package com.hongguotv.pad

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 刷剧预载：在后台把「后面 8 部」的详情、档位和解密代理头部先准备好。
 *
 * 为什么需要：刷剧是上下换剧，每次换一部都要重新
 * 拉详情 → 拉档位 → 下载 512KB 索引 → 解析样本表，隧道只有 1MB/s 左右，
 * 所以「滑到下一部」总要等一两秒。提前把这几步做完，切换时直接接管 RemoteVideo，
 * 起播几乎无感。
 *
 * 约束：
 * - 最多 2 部并发预载（漫剧走 CDN 直连、带宽大，双路能把带宽吃得更满），仍避免饿死正在播的那一路；
 * - 只准备「头部」不预读画面，预载 8 部大约只多占 3~5MB 流量、几 MB 内存；
 * - 解不了的片源（VVC/AV1 私有封装）记进 dead，不再重复尝试。
 */
object FeedPreload {

    class Ready(val seriesId: String, val info: PlayInfo, val remote: RemoteVideo)

    /** 往后预载几部 */
    const val AHEAD = 8
    /** 其中前几部做「完整预载」（档位 + 解密头部），其余只拉详情 */
    const val FULL_COUNT = 4
    /** 预载并发上限（≤2：漫剧走 CDN 直连带宽大，双路能更吃满；又不至于饿死正在播的那一路） */
    private const val PRELOAD_CONCURRENCY = 2

    private val ready = LinkedHashMap<String, Ready>()
    private val dead = HashSet<String>()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * 从 cards 的第 from 个开始，向后预载 AHEAD 部。
     * 窗口外的已预载项会被关掉（它们的 RemoteVideo 占着内存）。
     */
    fun request(cards: List<SeriesCard>, from: Int) {
        // 窗口要包含「当前这一部」：它可能已经被启动预热好了，不能被窗口清理关掉
        val keepCurrent = cards.getOrNull(from - 1)?.seriesId?.let { listOf(it) } ?: emptyList()
        val ids = keepCurrent + cards.drop(from).take(AHEAD).map { it.seriesId }
        synchronized(lock) {
            val it = ready.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key !in ids) {
                    runCatching { e.value.remote.close() }
                    it.remove()
                }
            }
            job?.cancel()
            job = scope.launch {
                // 先等起播那一波过去（首屏、档位、索引），再开始预载
                delay(1_500)
                // 有限并发（≤ PRELOAD_CONCURRENCY=2）：每批 2 部同时预载，批内并行、批间串行。
                // 漫剧走 CDN 直连、带宽大，双路能把带宽吃得更满；仍不超 2 路，别饿死正在播的那一路。
                val indexed = ids.withIndex().toList()
                var i = 0
                while (i < indexed.size) {
                    if (!isActive) return@launch
                    val batch = indexed.subList(i, minOf(i + PRELOAD_CONCURRENCY, indexed.size))
                    batch.map { (index, id) ->
                        async {
                            if (!isActive) return@async
                            if (synchronized(lock) { ready.containsKey(id) || id in dead }) return@async
                            // 只给最近 FULL_COUNT 部做「完整预载」（档位 + 解密头部）；其余只拉详情。
                            runCatching { build(id, full = index < FULL_COUNT) }
                        }
                    }.forEach { it.await() }
                    i += PRELOAD_CONCURRENCY
                }
            }
        }
    }

    private suspend fun build(seriesId: String, full: Boolean = true) {
        // 低优先级预取：全局并发≤2 + 失败退避 + 同 series 去重（失败静默）
        PrefetchGuard.prefetch("preload:$seriesId") {
            val d = Api.ensureDetail(seriesId) ?: return@prefetch null
            if (!full) return@prefetch true   // 详情已进 DetailCache，切过去时只剩档位+索引
            val ep = d.episodes.firstOrNull() ?: return@prefetch null
            if (Store.isUnsupported(ep.vid)) {
                synchronized(lock) { dead.add(seriesId) }
                return@prefetch null
            }
            val tiers = PlaybackApi.tiers(ep.vid)
            val cap = Store.maxHeight
            val playable = tiers.filter { it.playable }
            val picked = playable.firstOrNull { it.height == cap }
                ?: playable.firstOrNull { it.height <= cap }
                ?: playable.firstOrNull()
            val info = picked?.toPlayInfo()
                ?: runCatching { PlaybackApi.play(ep.vid, 1004, 0) }.getOrNull()
                ?: return@prefetch null
            val rv = RemoteVideo(info)
            rv.prepare(warmLastBlock = false)
            synchronized(lock) {
                val old = ready.put(seriesId, Ready(seriesId, info, rv))
                old?.let { runCatching { it.remote.close() } }
            }
            true
        }
    }

    /**
     * 启动时预热：App 一启动就把刷剧列表第一部准备好（详情+档位+索引），
     * 用户点「刷剧」时不用再等整条链路。
     */
    fun warm(seriesId: String) {
        synchronized(lock) {
            if (ready.containsKey(seriesId) || seriesId in dead) return
            job?.cancel()
            job = scope.launch {
                delay(1_200)
                runCatching { build(seriesId, full = true) }
            }
        }
    }

    /** 切剧时取走预载；没有命中返回 null（调用方走完整流程）。 */
    fun take(seriesId: String): Ready? = synchronized(lock) { ready.remove(seriesId) }

    /** 离开刷剧/切分类时调用：关掉所有预载连接，清空状态。 */
    fun clear() {
        synchronized(lock) {
            job?.cancel()
            job = null
            ready.values.forEach { runCatching { it.remote.close() } }
            ready.clear()
            dead.clear()
        }
    }
}
