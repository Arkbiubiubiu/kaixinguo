@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.kaixinguo.pc

import androidx.compose.ui.graphics.toArgb

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
import com.hongguotv.pad.ShelfKind
import com.hongguotv.pad.ShelfRepository
import com.hongguotv.pad.Api
import com.hongguotv.pad.App
import com.hongguotv.pad.FeedPage
import com.hongguotv.pad.FilterRow
import com.hongguotv.pad.HistoryApi
import com.hongguotv.pad.HistoryRepository
import com.hongguotv.pad.HistoryShadow
import com.hongguotv.pad.Log
import com.hongguotv.pad.MachineIdentity
import com.hongguotv.pad.Notice
import com.hongguotv.pad.NoticeCenter
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
internal val brandBitmap: ImageBitmap? by lazy {
    runCatching {
        val stream = object {}.javaClass.getResourceAsStream("/brand.png")
            ?: return@runCatching null
        stream.use { javax.imageio.ImageIO.read(it).toComposeImageBitmap() }
    }.getOrNull()
}

/** 跨页面保留的浏览状态：进详情/播放再返回时回到原处（原来一离开页面状态就全丢）。 */
internal object PageMemo {
    var feedTab = 0   // 0=漫剧（三端默认）
    var feedCursor = 0
    var feedCards: List<SeriesCard> = emptyList()
    var feedPage: FeedPage? = null
    /** 从刷剧进详情/评论再返回：这一次保留现场；从菜单重新进刷剧必须现拉 */
    var feedReturnKeep: Boolean = false

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

    // 新剧页：进详情会离开组合，局部 remember 全丢。这里保存视图/分类/分页/日期/滚动，
    // 返回时回到「你在哪、看到哪」（2026-11-16 用户反馈：返回跳回推荐全部、日历回今天）。
    var newView = 0
    var newGender = 0
    var newPage: FeedPage? = null
    var newKey = ""
    var newAt = 0L
    var calDate = ""
    var calDays: List<String> = emptyList()
    var calByDay: Map<String, List<CalendarItem>> = emptyMap()
    var calMore: Map<String, Pair<Int, String>> = emptyMap()
    var calAt = 0L
    /** 上次因切日期而滚到顶部的日期：从详情返回同一天时不能再滚 */
    var calScrollDate = ""

    // ---- 其余「列表 → 详情 → 返回」页面（2026-11-16 系统排查） ----
    // 首页：已加载页、翻页游标、hero 当前序号
    var homePage: FeedPage? = null
    var homeCursor: FeedPage? = null
    var homeHeroIndex = 0
    var homeKey = ""
    var homeAt = 0L

    // 排行榜：大类/子榜/筛选/月榜 + 已加载列表/分页
    var rankKey = ""
    var rankAt = 0L
    var rankMetaAt = 0L
    var rankTabs: List<RankTabsel> = emptyList()
    var rankTabIndex = 0
    var rankSubIndex = 0
    var rankPanel = ""
    var rankMonth = ""
    var rankCards: List<SeriesCard> = emptyList()
    var rankSession = ""
    var rankNextOffset = 0
    var rankHasMore = false
    var rankDataAt = 0L

    // 收藏 / 点赞
    var favoriteCards: List<SeriesCard> = emptyList()
    var likeCards: List<SeriesCard> = emptyList()
    var favoriteAt = 0L
    var likeAt = 0L
    val shelfScroll = androidx.compose.foundation.ScrollState(0)

    // 历史：tab/筛选/搜索 + 滚动
    var historyTab = 0
    var historyFilter = 0
    var historySearch = ""
    val historyList = androidx.compose.foundation.lazy.LazyListState(0, 0)

    // 追更：tab/列表 + 滚动
    var followTab = 0
    var followCards: List<SeriesCard> = emptyList()
    var followAt = 0L
    val followScroll = androidx.compose.foundation.ScrollState(0)

    // 预约：tab/搜索/列表 + 滚动
    var reserveTab = 0
    var reserveSearch = ""
    var reserveCards: List<SeriesCard> = emptyList()
    var reserveAt = 0L
    /** 预约/取消成功后清掉预约页的界面内存缓存（第二层），下次进预约页快路径未命中→重拉。 */
    fun clearReserveMemo() {
        reserveCards = emptyList()
        reserveAt = 0L
    }
    val reserveList = androidx.compose.foundation.lazy.LazyListState(0, 0)

    // 演职员：资料/作品 + 滚动
    var creatorId = ""
    var creatorProfile: com.hongguotv.pad.CreatorProfileInfo? = null
    var creatorCards: List<SeriesCard> = emptyList()
    var creatorAt = 0L
    val creatorScroll = androidx.compose.foundation.ScrollState(0)

