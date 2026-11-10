package com.hongguotv.pad.sign

import java.io.File

/**
 * 从设备档案 JSON 构造 [Device]。
 *
 * 顺序规则必须与参考实现一致（签名对顺序敏感）：
 *   1. 注册模板的 24 个字段（按固定顺序）
 *   2. cdid、openudid（由调用方给定）
 *   3. 档案里其余字段按**字母序**追加（空值、created_at、store_region、user_agent 跳过）
 */
object Profile {

    /** 注册模板的字段顺序（与参考实现逐项一致）。 */
    private val BASE: List<Pair<String, String>> = listOf(
        "ac" to "wifi",
        "aid" to "8662",
        "app_name" to "novelread",
        "channel" to "xiaomi_8662_64",
        "compliance_status" to "0",
        "device_brand" to "xiaomi",
        "device_platform" to "android",
        "device_type" to "23127PN0CC",
        "dpi" to "460",
        "dragon_device_type" to "phone",
        "host_abi" to "arm64-v8a",
        "is_android_pad_screen" to "0",
        "language" to "zh",
        "manifest_version_code" to "74232",
        "os" to "android",
        "os_api" to "34",
        "os_version" to "14",
        "player_so_load" to "1",
        "pv_player" to "74232",
        "resolution" to "1200*2670",
        "ssmix" to "a",
        "update_version_code" to "74232",
        "version_code" to "74232",
        "version_name" to "7.4.2.32",
    )

    private val SKIP = setOf("created_at", "store_region", "user_agent")

    fun fromJson(text: String, userAgent: String = Consts.UA): Device {
        val raw = Json.obj(text)
        val fields = ArrayList<Pair<String, String>>(BASE)
        val index = HashMap<String, Int>()
        fields.forEachIndexed { i, pair -> index[pair.first] = i }

        fun put(key: String, value: String) {
            val at = index[key]
            if (at != null) {
                fields[at] = key to value
            } else {
                index[key] = fields.size
                fields.add(key to value)
            }
        }

        put("cdid", raw["cdid"] as? String ?: "")
        put("openudid", raw["openudid"] as? String ?: "")

        // 其余字段按字母序（与 serde_json 的 BTreeMap 迭代顺序一致）
        raw.keys.sorted().forEach { key ->
            if (key == "cdid" || key == "openudid" || key in SKIP) return@forEach
            val value = raw[key] as? String ?: return@forEach
            if (value.isEmpty()) return@forEach
            put(key, value)
        }
        return Device(fields, userAgent)
    }

    fun fromFile(file: File, userAgent: String = Consts.UA): Device =
        fromJson(file.readText(Charsets.UTF_8), userAgent)
}
