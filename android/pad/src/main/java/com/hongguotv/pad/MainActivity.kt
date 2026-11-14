package com.hongguotv.pad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource

/**
 * 「App 是不是在前台」。
 *
 * 电视上没有窗口最小化，但有「按主页键退到后台」这件事——那时候片子还在放着、
 * 声音还在响，很别扭。Pad 上最小化窗口同理。所以两端的口径统一成：
 * 退到后台就暂停（回到前台不自动续播，由用户自己按确定）。
 */
object AppLifecycle {
    val inBackground = mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    companion object {
        /** 仅 debug 用的直达参数（adb am start --es kxg_series … --ei kxg_ep …） */
        var deepSeries: String? = null
        var deepEp: Int = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra("kxg_series")?.let { deepSeries = it }
            deepEp = intent?.getIntExtra("kxg_ep", 0) ?: 0
        }
        // 日志同时落盘：电视/平板连不上 adb 时，把 app.log 发回来就能定位问题
        val logFile = java.io.File(filesDir, "app.log")
        if (logFile.length() > 5L * 1024 * 1024) logFile.delete()
        val logWriter = runCatching {
            java.io.BufferedWriter(java.io.FileWriter(logFile, true))
        }.getOrNull()
        Log.sink = { level, tag, message ->
            android.util.Log.println(if (level == "E") android.util.Log.ERROR else android.util.Log.DEBUG, tag, message)
            runCatching {
                logWriter?.let { w ->
                    synchronized(w) {
                        w.write(level + "/" + tag + ": " + message + System.lineSeparator())
                        w.flush()
                    }
                }
            }
        }
        Store.init(this)
        // 日志定时回传（每 10 分钟发增量），电视上出问题我们也能主动看到
        runCatching { LogUploader.start(this) }
        App.state = Store
        App.playToken = BuildConfig.PLAY_TOKEN
        App.playBases = BuildConfig.DEFAULT_PLAY_BASES.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        App.historyBase = BuildConfig.HISTORY_BASE
        App.deviceIid = BuildConfig.DEVICE_IID
        App.deviceId = BuildConfig.DEVICE_ID
        App.deviceCdid = BuildConfig.DEVICE_CDID
        FeedCache.init(this)
        // 本机取流：Go 绑定（随 APK 打包），没有配置服务器时用它。
        // 初始化要加载 .so + Core.touch()，TV 上实测明显拖慢冷启动 —— 放后台线程，
        // 播放器真正取流前（至少几秒后）早就绪；DeviceResolver.play 里另有等待兜底。
        Thread({ DeviceResolver.init(this) }, "resolver-init").start()
        LocalResolverProvider.play = { vid, want -> DeviceResolver.play(vid, want) }
        setContent {
            // 先把「启动画面」这一帧画上去，再做完整界面的首次组合：
            // 电视上冷启动 Compose 首帧要好几秒（实测 Displayed +10~11s），
            // 直接组合 App() 的话这段时间用户看到的是黑屏。启动图和
            // themes.xml 的 windowBackground 用同一张图，衔接无跳变。
            var showApp by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                showApp = true
            }
            if (showApp) {
                TvRoot { App() }
            } else {
                LaunchScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLifecycle.inBackground.value = false
    }

    override fun onStop() {
        super.onStop()
        AppLifecycle.inBackground.value = true
    }

    override fun onResume() {
        super.onResume()
        // 云端历史合并留在后台做，不挡界面
        Api.historySyncAsync()
    }
}

/** 冷启动第一帧：品牌底色 + 图标，和 themes.xml 的启动窗口背景一致。 */
@androidx.compose.runtime.Composable
private fun LaunchScreen() {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0F0D0F)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painter = painterResource(R.mipmap.ic_tv), contentDescription = null)
    }
}
