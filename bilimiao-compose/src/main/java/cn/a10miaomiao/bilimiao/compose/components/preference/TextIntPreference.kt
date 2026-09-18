package cn.a10miaomiao.bilimiao.compose.components.preference

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.rememberPreferenceState
import cn.a10miaomiao.bilimiao.compose.components.dialogs.OverlayAlertDialog

fun LazyListScope.textIntPreference(
    key: String,
    defaultValue: Int,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    rememberState: @Composable () -> MutableState<Int> = {
        rememberPreferenceState(key, defaultValue)
    },
    enabled: () -> Boolean = { true },
    icon: @Composable (() -> Unit)? = null,
    summary: @Composable ((Int) -> Unit)? = null,
    label: String = "",
) {
    item(key = key, contentType = "TextIntPreference") {
        val state = rememberState()
        val value by state
        TextIntPreference(
            state = state,
            title = title,
            modifier = modifier,
            enabled = enabled(),
            icon = icon,
            summary = summary?.let { { it(value) } },
            label = label,
        )
    }
}

@Composable
fun TextIntPreference(
    state: MutableState<Int>,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (() -> Unit)? = null,
    summary: @Composable (() -> Unit)? = null,
    label: String = "",
) {
    var showDialog by remember { mutableStateOf(false) }
    var value by state
    // 用 TextFieldValue 而不是 String：String 重载的初始 selection 是 0，
    // 编辑已有数值模板时光标会停在最前面（反习惯）
    var tempValue by remember { mutableStateOf(TextFieldValue("")) }

    Preference(
        modifier = modifier,
        title = title,
        enabled = enabled,
        icon = icon,
        summary = summary ?: { Text(value.toString() + label) },
        onClick = {
            val initText = if (value == 0) "" else value.toString()
            tempValue = TextFieldValue(initText, TextRange(initText.length))
            showDialog = true
        },
    )

    if (showDialog) {
        OverlayAlertDialog(
            onDismissRequest = { showDialog = false },
            title = title,
            text = {
                TextField(
                    value = tempValue,
                    onValueChange = { newVal ->
                        val digits = newVal.text.filter { c -> c.isDigit() }
                        // 没被过滤掉时保留用户自己的光标位置
                        tempValue = if (digits == newVal.text) newVal
                        else TextFieldValue(digits, TextRange(digits.length))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    label = { Text(label) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newValue = tempValue.text.toIntOrNull() ?: 0
                        value = newValue
                        showDialog = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
