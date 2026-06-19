package cn.a10miaomiao.bilimiao.compose.pages.dynamic.content

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bilibili.app.dynamic.v2.DynamicGRPC
import bilibili.app.dynamic.v2.DynamicItem
import bilibili.app.dynamic.v2.UpListItem
import cn.a10miaomiao.bilimiao.compose.common.addPaddingValues
import cn.a10miaomiao.bilimiao.compose.common.constant.PageTabIds
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.emitter.EmitterAction
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localEmitter
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.dyanmic.DynamicItemCard
import cn.a10miaomiao.bilimiao.compose.components.dyanmic.DynamicModuleAuthorBox
import cn.a10miaomiao.bilimiao.compose.components.dyanmic.DynamicModuleStatBox
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.components.video.VideoItemBox
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.BangumiDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.dynamic.DynamicVideoContentInfo
import cn.a10miaomiao.bilimiao.compose.pages.dynamic.DynamicVideoInfo
import cn.a10miaomiao.bilimiao.compose.pages.dynamic.DynamicViewModel
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.store.FilterStore
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance


class DynamicAllListContenttViewModel(
    override val di: DI,
    val dynamicViewModel: DynamicViewModel,
) : ViewModel(), DIAware {

    private val pageNavigation: PageNavigation by instance()
    private val filterStore: FilterStore by instance()
    private val userStore: UserStore by instance()

    // 保存列表滚动位置，防止进入评论区返回后跳顶
    var savedListIndex = -1
    var savedListOffset = 0

    private var _offset = ""
    private var _baseline = ""
    val list = FlowPaginationInfo<DynamicItem>()
    val isRefreshing = MutableStateFlow(false)

    init {
        loadData("")
    }


    private fun loadData(
        offset: String = _offset
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            list.loading.value = true
            val type = if (offset.isBlank()) {
                bilibili.app.dynamic.v2.Refresh.NEW
            } else {
                bilibili.app.dynamic.v2.Refresh.HISTORY
            }
            val req = bilibili.app.dynamic.v2.DynAllReq(
                refreshType = type,
                localTime = 8,
                offset = offset,
                updateBaseline = _baseline,
                from = "3", // 设置客户端来源，可能影响内容获取
                coldStart = 1, // 冷启动，可能加载更多内容类型
                tabRecallType = 1, // 标签召回类型，可能包含订阅内容
                tabRecallUid = userStore.state.info?.mid ?: 0L, // 设置当前用户UID
            )
            val result = BiliGRPCHttp.request {
                DynamicGRPC.dynAll(req)
            }.awaitCall()
            val dynamicList = result.dynamicList
            result.upList?.let {
                val upList = mutableListOf<UpListItem>()
                upList.addAll(it.list)
                upList.addAll(it.listSecond)
                dynamicViewModel.setUpList(upList)
            }
            if (dynamicList != null) {
                _offset = dynamicList.historyOffset
                _baseline = dynamicList.updateBaseline
                val itemsList = dynamicList.list.filter { item ->
                    // 过滤投稿视频：视频内容在「视频」tab查看，避免feed重复
                    val dynType = item.modules
                        .firstOrNull { it.moduleDynamic != null }
                        ?.moduleDynamic?.type
                    dynType != bilibili.app.dynamic.v2.ModuleDynamicType.MDL_DYN_ARCHIVE
                }
                if (offset.isBlank()) {
                    list.data.value = itemsList
                } else {
                    list.data.value = list.data.value
                        .toMutableList()
                        .apply { addAll(itemsList) }
                }
            } else {
                list.data.value = listOf()
                list.finished.value = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            list.fail.value = e.message ?: e.toString()
            list.loading.value = false
        } finally {
            list.loading.value = false
            isRefreshing.value = false
        }
    }


    fun tryAgainLoadData() {
        if (!list.loading.value && !list.finished.value) {
            loadData()
        }
    }

    fun loadMore() {
        if (!list.loading.value && !list.finished.value) {
            loadData(_offset)
        }
    }

    fun refresh() {
        list.reset()
        isRefreshing.value = true
        loadData("")
    }

    fun toDetailPage(item: DynamicItem) {
        val extend = item.extend ?: return
        val toUrl = extend.cardUrl
        try {
            pageNavigation.navigateByUri(Uri.parse(toUrl))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun DynamicAllListContent(
    dynamicViewModel: DynamicViewModel,
) {
    val viewModel = diViewModel(
        key = "dynamic-all-list"
    ) {
        DynamicAllListContenttViewModel(it, dynamicViewModel)
    }
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val list by viewModel.list.data.collectAsState()
    val listLoading by viewModel.list.loading.collectAsState()
    val listFinished by viewModel.list.finished.collectAsState()
    val listFail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val listState = rememberLazyListState()
    val emitter = localEmitter()

    // 恢复滚动位置
    LaunchedEffect(Unit) {
        val idx = viewModel.savedListIndex
        if (idx >= 0) {
            listState.scrollToItem(idx, viewModel.savedListOffset)
        }
    }
    // 离开时保存滚动位置
    DisposableEffect(Unit) {
        onDispose {
            viewModel.savedListIndex = listState.firstVisibleItemIndex
            viewModel.savedListOffset = listState.firstVisibleItemScrollOffset
        }
    }

    LaunchedEffect(Unit) {
        emitter.collectAction<EmitterAction.DoubleClickTab> {
            if (it.tab == PageTabIds.DynamicAll) {
                if (listState.firstVisibleItemIndex == 0) {
                    viewModel.refresh()
                } else {
                    listState.animateScrollToItem(0)
                }
            }
        }
    }

    SwipeToRefresh(
        refreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = windowInsets.addPaddingValues(
                addTop = -windowInsets.topDp.dp + 10.dp,
                addBottom = 10.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list) {
                DynamicItemCard(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth(),
                    item = it,
                    onClick = {
                        viewModel.savedListIndex = listState.firstVisibleItemIndex
                        viewModel.savedListOffset = listState.firstVisibleItemScrollOffset
                        viewModel.toDetailPage(it)
                    },
                )
            }
            item() {
                ListStateBox(
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