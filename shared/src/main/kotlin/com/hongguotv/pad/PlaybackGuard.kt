package com.hongguotv.pad

/** 播放器错误分类：哪些错误值得重取当前集，哪些应该直接报给用户。 */
object PlaybackErrorPolicy {
    fun isRecoverable(message: String): Boolean {
        val msg = message.lowercase()
        return listOf(
            "trust anchor", "certpath", "source error", "http", "404", "unexpected",
        ).any { msg.contains(it) }
    }
}

/**
 * 卡顿看门狗：进度连续 [stallMs] 没动，且播放器认为自己在播/缓冲时，
 * 触发重取当前集；最多 [maxRetries] 次，避免坏片源无限重建。
 *
 * PC/Android 共用同一套判定，平台只负责在重试时重建自己的引擎/播放器。
 */
class StallWatchdog(
    private val stallMs: Long = 8_000L,
    private val maxRetries: Int = 2,
) {
    enum class Decision { NONE, RETRY, GIVE_UP }

    private var lastPos = -1L
    private var lastAt = 0L
    private var retries = 0

    val retryCount: Int get() = retries

    fun reset() {
        lastPos = -1L
        lastAt = 0L
        retries = 0
    }

    /** 错误恢复也占用同一个重试预算，避免“卡顿两次 + 错误两次”无限循环。 */
    fun consumeRetry(): Boolean {
        if (retries >= maxRetries) return false
        retries += 1
        return true
    }

    fun onTick(
        position: Long,
        now: Long,
        active: Boolean,
        preparing: Boolean,
        switching: Boolean,
    ): Decision {
        if (position <= 0L || position != lastPos) {
            lastPos = position
            lastAt = 0L
            return Decision.NONE
        }
        if (lastAt == 0L) {
            lastAt = now
            return Decision.NONE
        }
        if (now - lastAt <= stallMs || preparing || switching || !active) {
            return Decision.NONE
        }
        lastAt = 0L
        return if (consumeRetry()) Decision.RETRY else Decision.GIVE_UP
    }
}
