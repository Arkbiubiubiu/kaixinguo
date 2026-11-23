@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.kaixinguo.pc

import androidx.compose.ui.graphics.toArgb

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hongguotv.pad.ShelfKind
import com.hongguotv.pad.ShelfRepository
import com.hongguotv.pad.Api
import com.hongguotv.pad.App
import com.hongguotv.pad.FeedPage
import com.hongguotv.pad.FilterRow
import com.hongguotv.pad.HistoryApi
import com.hongguotv.pad.HistoryRepository
import com.hongguotv.pad.HistoryShadow
import com.hongguotv.pad.Log
import com.hongguotv.pad.RankTabsel
import com.hongguotv.pad.SeriesCard
import com.hongguotv.pad.SeriesDetail
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun SettingsPage() {
    Box(Modifier.fillMaxSize().verticalScroll(PageMemo.settingsScroll)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                SettingBlock("关于") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            UpdateRow()
                        }
                        Spacer(Modifier.width(24.dp))
                        OfficialSiteCard()
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 外观（旧版 theme-switch）：主题三选
                SettingBlock("外观") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("主题", color = Palette.text, fontSize = 14.sp, modifier = Modifier.width(96.dp))
                        listOf("auto" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (k, label) ->
                            TabChip(label, PcStore.theme == k) {
                                PcStore.theme = k
                                Palette.applyTheme(k)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 播放（旧版 playback 卡）：三开关 + 连播预取 + 清晰度上限
                SettingBlock("播放") {
                    SettingToggle("播完自动下一集", PcStore.autoNextEpisode) { PcStore.autoNextEpisode = it }
                    Spacer(Modifier.height(10.dp))
                    SettingToggle("看完自动删除记录", PcStore.deleteAfterFinish) { PcStore.deleteAfterFinish = it }
                    Spacer(Modifier.height(10.dp))
                    SettingToggle("最小化时自动暂停", PcStore.pauseOnMinimize) { PcStore.pauseOnMinimize = it }
                    Spacer(Modifier.height(14.dp))
                    // 连播预取集数（旧版 issue #6）：0=关闭。
                    // 紧凑步进器（用户反馈整条滑杆太丑）：− 数字 +，行内完成
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "连播预取集数",
                            color = Palette.text, fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        StepBox("−", enabled = PcStore.prefetchEpisodes > 0) {
                            PcStore.prefetchEpisodes = (PcStore.prefetchEpisodes - 1).coerceAtLeast(0)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (PcStore.prefetchEpisodes == 0) "关闭" else "${PcStore.prefetchEpisodes} 集",
                            color = if (PcStore.prefetchEpisodes == 0) Palette.faint else Palette.text,
                            fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(56.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.width(10.dp))
                        StepBox("+", enabled = PcStore.prefetchEpisodes < 10) {
                            PcStore.prefetchEpisodes = (PcStore.prefetchEpisodes + 1).coerceAtMost(10)
                        }
                    }
                    Text("起播后自动向后预接下来几集的分集档案，切集秒开；0 = 关闭。", color = Palette.faint, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("清晰度上限：${PcStore.maxHeight}P", color = Palette.text, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1080, 720, 540).forEach { h ->
                            TabChip("${h}P", PcStore.maxHeight == h) { PcStore.maxHeight = h }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("自动档按这个上限挑清晰度；播放器清晰度菜单里的「自动」同源。", color = Palette.faint, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    // 默认倍速（安卓设置里同一项）：播放器起播用它，播放器里改倍速也会写回这里
                    var rate by remember { mutableStateOf(PcStore.playbackRate) }
                    Text(
                        "默认倍速：${if (rate % 1f == 0f) "${rate.toInt()}x" else "${rate}x"}",
                        color = Palette.text, fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { r ->
                            TabChip(
                                if (r % 1f == 0f) "${r.toInt()}x" else "${r}x",
                                rate == r,
                            ) { rate = r; PcStore.playbackRate = r }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("播放器里改倍速会记住，下次起播继续用。", color = Palette.faint, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                // 磁盘占用（旧版 storage 卡）
                SettingBlock("磁盘占用") {
                    var cacheBump by remember { mutableStateOf(0) }
                    val coversDir = java.io.File(
                        System.getenv("APPDATA") ?: ".",
                        "KaixinguoPC/covers",
                    )
                    val files = remember(cacheBump) { coversDir.listFiles()?.filter { it.isFile }.orEmpty() }
                    val totalMb = remember(cacheBump) { files.sumOf { it.length() } / 1024.0 / 1024.0 }
                    Text("缓存目录", color = Palette.dim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            coversDir.absolutePath,
                            color = Palette.text, fontSize = 12.sp,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                .background(Palette.coverPlaceholder)
                                .border(1.dp, Palette.line, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlineButton("打开目录") { runCatching { java.awt.Desktop.getDesktop().open(coversDir) } }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "封面缓存占用 %.1f MB · %d 个文件".format(totalMb, files.size),
                            color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f),
                        )
                        OutlineButton("清理缓存") {
                            files.forEach { it.delete() }
                            Covers.clearMemory()
                            cacheBump += 1
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("清理后封面会在浏览时自动重新下载。", color = Palette.faint, fontSize = 12.sp)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 设置行开关（旧版 ToggleRow 同语义：左标签右开关）。 */
@Composable
internal fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.width(44.dp).height(24.dp).clip(RoundedCornerShape(50))
                .background(if (checked) Palette.accentFill else Palette.panelHi)
                .clickable { onChange(!checked) }
                .padding(2.dp),
        ) {
            Box(
                Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .size(20.dp).clip(RoundedCornerShape(50)).background(Color.White),
            )
        }
    }
}

@Composable
internal fun SettingBlock(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .border(1.dp, Palette.line, RoundedCornerShape(12.dp))
            .background(Palette.panel).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Palette.text, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** 设置页「关于」右侧的官网入口：二维码扫码打开，地址可点击，旁边可复制链接。 */
@Composable
internal fun OfficialSiteCard() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        officialQrBitmap?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = "官网二维码",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(104.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .padding(6.dp)
                    .clickable { openOfficialSite() },
            )
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text("官方网站", color = Palette.text, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "kxg.fabao.xin",
                color = Palette.accent,
                fontSize = 13.sp,
                modifier = Modifier.clickable { openOfficialSite() },
            )
            Spacer(Modifier.height(4.dp))
            Text("扫码或点击打开，方便分享", color = Palette.faint, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            OutlineButton("复制链接") {
                runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(java.awt.datatransfer.StringSelection(OFFICIAL_SITE), null)
                }
            }
        }
    }
}

internal fun openOfficialSite() {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(OFFICIAL_SITE)) }
}

// ---------------------------------------------------------------- 通用

/** 服务配置的一行输入（标签 + 输入框 + 占位提示）。 */
@Composable
internal fun ServiceField(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.text, fontSize = 13.sp, modifier = Modifier.width(80.dp))
        Box(
            Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp))
                .background(Palette.panelHi)
                .border(1.dp, Palette.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(hint, color = Palette.faint, fontSize = 12.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(color = Palette.text, fontSize = 12.sp),
                cursorBrush = SolidColor(Palette.accent),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 小步进按钮（设置页 −/+）：28px 方块，禁用置灰。 */
