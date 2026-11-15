package com.hongguotv.pad

import org.json.JSONObject

/**
 * 历史合并的唯一策略（PC / Android 共用）。
 *
 * 规则：
 * - 最近一次真实播放者胜：at 大者胜；
 * - 秒数为 0 的旧「只点开」记录，不能覆盖已经真实播放过的位置；
 * - at 相同再比集数/秒数；
 * - 空 cover/title 不覆盖已有值；
 * - 同一 seriesId 只留一条，按 at 倒序，最多 [LIMIT] 条。
 *
 * 服务端 history_store.py 必须保持同一套规则；改这里必须同步服务端和树。
 */
object HistoryRepository {
    const val SCHEMA = 3
    const val LIMIT = 1500

    /**
     * 「试看保护」：低集数 + 短播放（<60 秒）的行视为点开试试，不允许顶掉
     * 更高集数的真实进度。2026-11-15 事故：TV 连播看了几十集，一次 18 秒的
     * 低集数写入（上报时集号卡在旧值）把整份进度顶回第 4 集。
     * 真的跳回低集数看（≥60 秒）仍然照实更新——与「低集数正常看也如实记录」不冲突。
     */
    const val SHORT_WATCH_MS = 60_000L

    fun merge(
        local: List<JSONObject>,
        remote: List<JSONObject>,
        limit: Int = LIMIT,
    ): List<JSONObject> {
        val merged = LinkedHashMap<String, JSONObject>()
        // 新客户端只会在真正播放 >=3 秒后写历史；本地 <3 秒的行都是旧版本/测试残留，
        // 不能拿来覆盖云端真实进度。远端（官方）的 0 秒行仍保留，用于兼容“看完但秒数为0”。
        val validLocal = local.filter { it.optLong("positionMs", 0L) >= 3_000L }
        (validLocal + remote).forEach { row ->
            val id = row.optString("seriesId")
            if (id.isEmpty()) return@forEach
            val old = merged[id]
            if (old == null) {
                merged[id] = row
                return@forEach
            }

            val re = row.optInt("episode", 0)
            val oe = old.optInt("episode", 0)
            val rp = row.optLong("positionMs", 0L)
            val op = old.optLong("positionMs", 0L)
            val ra = row.optLong("at", 0L)
            val oa = old.optLong("at", 0L)

            val rowBetter = when {
                // 0 秒行只有“集数更高且时间更新”才算后续进度；同集/更低集数让真实秒数赢
                rp <= 0L && op > 0L -> re > oe && ra > oa
                op <= 0L && rp > 0L -> !(oe > re && oa > ra)
                // 试看保护：低集数 + 短播放不顶掉高集数真实行（双向对称）。
                // 只看「来犯的行」是不是短播放，旧行本身秒数长短不影响保护。
                re < oe && rp in 1..SHORT_WATCH_MS && op > 0L -> false
                oe < re && op in 1..SHORT_WATCH_MS && rp > 0L -> true
                ra != oa -> ra > oa
                re != oe -> re > oe
                else -> rp > op
            }
            val win = if (rowBetter) row else old
            val lose = if (rowBetter) old else row
            if (win.optString("cover").isEmpty()) win.put("cover", lose.optString("cover"))
            if (win.optString("title").isEmpty()) win.put("title", lose.optString("title"))
            merged[id] = win
        }
        return merged.values.sortedByDescending { it.optLong("at", 0L) }.take(limit)
    }
}
