package com.hongguotv.pad.sign

/** 字节工具（小工具集中放一处，避免每个算法文件各写一遍）。 */

fun le32(value: Int): ByteArray =
    byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

fun be32(value: Int): ByteArray =
    byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

fun be32ToInt(data: ByteArray, at: Int): Int =
    ((data[at].toInt() and 0xFF) shl 24) or
        ((data[at + 1].toInt() and 0xFF) shl 16) or
        ((data[at + 2].toInt() and 0xFF) shl 8) or
        (data[at + 3].toInt() and 0xFF)

fun le32ToInt(data: ByteArray, at: Int): Int =
    (data[at].toInt() and 0xFF) or
        ((data[at + 1].toInt() and 0xFF) shl 8) or
        ((data[at + 2].toInt() and 0xFF) shl 16) or
        ((data[at + 3].toInt() and 0xFF) shl 24)

fun be64ToLong(data: ByteArray, at: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (data[at + i].toLong() and 0xFF)
    return v
}

fun le64ToLong(data: ByteArray, at: Int): Long {
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or (data[at + i].toLong() and 0xFF)
    return v
}

fun longToLe64(value: Long): ByteArray {
    val out = ByteArray(8)
    for (i in 0 until 8) out[i] = ((value ushr (8 * i)) and 0xFF).toByte()
    return out
}

/** 逐字节异或，长度取较短者。 */
fun bxor(a: ByteArray, b: ByteArray): ByteArray {
    val n = minOf(a.size, b.size)
    val out = ByteArray(n)
    for (i in 0 until n) out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
    return out
}

/** 逐字节异或，长度取 a，b 循环使用。 */
fun xorBytes(a: ByteArray, b: ByteArray): ByteArray {
    require(b.isNotEmpty()) { "异或密钥不能为空" }
    val out = ByteArray(a.size)
    for (i in a.indices) out[i] = (a[i].toInt() xor b[i % b.size].toInt()).toByte()
    return out
}

fun hexToBytes(text: String): ByteArray {
    val clean = text.replace(" ", "")
    require(clean.length % 2 == 0) { "十六进制长度异常" }
    return ByteArray(clean.length / 2) {
        clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}

fun bytesToHex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }

fun bytesToHexUpper(data: ByteArray): String = data.joinToString("") { "%02X".format(it) }

fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
