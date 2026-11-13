package com.hongguotv.pad

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas

private class NavItem(val label: String, val icon: ImageVector?, val custom: String?, val screen: Screen)

/**
 * 左侧导航（与原版一致）：收藏/点赞收进「历史」页的三个 tab，
 * 预约收进「追更」页的两个 tab，导航栏只保留入口，不再各占一项。
 */
private val navItems = listOf(
    NavItem("推荐", Icons.Filled.Home, null, Screen.Home),
    // 刷剧：整屏连播（与 PC 刷剧页同一套数据）；Pad 上下滑、TV ↑↓ 换剧
    NavItem("刷剧", Icons.Filled.PlayArrow, null, Screen.Feed),
    NavItem("找剧", Icons.Filled.Search, null, Screen.Find),
    NavItem("排行榜", null, "rank", Screen.Rank),
    NavItem("新剧", null, "new", Screen.NewDrama),
    NavItem("历史", null, "history", Screen.History),
    NavItem("追更", Icons.Filled.Notifications, null, Screen.Follow),
    // 追更 / 预约是两个功能：追更看「更新到第几集」，预约看「已上线 / 待上线」
    NavItem("预约", null, "reserve", Screen.Reserve),
    // 「我的」与 PC 同名同义（PC 的我的页 = 账户 + 收藏/点赞 + 登录入口）；
    // TV 因登录方式不同额外含设备配对码，但命名与入口位置对齐 PC。
    NavItem("我的", Icons.Filled.Person, null, Screen.Account),
    // 打赏作者：纯自愿入口，点开弹窗看收款码（DonateDialog.kt）；不走 Screen 路由。
    // 图标走 custom 手绘（安卓没有 material-icons-extended，LocalCafe 引不到）
    NavItem("打赏", null, "cafe", Screen.Donate),
    NavItem("设置", Icons.Filled.Settings, null, Screen.Settings),
)

/**
 * 导航栈：栈底永远是首页，进详情/播放都是入栈，返回键出栈。
 * 播放页全屏（不显示左侧导航），其余页面统一「左侧导航 + 右侧内容」。
 */
