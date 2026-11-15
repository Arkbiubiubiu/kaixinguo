package com.hongguotv.pad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 播放页的纯 UI 状态：控制条/浮层/提示/评论面板。
 * 不包含播放器、源、历史等业务状态；后续 PlaybackSession 只引用这里，不反向依赖 UI。
 */
class PlayerUiState {
    var controlsVisible by mutableStateOf(false)
    var menu by mutableStateOf(PlayerMenu.None)
    var lastActionAt by mutableStateOf(0L)
    var editing by mutableStateOf(false)
    var hint by mutableStateOf<String?>(null)
    var commentsOpen by mutableStateOf(false)
}
