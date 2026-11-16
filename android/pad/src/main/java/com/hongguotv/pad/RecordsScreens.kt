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
fun RecordsScreen(
    initialTab: Int,
    onOpen: (String) -> Unit,
    onPlay: (seriesId: String, index: Int, startMs: Long) -> Unit,
) {
    // 从详情/播放返回时保持原来的页签：以前回收藏列表就跳回「历史」
    var tab by remember { mutableStateOf(UiMemo.recordsTab.takeIf { it >= 0 } ?: initialTab) }  // 0 历史 1 收藏 2 点赞
    // 筛选也存 UiMemo：以前返回就跳回「全部」，用户刚筛过的列表白筛了
    var filter by remember { mutableStateOf(UiMemo.recordsFilter) }        // 0 全部 1 未看完 2 已看完

    Column(Modifier.fillMaxSize()) {
        // 标题 + 三 tab（原版标题固定「历史」）
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 与 PC 一致不显示页面大标题，只留三 tab
            listOf("历史", "收藏", "点赞").forEachIndexed { i, label ->
                TvChip(label, selected = tab == i) { tab = i; UiMemo.recordsTab = i }
            }
        }
        when (tab) {
            0 -> HistoryTab(filter, setFilter = { filter = it; UiMemo.recordsFilter = it }, onPlay = onPlay)
            1 -> ShelfTab(Store.KEY_FAVORITE, onOpen)
            else -> ShelfTab(Store.KEY_LIKE, onOpen)
        }
    }
}

