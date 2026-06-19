package cn.a10miaomiao.bilimiao.compose.pages.user.content

import android.net.Uri
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bilibili.app.view.v1.ViewGRPC
import cn.a10miaomiao.bilimiao.compose.assets.BilimiaoIcons
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.Common
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.common.Menufold
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.common.Menuunfold
import cn.a10miaomiao.bilimiao.compose.common.defaultNavOptions

import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.mypage.rememberMyMenu
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.components.video.VideoItemBox
import cn.a10miaomiao.bilimiao.compose.pages.playlist.PlayListPage
import cn.a10miaomiao.bilimiao.compose.pages.user.components.TitleBar
import com.a10miaomiao.bilimiao.comm.delegate.player.BasePlayerDelegate
import com.a10miaomiao.bilimiao.comm.delegate.player.VideoPlayerSource
import com.a10miaomiao.bilimiao.comm.entity.MessageInfo
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.archive.ArchiveRelationInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.mypage.SearchConfigInfo
import com.a10miaomiao.bilimiao.comm.mypage.myMenu
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.PlayListStore
import com.a10miaomiao.bilimiao.comm.store.PlayerStore
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoDownloadDialog
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoDownloadDialogState
import cn.a10miaomiao.bilimiao.download.DownloadService
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

private enum class SeasonSortMode(val label: String) {
    TIME_DESC("最新发布"),
    TIME_ASC("最旧发布"),
    VIEW_DESC("最多播放"),
}

