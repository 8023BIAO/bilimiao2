package cn.a10miaomiao.bilimiao.compose.pages.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.nio.charset.StandardCharsets
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import com.a10miaomiao.bilimiao.comm.utils.ErrorLogCollector
import com.a10miaomiao.bilimiao.comm.utils.ErrorLogEntry
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.kodein.di.compose.rememberInstance

@Serializable
class ErrorLogPage : ComposePage() {

    companion object {
        /** Binder 事务上限约 1MB，留余量用 500KB */
        private const val MAX_CLIPBOARD_BYTES = 500 * 1024
    }

    private fun safeCopyToClipboard(cm: ClipboardManager, label: String, text: String) {
        // 用 byte 长度估算，比 char 长度更准确（Binder 按 byte 算）
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val clipped = if (bytes.size > MAX_CLIPBOARD_BYTES) {
            // 截断到安全大小（取整到 char 边界避免乱码）
            text.take(MAX_CLIPBOARD_BYTES / 4) // 最坏情况 4 byte/char
                .toByteArray(StandardCharsets.UTF_8)
                .let { safeBytes ->
                    if (safeBytes.size > MAX_CLIPBOARD_BYTES) {
                        // 进一步缩小直到安全
                        generateSequence(MAX_CLIPBOARD_BYTES / 4 - 100) { it - 100 }
                            .map { text.take(it) }
                            .first { candidate ->
                                candidate.toByteArray(StandardCharsets.UTF_8).size <= MAX_CLIPBOARD_BYTES
                            }
                    } else text.take(safeBytes.size.coerceAtMost(text.length))
                }
        } else text
        cm.setPrimaryClip(ClipData.newPlainText(label, clipped))
    }

    @Composable
    override fun Content() {
        PageConfig(title = "错误日志")
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
        val windowStore: WindowStore by rememberInstance()
        val windowState = windowStore.stateFlow.collectAsState().value
        val windowInsets = windowState.getContentInsets(localContainerView())

        val context = LocalContext.current
        var logs by remember { mutableStateOf<List<ErrorLogEntry>>(emptyList()) }
        var expandedIndices by remember { mutableStateOf(setOf<Int>()) }
        var showClearDialog by remember { mutableStateOf(false) }
        var selectionMode by remember { mutableStateOf(false) }
        var selectedIndices by remember { mutableStateOf(setOf<Int>()) }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                logs = ErrorLogCollector.getLogs()
            }
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("清空日志") },
                text = { Text("确定要清空所有错误日志吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        ErrorLogCollector.clearLogs()
                        logs = emptyList()
                        showClearDialog = false
                        Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) { Text("取消") }
                }
            )
        }

        Column(modifier = Modifier.fillMaxSize().padding(top = windowInsets.topDp.dp)) {
            // 顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectionMode) "已选 ${selectedIndices.size} 项" else "错误日志",
                    style = MaterialTheme.typography.titleLarge
                )
                if (logs.isNotEmpty()) {
                Row {
                    if (selectionMode) {
                        // 选择模式下的按钮
                        TextButton(onClick = {
                            if (selectedIndices.size == logs.size) {
                                selectedIndices = emptySet()
                            } else {
                                selectedIndices = logs.indices.toSet()
                            }
                        }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "全选")
                            Spacer(Modifier.width(4.dp))
                            Text(if (selectedIndices.size == logs.size) "取消" else "全选")
                        }
                        TextButton(
                            onClick = {
                                if (selectedIndices.isNotEmpty()) {
                                    val count = selectedIndices.size
                                    ErrorLogCollector.deleteLogs(selectedIndices)
                                    logs = ErrorLogCollector.getLogs()
                                    selectedIndices = emptySet()
                                    selectionMode = false
                                    Toast.makeText(context, "已删除 $count 条", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = selectedIndices.isNotEmpty()
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除选中")
                            Spacer(Modifier.width(4.dp))
                            Text("删除")
                        }
                        TextButton(onClick = {
                            selectionMode = false
                            selectedIndices = emptySet()
                        }) {
                            Text("完成")
                        }
                    } else {
                        // 普通模式下的按钮
                        TextButton(onClick = { selectionMode = true }) {
                            Text("选择")
                        }
                            IconButton(onClick = {
                            val text = logs.joinToString("\n\n---\n\n") { entry ->
                                buildString {
                                    appendLine("时间: ${entry.time}")
                                    appendLine("错误: ${entry.error}")
                                    if (entry.stackTrace.isNotBlank()) {
                                        appendLine("堆栈:")
                                        appendLine(entry.stackTrace)
                                    }
                                }
                            }
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            safeCopyToClipboard(cm, "error_logs", text)
                            Toast.makeText(context, "已复制全部日志", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "复制全部")
                        }
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "清空")
                        }
                    }
                }
                }
            }

            // 内容区
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无错误日志", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = windowInsets.bottomDp.dp + windowStore.bottomAppBarHeightDp.dp + 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(logs.indices.toList()) { index ->
                        val entry = logs[index]
                        val isExpanded = index in expandedIndices
                        val isSelected = index in selectedIndices

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            onClick = {
                                if (selectionMode) {
                                    selectedIndices = if (isSelected)
                                        selectedIndices - index
                                    else
                                        selectedIndices + index
                                }
                            }
                        ) {
                            Row(modifier = Modifier.padding(12.dp)) {
                                // 选择模式下显示复选框
                                if (selectionMode) {
                                    IconButton(
                                        onClick = {
                                            selectedIndices = if (isSelected)
                                                selectedIndices - index
                                            else
                                                selectedIndices + index
                                        },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            if (isSelected) Icons.Outlined.CheckBox
                                            else Icons.Outlined.CheckBoxOutlineBlank,
                                            contentDescription = "选择",
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                                                   else MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                // 头部
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.error,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = entry.time,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    Row {
                                        IconButton(
                                            onClick = {
                                                val text = buildString {
                                                    appendLine("时间: ${entry.time}")
                                                    appendLine("错误: ${entry.error}")
                                                    if (entry.stackTrace.isNotBlank()) {
                                                        appendLine("堆栈: ${entry.stackTrace}")
                                                    }
                                                }
                                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                safeCopyToClipboard(cm, "error_log", text)
                                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Outlined.ContentCopy,
                                                contentDescription = "复制",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                expandedIndices = if (isExpanded)
                                                    expandedIndices - index
                                                else
                                                    expandedIndices + index
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                if (isExpanded) Icons.Outlined.ExpandLess
                                                else Icons.Outlined.ExpandMore,
                                                contentDescription = if (isExpanded) "收起" else "展开",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                }

                                // 展开详情
                                if (isExpanded) {
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // 错误详情
                                    Text(
                                        "错误详情",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = entry.error,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp
                                            ),
                                            modifier = Modifier.padding(8.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    // 堆栈
                                    if (entry.stackTrace.isNotBlank() && entry.stackTrace != "null") {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "堆栈跟踪",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = entry.stackTrace,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 12.sp
                                                ),
                                                modifier = Modifier.padding(8.dp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
        } // Surface
    }
}
