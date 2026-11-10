package com.hongguotv.pad.sign

/**
 * X-Medusa：服务端唯一强制校验的签名头。
 *
 * 流程：设备指纹 → protobuf → SM3 派生 key 逐字节变换 → 反序 + 掩码 →
 * 自研 AES 变体加密 → 拼版本前缀 → base64。
 *
 * 所有随机量集中在 [Entropy] 里，方便测试喂固定值对齐黄金向量。
 */
object Medusa {

    /** 一次签名的随机量（顺序与参考实现一致）。 */
    data class Entropy(
        val envLaunch: Long,
        val envPid: Long,
        val nowSecs: Long,
        val messageRandom: Long,
        val random: Int,
        val packedRandom: Int,
    )

    fun randomEntropy(): Entropy {
        val rnd = java.util.Random()
        return Entropy(
            envLaunch = 100L + rnd.nextInt(21),
            envPid = 10_001L + rnd.nextInt(2_000),
            nowSecs = System.currentTimeMillis() / 1000,
            messageRandom = rnd.nextInt().toLong() and 0xFFFF_FFFFL,
            random = rnd.nextInt(),
            packedRandom = rnd.nextInt(),
        )
    }

    /** 用 SM3 派生的 key 做逐字节置换。 */
    fun xmxor(data: ByteArray, key: ByteArray): ByteArray {
        val n = data.size
        val encoded = ByteArray(n)

        for (i in 0 until n) {
            val at = (i * 4) and 28
            val d0 = key[at].toInt() and 0xFF
            val d1 = key[at + 1].toInt() and 0xFF
            val raw = data[i].toInt() and 0xFF
            var d2 = ((((raw shl 4) or (raw ushr 4)) and 255) + d0) and 0xFF
            d2 = (d2.inv() xor d1) and 0xFF
            d2 = ((d2 shl 3) or (d2 ushr 5)) and 0xFF
            d2 = (d2 + d1) and 0xFF
            d2 = d2 xor d0
            encoded[n - i - 1] = (d2.inv() and 0xFF).toByte()
        }

        val last = (encoded[n - 1].toInt() and 0xFF) xor (encoded[n - 2].toInt() and 0xFF)
        val first = encoded[0].toInt() and 0xFF
        encoded[0] = (((last.inv() and 0xFF) + first) and 0xFF).toByte()
        encoded[1] = (((encoded[0].toInt() and 0xFF) xor (encoded[n - 1].toInt() and 0xFF) xor 254) +
            (encoded[1].toInt() and 0xFF) and 0xFF).toByte()

        val b1 = encoded[1].toInt() and 0xFF
        val shifted = ((b1 shl 3) or (b1 ushr 5)) and 0xFF
        val fix2 = ((last - first) and 0xFF) xor (shifted xor 2)
        encoded[2] = (((encoded[2].toInt() and 0xFF) + fix2) and 0xFF).toByte()

        for (i in 0 until n - 4) {
            val e2 = encoded[i + 2].toInt() and 0xFF
            val shiftedInner = ((e2 shl 3) or (e2 ushr 5)) and 0xFF
            val temp = shiftedInner xor (encoded[i + 1].toInt() and 0xFF) xor ((i + 3) and 0xFF)
            encoded[i + 3] = (((temp.inv() and 0xFF) + (encoded[i + 3].toInt() and 0xFF)) and 0xFF).toByte()
        }

        encoded[n - 1] = ((encoded[n - 1].toInt() and 0xFF) xor (encoded[n - 2].toInt() and 0xFF)).toByte()

        // 累加时跳过第 0 个字节（参考实现如此，差一位就整体失配）
        var sum = 0
        for (i in 1 until n) sum = (sum + (encoded[i].toInt() and 0xFF)) and 0xFF
        encoded[0] = (((encoded[0].toInt() and 0xFF) xor (encoded[1].toInt() and 0xFF)) + sum and 0xFF).toByte()

        return encoded
    }

    /** 由签名主密钥 + 随机数派生 (hash, seed)。 */
    fun keyHash(signKey: ByteArray, random: Int): Pair<ByteArray, ByteArray> {
        val buf = signKey + le32(random) + signKey
        val hash = Digest.sm3(buf)

        val d1 = (random ushr 16) and 255
        var d2 = ((d1 shl 11) or (random ushr 24)) xor (d1 ushr 5) xor d1
        d2 = d2.inv()
        return hash to le32(d2)
    }

