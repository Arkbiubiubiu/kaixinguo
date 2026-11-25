package com.hongguotv.pad

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 刷剧候选池目标：主分类连续深翻到这么多部"不同"的剧为止（太小会天天在那几部里打转）。 */
private const val FEED_POOL_TARGET = 48
/** 主分类深翻页数上限：上游异常/翻不动时保底，别无限翻。 */
private const val FEED_POOL_MAX_PAGES = 8
/** 跨分类更多样：另外两个分类各额外补这么多部（合并去重后混在一起）。 */
private const val EXTRA_PER_CATEGORY = 16
/** 额外分类的翻页上限（16 部通常 1~2 页就够）。 */
private const val EXTRA_MAX_PAGES = 3

/**
 * 把某个分类从 [start]（为空则现拉首屏）往后深翻，边翻边塞进 [into]（按 seriesId 去重），
 * 直到 [into] 的 distinct 达到 [targetDistinct] 或翻满 [maxPages] 页。返回最后一页游标（供续翻）。
 * 全程容错：任一步失败就停，不抛异常。
 */
private fun fillFeedCategory(
    type: String,
    targetDistinct: Int,
    maxPages: Int,
    into: LinkedHashMap<String, SeriesCard>,
    start: FeedPage?,
): FeedPage? {
    var cur: FeedPage? = start ?: runCatching { Api.firstPage(type, refresh = true) }.getOrNull()
    cur?.cards?.forEach { into.putIfAbsent(it.seriesId, it) }
    var pages = 0
    while (into.size < targetDistinct && pages < maxPages) {
        val c = cur ?: break
        if (!c.hasMore || c.cellId.isEmpty()) break
        val next = runCatching {
            Api.feedNext(
                tabType = type,
                cellId = c.cellId,
                planId = c.planId,
                sessionId = c.sessionId,
                offset = c.nextOffset,
                seen = into.keys.toList(),
                refresh = true,
            )
        }.getOrNull() ?: break
        pages++
        next.cards.forEach { into.putIfAbsent(it.seriesId, it) }
        val advanced = next.nextOffset > c.nextOffset
        // 会话号/游标跟随最新一页（session 可能变，要用新的才能继续翻）
        cur = next.copy(
            sessionId = next.sessionId.ifEmpty { c.sessionId },
            cellId = next.cellId.ifEmpty { c.cellId },
            planId = next.planId.ifEmpty { c.planId },
        )
        // 游标没前进（翻不动了）就停，别用同一个 offset 空翻
        if (!advanced) break
    }
    return cur
}

/**
 * 建刷剧候选池：当前分类为主（深翻到 [FEED_POOL_TARGET]），另两个分类各补 [EXTRA_PER_CATEGORY] 部，
 * 合并按 seriesId 去重（LinkedHashMap），返回 (池, 主分类最后游标)。
 */
private fun buildFeedPool(
    primaryType: String,
    otherTypes: List<String>,
    first: FeedPage?,
): Pair<LinkedHashMap<String, SeriesCard>, FeedPage?> {
    val pool = LinkedHashMap<String, SeriesCard>()
    // 主分类：复用已经拿到的首屏，不重复拉
    val primaryLast = fillFeedCategory(primaryType, FEED_POOL_TARGET, FEED_POOL_MAX_PAGES, pool, first)
    // 另外两个分类各补一部分（这是"跨分类更丰富"的主要来源）
    otherTypes.forEach { t ->
        fillFeedCategory(t, pool.size + EXTRA_PER_CATEGORY, EXTRA_MAX_PAGES, pool, null)
    }
    return pool to primaryLast
}

