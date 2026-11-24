package com.hongguotv.pad

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * 预取 / 预热的**全局闸门**（只服务"预取/预热 tiers"这类低优先级后台请求；正片取流不走这里）。
 *
 * 目标：高峰时别把自己/服务器打爆。
 *  - 全局并发 ≤ [MAX_CONCURRENCY]=2：FeedPreload、无缝队列预取、详情预热 全部共用同一把 Semaphore。
 *  - 同一 key 正在预取时去重，不重复发请求。
 *  - 失败按 key 指数退避：30s → 60s → 120s … 封顶 [MAX_MS]=5min，期间不再重试；成功即清零。
 *  - 失败静默：block 抛异常/返回 null 一律吞掉并记退避，返回 null（调用方当作"没预取到"）。
 */
object PrefetchGuard {
    private const val MAX_CONCURRENCY = 2
    private const val BASE_MS = 30_000L
    private const val MAX_MS = 5 * 60_000L

    private val gate = Semaphore(MAX_CONCURRENCY)
    private val failCount = ConcurrentHashMap<String, Int>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val inflight = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 该 key 是否还在退避窗口内（供调用方提前跳过、别白等）。 */
    fun blocked(key: String): Boolean {
        val t = cooldownUntil[key] ?: return false
        if (t <= System.currentTimeMillis()) {
            cooldownUntil.remove(key)
            failCount.remove(key)
            return false
        }
        return true
    }

    private fun markOk(key: String) {
        failCount.remove(key)
        cooldownUntil.remove(key)
    }

    private fun markFail(key: String) {
        val n = (failCount[key] ?: 0) + 1
        failCount[key] = n
        val wait = minOf(BASE_MS shl (n - 1).coerceIn(0, 10), MAX_MS)
        cooldownUntil[key] = System.currentTimeMillis() + wait
    }

    /** 低优先级预取：并发≤2 + 同 key 去重 + 失败退避；block 返回 null 视为失败。 */
    suspend fun <T : Any> prefetch(key: String, block: suspend () -> T?): T? {
        if (key.isEmpty() || blocked(key)) return null
        if (!inflight.add(key)) return null
        try {
            return gate.withPermit {
                if (blocked(key)) {
                    null
                } else {
                    val r = try {
                        block()
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        null
                    }
                    if (r == null) markFail(key) else markOk(key)
                    r
                }
            }
        } finally {
            inflight.remove(key)
        }
    }
}
