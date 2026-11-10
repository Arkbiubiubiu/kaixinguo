package com.hongguotv.pad.sign

/**
 * f13 哈希（x-argus 的负载，也是 medusa 里被服务端核验的关键字段）。
 *
 * 服务端用 query / body / 时间戳推导出三个分支之一，客户端必须算出同一个分支。
 * 三个分支实现差异极大：
 * - branch 0：112 轮消息扩展 + 变体轮函数（重量级）
 * - branch 1：服务端不受理（抖动 `_rticket` 就是为了避开它）
 * - branch 2：变种 MD5
 * - 其它：直通（sm3 前 16 + body 摘要 + 校验和）
 */
object Argus {

    private const val SEED_IV = 0x20230928

    /** 预判落在哪个分支（0 / 1 / 2）。 */
    fun branchOf(query: ByteArray, bodyMd5: ByteArray, tsBytes: ByteArray): Int {
        val iv = chainIv(query, bodyMd5, tsBytes)
        val low = iv and 15
        return low - ((low * 171) shr 9) * 3
    }

    fun hashF13(querySm3: ByteArray, bodyMd5: ByteArray, tsBytes: ByteArray, khronos: Int): ByteArray {
        val iv = chainIv(querySm3, bodyMd5, tsBytes)
        val low = iv and 15
        val ivV0 = (low * 171) shr 9
        val branch = low - ivV0 * 3

        if (branch == 2) return branch2(iv, querySm3, bodyMd5, tsBytes, khronos)
        if (branch != 0) {
            // 直通：sm3 前 16 + body 摘要 + 自定义校验和
            val combined = querySm3 + bodyMd5
            return querySm3.copyOfRange(0, 16) + bodyMd5 + le32(Md5Variant.sumMd5(combined))
        }
        return branch0(querySm3, bodyMd5, tsBytes, khronos, ivV0)
    }

    private fun chainIv(query: ByteArray, bodyMd5: ByteArray, tsBytes: ByteArray): Int =
        Md5Variant.getIv(Md5Variant.getIv(Md5Variant.getIv(SEED_IV, query), bodyMd5), tsBytes)

    // ---------------------------------------------------------------- 分支 2

    private fun branch2(
        iv: Int,
        querySm3: ByteArray,
        bodyMd5: ByteArray,
        tsBytes: ByteArray,
        khronos: Int,
    ): ByteArray {
        val v = (iv and 13) * 86
        val ivV0 = ((v ushr 15) and 255) + ((v ushr 8) and 255)
        val n1 = N1[minOf(ivV0, N1.size - 1)]

        val countV1 = (n1 + khronos + 1) and 255
        val countV2 = n1 + khronos
        val shift = (countV2 + 5) and 7

        val seed = byteArrayOf(0x84.toByte(), 0x96.toByte(), 0x77, 0x9d.toByte(), 0xd4.toByte(), 0x15, 0x0b, 0xf8.toByte())
        val pad = ByteArray(seed.size) { i ->
            val v8 = seed[i].toInt() and 0xFF
            val combined = v8 or (v8 shl 8)
            ((combined ushr shift) and 255).toByte()
        }

        val input = querySm3 + bodyMd5 + tsBytes + pad + hexToBytes("a0010000")
        val begin = ivV0 shl 6
        val orders = branch2Orders().copyOfRange(begin, begin + 64)
        return md5SumV3(input, countV2, orders, countV1)
    }

    private fun md5SumV3(message: ByteArray, countV2: Int, orders: ByteArray, countV1: Int): ByteArray {
        val sv = IntArray(Consts.BRANCH2_SV.size) { ror32(Consts.BRANCH2_SV[it], countV1) }
        val start = intArrayOf(
            ror32(0x79e0f2fb, countV2),
            ror32(0xc8b52570.toInt(), countV2),
            ror32(0xebc2f8cd.toInt(), countV2),
            ror32(0x7c104d93, countV2),
        )
        val endCount = (countV2 + 6) and 255
        val end = intArrayOf(
            ror32(0x19be4866, endCount),
            ror32(0xe85986b4.toInt(), endCount),
            ror32(0xe19b326e.toInt(), endCount),
            ror32(0x71d1d7d4, endCount),
        )

        val m = IntArray(16) { le32ToInt(message, it * 4) }
        var a = start[0]
        var b = start[1]
        var c = start[2]
        var d = start[3]

        fun run(kind: Int, shifts: IntArray, offset: Int) {
            for (i in 0 until 16) {
                val phase = i and 3
                val w = m[orders[offset + i].toInt() and 0xFF]
                val k = sv[offset + i]
                val next = when (phase) {
                    0 -> step(kind, a, b, c, d, w, shifts[i], k)
                    1 -> step(kind, d, a, b, c, w, shifts[i], k)
                    2 -> step(kind, c, d, a, b, w, shifts[i], k)
                    else -> step(kind, b, c, d, a, w, shifts[i], k)
                }
                when (phase) {
                    0 -> a = next
                    1 -> d = next
                    2 -> c = next
                    else -> b = next
                }
            }
        }

        run(0, intArrayOf(7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22), 0)
        run(1, intArrayOf(5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20), 16)
        run(2, intArrayOf(4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23), 32)
        run(3, intArrayOf(6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21), 48)

        val ret = le32((start[0] + a) xor end[0]) +
            le32((start[1] + b) xor end[1]) +
            le32((start[2] + c) xor end[2]) +
            le32((start[3] + d) xor end[3])
        return ret + le32(Md5Variant.sumMd5(ret))
    }

