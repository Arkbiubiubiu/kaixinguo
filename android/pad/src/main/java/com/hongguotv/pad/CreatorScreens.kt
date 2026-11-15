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
internal fun hmOf(ts: Long): String =
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
internal val PC_FILTER_ROW_ORDER = listOf(
    "genre", "category_dim_theme", "category_dim_role", "category_dim_epoch",
    "sort", "gender", "creation_status", "online_time",
)

internal fun normalizeFilterRows(rows: MutableList<FilterRow>) {
    // 只做排序，不再补「长度」行：那三个 id（duration_0_60…）上游根本没有，
    // 选中必空，等于给用户埋了个「一点就 0 条」的假筛选项（PC 端没有这行）。
    val sorted = rows.sortedBy {
        val i = PC_FILTER_ROW_ORDER.indexOf(it.key)
        if (i < 0) PC_FILTER_ROW_ORDER.size else i
    }
    rows.clear()
    rows.addAll(sorted)
}
