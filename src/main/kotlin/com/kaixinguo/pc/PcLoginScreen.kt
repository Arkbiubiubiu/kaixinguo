@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.kaixinguo.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手机号 + 验证码登录（PC）。
 *
 * 与电视端同一套 passport 流程；登录态写进 PcStore。
 * 风控要求「上行短信验证」时不在这里处理，提示改用匹配码同步。
 */
@Composable
fun LoginScreen(onBack: () -> Unit) {
    var mobile by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun run(label: String, block: () -> String) {
        if (busy) return
        busy = true
        message = null
        scope.launch(Dispatchers.IO) {
            val result = runCatching { block() }
            withContext(Dispatchers.Main) {
                busy = false
                result.onSuccess { text ->
                    messageIsError = false
                    message = text
                }.onFailure { t ->
                    messageIsError = true
                    message = t.message ?: "$label 失败"
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(text = "返回", icon = Icons.Filled.ArrowBack, onClick = onBack)
            Spacer(Modifier.width(16.dp))
            Text("登录", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.panel)
                .padding(20.dp),
        ) {
            Text("手机号登录", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "登录后收藏、历史、追更跟着账号走；也可以直接回上一页用匹配码同步",
                color = Palette.dim, fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))

            Text("手机号", color = Palette.dim, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(mobile, "11 位手机号", Modifier.weight(1f)) { mobile = it.filter { c -> c.isDigit() }.take(11) }
                Spacer(Modifier.width(10.dp))
                PrimaryButton(if (busy) "请稍候" else "获取验证码", null) {
                    run("发送验证码") { PcLogin.sendCode(mobile) }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("验证码", color = Palette.dim, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(code, "短信里的 4~6 位数字", Modifier.weight(1f)) { code = it.filter { c -> c.isDigit() }.take(6) }
                Spacer(Modifier.width(10.dp))
                PrimaryButton(if (busy) "请稍候" else "登录", null) {
                    run("登录") {
                        val name = PcLogin.login(mobile, code)
                        "登录成功：$name"
                    }
                }
            }

            message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = if (messageIsError) Palette.accent else Palette.star, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Field(value: String, hint: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Box(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.panelHi)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, color = Palette.faint, fontSize = 14.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = Palette.text, fontSize = 14.sp),
            cursorBrush = SolidColor(Palette.accent),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
