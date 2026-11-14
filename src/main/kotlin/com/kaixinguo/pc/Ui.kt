@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.kaixinguo.pc

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hongguotv.pad.SeriesCard
import com.hongguotv.pad.SeriesDetail
import androidx.compose.ui.draw.alpha

/** 桌面端颜色与字号。数值直接取自旧 PC 版 design tokens。 */
object Palette {
    /** 亮/暗主题开关（旧版 theme store：auto 跟随系统）。所有取色走 get()，
     *  Compose 读 state 会自然重组，不需要重启窗口。 */
    val isDark = androidx.compose.runtime.mutableStateOf(true)

    private fun c(dark: Long, light: Long): Color =
        Color(if (isDark.value) dark else light)

    val bg get() = c(0xFF1C181E, 0xFFF6F5F7)
    val panel get() = c(0xFF252027, 0xFFFFFFFF)
    val panelHi get() = c(0xFF322A32, 0xFFEDEAEF)
    val line get() = if (isDark.value) Color(0x14FFFFFF) else Color(0x14000000)
    val text get() = c(0xFFF2F2F5, 0xFF151216)
    val dim get() = c(0xFF9A9AA2, 0xFF6B6770)
    val faint get() = c(0xFF6E6E77, 0xFF9A96A0)
    val accent get() = Color(0xFFFF4D4F)
    /** 实心按钮/选中块（旧版 primary 就是亮红） */
    val accentFill get() = Color(0xFFFF4D4F)
    val accentHover get() = Color(0xFFFF6B6D)
    val star get() = c(0xFFFFC53D, 0xFFE0A200)
    val coverPlaceholder get() = c(0xFF191417, 0xFFE7E4EA)
    /** 热度暖橙（签名细节：火焰图标 + 暖橙数字，全 app 唯一温度符号） */
    val heat get() = c(0xFFFFB26B, 0xFFD97A26)
    /** 白色系半透明（控制层/压暗层统一从这里取，禁止散落硬编码） */
    val whiteHover get() = Color(0x33FFFFFF)
    val whiteBorder get() = Color(0x4DFFFFFF)
    val whiteFill get() = Color(0x26FFFFFF)
    /** 黑色压暗层 */
    val scrimLight get() = Color(0x4D000000)
    val scrimMed get() = Color(0x99000000)
    val scrimStrong get() = Color(0xCC000000)
    val scrimFull get() = Color(0xE6000000)

    /** 启动时套用存下来的主题（auto 跟随系统外观） */
    fun applyTheme(mode: String) {
        val sysDark = runCatching {
            val f = java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>().firstOrNull()
            f == null || !java.awt.Desktop.isDesktopSupported() ||
                true // Compose Desktop 无系统外观 API，auto 按深色（旧版默认）
        }.getOrDefault(true)
        isDark.value = when (mode) {
            "light" -> false
            "dark" -> true
            else -> sysDark
        }
    }
}

/** 圆角系统：控件 6-8，卡片 12，大面板 16，chip 8（替代满屏 pill）。 */
object Radius {
    val xs = RoundedCornerShape(4.dp)
    val sm = RoundedCornerShape(6.dp)
    val ctrl = RoundedCornerShape(8.dp)
    val chip = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(12.dp)
    val panel = RoundedCornerShape(16.dp)
}

/**
 * 热度签名组件：线形火焰图标 + 暖橙数字（tabular）。
 * 全 app 唯一「温度符号」——其余数据一律灰调，保证热度的视觉独占。
 * @param big Hero 用大号（16sp 数字 / 16dp 图标），默认行内 12sp
 */
