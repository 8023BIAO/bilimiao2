package cn.a10miaomiao.bilimiao.compose.pages.video

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.os.Build
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bilibili.app.archive.v1.Page
import bilibili.app.view.v1.ViewGRPC
import bilibili.app.view.v1.ViewReply
import bilibili.app.view.v1.ViewReq
import cn.a10miaomiao.bilimiao.compose.BilimiaoPageRoute
import cn.a10miaomiao.bilimiao.compose.base.BottomSheetState
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.pages.playlist.PlayListPage
import cn.a10miaomiao.bilimiao.compose.pages.search.SearchResultPage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSeasonDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import cn.a10miaomiao.bilimiao.compose.pages.user.content.UserSeasonDetailContent
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoAddFavoriteDialogState
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoCoinDialogState
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoDownloadDialogState
import cn.a10miaomiao.bilimiao.cover.CoverActivity
import cn.a10miaomiao.bilimiao.download.DownloadService
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.delegate.player.BasePlayerDelegate
import com.a10miaomiao.bilimiao.comm.delegate.player.VideoPlayerSource
import com.a10miaomiao.bilimiao.comm.entity.MessageInfo
import com.a10miaomiao.bilimiao.comm.entity.player.PlayListFrom
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.FilterStore
import com.a10miaomiao.bilimiao.comm.store.PlayListStore
import com.a10miaomiao.bilimiao.comm.store.PlayerStore
import com.a10miaomiao.bilimiao.comm.store.UserLibraryStore
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.utils.ClickGuard
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.comm.utils.BvUtils
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.net.URL
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import kotlin.collections.mapNotNull
import android.webkit.CookieManager
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoAiConclusionDialog
import com.a10miaomiao.bilimiao.comm.entity.ResponseResult
import com.a10miaomiao.bilimiao.comm.entity.video.AiConclusionData
import com.a10miaomiao.bilimiao.comm.entity.video.AiConclusionResult
import com.a10miaomiao.bilimiao.comm.entity.video.AiOutline
import com.a10miaomiao.bilimiao.comm.entity.video.AiPartOutline
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

