package com.hongguotv.pad

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 刷剧预载：在后台把「后面 5 部」的详情、档位和解密代理头部先准备好。
 *
 * 为什么需要：刷剧是上下换剧，每次换一部都要重新
 * 拉详情 → 拉档位 → 下载 512KB 索引 → 解析样本表，隧道只有 1MB/s 左右，
 * 所以「滑到下一部」总要等一两秒。提前把这几步做完，切换时直接接管 RemoteVideo，
 * 起播几乎无感。
 *
 * 约束：
 * - 一次只预载一部（错峰 700ms），避免和正在播的那一路抢带宽；
 * - 只准备「头部」不预读画面，预载 5 部大约只多占 2~3MB 流量、几 MB 内存；
 * - 解不了的片源（VVC/AV1 私有封装）记进 dead，不再重复尝试。
 */
object FeedPreload {

    class Ready(val seriesId: String, val info: PlayInfo, val remote: RemoteVideo)

    /** 往后预载几部 */
    const val AHEAD = 5

    private val ready = LinkedHashMap<String, Ready>()
    private val dead = HashSet<String>()
    /** 正在后台等转码的剧：避免同一部重复排队 */
    private val warming = HashSet<String>()
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
                ids.forEachIndexed { index, id ->
                    if (!isActive) return@launch
                    if (synchronized(lock) { ready.containsKey(id) || id in dead }) return@forEachIndexed
                    // 后 5 部全部做「完整预载」：档位 + 解密头部，切过去直接接管。
                    // 用户明确要求刷剧换剧无缝，不能只拉详情。
                    runCatching { build(id) }
                    delay(900)
                }
            }
        }
    }

    private fun build(seriesId: String) {
        val d = Api.ensureDetail(seriesId) ?: return
        val ep = d.episodes.firstOrNull() ?: return
        if (Store.isUnsupported(ep.vid)) {
            synchronized(lock) { dead.add(seriesId) }
            return
        }
        val tiers = PlaybackApi.tiers(ep.vid)
        if (tiers.isEmpty()) {
            // 全 VVC 的冷集：服务端已排进转码 job。后台等它转完再把真实流预取好，
            // 不阻塞后面几部的预载；换到它时才能和普通剧一样无缝。
            val job = PlaybackApi.pendingJob(ep.vid)
            if (job != null) {
                val first = synchronized(lock) { warming.add(seriesId) }
                if (first) {
                    scope.launch {
                        val ok = PlaybackApi.awaitTranscode(job, 180_000L)
                        PlaybackApi.clearPendingJob(ep.vid)
                        if (ok) {
                            runCatching {
                                prepare(seriesId, ep, PlaybackApi.tiers(ep.vid))
                            }
                        }
                        synchronized(lock) { warming.remove(seriesId) }
                    }
                }
            }
            return
        }
        prepare(seriesId, ep, tiers)
    }

    /** 档位已经有了：选档 + 下头部/索引，放进 ready 池。 */
    private fun prepare(seriesId: String, ep: Episode, tiers: List<PlayTier>) {
        val cap = Store.maxHeight
        val playable = tiers.filter { it.playable }
        val picked = playable.firstOrNull { it.height == cap }
            ?: playable.firstOrNull { it.height <= cap }
            ?: playable.firstOrNull()
        val info = picked?.toPlayInfo()
            ?: runCatching { PlaybackApi.play(ep.vid, 1004, 0) }.getOrNull()
            ?: return
        val rv = RemoteVideo(info)
        rv.prepare(warmLastBlock = false)
        synchronized(lock) {
            val old = ready.put(seriesId, Ready(seriesId, info, rv))
            old?.let { runCatching { it.remote.close() } }
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
                runCatching { build(seriesId) }
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
            warming.clear()
        }
    }
}
