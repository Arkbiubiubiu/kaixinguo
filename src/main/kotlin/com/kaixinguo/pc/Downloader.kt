package com.kaixinguo.pc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * 本集下载（旧版 download-sheet 的极简等价）：
 * 用打包进来的 ffmpeg 直接 remux（-c copy，不转码），进度按 out_time 与
 * 时长比换算；文件落在 `%APPDATA%\KaixinguoPC\downloads\<剧名>\第N集.mp4`。
 *
 * 只做「当前集」单文件下载——旧版的选集批量面板 + 下载管理三页在 PC 端
 * 纯流媒体场景用不上（旧版自己也把入口从侧栏移除了）。
 */
object Downloader {

    enum class State { QUEUED, RUNNING, DONE, FAILED, CANCELED }

    class Task(
        val key: String,
        val title: String,
        val episode: Int,
        val file: File,
    ) {
        var progress by mutableStateOf(0f)
        var state by mutableStateOf(State.QUEUED)
        var error by mutableStateOf<String?>(null)
    }

    val tasks = mutableStateListOf<Task>()

    /** 正在跑的 ffmpeg（取消时按 key 杀掉） */
    private val procs = java.util.concurrent.ConcurrentHashMap<String, Process>()

    private val root: File by lazy {
        File(System.getenv("APPDATA") ?: ".", "KaixinguoPC/downloads").apply { mkdirs() }
    }

    private fun ffmpeg(): String {
        // 打包目录里带着 ffmpeg.exe（与播放器同一份）
        val local = File(System.getProperty("user.dir"), "app/resources/ffmpeg.exe")
        return if (local.exists()) local.absolutePath else "ffmpeg"
    }

    fun exists(seriesTitle: String, episode: Int): File =
        File(File(root, seriesTitle.ifBlank { "未命名" }), "第${episode}集.mp4")

    /** 下载一集（url 由播放器按当前清晰度档位给）。已存在则跳过。 */
    fun download(seriesTitle: String, episode: Int, url: String, durationMs: Long) {
        if (url.isBlank()) return
        val file = exists(seriesTitle, episode)
        val key = file.absolutePath
        if (tasks.any { it.key == key && it.state in setOf(State.QUEUED, State.RUNNING) }) return
        if (file.exists() && file.length() > 0 && tasks.none { it.key == key }) {
            tasks.add(0, Task(key, seriesTitle, episode, file).apply { state = State.DONE; progress = 1f })
            return
        }
        file.parentFile?.mkdirs()
        val task = Task(key, seriesTitle, episode, file)
        tasks.add(0, task)
        Thread {
            task.state = State.RUNNING
            try {
                val pb = ProcessBuilder(
                    ffmpeg(), "-y", "-i", url,
                    "-c", "copy", "-bsf:a", "aac_adtstoasc",
                    "-progress", "pipe:1", "-nostats", "-loglevel", "error",
                    file.absolutePath,
                ).redirectErrorStream(true).start()
                procs[key] = pb
                task.error = null
                pb.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val v = line.substringAfter("out_time_us=", "")
                        if (v.isNotBlank()) {
                            val us = v.toLongOrNull() ?: 0L
                            if (durationMs > 0) {
                                task.progress = (us / 1000f / durationMs).coerceIn(0f, 0.99f)
                            }
                        }
                    }
                }
                val code = pb.waitFor()
                task.progress = 1f
                task.state = if (code == 0 && file.length() > 0) State.DONE else State.FAILED
                if (task.state == State.FAILED) task.error = "ffmpeg 退出码 $code"
            } catch (e: Exception) {
                if (task.state != State.CANCELED) {
                    task.state = State.FAILED
                    task.error = e.message
                }
            } finally {
                procs.remove(key)
            }
        }.apply { isDaemon = true }.start()
    }

    /** 取消进行中的任务：杀掉 ffmpeg、删掉半截文件 */
    fun cancel(task: Task) {
        if (task.state !in setOf(State.QUEUED, State.RUNNING)) return
        task.state = State.CANCELED
        task.error = null
        procs.remove(task.key)?.let { runCatching { it.destroyForcibly() } }
        runCatching { task.file.delete() }
    }

    fun delete(task: Task) {
        cancel(task)
        runCatching { task.file.delete() }
        tasks.remove(task)
    }

    fun openFolder() {
        runCatching { java.awt.Desktop.getDesktop().open(root) }
    }
}
