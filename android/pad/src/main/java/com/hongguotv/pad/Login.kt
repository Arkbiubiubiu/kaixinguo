package com.hongguotv.pad

import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机号验证码登录（番茄 passport）。
 *
 * 协议与桌面版（`hongguo-api/src/login`）逐字段对齐，三个端点：
 *
 * ```text
 * POST /passport/mobile/send_code/v1/   mobile = XOR(0x05) hex，且必须带 +86 前缀
 * POST /passport/mobile/sms_login/      code 同套密文；风控要求二次验证时带 mfa 参数
 * POST /passport/upsms/verify/          「短信上行验证」轮询，error_code=1045 表示还在等
 * ```
 *
 * 会话在 Set-Cookie 里（sessionid / x-tt-token …），洗成 `k=v; k=v` 存本机。
 * **每个端存自己那份 cookie**，所以每台设备都能独立登录、也都能当主账号
 * （再通过 6 位匹配码共享给别的端）。
 */
object LoginApi {

    private const val SEND_CODE = "/passport/mobile/send_code/v1/"
    private const val SMS_LOGIN = "/passport/mobile/sms_login/"
    private const val UPSMS_VERIFY = "/passport/upsms/verify/"

    /** 发码结果：提示文案 + 服务端要求的重发间隔。 */
    data class SentCode(val message: String, val retryAfter: Int)

    /** 登录成功后的账号信息。 */
    data class Account(val name: String, val uid: String, val cookies: String)

    /**
     * 风控要求「短信上行验证」：用户要用手里的手机号给指定号码发一条短信，
     * 平台核对后才放行。这不是错误，是登录流程的一步，所以单独用异常类型承载。
     */
    class NeedMfa(
        val hint: String,
        /** 要发送到的号码（可能为空，为空时按提示文案引导） */
        val channel: String,
        /** 短信内容 */
        val content: String,
        val ctx: MfaCtx,
    ) : Exception(hint)

    data class MfaCtx(
        val retryTag: String,
        val smsCodeKey: String,
        val encryptUid: String,
        val logId: String,
        val verifyReason: String,
        val verifyScene: String,
        val copywritingKey: String,
        val diversionTag: String,
        var mfaToken: String,
        val mobile: String,
        val code: String,
    )

    /**
     * 设备档案：**与代签服务（/opt/rhg/device.json）严格同一台机器**。
     *
     * 签名票根里钉着这套设备身份，表单里的 iid/device_id 若与它不一致，
     * 服务端会判成伪造请求（发码直接回 1003「手机号错误」这种误导性错误）。
     * 改这里等于换设备，账号会被重新绑定，别随手动。
     */
    private val DEVICE = linkedMapOf(
        "ac" to "wifi",
        "aid" to "8662",
        "app_name" to "novelread",
        "cdid" to "a694f0f8-266a-421a-bef3-ee760b049478",
        "channel" to "xiaomi_8662_64",
        "device_brand" to "xiaomi",
        "device_id" to "23562609503024",
        "device_platform" to "android",
        "device_type" to "23127PN0CC",
        "dpi" to "460",
        "host_abi" to "arm64-v8a",
        "iid" to "23562609507120",
        "language" to "zh",
        "manifest_version_code" to "74232",
        "os" to "android",
        "os_api" to "34",
        "os_version" to "14",
        "resolution" to "1200*2670",
        "ssmix" to "a",
        "update_version_code" to "74232",
        "version_code" to "74232",
        "version_name" to "7.4.2.32",
        // 以下是官方客户端的运行时状态值（电量/网速/会话计数…）。
        // 服务端只拿它们作风控参考，不校验真实性，但**字段不能缺**。
        "app_dark_mode" to "0",
        "app_mini_window" to "0",
        "battery_pct" to "72",
        "charging" to "1",
        "cold_start_session_cnt_in_day" to "100",
        "cold_start_session_cnt_in_life" to "76",
        "cold_start_session_id" to "4357f7af-60c0-4a52-af34-473c59155de9",
        "compliance_status" to "0",
        "current_volume" to "1",
        "down_speed" to "311565",
        "dragon_device_type" to "phone",
        "font_scale" to "100",
        "gender" to "2",
        "har_status" to "0",
        "is_android_pad_screen" to "0",
        "is_power_save_mode" to "0",
        "need_personal_recommend" to "1",
        "network_type" to "4",
        "normal_session_cnt_in_day" to "182",
        "normal_session_cnt_in_life" to "270",
        "normal_session_id" to "23661b98-eac9-4960-81a4-d8837826b43f%23160",
        "player_so_load" to "1",
        "pv_player" to "74232",
        "screen_brightness" to "963",
        "sys_dark_mode" to "0",
        "sys_mini_window" to "1",
    )

