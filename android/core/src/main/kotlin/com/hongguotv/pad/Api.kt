package com.hongguotv.pad

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream

/**
 * 番茄接口访问层。
 *
 * 两条路：
 * 1. **服务器代取**（优先）：POST 到自己的 /api/fetch，由服务器去签、去拉、去缓存。
 *    这样列表只拉一次、多端共享，客户端也不用自己算签名。
 * 2. **本机直连**：服务器不通时兜底，走 request()。
 *
 * 设备参数是**固定值**——每次随机生成 iid/device_id 会被当成新设备，批量取流直接
 * 返回 HTTP 200 空包（实测随机身份命中率只有 20%，固定身份 100%）。
 */
/** 剧评条目 / 页。 */
data class ReviewItem(val user: String, val avatar: String, val text: String, val time: String, val likes: String)
data class ReviewPage(
    val items: List<ReviewItem> = emptyList(),
    val cursor: String = "",
    val hasMore: Boolean = false,
    /** 评分（extra.credibility_score，与 PC 端同源）；空 = 暂无评分 */
    val score: String = "",
    /** 评分人数（extra.credibility_score_count） */
    val scoreCount: Int = 0,
    /** 剧评总数（common_list_info.total，与 PC 的 commentTotal 同源） */
    val total: Int = 0,
)

/** 演职员资料。 */
data class CreatorProfileInfo(val id: String, val name: String, val avatar: String, val desc: String)

object Api {

    const val UA = "com.phoenix.read/74232 (Linux; U; Android 14; zh_CN; Xiaomi 14; Build/UKQ1.230804.001;tt-ok/3.12.13.20)"
    private const val PLAY_UA = "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C)"
    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val REFERER = "https://novelquickapp.com/"
    private const val HOST = "https://api5-normal-lq.fqnovel.com"
    private const val LAND_ORIGIN = "https://api5-normal-lq.fqnovel.com"
    private const val LAND_PATH = "/reading/distribution/category/landpage/v1/"
    private const val DETAIL_PATH = "/novel/player/video_detail/v1/"
    private const val RANK_CELL = "7470092475068071998"
    private const val NEW_CELL = "7431550523368554558"
    const val DOWN_STATUS = "已下架"

    private val PLAY_TOKEN: String get() = App.playToken
    private val MULTI_URL: String
        get() {
            val iid = App.deviceIid.ifBlank { "1000000000000000" }
            val did = App.deviceId.ifBlank { "1000000000000000" }
            val cdid = App.deviceCdid.ifBlank { "00000000-0000-0000-0000-000000000000" }
            return "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/" +
                "?iid=$iid&device_id=$did&aid=1967&version_code=71332" +
                "&version_name=7.1.3.32&device_level=1&update_version_code=71332&device_brand=Redmi" +
                "&os_version=16&device_platform=android&cdid=$cdid"
        }
    private val HISTORY_BASES: List<String> get() = App.historyBase.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()

    /** 自己的服务器：局域网优先（40ms），出门自动走公网隧道。 */
    private val PLAY_BASES: List<String> get() = App.playBases

    /** 本机直连取流开关：上游要求签名头之后这条路已失效，默认关闭（见 play() 注释）。 */
    private const val LOCAL_DIRECT_PLAY = false
    @Volatile
    private var preferredBase: String? = null

    /** 固定设备身份（与旧版一致，别改）。 */
    val device: LinkedHashMap<String, String> get() = linkedMapOf(
        "aid" to "8662",
        "app_name" to "novelread",
        "ac" to "wifi",
        "channel" to "xiaomi_8662_64",
        "device_platform" to "android",
        "device_brand" to "xiaomi",
        "device_type" to "23127PN0CC",
        "device_id" to App.deviceId.ifBlank { "1000000000000000" },
        "iid" to App.deviceIid.ifBlank { "1000000000000000" },
        "cdid" to App.deviceCdid.ifBlank { "00000000-0000-0000-0000-000000000000" },
        "os" to "android",
        "os_version" to "14",
        "os_api" to "34",
        "resolution" to "1200*2670",
        "dpi" to "460",
        "host_abi" to "arm64-v8a",
        "language" to "zh",
        "version_code" to "74232",
        "version_name" to "7.4.2.32",
        "manifest_version_code" to "74232",
        "update_version_code" to "74232",
        "pv_player" to "74232",
        "device_level" to "3",
        "dragon_device_type" to "phone",
        "is_android_pad_screen" to "1",
        "is_horizontal_screen" to "true",
        "compliance_status" to "0",
        "player_so_load" to "1",
        "ssmix" to "a",
    )

    private fun serverBases(): List<String> {
        val pref = preferredBase ?: return PLAY_BASES
        return listOf(pref) + PLAY_BASES.filter { it != pref }
    }

    private fun baseQuery(): String = device.entries.joinToString("&") { "${it.key}=${it.value}" }

    // ---------------- 底层请求 ----------------

