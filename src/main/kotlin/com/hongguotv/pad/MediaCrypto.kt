package com.hongguotv.pad

import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 片源解密：CENC（ISO/IEC 23001-7）+ MP4 容器解析（ISO/IEC 14496-12）。
 *
 * CDN 上拿到的是**加密 MP4**，播放器只认明文，所以这里要做四件事：
 *
 *  1. 读文件头：确认 `ftyp` 后面紧跟 `moov`（索引在文件开头，才能边下边播）；
 *  2. 读样本表：把每个音/视频轨道的「第几个样本在哪、多长、IV 是什么」解析出来；
 *  3. 还原编码标记：容器里把加密轨道标成 `encv`/`enca`，要换回真实编码（`hvc1`/`avc1`），
 *     否则解码器不认这条轨道；
 *  4. 按需解密：播放器每请求一段字节，就把落在这段里的密文用 AES-128-CTR 解掉。
 *
 * 密钥来自播放接口下发的混淆串（见 [deriveKey]），不是硬编码。
 *
 * 设计上刻意保持「纯函数 + 不可变输入」：只依赖 JDK 的 crypto/ByteBuffer，
 * 不碰 Android API，方便单独写对拍测试。
 */
object MediaCrypto {

    /** 索引（moov）最大允许 8MB：正常短剧的索引只有几百 KB。 */
    private const val MAX_INDEX_BYTES = 8L * 1024 * 1024

    /** 单轨样本数上限：防止畸形文件把内存吃光。 */
    private const val MAX_SAMPLES = 200_000L

