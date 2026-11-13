package com.kaixinguo.pc

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * PC 端日志回传：每 10 分钟把 app.log 的增量发到服务器，便于主动发现异常。
 * 只发增量、单次最多 256KB；失败静默重试。
 */
object ClientLog {
    private const val INTERVAL_MS = 10 * 60 * 1000L

    fun start() {
        Thread {
            runCatching { Thread.sleep(60_000) }
            while (true) {
                runCatching { uploadOnce() }
                runCatching { Thread.sleep(INTERVAL_MS) }
            }
        }.apply { isDaemon = true; name = "client-log-upload" }.start()
    }

    private fun uploadOnce() {
        val dir = File(System.getenv("APPDATA") ?: ".", "KaixinguoPC")
        val file = File(dir, "app.log")
        if (!file.exists() || file.length() == 0L) return
        if (file.length() > 4 * 1024 * 1024) {
            runCatching { file.writeBytes(file.readBytes().takeLast(1024 * 1024).toByteArray()) }
        }
        val mark = File(dir, "log_offset.txt")
        var offset = runCatching { mark.readText().trim().toLong() }.getOrDefault(0L)
        if (offset > file.length()) offset = 0L
        if (file.length() - offset <= 0) return
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

        val base = BuildDefaults.PLAY_BASES.split(',')
            .map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return
        val os = System.getProperty("os.name") ?: "Windows"
        val device = (System.getenv("COMPUTERNAME") ?: "PC") + " ($os)"
        val payload = org.json.JSONObject()
            .put("device", device)
            .put("platform", "pc")
            .put("version", BuildInfo.VERSION)
            .put("lines", text.takeLast(200_000))
        val ok = runCatching {
            val conn = URL(base.trimEnd('/') + "/api/clientlog").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 6_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("X-Token", BuildDefaults.PLAY_TOKEN)
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
        if (ok) runCatching { mark.writeText(file.length().toString()) }
    }
}
