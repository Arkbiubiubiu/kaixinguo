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
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * 从 cards 的第 from 个开始，向后预载 AHEAD 部。
     * 窗口外的已预载项会被关掉（它们的 RemoteVideo 占着内存）。
     */
    fun request(cards: List<SeriesCard>, from: Int) {
        val ids = cards.drop(from).take(AHEAD).map { it.seriesId }
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
                for (id in ids) {
                    if (!isActive) return@launch
                    if (synchronized(lock) { ready.containsKey(id) || id in dead }) continue
                    runCatching { build(id) }
                    // 每部之间留 900ms：预载不能把正在播的这一路挤卡
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
        val tiers = Api.tiers(ep.vid)
        val cap = Store.maxHeight
        val playable = tiers.filter { it.playable }
        val picked = playable.firstOrNull { it.height == cap }
            ?: playable.firstOrNull { it.height <= cap }
            ?: playable.firstOrNull()
        val info = picked?.toPlayInfo()
            ?: runCatching { Api.play(ep.vid, 1004, 0) }.getOrNull()
            ?: return
        val rv = RemoteVideo(info)
        rv.prepare(warmLastBlock = false)
        synchronized(lock) {
            val old = ready.put(seriesId, Ready(seriesId, info, rv))
            old?.let { runCatching { it.remote.close() } }
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
