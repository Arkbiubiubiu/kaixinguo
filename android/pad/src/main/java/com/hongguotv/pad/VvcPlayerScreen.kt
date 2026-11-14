package com.hongguotv.pad

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.viewinterop.AndroidView
import `is`.xyz.mpv.MpvCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * VVC（bytevc2）专用播放页：本地解密 → 拆成 Annex-B + ADTS → libmpv 软解渲染。
 *
 * ExoPlayer/MediaCodec 没有 VVC 解码器，这类片源以前只能提示后跳过；本页让它们也能正常看。
 * 不经过服务器转码：下载解密后的字节在本机拆容器，音画都留在本机。
 *
 * 行为与主播放器对齐：
 * - 非刷剧模式从上次位置续播，每 5 秒记一次进度；刷剧模式永远从 0:00 开始、不写历史。
 * - 一集播完自动下一集；集数来自主播放器已经拿到的 episodes。
 * - 电视遥控器：确定=暂停/播放，←/→=快退/快进 10 秒，返回=退出。
 */
@Composable
fun VvcPlayerScreen(
    seriesId: String,
    episodes: List<Episode>,
    startIndex: Int,
    title: String,
    /** 刷剧模式：从 0 开始、不写历史（和主播放器口径一致） */
    startAtZero: Boolean,
    onBack: () -> Unit,
    onDetail: () -> Unit,
    /** 上一集/下一集落到非 VVC 集时，交回普通播放器 */
    onNonVvc: (Int) -> Unit,
) {
    val context = LocalContext.current
    var index by remember(seriesId) { mutableStateOf(startIndex.coerceAtLeast(1)) }
    var attempt by remember { mutableStateOf(0) }
    var phase by remember { mutableStateOf("准备中…") }
    var error by remember { mutableStateOf<String?>(null) }
    var videoFile by remember { mutableStateOf<File?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0.0) }
    var dur by remember { mutableStateOf(0.0) }
    var fps by remember { mutableStateOf(0.0) }
    var surface by remember { mutableStateOf<android.view.Surface?>(null) }
    var ended by remember { mutableStateOf(false) }
    var lastSave by remember { mutableStateOf(0L) }
    // 画面没出来时把现场留在屏幕上（用户可截图），同时写进 app.log 回传
    var diag by remember { mutableStateOf("") }
    var baseSec by remember { mutableStateOf(0.0) }
    var seekIdx by remember { mutableStateOf<SeekIndex?>(null) }
    var seekJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var cutGen by remember { mutableStateOf(0) }

    val ep = episodes.getOrNull(index - 1)
    val isTv = Store.appContext.packageName.endsWith("nativeapp")
    val rootFocus = remember { FocusRequester() }
    val pauseFocus = remember { FocusRequester() }
    val prevFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    var rootHasFocus by remember { mutableStateOf(true) }

    // 电视遥控器：焦点必须先落在根容器上，否则方向键/确定全部收不到（2.2.142 的坑）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400)
        runCatching { rootFocus.requestFocus() }
    }
    // 控制条出现/切集后焦点可能被重建，重新把焦点还给播放层
    LaunchedEffect(loaded, index) {
        if (loaded) {
            kotlinx.coroutines.delay(200)
            runCatching { rootFocus.requestFocus() }
        }
    }

    fun saveProgress() {
        val e = episodes.getOrNull(index - 1) ?: return
        val ms = (pos * 1000).toLong()
        if (ms <= 0) return
        runCatching { Store.markPos(e.vid, ms) }
        if (!startAtZero) {
            runCatching {
                Store.record(
                    seriesId = seriesId,
                    title = title,
                    cover = DetailCache.get(seriesId)?.cover.orEmpty(),
                    episode = index,
                    total = episodes.size,
                )
            }
            runCatching { Api.historySyncAsync() }
            runCatching { Api.reportProgress(seriesId, e.vid, index, ms) }
        }
    }

    // 每一集：有缓存直接用，没有才下载+解密+拆容器
    LaunchedEffect(index, ep?.vid, attempt) {
        val e = ep ?: return@LaunchedEffect
        Log.d("VvcPlayer", "prepare ep=$index vid=${e.vid} attempt=$attempt")
        error = null
        loaded = false
        ended = false
        pos = 0.0
        dur = 0.0
        videoFile = null
        audioFile = null
        baseSec = 0.0
        seekIdx = null
        phase = "准备中…"
        var nonVvc = false
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                val root = File(context.cacheDir, "vvc")
                // 只留当前这集：短剧集数多，缓存全留会把存储吃满
                runCatching {
                    root.listFiles()?.forEach { d ->
                        if (d.name != e.vid) d.deleteRecursively()
                    }
                }
                val dir = File(root, e.vid).apply { mkdirs() }
                val v = File(dir, "video.266")
                val a = File(dir, "audio.aac")
                var metaDur = 0.0
                var metaFps = 0.0
                val meta = File(dir, "meta.txt")
                if (v.exists() && v.length() > 0 && meta.exists()) {
                    // 缓存复用：帧率/时长上次算过，直接读，别显示 00:00
                    runCatching {
                        val parts = meta.readText().trim().split(",")
                        metaDur = parts[0].toDouble()
                        metaFps = parts.getOrNull(1)?.toDouble() ?: 0.0
                    }
                }
                if (!(v.exists() && v.length() > 0 && File(dir, "video.index").exists())) {
                    phase = "正在下载并解密…"
                    val tiers = Api.tiers(e.vid)
                    val playable = tiers.filter { it.playable }
                    // VVC 是纯软解：1080p 在电视 CPU 上必卡，统一选 720p 以内
                    val cap = minOf(720, Store.maxHeight)
                    val picked = playable.firstOrNull { it.height == cap }
                        ?: playable.filter { it.height <= cap }.maxByOrNull { it.height }
                        ?: playable.minByOrNull { it.height }
                    val info = picked?.toPlayInfo() ?: Api.play(e.vid, 1004, 0)
                    val rv = RemoteVideo(info)
                    rv.prepare(allowVvc = true)
                    // 切集可能落到普通 HEVC/H.264 集：这集交给主播放器，别硬拆 VVC
                    val tag = rv.videoCodec
                    if (tag.isNotEmpty() && !tag.equals("bvc2", true)) {
                        nonVvc = true
                        return@runCatching null
                    }
                    val mp4 = File(dir, "source.mp4")
                    rv.saveDecrypted(mp4)
                    phase = "正在转换…"
                    val res = VvcExport.export(mp4, v, a)
                    runCatching { mp4.delete() }
                    if (!res.videoOk) error("转换失败")
                    metaDur = res.duration
                    metaFps = res.fps
                    runCatching { meta.writeText("${res.duration},${res.fps}") }
                }
                val af = a.takeIf { it.exists() && it.length() > 0 }
                Triple(v, af, metaDur to metaFps)
            }.getOrElse {
                error = it.message ?: "VVC 准备失败"
                null
            }
        }
        if (nonVvc) {
            Log.d("VvcPlayer", "ep$index 不是 VVC，交回主播放器")
            onNonVvc(index)
            return@LaunchedEffect
        }
        prepared ?: return@LaunchedEffect
        Log.d("VvcPlayer", "prepared video=${prepared.first.length()}B audio=${prepared.second?.length() ?: 0}B dur=${prepared.third.first} fps=${prepared.third.second}")
        videoFile = prepared.first
        audioFile = prepared.second
        seekIdx = withContext(Dispatchers.IO) { SeekIndex.load(prepared.first.parentFile) }
        dur = prepared.third.first
        fps = prepared.third.second
        phase = "正在启动解码…"
        if (!withContext(Dispatchers.IO) { MpvCore.ensure(context) }) {
            error = "解码器初始化失败"
            return@LaunchedEffect
        }
        phase = ""
    }

    // 视频与 Surface 谁后到都由这里起播
    LaunchedEffect(videoFile, surface, index, attempt) {
        val f = videoFile ?: return@LaunchedEffect
        val sf = surface ?: return@LaunchedEffect
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                if (!MpvCore.ensure(context)) return@runCatching false
                `is`.xyz.mpv.MPVLib.attachSurface(sf)
                MpvCore.load(f.absolutePath, audioFile?.absolutePath, fps)
                // 非刷剧：从上次看到的位置接着播
                val resume = if (startAtZero) 0L else Store.posOf(episodes.getOrNull(index - 1)?.vid.orEmpty())
                if (resume > 3000) MpvCore.seekTo(resume / 1000.0)
                true
            }.getOrElse {
                error = "播放器启动失败：${it.message}"
                false
            }
        }
        if (ok) {
            loaded = true
            phase = ""
            Log.d("VvcPlayer", "load ok ep=$index vo=${MpvCore.voConfigured()}")
        } else if (error == null) error = "播放器启动失败"
    }

    // 8 秒还没建立视频输出 = 这台设备的 GPU/驱动没跑起来，把诊断留在屏幕上
    LaunchedEffect(loaded, index) {
        if (!loaded) return@LaunchedEffect
        delay(8000)
        if (!MpvCore.voConfigured()) {
            diag = MpvCore.renderDiag()
            Log.e("VvcPlayer", "no video after 8s ep=$index $diag")
        } else {
            diag = ""
        }
    }

    // 位置轮询 + 进度落盘 + 播完自动下一集
    LaunchedEffect(loaded) {
        while (loaded) {
            pos = baseSec + MpvCore.position()
            if (dur <= 0) MpvCore.duration().let { if (it > 0) dur = it }
            paused = MpvCore.isPaused()
            val now = System.currentTimeMillis()
            if (now - lastSave > 5000) {
                lastSave = now
                saveProgress()
            }
            if (!ended && dur > 1 && pos >= dur - 1.2) {
                ended = true
                if (index < episodes.size) {
                    index += 1 // LaunchedEffect(index) 会重新准备下一集
                } else {
                    MpvCore.pause(true)
                }
            }
            delay(500)
        }
    }

    // 裸流 seek：从目标附近的关键帧切一段临时流（带 SPS/PPS），重新加载。
    // 这是唯一可靠的做法——mpv 对无索引的 .266 直接 seek 会跳到 EOF。
    fun requestSeek(target: Double) {
        val idx = seekIdx ?: return
        val vf = videoFile ?: return
        if (!loaded || idx.fps <= 0) return
        seekJob?.cancel()
        seekJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            // 连续按方向键时只处理最后一次
            kotlinx.coroutines.delay(180)
            val t = target.coerceIn(0.0, maxOf(0.0, dur - 1.0))
            val frame = (t * idx.fps).toInt().coerceIn(0, idx.vOffsets.size - 1)
            var k = frame
            while (k > 0 && !idx.vKeys[k]) k--
            val base = k / idx.fps
            val wasPaused = MpvCore.isPaused()
            cutGen += 1
            val gen = cutGen
            val okCut = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = vf.parentFile ?: return@runCatching false
                    dir.listFiles()?.forEach { f ->
                        if (f.name.startsWith("seek_") && !f.name.contains("_$gen.")) f.delete()
                    }
                    val params = java.io.File(dir, "params.266")
                    val cutV = java.io.File(dir, "seek_v_$gen.266")
                    cutV.outputStream().buffered(1 shl 20).use { out ->
                        if (params.exists()) out.write(params.readBytes())
                        java.io.RandomAccessFile(vf, "r").use { raf ->
                            raf.seek(idx.vOffsets[k])
                            val buf = ByteArray(1 shl 20)
                            var left = raf.length() - idx.vOffsets[k]
                            while (left > 0) {
                                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                left -= n
                            }
                        }
                    }
                    var cutA: java.io.File? = null
                    val af = audioFile
                    if (af != null && idx.aOffsets.isNotEmpty()) {
                        val ai = ((t / dur) * idx.aOffsets.size).toInt().coerceIn(0, idx.aOffsets.size - 1)
                        cutA = java.io.File(dir, "seek_a_$gen.aac")
                        cutA.outputStream().buffered(1 shl 18).use { out ->
                            java.io.RandomAccessFile(af, "r").use { raf ->
                                raf.seek(idx.aOffsets[ai])
                                val buf = ByteArray(1 shl 18)
                                var left = raf.length() - idx.aOffsets[ai]
                                while (left > 0) {
                                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    left -= n
                                }
                            }
                        }
                    }
                    baseSec = base
                    pos = base
                    MpvCore.load(cutV.absolutePath, cutA?.absolutePath, fps)
                    if (wasPaused) MpvCore.pause(true)
                    true
                }.getOrDefault(false)
            }
            if (!okCut) Log.e("VvcPlayer", "seek cut failed t=$t")
        }
    }

    BackHandler {
        saveProgress()
        onBack()
    }

    // 离开页面必须停掉 mpv，否则画面没了声音还在放
    DisposableEffect(Unit) {
        onDispose {
            saveProgress()
            runCatching { MpvCore.stop() }
            runCatching {
                videoFile?.parentFile?.listFiles()?.forEach { f ->
                    if (f.name.startsWith("seek_")) f.delete()
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onFocusChanged { rootHasFocus = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // 焦点在按钮上时，方向键/确定交给按钮自己处理
                if (!rootHasFocus) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionDown -> {
                        if (loaded) runCatching { pauseFocus.requestFocus() }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                        val p = MpvCore.isPaused()
                        MpvCore.pause(!p)
                        paused = !p
                        true
                    }
                    Key.MediaPlayPause -> {
                        val p = MpvCore.isPaused()
                        MpvCore.pause(!p)
                        paused = !p
                        true
                    }
                    Key.DirectionLeft -> {
                        requestSeek((baseSec + MpvCore.position() - 10).coerceAtLeast(0.0))
                        true
                    }
                    Key.DirectionRight -> {
                        requestSeek(baseSec + MpvCore.position() + 10)
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { surface = h.surface }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            surface = null
                            runCatching { `is`.xyz.mpv.MPVLib.detachSurface() }
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (error != null || phase.isNotEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (error != null) {
                    Text(error!!, color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xCCFF4A3D))
                            .clickable { runCatching { MpvCore.stop() }; attempt += 1 },
                    ) { Text("重试", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 9.dp)) }
                } else {
                    LoadingSpinner(size = 54.dp, stroke = 5.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(phase, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                }
            }
        }

        // 顶部：返回 + 标题 + 详情
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x66000000))
                    .clickable { saveProgress(); onBack() }.padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("← 返回", color = Color.White, fontSize = 15.sp) }
            Spacer(Modifier.width(12.dp))
            Text(
                "$title · 第 $index 集",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x66000000))
                    .clickable { saveProgress(); onDetail() }.padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("详情", color = Color.White, fontSize = 15.sp) }
        }

        if (diag.isNotEmpty()) {
            Text(
                "画面未初始化：$diag",
                color = Color(0xFFFFD54F),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 78.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color(0x99000000))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        // 底部控制条：可拖动进度条 + 播放/暂停 + 上下集（遥控器确定/左右也生效）
        if (loaded) {
            var scrub by remember { mutableStateOf<Float?>(null) }
            val frac = scrub ?: if (dur > 0) (pos / dur).toFloat().coerceIn(0f, 1f) else 0f
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp),
            ) {
                var barWidth by remember { mutableStateOf(1f) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .onSizeChanged { barWidth = it.width.toFloat().coerceAtLeast(1f) }
                        .pointerInput(dur) {
                            detectTapGestures { off ->
                                if (dur > 0) requestSeek((off.x / barWidth).coerceIn(0f, 1f) * dur)
                            }
                        }
                        .pointerInput(dur) {
                            detectDragGestures(
                                onDragStart = { off ->
                                    scrub = (off.x / barWidth).coerceIn(0f, 1f)
                                },
                                onDrag = { ch, _ ->
                                    ch.consume()
                                    scrub = (ch.position.x / barWidth).coerceIn(0f, 1f)
                                },
                                onDragEnd = {
                                    scrub?.let { f -> if (dur > 0) requestSeek(f * dur) }
                                    scrub = null
                                },
                                onDragCancel = { scrub = null },
                            )
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF), RoundedCornerShape(2.dp)))
                    Box(Modifier.fillMaxWidth(frac).height(4.dp).background(Color(0xFFFF4A3D), RoundedCornerShape(2.dp)))
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CtrlButton(
                        label = if (paused) "播放" else "暂停",
                        fontSize = 15.sp,
                        focusRequester = pauseFocus,
                        leftReq = null,
                        rightReq = if (index > 1) prevFocus else if (index < episodes.size) nextFocus else null,
                        onUp = { runCatching { rootFocus.requestFocus() } },
                    ) {
                        val p = MpvCore.isPaused()
                        MpvCore.pause(!p)
                        paused = !p
                    }
                    Spacer(Modifier.width(12.dp))
                    if (index > 1) {
                        CtrlButton(
                            label = "上一集",
                            fontSize = 14.sp,
                            focusRequester = prevFocus,
                            leftReq = pauseFocus,
                            rightReq = if (index < episodes.size) nextFocus else null,
                            onUp = { runCatching { rootFocus.requestFocus() } },
                        ) { saveProgress(); index -= 1 }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (index < episodes.size) {
                        CtrlButton(
                            label = "下一集",
                            fontSize = 14.sp,
                            focusRequester = nextFocus,
                            leftReq = if (index > 1) prevFocus else pauseFocus,
                            rightReq = null,
                            onUp = { runCatching { rootFocus.requestFocus() } },
                        ) { saveProgress(); index += 1 }
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "${fmt(scrub?.let { it * dur } ?: pos)} / ${fmt(dur)}",
                        color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    if (isTv) {
                        Text(
                            "← → 快进退 · ↓ 选按钮",
                            color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

private fun fmt(sec: Double): String {
    if (sec <= 0) return "00:00"
    val s = sec.toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}

/** .266 裸流的 seek 索引（导出时生成）：每一帧在文件里的字节位置 + 是否关键帧。 */
private class SeekIndex(
    val fps: Double,
    val dur: Double,
    val vOffsets: LongArray,
    val vKeys: BooleanArray,
    val aOffsets: LongArray,
) {
    companion object {
        fun load(dir: java.io.File): SeekIndex? = runCatching {
            val f = java.io.File(dir, "video.index")
            if (!f.exists()) return null
            var fps = 25.0
            var dur = 0.0
            val vs = ArrayList<Long>()
            val ks = ArrayList<Boolean>()
            val as_ = ArrayList<Long>()
            f.forEachLine { line ->
                when {
                    line.startsWith("fps=") -> fps = line.substring(4).toDoubleOrNull() ?: 25.0
                    line.startsWith("dur=") -> dur = line.substring(4).toDoubleOrNull() ?: 0.0
                    line.startsWith("v ") -> {
                        val parts = line.substring(2).trim().split(' ')
                        vs.add(parts[0].toLongOrNull() ?: 0L)
                        ks.add(parts.getOrNull(1) == "1")
                    }
                    line.startsWith("a ") -> as_.add(line.substring(2).trim().toLongOrNull() ?: 0L)
                }
            }
            if (vs.isEmpty()) null
            else SeekIndex(fps, dur, vs.toLongArray(), ks.toBooleanArray(), as_.toLongArray())
        }.getOrNull()
    }
}

/** 电视控制按钮：可聚焦、有红框焦点态、确定触发、↑ 回到播放层。 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@androidx.compose.runtime.Composable
private fun CtrlButton(
    label: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    focusRequester: FocusRequester? = null,
    leftReq: FocusRequester? = null,
    rightReq: FocusRequester? = null,
    onUp: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .clip(shape)
            .background(Color(0x66000000))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                left = leftReq ?: FocusRequester.Cancel
                right = rightReq ?: FocusRequester.Cancel
                up = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.dp, Color(0xFFFF4A3D), shape) else Modifier)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter -> { onClick(); true }
                    Key.DirectionUp -> { onUp(); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, color = Color.White, fontSize = fontSize)
    }
}
