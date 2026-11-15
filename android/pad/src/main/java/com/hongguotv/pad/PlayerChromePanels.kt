package com.hongguotv.pad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


@Composable
internal fun PanelSurface(width: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xF2000000))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
            .padding(6.dp),
        content = content,
    )
}

@Composable
internal fun PanelTitle(text: String) {
    Text(
        text,
        color = Color(0x8CFFFFFF),
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
    )
}

@Composable
internal fun MenuRow(
    label: String,
    checked: Boolean,
    tv: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (tv) 40.dp else 32.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable { onClick() }
            .clip(RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = BarText.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = if (tv) 15.sp else 12.sp,
            ),
        )
        Spacer(Modifier.weight(1f))
        if (checked) Text("✓", color = Color.White, fontSize = 12.sp)
    }
}

/** 选集浮层：8 列数字网格，当前集高亮，点一下直接切。 */
@Composable
internal fun EpisodePanel(
    episodes: List<Episode>,
    index: Int,
    tv: Boolean = false,
    focusFirst: FocusRequester? = null,
    onPick: (Int) -> Unit,
) {
    val grid = rememberLazyGridState()
    val fm = LocalFocusManager.current
    var gotFocus by remember(index) { mutableStateOf(false) }
    LaunchedEffect(index) {
        val row = (index - 1) / 8
        grid.scrollToItem((row - 3).coerceAtLeast(0) * 8)
        // 当前集滚出来之后才把焦点落到它上面：遥控器一开面板就有落点
        if (focusFirst != null) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { focusFirst.requestFocus() }
            // 兜底：当前集那一格还没被组合出来时 requestFocus 会静默失败，
            // 遥控器就像失灵。等 250ms 还没拿到焦点，就把焦点推进网格里。
            kotlinx.coroutines.delay(250)
            if (!gotFocus) runCatching { fm.moveFocus(FocusDirection.Down) }
        }
    }
    Column(
        Modifier
            .width(560.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xF2000000))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("选集", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("共 ${episodes.size} 集", color = TextDim, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Fixed(8),
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(episodes, key = { i, it -> "${it.vid}_p$i" }) { i, ep ->
                val active = i == index - 1
                Box(
                    Modifier
                        .then(
                            if (active && focusFirst != null) {
                                Modifier
                                    .focusRequester(focusFirst)
                                    .onFocusChanged { if (it.isFocused) gotFocus = true }
                            } else Modifier,
                        )
                        .height(if (tv) 46.dp else 42.dp)
                        .clickable { onPick(i + 1) }
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) Red else PanelHi),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        // 集数编号：ep.title 是整部剧的名字，铺满一屏只会看到同一句
                        "${i + 1}",
                        color = if (active) Color.White else TextMain,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 弹幕显示设置：透明度 / 字号 / 密度 / 显示区域，四项与 Windows 面板一致。 */
@Composable
internal fun DmSettingsPanel(
    opacity: Float,
    fontScale: Float,
    density: Float,
    area: Float,
    tv: Boolean = false,
    focusFirst: FocusRequester? = null,
    onChange: (Float, Float, Float, Float) -> Unit,
) {
    PanelSurface(240.dp) {
        PanelTitle("弹幕设置")
        if (tv) {
            // M3 的 Slider 在电视上既拿不到焦点、左右键也调不动，
            // 所以 TV 换成自绘的可聚焦滑条：红框 + ←/→ 调 5%。
            TvSlider("不透明度", opacity, 0.1f, 1f, focusFirst) { onChange(it, fontScale, density, area) }
            TvSlider("字号", fontScale, 0.5f, 2f) { onChange(opacity, it, density, area) }
            TvSlider("密度", density, 0.05f, 1f) { onChange(opacity, fontScale, it, area) }
            TvSlider("显示区域", area, 0.25f, 1f) { onChange(opacity, fontScale, density, it) }
        } else {
            DmSlider("不透明度", opacity, 0.1f, 1f) { onChange(it, fontScale, density, area) }
            DmSlider("字号", fontScale, 0.5f, 2f) { onChange(opacity, it, density, area) }
            DmSlider("密度", density, 0.05f, 1f) { onChange(opacity, fontScale, it, area) }
            DmSlider("显示区域", area, 0.25f, 1f) { onChange(opacity, fontScale, density, it) }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** 电视用滑条：可聚焦，←/→ 每次 5%，焦点框由统一 Indication 画。 */
@Composable
internal fun TvSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    focusRequester: FocusRequester? = null,
    onValueChange: (Float) -> Unit,
) {
    val step = (max - min) / 20f
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // 用 clickable 而不是 focusable：只有 clickable 会把 LocalIndication 挂上去，
            // 红框才画得出来（focusable 能调数值但屏幕上完全看不出焦点在哪）。onClick 留空。
            .clickable { }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> { onValueChange((value - step).coerceIn(min, max)); true }
                    Key.DirectionRight -> { onValueChange((value + step).coerceIn(min, max)); true }
                    else -> false
                }
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color(0xD9FFFFFF), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("${(value * 100).toInt()}%", color = Color(0x8CFFFFFF), fontSize = 13.sp)
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x33FFFFFF)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(((value - min) / (max - min)).coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(Red),
            )
        }
    }
}

@Composable
internal fun DmSlider(label: String, value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextDim, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("${(value * 100).toInt()}%", color = Color(0x8CFFFFFF), fontSize = 11.sp)
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onValueChange,
            valueRange = min..max,
            modifier = Modifier.height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = Red,
                activeTrackColor = Red,
                inactiveTrackColor = Color(0x33FFFFFF),
            ),
        )
    }
}

// ---------------- 图标（照着 Windows 的 lucide 形态画，不引图标库） ----------------

