@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.kaixinguo.pc

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
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
/** 品牌图标：与安装包 / 安卓启动图标同一张（红底竖屏 + 播放孔）。 */
private val brandBitmap: ImageBitmap? by lazy {
    runCatching {
        val stream = object {}.javaClass.getResourceAsStream("/brand.png")
            ?: return@runCatching null
        stream.use { javax.imageio.ImageIO.read(it).toComposeImageBitmap() }
    }.getOrNull()
}

/** 跨页面保留的浏览状态：进详情/播放再返回时回到原处（原来一离开页面状态就全丢）。 */
private object PageMemo {
    var feedTab = 0   // 0=漫剧（三端默认）
    var feedCursor = 0
    var feedCards: List<SeriesCard> = emptyList()
    var feedPage: FeedPage? = null

    var homeTab = -1
    var homeRefresh = -1

    // 找剧：筛选条件 + 已加载结果一起存。只存滚动位置的话，用户从详情返回会
    // 看到「筛选被清空、列表回到第一页，但页面停在原来那段」的错位内容。
    var findSelected: Map<String, String> = emptyMap()
    /** 是否已套用过默认体裁（漫剧） */
    var findDefaulted: Boolean = false
    var findPage: FeedPage? = null
    var findRows: List<FilterRow> = emptyList()

    val homeWall = androidx.compose.foundation.ScrollState(0)
    val findScroll = androidx.compose.foundation.ScrollState(0)
    val rankList = androidx.compose.foundation.lazy.LazyListState(0, 0)
    val newScroll = androidx.compose.foundation.ScrollState(0)
    val calList = androidx.compose.foundation.lazy.LazyListState(0, 0)
    val settingsScroll = androidx.compose.foundation.ScrollState(0)
}

/** 官网（设置页「关于」右侧：二维码 + 地址，扫码或点击打开）。 */
private const val OFFICIAL_SITE = "https://kxg.fabao.xin/"

private val officialQrBitmap: ImageBitmap? by lazy {
    runCatching {
        val stream = object {}.javaClass.getResourceAsStream("/official_qr.png")
            ?: return@runCatching null
        stream.use { javax.imageio.ImageIO.read(it).toComposeImageBitmap() }
    }.getOrNull()
}

/** 单实例锁：必须常驻引用，局部变量被回收 = 锁被释放，双实例又会出现。 */
private object SingleInstance {
    var channel: java.nio.channels.FileChannel? = null
    var lock: java.nio.channels.FileLock? = null
}