    // ---------------- 对外 ----------------

    /** 手机号形态校验：11 位、1 开头、全数字（号段交给服务端判）。 */
    fun isMobileOk(mobile: String) =
        mobile.length == 11 && mobile[0] == '1' && mobile.all { it.isDigit() }

    /** 发送验证码。 */
    fun sendCode(mobile: String): SentCode {
        if (!isMobileOk(mobile)) throw IllegalArgumentException("请输入 11 位手机号")
        val pairs = devicePairs() + listOf(
            "mobile" to encodeMobile(mobile),
            "mix_mode" to "1",
            "account_sdk_source" to "app",
            "passport_support_flow" to "captcha,verify",
            // type=3731 才是「短信登录发码」；type=1 会被当成换绑场景，
            // 号码已绑过账号时直接回 1001。
            "type" to "3731",
            "auto_read" to "0",
            "unbind_exist" to "34",
        )
        val resp = call(SEND_CODE, pairs, "", sdkQuery(withRom = true))
        val j = JSONObject(resp.body)
        checkError(j, "发送验证码失败")
        // 发码会话凭据：登录提交时要当 cookie 带上，否则服务端不认这次验证码
        Store.loginCsrf = cookieValue(resp.cookies, "passport_csrf_token")
        val data = j.optJSONObject("data")
        // 成功时 description 是 "success"（英文直译），别原样丢给用户
        val raw = data?.optString("description").orEmpty()
            .ifBlank { j.optString("message") }
        return SentCode(
            message = if (raw.isBlank() || raw.equals("success", true) || raw.equals("ok", true)) {
                "验证码已发送，请查看短信"
            } else {
                raw
            },
            retryAfter = data?.optInt("retry_time", 60)?.coerceIn(30, 300) ?: 60,
        )
    }

    /**
     * 提交验证码登录。成功即把会话落库（本机即主账号）。
     * 风控要求上行短信验证时抛 [NeedMfa]，调用方展示提示后按秒轮询 [pollMfa]。
     */
    fun login(mobile: String, code: String): Account {
        val jwt = submit(mobile, code, null)
        val cookies = jwt.cookies
        val user = parseUser(jwt.body)
        saveAccount(cookies, user.first, user.second)
        return Account(user.first, user.second, cookies)
    }

    /**
     * 一次 MFA 轮询：返回 true 表示已通过并完成登录。
     *
     * 平台流程是「用户给指定号发短信 → 服务端标记 registered → 我们再拿
     * mfa token 重发一次登录换会话」，所以这里不是单纯查询状态。
     */
    fun pollMfa(mfa: NeedMfa): Boolean {
        val ctx = mfa.ctx
        val biz = JSONObject()
            .put("passport_mfa_retry_tag", ctx.retryTag)
            .put("sms_code_key", ctx.smsCodeKey)
        val pairs = listOf(
            "biz_params" to biz.toString(),
            "copywriting_key" to ctx.copywritingKey,
            "encrypt_uid" to ctx.encryptUid,
            "ies_safety_diversion_tag" to ctx.diversionTag,
            "new_authn_sdk_log_id" to ctx.logId,
            "new_authn_sdk_verify_reason" to ctx.verifyReason,
            "new_authn_sdk_verify_scene" to ctx.verifyScene,
            "new_authn_sdk_version" to "1.1.31",
            "request_tag_from" to "h5",
            "verify_reason" to ctx.verifyReason,
            "verify_scene" to ctx.verifyScene,
            // 官方原样带的空字段：形态校验认它们，空也要有
            "new_verify_flow" to "",
            "verify_ticket" to "",
        )
        val cookie = buildString {
            if (Store.loginCsrf.isNotBlank()) append("passport_csrf_token=").append(Store.loginCsrf)
            if (ctx.mfaToken.isNotBlank()) {
                if (isNotEmpty()) append("; ")
                append("passport_mfa_token=").append(ctx.mfaToken)
            }
        }
        val resp = call(UPSMS_VERIFY, pairs, cookie, sdkQuery(withRom = false))
        val j = JSONObject(resp.body)
        val code = errorCodeOf(j)
        if (code == 1045) return false          // 还在等用户发短信
        if (code != 0) throw IllegalStateException(errorText(j, "上行验证失败"))

        val data = j.optJSONObject("data")
        val newToken = cookieValue(resp.cookies, "passport_mfa_token")
        if (newToken.isNotBlank()) ctx.mfaToken = newToken
        // registered=true：平台侧已核验通过，但还没有会话，要重发登录
        if (data?.optBoolean("registered") != true) return false

        val jwt = submit(ctx.mobile, ctx.code, ctx)
        saveAccount(jwt.cookies, parseUser(jwt.body).first, parseUser(jwt.body).second)
        return true
    }

