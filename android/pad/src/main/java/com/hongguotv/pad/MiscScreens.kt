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
private fun OutlinePill(text: String, enabled: Boolean = true, onClick: () -> Unit) {
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
    val selected = remember { mutableStateListOf<Pair<String, String>>() } // (key, id)
    val cards = remember {
        mutableStateListOf<SeriesCard>().apply {
            // 从详情返回时先把上次的结果顶上，避免闪一下空白
            if (FindMemo.searchQuery != null) addAll(FindMemo.cards)
        }
    }
    var loading by remember { mutableStateOf(true) }
    var listLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
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
    LaunchedEffect(selectedMap) {
        if (searchQuery != null) return@LaunchedEffect
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
    LaunchedEffect(searchQuery) {
        val q = searchQuery ?: return@LaunchedEffect
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
                            // 与 PC 同位置：搜索按钮右侧「共 N 部」
                            Text("共 ${cards.size} 部", color = TextDim, fontSize = 14.sp)
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
                            Text(error ?: "没有匹配的剧", color = TextDim, fontSize = 14.sp)
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
private fun FilterGroup(
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
private fun Modifier.verticalScrollCompat(): Modifier = this.verticalScroll(
    androidx.compose.foundation.rememberScrollState(),
)

// ---------------- 排行榜 ----------------

@Composable
fun RankScreen(
    onOpen: (SeriesCard) -> Unit,
    onPlay: (SeriesCard) -> Unit,
    onCreator: (String) -> Unit = {},
) {
    val tabs = remember { mutableStateListOf<RankTabsel>() }
    // 初始化自 RankMemo：进详情/播放再返回，保持上次选的大类和子榜
    var tabIdx by remember { mutableStateOf(RankMemo.tabIdx) }
    var subIdx by remember { mutableStateOf(RankMemo.subIdx) }
    val cards = remember { mutableStateListOf<SeriesCard>() }
    // 演员榜返回的是「人」，结构和剧集榜完全不同，单独一条列表
    val persons = remember { mutableStateListOf<RankPerson>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf("") }
    var offset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    val rankList = UiMemo.rankList
    // 这一屏榜单数据真正的拉取时刻（0 = 还没拿到）。来自服务端返回的 age：
    // age 是「这份数据离从上游拉下来过去了多久」，所以真实时刻 = 现在 - age。
    var dataAt by remember { mutableStateOf(0L) }
    // 榜单元数据（子榜的「N月N日已更新」）上次拉取时刻：挂着过夜时切榜要重拉，
    // 否则描述行一直停在昨天（PC 端同一时刻显示今天的日期，两端对不上）
    var metaAt by remember { mutableStateOf(0L) }
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
    LaunchedEffect(tabIdx, subIdx, tabs.size, panelSel, monthSel) {
        if (tabs.isEmpty()) return@LaunchedEffect
        // 挂着过夜再切榜：元数据超过 1 分钟就重拉一次，描述行别停在昨天
        if (System.currentTimeMillis() - metaAt > 60_000) {
            val fresh = withContext(Dispatchers.IO) { runCatching { Api.rankMeta() }.getOrDefault(emptyList()) }
            if (fresh.isNotEmpty()) {
                tabs.clear()
                tabs.addAll(fresh)
                metaAt = System.currentTimeMillis()
            }
        }
        loading = true
        error = null
        hasMore = true
        session = ""
        offset = 0
        dataAt = 0L
        // 换筛选/换月榜都是「换一份榜单」，从第 1 名看起。
        // 必须用 requestScrollToItem：此刻页面还在 loading、列表根本不在组合里，
        // 用挂起版 scrollToItem 会一直等列表挂载，而列表又要等这次加载结束 —— 互相卡死。
        runCatching { rankList.requestScrollToItem(0) }
        // 演员大类走人员榜接口，别用解析剧集的那套（那样永远是空的，就是之前的「榜单加载失败」）
        if (tab?.id == "ranklist_celebrity") {
            val list = withContext(Dispatchers.IO) {
                runCatching { Api.rankActors(tab.id, sub?.id ?: "") }.getOrDefault(emptyList())
            }
            cards.clear()
            persons.clear()
            persons.addAll(list)
            if (persons.isEmpty()) error = "榜单加载失败"
            loading = false
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
                } else {
                    hasMore = false
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
                    tabIdx = i; subIdx = 0; panelSel = ""; monthSel = ""
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
                        subIdx = i; panelSel = ""; monthSel = ""
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
                title = "筛选 · ${sub.label}",
                rows = sub.panels.map { p -> p.name to p.items },
                selected = panelSel,
                onPick = { id -> panelSel = id; sheet = ""; runCatching { filterFocus.requestFocus() } },
                onClose = { sheet = ""; runCatching { filterFocus.requestFocus() } },
            )
            sub != null && sheet == "month" -> RankSheet(
                title = "月榜 · ${sub.label}",
                rows = listOf("选择月份" to sub.months),
                selected = monthSel,
                onPick = { id -> monthSel = id; sheet = ""; runCatching { monthFocus.requestFocus() } },
                onClose = { sheet = ""; runCatching { monthFocus.requestFocus() } },
            )
            loading -> StatusBox(loading = true, error = null)
            error != null && cards.isEmpty() && persons.isEmpty() -> StatusBox(loading = false, error = error)
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
private fun RankSheet(
    title: String,
    rows: List<Pair<String, List<RankOption>>>,
    selected: String,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
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
        Text(
            title,
            color = TextMain,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
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
private fun ActorRankRow(person: RankPerson, index: Int, onClick: () -> Unit) {
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
private fun RankCard(card: SeriesCard, index: Int, onOpen: () -> Unit, onPlay: () -> Unit) {
    // 电视：远看要清楚 —— 封面更大、字更大、简介三行、热度贴着标签放同一行。
    // 平板保持原来的紧凑规格（同一组件，按设备分开）。
    val isTv = Store.appContext.packageName.endsWith("nativeapp")
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
            .clickable { onOpen() }
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
                        .clickable { onOpen() }
                        .padding(horizontal = btnPadH, vertical = btnPadV),
                ) { Text("详情", color = Color.White, fontSize = btnFont) }
            }
        }
    }
}

@Composable
private fun Modifier.androidHorizontalScroll(): Modifier = this.horizontalScroll(
    androidx.compose.foundation.rememberScrollState(),
)

// ---------------- 新剧 ----------------

@Composable
fun NewDramaScreen(onOpen: (SeriesCard) -> Unit, onPlay: (String) -> Unit = {}) {
    var tab by remember { mutableStateOf(0) }      // 0 新剧推荐 1 上新日历
    var gender by remember { mutableStateOf(0) }   // 0 全部 1 男频 2 女频（原版映射）
    val cards = remember { mutableStateListOf<SeriesCard>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 失败后点「重新加载」用：自增一次就重新拉一遍
    var reload by remember { mutableStateOf(0) }
    var session by remember { mutableStateOf("") }
    var offset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    val grid = UiMemo.newGrid

    LaunchedEffect(tab, gender) {
        if (tab != 0) return@LaunchedEffect
        loading = true
        error = null
        hasMore = true
        var err: String? = null
        val page = withContext(Dispatchers.IO) {
            try { Api.newDramaCached(gender, 0, "") } catch (e: Exception) { err = e.message; null }
        }
        cards.clear()
        if (page != null && page.cards.isNotEmpty()) {
            cards.addAll(page.cards)
            session = page.sessionId
            offset = page.nextOffset
            hasMore = page.hasMore
        } else {
            error = "新剧加载失败" + (err?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
        }
        loading = false
    }

    LaunchedEffect(tab, gender, cards.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .debounce(300)
            .distinctUntilChanged()
            .collect { last ->
                if (tab != 0 || loading || loadingMore || !hasMore || cards.isEmpty()) return@collect
                if (last < cards.size - 6) return@collect
                loadingMore = true
                val page = withContext(Dispatchers.IO) {
                    runCatching { Api.newDrama(gender, offset, session) }.getOrNull()
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
                loadingMore = false
            }
    }

    Column(Modifier.fillMaxSize()) {
        // 双 tab（原版：新剧推荐 / 上新日历）。与 PC 一致不显示页面大标题
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            listOf("新剧推荐", "上新日历").forEachIndexed { i, label ->
                TvChip(label, selected = tab == i) { tab = i }
            }
        }
        if (tab == 0) {
            // 受众筛选行
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("全部", "男频", "女频").forEachIndexed { i, label ->
                    TvChip(label, selected = gender == i) { gender = i }
                }
            }
        }
        when {
            tab == 1 -> CalendarTab(onOpen, onPlay)
            loading && cards.isEmpty() -> StatusBox(loading = true, error = null)
            error != null && cards.isEmpty() -> StatusBox(loading = false, error = error)
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                // 和首页同一套算法：两行正好铺满，第二行不会被屏幕边缘切掉
                val gap = 12.dp
                val rowH = ((maxHeight - 4.dp - 8.dp - gap) / 2).coerceAtLeast(140.dp)
                LazyVerticalGrid(
                    state = grid,
                    columns = GridCells.Fixed(6),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    gridItems(cards, key = { it.seriesId + it.title }) { card ->
                        SeriesCardView(card, cardHeight = rowH, onClick = { onOpen(card) })
                    }
                }
            }
        }
    }
}

/** 上新日历的一条（字段照 Windows 版 CalendarItem 取，两套 schema 都归一到这里）。 */
private data class CalendarItem(
    val seriesId: String,
    val title: String,
    val cover: String,
    val desc: String,
    /** 卡片第二行橙色那条：已上线是「333万播放」，待上线是「1570.3万人预约」 */
    val heat: String,
    val score: String,
    val category: String,
    val episodeCount: Int,
    val timeSec: Long,
    val online: Boolean,
    val subscribed: Boolean,
)

/**
 * 上新日历。
 *
 * 三个必须按官方口径处理的点（对齐 Windows 版 calendar.rs）：
 * 1. **切日期要带 `target_date=YYYYMMDD` 重新请求**。不带的话服务端只回默认日
 *    （今天）那批，按天过滤后其它日期必然「暂无上新」。
 * 2. **条目有两套 schema**：今天那批是嵌套 `subscribe_data.*`，其它日期是扁平
 *    `name / item_id`。只认嵌套会把别的日子的条目全丢掉。
 * 3. 发布时间是 `schedule_publish_time`（秒），**要连日期一起显示**（09-27 12:00）。
 *    只显示 HH:mm 时，平台统一给的 00:0x 看着就像 bug。
 */
@Composable
private fun CalendarTab(onOpen: (SeriesCard) -> Unit, onPlay: (String) -> Unit) {
    val dayFmt = remember { java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA) }
    val stampFmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA) }
    val days = remember { mutableStateListOf<String>() }
    val byDay = remember { mutableStateMapOf<String, MutableList<CalendarItem>>() }
    var selected by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var dayLoading by remember { mutableStateOf(false) }
    val more = remember { mutableStateMapOf<String, Pair<Int, String>>() }
    val scope = rememberCoroutineScope()

    fun parseDay(j: JSONObject?): List<CalendarItem> {
        val data = j?.optJSONObject("data") ?: return emptyList()
        val arr = data.optJSONArray("subscribe_items") ?: return emptyList()
        val out = mutableListOf<CalendarItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sd = o.optJSONObject("subscribe_data")
            val title = sd?.optString("title").orEmpty()
                .ifEmpty { o.optString("name") }
                .ifEmpty { sd?.optString("series_title").orEmpty() }
            if (title.isEmpty()) continue
            val sid = sd?.optString("series_id").orEmpty()
                .ifEmpty { o.optString("item_id") }
                .ifEmpty { o.optString("series_id") }
            if (sid.isEmpty() || sid == "0") continue
            var ts = o.optLong("schedule_publish_time", 0L)
            if (ts in 1..9_999_999_999L) ts *= 1000
            // 橙色那格：rec_tags 首条（「333万播放」/「1570.3万人预约」），
            // 没有就退回 play_cnt、expected_rec_text
            var heat = ""
            o.optJSONArray("rec_tags")?.let { a ->
                for (k in 0 until a.length()) {
                    val node = a.optJSONObject(k) ?: continue
                    val t = node.optString("text").ifEmpty { node.optString("content") }
                    if (t.isNotBlank()) {
                        heat = t
                        return@let
                    }
                }
            }
            if (heat.isEmpty()) {
                val pc = sd?.optLong("play_cnt", 0L) ?: 0L
                heat = if (pc > 0) "${formatCount(pc.toString())}播放" else o.optString("expected_rec_text")
            }
            out.add(
                CalendarItem(
                    seriesId = sid,
                    title = title,
                    cover = sd?.optString("cover").orEmpty().ifEmpty { o.optString("cover") },
                    desc = sd?.optString("video_desc").orEmpty().ifEmpty { o.optString("item_desc") },
                    heat = heat,
                    score = sd?.optString("score").orEmpty(),
                    category = o.optString("category"),
                    episodeCount = sd?.optInt("episode_cnt", 0) ?: o.optInt("episode_cnt", 0),
                    timeSec = ts,
                    online = o.optBoolean("is_online", false),
                    subscribed = o.optBoolean("has_subscribed", false) || Store.contains(Store.KEY_RESERVE, sid),
                ),
            )
        }
        return out
    }

    LaunchedEffect(Unit) {
        val j = withContext(Dispatchers.IO) { runCatching { Api.calendar() }.getOrNull() }
        val data = j?.optJSONObject("data")
        val schema = data?.optJSONObject("calendar_schema")
        val def = schema?.optString("default_date").orEmpty()
        schema?.optJSONArray("date_list")?.let { dateArr ->
            days.clear()
            for (i in 0 until dateArr.length()) {
                dateArr.optString(i).takeIf { d -> d.length == 8 }?.let(days::add)
            }
        }
        val items = parseDay(j)
        items.groupBy { dayFmt.format(java.util.Date(it.timeSec)) }.forEach { (d, list) ->
            byDay[d] = list.toMutableList()
        }
        data?.let { d ->
            if (d.optBoolean("has_more", false)) {
                more[dayFmt.format(java.util.Date())] =
                    d.optInt("next_offset", items.size) to d.optString("session_id")
            }
        }
        if (days.isEmpty() && items.isNotEmpty()) {
            items.map { dayFmt.format(java.util.Date(it.timeSec)) }.distinct().sorted().forEach(days::add)
        }
        if (days.isNotEmpty()) {
            val today = dayFmt.format(java.util.Date())
            selected = when {
                def.isNotEmpty() && days.contains(def) -> def
                days.contains(today) -> today
                else -> days.first()
            }
        }
        loading = false
    }

    // 选中没加载过的日期：带 target_date 重新请求（官方切日期的方式）
    LaunchedEffect(selected) {
        if (selected.isEmpty() || byDay.containsKey(selected)) return@LaunchedEffect
        dayLoading = true
        val j = withContext(Dispatchers.IO) {
            runCatching { Api.calendar(date = selected) }.getOrNull()
        }
        val list = parseDay(j)
        byDay[selected] = list.toMutableList()
        j?.optJSONObject("data")?.let { d ->
            if (d.optBoolean("has_more", false)) {
                more[selected] = d.optInt("next_offset", list.size) to d.optString("session_id")
            }
        }
        dayLoading = false
    }

    fun labelOf(day: String): String {
        val today = dayFmt.format(java.util.Date())
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        val tomorrow = dayFmt.format(cal.time)
        return when (day) {
            today -> "今天"
            tomorrow -> "明天"
            else -> if (day.length == 8) "${day.substring(4, 6)}-${day.substring(6)}" else day
        }
    }

    fun weekdayOf(day: String): String {
        val parsed = runCatching { dayFmt.parse(day) }.getOrNull() ?: return ""
        val cal = java.util.Calendar.getInstance().apply { time = parsed }
        return when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> "周一"
            java.util.Calendar.TUESDAY -> "周二"
            java.util.Calendar.WEDNESDAY -> "周三"
            java.util.Calendar.THURSDAY -> "周四"
            java.util.Calendar.FRIDAY -> "周五"
            java.util.Calendar.SATURDAY -> "周六"
            else -> "周日"
        }
    }

    if (loading) {
        StatusBox(loading = true, error = null)
        return
    }

    Column(Modifier.fillMaxSize()) {
        // 日期条：今天 / 明天 / MM-DD + 周几（拉过的日子带条数）
        Row(
            Modifier
                .fillMaxWidth()
                .androidHorizontalScroll()
                .padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            days.forEach { day ->
                val list = byDay[day]
                val sel = day == selected
                Column(
                    Modifier
                        .width(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) Red else Panel)
                        .clickable { selected = day }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        labelOf(day),
                        color = if (sel) Color.White else TextMain,
                        fontSize = 14.sp,
                        fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (list != null && list.isNotEmpty()) "${weekdayOf(day)} · ${list.size}" else weekdayOf(day),
                        color = if (sel) Color(0xCCFFFFFF) else TextDim,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        val dayItems = byDay[selected].orEmpty()
        when {
            dayLoading && dayItems.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                LoadingBlock()
            }
            dayItems.isEmpty() -> StatusBox(
                loading = false, error = null, empty = true,
                emptyText = if (selected.isEmpty()) "在详情页点「预约」，上线时间会显示在这里" else "这天暂无上新",
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                state = UiMemo.calTabList,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listItems(dayItems, key = { it.seriesId + it.timeSec }) { row ->
                    CalendarRow(
                        item = row,
                        stamp = if (row.timeSec > 0) stampFmt.format(java.util.Date(row.timeSec)) else "",
                        onOpen = {
                            onOpen(
                                SeriesCard(
                                    seriesId = row.seriesId,
                                    title = row.title,
                                    cover = row.cover,
                                    episodeCount = row.episodeCount,
                                ),
                            )
                        },
                        onPlay = { onPlay(row.seriesId) },
                        onReserve = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching { Api.reserveToggle(row.seriesId, true) }.getOrDefault(false)
                                }
                                if (ok) {
                                    byDay[selected]?.let { list ->
                                        val idx = list.indexOfFirst { it.seriesId == row.seriesId }
                                        if (idx >= 0) {
                                            list[idx] = list[idx].copy(subscribed = true)
                                            byDay[selected] = list.toMutableList()
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
                val page = more[selected]
                if (page != null) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "加载更多",
                                color = TextDim,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Panel)
                                    .clickable {
                                        scope.launch {
                                            val (offset, session) = page
                                            more.remove(selected)
                                            val j = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    Api.calendar(date = selected, offset = offset, session = session)
                                                }.getOrNull()
                                            }
                                            val moreItems = parseDay(j)
                                            if (moreItems.isNotEmpty()) {
                                                val merged = (byDay[selected].orEmpty() + moreItems)
                                                    .distinctBy { it.seriesId }
                                                    .toMutableList()
                                                byDay[selected] = merged
                                            }
                                            j?.optJSONObject("data")?.let { d ->
                                                if (d.optBoolean("has_more", false)) {
                                                    more[selected] =
                                                        d.optInt("next_offset", offset + moreItems.size) to
                                                        d.optString("session_id")
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 22.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 日历行：封面 + 标题/状态 + 热度/分类/评分/集数 + 简介，右侧发布时间与操作按钮。
 * 版式对齐 Windows 版 render_calendar_row。
 */
@Composable
private fun CalendarRow(
    item: CalendarItem,
    stamp: String,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onReserve: () -> Unit,
) {
    val isTv = Store.appContext.packageName.endsWith("nativeapp")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(12.dp))
            .clickable { onOpen() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = item.cover,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = if (isTv) 120.dp else 88.dp, height = if (isTv) 160.dp else 117.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CoverPlaceholder),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    color = TextMain,
                    fontSize = if (isTv) 21.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (item.online) "已上线" else "待上线",
                    color = if (item.online) Success else TextDim,
                    fontSize = if (isTv) 13.sp else 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(
                            if (item.online) Success.copy(alpha = 0.12f) else Color(0x14FFFFFF),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = if (isTv) 8.dp else 5.dp, vertical = if (isTv) 3.dp else 1.dp),
                )
            }
            // 热度 / 分类 / 评分 / 集数
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.score.takeIf { it.isNotBlank() && (it.toFloatOrNull() ?: 0f) > 0f }?.let {
                    Text("★", color = Star, fontSize = if (isTv) 15.sp else 12.sp)
                    Text(it, color = Star, fontSize = if (isTv) 15.sp else 12.sp, fontWeight = FontWeight.Medium)
                }
                item.heat.takeIf { it.isNotBlank() }?.let {
                    FlameIcon()
                    Spacer(Modifier.width(3.dp))
                    Text(it, color = Warm, fontSize = if (isTv) 15.sp else 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
            val meta = listOfNotNull(
                item.category.takeIf { it.isNotBlank() },
                item.episodeCount.takeIf { it > 0 }?.let { "全 ${it} 集" },
            ).joinToString(" · ").takeIf { it.isNotBlank() }
            meta?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = TextDim, fontSize = if (isTv) 15.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.desc.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    item.desc,
                    color = TextDim.copy(alpha = 0.8f),
                    fontSize = if (isTv) 15.sp else 12.sp,
                    lineHeight = if (isTv) 22.sp else 18.sp,
                    maxLines = if (isTv) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (stamp.isNotEmpty()) {
                Text(stamp, color = TextDim, fontSize = if (isTv) 13.sp else 10.sp)
                Spacer(Modifier.height(8.dp))
            }
            if (item.online) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Red)
                            .clickable { onPlay() }
                            .padding(horizontal = if (isTv) 20.dp else 12.dp, vertical = if (isTv) 9.dp else 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("播放", color = Color.White, fontSize = if (isTv) 15.sp else 11.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        "详情",
                        color = TextDim,
                        fontSize = if (isTv) 15.sp else 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onOpen() }
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                    )
                }
            } else {
                val done = item.subscribed
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (done) Color(0x14FFFFFF) else Red)
                        .clickable(enabled = !done) { onReserve() }
                        .padding(horizontal = if (isTv) 20.dp else 12.dp, vertical = if (isTv) 9.dp else 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (done) "已预约" else "预约",
                        color = if (done) TextDim else Color.White,
                        fontSize = if (isTv) 15.sp else 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

// ---------------- 历史 / 收藏 / 点赞（原版同页三 tab） ----------------

@Composable
fun RecordsScreen(
    initialTab: Int,
    onOpen: (String) -> Unit,
    onPlay: (seriesId: String, index: Int, startMs: Long) -> Unit,
) {
    var tab by remember { mutableStateOf(initialTab) }  // 0 历史 1 收藏 2 点赞
    var filter by remember { mutableStateOf(0) }        // 0 全部 1 未看完 2 已看完

    Column(Modifier.fillMaxSize()) {
        // 标题 + 三 tab（原版标题固定「历史」）
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 与 PC 一致不显示页面大标题，只留三 tab
            listOf("历史", "收藏", "点赞").forEachIndexed { i, label ->
                TvChip(label, selected = tab == i) { tab = i }
            }
        }
        when (tab) {
            0 -> HistoryTab(filter, setFilter = { filter = it }, onPlay = onPlay)
            1 -> ShelfTab(Store.KEY_FAVORITE, onOpen)
            else -> ShelfTab(Store.KEY_LIKE, onOpen)
        }
    }
}

@Composable
private fun HistoryTab(filter: Int, setFilter: (Int) -> Unit, onPlay: (String, Int, Long) -> Unit) {
    val rows = remember { mutableStateListOf<JSONObject>() }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loading = true
        rows.clear()
        rows.addAll(Store.history())
        loading = false
        // 云端合并：historySync 内部是「拉云端 → mergeHistory（按集数取大）→ 回写」
        // 注意：这里两处都不能 rows.clear() + addAll()
        // —— 本地历史是立刻显示的，用户这时候已经在列表里按方向键了。
        // 云端同步要一两秒，回来一整表重建的话，item 节点全销毁，焦点当场丢，
        // 表现就是「看历史看着看着，焦点自己跳回第一条」。改成原地对齐更新。
        if (Store.loggedIn) {
            val ok = withContext(Dispatchers.IO) { runCatching { Api.historySync() }.getOrDefault(false) }
            if (ok) {
                rows.diffUpdate(Store.history()) { it.optString("seriesId") }
            }
        }
        // 老数据里 cover 是空的（早期写历史时传了空串），进来顺手补一次
        if (rows.any { it.optString("cover").isEmpty() }) {
            val fixed = withContext(Dispatchers.IO) { runCatching { Api.backfillHistoryCovers() }.getOrDefault(0) }
            if (fixed > 0) {
                rows.diffUpdate(Store.history()) { it.optString("seriesId") }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 筛选行：全部 / 未看完 / 已看完 + 搜索 + 清空 + 管理（多选删除）
        var managing by remember { mutableStateOf(false) }
        var picked by remember { mutableStateOf(setOf<String>()) }
        var search by remember { mutableStateOf("") }
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
                onValueChange = { search = it },
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
                                    runCatching { Api.historyDelete(ids) }
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
                            runCatching { Api.historyDelete(ids) }
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
private fun HistoryRow(card: SeriesCard, row: JSONObject, onPlay: () -> Unit) {
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

private fun cardFromHistory(row: JSONObject): SeriesCard? {
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
private fun ShelfTab(key: String, onOpen: (String) -> Unit) {
    val cards = remember { mutableStateListOf<SeriesCard>() }
    var loading by remember { mutableStateOf(true) }
    var syncing by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(key, reload) {
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
@Composable
fun FollowReserveScreen(
    initialMode: Int,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit = {},
) {
    val mode = initialMode
    val cards = remember { mutableStateListOf<SeriesCard>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 失败后点「重新加载」用：自增一次就重新拉一遍
    var reload by remember { mutableStateOf(0) }
    // 0 = 第一个 tab（追更的剧 / 已上线），1 = 第二个（更新提醒 / 待上线）
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(mode, reload) {
        loading = true
        error = null
        cards.clear()
        tab = 0
        query = ""
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
                    onValueChange = { query = it },
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
                        .clickable { tab = i }
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
                                    if (mode == 0) Api.followToggle(id, false)
                                    else Api.reserveToggle(id, false)
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
private fun LibraryRow(
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
private fun GroupTitle(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

// ---------------- 账号 / 设置 / 登录 / 演职员 ----------------

@Composable
fun AccountScreen(
    onOpen: (String) -> Unit,
    onLogin: () -> Unit,
    /** 跳到「历史」页（PC 的我的页里收藏/点赞是 tab，TV 收敛为入口跳转到历史页三 tab） */
    onGoHistory: () -> Unit = {},
) {
    var syncMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 从设备：要输入的匹配码
    var inputCode by remember { mutableStateOf("") }
    // 主设备：共享码（没存过就随机生成一个候选）
    var shareCode by remember {
        mutableStateOf(Store.pairCode.ifBlank { (100000..999999).random().toString() })
    }

    fun refreshMsg(msg: String?) { syncMsg = msg }

    // 网络调用必须离开主线程：之前这四个按钮在 clickable 里同步发请求，
    // Compose 的点击回调跑在主线程 → NetworkOnMainThreadException（message 为 null），
    // 界面只会显示「同步失败 / 匹配失败」，看起来像服务端的问题。
    val scope = rememberCoroutineScope()
    fun runAsync(label: String, block: () -> String) {
        if (busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            val r = runCatching { block() }
            withContext(Dispatchers.Main) {
                busy = false
                syncMsg = r.fold(
                    { it.ifBlank { "$label 成功" } },
                    { it.message ?: "$label 失败（${it.javaClass.simpleName}）" },
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = UiMemo.settingsList,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 账户卡（**PC「我的」页同款首屏结构**）：头像 + 昵称 + 登录态说明。
            // 与 PC 一致不显示页面大标题，页面直接从账户卡开始。
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(50))
                            .background(PanelHi),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("👤", fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (Store.loggedIn) Store.accountName.ifBlank { "用户" } else "未登录",
                            color = TextMain,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (Store.loggedIn) "已同步云端收藏与点赞" else "登录后同步收藏与点赞",
                            color = TextDim,
                            fontSize = 12.sp,
                        )
                    }
                    if (!Store.loggedIn) {
                        TvChip("登录", selected = true) { onLogin() }
                    }
                }
            }
            // 收藏 / 点赞入口（**PC「我的」页的两个 tab**）：TV 上收敛成
            // 两个入口跳到历史页（那边的三 tab 是 历史/收藏/点赞），
            // 遥控器少一层 tab 切换，功能与 PC 等价。
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TvChip("收藏", selected = false) { onGoHistory() }
                    TvChip("点赞", selected = false) { onGoHistory() }
                }
            }
            // 状态卡（原版：绿字登录态 + 来源/uid + 退出）
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        if (Store.loggedIn) {
                            Text(
                                "已登录：${Store.accountName.ifBlank { "用户" }}",
                                color = Success,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            val source = when (Store.accountSource) {
                                "local" -> "本机登录（可作主设备共享给其它设备）"
                                "pair" -> "匹配自其它设备（从设备）"
                                else -> "同步自其它设备"
                            }
                            Text(
                                source + if (Store.accountUid.isNotEmpty()) " · uid ${Store.accountUid}" else "",
                                color = TextDim,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row {
                                TvChip("重新同步登录态", selected = false) {
                                    runAsync("同步") { Api.syncAccount(force = true); "已重新同步" }
                                }
                                Spacer(Modifier.width(10.dp))
                                TvChip("退出", selected = false) {
                                    Store.clearAccount()
                                    refreshMsg("已退出登录")
                                }
                            }
                        } else {
                            Text("未登录", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(6.dp))
                            Text("登录后收藏 / 追更 / 预约 / 历史跟着账号走", color = TextDim, fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))
                            Row {
                                TvChip("手机号登录", selected = true) { onLogin() }
                                Spacer(Modifier.width(10.dp))
                                TvChip("从电脑同步账号", selected = false) {
                                    runAsync("同步") {
                                        val name = Api.syncAccount()
                                        if (name.isBlank()) "已同步登录态" else "已同步：$name"
                                    }
                                }
                            }
                        }
                        syncMsg?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = TextDim, fontSize = 12.sp)
                        }
                    }
                }
            }
            // 主设备 · 共享我的账号
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("主设备 · 共享我的账号", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            if (!Store.loggedIn) {
                                Text("需先登录", color = TextDim, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "在其它设备输入这个 6 位匹配码，它就能用你的账号看收藏 / 历史 / 追更",
                            color = TextDim, fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                shareCode,
                                color = Red,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 4.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            TvChip("换个码", selected = false) {
                                shareCode = (100000..999999).random().toString()
                            }
                            Spacer(Modifier.width(10.dp))
                            TvChip("保存并共享", selected = Store.pairCode == shareCode) {
                                val code = shareCode
                                runAsync("共享") {
                                    Api.pairUpload(code)
                                    Store.pairCode = code
                                    "已共享，其它设备输入 $code 即可匹配"
                                }
                            }
                        }
                    }
                }
            }
            // 从设备 · 匹配其他设备
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Text("从设备 · 匹配其他设备", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "输入主设备显示的匹配码，登录态、收藏、历史会一起过来",
                            color = TextDim, fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(
                                value = inputCode,
                                onValueChange = { inputCode = it.filter { ch -> ch.isDigit() }.take(6) },
                                textStyle = TextStyle(color = TextMain, fontSize = 20.sp, letterSpacing = 3.sp),
                                cursorBrush = SolidColor(Red),
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PanelHi)
                                    .padding(horizontal = 14.dp),
                                decorationBox = { inner ->
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                        if (inputCode.isEmpty()) Text("输入 6 位匹配码", color = Color(0x66FFFFFF), fontSize = 14.sp)
                                        inner()
                                    }
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            TvChip("匹配", selected = inputCode.length == 6) {
                                if (inputCode.length != 6) {
                                    refreshMsg("匹配码是 6 位数字")
                                    return@TvChip
                                }
                                val code = inputCode
                                runAsync("匹配") {
                                    val name = Api.pairFetch(code)
                                    inputCode = ""
                                    "匹配成功：${name.ifBlank { "用户" }}，收藏/历史已同步"
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
 * 登录页：手机号 + 短信验证码。
 *
 * 每台设备都能用自己的手机号独立登录 —— 登录后本机就是**主账号**，
 * 可在账号页生成 6 位匹配码共享给其它端（TV / 平板 / 电脑）；
 * 反过来，这台设备也能用匹配码把别处的登录态接过来。
 *
 * 协议细节都在 [LoginApi] 里，这里只处理交互与状态。
 */
@Composable
fun LoginScreen(onDone: () -> Unit) {
    var mobile by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var wait by remember { mutableStateOf(0) }
    var mfa by remember { mutableStateOf<LoginApi.NeedMfa?>(null) }
    val scope = rememberCoroutineScope()

    // 重发倒计时（服务端 retry_time，一般 60 秒）
    LaunchedEffect(wait) {
        if (wait > 0) {
            delay(1000)
            wait -= 1
        }
    }

    fun sendCode() {
        if (busy || wait > 0) return
        if (!LoginApi.isMobileOk(mobile)) {
            msg = "请输入 11 位手机号"
            return
        }
        busy = true
        msg = "正在发送验证码…"
        scope.launch(Dispatchers.IO) {
            val r = runCatching { LoginApi.sendCode(mobile) }
            withContext(Dispatchers.Main) {
                busy = false
                r.fold(
                    {
                        wait = it.retryAfter
                        msg = it.message.ifBlank { "验证码已发送，请查看短信" }
                    },
                    { msg = it.message ?: "发送失败，请稍后重试" },
                )
            }
        }
    }

    fun submit() {
        if (busy) return
        if (!LoginApi.isMobileOk(mobile)) {
            msg = "请输入 11 位手机号"
            return
        }
        if (code.length < 4) {
            msg = "请输入短信里的验证码"
            return
        }
        busy = true
        msg = "正在登录…"
        scope.launch(Dispatchers.IO) {
            val r = runCatching { LoginApi.login(mobile, code) }
            withContext(Dispatchers.Main) {
                busy = false
                r.fold(
                    { acc ->
                        msg = "登录成功：${acc.name.ifBlank { "用户" }}"
                        mfa = null
                        onDone()
                    },
                    { e ->
                        // 风控要求「手机发一条短信」时才走 MFA，不是错误，单独提示
                        if (e is LoginApi.NeedMfa) {
                            mfa = e
                            msg = e.hint
                        } else {
                            msg = e.message ?: "登录失败，请重试"
                        }
                    },
                )
            }
        }
    }

    // MFA：每 3 秒问一次平台（最多 2 分钟），通过后 LoginApi 内部会重发登录换会话
    LaunchedEffect(mfa) {
        val cur = mfa ?: return@LaunchedEffect
        repeat(40) {
            delay(3000)
            val r = runCatching { withContext(Dispatchers.IO) { LoginApi.pollMfa(cur) } }
            if (r.getOrNull() == true) {
                msg = "安全验证通过，已登录"
                mfa = null
                onDone()
                return@LaunchedEffect
            }
            r.exceptionOrNull()?.message?.let { msg = it }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(title = "登录")
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 说明：为什么要单独登录、登录后能做什么
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Column {
                    Text("手机号登录", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "用手机号 + 短信验证码就能登录。每台设备各自登录自己的账号，" +
                            "登录后这台设备就是主账号，可以在「我的」生成匹配码给其它设备用。",
                        color = TextDim, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                }
            }

            // 手机号
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Column {
                    Text("手机号", color = TextDim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoginField(
                            value = mobile,
                            placeholder = "11 位手机号",
                            maxLen = 11,
                            modifier = Modifier.weight(1f),
                        ) { mobile = it.filter { ch -> ch.isDigit() } }
                        Spacer(Modifier.width(12.dp))
                        TvChip(
                            if (wait > 0) "${wait}s 后重发" else if (busy) "发送中…" else "获取验证码",
                            selected = wait == 0 && !busy,
                        ) { sendCode() }
                    }
                }
            }

            // 验证码 + 登录
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Column {
                    Text("验证码", color = TextDim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoginField(
                            value = code,
                            placeholder = "短信里的 4–6 位数字",
                            maxLen = 6,
                            modifier = Modifier.weight(1f),
                        ) { code = it.filter { ch -> ch.isDigit() } }
                        Spacer(Modifier.width(12.dp))
                        TvChip(if (busy) "登录中…" else "登录", selected = code.length >= 4 && !busy) {
                            submit()
                        }
                    }
                }
            }

            msg?.let {
                Text(it, color = if (mfa != null) Star else TextDim, fontSize = 13.sp)
            }

            // 需要上行短信验证时，把号码和内容直接摊开给用户
            mfa?.let { m ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Star.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .border(1.dp, Star.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Text("需要一次安全验证", color = Star, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "平台判断这次登录有风险，需要你用手里的手机给下面号码发一条短信，发送后这里会自动继续。",
                            color = TextDim, fontSize = 12.sp, lineHeight = 18.sp,
                        )
                        if (m.channel.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text("号码：${m.channel}", color = TextMain, fontSize = 13.sp)
                        }
                        if (m.content.isNotBlank()) {
                            Text("内容：${m.content}", color = TextMain, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("正在等待验证结果…", color = TextDim, fontSize = 12.sp)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TvChip("返回", selected = false) { onDone() }
                Spacer(Modifier.width(10.dp))
                Text("电视上用遥控器打不了字时，请用「我的」的「匹配码」把手机上的登录态接过来", color = TextDim, fontSize = 11.sp)
            }
        }
    }
}

/** 登录页输入框（数字键盘、无下划线，与账号页匹配码输入同一套外观）。 */
@Composable
private fun LoginField(
    value: String,
    placeholder: String,
    maxLen: Int,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(maxLen)) },
        textStyle = TextStyle(color = TextMain, fontSize = 20.sp, letterSpacing = 3.sp),
        cursorBrush = SolidColor(Red),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        ),
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PanelHi)
            .padding(horizontal = 14.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, color = Color(0x66FFFFFF), fontSize = 14.sp)
                }
                inner()
            }
        },
    )
}

/** 官网入口：二维码 + 域名（版本更新右侧；手机/平板扫码即可打开）。 */
@Composable
private fun OfficialSiteColumn() {
    val uri = LocalUriHandler.current
    val url = "https://kxg.fabao.xin/"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("官方网站", color = TextMain, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Image(
            painter = painterResource(R.drawable.official_qr),
            contentDescription = "官网二维码",
            modifier = Modifier
                .size(104.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(5.dp)
                .clickable { runCatching { uri.openUri(url) } },
        )
        Spacer(Modifier.height(8.dp))
        Text("kxg.fabao.xin", color = TextDim, fontSize = 12.sp)
        Text("扫码打开", color = TextDim, fontSize = 11.sp)
    }
}

@Composable
fun SettingsScreen() {
    val testScope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        PageHeader(title = "设置")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 版本更新：查外网/内网清单，有新版直接弹窗下载安装
            item {
                var checking by remember { mutableStateOf(false) }
                var info by remember { mutableStateOf<UpdateInfo?>(null) }
                var status by remember { mutableStateOf<String?>(null) }
                val upScope = rememberCoroutineScope()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text("版本更新", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "当前版本 ${Updater.versionName(Store.appContext)}（${Updater.versionCode(Store.appContext)}）",
                                color = TextDim,
                                fontSize = 13.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "更新走自建服务器，边缘节点加速下载",
                                color = TextDim,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TvChip(if (checking) "检查中…" else "检查更新", selected = false) {
                                    if (checking) return@TvChip
                                    checking = true
                                    status = null
                                    upScope.launch {
                                        // check() 在「全部更新源连不上」时会抛 CheckException：
                                        // 必须如实显示失败，不能当成「已是最新」（外网 2.2.73 谎报事故）
                                        val r = runCatching { Updater.check(Store.appContext) }
                                        checking = false
                                        r.getOrNull()?.let { info = it }
                                        if (r.exceptionOrNull() is Updater.CheckException) {
                                            status = "检查失败：网络连不上更新源，请重试"
                                        } else if (r.getOrNull() == null) {
                                            status = "已是最新版本"
                                        }
                                    }
                                }
                                status?.let {
                                    Spacer(Modifier.width(10.dp))
                                    Text(it, color = TextDim, fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.width(20.dp))
                        OfficialSiteColumn()
                    }
                }
                info?.let { UpdateDialog(it) { info = null } }
            }
            item {
                // 这几项存在 SharedPreferences 里，是普通属性不是 Compose State：
                // 只写 Store.xxx 不会触发重组，界面毫无变化（就是「点了没反应」）。
                // 本地 state 承接一下，点完立刻刷新；写盘照旧。
                var autoNextOn by remember { mutableStateOf(Store.autoNext) }
                var deleteAfterOn by remember { mutableStateOf(Store.deleteAfterFinish) }
                var softOn by remember { mutableStateOf(Store.softDecode) }
                var capHeight by remember { mutableStateOf(Store.maxHeight) }
                var defSpeed by remember { mutableStateOf(Store.speed) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Text("播放", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        // 整行都能点/能被遥控器选中。原来只有右边那个 46×26 的小开关带
                        // clickable，遥控器按上/下做的是几何搜索，会直接越过它落到下面的
                        // 「1080P」上（那一列在左边，比贴在最右边的小开关近得多），
                        // 结果电视上根本关不掉自动连播。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    autoNextOn = !autoNextOn
                                    Store.autoNext = autoNextOn
                                },
                        ) {
                            Text("播完自动下一集", color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Box(
                                Modifier
                                    .width(46.dp)
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (autoNextOn) Red else Color(0x33FFFFFF)),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Box(
                                    Modifier
                                        .padding(horizontal = 3.dp)
                                        .size(20.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Color.White),
                                )
                            }
                        }
                        Text("播放完当前集自动接着播下一集", color = TextDim, fontSize = 12.sp)
                        Spacer(Modifier.height(14.dp))
                        // 看完自动删除记录（与 PC 同名同义）：最后一集播完把这部剧从观看记录里移除
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    deleteAfterOn = !deleteAfterOn
                                    Store.deleteAfterFinish = deleteAfterOn
                                },
                        ) {
                            Text("看完自动删除记录", color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Box(
                                Modifier
                                    .width(46.dp)
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (deleteAfterOn) Red else Color(0x33FFFFFF)),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Box(
                                    Modifier
                                        .padding(horizontal = 3.dp)
                                        .size(20.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Color.White),
                                )
                            }
                        }
                        Text("最后一集播完，把这部剧从观看记录里移除", color = TextDim, fontSize = 12.sp)
                        Spacer(Modifier.height(14.dp))
                        // 软件解码：整行可点（同「自动连播」那条的教训——只点右边的小开关，
                        // 遥控器上下做几何搜索会直接越过它落到别的控件上）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    softOn = !softOn
                                    Store.softDecode = softOn
                                },
                        ) {
                            Text(
                                "软件解码（画面绿块/花屏时打开）",
                                color = TextMain,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                Modifier
                                    .width(46.dp)
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (softOn) Red else Color(0x33FFFFFF)),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Box(
                                    Modifier
                                        .padding(horizontal = 3.dp)
                                        .size(20.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Color.White),
                                )
                            }
                        }
                        Text(
                            "打开＝用软件解码，关闭＝用电视硬件解码。画面出现绿色方块时打开",
                            color = TextDim,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text("默认清晰度上限", color = TextMain, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1080 to "1080P", 720 to "720P", 540 to "540P").forEach { (h, label) ->
                                TvChip(label, selected = capHeight == h) {
                                    capHeight = h
                                    Store.maxHeight = h
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("默认倍速", color = TextMain, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { s ->
                                TvChip("${s}x", selected = defSpeed == s) {
                                    defSpeed = s
                                    Store.speed = s
                                }
                            }
                        }
                    }
                }
            }
            item {
                // 存储：安卓这边视频是边下边播、不落盘，真正占地方的是封面图缓存
                var cacheText by remember { mutableStateOf("计算中…") }
                var cleared by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    cacheText = withContext(Dispatchers.IO) {
                        Store.cacheSizeText(Store.appContext)
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Text("存储", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "缓存占用 $cacheText（主要是封面图）",
                            color = TextDim,
                            fontSize = 13.sp,
                        )
                        cleared?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = Red, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        TvChip("清除缓存") {
                            cleared = Store.clearCache(Store.appContext)
                            cacheText = Store.cacheSizeText(Store.appContext)
                        }
                    }
                }
            }
            item {
                // 原版同款：找剧筛选服务（代签）卡
                var signUrl by remember { mutableStateOf(Store.signUrl) }
                var signState by remember { mutableStateOf<String?>(null) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Text("找剧筛选服务（代签）", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "找剧筛选和设备配对走这个服务；同一局域网填服务器内网地址即可",
                            color = TextDim, fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(
                                value = signUrl,
                                onValueChange = { signUrl = it },
                                textStyle = TextStyle(color = TextMain, fontSize = 15.sp),
                                cursorBrush = SolidColor(Red),
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PanelHi)
                                    .padding(horizontal = 14.dp),
                                decorationBox = { inner ->
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                        inner()
                                    }
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            TvChip("测试连接", selected = false) {
                                Store.signUrl = signUrl
                                signState = "正在测试…"
                                // 原来 runBlocking 把主线程卡住 12~25 秒（遥控器完全无响应，可能 ANR）
                                testScope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching { Api.testSignService(signUrl) }.getOrDefault(false)
                                    }
                                    signState = if (ok) "连接正常" else "连不上，检查地址"
                                }
                            }
                        }
                        signState?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = if (it == "连接正常") Success else TextDim, fontSize = 12.sp)
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("清除缓存", color = TextMain, fontSize = 15.sp)
                        Text("视频分块缓存、首屏缓存与详情缓存", color = TextDim, fontSize = 12.sp)
                    }
                    TvChip("清除", selected = false) {
                        FeedCache.clear()
                        DetailCache.clear()
                    }
                }
            }
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Text("重建版 2.2.1-r1 · 从已发布包反编译还原", color = TextDim, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun CreatorScreen(
    userId: String,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    var profile by remember { mutableStateOf<CreatorProfileInfo?>(null) }
    val works = remember { mutableStateListOf<SeriesCard>() }
    var loading by remember { mutableStateOf(true) }
    // 接口异常（不是"没有作品"）：要区分开，别把失败显示成空列表
    var loadError by remember { mutableStateOf(false) }

    LaunchedEffect(userId) {
        loading = true
        val p = withContext(Dispatchers.IO) { runCatching { Api.creatorProfile(userId) }.getOrNull() }
        profile = p
        // 作品列表：按 cursor 翻页拉全（每页 18 部）。
        // 只拉第一页的话，出品方这种十几二十部的账号会少一大截。
        val all = mutableListOf<SeriesCard>()
        var cursor = ""
        var guard = 0
        var failed = false
        while (guard++ < 12) {
            val page = withContext(Dispatchers.IO) {
                runCatching { Api.creatorWorks(userId, cursor) }.getOrNull()
            }
            if (page == null) { failed = true; break }
            all.addAll(page.cards)
            if (!page.hasMore || page.cursor.isEmpty() || page.cursor == cursor) break
            cursor = page.cursor
        }
        var w: List<SeriesCard> = all
        // 演员不是创作者账号，「用户发帖」接口对他们永远是空的。
        // 平台没有「参演作品」接口，能拿到剧的路只剩按名字搜（tab_type=11）。
        // 注意：只有**真的没有数据**时才走这条兜底；接口报错要走报错态，
        // 不能悄悄给个搜索缩水结果（之前出品方只显示 4 部就是被这条吞掉的）。
        val name = p?.name.orEmpty()
        if (w.isEmpty() && !failed && name.isNotBlank() && !name.startsWith("用户")) {
            w = withContext(Dispatchers.IO) {
                runCatching { Api.searchPage(name, 0, "", "11").cards }
                    .getOrDefault(emptyList())
                    .filter { it.episodeCount > 0 }
            }
        }
        works.clear()
        works.addAll(w)
        loadError = failed && w.isEmpty()
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = profile?.name ?: "演职员",
            subtitle = profile?.desc,
            trailing = { TvChip("返回", selected = false) { onBack() } },
        )
        when {
            loading -> StatusBox(loading = true, error = null)
            loadError -> StatusBox(
                loading = false,
                error = "作品列表没加载出来（接口异常）",
            )
            works.isEmpty() -> StatusBox(
                loading = false, error = null, empty = true,
                emptyText = "暂无可播放的参演作品",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                gridItems(works, key = { it.seriesId + it.title }) { card ->
                    SeriesCardView(card, onClick = { onOpen(card.seriesId) })
                }
            }
        }
    }
}


/** 只取时:分（本地时区），给「数据 17:52 拉取」这类文案用。 */
private fun hmOf(ts: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

/**
 * 找剧筛选行归一化：**与 PC 端完全一致的内容与顺序**（用户要求两端一字一句对齐）。
 *
 * PC 的筛选行顺序是 体裁→主题→设定→背景→推荐→受众→时间→长度→状态
 * （见 PC `filter-panel.tsx` 的 ROW_FILTER_KEYS 顺序），而服务端面板下发
 * 的顺序是 体裁→主题→设定→背景→推荐→受众→状态→时间，且**不下发「长度」行**。
 * 这里统一按 PC 顺序重排，并补上长度兜底（option id 与 PC 的
 * withDurationFallback 完全相同，服务端 select_items.duration 必认）。
 */
private val PC_FILTER_ROW_ORDER = listOf(
    "genre", "category_dim_theme", "category_dim_role", "category_dim_epoch",
    "sort", "gender", "creation_status", "online_time",
)

private fun normalizeFilterRows(rows: MutableList<FilterRow>) {
    // 只做排序，不再补「长度」行：那三个 id（duration_0_60…）上游根本没有，
    // 选中必空，等于给用户埋了个「一点就 0 条」的假筛选项（PC 端没有这行）。
    val sorted = rows.sortedBy {
        val i = PC_FILTER_ROW_ORDER.indexOf(it.key)
        if (i < 0) PC_FILTER_ROW_ORDER.size else i
    }
    rows.clear()
    rows.addAll(sorted)
}
