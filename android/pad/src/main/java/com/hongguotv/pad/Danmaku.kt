package com.hongguotv.pad

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.roundToInt
import kotlin.random.Random

/** 一条弹幕横穿一屏的时长（墙钟，不随倍速变、暂停即冻结，与 Windows 版同口径）。 */
private const val CROSS_MS = 9000f

/** 同泳道相邻两条的最小入场间隔。 */
private const val LANE_BUSY_MS = 1400L

/** 泳道间距（dp）。 */
private const val LANE_GAP_DP = 10f

/** 基准字号（dp）：取画面高度/30 夹在这一段里，再乘用户字号倍率。 */
private const val DM_FONT_MIN = 15f
private const val DM_FONT_MAX = 22f

/** 最少几条泳道——再矮的画面也别只剩两三行。 */
private const val MIN_LANES = 4


/**
 * 弹幕里的表情码 `[笑哭]` → 真 emoji。
 *
 * Windows 版是把码换成打包的 webp 图；安卓端直接映射到系统 emoji 更轻，
 * 也不会出现「码原样显示成 [笑哭]」的破相文本。表外的码直接去掉。
 */
private val EMOJI = mapOf(
    "笑哭" to "😂", "捂脸" to "🤦", "流泪" to "😭", "大哭" to "😭", "微笑" to "🙂",
    "大笑" to "😄", "呲牙" to "😁", "偷笑" to "🤭", "害羞" to "☺️", "酷" to "😎",
    "赞" to "👍", "比心" to "🫰", "爱心" to "❤️", "花心" to "😍", "鼓掌" to "👏",
    "加油" to "💪", "狗头" to "🐶", "打脸" to "🤕", "白眼" to "🙄", "发呆" to "😳",
    "思考" to "🤔", "惊喜" to "😲", "恐惧" to "😱", "委屈" to "🥺", "斜眼" to "😏",
    "灵机一动" to "💡", "吐舌" to "😜", "耶" to "✌️", "尴尬" to "😅", "嘘" to "🤫",
    "what" to "❓", "问号" to "❓", "微笑面对" to "🙂",
)

