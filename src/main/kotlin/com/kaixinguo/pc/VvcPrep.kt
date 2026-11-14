package com.kaixinguo.pc

import com.hongguotv.pad.Log
import com.hongguotv.pad.PlayInfo
import com.hongguotv.pad.RemoteVideo
import java.io.File
import java.io.RandomAccessFile

/**
 * VVC（bytevc2）本机转换：解开 CENC 后把 MP4 拆成
 *   - 视频：4 字节长度前缀 → 00 00 00 01 Annex-B（.266）
 *   - 音频：AAC 原始帧 → ADTS（.aac）
 * 然后交给 PC 自带的 ffmpeg（原生 vvc 解码器）播放。
 *
 * 只做容器拆包，不转码；缓存按 vid 存，第二次进同一集直接起播。
 */
object VvcPrep {

    data class Result(
        /** 干净的 vvc1 MP4：PC 播放器（ffmpeg）直接当普通片源播，拖动/续播都正常 */
        val playable: File,
        val durationMs: Long,
        val fps: Double,
    )

    private fun baseDir(): File {
        val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
        return File(File(appData, "KaixinguoPC"), "vvc")
    }

    /** 只保留 keep 里的缓存目录（当前集 + 预取的下一集），其余删掉。 */
    fun pruneExcept(keep: Set<String>) {
        runCatching {
            baseDir().listFiles()?.forEach { d -> if (d.name !in keep) d.deleteRecursively() }
        }
    }

    /** 自动清理：超过 7 天没碰过的 VVC 缓存整目录删掉（切集时的 prune 只保当前/下一集）。 */
    fun autoClean(maxAgeDays: Int = 7) {
        runCatching {
            val cutoff = System.currentTimeMillis() - maxAgeDays * 86_400_000L
            baseDir().listFiles()?.forEach { d ->
                val newest = d.listFiles()?.maxOfOrNull { it.lastModified() } ?: d.lastModified()
                if (newest < cutoff) d.deleteRecursively()
            }
        }
    }

    /** 已经转换好的 clean.mp4（没有则 null）。预取和起播共用同一个判断。 */
    fun cached(vid: String): File? {
        val dir = File(baseDir(), vid)
        val clean = File(dir, "clean.mp4")
        val marker = File(dir, "v3.ok")
        return clean.takeIf { marker.exists() && it.exists() && it.length() > 0 }
    }

    fun prepare(vid: String, info: PlayInfo): Result {
        val root = baseDir().apply { mkdirs() }
        val dir = File(root, vid).apply { mkdirs() }
        val video = File(dir, "video.266")
        val audio = File(dir, "audio.aac")
        val clean = File(dir, "clean.mp4")
        val meta = File(dir, "meta.txt")
        // 缓存版本：v3 = 修了 esds 误读（码率里的 0x05）和 HE-AACv2 ADTS profile；旧缓存可能无声，必须重建
        val marker = File(dir, "v3.ok")

        var durationMs = 0L
        var fps = 0.0
        if (marker.exists() && video.exists() && video.length() > 0 && meta.exists()) {
            runCatching {
                val parts = meta.readText().trim().split(",")
                durationMs = parts[0].toLong()
                fps = parts.getOrNull(1)?.toDouble() ?: 0.0
            }
        }
        if (!(marker.exists() && video.exists() && video.length() > 0)) {
            val rv = RemoteVideo(info)
            rv.prepare(warmLastBlock = false, allowVvc = true)
            val mp4 = File(dir, "source.mp4")
            rv.saveDecrypted(mp4)
            val res = export(mp4, video, audio)
            // 容器解析偶尔认不出 esds（日志 audio=NNN 但 cfg=null）：这时别直接放弃音轨，
            // 让 ffmpeg 从解密后的源 MP4 里再抽一次 AAC。之前这种情况会转出一集无声片。
            if (!(audio.exists() && audio.length() > 0)) {
                extractAudio(mp4, audio)
            }
            runCatching { mp4.delete() }
            if (!res.first) error("VVC 转换失败")
            durationMs = res.second
            fps = res.third
            runCatching {
                meta.writeText("$durationMs,$fps")
                marker.writeText("ok")
            }
        }
        if (!(clean.exists() && clean.length() > 0)) {
            remux(video, audio.takeIf { it.exists() && it.length() > 0 }, clean, fps, durationMs)
        }
        return Result(clean, durationMs, fps)
    }

