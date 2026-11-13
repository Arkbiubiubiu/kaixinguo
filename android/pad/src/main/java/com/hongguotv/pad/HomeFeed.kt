package com.hongguotv.pad

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val grid = UiMemo.homeGrid
    val scope = rememberCoroutineScope()
    // hero：焦点跟随（PC 端同款信息的 TV 形态）——焦点在网格哪张卡上，
    // hero 就显示那部剧的详情档案（评分/热度/追剧/集数/标签/简介）。
    // 数据 Api.ensureDetail（DetailCache LRU 60 与详情页共享）；焦点快速
    // 移动时 LaunchedEffect 自动取消上一个请求，天然防抖；数据未到时用
    // 卡片快照（标题/封面）撑住版面，不白屏。
    var heroCard by remember { mutableStateOf<SeriesCard?>(null) }
    // 平板有左右箭头（和 PC 一样手动切 hero），TV 不加：
    // TV 的「切换」就是方向键在下面网格里移动焦点（hero 焦点跟随）。
    val isTv = Store.appContext.packageName.endsWith("nativeapp")
    fun stepHero(dir: Int) {
        if (cards.isEmpty()) return
        val cur = cards.indexOfFirst { it.seriesId == heroCard?.seriesId }.coerceAtLeast(0)
        heroCard = cards[(cur + dir + cards.size) % cards.size]
    }
    var heroDetail by remember { mutableStateOf<SeriesDetail?>(null) }
    // 评分（剧评接口 extra，与 PC 端同源）+ 相关推荐（plan 接口 works）
    var heroScore by remember { mutableStateOf("") }
    var heroScoreCount by remember { mutableStateOf(0) }
    var heroRelated by remember { mutableStateOf<List<SeriesCard>>(emptyList()) }
    /** 「换一批」计数：变化时强制重拉首屏（跳过缓存） */
    var refreshNonce by remember { mutableStateOf(0) }
    LaunchedEffect(heroCard?.seriesId) {
        val id = heroCard?.seriesId
        if (id == null) {
            heroDetail = null
            heroScore = ""
            heroScoreCount = 0
            heroRelated = emptyList()
            return@LaunchedEffect
        }
        // 串行、但每一步都在 IO 线程：detail 有 LRU 缓存通常秒回，
        // 评分/相关是新请求。LaunchedEffect 在 key 变化时自动取消，
        // 焦点快速移动不会堆积请求（并行版曾因 async 在主线程执行
        // 阻塞调用导致 detail 拿不到值，hero 只剩标题——已改回）。
        // 焦点防抖 + 三路并行：原来每移一张卡就串行发 3 个请求（快速划 10 张 = 20+ 请求，
        // 且阻塞 IO 不会被协程取消）。停留 350ms 才查，三路并发（各自在 IO 线程执行）。
        kotlinx.coroutines.delay(350)
        kotlinx.coroutines.coroutineScope {
            launch {
                heroDetail = withContext(Dispatchers.IO) {
                    runCatching { Api.ensureDetail(id) }.getOrNull()
                }
            }
            launch {
                val page = withContext(Dispatchers.IO) {
                    runCatching { Api.reviews(id, 1) }.getOrNull()
                }
                heroScore = page?.score.orEmpty()
                heroScoreCount = page?.scoreCount ?: 0
            }
            launch {
                heroRelated = withContext(Dispatchers.IO) {
                    runCatching { Api.relatedSeries(id) }.getOrNull()
                        ?.firstOrNull()?.items.orEmpty().take(3)
                }
            }
        }
    }

    LaunchedEffect(tab, refreshNonce) {
        if (refreshNonce > 0) Api.clearListCache()   // 换一批：作废列表缓存
        val key = "home_${tab.name}"
        val cached = withContext(Dispatchers.IO) { FeedCache.load(key) }
        cards.clear()
        cards.addAll(cached)
        // hero 也要跟着缓存立刻出来：原来只有网络首屏回来后才赋 heroCard，
        // 而卡片墙是先用缓存画的 —— 于是开 App 那几秒上面是 300dp 的一块空板
        // （用户看到的就是「加载中但什么都不动」）。缓存里的第一张先顶上，
        // 详情/评分随后到；网络首屏回来后再按新列表重设。
        if (cached.isNotEmpty()) heroCard = cached.firstOrNull()
        loading = cached.isEmpty()
        error = null
        hasMore = true
        cursor = null
        // 只有「切了 tab」才回到顶部；从详情返回时保持原来的滚动位置
        if (cached.isNotEmpty() && UiMemo.homeTab != tab.name) {
            runCatching { grid.scrollToItem(0) }
            UiMemo.homeTab = tab.name
        }

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
                // 换一批（refreshNonce>0）也强制用网络结果铺屏——用户明确要「换新的」
                if (cached.isEmpty() || refreshNonce > 0) {
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
        if (cursor == null && cached.isEmpty()) error = "首页没加载出来（网络问题）"
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
        // 顶栏：**与 PC 完全同款**的胶囊 tab（原为纯文字「选中白色加粗」）
        // + 右侧「换一批」——PC 在 tab 行右端同样有这个按钮，
        // 而 TV 原来没有（懒刷核心动作缺失）。
        val tabFocus = LocalFocusManager.current
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeTab.entries.forEach { t ->
                TvChip(
                    t.label,
                    selected = t == tab,
                    // 按 ↓ 显式把焦点交给下面的卡片墙（原来靠几何就近查找，
                    // 从 tab 行到第一排卡片经常走不过去）
                    modifier = Modifier.onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) {
                            tabFocus.moveFocus(FocusDirection.Down)
                            true
                        } else false
                    },
                ) { tab = t }
            }
            Spacer(Modifier.weight(1f))
            TvChip("换一批", selected = false) { refreshNonce++ }
        }

        // hero：焦点跟随的详情展示卡（PC 端同款信息的 TV 形态）。
        // 纯展示、不聚焦——遥控器焦点模型不变（焦点仍落在网格卡片上，
        // 侧栏锁/返回链路原样），确认动作仍在卡片上（OK = 进详情）。
        if (cards.isNotEmpty()) {
        val heroPos = cards.indexOfFirst { it.seriesId == heroCard?.seriesId }.coerceAtLeast(0)
        HomeHero(
            card = heroCard,
            detail = heroDetail,
            score = heroScore,
            scoreCount = heroScoreCount,
            related = heroRelated,
            position = heroPos,
            count = cards.size,
            showArrows = !isTv,
            onPrev = { stepHero(-1) },
            onNext = { stepHero(1) },
            onPlay = { c -> onPlay(c) },
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        )
        }

        when {
            loading && cards.isEmpty() -> StatusBox(loading = true, error = null)
            error != null && cards.isEmpty() -> StatusBox(loading = false, error = error, onRetry = { refreshNonce++ })
            cards.isEmpty() -> StatusBox(loading = false, error = null, empty = true, emptyText = "这个分类暂时没有内容")
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                // 卡片用**自然比例**（SeriesCardView 的 aspectRatio(0.755)），
                // 不再按「两排铺满一屏」强制 cardHeight —— PC 端卡片的
                // 封面是固定 3:4 比例，强制高度会把封面压扁/拉长（实测变形）。
                // 网格按可用空间自然排，多出来的行滚出去。
                val gap = 12.dp
                val padV = 4.dp
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
                                showHeat = true,
                                onClick = { onOpen(card) },
                            )
                        }
                    }
                    if (!hasMore && !loadingMore && cards.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                Modifier.fillMaxWidth().height(44.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("没有更多了", color = TextDim, fontSize = 12.sp)
                            }
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
 * 首页 hero：PC 端同款**全屏详情舞台**（1:1 移植）。
 *
 * 信息全部来自详情数据源（Api.ensureDetail，DetailCache 与详情页共享）——
 * 评分/红果热度/追剧人数/更新状态/题材标签/官方简介，全是海报给不了的。
 * 视觉同 PC：模糊封面铺底 + 卡面色遮罩渐变 + 左海报 + 右信息列 + 左下
 * 计数器（N / M）。
 *
 * 平板显示 PC 同款左右圆钮（手动切 hero）；TV 不显示——遥控器方向键在
 * 网格里左右移动焦点就是切换 hero（焦点跟随），焦点模型不变。
 * 整块纯展示、不聚焦：确认动作仍在卡片上（OK = 进详情）。
 */
@Composable
private fun HomeHero(
    card: SeriesCard?,
    detail: SeriesDetail?,
    score: String,
    scoreCount: Int,
    related: List<SeriesCard>,
    position: Int,
    count: Int,
    /** 平板显示左右圆钮（PC 同款）；TV 不显示，靠网格焦点切换 */
    showArrows: Boolean = false,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    /** 电视上没有「继续看」入口：每张卡都要先进详情，侧栏还要按 5 次才到历史 */
    onPlay: (SeriesCard) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val title = detail?.title?.takeIf { it.isNotEmpty() } ?: card?.title.orEmpty()
    val hot = detail?.hotScore.orEmpty()
    val followed = detail?.followedCount.orEmpty()
    val epCount = detail?.episodeCount ?: card?.episodeCount ?: 0
    val updateTag = detail?.subTitles?.firstOrNull().orEmpty()
    val tags = detail?.tags.orEmpty().take(3)
    val intro = detail?.intro.orEmpty()
    val cover = card?.cover.orEmpty()

    Box(
        modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Panel),
    ) {
        if (cover.isNotEmpty()) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .scale(1.25f)
                    .blur(32.dp),
                contentScale = ContentScale.Crop,
            )
        }
        // 遮罩：卡面色 63.5% 压住上半，向下融入页面底色（Animeko 配方）
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Panel.copy(alpha = 0.635f),
                        0.4f to Panel.copy(alpha = 0.635f),
                        1f to Bg,
                    ),
                ),
        )
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (cover.isNotEmpty()) {
                // 海报要等图片下载完才有内容，之前留一块纯色，看着像「页面卡住」。
                // 底下垫一个转动的小圈，图到了自然盖住（AsyncImage 未完成时是透明的）。
                Box(
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingSpinner(size = 26.dp, stroke = 2.5.dp)
                    AsyncImage(
                        model = cover,
                        contentDescription = title,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            if (cover.isEmpty()) {
                // 连封面地址都还没有（首屏还没回来）：占住同样大小的位置并转圈，
                // 不要留一块空板子让人以为卡死。
                Box(
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingSpinner(size = 26.dp, stroke = 2.5.dp)
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                if (tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { tag ->
                            Text(tag, color = TextDim, fontSize = 12.sp)
                        }
                    }
                }
                Text(
                    title,
                    color = TextMain,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 「继续看第 N 集」：电视上少按好几下（历史里点也行，但路径太深）
                card?.let { c ->
                    val resume = Store.progress(c.seriesId)
                    if (resume > 0) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(Red)
                                .clickable { onPlay(c) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "继续看第 $resume 集",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 评分 + 人数（PC 同款排版：大数字 + 小字人数，同属一组）
                    if (score.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text(
                                score,
                                color = Star,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (scoreCount > 0) {
                                Text(
                                    "${formatCount(scoreCount.toString())}人评分",
                                    color = TextDim,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                    if (hot.isNotEmpty()) {
                        // PC 同款：火焰图标 + 「红果热度值4786.2万」（一位小数）
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FlameIcon(14.dp)
                            Text(
                                "红果热度值${formatCountPrecise(hot)}",
                                color = Warm,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    if (followed.isNotEmpty()) {
                        Text(
                            "${formatCountPrecise(followed)}人追剧",
                            color = TextDim,
                            fontSize = 13.sp,
                        )
                    }
                    if (updateTag.isNotEmpty()) {
                        Text(updateTag, color = TextDim, fontSize = 13.sp)
                    } else if (epCount > 0) {
                        Text("全${epCount}集", color = TextDim, fontSize = 13.sp)
                    }
                }
                if (intro.isNotEmpty()) {
                    Text(
                        intro,
                        color = TextDim,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 相关推荐列（PC 同款：3 张迷你海报 + 剧名，右侧竖排）。
            // TV 上纯展示不聚焦——遥控器焦点模型不变（焦点仍在网格卡片上），
            // 想看相关作品在网格里走即可。
            if (related.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        "相关推荐 · ${related.size}",
                        color = TextDim,
                        fontSize = 11.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        related.forEach { r ->
                            Column(
                                Modifier.width(64.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                AsyncImage(
                                    model = r.cover,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(3f / 4f)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Text(
                                    r.title,
                                    color = TextDim,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        // 平板：PC 同款左右圆钮，手动切 hero（TV 不显示，焦点即切换）
        if (showArrows && count > 1) {
            Row(
                Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroArrowButton(-1, "上一部", onPrev)
                Spacer(Modifier.weight(1f))
                HeroArrowButton(1, "下一部", onNext)
            }
        }
        // 计数器（PC 同款左下）：N / M
        Text(
            "${position + 1} / $count",
            color = TextMain,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = 14.dp)
                .background(Color(0x99000000), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** hero 左右切换圆钮（44×44 半透明黑底，PC 同款；chevron 用 Canvas 画，不引扩展图标包）。 */
@Composable
private fun HeroArrowButton(dir: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0x99000000))
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                if (dir < 0) {
                    moveTo(w * 0.64f, h * 0.20f)
                    lineTo(w * 0.34f, h * 0.50f)
                    lineTo(w * 0.64f, h * 0.80f)
                } else {
                    moveTo(w * 0.36f, h * 0.20f)
                    lineTo(w * 0.66f, h * 0.50f)
                    lineTo(w * 0.36f, h * 0.80f)
                }
            }
            drawPath(
                path,
                color = Color.White,
                style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
