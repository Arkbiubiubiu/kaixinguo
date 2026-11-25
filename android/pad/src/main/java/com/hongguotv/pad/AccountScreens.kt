package com.hongguotv.pad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「账号与同步」页（TV/Pad 共用，接新公网配对服务 BASE）。
 *
 * 严格按「是否登录」二选一，绝不同时显示两块（文案与 PC 逐字一致）：
 *  - 已登录：进页按 uid 取 canonical 匹配码（pairByUid，没则 pairEnsure 建）；大号显示；
 *            「换个码」=pairRotate(uid)；手动改 6 位码 +「保存并共享」=pairRotate(uid,新码)。
 *  - 未登录：输入 6 位码「同步」=pairFetch(code)（落 cookies/uid/name → 已登录）；手机号登录见账户行。
 * 账户行（头像/昵称/副标题 + 手机号登录/退出）与收藏/点赞入口始终保留。
 */
@Composable
fun AccountScreen(
    onOpen: (String) -> Unit,
    onLogin: () -> Unit,
    /** 跳到「历史」页并落到指定 tab（0 历史 / 1 收藏 / 2 点赞） */
    onGoHistory: (Int) -> Unit = {},
) {
    var syncMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 未登录：要输入的匹配码
    var inputCode by remember { mutableStateOf("") }
    // 已登录：本机 canonical 匹配码
    var shareCode by remember { mutableStateOf(Store.pairCode) }
    var confirmExit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refreshMsg(msg: String?) { syncMsg = msg }

    // 网络调用离开主线程（clickable 回调在主线程，直接发请求会 NetworkOnMainThread）
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

    // 已登录进入本页：取 uid → pairByUid 拿 canonical code；没有就 pairEnsure 新建。不再本地随机。
    // key 用 Store.loggedIn：本页内用匹配码登录成功(false→true)也会触发一次。
    LaunchedEffect(Store.loggedIn) {
        if (!Store.loggedIn) return@LaunchedEffect
        // 副设备（通过匹配码同步来的，accountSource=pair）不再生成/显示匹配码——它已登录、没法再匹配
        if (Store.accountSource == "pair") return@LaunchedEffect
        val uid = Store.accountUid
        if (uid.isBlank()) {
            syncMsg = "账号信息缺少用户ID，请重新登录"
            return@LaunchedEffect
        }
        val existing = withContext(Dispatchers.IO) { runCatching { Api.pairByUid(uid) }.getOrNull() }
        if (!existing.isNullOrBlank()) {
            shareCode = existing
            Store.pairCode = existing
        } else {
            val r = withContext(Dispatchers.IO) {
                runCatching { Api.pairEnsure(uid, Store.accountName, Store.accountCookies) }
            }
            r.onSuccess { code -> shareCode = code; Store.pairCode = code }
                .onFailure { syncMsg = "获取匹配码失败：${it.message}" }
        }
    }
    LaunchedEffect(confirmExit) {
        if (confirmExit) { delay(3_000); confirmExit = false }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = UiMemo.settingsList,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 账户行：头像 + 昵称 + 副标题 + 手机号登录/退出
            item {
                Row(
                    Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(PanelHi),
                        contentAlignment = Alignment.Center,
                    ) { Text("👤", fontSize = 20.sp) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (Store.loggedIn) Store.accountName.ifBlank { "用户" } else "未登录",
                            color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (Store.loggedIn) "收藏 / 历史 / 追更 都跟着账号走"
                            else "登录后收藏 / 历史 / 追更 跟着账号走",
                            color = TextDim, fontSize = 12.sp,
                        )
                    }
                    if (!Store.loggedIn) {
                        TvChip("手机号登录", selected = true) { onLogin() }
                    } else {
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
                }
            }

            // 收藏 / 点赞入口（功能入口，保留）
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvChip("收藏", selected = false) { onGoHistory(1) }
                    TvChip("点赞", selected = false) { onGoHistory(2) }
                }
            }

            // —— 只按登录态二选一；副设备(pair)两块都不显示，只留上面的账号行+退出 ——
            if (Store.loggedIn && Store.accountSource != "pair") {
                item {
                    SyncCard(
                        title = "在其它设备上同步",
                        desc = "在需要同步的设备上输入这 6 位码，就能用这个账号看收藏 / 历史 / 追更。",
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(
                                value = shareCode,
                                onValueChange = { shareCode = it.filter { ch -> ch.isLetterOrDigit() }.take(6) },
                                textStyle = TextStyle(color = Red, fontSize = 26.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold),
                                cursorBrush = SolidColor(Red), singleLine = true,
                                modifier = Modifier
                                    .width(190.dp).height(46.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PanelHi)
                                    .padding(horizontal = 14.dp),
                                decorationBox = { inner ->
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                        if (shareCode.isEmpty()) Text("6 位匹配码", color = Color(0x66FFFFFF), fontSize = 14.sp)
                                        inner()
                                    }
                                },
                            )
                            Spacer(Modifier.weight(1f))
                            TvChip("换个码", selected = false) {
                                val uid = Store.accountUid
                                if (uid.isBlank()) { refreshMsg("账号信息缺少用户ID，请重新登录"); return@TvChip }
                                runAsync("共享") {
                                    val code = Api.pairRotate(uid)
                                    shareCode = code
                                    Store.pairCode = code
                                    "已保存并共享，其它设备输入 $code 即可同步"
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            TvChip("保存并共享", selected = shareCode.length == 6) {
                                if (shareCode.length != 6) { refreshMsg("匹配码是 6 位字母或数字"); return@TvChip }
                                val uid = Store.accountUid
                                if (uid.isBlank()) { refreshMsg("账号信息缺少用户ID，请重新登录"); return@TvChip }
                                val want = shareCode
                                runAsync("共享") {
                                    val code = Api.pairRotate(uid, want)
                                    shareCode = code
                                    Store.pairCode = code
                                    "已保存并共享，其它设备输入 $code 即可同步"
                                }
                            }
                        }
                    }
                }
            } else if (!Store.loggedIn) {
                item {
                    SyncCard(
                        title = "用其它设备的账号同步",
                        desc = "在已登录的设备上生成 6 位匹配码，填到这里。",
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(
                                value = inputCode,
                                onValueChange = { inputCode = it.filter { ch -> ch.isLetterOrDigit() }.take(6) },
                                textStyle = TextStyle(color = TextMain, fontSize = 20.sp, letterSpacing = 3.sp),
                                cursorBrush = SolidColor(Red), singleLine = true,
                                modifier = Modifier
                                    .weight(1f).height(46.dp)
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
                            TvChip("同步", selected = inputCode.length == 6) {
                                if (inputCode.length != 6) { refreshMsg("匹配码是 6 位字母或数字"); return@TvChip }
                                val code = inputCode
                                runAsync("同步") {
                                    val r = Api.pairFetch(code) // 落 cookies/uid/name → 已登录
                                    inputCode = ""
                                    "同步成功：${r.name}"
                                }
                            }
                        }
                    }
                }
            }

            syncMsg?.let { msg ->
                item { Text(msg, color = TextDim, fontSize = 12.sp) }
            }
        }
    }
}

/** 账号同步卡片：标题 + 说明 + 内容。 */
@Composable
private fun SyncCard(title: String, desc: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Column {
            Text(title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(desc, color = TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
