package com.hongguotv.pad

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 客户端日志回传：每 10 分钟把 app.log 里「上次没发过的部分」发到服务器。
 *
 * 目的：电视/平板出问题时，用户往往只能描述「黑屏/很慢」，我们拿不到现场。
 * 有了这条通道，服务端就能主动看到异常（取流失败、解码跳过、崩溃栈），
 * 不用等用户截图或连 adb。
 *
 * 约束：
 * - 只发增量（按字节偏移记住发到哪）；
 * - 单次最多 256KB，避免把隧道占满；
 * - 失败静默，下一轮再试；日志文件超过 2MB 时保留尾部。
 */
object LogUploader {
    private const val PREF = "log_upload"
    private const val KEY_OFFSET = "sent_offset"
    private const val INTERVAL_MS = 10 * 60 * 1000L

    fun start(context: Context) {
        val app = context.applicationContext
        Thread {
            // 启动后先等 45 秒（别和首屏抢带宽），之后每 10 分钟一轮
            runCatching { Thread.sleep(45_000) }
            while (true) {
                runCatching { uploadOnce(app) }
                runCatching { Thread.sleep(INTERVAL_MS) }
            }
        }.apply { isDaemon = true; name = "client-log-upload" }.start()
    }

    private fun uploadOnce(context: Context) {
        val file = File(context.filesDir, "app.log")
        if (!file.exists() || file.length() == 0L) return
        // 文件过大：只保留尾部 2MB，避免越滚越大
        if (file.length() > 2 * 1024 * 1024) {
            runCatching {
                val keep = file.readBytes().takeLast(1024 * 1024).toByteArray()
                file.writeBytes(keep)
                context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_OFFSET, 0L).apply()
            }
        }
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        var offset = prefs.getLong(KEY_OFFSET, 0L)
        if (offset > file.length()) offset = 0L
        val available = file.length() - offset
        if (available <= 0) return
        val start = maxOf(offset, file.length() - 256 * 1024)
        val text = runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray((file.length() - start).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }.getOrNull() ?: return
        if (text.isBlank()) return

        val base = BuildConfig.DEFAULT_PLAY_BASES.split(',')
            .map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return
        val payload = JSONObject()
            .put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            .put("platform", if (context.packageName.endsWith("nativeapp")) "android-tv" else "android-pad")
            .put("version", BuildConfig.VERSION_NAME)
            .put("lines", text.takeLast(200_000))
        val ok = runCatching {
            val conn = URL(base.trimEnd('/') + "/api/clientlog").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 6_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("X-Token", BuildConfig.PLAY_TOKEN)
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
        if (ok) {
            prefs.edit().putLong(KEY_OFFSET, file.length()).apply()
        }
    }
}
