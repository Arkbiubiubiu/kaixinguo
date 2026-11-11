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
    const val VERSION = "1.2.14"
    const val CODE = 10214
}

data class PcUpdate(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val notes: String,
)

/**
 * PC 在线更新。
 *
 * 唯一更新源 = 自建服务器（https://hgtv.fabao.xin，Cloudflare 边缘缓存 APK，
 * 大陆实测 0.1~2 MB/s；GitHub release 大陆只有几十 KB/s，2026-11-11 起弃用）。
 * 清单 path 是安装包文件名，用同一个 base 拼下载地址 —— 换版本不用改代码。
 * 下载完校验 sha256，再交给一个临时 .bat：等 msiexec 装完、自动把新版本拉起来。
 * 设置里的「更新源」字段保留：留空用上面的默认源，填了就覆盖（自建多入口时用）。
 */
object PcUpdater {

    /** 留空 = 自建服务器；设置里填了「更新源」就用用户自己的。 */
    private fun base(): String =
        PcStore.updateBase.trim().trimEnd('/').ifBlank {
            "https://hgtv.fabao.xin"
        }

    private fun fetchManifest(): PcUpdate? {
        val root = base()
        val conn = (URL("$root/version-pc.json").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
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
        val info = runCatching { fetchManifest() }.getOrNull()
            ?: throw CheckException("更新源连不上")
        if (info.versionCode > BuildInfo.CODE) info else null
    }

    suspend fun download(info: PcUpdate, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(System.getProperty("java.io.tmpdir"), "kaixinguo-update").apply { mkdirs() }
                val out = File(dir, "kaixinguo-${info.versionName}.msi")
                if (out.exists()) out.delete()
                val conn = (URL(info.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                try {
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
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
                if (info.size > 0 && out.length() != info.size) error("大小不对")
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
                    if (!hex.equals(info.sha256, ignoreCase = true)) error("校验失败")
                }
                out
            }.getOrNull()
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

