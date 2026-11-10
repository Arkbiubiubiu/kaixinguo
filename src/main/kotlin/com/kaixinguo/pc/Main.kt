@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.kaixinguo.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hongguotv.pad.Api
import com.hongguotv.pad.App
import com.hongguotv.pad.FeedPage
import com.hongguotv.pad.FilterRow
import com.hongguotv.pad.Log
import com.hongguotv.pad.RankTabsel
import com.hongguotv.pad.SeriesCard
import com.hongguotv.pad.SeriesDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * 桌面客户端（自研）。
 *
 * 数据层来自共享模块 core（Api / 签名 / 解密），这里只负责界面与交互。
 * 排版按真实像素：1dp = 1px，窗口拉大只增加列数。
 */
fun main() = application {
    boot()
    val state = rememberWindowState(width = 1440.dp, height = 900.dp)
    // 无边框（旧版 decorations:false 同语义）：系统白条不画，自绘窗口钮+拖拽区
    Window(
        onCloseRequest = ::exitApplication,
        title = "开心果",
        state = state,
        undecorated = true,
        transparent = false,
    ) {
        val awt = java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>().firstOrNull()
        // 窗口最小化 → 播放暂停（回到前台不自动续播）
        androidx.compose.runtime.DisposableEffect(awt) {
            if (awt == null) return@DisposableEffect onDispose { }
            val listener = java.awt.event.WindowStateListener { ev ->
                AppLifecycle.inBackground.value = (ev.newState and java.awt.Frame.ICONIFIED) != 0
            }
            awt.addWindowStateListener(listener)
            onDispose { awt.removeWindowStateListener(listener) }
        }
        // 竖屏 / 小窗：窗口几何由 UiMode 驱动（旧版 use-portrait-window / use-mini-window 同语义）
        val portrait = UiMode.portrait.value
        val mini = UiMode.mini.value
        androidx.compose.runtime.LaunchedEffect(portrait, mini) {
            val f = java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>().firstOrNull() ?: return@LaunchedEffect
            when {
                portrait -> {
                    if (!UiMode.saved) { UiMode.prevBounds = f.bounds; UiMode.saved = true }
                    val h = f.height.coerceAtLeast(520)
                    val w = (h * 9 / 16).coerceIn(260, 520)
                    f.setSize(w, h)
                }
                mini -> {
                    if (!UiMode.saved) { UiMode.prevBounds = f.bounds; UiMode.saved = true }
                    f.setSize(520, 300)
                }
                else -> {
                    if (UiMode.saved) {
                        UiMode.saved = false
                        f.bounds = UiMode.prevBounds
                    }
                }
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f),
        ) {
            // 全局深色 M3 主题：不包的话 DropdownMenu/AlertDialog 等会按默认浅色
            // 主题渲染成白底（排行筛选、历史管理两个菜单实际翻车过）
            androidx.compose.material3.MaterialTheme(
                colorScheme = if (Palette.isDark.value) {
                    androidx.compose.material3.darkColorScheme(
                        primary = Palette.accent,
                        background = Palette.bg,
                        surface = Palette.panel,
                        surfaceContainer = Color(0xFF1E181D),
                        surfaceContainerHigh = Color(0xFF261F24),
                        onSurface = Palette.text,
                        onPrimary = Color.White,
                    )
                } else {
                    androidx.compose.material3.lightColorScheme(
                        primary = Palette.accent,
                        background = Palette.bg,
                        surface = Palette.panel,
                        surfaceContainer = Color(0xFFEDEAEF),
                        surfaceContainerHigh = Color(0xFFE4E0E6),
                        onSurface = Palette.text,
                        onPrimary = Color.White,
                    )
                },
            ) {
                AppShell(onExit = ::exitApplication)
            }
        }
    }
}

/** 顶栏高度；AWT 事件坐标是逻辑像素，与 dp 1:1。 */
private const val TOP_BAR_H = 44

/** 右侧三个 44dp 窗口钮的占位宽度（含左侧 12dp 间距）。 */
private const val WINDOW_BTNS_W = 144

/** 自绘窗口控制钮（旧版 window-controls.tsx 同语义：方形按钮靠右，寄生顶栏）。 */
@Composable
private fun WindowButtons(onExit: () -> Unit) {
    val frame = java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>().firstOrNull()
    var maximized by remember {
        mutableStateOf(frame != null && (frame.extendedState and java.awt.Frame.MAXIMIZED_BOTH) != 0)
    }
    if (frame == null) return
    androidx.compose.runtime.DisposableEffect(Unit) {
        val l = java.awt.event.WindowStateListener { ev ->
            maximized = (ev.newState and java.awt.Frame.MAXIMIZED_BOTH) != 0
        }
        frame.addWindowStateListener(l)
        onDispose { frame.removeWindowStateListener(l) }
    }
    Row {
        WindowBtn("—") { frame.extendedState = java.awt.Frame.ICONIFIED }
        WindowBtn(if (maximized) "❐" else "□") {
            frame.extendedState = if (maximized) {
                frame.extendedState and java.awt.Frame.MAXIMIZED_BOTH.inv()
            } else {
                frame.extendedState or java.awt.Frame.MAXIMIZED_BOTH
            }
        }
        WindowBtn("✕") { onExit() }
    }
}

@Composable
private fun WindowBtn(glyph: String, onClick: () -> Unit) {
    var h by remember { mutableStateOf(false) }
    Box(
        Modifier.size(44.dp)
            .background(if (h) Color(0x33FFFFFF) else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { h = true }
            .onPointerEvent(PointerEventType.Exit) { h = false }
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Palette.dim, fontSize = 14.sp)
    }
}

/**
 * 应用壳（旧版 AppShell 同构）：
 * 顶栏 44px 薄行横贯全宽（logo/应用名在左，窗口钮在右），
 * 下方 = 侧栏 72px + 内容区。
 */
@Composable
private fun AppShell(onExit: () -> Unit) {
    // 顶栏拖拽：AWT 监听器直接挂 ComposeWindow（frameless 下唯一可靠方案，
    // 旧版 Tauri 由 data-tauri-drag-region 处理，Compose 无内置等价物）。
    // 位移必须用「按下时的窗口位置 + 光标屏幕位移量」，不能把窗口位置设成光标位置，
    // 否则一按下窗口左上角就吸到鼠标上。可拖区域只限顶部 44dp，且避开右侧窗口钮。
    val frame = java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>().firstOrNull()
    androidx.compose.runtime.DisposableEffect(frame) {
        if (frame == null) return@DisposableEffect onDispose { }
        val drag = object : java.awt.event.MouseAdapter() {
            private var pressScreen: java.awt.Point? = null
            private var pressFrame: java.awt.Point? = null
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                pressScreen = null
                pressFrame = null
                if (e.button != java.awt.event.MouseEvent.BUTTON1) return
                if ((frame.extendedState and java.awt.Frame.MAXIMIZED_BOTH) != 0) return
                val onTopBar = e.y in 0 until TOP_BAR_H && e.x < frame.width - WINDOW_BTNS_W
                if (!onTopBar) return
                pressScreen = e.locationOnScreen
                pressFrame = frame.location
            }
            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                val startScreen = pressScreen ?: return
                val startFrame = pressFrame ?: return
                val now = e.locationOnScreen
                frame.setLocation(
                    startFrame.x + (now.x - startScreen.x),
                    startFrame.y + (now.y - startScreen.y),
                )
            }
            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                pressScreen = null
                pressFrame = null
            }
        }
        frame.addMouseListener(drag)
        frame.addMouseMotionListener(drag)
        onDispose {
            frame.removeMouseListener(drag)
            frame.removeMouseMotionListener(drag)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Palette.bg)) {
            // 小窗模式：顶栏整条藏掉，整窗只剩画面（旧版 miniScreen 同语义）
            if (!UiMode.mini.value) {
            // 顶栏：44px 薄行（左：logo + 应用名 + 日期；右：窗口钮）
            Row(
                Modifier.fillMaxWidth().height(44.dp).background(Palette.panel),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(Palette.accentFill), contentAlignment = Alignment.Center) {
                    Text("果", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
                Text("开心果", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(java.time.LocalDate.now().toString(), color = Palette.faint, fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                WindowButtons(onExit)
            }
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                AppRoot()
            }
        }
        }
        // 首次启动引导（开源版不含内置服务器地址）
        var setupDismissed by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(PcStore.configured)
        }
        if (!setupDismissed && !PcStore.configured) {
            Box(
                Modifier.fillMaxSize().background(Color(0xCC000000))
                    .clickable { },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.width(460.dp).clip(RoundedCornerShape(14.dp))
                        .background(Palette.panel)
                        .border(1.dp, Palette.line, RoundedCornerShape(14.dp))
                        .padding(24.dp),
                ) {
                    Text("开箱即用", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "内容接口用的是内置签名，取流用的是随安装包分发的本机服务——装好就能浏览和播放，不需要填任何服务器地址。想换成自己的取流服务，可以在「设置 → 服务配置」里改。",
                        color = Palette.dim, fontSize = 13.sp, lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        SecondaryButton("知道了") { setupDismissed = true }
                        Spacer(Modifier.width(8.dp))
                        PrimaryButton("打开设置", Icons.Filled.Settings) {
                            RouteHolder.select("设置")
                            setupDismissed = true
                        }
                    }
                }
            }
        }
    }
}

