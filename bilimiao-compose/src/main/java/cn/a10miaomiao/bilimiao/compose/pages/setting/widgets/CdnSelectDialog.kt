package cn.a10miaomiao.bilimiao.compose.pages.setting.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.a10miaomiao.bilimiao.comm.utils.CdnHosts
import com.a10miaomiao.bilimiao.comm.utils.CdnSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.compose.rememberInstance

@Composable
fun CdnSelectDialog(
    onDismiss: () -> Unit,
    currentCdnKey: String,
    onCdnSelected: (String) -> Unit,
) {
    val activity: FragmentActivity by rememberInstance()
    val scope = rememberCoroutineScope()

    var testing by remember { mutableStateOf(false) }  // 打开即测
    var sortedCdnList by remember {
        mutableStateOf<List<Pair<CdnHosts.CdnInfo, Long>>>(
            CdnHosts.list.map { it to -1L }
        )
    }

    // 打开弹窗时自动测速
    LaunchedEffect(Unit) {
        testing = true
        val results = withContext(Dispatchers.IO) {
            CdnSelector.testBatch(CdnHosts.list.map { c -> if (c.host.isNotEmpty() && c.host != "backup") "https://${c.host}/1" else "" })
        }
        val ranked = mutableListOf<Pair<CdnHosts.CdnInfo, Long>>()
        for (cdn in CdnHosts.list) {
            val idx = CdnHosts.list.indexOf(cdn)
            val latency = results.getOrNull(idx)?.latencyMs ?: -1L
            ranked.add(cdn to latency)
        }
        // 默认/备用保持在最前（无法预测试），其余按延迟排序
        ranked.sortWith(compareBy(
            { cdn -> if (cdn.first.key == "default" || cdn.first.key == "backup") -1 else 1 },
            { if (it.second <= 0) Long.MAX_VALUE else it.second }
        ))
        sortedCdnList = ranked
        testing = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CDN 选择") },
        text = {
            Column {
                Text(
                    "自动测速并按延迟排序。点击选择即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))

                if (testing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }

                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    itemsIndexed(sortedCdnList) { _, (cdn, latency) ->
                        val isSelected = cdn.key == currentCdnKey
                        val latencyText = when {
                            testing -> "---"
                            cdn.key == "default" || cdn.key == "backup" -> "N/A"
                            latency > 0 -> "${latency}ms"
                            else -> "超时"
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCdnSelected(cdn.key) }
                                .padding(vertical = 6.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onCdnSelected(cdn.key) }
                            )
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(
                                    cdn.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (cdn.host.isNotEmpty() && cdn.host != "backup") {
                                    Text(
                                        cdn.host,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                    )
                                }
                            }
                            // Latency
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = when {
                                    testing -> MaterialTheme.colorScheme.surfaceVariant
                                    cdn.key == "default" || cdn.key == "backup" -> MaterialTheme.colorScheme.surfaceVariant
                                    latency > 0 && latency < 200 -> MaterialTheme.colorScheme.primaryContainer
                                    latency > 0 -> MaterialTheme.colorScheme.tertiaryContainer
                                    else -> MaterialTheme.colorScheme.errorContainer
                                }
                            ) {
                                Text(
                                    latencyText,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        dismissButton = {
            // 重新测速按钮
            TextButton(
                onClick = {
                    scope.launch {
                        testing = true
                        val results = withContext(Dispatchers.IO) {
                            CdnSelector.testBatch(CdnHosts.list.map { c -> if (c.host.isNotEmpty() && c.host != "backup") "https://${c.host}/1" else "" })
                        }
                        val ranked = mutableListOf<Pair<CdnHosts.CdnInfo, Long>>()
                        for (cdn in CdnHosts.list) {
                            val idx = CdnHosts.list.indexOf(cdn)
                            val latency = results.getOrNull(idx)?.latencyMs ?: -1L
                            ranked.add(cdn to latency)
                        }
                        ranked.sortWith(compareBy({ if (it.second <= 0) Long.MAX_VALUE else it.second }, { it.first.key }))
                        sortedCdnList = ranked
                        testing = false
                    }
                },
                enabled = !testing,
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("重新测速")
            }
        },
    )
}