private class UserSeasonDetailViewModel(
    override val di: DI,
    private val sid: String,
) : ViewModel(), DIAware {

    private val activity by instance<android.app.Activity>()

    private val pageNavigation: PageNavigation by instance()
    val userStore: UserStore by instance()
    private val playerDelegate: BasePlayerDelegate by instance()
    private val playerStore by instance<PlayerStore>()
    private val playListStore by instance<PlayListStore>()

    var seasonInfo = MutableStateFlow<bilibili.app.view.v1.UgcSeason?>(null)
    val isRefreshing = MutableStateFlow(false)
    val curSection = MutableStateFlow<bilibili.app.view.v1.Section?>(null)
    val sections = MutableStateFlow<List<bilibili.app.view.v1.Section>>(listOf())
    val favState = MutableStateFlow(-1)
    val list = FlowPaginationInfo<bilibili.app.view.v1.Episode>()
    val isAutoPlay = MutableStateFlow(false)
    val sortMode = MutableStateFlow(SeasonSortMode.TIME_DESC)
    val searchKeyword = MutableStateFlow("")
    val downloadDialogState = VideoDownloadDialogState(viewModelScope)
    private var originalEpisodes: List<bilibili.app.view.v1.Episode> = emptyList()

    init {
        loadData(1)
    }

    private fun loadData(
        pageNum: Int = list.pageNum
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            list.loading.value = true
            val req = bilibili.app.view.v1.SeasonReq(
                seasonId = sid.toLong(),
            )
            val res = BiliGRPCHttp.request {
                ViewGRPC.season(req)
            }.awaitCall()
            seasonInfo.value = res.season
            sections.value = res.season?.sections ?: listOf()
            if (sections.value.isNotEmpty()) {
                setCurrentSection(sections.value[0])
            }
            list.finished.value = true
            list.pageNum = pageNum
            if (favState.value == -1 && userStore.isLogin()) {
                // 获取订阅状态
                val firstEp = sections.value.firstOrNull()
                    ?.episodes
                    ?.firstOrNull()
                if (firstEp == null) {
                    favState.value = 1
                } else {
                    getFavState(firstEp.aid.toString())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            list.fail.value = "网络请求失败"
        } finally {
            list.loading.value = false
            isRefreshing.value = false
        }
    }

    /**
     * 获取订阅状态
     */
    private suspend fun getFavState(aid: String) {
        try {
            val res = BiliApiService.archiveApi.relation(
                aid = aid
            ).awaitCall().json<ResponseData<ArchiveRelationInfo>>()
            favState.value = if (res.requireData().season_fav) 1 else 0
        } catch (e: Exception) {
            toast("获取订阅状态失败")
            e.printStackTrace()
        }
    }

    fun tryAgainLoadData() = loadData()

    fun refresh() {
        isRefreshing.value = true
        list.finished.value = false
        list.fail.value = ""
        loadData(1)
    }

    fun loadMore() {
        if (!list.finished.value && !list.loading.value) {
            loadData(list.pageNum + 1)
        }
    }

    fun changeAutoPlay(value: Boolean) {
        isAutoPlay.value = value
    }

    fun openVideo(item: bilibili.app.view.v1.Episode) {
        if (isAutoPlay.value) {
            addPlayList()
            val id = item.cid.toString()
            if (playerStore.state.cid != id) {
                playerDelegate.openPlayer(
                    VideoPlayerSource(
                        mainTitle = item.title,
                        title = item.title,
                        coverUrl = item.cover,
                        aid = item.aid.toString(),
                        id = id,
                        ownerId = item.author?.mid.toString(),
                        ownerName = item.author?.name.toString(),
                    )
                )
            }
        } else {
            pageNavigation.navigateToVideoInfo(item.aid.toString())
        }
    }

    fun setCurrentSection(section: bilibili.app.view.v1.Section) {
        curSection.value = section
        originalEpisodes = section.episodes
        list.data.value = applySort(originalEpisodes, sortMode.value)
    }

    fun changeSortMode(mode: SeasonSortMode) {
        sortMode.value = mode
        val source = if (searchKeyword.value.isNotBlank()) {
            originalEpisodes.filter { it.title.contains(searchKeyword.value, ignoreCase = true) }
        } else {
            originalEpisodes
        }
        list.data.value = applySort(source, mode)
    }

    private fun applySort(
        episodes: List<bilibili.app.view.v1.Episode>,
        mode: SeasonSortMode,
    ): List<bilibili.app.view.v1.Episode> {
        return when (mode) {
            SeasonSortMode.TIME_DESC -> episodes.sortedWith(compareByDescending<bilibili.app.view.v1.Episode> { it.aid.toLong() })
            SeasonSortMode.TIME_ASC -> episodes.sortedWith(compareBy<bilibili.app.view.v1.Episode> { it.aid.toLong() })
            SeasonSortMode.VIEW_DESC -> episodes.sortedWith(compareByDescending<bilibili.app.view.v1.Episode> { it.stat?.view ?: 0L })
        }
    }

    fun addPlayList() {
        val season = seasonInfo.value
        if (season == null) {
            toast("数据加载中，请稍后再试")
            return
        }
        val currentId = curSection.value?.id
        val index = sections.value.indexOfFirst {
            currentId == it.id
        }
        playListStore.setPlayList(season, index)
    }

    fun toPlayListPage() {
        pageNavigation.navigate(PlayListPage())
    }

    fun favSeason() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val res = BiliApiService.userApi
                .favFavSeason(
                    seasonId = sid,
                )
                .awaitCall()
                .json<MessageInfo>()
            if (res.isSuccess) {
                toast("订阅成功")
                favState.value = 1
            } else {
                toast(res.message)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            toast(e.message ?: e.toString())
        }
    }

    fun unfavSeason() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val res = BiliApiService.userApi
                .favUnfavSeason(
                    seasonId = sid,
                )
                .awaitCall()
                .json<MessageInfo>()
            if (res.isSuccess) {
                toast("已取消订阅")
                favState.value = 0
            } else {
                toast(res.message)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            toast(e.message ?: e.toString())
        }
    }

    fun openDownloadDialog() {
        val allItems = list.data.value.toList()
        if (allItems.isEmpty()) {
            toast("没有可下载的视频")
            return
        }
        val seasonEpisodes = allItems.map { item ->
            VideoDownloadDialogState.SeasonEpisodeItem(
                aid = item.aid,
                title = item.title,
                cover = item.cover,
                duration = NumberUtil.converDuration(item.page?.duration ?: 0),
            )
        }
        viewModelScope.launch {
            val downloadService = DownloadService.getService(activity)
            downloadDialogState.show(
                service = downloadService,
                bvid = "",
                videoArc = null,
                videoPages = emptyList(),
                context = activity,
                ugcSeasonEpisodes = seasonEpisodes,
                seasonId = sid,
                seasonTitle = seasonInfo.value?.title ?: "",
            )
        }
    }

    fun openDownloadDialog(filteredItems: List<bilibili.app.view.v1.Episode>) {
        if (filteredItems.isEmpty()) {
            toast("没有可下载的视频")
            return
        }
        val seasonEpisodes = filteredItems.map { item ->
            VideoDownloadDialogState.SeasonEpisodeItem(
                aid = item.aid,
                title = item.title,
                cover = item.cover,
                duration = NumberUtil.converDuration(item.page?.duration ?: 0),
            )
        }
        viewModelScope.launch {
            val downloadService = DownloadService.getService(activity)
            downloadDialogState.show(
                service = downloadService,
                bvid = "",
                videoArc = null,
                videoPages = emptyList(),
                context = activity,
                ugcSeasonEpisodes = seasonEpisodes,
                seasonId = sid,
                seasonTitle = seasonInfo.value?.title ?: "",
            )
        }
    }

    fun searchSelfPage(text: String) {
        searchKeyword.value = text
        if (text.isBlank()) {
            list.data.value = applySort(originalEpisodes, sortMode.value)
            return
        }
        // 在后台线程过滤，避免大数据量卡UI
        viewModelScope.launch(Dispatchers.Default) {
            val filtered = originalEpisodes.filter { episode ->
                episode.title.contains(text, ignoreCase = true)
            }
            val sorted = applySort(filtered, sortMode.value)
            list.data.value = sorted
        }
    }


    fun cleanFav() = viewModelScope.launch(Dispatchers.IO) {
        try {
            toast("已清除失效内容")
            refresh()
        } catch (e: Exception) {
            e.printStackTrace()
            toast(e.message ?: e.toString())
        }
    }

    fun menuItemClick(view: View, item: MenuItemPropInfo) {
        when (item.key) {
            MenuKeys.playList -> {
                addPlayList()
                toPlayListPage()
            }
            MenuKeys.follow -> {
                favSeason()
            }
            -MenuKeys.follow -> {
                unfavSeason()
            }
            MenuKeys.clear -> {
                cleanFav()
            }
            in 0..2 -> {
                val key = item.key ?: return
                val mode = SeasonSortMode.entries[key]
                changeSortMode(mode)
            }
        }
    }
}

