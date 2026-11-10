package com.kaixinguo.pc

import com.hongguotv.pad.Api
import com.hongguotv.pad.Episode
import com.hongguotv.pad.Log
import com.hongguotv.pad.PlayInfo
import com.hongguotv.pad.PlayTier
import com.hongguotv.pad.RemoteVideo
import com.hongguotv.pad.StreamProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 连播预取（设置页「连播预取集数」的真正执行者）。
 *
 * 切集有两段固定等待：
 *   1. 拿直链 —— 签名服务 + playinfo 一次往返（几百毫秒）
 *   2. 下头部 —— CDN 拉 1MB 分片、解密 moov（最慢的一段，网络差时到秒级）
 *
 * 起播后在后台按设置往后预取 N 集：**全部集**把 1、2 都做掉（1MB/集），
 * 只给「下一集」预热头部（多点内存、多一点流量换真正的秒切）。
 *
 * 边界：
 * - 直链有时效，超过 [MAX_AGE_MS] 的条目直接丢掉，宁可再生也不播过期地址
 * - 每个条目只有一个本地代理端口；take 出去即从池里移除，不许两个消费者抢
 * - 离开播放页整批释放（代理关掉、socket 关闭），不给后台留线程
 */
object PlaybackPrefetch {

    /** 一集的预取结果。remote/proxy 可能为空（只预取了直链，没预热头部）。 */
    class Entry(
        val vid: String,
        val height: Int,
        val info: PlayInfo,
        val tiers: List<PlayTier>,
        val remote: RemoteVideo?,
        val proxy: StreamProxy?,
        val at: Long,
    ) {
        fun release() {
            runCatching { proxy?.stop() }
            runCatching { remote?.close() }
        }
    }

    /** 直链有效期保守取 5 分钟：短剧一集也就 2~5 分钟，够用到下一集了。 */
    private const val MAX_AGE_MS = 5 * 60_000L

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    /** 取出一集（取出即出池）。过期条目就地释放并返回 null。 */
    fun take(vid: String): Entry? {
        val e = synchronized(lock) { entries.remove(vid) } ?: return null
        if (System.currentTimeMillis() - e.at > MAX_AGE_MS) {
            e.release()
            return null
        }
        return e
    }

    /** 只保留 [keep] 里的集，其余释放。 */
    fun retain(keep: Set<String>) {
        val dropped = ArrayList<Entry>()
        synchronized(lock) {
            val it = entries.entries.iterator()
            while (it.hasNext()) {
                val (vid, e) = it.next()
                if (vid !in keep) {
                    it.remove()
                    dropped += e
                }
            }
        }
        dropped.forEach { it.release() }
    }

    /** 离开播放页：全部释放。 */
    fun clear() {
        val all = synchronized(lock) { entries.values.toList().also { entries.clear() } }
        all.forEach { it.release() }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun contains(vid: String): Boolean = synchronized(lock) { entries.containsKey(vid) }

    /**
     * 从第 [fromIndex] 集（1-based，含）开始预取 [count] 集。
     *
     * @param prepareHeadFor 哪一集需要连头部一起预热（通常就是下一集）；传 -1 表示都不预热
     */
    suspend fun warm(
        episodes: List<Episode>,
        fromIndex: Int,
        count: Int,
        maxHeight: Int,
        prepareHeadFor: Int = fromIndex,
    ) {
        if (count <= 0 || episodes.isEmpty()) return
        val end = minOf(episodes.size, fromIndex + count - 1)
        if (fromIndex > end) return
        val keep = (fromIndex..end).map { episodes[it - 1].vid }.toSet()
        retain(keep)

        for (i in fromIndex..end) {
            val ep = episodes.getOrNull(i - 1) ?: break
            if (contains(ep.vid)) continue
            val head = i == prepareHeadFor
            val entry = withContext(Dispatchers.IO) { build(ep.vid, maxHeight, head) }
            if (entry == null) {
                Log.d("Prefetch", "第 ${ep.index} 集预取失败（忽略，切集时走正常流程）")
                continue
            }
            synchronized(lock) {
                // 预取期间可能已经被切走/清空了，多出来的就地释放
                if (entries.containsKey(entry.vid)) {
                    entry.release()
                } else {
                    entries[entry.vid] = entry
                    Log.d(
                        "Prefetch",
                        "已预取第 ${ep.index} 集 · ${entry.height}p" +
                            if (head) "（直链 + 头部）" else "（直链）",
                    )
                }
            }
            // 一次只做一个，集与集之间让一下带宽，别和正在播的那一路抢
            if (i < end) delay(250)
        }
    }

    private fun build(vid: String, maxHeight: Int, prepareHead: Boolean): Entry? = runCatching {
        val tiers = Api.tiers(vid)
        val picked = tiers.filter { it.height <= maxHeight }.maxByOrNull { it.height }
            ?: tiers.maxByOrNull { it.height }
        val info = picked?.toPlayInfo() ?: Api.play(vid, 1004, maxHeight)
        if (!prepareHead) {
            return@runCatching Entry(vid, info.height, info, tiers, null, null, System.currentTimeMillis())
        }
        val remote = RemoteVideo(info)
        try {
            // 预取的那一集不预热尾块：省 1MB，别跟正在播的抢带宽
            remote.prepare(warmLastBlock = false)
        } catch (t: Throwable) {
            runCatching { remote.close() }
            throw t
        }
        val proxy = StreamProxy(remote)
        proxy.start()
        Entry(vid, info.height, info, tiers, remote, proxy, System.currentTimeMillis())
    }.getOrNull()
}
