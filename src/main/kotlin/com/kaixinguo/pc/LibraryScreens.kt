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
internal fun HistoryPage(onOpen: (String) -> Unit, onPlay: (String, Int, Long) -> Unit) {
    var rows by remember { mutableStateOf(PcStore.history()) }
    var cloudRows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var syncing by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var manageOpen by remember { mutableStateOf(false) }
    // 与安卓历史页对齐的三个 tab：历史 / 收藏 / 点赞（收藏、点赞复用海报墙）
    var tab by remember { mutableStateOf(0) }

    fun reload() {
        rows = PcStore.history()
    }

    LaunchedEffect(Unit, PcStore.loggedIn) {
        syncing = true
        // 云端历史（read_history/list，官方 App 同源）：登录才有；
        // 未登录用本地观看记录（旧版双模式同语义）
        if (PcStore.loggedIn) {
            cloudRows = withContext(Dispatchers.IO) {
                val cloud = runCatching { HistoryApi.official(3) }.getOrDefault(emptyList())
                // 影子对比：只记日志，不改变旧页面数据源。
                // 用 1500 上限做“展示合并”，否则 PC 的 1374 条会被本地 500 条截断。
                HistoryShadow.report(cloud, HistoryRepository.merge(PcStore.history(), cloud, 1500))
                cloud
            }
            if (App.flags["history_v2"] == true) reload()
        } else {
            // 未登录没有云端历史：直接读本地（原实现调 historySyncAsync 内部立刻 return，再白等 1.5 秒）
            reload()
        }
        syncing = false
    }

    // 数据源：登录 = 云端（跨设备）；未登录 = 本地
    val source: List<JSONObject> = when {
        PcStore.loggedIn && App.flags["history_v2"] == true ->
            HistoryRepository.merge(rows, cloudRows, 1500)
        PcStore.loggedIn && cloudRows.isNotEmpty() -> cloudRows
        else -> rows
    }

    // 官方同款「已看完」判定：看到最后一集即算，与片内进度无关
    fun isFinished(r: JSONObject): Boolean {
        val total = r.optInt("total", 0)
        val ep = r.optInt("episode", 1)
        return total > 0 && ep >= total
    }

    val shown = source.filter { r ->
        val title = r.optString("title")
        val matchSearch = search.isBlank() || title.contains(search.trim(), ignoreCase = true)
        val matchFilter = when (filter) {
            1 -> !isFinished(r)
            2 -> isFinished(r)
            else -> true
        }
        matchSearch && matchFilter
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        // 顶部分类（安卓同款三 tab）：历史 / 收藏 / 点赞
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabChip("历史", tab == 0) { tab = 0 }
            TabChip("收藏", tab == 1) { tab = 1 }
            TabChip("点赞", tab == 2) { tab = 2 }
        }
        if (tab != 0) {
            Spacer(Modifier.height(12.dp))
            ShelfPage(
                key = if (tab == 1) "favorite" else "like",
                onOpen = onOpen,
                onPlay = { id -> onPlay(id, 1, 0L) },
            )
            return@Column
        }
        // 页头：分组 tab + 搜索 + 管理（旧版 history 顶部那一行）
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabChip("全部", filter == 0) { filter = 0 }
            Spacer(Modifier.width(8.dp))
            TabChip("未看完", filter == 1) { filter = 1 }
            Spacer(Modifier.width(8.dp))
            TabChip("已看完", filter == 2) { filter = 2 }
            if (syncing) {
                Spacer(Modifier.width(10.dp))
                Text("正在同步云端历史…", color = Palette.dim, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            ListSearchBox(search, { search = it }, "搜索看过的剧")
            Spacer(Modifier.width(8.dp))
            OutlineButton("管理") { manageOpen = !manageOpen }
        }
        when {
            // 云端历史还在同步（刚登录/换设备首开）时先转圈，不要先甩一句「还没有观看记录」
            syncing && source.isEmpty() -> CenterLoading()
            source.isEmpty() -> EmptyState(
                title = "还没有观看记录",
                hint = "播放过的剧会出现在这里，进度会自动同步",
                icon = Icons.Filled.Refresh,
            )
            shown.isEmpty() -> EmptyState(
                title = "没有匹配的记录",
                hint = "换个关键词，或切换上面的分组",
                icon = Icons.Filled.Search,
            )
            else -> androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(shown) { row ->
                    val seriesId = row.optString("seriesId")
                    HistoryRow(
                        title = row.optString("title"),
                        cover = row.optString("cover"),
                        episode = row.optInt("episode"),
                        total = row.optInt("total"),
                        positionMs = row.optLong("positionMs", 0L),
                        at = row.optLong("at"),
                        onOpen = { onOpen(seriesId) },
                        onPlay = { onPlay(seriesId, row.optInt("episode", 1).coerceAtLeast(1), row.optLong("positionMs", 0L)) },
                    )
                }
            }
        }
    }
    if (manageOpen) {
        Box(
            Modifier.matchParentSize()
                .onPointerEvent(PointerEventType.Press) { manageOpen = false },
        )
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 104.dp, end = 24.dp)
                .width(150.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xF2101014))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
                .padding(vertical = 4.dp),
        ) {
            Text(
                "清空观看记录",
                color = Palette.text,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth()
                    .clickable { manageOpen = false; confirmClear = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
    if (confirmClear) {
        Box(Modifier.matchParentSize().background(Color(0x99000000)).onPointerEvent(PointerEventType.Press) { confirmClear = false })
        Column(
            Modifier.align(Alignment.Center).width(360.dp).clip(RoundedCornerShape(12.dp))
                .background(Palette.panel).padding(20.dp),
        ) {
            Text("清空观看记录？", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("会删除本地历史与播放进度；云端历史下次同步可能还会回来。", color = Palette.dim, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryButton("取消") { confirmClear = false }
                Spacer(Modifier.width(8.dp))
                PrimaryButton("清空", null) { PcStore.clearHistory(); reload(); confirmClear = false }
            }
        }
    }
    }
}

@Composable
internal fun ShelfPage(key: String, onOpen: (String) -> Unit, onPlay: (String) -> Unit) {
    var cards by remember { mutableStateOf<List<SeriesCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var syncing by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(key, tick, PcStore.loggedIn) {
        syncing = tick > 0
        loading = true
        // 收藏以云端为准：先拉云端（1 跳）；只有未登录/云端失败才回落到本地逐条补详情。
        // 原实现先对 N 个 id 串行 ensureDetail（每个 1-3 秒），再被云端结果整批覆盖——前面的请求全白做。
        var list: List<SeriesCard> = emptyList()
        if (key == "favorite" && PcStore.loggedIn) {
            list = withContext(Dispatchers.IO) { runCatching { Api.cloudShelf() }.getOrNull() } ?: emptyList()
        }
        if (list.isEmpty()) {
            val ids = PcStore.ids(key)
            list = withContext(Dispatchers.IO) {
                ids.mapNotNull { id ->
                    runCatching { Api.ensureDetail(id) }.getOrNull()?.let { Api.cardOfDetail(it) }
                }
            }
        }
        cards = list
        loading = false
        syncing = false
    }

    Column(Modifier.fillMaxSize()) {
        if (key == "favorite") {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlineButton(if (syncing) "刷新中…" else "刷新云端（${cards.size}）") { tick++ }
            }
        }
        when {
            loading -> CenterLoading()
            cards.isEmpty() -> EmptyState(
                title = if (key == "like") "还没有点赞" else "还没有收藏",
                hint = if (key == "like") "在播放页点「点赞」，点过赞的剧会出现在这里"
                else "在详情页点「收藏」就会出现在这里",
                icon = if (key == "like") Icons.Filled.Favorite else Icons.Filled.Star,
            )
            else -> PosterGrid(
                cards = cards,
                onOpen = { onOpen(it.seriesId) },
                onPlay = { onPlay(it.seriesId) },
                onCollect = { c ->
                    val was = PcStore.contains(key, c.seriesId)
                    PcStore.toggle(key, c.seriesId)
                    if (was) cards = cards.filterNot { it.seriesId == c.seriesId }
                },
                coverBadge = { if (it.episodeCount > 0) "${it.episodeCount} 集" else null },
            )
        }
    }
}

internal fun historyDate(at: Long): String {
    if (at <= 0) return ""
    val day = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when {
        day == today -> "今天"
        day == today.minusDays(1) -> "昨天"
        else -> "${day.monthValue}月${day.dayOfMonth}日"
    }
}

@Composable
internal fun HistoryRow(
    title: String,
    cover: String,
    episode: Int,
    total: Int,
    positionMs: Long,
    at: Long,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (hovered) Color(0x33FFFFFF) else Palette.line, RoundedCornerShape(12.dp))
            .background(Palette.panel)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onOpen() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            cover,
            Modifier.width(72.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)),
            fallback = title,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 位置徽标（官方同款）：红 10% 底 chip「看到第N集/共M集 时长」
                Text(
                    if (positionMs > 0) "看到第 $episode 集" + (if (total > 0) " / 共 $total 集" else "") + "  " + fmtTime(positionMs)
                    else "看到第 $episode 集" + (if (total > 0) " / 共 $total 集" else ""),
                    color = Palette.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(Radius.xs)
                        .background(Palette.accent.copy(alpha = 0.10f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                // 相对时间（今天/昨天/N天前）
                val time = historyDate(at)
                if (time.isNotBlank()) Text(time, color = Palette.dim, fontSize = 12.sp)
            }
        }
        // 继续播放 = 红描边按钮（本屏唯一 accent 动作）
        Row(
            Modifier.clip(Radius.ctrl)
                .border(1.dp, Palette.accent.copy(alpha = 0.6f), Radius.ctrl)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .clickable { onPlay() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("继续播放", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ---------------------------------------------------------------- 追更（追更的剧 / 更新提醒）

@Composable
internal fun FollowsPage(
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
    onPlayEp: (String, Int) -> Unit = { id, _ -> onPlay(id) },
    onLogin: () -> Unit = {},
) {
    var tab by remember { mutableStateOf(0) }
    var cards by remember { mutableStateOf<List<SeriesCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 更新提醒（本地通知：服务端集数 vs 本地基线，旧版 follow_service 同口径）
    var notices by remember { mutableStateOf(PcStore.followNotices()) }
    var checking by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loading = true
        val result = withContext(Dispatchers.IO) { runCatching { Api.followList() } }
        cards = result.getOrDefault(emptyList())
        error = result.exceptionOrNull()?.message
        loading = false
    }

    fun refreshNotices() { notices = PcStore.followNotices() }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabChip("追更的剧", tab == 0) { tab = 0 }
            Spacer(Modifier.width(8.dp))
            val unread = notices.count { !it.optBoolean("read", false) }
            TabChip(if (unread > 0) "更新提醒 $unread" else "更新提醒", tab == 1) { tab = 1 }
            if (tab == 1) {
                Spacer(Modifier.weight(1f))
                SecondaryButton(if (checking) "检查中…" else "检查更新", icon = Icons.Filled.Refresh) {
                    if (checking) return@SecondaryButton
                    checking = true
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { PcStore.checkFollowUpdates() } }
                        refreshNotices()
                        checking = false
                    }
                }
                if (notices.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("全部已读") { PcStore.markAllNoticesRead(); refreshNotices() }
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("清空") { PcStore.clearNotices(); refreshNotices() }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                tab == 1 -> NoticesList(
                    notices = notices,
                    onRead = { id -> PcStore.markNoticeRead(id); refreshNotices() },
                    onPlay = { n ->
                        val sid = n.optString("seriesId")
                        val ep = n.optInt("episode", 0)
                        PcStore.markNoticeRead(n.optLong("id", -1L)); refreshNotices()
                        if (ep > 0) onPlayEp(sid, ep) else onPlay(sid)
                    },
                )
                loading -> CenterLoading()
                cards.isEmpty() && error != null -> CenterMessage("加载失败：$error")
                !PcStore.loggedIn -> EmptyState(
                    title = "登录后才能同步追更",
                    hint = "追更跟着账号走：登录后在这里看更新，也可以在任意设备用匹配码同步",
                    icon = FollowsEmptyIcon,
                    actionLabel = "手机号登录",
                    onAction = onLogin,
                )
                cards.isEmpty() -> EmptyState(
                    title = "还没有追更的剧",
                    hint = "在详情页点「追更」，有新集时会出现在这里",
                    icon = FollowsEmptyIcon,
                )
                else -> PosterGrid(
                    cards = cards,
                    onOpen = { onOpen(it.seriesId) },
                    onPlay = { onPlay(it.seriesId) },
                    onCollect = { PcStore.toggle("favorite", it.seriesId) },
                    modifier = Modifier.fillMaxSize(),
                    coverBadge = { c -> if (c.episodeCount > 0) "${c.episodeCount} 集" else null },
                    tagsLine = { c -> c.tags.take(3).joinToString(" ").takeIf { it.isNotBlank() } },
                )
            }
        }
    }
}

/** 更新提醒列表（旧版「更新提醒」tab：未读点 + 标题 + 详情 + 相对时间，点=已读+起播）。 */
@Composable
internal fun NoticesList(
    notices: List<org.json.JSONObject>,
    onRead: (Long) -> Unit,
    onPlay: (org.json.JSONObject) -> Unit,
) {
    if (notices.isEmpty()) {
        EmptyState(
            title = "还没有更新提醒",
            hint = "追更的剧有新集时会在这里提醒你；也可点「检查更新」立即查",
            icon = FollowsEmptyIcon,
        )
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        items(notices.size) { i ->
            val n = notices[i]
            val read = n.optBoolean("read", false)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Palette.panel)
                    .border(1.dp, Palette.line, RoundedCornerShape(12.dp))
                    .clickable { onPlay(n) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.NotificationsActive,
                    contentDescription = null,
                    tint = if (read) Palette.dim else Palette.accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            n.optString("title"),
                            color = Palette.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!read) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.size(7.dp).clip(RoundedCornerShape(50))
                                    .background(Palette.accent),
                            )
                        }
                    }
                    Text(n.optString("detail"), color = Palette.dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(historyDate(n.optLong("at", 0L)), color = Palette.faint, fontSize = 11.sp)
                }
                Spacer(Modifier.width(12.dp))
                PrimaryButton("播放", Icons.Filled.PlayArrow) { onPlay(n) }
            }
        }
    }
}

