@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.kaixinguo.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hongguotv.pad.Notice

/**
 * 普通公告横幅（顶部居中，可关）。点击 × → onDismiss（调用方负责
 * NoticeCenter.dismissed.add(id) 并置空状态，本次运行不再弹）。
 */
@Composable
internal fun NoticeBanner(notice: Notice, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.padding(top = 12.dp)
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xE6201A1F))
                .border(1.dp, Palette.line, RoundedCornerShape(10.dp))
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (notice.title.isNotBlank()) {
                    Text(
                        notice.title,
                        color = Palette.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                if (notice.text.isNotBlank()) {
                    Text(
                        notice.text,
                        color = Palette.dim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(50)).clickable { onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Palette.dim, fontSize = 14.sp)
            }
        }
    }
}

/**
 * 强制更新弹窗：全屏半透明遮罩（消费点击/滚轮）+ 居中卡片。
 * 用 FocusRequester 抢焦点 + onPreviewKeyEvent 全吞，真正做到「不可关」：
 * 不会把 Esc/空格/方向键漏给下层播放器等。url 为空/非法时给「知道了」兜底，不锁死。
 */
@Composable
internal fun ForceUpdateDialog(
    notice: Notice,
    onOpenUrl: () -> Unit,
    onFallbackClose: () -> Unit,
) {
    val urlOk = runCatching { java.net.URI(notice.url) }.getOrNull()
        ?.let { it.scheme == "http" || it.scheme == "https" } == true
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }

    Box(
        Modifier.fillMaxSize()
            .background(Color(0xD9000000))
            .focusRequester(fr)
            .focusable()
            .onPreviewKeyEvent { true }            // 键盘全吞（Esc/空格/方向）
            .clickable { }                          // 吃掉点击（背景不穿透）
            .onPointerEvent(PointerEventType.Scroll) { }, // 吃掉滚轮
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.panel)
                .border(1.dp, Palette.line, RoundedCornerShape(14.dp))
                .padding(24.dp),
        ) {
            Text(
                notice.title.ifBlank { "需要更新" },
                color = Palette.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            if (notice.text.isNotBlank()) {
                Text(notice.text, color = Palette.dim, fontSize = 13.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(18.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (urlOk) {
                    PrimaryButton("去更新", Icons.Filled.Download) { onOpenUrl() }
                } else {
                    // url 空/非法：绝不锁死，给「知道了」+「打开官网」
                    Text(
                        "更新地址暂不可用，可到官网下载最新版。",
                        color = Palette.faint,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("打开官网") { openOfficialSite() }
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton("知道了", null) { onFallbackClose() }
                }
            }
        }
    }
}
