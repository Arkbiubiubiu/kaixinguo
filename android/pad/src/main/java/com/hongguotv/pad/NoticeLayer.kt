package com.hongguotv.pad

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 官方站点（兜底弹窗「打开官网」用）。 */
private const val OFFICIAL_SITE = "https://kxg.fabao.xin/"

/**
 * 播放可见状态：播放页进入时置 true、退出置 false。
 * 普通公告横幅据此在「正在播放/沉浸」时隐藏，不盖在视频上（与 PC 对齐）。
 * 用 Compose state 以便 NoticeLayer 能感知变化并重组。
 */
object NoticeGate {
    var playerImmersive by mutableStateOf(false)
}

/** 只认 http/https，避免 url 非空但非法/无对应应用 → 点了没反应又关不掉＝锁死。 */
private fun isValidUpdateUrl(url: String): Boolean {
    val u = url.trim()
    return u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
}

private fun openUrl(context: android.content.Context, url: String) {
    if (!isValidUpdateUrl(url)) return
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * 公告 + 强更 的 Android(TV/Pad) UI 层。必须挂在 TvRoot 里层（`TvRoot { Box { App(); NoticeLayer() } }`）。
 *
 *  - 普通公告：顶部横幅，右侧「✕」可聚焦、可关（NoticeCenter.dismissed）；播放/沉浸时隐藏。
 *  - 强制公告(url 合法)：居中弹窗，必须点「去更新」；BackHandler + 遮罩吃键双重拦截返回。
 *  - 强制公告(url 空/非法)：可关闭兜底弹窗（正文 +「打开官网」+「知道了/×」），不静默丢弃、不锁死。
 *  - 离线/失败：all 空 → 什么都不弹。
 * 纯 compose 基础组件（未引 material3 Dialog）；弹窗用「遮罩持焦 + 手动吃键」做焦点陷阱。
 */
@Composable
fun NoticeLayer() {
    val isTv = Store.appContext.packageName.endsWith("nativeapp")
    val platform = if (isTv) "tv" else "pad"
    val curCode = BuildConfig.VERSION_CODE
    val context = LocalContext.current

    var forceNotice by remember { mutableStateOf<Notice?>(null) }
    var forceBlocking by remember { mutableStateOf(false) }
    var bannerNotice by remember { mutableStateOf<Notice?>(null) }

    // 关键：Pad 系统返回手势走 OnBackPressedDispatcher，单靠 onPreviewKeyEvent 拦不住，
    // 会落到 App 的 BackHandler(出栈/双击退出) 绕过强更。这里在本层注册 BackHandler
    // （比 App 更晚注册 → 更优先）：强更期间吞掉返回；兜底弹窗允许关闭。
    BackHandler(enabled = forceNotice != null) {
        if (!forceBlocking) forceNotice = null
    }

    LaunchedEffect(Unit) {
        var guard = 0
        while (!NoticeCenter.loaded && guard < 120) {
            delay(150)
            guard++
        }
        val (f, b) = NoticeCenter.pick(curCode, platform)
        forceNotice = f
        forceBlocking = f != null && isValidUpdateUrl(f.url)
        bannerNotice = b
    }

    Box(Modifier.fillMaxSize()) {
        // 普通横幅：正在播放/沉浸时隐藏，别盖视频
        if (forceNotice == null && !NoticeGate.playerImmersive) {
            bannerNotice?.let { n ->
                NoticeBanner(n, isTv) {
                    NoticeCenter.dismissed.add(n.id)
                    bannerNotice = null
                }
            }
        }

        forceNotice?.let { n ->
            if (forceBlocking) {
                // 硬强更：url 合法 → 只给「去更新」，返回吞掉
                NoticeModal(
                    notice = n,
                    actions = listOf("去更新" to { openUrl(context, n.url) }),
                    onBack = { /* 吞掉，不锁死不退出 */ },
                )
            } else {
                // mustUpdate 但 url 空/非法 → 可关闭兜底弹窗，不静默失败、不锁死
                NoticeModal(
                    notice = n,
                    actions = listOf(
                        "打开官网" to { openUrl(context, OFFICIAL_SITE) },
                        "知道了" to { forceNotice = null },
                    ),
                    onBack = { forceNotice = null },
                    closable = true,
                )
            }
        }
    }
}

/** 普通公告：顶部横幅（状态栏/刘海 + TV 安全边内缩，防遮挡/切边）。 */
@Composable
private fun NoticeBanner(notice: Notice, isTv: Boolean, onClose: () -> Unit) {
    // 显示后 5 秒倒计时，归零自动关闭；「✕」仍可提前关
    var remain by remember(notice.id) { mutableStateOf(5) }
    LaunchedEffect(notice.id) {
        while (remain > 0) {
            delay(1_000)
            remain -= 1
        }
        onClose()
    }
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = if (isTv) 24.dp else 8.dp)
                .background(
                    Color(0xE6161A22),
                    RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (notice.title.isNotBlank()) {
                    Text(notice.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                }
                Text(notice.text, color = Color.White.copy(alpha = 0.86f), fontSize = 13.sp)
                if (remain > 0) {
                    Spacer(Modifier.height(3.dp))
                    Text("$remain 秒后自动关闭", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            // 「✕」clickable 即 focusable，加入焦点链：TV 上会拿到白框，Pad 直接点
            Box(
                Modifier
                    .clickable { onClose() }
                    .background(Color(0x33FFFFFF), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 公告弹窗（1~2 个动作）。遮罩持焦 + 手动吃键 = 焦点陷阱，盖住 App 的返回/双击退出。
 * 不依赖系统焦点（按钮用 idx 高亮），TV 遥控器 ↑↓←→ 选择、确定触发、返回见 onBack。
 */
@Composable
private fun NoticeModal(
    notice: Notice,
    actions: List<Pair<String, () -> Unit>>,
    onBack: () -> Unit,
    closable: Boolean = false,
) {
    val scrimFocus = remember { FocusRequester() }
    var idx by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        repeat(20) {
            withFrameNanos { }
            runCatching { scrimFocus.requestFocus() }
            delay(40)
        }
    }

    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .focusRequester(scrimFocus)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Back -> { onBack(); true }
                    Key.DirectionLeft, Key.DirectionUp -> {
                        if (actions.size > 1) idx = (idx - 1 + actions.size) % actions.size
                        true
                    }
                    Key.DirectionRight, Key.DirectionDown -> {
                        if (actions.size > 1) idx = (idx + 1) % actions.size
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                        actions.getOrNull(idx)?.second?.invoke()
                        true
                    }
                    else -> true // 吃掉方向键，焦点不许漏到下面 App
                }
            }
            // 吃掉触摸（Pad），阻止点到下面 App；indication=null 避免整屏涟漪
            .clickable(interactionSource = interaction, indication = null) { }
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.displayCutout),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .background(Color(0xFF1B1F27), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (closable) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier.clickable { onBack() }.padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Text(
                notice.title.ifBlank { "需要更新" },
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            )
            if (notice.text.isNotBlank()) {
                Text(notice.text, color = Color.White.copy(alpha = 0.86f), fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.forEachIndexed { i, pair ->
                    val on = i == idx
                    Box(
                        Modifier
                            .then(
                                if (on) Modifier.border(2.dp, Color(0xFFFF4A3D), RoundedCornerShape(50))
                                else Modifier
                            )
                            .background(
                                if (on) Color(0xCCFF4A3D) else Color(0xCC2A2F3A),
                                RoundedCornerShape(50),
                            )
                            .clickable { pair.second() }
                            .padding(horizontal = 28.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(pair.first, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(
                if (closable) "可点「知道了」或 ✕ 关闭" else "更新后才能继续使用",
                color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
            )
        }
    }
}
