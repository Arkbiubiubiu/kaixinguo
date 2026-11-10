package com.hongguotv.pad.sign

/**
 * Medusa 用的 AES 变体。
 *
 * 与标准 AES 的三处差异（任何一处按标准写都会失配）：
 * 1. S-Box 按 khronos 低 2 位从 4 张表里选一张；
 * 2. 密钥扩展第 4/8/12 轮用改写的字节选择；
 * 3. 加密前后各有一层手写位打包/位解包，不是标准 ShiftRows。
 */
class AesV3(key: ByteArray, khronos: Int) {

    private val box: ByteArray
    private val con2: IntArray
    private val order: IntArray
    private val keys: Array<IntArray>

    init {
        val wordSize = khronos and 3
        val all = java.util.Base64.getDecoder().decode(Consts.SBOX_B64)
        require(all.size == 1024) { "S-Box 必须是 1024 字节" }
        box = all.copyOfRange(wordSize * 256, wordSize * 256 + 256)
        con2 = CON2[wordSize]
        order = ORDER[wordSize]
        keys = expand(key, wordSize)
    }

    private fun expand(key: ByteArray, wordSize: Int): Array<IntArray> {
        val init = INIT_WORD[wordSize]
        val sbox = java.util.Base64.getDecoder().decode(Consts.SBOX_B64)
            .copyOfRange(wordSize * 256, wordSize * 256 + 256)

        // 4 个槽位写的都是同一个 init
        val initial = ByteArray(16)
        for (k in 0 until 4) le32(init).copyInto(initial, k * 4)

        val expanded = ByteArray(48)
        xorBytes(initial, key).copyInto(expanded, 0)
        // 后 32 字节保持 0

        var rounds = 8
        for (i in 4 until 12) {
            val at = 4 * (i - 1)
            var a = expanded[at].toInt() and 0xFF
            var b = expanded[at + 1].toInt() and 0xFF
            var c = expanded[at + 2].toInt() and 0xFF
            var d = expanded[at + 3].toInt() and 0xFF

            if (i and 3 == 0) {
                val t = ((init ushr (rounds and 24)) xor (sbox[b].toInt() and 0xFF)) and 255
                b = sbox[c].toInt() and 0xFF
                c = sbox[d].toInt() and 0xFF
                d = sbox[a].toInt() and 0xFF
                a = t
            }
            rounds += 2

            expanded[at + 4] = (a xor (expanded[at - 12].toInt() and 0xFF)).toByte()
            expanded[at + 5] = (b xor (expanded[at - 11].toInt() and 0xFF)).toByte()
            expanded[at + 6] = (c xor (expanded[at - 10].toInt() and 0xFF)).toByte()
            expanded[at + 7] = (d xor (expanded[at - 9].toInt() and 0xFF)).toByte()
        }

        return Array(12) { i ->
            IntArray(4) { j -> expanded[i * 4 + j].toInt() and 0xFF }
        }
    }

    private fun addKey(state: Array<IntArray>, groupStart: Int) {
        for (i in 0 until 4) for (j in 0 until 4) {
            state[i][j] = state[i][j] xor keys[groupStart + i][j]
        }
    }

    private fun addCon(state: Array<IntArray>, groupStart: Int) {
        for (i in 0 until 4) for (j in 0 until 4) {
            state[i][j] = state[i][j] xor keys[groupStart + i][con2[j]]
        }
    }

    private fun sub(state: Array<IntArray>) {
        for (i in 0 until 4) for (j in 0 until 4) {
            state[i][j] = box[state[i][j]].toInt() and 0xFF
        }
        val old = Array(4) { state[it].copyOf() }
        for (i in 0 until 4) state[i] = old[con2[i]].copyOf()
    }

    private fun shift(state: Array<IntArray>) {
        val flat = IntArray(16)
        for (i in 0 until 4) for (j in 0 until 4) flat[i * 4 + j] = state[i][j]
        for (i in 0 until 16) state[i / 4][i % 4] = flat[order[i]]
    }

    private fun shiftCon(state: Array<IntArray>) {
        val old = Array(4) { state[it].copyOf() }
        for (i in 0 until 4) for (j in 0 until 4) state[i][j] = old[i][con2[j]]
    }

