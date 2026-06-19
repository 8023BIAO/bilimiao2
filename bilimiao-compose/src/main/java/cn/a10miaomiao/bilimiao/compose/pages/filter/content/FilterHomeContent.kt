package cn.a10miaomiao.bilimiao.compose.pages.filter.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.preference.rememberPreferenceFlow
import cn.a10miaomiao.bilimiao.compose.components.preference.textIntPreference
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.preference
import me.zhanghai.compose.preference.switchPreference
import org.kodein.di.compose.rememberInstance

@Composable
fun FilterHomeContent() {
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = remember { SettingPreferences.run { context.dataStore } }

    var showCommentFilterDialog by remember { mutableStateOf(false) }
    val commentBlockedWords by dataStore.data.map {
        it[SettingPreferences.CommentBlockedWords] ?: emptySet()
    }.collectAsState(initial = emptySet())

    ProvidePreferenceLocals(
        flow = rememberPreferenceFlow(dataStore)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = windowInsets.leftDp.dp,
                    end = windowInsets.rightDp.dp,
                )
        ) {

            item {
                Spacer(modifier = Modifier.height(10.dp))
            }

            // === 评论区评论关键字 ===
            preference(
                key = "comment_blocked_words",
                title = { Text("评论区关键字") },
                summary = { Text("屏蔽评论关键字") },
                onClick = { showCommentFilterDialog = true }
            )

            textIntPreference(
                key = SettingPreferences.VideoMinDuration.name,
                defaultValue = SettingConstants.VIDEO_MIN_DURATION_DEFAULT,
                title = { Text("最小视频时长过滤") },
                summary = { Text("过滤短视频，0=不过滤") },
                label = "秒",
            )
            textIntPreference(
                key = SettingPreferences.VideoMinPlayCount.name,
                defaultValue = SettingConstants.VIDEO_MIN_PLAY_COUNT_DEFAULT,
                title = { Text("最小播放量过滤") },
                summary = { Text("过滤低播放量视频，0=不过滤") },
                label = "个",
            )
            switchPreference(
                key = SettingPreferences.VideoHideCover.name,
                title = { Text("不显示封面") },
                summary = { Text("在推荐列表隐藏视频封面图") },
                defaultValue = false,
            )
            switchPreference(
                key = SettingPreferences.VideoHideRelates.name,
                title = { Text("隐藏相关推荐") },
                summary = { Text("视频详情页不再显示推荐视频列表") },
                defaultValue = false,
            )

            // 【已移除】"白名单" 分类标题 — 不需要隔开
            switchPreference(
                key = SettingPreferences.FollowWhitelistEnabled.name,
                title = { Text("已关注UP主白名单") },
                summary = { Text("开启后，已关注的UP主不受任何屏蔽规则影响") },
                defaultValue = false,
            )
            switchPreference(
                key = SettingPreferences.BlockPromotion.name,
                title = { Text("屏蔽推广视频") },
                summary = { Text("直接过滤B站推广和广告类视频（非av类型卡片）") },
                defaultValue = false,
            )

            item("bottom") {
                Spacer(
                    modifier = Modifier.height(windowInsets.bottomDp.dp)
                )
            }
        }
    }

    // 评论区屏蔽关键字弹出编辑
    if (showCommentFilterDialog) {
        var newKeyword by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCommentFilterDialog = false },
            title = { Text("评论区评论关键字") },
            text = {
                Column {
                    Text(
                        text = "屏蔽评论关键字",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    commentBlockedWords.forEach { kw ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = kw)
                            TextButton(onClick = {
                                scope.launch {
                                    SettingPreferences.edit(context) {
                                        it[SettingPreferences.CommentBlockedWords] = commentBlockedWords - kw
                                    }
                                }
                            }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (commentBlockedWords.isEmpty()) {
                        Text("暂无屏蔽关键字", color = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = newKeyword,
                        onValueChange = { newKeyword = it },
                        label = { Text("新关键字") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default.copy(
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (newKeyword.isNotBlank()) {
                                    scope.launch {
                                        SettingPreferences.edit(context) {
                                            it[SettingPreferences.CommentBlockedWords] = commentBlockedWords + newKeyword.trim()
                                        }
                                        newKeyword = ""
                                    }
                                }
                            }
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "注：支持正则表达式（语法：/正则表达式主体/）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newKeyword.isNotBlank()) {
                        scope.launch {
                            SettingPreferences.edit(context) {
                                it[SettingPreferences.CommentBlockedWords] = commentBlockedWords + newKeyword.trim()
                            }
                            newKeyword = ""
                        }
                    } else {
                        showCommentFilterDialog = false
                    }
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCommentFilterDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
