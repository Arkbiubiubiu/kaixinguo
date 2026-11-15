@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.kaixinguo.pc

import androidx.compose.ui.graphics.toArgb

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hongguotv.pad.ShelfKind
import com.hongguotv.pad.ShelfRepository
import com.hongguotv.pad.Api
import com.hongguotv.pad.App
import com.hongguotv.pad.FeedPage
import com.hongguotv.pad.FilterRow
import com.hongguotv.pad.HistoryApi
import com.hongguotv.pad.HistoryRepository
import com.hongguotv.pad.HistoryShadow
import com.hongguotv.pad.Log
import com.hongguotv.pad.RankTabsel
import com.hongguotv.pad.SeriesCard
import com.hongguotv.pad.SeriesDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun RankPage(onOpen: (String) -> Unit, onPlay: (String) -> Unit) {
    var tabs by remember { mutableStateOf<List<RankTabsel>>(emptyList()) }
    var tabIndex by remember { mutableStateOf(0) }
    var subIndex by remember { mutableStateOf(0) }
    var panel by remember { mutableStateOf("") }
    var filterOpen by remember { mutableStateOf(false) }
    // 月榜（安卓一直有，PC 按用户要求补回）：空 = 当前榜，非空 = 服务器下发的某个月份
    var month by remember { mutableStateOf("") }
    var monthOpen by remember { mutableStateOf(false) }
    var cards by remember { mutableStateOf<List<SeriesCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    /** 这一页榜单数据到底是什么时候拉下来的（描述行的兜底时间，别装成「今天刚更新」） */
    var dataAt by remember { mutableStateOf(0L) }
    /** 榜单元数据（含各子榜的「N月N日已更新」）上次拉取时刻；超过 1 分钟再进/切榜就重拉 */
    var metaAt by remember { mutableStateOf(0L) }
    /** 榜单翻页：上游一页 10 条，滚到底继续要下一页（以前只拉第一页，所以永远只有 10 名） */
    var session by remember { mutableStateOf("") }
    var nextOffset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    val rankList = PageMemo.rankList

    // 榜单元数据拉不到时，以前 tab 为 null、下面的 effect 直接 return，
    // loading 永远 true → 页面无限转圈。现在落成可重试的失败态。
    var metaError by remember { mutableStateOf(false) }
    var metaAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(metaAttempt) {
        val list = withContext(Dispatchers.IO) { runCatching { Api.rankMeta() }.getOrDefault(emptyList()) }
        if (list.isEmpty()) {
            metaError = true
            loading = false
        } else {
            tabs = list
            metaError = false
            metaAt = System.currentTimeMillis()
        }
    }

    val tab = tabs.getOrNull(tabIndex)
    val sub = tab?.subs?.getOrNull(subIndex)

    LaunchedEffect(tab?.id, sub?.id, panel, month) {
        val t = tab ?: return@LaunchedEffect
        // 榜单元数据（子榜的「N月N日已更新」）只在进页面时拉一次的话，
        // 挂着过夜就会一直显示昨天。切榜/换筛选时顺手刷新一下（1 分钟内不重复拉）。
        if (System.currentTimeMillis() - metaAt > 60_000) {
            val fresh = withContext(Dispatchers.IO) { runCatching { Api.rankMeta() }.getOrDefault(emptyList()) }
            if (fresh.isNotEmpty()) {
                tabs = fresh
                metaAt = System.currentTimeMillis()
            }
        }
        loading = true
        val page = withContext(Dispatchers.IO) {
            // 优先走官方榜单（带筛选面板），失败再退回服务器聚合的 chart
            val p = runCatching { Api.rankCached(0, "", t.id, sub?.id ?: "", panel, month) }.getOrNull()
            if (p != null && p.cards.isNotEmpty()) {
                p
            } else {
                com.hongguotv.pad.FeedPage(
                    cards = runCatching {
                        Api.chart(metric = sub?.id ?: "", genre = t.id, limit = 60)
                    }.getOrDefault(emptyList()),
                    hasMore = false,
                )
            }
        }
        cards = page.cards
        session = page.sessionId
        nextOffset = page.nextOffset
        hasMore = page.hasMore
        dataAt = System.currentTimeMillis()
        loading = false
        // 换榜回到顶部：必须等 loading=false 之后（列表重新组合出来再滚，
        // 在列表还没挂上时调 scrollToItem 会一直挂起，页面就卡在转圈）
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            runCatching { rankList.scrollToItem(0) }
        }
    }

    // 滚到倒数第三条就开始要下一页（同 find 页的哨兵语义）
    LaunchedEffect(rankList, cards.size, hasMore, tab?.id, sub?.id, panel, month) {
        androidx.compose.runtime.snapshotFlow {
            rankList.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { last ->
            val t = tab ?: return@collect
            if (hasMore && !loadingMore && !loading && last >= cards.size - 3) {
                loadingMore = true
                val next = withContext(Dispatchers.IO) {
                    runCatching { Api.rank(nextOffset, session, t.id, sub?.id ?: "", panel, month) }.getOrNull()
                }
                if (next != null && next.cards.isNotEmpty()) {
                    val known = cards.map { it.seriesId }.toSet()
                    cards = cards + next.cards.filter { it.seriesId !in known }
                    session = next.sessionId
                    nextOffset = next.nextOffset
                    hasMore = next.hasMore
                } else {
                    hasMore = false
                }
                loadingMore = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        // 大类 tab（旧版页内第一行：全部/系列剧/漫剧/真人剧/AI剧）
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, t ->
                TabChip(t.label, index == tabIndex) {
                    tabIndex = index; subIndex = 0; panel = ""; month = ""
                    filterOpen = false; monthOpen = false
                }
            }
        }
        if (tab != null && tab.subs.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tab.subs.forEachIndexed { index, s ->
                    TabChip(s.label, index == subIndex) {
                        subIndex = index; panel = ""; month = ""
                        filterOpen = false; monthOpen = false
                    }
                }
                // 筛选按钮（旧版 FilterPanelButton）：仅当前子榜自带面板时出现，
                // chip 形态——未选中灰、选中红；面板里单选，选中后 chip 显示所选名
                if (sub != null && sub.panels.any { it.items.isNotEmpty() }) {
                    Spacer(Modifier.width(4.dp))
                    val selectedName = sub.panels.firstNotNullOfOrNull { p ->
                        p.items.firstOrNull { it.id == panel && it.id.isNotEmpty() }?.name
                    }
                    Row(
                        Modifier.height(28.dp).clip(Radius.chip)
                            .background(
                                if (panel.isNotEmpty()) Palette.accent.copy(alpha = 0.16f) else Palette.panel,
                            )
                            .border(
                                1.dp,
                                if (panel.isNotEmpty()) Palette.accent.copy(alpha = 0.45f) else Palette.line,
                                Radius.chip,
                            )
                            .clickable { filterOpen = !filterOpen }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = null,
                            tint = if (panel.isNotEmpty()) Palette.accentHover else Palette.dim,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            selectedName ?: "筛选",
                            color = if (panel.isNotEmpty()) Palette.accentHover else Palette.dim,
                            fontSize = 13.sp,
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = if (panel.isNotEmpty()) Palette.accentHover else Palette.dim,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                // 月榜（安卓有、PC 补回）：服务器给月份才出现；选中后 chip 显示月份名
                if (sub != null && sub.months.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    val monthName = sub.months.firstOrNull { it.id == month }?.name
                    Row(
                        Modifier.height(28.dp).clip(Radius.chip)
                            .background(
                                if (month.isNotEmpty()) Palette.accent.copy(alpha = 0.16f) else Palette.panel,
                            )
                            .border(
                                1.dp,
                                if (month.isNotEmpty()) Palette.accent.copy(alpha = 0.45f) else Palette.line,
                                Radius.chip,
                            )
                            .clickable { monthOpen = !monthOpen }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.DateRange,
                            contentDescription = null,
                            tint = if (month.isNotEmpty()) Palette.accentHover else Palette.dim,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            monthName ?: "月榜",
                            color = if (month.isNotEmpty()) Palette.accentHover else Palette.dim,
                            fontSize = 13.sp,
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = if (month.isNotEmpty()) Palette.accentHover else Palette.dim,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        // 描述行（旧版：子榜名 + 「N月N日已更新·基于红果热度及个人数据推荐」）
        // 组间 16 / 组内 8：与上方筛选拉开，与下方列表贴近
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)) {
            Text(
                sub?.label ?: "榜单",
                color = Palette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
            // 描述行用上游给的「N月N日已更新…」；没有就老实写「数据 HH:mm 拉取」。
            // 以前这里写 LocalDate.now()，等于假装今天刚更新过——TV 端显示的是上游日期，
            // 于是同一时刻两端日期对不上（用户实测：TV 11-9 / PC 11-10）。
            Text(
                sub?.desc?.takeIf { it.isNotBlank() }
                    ?: run {
                        val stamp = if (dataAt > 0L) {
                            val t = java.time.Instant.ofEpochMilli(dataAt)
                                .atZone(java.time.ZoneId.systemDefault())
                            "数据 %02d:%02d 拉取".format(t.hour, t.minute)
                        } else ""
                        (if (stamp.isEmpty()) "" else "$stamp · ") + "基于红果热度及个人数据推荐"
                    },
                color = Palette.faint, fontSize = 12.sp,
            )
        }
        // 子榜筛选已收进上方「筛选」按钮弹层（旧版 FilterPanelButton 同构），
        // 不再整面平铺——面板只在子榜自带选项时出现，且不占版面。
        if (tab == null && metaError) {
            CenterRetry("榜单没加载出来（网络问题）") { metaError = false; loading = true; metaAttempt += 1 }
        } else if (loading) {
            CenterLoading()
        } else {
            val list = cards
            androidx.compose.foundation.lazy.LazyColumn(
                state = rankList,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(list) { index, card ->
                    RankRow(index + 1, card, onClick = { onOpen(card.seriesId) }, onPlay = { onPlay(card.seriesId) })
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            when {
                                loadingMore -> "加载中…"
                                hasMore -> "下滑加载更多"
                                list.isEmpty() -> ""
                                else -> "已经到底了"
                            },
                            color = Palette.faint, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
    // 筛选浮层：分组 + 胶囊（和「找剧」同一套观感）。
    // 原来是一列 220dp 长条，选项一多就是一整块往下无限滚、没有任何分组层级，
    // 用户反馈「看不清、不知道在选什么」——改成固定宽度、每组一行标签 + 一排胶囊。
    val subNow = sub
    if (filterOpen && subNow != null) {
        Box(
            Modifier.matchParentSize()
                .onPointerEvent(PointerEventType.Press) { filterOpen = false },
        )
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 24.dp)
                .width(620.dp).heightIn(max = 520.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF7101014))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("筛选", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (panel.isNotEmpty()) {
                    Text(
                        "重置",
                        color = Palette.accent, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .clickable { panel = "" }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            subNow.panels.forEach { p ->
                if (p.items.isEmpty()) return@forEach
                Spacer(Modifier.height(12.dp))
                Text(p.name, color = Palette.faint, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    p.items.forEach { option ->
                        val active = panel == option.id
                        FilterChip(option.name, active) {
                            panel = if (active) "" else option.id
                        }
                    }
                }
            }
        }
    }
    // 月榜浮层：单选，选中即收起；第一项是「当前榜」（= 不带 month 参数）
    if (monthOpen && subNow != null && subNow.months.isNotEmpty()) {
        Box(
            Modifier.matchParentSize()
                .onPointerEvent(PointerEventType.Press) { monthOpen = false },
        )
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 24.dp)
                .width(240.dp).heightIn(max = 420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF7101014))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("月榜", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (month.isNotEmpty()) {
                    Text(
                        "重置",
                        color = Palette.accent, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .clickable { month = "" }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip("当前榜", month.isEmpty()) { month = ""; monthOpen = false }
                subNow.months.forEach { m ->
                    FilterChip(m.name, month == m.id) { month = m.id; monthOpen = false }
                }
            }
        }
    }
    }
}

/**
 * 行卡右侧动作列（排行榜 / 上新日历共用）：
 * 时间（可选）→ 播放/预约 → 详情，三行右对齐、宽度固定，按钮同宽。
 * 之前详情是一个裸文字、还带自己的 padding，和按钮对不齐也不像按钮。
 */
@Composable
internal fun RowActions(
    onPlay: () -> Unit,
    onDetail: () -> Unit,
    timeLabel: String? = null,
    upcoming: Boolean = false,
    reserved: Boolean = false,
    onReserve: (() -> Unit)? = null,
) {
    Column(
        Modifier.width(104.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!timeLabel.isNullOrBlank()) {
            Text(timeLabel, color = Palette.faint, fontSize = 12.sp, maxLines = 1)
        }
        when {
            !upcoming -> PrimaryButton("播放", Icons.Filled.PlayArrow, Modifier.fillMaxWidth(), onPlay)
            onReserve != null -> SecondaryButton(
                if (reserved) "已预约" else "预约",
                icon = if (reserved) Icons.Filled.Check else Icons.Filled.Add,
                modifier = Modifier.fillMaxWidth(),
                onClick = onReserve,
            )
            else -> Text(
                if (reserved) "已预约" else "待上线",
                color = if (reserved) Palette.dim else Palette.accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        OutlineButton("详情", null, Modifier.fillMaxWidth(), onDetail)
    }
}

@Composable
internal fun RankRow(rank: Int, card: SeriesCard, onClick: () -> Unit, onPlay: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    // 旧版 SeriesRowCard 规格：p-3、gap-4、圆角 12、细边框；
    // 名次 2xl font-black（前 3 星色，其余 muted/50）；封面 80px 3:4；
    // 标题 14/600 + 季徽；metaLine（副标题·评分·标签）12px；
    // 两行简介 12px；🔥热度行 warm 色；行尾动作列（红底播放 + 文字详情）。
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (hovered) Color(0x33FFFFFF) else Palette.line, RoundedCornerShape(12.dp))
            .background(Palette.panel)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank > 0) {
            Text(
                "$rank",
                color = if (rank <= 3) Palette.star else Palette.dim.copy(alpha = 0.5f),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        RemoteImage(
            card.cover,
            Modifier.width(88.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)),
            fallback = card.title,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(card.title, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // 评分：星形 + 黄字（与详情页评分同语言）
                card.score.takeIf { it.isNotBlank() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Palette.star, modifier = Modifier.size(12.dp))
                        Text(it, color = Palette.star, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                card.season?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = Palette.dim,
                        fontSize = 10.sp,
                        modifier = Modifier.border(1.dp, Palette.line, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            val meta = listOf(
                card.subtitle?.takeIf { it.isNotBlank() },
                card.tags.take(3).joinToString(" · ").takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            card.desc?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Palette.dim.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val rec = card.recText?.takeIf { it.isNotBlank() }
            val secondary = card.secondary?.filter { it.isNotBlank() && it != rec }.orEmpty()
            if (rec != null || secondary.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    rec?.let {
                        HeatBadge(it)
                    }
                    if (secondary.isNotEmpty()) {
                        Text(secondary.joinToString("  "), color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        RowActions(
            onPlay = onPlay,
            onDetail = onClick,
            upcoming = card.upcoming,
            reserved = card.reserved,
        )
    }
}

// ---------------------------------------------------------------- 新剧（推荐 + 上新日历双视图）
