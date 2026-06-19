package com.a10miaomiao.bilimiao.comm.delegate.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.util.Rational
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.PopupMenu
import androidx.datastore.preferences.core.Preferences
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.BangumiEpisodesPage
import cn.a10miaomiao.bilimiao.compose.pages.player.SendDanmakuPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.DanmakuDisplaySettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.DanmakuSettingPage

import cn.a10miaomiao.bilimiao.compose.pages.setting.VideoSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.video.VideoPagesPage
import com.a10miaomiao.bilimiao.R
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.delegate.helper.StatusBarHelper
import com.a10miaomiao.bilimiao.comm.dialogx.showTop
import com.a10miaomiao.bilimiao.comm.navigation.openBottomSheet
import com.a10miaomiao.bilimiao.comm.store.AppStore
import com.a10miaomiao.bilimiao.comm.store.PlayListStore
import com.a10miaomiao.bilimiao.comm.store.PlayerStore
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.service.PlaybackService
import com.a10miaomiao.bilimiao.widget.player.ChapterInfo
import android.widget.Toast
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.comm.delegate.player.PlayerSeekBus
import com.a10miaomiao.bilimiao.widget.player.DanmakuTextFilter
import com.a10miaomiao.bilimiao.widget.player.DanmakuVideoPlayer
import com.a10miaomiao.bilimiao.widget.player.DlnaManager
import com.a10miaomiao.bilimiao.widget.player.DlnaDevice
import com.a10miaomiao.bilimiao.widget.player.VideoPlayerCallBack
import master.flame.danmaku.controller.DanmakuFilters
import com.a10miaomiao.bilimiao.widget.scaffold.ScaffoldView
import com.a10miaomiao.bilimiao.comm.toast
import com.kongzue.dialogx.dialogs.PopTip
import com.shuyu.gsyvideoplayer.listener.GSYVideoProgressListener
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance


