package com.hongguotv.pad

import com.hongguotv.pad.sign.Device
import com.hongguotv.pad.sign.Digest
import com.hongguotv.pad.sign.Gorgon
import com.hongguotv.pad.sign.Signer
import com.hongguotv.pad.sign.base64
import com.hongguotv.pad.sign.bytesToHexUpper
import com.hongguotv.pad.sign.hexToBytes
import com.hongguotv.pad.sign.le32
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 登录平面「每机唯一设备身份」：把固定写死的公用设备身份，换成每台电脑注册得到的唯一身份。
 *
 * 背景：上游内容/登录网关把「签名设备身份」和「请求里的 device_id」当成同一台设备校验，
 * 全体共用写死身份 + 多人同时用 → 被风控 / 返回 1003。注册一台设备拿到本机唯一的
 * device_id / install_id / device_token，再让本地签名统一用这份身份，就能消除该来源。
 *
 * 本模块移植自 aston314 登录平面（tools/_ref/Passport.kt 的 PassportClient.registerDevice）：
 *  * 注册画像固定为 **7.3.5.32 / Redmi 25053RT47C / Android 16**（VERSION/VERSION_CODE 见下）；
 *  * 注册请求体 = ttEncrypt(gzip(JSON))，固定 10 字节 gzip 头，anon、不带业务签名头；
 *  * **登录签名画像必须与注册画像逐字段一致**，否则 device_token 与签名对不上又会 1003。
 * 因此 [buildProfile] 构造的 [Device] 用的就是同一套 7.3.5.32 字段，只是把 device_id / iid /
 * cdid / openudid 覆盖成注册所得。
 *
 * 私密约束：device_token / ttreq 属私有，调用方负责按私密存储，本模块不写日志、不落公开处。
 */
data class RegisteredDevice(
    val deviceId: String = "",
    val installId: String = "",
    val cdid: String = "",
    val openudid: String = "",
    val clientUdid: String = "",
    /** 平台下发的设备 token（来自响应头 X-Tt-Token 或 body.device_token）。 */
    val deviceToken: String = "",
    /** Set-Cookie 里回写的 ttreq / store-region（部分接口要带）。 */
    val ttreq: String = "",
    val storeRegion: String = "",
    val cookies: Map<String, String> = emptyMap(),
) {
    /** 身份齐全（device_id + install_id）。用于构造签名 Profile。 */
    val identityValid: Boolean get() = deviceId.isNotBlank() && installId.isNotBlank() && deviceId != "0" && installId != "0"

    /** 原子启用门槛：三项全有效才允许切到每机身份（否则回退固定 DEVICE）。 */
    val fullyValid: Boolean get() = identityValid && deviceToken.isNotBlank()
}

/**
 * 每机身份的总入口（供 pc-client 调用）：
 *
 *  1) `register()`             —— 调上游注册，返回 [RegisteredDevice]（失败 null，绝不抛）。
 *  2) `buildProfile(reg)`      —— 把注册结果构造成 7.3.5.32 画像的签名 [Device]。
 *  3) `install(reg)`           —— 原子注入 LocalSign：仅 [RegisteredDevice.fullyValid] 才启用，返回是否启用。
 *  4) `clear()`                —— 回退：把注入置 null，恢复固定 DEVICE 旧行为。
 *  5) `active`                 —— 当前是否已启用每机身份。
 *
 * 只影响 LocalSign.sign() 这一路（阅读内容 + passport 登录 + 账号业务）；取流 signPlay / PLAY_DEVICE 不动。
 */
object MachineIdentity {

