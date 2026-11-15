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

/** 原版同款红描边胶囊（继续播放 / 刷新云端用）。 */
@Composable
internal fun OutlinePill(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val isTv = Store.appContext.packageName.endsWith("nativeapp")
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, Color(0x80FF4D4F), RoundedCornerShape(50))
            .background(Color(0x14FF4D4F))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = if (isTv) 22.dp else 16.dp, vertical = if (isTv) 11.dp else 9.dp),
    ) {
        Text(text, color = Red, fontSize = if (isTv) 15.sp else 13.sp)
    }
}

// ---------------- 找剧 ----------------

/**
 * 找剧页（原版布局）：顶部搜索框 + 筛选行（流式胶囊）+ 清除筛选 + 结果网格。
 * 换体裁会重拉筛选面板（主题/设定/背景跟着体裁变），其余条件只换内容。
 */
/**
 * 找剧页的浏览状态：从详情返回后要接着上次的关键词和结果。
 *
 * 之前这些状态都放在 FindScreen 的 remember 里，一进详情页 FindScreen 被销毁 → 全丢，
 * 返回时回到初始页，用户还得把关键词重新打一遍。
 */
object FindMemo {
    var query: String = ""
    var searchQuery: String? = null
    var cards: List<SeriesCard> = emptyList()
    var searchId: String = ""
    var offset: Int = 0
    var hasMore: Boolean = false
    // 筛选条件也要记住：只记结果不记条件，从详情返回会看到「列表还是上次的、
    // 条件却清空了」的错位状态。
    var selected: List<Pair<String, String>> = emptyList()

    /** 是否已经套用过默认体裁（漫剧）；只在本次进程第一次进找剧时加一次 */
    var defaultGenreApplied: Boolean = false
}

/**
 * 排行榜选择的记忆：进详情/播放再返回时，保持「大类 / 子榜 / 筛选 / 月榜」。
 *
 * 页面是「只组合当前页」的，离开排行榜时整棵子树销毁，`remember` 里的选择
 * 会跟着清零——表现就是选了「漫剧 + 推荐榜 + 某筛选」，点进一部剧再返回，
 * 又变回「全部 + 推荐榜」。这里按 FindMemo 的做法存一份领域状态。
 *
 * 存的是**下标**（tabIdx/subIdx），因为服务端下发的分类表顺序稳定；
 * 万一哪天下发条数变少导致越界，加载完分类表后会钳回 0（见 RankScreen）。
 */
object RankMemo {
    var tabIdx: Int = 0
    var subIdx: Int = 0
    var panelSel: String = ""
    var monthSel: String = ""
}

