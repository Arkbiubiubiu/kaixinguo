package com.hongguotv.pad

import android.util.Log

/**
 * 预取好的一集：档位表、解密后的样本表、已监听的本地代理，起播要的东西都在这里，
 * 切过去只差一句 setMediaItem。
 */
internal class ReadyEpisode(
    val index: Int,
    val vid: String,
    val defWant: Int,
    val tiers: List<PlayTier>,
    val remote: RemoteVideo,
    val proxy: StreamProxy,
    val def: Int,
)

internal fun dropReady(r: ReadyEpisode) {
    runCatching { r.proxy.stop() }
    runCatching { r.remote.close() }
}

/**
 * 选档 + 准备下一集（取流、解密样本表、起本地代理）。
 * 从 PlayerScreen 抽出来，播放器只负责队列/UI；平台取流细节留在这里。
 */
internal fun buildReady(
    index: Int,
    ep: Episode,
    defWant: Int,
    keepVids: Set<String> = emptySet(),
    alive: () -> Boolean,
): ReadyEpisode? {
    if (!alive()) return null
    var ts = PlaybackApi.tiers(ep.vid)
    if (ts.isEmpty()) {
        // 全 VVC 集：服务端已排进异步转码 job，等它完成再取档位，
        // 下一集就不用落到同步 play() 的长等待。
        val job = PlaybackApi.pendingJob(ep.vid)
        if (job != null && PlaybackApi.awaitTranscode(job, 60_000L)) {
            PlaybackApi.clearPendingJob(ep.vid)
            ts = PlaybackApi.tiers(ep.vid)
        }
    }
    if (!alive()) return null
    val cap = if (defWant > 0) defWant else Store.maxHeight
    val playable = ts.filter { it.playable }
    val picked = playable.firstOrNull { it.height == cap }
        ?: playable.firstOrNull { it.height <= cap }
        ?: playable.firstOrNull()
    val info = picked?.toPlayInfo() ?: PlaybackApi.play(ep.vid, 1004, defWant)
    val rv = RemoteVideo(info)
    try {
        rv.prepare(warmLastBlock = false)
    } catch (e: UnsupportedCodecException) {
        rv.close()
        Log.w("nextup", "next ep$index unsupported codec=${e.codec}（服务端转码失败）")
        return null
    }
    if (!alive()) {
        rv.close()
        return null
    }
    val px = StreamProxy(rv)
    px.start()
    if (!alive()) {
        px.stop()
        rv.close()
        return null
    }
    return ReadyEpisode(index, ep.vid, defWant, ts, rv, px, info.height)
}
