package com.hongguotv.pad.sign

/**
 * 设备档案：**保序**键值对 + UA。
 *
 * 字段顺序就是签名 query 的参数顺序，签名对它敏感，所以这里只做保序搬运，
 * 绝不排序、不去重。
 */
class Device(val fields: List<Pair<String, String>>, val userAgent: String) {

    fun get(key: String): String = fields.firstOrNull { it.first == key }?.second ?: ""

    companion object {

        /** Medusa message 的 field 12：设备指纹 protobuf（除 device_id 外全是固定值）。 */
        fun deviceProto(deviceId: String): ByteArray = Proto.encode(
            listOf(
                1 to Proto.Value.SInt(1),
                2 to Proto.Value.SInt(2),
                3 to Proto.Value.Str(Consts.APP_ID),
                4 to Proto.Value.Str(deviceId),
                5 to Proto.Value.Str("Ai6svO3PyrwDOUSmO6ZcResxu"),
                6 to Proto.Value.Str("!noperm!"),
                7 to Proto.Value.SInt(-888_888),
                8 to Proto.Value.SInt(-888_888),
                9 to Proto.Value.SInt(3),
                10 to Proto.Value.SInt(-888_888),
                11 to Proto.Value.Str("!notset!"),
                12 to Proto.Value.Str("Asia/Shanghai,8"),
                13 to Proto.Value.Str("zh_CN"),
                14 to Proto.Value.SInt(4),
                16 to Proto.Value.Float32(255.24993896484375f),
                17 to Proto.Value.Float32(35.58599090576172f),
                18 to Proto.Value.Float32(3.467449188232422f),
                19 to Proto.Value.Float32(3.467449188232422f),
                20 to Proto.Value.Float32(255.1754913330078f),
                21 to Proto.Value.Float32(42.17544174194336f),
                22 to Proto.Value.Str("16"),
                23 to Proto.Value.SInt(41),
                24 to Proto.Value.SInt(36),
                25 to Proto.Value.SInt(1_728_388_016_635),
                26 to Proto.Value.SInt(1_728_388_016_635),
                27 to Proto.Value.SInt(1_728_388_016_635),
                28 to Proto.Value.SInt(1_728_388_016_637),
                29 to Proto.Value.SInt(-1),
                30 to Proto.Value.Str("25053RT47C"),
                31 to Proto.Value.Str("Redmi"),
                32 to Proto.Value.Str("25053RT47C"),
                33 to Proto.Value.Str("25053RT47C"),
                34 to Proto.Value.Str("Xiaomi"),
                35 to Proto.Value.Str("Redmi"),
                36 to Proto.Value.Str("Redmi"),
                38 to Proto.Value.SInt(31),
            ),
        )
    }
}
