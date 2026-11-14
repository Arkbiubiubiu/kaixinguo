package com.hongguotv.pad

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    val ep = episodes.getOrNull(index - 1)
    val isTv = Store.appContext.packageName.endsWith("nativeapp")

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
        phase = "准备中…"
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
                if (!(v.exists() && v.length() > 0)) {
                    phase = "正在下载并解密…"
                    val tiers = Api.tiers(e.vid)
                    val playable = tiers.filter { it.playable }
                    val cap = Store.maxHeight
                    val picked = playable.firstOrNull { it.height == cap }
                        ?: playable.firstOrNull { it.height <= cap }
                        ?: playable.firstOrNull()
                    val info = picked?.toPlayInfo() ?: Api.play(e.vid, 1004, 0)
                    val rv = RemoteVideo(info)
                    rv.prepare(allowVvc = true)
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
        } ?: return@LaunchedEffect
        Log.d("VvcPlayer", "prepared video=${prepared.first.length()}B audio=${prepared.second?.length() ?: 0}B dur=${prepared.third.first} fps=${prepared.third.second}")
        videoFile = prepared.first
        audioFile = prepared.second
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
            pos = MpvCore.position()
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

    BackHandler {
        saveProgress()
        onBack()
    }

    // 离开页面必须停掉 mpv，否则画面没了声音还在放
    DisposableEffect(Unit) {
        onDispose {
            saveProgress()
            runCatching { MpvCore.stop() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        val p = MpvCore.isPaused()
                        MpvCore.pause(!p)
                        paused = !p
                        true
                    }
                    Key.DirectionLeft -> {
                        if (loaded) MpvCore.seekTo((pos - 10).coerceAtLeast(0.0))
                        true
                    }
                    Key.DirectionRight -> {
                        if (loaded) MpvCore.seekTo(pos + 10)
                        true
                    }
                    else -> false
                }
            },
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

        // 底部：暂停 + 时间 + 下一集
        if (loaded) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x66000000))
                        .clickable { MpvCore.pause(!paused); paused = !paused }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) { Text(if (paused) "播放" else "暂停", color = Color.White, fontSize = 15.sp) }
                Spacer(Modifier.width(16.dp))
                Text(
                    "${fmt(pos)} / ${fmt(dur)}",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp,
                )
                Spacer(Modifier.weight(1f))
                if (!isTv && index < episodes.size) {
                    Box(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x66000000))
                            .clickable { saveProgress(); index += 1 }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) { Text("下一集", color = Color.White, fontSize = 15.sp) }
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
