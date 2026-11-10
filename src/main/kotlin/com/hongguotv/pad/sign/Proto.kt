package com.hongguotv.pad.sign

/**
 * 极简 protobuf 编码（只做 wire format 的编码侧）。
 *
 * medusa 把设备指纹包成 protobuf 再加密，字段顺序与"空值跳过"规则都参与签名，
 * 所以这里不做任何排序或去重。
 */
object Proto {

    fun varint(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>(10)
        while (v > 127) {
            out.add((((v and 127) or 128).toInt()).toByte())
            v = v ushr 7
        }
        out.add(v.toByte())
        return out.toByteArray()
    }

    /** 字段值：字符串 / 字节 / 嵌套 message（已编码）/ 整数（zigzag）/ 浮点。 */
    sealed class Value {
        data class Str(val text: String) : Value()
        data class Bytes(val data: ByteArray) : Value()
        data class SInt(val number: Long) : Value()
        data class Float32(val number: Float) : Value()

        /** 空字符串 / 空字节按参考实现直接跳过整条字段。 */
        val skipped: Boolean
            get() = when (this) {
                is Str -> text.isEmpty()
                is Bytes -> data.isEmpty()
                else -> false
            }
    }

    private fun wireTypeOf(value: Value): Int = when (value) {
        is Value.Str, is Value.Bytes -> 2
        is Value.SInt -> 0
        is Value.Float32 -> 5
    }

    fun field(tag: Int, value: Value): ByteArray? {
        if (value.skipped) return null
        val key = varint((tag.toLong() shl 3) or wireTypeOf(value).toLong())
        return when (val v = value) {
            is Value.Str -> {
                val body = v.text.toByteArray(Charsets.UTF_8)
                key + varint(body.size.toLong()) + body
            }
            is Value.Bytes -> key + varint(v.data.size.toLong()) + v.data
            is Value.SInt -> key + varint(zigzag(v.number))
            is Value.Float32 -> {
                val bits = java.lang.Float.floatToIntBits(v.number)
                key + le32(bits)
            }
        }
    }

    fun encode(fields: List<Pair<Int, Value>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((tag, value) in fields) {
            field(tag, value)?.let { out.write(it) }
        }
        return out.toByteArray()
    }
}