@Composable
fun App() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }

    // 启动后自动查一次更新（局域网优先、外网兜底），有新版本直接弹窗
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        update = runCatching { Updater.check(Store.appContext) }.getOrNull()
    }
    update?.let { info -> UpdateDialog(info) { update = null } }
    // 打赏弹窗：侧栏「打赏」入口，纯展示不接支付，不进路由栈
    var donateOpen by remember { mutableStateOf(false) }
    // 追更未读角标：启动查一次，之后每 20 分钟一轮（与 PC 同周期）；进「追更」页清零
    var unreadFollow by remember { mutableStateOf(Store.followBadge) }
    LaunchedEffect(Unit) {
        while (true) {
            if (Store.loggedIn) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { Store.checkFollowUpdates() }
                }
                unreadFollow = Store.followBadge
            }
            kotlinx.coroutines.delay(20 * 60 * 1000L)
        }
    }
    if (donateOpen) DonateDialog { donateOpen = false }
    val current = stack.last()

    fun push(screen: Screen) {
        // 同一个页面不重复入栈（反复点同一个导航项不该把栈堆起来）
        if (stack.lastOrNull() != screen) stack.add(screen)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    if (current is Screen.Play) {
        // 播放页自己没有 BackHandler（只靠焦点键处理），这里兜住系统返回
        BackHandler(enabled = true) { back() }
        PlayerScreen(
            seriesId = current.seriesId,
            startIndex = current.index,
            startMs = current.startMs,
            onBack = { back() },
            onSwitch = { id, index ->
                // 切集不换页面，只更新栈顶参数
                stack[stack.lastIndex] = Screen.Play(id, index)
            },
            onDetail = { push(Screen.Detail(current.seriesId)) },
        )
        return
    }

    // 焦点跳板用的请求器与焦点管理器
    val focusBridge = remember { FocusRequester() }
    val focusBridgeManager = LocalFocusManager.current
    // 最后一次按的是不是 ←。跳板要靠它判断「这次是从侧栏进内容区」还是「从内容区退回侧栏」
    var lastKeyLeft by remember { mutableStateOf(false) }
    val bridgeScope = rememberCoroutineScope()
    // 侧栏「当前分类」项的焦点请求器：启动时默认焦点、返回键回侧栏都靠它
    val railEntry = remember { FocusRequester() }
    // 找剧页的焦点入口（绑在搜索框上）
    val findEntry = remember { FocusRequester() }
    // 搜索框是否真的拿到焦点。requestFocus() 在这块是静默无效的，
    // 只能靠 FindScreen 回调来确认，不能靠 isSuccess 判断
    var findFocused by remember { mutableStateOf(false) }
    // 侧栏上焦点停在哪个分类（移动焦点不切页，所以和 current 可能不同）
    var railFocused by remember { mutableStateOf<Screen?>(null) }
    // 用户按过任何键：启动时的默认焦点重试必须立刻收手，绝不能跟用户抢焦点
    var userActed by remember { mutableStateOf(false) }

    // ── 侧栏的第二层锁：进入板块就锁死 ──────────────────────────────
    // 电视上只有「导航层」能操作侧栏：在侧栏按确定 / → 进入某个板块之后，侧栏变暗、
    // 不接收焦点，方向键在板块里怎么绕都出不去，必须按返回才回到导航层。
    // 这跟压栈页（详情/播放）那层锁是两件事：那层看 stack.size，这层看 railLocked。
    // 平板不锁：触屏没有方向键，锁了只会让人每次都得按系统返回，纯粹添麻烦。
    val railLockable = Store.appContext.packageName.endsWith("nativeapp")
    var railLocked by remember { mutableStateOf(false) }
    // 「正在把焦点送进内容区」的短暂窗口。1dp 焦点跳板只在这个窗口里存在：
    // 平时锁着侧栏时它不能可聚焦 —— 否则内容区左边缘按左，几何搜索会把焦点吸到
    // 这条看不见的细条上（用户看到的就是「光标消失了」）。压栈页不渲染跳板，
    // 所以详情页左键按不动，正是这个原因。
    var enteringContent by remember { mutableStateOf(false) }

    // 返回键统一兜底（以前只有「栈深 > 1」才接管，顶层按返回直接退 App；
    // 侧栏锁定时如果焦点不在内容区，那条 onKeyEvent 也收不到，同样会直接退出）：
    //   压栈页 → 出栈；进入板块 → 回导航层；顶层 → 再按一次才退出
    var backArmed by remember { mutableStateOf(false) }
    LaunchedEffect(backArmed) {
        if (backArmed) {
            kotlinx.coroutines.delay(2_000)
            backArmed = false
        }
    }
    BackHandler(enabled = true) {
        when {
            stack.size > 1 -> back()
            railLocked -> {
                railLocked = false
                bridgeScope.launch {
                    androidx.compose.runtime.withFrameNanos { }
                    runCatching { railEntry.requestFocus() }
                }
            }
            backArmed -> (Store.appContext as? android.app.Activity)?.finish()
            else -> {
                backArmed = true
                runCatching {
                    android.widget.Toast.makeText(
                        Store.appContext, "再按一次返回退出", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    /**
     * 把焦点送进内容区第一个可聚焦控件（带重试：页面刚组合出来时控件可能还没上）。
     * 返回 true = 焦点确实落进内容区了。
     */
    suspend fun focusIntoContent(screen: Screen): Boolean {
        enteringContent = true
        try {
            repeat(20) {
                if (screen is Screen.Find) {
                    // 找剧页：点名进搜索框，并以 FindScreen 的回调确认**真落上了**。
                    // requestFocus() 返回 Unit，静默无效也不报错，只判 isSuccess 会误判。
                    runCatching { findEntry.requestFocus() }
                    androidx.compose.runtime.withFrameNanos { }
                    if (findFocused) return true
                } else if (runCatching { focusBridge.requestFocus() }.isSuccess) {
                    // 其它页：借焦点跳板往右找第一个可聚焦控件
                    androidx.compose.runtime.withFrameNanos { }
                    if (focusBridgeManager.moveFocus(FocusDirection.Right) ||
                        focusBridgeManager.moveFocus(FocusDirection.Next)
                    ) return true
                }
                kotlinx.coroutines.delay(80)
            }
            return false
        } finally {
            enteringContent = false
        }
    }
    // 启动默认焦点落在侧栏「当前分类」上（原来悬空，←/↓ 都没反应）。
    // 只对 TV 有意义：平板触屏不该启动就高亮任何项（高亮≠可用，还会误导）。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // 启动后窗口可能还没拿到焦点，requestFocus 会被静默忽略（而且它返回 Unit，
        // 「没抛异常」不等于「焦点真落上了」）。这里以侧栏项的 onFocusChanged 为准：
        // railFocused 被回调了才算成功，否则持续重试。
        // 三条退出条件缺一不可——尤其是「用户一按键就停」：
        // 万一确认信号哪天又失效，最多干扰到第一次按键，而不是整整 6 秒。
        repeat(20) { n ->
            kotlinx.coroutines.delay(150)
            if (railFocused != null || userActed || !railLockable) return@LaunchedEffect
            android.util.Log.i("focusdbg", "try #")
            runCatching { railEntry.requestFocus() }
                .onFailure { android.util.Log.e("focusdbg", "rail focus fail", it) }
        }
    }
    // 从详情/播放退回顶层页面时，决定焦点交给谁（只对 TV 有意义：平板不锁侧栏、
    // 也不主动送焦点，触屏谁被点谁有焦点）：
    // - 侧栏没锁（在导航层）：还给侧栏「当前分类」。不还的话侧栏刚恢复可聚焦，
    //   焦点会被系统丢到第一项（推荐）上，而高亮的是别的分类 —— 看着像"光标跑错地方"。
    // - 侧栏锁着（在板块里）：还给内容区第一个控件，别让焦点悬空。退回来之后侧栏
    //   仍然是锁的，再按一次返回才回到导航层。
    androidx.compose.runtime.LaunchedEffect(stack.size) {
        if (stack.size == 1 && railLockable) {
            androidx.compose.runtime.withFrameNanos { }
            if (railLocked) {
                if (!focusIntoContent(current)) {
                    // 内容区一个可聚焦控件都没有（空页/加载失败）：解锁，别把人锁在门外
                    railLocked = false
                    runCatching { railEntry.requestFocus() }
                }
            } else {
                runCatching { railEntry.requestFocus() }
            }
        }
    }
    // 找剧筛选面板预热：第一次进找剧页时筛选框要等接口返回才出现（比内容区慢），
    // 进 App 就把默认体裁的筛选项拉好缓存
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { Api.findRowsCached("") }
        }
    }

    /**
     * 侧栏确定 / →：切到该分类、把焦点送进内容区，并**锁住侧栏**。
     * 页面刚切过去时可能还没组合出可聚焦节点（数据在加载），所以带重试，
     * 彻底消灭「按了没反应」的空格。
     */
    val enterContent: (Screen) -> Unit = { screen ->
        // 打赏不走路由：弹窗盖在当前页上，栈和焦点都不动
        if (screen is Screen.Donate) {
            donateOpen = true
        } else {
            // 等同按确定选中该分类：清掉叠加页、切到目标页
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
            stack[0] = screen
            // 进「追更」即视为已读：角标清零（与 PC 读通知同语义）
            if (screen is Screen.Follow) {
                Store.followBadge = 0
                unreadFollow = 0
            }
            if (railLockable) {
                // TV：进板块 = 锁侧栏 + 焦点送进内容区（遥控器没有"点"，必须自动送焦点）
                railLocked = true
                bridgeScope.launch {
                    if (!focusIntoContent(screen)) {
                        // 重试耗尽（比如空页面）：解锁，焦点留在侧栏，不悬空也不把人锁在门外
                        railLocked = false
                        runCatching { railEntry.requestFocus() }
                    }
                }
            }
            // 平板（触屏）：什么都不做。以前照搬 TV 也会把焦点塞进内容区第一个控件
            // ——找剧页=搜索框、设置页=匹配码输入框，一进页面键盘就自动弹出来；
            // 而且侧栏被锁灰，想去别的板块还得先按返回。触屏直接点侧栏就行，
            // 焦点应该留给用户第一次真正点到的控件。
        }
    }
    // 找剧页的焦点入口（绑在搜索框上）：几何搜索在这个页面不可靠，直接点名

    Row(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    userActed = true
                    when (ev.key) {
                        Key.DirectionLeft -> lastKeyLeft = true
                        Key.DirectionRight -> lastKeyLeft = false
                        else -> {}
                    }
                }
                false
            },
    ) {
        NavRail(
            followBadge = unreadFollow,
            // 侧栏高亮和「当前分类」的焦点入口，都要跟着**栈底那一页**走。
            // 传栈顶的话，进了详情页就没有任何一项是「当前分类」：
            // 红底高亮消失、焦点入口也没挂在任何项上 —— 从内容区往左退回来就掉进空里。
            current = stack.first(),
            // 侧栏可聚焦的两个条件：①顶层页面（详情/播放这类压栈页期间锁掉）；
            // ②没有进入板块（进了板块也锁，按返回才解开）。两把锁任意一把关上，
            // 焦点都不可能从内容区左侧漏到侧栏。
            // 平板（触屏）：侧栏任何时候都能点 —— 点了就清掉详情/播放、切到目标板块。
            // 以前这里跟 TV 一样锁死，用户在详情页点侧栏毫无反应（只是变暗），
            // 触屏第一反应被无视，只能去按系统返回。
            enabled = if (railLockable) stack.size <= 1 && !railLocked else true,
            entryFocus = railEntry,
            onFocused = { railFocused = it },
            // 侧栏上按确定 = 进这个板块（切换分类 + 焦点进内容区 + 锁侧栏）。
            // 只切页不进内容区的话，遥控器还停在侧栏上，等于没"进去"。
            // 右键不再算确认（用户明确要求：侧栏只认上下 + 确定），见 NavRail 里的按键处理。
            onPick = enterContent,
            // 右键 = 把焦点送进内容区（保持当前分类，不切板块）。
            // 直接 requestFocus 到 1dp 桥接条在这条路径上不生效（实测按 → 仍无反应），
            // 走 enterContent 与「确定」同一条已验证的链路。
            onRight = { enterContent(stack.first()) },
        )
        // focusGroup：把内容区当成一个整体参与方向键搜索。
        // 不加的话，从 72dp 宽的侧栏往右找落点时，如果内容区第一个可聚焦控件
        // 在垂直方向上没有重叠（比如找剧页的搜索框比侧栏项高），就找不到，
        // 遥控器「→ 进不去页面」，只能靠 TAB。
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                // 返回键 = 从板块退回导航层。用 onKeyEvent（冒泡）而不是
                // onPreviewKeyEvent：找剧输入框在输入态按返回要收键盘，得让子控件先处理。
                // 侧栏这时是锁着的（不可聚焦），所以先解锁、等这一帧它重新可聚焦，
                // 再点名「当前分类」，否则 requestFocus 会静默失败、焦点直接丢掉。
                .onKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown &&
                        ev.key == Key.Back &&
                        stack.size == 1 &&
                        railLocked
                    ) {
                        railLocked = false
                        bridgeScope.launch {
                            androidx.compose.runtime.withFrameNanos { }
                            runCatching { railEntry.requestFocus() }
                        }
                        return@onKeyEvent true
                    }
                    false
                },
        ) {
            // 焦点跳板：
            // 1dp 宽、全高、可聚焦，跟侧栏每一项在垂直方向都重叠，
            // 所以从侧栏按 → 一定落到它身上；它拿到焦点就立刻交给内容区第一个控件。
            // 只在两种时刻渲染：①顶层且侧栏没锁（导航层，从侧栏往右进）；②正在
            // 把焦点送进内容区的那一瞬。锁着侧栏时留着它，左边缘按左会把焦点吸到
            // 这条看不见的 1dp 细条上（用户看到的就是「光标消失了」）。
            if (stack.size <= 1 && (!railLocked || enteringContent)) Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(1.dp)
                    .fillMaxHeight()
                    .focusRequester(focusBridge)
                    .focusable()
                    .onFocusChanged { st ->
                        if (!st.isFocused) return@onFocusChanged
                        // 必须等这一帧结束再转发：现在仍在按键引起的焦点搜索过程中，
                        // 这时 moveFocus 经常被忽略，焦点就悬在这条看不见的条上
                        // —— 用户看到的就是「往右按一下是空的」。
                        bridgeScope.launch {
                            withFrameNanos { }
                            if (lastKeyLeft) {
                                // 两种情况在左边缘按左都「留在内容区」：
                                // ① 压栈页（详情/播放）——侧栏里没有对应项，退回侧栏会把焦点
                                //    交给一个**不是当前页**的分类（实测落到「追更」），而侧栏上
                                //    按 → 的规则是「切到该分类并进内容区」，结果就是看详情时被
                                //    直接带走；
                                // ② 已经进入板块（侧栏锁着）——同理，方向键不该溜到侧栏。
                                if (stack.size > 1 || railLocked) {
                                    focusBridgeManager.moveFocus(FocusDirection.Right)
                                    return@launch
                                }
                                // 从内容区往左退回来：点名侧栏「当前分类」项。
                                // 以前用 moveFocus(Left) 几何找，会落错项（比如回到排行榜）
                                if (runCatching { railEntry.requestFocus() }.isSuccess) return@launch
                                focusBridgeManager.moveFocus(FocusDirection.Left)
                                return@launch
                            }
                            // 找剧页：直接点名进搜索框（几何搜索在这里时灵时不灵）
                            // 其它页面：先按方向找，再按顺序焦点兜底（设置页第一个可聚焦
                            // 控件比侧栏项高，横向搜索找不到）；两个都不行才退回侧栏。
                            //
                            // 这里必须**带重试**：页面刚切过去时控件可能还没组合出来
                            // （数据在加载），一次失败就把焦点留在 1dp 细条上的话，
                            // 用户看到的就是「按右是空的、焦点不见了」。实测找剧页必现。
                            repeat(12) {
                                val named = current is Screen.Find &&
                                    runCatching { findEntry.requestFocus() }.isSuccess
                                if (named) return@launch
                                if (focusBridgeManager.moveFocus(FocusDirection.Right) ||
                                    focusBridgeManager.moveFocus(FocusDirection.Next)
                                ) {
                                    return@launch
                                }
                                kotlinx.coroutines.delay(80)
                            }
                            // 进不去内容区（页面可能一直加载不出来）：回侧栏，别悬空。
                            // 侧栏若锁着先解锁，否则 requestFocus 会静默失败、焦点直接丢掉。
                            if (railLocked) railLocked = false
                            runCatching { railEntry.requestFocus() }
                        }
                    },
            )
            when (current) {
                is Screen.Home -> HomeScreen(
                    onOpen = { push(Screen.Detail(it.seriesId)) },
                    onPlay = { push(Screen.Play(it.seriesId, 1)) },
                )
                is Screen.Feed -> FeedScreen(
                    onOpenDetail = { push(Screen.Detail(it)) },
                    onExit = {
                        if (railLockable) {
                            // TV：退回导航层（和顶层页按返回同语义），页面本身不换
                            railLocked = false
                            bridgeScope.launch {
                                androidx.compose.runtime.withFrameNanos { }
                                runCatching { railEntry.requestFocus() }
                            }
                        } else {
                            // 触屏：按返回直接回推荐（同 PC 刷剧页的 onExit）
                            stack[0] = Screen.Home
                        }
                    },
                )
                is Screen.Find -> FindScreen(
                    onOpen = { push(Screen.Detail(it.seriesId)) },
                    entryFocus = findEntry,
                    onSearchFocus = { findFocused = it },
                )
                is Screen.Rank -> RankScreen(
                    onOpen = { push(Screen.Detail(it.seriesId)) },
                    onPlay = { push(Screen.Play(it.seriesId, 1)) },
                    onCreator = { push(Screen.Creator(it)) },
                )
                is Screen.NewDrama -> NewDramaScreen(
                    onOpen = { push(Screen.Detail(it.seriesId)) },
                    // 日历里「已上线」的行可以直接播放（从第 1 集起）
                    onPlay = { id -> push(Screen.Play(id, 1)) },
                )
                is Screen.History -> RecordsScreen(
                    initialTab = 0,
                    onOpen = { push(Screen.Detail(it)) },
                    // 历史行带云端集内秒数 → 交给播放器起播时续上（跨端进度）
                    onPlay = { id, index, startMs -> push(Screen.Play(id, index, startMs)) },
                )
                is Screen.Favorite -> RecordsScreen(
                    initialTab = 1,
                    onOpen = { push(Screen.Detail(it)) },
                    onPlay = { id, index, startMs -> push(Screen.Play(id, index, startMs)) },
                )
                is Screen.Like -> RecordsScreen(
                    initialTab = 2,
                    onOpen = { push(Screen.Detail(it)) },
                    onPlay = { id, index, startMs -> push(Screen.Play(id, index, startMs)) },
                )
                is Screen.Follow -> FollowReserveScreen(
                    0,
                    onOpen = { push(Screen.Detail(it)) },
                    onPlay = { id -> push(Screen.Play(id, (Store.progress(id) + 1).coerceAtLeast(1))) },
                    onLogin = { push(Screen.Login) },
                )
                is Screen.Reserve -> FollowReserveScreen(
                    1,
                    onOpen = { push(Screen.Detail(it)) },
                    onPlay = { id -> push(Screen.Play(id, (Store.progress(id) + 1).coerceAtLeast(1))) },
                    onLogin = { push(Screen.Login) },
                )
                is Screen.Account -> AccountScreen(
                    onOpen = { push(Screen.Detail(it)) },
                    onLogin = { push(Screen.Login) },
                    onGoHistory = { t -> UiMemo.recordsTab = t; push(Screen.History) },
                )
                is Screen.Login -> LoginScreen(onDone = { back() })
                is Screen.Settings -> SettingsScreen()
                is Screen.Detail -> DetailScreen(
                    seriesId = current.seriesId,
                    onBack = { back() },
                    // 从播放页点「详情」再点某一集时，栈里已经有一层同剧的播放页了，
                    // 直接 push 会变成 播放1 → 详情 → 播放2，返回要按好几次，
                    // 而且中间那层每次都重新起播。这里先退回到已有的播放页再改参数。
                    onPlay = { id, index ->
                        val existing = stack.indexOfLast { it is Screen.Play && it.seriesId == id }
                        if (existing >= 0) {
                            while (stack.size > existing + 1) stack.removeAt(stack.lastIndex)
                            stack[existing] = Screen.Play(id, index)
                        } else {
                            push(Screen.Play(id, index))
                        }
                    },
                    onCreator = { push(Screen.Creator(it)) },
                    onOpen = { push(Screen.Detail(it)) },
                )
                is Screen.Creator -> CreatorScreen(
                    userId = current.userId,
                    onBack = { back() },
                    onOpen = { push(Screen.Detail(it)) },
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun NavRail(
    current: Screen,
    onPick: (Screen) -> Unit,
    onFocused: (Screen) -> Unit = {},
    entryFocus: FocusRequester? = null,
    /** false = 侧栏不接收焦点（压栈页期间）。见 App() 里的调用。 */
    enabled: Boolean = true,
    /** 「追更」项右上角的未读数（0 = 不显示） */
    followBadge: Int = 0,
    /** 侧栏上按右键：不切板块（用户要求），但要把焦点送进内容区，否则按了没反应 */
    onRight: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    Column(
        Modifier
            .width(72.dp)
            .fillMaxHeight()
            .background(Panel)
            .padding(vertical = 8.dp),
        // 11 个分类 + 打赏 + 设置：桌面/电视常见 540dp 高度下必须排得下，
        // 之前加了「刷剧」后底部「设置」被挤出屏幕（文字被裁）——整体收紧间距。
        verticalArrangement = Arrangement.spacedBy(1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "开心果",
            color = Red,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        navItems.forEach { item ->
            // 侧栏只在「顶层页面」可聚焦。进了详情/播放这类压栈页之后，侧栏变暗、不接收焦点——
            // 否则从内容区左边缘按 ←，几何搜索会把焦点送到某个「不属于当前页」的分类上，
            // 而侧栏上按 → 的规则是「切到该分类并进内容区」，结果就是看详情时被直接带走。
            val active = enabled && current::class == item.screen::class
            val tint = when {
                !enabled -> TextDim.copy(alpha = 0.35f)
                active -> Color.White
                else -> TextDim
            }
            Column(
                // 高度用 weight 均分：不同平板/电视的逻辑高度差别很大（实测有的设备
                // 把底部「设置」挤出屏幕），固定高度排不下。weight 让 11 项永远等分，
                // 内容垂直居中，任何尺寸都不会被裁。
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 9.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (active) Red else Color.Transparent)
                    .then(
                        if (!enabled) {
                            Modifier
                        } else {
                            Modifier
                                // focusRequester 必须在 focusable（clickable）之前才挂得上焦点目标
                                .then(if (active && entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier)
                                // ⚠️ onFocusChanged 必须挂在 clickable **之前**：它是「观察外层焦点目标」的，
                                // 挂在 clickable 后面时它下面已经没有焦点目标了，回调永远不触发。
                                // 后果就是启动时那个「默认焦点重试」永远等不到确认，6 秒内每 150ms
                                // 把焦点抢回当前分类一次 —— 用户表现为「按下去又被弹回来」。
                                .onFocusChanged { if (it.isFocused) onFocused(item.screen) }
                                .clickable { onPick(item.screen) }
                                // 侧栏只认「上下移动 + 确定进入」。右键以前被当成确认
                                // （按下就切板块），用户明确不要；但什么也不做的话按 → 完全没反应，
                                // 用户会以为遥控器坏了。现在：右键不切板块，只把焦点送进内容区。
                                .onPreviewKeyEvent { ev ->
                                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) {
                                        onRight()
                                        true
                                    } else false
                                }
                        },
                    )
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box {
                    if (item.custom != null) {
                        RailIcon(item.custom, tint)
                    } else {
                        Icon(item.icon!!, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    }
                    // 「追更」未读角标：红底白字，>99 显示 99+
                    if (item.screen is Screen.Follow && followBadge > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 10.dp, y = (-6).dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFFE5484D))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                if (followBadge > 99) "99+" else "$followBadge",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    item.label,
                    color = tint,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 原版同款导航图标（排行榜柱状图 / 新剧锯齿章 / 历史时钟），Canvas 手绘不引图标库。 */
@Composable
private fun RailIcon(kind: String, tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        when (kind) {
            "cafe" -> {
                // 咖啡杯（打赏入口）：杯身圆角方 + 杯柄 + 热气两缕，同 Canvas 线稿风格
                val cr = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                val sw = 1.4.dp.toPx()
                // 杯身
                drawRoundRect(
                    tint,
                    topLeft = Offset(w * 0.16f, h * 0.34f),
                    size = Size(w * 0.52f, h * 0.46f),
                    cornerRadius = cr,
                    style = Stroke(sw),
                )
                // 杯柄
                drawArc(
                    tint,
                    startAngle = -75f,
                    sweepAngle = 220f,
                    useCenter = false,
                    topLeft = Offset(w * 0.6f, h * 0.42f),
                    size = Size(w * 0.26f, h * 0.3f),
                    style = Stroke(sw),
                )
                // 热气两缕
                drawLine(
                    tint,
                    start = Offset(w * 0.34f, h * 0.1f),
                    end = Offset(w * 0.34f, h * 0.24f),
                    strokeWidth = sw,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawLine(
                    tint,
                    start = Offset(w * 0.54f, h * 0.1f),
                    end = Offset(w * 0.54f, h * 0.24f),
                    strokeWidth = sw,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            "rank" -> {
                val bw = w * 0.2f
                val cr = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
                drawRoundRect(tint, topLeft = Offset(w * 0.06f, h * 0.28f), size = Size(bw, h * 0.64f), cornerRadius = cr, style = Fill)
                drawRoundRect(tint, topLeft = Offset(w * 0.4f, h * 0.08f), size = Size(bw, h * 0.84f), cornerRadius = cr, style = Fill)
                drawRoundRect(tint, topLeft = Offset(w * 0.74f, h * 0.42f), size = Size(bw, h * 0.5f), cornerRadius = cr, style = Fill)
            }
            "new" -> {
                val cx = w / 2f
                val cy = h / 2f
                val p = Path()
                val n = 12
                for (i in 0 until n * 2) {
                    val ang = Math.PI * i / n
                    val r = (if (i % 2 == 0) w * 0.5f else w * 0.42f).toDouble()
                    val x = cx + (r * Math.cos(ang)).toFloat()
                    val y = cy + (r * Math.sin(ang)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                drawPath(p, tint, style = Fill)
                // 感叹号用导航栏底色镂空
                drawRoundRect(
                    Panel,
                    topLeft = Offset(cx - w * 0.05f, cy - h * 0.3f),
                    size = Size(w * 0.1f, h * 0.36f),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    style = Fill,
                )
                drawCircle(Panel, radius = w * 0.06f, center = Offset(cx, cy + h * 0.22f))
            }
            "reserve" -> {
                // 日历：预约 = 上线日期
                val stroke = 1.7.dp.toPx()
                drawRoundRect(
                    tint,
                    topLeft = Offset(w * 0.13f, h * 0.2f),
                    size = Size(w * 0.74f, h * 0.64f),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    style = Stroke(stroke),
                )
                drawLine(tint, Offset(w * 0.13f, h * 0.4f), Offset(w * 0.87f, h * 0.4f), strokeWidth = stroke)
                drawLine(tint, Offset(w * 0.32f, h * 0.09f), Offset(w * 0.32f, h * 0.27f), strokeWidth = stroke)
                drawLine(tint, Offset(w * 0.68f, h * 0.09f), Offset(w * 0.68f, h * 0.27f), strokeWidth = stroke)
                drawCircle(tint, radius = w * 0.07f, center = Offset(w * 0.36f, h * 0.6f))
                drawCircle(tint, radius = w * 0.07f, center = Offset(w * 0.62f, h * 0.6f))
            }
            "history" -> {
                val stroke = 1.7.dp.toPx()
                val cx = w / 2f
                val cy = h / 2f
                val r = w * 0.4f
                drawCircle(tint, radius = r, center = Offset(cx, cy), style = Stroke(stroke))
                drawLine(tint, Offset(cx, cy), Offset(cx, cy - r * 0.55f), strokeWidth = stroke)
                drawLine(tint, Offset(cx, cy), Offset(cx + r * 0.5f, cy + r * 0.25f), strokeWidth = stroke)
                // 左上逆时针箭头
                val p = Path()
                p.moveTo(w * 0.02f, h * 0.34f)
                p.lineTo(w * 0.24f, h * 0.04f)
                p.lineTo(w * 0.34f, h * 0.34f)
                p.close()
                drawPath(p, tint, style = Fill)
            }
        }
    }
}
