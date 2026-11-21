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

/**
 * 刷剧候选池加载（只动取数与挑选，不碰播放/取流）：
 *   1) 首屏 Api.firstPage(type, refresh=true)；
 *   2) 按 nextOffset 连续 feedNext 深翻，直到 distinct ≥ [FEED_POOL_MIN] 或翻满 [FEED_MAX_PAGES] 页；
 *   3) LinkedHashMap 按 seriesId 去重；runCatching 容错，失败就停在已有池；游标不动就 break 防死循环。
 * 返回 (候选池, 最后游标)——最后游标给「换剧到列表尾」继续 feedNext 用。
 */
private const val FEED_POOL_MIN = 48
private const val FEED_MAX_PAGES = 8

private suspend fun loadFeedPool(tabType: String): Pair<List<SeriesCard>, FeedPage?> =
    withContext(Dispatchers.IO) {
        val first = runCatching { Api.firstPage(tabType, refresh = true) }.getOrNull()
            ?: return@withContext (emptyList<SeriesCard>() to null)
        val pool = LinkedHashMap<String, SeriesCard>()
        first.cards.forEach { if (it.seriesId.isNotBlank()) pool[it.seriesId] = it }
        var cur = first
        var pages = 1
        while (pool.size < FEED_POOL_MIN && pages < FEED_MAX_PAGES &&
            cur.hasMore && cur.cellId.isNotEmpty()
        ) {
            val prevOffset = cur.nextOffset
            val next = runCatching {
                Api.feedNext(
                    tabType = tabType,
                    cellId = cur.cellId,
                    planId = cur.planId,
                    sessionId = cur.sessionId,
                    offset = cur.nextOffset,
                    seen = pool.keys,
                    refresh = true,
                )
            }.getOrNull() ?: break
            var added = 0
            next.cards.forEach { c ->
                if (c.seriesId.isNotBlank() && pool.putIfAbsent(c.seriesId, c) == null) added++
            }
            // 游标没前进、也没新增 → 上游到底，停（防死循环）。
            if (next.nextOffset == prevOffset && added == 0) break
            cur = next.copy(
                sessionId = next.sessionId.ifEmpty { cur.sessionId },
                cellId = next.cellId.ifEmpty { cur.cellId },
                planId = next.planId.ifEmpty { cur.planId },
            )
            pages++
        }
        pool.values.toList() to cur
    }

@Composable
internal fun FeedPlayerPage(onDetail: (String) -> Unit, onComments: (String) -> Unit, onExit: () -> Unit) {
    // 三端默认漫剧；顺序与顶部 tab 一致：漫剧 / 真人剧 / 推荐。
    // 上游真实 tab：36=漫剧、39=真人剧、16=推荐；3/4/2 是已失效的旧 id。
    val feedTypes = listOf("36", "39", "16")
    var tab by remember { mutableStateOf(PageMemo.feedTab) }
    var cards by remember { mutableStateOf(PageMemo.feedCards) }
    var cursor by remember { mutableStateOf(PageMemo.feedCursor) }
    // 首次进入先显示 loading，绝不先画旧列表。
    var loading by remember { mutableStateOf(true) }
    /** 翻页游标（cellId/planId/sessionId/offset 都在里面），hasMore=false 后不再翻 */
    var feedCursor by remember { mutableStateOf(PageMemo.feedPage) }
    var moreLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tab) {
        // 从详情/播放返回同一次刷剧会话：保留当前剧与位置（只此一种例外）。
        val returning = PageMemo.feedReturnKeep &&
            cards.isNotEmpty() && tab == PageMemo.feedTab
        PageMemo.feedReturnKeep = false
        if (returning) {
            loading = false
            return@LaunchedEffect
        }
        // 每次进入刷剧都必须现拉，不显示旧列表；可以等 loading。
        loading = true
        cards = emptyList()
        cursor = 0
        feedCursor = null

        // 深翻凑候选池（distinct ≥ 48 或翻满 8 页），再做跨天去重 + 打乱。
        val (rawPool, finalCursor) = loadFeedPool(feedTypes[tab])
        // 跨天去重：优先过滤掉最近推过的（feed_seen_ids）；过滤后不足 12 就放宽用整池兜底。
        val seen = PcStore.feedSeenIds().toHashSet()
        val deduped = rawPool.filter { it.seriesId !in seen }
        val shown = (if (deduped.size >= 12) deduped else rawPool).shuffled()
        cards = shown
        feedCursor = finalCursor
        cursor = 0
        // 本次展示的 seriesId 写回持久化（最近 300，FIFO）。
        PcStore.recordFeedSeen(shown.map { it.seriesId })
        loading = false
    }

    // 位置随变随记：进详情时这一页会离开组合，状态只能放在单例里
    LaunchedEffect(tab, cursor, cards, feedCursor) {
        PageMemo.feedTab = tab
        PageMemo.feedCursor = cursor
        PageMemo.feedCards = cards
        PageMemo.feedPage = feedCursor
    }

    // 滚轮换剧（旧版 onWheelStep 同语义）：向下=下一部，向上=上一部；
    // 接近列表尾（剩 2 部）自动 cell/change 翻页续上
    fun wheelStep(dir: Int) {
        val next = cursor + dir
        if (next < 0) return
        if (next >= cards.size) {
            val c = feedCursor ?: return
            if (!c.hasMore || moreLoading) return
            moreLoading = true
            scope.launch(Dispatchers.IO) {
                val np = runCatching {
                    Api.feedNext(
                        feedTypes[tab], c.cellId, c.planId, c.sessionId, c.nextOffset,
                        cards.map { it.seriesId }, refresh = true,
                    )
                }.getOrNull()
                if (np != null && np.cards.isNotEmpty()) {
                    val known = cards.map { it.seriesId }.toSet()
                    cards = cards + np.cards.filter { it.seriesId !in known }
                    feedCursor = np
                } else {
                    feedCursor = c.copy(hasMore = false)
                }
                moreLoading = false
            }
            return
        }
        cursor = next
    }

    val current = cards.getOrNull(cursor)
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = Palette.accent, strokeWidth = 3.dp, modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("正在加载…", color = Palette.dim, fontSize = 14.sp)
            }
        }
        current == null -> EmptyState(
            title = "没有拿到推荐",
            hint = "检查网络后重试",
            icon = Icons.Filled.PlayArrow,
        )
        else -> PlayerScreen(
            seriesId = current.seriesId,
            startIndex = 1,
            onBack = { PageMemo.feedReturnKeep = false; UiMode.exitAll(); onExit() },
            onDetail = { PageMemo.feedReturnKeep = true; onDetail(current.seriesId) },
            onComments = { PageMemo.feedReturnKeep = true; onComments(current.seriesId) },
            embedded = true,
            feedTab = tab,
            onFeedTab = { PageMemo.feedReturnKeep = false; tab = it; cursor = 0 },
            onWheelStep = { dir -> wheelStep(dir) },
        )
    }
}

// ---------------------------------------------------------------- 找剧