    /**
     * 走本机直连发一个请求。body 为 null 表示 GET。
     * 返回响应文本；空响应直接抛异常（否则上层拿到空串会误当成功）。
     */
    private fun requestOnce(url: String, body: String?, ua: String, token: String = ""): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = if (body == null) "GET" else "POST"
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("lc", "101")
            conn.setRequestProperty("x-ss-dp", "8662")
            conn.setRequestProperty("Referer", REFERER)
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (App.state.loggedIn) conn.setRequestProperty("Cookie", App.state.accountCookies)
            if (body != null) conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotEmpty()) conn.setRequestProperty("X-Token", token)
            conn.connectTimeout = 12_000
            conn.readTimeout = 25_000
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isEmpty()) error("接口返回空内容（HTTP $code）")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** 带签名服务兜底：直连失败时依次试自己的服务器 /api/sign。 */
    private fun request(url: String, body: String?, ua: String = UA): String {
        if (body != null && url.endsWith("/sign")) {
            runCatching { return requestOnce(url, body, ua) }
            for (base in PLAY_BASES) {
                runCatching {
                    return requestOnce(base.trimEnd('/') + "/api/sign", body, ua, PLAY_TOKEN)
                }
            }
            throw IOException("签名服务不可用（局域网和公网都不通）")
        }
        return requestOnce(url, body, ua)
    }

    private fun get(url: String): String = request(url, null)

    /**
     * 服务器代取（不签名的普通 GET）。
     * 服务器会缓存并压缩，返回值里的 text 就是上游原文。
     */
    private fun viaServerUrl(url: String): String? = viaServerUrlEx(url).first

    /**
     * 同 viaServerUrl，但把「这份数据多旧」也带出来（服务端 age 字段）。
     *
     * 榜单页要显示真实的数据时间：age=0 表示本次现取，>0 表示命中了服务端缓存
     * （榜单类接口缓存 5 分钟）。不把 age 带出来，界面只能瞎写一个时间。
     */
    private fun viaServerUrlEx(url: String): Pair<String?, Double> {
        val payload = JSONObject().put("url", url).put("ua", UA)
        if (App.state.loggedIn) payload.put("cookie", App.state.accountCookies)
        for (base in serverBases()) {
            val reply = postFetch(base, payload)
            if (reply != null && reply.text.isNotEmpty()) {
                preferredBase = base
                return reply.text to reply.ageSec
            }
        }
        return null to 0.0
    }

    /** 服务器代取（需要签名的接口，服务器侧代签）。 */
    private fun viaServer(payload: JSONObject, extras: Map<String, String> = emptyMap()): String? {
        val body = JSONObject()
            .put("origin", payload.optString("origin"))
            .put("path", payload.optString("path"))
            .put("query", payload.optJSONArray("query") ?: JSONArray())
            .put("body", payload.optString("body"))
        if (extras.isNotEmpty()) {
            val h = JSONObject()
            extras.forEach { (k, v) -> h.put(k, v) }
            body.put("headers", h)
        }
        if (App.state.loggedIn) body.put("cookie", App.state.accountCookies)
        for (base in serverBases()) {
            val reply = postFetch(base, body)
            if (reply != null && reply.text.isNotEmpty()) {
                preferredBase = base
                return reply.text
            }
        }
        // 两个入口都失败：大概率是记住的入口出了问题，下次重新从局域网试起
        preferredBase = null
        return null
    }

    /** 代取结果：text 是上游原文，ageSec 是这份数据离「从上游拉下来」过去的秒数。 */
    private data class FetchReply(val text: String, val ageSec: Double)

    /** POST 一次 /api/fetch，按 Content-Encoding 解 gzip，返回 text 与数据年龄。 */
    private fun postFetch(base: String, payload: JSONObject): FetchReply? = runCatching {
        val conn = URL(base.trimEnd('/') + "/api/fetch").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Token", PLAY_TOKEN)
            conn.setRequestProperty("Accept-Encoding", "gzip")
            // 内网地址不在同网段时别死等；但签名接口首包要 1.4s+，2 秒会误杀
            conn.connectTimeout = if (base.contains("192.168.")) 4_000 else 6_000
            conn.readTimeout = 25_000
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) return@runCatching null
            val raw = conn.inputStream
            val decoded = if ((conn.contentEncoding ?: "").contains("gzip", true)) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            val obj = JSONObject(decoded.bufferedReader().use { it.readText() })
            val t = obj.optString("text")
            if (t.isEmpty()) null else FetchReply(t, obj.optDouble("age", 0.0))
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** 代签服务地址（设置页可改，默认局域网 9100）。 */
    private fun signBase(): String =
        App.state.signUrl.trimEnd('/')

    /**
     * 代签：拿回签名后的完整 url 与必须带上的请求头。
     *
     * 为什么要自己发而不是让服务器代取：
     * 上游对「签名头 + 真实 UA/设备头」的请求才回完整数据。服务器代取只能带固定头，
     * 实测详情接口会降级返回（video_data 15 个字段、video_list 为空、无演职员、无评分），
     * 而代签后客户端自己带 x-argus/x-gorgon/x-khronos 等头发，能拿到 47 个字段、146 集。
     */
    private fun signEndpoint(
        origin: String,
        path: String,
        query: JSONArray,
        body: String?,
    ): Pair<String, JSONObject>? {
        val payload = JSONObject().put("origin", origin).put("path", path)
            .put("query", query).put("body", body ?: "")
        val base = signBase()
        if (base.isEmpty()) {
            Log.e("PadApi", "未配置签名服务地址（设置 → 签名服务）")
            return null
        }
        val text = runCatching { request(base + "/sign", payload.toString()) }.getOrNull()
            ?: return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val url = o.optString("url")
        if (url.isEmpty()) return null
        return url to (o.optJSONObject("headers") ?: JSONObject())
    }

    /** 按签名返回的 headers 原样发请求（键值逐个透传，额外头可覆盖）。 */
    private fun requestWithHeaders(
        url: String,
        body: String?,
        headers: JSONObject,
        extras: Map<String, String> = emptyMap(),
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = if (body == null) "GET" else "POST"
            val merged = LinkedHashMap<String, String>()
            headers.keys().forEach { k -> merged[k] = headers.optString(k) }
            merged.putAll(extras)
            merged.forEach { (k, v) ->
                if (k.equals("Accept-Encoding", true)) return@forEach
                conn.setRequestProperty(k, v)
            }
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (App.state.loggedIn) conn.setRequestProperty("Cookie", App.state.accountCookies)
            conn.connectTimeout = 12_000
            conn.readTimeout = 25_000
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isEmpty()) error("接口返回空内容（HTTP $code）")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** 代签 + 客户端直发；失败才退回服务器代取（兜底，可能拿到降级数据）。 */
    private fun signedGet(
        path: String,
        query: JSONArray = JSONArray(),
        extraHeaders: Map<String, String> = emptyMap(),
        origin: String = HOST,
    ): String? {
        signEndpoint(origin, path, query, null)?.let { (url, headers) ->
            runCatching { return requestWithHeaders(url, null, headers, extraHeaders) }
        }
        val payload = JSONObject().put("origin", origin).put("path", path)
            .put("query", query).put("body", "")
        return viaServer(payload, extraHeaders)
    }

    private fun signedPost(
        path: String,
        body: String,
        query: JSONArray = JSONArray(),
        origin: String = HOST,
    ): String? {
        signEndpoint(origin, path, query, body)?.let { (url, headers) ->
            runCatching { return requestWithHeaders(url, body, headers) }
        }
        val payload = JSONObject().put("origin", origin).put("path", path)
            .put("query", query).put("body", body.asSignBody())
        return viaServer(payload)
    }

    /** 写操作是否成功：严格按上游 code==0 判定，其它情况一律算失败（交给 UI 回滚）。 */
    private fun writeOk(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val code = runCatching { JSONObject(text).optInt("code", -1) }.getOrDefault(-1)
        if (code != 0) Log.d("PadWrite", "write failed code=$code body=${text.take(120)}")
        return code == 0
    }

    /** body 尽量以 JSON 对象进签名（见 fetchSeriesData 注释）。 */
    private fun String.asSignBody(): Any =
        runCatching { JSONObject(this) }.getOrDefault(this)

    // ---------------- 取流 ----------------

    /**
     * 取播放地址：先让服务器代取（服务器签名 + 固定设备身份，命中率高），
     * 拿不到才本机直连。
     */
    fun play(vid: String, contentType: Int = 1004, want: Int = 0): PlayInfo {
        Log.d("PadPlayer", "play() vid=$vid ct=$contentType want=$want")
        playFromServer(vid, want)?.let {
            Log.d("PadPlayer", "playFromServer ok ${it.definition} ${it.size}")
            return it
        }
        // 本机直连从 2026-10-30 15:32 起已彻底失效：上游取流网关要求官方签名头
        // （X-Argus/X-Gorgon/X-Ladon），OkHttp 在这里发多少次都是 HTTP 200 空包，
        // 唯一的效果是让用户白等 30 秒连接+读超时才看到报错。
        // 服务器那边的签名通道已经接管，这里直接快速失败；留开关是为了上游哪天放开能一键恢复。
        if (!LOCAL_DIRECT_PLAY) {
            Log.d("PadPlayer", "playFromServer miss，本机直连已停用")
            error("取流失败：服务器没有返回播放地址")
        }
        Log.d("PadPlayer", "playFromServer miss，走本机直连")

        val payload = JSONObject()
            .put(
                "biz_param",
                JSONObject()
                    .put("detail_page_version", 0).put("device_level", 1)
                    .put("disable_digg_stat", false).put("need_all_video_definition", false)
                    .put("need_mp4_align", false).put("use_os_player", false)
                    .put("use_server_dns", false).put("video_platform", 1024),
            )
            .put(
                "mixed_video_id_map",
                JSONObject().put(if (contentType > 0) contentType.toString() else "1004", JSONArray().put(vid)),
            )
            .toString()

        val url = MULTI_URL

        val data = JSONObject(request(url, payload, PLAY_UA)).optJSONObject("data")
            ?: error("取流返回为空（该集可能已下线）")
        var model = data.optJSONObject(vid)
            ?: data.optJSONObject(data.keys().next())
            ?: error("取流返回为空")
        if (model.optString("video_model").startsWith("{")) {
            model = JSONObject(model.getString("video_model"))
        }
        val list = model.optJSONArray("video_list") ?: error("没有可播放档位")

        // 选不高于「清晰度上限」的最高一档
        var best: JSONObject? = null
        var bestH = -1
        val cap = if (want > 0) want else App.state.maxHeight
        for (i in 0 until list.length()) {
            val v = list.optJSONObject(i) ?: continue
            val meta = v.optJSONObject("video_meta")
            val h = meta?.optString("definition")?.trimEnd('p')?.toIntOrNull()
                ?: meta?.optInt("vheight", 0) ?: 0
            if (h in 1..cap && h > bestH) {
                bestH = h
                best = v
            }
        }
        if (best == null) {
            for (i in 0 until list.length()) {
                val v = list.optJSONObject(i) ?: continue
                val meta = v.optJSONObject("video_meta")
                val h = meta?.optString("definition")?.trimEnd('p')?.toIntOrNull() ?: 0
                if (h > bestH) {
                    bestH = h
                    best = v
                }
            }
        }
        val chosen = best ?: error("没有可播放档位")
        val meta = chosen.optJSONObject("video_meta")
        val spade = chosen.optJSONObject("encrypt_info")?.optString("spade_a").orEmpty()
        val key = spade.takeIf { it.isNotEmpty() }
            ?.let { runCatching { MediaCrypto.deriveKey(it) }.getOrNull() }
        return PlayInfo(
            url = chosen.optString("main_url"),
            backupUrl = chosen.optString("backup_url"),
            key = key,
            definition = meta?.optString("definition").orEmpty().ifEmpty { "${bestH}p" },
            height = if (bestH > 0) bestH else 720,
            duration = model.optDouble("video_duration", 0.0).toInt(),
            size = meta?.optLong("size", 0L) ?: 0L,
        )
    }

    /**
     * POST /api/playinfo，返回 data 对象（服务器签名 + 固定设备身份）。
     *
     * 番茄取流网关按 TLS 指纹放行，手机/电视上的 OkHttp 一条也拿不到，
     * 所以取流一律走服务器代做，客户端只拿解密后的直链。
     */
    private fun playInfoData(vid: String, want: String): JSONObject? {
        if (PLAY_BASES.isEmpty()) {
            Log.e("PadApi", "未配置播放信息服务地址")
            return null
        }
        val body = JSONObject().put("vid", vid).put("want", want).toString()
        for (base in serverBases()) {
            val data = runCatching {
                val conn = URL(base.trimEnd('/') + "/api/playinfo").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("X-Token", PLAY_TOKEN)
                    conn.setRequestProperty("Accept-Encoding", "gzip")
                    conn.connectTimeout = if (base.contains("192.168.")) 2_500 else 8_000
                    conn.readTimeout = 25_000
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    val code = conn.responseCode
                    if (code !in 200..299) return@runCatching null
                    val raw = conn.inputStream
                    // 服务器统一 gzip：要了 gzip 就必须解压，否则 JSON 解析必然失败
                    val decoded = if ((conn.contentEncoding ?: "").contains("gzip", true)) {
                        GZIPInputStream(raw)
                    } else {
                        raw
                    }
                    JSONObject(decoded.bufferedReader().use { it.readText() }).optJSONObject("data")
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (data != null) {
                preferredBase = base
                return data
            }
        }
        return null
    }

    /** 服务器代取播放地址（服务器签名 + 固定设备身份）。 */
    private fun playFromServer(vid: String, want: Int = 0): PlayInfo? {
        val target = if (want > 0) want else App.state.maxHeight
        val data = playInfoData(vid, "${target}p") ?: run {
            Log.d("PadPlayer", "playinfo miss want=${target}p")
            return null
        }
        val url = data.optString("url")
        if (!url.startsWith("http")) return null
        val spade = data.optString("spade_a")
        val height = data.optInt("height").takeIf { h -> h > 0 } ?: 720
        Log.d("PadPlayer", "playinfo ok ${data.optString("definition")} want=${target}p")
        return PlayInfo(
            url = url,
            backupUrl = data.optString("backup"),
            key = spade.takeIf { s -> s.isNotEmpty() }
                ?.let { s -> runCatching { MediaCrypto.deriveKey(s) }.getOrNull() },
            definition = data.optString("definition").ifEmpty { "${height}p" },
            height = height,
            duration = 0,
            size = data.optLong("size"),
        )
    }

    // ---------------- JSON → 卡片 ----------------

    /** 从列表 JSON 里猜标签：category_schema 的前几项 name。 */
    fun parseTags(schema: String?): List<String> = runCatching {
        val arr = JSONArray(schema.orEmpty())
        (0 until minOf(arr.length(), 4)).mapNotNull {
            arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotEmpty() }
        }
    }.getOrDefault(emptyList())

    /** 列表里的一个 book 节点 → 卡片。字段名与上游保持一致。 */
    /**
     * video_data 条目 → 卡片。字段带三层兜底：本条 → video_detail → 空串。
     * 没有 series_id 或标题的直接丢掉（首页见过空壳节点）。
     */
    fun toCard(v: JSONObject): SeriesCard? {
        val vd = v.optJSONObject("video_detail")
        val id = v.optString("series_id").ifEmpty { vd?.optString("series_id") ?: "" }
        if (id.isEmpty()) return null
        var title = v.optString("title")
        if (title.isEmpty()) title = vd?.optString("series_title") ?: ""
        if (title.isEmpty()) title = v.optString("series_title")
        if (title.isEmpty()) return null
        val cover = v.optString("cover").ifEmpty { vd?.optString("series_cover") ?: "" }
        val epCnt = v.optString("episode_cnt").toIntOrNull()
            ?: vd?.optString("episode_cnt")?.toIntOrNull() ?: 0
        val score = v.optString("score").ifEmpty { vd?.optString("score") ?: "" }
        // 热度：卡片统一显示「🔥 4389万」这种纯数字，不带「播放 / 人在追」字样。
        // 之前直接把 rec_text 的「共377万人在看」当播放量，所以有的卡有字、有的没有，
        // 而且文案五花八门。数值按可靠度取：
        //   video_detail.series_play_cnt → 顶层 series_play_cnt → play_cnt → 文案里抠。
        // 热度：卡片统一显示红果「热度值」。
        // 注意 play_cnt 是「播放次数」，不是热度——详情页「4993万热度」= hot_score，
        // 卡片原来显示的 786万 = play_cnt，两个是不同指标（用户实测发现对不上）。
        // 热度在列表数据里就有，取值优先级：
        //   ① video_detail.hot_score（详情形态，数值最准）
        //   ② rec_text 文本（搜索接口："4993万热度"）
        //   ③ sub_title_list 里 data_type=27（找剧 landpage："1857万"）
        //   ④ 实在没有热度才退回播放次数（老行为，避免卡片没数字）
        val hotNum = sequenceOf(
            vd?.optLong("hot_score") ?: 0L,
            v.optLong("hot_score"),
        ).firstOrNull { it > 0 } ?: 0L
        val playCnt = sequenceOf(
            vd?.optLong("series_play_cnt") ?: 0L,
            v.optLong("series_play_cnt"),
            v.optString("play_cnt").toLongOrNull() ?: 0L,
        ).firstOrNull { it > 0 } ?: 0L
        // 卡片上的数字只显示**热度**，拿不到热度就空着。
        // 不能再退回播放次数顶替：实测《无限回档48小时极限成神》热度 4416万、
        // 播放次数只有 353万，用播放次数顶上就会出现「卡片 353万 / 详情 4416万」，
        // 用户看到的就是数据错乱。宁可这张卡片没有数字，也不给错的数字。
        val recRaw = v.optString("rec_text").trim()
        val playText = when {
            recRaw.isNotEmpty() && !recRaw.startsWith("{") -> recRaw
            hotNum > 0 -> formatCount(hotNum.toString())
            else -> extractCount(v.optString("rec_text")).ifEmpty { subTitleHeat(v) }
        }
        val statusText = v.optString("series_status").takeIf { it.isNotEmpty() && it != "0" }
        val contentType = v.optInt("content_type", vd?.optInt("content_type", 1004) ?: 1004)
        val vid = sequenceOf(
            v.optString("vid"),
            v.optString("first_vid"),
            vd?.optString("vid") ?: "",
        ).firstOrNull { it.isNotEmpty() } ?: ""
        val tags = mutableListOf<String>()
        v.optJSONArray("rec_tags")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i)
                if (o == null) {
                    // 纯字符串节点直接当标签（跳过 JSON 串）
                    a.optString(i).takeIf { it.isNotEmpty() && !it.startsWith("{") }?.let(tags::add)
                    continue
                }
                val text = o.optString("text")
                val content = o.optString("content")
                // 只有带 text 的才是标签；content 那种是推荐语（「共377万人在看」），
                // 热度已经由 series_play_cnt 算好，这里直接跳过
                if (text.isNotEmpty()) tags.add(text)
            }
        }
        if (tags.isEmpty()) {
            val schema = v.optString("category_schema").ifEmpty { vd?.optString("category_schema") ?: "" }
            tags.addAll(parseTags(schema).take(4))
        }
        val recText = v.optString("rec_text").ifEmpty { null }
            ?: v.optJSONObject("rec_text_item")?.optString("text")?.ifEmpty { null }
        return SeriesCard(
            seriesId = id,
            vid = vid,
            title = title,
            cover = cover,
            episodeCount = epCnt,
            score = score,
            playText = playText,
            tags = tags,
            contentType = contentType,
            statusText = statusText,
            recText = recText,
            desc = vd?.optString("series_intro")?.ifBlank { null }
                ?: v.optString("video_desc").ifBlank { null },
            upcoming = v.optBoolean("is_upcoming", false),
            reserved = v.optBoolean("is_reserved", false),
        )
    }

    /** 从存档 JSON（我们自己的缓存格式）还原卡片。 */
    fun seriesCardFromStored(o: JSONObject): SeriesCard? {
        val id = o.optString("seriesId")
        if (id.isEmpty()) return null
        val tags = mutableListOf<String>()
        o.optJSONArray("tags")?.let { a ->
            for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let(tags::add)
        }
        return SeriesCard(
            seriesId = id,
            vid = o.optString("vid"),
            title = o.optString("title"),
            cover = o.optString("cover"),
            episodeCount = o.optInt("episodeCount"),
            score = o.optString("score"),
            playText = o.optString("playText"),
            tags = tags,
            contentType = o.optInt("contentType", 1004),
        )
    }

    // ---------------- 详情 ----------------

    /**
     * 拉一部剧的完整信息（含全部分集），写进内存缓存。
     *
     * 正确的接口是 POST /novel/player/video_detail/v1/（biz_param body），
     * 不是 GET multi_video/detail——那条路径上游已经 404 了。
     * 服务器代签优先；但上游对服务器身份会回 101000「pack ret empty」，
     * 这时退回设备自己签名直发（旧版一直这么干，所以一直能用）。
     */
    fun fetchSeriesData(seriesId: String): JSONObject? {
        val bodyObj = JSONObject()
            .put(
                "biz_param",
                JSONObject()
                    .put("caller_scene", "single_col")
                    .put("detail_page_version", 1)
                    .put("disable_digg_stat", false)
                    .put("disable_video_relate_book", false)
                    .put("from_video_id", "")
                    .put("need_all_video_definition", false)
                    .put("need_mp4_align", false)
                    .put("screen_width_px", "1078")
                    .put("source", 4)
                    .put("use_os_player", false)
                    .put("use_server_dns", false)
                    .put("video_id_type", 1),
            )
            .put("series_id", seriesId)
        val body = bodyObj.toString()
        // 给签名服务的 body 必须是 JSON 对象（不是转义字符串）：
        // 签名按 body 的规范序列化计算，形式不一致上游就软拒（code=0 但 video_list 被清空）
        val payload = JSONObject()
            .put("origin", LAND_ORIGIN)
            .put("path", DETAIL_PATH)
            .put("query", JSONArray())
            .put("body", bodyObj)

        fun extract(text: String?, step: String): JSONObject? {
            val root = runCatching { JSONObject(text ?: return null) }.getOrNull() ?: return null
            val data = root.optJSONObject("data")
            val vd0 = data?.optJSONObject("video_data")
            val vd = vd0 ?: return null
            if (vd.length() == 0) return null
            // 退化数据不算成功：签名/身份不对时上游会回 code=0 但把 video_list 清空。
            // 这时必须落到下一档（设备直发），否则详情页永远只有标题没有分集。
            if (!vd.has("video_list")) return null
            if (!vd.has("series_id")) vd.put("series_id", seriesId)
            DetailCache.put(vd)
            return vd
        }

        // 1) 代签 + 客户端直发（原版同款：带 x-argus/x-gorgon 等签名头，上游才回完整数据）
        signEndpoint(LAND_ORIGIN, DETAIL_PATH, JSONArray(), body)?.let { (url, headers) ->
            runCatching { extract(requestWithHeaders(url, body, headers), "dev") }
                .onFailure { Log.d("PadPlayer", "detail-sign 失败: ${it.message}") }
                .getOrNull()
        }?.let { return it }
        // 2) 服务器代取兜底（可能降级：video_list 为空则 extract 会判失败）
        return extract(viaServer(payload), "srv")
    }

    /** 拿详情；缓存没有就先拉一次。 */
    fun ensureDetail(seriesId: String): SeriesDetail? {
        DetailCache.get(seriesId)?.let { return it }
        fetchSeriesData(seriesId)
        return DetailCache.get(seriesId)
    }

    // ---------------- 首页 / 列表（按原工程逐句还原） ----------------

    /**
     * 首页首屏：走 bookmall/tab/v，把 tab_item 里的卡片全收出来。
     * 拿不到 cell/plan 时用 TAB_IDS 的兜底值（老 cell 还在服务）。
     */
    fun firstPage(tabType: String): FeedPage {
        val qs = baseQuery() +
            "&auth_aweme=true&auth_backward=true&bottom_tab_type=7&client_req_type=4" +
            "&device_level=3&disable_digg_stat=false&has_video_cache=false" +
            "&is_horizontal_screen=false&landing_bottom_tab_type=7&last_tab_index=0" +
            "&last_tab_type=0&offset=0&req_rank_category_id=0&screen_width_px=1078" +
            "&tab_index=0&tab_type=$tabType&video_type_preferences_str=[]"
        val text = viaServerUrl("$HOST/reading/bookapi/bookmall/tab/v?$qs")
            ?: runCatching { get("$HOST/reading/bookapi/bookmall/tab/v?$qs") }.getOrNull()
            ?: return FeedPage()
        val data = runCatching { JSONObject(text).optJSONObject("data") }.getOrNull()
            ?: return FeedPage()
        val tabItem = data.optJSONArray("tab_item") ?: return FeedPage()

        val cards = LinkedHashMap<String, SeriesCard>()
        var sessionId = ""
        var cellId = ""
        var planId = ""
        var nextOffset = 0
        var hasMore = false
        // 找不到对应 tab 时退而求其次：两轮循环，第二轮不筛 tab_type
        for (round in 0 until 2) {
            for (i in 0 until tabItem.length()) {
                val item = tabItem.optJSONObject(i) ?: continue
                if (round == 0 && item.optString("tab_type") != tabType) continue
                collectCards(item.optJSONArray("cell_data"), cards)
                if (cards.isNotEmpty()) {
                    if (sessionId.isEmpty()) sessionId = item.optString("session_id")
                    if (cellId.isEmpty()) cellId = firstCellId(item.optJSONArray("cell_data"))
                    if (planId.isEmpty()) planId = item.optString("bookstore_id")
                    nextOffset = maxOf(nextOffset, item.optInt("next_offset", 0))
                    hasMore = hasMore || item.optBoolean("has_more", false)
                }
            }
            if (cards.isNotEmpty()) break
        }
        // 兜底 cell：tab 接口什么都没给时，用写死的老 cell 再试一次
        val fallback = TAB_IDS[tabType]
        if (fallback != null) {
            if (cellId.isEmpty() || cellId == "0") cellId = fallback.first
            if (planId.isEmpty() || planId == "0") planId = fallback.second
        }
        if (nextOffset == 0) nextOffset = cards.size
        var page = FeedPage(
            cards = cards.values.toList(),
            sessionId = sessionId,
            nextOffset = nextOffset,
            hasMore = hasMore || (sessionId.isNotEmpty() && cellId.isNotEmpty()),
            cellId = cellId,
            planId = planId,
        )
        // tab/v 的 offset=0 只回「今日精选」那 3 张，真正的海报墙在第一页 cell/change 里。
        // 之前是让首页滚动观察者去补，用户看到的就是「先出 3 个，剩下的再蹦出来」。
        // 这里就地补到 12 张（6 列 × 2 行），首屏一次画满。
        var round = 0
        while (page.cards.size < 12 && page.hasMore && page.cellId.isNotEmpty() && round < 3) {
            val next = runCatching {
                feedNext(
                    tabType = tabType,
                    cellId = page.cellId,
                    planId = page.planId,
                    sessionId = page.sessionId,
                    offset = page.nextOffset,
                    seen = page.cards.map { it.seriesId },
                )
            }.getOrNull() ?: break
            val merged = LinkedHashMap<String, SeriesCard>()
            page.cards.forEach { merged[it.seriesId] = it }
            next.cards.forEach { merged.putIfAbsent(it.seriesId, it) }
            if (merged.size <= page.cards.size) break
            page = page.copy(
                cards = merged.values.toList(),
                nextOffset = next.nextOffset,
                hasMore = next.hasMore,
                sessionId = next.sessionId.ifEmpty { page.sessionId },
            )
            round++
        }
        return page
    }

    /** tab 接口拿不到时的兜底 cell/plan（老 cell 仍在服务）。 */
    private val TAB_IDS = mapOf(
        "16" to ("7294257141911650341" to "7294257258253254693"),
        "36" to ("7597343008509411390" to "7597343072904560702"),
        "39" to ("7650476176527343678" to "7650476689721409598"),
    )

    fun feedNext(
        tabType: String,
        cellId: String,
        planId: String,
        sessionId: String,
        offset: Int,
        seen: Collection<String>,
    ): FeedPage = cellChange(
        "/reading/bookapi/bookmall/cell/change/v",
        linkedMapOf(
            "cell_id" to cellId,
            "plan_id" to planId,
            "tab_type" to tabType,
            "session_id" to sessionId,
            "offset" to offset.toString(),
            "filter_ids" to seen.take(60).joinToString(","),
        ),
    )

    private fun firstCellId(arr: JSONArray?): String {
        if (arr == null) return ""
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("cell_id_str").ifEmpty { o.optString("cell_id") }
            if (id.isNotEmpty() && id != "0") return id
        }
        return ""
    }

    /** cell/change 的原始返回（排行榜的 selector 也要用）。 */
    private fun cellChangeRaw(path: String, params: Map<String, String>): JSONObject? {
        val qs = baseQuery() + params.entries
            .filter { it.key != "filter_ids" || it.value.isNotEmpty() }
            .joinToString("") { "&${it.key}=${it.value}" }
        val text = runCatching { get("$HOST$path?$qs") }.getOrNull() ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /**
     * cell/change 翻页。offset=0 时是「换一批」语义，服务端行为不同，
     * 通过 unlimited_selector_change_type 区分。
     */
    private fun cellChange(
        path: String,
        params: LinkedHashMap<String, String>,
        allowEmptySession: Boolean = false,
    ): FeedPage {
        val sessionIdIn = params["session_id"] ?: ""
        val qs = baseQuery() + params.entries
            .filter { it.key != "filter_ids" || it.value.isNotEmpty() }
            .joinToString("") { "&${it.key}=${it.value}" } +
            "&cell_sub_id=0&client_template=7&is_horizontal_screen=false&limit=0" +
            "&need_personal_recommend=1&screen_width_px=1078&genre=0&genre_type=0" +
            "&unlimited_selector_change_type=" + if (params["offset"] == "0") "2" else "1"
        val (relayText, relayAge) = viaServerUrlEx("$HOST$path?$qs")
        val text = relayText
            ?: runCatching { get("$HOST$path?$qs") }.getOrNull()
            ?: return FeedPage(sessionId = sessionIdIn)
        val data = runCatching { JSONObject(text).optJSONObject("data") }.getOrNull()
            ?: return FeedPage(sessionId = sessionIdIn)
        val cards = LinkedHashMap<String, SeriesCard>()
        val view = data.optJSONObject("cell_view")
        collectCards(view?.optJSONArray("cell_data") ?: data.optJSONArray("cell_data"), cards)
        if (cards.isEmpty()) {
            // cell/change/v1 的 cell_view 本身就是卡片容器：video_data 直接挂在上面，
            // 没有 cell_data 子数组（新剧页就是这个结构）
            val direct = org.json.JSONArray()
            if (view != null && view.has("video_data")) direct.put(view)
            else if (data.has("video_data")) direct.put(data)
            if (direct.length() > 0) collectCards(direct, cards)
        }
        var session = data.optString("session_id").ifEmpty { sessionIdIn }
        return FeedPage(
            cards = cards.values.toList(),
            sessionId = session,
            nextOffset = data.optInt("next_offset", cards.size),
            hasMore = data.optBoolean("has_more", false) || (allowEmptySession && cards.isEmpty()),
            cellId = params["cell_id"] ?: "",
            planId = params["plan_id"] ?: "",
            dataAgeSec = relayAge,
        )
    }

    /** 递归收 cell_data 里的所有 book 节点，按 seriesId 去重。 */
    /**
     * 从「共377万人在看」「4389万播放」「4250310」这类文案里抠出热度数字（万/亿）。
     *
     * 拿不到数字就返回空串——宁可不显示，也不把「人在追」这种运营文案当热度。
     */
    /**
     * sub_title_list（找剧 landpage）里 data_type=27 的那条就是红果热度值，如 "1857万"。
     * recommend_reason_list 是同一份内容的 JSON 字符串形态，一起兜底。
     */
    private fun subTitleHeat(v: JSONObject): String {
        val arr = v.optJSONArray("sub_title_list") ?: run {
            val raw = v.optString("recommend_reason_list")
            if (raw.startsWith("{")) {
                runCatching { JSONObject(raw).optJSONArray("sub_title_list") }.getOrNull()
            } else null
        } ?: return ""
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("data_type") == 27) {
                val c = o.optString("content")
                // 内容一般是"1857万"；过一遍 extractCount，万一带"热度"字样也能抠出来
                return extractCount(c).ifEmpty { c }
            }
        }
        return ""
    }

    private fun extractCount(raw: String): String {
        if (raw.isBlank()) return ""
        val s = if (raw.startsWith("{")) {
            runCatching { JSONObject(raw).optString("content") }.getOrDefault("")
        } else {
            raw
        }
        if (s.isBlank()) return ""
        s.trim().toLongOrNull()?.let { if (it > 0) return formatCount(it.toString()) }
        Regex("(\\d+(?:\\.\\d+)?)\\s*(亿|万)").find(s)?.let { m ->
            return "${m.groupValues[1]}${m.groupValues[2]}"
        }
        return ""
    }

    private fun collectCards(arr: JSONArray?, out: LinkedHashMap<String, SeriesCard>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            collectCards(o as Any, out)
        }
    }

    private fun collectCards(node: Any?, out: LinkedHashMap<String, SeriesCard>) {
        when (node) {
            is JSONObject -> {
                // 推荐流的卡片在 video_data 里（每格一集：series_id/vid/cover/video_detail）
                val videos = node.optJSONArray("video_data")
                if (videos != null) {
                    for (i in 0 until videos.length()) {
                        val vo = videos.optJSONObject(i) ?: continue
                        val c = toCard(vo) ?: continue
                        if (c.seriesId.isNotEmpty()) out.putIfAbsent(c.seriesId, c)
                    }
                }
                // 老结构兜底：book_data（bookmall 老 cell 用）
                val book = node.optJSONArray("book_data")
                if (book != null) {
                    for (i in 0 until book.length()) {
                        val b = book.optJSONObject(i) ?: continue
                        val c = toCard(b) ?: continue
                        if (c.seriesId.isNotEmpty()) out.putIfAbsent(c.seriesId, c)
                    }
                }
                node.keys().forEach { k -> collectCards(node.opt(k), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collectCards(node.opt(i), out)
        }
    }

    /** 旧签名版：给云端书架 / 预约 / 追更用（输出 List、跨层去重）。 */
    private fun collectCards(node: Any?, out: MutableList<SeriesCard>, seen: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                val videos = node.optJSONArray("video_data")
                if (videos != null) {
                    for (i in 0 until videos.length()) {
                        val vo = videos.optJSONObject(i) ?: continue
                        val c = toCard(vo) ?: continue
                        if (seen.add(c.seriesId)) out.add(c)
                    }
                }
                val book = node.optJSONArray("book_data")
                if (book != null) {
                    for (i in 0 until book.length()) {
                        val b = book.optJSONObject(i) ?: continue
                        val c = toCard(b) ?: continue
                        if (seen.add(c.seriesId)) out.add(c)
                    }
                }
                node.keys().forEach { k -> collectCards(node.opt(k), out, seen) }
            }
            is JSONArray -> for (i in 0 until node.length()) collectCards(node.opt(i), out, seen)
        }
    }

    // ---------------- 找剧 / 搜索 / 排行榜 / 新剧 ----------------

    private val SELECT_KEYS = listOf(
        "category_dim_epoch", "category_dim_role", "category_dim_theme",
        "duration", "gender", "genre", "online_time", "sort", "creation_status",
    )

    private fun emptySelect(): JSONObject {
        val o = JSONObject()
        SELECT_KEYS.filter { it != "creation_status" }.forEach { o.put(it, JSONArray()) }
        return o
    }

    /**
     * landpage：番茄「找剧」的核心接口，body 决定要筛选面板还是内容列表。
     * 优先服务器代签，失败再本机签名直发。
     */
    private fun landpage(body: JSONObject): JSONObject? {
        val payload = JSONObject()
            .put("origin", LAND_ORIGIN)
            .put("path", LAND_PATH)
            .put("query", JSONArray())
            .put("body", body)
        viaServer(payload)?.let {
            return runCatching { JSONObject(it) }.getOrNull()
        }
        return directSignedPost(LAND_PATH, body)
    }

    /** 本机签名直发（兜底路径）。 */
    private fun directSignedPost(path: String, body: JSONObject): JSONObject? = runCatching {
        val signReq = JSONObject()
            .put("origin", LAND_ORIGIN)
            .put("path", path)
            .put("query", JSONArray())
            .put("body", body)
        val resp = JSONObject(request(App.state.signUrl.trimEnd('/') + "/sign", signReq.toString()))
        val url = resp.optString("url")
        if (url.isEmpty()) return null
        val headers = resp.optJSONObject("headers") ?: JSONObject()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            headers.keys().forEach { k -> conn.setRequestProperty(k, headers.optString(k)) }
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (App.state.loggedIn) conn.setRequestProperty("Cookie", App.state.accountCookies)
            conn.connectTimeout = 12_000
            conn.readTimeout = 25_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun itemsOf(row: JSONObject?): List<FilterItem> {
        val arr = row?.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<FilterItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("selector_item_id")
            val name = o.optString("show_name")
            if (id.isNotEmpty() && name.isNotEmpty()) out.add(FilterItem(id, name))
        }
        return out
    }

    /** 找剧页的筛选行。genreId 为空时是首屏。 */
    /** 面板内存缓存：从详情返回时瞬时渲染，不再重新拉 2-8 秒。 */
    private val findRowsCache = LinkedHashMap<String, List<FilterRow>>()

    fun hasCachedRows(genreId: String): Boolean = findRowsCache.containsKey(genreId)

    fun findRowsCached(genreId: String): List<FilterRow> {
        findRowsCache[genreId]?.let { return it }
        val r = runCatching { findRows(genreId) }.onFailure {
            Log.d("FindRows", "findRows FAIL", it)
        }.getOrDefault(emptyList())
        Log.d("FindRows", "findRows size=${r.size}")
        if (r.isNotEmpty()) findRowsCache[genreId] = r
        return r
    }

    /**
     * 找剧筛选面板。背后逻辑（与原版一致）：
     * 漫剧和真人剧是两套分类体系，landpage 面板只会给真人剧的通用分类；
     * 漫剧(comic_series)/AI剧(ai_series) 的主题/设定/背景藏在各自频道首页
     * tab 数据（tab_type=32）的 cell_selector.inner_rows 里（就是频道页顶部
     * 筛选器的数据源），且必须用签名请求（query 只带 tab_index/tab_type/offset）
     * 上游才返回对应频道。真人剧走 tab_type=38。取不到再退回 landpage 面板。
     */
    fun findRows(genreId: String): List<FilterRow> {
        val body = JSONObject()
            .put("need_selector_panel", false)
            .put("req_scene", "default")
            .put("req_type", "only_panel")
        if (genreId.isNotEmpty()) {
            body.put("select_items", JSONObject().put("genre", JSONArray().put(genreId)))
        }
        val rows = LinkedHashMap<String, JSONObject>()
        val lp = landpage(body)
        Log.d("FindRows", "landpage=${lp != null}")
        lp?.optJSONObject("data")?.optJSONArray("selector_rows")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                rows[o.optString("type")] = o
            }
        }
        Log.d("FindRows", "panel rows=${rows.keys}")
        val dims = genreDimensions(genreId)
        Log.d("FindRows", "dims keys=${dims.keys}")
        val out = mutableListOf<FilterRow>()
        var genre = itemsOf(rows["genre"])
        if (genre.isEmpty()) {
            genre = listOf(
                FilterItem("short_play", "真人剧"),
                FilterItem("comic_series", "漫剧"),
                FilterItem("ai_series", "AI剧"),
            )
        }
        out.add(FilterRow("genre", "体裁", genre))
        // 主题/设定/背景：频道 selector 优先，landpage 面板兜底
        for (pair in listOf(
            "category_dim_theme" to "主题",
            "category_dim_role" to "设定",
            "category_dim_epoch" to "背景",
        )) {
            val items = dims[pair.first]?.takeIf { it.isNotEmpty() } ?: itemsOf(rows[pair.first])
            if (items.isNotEmpty()) out.add(FilterRow(pair.first, pair.second, items))
        }
        itemsOf(rows["sort"]).takeIf { it.isNotEmpty() }?.let {
            out.add(FilterRow("sort", "推荐", it))
        }
        itemsOf(rows["gender"]).takeIf { it.isNotEmpty() }?.let {
            out.add(FilterRow("gender", "受众", it))
        }
        out.add(
            FilterRow(
                "creation_status", "状态",
                listOf(
                    FilterItem("creation_status_0", "已完结"),
                    FilterItem("creation_status_1", "连载中"),
                ),
            )
        )
        itemsOf(rows["online_time"]).takeIf { it.isNotEmpty() }?.let {
            out.add(FilterRow("online_time", "时间", it))
        }
        return out
    }

    /**
     * 体裁专属分类：从对应频道首页 tab 数据的 cell_selector.inner_rows 解析。
     * row_name 含「主题/设定/背景」分别映射到三个维度 key。
     */
    private fun genreDimensions(genreId: String): Map<String, List<FilterItem>> {
        val tabType = if (genreId == "comic_series" || genreId == "ai_series") "32" else "38"
        val tabs = runCatching { tabItemsRaw(tabType) }.getOrDefault(JSONArray())
        for (i in 0 until tabs.length()) {
            val tab = tabs.optJSONObject(i) ?: continue
            if (tab.optString("tab_type") != tabType) continue
            // cell_data 可能嵌套（cell 里套 cell），递归找带 inner_rows 的 cell_selector
            val found = runCatching { findInnerRows(tab.optJSONArray("cell_data")) }.getOrNull()
            if (found != null) {
                val map = LinkedHashMap<String, List<FilterItem>>()
                for (j in 0 until found.length()) {
                    val row = found.optJSONObject(j) ?: continue
                    val name = row.optString("row_name")
                    val key = when {
                        name.contains("主题") -> "category_dim_theme"
                        name.contains("设定") -> "category_dim_role"
                        name.contains("背景") -> "category_dim_epoch"
                        else -> continue
                    }
                    val items = mutableListOf<FilterItem>()
                    val arr = row.optJSONArray("items") ?: continue
                    for (k in 0 until arr.length()) {
                        val it = arr.optJSONObject(k) ?: continue
                        val id = it.optString("selector_item_id")
                        val label = it.optString("show_name")
                        if (id.isNotEmpty() && label.isNotEmpty()) items.add(FilterItem(id, label))
                    }
                    if (items.isNotEmpty()) map[key] = items
                }
                if (map.isNotEmpty()) return map
            }
        }
        return emptyMap()
    }

    /** 递归找 cell_selector.inner_rows（cell 可能多层嵌套）。 */
    private fun findInnerRows(arr: org.json.JSONArray?): org.json.JSONArray? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            val cell = arr.optJSONObject(i) ?: continue
            val sel = cell.optJSONObject("cell_selector")
            val inner = sel?.optJSONArray("inner_rows")
            if (inner != null && inner.length() > 0) return inner
            val nested = findInnerRows(cell.optJSONArray("cell_data"))
            if (nested != null) return nested
        }
        return null
    }

    /** 频道首页 tab 数据（签名请求，query 只带 tab_index/tab_type/offset，上游才认）。 */
    private fun tabItemsRaw(tabType: String): org.json.JSONArray {
        val query = JSONArray()
            .put(JSONArray().put("tab_index").put("0"))
            .put(JSONArray().put("tab_type").put(tabType))
            .put(JSONArray().put("offset").put("0"))
        val text = signedGet("/reading/bookapi/bookmall/tab/v", query) ?: return JSONArray()
        return runCatching {
            JSONObject(text).optJSONObject("data")?.optJSONArray("tab_item") ?: JSONArray()
        }.getOrDefault(JSONArray())
    }

    /** 找剧列表：按筛选条件拉一页。 */
    fun findList(selected: Map<String, String>, offset: Int, sessionId: String): FeedPage {
        var session = sessionId
        val body = JSONObject()
            .put("client_req_type", 3)
            .put("filter_ids", "")
            .put("limit", 20)
            .put("need_selector_panel", false)
            .put("offset", offset)
            .put("req_scene", "default")
            .put("req_type", "only_content")
        val select = emptySelect()
        selected.forEach { (k, v) ->
            if (SELECT_KEYS.contains(k) && k != "creation_status" && v.isNotEmpty()) {
                select.put(k, JSONArray().put(v))
            }
        }
        body.put("select_items", select).put("session_id", session)
        selected["creation_status"]?.takeIf { it.isNotEmpty() }?.let {
            body.put("creation_status", it)
        }
        val data = landpage(body)?.optJSONObject("data") ?: return FeedPage()
        val cards = LinkedHashMap<String, SeriesCard>()
        collectCards(data as Any, cards)
        data.optString("session_id").takeIf { it.isNotEmpty() }?.let { session = it }
        return FeedPage(
            cards = cards.values.toList(),
            sessionId = session,
            nextOffset = data.optInt("next_offset", offset + cards.size),
            hasMore = data.optBoolean("has_more", false),
        )
    }

    /** 搜索：先走带签名的 tab 搜索，空了再说。 */
    /**
     * 搜索联想（对齐 Windows：输入 2 字以上就出候选）。
     *
     * 接口是 `search/suggest/v`，查询词参数叫 **q**（不是 search/tab 的 query），
     * 响应在 `data.query_result_v2[].name`。
     */
    fun searchSuggest(q: String): List<String> {
        val arr = JSONArray()
            .put(JSONArray().put("q").put(q))
            .put(JSONArray().put("bookshelf_search_plan").put("4"))
            .put(JSONArray().put("bookstore_tab").put("16"))
            .put(JSONArray().put("count").put("0"))
            .put(JSONArray().put("need_personal_recommend").put("1"))
            .put(JSONArray().put("need_preload").put("true"))
            .put(JSONArray().put("search_source").put("1"))
            .put(JSONArray().put("tab_name").put("feed"))
        val text = runCatching { signedGet("/reading/bookapi/search/suggest/v", arr) }.getOrNull()
            ?: return emptyList()
        val data = runCatching { JSONObject(text).optJSONObject("data") }.getOrNull() ?: return emptyList()
        val list = data.optJSONArray("query_result_v2") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val word = o.optString("name").ifEmpty { o.optString("keyword") }
            if (word.isNotBlank() && !out.contains(word)) out.add(word)
            if (out.size >= 8) break
        }
        return out
    }

    /**
     * 搜索结果一页（对齐 Windows 的 search_series）。
     *
     * 只认 `search_tabs[tab_type=11]`（综合）这一个 tab：它的 `data[].video_data[]`
     * 才是剧集卡片；分页字段也挂在 tab 上（has_more / next_offset / search_id），
     * 翻页时必须把上一页的 search_id 作为 `passback`+`search_id` 传回去，
     * 否则服务端给的是另一份结果集（表现为第二页重复第一页）。
     */
    fun searchPage(query: String, offset: Int = 0, searchId: String = "", tabType: String = "11"): FeedPage {
        val arr = JSONArray()
            .put(JSONArray().put("query").put(query))
            .put(JSONArray().put("tab_type").put(tabType))
            .put(JSONArray().put("count").put("100"))
            .put(JSONArray().put("offset").put(offset.toString()))
            .put(JSONArray().put("use_correct").put("true"))
            .put(JSONArray().put("bookshelf_search_plan").put("4"))
        if (offset > 0) {
            arr.put(JSONArray().put("passback").put(offset.toString()))
            arr.put(JSONArray().put("search_id").put(searchId))
        }
        val reqId = "${System.currentTimeMillis()}-${(100000..999999).random()}"
        val text = signedGet("/reading/bookapi/search/tab/v", arr, mapOf("x-reading-request" to reqId))
            ?: return FeedPage()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return FeedPage()
        val cards = LinkedHashMap<String, SeriesCard>()

        val tabs = root.optJSONArray("search_tabs")
        var tab: JSONObject? = null
        if (tabs != null) {
            for (i in 0 until tabs.length()) {
                val t = tabs.optJSONObject(i) ?: continue
                if (t.optInt("tab_type") == 11) { tab = t; break }
            }
        }
        if (tab == null) {
            // 兜底：结构对不上时退回递归收集（老接口形态）
            collectSearchNodes(root, cards)
            return FeedPage(cards = cards.values.toList(), nextOffset = cards.size, hasMore = false)
        }

        val data = tab.optJSONArray("data") ?: JSONArray()
        for (i in 0 until data.length()) {
            val cell = data.optJSONObject(i) ?: continue
            val vd = cell.optJSONArray("video_data") ?: continue
            for (j in 0 until vd.length()) {
                val raw = vd.optJSONObject(j) ?: continue
                val card = cardFromSearch(raw) ?: continue
                cards.putIfAbsent(card.seriesId, card)
            }
        }
        return FeedPage(
            cards = fillHeat(cards.values.toList()),
            sessionId = tab.optString("search_id"),
            nextOffset = tab.optInt("next_offset", offset + cards.size),
            hasMore = tab.optBoolean("has_more", false),
        )
    }

    /** 搜索结果条目 → 卡片（字段名按实测：顶层 cover / episode_cnt / play_cnt / sub_title_list）。 */
    private fun cardFromSearch(raw: JSONObject): SeriesCard? {
        val id = raw.optString("series_id")
        val title = raw.optString("title").ifEmpty { raw.optString("series_title") }
        if (id.isEmpty() || title.isEmpty()) return null
        val pc = raw.optLong("play_cnt", 0L)
        val subs = mutableListOf<String>()
        raw.optJSONArray("sub_title_list")?.let { a ->
            for (k in 0 until a.length()) {
                a.optJSONObject(k)?.optString("content")?.takeIf { it.isNotBlank() }?.let(subs::add)
            }
        }
        return SeriesCard(
            seriesId = id,
            vid = raw.optString("vid"),
            title = title,
            cover = raw.optString("cover"),
            episodeCount = raw.optInt("episode_cnt", 0),
            score = raw.optString("score"),
            playText = if (pc > 0) formatCount(pc.toString()) else "",
            tags = parseTags(raw.optString("category_schema")).ifEmpty { subs.take(3) },
            contentType = raw.optInt("content_type", 1004),
            desc = raw.optString("video_desc").takeIf { it.isNotBlank() },
            upcoming = raw.optInt("episode_cnt", 0) <= 0,
        )
    }

    private fun collectSearchNodes(node: Any?, out: LinkedHashMap<String, SeriesCard>) {
        when (node) {
            is JSONObject -> {
                val id = node.optString("series_id")
                val title = node.optString("title")
                if (id.isNotEmpty() && title.isNotEmpty()) {
                    val vd = node.optJSONObject("video_detail")
                    // 字段名按搜索接口实测对齐：封面是顶层 cover（不是 thumb_url），
                    // 集数是 episode_cnt（不是 series_episode_count），否则卡片全是黑图 + 没集数
                    // show_play_cnt 在这个接口里可能是 "true"（布尔），不能直接当播放量；
                    // 只认 play_cnt 的数值，否则卡片上会出现「🔥 true」
                    val pcNum = node.optLong("play_cnt", 0L)
                    out[id] = SeriesCard(
                        seriesId = id,
                        vid = node.optString("vid").ifEmpty { vd?.optString("vid") ?: "" },
                        title = title,
                        cover = node.optString("cover").ifEmpty { vd?.optString("series_cover") ?: "" },
                        episodeCount = node.optInt("episode_cnt", vd?.optInt("episode_cnt", 0) ?: 0),
                        score = node.optString("score"),
                        playText = if (pcNum > 0) formatCount(pcNum.toString()) else "",
                        tags = parseTags(node.optString("category_schema")),
                        contentType = node.optInt("content_type", 1004),
                    )
                }
                node.keys().forEach { k -> collectSearchNodes(node.opt(k), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collectSearchNodes(node.opt(i), out)
        }
    }

    /**
     * 排行榜一页。genre/sub 为空时是全站总榜。
     *
     * @param panel 筛选面板选中的 id（空 = 总榜）。
     * @param month 月榜的 id（`date_YYYYMM`，空 = 当前榜）。
     */
    fun rank(
        offset: Int,
        sessionId: String,
        genre: String,
        sub: String,
        panel: String = "",
        month: String = "",
    ): FeedPage {
        val params = linkedMapOf(
            "cell_id" to RANK_CELL,
            "tab_type" to "26",
            // 上游「全部」的 id 是 all。传 * 时多数子榜只回 0~2 条，
            // 必看/臻果/热搜/收藏 甚至整包只回选择器（界面就是「榜单加载失败」）。
            "selected_items" to (genre.takeIf { it.isNotEmpty() && it != "*" } ?: "all"),
            "sub_selected_items" to sub,
            "session_id" to sessionId,
            "offset" to offset.toString(),
        )
        // 两个都是「有才带」：空值不能被拼进 query，否则上游把空串当成一个非法选项，直接回空包
        if (panel.isNotEmpty()) params["panel_selected_items"] = panel
        if (month.isNotEmpty()) params["background_selected_items"] = month
        return cellChange("/reading/bookapi/bookmall/cell/change/v", params)
    }

    /**
     * 口碑榜：按评分 / 热度 / 追更排序。
     *
     * 数据来自服务器的剧库（28 万条，评分/热度/追更都是详情里的真实数字），
     * 不走站方榜单接口 —— 站方的榜是人工运营的推荐位，不是按数字排的。
     *
     * @param metric rating（评分）| hot（热度）| favs（追更）
     * @param genre  all | comic（漫剧）| human（真人剧）| ai（AI剧）
     */
    fun chart(metric: String, genre: String, limit: Int = 60): List<SeriesCard> {
        for (base0 in serverBases()) {
            val text = runCatching {
                val conn = URL(
                    base0.trimEnd('/') + "/api/chart?metric=" + metric + "&genre=" + genre + "&limit=" + limit,
                ).openConnection() as HttpURLConnection
                conn.connectTimeout = if (base0.contains("192.168.")) 2_500 else 8_000
                conn.readTimeout = 15_000
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull()
            if (text.isNullOrEmpty()) continue
            val arr = runCatching { JSONObject(text).optJSONArray("items") }.getOrNull() ?: continue
            val out = mutableListOf<SeriesCard>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val rating = o.optString("rating").toDoubleOrNull() ?: 0.0
                val hot = o.optString("hot").toLongOrNull() ?: 0L
                val favs = o.optString("favs").toLongOrNull() ?: 0L
                val play = o.optString("play").toLongOrNull() ?: 0L
                val rcount = o.optString("rcount").toLongOrNull() ?: 0L
                // 服务器给的是 JSON 数组；直接 optString 会拿到 "[\"玄幻\",\"3D\"]" 这种原文
                val tagList = o.optJSONArray("tags")?.let { arr ->
                    (0 until arr.length()).mapNotNull { k ->
                        arr.optString(k).takeIf { s -> s.isNotBlank() }
                    }
                }.orEmpty()
                out.add(
                    SeriesCard(
                        seriesId = id,
                        title = o.optString("name"),
                        cover = o.optString("cover"),
                        episodeCount = o.optString("ep").toIntOrNull() ?: 0,
                        // 评分榜才把评分放卡片上；其它榜放评分反而显得乱
                        score = if (metric == "rating" && rating > 0) {
                            if (rating % 1.0 == 0.0) rating.toInt().toString() else rating.toString()
                        } else {
                            ""
                        },
                        heat = if (hot > 0) formatCount(hot.toString()) else null,
                        tags = tagList.take(3),
                        recText = when (metric) {
                            "rating" -> null
                            "favs" -> if (favs > 0) "${formatCount(favs.toString())}人追更" else null
                            "play" -> if (play > 0) "${formatCount(play.toString())}次播放" else null
                            else -> if (hot > 0) "热度值${formatCount(hot.toString())}" else null
                        },
                    ),
                )
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    /** 排行榜的榜单选择器（拿不到就用内置九榜）。 */
    fun rankSelectors(): List<RankTabsel> = listOf(
        RankTabsel(
            "all", "全部", listOf(
                RankSub("ranklist_hot_sc", "推荐榜"),
                RankSub("ranklist_hot_play_sc", "热播榜"),
                RankSub("ranklist_prestige", "臻果榜"),
                RankSub("ranklist_subscribe", "预约榜"),
                RankSub("ranklist_new_rank_sc", "新剧榜"),
                RankSub("ranklist_hot_search_sc", "热搜榜"),
                RankSub("ranklist_chase_update", "追更榜"),
                RankSub("ranklist_must_watch", "必看榜"),
                RankSub("ranklist_followed", "收藏榜"),
            ),
        ),
        RankTabsel(
            "human", "真人剧", listOf(
                RankSub("human_hot_sc", "推荐榜"),
                RankSub("human_hot_play", "热播榜"),
                RankSub("human_new_rank", "新剧榜"),
                RankSub("human_hot_search", "热搜榜"),
                RankSub("human_must_watch", "必看榜"),
                RankSub("human_followed", "收藏榜"),
            ),
        ),
        RankTabsel(
            "comic_series_rank", "漫剧", listOf(
                RankSub("comic_series_hot_rank", "推荐榜"),
                RankSub("comic_series_hot_play", "热播榜"),
                RankSub("comic_series_new_rank", "新剧榜"),
                RankSub("comic_series_hot_search", "热搜榜"),
            ),
        ),
        RankTabsel(
            "ai_playlet", "AI剧", listOf(
                RankSub("ai_playlet_hot_sc", "推荐榜"),
                RankSub("ai_playlet_hot_play", "热播榜"),
                RankSub("ai_playlet_new_rank", "新剧榜"),
                RankSub("ai_playlet_hot_search", "热搜榜"),
                RankSub("ai_playlet_must_watch", "必看榜"),
                RankSub("ai_playlet_followed", "收藏榜"),
            ),
        ),
        RankTabsel(
            "chart", "口碑榜", listOf(
                // 数据来自服务器自建的精选榜（chart.py）：只覆盖各分类榜单前几十名，
                // 但每个数字都是从详情接口取的真的——官方榜是运营排的，不是按数字算的。
                // 没有「评分榜」：平台只给约六分之一的剧打分（漫剧/AI剧 一部都没有），
                // 拼不成一个榜，留着只会让人以为在加载。
                RankSub("hot", "热度榜"),
                RankSub("favs", "追更榜"),
                RankSub("play", "播放榜"),
            ),
        ),
        RankTabsel(
            "ranklist_celebrity", "演员", listOf(
                RankSub("ranklist_series_actor", "剧星榜"),
                RankSub("ranklist_actor_fans", "星光榜"),
            ),
        ),
        RankTabsel(
            "series_album", "系列剧", listOf(
                RankSub("series_album_hot_sc", "推荐榜"),
                RankSub("series_album_new_rank", "新季榜"),
            ),
        ),
    )

    // ---------------- 排行榜菜单（服务器下发） ----------------

    /**
     * 榜单菜单：有哪些大类、每个大类下有哪些子榜、每个子榜能按什么筛、有没有月榜。
     *
     * 这些原本是写死在 [rankSelectors] 里的。写死有两个毛病：
     * ① 平台上新榜（比如节日榜）App 看不见，要改代码才跟得上；
     * ② 平台一旦改了某个榜的内部编号，这边就是「榜单加载失败」。
     * 服务器 `/api/rankmeta` 会把上游 cell_selector 摘出来（只留名字和 id，约 38KB），
     * 这里缓存 10 分钟；拿不到就退回内置表，页面至少还能用。
     */
    private var rankMetaCache: List<RankTabsel>? = null
    private var rankMetaAt = 0L
    private const val RANK_META_TTL = 10 * 60 * 1000L

    fun rankMeta(): List<RankTabsel> {
        rankMetaCache?.let {
            if (System.currentTimeMillis() - rankMetaAt < RANK_META_TTL) return it
        }
        val fresh = runCatching { fetchRankMeta() }.getOrNull()
        if (!fresh.isNullOrEmpty()) {
            rankMetaCache = fresh
            rankMetaAt = System.currentTimeMillis()
            return fresh
        }
        return rankMetaCache ?: rankSelectors()
    }

    private fun parseRankOptions(arr: JSONArray?): List<RankOption> {
        if (arr == null) return emptyList()
        val out = mutableListOf<RankOption>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isEmpty()) continue
            out.add(RankOption(o.optString("id"), name))
        }
        return out
    }

    private fun fetchRankMeta(): List<RankTabsel>? {
        for (base in serverBases()) {
            val text = runCatching {
                val conn = URL(base.trimEnd('/') + "/api/rankmeta").openConnection() as HttpURLConnection
                conn.connectTimeout = if (base.contains("192.168.")) 2_500 else 8_000
                conn.readTimeout = 20_000
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull()
            if (text.isNullOrEmpty()) continue
            val root = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val cats = root.optJSONArray("cats") ?: continue
            val out = mutableListOf<RankTabsel>()
            for (i in 0 until cats.length()) {
                val c = cats.optJSONObject(i) ?: continue
                val cid = c.optString("id")
                val cname = c.optString("name")
                if (cid.isEmpty() || cname.isEmpty()) continue
                val subsArr = c.optJSONArray("subs") ?: continue
                val subs = mutableListOf<RankSub>()
                for (j in 0 until subsArr.length()) {
                    val s = subsArr.optJSONObject(j) ?: continue
                    val sid = s.optString("id")
                    val sname = s.optString("name")
                    if (sid.isEmpty() || sname.isEmpty()) continue
                    val panels = mutableListOf<RankPanel>()
                    s.optJSONArray("panels")?.let { pa ->
                        for (k in 0 until pa.length()) {
                            val p = pa.optJSONObject(k) ?: continue
                            val items = parseRankOptions(p.optJSONArray("items"))
                            if (items.isNotEmpty()) {
                                panels.add(RankPanel(p.optString("key"), p.optString("name"), items))
                            }
                        }
                    }
                    subs.add(
                        RankSub(
                            id = sid,
                            label = sname,
                            desc = s.optString("desc"),
                            panels = panels,
                            months = parseRankOptions(s.optJSONArray("months")),
                        ),
                    )
                }
                if (subs.isNotEmpty()) out.add(RankTabsel(cid, cname, subs))
            }
            if (out.isNotEmpty()) {
                preferredBase = base
                return out
            }
        }
        return null
    }

    /**
     * 演员榜（剧星榜 / 星光榜）一页。
     *
     * 为什么单独一个方法：这个榜返回的是**人**，不是剧集 ——
     * 数据挂在 cell_data[].ugc_user_data[] 里，字段是 user_info.base_info
     * （user_name / user_avatar / user_id / actor_id_str）+ rec_text_item 的人气文案
     * + sub_title_list 的作品数。原来用解析 video_data 的那套去读，必然一条都拿不到，
     * 于是页面显示「榜单加载失败」。
     */
    fun rankActors(genre: String, sub: String, offset: Int = 0, sessionId: String = ""): List<RankPerson> {
        val qs = baseQuery() +
            "&cell_id=" + RANK_CELL +
            "&tab_type=26" +
            "&selected_items=" + (genre.takeIf { it.isNotEmpty() && it != "*" } ?: "all") +
            "&sub_selected_items=" + sub +
            "&session_id=" + sessionId +
            "&offset=" + offset +
            "&cell_sub_id=0&client_template=7&is_horizontal_screen=false&limit=0" +
            "&need_personal_recommend=1&screen_width_px=1078&genre=0&genre_type=0" +
            "&unlimited_selector_change_type=" + if (offset == 0) "2" else "1"
        val path = "/reading/bookapi/bookmall/cell/change/v?$qs"
        val text = viaServerUrl("$HOST$path")
            ?: runCatching { get("$HOST$path") }.getOrNull()
            ?: return emptyList()
        val cells = runCatching {
            JSONObject(text).optJSONObject("data")?.optJSONObject("cell_view")?.optJSONArray("cell_data")
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<RankPerson>()
        for (i in 0 until cells.length()) {
            val users = cells.optJSONObject(i)?.optJSONArray("ugc_user_data") ?: continue
            for (j in 0 until users.length()) {
                val u = users.optJSONObject(j) ?: continue
                val base = u.optJSONObject("user_info")?.optJSONObject("base_info") ?: continue
                // profile 接口只认 user_id：传 actor_id_str 会拿到默认昵称账号
                // （标题变成「用户76021193」），那样连按名字搜作品都搜不到。
                val userId = base.optString("user_id").ifEmpty { base.optString("actor_id_str") }
                val name = base.optString("user_name")
                if (userId.isEmpty() || name.isEmpty()) continue
                val details = mutableListOf<String>()
                u.optJSONArray("sub_title_list")?.let { sa ->
                    for (k in 0 until sa.length()) {
                        sa.optJSONObject(k)?.optString("content")?.takeIf { it.isNotBlank() }?.let { details.add(it) }
                    }
                }
                out.add(
                    RankPerson(
                        userId = userId,
                        name = name,
                        avatar = base.optString("user_avatar"),
                        score = u.optJSONObject("rec_text_item")?.optString("RecommendText").orEmpty(),
                        intro = u.optString("intro"),
                        details = details,
                    ),
                )
            }
        }
        return out
    }

    /** 新剧页：男女频两个 tab。 */
    fun newDrama(gender: Int, offset: Int, sessionId: String): FeedPage = cellChange(
        "/reading/bookapi/bookmall/cell/change/v1/",
        linkedMapOf(
            "cell_id" to NEW_CELL,
            "selected_items" to "firstonlinetime_new",
            "cell_gender" to gender.toString(),
            "change_type" to "1",
            "client_req_type" to "1",
            "limit" to "18",
            "need_personal_recommend" to "1",
            "offset" to offset.toString(),
        ),
        allowEmptySession = true,
    )

    // ---------------- 历史同步 ----------------

    private var lastHistSyncAt = 0L
    private val histSyncLock = Any()

    fun historyKey(): String? {
        if (!App.state.loggedIn) return null
        val uid = App.state.accountUid
        if (uid.isEmpty()) return null
        // 必须和原版同口径：sha256("hgtv-history-v2|" + uid)。
        // 只 hash uid 会得到另一个桶，读不到 Windows 版/原版同步上去的历史。
        return sha256Hex("hgtv-history-v2|$uid")
    }

    private fun sha256Hex(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * 官方观看历史（read_history/list）。
     * 这是**官方 App / Windows 版同一个数据源**：实测同一账号 1526 条，
     * 而自建桶只有 22 条。vid_index 是 0 基，转成我们的 1 基 episode。
     */
    fun officialHistory(maxPages: Int = 3): List<JSONObject> {
        if (!App.state.loggedIn) return emptyList()
        val out = mutableListOf<JSONObject>()
        var offset = 0
        repeat(maxPages) {
            val q = JSONArray()
                .put(JSONArray().put("book_type").put("2"))
                .put(JSONArray().put("full_field").put("false"))
                .put(JSONArray().put("is_first_load").put("true"))
                .put(JSONArray().put("last_min_read_timestamp_ms").put("0"))
                .put(JSONArray().put("limit").put("0"))
                .put(JSONArray().put("offset").put(offset.toString()))
                .put(JSONArray().put("query_soft_deleted").put("false"))
            val text = runCatching { signedGet("/reading/bookapi/read_history/list/v", q) }
                .getOrNull() ?: return out
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return out
            if (root.optInt("code", 0) != 0) return out
            val data = root.optJSONObject("data") ?: return out
            val list = data.optJSONArray("data_list") ?: return out
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val sid = o.optString("book_id_str").ifEmpty { o.optString("book_id") }
                val title = o.optString("book_name")
                if (sid.isEmpty() || title.isEmpty()) continue
                out.add(
                    JSONObject()
                        .put("seriesId", sid)
                        .put("title", title)
                        .put("cover", o.optString("thumb_url"))
                        .put("episode", o.optInt("vid_index", 0).coerceAtLeast(0) + 1)
                        .put("total", o.optInt("episode_cnt", 0))
                        // 云端带的「这一集看到第几秒」：跨端续播就靠它（安卓/Windows 共用）
                        .put("positionMs", o.optLong("current_play_position", 0L))
                        .put("at", o.optLong("read_timestamp_ms", 0L)),
                )
            }
            if (!data.optBoolean("has_more", false)) return out
            offset = data.optInt("next_offset", offset + 500)
        }
        return out
    }

    /**
     * 上报观看进度到官方（read_history/update + read_progress/upload）。
     * 这是「数据互通」的写方向：TV 上看到第几集、看到第几秒，
     * 手机上的官方 App / Windows 版侧栏历史都能看到。
     * vid_index 线上是 0 基，我们领域内是 1 基，发出前 -1。
     */
    fun reportProgress(seriesId: String, vid: String, episodeIndex: Int, positionMs: Long): Boolean {
        if (!App.state.loggedIn || vid.isEmpty() || seriesId.isEmpty()) return false
        val now = System.currentTimeMillis()
        fun idVal(s: String): Any = s.toLongOrNull() ?: s
        val wireIdx = (episodeIndex - 1).coerceAtLeast(0)
        val update = JSONObject().put(
            "update_datas",
            JSONArray().put(
                JSONObject()
                    .put("book_id", idVal(seriesId))
                    .put("book_type", 2)
                    .put("chapter_index", 0)
                    .put("current_play_position", positionMs)
                    .put("digged_count", 0)
                    .put("duration", 0)
                    .put("episode_cnt", 0)
                    .put("is_delete", false)
                    .put("is_interactive_game", false)
                    .put("is_listen_mode", false)
                    .put("is_multi_season", 0)
                    .put("meet_guide_comment_tag", false)
                    .put("origin_novel_book_id", 0)
                    .put("player_accumulate_total_time", positionMs)
                    .put("read_timestamp_ms", now)
                    .put("recent_reads", 0)
                    .put("retain_video_play_time", 0)
                    .put("season_index", 0)
                    .put("series_play_cnt", 0)
                    .put("tone_id", 0)
                    .put("update_timestamp_ms", now)
                    .put("use_soft_delete", false)
                    .put("user_digg", false)
                    .put("user_playlet_comment_flag", false)
                    .put("vid", idVal(vid))
                    .put("vid_index", wireIdx),
            ),
        )
        val t1 = runCatching { signedPost("/reading/bookapi/read_history/update/v", update.toString()) }
            .getOrNull() ?: return false
        val c1 = runCatching { JSONObject(t1).optInt("code", -1) }.getOrDefault(-1)
        if (c1 != 0) {
            Log.d("PadWrite", "reportProgress update code=$c1")
            return false
        }
        val upload = JSONObject().put(
            "books",
            JSONArray().put(
                JSONObject()
                    .put("book_id", seriesId)
                    .put("book_type", 2)
                    .put("channel_id", 0)
                    .put("check_timestamp", false)
                    .put("cur_channel_id", 0)
                    .put("current_play_time", positionMs)
                    .put("is_listen_mode", false)
                    .put("is_local_book", false)
                    .put("item_id", vid)
                    .put("listen_and_read", false)
                    .put("page_index", 0)
                    .put("page_progress_rate", 0)
                    .put("paragraph_offset", 0)
                    .put("player_cumulative_total_duration", positionMs)
                    .put("progress_type", 0)
                    .put("read_timestamp_ms", now)
                    .put("tone_id", 0)
                    .put("vid_index", wireIdx),
            ),
        )
        runCatching { signedPost("/reading/bookapi/read_progress/upload/v", upload.toString()) }
        return true
    }

    /**
     * 删除官方账号里的观看历史（read_history/update 的 is_delete）。
     *
     * 为什么必须删这份：历史页是「官方历史 + 自建桶」合并出来的，同步规则是两边合并。
     * 只删自建桶的话，下一次同步官方又把它返回，记录就"自己回来了"——
     * 用户看到的就是"删了没用/重装后又出现"。
     */
    fun deleteOfficialHistory(seriesIds: List<String>): Boolean {
        if (!App.state.loggedIn || seriesIds.isEmpty()) return false
        val now = System.currentTimeMillis()
        fun idVal(s: String): Any = s.toLongOrNull() ?: s
        val arr = JSONArray()
        seriesIds.forEach { sid ->
            arr.put(
                JSONObject()
                    .put("book_id", idVal(sid))
                    .put("book_type", 2)
                    .put("chapter_index", 0)
                    .put("is_delete", true)
                    .put("use_soft_delete", true)
                    .put("read_timestamp_ms", now)
                    .put("update_timestamp_ms", now)
                    .put("user_digg", false)
                    .put("vid_index", 0),
            )
        }
        val body = JSONObject().put("update_datas", arr).toString()
        val text = runCatching { signedPost("/reading/bookapi/read_history/update/v", body) }.getOrNull()
            ?: return false
        val code = runCatching { JSONObject(text).optInt("code", -1) }.getOrDefault(-1)
        Log.d("PadWrite", "deleteOfficial code=$code n=${seriesIds.size}")
        return code == 0
    }

    fun historySync(): Boolean {        // 官方观看历史（与官方 App / Windows 版同源，条数最全）
        val official = runCatching { officialHistory(3) }.getOrDefault(emptyList())
        // 自建桶（原版 APK 走这个，作为补充与回写通道）
        var legacy: List<JSONObject> = emptyList()
        var writeUrl: String? = null
        val key = historyKey()
        if (key != null) {
            val bases = serverBases().map { it.trimEnd('/') } + HISTORY_BASES
            for (base in bases.distinct()) {
                val url = "$base/api/history?key=$key"
                val remoteText = runCatching { get(url) }.getOrNull() ?: continue
                if (remoteText.isEmpty()) continue
                legacy = runCatching {
                    val arr = JSONObject(remoteText).optJSONArray("items") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                }.getOrDefault(emptyList())
                writeUrl = url
                break
            }
        }
        if (official.isEmpty() && legacy.isEmpty()) {
            Log.d("PadWrite", "historySync: 官方与自建桶都为空/失败")
            return false
        }
        val merged = App.state.mergeHistory(official + legacy)
        Log.d("PadWrite", "historySync official=${official.size} legacy=${legacy.size} merged=${merged.size}")
        if (writeUrl != null) {
            runCatching {
                val payload = JSONObject().put("items", JSONArray().also { out -> merged.forEach { out.put(it) } })
                requestOnce(writeUrl, payload.toString(), UA)
            }
        }
        return true
    }

    /**
     * 删除云端历史（seriesIds 为空 = 清空全部）。
     * /api/history 的 POST 是合并语义，只删本地的话下次同步又会把云端记录合并回来，
     * 用户看到的就是「清空没用」。
     */
    fun historyDelete(seriesIds: List<String>): Boolean {
        val key = historyKey() ?: return false
        val body = JSONObject()
            .put("mode", "delete")
            .put("seriesIds", JSONArray(seriesIds))
            .toString()
        val bases = serverBases().map { it.trimEnd('/') } + HISTORY_BASES
        for (base in bases.distinct()) {
            val ok = runCatching {
                requestOnce("$base/api/history?key=$key", body, UA)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /**
     * 一次性修复：把历史里 cover 为空的记录补齐。
     * 早期版本记录历史时 cover 写死空串，导致历史列表出现灰块。
     * 先查 DetailCache，没有再拉详情；4 路并发，最多修 40 条。
     */
    fun backfillHistoryCovers(): Int {
        val rows = App.state.history()
        val missing = rows.map { it.optString("seriesId") }
            .filter { id -> id.isNotEmpty() && rows.first { it.optString("seriesId") == id }.optString("cover").isEmpty() }
            .distinct()
            .take(40)
        if (missing.isEmpty()) return 0
        val covers = java.util.concurrent.ConcurrentHashMap<String, String>()
        for (id in missing) {
            DetailCache.get(id)?.cover?.takeIf { it.isNotEmpty() }?.let { covers[id] = it }
        }
        val need = missing.filter { !covers.containsKey(it) }
        if (need.isNotEmpty()) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            try {
                val futures = need.map { id ->
                    pool.submit {
                        runCatching { fetchSeriesData(id) }
                        DetailCache.get(id)?.cover?.takeIf { it.isNotEmpty() }?.let { covers[id] = it }
                    }
                }
                futures.forEach { runCatching { it.get(30, java.util.concurrent.TimeUnit.SECONDS) } }
            } finally {
                pool.shutdownNow()
            }
        }
        var fixed = 0
        covers.forEach { (id, cover) ->
            App.state.updateHistoryCover(id, cover)
            fixed++
        }
        if (fixed > 0) {
            Log.d("PadWrite", "backfillHistoryCovers fixed=$fixed")
            runCatching { historySync() }
        }
        return fixed
    }

    fun historySyncAsync(onDone: (() -> Unit)? = null, force: Boolean = false) {
        if (!App.state.loggedIn) return
        val now = System.currentTimeMillis()
        synchronized(histSyncLock) {
            if (!force && now - lastHistSyncAt < 10_000) {
                onDone?.invoke()
                return
            }
            lastHistSyncAt = now
        }
        Thread {
            runCatching { historySync() }
            onDone?.invoke()
        }.apply { isDaemon = true; name = "hgtv-history" }.start()
    }

    /** 云端书架（收藏跟着账号走）。 */
    fun cloudShelf(): List<SeriesCard> {
        if (!App.state.loggedIn) error("未同步账号")
        val uid = App.state.accountUid.ifEmpty { error("账号信息缺少 user_id") }
        val query = JSONArray().put(JSONArray().put("target_user_id").put(uid))
        // 原版这里是 GET（signedRequest(url, headers, null)）。
        // 之前误用 POST + 空 body，上游直接回空 → 收藏/点赞列表永远是 0。
        val text = signedGet("/reading/bookapi/bookshelf/video/list/v", query)
            ?: error("书架接口无响应")
        val root = runCatching { JSONObject(text) }.getOrNull() ?: error("书架接口返回格式异常")
        val code = root.optInt("code", 0)
        if (code != 0) {
            val msg = root.optString("message").ifEmpty { code.toString() }
            Log.d("PadWrite", "cloudShelf code=$code msg=$msg")
            error("书架接口返回：$msg（登录态可能已过期）")
        }
        val arr = root.optJSONObject("data")?.optJSONArray("video_shelf_info")
            ?: return emptyList()

        // 书架只回 {series_id, collect_time}，标题封面要靠详情补：
        // 原版按 collect_time 倒序，再 6 线程并发 fetchSeriesData 补全，最后照顺序取 DetailCache。
        val pairs = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("series_id")
            if (id.isEmpty()) return@mapNotNull null
            var t = o.optLong("collect_time", 0L)
            if (t in 1..1_000_000_000_000L) t *= 1000
            id to t
        }.sortedByDescending { it.second }
        if (pairs.isEmpty()) return emptyList()

        val missing = pairs.map { it.first }.filter { DetailCache.get(it) == null }
        if (missing.isNotEmpty()) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
            try {
                val futures = missing.map { id -> pool.submit { runCatching { fetchSeriesData(id) } } }
                futures.forEach { f ->
                    runCatching { f.get(30, java.util.concurrent.TimeUnit.SECONDS) }
                }
            } finally {
                pool.shutdownNow()
            }
        }
        return pairs.mapNotNull { (id, _) ->
            val d = DetailCache.get(id)
            if (d != null) cardOfDetail(d)
            else SeriesCard(seriesId = id, title = "该剧集已下架")
        }
    }

    /**
     * 只取收藏的 seriesId 集合（不补详情，一次请求）。
     * 详情接口没有 is_collected 字段，换设备后收藏按钮会是空的，
     * 用它校正按钮状态，成本只有一次书架查询。
     */
    fun shelfIds(): Set<String> {
        if (!App.state.loggedIn) return emptySet()
        val uid = App.state.accountUid.ifEmpty { return emptySet() }
        val query = JSONArray().put(JSONArray().put("target_user_id").put(uid))
        val text = runCatching { signedGet("/reading/bookapi/bookshelf/video/list/v", query) }
            .getOrNull() ?: return emptySet()
        val arr = runCatching {
            JSONObject(text).optJSONObject("data")?.optJSONArray("video_shelf_info")
        }.getOrNull() ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("series_id")?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out
    }

    /** 只取预约的 seriesId 集合（详情接口没有预约状态字段）。 */
    fun reserveIds(): Set<String> {
        if (!App.state.loggedIn) return emptySet()
        val out = LinkedHashSet<String>()
        for (online in listOf("false", "true")) {
            val query = JSONArray()
                .put(JSONArray().put("is_online").put(online))
                .put(JSONArray().put("limit").put("50"))
                .put(JSONArray().put("offset").put("0"))
                .put(JSONArray().put("subscribe_offset").put("0"))
                .put(JSONArray().put("subscribe_order_type").put("0"))
                .put(JSONArray().put("swipe_type").put("0"))
                .put(JSONArray().put("tab_type").put("13"))
                .put(JSONArray().put("need_calendar_schema").put("false"))
            val text = runCatching { signedGet("/reading/user/subscribe/list/v1/", query) }
                .getOrNull() ?: continue
            val items = runCatching {
                JSONObject(text).optJSONObject("data")?.optJSONArray("subscribe_items")
            }.getOrNull() ?: continue
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val sid = o.optJSONObject("subscribe_data")?.optString("series_id").orEmpty()
                    .ifEmpty { o.optString("item_id") }
                if (sid.isNotEmpty()) out.add(sid)
            }
        }
        return out
    }

    /** 详情 → 网格卡片（原版 cardFromDetail）。 */
    fun cardOfDetail(d: SeriesDetail): SeriesCard = SeriesCard(
        seriesId = d.seriesId,
        vid = d.episodes.firstOrNull()?.vid.orEmpty(),
        title = d.title,
        cover = d.cover,
        episodeCount = if (d.episodeCount > 0) d.episodeCount else d.episodes.size,
        // 卡片那个位置显示的是**热度**，不能塞播放次数（收藏/点赞页就是从这里来的）
        playText = d.hotScore.toLongOrNull()?.takeIf { it > 0 }
            ?.let { formatCount(it.toString()) }.orEmpty(),
        tags = d.tags,
        contentType = d.contentType,
        statusText = d.status.ifBlank { null },
    )

    // ---------------- 预约 / 追更 ----------------

    /**
     * 预约 / 取消预约。端点是 uncover_subscribe（旧包写成了 plan/reserve/v，
     * 路径错 → 预约根本没写进云端，预约页自然是空的——这就是那个老 bug）。
     * 必须带登录 cookie；op_type 1 预约 / 2 取消。
     */
    /**
     * 已预约的 series_id 集合（服务端为准）。
     *
     * 详情页相关推荐里每张「待上线」卡都要知道自己是「预约 / 已预约」，
     * 这个状态只有服务端有（换设备也在），所以拉一次 subscribe/list 全量对号，
     * 内存缓存 60 秒；写操作成功立刻失效，避免刚预约完卡片还是「预约」。
     */
    private var reserveCache: Set<String>? = null
    private var reserveCacheAt = 0L

    fun reservedIds(): Set<String> {
        val now = System.currentTimeMillis()
        reserveCache?.let { if (now - reserveCacheAt < 60_000L) return it }
        val ids = runCatching { reserveList().map { it.seriesId }.toSet() }.getOrDefault(emptySet())
        reserveCache = ids
        reserveCacheAt = now
        return ids
    }

    fun invalidateReserve() {
        reserveCache = null
        reserveCacheAt = 0L
    }

    fun reserveToggle(seriesId: String, on: Boolean): Boolean {
        if (!App.state.loggedIn) return false
        val itemId = seriesId.toLongOrNull() ?: return false
        val body = JSONObject()
            .put("item_id", itemId)
            .put("item_type", 1)
            .put("op_type", if (on) 1 else 2)
            .put(
                "shark_param",
                JSONObject()
                    .put("enter_from", "BulletActivity")
                    .put("page_list", "MainFragmentActivity,BulletActivity")
                    .put("previous_page", "MainFragmentActivity"),
            )
            .put("wish_list_all_del", 0)
            .toString()
        val ok = writeOk(signedPost("/reading/bookapi/search/uncover_subscribe/v", body))
        // 列表接口按账号聚合，写完必须让缓存失效，否则卡片状态还是旧的
        invalidateReserve()
        return ok
    }

    /**
     * 收藏（追剧）写入云端。bookshelf/video/update/v，对象是 series_id
     * （body 字段名叫 book_id）；video_shelf_operate_type 0 收 / 1 取。
     */
    fun collectSeries(seriesId: String, on: Boolean): Boolean {
        if (!App.state.loggedIn) return false
        val body = JSONObject()
            .put("is_cancelled", false)
            .put(
                "shark_extra",
                JSONObject()
                    .put("enter_from", "MainFragmentActivity")
                    .put("inactive_type", "0")
                    .put("is_active_behavior", "true")
                    .put("page_list", "MainFragmentActivity")
                    .put("previous_page", ""),
            )
            .put(
                "update_bookshelf_video_list",
                JSONArray().put(
                    JSONObject()
                        .put("book_id", seriesId)
                        .put("book_type", 2)
                        .put("modify_time", System.currentTimeMillis())
                        .put("video_shelf_operate_type", if (on) 0 else 1),
                ),
            )
            .toString()
        return writeOk(signedPost("/reading/bookapi/bookshelf/video/update/v", body))
    }

    /**
     * 点赞 / 取消点赞一集。do_action：object_type=6（视频）、
     * action_type 3 赞 / 4 取消；business_param.video_id 填的是剧 id
     * （官方体字段名与语义不符，照抄）。
     */
    fun diggVideo(vid: String, seriesId: String, digg: Boolean): Boolean {
        if (!App.state.loggedIn || vid.isEmpty() || seriesId.isEmpty()) return false
        val body = JSONObject()
            .put("action_category", 1)
            .put("action_reason_remark", "like_click")
            .put("action_type", if (digg) 3 else 4)
            .put(
                "business_param",
                JSONObject()
                    .put("book_id", 0)
                    .put("has_aigc_content", false)
                    .put("modify_count", 0)
                    .put(
                        "shark_param",
                        JSONObject()
                            .put("enter_from", "MainFragmentActivity")
                            .put("page_list", "MainFragmentActivity")
                            .put("previous_page", ""),
                    )
                    .put("video_id", seriesId),
            )
            .put("object_id", vid)
            .put("object_type", 6)
            .toString()
        return writeOk(signedPost("/novel/articleapi/do_action/v1/", body))
    }

    /**
     * 预约列表。原版走 subscribe/list/v1，is_online 分别取 false/true 各拉一次
     * （未上线 / 已上线两组），条目在 data.subscribe_items[].subscribe_data 里，
     * 是自带 title/cover/score/play_cnt/episode_cnt 的扁平卡片，不需要二次请求。
     * 之前用的是 plan/v + is_online/limit/offset —— 上游直接回 100103（参数无效），列表永远空。
     */
    fun reserveList(): List<SeriesCard> {
        if (!App.state.loggedIn) return emptyList()
        val out = LinkedHashMap<String, SeriesCard>()
        for (online in listOf("false", "true")) {
            val query = JSONArray()
                .put(JSONArray().put("is_online").put(online))
                .put(JSONArray().put("limit").put("50"))
                .put(JSONArray().put("offset").put("0"))
                .put(JSONArray().put("subscribe_offset").put("0"))
                .put(JSONArray().put("subscribe_order_type").put("0"))
                .put(JSONArray().put("swipe_type").put("0"))
                .put(JSONArray().put("tab_type").put("13"))
                .put(JSONArray().put("need_calendar_schema").put("false"))
            val text = runCatching { signedGet("/reading/user/subscribe/list/v1/", query) }
                .getOrNull() ?: continue
            val items = runCatching {
                JSONObject(text).optJSONObject("data")?.optJSONArray("subscribe_items")
            }.getOrNull() ?: continue
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                // 订阅列表同样有两套 schema：今天那批是 subscribe_data.*，
                // 其它情况直接平铺在条目上（日历页踩过这个坑）
                val sd = o.optJSONObject("subscribe_data") ?: o
                val card = toFlatCard(sd) ?: continue
                // is_online=false 那轮拉到的就是「待上线」，用 upcoming 区分分组
                out[card.seriesId] = card.copy(
                    upcoming = online == "false",
                    reserved = true,
                )
            }
        }
        // 预约页会显示热度，这批数据是直连上游拿的，统一补一次
        return fillHeat(out.values.toList())
    }

    /**
     * 追更列表。原版走 relation/get_user_action，数据结构是
     * data.mix_data[].video.{video_id, common{title,cover,video_content_type}, expand{episode_cnt}}，
     * **不是** video_data 数组——之前按 video_data 收，所以永远 0 条。
     * 条目里没有 series_id/book_id 时用 video_id 兜底（原版 findSeriesId 同口径）。
     */
    fun followList(): List<SeriesCard> {
        if (!App.state.loggedIn) return emptyList()
        val query = JSONArray()
            .put(JSONArray().put("action_channel").put("9"))
            .put(JSONArray().put("count").put("50"))
            .put(JSONArray().put("cursor_str").put(""))
        val text = runCatching {
            signedGet("/novel/articleapi/relation/get_user_action/v1/", query)
        }.getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        if (root.optInt("code", -1) != 0) {
            Log.d("PadWrite", "followList code=${root.optInt("code")}")
            return emptyList()
        }
        val mix = root.optJSONObject("data")?.optJSONArray("mix_data") ?: return emptyList()
        val out = mutableListOf<SeriesCard>()
        for (i in 0 until mix.length()) {
            val v = mix.optJSONObject(i)?.optJSONObject("video") ?: continue
            val common = v.optJSONObject("common") ?: continue
            val title = common.optString("title")
            if (title.isEmpty()) continue
            val vid = v.optString("video_id")
            val sid = v.optString("series_id")
                .ifEmpty { v.optString("book_id") }
                .ifEmpty { vid }
            if (sid.isEmpty()) continue
            out.add(
                SeriesCard(
                    seriesId = sid,
                    vid = vid,
                    title = title,
                    cover = common.optString("cover"),
                    episodeCount = v.optJSONObject("expand")?.optInt("episode_cnt") ?: 0,
                    contentType = common.optInt("video_content_type", 1004),
                ),
            )
        }
        return out
    }

    fun reviews(seriesId: String, limit: Int = 20): ReviewPage {
        val body = JSONObject()
            .put("aid", 8662)
            .put(
                "business_param",
                JSONObject()
                    .put("book_id", seriesId)
                    .put("comment_sort_debug", false)
                    .put("end_offset_time", 0)
                    .put("hit_interaction_data_migration_ab", false)
                    .put("is_last_episode", false)
                    .put("item_count", 0)
                    .put("max_item_count", 0)
                    .put("need_count", true)
                    .put("need_danmaku_occlusion_face_data", false)
                    .put("outflow_comment_count", 0)
                    .put("para_index", 0)
                    .put("playlet_consume_duration_ms", 0)
                    .put("playlet_item_consume_duration_ms", 0)
                    .put("playlet_item_duration", 0)
                    .put("read_item_count", 0)
                    .put("req_type", 0)
                    .put("start_offset_time", 0),
            )
            .put("comment_source", 1)
            .put("comment_type", 2)
            .put("count", limit)
            .put("group_id", seriesId)
            .put("group_type", 1)
            .put("server_channel", 34)
            .put("sort", 1)
            .toString()
        val text = signedPost("/novel/commentapi/comment/list/$seriesId/v1/", body)
            ?: return ReviewPage()
        val data = runCatching { JSONObject(text).optJSONObject("data") }.getOrNull()
            ?: return ReviewPage()
        val arr = data.optJSONArray("data_list") ?: return ReviewPage()
        val items = mutableListOf<ReviewItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // 实测结构：{comment: {common: {content(对象{text}) , create_timestamp,
            // user_info: {base_info: {nickname}}}, stat: {digg_count}}}
            val c = o.optJSONObject("comment") ?: o
            val common = c.optJSONObject("common")
            var text2 = ""
            when (val content = common?.opt("content")) {
                is JSONObject -> text2 = content.optString("text")
                is String -> text2 = content
            }
            if (text2.isEmpty()) continue
            val ui = common?.optJSONObject("user_info")
            val base = ui?.optJSONObject("base_info")
            val ts = common?.optLong("create_timestamp", 0) ?: 0L
            items.add(
                ReviewItem(
                    user = base?.optString("nickname").orEmpty(),
                    avatar = base?.optString("avatar").orEmpty(),
                    text = text2,
                    time = if (ts > 0) java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA)
                        .format(java.util.Date(ts * 1000)) else "",
                    likes = c.optJSONObject("stat")?.optLong("digg_count", 0)
                        ?.takeIf { it > 0 }?.toString().orEmpty(),
                ),
            )
        }
        val cursor = data.optJSONObject("common_list_info")?.optString("cursor").orEmpty()
        // 评分/评分人数在本接口响应的 extra 里（credibility_score /
        // credibility_score_count）——与 PC 端同一数据源，保证两端 hero
        // 显示同一套内容（detail 接口的 secondary_infos 只有部分剧有评分）
        val extra = data.optJSONObject("extra")
        val score = extra?.opt("credibility_score")?.toString().orEmpty()
        val scoreCount = extra?.optInt("credibility_score_count", 0) ?: 0
        // 剧评总数（common_list_info.total，与 PC 的 commentTotal 同源）
        val total = data.optJSONObject("common_list_info")?.optInt("total", 0) ?: 0
        return ReviewPage(items, cursor, data.optBoolean("has_more", false), score, scoreCount, total)
    }

    /** 详情页底部「相关推荐」：plan/v 按剧 id 拉同系列/推荐合集。 */
    /**
     * 相关推荐。原版逻辑：plan/v 的 data 是数组，按 cell_name 分成「相关作品」「猜你喜欢」两组，
     * 组内条目是**扁平剧集字段**（title/cover/score/play_cnt/episode_cnt/tag_info.text），
     * 不嵌套 video_detail——之前只按嵌套结构收，导致信息（标题/评分/角标）丢失。
     * 原版还会重试 3 次（间隔 400/700ms），因为「猜你喜欢」常需要多次才有数据。
     */
    fun relatedSeries(seriesId: String): List<RelatedGroup> {
        // 参数与原版逐项一致（缺了上游会回 100103）
        val query = JSONArray()
            .put(JSONArray().put("book_id").put(seriesId))
            .put(JSONArray().put("bookstore_tab").put("0"))
            .put(JSONArray().put("bookstore_tab_type").put("0"))
            .put(JSONArray().put("current_chapter_num").put("0"))
            .put(JSONArray().put("from").put("detail_page_more_related"))
            .put(JSONArray().put("is_horizontal_screen").put("false"))
            .put(JSONArray().put("limit").put("0"))
            .put(JSONArray().put("need_personal_recommend").put("1"))
            .put(JSONArray().put("offset").put("0"))
            .put(JSONArray().put("post_id").put("0"))
            .put(JSONArray().put("scene").put("10"))
            .put(JSONArray().put("total_chapter_num").put("0"))

        repeat(3) { attempt ->
            if (attempt > 0) runCatching { Thread.sleep(400L + (attempt - 1) * 300L) }
            val text = runCatching { signedGet("/reading/bookapi/plan/v", query) }.getOrNull()
                ?: return@repeat
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return@repeat
            if (root.optInt("code", 0) != 0) return@repeat
            val data = root.optJSONArray("data") ?: return@repeat
            val groups = mutableListOf<RelatedGroup>()
            for (i in 0 until data.length()) {
                val cell = data.optJSONObject(i) ?: continue
                val name = cell.optString("cell_name")
                val raw = mutableListOf<JSONObject>()
                val vd = cell.optJSONArray("video_data")
                if (vd != null && vd.length() > 0) {
                    for (j in 0 until vd.length()) vd.optJSONObject(j)?.let { raw.add(it) }
                } else {
                    // 兼容：部分 cell 把条目放在 cell_data[].video_data
                    val cd = cell.optJSONArray("cell_data") ?: continue
                    for (j in 0 until cd.length()) {
                        val sub = cd.optJSONObject(j)?.optJSONArray("video_data") ?: continue
                        for (k in 0 until sub.length()) sub.optJSONObject(k)?.let { raw.add(it) }
                    }
                }
                val items = fillHeat(raw.mapNotNull { toFlatCard(it) })
                    .filter { it.seriesId != seriesId }
                    .distinctBy { it.seriesId }
                if (name.isNotEmpty() && items.isNotEmpty()) groups.add(RelatedGroup(name, items))
            }
            if (groups.isNotEmpty()) return groups
        }
        return emptyList()
    }

    /** 相关推荐条目是扁平字段，单独解析（不能走 toCard，那条路依赖 video_detail）。 */
    private fun toFlatCard(o: JSONObject): SeriesCard? {
        val sid = o.optString("series_id").ifEmpty {
            o.optString("series_id_str").ifEmpty {
                o.optString("book_id").ifEmpty { o.optString("item_id") }
            }
        }
        if (sid.isEmpty()) return null
        val epCnt = o.optString("episode_cnt").toIntOrNull() ?: o.optInt("episode_cnt", 0)
        var tag = o.optJSONObject("tag_info")?.optString("text").orEmpty()
        // 没集 = 还没上线（待上线只能预约，不能播）。相关推荐靠它决定要不要出预约按钮。
        if (tag.isEmpty() && epCnt == 0) tag = "即将上线"
        return SeriesCard(
            seriesId = sid,
            vid = o.optString("vid"),
            title = o.optString("title").ifEmpty { o.optString("series_title") }
                .ifEmpty { o.optString("name") },
            cover = o.optString("cover").ifEmpty { o.optString("series_cover") },
            episodeCount = epCnt,
            score = o.optString("score"),
            // play_cnt 是纯数字：折成「57万」，别把 3059625 原样糊在卡片上
            playText = o.optString("play_cnt").toLongOrNull()
                ?.let { if (it > 0) formatCount(it.toString()) else "" }
                .orEmpty(),
            tags = parseTags(o.optString("category_schema")),
            contentType = o.optInt("content_type", 1004),
            statusText = tag.ifEmpty { null },
            upcoming = epCnt <= 0,
            reserved = o.optBoolean("has_subscribed", false) ||
                o.optBoolean("online_subscribed", false) ||
                App.state.contains(App.KEY_RESERVE, sid),
            // season_index 原样留在 series 字段里，多季接续靠它找下一季
            season = o.optInt("season_index", 0).takeIf { it > 0 }?.toString(),
        )
    }

    /**
     * 多季剧：当前季播完后要接的「下一季」series_id。
     *
     * 上游没有「下一季」接口，从详情页那份相关推荐里找：
     * 同名（去掉季后缀后标题一致 / 前缀相同）且 season_index = 当前季 + 1 的那部。
     * 《末日宿舍求生》这种 1/2/3/4 季的剧就是这么串起来的。
     */
    /**
     * 补齐这个合集里缺掉的季。
     *
     * 有些剧在平台上是**多套独立合集**（重复上架/不同发行批次），每套只收录一部分季，
     * 甚至某一套里的那一季已经下架。《全球躲猫猫》用户点开的那套只有 1/3/4/5/6：
     * 第二季在库里那条已经 content_gone（下架），能播的那一季在另一套合集里，
     * 标题还叫「我是林岁安第二季」。
     *
     * 三条通道捞候选，逐个用**主创**验明正身：
     * ① 服务器同族索引（严格）：按剧名基名归族，同名的各季都在；
     * ② 服务器同族索引（宽松）：剧名**包含**主标题就算，能捞到改了副标题的续集；
     * ③ 上游搜索（tab 用「剧集」29）：库里还没收录的新剧，只有搜索能捞到。
     *    注意 11（短剧）同样搜「全球躲猫猫」只回 4 条，29 能回十几条 —— 差别就在这。
     *
     * 主创比对：同一部剧的班底不会变（实测两套合集演员名完全一致，只是账号 id 不同，
     * 各自重复建档）。出品方（「艺海星合剧场」这类机构账号）也在主创列表里，一并参与。
     * 光靠标题会把「逗号版第二季」这种同名的别的剧混进来 —— 实测它的主创只有一个
     * 「书生万卷剧场」，靠这一步被挡掉了。
     */
    fun fillMissingSeasons(
        seriesId: String,
        title: String,
        have: List<SeasonRef>,
        baseCreators: Set<String>,
    ): List<SeasonRef> {
        if (have.isEmpty() || title.isBlank()) return emptyList()
        val maxIdx = have.maxOf { it.index }
        if (maxIdx <= 1) return emptyList()
        val missing = (1..maxIdx).filter { i -> have.none { it.index == i } }.toMutableSet()
        if (missing.isEmpty()) return emptyList()

        val cands = LinkedHashMap<String, String>()
        runCatching { familyCandidates(title) }.getOrDefault(emptyList())
            .forEach { (id, name) -> cands[id] = name }
        // 上游搜索兜底：库覆盖不全时只有这条能捞到新剧。
        // 两个 tab 都查一遍再合并：11（综合）命中主标题，29（剧集）列表更全，
        // 改名续集（「…我是林岁安第二季」）只能靠 29 捞到。
        val searchKey = runCatching {
            val head = title.substringBefore('：').substringBefore(':').substringBefore('，')
                .substringBefore(',').trim()
            if (head.length in 3..14) head else title
        }.getOrDefault("")
        if (searchKey.isNotEmpty()) {
            for (tab in listOf("11", "29")) {
                runCatching { searchPage(searchKey, 0, "", tab) }
                    .getOrNull()?.cards?.forEach { c ->
                        if (c.seriesId.isNotEmpty()) cands[c.seriesId] = c.title
                    }
            }
        }

        val out = mutableListOf<SeasonRef>()
        // 每部候选都要拉一次详情（详情才有季号和主创），限制次数免得进页面拖太久
        var budget = 10
        for ((id, name) in cands) {
            if (budget <= 0 || missing.isEmpty()) break
            if (id == seriesId) continue
            if (have.any { it.seriesId == id }) continue
            // 标题里就能看出季号、而这个号我们不需要 → 省一次详情请求
            val byTitle = seasonFromTitle(name)
            if (byTitle > 0 && byTitle !in missing) continue
            val d = runCatching { ensureDetail(id) }.getOrNull() ?: continue  // 下架的会返回空
            budget--
            val idx = d.seasonIndex.takeIf { it > 0 } ?: seasonFromTitle(d.title.ifBlank { name })
            if (idx <= 0 || idx !in missing) continue
            val names = d.creators.map { it.name }.filter { it.isNotBlank() }.toSet()
            // 双方都拿得到主创时必须对得上；拿不到主创时放行（宁可多给一季，也别硬藏）
            if (baseCreators.isNotEmpty() && names.isNotEmpty() && names.intersect(baseCreators).isEmpty()) continue
            out.add(
                SeasonRef(
                    seriesId = d.seriesId,
                    title = d.title.ifBlank { name },
                    cover = d.cover,
                    count = d.episodeCount,
                    index = idx,
                    fromOther = true,
                ),
            )
            missing.remove(idx)
        }
        return out
    }

    /** 从标题里抠「第X季」，详情没给 season_index 时兜底（只认一~十，够用）。 */
    private fun seasonFromTitle(t: String): Int {
        val m = Regex("第([一二三四五六七八九十]+)季").find(t) ?: return 0
        val cn = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10,
        )
        return cn[m.groupValues[1].firstOrNull()] ?: 0
    }

    /**
     * 服务器的同族索引，两条通道合并：
     * ① 严格（loose=0）：按剧名基名归族，同名的各季都在里面；
     * ② 宽松（loose=1）：剧名**包含**主标题就算，能捞到改了副标题的续集
     *    （「全球躲猫猫：我是林岁安第二季」这种严格归族认不出来的）。
     * 是不是同一部剧由调用方用主创比对来定，这里只负责把候选捞全。
     */
    private fun familyCandidates(title: String): List<Pair<String, String>> {
        val q = java.net.URLEncoder.encode(title, "UTF-8")
        val merged = LinkedHashMap<String, String>()
        for (loose in listOf("0", "1")) {
            for (base in serverBases()) {
                val text = runCatching {
                    val conn = URL(base.trimEnd('/') + "/api/family?title=" + q + "&loose=" + loose)
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = if (base.contains("192.168.")) 2_500 else 8_000
                    conn.readTimeout = 15_000
                    conn.inputStream.bufferedReader().use { it.readText() }
                }.getOrNull()
                if (text.isNullOrEmpty()) continue
                val arr = runCatching { JSONObject(text).optJSONArray("items") }.getOrNull() ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isEmpty()) continue
                    merged[id] = o.optString("name")
                }
                break
            }
        }
        return merged.map { it.key to it.value }
    }

    /**
     * 同一部剧的全部季。走 plan 接口的「系列剧」行（show_type=547），
     * 这是上游唯一靠谱的季清单 —— 相关推荐会漏季，标题匹配更不可靠
     * （《全球躲猫猫》第二季叫「我是林岁安第二季」，前缀和其他季完全不同）。
     */
    fun seasons(seriesId: String): List<SeasonRef> {
        // 季清单必须由服务器出：plan 接口要官方签名，客户端自己请求拿到的
        // 是降级推荐位（里面混着别的剧），怎么调都不对。
        // 服务器侧 /api/seasons 转发给 streamsrv，用的是跑通的 openhongguo 签名。
        for (base in serverBases()) {
            val text = runCatching {
                val conn = URL(base.trimEnd('/') + "/api/seasons?id=" + seriesId).openConnection() as HttpURLConnection
                conn.connectTimeout = if (base.contains("192.168.")) 2_500 else 8_000
                conn.readTimeout = 25_000
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull()
            if (text.isNullOrEmpty()) continue
            val arr = runCatching { JSONObject(text).optJSONArray("list") }.getOrNull() ?: continue
            val out = mutableListOf<SeasonRef>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("series_id")
                if (id.isEmpty()) continue
                out.add(
                    SeasonRef(
                        seriesId = id,
                        title = o.optString("title"),
                        cover = o.optString("cover"),
                        count = o.optInt("count", 0),
                        index = o.optInt("index", 0),
                    ),
                )
            }
            if (out.isNotEmpty()) return out.sortedBy { it.index }
        }
        return emptyList()
    }

    /** 这一季到底有没有分集。拉不到会重试一次：详情偶发空包，不能当成"这季没内容"。 */
    private fun seasonHasEpisodes(seriesId: String): Boolean {
        repeat(2) { attempt ->
            val ok = runCatching { ensureDetail(seriesId)?.episodes?.isNotEmpty() == true }
                .getOrDefault(false)
            if (ok) return true
            if (attempt == 0) runCatching { Thread.sleep(350) }
        }
        return false
    }

    fun nextSeason(seriesId: String): String? {
        // 首选 plan 接口的季清单：完整、可靠，不看标题。
        // 老的「相关推荐 + 标题前缀匹配」会漏季：《全球躲猫猫》第二季叫
        // 「我是林岁安第二季」，前缀和其他四季完全不同，靠标题永远找不到。
        val list = runCatching { seasons(seriesId) }.getOrDefault(emptyList())
        if (list.size > 1) {
            val at = list.indexOfFirst { it.seriesId == seriesId }
            val tail = if (at >= 0) list.drop(at + 1) else list.filter { it.index > 0 }
            for (s in tail) {
                // 季清单里上游登记的集数就是"这季上没上线"的权威答案：>0 直接接，
                // 不用再为每季单独拉详情。
                //
                // 以前这里不分青红皂白，每季都先拉一次详情再判断；详情偶发空包
                // （或清单降级没填 count）时，好好的一季会被当成"还没上线"跳过——
                // 实测《穿书末世我靠系统囤货逆袭》：第二季播完直接跳到第四季，
                // 可第三季明明有 162 集。
                if (s.count > 0) return s.seriesId
                // count=0：可能真没上线（第十季预约中），也可能清单降级没填。
                // 拉一次详情确认（带重试），真有集也接上，别一次空包就漏季。
                if (seasonHasEpisodes(s.seriesId)) return s.seriesId
            }
            if (at >= 0) return null
        }

        // 兜底：季清单拿不到时，仍按相关推荐 + 季号匹配试一次
        val cur = DetailCache.get(seriesId) ?: fetchSeriesData(seriesId)?.let { DetailCache.get(seriesId) }
        val curSeason = cur?.seasonIndex ?: 0
        val baseTitle = (cur?.title ?: "").trim()
        if (baseTitle.isEmpty()) return null
        val cards = runCatching { relatedSeries(seriesId) }.getOrNull()
            ?.flatMap { it.items }.orEmpty()
        if (cards.isEmpty()) return null

        fun norm(t: String) = t.replace(Regex("[0-9０-９]+$"), "")
            .replace("第", "").replace("季", "").trim()

        val same = cards.filter { norm(it.title).startsWith(norm(baseTitle).take(8)) || norm(it.title) == norm(baseTitle) }
        val candidates = same
            .mapNotNull { c ->
                val n = c.season?.toIntOrNull() ?: 0
                if (n > curSeason) n to c else null
            }
            .sortedBy { it.first }
            .map { it.second }
        for (c in candidates) {
            if (seasonHasEpisodes(c.seriesId)) return c.seriesId
        }
        return null
    }

    /**
     * 给一批卡片补上真热度。
     *
     * 为什么要这个：有些列表（搜索、相关推荐、预约）是**直连上游**拿的，
     * 不经过我们服务器的 relay，所以享受不到那边的热度补齐；
     * 它们的数据里往往只有播放次数，卡片上就会出现「播放量当热度」。
     * 这里统一去服务器对一次，拿到的就是详情里的 hot_score。
     */
    fun fillHeat(cards: List<SeriesCard>): List<SeriesCard> {
        if (cards.isEmpty()) return cards
        val ids = cards.map { it.seriesId }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return cards
        for (base in serverBases()) {
            val text = runCatching {
                val conn = URL(
                    base.trimEnd('/') + "/api/heat?ids=" + ids.joinToString(","),
                ).openConnection() as HttpURLConnection
                conn.connectTimeout = if (base.contains("192.168.")) 2_500 else 8_000
                conn.readTimeout = 20_000
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            val items = runCatching { JSONObject(text).optJSONObject("items") }.getOrNull() ?: continue
            if (items.length() == 0) continue
            var changed = false
            val out = cards.map { c ->
                val h = items.optString(c.seriesId)
                if (h.isNotEmpty() && c.playText != h) {
                    changed = true
                    c.copy(playText = h)
                } else {
                    c
                }
            }
            return if (changed) out else cards
        }
        return cards
    }

    /** 演职员资料。user/profile/get，ugc_user.base_info。 */
    fun creatorProfile(userId: String): CreatorProfileInfo? {
        val query = JSONArray().put(JSONArray().put("user_id").put(userId))
        val text = signedGet("/reading/user/profile/get/v:version/", query) ?: return null
        val base = runCatching {
            JSONObject(text).optJSONObject("data")?.optJSONObject("ugc_user")
                ?.optJSONObject("base_info")
        }.getOrNull() ?: return null
        val name = base.optString("user_name")
        if (name.isEmpty()) return null
        return CreatorProfileInfo(
            id = base.optString("user_id"),
            name = name,
            avatar = base.optString("user_avatar"),
            desc = base.optString("user_desc").ifEmpty { base.optString("signature") },
        )
    }

    /** 演职员/出品方作品列表的一页。 */
    data class CreatorWorksPage(
        val cards: List<SeriesCard> = emptyList(),
        val cursor: String = "",
        val hasMore: Boolean = false,
        val total: Int = 0,
    )

    /**
     * 演职员作品：`POST /novel/commentapi/user/profile/{uid}/v1/`。
     *
     * ⚠️ **路径里必须带 user_id**。之前写成了不带 id 的 `.../profile/v1/`，
     * 上游直接 404，上层只好退回"按名字搜索"，于是"点进出品方只看到 4 部"
     * （实测用户报的就是这个，官方 App 里同一家有十几二十部）。
     * 参数按官方作品 tab 形态（tab_type=23 / sub_tab_type=22），cursor 原样续传。
     */
    fun creatorWorks(userId: String, cursor: String): CreatorWorksPage {
        if (userId.isEmpty()) return CreatorWorksPage()
        val body = JSONObject()
            .put("aid", 8662)
            .put("comment_source", 1506)
            .put("count", 18)
            .put("cursor", cursor)
            .put("extra_param", JSONObject().put("is_video_tab_comment", "1"))
            .put("locate_post_id", "")
            .put("profile_user_type", 0)
            .put("sort", "time_desc")
            .put("sub_tab_type", 22)
            .put("tab_type", 23)
            .put("target_user_id", userId)
            .toString()
        val text = signedPost("/novel/commentapi/user/profile/$userId/v1/", body)
            ?: return CreatorWorksPage()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return CreatorWorksPage()
        val data = root.optJSONObject("data") ?: return CreatorWorksPage()
        val arr = data.optJSONArray("data_list")
        val out = mutableListOf<SeriesCard>()
        val seen = LinkedHashSet<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val v = item.optJSONObject("video") ?: continue
                // 作品的唯一钥匙是 video_id（就是 series_id），缺了这条没法跳转
                val sid = v.optString("video_id")
                if (sid.isEmpty() || !seen.add(sid)) continue
                val common = v.optJSONObject("common")
                val title = common?.optString("title").orEmpty()
                if (title.isEmpty()) continue
                out.add(
                    SeriesCard(
                        seriesId = sid,
                        vid = sid,
                        title = title,
                        cover = common?.optString("cover").orEmpty(),
                        episodeCount = v.optJSONObject("expand")?.optInt("episode_cnt", 0) ?: 0,
                        contentType = common?.optInt("video_content_type", 1004) ?: 1004,
                    ),
                )
            }
        }
        val info = data.optJSONObject("common_list_info")
        return CreatorWorksPage(
            cards = out,
            cursor = info?.optString("cursor").orEmpty(),
            hasMore = info?.optBoolean("has_more", false) ?: false,
            total = data.optJSONObject("hightlight")?.optInt("total", 0) ?: 0,
        )
    }

    /**
     * 账号同步：从签名机（账号中台）拿 Windows 版登录的 cookie。
     * 拿到后写入 Store；已有别的账号且未强推时保持现状。
     */
    /** 测试代签/匹配服务连通性。 */
    fun testSignService(base: String): Boolean {
        val text = request(base.trimEnd('/') + "/", null)
        return text.isNotEmpty()
    }

    // ---------------- 设备配对（原版同款：匹配码共享登录态） ----------------

    /**
     * 主设备：把本机登录态挂到 6 位匹配码上。
     * 服务端（代签服务 9100 的 /pair）存一份 cookies。
     */
    fun pairUpload(code: String): String {
        if (!App.state.loggedIn) error("本机还没有登录态，先同步登录再共享")
        val j = JSONObject(
            request(
                App.state.signUrl.trimEnd('/') + "/pair",
                JSONObject()
                    .put("code", code)
                    .put("cookies", App.state.accountCookies)
                    .put("user_id", App.state.accountUid)
                    .put("user_name", App.state.accountName)
                    .put("login_at", App.state.accountSyncedAt)
                    .toString(),
            ),
        )
        if (j.optBoolean("ok", false)) return code
        throw IllegalStateException(j.optString("error").ifEmpty { "上传失败" })
    }

    /** 从设备：按匹配码取登录态并落库，返回用户名。 */
    fun pairFetch(code: String): String {
        val text = request(App.state.signUrl.trimEnd('/') + "/pair/" + code, null)
        if (text.isBlank()) error("连不上匹配服务")
        val j = JSONObject(text)
        val cookies = j.optString("cookies")
        if (cookies.isBlank()) error(j.optString("error").ifEmpty { "该匹配码还没有绑定登录态" })
        App.state.accountCookies = cookies
        App.state.accountUid = j.optString("user_id")
        App.state.accountName = j.optString("user_name")
        App.state.accountSyncedAt = System.currentTimeMillis()
        App.state.accountSource = "pair"
        return App.state.accountName
    }

    fun syncAccount(force: Boolean = false): String {

        val text = request(App.state.signUrl.trimEnd('/') + "/account", null)
        if (text.isBlank()) error("连不上账号中台")
        val o = JSONObject(text)
        val cookies = o.optString("cookies")
        if (cookies.isEmpty()) error("账号中台里还没有登录态，请先在 Windows 版登录")
        val uid = o.optString("user_id")
        val name = o.optString("user_name")
        val same = uid.isNotEmpty() && uid == App.state.accountUid
        if (!App.state.loggedIn || same || force) {
            App.state.accountCookies = cookies
            App.state.accountUid = uid
            App.state.accountName = name
            App.state.accountSyncedAt = System.currentTimeMillis()
        }
        return name
    }

    /**
     * 上新日历。subscribe/list 的日历形态（active_panel=6）；
     * 翻页必须 offset + subscribe_offset 同值 + session_id，首页绝不能带 session_id。
     */
    fun calendar(date: String = "", offset: Int = 0, session: String = ""): JSONObject? {
        val query = JSONArray()
            .put(JSONArray().put("active_panel").put("6"))
            .put(JSONArray().put("gender_type").put("2"))
            .put(JSONArray().put("need_calendar_schema").put("true"))
            .put(JSONArray().put("need_personal_recommend").put("1"))
            .put(JSONArray().put("tab_style").put("2"))
            .put(JSONArray().put("tab_type").put("5"))
        if (date.isNotEmpty()) query.put(JSONArray().put("target_date").put(date))
        if (offset > 0) {
            query.put(JSONArray().put("offset").put(offset.toString()))
            query.put(JSONArray().put("subscribe_offset").put(offset.toString()))
            if (session.isNotEmpty()) query.put(JSONArray().put("session_id").put(session))
        }
        val text = signedGet("/reading/user/subscribe/list/v1/", query) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    fun followToggle(seriesId: String, on: Boolean): Boolean {
        if (!App.state.loggedIn) return false
        val body = JSONObject()
            .put("action_type", if (on) 34 else 35)
            .put(
                "business_param",
                JSONObject()
                    .put("book_id", 0)
                    .put("has_aigc_content", false)
                    .put("modify_count", 0)
                    .put(
                        "shark_param",
                        JSONObject()
                            .put("enter_from", "MainFragmentActivity")
                            .put("page_list", "MainFragmentActivity")
                            .put("previous_page", ""),
                    ),
            )
            .put("object_id", seriesId)
            .put("object_type", 10)
            .toString()
        return writeOk(signedPost("/novel/articleapi/do_action/v1/", body))
    }

    // ---------------- 清晰度档位 ----------------

    /** vid → 这一集所有档位（含直链/编码）。切清晰度不用再请求。 */
    /**
     * 档位缓存带 25 分钟有效期。
     *
     * 之前是永久的：直链 URL 自己带 x-expires（实测约 2 小时），
     * 挂机过夜后再切集/切清晰度一直拿过期地址去拉，402/403 之后又因为缓存不刷新
     * 永远好不了，只能杀进程。有效期比 URL 短一点最稳。
     */
    private val tierCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<PlayTier>>>()
    private const val TIER_TTL_MS = 25 * 60 * 1000L

    /**
     * 这一集的全部清晰度档位（高→低，服务器 want=all 已解好直链）。
     *
     * 番茄取流网关按 TLS 指纹放行，手机/电视直连一条也拿不到，所以档位表也走服务器。
     * 切档时直接用这里的 url + spade_a，不再请求上游——既快又不会出现
     * 「菜单里列的档位」和「实际取回来的档位」对不上的情况。
     */
    fun tiers(vid: String): List<PlayTier> {
        tierCache[vid]?.let { (at, list) ->
            if (System.currentTimeMillis() - at < TIER_TTL_MS) return list
            tierCache.remove(vid)
        }
        val out = runCatching {
            val list = playInfoData(vid, "all")?.optJSONArray("list")
                ?: return@runCatching emptyList()
            (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val h = o.optInt("height", 0)
                val u = o.optString("url")
                if (h <= 0 || !u.startsWith("http")) return@mapNotNull null
                PlayTier(
                    height = h,
                    definition = o.optString("definition"),
                    url = u,
                    backup = o.optString("backup"),
                    spadeA = o.optString("spade_a"),
                    size = o.optLong("size"),
                    codec = o.optString("codec"),
                )
            }.sortedByDescending { it.height }
        }.getOrDefault(emptyList())
        if (out.isNotEmpty()) tierCache[vid] = System.currentTimeMillis() to out
        return out
    }

    /** 能列的档位高度（过滤掉本机解不了的 bytevc2 档）。 */
    fun definitions(vid: String): List<Int> = tiers(vid).filter { it.playable }.map { it.height }

    // ---------------- 弹幕 ----------------

    /**
     * 拉一窗弹幕（官方滚窗协议，与 Windows 版同一套口径）。
     *
     * group_id 是**分集 vid**（在路径里），book_id 是剧 id，`start_offset_time`
     * 是本窗起点毫秒、`playlet_item_duration` 是本集真实时长。响应 cursor
     * 原样回传给下一窗、`start_offset_time` 推进到其 `end_offset_time`。
     * 风控降级时每窗条目少且 has_more=false——所以还是要逐窗拉，不能一次拉全。
     */
    fun danmakuPage(
        vid: String,
        seriesId: String,
        startMs: Long,
        itemDurationMs: Long,
        cursor: String,
    ): DmWindow? {
        val body = JSONObject()
            .put("aid", 8662)
            .put(
                "business_param",
                JSONObject()
                    .put("book_id", seriesId)
                    .put("need_danmaku_guide_type", JSONArray().put(2))
                    .put("playlet_item_duration", itemDurationMs.coerceAtLeast(0))
                    .put("start_offset_time", startMs.coerceAtLeast(0)),
            )
            .put("comment_source", 601)
            .put("comment_type", 20)
            .put("compliance_status", 0)
            .put("count", 90)
            .put("cursor", cursor)
            .put("group_id", vid)
            .put("group_type", 30)
            .put("server_channel", 1000)
            .put("sort", 1)
            .toString()
        val text = runCatching { signedPost("/novel/commentapi/comment/list/$vid/v1/", body) }
            .getOrNull() ?: return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        // 风控拒绝回的是大写 Code/Message，只认小写会把 110001 当成解析失败
        val code = if (root.has("code")) root.optInt("code", -1) else root.optInt("Code", -1)
        if (code != 0) {
            Log.d("PadDm", "danmaku code=$code ${root.optString("Message")}")
            return null
        }
        val data = root.optJSONObject("data") ?: return null
        val info = data.optJSONObject("common_list_info")
        val items = mutableListOf<DmItem>()
        val arr = data.optJSONArray("data_list") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i)?.optJSONObject("comment") ?: continue
            val id = c.optString("comment_id")
            val t = when (val content = c.optJSONObject("common")?.opt("content")) {
                is JSONObject -> content.optString("text")
                is String -> content
                else -> ""
            }
            if (id.isEmpty() || t.isBlank()) continue
            items.add(
                DmItem(
                    id = id,
                    text = t,
                    offsetMs = c.optJSONObject("expand")?.optLong("offset_time", 0L) ?: 0L,
                ),
            )
        }
        val nextCursor = info?.optString("cursor").orEmpty()
        val nextStart = runCatching { JSONObject(nextCursor).optLong("end_offset_time", 0L) }
            .getOrDefault(0L)
        return DmWindow(
            items = items,
            hasMore = info?.optBoolean("has_more", false) ?: false,
            cursor = nextCursor,
            nextStart = nextStart,
        )
    }

    /**
     * 发一条弹幕（comment/add，data_type=20）。
     *
     * 与评论同端点，差别只有 data_type / commit_source 与 business_param
     * 里的 offset（时间轴毫秒）。未登录直接返回失败，由 UI 提示去登录。
     */
    fun sendDanmaku(vid: String, seriesId: String, text: String, offsetMs: Long): Boolean {
        if (!App.state.loggedIn || text.isBlank()) return false
        val body = JSONObject()
            .put("aid", 8662)
            .put(
                "business_param",
                JSONObject()
                    .put("book_id", seriesId)
                    .put("ignore_urge_rule", false)
                    .put("offset", offsetMs.coerceAtLeast(0))
                    .put(
                        "shark_param",
                        JSONObject()
                            .put("aid", "8662")
                            .put("type", "short_play")
                            .put("enter_from", "MainFragmentActivity")
                            .put("page_list", "MainFragmentActivity")
                            .put("previous_page", ""),
                    ),
            )
            .put("commit_source", 1500)
            .put("data_type", 20)
            .put("group_id", vid)
            .put("group_type", 30)
            .put("text", text.trim())
            .toString()
        val resp = runCatching { signedPost("/novel/commentapi/comment/add/v1/", body) }.getOrNull()
            ?: return false
        val root = runCatching { JSONObject(resp) }.getOrNull() ?: return false
        val code = if (root.has("code")) root.optInt("code", -1) else root.optInt("Code", -1)
        if (code != 0) Log.d("PadDm", "send code=$code ${root.optString("Message")}")
        return code == 0
    }

    /**
     * 点卡片进去时先把卡片本身存一份。
     *
     * 有些「季」的 series_id 查不到详情（实测《失联二十五年》第二季直接回
     * 101000 pack ret empty），详情页只能空转。用列表里已知的标题/封面/集数兜底，
     * 至少能显示「待上线」并给出预约按钮，而不是一个点不动的死页面。
     */
    private val cardStash = java.util.concurrent.ConcurrentHashMap<String, SeriesCard>()

    fun stashCard(card: SeriesCard) {
        if (card.seriesId.isNotEmpty()) cardStash[card.seriesId] = card
    }

    fun stashedCard(seriesId: String): SeriesCard? = cardStash[seriesId]

    // ---------------- 短信登录（passport 域） ----------------

    /** 登录走 passport 域，与业务接口（fqnovel）不是同一套域名，但同一张会员体系。 */
    const val PASSPORT_HOST = "https://novel.snssdk.com"

    /** passport 系 form 请求的 content-type（官方原值，含 charset）。 */
    const val FORM_CT = "application/x-www-form-urlencoded; charset=UTF-8"

    /** passport 响应：正文 + 原始 Set-Cookie 行。 */
    data class RawResp(val body: String, val cookies: List<String>)

    /**
     * passport 域 form 请求。
     *
     * 和业务接口有三点不同，都得单独处理：
     * 1. body 是 form-urlencoded（不是 JSON），签名按这份字节算，所以必须先拼好再签；
     * 2. content-type 要用官方那个带 charset 的 form 值（签名服务默认按 JSON 出）；
     * 3. **必须收 Set-Cookie** —— 登录会话（sessionid / x-tt-token）只在这里下发，
     *    漏了就等于没登录（业务接口会一直游客态）。
     */
    fun passportPost(path: String, form: String, cookie: String, query: JSONArray): RawResp? {
        val (url, headers) = signEndpoint(PASSPORT_HOST, path, query, form) ?: return null
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            headers.keys().forEach { k ->
                if (!k.equals("Accept-Encoding", true) && !k.equals("Content-Type", true)) {
                    conn.setRequestProperty(k, headers.optString(k))
                }
            }
            conn.setRequestProperty("Content-Type", FORM_CT)
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
            conn.connectTimeout = 12_000
            conn.readTimeout = 25_000
            conn.doOutput = true
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val cookies = conn.headerFields["Set-Cookie"]?.toList().orEmpty()
            if (text.isEmpty()) error("passport 返回空内容（HTTP $code）")
            return RawResp(text, cookies)
        } finally {
            conn.disconnect()
        }
    }
}