    /** 需要继续往下钻的容器盒子类型。 */
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "sinf", "schi")

    /**
     * 一段密文。
     *
     * @param offset       在文件里的绝对偏移
     * @param length       密文字节数
     * @param iv           该样本自带的 8 字节初始向量
     * @param streamOffset 这段密文在本样本「加密流」里的起点（子样本时前面有明文，要跳过）
     */
    class CipherBlock(
        val offset: Long,
        val length: Int,
        val iv: ByteArray,
        val streamOffset: Long,
    )

    /**
     * 预处理结果。
     *
     * @param header 索引区（moov）在**解密后**应有的字节：加密标记已被还原成真实编码
     * @param blocks 所有需要解密的密文段，按文件偏移升序
     */
    class Prepared(val header: ByteArray, val blocks: List<CipherBlock>)

    // ---------------------------------------------------------------- 密钥

    /**
     * 把接口下发的密钥串还原成 16 字节 AES 密钥。
     *
     * 下发的是 base64（URL-safe、可能缺 padding），解开后前三个字节决定有效长度，
     * 有效段还要按「奇偶两条链」逐字节去混淆，最后 32 个字符是十六进制密钥本体。
     */
    fun deriveKey(payload: String): ByteArray {
        val blob = base64Url(payload)
        require(blob.size >= 3) { "内容密钥长度不足" }

        val shift = (blob[0].toInt() and 0xFF) xor
            (blob[1].toInt() and 0xFF) xor
            (blob[2].toInt() and 0xFF)
        val span = minOf(blob.size - shift + 47, blob.size - 1)
        require(span >= 33) { "内容密钥结构异常" }

        // 从第 2 个字节开始是混淆段；偶数位和奇数位各自维护「上一个原始字节」
        val body = blob.copyOfRange(1, 1 + span)
        var oddPrev = 85
        var evenPrev = 246
        for (i in body.indices) {
            val current = body[i].toInt() and 0xFF
            val previous: Int
            if (i and 1 == 1) {
                previous = oddPrev
                oddPrev = current
            } else {
                previous = evenPrev
                evenPrev = current
            }
            body[i] = (-21 - Integer.bitCount(i) + (previous xor current)).toByte()
        }

        // 去混淆后第 2 字节起是 32 个十六进制字符 → 16 字节
        val text = String(body, 1, 32, Charsets.US_ASCII)
        return hexToBytes(text).also { require(it.size == 16) { "内容密钥格式异常" } }
    }

    // ---------------------------------------------------------------- 索引范围

    /**
     * 算出 moov（索引）在文件里的结束位置。
     *
     * 播放器要先拿到完整索引才能建解码器，所以起播第一步永远是「读到 headerEnd 为止」。
     */
    fun headerEnd(head: ByteArray, total: Long): Int {
        if (head.size < 16) throw IOException("文件头太短")
        if (fourcc(head, 4) != "ftyp") throw IOException("不是 MP4 容器")

        val ftypSize = u32(head, 0)
        if (ftypSize < 8 || ftypSize + 8 > head.size) throw IOException("文件头结构异常")
        if (fourcc(head, ftypSize.toInt() + 4) != "moov") throw IOException("索引不在文件开头")

        val moovSize = u32(head, ftypSize.toInt())
        val end = ftypSize + moovSize
        if (moovSize < 8 || end > total || end > MAX_INDEX_BYTES) throw IOException("索引大小异常")
        return end.toInt()
    }

    // ---------------------------------------------------------------- 预处理

    /**
     * 解析索引，得到「解密后头部 + 待解密段列表」。
     *
     * 传 null 密钥表示这条流没加密，此时只把索引原样留着。
     */
    fun prepare(index: ByteArray, key: ByteArray?): Prepared {
        if (key == null) return Prepared(index.copyOf(), emptyList())
        if (index.size < 8) throw IOException("索引不完整")

        val moov = boxAt(index, u32(index, 0).toInt(), index.size)
            ?: throw IOException("索引不完整")
        if (moov.type != "moov") throw IOException("索引位置异常")

        val header = index.copyOf()
        val blocks = ArrayList<CipherBlock>(1024)

        for (trak in children(index, moov)) {
            if (trak.type != "trak") continue
            val stbl = descendant(index, trak, "stbl") ?: continue
            val saiz = descendant(index, stbl, "saiz") ?: continue   // 没有辅助信息 = 这条轨道没加密

            val stsz = descendant(index, stbl, "stsz") ?: throw IOException("样本大小表缺失")
            val stsc = descendant(index, stbl, "stsc") ?: throw IOException("样本块映射表缺失")
            val stco = descendant(index, stbl, "stco")
                ?: descendant(index, stbl, "co64")
                ?: throw IOException("块偏移表缺失")
            val saio = descendant(index, stbl, "saio") ?: throw IOException("辅助信息偏移表缺失")

            readTrack(index, stsz, stsc, stco, saiz, saio, blocks)
            restoreCodec(index, stbl, header)
        }

        if (blocks.isEmpty()) throw IOException("没有可解密的样本")

        blocks.sortBy { it.offset }
        for (i in 1 until blocks.size) {
            val prev = blocks[i - 1]
            if (prev.offset + prev.length > blocks[i].offset) throw IOException("样本区间重叠")
        }
        return Prepared(header, blocks)
    }

    /** 读一条轨道的样本表，把所有密文段追加到 [out]。 */
    private fun readTrack(
        data: ByteArray,
        stsz: Box,
        stsc: Box,
        stco: Box,
        saiz: Box,
        saio: Box,
        out: MutableList<CipherBlock>,
    ) {
        // ---- 每个样本多大
        val sampleCount = readCount(data, stsz.body + 8, stsz.end)
        val uniformSize = u32(data, stsz.body + 4)
        if (uniformSize == 0L && stsz.body + 12 + sampleCount * 4L > stsz.end) {
            throw IOException("样本大小表截断")
        }
        val sampleSizes = LongArray(sampleCount) {
            if (uniformSize > 0) uniformSize else u32(data, stsz.body + 12 + it * 4)
        }

        // ---- 每个块在文件里的位置
        val chunkCount = readCount(data, stco.body + 4, stco.end)
        val wide = stco.type == "co64"
        val width = if (wide) 8 else 4
        if (stco.body + 8 + chunkCount * width.toLong() > stco.end) throw IOException("块偏移表截断")
        val chunkOffsets = LongArray(chunkCount) {
            val at = stco.body + 8 + it * width
            if (wide) u64(data, at) else u32(data, at)
        }

        // ---- 块 → 样本数的映射（stsc：从第几块起、每块几个样本）
        val runCount = readCount(data, stsc.body + 4, stsc.end)
        if (runCount == 0) throw IOException("样本块映射为空")
        if (stsc.body + 8 + runCount * 12L > stsc.end) throw IOException("样本块映射截断")
        val runs = Array(runCount) { i ->
            val at = stsc.body + 8 + i * 12
            u32(data, at) to u32(data, at + 4).toInt()
        }

        // ---- 每个样本的辅助信息长度（saiz）
        val saizFlags = u32(data, saiz.body).toInt()
        val saizBody = saiz.body + 4 + if (saizFlags and 1 != 0) 8 else 0
        if (saizBody + 5 > saiz.end) throw IOException("辅助信息表截断")
        val uniformAux = data[saizBody].toInt() and 0xFF
        val auxCount = readCount(data, saizBody + 1, saiz.end)
        if (auxCount != sampleCount) throw IOException("辅助信息数量与样本数不一致")
        if (uniformAux == 0 && saizBody + 5 + sampleCount.toLong() > saiz.end) {
            throw IOException("辅助信息表截断")
        }
        val auxSizes = IntArray(sampleCount) {
            if (uniformAux > 0) uniformAux else data[saizBody + 5 + it].toInt() and 0xFF
        }

        // ---- 辅助信息在文件里的起始位置（saio，只有一段）
        val saioVersion = data[saio.body].toInt() and 0xFF
        val saioFlags = u32(data, saio.body).toInt()
        val saioBody = saio.body + 4 + if (saioFlags and 1 != 0) 8 else 0
        val offsetWidth = if (saioVersion == 1) 8 else 4
        if (readCount(data, saioBody, saio.end) != 1) throw IOException("不支持多段辅助信息")
        if (saioBody + 4 + offsetWidth > saio.end) throw IOException("辅助信息偏移表截断")
        var auxPos = if (offsetWidth == 8) u64(data, saioBody + 4) else u32(data, saioBody + 4)

        val auxLast = auxPos + auxSizes.sumOf { it.toLong() }
        if (auxPos < 0 || auxLast > data.size) throw IOException("辅助信息超出索引范围")

        // ---- 逐块、逐样本展开
        var sample = 0
        var run = 0
        for (chunk in 0 until chunkCount) {
            while (run + 1 < runs.size && runs[run + 1].first <= chunk + 1) run++
            val perChunk = runs[run].second
            if (perChunk <= 0) throw IOException("样本块映射异常")

            var cursor = chunkOffsets[chunk]
            repeat(perChunk) {
                if (sample >= sampleCount) throw IOException("样本数量与块映射不一致")
                val size = sampleSizes[sample]
                val auxSize = auxSizes[sample]
                if (size <= 0 || cursor < 0 || cursor > Long.MAX_VALUE - size) {
                    throw IOException("样本偏移异常")
                }
                if (auxSize < 8) throw IOException("辅助信息过短")

                val auxAt = auxPos.toInt()
                val iv = data.copyOfRange(auxAt, auxAt + 8)

                if (auxSize == 8) {
                    // 整个样本都是密文
                    out += CipherBlock(cursor, size.toInt(), iv, 0L)
                } else {
                    // 子样本：每项 2 字节明文长度 + 4 字节密文长度
                    if (auxSize < 10) throw IOException("子样本表异常")
                    val subs = ((data[auxAt + 8].toInt() and 0xFF) shl 8) or
                        (data[auxAt + 9].toInt() and 0xFF)
                    if (auxSize != 10 + subs * 6) throw IOException("子样本表长度异常")

                    var inner = cursor
                    var streamed = 0L
                    for (s in 0 until subs) {
                        val at = auxAt + 10 + s * 6
                        val clear = ((data[at].toInt() and 0xFF) shl 8) or
                            (data[at + 1].toInt() and 0xFF)
                        val cipher = u32(data, at + 2)
                        inner += clear
                        if (inner + cipher > cursor + size) throw IOException("子样本越界")
                        if (cipher > 0) out += CipherBlock(inner, cipher.toInt(), iv, streamed)
                        streamed += cipher
                        inner += cipher
                    }
                    if (inner != cursor + size) throw IOException("子样本总长与样本不符")
                }

                cursor += size
                auxPos += auxSize
                sample++
            }
        }
        if (sample != sampleCount) throw IOException("样本未被块映射完整覆盖")
    }

    /**
     * 把「加密轨道」改回真实编码。
     *
     * `stbl` 里的 `sinf/frma` 记着原始编码（`hvc1`/`avc1`），而样本描述里写的是
     * `encv`/`enca`。不改回来的话，解码器拿到的是「未知加密轨道」，直接不出画面。
     */
    private fun restoreCodec(data: ByteArray, stbl: Box, header: ByteArray) {
        var from = stbl.body
        while (true) {
            val sinf = scanBox(data, "sinf", from, stbl.end) ?: break
            val frma = descendant(data, sinf, "frma") ?: throw IOException("原始编码标记缺失")
            if (frma.body + 4 > frma.end) throw IOException("原始编码标记异常")
            val codec = data.copyOfRange(frma.body, frma.body + 4)

            // 从 sinf 往前找样本描述里的 encv/enca，把真实编码写进去
            var at = sinf.start - 1
            while (at >= stbl.body) {
                val type = fourcc(data, at)
                if (type == "encv" || type == "enca") {
                    codec.copyInto(header, at)
                    break
                }
                at--
            }

            // sinf 本身之后无用：类型改成 free、内容清零
            "free".toByteArray(Charsets.US_ASCII).copyInto(header, sinf.start + 4)
            header.fill(0, sinf.body, sinf.end)
            from = sinf.end
        }
    }

    // ---------------------------------------------------------------- 解密

    /**
     * 解密文件里 `[at, at+chunk.size)` 这一段。
     *
     * 播放器会并发请求很多段（视频轨、音频轨、探测），所以这里必须**只处理与这段相交的
     * 样本**：先用二分找到第一个可能相交的段，再往后扫。
     */
    fun decrypt(chunk: ByteArray, at: Long, key: ByteArray?, prepared: Prepared): ByteArray {
        require(at >= 0) { "偏移不能为负" }
        val out = chunk.copyOf()
        val end = at + chunk.size

        // 索引区用「解密后」的版本覆盖（编码标记已还原）
        if (at < prepared.header.size) {
            val to = minOf(end, prepared.header.size.toLong()).toInt()
            prepared.header.copyInto(out, 0, at.toInt(), to)
        }
        if (key == null) return out

        val blocks = prepared.blocks
        var lo = 0
        var hi = blocks.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (blocks[mid].offset + blocks[mid].length <= at) lo = mid + 1 else hi = mid
        }

        for (i in lo until blocks.size) {
            val block = blocks[i]
            if (block.offset >= end) break

            val from = maxOf(at, block.offset)
            val to = minOf(end, block.offset + block.length)
            if (from >= to) continue

            // 在本样本加密流里的位置：决定计数器从哪一块开始、开头要跳过几个字节
            val stream = block.streamOffset + (from - block.offset)
            val blockIndex = stream / 16
            val skip = (stream % 16).toInt()

            val counter = counterAt(block.iv, blockIndex)
            val payload = ByteArray(skip) + out.copyOfRange((from - at).toInt(), (to - at).toInt())
            val plain = ctrDecrypt(key, counter, payload)
            plain.copyInto(out, (from - at).toInt(), skip)
        }
        return out
    }

    /**
     * 128 位计数器：**IV 占高 64 位，块序号占低 64 位**（CENC 的约定），相加后回绕。
     *
     * 注意不能把 8 字节 IV 当成一个普通整数直接加块序号——那样 IV 会跑到低 64 位，
     * 等于换了半把钥匙，解出来是花屏。
     */
    private fun counterAt(iv: ByteArray, blockIndex: Long): ByteArray {
        val base = ByteArray(16)
        iv.copyInto(base, 0, 0, minOf(iv.size, 8))
        val limit = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
        val value = BigInteger(1, base).add(BigInteger.valueOf(blockIndex)).and(limit)
        val raw = value.toByteArray()
        val out = ByteArray(16)
        val n = minOf(raw.size, 16)
        raw.copyInto(out, 16 - n, raw.size - n, raw.size)
        return out
    }

    private fun ctrDecrypt(key: ByteArray, counter: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
            doFinal(data)
        }

    // ---------------------------------------------------------------- 盒子解析

    /** 一个 MP4 盒子：`[start, end)` 是整盒范围，`body` 是去掉头之后的起点。 */
    private class Box(val type: String, val start: Int, val body: Int, val end: Int)

    /**
     * 在 [at] 处读一个盒子。
     *
     * 大小字段有三种写法：普通 32 位、`1` 表示后面 8 字节是真大小、`0` 表示到父盒末尾。
     */
    private fun boxAt(data: ByteArray, at: Int, limit: Int): Box? {
        if (at < 0 || at + 8 > limit) return null
        val size32 = u32(data, at)
        var header = 8
        var size = size32
        if (size32 == 1L) {
            if (at + 16 > limit) return null
            size = u64(data, at + 8)
            header = 16
        } else if (size32 == 0L) {
            size = (limit - at).toLong()
        }
        if (size < header || size > limit - at) return null
        return Box(fourcc(data, at + 4), at, at + header, at + size.toInt())
    }

    /** 列出父盒下的直接子盒（遇到畸形头就停，不越界）。 */
    private fun children(data: ByteArray, parent: Box): List<Box> {
        val out = ArrayList<Box>(8)
        var at = parent.body
        while (at + 8 <= parent.end) {
            val child = boxAt(data, at, parent.end) ?: break
            if (child.end <= at) break
            out += child
            at = child.end
        }
        return out
    }

    /** 在已知容器里按类型找后代盒。 */
    private fun descendant(data: ByteArray, root: Box, type: String): Box? {
        for (child in children(data, root)) {
            if (child.type == type) return child
            if (child.type in CONTAINERS) descendant(data, child, type)?.let { return it }
        }
        return null
    }

    /**
     * 在 [from, limit) 里扫一个盒子类型。
     *
     * 为什么不用 [descendant]：`sinf` 挂在样本描述 `encv`/`enca` 里，而样本描述的前面
     * 是一段定长字段（不是盒子头），无法当容器正常遍历，只能按「类型 + 合法大小」扫描。
     */
    private fun scanBox(data: ByteArray, type: String, from: Int, limit: Int): Box? {
        var at = from
        while (at + 8 <= limit) {
            if (fourcc(data, at + 4) == type) {
                val box = boxAt(data, at, limit)
                if (box != null && box.type == type) return box
            }
            at++
        }
        return null
    }

    // ---------------------------------------------------------------- 字节工具

    private fun u32(data: ByteArray, at: Int): Long =
        ByteBuffer.wrap(data, at, 4).int.toLong() and 0xFFFF_FFFFL

    private fun u64(data: ByteArray, at: Int): Long = ByteBuffer.wrap(data, at, 8).long

    private fun fourcc(data: ByteArray, at: Int): String =
        if (at >= 0 && at + 4 <= data.size) String(data, at, 4, Charsets.US_ASCII) else ""

    private fun readCount(data: ByteArray, at: Int, limit: Int): Int {
        if (at < 0 || at + 4 > limit) throw IOException("索引越界")
        val n = u32(data, at)
        if (n > MAX_SAMPLES) throw IOException("索引数量异常")
        return n.toInt()
    }

    /** URL-safe base64，允许缺省 padding。 */
    private fun base64Url(text: String): ByteArray {
        val normalized = text.replace('-', '+').replace('_', '/')
        val padding = (4 - normalized.length % 4) % 4
        return Base64.getDecoder().decode(normalized + "=".repeat(padding))
    }

    private fun hexToBytes(text: String): ByteArray {
        require(text.length % 2 == 0) { "十六进制长度异常" }
        return ByteArray(text.length / 2) {
            text.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}
