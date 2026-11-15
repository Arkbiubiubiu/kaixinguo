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
fun AccountScreen(
    onOpen: (String) -> Unit,
    onLogin: () -> Unit,
    /** 跳到「历史」页并落到指定 tab（0 历史 / 1 收藏 / 2 点赞） */
    onGoHistory: (Int) -> Unit = {},
) {
    var syncMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 从设备：要输入的匹配码
    var inputCode by remember { mutableStateOf("") }
    // 主设备：共享码（没存过就随机生成一个候选）
    var shareCode by remember {
        mutableStateOf(Store.pairCode.ifBlank { (100000..999999).random().toString() })
    }

    fun refreshMsg(msg: String?) { syncMsg = msg }

    // 网络调用必须离开主线程：之前这四个按钮在 clickable 里同步发请求，
    // Compose 的点击回调跑在主线程 → NetworkOnMainThreadException（message 为 null），
    // 界面只会显示「同步失败 / 匹配失败」，看起来像服务端的问题。
    val scope = rememberCoroutineScope()
    fun runAsync(label: String, block: () -> String) {
        if (busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            val r = runCatching { block() }
            withContext(Dispatchers.Main) {
                busy = false
                syncMsg = r.fold(
                    { it.ifBlank { "$label 成功" } },
                    { it.message ?: "$label 失败（${it.javaClass.simpleName}）" },
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = UiMemo.settingsList,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 账户卡（**PC「我的」页同款首屏结构**）：头像 + 昵称 + 登录态说明。
            // 与 PC 一致不显示页面大标题，页面直接从账户卡开始。
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(50))
                            .background(PanelHi),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("👤", fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (Store.loggedIn) Store.accountName.ifBlank { "用户" } else "未登录",
                            color = TextMain,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (Store.loggedIn) "已同步云端收藏与点赞" else "登录后同步收藏与点赞",
                            color = TextDim,
                            fontSize = 12.sp,
                        )
                    }
                    if (!Store.loggedIn) {
                        TvChip("登录", selected = true) { onLogin() }
                    }
                }
            }
            // 收藏 / 点赞入口（**PC「我的」页的两个 tab**）：TV 上收敛成
            // 两个入口跳到历史页（那边的三 tab 是 历史/收藏/点赞），
            // 遥控器少一层 tab 切换，功能与 PC 等价。
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TvChip("收藏", selected = false) { onGoHistory(1) }
                    TvChip("点赞", selected = false) { onGoHistory(2) }
                }
            }
            // 状态卡（原版：绿字登录态 + 来源/uid + 退出）
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        if (Store.loggedIn) {
                            Text(
                                "已登录：${Store.accountName.ifBlank { "用户" }}",
                                color = Success,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            val source = when (Store.accountSource) {
                                "local" -> "本机登录（可作主设备共享给其它设备）"
                                "pair" -> "匹配自其它设备（从设备）"
                                else -> "同步自其它设备"
                            }
                            Text(
                                source + if (Store.accountUid.isNotEmpty()) " · uid ${Store.accountUid}" else "",
                                color = TextDim,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row {
                                TvChip("重新同步登录态", selected = false) {
                                    runAsync("同步") { Api.syncAccount(force = true); "已重新同步" }
                                }
                                Spacer(Modifier.width(10.dp))
                                var confirmExit by remember { mutableStateOf(false) }
                                LaunchedEffect(confirmExit) {
                                    if (confirmExit) {
                                        kotlinx.coroutines.delay(3_000)
                                        confirmExit = false
                                    }
                                }
                                TvChip(if (confirmExit) "确认退出？" else "退出", selected = false) {
                                    if (confirmExit) {
                                        Store.clearAccount()
                                        refreshMsg("已退出登录")
                                        confirmExit = false
                                    } else {
                                        confirmExit = true
                                        refreshMsg("再点一次确认退出（本机收藏列表会清空）")
                                    }
                                }
                            }
                        } else {
                            Text("未登录", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(6.dp))
                            Text("登录后收藏 / 追更 / 预约 / 历史跟着账号走", color = TextDim, fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))
                            Row {
                                TvChip("手机号登录", selected = true) { onLogin() }
                                Spacer(Modifier.width(10.dp))
                                TvChip("从电脑同步账号", selected = false) {
                                    runAsync("同步") {
                                        val name = Api.syncAccount()
                                        if (name.isBlank()) "已同步登录态" else "已同步：$name"
                                    }
                                }
                            }
                        }
                        syncMsg?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = TextDim, fontSize = 12.sp)
                        }
                    }
                }
            }
            // 主设备 · 共享我的账号
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("主设备 · 共享我的账号", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            if (!Store.loggedIn) {
                                Text("需先登录", color = TextDim, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "在其它设备输入这个 6 位匹配码，它就能用你的账号看收藏 / 历史 / 追更",
                            color = TextDim, fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                shareCode,
                                color = Red,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 4.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            TvChip("换个码", selected = false) {
                                shareCode = (100000..999999).random().toString()
                            }
                            Spacer(Modifier.width(10.dp))
                            TvChip("保存并共享", selected = Store.pairCode == shareCode) {
                                val code = shareCode
                                runAsync("共享") {
                                    Api.pairUpload(code)
                                    Store.pairCode = code
                                    "已共享，其它设备输入 $code 即可匹配"
                                }
                            }
                        }
                    }
                }
            }
            // 从设备 · 匹配其他设备
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Text("从设备 · 匹配其他设备", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "输入主设备显示的匹配码，登录态、收藏、历史会一起过来",
                            color = TextDim, fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(
                                value = inputCode,
                                onValueChange = { inputCode = it.filter { ch -> ch.isDigit() }.take(6) },
                                textStyle = TextStyle(color = TextMain, fontSize = 20.sp, letterSpacing = 3.sp),
                                cursorBrush = SolidColor(Red),
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PanelHi)
                                    .padding(horizontal = 14.dp),
                                decorationBox = { inner ->
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                        if (inputCode.isEmpty()) Text("输入 6 位匹配码", color = Color(0x66FFFFFF), fontSize = 14.sp)
                                        inner()
                                    }
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            TvChip("匹配", selected = inputCode.length == 6) {
                                if (inputCode.length != 6) {
                                    refreshMsg("匹配码是 6 位数字")
                                    return@TvChip
                                }
                                val code = inputCode
                                runAsync("匹配") {
                                    val name = Api.pairFetch(code)
                                    inputCode = ""
                                    "匹配成功：${name.ifBlank { "用户" }}，收藏/历史已同步"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 登录页：手机号 + 短信验证码。
 *
 * 每台设备都能用自己的手机号独立登录 —— 登录后本机就是**主账号**，
 * 可在账号页生成 6 位匹配码共享给其它端（TV / 平板 / 电脑）；
 * 反过来，这台设备也能用匹配码把别处的登录态接过来。
 *
 * 协议细节都在 [LoginApi] 里，这里只处理交互与状态。
 */