fun main() = application {
    // 单实例：再双击一次图标不该开第二个窗口（两个实例的登录态/设置会互相覆盖）
    runCatching {
        val dir = java.io.File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "KaixinguoPC")
        dir.mkdirs()
        val ch = java.nio.channels.FileChannel.open(
            java.io.File(dir, "app.lock").toPath(),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
        )
        val lk = runCatching { ch.tryLock() }.getOrNull()
        if (lk == null) {
            ch.close()
            kotlin.system.exitProcess(0)
        }
        SingleInstance.channel = ch
        SingleInstance.lock = lk
    }
    // 本机到更新源的 IPv4 连接会被本地代理链路直接重置（curl -4 返回 000，IPv6 正常），
    // 而 Java 默认先走 IPv4 → 「检查更新」永远失败（旧版还把它显示成「已是最新」）。
    // 优先 IPv6；纯 IPv4 网络仍会回退，不影响其他机器。
    System.setProperty("java.net.preferIPv6Addresses", "true")
    boot()
    val state = rememberWindowState(width = 1440.dp, height = 900.dp)
    // 无边框（旧版 decorations:false 同语义）：系统白条不画，自绘窗口钮+拖拽区
    Window(
        onCloseRequest = ::exitApplication,
        title = "开心果",
        state = state,
        undecorated = true,
        transparent = false,
        icon = brandBitmap?.let { BitmapPainter(it) },
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
            // 小窗 / 全屏播放：顶栏整条藏掉，整窗只剩画面
            if (!UiMode.mini.value && !UiMode.fullscreen.value) {
            // 顶栏：44px 薄行（左：logo + 应用名 + 日期；右：窗口钮）
            Row(
                Modifier.fillMaxWidth().height(44.dp).background(Palette.panel),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                        .background(Palette.accentFill),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = brandBitmap
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bmp,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        Text("果", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
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
            androidx.compose.runtime.mutableStateOf(PcStore.setupTipSeen)
        }
        if (!setupDismissed) {
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
                        "内容服务和播放服务都已内置，装好就能浏览和播放，不需要填任何服务器地址。想换成自己的服务，可以在「设置 → 服务配置」里改。",
                        color = Palette.dim, fontSize = 13.sp, lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        SecondaryButton("知道了") { setupDismissed = true; PcStore.setupTipSeen = true }
                        Spacer(Modifier.width(8.dp))
                        PrimaryButton("打开设置", Icons.Filled.Settings) {
                            RouteHolder.select("设置")
                            setupDismissed = true
                            PcStore.setupTipSeen = true
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
    /** 全屏播放：侧栏和顶栏一起藏掉，真正铺满（以前只是把窗口最大化，侧栏还占 72px） */
    val fullscreen = androidx.compose.runtime.mutableStateOf(false)
    var prevBounds: java.awt.Rectangle = java.awt.Rectangle(0, 0, 1440, 900)
    var saved = false
    fun exitAll() { portrait.value = false; mini.value = false; fullscreen.value = false }
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
    Log.sink = { level, tag, message ->
        println("$level/$tag: $message")
        // 打包版没有控制台：同时落盘，出问题让用户把 app.log 发回来即可定位
        runCatching { DiagLog.write(level, tag, message) }
    }
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
    // 这几个值背后是 mutableState：必须直接读写 holder，
    // 不能 `var x = holder.value` 再局部赋值——那样只改了闭包里的副本，
    // 既不触发重组、也写不回状态（表现为输入框打不进字、返回没反应）。
    val page = RouteHolder.page.value
    val collapsed = RouteHolder.collapsed.value
    val search = SearchHolder.query.value
    var tab by remember { mutableStateOf(1) }   // 0=推荐 1=漫剧 2=真人剧；三端默认漫剧
    var refresh by remember { mutableStateOf(0) }
    var route by remember { mutableStateOf<Route?>(null) }
    // 二级页返回栈：详情 → 播放 → 返回应该回详情，而不是直接掉回首页。
    // 以前 route 是单值，Player 的返回一律 route=null，用户想换集/看简介得重新找剧。
    val navStack = remember { mutableStateListOf<Route>() }
    fun nav(next: Route) {
        route?.let { if (it != next) navStack.add(it) }
        route = next
    }
    fun navBack() {
        route = if (navStack.isNotEmpty()) navStack.removeAt(navStack.lastIndex) else null
    }
    fun navReset() {
        navStack.clear()
        route = null
    }
    // 从任意入口点「播放」都接着上次看的地方：以前只有历史页/详情页会续播，
    // 首页、排行榜、新剧、追更、预约一点就回第 1 集。
    fun playSeries(id: String) {
        val ep = runCatching {
            com.kaixinguo.pc.PcStore.history().firstOrNull { it.optString("seriesId") == id }
                ?.optInt("episode", 0) ?: 0
        }.getOrDefault(0)
        nav(Route.Player(id, if (ep > 0) ep else 1))
    }
    // 打赏浮层：侧栏「打赏」入口，纯展示不接支付
    var donateOpen by remember { mutableStateOf(false) }
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
        if ((route == null || route is Route.Player) && !UiMode.mini.value &&
            !UiMode.portrait.value && !UiMode.fullscreen.value
        ) {
            Sidebar(
                if (route is Route.Player) "刷剧" else page,
                collapsed,
                onSelect = {
                    if (it == "打赏") donateOpen = true else { RouteHolder.select(it); navReset() }
                },
                onToggle = { RouteHolder.toggle() },
                badge = unreadNotices,
            )
        }
        Column(Modifier.weight(1f)) {
            when (val r = route) {
                Route.Login -> LoginScreen(onBack = { navBack() })
                is Route.Creator -> CreatorScreen(
                    r.userId,
                    onBack = { navBack() },
                    onPlaySeries = { id -> nav(Route.Player(id)) },
                )
                is Route.Detail -> DetailScreen(r.seriesId, initialTab = r.tab, onBack = { navBack() }, onPlay = { ep -> nav(Route.Player(r.seriesId, ep)) }, onOpenSeason = { nav(Route.Detail(it)) }, onCreator = { nav(Route.Creator(it)) })
                is Route.Player -> PlayerScreen(r.seriesId, r.index, r.startMs, onBack = { UiMode.exitAll(); navBack() }, onDetail = { nav(Route.Detail(r.seriesId)) }, onComments = { nav(Route.Detail(r.seriesId, tab = 1)) }, onSwitchSeries = { id -> route = Route.Player(id, 1) })
                null -> {
                    when (page) {
                        "刷剧" -> FeedPlayerPage(
                            onDetail = { nav(Route.Detail(it)) },
                            onComments = { nav(Route.Detail(it, tab = 1)) },
                            onExit = { RouteHolder.select("推荐") },
                        )
                        "找剧" -> FindPage(
                            search,
                            onSearch = { SearchHolder.query.value = it },
                            onOpen = { nav(Route.Detail(it)) },
                            onPlay = { id -> playSeries(id) },
                        )
                        "排行榜" -> RankPage(
                            onOpen = { nav(Route.Detail(it)) },
                            onPlay = { playSeries(it) },
                        )
                        "新剧" -> NewPage(
                            onOpen = { nav(Route.Detail(it)) },
                            onPlay = { id -> playSeries(id) },
                        )
                        "历史" -> HistoryPage(
                            onOpen = { nav(Route.Detail(it)) },
                            onPlay = { id, ep, pos -> nav(Route.Player(id, ep, pos)) },
                        )
                        "追更" -> FollowsPage(
                            onOpen = { nav(Route.Detail(it)) },
                            onPlay = { id -> playSeries(id) },
                            onPlayEp = { id, ep -> nav(Route.Player(id, ep)) },
                            onLogin = { nav(Route.Login) },
                        )
                        "预约" -> ReservePage(
                            onOpen = { nav(Route.Detail(it)) },
                            onPlay = { playSeries(it) },
                        )
                        "我的" -> MinePage(
                            onOpen = { nav(Route.Detail(it)) },
                            onPlay = { id -> playSeries(id) },
                            onLogin = { nav(Route.Login) },
                        )
                        "设置" -> SettingsPage()
                        else -> HomePage(
                            tab = tab,
                            refresh = refresh,
                            onTab = { tab = it },
                            onRefresh = { refresh++ },
                            onOpen = { nav(Route.Detail(it)) },
                            onPlay = { id -> playSeries(id) },
                        )
                    }
                }
            }
        }
    }
    // 打赏浮层盖在最上层（侧栏/播放页都在它下面）
    if (donateOpen) DonateOverlay(onClose = { donateOpen = false })
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
    /** 推荐墙的滚动状态：滚到靠底就自动续拉，不再放按钮 */
    val wallScroll = PageMemo.homeWall

    val tabTypes = listOf("2", "3", "4")

    LaunchedEffect(tab, refresh) {
        // 只有切 tab / 换一批才回到墙顶；从详情返回时保留原来的滚动位置
        val resetWall = PageMemo.homeTab != tab || PageMemo.homeRefresh != refresh
        PageMemo.homeTab = tab
        PageMemo.homeRefresh = refresh
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching { Api.firstPageCached(tabTypes[tab.coerceIn(0, tabTypes.lastIndex)]) }.getOrNull()
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
        error = if (cur == null || cur.cards.isEmpty()) "暂时没能加载出来 · 检查网络后重试" else null
        loading = false
        // 换 tab / 换一批回到墙顶（滚动状态是常驻的，不重置会停在上一次的底部）
        if (resetWall) runCatching { wallScroll.scrollTo(0) }
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
        Api.clearListCache()   // 换一批：作废列表缓存，强制拉新
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
            runCatching { wallScroll.scrollTo(0) }
        }
    }

    /** 自动续拉下一页：滚到靠底 / 首屏不满两屏时都会被调用 */
    fun loadMore() {
        val c = cursor ?: return
        if (moreLoading || !c.hasMore) return
        moreLoading = true
        scope.launch {
            val type = tabTypes[tab.coerceIn(0, tabTypes.lastIndex)]
            val next = withContext(Dispatchers.IO) {
                runCatching {
                    Api.feedNext(type, c.cellId, c.planId, c.sessionId, c.nextOffset, cards.map { it.seriesId })
                }.getOrNull()
            }
            val cur = page
            if (next != null && next.cards.isNotEmpty() && cur != null) {
                val known = cur.cards.map { it.seriesId }.toSet()
                page = next.copy(cards = cur.cards + next.cards.filter { it.seriesId !in known })
                cursor = next
            } else {
                cursor = c.copy(hasMore = false)
            }
            moreLoading = false
        }
    }

    // 滚到离底 700px 内自动拉下一页（用户要求：不要按钮，滑到底自己出）
    LaunchedEffect(wallScroll, cursor?.hasMore, cursor?.nextOffset, moreLoading) {
        if (cursor?.hasMore != true) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { wallScroll.value to wallScroll.maxValue }
            .collect { (v, max) ->
                if (max > 0 && max - v < 700) loadMore()
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
                    error != null && (page?.cards.isNullOrEmpty()) -> CenterRetry(error!!) { onRefresh() }
                    else -> Column(Modifier.fillMaxSize().verticalScroll(wallScroll)) {
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
                            coverBadge = { c -> if (c.episodeCount > 0) "${c.episodeCount} 集" else null },
                            tagsLine = { c -> c.tags.take(3).joinToString(" ").takeIf { it.isNotBlank() } },
                        )
                        // 底部只是一行状态：滚动到底自动续拉，不再有「加载更多」按钮
                        val c = cursor
                        if (page != null) {
                            Spacer(Modifier.height(20.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 24.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (moreLoading) {
                                    CircularProgressIndicator(
                                        color = Palette.accent,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text("正在加载更多…", color = Palette.faint, fontSize = 12.sp)
                                } else if (c != null && !c.hasMore && cards.isNotEmpty()) {
                                    Text("已经到底了 · 共 ${cards.size} 部", color = Palette.faint, fontSize = 12.sp)
                                } else if (c != null && c.hasMore && cards.isNotEmpty()) {
                                    Text("继续下滑，自动加载更多", color = Palette.faint, fontSize = 12.sp)
                                }
                            }
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
    var tab by remember { mutableStateOf(PageMemo.feedTab) }
    var cards by remember { mutableStateOf(PageMemo.feedCards) }
    var cursor by remember { mutableStateOf(PageMemo.feedCursor) }
    var loading by remember { mutableStateOf(cards.isEmpty()) }
    /** 翻页游标（cellId/planId/sessionId/offset 都在里面），hasMore=false 后不再翻 */
    var feedCursor by remember { mutableStateOf(PageMemo.feedPage) }
    var moreLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tab) {
        // 从详情/播放返回：列表和位置都还在，直接接回来
        if (cards.isNotEmpty() && tab == PageMemo.feedTab) {
            loading = false
            return@LaunchedEffect
        }
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

    // 位置随变随记：进详情时这一页会离开组合，状态只能放在单例里
    LaunchedEffect(tab, cursor, cards, feedCursor) {
        PageMemo.feedTab = tab
        PageMemo.feedCursor = cursor
        PageMemo.feedCards = cards
        PageMemo.feedPage = feedCursor
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
            hint = "检查网络后重试",
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
    // 首次进入用 PageMemo 里的上次状态（从详情/播放返回时原样恢复）
    var rows by remember { mutableStateOf(PageMemo.findRows) }
    var rowsLoading by remember { mutableStateOf(PageMemo.findRows.isEmpty()) }
    var selected by remember {
        mutableStateOf(
            PageMemo.findSelected.ifEmpty {
                if (PageMemo.findDefaulted) emptyMap()
                else {
                    PageMemo.findDefaulted = true
                    mapOf("genre" to "comic_series")   // 三端默认漫剧
                }
            },
        )
    }
    var page by remember { mutableStateOf(PageMemo.findPage) }
    var restoredRows by remember { mutableStateOf(PageMemo.findRows.isNotEmpty()) }
    var restoredList by remember { mutableStateOf(PageMemo.findPage != null) }
    var loading by remember { mutableStateOf(false) }
    var moreLoading by remember { mutableStateOf(false) }
    /** 「搜索」按钮的重搜信号：输入是即时的，按钮只是手动再来一次 */
    var searchTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    // 整页一个滚动容器（旧版同款：筛选随内容一起滚走，不是上半屏钉死）
    val pageScroll = PageMemo.findScroll

    // 体裁（大类）一变，主题/设定/背景整块换一套——不同体裁下小类完全不同。
    // 以前这里写死 findRowsCached("landpage")：面板永远是 tab 38（真人剧）那一套，
    // 选「漫剧 + 悬疑」时把真人剧的悬疑 id 发给漫剧频道，结果必然是空。
    val genreSel = selected["genre"].orEmpty()
    LaunchedEffect(genreSel) {
        if (restoredRows) {
            restoredRows = false
            return@LaunchedEffect
        }
        rowsLoading = true
        val r = withContext(Dispatchers.IO) {
            runCatching { Api.findRowsCached(genreSel) }.getOrDefault(emptyList())
        }
        rows = r
        // 新面板里已经没有的旧选择自动作废（和 Android 端同一口径）
        val valid = r.associate { row -> row.key to row.items.map { it.id }.toSet() }
        selected = selected.filter { (k, v) -> k == "genre" || valid[k]?.contains(v) == true }
        rowsLoading = false
    }

    LaunchedEffect(selected, search, searchTick) {
        if (restoredList) {
            restoredList = false
            return@LaunchedEffect
        }
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

    // 写回 PageMemo：离开页面后这些状态还在，返回时列表和筛选对得上
    LaunchedEffect(rows) { PageMemo.findRows = rows }
    LaunchedEffect(selected, page) {
        PageMemo.findSelected = selected
        PageMemo.findPage = page
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
            PrimaryButton("搜索", null) { searchTick++ }
            Spacer(Modifier.width(14.dp))
            Text("已加载 ${page?.cards?.size ?: 0} 部", color = Palette.dim, fontSize = 12.sp)
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
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
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
                    // 体裁这一行的「全部」等于清空整套筛选：别的小类 id 属于上一个体裁，
                    // 留着会被后端当成非法组合，先闪一下空/一条的结果。
                    FilterChip("全部", selected[row.key].isNullOrEmpty()) {
                        selected = if (row.key == "genre") emptyMap() else selected - row.key
                    }
                    row.items.filter { it.id.isNotEmpty() }.forEach { item ->
                        val active = selected[row.key] == item.id
                        FilterChip(item.label, active) {
                            // 换体裁 = 换一整套主题/设定/背景，旧选择当场作废（不等面板回来才清）
                            selected = if (row.key == "genre") mapOf("genre" to item.id)
                            else selected + (row.key to item.id)
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
                            search.isNotBlank() -> "搜索结果已加载 ${cardsNow.size} 部"
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
    // 月榜（安卓一直有，PC 按用户要求补回）：空 = 当前榜，非空 = 服务器下发的某个月份
    var month by remember { mutableStateOf("") }
    var monthOpen by remember { mutableStateOf(false) }
    var cards by remember { mutableStateOf<List<SeriesCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    /** 这一页榜单数据到底是什么时候拉下来的（描述行的兜底时间，别装成「今天刚更新」） */
    var dataAt by remember { mutableStateOf(0L) }
    /** 榜单元数据（含各子榜的「N月N日已更新」）上次拉取时刻；超过 1 分钟再进/切榜就重拉 */
    var metaAt by remember { mutableStateOf(0L) }
    /** 榜单翻页：上游一页 10 条，滚到底继续要下一页（以前只拉第一页，所以永远只有 10 名） */
    var session by remember { mutableStateOf("") }
    var nextOffset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    val rankList = PageMemo.rankList

    // 榜单元数据拉不到时，以前 tab 为 null、下面的 effect 直接 return，
    // loading 永远 true → 页面无限转圈。现在落成可重试的失败态。
    var metaError by remember { mutableStateOf(false) }
    var metaAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(metaAttempt) {
        val list = withContext(Dispatchers.IO) { runCatching { Api.rankMeta() }.getOrDefault(emptyList()) }
        if (list.isEmpty()) {
            metaError = true
            loading = false
        } else {
            tabs = list
            metaError = false
            metaAt = System.currentTimeMillis()
        }
    }

    val tab = tabs.getOrNull(tabIndex)
    val sub = tab?.subs?.getOrNull(subIndex)

    LaunchedEffect(tab?.id, sub?.id, panel, month) {
        val t = tab ?: return@LaunchedEffect
        // 榜单元数据（子榜的「N月N日已更新」）只在进页面时拉一次的话，
        // 挂着过夜就会一直显示昨天。切榜/换筛选时顺手刷新一下（1 分钟内不重复拉）。
        if (System.currentTimeMillis() - metaAt > 60_000) {
            val fresh = withContext(Dispatchers.IO) { runCatching { Api.rankMeta() }.getOrDefault(emptyList()) }
            if (fresh.isNotEmpty()) {
                tabs = fresh
                metaAt = System.currentTimeMillis()
            }
        }
        loading = true
        val page = withContext(Dispatchers.IO) {
            // 优先走官方榜单（带筛选面板），失败再退回服务器聚合的 chart
            val p = runCatching { Api.rankCached(0, "", t.id, sub?.id ?: "", panel, month) }.getOrNull()
            if (p != null && p.cards.isNotEmpty()) {
                p
            } else {
                com.hongguotv.pad.FeedPage(
                    cards = runCatching {
                        Api.chart(metric = sub?.id ?: "", genre = t.id, limit = 60)
                    }.getOrDefault(emptyList()),
                    hasMore = false,
                )
            }
        }
        cards = page.cards
        session = page.sessionId
        nextOffset = page.nextOffset
        hasMore = page.hasMore
        dataAt = System.currentTimeMillis()
        loading = false
        // 换榜回到顶部：必须等 loading=false 之后（列表重新组合出来再滚，
        // 在列表还没挂上时调 scrollToItem 会一直挂起，页面就卡在转圈）
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            runCatching { rankList.scrollToItem(0) }
        }
    }

    // 滚到倒数第三条就开始要下一页（同 find 页的哨兵语义）
    LaunchedEffect(rankList, cards.size, hasMore, tab?.id, sub?.id, panel, month) {
        androidx.compose.runtime.snapshotFlow {
            rankList.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { last ->
            val t = tab ?: return@collect
            if (hasMore && !loadingMore && !loading && last >= cards.size - 3) {
                loadingMore = true
                val next = withContext(Dispatchers.IO) {
                    runCatching { Api.rank(nextOffset, session, t.id, sub?.id ?: "", panel, month) }.getOrNull()
                }
                if (next != null && next.cards.isNotEmpty()) {
                    val known = cards.map { it.seriesId }.toSet()
                    cards = cards + next.cards.filter { it.seriesId !in known }
                    session = next.sessionId
                    nextOffset = next.nextOffset
                    hasMore = next.hasMore
                } else {
                    hasMore = false
                }
                loadingMore = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        // 大类 tab（旧版页内第一行：全部/系列剧/漫剧/真人剧/AI剧）
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, t ->
                TabChip(t.label, index == tabIndex) { tabIndex = index; subIndex = 0; panel = ""; month = "" }
            }
        }
        if (tab != null && tab.subs.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tab.subs.forEachIndexed { index, s ->
                    TabChip(s.label, index == subIndex) { subIndex = index; panel = ""; month = "" }
                }
                // 筛选按钮（旧版 FilterPanelButton）：仅当前子榜自带面板时出现，
                // chip 形态——未选中灰、选中红；面板里单选，选中后 chip 显示所选名
                if (sub != null && sub.panels.any { it.items.isNotEmpty() }) {
                    Spacer(Modifier.width(4.dp))
                    val selectedName = sub.panels.firstNotNullOfOrNull { p ->
                        p.items.firstOrNull { it.id == panel && it.id.isNotEmpty() }?.name
                    }
                    Row(
                        Modifier.height(28.dp).clip(Radius.chip)
                            .background(
                                if (panel.isNotEmpty()) Palette.accent.copy(alpha = 0.16f) else Palette.panel,
                            )
                            .border(
                                1.dp,
                                if (panel.isNotEmpty()) Palette.accent.copy(alpha = 0.45f) else Palette.line,
                                Radius.chip,
                            )
                            .clickable { filterOpen = !filterOpen }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = null,
                            tint = if (panel.isNotEmpty()) Palette.accentHover else Palette.dim,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            selectedName ?: "筛选",
                            color = if (panel.isNotEmpty()) Palette.accentHover else Palette.dim,
                            fontSize = 13.sp,
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = if (panel.isNotEmpty()) Palette.accentHover else Palette.dim,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                // 月榜（安卓有、PC 补回）：服务器给月份才出现；选中后 chip 显示月份名
                if (sub != null && sub.months.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    val monthName = sub.months.firstOrNull { it.id == month }?.name
                    Row(
                        Modifier.height(28.dp).clip(Radius.chip)
                            .background(
                                if (month.isNotEmpty()) Palette.accent.copy(alpha = 0.16f) else Palette.panel,
                            )
                            .border(
                                1.dp,
                                if (month.isNotEmpty()) Palette.accent.copy(alpha = 0.45f) else Palette.line,
                                Radius.chip,
                            )
                            .clickable { monthOpen = !monthOpen }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.DateRange,
                            contentDescription = null,
                            tint = if (month.isNotEmpty()) Palette.accentHover else Palette.dim,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            monthName ?: "月榜",
                            color = if (month.isNotEmpty()) Palette.accentHover else Palette.dim,
                            fontSize = 13.sp,
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = if (month.isNotEmpty()) Palette.accentHover else Palette.dim,
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
            // 描述行用上游给的「N月N日已更新…」；没有就老实写「数据 HH:mm 拉取」。
            // 以前这里写 LocalDate.now()，等于假装今天刚更新过——TV 端显示的是上游日期，
            // 于是同一时刻两端日期对不上（用户实测：TV 11-9 / PC 11-10）。
            Text(
                sub?.desc?.takeIf { it.isNotBlank() }
                    ?: run {
                        val stamp = if (dataAt > 0L) {
                            val t = java.time.Instant.ofEpochMilli(dataAt)
                                .atZone(java.time.ZoneId.systemDefault())
                            "数据 %02d:%02d 拉取".format(t.hour, t.minute)
                        } else ""
                        (if (stamp.isEmpty()) "" else "$stamp · ") + "基于红果热度及个人数据推荐"
                    },
                color = Palette.faint, fontSize = 12.sp,
            )
        }
        // 子榜筛选已收进上方「筛选」按钮弹层（旧版 FilterPanelButton 同构），
        // 不再整面平铺——面板只在子榜自带选项时出现，且不占版面。
        if (tab == null && metaError) {
            CenterRetry("榜单没加载出来（网络问题）") { metaError = false; loading = true; metaAttempt += 1 }
        } else if (loading) {
            CenterLoading()
        } else {
            val list = cards
            androidx.compose.foundation.lazy.LazyColumn(
                state = rankList,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(list) { index, card ->
                    RankRow(index + 1, card, onClick = { onOpen(card.seriesId) }, onPlay = { onPlay(card.seriesId) })
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            when {
                                loadingMore -> "加载中…"
                                hasMore -> "下滑加载更多"
                                list.isEmpty() -> ""
                                else -> "已经到底了"
                            },
                            color = Palette.faint, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
    // 筛选浮层：分组 + 胶囊（和「找剧」同一套观感）。
    // 原来是一列 220dp 长条，选项一多就是一整块往下无限滚、没有任何分组层级，
    // 用户反馈「看不清、不知道在选什么」——改成固定宽度、每组一行标签 + 一排胶囊。
    val subNow = sub
    if (filterOpen && subNow != null) {
        Box(
            Modifier.matchParentSize()
                .onPointerEvent(PointerEventType.Press) { filterOpen = false },
        )
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 24.dp)
                .width(620.dp).heightIn(max = 520.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF7101014))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("筛选", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (panel.isNotEmpty()) {
                    Text(
                        "重置",
                        color = Palette.accent, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .clickable { panel = "" }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            subNow.panels.forEach { p ->
                if (p.items.isEmpty()) return@forEach
                Spacer(Modifier.height(12.dp))
                Text(p.name, color = Palette.faint, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    p.items.forEach { option ->
                        val active = panel == option.id
                        FilterChip(option.name, active) {
                            panel = if (active) "" else option.id
                        }
                    }
                }
            }
        }
    }
    // 月榜浮层：单选，选中即收起；第一项是「当前榜」（= 不带 month 参数）
    if (monthOpen && subNow != null && subNow.months.isNotEmpty()) {
        Box(
            Modifier.matchParentSize()
                .onPointerEvent(PointerEventType.Press) { monthOpen = false },
        )
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 24.dp)
                .width(240.dp).heightIn(max = 420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF7101014))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("月榜", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (month.isNotEmpty()) {
                    Text(
                        "重置",
                        color = Palette.accent, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .clickable { month = "" }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip("当前榜", month.isEmpty()) { month = ""; monthOpen = false }
                subNow.months.forEach { m ->
                    FilterChip(m.name, month == m.id) { month = m.id; monthOpen = false }
                }
            }
        }
    }
    }
}

/**
 * 行卡右侧动作列（排行榜 / 上新日历共用）：
 * 时间（可选）→ 播放/预约 → 详情，三行右对齐、宽度固定，按钮同宽。
 * 之前详情是一个裸文字、还带自己的 padding，和按钮对不齐也不像按钮。
 */
@Composable
private fun RowActions(
    onPlay: () -> Unit,
    onDetail: () -> Unit,
    timeLabel: String? = null,
    upcoming: Boolean = false,
    reserved: Boolean = false,
    onReserve: (() -> Unit)? = null,
) {
    Column(
        Modifier.width(104.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!timeLabel.isNullOrBlank()) {
            Text(timeLabel, color = Palette.faint, fontSize = 12.sp, maxLines = 1)
        }
        when {
            !upcoming -> PrimaryButton("播放", Icons.Filled.PlayArrow, Modifier.fillMaxWidth(), onPlay)
            onReserve != null -> SecondaryButton(
                if (reserved) "已预约" else "预约",
                icon = if (reserved) Icons.Filled.Check else Icons.Filled.Add,
                modifier = Modifier.fillMaxWidth(),
                onClick = onReserve,
            )
            else -> Text(
                if (reserved) "已预约" else "待上线",
                color = if (reserved) Palette.dim else Palette.accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        OutlineButton("详情", null, Modifier.fillMaxWidth(), onDetail)
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
            .border(1.dp, if (hovered) Color(0x33FFFFFF) else Palette.line, RoundedCornerShape(12.dp))
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
        RowActions(
            onPlay = onPlay,
            onDetail = onClick,
            upcoming = card.upcoming,
            reserved = card.reserved,
        )
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
    val newScroll = PageMemo.newScroll

    // 日历：日期条 + 当日上新行卡列表。
    // 官方口径（对齐 Android CalendarTab / Windows calendar.rs）：
    // 1. 首屏不带 target_date：服务端回 calendar_schema.date_list + 当天那批；
    // 2. 切日期要带 target_date 重新请求，不能拿当天那批按天过滤（其它日期会全空）；
    // 3. 条目有两套 schema：当天批是嵌套 subscribe_data.*，其它日期是扁平 name/item_id；
    // 4. 发布时间 schedule_publish_time（秒）要连日期一起显示（MM-dd HH:mm）。
    var calDays by remember { mutableStateOf<List<String>>(emptyList()) }
    var calDate by remember { mutableStateOf("") }
    var calByDay by remember { mutableStateOf<Map<String, List<CalendarItem>>>(emptyMap()) }
    /** date → (nextOffset, sessionId)；有值表示这天还能翻 */
    var calMore by remember { mutableStateOf<Map<String, Pair<Int, String>>>(emptyMap()) }
    var calLoading by remember { mutableStateOf(false) }
    var calMoreLoading by remember { mutableStateOf(false) }
    var calInit by remember { mutableStateOf(false) }
    val calList = PageMemo.calList
    val dayFmt = remember { java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA) }

    LaunchedEffect(view, gender) {
        if (view == 0) {
            loading = true
            page = withContext(Dispatchers.IO) { runCatching { Api.newDramaCached(gender, 0, "") }.getOrNull() }
            loading = false
        }
    }

    // 日历首屏（只跑一次）：日期表 + 当天条目
    LaunchedEffect(view) {
        if (view != 1 || calInit) return@LaunchedEffect
        calLoading = true
        val root = withContext(Dispatchers.IO) { runCatching { Api.calendar() }.getOrNull() }
        val data = root?.optJSONObject("data")
        val schema = data?.optJSONObject("calendar_schema")
        val days = mutableListOf<String>()
        schema?.optJSONArray("date_list")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.length == 8 }?.let(days::add)
        }
        val items = parseCalendarDay(root)
        val today = dayFmt.format(java.util.Date())
        val grouped = items.groupBy { it.dayKey().ifEmpty { today } }
        calDays = days.ifEmpty { grouped.keys.sorted() }
        val def = schema?.optString("default_date").orEmpty()
        calDate = when {
            def.isNotEmpty() && calDays.contains(def) -> def
            calDays.contains(today) -> today
            else -> calDays.firstOrNull().orEmpty()
        }
        calByDay = grouped
        data?.let { d ->
            if (d.optBoolean("has_more", false)) {
                calMore = mapOf(today to (d.optInt("next_offset", items.size) to d.optString("session_id")))
            }
        }
        calInit = true
        calLoading = false
    }

    // 选中尚未拉过的日期：带 target_date 重新请求
    LaunchedEffect(calDate) {
        if (view != 1 || !calInit || calDate.isEmpty() || calByDay.containsKey(calDate)) return@LaunchedEffect
        calLoading = true
        val root = withContext(Dispatchers.IO) { runCatching { Api.calendar(calDate) }.getOrNull() }
        val list = parseCalendarDay(root)
        calByDay = calByDay + (calDate to list)
        val d = root?.optJSONObject("data")
        calMore = if (d != null && d.optBoolean("has_more", false)) {
            calMore + (calDate to (d.optInt("next_offset", list.size) to d.optString("session_id")))
        } else {
            calMore - calDate
        }
        calLoading = false
    }

    // 日历翻页：滚到接近底部自动续拉（接口返回空页就当到底）
    LaunchedEffect(view, calDate, calList) {
        if (view != 1) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { calList.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                val items = calByDay[calDate].orEmpty()
                val more = calMore[calDate] ?: return@collect
                if (calLoading || calMoreLoading || items.isEmpty() || last < items.size - 2) return@collect
                calMoreLoading = true
                val (offset, session) = more
                val root = withContext(Dispatchers.IO) {
                    runCatching { Api.calendar(calDate, offset, session) }.getOrNull()
                }
                val extra = parseCalendarDay(root)
                if (extra.isNotEmpty()) {
                    calByDay = calByDay + (calDate to (items + extra).distinctBy { it.seriesId })
                }
                val d = root?.optJSONObject("data")
                calMore = if (d != null && d.optBoolean("has_more", false) && extra.isNotEmpty()) {
                    calMore + (calDate to (d.optInt("next_offset", offset + extra.size) to d.optString("session_id")))
                } else {
                    calMore - calDate
                }
                calMoreLoading = false
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

    Column(Modifier.fillMaxSize()) {
        // 视图 tab：剧剧推荐 / 上新日历（旧版页内第一行）
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("新剧推荐", "上新日历").forEachIndexed { i, label ->
                TabChip(label, view == i) { view = i }
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (view == 1) "共 ${calByDay[calDate]?.size ?: 0} 部"
                else "共 ${page?.cards?.size ?: 0} 部",
                color = Palette.faint, fontSize = 12.sp,
            )
        }
        // 频道筛选：只对「新剧推荐」有效（日历接口按官方日历走，不分男女频）
        if (view == 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("全部", "男频", "女频").forEachIndexed { i, label ->
                    TabChip(label, gender == i) { gender = i }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        if (view == 1) {
            // 日期条：用官方的 calendar_schema.date_list（约 15 天），
            // 每格「今天/明天/MM月DD日 + 周几 · 当天条数」，横向可滚。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    calDays.forEach { d ->
                        CalendarDateCell(d, calDate == d, calByDay[d]?.size) { calDate = d }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        val calDayItems = calByDay[calDate].orEmpty()
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
                calLoading && calDayItems.isEmpty() -> CenterLoading()
                calDayItems.isEmpty() -> EmptyState(title = "这一天没有上新", hint = "换一天看看")
                else -> androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.fillMaxSize(),
                    state = calList,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    items(calDayItems, key = { it.seriesId }) { item ->
                        CalendarRow(
                            item = item,
                            onClick = { onOpen(item.seriesId) },
                            onPlay = { onPlay(item.seriesId) },
                        )
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                when {
                                    calMoreLoading -> "加载中…"
                                    calMore[calDate] != null -> "下滑加载更多"
                                    else -> "已经到底了"
                                },
                                color = Palette.faint, fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 上新日历的一条（字段口径对齐 Android CalendarTab / Windows calendar.rs）。 */
private data class CalendarItem(
    val seriesId: String,
    val title: String,
    val cover: String,
    /** subscribe_data.video_desc（不是 description） */
    val desc: String,
    /** 橙色那格：已上线「333万播放」/ 待上线「1570.3万人预约」 */
    val heat: String,
    val score: String,
    val category: String,
    val episodeCount: Int,
    /** schedule_publish_time，毫秒 */
    val timeSec: Long,
    val online: Boolean,
    val subscribed: Boolean,
)

private fun CalendarItem.dayKey(): String =
    if (timeSec <= 0) "" else
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA).format(java.util.Date(timeSec))

/**
 * 日历响应 → CalendarItem。
 * 两套 schema 都要认：当天那批是嵌套 `subscribe_data.*`，其它日期是扁平
 * `name / item_id / cover`；标题、series_id、封面都要按这个顺序回退。
 */
private fun parseCalendarDay(root: JSONObject?): List<CalendarItem> {
    val data = root?.optJSONObject("data") ?: return emptyList()
    val arr = data.optJSONArray("subscribe_items") ?: return emptyList()
    val out = mutableListOf<CalendarItem>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val sd = o.optJSONObject("subscribe_data")
        val title = sd?.optString("title").orEmpty()
            .ifEmpty { o.optString("name") }
            .ifEmpty { sd?.optString("series_title").orEmpty() }
        if (title.isEmpty()) continue
        val sid = sd?.optString("series_id").orEmpty()
            .ifEmpty { o.optString("item_id") }
            .ifEmpty { o.optString("series_id") }
        if (sid.isEmpty() || sid == "0") continue
        var ts = o.optLong("schedule_publish_time", 0L)
        if (ts in 1..9_999_999_999L) ts *= 1000
        var heat = ""
        o.optJSONArray("rec_tags")?.let { a ->
            for (k in 0 until a.length()) {
                val node = a.optJSONObject(k) ?: continue
                val t = node.optString("text").ifEmpty { node.optString("content") }
                if (t.isNotBlank()) {
                    heat = t
                    break
                }
            }
        }
        if (heat.isEmpty()) {
            val plays = sd?.optLong("play_cnt", 0L) ?: 0L
            heat = if (plays > 0) com.hongguotv.pad.formatCount(plays.toString()) + "播放"
            else o.optString("expected_rec_text")
        }
        out.add(
            CalendarItem(
                seriesId = sid,
                title = title,
                cover = sd?.optString("cover").orEmpty().ifEmpty { o.optString("cover") },
                desc = sd?.optString("video_desc").orEmpty().ifEmpty { o.optString("item_desc") },
                heat = heat,
                score = sd?.optString("score").orEmpty(),
                category = o.optString("category").ifEmpty { sd?.optString("category").orEmpty() },
                episodeCount = sd?.optInt("episode_cnt", 0) ?: o.optInt("episode_cnt", 0),
                timeSec = ts,
                online = o.optBoolean("is_online", false),
                subscribed = o.optBoolean("has_subscribed", false),
            ),
        )
    }
    return out
}

/** 日期格：「今天 / 明天 / M月D日」+「周几 · 当天条数」。 */
@Composable
private fun CalendarDateCell(date: String, active: Boolean, count: Int?, onClick: () -> Unit) {
    val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
    val d = runCatching { java.time.LocalDate.parse(date, fmt) }.getOrNull()
    val today = java.time.LocalDate.now()
    val label = when {
        d == null -> date
        d == today -> "今天"
        d == today.plusDays(1) -> "明天"
        else -> "${d.monthValue}月${d.dayOfMonth}日"
    }
    val weekday = d?.dayOfWeek?.let {
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[it.value - 1]
    }.orEmpty()
    Column(
        Modifier
            .width(76.dp)
            .clip(Radius.chip)
            .background(if (active) Palette.accent.copy(alpha = 0.16f) else Palette.panel)
            .border(
                1.dp,
                if (active) Palette.accent.copy(alpha = 0.45f) else Palette.line,
                Radius.chip,
            )
            .clickable { onClick() }
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = if (active) Palette.accentHover else Palette.text,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (count != null && count > 0) "$weekday · $count" else weekday,
            color = if (active) Palette.accentHover.copy(alpha = 0.85f) else Palette.faint,
            fontSize = 10.sp,
        )
    }
}

/**
 * 日历行：封面 + 标题/评分/上线状态 + 分类·集数 + 热度 + 两行简介，
 * 右侧是「MM-dd HH:mm」发布时间 + 播放/预约（旧版 render_calendar_row 同信息量）。
 */
@Composable
private fun CalendarRow(item: CalendarItem, onClick: () -> Unit, onPlay: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    var reserved by remember(item.seriesId) { mutableStateOf(item.subscribed) }
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (hovered) Color(0x33FFFFFF) else Palette.line, RoundedCornerShape(12.dp))
            .background(Palette.panel)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            item.cover,
            Modifier.width(88.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)),
            fallback = item.title,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.title, color = Palette.text, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                item.score.takeIf { it.isNotBlank() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Palette.star, modifier = Modifier.size(12.dp))
                        Text(it, color = Palette.star, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            val meta = listOfNotNull(
                item.category.takeIf { it.isNotBlank() },
                if (item.episodeCount > 0) "全 ${item.episodeCount} 集" else null,
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.heat.isNotBlank()) HeatBadge(item.heat)
            item.desc.takeIf { it.isNotBlank() }?.let {
                Text(
                    it, color = Palette.dim.copy(alpha = 0.8f), fontSize = 12.sp,
                    lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        RowActions(
            onPlay = onPlay,
            onDetail = onClick,
            timeLabel = if (item.timeSec > 0) {
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date(item.timeSec))
            } else null,
            upcoming = !item.online,
            reserved = reserved,
            onReserve = {
                scope.launch {
                    val target = !reserved
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { Api.reserveToggle(item.seriesId, target) }.getOrDefault(false)
                    }
                    if (ok) reserved = target
                }
            },
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
    // 与安卓历史页对齐的三个 tab：历史 / 收藏 / 点赞（收藏、点赞复用海报墙）
    var tab by remember { mutableStateOf(0) }

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
            // 未登录没有云端历史：直接读本地（原实现调 historySyncAsync 内部立刻 return，再白等 1.5 秒）
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
        // 顶部分类（安卓同款三 tab）：历史 / 收藏 / 点赞
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabChip("历史", tab == 0) { tab = 0 }
            TabChip("收藏", tab == 1) { tab = 1 }
            TabChip("点赞", tab == 2) { tab = 2 }
        }
        if (tab != 0) {
            Spacer(Modifier.height(12.dp))
            ShelfPage(
                key = if (tab == 1) "favorite" else "like",
                onOpen = onOpen,
                onPlay = { id -> onPlay(id, 1, 0L) },
            )
            return@Column
        }
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

@Composable
private fun ShelfPage(key: String, onOpen: (String) -> Unit, onPlay: (String) -> Unit) {
    var cards by remember { mutableStateOf<List<SeriesCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var syncing by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(key, tick, PcStore.loggedIn) {
        syncing = tick > 0
        loading = true
        // 收藏以云端为准：先拉云端（1 跳）；只有未登录/云端失败才回落到本地逐条补详情。
        // 原实现先对 N 个 id 串行 ensureDetail（每个 1-3 秒），再被云端结果整批覆盖——前面的请求全白做。
        var list: List<SeriesCard> = emptyList()
        if (key == "favorite" && PcStore.loggedIn) {
            list = withContext(Dispatchers.IO) { runCatching { Api.cloudShelf() }.getOrNull() } ?: emptyList()
        }
        if (list.isEmpty()) {
            val ids = PcStore.ids(key)
            list = withContext(Dispatchers.IO) {
                ids.mapNotNull { id ->
                    runCatching { Api.ensureDetail(id) }.getOrNull()?.let { Api.cardOfDetail(it) }
                }
            }
        }
        cards = list
        loading = false
        syncing = false
    }

    Column(Modifier.fillMaxSize()) {
        if (key == "favorite") {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlineButton(if (syncing) "刷新中…" else "刷新云端（${cards.size}）") { tick++ }
            }
        }
        when {
            loading -> CenterLoading()
            cards.isEmpty() -> EmptyState(
                title = if (key == "like") "还没有点赞" else "还没有收藏",
                hint = if (key == "like") "在播放页点「点赞」，点过赞的剧会出现在这里"
                else "在详情页点「收藏」就会出现在这里",
                icon = if (key == "like") Icons.Filled.Favorite else Icons.Filled.Star,
            )
            else -> PosterGrid(
                cards = cards,
                onOpen = { onOpen(it.seriesId) },
                onPlay = { onPlay(it.seriesId) },
                onCollect = { c ->
                    val was = PcStore.contains(key, c.seriesId)
                    PcStore.toggle(key, c.seriesId)
                    if (was) cards = cards.filterNot { it.seriesId == c.seriesId }
                },
                coverBadge = { if (it.episodeCount > 0) "${it.episodeCount} 集" else null },
            )
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
            .border(1.dp, if (hovered) Color(0x33FFFFFF) else Palette.line, RoundedCornerShape(12.dp))
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
private fun FollowsPage(
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
    onPlayEp: (String, Int) -> Unit = { id, _ -> onPlay(id) },
    onLogin: () -> Unit = {},
) {
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
                !PcStore.loggedIn -> EmptyState(
                    title = "登录后才能同步追更",
                    hint = "追更跟着账号走：登录后在这里看更新，也可以在任意设备用匹配码同步",
                    icon = FollowsEmptyIcon,
                    actionLabel = "手机号登录",
                    onAction = onLogin,
                )
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
                    coverBadge = { c -> if (c.episodeCount > 0) "${c.episodeCount} 集" else null },
                    tagsLine = { c -> c.tags.take(3).joinToString(" ").takeIf { it.isNotBlank() } },
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
        cards = withContext(Dispatchers.IO) { runCatching { Api.reserveListCached() }.getOrDefault(emptyList()) }
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
            .border(1.dp, if (hovered) Color(0x33FFFFFF) else Palette.line, RoundedCornerShape(12.dp))
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
    Box(Modifier.fillMaxSize().verticalScroll(PageMemo.settingsScroll)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                SettingBlock("关于") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            UpdateRow()
                        }
                        Spacer(Modifier.width(24.dp))
                        OfficialSiteCard()
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
                    Spacer(Modifier.height(12.dp))
                    // 默认倍速（安卓设置里同一项）：播放器起播用它，播放器里改倍速也会写回这里
                    var rate by remember { mutableStateOf(PcStore.playbackRate) }
                    Text(
                        "默认倍速：${if (rate % 1f == 0f) "${rate.toInt()}x" else "${rate}x"}",
                        color = Palette.text, fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { r ->
                            TabChip(
                                if (r % 1f == 0f) "${r.toInt()}x" else "${r}x",
                                rate == r,
                            ) { rate = r; PcStore.playbackRate = r }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("播放器里改倍速会记住，下次起播继续用。", color = Palette.faint, fontSize = 12.sp)
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
                            if (t.state == Downloader.State.RUNNING || t.state == Downloader.State.QUEUED) {
                                Text(
                                    "取消",
                                    color = Palette.faint, fontSize = 11.sp,
                                    modifier = Modifier.clickable { Downloader.cancel(t) },
                                )
                                Spacer(Modifier.width(10.dp))
                            }
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
                    var upBase by remember { mutableStateOf(PcStore.updateBase) }
                    ServiceField(
                        "更新源",
                        upBase,
                        "留空 = 自建服务器（推荐）；也可填自己的更新目录（含 version-pc.json）",
                    ) { upBase = it }
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
                            PcStore.updateBase = upBase
                            PcStore.deviceId = did
                            PcStore.deviceIid = iid
                            PcStore.deviceCdid = cdid
                            applyServiceConfig()
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("保存后立即生效（无需重启）", color = Palette.faint, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(10.dp))
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

/** 设置页「关于」右侧的官网入口：二维码扫码打开，地址可点击，旁边可复制链接。 */
@Composable
private fun OfficialSiteCard() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        officialQrBitmap?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = "官网二维码",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(104.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .padding(6.dp)
                    .clickable { openOfficialSite() },
            )
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text("官方网站", color = Palette.text, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "kxg.fabao.xin",
                color = Palette.accent,
                fontSize = 13.sp,
                modifier = Modifier.clickable { openOfficialSite() },
            )
            Spacer(Modifier.height(4.dp))
            Text("扫码或点击打开，方便分享", color = Palette.faint, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            OutlineButton("复制链接") {
                runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(java.awt.datatransfer.StringSelection(OFFICIAL_SITE), null)
                }
            }
        }
    }
}

private fun openOfficialSite() {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(OFFICIAL_SITE)) }
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

/** 筛选选项芯片（找剧/排行榜共用）：未选中=卡面底+发丝边；选中=红色薄底+
 *  红字+细红边（实心红只给动作按钮，几十个选项里不能每个都像主按钮）。 */
@Composable
fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier
            .clip(Radius.chip)
            .background(
                when {
                    active -> Palette.accent.copy(alpha = 0.16f)
                    hovered -> Palette.panelHi
                    else -> Palette.panel
                },
            )
            .border(
                1.dp,
                when {
                    active -> Palette.accent.copy(alpha = 0.45f)
                    hovered -> Palette.dim.copy(alpha = 0.30f)
                    else -> Palette.line
                },
                Radius.chip,
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (active) Palette.accentHover else Palette.dim,
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

/** 失败态：一句话 + 一个真的会重试的按钮（纯文字的「检查网络后重试」等于没说）。 */
@Composable
fun CenterRetry(text: String, action: String = "重试", onAction: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = Palette.dim, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            PrimaryButton(action, null, onAction)
        }
    }
}

/**
 * 诊断日志落盘（PC）。打包版没有控制台，日志只进这里：
 * %APPDATA%\KaixinguoPCpp.log；超过 5MB 启动时清空重来。
 */
object DiagLog {
    private val file by lazy {
        File(System.getenv("APPDATA") ?: ".", "KaixinguoPC/app.log").also { f ->
            runCatching {
                f.parentFile?.mkdirs()
                if (f.length() > 5L * 1024 * 1024) f.delete()
            }
        }
    }

    private val writer by lazy {
        java.io.BufferedWriter(java.io.FileWriter(file, true))
    }

    private val fmt by lazy { java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.CHINA) }

    fun write(level: String, tag: String, message: String) {
        val line = "${fmt.format(java.util.Date())} $level/$tag: $message" + System.lineSeparator()
        synchronized(writer) {
            writer.write(line)
            writer.flush()
        }
    }
}