    // ---- 注册/签名统一画像：7.3.5.32 ----
    const val VERSION = "7.3.5.32"
    const val VERSION_CODE = "73532"
    const val DEVICE_BASE = "https://log.snssdk.com"
    private const val UA =
        "com.phoenix.read/$VERSION_CODE (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    private val KDF_SALT = hexToBytes(
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
    )
    private val TT_MAGIC = byteArrayOf(0x74, 0x63, 0x05, 0x10, 0x00, 0x00)
    private val random = SecureRandom()

    private val http by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    val active: Boolean get() = LocalSign.machineActive()

    /** 注册登录平面设备。任何失败/坏 JSON 都返回 null（调用方据此回退固定 DEVICE）。 */
    fun register(seedCdid: String = "", seedOpenudid: String = "", seedClientUdid: String = ""): RegisteredDevice? {
        return runCatching {
            val cdid = seedCdid.ifBlank { uuid() }
            val openudid = seedOpenudid.ifBlank { ByteArray(8).also { random.nextBytes(it) }.let { bytesToHexUpper(it).lowercase(Locale.ROOT) } }
            val clientUdid = seedClientUdid.ifBlank { uuid() }
            val now = System.currentTimeMillis()
            val seed = ByteArray(32).also { random.nextBytes(it) }
            val payload = devicePayload(cdid, clientUdid, openudid, now)
            val body = ttEncryptFixedHeader(gzipFixedHeader(payload), seed)
            val query = registerQuery(now)
            val ts = now / 1000
            val req = okhttp3.Request.Builder()
                .url("$DEVICE_BASE/service/2/device_register/?$query")
                .post(okhttp3.RequestBody.create(null, body))
                .header("User-Agent", UA)
                .header("Content-Type", "application/octet-stream;tt-data=a")
                .header("Content-Encoding", "gzip")
                .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
                .header("X-Tt-Request-Tag", "t=0;n=1")
                .header("Log-Encode-Type", "gzip")
                .header("Lc", "102")
                .header("Sdk-Version", "2")
                .header("Passport-Sdk-Version", "60755")
                .header("Passport-Sdk-Settings", "device_transfer_s_0,device_transfer_ab_1")
                .header("X-SS-DP", "8662")
                .header("X-Vc-Bdturing-Sdk-Version", "4.0.3.cn")
                .header("X-Tt-Store-Region", "cn")
                .header("X-Tt-Store-Region-Src", "did")
                .header("X-Khronos", ts.toString())
                .header("X-Argus", base64(le32(ts.toInt())))
                .header("X-SS-Req-Ticket", now.toString())
                .header("X-SS-STUB", bytesToHexUpper(Digest.md5Raw(body)))
                .header("X-Gorgon", Gorgon.sign(query, body, ts.toInt(), random.nextInt() and 0xFFFF))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return runCatching { null }.getOrNull()
                val raw = resp.body?.bytes() ?: return runCatching { null }.getOrNull()
                val text = if (raw.size >= 38 && raw.copyOfRange(0, 6).contentEquals(TT_MAGIC)) ttDecrypt(raw) else raw
                val json = JSONObject(String(text, Charsets.UTF_8))
                val did = json.optString("device_id_str", "").ifBlank { json.opt("device_id")?.toString().orEmpty() }
                val iid = json.optString("install_id_str", "").ifBlank { json.opt("install_id")?.toString().orEmpty() }
                if (did.isBlank() || iid.isBlank() || did == "0" || iid == "0") return runCatching { null }.getOrNull()
                val cookieMap = LinkedHashMap<String, String>()
                resp.headers("Set-Cookie").forEach { line ->
                    val pair = line.substringBefore(';')
                    val name = pair.substringBefore('=', "").trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) cookieMap[name] = value
                }
                val token = resp.header("X-Tt-Token").orEmpty().ifBlank {
                    json.optString("device_token", "").ifBlank { json.optString("device_token_str", "") }
                }
                RegisteredDevice(
                    deviceId = did,
                    installId = iid,
                    cdid = cdid,
                    openudid = openudid,
                    clientUdid = clientUdid,
                    deviceToken = token,
                    ttreq = cookieMap["ttreq"] ?: json.optString("ttreq", ""),
                    storeRegion = cookieMap["store-region"] ?: json.optString("store_region", ""),
                    cookies = cookieMap,
                )
            }
        }.getOrNull()
    }

    /** 由注册结果构造 7.3.5.32 画像的签名 Device（device_id/iid/cdid/openudid 覆盖成注册所得）。 */
    fun buildProfile(reg: RegisteredDevice): Device? {
        if (!reg.identityValid) return null
        val fields = ArrayList<Pair<String, String>>(24)
        fields.add("ac" to "wifi")
        fields.add("aid" to "8662")
        fields.add("app_name" to "novelread")
        fields.add("channel" to "update_64")
        fields.add("device_brand" to "Redmi")
        fields.add("device_platform" to "android")
        fields.add("device_type" to "25053RT47C")
        fields.add("dpi" to "520")
        fields.add("language" to "zh")
        fields.add("manifest_version_code" to VERSION_CODE)
        fields.add("os" to "android")
        fields.add("os_api" to "36")
        fields.add("os_version" to "16")
        fields.add("resolution" to "1280*2772")
        fields.add("ssmix" to "a")
        fields.add("update_version_code" to VERSION_CODE)
        fields.add("version_code" to VERSION_CODE)
        fields.add("version_name" to VERSION)
        // 注册所得身份（必须与请求体里的 device_id/iid/cdid 完全一致）
        fields.add("device_id" to reg.deviceId)
        fields.add("iid" to reg.installId)
        fields.add("cdid" to reg.cdid)
        fields.add("openudid" to reg.openudid)
        return Device(fields, UA)
    }

    /**
     * 原子注入：只有 [RegisteredDevice.fullyValid]（device_id+install_id+device_token 全有效）才启用，
     * 否则不动（保持固定 DEVICE 旧行为），返回是否启用。
     */
    fun install(reg: RegisteredDevice?): Boolean {
        if (reg == null || !reg.fullyValid) return false
        val device = buildProfile(reg) ?: return false
        LocalSign.installMachineDevice(device)
        return true
    }

    /** 回退入口：把每机身份置空，恢复固定 DEVICE 旧行为。 */
    fun clear() = LocalSign.installMachineDevice(null)

    // ---------------- 内部：注册请求体/加密 ----------------

    private fun registerQuery(now: Long): String {
        val list = listOf(
            "ac" to "wifi",
            "aid" to "8662",
            "app_name" to "novelread",
            "channel" to "update_64",
            "device_brand" to "Redmi",
            "device_platform" to "android",
            "device_type" to "25053RT47C",
            "dpi" to "520",
            "language" to "zh",
            "manifest_version_code" to VERSION_CODE,
            "os" to "android",
            "os_api" to "36",
            "os_version" to "16",
            "resolution" to "1280*2772",
            "ssmix" to "a",
            "update_version_code" to VERSION_CODE,
            "version_code" to VERSION_CODE,
            "version_name" to VERSION,
            "tt_data" to "a",
            "ts" to (now / 1000).toString(),
            "_rticket" to now.toString(),
            "package" to "com.phoenix.read",
        )
        return Signer.encodeQuery(list)
    }

    private fun devicePayload(cdid: String, clientUdid: String, openudid: String, now: Long): ByteArray {
        val header = JSONObject()
            .put("aid", 8662).put("access", "wifi").put("app_version", VERSION).put("app_version_minor", "")
            .put("channel", "update_64").put("device_brand", "Redmi").put("device_manufacturer", "Redmi")
            .put("device_model", "25053RT47C").put("device_category", "phone").put("device_platform", "android")
            .put("os", "Android").put("os_api", 36).put("os_version", "16").put("density_dpi", 520)
            .put("display_density", "xxxhdpi").put("resolution", "2772x1280").put("language", "zh")
            .put("timezone", 8).put("tz_name", "Asia/Shanghai").put("tz_offset", 28800).put("region", "CN")
            .put("package", "com.phoenix.read").put("version_code", 73532).put("manifest_version_code", 73532)
            .put("update_version_code", 73532).put("apk_first_install_time", now)
            .put("cdid", cdid).put("clientudid", clientUdid).put("openudid", openudid)
            .put("cpu_abi", "arm64-v8a").put("display_name", "红果短剧")
            .put("sdk_version", "3.7.2").put("sdk_target_version", 29).put("sdk_flavor", "china")
            .put("guest_mode", 0).put("is_system_app", 0).put("not_request_sender", 0).put("oaid_may_support", true)
            .put("ipv6_list", org.json.JSONArray()).put("sim_serial_number", org.json.JSONArray())
            .put(
                "custom",
                JSONObject().put("is_android_fold", 0).put("filter_warn", 0).put("web_ua", "").put("is_android_pad", 0).put("client_ipv4", "")
            )
        return JSONObject()
            .put("magic_tag", "ss_app_log")
            .put("header", header)
            .put("_gen_time", now)
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun gzipFixedHeader(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        val z = out.toByteArray()
        System.arraycopy(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0), 0, z, 0, 10)
        return z
    }

    private fun ttEncryptFixedHeader(z: ByteArray, seed: ByteArray): ByteArray {
        val hash = sha512(z)
        val p = hash + z
        val padding = 16 - p.size % 16
        val padded = p + ByteArray(padding) { padding.toByte() }
        val derived = sha512(sha512(seed) + KDF_SALT)
        val encrypted = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived.copyOfRange(0, 16), "AES"), IvParameterSpec(derived.copyOfRange(16, 32)))
            doFinal(padded)
        }
        return TT_MAGIC + seed + encrypted
    }

    private fun ttDecrypt(raw: ByteArray): ByteArray {
        require(raw.size >= 118 && (raw.size - 38) % 16 == 0) { "设备注册加密响应无效" }
        val derived = sha512(sha512(raw.copyOfRange(6, 38)) + KDF_SALT)
        val p = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(derived.copyOfRange(0, 16), "AES"), IvParameterSpec(derived.copyOfRange(16, 32)))
            doFinal(raw.copyOfRange(38, raw.size))
        }
        val padding = p.last().toInt() and 0xFF
        require(padding in 1..16 && p.copyOfRange(p.size - padding, p.size).all { (it.toInt() and 0xFF) == padding }) { "设备注册加密响应无效" }
        val content = p.copyOfRange(0, p.size - padding)
        require(content.size >= 64) { "设备注册加密响应无效" }
        require(sha512(content.copyOfRange(64, content.size)).contentEquals(content.copyOfRange(0, 64))) { "设备注册响应校验失败" }
        return GZIPInputStream(content.copyOfRange(64, content.size).inputStream()).readBytes()
    }

    private fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    private fun uuid(): String {
        val b = ByteArray(16).also { random.nextBytes(it) }
        b[6] = ((b[6].toInt() and 15) or 64).toByte()
        b[8] = ((b[8].toInt() and 63) or 128).toByte()
        val h = bytesToHexUpper(b).lowercase(Locale.ROOT)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
}
