package com.kaixinguo.pc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * 播放内核：**FFmpeg 解码 → Compose 画帧 + 声卡出声**。
 *
 * 为什么不用 JavaFX MediaView：片源是 HEVC，JavaFX 只解 H.264/AAC。
 * 这里两路 FFmpeg：
 * - 视频 `-f rawvideo -pix_fmt bgra` 出帧，喂给 Skia Bitmap（复用像素内存）
 * - 声音 `-f s16le` 喂 SourceDataLine，**音频播放位置就是主时钟**
 *
 * 起播/切集/拖动 = 带 `-ss` 重启两个进程；暂停 = 停进程记位置。
 */
class VideoEngine(private val sourceUrl: String) {

    data class Info(
        val width: Int,
        val height: Int,
        val fps: Double,
        val hasAudio: Boolean,
        val durationMs: Long,
    )

    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    var playing by mutableStateOf(false)
        private set

    var positionMs: Long = 0L
        private set

    var info: Info? = null
        private set

    var onEnded: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pumpJob: Job? = null

    private var videoProc: Process? = null
    private var audioProc: Process? = null
    private var audioLine: SourceDataLine? = null
    private var audioThread: Thread? = null

    private var baseMs = 0L
    private var baseNanos = 0L

    @Volatile
    private var pausedAtMs = 0L

    @Volatile
    private var stopping = false

    /** 本次结束的解码进程退出码：0=自然读到结尾(真播完)；非0=异常中断。供播放器判断“是否该跳下一集”。 */
    @Volatile
    var lastEndExitCode: Int = -999
        private set

    /** 本次起播是否已经画出第一帧（冷启动首帧可能很慢，未画出前不按“落后”丢帧）。 */
    @Volatile
    private var firstPainted = false

    private var volume = 1f
    private var speed = 1f

    private var outHeight = 720
    private var outWidth = 1280

    // 16 帧缓冲（约 0.6s）吸收短时网络/解码抖动；原来 6 帧偏小，抖一下就见底卡顿
    private val queue = ArrayBlockingQueue<TimedFrame>(16)
    private lateinit var bitmap: Bitmap

    private class TimedFrame(val atMs: Long, val bytes: ByteArray)

    // ---------------- 探测 ----------------

    fun probe(): Info {
        val exe = Ffmpeg.binary() ?: error("缺少 ffmpeg")
        val p = ProcessBuilder(exe.absolutePath, "-hide_banner", "-i", sourceUrl)
            .redirectErrorStream(true).start()
        val text = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")
        p.waitFor(8, TimeUnit.SECONDS)
        p.destroy()

        val duration = Regex("Duration: (\\d+):(\\d+):(\\d+\\.\\d+)").find(text)?.let { m ->
            val (h, mnt, s) = m.destructured
            (h.toLong() * 3600 + mnt.toLong() * 60) * 1000 + (s.toDouble() * 1000).toLong()
        } ?: 0L

        val videoLine = text.lineSequence().firstOrNull { it.contains("Video: ") }.orEmpty()
        val size = Regex("(\\d{2,5})x(\\d{2,5})").find(videoLine)?.let {
            it.groupValues[1].toInt() to it.groupValues[2].toInt()
        } ?: (1280 to 720)
        val fps = Regex("([\\d.]+) fps").find(videoLine)?.groupValues?.get(1)?.toDoubleOrNull() ?: 25.0
        val hasAudio = text.lineSequence().any { it.contains("Audio: ") }

        val srcH = size.second.coerceAtLeast(2)
        val srcW = size.first.coerceAtLeast(2)
        outHeight = minOf(720, srcH)
        outWidth = (srcW.toDouble() * outHeight / srcH).toInt().let { it - (it % 2) }

        val result = Info(srcW, srcH, fps.coerceIn(1.0, 60.0), hasAudio, duration)
        info = result
        return result
    }

    // ---------------- 控制 ----------------

    fun start(atMs: Long = 0L) {
        scope.launch { playFrom(atMs.coerceAtLeast(0L)) }
    }

