@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.kaixinguo.pc

import androidx.compose.ui.graphics.toArgb

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hongguotv.pad.ShelfKind
import com.hongguotv.pad.ShelfRepository
import com.hongguotv.pad.Api
import com.hongguotv.pad.App
import com.hongguotv.pad.FeedPage
import com.hongguotv.pad.FilterRow
import com.hongguotv.pad.HistoryApi
import com.hongguotv.pad.HistoryRepository
import com.hongguotv.pad.HistoryShadow
import com.hongguotv.pad.Log
import com.hongguotv.pad.RankTabsel
import com.hongguotv.pad.SeriesCard
import com.hongguotv.pad.SeriesDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun HomePage(
    tab: Int,
    refresh: Int,
    onTab: (Int) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
) {
    var page by remember { mutableStateOf<FeedPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var heroDetail by remember { mutableStateOf<SeriesDetail?>(null) }
    /** 上一页的游标：cellId/planId/sessionId/offset 都在里面，翻页要用 */
    var cursor by remember { mutableStateOf<FeedPage?>(null) }
    var moreLoading by remember { mutableStateOf(false) }
    // 不跟 page 绑定：数据刷新（热度补齐等）不能把 hero 拉回第一张
    var heroIndex by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    /** 推荐墙的滚动状态：滚到靠底就自动续拉，不再放按钮 */
    val wallScroll = PageMemo.homeWall

    // 与安卓/TV 同一套首页 tab_type（推荐 16 / 漫剧 36 / 真人剧 39）；
    // 旧的 2/3/4 是另一条推荐池，两端内容会对不上
    val tabTypes = listOf("16", "36", "39")

    LaunchedEffect(tab, refresh) {
        // 只有切 tab / 换一批才回到墙顶；从详情返回时保留原来的滚动位置
        val resetWall = PageMemo.homeTab != tab || PageMemo.homeRefresh != refresh
        PageMemo.homeTab = tab
        PageMemo.homeRefresh = refresh
        loading = true
        heroIndex = 0
        val result = withContext(Dispatchers.IO) {
            runCatching { Api.firstPageCached(tabTypes[tab.coerceIn(0, tabTypes.lastIndex)]) }.getOrNull()
        }
        var cur = result
        // 墙要「满」：首批不足两屏（约 16 张）时自动续拉，最多补 2 轮（旧版同语义）
        var rounds = 0
        while (cur != null && cur.cards.size < 16 && cur.hasMore && rounds < 2) {
            val next = withContext(Dispatchers.IO) {
                runCatching {
                    Api.feedNext(
                        tabTypes[tab.coerceIn(0, tabTypes.lastIndex)],
                        cur.cellId, cur.planId, cur.sessionId, cur.nextOffset,
                        cur.cards.map { it.seriesId },
                    )
                }.getOrNull()
            } ?: break
            if (next.cards.isEmpty()) break
            cur = next.copy(cards = cur.cards + next.cards)
            rounds++
        }
        // 列表接口自带的热度是旧值/播放量口径，和详情对不上；
        // /api/heat 返回的就是详情 hot_score，首屏统一刷一遍再上屏
        cur = cur?.let { c ->
            val fixed = withContext(Dispatchers.IO) {
                runCatching { Api.fillHeat(c.cards) }.getOrDefault(c.cards)
            }
            c.copy(cards = fixed)
        }
        page = cur
        cursor = cur
        error = if (cur == null || cur.cards.isEmpty()) "暂时没能加载出来 · 检查网络后重试" else null
        loading = false
        // 换 tab / 换一批回到墙顶（滚动状态是常驻的，不重置会停在上一次的底部）
        if (resetWall) runCatching { wallScroll.scrollTo(0) }
    }

    val cards = page?.cards.orEmpty()
    // hero 可左右翻（旧版：hero 覆盖整条流，箭头切主角，底部计数 N / 总数）
    val safeHeroIndex = heroIndex.coerceIn(0, (cards.size - 1).coerceAtLeast(0))
    val hero = cards.getOrNull(safeHeroIndex)
    LaunchedEffect(hero?.seriesId) {
        val id = hero?.seriesId ?: return@LaunchedEffect
        heroDetail = withContext(Dispatchers.IO) { runCatching { Api.ensureDetail(id) }.getOrNull() }
    }

    // 「换一批」：feed 游标前进一页当新首屏（firstPage 每次返回同批，
    // 只重拉没有换批效果）；到尾就回绕首批
    fun swapBatch() {
        Api.clearListCache()   // 换一批：作废列表缓存，强制拉新
        val c = cursor ?: return
        if (moreLoading) return
        scope.launch {
            moreLoading = true
            heroIndex = 0
            val type = tabTypes[tab.coerceIn(0, tabTypes.lastIndex)]
            val np = if (c.hasMore) withContext(Dispatchers.IO) {
                runCatching {
                    Api.feedNext(type, c.cellId, c.planId, c.sessionId, c.nextOffset, cards.map { it.seriesId })
                }.getOrNull()
            } else null
            if (np != null && np.cards.isNotEmpty()) {
                val fixed = withContext(Dispatchers.IO) {
                    runCatching { Api.fillHeat(np.cards) }.getOrDefault(np.cards)
                }
                page = np.copy(cards = fixed)
                cursor = np
                error = null
            } else {
                val r = withContext(Dispatchers.IO) {
                    runCatching { Api.firstPage(type) }.getOrNull()
                }
                page = r
                cursor = r
            }
            moreLoading = false
            runCatching { wallScroll.scrollTo(0) }
        }
    }

    /** 自动续拉下一页：滚到靠底 / 首屏不满两屏时都会被调用 */
    fun loadMore() {
        val c = cursor ?: return
        if (moreLoading || !c.hasMore) return
        moreLoading = true
        scope.launch {
            val type = tabTypes[tab.coerceIn(0, tabTypes.lastIndex)]
            val next = withContext(Dispatchers.IO) {
                runCatching {
                    Api.feedNext(type, c.cellId, c.planId, c.sessionId, c.nextOffset, cards.map { it.seriesId })
                }.getOrNull()
            }
            val cur = page
            if (next != null && next.cards.isNotEmpty() && cur != null) {
                val known = cur.cards.map { it.seriesId }.toSet()
                val fixed = withContext(Dispatchers.IO) {
                    runCatching { Api.fillHeat(next.cards) }.getOrDefault(next.cards)
                }
                page = next.copy(cards = cur.cards + fixed.filter { it.seriesId !in known })
                cursor = next
            } else {
                cursor = c.copy(hasMore = false)
            }
            moreLoading = false
        }
    }

    // 滚到离底 700px 内自动拉下一页（用户要求：不要按钮，滑到底自己出）
    LaunchedEffect(wallScroll, cursor?.hasMore, cursor?.nextOffset, moreLoading) {
        if (cursor?.hasMore != true) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { wallScroll.value to wallScroll.maxValue }
            .collect { (v, max) ->
                if (max > 0 && max - v < 700) loadMore()
            }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = (maxHeight * 0.5f - 40.dp).coerceIn(340.dp, 560.dp)
        Column(Modifier.fillMaxSize()) {
            // 页头薄行：分类 tab + 右端「换一批」（旧版 home-wall 顶部那一行）
            Row(
                Modifier.fillMaxWidth().padding(start = 0.dp, end = 24.dp, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("推荐", "漫剧", "真人剧").forEachIndexed { i, label ->
                    TabChip(label, i == tab) { if (i == tab) onRefresh() else onTab(i) }
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.weight(1f))
                HeaderAction("换一批", onClick = { swapBatch() })
            }
            Box(Modifier.fillMaxSize().padding(start = 0.dp, end = 24.dp)) {
                when {
                    // 首屏骨架（旧版 WallSkeleton：墙形灰砖比转圈更像成品）
                    loading && page == null -> Box(Modifier.fillMaxSize().padding(top = 8.dp)) { SkeletonWall() }
                    error != null && (page?.cards.isNullOrEmpty()) -> CenterRetry(error!!) { onRefresh() }
                    else -> Column(Modifier.fillMaxSize().verticalScroll(wallScroll)) {
                        if (hero != null) {
                            HeroCard(
                                hero,
                                heroDetail,
                                onPlay = { onPlay(hero.seriesId) },
                                onDetail = { onOpen(hero.seriesId) },
                                position = safeHeroIndex,
                                count = cards.size,
                                onNavigate = { d -> heroIndex = (safeHeroIndex + d + cards.size) % cards.size },
                                height = heroHeight,
                            )
                            Spacer(Modifier.height(20.dp))
                        }
                        PosterGrid(
                            cards = cards,
                            onOpen = { onOpen(it.seriesId) },
                            onPlay = { onPlay(it.seriesId) },
                            onCollect = { PcStore.toggle("favorite", it.seriesId) },
                            scrollable = false,
                            coverBadge = { c -> if (c.episodeCount > 0) "${c.episodeCount} 集" else null },
                            tagsLine = { c -> c.tags.take(3).joinToString(" ").takeIf { it.isNotBlank() } },
                        )
                        // 底部只是一行状态：滚动到底自动续拉，不再有「加载更多」按钮
                        val c = cursor
                        if (page != null) {
                            Spacer(Modifier.height(20.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 24.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (moreLoading) {
                                    CircularProgressIndicator(
                                        color = Palette.accent,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text("正在加载更多…", color = Palette.faint, fontSize = 12.sp)
                                } else if (c != null && !c.hasMore && cards.isNotEmpty()) {
                                    Text("已经到底了 · 共 ${cards.size} 部", color = Palette.faint, fontSize = 12.sp)
                                } else if (c != null && c.hasMore && cards.isNotEmpty()) {
                                    Text("继续下滑，自动加载更多", color = Palette.faint, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                // 切 tab/换一批拉取期间：旧内容保留在下方但要能看出来在刷新
                // （此前切 tab 后旧墙静默停留 1-3 秒，像点了没反应）
                if (loading && page != null) {
                    Box(
                        Modifier.matchParentSize().background(Color(0x66000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Palette.accent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 刷剧（整屏播放）