class VideoDetailViewModel(
    override val di: DI,
    id: String,
    seekPosition: Long? = null,
    private val highlightDanmakuText: String? = null,
) : ViewModel(), DIAware {
    private val activity by instance<Activity>()
    private val pageNavigation by instance<PageNavigation>()
    private val basePlayerDelegate by instance<BasePlayerDelegate>()

    private val filterStore: FilterStore by instance()
    private val playerStore: PlayerStore by instance()
    private val playListStore: PlayListStore by instance()
    private val userStore: UserStore by instance()
    private val userLibraryStore: UserLibraryStore by instance()
    private val bottomSheetState: BottomSheetState by instance()

    // AI 总结独立弹窗（不通过 bottomSheetState）
    private val _aiConclusionData = kotlinx.coroutines.flow.MutableStateFlow<com.a10miaomiao.bilimiao.comm.entity.video.AiConclusionResult?>(null)
    val aiConclusionData: kotlinx.coroutines.flow.StateFlow<com.a10miaomiao.bilimiao.comm.entity.video.AiConclusionResult?> get() = _aiConclusionData


    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> get() = _isRefreshing
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> get() = _loading
    private val _fail = MutableStateFlow<Any?>(null)
    val fail: StateFlow<Any?> get() = _fail
    private val _detailData = MutableStateFlow<ViewReply?>(null)
    val detailData: StateFlow<ViewReply?> get() = _detailData

    // 自动连播合集
    private val _isAutoPlaySeason = mutableStateOf(true)
    val isAutoPlaySeason get() = _isAutoPlaySeason.value

    // 此ViewModel启动播放的视频Aid
    private var videoAidToPlay = ""

    /**
     * 消息页导航带来的 seek 位置：只能消费一次。
     * 否则切分P/切集重新播放时会一直带着进入页面时那个旧弹幕时间点起播。
     */
    private var pendingSeekPosition: Long? = seekPosition

    val coinDialogState = VideoCoinDialogState(
        scope = viewModelScope,
        onChanged = ::updateCoinState,
    )
    val addFavoriteDialogState = VideoAddFavoriteDialogState(
        scope = viewModelScope,
        onChanged = ::updateFavoriteState,
    )
    val downloadDialogState = VideoDownloadDialogState(
        scope = viewModelScope,
    )

    private var _id = id

    /**
     * 当前视频的真实 BV 号（空降助手要用）。
     *
     * ★ **不能直接用 `_id`**：`_id` 不一定是 BV 号 ——「继续播放」卡片
     *   （`StartViewContent.kt:312` 传的是 `playerState.aid`）、历史/收藏/稍后再看卡片
     *   （`StartLibraryCard.kt:211` 传的是 `it.aid.toString()`）、点赞消息、动态里的
     *   UGC 合集等入口传进来的**都是 av 号**。把 av 号当 bvid 用 →
     *   查询变成 `videoID=98935548`（av 号）→ 服务端返回 `[]` → **片头片尾等片段全部消失**
     *   （用户实测报的"刚才好了怎么又不见了"）。
     *
     * 取值优先级：
     * ① 视频详情接口返回的 bvid（最可靠，av 入口也能拿到真 BV 号）；
     * ② `_id` **本身就是合法 BV 号**时用它（详情还没加载完时的兜底）；
     * ③ 都没有 → 空串，交给 `VideoPlayerSource.effectiveBvid` 用 aid 现算（带范围保护）。
     */
    private val currentBvid: String
        get() = getBvid().ifBlank { _id.takeIf { BvUtils.isValidBvid(it) } ?: "" }

    init {
        loadData()
    }

    fun onBackPressed() {
        viewModelScope.launch(Dispatchers.Main) {
            if (basePlayerDelegate.isOpened()
                && basePlayerDelegate.getSourceIds().aid == videoAidToPlay) {
                val openMode = SettingPreferences.mapData(activity) {
                    it[PlayerOpenMode] ?: SettingConstants.PLAYER_OPEN_MODE_DEFAULT
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

    private var loadJob: kotlinx.coroutines.Job? = null

    /** 当前在途的 AI 总结请求：切视频/重复点按要取消旧的 */
    private var aiJob: kotlinx.coroutines.Job? = null

    fun changeVideo(id: String) {
        _id = id
        loadData()
    }

    /**
     * 页面被复用（nav entry 不变、只有参数变了）时切换目标视频。
     *
     * 为什么要它：[VideoDetailPage] 的 ViewModel 以前用 `key = id` 创建 —— 自动连播/切集时
     * 同一个页面会换 id，于是每换一次就新建一个 ViewModel，旧的全部留在 ViewModelStore 里
     * 直到这一页真正退出（连播 50 集 = 50 个 VM，各自攥着一份详情/评论状态）。
     * 现在整页只用一个 VM，id 变了就调这里换目标重新加载。
     *
     * 首次组合时也会走到这里（id 与构造时相同）→ 直接返回，**不会**重复请求。
     */
    fun changeVideoIfNeeded(id: String, seekPosition: Long? = null) {
        if (_id == id) return
        // 新参数带来的起始位置：只消费一次（与构造参数 pendingSeekPosition 的语义一致）
        pendingSeekPosition = seekPosition
        changeVideo(id)
    }

    fun loadData() {
        // 取消上一未完成的加载：快速切换视频时避免乱序响应覆盖新视频数据
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
        try {
            _loading.value = true
            _fail.value = null
            _aiConclusionData.value = null
            val req = if (_id.startsWith("BV")) {
                ViewReq(
                    // ⚠️ 这里必须用 _id（不能用 currentBvid）：本函数就是**去加载详情**的，
                    //    此刻 detailData 还是空的，currentBvid 会算出空串 → 请求发不出去。
                    //    入口传 av 号时走下面的 else 分支，本来就正确。
                    bvid = _id,
                )
            } else {
                ViewReq(
                    aid = _id.toLong(),
                )
            }
            val res = BiliGRPCHttp.request {
                ViewGRPC.view(req)
            }.awaitCall()
            _detailData.value = res
            autoStartPlay()
            // 自动获取AI总结（如果开启）
            val aiSummaryEnabled = com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences.mapData(activity) {
                it[com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences.AiSummaryEnabled] ?: false
            }
            if (aiSummaryEnabled) {
                requestAiConclusion(silent = true)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            _fail.value = e
        } finally {
            _isRefreshing.value = false
            _loading.value = false
        }
        }
    }

    private fun autoStartPlay() = viewModelScope.launch(Dispatchers.Main) {
        val arcData = detailData.value?.getArcData() ?: return@launch
        if (basePlayerDelegate.getSourceIds().aid == arcData.aid.toString()) {
            // 同个视频不替换播放
            return@launch
        }
        val openMode = SettingPreferences.mapData(activity) {
            it[PlayerOpenMode] ?: SettingConstants.PLAYER_OPEN_MODE_DEFAULT
        }
        if (basePlayerDelegate.isOpened()) {
            if (basePlayerDelegate.isPlaying()) {
                // 自动替换正在播放的视频
                if (openMode and SettingConstants.PLAYER_OPEN_MODE_AUTO_REPLACE != 0) {
                    playVideo()
                }
            } else if (basePlayerDelegate.isPause()) {
                // 自动替换暂停的视频
                if (openMode and SettingConstants.PLAYER_OPEN_MODE_AUTO_REPLACE_PAUSE != 0) {
                    playVideo()
                }
            } else {
                // 自动替换完成的视频
                if (openMode and SettingConstants.PLAYER_OPEN_MODE_AUTO_REPLACE_COMPLETE != 0) {
                    playVideo()
                }
            }
        } else {
            // 自动播放新视频
            if (openMode and SettingConstants.PLAYER_OPEN_MODE_AUTO_PLAY != 0) {
                playVideo()
            }
        }
    }


    fun playVideo() {
        val detail = detailData.value ?: return
        val pages = detail.getPages()
        val history = detail.history
        if (pages.isNotEmpty()) {
            val page = history?.let { h ->
                pages.find { it.cid == h.cid }
            } ?: pages[0] ?: return
            playVideo(page)
        }
    }
    fun playVideo(page: Page, seekOverride: Long? = null) {
        val detail = detailData.value ?: return
        val arc = detail.getArcData() ?: return
        val author = arc.author ?: return
        videoAidToPlay = arc.aid.toString()
        val viewPages = detail.getPages()
        val ugcSeason = detail.getUgcSeasonData()
        val title = if (viewPages.size > 1) {
            page.part
        } else {
            arc.title
        }
        val cid = page.cid
        val isAutoPlaySeason = this.isAutoPlaySeason
        if (isAutoPlaySeason && ugcSeason != null) {
            // 将合集加入播放列表
            val playListFromId = (playListStore.state.from as? PlayListFrom.Season)?.seasonId
                ?: (playListStore.state.from as? PlayListFrom.Section)?.seasonId
            if (playListFromId != ugcSeason.id.toString() ||
                !playListStore.state.inListForAid(arc.aid.toString())) {
                // 当前播放列表来源不是当前合集或视频不在播放列表中时，创建新播放列表
                // 以合集创建播放列表
                val index = if (ugcSeason.sections.size > 1) {
                    ugcSeason.sections.indexOfFirst { section ->
                        section.episodes.indexOfFirst { it.aid == arc.aid } != -1
                    }
                } else { 0 }
                playListStore.setPlayList(ugcSeason, index)
            }
        } else if (!playListStore.state.inListForAid(arc.aid.toString())) {
            // 当前视频不在播放列表中时，如果未正在播放或播放列表为空则创建新的播放列表，否则将视频加入列表尾部
            if (playListStore.state.items.isEmpty()
                || playerStore.state.aid.isEmpty()) {
                // 以当前视频创建新的播放列表
                val playListItem = playListStore.run {
                    arc.toPlayListItem(viewPages, bvid = currentBvid)
                }
                playListStore.setPlayList(
                    name = arc.title,
                    from = playListItem.from,
                    items = listOf(
                        playListItem,
                    )
                )
            } else {
                // 将视频添加到播放列表末尾
                playListStore.addItem(playListStore.run {
                    arc.toPlayListItem(viewPages, bvid = currentBvid)
                })
            }
        }

        // 播放视频
        basePlayerDelegate.openPlayer(
            VideoPlayerSource(
                mainTitle = arc.title,
                title = title,
                coverUrl = arc.pic,
                aid = arc.aid.toString(),
                // ★ 真实 BVID：必须用 _id，不能用主构造参数 id —— 无 val/var 的构造参数
                //   在成员函数体内不可见（Kotlin 规则，编译报 Unresolved reference）；
                //   而且 _id 会被 changeVideo() 更新，语义正好是"当前正在看的视频"。
                //   绝不能靠 aid 现算：新的 BV 号已不在经典算法的编码范围内，会算出别的视频。
                bvid = currentBvid,
                id = cid.toString(),
                ownerId = author.mid.toString(),
                ownerName = author.name,
            ).apply {
                pages = viewPages
                    .map {
                        VideoPlayerSource.PageInfo(
                            cid = it.cid.toString(),
                            title = it.part,
                        )
                    }
                defaultPlayerSource.run {
                    val history = detailData.value?.history
                    if (history != null) {
                        lastPlayCid = history.cid.toString()
                        lastPlayTime = history.progress * 1000L
                    }
                    // 如果消息页导航携带了 seek 位置，覆盖历史进度（一次性：用掉后置空）
                    pendingSeekPosition?.let { seek ->
                        lastPlayTime = seek
                        lastPlayCid = cid.toString()
                        pendingSeekPosition = null
                    }
                    // 评论空降跳转覆盖，优先级最高
                    if (seekOverride != null) {
                        lastPlayTime = seekOverride
                        lastPlayCid = cid.toString()
                    }
                    val dimension = arc.dimension
                    if (dimension != null) {
                        width = dimension.width.toInt()
                        height = dimension.height.toInt()
                    }
                }
            }
        )
    }

    /**
     * 从评论区空降跳转到指定位置（无视频播放时调用）
     */
    fun playVideoAtPosition(seekMs: Long) {
        val detail = detailData.value ?: return
        val pages = detail.getPages()
        if (pages.isNotEmpty()) {
            val history = detail.history
            val page = history?.let { h ->
                pages.find { it.cid == h.cid }
            } ?: pages[0] ?: return
            playVideo(page, seekOverride = seekMs)
        }
    }

    fun isPlayerActive(): Boolean {
        return basePlayerDelegate.isPlaying() || basePlayerDelegate.isPause()
    }

    /**
     * 安全跳转到指定位置。如果播放器正在准备中，则重新打开并等待加载完成后再跳转。
     * 复用番剧恢复播放的等待逻辑：通过 seekOverride 设置 lastPlayTime，播放器 delegate 在加载完成后自动 seek。
     */
    fun seekToPosition(seekMs: Long) {
        if (basePlayerDelegate.isPlaying() && !basePlayerDelegate.isPreparing()) {
            // 播放器已在播放中 → 直接跳转
            com.a10miaomiao.bilimiao.comm.delegate.player.PlayerSeekBus.onSeek?.invoke(seekMs)
        } else if (basePlayerDelegate.isPause()) {
            // 播放器已暂停（已加载完成）→ 直接跳转
            com.a10miaomiao.bilimiao.comm.delegate.player.PlayerSeekBus.onSeek?.invoke(seekMs)
        } else if (basePlayerDelegate.isPreparing()) {
            // 播放器正在准备中 → 不重复调用 openPlayer，避免卡死
            // 已发起的 openPlayer 会通过 seekOverride 处理跳转位置
            return
        } else {
            // 播放器未打开 → 重新打开并等待加载完成后跳转
            playVideoAtPosition(seekMs)
        }
    }

    /**
     * 添加至稍后再看
     */
    fun addVideoHistoryToview() = viewModelScope.launch(Dispatchers.IO) {
        if (!userStore.isLogin()) {
            toast("请先登录")
            return@launch
        }
        try {
            val arcData = detailData.value?.getArcData() ?: return@launch
            val res = BiliApiService.userApi
                .videoToviewAdd(arcData.aid.toString())
                .awaitCall()
                .json<MessageInfo>()
            if (res.code == 0) {
                toast("已添加至稍后再看")
                userLibraryStore.appendWatchLater(
                    UserLibraryStore.WatchLaterInfo(
                        aid = arcData.aid,
                        title = arcData.title,
                        cover = arcData.pic,
                    )
                )
            } else {
                toast(res.message)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            toast(e.toString())
        }
    }

    fun ViewReply.getArcData(): bilibili.app.archive.v1.Arc? {
        return arc ?: activitySeason?.arc
    }

    fun ViewReply.getReqUserData(): bilibili.app.view.v1.ReqUser? {
        return activitySeason?.reqUser ?: reqUser
    }

    fun ViewReply.getUgcSeasonData(): bilibili.app.view.v1.UgcSeason? {
        return ugcSeason ?: activitySeason?.ugcSeason
    }

    fun ViewReply.getPages(): List<bilibili.app.archive.v1.Page> {
        return (activitySeason?.pages ?: pages).mapNotNull { it.page }
    }

    fun ViewReply.getBvid(): String {
        return activitySeason?.bvid ?: bvid
    }

    fun getBvid(): String {
        return detailData.value?.getBvid() ?: ""
    }

    private fun updateArcAndReqUser(
        arc: bilibili.app.archive.v1.Arc?,
        reqUser: bilibili.app.view.v1.ReqUser?,
    ) {
        val videoDetail = detailData.value ?: return
        val activitySeason = videoDetail.activitySeason
        if (activitySeason != null) {
            _detailData.value = videoDetail.copy(
                activitySeason = activitySeason.copy(
                    arc = arc,
                    reqUser = reqUser,
                ),
            )
        } else {
            _detailData.value = videoDetail.copy(
                arc = arc,
                reqUser = reqUser,
            )
        }
    }

    private fun updateCoinState(state: Int) {
        val videoDetail = detailData.value ?: return
        var videoArc = videoDetail.getArcData()
        var reqUser = videoDetail.getReqUserData()
        val stat = videoArc?.stat
        videoArc = videoArc?.copy(
            stat = stat?.copy(
                coin = stat.coin + state,
            )
        )
        reqUser = reqUser?.copy(
            // 这里存的是"我在这个视频上累计投了几枚"，原来直接写成本次数量
            coin = (reqUser?.coin ?: 0) + state,
        )
        updateArcAndReqUser(videoArc, reqUser)
    }

    private fun updateFavoriteState(state: Int) {
        val videoDetail = detailData.value ?: return
        var videoArc = videoDetail.getArcData()
        var reqUser = videoDetail.getReqUserData()
        val stat = videoArc?.stat
        if (state == 0) {
            videoArc = videoArc?.copy(
                stat = stat?.copy(
                    fav = stat.fav - 1,
                )
            )
            reqUser = reqUser?.copy(
                favorite = state,
            )
        } else if (state == 1) {
            videoArc = videoArc?.copy(
                stat = stat?.copy(
                    fav = stat.fav + 1,
                )
            )
            reqUser = reqUser?.copy(
                favorite = state,
            )
        }
        updateArcAndReqUser(videoArc, reqUser)
    }

    private fun updateLikeState(state: Int) {
        val videoDetail = detailData.value ?: return
        var videoArc = videoDetail.arc ?: videoDetail.activitySeason?.arc
        var reqUser = videoDetail.getReqUserData()
        val stat = videoArc?.stat
        if (state == 0) {
            videoArc = videoArc?.copy(
                stat = stat?.copy(
                    like = stat.like - 1,
                )
            )
            reqUser = reqUser?.copy(
                like = state,
            )
        } else if (state == 1) {
            videoArc = videoArc?.copy(
                stat = stat?.copy(
                    like = stat.like + 1,
                )
            )
            reqUser = reqUser?.copy(
                like = state,
            )
        }
        updateArcAndReqUser(videoArc, reqUser)
    }

    /**
     * 点赞/取消点赞
     */
    fun requestLike(
        arc: bilibili.app.archive.v1.Arc,
        reqUser: bilibili.app.view.v1.ReqUser?,
    ) = viewModelScope.launch(Dispatchers.IO) {
        if (!userStore.isLogin()) {
            withContext(Dispatchers.Main) {
                toast("请先登录")
            }
            return@launch
        }
        // ★ 防连点：点赞是 toggle，但连点两次时第二次读到的还是**没更新的旧状态**，
        //   于是同一个 like=1 被发两遍 —— 既白刷服务端，回来还会把本地状态改乱。
        //   同一 aid 同一时刻只放一个请求进去（请求结束即释放，不影响正常单点）。
        // ★ 这里以前写的是 "${'$'}{arc.aid}"，${'$'} 转义产出的是**字面量 $**，
        //   key 恒等于 "video:like:${arc.aid}"（跟 aid 无关）。于是 A 视频的点赞请求还在飞时
        //   给 B 视频点赞，会被 ClickGuard 当成连点**静默丢掉**（没有任何提示，用户以为点了没反应）。
        val likeKey = "video:like:${arc.aid}"
        if (!ClickGuard.enter(likeKey)) return@launch
        try {
            val res = BiliApiService.videoAPI
                .like(
                    aid = arc.aid.toString(),
                    dislike = reqUser?.dislike ?: 0,
                    like = reqUser?.like ?: 0,
                )
                .awaitCall()
                .json<MessageInfo>()
            if (res.isSuccess) {
                val state = if (reqUser?.like == 1) 0 else 1
                if (state == 1) {
                    toast("点赞成功")
                } else {
                    toast("已取消点赞")
                }
                updateLikeState(state)
            } else {
                toast(res.message)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            toast(e.message ?: e.toString())
        } finally {
            ClickGuard.leave(likeKey)
        }
    }

    fun updateIsAutoPlaySeason(isChecked: Boolean) {
        _isAutoPlaySeason.value = isChecked
    }

    fun openVideoPages() {
        val arc = detailData.value?.getArcData() ?: return
        // 分 P 列表也要带上真实 BV 号，否则从分 P 切集会丢掉 bvid，空降助手查不到片段
        bottomSheetState.open(VideoPagesPage(arc.aid.toString(), currentBvid))
    }

    fun openCoverActivity() {
        val arc = detailData.value?.getArcData() ?: return
        var coverUrl = arc.pic.replace("http://", "https://")
        // 去掉图床处理参数（@672w_378h_1c_ 等），取原始封面图
        val atIndex = coverUrl.indexOf('@')
        if (atIndex > 0) {
            coverUrl = coverUrl.substring(0, atIndex)
        }
        if (coverUrl.isBlank()) {
            toast("未获取到封面")
            return
        }
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val conn = URL(coverUrl).openConnection()
                    conn.connectTimeout = 10000
                    conn.doInput = true
                    conn.connect()
                    conn.getInputStream().use { it.readBytes() }
                }
                if (bytes.isEmpty()) {
                    withContext(Dispatchers.Main) { toast("封面下载失败") }
                    return@launch
                }
                // 根据 URL 扩展名决定文件名与 MIME，保持原图格式
                val path = coverUrl.substringBefore('?')
                val ext = path.substringAfterLast('.', "").lowercase()
                val (fileName, mime) = when (ext) {
                    "png" -> "cover_${arc.aid}.png" to "image/png"
                    "webp" -> "cover_${arc.aid}.webp" to "image/webp"
                    "gif" -> "cover_${arc.aid}.gif" to "image/gif"
                    "jpeg" -> "cover_${arc.aid}.jpeg" to "image/jpeg"
                    else -> "cover_${arc.aid}.jpg" to "image/jpeg"
                }
                withContext(Dispatchers.IO) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, mime)
                        put(MediaStore.Images.Media.DESCRIPTION, arc.title)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Bilimiao")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                    val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        // insert/开流失败（存储满、无权限）以前也照样提示"封面已保存到相册"
                        ?: throw Exception("无法写入相册")
                    activity.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(bytes)
                    } ?: throw Exception("无法写入相册")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        activity.contentResolver.update(uri, values, null, null)
                    }
                }
                withContext(Dispatchers.Main) { toast("封面已保存到相册") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                withContext(Dispatchers.Main) { toast("封面保存失败: ${e.message}") }
            }
        }
    }

    fun toUserPage(mid: String) {
        pageNavigation.navigate(UserSpacePage(
            id = mid,
        ))
    }

    /**
     * 打开"相关视频"里的另一个视频：**必须压新页**（传 defaultNavOptions），不能走默认的 launchSingleTop。
     *
     * 为什么：默认导航参数为了让"连点同一个入口"不压 N 层，给所有导航都补了 launchSingleTop；
     * 而相关视频点进去的目标页**和当前页是同一个路由**（VideoDetailPage，只是 id 不同），
     * 于是导航框架把当前这一页**替换**掉了 —— 用户实测到的三条问题全出在这儿：
     *   ① 一路点到第 N 个视频，返回直接回首页（栈里从头到尾只有一个详情页）；
     *   ② 新视频的页面沿用旧页面的滚动位置（同一个 entry，滚动状态没重建）；
     *   ③ 没有转场动画、内容直接换掉（没有新 entry 就没有进场动画，还会闪一下）。
     * 传 defaultNavOptions（不带 singleTop）后：压新页 → 返回回到上一个视频、页面从顶部开始、
     * 走 BilimiaoPageRoute 里的转场动画；新 entry 的 ViewModel 是新的（detailData 为空）→
     * 加载时的 22/33 加载动画也回来了。
     */
    fun toVideoPage(aid: String) {
        pageNavigation.navigate(VideoDetailPage(
            id = aid,
        ), cn.a10miaomiao.bilimiao.compose.common.defaultNavOptions)
    }

    fun toSearchPage(keyword: String) {
        pageNavigation.navigate(SearchResultPage(
            keyword = keyword,
        ))
    }

    fun toPlayListPage() {
        pageNavigation.navigate(PlayListPage())
    }

    fun toUgcSeasonPage(seasonId: String, seasonTitle: String) {
        pageNavigation.navigate(UserSeasonDetailPage(
            id = seasonId,
            title = seasonTitle,
        ))
    }

    fun openCoinDialog(aid: String, copyright: Int) {
        if (!userStore.isLogin()) {
            toast("请先登录")
            return
        }
        val maxTotal = if (copyright == 2) 1 else 2
        val coinGiven = detailData.value?.getReqUserData()?.coin ?: 0
        if (coinGiven >= maxTotal) {
            toast("这个视频已经投满币了")
            return
        }
        coinDialogState.show(aid, copyright, coinGiven)
    }

    fun openAddFavoriteDialog(aid: String) {
        if (!userStore.isLogin()) {
            toast("请先登录")
            return
        }
        addFavoriteDialogState.show(aid)
    }

    fun openDownloadDialog() {
        val videoDetail = detailData.value ?: return
        val videoArc = videoDetail.getArcData() ?: return
        val viewPages = videoDetail.getPages()
        val ugcSeason = videoDetail.getUgcSeasonData()
        val ugcSeasonEpisodes = ugcSeason?.sections?.flatMap { section ->
            section.episodes.filterNotNull().map { ep ->
                VideoDownloadDialogState.SeasonEpisodeItem(
                    aid = ep.aid,
                    title = ep.title,
                    cover = ep.cover,
                    duration = ep.coverRightText,
                )
            }
        }
        viewModelScope.launch {
            val downloadService = DownloadService.getService(activity)
            downloadDialogState.show(
                downloadService,
                videoDetail.getBvid(),
                videoArc,
                viewPages,
                activity,
                ugcSeasonEpisodes = ugcSeasonEpisodes,
                seasonId = ugcSeason?.id?.toString(),
                seasonTitle = ugcSeason?.title,
            )
        }
    }

    fun openShare(id: String, title: String) {
        val url = "http://www.bilibili.com/video/$id"
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "bilibili视频分享")
            putExtra(
                Intent.EXTRA_TEXT,
                "$title $url"
            )
        }
        activity.startActivity(Intent.createChooser(shareIntent, "分享"))
    }

    fun copyPlainText(label: String, text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    fun menuItemClick(view: View, item: MenuItemPropInfo) {
        val videoDetail = detailData.value ?: return
        val videoArc = videoDetail.getArcData() ?: return
        val viewPages = videoDetail.getPages()
        val ugcSeason = videoDetail.getUgcSeasonData()
        val ugcSeasonEpisodes = ugcSeason?.sections?.flatMap { section ->
            section.episodes.filterNotNull().map { ep ->
                VideoDownloadDialogState.SeasonEpisodeItem(
                    aid = ep.aid,
                    title = ep.title,
                    cover = ep.cover,
                    duration = ep.coverRightText,
                )
            }
        }
        when (item.key) {
            MenuKeys.download -> openDownloadDialog()
            MenuKeys.favourite -> {
                openAddFavoriteDialog(videoArc.aid.toString())
            }
            1 -> {
                // 分享
                openShare(videoDetail.getBvid(), videoArc.title)
            }
            2 -> {
                // 浏览器打开
                val url = "http://www.bilibili.com/video/${videoDetail.getBvid()}"
                pageNavigation.launchWebBrowser(url)
            }
            3 -> {
                // 复制链接
                val text = "http://www.bilibili.com/video/${videoDetail.getBvid()}"
                copyPlainText("URL", text)
                toast("已复制：$text")
            }
            4 -> {
                // 复制AV号
                val text = "av${videoArc.aid}"
                copyPlainText("URL", text)
                toast("已复制：$text")
            }
            5 -> {
                // 复制BV号
                val text = videoDetail.getBvid()
                copyPlainText("URL", text)
                toast("已复制：$text")
            }
            6 -> {
                // 保存封面
                openCoverActivity()
            }
            11 -> {
                // 添加至下一个播放
                val current = playerStore.getPlayListCurrentPosition()
                if (current != -1) {
                    playListStore.run {
                        addItem(
                            videoArc.toPlayListItem(viewPages, bvid = currentBvid),
                            current + 1
                        )
                    }
                    toast("已添加至下一个播放")
                } else {
                    toast("添加失败，找不到正在播放的视频")
                }
            }
            12 -> {
                // 添加至最后一个播放
                playListStore.run {
                    addItem(
                        videoArc.toPlayListItem(viewPages, bvid = currentBvid),
                        state.items.size,
                    )
                }
                toast("已添加至最后一个播放")
            }
            MenuKeys.aiSummary -> {
                requestAiConclusion()
            }
            13 -> {
                // 添加至稍后再看
                addVideoHistoryToview()
            }
        }
    }

    // ============ AI 视频总结 + WBI 签名（内联同步实现，避开协程嵌套） ============

    companion object {
        private val MIXIN_TABLE = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13
        )
        private val CHR_FILTER = Regex("[!'()*]")

        private var cachedMixKey: String? = null
        private var cachedDay: Int = -1

        private fun getMixinKey(raw: String): String {
            val sb = StringBuilder(32)
            for (i in MIXIN_TABLE) if (i < raw.length) sb.append(raw[i])
            return sb.toString()
        }

        /** 同步获取 mixKey——独立 OkHttpClient + Gson 解析，不经过 MiaoHttp/协程 */
        fun fetchMixKeySync(log: (String) -> Unit): String {
            val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
            if (cachedMixKey != null && cachedMixKey!!.isNotEmpty() && cachedDay == today) {
                // log("✓ 使用缓存 mixKey")
                return cachedMixKey!!
            }
            // log("→ HTTP 请求 nav 接口...")
            val client = OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com") ?: ""
            // log("Cookie 长度: ${cookie.length}")
            val req = okhttp3.Request.Builder()
                .url("https://api.bilibili.com/x/web-interface/nav")
                .addHeader("User-Agent", "BiliDroid/1.45.0 (biao@bilimiao)")
                .addHeader("Referer", "https://www.bilibili.com/")
                .apply { if (cookie.isNotBlank()) addHeader("Cookie", cookie) }
                .build()
            val res = client.newCall(req).execute()
            // log("← nav HTTP ${res.code}")
            val body = res.body?.string() ?: ""
            res.close()
            // log("nav body 长度: ${body.length}, 前200: ${body.take(200)}")
            // 用 Android 内置 JSONObject（MiaoJson 是 kotlinx.serialization，不支持 Any）
            val navJson = org.json.JSONObject(body)
            val dataJson = navJson.optJSONObject("data")
            // log("nav code: ${navJson.optInt("code", -1)}")
            if (dataJson == null) { log("data 为 null"); return "" }
            val wbiJson = dataJson.optJSONObject("wbi_img")
            if (wbiJson == null) { log("wbi_img 为 null, data keys: ${dataJson.keys()}"); return "" }
            val imgUrl = wbiJson.optString("img_url", "")
            val subUrl = wbiJson.optString("sub_url", "")
            if (imgUrl.isEmpty() || subUrl.isEmpty()) { log("img_url=$imgUrl sub_url=$subUrl"); return "" }
            val imgKey = imgUrl.substringAfterLast("/").substringBefore(".")
            val subKey = subUrl.substringAfterLast("/").substringBefore(".")
            // log("imgKey=$imgKey subKey=$subKey")
            val mk = getMixinKey(imgKey + subKey)
            // log("mixKey 长度: ${mk.length}")
            if (mk.isNotEmpty()) { cachedMixKey = mk; cachedDay = today }
            return mk
        }

        fun signUrl(rawUrl: String, mixKey: String): String {
            if (mixKey.isEmpty()) return rawUrl
            val qi = rawUrl.indexOf('?'); if (qi < 0) return rawUrl
            val params = linkedMapOf<String, String>()
            for (pair in rawUrl.substring(qi + 1).split("&")) {
                val eq = pair.indexOf('='); if (eq < 0) continue
                params[URLDecoder.decode(pair.substring(0, eq), "UTF-8")] =
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }
            val wts = (System.currentTimeMillis() / 1000).toString()
            params["wts"] = wts
            val qs = params.keys.sorted().joinToString("&") { k ->
                val v = params[k]?.replace(CHR_FILTER, "") ?: ""
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            val wr = MessageDigest.getInstance("MD5")
                .digest((qs + mixKey).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return rawUrl.substring(0, qi + 1) + qs + "&w_rid=$wr"
        }
    }

    fun requestAiConclusion(silent: Boolean = false) = run {
        // ★ 先取消在途的旧请求。自动拉取（loadData 里）和菜单点按可以并发，切视频之后旧视频的
        //   WBI 签名 + 请求还在飞，回来照样写 _aiConclusionData —— 用户看到的是"B 视频页面
        //   显示 A 视频的 AI 总结"。
        aiJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
        if (!userStore.isLogin()) {
            if (!silent) withContext(Dispatchers.Main) { toast("请先登录后使用 AI 总结") }
            return@launch
        }
        val detail = detailData.value
        if (detail == null) { miaoLogger().e("AI总结：detailData 为空"); return@launch }
        val arc = detail.getArcData()
        if (arc == null) { miaoLogger().e("AI总结：arcData 为空"); return@launch }
        val bvid = detail.getBvid()
        if (bvid == null) { miaoLogger().e("AI总结：bvid 为空"); return@launch }
        val pages = detail.getPages()
        val pageCid = detail.history?.let { h -> pages.find { it.cid == h.cid } }?.cid
            ?: pages.firstOrNull()?.cid
        if (pageCid == null) { miaoLogger().e("AI总结：cid 为空（无分P数据）"); return@launch }
        val cid = pageCid.toString()
        val upMid = arc.author?.mid?.toString()
        if (upMid == null) { miaoLogger().e("AI总结：upMid 为空"); return@launch }


        // 步骤 1：同步获取 WBI mix_key
        val mixKey = fetchMixKeySync { miaoLogger().d("AI总结：$it") }
        if (mixKey.isEmpty()) {
            if (!silent) withContext(Dispatchers.Main) { toast("WBI 密钥获取失败，请检查网络") }
            return@launch
        }

        // 步骤 2：构建并签名 URL
        val rawUrl = "https://api.bilibili.com/x/web-interface/view/conclusion/get?" +
            "bvid=${URLEncoder.encode(bvid, "UTF-8")}" +
            "&cid=${URLEncoder.encode(cid, "UTF-8")}" +
            "&up_mid=${URLEncoder.encode(upMid, "UTF-8")}"
        val signedUrl = signUrl(rawUrl, mixKey)

        // 步骤 3：发起 AI 总结请求
        try {
            val res = MiaoHttp.request {
                isWebApi = true
                url = signedUrl
            }.awaitCall()
            val resBody = res.body?.string() ?: ""
            // 直接用 JSONObject 解析（ResponseResult 的 result 字段对不上 B站 web API 的 "data" 键）
            val root = org.json.JSONObject(resBody)
            val code = root.optInt("code", -1)
            if (code != 0) {
                if (!silent) withContext(Dispatchers.Main) {
                    toast(root.optString("message", "AI 总结获取失败"))
                }
                return@launch
            }
            val data = root.optJSONObject("data")
            if (data == null) {
                if (!silent) withContext(Dispatchers.Main) { toast("AI 总结数据为空") }
                return@launch
            }
            val mr = data.optJSONObject("model_result")
            if (mr == null) {
                if (!silent) withContext(Dispatchers.Main) { toast("AI 总结内容为空") }
                return@launch
            }
            val summary = mr.optString("summary", "")
            val outlineList = mutableListOf<AiOutline>()
            val outlineArr = mr.optJSONArray("outline")
            if (outlineArr != null) {
                for (i in 0 until outlineArr.length()) {
                    val o = outlineArr.optJSONObject(i) ?: continue
                    val title = o.optString("title", "")
                    val ts = o.optInt("timestamp", 0)
                    val parts = mutableListOf<AiPartOutline>()
                    val partArr = o.optJSONArray("part_outline")
                    if (partArr != null) {
                        for (j in 0 until partArr.length()) {
                            val p = partArr.optJSONObject(j) ?: continue
                            parts.add(AiPartOutline(
                                timestamp = p.optInt("timestamp", 0),
                                content = p.optString("content", "")
                            ))
                        }
                    }
                    outlineList.add(AiOutline(
                        title = title,
                        timestamp = ts,
                        partOutline = parts
                    ))
                }
            }
            val aiResult = AiConclusionResult(
                summary = summary,
                outline = outlineList.ifEmpty { null }
            )
            withContext(Dispatchers.Main) {
                // 写回前再确认一次"还是同一个视频、同一个分P"：取消是异步的，万一旧请求刚好
                // 走到这里，也不能把新视频的总结覆盖掉
                val nowDetail = detailData.value
                val nowPages = nowDetail?.getPages()
                val nowCid = nowDetail?.history?.let { h -> nowPages?.find { it.cid == h.cid }?.cid }
                    ?: nowPages?.firstOrNull()?.cid
                if (nowDetail?.getBvid() == bvid && nowCid?.toString() == cid) {
                    _aiConclusionData.value = aiResult
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                if (!silent) toast("AI 总结请求失败: ${e.message}")
            }
        } finally {
        }
        }.also { aiJob = it }
    }

}