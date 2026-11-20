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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

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
        // 用户要求：每次进入刷剧都必须现拉，不显示旧列表；可以等 loading。
        loading = true
        cards = emptyList()
        cursor = 0
        feedCursor = null
        val page = withContext(Dispatchers.IO) {
            runCatching { Api.firstPage(feedTypes[tab], refresh = true) }.getOrNull()
        }
        if (page?.cards?.isNotEmpty() == true) {
            cards = page.cards
            feedCursor = page
            cursor = 0
        } else {
            cards = emptyList()
        }
        loading = false
    }

    // 位置随变随记：进详情时这一页会离开组合，状态只能放在单例里
    LaunchedEffect(tab, cursor, cards, feedCursor) {
        PageMemo.feedTab = tab
        PageMemo.feedCursor = cursor
        PageMemo.feedCards = cards
        PageMemo.feedPage = feedCursor
    }

    /** 续一页：成功后回调（换剧时推进 cursor；预取时不需要）。 */
    fun loadMore(andThen: (() -> Unit)? = null) {
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
                andThen?.invoke()
            } else {
                feedCursor = c.copy(hasMore = false)
            }
            moreLoading = false
        }
    }

    // 滚轮换剧（旧版 onWheelStep 同语义）：向下=下一部，向上=上一部；
    // 走到列表尾自动续一页
    fun wheelStep(dir: Int) {
        val next = cursor + dir
        if (next < 0) return
        if (next >= cards.size) {
            loadMore { cursor = next.coerceAtMost(cards.lastIndex) }
            return
        }
        cursor = next
    }

    // 刚进刷剧就把下一页拉好，不等切到第 16 集现拉
    var autoPrefetched by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { autoPrefetched = false }
    LaunchedEffect(feedCursor, cards.size) {
        val c = feedCursor ?: return@LaunchedEffect
        if (!c.hasMore || autoPrefetched) return@LaunchedEffect
        delay(1_200)
        autoPrefetched = true
        loadMore()
    }
    LaunchedEffect(cursor, cards.size, feedCursor) {
        if (feedCursor?.hasMore == true && cursor >= cards.size - 4) loadMore()
    }

    // 刷剧预载：PC 端不做全量视频预取（会和正在播的那一路抢带宽），
    // 只保留前面的「下一页提前加载」；切剧仍按正常流程取流。

    val current = cards.getOrNull(cursor)
    when {
        loading -> CenterLoading()
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