    /** 兜底：直接从解密后的 MP4 抽 AAC 成 ADTS；抽不出来就保持无声，不阻塞视频。 */
    private fun extractAudio(src: File, out: File) {
        val exe = Ffmpeg.binary() ?: return
        val cmd = listOf(
            exe.absolutePath, "-hide_banner", "-loglevel", "error", "-y",
            "-i", src.absolutePath, "-vn", "-c:a", "copy", "-f", "adts", out.absolutePath,
        )
        Log.d("VvcPrep", "audio parse miss, ffmpeg extract: ${cmd.joinToString(" ")}")
        runCatching {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
            if (ok && out.exists() && out.length() > 0) {
                Log.d("VvcPrep", "audio extract ok ${out.length()}B")
            } else {
                Log.e("VvcPrep", "audio extract failed: $text")
                runCatching { out.delete() }
            }
        }
    }

    /**
     * 把裸流封回标准 MP4（vvc1 采样条目）：ffmpeg 才有索引可拖动，
     * 播放器也能直接当普通文件播。纯 -c copy，不重新编码。
     */
    private fun remux(video: File, audio: File?, out: File, fps: Double, durationMs: Long) {
        val exe = Ffmpeg.binary() ?: error("缺少 ffmpeg")
        val rate = if (fps > 1.0) fps else 25.0
        val cmd = ArrayList<String>()
        cmd += listOf(exe.absolutePath, "-hide_banner", "-loglevel", "error", "-y")
        cmd += listOf("-r", String.format(java.util.Locale.US, "%.3f", rate), "-i", video.absolutePath)
        if (audio != null) cmd += listOf("-i", audio.absolutePath)
        cmd += listOf("-c", "copy", "-movflags", "+faststart", out.absolutePath)
        Log.d("VvcPrep", "remux: ${cmd.joinToString(" ")}")
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")
        val ok = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
        if (!ok || !out.exists() || out.length() == 0L) {
            Log.e("VvcPrep", "remux failed: $text")
            error("VVC 封装失败")
        }
        Log.d("VvcPrep", "remux ok ${out.length()}B 时长 ${durationMs}ms ${rate}fps")
    }

    private class Track(
        val offsets: LongArray,
        val sizes: IntArray,
        val audioConfig: ByteArray?,
        val fps: Double = 0.0,
        val duration: Double = 0.0,
    )

