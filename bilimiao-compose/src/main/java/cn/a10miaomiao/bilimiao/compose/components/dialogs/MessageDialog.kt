package cn.a10miaomiao.bilimiao.compose.components.dialogs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier


@Composable
fun MessageDialog(
    state: MessageDialogState
) {
    when(val messageState = state.messageState.value) {
        MessageDialogState.NoneState -> Unit
        is MessageDialogState.AlertState -> {
            // 走覆盖层：窗口铺满屏幕、卡片高度封顶，长文本不会把"确定"顶到屏幕外
            OverlayAlertDialog(
                onDismissRequest = state::close,
                title = {
                    Text(messageState.title)
                },
                text = {
                    Text(messageState.text)
                },
                confirmButton = {
                    TextButton(
                        onClick = state::close,
                    ) {
                        Text("确定")
                    }
                }
            )
        }
        is MessageDialogState.LoadingState -> {
            OverlayAlertDialog(
                onDismissRequest = {},
                title = {
                    Text(messageState.title)
                },
                text = {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {  }
            )
        }
        is MessageDialogState.CustomState -> {
            OverlayAlertDialog(
                onDismissRequest = messageState.onDismissRequest,
                confirmButton = messageState.confirmButton,
                modifier = messageState.modifier,
                dismissButton = messageState.dismissButton,
                icon = messageState.icon,
                title = messageState.title,
                text = messageState.text,
            )
        }
    }
}
