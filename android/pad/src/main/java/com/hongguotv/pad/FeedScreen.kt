package com.hongguotv.pad

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 刷剧（Pad / TV 共用）：
 *
 * 整屏连播一部短剧，换剧不换页——和 PC 的刷剧页同一套数据（`Api.firstPage/feedNext`，
 * tab 顺序同 PC：漫剧 / 真人剧 / 推荐，默认推荐）。
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
fun FeedScreen(onOpenDetail: (String) -> Unit, onExit: () -> Unit) {
    // 与 PC 一致：漫剧 / 真人剧 / 推荐（默认推荐）
    val feedTypes = listOf("3", "4", "2")
    val isTv = Store.appContext.packageName.endsWith("nativeapp")
    var tab by remember { mutableStateOf(UiMemo.feedTab) }
    var cards by remember { mutableStateOf(UiMemo.feedCards) }
    var cursor by remember { mutableStateOf(UiMemo.feedCursor) }
    var page by remember { mutableStateOf(UiMemo.feedPage) }
    var loading by remember { mutableStateOf(cards.isEmpty()) }
    var moreLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tab) {
        // 从详情/播放返回：列表和位置都还在，直接接回来，不重新拉、不回到第一部
        if (cards.isNotEmpty() && tab == UiMemo.feedTab) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        cards = emptyList()
        cursor = 0
        page = null
        val p = withContext(Dispatchers.IO) {
            runCatching { Api.firstPage(feedTypes[tab]) }.getOrNull()
        }
        cards = p?.cards.orEmpty()
        page = p
        loading = false
    }

    // 位置随变随记：进详情时这一页会离开组合，这些状态只能存在单例里
    LaunchedEffect(tab, cursor, cards, page) {
        UiMemo.feedTab = tab
        UiMemo.feedCursor = cursor
        UiMemo.feedCards = cards
        UiMemo.feedPage = page
    }

    /** 换剧：-1 上一部 / +1 下一部；走到列表尾自动续一页。 */
    fun step(dir: Int) {
        val next = cursor + dir
        if (next < 0) return
        if (next >= cards.size) {
            val c = page ?: return
            if (!c.hasMore || moreLoading) return
            moreLoading = true
            scope.launch {
                val np = withContext(Dispatchers.IO) {
                    runCatching {
                        Api.feedNext(
                            feedTypes[tab], c.cellId, c.planId, c.sessionId, c.nextOffset,
                            cards.map { it.seriesId },
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

    BackHandler { onExit() }

    val current = cards.getOrNull(cursor)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            loading -> CircularProgressIndicator(
                color = Red,
                modifier = Modifier.align(Alignment.Center),
            )
            current == null -> Text(
                "没有拿到推荐，检查网络后重试",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> key(current.seriesId) {
                PlayerScreen(
                    seriesId = current.seriesId,
                    startIndex = 1,
                    onBack = onExit,
                    // 刷剧模式不切集（↑↓ 已被换剧占用）；连播仍由播放器内部接管
                    onSwitch = { _, _ -> },
                    onDetail = { onOpenDetail(current.seriesId) },
                    feedMode = true,
                    feedLabel = "第 ${cursor + 1} 部 · 共 ${cards.size} 部",
                    onFeedStep = { dir -> step(dir) },
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
                            .clickable { tab = i }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}