@Composable
fun FindScreen(
    onOpen: (SeriesCard) -> Unit,
    /** 从侧栏按 → 进来时，焦点落在哪儿（绑到搜索框上） */
    entryFocus: FocusRequester? = null,
    /**
     * 搜索框的焦点状态回调。
     *
     * 为什么需要它：`requestFocus()` 返回 Unit，"没抛异常"不等于"焦点真落上了"——
     * 实测在这个输入框上它是**静默无效**的（焦点留在外面那条隐形跳板上）。
     * 所以外面必须靠这个回调确认真落上了，而不是靠 isSuccess。
     */
    onSearchFocus: (Boolean) -> Unit = {},
) {
    val rows = remember { mutableStateListOf<FilterRow>() }
    val selected = remember {
        val initial = when {
            FindMemo.selected.isNotEmpty() -> FindMemo.selected
            FindMemo.defaultGenreApplied -> emptyList()
            else -> {
                FindMemo.defaultGenreApplied = true
                listOf("genre" to "comic_series")   // 三端默认漫剧
            }
        }
        mutableStateListOf<Pair<String, String>>().apply { addAll(initial) }
    } // (key, id)
    val cards = remember {
        mutableStateListOf<SeriesCard>().apply {
            // 从详情返回时先把上次的结果顶上，避免闪一下空白
            if (FindMemo.searchQuery != null) addAll(FindMemo.cards)
        }
    }
    var loading by remember { mutableStateOf(true) }
    var listLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 「重试」自增一次就重跑加载
    var retryTick by remember { mutableStateOf(0) }
    var session by remember { mutableStateOf("") }
    var offset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    // 从 FindMemo 恢复：从详情返回时关键词和结果都还在
    // 用 TextFieldValue（而不是 String）是为了自己掌握**光标位置**：
    // 遥控器上没有方向键挪光标，光标必须落在文字末尾，否则退回删除键删不掉
    // （旧实现回到搜索框时光标在开头，用户按删除毫无反应 = 搜索功能被卡死）。
    var query by remember {
        mutableStateOf(
            TextFieldValue(
                text = FindMemo.query,
                selection = TextRange(FindMemo.query.length),
            ),
        )
    }
    var searchQuery by remember { mutableStateOf(FindMemo.searchQuery) } // 非空 = 搜索结果模式
    // 电视上「选中搜索框」≠「正在输入」：选中只代表能按确定，
    // 按了确定才进输入态（拉输入法），否则一聚焦键盘就弹出来
    var typing by remember { mutableStateOf(false) }
    // TV 上用遥控器，联想下拉既挡内容又难选（还得先按上下跳出输入框），直接关掉
    val isTv = Store.appContext.packageName.endsWith("nativeapp")
    val focusManager = LocalFocusManager.current
    /** 第一个筛选 chip 的焦点请求器：搜索框按 ↓ 直接跳到这里 */
    val chipEntry = remember { FocusRequester() }
    // 搜索框自己画焦点框：它不是 clickable，套不到全站那套白色焦点框，
    // 焦点进去后屏幕上毫无提示，用户会以为焦点丢了
    var searchFocused by remember { mutableStateOf(false) }
    // 搜索分页（对齐 Windows：offset + search_id 续页）与联想候选
    var searchOffset by remember { mutableStateOf(0) }
    var searchId by remember { mutableStateOf("") }
    var searchHasMore by remember { mutableStateOf(false) }
    val suggest = remember { mutableStateListOf<String>() }
    val grid = UiMemo.findGrid

    val selectedMap = selected.toMap()

    // 原版同款联动：体裁（大类）一变，筛选面板整块重拉——
    // 主题 / 设定 / 背景的小类是跟着体裁走的，不同体裁下选项完全不同。
    // 重拉后，在新面板里已经找不到的旧选择（id 变了的行）自动作废。
    val genreSel = selectedMap["genre"] ?: ""
    LaunchedEffect(genreSel) {
        // 命中内存缓存时瞬时渲染；未命中最多退避重试 3 次
        if (Api.hasCachedRows(genreSel)) {
            rows.clear()
            rows.addAll(Api.findRowsCached(genreSel))
            normalizeFilterRows(rows)
            loading = false
            return@LaunchedEffect
        }
        loading = true
        var r: List<FilterRow> = emptyList()
        for (attempt in 0 until 3) {
            r = withContext(Dispatchers.IO) { runCatching { Api.findRowsCached(genreSel) }.getOrDefault(emptyList()) }
            if (r.isNotEmpty()) break
            if (attempt < 2) delay(1500L * (attempt + 1))
        }
        rows.clear()
        rows.addAll(r)
        normalizeFilterRows(rows)
        val validIds = r.associate { it.key to it.items.map { item -> item.id }.toSet() }
        selected.removeAll { sel ->
            sel.first != "genre" && validIds[sel.first]?.contains(sel.second) != true
        }
        loading = false
    }

    // 筛选或体裁变化 → 重拉列表
    // 筛选条件写回记忆（离开页面后从详情返回还能对得上）
    LaunchedEffect(selectedMap) { FindMemo.selected = selected.toList() }

    // 从详情返回：条件 + 结果都还在，就不要再拉一次（否则会清空列表、跳回顶部）
    var restoredOnce by remember { mutableStateOf(FindMemo.cards.isNotEmpty()) }

    LaunchedEffect(selectedMap, retryTick) {
        if (searchQuery != null) return@LaunchedEffect
        if (restoredOnce) { restoredOnce = false; return@LaunchedEffect }
        listLoading = true
        error = null
        val page = withContext(Dispatchers.IO) {
            runCatching { Api.findList(selectedMap, 0, "") }.getOrNull()
        }
        cards.clear()
        if (page != null) {
            cards.addAll(page.cards)
            session = page.sessionId
            offset = page.nextOffset
            hasMore = page.hasMore
            if (page.cards.isEmpty()) error = "这个组合没有内容，换个筛选试试"
        } else {
            error = "加载失败，稍后再试"
        }
        // 换筛选条件回到页首：否则还停在上一次的滚动位置，看到的是一半内容
        runCatching { grid.scrollToItem(0) }
        listLoading = false
    }

    // 搜索模式
    // 搜索结果首屏（提交搜索 / 换关键词时）
    LaunchedEffect(searchQuery, retryTick) {
        val q = searchQuery ?: return@LaunchedEffect
        if (restoredOnce) { restoredOnce = false; return@LaunchedEffect }
        listLoading = true
        error = null
        suggest.clear()
        val page = withContext(Dispatchers.IO) {
            runCatching { Api.searchPage(q, 0, "") }.getOrNull()
        }
        cards.clear()
        if (page != null) {
            cards.addAll(page.cards)
            searchOffset = page.nextOffset
            searchId = page.sessionId
            searchHasMore = page.hasMore
            // 记下来：进详情再返回时直接还原这一屏
            FindMemo.cards = page.cards
            FindMemo.searchId = page.sessionId
            FindMemo.offset = page.nextOffset
            FindMemo.hasMore = page.hasMore
            if (page.cards.isEmpty()) error = "没搜到「$q」，换个关键词试试"
        } else {
            error = "搜索失败，稍后再试"
        }
        runCatching { grid.scrollToItem(0) }
        listLoading = false
    }

    // 联想：输入 2 个字以上才开始问，250ms 防抖；提交搜索后就不再弹。
    // TV 版整个关掉（见 isTv 说明）。
    LaunchedEffect(query.text, searchQuery, isTv) {
        val w = query.text.trim()
        if (isTv || searchQuery != null || w.length < 2) {
            suggest.clear()
            return@LaunchedEffect
        }
        delay(250)
        val words = withContext(Dispatchers.IO) {
            runCatching { Api.searchSuggest(w) }.getOrDefault(emptyList())
        }
        suggest.clear()
        suggest.addAll(words)
    }


    Column(Modifier.fillMaxSize()) {

        /**
         * 整页只有一个滚动容器（LazyVerticalGrid），搜索行 + 筛选区是它的**头部**。
         *
         * 之前筛选区是钉死的：往下滑只有下面那半屏在动，上面 50% 永远占着不动，
         * 能看内容的地方只剩一半，非常反人类。现在筛选跟着列表一起上滚，
         * 滑下去之后整屏都是结果，往回滑筛选再出现 —— 和 Windows 版一个行为。
         *
         * 首屏仍然是「筛选 + 一整行卡片」：卡片行高按首屏剩余高度算，
         * 但不超过 0.72 封面比例的卡片自然高度（否则封面会被拉长）。
         */
        BoxWithConstraints(Modifier.fillMaxSize().weight(1f)) {
            val totalW = maxWidth
            val showFilter = searchQuery == null
            // 卡片宽 =（屏宽 - 左右 padding - 5 个间距）/ 6
            val cardW = (totalW - 48.dp - 60.dp) / 6
            // 筛选区改成 PC 同款多行换行后高度不再固定，卡片统一按自然高度排，
            // 整页跟着滚动（和 PC 的找剧页一致），不再硬算首屏塞几行。
            val rowH = (cardW / 0.72f + 66.dp).coerceAtLeast(160.dp)

            // 滚到接近底部就续页。头部占一个 item，所以卡片索引要 -1。
            LaunchedEffect(Unit) {
                snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                    .debounce(250)
                    .distinctUntilChanged()
                    .collect { last ->
                        if (searchQuery != null) {
                            // 搜索：还有下一页就继续拉（offset + search_id 续页）。
                            // 必须真的滚到最后一条才拉——只看「可见尾索引接近条数」的话，
                            // 结果刚好一两屏时一进页面就满足条件，会连环拉好几页（实测 20→80）。
                            if (listLoading || !searchHasMore || cards.isEmpty()) return@collect
                            val info = grid.layoutInfo
                            if (last < info.totalItemsCount - 1) return@collect
                            listLoading = true
                            val q = searchQuery ?: return@collect
                            val page = withContext(Dispatchers.IO) {
                                runCatching { Api.searchPage(q, searchOffset, searchId) }.getOrNull()
                            }
                            if (page != null && page.cards.isNotEmpty()) {
                                val known = cards.map { it.seriesId }.toSet()
                                cards.addAll(page.cards.filter { it.seriesId !in known })
                                searchOffset = page.nextOffset
                                searchId = page.sessionId
                                searchHasMore = page.hasMore
                            } else {
                                searchHasMore = false
                            }
                            listLoading = false
                            return@collect
                        }
                        if (listLoading || !hasMore || cards.isEmpty()) return@collect
                        if (last - 1 < cards.size - 7) return@collect
                        listLoading = true
                        val page = withContext(Dispatchers.IO) {
                            runCatching { Api.findList(selectedMap, offset, session) }.getOrNull()
                        }
                        if (page != null && page.cards.isNotEmpty()) {
                            val known = cards.map { it.seriesId }.toSet()
                            cards.addAll(page.cards.filter { it.seriesId !in known })
                            session = page.sessionId
                            offset = page.nextOffset
                            hasMore = page.hasMore
                        } else {
                            hasMore = false
                        }
                        listLoading = false
                    }
            }

            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Fixed(6),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 头部：搜索行 + 筛选区（跟着滚，不钉死）
                item(span = { GridItemSpan(6) }, key = "find_header") {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp, bottom = if (showFilter) 10.dp else 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.weight(1f)) {
                            BasicTextField(
                                value = query,
                                onValueChange = {
                                    query = it
                                    FindMemo.query = it.text
                                },
                                textStyle = TextStyle(color = TextMain, fontSize = 16.sp),
                                cursorBrush = SolidColor(Red),
                                singleLine = true,
                                // 电视上默认只读：聚焦不弹输入法，按「确定」才进输入态
                                readOnly = isTv && !typing,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                                ),
                                // 回车/键盘「搜索」键直接提交（对齐 Windows 的 Enter 搜索）
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                    onSearch = {
                                        val w = query.text.trim()
                                        if (w.isNotEmpty()) {
                                            searchQuery = w
                                            FindMemo.searchQuery = w
                                        }
                                        typing = false
                                    },
                                ),
                                // 遥控器上这是唯一能出输入框的口子：
                                // 单行输入框里上下键本来没用，拦下来把焦点送给相邻控件。
                                // 之前不拦，焦点进来就出不去，遥控器只能干瞪眼。
                                modifier = Modifier
                                    .then(
                                        if (entryFocus != null) Modifier.focusRequester(entryFocus)
                                        else Modifier,
                                    )
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .onFocusChanged {
                                        searchFocused = it.isFocused
                                        onSearchFocus(it.isFocused)
                                        // 焦点落到搜索框：把光标收到文字末尾。
                                        // 遥控器没有左右键挪光标，落在别处（尤其是开头）
                                        // 就等于删除键失效，用户没法改上一次的关键词。
                                        // 只在电视上做：触屏平板上点哪儿就该在哪儿，不能抢。
                                        if (isTv && it.isFocused && query.selection != TextRange(query.text.length)) {
                                            query = query.copy(selection = TextRange(query.text.length))
                                        }
                                        // 焦点离开（按返回关掉键盘等）就退回选中态
                                        if (!it.isFocused && typing) typing = false
                                    }
                                    .border(
                                        width = if (searchFocused) 3.dp else 0.dp,
                                        color = if (searchFocused) Color.White else Color.Transparent,
                                        shape = RoundedCornerShape(19.dp),
                                    )
                                    .onPreviewKeyEvent { ev ->
                                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (ev.key) {
                                            // 确定 = 进输入态（只有电视要，触屏直接点输入框就行）
                                            Key.DirectionCenter, Key.Enter -> {
                                                if (isTv && !typing) {
                                                    typing = true
                                                    // 进输入态的同时把光标放到末尾：删改上一次的
                                                    // 关键词只能从末尾退，落在开头就删不动
                                                    query = query.copy(selection = TextRange(query.text.length))
                                                    true
                                                } else false
                                            }
                                            // 输入态下按返回 = 收键盘、回到选中态，不要退出页面
                                            Key.Back -> {
                                                if (isTv && typing) {
                                                    typing = false
                                                    true
                                                } else false
                                            }
                                            // 返回 moveFocus 的结果：真的移走了才消费按键，
                                            // 没移走就交回默认处理，别把事件吃掉让人按了没反应
                                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                                            // 实测：从输入框往下做几何搜索找不到筛选 chip（moveFocus 返回 false）。
                                            // 所以按「先点名、再几何、最后往右」三级兜底：
                                            // ①②都失败时退到右边那颗红色「搜索」按钮，至少让遥控器有路可走。
                                            // 实测：几何搜索从输入框往 ↓ 找不到筛选 chip（chips 在横向滚动容器里，
                                            // 好像自成一个焦点区）。顺序焦点（等价 TAB）不受这个限制，所以用它。
                                            Key.DirectionDown -> {
                                                val r0 = runCatching { chipEntry.requestFocus() }
                                                val d = if (r0.isSuccess) true else focusManager.moveFocus(FocusDirection.Down)
                                                val nx = if (!d) focusManager.moveFocus(FocusDirection.Next) else false
                                                val rt = if (!d && !nx) focusManager.moveFocus(FocusDirection.Right) else false
                                                d || nx || rt
                                            }
                                            else -> false
                                        }
                                    }
                                    .clip(RoundedCornerShape(19.dp))
                                    .background(PanelHi)
                                    .padding(horizontal = 16.dp),
                                decorationBox = { inner ->
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                        if (query.text.isEmpty()) Text("输入剧名 / 关键词", color = TextDim, fontSize = 14.sp)
                                        inner()
                                    }
                                },
                            )
                            if (!isTv && searchQuery == null && suggest.isNotEmpty()) {
                                // 联想候选浮在输入框下面（Popup 不参与布局），
                                // 和 Windows 一样是覆盖式下拉，不会把筛选区挤走
                                val offsetY = with(androidx.compose.ui.platform.LocalDensity.current) {
                                    (46.dp).roundToPx()
                                }
                                androidx.compose.ui.window.Popup(
                                    alignment = Alignment.TopStart,
                                    offset = androidx.compose.ui.unit.IntOffset(0, offsetY),
                                    onDismissRequest = { suggest.clear() },
                                ) {
                                    Column(
                                        Modifier
                                            .width(520.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Panel)
                                            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(12.dp))
                                            .padding(vertical = 4.dp),
                                    ) {
                                        suggest.take(6).forEach { word ->
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        query = TextFieldValue(word, TextRange(word.length))
                                                        searchQuery = word
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text("搜索", color = Color(0x66FFFFFF), fontSize = 11.sp)
                                                Spacer(Modifier.width(8.dp))
                                                Text(word, color = TextMain, fontSize = 14.sp, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                            }

                            // 搜索/退出搜索两态：已经在搜索里改了关键词，按钮要变回「搜索」，
                            // 否则用户改完词点一下只会退出搜索（实测很容易踩）
                            val q = query.text.trim()
                            val reSearch = searchQuery != null && q.isNotEmpty() && q != searchQuery
                            Box(
                                Modifier
                                    // 从这颗按钮往下也是断的（几何搜索找不到筛选区），
                                    // 点名跳到第一颗 chip，保证遥控器一路走得到卡片
                                    .onPreviewKeyEvent { ev ->
                                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) {
                                            val r0 = runCatching { chipEntry.requestFocus() }
                                            val r1 = if (r0.isSuccess) true else focusManager.moveFocus(FocusDirection.Down)
                                            val r2 = if (!r1) focusManager.moveFocus(FocusDirection.Next) else false
                                            r1 || r2
                                        } else false
                                    }
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(21.dp))
                                    .background(Red)
                                    .clickable {
                                        if (searchQuery == null || reSearch) {
                                            if (q.isNotBlank()) Store.pushSearch(q)
                                            searchQuery = q.ifEmpty { null }
                                            // 按钮提交这条路径也要写进记忆，否则从详情返回后
                                            // 关键词还在、结果却没了（回到筛选页）
                                            FindMemo.searchQuery = searchQuery
                                            FindMemo.query = q
                                        } else {
                                            searchQuery = null
                                            FindMemo.searchQuery = null
                                            FindMemo.cards = emptyList()
                                        }
                                    }
                                    .padding(horizontal = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (searchQuery == null || reSearch) "搜索" else "退出搜索",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            // 与 PC 同位置：搜索按钮右侧。接口不给总数，写「已加载」才不骗人
                            Text("已加载 ${cards.size} 部", color = TextDim, fontSize = 14.sp)
                        }

                        // 搜索历史：换过几次关键词后点一下就搜，不用在电视上重打
                        if (searchQuery == null && query.text.isEmpty() && Store.searchHistory.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp).androidHorizontalScroll(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "最近搜索",
                                    color = TextDim, fontSize = 12.sp,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                                Store.searchHistory.take(8).forEach { w ->
                                    OutlinePill(w) {
                                        query = TextFieldValue(w, TextRange(w.length))
                                        Store.pushSearch(w)
                                        searchQuery = w
                                        FindMemo.searchQuery = w
                                        FindMemo.query = w
                                    }
                                }
                            }
                        }

                        if (showFilter) {
                            if (rows.isEmpty()) {
                                Box(Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center) {
                                    LoadingSpinner(size = 24.dp, stroke = 2.5.dp)
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                rows.forEachIndexed { idx, row ->
                                    FilterGroup(
                                        label = row.name,
                                        items = listOf("全部") + row.items.map { it.label },
                                        ids = listOf("") + row.items.map { it.id },
                                        selectedId = selectedMap[row.key] ?: "",
                                        // 第一行第一颗 chip 就是「搜索框按 ↓」的落点
                                        firstFocus = if (idx == 0) chipEntry else null,
                                        onPick = { id ->
                                            if (row.key == "genre") {
                                                // 换体裁 = 整套主题/设定/背景都换了一套 id，
                                                // 旧选择必须当场清掉，否则会先发一次非法组合，
                                                // 界面闪一下「这个组合没有内容」。
                                                selected.clear()
                                                if (id.isNotEmpty()) selected.add(row.key to id)
                                            } else {
                                                selected.removeAll { it.first == row.key }
                                                if (id.isNotEmpty()) selected.add(row.key to id)
                                            }
                                        },
                                    )
                                }
                                // 与 PC 同款：有选择时在筛选区右下角出现「重置筛选」
                                if (selected.isNotEmpty()) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        Text(
                                            "重置筛选",
                                            color = Red,
                                            fontSize = 12.sp,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { selected.clear() }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }

                if (loading && cards.isEmpty()) {
                    item(span = { GridItemSpan(6) }, key = "find_loading") {
                        Box(Modifier.fillMaxWidth().height(rowH), contentAlignment = Alignment.Center) {
                            LoadingBlock()
                        }
                    }
                } else if (cards.isEmpty()) {
                    item(span = { GridItemSpan(6) }, key = "find_empty") {
                        Box(Modifier.fillMaxWidth().height(rowH), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(error ?: "没有匹配的剧", color = TextDim, fontSize = 14.sp)
                                if (error != null) {
                                    Spacer(Modifier.height(10.dp))
                                    OutlinePill("重试") { retryTick++ }
                                }
                            }
                        }
                    }
                }

                gridItems(cards, key = { it.seriesId }) { card ->
                    SeriesCardView(
                        card = card,
                        modifier = Modifier.fillMaxWidth(),
                        cardHeight = rowH,
                        onClick = {
                            Api.stashCard(card)
                            onOpen(card)
                        },
                    )
                }

                if (listLoading && cards.isNotEmpty()) {
                    item(span = { GridItemSpan(6) }, key = "find_more") {
                        Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                            LoadingSpinner(size = 26.dp, stroke = 3.dp)
                        }
                    }
                } else if (!hasMore && cards.isNotEmpty() && searchQuery == null) {
                    item(span = { GridItemSpan(6) }, key = "find_end") {
                        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                            Text("没有更多了", color = TextDim, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}



/**
 * 筛选组（对齐 PC 找剧页）：左侧组名 + 右侧多行换行芯片。
 * 原来是单行横向滚动，选项被藏在屏幕外（主题 26 个、设定 50+），
 * 和 PC 的「全部摊开」完全不是一回事——用户反馈筛选框不对就是这里。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterGroup(
    label: String,
    items: List<String>,
    ids: List<String>,
    selectedId: String,
    firstFocus: FocusRequester?,
    onPick: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = TextDim,
            fontSize = 12.sp,
            modifier = Modifier.width(56.dp).padding(top = 8.dp),
        )
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEachIndexed { i, text ->
                val id = ids.getOrElse(i) { "" }
                val sel = id == selectedId
                Box(
                    Modifier
                        .then(if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        // 与 PC 一致：选中是红薄底 + 红字 + 细红边，实心红只给动作按钮
                        .background(if (sel) Red.copy(alpha = 0.16f) else Panel)
                        .border(
                            1.dp,
                            if (sel) Red.copy(alpha = 0.45f) else Color(0x1AFFFFFF),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onPick(if (sel) "" else id) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text,
                        color = if (sel) Color(0xFFFF6B6D) else TextDim,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun Modifier.verticalScrollCompat(): Modifier = this.verticalScroll(
    androidx.compose.foundation.rememberScrollState(),
)

// ---------------- 排行榜 ----------------