private fun renderEmoji(raw: String): String {
    if (!raw.contains('[')) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '[') {
            val end = raw.indexOf(']', i + 1)
            // 码最多四个字，超长的当普通文本，别把整句吞掉
            if (end in (i + 2)..(i + 6)) {
                val name = raw.substring(i + 1, end)
                val hit = EMOJI[name]
                if (hit != null) {
                    out.append(hit)
                    i = end + 1
                    continue
                }
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

private class DmFlight(
    val text: String,
    val lane: Int,
    val startFlow: Long,
    val width: Float,
)

/**
 * 弹幕引擎（纯逻辑，不含 Composable）。
 *
 * 三个关键口径，与 Windows 版逐条对齐：
 * 1. 位移用**墙钟时间**（`flowMs` 只在播放中累加），所以暂停即冻结、
 *    2 倍速播放时弹幕不会跟着快一倍；
 * 2. 泳道数 = 画面高度 × 显示区域 / (字号 + 10dp)，入场挑「最早空出来」的那条；
 * 3. 时间轴跳变（seek / 换集 / 换流）重置入场游标并清屏，不补发积压弹幕——
 *    否则一拖进度条就是满屏弹幕糊脸。
 */
private class DmEngine {
    val flights = ArrayList<DmFlight>()
    var flowMs = 0L
    var fontPx = 18f
    var laneStep = 30f

    private var spawned = 0
    /** 最近发过的弹幕文本 → 时间：同一句连发只留一条（短剧弹幕区刷屏严重） */
    private val recent = HashMap<String, Long>()
    private var lanes = MIN_LANES
    private var laneFree = LongArray(MIN_LANES)
    private var lastPos = -1L
    private var lastNs = 0L
    private var bound: List<DmItem> = emptyList()

    fun step(
        nowNs: Long,
        player: ExoPlayer?,
        items: List<DmItem>,
        size: IntSize,
        densityDpi: Float,
        fontScale: Float,
        densityRate: Float,
        area: Float,
        paint: Paint,
    ) {
        // 数据换了（换集 / 新拉一窗）：列表只增不改，游标不用回退
        if (items !== bound) {
            bound = items
            if (spawned > items.size) spawned = items.size
        }
        val pos = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)
        val playing = runCatching { player?.isPlaying == true }.getOrDefault(false)
        val dt = if (lastNs == 0L) 0L else ((nowNs - lastNs) / 1_000_000L).coerceIn(0L, 100L)
        lastNs = nowNs
        if (size.width <= 0 || size.height <= 0) return

        // 字号跟着画面高度走：1080p 电视与 720p 平板看到的观感一致
        val hDp = size.height / densityDpi
        fontPx = ((hDp / 30f).coerceIn(DM_FONT_MIN, DM_FONT_MAX) * fontScale)
            .coerceAtLeast(10f) * densityDpi
        laneStep = fontPx + LANE_GAP_DP * densityDpi
        val need = maxOf(MIN_LANES, (size.height * area / laneStep).toInt())
        if (need != lanes) {
            lanes = need
            laneFree = LongArray(need)
        }

        // 首次进入 / seek / 换集：游标落到当前时刻，清空在飞的
        if (lastPos < 0 || pos < lastPos - 60 || pos > lastPos + 1500) {
            reset(pos, items)
            return
        }
        if (playing) flowMs += dt
        lastPos = pos

        paint.textSize = fontPx
        while (spawned < items.size && items[spawned].offsetMs <= pos) {
            val d = items[spawned]
            spawned++
            if (d.text.isBlank()) continue
            // 同一句 2 秒内只放一条：否则几十条同文本会同时铺满所有泳道
            val last = recent[d.text]
            if (last != null && flowMs - last < 2_000) continue
            recent[d.text] = flowMs
            // 密度：按比例随机丢弃（要的是同屏条数变稀，丢哪条无所谓）
            if (densityRate < 0.999f && Random.nextFloat() > densityRate) continue
            var pick = 0
            for (i in 1 until lanes) if (laneFree[i] < laneFree[pick]) pick = i
            laneFree[pick] = flowMs + LANE_BUSY_MS
            val shown = renderEmoji(d.text)
            if (shown.isBlank()) continue
            flights.add(DmFlight(shown, pick, flowMs, paint.measureText(shown)))
        }
        if (flights.isNotEmpty()) flights.removeAll { flowMs - it.startFlow > CROSS_MS.toLong() }
    }

    private fun reset(pos: Long, items: List<DmItem>) {
        spawned = items.indexOfFirst { it.offsetMs > pos }.let { if (it < 0) items.size else it }
        flights.clear()
        recent.clear()
        laneFree = LongArray(lanes)
        flowMs = 0L
        lastPos = pos
    }
}

/**
 * 弹幕渲染层：铺在视频上、不接指针、不进焦点。
 *
 * 用 Canvas + 原生 Paint 画（而不是每条一个 Text 组件）：同屏几十条时
 * 组件重排会掉帧，画布只做一次 drawText；投影用 setShadowLayer 一次成型，
 * 和 Windows 版「600 字重 + 柔和投影」是同一观感。
 */
@Composable
fun DanmakuLayer(
    player: ExoPlayer?,
    items: List<DmItem>,
    enabled: Boolean,
    opacity: Float,
    fontScale: Float,
    density: Float,
    area: Float,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    val dpi = LocalDensity.current.density
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setShadowLayer(3f, 0f, 1.5f, 0xD9000000.toInt())
        }
    }
    val engine = remember { DmEngine() }
    var frame by remember { mutableLongStateOf(0L) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val itemsNow by rememberUpdatedState(items)
    val alpha = opacity.coerceIn(0.1f, 1f)

    LaunchedEffect(player) {
        while (true) {
            val playing = runCatching { player?.isPlaying == true }.getOrDefault(false)
            withFrameNanos { ns ->
                engine.step(ns, player, itemsNow, size, dpi, fontScale, density, area, paint)
            }
            // 写 state 只为触发画布重绘（draw 里读它，不会带来重组）
            frame++
            // 暂停/缓冲时弹幕本来就不动，还按 60fps 重绘等于白烧 GPU、让电视一直醒着。
            // 改成 5fps 低频唤醒（暂停中拖动进度也能在 200ms 内跟上），复播立刻回逐帧。
            if (!playing) delay(200)
        }
    }

    Canvas(modifier.fillMaxSize().onSizeChanged { size = it }) {
        @Suppress("UNUSED_EXPRESSION") frame
        paint.alpha = (alpha * 255).roundToInt()
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            val w = this.size.width
            for (f in engine.flights) {
                val p = ((engine.flowMs - f.startFlow).toFloat() / CROSS_MS).coerceIn(0f, 1f)
                if (p <= 0f) continue
                val x = w - p * (w + f.width)
                val baseline = f.lane * engine.laneStep - paint.fontMetrics.ascent
                nc.drawText(f.text, x, baseline, paint)
            }
        }
    }
}
