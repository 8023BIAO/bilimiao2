package cn.a10miaomiao.bilimiao.compose.pages.home.content

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.navOptions
import cn.a10miaomiao.bilimiao.compose.common.constant.PageTabIds
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.emitter.EmitterAction
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localEmitter
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.bangumi.MiniBangumiItemBox
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.BangumiDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.SeasonCheckPage
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.ResponseResult
import com.a10miaomiao.bilimiao.comm.entity.bangumi.PgcIndexItem
import com.a10miaomiao.bilimiao.comm.entity.bangumi.PgcIndexConditionData
import com.a10miaomiao.bilimiao.comm.entity.bangumi.PgcConditionFilter
import com.a10miaomiao.bilimiao.comm.entity.bangumi.PgcConditionValue
import com.a10miaomiao.bilimiao.comm.entity.bangumi.PgcIndexResultInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.toast
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

/**
 * 番剧/影视 首页内容页
 * @param seasonType 1=番剧, 102=影视综合(电影+电视剧等)
 * @param tabId 对应 PageTabIds 中的 ID，用于双击刷新
 */
@Stable
private class HomeBangumiContentViewModel(
    override val di: DI,
    private val seasonType: Int,
    private val tabId: String,
    private val context: Context,
) : ViewModel(), DIAware {

    // 影视 Tab 的 index_type（跟随 PiliPlus: 番剧=null, 影视=102, 子分类=具体值）
    private val isCinema = seasonType == 102

    private val pageNavigation: PageNavigation by instance()

    val list = FlowPaginationInfo<PgcIndexItem>()
    val isRefreshing = androidx.compose.runtime.mutableStateOf(false)

    /** 动态筛选条件（从 B站 API 获取） */
    val conditionData = mutableStateOf<PgcIndexConditionData?>(null)

    /** 当前筛选参数（field → keyword） */
    val indexParams = mutableStateOf<Map<String, String>>(emptyMap())

    /** 影视子分类 index_type（跟随 PiliPlus: 番剧=null, 影视主tab=102） */
    val indexType = mutableStateOf<Int?>(if (isCinema) 102 else null)

    val showFilter = mutableStateOf(false)

    /** 当前筛选对应的 DataStore key */
    private val filterPrefKey = if (seasonType == 1)
        SettingPreferences.HomeBangumiFilter
    else
        SettingPreferences.HomeCinemaFilter

    init {
        restoreOrLoad()
    }

    /** 尝试从持久化恢复筛选，失败时重新加载 */
    private fun restoreOrLoad() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val saved = SettingPreferences.mapData(context) { it[filterPrefKey] } ?: ""
            if (saved.isNotEmpty()) {
                val jsonObj = org.json.JSONObject(saved)
                val map = mutableMapOf<String, String>()
                jsonObj.keys().forEach { key ->
                    map[key] = jsonObj.optString(key, "")
                }
                indexType.value = map.remove("__indexType")?.toIntOrNull()
                indexParams.value = map
                loadData(1)
                // 后台加载 condition（只更新 UI 不覆盖已恢复的参数）
                loadConditionInternal(setDefaults = false)
                return@launch
            }
        } catch (_: Exception) {}
        loadCondition()
    }

    /** 保存当前筛选到 DataStore */
    private fun saveFilter() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val saveMap: Map<String, String> = indexParams.value.toMutableMap().also {
                    indexType.value?.let { t -> it["__indexType"] = t.toString() }
                }
                val jsonStr = org.json.JSONObject(saveMap).toString()
                SettingPreferences.edit(context) {
                    it[filterPrefKey] = jsonStr
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadCondition() =
        viewModelScope.launch(Dispatchers.IO) {
            loadConditionInternal()
            saveFilter()
            loadData(1)
        }

    private suspend fun loadConditionInternal(setDefaults: Boolean = true) {
        try {
            val res = BiliApiService.bangumiAPI.seasonIndexCondition(
                seasonType = if (indexType.value != null) null else seasonType,
                indexType = indexType.value,
            ).awaitCall().json<ResponseData<PgcIndexConditionData>>()
            if (res.isSuccess) {
                val data = res.requireData()
                // 影视 Tab 注入子分类筛选（跟随 PiliPlus: 102=全部, 2=电影, 5=电视剧, 3=纪录片, 7=综艺）
                if (isCinema) {
                    val categoryFilter = PgcConditionFilter(
                        field = "index_type",
                        name = "分类",
                        values = listOf(
                            PgcConditionValue(keyword = "102", name = "全部"),
                            PgcConditionValue(keyword = "2", name = "电影"),
                            PgcConditionValue(keyword = "5", name = "电视剧"),
                            PgcConditionValue(keyword = "3", name = "纪录片"),
                            PgcConditionValue(keyword = "7", name = "综艺"),
                        )
                    )
                    conditionData.value = data.copy(
                        filter = listOf(categoryFilter) + data.filter
                    )
                } else {
                    conditionData.value = data
                }
                // 仅在首次加载时设置默认参数（恢复时不覆盖）
                if (setDefaults) {
                    val params = mutableMapOf<String, String>()
                    data.order?.firstOrNull()?.let {
                        params["order"] = it.field
                    }
                    data.filter?.forEach { filter ->
                        filter.values.firstOrNull()?.let {
                            params[filter.field] = it.keyword
                        }
                    }
                    // 补上注入的 index_type（分类）默认值
                    if (isCinema) {
                        params["index_type"] = (indexType.value ?: 102).toString()
                    }
                    indexParams.value = params
                } else {
                    // 恢复时 reconcile：旧值对不上API选项时回退到默认值
                    val current = indexParams.value.toMutableMap()
                    var changed = false
                    data.filter?.forEach { filter ->
                        val saved = current[filter.field]
                        if (saved != null && filter.values.none { it.keyword == saved }) {
                            filter.values.firstOrNull()?.let {
                                current[filter.field] = it.keyword
                                changed = true
                            }
                        }
                        if (!current.containsKey(filter.field) && filter.values.isNotEmpty()) {
                            current[filter.field] = filter.values.first().keyword
                            changed = true
                        }
                    }
                    // 补 reconciliation 时也处理 index_type
                    if (isCinema) {
                        val savedIndexType = current["index_type"]
                        if (savedIndexType == null || listOf("102","2","5","3","7").none { it == savedIndexType }) {
                            current["index_type"] = indexType.value?.toString() ?: "102"
                            changed = true
                        }
                    }
                    if (changed) {
                        indexParams.value = current
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
        }
    }

    private fun loadData(page: Int = (list.data.value.size / 20) + 1) =
        viewModelScope.launch(Dispatchers.IO) {
            try {
                list.loading.value = true
                val res = BiliApiService.bangumiAPI.seasonIndex(
                    seasonType = if (indexType.value != null) null else seasonType,
                    page = page,
                    params = indexParams.value,
                    indexType = indexType.value,
                ).awaitCall().json<ResponseData<PgcIndexResultInfo>>()
                if (res.isSuccess) {
                    val data = res.requireData()
                    val newList = if (page == 1) mutableListOf()
                    else list.data.value.toMutableList()
                    val newIds = newList.map { it.season_id }.toMutableSet()
                    newList.addAll(data.list.filter { it.season_id > 0 && newIds.add(it.season_id) })
                    newList.distinctBy { it.season_id }.let { list.data.value = it }
                    list.finished.value = data.has_next != 1
                } else {
                    toast(res.message)
                    throw Exception(res.message)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                if (e is java.io.IOException) {
                    // 网络错误静默忽略，下滑自动重试
                } else {
                    list.fail.value = e.message ?: e.toString()
                }
            } finally {
                list.loading.value = false
                isRefreshing.value = false
            }
        }

    fun loadMore() {
        if (!list.finished.value && !list.loading.value) {
            loadData()
        }
    }

    fun refresh() {
        isRefreshing.value = true
        list.finished.value = false
        list.fail.value = ""
        loadData(1)
    }
    fun applyFilter(params: Map<String, String>) {
        // 处理 index_type 分类切换
        val newIndexType = params["index_type"]?.toIntOrNull()
        if (newIndexType != indexType.value) {
            indexType.value = newIndexType
            conditionData.value = null
            indexParams.value = params
            saveFilter()
            // 重新获取条件但不覆盖用户已选的筛选参数
            viewModelScope.launch(Dispatchers.IO) {
                loadConditionInternal(setDefaults = false)
                loadData(1)
            }
            return
        }
        indexParams.value = params
        saveFilter()
        list.data.value = emptyList()
        list.finished.value = false
        list.fail.value = ""
        loadData(1)
    }

    fun toSeasonDetail(item: PgcIndexItem) {
        if (item.season_id <= 0) {
            toast("该条目暂不支持查看")
            return
        }
        pageNavigation.navigate(SeasonCheckPage(
            id = item.season_id.toString(),
        ), navOptions {
            launchSingleTop = true
        })
    }
}

@Composable
internal fun HomeBangumiContent(
    seasonType: Int,
    tabId: String,
) {
    val context = LocalContext.current
    val viewModel: HomeBangumiContentViewModel = diViewModel(
        key = "bangumi-content-$seasonType",
        initializer = { di -> HomeBangumiContentViewModel(di = di, seasonType = seasonType, tabId = tabId, context = context) }
    )
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    // 读取卡片列数设置
    val dataStore = remember { SettingPreferences.run { context.dataStore } }
    val gridSpan by dataStore.data
        .map { it[SettingPreferences.HomeBangumiGridSpan] ?: 0 }
        .collectAsState(initial = 0)

    val list by viewModel.list.data.collectAsState()
    val listLoading by viewModel.list.loading.collectAsState()
    val listFinished by viewModel.list.finished.collectAsState()
    val listFail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing
    val showFilter by viewModel.showFilter
    val conditionData by viewModel.conditionData
    val indexParams by viewModel.indexParams

    val listState = rememberLazyGridState()
    val emitter = localEmitter()
    LaunchedEffect(Unit) {
        launch {
            emitter.collectAction<EmitterAction.DoubleClickTab> {
                if (it.tab == tabId) {
                    if (listState.firstVisibleItemIndex == 0) {
                        viewModel.refresh()
                    } else {
                        listState.animateScrollToItem(0)
                    }
                }
            }
        }
        launch {
            emitter.collectAction<EmitterAction.OpenFilter> {
                if (it.tabId == tabId) {
                    viewModel.showFilter.value = true
                }
            }
        }
    }

    // 滑到底部自动加载更多
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
            columns = if (gridSpan == 0) GridCells.Adaptive(180.dp) else GridCells.Fixed(gridSpan),
            contentPadding = windowInsets.toPaddingValues(
                top = 0.dp,
            ),
        ) {
            items(list, { it.season_id }) {
                MiniBangumiItemBox(
                    modifier = Modifier.padding(5.dp),
                    title = it.title,
                    cover = it.cover,
                    desc = it.badge.ifBlank { it.index_show },
                    onClick = { viewModel.toSeasonDetail(it) },
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

    // 筛选弹窗
    if (showFilter && conditionData != null) {
        HomeBangumiFilterSheet(
            conditionData = conditionData!!,
            currentParams = indexParams,
            onApply = { viewModel.applyFilter(it) },
            onDismiss = { viewModel.showFilter.value = false },
        )
    }
}
