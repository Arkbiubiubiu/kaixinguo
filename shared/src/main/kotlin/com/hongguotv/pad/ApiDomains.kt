package com.hongguotv.pad

import org.json.JSONObject

/**
 * API 领域边界（Phase 5 strangler 第一步）。
 *
 * 现在内部仍转调 `Api`，但播放器/历史/书架/社交/目录只依赖各自 facade。
 * 下一步把 `Api.kt` 对应段落搬进这些对象，调用方无需再改。
 */
object PlaybackApi {
    fun tiers(vid: String, priority: Boolean = false): List<PlayTier> = Api.tiers(vid, priority)
    fun play(vid: String, contentType: Int = 1004, want: Int = 0): PlayInfo = Api.play(vid, contentType, want, priority = true)
    fun clearTierCache() = Api.clearTierCache()
    /** 服务端异步转码 job（非优先级取流返回 202 时记录）。 */
    fun pendingJob(vid: String): String? = Api.pendingJob(vid)
    fun clearPendingJob(vid: String) = Api.clearPendingJob(vid)
    fun awaitTranscode(jobId: String, timeoutMs: Long): Boolean = Api.awaitTranscode(jobId, timeoutMs)
}

object HistoryApi {
    fun official(maxPages: Int = 3): List<JSONObject> = Api.officialHistory(maxPages)
    fun sync(): Boolean = Api.historySync()
    fun syncAsync(onDone: (() -> Unit)? = null, force: Boolean = false) = Api.historySyncAsync(onDone, force)
    fun report(seriesId: String, vid: String, episodeIndex: Int, positionMs: Long): Boolean =
        Api.reportProgress(seriesId, vid, episodeIndex, positionMs)
    fun delete(seriesIds: List<String>): Boolean = Api.historyDelete(seriesIds)
    fun backfillCovers(): Int = Api.backfillHistoryCovers()
}

object AccountApi {
    fun shelfIds(): Set<String> = Api.shelfIds()
    fun reserveIds(): Set<String> = Api.reserveIds()
    fun reserveList(): List<SeriesCard> = Api.reserveList()
    fun reserveListCached(): List<SeriesCard> = Api.reserveListCached()
    fun followList(): List<SeriesCard> = Api.followList()
    fun followUpdates(cursor: String = ""): FollowUpdatePage = Api.followUpdates(cursor)
    fun reserveToggle(seriesId: String, on: Boolean): Boolean = Api.reserveToggle(seriesId, on)
    fun followToggle(seriesId: String, on: Boolean): Boolean = Api.followToggle(seriesId, on)
}

object SocialApi {
    fun reviews(seriesId: String, limit: Int = 20): ReviewPage = Api.reviews(seriesId, limit)
    fun danmakuPage(vid: String, seriesId: String, startMs: Long, durationMs: Long, cursor: String): DmWindow? =
        Api.danmakuPage(vid, seriesId, startMs, durationMs, cursor)
}

object CatalogApi {
    fun search(query: String, offset: Int = 0, searchId: String = "", tabType: String = "11"): FeedPage =
        Api.searchPage(query, offset, searchId, tabType)
}