/**
 * 刷剧（Pad / TV 共用）：
 *
 * 整屏连播一部短剧，换剧不换页——和 PC 的刷剧页同一套数据（`Api.firstPage/feedNext`，
 * tab 顺序同 PC：漫剧 / 真人剧 / 推荐，默认漫剧）。
 *
 * 交互按设备分：
 * - Pad：上下滑换剧、单击暂停；顶部三个分类可点。
 * - TV：遥控器 ↑/↓ 换剧、确定暂停、←/→ 快进退、返回退出；不显示分类（少一个焦点陷阱），
 *   控制台整体不出现（极简版），只在底部留一行标题/集数和一条细进度条。
 *
 * 换剧必须用 `key(seriesId)` 重建播放页：PlayerScreen 的集数状态是 remember(startIndex)
 * 存的，同一位置复用会让下一部剧继承上一部的集号（PC 上踩过「每部都从第 2 集开始」）。
 */
@Composable
fun FeedScreen(
    onOpenDetail: (String) -> Unit,
    onExit: () -> Unit,
    onOpenFull: (String) -> Unit = {},
) {
    // 三端默认漫剧；与 PC 一致：漫剧 / 真人剧 / 推荐。
    // 上游真实 tab：36=漫剧、39=真人剧、16=推荐；3/4/2 是已失效的旧 id。
    val feedTypes = listOf("36", "39", "16")
    val isTv = Store.appContext.packageName.endsWith("nativeapp")
    var tab by remember { mutableStateOf(UiMemo.feedTab) }
    var cards by remember { mutableStateOf(UiMemo.feedCards) }
    var cursor by remember { mutableStateOf(UiMemo.feedCursor) }
    var page by remember { mutableStateOf(UiMemo.feedPage) }
    // 首次进入先显示 loading，绝不先画旧列表。
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var moreLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // TV：进刷剧先弹「分类选择」（方向键已被换剧/进详情占用，没有别的切换入口）。
    // 从详情/播放返回同一次会话不弹（保留现场）。
    var tvShowPicker by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (isTv) {
            val returning = UiMemo.feedReturnKeep && UiMemo.feedCards.isNotEmpty()
            if (!returning) tvShowPicker = true
        }
    }

    LaunchedEffect(tab, reload, tvShowPicker) {
        // TV 还没选分类前不加载，等用户选完再拉，避免先播默认漫剧再跳。
        if (isTv && tvShowPicker) return@LaunchedEffect
        // 从详情/播放返回同一次刷剧会话：保留当前剧与位置（只此一种例外）。
        val returning = UiMemo.feedReturnKeep &&
            cards.isNotEmpty() && tab == UiMemo.feedTab
        UiMemo.feedReturnKeep = false
        if (returning) {
            loading = false
            return@LaunchedEffect
        }
        // 用户要求：每次进入刷剧都必须现拉，不显示旧列表。
        loading = true
        cards = emptyList()
        cursor = 0
        page = null
        FeedPreload.clear()
        val cacheKey = "feed_${feedTypes[tab]}"
        val type = feedTypes[tab]

        if (type == "16") {
        // ---- ① 首屏秒开：只拉第一页，跨天去重后立即开播；不再等深翻凑池 ----
        val first = withContext(Dispatchers.IO) {
            runCatching { Api.firstPage(type, refresh = true) }.getOrNull()
        }
        val rawFirst = first?.cards ?: emptyList()
        // 不再跨会话/跨天按 feedSeen 剔除（会持久导致"刷到过就永不出现"）；首屏直接打乱显示，
        // 同一趟列表内靠 putIfAbsent/known 去重即可。
        val firstDisplay = rawFirst.shuffled()
        if (firstDisplay.isNotEmpty()) {
            cards = firstDisplay
            page = first
            cursor = 0
            withContext(Dispatchers.IO) { runCatching { FeedCache.save(cacheKey, firstDisplay) } }
        } else {
            cards = emptyList()
            page = null
        }
        loading = false // 立即开播，深翻放后台

        // ---- ② 后台继续深翻补池，完成后 append 到尾部 ----
        // 不打断当前播放：不清空、不重排、不动 cursor，只往 cards 尾部加"没出现过的、优先没见过"的剧。
        // BUG①修复：只有「推荐(16)」才跨分类补；具体分类(36漫剧 / 39真人剧)只深翻本类，避免类型串台。
        val extraTypes = if (type == "16") feedTypes.filter { it != type } else emptyList()
        if (first != null) {
            launch {
                val built = withContext(Dispatchers.IO) {
                    buildFeedPool(type, extraTypes, first)
                }
                val pool = built.first
                val primaryLast = built.second
                val shownIds = cards.map { it.seriesId }.toSet()
                // 同一趟列表内去重（不落盘、不跨会话）：只排除已经出现在 cards 里的
                val poolValues = pool.values
                    .filter { it.seriesId !in shownIds }
                    .shuffled()
                val shownNow = cards.map { it.seriesId }.toSet()
                val appended = poolValues.filter { it.seriesId !in shownNow }
                if (appended.isNotEmpty()) {
                    cards = cards + appended // append 到尾部
                    if (primaryLast != null) page = primaryLast // 更新 step 尾部续翻游标
                    withContext(Dispatchers.IO) { runCatching { FeedCache.save(cacheKey, cards) } }
                }
            }
        }
        } else {
            // ---- 36/39：热播榜 + 新剧榜 候选，剔除「本机真看过(positionMs>=3s)」；不足 12 用 feed 池补足 48 ----
            val excluded = withContext(Dispatchers.IO) { runCatching { buildExcludeIds() }.getOrDefault(emptySet()) }
            val rank = withContext(Dispatchers.IO) { runCatching { buildRankCandidates(type) }.getOrDefault(emptyList()) }
            val picked = ArrayList<SeriesCard>()
            val have = HashSet<String>()
            for (c in rank) {
                if (c.seriesId in excluded || !have.add(c.seriesId)) continue
                picked.add(c)
            }
            var feedLast: FeedPage? = null
            if (picked.size < 12) {
                val built = withContext(Dispatchers.IO) {
                    runCatching { buildFeedPool(type, emptyList(), null) }.getOrNull()
                }
                if (built != null) {
                    feedLast = built.second
                    for (c in built.first.values) {
                        if (picked.size >= 48) break
                        if (c.seriesId in excluded || !have.add(c.seriesId)) continue
                        picked.add(c)
                    }
                }
            }
            if (picked.isNotEmpty()) {
                cards = picked
                page = feedLast // rank 池无游标：走 feed 兜底续翻（见 step 的 page==null 分支）
                cursor = 0
                withContext(Dispatchers.IO) { runCatching { FeedCache.save(cacheKey, picked) } }
            } else {
                cards = emptyList()
                page = null
            }
            loading = false
        }
    }

    // 预载后面若干部（FeedPreload.AHEAD=8）：换剧时直接用准备好的解密代理，起播几乎无感
    LaunchedEffect(cursor, cards) {
        if (cards.isNotEmpty()) FeedPreload.request(cards, cursor + 1)
    }

    // 位置随变随记：进详情时这一页会离开组合，这些状态只能存在单例里
    LaunchedEffect(tab, cursor, cards, page) {
        UiMemo.feedTab = tab
        UiMemo.feedCursor = cursor
        UiMemo.feedCards = cards
        UiMemo.feedPage = page
    }

    /** 换剧：-1 上一部 / +1 下一部；走到列表尾自动续一页（rank 池无游标时走 feed 兜底，别崩）。 */
    fun step(dir: Int) {
        val next = cursor + dir
        if (next < 0) return
        if (next >= cards.size) {
            if (moreLoading) return
            val c = page
            if (c == null) {
                // rank 池没有游标：拉一页 feed 兜底续上，别卡死/崩
                moreLoading = true
                scope.launch {
                    val excluded = withContext(Dispatchers.IO) {
                        runCatching { buildExcludeIds() }.getOrDefault(emptySet())
                    }
                    val fb = withContext(Dispatchers.IO) {
                        runCatching { Api.firstPage(feedTypes[tab], refresh = true) }.getOrNull()
                    }
                    if (fb != null && fb.cards.isNotEmpty()) {
                        val known = cards.map { it.seriesId }.toSet()
                        // 同一套剔除：只屏蔽本机真看过的，其余都保留
                        val add = fb.cards.filter { it.seriesId !in known && it.seriesId !in excluded }
                        page = fb
                        if (add.isNotEmpty()) {
                            cards = cards + add
                            cursor = next.coerceAtMost(cards.lastIndex)
                        }
                    }
                    moreLoading = false
                }
                return
            }
            if (!c.hasMore) return
            moreLoading = true
            scope.launch {
                val np = withContext(Dispatchers.IO) {
                    runCatching {
                        Api.feedNext(
                            feedTypes[tab], c.cellId, c.planId, c.sessionId, c.nextOffset,
                            cards.map { it.seriesId }, refresh = true,
                        )
                    }.getOrNull()
                }
                if (np != null && np.cards.isNotEmpty()) {
                    val known = cards.map { it.seriesId }.toSet()
                    cards = cards + np.cards.filter { it.seriesId !in known }
                    page = np
                    cursor = next.coerceAtMost(cards.lastIndex)
                } else {
                    page = c.copy(hasMore = false)
                }
                moreLoading = false
            }
            return
        }
        cursor = next
    }

    BackHandler { UiMemo.feedReturnKeep = false; onExit() }

    val current = cards.getOrNull(cursor)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            // 统一用 LoadingSpinner（withFrameNanos 自转），不要 material3 的圈：
            // 电视普遍把「动画时长缩放」关成 0，material3 的无限动画在这种情况下
            // 完全不派帧，圈会冻在第一帧，看着就是一个红点/一个卡住的圈。
            loading -> Box(Modifier.align(Alignment.Center)) {
                LoadingBlock("正在加载…")
            }
            current == null -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "没有拿到推荐，检查网络后重试",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "重试",
                        color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCCFF4A3D))
                            .clickable { reload++ }.padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                    Text(
                        "退出",
                        color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x66000000))
                            .clickable { UiMemo.feedReturnKeep = false; onExit() }.padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
            else -> key(current.seriesId) {
                PlayerScreen(
                    seriesId = current.seriesId,
                    startIndex = 1,
                    onBack = { UiMemo.feedReturnKeep = false; onExit() },
                    // 刷剧模式不切集（↑↓ 已被换剧占用）；连播仍由播放器内部接管
                    onSwitch = { _, _ -> },
                    onDetail = { UiMemo.feedReturnKeep = true; onOpenDetail(current.seriesId) },
                    feedMode = true,
                    feedLabel = "第 ${cursor + 1} 部/共 ${cards.size} 部",
                    onFeedStep = { dir -> step(dir) },
                    onFeedFull = {
                        UiMemo.feedReturnKeep = true
                        onOpenFull(current.seriesId)
                    },
                )
            }
        }

        // Pad 刷剧：顶部常驻「详情 / 退出」（TV 不必，遥控器有返回键）
        if (!isTv) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (current != null) {
                    Text(
                        "详情", color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x66000000))
                            .clickable { UiMemo.feedReturnKeep = true; onOpenDetail(current.seriesId) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                Text(
                    "退出", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x66000000))
                        .clickable { UiMemo.feedReturnKeep = false; onExit() }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }

        // 分类只在触屏端显示；TV 上多一排可点项只会多一个焦点陷阱
        if (!isTv) {
            Row(
                Modifier.align(Alignment.TopStart).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("漫剧", "真人剧", "推荐").forEachIndexed { i, label ->
                    val on = tab == i
                    Text(
                        label,
                        color = if (on) Color.White else Color.White.copy(alpha = 0.62f),
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) Color(0xCCFF4A3D) else Color(0x66000000))
                            .clickable { UiMemo.feedReturnKeep = false; tab = i }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }

        // TV：进刷剧先弹分类选择（遥控器 ←→ 选、确定进、返回退出）
        if (isTv && tvShowPicker) {
            Box(
                Modifier.fillMaxSize().background(Color(0xF0121216)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text("刷剧 · 选择分类", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        FeedPickButton("漫剧", true) { tvShowPicker = false; UiMemo.feedReturnKeep = false; tab = 0 }
                        FeedPickButton("真人剧", false) { tvShowPicker = false; UiMemo.feedReturnKeep = false; tab = 1 }
                        FeedPickButton("推荐", false) { tvShowPicker = false; UiMemo.feedReturnKeep = false; tab = 2 }
                    }
                    Text("遥控器：← → 选择 · 确定 进入 · 返回 退出", color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
                }
            }
        }
    }
}

