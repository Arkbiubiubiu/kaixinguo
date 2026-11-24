package com.kaixinguo.pc

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * PC 预取/预热的统一闸门（防止高峰猛刷把服务器打爆，上次 503 就是预取洪峰）：
 *  - **全局并发 ≤2**：所有走本闸门的预取（PlaybackPrefetch / feed 跨剧预热 / 格式预热）共享最多 2 个并发；
 *  - **失败指数退避**：同一 key 失败后，第一次 30s 内、之后 2 分钟内不再重试（静默）；
 *  - **命中即短路**：guardCached 在成功过且 TTL 内直接跳过，不重复请求。
 */
object PrefetchGate {
    private val sem = Semaphore(2)
    private class Fail(var count: Int, var at: Long)
    private val fails = ConcurrentHashMap<String, Fail>()
    private val okAt = ConcurrentHashMap<String, Long>()
    private const val OK_TTL = 3 * 60_000L

    private fun backoffMs(count: Int): Long = if (count <= 1) 30_000L else 120_000L
    private fun blocked(key: String): Boolean {
        val f = fails[key] ?: return false
        return System.currentTimeMillis() - f.at < backoffMs(f.count)
    }

    /** 并发≤2 + 失败退避；block 返回 true=成功。异常/失败都静默记为失败。 */
    suspend fun guard(key: String, block: suspend () -> Boolean): Boolean {
        if (blocked(key)) return false
        return sem.withPermit {
            if (blocked(key)) return@withPermit false
            val ok = try { block() } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                false
            }
            if (ok) {
                fails.remove(key)
                okAt[key] = System.currentTimeMillis()
            } else {
                val old = fails[key]
                fails[key] = Fail((old?.count ?: 0) + 1, System.currentTimeMillis())
            }
            ok
        }
    }

    /** 同上，但成功过且 TTL 内直接跳过（命中缓存不重复请求）；用于 feed 跨剧详情/档位预热。 */
    suspend fun guardCached(key: String, ttlMs: Long = OK_TTL, block: suspend () -> Boolean): Boolean {
        val at = okAt[key]
        if (at != null && System.currentTimeMillis() - at < ttlMs) return true
        return guard(key, block)
    }

    fun reset() {
        fails.clear()
        okAt.clear()
    }
}
