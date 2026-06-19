package cn.a10miaomiao.bilimiao.compose.pages.download.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.R
import cn.a10miaomiao.bilimiao.compose.pages.download.DownloadItemInfo
import cn.a10miaomiao.bilimiao.download.entry.CurrentDownloadInfo
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun DownloadDetailItem(
    curDownload: CurrentDownloadInfo?,
    item: DownloadItemInfo,
    onClick: () -> Unit,
    onStartClick: () -> Unit,
    onPauseClick: (taskId: Long) -> Unit,
    onDeleteClick: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = "删除视频") },
            text = { Text(text = "是否要删除「${item.title}」？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteClick()
                    },
                ) {
                    Text(
                        "删除",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                ) {
                    Text("取消")
                }
            }
        )
    }

    Box(
        modifier = Modifier.padding(5.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth()
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(10.dp),
            color = Color.Transparent
        ) {
            Column() {
                Row(
                    modifier = Modifier.padding(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlideImage(
                        model = UrlUtil.autoHttps(item.cover) + "@672w_378h_1c_",
                        contentScale = ContentScale.Crop,
                        contentDescription = null,
                        loading = placeholder(R.drawable.bili_default_placeholder_img_tv),
                        failure = placeholder(R.drawable.bili_fail_placeholder_img_tv),
                        modifier = Modifier
                            .size(width = 60.dp, height = 40.dp)
                            .clip(RoundedCornerShape(5.dp))
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 5.dp)
                    ) {
                        Text(
                            text = item.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val status = if (item.is_completed) {
                            "已完成下载"
                        } else if (item.cid == curDownload?.id) {
                            curDownload.statusText
                        } else {
                            "暂停中"
                        }
                        Text(
                            text = status,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!item.is_completed) {
                        if (item.cid == curDownload?.id && curDownload.status in 100 until  200) {
                            IconButton(onClick = { onPauseClick(curDownload.taskId) }) {
                                Icon(Icons.Filled.Pause, null)
                            }
                        } else {
                            IconButton(onClick = onStartClick) {
                                Icon(Icons.Filled.PlayArrow, null)
                            }
                        }
                    }
                    TextButton(
                        onClick = { showDeleteDialog = true },
                    ) {
                        Text(
                            "删除",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                }
                if (!item.is_completed) {
                    if (item.cid == curDownload?.id) {
                        LinearProgressIndicator(
                            progress = { curDownload.rate },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (item.total_bytes != 0L) {
                        LinearProgressIndicator(
                            progress = { item.downloaded_bytes.toFloat() / item.total_bytes.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

        }
    }
}