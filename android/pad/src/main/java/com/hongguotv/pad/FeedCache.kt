package com.hongguotv.pad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 首屏列表落盘缓存。
 *
 * 目的：开机/切 tab 时先把上次的列表画出来（0ms），再去服务器换新的。
 * 没有这层的话，冷启动要等服务器往返，用户看到的就是转圈。
 *
 * 存成单个 JSON 文件（不是 per-tab 多个文件），读写都在 IO 线程外调用，
 * 单次几十 KB，够快。
 */
object FeedCache {
    private const val FILE = "feed-cache.json"
    private const val TTL_MS = 24 * 60 * 60 * 1000L

    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, FILE)
    }

    fun save(key: String, cards: List<SeriesCard>) {
        val f = file ?: return
        runCatching {
            val root = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            val arr = JSONArray()
            cards.forEach { arr.put(it.toJson()) }
            root.put(
                key,
                JSONObject().put("at", System.currentTimeMillis()).put("cards", arr),
            )
            f.writeText(root.toString())
        }
    }

    fun load(key: String): List<SeriesCard> {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        return runCatching {
            val node = JSONObject(f.readText()).optJSONObject(key) ?: return emptyList()
            if (System.currentTimeMillis() - node.optLong("at") > TTL_MS) return emptyList()
            val arr = node.optJSONArray("cards") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.toCard()
            }
        }.getOrDefault(emptyList())
    }

    fun clear() {
        runCatching { file?.delete() }
    }

    private fun SeriesCard.toJson(): JSONObject = JSONObject()
        .put("id", seriesId).put("t", title).put("c", cover)
        .put("n", episodeCount).put("s", score).put("p", playText)
        .put("v", vid).put("ct", contentType)
        .put("g", JSONArray(tags))

    private fun JSONObject.toCard(): SeriesCard {
        val tags = mutableListOf<String>()
        optJSONArray("g")?.let { a ->
            for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let(tags::add)
        }
        return SeriesCard(
            seriesId = optString("id"),
            title = optString("t"),
            cover = optString("c"),
            episodeCount = optInt("n"),
            score = optString("s"),
            playText = optString("p"),
            vid = optString("v"),
            contentType = optInt("ct", 1004),
            tags = tags,
        )
    }
}
