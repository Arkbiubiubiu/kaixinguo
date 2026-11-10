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
import androidx.compose.ui.draw.clip
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

/** 控制台浮层：同一时间只开一个（对齐 Windows 的菜单互斥）。 */
enum class PlayerMenu { None, Speed, Definition, Episodes, DmSettings }

/** 倍速档位与 Windows 版一致。 */
private val RATES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

/** 控制条文字：白字 + 柔和投影，压在画面上不靠底板也看得清。 */
private val BarText = TextStyle(
    fontSize = 12.sp,
    color = Color(0xE6FFFFFF),
    shadow = Shadow(Color(0xE6000000), Offset(0f, 1f), 3f),
)

/** 控制条高度（进度条 + 按钮行 + 内边距），浮层据此避让。 */
private val BAR_HEIGHT = 72.dp

/**
 * 播放控制台。
 *
 * 结构 1:1 对 Windows 版 `player-controls`：
 * 底上一层细进度条，下面一行「播放/上一集/下一集 · 时间 · 弹幕输入框 —— 倍速/清晰度/选集/弹幕设置/弹幕开关」。
 * 与 Windows 的差异只有按需求裁掉的入口：音量、横竖屏、无边框、全屏、点赞、收藏、评论。
 */
@Composable
fun PlayerChrome(
    menu: PlayerMenu,
    onMenu: (PlayerMenu) -> Unit,
    visible: Boolean,
    position: Long,
    duration: Long,
    playing: Boolean,
    speed: Float,
    definitions: List<Int>,
    definition: Int,
    episodes: List<Episode>,
    index: Int,
    danmakuOn: Boolean,
    dmOpacity: Float,
    dmFontScale: Float,
    dmDensity: Float,
    dmArea: Float,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSpeed: (Float) -> Unit,
    onDefinition: (Int) -> Unit,
    onPickEpisode: (Int) -> Unit,
    onToggleDanmaku: () -> Unit,
    onDmSettings: (Float, Float, Float, Float) -> Unit,
    onSendDanmaku: (String) -> Unit,
    onEditingChange: (Boolean) -> Unit = {},
    /** TV 版：按钮放大、隐藏弹幕输入框（遥控器打不了字） */
    isTv: Boolean = false,
    /** TV 版进入按钮行时，焦点落在播放键上 */
    playFocus: FocusRequester? = null,
    /** 最右侧「详情」按钮：跳详情页。传 null 就不显示这个按钮（Pad 走顶部胶囊） */
    onDetail: (() -> Unit)? = null,
    /** 当前是不是软件解码：按钮上显示「软解」还是「硬解」 */
    softDecode: Boolean = false,
    /** 点一下切一次：硬解 → 软解 → 硬解 */
    onToggleDecode: () -> Unit = {},
    /** 真实焦点是否停在控制台（按钮行或浮层）上。PlayerScreen 据此决定方向键是快进退还是移焦点。 */
    onChromeFocus: (Boolean) -> Unit = {},
    /** 控制条出现时是否把焦点直接送进按钮行（TV：只有按 ↓ 进来时才要） */
    focusOnShow: Boolean = true,
) {
    // 控制条收起时把浮层一并收掉，否则会孤零零挂在画面上
    LaunchedEffect(visible) { if (!visible) onMenu(PlayerMenu.None) }

    // 焦点状态只有 Compose 说了算：按钮行/浮层任一有焦点，就算「在控制台上」。
    // 以前靠 PlayerScreen 自己记一个布尔，跟真实焦点两条线，一旦不同步方向键就全乱。
    // 回调里立刻上报（不走 LaunchedEffect），否则按 ↓ 之后马上按 → 还会被当成快进。
    var barFocus by remember { mutableStateOf(false) }
    var panelFocus by remember { mutableStateOf(false) }

    // 浮层第一项：打开后直接把焦点送进去，不再靠 moveFocus 猜方向
    val panelFirst = remember { FocusRequester() }

    // 按钮是随控制条一起出现的，requestFocus 必须等这一帧 layout 结束（节点已 placed），
    // 否则 FocusRequester 未初始化直接抛异常 —— 之前就是被 runCatching 吞掉，
    // 焦点永远留在全屏 Box 上，遥控器根本进不来。
    LaunchedEffect(visible, isTv, focusOnShow) {
        if (isTv && visible && focusOnShow && menu == PlayerMenu.None) {
            withFrameNanos { }
            runCatching { playFocus?.requestFocus() }
        }
    }

    // 浮层开了把焦点送进去，关了送回按钮行（不回全屏层，否则又要按一次 ↓）
    var prevMenu by remember { mutableStateOf(PlayerMenu.None) }
    LaunchedEffect(menu) {
        if (isTv) {
            withFrameNanos { }
            withFrameNanos { }
            if (menu != PlayerMenu.None) {
                runCatching { panelFirst.requestFocus() }
            } else if (prevMenu != PlayerMenu.None) {
                runCatching { playFocus?.requestFocus() }
            }
        }
        prevMenu = menu
    }

    Box(Modifier.fillMaxSize()) {
        // 点空白关浮层（只在浮层打开时挂，且不进焦点，不干扰遥控器）
        if (menu != PlayerMenu.None) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onMenu(PlayerMenu.None) } },
            )
        }

        if (visible) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x00000000), Color(0x59000000)),
                        ),
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 12.dp),
            ) {
                ScrubBar(
                    position = position,
                    duration = duration,
                    modifier = Modifier.fillMaxWidth(),
                    onSeek = onSeek,
                )
                Spacer(Modifier.height(6.dp))
                // TV 上按钮要放大：3 米外看 34x32 的图标既难点也难认
                val btnW = if (isTv) 50.dp else 34.dp
                val btnH = if (isTv) 40.dp else 32.dp
                val barText = if (isTv) BarText.copy(fontSize = 15.sp) else BarText
                Row(
                    Modifier
                        .fillMaxWidth()
                        // 1D 焦点组：左右在按钮间按顺序走，不会跳到画面里别的控件去
                        .focusGroup()
                        .onFocusChanged {
                            barFocus = it.hasFocus
                            onChromeFocus(it.hasFocus || panelFocus)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isTv) 8.dp else 4.dp),
                ) {
                    IconBtn(
                        width = btnW,
                        height = btnH,
                        focusRequester = playFocus,
                        onClick = onTogglePlay,
                    ) { c -> if (playing) PauseGlyph(c) else PlayGlyph(c) }
                    IconBtn(width = btnW, height = btnH, enabled = index > 1, onClick = onPrev) { c ->
                        SkipGlyph(c, forward = false)
                    }
                    IconBtn(width = btnW, height = btnH, enabled = index < episodes.size, onClick = onNext) { c ->
                        SkipGlyph(c, forward = true)
                    }
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "${fmtTime(position)} / ${fmtTime(duration)}",
                        style = barText.copy(fontFamily = FontFamily.Monospace),
                    )
                    if (!isTv) {
                        Spacer(Modifier.width(8.dp))
                        DmSendBox(onSend = onSendDanmaku, onEditingChange = onEditingChange)
                    }

                    Spacer(Modifier.weight(1f))

                    TextBtn(
                        text = "${trimRate(speed)}x",
                        height = btnH,
                        fontSize = if (isTv) 15.sp else 12.sp,
                        onClick = { onMenu(toggle(menu, PlayerMenu.Speed)) },
                    ) { c -> GaugeGlyph(c) }
                    TextBtn(
                        text = if (definition > 0) "${definition}P" else "自动",
                        enabled = definitions.isNotEmpty(),
                        height = btnH,
                        fontSize = if (isTv) 15.sp else 12.sp,
                        onClick = { onMenu(toggle(menu, PlayerMenu.Definition)) },
                    ) { c -> ScreenGlyph(c) }
                    // 解码方式：硬解/软解一键切。绿块花屏时不用退出到设置，直接点它
                    TextBtn(
                        text = if (softDecode) "软解" else "硬解",
                        height = btnH,
                        fontSize = if (isTv) 15.sp else 12.sp,
                        onClick = onToggleDecode,
                    ) { c -> ChipGlyph(c) }
                    TextBtn(
                        text = if (episodes.isEmpty()) "选集" else "选集 · 共 ${episodes.size} 集",
                        height = btnH,
                        fontSize = if (isTv) 15.sp else 12.sp,
                        onClick = { onMenu(toggle(menu, PlayerMenu.Episodes)) },
                    ) { c -> ListGlyph(c) }
                    IconBtn(width = btnW, height = btnH, active = menu == PlayerMenu.DmSettings, onClick = {
                        onMenu(toggle(menu, PlayerMenu.DmSettings))
                    }) { c -> DmGlyph(c, gear = true) }
                    IconBtn(width = btnW, height = btnH, active = danmakuOn, onClick = onToggleDanmaku) { c ->
                        DmGlyph(c, slash = !danmakuOn)
                    }
                    // 详情放最右：顶部那枚胶囊在电视上是遥控器够不到的装饰，
                    // 真正能用的入口收进这一排，跟其他按钮一样靠方向键走、确定键点。
                    if (onDetail != null) {
                        TextBtn(
                            text = "详情",
                            height = btnH,
                            fontSize = if (isTv) 15.sp else 12.sp,
                            onClick = onDetail,
                        ) { c -> InfoGlyph(c) }
                    }
                }
            }
        }

        // 浮层统一贴右下、抬到控制条上沿之上（与 Windows 的「贴按钮向上弹」同位置）
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = BAR_HEIGHT + 6.dp)
                .onFocusChanged {
                    panelFocus = it.hasFocus
                    onChromeFocus(it.hasFocus || barFocus)
                },
            horizontalAlignment = Alignment.End,
        ) {
            when (menu) {
                PlayerMenu.Speed -> PanelSurface(120.dp) {
                    PanelTitle("播放速度")
                    RATES.forEachIndexed { i, r ->
                        MenuRow(
                            "${trimRate(r)}x",
                            checked = kotlin.math.abs(speed - r) < 0.01f,
                            tv = isTv,
                            focusRequester = if (i == 0) panelFirst else null,
                        ) {
                            onSpeed(r)
                            onMenu(PlayerMenu.None)
                        }
                    }
                }

                PlayerMenu.Definition -> PanelSurface(150.dp) {
                    PanelTitle("清晰度")
                    MenuRow("自动", checked = definition <= 0, tv = isTv, focusRequester = panelFirst) {
                        onDefinition(0)
                        onMenu(PlayerMenu.None)
                    }
                    definitions.forEach { h ->
                        MenuRow("${h}P", checked = definition == h, tv = isTv) {
                            onDefinition(h)
                            onMenu(PlayerMenu.None)
                        }
                    }
                }

                PlayerMenu.Episodes -> EpisodePanel(
                    episodes = episodes,
                    index = index,
                    tv = isTv,
                    focusFirst = panelFirst,
                    onPick = {
                        onMenu(PlayerMenu.None)
                        onPickEpisode(it)
                    },
                )

                PlayerMenu.DmSettings -> DmSettingsPanel(
                    opacity = dmOpacity,
                    fontScale = dmFontScale,
                    density = dmDensity,
                    area = dmArea,
                    tv = isTv,
                    focusFirst = panelFirst,
                    onChange = onDmSettings,
                )

                PlayerMenu.None -> Unit
            }
        }
    }
}

