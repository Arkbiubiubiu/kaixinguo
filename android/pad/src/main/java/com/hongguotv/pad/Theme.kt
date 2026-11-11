package com.hongguotv.pad

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

val Red = Color(0xFFFF4D4F)
val Bg = Color(0xFF0F0D0F)
val Panel = Color(0xFF1B161A)
val PanelHi = Color(0xFF261F24)
val TextMain = Color(0xFFF2F2F5)
val TextDim = Color(0xFF9A9AA2)

/**
 * 业务语义色（**与 PC 的 CSS token 一一对应**）。
 *
 * 全量审计发现的漂移：曾同时存在 3 种橙（#FF9500 评分、#FF7A2F 热度、
 * #FFB13B 提示）、2 种绿（#5BD46B 登录、#4CC38A 上线）、2 个只差 1 的
 * 灰（#8A8A92/#8A8A93）——「看着不像同一个软件」的直接来源。
 * 所有散落硬编码一律收编到这里，新代码只准用常量。
 */
val Star = Color(0xFFFFC53D)      // 评分金（PC --star）
val Warm = Color(0xFFFFB26B)      // 热度橙（PC --warm）
val Hot = Color(0xFFFF4D4F)       // 点赞/预警红（PC --hot）
val Success = Color(0xFF4CC38A)   // 成功/在线绿（PC --success）
val CoverPlaceholder = Color(0xFF221C21)  // 封面占位底
val AvatarPlaceholder = Color(0xFF2A2328) // 头像/图标底
val RecordDim = Color(0xFF5A5A62)         // 备案号等极弱信息

private val scheme = darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    background = Bg,
    onBackground = TextMain,
    surface = Panel,
    onSurface = TextMain,
    surfaceVariant = PanelHi,
    onSurfaceVariant = TextDim,
)

/**
 * TV 适配根节点。
 *
 * 电视的 screenWidthDp 差别很大（1080p 上报 960dp、4K 上报 1920dp），
 * 直接按 dp 排版会有的机器字特别小、有的挤成一团。
 * 这里把逻辑宽度统一压到 1280dp：density = 实际像素宽 / 1280，
 * 所有界面都能按 1280x720 这套基准写，换机器不会走形。
 *
 * 注意：必须套在 MaterialTheme/Surface 里面——M3 会自己提供 LocalIndication，
 * 我们提供的红框焦点会被它盖掉。
 */
@Composable
fun TvRoot(content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current
    val d = LocalDensity.current
    val scale = ((cfg.screenWidthDp * d.density) / 1280f).coerceIn(1f, 3f)
    val tvDensity = Density(scale, d.fontScale)

    CompositionLocalProvider(LocalDensity provides tvDensity) {
        MaterialTheme(colorScheme = scheme) {
            Surface(color = Bg) {
                /**
                 * LocalIndication 必须提供在 MaterialTheme/Surface **里面**。
                 *
                 * 原来写在外层，M3 的 MaterialTheme 会自己再提供一份 ripple，
                 * 把红框盖掉 —— 结果遥控器焦点在哪个按钮上完全看不出来（实测确实如此：
                 * 焦点节点拿得到，屏幕上却没有任何描边）。
                 */
                // 红框焦点只给 TV：Pad 是触屏，点一下就冒红框属于干扰
                if (Store.appContext.packageName.endsWith("nativeapp")) {
                    CompositionLocalProvider(LocalIndication provides TvFocusIndication) {
                        content()
                        // 焦点框统一画在这一层（整屏重绘，避免电视上旧框擦不掉）
                        TvFocusFrameOverlay()
                    }
                } else {
                    content()
                }
            }
        }
    }
}

/**
 * 焦点归属表：全局只记一个「当前焦点框该画在谁身上」。
 *
 * 为什么不是每个节点各记一个 boolean：
 * 电视上实测会出现「遥控器划过的地方每格都留着白框」——某个节点收不到
 * FocusInteraction.Unfocus（节点被复用/重建、焦点被程序化转移时都可能漏），
 * 它自己那个 boolean 就永远是 true，白框永久留在屏上，划过多少格积累多少格。
 * 改成唯一 owner 之后，屏幕上最多只有一个框，且不依赖 Unfocus 是否送达。
 */