    /** @return Triple(成功, 时长ms, fps) */
    private fun export(mp4: File, videoOut: File, audioOut: File): Triple<Boolean, Long, Double> = runCatching {
        RandomAccessFile(mp4, "r").use { raf ->
            val len = raf.length().toInt()
            val head = ByteArray(minOf(len, 4 * 1024 * 1024))
            raf.seek(0)
            raf.readFully(head)
            val moov = findBox(head, 0, head.size, "moov")
            if (moov == null) {
                Log.e("VvcPrep", "moov not found in first ${head.size} bytes")
                return Triple(false, 0L, 0.0)
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
            Log.d("VvcPrep", "tracks: video=${video?.sizes?.size} audio=${audio?.sizes?.size} cfg=${audio?.audioConfig?.size}")
            val v = video ?: return Triple(false, 0L, 0.0)
            var written = 0
            videoOut.outputStream().buffered(1 shl 20).use { out ->
                for (i in v.sizes.indices) {
                    val size = v.sizes[i]
                    if (size <= 0 || size > 8 shl 20) continue
                    raf.seek(v.offsets[i])
                    val data = ByteArray(size)
                    raf.readFully(data)
                    var p = 0
                    while (p + 4 <= data.size) {
                        val n = be32(data, p)
                        if (n <= 0 || p + 4 + n > data.size) break
                        out.write(byteArrayOf(0, 0, 0, 1))
                        out.write(data, p + 4, n)
                        written++
                        p += 4 + n
                    }
                }
            }
            if (written == 0) {
                Log.e("VvcPrep", "视频轨没有解出任何 NAL（解密 key 不对？）")
                return Triple(false, 0L, 0.0)
            }
            var audioOk = false
            val a = audio
            if (a != null && a.audioConfig != null && a.audioConfig.size >= 2) {
                val cfg = a.audioConfig
                val objectType = (cfg[0].toInt() ushr 3) and 0x1F
                val freqIdx = ((cfg[0].toInt() and 0x07) shl 1) or ((cfg[1].toInt() ushr 7) and 0x01)
                val channels = (cfg[1].toInt() ushr 3) and 0x0F
                audioOut.outputStream().buffered(1 shl 18).use { out ->
                    for (i in a.sizes.indices) {
                        val size = a.sizes[i]
                        if (size <= 0) continue
                        raf.seek(a.offsets[i])
                        val data = ByteArray(size)
                        raf.readFully(data)
                        out.write(adtsHeader(objectType, freqIdx, channels, size + 7))
                        out.write(data)
                    }
                }
                audioOk = audioOut.length() > 0
            } else {
                Log.e("VvcPrep", "audio track missing or no esds config")
            }
            Triple(true, (v.duration * 1000).toLong(), v.fps)
        }
    }.getOrElse {
        Log.e("VvcPrep", "export failed: ${it.message}", it)
        Triple(false, 0L, 0.0)
    }

    private fun adtsHeader(objectType: Int, freqIdx: Int, channels: Int, frameLen: Int): ByteArray {
        // ADTS 只有 Main/LC/SSR/LTP 四种 profile；HE-AAC(5)/HE-AACv2(29) 的核心是 LC，
        // 必须写 1。原来按 objectType-1 算成 3（LTP），部分解码器直接不出声。
        val profile = when (objectType) {
            1 -> 0
            2 -> 1
            3 -> 2
            4 -> 3
            else -> 1
        }
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

    /**
     * 从 stsd/mp4a/esds 里取 AudioSpecificConfig。
     *
     * 必须按描述符树走，不能在整段 esds 里搜 0x05：码率字段也可能出现 0x05
     * （例如 avgBitrate=0xFA05），扫到就会把后面的字节当成 AAC 配置，
     * 生成的 ADTS 采样率/声道全错，mpv 能挂上音轨却解不出声音 → 只有画面。
     */
    private fun parseAudioConfig(d: ByteArray, start: Int, end: Int): ByteArray? {
        val stsd = findBox(d, start, end, "stsd") ?: return null
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
        val limit = es.first + es.second

        fun readLen(q0: Int): Pair<Int, Int> {
            var q = q0
            var n = 0
            var count = 0
            while (q < limit && count < 4) {
                val b = d[q].toInt() and 0xFF
                q++
                count++
                n = (n shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) break
            }
            return n to q
        }

        // ES_Descriptor -> DecoderConfigDescriptor -> DecoderSpecificInfo
        var p = es.first + 12 // box header 8 + fullbox 4
        if (p < limit && (d[p].toInt() and 0xFF) == 0x03) {
            p++
            val (_, q) = readLen(p)
            p = q
            if (p + 3 <= limit) {
                p += 2 // ES_ID
                val flags = d[p].toInt() and 0xFF
                p++
                if (flags and 0x80 != 0) p += 2
                if (flags and 0x40 != 0 && p < limit) {
                    val n = d[p].toInt() and 0xFF
                    p += 1 + n
                }
                if (flags and 0x20 != 0) p += 2
            }
            if (p < limit && (d[p].toInt() and 0xFF) == 0x04) {
                p++
                val (_, q) = readLen(p)
                p = q
                p += 13 // objectTypeIndication + streamType + bufferSize + max/avg bitrate
                if (p < limit && (d[p].toInt() and 0xFF) == 0x05) {
                    p++
                    val (len, dataAt) = readLen(p)
                    if (len in 2..8 && dataAt + len <= limit) {
                        return d.copyOfRange(dataAt, dataAt + len)
                    }
                }
            }
        }

        // 结构不标准时退回扫描，但要求 ASC 字段像样，避免再被码率里的 0x05 骗到
        var i = es.first + 12
        while (i < limit - 4) {
            if ((d[i].toInt() and 0xFF) == 0x05) {
                val (len, dataAt) = readLen(i + 1)
                if (len in 2..8 && dataAt + len <= limit) {
                    val cfg = d.copyOfRange(dataAt, dataAt + len)
                    val aot = (cfg[0].toInt() ushr 3) and 0x1F
                    val freq = ((cfg[0].toInt() and 7) shl 1) or ((cfg[1].toInt() ushr 7) and 1)
                    val ch = (cfg[1].toInt() ushr 3) and 0x0F
                    if (freq in 0..12 && ch in 0..7 && (aot in 1..5 || aot == 29 || aot >= 17)) {
                        return cfg
                    }
                }
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
