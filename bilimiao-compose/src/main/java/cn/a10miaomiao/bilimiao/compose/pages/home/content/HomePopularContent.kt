package cn.a10miaomiao.bilimiao.compose.pages.home.content

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import cn.a10miaomiao.bilimiao.compose.components.video.VideoItemBox
import cn.a10miaomiao.bilimiao.compose.pages.message.components.MessageItemBox
import cn.a10miaomiao.bilimiao.compose.pages.web.WebPage
// 【已删除】import SettingPreferences — 热门个性化开关已移除
import com.a10miaomiao.bilimiao.comm.entity.archive.ArchiveInfo
import com.a10miaomiao.bilimiao.comm.entity.comm.PaginationInfo
import com.a10miaomiao.bilimiao.comm.entity.message.AtMessageInfo
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.store.FilterStore
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.store.WindowStore
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicLong
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance


@Stable
private class HomePopularContentViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val context: Context by instance()
    private val pageNavigation: PageNavigation by instance()
    private val filterStore: FilterStore by instance()

    /** 已看视频URI集合，刷新时保留（与推荐页一致，防止刷新重复），上限500防OOM */
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
        get() = list.data.value.lastOrNull()?.base?.idx ?: 0
    val list = FlowPaginationInfo<SmallCoverV5>()
    val topEntranceList = MutableStateFlow(listOf<EntranceShow>())
    val isRefreshing = MutableStateFlow(false)

    // 在途加载请求（refresh 时取消，避免慢的旧批次用旧快照覆盖新列表）
    private var loadJob: Job? = null
    // 加载代数：每次 loadData 递增，过期批次不写列表/不重置 loading
    private val loadEpoch = AtomicLong(0)

    init {
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
                // 【已删除】个性化热门列表token开关 — 热门API不支持个性化，无实际作用
                val req = PopularResultReq(
                    idx = idx,
                )
                val result = BiliGRPCHttp.request {
                    PopularGRPC.index(req)
                }.awaitCall()
                // 等屏蔽开关（时长/播放量/推广/标签严格）就位再过滤，
                // 避免冷启动第一批在默认值下漏过本应屏蔽的视频
                filterStore.awaitSettingsReady()
                val itemsList = result.items
                val batchSeen = mutableSetOf<String>()
                val filterList = itemsList.mapNotNull {
                    val item = (it.item as? Card.Item.SmallCoverV5)?.value ?: return@mapNotNull null
                    val base = item.base ?: return@mapNotNull null
                    val id = base.param.ifEmpty { base.uri }
                    if (filterStore.filterPromotion(base.cardGoto)
                            && id !in seenUris
                            && id !in batchSeen
                            && filterStore.filterDuration(item.coverRightText1)      // 先短路：最便宜
                            && filterStore.filterPlayCount(item.rightDesc2)          // 再短路
                            && filterStore.filterWord(base.title)
                            && filterStore.filterUpperName(item.rightDesc1)) {
                        if (id.isNotEmpty()) batchSeen.add(id)
                        item
                    } else {
                        null
                    }
                }
                // 顶部入口行与过滤无关，保持原时序（拿到数据立刻更新）
                val topItems = result.config?.topItems
                if (topItems != null) {
                    topEntranceList.value = topItems
                }
                // 屏蔽标签：并行查询（限并发4路；原先逐视频串行 gRPC 一批要数秒~数十秒，
                // 期间一旦刷新就出现旧批次覆盖新列表的竞态）
                val passResults: List<Pair<SmallCoverV5, Boolean>> =
                    if (filterStore.filterTagListIsEmpty()) {
                        filterList.map { it to true }
                    } else {
                        coroutineScope {
                            val semaphore = Semaphore(4)
                            filterList.map { item ->
                                async {
                                    semaphore.withPermit {
                                        item to filterStore.filterTag(item.base?.param ?: "")
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                // 过期批次（期间发生了刷新）直接丢弃：不写任何状态
                if (loadEpoch.get() != epoch) return@launch
                // 快照在写入前一刻才取，避免慢批次把并发期间的新内容覆盖回旧列表
                val newList = if (idx == 0L) mutableListOf()
                    else list.data.value.toMutableList()
                passResults.forEach { (item, passed) ->
                    val id = item.base?.param?.ifEmpty { item.base?.uri ?: "" } ?: ""
                    // 无论 filterTag 结果如何都写入 seenUris，避免 gRPC 波动导致重复评估
                    if (id.isNotEmpty()) seenUris.add(id)
                    if (passed) newList.add(item)
                }
                list.data.value = newList
                list.finished.value = itemsList.isEmpty()
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

    fun toVideoDetail(item: SmallCoverV5) {
        val base = item.base ?: return
        pageNavigation.navigateToVideoInfo(base.param)
    }

    fun toPageByUrl(url: String) {
        if (!BilibiliNavigation.navigationTo(pageNavigation, url)) {
            BilibiliNavigation.navigationToWeb(pageNavigation, url)
        }
    }

}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun EntranceListBox(
    viewModel: HomePopularContentViewModel
) {
    val topEntranceList by viewModel.topEntranceList.collectAsState()
    LazyRow {
        items(topEntranceList, { it.uri }) {
            Column(
                modifier = Modifier.width(80.dp)
                    .clickable {
                        viewModel.toPageByUrl(it.uri)
                    }
                    .padding(top = 10.dp, bottom = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GlideImage(
                    model = UrlUtil.autoHttps(it.icon),
                    contentDescription = "",
                    modifier = Modifier
                        .padding(bottom = 5.dp)
                        .size(40.dp, 40.dp)
                )
                Text(
                    text = it.title,
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
internal fun HomePopularContent() {
    val viewModel: HomePopularContentViewModel = diViewModel()
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val list by viewModel.list.data.collectAsState()
    val listLoading by viewModel.list.loading.collectAsState()
    val listFinished by viewModel.list.finished.collectAsState()
    val listFail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val listState = rememberLazyGridState()
    val emitter = localEmitter()
    LaunchedEffect(Unit) {
        emitter.collectAction<EmitterAction.DoubleClickTab> {
            if (it.tab == PageTabIds.HomePopular) {
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
        LazyVerticalGrid(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            columns = GridCells.Adaptive(300.dp),
            contentPadding = windowInsets.toPaddingValues(
                top = 0.dp,
            )
        ) {
            item {
                EntranceListBox(viewModel)
            }
            items(list, { it.base?.param?.ifEmpty { it.base?.uri ?: "" } ?: "" }) {
                VideoItemBox(
                    modifier = Modifier.padding(
                        horizontal = 10.dp,
                        vertical = 5.dp
                    ),
                    title = it.base?.title,
                    pic =it.base?.cover,
                    upperName = it.rightDesc1,
                    playNum = it.rightDesc2,
                    duration = it.coverRightText1,
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