/**
 * 焦点归属表：全局只记一个「当前该画框的是谁」。
 *
 * 就算某个控件漏收 FocusInteraction.Unfocus，也只有 owner 会画框，
 * 不会出现「划过多少格就留多少个框」。
 */
/**
 * 焦点框的「唯一状态」：当前该画在屏幕的哪个矩形上（root 像素坐标）。
 *
 * 只存一个矩形：屏幕上永远只有一个框，不存在「划过多少格留多少框」。
 */
private object TvFocusFrame {
    val rect = mutableStateOf<Rect?>(null)

    /** 当前上报坐标的那个控件；只有它能改/清 rect */
    var owner: Any? = null

    fun publish(who: Any, r: Rect) {
        owner = who
        rect.value = r
    }

    fun clear(who: Any) {
        if (owner === who) {
            owner = null
            rect.value = null
        }
    }
}

/**
 * 统一焦点框：只要控件带 clickable / focusable 就自动有白框。
 *
 * 这里**不画**任何东西，只负责两件事：订阅焦点、把屏幕坐标报给 TvFocusFrame。
 * 画框由 TvRoot 里那层整屏 Canvas 做（见 TvFocusFrameOverlay）。
 *
 * 为什么不在条目自己的 draw 里画：
 * 之前两版都这么做，模拟器上没问题，电视上却是「划过的地方每格都留着白框」——
 * 条目节点的重绘失效在电视上不落地，旧框像素永远擦不掉。整屏浮层每次都是整屏重绘，
 * 不存在局部没擦到的可能。
 */
object TvFocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FocusNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = -1

    private class FocusNode(
        private val source: InteractionSource,
    ) : Modifier.Node(), LayoutAwareModifierNode {
        private val self = this
        private var focused = false
        // 只缓存「当前这次挂载」量出来的位置：节点被复用搬到别的条目时，
        // 旧坐标必须作废，否则框会画在上一个条目的位置上（实测滚动后就成了看不到框）
        private var bounds: Rect? = null

        override fun onPlaced(coordinates: LayoutCoordinates) {
            val r = coordinates.boundsInRoot()
            bounds = r
            if (focused) TvFocusFrame.publish(self, r)
        }

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is FocusInteraction.Focus -> {
                            focused = true
                            bounds?.let { TvFocusFrame.publish(self, it) }
                        }
                        is FocusInteraction.Unfocus -> {
                            focused = false
                            TvFocusFrame.clear(self)
                        }
                    }
                }
            }
        }

        override fun onDetach() {
            // 坐标作废；如果自己是当前 owner 就先撤框，
            // 真只是被复用换位置的话，紧接着的 onPlaced 会重新报一次（focused 仍为 true）
            bounds = null
            TvFocusFrame.clear(self)
        }
    }
}

/**
 * 整屏焦点框浮层：必须放在 TvRoot 的内容之上（同窗口、同原点）。
 * 坐标来自 LayoutCoordinates.boundsInRoot()，与这里一致。
 *
 * 焦点态升级（对齐 Netflix/YouTube TV 的手感）：
 * 1. **框滑行**——焦点移动时框从旧位置 140ms 平滑滑到新位置（Animatable +
 *    FastOutSlowInEasing），不再是「消失→原地出现」的硬切；
 * 2. **分层柔影**——白核底下垫三层由实到虚的暗色描边，白框浮在海报上
 *    多了一圈「悬浮感」；
 * 3. **淡入淡出**——框出现/消失带 90/120ms alpha 过渡，进出列表边缘
 *    不再生硬。
 * 架构不变：仍是中央单框整屏 Canvas（电视重绘不落地的坑见上文），
 * 动画只发生在这一层，条目零改动。
 */
