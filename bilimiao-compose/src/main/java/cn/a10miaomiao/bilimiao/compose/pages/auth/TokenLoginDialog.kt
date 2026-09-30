package cn.a10miaomiao.bilimiao.compose.pages.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.components.dialogs.AutoSheetDialog

/**
 * Token / Cookie 登录表单浮层。
 *
 * 单独一个文件的原因：登录页自己用的是页面内的 MessageDialogState（提示/结果卡），
 * 表单类浮层按 UI 规则必须走 [AutoSheetDialog] —— 两种弹窗机制别混在同一个文件里。
 *
 * 结构照申诉弹窗：标题 → 可滚动内容 → 右下按钮行。
 */
@Composable
fun TokenLoginDialog(
    loading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    AutoSheetDialog(
        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        onDismiss = onDismiss,
    ) {
        TokenLoginSheet(
            loading = loading,
            onDismiss = onDismiss,
            onConfirm = onConfirm,
        )
    }
}

@Composable
private fun TokenLoginSheet(
    loading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .imePadding(),
    ) {
        Text(
            text = "Token 登录",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "粘贴 access_token；也可以粘贴含 SESSDATA 的 Cookie",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        // 内容可滚动（弹窗规则要求）；刻意不用 weight(1f)：外壳 Box 是全屏有界的，
        // weight 会把浮层内容区撑到可用全高（弹窗被顶满）；这张表单内容本来就短，
        // 让它按内容撑开、内容区自己 verticalScroll 即可。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                minLines = 3,
                maxLines = 8,
                placeholder = { Text("access_token 或 Cookie") },
            )
            Text(
                // ≤20 字符的一行小字：只说结论，不解释原因
                text = "Cookie 仅网页接口，用 Token",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = { onConfirm(input.trim()) },
                enabled = !loading && input.isNotBlank(),
            ) {
                Text("登录")
            }
        }
    }
}