    fun build(
        url: String,
        body: ByteArray?,
        khronos: Int,
        deviceId: String,
        versionName: String,
        entropy: Entropy = randomEntropy(),
    ): String = assemble(url, body, khronos, deviceId, versionName, entropy)

    /** 调试用：只产出 protobuf message 字节，供与参考实现逐字节对拍。 */
    fun debugMessage(
        url: String,
        body: ByteArray?,
        khronos: Int,
        deviceId: String,
        versionName: String,
        entropy: Entropy,
    ): ByteArray {
        val bodyMd5 = if (body != null) Digest.md5Raw(body) else ByteArray(16)
        val queryRaw = url.substringAfter('?', "")
        val querySm3 = Digest.sm3(queryRaw.toByteArray(Charsets.UTF_8))
        val ts = le32(khronos)
        val queryBodyTs = Argus.hashF13(querySm3, bodyMd5, ts, khronos)

        val nested = Proto.encode(
            listOf(
                1 to Proto.Value.SInt(111),
                2 to Proto.Value.SInt(10),
                3 to Proto.Value.SInt(694_367),
                5 to Proto.Value.SInt(586_952_199),
            ),
        )
        val nested13 = Proto.encode(
            listOf(
                1 to Proto.Value.SInt(entropy.nowSecs),
                2 to Proto.Value.SInt(-2),
                4 to Proto.Value.SInt(200),
            ),
        )
        val env = Proto.encode(
            listOf(
                1 to Proto.Value.SInt(entropy.envLaunch),
                2 to Proto.Value.SInt(146_331_399),
                3 to Proto.Value.SInt(146_331_396),
                5 to Proto.Value.SInt(7),
                6 to Proto.Value.Str("v04.06.04.03-bugfix"),
                7 to Proto.Value.SInt(entropy.envPid),
                12 to Proto.Value.Bytes(Device.deviceProto(deviceId)),
                13 to Proto.Value.Bytes(nested13),
                14 to Proto.Value.Str(versionName),
            ),
        )
        val queryHash = Digest.sm3(
            queryRaw.toByteArray(Charsets.UTF_8) + bodyMd5 + "none".toByteArray(Charsets.US_ASCII),
        )
        return Proto.encode(
            listOf(
                1 to Proto.Value.Bytes(hexToBytes("f7e85ffad7d7dc3bd62ac87057cf6118")),
                2 to Proto.Value.SInt(3),
                3 to Proto.Value.SInt(entropy.messageRandom),
                4 to Proto.Value.Str(Consts.APP_ID),
                5 to Proto.Value.Str(deviceId),
                6 to Proto.Value.Str(Consts.CHANNEL_ID),
                7 to Proto.Value.Str(versionName),
                8 to Proto.Value.Str("v04.06.04-ml-android"),
                9 to Proto.Value.SInt(67_503_104),
                10 to Proto.Value.Bytes(hexToBytes("4001000000000000")),
                12 to Proto.Value.SInt(khronos.toLong()),
                13 to Proto.Value.Bytes(queryBodyTs),
                14 to Proto.Value.Bytes(querySm3.copyOfRange(0, 6)),
                15 to Proto.Value.Bytes(nested),
                16 to Proto.Value.Str("AXYQOS6n2m60x1fVZHIrH3iol"),
                17 to Proto.Value.SInt(khronos.toLong()),
                19 to Proto.Value.Bytes(queryHash),
                20 to Proto.Value.Str("none"),
                21 to Proto.Value.SInt(312),
                23 to Proto.Value.Bytes(env),
                24 to Proto.Value.Str(
                    "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":1879194040,\"vpn\":0,\"kd\":0," +
                        "\"fkd\":3672518972,\"pd\":-1872573247,\"dyn\":\"\",\"do\":0,\"tk\":true}",
                ),
            ),
        )
    }

