package com.hongguotv.pad

import org.json.JSONArray
import org.json.JSONObject

data class SeriesDetail(
    val seriesId: String,
    val title: String = "",
    val intro: String = "",
    val cover: String = "",
    val episodeCount: Int = 0,
    val score: String = "",
    val playCount: String = "",
    val followedCount: String = "",
    val status: String = "",
    val tags: List<String> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val contentType: Int = 1004,
    val hotScore: String = "",
    val recordNumber: String = "",
    val season: String = "",
    val creators: List<Creator> = emptyList(),
    // 云端状态：换设备后按钮要按这个初始化，否则会出现「云端已追更、本机显示追更」
    val followed: Boolean = false,
    val hasDigg: Boolean = false,
    val diggCount: String = "",
    /** 副标题（如 ["全178集", "红果热度值4948万"]） */
    val subTitles: List<String> = emptyList(),
    /** 云端是否已预约（online_subscribed） */
    val onlineSubscribed: Boolean = false,
    /** 第几季（season_index），多季剧接续下一季要用 */
    val seasonIndex: Int = 0,
) {
    /**
     * 是否还没上线。
     *
     * **不能看 series_status**：实测未上线的第四季 status 也是 1
     * （《末日宿舍求生》第四季：series_status=1、episode_cnt=0、video_list=[]），
     * 按 status 判断就永远不会出现预约按钮。有集才算上线。
     */
    val upcoming: Boolean get() = episodes.isEmpty() || episodeCount == 0
}

/**
 * 详情内存缓存（LRU 60 条）。
 *
 * 上游详情接口一次要回 200 多集的 video_list，反复进出详情页很浪费，
 * 所以把解析好的结构留在内存里；进程重启就没了，属于预期行为。
 */
object DetailCache {

    /** 详情页 secondary_infos 里的评分，形如「9.7分」 */
    private val SCORE_RE = Regex("^[0-9]+(\\.[0-9]+)?分$")

    private const val LIMIT = 60

    private val cache = object : LinkedHashMap<String, SeriesDetail>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SeriesDetail>?): Boolean =
            size > LIMIT
    }

    fun put(detail: JSONObject) {
        val id = detail.optString("series_id")
        if (id.isEmpty()) return

        val episodes = buildList {
            val arr = detail.optJSONArray("video_list") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    Episode(
                        vid = o.optString("vid"),
                        // video_list 里没有 episode 字段，权威集数是 vid_index；
                        // 用数组下标兜底只在顺序严格按集数时才正确
                        index = o.optInt("vid_index", o.optInt("episode", i + 1)),
                        title = o.optString("title"),
                        duration = o.optInt("duration", 0),
                        cover = o.optString("cover"),
                        userDigg = o.optBoolean("user_digg", false),
                    ),
                )
            }
        }

        // season：原版把 secondary_infos 里 data_type == 0 的第一条放在这个字段
        // （不是简介——简介走 series_intro，两处不能混）
        //
        // 评分也在这个数组里，内容形如「9.7分」（实测《逃出大英博物馆》）。
        // data_type 不稳定，直接按内容认：整串就是个分数。之前只认 data_type==0，
        // 把评分那条整个漏掉了 —— 详情页不显示评分，评分榜也就没有数据源。
        var seasonText = ""
        var scoreText = ""
        detail.optJSONArray("secondary_infos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val content = o.optString("content").trim()
                if (content.isEmpty()) continue
                if (o.optInt("data_type", -1) == 0 && seasonText.isEmpty()) seasonText = content
                if (scoreText.isEmpty() && SCORE_RE.matches(content)) {
                    scoreText = content.removeSuffix("分")
                }
            }
        }

        val creators = buildList {
            detail.optJSONArray("main_creator_users")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val base = arr.optJSONObject(i)?.optJSONObject("base_info") ?: continue
                    val name = base.optString("user_name")
                    if (name.isEmpty()) continue
                    val role = base.optJSONObject("user_tag")
                        ?.optJSONArray("sub_title_list")
                        ?.optJSONObject(0)?.optString("text").orEmpty()
                    add(
                        Creator(
                            id = base.optString("user_id"),
                            name = name,
                            avatar = base.optString("user_avatar"),
                            role = role,
                        ),
                    )
                }
            }
        }

        val tags = runCatching {
            val arr = JSONArray(detail.optString("category_schema"))
            (0 until minOf(arr.length(), 4)).mapNotNull {
                arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotEmpty() }
            }
        }.getOrDefault(emptyList())

        val d = SeriesDetail(
            seriesId = id,
            title = detail.optString("series_title"),
            score = scoreText.ifEmpty { detail.optString("score") },
            intro = detail.optString("series_intro"),
            cover = detail.optString("series_cover"),
            episodeCount = detail.optInt("episode_cnt", episodes.size),
            playCount = detail.optString("series_play_cnt"),
            followedCount = detail.optString("followed_cnt"),
            status = detail.optString("series_status"),
            tags = tags,
            episodes = episodes,
            contentType = detail.optInt("content_type", 1004),
            hotScore = detail.optString("hot_score"),
            recordNumber = detail.optJSONObject("record_info")?.optString("record_number").orEmpty(),
            season = seasonText,
            creators = creators,
            followed = detail.optBoolean("followed", false),
            hasDigg = detail.optBoolean("has_digg", false),
            diggCount = detail.optString("digg_cnt"),
            subTitles = detail.optJSONArray("series_sub_title_list")?.let { a ->
                (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
            } ?: emptyList(),
            onlineSubscribed = detail.optBoolean("online_subscribed", false),
            seasonIndex = detail.optInt("season_index", 0),
        )
        synchronized(cache) { cache[id] = d }
    }

    fun get(seriesId: String): SeriesDetail? = synchronized(cache) { cache[seriesId] }

    fun clear() = synchronized(cache) { cache.clear() }
}
