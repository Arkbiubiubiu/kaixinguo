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
import com.hongguotv.pad.PlaybackApi
import com.hongguotv.pad.RankTabsel
import com.hongguotv.pad.SeriesCard
import com.hongguotv.pad.SeriesDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/** 刷剧每步计时日志（写本地文件，供定位“卡在某处/某请求多久”）。 */
object FeedTrace {
    @Volatile var enabled = true
    private val file = java.io.File("C:/kaixinguo-feed-trace.log")
    fun log(m: String) { if (!enabled) return; runCatching { file.appendText("${System.currentTimeMillis()}\t$m\n") } }
}

private const val FEED_WARM_COUNT = 4

/** 预取后面几部：详情 + 首集档位预热，有限并发 ≤2，best-effort。 */
private suspend fun warmUpcomingFeed(cards: List<SeriesCard>, fromIndex: Int, count: Int) {
    if (cards.isEmpty() || count <= 0) return
    val end = minOf(cards.size - 1, fromIndex + count - 1)
    if (fromIndex > end) return
    val slice = (fromIndex..end).mapNotNull { cards.getOrNull(it) }
    for (batch in slice.chunked(2)) {
        coroutineScope {
            batch.map { c ->
                async {
                    // 全局并发≤2 + 失败退避 + 命中缓存短路（健康度由 PrefetchGate 统一兜住）
                    PrefetchGate.guardCached("feedwarm:${c.seriesId}") {
                        runCatching {
                            val ep = Api.ensureDetail(c.seriesId)?.episodes?.firstOrNull()
                            if (ep != null) {
                                PlaybackApi.tiers(ep.vid, priority = false)
                                true
                            } else {
                                false
                            }
                        }.getOrDefault(false)
                    }
                }
            }.forEach { it.await() }
        }
    }
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
    var moreLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    // 官方 feed 续推状态：累计 filter_ids + rec_filter_info（回传给 feedCell）
    val filterIds = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val recInfos = remember { androidx.compose.runtime.mutableStateListOf<JSONObject>() }
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
        // 每次进入刷剧都必须现拉，不显示旧列表。官方 feed：首屏 feedCell(空 filter) → 播
        loading = true
        cards = emptyList()
        cursor = 0
        hasMore = true
        filterIds.clear()
        recInfos.clear()
        val type = feedTypes[tab]
        FeedTrace.log("S1 start tab=$type")
        val t0s1 = System.currentTimeMillis()
        val res = withContext(Dispatchers.IO) {
            runCatching { Api.feedCell(type, emptyList(), emptyList()) }.getOrNull()
        }
        if (res != null) {
            cards = res.cards
            filterIds.addAll(res.filterIds)
            recInfos.addAll(res.recFilterInfos)
            hasMore = res.hasMore
        }
        FeedTrace.log("S1 done ms=${System.currentTimeMillis() - t0s1} n=${cards.size} hasMore=$hasMore f=${filterIds.size} rec=${recInfos.size} resNull=${res == null}")
        loading = false
        // 预取后面几部（详情 + 首集档位预热），首屏之后再预热，别在首屏阶段抢。
        launch {
            kotlinx.coroutines.delay(2_000)
            FeedTrace.log("WARM start")
            val tw = System.currentTimeMillis()
            warmUpcomingFeed(cards, cursor + 1, FEED_WARM_COUNT)
            FeedTrace.log("WARM done ms=${System.currentTimeMillis() - tw}")
        }
    }

    // 位置随变随记：进详情时这一页会离开组合，状态只能放在单例里
    LaunchedEffect(tab, cursor, cards) {
        PageMemo.feedTab = tab
        PageMemo.feedCursor = cursor
        PageMemo.feedCards = cards
        PageMemo.feedPage = null
    }

    /** 到底/不够：feedCell(累计 filter_ids + rec_filter_info) 追加，不重置当前播放。 */
    fun loadMore() {
        if (moreLoading || !hasMore) { FeedTrace.log("LM skip cur=$cursor size=${cards.size} more=$moreLoading has=$hasMore"); return }
        moreLoading = true
        val type = feedTypes[tab]
        val fIds = filterIds.toList()
        val recs = recInfos.toList()
        val have = cards.map { it.seriesId }.toHashSet()
        scope.launch(Dispatchers.IO) {
            FeedTrace.log("LM start cur=$cursor size=${cards.size} f=${filterIds.size} rec=${recInfos.size}")
            val t0 = System.currentTimeMillis()
            val res = runCatching { Api.feedCell(type, fIds, recs) }.getOrNull()
            val ms = System.currentTimeMillis() - t0
            withContext(Dispatchers.Main) {
                if (res != null) {
                    val add = res.cards.filter { it.seriesId !in have }
                    if (add.isNotEmpty()) cards = cards + add
                    // feedCell 返回的是“含本批在内”的累计 filter；直接替换，避免重复累加膨胀
                    filterIds.clear(); filterIds.addAll(res.filterIds)
                    recInfos.clear(); recInfos.addAll(res.recFilterInfos)
                    hasMore = res.hasMore && res.cards.isNotEmpty()
                    FeedTrace.log("LM done ms=$ms got=${res.cards.size} add=${add.size} newSize=${cards.size} hasMore=$hasMore")
                } else {
                    hasMore = false
                    FeedTrace.log("LM NULL ms=$ms -> hasMore=false")
                }
                moreLoading = false
            }
        }
    }

    // 滚轮换剧（旧版 onWheelStep 同语义）：向下=下一部，向上=上一部；到底续推官方 feed
    fun wheelStep(dir: Int) {
        val next = cursor + dir
        if (next < 0) return
        if (next >= cards.size) {
            FeedTrace.log("WHEEL end cur=$cursor size=${cards.size} more=$moreLoading has=$hasMore next=$next")
            if (hasMore) loadMore()
            return
        }
        cursor = next
    }

    // 提前预取：保持领先约 6 部（距尾≤5部就在后台续推），别到最后一部才卡一下再蹦新剧
    LaunchedEffect(cursor, cards.size, hasMore) {
        if (cards.isNotEmpty() && hasMore && !moreLoading && cursor >= cards.size - 5) {
            FeedTrace.log("PRE trigger cur=$cursor size=${cards.size}")
            loadMore()
        }
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
            feedIndex = cursor,
            feedTotal = cards.size,
        )
    }
}

// ---------------------------------------------------------------- 找剧
