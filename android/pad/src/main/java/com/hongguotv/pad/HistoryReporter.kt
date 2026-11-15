package com.hongguotv.pad

/**
 * 播放器只通过这个入口写历史/报进度，不再直接碰 Store/Api。
 *
 * 现在内部还是 Store.recordPlayback + Api.reportProgress；Phase 3 后半段会把它
 * 换成 HistoryRepository，播放器不需要再跟着改。
 */
object HistoryReporter {
    fun onPlayed(seriesId: String, title: String, cover: String, episode: Int, total: Int, positionMs: Long) {
        if (seriesId.isEmpty() || episode <= 0 || positionMs <= 0L) return
        Store.recordPlayback(seriesId, title, cover, episode, total, positionMs)
    }

    fun sync() {
        HistoryApi.syncAsync()
    }

    /** 官方进度上报；调用方在 IO 线程执行。 */
    fun reportOfficial(seriesId: String, vid: String, episode: Int, positionMs: Long): Boolean {
        if (seriesId.isEmpty() || vid.isEmpty() || episode <= 0 || positionMs <= 0L) return false
        return HistoryApi.report(seriesId, vid, episode, positionMs)
    }
}
