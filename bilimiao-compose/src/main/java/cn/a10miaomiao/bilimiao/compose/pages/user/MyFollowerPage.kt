package cn.a10miaomiao.bilimiao.compose.pages.user

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.R
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.status.BiliFailBox

import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.entity.MessageInfo
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance


@Serializable
class MyFollowerPage(
    val vmid: String? = null,
) : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel = diViewModel<MyFollowerViewModel>()
        LaunchedEffect(vmid) {
            viewModel.targetMid = vmid
        }
        MyFollowerContent(viewModel)
    }
}

private class MyFollowerViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val fragment by instance<Fragment>()
    private val pageNavigation by instance<PageNavigation>()
    private val activity by instance<Activity>()

    var targetMid: String? = null
        set(value) {
            if (field != value) {
                field = value
                loadData(1)
            }
        }

    val isOwner: Boolean
        get() = targetMid == null || targetMid == BilimiaoCommApp.commApp.loginInfo?.token_info?.mid?.toString()

    val isRefreshing = MutableStateFlow(false)
    val listState = MutableStateFlow(LazyListState(0, 0))
    val list = FlowPaginationInfo<FollowerUserInfo>()

    fun loadData(
        pageNum: Int = list.pageNum
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            list.loading.value = true
            val mid = targetMid ?: BilimiaoCommApp.commApp.loginInfo?.token_info?.mid?.toString() ?: return@launch
            val res = BiliApiService.userRelationApi
                .followers(mid = mid, pageNum = pageNum, pageSize = list.pageSize)
                .awaitCall()
                .json<ResponseData<FollowerListResult>>()
            if (res.isSuccess) {
                list.pageNum = pageNum
                val result = res.requireData()
                if (result != null) {
                    if (pageNum == 1) {
                        list.data.value = result.list.distinctBy { it.mid }
                    } else {
                        list.data.value = (list.data.value + result.list).distinctBy { it.mid }
                    }
                    list.finished.value = result.list.size < list.pageSize
                } else {
                    list.finished.value = true
                }
            } else {
                list.fail.value = res.message
            }
        } catch (e: Exception) {
            list.fail.value = "网络请求失败"
        } finally {
            list.loading.value = false
            isRefreshing.value = false
        }
    }

    fun loadMore() {
        if (!list.finished.value && !list.loading.value) {
            loadData(list.pageNum + 1)
        }
    }

    fun refresh() {
        isRefreshing.value = true
        list.finished.value = false
        list.fail.value = ""
        loadData(1)
    }

    fun removeFollower(target: FollowerUserInfo) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val res = BiliApiService.userRelationApi
                .removeFollower(fid = target.mid.toString())
                .awaitCall()
                .json<MessageInfo>()
            if (res.code == 0) {
                list.data.value = list.data.value.filter { it.mid != target.mid }
                withContext(Dispatchers.Main) {
                    toast("已移除粉丝 ${target.uname}")
                }
            } else {
                withContext(Dispatchers.Main) {
                    toast(res.message)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                toast("操作失败: ${e.message}")
            }
        }
    }

    fun toUserSpace(mid: Long) {
        pageNavigation.navigate(UserSpacePage(id = mid.toString()))
    }
}

@Serializable
data class FollowerUserInfo(
    val mid: Long = 0,
    val uname: String = "",
    val face: String = "",
    val sign: String = "",
    val mtime: Long = 0,
)

@Serializable
data class FollowerListResult(
    val list: List<FollowerUserInfo> = emptyList(),
    val total: Int = 0,
)

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun MyFollowerContent(viewModel: MyFollowerViewModel) {
    PageConfig(title = if (viewModel.isOwner) "我的粉丝" else "TA的粉丝")
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val listData by viewModel.list.data.collectAsState()
    val loading by viewModel.list.loading.collectAsState()
    val finished by viewModel.list.finished.collectAsState()
    val fail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    var showRemoveDialog by rememberSaveable { mutableStateOf<FollowerUserInfo?>(null) }

    if (fail.isNotEmpty() && listData.isEmpty()) {
        BiliFailBox(
            e = fail,
            modifier = Modifier
                .fillMaxSize()
                .padding(windowInsets.toPaddingValues())
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = windowInsets.topDp.dp,
                start = windowInsets.leftDp.dp,
                end = windowInsets.rightDp.dp,
            ),
    ) {
        items(listData, key = { it.mid }) { follower ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlideImage(
                    model = UrlUtil.autoHttps(follower.face) + "@200w_200h",
                    loading = placeholder(R.drawable.bili_akari_img),
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { viewModel.toUserSpace(follower.mid) }
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 8.dp)
                        .clickable { viewModel.toUserSpace(follower.mid) },
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = follower.uname,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (follower.sign.isNotEmpty()) {
                        Text(
                            text = follower.sign,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (viewModel.isOwner) {
                    TextButton(
                        onClick = { showRemoveDialog = follower },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("移除")
                    }
                }
            }
        }
        item {
            ListStateBox(
                loading = loading,
                finished = finished,
                fail = fail,
                listData = listData,
                loadMore = { viewModel.loadMore() },
            )
        }
    }

    showRemoveDialog?.let { target ->
        AlertDialog(
            onDismissRequest = { showRemoveDialog = null },
            title = { Text("移除粉丝") },
            text = { Text("确定要移除粉丝「${target.uname}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeFollower(target)
                        showRemoveDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("移除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = null }) {
                    Text("取消")
                }
            }
        )
    }
}
