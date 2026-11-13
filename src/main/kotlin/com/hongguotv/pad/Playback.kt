package com.hongguotv.pad

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 把 CDN 上的加密流实时解成明文 MP4，经本地 127.0.0.1 代理给播放器。
 *
 * 为什么不自己写 ExoPlayer 的 DataSource：视频是 CENC-CTR 加密的，样本表在 moov 里，
 * 处理任意 Seek 的跨样本边界很麻烦。起一路本地代理后，播放器看到的就是普通 MP4，
 * Range 随便跳，播放器一行都不用改。
 */
class RemoteVideo(private val info: PlayInfo) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val label: String get() = "${info.definition} · ${info.height}p"

    var total: Long = 0L
        private set

    private var prepared: MediaCrypto.Prepared? = null

    /** 切集后旧实例要能被叫停：否则后台还在拉数据，跟新流抢带宽。 */
    @Volatile
    private var closed = false

    private val blockLock = Any()
    private val inflight = HashMap<Long, CountDownLatch>()

    fun close() {
        closed = true
        synchronized(blockLock) { cache.clear() }
    }

    /**
     * 下载头部并解出样本表。
     *
     * 这里是起播和切集的固定成本，策略很关键：一开始就要 min(总长, 8MB) 的话，
     * 一部 16MB 的片子会白下 7MB+，电视上就是好几秒的「切集卡住」。
     * 现在 1MB 起、不够再翻倍（正常一次就够），而且首跳正好对齐一个缓存块。
     */
    fun prepare(warmLastBlock: Boolean = true, allowVvc: Boolean = false) {
        // 先按 512KB 取索引：短剧 moov 一般几十~几百 KB，少下一半就少等半秒；
        // 不够再翻倍（最多 8MB），只是多一次请求，不会失败。
        var want = 512 * 1024
        while (true) {
            val (first, known) = fetchHead(want)
            if (first.isEmpty()) error("读取视频头部失败")
            if (known > 0) total = known
            if (total <= 0) total = probeLength()
            val end = runCatching { MediaCrypto.headerEnd(first, total) }.getOrNull()
            if (end != null && end <= first.size) {
                val ready = MediaCrypto.prepare(first.copyOf(end), info.key)
                // bytevc2：码流是字节跳动的私有封装，平台 AV1 解码器也只能建起来、
                // 一帧都出不来（实测：有声音、画面全黑）。这种情况直接抛明确的
                // 「编码不支持」，由播放页提示并跳到下一集，绝不黑屏装死。
                if (!allowVvc && ready.videoCodec.equals("bvc2", true)) {
                    throw UnsupportedCodecException("bytevc2/AV1")
                }
                // AV1（bytevc2）：把首帧里的序列头写进 av1C。只靠容器里的私有 bv2C
                // 配置盒，解码器建得起来但一帧都不出（有声音、画面全黑）。
                if (MediaCrypto.hasAv1(ready.header) && ready.videoSampleAt >= 0L && ready.videoSampleSize > 0) {
                    runCatching {
                        val raw = fetchRange(ready.videoSampleAt, ready.videoSampleSize)
                        if (raw.isNotEmpty()) {
                            val plain = MediaCrypto.decrypt(raw, ready.videoSampleAt, info.key, ready)
                            MediaCrypto.av1ConfigObus(plain)?.let { MediaCrypto.applyAv1Config(ready.header, it) }
                        }
                    }
                }
                prepared = ready
                if (first.size >= BLOCK) {
                    runCatching {
                        val plain = MediaCrypto.decrypt(first.copyOf(BLOCK), 0, info.key, ready)
                        synchronized(blockLock) { cache[0] = plain }
                    }
                }
                // 预取的那一集不用预热尾块：省下 1MB，别跟正在播的那一路抢带宽
                if (warmLastBlock) warmTail()
                return
            }
            if (want >= 8 * 1024 * 1024) error("视频索引过大，无法播放")
            want = minOf(want * 2, 8 * 1024 * 1024)
        }
    }

    /** 拉指定区间（AV1 取首帧序列头用）。 */
    private fun fetchRange(at: Long, len: Int): ByteArray {
        val req = Request.Builder().url(info.url)
            .header("Range", "bytes=$at-${at + len - 1}")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) ByteArray(0) else (resp.body?.bytes() ?: ByteArray(0))
        }
    }

    /** 拉头部并顺手取出文件总长（省掉一次单独的探测往返）。 */
    private fun fetchHead(want: Int): Pair<ByteArray, Long> {
        val req = Request.Builder().url(info.url)
            .header("Range", "bytes=0-${want - 1}")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("视频分片下载失败（HTTP ${resp.code}）")
            val cr = resp.header("Content-Range").orEmpty()
            val t = cr.substringAfter('/', "0").trim().toLongOrNull() ?: 0L
            (resp.body?.bytes() ?: ByteArray(0)) to t
        }
    }

    // 1MB 对齐分块：解密按块做，按块缓存正好命中播放器重叠的请求
    private val cache = object : LinkedHashMap<Long, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > CACHE_BLOCKS
    }

    /** 把解密后的整份 MP4 落盘（VVC 导出用；只在 VVC 剧集走这条路）。 */
    fun saveDecrypted(file: File) {
        if (total <= 0) error("视频长度未知")
        file.parentFile?.mkdirs()
        file.outputStream().buffered(1 shl 20).use { out ->
            var at = 0L
            while (at < total) {
                val want = minOf(1L shl 20, total - at).toInt()
                val chunk = read(at, want)
                if (chunk.isEmpty()) break
                out.write(chunk)
                at += chunk.size
            }
        }
    }

    fun read(at: Long, want: Int): ByteArray {
        if (at >= total) return ByteArray(0)
        val size = minOf(want.toLong(), total - at).toInt()
        if (size <= 0) return ByteArray(0)
        val out = ByteArray(size)
        var copied = 0
        while (copied < size) {
            val pos = at + copied
            val blockStart = pos / BLOCK * BLOCK
            val blk = block(blockStart)
            val offsetInBlock = (pos - blockStart).toInt()
            if (offsetInBlock >= blk.size) break
            val n = minOf(size - copied, blk.size - offsetInBlock)
            System.arraycopy(blk, offsetInBlock, out, copied, n)
            copied += n
        }
        return if (copied == size) out else out.copyOf(copied)
    }

    /**
     * 取一个 1MB 对齐块（LRU 缓存）：同一个块只从远端拉一次。
     *
     * single-flight 很关键：播放器起来时会同时发探测、视频轨、音频轨几路请求，
     * 落点常常是同一块。以前每路各下一遍，一块 1MB 会被下 3~8 次。
     */
    private fun block(blockStart: Long): ByteArray {
        var wait: CountDownLatch? = null
        synchronized(blockLock) {
            cache[blockStart]?.let { return it }
            val pending = inflight[blockStart]
            if (pending != null) {
                wait = pending
            } else {
                inflight[blockStart] = CountDownLatch(1)
            }
        }
        val w = wait
        if (w != null) {
            runCatching { w.await(20, TimeUnit.SECONDS) }
            synchronized(blockLock) { cache[blockStart]?.let { return it } }
            return ByteArray(0)
        }
        try {
            if (closed || blockStart >= total) return ByteArray(0)
            val size = minOf(BLOCK.toLong(), total - blockStart).toInt()
            val raw = runCatching { fetch(blockStart, size) }.getOrDefault(ByteArray(0))
            if (raw.isEmpty()) return raw
            val prep = prepared
            val data = if (prep == null) raw
            else runCatching { MediaCrypto.decrypt(raw, blockStart, info.key, prep) }
                .getOrDefault(raw)
            synchronized(blockLock) { cache[blockStart] = data }
            return data
        } finally {
            synchronized(blockLock) { inflight.remove(blockStart)?.countDown() }
        }
    }

    /**
     * 片尾预热：把最后 1MB 拉进缓存。
     *
     * 短剧文件一般 5~12MB，绝对成本不高，但片尾是**唯一必卡**的点 ——
     * 远端对文件末尾的首次分片请求明显更慢，提前拉好就不用等。
     */
    private fun warmTail() {
        Thread {
            runCatching { Thread.sleep(3000) }
            if (!closed) runCatching {
                val lastBlock = maxOf(0L, (total - 1) / BLOCK * BLOCK)
                block(lastBlock)
            }
        }.apply { isDaemon = true; name = "pad-warmtail" }.start()
    }

    private fun probeLength(): Long = runCatching {
        val req = Request.Builder().url(info.url).header("Range", "bytes=0-0")
            .header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            val cr = resp.header("Content-Range").orEmpty()
            val t = cr.substringAfter('/', "0").trim().toLongOrNull() ?: 0L
            if (t > 0) t else resp.body?.contentLength() ?: 0L
        }
    }.getOrDefault(0L).let { if (it > 0) it else fallbackLength() }

    private fun fallbackLength(): Long = runCatching {
        val req = Request.Builder().url(info.url).head().header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { it.body?.contentLength() ?: 0L }
    }.getOrDefault(0L)

    private fun fetch(at: Long, want: Int): ByteArray {
        if (want <= 0) return ByteArray(0)
        val end = at + want - 1
        val req = Request.Builder().url(info.url)
            .header("Range", "bytes=$at-$end")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("视频分片下载失败（HTTP ${resp.code}）")
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    companion object {
        private const val BLOCK = 1 shl 20

        /** 缓存块数（16MB）：短剧整部 5~12MB，基本等于整片缓存。 */
        private const val CACHE_BLOCKS = 16

        private const val USER_AGENT =
            "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C)"
    }
}

