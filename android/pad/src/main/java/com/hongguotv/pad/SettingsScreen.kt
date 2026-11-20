package com.hongguotv.pad

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

@Composable
internal fun OfficialSiteColumn() {
    val uri = LocalUriHandler.current
    val url = "https://kxg.fabao.xin/"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("官方网站", color = TextMain, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Image(
            painter = painterResource(R.drawable.official_qr),
            contentDescription = "官网二维码",
            modifier = Modifier
                .size(104.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(5.dp)
                .clickable { runCatching { uri.openUri(url) } },
        )
        Spacer(Modifier.height(8.dp))
        Text("kxg.fabao.xin", color = TextDim, fontSize = 12.sp)
        Text("扫码打开", color = TextDim, fontSize = 11.sp)
    }
}

@Composable
fun SettingsScreen() {
    val testScope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        PageHeader(title = "设置")
        LazyColumn(
            Modifier.fillMaxSize(),
            state = UiMemo.settingsList,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 版本更新：查外网/内网清单，有新版直接弹窗下载安装
            item {
                var checking by remember { mutableStateOf(false) }
                var info by remember { mutableStateOf<UpdateInfo?>(null) }
                var status by remember { mutableStateOf<String?>(null) }
                val upScope = rememberCoroutineScope()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text("版本更新", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "当前版本 ${Updater.versionName(Store.appContext)}（${Updater.versionCode(Store.appContext)}）",
                                color = TextDim,
                                fontSize = 13.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "更新走自建服务器，边缘节点加速下载",
                                color = TextDim,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TvChip(if (checking) "检查中…" else "检查更新", selected = false) {
                                    if (checking) return@TvChip
                                    checking = true
                                    status = null
                                    upScope.launch {
                                        // check() 在「全部更新源连不上」时会抛 CheckException：
                                        // 必须如实显示失败，不能当成「已是最新」（外网 2.2.73 谎报事故）
                                        val r = runCatching { Updater.check(Store.appContext) }
                                        checking = false
                                        r.getOrNull()?.let { info = it }
                                        if (r.exceptionOrNull() is Updater.CheckException) {
                                            status = "检查失败：网络连不上更新源，请重试"
                                        } else if (r.getOrNull() == null) {
                                            status = "已是最新版本"
                                        }
                                    }
                                }
                                status?.let {
                                    Spacer(Modifier.width(10.dp))
                                    Text(it, color = TextDim, fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.width(20.dp))
                        OfficialSiteColumn()
                    }
                }
                info?.let { UpdateDialog(it) { info = null } }
            }
            item {
                // 这几项存在 SharedPreferences 里，是普通属性不是 Compose State：
                // 只写 Store.xxx 不会触发重组，界面毫无变化（就是「点了没反应」）。
                // 本地 state 承接一下，点完立刻刷新；写盘照旧。
                var autoNextOn by remember { mutableStateOf(Store.autoNext) }
                var deleteAfterOn by remember { mutableStateOf(Store.deleteAfterFinish) }
                var softOn by remember { mutableStateOf(Store.softDecode) }
                var capHeight by remember { mutableStateOf(Store.maxHeight) }
                var defSpeed by remember { mutableStateOf(Store.speed) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Text("播放", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        // 整行都能点/能被遥控器选中。原来只有右边那个 46×26 的小开关带
                        // clickable，遥控器按上/下做的是几何搜索，会直接越过它落到下面的
                        // 「1080P」上（那一列在左边，比贴在最右边的小开关近得多），
                        // 结果电视上根本关不掉自动连播。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    autoNextOn = !autoNextOn
                                    Store.autoNext = autoNextOn
                                },
                        ) {
                            Text("播完自动下一集", color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Box(
                                Modifier
                                    .width(46.dp)
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (autoNextOn) Red else Color(0x33FFFFFF)),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Box(
                                    Modifier
                                        .padding(horizontal = 3.dp)
                                        .size(20.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Color.White),
                                )
                            }
                        }
                        Text("播放完当前集自动接着播下一集", color = TextDim, fontSize = 12.sp)
                        Spacer(Modifier.height(14.dp))
                        // 看完自动删除记录（与 PC 同名同义）：最后一集播完把这部剧从观看记录里移除
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    deleteAfterOn = !deleteAfterOn
                                    Store.deleteAfterFinish = deleteAfterOn
                                },
                        ) {
                            Text("看完自动删除记录", color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Box(
                                Modifier
                                    .width(46.dp)
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (deleteAfterOn) Red else Color(0x33FFFFFF)),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Box(
                                    Modifier
                                        .padding(horizontal = 3.dp)
                                        .size(20.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Color.White),
                                )
                            }
                        }
                        Text("最后一集播完，把这部剧从观看记录里移除", color = TextDim, fontSize = 12.sp)
                        Spacer(Modifier.height(14.dp))
                        // 软件解码：整行可点（同「自动连播」那条的教训——只点右边的小开关，
                        // 遥控器上下做几何搜索会直接越过它落到别的控件上）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    softOn = !softOn
                                    Store.softDecode = softOn
                                },
                        ) {
                            Text(
                                "软件解码（画面绿块/花屏时打开）",
                                color = TextMain,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                Modifier
                                    .width(46.dp)
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (softOn) Red else Color(0x33FFFFFF)),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Box(
                                    Modifier
                                        .padding(horizontal = 3.dp)
                                        .size(20.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Color.White),
                                )
                            }
                        }
                        Text(
                            "打开＝用软件解码，关闭＝用电视硬件解码。画面出现绿色方块时打开",
                            color = TextDim,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text("默认清晰度上限", color = TextMain, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1080 to "1080P", 720 to "720P", 540 to "540P").forEach { (h, label) ->
                                TvChip(label, selected = capHeight == h) {
                                    capHeight = h
                                    Store.maxHeight = h
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("默认倍速", color = TextMain, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { s ->
                                TvChip("${s}x", selected = defSpeed == s) {
                                    defSpeed = s
                                    Store.speed = s
                                }
                            }
                        }
                    }
                }
            }
            item {
                // 存储：安卓这边视频是边下边播、不落盘，真正占地方的是封面图缓存
                var cacheText by remember { mutableStateOf("计算中…") }
                var cleared by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    cacheText = withContext(Dispatchers.IO) {
                        Store.cacheSizeText(Store.appContext)
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Text("存储", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "缓存占用 $cacheText（主要是封面图）",
                            color = TextDim,
                            fontSize = 13.sp,
                        )
                        cleared?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = Red, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        TvChip("清除缓存") {
                            cleared = Store.clearCache(Store.appContext)
                            cacheText = Store.cacheSizeText(Store.appContext)
                        }
                    }
                }
            }
            item {
                // 原版同款：找剧筛选服务（代签）卡
                var signUrl by remember { mutableStateOf(Store.signUrl) }
                var signState by remember { mutableStateOf<String?>(null) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Text("找剧筛选服务（代签）", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "找剧筛选和设备配对走这个服务；同一局域网填服务器内网地址即可",
                            color = TextDim, fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(
                                value = signUrl,
                                onValueChange = { signUrl = it },
                                textStyle = TextStyle(color = TextMain, fontSize = 15.sp),
                                cursorBrush = SolidColor(Red),
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PanelHi)
                                    .padding(horizontal = 14.dp),
                                decorationBox = { inner ->
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                        inner()
                                    }
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            TvChip("测试连接", selected = false) {
                                Store.signUrl = signUrl
                                signState = "正在测试…"
                                // 原来 runBlocking 把主线程卡住 12~25 秒（遥控器完全无响应，可能 ANR）
                                testScope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching { Api.testSignService(signUrl) }.getOrDefault(false)
                                    }
                                    signState = if (ok) "连接正常" else "连不上，检查地址"
                                }
                            }
                        }
                        signState?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = if (it == "连接正常") Success else TextDim, fontSize = 12.sp)
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("清除图片与列表缓存", color = TextMain, fontSize = 15.sp)
                        Text("封面图、首页/详情缓存；观看记录和登录不受影响", color = TextDim, fontSize = 12.sp)
                    }
                    TvChip("清除", selected = false) {
                        FeedCache.clear()
                        DetailCache.clear()
                        runCatching {
                            android.widget.Toast.makeText(
                                Store.appContext, "已清理图片与列表缓存", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Text("重建版 2.2.1-r1 · 从已发布包反编译还原", color = TextDim, fontSize = 12.sp)
                }
            }
        }
    }
}
