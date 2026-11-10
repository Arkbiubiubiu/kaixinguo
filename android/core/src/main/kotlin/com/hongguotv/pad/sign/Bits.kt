package com.hongguotv.pad.sign

/**
 * 字节系签名用到的位运算原语。
 *
 * 全部按 32 位无符号语义实现：Kotlin 的 Int 是有符号的，凡是参与位运算的地方
 * 都用 Int 承载位模式、用 toLong()/and 0xFFFFFFFFL 转无符号做算术比较。
 */

/** 归一为 32 位（丢弃高位）。 */
fun u32(v: Long): Int = (v and 0xFFFF_FFFFL).toInt()

/** 无符号右移（Kotlin 的 shr 是有符号的，必须用 ushr）。 */
fun ushr(x: Int, n: Int): Int = x ushr n

/** 32 位循环左移。 */
fun rol32(value: Int, count: Int): Int {
    val n = count and 31
    return if (n == 0) value else (value shl n) or (value ushr (32 - n))
}

/** 32 位循环右移。 */
fun ror32(value: Int, count: Int): Int {
    val n = count and 31
    return if (n == 0) value else (value ushr n) or (value shl (32 - n))
}

/** 64 位循环右移。 */
fun ror64(value: Long, count: Int): Long {
    val n = count and 63
    return if (n == 0) value else (value ushr n) or (value shl (64 - n))
}

/**
 * ZigZag 编码（protobuf sint32/sint64）。
 *
 * JS 版用 BigInt 计算，这里对 Long 做同样的位模式运算：
 * 正数乘 2，负数取补后乘 2 再减 1。Long.MIN_VALUE 用溢出回绕保持一致。
 */
fun zigzag(value: Long): Long =
    if (value < 0) (-value * 2L - 1L) else (value * 2L)

/** 8 位比特序反转。 */
fun reverseBits(value: Int): Int {
    var x = value and 0xFF
    var result = 0
    for (i in 0 until 8) {
        result = (result shl 1) or ((x ushr i) and 1)
    }
    return result and 0xFF
}
