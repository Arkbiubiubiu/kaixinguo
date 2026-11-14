package com.hongguotv.pad

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * 把「解密后的 bvc2 MP4」拆成 libmpv 能直接播的两条流：
 *  - 视频：容器里的 4 字节长度前缀 → 00 00 00 01 起始码的 Annex-B（.266）
 *  - 音频：AAC 原始帧 → 补 ADTS 头（.aac）
 *
 * 只做容器拆包，不做任何转码。实测（服务器 ffmpeg）这样拆出来的 VVC 流
 * 可以被原生 vvc 解码器完整解出。
 */
object VvcExport {

    class Result(val videoOk: Boolean, val audioOk: Boolean, val fps: Double, val duration: Double)

    private class Track(
        val offsets: LongArray,
        val sizes: IntArray,
        val audioConfig: ByteArray?,
        val fps: Double = 0.0,
        val duration: Double = 0.0,
    )

    fun export(mp4: File, videoOut: File, audioOut: File): Result = runCatching {
        RandomAccessFile(mp4, "r").use { raf ->
            val len = raf.length().toInt()
            val head = ByteArray(minOf(len, 4 * 1024 * 1024))
            raf.seek(0)
            raf.readFully(head)
            val moov = findBox(head, 0, head.size, "moov")
            if (moov == null) {
                Log.w("VvcExport", "moov not found in first ${head.size} bytes")
                return Result(false, false, 0.0, 0.0)
            }
            var video: Track? = null
            var audio: Track? = null
            forEachBox(head, moov.first + 8, moov.first + moov.second) { p, sz, type ->
                if (type == "trak") {
                    val mdia = findBox(head, p + 8, p + sz, "mdia")
                    val hdlr = mdia?.let { findBox(head, it.first + 8, it.first + it.second, "hdlr") }
                    val kind = hdlr?.let {
                        val b = it.first + 8
                        if (b + 12 <= head.size) String(head, b + 8, 4, Charsets.US_ASCII) else ""
                    }
                    val track = parseTrack(head, p + 8, p + sz)
                    if (kind == "vide" && video == null) video = track
                    if (kind == "soun" && audio == null) audio = track
                }
            }
            Log.i("VvcExport", "tracks: video=${video?.sizes?.size} audio=${audio?.sizes?.size} cfg=${audio?.audioConfig?.size}")
            val v = video
            if (v == null) return Result(false, false, 0.0, 0.0)
            // 视频 → Annex-B
            var written = 0
            val vOffsets = LongArray(v.sizes.size)
            val vKeys = BooleanArray(v.sizes.size)
            val params = java.io.ByteArrayOutputStream()
            var vBytes = 0L
            videoOut.outputStream().buffered(1 shl 20).use { out ->
                for (i in v.sizes.indices) {
                    val size = v.sizes[i]
                    vOffsets[i] = vBytes
                    if (size <= 0 || size > 8 shl 20) continue
                    raf.seek(v.offsets[i])
                    val data = ByteArray(size)
                    raf.readFully(data)
                    var p = 0
                    while (p + 4 <= data.size) {
                        val n = be32(data, p)
                        if (n <= 0 || p + 4 + n > data.size) break
                        val nal = data.copyOfRange(p + 4, p + 4 + n)
                        val type = nalType(nal)
                        // 关键帧：IDR_W_RADL(7)/IDR_N_LP(8)/CRA(9)，seek 只能落在这里
                        if (type == 7 || type == 8 || type == 9) vKeys[i] = true
                        // 第一帧里收集参数集（SPS=15/PPS=16/SEI=23），切段时补在最前面
                        if (i == 0 && (type == 15 || type == 16 || type == 23)) {
                            params.write(byteArrayOf(0, 0, 0, 1))
                            params.write(nal)
                        }
                        out.write(byteArrayOf(0, 0, 0, 1))
                        out.write(nal)
                        vBytes += 4L + n
                        written++
                        p += 4 + n
                    }
                }
            }
            if (vKeys.isNotEmpty()) vKeys[0] = true
            runCatching { java.io.File(videoOut.parentFile, "params.266").writeBytes(params.toByteArray()) }
            if (written == 0) {
                Log.e("VvcExport", "视频轨没有解出任何 NAL（解密 key 不对？）")
                return Result(false, false, 0.0, 0.0)
            }
            // 音频 → ADTS
            var audioOk = false
            val a = audio
            if (a != null && a.audioConfig != null && a.audioConfig.size >= 2) {
                val cfg = a.audioConfig
                val objectType = (cfg[0].toInt() ushr 3) and 0x1F
                val freqIdx = ((cfg[0].toInt() and 0x07) shl 1) or ((cfg[1].toInt() ushr 7) and 0x01)
                val channels = (cfg[1].toInt() ushr 3) and 0x0F
                val aOffsets = LongArray(a.sizes.size)
                var aBytes = 0L
                audioOut.outputStream().buffered(1 shl 18).use { out ->
                    for (i in a.sizes.indices) {
                        val size = a.sizes[i]
                        aOffsets[i] = aBytes
                        if (size <= 0) continue
                        raf.seek(a.offsets[i])
                        val data = ByteArray(size)
                        raf.readFully(data)
                        out.write(adtsHeader(objectType, freqIdx, channels, size + 7))
                        out.write(data)
                        aBytes += 7L + size
                    }
                }
                runCatching {
                    val sb = StringBuilder()
                    sb.append("fps=").append(v.fps).append("\n")
                    sb.append("dur=").append(v.duration).append("\n")
                    for (i in vOffsets.indices) {
                        sb.append("v ").append(vOffsets[i]).append(if (vKeys[i]) " 1" else " 0").append("\n")
                    }
                    for (o in aOffsets) sb.append("a ").append(o).append("\n")
                    java.io.File(videoOut.parentFile, "video.index").writeText(sb.toString())
                }
                audioOk = audioOut.length() > 0
            } else {
                Log.w("VvcExport", "audio track missing or no esds config")
                runCatching {
                    val sb = StringBuilder()
                    sb.append("fps=").append(v.fps).append("\n")
                    sb.append("dur=").append(v.duration).append("\n")
                    for (i in vOffsets.indices) {
                        sb.append("v ").append(vOffsets[i]).append(if (vKeys[i]) " 1" else " 0").append("\n")
                    }
                    java.io.File(videoOut.parentFile, "video.index").writeText(sb.toString())
                }
            }
            Result(true, audioOk, v.fps, v.duration)
        }
    }.getOrElse {
        Log.e("VvcExport", "export failed", it)
        Result(false, false, 0.0, 0.0)
    }

