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

/**
 * 刷剧取池/挑选（只动取数与挑选，不碰播放/取流）：
 *  - 秒开：先只等 firstPage，跨天去重后立即开播；
 *  - 后台深翻当前分类（distinct ≥ [FEED_POOL_MIN] 或 ≤[FEED_MAX_PAGES] 页），并对另外两个
 *    分类各补一部分（跨分类混合，整体更多样）；
 *  - LinkedHashMap 按 seriesId 去重，runCatching 容错，失败停在已有池，游标不动就 break。
 */
private const val FEED_POOL_MIN = 48
private const val FEED_MAX_PAGES = 8
private const val FEED_PER_OTHER = 16
private const val FEED_WARM_COUNT = 4

/** 跨天去重：优先过滤最近推过的；过滤后不足 12 就放宽用整页。 */
private fun pickFeed(raw: List<SeriesCard>, seen: Set<String>): List<SeriesCard> {
    val distinct = raw.filter { it.seriesId.isNotBlank() }.distinctBy { it.seriesId }
    val fresh = distinct.filter { it.seriesId !in seen }
    return if (fresh.size >= 12) fresh else distinct
}

/** 在 [pool] 上把某分类深翻到 target 张或翻满 maxPages 页；返回最后游标。 */
private fun deepFill(
    tabType: String,
    pool: LinkedHashMap<String, SeriesCard>,
    target: Int,
    maxPages: Int,
    start: FeedPage,
): FeedPage {
    var cur = start
    var pages = 1
    while (pool.size < target && pages < maxPages && cur.hasMore && cur.cellId.isNotEmpty()) {
        val prevOffset = cur.nextOffset
        val next = runCatching {
            Api.feedNext(tabType, cur.cellId, cur.planId, cur.sessionId, cur.nextOffset, pool.keys, refresh = true)
        }.getOrNull() ?: break
        var added = 0
        next.cards.forEach { c ->
            if (c.seriesId.isNotBlank() && pool.putIfAbsent(c.seriesId, c) == null) added++
        }
        if (next.nextOffset == prevOffset && added == 0) break
        cur = next.copy(
            sessionId = next.sessionId.ifEmpty { cur.sessionId },
            cellId = next.cellId.ifEmpty { cur.cellId },
            planId = next.planId.ifEmpty { cur.planId },
        )
        pages++
    }
    return cur
}

/**
 * 后台补池：当前分类深翻为主 + 另外两个分类各补一部分，合并去重。
 * 返回 (合并候选池, 当前分类最深游标[给「换剧到尾部」续用])。
 */
private suspend fun buildFeedPool(
    currentType: String,
    allTypes: List<String>,
    seed: FeedPage?,
    minTotal: Int,
    maxPages: Int,
    perOther: Int,
): Pair<List<SeriesCard>, FeedPage?> = withContext(Dispatchers.IO) {
    val pool = LinkedHashMap<String, SeriesCard>()
    var curCursor: FeedPage? = null
    val first = seed ?: runCatching { Api.firstPage(currentType, refresh = true) }.getOrNull()
    if (first != null) {
        first.cards.forEach { if (it.seriesId.isNotBlank()) pool[it.seriesId] = it }
        curCursor = deepFill(currentType, pool, minTotal, maxPages, first)
    }
    // 跨分类：只有 perOther>0（推荐 16）才跨分类各补一部分；具体分类不再串台。
    for (t in allTypes) {
        if (perOther <= 0) break
        if (t == currentType) continue
        val p0 = runCatching { Api.firstPage(t, refresh = true) }.getOrNull() ?: continue
        p0.cards.forEach { if (it.seriesId.isNotBlank()) pool.putIfAbsent(it.seriesId, it) }
        if (perOther <= p0.cards.size) continue
        var c = p0
        var pages = 1
        while (pages < 2 && c.hasMore && c.cellId.isNotEmpty()) { // 其它分类最多再翻 1 页
            val prevOffset = c.nextOffset
            val next = runCatching {
                Api.feedNext(t, c.cellId, c.planId, c.sessionId, c.nextOffset, pool.keys, refresh = true)
            }.getOrNull() ?: break
            var added = 0
            next.cards.forEach { x ->
                if (x.seriesId.isNotBlank() && pool.putIfAbsent(x.seriesId, x) == null) added++
            }
            if (next.nextOffset == prevOffset && added == 0) break
            c = next.copy(
                sessionId = next.sessionId.ifEmpty { c.sessionId },
                cellId = next.cellId.ifEmpty { c.cellId },
                planId = next.planId.ifEmpty { c.planId },
            )
            pages++
        }
    }
    pool.values.toList() to curCursor
}

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
                    runCatching {
                        val ep = Api.ensureDetail(c.seriesId)?.episodes?.firstOrNull()
                        if (ep != null) PlaybackApi.tiers(ep.vid, priority = false)
                    }
                }
            }.forEach { it.await() }
        }
    }
}

