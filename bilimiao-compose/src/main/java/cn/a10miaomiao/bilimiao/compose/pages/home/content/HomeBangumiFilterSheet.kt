package cn.a10miaomiao.bilimiao.compose.pages.home.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.components.dialogs.AutoSheetDialog
import com.a10miaomiao.bilimiao.comm.entity.bangumi.PgcIndexConditionData
import com.a10miaomiao.bilimiao.comm.entity.bangumi.PgcConditionOrder
import com.a10miaomiao.bilimiao.comm.entity.bangumi.PgcConditionFilter
import com.a10miaomiao.bilimiao.comm.entity.bangumi.PgcConditionValue

/**
 * 番剧/影视首页筛选弹窗（动态渲染 B站 API 返回的筛选条件）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeBangumiFilterSheet(
    conditionData: PgcIndexConditionData,
    currentParams: Map<String, String>,
    onApply: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var tempParams by remember { mutableStateOf(currentParams.toMap()) }

    AutoSheetDialog(
        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 自适应高度，横屏不溢出
                .padding(horizontal = 16.dp)
        ) {
            // 标题
            Text(
                text = "筛选",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 可滚动筛选内容（占满剩余空间，按钮始终固定在底部）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // ── 排序（order） ──
                val orders = conditionData.order
                if (orders.isNotEmpty()) {
                    Text(
                        text = "排序方式",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        orders.forEach { order ->
                            val selected = tempParams["order"] == order.field
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    tempParams = tempParams.toMutableMap().apply {
                                        put("order", order.field)
                                    }
                                },
                                label = { Text(order.name) },
                            )
                        }
                    }
                }

                // ── 筛选分类（filter） ──
                conditionData.filter.forEach { filter ->
                    val values = filter.values
                    if (values.isNotEmpty()) {
                        Text(
                            text = filter.name.ifBlank { filter.field },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            values.forEach { value ->
                                val selected = tempParams[filter.field] == value.keyword
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        tempParams = tempParams.toMutableMap().apply {
                                            put(filter.field, value.keyword)
                                        }
                                    },
                                    label = { Text(value.name) },
                                )
                            }
                        }
                    }
                }
            }

            // ── 底部按钮 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        // 重置为默认值（每种第一个）
                        val defaults = mutableMapOf<String, String>()
                        conditionData.order.firstOrNull()?.let {
                            defaults["order"] = it.field
                        }
                        conditionData.filter.forEach { filter ->
                            filter.values.firstOrNull()?.let {
                                defaults[filter.field] = it.keyword
                            }
                        }
                        tempParams = defaults
                    }
                ) {
                    Text("重置")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        onApply(tempParams)
                        onDismiss()
                    }
                ) {
                    Text("确定")
                }
            }
        }
    }
}
