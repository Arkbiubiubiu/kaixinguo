package com.hongguotv.pad

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 一条服务端公告。 */
data class Notice(
    val id: String,
    val title: String,
    val text: String,
    val minCode: Int,
    val maxCode: Int,
    /** 生效开始时间（epoch 毫秒，0 = 不限）。 */
    val startAt: Long,
    /** 生效结束时间（epoch 毫秒，0 = 不限）。 */
    val endAt: Long,
    val mustUpdate: Boolean,
    val url: String,
    val dismissible: Boolean,
    val platform: String,
)

/**
 * 公告中心：从自建服务器拉 `notice.json`（随更新清单一起静态发布，放 /srv/hgtv-update）。
 * 按「平台 + 客户端版本号 + 生效时间窗」过滤，给出「强制更新公告」与「普通横幅公告」。
 * 拉取失败静默降级，不影响主流程（与 [Api.fetchFlags] 同款多 base 回退、X-Token、短超时）。
 */
object NoticeCenter {
    @Volatile
    var all: List<Notice> = emptyList()
        private set

    @Volatile
    var loaded: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 本次运行内被用户关掉的横幅 id（仅进程内，不持久化；下次启动会再提醒一次）。 */
    val dismissed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** 候选 base：playBases + historyBase（按逗号拆、去空白、去重，保序）。 */
    private fun candidateBases(): List<String> {
        val raw = ArrayList<String>()
        raw.addAll(App.playBases)
        App.historyBase.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { raw.add(it) }
        return raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** 阻塞拉取，务必在 IO 线程调用。多 base 依次尝试，任一成功即返回。 */
    fun load() {
        val bases = candidateBases()
        if (bases.isEmpty()) {
            loaded = true
            return
        }
        val token = App.playToken
        for (base in bases) {
            val ok = runCatching {
                val conn = URL(base.trimEnd('/') + "/notice.json").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("X-Token", token)
                    conn.connectTimeout = if (base.contains("192.168.")) 2_500 else 6_000
                    conn.readTimeout = 8_000
                    if (conn.responseCode !in 200..299) return@runCatching false
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val arr = JSONObject(text).optJSONArray("notices") ?: return@runCatching false
                    // 按 id 去重（保留第一次出现）
                    val byId = LinkedHashMap<String, Notice>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id")
                        if (id.isEmpty() || byId.containsKey(id)) continue
                        val must = o.optBoolean("mustUpdate", false)
                        byId[id] = Notice(
                            id = id,
                            title = o.optString("title"),
                            text = o.optString("text"),
                            minCode = o.optInt("minCode", 0),
                            maxCode = o.optInt("maxCode", 0),
                            startAt = o.optLong("startAt", o.optLong("start_at", 0L)),
                            endAt = o.optLong("endAt", o.optLong("end_at", 0L)),
                            mustUpdate = must,
                            url = o.optString("url"),
                            // 强制更新一律不可关
                            dismissible = !must && o.optBoolean("dismissible", true),
                            platform = o.optString("platform", "all"),
                        )
                    }
                    all = byId.values.toList()
                    lastError = null
                    true
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)
            if (ok) {
                loaded = true
                return
            }
        }
        lastError = "公告拉取失败"
        loaded = true
    }

    /** 该条是否命中平台：all / 精确相等 / 客户端为 tv|pad 时也认 android。 */
    private fun platformMatches(n: Notice, platform: String): Boolean {
        if (n.platform == "all") return true
        if (n.platform == platform) return true
        return (platform == "tv" || platform == "pad") && n.platform == "android"
    }

    /**
     * 选出当前该展示的：强制公告（阻塞）优先；否则一条可关的横幅。
     * 过滤条件：平台匹配 && 版本区间 && 生效时间窗。
     *
     * 版本区间注意：PC 与安卓的 versionCode 量纲不同（PC 1.x 万级、安卓几百），
     * 所以 platform=="all" 的通告**一律忽略 minCode/maxCode**（视为不限，全版本可见），
     * 只有 platform 明确为 pc / tv / pad / android 时才按各自量纲比较版本。
     */
    fun pick(curCode: Int, platform: String): Pair<Notice?, Notice?> {
        val now = System.currentTimeMillis()
        val applicable = all.filter { n ->
            platformMatches(n, platform) &&
                // all 通告跨平台量纲不可比，忽略版本区间；其余按该平台各自 versionCode 比较
                (n.platform == "all" ||
                    ((n.minCode == 0 || curCode >= n.minCode) &&
                        (n.maxCode == 0 || curCode <= n.maxCode))) &&
                (n.startAt == 0L || now >= n.startAt) &&
                (n.endAt == 0L || now <= n.endAt)
        }
        val force = applicable.lastOrNull { it.mustUpdate }
        val banner = applicable.lastOrNull { !it.mustUpdate && !dismissed.contains(it.id) }
        return force to banner
    }
}
