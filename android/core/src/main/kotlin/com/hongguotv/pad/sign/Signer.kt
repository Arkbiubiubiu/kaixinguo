package com.hongguotv.pad.sign

/**
 * 签名编排：拼 query、抖动 `_rticket` 选一个服务端受理的分支、产出整套请求头。
 *
 * 签完之后**不能再改 url 或 body**：签名是「query + body + 时间戳」的联合函数，
 * 改一个字节（包括参数顺序、URL 编码方式）都会失配，服务端只会静默返回空响应。
 */
object Signer {

    /** 抖动上限（与官方客户端一致）。 */
    private const val MAX_TICKET_OFFSET = 32

    data class Signed(val url: String, val headers: List<Pair<String, String>>)

    /**
     * 与 `encodeURIComponent` 一致的转义：unreserved 集合原样保留，其余按大写十六进制转义。
     */
    fun encodeComponent(text: String): String {
        val unreserved = "-_.!~*'()"
        val sb = StringBuilder(text.length)
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val ch = (byte.toInt() and 0xFF).toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || unreserved.contains(ch)) {
                sb.append(ch)
            } else {
                sb.append('%').append(String.format("%02X", byte.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    fun encodeQuery(values: List<Pair<String, String>>): String =
        values.joinToString("&") { (k, v) -> "${encodeComponent(k)}=${encodeComponent(v)}" }

    /**
     * 拼 query 并挑一个 branch != 1 的 ticket。
     *
     * @return (url, query, ticket, khronos)
     */
    fun resolveTicket(
        origin: String,
        pathname: String,
        body: ByteArray?,
        device: Device,
        bizQuery: List<Pair<String, String>>,
        baseTicket: Long,
    ): TicketResult {
        val bodyMd5 = if (body != null) Digest.md5Raw(body) else ByteArray(16)

        for (offset in 0 until MAX_TICKET_OFFSET) {
            val ticket = baseTicket + offset
            val khronos = (ticket / 1000).toInt()
            val params = ArrayList<Pair<String, String>>(device.fields.size + bizQuery.size + 2)
            params.addAll(device.fields)
            params.addAll(bizQuery)
            params.add("ts" to khronos.toString())
            params.add("_rticket" to ticket.toString())

            val query = encodeQuery(params)
            // 判分支要用 query 的 SM3 摘要，不是原文
            val querySm3 = Digest.sm3(query.toByteArray(Charsets.UTF_8))
            if (Argus.branchOf(querySm3, bodyMd5, le32(khronos)) != 1) {
                return TicketResult("$origin$pathname?$query", query, ticket, khronos)
            }
        }

        // 32 次都落在 branch 1：沿用最后一次（与官方实现一致）
        val ticket = baseTicket + MAX_TICKET_OFFSET - 1
        val khronos = (ticket / 1000).toInt()
        val params = ArrayList<Pair<String, String>>()
        params.addAll(device.fields)
        params.addAll(bizQuery)
        params.add("ts" to khronos.toString())
        params.add("_rticket" to ticket.toString())
        val query = encodeQuery(params)
        return TicketResult("$origin$pathname?$query", query, ticket, khronos)
    }

    data class TicketResult(val url: String, val query: String, val ticket: Long, val khronos: Int)

    fun sign(
        origin: String,
        pathname: String,
        body: ByteArray?,
        device: Device,
        bizQuery: List<Pair<String, String>> = emptyList(),
        extraHeaders: List<Pair<String, String>> = emptyList(),
        baseTicket: Long = System.currentTimeMillis(),
        entropy: Medusa.Entropy = Medusa.randomEntropy(),
    ): Signed {
        val resolved = resolveTicket(origin, pathname, body, device, bizQuery, baseTicket)
        val random = java.util.Random().nextInt() and 0xFFFF

        val headers = ArrayList<Pair<String, String>>(16)
        headers.add("User-Agent" to device.userAgent)
        headers.add("Accept" to "application/json; charset=utf-8,application/x-protobuf")
        headers.add("x-xs-from-web" to "0")
        headers.add("x-ss-req-ticket" to resolved.ticket.toString())
        headers.add("sdk-version" to "2")
        headers.add("passport-sdk-version" to "5051452")
        headers.add("x-vc-bdturing-sdk-version" to "4.0.3.cn")
        headers.add("x-khronos" to resolved.khronos.toString())
        headers.add("x-ladon" to base64(be32(resolved.khronos)))
        headers.add("x-argus" to base64(le32(resolved.khronos)))
        headers.add("x-gorgon" to Gorgon.sign(resolved.query, body, resolved.khronos, random))
        headers.add("x-helios" to Helios.assemble(resolved.khronos, entropy.random))
        headers.add(
            "x-medusa" to Medusa.assemble(
                resolved.url,
                body,
                resolved.khronos,
                device.get("device_id"),
                device.get("version_name"),
                entropy,
            ),
        )

        if (body != null) {
            val hasContentType = extraHeaders.any { it.first.equals("content-type", ignoreCase = true) }
            if (!hasContentType) {
                headers.add("Content-Type" to "application/json; charset=UTF-8")
            }
            headers.add("x-ss-stub" to bytesToHexUpper(Digest.md5Raw(body)))
        }

        headers.addAll(extraHeaders)
        return Signed(resolved.url, headers)
    }
}