private fun toggle(current: PlayerMenu, want: PlayerMenu): PlayerMenu =
    if (current == want) PlayerMenu.None else want

private fun trimRate(r: Float): String = if (r % 1f == 0f) r.toInt().toString() else r.toString()

/** 时间格式：0:43 / 12:03（与 Windows 的 formatDuration 一致）。 */
fun fmtTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

// ---------------- 基础控件 ----------------

/** 图标按钮：32×32 的点击区，常态透明，按下/聚焦靠统一焦点框。 */
@Composable
private fun IconBtn(
    enabled: Boolean = true,
    active: Boolean = false,
    width: Dp = 34.dp,
    height: Dp = 32.dp,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    Box(
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .size(width = width, height = height)
            // clickable 必须套在 clip 外面：焦点框画在 clickable 这一层，
            // 放里面会被内层的圆角裁剪吃掉（实测只剩一个浅色块，看不到红框）
            .clickable(enabled = enabled) { onClick() }
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        icon(
            when {
                !enabled -> Color(0x59FFFFFF)
                active -> Color.White
                else -> Color(0xD9FFFFFF)
            },
        )
    }
}

/** 图标 + 文字按钮（倍速 / 清晰度 / 选集），间距与字号照 Windows 的 h-8 text-xs。 */
@Composable
private fun TextBtn(
    text: String,
    enabled: Boolean = true,
    height: Dp = 32.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    Row(
        Modifier
            .height(height)
            .clickable(enabled = enabled) { onClick() }
            .clip(RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) Color(0xD9FFFFFF) else Color(0x59FFFFFF)
        icon(tint)
        Spacer(Modifier.width(5.dp))
        Text(
            text,
            style = BarText.copy(
                color = if (enabled) Color(0xE6FFFFFF) else Color(0x59FFFFFF),
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
            ),
        )
    }
}

