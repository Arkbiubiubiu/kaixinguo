package com.hongguotv.pad

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf

/**
 * 跨页面保留的浏览状态：从列表/刷剧进详情、播放，再返回时回到原来的位置。
 *
 * 背景：各页面是「按需组合」的（切页面就离开组合），原来所有状态都是局部 remember，
 * 一进详情就全丢，返回时刷剧回到第一部、列表回到顶部。这里把需要保留的东西放进单例：
 * - 刷剧：整个列表 + 当前第几部（靠它算当前播放哪部，不能只存下标）
 * - 其它页面：列表滚动状态对象直接复用（懒加载列表靠它恢复位置）
 */
object UiMemo {
    // ---- 刷剧（整屏连播） ----
    var feedTab: Int = 0   // 0=漫剧（三端默认）
    var feedCursor: Int = 0
    var feedCards: List<SeriesCard> = emptyList()
    var feedPage: FeedPage? = null
    /** 刷剧里连续「取流失败」跳过的部数：超过 2 部就停在错误页，避免网络断了还一路狂跳 */
    var feedFailStreak: Int = 0

    /** 推荐页当前 tab（只在切 tab 时把列表滚回顶部） */
    var homeTab: String? = null

    // ---- 列表滚动位置 ----
    val homeGrid = LazyGridState(0, 0)
    val findGrid = LazyGridState(0, 0)
    val rankList = LazyListState(0, 0)
    val newGrid = LazyGridState(0, 0)
    val followGrid = LazyGridState(0, 0)
    val historyList = LazyListState(0, 0)
    val reserveGrid = LazyGridState(0, 0)
    val shelfGrid = LazyGridState(0, 0)
    val followList = LazyListState(0, 0)
    val settingsList = LazyListState(0, 0)
    val calTabList = LazyListState(0, 0)
    val settingsScroll = ScrollState(0)

    /** 历史页当前页签（0 历史 / 1 收藏 / 2 点赞）：从详情返回时别再跳回历史 */
    var recordsTab: Int = -1

    // ---- 新剧页（推荐 / 日历）：从详情返回要回到原分类、原日期、原位置 ----
    // 各页面按需组合，局部 remember 一进详情就全丢；这里把「你在哪、看到哪」存下来。
    var newTab: Int = 0          // 0 新剧推荐 / 1 上新日历
    var newGender: Int = 0       // 0 全部 / 1 男频 / 2 女频
    var newSession: String = ""
    var newOffset: Int = 0
    var newHasMore: Boolean = true
    /** 已加载的首屏 key（tab|gender）：返回时命中就不重新拉第一页，保留分页进度 */
    var newKey: String = ""
    /** 首屏拉取时间：超过 10 分钟再回页才刷新，避免每次返回都重拉 */
    var newAt: Long = 0L
    val newCards = mutableStateListOf<SeriesCard>()

    var calSelected: String = ""
    /** 日历首屏拉取时间：返回时 5 分钟内不重拉，保住用户选的那一天 */
    var calAt: Long = 0L
    val calDays = mutableStateListOf<String>()
    internal val calByDay = mutableStateMapOf<String, MutableList<CalendarItem>>()
    val calMore = mutableStateMapOf<String, Pair<Int, String>>()
    /** 上次因「切日期」而滚到顶部的日期：从详情返回同一日期时不能再滚，否则位置丢失 */
    var calScrollDate: String = ""

    /** 详情页的滚动位置与页签：从播放返回时回到第 80 集那一段 */
    val detailScroll = ScrollState(0)
    var detailTabSeries: String = ""
    var detailTab: Int = 0

    // ---- 其余「列表 → 详情 → 返回」页面（2026-11-16 系统排查） ----
    // 统一存「你在哪、看到哪、已加载到哪一页」；返回时同一 key 且在 TTL 内
    // 就直接复用，不重拉首屏、不重置滚动。
    // 首页：三 tab + 卡片墙 + 翻页游标 + hero 当前剧
    var homeTabName: String = ""
    var homeKey: String = ""
    var homeAt: Long = 0L
    var homeHasMore: Boolean = true
    var homeCursor: FeedPage? = null
    var homeHeroId: String = ""
    val homeCards = mutableStateListOf<SeriesCard>()

    // 排行榜：大类/子榜/筛选/月榜（RankMemo）+ 已加载列表与翻页
    var rankKey: String = ""
    var rankAt: Long = 0L
    var rankMetaAt: Long = 0L
    var rankSession: String = ""
    var rankOffset: Int = 0
    var rankHasMore: Boolean = true
    var rankDataAt: Long = 0L
    val rankCards = mutableStateListOf<SeriesCard>()
    val rankPersons = mutableStateListOf<RankPerson>()

    // 收藏 / 点赞：卡片墙（分 key 存）
    val favoriteCards = mutableStateListOf<SeriesCard>()
    val likeCards = mutableStateListOf<SeriesCard>()
    var favoriteAt: Long = 0L
    var likeAt: Long = 0L

    // 历史：筛选（全部/未看完/已看完）与搜索词
    var recordsFilter: Int = 0
    var recordsSearch: String = ""

    // 追更 / 预约：tab 与搜索词、卡片
    var followTab: Int = 0
    var followQuery: String = ""
    var followAt: Long = 0L
    val followCards = mutableStateListOf<SeriesCard>()
    var reserveTab: Int = 0
    var reserveQuery: String = ""
    var reserveAt: Long = 0L
    val reserveCards = mutableStateListOf<SeriesCard>()

    // 演职员页（详情 → 演职员 → 作品详情 → 返回）：资料、作品、滚动
    var creatorId: String = ""
    var creatorAt: Long = 0L
    var creatorProfile: CreatorProfileInfo? = null
    var creatorWorks: List<SeriesCard> = emptyList()
    val creatorGrid = LazyGridState(0, 0)

    /** 「列表→详情→返回」状态复用窗口：超过这个时间才重新拉首屏。 */
    const val RETURN_TTL_MS = 5 * 60_000L
}
