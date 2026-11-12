package com.hongguotv.pad

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 详情页（按原版 2.2.1 布局还原）：
 * 顶部「← 标题」→ 头部（封面 + 元信息行 + 标签 + 按钮行）→ 备案号 →
 * 简介（两行折叠 + 展开）→ 演职员圆头像 → 「选集 N / 剧评 / 相关推荐 N」三 tab。
 */
@Composable
fun DetailScreen(
    seriesId: String,
    onBack: () -> Unit,
    onPlay: (String, Int) -> Unit,
    onCreator: (String) -> Unit,
    onOpen: (String) -> Unit = {},
) {
    // 全部按 seriesId 记住：详情页「相关推荐 → 另一部剧」是同一个 when 分支、
    // 同一个调用点，Compose 会复用组合槽。不加 key 的话收藏/预约/追更/当前 tab/
    // 简介展开这些状态会从上一部剧原样带过来（看着像新剧已收藏、剧评 tab 空的）。
    var detail by remember(seriesId) { mutableStateOf<SeriesDetail?>(null) }
    var error by remember(seriesId) { mutableStateOf<String?>(null) }
    var loading by remember(seriesId) { mutableStateOf(true) }
    var favorite by remember(seriesId) { mutableStateOf(Store.contains(Store.KEY_FAVORITE, seriesId)) }
    // 云端「已预约」优先：换设备后本地没有记录，但服务端有
    var reserved by remember(seriesId) { mutableStateOf(Store.contains(Store.KEY_RESERVE, seriesId)) }
    var followed by remember(seriesId) { mutableStateOf(Store.contains(Store.KEY_FOLLOW, seriesId)) }
    var relatedGroups by remember(seriesId) { mutableStateOf<List<RelatedGroup>>(emptyList()) }
    /** 同一部剧的全部季（plan 接口）；相关推荐会漏季，这里单独补一份完整的 */
    var seasons by remember(seriesId) { mutableStateOf<List<SeasonRef>>(emptyList()) }
    // 服务端已预约的 series_id：相关推荐里每张待上线卡的「预约 / 已预约」靠它
    var reservedIds by remember(seriesId) { mutableStateOf<Set<String>>(emptySet()) }
    var reviews by remember(seriesId) { mutableStateOf<List<ReviewItem>>(emptyList()) }
    /** 评分/人数（剧评接口 extra，与 PC 同源） */
    var reviewScore by remember(seriesId) { mutableStateOf("") }
    var reviewScoreCount by remember(seriesId) { mutableStateOf(0) }
    /** 剧评总数（PC 的 tab 上会显示「剧评 2109」） */
    var reviewTotal by remember(seriesId) { mutableStateOf(0) }
    var tab by remember(seriesId) { mutableStateOf(0) }
    var introExpanded by remember(seriesId) { mutableStateOf(false) }
    // 续播集数：初始取本地，进页后再用官方历史刷新一次（主号在手机上看过也能续上）
    var resumeIdx by remember(seriesId) { mutableStateOf(0) }
    var actionMsg by remember(seriesId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    /**
     * 相关推荐卡片上的预约开关。
     *
     * 乐观更新：先改本地 UI，再打服务端；服务端不认就整体回滚并给提示
     * （未登录时只存本地，提示去登录）。
     */
    fun toggleReserveOnCard(card: SeriesCard) {
        val on = !reservedIds.contains(card.seriesId)
        reservedIds = if (on) reservedIds + card.seriesId else reservedIds - card.seriesId
        Store.toggleCard(Store.KEY_RESERVE, card)
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { Api.reserveToggle(card.seriesId, on) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (!ok) {
                    if (Store.loggedIn) {
                        // 登录态下写失败 = 真的没写上，整体回滚
                        reservedIds = if (on) reservedIds - card.seriesId else reservedIds + card.seriesId
                        Store.toggleCard(Store.KEY_RESERVE, card)
                        actionMsg = "预约没同步成功，检查网络后重试"
                    } else {
                        // 未登录：保留本地记录（离线也能标记），只是不会同步到账号
                        actionMsg = "未登录，预约只保存在本机，登录后会同步"
                    }
                } else {
                    actionMsg = if (on) "已预约，上线会在「预约」里提醒你" else "已取消预约"
                }
            }
        }
    }

    // TV：进详情页自动把焦点放到「播放」上。否则遥控器一进来没有落点，
    // 第一下按方向键会落到「展开」这种次要控件上，用户以为界面卡了。
    //
    // 必须等 loading 结束再请求：详情还在加载时播放按钮根本没组合，
    // 这时 requestFocus 会抛「FocusRequester is not initialized」，
    // 被 runCatching 吞掉，表现就是「进页面没有焦点」（第一次就踩了这个）。
    val playFocus = remember { FocusRequester() }
    // 本次进入页面还没补过焦点。**只有补成功了才清**，不是尝试一次就清。
    var needAutoFocus by remember(seriesId) { mutableStateOf(true) }
    // 本页（含所有子控件）此刻是否已经有焦点。控制条、选集按钮、侧栏之外的地方
    // 拿到焦点都算；只有「完全没有落点」时才去补。
    var hasAnyFocus by remember(seriesId) { mutableStateOf(false) }
    val anyFocusNow by rememberUpdatedState(hasAnyFocus)
    val isTv = Store.appContext.packageName.endsWith("nativeapp")
    // 进页面的焦点落点：**等主按钮真正上屏那一刻**再给，不是靠 delay 猜。
    //
    // 两条历史教训都在这里：
    // ① 原来挂在 `LaunchedEffect(loading, detail)` 上等 loading 变 false。可页面
    //    只要 detail 到手就画出来了，loading 还要等收藏/预约那几个请求 —— 用户已经
    //    在看页面、已经在按方向键了，它才把手伸过来把焦点抢回「继续看第 N 集」，
    //    正是反馈的「前一两秒正常，之后被拉回第一项」。
    // ② 改成 delay(80/240) 猜时机也不行：节点没 attach 时 requestFocus 直接抛
    //    「not initialized」被吞掉，页面就没焦点，第一下方向键落到返回箭头上。
    // ③ 只靠 onPlaced 一次仍然不够：那一刻旧页面的焦点可能还没释放（anyFocusNow
    //    还是 true），跳过一次以后就永远不再补 —— TV 进详情页无落点，第一下方向键
    //    落到左上角返回箭头。所以改成「成功才清标记」+ 首帧后短轮询补焦点。
    // onPlaced 是布局完成回调，那一刻节点一定在位，请求一定成功；轮询只是兜底。
    val autoFocusPlay: () -> Unit = {
        if (isTv && needAutoFocus && !anyFocusNow) {
            if (runCatching { playFocus.requestFocus() }.isSuccess) needAutoFocus = false
        }
    }
    // 兜底轮询：进页面后旧焦点释放有一次延迟，onPlaced 那一下可能被挡掉。
    // 每 120ms 试一次，最多 2.4s；本页任何控件拿到焦点、或用户已经开始操作，就停手。
    LaunchedEffect(seriesId) {
        if (!isTv) return@LaunchedEffect
        repeat(20) {
            if (!needAutoFocus || anyFocusNow) return@LaunchedEffect
            autoFocusPlay()
            if (!needAutoFocus || anyFocusNow) return@LaunchedEffect
            kotlinx.coroutines.delay(120)
        }
    }

    LaunchedEffect(seriesId) {
        loading = true
        val d = withContext(Dispatchers.IO) { runCatching { Api.ensureDetail(seriesId) }.getOrNull() }
        detail = d

        // ---- 先把页面画出来 ----
        // 这里只用本地数据。原来底下这一串全是串行 await：收藏列表、预约列表、
        // 云端历史、相关推荐、季清单、剧评……实测进详情页要转 2 秒圈，
        // 而「详情已经到了却还不显示」正是焦点被抢那件事的土壤。
        resumeIdx = Store.progress(seriesId)
        d?.let { if (it.onlineSubscribed && !reserved) reserved = true }
        error = if (d == null) "详情加载失败，返回后再进来重试" else null
        loading = false

        // ---- 以下是页面已经显示之后才补的数据，全部后台跑 ----

        // 云端状态优先：换设备/重装后本地是空的，但账号里其实已经收藏/预约/追更过
        if (d != null && Store.loggedIn) {
            launch {
                val sid = d.seriesId
                val favIds = withContext(Dispatchers.IO) { runCatching { Api.shelfIds() }.getOrDefault(emptySet()) }
                if (sid in favIds && !Store.contains(Store.KEY_FAVORITE, sid)) {
                    favorite = true
                    Store.toggleCard(Store.KEY_FAVORITE, cardOf(d))
                }
                val resIds = withContext(Dispatchers.IO) { runCatching { Api.reserveIds() }.getOrDefault(emptySet()) }
                if (sid in resIds && !Store.contains(Store.KEY_RESERVE, sid)) {
                    reserved = true
                    Store.toggleCard(Store.KEY_RESERVE, cardOf(d))
                }
                if (d.followed && !followed) {
                    followed = true
                    Store.toggleCard(Store.KEY_FOLLOW, cardOf(d))
                }
                if (d.hasDigg && !Store.contains(Store.KEY_LIKE, seriesId)) {
                    Store.toggleCard(Store.KEY_LIKE, cardOf(d))
                }
            }
            // 跨端续播：拉一页官方最近历史，主号在别的端看到第几集这边也能续上。
            // 只往「更靠后」的方向修，别把用户已经翻到的更远位置拉回来。
            launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val remote = Api.officialHistory(1)
                        if (remote.isNotEmpty()) Store.mergeHistory(remote)
                    }
                }
                val merged = Store.progress(seriesId)
                if (merged > resumeIdx) resumeIdx = merged
            }
        }
        launch {
            relatedGroups = withContext(Dispatchers.IO) {
                runCatching { Api.relatedSeries(seriesId) }.getOrDefault(emptyList())
            }
        }
        // 上游的相关推荐是推荐位，会漏季（《全球躲猫猫》五季只给了 1/3/4/5/6），
        // 季清单单独问 plan 接口，保证每一季都点得到
        launch {
            seasons = withContext(Dispatchers.IO) {
                val own = runCatching { Api.seasons(seriesId) }.getOrDefault(emptyList())
                if (own.isEmpty() || d == null) {
                    own
                } else {
                    // 有些剧在平台上是两套独立合集，各自只收录一部分季（第一套有 1/3/4/5/6，
                    // 第二季只存在于另一套）。缺号就按「主创名字有交集」去另一套里补回来。
                    val names = d.creators.map { it.name }.filter { it.isNotBlank() }.toSet()
                    val extra = runCatching {
                        Api.fillMissingSeasons(seriesId, d.title, own, names)
                    }.getOrDefault(emptyList())
                    (own + extra).sortedBy { it.index }
                }
            }
        }
        // 预约态以服务端为准（换设备也一致）；未登录直接空集
        launch {
            val ids = withContext(Dispatchers.IO) {
                runCatching { Api.reservedIds() }.getOrDefault(emptySet())
            }
            reservedIds = ids
            if (ids.contains(seriesId)) reserved = true
        }
        launch {
            reviews = withContext(Dispatchers.IO) {
                runCatching { Api.reviews(seriesId, 10) }.getOrNull().also { rp -> reviewScore = rp?.score.orEmpty(); reviewScoreCount = rp?.scoreCount ?: 0; reviewTotal = rp?.total ?: 0 }?.items.orEmpty()
            }
        }
    }

    // 详情拿不到（未上线的季会直接 101000）就用列表里暂存的卡片兜底，
    // 这样「点进去看不了」变成「能看到待上线 + 预约」
    val d = detail ?: Api.stashedCard(seriesId)?.let { c ->
        SeriesDetail(
            seriesId = c.seriesId,
            title = c.title,
            cover = c.cover,
            episodeCount = c.episodeCount,
            tags = c.tags,
            contentType = c.contentType,
            intro = c.desc.orEmpty(),
        )
    }
    if (d == null) {
        Box(Modifier.fillMaxSize()) { StatusBox(loading = loading, error = error) }
        return
    }
    if (loading && detail == null) {
        Box(Modifier.fillMaxSize()) { StatusBox(loading = true, error = null) }
        return
    }

    val episodes = d.episodes
    val resumeIndex = (if (resumeIdx > 0) resumeIdx else Store.progress(seriesId))
        .coerceIn(1, episodes.size.coerceAtLeast(1))
    val metaLine = listOfNotNull(
        // 评分优先用剧评接口的（PC 同源）；detail 的 score 只作兜底
        (reviewScore.ifEmpty { d.score }).takeIf { it.isNotBlank() && it != "0" }?.let { s ->
            if (reviewScoreCount > 0) "${s}分 ${formatCount(reviewScoreCount.toString())}人评分" else "${s}分"
        },
        d.hotScore.takeIf { it.isNotBlank() }?.let { "热度值${formatCount(it)}" },
        d.playCount.takeIf { it.isNotBlank() }?.let { "${formatCount(it)}播放" },
        d.followedCount.takeIf { it.isNotBlank() }?.let { "${formatCount(it)}人追" },
        d.diggCount.takeIf { it.isNotBlank() && it != "0" }?.let { "${formatCount(it)}赞" },
        "共${if (d.episodeCount > 0) d.episodeCount else episodes.size}集",
    ).joinToString(" · ")

    Box(
        Modifier
            .fillMaxSize()
            // **必须裁切**：沉浸背景的封面用了 scale(1.25f) 放大铺底，
            // 不裁切会溢出内容区、盖到左侧导航栏上（实测：详情页侧栏
            // 「开心果」和全部导航图标被一层模糊图蒙住看不见）
            .clipToBounds()
            .onFocusChanged { hasAnyFocus = it.hasFocus },
    ) {
        // 沉浸式封面背景（与 PC 详情页 1:1 同款配方）：封面放大 1.25 +
        // 32dp 高斯模糊铺满整页，卡面色 63.5% 遮罩向下融入页面底色。
        // 原来 TV 详情是纯黑背景，与 PC 的「封面色彩弥漫页头」差异明显。
        if (d.cover.isNotEmpty()) {
            AsyncImage(
                model = d.cover,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .scale(1.25f)
                    .blur(32.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Panel.copy(alpha = 0.72f),
                        0.35f to Panel.copy(alpha = 0.72f),
                        1f to Bg,
                    ),
                ),
        )
    Column(Modifier.fillMaxSize()) {
        // ---------- 顶栏：← 返回（**PC 同款独立一行，无标题**）----------
        // PC 的标题在封面右侧的信息区里，顶栏只有「← 返回」。
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "←",
                color = TextMain,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "返回",
                color = TextDim,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(vertical = 2.dp),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            // ---------- 头部：封面 + 元信息 + 按钮 ----------
            Row {
                Box(
                    Modifier
                        .width(108.dp)
                        .aspectRatio(0.72f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CoverPlaceholder),
                ) {
                    AsyncImage(
                        model = d.cover,
                        contentDescription = d.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // 标题（PC 同款：在封面右侧信息区最上方，大字号）
                    Text(
                        d.title,
                        color = TextMain,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    // stats 行（**PC 同款结构**）：评分大数字 + N人评分 →
                    // 火焰图标 + 红果热度值X万 → X人追剧 → 全X集。
                    // 原来是一整串「9.2分 4万人评分 · 热度值6354万 · …」纯文字，
                    // PC 是分段带图标、数字用「万」单位的。
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val scoreText = reviewScore.ifEmpty { d.score }
                        if (scoreText.isNotBlank() && scoreText != "0") {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Text(
                                    scoreText,
                                    color = Star,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (reviewScoreCount > 0) {
                                    Text(
                                        "${formatCount(reviewScoreCount.toString())}人评分",
                                        color = TextDim,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                        if (d.hotScore.isNotBlank()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FlameIcon(12.dp)
                                Text(
                                    "红果热度值${formatCountPrecise(d.hotScore)}",
                                    color = Warm,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        if (d.followedCount.isNotBlank()) {
                            Text(
                                "${formatCountPrecise(d.followedCount)}人追剧",
                                color = TextDim,
                                fontSize = 12.sp,
                            )
                        }
                        Text(
                            "全${if (d.episodeCount > 0) d.episodeCount else episodes.size}集",
                            color = TextDim,
                            fontSize = 12.sp,
                        )
                        // 播放次数（PC 同款 stats 行末位：「1129万次播放」）
                        if (d.playCount.isNotBlank()) {
                            Text(
                                "${formatCountPrecise(d.playCount)}次播放",
                                color = TextDim,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 徽标行（PC 同款）：「全544集」是有背景的胶囊，题材标签跟在后面
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(PanelHi)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                "全${if (d.episodeCount > 0) d.episodeCount else episodes.size}集",
                                color = TextMain,
                                fontSize = 11.sp,
                            )
                        }
                        d.tags.take(3).forEach { tag ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(PanelHi)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(tag, color = TextDim, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // 简介（PC 同款位置：徽标行下方、出品方上方）
                    if (d.intro.isNotBlank()) {
                        Text(
                            d.intro,
                            color = TextDim,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            maxLines = if (introExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (introExpanded) "收起" else "展开",
                            color = Red,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clickable { introExpanded = !introExpanded }
                                .padding(top = 3.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    // 出品方（PC 同款：**横排小卡**——圆形小头像 + 名字 + 「出品方」，
                    // 原来 TV 是竖排大卡（56dp 头像占很大空间），与 PC 差异明显）
                    if (d.creators.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            d.creators.take(4).forEach { c ->
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Panel)
                                        .clickable { onCreator(c.id) }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(AvatarPlaceholder),
                                    ) {
                                        AsyncImage(
                                            model = c.avatar,
                                            contentDescription = c.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    Column {
                                        Text(
                                            c.name,
                                            color = TextMain,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                        )
                                        Text(
                                            if (c.role.isNotBlank()) c.role else "出品方",
                                            color = TextDim,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    /**
                     * 未上线（episode_cnt=0 / 没有分集）的剧：主按钮就是「预约」，
                     * 播放键不给（点了也是空播放器）。上线后再回来这里会自动变成播放。
                     */
                    if (d.upcoming) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier
                                    .height(40.dp)
                                    .focusRequester(playFocus)
                                    .onPlaced { autoFocusPlay() }
                                    .clickable {
                                        val next = !reserved
                                        reserved = next
                                        Store.toggleCard(Store.KEY_RESERVE, cardOf(d))
                                        scope.launch(Dispatchers.IO) {
                                            val ok = runCatching { Api.reserveToggle(seriesId, next) }
                                                .getOrDefault(false)
                                            if (!ok) withContext(Dispatchers.Main) {
                                                reserved = !next
                                                Store.toggleCard(Store.KEY_RESERVE, cardOf(d))
                                                actionMsg = if (Store.loggedIn) {
                                                    "预约没同步成功，检查网络后重试"
                                                } else {
                                                    "未登录，预约只保存在本机"
                                                }
                                            } else if (next) {
                                                withContext(Dispatchers.Main) {
                                                    actionMsg = "已预约，上线会在「预约」里提醒你"
                                                }
                                            }
                                        }
                                    }
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (reserved) PanelHi else Red)
                                    .padding(horizontal = 18.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (reserved) "已预约 · 上线提醒" else "预约上线提醒",
                                    color = if (reserved) TextDim else Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            MetaChip(if (favorite) "★ 已收藏" else "☆ 收藏", active = favorite, activeColor = Star) {
                                val next = !favorite
                                favorite = next
                                Store.toggleCard(Store.KEY_FAVORITE, cardOf(d))
                                scope.launch(Dispatchers.IO) {
                                    // 和下面的正式分支保持一致：写失败要回滚并说明原因，
                                    // 不然未登录时界面照样显示「已收藏」，其实没写上去
                                    val ok = runCatching { Api.collectSeries(seriesId, next) }
                                        .getOrDefault(false)
                                    if (!ok) withContext(Dispatchers.Main) {
                                        favorite = !next
                                        Store.toggleCard(Store.KEY_FAVORITE, cardOf(d))
                                        actionMsg = if (Store.loggedIn) {
                                            "收藏没同步成功，检查网络后重试"
                                        } else {
                                            "未登录，收藏只保存在本机"
                                        }
                                    }
                                }
                            }
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier
                                .height(40.dp)
                                // focusRequester 必须在 clickable **外层**（clickable 内部才是 focusTarget），
                                // 放到里面 requestFocus 会抛 "not initialized"，被 runCatching 吞掉后
                                // 表现就是「进页面没有焦点」。
                                .focusRequester(playFocus)
                                // 主按钮第一次真正上屏时给一次焦点（见上方说明）
                                .onPlaced { autoFocusPlay() }
                                // clickable 在外、clip 在内：焦点框画在 clickable 层，不会被圆角裁掉
                                .clickable { onPlay(seriesId, resumeIndex) }
                                .clip(RoundedCornerShape(8.dp))
                                .background(Red)
                                .padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (resumeIndex > 1) "继续看第 $resumeIndex 集" else "从第 1 集开始",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        MetaChip(if (favorite) "★ 已收藏" else "☆ 收藏", active = favorite, activeColor = Star) {
                            val next = !favorite
                            favorite = next
                            Store.toggleCard(Store.KEY_FAVORITE, cardOf(d))
                            scope.launch(Dispatchers.IO) {
                                val ok = runCatching { Api.collectSeries(seriesId, next) }.getOrDefault(false)
                                if (!ok) withContext(Dispatchers.Main) {
                                    favorite = !next
                                    Store.toggleCard(Store.KEY_FAVORITE, cardOf(d))
                                    actionMsg = if (Store.loggedIn) "收藏没同步成功，检查网络后重试" else "未登录，收藏只保存在本机"
                                }
                            }
                        }
                        MetaChip(if (followed) "♥ 已追更" else "♡ 追更", active = followed, activeColor = Color(0xFFFF4D4F)) {
                            val next = !followed
                            followed = next
                            Store.toggleCard(Store.KEY_FOLLOW, cardOf(d))
                            scope.launch(Dispatchers.IO) {
                                val ok = runCatching { Api.followToggle(seriesId, next) }.getOrDefault(false)
                                if (!ok) withContext(Dispatchers.Main) {
                                    followed = !next
                                    Store.toggleCard(Store.KEY_FOLLOW, cardOf(d))
                                    actionMsg = if (Store.loggedIn) "追更没同步成功，检查网络后重试" else "未登录，追更只保存在本机"
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 备案号（PC 同款位置：按钮行下方、tab 上方） ----------
            if (d.recordNumber.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(d.recordNumber, color = RecordDim, fontSize = 10.sp)
            }

            // ---------- 三 tab：选集 N / 剧评 N / 相关推荐 N ----------
            // **PC 同款：外框容器 + 选中胶囊**（PC 的 TabsList 是 bg-muted 圆角
            // 容器，选中 trigger 更亮一档）。之前误判去掉了外框，特写对比确认
            // PC 确实有那一圈，已改回：外框 Panel、选中项 PanelHi。
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier
                    .background(Panel, RoundedCornerShape(10.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TabTitle("选集 ${episodes.size}", tab == 0) { tab = 0 }
                TabTitle(if (reviewTotal > 0) "剧评 $reviewTotal" else "剧评", tab == 1) { tab = 1 }
                TabTitle(
                    if (relatedGroups.isNotEmpty()) "相关推荐 ${relatedGroups.sumOf { it.items.size }}" else "相关推荐",
                    tab == 2,
                ) { tab = 2 }
            }
            Spacer(Modifier.height(12.dp))

            when (tab) {
                0 -> EpisodeGrid(
                    episodes = episodes,
                    activeIndex = resumeIndex - 1,
                    onPick = { i -> onPlay(seriesId, i + 1) },
                )
                1 -> ReviewsBlock(
                    reviews, reviewScore, reviewScoreCount,
                    canPost = Store.loggedIn,
                    onPost = { text, stars ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { Api.sendSeriesReview(seriesId, text, stars * 2) }
                                    .getOrDefault(false)
                            }
                            if (ok) {
                                val rp = withContext(Dispatchers.IO) {
                                    runCatching { Api.reviews(seriesId, 10) }.getOrNull()
                                }
                                if (rp != null) {
                                    reviews = rp.items
                                    reviewScore = rp.score
                                    reviewScoreCount = rp.scoreCount
                                    reviewTotal = rp.total
                                }
                                actionMsg = "剧评已发布"
                            } else {
                                actionMsg = "发布失败：先登录再发剧评"
                            }
                        }
                    },
                )
                else -> {
                    val groups = buildList {
                        if (seasons.size > 1) {
                            add(
                                RelatedGroup(
                                    "本剧系列 · " + seasons.size + " 季",
                                    seasons.map { s ->
                                        SeriesCard(
                                            seriesId = s.seriesId,
                                            title = s.title,
                                            cover = s.cover,
                                            episodeCount = s.count,
                                            season = s.index.takeIf { it > 0 }?.toString(),
                                            fromOther = s.fromOther,
                                            // 还没上线的季（没有剧集）：标成「待上线」，
                                            // 这样卡片下面才会出现「预约」按钮。
                                            // 以前这里恒为 false，未上线的季就只剩一张空卡片，
                                            // 而同一个季在「相关作品」里却带预约按钮 —— 前言不搭后语。
                                            upcoming = s.count <= 0,
                                        )
                                    },
                                ),
                            )
                        }
                        // 「相关作品」里把本剧各季剔掉：上游推荐位本来就把同一部剧的各季
                        // 一起塞进来，不去重的话两组前几项完全一样，看着像重复了两遍。
                        // 剔完剩下的才是真正的「同 IP 其它作品」。
                        val seasonIds = seasons.map { it.seriesId }.toSet()
                        relatedGroups.forEach { g ->
                            val rest = g.items.filter { it.seriesId !in seasonIds }
                            if (rest.isNotEmpty()) add(g.copy(items = rest))
                        }
                    }
                    RelatedBlock(
                        groups = groups,
                        reservedIds = reservedIds,
                        onOpen = onOpen,
                        onReserve = { toggleReserveOnCard(it) },
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

        /**
         * 操作反馈：浮在屏幕底部，2.6 秒自动消失。
         *
         * 之前这行文案写在按钮下面，在「相关推荐」里点卡片上的预约时，
         * 提示会飘在屏幕上方看不见的地方 —— 用户以为没反应。
         */
        actionMsg?.let { msg ->
            LaunchedEffect(msg) {
                delay(2600)
                if (actionMsg == msg) actionMsg = null
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xE6101013))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(msg, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TabTitle(text: String, selected: Boolean, onClick: () -> Unit) {
    // PC 同款：外框由调用处提供（Panel），选中项是**更亮一档的 PanelHi 胶囊**。
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) PanelHi else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) TextMain else TextDim,
            fontSize = if (selected) 15.sp else 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun MetaChip(
    text: String,
    active: Boolean = false,
    activeColor: Color = Star,
    onClick: () -> Unit,
) {
    // **PC 同款激活色**：收藏激活 = 黄（text-star #FF9500，星标实心）、
    // 追更/点赞激活 = 红（text-hot）。未激活保持中性 PanelHi。
    Box(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) activeColor.copy(alpha = 0.16f) else PanelHi)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (active) activeColor else TextMain,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 选集：**与 PC 完全同款**的宽格子（序号 + 「第N集」+ 时长），一行 5 个。
 *  PC 的选集格是 [序号 32px + 第N集 + 右对齐时长] 的定宽结构、最多 5 列；
 *  原来 TV 是纯数字一行 17 个（原版口径），信息只有序号，与 PC 差异明显。 */
@Composable
private fun EpisodeGrid(episodes: List<Episode>, activeIndex: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        episodes.indices.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { i ->
                    val ep = episodes[i]
                    val active = i == activeIndex
                    Row(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) Red else PanelHi)
                            .clickable { onPick(i) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 序号（定宽，与 PC 的 w-8 同构）
                        Text(
                            "${i + 1}",
                            color = if (active) Color.White else TextDim,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(26.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Text(
                            "第${i + 1}集",
                            color = if (active) Color.White else TextMain,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // 时长（与 PC 同款右对齐；0 表示上游没给，不占位）
                        if (ep.duration > 0) {
                            Text(
                                fmtTime(ep.duration.toLong() * 1000),
                                color = if (active) Color(0xCCFFFFFF) else TextDim,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                // 末行补齐空位，保持格子等宽
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ReviewsBlock(
    reviews: List<ReviewItem>,
    score: String = "",
    scoreCount: Int = 0,
    /** 登录后才显示发布框；TV 也能发（评分 + 文字），与 PC 的剧评发布对齐 */
    canPost: Boolean = false,
    onPost: ((String, Int) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 评分区（PC 同款：大数字橙色 + 「N人评分」灰色小字并排）
        if (score.isNotBlank()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    score,
                    color = Star,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
                if (scoreCount > 0) {
                    Text(
                        "${formatCount(scoreCount.toString())}人评分",
                        color = TextDim,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
        }
        if (canPost && onPost != null) {
            var stars by remember { mutableStateOf(5) }
            var text by remember { mutableStateOf("") }
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("发剧评", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp))
                    (1..5).forEach { i ->
                        Text(
                            if (i <= stars) "★" else "☆",
                            color = Star,
                            fontSize = 20.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { stars = i }
                                .padding(horizontal = 3.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TvChip("发布", selected = text.isNotBlank()) {
                        if (text.isNotBlank()) {
                            onPost(text.trim(), stars)
                            text = ""
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(200) },
                    textStyle = TextStyle(color = TextMain, fontSize = 13.sp),
                    cursorBrush = SolidColor(Red),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PanelHi)
                        .padding(10.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxSize()) {
                            if (text.isEmpty()) Text("说说你的看法…", color = TextDim, fontSize = 13.sp)
                            inner()
                        }
                    },
                )
            }
        }
        if (reviews.isEmpty()) {
            Text("还没有剧评", color = TextDim, fontSize = 13.sp)
            return@Column
        }
        reviews.forEach { r ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.user.ifBlank { "匿名" },
                        color = TextMain,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (r.time.isNotBlank()) Text(r.time, color = Color(0x66FFFFFF), fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(r.text, color = TextDim, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

/**
 * 相关推荐按上游的 cell 分组渲染（「相关作品」「猜你喜欢」），与原版一致。
 * 每个分组一个小标题 + 6 列网格。
 */
@Composable
private fun RelatedBlock(
    groups: List<RelatedGroup>,
    reservedIds: Set<String> = emptySet(),
    onOpen: (String) -> Unit,
    onReserve: (SeriesCard) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        groups.forEach { group ->
            Text(
                "${group.name} · ${group.items.size}",
                color = TextDim,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            group.items.chunked(6).forEach { rowCards ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowCards.forEach { card ->
                        // focusGroup：把「卡片 + 预约按钮」包成一个焦点组，
                        // 遥控器在卡片上按 ↓ 会先进组内的预约按钮，而不是直接
                        // 跳到下一排卡片（电视上非常容易按错、也点不到按钮）。
                        Column(
                            Modifier
                                .weight(1f)
                                .focusGroup(),
                        ) {
                            // 已上线：一行「共125集 · 21万播放」；待上线：这行让位给预约按钮
                            val meta = listOfNotNull(
                                // 这套季是从另一套合集补来的，明说一句，免得用户以为是重名
                                "另一版本".takeIf { card.fromOther },
                                // 与 PC 相关卡同款副行：共N集 · 热度
                                card.episodeCount.takeIf { it > 0 }?.let { "共 ${it} 集" },
                                card.playText.takeIf { it.isNotBlank() },
                            ).joinToString(" · ").ifBlank { null }
                            SeriesCardView(
                                card = card,
                                modifier = Modifier.fillMaxWidth(),
                                metaLine = meta,
                                onClick = {
                                    // 先暂存：未上线的「季」详情接口可能直接 404，靠这份兜底
                                    Api.stashCard(card)
                                    onOpen(card.seriesId)
                                },
                            )
                            // 待上线的季：卡片**下方**一个独立按钮（TV 遥控器要能单独选中）
                            if (card.upcoming) {
                                Spacer(Modifier.height(6.dp))
                                ReserveButton(
                                    reserved = reservedIds.contains(card.seriesId),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { onReserve(card) }
                            }
                        }
                    }
                    repeat(6 - rowCards.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun cardOf(d: SeriesDetail): SeriesCard = SeriesCard(
    seriesId = d.seriesId,
    title = d.title,
    cover = d.cover,
    episodeCount = d.episodeCount,
    contentType = d.contentType,
    tags = d.tags,
)
