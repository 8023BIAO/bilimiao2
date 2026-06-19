package cn.a10miaomiao.bilimiao.compose.pages.bangumi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import cn.a10miaomiao.bilimiao.compose.common.foundation.LocalOnSeekTime
import cn.a10miaomiao.bilimiao.compose.common.foundation.pagerTabIndicatorOffset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.dialogs.AutoSheetDialog
import cn.a10miaomiao.bilimiao.compose.components.layout.DoubleColumnAutofitLayout
import cn.a10miaomiao.bilimiao.compose.components.layout.chain_scrollable.rememberChainScrollableLayoutState
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.components.status.BiliLoadingBox
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.components.BangumiEpisodeItem
import cn.a10miaomiao.bilimiao.compose.base.BottomSheetState
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.pages.community.MainReplyListPage
import cn.a10miaomiao.bilimiao.compose.pages.community.MainReplyViewModel
import cn.a10miaomiao.bilimiao.compose.pages.community.content.ReplyDetailContent
import cn.a10miaomiao.bilimiao.compose.pages.community.content.ReplyListContent
import cn.a10miaomiao.bilimiao.compose.pages.download.DownloadBangumiCreatePage
import cn.a10miaomiao.bilimiao.compose.pages.download.DownloadBangumiCreatePageContent
import cn.a10miaomiao.bilimiao.compose.pages.download.DownloadBangumiCreatePageViewModel
import cn.a10miaomiao.bilimiao.compose.pages.video.VideoDetailPage

import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.delegate.player.BangumiPlayerSource
import com.a10miaomiao.bilimiao.comm.delegate.player.BasePlayerDelegate
import com.a10miaomiao.bilimiao.comm.delegate.player.PlayerSeekBus
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.ResponseResult
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo2
import com.a10miaomiao.bilimiao.comm.entity.bangumi.*
import com.a10miaomiao.bilimiao.comm.entity.comm.ToastInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.mypage.myMenu
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.PlayerStore
import com.a10miaomiao.bilimiao.comm.store.PlayListStore
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.entity.player.PlayListFrom
import com.a10miaomiao.bilimiao.comm.entity.player.PlayListItemInfo
import com.a10miaomiao.bilimiao.comm.utils.BiliUrlMatcher
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.localDI
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
data class BangumiDetailPage(
    // 三选其一
    private val id: String = "",
    private val epId: String = "",
    private val mediaId: String = "",
) : ComposePage() {


    @Composable
    override fun Content() {
        val viewModel: BangumiDetailPageViewModel = diViewModel()
        BangumiDetailPageContent(
            id = id,
            epid = epId,
            mediaId = mediaId,
            viewModel = viewModel,
        )
    }

}

/**
 * 轻量 PV 检查页面：在创建 BangumiDetailPage 或跳转 VideoDetailPage 前检查是否为 PV
 * 避免重型 BangumiDetailPage 在 PV 场景下被频繁创建/销毁导致卡死
 */
