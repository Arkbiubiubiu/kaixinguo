package com.hongguotv.pad

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * 观看历史与秒级续播位置（Phase 10 从 Store 拆出）。
 * 合并规则仍由 HistoryRepository 统一，PC/Android/服务端同口径。
 */
object HistoryStore {
    private const val HISTORY_SCHEMA = HistoryRepository.SCHEMA
    // ---------------- 观看历史 ----------------

    /** 历史列表：一部剧一条，按最近真实播放时间倒序。 */
    fun history(): List<JSONObject> = runCatching {
        val arr = JSONArray(Store.prefs().getString("history", "[]"))
        val rows = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        rows.groupBy { it.optString("seriesId").ifEmpty { it.optString("title") } }.values
            .mapNotNull { same ->
                same.maxWithOrNull(compareBy({ it.optLong("at") }, { it.optInt("episode", 0) }))
            }
            .sortedByDescending { it.optLong("at") }
    }.getOrDefault(emptyList())

    /**
     * 记一次**真实播放**后的观看进度。
     *
     * 历史就是「最近一次看到哪」：当前第几集、第几秒、什么时间。
     * 只点开播放页不算——调用方必须等真正播放超过几秒再调这里。
     * 换到更低的集数正常看，也会如实更新；不再做「集数只增不减」。
     */
    fun recordPlayback(
        seriesId: String,
        title: String,
        cover: String,
        episode: Int,
        total: Int,
        positionMs: Long,
    ) {
        if (seriesId.isEmpty() || episode <= 0 || positionMs <= 0) return
        synchronized(historyLock) {
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
            Store.prefs().edit().putString("history", arr.toString())
                .putInt("progress_$seriesId", episode)
                .apply()
            historyStamp.value = System.currentTimeMillis()
        }
    }

    // ---------------- 秒级位置（只在本次进程内有效） ----------------
    //
    // 用来解决「从播放页点详情、返回后这一集从头开始」：导航栈只存集号，
    // 播放页一销毁 ExoPlayer 就没了。这里按 vid 记一个秒数，回来后 seek 过去。
    // 故意不放 SharedPreferences —— 跨进程沿用旧位置反而容易让人困惑（比如想重看某集）。
    private val lastPos = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 记下某集看到哪儿。开头 5 秒内不记，免得「刚点进去就退出」也存个 0 秒。
     *
     * 落盘（`pos_<vid>`）：以前只存内存，App 一重启历史就只能从这一集的开头看。
     * 按 5 秒节流，避免每秒写一次盘。
     */
    fun markPos(vid: String, ms: Long) {
        if (vid.isEmpty()) return
        val key = "pos_$vid"
        if (ms > 5_000) {
            lastPos[vid] = ms
            val saved = runCatching { Store.prefs().getLong(key, 0L) }.getOrDefault(0L)
            if (kotlin.math.abs(ms - saved) >= 5_000) {
                runCatching { Store.prefs().edit().putLong(key, ms).apply() }
            }
        } else {
            lastPos.remove(vid)
            // 从头看了：把旧的续播点清掉，否则下次又跳回老位置
            runCatching { Store.prefs().edit().remove(key).apply() }
        }
    }

    /** 该集上次看到的毫秒数；内存没有就读盘（跨进程也能接着看）。 */
    fun posOf(vid: String): Long =
        if (vid.isEmpty()) 0L
        else lastPos[vid] ?: runCatching { Store.prefs().getLong("pos_$vid", 0L) }.getOrDefault(0L)

    /** 该剧最近看到第几集（历史行为准，旧数据才回落到 progress_ 前缀）。 */
    fun progress(seriesId: String): Int {
        val fromHistory = history().firstOrNull { it.optString("seriesId") == seriesId }
            ?.optInt("episode", 0) ?: 0
        return if (fromHistory > 0) fromHistory else Store.prefs().getInt("progress_$seriesId", 0)
    }

    /** 历史变化计数：界面靠它感知「别处写入了历史」，不用轮询。 */
    val historyStamp: MutableState<Long> = mutableStateOf(0L)

    /** 与云端历史合并：一部剧一条，最近一次真实播放者胜。 */
    private val historyLock = Any()

    fun mergeHistory(remote: List<JSONObject>): List<JSONObject> = synchronized(historyLock) {
        mergeHistoryLocked(remote)
    }

    internal fun mergeHistoryLocked(remote: List<JSONObject>): List<JSONObject> {
        // 合并规则统一在 HistoryRepository（PC/Android 共用；服务端保持同一套规则）
        val list = HistoryRepository.merge(history(), remote)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        val editor = Store.prefs().edit().putString("history", arr.toString())
        list.forEach { editor.putInt("progress_${it.optString("seriesId")}", it.optInt("episode")) }
        editor.apply()
        historyStamp.value = System.currentTimeMillis()
        return list
    }

    fun clearHistory() {
        Store.prefs().edit().remove("history").apply()
    }

    /** 补一条历史的封面（早期版本写历史时 cover 写死空串，留下脏数据）。 */
    fun updateHistoryCover(seriesId: String, cover: String) {
        val arr = JSONArray()
        history().forEach { r ->
            if (r.optString("seriesId") == seriesId) r.put("cover", cover)
            arr.put(r)
        }
        Store.prefs().edit().putString("history", arr.toString()).apply()
    }

    fun removeHistory(seriesId: String) {
        val arr = JSONArray()
        history().filter { it.optString("seriesId") != seriesId }.forEach { arr.put(it) }
        Store.prefs().edit().putString("history", arr.toString())
            .remove("progress_$seriesId").apply()
    }

}
