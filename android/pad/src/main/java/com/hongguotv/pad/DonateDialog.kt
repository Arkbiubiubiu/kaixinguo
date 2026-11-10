package com.hongguotv.pad

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.graphics.BitmapFactory

/**
 * 打赏收款码：随包放在 pad/src/main/res/raw/donate_wechat.png（微信收款码）。
 *
 * 界面只展示静态收款码，不接任何支付 SDK，钱从付款人微信直接进作者零钱；
 * 没放图时显示「待配置」，绝不画一个假二维码骗扫。
 */
private val donateQr: ImageBitmap? by lazy {
    runCatching {
        val stream = Store.appContext.resources.openRawResource(R.raw.donate_wechat)
        BitmapFactory.decodeStream(stream)?.asImageBitmap()
    }.getOrNull()
}

private const val REPO_URL = "https://github.com/Arkbiubiubiu/kaixinguo"

/** 咖啡杯小图标（打赏标头/缺图占位）：安卓没有 icons-extended，Canvas 手绘。 */
@Composable
private fun CafeGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val sw = 1.4.dp.toPx()
        val cr = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        // 杯身
        drawRoundRect(
            color,
            topLeft = Offset(size.width * 0.14f, size.height * 0.34f),
            size = Size(size.width * 0.54f, size.height * 0.46f),
            cornerRadius = cr,
            style = Stroke(sw),
        )
        // 杯柄
        drawArc(
            color,
            startAngle = -75f,
            sweepAngle = 220f,
            useCenter = false,
            topLeft = Offset(size.width * 0.6f, size.height * 0.42f),
            size = Size(size.width * 0.26f, size.height * 0.3f),
            style = Stroke(sw),
        )
        // 热气两缕
        drawLine(
            color,
            start = Offset(size.width * 0.33f, size.height * 0.08f),
            end = Offset(size.width * 0.33f, size.height * 0.24f),
            strokeWidth = sw,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color,
            start = Offset(size.width * 0.53f, size.height * 0.08f),
            end = Offset(size.width * 0.53f, size.height * 0.24f),
            strokeWidth = sw,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/**
 * 「打赏作者」弹窗。入口在侧栏「设置」上方。
 *
 * 文案口径参考同类开源客户端：先讲清楚免费与自费成本，再轻量请求，
 * 明确「纯自愿、不解锁任何内容」，不诱导、不打断播放。
 * TV 上 Dialog 是独立 window，主窗口那套 Canvas 焦点框在这里错位，
 * 弹窗内控件用本地焦点框（与 UpdateDialog 同一处理）。
 */
@Composable
fun DonateDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.LocalIndication provides TvDialogIndication,
        ) {
            Column(
                Modifier
                    .width(430.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Panel)
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(20.dp))
                    // 屏幕矮（TV 小窗/横屏小 Pad）时整体可滚，保证底部文案一定看得到
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                            .background(Red.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CafeGlyph(Red, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "打赏作者",
                            color = TextMain,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "开心果 · 免费无广告",
                            color = RecordDim,
                            fontSize = 11.sp,
                        )
                    }
                    Text(
                        "关闭",
                        color = TextDim,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onClose() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "这个客户端不收费、不插广告，也不用登录。解析、签名、服务器和带宽，" +
                        "都是作者自己掏钱在撑。如果它帮你省了点时间，欢迎请我喝杯咖啡。",
                    color = TextDim,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                // 二维码方块：高度定死 280dp，宽度取 min(弹窗内宽, 280dp) 居中。
                // 用 fillMaxWidth+aspectRatio 在矮屏上必然把弹窗顶出屏幕（实测），
                // 定高 + Fit 内容才稳。
                Box(
                    Modifier
                        .height(280.dp)
                        .width(280.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(10.dp),
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
                            CafeGlyph(Color(0xFFB3ADB5), Modifier.size(38.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("收款码暂未配置", color = Color(0xFF8A848C), fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "微信扫一扫 · 金额随心",
                    color = TextMain,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "纯自愿 · 不打赏也照样用全部功能",
                    color = RecordDim,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
                Spacer(Modifier.height(12.dp))
                // TV 上没法点链接，只展示网址让人记下来；平板同理，不放按钮误导
                Text(
                    "不想花钱？去 GitHub 点个 Star 也是支持\n$REPO_URL",
                    color = TextDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "谢谢每一个愿意留下的人。",
                    color = TextDim,
                    fontSize = 11.5.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
