package cn.a10miaomiao.bilimiao.compose.pages.video.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cn.a10miaomiao.bilimiao.compose.components.miao.MiaoTitleBar
import cn.a10miaomiao.bilimiao.compose.pages.community.MainReplyViewModel


@Composable
fun VideoReplyTitleBar(
    modifier: Modifier = Modifier,
    viewModel: MainReplyViewModel,
    count: Int = -1,
) {
    val sortOrder by viewModel.sortOrder.collectAsState()
    val currentLabel = viewModel.sortOrderList.find { it.first == sortOrder }?.second ?: "排序"

    MiaoTitleBar(
        modifier = modifier,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "视频评论",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (count > 0) {
                    Text(
                        text = "($count)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        },
        action = {
            if (viewModel.isLogin()) {
                IconButton(
                    onClick = viewModel::openReplyDialog
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Comment,
                        contentDescription = "发布评论",
                    )
                }
            }
            IconButton(
                onClick = {
                    val next = viewModel.sortOrderList
                        .firstOrNull { it.first != sortOrder }
                        ?: viewModel.sortOrderList.first()
                    viewModel.setSortOrder(next.first)
                }
            ) {
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    )
}