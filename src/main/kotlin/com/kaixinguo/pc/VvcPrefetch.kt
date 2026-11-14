package com.kaixinguo.pc

import com.hongguotv.pad.Log
import com.hongguotv.pad.PlayInfo
import java.io.File
import java.util.concurrent.FutureTask

/**
 * VVC 预取：同一集只跑一次转换，起播/预取共用。
 *
 * VVC 的额外成本只有「下载+解密+拆容器」；转换结果按 vid 缓存在
 * %APPDATA%\KaixinguoPCvc\<vid>\clean.mp4，切集时直接命中，
 * 行为和普通 HEVC 集的预取一致（下一集提前备好，切换无等待）。
 */
object VvcPrefetch {
    private val lock = Any()
    private val inflight = HashMap<String, FutureTask<File?>>()

    /** 已经开始/完成的任务直接复用；没有就同步跑一次（起播用）。 */
    fun ensure(vid: String, info: PlayInfo): File {
        val task: FutureTask<File?>
        synchronized(lock) {
            VvcPrep.cached(vid)?.let { return it }
            val running = inflight[vid]
            if (running != null) {
                task = running
            } else {
                val t = FutureTask { VvcPrep.prepare(vid, info).playable }
                inflight[vid] = t
                Thread(t, "vvc-prep-$vid").apply { isDaemon = true }.start()
                task = t
            }
        }
        return try {
            task.get() ?: error("VVC 转换失败")
        } finally {
            synchronized(lock) {
                if (inflight[vid] === task && task.isDone) inflight.remove(vid)
            }
        }
    }

    /** 后台预热（不等待）：预取只给最近一集用，别把带宽全占了。 */
    fun prime(vid: String, info: PlayInfo) {
        synchronized(lock) {
            if (VvcPrep.cached(vid) != null || inflight.containsKey(vid)) return
            val t = FutureTask {
                runCatching { VvcPrep.prepare(vid, info).playable }
                    .onFailure { Log.e("VvcPrefetch", "预热失败 $vid: ${it.message}", it) }
                    .getOrNull()
            }
            inflight[vid] = t
            Thread(t, "vvc-prime-$vid").apply { isDaemon = true }.start()
        }
    }
}
