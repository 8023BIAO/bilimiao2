package cn.a10miaomiao.bilimiao.compose.pages.mine

import android.content.Context
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.Navigation
import bilibili.app.interfaces.v1.Cursor
import bilibili.app.interfaces.v1.CursorItem
import bilibili.app.interfaces.v1.CursorV2Req
import bilibili.app.interfaces.v1.HistoryGRPC
import cn.a10miaomiao.bilimiao.compose.BilimiaoPageRoute
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.addPaddingValues
import cn.a10miaomiao.bilimiao.compose.common.navigation.BilibiliNavigation
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.mypage.rememberMyMenu
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.dialogs.MessageDialogState
import cn.a10miaomiao.bilimiao.compose.components.dialogs.OverlayAlertDialog
import cn.a10miaomiao.bilimiao.compose.components.layout.sticky.StickyHeaders
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.components.user.enterLiveRoom
import cn.a10miaomiao.bilimiao.compose.components.video.VideoItemBox
import cn.a10miaomiao.bilimiao.compose.pages.article.ArticleReaderPage
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.BangumiDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.SeasonCheckPage
import cn.a10miaomiao.bilimiao.compose.pages.search.components.ArticleItemBox
import cn.a10miaomiao.bilimiao.compose.pages.user.UserFavouriteDetailPage
import com.a10miaomiao.bilimiao.comm.entity.comm.PaginationInfo
import com.a10miaomiao.bilimiao.comm.entity.history.WebHistory
import com.a10miaomiao.bilimiao.comm.entity.history.WebHistoryItem
import com.a10miaomiao.bilimiao.comm.entity.history.WebHistoryResponse
import com.a10miaomiao.bilimiao.comm.mypage.MenuActions
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.mypage.SearchConfigInfo
import com.a10miaomiao.bilimiao.comm.mypage.myMenu
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
class HistoryPage : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: HistoryPageViewModel = diViewModel()
        BoxWithConstraints {
            HistoryPageContent(viewModel, maxWidth)
        }
    }
}

