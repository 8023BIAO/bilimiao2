package cn.a10miaomiao.bilimiao.compose.pages.video.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.a10miaomiao.bilimiao.comm.entity.video.AiConclusionResult

@Composable
fun VideoAiConclusionDialog(
    result: AiConclusionResult,
    onDismiss: () -> Unit,
    onSeekToTimestamp: (timestampSec: Int) -> Unit,
) {
    val summary = result.summary?.takeIf { it.isNotBlank() }
    val outlines = result.outline?.takeIf { it.isNotEmpty() }
    val clipboard = LocalClipboardManager.current

    val fullText = buildString {
        if (summary != null) {
            appendLine(summary)
            appendLine()
        }
        outlines?.forEach { o ->
            val ts = o.timestamp?.let { t -> "${t / 60}:${String.format("%02d", t % 60)} " } ?: ""
            appendLine("$ts${o.title ?: ""}")
            o.partOutline?.forEach { p ->
                val pts = p.timestamp?.let { t -> "${t / 60}:${String.format("%02d", t % 60)} " } ?: ""
                appendLine("  $pts${p.content ?: ""}")
            }
        }
    }.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("AI 视频总结", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column {
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (outlines != null) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                    }
                }
                outlines?.let { list ->
                    Text(
                        "分段大纲",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(list) { o ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { o.timestamp?.let { onSeekToTimestamp(it) } }
                                    .padding(vertical = 3.dp)
                            ) {
                                RowWithTs(o.timestamp, o.title)
                                o.partOutline?.forEach { p ->
                                    RowWithTs(
                                        timestamp = p.timestamp,
                                        title = p.content,
                                        isChild = true
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(fullText))
                onDismiss()
            }) {
                Text("复制全文")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun RowWithTs(
    timestamp: Int?,
    title: String?,
    isChild: Boolean = false,
) {
    val tsText = timestamp?.let {
        val m = it / 60
        val s = it % 60
        String.format("%d:%02d", m, s)
    } ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (isChild) 16.dp else 0.dp)
    ) {
        if (tsText.isNotBlank()) {
            Text(
                text = tsText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
