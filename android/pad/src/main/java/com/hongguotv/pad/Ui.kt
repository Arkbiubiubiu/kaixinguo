package com.hongguotv.pad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** 页面右上角的小标题栏。 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Spacer(Modifier.width(12.dp))
            Text(subtitle, color = TextDim, fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 通用小胶囊按钮。 */
@Composable
fun TvChip(
    text: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    /**
     * 加大号：排行榜的筛选面板里用。
     * 那里面板是整屏铺开的，13sp/34dp 的规格在电视上离得远、字偏小；
     * 顶部那两行分类/子榜是横向排的，保持原尺寸不挤。
     */
    large: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(if (large) 40.dp else 34.dp)
            // 与 PC 一致：选中是红薄底 + 红字 + 细红边，实心红只给动作按钮。
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Red.copy(alpha = 0.16f) else Panel)
            .border(
                1.dp,
                if (selected) Red.copy(alpha = 0.45f) else Color(0x1AFFFFFF),
                RoundedCornerShape(8.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = if (large) 20.dp else 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Color(0xFFFF6B6D) else TextDim,
            fontSize = if (large) 15.sp else 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/**
 * 海报卡片：封面 + 集数 + 标题 + 热度 + 标签。
 *
 * [cardHeight] 传了就用「封面吃剩余高度」的弹性布局——首页海报墙靠它把两行
 * 正好铺满一屏，第二行不会被屏幕边缘切掉；不传（详情页相关推荐等）就按
 * 3:4 固定比例排。文字块高度固定，封面自己伸缩，所以换任何机型都不会半行悬空。
 */
@Composable
fun SeriesCardView(
    card: SeriesCard,
    modifier: Modifier = Modifier,
    showHeat: Boolean = false,
    cardHeight: androidx.compose.ui.unit.Dp? = null,
    /**
     * 传了就只显示这一行灰色元信息（如「共125集 · 21万播放」），
     * 不再画火焰热度和标签——详情页相关推荐按 Windows 版就是这个样式。
     */
    metaLine: String? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .then(
                if (cardHeight != null) {
                    Modifier.height(cardHeight)
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .clickable { onClick() }
            .padding(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    if (cardHeight != null) {
                        Modifier.weight(1f)
                    } else {
                        Modifier.aspectRatio(3f / 4f)
                    },
                )
                .clip(RoundedCornerShape(8.dp))
                .background(CoverPlaceholder),
        ) {
            AsyncImage(
                model = card.cover,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 评分角标（右上，橙字暗底）
            val scoreVal = card.score.toFloatOrNull() ?: 0f
            if (scoreVal > 0f) {
                Text(
                    card.score,
                    color = Star,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            // 封面底部压一层暗渐变：集数白字压在任何封面上都看得清
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color(0x00000000), Color(0x8C000000))),
                    ),
            )
            // 集数：封面左下角纯文字（对齐原版卡片，比「全 N 集」小药丸干净）
            if (card.episodeCount > 0) {
                Text(
                    "${card.episodeCount} 集",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    // 半透明小底：很多封面本身就印着「共 96 集」，纯白字压上去会糊成一片
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 5.dp)
                        .background(Color(0x59000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            } else {
                // 兜底：排行榜名次 / 季角标
                val badge = card.rankText ?: card.heat
                if (!badge.isNullOrBlank()) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(badge, color = Color.White, fontSize = 11.sp)
                    }
                }
            }
            // 状态角标（左上，红底）：红果首发 / 漫剧 / 爆更 等
            // statusText 定义在 core 模块，跨模块不能智能转换，先取到局部变量
            val statusLabel = card.statusText
            if (!statusLabel.isNullOrBlank() && statusLabel.toIntOrNull() == null) {
                Text(
                    statusLabel,
                    color = Color.White,
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Red, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (card.upcoming) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Red, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("待上线", color = Color.White, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        // 标题给两行：很多剧名把季数写在最后（「…第二季」），压成一行时会被省略号吃掉，
        // 用户根本分不清是第几季。minLines 也设 2，是为了短标题同样占两行高度，
        // 同一排卡片高度一致，网格不会错位。
        Text(
            card.title,
            color = TextMain,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // 不要再套 height：固定 36dp 时两行放不下（字体上下留白），
            // 结果第二行被挤掉、第一行末尾变省略号。minLines 已经保证两行高度。
        )
        if (metaLine != null) {
            // 相关推荐：一行「共 N 集 · N 万播放」，和 Windows 版一致；
            // 传空串 = 这行留空（待上线的卡把这行让给下面的预约按钮），
            // 同时跳过热度/标签，别在预约按钮上面再挂一排小胶囊。
            if (metaLine.isBlank()) return@Column
            Spacer(Modifier.height(3.dp))
            Text(
                metaLine,
                color = Color(0x99FFFFFF),
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            return@Column
        }
        // 热度：🔥 + 数字 + 单位。单位由 Api.toCard 决定（热度 / 播放），
        // 这里不再自己拼——拼错就会出现「把播放量当热度」的问题。
        if (card.playText.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(17.dp)) {
                FlameIcon()
                Spacer(Modifier.width(3.dp))
                Text(
                    card.playText,
                    color = Warm,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
        // 标签条：最多 3 个小暗色胶囊
        if (card.tags.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.height(17.dp),
            ) {
                card.tags.filter { !it.startsWith("{") }.take(3).forEach { tag ->
                    Text(
                        tag,
                        color = TextDim,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .background(Color(0x0FFFFFFF), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 预约按钮：**卡片外的独立控件**，样式对齐 Windows 版（浅描边宽胶囊 + 铃铛）。
 *
 * 为什么不做成卡片里的小胶囊：TV 版靠遥控器焦点导航，嵌套在卡片 clickable
 * 里的子按钮既拿不到清晰焦点、也容易被父级吃掉确认键；独立出来之后
 * Pad 精确点、TV 上下左右都能选中并按确认。
 *
 * 只有待上线（没有分集）的季才显示；点了走官方 uncover_subscribe，
 * 和详情页主按钮、左侧「预约」页共用同一份服务端数据。
 */
@Composable
fun ReserveButton(
    reserved: Boolean,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 34.dp,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(if (reserved) Color(0x0DFFFFFF) else Color(0x14FFFFFF))
            .border(1.dp, Color(0x4DFFFFFF), RoundedCornerShape(8.dp))
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(13.dp)) {
            val w = size.width
            val h = size.height
            val c = if (reserved) Color(0x66FFFFFF) else Color(0xE6FFFFFF)
            val sw = 1.3f * density
            // 铃身：上方拱形 + 下沿一条横线
            drawRoundRect(
                color = c,
                topLeft = Offset(w * 0.2f, h * 0.16f),
                size = Size(w * 0.6f, h * 0.5f),
                cornerRadius = CornerRadius(w * 0.3f, h * 0.3f),
                style = Stroke(width = sw),
            )
            drawLine(c, Offset(w * 0.12f, h * 0.68f), Offset(w * 0.88f, h * 0.68f), strokeWidth = sw)
            // 铃舌
            drawCircle(c, radius = w * 0.1f, center = Offset(w * 0.5f, h * 0.84f), style = Stroke(width = sw))
        }
        Spacer(Modifier.width(7.dp))
        Text(
            if (reserved) "已预约" else "预约",
            color = if (reserved) Color(0x99FFFFFF) else Color(0xF2FFFFFF),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 热度火焰小图标（橙色实心）。卡片热度行用 11dp，hero/详情页用更大号。 */
@Composable
fun FlameIcon(iconSize: androidx.compose.ui.unit.Dp = 11.dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.5f, h)
            cubicTo(w * 0.02f, h * 0.66f, w * 0.18f, h * 0.4f, w * 0.44f, 0f)
            cubicTo(w * 0.5f, h * 0.3f, w * 0.6f, h * 0.36f, w * 0.68f, h * 0.32f)
            cubicTo(w * 0.98f, h * 0.58f, w * 0.88f, h, w * 0.5f, h)
            close()
        }
        drawPath(p, Warm)
    }
}

/** 横向行卡片（排行榜、历史、预约用）。 */
@Composable
fun SeriesRowView(
    card: SeriesCard,
    index: Int? = null,
    tail: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .clickable { onClick() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
                Text(
                    index.toString(),
                    color = if (index <= 3) Red else TextDim,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        Box(
            Modifier
                .width(72.dp)
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(6.dp))
                .background(CoverPlaceholder),
        ) {
            AsyncImage(
                model = card.cover,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                card.title,
                color = TextMain,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            val line = card.desc
                ?: card.tags.take(3).joinToString(" · ").ifBlank { null }
                ?: card.playText.ifBlank { null }
            if (line != null) {
                Text(line, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!tail.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(tail, color = TextDim, fontSize = 12.sp)
        }
    }
}

/**
 * 统一的加载圈：一圈很淡的底环 + 一段红色圆弧在转。
 *
 * 之前各处直接用 `CircularProgressIndicator(size = 18~30dp, stroke = 2dp)`，
 * 电视上离得远，加上圆弧某一瞬间只剩一小段，看着就是一个"红点"，不像在加载。
 * 这里统一成「底环 + 更粗的圆头弧」，尺寸也放大，一眼能看出在转。
 *
 * 角度用 `withFrameNanos` 自己按帧算，**不用 `rememberInfiniteTransition`**：
 * 电视上普遍把「动画时长缩放」关成 0（有些还锁死），Compose 的动画时钟在这种
 * 设置下完全不派帧，圈就定在第一帧不动了——用户看到的就是"圈卡在那"。
 * withFrameNanos 走的是屏幕刷新节拍，不受那个开关影响。
 */
@Composable
fun LoadingSpinner(size: Dp = 44.dp, stroke: Dp = 4.dp) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val periodNs = 900_000_000L // 900ms 一圈
        var startNs = -1L
        while (true) {
            withFrameNanos { now ->
                if (startNs < 0) startNs = now
                angle = ((now - startNs) % periodNs).toFloat() / periodNs * 360f
            }
        }
    }
    Canvas(Modifier.size(size)) {
        val w = stroke.toPx()
        val r = (this.size.minDimension - w) / 2f
        // 底环：淡淡一圈，让"在转"这件事有个参照
        drawCircle(Color(0x22FFFFFF), radius = r, style = Stroke(w))
        drawArc(
            color = Red,
            startAngle = angle,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(w / 2f, w / 2f),
            size = Size(this.size.width - w, this.size.height - w),
            style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}

/** 整块区域的加载态：大圈 + 一行字。 */
@Composable
fun LoadingBlock(text: String = "正在加载…") {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LoadingSpinner(size = 54.dp, stroke = 5.dp)
        Spacer(Modifier.height(14.dp))
        Text(text, color = TextDim, fontSize = 15.sp)
    }
}

/** 加载中 / 空 / 错误的统一占位。 */
@Composable
fun StatusBox(
    loading: Boolean,
    error: String?,
    empty: Boolean = false,
    emptyText: String = "这里还没有内容",
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            loading -> LoadingBlock()
            error != null -> Text(error, color = TextDim, fontSize = 14.sp)
            empty -> Text(emptyText, color = TextDim, fontSize = 14.sp)
        }
    }
}

/** 封面加载前的渐变占位，避免一片纯黑。 */
val CoverFallback = Brush.linearGradient(
    listOf(AvatarPlaceholder, Bg),
)

@Composable
fun SectionGap(h: Int = 14) = Spacer(Modifier.height(h.dp))