/**
 * 细进度条：轨道白 20%、已播白 60%、白点滑块，按下/拖动实时跳转。
 *
 * 与 Windows 同款「平时隐身、上手好用」：轨道只有 2dp，触摸区给到 18dp。
 * Pad/TV 没有 hover，所以滑块常显——没有滑块根本不知道能拖。
 */
@Composable
private fun ScrubBar(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit,
) {
    val dur by rememberUpdatedState(duration)
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shown = (drag ?: frac).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier
            .height(18.dp)
            .pointerInput(dur) {
                if (dur <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    drag = (down.position.x / size.width).coerceIn(0f, 1f)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        val f = (ch.position.x / size.width).coerceIn(0f, 1f)
                        drag = f
                        onSeek((f * dur).toLong())
                    }
                    drag = null
                }
            },
    ) {
        val track = maxWidth
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color(0x33FFFFFF)),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(track * shown)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color(0xB3FFFFFF)),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = (track - 10.dp) * shown)
                .size(10.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** 弹幕输入框（常驻控制条，对齐 Windows 的 DanmakuSendBox）。 */
@Composable
private fun DmSendBox(onSend: (String) -> Unit, onEditingChange: (Boolean) -> Unit = {}) {
    var text by remember { mutableStateOf("") }
    val submit = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            onSend(t)
            text = ""
        }
    }
    Row(
        Modifier
            .height(32.dp)
            .width(196.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0x26FFFFFF))
            .padding(start = 12.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(50) },
            singleLine = true,
            textStyle = BarText.copy(color = Color.White, fontFamily = FontFamily.Default),
            cursorBrush = SolidColor(Red),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onEditingChange(it.isFocused) },
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        Text("发个弹幕…", style = BarText.copy(color = Color(0x8CFFFFFF), fontFamily = FontFamily.Default))
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .height(24.dp)
                .clip(RoundedCornerShape(50))
                .background(if (text.isNotBlank()) Red else Color(0x33FFFFFF))
                .clickable(enabled = text.isNotBlank()) { submit() }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("发送", color = Color.White, fontSize = 11.sp)
        }
    }
}

