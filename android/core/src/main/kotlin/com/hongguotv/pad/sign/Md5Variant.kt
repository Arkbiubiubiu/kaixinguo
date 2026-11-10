package com.hongguotv.pad.sign

/**
 * 变种 MD5 累加器：f13 的分支判定与校验和都依赖这两个**非标准**函数。
 * 不能用标准 MD5 替代。
 */
object Md5Variant {

    /**
     * 分支判定的初始向量递推（对应参考实现的 `getIv`）。
     *
     * 按字节下标奇偶走两条不同递推：偶数位是移位异或混合，奇数位取反。
     */
    fun getIv(iv: Int, data: ByteArray): Int {
        var value = iv
        for (i in data.indices) {
            val byte = data[i].toInt() and 0xFF
            value = if (i and 1 == 0) {
                (value ushr 4) xor value xor (value shl 6) xor byte
            } else {
                val inner = (value ushr 7) xor value xor (byte or (value shl 12))
                inner.inv()
            }
        }
        return value
    }

    /**
     * 自定义累加和（对应参考实现的 `sumMd5`）。
     *
     * 只前 12 字节参与，奇偶两路各自的移位与取反不同，最后强制置位 bit2。
     */
    fun sumMd5(data: ByteArray): Int {
        require(data.size >= 12) { "sumMd5 需要至少 12 字节" }
        var check = 0x20220420
        for (i in 0 until 12) {
            val temp = if (i and 1 == 0) (check ushr 3) xor check else (check ushr 5) xor check
            check = if (i and 1 == 0) {
                (data[i].toInt() and 0xFF) xor (check shl 7)
            } else {
                (data[i].toInt() and 0xFF) or (check shl 11)
            }
            if (i and 1 != 0) check = check.inv()
            check = check xor temp
        }
        return (check or 4) xor 0x01000000
    }
}