@Serializable
data class SeasonCheckPage(
    private val id: String = "",
    private val epId: String = "",
    private val mediaId: String = "",
) : ComposePage() {

    @Composable
    override fun Content() {
        val pageNavigation: PageNavigation by rememberInstance()

        LaunchedEffect(Unit) {
            val savedRoute = pageNavigation.hostController.currentDestination?.route
            val target = withContext(Dispatchers.IO) {
                detectPvSeason(id, epId, mediaId)
            }
            if (!isActive) return@LaunchedEffect
            // 验证用户没有在 API 调用期间按返回退出
            val currentRoute = pageNavigation.hostController.currentDestination?.route
            if (savedRoute == null || currentRoute != savedRoute) return@LaunchedEffect
            val destPage = if (target != null) {
                VideoDetailPage(id = target)
            } else {
                BangumiDetailPage(id = id, epId = epId, mediaId = mediaId)
            }
            if (currentRoute != null) {
                pageNavigation.navigate(destPage) {
                    popUpTo(currentRoute) { inclusive = true }
                }
            } else {
                pageNavigation.navigate(destPage)
            }
        }

        // 始终铺主题背景，API完成后由目标页替换，无中间帧闪烁
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

/**
 * PV/伪番剧检测
 * 调 seasonSection 查 section type：全部 type=2 则为 PV，走 fallback 拿 bvid 跳转
 */
private suspend fun detectPvSeason(
    sid: String,
    eid: String,
    mid: String,
): String? {
    var seasonId = sid
    if (seasonId.isBlank() && mid.isNotBlank()) {
        seasonId = resolveMediaId(mid) ?: ""
    }
    if (seasonId.isNotBlank()) {
        try {
            val sectionRes = BiliApiService.bangumiAPI.seasonSection(seasonId)
                .awaitCall()
                .json<ResponseResult<SeasonSectionInfo>>()
            if (sectionRes.isSuccess && sectionRes.result != null) {
                val sectionData = sectionRes.result!!
                val sections = mutableListOf<SeasonSectionInfo.SectionInfo>()
                sectionData.main_section?.let { sections.add(it) }
                sectionData.section?.let { sections.addAll(it) }
                if (sections.isNotEmpty() && sections.all { it.type == 2 }) {
                    return getBvidFromFallback(seasonId, eid)
                }
                return null
            }
            // sectionSection 成功但 result=null（PV season 常见情况）
            return getBvidFromFallback(seasonId, eid)
        } catch (_: Exception) {
            // seasonSection 反序列化失败（PV season main_section={} 导致）
            return getBvidFromFallback(seasonId, eid)
        }
    }
    return getBvidFromFallback(seasonId, eid)
}

private suspend fun getBvidFromFallback(
    seasonId: String,
    epId: String,
): String? {
    return try {
        val fallbackRes = MiaoHttp.request {
            url = BiliApiService.biliApi("pgc/view/web/season",
                "season_id" to seasonId.ifBlank { null },
                "ep_id" to epId.ifBlank { null },
            )
        }.awaitCall().json<ResponseResult<JsonElement>>()
        if (fallbackRes.isSuccess && fallbackRes.result != null) {
            val data = fallbackRes.result!!.jsonObject
            // 先查顶层 episodes
            val episodes = data["episodes"]
            if (episodes is JsonArray && episodes.isNotEmpty()) {
                val firstEp = episodes[0].jsonObject
                firstEp["bvid"]?.jsonPrimitive?.content
                    ?: firstEp["aid"]?.jsonPrimitive?.content
            } else {
                // 再查 section（PV 的 episodes 可能在这里）
                val sections = data["section"]
                if (sections is JsonArray && sections.isNotEmpty()) {
                    for (section in sections) {
                        val secEps = section.jsonObject["episodes"]
                        if (secEps is JsonArray && secEps.isNotEmpty()) {
                            val firstEp = secEps[0].jsonObject
                            val bvid = firstEp["bvid"]?.jsonPrimitive?.content
                            if (!bvid.isNullOrBlank()) return bvid
                            val aid = firstEp["aid"]?.jsonPrimitive?.content
                            if (!aid.isNullOrBlank()) return aid
                        }
                    }
                }
                null
            }
        } else null
    } catch (_: Exception) {
        null
    }
}

private suspend fun resolveMediaId(mediaId: String): String? {
    return try {
        val res = MiaoHttp.request {
            url = "https://api.bilibili.com/pgc/review/user?media_id=${mediaId}"
        }.awaitCall().json<ResponseResult<Map<String, JsonElement>>>()
        if (res.isSuccess) {
            res.result?.get("media")?.jsonObject?.get("season_id")?.jsonPrimitive?.content
        } else null
    } catch (_: Exception) {
        null
    }
}

private class BangumiDetailPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val fragment by instance<Fragment>()
    private val activity = fragment.requireActivity() as androidx.appcompat.app.AppCompatActivity
    private val pageNavigation by instance<PageNavigation>()
    private val basePlayerDelegate by instance<BasePlayerDelegate>()
    private val bottomSheetState by instance<BottomSheetState>()
    private val userStore by instance<UserStore>()
    private val playListStore by instance<PlayListStore>()

    var seasonId = ""
        set(value) {
            if (field != value) {
                field = value
                if (field.isNotBlank() && field != detailInfo.value?.season_id) {
                    loadData()
                    loadEpisodeList(field)
                }
            }
        }
    var epId = ""
        set(value) {
            if (field != value) {
                field = value
                if (field.isNotBlank() && seasonId.isBlank()) {
                    loadData()
                }
            }
        }

    var loading = MutableStateFlow(false)
    val detailInfo = MutableStateFlow<SeasonV2Info?>(null)
    val isFollow = MutableStateFlow(false)
    var seasons = MutableStateFlow<List<SeasonInfo>>(emptyList())

    var sectionLoading = MutableStateFlow(false)
    var sectionList = MutableStateFlow<List<SeasonSectionInfo.SectionInfo>>(emptyList())
    val sectionId = MutableStateFlow("")
    val isRefreshing = MutableStateFlow(false)

    // 下载弹窗
    val showDownload = MutableStateFlow(false)
    private val _downloadSeasonId = MutableStateFlow("")
    val downloadSeasonId: StateFlow<String> get() = _downloadSeasonId
//    val isFollow get() = detailInfo.value?.user_status?.follow == 1


    fun loadData() = viewModelScope.launch(Dispatchers.IO) {
        try {
            loading.value = true
            detailInfo.value = null

            val res = BiliApiService.bangumiAPI.seasonInfoV2(
                seasonId, epId
            ).awaitCall()
                .json<ResponseData<SeasonV2Info>>()
            if (res.code == 0) {
                val result = res.requireData()
                detailInfo.value = result
                val seasonModule = result.modules.find {
                    it.style == "season"
                }
                seasons.value = seasonModule?.data?.seasons ?: emptyList()
                isFollow.value = detailInfo.value?.user_status?.follow == 1
                if (seasonId != result.season_id) {
                    seasonId = result.season_id
                    loadEpisodeList(result.season_id)
                }
            } else {
                withContext(Dispatchers.Main) {
                    toast(res.message)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                toast("网络请求失败")
            }
        } finally {
            loading.value = false
        }
    }

    /**
     * 剧集信息
     */
    fun loadEpisodeList(
        id: String,
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            sectionLoading.value = true
            sectionList.value = emptyList()
            sectionId.value = ""

            val res = BiliApiService.bangumiAPI.seasonSection(id)
                .awaitCall()
                .json<ResponseResult<SeasonSectionInfo>>()
            if (res.isSuccess) {
                val result = res.result ?: return@launch
                val list = mutableListOf<SeasonSectionInfo.SectionInfo>()
                result.main_section?.let(list::add)
                result.section?.let(list::addAll)
                sectionList.value = list.toList()
                list.firstOrNull()?.let {
                    sectionId.value = it.id
                }
                // PV检测：全部 section type=2 表示是伪番剧/PV，跳转视频详情页
                if (list.isNotEmpty() && list.all { it.type == 2 }) {
                    val aid = list.first().episodes.firstOrNull()?.aid
                    if (!aid.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            val currentRoute = pageNavigation.hostController.currentDestination?.route
                            if (currentRoute != null) {
                                pageNavigation.navigate(VideoDetailPage(id = aid)) {
                                    popUpTo(currentRoute) { inclusive = true }
                                }
                            } else {
                                pageNavigation.navigate(VideoDetailPage(id = aid))
                            }
                        }
                        return@launch
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    toast(res.message)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                toast("网络请求失败")
            }
        } finally {
            sectionLoading.value = false
            isRefreshing.value = false
        }
    }

    fun followSeason() = viewModelScope.launch(Dispatchers.IO) {
        if (!userStore.isLogin()) {
            toast("请先登录")
            return@launch
        }
        val detail = detailInfo.value ?: return@launch
        try {
            val mode = if (isFollow.value) {
                2
            } else {
                1
            }
            val res = (if (mode == 2) {
                BiliApiService.bangumiAPI.cancelFollow(detail.season_id)
            } else {
                BiliApiService.bangumiAPI.followSeason(detail.season_id)
            }).awaitCall().json<ResponseResult<ToastInfo>>()
            if (res.isSuccess) {
                isFollow.value = mode == 1
                withContext(Dispatchers.Main) {
                    toast(
                        if (mode == 1) {
                            res.result?.toast ?: "追番成功"
                        } else {
                            res.result?.toast ?: "已取消追番"
                        }
                    )
                }
            } else {
                withContext(Dispatchers.Main) {
                    toast(res.message)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                toast("网络错误")
            }
            e.printStackTrace()
        }
    }

    fun refresh() {
        if (seasonId.isNotBlank()) {
            isRefreshing.value = true
            loadData()
            loadEpisodeList(seasonId)
        }
    }

    fun changeSection(item: SeasonSectionInfo.SectionInfo) {
        sectionId.value = item.id
    }

    fun onSeasonClick(season: SeasonInfo) = viewModelScope.launch {
        val target = withContext(Dispatchers.IO) {
            detectPvSeason(season.season_id, "", "")
        }
        if (target != null) {
            withContext(Dispatchers.Main) {
                val currentRoute = pageNavigation.hostController.currentDestination?.route
                if (currentRoute != null) {
                    pageNavigation.navigate(VideoDetailPage(id = target)) {
                        popUpTo(currentRoute) { inclusive = true }
                    }
                } else {
                    pageNavigation.navigate(VideoDetailPage(id = target))
                }
            }
        } else {
            seasonId = season.season_id
        }
    }

    fun toCommentListPage(item: EpisodeInfo) {
        pageNavigation.navigate(MainReplyListPage(
            oid = item.aid,
            type = 1,
        ))
    }

    fun shareEpisode(item: EpisodeInfo) {
        val title = item.title + if (item.long_title.isBlank()) "" else "_" + item.long_title
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "bilibili番剧分享")
            putExtra(Intent.EXTRA_TEXT, "$title https://www.bilibili.com/bangumi/play/ep${item.id}")
        }
        fragment.requireActivity().startActivity(Intent.createChooser(shareIntent, "分享"))
    }

    fun startPlayPv(aid: String) {
        pageNavigation.navigate(VideoDetailPage(id = aid))
    }

    fun startPlayBangumi(episodes: List<EpisodeInfo>, item: EpisodeInfo, seekOverride: Long? = null) {
        val seasonDetail = detailInfo.value ?: return
        val playerSource = BangumiPlayerSource(
            sid = seasonDetail.season_id,
            epid = item.id,
            aid = item.aid,
            id = item.cid,
            title = item.long_title.ifBlank { item.title },
            coverUrl = item.cover,
            ownerId = "",
            ownerName = seasonDetail.season_title
        )

        playerSource.episodes = episodes.map {
            BangumiPlayerSource.EpisodeInfo(
                epid = it.id, aid = it.aid, cid = it.cid,
                cover = it.cover,
                index = it.title,
                index_title = it.long_title,
                badge = it.badge,
                badge_info = BangumiPlayerSource.EpisodeBadgeInfo(
                    text = it.badge_info.text,
                    bg_color = it.badge_info.bg_color,
                    bg_color_night = it.badge_info.bg_color_night,
                ),
            )
        }
        playerSource.defaultPlayerSource.run {
            val progress = detailInfo.value?.user_status?.progress
            if (progress != null && item.id == progress.last_ep_id ) {
                lastPlayCid = item.cid
                lastPlayTime = progress.last_time * 1000L
            }
            // 评论空降跳转覆盖，优先级最高
            if (seekOverride != null) {
                lastPlayTime = seekOverride
            }
            val dimension = item.dimension
            if (dimension != null) {
                width = dimension.width
                height = dimension.height
            }
        }
        basePlayerDelegate.openPlayer(playerSource)

        // 设置播单以支持通知栏播放模式按钮（随机/单曲/列表/正常）
        val listFrom = PlayListFrom.Section(
            seasonId = seasonDetail.season_id,
            sectionId = sectionId.value,
        )
        if (playListStore.state.from !is PlayListFrom.Section
            || (playListStore.state.from as PlayListFrom.Section).seasonId != listFrom.seasonId
            || (playListStore.state.from as PlayListFrom.Section).sectionId != listFrom.sectionId
        ) {
            playListStore.setPlayList(
                name = seasonDetail.season_title,
                from = listFrom,
                items = episodes.map { ep ->
                    PlayListItemInfo(
                        aid = ep.aid,
                        cid = ep.cid,
                        duration = 0,
                        title = ep.long_title.ifBlank { ep.title },
                        cover = ep.cover,
                        ownerId = "",
                        ownerName = seasonDetail.season_title,
                        from = listFrom,
                        sid = seasonDetail.season_id,
                        epid = ep.id,
                    )
                },
            )
        }
        // seekOnStart 通过 lastPlayCid/lastPlayTime 已可靠处理空降跳转
        // （getPlayerUrl 不再覆盖预设的进度值）
    }

    /**
     * 从评论区空降跳转到指定位置（无视频播放时调用）
     */
    fun playBangumiAtPosition(
        episodes: List<EpisodeInfo>,
        episode: EpisodeInfo,
        seekMs: Long,
    ) {
        startPlayBangumi(episodes, episode, seekOverride = seekMs)
    }

    fun isPlayerActive(): Boolean {
        return basePlayerDelegate.isPlaying() || basePlayerDelegate.isPause()
    }

    fun menuItemClick(view: View, menuItem: MenuItemPropInfo) {
        when (menuItem.key) {
            1 -> {
                // 用浏览器打开
                val info = detailInfo.value
                if (info != null) {
                    val id = info.season_id
                    var url = "https://www.bilibili.com/bangumi/play/ss$id"
                    BiliUrlMatcher.toUrlLink(fragment.requireContext(), url)
                } else {
                    toast("请等待信息加载完成")
                }
            }
            2 -> {
                // 分享番剧
                val info = detailInfo.value
                if (info != null) {
                    val activity = fragment.requireActivity()
                    var shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "bilibili番剧分享")
                        putExtra(Intent.EXTRA_TEXT, "${info.season_title} https://www.bilibili.com/bangumi/play/ss${info.season_id}")
                    }
                    activity.startActivity(Intent.createChooser(shareIntent, "分享"))
                } else {
                    toast("请等待信息加载完成")
                }

            }
            3 -> {
                // 复制链接
                val info = detailInfo.value
                if (info != null) {
                    val activity = fragment.requireActivity()
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    var label = "url"
                    var text = "https://www.bilibili.com/bangumi/play/ss${info.season_id}"
                    val clip = ClipData.newPlainText(label, text)
                    clipboard.setPrimaryClip(clip)
                    toast("已复制：$text")
                } else {
                    toast("请等待信息加载完成")
                }
            }
            MenuKeys.download -> {
                // 下载番剧
                val info = detailInfo.value
                if (info != null) {
                    _downloadSeasonId.value = info.season_id
                    showDownload.value = true
                } else {
                    toast("请等待信息加载完成")
                }
            }

            MenuKeys.follow -> {
                // 追番
                followSeason()
            }
        }
    }

    fun onBackPressed() {
        viewModelScope.launch(Dispatchers.Main) {
            val sourceIds = basePlayerDelegate.getSourceIds()
            val currentSeasonId = seasonId
            val currentEpId = epId
            if (basePlayerDelegate.isOpened()
                && (sourceIds.sid == currentSeasonId || sourceIds.epid == currentEpId)) {
                val openMode = SettingPreferences.mapData(activity) {
                    it[SettingPreferences.PlayerOpenMode] ?: SettingConstants.PLAYER_OPEN_MODE_DEFAULT
                }
                if (openMode and SettingConstants.PLAYER_OPEN_MODE_AUTO_CLOSE != 0) {
                    basePlayerDelegate.closePlayer()
                }
            }
            runCatching {
                pageNavigation.popBackStack()
            }
        }
    }

    fun findSectionEpisodeIndex(id: String): Pair<SeasonSectionInfo.SectionInfo?, Int> {
        val sections = sectionList.value
        var index: Int = -1
        return sections.find {
            index = it.episodes.indexOfFirst { ep ->
                ep.id == id
            }
            index != -1
        } to index
    }
}


