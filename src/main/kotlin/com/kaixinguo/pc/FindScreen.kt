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
internal fun FindPage(
    search: String,
    onSearch: (String) -> Unit,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
) {
    // 首次进入用 PageMemo 里的上次状态（从详情/播放返回时原样恢复）
    var rows by remember { mutableStateOf(PageMemo.findRows) }
    var rowsLoading by remember { mutableStateOf(PageMemo.findRows.isEmpty()) }
    var selected by remember {
        mutableStateOf(
            PageMemo.findSelected.ifEmpty {
                if (PageMemo.findDefaulted) emptyMap()
                else {
                    PageMemo.findDefaulted = true
                    mapOf("genre" to "comic_series")   // 三端默认漫剧
                }
            },
        )
    }
    var page by remember { mutableStateOf(PageMemo.findPage) }
    var restoredRows by remember { mutableStateOf(PageMemo.findRows.isNotEmpty()) }
    var restoredList by remember { mutableStateOf(PageMemo.findPage != null) }
    var loading by remember { mutableStateOf(false) }
    var moreLoading by remember { mutableStateOf(false) }
    /** 「搜索」按钮的重搜信号：输入是即时的，按钮只是手动再来一次 */
    var searchTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    // 整页一个滚动容器（旧版同款：筛选随内容一起滚走，不是上半屏钉死）
    val pageScroll = PageMemo.findScroll

    // 体裁（大类）一变，主题/设定/背景整块换一套——不同体裁下小类完全不同。
    // 以前这里写死 findRowsCached("landpage")：面板永远是 tab 38（真人剧）那一套，
    // 选「漫剧 + 悬疑」时把真人剧的悬疑 id 发给漫剧频道，结果必然是空。
    val genreSel = selected["genre"].orEmpty()
    LaunchedEffect(genreSel) {
        if (restoredRows) {
            restoredRows = false
            return@LaunchedEffect
        }
        rowsLoading = true
        val r = withContext(Dispatchers.IO) {
            runCatching { Api.findRowsCached(genreSel) }.getOrDefault(emptyList())
        }
        rows = r
        // 新面板里已经没有的旧选择自动作废（和 Android 端同一口径）
        val valid = r.associate { row -> row.key to row.items.map { it.id }.toSet() }
        selected = selected.filter { (k, v) -> k == "genre" || valid[k]?.contains(v) == true }
        rowsLoading = false
    }

    LaunchedEffect(selected, search, searchTick) {
        if (restoredList) {
            restoredList = false
            return@LaunchedEffect
        }
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (search.isNotBlank()) {
                    Api.searchPage(search)
                } else {
                    Api.findList(selected, 0, "")
                }
            }.getOrNull()
        }
        page = result
        loading = false
    }

    // 写回 PageMemo：离开页面后这些状态还在，返回时列表和筛选对得上
    LaunchedEffect(rows) { PageMemo.findRows = rows }
    LaunchedEffect(selected, page) {
        PageMemo.findSelected = selected
        PageMemo.findPage = page
    }

    // 滚动加载：滚到离底部 500px 内自动翻页（旧版哨兵同语义，去掉按钮）
    val cardsNow = page?.cards.orEmpty()
    val hasMoreNow = page?.hasMore == true && search.isBlank()
    LaunchedEffect(pageScroll.value) {
        val max = pageScroll.maxValue
        if (max <= 0) return@LaunchedEffect
        if (max - pageScroll.value > 500) return@LaunchedEffect
        val c = page ?: return@LaunchedEffect
        if (!c.hasMore || moreLoading || loading) return@LaunchedEffect
        moreLoading = true
        val next = withContext(Dispatchers.IO) {
            runCatching { Api.findList(selected, c.nextOffset, c.sessionId) }.getOrNull()
        }
        if (next != null && next.cards.isNotEmpty()) {
            val known = c.cards.map { it.seriesId }.toSet()
            page = c.copy(cards = c.cards + next.cards.filter { it.seriesId !in known })
        } else {
            page = c.copy(hasMore = false)
        }
        moreLoading = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(pageScroll).padding(horizontal = 24.dp),
    ) {
        // 顶部搜索条（旧版 find：整行输入框 + 红色「搜索」+ 右端「共 N 部」）
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp))
                    .background(Palette.coverPlaceholder).border(1.dp, Palette.line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.faint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (search.isEmpty()) {
                        Text("输入剧名 / 关键词", color = Palette.faint, fontSize = 14.sp)
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = search,
                        onValueChange = onSearch,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 14.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.accent),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (search.isNotEmpty()) {
                    Text(
                        "✕",
                        color = Palette.faint,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { onSearch("") }.padding(4.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            PrimaryButton("搜索", null) { searchTick++ }
            Spacer(Modifier.width(14.dp))
            Text("已加载 ${page?.cards?.size ?: 0} 部", color = Palette.dim, fontSize = 12.sp)
        }
        // 筛选项：每个维度一行，横向滚动，和旧 PC 版一致（不再是一整面标签墙）
        if (rowsLoading && rows.isEmpty()) {
            // 骨架占位：筛选项是慢接口（2–8 秒），先把高度占住，避免海报墙跳一下
            repeat(9) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(44.dp).height(10.dp).clip(RoundedCornerShape(3.dp)).background(Palette.panel))
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(10) {
                            Box(Modifier.width(46.dp).height(24.dp).clip(RoundedCornerShape(6.dp)).background(Palette.panel))
                        }
                    }
                }
            }
        } else if (rows.isEmpty()) {
            Text("筛选项加载失败，重开本页可重试", color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    row.name,
                    color = Palette.faint,
                    fontSize = 12.sp,
                    modifier = Modifier.width(56.dp).padding(top = 8.dp),
                )
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 每行第一个永远是「全部」（旧版筛选行口径；接口不保证下发空 id 项）
                    // 体裁这一行的「全部」等于清空整套筛选：别的小类 id 属于上一个体裁，
                    // 留着会被后端当成非法组合，先闪一下空/一条的结果。
                    FilterChip("全部", selected[row.key].isNullOrEmpty()) {
                        selected = if (row.key == "genre") emptyMap() else selected - row.key
                    }
                    row.items.filter { it.id.isNotEmpty() }.forEach { item ->
                        val active = selected[row.key] == item.id
                        FilterChip(item.label, active) {
                            // 换体裁 = 换一整套主题/设定/背景，旧选择当场作废（不等面板回来才清）
                            selected = if (row.key == "genre") mapOf("genre" to item.id)
                            else selected + (row.key to item.id)
                        }
                    }
                }
            }
        }
        if (selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text(
                    "重置筛选",
                    color = Palette.accent,
                    fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { selected = emptyMap() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading && page == null -> CenterLoading()
            page == null -> CenterMessage("没有匹配的剧")
            cardsNow.isEmpty() -> EmptyState(
                title = "没有找到匹配的剧",
                hint = if (selected.isEmpty()) "换个关键词试试" else "放宽筛选条件，或点「重置筛选」",
                icon = Icons.Filled.Search,
            )
            else -> Column(Modifier.fillMaxWidth()) {
                PosterGrid(
                    cards = cardsNow,
                    onOpen = { onOpen(it.seriesId) },
                    onPlay = { onPlay(it.seriesId) },
                    onCollect = { PcStore.toggle("favorite", it.seriesId) },
                    scrollable = false,
                    coverBadge = { c -> if (c.episodeCount > 0) "${c.episodeCount} 集" else null },
                    tagsLine = { c -> c.tags.take(3).joinToString(" ").takeIf { it.isNotBlank() } },
                )
                // 底部状态：翻页中/没有更多（滚动到附近自动触发，不再放按钮）
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        when {
                            moreLoading -> "加载中…"
                            search.isNotBlank() -> "搜索结果已加载 ${cardsNow.size} 部"
                            hasMoreNow -> "下滑加载更多"
                            else -> "已经到底了"
                        },
                        color = Palette.faint,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------- 排行榜
