package com.hongguotv.pad

import android.content.Context
import dev.openhongguo.core.Callback
import dev.openhongguo.core.Client
import dev.openhongguo.core.Core
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 本机取流（Android）：直接调用随 APK 打包的 Go 绑定（gomobile）。
 *
 * 上游取流网关对「一台匿名设备」只放行第一次请求，所以每次取流都注册一台
 * 新的匿名设备、取完就把档案删掉——和 PC 端 streamsrv 的策略一致。
 * 设备档案只落在应用私有目录，不涉及账号，也不经过任何服务器。
 */
object DeviceResolver {

    private var dir: File? = null
    @Volatile private var ready = false
    /** 初始化完成信号：启动时 init 在后台线程跑，第一次取流撞上就等它 */
    private val readyLatch = CountDownLatch(1)

    fun init(context: Context) {
        val d = File(context.filesDir, "ohg").apply { mkdirs() }
        // 清掉上次残留的档案（每个只用一次）
        runCatching { d.listFiles()?.forEach { it.delete() } }
        dir = d
        ready = runCatching {
            Core.touch()
            true
        }.getOrDefault(false)
        readyLatch.countDown()
        Log.d("PadResolver", "init ready=$ready dir=${d.absolutePath}")
    }

    /**
     * 取流。上游对匿名设备有个坏毛病：偶尔第一次请求就回空包
     * （ANONYMOUS_LIMITED，"anonymous response was empty"），换一台新设备再来一次
     * 通常就能过。所以走到这一步失败时重试一次；超时/异常不重试，别让用户干等。
     */
    fun play(vid: String, want: Int): PlayInfo? {
        if (!ready) runCatching { readyLatch.await(6, TimeUnit.SECONDS) }
        if (!ready) return null
        repeat(2) { i ->
            val (info, answered) = playOnce(vid, want)
            if (info != null) {
                Log.d("PadResolver", "取流成功（第 ${i + 1} 台设备）vid=$vid")
                return info
            }
            if (!answered) return null
            Log.d("PadResolver", "第 ${i + 1} 台设备被上游回空包，换设备重试")
        }
        return null
    }

    /** 返回 (播放信息, 上游是否回了包)；只有「回了包但拿不到流」才值得换设备重试。 */
    private fun playOnce(vid: String, want: Int): Pair<PlayInfo?, Boolean> {
        val base = dir ?: return null to false
        val profile = File(base, "profile-${System.nanoTime()}.json")
        val client: Client = runCatching {
            Core.newClient(profile.absolutePath, "", null)
        }.onFailure { Log.e("PadResolver", "newClient 失败: ${it.message}") }.getOrNull()
            ?: return null to false

        val args = org.json.JSONArray().put(vid).toString()
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        return try {
            client.start(
                "resolvePlayback",
                args,
                Callback { json ->
                    holder[0] = json
                    latch.countDown()
                },
            )
            if (!latch.await(25, TimeUnit.SECONDS)) {
                Log.e("PadResolver", "取流超时 vid=$vid")
                null to false
            } else {
                parse(holder[0], want) to (holder[0] != null)
            }
        } catch (t: Throwable) {
            Log.e("PadResolver", "取流失败: ${t.message}")
            null to false
        } finally {
            runCatching { profile.delete() }
            runCatching { client.toString() }
        }
    }

    private fun parse(json: String?, want: Int): PlayInfo? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        root.optJSONObject("error")?.let {
            Log.e("PadResolver", "上游错误: ${it.optString("code")} ${it.optString("message")}")
            return null
        }
        val result = root.optJSONObject("result") ?: return null
        val pb = result.optJSONObject("playback") ?: return null
        val streams = pb.optJSONArray("streams") ?: return null
        var best: JSONObject? = null
        var bestH = -1
        val cap = if (want > 0) want else 1080
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            if (s.optString("url").isEmpty()) continue
            val h = s.optInt("height", 0)
            if (h > 0 && h <= cap && h > bestH) {
                bestH = h
                best = s
            }
        }
        if (best == null) {
            for (i in 0 until streams.length()) {
                val s = streams.optJSONObject(i) ?: continue
                if (s.optString("url").isEmpty()) continue
                val h = s.optInt("height", 0)
                if (h > bestH) {
                    bestH = h
                    best = s
                }
            }
        }
        val chosen = best ?: return null
        val keyHex = result.optJSONObject("contentKey")?.optString("key").orEmpty()
        val key = keyHex.takeIf { it.length >= 32 }?.let { hexToBytes(it) }
        return PlayInfo(
            url = chosen.optString("url"),
            backupUrl = chosen.optString("backup_url"),
            key = key,
            definition = chosen.optString("definition").ifEmpty { "${bestH}p" },
            height = if (bestH > 0) bestH else 720,
            duration = pb.optInt("duration_seconds", 0),
            size = chosen.optLong("size", 0L),
        )
    }

    private fun hexToBytes(text: String): ByteArray =
        ByteArray(text.length / 2) { i ->
            ((Character.digit(text[i * 2], 16) shl 4) + Character.digit(text[i * 2 + 1], 16)).toByte()
        }
}
