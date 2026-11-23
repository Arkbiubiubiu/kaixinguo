package com.hongguotv.pad

import org.json.JSONObject

/**
 * 统一的"取昵称"口径（PC / Android 共用，避免一端取 user_name、一端取 nickname）。
 *
 * 规则：
 *  - 优先看 `data.user_info`（登录/账号响应的昵称常嵌在这里），没有再退回 data 本身；
 *  - 候选字段按优先级：nickname → nick_name → name → user_name → username → screen_name；
 *  - 取第一个非空且不是字符串 "null" 的；都没有返回 ""（显示层再 ifBlank { "用户" } 兜底）。
 */
fun pickNickname(data: JSONObject): String {
    val inner = data.optJSONObject("user_info") ?: data
    val candidates = listOf("nickname", "nick_name", "name", "user_name", "username", "screen_name")
    for (key in candidates) {
        val v = inner.optString(key, "")
        if (v.isNotBlank() && v != "null") return v
    }
    return ""
}
