package com.hongguotv.pad

/**
 * 登录域边界：passport form 请求只从这里走。
 * 具体短信/MFA 流程仍是平台 UI，但协议底层不再直接依赖 Api。
 */
object PassportClient {
    fun post(path: String, form: String, cookie: String, query: org.json.JSONArray): Api.RawResp? =
        Api.passportPost(path, form, cookie, query)
}