    // 详情页：tab/滚动（详情 → 演职员 → 返回时保持）
    var detailTabSeries = ""
    var detailTab = 0
    val detailScroll = androidx.compose.foundation.ScrollState(0)

    /** 「列表→详情→返回」状态复用窗口（与安卓 UiMemo.RETURN_TTL_MS 同口径）。 */
    const val RETURN_TTL_MS = 5 * 60_000L
}

/** 官网（设置页「关于」右侧：二维码 + 地址，扫码或点击打开）。 */
internal const val OFFICIAL_SITE = "https://kxg.fabao.xin/"

internal val officialQrBitmap: ImageBitmap? by lazy {
    runCatching {
        val stream = object {}.javaClass.getResourceAsStream("/official_qr.png")
            ?: return@runCatching null
        stream.use { javax.imageio.ImageIO.read(it).toComposeImageBitmap() }
    }.getOrNull()
}

/** 单实例锁：必须常驻引用，局部变量被回收 = 锁被释放，双实例又会出现。 */
internal object SingleInstance {
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
    // 日志定时回传（每 10 分钟发增量）
    runCatching { ClientLog.start() }
    // 缓存自动清理（封面 30 天/最多 3000 张，VVC 7 天）：后台跑，不拖启动
    Thread {
        runCatching { Covers.autoClean() }
        // VVC 播放链路已删除，旧转换缓存直接清掉
        runCatching {
            java.io.File(System.getenv("APPDATA") ?: ".", "KaixinguoPC/vvc").deleteRecursively()
        }
    }.apply { isDaemon = true; name = "cache-clean" }.start()
    val state = rememberWindowState(width = 1440.dp, height = 900.dp)
    // 无边框（旧版 decorations:false 同语义）：系统白条不画，自绘窗口钮+拖拽区
    Window(
        onCloseRequest = ::exitApplication,
        title = "",
        state = state,
        // 原生标题栏：标题留空 + 染成应用底色，看起来和内容一体；贴靠/最大化全是系统行为
        undecorated = false,
        transparent = false,
        icon = brandBitmap?.let { BitmapPainter(it) },
    ) {
        val awt = java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>().firstOrNull()
        androidx.compose.runtime.LaunchedEffect(awt, Palette.isDark.value) {
            // DWM 在窗口上屏/激活时会重刷标题栏，单次设置可能被盖回黑色；
            // 启动阶段多刷几次，并在窗口激活/状态变化时补刷
            repeat(6) {
                kotlinx.coroutines.delay(if (it == 0) 120 else 400)
                DarkTitleBar.apply(awt, Palette.bg.toArgb())
            }
        }
        androidx.compose.runtime.DisposableEffect(awt, Palette.isDark.value) {
            val f = awt ?: return@DisposableEffect onDispose { }
            val l = object : java.awt.event.WindowAdapter() {
                override fun windowActivated(e: java.awt.event.WindowEvent?) {
                    DarkTitleBar.apply(f, Palette.bg.toArgb())
                }
                override fun windowStateChanged(e: java.awt.event.WindowEvent?) {
                    DarkTitleBar.apply(f, Palette.bg.toArgb())
                }
            }
            f.addWindowListener(l)
            onDispose { f.removeWindowListener(l) }
        }
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
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Palette.bg)) {
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
                        "内容服务和播放服务都已内置，装好就能浏览和播放，不需要填任何服务器地址。",
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

internal object RouteHolder {
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

internal object SearchHolder {
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
    App.platform = "pc"   // 公告按平台过滤用（NoticeCenter.pick(curCode,"pc")）
    Palette.applyTheme(PcStore.theme)
    applyServiceConfig()
    Thread { runCatching { Api.fetchFlags() } }.apply { isDaemon = true; name = "flags-init" }.start()
    App.deviceIid = PcStore.deviceIid
    App.deviceId = PcStore.deviceId
    App.deviceCdid = PcStore.deviceCdid
    // 每机唯一登录身份：不依赖旧的 login_unique_device 开关（旧值可能残留=0 把默认盖住）。
    // 仅本地签名(signUrl 空) 且 未处于「上次 1003 回退记忆」时，后台自动注册/安装；失败静默回退固定。
    if (PcStore.signUrl.isBlank() && !PcStore.loginFallbackActive) {
        Thread {
            runCatching {
                val stored = PcStore.loadRegisteredDevice()
                val reg = if (stored != null && stored.fullyValid) stored else {
                    MachineIdentity.register(
                        seedCdid = stored?.cdid ?: "",
                        seedOpenudid = stored?.openudid ?: "",
                        seedClientUdid = stored?.clientUdid ?: "",
                    )?.also { PcStore.saveRegisteredDevice(it) }
                }
                if (!MachineIdentity.install(reg)) MachineIdentity.clear()
            }
        }.apply { isDaemon = true; name = "machine-identity" }.start()
    }
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
        val row = runCatching {
            com.kaixinguo.pc.PcStore.history().firstOrNull { it.optString("seriesId") == id }
        }.getOrNull()
        val ep = (row?.optInt("episode", 0) ?: 0).coerceAtLeast(1)
        // 连秒数一起传：本机有 posOf 能续，带上历史秒数后换机/清缓存也能接着播。
        val pos = row?.optLong("positionMs", 0L) ?: 0L
        nav(Route.Player(id, ep, pos))
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

    // ---- 公告 + 强更（后台拉取，不阻塞首屏；失败静默） ----
    var forceNotice by remember { mutableStateOf<Notice?>(null) }
    var bannerNotice by remember { mutableStateOf<Notice?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { NoticeCenter.load() } }
        val (f, b) = NoticeCenter.pick(BuildInfo.CODE, "pc")
        forceNotice = f
        bannerNotice = b
    }

    // 公告层统一浮在内容之上；打赏浮层其次；强更弹窗最顶层
    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().background(Palette.bg)) {
        // 侧栏只在「页面态」(route==null) 显示；进入 Route.Player 播放页后隐藏侧栏，
        // 播放页改成真正独立的整屏外壳（不再被套上「刷剧」侧栏/高亮）。
        // 小窗/竖屏/全屏里侧栏也没有余地，一并藏掉。
        if (route == null && !UiMode.mini.value &&
            !UiMode.portrait.value && !UiMode.fullscreen.value
        ) {
            Sidebar(
                page,
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
                is Route.Detail -> DetailScreen(r.seriesId, initialTab = r.tab, onBack = { navBack() }, onPlay = { ep, pos -> nav(Route.Player(r.seriesId, ep, pos)) }, onOpenSeason = { nav(Route.Detail(it)) }, onCreator = { nav(Route.Creator(it)) })
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
        // 普通公告横幅：播放页(Route.Player) / 全屏时不显示
        val bn = bannerNotice
        if (bn != null && route !is Route.Player && !UiMode.fullscreen.value) {
            NoticeBanner(
                bn,
                onDismiss = { NoticeCenter.dismissed.add(bn.id); bannerNotice = null },
                onAutoClose = { bannerNotice = null },
            )
        }
        // 打赏浮层（在公告横幅之下）
        if (donateOpen) DonateOverlay(onClose = { donateOpen = false })
        // 强制更新弹窗（最顶层；消费点击/滚轮 + 真拦键盘，不可关）
        val fn = forceNotice
        if (fn != null) {
            ForceUpdateDialog(
                notice = fn,
                onOpenUrl = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(fn.url)) } },
                onFallbackClose = { forceNotice = null },
            )
        }
    }
}

