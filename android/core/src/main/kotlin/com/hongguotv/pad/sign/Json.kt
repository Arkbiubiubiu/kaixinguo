package com.hongguotv.pad.sign

/**
 * 极简 JSON 解析（只服务于本地签名服务：读设备档案、读 /sign 请求体、写返回）。
 *
 * 不引第三方依赖：这个服务要打成单 jar 丢到服务器上跑，越少依赖越省事。
 * 支持对象 / 数组 / 字符串（含转义）/ 数字 / true / false / null，够用即可。
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.value()
        p.skipSpace()
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Map<String, Any?> =
        (parse(text) as? Map<String, Any?>) ?: emptyMap()

    private class Parser(private val s: String) {
        private var i = 0

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipSpace()
            if (i >= s.length) error("JSON 提前结束")
            return when (s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> number()
            }
        }

        private fun expect(word: String) {
            require(s.startsWith(word, i)) { "JSON 期望 $word" }
            i += word.length
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            require(s[i] == '{') { "JSON 期望 {" }
            i++
            skipSpace()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipSpace()
                val key = str()
                skipSpace()
                require(s[i] == ':') { "JSON 期望 :" }
                i++
                out[key] = value()
                skipSpace()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> error("JSON 对象分隔符异常")
                }
            }
        }

        private fun array(): List<Any?> {
            val out = ArrayList<Any?>()
            require(s[i] == '[') { "JSON 期望 [" }
            i++
            skipSpace()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                skipSpace()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> error("JSON 数组分隔符异常")
                }
            }
        }

        private fun str(): String {
            require(s[i] == '"') { "JSON 期望字符串" }
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> error("未知转义 $e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }

    // ---------------- 输出 ----------------

    fun quote(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    fun write(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> quote(k.toString()) + ":" + write(v) }
        is List<*> -> value.joinToString(",", "[", "]") { write(it) }
        else -> quote(value.toString())
    }
}
