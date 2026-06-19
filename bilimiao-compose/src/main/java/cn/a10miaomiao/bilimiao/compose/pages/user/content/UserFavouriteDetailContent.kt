package cn.a10miaomiao.bilimiao.compose.pages.user.content

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.components.video.VideoItemBox
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.BangumiDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.SeasonCheckPage
import cn.a10miaomiao.bilimiao.compose.pages.playlist.PlayListPage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserFavouriteDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoDownloadDialog
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoDownloadDialogState
import cn.a10miaomiao.bilimiao.compose.pages.user.components.FavouriteEditForm
import cn.a10miaomiao.bilimiao.compose.pages.user.components.FavouriteEditFormState
import cn.a10miaomiao.bilimiao.compose.pages.user.components.TitleBar
import cn.a10miaomiao.bilimiao.download.DownloadService
import com.a10miaomiao.bilimiao.comm.delegate.player.BasePlayerDelegate
import com.a10miaomiao.bilimiao.comm.delegate.player.VideoPlayerSource
import com.a10miaomiao.bilimiao.comm.entity.MessageInfo
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.media.MediaDetailInfo
import com.a10miaomiao.bilimiao.comm.entity.media.MediaListInfo
import com.a10miaomiao.bilimiao.comm.entity.media.MediasInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuActions
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.mypage.SearchConfigInfo
import com.a10miaomiao.bilimiao.comm.mypage.myMenu
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.PlayListStore
import com.a10miaomiao.bilimiao.comm.store.PlayerStore
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

enum class FavSortMode(val label: String) {
    TIME_DESC("最新发布"),
    TIME_ASC("最旧发布"),
    VIEW_DESC("最多播放"),
}

