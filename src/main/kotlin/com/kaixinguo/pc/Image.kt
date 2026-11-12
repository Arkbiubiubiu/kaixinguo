package com.kaixinguo.pc

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 封面加载（PC 样张）。
 *
 * 红果有一部分封面是 HEIC，Skia 解不了，所以走「先存原图 → ffmpeg 转 PNG →
 * 再解」的兜底；转换结果落盘缓存，第二次直接读。
 */
object Covers {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 120
    }

    private val dir: File by lazy {
        File(System.getenv("APPDATA") ?: ".", "KaixinguoPC/covers").apply { mkdirs() }
    }

    fun peek(url: String): ImageBitmap? = synchronized(memory) { memory[url] }

    /** 清掉内存里的封面（设置页「清理缓存」用；否则删了文件界面还挂着旧图）。 */
    fun clearMemory() {
        synchronized(memory) { memory.clear() }
    }

    suspend fun load(url: String): ImageBitmap? {
        peek(url)?.let { return it }
        val file = File(dir, sha1(url))
        val bytes = withContext(Dispatchers.IO) {
            if (file.isFile && file.length() > 0) {
                file.readBytes()
            } else {
                runCatching {
                    client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) null else resp.body?.bytes()
                    }
                }.getOrNull()?.also { runCatching { file.writeBytes(it) } }
            }
        } ?: return null

        // 解码也必须在 IO 线程：原来在主线程解码，满墙封面同时解码会卡住 UI（审计 P1）
        var bitmap = withContext(Dispatchers.IO) { decode(bytes) }
        if (bitmap == null) {
            // HEIC 兜底：交给 ffmpeg 转 PNG
            val png = withContext(Dispatchers.IO) { Ffmpeg.heicToPng(bytes) }
            if (png != null) bitmap = withContext(Dispatchers.IO) { decode(png) }
        }
        if (bitmap != null) synchronized(memory) { memory[url] = bitmap }
        return bitmap
    }

    private fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

@Composable
fun RemoteImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Color = Palette.coverPlaceholder,
    fallback: String? = null,
) {
    var bitmap by remember(url) { mutableStateOf(Covers.peek(url)) }
    LaunchedEffect(url) {
        if (bitmap == null && url.isNotBlank()) bitmap = Covers.load(url)
    }
    Box(modifier.background(placeholder), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(bitmap = image, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = contentScale)
        } else {
            // 占位：旧版是居中的 Tv 图标（不显示文字首字，满墙「该」太脏）
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.Tv,
                contentDescription = null,
                tint = Palette.faint,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** 极简 ffmpeg 封装：目前只用来把 HEIC 转 PNG。 */
object Ffmpeg {

    /** ffmpeg 路径（找不到返回 null）。 */
    fun binary(): File? = cachedBin

    private val cachedBin: File? by lazy {
        val home = File(System.getProperty("user.dir"))
        // codeSource 可能是 <安装目录>/app/<jar>，也可能直接是 <安装目录>/app
        val code = runCatching {
            File(Ffmpeg::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        val dirs = listOfNotNull(code, code?.parentFile, home, File(home, "app"))
        val candidates = dirs.flatMap { d ->
            listOf(
                File(d, "resources/ffmpeg.exe"),
                File(d, "ffmpeg.exe"),
                File(d, "runtime/ffmpeg.exe"),
            )
        } + listOf(File(home, "runtime/ffmpeg.exe"))
        val found = candidates.firstOrNull { it.isFile }
        println("[ffmpeg] 路径=${found ?: "未找到"}")
        found
    }

    fun heicToPng(bytes: ByteArray): ByteArray? {
        val bin = cachedBin ?: return null
        val dir = File(System.getProperty("java.io.tmpdir"), "kxg-heic").apply { mkdirs() }
        val src = File.createTempFile("in-", ".heic", dir)
        val out = File.createTempFile("out-", ".png", dir)
        return try {
            src.writeBytes(bytes)
            val ok = ProcessBuilder(bin.absolutePath, "-y", "-loglevel", "error", "-i", src.absolutePath, "-frames:v", "1", out.absolutePath)
                .redirectErrorStream(true).start()
                .let { p -> p.inputStream.use { it.readBytes() }; p.waitFor() == 0 }
            if (ok && out.length() > 0) out.readBytes() else null
        } catch (_: Throwable) {
            null
        } finally {
            src.delete()
            out.delete()
        }
    }
}