private const val RANK_GENRE_COMIC = "comic_series_rank"
private const val RANK_HOT_COMIC = "comic_series_hot_play"
private const val RANK_NEW_COMIC = "comic_series_new_rank"
private const val RANK_GENRE_HUMAN = "human"
private const val RANK_HOT_HUMAN = "human_hot_play"
private const val RANK_NEW_HUMAN = "human_new_rank"

/** 剔除集合：①登录时云端历史 ②本地历史 ③预约列表里「已上线」的剧（未上线不剔）。 */
private suspend fun buildFeedExcludeSet(): Set<String> = withContext(Dispatchers.IO) {
    val ex = HashSet<String>()
    if (PcStore.loggedIn) {
        runCatching { HistoryApi.official(3) }.getOrDefault(emptyList())
            .forEach { ex.add(it.optString("seriesId")) }
    }
    runCatching { PcStore.history() }.getOrDefault(emptyList())
        .forEach { ex.add(it.optString("seriesId")) }
    if (PcStore.loggedIn) {
        runCatching { Api.reserveListCached() }.getOrDefault(emptyList())
            .forEach { if (!it.upcoming) ex.add(it.seriesId) }
    }
    ex.remove("")
    ex
}

/** 单个榜单最多翻 2 页，按入池顺序去重合并（热播榜先入=在前）。 */
private fun fetchRankTwoPages(genre: String, sub: String, into: LinkedHashMap<String, SeriesCard>) {
    var page = runCatching { Api.rank(0, "", genre, sub, "", "") }.getOrNull() ?: return
    page.cards.forEach { if (it.seriesId.isNotBlank()) into.putIfAbsent(it.seriesId, it) }
    var n = 1
    while (n < 2 && page.hasMore && page.cellId.isNotEmpty()) {
        val prevOffset = page.nextOffset
        val next = runCatching { Api.rank(page.nextOffset, page.sessionId, genre, sub, "", "") }.getOrNull() ?: break
        var added = 0
        next.cards.forEach { if (it.seriesId.isNotBlank() && into.putIfAbsent(it.seriesId, it) == null) added++ }
        if (added == 0 && next.nextOffset == prevOffset) break
        page = next
        n++
    }
}

/**
 * 36/39 候选池：热播榜(前) + 新剧榜(后) 合并去重 → 剔除 ①②③ + 跨天 seen →
 * 不足 12 用 feed 流（同样先剔除 ①②③）补足到 48。返回 (卡片, feed 兜底游标)。
 */
