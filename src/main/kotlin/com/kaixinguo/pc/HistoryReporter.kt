package com.kaixinguo.pc

import com.hongguotv.pad.HistoryApi

/**
 * PC 播放器写历史/报进度的唯一入口；播放器不再直接碰 PcStore/Api。
 */
object HistoryReporter {
    fun onPlayed(seriesId: String, title: String, cover: String, episode: Int, total: Int, positionMs: Long) {
        if (seriesId.isEmpty() || episode <= 0 || positionMs <= 0L) return
        PcStore.recordPlayback(seriesId, title, cover, episode, total, positionMs)
    }

    fun reportOfficial(seriesId: String, vid: String, episode: Int, positionMs: Long): Boolean {
        if (seriesId.isEmpty() || vid.isEmpty() || episode <= 0 || positionMs <= 0L) return false
        return HistoryApi.report(seriesId, vid, episode, positionMs)
    }
}
