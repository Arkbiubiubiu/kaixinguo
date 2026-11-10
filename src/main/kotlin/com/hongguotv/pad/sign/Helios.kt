package com.hongguotv.pad.sign

/**
 * X-Helios：时间戳派生的 34 轮扩散哈希。
 *
 * 先把 `le32(random) || APP_ID` 的 MD5 摘要（取十六进制文本的字节）拆成
 * 4 个 u64 种子扩散出 34 项表；再用 `"<khronos>-<CHANNEL_ID>-<APP_ID>"`
 * 逐块喂进去，每块跑 34 轮出 16 字节。
 */
object Helios {

    private const val ROUNDS = 34

    fun sign(khronos: Int, random: Int): String = assemble(khronos, random)

    fun assemble(khronos: Int, random: Int): String {
        val seedInput = le32(random) + Consts.APP_ID.toByteArray(Charsets.UTF_8)
        val digest = Digest.md5Raw(seedInput)
        // 取的是摘要「十六进制文本」的字节，不是摘要本身
        val ascii = bytesToHex(digest).toByteArray(Charsets.US_ASCII)

        val words = LongArray(4) { le64ToLong(ascii, it * 8) }
        val table = diffuse(words)

        val out = java.io.ByteArrayOutputStream()
        val padded = pad(khronos)
        var at = 0
        while (at < padded.size) {
            var a = le64ToLong(padded, at)
            var b = le64ToLong(padded, at + 8)
            for (t in 0 until ROUNDS) {
                b = table[t] xor (a + ror64(b, 8))
                a = b xor ror64(a, 61)
            }
            out.write(longToLe64(a))
            out.write(longToLe64(b))
            at += 16
        }

        return base64(le32(random) + out.toByteArray())
    }

    /**
     * 34 轮扩散。
     *
     * 队列装的是**去掉前两个之后**的 w2/w3 —— 写成"取被摘掉的前两个"会整体错位一轮。
     */
    private fun diffuse(words: LongArray): LongArray {
        val table = LongArray(ROUNDS + 1)
        var b0 = words[0]
        var b8 = words[1]
        table[0] = b0
        val queue = ArrayDeque<Long>()
        queue.addLast(words[2])
        queue.addLast(words[3])

        for (i in 0 until ROUNDS) {
            var x8 = ror64(b8, 8) + b0
            x8 = x8 xor i.toLong()
            queue.addLast(x8)
            x8 = x8 xor ror64(b0, 61)
            table[i + 1] = x8
            b0 = x8
            b8 = queue.removeFirst()
        }
        return table
    }

    /** 补齐到 16 的倍数：填充字节值 = 16 - (长度 % 16)，恰好整除时填 0x10。 */
    private fun pad(khronos: Int): ByteArray {
        val text = "$khronos-${Consts.CHANNEL_ID}-${Consts.APP_ID}".toByteArray(Charsets.US_ASCII)
        val fill = 16 - (text.size % 16)
        val total = ((text.size + 1 + 15) / 16) * 16
        val out = ByteArray(total) { fill.toByte() }
        text.copyInto(out, 0)
        return out
    }
}
