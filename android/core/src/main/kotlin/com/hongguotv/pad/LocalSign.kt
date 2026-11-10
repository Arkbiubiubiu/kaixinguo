package com.hongguotv.pad

import com.hongguotv.pad.sign.Device
import com.hongguotv.pad.sign.Profile
import com.hongguotv.pad.sign.Signer
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地签名：用内置的公用设备档案直接给请求签名，不依赖任何外部签名服务。
 *
 * 分发版（「下载即用」）走这条路；作者自己的机器在「设置 → 签名服务」里填了
 * 自建服务地址，仍然走远程代签，两条路互不影响。
 *
 * 设备档案是公开分发用的那一套（与私用身份隔离），签出来的 query 顺序由
 * [Profile] / [Signer] 保证，与参考实现一致；签完不能再改 url 或 body。
 */
internal object LocalSign {

    /** 公用档案的 UA（版本号与档案里的 version_code 一致）。 */
    private const val UA_PUBLIC =
        "com.phoenix.read/74232 (Linux; U; Android 14; zh_CN; Xiaomi 14; Build/UKQ1.230804.001;tt-ok/3.12.13.20)"

    private val DEVICE by lazy { Profile.fromJson(PROFILE_JSON, UA_PUBLIC) }

    /** 取流用的 UA（多码率接口按这个身份放行）。 */
    private const val UA_PLAY = "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C)"

    /**
     * 取流档案：多码率接口只认 aid=1967 / 7.1.3.32 / Redmi 这一套参数，
     * 与阅读接口的 aid=8662 不同，所以单独一份（字段顺序按参考实现）。
     */
    private val PLAY_DEVICE by lazy {
        Device(
            listOf(
                "iid" to "23562609507120",
                "device_id" to "23562609503024",
                "aid" to "1967",
                "version_code" to "71332",
                "version_name" to "7.1.3.32",
                "update_version_code" to "71332",
                "device_brand" to "Redmi",
                "os_version" to "16",
                "device_platform" to "android",
                "cdid" to "a694f0f8-266a-421a-bef3-ee760b049478",
                "device_level" to "1",
            ),
            UA_PLAY,
        )
    }

    /**
     * @return (签名后的完整 url, 必须带上的请求头)；签名失败返回 null。
     */
    fun sign(
        origin: String,
        path: String,
        query: JSONArray,
        body: String?,
    ): Pair<String, JSONObject>? = runCatching {
        val pairs = ArrayList<Pair<String, String>>(query.length())
        for (i in 0 until query.length()) {
            val item = query.optJSONArray(i) ?: continue
            pairs.add(item.optString(0) to item.optString(1))
        }
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val signed = Signer.sign(origin, path, bytes, DEVICE, pairs)
        val headers = JSONObject()
        signed.headers.forEach { (k, v) -> headers.put(k, v) }
        signed.url to headers
    }.onFailure {
        Log.d("PadSign", "本地签名失败: ${it.message}")
    }.getOrNull()

    /** 取流接口的本地签名（query 由 [PLAY_DEVICE] 决定，body 原样参与签名）。 */
    fun signPlay(
        origin: String,
        path: String,
        body: String,
    ): Pair<String, JSONObject>? = runCatching {
        val signed = Signer.sign(origin, path, body.toByteArray(Charsets.UTF_8), PLAY_DEVICE)
        val headers = JSONObject()
        signed.headers.forEach { (k, v) -> headers.put(k, v) }
        signed.url to headers
    }.onFailure {
        Log.d("PadSign", "取流签名失败: ${it.message}")
    }.getOrNull()

    /** 公用设备档案（可公开分发；与作者私用身份无关）。 */
    private val PROFILE_JSON = """
        {
          "device_id": "23562609503024",
          "install_id": "23562609507120",
          "iid": "23562609507120",
          "cdid": "a694f0f8-266a-421a-bef3-ee760b049478",
          "openudid": "714ac36b1c7d652c",
          "device_token": "AAA7V7FDU3HODKYVK5CA7U4G3S67HTYO4NZYL53MCRSILSP7UAHHNOGJNZBYLT4EGXZ664UNCVTAYYZNSESAB3WYPB6POQCGACNA",
          "ttreq": "1${'$'}5a3046ef69330eb4dbfd1bbcdc7e5ce618f711d7",
          "store_region": "cn-sh",
          "device_brand": "xiaomi",
          "device_type": "23127PN0CC",
          "os_version": "14",
          "os_api": "34",
          "resolution": "1200*2670",
          "dpi": "460",
          "channel": "xiaomi_8662_64",
          "created_at": "2026-10-28 14:00:45"
        }
    """.trimIndent()
}