/** TV 刷剧分类选择按钮：遥控器 ←→ 移焦点、确定触发；也留 clickable 兼容触屏/鼠标。 */
@Composable
private fun FeedPickButton(label: String, initialFocus: Boolean, onSelect: () -> Unit) {
    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        color = if (focused) Color.White else Color.White.copy(alpha = 0.82f),
        fontSize = 18.sp,
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .focusRequester(fr)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp &&
                    (e.key == Key.Enter || e.key == Key.DirectionCenter || e.key == Key.NumPadEnter)
                ) { onSelect(); true } else false
            }
            .focusable()
            .clip(RoundedCornerShape(50))
            .background(if (focused) Color(0xFFFF4A3D) else Color(0x33FFFFFF))
            .clickable { onSelect() }
            .padding(horizontal = 36.dp, vertical = 16.dp),
    )
    LaunchedEffect(Unit) { if (initialFocus) runCatching { fr.requestFocus() } }
}

// ---------------- 刷剧「热播榜 + 新剧榜」候选（36 漫剧 / 39 真人剧） ----------------

/** 取一个榜单子榜的 ≤pages 页，按 seriesId 去重合并。 */
private fun fetchRankSub(genre: String, sub: String, pages: Int = 2): List<SeriesCard> {
    val out = LinkedHashMap<String, SeriesCard>()
    var offset = 0
    var session = ""
    var hasMore = true
    var p = 0
    while (p < pages && hasMore) {
        val pg = runCatching { Api.rank(offset, session, genre, sub) }.getOrNull() ?: break
        pg.cards.forEach { out.putIfAbsent(it.seriesId, it) }
        val advanced = pg.nextOffset > offset
        offset = pg.nextOffset
        session = pg.sessionId.ifEmpty { session }
        hasMore = pg.hasMore && advanced
        p++
    }
    return out.values.toList()
}

