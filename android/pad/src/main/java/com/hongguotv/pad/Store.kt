package com.hongguotv.pad

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地设置与观看数据。
 *
 * 存储格式（与旧版完全一致，升级后用户原有数据能直接读出来）：
 * - 简单值直接放 SharedPreferences
 * - 集合（收藏/点赞/追更/预约）用 \u0001 分隔的字符串
 * - 历史是 JSONArray，按标题去重、取已看集数最大的一条
 */
object Store : AccountState {

    const val KEY_FAVORITE = "favorite"
    const val KEY_LIKE = "like"
    const val KEY_FOLLOW = "follow"
    const val KEY_RESERVE = "reserve"
    private const val HISTORY_SCHEMA = HistoryRepository.SCHEMA

    private const val SEP = "\u0001"

    lateinit var appContext: Context
        private set

    private var prefs: SharedPreferences? = null

    private fun p(): SharedPreferences =
        prefs ?: error("Store 未初始化，先调用 Store.init(context)")

    /** 给 ShelfStore / HistoryStore 共用同一份 SharedPreferences。 */
    internal fun prefs(): SharedPreferences = p()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("hongguo_pad", Context.MODE_PRIVATE)
        migrateHistorySchema()
    }

    /** 历史行结构版本：清掉缺 seriesId/集数的脏行，补齐秒数/时间字段；只跑一次。 */
    private fun migrateHistorySchema() {
        val sp = p()
        if (sp.getInt("history_schema", 0) >= HISTORY_SCHEMA) return
        runCatching {
            val arr = JSONArray(sp.getString("history", "[]"))
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
            sp.edit().putString("history", out.toString())
                .putInt("history_schema", HISTORY_SCHEMA).apply()
        }
    }

    // ---------------- 播放设置 ----------------

    var autoNext: Boolean
        get() = p().getBoolean("auto_next", true)
        set(v) = p().edit().putBoolean("auto_next", v).apply()

    /** 看完（最后一集播完）自动删除观看记录——与 PC 设置项同名同义。 */
    var deleteAfterFinish: Boolean
        get() = p().getBoolean("delete_after_finish", false)
        set(v) = p().edit().putBoolean("delete_after_finish", v).apply()

    /**
     * 软件解码：画面出现**绿块 / 花屏**时打开。
     *
     * 有些电视的厂商硬解（实测小米 MiTV 的 Amlogic HEVC 解码器）遇到个别
     * HEVC 流会不停超时、丢帧，丢帧没填满的缓冲区显示出来就是一片片绿块，
     * 暂停时也定在那里。打开后强制走 Google 软件解码器，慢一点但画面是准的。
     */
    var softDecode: Boolean
        get() = p().getBoolean("soft_decode", false)
        set(v) = p().edit().putBoolean("soft_decode", v).apply()

    override var maxHeight: Int
        get() = p().getInt("max_height", 1080)
        set(v) = p().edit().putInt("max_height", v).apply()

    var speed: Float
        get() = p().getFloat("speed", 1.0f)
        set(v) = p().edit().putFloat("speed", v).apply()

    // ---------------- 追更未读角标（与 PC 同语义） ----------------

    /** 未读更新数：进「追更」页清零，每轮检查有新集就累加。 */
    var followBadge: Int
        get() = p().getInt("follow_badge", 0)
        set(v) = p().edit().putInt("follow_badge", v).apply()

    /**
     * 一轮更新检查：服务端集数 > 基线 → 未读数 +1 并把基线推到最新；
     * 第一次见到的剧只记基线（和 PC 的 checkFollowUpdates 同口径，PC 那边落通知列表，
     * 这里只记数字，够画侧栏角标）。
     */
    fun checkFollowUpdates(): Int {
        if (!loggedIn) return 0
        val page = Api.followUpdates()
        if (page.items.isEmpty()) return 0
        var added = 0
        page.items.forEach { item ->
            val key = "fb_${item.seriesId}"
            val baseline = p().getString(key, null)?.toIntOrNull()
            if (baseline == null) {
                p().edit().putString(key, item.episodeCount.toString()).apply()
                return@forEach
            }
            if (item.episodeCount <= baseline) return@forEach
            p().edit().putString(key, item.episodeCount.toString()).apply()
            added++
        }
        if (added > 0) followBadge = followBadge + added
        return added
    }

    /** 追更时立刻把基线记成当前集数（避免「刚追完就报更新」）。 */
    fun seedFollowBaseline(seriesId: String, episodeCount: Int) {
        p().edit().putString("fb_$seriesId", episodeCount.toString()).apply()
    }

    var darkTheme: Boolean
        get() = p().getBoolean("dark_theme", true)
        set(v) = p().edit().putBoolean("dark_theme", v).apply()

    // ---------------- 弹幕显示设置（与 Windows 版同一组口径） ----------------

    /** 弹幕总开关，默认开（原版播放器默认就有弹幕） */
    var danmakuOn: Boolean
        get() = p().getBoolean("dm_on", true)
        set(v) = p().edit().putBoolean("dm_on", v).apply()

    /** 透明度 0.1–1 */
    var dmOpacity: Float
        get() = p().getFloat("dm_opacity", 0.9f)
        set(v) = p().edit().putFloat("dm_opacity", v).apply()

    /** 字号倍率 0.5–2 */
    var dmFontScale: Float
        get() = p().getFloat("dm_font", 1.0f)
        set(v) = p().edit().putFloat("dm_font", v).apply()

    /** 密度 0–1：按比例随机丢弃同一屏的弹幕 */
    var dmDensity: Float
        get() = p().getFloat("dm_density", 0.6f)
        set(v) = p().edit().putFloat("dm_density", v).apply()

    /** 显示区域 0.25–1：弹幕只占画面上部这么高 */
    var dmArea: Float
        get() = p().getFloat("dm_area", 1.0f)
        set(v) = p().edit().putFloat("dm_area", v).apply()

    // ---------------- 账号 ----------------

    override var accountCookies: String
        get() = p().getString("account_cookies", "").orEmpty()
        set(v) = p().edit().putString("account_cookies", v).apply()

    override var accountName: String
        get() = p().getString("account_name", "").orEmpty()
        set(v) = p().edit().putString("account_name", v).apply()

    override var accountUid: String
        get() = p().getString("account_uid", "").orEmpty()
        set(v) = p().edit().putString("account_uid", v).apply()

    override var accountSyncedAt: Long
        get() = p().getLong("account_at", 0L)
        set(v) = p().edit().putLong("account_at", v).apply()

    override var accountSource: String
        get() = p().getString("account_source", "").orEmpty()
        set(v) = p().edit().putString("account_source", v).apply()

    /**
     * 发码时下发的 `passport_csrf_token`：提交验证码时必须当 cookie 带上，
     * 否则服务端不认这次验证码（表现为「验证码错误」，其实码是对的）。
     */
    var loginCsrf: String
        get() = p().getString("login_csrf", "").orEmpty()
        set(v) = p().edit().putString("login_csrf", v).apply()

    override val loggedIn: Boolean get() = accountCookies.isNotEmpty()

    var pairCode: String
        get() = p().getString("pair_code", "").orEmpty()
        set(v) = p().edit().putString("pair_code", v).apply()

    var pairEnabled: Boolean
        get() = p().getBoolean("pair_enabled", false)
        set(v) = p().edit().putBoolean("pair_enabled", v).apply()

    var pairUid: String
        get() = p().getString("pair_uid", "").orEmpty()
        set(v) = p().edit().putString("pair_uid", v).apply()

    var pairName: String
        get() = p().getString("pair_name", "").orEmpty()
        set(v) = p().edit().putString("pair_name", v).apply()

    override var signUrl: String
        get() = p().getString("sign_url", BuildConfig.DEFAULT_SIGN_URL).orEmpty()
        set(v) = p().edit().putString("sign_url", v.trim()).apply()

    fun clearAccount() {
        p().edit()
            .remove("account_cookies").remove("account_name").remove("account_uid")
            .remove("account_at").remove("account_source").remove("login_csrf")
            .remove("pair_uid").remove("pair_name").remove("pair_code")
            .apply()
        clearShelfCards()
    }

    // ---------------- 搜索历史 ----------------

    var searchHistory: List<String>
        get() = p().getString("search_history", "").orEmpty()
            .split(SEP).filter { it.isNotBlank() }
        set(v) = p().edit()
            .putString("search_history", v.take(12).joinToString(SEP))
            .apply()

    fun pushSearch(word: String) {
        if (word.isBlank()) return
        val list = searchHistory.toMutableList()
        list.remove(word)
        list.add(0, word)
        searchHistory = list
    }

    // ---------------- 刷剧：最近已推送（跨会话/跨天去重用） ----------------

    /**
     * 刷剧最近推送过的 seriesId（FIFO，保留最近 300 个）。
     * 进入刷剧时优先展示"没推过的"，避免隔天刷来刷去还是那几部；存最近 300 个防无限增长。
     */
    var feedSeenIds: List<String>
        get() = p().getString("feed_seen_ids", "").orEmpty()
            .split(SEP).filter { it.isNotBlank() }
        set(v) = p().edit()
            .putString("feed_seen_ids", v.takeLast(300).joinToString(SEP))
            .apply()

    // ---------------- 连播预取 ----------------

    /** 连播时向后预取几集（默认 5；0/负=不预取）。对齐 PC「预取集数」口径。 */
    var prefetchEps: Int
        get() = p().getInt("prefetch_eps", 5)
        set(v) = p().edit().putInt("prefetch_eps", v.coerceIn(0, 8)).apply()

    // ---------------- 集合类（收藏/点赞/追更/预约） ----------------
    // 实现已拆到 ShelfStore.kt；这里保留同名方法，调用方零改动。

    fun isUnsupported(vid: String): Boolean = ShelfStore.isUnsupported(vid)

    fun markUnsupported(vid: String) = ShelfStore.markUnsupported(vid)

    fun toggle(key: String, seriesId: String): Boolean = ShelfStore.toggle(key, seriesId)

    override fun contains(key: String, seriesId: String): Boolean = ShelfStore.contains(key, seriesId)

    fun ids(key: String): List<String> = ShelfStore.ids(key)

    fun toggleCard(key: String, card: SeriesCard): Boolean = ShelfStore.toggleCard(key, card)

    fun replaceCards(key: String, cards: List<SeriesCard>) = ShelfStore.replaceCards(key, cards)

    fun card(key: String, seriesId: String): SeriesCard? = ShelfStore.card(key, seriesId)

    fun cacheShelfCards(cards: List<SeriesCard>) = ShelfStore.cacheShelfCards(cards)

    fun clearShelfCards() = ShelfStore.clearShelfCards()

    fun shelfCard(seriesId: String): SeriesCard? = ShelfStore.shelfCard(seriesId)

    // ---------------- 观看历史（实现见 HistoryStore.kt） ----------------

    override fun history(): List<JSONObject> = HistoryStore.history()

    fun recordPlayback(
        seriesId: String,
        title: String,
        cover: String,
        episode: Int,
        total: Int,
        positionMs: Long,
    ) = HistoryStore.recordPlayback(seriesId, title, cover, episode, total, positionMs)

    fun markPos(vid: String, ms: Long) = HistoryStore.markPos(vid, ms)

    fun posOf(vid: String): Long = HistoryStore.posOf(vid)

    fun progress(seriesId: String): Int = HistoryStore.progress(seriesId)

    val historyStamp: MutableState<Long> get() = HistoryStore.historyStamp

    override fun mergeHistory(remote: List<JSONObject>): List<JSONObject> = HistoryStore.mergeHistory(remote)

    fun clearHistory() = HistoryStore.clearHistory()

    override fun updateHistoryCover(seriesId: String, cover: String) =
        HistoryStore.updateHistoryCover(seriesId, cover)

    fun removeHistory(seriesId: String) = HistoryStore.removeHistory(seriesId)

    /** 缓存目录当前占用多少（给设置页显示用，不动文件）。 */
    fun cacheSizeText(context: Context): String {
        var total = 0L
        runCatching {
            context.cacheDir?.listFiles()?.forEach { f ->
                total += f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            }
        }
        return if (total >= 1024L * 1024 * 1024) {
            "%.1f GB".format(total / 1024.0 / 1024.0 / 1024.0)
        } else {
            "%.1f MB".format(total / 1024.0 / 1024.0)
        }
    }

    /**
     * 自动清理缓存（启动时后台跑）：
     *  - VVC 转换目录超过 7 天没碰过 → 删
     *  - 总量超过 800MB → 从最旧的删到 400MB 以下
     * 封面图由 Coil 自己的磁盘缓存管理（超限自动淘汰），这里只管我们自己的大文件。
     */
    fun autoCleanCache(context: Context) {
        // VVC 播放页已删除，旧的 vvc 转换缓存不再使用，直接清掉
        runCatching { File(context.cacheDir, "vvc").deleteRecursively() }
    }

    /** 清缓存目录，返回给用户看的结果文案。 */
    fun clearCache(context: Context): String {
        var total = 0L
        context.cacheDir?.listFiles()?.forEach { f ->
            total += f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            f.deleteRecursively()
        }
        DetailCache.clear()
        return "已清理缓存，释放 %.1f MB".format(total / 1024.0 / 1024.0)
    }
}
