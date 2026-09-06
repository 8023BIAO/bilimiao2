package cn.a10miaomiao.bilimiao.compose.pages.home.content

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bilibili.app.card.v1.Card
import bilibili.app.card.v1.SmallCoverV5
import bilibili.app.show.v1.EntranceShow
import bilibili.app.show.v1.PopularGRPC
import bilibili.app.show.v1.PopularResultReq
import cn.a10miaomiao.bilimiao.compose.common.constant.PageTabIds
import cn.a10miaomiao.bilimiao.compose.common.defaultNavOptions
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.emitter.EmitterAction
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localEmitter
import cn.a10miaomiao.bilimiao.compose.common.navigation.BilibiliNavigation
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.components.video.MiniVideoItemBox
import cn.a10miaomiao.bilimiao.compose.components.video.VideoItemBox
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.BangumiDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.SeasonCheckPage
import cn.a10miaomiao.bilimiao.compose.pages.home.HomePage
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.home.HomeRecommendInfo
import com.a10miaomiao.bilimiao.comm.entity.home.RecommendCardInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.FilterStore
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance


@Stable
private class HomeRecommendContentViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val context: Context by instance()
    private val pageNavigation: PageNavigation by instance()
    private val filterStore: FilterStore by instance()

    /** 已看视频URI集合，刷新时保留，上限500防OOM */
    private val seenUris = object : java.util.LinkedHashSet<String>(500, 0.75f) {
        override fun add(element: String): Boolean {
            if (size >= 500) {
                val it = iterator()
                it.next()
                it.remove()
            }
            return super.add(element)
        }
    }

    private val lastIdx
        get() = list.data.value.lastOrNull()?.idx ?: 0
    val list = FlowPaginationInfo<RecommendCardInfo>()
    val isRefreshing = MutableStateFlow(false)
    val listStyle = MutableStateFlow(0)

    // 在途加载请求（refresh 时取消，避免慢的旧批次用旧快照覆盖新列表）
    private var loadJob: Job? = null
    // 加载代数：每次 loadData 递增，过期批次不写列表/不重置 loading
    private val loadEpoch = AtomicLong(0)



    init {
        viewModelScope.launch {
            SettingPreferences.run {
                context.dataStore.data.map {
                    it[HomeRecommendListStyle] ?: 0
                }
            }.collect {
                listStyle.value = it
            }
        }
        loadData(0)
    }

    private fun loadData(
        idx: Long = lastIdx
    ) {
        val epoch = loadEpoch.incrementAndGet()
        // 同步置位（调用线程），堵住 loadMore 双发窗口（同 idx 重复请求/重复 key 崩溃）
        list.loading.value = true
        // 取消上一个在途请求（含慢的标签 gRPC 批次），避免旧结果覆盖新列表
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = BiliApiService.homeApi.recommendList(
                    idx = idx,
                ).awaitCall().json<ResponseData<HomeRecommendInfo>>()
                if (res.isSuccess) {
                    // 等屏蔽开关（时长/播放量/白名单/推广/标签严格）就位再过滤，
                    // 避免冷启动第一批在默认值下漏过本应屏蔽的视频
                    filterStore.awaitSettingsReady()
                    val itemsList = res.requireData().items
                    val batchSeen = mutableSetOf<String>()
                    val filterList = itemsList.mapNotNull { item ->
                        // 白名单：已关注UP主不受任何屏蔽规则影响
                        if (filterStore.isFollowWhitelisted(item.is_followed)) return@mapNotNull item
                        val id = item.param.ifEmpty { item.uri }
                        if ((item.goto?.isNotEmpty() ?: false)
                                && id !in seenUris
                                && id !in batchSeen
                                && filterStore.filterPromotion(item.card_goto)        // 推广视频过滤
                                && filterStore.filterDuration(item.cover_right_text)      // 先短路
                                && filterStore.filterPlayCount(item.cover_left_text_1)    // 再短路
                                && filterStore.filterWord(item.title)
                                && item.args != null
                                && item.args!!.up_id != null
                                && filterStore.filterUpper(item.args!!.up_id!!)
                                && filterStore.filterUpperName(item.args!!.up_name ?: "")) {
                            if (id.isNotEmpty()) batchSeen.add(id)
                            item
                        } else {
                            null
                        }
                    }
                    // 屏蔽标签：并行查询（限并发4路；原先逐视频串行 gRPC 一批要数秒~数十秒，
                    // 期间一旦刷新就出现旧批次覆盖新列表的竞态）
                    val passResults: List<Pair<RecommendCardInfo, Boolean>> =
                        if (filterStore.filterTagListIsEmpty()) {
                            filterList.map { it to true }
                        } else {
                            coroutineScope {
                                val semaphore = Semaphore(4)
                                filterList.map { item ->
                                    async {
                                        semaphore.withPermit {
                                            item to filterStore.filterTag(item.param)
                                        }
                                    }
                                }.awaitAll()
                            }
                        }
                    // 过期批次（期间发生了刷新）直接丢弃：不写列表也不标记已看
                    if (loadEpoch.get() != epoch) return@launch
                    // 快照在写入前一刻才取，避免慢批次把并发期间的新内容覆盖回旧列表
                    val newList = if (idx == 0L) mutableListOf()
                    else list.data.value.toMutableList()
                    passResults.forEach { (item, passed) ->
                        val id = item.param.ifEmpty { item.uri }
                        // 无论 filterTag 结果如何都写入 seenUris，避免 gRPC 波动导致重复评估
                        if (id.isNotEmpty()) seenUris.add(id)
                        if (passed) newList.add(item)
                    }
                    list.data.value = newList
                    list.finished.value = itemsList.isEmpty()
                } else {
                    toast(res.message)
                    throw Exception(res.message)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                if (e is java.io.IOException) {
                    // 网络错误（SSL、DNS、connection abort 等）静默忽略，下滑自动重试
                } else {
                    list.fail.value = e.message ?: e.toString()
                }
            } finally {
                // 只有最新一代请求才复位加载标志（被取消的旧批次不得复位）
                if (loadEpoch.get() == epoch) {
                    list.loading.value = false
                    isRefreshing.value = false
                }
            }
        }
    }

    fun tryAgainLoadData() {
        loadData()
    }

    fun loadMore() {
        if (!list.finished.value && !list.loading.value) {
            loadData(lastIdx)
        }
    }

    fun refresh() {
        // 先清空旧列表，避免屏蔽规则变化后，旧内容继续显示到新列表过滤完成，造成“闪一下又消失”
        list.reset()
        isRefreshing.value = true
        loadData(0)
    }

    fun toVideoDetail(item: RecommendCardInfo) {
        if (item.goto == "av" || item.goto == "vertical_av") {
            pageNavigation.navigateToVideoInfo(item.param)
        } else if (item.goto == "bangumi") {
            pageNavigation.navigate(SeasonCheckPage(
                epId = item.param
            ))
        } else if (!BilibiliNavigation.navigationTo(pageNavigation, item.uri)){
            BilibiliNavigation.navigationToWeb(pageNavigation, item.uri)
        }
    }


}

