package cn.a10miaomiao.bilimiao.compose.components.input

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * 全 App 统一的「搜索框」：左放大镜 + 输入 + **右侧叉号（一键清空）**。
 *
 * 为什么要有它（同一交互不许开第二份）：叉号清空这套交互原来散在多处
 * （下载列表页内联、全屏搜索 `SearchInputInline`、直播搜索页各自一份）。
 * 2026-10-02 先从下载那边抽出可复用的一份；2026-10-15 提为公共件，
 * 凡是"页面顶部一个搜索框、输入即过滤"的地方都用它。
 *
 * ★叉号**常驻**（不随"有没有输入"显隐）：没有输入时点它等于空操作。
 * ★IME 动作固定为 `Search`：这些框都是"输入即过滤 / 回车即搜"。
 * ★只负责输入框本身，外边距由调用方通过 [modifier] 给。
 */
@Composable
fun MiaoSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            IconButton(onClick = { onValueChange("") }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "清空",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // ★圆角 8dp：本仓输入框/列表项的既定档位（rules/02 §9；`MiaoInputBar` 同样口径）。
        //   不写就会落到 M3 默认的 4dp（CornerExtraSmall），比原来收藏页那版还小一档。
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
