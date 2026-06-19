package cn.a10miaomiao.bilimiao.compose.pages.user

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.R
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.a10miaomiao.bilimiao.comm.entity.MessageInfo
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.mypage.myMenu
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.store.WindowStore
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
class UserFollowPage(
    private val id: String
) : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel = diViewModel<UserFollowPageViewModel>()
        val mid = id
        LaunchedEffect(mid) {
            viewModel.mid = mid
        }
        UserFollowPageContent(viewModel = viewModel)
    }
}

private class UserFollowPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val fragment by instance<Fragment>()
    private val pageNavigation by instance<PageNavigation>()
    private val activity by instance<Activity>()
    private val userStore by instance<UserStore>()

    var mid = "0"
        set(value) {
            if (field != value) {
                field = value
                loadData(1)
            }
        }

    val isRefreshing = MutableStateFlow(false)
    val listState = MutableStateFlow(LazyListState(0, 0))
    val list = FlowPaginationInfo<FollowingItemInfo>()

    val orderType = MutableStateFlow("attention")
    val orderTypeToNameMap = mapOf(
        "attention" to "最常访问",
        "" to "关注顺序",
    )

    fun changeOrderType(value: String) {
        if (orderType.value != value) {
            orderType.value = value
            list.data.value = emptyList()
            list.finished.value = false
            list.fail.value = ""
            loadData(1)
        }
    }

    fun loadData(
        pageNum: Int = list.pageNum
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            list.loading.value = true
            val res = BiliApiService.userRelationApi
                .followings(
                    mid = mid,
                    pageNum = pageNum,
                    pageSize = list.pageSize,
                    order = orderType.value
                )
                .awaitCall()
                .json<ResponseData<FollowingsInfo>>()
            if (res.isSuccess) {
                list.pageNum = pageNum
                val result = res.requireData()
                list.finished.value = res.data == null || result.list.isEmpty()
                if (res.data != null) {
                    if (pageNum == 1) {
                        list.data.value = result.list
                    } else {
                        list.data.value = mutableListOf<FollowingItemInfo>().apply {
                            addAll(list.data.value)
                            addAll(result.list)
                        }
                    }
                    list.finished.value = result.list.size < list.pageSize
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

    fun attention(
        index: Int,
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            if (!userStore.isLogin()) {
                withContext(Dispatchers.Main) {
                    toast("请先登录")
                }
                return@launch
            }
            val item = list.data.value[index]
            val mode = if (item.isFollowing) {
                2
            } else {
                1
            }
            val newAttribute = if (item.isFollowing) {
                0
            } else {
                2
            }
            val res = BiliApiService.userRelationApi
                .modify(item.mid, mode)
                .awaitCall().json<MessageInfo>()
            if (res.code == 0) {
                list.data.value = list.data.value.map {
                    if (item.mid == it.mid) {
                        it.copy(attribute = newAttribute)
                    } else {
                        it
                    }
                }
                withContext(Dispatchers.Main) {
                    toast(
                        if (mode == 2) {
                            "已取消关注"
                        } else {
                            "关注成功"
                        }
                    )
                }
            } else {
                withContext(Dispatchers.Main) {
                    toast(res.message)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                toast("网络错误")
            }
            e.printStackTrace()
        }
    }

    fun toUserDetailPage(id: String) {
        pageNavigation.navigate(UserSpacePage(id))
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun UserFollowPageContent(
    viewModel: UserFollowPageViewModel,
) {
    val userStore: UserStore by rememberInstance()
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val list by viewModel.list.data.collectAsState()
    val listLoading by viewModel.list.loading.collectAsState()
    val listFinished by viewModel.list.finished.collectAsState()
    val listFail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isLogin = userStore.isLogin()

    val orderTypeToNameMap = viewModel.orderTypeToNameMap
    val orderType by viewModel.orderType.collectAsState()

    val pageConfigId = PageConfig(
        title = if (userStore.isSelf(viewModel.mid)) {
            "我的关注"
        } else {
            "Ta的关注"
        },
        menu = remember(orderType) {
            myMenu {
                myItem {
                    key = 1
                    iconFileName = "ic_baseline_filter_list_grey_24"
                    title = orderTypeToNameMap[orderType]
                }
            }
        }
    )
    PageListener(
        pageConfigId,
        onMenuItemClick = fun(_, item) {
            val key = item.key ?: return
            if (key == 1) {
                val newOrderType = if (orderType == "attention") "" else "attention"
                viewModel.changeOrderType(newOrderType)
            }
        }
    )

    SwipeToRefresh(
        refreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
    ) {
        LazyColumn(
            modifier = Modifier.padding(
                top = windowInsets.topDp.dp,
                start = windowInsets.leftDp.dp,
                end = windowInsets.rightDp.dp,
            )
        ) {
            items(list.size, key = { list[it].mid }) { index ->
                val follow = list[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toUserDetailPage(follow.mid) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlideImage(
                        model = UrlUtil.autoHttps(follow.face) + "@200w_200h",
                        loading = placeholder(R.drawable.bili_akari_img),
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = follow.uname,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (follow.sign.isNotEmpty()) {
                            Text(
                                text = follow.sign,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.outline,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    TextButton(
                        onClick = { viewModel.attention(index) },
                        enabled = isLogin,
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (follow.isFollowing)
                                MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                            contentColor = if (follow.isFollowing)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(
                            if (!isLogin) "未登录"
                            else if (follow.isFollowing) "已关注"
                            else "关注"
                        )
                    }
                }
            }
            item {
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
