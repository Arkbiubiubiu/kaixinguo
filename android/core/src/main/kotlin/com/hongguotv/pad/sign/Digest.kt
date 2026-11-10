package com.hongguotv.pad.sign

import java.security.MessageDigest

/**
 * 摘要：MD5（JDK 自带）与 SM3（自己实现）。
 *
 * 为什么 SM3 自己写：JDK 21 不自带 SM3，而签名里 SM3 是核心（medusa 的
 * key 派生、f13 的输入摘要都用它）。实现按 GB/T 32905-2016 标准写，
 * 用标准测试向量锁定。
 */
object Digest {

    fun md5Raw(value: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(value)

    fun md5HexUpper(value: ByteArray): String = bytesToHexUpper(md5Raw(value))

    // ---------------- SM3 ----------------

    private val IV = intArrayOf(
        0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt(),
    )

    fun sm3(value: ByteArray): ByteArray {
        // 填充：0x80 + 若干 0 + 64 位大端比特长度
        val bitLen = value.size.toLong() * 8
        val padLen = run {
            val rem = (value.size + 1) % 64
            if (rem <= 56) 56 - rem else 120 - rem
        }
        val data = ByteArray(value.size + 1 + padLen + 8)
        value.copyInto(data, 0)
        data[value.size] = 0x80.toByte()
        for (i in 0 until 8) {
            data[data.size - 1 - i] = ((bitLen ushr (8 * i)) and 0xFF).toByte()
        }

        val v = IV.copyOf()
        val w = IntArray(68)
        val w1 = IntArray(64)
        var offset = 0
        while (offset < data.size) {
            for (i in 0 until 16) w[i] = be32ToInt(data, offset + i * 4)
            for (j in 16 until 68) {
                val x = w[j - 16] xor w[j - 9] xor rol32(w[j - 3], 15)
                w[j] = p1(x) xor rol32(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) w1[j] = w[j] xor w[j + 4]

            var a = v[0]; var b = v[1]; var c = v[2]; var d = v[3]
            var e = v[4]; var f = v[5]; var g = v[6]; var h = v[7]

            for (j in 0 until 64) {
                val t = if (j < 16) 0x79cc4519 else 0x7a879d8a
                val ss1 = rol32(rol32(a, 12) + e + rol32(t, j and 31), 7)
                val ss2 = ss1 xor rol32(a, 12)
                val ff = if (j < 16) a xor b xor c else (a and b) or (a and c) or (b and c)
                val gg = if (j < 16) e xor f xor g else (e and f) or (e.inv() and g)
                val tt1 = ff + d + ss2 + w1[j]
                val tt2 = gg + h + ss1 + w[j]
                d = c
                c = rol32(b, 9)
                b = a
                a = tt1
                h = g
                g = rol32(f, 19)
                f = e
                e = p0(tt2)
            }

            v[0] = v[0] xor a; v[1] = v[1] xor b
            v[2] = v[2] xor c; v[3] = v[3] xor d
            v[4] = v[4] xor e; v[5] = v[5] xor f
            v[6] = v[6] xor g; v[7] = v[7] xor h
            offset += 64
        }

        val out = ByteArray(32)
        for (i in 0 until 8) be32(v[i]).copyInto(out, i * 4)
        return out
    }

    private fun p0(x: Int): Int = x xor rol32(x, 9) xor rol32(x, 17)

    private fun p1(x: Int): Int = x xor rol32(x, 15) xor rol32(x, 23)
}
