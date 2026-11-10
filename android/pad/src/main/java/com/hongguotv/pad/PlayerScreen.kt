package com.hongguotv.pad

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 提前准备几集（当前集之外）。
 *
 * 1 = 只备下一集（老行为）；2 = 正在看第 N 集时，N+1、N+2 都备好，连看三集都不用等。
 * 别往大调：每一集都要在后台拉约 1MB 片头并占一条连接，条数一多就会跟当前这集抢带宽。
 */
private const val PREFETCH_AHEAD = 2

/**
 * 无缝换集开关。
 *
 * true  = 把预取好的下一集**挂进播放器的播放队列**，播完由播放器自己接手，
 *         不重建解码器，换集没有那零点几秒的定格。
 * false = 回到老行为（播完手动 setMediaItem 换源，会有短暂定格）。
 *
 * 这个开关就是回滚手段：万一队列方式出问题（串集/进度错乱），
 * 改成 false 重新编译发版即可，不需要恢复整份源码。
 */
private const val SEAMLESS_PLAYLIST = true

/**
 * 播放页。
 *
 * 遥控器键位：确定 = 暂停/继续、左右 = ±10 秒、上/下 = 弹出控制条；
 * 控制条显示时返回 = 收起（先收浮层再收控制条），再按才退出播放页。
 *
 * 控制台结构与 Windows 版一致（见 [PlayerChrome]）：
 * 细进度条 + 播放/上下集 + 时间 + 弹幕输入框，右侧倍速/清晰度/选集/弹幕设置/弹幕开关。
 * 音量、横竖屏、无边框、全屏、点赞、收藏、评论按需求在 Pad/TV 上不出现。
 *
 * 切集只下 1MB 头部就能起播（原来是 8MB）；切集瞬间旧的那一路会被关掉，
 * 避免两路取流同时抢带宽——那正是「切集卡死」的主因之一。
 */