@Composable
internal fun HomeRecommendContent() {
    val viewModel: HomeRecommendContentViewModel = diViewModel()
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val list by viewModel.list.data.collectAsState()
    val listLoading by viewModel.list.loading.collectAsState()
    val listFinished by viewModel.list.finished.collectAsState()
    val listFail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val listStyle by viewModel.listStyle.collectAsState()

    val listState = rememberLazyGridState()
    val emitter = localEmitter()
    LaunchedEffect(Unit) {
        emitter.collectAction<EmitterAction.DoubleClickTab> {
            if (it.tab == PageTabIds.HomeRecommend) {
                if (listState.firstVisibleItemIndex == 0) {
                    viewModel.refresh()
                } else {
                    listState.animateScrollToItem(0)
                }
            }
        }
    }

    // 滑到底部时自动加载更多（无网络/SSL错误时不阻塞，下次下滑自动重试）
    val reachBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible != null && lastVisible.index >= layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(reachBottom) {
        if (reachBottom && !listLoading && !listFinished && list.isNotEmpty()) {
            viewModel.loadMore()
        }
    }

    SwipeToRefresh(
        refreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
    ) {
        LazyVerticalGrid(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            columns = if (listStyle == 0) GridCells.Adaptive(300.dp)
                else GridCells.Adaptive(180.dp),
            contentPadding = windowInsets.toPaddingValues(
                top = 0.dp,
            )
        ) {
            items(list, { it.param.ifEmpty { it.uri } }) {
                if (listStyle == 0) {
                    VideoItemBox(
                        modifier = Modifier.padding(
                            horizontal = 10.dp,
                            vertical = 5.dp
                        ),
                        title = it.title,
                        pic = it.cover,
                        upperName = it.args?.up_name,
                        playNum = it.cover_left_text_1,
                        damukuNum = it.cover_left_text_2,
                        duration = it.cover_right_text,
                        onClick = {
                            viewModel.toVideoDetail(it)
                        }
                    )
                } else {
                    MiniVideoItemBox(
                        modifier = Modifier.padding(5.dp),
                        title = it.title,
                        pic = it.cover,
                        upperName = it.args?.up_name,
                        playNum = it.cover_left_text_1,
                        damukuNum = it.cover_left_text_2,
                        duration = it.cover_right_text,
                        onClick = {
                            viewModel.toVideoDetail(it)
                        }
                    )
                }
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