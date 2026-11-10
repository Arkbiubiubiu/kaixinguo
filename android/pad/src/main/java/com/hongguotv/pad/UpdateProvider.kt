package com.hongguotv.pad

import androidx.core.content.FileProvider

/**
 * 安装包升级用的 FileProvider。
 *
 * 必须继承 androidx 的 FileProvider（不能只实现 ContentProvider 空壳）：
 * Android 7+ 不允许把 `file://` 交给系统安装器，只能给 content:// 并临时授权，
 * 而 content:// 的映射表就是下面的 res/xml/file_paths.xml。
 */
class UpdateProvider : FileProvider()
