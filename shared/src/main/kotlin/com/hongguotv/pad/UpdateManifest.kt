package com.hongguotv.pad

import org.json.JSONObject

/** 清单里选中的下载文件（通用包或某个 ABI 包）。 */
data class UpdateSelection(
    val abi: String?,
    val path: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

/**
 * 更新清单的统一解析：Android 的 abis 节点、PC 的 path/url 都走这里。
 * 只做“选哪个文件、大小、校验值”，下载/安装仍归各平台。
 */
object UpdateManifest {
    fun select(
        root: JSONObject,
        base: String,
        supportedAbis: List<String>,
        preferUrl: Boolean = true,
    ): UpdateSelection? {
        val abis = root.optJSONObject("abis")
        val abi = supportedAbis.firstOrNull { abis?.has(it) == true }
        val node = abi?.let { abis?.optJSONObject(it) }
        val path = node?.optString("path")?.takeIf { it.isNotEmpty() }
            ?: root.optString("path")
        val full = if (node != null) {
            if (path.isNotEmpty()) "$base/$path" else ""
        } else {
            (if (preferUrl) root.optString("url").takeIf { it.startsWith("http") } else null)
                ?: if (path.isNotEmpty()) "$base/$path" else ""
        }
        if (full.isEmpty()) return null
        return UpdateSelection(
            abi = abi,
            path = path,
            url = full,
            size = node?.optLong("size", 0L)?.takeIf { it > 0L } ?: root.optLong("size", 0L),
            sha256 = node?.optString("sha256")?.takeIf { it.isNotEmpty() } ?: root.optString("sha256"),
        )
    }
}
