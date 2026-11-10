@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.kaixinguo.pc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.CropPortrait
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PersonalVideo
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hongguotv.pad.Api
import com.hongguotv.pad.DmItem
import com.hongguotv.pad.Episode
import com.hongguotv.pad.Log
import com.hongguotv.pad.PlayTier
import com.hongguotv.pad.RemoteVideo
import com.hongguotv.pad.StreamProxy
import com.hongguotv.pad.formatCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 播放页：解密 → 本地代理 → FFmpeg 解码 → 画面 + 声音。
 *
 * - 清晰度：列出 tiers 里的档位，选「自动」按设置上限
 * - 弹幕：按 vid 分窗拉取，按播放位置滚动播出，可开关
 * - 选集：抽屉自动滚到当前集
 * - 快捷键：空格 / ←→ / ↑↓ / Esc
 */
@Composable
fun PlayerScreen(
    seriesId: String,
    startIndex: Int,
    startMs: Long = 0L,
    onBack: () -> Unit,
    onDetail: () -> Unit,
    /** 右栏「评论」：旧版开单集评论面板，新版等价=详情页剧评 tab */
    onComments: () -> Unit = onDetail,
    embedded: Boolean = false,
    feedTab: Int? = null,
    onFeedTab: (Int) -> Unit = {},
    /** 刷剧（信息流）模式：滚轮上/下换剧。null = 普通播放页，滚轮=切集 */
    onWheelStep: ((Int) -> Unit)? = null,
    /** 系列剧 tab：点名跳到另一季/另一部 */
    onSwitchSeries: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val episodeGrid = rememberLazyGridState()

    var title by remember { mutableStateOf("") }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var index by remember { mutableStateOf(startIndex.coerceAtLeast(1)) }
    var engine by remember { mutableStateOf<VideoEngine?>(null) }
    var remote by remember { mutableStateOf<RemoteVideo?>(null) }
    var proxy by remember { mutableStateOf<StreamProxy?>(null) }
    var tiers by remember { mutableStateOf<List<PlayTier>>(emptyList()) }
    var quality by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    var volume by remember { mutableStateOf(PcStore.volumePref) }
    var muted by remember { mutableStateOf(PcStore.mutedPref) }
    var speed by remember { mutableStateOf(PcStore.playbackRate) }
    var tick by remember { mutableStateOf(0L) }
    var danmakuOn by remember { mutableStateOf(PcStore.danmakuEnabled) }
    var danmaku by remember { mutableStateOf<List<DmItem>>(emptyList()) }
    var dmCursor by remember { mutableStateOf("") }
    var dmMore by remember { mutableStateOf(false) }
    var dmText by remember { mutableStateOf("") }
    var volOpen by remember { mutableStateOf(false) }
    var volHovering by remember { mutableStateOf(false) }
    // ------- 单集评论面板（旧版 comment-panel）：右侧滑出，列表 + 发评论 + 回复 -------
    var commentOpen by remember { mutableStateOf(false) }
    var commentItems by remember { mutableStateOf<List<com.hongguotv.pad.EpisodeComment>>(emptyList()) }
    var commentCursor by remember { mutableStateOf("") }
    var commentMore by remember { mutableStateOf(false) }
    var commentTotal by remember { mutableStateOf(0) }
    var commentLoading by remember { mutableStateOf(false) }
    var commentText by remember { mutableStateOf("") }
    var commentSending by remember { mutableStateOf(false) }
    /** 展开回复的评论 id → 已加载回复 */
    val replies = remember { mutableStateMapOf<String, List<com.hongguotv.pad.CommentReply>>() }
    var replyTo by remember { mutableStateOf<com.hongguotv.pad.EpisodeComment?>(null) }
    /** 表情面板开关（弹幕/评论共用） */
    var emojiFor by remember { mutableStateOf<String?>(null) } // null/dm/comment
    // 控制栏浮层：主窗口内绘制（Popup 在 Desktop 上定位不可靠——实测 BottomEnd
    // 有时相对按钮、有时相对窗口）。popup 记当前打开的浮层，btnPos 记各按钮
    // 中心/右缘的窗口坐标，浮层按坐标绝对定位，100% 确定。
    var popup by remember { mutableStateOf<String?>(null) } // speed/quality/episodes/dmset
    val btnPos = remember { mutableStateMapOf<String, androidx.compose.ui.unit.IntOffset>() }
    // 按钮中心（窗口坐标）：遮罩代点击时判定「点在哪个按钮上」用，
    // 与 btnPos（锚点，给 OverlayAt 用）分离——episodes/dmset 锚点是右上角
    val btnCenter = remember { mutableStateMapOf<String, androidx.compose.ui.unit.IntOffset>() }
    fun anchor(name: String): androidx.compose.ui.unit.IntOffset? = btnPos[name]
    // 悬浮层显隐状态机（旧版 use-player-overlay）：移动/点击唤醒，静止 3 秒淡出；
    // 暂停时常驻；指针在控制栏/弹层上不倒计时
    var chromeVisible by remember { mutableStateOf(true) }
    var chromeHovered by remember { mutableStateOf(false) }
    var cursorVisible by remember { mutableStateOf(true) }
    var lastInputAt by remember { mutableStateOf(0L) }
    // 用户主动暂停（空格/点画面/控制栏按钮）。这里必须和「引擎还没出首帧」分开：
    // 新引擎从创建到首帧这段时间 playing 也是 false（v1.2.1 的首帧门控），
    // 以前把它当暂停 → 自动切下一集时控制栏会闪一下。
    var userPaused by remember { mutableStateOf(false) }
    // 引擎创建时刻：起播/换集后 6 秒内不算「暂停」，避免控制栏闪
    var engineReadyAt by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val now = System.currentTimeMillis()
            val idle = now - lastInputAt > 3_000
            val e = engine
            val starting = e != null && !e.playing && now - engineReadyAt < 6_000
            chromeVisible = !idle || chromeHovered || userPaused ||
                (e != null && !e.playing && !starting)
        }
    }
    fun chromeWake() {
        lastInputAt = System.currentTimeMillis()
        chromeVisible = true
    }
    // 评论面板加载（首屏 need_count 带回总数；翻页带回 cursor）
    fun loadComments(reset: Boolean) {
        val ep = episodes.getOrNull(index - 1) ?: return
        if (commentLoading) return
        commentLoading = true
        scope.launch {
            val page = withContext(Dispatchers.IO) {
                runCatching { Api.episodeComments(ep.vid, seriesId, if (reset) "" else commentCursor) }.getOrNull()
            }
            if (page != null) {
                commentItems = if (reset) page.items else (commentItems + page.items.filter { c -> commentItems.none { it.id == c.id } })
                commentCursor = page.cursor
                commentMore = page.hasMore
                if (page.total > 0) commentTotal = page.total
            }
            commentLoading = false
        }
    }
    // 切集后评论列表失效（面板开着就重拉第一页）
    LaunchedEffect(index) {
        commentItems = emptyList(); commentCursor = ""; commentMore = false; commentTotal = 0
        replies.clear(); replyTo = null
        if (commentOpen) loadComments(reset = true)
    }
    // 发评论 / 发回复（replyTo 非空 = 回复模式，发完就地内联追加）
    fun sendCommentOrReply() {
        val ep = episodes.getOrNull(index - 1) ?: return
        val txt = commentText.trim()
        if (txt.isBlank() || commentSending) return
        commentSending = true
        val target = replyTo
        scope.launch {
            if (target != null) {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { Api.sendCommentReply(ep.vid, seriesId, target.id, txt) }.getOrDefault(false)
                }
                if (ok) {
                    replies[target.id] = replies[target.id].orEmpty() + com.hongguotv.pad.CommentReply(
                        id = "local_${System.currentTimeMillis()}",
                        user = PcStore.accountName.ifBlank { "我" },
                        text = txt,
                        time = "刚刚",
                    )
                    commentText = ""
                    replyTo = null
                }
            } else {
                val added = withContext(Dispatchers.IO) {
                    runCatching { Api.sendEpisodeComment(ep.vid, seriesId, txt) }.getOrNull()
                }
                if (added != null) {
                    commentItems = listOf(added) + commentItems
                    commentTotal += 1
                    commentText = ""
                }
            }
            commentSending = false
        }
    }
    // 静音切换（旧版点击=静音，音量独立保存）：mask 代理与音量按钮共用
    fun muteToggle() {
        val next = !(muted || volume <= 0f)
        muted = next
        PcStore.mutedPref = next
        engine?.setVolume(if (next) 0f else if (volume <= 0f) 1f else volume)
    }
    // 滚轮切剧/切集：累积阈值 + 400ms 冷却（旧版 wheelLock 同口径）。
    // lastWheelTick=上次滚轮事件（>600ms 无滚动清零累积）；lastWheelAt=上次成功切换（冷却）
    var wheelAcc by remember { mutableStateOf(0f) }
    var lastWheelAt by remember { mutableStateOf(0L) }
    var lastWheelTick by remember { mutableStateOf(0L) }
    // 舞台像素高（弹幕字号/车道的自适应基准）
    var stageHpx by remember { mutableStateOf(0f) }
    var detail by remember { mutableStateOf<com.hongguotv.pad.SeriesDetail?>(null) }
    var reviewTotal by remember { mutableStateOf(0) }
    var introExpanded by remember { mutableStateOf(false) }
    var fav by remember { mutableStateOf(PcStore.contains("favorite", seriesId)) }
    var followed by remember { mutableStateOf(false) }
    var liked by remember { mutableStateOf(PcStore.contains("like", seriesId)) }

    // ------- 切集末帧定格（旧版 frame-hold）：切集瞬间抓上一集末帧钉住，
    // 新流首帧出来的一瞬间直接换掉（硬切，不做淡入淡出——淡出看着像残影；
    // 电视/平板就是这种硬切，肉眼察觉不到换集）-------
    var holdFrame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var holdVisible by remember { mutableStateOf(false) }
    // 注意：末帧抓取放在「按 index 拉流建引擎」的 effect 内部开头（见下），
    // 那里才是旧引擎还活着的时刻
    // 新引擎画出第一帧就立刻撤掉定格层，中间没有混合帧
    val firstNewFrame = engine?.frame
    LaunchedEffect(engine, firstNewFrame) {
        if (holdVisible && engine != null && firstNewFrame != null) holdVisible = false
    }
    // 兜底：新流一直没有画面（只有声音/失败）时 800ms 也得收掉，不能一直盖着
    LaunchedEffect(tick) {
        if (holdVisible) {
            kotlinx.coroutines.delay(800)
            holdVisible = false
        }
    }
    // 光标隐藏（旧版 cursor-none）：悬浮层收起且没开面板时，指针换成全透明光标
    LaunchedEffect(chromeVisible, commentOpen, popup != null || volOpen) {
        runCatching {
            val win = java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>().firstOrNull() ?: return@LaunchedEffect
            win.cursor = if (!chromeVisible && !commentOpen && popup == null && !volOpen) {
                val img = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                java.awt.Toolkit.getDefaultToolkit().createCustomCursor(img, java.awt.Point(0, 0), "blank")
            } else {
                java.awt.Cursor.getDefaultCursor()
            }
        }
    }

    LaunchedEffect(seriesId) {
        val d = withContext(Dispatchers.IO) { runCatching { Api.ensureDetail(seriesId) }.getOrNull() }
        if (d != null) {
            title = d.title
            episodes = d.episodes
            detail = d
            followed = d.followed
        }
        reviewTotal = withContext(Dispatchers.IO) {
            runCatching { Api.reviews(seriesId, 1) }.getOrNull()?.total ?: 0
        }
        runCatching { focus.requestFocus() }
    }

    // 起播 / 切集 / 换清晰度
    LaunchedEffect(index, episodes.size, quality) {
        val ep = episodes.getOrNull(index - 1) ?: return@LaunchedEffect
        // 末帧定格：此处旧引擎还在（下面才 release），先把它的末帧钉住
        if (engine?.frame != null) {
            holdFrame = engine?.frame
            holdVisible = true
        }
        loading = true
        failed = null
        engine?.release()
        engine = null
        runCatching { proxy?.stop() }
        runCatching { remote?.close() }
        proxy = null
        remote = null
        danmaku = emptyList()
        dmCursor = ""
        dmMore = false

        // 只用「自动」档的预取（指定清晰度时档位可能不同，别硬套）
        val warmRaw = if (quality == 0) PlaybackPrefetch.take(ep.vid) else null
        val warm = warmRaw?.takeIf { it.height <= PcStore.maxHeight }
        if (warmRaw != null && warm == null) warmRaw.release()
        if (warm != null) {
            Log.d("Prefetch", "命中第 $index 集预取（直链" + if (warm.proxy != null) " + 头部）" else "）")
        }

        val built = withContext(Dispatchers.IO) {
            runCatching {
                // 预取命中：直链（和下一集的头部）都是现成的，切集直接接管
                if (warm != null) {
                    tiers = warm.tiers
                    val rv = warm.remote
                    val px = warm.proxy
                    if (rv != null && px != null) {
                        Triple(rv, px, warm.info)
                    } else {
                        val fresh = RemoteVideo(warm.info)
                        fresh.prepare()
                        val p2 = StreamProxy(fresh)
                        p2.start()
                        Triple(fresh, p2, warm.info)
                    }
                } else {
                    val list = runCatching { Api.tiers(ep.vid) }.getOrDefault(emptyList())
                    tiers = list
                    val picked = if (quality > 0) list.firstOrNull { it.height == quality }
                    else list.filter { it.height <= PcStore.maxHeight }.maxByOrNull { it.height }
                        ?: list.maxByOrNull { it.height }
                    val info = picked?.toPlayInfo() ?: Api.play(ep.vid, 1004, PcStore.maxHeight)
                    val rv = RemoteVideo(info)
                    rv.prepare()
                    val px = StreamProxy(rv)
                    px.start()
                    Triple(rv, px, info)
                }
            }.getOrNull()
        }
        if (built == null) {
            warm?.release()
            loading = false
            failed = "本版不内置取流服务：请在「设置 → 服务配置 → 取流服务」填写地址"
            return@LaunchedEffect
        }
        remote = built.first
        proxy = built.second

        val eng = VideoEngine(built.second.url)
        engine = eng
        // 换集/起播：新引擎从 0 开始播，暂停状态和「启动宽限」一起重置
        userPaused = false
        engineReadyAt = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            runCatching { eng.probe() }
            eng.setVolume(if (muted) 0f else volume)
            eng.setSpeed(speed)
            // 首次进入这一集：优先用调用方给的位置（历史点进来），其次本地进度
            val resume = if (index == startIndex && startMs > 0) startMs
            else PcStore.posOf(ep.vid).takeIf { it > 3_000 } ?: 0L
            eng.start(resume)
            PcStore.record(seriesId, title, "", index, episodes.size, positionMs = resume)
        }
        eng.onEnded = {
            // 自然播完到下一集之间也有一个极短的「旧引擎已停、新引擎未建」窗口，
            // 把启动宽限从这里就开始算，避免 500ms 轮询刚好落进去把控制栏点亮
            engineReadyAt = System.currentTimeMillis()
            if (index < episodes.size) {
                if (PcStore.autoNextEpisode) index += 1
            } else if (PcStore.deleteAfterFinish) {
                // 旧版「看完自动删除」：最后一集播完把这条历史抹掉
                PcStore.removeHistory(seriesId)
            }
        }
        loading = false

        if (danmakuOn) {
            val dur = eng.info?.durationMs ?: 0L
            val win = withContext(Dispatchers.IO) {
                runCatching { Api.danmakuPage(ep.vid, seriesId, 0, dur, "") }.getOrNull()
            }
            if (win != null) {
                danmaku = win.items
                dmCursor = win.cursor
                dmMore = win.hasMore
            }
        }
    }

    // 连播预取：起播后按设置往后预取 N 集（0 = 关闭）
    LaunchedEffect(index, episodes.size, PcStore.prefetchEpisodes, PcStore.maxHeight) {
        val n = PcStore.prefetchEpisodes
        if (n <= 0 || episodes.isEmpty() || index >= episodes.size) {
            PlaybackPrefetch.retain(emptySet())
            return@LaunchedEffect
        }
        // 等这一集真正播起来再预取，别抢起播带宽
        while (loading) delay(300)
        PlaybackPrefetch.warm(
            episodes = episodes,
            fromIndex = index + 1,
            count = n,
            maxHeight = PcStore.maxHeight,
            prepareHeadFor = index + 1,
        )
    }

    // 位置轮询 + 弹幕续窗
    LaunchedEffect(engine) {
        while (true) {
            val e = engine
            if (e != null) {
                tick = e.positionMs
                if (dmMore && danmakuOn && danmaku.isNotEmpty() && tick > danmaku.last().offsetMs - 2_000) {
                    dmMore = false
                    val ep = episodes.getOrNull(index - 1)
                    if (ep != null) {
                        val next = withContext(Dispatchers.IO) {
                            runCatching {
                                Api.danmakuPage(ep.vid, seriesId, tick, e.info?.durationMs ?: 0L, dmCursor)
                            }.getOrNull()
                        }
                        if (next != null && next.items.isNotEmpty()) {
                            val known = danmaku.map { it.id }.toSet()
                            danmaku = danmaku + next.items.filter { it.id !in known }
                            dmCursor = next.cursor
                            dmMore = next.hasMore
                        }
                    }
                }
            }
            delay(250)
        }
    }

    // 进度落盘 + 历史上报（节拍对齐旧版 history-cadence）：
    // 落盘 5 秒节流；上报首报 5 秒、同集每 60 秒一条，暂停不计时
    LaunchedEffect(engine, index) {
        val ep = episodes.getOrNull(index - 1) ?: return@LaunchedEffect
        var lastSaved = 0L
        var lastReport = 0L
        while (true) {
            delay(1_000)
            val e = engine ?: continue
            val playing = e.playing
            val pos = e.positionMs
            if (playing && pos > 0 && pos - lastSaved >= 5_000) {
                lastSaved = pos
                PcStore.markPos(ep.vid, pos)
            }
            if (playing && pos > 5_000 && pos - lastReport >= 60_000) {
                lastReport = pos
                withContext(Dispatchers.IO) { runCatching { Api.reportProgress(seriesId, ep.vid, index, pos) } }
            }
        }
    }

    // 窗口最小化 → 暂停（设置可关）
    LaunchedEffect(engine) {
        while (true) {
            if (AppLifecycle.inBackground.value && PcStore.pauseOnMinimize) engine?.pause()
            delay(500)
        }
    }

    // 选集浮层滚到当前集
    LaunchedEffect(popup, index) {
        if (popup == "episodes" && episodes.isNotEmpty()) {
            val row = (index - 1).coerceAtLeast(0) / 4
            runCatching { episodeGrid.scrollToItem(row * 4) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 预取池整批释放：代理端口、socket、1MB 头部缓存都不留给后台
            PlaybackPrefetch.clear()
            runCatching { engine?.release() }
            runCatching { proxy?.stop() }
            runCatching { remote?.close() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { stageHpx = it.height.toFloat() }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Spacebar -> { chromeWake(); engine?.let { if (it.playing) { it.pause(); userPaused = true } else { it.resume(); userPaused = false } }; true }
                    Key.DirectionLeft -> { chromeWake(); engine?.seekTo((engine?.positionMs ?: 0) - 5_000); true }
                    Key.DirectionRight -> { chromeWake(); engine?.seekTo((engine?.positionMs ?: 0) + 5_000); true }
                    // 刷剧页面（onWheelStep 非空）：上/下 = 上一部 / 下一部剧，和滚轮同语义。
                    // 只有从详情进来看完整短剧时才是切上一集/下一集。
                    Key.DirectionUp -> {
                        if (onWheelStep != null) onWheelStep.invoke(-1) else if (index > 1) index -= 1
                        true
                    }
                    Key.DirectionDown -> {
                        if (onWheelStep != null) onWheelStep.invoke(1) else if (index < episodes.size) index += 1
                        true
                    }
                    Key.Escape -> {
                        if (popup != null || volOpen) { popup = null; volOpen = false } else onBack()
                        true
                    }
                    else -> false
                }
            }
            // 旧版舞台交互：移动/按下=唤醒 3 秒倒计时；滚轮=切剧(刷剧)/切集(播放页)；
            // 单击画面=播放/暂停；悬浮层收起后光标跟着藏（cursor-none）
            .onPointerEvent(PointerEventType.Move) { chromeWake(); cursorVisible = true }
            .onPointerEvent(PointerEventType.Press) { chromeWake(); cursorVisible = true }
            .onPointerEvent(PointerEventType.Scroll) { ev ->
                // 旧版 use-player-interactions 同口径：400ms 冷却 + 累积阈值；
                // 浮层（Popup 独立窗口）不会把滚动冒泡到这里，无需 data-wheel-block
                chromeWake()
                val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy == 0f) return@onPointerEvent
                val now = System.currentTimeMillis()
                if (now - lastWheelTick > 600) wheelAcc = 0f
                lastWheelTick = now
                wheelAcc += dy
                if (kotlin.math.abs(wheelAcc) < 1.5f) return@onPointerEvent
                val dir = if (wheelAcc > 0) 1 else -1
                wheelAcc = 0f
                if (now - lastWheelAt < 400) return@onPointerEvent
                lastWheelAt = now
                val step = { d: Int ->
                    if (d > 0 && index < episodes.size) index += 1
                    else if (d < 0 && index > 1) index -= 1
                }
                if (onWheelStep != null) onWheelStep.invoke(dir) else step(dir)
            }
            .onPointerEvent(PointerEventType.Enter) { chromeVisible = true }
            .onPointerEvent(PointerEventType.Exit) { chromeVisible = false; cursorVisible = true }
            .pointerHoverIcon(PointerIcon.Default),
    ) {
        // 单击画面 = 播放/暂停（旧版 onStageClick）。选集改走控制栏按钮，
        // 不再是点画面（旧版点画面从来不是开选集）。
        // hover 移动唤醒 chrome：Compose Desktop 的 onPointerEvent(Move) 对
        // 纯悬停不派发（实测静默），必须用 pointerInput 事件循环才能收到。
        var clickAt by remember { mutableStateOf(0L) }
        Box(
            Modifier.fillMaxSize()
                .clickable {
                    val now = System.currentTimeMillis()
                    if (now - clickAt < 350) return@clickable   // 连击/误触冷却（旧版 picker cooldown 同思路）
                    clickAt = now
                    chromeWake()
                    engine?.let { if (it.playing) { it.pause(); userPaused = true } else { it.resume(); userPaused = false } }
                }
                .pointerInput(Unit) {
                    // 纯 hover 的 Move 事件泵：onPointerEvent(Move) 在 Desktop 对
                    // 纯悬停不派发、awaitEachGesture 要等首次按下，只有
                    // awaitPointerEventScope 无限泵能稳定收到悬停移动
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Move) chromeWake()
                        }
                    }
                },
        )
        engine?.frame?.let { bmp ->
            Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        // 切集末帧定格层（盖在画面之上、弹幕之下）。
        // 画的条件直接带「新引擎还没有帧」：首帧一进 state 就同帧撤掉，
        // 不等 LaunchedEffect 下一轮，避免那一帧的叠加被看成残影。
        holdFrame?.takeIf { holdVisible && engine?.frame == null }?.let { bmp ->
            Image(
                bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }

        // 弹幕层（旧版 danmaku-layer 同构）：每条 = 车道上的横穿飞行体，
        // 9 秒线性滚过全屏；车道按显示区域切分，同车道 1.4 秒占位防叠。
        if (danmakuOn && danmaku.isNotEmpty() && stageHpx > 0f) {
            DanmakuLayer(
                items = danmaku,
                positionMs = tick,
                playing = engine?.playing != false,
                stageHeightPx = stageHpx,
                opacity = PcStore.danmakuOpacity,
                fontScale = PcStore.danmakuFontScale,
                density = PcStore.danmakuDensity,
                area = PcStore.danmakuArea,
                modifier = Modifier.matchParentSize(),
            )
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Palette.accent, strokeWidth = 3.dp, modifier = Modifier.width(40.dp).height(40.dp),
                )
            }
        }
        failed?.let { msg ->
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(msg, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                SecondaryButton("返回", onClick = onBack)
            }
        }

        Row(
            Modifier.fillMaxWidth()
                .graphicsLayer { alpha = if (chromeVisible) 1f else 0f }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .onPointerEvent(PointerEventType.Enter) { chromeHovered = true }
                .onPointerEvent(PointerEventType.Exit) { chromeHovered = false },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackPill("第 $index 集") { onBack() }
            Spacer(Modifier.weight(1f))
            if (feedTab != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("漫剧", "真人剧", "推荐").forEachIndexed { i, label ->
                        FeedTab(label, feedTab == i) { onFeedTab(i) }
                    }
                }
                Spacer(Modifier.weight(1f))
            }
        }

        // 「观看完整短剧 · 全 N 集 ›」：刷剧页里进详情的显性入口（旧版顶部胶囊）
        if (feedTab != null) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 44.dp)
                    .graphicsLayer { alpha = if (chromeVisible) 1f else 0f },
                horizontalArrangement = Arrangement.Center,
            ) {
                Row(
                    Modifier.height(30.dp).clip(RoundedCornerShape(50))
                        .background(Color(0x99000000))
                        .onPointerEvent(PointerEventType.Enter) { chromeHovered = true }
                        .onPointerEvent(PointerEventType.Exit) { chromeHovered = false }
                        .clickable { onDetail() }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "观看完整短剧 · 全 ${detail?.episodeCount ?: episodes.size} 集",
                        color = Color.White.copy(alpha = 0.92f), fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                }
            }
        }

        // 右侧互动栏：收藏 → 评论 → 点赞 → 追更（旧版抖音式竖排，随 chrome 淡出）
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 96.dp)
                .graphicsLayer { alpha = if (chromeVisible) 1f else 0f }
                .onPointerEvent(PointerEventType.Enter) { chromeHovered = true }
                .onPointerEvent(PointerEventType.Exit) { chromeHovered = false },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RailAction(
                Icons.Filled.Star,
                detail?.followedCount?.takeIf { it.isNotBlank() }?.let { formatCount(it) } ?: "",
                "收藏",
                fav,
                onClick = {
                    val target = !fav
                    scope.launch(Dispatchers.IO) {
                        runCatching { Api.collectSeries(seriesId, target) }
                        if (PcStore.contains("favorite", seriesId) != target) PcStore.toggle("favorite", seriesId)
                        withContext(Dispatchers.Main) { fav = target }
                    }
                },
                activeColor = Palette.star,
            )
            // 评论：打开右侧单集评论面板（旧版 comment-panel 同构）
            RailAction(
                Icons.Filled.Comment,
                if (commentTotal > 0) "$commentTotal" else (if (reviewTotal > 0) "$reviewTotal" else ""),
                "评论",
                false,
                onClick = {
                    commentOpen = true
                    if (commentItems.isEmpty()) loadComments(reset = true)
                },
            )
            RailAction(
                Icons.Filled.Favorite,
                PcStore.ids("like").size.takeIf { it > 0 }?.toString() ?: "",
                "点赞",
                liked,
                onClick = {
                    val target = !liked
                    val vid = episodes.getOrNull(index - 1)?.vid.orEmpty()
                    scope.launch(Dispatchers.IO) {
                        if (vid.isNotBlank()) runCatching { Api.diggVideo(vid, seriesId, target) }
                        if (PcStore.contains("like", seriesId) != target) PcStore.toggle("like", seriesId)
                        withContext(Dispatchers.Main) { liked = target }
                    }
                },
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .graphicsLayer { alpha = if (chromeVisible) 1f else 0f }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .onPointerEvent(PointerEventType.Enter) { chromeHovered = true }
                .onPointerEvent(PointerEventType.Exit) { chromeHovered = false },
        ) {
            // 左下：@剧名 / 第N集 / 简介两行（数值对齐旧版 immersive-info-overlay）
            val intro = detail?.intro.orEmpty()
            val seriesTitle = title.ifBlank { "播放" }
            Column(Modifier.widthIn(max = 620.dp).padding(end = 80.dp)) {
                // 标题区（@剧名 + 第N集）点一下进详情：旧版就有这个入口，
                // 重写播放器时只留了刷剧页那颗胶囊，正片里的入口漏了
                Column(
                    Modifier.pointerHoverIcon(PointerIcon.Hand)
                        .clickable(enabled = chromeVisible) { onDetail() },
                ) {
                    Text(
                        "@$seriesTitle",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "第 $index 集" + if (episodes.isNotEmpty()) " · 全 ${detail?.episodeCount ?: episodes.size} 集" else "",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                    )
                }
                if (intro.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.widthIn(max = 420.dp), verticalAlignment = Alignment.Bottom) {
                        Text(
                            intro,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            maxLines = if (introExpanded) 6 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (intro.length > 40) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (introExpanded) "收起" else "展开",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { introExpanded = !introExpanded },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (engine?.playing != true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text(
                        "空格播放/暂停 · ←→ 快进快退 5 秒 · ↑↓ 切集 · Esc 退出选集",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x8C000000))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            val duration = engine?.info?.durationMs ?: 0L
            ScrubBar(tick, duration) { ratio -> engine?.seekTo((ratio * duration).toLong()) }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CtrlIcon(if (engine?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow) {
                    engine?.let { if (it.playing) { it.pause(); userPaused = true } else { it.resume(); userPaused = false } }
                }
                CtrlIcon(Icons.Filled.SkipPrevious, enabled = index > 1) { if (index > 1) index -= 1 }
                CtrlIcon(Icons.Filled.SkipNext, enabled = index < episodes.size) { if (index < episodes.size) index += 1 }
                Spacer(Modifier.width(4.dp))
                Text(
                    "${fmtPlayerTime(tick)} / ${fmtPlayerTime(duration)}",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                // 弹幕发送框（旧版 EmojiSendBox 皮肤）：胶囊白/15 底，右侧红底「发送」胶囊；
                // 表情按钮弹出 emoji 宫格（旧版 [名字] 代码的轻量等价）
                Box {
                    Row(
                        Modifier.width(240.dp).height(32.dp).clip(RoundedCornerShape(50))
                            .background(Color(0x26FFFFFF)).padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (dmText.isEmpty()) Text("发条弹幕…", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
                            BasicTextField(
                                value = dmText,
                                onValueChange = { dmText = it.take(100) },
                                textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                                cursorBrush = SolidColor(Color.White),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .clickable { emojiFor = if (emojiFor == "dm") null else "dm" }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) { Text("😊", fontSize = 14.sp) }
                        // 发送 = 红底胶囊（旧版 sendClassName 皮肤）
                        Row(
                            Modifier.height(24.dp).clip(RoundedCornerShape(50))
                                .background(Palette.accentFill)
                                .clickable {
                                    val ep = episodes.getOrNull(index - 1)
                                    val text = dmText.trim()
                                    if (ep != null && text.isNotEmpty()) {
                                        dmText = ""
                                        scope.launch(Dispatchers.IO) { runCatching { Api.sendDanmaku(ep.vid, seriesId, text, tick) } }
                                    }
                                }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("发送", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (emojiFor == "dm") {
                        Box(Modifier.align(Alignment.TopStart).offset(y = (-196).dp).width(280.dp)) {
                            EmojiPanel { e -> dmText = (dmText + e).take(100); emojiFor = null }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.onGloballyPositioned { val p = it.positionInWindow(); val c = androidx.compose.ui.unit.IntOffset((p.x + it.size.width / 2).toInt(), (p.y + it.size.height / 2).toInt()); btnPos["speed"] = c; btnCenter["speed"] = c }) {
                    SpeedButton(speed, popup == "speed") { popup = if (popup == "speed") null else "speed" }
                }
                Box(Modifier.onGloballyPositioned { val p = it.positionInWindow(); val c = androidx.compose.ui.unit.IntOffset((p.x + it.size.width / 2).toInt(), (p.y + it.size.height / 2).toInt()); btnPos["quality"] = c; btnCenter["quality"] = c }) {
                    QualityButton(quality, tiers, engine?.info?.height ?: 0, popup == "quality", onToggle = { popup = if (popup == "quality") null else "quality" }, onPick = { q -> popup = null; quality = q })
                }
                Box(Modifier.onGloballyPositioned { val p = it.positionInWindow(); btnPos["episodes"] = androidx.compose.ui.unit.IntOffset((p.x + it.size.width).toInt(), p.y.toInt()); btnCenter["episodes"] = androidx.compose.ui.unit.IntOffset((p.x + it.size.width / 2).toInt(), (p.y + it.size.height / 2).toInt()) }) {
                    CtrlText(Icons.AutoMirrored.Filled.List, "选集·全${episodes.size}集") { popup = if (popup == "episodes") null else "episodes" }
                }
                // 下载本集（旧版 download-sheet 的单文件等价形态）
                CtrlText(Icons.Filled.FileDownload, "下载") {
                    val ep = episodes.getOrNull(index - 1)
                    val best = tiers.filter { it.height <= PcStore.maxHeight }.maxByOrNull { it.height }
                        ?: tiers.maxByOrNull { it.height }
                    if (ep != null && best != null) {
                        Downloader.download(title.ifBlank { seriesId }, ep.index, best.url, engine?.info?.durationMs ?: 0L)
                        chromeWake()
                    }
                }
                // 弹幕开关：「弹」字按钮，关闭叠反斜杠（B 站同款，旧版 DanmakuSwitchIcon）
                DanmakuSwitchButton(danmakuOn) { danmakuOn = !danmakuOn; PcStore.danmakuEnabled = !danmakuOn }
                // 弹幕设置：「弹」+ 齿轮角标 → 上方面板
                Box(Modifier.onGloballyPositioned { val p = it.positionInWindow(); btnPos["dmset"] = androidx.compose.ui.unit.IntOffset((p.x + it.size.width).toInt(), p.y.toInt()); btnCenter["dmset"] = androidx.compose.ui.unit.IntOffset((p.x + it.size.width / 2).toInt(), (p.y + it.size.height / 2).toInt()) }) {
                    DanmakuSettingsButton(popup == "dmset") { popup = if (popup == "dmset") null else "dmset" }
                }
                // 音量：点击=静音切换，悬停=竖条浮层（旧版 volume-popup 交互语义）；
                // 移出按钮 150ms 内没进浮层就收（按钮→浮层隔 6px 间隙，直接收会截断 hover）
                Box(
                    Modifier
                        .onGloballyPositioned { val p = it.positionInWindow(); val c = androidx.compose.ui.unit.IntOffset((p.x + it.size.width / 2).toInt(), (p.y + it.size.height / 2).toInt()); btnPos["volume"] = c; btnCenter["volume"] = c }
                        .onPointerEvent(PointerEventType.Enter) { volOpen = true }
                        .onPointerEvent(PointerEventType.Exit) {
                            scope.launch { delay(150); if (!volHovering) volOpen = false }
                        },
                ) {
                    CtrlIcon(
                        if (muted || volume <= 0f) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        selected = volOpen,
                    ) { muteToggle() }
                }
                // 竖屏：窗口收成 9:16 竖条（旧版 portrait 入口；退出走同一个钮）
                CtrlIcon(
                    Icons.Filled.CropPortrait,
                    selected = UiMode.portrait.value,
                ) {
                    UiMode.mini.value = false
                    UiMode.portrait.value = !UiMode.portrait.value
                }
                // 小窗：整窗缩成 520×300，侧栏顶栏全藏（旧版 miniScreen）
                CtrlIcon(
                    Icons.Filled.PictureInPictureAlt,
                    selected = UiMode.mini.value,
                ) {
                    UiMode.portrait.value = false
                    UiMode.mini.value = !UiMode.mini.value
                }
                CtrlIcon(Icons.Filled.Fullscreen) { toggleWindowFullscreen() }
            }
        }

        // ------- 控制栏浮层（主窗口内绘制，坐标定位；遮罩点外部全关） -------
        val anyOverlay = popup != null || volOpen
        if (anyOverlay) {
            // 遮罩在 stage 最顶层：盖住控制栏按钮。按钮收到不到点击（命中被遮罩截断），
            // 所以「点在按钮上」由遮罩代为执行按钮语义（toggle/静音）；点外部=全关浮层。
            // 坐标系：ev.position 是遮罩局部坐标，btnPos 是窗口坐标，必须加回遮罩原点。
            var maskOrigin by remember { mutableStateOf(androidx.compose.ui.unit.IntOffset.Zero) }
            Box(
                Modifier.matchParentSize()
                    .onGloballyPositioned {
                        val p = it.positionInWindow()
                        maskOrigin = androidx.compose.ui.unit.IntOffset(p.x.toInt(), p.y.toInt())
                    }
                    .onPointerEvent(PointerEventType.Press) { ev ->
                        val p = ev.changes.firstOrNull()?.position ?: return@onPointerEvent
                        val wx = p.x + maskOrigin.x
                        val wy = p.y + maskOrigin.y
                        // 半径 24：按钮半宽 ~16 + 余量；相邻可代理按钮中心最小间距 49
                        // （dmset↔volume），24×2=48 < 49 判定圈不重叠
                        val hit: String? = listOf("speed", "quality", "episodes", "dmset", "volume").firstOrNull { name ->
                            val c = btnCenter[name] ?: return@firstOrNull false
                            kotlin.math.abs(wx - c.x) < 24 && kotlin.math.abs(wy - c.y) < 24
                        }
                        when (hit) {
                            "speed" -> popup = if (popup == "speed") null else "speed"
                            "quality" -> popup = if (popup == "quality") null else "quality"
                            "episodes" -> popup = if (popup == "episodes") null else "episodes"
                            "dmset" -> popup = if (popup == "dmset") null else "dmset"
                            "volume" -> muteToggle()
                            else -> { popup = null; volOpen = false }
                        }
                    },
            )
            if (popup == "speed") {
                OverlayAt(anchor("speed"), w = 132, h = 244, centered = true) {
                    UpMenuCard {
                        MenuTitle("倍速")
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { s ->
                            MenuRow(speedLabel(s), speed == s) { popup = null; speed = s; PcStore.playbackRate = s; engine?.setSpeed(s) }
                        }
                    }
                }
            }
            if (popup == "quality") {
                OverlayAt(anchor("quality"), w = 190, h = 240, centered = true) {
                    UpMenuCard {
                        MenuTitle("清晰度")
                        MenuRow("自动（上限 ${PcStore.maxHeight}P）", quality == 0) { popup = null; quality = 0 }
                        tiers.sortedByDescending { it.height }.forEach { t ->
                            MenuRow("${t.height}P" + if (t.codec.isNotBlank()) " · ${t.codec}" else "", quality == t.height) {
                                popup = null; quality = t.height
                            }
                        }
                    }
                }
            }
            if (popup == "dmset") {
                OverlayAt(anchor("dmset"), w = 252, h = 264, centered = false) {
                    DanmakuSettingsPanel()
                }
            }
            if (popup == "episodes") {
                OverlayAt(anchor("episodes"), w = 460, h = 470, centered = false) {
                    EpisodePickerPopup(
                        seriesId = seriesId,
                        currentIndex = index,
                        episodes = episodes,
                        gridState = episodeGrid,
                        onSelect = { index = it },
                        onSwitchSeries = { popup = null; onSwitchSeries(it) },
                        onClose = { popup = null },
                    )
                }
            }
            if (volOpen) {
                OverlayAt(anchor("volume"), w = 56, h = 150, centered = true) {
                    VolumePopup(
                        if (muted) 0f else volume,
                        onHover = { volHovering = it },
                        onClose = { volOpen = false },
                    ) {
                        volume = it
                        muted = it <= 0f
                        PcStore.volumePref = if (it <= 0f) volume else it
                        PcStore.mutedPref = it <= 0f
                        engine?.setVolume(it)
                    }
                }
            }
        }

        // ------- 单集评论面板（旧版 comment-panel：右侧滑出，评论列表 + 底部发送框） -------
        if (commentOpen) {
            Column(
                Modifier.align(Alignment.CenterEnd).width(380.dp).fillMaxHeight()
                    .background(Color(0xF20B0B0D))
                    .border(1.dp, Color(0x14FFFFFF)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "评论" + if (commentTotal > 0) " · $commentTotal" else "",
                        color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { commentOpen = false }.padding(6.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Palette.dim, modifier = Modifier.size(16.dp))
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        commentItems.isEmpty() && commentLoading -> CenterLoading()
                        commentItems.isEmpty() -> Text(
                            "还没有评论，来说两句",
                            color = Palette.dim, fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        else -> androidx.compose.foundation.lazy.LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            items(commentItems.size) { i ->
                                val c = commentItems[i]
                                CommentRow(
                                    c = c,
                                    replies = replies[c.id],
                                    onLoadReplies = {
                                        scope.launch {
                                            val list = withContext(Dispatchers.IO) {
                                                runCatching { Api.commentReplies(c.id) }.getOrDefault(emptyList())
                                            }
                                            replies[c.id] = list
                                        }
                                    },
                                    onReply = { replyTo = c },
                                )
                            }
                            if (commentMore) {
                                item {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
                                        SecondaryButton(if (commentLoading) "加载中…" else "加载更多") { loadComments(reset = false) }
                                    }
                                }
                            }
                        }
                    }
                    // 表情面板（评论）
                    if (emojiFor == "comment") {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 64.dp)) {
                            EmojiPanel { e -> commentText = (commentText + e).take(200); emojiFor = null }
                        }
                    }
                }
                // 底部发送区
                Column(Modifier.fillMaxWidth().background(Color(0xFF141014)).padding(12.dp)) {
                    replyTo?.let { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("回复 @${r.user}", color = Palette.accent, fontSize = 11.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("取消", color = Palette.faint, fontSize = 11.sp, modifier = Modifier.clickable { replyTo = null })
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(50))
                                .background(Color(0x26FFFFFF)).padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (commentText.isEmpty()) Text("说点什么…", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
                                BasicTextField(
                                    value = commentText,
                                    onValueChange = { commentText = it.take(200) },
                                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                                    cursorBrush = SolidColor(Color.White),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Box(Modifier.clip(RoundedCornerShape(50)).clickable { emojiFor = if (emojiFor == "comment") null else "comment" }.padding(horizontal = 6.dp, vertical = 4.dp)) {
                                Text("😊", fontSize = 14.sp)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        val canSend = commentText.isNotBlank() && !commentSending
                        Text(
                            "发送",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (canSend) Palette.accentFill else Palette.panel)
                                .clickable(enabled = canSend) { sendCommentOrReply() }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 清晰度切换（旧版）：MonitorPlay 图标 + 档位/「自动」；浮层由 stage 统一绘制。 */
@Composable
private fun QualityButton(current: Int, tiers: List<PlayTier>, actual: Int, open: Boolean, onToggle: () -> Unit, onPick: (Int) -> Unit) {
    CtrlText(
        Icons.Filled.PersonalVideo,
        if (current > 0) "${current}P" else "自动",
        onClick = onToggle,
    )
}

/** 倍速切换（旧版）：Gauge 图标 + 当前倍速（mono）；浮层由 stage 统一绘制。 */
@Composable
private fun SpeedButton(current: Float, open: Boolean, onToggle: () -> Unit) {
    CtrlText(Icons.Filled.Speed, speedLabel(current), mono = true, onClick = onToggle)
}

private fun speedLabel(s: Float): String =
    if (s == s.roundToInt().toFloat()) "${s.roundToInt()}x" else "${s}x"

/**
 * 控制栏浮层定位器：以按钮锚点为基准，把浮层内容放到按钮正上方
 * （centered=水平居中，否则右缘对齐按钮右缘——选集/设置面板 460/252 宽）。
 * 全部主窗口内绘制 + offset 绝对定位，规避 Desktop Popup 的对齐玄学。
 */
@Composable
private fun OverlayAt(
    anchor: androidx.compose.ui.unit.IntOffset?,
    w: Int,
    h: Int,
    centered: Boolean,
    content: @Composable () -> Unit,
) {
    if (anchor == null) return
    val d = androidx.compose.ui.platform.LocalDensity.current
    // anchor 是窗口坐标（positionInWindow），offset 是相对本 Box（stage 内 fillMaxSize）
    // 的坐标——必须先减去本 Box 的窗口原点，否则浮层整体偏右 72px（侧栏）偏下 44px（顶栏）
    var self by remember { mutableStateOf<androidx.compose.ui.unit.IntOffset?>(null) }
    Box(Modifier.fillMaxSize().onGloballyPositioned {
        val p = it.positionInWindow()
        self = androidx.compose.ui.unit.IntOffset(p.x.toInt(), p.y.toInt())
    }) {
        Column(
            Modifier
                .offset {
                    val s = self ?: return@offset androidx.compose.ui.unit.IntOffset.Zero
                    val x = if (centered) anchor.x - s.x - (w * d.density).toInt() / 2 else anchor.x - s.x - (w * d.density).toInt()
                    val y = anchor.y - s.y - (h * d.density).toInt() - (6 * d.density).toInt()
                    androidx.compose.ui.unit.IntOffset(x.coerceAtLeast(8), y.coerceAtLeast(8))
                }
                .width(w.dp),
        ) { content() }
    }
}

/** 控制栏向上弹的深色菜单卡（旧版 bottom-full 浮层同语言）。 */
@Composable
private fun UpMenuCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.widthIn(min = 132.dp).clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6101014))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
            .padding(vertical = 5.dp),
        content = content,
    )
}

/** 上弹菜单的标题行（旧版 DropdownMenuLabel：「倍速」「清晰度」）。 */
@Composable
private fun MenuTitle(label: String) {
    Text(
        label,
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
    )
}

/** 上弹菜单的选项行：右端 ✓ 标记当前项。 */
@Composable
private fun MenuRow(label: String, active: Boolean, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }
            .background(if (active) Color(0x26FFFFFF) else if (hovered) Color(0x1AFFFFFF) else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (active) Color.White else Color.White.copy(alpha = 0.82f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.weight(1f))
        if (active) Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(13.dp))
    }
}

/** 「弹」字方框 + 右下齿轮角标 → 上方设置面板（透明度/字号/密度/显示区域）。 */
@Composable
private fun DanmakuSettingsButton(open: Boolean, onToggle: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hovered || open) Color(0x33FFFFFF) else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onToggle() },
    ) {
        Text(
            "弹",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
        )
        Box(
            Modifier.align(Alignment.BottomEnd)
                .size(9.dp).clip(RoundedCornerShape(50)).background(Color(0xB3000000)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(7.dp),
            )
        }
    }
}

/** 弹幕设置面板：透明度 / 字号 / 密度 / 显示区域（旧版 playback-prefs 同款四项）。 */
@Composable
private fun DanmakuSettingsPanel() {
    Column(
        Modifier.width(252.dp).clip(RoundedCornerShape(12.dp))
            .background(Color(0xE6101014))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("弹幕设置", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        DmSlider("透明度", PcStore.danmakuOpacity, 0.1f, 1f) { PcStore.danmakuOpacity = it }
        DmSlider("字号", PcStore.danmakuFontScale, 0.5f, 2f) { PcStore.danmakuFontScale = it }
        DmSlider("密度", PcStore.danmakuDensity, 0f, 1f) { PcStore.danmakuDensity = it }
        DmSlider("显示区域", PcStore.danmakuArea, 0.25f, 1f) { PcStore.danmakuArea = it }
    }
}

@Composable
private fun DmSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (label == "字号") "${"%.1f".format(value)}x" else "${(value * 100).roundToInt()}%",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        androidx.compose.material3.Slider(
            value = value.coerceIn(min, max),
            onValueChange = { onChange(it) },
            valueRange = min..max,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Palette.accent,
                inactiveTrackColor = Color(0x33FFFFFF),
            ),
            modifier = Modifier.height(22.dp),
        )
    }
}

/**
 * 选集浮层（旧版 episode-picker 同构）：「选集 | 系列剧」双 tab。
 * 选集 tab = 30 集分段行 + 8 列数字网格（选中不关浮层）；
 * 系列剧 tab = 同系列各季卡（当前季「播放中」，其余点名跳转）。
 */
@Composable
private fun EpisodePickerPopup(
    seriesId: String,
    currentIndex: Int,
    episodes: List<Episode>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onSelect: (Int) -> Unit,
    onSwitchSeries: (String) -> Unit,
    onClose: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var seasons by remember { mutableStateOf<List<com.hongguotv.pad.SeasonRef>>(emptyList()) }
    var seasonsLoading by remember { mutableStateOf(false) }
    // 手动翻到的范围段：记录当时当前集，当前集变化即覆盖作废
    var groupOverride by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(seriesId, tab) {
        if (tab != 1 || seasons.isNotEmpty()) return@LaunchedEffect
        seasonsLoading = true
        seasons = withContext(Dispatchers.IO) { runCatching { Api.seasons(seriesId) }.getOrDefault(emptyList()) }
        seasonsLoading = false
    }
    LaunchedEffect(seriesId) { seasons = emptyList() }

    val total = episodes.size
    val groupSize = 30
    val groupCount = maxOf(1, (total + groupSize - 1) / groupSize)
    val followGroup = ((currentIndex - 1).coerceAtLeast(0)) / groupSize
    val activeGroup = groupOverride?.takeIf { it.second == currentIndex }?.first ?: followGroup
    val slice = episodes.drop(activeGroup * groupSize).take(groupSize)

    Column(
        Modifier.width(460.dp).heightIn(max = 460.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xE6101014))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) { onClose(); true } else false
            }
            .padding(16.dp),
    ) {
        // 头部：双 tab（系列剧带红数字 badge）+ 全N集 + ∨ 收起（旧版同款）
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("选集", "系列剧").forEachIndexed { i, label ->
                    Column(
                        Modifier
                            .width(intrinsicSize = androidx.compose.foundation.layout.IntrinsicSize.Min)
                            .clickable { tab = i },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label,
                                color = if (tab == i) Palette.text else Palette.dim,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            if (i == 1 && seasons.isNotEmpty()) {
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "${seasons.size}",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(50))
                                        .background(Palette.accentFill)
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (tab == i) Palette.accent else Color.Transparent),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (tab == 0 && total > 0) {
                Text("全 $total 集", color = Palette.dim, fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            CtrlIcon(Icons.Filled.ExpandMore, onClick = onClose)
        }
        Spacer(Modifier.height(12.dp))
        if (tab == 0) {
            // 范围段 tab（1-30 / 31-60 / …）：放不下自动换行（旧版 flex-wrap 同款）
            if (groupCount > 1) {
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    (0 until groupCount).forEach { i ->
                        val from = i * groupSize + 1
                        val to = minOf((i + 1) * groupSize, total)
                        Text(
                            "$from-$to",
                            color = if (i == activeGroup) Palette.text else Palette.dim,
                            fontSize = 13.sp,
                            fontWeight = if (i == activeGroup) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.clickable { groupOverride = i to currentIndex },
                        )
                    }
                }
            }
            // 8 列数字网格；选中不关浮层（连选连跳）
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(8),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 340.dp),
            ) {
                items(slice, key = { it.vid }) { ep ->
                    val active = ep.index == currentIndex
                    Box(
                        Modifier.height(36.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (active) Palette.accentFill else Palette.panel)
                            .clickable { onSelect(ep.index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${ep.index}", color = if (active) Color.White else Palette.text, fontSize = 14.sp)
                    }
                }
            }
        } else {
            // 系列剧 tab：各季卡（当前季高亮「播放中」，未上线给预约文案）
            when {
                seasonsLoading -> CenterLoading()
                seasons.isEmpty() -> Text("这一部没有系列剧", color = Palette.dim, fontSize = 13.sp)
                else -> Column(
                    Modifier.verticalScroll(rememberScrollState()).heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    seasons.forEach { s ->
                        val current = s.seriesId == seriesId
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (current) Palette.accentFill else Palette.panel)
                                .clickable(enabled = !current) { if (s.count > 0) onSwitchSeries(s.seriesId) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (s.index > 0) "第 ${s.index} 季" else s.title) + if (s.count > 0) " · ${s.count} 集" else "",
                                    color = if (current) Color.White else Palette.text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (s.count == 0) {
                                    Text("未上线 · 可预约", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
                                }
                            }
                            Text(
                                if (current) "播放中" else if (s.count > 0) "播放" else "预约",
                                color = if (current) Color.White else Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Text(
        text,
        color = Color.White,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (hovered) Color(0x4DFFFFFF) else Color(0x26FFFFFF))
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 控制栏图标按钮：32×32，16px 图标，透明底，旧版 CHROME_BUTTON 同款。 */
@Composable
private fun CtrlIcon(
    icon: ImageVector,
    enabled: Boolean = true,
    active: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> Color(0xCC000000)
                    hovered && enabled -> Color(0x33FFFFFF)
                    else -> Color.Transparent
                },
            )
            .onPointerEvent(PointerEventType.Enter) { if (enabled) hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> Color.White.copy(alpha = 0.35f)
                !active -> Color.White.copy(alpha = 0.45f)
                else -> Color.White
            },
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 控制栏「图标 + 文字」按钮（倍速/清晰度/选集）。 */
@Composable
private fun CtrlText(icon: ImageVector, text: String, mono: Boolean = false, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Color(0x33FFFFFF) else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

/** 弹幕开关：「弹」字，关闭叠反斜杠（旧版 DanmakuSwitchIcon，B 站同款）。 */
@Composable
private fun DanmakuSwitchButton(on: Boolean, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Color(0x33FFFFFF) else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "弹",
            color = if (on) Color.White else Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (!on) {
            Canvas(Modifier.size(18.dp)) {
                drawLine(
                    Color.White.copy(alpha = 0.9f),
                    Offset(0f, 0f),
                    Offset(size.width, size.height),
                    strokeWidth = 1.6.dp.toPx(),
                )
            }
        }
    }
}

/** 播放页左上「‹ 第N集」胶囊。 */
@Composable
private fun BackPill(text: String, onClick: () -> Unit) {
    Row(
        Modifier.height(24.dp).clip(RoundedCornerShape(50)).background(Color(0x4D000000))
            .clickable { onClick() }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.ChevronLeft, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** 自绘进度条：静止 2px，悬停 4px + 白色圆点（旧版 ScrubBar 同口径）。 */
@Composable
private fun ScrubBar(currentMs: Long, durationMs: Long, onSeek: (Float) -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    val ratio = if (durationMs > 0) (currentMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Canvas(
        Modifier.fillMaxWidth().height(16.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0 && size.width > 0) onSeek((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(durationMs) {
                detectDragGestures { change, _ ->
                    if (durationMs > 0 && size.width > 0) onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val cy = size.height / 2f
        val h = if (hovered) 4.dp.toPx() else 2.dp.toPx()
        drawRoundRect(Color(0x33FFFFFF), Offset(0f, cy - h / 2f), Size(size.width, h), CornerRadius(h / 2f))
        drawRoundRect(Color(0x99FFFFFF), Offset(0f, cy - h / 2f), Size(size.width * ratio, h), CornerRadius(h / 2f))
        if (hovered) drawCircle(Color.White, 6.dp.toPx(), Offset(size.width * ratio, cy))
    }
}

private fun toggleWindowFullscreen() {
    val w = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    if (w is java.awt.Frame) {
        val max = (w.extendedState and java.awt.Frame.MAXIMIZED_BOTH) == java.awt.Frame.MAXIMIZED_BOTH
        w.extendedState = if (max) {
            w.extendedState and java.awt.Frame.MAXIMIZED_BOTH.inv()
        } else {
            w.extendedState or java.awt.Frame.MAXIMIZED_BOTH
        }
    }
}

@Composable
private fun FeedTab(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Color.White else Color.White.copy(alpha = 0.65f),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Palette.accentFill else Color(0x33FFFFFF))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun RailAction(
    icon: ImageVector,
    count: String,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    activeColor: Color = Palette.accent,
) {
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) activeColor else Color.White,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (count.isNotBlank()) count else label,
            color = if (active) activeColor else Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
        )
    }
}

/** 音量浮层（旧版 volume-popup）：数字 + 竖条 + 大圆点，居中于按钮上方；
 *  移出浮层即收（旧版 mouseleave 对策的 Compose 等价——收起挂内容 Exit）；
 *  onHover 供按钮侧的延迟收起判断「光标是否进了浮层」。 */
@Composable
private fun VolumePopup(volume: Float, onHover: (Boolean) -> Unit = {}, onClose: () -> Unit, onChange: (Float) -> Unit) {
    Column(
        Modifier.width(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000))
            .onPointerEvent(PointerEventType.Enter) { onHover(true) }
            .onPointerEvent(PointerEventType.Exit) { onHover(false); onClose() }
            .padding(top = 6.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${(volume * 100).roundToInt()}",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier.width(24.dp).height(96.dp)
                .pointerInput(Unit) {
                    detectTapGestures { o -> onChange((1f - o.y / size.height).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        onChange((1f - change.position.y / size.height).coerceIn(0f, 1f))
                    }
                },
        ) {
            val cx = size.width / 2f
            val w = 2.dp.toPx()
            val fillTop = size.height * (1f - volume)
            drawRoundRect(Color(0x40FFFFFF), Offset(cx - w / 2f, 0f), Size(w, size.height), CornerRadius(w / 2f))
            drawRoundRect(Color.White, Offset(cx - w / 2f, fillTop), Size(w, size.height - fillTop), CornerRadius(w / 2f))
            drawCircle(Color.White, 6.dp.toPx(), Offset(cx, fillTop))
        }
    }
}

/** 进度时间：补零到 MM:SS（旧版 formatDuration 同款）。 */
private fun fmtPlayerTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    return if (h > 0) {
        "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60)
    } else {
        "%02d:%02d".format(s / 60, s % 60)
    }
}

// ---------------------------------------------------------------- 弹幕渲染层

/** 飞行中的弹幕：车道 + 入场墙钟 + 文本宽（车道占位与 x 计算都要）。 */
private class FlyingDm(val id: Int, val text: String, val lane: Int, val widthPx: Float, var startAtMs: Long)

private const val DM_CROSS_MS = 9_000f
private const val DM_LANE_BUSY_MS = 1_400L

/**
 * 弹幕渲染层（旧版 danmaku-layer 同构）：
 * - 每条 = 绝对定位文本，9 秒线性从右缘滚到左缘出屏；
 * - 字号 = 画面高/30 限 15–22px，再乘设置缩放；车道高 = 字号 + 10；
 * - 车道数 = 高×显示区域 / 车道高（下限 4），同车道 1.4 秒占位防叠；
 * - 入场游标跟视频位置走，seek 跳变（倒跳/大跳）重置游标；
 * - 暂停冻结：记 pauseAt，恢复时把全部入场时刻整体平移；
 * - 滚动速度固定墙钟时长，不随视频倍速变（旧版实测结论）。
 */
@Composable
private fun DanmakuLayer(
    items: List<DmItem>,
    positionMs: Long,
    playing: Boolean,
    stageHeightPx: Float,
    opacity: Float,
    fontScale: Float,
    density: Float,
    area: Float,
    modifier: Modifier = Modifier,
) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    var stageW by remember { mutableStateOf(0f) }
    // 基准字号（px）：舞台高/30 限 15–22；车道高 = 字号 + 10
    val baseFontPx = ((stageHeightPx / 30f).coerceIn(15f, 22f)) * fontScale
    val laneH = baseFontPx + 10f
    val laneCount = maxOf(4, kotlin.math.floor(stageHeightPx * area / laneH).toInt())

    var flying by remember { mutableStateOf(emptyList<FlyingDm>()) }
    var laneFreeAt by remember { mutableStateOf(LongArray(4)) }
    var spawned by remember(items) { mutableStateOf(0) }
    var lastPosMs by remember(items) { mutableStateOf(0L) }
    var idGen by remember { mutableStateOf(0) }
    var nowMs by remember { mutableStateOf(0L) }
    var pauseAt by remember { mutableStateOf(0L) }
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()

    fun measureWidth(text: String): Float = try {
        textMeasurer
            .measure(
                androidx.compose.ui.text.AnnotatedString(text),
                androidx.compose.ui.text.TextStyle(
                    fontSize = with(d) { (baseFontPx / d.density).sp },
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            .size.width.toFloat()
    } catch (_: Exception) { 120f }

    fun xOf(dm: FlyingDm, now: Long): Float =
        stageW - ((now - dm.startAtMs).coerceAtLeast(0) / DM_CROSS_MS) * (stageW + dm.widthPx)

    // 墙钟帧循环：offset lambda 每帧读 nowMs，只重排偏移不重组内容
    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { nowMs = it / 1_000_000L }
        }
    }

    // 暂停冻结：恢复时把所有入场时刻整体平移
    LaunchedEffect(playing) {
        if (!playing) {
            pauseAt = System.currentTimeMillis()
        } else if (pauseAt > 0) {
            val delta = System.currentTimeMillis() - pauseAt
            pauseAt = 0L
            flying = flying.toList()
            flying.forEach { it.startAtMs += delta }
        }
    }

    // 入场：offset 落到当前播放位置之前的弹幕依次发射（密度按比例随机丢弃）
    LaunchedEffect(positionMs) {
        if (items.isEmpty()) return@LaunchedEffect
        // seek：位置倒跳或大幅前跳 → 游标落到新位置、清空在飞
        if (lastPosMs > 0 && (positionMs < lastPosMs - 500 || positionMs > lastPosMs + 3_000)) {
            spawned = items.indexOfFirst { it.offsetMs > positionMs }.let { if (it < 0) items.size else it }
            flying = emptyList()
            laneFreeAt = LongArray(laneCount)
        }
        lastPosMs = positionMs
        if (!playing || stageW <= 0f) return@LaunchedEffect
        if (laneFreeAt.size != laneCount) laneFreeAt = LongArray(laneCount)
        val now = System.currentTimeMillis()
        while (spawned < items.size && items[spawned].offsetMs <= positionMs) {
            val d = items[spawned]
            spawned += 1
            if (d.text.isBlank()) continue
            // 密度按比例随机丢弃（Kotlin 随机，不依赖 jdk.random——jlink 裁剪后
            // RandomGenerator.getDefault() 会抛 NoSuchAlgorithm）
            if (kotlin.random.Random.nextDouble() > density) continue
            var pick = 0
            for (i in 1 until laneCount) if (laneFreeAt[i] < laneFreeAt[pick]) pick = i
            laneFreeAt[pick] = now + DM_LANE_BUSY_MS
            flying = flying + FlyingDm(idGen++, d.text, pick, measureWidth(d.text), now)
        }
    }

    // 出屏回收：每半秒清一次已完成飞行的条目
    LaunchedEffect(nowMs / 500) {
        if (flying.isNotEmpty()) flying = flying.filter { xOf(it, nowMs) + it.widthPx > 0f }
    }

    Box(
        modifier
            .onSizeChanged { stageW = it.width.toFloat() },
    ) {
        if (opacity > 0.01f) {
            flying.forEach { dm ->
                androidx.compose.runtime.key(dm.id) {
                    Text(
                        dm.text,
                        color = Color.White,
                        fontSize = with(d) { (baseFontPx / d.density).sp },
                        fontWeight = FontWeight.SemiBold,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                Color(0xE6000000),
                                Offset(0f, 1f),
                                blurRadius = 3f,
                            ),
                        ),
                        maxLines = 1,
                        modifier = Modifier
                            .offset { androidx.compose.ui.unit.IntOffset(xOf(dm, nowMs).toInt(), (dm.lane * laneH).toInt()) }
                            .alpha(opacity),
                    )
                }
            }
        }
    }
}

/** 评论行（旧版 comment 列表条）：头像 32 + 昵称 + 时间 + 正文 + 回复入口 + 内联回复。 */
@Composable
private fun CommentRow(
    c: com.hongguotv.pad.EpisodeComment,
    replies: List<com.hongguotv.pad.CommentReply>?,
    onLoadReplies: () -> Unit,
    onReply: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row {
            RemoteImage(
                c.avatar,
                Modifier.size(32.dp).clip(RoundedCornerShape(50)),
                fallback = c.user,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.user.ifBlank { "匿名" },
                        color = Palette.dim, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(c.time, color = Palette.faint, fontSize = 11.sp)
                }
                Spacer(Modifier.height(3.dp))
                Text(c.text, color = Palette.text, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("回复", color = Palette.faint, fontSize = 11.sp, modifier = Modifier.clickable { onReply() })
                    if (c.replyCount > 0) {
                        Spacer(Modifier.width(12.dp))
                        val expanded = replies != null
                        Text(
                            if (expanded) "收起回复" else "展开 ${c.replyCount} 条回复",
                            color = Palette.accent, fontSize = 11.sp,
                            modifier = Modifier.clickable { if (!expanded) onLoadReplies() },
                        )
                    }
                    if (c.likes.isNotBlank()) {
                        Spacer(Modifier.width(12.dp))
                        Text("♡ ${c.likes}", color = Palette.faint, fontSize = 11.sp)
                    }
                }
                if (replies != null && replies.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("暂无回复（服务端未返回）", color = Palette.faint, fontSize = 11.sp)
                }
                replies?.takeIf { it.isNotEmpty() }?.let { list ->
                    Spacer(Modifier.height(6.dp))
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0x14FFFFFF)).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        list.forEach { r ->
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.user.ifBlank { "匿名" }, color = Palette.dim, fontSize = 11.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(r.time, color = Palette.faint, fontSize = 10.sp)
                                }
                                Text(r.text, color = Palette.text.copy(alpha = 0.9f), fontSize = 12.sp, lineHeight = 17.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 表情面板（旧版 emoji picker 的轻量等价）：常用 emoji 宫格，点选插入正文。 */
private val EMOJIS = listOf(
    "😀", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎",
    "🤔", "😅", "😭", "😡", "😱", "🤯", "😴", "🥳",
    "👍", "👎", "👏", "🙏", "💪", "🤝", "✌️", "👌",
    "❤️", "💔", "🔥", "⭐", "🎉", "🌹", "🍉", "🐶",
)

@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xF2141014))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        EMOJIS.chunked(8).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { e ->
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(6.dp))
                            .clickable { onPick(e) },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 17.sp) }
                }
            }
        }
    }
}


