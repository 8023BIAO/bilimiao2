package cn.a10miaomiao.bilimiao.compose.pages.home.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import cn.a10miaomiao.bilimiao.compose.common.constant.PageTabIds
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.emitter.EmitterAction
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localEmitter
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.components.video.VideoItemBox
import cn.a10miaomiao.bilimiao.compose.pages.home.HomePageState
import cn.a10miaomiao.bilimiao.compose.pages.setting.TimeSelectSettingPage
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.region.RankingV2Response
import com.a10miaomiao.bilimiao.comm.entity.region.RankingV2VideoInfo
import com.a10miaomiao.bilimiao.comm.entity.region.RegionInfo
import com.a10miaomiao.bilimiao.comm.entity.region.RegionVideoInfo
import com.a10miaomiao.bilimiao.comm.entity.region.RegionVideosRankInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.RegionStore
import com.a10miaomiao.bilimiao.comm.utils.TimeSelectUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

private class HomeTimeSelectContentViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val pageNavigation: PageNavigation by instance()
    private val fragment by instance<Fragment>()
    private val regionStore: RegionStore by instance()

    val regionList = regionStore.state.regions

    fun toVideoDetail(video: RegionVideoInfo) {
        pageNavigation.navigateToVideoInfo(video.id)
    }

    fun toSetting() {
        pageNavigation.navigate(TimeSelectSettingPage)
    }

    /** 预读取配置（init 时加载一次） */
    private var cachedConfig: TimeSelectConfig? = null

    /** 待加载的分区索引 */
    private var pendingRegionIndex = 0
    private var pendingRegions: List<RegionInfo> = emptyList()

    val list = FlowPaginationInfo<RegionVideoInfo>()
    val isRefreshing = MutableStateFlow(false)

    private data class TimeSelectConfig(
        val timeFrom: String,
        val timeTo: String,
        val weights: Map<String, Int>,
        val selectedRegions: List<RegionInfo>,
        val pagesPerRegion: Int,
        val pageSize: Int,
        val minDuration: Int,
        val minPlayCount: Int,
    )

    init {
        loadAllRegions()
    }

    fun refresh() {
        if (isRefreshing.value) return
        isRefreshing.value = true
        list.reset()
        pendingRegionIndex = 0
        cachedConfig = null
        loadAllRegions()
    }

    fun loadMore() {
        if (!list.finished.value && !list.loading.value) {
            loadAllRegions()
        }
    }

    /** 顺序加载所有分区（防爬虫：逐分区串行，空分区自动跳过） */
    private fun loadAllRegions() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val config = if (cachedConfig != null) cachedConfig!!
            else readConfig().also { cachedConfig = it }

            val regions = config.selectedRegions
            if (pendingRegionIndex == 0) {
                pendingRegions = regions
            }
            // android.util.Log.d("TimeSelect", "regions=${regions.size} startIdx=$pendingRegionIndex")

            if (regions.isEmpty()) {
                list.fail.value = "没有选择分区，请去设置中配置"
                isRefreshing.value = false
                return@launch
            }

            val isInitial = pendingRegionIndex == 0

            while (pendingRegionIndex < regions.size) {
                val region = regions[pendingRegionIndex]
                pendingRegionIndex++
                list.loading.value = true

                try {
                    val res = BiliApiService.regionAPI
                        .regionVideoRanking(rid = region.tid)
                        .awaitCall()
                        .json<ResultInfo<RankingV2Response>>()
                    // android.util.Log.d("TimeSelect", "API rid=${region.tid} code=${res.code} count=${res.data?.list?.size ?: 0}")


                    if (res.code == 0) {
                        val videoList = res.data?.list ?: emptyList()
                        val timeFromLong = config.timeFrom.toLongOrNull() ?: 20090901L
                        val timeToLong = config.timeTo.toLongOrNull() ?: 99999999L

                        val newVideos = videoList.filter { video ->
                            val dateInt = pubdateToDateInt(video.pubdate)
                            dateInt in timeFromLong..timeToLong
                        }.map { it.toRegionVideoInfo() }
                        .filter { video ->
                            val passDuration = config.minDuration <= 0 ||
                                (video.duration.toIntOrNull() ?: 0) >= config.minDuration
                            val passPlayCount = config.minPlayCount <= 0 ||
                                (video.play.toLongOrNull() ?: 0L) >= config.minPlayCount
                            passDuration && passPlayCount
                        }

                        if (newVideos.isNotEmpty()) {
                            // android.util.Log.d("TimeSelect", "rid=${region.tid} newVideos=${newVideos.size}")
                            val existingIds = list.data.value.map { it.id }.toSet()
                            val deduped = newVideos.filter { it.id !in existingIds }
                            if (deduped.isNotEmpty()) {
                                val merged = list.data.value.toMutableList()
                                merged.addAll(deduped)
                                list.data.value = scoreAndSort(merged, config.weights)
                                break // 拿到数据就停，后续分区等用户滑动触发
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("TimeSelect", "rid=${region.tid} failed: ${e.message}")
                } finally {
                    list.loading.value = false
                }
            }

            if (pendingRegionIndex >= regions.size) {
                list.finished.value = true
            }
            if (isInitial && list.data.value.isEmpty()) {
                list.fail.value = "没有找到符合条件的视频"
            }
        } catch (e: Exception) {
            list.fail.value = "加载失败: ${e.message}"
        } finally {
            isRefreshing.value = false
        }
    }

    /** Unix 时间戳秒 → YYYYMMDD Int */
    private fun pubdateToDateInt(timestamp: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestamp * 1000
        return (cal.get(java.util.Calendar.YEAR) * 10000L +
                (cal.get(java.util.Calendar.MONTH) + 1) * 100L +
                cal.get(java.util.Calendar.DAY_OF_MONTH)).toLong()
    }

    private fun scoreAndSort(
        videos: List<RegionVideoInfo>,
        weights: Map<String, Int>,
    ): List<RegionVideoInfo> {
        if (videos.isEmpty()) return videos

        var minFav = Long.MAX_VALUE; var maxFav = Long.MIN_VALUE
        var minClick = Long.MAX_VALUE; var maxClick = Long.MIN_VALUE
        var minDanmaku = Long.MAX_VALUE; var maxDanmaku = Long.MIN_VALUE
        var minReply = Long.MAX_VALUE; var maxReply = Long.MIN_VALUE

        for (v in videos) {
            val fav = v.favorites.toLongOrNull() ?: 0L
            minFav = minOf(minFav, fav)
            maxFav = maxOf(maxFav, fav)

            val click = v.play.toLongOrNull() ?: 0L
            minClick = minOf(minClick, click)
            maxClick = maxOf(maxClick, click)

            val danmaku = v.video_review.toLongOrNull() ?: 0L
            minDanmaku = minOf(minDanmaku, danmaku)
            maxDanmaku = maxOf(maxDanmaku, danmaku)

            val reply = v.review.toLongOrNull() ?: 0L
            minReply = minOf(minReply, reply)
            maxReply = maxOf(maxReply, reply)
        }

        val coinW = 0f
        val favW = (weights["favorite"] ?: 35) / 100f
        val clickW = (weights["click"] ?: 15) / 100f
        val danmakuW = (weights["danmaku"] ?: 5) / 100f
        val replyW = (weights["reply"] ?: 5) / 100f

        data class ScoredVideo(val video: RegionVideoInfo, val score: Float)
        val scored = videos.map { v ->
            val fav = v.favorites.toLongOrNull() ?: 0L
            val click = v.play.toLongOrNull() ?: 0L
            val danmaku = v.video_review.toLongOrNull() ?: 0L
            val reply = v.review.toLongOrNull() ?: 0L

            val normFav = if (maxFav > minFav)
                (fav - minFav).toFloat() / (maxFav - minFav).toFloat() else 0f
            val normClick = if (maxClick > minClick)
                (click - minClick).toFloat() / (maxClick - minClick).toFloat() else 0f
            val normDanmaku = if (maxDanmaku > minDanmaku)
                (danmaku - minDanmaku).toFloat() / (maxDanmaku - minDanmaku).toFloat() else 0f
            val normReply = if (maxReply > minReply)
                (reply - minReply).toFloat() / (maxReply - minReply).toFloat() else 0f

            val score = normFav * favW + normClick * clickW
                    + normDanmaku * danmakuW + normReply * replyW

            ScoredVideo(v, score)
        }

        return scored.sortedByDescending { it.score }.map { it.video }
    }

    private fun readConfig(): TimeSelectConfig {
        val context = fragment.requireContext()
        val prefs = runBlocking {
            SettingPreferences.run {
                SettingPreferences.mapData(context) { it }
            }
        }

        val weightsStr = prefs[SettingPreferences.TimeSelectWeights]
            ?: SettingConstants.TIME_SELECT_DEFAULT_WEIGHTS
        val allRegions = prefs[SettingPreferences.TimeSelectAllRegions] ?: true
        val selectedRegionIds = prefs[SettingPreferences.TimeSelectSelectedRegions] ?: emptySet()
        val pagesPerRegion = prefs[SettingPreferences.TimeSelectPagesPerRegion]
            ?: SettingConstants.TIME_SELECT_DEFAULT_PAGES
        val minDuration = prefs[SettingPreferences.TimeSelectMinDuration] ?: 0
        val minPlayCount = prefs[SettingPreferences.TimeSelectMinPlayCount] ?: 0

        // 排除最近N天：N=0 表示全部时间
        val excludeDays = prefs[SettingPreferences.TimeSelectExcludeRecent] ?: 0
        val timeFrom = "20090901"
        val timeTo = if (excludeDays <= 0) getTodayStr() else getDateDaysAgo(excludeDays)

        val regions = if (allRegions) {
            regionStore.state.regions
        } else {
            regionStore.state.regions.filter {
                selectedRegionIds.contains(it.tid.toString())
            }
        }

        return TimeSelectConfig(
            timeFrom = timeFrom,
            timeTo = timeTo,
            weights = TimeSelectUtil.parseWeights(weightsStr),
            selectedRegions = regions,
            pagesPerRegion = pagesPerRegion,
            pageSize = SettingConstants.TIME_SELECT_DEFAULT_PAGE_SIZE,
            minDuration = minDuration,
            minPlayCount = minPlayCount,
        )
    }

    private fun getTodayStr(): String {
        val cal = java.util.Calendar.getInstance()
        return String.format("%04d%02d%02d", cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }

    private fun getDateDaysAgo(days: Int): String {
        if (days <= 0) return getTodayStr()
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -days)
        return String.format("%04d%02d%02d", cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }
}

@Composable
internal fun HomeTimeSelectContent(
    pageState: HomePageState
) {
    val viewModel: HomeTimeSelectContentViewModel = diViewModel()
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val list by viewModel.list.data.collectAsState()
    val listLoading by viewModel.list.loading.collectAsState()
    val listFinished by viewModel.list.finished.collectAsState()
    val listFail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val emitter = localEmitter()
    LaunchedEffect(Unit) {
        emitter.collectAction<EmitterAction.DoubleClickTab> {
            if (it.tab == PageTabIds.HomeTimeSelect) {
                viewModel.refresh()
            }
        }
    }

    SwipeToRefresh(
        refreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
    ) {
        val gridState = rememberLazyGridState()
        // 滚动到底部时自动加载更多
        val shouldLoadMore by remember {
            derivedStateOf {
                val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()
                    ?: return@derivedStateOf false
                lastVisibleItem.index >= gridState.layoutInfo.totalItemsCount - 3
            }
        }
        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore && !listLoading && !listFinished) {
                viewModel.loadMore()
            }
        }
        LazyVerticalGrid(
            modifier = Modifier
                .fillMaxSize()
                .padding(windowInsets.toPaddingValues()),
            columns = GridCells.Adaptive(300.dp),
            state = gridState,
        ) {
            items(list, { it.id }) { video ->
                VideoItemBox(
                    modifier = Modifier.padding(
                        horizontal = 10.dp,
                        vertical = 5.dp
                    ),
                    title = video.title,
                    pic = video.pic,
                    upperName = video.author,
                    playNum = video.play,
                    damukuNum = video.video_review,
                    duration = video.duration,
                    onClick = {
                        viewModel.toVideoDetail(video)
                    }
                )
            }
            item(
                span = { androidx.compose.foundation.lazy.grid.GridItemSpan(this.maxLineSpan) }
            ) {
                // 居中的加载状态
                if (listLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                if (listFail.isNotEmpty() && list.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        TextButton(onClick = { viewModel.refresh() }) {
                            Text(listFail)
                        }
                        TextButton(onClick = { viewModel.toSetting() }) {
                            Text("前往设置")
                        }
                    }
                }
                if (!listLoading && listFail.isEmpty() && listFinished && list.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("没有符合条件的视频")
                        TextButton(onClick = { viewModel.toSetting() }) {
                            Text("前往设置")
                        }
                    }
                }
            }
        }
    }
}