    fun assemble(
        url: String,
        body: ByteArray?,
        khronos: Int,
        deviceId: String,
        versionName: String,
        entropy: Entropy,
    ): String {
        val bodyMd5 = if (body != null) Digest.md5Raw(body) else ByteArray(16)
        val queryRaw = url.substringAfter('?', "")
        val querySm3 = Digest.sm3(queryRaw.toByteArray(Charsets.UTF_8))
        val ts = le32(khronos)

        val queryBodyTs = Argus.hashF13(querySm3, bodyMd5, ts, khronos)
        val check = ((querySm3[0].toInt() and 63) shl 14) or
            0x18000001 or
            ((queryBodyTs[0].toInt() and 63) shl 8)

        val nested = Proto.encode(
            listOf(
                1 to Proto.Value.SInt(111),
                2 to Proto.Value.SInt(10),
                3 to Proto.Value.SInt(694_367),
                5 to Proto.Value.SInt(586_952_199),
            ),
        )
        val nested13 = Proto.encode(
            listOf(
                1 to Proto.Value.SInt(entropy.nowSecs),
                2 to Proto.Value.SInt(-2),
                4 to Proto.Value.SInt(200),
            ),
        )
        val env = Proto.encode(
            listOf(
                1 to Proto.Value.SInt(entropy.envLaunch),
                2 to Proto.Value.SInt(146_331_399),
                3 to Proto.Value.SInt(146_331_396),
                5 to Proto.Value.SInt(7),
                6 to Proto.Value.Str("v04.06.04.03-bugfix"),
                7 to Proto.Value.SInt(entropy.envPid),
                12 to Proto.Value.Bytes(Device.deviceProto(deviceId)),
                13 to Proto.Value.Bytes(nested13),
                14 to Proto.Value.Str(versionName),
            ),
        )

        val queryHash = Digest.sm3(
            queryRaw.toByteArray(Charsets.UTF_8) + bodyMd5 + "none".toByteArray(Charsets.US_ASCII),
        )

        val message = Proto.encode(
            listOf(
                1 to Proto.Value.Bytes(hexToBytes("f7e85ffad7d7dc3bd62ac87057cf6118")),
                2 to Proto.Value.SInt(3),
                3 to Proto.Value.SInt(entropy.messageRandom),
                4 to Proto.Value.Str(Consts.APP_ID),
                5 to Proto.Value.Str(deviceId),
                6 to Proto.Value.Str(Consts.CHANNEL_ID),
                7 to Proto.Value.Str(versionName),
                8 to Proto.Value.Str("v04.06.04-ml-android"),
                9 to Proto.Value.SInt(67_503_104),
                10 to Proto.Value.Bytes(hexToBytes("4001000000000000")),
                12 to Proto.Value.SInt(khronos.toLong()),
                13 to Proto.Value.Bytes(queryBodyTs),
                14 to Proto.Value.Bytes(querySm3.copyOfRange(0, 6)),
                15 to Proto.Value.Bytes(nested),
                16 to Proto.Value.Str("AXYQOS6n2m60x1fVZHIrH3iol"),
                17 to Proto.Value.SInt(khronos.toLong()),
                19 to Proto.Value.Bytes(queryHash),
                20 to Proto.Value.Str("none"),
                21 to Proto.Value.SInt(312),
                23 to Proto.Value.Bytes(env),
                24 to Proto.Value.Str(
                    "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":1879194040,\"vpn\":0,\"kd\":0," +
                        "\"fkd\":3672518972,\"pd\":-1872573247,\"dyn\":\"\",\"do\":0,\"tk\":true}",
                ),
            ),
        )

        val random = entropy.random
        val (hash, seed) = keyHash(hexToBytes(Consts.MEDUSA_SIGN_KEY), random)

        var transformed = hexToBytes("4001000000000000") + xmxor(message, hash)
        transformed = transformed.reversedArray()
        for (i in transformed.indices) {
            transformed[i] = ((transformed[i].toInt() and 0xFF) xor (seed[i.inv() and 3].toInt() and 0xFF)).toByte()
        }

        val packed = byteArrayOf(0x35) +
            le32(entropy.packedRandom) +
            le32(check) +
            transformed +
            byteArrayOf(((random ushr 16) and 255).toByte(), ((random ushr 24) and 255).toByte())

        val encrypted = AesV3(hexToBytes(Consts.MEDUSA_AES_KEY), khronos)
            .encrypt(packed, hexToBytes(Consts.MEDUSA_AES_IV))

        // 版本前缀：每 4 字节与 khronos 异或
        val version = hexToBytes("03000000f7e85ffad7d7dc3bd62ac87057cf6118")
        val prefix = ByteArray(20)
        for (i in 0 until 5) {
            val v = le32ToInt(version, i * 4) xor khronos
            le32(v).copyInto(prefix, i * 4)
        }

        val out = prefix +
            byteArrayOf((random and 255).toByte(), ((random ushr 8) and 255).toByte(), 0, 1) +
            encrypted
        return base64(out)
    }
}