@Composable
fun PlayerScreen(
    seriesId: String,
    startIndex: Int,
    /** 从历史点进来时云端记录的「这一集看到第几秒」；只用一次，0 = 没有 */
    startMs: Long = 0L,
    onBack: () -> Unit,
    onSwitch: (String, Int) -> Unit,
    onDetail: () -> Unit = {},
) {
    val context = LocalContext.current

    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    /** 这一季的详情是否已经拉回来了：用来区分「还在加载」和「这一季确实没内容」 */
    var detailLoaded by remember { mutableStateOf(false) }
    // index 必须带 startIndex 做 key：切集走的是「更新栈顶 Screen.Play」，
    // 同一个 composable 位置被复用，不带 key 的话 remember 会把新集数吃掉，
    // 表现就是点了选集/下一集，画面还在原集播。
    var index by remember(startIndex) { mutableStateOf(startIndex) }
    var title by remember { mutableStateOf("") }

    var controlsVisible by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(PlayerMenu.None) }
    var lastActionAt by remember { mutableStateOf(0L) }
    /** 弹幕输入框拿到焦点期间不许自动收控制条，否则打字打到一半栏没了 */
    var editing by remember { mutableStateOf(false) }

    var speed by remember { mutableStateOf(Store.speed) }
    var dmOn by remember { mutableStateOf(Store.danmakuOn) }
    var dmOpacity by remember { mutableStateOf(Store.dmOpacity) }
    var dmFont by remember { mutableStateOf(Store.dmFontScale) }
    var dmDensity by remember { mutableStateOf(Store.dmDensity) }
    var dmArea by remember { mutableStateOf(Store.dmArea) }

    // 清晰度：0 = 自动（取 Store.maxHeight 上限以内的最高档）
    var defWant by remember { mutableStateOf(0) }
    var tiers by remember { mutableStateOf<List<PlayTier>>(emptyList()) }
    var curDef by remember { mutableStateOf(0) }

    var dmItems by remember { mutableStateOf<List<DmItem>>(emptyList()) }
    var hint by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var preparedIndex by remember { mutableStateOf(-1) }
    /** 转圈只在真需要等网络时出现；已经预取好的集切过去不转 */
    var showSpinner by remember { mutableStateOf(true) }
    /** 预取好的下一集：URL / 索引 / 本地代理全部就绪，切过去只差一句 setMediaItem */
    var prefetchReady by remember { mutableStateOf<ReadyEpisode?>(null) }
    /** 再往后一集的预取（「预取集数 = 2」时的第二集）。 */
    var prefetchReady2 by remember { mutableStateOf<ReadyEpisode?>(null) }
    /**
     * 已经挂进播放器队列的集：代理地址 → ReadyEpisode。
     * 播放器自动切到某一集时，靠这张表找到那集的流/代理并接管（而不是再去请求一次）。
     */
    val queued = remember { androidx.compose.runtime.mutableStateMapOf<String, ReadyEpisode>() }
    /** 队列已经自动接手过的集号。那个集号不要再走「重新取流」的老路。 */
    var seamlessHandled by remember { mutableStateOf(-1) }
    /**
     * 播放地址 → 集号。队列自动换集时外层导航栈里的集号不会更新，
     * 所以「播完之后该接第几集」不能问栈，只能问播放器当前在放哪个地址。
     */
    val uriToEp = remember { mutableMapOf<String, Int>() }
    /** 预取正在进行时的占位：切集撞上它就直接等，不用重新取一遍流 */
    var prefetchSlot by remember { mutableStateOf<CompletableDeferred<ReadyEpisode?>?>(null) }
    /** 预取用：当前集自己的 vid，用来校验 episodes 列表刷新后没串集 */
    var preparedVid by remember { mutableStateOf("") }
    /** 正在预取哪一集（-1 = 没在预取）；切成别的集时靠它让预取自己收手 */
    var prefetchWant by remember { mutableStateOf(-1) }
    /** 切集/换源的头一秒：这段时间 playing 会短暂变 false，不能当成「用户暂停了」 */
    var switching by remember { mutableStateOf(false) }

    // 播放器监听器用（避免闭包捕获旧值）
    //
    // 这里每一个都要 rememberUpdatedState：DisposableEffect(Unit) 只跑一次，
    // listener 里闭包捕获的全是首次组合时的值。之前只包了 index 和集数，
    // seriesId 没包 —— 结果跨季之后（第一季→第二季）listener 里还是第一季的 id，
    // 于是第二季第 1 集播完会用第一季的 id 去切第 2 集，看起来就是「跳回第一季」。
    val curIndex by rememberUpdatedState(index)
    /**
     * 给播放器监听器用的「设置当前集号」入口。
     *
     * 为什么不能直接写 index：index 是 remember(startIndex) 存的，换季/换剧时
     * startIndex 变了会**重建一份新的状态对象**；而监听器整个播放页只创建一次，
     * 手里攥着的是旧的那份。直接写等于写进一个已经没人读的死对象——
     * 表现就是「画面已经跳到下一集了，界面上还显示第 1 集」。
     * rememberUpdatedState 每次重组都会把最新的 lambda 换进来，永远写得到当前那份。
     */
    val setEpisodeIndex: (Int) -> Unit by rememberUpdatedState { v -> index = v }
    val curCount by rememberUpdatedState(episodes.size)
    val curSeries by rememberUpdatedState(seriesId)
    val curSwitch by rememberUpdatedState(onSwitch)

    // 多季接续去重：STATE_ENDED 可能连着回调多次
    var seasonJumping by remember { mutableStateOf(false) }

    // 换集或换季之后放开跳季锁，否则第二季播完时 seasonJumping 还是 true，永远不再往第三季跳
    LaunchedEffect(seriesId, index) { seasonJumping = false }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(true) }

    val focus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // 根节点当前是否（自己或子节点）持有焦点。控制条按钮聚焦时这里也是 true，
    // 正好用来判断「用户已经把焦点移到控制条上了，别再抢回全屏层」。
    var rootHasFocus by remember { mutableStateOf(false) }
    val rootFocusedNow by rememberUpdatedState(rootHasFocus)

    // TV 与 Pad 共用一份源码，靠包名区分：TV 用遥控器，键位和 Pad 的触屏手势不一样
    val isTv = Store.appContext.packageName.endsWith("nativeapp")

    /**
     * 遥控器焦点是否在底部按钮行上。
     *
     * 不能靠 onFocusChanged 判断（容器不会因为子控件聚焦而回调），所以显式记：
     * 按 ↓ 进按钮行 → true；按 ↑ 或返回退出 → false。
     * 这样「确认=暂停、左右=快进快退」只在**焦点不在按钮行**时生效，
     * 一旦进了按钮行，方向键就交给 Compose 去移动焦点，两种用法不打架。
     */
    var chromeFocused by remember { mutableStateOf(false) }

    // 当前这一路取流（切集时留着上一路，等新的起来再关）
    var live by remember { mutableStateOf<Pair<RemoteVideo, StreamProxy>?>(null) }

    /**
     * 起播缓冲门槛按点播调低。
     *
     * ExoPlayer 默认要凑够 2.5 秒才开播，连播时「上一集放完 → 下一集」就是硬生生
     * 卡 2 秒多；本地代理已经把数据放在手边了，没必要等这么久。
     */
    /**
     * 解码方式：控制条上可随时切（硬解 ↔ 软解），不用退出到设置。
     *
     * 切换必须重建播放器（Media3 不支持运行时换解码器），位置在重建后跳回去，
     * 用户只需要等一次很短的重新缓冲。设置页里那个开关是它的持久值。
     */
    var softMode by remember { mutableStateOf(Store.softDecode) }
    /** 重建计数：+1 就让 remember 重新造一个播放器 */
    var decodeGen by remember { mutableStateOf(0) }
    /** 重建后要跳回的位置（切换前记下来的秒数，毫秒） */
    var decodeSeekTo by remember { mutableStateOf(-1L) }

    val player = remember(decodeGen) {
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 400, 800)
            .build()
        // 视频解码器：默认交给厂商硬解；开了「软件解码」就强制走 Google 软解。
        //
        // 为什么需要这个开关：部分电视的硬解（实测小米 MiTV 用的
        // OMX.amlogic.hevc.decoder.awesome）遇到个别 HEVC 流会一直
        // `wait timeout` + 丢帧，丢帧时没写满的缓冲区直接显示出来就是
        // 一片片绿块，暂停也定在那一帧。软解慢一点，但输出是准的。
        val renderers = DefaultRenderersFactory(context)
        if (softMode) {
            renderers.setMediaCodecSelector { mime, secure, tunneling ->
                val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                if (!mime.startsWith("video/")) {
                    all
                } else {
                    // 名字里带 google / c2.android 的是 AOSP 软解，排到最前面
                    val soft = all.filter {
                        it.name.contains("google", true) || it.name.contains("c2.android", true)
                    }
                    if (soft.isEmpty()) all else soft + all.filterNot { it in soft }
                }
            }
        }
        ExoPlayer.Builder(context, renderers).setLoadControl(load).build()
            .apply { playWhenReady = true }
    }

    // ---- 换集最后一帧定格 ----
    // 播放器切到下一集的瞬间，画面缓冲区会被清掉，新一集要等解码器吐出第一帧
    // 才有内容 —— 电视上就是「黑一下 / 像刷新了一遍」。这里在快播完时把当前
    // 这一帧抓成图先顶着，新一集真正出画（onRenderedFirstFrame）再撤。
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var heldFrame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var heldAt by remember { mutableStateOf(0L) }

    fun captureLastFrame() {
        val surface = playerViewRef?.videoSurfaceView as? android.view.SurfaceView ?: return
        val w = surface.width
        val h = surface.height
        if (w <= 0 || h <= 0) return
        val bmp = runCatching {
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565)
        }.getOrNull() ?: return
        val t0 = System.currentTimeMillis()
        runCatching {
            android.view.PixelCopy.request(
                surface,
                bmp,
                { result ->
                    if (result == android.view.PixelCopy.SUCCESS && !isMostlyBlack(bmp)) {
                        heldFrame = bmp
                        heldAt = t0
                        android.util.Log.i("nextup", "held frame ${w}x$h ok")
                    } else {
                        android.util.Log.i("nextup", "held frame skip result=$result")
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
        }
    }

    // 换解码器后新播放器是空的：把当前这一集的本地代理流重新挂上，并跳回切换前的位置
    LaunchedEffect(decodeGen) {
        if (decodeGen == 0) return@LaunchedEffect
        val stream = live ?: return@LaunchedEffect
        runCatching {
            // 新播放器里的这条流也要能查到集号：播完时「下一集是几号」靠它
            uriToEp[stream.second.url] = index
            player.setMediaItem(MediaItem.fromUri(stream.second.url))
            player.setPlaybackSpeed(speed)
            player.prepare()
            if (decodeSeekTo > 0) player.seekTo(decodeSeekTo)
            player.play()
        }
        decodeSeekTo = -1L
    }

    // 监听器/释放跟着**播放器实例**走：切解码器会重建播放器，旧实例要解绑释放，
    // 新实例要重新挂上同一套监听（下面流代理的清理仍在屏幕级，不跟着重建）。
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            /**
             * 队列自动接手：上一集播完，播放器自己切到挂在后面的那一集。
             *
             * 这里是"无缝"的关键——不再走「播完 → 通知上层 → 重新取流 → setMediaItem」，
             * 所以没有解码器重建、没有画面定格。要做的只是把状态对齐到新的那一集。
             */
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!SEAMLESS_PLAYLIST) return
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
                val uri = mediaItem?.localConfiguration?.uri?.toString() ?: return
                val ready = queued.remove(uri) ?: return
                val epIndex = ready.index
                // 换集前的清晰度：用来判断这次要不要重建解码器
                val prevDef = curDef
                uriToEp[uri] = epIndex
                val prev = live
                live = ready.remote to ready.proxy
                prev?.let { (r, p) ->
                    runCatching { p.stop() }
                    runCatching { r.close() }
                }
                preparedIndex = epIndex
                preparedVid = ready.vid
                tiers = ready.tiers
                curDef = ready.def
                duration = ready.remote.total
                // 先记上"这集是队列接手的"，再去改 index —— 否则 index 一变，
                // 下面那个「开始播放某一集」的 LaunchedEffect 会重新取一次流，
                // 正好把无缝打断（变成原来那种定格）。
                seamlessHandled = epIndex
                setEpisodeIndex(epIndex)
                playing = true
                Log.i("nextup", "seamless -> index=$epIndex def=$prevDef->${ready.def}")
                // 清晰度变了（720↔1080）：不图无缝，重建播放器换一颗干净的解码器。
                // 这台小米电视的 Amlogic 硬解原地重配输出尺寸久了会整屏绿块
                // （日志里反复 720→1080→720 的 omx output size changed），
                // 重建一次就干净了；代价是这一次换集多黑一下（约 0.2~0.5 秒）。
                // 清晰度相同的那一集仍然走无缝队列，该有多顺还是多顺。
                if (prevDef > 0 && ready.def > 0 && prevDef != ready.def) {
                    Log.i("nextup", "def changed -> rebuild decoder")
                    // 重建会丢掉播放器里的队列，但 queued 这张记账表还在 ——
                    // 预取下一轮会以为「下一集已经排好了」而跳过它，结果挂进去的是
                    // 再下一集，播着播着就跳过一集（实测 214 → 216）。这里连账一起清。
                    queued.values.forEach { dropReady(it) }
                    queued.clear()
                    decodeSeekTo = 0L
                    decodeGen += 1
                }
                scope.launch {
                    Store.record(
                        seriesId = curSeries,
                        title = title,
                        cover = DetailCache.get(curSeries)?.cover.orEmpty(),
                        episode = epIndex,
                        total = curCount,
                    )
                    Api.historySyncAsync()
                }
            }

            override fun onRenderedFirstFrame() {
                // 新一集真正出画了：撤掉换集时顶着的定格帧
                if (heldFrame != null) {
                    android.util.Log.i("nextup", "held frame release after ${System.currentTimeMillis() - heldAt}ms")
                }
                heldFrame = null
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED || !Store.autoNext) return
                // 「下一集是几号」以播放器当前在放的那个地址为准：
                // 队列自动换集之后，外层导航栈里的集号是旧的，问它会往回跳（实测会跳回第 2 集）
                val curUri = player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
                val at = uriToEp[curUri] ?: curIndex
                Log.i("nextup", "ended at=$at curUri=${curUri.takeLast(16)}")
                if (at < curCount) {
                    curSwitch(curSeries, at + 1)
                    return
                }
                // 本季播完了：多季剧要接下一季第 1 集（第一季看完接第二季），
                // 上游没有「下一季」接口，从相关推荐里按 season_index 找，见 Api.nextSeason
                if (seasonJumping) return
                seasonJumping = true
                scope.launch {
                    val next = withContext(Dispatchers.IO) { runCatching { Api.nextSeason(curSeries) }.getOrNull() }
                    withContext(Dispatchers.Main) {
                        if (next != null) {
                            curSwitch(next, 1)
                        } else {
                            // 没有更多季了：停在这一集的最后一帧。
                            // 光给个一闪而过的提示不够，把控制台亮出来，
                            // 用户能看到进度在末尾、能直接选集或退出，不会以为卡死。
                            seasonJumping = false
                            hint = "已经是最后一集了"
                            controlsVisible = true
                            lastActionAt = System.currentTimeMillis()
                        }
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            // 只有播放器自己的东西在这里收：流代理/预取属于「这一屏」，
            // 切解码器重建播放器时不能跟着关（关了就没得续播了）。
            runCatching { player.removeListener(listener) }
            runCatching { player.release() }
        }
    }

    // 这一屏的流资源：离开播放页才关（屏幕级，不随播放器重建）
    DisposableEffect(Unit) {
        onDispose {
            prefetchWant = -1
            prefetchSlot?.complete(null)
            prefetchSlot = null
            prefetchReady?.let { dropReady(it) }
            prefetchReady = null
            prefetchReady2?.let { dropReady(it) }
            prefetchReady2 = null
            live?.let { (r, p) ->
                runCatching { p.stop() }
                r.close()
            }
        }
    }

    LaunchedEffect(seriesId) {
        // 换季/换剧时先清掉，避免拿上一季的集数去判断「有没有内容」
        detailLoaded = false
        val d = withContext(Dispatchers.IO) { runCatching { Api.ensureDetail(seriesId) }.getOrNull() }
        if (d != null) {
            episodes = d.episodes
            title = d.title
        }
        detailLoaded = true
        // 只有一个悬空焦点才需要补。用户这会儿可能已经唤出控制条、在按钮行上选东西，
        // 无脑 requestFocus 会把焦点拽回全屏层，控制条那边的操作就白费了。
        if (!rootFocusedNow) runCatching { focus.requestFocus() }
    }

    // 退到后台就暂停：电视按主页键、Pad 最小化窗口都算。回到前台不自动续播，
    // 由用户自己按确定——自动续播会在人还没坐回来的时候先放掉几十秒。
    // key 绑 player：重建过播放器之后，这个监听要挂在新实例上才停得下来。
    LaunchedEffect(player) {
        snapshotFlow { AppLifecycle.inBackground.value }.collect { bg ->
            if (bg && runCatching { player.isPlaying }.getOrDefault(false)) {
                runCatching { player.pause() }
            }
        }
    }

    // 本地续播位置：每 4 秒记一次。退出播放页、切集、甚至杀进程后，
    // 从历史/追更进来都能接着上次的位置看（以前只存内存，重启就归零）
    LaunchedEffect(seriesId, index, player) {
        while (true) {
            delay(4_000)
            // 从「历史 / 追更」直接点继续播放进来时，这一帧详情还没到、episodes 是空的。
            // 以前在协程开头 getOrNull(...) ?: return 会直接结束整个循环，
            // 于是这一整集既不留秒级位置、也不上报（详情页进来的那条路有缓存，看不出问题）。
            // 改成每次 tick 再取，详情到位后自然接上。
            val ep = episodes.getOrNull(index - 1) ?: continue
            val pos = runCatching { player.currentPosition }.getOrDefault(0L)
            Store.markPos(ep.vid, pos)
        }
    }

    // 播放中定期把秒级位置报给官方（互通的关键：手机端能看到「看到第几分第几秒」）
    LaunchedEffect(seriesId, index, player) {
        while (true) {
            delay(30_000)
            val ep = episodes.getOrNull(index - 1) ?: continue
            val pos = runCatching { player.currentPosition }.getOrDefault(0L)
            // 开头 5 秒不报（刚点进来就退出不该把云端进度压回开头）。
            // 但这里必须是 continue 不能是 break：网慢、或者用户一进来就按了暂停时，
            // 第一条 tick 读到的位置还在 5 秒内，break 会让这个协程直接结束，
            // 这一整集再也不上报 —— 手机上就永远看不到「电视里看到第几分钟」。
            if (pos <= 5_000) continue
            withContext(Dispatchers.IO) {
                val ok = runCatching {
                    Api.reportProgress(seriesId, ep.vid, index, pos)
                }.getOrDefault(false)
                Log.d("PadReport", "ep=$index pos=${pos}ms ok=$ok")
            }
        }
    }

    // 换了清晰度就不是"队列已接手"那一集了，得重新取流
    LaunchedEffect(defWant) { seamlessHandled = -1 }

    LaunchedEffect(index, attempt, episodes.size, defWant, detailLoaded) {
        // 队列刚自动把这一集接上、已经在播了 —— 不能再去取一次流，否则无缝就被打断
        if (SEAMLESS_PLAYLIST && seamlessHandled == index) return@LaunchedEffect
        if (episodes.isEmpty()) {
            // 详情还没回来就等（到位后 key 变化会重跑）。已经回来了还是空，
            // 说明这一季是「预约未上线」——自动跳季跳过来会卡在这里转圈，明说一句
            if (detailLoaded) {
                preparing = false
                showSpinner = false
                failed = "这一季还没有上线的剧集"
            }
            return@LaunchedEffect
        }
        val ep = episodes.getOrNull(index - 1) ?: return@LaunchedEffect
        failed = null

        // ---- 快速路径：下一集在上一集播着的时候就预取好了，直接换源 ----
        // URL 解析、1MB 头部、样本表、本地代理全是现成的，只剩一句 setMediaItem，
        // 所以这时候一个转圈都不该出现——这正是「第一集接第二集」看起来断开的原因。
        var hit = prefetchReady
            ?.takeIf { it.index == index && it.vid == ep.vid && it.defWant == defWant }
            ?: prefetchReady2
                ?.takeIf { it.index == index && it.vid == ep.vid && it.defWant == defWant }
        if (hit == null && attempt == 0) {
            // 预取正在路上：等它，别重新取一遍流。等的时候不画转圈，
            // 画面停在上一集最后一帧，比中间闪个圈干净得多。
            val slot = prefetchSlot
            if (slot != null && prefetchWant == index) {
                preparing = true
                showSpinner = false
                hit = withTimeoutOrNull(4_000) { slot.await() }
                    ?.takeIf { it.index == index && it.vid == ep.vid && it.defWant == defWant }
            }
        }
        if (hit != null && attempt == 0) {
            // 所有权交给 live，不能再让预取逻辑去关它
            if (prefetchReady === hit) prefetchReady = null
            if (prefetchReady2 === hit) prefetchReady2 = null
            Log.i("nextup", "hit index=$index def=${hit.def}")
            val ok = runCatching {
                preparing = true
                showSpinner = false
                tiers = hit.tiers
                curDef = hit.def
                duration = hit.remote.total
                // 切走之前把上一集的进度存下来，来回切集/退出都能接着看
                if (preparedVid.isNotEmpty() && preparedIndex > 0) {
                    runCatching { Store.markPos(preparedVid, player.currentPosition) }
                }
                player.setMediaItem(MediaItem.fromUri(hit.proxy.url))
                uriToEp[hit.proxy.url] = index
                player.setPlaybackSpeed(speed)
                player.prepare()
                // 这一集之前看过（同一进程内）就接着看，否则从头
                val resume = Store.posOf(ep.vid).takeIf {
                    it > 2_000 && (hit.remote.total <= 0 || it < hit.remote.total - 5_000)
                } ?: 0L
                if (resume > 0) player.seekTo(resume)
                player.play()
                val prev = live
                live = hit.remote to hit.proxy
                prev?.let { (r, p) ->
                    runCatching { p.stop() }
                    r.close()
                }
                preparedIndex = index
                preparedVid = ep.vid
                Store.record(
                    seriesId = seriesId,
                    title = title,
                    cover = DetailCache.get(seriesId)?.cover.orEmpty(),
                    episode = index,
                    total = episodes.size,
                )
                Api.historySyncAsync()
                scope.launch(Dispatchers.IO) {
                    // 报「这次实际从哪儿开始播」，别再写死 0 —— 写 0 会把云端进度压没，
                    // 手机端/Windows 端看到的就是「刚从头开始」
                    runCatching { Api.reportProgress(seriesId, ep.vid, index, resume) }
                }
                preparing = false
            }.isSuccess
            if (ok) return@LaunchedEffect
            // 预取的那一路万一已经坏了，退回正常流程重新取一遍，不要停在这
        }

        // 没命中（用户从选集直接跳、或刚进播放页）：作废的预取先关掉，别在后台抢带宽
        Log.i("nextup", "miss index=$index have=${prefetchReady?.index}")
        prefetchReady?.let { dropReady(it) }
        prefetchReady = null
        prefetchReady2?.let { dropReady(it) }
        prefetchReady2 = null

        preparing = true
        showSpinner = true
        // 同一切清晰度要保住播放进度；换集才从头开始
        val localPos = Store.posOf(ep.vid)
        val keepAt = if (index == preparedIndex) {
            runCatching { player.currentPosition }.getOrDefault(0L)
        } else if (index == startIndex && startMs > 0L && localPos <= 2_000L) {
            // 从历史点进来：这一集用云端「看到第几秒」。
            // 本机已经有这一集的播放档案（localPos）时以本机为准——起播后
            // 播放器上报的位置会写进档案，之后再 prepare 一次也不会被拉回 0。
            startMs
        } else {
            // 换集：回到这一集上次看到的位置（同一进程内看过才有），没有就从头
            localPos
        }
        var created: Pair<RemoteVideo, StreamProxy>? = null
        try {
            // 档位表一次拿全（含直链/编码），切清晰度直接用表里的地址，不再请求上游
            val ts = withContext(Dispatchers.IO) {
                runCatching { Api.tiers(ep.vid) }.getOrDefault(emptyList())
            }
            tiers = ts
            val cap = if (defWant > 0) defWant else Store.maxHeight
            val playableTiers = ts.filter { it.playable }
            val picked = playableTiers.firstOrNull { it.height == cap }
                ?: playableTiers.firstOrNull { it.height <= cap }
                ?: playableTiers.firstOrNull()
            // 表拿不到（或全是不支持的编码）才退回「让服务器挑一档」
            val info = picked?.toPlayInfo()
                ?: withContext(Dispatchers.IO) { Api.play(ep.vid, 1004, defWant) }
            curDef = info.height
            val rv = RemoteVideo(info)
            withContext(Dispatchers.IO) { rv.prepare() }
            val px = StreamProxy(rv)
            px.start()
            created = rv to px
            duration = rv.total
            // 同上：切集前先记住上一集看到哪儿
            if (preparedVid.isNotEmpty() && preparedIndex > 0) {
                runCatching { Store.markPos(preparedVid, player.currentPosition) }
            }
            // 手动换集/换清晰度会走到这里，播放列表会被整体替换：
            // 之前挂在队列里的那几集也就作废了，连同它们的本地代理一起关掉，
            // 否则几路代理会在后台继续吃带宽
            if (queued.isNotEmpty()) {
                val stale = queued.values.toList()
                queued.clear()
                stale.forEach { dropReady(it) }
            }
            player.setMediaItem(MediaItem.fromUri(px.url))
            uriToEp[px.url] = index
            player.setPlaybackSpeed(speed)
            player.prepare()
            // 只剩最后 5 秒的「上次位置」没意义，直接从头
            if (keepAt > 2_000 && (rv.total <= 0 || keepAt < rv.total - 5_000)) player.seekTo(keepAt)
            player.play()

            // 新的已经跑起来了，这时才关旧的
            val prev = live
            live = created
            created = null
            prev?.let { (r, p) ->
                runCatching { p.stop() }
                r.close()
            }

            preparedIndex = index
            preparedVid = ep.vid
            Store.record(
                seriesId = seriesId,
                title = title,
                // 之前写死空串，导致本地和云端的历史卡片全是空封面
                cover = DetailCache.get(seriesId)?.cover.orEmpty(),
                episode = index,
                total = episodes.size,
            )
            Api.historySyncAsync()
            // 互通：把进度也报给官方，手机官方 App / Windows 版侧栏历史能看到
            scope.launch(Dispatchers.IO) {
                runCatching { Api.reportProgress(seriesId, ep.vid, index, keepAt) }
            }
            preparing = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.message ?: "起播失败"
            preparing = false
        } finally {
            // 被下一集打断（或起播失败）时，本协程自己建的资源在这里关掉
            created?.let { (r, p) ->
                runCatching { p.stop() }
                r.close()
            }
        }
    }

    /**
     * 预取下一集。
     *
     * 当前集真正播起来之后才动手，而且只备下一集：URL 解析 + 1MB 头部 + 样本表 + 本地代理，
     * 全做完放在 [prefetchReady] 里，切集时直接 setMediaItem。
     * 不往前多备——每集都要多下 1MB 头部，顺带抢带宽，而用户绝大多数是顺序看。
     */
    LaunchedEffect(index, preparedIndex, preparedVid, episodes.size, defWant, seamlessHandled, decodeGen) {
        if (preparedIndex != index || preparedVid.isEmpty()) return@LaunchedEffect
        Log.i("nextup", "scan index=$index prepared=$preparedIndex")
        // 起播后稍等一拍再动手，别跟首帧抢带宽；但也不能等太久——有的短剧一集只有 47 秒
        delay(1_500)
        // 提前准备后面 PREFETCH_AHEAD 集（= 2）：连看三集都不用等转圈。
        // 每一集都是一份独立的 RemoteVideo + 本地代理，所以不能开太多——
        // 每份要在后台拉约 1MB 片头，开 5 集以上就会跟当前这集抢带宽，反而更卡。
        for (ahead in 1..PREFETCH_AHEAD) {
            val want = index + ahead
            if (want > episodes.size) break
            val next = episodes.getOrNull(want - 1) ?: break
            if (next.vid.isEmpty()) break
            val slotIndex = ahead == 1
            // 两个格子都要先看：切到 N+1 之后，原来躺在「第二格」里的 N+2
            // 就是新的「第一格」，挪过去就行，不用重新下载
            val ready1 = prefetchReady
                ?.takeIf { it.index == want && it.vid == next.vid && it.defWant == defWant }
            val ready2 = prefetchReady2
                ?.takeIf { it.index == want && it.vid == next.vid && it.defWant == defWant }
            if (ready1 != null || ready2 != null) {
                if (slotIndex && ready1 == null) {
                    prefetchReady2 = null
                    prefetchReady = ready2
                }
                continue
            }
            // 两格里装的都是过期的：先关掉，别让它们继续占连接
            prefetchReady?.let { dropReady(it) }
            prefetchReady = null
            prefetchReady2?.let { dropReady(it) }
            prefetchReady2 = null

            prefetchWant = want
            val slot = if (slotIndex) CompletableDeferred<ReadyEpisode?>() else null
            if (slot != null) prefetchSlot = slot
            var produced: ReadyEpisode? = null
            try {
                withContext(Dispatchers.IO) {
                    produced = buildReady(want, next, defWant) { prefetchWant == want }
                }
                slot?.complete(produced)
                val p = produced
                // 注意 Store.autoNext：设成「播完不自动下一集」时绝不能挂队列，
                // 挂了就等于无视这个设置（播放器会自作主张往下播）
                if (SEAMLESS_PLAYLIST && p != null && Store.autoNext) {
                    // 挂进播放器队列：这一集播完由播放器自己接手，不回到上层重新取流，
                    // 所以没有解码器重建、没有那零点几秒定格。
                    val uri = p.proxy.url
                    // 按集号判重，不能按地址：每次 buildReady 都会起一个新的本地代理（端口不同），
                    // 地址永远不重复。只比地址的话，同一集会被挂进队列两次 → 播完一遍又播一遍。
                    val dup = queued.values.firstOrNull { it.index == p.index }
                    if (dup != null || queued.containsKey(uri)) {
                        dropReady(p)
                        Log.i("nextup", "dup skip index=${p.index}")
                    } else {
                        queued[uri] = p
                        withContext(Dispatchers.Main) {
                            runCatching { player.addMediaItem(MediaItem.fromUri(uri)) }
                        }
                        Log.i("nextup", "queued ahead=$ahead index=${p.index}")
                    }
                } else {
                    if (slotIndex) prefetchReady = produced else prefetchReady2 = produced
                }
                Log.i("nextup", "prefetched ahead=$ahead index=${produced?.index} def=${produced?.def}")
            } catch (e: CancellationException) {
                // 预取跑到一半被切集打断：已经拉起来的代理要自己收拾，别留个后台吃带宽的
                slot?.complete(null)
                Log.i("nextup", "prefetch cancel $want")
                produced?.let { dropReady(it) }
                throw e
            } catch (e: Exception) {
                slot?.complete(null)
                Log.i("nextup", "prefetch fail $want: ${e.message}")
            } finally {
                if (slot != null && prefetchSlot === slot) prefetchSlot = null
                if (prefetchWant == want) prefetchWant = -1
            }
        }
    }

    // key 绑 player：切解码器/换清晰度会重建播放器，这个循环必须跟着换到新实例上，
    // 否则它会一直读旧（已释放）实例的 isPlaying —— UI 永远显示“暂停”，
    // 而画面其实在播（实测：换集重建后控制条自己弹出来、图标是暂停）。
    LaunchedEffect(player) {
        while (true) {
            runCatching {
                position = player.currentPosition
                if (player.duration > 0) duration = player.duration
                playing = player.isPlaying
                // 快播完时抓一帧顶住换集黑屏；抓不到/全黑就什么都不做（沿用原行为）
                val total = player.duration
                if (heldFrame == null && player.isPlaying && total > 0) {
                    val remain = total - player.currentPosition
                    if (remain in 250..1_200) captureLastFrame()
                }
                // 定格帧不能一直挂着：3 秒还没等到新画面就撤（起播失败时别糊住画面）
                if (heldFrame != null && System.currentTimeMillis() - heldAt > 3_000) heldFrame = null
                // 顺手记下这一集看到哪儿：播放页被销毁（比如跳详情页）后回来能接着看
                episodes.getOrNull(index - 1)?.let { ep -> Store.markPos(ep.vid, position) }
            }
            delay(400)
        }
    }

    // 弹幕：官方滚窗协议——从当前窗口拉到「播到哪拉到哪」，has_more=false 收尾
    LaunchedEffect(seriesId, index, episodes.size) {
        dmItems = emptyList()
        val ep = episodes.getOrNull(index - 1) ?: return@LaunchedEffect
        val totalMs = if (ep.duration > 0) ep.duration * 1000L else 0L
        var cursor = ""
        var start = 0L
        val acc = mutableListOf<DmItem>()
        var guard = 0
        while (guard++ < 60) {
            val page = withContext(Dispatchers.IO) {
                runCatching { Api.danmakuPage(ep.vid, seriesId, start, totalMs, cursor) }.getOrNull()
            } ?: break
            if (page.items.isNotEmpty()) {
                acc.addAll(page.items)
                dmItems = acc.sortedBy { it.offsetMs }
            }
            if (!page.hasMore) break
            val next = if (page.nextStart > start) page.nextStart else start + 30_000L
            if (totalMs > 0 && next > totalMs + 5_000L) break
            // 播到窗口边界附近再拉下一窗（官方就是播到哪拉到哪，一次拉全会触发风控降级）
            var waited = 0
            while (position < next - 5_000L && waited < 600_000) {
                delay(500)
                waited += 500
            }
            cursor = page.cursor
            start = next
        }
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(2_200)
            hint = null
        }
    }

    /**
     * 一暂停就把控制台亮出来：暂停时用户就是要操作，这时候藏起来没道理。
     *
     * 但**切集/缓冲也会让 playing 变 false**（ExoPlayer 换源那一瞬），
     * 原来不加判断，于是「上一集播完接下一集」必然弹一次控制条，
     * 连播体验直接断掉。preparing 期间一律不弹。
     */
    /**
     * 换源那一秒 playing 会抖一下（STATE_ENDED → IDLE → BUFFERING → READY），
     * 期间一律不算「暂停」，否则连播必弹一次控制条。
     */
    LaunchedEffect(index) {
        switching = true
        delay(1_000)
        switching = false
    }

    LaunchedEffect(playing, preparing, switching) {
        if (playing || preparing || switching) return@LaunchedEffect
        // 不要「一停就亮」。换集瞬间 playing 会抖一串（ENDED → IDLE → BUFFERING → READY），
        // 原来只靠上面那个 1 秒的 switching 挡着；新一集要是超过 1 秒才起来
        // （缓冲慢、片子大），这一秒一过就被判成「用户暂停了」，控制条闪出来一条。
        // 表现就是连播时偶尔闪一下、没规律（几十集里出现好几次）。
        // 现在改成：真停了以后再等 1.5 秒复核，确实还停着才亮。
        delay(1_500)
        if (playing || preparing || switching) return@LaunchedEffect
        // 电视上：用户刚按过 ←/→（快进会短暂缓冲）或确定（主动暂停），
        // 这时候 isPlaying 变 false 是「他要的效果」，不能顺手把控制条亮出来 ——
        // 一亮就把焦点抢进按钮行，第二下右键就变成移动焦点了。
        val justActed = System.currentTimeMillis() - lastActionAt < 1_500L
        if (!(isTv && justActed)) {
            controlsVisible = true
            lastActionAt = System.currentTimeMillis()
            Log.d("chrome", "auto show paused index=$index")
        }
    }

    /**
     * 5 秒没操作才收起。
     *
     * 四个例外一律不收：暂停中、开着浮层（选集/倍速/清晰度/弹幕设置）、
     * 正在输入弹幕、刚点过控制台上的任何东西（lastActionAt 会被重置，
     * 这里靠它做 key 重新起跑计时）。
     */
    LaunchedEffect(controlsVisible, menu, editing, lastActionAt, playing, chromeFocused) {
        // 注意：这里**不能**因为「焦点在按钮行」就不收。
        // 以前加了 chromeFocused 这个免责条件，结果是用过选集/倍速之后焦点留在按钮行，
        // 控制条就再也不隐藏了——用户表现为「播着播着控制条一直挂着，要按两次返回才收」
        // （第一次返回清焦点标记，第二次才收条）。
        // 现在改成：只要没人在操作就收，收之前先把焦点还给全屏层，别让焦点悬空。
        if (!controlsVisible || !playing || menu != PlayerMenu.None || editing) {
            return@LaunchedEffect
        }
        delay(5000)
        if (System.currentTimeMillis() - lastActionAt < 4900) return@LaunchedEffect
        if (chromeFocused) {
            chromeFocused = false
            runCatching { focusManager.clearFocus() }
            runCatching { focus.requestFocus() }
        }
        controlsVisible = false
    }

    // 控制条收起时焦点标记一起复位，否则下次唤出会误判成「还在按钮行」
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) chromeFocused = false
    }

    /**
     * 浮层（倍速/清晰度/选集/弹幕设置）的焦点由 PlayerChrome 内部接管：
     * 打开时直接请求面板首项，关闭时送回按钮行。这里只把「开着浮层」当作在控制台上，
     * 免得 5 秒计时把面板连同控制条一起收掉。
     */
    LaunchedEffect(menu) {
        if (menu != PlayerMenu.None) chromeFocused = true
    }

    val mark = { lastActionAt = System.currentTimeMillis() }

    fun seekBy(deltaMs: Long) {
        val max = (duration - 800).coerceAtLeast(0)
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, max))
        mark()
    }

    // 遥控器连按加速：一路按着同一个方向时步长 5 → 10 → 20 → 40 秒，
    // 停手 1.2 秒后回到 5 秒。电视上没有可拖动的进度条，一下一下按 5 秒
    // 要到片尾得按几十次；加这一层之后一次长按就能走到任意位置，
    // 而单次轻按的手感仍然是原来的 5 秒。
    var seekDir by remember { mutableStateOf(0) }
    var seekAt by remember { mutableStateOf(0L) }
    var seekStep by remember { mutableStateOf(5_000L) }

    fun seekByKey(dir: Int) {
        val now = System.currentTimeMillis()
        seekStep = if (dir == seekDir && now - seekAt < 1_200) {
            (seekStep * 2).coerceAtMost(40_000L)
        } else {
            5_000L
        }
        seekDir = dir
        seekAt = now
        seekBy(dir * seekStep)
    }

    fun enterChrome() {
        controlsVisible = true
        mark()
        // 焦点交给 PlayerChrome：控制条这一帧才刚可见，按钮还没上屏，
        // 在这里 requestFocus 会抛「FocusRequester is not initialized」并被吞掉，
        // 结果焦点留在全屏层，遥控器怎么按都进不了按钮行。
        chromeFocused = true
    }

    fun leaveChrome() {
        chromeFocused = false
        mark()
        focusManager.clearFocus()
        runCatching { focus.requestFocus() }
    }

    /**
     * TV 键位（遥控器）：
     * - 确认 = 暂停/继续，**不弹控制条**（焦点在按钮行时交给按钮自己）
     * - ←/→ = 快退/快进（单按 5 秒，连按加速到 40 秒），**不弹控制条**（焦点在按钮行时交给焦点移动）
     * - ↑ = 呼出控制条；↓ = 呼出控制条并进入按钮行
     * - 返回 = 关浮层 → 退出按钮行 → 收控制条 → 退出播放
     *
     * 「不弹控制条」是硬要求：以前快进会让 player 短暂缓冲，触发
     * 「暂停就亮控制条」那段逻辑，第一下右键就把焦点抢进按钮行，
     * 连按两下快进根本按不出来。
     *
     * 为什么要这么分：如果确认键永远等于暂停，控制条上的「选集/倍速」就永远按不到；
     * 反过来如果永远交给按钮，控制条一显示就没法暂停。用「有没有进按钮行」区分最直观。
     */
    fun tvKeys(ev: KeyEvent): Boolean = when (ev.key) {
        Key.Back -> when {
            menu != PlayerMenu.None -> { menu = PlayerMenu.None; mark(); true }
            chromeFocused -> { leaveChrome(); true }
            controlsVisible -> { controlsVisible = false; mark(); true }
            else -> { onBack(); true }
        }
        Key.DirectionCenter, Key.Enter, Key.Spacebar ->
            if (chromeFocused) false else {
                if (player.isPlaying) player.pause() else player.play()
                mark()
                true
            }
        Key.DirectionLeft ->
            if (chromeFocused) false else { seekByKey(-1); true }
        Key.DirectionRight ->
            if (chromeFocused) false else { seekByKey(1); true }
        Key.DirectionDown ->
            if (menu != PlayerMenu.None) false
            // 已经在按钮行：吃掉，别把焦点甩出控制台（甩出去就变成快进了，看着像坏了）
            else if (chromeFocused) true
            else { enterChrome(); true }
        Key.DirectionUp ->
            if (menu != PlayerMenu.None) false
            else if (chromeFocused) { leaveChrome(); true }
            else if (!controlsVisible) { controlsVisible = true; mark(); true }
            else false
        else -> false
    }

    /** Pad 键位（触屏为主，这里只服务外接键盘/鼠标，保持原有手感）。 */
    fun padKeys(ev: KeyEvent): Boolean = when (ev.key) {
        Key.Back -> when {
            menu != PlayerMenu.None -> { menu = PlayerMenu.None; true }
            controlsVisible -> { controlsVisible = false; true }
            else -> { onBack(); true }
        }
        Key.DirectionCenter, Key.Enter -> {
            if (controlsVisible) {
                false // 控制条显示时，确定键交给聚焦的按钮
            } else {
                if (player.isPlaying) player.pause() else player.play()
                true
            }
        }
        Key.DirectionLeft ->
            if (controlsVisible) false else { seekByKey(-1); true }
        Key.DirectionRight ->
            if (controlsVisible) false else { seekByKey(1); true }
        Key.DirectionUp, Key.DirectionDown ->
            if (!controlsVisible) { controlsVisible = true; true } else false
        else -> false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onFocusChanged { rootHasFocus = it.hasFocus }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                mark()
                if (isTv) tvKeys(ev) else padKeys(ev)
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    // 换集时保留上一集最后一帧：默认 reset 会清空 Surface，
                    // 在电视上就是那零点几秒的黑闪
                    runCatching { setKeepContentOnPlayerReset(true) }
                    playerViewRef = this
                }
            },
            update = { view -> if (view.player !== player) view.player = player },
            modifier = Modifier
                .fillMaxSize()
                // 原版同款手势层：单击=控制条显隐，双击=暂停/继续，左右滑=±10 秒
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (menu != PlayerMenu.None) {
                                menu = PlayerMenu.None
                            } else {
                                controlsVisible = !controlsVisible
                            }
                            mark()
                        },
                        onDoubleTap = {
                            if (player.isPlaying) player.pause() else player.play()
                            mark()
                        },
                    )
                }
                .pointerInput(Unit) {
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { acc = 0f },
                        onHorizontalDrag = { change, amount ->
                            acc += amount
                            change.consume()
                        },
                        onDragEnd = {
                            // 右滑快进 5 秒、左滑快退 5 秒（阈值 50px，避免手抖误触）
                            if (kotlin.math.abs(acc) > 50) seekBy(if (acc > 0) 5_000 else -5_000)
                        },
                    )
                },
        )

        // 换集瞬间顶住黑屏：把上一集最后一帧盖在画面上，新一集出画
        // （onRenderedFirstFrame）就撤掉。压弹幕之下、控制条之上。
        heldFrame?.let { bmp ->
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 弹幕层压在画面之上、控制条之下；不接指针
        DanmakuLayer(
            player = player,
            items = dmItems,
            enabled = dmOn && !preparing,
            opacity = dmOpacity,
            fontScale = dmFont,
            density = dmDensity,
            area = dmArea,
        )

        if (preparing && showSpinner) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingBlock("正在取流…")
            }
        }

        failed?.let { msg ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(msg, color = TextMain, fontSize = 15.sp)
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier
                            .background(PanelHi, RoundedCornerShape(10.dp))
                            .clickable { attempt++ }
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                    ) { Text("重试", color = TextMain, fontSize = 14.sp) }
                }
            }
        }

        if (controlsVisible) {
            // 顶部：返回胶囊 + 标题/集数 + 详情胶囊（原版播放页形态）
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0x99000000), Color(0x00000000))))
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x40FFFFFF))
                        .clickable { onBack() }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("←", color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.width(5.dp))
                    Text("返回", color = Color.White, fontSize = 14.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        style = TextStyle(
                            shadow = Shadow(Color(0xCC000000), offset = Offset(0f, 1f), blurRadius = 4f),
                        ),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text("第 $index 集 / 共 ${episodes.size} 集", color = Color(0x99FFFFFF), fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                // 电视上这枚胶囊遥控器够不到（焦点进不来），入口已挪到控制条最右侧；
                // Pad 是触摸为主，顶部这颗留着更好点。
                if (!isTv) {
                    Text(
                        "详情",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x40FFFFFF))
                            .clickable { onDetail() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // 控制台（与 Windows 版同一套：细进度条 + 图标行 + 右侧功能组）
        PlayerChrome(
            menu = menu,
            // ↑ 只亮控制条（不抢焦点，左右键照常快进）；↓ 进来时才把焦点交给按钮行
            focusOnShow = chromeFocused,
            onMenu = {
                menu = it
                // 浮层关闭也算一次操作，否则关完立刻开始倒计时
                if (it == PlayerMenu.None) lastActionAt = System.currentTimeMillis()
            },
            visible = controlsVisible,
            position = position,
            duration = duration,
            playing = playing,
            speed = speed,
            definitions = tiers.filter { it.playable }.map { it.height },
            definition = curDef,
            episodes = episodes,
            index = index,
            danmakuOn = dmOn,
            dmOpacity = dmOpacity,
            dmFontScale = dmFont,
            dmDensity = dmDensity,
            dmArea = dmArea,
            onSeek = { player.seekTo(it); mark() },
            softDecode = softMode,
            onToggleDecode = {
                // 记下当前位置：重建播放器后要跳回来，不能让人从头看
                decodeSeekTo = runCatching { player.currentPosition }.getOrDefault(0L)
                softMode = !softMode
                Store.softDecode = softMode
                decodeGen += 1
                mark()
            },
            onTogglePlay = {
                if (player.isPlaying) player.pause() else player.play()
                mark()
            },
            onPrev = {
                mark()
                if (index > 1) onSwitch(seriesId, index - 1)
            },
            onNext = {
                mark()
                if (index < episodes.size) onSwitch(seriesId, index + 1)
            },
            onSpeed = { s ->
                speed = s
                Store.speed = s
                player.setPlaybackSpeed(s)
                mark()
            },
            onDefinition = { h ->
                defWant = h
                mark()
            },
            onPickEpisode = {
                mark()
                onSwitch(seriesId, it)
            },
            onToggleDanmaku = {
                dmOn = !dmOn
                Store.danmakuOn = dmOn
                mark()
            },
            onDmSettings = { o, f, d, a ->
                dmOpacity = o; dmFont = f; dmDensity = d; dmArea = a
                Store.dmOpacity = o; Store.dmFontScale = f
                Store.dmDensity = d; Store.dmArea = a
                mark()
            },
            onSendDanmaku = { text ->
                val ep = episodes.getOrNull(index - 1)
                when {
                    ep == null -> hint = "这一集还没准备好"
                    !Store.loggedIn -> hint = "登录后才能发弹幕"
                    else -> {
                        val at = runCatching { player.currentPosition }.getOrDefault(0L)
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { Api.sendDanmaku(ep.vid, seriesId, text, at) }
                                    .getOrDefault(false)
                            }
                            hint = if (ok) "弹幕已发送" else "弹幕发送失败"
                        }
                    }
                }
                mark()
            },
            onEditingChange = { editing = it },
            isTv = isTv,
            playFocus = if (isTv) playFocus else null,
            // 控制条最右侧的「详情」：电视上这是唯一入口（顶部胶囊遥控器够不到）；
            // Pad 顶部那颗能点，这里不重复
            onDetail = if (isTv) onDetail else null,
            // 真实焦点状态回流：焦点在按钮行/浮层上时，←/→ 交给焦点移动，其他时候才是快进快退
            onChromeFocus = { chromeFocused = it },
        )

        // 提示条（发送结果之类）：控制条上方居中的小胶囊
        hint?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xE616161A))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(msg, color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

// ---------------- 下一集预取 ----------------

/**
 * 预取好的一集：档位表、解密后的样本表、已监听的本地代理，起播要的东西都在这里，
 * 切过去只差一句 setMediaItem。
 */
private class ReadyEpisode(
    val index: Int,
    val vid: String,
    val defWant: Int,
    val tiers: List<PlayTier>,
    val remote: RemoteVideo,
    val proxy: StreamProxy,
    val def: Int,
)

private fun dropReady(r: ReadyEpisode) {
    runCatching { r.proxy.stop() }
    runCatching { r.remote.close() }
}

/**
 * 把一集准备到「只差 setMediaItem」的程度。
 *
 * [alive] 在几个耗时的位置各查一次：用户中途切集或换清晰度时尽早收手，
 * 别让一个没人要的代理在后台继续吃带宽。
 */
private fun buildReady(
    index: Int,
    ep: Episode,
    defWant: Int,
    alive: () -> Boolean,
): ReadyEpisode? {
    if (!alive()) return null
    val ts = Api.tiers(ep.vid)
    if (!alive()) return null
    val cap = if (defWant > 0) defWant else Store.maxHeight
    val playable = ts.filter { it.playable }
    val picked = playable.firstOrNull { it.height == cap }
        ?: playable.firstOrNull { it.height <= cap }
        ?: playable.firstOrNull()
    val info = picked?.toPlayInfo() ?: Api.play(ep.vid, 1004, defWant)
    val rv = RemoteVideo(info)
    rv.prepare(warmLastBlock = false)
    if (!alive()) {
        rv.close()
        return null
    }
    val px = StreamProxy(rv)
    px.start()
    if (!alive()) {
        px.stop()
        rv.close()
        return null
    }
    return ReadyEpisode(index, ep.vid, defWant, ts, rv, px, info.height)
}

/**
 * 粗略判断一帧是不是全黑。
 *
 * 抓帧这条路有两种失败：PixelCopy 拿不到内容（硬件视频层取不到时给全黑），
 * 或者本来就播到黑场。全黑帧拿来当定格帧等于没顶住，反而更糟 —— 直接丢弃。
 */
private fun isMostlyBlack(bmp: android.graphics.Bitmap): Boolean {
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) return true
    val stepX = (w / 12).coerceAtLeast(1)
    val stepY = (h / 12).coerceAtLeast(1)
    var bright = 0
    var samples = 0
    var y = stepY
    while (y < h) {
        var x = stepX
        while (x < w) {
            val c = runCatching { bmp.getPixel(x, y) }.getOrDefault(0)
            val lum = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
            if (lum > 40) bright++
            samples++
            x += stepX
        }
        y += stepY
    }
    return samples == 0 || bright * 100 / samples < 3
}