/** 极简 HTTP/1.1 服务，只服务一路视频，支持 Range 与长连接。 */
class StreamProxy(private val remote: RemoteVideo, port: Int = 0) {

    private val server = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))

    // 8 条线程：视频轨/音频轨/探测请求会并发到达
    private val pool = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "pad-stream").apply { isDaemon = true }
    }

    val port: Int get() = server.localPort
    val url: String get() = "http://127.0.0.1:$port/stream"

    fun start() {
        pool.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { serve(socket) }
            }
        }
    }

    fun stop() {
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private fun serve(socket: Socket) {
        runCatching {
            socket.use { client ->
                client.tcpNoDelay = true
                val input = BufferedInputStream(client.getInputStream())
                val output = client.getOutputStream()
                var keepAlive = true
                while (keepAlive) {
                    val requestLine = readLine(input) ?: break
                    if (requestLine.isBlank()) continue
                    var range: String? = null
                    while (true) {
                        val line = readLine(input) ?: return@use
                        if (line.isEmpty()) break
                        val lower = line.lowercase()
                        if (lower.startsWith("range:")) range = line.substringAfter(':').trim()
                        if (lower.startsWith("connection:") && lower.contains("close")) keepAlive = false
                    }
                    val parsed = Regex("bytes=(\\d+)-(\\d*)").find(range.orEmpty())
                    val start = parsed?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val asked = parsed?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
                    // 一次给 8MB，覆盖播放器常见的整段请求；单次解密上限 1MB，内部再切
                    val hardEnd = minOf(start + 8L * 1024 * 1024 - 1, remote.total - 1)
                    val end = if (asked == null) hardEnd else minOf(asked, hardEnd)
                    // 先发响应头，再按 1MB 边解密边推：播放器不用等整段下完
                    val length = end - start + 1
                    val head = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Content-Length: $length\r\n" +
                        "Content-Range: bytes $start-$end/${remote.total}\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n\r\n"
                    output.write(head.toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                    var at = start
                    while (at <= end) {
                        val want = minOf(1024 * 1024L, end - at + 1).toInt()
                        val data = remote.read(at, want)
                        if (data.isEmpty()) {
                            // 短读：Content-Length 已声明完整长度，剩余字节发不出来时必须立刻断开，
                            // 否则播放器会永久等待 body（黑屏/卡死）。断开后播放器可自行重试 Range。
                            Log.e("PadPlayer", "proxy short read at=$at end=$end（已发 ${at - start}B），断开连接")
                            return@use
                        }
                        output.write(data)
                        output.flush()
                        at += data.size
                    }
                }
            }
        }.onFailure { Log.e("PadPlayer", "proxy serve err: ${it.message}") }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val v = input.read()
            if (v < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (v == '\n'.code) return buffer.toString().trimEnd('\r')
            buffer.append(v.toChar())
        }
    }
}
