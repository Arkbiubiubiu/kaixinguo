package com.hongguotv.pad.sign

/**
 * X-Gorgon：对 query + body 做摘要，再用 RC4 变种混淆。
 *
 * 服务端现在对它的校验强度不高（实测去掉也能拿到数据），但保留它是为了
 * 与官方客户端形态一致 —— 头部形态突变更容易被风控盯上。
 */
object Gorgon {

    /**
     * RC4 变种。
     *
     * 与标准 RC4 的两处差异（改回标准就整体失配）：
     * 1. KSA 是 `s[i] = s[j]` 赋值，不是交换；
     * 2. PRGA 取 `s[(y + y) & 255]`，不是 `s[(s[i] + s[j]) & 255]`。
     */
    fun rc4(data: ByteArray, key: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 255
            s[i] = s[j]
        }
        val out = ByteArray(data.size)
        var i = 0
        j = 0
        for (idx in data.indices) {
            i += 1
            val x = s[i and 255]
            j += x
            val y = s[j and 255]
            s[i and 255] = y
            out[idx] = (data[idx].toInt() xor s[(y + y) and 255]).toByte()
        }
        return out
    }

    /**
     * 计算 x-gorgon 头。
     *
     * 结构：`md5(query)[0:4] | md5(body)[0:4] | 4 个 0 | sdk 版本(小端) |
     * khronos(大端)` → RC4 → 逐字节 nibble 交换 + 邻位异或 + 反转比特 + 取反
     * → `8404` + 随机种子 + 标志位 + 密文，整体 hex。
     */
    fun sign(query: String, body: ByteArray?, khronos: Int, random: Int): String {
        val queryMd5 = Digest.md5Raw(query.toByteArray(Charsets.UTF_8))
        val bodyMd5 = if (body != null) Digest.md5Raw(body) else ByteArray(16)

        val input = ByteArray(20)
        queryMd5.copyInto(input, 0, 0, 4)
        bodyMd5.copyInto(input, 4, 0, 4)
        // 4 个 0 已经在 input[8..11]
        le32(Consts.GORGON_SDK_VERSION).copyInto(input, 12)
        be32(khronos).copyInto(input, 16)

        val key = byteArrayOf(
            0x4a,
            0x40,
            0x16,
            ((random ushr 8) and 255).toByte(),
            0x47,
            0x6c,
            0x01,
            (random and 255).toByte(),
        )

        val out = rc4(input, key)
        val n = out.size
        for (i in 0 until n) {
            val value = out[i].toInt() and 0xFF
            val swapped = ((value ushr 4) or (value shl 4)) and 0xFF
            val next = if (i + 1 < n) out[i + 1].toInt() and 0xFF else out[0].toInt() and 0xFF
            val mixed = reverseBits(next xor swapped)
            out[i] = ((mixed.inv() xor 20) and 0xFF).toByte()
        }

        val result = ByteArray(4 + n)
        result[0] = 0x84.toByte()
        result[1] = 0x04.toByte()
        result[2] = (random and 255).toByte()
        result[3] = ((random ushr 8) and 255).toByte()
        // 参考实现的输出是：前缀 2 字节 + 种子 2 字节 + 0x40 0x01 + 密文
        val full = ByteArray(4 + n + 2)
        full[0] = 0x84.toByte()
        full[1] = 0x04.toByte()
        full[2] = (random and 255).toByte()
        full[3] = ((random ushr 8) and 255).toByte()
        full[4] = 0x40
        full[5] = 0x01
        out.copyInto(full, 6)
        return bytesToHex(full)
    }
}
