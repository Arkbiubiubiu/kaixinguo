package com.kaixinguo.pc

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 打赏收款码：随包放在 src/main/resources/donate_wechat.png（微信收款码）。
 *
 * 界面只展示静态收款码，不接任何支付 SDK，钱从付款人微信直接进作者零钱；
 * 没放图时显示「待配置」，绝不画一个假二维码骗扫。
 */
private val donateQr: ImageBitmap? by lazy {
    runCatching {
        val stream = object {}.javaClass.getResourceAsStream("/donate_wechat.png")
            ?: return@runCatching null
        stream.use { javax.imageio.ImageIO.read(it).toComposeImageBitmap() }
    }.getOrNull()
}

private const val REPO_URL = "https://github.com/Arkbiubiubiu/kaixinguo"

/**
 * 「打赏作者」浮层。入口在侧栏设置上方；点击遮罩 / 右上角 / Esc 关闭。
 *
 * 文案口径参考同类开源客户端：先讲清楚免费与自费成本，再轻量请求，
 * 明确「纯自愿、不解锁任何内容」，不诱导、不打断播放。
 */
@Composable
fun DonateOverlay(onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.scrimStrong)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    onClose(); true
                } else false
            }
            .focusRequester(focus)
            .focusable()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(462.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.panel)
                .border(1.dp, Palette.line, RoundedCornerShape(20.dp))
                // 吃掉卡片上的点击，别让遮罩的「点空白关闭」穿透
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {}
                .padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(Palette.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.LocalCafe,
                        contentDescription = null,
                        tint = Palette.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "打赏作者",
                        color = Palette.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "开心果 · 免费无广告",
                        color = Palette.faint,
                        fontSize = 11.sp,
                    )
                }
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = Palette.dim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "这个客户端不收费、不插广告，也不用登录。解析、签名、服务器和带宽，" +
                    "都是作者自己掏钱在撑。如果它帮你省了点时间，欢迎请我喝杯咖啡。",
                color = Palette.dim,
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.size(238.dp).clip(RoundedCornerShape(16.dp))
                    .background(Color.White).padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                    val qr = donateQr
                    if (qr != null) {
                        Image(
                            bitmap = qr,
                            contentDescription = "微信收款码",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.LocalCafe,
                                contentDescription = null,
                                tint = Color(0xFFB3ADB5),
                                modifier = Modifier.size(38.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("收款码暂未配置", color = Color(0xFF8A848C), fontSize = 12.sp)
                        }
                    }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "微信扫一扫 · 金额随心",
                color = Palette.text,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "纯自愿 · 不打赏也照样用全部功能",
                color = Palette.faint,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "不想花钱？去 GitHub 点个 Star 也是支持",
                    color = Palette.faint,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "打开 GitHub",
                    color = Palette.accent,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            runCatching {
                                java.awt.Desktop.getDesktop().browse(java.net.URI(REPO_URL))
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "谢谢每一个愿意留下的人。",
                color = Palette.dim,
                fontSize = 11.5.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}