/** 36 漫剧：热播榜+新剧榜；39 真人剧：热播榜+新剧榜。热播在前、新剧在后，按 seriesId 去重。 */
private fun buildRankCandidates(type: String): List<SeriesCard> {
    val genre = if (type == "36") "comic_series_rank" else "human"
    val hot = if (type == "36") "comic_series_hot_play" else "human_hot_play"
    val fresh = if (type == "36") "comic_series_new_rank" else "human_new_rank"
    val out = LinkedHashMap<String, SeriesCard>()
    fetchRankSub(genre, hot, 2).forEach { out.putIfAbsent(it.seriesId, it) }   // 热播在前
    fetchRankSub(genre, fresh, 2).forEach { out.putIfAbsent(it.seriesId, it) } // 新剧在后
    return out.values.toList()
}

/** 剔除集合：**只取本机真看过的**（Store.history 里 positionMs>=3000ms 的 seriesId）；
 *  仅点开0秒 / 仅刷到（无真实播放位置）不算。不再为剔除去拉官方历史/预约，也不跨会话记 feedSeen。 */
private fun buildExcludeIds(): Set<String> {
    val ids = HashSet<String>()
    runCatching {
        Store.history().forEach { r ->
            if (r.optLong("positionMs", 0L) >= 3_000L) {
                r.optString("seriesId").takeIf { it.isNotBlank() }?.let { ids.add(it) }
            }
        }
    }
    return ids
}