class UserFavouriteDetailViewModel(
    override val di: DI,
    private val mediaId: String,
    private val mediaTitle: String,
    private val keyword: String = "",
) : ViewModel(), DIAware {

    private val activity by instance<Activity>()
    private val pageNavigation: PageNavigation by instance()
    val userStore: UserStore by instance()
    private val playerDelegate: BasePlayerDelegate by instance()
    private val playerStore by instance<PlayerStore>()
    private val playListStore by instance<PlayListStore>()

    var mediaInfo = MutableStateFlow<MediaListInfo?>(null)
    val isRefreshing = MutableStateFlow(false)
    val list = FlowPaginationInfo<MediasInfo>()
    val isAutoPlay = MutableStateFlow(false)
    val downloadDialogState = VideoDownloadDialogState(viewModelScope)
    val sortMode = MutableStateFlow(FavSortMode.TIME_DESC)

    init {
        loadData(1)
    }

    private fun loadData(
        pageNum: Int = list.pageNum
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            list.loading.value = true
            val res = BiliApiService.userApi.mediaDetail(
                media_id = mediaId,
                keyword = keyword,
                pageNum = pageNum,
                pageSize = list.pageSize
            ).awaitCall().json<ResponseData<MediaDetailInfo>>()
            if (res.code == 0) {
                val result = res.requireData()
                val mediaList = result.medias ?: listOf()
                mediaInfo.value = result.info
                if (pageNum == 1) {
                    list.data.value = mediaList
                } else {
                    list.data.value = mutableListOf<MediasInfo>().apply {
                        addAll(list.data.value)
                        addAll(mediaList)
                    }
                }
                list.finished.value = !result.has_more || mediaList.isEmpty()
                list.pageNum = pageNum
                applySort()
            } else {
                list.fail.value = res.message
            }
        } catch (e: Exception) {
            e.printStackTrace()
            list.fail.value = "网络请求失败"
        } finally {
            list.loading.value = false
            isRefreshing.value = false
        }
    }

    fun tryAgainLoadData() = loadData()

    fun refresh() {
        mediaInfo.value = null
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

    fun openVideo(item: MediasInfo) {
        val ugcInfo = item.ugc
        val ogvInfo = item.ogv
        if (isAutoPlay.value) {
            if (ugcInfo != null) {
                addPlayList()
                if (playerStore.state.aid != item.id) {
                    playerDelegate.openPlayer(
                        VideoPlayerSource(
                            mainTitle = item.title,
                            title = item.title,
                            coverUrl = item.cover,
                            aid = item.id,
                            id = ugcInfo.first_cid,
                            ownerId = item.upper.mid,
                            ownerName = item.upper.name,
                        )
                    )
                }
                return
            } else {
                toast("自动连播仅支持普通视频")
            }
        }
        if (ugcInfo != null) {
            pageNavigation.navigateToVideoInfo(item.id)
        } else if (ogvInfo != null) {
            pageNavigation.navigate(SeasonCheckPage(ogvInfo.season_id))
        }
    }

    fun addPlayList() {
        val media = mediaInfo.value
        if (media == null) {
            toast("数据加载中，请稍后再试")
            return
        }
        playListStore.setFavoriteList(media.id, media.title)
    }

    fun openDownloadDialog() {
        val allItems = list.data.value.toList()
        if (allItems.isEmpty()) {
            toast("没有可下载的视频")
            return
        }
        val seasonEpisodes = allItems.map { item ->
            VideoDownloadDialogState.SeasonEpisodeItem(
                aid = item.id.toLong(),
                title = item.title,
                cover = item.cover,
                duration = NumberUtil.converDuration(item.duration),
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
                seasonId = mediaId,
                seasonTitle = mediaTitle,
            )
        }
    }

    fun openDownloadDialog(filteredItems: List<MediasInfo>) {
        if (filteredItems.isEmpty()) {
            toast("没有可下载的视频")
            return
        }
        val seasonEpisodes = filteredItems.map { item ->
            VideoDownloadDialogState.SeasonEpisodeItem(
                aid = item.id.toLong(),
                title = item.title,
                cover = item.cover,
                duration = NumberUtil.converDuration(item.duration),
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
                seasonId = mediaId,
                seasonTitle = mediaTitle,
            )
        }
    }

    fun toPlayListPage() {
        pageNavigation.navigate(PlayListPage())
    }

    suspend fun editFolder(
        title: String,
        cover: String,
        intro: String,
        privacy: Int, // 0:公开,1:不公开
    ) {
        val res = BiliApiService.userApi
            .favEditFolder(
                mediaId = mediaId,
                title = title,
                cover = cover,
                intro = intro,
                privacy = privacy,
            )
            .awaitCall()
            .json<MessageInfo>()
        if (!res.isSuccess) {
            throw Exception(res.message)
        }
    }

    suspend fun deleteFolder() {
        val res = BiliApiService.userApi
            .favDeleteFolder(
                mediaIds = mediaId,
            )
            .awaitCall()
            .json<MessageInfo>(isLog = true)
        if (!res.isSuccess) {
            throw Exception(res.message)
        }
    }

    fun favFolder() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val res = BiliApiService.userApi
                .favFavFolder(
                    mediaId = mediaId,
                )
                .awaitCall()
                .json<MessageInfo>()
            if (res.isSuccess) {
                toast("订阅成功")
                refresh()
            } else {
                toast(res.message)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            toast(e.message ?: e.toString())
        }
    }

    fun unfavFolder() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val res = BiliApiService.userApi
                .favUnfavFolder(
                    mediaId = mediaId,
                )
                .awaitCall()
                .json<MessageInfo>()
            if (res.isSuccess) {
                toast("已取消订阅")
                refresh()
            } else {
                toast(res.message)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            toast(e.message ?: e.toString())
        }
    }

    fun searchSelfPage(text: String) {
        pageNavigation.navigate(UserFavouriteDetailPage(
            id = mediaId,
            title = mediaTitle,
            keyword = text,
        ))
    }


    fun changeSortMode(mode: FavSortMode) {
        sortMode.value = mode
        applySort()
    }

    private fun applySort() {
        val source = list.data.value
        val sorted = when (sortMode.value) {
            FavSortMode.TIME_DESC -> source.sortedByDescending { it.id.toLongOrNull() ?: 0L }
            FavSortMode.TIME_ASC -> source.sortedBy { it.id.toLongOrNull() ?: 0L }
            FavSortMode.VIEW_DESC -> source.sortedByDescending { it.cnt_info.play.toLongOrNull() ?: 0L }
        }
        list.data.value = sorted
    }

    fun cleanFav() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val res = BiliApiService.userApi
                .cleanFavFolder(mediaId)
                .awaitCall()
                .json<MessageInfo>()
            if (res.isSuccess) {
                toast("已清除失效内容")
                refresh()
            } else {
                toast(res.message)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            toast(e.message ?: e.toString())
        }
    }

    fun deleteVideos(ids: List<String>) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val res = BiliApiService.userApi
                .favBatchDelete(mediaId, ids)
                .awaitCall()
                .json<MessageInfo>()
            if (res.isSuccess) {
                toast("已删除${ids.size}项")
                refresh()
            } else {
                toast(res.message)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            toast(e.message ?: e.toString())
        }
    }


    fun isSelfFav(): Boolean {
        return mediaInfo.value?.let {
            userStore.isSelf(it.mid.toString())
        } ?: true
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserFavouriteDetailContent(
    mediaId: String,
    mediaTitle: String,
    keyword: String = "",
    showTowPane: Boolean,
    hideFirstPane: Boolean,
    onChangeHideFirstPane: (hidden: Boolean) -> Unit,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
) {
    val viewModel = diViewModel(key = mediaId + keyword) {
        UserFavouriteDetailViewModel(it, mediaId, mediaTitle, keyword)
    }

    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val detailInfo by viewModel.mediaInfo.collectAsState()
    val list by viewModel.list.data.collectAsState()
    val listLoading by viewModel.list.loading.collectAsState()
    val listFinished by viewModel.list.finished.collectAsState()
    val listFail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isAutoPlay by viewModel.isAutoPlay.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()

    // 🔧 搜索功能 — 后台线程过滤，debounce 防抖
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

    // 编辑模式
    var isEditMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }

    BackHandler(enabled = isEditMode) {
        isEditMode = false
        selectedIds.clear()
    }

    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    val grayIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f).toArgb()
    val pageConfigId = PageConfig(
        title = if (keyword.isBlank()) "收藏详情" else "搜索\n-\n${keyword}",
        menu = remember(detailInfo, sortMode) {
            myMenu {
                val selfFav = viewModel.isSelfFav()
                myItem {
                    key = MenuKeys.more
                    iconFileName = "ic_more_vert_grey_24dp"
                    title = "更多"
                    childMenu = myMenu {
                        myItem {
                            key = MenuKeys.playList
                            title = "设置为播放列表"
                        }
                        if (detailInfo != null && selfFav) {
                            myItem {
                                key = MenuKeys.clear
                                title = "清除失效内容"
                            }
                            myItem {
                                key = MenuKeys.edit
                                title = "编辑收藏夹"
                            }
                            if (detailInfo?.isDefaultFav == false) {
                                myItem {
                                    key = MenuKeys.delete
                                    title = "删除收藏夹"
                                }
                            }
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
                        FavSortMode.entries.forEachIndexed { index, mode ->
                            myItem {
                                key = index
                                title = mode.label
                            }
                        }
                    }
                }

                myItem {
                    key = MenuKeys.select
                    iconFileName = "ic_baseline_edit_24"
                    title = "编辑"
                }
                myItem {
                    key = MenuKeys.download
                    iconFileName = "ic_download_grey_24dp"
                    title = "下载"
                }
                if (detailInfo != null && !selfFav) {
                    myItem {
                        key = MenuKeys.follow
                        if (detailInfo?.fav_state == 1) {
                            iconFileName = "ic_baseline_favorite_24"
                            title = "已订阅"
                            tintColor = primaryColor
                        } else {
                            iconFileName = "ic_outline_favorite_border_24"
                            title = "订阅"
                        }
                    }
                }
            }
        },
    )

    val scope = rememberCoroutineScope()
    var showEditDialog by remember {
        mutableStateOf(false)
    }
    var showDeleteDialog by remember {
        mutableStateOf(false)
    }

    PageListener(
        pageConfigId,
        onMenuItemClick = remember(detailInfo) {
            { _, item ->
                when (item.key) {
                    MenuKeys.follow -> {
                        if (detailInfo?.fav_state == 1) {
                            viewModel.unfavFolder()
                        } else {
                            viewModel.favFolder()
                        }
                    }

                    MenuKeys.edit -> {
                        showEditDialog = true
                    }

                    MenuKeys.delete -> {
                        showDeleteDialog = true
                    }

                    MenuKeys.playList -> {
                        viewModel.addPlayList()
                        viewModel.toPlayListPage()
                    }

                    MenuKeys.download -> {
                        viewModel.openDownloadDialog(filteredList)
                    }

                    in 0..2 -> {
                        val mode = FavSortMode.entries[item.key!!]
                        viewModel.changeSortMode(mode)
                    }

                    MenuKeys.clear -> {
                        viewModel.cleanFav()
                    }

                    MenuKeys.select -> {
                        if (isEditMode) {
                            isEditMode = false
                            selectedIds.clear()
                        } else {
                            isEditMode = true
                        }
                    }
                }
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
                    text = mediaTitle,
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
        // 编辑模式顶部栏
        if (isEditMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = selectedIds.size == filteredList.size && filteredList.isNotEmpty(),
                        onCheckedChange = { checked ->
                            if (checked) filteredList.forEach { selectedIds.add(it.id) }
                            else selectedIds.clear()
                        }
                    )
                    Text(
                        text = if (selectedIds.isEmpty()) "全选" else "已选 ${selectedIds.size} 项",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row {
                    TextButton(onClick = {
                        viewModel.deleteVideos(selectedIds.toList())
                        selectedIds.clear()
                        isEditMode = false
                    }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = {
                        isEditMode = false
                        selectedIds.clear()
                    }) {
                        Text("取消")
                    }
                }
            }
        }
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            placeholder = {
                Text("搜索收藏夹内视频", color = MaterialTheme.colorScheme.outline)
            },
            leadingIcon = {
                Icon(Icons.Default.Search, "搜索", tint = MaterialTheme.colorScheme.outline)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { }),
            shape = RoundedCornerShape(8.dp),
        )
        SwipeToRefresh(
            modifier = Modifier.weight(1f),
            refreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp)
            ) {
                items(filteredList, key = { it.id }) { item ->
                    val vid = item.id
                    Box {
                        VideoItemBox(
                            modifier = if (isEditMode) Modifier
                                .padding(10.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (vid in selectedIds) selectedIds.remove(vid)
                                        else selectedIds.add(vid)
                                    },
                                    onLongClick = {
                                        if (vid in selectedIds) selectedIds.remove(vid)
                                        else selectedIds.add(vid)
                                    }
                                ) else Modifier.padding(10.dp),
                            title = item.title,
                            pic = item.cover,
                            upperName = item.upper.name,
                            playNum = item.cnt_info.play,
                            damukuNum = item.cnt_info.danmaku,
                            duration = NumberUtil.converDuration(item.duration),
                            onClick = if (isEditMode) null else {
                                { viewModel.openVideo(item) }
                            }
                        )
                        if (isEditMode) {
                            Checkbox(
                                checked = vid in selectedIds,
                                onCheckedChange = {
                                    selectedIds.remove(vid)
                                },
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                            )
                        }
                    }
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

    val info = detailInfo
    if (showEditDialog && info != null) {
        val formState = remember {
            FavouriteEditFormState(
                initialTitle = info.title,
                initialIntro = info.intro,
                initialPrivacy = info.privacy,
            )
        }
        var loading by remember {
            mutableStateOf(false)
        }
        val handleSubmit = remember(info) {
            {
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        loading = true
                        viewModel.editFolder(
                            cover = info.cover,
                            title = formState.title,
                            intro = formState.intro,
                            privacy = formState.privacy,
                        )
                    }.onSuccess {
                        loading = false
                        toast("修改成功")
                        showEditDialog = false
                        onRefresh()
                    }.onFailure {
                        loading = false
                        toast(it.message ?: it.toString())
                    }
                }
                Unit
            }
        }

        AlertDialog(
            onDismissRequest = {
                showEditDialog = false
            },
            title = {
                Text(
                    text = "编辑收藏夹",
                    fontWeight = FontWeight.W700,
                    style = MaterialTheme.typography.titleSmall
                )
            },
            text = {
                FavouriteEditForm(formState)
            },
            confirmButton = {
                TextButton(
                    enabled = !loading,
                    onClick = handleSubmit,
                ) {
                    Text(text = "修改")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showEditDialog = false
                    },
                ) {
                    Text(text = "取消")
                }
            }
        )
    } else if (showDeleteDialog) {
        var loading by remember {
            mutableStateOf(false)
        }

        fun handleDelete() = scope.launch(Dispatchers.IO) {
            runCatching {
                loading = true
                viewModel.deleteFolder()
                onRefresh()
                onClose()
            }.onSuccess {
                loading = false
                toast("修改成功")
                showEditDialog = false
            }.onFailure {
                loading = false
                toast(it.message ?: it.toString())
            }
        }
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
            },
            title = {
                Text(
                    text = "提示",
                    fontWeight = FontWeight.W700,
                    style = MaterialTheme.typography.titleSmall
                )
            },
            text = {
                Text(text = "确定删除收藏夹：${mediaTitle}?")
            },
            confirmButton = {
                TextButton(
                    enabled = !loading,
                    onClick = ::handleDelete,
                ) {
                    Text(text = "删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                    },
                ) {
                    Text(text = "取消")
                }
            }
        )
    }

    VideoDownloadDialog(state = viewModel.downloadDialogState)

}