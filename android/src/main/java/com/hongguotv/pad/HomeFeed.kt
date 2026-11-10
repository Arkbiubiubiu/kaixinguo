package com.hongguotv.pad

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 推荐页：三个 tab（推荐 / 漫剧 / 真人剧），四列海报墙 + 无限滚动。
 *
 * 刷新只有一个触发源：`LaunchedEffect(tab)`。
 * 原工程把 `cards.size` 也放进了 effect 的 key，结果只要列表长度一变
 * （加载更多、缓存回填）整个 effect 就重启，滚动位置被拉回顶部——
 * 这就是「翻着翻着自己刷新」那个 bug，这里不再犯。
 */
@Composable
fun HomeScreen(
    onOpen: (SeriesCard) -> Unit,
    onPlay: (SeriesCard) -> Unit,
) {
    var tab by remember { mutableStateOf(HomeTab.Recommend) }
    val cards = remember { mutableStateListOf<SeriesCard>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var cursor by remember { mutableStateOf<FeedPage?>(null) }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // hero：焦点跟随（PC 端同款信息的 TV 形态）——焦点在网格哪张卡上，
    // hero 就显示那部剧的详情档案（评分/热度/追剧/集数/标签/简介）。
    // 数据 Api.ensureDetail（DetailCache LRU 60 与详情页共享）；焦点快速
    // 移动时 LaunchedEffect 自动取消上一个请求，天然防抖；数据未到时用
    // 卡片快照（标题/封面）撑住版面，不白屏。
    var heroCard by remember { mutableStateOf<SeriesCard?>(null) }
    var heroDetail by remember { mutableStateOf<SeriesDetail?>(null) }
    LaunchedEffect(heroCard?.seriesId) {
        val id = heroCard?.seriesId
        heroDetail = if (id == null) null else withContext(Dispatchers.IO) {
            runCatching { Api.ensureDetail(id) }.getOrNull()
        }
    }

    LaunchedEffect(tab) {
        val key = "home_${tab.name}"
        val cached = withContext(Dispatchers.IO) { FeedCache.load(key) }
        cards.clear()
        cards.addAll(cached)
        loading = cached.isEmpty()
        error = null
        hasMore = true
        cursor = null
        if (cached.isNotEmpty() && grid.firstVisibleItemIndex > 0) grid.scrollToItem(0)

        // 首屏最多试 3 次：服务器忙/上游风控时退避重试。
        // 之前失败一次就把 cursor 留空，下面的翻页 collect 因 cursor==null
        // 直接返回，首页就永远只有首屏那几张卡。
        var attempt = 0
        while (attempt < 3) {
            val page = withContext(Dispatchers.IO) {
                runCatching { Api.firstPage(tab.tabType) }.getOrNull()
            }
            if (page != null && page.cards.isNotEmpty()) {
                // 关键：**有缓存时不动屏幕上的列表**。
                //
                // 原来这里无条件 cards.clear() + 换新内容，于是「开 App 用缓存先画出来
                // → 一两秒后网络回来整页换掉」：用户刚翻到第 2、3 排找片，
                // 列表突然变了，前面看的全没了。推荐内容是随机的，换完根本找不回原位置。
                //
                // 现在有缓存就只把新首屏写进磁盘，等下次进推荐页（切 tab / 重启）才生效；
                // 只有完全没有缓存（冷启动第一次）才直接用网络结果铺屏。
                if (cached.isEmpty()) {
                    cards.clear()
                    cards.addAll(page.cards)
                }
                // 游标照常更新：继续下滑翻页时用新的 offset，翻页那边会按 seriesId 去重
                cursor = page
                hasMore = page.hasMore
                withContext(Dispatchers.IO) { FeedCache.save(key, page.cards.toList()) }
                break
            }
            attempt++
            if (attempt < 3) delay(2500L * attempt)
        }
        if (cursor == null && cached.isEmpty()) error = "加载失败，返回后再进来重试"
        loading = false
        // hero 默认主角：切 tab 后无条件回到本 tab 第一张（焦点未动时也翻面）
        heroCard = cards.firstOrNull()
    }

    // 焦点移到末尾附近就续一页。debounce 防止一路划到底时连发多次。
    //
    // key 只留 tab：cards/cursor 是 remember 的 state，collect 里读到的就是最新值。
    // 以前 key 带 cards.size，每加载一页就重启一次收集器，重启瞬间 snapshotFlow
    // 又立刻发一次「已在底部」——于是一次下滑连拉好几页，看着就像整页刷新了一遍。
    LaunchedEffect(tab) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .debounce(260)
            .distinctUntilChanged()
            .collect { last ->
                if (loading || loadingMore || !hasMore || cards.isEmpty()) return@collect
                if (last < cards.size - 6) return@collect
                val c = cursor ?: return@collect
                loadingMore = true
                val next = withContext(Dispatchers.IO) {
                    runCatching {
                        Api.feedNext(
                            tabType = tab.tabType,
                            cellId = c.cellId,
                            planId = c.planId,
                            sessionId = c.sessionId,
                            offset = c.nextOffset,
                            seen = cards.map { it.seriesId },
                        )
                    }.getOrNull()
                }
                if (next != null && next.cards.isNotEmpty()) {
                    val known = cards.map { it.seriesId }.toSet()
                    val fresh = next.cards.filter { it.seriesId !in known }
                    cards.addAll(fresh)
                    cursor = next
                    hasMore = next.hasMore
                } else {
                    hasMore = false
                }
                loadingMore = false
            }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：行内大 tab（原版样式：选中白色加粗，未选中灰），无页面大标题
        val tabFocus = LocalFocusManager.current
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            HomeTab.entries.forEach { t ->
                val selected = t == tab
                Text(
                    t.label,
                    color = if (selected) TextMain else TextDim,
                    fontSize = if (selected) 22.sp else 18.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    // 按 ↓ 显式把焦点交给下面的卡片墙。
                    // 之前靠默认的几何就近查找，从 tab 行到第一排卡片这一步经常走不过去，
                    // 用户得先往右挪一格再按 ↓ 才出得来。
                    modifier = Modifier
                        .clickable { tab = t }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) {
                                tabFocus.moveFocus(FocusDirection.Down)
                                true
                            } else false
                        },
                )
            }
        }

        // hero：焦点跟随的详情展示卡（PC 端同款信息的 TV 形态）。
        // 纯展示、不聚焦——遥控器焦点模型不变（焦点仍落在网格卡片上，
        // 侧栏锁/返回链路原样），确认动作仍在卡片上（OK = 进详情）。
        if (cards.isNotEmpty()) {
            HomeHero(
                card = heroCard,
                detail = heroDetail,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            )
        }

        when {
            loading && cards.isEmpty() -> StatusBox(loading = true, error = null)
            error != null && cards.isEmpty() -> StatusBox(loading = false, error = error)
            cards.isEmpty() -> StatusBox(loading = false, error = null, empty = true, emptyText = "这个分类暂时没有内容")
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                // 一屏 6 列 × 2 排，卡片高度按「两排正好铺满」平分，
                // 第二排不会被屏幕边缘切掉（SeriesCardView 的 cardHeight 就是为这个加的）。
                val gap = 12.dp
                val padV = 4.dp
                val rowH = ((maxHeight - padV * 2 - gap) / 2).coerceAtLeast(160.dp)
                LazyVerticalGrid(
                    state = grid,
                    columns = GridCells.Fixed(6),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = padV, bottom = padV),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(cards, key = { it.seriesId + it.title }) { card ->
                        // 焦点缩放（TV「出彩」升级）：聚焦卡片 180ms 放大到 1.05，
                        // 与 Theme.kt 的滑行白框配合。onFocusChanged 不依赖
                        // interactionSource；graphicsLayer 是纯绘制层缩放，
                        // 不触发布局（LazyGrid 不抖）。
                        var cardFocused by remember { mutableStateOf(false) }
                        val focusScale by animateFloatAsState(
                            targetValue = if (cardFocused) 1.05f else 1f,
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            label = "cardFocusScale",
                        )
                        Box(
                            Modifier
                                .onFocusChanged {
                                    cardFocused = it.hasFocus
                                    // hero 焦点跟随：焦点落在哪张卡，上面的大屏就刷到那部剧
                                    if (it.hasFocus) heroCard = card
                                }
                                .graphicsLayer {
                                    scaleX = focusScale
                                    scaleY = focusScale
                                },
                        ) {
                            SeriesCardView(
                                card,
                                cardHeight = rowH,
                                showHeat = true,
                                onClick = { onOpen(card) },
                            )
                        }
                    }
                    if (loadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                Modifier.fillMaxWidth().height(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LoadingSpinner(size = 22.dp, stroke = 2.5.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text("正在加载更多…", color = TextDim, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 首页 hero：焦点跟随的详情展示卡（PC 端同款信息的 TV 形态）。
 *
 * 焦点在网格哪张卡上，这里就显示那部剧的详情档案——评分/红果热度/
 * 追剧人数/更新状态/题材标签/官方简介，全是海报给不了的信息。
 * **纯展示、不聚焦**：遥控器焦点模型不变（焦点仍落在网格卡片上，
 * 侧栏锁/返回链路原样），确认动作仍在卡片上（OK = 进详情）。
 * 颜色/字号/圆角全按 Theme.kt 既有规格（TextMain/TextDim/橙 #FF9500、
 * 20/15/13/11sp、圆角 12dp），不引入新视觉语言。
 * 数据未到时用卡片快照（标题/封面）撑住版面，不白屏。
 */
@Composable
private fun HomeHero(card: SeriesCard?, detail: SeriesDetail?, modifier: Modifier = Modifier) {
    val title = detail?.title?.takeIf { it.isNotEmpty() } ?: card?.title.orEmpty()
    val score = detail?.score.orEmpty()
    val hot = detail?.hotScore.orEmpty()
    val followed = detail?.followedCount.orEmpty()
    val epCount = detail?.episodeCount ?: card?.episodeCount ?: 0
    val updateTag = detail?.subTitles?.firstOrNull().orEmpty()
    val tags = detail?.tags.orEmpty()
    val cover = card?.cover.orEmpty()

    Row(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Panel),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (cover.isNotEmpty()) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(165.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.take(3).forEach { tag ->
                        Text(tag, color = TextDim, fontSize = 11.sp)
                    }
                }
            }
            Text(
                title,
                color = TextMain,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (score.isNotEmpty()) {
                    Text(
                        "${score}分",
                        color = Color(0xFFFF9500),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (hot.isNotEmpty()) {
                    Text("红果热度值$hot", color = Color(0xFFFF7A2F), fontSize = 13.sp)
                }
                if (followed.isNotEmpty()) {
                    Text("${followed}人追剧", color = TextDim, fontSize = 13.sp)
                }
                if (updateTag.isNotEmpty()) {
                    Text(updateTag, color = TextDim, fontSize = 13.sp)
                }
                if (epCount > 0) {
                    Text("全${epCount}集", color = TextDim, fontSize = 13.sp)
                }
            }
            if (detail?.intro?.isNotEmpty() == true) {
                Text(
                    detail.intro,
                    color = TextDim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