// ---------------------------------------------------------------- 首页

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

/**
 * 原生标题栏染成应用底色、文字同色（看起来没有标题栏），只保留右侧系统按钮。
 * DWMWA_USE_IMMERSIVE_DARK_MODE=20 / CAPTION_COLOR=35 / TEXT_COLOR=36 / BORDER_COLOR=34。
 */
internal object DarkTitleBar {
    private interface Dwmapi : com.sun.jna.win32.StdCallLibrary {
        fun DwmSetWindowAttribute(
            hWnd: com.sun.jna.platform.win32.WinDef.HWND,
            attr: Int,
            value: com.sun.jna.ptr.IntByReference,
            size: Int,
        ): Int

        companion object {
            val I: Dwmapi = com.sun.jna.Native.load("dwmapi", Dwmapi::class.java)
        }
    }

    fun apply(frame: java.awt.Frame?, argb: Int) {
        frame ?: return
        if (!System.getProperty("os.name").lowercase().contains("win")) return
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val colorref = (b shl 16) or (g shl 8) or r
        runCatching {
            val hwnd = com.sun.jna.platform.win32.WinDef.HWND(
                com.sun.jna.Native.getWindowPointer(frame),
            )
            fun set(attr: Int, v: Int) {
                val r = runCatching {
                    Dwmapi.I.DwmSetWindowAttribute(hwnd, attr, com.sun.jna.ptr.IntByReference(v), 4)
                }
                com.hongguotv.pad.Log.d("DarkTitle", "dwma attr=$attr v=${Integer.toHexString(v)} ret=${r.getOrNull()} err=${r.exceptionOrNull()?.message}")
            }
            set(20, 1)                // 深色
            set(35, colorref)         // 标题栏底色 = 顶条颜色
            set(36, colorref)         // 标题文字同色 → 看不见
            set(34, colorref)         // 边框同色
        }
    }
}
