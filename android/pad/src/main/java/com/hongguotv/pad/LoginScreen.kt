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
fun LoginScreen(onDone: () -> Unit) {
    var mobile by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var wait by remember { mutableStateOf(0) }
    var mfa by remember { mutableStateOf<LoginApi.NeedMfa?>(null) }
    val scope = rememberCoroutineScope()

    // 重发倒计时（服务端 retry_time，一般 60 秒）
    LaunchedEffect(wait) {
        if (wait > 0) {
            delay(1000)
            wait -= 1
        }
    }

    fun sendCode() {
        if (busy || wait > 0) return
        if (!LoginApi.isMobileOk(mobile)) {
            msg = "请输入 11 位手机号"
            return
        }
        busy = true
        msg = "正在发送验证码…"
        scope.launch(Dispatchers.IO) {
            val r = runCatching { LoginApi.sendCode(mobile) }
            withContext(Dispatchers.Main) {
                busy = false
                r.fold(
                    {
                        wait = it.retryAfter
                        msg = it.message.ifBlank { "验证码已发送，请查看短信" }
                    },
                    { msg = it.message ?: "发送失败，请稍后重试" },
                )
            }
        }
    }

    fun submit() {
        if (busy) return
        if (!LoginApi.isMobileOk(mobile)) {
            msg = "请输入 11 位手机号"
            return
        }
        if (code.length < 4) {
            msg = "请输入短信里的验证码"
            return
        }
        busy = true
        msg = "正在登录…"
        scope.launch(Dispatchers.IO) {
            val r = runCatching { LoginApi.login(mobile, code) }
            withContext(Dispatchers.Main) {
                busy = false
                r.fold(
                    { acc ->
                        msg = "登录成功：${acc.name.ifBlank { "用户" }}"
                        mfa = null
                        onDone()
                    },
                    { e ->
                        // 风控要求「手机发一条短信」时才走 MFA，不是错误，单独提示
                        if (e is LoginApi.NeedMfa) {
                            mfa = e
                            msg = e.hint
                        } else {
                            msg = e.message ?: "登录失败，请重试"
                        }
                    },
                )
            }
        }
    }

    // MFA：每 3 秒问一次平台（最多 2 分钟），通过后 LoginApi 内部会重发登录换会话
    LaunchedEffect(mfa) {
        val cur = mfa ?: return@LaunchedEffect
        repeat(40) {
            delay(3000)
            val r = runCatching { withContext(Dispatchers.IO) { LoginApi.pollMfa(cur) } }
            if (r.getOrNull() == true) {
                msg = "安全验证通过，已登录"
                mfa = null
                onDone()
                return@LaunchedEffect
            }
            r.exceptionOrNull()?.message?.let { msg = it }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(title = "登录")
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 电视上打字很痛苦：先把「用手机配对」这条路摆在最前面
            if (Store.appContext.packageName.endsWith("nativeapp")) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Star.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .border(1.dp, Star.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Text("电视上建议用手机配对登录", color = Star, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "回到「我的」页，用手机扫那里的二维码或输入 6 位匹配码即可，不用在电视上打手机号和验证码。",
                            color = TextDim, fontSize = 12.sp, lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        TvChip("回「我的」用配对码", selected = true) { onDone() }
                    }
                }
            }

            // 说明：为什么要单独登录、登录后能做什么
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Column {
                    Text("手机号登录", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "用手机号 + 短信验证码就能登录。每台设备各自登录自己的账号，" +
                            "登录后这台设备就是主账号，可以在「我的」生成匹配码给其它设备用。",
                        color = TextDim, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                }
            }

            // 手机号
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Column {
                    Text("手机号", color = TextDim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoginField(
                            value = mobile,
                            placeholder = "11 位手机号",
                            maxLen = 11,
                            modifier = Modifier.weight(1f),
                        ) { mobile = it.filter { ch -> ch.isDigit() } }
                        Spacer(Modifier.width(12.dp))
                        TvChip(
                            if (wait > 0) "${wait}s 后重发" else if (busy) "发送中…" else "获取验证码",
                            selected = wait == 0 && !busy,
                        ) { sendCode() }
                    }
                }
            }

            // 验证码 + 登录
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Column {
                    Text("验证码", color = TextDim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoginField(
                            value = code,
                            placeholder = "短信里的 4–6 位数字",
                            maxLen = 6,
                            modifier = Modifier.weight(1f),
                        ) { code = it.filter { ch -> ch.isDigit() } }
                        Spacer(Modifier.width(12.dp))
                        TvChip(if (busy) "登录中…" else "登录", selected = code.length >= 4 && !busy) {
                            submit()
                        }
                    }
                }
            }

            msg?.let {
                Text(it, color = if (mfa != null) Star else TextDim, fontSize = 13.sp)
            }

            // 需要上行短信验证时，把号码和内容直接摊开给用户
            mfa?.let { m ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Star.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .border(1.dp, Star.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Text("需要一次安全验证", color = Star, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "平台判断这次登录有风险，需要你用手里的手机给下面号码发一条短信，发送后这里会自动继续。",
                            color = TextDim, fontSize = 12.sp, lineHeight = 18.sp,
                        )
                        if (m.channel.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text("号码：${m.channel}", color = TextMain, fontSize = 13.sp)
                        }
                        if (m.content.isNotBlank()) {
                            Text("内容：${m.content}", color = TextMain, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("正在等待验证结果…", color = TextDim, fontSize = 12.sp)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TvChip("返回", selected = false) { onDone() }
                Spacer(Modifier.width(10.dp))
                Text("电视上用遥控器打不了字时，请用「我的」的「匹配码」把手机上的登录态接过来", color = TextDim, fontSize = 11.sp)
            }
        }
    }
}

/** 登录页输入框（数字键盘、无下划线，与账号页匹配码输入同一套外观）。 */
@Composable
internal fun LoginField(
    value: String,
    placeholder: String,
    maxLen: Int,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(maxLen)) },
        textStyle = TextStyle(color = TextMain, fontSize = 20.sp, letterSpacing = 3.sp),
        cursorBrush = SolidColor(Red),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        ),
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PanelHi)
            .padding(horizontal = 14.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, color = Color(0x66FFFFFF), fontSize = 14.sp)
                }
                inner()
            }
        },
    )
}

/** 官网入口：二维码 + 域名（版本更新右侧；手机/平板扫码即可打开）。 */
