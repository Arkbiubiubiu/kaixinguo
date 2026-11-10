@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.kaixinguo.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hongguotv.pad.Api
import com.hongguotv.pad.CreatorProfileInfo
import com.hongguotv.pad.SeriesCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 创作者主页（旧版 creator-home-page.tsx 规格）：
 * - 头部 = 头像 64px 圆 + 昵称 20px/600 + 简介 14px 两行
 * - 作品标题行「作品 N」（16/600）
 * - 作品卡 = **封面即卡片**（无下方标题区）：底部黑渐变上叠 标题/集数/🔥热度/播放数，
 *   hover 封面 80% 透明度，**点击起播**（与追更/历史同一套语义）
 * - 网格 2→3→4→5 列
 */
@Composable
fun CreatorScreen(userId: String, onBack: () -> Unit, onPlaySeries: (String) -> Unit) {
    var profile by remember(userId) { mutableStateOf<CreatorProfileInfo?>(null) }
    var cards by remember(userId) { mutableStateOf<List<SeriesCard>>(emptyList()) }
    var cursor by remember { mutableStateOf("") }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var moreLoading by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(userId) {
        loading = true
        withContext(Dispatchers.IO) {
            profile = runCatching { Api.creatorProfile(userId) }.getOrNull()
            val page = runCatching { Api.creatorWorks(userId, "") }.getOrNull()
            cards = page?.cards.orEmpty()
            cursor = page?.cursor.orEmpty()
            hasMore = page?.hasMore == true
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 32.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(text = "返回", icon = Icons.Filled.ArrowBack, onClick = onBack)
            }
            Spacer(Modifier.height(16.dp))

            // 头部：头像 64 + 昵称 20/600 + 简介 14 两行（资料失败不打断作品列表）
            profile?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(p.avatar, Modifier.size(64.dp).clip(RoundedCornerShape(50)))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(p.name, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.desc.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(p.desc, color = Palette.dim, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // 作品标题行「作品 N」
            Spacer(Modifier.height(24.dp))
            Text("作品 ${cards.size}", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

            Spacer(Modifier.height(12.dp))
            when {
                loading && cards.isEmpty() -> CenterLoading()
                cards.isEmpty() -> Text("还没有作品", color = Palette.dim, fontSize = 14.sp)
                else -> androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    cards.forEach { card ->
                        CreatorWorkCard(card) { onPlaySeries(card.seriesId) }
                    }
                }
            }

            if (hasMore) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    SecondaryButton(if (moreLoading) "加载中…" else "加载更多") {
                        if (moreLoading) return@SecondaryButton
                        moreLoading = true
                        scope.launch(Dispatchers.IO) {
                            val page = runCatching { Api.creatorWorks(userId, cursor) }.getOrNull()
                            withContext(Dispatchers.Main) {
                                if (page != null && page.cards.isNotEmpty()) {
                                    val known = cards.map { it.seriesId }.toSet()
                                    cards = cards + page.cards.filter { it.seriesId !in known }
                                    cursor = page.cursor
                                    hasMore = page.hasMore
                                } else {
                                    hasMore = false
                                }
                                moreLoading = false
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 作品卡：封面即卡片，底部黑渐变 + 标题/集数/热度/播放数；点击起播。 */
@Composable
private fun CreatorWorkCard(card: SeriesCard, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF191417))
            // hover 压暗（旧版 group-hover:bg-black/30）：此前只声明了 hovered
            // 状态但没有任何指针事件给它赋值，压暗层永远不显示
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() },
    ) {
        RemoteImage(
            card.cover,
            Modifier.width(200.dp).aspectRatio(3f / 4f),
            contentScale = ContentScale.Crop,
            fallback = card.title,
        )
        // hover 压暗
        Box(
            Modifier.matchParentSize().background(
                if (hovered) Color(0x4D000000) else Color.Transparent,
            ),
        )
        // 底部渐变信息层
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xD9000000))))
                .padding(10.dp, 8.dp),
        ) {
            Text(card.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (card.episodeCount > 0) Text("全 ${card.episodeCount} 集", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                card.heat?.takeIf { it.isNotBlank() }?.let {
                    HeatBadge(it)
                }
            }
        }
    }
}
