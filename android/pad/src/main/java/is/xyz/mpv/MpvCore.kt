package `is`.xyz.mpv

import android.content.Context
import com.hongguotv.pad.Log

/** mpv 实例的极简封装：初始化一次、设置软解/音频输出，加载本地文件。 */
object MpvCore {
    private const val TAG = "MpvCore"
    @Volatile private var ready = false
    @Volatile private var current = ""
    @Volatile var version: String = ""
        private set

    /** 本机 ABI 有没有对应的 libmpv（v7a 缺库时不要往 VVC 页引，直接走原来的跳过逻辑） */
    fun nativeSupported(): Boolean = runCatching {
        android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86_64" }
    }.getOrDefault(false)

    @Synchronized
    fun ensure(context: Context): Boolean {
        if (ready) return true
        return runCatching {
            MPVLib.create(context.applicationContext)
            MPVLib.setOptionString("config", "no")
            MPVLib.setOptionString("terminal", "no")
            MPVLib.setOptionString("vo", "gpu")
            MPVLib.setOptionString("gpu-context", "android")
            MPVLib.setOptionString("gpu-api", "opengl")
            // 电视上很多 GPU 驱动实现不完整：dumb mode 用最少的着色器特性，兼容性最好。
            // 以前这里还有 gpu-sw=yes（软件 GL），真机上没有软件 GL 反而会黑屏，去掉。
            MPVLib.setOptionString("gpu-dumb-mode", "yes")
            MPVLib.setOptionString("ao", "audiotrack")
            MPVLib.setOptionString("hwdec", "no")
            // VVC 只有软解：吃满多核 + 允许 VO 丢帧，否则 1080p 在电视上会卡成幻灯片
            val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            MPVLib.setOptionString("vd-lavc-threads", cores.toString())
            MPVLib.setOptionString("framedrop", "vo")
            MPVLib.setOptionString("keep-open", "no")
            MPVLib.setOptionString("idle", "yes")
            MPVLib.init()
            version = runCatching { MPVLib.getPropertyString("mpv-version") }.getOrNull().orEmpty()
            ready = true
            Log.d(TAG, "mpv ready: $version abi=" + android.os.Build.SUPPORTED_ABIS.joinToString(","))
            true
        }.getOrElse {
            Log.e(TAG, "mpv init failed: ${it.message}", it)
            false
        }
    }

    @Synchronized
    fun load(file: String, audioFile: String? = null, fps: Double = 0.0) {
        // 同一路流只加载一次：两个 LaunchedEffect 同时就绪时会重复 loadfile
        val key = "$file|$audioFile"
        if (key == current) return
        current = key
        Log.d(TAG, "load video=$file audio=$audioFile fps=$fps")
        runCatching {
            // 裸流没有时间戳：必须用 container-fps-override（--fps 对 lavf 裸流不生效），
            // 否则 mpv 按 25fps 算时间轴，30fps 的片源会一直音画追赶 → 看起来就是卡
            if (fps > 1.0) {
                val f = String.format(java.util.Locale.US, "%.3f", fps)
                MPVLib.setOptionString("fps", f)
                MPVLib.setOptionString("container-fps-override", f)
            }
            MPVLib.command(arrayOf("loadfile", file, "replace"))
            // 独立音轨用 audio-add 挂，--audio-file 在这个 libmpv 构建里不生效
            if (audioFile != null) {
                // 文件刚 loadfile 时还没进 loaded 状态，audio-add 可能被拒；重试几次到成功
                Thread {
                    for (i in 0 until 6) {
                        try { Thread.sleep(400) } catch (_: InterruptedException) {}
                        val r = runCatching { MPVLib.command(arrayOf("audio-add", audioFile, "select", "1")) }
                        Log.d(TAG, "audio-add #$i ok=${r.isSuccess} ${r.exceptionOrNull()?.message ?: ""}")
                        if (r.isSuccess) return@Thread
                    }
                }.start()
            }
        }.onFailure { Log.e(TAG, "load failed", it) }
    }

    fun stop() {
        current = ""
        runCatching { MPVLib.command(arrayOf("stop")) }
    }
    fun pause(p: Boolean) = runCatching { MPVLib.setPropertyBoolean("pause", p) }
    fun seekTo(sec: Double) = runCatching { MPVLib.command(arrayOf("seek", sec.toString(), "absolute+exact")) }
    fun position(): Double = runCatching { MPVLib.getPropertyDouble("time-pos") ?: 0.0 }.getOrDefault(0.0)
    fun duration(): Double = runCatching { MPVLib.getPropertyDouble("duration") ?: 0.0 }.getOrDefault(0.0)
    fun isPaused(): Boolean = runCatching { MPVLib.getPropertyBoolean("pause") ?: false }.getOrDefault(false)
    fun statD(name: String): Double = runCatching { MPVLib.getPropertyDouble(name) ?: 0.0 }.getOrDefault(0.0)
    fun statI(name: String): Int = runCatching { MPVLib.getPropertyInt(name) ?: 0 }.getOrDefault(0)

    fun voConfigured(): Boolean =
        runCatching { MPVLib.getPropertyBoolean("vo-configured") ?: false }.getOrDefault(false)
    /** 没有画面时用来回报现场：VO 参数 / GL 版本 / ABI */
    fun renderDiag(): String {
        val vo = runCatching { MPVLib.getPropertyString("video-out-params") }.getOrNull().orEmpty()
        val gl = runCatching { MPVLib.getPropertyString("gl-version") }.getOrNull().orEmpty()
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return "vo=${vo.ifEmpty { "-" }} gl=${gl.ifEmpty { "-" }} abi=$abi mpv=$version"
    }
}