private class HistoryPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    @Stable
    class HistoryItem(
        val localDate: LocalDate,
        val item: CursorItem?, // 数据为空时表示为日期分割线
    )

    private val pageNavigation by instance<PageNavigation>()
    private val messageDialog by instance<MessageDialogState>()

    /** 自搜索关键字：是"页面内过滤"而不是新页面，用 state 让标题/返回键即时同步 */
    var keyword by mutableStateOf("")

    /**
     * 顶部历史分类（对齐 PiliPlus：全部 / 视频 / 直播 / 专栏）。
     *
     * business 直接传给 `HistoryGRPC.CursorV2`；bilibili 的 proto 注释里
     * `all:全部 archive:视频 live:直播 article:专栏` 与 PiliPlus 的 type=all 一致。
     */
    val tabs = listOf(
        HistoryTab("all", "全部"),
        HistoryTab("archive", "视频"),
        HistoryTab("live", "直播"),
        HistoryTab("article", "专栏"),
    )
    var selectedTabIndex by mutableStateOf(0)
        private set
    val selectedBusiness: String get() = tabs[selectedTabIndex].business

    data class HistoryTab(val business: String, val name: String)

    /** 切换顶部 tab：清游标、清选中、重新拉当前分类（搜索关键字一并清掉）。 */
    fun switchTab(index: Int) {
        if (index == selectedTabIndex || index !in tabs.indices) return
        selectedTabIndex = index
        keyword = ""
        clearSelectedItemMap()
        _maxId = 0L
        _viewAt = 0L
        _mapTp = 3
        list.reset()
        loadData(0L)
    }

    val isRefreshing = MutableStateFlow(false)
    val list = FlowPaginationInfo<HistoryItem>()
    private val _selectedItemMap = mutableStateMapOf<Long, Int>()
    val selectedItemMap: Map<Long, Int> get() = _selectedItemMap

    private var _mapTp = 3
    private var _maxId = 0L
    private var _viewAt = 0L

    init {
        loadData(0L)
    }

    fun clearSelectedItemMap() {
        _selectedItemMap.clear()
    }

    fun addSelectedItem(key: Long, i: Int) {
        _selectedItemMap[key] = i
    }

    fun removeSelectedItem(key: Long) {
        _selectedItemMap.remove(key)
    }

    fun getDateByCursorItem(item: CursorItem): LocalDate {
        return Instant.fromEpochMilliseconds(item.viewAt * 1000)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
    }

    private fun loadData(
        maxId: Long = _maxId
    ) = viewModelScope.launch(Dispatchers.IO) {
        val requestBusiness = selectedBusiness
        try {
            list.loading.value = true
            list.fail.value = ""   // 开始加载就清掉上一次的失败提示
            val keywordText = keyword
            val itemList = if (keywordText.isBlank()) {
                loadList(maxId, requestBusiness)
            } else {
                searchList(keywordText, maxId + 1, requestBusiness)
            }
            // 请求期间用户已经切了 tab：旧响应不能拼进新列表
            if (requestBusiness != selectedBusiness) return@launch
            val newListData = if (maxId == 0L) {
                mutableListOf<HistoryItem>()
            } else {
                list.data.value.toMutableList()
            }
            var prevItem = newListData.lastOrNull()
            itemList.forEach {
                val localData = getDateByCursorItem(it)
                if (prevItem?.localDate != localData) {
                    // 添加日期分割
                    newListData.add(
                        HistoryItem(
                            item = null,
                            localDate = localData,
                        )
                    )
                }
                prevItem = HistoryItem(
                    item = it,
                    localDate = localData,
                ).also(newListData::add)
            }
            list.data.value = newListData
        } catch (e: Exception) {
            e.printStackTrace()
            list.fail.value = e.message ?: e.toString()
        } finally {
            list.loading.value = false
            isRefreshing.value = false
        }
    }

    /**
     * 拉取观看历史列表（Web cursor，PiliPlus 同款）。
     *
     * `type=all` 实测会返回 archive/live/article 混合，因此「全部」不需要再赌
     * gRPC `business=all` 是否被服务端接受。返回后映射回项目里既有的 [CursorItem] 模型，
     * 这样列表 UI / 删除逻辑完全不需要改。
     */
    private suspend fun loadList(
        maxId: Long,
        business: String,
    ): List<CursorItem> {
        val res = BiliApiService.historyApi
            .cursor(
                type = business,
                max = maxId,
                viewAt = _viewAt,
            )
            .awaitCall()
            .json<WebHistoryResponse>()
        if (res.code != 0) {
            throw Exception(res.message.ifBlank { "历史记录加载失败" })
        }
        val data = res.data ?: return emptyList()
        val items = data.list.orEmpty()
        val cursor = data.cursor
        val cursorMax = cursor?.max ?: 0L
        val cursorViewAt = cursor?.view_at ?: 0L
        if (cursorMax > 0L || cursorViewAt > 0L) {
            _maxId = cursorMax
            _viewAt = cursorViewAt
        } else {
            val last = items.lastOrNull()
            _maxId = last?.history?.oid ?: 0L
            _viewAt = last?.view_at ?: 0L
        }
        // Web cursor 没有 has_more：按"这一页是否拿满"近似判断
        list.finished.value = items.size < 20
        return items.map { it.toCursorItem() }
    }

    /** Web 历史 JSON → 项目既有的 gRPC CursorItem 模型，UI/删除逻辑零改动。 */
    private fun WebHistoryItem.toCursorItem(): CursorItem {
        val h = history ?: WebHistory()
        val business = h.business.orEmpty()
        val coverText = cover.orEmpty()
        val articleCovers = covers?.ifEmpty { listOf(coverText) } ?: listOf(coverText)
        val cardItem: CursorItem.CardItem<*> = when {
            business == "live" -> CursorItem.CardItem.CardLive(
                bilibili.app.interfaces.v1.CardLive(
                    cover = coverText,
                    name = author_name.orEmpty(),
                    mid = author_mid ?: 0L,
                    tag = tag_name.orEmpty(),
                    ststus = live_status ?: 0,
                )
            )
            business.contains("article") -> CursorItem.CardItem.CardArticle(
                bilibili.app.interfaces.v1.CardArticle(
                    covers = articleCovers,
                    name = author_name.orEmpty(),
                    mid = author_mid ?: 0L,
                    badge = badge.orEmpty(),
                )
            )
            business == "pgc" || business == "bangumi" -> CursorItem.CardItem.CardOgv(
                bilibili.app.interfaces.v1.CardOGV(
                    cover = coverText,
                    progress = progress ?: 0L,
                    duration = duration ?: 0L,
                    badge = badge.orEmpty(),
                )
            )
            business == "cheese" -> CursorItem.CardItem.CardCheese(
                bilibili.app.interfaces.v1.CardCheese(
                    cover = coverText,
                    progress = progress ?: 0L,
                    duration = duration ?: 0L,
                )
            )
            else -> CursorItem.CardItem.CardUgc(
                bilibili.app.interfaces.v1.CardUGC(
                    cover = coverText,
                    progress = progress ?: 0L,
                    duration = duration ?: 0L,
                    name = author_name.orEmpty(),
                    mid = author_mid ?: 0L,
                    cid = h.cid ?: 0L,
                    page = h.page ?: 0,
                    bvid = h.bvid.orEmpty(),
                )
            )
        }
        return CursorItem(
            title = title.orEmpty(),
            uri = uri.orEmpty(),
            viewAt = view_at ?: 0L,
            kid = kid ?: 0L,
            oid = h.oid ?: 0L,
            business = business,
            cardItem = cardItem,
        )
    }

    private suspend fun searchList(
        keywordText: String,
        pageNum: Long,
        business: String,
    ): List<CursorItem> {
        val req = bilibili.app.interfaces.v1.SearchReq(
            business = business,
            keyword = keywordText,
            pn = pageNum,
        )
        val res = BiliGRPCHttp.request {
            HistoryGRPC.search(req)
        }.awaitCall()
        _maxId = res.page?.pn ?: 0
        list.finished.value = !res.hasMore
        return res.items
    }

    fun deleteHistory(kids: Set<Long>) = viewModelScope.launch(Dispatchers.IO) {
        try {
            messageDialog.loading("操作请求中")
            val deleteItems = mutableListOf<CursorItem>()
            val newItems = mutableListOf<HistoryItem>()
            list.data.value.forEach {
                val item = it.item
                if (item != null && kids.indexOf(item.kid) != -1) {
                    deleteItems.add(item)
                } else {
                    newItems.add(it)
                }
            }
            val req = bilibili.app.interfaces.v1.DeleteReq(
                hisInfo = deleteItems.map {
                    bilibili.app.interfaces.v1.HisInfo(
                        business = it.business,
                        kid = it.kid,
                    )
                }
            )
            BiliGRPCHttp.request {
                HistoryGRPC.delete(req)
            }.awaitCall()
            list.data.value = newItems
            toast("已删除选中的${deleteItems.size}个记录")
            clearSelectedItemMap()
        } catch (e: Exception) {
            e.printStackTrace()
            toast("删除失败:$e")
        } finally {
            messageDialog.close()
        }
    }

    fun clearHistoryList() = viewModelScope.launch(Dispatchers.IO) {
        try {
            messageDialog.loading("操作请求中")
            // 「全部」tab 下要把视频/直播/专栏三块的云端历史都清掉；
            // 具体分类 tab 只清自己那块（清空语义跟 UI 口径一致）。
            val businesses = if (selectedBusiness == "all") {
                listOf("archive", "live", "article")
            } else {
                listOf(selectedBusiness)
            }
            var lastError: Exception? = null
            businesses.forEach { business ->
                try {
                    BiliGRPCHttp.request {
                        HistoryGRPC.clear(bilibili.app.interfaces.v1.ClearReq(business = business))
                    }.awaitCall()
                } catch (e: Exception) {
                    e.printStackTrace()
                    lastError = e
                }
            }
            lastError?.let { throw it }
            list.data.value = listOf()
        } catch (e: Exception) {
            e.printStackTrace()
            toast("操作失败:$e")
        } finally {
            messageDialog.close()
        }
    }

    private fun tryAgainLoadData() {
        loadData()
    }

    fun loadMore() {
        if (!list.finished.value && !list.loading.value) {
            loadData(_maxId)
        }
    }

    fun refreshList() {
        isRefreshing.value = true
        _maxId = 0L
        _viewAt = 0L
        list.reset()
        loadData(0L)
    }

    fun toVideoDetail(item: CursorItem) {
        when(item.business) {
            "archive" -> {
                pageNavigation.navigateToVideoInfo(item.oid.toString())
            }
            "pgc" -> {
                pageNavigation.navigate(SeasonCheckPage(
                    id = item.kid.toString()
                ))
            }
            "article", "article-list" -> toArticleDetail(item)
            "live" -> toast("直播记录请点卡片进入") // 真正入口在 UI（需要 Context 调 enterLiveRoom）
            else -> {
                toast("未知类型:${item.business}")
            }
        }

    }

    /**
     * 专栏历史：优先从 uri / oid 解析 cv 号，进原生专栏阅读页；
     * 解析不出来再走 BilibiliNavigation（深链/网页兜底）。
     */
    fun toArticleDetail(item: CursorItem) {
        val raw = item.uri
        val cvId = Regex("(?i)(?:read/cv|read/mobile/|article/|cv)(\\d{1,})")
            .find(raw)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: item.oid.takeIf { it > 0L && it < 1_000_000_000_000L }
        if (cvId != null && cvId > 0L) {
            pageNavigation.navigate(ArticleReaderPage(cvId))
            return
        }
        if (raw.isNotBlank() && BilibiliNavigation.navigationTo(pageNavigation, raw)) {
            return
        }
        if (raw.isNotBlank()) {
            BilibiliNavigation.navigationToWeb(pageNavigation, raw)
        } else {
            toast("无法打开该专栏")
        }
    }

    fun searchSelfPage(text: String) {
        keyword = text
        _selectedItemMap.clear()
        refreshList()
    }
}


