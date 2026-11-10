package com.hongguotv.pad

/**
 * 数据模型。字段名与顺序按反编译出来的原工程还原
 * （SeriesCard 有 20 个字段，后 8 个可空、有默认值）。
 */

data class Episode(
    val vid: String,
    val index: Int,
    /** 这一集是否已点赞（video_list[].user_digg）——点赞是按集的，不是按剧 */
    val userDigg: Boolean = false,
    val title: String = "",
    val duration: Int = 0,
    val cover: String = "",
)

data class PlayInfo(
    val url: String,
    val backupUrl: String = "",
    val key: ByteArray? = null,
    val definition: String = "",
    val height: Int = 0,
    val duration: Int = 0,
    val size: Long = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayInfo) return false
        return url == other.url && definition == other.definition && height == other.height
    }

    override fun hashCode(): Int = url.hashCode() * 31 + height
}

/** 一个清晰度档位（服务器 want=all 解好的完整直链，切换清晰度不用再请求）。 */
data class PlayTier(
    val height: Int,
    val definition: String,
    val url: String,
    val backup: String = "",
    val spadeA: String = "",
    val size: Long = 0L,
    val codec: String = "",
) {
    /**
     * 能不能在本机播。
     *
     * 番茄的 720p 及以下常常是 **bytevc2（HEVC）**，ExoPlayer 在不少电视/模拟器上
     * 解出来就是黑屏（解码器建起来了、画面不出来），只有 1080p 是 bytevc1（H.264）。
     * 与其让用户切过去看黑屏，不如只列真能播的档位。
     */
    val playable: Boolean get() = !codec.contains("bytevc2", true)

    fun toPlayInfo(): PlayInfo = PlayInfo(
        url = url,
        backupUrl = backup,
        key = spadeA.takeIf { it.isNotEmpty() }
            ?.let { s -> runCatching { MediaCrypto.deriveKey(s) }.getOrNull() },
        definition = definition.ifEmpty { "${height}p" },
        height = height,
        duration = 0,
        size = size,
    )
}

data class SeriesCard(
    val seriesId: String,
    val vid: String = "",
    val title: String = "",
    val cover: String = "",
    val episodeCount: Int = 0,
    val score: String = "",
    val playText: String = "",
    val tags: List<String> = emptyList(),
    val contentType: Int = 1004,
    val subtitle: String? = null,
    val statusText: String? = null,
    val heat: String? = null,
    val season: String? = null,
    val badge: String? = null,
    val desc: String? = null,
    val rankText: String? = null,
    val recText: String? = null,
    val secondary: List<String>? = null,
    val upcoming: Boolean = false,
    val reserved: Boolean = false,
    /** 来自平台上的另一套合集（同一部剧重复上架，补齐缺失季时用） */
    val fromOther: Boolean = false,
)

data class RankUser(val userId: String, val name: String, val avatar: String)

/** 详情页「相关推荐」的一个分组（相关作品 / 猜你喜欢），原版就是分两组展示的。 */
data class RelatedGroup(val name: String, val items: List<SeriesCard>)

data class FeedPage(
    val cards: List<SeriesCard> = emptyList(),
    val sessionId: String = "",
    val nextOffset: Int = 0,
    val hasMore: Boolean = true,
    val cellId: String = "",
    val planId: String = "",
    val users: List<RankUser> = emptyList(),
    /**
     * 这份数据离「从上游真正拉下来」过去了多少秒（来自服务端 /api/fetch 的 age）。
     * 0 = 本次现取；>0 = 命中了服务端共享缓存（榜单缓存 5 分钟）。
     */
    val dataAgeSec: Double = 0.0,
)

data class FilterItem(val id: String, val label: String)

data class FilterRow(val key: String, val name: String, val items: List<FilterItem>)

/** 排行榜筛选面板里的一个选项（如「主题情节 · 重生」）。id 为空表示「总榜」。 */
data class RankOption(val id: String, val name: String)

