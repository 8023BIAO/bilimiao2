package cn.a10miaomiao.bilimiao.compose.pages.message.content

import ReplyDetailListPage
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.common.defaultNavOptions
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.navigation.BilibiliNavigation
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.pages.message.components.MessageItemBox
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import cn.a10miaomiao.bilimiao.compose.pages.video.VideoDetailPage
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.message.LikeMessageInfo
import com.a10miaomiao.bilimiao.comm.entity.message.LikeMessageResponseInfo
import com.a10miaomiao.bilimiao.comm.entity.message.MessageCursorInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.MessageStore
import com.a10miaomiao.bilimiao.comm.utils.BiliUrlMatcher
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import com.a10miaomiao.bilimiao.comm.toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment

private class LikeMessageContentModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val pageNavigation by instance<PageNavigation>()
    private val messageStore by instance<MessageStore>()

    val isRefreshing = MutableStateFlow(false)
    val list = FlowPaginationInfo<LikeMessageInfo>()
    var _cursor: MessageCursorInfo? = null

    init {
        loadData()
    }

    fun loadData(
        id: Long = 0L,
        time: Long = 0L,
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            list.loading.value = true
            val res = BiliApiService.messageApi
                .like(id, time)
                .awaitCall()
                .json<ResultInfo<LikeMessageResponseInfo>>()
            if (res.isSuccess) {
                val d = res.data
                if (d == null) {
                    list.fail.value = "未登录账号或加载失败"
                    return@launch
                }
                messageStore.clearLikeUnread()
                val total = d.total
                _cursor = total.cursor
                if (id == 0L) {
                    list.data.value = total.items
                } else {
                    list.data.value = mutableListOf<LikeMessageInfo>().apply {
                        addAll(list.data.value)
                        addAll(total.items)
                    }
                }
                list.finished.value = total.items.isEmpty()
            } else {
                list.fail.value = res.message.ifBlank { "未登录账号或加载失败" }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (e !is java.net.UnknownHostException) {
                list.fail.value = e.message ?: e.toString()
            }
        } finally {
            list.loading.value = false
            isRefreshing.value = false
        }
    }

    fun loadMore() {
        if (
            !list.finished.value &&
            !list.loading.value
        ) {
            _cursor?.let {
                loadData(it.id, it.time)
            }
        }
    }

    fun refresh() {
        isRefreshing.value = true
        list.finished.value = false
        list.fail.value = ""
        _cursor = null
        loadData()
    }

    fun toUserPage(item: LikeMessageInfo) {
        val mid = item.users[0].mid
        pageNavigation.navigate(UserSpacePage(mid.toString()))
    }

    fun toDetailPage(item: LikeMessageInfo) {
        val type = item.item.type
        if (type == "reply") {
            // 评论
            var enterUrl = ""
            val id = item.item.item_id
            if (item.item.uri.startsWith("https://www.bilibili.com/video/")) {
                val bvID = item.item.uri.substring(31, item.item.uri.length)
                enterUrl = "bilimiao://video/${bvID}"
            }
            pageNavigation.navigate(ReplyDetailListPage(
                id = id.toString(),
                enterUrl = enterUrl,
            ))
        } else if (type == "album") {
            // 动态
        } else if (type == "danmu") {
            // 弹幕
            val (aid, seekMs) = parseDanmuUri(item.item)
            pageNavigation.navigate(VideoDetailPage(
                id = aid,
                seekPosition = seekMs,
                highlightDanmakuText = item.item.title,
            ))
        } else if (type == "video") {
            // 视频
            val aid = item.item.item_id
            pageNavigation.navigate(VideoDetailPage(
                id = aid.toString(),
            ))
        } else {
            BilibiliNavigation.navigationTo(pageNavigation, item.item.uri)
        }
    }
    fun removeItem(id: String, index: Int) = viewModelScope.launch(Dispatchers.IO) {
        try {
            BiliApiService.messageApi
                .delMsgfeed(0, id)
                .awaitCall()
            toast("删除成功")
            val current = list.data.value.toMutableList()
            if (index in current.indices && current[index].id == id) {
                current.removeAt(index)
            } else {
                current.removeAll { it.id == id }
            }
            list.data.value = current
        } catch (e: Exception) {
            e.printStackTrace()
            toast("删除失败")
        }
    }
}


