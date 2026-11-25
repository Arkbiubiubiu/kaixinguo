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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

@Composable
fun RankScreen(
    onOpen: (SeriesCard) -> Unit,
    onPlay: (SeriesCard) -> Unit,
    onCreator: (String) -> Unit = {},
) {
    val tabs = remember { mutableStateListOf<RankTabsel>() }
    // 初始化自 RankMemo/UiMemo：进详情/播放再返回，保持上次选的大类、子榜和滚动位置
    var tabIdx by remember { mutableStateOf(RankMemo.tabIdx) }
    var subIdx by remember { mutableStateOf(RankMemo.subIdx) }
    val cards = UiMemo.rankCards
    // 演员榜返回的是「人」，结构和剧集榜完全不同，单独一条列表
    val persons = UiMemo.rankPersons
    var loading by remember { mutableStateOf(cards.isEmpty() && persons.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf(UiMemo.rankSession) }
    var offset by remember { mutableStateOf(UiMemo.rankOffset) }
    var hasMore by remember { mutableStateOf(UiMemo.rankHasMore) }
    var loadingMore by remember { mutableStateOf(false) }
    val rankList = UiMemo.rankList
    // 这一屏榜单数据真正的拉取时刻（0 = 还没拿到）。来自服务端返回的 age：
    // age 是「这份数据离从上游拉下来过去了多久」，所以真实时刻 = 现在 - age。
    var dataAt by remember { mutableStateOf(UiMemo.rankDataAt) }
    // 榜单元数据（子榜的「N月N日已更新」）上次拉取时刻：挂着过夜时切榜要重拉，
    // 否则描述行一直停在昨天（PC 端同一时刻显示今天的日期，两端对不上）
    var metaAt by remember { mutableStateOf(UiMemo.rankMetaAt) }
    // 服务器下发的筛选项与月榜。空串 = 不加这两个参数（等价于「总榜」/「当前榜」）
    var panelSel by remember { mutableStateOf(RankMemo.panelSel) }
    var monthSel by remember { mutableStateOf(RankMemo.monthSel) }
    var sheet by remember { mutableStateOf("") }
    val filterFocus = remember { FocusRequester() }
    val monthFocus = remember { FocusRequester() }

    // 选择变化就记下来，离开页面（进详情）时不会丢
    LaunchedEffect(tabIdx, subIdx, panelSel, monthSel) {
        RankMemo.tabIdx = tabIdx
        RankMemo.subIdx = subIdx
        RankMemo.panelSel = panelSel
        RankMemo.monthSel = monthSel
    }

    LaunchedEffect(Unit) {
        // 榜单菜单由服务器下发（官方上新榜、改筛选项，这里不用改代码就能跟上）；
        // 拿不到会退回内置表，页面至少还能用
        val t = withContext(Dispatchers.IO) { runCatching { Api.rankMeta() }.getOrDefault(emptyList()) }
        tabs.clear()
        tabs.addAll(t)
        metaAt = System.currentTimeMillis()
        // 记忆里的下标可能因为上游改榜而越界：钳回去，别让页面卡在空分类上
        if (tabs.isEmpty()) return@LaunchedEffect
        val ti = tabIdx.coerceIn(tabs.indices)
        if (ti != tabIdx) tabIdx = ti
        val subs = tabs[ti].subs
        val si = if (subs.isEmpty()) 0 else subIdx.coerceIn(subs.indices)
        if (si != subIdx) subIdx = si
        // 筛选/月榜的 id 在新一份菜单里不存在了（上游撤了某项）就清掉，
        // 否则会带着一个不认识的参数去请求，落回空榜
        val subNow = subs.getOrNull(si)
        if (panelSel.isNotEmpty() &&
            subNow?.panels.orEmpty().none { p -> p.items.any { it.id == panelSel } }
        ) {
            panelSel = ""
        }
        if (monthSel.isNotEmpty() && subNow?.months.orEmpty().none { it.id == monthSel }) {
            monthSel = ""
        }
    }

    val tab = tabs.getOrNull(tabIdx)
    val sub = tab?.subs?.getOrNull(subIdx)
    val reqGenre = tab?.id ?: ""
    val reqSub = sub?.id ?: ""

    // key 里必须带 tabs.size：分类表是异步填的，首帧还是空。
    // 但**空分类时直接不发请求** —— 否则会先用空分类画一版「全站榜」，
    // 等分类到位再整页清空重来，列表闪一下、焦点也被打散。
    var rankRetry by remember { mutableStateOf(0) }
    LaunchedEffect(tabIdx, subIdx, tabs.size, panelSel, monthSel, rankRetry) {
        if (tabs.isEmpty()) return@LaunchedEffect
        // 分类表刚加载/上游改榜：先等钳位 effect 把越界下标改回来
        val ti = tabIdx.coerceIn(tabs.indices)
        val subsNow = tabs[ti].subs
        val si = if (subsNow.isEmpty()) 0 else subIdx.coerceIn(subsNow.indices)
        if (ti != tabIdx || si != subIdx) {
            tabIdx = ti
            subIdx = si
            RankMemo.tabIdx = ti
            RankMemo.subIdx = si
            return@LaunchedEffect
        }
        val key = "$tabIdx|$subIdx|$panelSel|$monthSel"
        // 从详情/播放返回且榜单没变、数据还新鲜：保留列表、分页和滚动，不重拉
        if (rankRetry == 0 && UiMemo.rankKey == key &&
            (cards.isNotEmpty() || persons.isNotEmpty()) &&
            System.currentTimeMillis() - UiMemo.rankAt < UiMemo.RETURN_TTL_MS
        ) {
            loading = false
            return@LaunchedEffect
        }
        // 挂着过夜再切榜：元数据超过 1 分钟就重拉一次，描述行别停在昨天
        if (System.currentTimeMillis() - metaAt > 60_000) {
            val fresh = withContext(Dispatchers.IO) { runCatching { Api.rankMeta() }.getOrDefault(emptyList()) }
            if (fresh.isNotEmpty()) {
                tabs.clear()
                tabs.addAll(fresh)
                metaAt = System.currentTimeMillis()
                UiMemo.rankMetaAt = metaAt
            }
        }
        val changed = UiMemo.rankKey != key
        loading = true
        error = null
        hasMore = true
        session = ""
        offset = 0
        dataAt = 0L
        // 换筛选/换月榜都是「换一份榜单」，从第 1 名看起；返回页面时不滚。
        // 必须用 requestScrollToItem：此刻页面还在 loading、列表根本不在组合里，
        // 用挂起版 scrollToItem 会一直等列表挂载，而列表又要等这次加载结束 —— 互相卡死。
        if (changed) {
            cards.clear()
            persons.clear()
            runCatching { rankList.requestScrollToItem(0) }
        }
        // 演员大类走人员榜接口，别用解析剧集的那套（那样永远是空的，就是之前的「榜单加载失败」）
        if (tab?.id == "ranklist_celebrity") {
            val list = withContext(Dispatchers.IO) {
                runCatching { Api.rankActors(tab.id, sub?.id ?: "") }.getOrDefault(emptyList())
            }
            cards.clear()
            persons.clear()
            persons.addAll(list)
            if (persons.isEmpty()) error = "榜单加载失败"
            dataAt = System.currentTimeMillis()
            loading = false
            if (persons.isNotEmpty()) {
                UiMemo.rankKey = key
                UiMemo.rankAt = System.currentTimeMillis()
                UiMemo.rankDataAt = dataAt
            }
            return@LaunchedEffect
        }
        persons.clear()
        val page = withContext(Dispatchers.IO) {
            runCatching { Api.rankCached(0, "", reqGenre, reqSub, panelSel, monthSel) }.getOrNull()
        }
        cards.clear()
        if (page != null && page.cards.isNotEmpty()) {
            cards.addAll(page.cards)
            session = page.sessionId
            offset = page.nextOffset
            hasMore = page.hasMore
            dataAt = System.currentTimeMillis() - (page.dataAgeSec * 1000).toLong()
            UiMemo.rankKey = key
            UiMemo.rankAt = System.currentTimeMillis()
            UiMemo.rankSession = session
            UiMemo.rankOffset = offset
            UiMemo.rankHasMore = hasMore
            UiMemo.rankDataAt = dataAt
        } else {
            error = "榜单加载失败"
        }
        loading = false
    }

    LaunchedEffect(tabIdx, subIdx, panelSel, monthSel) {
        snapshotFlow { rankList.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .debounce(300)
            .distinctUntilChanged()
            .collect { last ->
                if (loading || loadingMore || !hasMore || cards.isEmpty()) return@collect
                if (last < cards.size - 3) return@collect
                loadingMore = true
                val page = withContext(Dispatchers.IO) {
                    runCatching { Api.rank(offset, session, reqGenre, reqSub, panelSel, monthSel) }.getOrNull()
                }
                if (page != null && page.cards.isNotEmpty()) {
                    val known = cards.map { it.seriesId }.toSet()
                    cards.addAll(page.cards.filter { it.seriesId !in known })
                    session = page.sessionId
                    offset = page.nextOffset
                    hasMore = page.hasMore
                    UiMemo.rankSession = session
                    UiMemo.rankOffset = offset
                    UiMemo.rankHasMore = hasMore
                } else {
                    hasMore = false
                    UiMemo.rankHasMore = false
                }
                loadingMore = false
            }
    }

    Column(Modifier.fillMaxSize()) {
        // 与 PC 完全一致的页头顺序：**大类行 → 子榜行（含筛选/月榜）→ 描述行**。
        // 不显示页面大标题（导航位置由左侧栏高亮表达，PC 同款）。
        Spacer(Modifier.height(14.dp))
        // 大类行
        Row(
            Modifier.padding(horizontal = 24.dp).fillMaxWidth().androidHorizontalScroll(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEachIndexed { i, t ->
                TvChip(t.label, selected = i == tabIdx) {
                    tabIdx = i; subIdx = 0; panelSel = ""; monthSel = ""; sheet = ""
                }
            }        }
        Spacer(Modifier.height(8.dp))
        // 子榜行（后面跟着「筛选」和「月榜」两个入口，有没有看服务器给没给）
        if (tab != null && tab.subs.isNotEmpty()) {
            val panels = sub?.panels.orEmpty()
            val months = sub?.months.orEmpty()
            // 按钮上直接显示当前选中的是哪个（没选就写「筛选」/「月榜」）
            val panelLabel = panels.asSequence()
                .flatMap { it.items.asSequence() }
                .firstOrNull { it.id == panelSel && it.name.isNotEmpty() }?.name ?: "筛选"
            val monthLabel = months.firstOrNull { it.id == monthSel }?.name ?: "月榜"
            Row(
                Modifier.padding(horizontal = 24.dp).fillMaxWidth().androidHorizontalScroll(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tab.subs.forEachIndexed { i, s ->
                    TvChip(s.label, selected = i == subIdx) {
                        subIdx = i; panelSel = ""; monthSel = ""; sheet = ""
                    }
                }
                if (panels.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    TvChip(
                        if (panelSel.isEmpty()) "筛选" else "筛选 · $panelLabel",
                        selected = panelSel.isNotEmpty(),
                        modifier = Modifier.focusRequester(filterFocus),
                    ) { sheet = "filter" }
                }
                if (months.isNotEmpty()) {
                    TvChip(
                        if (monthSel.isEmpty()) "月榜" else "月榜 · $monthLabel",
                        selected = monthSel.isNotEmpty(),
                        modifier = Modifier.focusRequester(monthFocus),
                    ) { sheet = "month" }
                }
            }
        }
        // 描述行（PC 同款位置：子榜行下方、列表上方）
        Text(
            sub?.desc?.takeIf { it.isNotBlank() } ?: run {
                // 只写「真实拉取时刻」：服务端对榜单有 5 分钟共享缓存，
                // 数据可能不是这一秒拉下来的，所以要显示它到底是什么时候拉的，
                // 而不是假装「刚更新」。
                val stamp = if (dataAt > 0L) "数据 ${hmOf(dataAt)} 拉取·" else ""
                stamp + "基于开心果观看/互动以及个人兴趣推荐"
            },
            color = TextDim,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
        )
        Spacer(Modifier.height(10.dp))
        when {
            // 筛选 / 月榜面板直接占掉列表区（不做浮层）：电视上用遥控器，
            // 一个「确定」就能选、一个「返回」就能收，比弹窗稳。
            sub != null && sheet == "filter" -> RankSheet(
                title = "筛选 · ${tab?.label ?: ""} · ${sub.label}",
                // 每组都补一个「全部」：以前选了就取消不掉，只能退出去重进
                rows = sub.panels.map { p -> p.name to (listOf(RankOption("", "全部")) + p.items) },
                selected = panelSel,
                onPick = { id -> panelSel = id; sheet = ""; runCatching { filterFocus.requestFocus() } },
                onReset = { panelSel = ""; sheet = ""; runCatching { filterFocus.requestFocus() } },
                onClose = { sheet = ""; runCatching { filterFocus.requestFocus() } },
            )
            sub != null && sheet == "month" -> RankSheet(
                title = "月榜 · ${tab?.label ?: ""} · ${sub.label}",
                rows = listOf("选择月份" to (listOf(RankOption("", "当前榜")) + sub.months)),
                selected = monthSel,
                onPick = { id -> monthSel = id; sheet = ""; runCatching { monthFocus.requestFocus() } },
                onReset = { monthSel = ""; sheet = ""; runCatching { monthFocus.requestFocus() } },
                onClose = { sheet = ""; runCatching { monthFocus.requestFocus() } },
            )
            loading -> StatusBox(loading = true, error = null)
            error != null && cards.isEmpty() && persons.isEmpty() -> StatusBox(
                loading = false, error = error,
                onRetry = { loading = true; error = null; rankRetry++ },
            )
            persons.isNotEmpty() -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = rankList,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listItemsIndexed(persons, key = { i: Int, p: RankPerson -> p.userId + i }) { i, person ->
                    ActorRankRow(person = person, index = i + 1, onClick = { onCreator(person.userId) })
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = rankList,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listItemsIndexed(cards, key = { i: Int, c: SeriesCard -> c.seriesId + i }) { i, card ->
                    RankCard(
                        card = card,
                        index = i + 1,
                        onOpen = { onOpen(card) },
                        onPlay = { onPlay(card) },
                    )
                }
                if (loadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                            LoadingSpinner(size = 26.dp, stroke = 3.dp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 榜单筛选 / 月榜面板：每行一组选项，选一个就生效并收起。
 *
 * 没有做成浮层：这页本身就是全屏列表，浮层还要额外处理遮罩、焦点回填、
 * 返回键穿透，在遥控器上全是坑。直接替换列表区，行为最直白——
 * 方向键选、确定生效、返回收起。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RankSheet(
    title: String,
    rows: List<Pair<String, List<RankOption>>>,
    selected: String,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    /** 有选中时在标题栏给一个「重置」；没有选中就不显示 */
    onReset: (() -> Unit)? = null,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 面板这一帧才上屏，立刻 requestFocus 会抛「not initialized」
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Back) {
                    onClose()
                    true
                } else {
                    false
                }
            },
    ) {
        // 面板整屏铺开，必须有一个看得见的「关闭」：以前只能按返回键
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = TextMain,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            if (selected.isNotEmpty() && onReset != null) {
                TvChip("重置", selected = false) { onReset() }
                Spacer(Modifier.width(8.dp))
            }
            TvChip("关闭", selected = false) { onClose() }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            rows.forEachIndexed { rowIdx, (rowName, items) ->
                Text(rowName, color = TextDim, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                // 换行排布，不再横向滚动：面板是整屏铺开的，一行放不下就折到下一行，
                // 用户一眼能看到这一组有哪些标签（原来要一直按右键才能翻到后面）。
                FlowRow(
                    // 右侧留出和顶部标签行一样的 24dp 边距：不然最后一片会贴死在
                    // 屏幕边缘，圆角被切掉一半，看着像没排完
                    Modifier.fillMaxWidth().padding(end = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items.forEachIndexed { i, opt ->
                        TvChip(
                            text = opt.name,
                            selected = opt.id == selected,
                            large = true,
                            modifier = if (rowIdx == 0 && i == 0) {
                                Modifier.focusRequester(first)
                            } else {
                                Modifier
                            },
                        ) { onPick(opt.id) }
                    }
                }
            }
        }
    }
}

/** 演员榜的一行：排名 + 圆头像 + 名字 + 人气 + 作品数。点进去是演员主页。 */
@Composable
internal fun ActorRankRow(person: RankPerson, index: Int, onClick: () -> Unit) {
    val isTv = Store.appContext.packageName.endsWith("nativeapp")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(if (isTv) 54.dp else 40.dp), contentAlignment = Alignment.Center) {
            Text(
                index.toString(),
                color = if (index <= 3) Red else TextDim,
                fontSize = if (isTv) 36.sp else 26.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        AsyncImage(
            model = person.avatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(if (isTv) 104.dp else 76.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(PanelHi),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(person.name, color = TextMain, fontSize = if (isTv) 24.sp else 18.sp, fontWeight = FontWeight.Bold)
                if (person.score.isNotBlank()) {
                    Spacer(Modifier.width(12.dp))
                    Text(person.score, color = Red, fontSize = if (isTv) 17.sp else 13.sp)
                }
            }
            if (person.details.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    person.details.joinToString(" · "),
                    color = TextDim, fontSize = if (isTv) 15.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (person.intro.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    person.intro,
                    color = TextDim, fontSize = if (isTv) 15.sp else 12.sp, maxLines = if (isTv) 3 else 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 原版同款榜单大条目：排名 + 封面 + 标题 + 标签 + 简介 + 热度行 + 播放/详情按钮。 */
@Composable
internal fun RankCard(card: SeriesCard, index: Int, onOpen: () -> Unit, onPlay: () -> Unit) {
    // 电视：远看要清楚 —— 封面更大、字更大、简介三行、热度贴着标签放同一行。
    // 平板保持原来的紧凑规格（同一组件，按设备分开）。
    val isTv = Store.appContext.packageName.endsWith("nativeapp")
    // 待上线（无剧集）条目：只给「预约」，不给播放/详情（否则点播放报未上线、点详情报网络错）
    val upcoming = card.upcoming || card.episodeCount <= 0
    val scope = rememberCoroutineScope()
    var reserved by remember(card.seriesId) { mutableStateOf(Store.contains(Store.KEY_RESERVE, card.seriesId)) }
    val rankW = if (isTv) 50.dp else 32.dp
    val rankFont = if (isTv) 33.sp else 24.sp
    val coverW = if (isTv) 132.dp else 88.dp
    val titleFont = if (isTv) 22.sp else 14.sp
    val scoreFont = if (isTv) 17.sp else 12.sp
    val metaFont = if (isTv) 15.sp else 12.sp
    val introLine = if (isTv) 22.sp else 18.sp
    val introMax = if (isTv) 3 else 2
    val btnFont = if (isTv) 16.sp else 13.sp
    val btnPadH = if (isTv) 22.dp else 18.dp
    val btnPadV = if (isTv) 10.dp else 8.dp
    val colGap = if (isTv) 7.dp else 4.dp

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            // TV：整行不做焦点目标（否则行内「播放/详情」按钮遥控器选不中，只能被整行吞成详情）；
            // Pad 触摸仍可整行点播=详情。
            .then(if (isTv) Modifier else Modifier.clickable { onOpen() })
            .padding(if (isTv) 16.dp else 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(rankW), contentAlignment = Alignment.TopCenter) {
            Text(
                index.toString(),
                // PC 同款：前三名金色（star），其余半透明灰
                color = if (index <= 3) Star else TextDim.copy(alpha = 0.5f),
                fontSize = rankFont,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(
            Modifier
                .width(coverW)
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
        Spacer(Modifier.width(if (isTv) 18.dp else 16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(colGap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (isTv) 10.dp else 6.dp)) {
                Text(
                    card.title, color = TextMain, fontSize = titleFont, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                // 评分：星形 + 金字（与 PC 行卡同语言）
                card.score.takeIf { it.isNotBlank() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("★", color = Star, fontSize = scoreFont)
                        Text(it, color = Star, fontSize = scoreFont, fontWeight = FontWeight.Medium)
                    }
                }
            }
            val meta = card.tags.take(3).joinToString(" · ").takeIf { it.isNotBlank() }
            val heatLabel = card.recText?.takeIf { it.isNotBlank() }
                ?: card.playText.takeIf { it.isNotBlank() }
            if (isTv) {
                // 标签 + 热度同一行（热度紧跟在标签右边）
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    meta?.let {
                        Text(
                            it, color = TextDim, fontSize = metaFont, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    heatLabel?.takeIf { it.isNotBlank() }?.let { heat ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FlameIcon()
                            Spacer(Modifier.width(4.dp))
                            Text(heat, color = Warm, fontSize = metaFont, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            } else {
                meta?.let {
                    Text(it, color = TextDim, fontSize = metaFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            card.desc?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it, color = TextDim.copy(alpha = 0.8f), fontSize = metaFont, lineHeight = introLine,
                    maxLines = introMax, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!isTv) {
                // 平板：热度单独一行（原来的紧凑布局）
                heatLabel?.takeIf { it.isNotBlank() }?.let { heat ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlameIcon()
                        Spacer(Modifier.width(4.dp))
                        Text(heat, color = Warm, fontSize = metaFont, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(if (isTv) 4.dp else 2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 8.dp)) {
                if (upcoming) {
                    // 待上线：只给「预约」（不提供播放/详情）；写后校验失败回滚
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (reserved) PanelHi else Red)
                            .clickable {
                                val next = !reserved
                                reserved = next
                                Store.toggleCard(Store.KEY_RESERVE, card)
                                scope.launch(Dispatchers.IO) {
                                    val ok = runCatching { ShelfRepository.toggle(ShelfKind.Reserve, card.seriesId, next) }
                                        .getOrDefault(false)
                                    if (!ok) withContext(Dispatchers.Main) {
                                        reserved = !next
                                        Store.toggleCard(Store.KEY_RESERVE, card)
                                    }
                                }
                            }
                            .padding(horizontal = btnPadH, vertical = btnPadV),
                    ) {
                        Text(
                            if (reserved) "已预约" else "预约",
                            color = if (reserved) TextDim else Color.White,
                            fontSize = btnFont, fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Red)
                            .clickable { onPlay() }
                            .padding(horizontal = btnPadH, vertical = btnPadV),
                    ) { Text("播放", color = Color.White, fontSize = btnFont, fontWeight = FontWeight.Medium) }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0x4DFFFFFF), RoundedCornerShape(8.dp))
                            // 打开详情前 stash 卡片：待上线详情接口可能 101000/空，DetailScreen 用 stash 兜底显示"待上线+预约"而不是网络错误
                            .clickable { Api.stashCard(card); onOpen() }
                            .padding(horizontal = btnPadH, vertical = btnPadV),
                    ) { Text("详情", color = Color.White, fontSize = btnFont) }
                }
            }
        }
    }
}

@Composable
internal fun Modifier.androidHorizontalScroll(): Modifier = this.horizontalScroll(
    androidx.compose.foundation.rememberScrollState(),
)

// ---------------- 新剧 ----------------