    // ---------------- 内部 ----------------

    private data class Jwt(val body: String, val cookies: String)

    private fun submit(mobile: String, code: String, mfa: MfaCtx?): Jwt {
        if (!isMobileOk(mobile)) throw IllegalArgumentException("请输入 11 位手机号")
        if (code.length < 4) throw IllegalArgumentException("请输入验证码")
        val pairs = devicePairs() + listOf(
            "mobile" to encodeMobile(mobile),
            "mix_mode" to "1",
            "code" to encodeCode(code),
            "account_sdk_source" to "app",
            "passport_support_flow" to "captcha,verify",
        ) + if (mfa != null) {
            listOf(
                "passport_mfa_retry_tag" to mfa.retryTag,
                "sms_code_key" to encodeCode(mfa.smsCodeKey),
            )
        } else {
            emptyList()
        }

        val cookie = buildString {
            if (Store.loginCsrf.isNotBlank()) append("passport_csrf_token=").append(Store.loginCsrf)
            if (mfa != null && mfa.mfaToken.isNotBlank()) {
                if (isNotEmpty()) append("; ")
                append("passport_mfa_token=").append(mfa.mfaToken)
            }
        }
        val resp = call(SMS_LOGIN, pairs, cookie, sdkQuery(withRom = true))
        val j = JSONObject(resp.body)

        // MFA 特征：data.biz_params 里 retry_tag 与 sms_code_key 成对出现
        val data = j.optJSONObject("data")
        val biz = data?.optJSONObject("biz_params")?.takeIf { it.has("passport_mfa_retry_tag") } ?: j
        val tag = biz.optString("passport_mfa_retry_tag")
        val key = biz.optString("sms_code_key")
        if (tag.isNotBlank() && key.isNotBlank()) {
            val way = data?.optJSONArray("verify_ways")
                ?.let { arr ->
                    (0 until arr.length()).map { arr.optJSONObject(it) }
                        .firstOrNull { it?.optString("verify_way") == "mobile_up_sms_verify" }
                }
            val ctx = MfaCtx(
                retryTag = tag,
                smsCodeKey = key,
                encryptUid = fieldOf(j, "data.encrypt_uid"),
                logId = fieldOf(j, "data.event_params.log_id"),
                verifyReason = fieldOf(j, "data.event_params.verify_reason").ifBlank { "ato" },
                verifyScene = fieldOf(j, "data.event_params.verify_scene").ifBlank { "sms_login" },
                copywritingKey = fieldOf(j, "data.common_params.copywriting_key").ifBlank { "sms_login" },
                diversionTag = fieldOf(j, "data.common_params.ies_safety_diversion_tag").ifBlank { "mfa" },
                mfaToken = cookieValue(resp.cookies, "passport_mfa_token"),
                mobile = mobile,
                code = code,
            )
            val hint = fieldOf(j, "data.verify_scene_desc").ifBlank {
                fieldOf(j, "data.description").ifBlank { j.optString("message").ifBlank { "安全验证" } }
            }
            throw NeedMfa(
                hint = hint,
                channel = way?.optString("channel_mobile").orEmpty(),
                content = way?.optString("sms_content").orEmpty(),
                ctx = ctx,
            )
        }

        checkError(j, "登录失败")
        val cookies = extractCookies(resp.cookies)
        if (cookies.isEmpty()) throw IllegalStateException("登录成功但没拿到会话，请重试")
        return Jwt(resp.body, cookies)
    }

    private fun saveAccount(cookies: String, name: String, uid: String) {
        Store.accountCookies = cookies
        Store.accountName = name
        Store.accountUid = uid
        // local = 本机手机号登录（可作主设备共享给其它端）
        Store.accountSource = "local"
        Store.accountSyncedAt = System.currentTimeMillis()
        Store.loginCsrf = ""
    }

    private fun call(path: String, pairs: List<Pair<String, String>>, cookie: String, query: JSONArray): Api.RawResp =
        Api.passportPost(path, encodeForm(pairs), cookie, query)
            ?: throw IllegalStateException("签名服务不可用，请检查网络后重试")