class PlayerController(
    private var activity: AppCompatActivity,
    private val delegate: PlayerDelegate2,
    private val scope: CoroutineScope,
    override val di: DI,
) : DIAware, VideoPlayerCallBack, GSYVideoProgressListener {

    private val userStore by instance<UserStore>()
    private val appStore by instance<AppStore>()
    private val playerStore by instance<PlayerStore>()
    private val playListStore by instance<PlayListStore>()
    private val statusBarHelper by instance<StatusBarHelper>()
    private val scaffoldApp get() = delegate.scaffoldApp
    private val views get() = delegate.views
    private val player: DanmakuVideoPlayer? get() = views.videoPlayer
    private val playerSourceInfo get() = delegate.playerSourceInfo
    private var moreMenuAnchor: View? = null

    // 🚫 DLNA_DISABLED
    // private val dlnaManager by lazy { DlnaManager(activity) }
    private val dlnaManager: DlnaManager? = null
    private val danmakuContext = DanmakuContext.create()
    private val danmakuTextFilter = DanmakuTextFilter()

    private var onlyFull = false // 仅全屏播放
    private var hasCheckedAutoFullScreen = false
    private var showSubtitle = false // 默认显示字幕
    private var showAiSubtitle = true // 默认显示AI字幕
    private var canAutoCloseFullScreen = false
    var isBackgroundPlay = false // 后台播放
        private set
    var isPipOnBackground = false // 后台小窗播放
        private set
    var explicitExitSmallWindow = false // 用户主动退出小窗，阻止后台自动PiP

    var isShowChild = false
    private var preparedRunQueue = mutableListOf<Pair<String, Runnable>>()

    private fun currentDanmakuMode(): SettingPreferences.Danmaku {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.isInPictureInPictureMode
        ) {
            return SettingPreferences.DanmakuPipMode
        }
        val player = player ?: return SettingPreferences.DanmakuDefault
        return when (player.mode) {
            DanmakuVideoPlayer.PlayerMode.SMALL_TOP -> SettingPreferences.DanmakuSmallMode
            DanmakuVideoPlayer.PlayerMode.SMALL_FLOAT -> SettingPreferences.DanmakuSmallMode
            DanmakuVideoPlayer.PlayerMode.FULL -> SettingPreferences.DanmakuFullMode
        }
    }

    private fun getFullMode(preferences: Preferences): Int {
        return preferences[SettingPreferences.PlayerFullMode]
            ?: SettingConstants.PLAYER_FULL_MODE_AUTO
    }

    fun initController() = player?.run {
        val that = this@PlayerController
        // 注册 seek 总线供评论区时间戳使用
        PlayerSeekBus.onSeek = { ms -> seekTo(ms) }
        // 弹幕时间戳点击检测已移到 DanmakuVideoPlayer.onTouchEvent 中
        // 避免 GestureDetector 拦截播放器的触摸手势
        statusBarHelper = that.statusBarHelper
        isFullHideActionBar = true
        backButton.setOnClickListener { onBackClick() }
        setIsTouchWiget(true)
        fullscreenButton.setOnClickListener(::changeFullscreen)
        fullscreenButton.setOnLongClickListener {
            showFullModeMenu(it)
            true
        }
        danmakuContext = that.danmakuContext

        qualityView.setOnClickListener(that::showQualityPopupMenu)
        speedView.setOnClickListener(that::showSpeedPopupMenu)
        moreBtn.setOnClickListener(that::showMoreMenu)
        setDanmakuSwitchOnClickListener(that::danmakuSwitchClick)
        setExpandButtonOnClickListener(that::showPagesOrEpisodes)
        setSendDanmakuButtonOnClickListener(that::showSendDanmakuPage)
        setSendDanmakuButtonOnLongClickListener {
            danmakuSwitchClick(it)
            true
        }
        serHoldUpButtonOnClickListener(that::holdUpPlayer)
        // 🚫 DLNA_DISABLED
//        dlnaManager = that.dlnaManager
//        dlnaManager?.onDevicesChanged = { _ ->
//            updateCastButton()
//        }
//        onCastClick = { view ->
//            that.showCastDeviceList(view)
//        }
        videoPlayerCallBack = that
        setGSYVideoProgressListener(that)
        updatePlayerMode(activity.resources.configuration)
        // 同步初始化弹幕过滤器，避免首次启动时因协程延迟导致过滤器未注册
        kotlinx.coroutines.runBlocking {
            SettingPreferences.getData(activity) {
                initDanmakuContext(it)
            }
        }
        scope.launch {
            initPlayerSetting()
        }

        // 无障碍适配
        contentDescription = "播放窗口"
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                super.sendAccessibilityEvent(host, eventType)
                when (eventType) {
                    AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> {
                        showController()
                    }
                }
            }
        }
    }

    fun changeFullscreen(view: View) {
        scope.launch {
            if (scaffoldApp.fullScreenPlayer) {
                smallScreen()
            } else {
                // 横屏模式下直接进入全屏
                if (scaffoldApp.orientation == ScaffoldView.HORIZONTAL) {
                    val fullMode = SettingPreferences.mapData(activity) {
                        getFullMode(it)
                    }
                    fullScreen(fullMode)
                    return@launch
                }
                // 小窗浮窗模式：点全屏改为调整窗口大小适配视频比例
                if (player?.mode == DanmakuVideoPlayer.PlayerMode.SMALL_FLOAT) {
                    resizeSmallWindowToVideoRatio()
                    return@launch
                }
                val fullMode = SettingPreferences.mapData(activity) {
                    getFullMode(it)
                }
                fullScreen(fullMode)
            }
        }
    }

    /**
     * 全屏
     */
    fun fullScreen(fullMode: Int, onlyFull: Boolean = false) {
        this.onlyFull = onlyFull
        canAutoCloseFullScreen = false
        player?.mode = DanmakuVideoPlayer.PlayerMode.FULL
        scaffoldApp.fullScreenPlayer = true
        // 全屏播放时允许横屏
        activity.requestedOrientation = when (fullMode) {
            // 横向全屏(自动旋转)
            SettingConstants.PLAYER_FULL_MODE_SENSOR_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            // 横向全屏(固定方向1)
            SettingConstants.PLAYER_FULL_MODE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            // 横向全屏(固定方向2)
            SettingConstants.PLAYER_FULL_MODE_REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            // 跟随系统：不指定方向
            SettingConstants.PLAYER_FULL_MODE_UNSPECIFIED -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // 跟随视频：竖向视频时为不指定方向，横向视频时候为横屏全屏(自动旋转)
            SettingConstants.PLAYER_FULL_MODE_AUTO -> {
                if ((playerSourceInfo?.screenProportion ?: 1f) < 1f) {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }

            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        statusBarHelper.isShowStatus = true
        statusBarHelper.isShowNavigation = false

        scope.launch {
            SettingPreferences.getData(activity) {
                initVideoSetting(it)
                initDanmakuContext(it)
            }
        }
    }

    /**
     * 自适应小窗比例：在全屏浮窗内按视频比例调整窗口大小
     */
    private fun resizeSmallWindowToVideoRatio() {
        try {
            val sourceInfo = playerSourceInfo
                ?: delegate.playerSource?.defaultPlayerSource
                ?: return
            val videoW = sourceInfo.width ?: return
            val videoH = sourceInfo.height ?: return
            if (videoH == 0) return

            val parent = player?.parent as? View ?: return
            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            val lp = parent.layoutParams as? WindowManager.LayoutParams ?: return

            val aspectRatio = videoW.toFloat() / videoH.toFloat()
            val density = activity.resources.displayMetrics.density

            // 保持当前高度，按视频比例计算宽度
            val currentH = lp.height
            if (currentH > 0) {
                lp.width = (currentH * aspectRatio).toInt().coerceAtLeast((80 * density).toInt())
            }

            wm.updateViewLayout(parent, lp)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 退出全屏
     */
    fun smallScreen() {
        if (player?.mode == DanmakuVideoPlayer.PlayerMode.SMALL_FLOAT) {
            explicitExitSmallWindow = true
        }
        player?.mode = DanmakuVideoPlayer.PlayerMode.SMALL_TOP
        updatePlayerMode(activity.resources.configuration)
        scaffoldApp.fullScreenPlayer = false
        activity.requestedOrientation = getAppSettingScreenOrientation()
        statusBarHelper.isShowStatus = true
        statusBarHelper.isShowNavigation = true

        scope.launch {
            SettingPreferences.getData(activity) {
                initVideoSetting(it)
                initDanmakuContext(it)
            }
        }
    }

    private fun getAppSettingScreenOrientation(): Int {
        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun updatePlayerMode(config: Configuration) {
        val isLandscape = config.orientation != Configuration.ORIENTATION_PORTRAIT
        if (player?.mode != DanmakuVideoPlayer.PlayerMode.FULL) {
            player?.mode = if (config.orientation == ScaffoldView.VERTICAL) {
                DanmakuVideoPlayer.PlayerMode.SMALL_TOP
            } else {
                DanmakuVideoPlayer.PlayerMode.SMALL_FLOAT
            }
        }
        // 同步布局方向给播放器，用于横屏非全屏禁用双指手势
        player?.isLandscapeLayout = isLandscape
    }

    /**
     * 屏幕方向改变
     */
    fun onChangedScreenOrientation(
        orientation: Int
    ) {
        // 通知播放器方向变化，横屏非全屏时禁用双指手势
        player?.onOrientationChanged()

        if (!scaffoldApp.showPlayer) {
            return
        }
        scope.launch {
            val openMode = SettingPreferences.mapData(activity) {
                it[PlayerOpenMode] ?: SettingConstants.PLAYER_OPEN_MODE_DEFAULT
            }
            val autoFullScreen = if (orientation == ScaffoldView.VERTICAL) {
                openMode and SettingConstants.PLAYER_OPEN_MODE_AUTO_FULL_SCREEN != 0
            } else {
                openMode and SettingConstants.PLAYER_OPEN_MODE_AUTO_FULL_SCREEN_LANDSCAPE != 0
            }
            if (autoFullScreen && !scaffoldApp.fullScreenPlayer) {
                // 自动切换全屏
                fullScreen(SettingConstants.PLAYER_FULL_MODE_UNSPECIFIED)
                canAutoCloseFullScreen = true
            } else if (!autoFullScreen && canAutoCloseFullScreen && scaffoldApp.fullScreenPlayer) {
                // 自动切回小屏
                smallScreen()
            }
        }
    }

    private suspend fun initPlayerSetting() {
        SettingPreferences.getData(activity) {
            GSYVideoType.setShowType(
                it[PlayerScreenType] ?: GSYVideoType.SCREEN_TYPE_DEFAULT
            )
            if (it[DanmakuSysFont] != true) {
                danmakuContext.setTypeface(
                    Typeface.createFromAsset(
                        activity.assets,
                        "fonts/danmaku.ttf"
                    )
                )
            }
        }
        SettingPreferences.run {
            activity.dataStore.data.collect {
                initVideoSetting(it)
                initDanmakuContext(it)
            }
        }
    }

    fun initDanmakuContext(
        preferences: Preferences
    ) {
        val player = views.videoPlayer ?: return  // 播放器视图尚未就绪
        val danmakuMode = currentDanmakuMode().let {
            if (preferences[it.enable] == true) {
                it
            } else {
                SettingPreferences.DanmakuDefault
            }
        }
        val danmakuShow = (preferences[SettingPreferences.DanmakuEnable] ?: true) &&
                (preferences[danmakuMode.show] ?: true)
        player?.isShowDanmaku = danmakuShow

        // 滚动弹幕显示
        val danmakuR2LShow = preferences[danmakuMode.r2lShow] ?: true
        // 顶部弹幕显示
        val danmakuFTShow = preferences[danmakuMode.ftShow] ?: true
        // 底部弹幕显示
        val danmakuFBShow = preferences[danmakuMode.fbShow] ?: true
        // 高级弹幕显示
        val danmakuSpecialShow = preferences[danmakuMode.specialShow] ?: true
        // 字体大小
        var scaleTextSize = try {
            preferences[danmakuMode.fontSize] ?: 1f
        } catch (_: ClassCastException) {
            1f
        }
        // 弹幕速度
        val danmakuSpeed = try {
            preferences[danmakuMode.speed] ?: 1f
        } catch (_: ClassCastException) {
            1f
        }
        // 字体不透明度
        val danmakuOpacity = try {
            preferences[danmakuMode.opacity] ?: 1f
        } catch (_: ClassCastException) {
            1f
        }

        // 滚动弹幕最大行数
        val danmakuR2LMaxLine = preferences[danmakuMode.r2lMaxLine].let {
            if (it == null || it == 0) null else it
        }
        // 顶部弹幕最大行数
        val danmakuFTMaxLine = preferences[danmakuMode.ftMaxLine].let {
            if (it == null || it == 0) null else it
        }
        // 底部弹幕最大行数
        val danmakuFBMaxLine = preferences[danmakuMode.fbMaxLine].let {
            if (it == null || it == 0) null else it
        }
        // 设置最大显示行数
        val maxLinesPair = mapOf(
            BaseDanmaku.TYPE_SCROLL_RL to danmakuR2LMaxLine,
            BaseDanmaku.TYPE_FIX_TOP to danmakuFTMaxLine,
            BaseDanmaku.TYPE_FIX_BOTTOM to danmakuFBMaxLine,
        )

        //设置弹幕样式
        val ctx = danmakuContext
        if (ctx == null) {
            android.util.Log.w("PlayerController", "danmakuContext is null, skip danmaku config")
            return
        }
        ctx.apply {
            ftDanmakuVisibility = danmakuFTShow
            fbDanmakuVisibility = danmakuFBShow
            r2LDanmakuVisibility = danmakuR2LShow
            specialDanmakuVisibility = danmakuSpecialShow
            setScrollSpeedFactor(1 / danmakuSpeed)
            setScaleTextSize(scaleTextSize)
            setMaximumLines(maxLinesPair)
            setDanmakuTransparency(danmakuOpacity)

            // 弹幕过滤
            val filterEnabled = preferences[SettingPreferences.DanmakuFilterEnabled] ?: false
            if (filterEnabled) {
                // 关键词过滤
                val keywords = preferences[SettingPreferences.DanmakuFilterKeywords] ?: emptySet()
                danmakuTextFilter.setData(keywords)
                registerFilter(danmakuTextFilter)

                // 重复弹幕过滤
                if (preferences[SettingPreferences.DanmakuFilterDuplicate] == true) {
                    setDuplicateMergingEnabled(true)
                    mDanmakuFilters.get(DanmakuFilters.TAG_DUPLICATE_FILTER)
                } else {
                    setDuplicateMergingEnabled(false)
                    mDanmakuFilters.unregisterFilter(DanmakuFilters.TAG_DUPLICATE_FILTER)
                }
            } else {
                setDuplicateMergingEnabled(false)
                mDanmakuFilters.unregisterFilter(danmakuTextFilter)
                danmakuTextFilter.setData(emptySet())
                mDanmakuFilters.unregisterFilter(DanmakuFilters.TAG_DUPLICATE_FILTER)
            }
            mGlobalFlagValues.updateFilterFlag()
        }
    }

    private fun danmakuSwitchClick(view: View) {
        scope.launch {
            val danmakuMode = currentDanmakuMode()
            val isEnable = SettingPreferences.mapData(activity) {
                it[DanmakuEnable] ?: true
            }
            if (isEnable) {
                val show = !(player?.isShowDanmaku ?: false)
                player?.isShowDanmaku = show
                SettingPreferences.edit(activity) {
                    it[DanmakuDefault.show] = show
                    it[danmakuMode.show] = show
                }
            } else {
                PopTip.show("弹幕功能已关闭，请手动打开", "打开")
                    .showTop()
                    .setButton { _, _ ->
                        scope.launch {
                            SettingPreferences.edit(activity) {
                                it[DanmakuEnable] = true
                                it[DanmakuDefault.show] = true
                                it[danmakuMode.show] = true
                            }
                        }
                        player?.isShowDanmaku = true
                        false
                    }
            }
        }
    }

    fun initVideoSetting(preferences: Preferences) {
        val player = views.videoPlayer ?: return  // 播放器视图尚未就绪
        val show = SettingPreferences.run {
            preferences[PlayerBottomProgressBarShow] ?: 0
        }
        player?.showBottomProgressBarInSmallMode = (
            show and SettingConstants.PLAYER_BOTTOM_PROGRESS_BAR_SHOW_IN_SMALL != 0
        )
        player?.showBottomProgressBarInFullMode = (
            show and SettingConstants.PLAYER_BOTTOM_PROGRESS_BAR_SHOW_IN_FULL != 0
        )
        player?.showBottomProgressBarInPipMode = (
            show and SettingConstants.PLAYER_BOTTOM_PROGRESS_BAR_SHOW_IN_PIP != 0
        )
        showSubtitle = preferences[SettingPreferences.PlayerSubtitleShow] ?: true
        showAiSubtitle = preferences[SettingPreferences.PlayerAiSubtitleShow] ?: false
        isBackgroundPlay = preferences[SettingPreferences.PlayerBackground] ?: false
        isPipOnBackground = preferences[SettingPreferences.PlayerPipOnBackground] ?: false
    }

    /**
     * 播放器是否默认全屏播放（在 onPrepared 后调用，确保播放器已就绪）
     */
    fun checkIsPlayerDefaultFull() = scope.launch {
        val (openMode, fullMode) = SettingPreferences.mapData(activity)  {
            Pair(
                it[PlayerOpenMode] ?: SettingConstants.PLAYER_OPEN_MODE_DEFAULT,
                it[PlayerFullMode] ?: SettingConstants.PLAYER_FULL_MODE_AUTO,
            )
        }
        if (scaffoldApp.orientation == ScaffoldView.VERTICAL
            && openMode and SettingConstants.PLAYER_OPEN_MODE_AUTO_FULL_SCREEN != 0) {
            fullScreen(fullMode, onlyFull = true)
        } else if (scaffoldApp.orientation == ScaffoldView.HORIZONTAL
            && openMode and SettingConstants.PLAYER_OPEN_MODE_AUTO_FULL_SCREEN_LANDSCAPE != 0
        ){
            fullScreen(fullMode, onlyFull = true)
        }
    }

    /**
     * 重置自动全屏检查标志（新视频打开时调用）
     */
    fun resetAutoFullScreenCheck() {
        hasCheckedAutoFullScreen = false
    }

    fun showQualityPopupMenu(view: View) {
        val sourceInfo = delegate.playerSourceInfo ?: return
        val popup = QualityPopupMenu(
            activity = activity,
            anchor = view,
            userStore = userStore,
            list = sourceInfo.acceptList,
            value = delegate.quality,
            themeColor = player?.themeColor ?: 0,
        )
        popup.setOnChangedQualityListener(delegate::changedQuality)
        popup.show()
    }

    fun showSpeedPopupMenu(view: View) {
        scope.launch {
            val speedValueSets = SettingPreferences.mapData(activity) {
                it[PlayerSpeedValues] ?: SettingConstants.PLAYER_SPEED_SETS
            }
            val popup = SpeedPopupMenu(
                activity = activity,
                anchor = view,
                value = delegate.speed,
                list = speedValueSets.map { it.toFloat() }.sorted(),
                themeColor = player?.themeColor ?: 0,
            )
            popup.setOnChangedSpeedListener(delegate::changedSpeed)
            popup.show()
        }
    }

    fun showFullModeMenu(view: View) {
        val fullModeMenuItemClick = this::fullModeMenuItemClick
        scope.launch {
            val popupMenu = PopupMenu(ContextThemeWrapper(activity, com.a10miaomiao.bilimiao.R.style.Theme_Bilimiao), view)
            val fullMode = SettingPreferences.mapData(activity) {
                it[PlayerFullMode] ?: SettingConstants.PLAYER_FULL_MODE_AUTO
            }
            val checkMenuId = when (fullMode) {
                SettingConstants.PLAYER_FULL_MODE_SENSOR_LANDSCAPE -> R.id.full_mode_sl
                SettingConstants.PLAYER_FULL_MODE_LANDSCAPE -> R.id.full_mode_l
                SettingConstants.PLAYER_FULL_MODE_REVERSE_LANDSCAPE -> R.id.full_mode_rl
                SettingConstants.PLAYER_FULL_MODE_UNSPECIFIED -> R.id.full_mode_u
                SettingConstants.PLAYER_FULL_MODE_AUTO -> R.id.full_mode_auto
                else -> SettingConstants.PLAYER_FULL_MODE_AUTO
            }
            popupMenu.inflate(R.menu.player_full_mode)
            popupMenu.menu.findItem(checkMenuId).isChecked = true
            popupMenu.setOnMenuItemClickListener(fullModeMenuItemClick)
            popupMenu.show()
        }
    }

    private fun fullModeMenuItemClick(item: MenuItem): Boolean {
        item.isChecked = true
        val fullMode = when (item.itemId) {
            R.id.full_mode_sl -> SettingConstants.PLAYER_FULL_MODE_SENSOR_LANDSCAPE
            R.id.full_mode_l -> SettingConstants.PLAYER_FULL_MODE_LANDSCAPE
            R.id.full_mode_rl -> SettingConstants.PLAYER_FULL_MODE_REVERSE_LANDSCAPE
            R.id.full_mode_u -> SettingConstants.PLAYER_FULL_MODE_UNSPECIFIED
            R.id.full_mode_auto -> SettingConstants.PLAYER_FULL_MODE_AUTO
            else -> SettingConstants.PLAYER_FULL_MODE_AUTO
        }
        if (scaffoldApp.fullScreenPlayer) {
            fullScreen(fullMode)
        }
        scope.launch {
            SettingPreferences.edit(activity) {
                it[PlayerFullMode] = fullMode
            }
        }
        return true
    }

    fun showMoreMenu(view: View) {
        moreMenuAnchor = view
        val popupMenu = PopupMenu(ContextThemeWrapper(activity, com.a10miaomiao.bilimiao.R.style.Theme_Bilimiao), view)
        popupMenu.inflate(R.menu.player_top_more)
        popupMenu.setOnMenuItemClickListener(this::moreMenuItemClick)
        popupMenu.show()
    }

    fun showPagesOrEpisodes(view: View) {
        val playerSource = delegate.playerSource
        if (playerSource is VideoPlayerSource) {
            activity.openBottomSheet(VideoPagesPage(playerSource.aid))
        }
        if (playerSource is BangumiPlayerSource) {
            activity.openBottomSheet(BangumiEpisodesPage(
                sid = playerSource.sid,
                title = playerSource.ownerName,
            ))
        }

    }

    private fun showSendDanmakuPage(view: View) {
        if (!userStore.isLogin()) {
            toast("请先登录")
            return
        }
        if (delegate.isPlaying()) {
            player?.onVideoPause()
            player?.hideController()
        }
        activity.openBottomSheet(SendDanmakuPage())
    }

    fun holdUpPlayer(view: View) {
        if (player?.mode == DanmakuVideoPlayer.PlayerMode.SMALL_FLOAT) {
            explicitExitSmallWindow = true
        }
        scaffoldApp.holdUpPlayer()
    }

    private fun moreMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.mini_window -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val height = playerSourceInfo?.height
                    val width = playerSourceInfo?.width
                    // 设置宽高比例值
                    var aspectRatio = if (height == null || width == null) {
                        Rational(16, 9)
                    } else {
                        Rational(width, height)
                    }
                    try {
                        delegate.picInPicHelper?.enterPictureInPictureMode(aspectRatio)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        toast("此设备不支持小窗播放")
                    }
                } else {
                    toast("小窗播放功能需要安卓8.0及以上版本")
                }
            }

            R.id.video_setting -> {
                activity.openBottomSheet(VideoSettingPage())
            }

            R.id.danmuku_setting -> {
                val tabName = if (scaffoldApp.fullScreenPlayer){
                    SettingPreferences.DanmakuFullMode.name
                } else {
                    SettingPreferences.DanmakuSmallMode.name
                }
                activity.openBottomSheet(DanmakuDisplaySettingPage(tabName))
            }
            R.id.player_scale -> {
                val anchor = moreMenuAnchor ?: return@moreMenuItemClick true
                val popup = ScalePopupMenu(
                    activity = activity,
                    anchor = anchor,
                    value = GSYVideoType.getShowType(),
                    themeColor = player?.themeColor ?: 0,
                )
                popup.setOnChangedScaleListener { type ->
                    GSYVideoType.setShowType(type)
                    player?.updateTextureViewShowType()
                    scope.launch {
                        SettingPreferences.edit(activity) {
                            it[PlayerScreenType] = type
                        }
                    }
                }
                popup.show()
            }
        }
        return true
    }

    /**
     * 获取默认字幕
     */
    fun getDefaultSubtitle(
        list: List<DanmakuVideoPlayer.SubtitleSourceInfo>
    ): DanmakuVideoPlayer.SubtitleSourceInfo? {
        if (showSubtitle) {
            return list.find { showAiSubtitle || it.ai_status == 0 }
        }
        return null
    }

    /**
     * 创建弹幕
     * type: 1从右至左滚动弹幕|6从左至右滚动弹幕|5顶端固定弹幕|4底端固定弹幕|7高级弹幕|8脚本弹幕
     */
    fun createDanmaku(type: Int): BaseDanmaku {
        return danmakuContext.mDanmakuFactory.createDanmaku(type, danmakuContext)
    }

    fun onBackClick() {
        if (!scaffoldApp.fullScreenPlayer || onlyFull) {
            if (player?.mode == DanmakuVideoPlayer.PlayerMode.SMALL_FLOAT) {
                explicitExitSmallWindow = true
            }
            delegate.closePlayer()
        }
        smallScreen()
    }

    /**
     * 到准备完成后执行
     */
    fun postPrepared(id: String, action: Runnable) {
        preparedRunQueue.add(Pair(id, action))
    }

    /**
     * 准备完成
     */
    override fun onPrepared() {
        preparedRunQueue.forEach {
            val (id, action) = it
            if (id == delegate.playerSourceId) {
                player?.post(action)
            }
        }
        preparedRunQueue = mutableListOf()
        // 播放器准备完成后重新应用倍速
        player?.setSpeed(delegate.speed, true)
        // 自动全屏播放检查（仅首次预备）
        if (!hasCheckedAutoFullScreen) {
            hasCheckedAutoFullScreen = true
            checkIsPlayerDefaultFull()
        }
        // 视频章节
        fetchChapters()
    }

    /**
     * 播放结束
     */
    override fun onAutoCompletion() {
        delegate.historyReport(player?.currentPosition ?: 0L)
        scope.launch {
            val currentPlayerSourceInfo = delegate.playerSource ?: return@launch
            val nextPlayerSourceInfo = currentPlayerSourceInfo.next()
            val (order, orderRandom) = SettingPreferences.mapData(activity) {
                val order = it[PlayerOrder] ?: SettingConstants.PLAYER_ORDER_DEFAULT
                val orderRandom = it[PlayerOrderRandom] ?: false
                order to orderRandom
            }
            // 循环播放
            val isLoop = order and SettingConstants.PLAYER_ORDER_LOOP != 0
            val hasNextFlags = (order and (
                    SettingConstants.PLAYER_ORDER_NEXT_P or
                    SettingConstants.PLAYER_ORDER_NEXT_VIDEO or
                    SettingConstants.PLAYER_ORDER_NEXT_EPISODE)) != 0
            if (nextPlayerSourceInfo is VideoPlayerSource
                && order and SettingConstants.PLAYER_ORDER_NEXT_P != 0) {
                // 自动播放下一P
                delegate.openPlayer(nextPlayerSourceInfo)
                return@launch
            } else if (nextPlayerSourceInfo is BangumiPlayerSource
                && order and SettingConstants.PLAYER_ORDER_NEXT_EPISODE != 0
                && !orderRandom) {
                // 自动播放下一集（顺序模式；随机模式跳过 next() 走播单路径）
                delegate.openPlayer(nextPlayerSourceInfo)
                return@launch
            }
            // 播单自动连播：仅非单曲循环时走播单（单曲循环=LOOP但无任何next标志）
            val playListState = playListStore.stateFlow.value
            if (playListState.items.isNotEmpty() && !(isLoop && !hasNextFlags)) {
                val currentAid = (currentPlayerSourceInfo as? VideoPlayerSource)?.aid
                    ?: (currentPlayerSourceInfo as? BangumiPlayerSource)?.aid
                val currentIndex = if (currentAid != null) {
                    playListState.items.indexOfFirst { it.aid == currentAid }
                } else -1
                if (currentIndex >= 0) {
                    val listSize = playListState.items.size
                    val nextItem = if (orderRandom && listSize > 1) {
                        // 随机：取不等于当前项的随机项
                        var r = (0 until listSize).random()
                        while (r == currentIndex) r = (0 until listSize).random()
                        playListState.items[r]
                    } else if (currentIndex + 1 < listSize) {
                        playListState.items[currentIndex + 1]
                    } else if (isLoop) {
                        playListState.items[0]
                    } else {
                        null
                    }
                    if (nextItem != null && nextItem.aid != currentAid) {
                        val nextSource = if (nextItem.sid.isNotBlank() && nextItem.epid.isNotBlank()) {
                            nextItem.toBangumiPlayerSource().also { bs ->
                                bs.episodes = (currentPlayerSourceInfo as? BangumiPlayerSource)?.episodes ?: emptyList()
                            }
                        } else {
                            nextItem.toVideoPlayerSource()
                        }
                        delegate.openPlayer(nextSource)
                        return@launch
                    }
                    // 列表循环+单集：直接给播放器开循环，不重启避免闪烁
                    if (nextItem != null && nextItem.aid == currentAid && isLoop) {
                        currentPlayerSourceInfo.isLoop = true
                        player?.setLooping(true)
                        return@launch
                    }
                }
            }
            if (order and SettingConstants.PLAYER_ORDER_NEXT_VIDEO != 0) {
                // 自动下一个视频
                val nextVideo = playerStore.nextVideo(
                    orderRandom, isLoop
                )
                if (nextVideo != null) {
                    delegate.openPlayer(nextVideo.toVideoPlayerSource())
                    return@launch
                }
            }
            if (isLoop) {
                // 单个视频循环
                currentPlayerSourceInfo.isLoop = true
                delegate.openPlayer(currentPlayerSourceInfo)
            } else {
                // 小窗播放结束自动退出 PIP
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.isInPictureInPictureMode) {
                    activity.moveTaskToBack(true)
                }
                delegate.completionBoxController.show()
            }
        }
    }

    override fun onVideoPause() {
    }

    override fun onVideoResume(isResume: Boolean) {
        if (isResume) {
            // 🚫 DLNA_DISABLED
            // dlnaManager.startDiscovery()
        }
    }



    override fun setStateAndUi(state: Int) {
        delegate.picInPicHelper?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && it.isInPictureInPictureMode) {
                try {
                    it.updatePictureInPictureActions(state)
                } catch (e: Exception) {
                }
            }
        }
        if (state >= GSYVideoView.CURRENT_STATE_PAUSE) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // 播放状态变化（Media3 自动同步通知栏，无需手动 setPlaying）
    }

    override fun onVideoClose() {
        // 🚫 DLNA_DISABLED
        // dlnaManager.stopDiscovery()
        delegate.closePlayer()
    }

    override fun onClickUiToggle(e: MotionEvent?) {
        scaffoldApp.animatePlayerHeight(scaffoldApp.smallModePlayerMaxHeight)
    }

    private var lastRecordedPosition = 0L
    private var isTimerInitialized = false

    override fun onProgress(
        progress: Long,
        secProgress: Long,
        currentPosition: Long,
        duration: Long
    ) {
        delegate.historyReport(currentPosition)

        //定时关闭 - 使用真实时间代替视频时间（修复倍速<1时计时卡住/失效问题）
        val autoStopDuration = playerStore.autoStopDuration
        if (autoStopDuration > 0 && delegate.isPlaying()) {
            if (!isTimerInitialized) {
                lastRecordedPosition = System.currentTimeMillis()
                isTimerInitialized = true
                return
            }

            val now = System.currentTimeMillis()
            val passedTime = (now - lastRecordedPosition) / 1000L
            if (passedTime in 0L..5L) {
                var remainTimeNew = autoStopDuration - passedTime.toInt()
                if (remainTimeNew <= 0) {
                    // 时间被消耗完，暂停
                    remainTimeNew = 0
                    delegate.views.videoPlayer?.onVideoPause()
                    isTimerInitialized = false
                }
                playerStore.setAutoStopDuration(remainTimeNew)
                // 同步倒计时到UI
                delegate.views.videoPlayer?.updateAutoStopTimer(remainTimeNew)
            }
            lastRecordedPosition = now
        } else if (autoStopDuration == 0) {
            // 计时器被重置，隐藏UI
            delegate.views.videoPlayer?.updateAutoStopTimer(0)
            isTimerInitialized = false
        }
    }

    // ========== 视频章节 ==========

    private var chapterFetchJob: Job? = null

    private fun fetchChapters() {
        chapterFetchJob?.cancel()
        // 先隐藏旧章节，防止切换视频后残留
        player?.chapterManager?.hideChapters()
        chapterFetchJob = scope.launch(Dispatchers.IO) {
            try {
                val aid = playerStore.state.aid
                val cid = playerStore.state.cid
                // 下载视频时 playerStore 可能没有 aid/cid，从 playerSource 取
                val finalAid = aid.ifBlank {
                    delegate.playerSource?.getSourceIds()?.aid ?: return@launch
                }
                val finalCid = cid.ifBlank {
                    delegate.playerSource?.getSourceIds()?.cid ?: return@launch
                }
                if (finalAid.isBlank() || finalCid.isBlank()) return@launch

                val response = BiliApiService.playerAPI.getPlayerV2Info(finalAid, finalCid)
                    .apply {
                        headers["Referer"] = "https://www.bilibili.com/video/av$finalAid"
                        headers["User-Agent"] = "Mozilla/5.0"
                    }
                    .awaitCall()
                val jsonStr = response.body?.string() ?: ""
                val root = org.json.JSONObject(jsonStr)
                if (root.optInt("code", -1) != 0) return@launch

                val data = root.optJSONObject("data") ?: return@launch
                val viewPointsArr = data.optJSONArray("view_points") ?: return@launch

                // 解析 view_points，只取 type=2（章节类型）
                val chapterPoints = mutableListOf<org.json.JSONObject>()
                for (i in 0 until viewPointsArr.length()) {
                    val vp = viewPointsArr.getJSONObject(i)
                    if (vp.optInt("type", 0) == 2) {
                        chapterPoints.add(vp)
                    }
                }
                if (chapterPoints.size <= 1) return@launch

                val duration = playerSourceInfo?.duration ?: return@launch
                val durationSec = duration / 1000f
                if (durationSec <= 0) return@launch

                val chapters = chapterPoints.map { vp ->
                    val from = vp.optInt("from", 0)
                    val to = vp.optInt("to", 0)
                    ChapterInfo(
                        title = vp.optString("content", null),
                        startFraction = (from.toFloat() / durationSec).coerceIn(0f, 1f),
                        endFraction = ((if (to > 0) to else from + 1).toFloat() / durationSec).coerceIn(0f, 1f),
                        startMs = from * 1000L,
                        endMs = to * 1000L
                    )
                }

                withContext(Dispatchers.Main) {
                    player?.chapterManager?.setChapters(chapters) { startMs ->
                        player?.seekTo(startMs)
                    }
                }
            } catch (e: Exception) {
                miaoLogger().e("ChapterSegments", "获取章节异常", e)
                withContext(Dispatchers.Main) { player?.chapterManager?.hideChapters() }
            }
        }
    }

    // 🚫 DLNA_DISABLED — showCastDeviceList 暂时禁用
    /*
    private fun showCastDeviceList(anchor: View) {
        val devices = dlnaManager.devices
        if (devices.isEmpty()) {
            Toast.makeText(activity, "未发现投屏设备", Toast.LENGTH_SHORT).show()
            return
        }

        val popupMenu = PopupMenu(ContextThemeWrapper(activity, com.a10miaomiao.bilimiao.R.style.Theme_Bilimiao), anchor)
        devices.forEachIndexed { index, device ->
            popupMenu.menu.add(0, index, 0, device.name)
        }
        popupMenu.setOnMenuItemClickListener { item ->
            val device = devices[item.itemId]
            val videoUrl = player?.currentVideoUrl ?: ""
            val title = delegate.playerSource?.title ?: "视频"
            if (videoUrl.isNotEmpty()) {
                dlnaManager.castToDevice(device, videoUrl, title) { success, msg ->
                    scope.launch(Dispatchers.Main) {
                        if (success) {
                            player?.onVideoPause() // 暂停本地播放
                        }
                        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(activity, "无法获取视频地址", Toast.LENGTH_SHORT).show()
            }
            true
        }
        popupMenu.show()
    }
    */
}