package com.hongguotv.pad

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

@Composable
fun FollowReserveScreen(
    initialMode: Int,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit = {},
    onLogin: () -> Unit = {},
) {
    val mode = initialMode
    // 追更/预约的列表、tab、搜索词都存 UiMemo：进详情返回不再跳回第一个 tab / 清空搜索
    val cards = if (mode == 0) UiMemo.followCards else UiMemo.reserveCards
    var loading by remember { mutableStateOf(cards.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    // 失败后点「重新加载」用：自增一次就重新拉一遍
    var reload by remember { mutableStateOf(0) }
    // 0 = 第一个 tab（追更的剧 / 已上线），1 = 第二个（更新提醒 / 待上线）
    var tab by remember { mutableStateOf(if (mode == 0) UiMemo.followTab else UiMemo.reserveTab) }
    var query by remember { mutableStateOf(if (mode == 0) UiMemo.followQuery else UiMemo.reserveQuery) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(mode, reload) {
        val at = if (mode == 0) UiMemo.followAt else UiMemo.reserveAt
        if (Store.loggedIn && reload == 0 && cards.isNotEmpty() &&
            System.currentTimeMillis() - at < UiMemo.RETURN_TTL_MS
        ) {
            loading = false
            return@LaunchedEffect
        }
        // 一次性拿到完整列表（含封面/热度）再显示、直接可操作；不在 UI 层留半成品做 SWR
        loading = true
        error = null
        cards.clear()
        if (!Store.loggedIn) {
            error = if (mode == 0) "追更跟着账号走，请先到「我的」用手机号登录"
            else "预约跟着账号走，请先到「我的」用手机号登录"
            loading = false
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { if (mode == 0) Api.followList() else Api.reserveListCached() }
        }
        result.onSuccess { cards.addAll(it) }
        // 失败不能当成「空」——原来那样写，网络抖一下界面就变成
        // 「你还没追更任何剧」，用户会以为数据丢了
        result.onFailure {
            error = if (mode == 0) "追更列表没加载出来" else "预约列表没加载出来"
        }
        loading = false
        if (cards.isNotEmpty()) {
            if (mode == 0) UiMemo.followAt = System.currentTimeMillis()
            else UiMemo.reserveAt = System.currentTimeMillis()
        }
    }

    // 追更：未看集数 > 0 的算「有更新」；预约：按 is_online 分两栏
    fun unseen(card: SeriesCard): Int {
        val total = card.episodeCount
        if (total <= 0) return 0
        return (total - Store.progress(card.seriesId)).coerceAtLeast(0)
    }

    val updates = cards.filter { unseen(it) > 0 }
    val online = cards.filter { !it.upcoming }
    val upcoming = cards.filter { it.upcoming }
    val pool = when {
        mode == 0 && tab == 0 -> cards
        mode == 0 -> updates
        tab == 0 -> online
        else -> upcoming
    }
    val shown = if (mode == 1 && query.isNotBlank()) {
        pool.filter { it.title.contains(query.trim(), ignoreCase = true) }
    } else {
        pool
    }

    Column(Modifier.fillMaxSize()) {
        var managing by remember { mutableStateOf(false) }
        var picked by remember { mutableStateOf(setOf<String>()) }
        var busy by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 与 PC 一致不显示页面大标题（导航位置由左侧栏高亮表达）
            Spacer(Modifier.weight(1f))
            if (mode == 1) {
                // 预约页带搜索（对齐 Windows：搜索预约的剧）
                BasicTextField(
                    value = query,
                    onValueChange = { query = it; if (mode == 0) UiMemo.followQuery = it else UiMemo.reserveQuery = it },
                    textStyle = TextStyle(color = TextMain, fontSize = 14.sp),
                    cursorBrush = SolidColor(Red),
                    singleLine = true,
                    modifier = Modifier
                        .width(260.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(PanelHi)
                        .padding(horizontal = 14.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("搜索预约的剧", color = TextDim, fontSize = 13.sp)
                            inner()
                        }
                    },
                )
            }
        }

        // tab 条：追更（追更的剧 / 更新提醒）；预约（已上线 N / 待上线 N）
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tabs = if (mode == 0) {
                listOf("追更的剧 ${cards.size}", "更新提醒 ${updates.size}")
            } else {
                listOf("已上线 ${online.size}", "待上线 ${upcoming.size}")
            }
            tabs.forEachIndexed { i, label ->
                val sel = tab == i
                Box(
                    Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (sel) Red else PanelHi)
                        .clickable { tab = i; if (mode == 0) UiMemo.followTab = i else UiMemo.reserveTab = i }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (sel) Color.White else TextDim,
                        fontSize = 13.sp,
                        fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (!managing) {
                if (cards.isNotEmpty()) {
                    Text(
                        "管理",
                        color = TextDim,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(17.dp))
                            .background(PanelHi)
                            .clickable { managing = true; picked = emptySet() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                TvChip("全选", selected = picked.size == shown.size && shown.isNotEmpty()) {
                    picked = if (picked.size == shown.size) emptySet() else shown.map { it.seriesId }.toSet()
                }
                TvChip(
                    if (busy) "处理中…" else if (mode == 0) "取消追更 ${picked.size}" else "取消预约 ${picked.size}",
                    selected = picked.isNotEmpty(),
                ) {
                    if (picked.isEmpty() || busy) return@TvChip
                    val ids = picked.toList()
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            ids.forEach { id ->
                                runCatching {
                                    if (mode == 0) ShelfRepository.toggle(ShelfKind.Follow, id, false)
                                    else ShelfRepository.toggle(ShelfKind.Reserve, id, false)
                                }
                            }
                        }
                        cards.removeAll { it.seriesId in picked }
                        picked = emptySet()
                        busy = false
                        managing = false
                    }
                }
                Text(
                    "取消",
                    color = TextDim,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(17.dp))
                        .clickable { managing = false; picked = emptySet() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        when {
            loading -> StatusBox(loading = true, error = null)
            // 未登录单独一屏：以前只显示「请先去登录」+「重新加载」，点多少次都还是同一句，
            // 也不给入口 —— 用户以为功能坏了。
            !Store.loggedIn -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (mode == 0) "登录后才能同步追更" else "登录后才能同步预约",
                    color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (Store.appContext.packageName.endsWith("nativeapp"))
                        "在「我的」用手机扫码配对，或直接在这里登录"
                    else "在「我的」用手机号登录，或直接在这里登录",
                    color = TextDim, fontSize = 13.sp,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinePill("去登录") { onLogin() }
                }
            }
            shown.isEmpty() && error != null -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error!!, color = TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(PanelHi)
                        .clickable { reload++ }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text("重新加载", color = TextMain, fontSize = 13.sp)
                }
            }
            shown.isEmpty() -> StatusBox(
                loading = false,
                error = if (cards.isEmpty()) error else null,
                empty = true,
                emptyText = when {
                    mode == 0 && tab == 1 -> "追的剧有新集数时会出现在这里"
                    mode == 0 -> "在剧集详情页点「追更」，这部剧更新时会出现在这里"
                    tab == 1 -> "在待上线的剧详情页点「预约」，上线前后都会出现在这里"
                    else -> "预约的剧上线后会出现在这里"
                },
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                state = UiMemo.followList,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                listItems(shown, key = { it.seriesId + it.title }) { card ->
                    // 管理态：整行变成可选的条目（不显示播放/详情，避免误点）
                    if (managing) {
                        val sel = card.seriesId in picked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (sel) Color(0x33FF3B47) else Panel)
                                .clickable {
                                    picked = if (sel) picked - card.seriesId else picked + card.seriesId
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 封面必须留着：只看标题很难判断是哪部剧（同一部剧常有多个季/合集）
                            Box(
                                Modifier
                                    .width(48.dp)
                                    .aspectRatio(0.72f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CoverPlaceholder),
                            ) {
                                AsyncImage(
                                    model = card.cover,
                                    contentDescription = card.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(
                                card.title,
                                color = TextMain,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (sel) "已选" else "选择",
                                color = if (sel) Red else TextDim,
                                fontSize = 12.sp,
                            )
                        }
                        return@listItems
                    }
                    val total = card.episodeCount
                    val seen = Store.progress(card.seriesId)
                    val left = if (unseen(card) > 0) unseen(card) else 0
                    // 第一行：共 N 集 · 连载中/已完结；第二行：看到 X/Y 集 或 N 集未看
                    val line1 = listOfNotNull(
                        total.takeIf { it > 0 }?.let { "共 $it 集" },
                        if (mode == 0) (if (card.upcoming) "连载中" else "已完结") else null,
                        // playText 本身已经带单位（热度/播放），这里不再拼后缀
                    card.playText.takeIf { mode == 1 && it.isNotBlank() },
                    ).joinToString(" · ").ifBlank {
                        // 待上线的剧没有集数也没有播放量，至少给一句状态，别让行里空着
                        if (mode == 1 && card.upcoming) "待上线 · 可预约" else null
                    }
                    val line2 = when {
                        mode == 1 -> card.tags.take(2).joinToString(" · ").ifBlank { null }
                        total <= 0 -> null
                        seen <= 0 -> "还没开始看"
                        left > 0 -> "看到 $seen / $total 集 · $left 集未看"
                        else -> "看到 $seen / $total 集 · 已追平"
                    }
                    LibraryRow(
                        card = card,
                        line1 = line1,
                        line2 = line2,
                        // 已上线才给播放；待上线只能进详情预约
                        playable = mode == 0 || !card.upcoming,
                        onPlay = { onPlay(card.seriesId) },
                        onDetail = { onOpen(card.seriesId) },
                    )
                }
            }
        }
    }
}

/**
 * 追更 / 预约的列表行：封面 + 标题 + 两行说明 + 右侧「播放 / 详情」。
 * 版式对齐 Windows 版（右边两个独立按钮，TV 上各自可聚焦）。
 */
@Composable
internal fun LibraryRow(
    card: SeriesCard,
    line1: String?,
    line2: String?,
    playable: Boolean,
    onPlay: () -> Unit,
    onDetail: () -> Unit,
) {
    val isTv = Store.appContext.packageName.endsWith("nativeapp")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(if (isTv) 120.dp else 88.dp)
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(6.dp))
                .background(CoverPlaceholder),
        ) {
            AsyncImage(
                model = card.cover,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                card.title,
                color = TextMain,
                fontSize = if (isTv) 21.sp else 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!line1.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(line1, color = TextDim, fontSize = if (isTv) 15.sp else 12.sp, maxLines = 1)
            }
            if (!line2.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(line2, color = TextDim, fontSize = if (isTv) 15.sp else 12.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.width(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (playable) {
                Box(
                    Modifier
                        .height(if (isTv) 42.dp else 32.dp)
                        .clip(RoundedCornerShape(if (isTv) 21.dp else 16.dp))
                        .background(Red)
                        .clickable { onPlay() }
                        .padding(horizontal = if (isTv) 24.dp else 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("播放", color = Color.White, fontSize = if (isTv) 15.sp else 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            Box(
                Modifier
                    .height(if (isTv) 42.dp else 32.dp)
                    .clip(RoundedCornerShape(if (isTv) 21.dp else 16.dp))
                    .background(Color(0x14FFFFFF))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(if (isTv) 21.dp else 16.dp))
                    .clickable { onDetail() }
                    .padding(horizontal = if (isTv) 24.dp else 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("详情", color = Color(0xE6FFFFFF), fontSize = if (isTv) 15.sp else 13.sp)
            }
        }
    }
}

/** 预约页的分组标题。 */
@Composable
internal fun GroupTitle(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

// ---------------- 账号 / 设置 / 登录 / 演职员 ----------------