@Composable
internal fun LikeMessageContent() {
    val viewModel: LikeMessageContentModel = diViewModel()
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val list by viewModel.list.data.collectAsState()
    val listLoading by viewModel.list.loading.collectAsState()
    val listFinished by viewModel.list.finished.collectAsState()
    val listFail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val deleteTarget = remember { mutableStateOf<LikeMessageInfo?>(null) }

    deleteTarget.value?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget.value = null },
            title = { Text("删除通知") },
            text = { Text("确认删除这条通知？删除后不会再次显示本条，但新的回复/@/点赞仍会出现。") },
            confirmButton = {
                TextButton(onClick = {
                    val idx = list.indexOf(target)
                    if (idx >= 0) viewModel.removeItem(target.id, idx)
                    deleteTarget.value = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget.value = null }) {
                    Text("取消")
                }
            },
        )
    }

    SwipeToRefresh(
        modifier = Modifier.padding(
            start = windowInsets.leftDp.dp,
            end = windowInsets.rightDp.dp,
        ),
        refreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
    ) {
        LazyColumn() {
            items(list.size, { list[it].id }) {
                val item = list[it]
                Column() {
                    if (it != 0) {
                        HorizontalDivider()
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val business = item.item.business
                            val (nickname, actionText) = when(item.users.size) {
                                0 -> Pair("", "零人赞了我的${business}")
                                1 ->  Pair(
                                    item.users[0].nickname,
                                    "赞了我的${business}"
                                )
                                2 ->  Pair(
                                    "${item.users[0].nickname}、${item.users[1].nickname}",
                                    "赞了我的${business}"
                                )
                                else -> Pair(
                                    "${item.users[0].nickname}、${item.users[1].nickname}",
                                    "等总计${item.counts}人赞了我的${business}"
                                )
                            }
                            MessageItemBox(
                                avatar = item.users[0]?.avatar ?: "",
                                nickname = nickname,
                                actionText = actionText,
                                title = item.item.title,
                                sourceContent = "",
                                time = item.like_time,
                                onUserClick = {
                                    viewModel.toUserPage(item)
                                },
                                onDetailClick = {
                                    viewModel.toDetailPage(item)
                                },
                                onMessageClick = {
                                }
                            )
                        }
                        IconButton(
                            onClick = { deleteTarget.value = item },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除通知",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            item() {
                ListStateBox(
                    modifier = Modifier.padding(
                        bottom = windowInsets.bottomDp.dp
                    ),
                    loading = listLoading,
                    finished = listFinished,
                    fail = listFail,
                    listData = list,
                ) {
                    viewModel.loadMore()
                }
            }
        }
    }
}

/**
 * 从 B站 弹幕消息的 URI 中提取视频ID和时间戳
 * URI 格式示例：
 *   uri: https://www.bilibili.com/video/BV1Qy9tBeEPE?dm_progress=160543&p=1&dmid=...
 *   native_uri: bilibili://video/116491072572129?dm_progress=160543&cid=...&dmid=...
 * @return Pair(视频aid字符串, seek毫秒数)
 */
private fun parseDanmuUri(item: LikeMessageInfo.ItemInfo): Pair<String, Long?> {
    // 优先从 native_uri 提取数字ID: bilibili://video/{aid}?...
    val nativeUri = item.native_uri
    if (nativeUri.isNotBlank()) {
        val uri = Uri.parse(nativeUri)
        // pathSegments: ["{aid}"]，scheme/authority 不包含在内
        val aid = uri.pathSegments?.getOrNull(0)
        if (aid != null) {
            val dmProgress = uri.getQueryParameter("dm_progress")
            val seekMs = dmProgress?.toLongOrNull() // dm_progress 单位是毫秒
            return aid to seekMs
        }
    }
    // 回退：从 uri 提取 BV，然后用 item_id（可能是 aid？实际上不是，但作为降级）
    val uri = item.uri
    if (uri.isNotBlank()) {
        val bvId = BiliUrlMatcher.findIDByUrl(uri)
        val aid = if (bvId[0] == "BV") bvId[1] else item.item_id.toString()
        val dmProgress = Uri.parse(uri).getQueryParameter("dm_progress")
        val seekMs = dmProgress?.toLongOrNull()
        return aid to seekMs
    }
    return item.item_id.toString() to null
}