@Composable
private fun HistoryPageContent(
    viewModel: HistoryPageViewModel,
    pageWidth: Dp,
) {

    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val showClearTipsDialog = remember {
        mutableStateOf(false)
    }
    val enableEditMode = remember {
        mutableStateOf(false)
    }

    fun clearHistoryList() {
        showClearTipsDialog.value = false
        viewModel.clearHistoryList()
    }

    fun menuItemClick (view: View, menuItem: MenuItemPropInfo) {
        when(menuItem.key) {
            MenuKeys.clear -> {
                showClearTipsDialog.value = true
            }
            MenuKeys.edit -> {
                viewModel.clearSelectedItemMap()
                enableEditMode.value = true
            }
            MenuKeys.delete -> {
                val selectedKeys = viewModel.selectedItemMap.keys
                if (selectedKeys.isEmpty()) {
                    toast("未选中任何视频")
                } else {
                    viewModel.deleteHistory(selectedKeys)
                }
            }
            MenuKeys.complete -> {
                enableEditMode.value = false
            }
        }
    }
    val grayIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f).toArgb()
    val pageConfigId = PageConfig(
        title = if (viewModel.keyword.isBlank()) "历史记录"
            else "搜索历史\n-\n${viewModel.keyword}",
        menu = rememberMyMenu(enableEditMode.value) {
            if (enableEditMode.value) {
                myItem {
                    key = MenuKeys.complete
                    title = "完成编辑"
                    iconFileName = "ic_baseline_check_24"
                }
                myItem {
                    key = MenuKeys.delete
                    title = "删除选中"
                    iconFileName = "ic_baseline_delete_outline_24"
                }
            } else {
                myItem {
                    key = MenuKeys.more
                    title = "更多"
                    iconFileName = "ic_more_vert_grey_24dp"
                    childMenu = myMenu {
                        myItem {
                            key = MenuKeys.edit
                            title = "批量管理"
                            iconFileName = "ic_baseline_edit_note_24"
                        }
                        myItem {
                            key = MenuKeys.clear
                            title = "清空历史记录"
                        }
                    }
                }
                myItem {
                    key = MenuKeys.search
                    action = MenuActions.search
                    title = "搜索"
                    iconFileName = "ic_search_gray"
                }
            }
        },
        search = SearchConfigInfo(
            name = "搜索历史记录",
            keyword = viewModel.keyword,
        )
    )
    PageListener(
        configId = pageConfigId,
        onMenuItemClick = ::menuItemClick,
        onSearchSelfPage = viewModel::searchSelfPage
    )
    BackHandler(
        enabled = enableEditMode.value || viewModel.keyword.isNotBlank(),
        onBack = {
            // 自搜索只是页面内过滤，不是独立页面：返回键先退出搜索状态（恢复完整历史列表），
            // 再按一次才离开本页。否则用户搜完一按返回就被弹回上级页面，
            // 看起来就像"返回没回到观看历史界面"。
            if (enableEditMode.value) {
                enableEditMode.value = false
            } else {
                viewModel.searchSelfPage("")
            }
        }
    )

    val listFlow = viewModel.list
    val list by listFlow.data.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val listState = rememberLazyGridState()
    val calendarListState = rememberLazyListState()
    val sideTimeline = pageWidth >= 650.dp

    val today = remember {
        val now = java.lang.System.currentTimeMillis()
        kotlin.time.Instant.fromEpochMilliseconds(now).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
    }
    val currentDate = remember {
        mutableStateOf(today)
    }

    // 切顶部 tab 后列表/日期时间线都回到最新一端
    LaunchedEffect(viewModel.selectedTabIndex) {
        listState.scrollToItem(0)
        calendarListState.scrollToItem(0)
        currentDate.value = today
    }

    LaunchedEffect(listState, calendarListState) {
        launch {
            snapshotFlow { listState.firstVisibleItemIndex }
                .collectLatest {
                    if (list.size > it) {
                        val itemDate = list[it].localDate
                        calendarListState.animateScrollToItem(
                            itemDate.daysUntil(today)
                        )
                        currentDate.value = itemDate
                    }
                }
        }
    }

    fun scrollToDate(date: LocalDate) {
        scope.launch {
            val index = withContext(Dispatchers.IO) {
                list.indexOfFirst {
                    it.localDate == date
                }
            }
            if (index != -1) {
                listState.scrollToItem(index)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(windowInsets.toPaddingValues(
                bottom = 0.dp
            ))
    ) {
        TabRow(
            selectedTabIndex = viewModel.selectedTabIndex,
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            viewModel.tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = index == viewModel.selectedTabIndex,
                    onClick = { viewModel.switchTab(index) },
                    text = { Text(tab.name) },
                )
            }
        }
        CalendarRowView(
            modifier = Modifier
                .fillMaxWidth(),
            listState = calendarListState,
            startDate = today,
            endDate = list.lastOrNull()?.localDate,
            currentDate = currentDate.value,
            onChangeDate = ::scrollToDate,
        )
        HistoryListView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            viewModel = viewModel,
            bottomEdgePadding = windowInsets.bottomDp.dp,
            sideTimeline = sideTimeline,
            listState = listState,
            enableEdit = enableEditMode.value,
        )
    }


    if (showClearTipsDialog.value) {
        OverlayAlertDialog(
            onDismissRequest = {
                showClearTipsDialog.value = false
            },
            title = {
                Text(text = "提示")
            },
            text = {
                Text(text = "确认清空历史记录(⊙ˍ⊙)？")
            },
            confirmButton = {
                TextButton(onClick = ::clearHistoryList) {
                    Text(text = "确认")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClearTipsDialog.value = false
                }) {
                    Text(text = "取消")
                }
            }
        )
    }
}

