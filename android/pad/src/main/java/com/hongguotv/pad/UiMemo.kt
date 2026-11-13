package com.hongguotv.pad

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState

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
    var feedTab: Int = 2
    var feedCursor: Int = 0
    var feedCards: List<SeriesCard> = emptyList()
    var feedPage: FeedPage? = null

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

    /** 详情页的滚动位置与页签：从播放返回时回到第 80 集那一段 */
    val detailScroll = ScrollState(0)
    var detailTabSeries: String = ""
    var detailTab: Int = 0
}
