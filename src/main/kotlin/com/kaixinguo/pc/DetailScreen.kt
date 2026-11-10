@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.kaixinguo.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hongguotv.pad.Api
import com.hongguotv.pad.ReviewPage
import com.hongguotv.pad.RelatedGroup
import com.hongguotv.pad.SeriesCard
import com.hongguotv.pad.SeriesDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 详情页：沉浸背景 + 左海报 + 右信息 + 选集 / 剧评 / 相关推荐。
 * 收藏、追更按钮直接调 core 的 Api（走签名服务）。
 */
@Composable
fun DetailScreen(seriesId: String, initialTab: Int = 0, onBack: () -> Unit, onPlay: (Int) -> Unit, onOpenSeason: (String) -> Unit = {}, onCreator: (String) -> Unit = {}) {
    var detail by remember(seriesId) { mutableStateOf<SeriesDetail?>(null) }
    var loadDone by remember(seriesId) { mutableStateOf(false) }
    var reviews by remember { mutableStateOf<ReviewPage?>(null) }
    var related by remember { mutableStateOf<List<RelatedGroup>>(emptyList()) }
    var seasons by remember { mutableStateOf<List<com.hongguotv.pad.SeasonRef>>(emptyList()) }
    // key 带 initialTab：从播放器点「评论」进来要直接落在剧评 tab
    // （仅 remember 不带 key 时，同一详情实例复用时不会重读初值）
    var tab by remember(seriesId, initialTab) { mutableStateOf(initialTab.coerceIn(0, 2)) }
    var fav by remember { mutableStateOf(PcStore.contains("favorite", seriesId)) }
    var followed by remember { mutableStateOf(false) }
    var liked by remember { mutableStateOf(false) }
    var likedVid by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    /** 銆岀户缁湅銆嶇殑闆嗗彿锛氫簯绔巻鍙?> 鏈湴杩涘害 > 1锛堟棫鐗?resolveResumeEntry 鍚岃涔夛級 */
    var continueIndex by remember { mutableStateOf(1) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(seriesId) {
        // 五路请求并行（旧版 react-query 并行同构）：此前串行导致
        // 相关推荐/剧评要等前面全部回来才出现，详情页下半屏迟迟空着
        kotlinx.coroutines.coroutineScope {
            launch {
                val d = withContext(Dispatchers.IO) { runCatching { Api.ensureDetail(seriesId) }.getOrNull() }
                detail = d
                followed = d?.followed ?: false
                liked = d?.hasDigg ?: false
            }
            launch {
                // 云端历史推「继续看」：同一部剧取最新一条的集号（>3 秒才续播）
                val hist = withContext(Dispatchers.IO) {
                    runCatching { Api.officialHistory(2) }.getOrDefault(emptyList())
                }
                val mine = hist.firstOrNull { it.optString("seriesId") == seriesId }
                val cloudEp = mine?.optInt("episode", 0) ?: 0
                val cloudPos = mine?.optLong("positionMs", 0L) ?: 0L
                continueIndex = if (cloudEp > 1 && cloudPos > 3_000) cloudEp else 1
            }
            launch {
                reviews = withContext(Dispatchers.IO) { runCatching { Api.reviews(seriesId, 20) }.getOrNull() }
            }
            launch {
                related = withContext(Dispatchers.IO) { runCatching { Api.relatedSeries(seriesId) }.getOrDefault(emptyList()) }
            }
            launch {
                seasons = withContext(Dispatchers.IO) { runCatching { Api.seasons(seriesId) }.getOrDefault(emptyList()) }
            }
        }
        loadDone = true
    }

    Box(Modifier.fillMaxSize()) {
        // 沉浸背景：封面放大模糊 + 暗化渐变
        val cover = detail?.cover.orEmpty()
        if (cover.isNotBlank()) {
            RemoteImage(cover, Modifier.matchParentSize().padding(0.dp), contentScale = ContentScale.Crop)
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to Color(0xE60B0B0D),
                        0.45f to Color(0xF20B0B0D),
                        1f to Palette.bg,
                    ),
                ),
            )
        }
        if (detail == null && !loadDone) {
            androidx.compose.material3.CircularProgressIndicator(
                color = Palette.accent,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(40.dp),
            )
        }
        if (detail == null && loadDone) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("加载失败，检查网络后重试", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                SecondaryButton("返回", onClick = onBack)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(text = "返回", icon = Icons.Filled.ArrowBack, onClick = onBack)
            }
            Spacer(Modifier.height(16.dp))

            val d = detail
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                // 封面 w-44(176px) 圆角 12（旧版 aspect-[3/4] w-44 rounded-xl）
                RemoteImage(
                    d?.cover.orEmpty(),
                    Modifier.width(176.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)),
                    fallback = d?.title,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 标题 24/600（旧版 text-2xl font-semibold）
                    Text(
                        d?.title ?: "加载中…",
                        color = Palette.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 统计行（旧版排版：评分(star 20px) 评分人数 → 热度值 → 追剧 → 播放，muted 14px）
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val reviewScore = reviews?.score.orEmpty()
                        val reviewCnt = reviews?.scoreCount ?: 0
                        if (reviewScore.isNotBlank()) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    try { "%.1f".format(reviewScore.toDouble()) } catch (_: Exception) { reviewScore },
                                    color = Palette.star,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "分 " + com.hongguotv.pad.formatCount(reviewCnt.toString()) + "人评分",
                                    color = Palette.dim,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(bottom = 2.dp),
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        d?.hotScore?.takeIf { it.isNotBlank() }?.let {
                            HeatBadge(it, prefix = "红果热度值")
                        }
                        d?.followedCount?.takeIf { it.isNotBlank() }?.let {
                            Text(com.hongguotv.pad.formatCount(it) + "人追剧", color = Palette.dim, fontSize = 14.sp)
                        }
                        d?.playCount?.takeIf { it.isNotBlank() && it != "0" }?.let {
                            Text(com.hongguotv.pad.formatCount(it) + "次播放", color = Palette.dim, fontSize = 14.sp)
                        }
                    }
                    // 季徽 + 全 N 集（旧版 hot 底红徽 + 集 badge）
                    if ((d?.episodeCount ?: 0) > 0 || d?.season?.isNotBlank() == true) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            d?.season?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.accentFill)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            if ((d?.episodeCount ?: 0) > 0) {
                                Text(
                                    "全 ${d?.episodeCount} 集",
                                    color = Palette.text,
                                    fontSize = 12.sp,
                                    modifier = Modifier.border(1.dp, Palette.line, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    // 出品方：点进去看创作者主页
                    if (!d?.creators.isNullOrEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            d!!.creators.take(6).forEach { c ->
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Palette.panel)
                                        .clickable { onCreator(c.id) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RemoteImage(c.avatar, Modifier.size(24.dp).clip(RoundedCornerShape(50)))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(c.name, color = Palette.text, fontSize = 12.sp)
                                        if (c.role.isNotBlank()) Text(c.role, color = Palette.faint, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        d?.intro.orEmpty().ifBlank { "暂无简介" },
                        color = Palette.dim,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(18.dp))
                    if (d?.upcoming == true) {
                        // 未上线降级视图（旧版同款）：即将上线 + 预约按钮
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("即将上线", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            SecondaryButton(if (d.onlineSubscribed) "已预约" else "预约", icon = if (d.onlineSubscribed) Icons.Filled.Check else Icons.Filled.Add) {
                                scope.launch(Dispatchers.IO) {
                                    val ok = runCatching { Api.reserveToggle(seriesId, !d.onlineSubscribed) }.getOrDefault(false)
                                    withContext(Dispatchers.Main) {
                                        message = if (ok) (if (d.onlineSubscribed) "已取消预约" else "预约成功，上线提醒你") else "操作失败（需要登录）"
                                        if (ok) detail = d.copy(onlineSubscribed = !d.onlineSubscribed)
                                    }
                                }
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            PrimaryButton(
                                if (continueIndex > 1) "继续看第 $continueIndex 集" else "从第 1 集开始",
                                Icons.Filled.PlayArrow,
                            ) { onPlay(continueIndex) }
                            SecondaryButton(if (fav) "已收藏" else "收藏", icon = if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, iconTint = if (fav) Palette.star else Palette.dim) {
                                val target = !fav
                                scope.launch(Dispatchers.IO) {
                                    // 收藏要落到账号上（云端），本地只做离线镜像
                                    val ok = runCatching { Api.collectSeries(seriesId, target) }.getOrDefault(false)
                                    withContext(Dispatchers.Main) {
                                        if (ok) {
                                            fav = PcStore.toggle("favorite", seriesId) == target
                                            message = if (target) "已加入收藏" else "已取消收藏"
                                        } else {
                                            message = "收藏失败（需要登录）"
                                        }
                                    }
                                }
                            }
                            // 点赞对象是当前「继续看」那一集的 vid（旧版同语义）
                            val likeVid = d?.episodes?.firstOrNull { it.index == continueIndex }?.vid.orEmpty()
                            SecondaryButton(if (liked) "已赞" else "点赞", icon = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, iconTint = if (liked) Palette.accent else Palette.dim) {
                                if (likeVid.isEmpty()) return@SecondaryButton
                                val target = !liked
                                scope.launch(Dispatchers.IO) {
                                    val ok = runCatching { Api.diggVideo(likeVid, seriesId, target) }.getOrDefault(false)
                                    withContext(Dispatchers.Main) {
                                        if (ok) liked = target
                                        message = if (ok) (if (target) "已点赞" else "已取消点赞") else "操作失败（需要登录）"
                                    }
                                }
                            }
                            // 追更（对齐官方详情页）：仅连载中的剧显示
                            if (d?.status == "0") {
                                SecondaryButton(if (followed) "已追更" else "追更", icon = if (followed) Icons.Filled.Check else Icons.Filled.Add) {
                                    val target = !followed
                                    scope.launch(Dispatchers.IO) {
                                        val ok = runCatching { Api.followToggle(seriesId, target) }.getOrDefault(false)
                                        // 追更成功立刻把更新提醒基线记成当前集数
                                        // （旧版 follow_service 同口径：从现在起出新集才提示）
                                        if (ok && target) {
                                            PcStore.seedFollowBaseline(seriesId, d?.episodeCount ?: 0)
                                        }
                                        withContext(Dispatchers.Main) {
                                            if (ok) followed = target
                                            message = if (ok) (if (target) "已追更" else "已取消追更") else "操作失败（需要登录）"
                                        }
                                    }
                                }
                            }
                        }
                    }
                    message?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Palette.accent, fontSize = 12.sp)
                    }
                }
            }

            // 本剧系列：同一部剧的其它季（来自 /api/seasons，官方数据）
            if (seasons.size > 1) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("本剧系列", color = Palette.dim, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        seasons.forEach { s ->
                            val active = s.seriesId == seriesId
                            Text(
                                (if (s.index > 0) "第 ${s.index} 季" else s.title) + if (s.count > 0) " · ${s.count} 集" else "",
                                color = if (active) Color.White else Palette.dim,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (active) Palette.accentFill else Palette.panel)
                                    .clickable { if (!active) onOpenSeason(s.seriesId) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabChip("选集 ${d?.episodes?.size ?: 0}", tab == 0) { tab = 0 }
                TabChip("剧评 ${reviews?.total ?: 0}", tab == 1) { tab = 1 }
                // 相关推荐 tab 再次点开 = 换一批（旧版同款；无游标的接口用本地轮换等价实现）
                TabChip("相关推荐", tab == 2) {
                    if (tab == 2 && related.isNotEmpty()) {
                        related = related.map { g ->
                            if (g.items.size > 2) g.copy(items = g.items.drop(2) + g.items.take(2)) else g
                        }
                    } else {
                        tab = 2
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            when (tab) {
                0 -> EpisodeGrid(d?.episodes.orEmpty(), continueIndex) { ep -> onPlay(ep) }
                1 -> ReviewsBlock(reviews, seriesId)
                else -> RelatedBlock(related, onOpenSeries = onOpenSeason)
            }
            // 备案号：头部块之下、tab 之上（旧版同位置独立的一行小字）
            d?.recordNumber?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Palette.faint, fontSize = 12.sp)
            }
        }
    }
}

/** 在小协程里跑一次写操作（Compose 事件回调不能直接挂起） */
private fun withContextMsg(target: Boolean, block: () -> Boolean): Boolean = runCatching { block() }.getOrDefault(false)

@Composable
private fun EpisodeGrid(episodes: List<com.hongguotv.pad.Episode>, continueIndex: Int, onPlay: (Int) -> Unit) {
    if (episodes.isEmpty()) {
        Text("这一季还没有上线的剧集", color = Palette.dim, fontSize = 14.sp)
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        episodes.forEach { ep ->
            val current = ep.index == continueIndex
            Row(
                Modifier
                    .width(150.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.panel)
                    // 选集格当前集红描边高亮（旧版 vidIndex===continueIndex 同款）
                    .border(
                        if (current) 1.dp else 0.dp,
                        if (current) Palette.accentFill else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onPlay(ep.index) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${ep.index}", color = Palette.dim, fontSize = 12.sp, modifier = Modifier.width(26.dp))
                Text("第 ${ep.index} 集", color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (ep.duration > 0) Text(fmtTime(ep.duration * 1000L), color = Palette.faint, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ReviewsBlock(page: ReviewPage?, seriesId: String) {
    if (page == null) {
        Text("加载中…", color = Palette.dim, fontSize = 14.sp)
        return
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 发评状态（旧版 ReviewComposer 同构）：5 星制 ×2 上送；发布成功本地置顶
    var stars by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var items by remember(page) { mutableStateOf(page.items) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.panel.copy(alpha = 0.72f)).padding(16.dp)) {
        // 剧均评分块（旧版：9.0 大字 + 星 + N人评分）
        if (page.score.isNotBlank()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(page.score, color = Palette.star, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("${page.scoreCount} 人评分", color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
        // 发评框：我的评分（5 星）+ 多行文本 + 发布（旧版 ReviewComposer 同款）
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Palette.panel).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("我的评分", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                (1..5).forEach { s ->
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = if (s <= stars) Palette.star else Color(0x40FFFFFF),
                        modifier = Modifier.size(20.dp).clickable { stars = s },
                    )
                }
                if (stars > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("已评 ${stars * 2} 分", color = Palette.faint, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { text = it.take(500) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.accent),
                minLines = 2,
                maxLines = 4,
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text("说说这部剧的看法…（评分后才能发布）", color = Palette.faint, fontSize = 13.sp)
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("${text.length}/500", color = Palette.faint, fontSize = 11.sp)
                Spacer(Modifier.width(12.dp))
                val canSend = text.isNotBlank() && stars >= 1 && !sending
                Text(
                    "发布",
                    color = if (canSend) Color.White else Palette.faint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (canSend) Palette.accentFill else Palette.panel.copy(alpha = 0f))
                        .border(1.dp, if (canSend) Color.Transparent else Color(0x1AFFFFFF), RoundedCornerShape(50))
                        .clickable(enabled = canSend) {
                            sending = true
                            val s = stars * 2
                            val t = text.trim()
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { runCatching { Api.sendSeriesReview(seriesId, t, s) }.getOrDefault(false) }
                                sending = false
                                if (ok) {
                                    // 本地置顶（旧版 unshift 同语义：服务端列表有索引延迟）
                                    items = listOf(
                                        com.hongguotv.pad.ReviewItem(
                                            user = PcStore.accountName.ifBlank { "我" },
                                            avatar = "", text = t, time = "刚刚", likes = "", score = s.toString(),
                                        ),
                                    ) + items
                                    text = ""
                                    stars = 0
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        if (items.isEmpty()) {
            Text("还没有剧评", color = Palette.dim, fontSize = 14.sp)
        } else {
            items.take(20).forEach { r ->
                Column(Modifier.padding(bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.user.ifBlank { "匿名" }, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        // 条目评分星（旧版 ScoreStars：十分制 ÷2）
                        if (r.score.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            val starN = (r.score.toDoubleOrNull() ?: 0.0) / 2.0
                            (1..5).forEach { s ->
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = if (s <= starN + 0.01) Palette.star else Color(0x33FFFFFF),
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(r.time, color = Palette.faint, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(r.text, color = Palette.dim, fontSize = 14.sp, lineHeight = 21.sp)
                }
            }
        }
    }
}

@Composable
private fun RelatedBlock(groups: List<RelatedGroup>, onOpenSeries: (String) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (groups.isEmpty()) {
        Text("暂无相关推荐", color = Palette.dim, fontSize = 14.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        groups.forEach { group ->
            Text(group.name, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                group.items.take(12).forEach { card ->
                    Column(Modifier.width(128.dp)) {
                        // 封面可点进详情（旧版：卡片一律进详情）
                        RemoteImage(
                            card.cover,
                            Modifier.width(128.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)).clickable { onOpenSeries(card.seriesId) },
                            fallback = card.title,
                        )
                        Spacer(Modifier.height(8.dp))
                        // 剧名 2 行截断（旧版 line-clamp-2，解决名字显示不全）
                        Text(
                            card.title,
                            color = Palette.text,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onOpenSeries(card.seriesId) },
                        )
                        // 副行：共 N 集 · 热度/播放（旧版同款）
                        val meta = buildString {
                            if (card.episodeCount > 0) append("共 ${card.episodeCount} 集")
                            card.heat?.takeIf { it.isNotBlank() }?.let {
                                if (isNotEmpty()) append(" · ")
                                append(com.hongguotv.pad.formatCount(it))
                            }
                        }
                        if (meta.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(meta, color = Palette.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // 未上线 → 预约按钮（旧版 CalendarRow 同款：未上线给预约）
                        if (card.upcoming) {
                            Spacer(Modifier.height(4.dp))
                            var reserved by remember(card.seriesId) { mutableStateOf(card.reserved) }
                            Row(
                                Modifier.height(32.dp).clip(Radius.chip)
                                    .background(if (reserved) Palette.panelHi else Palette.panel)
                                    .border(1.dp, if (reserved) Palette.line else Palette.accent, Radius.chip)
                                    .clickable {
                                        scope.launch(Dispatchers.IO) {
                                            val ok = runCatching { Api.reserveToggle(card.seriesId, !reserved) }.getOrDefault(false)
                                            withContext(Dispatchers.Main) { if (ok) reserved = !reserved }
                                        }
                                    }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    if (reserved) Icons.Filled.Check else Icons.Filled.NotificationsActive,
                                    contentDescription = null,
                                    tint = if (reserved) Palette.dim else Palette.accent,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    if (reserved) "已预约" else "预约",
                                    color = if (reserved) Palette.dim else Palette.accent,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 毫秒 → 1:23 / 1:02:03 */
fun fmtTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