private object LocalDayOfWeekNames {
    val CHINESE_ABBREVIATED: DayOfWeekNames = DayOfWeekNames(
        listOf(
            "一", "二", "三", "四", "五", "六", "日"
        )
    )

    val CHINESE_FULL: DayOfWeekNames = DayOfWeekNames(
        listOf(
            "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"
        )
    )
}

@Composable
private fun CalendarRowView(
    modifier: Modifier,
    listState: LazyListState,
    startDate: LocalDate,
    endDate: LocalDate?,
    currentDate: LocalDate,
    onChangeDate: (LocalDate) -> Unit,
) {
    Column(
        modifier = modifier,
    ) {
        StickyHeaders(
            modifier = Modifier.fillMaxWidth(),
            state = listState,
            key = { item ->
                val date = startDate.minus(item.index, DateTimeUnit.DAY)
                LocalDate(date.year, date.month, 1)
            },
        ) {
            val formatter = LocalDate.Format {
                year()
                chars("年")
                monthNumber()
                chars("月")
            }
            Text(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                text = it.key.format(formatter),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        LazyRow(
            state = listState,
        ) {
            items(
                count = Int.MAX_VALUE,
                key = { it },
            ) {
                val date = startDate.minus(it, DateTimeUnit.DAY)

                val formatter = LocalDate.Format {
                    dayOfWeek(LocalDayOfWeekNames.CHINESE_ABBREVIATED)
                }

                val dateHeader = formatter.format(date).let { day ->
                    day.firstOrNull()?.toString() ?: day
                }

                val isEnable = date >= (endDate ?: startDate)
                val color = if (isEnable)
                    MaterialTheme.colorScheme.onBackground
                else
                    MaterialTheme.colorScheme.outlineVariant
                Column(
                    modifier = Modifier
                        .size(40.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = dateHeader,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                    )
                    if (date == currentDate) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.secondary,
                                    shape = CircleShape,
                                )
                                .size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${date.dayOfMonth}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondary,
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .run {
                                    if (isEnable) clickable { onChangeDate(date) }
                                    else this
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                modifier = Modifier,
                                text = "${date.dayOfMonth}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = color,
                            )
                        }
                    }

                }
            }
        }
    }
}