@Composable
fun TvFocusFrameOverlay() {
    // 组合期读 rect：切换焦点 → 重组 → 整屏重录一次，旧框必然被擦掉
    val target = TvFocusFrame.rect.value
    // 浮层自己在窗口里的位置。正常情况下是 (0,0)，但电视若有系统边距/overscan，
    // 不校正就会整体偏一段 —— 这里按自身位置做偏移抵消
    var origin by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }

    // 首次出现不滑入（避免从 (0,0) 飞入的怪动作）：第一次出现时长 0ms 直接落位
    var everShown by remember { mutableStateOf(false) }
    val slideSpec = tween<Rect>(if (everShown) 140 else 0, easing = FastOutSlowInEasing)
    if (target != null && !everShown) everShown = true

    // 框位置/透明度全走 animate*AsState：返回 State，组合内回读必然可观察
    // （core Animatable.value 手动编排在这个整屏 Canvas 场景下重组不落地，
    //   实测框不显示——animate*AsState 是状态驱动的可靠形态）
    val position = animateValueAsState(
        targetValue = target ?: Rect.Zero,
        typeConverter = Rect.VectorConverter,
        animationSpec = slideSpec,
        label = "tvFocusFrameRect",
    )
    val alpha = animateFloatAsState(
        targetValue = if (target != null) 1f else 0f,
        animationSpec = tween(if (target != null) 90 else 120),
        label = "tvFocusFrameAlpha",
    )

    val r = position.value
    val a = alpha.value
    Canvas(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
    ) {
        if (r == null || r.width <= 0f || r.height <= 0f || a <= 0.01f) return@Canvas
        val w = 3.dp.toPx()
        val radius = CornerRadius(10.dp.toPx())
        val topLeft = Offset(r.left - origin.x + w / 2f, r.top - origin.y + w / 2f)
        val sz = Size(r.width - w, r.height - w)
        if (sz.width <= 0f || sz.height <= 0f) return@Canvas
        // 分层柔影：由实到虚的三圈暗色描边垫底（原来只有一圈），白框
        // 浮在海报上有「抬起来」的悬浮感。每层 alpha 乘上框的整体 a。
        drawRoundRect(
            color = Color(0x22000000).copy(alpha = 0.13f * a),
            topLeft = topLeft,
            size = sz,
            cornerRadius = radius,
            style = Stroke(width = w + 6.dp.toPx()),
        )
        drawRoundRect(
            color = Color(0x44000000).copy(alpha = 0.27f * a),
            topLeft = topLeft,
            size = sz,
            cornerRadius = radius,
            style = Stroke(width = w + 3.dp.toPx()),
        )
        drawRoundRect(
            color = Color(0x66000000).copy(alpha = a),
            topLeft = topLeft,
            size = sz,
            cornerRadius = radius,
            style = Stroke(width = w + 2.dp.toPx()),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = a),
            topLeft = topLeft,
            size = sz,
            cornerRadius = radius,
            style = Stroke(width = w),
        )
    }
}

/**
 * Dialog 内专用的焦点框：直接画在控件自己的 draw 里。
 *
 * Compose 的 Dialog 是独立 window —— 弹窗里控件的 boundsInRoot() 是「对话框自己的
 * 坐标系」，而 TvFocusFrame 的整屏 Canvas 在主窗口。把对话框坐标拿到主窗口去画，
 * 框就会落在主窗口里完全不相干的位置（实测落在设置页的「默认倍速」行上），
 * 弹窗内的按钮反而没框，真焦点在哪个按钮上完全看不出来。
 */
object TvDialogIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        DialogFocusNode(interactionSource)

    override fun equals(other: Any?) = other === this
    override fun hashCode() = -2
}

private class DialogFocusNode(private val source: InteractionSource) :
    Modifier.Node(), androidx.compose.ui.node.DrawModifierNode {
    // 用 snapshot state：draw() 里读了它，焦点变化会自动触发重绘
    private var focused by androidx.compose.runtime.mutableStateOf(false)

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                }
            }
        }
    }

    // 样式与主窗口的整屏浮层一致：3dp 白框 + 底下一圈半透明黑
    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val w = 3.dp.toPx()
        // 圆角自适应：固定 10dp 在弹窗的小按钮上会占掉小半个高度，
        // 看起来像个椭圆（用户实测反馈）。小控件用小圆角，大控件维持 10dp。
        val radius = CornerRadius(minOf(10.dp.toPx(), size.minDimension / 4f))
        val topLeft = Offset(w / 2f, w / 2f)
        val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
        if (sz.width <= 0f || sz.height <= 0f) return
        drawRoundRect(
            color = Color(0x66000000),
            topLeft = topLeft,
            size = sz,
            cornerRadius = radius,
            style = Stroke(width = w + 2.dp.toPx()),
        )
        drawRoundRect(
            color = Color.White,
            topLeft = topLeft,
            size = sz,
            cornerRadius = radius,
            style = Stroke(width = w),
        )
    }
}

private typealias ContentDrawScope = androidx.compose.ui.graphics.drawscope.ContentDrawScope
