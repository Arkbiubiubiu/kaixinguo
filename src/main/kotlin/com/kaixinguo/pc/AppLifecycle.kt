package com.kaixinguo.pc

import androidx.compose.runtime.mutableStateOf

/**
 * 「App 是不是在后台」。
 *
 * PC 上对应「窗口最小化/被遮住」：那时候片子还在放、声音还在响很别扭，
 * 所以和电视端一个口径——退到后台就暂停，回到前台不自动续播。
 */
object AppLifecycle {
    val inBackground = mutableStateOf(false)
}