@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun BangumiDetailPageContent(
    id: String,
    epid: String,
    mediaId: String,
    viewModel: BangumiDetailPageViewModel,
) {
    val playerStore: PlayerStore by rememberInstance()
    val windowStore: WindowStore by rememberInstance()
    val playerState = playerStore.stateFlow.collectAsState().value
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val detailInfo = viewModel.detailInfo.collectAsState().value
    val isFollow = viewModel.isFollow.collectAsState().value
    val seasons = viewModel.seasons.collectAsState().value
    val loading = viewModel.loading.collectAsState().value

    val sectionList = viewModel.sectionList.collectAsState().value
    val sectionId = viewModel.sectionId.collectAsState().value
    val sectionLoading = viewModel.sectionLoading.collectAsState().value
    val episodes = remember(sectionId, sectionList) {
        sectionList.find {
            it.id == sectionId
        }?.episodes ?: emptyList()
    }

    val scope = rememberCoroutineScope()
    BackHandler(onBack = viewModel::onBackPressed)
    val chainScrollableLayoutState = rememberChainScrollableLayoutState(
        maxScrollPosition = 0.dp,
    )
    val seasonsListState = rememberLazyListState()
    val episodesListState = rememberLazyListState()
    val replyListState = rememberLazyListState()

    // 跟踪当前选中剧集的 aid，用于评论区
    var selectedEpisodeAid by rememberSaveable { mutableStateOf("") }
    val replyOid = remember(selectedEpisodeAid, episodes) {
        if (selectedEpisodeAid.isNotBlank()) selectedEpisodeAid
        else episodes.firstOrNull()?.aid?.toString() ?: ""
    }
    val di = localDI()
    val replyViewModel = remember(replyOid) {
        if (replyOid.isNotBlank()) {
            MainReplyViewModel(di, type = 1, oid = replyOid)
        } else null
    }

    var seasonId = rememberSaveable() {
        mutableStateOf(id)
    }
    var seasonEpId = rememberSaveable() {
        mutableStateOf(epid)
    }

    LaunchedEffect(seasonId.value) {
        viewModel.seasonId = seasonId.value
    }
    LaunchedEffect(seasonEpId.value) {
        viewModel.epId = seasonEpId.value
    }
    LaunchedEffect(detailInfo) {
        detailInfo?.let {
            if (it.season_id != seasonId.value) {
                seasonId.value = it.season_id
            }
        }
    }
    LaunchedEffect(seasons, seasonId.value) {
        val index = seasons.indexOfFirst {
            it.season_id == seasonId.value
        }
        if (index > 0) {
            seasonsListState.scrollToItem(index)
        }
    }

    // 从 epid 参数初始化 selectedEpisodeAid（评论区跟随选中剧集）
    LaunchedEffect(epid, episodes) {
        if (selectedEpisodeAid.isBlank() && epid.isNotBlank() && episodes.isNotEmpty()) {
            val matched = episodes.find { it.id == epid }
            if (matched != null) {
                selectedEpisodeAid = matched.aid.toString()
            }
        }
    }

    LaunchedEffect(mediaId) {
        // 先通过mediaId拿到seasonId
        if (mediaId.isNotBlank() && seasonId.value.isBlank()) {
            try {
                val res = withContext(Dispatchers.IO) {
                    MiaoHttp
                        .request {
                            url = "https://api.bilibili.com/pgc/review/user?media_id=${mediaId}"
                        }
                        .awaitCall()
                        .json<ResponseResult<Map<String, JsonElement>>>()
                }
                if (res.isSuccess) {
                    val resultData = res.requireData()
                    if (resultData.containsKey("media")) {
                        val media = resultData["media"]!!
                        val jsonObject = media.jsonObject
                        if (jsonObject.containsKey("season_id")) {
                            seasonId.value = jsonObject["season_id"]!!.jsonPrimitive.content
                        }
                    }
                } else {
                    toast(res.message)
                }
            } catch (e: Exception) {
                toast("网络错误")
            }
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    val grayIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f).toArgb()

    // 跟踪是否在评论 tab，用于切换 AppBar 菜单
    var isReplyTabActive by remember { mutableStateOf(false) }

    // 详情 tab 的 PageConfig（更多/追番）
    if (!isReplyTabActive) {
        val pageConfigId = PageConfig(
            title = detailInfo?.season_title ?: "番剧详情",
            menu = remember(isFollow) {
                myMenu {
                    myItem {
                        key = MenuKeys.more
                        iconFileName = "ic_more_vert_grey_24dp"
                        title = "更多"

                        childMenu = myMenu {
                            myItem {
                                key = 1
                                title = "用浏览器打开"
                            }
                            myItem {
                                key = 2
                                title = if (detailInfo != null) {
                                    "分享番剧(${detailInfo.stat.share})"
                                } else {
                                    "分享番剧"
                                }
                            }
                            myItem {
                                key = 3
                                title = "复制链接"
                            }
                        }
                    }
                    myItem {
                        key = MenuKeys.download
                        iconFileName = "ic_download_grey_24dp"
                        title = "下载"
                    }
                    myItem {
                        key = MenuKeys.follow
                        if (isFollow) {
                            iconFileName = "ic_baseline_favorite_24"
                            title = "已追番"
                            tintColor = primaryColor
                        } else {
                            iconFileName = "ic_outline_favorite_border_24"
                            title = "追番"
                        }
                    }
                }
            }
        )
        PageListener(
            pageConfigId,
            onMenuItemClick = viewModel::menuItemClick
        )
    }

    val isRefreshing by viewModel.isRefreshing.collectAsState()

    SwipeToRefresh(
        modifier = Modifier
            .fillMaxSize(),
        refreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
    ) {
        DoubleColumnAutofitLayout(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            innerPadding = windowInsets.toPaddingValues(),
            chainScrollableLayoutState = chainScrollableLayoutState,
            leftMaxWidth = 9999.dp,
            leftMaxHeight = 0.dp,
            leftContent = { _, innerPadding ->
                if (loading) {
                    BiliLoadingBox(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }
            }
        ) { _, innerPadding ->
            val scope = rememberCoroutineScope()
    BackHandler(onBack = viewModel::onBackPressed)
            val replyCount by (replyViewModel?.replyCount ?: MutableStateFlow(0L)).collectAsState()
            val replyTabText = if (replyCount > 0) "评论($replyCount)" else "评论"
            val tabs = remember(replyTabText) { listOf("detail" to "详情", "reply" to replyTabText) }
            val pagerState = rememberPagerState(pageCount = { tabs.size })
            LaunchedEffect(pagerState.currentPage) {
                isReplyTabActive = tabs[pagerState.currentPage].first == "reply"
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = windowInsets.topDp.dp)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                TabRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                            end = innerPadding.calculateEndPadding(LayoutDirection.Ltr),
                        )
                        .background(MaterialTheme.colorScheme.surface)
                        .nestedScroll(chainScrollableLayoutState.nestedScroll),
                    selectedTabIndex = pagerState.currentPage,
                    indicator = { positions ->
                        TabRowDefaults.PrimaryIndicator(
                            Modifier.pagerTabIndicatorOffset(pagerState, positions),
                        )
                    },
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val selected = tabs[pagerState.currentPage].first == tab.first
                        Tab(
                            modifier = Modifier.height(38.dp),
                            text = {
                                Text(
                                    tab.second,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onBackground
                                    }
                                )
                            },
                            selected = selected,
                            onClick = {
                                scope.launch { pagerState.scrollToPage(index) }
                            },
                        )
                    }
                }
                // 返回导航：在"评论"tab时返回切换到"详情"tab
                BackHandler(
                    enabled = pagerState.currentPage > 0
                ) {
                    scope.launch { pagerState.animateScrollToPage(0) }
                }
                HorizontalPager(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    state = pagerState,
                    key = { index -> tabs[index].first },
                ) { index ->
                    val tab = tabs[index]
                    when (tab.first) {
                        "detail" -> {
                            LazyColumn(
                                state = episodesListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                                    end = innerPadding.calculateEndPadding(LayoutDirection.Ltr),
                                    top = 0.dp,
                                    bottom = innerPadding.calculateBottomPadding(),
                                ),
                            ) {
                                item("evaluate") {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(8.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                        ) {
                                            if (seasons.isNotEmpty()) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = "选择系列：",
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                    )
                                                    LazyRow(
                                                        state = seasonsListState,
                                                        modifier = Modifier.weight(1f),
                                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                                    ) {
                                                        items(seasons, { it.season_id }) {
                                                            FilterChip(
                                                                selected = it.season_id == seasonId.value,
                                                                onClick = {
                                                                    viewModel.onSeasonClick(it)
                                                                },
                                                                label = {
                                                                    Text(text = it.season_title)
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(5.dp))
                                            }
                                            Text(
                                                text = detailInfo?.evaluate ?: "",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                }

                                if (sectionList.size > 1) {
                                    item("sectionList") {
                                        LazyRow(
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            items(sectionList, { it.id }) {
                                                FilterChip(
                                                    selected = it.id == sectionId,
                                                    onClick = {
                                                        viewModel.changeSection(it)
                                                    },
                                                    label = {
                                                        Text(text = it.title)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                val userProgress = detailInfo?.user_status?.progress
                                items(episodes, { it.id }) { item ->
                                    BangumiEpisodeItem(
                                        modifier = Modifier.padding(
                                            horizontal = 8.dp,
                                            vertical = 4.dp,
                                        ),
                                        item = item,
                                        desc = if (item.id == userProgress?.last_ep_id) {
                                            val time = NumberUtil.converDuration(userProgress.last_time)
                                            "上次看到 $time"
                                        } else null,
                                        playerState = playerState,
                                        onClick = {
                                            val currentType = sectionList.find { it.id == sectionId }?.type
                                            if (currentType == 2) {
                                                viewModel.startPlayPv(item.aid)
                                            } else {
                                                selectedEpisodeAid = item.aid.toString()
                                                viewModel.startPlayBangumi(episodes, item)
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        "reply" -> {
                            // 评论区空降跳转：检查当前评论所属剧集是否匹配正在播放的视频
                            val seekCallback: ((Int) -> Unit) = { seconds ->
                                val currentAid = playerState.aid
                                val pageAid = replyOid
                                when {
                                    currentAid.isNotBlank() && currentAid == pageAid -> {
                                        // 当前播放的就是本评论区的剧集，且播放器处于活跃状态 → 跳转
                                        if (viewModel.isPlayerActive()) {
                                            PlayerSeekBus.onSeek?.invoke(seconds * 1000L)
                                        } else {
                                            // 播放器已关闭或播放完成 → 重新打开并跳转
                                            val episode = episodes.find { it.aid == pageAid }
                                            if (episode != null) {
                                                viewModel.playBangumiAtPosition(episodes, episode, seconds * 1000L)
                                            }
                                        }
                                    }
                                    currentAid.isBlank() -> {
                                        // 没有任何视频在播放 → 打开播放器并跳转
                                        val episode = episodes.find { it.aid == pageAid }
                                        if (episode != null) {
                                            viewModel.playBangumiAtPosition(episodes, episode, seconds * 1000L)
                                        }
                                    }
                                    // else: 播放的是别的视频 → 什么都不做
                                }
                            }
                            CompositionLocalProvider(LocalOnSeekTime provides seekCallback) {
                                replyViewModel?.let { vm ->
                                val currentReply by vm.currentReply.collectAsState()
                                BackHandler(enabled = currentReply != null) {
                                    vm.clearCurrentReply()
                                }
                                Box(modifier = Modifier.fillMaxSize()) {
                                    if (currentReply != null) {
                                        ReplyDetailContent(
                                            reply = currentReply!!,
                                            innerPadding = PaddingValues(
                                                bottom = innerPadding.calculateBottomPadding(),
                                                start = innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                                                end = innerPadding.calculateEndPadding(LayoutDirection.Ltr),
                                            ),
                                            usePageConfig = true,
                                            onCloseClick = { vm.clearCurrentReply() },
                                            onLikeReply = vm::likeReply,
                                            onDeletedReply = vm::removeReplyItem,
                                        )
                                    } else {
                                        ReplyListContent(
                                            viewModel = vm,
                                            innerPadding = PaddingValues(
                                                bottom = innerPadding.calculateBottomPadding(),
                                                start = innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                                                end = innerPadding.calculateEndPadding(LayoutDirection.Ltr),
                                            ),
                                            listState = replyListState,
                                            usePageConfig = true,
                                            pageTitle = "评论",
                                        )
                                    }
                                }
                            } ?: Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("暂无评论", color = MaterialTheme.colorScheme.outline)
                            }
                            } // CompositionLocalProvider
                        }
                    }
                }
            }
        }

        detailInfo?.user_status?.progress?.let {
            if (playerState.sid == detailInfo.season_id) {
                return@let
            }
            val lastEpIndex = it.last_ep_index.ifBlank {
                episodes.firstOrNull { episode ->
                    it.last_ep_id == episode.id
                }?.index ?: return@let
            }
            FloatingActionButton(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .padding(
                        bottom = windowStore.bottomAppBarHeightDp.dp
                                + windowInsets.bottomDp.dp
                    ),
                onClick = {
                    scope.launch {
                        // 找到匹配的剧集并打开播放器
                        val matchedEpisode = sectionList.flatMap { it.episodes }
                            .firstOrNull { ep -> ep.id == it.last_ep_id }
                        if (matchedEpisode != null) {
                            // 同步评论区到当前剧集
                            selectedEpisodeAid = matchedEpisode.aid.toString()
                            // 找到 matchedEpisode 所属的 section，用它的 episodes 列表
                            val targetEpisodes = sectionList
                                .firstOrNull { s -> s.episodes.any { ep -> ep.id == matchedEpisode.id } }
                                ?.episodes ?: episodes
                            viewModel.startPlayBangumi(targetEpisodes, matchedEpisode)
                        } else {
                            // 找不到剧集时回退到滚动定位
                            chainScrollableLayoutState.scrollToMax()
                            val (section, index) = viewModel.findSectionEpisodeIndex(
                                it.last_ep_id
                            )
                            if (section != null && index != -1) {
                                val offset = if (sectionList.size > 1) {
                                    2
                                } else {
                                    1
                                }
                                viewModel.changeSection(section)
                                episodesListState.scrollToItem(
                                    index = index + offset,
                                    scrollOffset = -windowInsets.top
                                )
                            }
                        }
                    }
                }
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(text = "上次看到")
                    Text(
                        text = "${if (NumberUtil.isNumber(lastEpIndex)) {
                            "第${lastEpIndex}话"
                        } else {
                            lastEpIndex
                        }} ${NumberUtil.converDuration(it.last_time)}"
                    )

                }
                }
            }

        // 下载弹窗（直接渲染在页面内，主题切换不会丢）
        val showDownload by viewModel.showDownload.collectAsState()
        if (showDownload) {
            val dlSeasonId = viewModel.downloadSeasonId.collectAsState().value
            val dlViewModel: DownloadBangumiCreatePageViewModel = diViewModel()
            LaunchedEffect(dlSeasonId) {
                dlViewModel.loadEpisodeList(dlSeasonId)
            }
            AutoSheetDialog(
                onDismiss = { viewModel.showDownload.value = false },
            ) {
                DownloadBangumiCreatePageContent(dlViewModel)
            }
        }

    }
}
