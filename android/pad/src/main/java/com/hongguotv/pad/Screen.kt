package com.hongguotv.pad

/** 页面路由。与原工程的 sealed class 一一对应（15 个页面）。 */
sealed class Screen {
    object Home : Screen()
    object Find : Screen()
    object Rank : Screen()
    object NewDrama : Screen()
    object History : Screen()
    object Favorite : Screen()
    object Like : Screen()
    object Follow : Screen()
    object Reserve : Screen()
    object Settings : Screen()
    object Login : Screen()
    object Account : Screen()
    data class Detail(val seriesId: String) : Screen()
    /**
     * [startMs] 只在第一集起播时用一次：从历史点进来时带着云端「这一集看到第几秒」。
     * 0 = 没有（交给本地播放档案续播）。
     */
    data class Play(val seriesId: String, val index: Int = 1, val startMs: Long = 0L) : Screen()
    data class Creator(val userId: String) : Screen()
}