@Composable
internal fun HistoryTab(filter: Int, setFilter: (Int) -> Unit, onPlay: (String, Int, Long) -> Unit) {
    // 先同步读本地历史：列表立刻进组合，滚动位置才能恢复；
    // 云端同步随后原地 diff，不重建列表（原来 loading 期间列表不在组合，滚动会归零）。
    val rows = remember {
        mutableStateListOf<JSONObject>().apply { runCatching { addAll(Store.history()) } }
    }
    var loading by remember { mutableStateOf(rows.isEmpty()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (rows.isEmpty()) {
            loading = true
            rows.addAll(Store.history())
        }
        loading = false
        // 云端合并：historySync 内部是「拉云端 → mergeHistory（最近一次真实播放者胜）→ 回写」
        // 注意：这里两处都不能 rows.clear() + addAll()
        // —— 本地历史是立刻显示的，用户这时候已经在列表里按方向键了。
        // 云端同步要一两秒，回来一整表重建的话，item 节点全销毁，焦点当场丢，
        // 表现就是「看历史看着看着，焦点自己跳回第一条」。改成原地对齐更新。
        if (Store.loggedIn) {
            val ok = withContext(Dispatchers.IO) { runCatching { HistoryApi.sync() }.getOrDefault(false) }
            if (ok) {
                rows.diffUpdate(Store.history()) { it.optString("seriesId") }
            }
        }
        // 老数据里 cover 是空的（早期写历史时传了空串），进来顺手补一次
        if (rows.any { it.optString("cover").isEmpty() }) {
            val fixed = withContext(Dispatchers.IO) { runCatching { HistoryApi.backfillCovers() }.getOrDefault(0) }
            if (fixed > 0) {
                rows.diffUpdate(Store.history()) { it.optString("seriesId") }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 筛选行：全部 / 未看完 / 已看完 + 搜索 + 清空 + 管理（多选删除）
        var managing by remember { mutableStateOf(false) }
        var picked by remember { mutableStateOf(setOf<String>()) }
        var search by remember { mutableStateOf(UiMemo.recordsSearch) }
        var confirmClear by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("全部", "未看完", "已看完").forEachIndexed { i, label ->
                TvChip(label, selected = filter == i) { setFilter(i) }
            }
            Spacer(Modifier.weight(1f))
            // 搜索看过的剧（与 PC 历史页同一项；收藏/点赞 tab 不显示）
            BasicTextField(
                value = search,
                onValueChange = { search = it; UiMemo.recordsSearch = it },
                textStyle = TextStyle(color = TextMain, fontSize = 14.sp),
                cursorBrush = SolidColor(Red),
                singleLine = true,
                modifier = Modifier
                    .width(220.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(PanelHi)
                    .padding(horizontal = 14.dp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (search.isEmpty()) Text("搜索看过的剧", color = TextDim, fontSize = 13.sp)
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            if (!managing && !confirmClear) {
                // 一键清空保留，但必须二次确认（与 PC 同语义）
                Text(
                    "清空",
                    color = TextDim,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(17.dp))
                        .clickable { confirmClear = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (confirmClear) {
                Text(
                    "确认清空",
                    color = Red,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(17.dp))
                        .background(Red.copy(alpha = 0.12f))
                        .clickable {
                            confirmClear = false
                            val ids = rows.map { it.optString("seriesId") }
                            rows.clear()
                            Store.clearHistory()
                            if (Store.loggedIn) {
                                scope.launch(Dispatchers.IO) {
                                    runCatching { Api.deleteOfficialHistory(ids) }
                                    runCatching { HistoryApi.delete(ids) }
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "取消",
                    color = TextDim,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(17.dp))
                        .clickable { confirmClear = false }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (!managing && !confirmClear) {
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
            } else {
                TvChip("全选", selected = picked.size == rows.size) {
                    picked = if (picked.size == rows.size) emptySet() else rows.map { it.optString("seriesId") }.toSet()
                }
                TvChip("删除 ${picked.size}", selected = picked.isNotEmpty()) {
                    if (picked.isEmpty()) return@TvChip
                    picked.forEach { Store.removeHistory(it) }
                    rows.removeAll { it.optString("seriesId") in picked }
                    // 云端也要删，而且是**两份都删**：
                    // 自建桶（/api/history）+ 官方账号（read_history/update 的 is_delete）。
                    // 只删前者的话，下次同步官方又返回，用户眼里就是"删了又回来"。
                    if (Store.loggedIn) {
                        val ids = picked.toList()
                        scope.launch(Dispatchers.IO) {
                            runCatching { Api.deleteOfficialHistory(ids) }
                            runCatching { HistoryApi.delete(ids) }
                        }
                    }
                    picked = emptySet()
                    managing = false
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
        val visible = rows.filter { r ->
            val ep = r.optInt("episode", 1)
            val total = r.optInt("total", 0)
            val matchSearch = search.isBlank() ||
                r.optString("title").contains(search.trim(), ignoreCase = true)
            matchSearch && when (filter) {
                1 -> total <= 0 || ep < total
                2 -> total > 0 && ep >= total
                else -> true
            }
        }
        when {
            loading -> StatusBox(loading = true, error = null)
            visible.isEmpty() -> StatusBox(loading = false, error = null, empty = true, emptyText = if (search.isNotBlank()) "没有匹配的记录" else "看过的剧会出现在这里")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                state = UiMemo.historyList,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                listItems(visible, key = { it.optString("seriesId") }) { row ->
                    val card = cardFromHistory(row)
                    if (card != null) {
                        val sel = card.seriesId in picked
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (sel) Modifier.border(2.dp, Red, RoundedCornerShape(12.dp))
                                    else Modifier,
                                ),
                        ) {
                            HistoryRow(card, row) {
                                if (managing) {
                                    picked = if (sel) picked - card.seriesId else picked + card.seriesId
                                } else {
                                    // 带上云端的集内秒数：在别的端（Windows/官方 App）
                                    // 看到一半，回电视点历史能接着播，而不是从 0 秒重来
                                    onPlay(
                                        card.seriesId,
                                        row.optInt("episode", 1),
                                        row.optLong("positionMs", 0L),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HistoryRow(card: SeriesCard, row: JSONObject, onPlay: () -> Unit) {
    val isTv = Store.appContext.packageName.endsWith("nativeapp")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .clickable { onPlay() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(if (isTv) 120.dp else 88.dp)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp))
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
        Column(Modifier.weight(1f)) {
            Text(
                card.title,
                color = TextMain,
                fontSize = if (isTv) 21.sp else 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            val total = row.optInt("total", 0)
            // PC 同款：进度是**红色淡底徽标**（bg-primary/10 + text-primary），
            // 不是普通灰字——历史页一眼扫过去，进度应该是最跳的信息。
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Red.copy(alpha = 0.10f))
                    .padding(horizontal = if (isTv) 10.dp else 6.dp, vertical = if (isTv) 4.dp else 2.dp),
            ) {
                Text(
                    if (total > 0) "看到第 ${row.optInt("episode", 1)} 集 / 共 $total 集"
                    else "看到第 ${row.optInt("episode", 1)} 集",
                    color = Red,
                    fontSize = if (isTv) 15.sp else 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        OutlinePill("继续播放") { onPlay() }
    }
}

internal fun cardFromHistory(row: JSONObject): SeriesCard? {
    val id = row.optString("seriesId")
    val title = row.optString("title")
    if (id.isEmpty() || title.isEmpty()) return null
    return SeriesCard(
        seriesId = id,
        title = title,
        cover = row.optString("cover"),
        episodeCount = row.optInt("total"),
    )
}

@Composable
internal fun ShelfTab(key: String, onOpen: (String) -> Unit) {
    // 收藏/点赞按 key 缓存：从详情返回不再清空重拉，滚动位置才能保住
    val cards = if (key == Store.KEY_FAVORITE) UiMemo.favoriteCards else UiMemo.likeCards
    var loading by remember { mutableStateOf(cards.isEmpty()) }
    var syncing by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(key, reload) {
        val at = if (key == Store.KEY_FAVORITE) UiMemo.favoriteAt else UiMemo.likeAt
        if (reload == 0 && cards.isNotEmpty() &&
            System.currentTimeMillis() - at < UiMemo.RETURN_TTL_MS
        ) {
            // 在详情页取消收藏/点赞后返回：本地集合已经变了，列表同步剔除
            val ids = Store.ids(key).toSet()
            cards.retainAll { it.seriesId in ids }
            loading = false
            return@LaunchedEffect
        }
        if (reload > 0) syncing = true
        loading = true
        cards.clear()
        Store.ids(key).forEach { id -> Store.card(key, id)?.let { cards.add(it) } }
        if (key == Store.KEY_FAVORITE && Store.loggedIn) {
            // 收藏以云端为准：别的设备上取消了收藏，这里不该还留着。
            // 拉取失败（getOrNull()==null）时保留本地，避免网络抖动把列表清空。
            val cloud = withContext(Dispatchers.IO) { runCatching { Api.cloudShelf() }.getOrNull() }
            if (cloud != null) {
                cards.clear()
                cards.addAll(cloud)
                Store.replaceCards(Store.KEY_FAVORITE, cloud)
            }
        }
        loading = false
        syncing = false
        if (cards.isNotEmpty()) {
            if (key == Store.KEY_FAVORITE) UiMemo.favoriteAt = System.currentTimeMillis()
            else UiMemo.likeAt = System.currentTimeMillis()
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (key == Store.KEY_FAVORITE) {
            // 原版：右上「刷新云端（N）」
            Row(
                Modifier.fillMaxWidth().padding(end = 24.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinePill(
                    if (syncing) "刷新中…" else "刷新云端（${cards.size}）",
                    enabled = !syncing && Store.loggedIn,
                ) { reload++ }
            }
        }
        when {
            loading -> StatusBox(loading = true, error = null)
            cards.isEmpty() -> StatusBox(
                loading = false, error = null, empty = true,
                emptyText = if (key == Store.KEY_LIKE) "在播放页点❤，点过赞的剧会出现在这里"
                else "在详情页点「收藏」就会出现在这里",
            )
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val gap = 12.dp
                val rowH = ((maxHeight - 4.dp - 8.dp - gap) / 2).coerceAtLeast(140.dp)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    state = UiMemo.shelfGrid,
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    gridItems(cards, key = { it.seriesId + it.title }) { card ->
                        SeriesCardView(card, cardHeight = rowH, onClick = { onOpen(card.seriesId) })
                    }
                }
            }
        }
    }
}

// ---------------- 追更 / 预约（两个独立功能） ----------------

/**
 * 追更页 / 预约页（[initialMode] 0=追更，1=预约）。
 *
 * 拆成两个导航项，理由和 Windows 版一致：
 * - 追更 = 追的剧 + **更新提醒**（更新到第几集、我看到第几集）
 * - 预约 = **待上线 / 已上线** 两个分组，上线后自动从「待上线」挪到「已上线」
 *
 * 数据每次都从云端拉（followList / reserveList）：预约、追更都是账号级操作，
 * 只有以云端为准，多端才能同步。
 */