@Composable
internal fun UserSeasonDetailContent(
    seasonId: String,
    seasonTitle: String,
    showTowPane: Boolean,
    hideFirstPane: Boolean,
    onChangeHideFirstPane: (hidden: Boolean) -> Unit,
) {
    val viewModel = diViewModel(
        key = seasonId,
    ) {
        UserSeasonDetailViewModel(it, seasonId)
    }
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val list by viewModel.list.data.collectAsState()
    val listLoading by viewModel.list.loading.collectAsState()
    val listFinished by viewModel.list.finished.collectAsState()
    val listFail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isAutoPlay by viewModel.isAutoPlay.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()

    val detailInfo by viewModel.seasonInfo.collectAsState()
    val sections by viewModel.sections.collectAsState()
    val curSection by viewModel.curSection.collectAsState()
    val favState by viewModel.favState.collectAsState()

    // 🔧 搜索功能
    var searchQuery by remember { mutableStateOf("") }
    var filteredList by remember { mutableStateOf(list) }

    LaunchedEffect(list, searchQuery) {
        if (searchQuery.isBlank()) {
            filteredList = list
        } else {
            delay(300)
            val q = searchQuery
            filteredList = withContext(Dispatchers.Default) {
                list.filter { it.title.contains(q, ignoreCase = true) }
            }
        }
    }


    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    val grayIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f).toArgb()
    val pageConfigId = PageConfig(
        title = "合集详情",
        menu = rememberMyMenu(favState, sortMode) {
            myItem {
                key = MenuKeys.more
                iconFileName = "ic_more_vert_grey_24dp"
                title = "更多"
                childMenu = myMenu {
                    myItem {
                        key = MenuKeys.playList
                        title = "设置为播放列表"
                    }
                    myItem {
                        key = MenuKeys.clear
                        title = "清除失效内容"
                    }
                }
            }
            myItem {
                key = MenuKeys.sort
                iconFileName = "ic_baseline_filter_list_grey_24"
                title = sortMode.label
                childMenu = myMenu {
                    checkable = true
                    checkedKey = sortMode.ordinal
                    SeasonSortMode.entries.forEachIndexed { index, mode ->
                        myItem {
                            key = index
                            title = mode.label
                        }
                    }
                }
            }
            myItem {
                key = MenuKeys.download
                iconFileName = "ic_download_grey_24dp"
                title = "下载"
            }
            if (favState == 1) {
                myItem {
                    key = -MenuKeys.follow
                    iconFileName = "ic_baseline_favorite_24"
                    title = "已订阅"
                    tintColor = primaryColor
                }
            } else if (favState == 0){
                myItem {
                    key = MenuKeys.follow
                    iconFileName = "ic_outline_favorite_border_24"
                    title = "订阅"
                }
            }
        },
    )
    PageListener(
        pageConfigId,
        onMenuItemClick = { view, item ->
            when (item.key) {
                MenuKeys.download -> {
                    viewModel.openDownloadDialog(filteredList)
                }
                else -> viewModel.menuItemClick(view, item)
            }
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        TitleBar(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp + windowInsets.topDp.dp)
                .background(MaterialTheme.colorScheme.background)
                .padding(top = windowInsets.topDp.dp),
            icon = {
                if (showTowPane) {
                    IconButton(
                        onClick = {
                            onChangeHideFirstPane(!hideFirstPane)
                        }
                    ) {
                        Icon(
                            imageVector = if (hideFirstPane) {
                                BilimiaoIcons.Common.Menufold
                            } else {
                                BilimiaoIcons.Common.Menuunfold
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(24.dp),
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(16.dp))
                }
            },
            title = {
                Text(
                    text = seasonTitle,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            action = {
                Text(
                    text = "自动连播",
                    style = MaterialTheme.typography.labelMedium,
                )
                Switch(
                    modifier = Modifier.scale(0.75f),
                    checked = isAutoPlay,
                    onCheckedChange = viewModel::changeAutoPlay,
                )
            }
        )
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            placeholder = {
                Text("搜索合集内视频", color = MaterialTheme.colorScheme.outline)
            },
            leadingIcon = {
                Icon(Icons.Default.Search, "搜索", tint = MaterialTheme.colorScheme.outline)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { }),
            shape = RoundedCornerShape(8.dp),
        )
        if (sections.size > 1) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 5.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                sections.forEach {
                    FilterChip(
                        selected = curSection?.id == it.id,
                        onClick = {
                            viewModel.setCurrentSection(it)
                        },
                        label = {
                            Text(text = it.title)
                        }
                    )
                }
                }
        }
        SwipeToRefresh(
            modifier = Modifier.weight(1f),
            refreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp)
            ) {
                items(filteredList) { item ->
                    VideoItemBox(
                        modifier = Modifier.padding(10.dp),
                        title = item.title,
                        pic = item.cover,
                        upperName = item.author?.name,
                        playNum = item.stat?.view.toString(),
                        damukuNum = item.stat?.danmaku.toString(),
                        duration = NumberUtil.converDuration(item.page?.duration ?: 0),
                        onClick = { viewModel.openVideo(item) }
                    )
                }
                item(
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    ListStateBox(
                        modifier = Modifier.padding(
                            bottom = windowInsets.bottomDp.dp
                        ),
                        loading = listLoading,
                        finished = listFinished,
                        fail = listFail,
                        listData = filteredList,
                    ) {
                        viewModel.loadMore()
                    }
                }
            }
        }
    }

    VideoDownloadDialog(state = viewModel.downloadDialogState)
}