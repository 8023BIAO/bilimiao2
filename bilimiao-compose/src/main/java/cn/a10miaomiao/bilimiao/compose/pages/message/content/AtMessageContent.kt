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
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.message.AtMessageInfo
import com.a10miaomiao.bilimiao.comm.entity.message.MessageCursorInfo
import com.a10miaomiao.bilimiao.comm.entity.message.MessageResponseInfo
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

private class AtMessageContentModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val pageNavigation by instance<PageNavigation>()
    private val messageStore by instance<MessageStore>()

    val isRefreshing = MutableStateFlow(false)
    val list = FlowPaginationInfo<AtMessageInfo>()
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
                .at(id, time)
                .awaitCall()
                .json<ResponseData<MessageResponseInfo<AtMessageInfo>>>()
            if (res.isSuccess) {
                messageStore.clearAtUnread()
                _cursor = res.requireData().cursor
                if (id == 0L) {
                    list.data.value = res.requireData().items
                } else {
                    list.data.value = mutableListOf<AtMessageInfo>().apply {
                        addAll(list.data.value)
                        addAll(res.requireData().items)
                    }
                }
                list.finished.value = res.requireData().items.isEmpty()
            } else {
                list.fail.value = res.message
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

    fun toUserPage(item: AtMessageInfo) {
        val mid = item.user.mid
        pageNavigation.navigate(UserSpacePage(mid.toString()))
    }

    fun toMessagePage(item: AtMessageInfo) {
        // 评论
        if (item.item.type == "reply") {
            val sourceId = item.item.source_id
            var enterUrl = ""
            if (item.item.business_id == 1) {
                val videoPageUrl = "bilimiao://video/${item.item.subject_id}"
                enterUrl = videoPageUrl
            }
            pageNavigation.navigate(ReplyDetailListPage(
                id = sourceId.toString(),
                enterUrl = enterUrl,
            ))
        } else {
            BilibiliNavigation.navigationTo(pageNavigation, item.item.uri)
        }
    }

    fun toDetailPage(item: AtMessageInfo) {
        val type = item.item.type
        if (item.item.business_id == 1) {
            val aid = item.item.subject_id
            val targetId = item.item.target_id
            val videoPageUrl = "bilimiao://video/$aid}"
            if (targetId == 0L) {
                pageNavigation.navigateToVideoInfo(aid.toString())
            } else {
                pageNavigation.navigate(ReplyDetailListPage(
                    id = targetId.toString(),
                    enterUrl = videoPageUrl,
                ))
            }
        } else {
            BilibiliNavigation.navigationTo(pageNavigation, item.item.uri)
        }
    }

    fun removeItem(id: Long, index: Int) = viewModelScope.launch(Dispatchers.IO) {
        try {
            BiliApiService.messageApi
                .delMsgfeed(2, id)
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
internal fun AtMessageContent() {
    val viewModel: AtMessageContentModel = diViewModel()
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())


    val list by viewModel.list.data.collectAsState()
    val listLoading by viewModel.list.loading.collectAsState()
    val listFinished by viewModel.list.finished.collectAsState()
    val listFail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val deleteTarget = remember { mutableStateOf<AtMessageInfo?>(null) }

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
                            MessageItemBox(
                                avatar = item.user.avatar,
                                nickname = item.user.nickname,
                                actionText = "在${item.item.business}中@了我",
                                title = item.item.title,
                                sourceContent = item.item.source_content,
                                time = item.at_time,
                                onUserClick = {
                                    viewModel.toUserPage(item)
                                },
                                onDetailClick = {
                                    viewModel.toDetailPage(item)
                                },
                                onMessageClick = {
                                    viewModel.toMessagePage(item)
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