    private suspend fun playFrom(atMs: Long) {
        stopProcesses()
        stopping = false
        baseMs = atMs
        baseNanos = System.nanoTime()
        positionMs = atMs
        pausedAtMs = atMs
        // 先不起「在播」：等视频首帧到了再放行，否则声音先走、时钟先跑，
        // 迟到的视频帧会被 pumpLoop 整段丢掉（画面定住、声音正常）。
        playing = false
        queue.clear()
        firstPainted = false

        val i = info ?: return
        if (!::bitmap.isInitialized) {
            bitmap = Bitmap().apply {
                allocPixels(ImageInfo(outWidth, outHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
            }
        }
        startVideo(atMs, i)
        pumpJob?.cancel()
        pumpJob = scope.launch { pumpLoop() }
        scope.launch { readFrames(i) }

        // 等首帧（最多 20 秒）：冷启动首个视频解码可能 ~10s；20s 有界，超时仍退化为「只有声音」，防空视频卡死。
        val t0 = System.currentTimeMillis()
        val deadline = t0 + 20_000
        while (!stopping && queue.isEmpty() &&
            videoProc?.isAlive == true && System.currentTimeMillis() < deadline
        ) {
            kotlinx.coroutines.delay(20)
        }
        if (stopping) return
        println("[player] 首帧等待 ${System.currentTimeMillis() - t0}ms（队列 ${queue.size}）")
        // 视频和声音从同一时刻起步：baseNanos 重置，声轨时钟也从 0 开始
        baseNanos = System.nanoTime()
        playing = true
        startAudio(atMs)
    }

    fun pause() {
        if (!playing) return
        pausedAtMs = positionMs
        playing = false
        stopping = true
        stopProcesses()
    }

    fun resume() {
        if (playing) return
        start(pausedAtMs)
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0L, maxOf(0L, (info?.durationMs ?: 0L) - 500))
        val wasPlaying = playing
        stopping = true
        stopProcesses()
        pausedAtMs = target
        positionMs = target
        playing = false
        if (wasPlaying) start(target) else frame = frame
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        runCatching { audioLine?.let { applyGain(it) } }
    }

    fun setSpeed(s: Float) {
        speed = s.coerceIn(0.5f, 2f)
        if (playing) {
            val at = positionMs
            stopping = true
            stopProcesses()
            start(at)
        }
    }

    fun release() {
        stopping = true
        stopProcesses()
        scope.cancel()
    }

    // ---------------- 时钟 / 帧 ----------------

    private fun clockMs(): Long {
        val line = audioLine
        val elapsed = if (line != null) {
            (line.longFramePosition * 1000.0 / 48_000.0).toLong()
        } else {
            (System.nanoTime() - baseNanos) / 1_000_000L
        }
        return baseMs + (elapsed * speed).toLong()
    }

    /**
     * 真实已播内容的绝对毫秒（按挂钟从本集起播算起）。
     * 只用于「是否播到片尾」的判定：音频时钟有起播延迟会略慢，用这个挂钟兜住，
     * 避免画面已经放到片尾、却因时钟偏慢被误判成断流。不参与画面/声音同步。
     */
    fun playbackElapsedMs(): Long = baseMs + (System.nanoTime() - baseNanos) / 1_000_000L

    private suspend fun pumpLoop() {
        while (true) {
            if (playing) {
                if (!firstPainted) {
                    // 首帧门控兜底：还没画出第一帧时，不按“落后”丢帧——只要有帧就取最新一帧画出来，
                    // 避免冷启动解码跟不上音轨时「画面冻住、声音在走」。
                    var newest: TimedFrame? = null
                    while (true) {
                        val h = queue.poll() ?: break
                        newest = h
                    }
                    val f = newest
                    if (f != null) {
                        positionMs = f.atMs
                        paint(f.bytes)
                        firstPainted = true
                    }
                    // 兜底：某集始终产不出任何帧（坏视频轨/解码失败）且解码进程已结束，
                    // 也要触发播完/自动连播，别因为 firstPainted 永远 false 而卡住不动。
                    if (!stopping && queue.isEmpty() && videoProc?.isAlive == false) {
                        lastEndExitCode = runCatching { videoProc?.exitValue() }.getOrNull() ?: -999
                        playing = false
                        onEnded?.invoke()
                        return
                    }
                } else {
                    val now = clockMs()
                    positionMs = now
                    // 把"已到点"的帧全部取出，但只保留最新的一帧来画；落后的旧帧直接丢弃。
                    // 否则解码/取流被拖慢后，恢复时会把欠下的一堆帧连续快速画出 → 就是"强行加速"。
                    var lastDue: TimedFrame? = null
                    while (true) {
                        val head = queue.peek() ?: break
                        if (head.atMs > now) break
                        queue.poll()
                        lastDue = head
                    }
                    if (lastDue != null) {
                        paint(lastDue.bytes)
                    } else if (queue.isEmpty() && videoProc?.isAlive == false) {
                        // 解码进程结束且队列空 → 播完
                        if (!stopping) {
                            lastEndExitCode = runCatching { videoProc?.exitValue() }.getOrNull() ?: -999
                            playing = false
                            onEnded?.invoke()
                            return
                        }
                    }
                }
            }
            delay(16)
        }
    }

    private var painted = 0L

    /**
     * 画一帧。
     *
     * 关键：**每帧直接 makeRaster，不复用同一个 Bitmap**。
     * 复用 Bitmap + makeFromBitmap 时，Skia 第一次快照后就写时复制了，
     * 后续 installPixels 不会影响已发出的 Image —— 表现就是「永远停在第一帧、
     * 声音照常走」。这个坑之前在平板版踩过一次。
     */
    private fun paint(bytes: ByteArray) {
        runCatching {
            val info = ImageInfo(outWidth, outHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            frame = Image.makeRaster(info, bytes, outWidth * 4).toComposeImageBitmap()
            painted++
        }.onFailure { println("[player] 画帧失败: $it") }
    }

    // ---------------- 视频进程 ----------------

    private fun startVideo(atMs: Long, i: Info) {
        val exe = Ffmpeg.binary() ?: return
        val cmd = listOf(
            exe.absolutePath, "-hide_banner", "-loglevel", "error",
            "-hwaccel", "d3d11va",
            "-ss", "%.3f".format(atMs / 1000.0),
            "-i", sourceUrl,
            "-an",
            "-vf", "scale=$outWidth:$outHeight",
            "-f", "rawvideo", "-pix_fmt", "bgra",
            "pipe:1",
        )
        videoProc = runCatching {
            ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrNull()
    }

    private fun readFrames(i: Info) {
        val proc = videoProc ?: return
        val input = proc.inputStream
        val frameBytes = outWidth * outHeight * 4
        val buffer = ByteArray(frameBytes)
        var index = 0L
        try {
            while (!stopping) {
                var read = 0
                while (read < frameBytes) {
                    val n = input.read(buffer, read, frameBytes - read)
                    if (n < 0) {
                        println("[player] 视频流结束，已读 $index 帧")
                        return
                    }
                    read += n
                }
                val at = baseMs + (index * 1000.0 / i.fps).toLong()
                index++
                val timed = TimedFrame(at, buffer.copyOf())
                // 背压：队列满就等播放线程消费，绝不能丢旧帧——
                // 丢掉旧帧会让队头始终是「未来帧」，画面就永远停在第一帧。
                while (!queue.offer(timed, 100, TimeUnit.MILLISECONDS)) {
                    if (stopping) return
                }
            }
        } catch (e: Throwable) {
            println("[player] 读帧异常: $e")
        }
    }

    // ---------------- 声音 ----------------

    private fun startAudio(atMs: Long) {
        val i = info ?: return
        if (!i.hasAudio) return
        val exe = Ffmpeg.binary() ?: return
        val filters = buildString {
            append("volume=").append("%.2f".format(volume))
            if (speed != 1f) append(",atempo=").append("%.3f".format(speed))
        }
        val cmd = listOf(
            exe.absolutePath, "-hide_banner", "-loglevel", "error",
            "-ss", "%.3f".format(atMs / 1000.0),
            "-i", sourceUrl,
            "-vn",
            "-af", filters,
            "-ac", "2", "-ar", "48000",
            "-f", "s16le",
            "pipe:1",
        )
        val proc = runCatching {
            ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrNull() ?: return
        audioProc = proc

        audioThread = Thread {
            val fmt = AudioFormat(48_000f, 16, 2, true, false)
            val line = runCatching {
                AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, fmt)) as SourceDataLine
            }.getOrNull() ?: return@Thread
            runCatching {
                line.open(fmt, 48_000 / 2)
                applyGain(line)
                line.start()
            }.onFailure { return@Thread }
            audioLine = line

            val buf = ByteArray(16 * 1024)
            try {
                while (!stopping) {
                    val n = proc.inputStream.read(buf)
                    if (n < 0) break
                    if (n > 0) line.write(buf, 0, n)
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { line.drain(); line.stop(); line.close() }
                if (audioLine === line) audioLine = null
            }
        }.apply { isDaemon = true; name = "kxg-audio"; start() }
    }

    private fun applyGain(line: SourceDataLine) {
        runCatching {
            if (line.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)) {
                val c = line.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)
                    as javax.sound.sampled.FloatControl
                val db = if (volume <= 0.001f) -80f else (20 * Math.log10(volume.toDouble())).toFloat()
                c.value = db.coerceIn(c.minimum, c.maximum)
            }
        }
    }

    private fun stopProcesses() {
        stopping = true
        queue.clear()
        runCatching { videoProc?.destroyForcibly() }
        runCatching { audioProc?.destroyForcibly() }
        videoProc = null
        audioProc = null
        audioThread = null
    }
}