/** 筛选面板的一行：综合 / 时代背景 / 主题情节 / 角色设定 / 画风。 */
data class RankPanel(val key: String, val name: String, val items: List<RankOption> = emptyList())

/**
 * 一个子榜。panels / months 由服务器下发（/api/rankmeta），
 * 内置的兜底表里这两个是空的 —— 也就是说拿不到服务器数据时，
 * 页面退化成老样子（只有大类 + 子榜，没有筛选），不会白屏。
 */
data class RankSub(
    val id: String,
    val label: String,
    val desc: String = "",
    val panels: List<RankPanel> = emptyList(),
    val months: List<RankOption> = emptyList(),
)

data class RankTabsel(val id: String, val label: String, val subs: List<RankSub>)

/**
 * 同一部剧的某一季（来自 plan 接口的「系列剧」行，show_type=547）。
 *
 * 为什么不用「相关推荐」：那是上游给的推荐位，会漏季 ——
 * 《全球躲猫猫》五季只给了 1/3/4/5/6，第二季标题是「我是林岁安第二季」，
 * 跟其他季的前缀都不一样，靠标题猜也猜不出来。
 */
data class SeasonRef(
    val seriesId: String,
    val title: String,
    val cover: String,
    val count: Int,
    val index: Int,
    /** 来自平台上的另一套合集（同一部剧被重复上架，各自只收录一部分季） */
    val fromOther: Boolean = false,
)

/**
 * 演员榜的一条：演员榜（剧星榜 / 星光榜）返回的是「人」，不是剧集，
 * 字段在 cell_data[].ugc_user_data[] 里，跟剧集榜的 video_data 完全两套。
 */
data class RankPerson(
    val userId: String,
    val name: String,
    val avatar: String,
    val score: String,
    val intro: String,
    val details: List<String>,
)

data class Creator(val id: String, val name: String, val avatar: String, val role: String = "")

/** 首页三个 tab：推荐 16 / 漫剧 36 / 真人剧 39。 */
enum class HomeTab(val label: String, val tabType: String) {
    Recommend("推荐", "16"),
    Comic("漫剧", "36"),
    Human("真人剧", "39"),
}

/** 大数字按原版口径折成「万 / 亿」（10312944 → 1031万）。 */
fun formatCount(raw: String): String {
    if (raw.isBlank()) return raw
    val n = raw.toLongOrNull() ?: return raw
    return when {
        n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000.0)
        n >= 10_000 -> "${n / 10_000}万"
        else -> raw
    }
}

/**
 * 精确计数（与 PC 的 formatCountPrecise 一致）：保留一位小数、去掉「.0」尾巴。
 * 47861696 → 「4786.2万」；23740300 → 「2374万」；不足一万原样返回。
 * hero / 详情页 stats 行用这个（卡片上的短格式仍用 formatCount）。
 */
fun formatCountPrecise(raw: String): String {
    if (raw.isBlank()) return raw
    val n = raw.toLongOrNull() ?: return raw
    fun trim(s: String) = if (s.endsWith(".0")) s.dropLast(2) else s
    return when {
        n >= 100_000_000 -> trim(String.format("%.1f", n / 100_000_000.0)) + "亿"
        n >= 10_000 -> trim(String.format("%.1f", n / 10_000.0)) + "万"
        else -> raw
    }
}

/** 播放量展示：已经是「x万」这类文案就原样返回。 */
fun formatPlayCount(raw: String): String {
    if (raw.isBlank()) return raw
    return if (raw.any { it == '万' || it == '亿' }) raw else formatCount(raw)
}

/** 一条弹幕（comment_type=20，时间轴在 expand.offset_time，毫秒）。 */
data class DmItem(val id: String, val text: String, val offsetMs: Long)

/** 弹幕一窗：条目 + 是否还有下一窗 + 原样回传的 cursor 与下一窗起点。 */
data class DmWindow(
    val items: List<DmItem> = emptyList(),
    val hasMore: Boolean = false,
    val cursor: String = "",
    val nextStart: Long = 0L,
)
