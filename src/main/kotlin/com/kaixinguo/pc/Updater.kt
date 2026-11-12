package com.kaixinguo.pc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 版本号唯一来源：发版时只改这里（与 build.gradle.kts 的 packageVersion 对齐）。 */
object BuildInfo {
    const val VERSION = "1.2.28"
    const val CODE = 10228
}

data class PcUpdate(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val path: String,
    val size: Long,
    val sha256: String,
    val notes: String,
)

/**
 * PC 在线更新。
 *
 * 更新源 = 樱花穿透四线（主，实测 1.3-1.5MB/s）+ cpolar 动态兜底；
 * 清单和包都走公网隧道，与外部用户完全一致（2026-11-11 CF 的 h2 对 hgtv 域名
 * 被针对性 reset，CF 不再作为下载源）。
 * 清单 path 是安装包文件名，用同一个 base 拼下载地址 —— 换版本不用改代码。
 * 下载完校验 sha256，再交给一个临时 .bat：等 msiexec 装完、自动把新版本拉起来。
 * 设置里的「更新源」字段保留：留空用上面的默认源，填了就覆盖（自建多入口时用）。
 */
object PcUpdater {

    /**
     * 下载镜像：樱花穿透四线（主，实测 1.3-1.5MB/s）。
     * cpolar 域名会漂移，check 时从 update-mirrors.json 动态取，作为兜底。
     * 2026-11-11 起 CF 的 HTTP/2 对 hgtv 域名被针对性 reset（他站 CF 正常），
     * CF 不再作为下载源；设置里的「更新源」仍可覆盖（自建多入口时用）。
     */
    private val SAKURA = listOf(
        "https://frp-bar.com:44468",
        "https://dx.frp-bar.com:44468",
        "https://yd.frp-bar.com:44468",
        "https://lt.frp-bar.com:44468",
    )

    private val SAKURA_HOSTS = setOf("frp-bar.com", "dx.frp-bar.com", "yd.frp-bar.com", "lt.frp-bar.com")