    private fun devicePairs(): List<Pair<String, String>> =
        listOf("_rticket" to System.currentTimeMillis().toString()) + DEVICE.map { it.key to it.value }

    private fun sdkQuery(withRom: Boolean): JSONArray {
        val q = JSONArray()
        listOf(
            "cronet_version" to "8d40f833_2026-03-03",
            "passport-sdk-version" to "5051452",
            "ttnet_version" to "4.2.243.31-douyin",
            "use_new_token_expire_rule" to "true",
            "use_store_region_cookie" to "1",
        ).forEach { (k, v) -> q.put(JSONArray().put(k).put(v)) }
        if (withRom) q.put(JSONArray().put("rom_version").put("miui_V12_V12.0.8.0.RKHCNXM"))
        return q
    }

    /** 手机号：`+86` + 号码，逐字节 XOR 0x05 后转小写 hex（不带前缀服务端按残缺号处理）。 */
    private fun encodeMobile(mobile: String) = xor05Hex("+86$mobile")

    /** 验证码：与手机号同一套密文，**但不是手机号格式**，所以不能加前缀。 */
    private fun encodeCode(code: String) = xor05Hex(code)

    private fun xor05Hex(s: String): String {
        val sb = StringBuilder(s.length * 2)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            sb.append(String.format("%02x", (b.toInt() and 0xFF) xor 0x05))
        }
        return sb.toString()
    }

    /** form-urlencoded（encodeURIComponent 语义：空格编码成 %20）。 */
    private fun encodeForm(pairs: List<Pair<String, String>>) =
        pairs.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    private fun enc(s: String): String {
        val keep = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
        val sb = StringBuilder(s.length + 8)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (keep.indexOf(c) >= 0) sb.append(c)
            else sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    /**
     * Set-Cookie 洗成 `k=v; k=v`。
     *
     * 同一 cookie 服务端会重复下发（odin_tt 尤其明显），后值覆盖、位置保持首现，
     * 否则存下来的串里会叠出一堆同名项。
     */
    private fun extractCookies(lines: List<String>): String {
        val pairs = LinkedHashMap<String, String>()
        for (line in lines) {
            val kv = line.split(';').firstOrNull()?.trim().orEmpty()
            val i = kv.indexOf('=')
            if (i <= 0) continue
            pairs[kv.substring(0, i)] = kv.substring(i + 1)
        }
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun cookieValue(lines: List<String>, name: String): String {
        for (line in lines) {
            val kv = line.split(';').firstOrNull()?.trim().orEmpty()
            if (kv.startsWith("$name=")) return kv.substring(name.length + 1)
        }
        return ""
    }

    // passport 惯例：错误码在 data 里，顶层只有 message
    private fun errorCodeOf(j: JSONObject): Int {
        val d = j.optJSONObject("data")
        val inner = d?.optInt("error_code", 0) ?: 0
        return if (inner != 0) inner else j.optInt("error_code", 0)
    }

    private fun errorText(j: JSONObject, prefix: String): String {
        val d = j.optJSONObject("data")
        val msg = d?.optString("description").orEmpty()
            .ifBlank { d?.optString("message").orEmpty() }
            .ifBlank { j.optString("message") }
            .ifBlank { "未知错误" }
        return "$prefix（${errorCodeOf(j)}）：$msg"
    }

    private fun checkError(j: JSONObject, prefix: String) {
        val code = errorCodeOf(j)
        if (code != 0) throw IllegalStateException(errorText(j, prefix))
    }

    /** 按 `a.b.c` 取字符串字段（登录响应层级在端点间不一致，统一走这里）。 */
    private fun fieldOf(j: JSONObject, path: String): String {
        var cur: JSONObject = j
        val parts = path.split('.')
        for (p in parts.dropLast(1)) {
            cur = cur.optJSONObject(p) ?: return ""
        }
        val v = cur.opt(parts.last())
        return if (v == null || v == JSONObject.NULL) "" else v.toString()
    }

    private fun parseUser(body: String): Pair<String, String> {
        val j = runCatching { JSONObject(body) }.getOrNull() ?: return "" to ""
        val data = j.optJSONObject("data") ?: j
        val inner = data.optJSONObject("user_info") ?: data
        val name = listOf("name", "user_name", "username", "nick_name")
            .map { inner.optString(it) }
            .firstOrNull { it.isNotBlank() && it != "null" }
            .orEmpty()
        val uid = listOf("user_id", "userId", "uid")
            .map { inner.optString(it) }
            .firstOrNull { it.isNotBlank() && it != "null" }
            .orEmpty()
        return name to uid
    }
}