@Composable
private fun HistoryListView(
    modifier: Modifier,
    viewModel: HistoryPageViewModel,
    bottomEdgePadding: Dp,
    sideTimeline: Boolean,
    listState: LazyGridState,
    enableEdit: Boolean,
) {
    val context = LocalContext.current
    val listFlow = viewModel.list
    val list by listFlow.data.collectAsStateWithLifecycle()
    val listLoading by listFlow.loading.collectAsStateWithLifecycle()
    val listFinished by listFlow.finished.collectAsStateWithLifecycle()
    val listFail by listFlow.fail.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    SwipeToRefresh(
        modifier = modifier,
        refreshing = isRefreshing,
        onRefresh = viewModel::refreshList,
    ) {
        LazyVerticalGrid(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            columns = GridCells.Adaptive(300.dp),
            contentPadding = PaddingValues(
                start = if (sideTimeline) {
                    50.dp
                } else {
                    0.dp
                }
            )
        ) {
            items(
                list.size,
                span = {
                    if (list[it].item == null) {
                        GridItemSpan(maxLineSpan)
                    } else {
                        GridItemSpan(1)
                    }
                },
                contentType = { if (list[it].item == null) 0 else 1 }
            ) { index ->
                val item = list[index].item
                if (item == null) {
                    Spacer(modifier = Modifier.height(if (sideTimeline) 10.dp else 30.dp))
                } else {
                    val isChecked = enableEdit && viewModel.selectedItemMap.containsKey(item.kid)

                    fun toggleSelect() {
                        if (isChecked) {
                            viewModel.removeSelectedItem(item.kid)
                        } else {
                            viewModel.addSelectedItem(item.kid, index)
                        }
                    }

                    Box(
                        contentAlignment = Alignment.CenterStart
                    ) {
                        when (item.business) {
                            // ── 直播：卡片显示直播中/未开播；开播中点进原生直播间，未开播提示 ──
                            "live" -> {
                                val live = item.cardLive
                                val isLiving = live?.ststus == 1
                                VideoItemBox(
                                    modifier = Modifier
                                        .run {
                                            if (enableEdit) alpha(0.6f)
                                            else this
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    title = item.title,
                                    pic = live?.cover?.takeIf { it.isNotBlank() },
                                    upperName = live?.name,
                                    remark = if (isLiving) "直播中" else "未开播",
                                    duration = null,
                                    playNum = null,
                                    damukuNum = null,
                                    onClick = {
                                        if (enableEdit) {
                                            toggleSelect()
                                        } else if (isLiving) {
                                            enterLiveRoom(context, item.oid)
                                        } else {
                                            toast("直播未开播")
                                        }
                                    }
                                )
                            }
                            // ── 专栏：复用搜索页的 ArticleItemBox（标题/封面/作者） ──
                            "article", "article-list" -> {
                                ArticleItemBox(
                                    modifier = Modifier
                                        .run {
                                            if (enableEdit) alpha(0.6f)
                                            else this
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    title = item.title,
                                    cover = item.cardArticle?.covers?.firstOrNull().orEmpty(),
                                    author = item.cardArticle?.name.orEmpty(),
                                    onClick = {
                                        if (enableEdit) {
                                            toggleSelect()
                                        } else {
                                            viewModel.toVideoDetail(item)
                                        }
                                    }
                                )
                            }
                            // ── 视频 / 番剧 / 课程：保持原有卡片逻辑 ──
                            else -> {
                                val duration = item.cardOgv?.duration ?: item.cardUgc?.duration ?: 0
                                val progress = item.cardOgv?.progress ?: item.cardUgc?.progress ?: 0
                                val progressRatio = if (duration > 0L) progress.toFloat() / duration.toFloat() else 0f
                                VideoItemBox(
                                    modifier = Modifier
                                        .run {
                                            if (enableEdit) alpha(0.6f)
                                            else this
                                        }
                                        .padding(
                                            horizontal = 10.dp,
                                            vertical = 5.dp
                                        ),
                                    title = item.title,
                                    pic = item.cardOgv?.cover
                                        ?: item.cardUgc?.cover,
                                    upperName = item.cardUgc?.name,
                                    remark = NumberUtil.converCTime(item.viewAt),
                                    duration = if (progressRatio >= 0.95f) {
                                        "已看完"
                                    } else if (progressRatio > 0f) {
                                        "${NumberUtil.converDuration(progress)}/${NumberUtil.converDuration(duration)}"
                                    } else {
                                        NumberUtil.converDuration(duration)
                                    },
                                    progress = progressRatio,
                                    isHtml = true,
                                    onClick = {
                                        if (enableEdit) {
                                            toggleSelect()
                                        } else {
                                            viewModel.toVideoDetail(item)
                                        }
                                    }
                                )
                            }
                        }

                        if (enableEdit) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = {
                                    if (it) {
                                        viewModel.addSelectedItem(item.kid, index)
                                    } else {
                                        viewModel.removeSelectedItem(item.kid)
                                    }
                                }
                            )
                        }
                    }
                }
            }
            item(
                span = { GridItemSpan(maxLineSpan) }
            ) {
                ListStateBox(
                    modifier = Modifier.padding(
                        bottom = bottomEdgePadding
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

        StickyHeaders(
            modifier = Modifier
                .fillMaxHeight(),
            state = listState,
            key = { item ->
                item.firstOrNull()?.let {
                    list.getOrNull(it.index)?.localDate
                }
            },
        ) {
            if (sideTimeline) {
                Column(
                    modifier = Modifier
                        .width(50.dp)
                        .padding(top = 10.dp, end = 5.dp)
                        .background(MaterialTheme.colorScheme.background),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val formatter = LocalDate.Format {
                        dayOfWeek(LocalDayOfWeekNames.CHINESE_FULL)
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            )
                            .size(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            modifier = Modifier.padding(bottom = 2.dp),
                            textAlign = TextAlign.Center,
                            text = "${it.key.dayOfMonth}",
                            color = MaterialTheme.colorScheme.surface
                        )
                    }
                    Text(
                        modifier = Modifier.padding(top = 5.dp),
                        text = it.key.format(formatter),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                val formatter = LocalDate.Format {
                    monthNumber()
                    chars("-")
                    dayOfMonth()
                    chars(" ")
                    dayOfWeek(LocalDayOfWeekNames.CHINESE_FULL)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(end = 5.dp)
                            .background(
                                color = MaterialTheme.colorScheme.secondary,
                                shape = CircleShape,
                            )
                            .size(10.dp),
                    )
                    Text(
                        text = it.key.format(formatter),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }

        }

    }
}