package com.hongguotv.pad

import org.json.JSONArray
import org.json.JSONObject

/**
 * 书架集合：收藏/点赞/追更/预约的 id 集合与卡片缓存。
 * 从 Store 拆出（Phase 10），存储格式不变；调用方仍走 Store 的委托方法。
 */
object ShelfStore {
    /** 分隔符，与旧版一致（）。 */
    internal const val SEP = ""
    // ---------------- 集合类（收藏/点赞/追更/预约） ----------------

    internal fun readSet(key: String): LinkedHashSet<String> {
        val raw = Store.prefs().getString(key, "").orEmpty()
        return LinkedHashSet(raw.split(SEP).filter { it.isNotBlank() })
    }

    internal fun writeSet(key: String, set: Collection<String>) {
        Store.prefs().edit().putString(key, set.joinToString(SEP)).apply()
    }

    // ---------------- 解不了的片源（bytevc2/AV1 私有封装） ----------------
    // 记住这些分集，下次直接跳过：否则每进一次刷剧都要为同一集白下载一遍头部
    // （隧道 1MB/s 左右，一集就要一两秒，看起来就是「加载特别慢」）。
    // v2：旧键里存着 VVC 支持前误标的集（如《黑白心弈》21 集，实际是 hvc1），
    // 换键名让这些历史标记全部作废，只保留新版本按真实编码记下的。
    internal const val KEY_UNSUPPORTED = "unsupported_vids_v3"

    fun isUnsupported(vid: String): Boolean =
        vid.isNotBlank() && readSet(KEY_UNSUPPORTED).contains(vid)

    fun markUnsupported(vid: String) {
        if (vid.isBlank()) return
        val set = readSet(KEY_UNSUPPORTED)
        if (set.add(vid)) writeSet(KEY_UNSUPPORTED, set)
    }

    /** 切换某个 id 在集合里的状态，返回切换后是否在集合中。 */
    fun toggle(key: String, seriesId: String): Boolean {
        val set = readSet(key)
        val on = if (set.contains(seriesId)) {
            set.remove(seriesId); false
        } else {
            set.add(seriesId); true
        }
        writeSet(key, set)
        return on
    }

    fun contains(key: String, seriesId: String): Boolean = readSet(key).contains(seriesId)

    fun ids(key: String): List<String> = readSet(key).toList()

    /** 切换并把卡片信息一并存下来，列表页不用再请求详情。 */
    fun toggleCard(key: String, card: SeriesCard): Boolean {
        val on = toggle(key, card.seriesId)
        if (on) {
            val arr = runCatching { JSONArray(Store.prefs().getString("cards_$key", "[]")) }
                .getOrDefault(JSONArray())
            arr.put(card.toJson())
            Store.prefs().edit().putString("cards_$key", arr.toString()).apply()
        }
        return on
    }

    /** 整体替换某个列表（云端为准时用）。不能循环 toggle：id 已存在时 toggle 会把它删掉。 */
    fun replaceCards(key: String, cards: List<SeriesCard>) {
        writeSet(key, cards.map { it.seriesId }.distinct())
        val arr = JSONArray()
        cards.forEach { arr.put(it.toJson()) }
        Store.prefs().edit().putString("cards_$key", arr.toString()).apply()
    }

    fun card(key: String, seriesId: String): SeriesCard? {
        val arr = runCatching { JSONArray(Store.prefs().getString("cards_$key", "[]")) }
            .getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("seriesId") == seriesId) return o.toCard()
        }
        return null
    }

    fun cacheShelfCards(cards: List<SeriesCard>) {
        val arr = JSONArray()
        cards.forEach { arr.put(it.toJson()) }
        Store.prefs().edit().putString("shelf_cards", arr.toString()).apply()
    }

    fun clearShelfCards() {
        Store.prefs().edit().remove("shelf_cards").apply()
    }

    fun shelfCard(seriesId: String): SeriesCard? {
        val arr = runCatching { JSONArray(Store.prefs().getString("shelf_cards", "[]")) }
            .getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("seriesId") != seriesId) continue
            if (o.optString("title").isEmpty()) return null
            return o.toCard()
        }
        return null
    }

    internal fun SeriesCard.toJson(): JSONObject = JSONObject()
        .put("seriesId", seriesId).put("title", title).put("cover", cover)
        .put("episodeCount", episodeCount).put("playText", playText)
        .put("score", score).put("vid", vid).put("contentType", contentType)
        .put("tags", JSONArray(tags))

    internal fun JSONObject.toCard(): SeriesCard {
        val tags = mutableListOf<String>()
        optJSONArray("tags")?.let { a ->
            for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let(tags::add)
        }
        return SeriesCard(
            seriesId = optString("seriesId"),
            vid = optString("vid"),
            title = optString("title"),
            cover = optString("cover"),
            episodeCount = optInt("episodeCount"),
            score = optString("score"),
            playText = optString("playText"),
            tags = tags,
            contentType = optInt("contentType", 1004),
        )
    }
}