// ---------------------------------------------------------------- 预约（已上线 / 待上线）

@Composable
internal fun ReservePage(onOpen: (String) -> Unit, onPlay: (String) -> Unit) {
    var cards by remember { mutableStateOf<List<SeriesCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(0) } // 0=已上线（旧版默认 tab）
    var search by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        loading = true
        cards = withContext(Dispatchers.IO) { runCatching { Api.reserveListCached() }.getOrDefault(emptyList()) }
        loading = false
    }

    val online = cards.filter { !it.upcoming }
    val upcoming = cards.filter { it.upcoming }
    val onlineCount = online.size
    val upcomingCount = upcoming.size
    val shown = (if (tab == 0) online else upcoming)
        .filter { search.isBlank() || it.title.contains(search.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabChip("已上线 $onlineCount", tab == 0) { tab = 0 }
            Spacer(Modifier.width(8.dp))
            TabChip("待上线 $upcomingCount", tab == 1) { tab = 1 }
            Spacer(Modifier.weight(1f))
            ListSearchBox(search, { search = it }, "搜索预约的剧")
        }
        when {
            loading -> CenterLoading()
            shown.isEmpty() -> EmptyState(
                title = if (tab == 0) "还没有已上线的预约" else "还没有待上线的预约",
                hint = "在详情页点「预约」，开播时会提醒你",
            )
            // 行卡形态（旧版 38 图：横向行卡 + 行尾播放/详情，非海报墙）
            else -> androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(shown, key = { it.seriesId }) { card ->
                    ReservationRow(card, onOpen = { onOpen(card.seriesId) }, onPlay = { onPlay(card.seriesId) })
                }
            }
        }
    }
}

