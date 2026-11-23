package com.hongguotv.pad

import org.json.JSONObject

/**
 * 数据层需要用到的本机状态：登录态、设置、本地历史。
 *
 * 安卓用 SharedPreferences 实现，PC 用文件实现 —— Api 只认这个接口，
 * 这样同一份数据层代码三端都能编译。
 */
interface AccountState {
    val loggedIn: Boolean

    var accountCookies: String
    var accountName: String
    var accountUid: String
    var accountSyncedAt: Long
    var accountSource: String

    /** 签名服务地址（空 = 未配置）。 */
    var signUrl: String

    /** 清晰度上限（540 / 720 / 1080）。 */
    var maxHeight: Int

    fun contains(key: String, seriesId: String): Boolean
    fun history(): List<JSONObject>
    fun mergeHistory(remote: List<JSONObject>): List<JSONObject>
    fun updateHistoryCover(seriesId: String, cover: String)
}

/** 全局装配点：启动时把各端实现挂上来。 */
object App {
    lateinit var state: AccountState

    /** 服务器配置：各端启动时注入（安卓来自 BuildConfig，PC 来自配置文件）。 */
    var playToken: String = ""
    var playBases: List<String> = emptyList()
    var historyBase: String = ""

    /** 服务端功能开关（只影响新实现是否启用；默认全部 false = 旧路径）。 */
    var flags: Map<String, Boolean> = emptyMap()

    /** 当前运行平台标记（公告按平台过滤用）：pc / tv / pad。 */
    var platform: String = ""

    /** 配对服务网关地址（发版注入；空=回落 signUrl）。Android 由各自注入，缺省空。 */
    var pairBase: String = ""

    /**
     * 设备身份覆盖（空 = 用占位默认值）。
     *
     * 开源版把 iid / device_id / cdid 从源码里抽出来，避免公开分发时
     * 所有人共用同一设备指纹；在设置页「服务配置」里填自己的。
     */
    var deviceIid: String = ""
    var deviceId: String = ""
    var deviceCdid: String = ""

    const val KEY_RESERVE = "reserve"
}
