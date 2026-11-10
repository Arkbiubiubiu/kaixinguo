package com.hongguotv.pad

/**
 * 跨平台日志出口。
 *
 * core 模块要同时被安卓和 PC(JVM) 使用，不能直接调 `android.util.Log`。
 * 各端在启动时注入自己的实现（安卓用 Logcat，PC 用 stdout/文件）。
 */
object Log {
    var sink: ((level: String, tag: String, message: String) -> Unit)? = null

    fun d(tag: String, message: String) = sink?.invoke("D", tag, message) ?: Unit
    fun e(tag: String, message: String) = sink?.invoke("E", tag, message) ?: Unit

    fun d(tag: String, message: String, error: Throwable) =
        sink?.invoke("D", tag, "$message\n${error.stackTraceToString()}") ?: Unit

    fun e(tag: String, message: String, error: Throwable) =
        sink?.invoke("E", tag, "$message\n${error.stackTraceToString()}") ?: Unit
}
