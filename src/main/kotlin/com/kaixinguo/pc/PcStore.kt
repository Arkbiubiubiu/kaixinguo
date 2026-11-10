package com.kaixinguo.pc

import androidx.compose.runtime.mutableStateOf
import com.hongguotv.pad.AccountState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Properties

/**
 * PC 端本地状态：实现 core 的 [AccountState]。
 *
 * 存成一个 properties 文件（和 TV 端一样：设置项 + 登录态 + 历史都在这），
 * 位置 `%APPDATA%\KaixinguoPC\prefs.properties`，方便备份和排查。
 */
object PcStore : AccountState {

    private val dir: File by lazy {
        File(System.getenv("APPDATA") ?: ".", "KaixinguoPC").apply { mkdirs() }
    }

    private val file = File(dir, "prefs.properties")

    private val props = Properties().apply {
        if (file.exists()) runCatching { file.reader(Charsets.UTF_8).use { load(it) } }
    }

    /**
     * 设置项的可观察镜像。
     *
     * 以前这些值是「读一次文件直接返回」，Compose 观察不到变化：开关点了不翻、
     * 滑杆拖不动（Slider 是受控组件，value 不变就回弹到原位）。所以值本身放
     * mutableStateOf，properties 只当持久化。
     */
    private val autoNextState = mutableStateOf(str("auto_next", "1") == "1")
    private val pauseMinState = mutableStateOf(str("pause_min", "1") == "1")
    private val prefetchState = mutableStateOf(str("prefetch_eps", "0").toIntOrNull()?.coerceIn(0, 10) ?: 0)
    private val maxHeightState = mutableStateOf(str("max_height", "1080").toIntOrNull() ?: 1080)

    private fun save() {
        runCatching {
            file.writer(Charsets.UTF_8).use { w ->
                props.stringPropertyNames().sorted().forEach { k -> w.write("$k=${props.getProperty(k)}\n") }
            }
        }
    }

    private fun str(key: String, def: String = ""): String = props.getProperty(key) ?: def
    private fun put(key: String, value: String) {
        props.setProperty(key, value); save()
    }

    /** 在线更新源（留空 = 自建服务器 hgtv.fabao.xin）。自建多入口时可填自己的更新目录。 */
    var updateBase: String
        get() = str("update_base", "")
        set(v) { put("update_base", v.trim()) }

    // ---------------- AccountState ----------------

    override val loggedIn: Boolean get() = accountCookies.isNotEmpty()

    override var accountCookies: String
        get() = str("account_cookies")
        set(v) = put("account_cookies", v)

    override var accountName: String
        get() = str("account_name")
        set(v) = put("account_name", v)

    override var accountUid: String
        get() = str("account_uid")
        set(v) = put("account_uid", v)

    override var accountSyncedAt: Long
        get() = str("account_at", "0").toLongOrNull() ?: 0L
        set(v) = put("account_at", v.toString())

    override var accountSource: String
        get() = str("account_source")
        set(v) = put("account_source", v)

    override var signUrl: String
        // 开源版默认留空：首次启动由用户填写自己的签名服务地址
        get() = str("sign_url", "")
        set(v) = put("sign_url", v.trim())

    /** 播放源（逗号分隔，按顺序回退；空 = 只用签名服务返回的地址） */
    var playBases: String
        get() = str("play_bases", "")
        set(v) = put("play_bases", v.trim())

    /** 播放令牌（随播放源下发的校验参数，空 = 不带） */
    var playToken: String
        get() = str("play_token", "")
        set(v) = put("play_token", v.trim())

    /** 历史/云端接口基址（空 = 与签名服务同源） */
    var historyBase: String
        get() = str("history_base", "")
        set(v) = put("history_base", v.trim())

    /** 设备身份（空 = 用占位默认值；公开版不内置真实指纹） */
    var deviceIid: String
        get() = str("device_iid", "")
        set(v) = put("device_iid", v.trim())

    var deviceId: String
        get() = str("device_id", "")
        set(v) = put("device_id", v.trim())

    var deviceCdid: String
        get() = str("device_cdid", "")
        set(v) = put("device_cdid", v.trim())

    /** 服务配置是否填齐（首次启动引导用） */
    val configured: Boolean get() = signUrl.isNotBlank()

    override var maxHeight: Int
        get() = maxHeightState.value
        set(v) {
            val clamped = v.coerceIn(360, 1080)
            maxHeightState.value = clamped
            put("max_height", clamped.toString())
        }

    // ---------------- PC 端本地设置（播放行为） ----------------

    /** 播完自动下一集（旧版 autoNextEpisode） */
    var autoNextEpisode: Boolean
        get() = autoNextState.value
        set(v) {
            autoNextState.value = v
            put("auto_next", if (v) "1" else "0")
        }

    /** 最小化时自动暂停（旧版 pauseOnMinimize） */
    var pauseOnMinimize: Boolean
        get() = pauseMinState.value
        set(v) {
            pauseMinState.value = v
            put("pause_min", if (v) "1" else "0")
        }

