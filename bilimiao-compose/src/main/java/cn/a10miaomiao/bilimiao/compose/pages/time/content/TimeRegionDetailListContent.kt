package cn.a10miaomiao.bilimiao.compose.pages.time.content

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.components.video.VideoItemBox
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.region.RegionVideoInfo
import com.a10miaomiao.bilimiao.comm.entity.region.RankingV2Response
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.FilterStore
import com.a10miaomiao.bilimiao.comm.store.TimeSettingStore
import com.a10miaomiao.bilimiao.comm.store.model.DateModel
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

private class TimeRegionDetailListContentViewModel(
    override val di: DI,
    private val rid: Int,
) : ViewModel(), DIAware {

    private val pageNavigation by instance<PageNavigation>()

    private val timeSettingStore: TimeSettingStore by instance()
    private val filterStore: FilterStore by instance()

    var timeFrom = DateModel()
    var timeTo = DateModel()
    var rankOrder = "click"  //排行依据

    val isRefreshing = MutableStateFlow(false)
    val list = FlowPaginationInfo<RegionVideoInfo>()

    init {
        val timeSettingState = timeSettingStore.state
        timeFrom = timeSettingState.timeFrom
        timeTo = timeSettingState.timeTo
        rankOrder = timeSettingState.rankOrder
        loadData()
    }

    private fun loadData(
        pageNum: Int = list.pageNum
    ) = viewModelScope.launch(Dispatchers.IO){
        try {
            list.loading.value = true
            list.fail.value = ""   // 开始加载就清掉上一次的失败提示
            // newlist_rank 已下线（B站返回 -10），改用 ranking/v2 拉这个分区的排行榜
            val res = BiliApiService.regionAPI
                .regionVideoRanking(rid = rid)
                .awaitCall()
                .json<ResultInfo<RankingV2Response>>()
            if (res.code == 0) {
                val result = res.data?.list ?: emptyList()
                val timeFromLong = timeFrom.getValue().toLongOrNull() ?: 20090901L
                val timeToLong = timeTo.getValue().toLongOrNull() ?: 99999999L
                val timeFiltered = result.filter {
                    pubdateToDateInt(it.pubdate) in timeFromLong..timeToLong
                }
                // ranking/v2 不支持 order 参数，排序按排行依据在本地做
                val sorted = when (rankOrder) {
                    "click" -> timeFiltered.sortedByDescending { it.stat.view }
                    "scores" -> timeFiltered.sortedByDescending { it.stat.reply }   // 设置页把 scores 显示为"评论数"
                    "stow" -> timeFiltered.sortedByDescending { it.stat.favorite }
                    "coin" -> timeFiltered.sortedByDescending { it.stat.coin }
                    "dm" -> timeFiltered.sortedByDescending { it.stat.danmaku }
                    else -> timeFiltered
                }
                list.data.value = sorted
                    .map { it.toRegionVideoInfo() }
                    .filter {
                        filterStore.filterWord(it.title)
                                && filterStore.filterUpper(it.mid)
                    }
                // ranking/v2 一次性返回整个榜单，没有分页
                list.pageNum = 1
                // 时光姬只能检索"该分区当前排行榜"里的视频，所以较早的时间线必然为空：
                // 直接说"没有找到符合条件的视频"会让用户以为 App 坏了
                list.fail.value = if (list.data.value.isEmpty())
                    "当前时间线内没有数据，试试把时间线调近一点" else ""
                list.finished.value = list.data.value.isNotEmpty()
            } else {
                toast(res.message)
                throw Exception(res.message)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            list.fail.value = e.message ?: e.toString()
        } finally {
            list.loading.value = false
            isRefreshing.value = false
        }
    }

    /** Unix 时间戳秒 → YYYYMMDD */
    private fun pubdateToDateInt(timestamp: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestamp * 1000
        return cal.get(java.util.Calendar.YEAR) * 10000L +
                (cal.get(java.util.Calendar.MONTH) + 1) * 100L +
                cal.get(java.util.Calendar.DAY_OF_MONTH)
    }

    fun tryAgainLoadData(pageNum: Int = list.pageNum) {
        loadData(pageNum)
    }

    fun loadMore () {
        if (!list.finished.value && !list.loading.value) {
            loadData(
                pageNum = list.pageNum + 1
            )
        }
    }

    fun refresh() {
        list.reset()
        isRefreshing.value = true
        loadData()
    }

    fun toVideoDetail(
        item: RegionVideoInfo
    ) {
        pageNavigation.navigateToVideoInfo(item.id)
    }
}

@Composable
fun TimeRegionDetailListContent(
    rid: Int,
) {
    val viewModel = diViewModel(key = rid.toString()) {
        TimeRegionDetailListContentViewModel(it, rid)
    }
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val timeSettingStore: TimeSettingStore by rememberInstance()
    val timeState by timeSettingStore.stateFlow.collectAsState()
    LaunchedEffect(timeState) {
        if (
            viewModel.timeFrom != timeState.timeFrom
            || viewModel.timeTo != timeState.timeTo
            || viewModel.rankOrder != timeState.rankOrder
        ) {
            viewModel.timeFrom = timeState.timeFrom
            viewModel.timeTo = timeState.timeTo
            viewModel.rankOrder = timeState.rankOrder
            viewModel.refresh()
        }
    }

    val list by viewModel.list.data.collectAsState()
    val listLoading by viewModel.list.loading.collectAsState()
    val listFinished by viewModel.list.finished.collectAsState()
    val listFail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    SwipeToRefresh(
        refreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
    ) {
        LazyVerticalGrid(
            modifier = Modifier.fillMaxSize(),
            columns = GridCells.Adaptive(300.dp),
            contentPadding = windowInsets.toPaddingValues(
                top = 0.dp,
            )
        ) {
            items(list, key = { it.id }) {
                VideoItemBox(
                    modifier = Modifier.padding(
                        horizontal = 10.dp,
                        vertical = 5.dp
                    ),
                    title = it.title,
                    pic =it.pic,
                    upperName = it.author,
                    playNum = it.play,
                    damukuNum = it.video_review,
                    duration = NumberUtil.converDuration(it.duration),
                    onClick = {
                        viewModel.toVideoDetail(it)
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


}