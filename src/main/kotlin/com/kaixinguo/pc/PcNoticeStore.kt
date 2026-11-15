package com.kaixinguo.pc

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 追更通知（Phase 10 从 PcStore 拆出）：检查更新、落通知、已读管理。
 * 数据文件仍是 %APPDATA%\KaixinguoPCollow_notices.json，格式不变。
 */
object PcNoticeStore {
    private var cache: MutableList<JSONObject>? = null

    private val noticesFile = File(PcStore.dir, "follow_notices.json")

    private fun notices(): MutableList<JSONObject> {
        cache?.let { return it }
        val list = mutableListOf<JSONObject>()
        runCatching {
            if (noticesFile.exists()) {
                val arr = JSONArray(noticesFile.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
            }
        }
        cache = list
        return list
    }

    private fun saveNotices() {
        runCatching {
            val arr = JSONArray()
            notices().forEach { arr.put(it) }
            noticesFile.writeText(arr.toString(), Charsets.UTF_8)
        }
    }

    fun followNotices(): List<JSONObject> = notices().sortedByDescending { it.optLong("at", 0L) }

    fun unreadNoticeCount(): Int = notices().count { !it.optBoolean("read", false) }

    /** 一轮更新检查：服务端集数 > 基线 → 落通知；首次见到的只记基线。返回新增数。 */
    fun checkFollowUpdates(): Int {
        val page = com.hongguotv.pad.Api.followUpdates()
        if (page.items.isEmpty()) return 0
        var added = 0
        val now = System.currentTimeMillis()
        page.items.forEach { item ->
            val key = "fb_${item.seriesId}"
            val baseline = PcStore.str(key, "").toIntOrNull()
            if (baseline == null) {
                PcStore.put(key, item.episodeCount.toString())
                return@forEach
            }
            if (item.episodeCount <= baseline) return@forEach
            PcStore.put(key, item.episodeCount.toString())
            val notice = JSONObject()
                .put("id", now + added)
                .put("seriesId", item.seriesId)
                .put("title", item.title.ifBlank { "追更更新" })
                .put("episode", item.episodeCount)
                .put("detail", "追的剧更新了，新增 ${item.episodeCount - baseline} 集（共 ${item.episodeCount} 集）")
                .put("at", now)
                .put("read", false)
            notices().add(0, notice)
            added++
        }
        if (added > 0) saveNotices()
        return added
    }

    /** 追更时立刻把基线记成当前集数（避免「刚追完就报更新」）。 */
    fun seedFollowBaseline(seriesId: String, episodeCount: Int) {
        if (episodeCount > 0) PcStore.put("fb_$seriesId", episodeCount.toString())
    }

    fun markNoticeRead(id: Long) {
        notices().firstOrNull { it.optLong("id", -1L) == id }?.put("read", true)
        saveNotices()
    }

    fun markAllNoticesRead() {
        notices().forEach { it.put("read", true) }
        saveNotices()
    }

    fun clearNotices() {
        notices().clear()
        saveNotices()
    }
}