    private fun step(
        kind: Int,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        m: Int,
        shift: Int,
        constant: Int,
    ): Int {
        val f = when (kind) {
            0 -> (b and c) or (b.inv() and d)
            1 -> (b and d) or (c and d.inv())
            2 -> b xor c xor d
            else -> c xor (b or d.inv())
        }
        return b + rol32(a + f + m + constant, shift)
    }

    // ---------------------------------------------------------------- 分支 0

    private fun branch0(
        querySm3: ByteArray,
        bodyMd5: ByteArray,
        tsBytes: ByteArray,
        khronos: Int,
        ivV0: Int,
    ): ByteArray {
        val ivV1 = Consts.BRANCH0_IV[minOf(ivV0, Consts.BRANCH0_IV.size - 1)]
        val countV1 = (ivV1 + khronos + 1) and 255
        val countV2 = ivV1 + khronos

        val tt = IntArray(Consts.BRANCH0_TT.size) { ror32(Consts.BRANCH0_TT[it], countV1) }
        val n0 = (countV2 + 2) and 7
        val seed = byteArrayOf(0xfa.toByte(), 0x45, 0x61, 0xd7.toByte())
        val pad = ByteArray(seed.size) { i ->
            val v8 = seed[i].toInt() and 0xFF
            ((v8 or (v8 shl 8)) ushr n0 and 255).toByte()
        }

        val data = querySm3 + bodyMd5 + tsBytes + pad + hexToBytes("00000000000001a0")
        val di = expandWords(data)
        val init = IntArray(Consts.BRANCH0_INIT.size) { ror32(Consts.BRANCH0_INIT[it], countV2 and 31) }
        val d = mixRounds(init, Consts.BRANCH0_ROUNDS[ivV0], di, tt, ivV1)

        val ret = ByteArray(32)
        for (i in 0 until 8) be32(d[i] + init[i]).copyInto(ret, i * 4)

        // 高低 16 字节折叠，再附累加和
        val folded = bxor(ret.copyOfRange(0, 16), ret.copyOfRange(16, 32))
        folded.copyInto(ret, 0)
        val head = ret.copyOfRange(0, 16)
        return head + le32(Md5Variant.sumMd5(head))
    }

    private fun expandWords(data: ByteArray): IntArray {
        val di = IntArray(128)
        val chunks = data.size / 4
        for (i in 0 until chunks) di[i] = be32ToInt(data, i * 4)

        var di0 = di[0]
        for (i in 0 until 112) {
            val di1 = di[i + 1]
            val di14 = di[i + 14]
            val r1 = rol32(di1, 14) xor rol32(di1, 25) xor (di1 ushr 3)
            val r2 = rol32(di14, 13) xor rol32(di14, 15) xor (di14 ushr 10)
            di[i + 16] = di0 + di[i + 9] + r1 + r2
            di0 = di1
        }
        return di
    }

    private fun mixRounds(
        init: IntArray,
        rounds: IntArray,
        di: IntArray,
        tt: IntArray,
        ivV1: Int,
    ): IntArray {
        val state = init.copyOf()
        for (i in 0 until rounds[0]) {
            val base = ivV1 + i
            val w = di[base and 127]
            fun idx(k: Int): Int = rounds[k]

            val n1 = ((state[idx(3)] xor state[idx(4)]) and state[idx(1)]) xor state[idx(3)]
            val n2 = rol32(state[idx(1)], 26) xor rol32(state[idx(1)], 21) xor rol32(state[idx(1)], 7)
            val n4 = w + n1 + n2 + tt[base and 63] + state[idx(5)]

            val n5 = rol32(state[idx(2)], 30) xor rol32(state[idx(2)], 19) xor rol32(state[idx(2)], 10)
            val n6 = (state[idx(2)] and state[idx(6)]) or ((state[idx(2)] or state[idx(6)]) and state[idx(7)])
            val n7 = n5 + n6

            val old = state[idx(9)]
            // 末尾元素移到开头（参考实现的 unshift(pop())）
            val last = state[state.size - 1]
            state.copyInto(state, 1, 0, state.size - 1)
            state[0] = last

            state[idx(10)] = n7 + n4
            state[idx(8)] = old + n4
        }
        return state
    }

    private val N1 = intArrayOf(
        0x8980f29b.toInt(),
        0xeb549c7f.toInt(),
        0xb08726db.toInt(),
        0xd40cb5e6.toInt(),
        0xe8f559e4.toInt(),
    )

    private fun branch2Orders(): ByteArray = hexToBytes(Consts.BRANCH2_ORDERS_HEX)
}
