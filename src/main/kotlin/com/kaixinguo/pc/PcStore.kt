package com.kaixinguo.pc

import androidx.compose.runtime.mutableStateOf
import com.hongguotv.pad.AccountState
import com.hongguotv.pad.HistoryRepository
import com.hongguotv.pad.MachineIdentity
import com.hongguotv.pad.RegisteredDevice
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

    internal val dir: File by lazy {
        File(System.getenv("APPDATA") ?: ".", "KaixinguoPC").apply { mkdirs() }
    }

    private val file = File(dir, "prefs.properties")

    internal val props = Properties().apply {
        if (file.exists()) runCatching { file.reader(Charsets.UTF_8).use { load(it) } }
    }

    private val HISTORY_SCHEMA = HistoryRepository.SCHEMA

    init { migrateHistorySchema() }

    /** 历史行结构版本：清脏行、补齐秒数/时间字段；只跑一次。 */
    private fun migrateHistorySchema() {
        if (str("history_schema", "0").toIntOrNull() ?: 0 >= HISTORY_SCHEMA) return
        runCatching {
            val arr = JSONArray(str("history", "[]"))
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val id = row.optString("seriesId")
                val ep = row.optInt("episode", 0)
                if (id.isEmpty() || ep <= 0) continue
                row.put("episode", ep)
                    .put("total", maxOf(0, row.optInt("total", 0)))
                    .put("positionMs", maxOf(0L, row.optLong("positionMs", 0L)))
                    .put("at", maxOf(0L, row.optLong("at", 0L)))
                out.put(row)
            }
            props.setProperty("history", out.toString())
            props.setProperty("history_schema", HISTORY_SCHEMA.toString())
            save()
        }
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
    private val prefetchState = mutableStateOf(str("prefetch_eps", "5").toIntOrNull()?.coerceIn(0, 10) ?: 5)
    private val maxHeightState = mutableStateOf(str("max_height", "1080").toIntOrNull() ?: 1080)

    internal fun save() {
        runCatching {
            file.writer(Charsets.UTF_8).use { w ->
                props.stringPropertyNames().sorted().forEach { k -> w.write("$k=${props.getProperty(k)}\n") }
            }
        }
    }

    internal fun str(key: String, def: String = ""): String = props.getProperty(key) ?: def
    internal fun put(key: String, value: String) {
        props.setProperty(key, value); save()
    }

    /** 在线更新源（留空 = 樱花主 + cpolar 自动发现）。自建多入口时可填自己的更新目录。 */
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

    /** 本机已生成/共享的 6 位匹配码（「我的」页在其它设备上同步用；持久化到 prefs）。 */
    var loginPairCode: String
        get() = str("login_pair_code")
        set(v) = put("login_pair_code", v.trim())

    /**
     * 签名服务：自用版（构建参数非空）**固定用构建值**，忽略本机旧配置 ——
     * 作者必须和普通用户走完全相同的公网通道，避免「我能用、别人不能用」的假正常。
     * 公开版构建为空，仍可在设置里自己填。
     */
    override var signUrl: String
        get() = if (BuildDefaults.SIGN_URL.isNotBlank()) BuildDefaults.SIGN_URL else str("sign_url")
        set(v) = put("sign_url", v.trim())

    /** 播放源：自用版固定用构建值（公网通道）；公开版构建为空时可自行填写。 */
    var playBases: String
        get() = if (BuildDefaults.PLAY_BASES.isNotBlank()) BuildDefaults.PLAY_BASES else str("play_bases")
        set(v) = put("play_bases", v.trim())

    /** 播放令牌（自用版固定构建值；公开版空则不带） */
    var playToken: String
        get() = if (BuildDefaults.PLAY_TOKEN.isNotBlank()) BuildDefaults.PLAY_TOKEN else str("play_token")
        set(v) = put("play_token", v.trim())

    /** 历史/云端接口基址：自用版固定构建值（公网）；公开版空则可配置。 */
    var historyBase: String
        get() = if (BuildDefaults.HISTORY_BASE.isNotBlank()) BuildDefaults.HISTORY_BASE else str("history_base")
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

    // ---------------- 登录用「本机唯一设备身份」（每机注册；shared MachineIdentity 提供） ----------------
    //
    // 开关 login_unique_device 默认 0 = 用公共身份（保持现有行为）；本机测时置 1：
    //  启动时读已注册身份 → MachineIdentity.install（仅完全有效才启用、签名同源）；
    //  未注册/无效则后台 MachineIdentity.register() → 私密持久化 → install；失败回退固定身份。
    // 私密约束：device_token / ttreq 只落本地 prefs.properties，不进公开配置、不打日志。
    private const val FALLBACK_LOGIN_DEVICE_ID = "23562609503024"
    private const val FALLBACK_LOGIN_IID = "23562609507120"
    private const val FALLBACK_LOGIN_CDID = "a694f0f8-266a-421a-bef3-ee760b049478"

    /** 是否启用「每机唯一登录身份」（保留字段但已不再用于门控；实际由 loginFallbackActive 决定）。 */
    val loginUniqueEnabled: Boolean get() = str("login_unique_device", "1") == "1"

    fun setLoginUniqueDevice(on: Boolean) = put("login_unique_device", if (on) "1" else "0")

    /** 上次登录被平台 1003 拒绝过：记住后下次启动不再强推每机身份，直接用固定身份保证能登。 */
    val loginFallbackActive: Boolean get() = str("login_unique_fallback", "0") == "1"

    fun setLoginFallback(on: Boolean) = put("login_unique_fallback", if (on) "1" else "0")

    /** 从本地 prefs 还原已注册的本机身份（token/ttreq 仅在本机文件）；未注册/不全返回 null。 */
    fun loadRegisteredDevice(): RegisteredDevice? {
        val did = str("login_device_id")
        val iid = str("login_iid")
        if (did.isBlank() || iid.isBlank()) return null
        return RegisteredDevice(
            deviceId = did,
            installId = iid,
            cdid = str("login_cdid"),
            openudid = str("login_openudid"),
            clientUdid = str("login_client_udid"),
            deviceToken = str("login_device_token"),
            ttreq = str("login_ttreq"),
            storeRegion = str("login_store_region"),
        )
    }

    /** 持久化注册身份。device_token/ttreq 属私密，只落本地 prefs，不进公开配置、不打日志。 */
    fun saveRegisteredDevice(reg: RegisteredDevice) {
        props.setProperty("login_device_id", reg.deviceId)
        props.setProperty("login_iid", reg.installId)
        props.setProperty("login_cdid", reg.cdid)
        props.setProperty("login_openudid", reg.openudid)
        props.setProperty("login_client_udid", reg.clientUdid)
        props.setProperty("login_device_token", reg.deviceToken)
        props.setProperty("login_ttreq", reg.ttreq)
        props.setProperty("login_store_region", reg.storeRegion)
        save()
    }

    fun clearRegisteredDevice() {
        listOf(
            "login_device_id", "login_iid", "login_cdid", "login_openudid",
            "login_client_udid", "login_device_token", "login_ttreq", "login_store_region",
        ).forEach { props.remove(it) }
        save()
    }

    /**
     * 登录请求用的 (device_id, iid, cdid)：
     * - 只有 **MachineIdentity.active 且 signUrl 为空（走本地 LocalSign、签名与请求同源）** 才用注册身份三项；
     * - 其余（含配置了远程签名服务 signUrl）一律回退固定三项 → 避免"请求用注册号、签名用远程固定号"不一致 1003。
     */
    fun loginIdentity(): Triple<String, String, String> {
        if (MachineIdentity.active && signUrl.isBlank() && !loginFallbackActive) {
            val reg = loadRegisteredDevice()
            if (reg != null && reg.identityValid) {
                return Triple(reg.deviceId, reg.installId, reg.cdid.ifBlank { FALLBACK_LOGIN_CDID })
            }
        }
        return Triple(FALLBACK_LOGIN_DEVICE_ID, FALLBACK_LOGIN_IID, FALLBACK_LOGIN_CDID)
    }

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

    // ---------------- 追更通知（实现见 PcNoticeStore.kt） ----------------

    /** 已读状态的通知列表（新→旧）。字段：id/seriesId/title/detail/at/read */
    fun followNotices(): List<JSONObject> = PcNoticeStore.followNotices()

    fun unreadNoticeCount(): Int = PcNoticeStore.unreadNoticeCount()

    /** 一轮更新检查：服务端集数 > 基线 → 落通知；首次见到的只记基线。返回新增数。 */
    fun checkFollowUpdates(): Int = PcNoticeStore.checkFollowUpdates()

    /** 追更时立刻把基线记成当前集数（避免「刚追完就报更新」）。 */
    fun seedFollowBaseline(seriesId: String, episodeCount: Int) =
        PcNoticeStore.seedFollowBaseline(seriesId, episodeCount)

    fun markNoticeRead(id: Long) = PcNoticeStore.markNoticeRead(id)

    fun markAllNoticesRead() = PcNoticeStore.markAllNoticesRead()

    fun clearNotices() = PcNoticeStore.clearNotices()


    /** 「开箱即用」新手提示是否已经看过（只弹一次，别每次冷启动都挡首屏） */
    var setupTipSeen: Boolean
        get() = str("setup_tip_seen", if (configured) "1" else "0") == "1"
        set(v) = put("setup_tip_seen", if (v) "1" else "0")

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

    // 四项和上面的开关一样用可观察镜像：直接读文件的话滑杆拖不动、数值也不刷新
    private val dmOpacityState = mutableStateOf(str("dm_opacity", "0.9").toFloatOrNull()?.coerceIn(0.1f, 1f) ?: 0.9f)
    private val dmFontState = mutableStateOf(str("dm_font", "1").toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 1f)
    private val dmDensityState = mutableStateOf(str("dm_density", "1").toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f)
    private val dmAreaState = mutableStateOf(str("dm_area", "1").toFloatOrNull()?.coerceIn(0.25f, 1f) ?: 1f)

    /** 文字不透明度 0.1–1，默认 0.9 */
    var danmakuOpacity: Float
        get() = dmOpacityState.value
        set(v) {
            val c = v.coerceIn(0.1f, 1f)
            dmOpacityState.value = c
            put("dm_opacity", c.toString())
        }

    /** 字号缩放 0.5–2（1 = 画面高度自适应基准字号） */
    var danmakuFontScale: Float
        get() = dmFontState.value
        set(v) {
            val c = v.coerceIn(0.5f, 2f)
            dmFontState.value = c
            put("dm_font", c.toString())
        }

    /** 密度 0–1（1 = 全部显示，入场按比例丢弃） */
    var danmakuDensity: Float
        get() = dmDensityState.value
        set(v) {
            val c = v.coerceIn(0f, 1f)
            dmDensityState.value = c
            put("dm_density", c.toString())
        }

    /** 显示区域 0.25–1（弹幕占画面顶部的高度比例） */
    var danmakuArea: Float
        get() = dmAreaState.value
        set(v) {
            val c = v.coerceIn(0.25f, 1f)
            dmAreaState.value = c
            put("dm_area", c.toString())
        }

    // ---------------- 观看历史（实现见 PcHistoryStore.kt） ----------------

    override fun contains(key: String, seriesId: String): Boolean = readSet(key).contains(seriesId)

    override fun history(): List<JSONObject> = PcHistoryStore.history()

    override fun mergeHistory(remote: List<JSONObject>): List<JSONObject> = PcHistoryStore.mergeHistory(remote)

    override fun updateHistoryCover(seriesId: String, cover: String) =
        PcHistoryStore.updateHistoryCover(seriesId, cover)

    fun recordPlayback(seriesId: String, title: String, cover: String, episode: Int, total: Int, positionMs: Long) =
        PcHistoryStore.recordPlayback(seriesId, title, cover, episode, total, positionMs)

    fun posOf(vid: String): Long = PcHistoryStore.posOf(vid)

    fun markPos(vid: String, positionMs: Long) = PcHistoryStore.markPos(vid, positionMs)

    fun removeHistory(seriesId: String) = PcHistoryStore.removeHistory(seriesId)

    fun clearHistory() = PcHistoryStore.clearHistory()


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

    // ---------------- 刷剧「跨天去重」：最近推过的 seriesId（FIFO，最多 300） ----------------

    /** 最近在刷剧里推过的 seriesId，按推入先后（旧→新）。 */
    fun feedSeenIds(): List<String> =
        str("feed_seen_ids", "").split('\u0001').filter { it.isNotBlank() }

    /** 把本次展示的 seriesId 追加进 feed_seen_ids：去重保序，只保留最近 300 个。 */
    fun recordFeedSeen(ids: List<String>) {
        if (ids.isEmpty()) return
        val ordered = LinkedHashSet<String>()
        feedSeenIds().forEach { if (it.isNotBlank()) ordered.add(it) }
        ids.forEach { sid ->
            if (sid.isNotBlank()) {
                ordered.remove(sid)   // 重新推到的挪到末尾 = 最新
                ordered.add(sid)
            }
        }
        val kept = ordered.toList().takeLast(300)
        put("feed_seen_ids", kept.joinToString("\u0001"))
    }
}
