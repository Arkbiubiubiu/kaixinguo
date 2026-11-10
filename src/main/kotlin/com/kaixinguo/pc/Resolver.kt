package com.kaixinguo.pc

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本机取流服务（随安装包分发的 streamsrv.exe，只监听 127.0.0.1）。
 *
 * 为什么需要它：上游取流网关对「一台匿名设备」只放行第一次请求，客户端拿着
 * 固定身份永远拿不到播放地址；这个小服务会在本机注册一台新设备、取完地址就把
 * 身份作废，等价于官方 App 首次安装时的设备注册，不经过任何远程服务器。
 *
 * 用户自己在「设置 → 取流服务」填了地址时，不会启动/使用本机服务。
 */
object Resolver {

    private const val PORT = 38120
    private const val TOKEN = "kxg-local"

    var base: String = "http://127.0.0.1:$PORT"
        private set
    var token: String = TOKEN
        private set

    private var process: Process? = null

    private val binary: File? by lazy {
        val home = File(System.getProperty("user.dir"))
        val code = runCatching {
            File(Resolver::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        val dirs = listOfNotNull(code, code?.parentFile, home, File(home, "app"))
        val candidates = dirs.flatMap { d ->
            listOf(
                File(d, "resources/streamsrv.exe"),
                File(d, "streamsrv.exe"),
                File(d, "runtime/streamsrv.exe"),
            )
        }
        val found = candidates.firstOrNull { it.isFile }
        println("[resolver] 路径=${found ?: "未找到"}")
        found
    }

    /** 启动本机服务（已启动/找不到程序时静默跳过），最多等 4 秒就绪。 */
    fun start() {
        if (process?.isAlive == true) return
        val exe = binary ?: return
        val dir = File(File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "KaixinguoPC"), "resolver")
        runCatching { dir.mkdirs() }
        val pb = ProcessBuilder(exe.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(File(dir, "resolver.log")))
        pb.environment()["LISTEN"] = "127.0.0.1:$PORT"
        pb.environment()["PROFILE_DIR"] = dir.absolutePath
        pb.environment()["TOKEN"] = TOKEN
        process = runCatching { pb.start() }.getOrNull() ?: return
        waitReady()
    }

    fun stop() {
        runCatching { process?.destroy() }
        process = null
    }

    private fun waitReady() {
        val deadline = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                val c = URL("$base/health").openConnection() as HttpURLConnection
                c.connectTimeout = 400
                c.readTimeout = 400
                val code = c.responseCode
                c.disconnect()
                code == 200
            }.getOrDefault(false)
            if (ok) return
            Thread.sleep(120)
        }
    }
}