    /** 樱花 TLS 终止在本地 8081，自签证书；只对樱花域名放行，其他域名仍严格校验。 */
    private val sakuraTls: Pair<javax.net.ssl.SSLSocketFactory, javax.net.ssl.HostnameVerifier> by lazy {
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(tm), java.security.SecureRandom())
        ctx.socketFactory to javax.net.ssl.HostnameVerifier { _, _ -> true }
    }

    /** 动态镜像（樱花 + cpolar），每次 check 刷新。 */
    private var mirrors: List<String> = emptyList()

    private fun openConn(url: String, connectMs: Int, readMs: Int): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            instanceFollowRedirects = true
        }
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
        if (conn is javax.net.ssl.HttpsURLConnection && host in SAKURA_HOSTS) {
            conn.sslSocketFactory = sakuraTls.first
            conn.hostnameVerifier = sakuraTls.second
        }
        return conn
    }

    /** 留空 = 默认（樱花 + cpolar 动态发现）；设置里填了「更新源」就只用它。 */
    private fun configuredBase(): String = PcStore.updateBase.trim().trimEnd('/')

    private fun discoverMirrors() {
        if (configuredBase().isNotBlank()) return
        for (b in (SAKURA + mirrors).distinct()) {
            val list = runCatching {
                val conn = openConn("$b/update-mirrors.json", 5_000, 7_000).apply {
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val arr = o.optJSONArray("mirrors") ?: return@runCatching null
                    (0 until arr.length())
                        .map { arr.optString(it).trim().trimEnd('/') }
                        .filter { it.startsWith("https://") || it.startsWith("http://") }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (!list.isNullOrEmpty()) {
                mirrors = list
                return
            }
        }
    }

    private fun bases(): List<String> =
        if (configuredBase().isNotBlank()) listOf(configuredBase())
        else (mirrors + SAKURA).distinct()

    private fun fetchManifest(root: String): PcUpdate? {
        val conn = openConn("$root/version-pc.json", 8_000, 12_000).apply {
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            val path = o.optString("path")
            val full = o.optString("url").takeIf { it.startsWith("http") }
                ?: if (path.isNotEmpty()) "$root/$path" else ""
            if (full.isEmpty()) return null
            return PcUpdate(
                versionCode = o.optInt("versionCode", 0),
                versionName = o.optString("versionName"),
                url = full,
                path = path,
                size = o.optLong("size", 0L),
                sha256 = o.optString("sha256"),
                notes = o.optString("notes"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 更新源连不上。必须和「已是最新」分开报，不能谎报（安卓 2.2.73 踩过同一个坑）。 */
    class CheckException(message: String) : Exception(message)

    /** 有新版返回信息；已是最新返回 null；连不上抛 [CheckException]。 */
    suspend fun check(): PcUpdate? = withContext(Dispatchers.IO) {
        discoverMirrors()
        for (b in bases()) {
            val info = runCatching { fetchManifest(b) }.getOrNull()
            if (info != null) return@withContext if (info.versionCode > BuildInfo.CODE) info else null
        }
        throw CheckException("更新源连不上")
    }

    /** 下载整包：按「清单给定地址 → 镜像列表」逐个尝试，校验大小和 sha256。 */
    suspend fun download(info: PcUpdate, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val dir = File(System.getProperty("java.io.tmpdir"), "kaixinguo-update").apply { mkdirs() }
            val out = File(dir, "kaixinguo-${info.versionName}.msi")
            val urls = buildList {
                add(info.url)
                for (b in bases()) if (info.path.isNotEmpty()) add("$b/${info.path}")
            }.distinct()
            for (u in urls) {
                out.delete()
                val ok = runCatching { downloadOne(u, out, info, onProgress) }.getOrDefault(false)
                if (ok) return@withContext out
            }
            null
        }

    private fun downloadOne(url: String, out: File, info: PcUpdate, onProgress: (Int) -> Unit): Boolean {
        val conn = openConn(url, 15_000, 60_000)
        try {
            if (conn.responseCode !in 200..299) return false
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            var read = 0L
            var last = -1
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val p = (read * 100 / total).toInt()
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (info.size > 0 && out.length() != info.size) return false
        if (info.sha256.isNotBlank()) {
            val digest = MessageDigest.getInstance("SHA-256")
            out.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(info.sha256, ignoreCase = true)) return false
        }
        return true
    }

    /**
     * 装包：写一个临时 .bat，跑完安装再把程序拉起来，然后自己退出。
     *
     * 安装用 `msiexec /a`（管理解包）+ robocopy 覆盖当前目录 —— 免提权，
     * 这是这台机器验证过的唯一不弹 UAC 的路子；`msiexec /i` 机器级安装没有
     * 管理员权限会静默失败（1925），不能用。
     *
     * **.bat 必须按系统 ANSI 码页（中文系统=GBK）写**：cmd 按 ANSI 读脚本，
     * Java 18+ 默认 UTF-8，用 UTF-8 写「开心果」就是乱码 `寰╂灉鏋?.exe`
     * （1.2.11 事故：更新装完 start 找不到 exe）。MSI 路径全 ASCII 不受影响，
     * 但 exe 路径和 /a 解包出的「开心果」目录都是中文，绕不过去。
     */
    fun installAndRestart(msi: File) {
        val exe = ProcessHandle.current().info().command().orElse("")
        val dir = File(exe).parentFile?.absolutePath.orEmpty()
        val relaunch = if (exe.contains("java", ignoreCase = true)) "" else
            "start \"\" \"$exe\"\r\n"
        val parent = msi.parentFile?.absolutePath ?: System.getProperty("java.io.tmpdir")
        // /a 会把 MSI 自身也复制进 TARGETDIR —— 指向 MSI 所在目录会自我复制失败（1603），
        // 必须给一个独立的 stage 子目录
        val stage = "$parent\\stage"
        val log = File(parent, "install.log")
        val ansi = runCatching { Charset.forName(System.getProperty("sun.jnu.encoding")) }
            .getOrDefault(Charset.forName("GBK"))
        val batText =
            "@echo off\r\n" +
                "echo [%date% %time%] update start >> \"$log\"\r\n" +
                // 等正在运行的旧版退出，exe 文件才不会被 robocopy 锁住
                "ping -n 4 127.0.0.1 >nul\r\n" +
                "start \"\" /wait msiexec /a \"${msi.absolutePath}\" /qn TARGETDIR=\"$stage\"\r\n" +
                "echo msiexec exit %errorlevel% >> \"$log\"\r\n" +
                "robocopy \"$stage\\开心果\" \"$dir\" /MIR /R:2 /W:2 >> \"$log\"\r\n" +
                "echo robocopy exit %errorlevel% >> \"$log\"\r\n" +
                "if not exist \"$exe\" goto fail\r\n" +
                relaunch +
                "goto done\r\n" +
                ":fail\r\n" +
                "echo exe missing after install >> \"$log\"\r\n" +
                ":done\r\n"
        val bat = File(msi.parentFile, "install.bat")
        bat.writeText(batText, ansi)
        runCatching {
            ProcessBuilder("cmd", "/c", bat.absolutePath)
                .redirectErrorStream(true)
                .start()
        }
    }
}

/**
 * 设置页「关于」里的一行：当前版本 + 检查更新 + 下载进度。
 * 状态全在本组件内，离开设置页就丢，不污染全局。
 */
@Composable
fun UpdateRow() {
    var state by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<PcUpdate?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("开心果 PC 版 ${BuildInfo.VERSION}", color = Palette.text, fontSize = 14.sp)
            if (state.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(state, color = Palette.faint, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            info != null -> OutlineButton("立即更新 ${info!!.versionName}") {
                val target = info ?: return@OutlineButton
                if (busy) return@OutlineButton
                busy = true
                state = "正在下载…"
                scope.launch {
                    val f = PcUpdater.download(target) { p -> state = "正在下载 $p%" }
                    busy = false
                    if (f == null) {
                        state = "下载失败，稍后再试"
                    } else {
                        state = "下载完成，正在启动安装程序…"
                        PcUpdater.installAndRestart(f)
                        kotlin.system.exitProcess(0)
                    }
                }
            }
            else -> OutlineButton(if (busy) "检查中…" else "检查更新") {
                if (busy) return@OutlineButton
                busy = true
                state = "正在检查…"
                scope.launch {
                    val r = runCatching { PcUpdater.check() }
                    busy = false
                    val found = r.getOrNull()
                    if (found == null && r.isFailure) {
                        state = "检查失败：连不上更新源，请重试"
                    } else if (found == null) {
                        state = "已是最新版本"
                    } else {
                        info = found
                        state = "发现新版本 ${found.versionName} · " +
                            "%.1f MB".format(found.size / 1024.0 / 1024.0)
                    }
                }
            }
        }
    }
}