/** 壳与内容共享的状态（顶栏 tab 也要读写 page）。 */
object UiMode {
    /** 竖屏：窗口收成 9:16 竖条，侧栏藏（旧版 portrait） */
    val portrait = androidx.compose.runtime.mutableStateOf(false)
    /** 小窗：整窗 520×300 + 侧栏顶栏全藏，只剩画面（旧版 miniScreen） */
    val mini = androidx.compose.runtime.mutableStateOf(false)
    var prevBounds: java.awt.Rectangle = java.awt.Rectangle(0, 0, 1440, 900)
    var saved = false
    fun exitAll() { portrait.value = false; mini.value = false }
}

private object RouteHolder {
    val page = androidx.compose.runtime.mutableStateOf("推荐")
    val collapsed = androidx.compose.runtime.mutableStateOf(false)
    fun select(p: String) {
        page.value = p
        SearchHolder.query.value = ""
    }
    fun toggle() {
        collapsed.value = !collapsed.value
    }
}

private object SearchHolder {
    val query = androidx.compose.runtime.mutableStateOf("")
}

/** 启动装配：日志、共享状态、服务配置。
 *
 * 开源版不含任何内置服务器地址：签名服务 / 播放源 / 令牌 / 历史基址
 * 全部来自本地设置（设置页「服务配置」），首次启动引导用户填写。
 */
private fun boot() {
    Log.sink = { level, tag, message -> println("$level/$tag: $message") }
    App.state = PcStore
    Palette.applyTheme(PcStore.theme)
    applyServiceConfig()
    App.deviceIid = PcStore.deviceIid
    App.deviceId = PcStore.deviceId
    App.deviceCdid = PcStore.deviceCdid
    val root = File(System.getenv("APPDATA") ?: ".", "KaixinguoPC").apply { mkdirs() }
    println("数据目录 ${root.absolutePath}")
    Runtime.getRuntime().addShutdownHook(Thread { Resolver.stop() })
}

/** 取流服务：用户填了就用用户的；没填就用随包分发的本机服务。 */
private fun configuredPlayBases(): List<String> {
    val custom = PcStore.playBases.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (custom.isNotEmpty()) return custom
    Resolver.start()
    return listOf(Resolver.base)
}

/** 把设置页改过的服务配置同步进 App（保存后调用，免重启）。 */
fun applyServiceConfig() {
    val custom = PcStore.playBases.isNotBlank()
    App.playBases = configuredPlayBases()
    // 本机服务用内置 token；用户自填的取流服务用他自己的 token
    App.playToken = if (custom) PcStore.playToken else Resolver.token
    App.historyBase = PcStore.historyBase
    App.deviceIid = PcStore.deviceIid
    App.deviceId = PcStore.deviceId
    App.deviceCdid = PcStore.deviceCdid
}

private sealed class Route {
    data class Detail(val seriesId: String, val tab: Int = 0) : Route()
    data class Player(val seriesId: String, val index: Int = 1, val startMs: Long = 0L) : Route()
    object Login : Route()
    data class Creator(val userId: String) : Route()
}

