package cn.a10miaomiao.bilimiao.compose.components.preference

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.rememberPreferenceState

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
    var tempValue by remember { mutableStateOf(value.toString()) }

    Preference(
        modifier = modifier,
        title = title,
        enabled = enabled,
        icon = icon,
        summary = summary ?: { Text(value.toString() + label) },
        onClick = {
            tempValue = if (value == 0) "" else value.toString()
            showDialog = true
        },
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = title,
            text = {
                TextField(
                    value = tempValue,
                    onValueChange = { tempValue = it.filter { c -> c.isDigit() } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    label = { Text(label) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newValue = tempValue.toIntOrNull() ?: 0
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
