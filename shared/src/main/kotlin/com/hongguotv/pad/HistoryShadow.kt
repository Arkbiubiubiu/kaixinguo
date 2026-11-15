package com.hongguotv.pad

import org.json.JSONObject

/**
 * 历史 v2 的影子对比：旧数据源 vs 合并后的 HistoryRepository 结果。
 * 只写日志、不改变任何显示；用于 PC 历史页切换前的 diff 观察。
 */
object HistoryShadow {
    fun report(oldRows: List<JSONObject>, newRows: List<JSONObject>) {
        val oldMap = oldRows.associateBy { it.optString("seriesId") }
        val newMap = newRows.associateBy { it.optString("seriesId") }
        val examples = mutableListOf<String>()
        var diff = 0
        for ((id, n) in newMap) {
            val o = oldMap[id]
            if (o == null) {
                diff++
                if (examples.size < 5) examples.add("+$id ep=${n.optInt("episode")} pos=${n.optLong("positionMs")}")
                continue
            }
            val oe = o.optInt("episode", 0)
            val ne = n.optInt("episode", 0)
            val op = o.optLong("positionMs", 0)
            val np = n.optLong("positionMs", 0)
            if (oe != ne || op != np || o.optString("title") != n.optString("title")) {
                diff++
                if (examples.size < 5) examples.add("~$id old=$oe/$op new=$ne/$np")
            }
        }
        for (id in oldMap.keys) {
            if (id !in newMap) {
                diff++
                if (examples.size < 5) examples.add("-$id")
            }
        }
        Log.d(
            "HistoryShadow",
            "old=${oldRows.size} new=${newRows.size} diff=$diff" +
                if (examples.isEmpty()) "" else " examples=${examples.joinToString(" | ")}"
        )
    }
}