    /** 看完自动删除记录（旧版「看完自动删除」）：最后一集播完从历史里移除 */
    var deleteAfterFinish: Boolean
        get() = str("delete_after_finish", "0") == "1"
        set(v) = put("delete_after_finish", if (v) "1" else "0")

    /** 连播预取集数（0=关闭；改完立刻作用于当前播放） */
    var prefetchEpisodes: Int
        get() = prefetchState.value
        set(v) {
            val clamped = v.coerceIn(0, 10)
            prefetchState.value = clamped
            put("prefetch_eps", clamped.toString())
        }

    // ---------------- PC 端本地设置（播放偏好，旧版 playback-prefs 同款） ----------------

    /** 主题：auto / light / dark（旧版 theme store 同款） */
    var theme: String
        get() = str("theme", "dark").takeIf { it in setOf("auto", "light", "dark") } ?: "dark"
        set(v) = put("theme", v)

    // ---------------- 追更更新提醒（旧版 follow_service 同口径，本地基线比对） ----------------

    private val noticesFile = File(dir, "follow_notices.json")

    /** 已读状态的通知列表（新→旧）。字段：id/seriesId/title/detail/at/read */
    private var noticesCache: MutableList<JSONObject>? = null

    private fun notices(): MutableList<JSONObject> {
        noticesCache?.let { return it }
        val list = mutableListOf<JSONObject>()
        runCatching {
            if (noticesFile.exists()) {
                val arr = JSONArray(noticesFile.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
            }
        }
        noticesCache = list
        return list
    }

    private fun saveNotices() {
        runCatching {
            val arr = JSONArray()
            notices().forEach { arr.put(it) }
            noticesFile.writeText(arr.toString(), Charsets.UTF_8)
        }
    }

    fun followNotices(): List<JSONObject> = notices().sortedByDescending { it.optLong("at", 0L) }

    fun unreadNoticeCount(): Int = notices().count { !it.optBoolean("read", false) }

    /** 一轮更新检查：服务端集数 > 基线 → 落通知；首次见到的只记基线。返回新增数。 */
    fun checkFollowUpdates(): Int {
        val page = com.hongguotv.pad.Api.followUpdates()
        if (page.items.isEmpty()) return 0
        var added = 0
        val now = System.currentTimeMillis()
        page.items.forEach { item ->
            val key = "fb_${item.seriesId}"
            val baseline = str(key, "").toIntOrNull()
            if (baseline == null) {
                put(key, item.episodeCount.toString())
                return@forEach
            }
            if (item.episodeCount <= baseline) return@forEach
            put(key, item.episodeCount.toString())
            val notice = JSONObject()
                .put("id", now + added)
                .put("seriesId", item.seriesId)
                .put("title", item.title.ifBlank { "追更更新" })
                .put("episode", item.episodeCount)
                .put("detail", "追的剧更新了，新增 ${item.episodeCount - baseline} 集（共 ${item.episodeCount} 集）")
                .put("at", now)
                .put("read", false)
            notices().add(0, notice)
            added++
        }
        if (added > 0) saveNotices()
        return added
    }

    /** 追更时立刻把基线记成当前集数（避免「刚追完就报更新」）。 */
    fun seedFollowBaseline(seriesId: String, episodeCount: Int) {
        if (episodeCount > 0) put("fb_$seriesId", episodeCount.toString())
    }

    fun markNoticeRead(id: Long) {
        notices().firstOrNull { it.optLong("id", -1L) == id }?.put("read", true)
        saveNotices()
    }

    fun markAllNoticesRead() {
        notices().forEach { it.put("read", true) }
        saveNotices()
    }

    fun clearNotices() {
        notices().clear()
        saveNotices()
    }

    // ---------------- PC 端本地设置（弹幕显示，旧版 playback-prefs 同款） ----------------

    /** 倍速 0.25–4，默认 1（旧版 hongguo.playbackRate） */
    var playbackRate: Float
        get() = str("rate", "1").toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f
        set(v) = put("rate", v.coerceIn(0.25f, 4f).toString())

    /** 音量 0–1，默认 1（旧版 hongguo.volume） */
    var volumePref: Float
        get() = str("volume", "1").toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        set(v) = put("volume", v.coerceIn(0f, 1f).toString())

    /** 静音独立于音量：静音不改 volume，取消静音恢复原值（旧版 hongguo.muted） */
    var mutedPref: Boolean
        get() = str("muted", "0") == "1"
        set(v) = put("muted", if (v) "1" else "0")

    /** 弹幕开关，默认开（旧版 hongguo.danmaku） */
    var danmakuEnabled: Boolean
        get() = str("danmaku", "1") == "1"
        set(v) = put("danmaku", if (v) "1" else "0")

    // ---------------- PC 端本地设置（弹幕显示，旧版 playback-prefs 同款） ----------------

    /** 文字不透明度 0.1–1，默认 0.9 */
    var danmakuOpacity: Float
        get() = str("dm_opacity", "0.9").toFloatOrNull()?.coerceIn(0.1f, 1f) ?: 0.9f
        set(v) = put("dm_opacity", v.coerceIn(0.1f, 1f).toString())

    /** 字号缩放 0.5–2（1 = 画面高度自适应基准字号） */
    var danmakuFontScale: Float
        get() = str("dm_font", "1").toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 1f
        set(v) = put("dm_font", v.coerceIn(0.5f, 2f).toString())

    /** 密度 0–1（1 = 全部显示，入场按比例丢弃） */
    var danmakuDensity: Float
        get() = str("dm_density", "1").toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        set(v) = put("dm_density", v.coerceIn(0f, 1f).toString())

    /** 显示区域 0.25–1（弹幕占画面顶部的高度比例） */
    var danmakuArea: Float
        get() = str("dm_area", "1").toFloatOrNull()?.coerceIn(0.25f, 1f) ?: 1f
        set(v) = put("dm_area", v.coerceIn(0.25f, 1f).toString())

    override fun contains(key: String, seriesId: String): Boolean = readSet(key).contains(seriesId)

    override fun history(): List<JSONObject> = runCatching {
        val arr = JSONArray(str("history", "[]"))
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .groupBy { it.optString("title") }
            .values
            .mapNotNull { same -> same.maxWithOrNull(compareBy({ it.optInt("episode", 0) }, { it.optLong("at") })) }
            .sortedByDescending { it.optLong("at") }
    }.getOrDefault(emptyList())

    override fun mergeHistory(remote: List<JSONObject>): List<JSONObject> {
        val merged = LinkedHashMap<String, JSONObject>()
        (history() + remote).forEach { row ->
            val id = row.optString("seriesId")
            if (id.isEmpty()) return@forEach
            val old = merged[id]
            if (old == null) {
                merged[id] = row
            } else {
                val rowBetter = row.optInt("episode", 0) > old.optInt("episode", 0) ||
                    (row.optInt("episode", 0) == old.optInt("episode", 0) && row.optLong("at") > old.optLong("at"))
                val win = if (rowBetter) row else old
                val lose = if (rowBetter) old else row
                if (win.optString("cover").isEmpty()) win.put("cover", lose.optString("cover"))
                if (win.optString("title").isEmpty()) win.put("title", lose.optString("title"))
                merged[id] = win
            }
        }
        val list = merged.values.sortedByDescending { it.optLong("at") }.take(500)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        put("history", arr.toString())
        list.forEach { put("progress_${it.optString("seriesId")}", it.optInt("episode").toString()) }
        return list
    }

    override fun updateHistoryCover(seriesId: String, cover: String) {
        val arr = JSONArray()
        history().forEach { r ->
            if (r.optString("seriesId") == seriesId) r.put("cover", cover)
            arr.put(r)
        }
        put("history", arr.toString())
    }

    // ---------------- 额外：播放进度 / 收藏追更 ----------------

    /** 记一条观看历史（进度只增不减）。 */
    fun record(seriesId: String, title: String, cover: String, episode: Int, total: Int, allowRewind: Boolean = false, positionMs: Long = 0L) {
        val rows = history()
        val oldEp = rows.firstOrNull { it.optString("seriesId") == seriesId }?.optInt("episode", 0) ?: 0
        val keep = if (allowRewind) episode else maxOf(oldEp, episode)
        val list = rows.filter { it.optString("seriesId") != seriesId }.toMutableList()
        list.add(
            0,
            JSONObject()
                .put("seriesId", seriesId).put("title", title).put("cover", cover)
                .put("episode", keep).put("total", total).put("at", System.currentTimeMillis())
                // 本地也存「这一集看到第几秒」，历史页位置徽标与跨端续播同源
                .put("positionMs", positionMs),
        )
        val arr = JSONArray()
        list.take(200).forEach { arr.put(it) }
        put("history", arr.toString())
        put("progress_$seriesId", keep.toString())
    }

    fun posOf(vid: String): Long = str("pos_$vid", "0").toLongOrNull() ?: 0L

    fun markPos(vid: String, positionMs: Long) {
        if (positionMs <= 0) return
        put("pos_$vid", positionMs.toString())
    }

    fun removeHistory(seriesId: String) {
        val arr = JSONArray()
        history().filter { it.optString("seriesId") != seriesId }.forEach { arr.put(it) }
        put("history", arr.toString())
        props.remove("progress_$seriesId")
        save()
    }

    fun clearHistory() {
        props.remove("history")
        props.keys.filter { it.toString().startsWith("progress_") || it.toString().startsWith("pos_") }
            .forEach { props.remove(it) }
        save()
    }

    fun toggle(key: String, seriesId: String): Boolean {
        val set = readSet(key)
        val on = if (set.contains(seriesId)) {
            set.remove(seriesId); false
        } else {
            set.add(seriesId); true
        }
        put(key, set.joinToString("\u0001"))
        return on
    }

    fun ids(key: String): List<String> = readSet(key).toList()

    private fun readSet(key: String): LinkedHashSet<String> =
        LinkedHashSet(str(key).split('\u0001').filter { it.isNotBlank() })
}