/** 预约行卡：封面 | 标题/简介/上线信息 | 播放+详情（已上线）/ 预约态（待上线）。 */
@Composable
internal fun ReservationRow(card: SeriesCard, onOpen: () -> Unit, onPlay: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (hovered) Color(0x33FFFFFF) else Palette.line, RoundedCornerShape(12.dp))
            .background(Palette.panel)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onOpen() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            card.cover,
            Modifier.width(80.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)),
            fallback = card.title,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(card.title, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // 评分：星形 + 黄字（与详情页/排行榜同语言）
                card.score.takeIf { it.isNotBlank() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Palette.star, modifier = Modifier.size(12.dp))
                        Text(it, color = Palette.star, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                // 上线状态徽标（旧版 success/secondary badge）
                val online = !card.upcoming
                Text(
                    if (online) "已上线" else "待上线",
                    color = if (online) Palette.text else Palette.dim,
                    fontSize = 10.sp,
                    modifier = Modifier.border(1.dp, Palette.line, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            val meta = listOf(
                card.subtitle?.takeIf { it.isNotBlank() },
                card.tags.take(3).joinToString(" · ").takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            card.desc?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Palette.dim.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val rec = card.recText?.takeIf { it.isNotBlank() }
            val secondary = card.secondary?.filter { it.isNotBlank() && it != rec }.orEmpty()
            if (rec != null || secondary.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    rec?.let { HeatBadge(it) }
                    if (secondary.isNotEmpty()) {
                        Text(secondary.joinToString("  "), color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!card.upcoming) {
                PrimaryButton("播放", Icons.Filled.PlayArrow, onPlay)
                Text(
                    "详情",
                    color = if (hovered) Palette.text else Palette.dim,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onOpen() }.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            } else {
                Text(card.statusText ?: "待上线", color = Palette.faint, fontSize = 12.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- 我的 / 设置（先接静态信息，登录下一步）

@Composable
internal fun MinePage(onOpen: (String) -> Unit, onPlay: (String) -> Unit, onLogin: () -> Unit) {
    // 用户定位：我的页 = 账号页。收藏/点赞/管理/搜索全部移除，
    // 只留「账号状态 + 手机号登录 + 匹配码同步」三件事。
    var pairCode by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        // 账户行（旧版 mine 顶部：头像 + 昵称，右侧登录/退出，不用整块卡片）
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(50))
                    .background(Palette.panelHi)
                    .border(1.dp, if (PcStore.loggedIn) Palette.accent else Palette.line, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = if (PcStore.loggedIn) Palette.accent else Palette.faint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (PcStore.loggedIn) PcStore.accountName.ifBlank { "用户" } else "未登录",
                    color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (PcStore.loggedIn) "收藏 / 追更 / 预约 / 历史都在跟着账号走"
                    else "手机号登录，或用匹配码把电视/手机的登录态接过来",
                    color = Palette.dim, fontSize = 12.sp,
                )
            }
            if (!PcStore.loggedIn) {
                PrimaryButton("手机号登录", null, onLogin)
                Spacer(Modifier.width(8.dp))
            }
            if (PcStore.loggedIn) {
                Spacer(Modifier.width(8.dp))
                OutlineButton("退出") {
                    PcStore.accountCookies = ""
                    PcStore.accountName = ""
                    status = "已退出登录"
                }
            }
        }

        // 匹配码：把电视/手机上已经登录的账号接过来（本页核心功能，常驻展示）
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.panel).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("用匹配码同步账号", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("在电视/手机的「我的」里生成 6 位匹配码，填在这里", color = Palette.dim, fontSize = 12.sp)
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Palette.accent, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.width(140.dp).height(34.dp).clip(RoundedCornerShape(8.dp))
                    .background(Palette.panelHi)
                    .border(1.dp, Palette.line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (pairCode.isEmpty()) {
                    Text("6 位匹配码", color = Palette.faint, fontSize = 12.sp)
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = pairCode,
                    onValueChange = { pairCode = it.filter { c -> c.isLetterOrDigit() }.take(12) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.accent),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            PrimaryButton("同步", null) {
                val code = pairCode.trim()
                if (code.length < 4) {
                    status = "匹配码至少 4 位"
                } else {
                    status = "同步中…"
                    scope.launch(Dispatchers.IO) {
                        val result = runCatching {
                            val name = Api.pairFetch(code)
                            Api.syncAccount(force = true)
                            name
                        }.getOrElse { it.message ?: "同步失败" }
                        withContext(Dispatchers.Main) { status = result.ifBlank { "已同步登录态" } }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "收藏 / 点赞 / 观看记录分别在对应板块查看；本页只负责账号与设备同步。",
            color = Palette.faint, fontSize = 12.sp, lineHeight = 18.sp,
        )
    }
}