@Composable
private fun AppRoot() {
    var page = RouteHolder.page.value
    var collapsed = RouteHolder.collapsed.value
    var tab by remember { mutableStateOf(0) }
    var refresh by remember { mutableStateOf(0) }
    var search = SearchHolder.query.value
    var route by remember { mutableStateOf<Route?>(null) }
    // 追更未读角标（旧版侧栏 badge）：启动查一次，之后每 20 分钟一轮
    // （旧版 follow_service 同周期；未登录时空转）
    var unreadNotices by remember { mutableStateOf(PcStore.unreadNoticeCount()) }
    LaunchedEffect(Unit) {
        while (true) {
            if (PcStore.loggedIn) {
                withContext(Dispatchers.IO) { runCatching { PcStore.checkFollowUpdates() } }
                unreadNotices = PcStore.unreadNoticeCount()
            }
            kotlinx.coroutines.delay(20 * 60 * 1000L)
        }
    }

    // 找剧筛选面板接口慢（2–8 秒），启动时先在后台暖一次
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { Api.findRowsCached("landpage") } }
    }

    Row(Modifier.fillMaxSize().background(Palette.bg)) {
        // 播放页也保留侧栏（旧版刷剧就是带侧栏的）；
        // 小窗/竖屏里侧栏没有余地，整条藏掉（旧版同语义）
        if ((route == null || route is Route.Player) && !UiMode.mini.value && !UiMode.portrait.value) {
            Sidebar(
                if (route is Route.Player) "刷剧" else page,
                collapsed,
                onSelect = { RouteHolder.select(it); route = null },
                onToggle = { RouteHolder.toggle() },
                badge = unreadNotices,
            )
        }
        Column(Modifier.weight(1f)) {
            when (val r = route) {
                Route.Login -> LoginScreen(onBack = { route = null })
                is Route.Creator -> CreatorScreen(
                    r.userId,
                    onBack = { route = null },
                    onPlaySeries = { id -> route = Route.Player(id) },
                )
                is Route.Detail -> DetailScreen(r.seriesId, initialTab = r.tab, onBack = { route = null }, onPlay = { ep -> route = Route.Player(r.seriesId, ep) }, onOpenSeason = { route = Route.Detail(it) }, onCreator = { route = Route.Creator(it) })
                is Route.Player -> PlayerScreen(r.seriesId, r.index, r.startMs, onBack = { UiMode.exitAll(); route = null }, onDetail = { route = Route.Detail(r.seriesId) }, onComments = { route = Route.Detail(r.seriesId, tab = 1) }, onSwitchSeries = { id -> route = Route.Player(id, 1) })
                null -> {
                    when (page) {
                        "刷剧" -> FeedPlayerPage(
                            onDetail = { route = Route.Detail(it) },
                            onComments = { route = Route.Detail(it, tab = 1) },
                            onExit = { page = "推荐" },
                        )
                        "找剧" -> FindPage(
                            search,
                            onSearch = { search = it },
                            onOpen = { route = Route.Detail(it) },
                            onPlay = { id -> route = Route.Player(id) },
                        )
                        "排行榜" -> RankPage(
                            onOpen = { route = Route.Detail(it) },
                            onPlay = { route = Route.Player(it) },
                        )
                        "新剧" -> NewPage(
                            onOpen = { route = Route.Detail(it) },
                            onPlay = { id -> route = Route.Player(id) },
                        )
                        "历史" -> HistoryPage(
                            onOpen = { route = Route.Detail(it) },
                            onPlay = { id, ep, pos -> route = Route.Player(id, ep, pos) },
                        )
                        "追更" -> FollowsPage(
                            onOpen = { route = Route.Detail(it) },
                            onPlay = { id -> route = Route.Player(id) },
                            onPlayEp = { id, ep -> route = Route.Player(id, ep) },
                        )
                        "预约" -> ReservePage(
                            onOpen = { route = Route.Detail(it) },
                            onPlay = { route = Route.Player(it) },
                        )
                        "我的" -> MinePage(
                            onOpen = { route = Route.Detail(it) },
                            onPlay = { id -> route = Route.Player(id) },
                            onLogin = { route = Route.Login },
                        )
                        "设置" -> SettingsPage()
                        else -> HomePage(
                            tab = tab,
                            refresh = refresh,
                            onTab = { tab = it },
                            onRefresh = { refresh++ },
                            onOpen = { route = Route.Detail(it) },
                            onPlay = { id -> route = Route.Player(id) },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 首页

@Composable
private fun HomePage(
    tab: Int,
    refresh: Int,
    onTab: (Int) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
) {
    var page by remember { mutableStateOf<FeedPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var heroDetail by remember { mutableStateOf<SeriesDetail?>(null) }
    /** 上一页的游标：cellId/planId/sessionId/offset 都在里面，翻页要用 */
    var cursor by remember { mutableStateOf<FeedPage?>(null) }
    var moreLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val tabTypes = listOf("2", "3", "4")

    LaunchedEffect(tab, refresh) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching { Api.firstPage(tabTypes[tab.coerceIn(0, tabTypes.lastIndex)]) }.getOrNull()
        }
        var cur = result
        // 墙要「满」：首批不足两屏（约 16 张）时自动续拉，最多补 2 轮（旧版同语义）
        var rounds = 0
        while (cur != null && cur.cards.size < 16 && cur.hasMore && rounds < 2) {
            val next = withContext(Dispatchers.IO) {
                runCatching {
                    Api.feedNext(
                        tabTypes[tab.coerceIn(0, tabTypes.lastIndex)],
                        cur.cellId, cur.planId, cur.sessionId, cur.nextOffset,
                        cur.cards.map { it.seriesId },
                    )
                }.getOrNull()
            } ?: break
            if (next.cards.isEmpty()) break
            cur = next.copy(cards = cur.cards + next.cards)
            rounds++
        }
        page = cur
        cursor = cur
        error = if (cur == null || cur.cards.isEmpty()) "没有拿到数据（检查签名服务 / 网络）" else null
        loading = false
    }

    val cards = page?.cards.orEmpty()
    // hero 可左右翻（旧版：hero 覆盖整条流，箭头切主角，底部计数 N / 总数）
    var heroIndex by remember(page) { mutableStateOf(0) }
    val safeHeroIndex = heroIndex.coerceIn(0, (cards.size - 1).coerceAtLeast(0))
    val hero = cards.getOrNull(safeHeroIndex)
    LaunchedEffect(hero?.seriesId) {
        val id = hero?.seriesId ?: return@LaunchedEffect
        heroDetail = withContext(Dispatchers.IO) { runCatching { Api.ensureDetail(id) }.getOrNull() }
    }

    // 「换一批」：feed 游标前进一页当新首屏（firstPage 每次返回同批，
    // 只重拉没有换批效果）；到尾就回绕首批
    fun swapBatch() {
        val c = cursor ?: return
        if (moreLoading) return
        scope.launch {
            moreLoading = true
            val type = tabTypes[tab.coerceIn(0, tabTypes.lastIndex)]
            val np = if (c.hasMore) withContext(Dispatchers.IO) {
                runCatching {
                    Api.feedNext(type, c.cellId, c.planId, c.sessionId, c.nextOffset, cards.map { it.seriesId })
                }.getOrNull()
            } else null
            if (np != null && np.cards.isNotEmpty()) {
                page = np
                cursor = np
                error = null
            } else {
                val r = withContext(Dispatchers.IO) {
                    runCatching { Api.firstPage(type) }.getOrNull()
                }
                page = r
                cursor = r
            }
            moreLoading = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = (maxHeight * 0.5f - 40.dp).coerceIn(340.dp, 560.dp)
        Column(Modifier.fillMaxSize()) {
            // 页头薄行：分类 tab + 右端「换一批」（旧版 home-wall 顶部那一行）
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("推荐", "漫剧", "真人剧").forEachIndexed { i, label ->
                    TabChip(label, i == tab) { if (i == tab) onRefresh() else onTab(i) }
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.weight(1f))
                HeaderAction("换一批", onClick = { swapBatch() })
            }
            Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                when {
                    // 首屏骨架（旧版 WallSkeleton：墙形灰砖比转圈更像成品）
                    loading && page == null -> Box(Modifier.fillMaxSize().padding(top = 8.dp)) { SkeletonWall() }
                    error != null && (page?.cards.isNullOrEmpty()) -> CenterMessage(error!!)
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (hero != null) {
                            HeroCard(
                                hero,
                                heroDetail,
                                onPlay = { onPlay(hero.seriesId) },
                                onDetail = { onOpen(hero.seriesId) },
                                position = safeHeroIndex,
                                count = cards.size,
                                onNavigate = { d -> heroIndex = (safeHeroIndex + d + cards.size) % cards.size },
                                height = heroHeight,
                            )
                            Spacer(Modifier.height(20.dp))
                        }
                        PosterGrid(
                            cards = cards,
                            onOpen = { onOpen(it.seriesId) },
                            onPlay = { onPlay(it.seriesId) },
                            onCollect = { PcStore.toggle("favorite", it.seriesId) },
                            scrollable = false,
                        )
                        val c = cursor
                        if (c != null && c.hasMore) {
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                SecondaryButton(if (moreLoading) "加载中…" else "加载更多") {
                                    if (moreLoading || c.cards.isEmpty()) return@SecondaryButton
                                    moreLoading = true
                                    scope.launch(Dispatchers.IO) {
                                        val next = runCatching {
                                            Api.feedNext(
                                                tabType = tabTypes[tab.coerceIn(0, tabTypes.lastIndex)],
                                                cellId = c.cellId,
                                                planId = c.planId,
                                                sessionId = c.sessionId,
                                                offset = c.nextOffset,
                                                seen = cards.map { it.seriesId },
                                            )
                                        }.getOrNull()
                                        withContext(Dispatchers.Main) {
                                            if (next != null && next.cards.isNotEmpty()) {
                                                val known = cards.map { it.seriesId }.toSet()
                                                page = next.copy(cards = cards + next.cards.filter { it.seriesId !in known })
                                                cursor = next
                                            } else {
                                                cursor = c.copy(hasMore = false)
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
                // 切 tab/换一批拉取期间：旧内容保留在下方但要能看出来在刷新
                // （此前切 tab 后旧墙静默停留 1-3 秒，像点了没反应）
                if (loading && page != null) {
                    Box(
                        Modifier.matchParentSize().background(Color(0x66000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Palette.accent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 刷剧（整屏播放）

@Composable
private fun FeedPlayerPage(onDetail: (String) -> Unit, onComments: (String) -> Unit, onExit: () -> Unit) {
    // 旧版顶部 Tab 顺序：漫剧 / 真人剧 / 推荐（默认推荐）
    val feedTypes = listOf("3", "4", "2")
    var tab by remember { mutableStateOf(2) }
    var cards by remember { mutableStateOf<List<SeriesCard>>(emptyList()) }
    var cursor by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    /** 翻页游标（cellId/planId/sessionId/offset 都在里面），hasMore=false 后不再翻 */
    var feedCursor by remember { mutableStateOf<FeedPage?>(null) }
    var moreLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tab) {
        loading = true
        cards = emptyList()
        cursor = 0
        val page = withContext(Dispatchers.IO) {
            runCatching { Api.firstPage(feedTypes[tab]) }.getOrNull()
        }
        cards = page?.cards.orEmpty()
        feedCursor = page
        loading = false
    }

    // 滚轮换剧（旧版 onWheelStep 同语义）：向下=下一部，向上=上一部；
    // 接近列表尾（剩 2 部）自动 cell/change 翻页续上
    fun wheelStep(dir: Int) {
        val next = cursor + dir
        if (next < 0) return
        if (next >= cards.size) {
            val c = feedCursor ?: return
            if (!c.hasMore || moreLoading) return
            moreLoading = true
            scope.launch(Dispatchers.IO) {
                val np = runCatching {
                    Api.feedNext(
                        feedTypes[tab], c.cellId, c.planId, c.sessionId, c.nextOffset,
                        cards.map { it.seriesId },
                    )
                }.getOrNull()
                if (np != null && np.cards.isNotEmpty()) {
                    val known = cards.map { it.seriesId }.toSet()
                    cards = cards + np.cards.filter { it.seriesId !in known }
                    feedCursor = np
                } else {
                    feedCursor = c.copy(hasMore = false)
                }
                moreLoading = false
            }
            return
        }
        cursor = next
    }

    val current = cards.getOrNull(cursor)
    when {
        loading -> CenterLoading()
        current == null -> EmptyState(
            title = "没有拿到推荐",
            hint = "检查网络或签名服务后重试",
            icon = Icons.Filled.PlayArrow,
        )
        else -> PlayerScreen(
            seriesId = current.seriesId,
            startIndex = 1,
            onBack = { UiMode.exitAll(); onExit() },
            onDetail = { onDetail(current.seriesId) },
            onComments = { onComments(current.seriesId) },
            embedded = true,
            feedTab = tab,
            onFeedTab = { tab = it; cursor = 0 },
            onWheelStep = { dir -> wheelStep(dir) },
        )
    }
}

// ---------------------------------------------------------------- 找剧

@Composable
private fun FindPage(
    search: String,
    onSearch: (String) -> Unit,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
) {
    var rows by remember { mutableStateOf<List<FilterRow>>(emptyList()) }
    var rowsLoading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var page by remember { mutableStateOf<FeedPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var moreLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 整页一个滚动容器（旧版同款：筛选随内容一起滚走，不是上半屏钉死）
    val pageScroll = rememberScrollState()

    LaunchedEffect(Unit) {
        rowsLoading = true
        rows = withContext(Dispatchers.IO) { runCatching { Api.findRowsCached("landpage") }.getOrDefault(emptyList()) }
        rowsLoading = false
    }

    LaunchedEffect(selected, search) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (search.isNotBlank()) {
                    Api.searchPage(search)
                } else {
                    Api.findList(selected, 0, "")
                }
            }.getOrNull()
        }
        page = result
        loading = false
    }

    // 滚动加载：滚到离底部 500px 内自动翻页（旧版哨兵同语义，去掉按钮）
    val cardsNow = page?.cards.orEmpty()
    val hasMoreNow = page?.hasMore == true && search.isBlank()
    LaunchedEffect(pageScroll.value) {
        val max = pageScroll.maxValue
        if (max <= 0) return@LaunchedEffect
        if (max - pageScroll.value > 500) return@LaunchedEffect
        val c = page ?: return@LaunchedEffect
        if (!c.hasMore || moreLoading || loading) return@LaunchedEffect
        moreLoading = true
        val next = withContext(Dispatchers.IO) {
            runCatching { Api.findList(selected, c.nextOffset, c.sessionId) }.getOrNull()
        }
        if (next != null && next.cards.isNotEmpty()) {
            val known = c.cards.map { it.seriesId }.toSet()
            page = c.copy(cards = c.cards + next.cards.filter { it.seriesId !in known })
        } else {
            page = c.copy(hasMore = false)
        }
        moreLoading = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(pageScroll).padding(horizontal = 24.dp),
    ) {
        // 顶部搜索条（旧版 find：整行输入框 + 红色「搜索」+ 右端「共 N 部」）
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp))
                    .background(Palette.coverPlaceholder).border(1.dp, Palette.line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.faint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (search.isEmpty()) {
                        Text("输入剧名 / 关键词", color = Palette.faint, fontSize = 14.sp)
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = search,
                        onValueChange = onSearch,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 14.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.accent),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (search.isNotEmpty()) {
                    Text(
                        "✕",
                        color = Palette.faint,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { onSearch("") }.padding(4.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            PrimaryButton("搜索", null) { }
            Spacer(Modifier.width(14.dp))
            Text("共 ${page?.cards?.size ?: 0} 部", color = Palette.dim, fontSize = 12.sp)
        }
        // 筛选项：每个维度一行，横向滚动，和旧 PC 版一致（不再是一整面标签墙）
        if (rowsLoading && rows.isEmpty()) {
            // 骨架占位：筛选项是慢接口（2–8 秒），先把高度占住，避免海报墙跳一下
            repeat(9) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(44.dp).height(10.dp).clip(RoundedCornerShape(3.dp)).background(Palette.panel))
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(10) {
                            Box(Modifier.width(46.dp).height(24.dp).clip(RoundedCornerShape(6.dp)).background(Palette.panel))
                        }
                    }
                }
            }
        } else if (rows.isEmpty()) {
            Text("筛选项加载失败，重开本页可重试", color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    row.name,
                    color = Palette.faint,
                    fontSize = 12.sp,
                    modifier = Modifier.width(56.dp).padding(top = 8.dp),
                )
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 每行第一个永远是「全部」（旧版筛选行口径；接口不保证下发空 id 项）
                    FilterChip("全部", selected[row.key].isNullOrEmpty()) {
                        selected = selected - row.key
                    }
                    row.items.filter { it.id.isNotEmpty() }.forEach { item ->
                        val active = selected[row.key] == item.id
                        FilterChip(item.label, active) {
                            selected = selected + (row.key to item.id)
                        }
                    }
                }
            }
        }
        if (selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text(
                    "重置筛选",
                    color = Palette.accent,
                    fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { selected = emptyMap() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading && page == null -> CenterLoading()
            page == null -> CenterMessage("没有匹配的剧")
            cardsNow.isEmpty() -> EmptyState(
                title = "没有找到匹配的剧",
                hint = if (selected.isEmpty()) "换个关键词试试" else "放宽筛选条件，或点「重置筛选」",
                icon = Icons.Filled.Search,
            )
            else -> Column(Modifier.fillMaxWidth()) {
                PosterGrid(
                    cards = cardsNow,
                    onOpen = { onOpen(it.seriesId) },
                    onPlay = { onPlay(it.seriesId) },
                    onCollect = { PcStore.toggle("favorite", it.seriesId) },
                    scrollable = false,
                    coverBadge = { c -> if (c.episodeCount > 0) "${c.episodeCount} 集" else null },
                    tagsLine = { c -> c.tags.take(3).joinToString(" ").takeIf { it.isNotBlank() } },
                )
                // 底部状态：翻页中/没有更多（滚动到附近自动触发，不再放按钮）
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        when {
                            moreLoading -> "加载中…"
                            search.isNotBlank() -> "搜索结果共 ${cardsNow.size} 部"
                            hasMoreNow -> "下滑加载更多"
                            else -> "已经到底了"
                        },
                        color = Palette.faint,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------- 排行榜

@Composable
private fun RankPage(onOpen: (String) -> Unit, onPlay: (String) -> Unit) {
    var tabs by remember { mutableStateOf<List<RankTabsel>>(emptyList()) }
    var tabIndex by remember { mutableStateOf(0) }
    var subIndex by remember { mutableStateOf(0) }
    var panel by remember { mutableStateOf("") }
    var filterOpen by remember { mutableStateOf(false) }
    var cards by remember { mutableStateOf<List<SeriesCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        tabs = withContext(Dispatchers.IO) { runCatching { Api.rankMeta() }.getOrDefault(emptyList()) }
    }

    val tab = tabs.getOrNull(tabIndex)
    val sub = tab?.subs?.getOrNull(subIndex)

    LaunchedEffect(tab?.id, sub?.id, panel) {
        val t = tab ?: return@LaunchedEffect
        loading = true
        cards = withContext(Dispatchers.IO) {
            // 优先走官方榜单（带筛选面板），失败再退回服务器聚合的 chart
            val ranked = runCatching {
                Api.rank(0, "", t.id, sub?.id ?: "", panel) 
            }.getOrNull()?.cards.orEmpty()
            if (ranked.isNotEmpty()) ranked
            else runCatching { Api.chart(metric = sub?.id ?: "", genre = t.id, limit = 60) }.getOrDefault(emptyList())
        }
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        // 大类 tab（旧版页内第一行：全部/系列剧/漫剧/真人剧/AI剧）
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, t ->
                TabChip(t.label, index == tabIndex) { tabIndex = index; subIndex = 0 }
            }
        }
        if (tab != null && tab.subs.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tab.subs.forEachIndexed { index, s ->
                    TabChip(s.label, index == subIndex) { subIndex = index; panel = "" }
                }
                // 筛选按钮（旧版 FilterPanelButton）：仅当前子榜自带面板时出现，
                // chip 形态——未选中灰、选中红；面板里单选，选中后 chip 显示所选名
                if (sub != null && sub.panels.any { it.items.isNotEmpty() }) {
                    Spacer(Modifier.width(4.dp))
                    val selectedName = sub.panels.firstNotNullOfOrNull { p ->
                        p.items.firstOrNull { it.id == panel && it.id.isNotEmpty() }?.name
                    }
                    Row(
                        Modifier.height(32.dp).clip(Radius.chip)
                            .background(if (panel.isNotEmpty()) Palette.accentFill else Palette.panel)
                            .clickable { filterOpen = !filterOpen }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = null,
                            tint = if (panel.isNotEmpty()) Color.White else Palette.dim,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            selectedName ?: "筛选",
                            color = if (panel.isNotEmpty()) Color.White else Palette.dim,
                            fontSize = 13.sp,
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = if (panel.isNotEmpty()) Color.White else Palette.dim,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        // 描述行（旧版：子榜名 + 「N月N日已更新·基于红果热度及个人数据推荐」）
        // 组间 16 / 组内 8：与上方筛选拉开，与下方列表贴近
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)) {
            Text(
                sub?.label ?: "榜单",
                color = Palette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
            Text("${java.time.LocalDate.now().monthValue}月${java.time.LocalDate.now().dayOfMonth}日已更新 · 基于红果热度及个人数据推荐", color = Palette.faint, fontSize = 12.sp)
        }
        // 子榜筛选已收进上方「筛选」按钮弹层（旧版 FilterPanelButton 同构），
        // 不再整面平铺——面板只在子榜自带选项时出现，且不占版面。
        if (loading) {
            CenterLoading()
        } else {
            val list = cards
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(list) { index, card ->
                    RankRow(index + 1, card, onClick = { onOpen(card.seriesId) }, onPlay = { onPlay(card.seriesId) })
                }
            }
        }
    }
    // 筛选浮层（自绘深色卡；M3 DropdownMenu 对自定义分组内容测量失控——
    // 实测出现分组行撑成大间距 + 右侧多出白边框空白块）
    val subNow = sub
    if (filterOpen && subNow != null) {
        Box(
            Modifier.matchParentSize()
                .onPointerEvent(PointerEventType.Press) { filterOpen = false },
        )
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 24.dp)
                .width(220.dp).heightIn(max = 400.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xF2101014))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            if (panel.isNotEmpty()) {
                Text(
                    "重置筛选",
                    color = Palette.accent, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { panel = ""; filterOpen = false }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
            subNow.panels.forEach { p ->
                if (p.items.isEmpty()) return@forEach
                Text(
                    p.name,
                    color = Palette.faint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 3.dp),
                )
                p.items.forEach { option ->
                    val active = panel == option.id
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                panel = if (active) "" else option.id
                                filterOpen = false
                            }
                            .background(if (active) Color(0x1AFF4D4F) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            option.name,
                            fontSize = 13.sp,
                            color = if (active) Palette.accent else Palette.dim,
                            modifier = Modifier.weight(1f),
                        )
                        if (active) Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Palette.accent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun RankRow(rank: Int, card: SeriesCard, onClick: () -> Unit, onPlay: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    // 旧版 SeriesRowCard 规格：p-3、gap-4、圆角 12、细边框；
    // 名次 2xl font-black（前 3 星色，其余 muted/50）；封面 80px 3:4；
    // 标题 14/600 + 季徽；metaLine（副标题·评分·标签）12px；
    // 两行简介 12px；🔥热度行 warm 色；行尾动作列（红底播放 + 文字详情）。
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (hovered) Color(0x4DFFFFFF) else Palette.line, RoundedCornerShape(12.dp))
            .background(Palette.panel)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank > 0) {
            Text(
                "$rank",
                color = if (rank <= 3) Palette.star else Palette.dim.copy(alpha = 0.5f),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        RemoteImage(
            card.cover,
            Modifier.width(88.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)),
            fallback = card.title,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(card.title, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // 评分：星形 + 黄字（与详情页评分同语言）
                card.score.takeIf { it.isNotBlank() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Palette.star, modifier = Modifier.size(12.dp))
                        Text(it, color = Palette.star, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                card.season?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = Palette.dim,
                        fontSize = 10.sp,
                        modifier = Modifier.border(1.dp, Palette.line, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            val meta = listOf(
                card.subtitle?.takeIf { it.isNotBlank() },
                card.tags.take(3).joinToString(" · ").takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            card.desc?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Palette.dim.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val rec = card.recText?.takeIf { it.isNotBlank() }
            val secondary = card.secondary?.filter { it.isNotBlank() && it != rec }.orEmpty()
            if (rec != null || secondary.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    rec?.let {
                        HeatBadge(it)
                    }
                    if (secondary.isNotEmpty()) {
                        Text(secondary.joinToString("  "), color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (card.upcoming) {
                SecondaryButton(if (card.reserved) "已预约" else "预约", icon = if (card.reserved) Icons.Filled.Check else Icons.Filled.Add) { }
            } else {
                PrimaryButton("播放", Icons.Filled.PlayArrow, onPlay)
                Text(
                    "详情",
                    color = if (hovered) Palette.text else Palette.dim,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onClick() }.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 新剧（推荐 + 上新日历双视图）

@Composable
private fun NewPage(onOpen: (String) -> Unit, onPlay: (String) -> Unit) {
    // 旧版双视图：推荐（firstonlinetime_new 倒序流）/ 上新日历（按日期）
    var view by remember { mutableStateOf(0) }
    var gender by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf<FeedPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var moreLoading by remember { mutableStateOf(false) }
    // 推荐视图滚动容器（滚动近底部自动翻页）
    val newScroll = rememberScrollState()

    // 日历：日期条 + 当日上新行卡列表
    var calDates by remember { mutableStateOf<List<String>>(emptyList()) }
    /** 日历周偏移（0=本周，±1 前后一周；旧版日历分页） */
    var calWeek by remember { mutableStateOf(0) }
    var calDate by remember { mutableStateOf("") }
    var calItems by remember { mutableStateOf<List<SeriesCard>>(emptyList()) }
    var calLoading by remember { mutableStateOf(false) }

    LaunchedEffect(view, gender, calWeek) {
        if (view == 0) {
            loading = true
            page = withContext(Dispatchers.IO) { runCatching { Api.newDrama(gender, 0, "") }.getOrNull() }
            loading = false
        } else {
            calLoading = true
            val root = withContext(Dispatchers.IO) { runCatching { Api.calendar(calDate) }.getOrNull() }
            // 日期条：官方日期未带独立列表时本地生成（按周偏移，7 天/页）
            val today = java.time.LocalDate.now().plusDays(calWeek * 7L)
            calDates = (0..6).map { today.plusDays(it.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) }
            if (calDate.isEmpty() || calDates.none { it == calDate }) calDate = calDates.first()
            calItems = parseCalendarItems(root)
            calLoading = false
        }
    }

    // 滚动加载（推荐视图）：近底部 500px 自动翻页
    LaunchedEffect(newScroll.value, view) {
        if (view != 0) return@LaunchedEffect
        val max = newScroll.maxValue
        if (max <= 0 || max - newScroll.value > 500) return@LaunchedEffect
        val c = page ?: return@LaunchedEffect
        if (!c.hasMore || moreLoading || loading) return@LaunchedEffect
        moreLoading = true
        val next = withContext(Dispatchers.IO) {
            runCatching { Api.newDrama(gender, c.nextOffset, c.sessionId) }.getOrNull()
        }
        if (next != null && next.cards.isNotEmpty()) {
            val known = c.cards.map { it.seriesId }.toSet()
            page = c.copy(cards = c.cards + next.cards.filter { it.seriesId !in known })
        } else {
            page = c.copy(hasMore = false)
        }
        moreLoading = false
    }

    LaunchedEffect(calDate) {
        if (view != 1 || calDate.isEmpty()) return@LaunchedEffect
        calLoading = true
        val root = withContext(Dispatchers.IO) { runCatching { Api.calendar(calDate) }.getOrNull() }
        calItems = parseCalendarItems(root)
        calLoading = false
    }

    Column(Modifier.fillMaxSize()) {
        // 视图 tab：剧剧推荐 / 上新日历（旧版页内第一行）
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("新剧推荐", "上新日历").forEachIndexed { i, label ->
                TabChip(label, view == i) { view = i }
            }
            Spacer(Modifier.weight(1f))
            Text("共 ${page?.cards?.size ?: 0} 部", color = Palette.faint, fontSize = 12.sp)
        }
        // 频道筛选：全部 / 男频 / 女频
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("全部", "男频", "女频").forEachIndexed { i, label ->
                TabChip(label, gender == i) { gender = i }
                Spacer(Modifier.width(8.dp))
            }
        }
        if (view == 1) {
            // 日期条：星期小字 + 日期大字，选中主色底（旧版官方同款）；
            // 左右箭头按周翻页（旧版日历分页同语义）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable { calWeek -= 1 }.padding(6.dp)) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "上一周", tint = Palette.dim, modifier = Modifier.size(18.dp))
                }
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    calDates.take(7).forEach { d ->
                        NewDateCell(d, calDate == d) { calDate = d }
                    }
                }
                Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable { calWeek += 1 }.padding(6.dp)) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "下一周", tint = Palette.dim, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
            when {
                view == 0 && loading && page == null -> CenterLoading()
                view == 0 -> Column(Modifier.fillMaxSize().verticalScroll(newScroll)) {
                    PosterGrid(
                        cards = page?.cards.orEmpty(),
                        onOpen = { onOpen(it.seriesId) },
                        onPlay = { onPlay(it.seriesId) },
                        onCollect = { PcStore.toggle("favorite", it.seriesId) },
                        modifier = Modifier.fillMaxWidth(),
                        scrollable = false,
                        // 旧版新剧卡：封面 → 标题 → 「分类·N集·标签」→ ⭐评分
                        metaLine = { c ->
                            listOfNotNull(
                                c.tags.firstOrNull(),
                                if (c.episodeCount > 0) "${c.episodeCount}集" else null,
                                c.tags.drop(1).take(2).joinToString("·").takeIf { it.isNotBlank() },
                            ).joinToString("·").takeIf { it.isNotBlank() }
                        },
                        scoreLine = { c -> c.score.takeIf { it.isNotBlank() }?.let { "$it 分" } },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            when {
                                moreLoading -> "加载中…"
                                page?.hasMore == true -> "下滑加载更多"
                                else -> "已经到底了"
                            },
                            color = Palette.faint,
                            fontSize = 12.sp,
                        )
                    }
                }
                calLoading && calItems.isEmpty() -> CenterLoading()
                calItems.isEmpty() -> EmptyState(title = "这一天没有上新", hint = "换一天看看")
                else -> androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    items(calItems, key = { it.seriesId }) { card ->
                        // 日历行卡 = 排行榜行同骨架；行尾按上线状态分
                        RankRow(
                            rank = 0,
                            card = card,
                            onClick = { onOpen(card.seriesId) },
                            onPlay = { onPlay(card.seriesId) },
                        )
                    }
                }
            }
        }
    }
}

/** 日历响应 → SeriesCard 列表（subscribe_items 的扁平卡片，带 is_online 分组）。 */
private fun parseCalendarItems(root: JSONObject?): List<SeriesCard> {
    if (root == null) return emptyList()
    val items = root.optJSONObject("data")?.optJSONArray("subscribe_items") ?: return emptyList()
    val out = mutableListOf<SeriesCard>()
    for (i in 0 until items.length()) {
        val o = items.optJSONObject(i) ?: continue
        val sd = o.optJSONObject("subscribe_data") ?: o
        val online = when (o.optString("is_online")) {
            "true" -> true
            "false" -> false
            else -> sd.optInt("episode_cnt", 0) > 0
        }
        out.add(
            SeriesCard(
                seriesId = sd.optString("series_id").ifEmpty { sd.optString("book_id") },
                title = sd.optString("book_name").ifEmpty { sd.optString("title") },
                cover = sd.optString("thumb_url").ifEmpty { sd.optString("cover") },
                episodeCount = sd.optInt("episode_cnt", 0),
                score = sd.optString("score"),
                tags = listOfNotNull(sd.optString("category").takeIf { it.isNotEmpty() }),
                desc = sd.optString("description").takeIf { it.isNotEmpty() },
                upcoming = !online,
            ),
        )
    }
    return out.filter { it.seriesId.isNotEmpty() }
}

@Composable
private fun NewDateCell(date: String, active: Boolean, onClick: () -> Unit) {
    // "20261109" → 周几 + 「11月9日」
    val day = runCatching {
        java.time.LocalDate.parse(date.take(4) + "-" + date.drop(4).take(2) + "-" + date.drop(6).take(2))
    }.getOrNull()
    val weekday = day?.dayOfWeek?.let {
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[it.value - 1]
    }.orEmpty()
    val label = day?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: date
    Column(
        Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Palette.accentFill else Palette.panel)
            .border(1.dp, if (active) Color.Transparent else Palette.line, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(weekday, color = if (active) Color.White else Palette.dim, fontSize = 12.sp)
        Text(
            label,
            color = if (active) Color.White else Palette.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------- 历史

@Composable
private fun HistoryPage(onOpen: (String) -> Unit, onPlay: (String, Int, Long) -> Unit) {
    var rows by remember { mutableStateOf(PcStore.history()) }
    var cloudRows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var syncing by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var manageOpen by remember { mutableStateOf(false) }

    fun reload() {
        rows = PcStore.history()
    }

    LaunchedEffect(Unit, PcStore.loggedIn) {
        syncing = true
        // 云端历史（read_history/list，官方 App 同源）：登录才有；
        // 未登录用本地观看记录（旧版双模式同语义）
        if (PcStore.loggedIn) {
            cloudRows = withContext(Dispatchers.IO) {
                runCatching { Api.officialHistory(3) }.getOrDefault(emptyList())
            }
        } else {
            withContext(Dispatchers.IO) { runCatching { Api.historySyncAsync() } }
            kotlinx.coroutines.delay(1500)
            reload()
        }
        syncing = false
    }

    // 数据源：登录 = 云端（跨设备）；未登录 = 本地
    val source: List<JSONObject> = if (PcStore.loggedIn && cloudRows.isNotEmpty()) cloudRows else rows

    // 官方同款「已看完」判定：看到最后一集即算，与片内进度无关
    fun isFinished(r: JSONObject): Boolean {
        val total = r.optInt("total", 0)
        val ep = r.optInt("episode", 1)
        return total > 0 && ep >= total
    }

    val shown = source.filter { r ->
        val title = r.optString("title")
        val matchSearch = search.isBlank() || title.contains(search.trim(), ignoreCase = true)
        val matchFilter = when (filter) {
            1 -> !isFinished(r)
            2 -> isFinished(r)
            else -> true
        }
        matchSearch && matchFilter
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        // 页头：分组 tab + 搜索 + 管理（旧版 history 顶部那一行）
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabChip("全部", filter == 0) { filter = 0 }
            Spacer(Modifier.width(8.dp))
            TabChip("未看完", filter == 1) { filter = 1 }
            Spacer(Modifier.width(8.dp))
            TabChip("已看完", filter == 2) { filter = 2 }
            if (syncing) {
                Spacer(Modifier.width(10.dp))
                Text("正在同步云端历史…", color = Palette.dim, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            ListSearchBox(search, { search = it }, "搜索看过的剧")
            Spacer(Modifier.width(8.dp))
            OutlineButton("管理") { manageOpen = !manageOpen }
        }
        when {
            source.isEmpty() -> EmptyState(
                title = "还没有观看记录",
                hint = "播放过的剧会出现在这里，进度会自动同步",
                icon = Icons.Filled.Refresh,
            )
            shown.isEmpty() -> EmptyState(
                title = "没有匹配的记录",
                hint = "换个关键词，或切换上面的分组",
                icon = Icons.Filled.Search,
            )
            else -> androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(shown) { row ->
                    val seriesId = row.optString("seriesId")
                    HistoryRow(
                        title = row.optString("title"),
                        cover = row.optString("cover"),
                        episode = row.optInt("episode"),
                        total = row.optInt("total"),
                        positionMs = row.optLong("positionMs", 0L),
                        at = row.optLong("at"),
                        onOpen = { onOpen(seriesId) },
                        onPlay = { onPlay(seriesId, row.optInt("episode", 1).coerceAtLeast(1), row.optLong("positionMs", 0L)) },
                    )
                }
            }
        }
    }
    if (manageOpen) {
        Box(
            Modifier.matchParentSize()
                .onPointerEvent(PointerEventType.Press) { manageOpen = false },
        )
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 104.dp, end = 24.dp)
                .width(150.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xF2101014))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
                .padding(vertical = 4.dp),
        ) {
            Text(
                "清空观看记录",
                color = Palette.text,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth()
                    .clickable { manageOpen = false; confirmClear = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
    if (confirmClear) {
        Box(Modifier.matchParentSize().background(Color(0x99000000)).onPointerEvent(PointerEventType.Press) { confirmClear = false })
        Column(
            Modifier.align(Alignment.Center).width(360.dp).clip(RoundedCornerShape(12.dp))
                .background(Palette.panel).padding(20.dp),
        ) {
            Text("清空观看记录？", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("会删除本地历史与播放进度；云端历史下次同步可能还会回来。", color = Palette.dim, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryButton("取消") { confirmClear = false }
                Spacer(Modifier.width(8.dp))
                PrimaryButton("清空", null) { PcStore.clearHistory(); reload(); confirmClear = false }
            }
        }
    }
    }
}

private fun historyDate(at: Long): String {
    if (at <= 0) return ""
    val day = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when {
        day == today -> "今天"
        day == today.minusDays(1) -> "昨天"
        else -> "${day.monthValue}月${day.dayOfMonth}日"
    }
}

@Composable
private fun HistoryRow(
    title: String,
    cover: String,
    episode: Int,
    total: Int,
    positionMs: Long,
    at: Long,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (hovered) Color(0x4DFFFFFF) else Palette.line, RoundedCornerShape(12.dp))
            .background(Palette.panel)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onOpen() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            cover,
            Modifier.width(72.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)),
            fallback = title,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 位置徽标（官方同款）：红 10% 底 chip「看到第N集/共M集 时长」
                Text(
                    if (positionMs > 0) "看到第 $episode 集" + (if (total > 0) " / 共 $total 集" else "") + "  " + fmtTime(positionMs)
                    else "看到第 $episode 集" + (if (total > 0) " / 共 $total 集" else ""),
                    color = Palette.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(Radius.xs)
                        .background(Palette.accent.copy(alpha = 0.10f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                // 相对时间（今天/昨天/N天前）
                val time = historyDate(at)
                if (time.isNotBlank()) Text(time, color = Palette.dim, fontSize = 12.sp)
            }
        }
        // 继续播放 = 红描边按钮（本屏唯一 accent 动作）
        Row(
            Modifier.clip(Radius.ctrl)
                .border(1.dp, Palette.accent.copy(alpha = 0.6f), Radius.ctrl)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .clickable { onPlay() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("继续播放", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ---------------------------------------------------------------- 追更（追更的剧 / 更新提醒）

@Composable
private fun FollowsPage(onOpen: (String) -> Unit, onPlay: (String) -> Unit, onPlayEp: (String, Int) -> Unit = { id, _ -> onPlay(id) }) {
    var tab by remember { mutableStateOf(0) }
    var cards by remember { mutableStateOf<List<SeriesCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 更新提醒（本地通知：服务端集数 vs 本地基线，旧版 follow_service 同口径）
    var notices by remember { mutableStateOf(PcStore.followNotices()) }
    var checking by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loading = true
        val result = withContext(Dispatchers.IO) { runCatching { Api.followList() } }
        cards = result.getOrDefault(emptyList())
        error = result.exceptionOrNull()?.message
        loading = false
    }

    fun refreshNotices() { notices = PcStore.followNotices() }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabChip("追更的剧", tab == 0) { tab = 0 }
            Spacer(Modifier.width(8.dp))
            val unread = notices.count { !it.optBoolean("read", false) }
            TabChip(if (unread > 0) "更新提醒 $unread" else "更新提醒", tab == 1) { tab = 1 }
            if (tab == 1) {
                Spacer(Modifier.weight(1f))
                SecondaryButton(if (checking) "检查中…" else "检查更新", icon = Icons.Filled.Refresh) {
                    if (checking) return@SecondaryButton
                    checking = true
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { PcStore.checkFollowUpdates() } }
                        refreshNotices()
                        checking = false
                    }
                }
                if (notices.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("全部已读") { PcStore.markAllNoticesRead(); refreshNotices() }
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("清空") { PcStore.clearNotices(); refreshNotices() }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                tab == 1 -> NoticesList(
                    notices = notices,
                    onRead = { id -> PcStore.markNoticeRead(id); refreshNotices() },
                    onPlay = { n ->
                        val sid = n.optString("seriesId")
                        val ep = n.optInt("episode", 0)
                        PcStore.markNoticeRead(n.optLong("id", -1L)); refreshNotices()
                        if (ep > 0) onPlayEp(sid, ep) else onPlay(sid)
                    },
                )
                loading -> CenterLoading()
                cards.isEmpty() && error != null -> CenterMessage("加载失败：$error")
                cards.isEmpty() -> EmptyState(
                    title = "还没有追更的剧",
                    hint = "在详情页点「追更」，有新集时会出现在这里",
                    icon = FollowsEmptyIcon,
                )
                else -> PosterGrid(
                    cards = cards,
                    onOpen = { onOpen(it.seriesId) },
                    onPlay = { onPlay(it.seriesId) },
                    onCollect = { PcStore.toggle("favorite", it.seriesId) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 更新提醒列表（旧版「更新提醒」tab：未读点 + 标题 + 详情 + 相对时间，点=已读+起播）。 */
@Composable
private fun NoticesList(
    notices: List<org.json.JSONObject>,
    onRead: (Long) -> Unit,
    onPlay: (org.json.JSONObject) -> Unit,
) {
    if (notices.isEmpty()) {
        EmptyState(
            title = "还没有更新提醒",
            hint = "追更的剧有新集时会在这里提醒你；也可点「检查更新」立即查",
            icon = FollowsEmptyIcon,
        )
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        items(notices.size) { i ->
            val n = notices[i]
            val read = n.optBoolean("read", false)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Palette.panel)
                    .border(1.dp, Palette.line, RoundedCornerShape(12.dp))
                    .clickable { onPlay(n) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.NotificationsActive,
                    contentDescription = null,
                    tint = if (read) Palette.dim else Palette.accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            n.optString("title"),
                            color = Palette.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!read) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.size(7.dp).clip(RoundedCornerShape(50))
                                    .background(Palette.accent),
                            )
                        }
                    }
                    Text(n.optString("detail"), color = Palette.dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(historyDate(n.optLong("at", 0L)), color = Palette.faint, fontSize = 11.sp)
                }
                Spacer(Modifier.width(12.dp))
                PrimaryButton("播放", Icons.Filled.PlayArrow) { onPlay(n) }
            }
        }
    }
}

// ---------------------------------------------------------------- 预约（已上线 / 待上线）

@Composable
private fun ReservePage(onOpen: (String) -> Unit, onPlay: (String) -> Unit) {
    var cards by remember { mutableStateOf<List<SeriesCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(0) } // 0=已上线（旧版默认 tab）
    var search by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        loading = true
        cards = withContext(Dispatchers.IO) { runCatching { Api.reserveList() }.getOrDefault(emptyList()) }
        loading = false
    }

    val online = cards.filter { !it.upcoming }
    val upcoming = cards.filter { it.upcoming }
    val onlineCount = online.size
    val upcomingCount = upcoming.size
    val shown = (if (tab == 0) online else upcoming)
        .filter { search.isBlank() || it.title.contains(search.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabChip("已上线 $onlineCount", tab == 0) { tab = 0 }
            Spacer(Modifier.width(8.dp))
            TabChip("待上线 $upcomingCount", tab == 1) { tab = 1 }
            Spacer(Modifier.weight(1f))
            ListSearchBox(search, { search = it }, "搜索预约的剧")
        }
        when {
            loading -> CenterLoading()
            shown.isEmpty() -> EmptyState(
                title = if (tab == 0) "还没有已上线的预约" else "还没有待上线的预约",
                hint = "在详情页点「预约」，开播时会提醒你",
            )
            // 行卡形态（旧版 38 图：横向行卡 + 行尾播放/详情，非海报墙）
            else -> androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(shown, key = { it.seriesId }) { card ->
                    ReservationRow(card, onOpen = { onOpen(card.seriesId) }, onPlay = { onPlay(card.seriesId) })
                }
            }
        }
    }
}

/** 预约行卡：封面 | 标题/简介/上线信息 | 播放+详情（已上线）/ 预约态（待上线）。 */
@Composable
private fun ReservationRow(card: SeriesCard, onOpen: () -> Unit, onPlay: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (hovered) Color(0x4DFFFFFF) else Palette.line, RoundedCornerShape(12.dp))
            .background(Palette.panel)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onOpen() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            card.cover,
            Modifier.width(80.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)),
            fallback = card.title,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(card.title, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // 评分：星形 + 黄字（与详情页/排行榜同语言）
                card.score.takeIf { it.isNotBlank() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Palette.star, modifier = Modifier.size(12.dp))
                        Text(it, color = Palette.star, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                // 上线状态徽标（旧版 success/secondary badge）
                val online = !card.upcoming
                Text(
                    if (online) "已上线" else "待上线",
                    color = if (online) Palette.text else Palette.dim,
                    fontSize = 10.sp,
                    modifier = Modifier.border(1.dp, Palette.line, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            val meta = listOf(
                card.subtitle?.takeIf { it.isNotBlank() },
                card.tags.take(3).joinToString(" · ").takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            card.desc?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Palette.dim.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val rec = card.recText?.takeIf { it.isNotBlank() }
            val secondary = card.secondary?.filter { it.isNotBlank() && it != rec }.orEmpty()
            if (rec != null || secondary.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    rec?.let { HeatBadge(it) }
                    if (secondary.isNotEmpty()) {
                        Text(secondary.joinToString("  "), color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!card.upcoming) {
                PrimaryButton("播放", Icons.Filled.PlayArrow, onPlay)
                Text(
                    "详情",
                    color = if (hovered) Palette.text else Palette.dim,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onOpen() }.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            } else {
                Text(card.statusText ?: "待上线", color = Palette.faint, fontSize = 12.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- 我的 / 设置（先接静态信息，登录下一步）

@Composable
private fun MinePage(onOpen: (String) -> Unit, onPlay: (String) -> Unit, onLogin: () -> Unit) {
    // 用户定位：我的页 = 账号页。收藏/点赞/管理/搜索全部移除，
    // 只留「账号状态 + 手机号登录 + 匹配码同步」三件事。
    var pairCode by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        // 账户行（旧版 mine 顶部：头像 + 昵称，右侧登录/退出，不用整块卡片）
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(50))
                    .background(Palette.panelHi)
                    .border(1.dp, if (PcStore.loggedIn) Palette.accent else Palette.line, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = if (PcStore.loggedIn) Palette.accent else Palette.faint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (PcStore.loggedIn) PcStore.accountName.ifBlank { "用户" } else "未登录",
                    color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (PcStore.loggedIn) "收藏 / 追更 / 预约 / 历史都在跟着账号走"
                    else "手机号登录，或用匹配码把电视/手机的登录态接过来",
                    color = Palette.dim, fontSize = 12.sp,
                )
            }
            if (!PcStore.loggedIn) {
                PrimaryButton("手机号登录", null, onLogin)
                Spacer(Modifier.width(8.dp))
            }
            if (PcStore.loggedIn) {
                Spacer(Modifier.width(8.dp))
                OutlineButton("退出") {
                    PcStore.accountCookies = ""
                    PcStore.accountName = ""
                    status = "已退出登录"
                }
            }
        }

        // 匹配码：把电视/手机上已经登录的账号接过来（本页核心功能，常驻展示）
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.panel).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("用匹配码同步账号", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("在电视/手机的「我的」里生成 6 位匹配码，填在这里", color = Palette.dim, fontSize = 12.sp)
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Palette.accent, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.width(140.dp).height(34.dp).clip(RoundedCornerShape(8.dp))
                    .background(Palette.panelHi)
                    .border(1.dp, Palette.line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (pairCode.isEmpty()) {
                    Text("6 位匹配码", color = Palette.faint, fontSize = 12.sp)
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = pairCode,
                    onValueChange = { pairCode = it.filter { c -> c.isLetterOrDigit() }.take(12) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.accent),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            PrimaryButton("同步", null) {
                val code = pairCode.trim()
                if (code.length < 4) {
                    status = "匹配码至少 4 位"
                } else {
                    status = "同步中…"
                    scope.launch(Dispatchers.IO) {
                        val result = runCatching {
                            val name = Api.pairFetch(code)
                            Api.syncAccount(force = true)
                            name
                        }.getOrElse { it.message ?: "同步失败" }
                        withContext(Dispatchers.Main) { status = result.ifBlank { "已同步登录态" } }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "收藏 / 点赞 / 观看记录分别在对应板块查看；本页只负责账号与设备同步。",
            color = Palette.faint, fontSize = 12.sp, lineHeight = 18.sp,
        )
    }
}

@Composable
private fun SettingsPage() {
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                // 服务配置（内容接口内置签名；播放才需要自备取流服务）
                SettingBlock("服务配置", icon = Icons.Filled.Settings) {
                    var sign by remember { mutableStateOf(PcStore.signUrl) }
                    var bases by remember { mutableStateOf(PcStore.playBases) }
                    var token by remember { mutableStateOf(PcStore.playToken) }
                    var hist by remember { mutableStateOf(PcStore.historyBase) }
                    if (!PcStore.configured) {
                        Text(
                            "已内置本机取流服务，装好就能看。想换成自己的取流服务，在下面填地址即可。",
                            color = Palette.accent, fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    ServiceField("取流服务", bases, "留空 = 用内置本机取流（推荐）") { bases = it }
                    Spacer(Modifier.height(8.dp))
                    ServiceField(
                        "签名服务",
                        sign,
                        "留空 = 内置签名（推荐）；填地址 = 走你自己的代签服务",
                    ) { sign = it }
                    Spacer(Modifier.height(8.dp))
                    ServiceField("取流令牌", token, "取流服务的 X-Token，可留空") { token = it }
                    Spacer(Modifier.height(8.dp))
                    ServiceField("历史基址", hist, "可留空，默认与取流服务同源") { hist = it }
                    Spacer(Modifier.height(8.dp))
                    var did by remember { mutableStateOf(PcStore.deviceId) }
                    var iid by remember { mutableStateOf(PcStore.deviceIid) }
                    var cdid by remember { mutableStateOf(PcStore.deviceCdid) }
                    ServiceField("设备 device_id", did, "可留空（留空用内置设备）") { did = it }
                    Spacer(Modifier.height(8.dp))
                    ServiceField("设备 iid", iid, "可留空（留空用内置设备）") { iid = it }
                    Spacer(Modifier.height(8.dp))
                    ServiceField("设备 cdid", cdid, "可留空（留空用内置设备）") { cdid = it }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlineButton("保存") {
                            PcStore.signUrl = sign
                            PcStore.playBases = bases
                            PcStore.playToken = token
                            PcStore.historyBase = hist
                            PcStore.deviceId = did
                            PcStore.deviceIid = iid
                            PcStore.deviceCdid = cdid
                            applyServiceConfig()
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("保存后立即生效（无需重启）", color = Palette.faint, fontSize = 11.sp)
                    }
                }
                // 账户（旧版 settings 第一张卡）：头像 + 昵称 + 红果号 + 退出，
                // 下面挂「设备匹配」——把本机登录态绑到匹配码上，别的设备填码即同步
                SettingBlock("账户", icon = Icons.Filled.Person) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(50))
                                .background(Palette.panelHi)
                                .border(1.dp, if (PcStore.loggedIn) Palette.accent else Palette.line, RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                tint = if (PcStore.loggedIn) Palette.accent else Palette.faint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (PcStore.loggedIn) PcStore.accountName.ifBlank { "已登录" } else "未登录",
                                color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (PcStore.loggedIn) "收藏 / 追更 / 预约 / 历史都在跟着账号走" else "手机号登录去「我的」页",
                                color = Palette.dim, fontSize = 12.sp,
                            )
                        }
                        if (PcStore.loggedIn) {
                            OutlineButton("退出登录") {
                                PcStore.accountCookies = ""
                                PcStore.accountName = ""
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    // 设备匹配
                    var pairCode by remember { mutableStateOf("") }
                    var pairHint by remember { mutableStateOf<String?>(null) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("设备匹配", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (PcStore.loggedIn) "主设备" else "未绑定",
                            color = if (PcStore.loggedIn) Palette.accent else Palette.faint,
                            fontSize = 10.sp,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                .background(Color(0x1AFF4D4F)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "把本机登录态绑到匹配码上，其它设备输入它，就能用同一个账号（收藏/追更/预约同步）。",
                        color = Palette.faint, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(140.dp).height(34.dp).clip(RoundedCornerShape(8.dp))
                                .background(Palette.coverPlaceholder)
                                .border(1.dp, Palette.line, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (pairCode.isEmpty()) Text("匹配码", color = Palette.faint, fontSize = 12.sp)
                            androidx.compose.foundation.text.BasicTextField(
                                value = pairCode,
                                onValueChange = { pairCode = it.filter { c -> c.isLetterOrDigit() }.take(12) },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 14.sp),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.accent),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        PrimaryButton("保存并共享", null) {
                            val code = pairCode.trim()
                            when {
                                code.length < 4 -> pairHint = "匹配码至少 4 位"
                                !PcStore.loggedIn -> pairHint = "先登录再把匹配码共享给其它设备"
                                else -> {
                                    pairHint = "保存中…"
                                    scope.launch(Dispatchers.IO) {
                                        val r = runCatching { Api.pairUpload(code) }
                                        withContext(Dispatchers.Main) {
                                            pairHint = r.fold(
                                                onSuccess = { "已保存：其它设备输入 $code 即可同步" },
                                                onFailure = { it.message ?: "保存失败" },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    pairHint?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Palette.accent, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 外观（旧版 theme-switch）：主题三选
                SettingBlock("外观") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("主题", color = Palette.text, fontSize = 14.sp, modifier = Modifier.width(96.dp))
                        listOf("auto" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (k, label) ->
                            TabChip(label, PcStore.theme == k) {
                                PcStore.theme = k
                                Palette.applyTheme(k)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 播放（旧版 playback 卡）：三开关 + 连播预取 + 清晰度上限
                SettingBlock("播放") {
                    SettingToggle("播完自动下一集", PcStore.autoNextEpisode) { PcStore.autoNextEpisode = it }
                    Spacer(Modifier.height(10.dp))
                    SettingToggle("看完自动删除记录", PcStore.deleteAfterFinish) { PcStore.deleteAfterFinish = it }
                    Spacer(Modifier.height(10.dp))
                    SettingToggle("最小化时自动暂停", PcStore.pauseOnMinimize) { PcStore.pauseOnMinimize = it }
                    Spacer(Modifier.height(14.dp))
                    // 连播预取集数（旧版 issue #6）：0=关闭。
                    // 紧凑步进器（用户反馈整条滑杆太丑）：− 数字 +，行内完成
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "连播预取集数",
                            color = Palette.text, fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        StepBox("−", enabled = PcStore.prefetchEpisodes > 0) {
                            PcStore.prefetchEpisodes = (PcStore.prefetchEpisodes - 1).coerceAtLeast(0)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (PcStore.prefetchEpisodes == 0) "关闭" else "${PcStore.prefetchEpisodes} 集",
                            color = if (PcStore.prefetchEpisodes == 0) Palette.faint else Palette.text,
                            fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(56.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.width(10.dp))
                        StepBox("+", enabled = PcStore.prefetchEpisodes < 10) {
                            PcStore.prefetchEpisodes = (PcStore.prefetchEpisodes + 1).coerceAtMost(10)
                        }
                    }
                    Text("起播后自动向后预接下来几集的分集档案，切集秒开；0 = 关闭。", color = Palette.faint, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("清晰度上限：${PcStore.maxHeight}P", color = Palette.text, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1080, 720, 540).forEach { h ->
                            TabChip("${h}P", PcStore.maxHeight == h) { PcStore.maxHeight = h }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("自动档按这个上限挑清晰度；播放器清晰度菜单里的「自动」同源。", color = Palette.faint, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                // 下载（旧版 download 页的等价形态：本集单文件任务列表）
                SettingBlock("下载") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (Downloader.tasks.isEmpty()) "播放页点「下载」把当前集存到本地（原画质 remux，不转码）" else "共 ${Downloader.tasks.size} 个任务",
                            color = Palette.dim, fontSize = 12.sp, modifier = Modifier.weight(1f),
                        )
                        OutlineButton("打开目录") { Downloader.openFolder() }
                    }
                    Downloader.tasks.forEach { t ->
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${t.title} · 第${t.episode}集",
                                    color = Palette.text, fontSize = 13.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50))
                                            .background(Palette.panelHi),
                                    ) {
                                        Box(
                                            Modifier.fillMaxWidth(t.progress.coerceIn(0f, 1f)).fillMaxHeight()
                                                .clip(RoundedCornerShape(50))
                                                .background(if (t.state == Downloader.State.FAILED) Palette.dim else Palette.accentFill),
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    val errMsg = t.error
                                    Text(
                                        when (t.state) {
                                            Downloader.State.DONE -> "已完成"
                                            Downloader.State.FAILED -> if (errMsg.isNullOrBlank()) "失败" else "失败：$errMsg"
                                            Downloader.State.CANCELED -> "已取消"
                                            else -> "${(t.progress * 100).toInt()}%"
                                        },
                                        color = Palette.faint, fontSize = 11.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "删除",
                                color = Palette.faint, fontSize = 11.sp,
                                modifier = Modifier.clickable { Downloader.delete(t) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 磁盘占用（旧版 storage 卡）
                SettingBlock("磁盘占用") {
                    var cacheBump by remember { mutableStateOf(0) }
                    val coversDir = java.io.File(
                        System.getenv("APPDATA") ?: ".",
                        "KaixinguoPC/covers",
                    )
                    val files = remember(cacheBump) { coversDir.listFiles()?.filter { it.isFile }.orEmpty() }
                    val totalMb = remember(cacheBump) { files.sumOf { it.length() } / 1024.0 / 1024.0 }
                    Text("缓存目录", color = Palette.dim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            coversDir.absolutePath,
                            color = Palette.text, fontSize = 12.sp,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                .background(Palette.coverPlaceholder)
                                .border(1.dp, Palette.line, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlineButton("打开目录") { runCatching { java.awt.Desktop.getDesktop().open(coversDir) } }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "封面缓存占用 %.1f MB · %d 个文件".format(totalMb, files.size),
                            color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f),
                        )
                        OutlineButton("清理缓存") {
                            files.forEach { it.delete() }
                            Covers.clearMemory()
                            cacheBump += 1
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("清理后封面会在浏览时自动重新下载。", color = Palette.faint, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                SettingBlock("关于") {
                    Text("开心果 PC 版 1.1.1", color = Palette.text, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("数据目录：%APPDATA%\\KaixinguoPC", color = Palette.faint, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "签名：" + (PcStore.signUrl.ifBlank { "内置（本地签名）" }),
                        color = Palette.faint, fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 设置行开关（旧版 ToggleRow 同语义：左标签右开关）。 */
@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.width(44.dp).height(24.dp).clip(RoundedCornerShape(50))
                .background(if (checked) Palette.accentFill else Palette.panelHi)
                .clickable { onChange(!checked) }
                .padding(2.dp),
        ) {
            Box(
                Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .size(20.dp).clip(RoundedCornerShape(50)).background(Color.White),
            )
        }
    }
}

@Composable
private fun SettingBlock(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .border(1.dp, Palette.line, RoundedCornerShape(12.dp))
            .background(Palette.panel).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Palette.text, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

// ---------------------------------------------------------------- 通用

/** 服务配置的一行输入（标签 + 输入框 + 占位提示）。 */
@Composable
private fun ServiceField(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.text, fontSize = 13.sp, modifier = Modifier.width(80.dp))
        Box(
            Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp))
                .background(Palette.panelHi)
                .border(1.dp, Palette.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(hint, color = Palette.faint, fontSize = 12.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(color = Palette.text, fontSize = 12.sp),
                cursorBrush = SolidColor(Palette.accent),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 小步进按钮（设置页 −/+）：28px 方块，禁用置灰。 */
@Composable
fun StepBox(symbol: String, enabled: Boolean = true, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier.size(28.dp).clip(Radius.ctrl)
            .background(
                when {
                    !enabled -> Palette.panel
                    hovered -> Palette.panelHi
                    else -> Palette.coverPlaceholder
                },
            )
            .border(1.dp, if (enabled && hovered) Palette.dim.copy(alpha = 0.3f) else Palette.line, Radius.ctrl)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            color = if (enabled) Palette.text else Palette.faint,
            fontSize = 14.sp, fontWeight = FontWeight.Medium,
        )
    }
}

/** 筛选选项胶囊（旧版找剧筛选规格）：未选中也有「身体」——卡面底 + 描边 +
 *  hover 抬亮；选中 = 红底白字。纯文字胶囊在多行筛选里会「飘」，行与行
 *  看不出结构（旧版实测反馈「乱糟糟」后的修正）。 */
@Composable
fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    active -> Palette.accentFill
                    hovered -> Palette.panelHi
                    else -> Palette.panel
                },
            )
            .border(
                1.dp,
                when {
                    active -> Palette.accentFill
                    hovered -> Palette.dim.copy(alpha = 0.30f)
                    else -> Palette.line
                },
                RoundedCornerShape(50),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (active) Color.White else Palette.dim,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun CenterLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Palette.accent, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    }
}

/** 墙形骨架屏（旧版 WallSkeleton）：转圈换成卡片灰砖，首屏观感更稳。 */
@Composable
fun SkeletonWall(columns: Int = 8, rows: Int = 2) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        repeat(rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(columns) {
                    Column(Modifier.weight(1f)) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Palette.panelHi),
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth(0.7f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Palette.panelHi))
                    }
                }
            }
        }
    }
}

@Composable
fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.dim, fontSize = 14.sp)
    }
}
