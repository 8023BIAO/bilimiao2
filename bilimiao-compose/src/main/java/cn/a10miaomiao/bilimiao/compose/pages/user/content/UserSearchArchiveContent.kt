package cn.a10miaomiao.bilimiao.compose.pages.user.content

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import bilibili.app.interfaces.v1.SearchArchiveReq
import bilibili.app.interfaces.v1.SpaceGRPC
import cn.a10miaomiao.bilimiao.compose.common.constant.PageTabIds
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.emitter.EmitterAction
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localEmitter
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.video.VideoItemBox
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSearchSort
import com.a10miaomiao.bilimiao.comm.entity.comm.PaginationInfo
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

private typealias ArchiveItem = bilibili.app.archive.v1.Arc

private class UserSearchArchiveContentViewModel(
    override val di: DI,
    private val mid: Long,
    private val keyword: String,
    private val rankOrder: MutableStateFlow<String>,
) : ViewModel(), DIAware {

    private val pageNavigation: PageNavigation by instance()

    val isRefreshing = MutableStateFlow(false)
    val list = FlowPaginationInfo<ArchiveItem>()

    init {
        loadData(1)
    }

    private fun loadData(
        pageNum: Int = list.pageNum
    ) = viewModelScope.launch(Dispatchers.IO){
        try {
            list.loading.value = true
            list.fail.value = ""   // 开始加载就清掉上一次的失败提示
            val req = SearchArchiveReq(
                keyword = keyword,
                mid = mid,
                pn = pageNum.toLong(),
                ps = list.pageSize.toLong(),
            )
            val res = BiliGRPCHttp.request {
                SpaceGRPC.searchArchive(req)
            }.awaitCall()
            val archivesList = res.archives.map {
                it.archive
            }.filterNotNull()
            if (pageNum == 1) {
                list.data.value = archivesList
            } else {
                list.data.value = list.data.value
                    .toMutableList()
                    .apply { addAll(archivesList) }
            }
            // 客户端排序（接口 SearchArchiveReq 没有排序字段）
            list.data.value = UserSearchSort.sort(list.data.value, rankOrder.value).toMutableList()
            // 必须回写页码：否则 loadMore 永远请求第 2 页 → 同一页反复追加 + LazyColumn key 重复崩溃
            list.pageNum = pageNum
            list.finished.value = archivesList.size < list.pageSize
        } catch (e: Exception) {
            e.printStackTrace()
            list.fail.value = e.message ?: e.toString()
        } finally {
            list.loading.value = false
            isRefreshing.value = false
        }
    }

    fun tryAgainLoadData() = loadData()

    /**
     * 切换排序菜单时调用：就地重排已有数据。
     * 之前只更新了 rankOrder 状态，没有任何地方消费它 → 排序菜单点了没反应。
     */
    fun applySort() {
        list.data.value = UserSearchSort.sort(list.data.value, rankOrder.value).toMutableList()
    }

    fun refresh() {
        isRefreshing.value = true
        list.reset()
        loadData(1)
    }

    fun loadMore() {
        if (!list.finished.value && !list.loading.value) {
            loadData(list.pageNum + 1)
        }
    }

    fun toDetailPage(
        item: ArchiveItem
    ) {
        pageNavigation.navigateToVideoInfo(item.aid.toString())
    }

}

@Composable
fun UserSearchArchiveContent(
    mid: Long,
    keyword: String,
    rankOrder: String,
) {
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val _rankOrder = remember { MutableStateFlow(rankOrder) }

    val viewModel = diViewModel(
        key = "${PageTabIds.UserSearchArchive}:$mid:$keyword"
    ) {
        UserSearchArchiveContentViewModel(it, mid, keyword, _rankOrder)
    }

    // 排序菜单（最新发布/最多播放/最旧发布）切换 → 本地重排列表。
    // 之前这里只更新 _rankOrder，没有触发任何刷新，所以三个排序项看着像没反应。
    // 首次组合时两者相等，跳过（loadData 已经按当前排序排好）。
    LaunchedEffect(rankOrder) {
        if (_rankOrder.value != rankOrder) {
            _rankOrder.value = rankOrder
            viewModel.applySort()
        }
    }

    val listFlow = viewModel.list
    val list by listFlow.data.collectAsStateWithLifecycle()
    val listLoading by listFlow.loading.collectAsStateWithLifecycle()
    val listFinished by listFlow.finished.collectAsStateWithLifecycle()
    val listFail by listFlow.fail.collectAsStateWithLifecycle()

    val emitter = localEmitter()
    val listState = rememberLazyGridState()
    LaunchedEffect(Unit) {
        emitter.collectAction<EmitterAction.DoubleClickTab> {
            if (it.tab == PageTabIds.UserSearchArchive) {
                if (listState.firstVisibleItemIndex == 0) {
                    viewModel.refresh()
                } else {
                    listState.animateScrollToItem(0)
                }
            }
        }
    }
    LazyVerticalGrid(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        columns = GridCells.Adaptive(300.dp),
        contentPadding = windowInsets.toPaddingValues(
            top = 0.dp,
        )
    ) {
        items(list, key = { it.aid }) {
            VideoItemBox(
                modifier = Modifier.padding(10.dp),
                title = it.title,
                pic = it.pic,
                playNum = it.stat?.view.toString(),
                damukuNum = it.stat?.danmaku.toString(),
                remark = NumberUtil.converCTime(it.ctime),
                duration = NumberUtil.converDuration(it.duration),
                isHtml = true,
                onClick = {
                    viewModel.toDetailPage(it)
                }
            )
        }
        item(
            span = { GridItemSpan(maxLineSpan) }
        ) {
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