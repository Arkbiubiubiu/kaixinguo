package com.kaixinguo.pc

import com.hongguotv.pad.HistoryRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * PC 观看历史与秒级续播位置（Phase 10 从 PcStore 拆出）。
 * 合并规则由 HistoryRepository 统一，与 Android/服务端同口径。
 */
object PcHistoryStore {
    /** 历史解析结果的内存缓存：避免每次全量 JSONArray 解析（列表/续播每次查都很贵）。写入/合并时失效。 */
    @Volatile
    private var cached: List<JSONObject>? = null

    /** 写入/合并后必须调用，下一读取重解析。 */
    fun invalidateHistoryCache() {
        cached = null
    }

    @Synchronized
    fun history(): List<JSONObject> {
        cached?.let { return it }
        val parsed = runCatching {
            val arr = JSONArray(PcStore.str("history", "[]"))
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .groupBy { it.optString("seriesId") }
                .values
                .mapNotNull { same -> same.maxWithOrNull(compareBy({ it.optLong("at") }, { it.optInt("episode", 0) })) }
                .sortedByDescending { it.optLong("at") }
        }.getOrDefault(emptyList())
        cached = parsed
        return parsed
    }

    fun mergeHistory(remote: List<JSONObject>): List<JSONObject> {
        // 合并规则统一在 HistoryRepository（PC/Android 共用；服务端保持同一套规则）
        val list = HistoryRepository.merge(history(), remote)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        PcStore.put("history", arr.toString())
        list.forEach { PcStore.put("progress_${it.optString("seriesId")}", it.optInt("episode").toString()) }
        invalidateHistoryCache()
        return list
    }

    fun updateHistoryCover(seriesId: String, cover: String) {
        val arr = JSONArray()
        history().forEach { r ->
            if (r.optString("seriesId") == seriesId) r.put("cover", cover)
            arr.put(r)
        }
        PcStore.put("history", arr.toString())
        invalidateHistoryCache()
    }

    // ---------------- 额外：播放进度 / 收藏追更 ----------------

    /**
     * 记一次**真实播放**后的观看进度：当前第几集、第几秒、什么时间。
     * 只点开播放页不算——调用方必须等真正播放超过几秒再调这里。
     */
    fun recordPlayback(seriesId: String, title: String, cover: String, episode: Int, total: Int, positionMs: Long) {
        if (seriesId.isEmpty() || episode <= 0 || positionMs <= 0) return
        synchronized(this) {
            val rows = history()
            val old = rows.firstOrNull { it.optString("seriesId") == seriesId }
            val list = rows.filter { it.optString("seriesId") != seriesId }.toMutableList()
            list.add(
                0,
                JSONObject()
                    .put("seriesId", seriesId)
                    .put("title", title.ifEmpty { old?.optString("title").orEmpty() })
                    .put("cover", cover.ifEmpty { old?.optString("cover").orEmpty() })
                    .put("episode", episode)
                    .put("total", if (total > 0) total else (old?.optInt("total", 0) ?: 0))
                    .put("positionMs", positionMs)
                    .put("at", System.currentTimeMillis()),
            )
            val arr = JSONArray()
            list.take(500).forEach { arr.put(it) }
            PcStore.put("history", arr.toString())
            PcStore.put("progress_$seriesId", episode.toString())
        }
        invalidateHistoryCache()
    }

    fun posOf(vid: String): Long = PcStore.str("pos_$vid", "0").toLongOrNull() ?: 0L

    /** 记「这一集看到第几秒」：换集/重进播放器时续播用。每 5 秒才落一次盘。 */
    fun markPos(vid: String, positionMs: Long) {
        if (vid.isEmpty() || positionMs <= 0L) return
        val saved = PcStore.str("pos_$vid", "0").toLongOrNull() ?: 0L
        if (kotlin.math.abs(positionMs - saved) >= 5_000L) PcStore.put("pos_$vid", positionMs.toString())
    }

    fun removeHistory(seriesId: String) {
        val arr = JSONArray()
        history().filter { it.optString("seriesId") != seriesId }.forEach { arr.put(it) }
        PcStore.put("history", arr.toString())
        PcStore.props.remove("progress_$seriesId")
        PcStore.save()
        invalidateHistoryCache()
    }

    fun clearHistory() {
        PcStore.props.remove("history")
        PcStore.props.keys.filter { it.toString().startsWith("progress_") || it.toString().startsWith("pos_") }
            .forEach { PcStore.props.remove(it) }
        PcStore.save()
        invalidateHistoryCache()
    }
}
