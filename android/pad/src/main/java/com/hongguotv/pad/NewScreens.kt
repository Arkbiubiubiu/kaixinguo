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
internal data class CalendarItem(
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
internal fun CalendarTab(onOpen: (SeriesCard) -> Unit, onPlay: (String) -> Unit) {
    val dayFmt = remember { java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA) }
    val stampFmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA) }
    val days = remember { mutableStateListOf<String>() }
    val byDay = remember { mutableStateMapOf<String, MutableList<CalendarItem>>() }
    var selected by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var dayLoading by remember { mutableStateOf(false) }
    // 日历首屏/单日失败要能重试：以前失败被当成「这天暂无上新」，用户以为真没内容
    var calFailed by remember { mutableStateOf(false) }
    var calRetry by remember { mutableStateOf(0) }
    var dayRetry by remember { mutableStateOf(0) }
    val dayError = remember { mutableStateMapOf<String, Boolean>() }
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

    LaunchedEffect(calRetry) {
        calFailed = false
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
        calFailed = days.isEmpty() && items.isEmpty()
        loading = false
    }

    // 选中没加载过的日期：带 target_date 重新请求（官方切日期的方式）
    LaunchedEffect(selected, dayRetry) {
        if (selected.isEmpty() || byDay.containsKey(selected)) return@LaunchedEffect
        dayLoading = true
        val j = withContext(Dispatchers.IO) {
            runCatching { Api.calendar(date = selected) }.getOrNull()
        }
        val list = parseDay(j)
        byDay[selected] = list.toMutableList()
        dayError[selected] = j == null
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
    if (calFailed && days.isEmpty()) {
        StatusBox(loading = false, error = "日历没加载出来（网络问题）", onRetry = { loading = true; calRetry++ })
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
                        fontSize = 12.sp,
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
            dayItems.isEmpty() && dayError[selected] == true -> StatusBox(
                loading = false, error = "这天的上新没加载出来（网络问题）",
                onRetry = { dayError[selected] = false; byDay.remove(selected); dayRetry++ },
            )
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
                                    runCatching { ShelfRepository.toggle(ShelfKind.Reserve, row.seriesId, true) }.getOrDefault(false)
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
internal fun CalendarRow(
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
            // 电视上整行不再可点：行内已经有「播放/详情/预约」三个按钮，整行再吃一次点击
            // 会让遥控器永远选不中里面那颗「预约」（嵌套 clickable 的焦点被父级吃掉）
            .clickable(enabled = !isTv) { onOpen() }
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
