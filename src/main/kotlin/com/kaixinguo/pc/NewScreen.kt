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
internal fun NewPage(onOpen: (String) -> Unit, onPlay: (String) -> Unit) {
    // 旧版双视图：推荐（firstonlinetime_new 倒序流）/ 上新日历（按日期）
    var view by remember { mutableStateOf(0) }
    var gender by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf<FeedPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var moreLoading by remember { mutableStateOf(false) }
    // 推荐视图滚动容器（滚动近底部自动翻页）
    val newScroll = PageMemo.newScroll

    // 日历：日期条 + 当日上新行卡列表。
    // 官方口径（对齐 Android CalendarTab / Windows calendar.rs）：
    // 1. 首屏不带 target_date：服务端回 calendar_schema.date_list + 当天那批；
    // 2. 切日期要带 target_date 重新请求，不能拿当天那批按天过滤（其它日期会全空）；
    // 3. 条目有两套 schema：当天批是嵌套 subscribe_data.*，其它日期是扁平 name/item_id；
    // 4. 发布时间 schedule_publish_time（秒）要连日期一起显示（MM-dd HH:mm）。
    var calDays by remember { mutableStateOf<List<String>>(emptyList()) }
    var calDate by remember { mutableStateOf("") }
    var calByDay by remember { mutableStateOf<Map<String, List<CalendarItem>>>(emptyMap()) }
    /** date → (nextOffset, sessionId)；有值表示这天还能翻 */
    var calMore by remember { mutableStateOf<Map<String, Pair<Int, String>>>(emptyMap()) }
    var calLoading by remember { mutableStateOf(false) }
    var calMoreLoading by remember { mutableStateOf(false) }
    var calInit by remember { mutableStateOf(false) }
    val calList = PageMemo.calList
    val dayFmt = remember { java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA) }

    LaunchedEffect(view, gender) {
        if (view == 0) {
            loading = true
            page = withContext(Dispatchers.IO) { runCatching { Api.newDramaCached(gender, 0, "") }.getOrNull() }
            loading = false
        }
    }

    // 日历首屏（只跑一次）：日期表 + 当天条目
    LaunchedEffect(view) {
        if (view != 1 || calInit) return@LaunchedEffect
        calLoading = true
        val root = withContext(Dispatchers.IO) { runCatching { Api.calendar() }.getOrNull() }
        val data = root?.optJSONObject("data")
        val schema = data?.optJSONObject("calendar_schema")
        val days = mutableListOf<String>()
        schema?.optJSONArray("date_list")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.length == 8 }?.let(days::add)
        }
        val items = parseCalendarDay(root)
        val today = dayFmt.format(java.util.Date())
        val grouped = items.groupBy { it.dayKey().ifEmpty { today } }
        calDays = days.ifEmpty { grouped.keys.sorted() }
        val def = schema?.optString("default_date").orEmpty()
        calDate = when {
            def.isNotEmpty() && calDays.contains(def) -> def
            calDays.contains(today) -> today
            else -> calDays.firstOrNull().orEmpty()
        }
        calByDay = grouped
        data?.let { d ->
            if (d.optBoolean("has_more", false)) {
                calMore = mapOf(today to (d.optInt("next_offset", items.size) to d.optString("session_id")))
            }
        }
        calInit = true
        calLoading = false
    }

    // 选中尚未拉过的日期：带 target_date 重新请求
    LaunchedEffect(calDate) {
        if (view != 1 || !calInit || calDate.isEmpty() || calByDay.containsKey(calDate)) return@LaunchedEffect
        calLoading = true
        val root = withContext(Dispatchers.IO) { runCatching { Api.calendar(calDate) }.getOrNull() }
        val list = parseCalendarDay(root)
        calByDay = calByDay + (calDate to list)
        val d = root?.optJSONObject("data")
        calMore = if (d != null && d.optBoolean("has_more", false)) {
            calMore + (calDate to (d.optInt("next_offset", list.size) to d.optString("session_id")))
        } else {
            calMore - calDate
        }
        calLoading = false
    }

    // 日历翻页：滚到接近底部自动续拉（接口返回空页就当到底）
    LaunchedEffect(view, calDate, calList) {
        if (view != 1) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { calList.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                val items = calByDay[calDate].orEmpty()
                val more = calMore[calDate] ?: return@collect
                if (calLoading || calMoreLoading || items.isEmpty() || last < items.size - 2) return@collect
                calMoreLoading = true
                val (offset, session) = more
                val root = withContext(Dispatchers.IO) {
                    runCatching { Api.calendar(calDate, offset, session) }.getOrNull()
                }
                val extra = parseCalendarDay(root)
                if (extra.isNotEmpty()) {
                    calByDay = calByDay + (calDate to (items + extra).distinctBy { it.seriesId })
                }
                val d = root?.optJSONObject("data")
                calMore = if (d != null && d.optBoolean("has_more", false) && extra.isNotEmpty()) {
                    calMore + (calDate to (d.optInt("next_offset", offset + extra.size) to d.optString("session_id")))
                } else {
                    calMore - calDate
                }
                calMoreLoading = false
            }
    }

    // 滚动加载（推荐视图）：近底部 500px 自动翻页
    LaunchedEffect(newScroll.value, view) {
        if (view != 0) return@LaunchedEffect
        val max = newScroll.maxValue
        if (max <= 0 || max - newScroll.value > 500) return@LaunchedEffect
        val c = page ?: return@LaunchedEffect
        if (!c.hasMore || moreLoading || loading) return@LaunchedEffect
        moreLoading = true
        val next = withContext(Dispatchers.IO) {
            runCatching { Api.newDrama(gender, c.nextOffset, c.sessionId) }.getOrNull()
        }
        if (next != null && next.cards.isNotEmpty()) {
            val known = c.cards.map { it.seriesId }.toSet()
            page = c.copy(cards = c.cards + next.cards.filter { it.seriesId !in known })
        } else {
            page = c.copy(hasMore = false)
        }
        moreLoading = false
    }

    Column(Modifier.fillMaxSize()) {
        // 视图 tab：剧剧推荐 / 上新日历（旧版页内第一行）
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("新剧推荐", "上新日历").forEachIndexed { i, label ->
                TabChip(label, view == i) { view = i }
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (view == 1) "共 ${calByDay[calDate]?.size ?: 0} 部"
                else "共 ${page?.cards?.size ?: 0} 部",
                color = Palette.faint, fontSize = 12.sp,
            )
        }
        // 频道筛选：只对「新剧推荐」有效（日历接口按官方日历走，不分男女频）
        if (view == 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("全部", "男频", "女频").forEachIndexed { i, label ->
                    TabChip(label, gender == i) { gender = i }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        if (view == 1) {
            // 日期条：用官方的 calendar_schema.date_list（约 15 天），
            // 每格「今天/明天/MM月DD日 + 周几 · 当天条数」，横向可滚。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    calDays.forEach { d ->
                        CalendarDateCell(d, calDate == d, calByDay[d]?.size) { calDate = d }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        val calDayItems = calByDay[calDate].orEmpty()
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
            when {
                view == 0 && loading && page == null -> CenterLoading()
                view == 0 -> Column(Modifier.fillMaxSize().verticalScroll(newScroll)) {
                    PosterGrid(
                        cards = page?.cards.orEmpty(),
                        onOpen = { onOpen(it.seriesId) },
                        onPlay = { onPlay(it.seriesId) },
                        onCollect = { PcStore.toggle("favorite", it.seriesId) },
                        modifier = Modifier.fillMaxWidth(),
                        scrollable = false,
                        // 旧版新剧卡：封面 → 标题 → 「分类·N集·标签」→ ⭐评分
                        metaLine = { c ->
                            listOfNotNull(
                                c.tags.firstOrNull(),
                                if (c.episodeCount > 0) "${c.episodeCount}集" else null,
                                c.tags.drop(1).take(2).joinToString("·").takeIf { it.isNotBlank() },
                            ).joinToString("·").takeIf { it.isNotBlank() }
                        },
                        scoreLine = { c -> c.score.takeIf { it.isNotBlank() }?.let { "$it 分" } },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            when {
                                moreLoading -> "加载中…"
                                page?.hasMore == true -> "下滑加载更多"
                                else -> "已经到底了"
                            },
                            color = Palette.faint,
                            fontSize = 12.sp,
                        )
                    }
                }
                calLoading && calDayItems.isEmpty() -> CenterLoading()
                calDayItems.isEmpty() -> EmptyState(title = "这一天没有上新", hint = "换一天看看")
                else -> androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.fillMaxSize(),
                    state = calList,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    items(calDayItems, key = { it.seriesId }) { item ->
                        CalendarRow(
                            item = item,
                            onClick = { onOpen(item.seriesId) },
                            onPlay = { onPlay(item.seriesId) },
                        )
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                when {
                                    calMoreLoading -> "加载中…"
                                    calMore[calDate] != null -> "下滑加载更多"
                                    else -> "已经到底了"
                                },
                                color = Palette.faint, fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 上新日历的一条（字段口径对齐 Android CalendarTab / Windows calendar.rs）。 */
internal data class CalendarItem(
    val seriesId: String,
    val title: String,
    val cover: String,
    /** subscribe_data.video_desc（不是 description） */
    val desc: String,
    /** 橙色那格：已上线「333万播放」/ 待上线「1570.3万人预约」 */
    val heat: String,
    val score: String,
    val category: String,
    val episodeCount: Int,
    /** schedule_publish_time，毫秒 */
    val timeSec: Long,
    val online: Boolean,
    val subscribed: Boolean,
)

internal fun CalendarItem.dayKey(): String =
    if (timeSec <= 0) "" else
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA).format(java.util.Date(timeSec))

/**
 * 日历响应 → CalendarItem。
 * 两套 schema 都要认：当天那批是嵌套 `subscribe_data.*`，其它日期是扁平
 * `name / item_id / cover`；标题、series_id、封面都要按这个顺序回退。
 */
internal fun parseCalendarDay(root: JSONObject?): List<CalendarItem> {
    val data = root?.optJSONObject("data") ?: return emptyList()
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
        var heat = ""
        o.optJSONArray("rec_tags")?.let { a ->
            for (k in 0 until a.length()) {
                val node = a.optJSONObject(k) ?: continue
                val t = node.optString("text").ifEmpty { node.optString("content") }
                if (t.isNotBlank()) {
                    heat = t
                    break
                }
            }
        }
        if (heat.isEmpty()) {
            val plays = sd?.optLong("play_cnt", 0L) ?: 0L
            heat = if (plays > 0) com.hongguotv.pad.formatCount(plays.toString()) + "播放"
            else o.optString("expected_rec_text")
        }
        out.add(
            CalendarItem(
                seriesId = sid,
                title = title,
                cover = sd?.optString("cover").orEmpty().ifEmpty { o.optString("cover") },
                desc = sd?.optString("video_desc").orEmpty().ifEmpty { o.optString("item_desc") },
                heat = heat,
                score = sd?.optString("score").orEmpty(),
                category = o.optString("category").ifEmpty { sd?.optString("category").orEmpty() },
                episodeCount = sd?.optInt("episode_cnt", 0) ?: o.optInt("episode_cnt", 0),
                timeSec = ts,
                online = o.optBoolean("is_online", false),
                subscribed = o.optBoolean("has_subscribed", false),
            ),
        )
    }
    return out
}

/** 日期格：「今天 / 明天 / M月D日」+「周几 · 当天条数」。 */
@Composable
internal fun CalendarDateCell(date: String, active: Boolean, count: Int?, onClick: () -> Unit) {
    val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
    val d = runCatching { java.time.LocalDate.parse(date, fmt) }.getOrNull()
    val today = java.time.LocalDate.now()
    val label = when {
        d == null -> date
        d == today -> "今天"
        d == today.plusDays(1) -> "明天"
        else -> "${d.monthValue}月${d.dayOfMonth}日"
    }
    val weekday = d?.dayOfWeek?.let {
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[it.value - 1]
    }.orEmpty()
    Column(
        Modifier
            .width(76.dp)
            .clip(Radius.chip)
            .background(if (active) Palette.accent.copy(alpha = 0.16f) else Palette.panel)
            .border(
                1.dp,
                if (active) Palette.accent.copy(alpha = 0.45f) else Palette.line,
                Radius.chip,
            )
            .clickable { onClick() }
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = if (active) Palette.accentHover else Palette.text,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (count != null && count > 0) "$weekday · $count" else weekday,
            color = if (active) Palette.accentHover.copy(alpha = 0.85f) else Palette.faint,
            fontSize = 10.sp,
        )
    }
}

/**
 * 日历行：封面 + 标题/评分/上线状态 + 分类·集数 + 热度 + 两行简介，
 * 右侧是「MM-dd HH:mm」发布时间 + 播放/预约（旧版 render_calendar_row 同信息量）。
 */
@Composable
internal fun CalendarRow(item: CalendarItem, onClick: () -> Unit, onPlay: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    var reserved by remember(item.seriesId) { mutableStateOf(item.subscribed) }
    val scope = rememberCoroutineScope()
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
        RemoteImage(
            item.cover,
            Modifier.width(88.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)),
            fallback = item.title,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.title, color = Palette.text, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                item.score.takeIf { it.isNotBlank() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Palette.star, modifier = Modifier.size(12.dp))
                        Text(it, color = Palette.star, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            val meta = listOfNotNull(
                item.category.takeIf { it.isNotBlank() },
                if (item.episodeCount > 0) "全 ${item.episodeCount} 集" else null,
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.heat.isNotBlank()) HeatBadge(item.heat)
            item.desc.takeIf { it.isNotBlank() }?.let {
                Text(
                    it, color = Palette.dim.copy(alpha = 0.8f), fontSize = 12.sp,
                    lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        RowActions(
            onPlay = onPlay,
            onDetail = onClick,
            timeLabel = if (item.timeSec > 0) {
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date(item.timeSec))
            } else null,
            upcoming = !item.online,
            reserved = reserved,
            onReserve = {
                scope.launch {
                    val target = !reserved
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { ShelfRepository.toggle(ShelfKind.Reserve, item.seriesId, target) }.getOrDefault(false)
                    }
                    if (ok) reserved = target
                }
            },
        )
    }
}

// ---------------------------------------------------------------- 历史
