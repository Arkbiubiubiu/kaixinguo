package com.hongguotv.pad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 当前这一集的播放会话状态：档位、清晰度、时长、准备态、错误/重试、切集态。
 *
 * 不持有 ExoPlayer/ffmpeg 实例（那个跟着平台解码器走），只描述“这一集现在是什么状态”。
 * PlayerScreen/Player 只更新这里，控制条和历史/预取通过它读状态。
 */
class PlaybackSession {
    var preparedIndex by mutableStateOf(-1)
    var preparedVid by mutableStateOf("")
    var tiers by mutableStateOf<List<PlayTier>>(emptyList())
    var curDef by mutableStateOf(0)
    var duration by mutableStateOf(0L)
    var preparing by mutableStateOf(true)
    var showSpinner by mutableStateOf(true)
    var failed by mutableStateOf<String?>(null)
    var attempt by mutableStateOf(0)
    var switching by mutableStateOf(false)
}