    /** VVC NAL 头 2 字节：type = (b1 >> 3) & 0x1F */
    private fun nalType(nal: ByteArray): Int =
        if (nal.size < 2) -1 else (nal[1].toInt() ushr 3) and 0x1F

    private fun adtsHeader(objectType: Int, freqIdx: Int, channels: Int, frameLen: Int): ByteArray {
        val profile = (objectType - 1).coerceIn(0, 3)
        val ch = channels.coerceIn(0, 7)
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((profile shl 6) or ((freqIdx and 0x0F) shl 2) or ((ch shr 2) and 1)).toByte(),
            (((ch and 3) shl 6) or ((frameLen shr 11) and 3)).toByte(),
            ((frameLen shr 3) and 0xFF).toByte(),
            (((frameLen and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    private fun parseTrack(d: ByteArray, start: Int, end: Int): Track? {
        val mdia = findBox(d, start, end, "mdia") ?: return null
        val minf = findBox(d, mdia.first + 8, mdia.first + mdia.second, "minf") ?: return null
        val stbl = findBox(d, minf.first + 8, minf.first + minf.second, "stbl") ?: return null
        val stsz = findBox(d, stbl.first + 8, stbl.first + stbl.second, "stsz") ?: return null
        val stsc = findBox(d, stbl.first + 8, stbl.first + stbl.second, "stsc") ?: return null
        val stco = findBox(d, stbl.first + 8, stbl.first + stbl.second, "stco")
            ?: findBox(d, stbl.first + 8, stbl.first + stbl.second, "co64") ?: return null
        val uniform = be32(d, stsz.first + 12)
        val count = be32(d, stsz.first + 16)
        if (count <= 0 || count > 300_000) return null
        val sizes = IntArray(count) { i ->
            if (uniform > 0) uniform else be32(d, stsz.first + 20 + i * 4)
        }
        val runs = be32(d, stsc.first + 12)
        val chunkCount = be32(d, stco.first + 12)
        val wide = String(d, stco.first + 4, 4, Charsets.US_ASCII) == "co64"
        val chunkOff = LongArray(chunkCount) { i ->
            val at = stco.first + 16 + i * (if (wide) 8 else 4)
            if (wide) be64(d, at) else be32(d, at).toLong()
        }
        val offsets = LongArray(count)
        var s = 0
        for (r in 0 until runs) {
            val at = stsc.first + 16 + r * 12
            val first = be32(d, at)
            val per = be32(d, at + 4)
            val last = if (r + 1 < runs) be32(d, stsc.first + 16 + (r + 1) * 12) - 1 else chunkCount
            for (c in first - 1 until last) {
                var base = chunkOff.getOrElse(c) { 0L }
                for (k in 0 until per) {
                    if (s >= count) break
                    offsets[s] = base
                    base += sizes[s]
                    s++
                }
            }
        }
        // mdhd: 时间基；stts: 每帧时长累计 → 精确帧率/时长
        var fps = 0.0
        var duration = 0.0
        run {
            val mdhd = findBox(d, mdia.first + 8, mdia.first + mdia.second, "mdhd")
            if (mdhd != null) {
                val ver = d[mdhd.first + 8].toInt()
                val timescale = if (ver == 1) be32(d, mdhd.first + 28) else be32(d, mdhd.first + 20)
                val stts = findBox(d, stbl.first + 8, stbl.first + stbl.second, "stts")
                if (stts != null && timescale > 0) {
                    val entries = be32(d, stts.first + 12)
                    var ticks = 0L
                    var frames = 0L
                    for (i in 0 until minOf(entries, 100_000)) {
                        val at = stts.first + 16 + i * 8
                        val n = be32(d, at)
                        val delta = be32(d, at + 4)
                        ticks += n.toLong() * delta
                        frames += n
                    }
                    if (frames > 0 && ticks > 0) {
                        duration = ticks.toDouble() / timescale
                        fps = frames * timescale.toDouble() / ticks
                    }
                }
            }
        }
        return Track(offsets, sizes, parseAudioConfig(d, stbl.first + 8, stbl.first + stbl.second), fps, duration)
    }

    /** 从 stsd/mp4a/esds 里取 AudioSpecificConfig（AAC 的 2 字节配置）。 */
    private fun parseAudioConfig(d: ByteArray, start: Int, end: Int): ByteArray? {
        val stsd = findBox(d, start, end, "stsd") ?: return null
        // stsd: fullbox(4) + count(4) + 样本描述（mp4a）；esds 在样本描述内部
        var esds: Pair<Int, Int>? = null
        forEachBox(d, stsd.first + 16, stsd.first + stsd.second) { p, sz, t ->
            if (esds == null) {
                esds = findBox(d, p + 8, p + sz, "esds")
                    ?: findBox(d, p + 86, p + sz, "esds")
            }
            if (t == "mp4a") {
                esds = esds ?: findBox(d, p + 8, p + sz, "esds") ?: findBox(d, p + 86, p + sz, "esds")
            }
        }
        if (esds == null) {
            // 兜底：在 stsd 范围内直接找 esds 的 4 字节类型名（enca/wave 等嵌套都能命中）
            var i = stsd.first + 8
            val limit = stsd.first + stsd.second - 8
            while (i < limit) {
                if (d[i] == 'e'.code.toByte() && d[i + 1] == 's'.code.toByte() &&
                    d[i + 2] == 'd'.code.toByte() && d[i + 3] == 's'.code.toByte()
                ) {
                    val sz = be32(d, i - 4)
                    if (sz in 8..(limit - i + 8)) {
                        esds = (i - 4) to sz
                        break
                    }
                }
                i++
            }
        }
        val es = esds ?: return null
        var p = es.first + 12 // fullbox
        // 跳过 ES_Descriptor 的 tag/len
        fun skipLen(q: Int): Int {
            var i = q
            var n = 0
            while (i < d.size) {
                val b = d[i].toInt() and 0xFF
                i++
                if (n == 0) n = b and 0x7F else n = (n shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) break
                if (n > 4) break
            }
            return i
        }
        // 在 esds 区间里找 tag 0x05
        var i = p
        val limit = es.first + es.second
        while (i < limit - 4) {
            val tag = d[i].toInt() and 0xFF
            if (tag == 0x05) {
                val q = skipLen(i + 1)
                val len = (q - (i + 1)).let { _ ->
                    // 重新解析长度
                    var j = i + 1; var n = 0
                    while (j < limit) {
                        val b = d[j].toInt() and 0xFF; j++
                        n = (n shl 7) or (b and 0x7F)
                        if (b and 0x80 == 0) break
                    }
                    n
                }
                // 注意：不要对 ASC 做「字段合理性」过滤 —— 番茄的 HE-AAC 配置
                // 用简单解析会算出 ot=31/ch=0，过滤掉就整集没声音（2.2.143 踩过）
                if (q + len <= limit && len in 2..8) return d.copyOfRange(q, q + len)
            }
            i++
        }
        return null
    }

    private fun be32(d: ByteArray, at: Int): Int =
        ((d[at].toInt() and 0xFF) shl 24) or ((d[at + 1].toInt() and 0xFF) shl 16) or
            ((d[at + 2].toInt() and 0xFF) shl 8) or (d[at + 3].toInt() and 0xFF)

    private fun be64(d: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[at + i].toLong() and 0xFF)
        return v
    }

    private fun findBox(d: ByteArray, start: Int, end: Int, type: String): Pair<Int, Int>? {
        var found: Pair<Int, Int>? = null
        forEachBox(d, start, end) { p, sz, t ->
            if (found == null && t == type) found = p to sz
        }
        return found
    }

    private inline fun forEachBox(d: ByteArray, start: Int, end: Int, cb: (Int, Int, String) -> Unit) {
        var p = start
        val limit = minOf(end, d.size)
        while (p + 8 <= limit) {
            val sz = be32(d, p)
            if (sz < 8 || p + sz > limit) return
            val t = String(d, p + 4, 4, Charsets.US_ASCII)
            cb(p, sz, t)
            p += sz
        }
    }
}