@Composable
fun HeatBadge(hot: String, big: Boolean = false, prefix: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Whatshot,
            contentDescription = null,
            tint = Palette.heat,
            modifier = Modifier.size(if (big) 16.dp else 12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            prefix + com.hongguotv.pad.formatCount(hot),
            color = Palette.heat,
            fontSize = if (big) 16.sp else 12.sp,
            fontWeight = if (big) FontWeight.SemiBold else FontWeight.Medium,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

private val HoverEase = tween<Float>(durationMillis = 150)

private val NAV = listOf(
    // 火焰只属于「热度」这一个签名符号，导航不再抢它
    "推荐" to Icons.Filled.Home,
    "刷剧" to Icons.Filled.Movie,
    "找剧" to Icons.Filled.Explore,
    "排行榜" to Icons.Filled.EmojiEvents,
    "新剧" to Icons.Filled.NewReleases,
    "历史" to Icons.Filled.History,
    "追更" to Icons.Filled.Tv,
    "预约" to Icons.Filled.NotificationsActive,
    "我的" to Icons.Filled.Person,
)

/** 追更空态图标（旧版 EmptyMedia 用 Tv 形）。 */
val FollowsEmptyIcon = Icons.Filled.Tv

@Composable
fun Sidebar(selected: String, collapsed: Boolean, onSelect: (String) -> Unit, onToggle: () -> Unit, badge: Int = 0) {
    Column(
        Modifier.width(72.dp).fillMaxHeight().background(Palette.bg).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NAV.forEach { (label, icon) ->
            // 追更未读角标（旧版：bg-destructive 圆形数字，>99 显示 99+）
            NavItem(label, icon, selected == label, if (label == "追更") badge else 0) { onSelect(label) }
        }
        Spacer(Modifier.weight(1f))
        // 打赏作者：纯自愿入口，点开浮层看赞赏码（Donate.kt）
        NavItem("打赏", Icons.Filled.LocalCafe, false, 0) { onSelect("打赏") }
        NavItem("设置", Icons.Filled.Settings, selected == "设置", 0) { onSelect("设置") }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, active: Boolean, badge: Int = 0, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    // 选中态从「整块亮红」收成「抬一档的卡面 + 红图标」，红只留一条 2dp 指示条。
    // 侧栏在每个页面都在，整块红会让全站一直有个红色大按钮抢焦点。
    Box(Modifier.fillMaxWidth()) {
        // 不再画最左侧的 2dp 红条：它会把「按钮」的视觉重心拉到左边，
        // 看起来图标不在侧栏中间；选中态只靠居中的图标底 + 红图标表达
        Column(
            Modifier
                .fillMaxWidth()
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .clickable { onClick() }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 选中/悬停只给图标一个 40dp 的圆角小底，不再整行铺一条浅色长方形
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(
                    when {
                        active -> Palette.panelHi
                        hovered -> Palette.panelHi.copy(alpha = 0.5f)
                        else -> Color.Transparent
                    },
                ),
                contentAlignment = Alignment.Center,
            ) {
                Box {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = when {
                        active -> Palette.accent
                        hovered -> Palette.text
                        else -> Palette.dim
                    },
                    modifier = Modifier.size(20.dp),
                )
                if (badge > 0) {
                    Text(
                        if (badge > 99) "99+" else "$badge",
                        color = Color.White,
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 14.dp, y = (-5).dp)
                            .clip(RoundedCornerShape(50))
                            .background(Palette.accentFill)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                color = if (active) Palette.text else Palette.dim,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

@Composable
fun TopBar(
    title: String,
    tabs: List<String> = emptyList(),
    currentTab: Int = 0,
    search: String? = null,
    onSearch: ((String) -> Unit)? = null,
    onTab: (Int) -> Unit = {},
    onRefresh: (() -> Unit)? = null,
    refreshLabel: String? = null,
    searchHint: String = "搜索剧名 / 关键词",
) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (title.isNotBlank()) {
            Text(title, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(20.dp))
        }
        tabs.forEachIndexed { index, label ->
            TabChip(label, index == currentTab) { onTab(index) }
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.weight(1f))
        if (search != null && onSearch != null) {
            SearchField(search, onSearch, searchHint)
            Spacer(Modifier.width(10.dp))
        }
        if (onRefresh != null) {
            if (refreshLabel != null) {
                TextAction(refreshLabel, onRefresh)
            } else {
                IconButton(Icons.Filled.Refresh, onRefresh)
            }
        }
    }
}

/** 页头右上角的文字动作（旧版 ghost 按钮：图标 + 灰字 + hover 抬一档）。 */
@Composable
fun HeaderAction(text: String, onClick: () -> Unit, icon: ImageVector? = Icons.Filled.Refresh) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier.height(32.dp).clip(Radius.chip)
            .background(if (hovered) Palette.panelHi else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Palette.dim, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Palette.dim, fontSize = 14.sp)
    }
}

/** 列表页右上角的搜索框（旧版 ListSearch：w-60，高 32，放大镜内嵌）。 */
@Composable
fun ListSearchBox(value: String, onChange: (String) -> Unit, hint: String) {
    Row(
        Modifier.width(240.dp).height(32.dp).clip(RoundedCornerShape(8.dp))
            .background(Palette.coverPlaceholder).border(1.dp, Palette.line, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.faint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, color = Palette.faint, fontSize = 12.sp, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(color = Palette.text, fontSize = 12.sp),
                cursorBrush = SolidColor(Palette.accent),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 一键清空：以前只能一个字一个字退
        if (value.isNotEmpty()) {
            Text(
                "✕",
                color = Palette.faint,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onChange("") }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** 描边小按钮（旧版 outline sm：高 32、圆角 8、细边框）。 */
@Composable
fun OutlineButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier.height(32.dp).clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (hovered) Color(0x4DFFFFFF) else Palette.line, RoundedCornerShape(8.dp))
            .background(if (hovered) Palette.panelHi else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Palette.text, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Text(
        text,
        color = if (hovered) Palette.text else Palette.dim,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(Radius.chip)
            .background(if (hovered) Palette.panelHi else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
fun TabChip(label: String, active: Boolean, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    // 选中 = 红色薄底 + 红字 + 细红边；实心红留给「动作」（播放/搜索/登录），
    // 页面里一屏能有十几个 tab，整块实心红会把主按钮压掉。
    val bg = when {
        active -> Palette.accent.copy(alpha = 0.16f)
        hovered -> Palette.panelHi
        else -> Palette.panel
    }
    Row(
        Modifier
            .clip(Radius.chip)
            .background(bg)
            .border(
                1.dp,
                if (active) Palette.accent.copy(alpha = 0.45f) else Palette.line,
                Radius.chip,
            )
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (active) Palette.accentHover else Palette.dim,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var focused by remember { mutableStateOf(false) }

    // 搜索联想：输入停顿 300ms 再拉，避免每个字符都发一次请求
    LaunchedEffect(value) {
        if (value.isBlank()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(300)
        suggestions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.hongguotv.pad.Api.searchSuggest(value) }.getOrDefault(emptyList())
        }
    }

    Box {
        Row(
            Modifier
                .width(240.dp).height(36.dp)
                .clip(RoundedCornerShape(8.dp)).background(Palette.panel)
                .border(1.dp, Palette.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.faint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, color = Palette.faint, fontSize = 14.sp)
                BasicTextField(
                    value = value,
                    onValueChange = { onChange(it); focused = true },
                    textStyle = TextStyle(color = Palette.text, fontSize = 14.sp),
                    cursorBrush = SolidColor(Palette.accent),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        androidx.compose.material3.DropdownMenu(
            expanded = focused && value.isNotBlank() && suggestions.isNotEmpty(),
            onDismissRequest = { focused = false },
        ) {
            suggestions.forEach { word ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(word, fontSize = 12.sp) },
                    onClick = {
                        focused = false
                        suggestions = emptyList()
                        onChange(word)
                    },
                )
            }
        }
    }
}

@Composable
private fun IconButton(icon: ImageVector, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Palette.panelHi else Palette.panel)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.dim, modifier = Modifier.size(16.dp))
    }
}

/** 首屏 hero：左海报 + 右信息（详情接口的数据优先，卡片兜底）。
 *  旧版还有左右箭头切主角 + 底部「N / 总数」计数（条目 >10 时用计数，
 *  否则用圆点）——没有这两个，hero 看着就是一张死图。 */
@Composable
fun HeroCard(
    card: SeriesCard,
    detail: SeriesDetail?,
    onPlay: () -> Unit,
    onDetail: () -> Unit,
    position: Int = 0,
    count: Int = 1,
    onNavigate: (Int) -> Unit = {},
    height: androidx.compose.ui.unit.Dp = 400.dp,
) {
    // 旧版 hero 的 hovered 状态供两个按钮共用
    var hovered by remember { mutableStateOf(false) }
    val title = detail?.title?.takeIf { it.isNotBlank() } ?: card.title
    val cover = detail?.cover?.takeIf { it.isNotBlank() } ?: card.cover
    val intro = detail?.intro?.takeIf { it.isNotBlank() }.orEmpty()
    val episodes = detail?.episodeCount ?: card.episodeCount
    val score = detail?.score?.takeIf { it.isNotBlank() } ?: card.score
    val hot = detail?.hotScore?.takeIf { it.isNotBlank() } ?: card.heat.orEmpty()
    val followed = detail?.followedCount.orEmpty()

    // 旧版 hero 规格（home-wall-page.tsx）：
    // 高 clamp(400px, 50vh-40px, 640px)、圆角 16（rounded-2xl）、细边框；
    // 背景 = 封面放大 1.25 倍 + blur 32px + 左深右浅渐变
    // (rgba(15,13,15,.94) 0% → .6 50% → .18 100%)；
    // 左海报高 76%、圆角 12、大投影；tag 胶囊 white/12；标题 30px/600 最多两行；
    // 评分 20px 星色、简介 14px 最多 3 行；播放/详情为圆角胶囊
    Box(
        Modifier.fillMaxWidth().height(height)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Palette.line, RoundedCornerShape(16.dp))
            .background(Palette.bg),
    ) {
        RemoteImage(cover, Modifier.matchParentSize().graphicsLayer { scaleX = 1.25f; scaleY = 1.25f }, fallback = title)
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to Color(0xF00F0D0F),
                    0.5f to Color(0x990F0D0F),
                    1f to Color(0x2E0F0D0F),
                ),
            ),
        )
        Row(
            Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteImage(
                cover,
                Modifier.fillMaxHeight(0.76f).aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)),
                fallback = title,
            )
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (detail?.tags.orEmpty().isNotEmpty() || card.tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (detail?.tags ?: card.tags).take(3).forEach { tag ->
                            Text(
                                tag,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(Color(0x1FFFFFFF)).padding(horizontal = 10.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (score.isNotBlank()) {
                        Text(score, color = Palette.star, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (hot.isNotBlank()) {
                        HeatBadge(hot, big = true)
                    }
                    if (followed.isNotBlank()) {
                        Text(com.hongguotv.pad.formatCount(followed) + " 人追剧", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    }
                    if (episodes > 0) Text("全 $episodes 集", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
                if (intro.isNotBlank()) {
                    Text(
                        intro,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Hero 主按钮：8dp 圆角、px-24 py-12（约 44 高），红 = 本屏唯一 accent
                    Box(
                        Modifier.clip(Radius.ctrl).background(Palette.accentFill)
                            .onPointerEvent(PointerEventType.Enter) { hovered = true }
                            .onPointerEvent(PointerEventType.Exit) { hovered = false }
                            .clickable { onPlay() }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("播放", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Box(
                        Modifier.clip(Radius.ctrl)
                            .border(1.dp, Palette.whiteBorder, Radius.ctrl)
                            .background(Color(0x66000000))
                            .onPointerEvent(PointerEventType.Enter) { hovered = true }
                            .onPointerEvent(PointerEventType.Exit) { hovered = false }
                            .clickable { onDetail() }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text("详情", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        // 左右箭头：手动切主角（旧版 size-11 半透明黑圆）
        if (count > 1) {
            Row(
                Modifier.fillMaxWidth().align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(16.dp))
                HeroArrow(Icons.Filled.ChevronLeft) { onNavigate(-1) }
                Spacer(Modifier.weight(1f))
                HeroArrow(Icons.Filled.ChevronRight) { onNavigate(1) }
                Spacer(Modifier.width(16.dp))
            }
        }
        // 指示器：条目多（>10）用「N / 总数」计数，少则用圆点
        if (count > 10) {
            Text(
                "${position + 1} / $count",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 20.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0x73000000))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        } else if (count > 1) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(count) { i ->
                    Box(
                        Modifier
                            .height(6.dp)
                            .width(if (i == position) 24.dp else 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (i == position) Palette.accentFill else Color(0x59FFFFFF))
                            .clickable { onNavigate(i - position) },
                    )
                }
            }
        }
    }
}

/** hero 左右切换圆钮（44×44 半透明黑底，hover 加深）。 */
@Composable
private fun HeroArrow(icon: ImageVector, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(if (hovered) Color(0xB3000000) else Color(0x8C000000))
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector?, onClick: () -> Unit) =
    PrimaryButton(text, icon, Modifier, onClick)

@Composable
fun PrimaryButton(text: String, icon: ImageVector?, modifier: Modifier, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Palette.accentHover else Palette.accentFill)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    icon: ImageVector? = null,
    iconTint: Color = Palette.dim,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Palette.panelHi else Color(0x1AFFFFFF))
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** 海报卡（旧版 home-wall-page 卡片规格）：
 *  圆角 12、细边框、hover 上浮 2px + 阴影（不做放大缩放）；
 *  封面 3:4；hover 压暗 30% + 中央双胶囊（播放=红底 / 详情=描边黑底）；
 *  角标（季/运营）在左上 10px；下方标题 14/600 + 热度 12px warm 色。 */
@Composable
fun PosterCard(
    card: SeriesCard,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onCollect: () -> Unit,
    /** 封面左下角「N 集」角标（找剧/收藏卡有，首页墙没有） */
    coverBadge: String? = null,
    /** 标题下第一行（默认走热度行；收藏页传「共 N 集」） */
    metaLine: String? = null,
    /** 标题下第二行：题材标签（找剧/新剧卡） */
    tagsLine: String? = null,
    /** 第三行：评分（新剧卡） */
    scoreLine: String? = null,
) {
    var hovered by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.panel)
            .border(1.dp, if (hovered) Color(0x33FFFFFF) else Palette.line, RoundedCornerShape(12.dp))
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .onPointerEvent(PointerEventType.Press) { ev ->
                if (ev.button == androidx.compose.ui.input.pointer.PointerButton.Secondary) menu = true
            }
            .clickable { onOpen() },
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 4f),
        ) {
            RemoteImage(card.cover, Modifier.matchParentSize(), fallback = card.title)
            // hover 压暗层（旧版 bg-black/30）
            Box(
                Modifier.matchParentSize().background(
                    if (hovered) Color(0x4D000000) else Color.Transparent,
                ),
            )
            // 中央双按钮（hover 出现）
            if (hovered) {
                Row(
                    Modifier.matchParentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Row(
                        Modifier.clip(Radius.ctrl).background(Palette.accentFill)
                            .clickable { onPlay() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("播放", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(Radius.ctrl)
                            .border(1.dp, Palette.whiteBorder, Radius.ctrl)
                            .background(Color(0x73000000))
                            .clickable { onOpen() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("详情", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            // 左上角标：状态标（漫剧/爆更/红果首发，与安卓同字段）优先，其次季/运营标
            val status = card.statusText?.takeIf { it.isNotBlank() && it.toIntOrNull() == null }
            val badge = status ?: card.badge ?: card.season
            if (!badge.isNullOrBlank()) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Palette.accentFill)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            // 右上评分
            if (card.score.isNotBlank()) {
                Text(
                    card.score,
                    color = Palette.star,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color(0xB3000000))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("播放", fontSize = 12.sp) }, onClick = { menu = false; onPlay() })
                DropdownMenuItem(text = { Text("详情", fontSize = 12.sp) }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text("收藏", fontSize = 12.sp) }, onClick = { menu = false; onCollect() })
            }
            // 左下「N 集」角标：底部暗渐变 + 白字（与安卓同一套，不再用胶囊）
            if (!coverBadge.isNullOrBlank()) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp)
                        .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0x8C000000)))),
                )
                Text(
                    coverBadge,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 5.dp)
                        .background(Color(0x59000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                card.title,
                color = Palette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                lineHeight = 20.sp,
                overflow = TextOverflow.Ellipsis,
            )
            val heat = card.heat?.takeIf { it.isNotBlank() } ?: card.playText.takeIf { it.isNotBlank() }
            val first = metaLine ?: heat
            if (metaLine == null && first != null) {
                // 热度行：线形火焰 + 暖橙数字（签名细节）；无热度数据时隐藏行位
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Whatshot, contentDescription = null, tint = Palette.heat, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        first,
                        color = Palette.heat,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    first ?: " ",
                    color = if (metaLine != null) Palette.dim else Palette.heat,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (first == null) Modifier.alpha(0f) else Modifier,
                )
            }
            if (!tagsLine.isNullOrBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tagsLine.split(' ').filter { it.isNotBlank() }.take(3).forEach { tag ->
                        Text(
                            tag,
                            color = Palette.dim,
                            fontSize = 10.sp,
                            modifier = Modifier.background(Palette.whiteBorder.copy(alpha = 0x0F / 255f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
            if (!scoreLine.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = Palette.star, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(scoreLine, color = Palette.star, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/**
 * 海报墙：按可用宽度自适应列数（1440 宽 7 列）。
 *
 * 用 FlowRow 按内容高度排版，列宽由可用宽度算出来，所以外面套
 * verticalScroll 时不会出现「按 6 列写死高度、换列数裁掉最后一行」。
 */
@Composable
fun PosterGrid(
    cards: List<SeriesCard>,
    onOpen: (SeriesCard) -> Unit,
    onPlay: (SeriesCard) -> Unit,
    onCollect: (SeriesCard) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    coverBadge: (SeriesCard) -> String? = { null },
    metaLine: (SeriesCard) -> String? = { null },
    tagsLine: (SeriesCard) -> String? = { null },
    scoreLine: (SeriesCard) -> String? = { null },
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = when {
            maxWidth >= 1500.dp -> 9
            maxWidth >= 1300.dp -> 8
            maxWidth >= 1120.dp -> 7
            maxWidth >= 960.dp -> 6
            maxWidth >= 780.dp -> 5
            else -> 4
        }
        val gap = 16.dp
        val cell = (maxWidth - gap * (columns - 1)) / columns
        val contentModifier = if (scrollable) {
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxWidth()
        }
        Column(contentModifier) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                maxItemsInEachRow = columns,
                modifier = Modifier.fillMaxWidth(),
            ) {
                cards.forEachIndexed { index, card ->
                    // 进场动画（旧版 wall-card-in：淡入 + 上浮，按列错峰 20ms）
                    var shown by remember(card.seriesId) { mutableStateOf(false) }
                    val alpha by animateFloatAsState(
                        targetValue = if (shown) 1f else 0f,
                        animationSpec = tween(durationMillis = 260, delayMillis = (index % 24) * 20),
                        label = "cardAlpha",
                    )
                    LaunchedEffect(card.seriesId) { shown = true }
                    Box(
                        Modifier.width(cell).graphicsLayer {
                            this.alpha = alpha
                            translationY = (1f - alpha) * 10f
                        },
                    ) {
                        PosterCard(
                            card,
                            onOpen = { onOpen(card) },
                            onPlay = { onPlay(card) },
                            onCollect = { onCollect(card) },
                            coverBadge = coverBadge(card),
                            metaLine = metaLine(card),
                            tagsLine = tagsLine(card),
                            scoreLine = scoreLine(card),
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 空列表占位：图标 + 标题 + 说明，比一行灰字更像成品软件。 */
@Composable
fun EmptyState(
    title: String,
    hint: String? = null,
    icon: ImageVector = Icons.Filled.Notifications,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Palette.panelHi),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Palette.faint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(hint, color = Palette.faint, fontSize = 12.sp)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            SecondaryButton(actionLabel, onClick = onAction)
        }
    }
}