    private fun block(value: ByteArray): ByteArray {
        val state = Array(4) { i -> IntArray(4) { j -> value[i * 4 + j].toInt() and 0xFF } }

        addCon(state, 0)
        for (i in 1 until 3) {
            sub(state)
            shift(state)
            if (i == 1) {
                shiftCon(state)
                mixColumns(state)
            }
            addCon(state, i * 4)
        }
        addKey(state, 4)

        val out = ByteArray(16)
        for (i in 0 until 4) for (j in 0 until 4) out[i * 4 + j] = state[i][j].toByte()
        return out
    }

    /**
     * 加密任意长度输入。
     *
     * 源数据按 8 字节一组做位重排压成 32 字节 → 按 16 字节分块 CBC（IV 来自调用方）
     * → 最后把派生出的 keystream 反向掩码回源数据的保留位。
     * 要求输入至少 248 字节（位打包固定读 31×8）。
     */
    fun encrypt(data: ByteArray, iv: ByteArray): ByteArray {
        require(data.size >= 248) { "AES 变体要求源数据至少 248 字节" }
        val source = data.copyOf()

        val plaintext = ByteArray(32)
        for (i in 0 until 31) {
            val at = i * 8
            var v = ((source[at].toInt() ushr 4) and 2)
            v = v or (source[at + 1].toInt() and 64)
            v = v or ((source[at + 2].toInt() ushr 2) and 1)
            v = v or ((source[at + 3].toInt() shl 3) and 128)
            v = v or ((source[at + 4].toInt() ushr 1) and 4)
            v = v or ((source[at + 5].toInt() shl 3) and 16)
            v = v or ((source[at + 6].toInt() shl 5) and 32)
            v = v or ((source[at + 7].toInt() ushr 4) and 8)
            plaintext[i] = v.toByte()
        }
        plaintext[31] = 1

        val blocks = ByteArray(32)
        var previous = iv.copyOf()
        for (chunk in 0 until 2) {
            val input = plaintext.copyOfRange(chunk * 16, chunk * 16 + 16)
            val value = block(xorBytes(input, previous))
            value.copyInto(blocks, chunk * 16)
            previous = value
        }

        val out = source
        for (i in 0 until 31) {
            val at = i * 8
            val k = blocks[i].toInt() and 0xFF
            out[at] = (out[at].toInt() and 0xDF or ((k shl 4) and 32)).toByte()
            out[at + 1] = (out[at + 1].toInt() and 0xBF or (k and 64)).toByte()
            out[at + 2] = (out[at + 2].toInt() and 0xFB or ((k shl 2) and 4)).toByte()
            out[at + 3] = (out[at + 3].toInt() and 0xEF or ((k ushr 3) and 16)).toByte()
            out[at + 4] = (out[at + 4].toInt() and 0xF7 or ((k + k) and 8)).toByte()
            out[at + 5] = (out[at + 5].toInt() and 0xFD or ((k ushr 3) and 2)).toByte()
            out[at + 6] = (out[at + 6].toInt() and 0xFE or ((k ushr 5) and 1)).toByte()
            out[at + 7] = (out[at + 7].toInt() and 0x7F or ((k shl 4) and 128)).toByte()
        }

        val result = ByteArray(1 + out.size)
        result[0] = blocks[31]
        out.copyInto(result, 1)
        return result
    }

    private fun mixColumns(state: Array<IntArray>) {
        for (i in 0 until 4) {
            val t = state[0][i] xor state[1][i] xor state[2][i] xor state[3][i]
            val u = state[0][i]
            fun xt(x: Int): Int = ((x shl 1) xor if (x and 128 != 0) 0x1b else 0) and 255

            state[0][i] = state[0][i] xor (t xor xt(state[0][i] xor state[1][i]))
            state[1][i] = state[1][i] xor (t xor xt(state[1][i] xor state[2][i]))
            state[2][i] = state[2][i] xor (t xor xt(state[2][i] xor state[3][i]))
            state[3][i] = state[3][i] xor (t xor xt(state[3][i] xor u))
        }
    }

    companion object {
        private val CON2 = arrayOf(
            intArrayOf(1, 0, 2, 3),
            intArrayOf(2, 0, 3, 1),
            intArrayOf(0, 1, 3, 2),
            intArrayOf(1, 0, 2, 3),
        )

        private val ORDER = arrayOf(
            intArrayOf(0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3),
            intArrayOf(0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11),
            intArrayOf(0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15),
            intArrayOf(0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3),
        )

        private val INIT_WORD = intArrayOf(
            0xca025ddc.toInt(), 0x823dc546.toInt(), 0xc9420583.toInt(), 0xc298225f.toInt(),
        )
    }
}
