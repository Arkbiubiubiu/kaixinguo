package com.hongguotv.pad

/** 书架四类动作：收藏 / 点赞 / 追更 / 预约。 */
enum class ShelfKind(val key: String) {
    Favorite("favorite"),
    Like("like"),
    Follow("follow"),
    Reserve("reserve"),
}

/**
 * 书架领域边界：详情/播放器/列表只依赖这里，不再各自记住用 Api 的哪个方法。
 * 内部暂时转调 Api；后续把本地缓存 + 云状态合并搬进来时调用方不用改。
 */
object ShelfRepository {
    fun contains(kind: ShelfKind, seriesId: String): Boolean =
        App.state.contains(kind.key, seriesId)

    fun cloudIds(kind: ShelfKind): Set<String> = when (kind) {
        ShelfKind.Favorite -> AccountApi.shelfIds()
        ShelfKind.Reserve -> AccountApi.reserveIds()
        else -> emptySet()
    }

    /**
     * 切换云端书架状态。[vid] 只有点赞需要（点赞对象是单集 vid，业务 id 是剧）。
     */
    fun toggle(kind: ShelfKind, seriesId: String, on: Boolean, vid: String = ""): Boolean =
        when (kind) {
            ShelfKind.Favorite -> Api.collectSeries(seriesId, on)
            ShelfKind.Like -> if (vid.isNotEmpty()) Api.diggVideo(vid, seriesId, on) else false
            ShelfKind.Follow -> AccountApi.followToggle(seriesId, on)
            ShelfKind.Reserve -> AccountApi.reserveToggle(seriesId, on)
        }
}
