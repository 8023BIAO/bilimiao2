package cn.a10miaomiao.bilimiao.compose.pages.download.components

import androidx.compose.foundation.layout.fillMaxWidth
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

/**
 * 下载相关界面共用的搜索框。
 *
 * 2026-10-02 从 `DownloadListPage` 的内联实现抽出来复用（样式逐字保留，含右侧清空按钮）——
 * 现在的使用方：下载列表页、视频页的下载面板（分P / 合集两个 tab 各一个）。
 * 加它的动因：几千集的合集靠手划翻不动，想按关键字把要下的几条筛出来勾选。
 */
@Composable
internal fun DownloadSearchBox(
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
            // ★叉号常驻（不随"有没有输入"显隐）：用户明确要求几个搜索框都要有这个"一键清空"，
            //   没输入时点它等于空操作，不会出错。
            IconButton(onClick = { onValueChange("") }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "清空",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
