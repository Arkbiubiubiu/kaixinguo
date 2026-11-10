package com.hongguotv.pad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        App.state = Store
        App.playToken = BuildConfig.PLAY_TOKEN
        App.playBases = BuildConfig.DEFAULT_PLAY_BASES.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        App.historyBase = BuildConfig.HISTORY_BASE
        FeedCache.init(this)
        setContent {
            TvRoot { App() }
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