private suspend fun buildRankPool(type: String, seen: Set<String>): Pair<List<SeriesCard>, FeedPage?> =
    withContext(Dispatchers.IO) {
        val genre = if (type == "36") RANK_GENRE_COMIC else RANK_GENRE_HUMAN
        val hotSub = if (type == "36") RANK_HOT_COMIC else RANK_HOT_HUMAN
        val newSub = if (type == "36") RANK_NEW_COMIC else RANK_NEW_HUMAN
        val pool = LinkedHashMap<String, SeriesCard>()
        fetchRankTwoPages(genre, hotSub, pool)   // 热播在前
        fetchRankTwoPages(genre, newSub, pool)   // 新剧在后
        val exclude = buildFeedExcludeSet() + seen
        var cards = pool.values.filter { it.seriesId !in exclude }
        var cursor: FeedPage? = null
        if (cards.size < 12) {
            val (fb, cur) = buildFeedPool(type, listOf(type), null, FEED_POOL_MIN, FEED_MAX_PAGES, 0)
            cursor = cur
            val have = cards.map { it.seriesId }.toHashSet()
            val extra = fb.filter { it.seriesId !in exclude && it.seriesId !in have }
            cards = (cards + extra).take(FEED_POOL_MIN)
        }
        cards to cursor
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
        // 每次进入刷剧都必须现拉，不显示旧列表。
        loading = true
        cards = emptyList()
        cursor = 0
        feedCursor = null

        val type = feedTypes[tab]
        val seen = PcStore.feedSeenIds().toHashSet()

        if (type == "16") {
            // 推荐(16)：完全不变 —— feed 流秒开 + 后台深翻补池。
            val first = withContext(Dispatchers.IO) {
                runCatching { Api.firstPage(type, refresh = true) }.getOrNull()
            }
            val firstShown = pickFeed(first?.cards.orEmpty(), seen).shuffled()
            cards = firstShown
            feedCursor = first
            cursor = 0
            PcStore.recordFeedSeen(firstShown.map { it.seriesId })
            loading = false
            launch {
                kotlinx.coroutines.delay(500) // 先让首帧起来，别抢带宽
                val (fullPool, deepCursor) =
                    buildFeedPool(type, feedTypes, first, FEED_POOL_MIN, FEED_MAX_PAGES, FEED_PER_OTHER)
                val existingNow = cards.map { it.seriesId }.toHashSet()
                val appended = fullPool.filter { it.seriesId !in existingNow && it.seriesId !in seen }.shuffled()
                if (appended.isNotEmpty()) {
                    cards = cards + appended
                    PcStore.recordFeedSeen(appended.map { it.seriesId })
                }
                if (deepCursor != null) {
                    val oldOffset = feedCursor?.nextOffset ?: -1
                    if (deepCursor.nextOffset > oldOffset) feedCursor = deepCursor
                }
            }
        } else {
            // 漫剧(36)/真人剧(39)：从「热播榜 + 新剧榜」取候选（不再用 feed 流顺序），并剔除已看/已预约上线。
            val (poolCards, poolCursor) = withContext(Dispatchers.IO) {
                runCatching { buildRankPool(type, seen) }.getOrElse { emptyList<SeriesCard>() to null }
            }
            cards = poolCards
            feedCursor = poolCursor
            cursor = 0
            PcStore.recordFeedSeen(poolCards.map { it.seriesId })
            loading = false
        }

        // 预取后面几部（详情 + 首集档位预热），首屏之后再预热，别在首屏阶段抢。
        launch {
            kotlinx.coroutines.delay(2_000)
            warmUpcomingFeed(cards, cursor + 1, FEED_WARM_COUNT)
        }
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
            val c = feedCursor
            if (c == null) {
                // rank 池没有游标：换到尾部时用 feed 流兜底补一段（同样剔除已看/已预约上线），不崩。
                if (moreLoading) return
                moreLoading = true
                val type = feedTypes[tab]
                scope.launch(Dispatchers.IO) {
                    val seenNow = PcStore.feedSeenIds().toHashSet()
                    val exclude = runCatching { buildFeedExcludeSet() }.getOrDefault(emptySet()) + seenNow
                    val (fb, cur) = runCatching {
                        buildFeedPool(type, listOf(type), null, FEED_POOL_MIN, FEED_MAX_PAGES, 0)
                    }.getOrElse { emptyList<SeriesCard>() to null }
                    withContext(Dispatchers.Main) {
                        val have = cards.map { it.seriesId }.toHashSet()
                        val extra = fb.filter { it.seriesId !in exclude && it.seriesId !in have }
                        if (extra.isNotEmpty()) cards = cards + extra
                        feedCursor = cur ?: FeedPage(hasMore = false)
                        moreLoading = false
                    }
                }
                return
            }
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
            feedIndex = cursor,
            feedTotal = cards.size,
        )
    }
}

// ---------------------------------------------------------------- 找剧
