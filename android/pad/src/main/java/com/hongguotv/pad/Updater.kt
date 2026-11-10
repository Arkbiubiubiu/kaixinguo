package com.hongguotv.pad

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 线上最新版信息（version-tv.json / version-hgpad.json 的解析结果）。 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val notes: String,
)

/**
 * 在线更新。
 *
 * 唯一更新源 = 自建服务器（Cloudflare 隧道回源 /srv/hgtv-update）。
 * GitHub release 从大陆实测只有几十 KB/s 且经常握手失败，已弃用；
 * 「局域网优先」也取消：域名走 Cloudflare 边缘，命中缓存后不回源，
 * 内外网速度同一水平，没必要分叉。
 *
 * 清单格式（服务端 /srv/hgtv-update 下）：
 * `{versionCode, versionName, url, path, size, sha256, notes}`，`url` 为空时
 * 用 `path` 拼；`sha256` 用于整包校验，对不上就不装（防止下到半截）。
 */
object Updater {

    /**
     * 唯一更新源。构建参数 `updateBases` 可覆盖（自建集群多入口时用），公开包不带参数。
     */
    private const val UPDATE_BASE = "https://hgtv.fabao.xin"

    private val BASES = BuildConfig.UPDATE_BASES
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .map { it to 8_000 }
        .ifEmpty { listOf(UPDATE_BASE to 8_000) }

    /** 两个包共用一套代码：按包名挑清单，免得依赖 BuildConfig 的 flavor 字段 */
    private fun manifestOf(pkg: String): String =
        if (pkg.endsWith("nativeapp")) "version-tv.json" else "version-hgpad.json"

