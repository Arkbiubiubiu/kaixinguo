package com.hongguotv.pad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放页单集评论面板（与 PC 的评论面板对齐）：
 * 列表 + 回复内联展开 + 发送框。
 *
 * TV 只读（遥控器打字太痛苦）：`readOnly = true` 时隐藏发送框和回复输入，只看。
 * 数据接口在 core：`Api.episodeComments / sendEpisodeComment / commentReplies / sendCommentReply`。
 */
@Composable
fun EpisodeCommentsPanel(
    vid: String,
    seriesId: String,
    readOnly: Boolean,
    onClose: () -> Unit,
) {
    var items by remember { mutableStateOf<List<EpisodeComment>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    // 当前展开回复的评论 id；null = 没展开
    var openId by remember { mutableStateOf<String?>(null) }
    var replies by remember { mutableStateOf<List<CommentReply>>(emptyList()) }
    var replyText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // TV：面板本身要拿焦点，才能用方向键滚列表（列表项没有可聚焦节点，
    // 不拿焦点的话整块面板方向键无响应 —— 遥控器只能看第一屏）。
    val listState = rememberLazyListState()
    val panelFocus = remember { FocusRequester() }
    val stepPx = with(LocalDensity.current) { 120.dp.toPx() }
    LaunchedEffect(Unit) {
        if (readOnly) runCatching { panelFocus.requestFocus() }
    }

    LaunchedEffect(vid) {
        loading = true
        val p = withContext(Dispatchers.IO) {
            runCatching { Api.episodeComments(vid, seriesId) }.getOrNull()
        }
        items = p?.items.orEmpty()
        total = p?.total ?: items.size
        error = if (p == null) "评论加载失败，检查网络后重试" else null
        loading = false
    }

    fun reload() {
        scope.launch {
            val p = withContext(Dispatchers.IO) {
                runCatching { Api.episodeComments(vid, seriesId) }.getOrNull()
            }
            if (p != null) {
                items = p.items
                total = p.total
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { onClose() }) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(420.dp)
                .fillMaxHeight()
                .background(Panel)
                .clickable(enabled = false) { }
                // 面板拿焦点：上下方向键滚动列表（一次 120dp），左右同上/下，
                // 返回键由播放页根节点处理（关面板）。触摸设备 UI 不受影响。
                .focusRequester(panelFocus)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionDown, Key.DirectionRight -> {
                            scope.launch { listState.animateScrollBy(stepPx) }
                            true
                        }
                        Key.DirectionUp, Key.DirectionLeft -> {
                            scope.launch { listState.animateScrollBy(-stepPx) }
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> true
                        else -> false
                    }
                }
                .focusable()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // 头部
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (total > 0) "评论 · $total" else "评论",
                    color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "关闭",
                    color = TextDim, fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(PanelHi)
                        .clickable { onClose() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))

            when {
                loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingSpinner(size = 26.dp, stroke = 2.5.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("加载中…", color = TextDim, fontSize = 13.sp)
                    }
                }
                error != null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(error!!, color = TextDim, fontSize = 13.sp)
                }
                items.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("还没有评论", color = TextDim, fontSize = 13.sp)
                }
                else -> LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.id }) { c ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    c.user.ifBlank { "匿名" },
                                    color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (c.time.isNotBlank()) {
                                    Text(c.time, color = TextDim, fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(c.text, color = TextMain, fontSize = 13.sp)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (c.likes.isNotBlank() && c.likes != "0") {
                                    Text("赞 ${c.likes}", color = TextDim, fontSize = 11.sp)
                                    Spacer(Modifier.width(10.dp))
                                }
                                if (c.replyCount > 0 || !readOnly) {
                                    Text(
                                        if (c.replyCount > 0) "回复 ${c.replyCount}" else "回复",
                                        color = Red, fontSize = 11.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                openId = if (openId == c.id) null else c.id
                                                replyText = ""
                                                replies = emptyList()
                                                if (openId == c.id) {
                                                    scope.launch {
                                                        val r = withContext(Dispatchers.IO) {
                                                            runCatching { Api.commentReplies(c.id) }.getOrNull()
                                                        }
                                                        if (openId == c.id) replies = r.orEmpty()
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (openId == c.id) {
                                Spacer(Modifier.height(6.dp))
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(PanelHi)
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (replies.isEmpty()) {
                                        Text("还没有回复", color = TextDim, fontSize = 12.sp)
                                    }
                                    replies.forEach { r ->
                                        Text(
                                            "${r.user.ifBlank { "匿名" }}：${r.text}",
                                            color = TextMain, fontSize = 12.sp,
                                        )
                                    }
                                    if (!readOnly) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            BasicTextField(
                                                value = replyText,
                                                onValueChange = { replyText = it.take(100) },
                                                textStyle = TextStyle(color = TextMain, fontSize = 12.sp),
                                                cursorBrush = SolidColor(Red),
                                                singleLine = true,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(30.dp)
                                                    .clip(RoundedCornerShape(15.dp))
                                                    .background(Color(0x22FFFFFF))
                                                    .padding(horizontal = 10.dp),
                                                decorationBox = { inner ->
                                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                                        if (replyText.isEmpty()) {
                                                            Text("回复…", color = TextDim, fontSize = 12.sp)
                                                        }
                                                        inner()
                                                    }
                                                },
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "发送",
                                                color = if (replyText.isBlank()) TextDim else Red,
                                                fontSize = 12.sp,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .clickable(enabled = replyText.isNotBlank()) {
                                                        val body = replyText.trim()
                                                        replyText = ""
                                                        scope.launch {
                                                            val sent = withContext(Dispatchers.IO) {
                                                                runCatching {
                                                                    Api.sendCommentReply(vid, seriesId, c.id, body)
                                                                }.getOrDefault(false)
                                                            }
                                                            if (sent) {
                                                                val r = withContext(Dispatchers.IO) {
                                                                    runCatching { Api.commentReplies(c.id) }.getOrNull()
                                                                }
                                                                replies = r ?: replies
                                                            }
                                                        }
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 发送框（TV 只读）
            if (!readOnly) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it.take(100) },
                        textStyle = TextStyle(color = TextMain, fontSize = 13.sp),
                        cursorBrush = SolidColor(Red),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(PanelHi)
                            .padding(horizontal = 14.dp),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                if (text.isEmpty()) Text("发一条评论…", color = TextDim, fontSize = 13.sp)
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (sending) "发送中…" else "发送",
                        color = if (text.isBlank() || sending) TextDim else Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(19.dp))
                            .background(if (text.isBlank() || sending) PanelHi else Red)
                            .clickable(enabled = text.isNotBlank() && !sending) {
                                val body = text.trim()
                                sending = true
                                scope.launch {
                                    val sent = withContext(Dispatchers.IO) {
                                        runCatching { Api.sendEpisodeComment(vid, seriesId, body) }
                                            .getOrNull()
                                    }
                                    sending = false
                                    if (sent != null) {
                                        text = ""
                                        items = listOf(sent) + items
                                        total += 1
                                    } else {
                                        // 发失败时保留输入内容并说明原因（以前清空 + 无提示）
                                        withContext(Dispatchers.Main) {
                                            runCatching {
                                                android.widget.Toast.makeText(
                                                    Store.appContext,
                                                    if (Store.loggedIn) "评论没发出去，检查网络后重试" else "登录后才能发评论",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}