// ---------------- 浮层 ----------------

@Composable
private fun PanelSurface(width: Dp, content: @Composable ColumnScope.() -> Unit) {
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
private fun PanelTitle(text: String) {
    Text(
        text,
        color = Color(0x8CFFFFFF),
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun MenuRow(
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
private fun EpisodePanel(
    episodes: List<Episode>,
    index: Int,
    tv: Boolean = false,
    focusFirst: FocusRequester? = null,
    onPick: (Int) -> Unit,
) {
    val grid = rememberLazyGridState()
    LaunchedEffect(index) {
        val row = (index - 1) / 8
        grid.scrollToItem((row - 3).coerceAtLeast(0) * 8)
        // 当前集滚出来之后才把焦点落到它上面：遥控器一开面板就有落点
        if (focusFirst != null) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { focusFirst.requestFocus() }
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
                            if (active && focusFirst != null) Modifier.focusRequester(focusFirst) else Modifier,
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
private fun DmSettingsPanel(
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
private fun TvSlider(
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
private fun DmSlider(label: String, value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
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

private fun DrawScope.lineStroke(w: Float = 1.6f) =
    Stroke(width = w * density, cap = androidx.compose.ui.graphics.StrokeCap.Round)

@Composable
private fun Glyph(color: Color, block: DrawScope.(Color) -> Unit) {
    Canvas(Modifier.size(17.dp)) { block(color) }
}

@Composable
private fun PlayGlyph(color: Color) = Glyph(color) { c ->
    val p = Path().apply {
        moveTo(size.width * 0.18f, size.height * 0.08f)
        lineTo(size.width * 0.92f, size.height * 0.5f)
        lineTo(size.width * 0.18f, size.height * 0.92f)
        close()
    }
    drawPath(p, c, style = Fill)
}

@Composable
private fun PauseGlyph(color: Color) = Glyph(color) { c ->
    val w = size.width * 0.24f
    val r = CornerRadius(2.dp.toPx(), 2.dp.toPx())
    drawRoundRect(c, topLeft = Offset(size.width * 0.2f, 0f), size = Size(w, size.height), cornerRadius = r)
    drawRoundRect(c, topLeft = Offset(size.width * 0.56f, 0f), size = Size(w, size.height), cornerRadius = r)
}

@Composable
private fun SkipGlyph(color: Color, forward: Boolean) = Glyph(color) { c ->
    val w = size.width
    val h = size.height
    if (forward) {
        val p = Path().apply {
            moveTo(w * 0.1f, h * 0.1f); lineTo(w * 0.68f, h * 0.5f); lineTo(w * 0.1f, h * 0.9f); close()
        }
        drawPath(p, c, style = Fill)
        drawRoundRect(c, topLeft = Offset(w * 0.76f, h * 0.1f), size = Size(w * 0.14f, h * 0.8f),
            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()))
    } else {
        val p = Path().apply {
            moveTo(w * 0.9f, h * 0.1f); lineTo(w * 0.32f, h * 0.5f); lineTo(w * 0.9f, h * 0.9f); close()
        }
        drawPath(p, c, style = Fill)
        drawRoundRect(c, topLeft = Offset(w * 0.1f, h * 0.1f), size = Size(w * 0.14f, h * 0.8f),
            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()))
    }
}

/** 倍速：仪表盘弧 + 指针。 */
@Composable
private fun GaugeGlyph(color: Color) = Glyph(color) { c ->
    val w = size.width
    val h = size.height
    drawArc(
        color = c,
        startAngle = 155f,
        sweepAngle = 230f,
        useCenter = false,
        topLeft = Offset(w * 0.08f, h * 0.18f),
        size = Size(w * 0.84f, h * 0.84f),
        style = lineStroke(),
    )
    drawLine(c, Offset(w * 0.5f, h * 0.55f), Offset(w * 0.74f, h * 0.3f), strokeWidth = 1.6f * density)
}

/** 清晰度：屏幕框 + 播放三角。 */
@Composable
private fun ScreenGlyph(color: Color) = Glyph(color) { c ->
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = c,
        topLeft = Offset(w * 0.05f, h * 0.12f),
        size = Size(w * 0.9f, h * 0.66f),
        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        style = lineStroke(),
    )
    val p = Path().apply {
        moveTo(w * 0.42f, h * 0.32f); lineTo(w * 0.64f, h * 0.45f); lineTo(w * 0.42f, h * 0.58f); close()
    }
    drawPath(p, c, style = Fill)
}

/** 解码方式：一颗芯片（硬解/软解共用同一个图标，文字区分）。 */
@Composable
private fun ChipGlyph(color: Color) = Glyph(color) { c ->
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = c,
        topLeft = Offset(w * 0.22f, h * 0.22f),
        size = Size(w * 0.56f, h * 0.56f),
        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        style = lineStroke(),
    )
    drawRoundRect(
        color = c,
        topLeft = Offset(w * 0.41f, h * 0.41f),
        size = Size(w * 0.18f, h * 0.18f),
        cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
    )
    val st = 1.4.dp.toPx()
    drawLine(c, Offset(w * 0.12f, h * 0.5f), Offset(w * 0.22f, h * 0.5f), st)
    drawLine(c, Offset(w * 0.78f, h * 0.5f), Offset(w * 0.88f, h * 0.5f), st)
    drawLine(c, Offset(w * 0.5f, h * 0.12f), Offset(w * 0.5f, h * 0.22f), st)
    drawLine(c, Offset(w * 0.5f, h * 0.78f), Offset(w * 0.5f, h * 0.88f), st)
}

/** 选集：播放列表（两条线 + 三角）。 */
@Composable
private fun ListGlyph(color: Color) = Glyph(color) { c ->
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = c,
        topLeft = Offset(w * 0.04f, h * 0.1f),
        size = Size(w * 0.72f, h * 0.8f),
        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        style = lineStroke(),
    )
    val p = Path().apply {
        moveTo(w * 0.3f, h * 0.34f); lineTo(w * 0.52f, h * 0.5f); lineTo(w * 0.3f, h * 0.66f); close()
    }
    drawPath(p, c, style = Fill)
    drawLine(c, Offset(w * 0.62f, h * 0.42f), Offset(w * 0.9f, h * 0.42f), strokeWidth = 1.5f * density)
    drawLine(c, Offset(w * 0.62f, h * 0.6f), Offset(w * 0.9f, h * 0.6f), strokeWidth = 1.5f * density)
}

/** 详情：圆圈里一个 i，小尺寸下比文字框更好认。 */
@Composable
private fun InfoGlyph(color: Color) = Glyph(color) { c ->
    val w = size.width
    val h = size.height
    drawCircle(
        color = c,
        radius = minOf(w, h) * 0.44f,
        center = Offset(w / 2, h / 2),
        style = lineStroke(),
    )
    drawCircle(color = c, radius = w * 0.055f, center = Offset(w / 2, h * 0.32f))
    drawLine(
        c, Offset(w / 2, h * 0.46f), Offset(w / 2, h * 0.72f),
        strokeWidth = 1.8f * density, cap = androidx.compose.ui.graphics.StrokeCap.Round,
    )
}

/**
 * 弹幕图标：屏幕框 + 两行飘字（设置态加齿轮角标，关闭态加斜线）。
 *
 * 不用「弹」字：控制条只有 32dp 高，汉字在这个尺寸（1080p 上约 16px）
 * 加粗后糊成一团，看不清是什么；几何图形在小尺寸下反而清楚。
 */
@Composable
private fun DmGlyph(color: Color, gear: Boolean = false, slash: Boolean = false) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.4f * density
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.1f, h * 0.18f),
            size = Size(w * 0.8f, h * 0.56f),
            cornerRadius = CornerRadius(2.5f * density, 2.5f * density),
            style = Stroke(width = stroke),
        )
        // 两行「飘过去的字」：一长一短，长短错开才像弹幕
        drawLine(
            color, Offset(w * 0.24f, h * 0.36f), Offset(w * 0.76f, h * 0.36f),
            strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color, Offset(w * 0.24f, h * 0.56f), Offset(w * 0.58f, h * 0.56f),
            strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        if (gear) {
            drawCircle(Color(0xF2000000), radius = w * 0.2f, center = Offset(w * 0.78f, h * 0.8f))
            drawCircle(color, radius = w * 0.18f, center = Offset(w * 0.78f, h * 0.8f), style = Stroke(stroke))
        }
        if (slash) {
            drawLine(
                color, Offset(w * 0.12f, h * 0.86f), Offset(w * 0.88f, h * 0.14f),
                strokeWidth = 1.8f * density, cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