    fun versionCode(context: Context): Int = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(0)

    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /**
     * 拉清单：有新版返回 UpdateInfo，已是最新返回 null。
     * 全部地址都连不上时抛 [CheckException]——调用方要把「检查失败」和
     * 「已是最新」分开显示，绝不能把连不上谎报成最新（外网 2.2.73 的事故）。
     *
     * Cloudflare 隧道偶尔挑到断边，表现是一次 TLS handshake 超时；
     * 每个地址重试 3 次（间隔递增）再判定失败。
     */
    class CheckException(message: String) : Exception(message)

    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val name = manifestOf(context.packageName)
        val cur = versionCode(context)
        var lastError: String? = null
        for ((base, timeout) in BASES) {
            var attempt = 0
            while (attempt < 3) {
                attempt++
                val info = runCatching { fetch("$base/$name", base, timeout) }.getOrNull()
                if (info != null) {
                    // 能拿到清单就说明通道是通的：不比当前新就直接结束
                    return@withContext if (info.versionCode > cur) info else null
                }
                lastError = "更新源连不上（第 $attempt 次）"
                if (attempt < 3) delay(700L * attempt)
            }
        }
        throw CheckException(lastError ?: "更新源连不上")
    }

    private fun fetch(url: String, base: String, timeoutMs: Int): UpdateInfo {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) error("清单 HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            val path = o.optString("path")
            val full = o.optString("url").takeIf { it.startsWith("http") }
                ?: if (path.isNotEmpty()) "$base/$path" else ""
            if (full.isEmpty()) error("清单缺少下载地址")
            return UpdateInfo(
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

    /**
     * 下载整包到 files/update/update.apk，校验 sha256。失败返回 null。
     *
     * **分段并行**：Cloudflare 免费隧道单连接吞吐有限（实测 0.1~0.3 MB/s），
     * 6 条分段连接（Range 请求）能叠到 1 MB/s 上下；每段带断点续传，
     * 段中断了从段内 offset 接着拉，不重下整包。段数少（文件 < 8MB）时退回单连接。
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "update").apply { mkdirs() }
        val out = File(dir, "update.apk")
        runCatching {
            if (out.exists()) out.delete()
            out.createNewFile()
            // 用 RandomAccessFile 按段写；进度按字节聚合
            val total = if (info.size > 0) info.size else contentLength(info.url)
            if (total <= 0) error("无法获知安装包大小")
            val segments = if (total >= 8L * 1024 * 1024) 6 else 1
            val segLen = total / segments
            val done = java.util.concurrent.atomic.AtomicLong(0)
            val lastPct = java.util.concurrent.atomic.AtomicInteger(-1)
            val failed = java.util.concurrent.atomic.AtomicBoolean(false)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(segments)
            try {
                val futures = (0 until segments).map { i ->
                    pool.submit(java.util.concurrent.Callable {
                        val a = i.toLong() * segLen
                        val b = if (i == segments - 1) total - 1 else (i + 1).toLong() * segLen - 1
                        var pos = a
                        var attempt = 0
                        while (pos <= b && attempt < 6 && !failed.get()) {
                            attempt++
                            try {
                                val conn = (URL(info.url).openConnection() as HttpURLConnection).apply {
                                    connectTimeout = 15_000
                                    readTimeout = 30_000
                                    instanceFollowRedirects = true
                                    setRequestProperty("Range", "bytes=$pos-$b")
                                    setRequestProperty("User-Agent", "Dalvik/2.1.0")
                                }
                                try {
                                    if (conn.responseCode !in 200..299) error("分段 HTTP ${conn.responseCode}")
                                    conn.inputStream.use { input ->
                                        java.io.RandomAccessFile(out, "rw").use { raf ->
                                            raf.seek(pos)
                                            val buf = ByteArray(128 * 1024)
                                            var lastReadAt = System.currentTimeMillis()
                                            while (pos <= b) {
                                                val want = minOf(buf.size, (b - pos + 1).toInt())
                                                val n = input.read(buf, 0, want)
                                                if (n <= 0) break
                                                raf.write(buf, 0, n)
                                                pos += n
                                                val d = done.addAndGet(n.toLong())
                                                val p = (d * 100 / total).toInt().coerceIn(0, 100)
                                                val prev = lastPct.getAndSet(p)
                                                if (p != prev) onProgress(p)
                                                if (System.currentTimeMillis() - lastReadAt > 45_000) {
                                                    error("分段下载超时")
                                                }
                                                lastReadAt = System.currentTimeMillis()
                                            }
                                        }
                                    }
                                } finally {
                                    conn.disconnect()
                                }
                            } catch (e: Exception) {
                                if (attempt >= 6) failed.set(true)
                                // 线程池里不是协程：用 Thread.sleep 退避
                                Thread.sleep(500L * attempt)
                            }
                        }
                        if (pos <= b) failed.set(true)
                    })
                }
                futures.forEach { it.get() }
                if (done.get() != total) error("下载不完整（${done.get()}/$total），请重试")
            } finally {
                pool.shutdown()
            }
            if (info.sha256.isNotEmpty() && !info.sha256.equals(sha256Of(out), ignoreCase = true)) {
                out.delete()
                error("安装包校验失败（可能下到半截），请重试")
            }
            out
        }.getOrNull()
    }

    /** 探一下整包大小（HEAD 不被所有源支持，用 Range 0-0 的 Content-Range 兜底）。 */
    private fun contentLength(url: String): Long {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("User-Agent", "Dalvik/2.1.0")
        }
        try {
            if (conn.responseCode == 206) {
                // Content-Range: bytes 0-0/36591346
                conn.getHeaderField("Content-Range")?.split("/")?.lastOrNull()?.toLongOrNull()?.let { return it }
            }
            return conn.contentLengthLong.takeIf { it > 0 } ?: 0L
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 系统是否还允许本应用拉起安装器（Android 8+ 需要「安装未知应用」授权）。
     *
     * 注意：没授权时系统多半是**静默拒绝** —— startActivity 不抛异常、安装界面也不弹，
     * 表现就是更新弹窗停在「校验并准备安装…」不动，用户完全不知道发生了什么。
     * 所以必须主动查，查不到就明确告诉用户去哪儿开。
     */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 跳到「安装未知应用」授权页；拿不到就退到本应用详情页。 */
    fun installSettingsIntent(context: Context): Intent {
        val direct = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val detail = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (direct.resolveActivity(context.packageManager) != null) direct else detail
    }

    /** 拉起系统安装器（FileProvider 授权，Android 7+ 必须）。 */
    fun install(context: Context, file: File): Boolean = runCatching {
        if (!canInstall(context)) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // 有些电视/盒子上的安装器只认 ClipData 里的 uri，
            // 只设 data 会被静默拒绝（startActivity 不报错、界面不弹）
            clipData = android.content.ClipData.newRawUri("", uri)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

/**
 * 更新弹窗：版本信息 + 更新说明 + 下载进度，下载完直接拉系统安装器。
 *
 * 全程只做两件事：把整包下到私有目录、把 Uri 交给系统安装器；不申请 root、
 * 不走静默安装（那需要系统签名）。
 */
@Composable
fun UpdateDialog(info: UpdateInfo, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    // 需要先去系统设置开「安装未知应用」权限
    var needPerm by remember { mutableStateOf(false) }
    // 已经下好的安装包：留着，重试安装时不用再下一遍
    var apkFile by remember { mutableStateOf<java.io.File?>(null) }
    val sizeMb = if (info.size > 0) String.format("%.1f", info.size / 1024.0 / 1024.0) else "?"

    Dialog(onDismissRequest = { if (progress < 0) onClose() }) {
        // Dialog 是独立 window，主窗口那套「整屏 Canvas 焦点框」在这里必然错位；
        // 弹窗内的控件改用本地绘制的焦点框
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.LocalIndication provides TvDialogIndication,
        ) {
        Column(
            Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Panel)
                .padding(22.dp),
        ) {
            Text(
                "发现新版本 ${info.versionName}",
                color = TextMain,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "当前 ${Updater.versionName(context)} · 新包 $sizeMb MB",
                color = TextDim,
                fontSize = 12.sp,
            )
            if (info.notes.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    info.notes,
                    color = TextDim,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(18.dp))
            if (progress >= 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x33FFFFFF)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress / 100f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Red),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (progress >= 100) "校验并准备安装…" else "正在下载 $progress%",
                    color = TextDim,
                    fontSize = 12.sp,
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Red, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "稍后",
                    color = TextDim,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x14FFFFFF))
                        // 永远可点：下载/安装卡住时，用户至少能关掉弹窗退出，
                        // 之前这里被 progress 禁用，一卡住连关都关不掉。
                        .clickable { onClose() }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
                Text(
                    when {
                        needPerm -> "去设置"
                        progress >= 0 && progress < 100 -> "下载中…"
                        progress >= 100 -> "准备安装…"
                        apkFile != null -> "重新安装"
                        else -> "立即更新"
                    },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (progress < 0 || needPerm) Red else Color(0x33FFFFFF))
                        .clickable(enabled = progress < 0 || needPerm) {
                            if (needPerm) {
                                runCatching { context.startActivity(Updater.installSettingsIntent(context)) }
                                return@clickable
                            }
                            // 已经下好包了：直接重试安装，别让用户再等一次 4MB 下载
                            val cached = apkFile
                            if (cached != null && cached.exists() && cached.length() > 0) {
                                error = null
                                if (!Updater.canInstall(context)) {
                                    needPerm = true
                                    error = "电视还没允许本应用安装软件，点右边「去设置」打开开关后重试"
                                } else if (!Updater.install(context, cached)) {
                                    error = "无法拉起安装器，请检查「安装未知应用」权限"
                                } else {
                                    error = "已调起安装器。如果电视上没有出现安装界面，点右边「重新安装」再试"
                                }
                                return@clickable
                            }
                            error = null
                            needPerm = false
                            progress = 0
                            scope.launch {
                                val file = Updater.download(context, info) { p -> progress = p }
                                if (file == null) {
                                    progress = -1
                                    error = "下载失败，请检查网络后重试"
                                } else {
                                    progress = 100
                                    apkFile = file
                                    if (!Updater.canInstall(context)) {
                                        // 系统静默拒绝，安装界面根本不会弹 —— 直接说明白并给入口
                                        progress = -1
                                        needPerm = true
                                        error = "电视还没允许本应用安装软件，点右边「去设置」打开开关后重试"
                                    } else if (!Updater.install(context, file)) {
                                        progress = -1
                                        error = "无法拉起安装器，请检查「安装未知应用」权限"
                                    } else {
                                        // 不要立刻关弹窗：电视上安装器偶尔不会来到前台，
                                        // 关掉之后用户既看不到安装界面、也没有重试入口，
                                        // 只能重新下载一遍（这就是「第一次点安装没用」的由来）
                                        progress = -1
                                        error = "已调起安装器。如果电视上没有出现安装界面，点右边「重新安装」再试"
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
        }  // CompositionLocalProvider
    }
}
