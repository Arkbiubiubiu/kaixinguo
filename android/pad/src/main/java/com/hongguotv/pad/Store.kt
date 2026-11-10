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

    private const val SEP = "\u0001"

    lateinit var appContext: Context
        private set

    private var prefs: SharedPreferences? = null

    private fun p(): SharedPreferences =
        prefs ?: error("Store 未初始化，先调用 Store.init(context)")

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("hongguo_pad", Context.MODE_PRIVATE)
    }

    // ---------------- 播放设置 ----------------

    var autoNext: Boolean
        get() = p().getBoolean("auto_next", true)
        set(v) = p().edit().putBoolean("auto_next", v).apply()

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
            .remove("pair_uid").remove("pair_name")
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

    // ---------------- 集合类（收藏/点赞/追更/预约） ----------------

    private fun readSet(key: String): LinkedHashSet<String> {
        val raw = p().getString(key, "").orEmpty()
        return LinkedHashSet(raw.split(SEP).filter { it.isNotBlank() })
    }

    private fun writeSet(key: String, set: Collection<String>) {
        p().edit().putString(key, set.joinToString(SEP)).apply()
    }

    /** 切换某个 id 在集合里的状态，返回切换后是否在集合中。 */
    fun toggle(key: String, seriesId: String): Boolean {
        val set = readSet(key)
        val on = if (set.contains(seriesId)) {
            set.remove(seriesId); false
        } else {
            set.add(seriesId); true
        }
        writeSet(key, set)
        return on
    }

    override fun contains(key: String, seriesId: String): Boolean = readSet(key).contains(seriesId)

    fun ids(key: String): List<String> = readSet(key).toList()

    /** 切换并把卡片信息一并存下来，列表页不用再请求详情。 */
    fun toggleCard(key: String, card: SeriesCard): Boolean {
        val on = toggle(key, card.seriesId)
        if (on) {
            val arr = runCatching { JSONArray(p().getString("cards_$key", "[]")) }
                .getOrDefault(JSONArray())
            arr.put(card.toJson())
            p().edit().putString("cards_$key", arr.toString()).apply()
        }
        return on
    }

    /** 整体替换某个列表（云端为准时用）。不能循环 toggle：id 已存在时 toggle 会把它删掉。 */
    fun replaceCards(key: String, cards: List<SeriesCard>) {
        writeSet(key, cards.map { it.seriesId }.distinct())
        val arr = JSONArray()
        cards.forEach { arr.put(it.toJson()) }
        p().edit().putString("cards_$key", arr.toString()).apply()
    }

    fun card(key: String, seriesId: String): SeriesCard? {
        val arr = runCatching { JSONArray(p().getString("cards_$key", "[]")) }
            .getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("seriesId") == seriesId) return o.toCard()
        }
        return null
    }

    fun cacheShelfCards(cards: List<SeriesCard>) {
        val arr = JSONArray()
        cards.forEach { arr.put(it.toJson()) }
        p().edit().putString("shelf_cards", arr.toString()).apply()
    }

    fun clearShelfCards() {
        p().edit().remove("shelf_cards").apply()
    }

    fun shelfCard(seriesId: String): SeriesCard? {
        val arr = runCatching { JSONArray(p().getString("shelf_cards", "[]")) }
            .getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("seriesId") != seriesId) continue
            if (o.optString("title").isEmpty()) return null
            return o.toCard()
        }
        return null
    }

    private fun SeriesCard.toJson(): JSONObject = JSONObject()
        .put("seriesId", seriesId).put("title", title).put("cover", cover)
        .put("episodeCount", episodeCount).put("playText", playText)
        .put("score", score).put("vid", vid).put("contentType", contentType)
        .put("tags", JSONArray(tags))

    private fun JSONObject.toCard(): SeriesCard {
        val tags = mutableListOf<String>()
        optJSONArray("tags")?.let { a ->
            for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let(tags::add)
        }
        return SeriesCard(
            seriesId = optString("seriesId"),
            vid = optString("vid"),
            title = optString("title"),
            cover = optString("cover"),
            episodeCount = optInt("episodeCount"),
            score = optString("score"),
            playText = optString("playText"),
            tags = tags,
            contentType = optInt("contentType", 1004),
        )
    }

    // ---------------- 观看历史 ----------------

    /** 历史列表：同标题只留「看得最多」的一条，按时间倒序。 */
    override fun history(): List<JSONObject> = runCatching {
        val arr = JSONArray(p().getString("history", "[]"))
        val rows = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        rows.groupBy { it.optString("title") }.values
            .mapNotNull { same ->
                same.maxWithOrNull(compareBy({ it.optInt("episode", 0) }, { it.optLong("at") }))
            }
            .sortedByDescending { it.optLong("at") }
    }.getOrDefault(emptyList())

    /**
     * 记一条观看历史。
     *
     * **进度只增不减**：历史回答的是「我看到哪了」，不是「我最后点了哪一集」。
     * 从推荐/搜索点进一部看了一半的剧时，详情页默认从第 1 集起播，
     * 一次就会把「看到 136 集」改写成 1 —— 所以这里取两者的较大值。
     */
    fun record(
        seriesId: String,
        title: String,
        cover: String,
        episode: Int,
        total: Int,
        allowRewind: Boolean = false,
    ) {
        val rows = history()
        val oldEp = rows.firstOrNull { it.optString("seriesId") == seriesId }
            ?.optInt("episode", 0) ?: 0
        val keep = if (allowRewind) episode else maxOf(oldEp, episode)

        val list = rows.filter { it.optString("seriesId") != seriesId }.toMutableList()
        list.add(
            0,
            JSONObject()
                .put("seriesId", seriesId).put("title", title).put("cover", cover)
                .put("episode", keep).put("total", total)
                .put("at", System.currentTimeMillis()),
        )
        val arr = JSONArray()
        list.take(200).forEach { arr.put(it) }
        p().edit().putString("history", arr.toString())
            .putInt("progress_$seriesId", keep)
            .apply()
    }

    // ---------------- 秒级位置（只在本次进程内有效） ----------------
    //
    // 用来解决「从播放页点详情、返回后这一集从头开始」：导航栈只存集号，
    // 播放页一销毁 ExoPlayer 就没了。这里按 vid 记一个秒数，回来后 seek 过去。
    // 故意不放 SharedPreferences —— 跨进程沿用旧位置反而容易让人困惑（比如想重看某集）。
    private val lastPos = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 记下某集看到哪儿。开头 5 秒内不记，免得「刚点进去就退出」也存个 0 秒。
     *
     * 落盘（`pos_<vid>`）：以前只存内存，App 一重启历史就只能从这一集的开头看。
     * 按 5 秒节流，避免每秒写一次盘。
     */
    fun markPos(vid: String, ms: Long) {
        if (vid.isEmpty()) return
        val key = "pos_$vid"
        if (ms > 5_000) {
            lastPos[vid] = ms
            val saved = runCatching { p().getLong(key, 0L) }.getOrDefault(0L)
            if (kotlin.math.abs(ms - saved) >= 5_000) {
                runCatching { p().edit().putLong(key, ms).apply() }
            }
        } else {
            lastPos.remove(vid)
            // 从头看了：把旧的续播点清掉，否则下次又跳回老位置
            runCatching { p().edit().remove(key).apply() }
        }
    }

    /** 该集上次看到的毫秒数；内存没有就读盘（跨进程也能接着看）。 */
    fun posOf(vid: String): Long =
        if (vid.isEmpty()) 0L
        else lastPos[vid] ?: runCatching { p().getLong("pos_$vid", 0L) }.getOrDefault(0L)

    /** 该剧看到第几集（本地进度与历史取较大值）。 */
    fun progress(seriesId: String): Int {
        val fromPrefs = p().getInt("progress_$seriesId", 0)
        val fromHistory = history().firstOrNull { it.optString("seriesId") == seriesId }
            ?.optInt("episode", 0) ?: 0
        return maxOf(fromPrefs, fromHistory)
    }

    /** 历史变化计数：界面靠它感知「别处写入了历史」，不用轮询。 */
    val historyStamp: MutableState<Long> = mutableStateOf(0L)

    /** 与云端历史合并：同一条目按集数取大（集数相同才比时间）。 */
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
                    (row.optInt("episode", 0) == old.optInt("episode", 0) &&
                        row.optLong("at") > old.optLong("at"))
                val win = if (rowBetter) row else old
                val lose = if (rowBetter) old else row
                // 空字段互补：历史记录里 cover 曾是空串，合并后不应把已有封面覆盖成空
                if (win.optString("cover").isEmpty()) win.put("cover", lose.optString("cover"))
                if (win.optString("title").isEmpty()) win.put("title", lose.optString("title"))
                merged[id] = win
            }
        }
        // 官方观看历史同账号实测 1500+ 条，本地只留最近的 500 条（prefs 体积可控，够翻很久）
        val list = merged.values.sortedByDescending { it.optLong("at") }.take(500)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        val editor = p().edit().putString("history", arr.toString())
        list.forEach { editor.putInt("progress_${it.optString("seriesId")}", it.optInt("episode")) }
        editor.apply()
        historyStamp.value = System.currentTimeMillis()
        return list
    }

    fun clearHistory() {
        p().edit().remove("history").apply()
    }

    /** 补一条历史的封面（早期版本写历史时 cover 写死空串，留下脏数据）。 */
    override fun updateHistoryCover(seriesId: String, cover: String) {
        val arr = JSONArray()
        history().forEach { r ->
            if (r.optString("seriesId") == seriesId) r.put("cover", cover)
            arr.put(r)
        }
        p().edit().putString("history", arr.toString()).apply()
    }

    fun removeHistory(seriesId: String) {
        val arr = JSONArray()
        history().filter { it.optString("seriesId") != seriesId }.forEach { arr.put(it) }
        p().edit().putString("history", arr.toString())
            .remove("progress_$seriesId").apply()
    }

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
