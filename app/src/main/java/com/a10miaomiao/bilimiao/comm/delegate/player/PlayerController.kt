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
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorCategory
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorSkipType
import com.a10miaomiao.bilimiao.comm.delegate.helper.StatusBarHelper
import com.a10miaomiao.bilimiao.comm.dialogx.showTop
import com.a10miaomiao.bilimiao.comm.navigation.openBottomSheet
import com.a10miaomiao.bilimiao.comm.store.AppStore
import com.a10miaomiao.bilimiao.comm.store.PlayListStore
import com.a10miaomiao.bilimiao.comm.store.PlayerStore
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.service.PlaybackService
import com.a10miaomiao.bilimiao.widget.player.media3.Media3ExoPlayerManager
import com.a10miaomiao.bilimiao.widget.player.ChapterInfo
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.comm.delegate.player.PlayerSeekBus
import com.a10miaomiao.bilimiao.widget.player.DanmakuTextFilter
import com.a10miaomiao.bilimiao.widget.player.DanmakuVideoPlayer
import com.a10miaomiao.bilimiao.widget.player.DlnaManager
import com.a10miaomiao.bilimiao.widget.player.DlnaDevice
import com.a10miaomiao.bilimiao.widget.player.VideoPlayerCallBack
import com.a10miaomiao.bilimiao.widget.player.SponsorBlockUi
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
    private val activity: AppCompatActivity,
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
    // null = 还没从设置里读过（首次读取前按"显示"处理，见 getDefaultSubtitle）。
    // 之前写死 false 初值，一旦"设置还没读出来就先加载完字幕列表"，就会误判成"用户不要字幕"。
    private var showSubtitle: Boolean? = null // 字幕显示
    private var showAiSubtitle: Boolean? = null // AI字幕显示
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
        // ⋮ 按钮已去掉：播放设置/画面比例都搬到了播放器按钮上（空降两项在顶栏有图标）
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
        // 初始化弹幕过滤器，避免首次启动时因协程延迟导致过滤器未注册。
        // ★ 设置值优先走内存快照（进程启动时后台维护，见 SettingPreferences.warmUpCache）：
        //   这是主线程路径，以前无条件 runBlocking 读 DataStore（500ms 超时）——每次进播放页都阻塞。
        //   只有"快照还没就绪"（进程刚起就直接进播放页）才退回一次带超时的阻塞读兜底。
        val cachedSetting = SettingPreferences.cachedPreferencesOrNull()
        if (cachedSetting != null) {
            initDanmakuContext(cachedSetting)
        } else {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(500L) {
                    SettingPreferences.getData(activity) {
                        initDanmakuContext(it)
                    }
                }
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
        // ★ 顺序很重要：先落 fullScreenPlayer，再按**宿主窗口实时尺寸**重算 mode。
        //   以前是"先写 mode，再读 resources.configuration"，而紧接着设置的
        //   requestedOrientation=UNSPECIFIED 要等下一次配置回调才生效 —— 此刻 config 还是旧方向，
        //   于是竖屏退出全屏会被算成横屏 → mode 钉成 SMALL_FLOAT → 竖屏下出现横屏形态的控件
        //   （小白条、✕ 图标），就是用户报的那个 bug。
        scaffoldApp.fullScreenPlayer = false
        updatePlayerMode(null)
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

    /**
     * 重算播放器模式：全屏 / 竖屏小窗（SMALL_TOP）/ 横屏浮动小窗（SMALL_FLOAT）。
     *
     * ★ 方向一律以**宿主窗口的实时宽高**为准（decorView），`resources.configuration` 只作兜底：
     *   本 Activity 声明了 configChanges、旋转不重建，而 `requestedOrientation` 的改动要等下一次
     *   配置回调才反映到 config —— 在"刚退出全屏 / 刚切方向"的那个窗口里，config 是**过期值**。
     *   `config` 参数只服务于 onConfigurationChanged 调用方（拿不到窗口尺寸时的兜底）。
     *
     * ★ 去掉了"mode == FULL 就早退"：那会让**复用播放器 View**（MainUi.keepPlayerView）时残留的
     *   FULL 模式永远降不下来（竖屏了还按全屏排版）。现在统一按 `fullScreenPlayer` 推导。
     */
    fun updatePlayerMode(config: Configuration? = null) {
        // 画中画：窗口方向 ≠ 设备方向，别按它推导（进出 PiP 由 PlayerDelegate2 负责恢复）
        if (player?.isPicInPicMode == true) return
        applyPlayerMode(hostIsLandscape(config))
    }

    /** 宿主窗口**真实尺寸**变化（ScaffoldView.onSizeChanged）→ 重算模式；尺寸是方向的最终真源 */
    fun onHostSizeChanged(width: Int, height: Int) {
        if (player?.isPicInPicMode == true) return
        if (width <= 0 || height <= 0) return
        applyPlayerMode(width > height)
    }

    private fun hostIsLandscape(fallback: Configuration? = null): Boolean {
        val decor = activity.window?.decorView
        val w = decor?.width ?: 0
        val h = decor?.height ?: 0
        if (w > 0 && h > 0) return w > h
        val orientation = fallback?.orientation ?: activity.resources.configuration.orientation
        return orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun applyPlayerMode(landscape: Boolean) {
        player?.mode = when {
            scaffoldApp.fullScreenPlayer -> DanmakuVideoPlayer.PlayerMode.FULL
            !landscape -> DanmakuVideoPlayer.PlayerMode.SMALL_TOP
            else -> DanmakuVideoPlayer.PlayerMode.SMALL_FLOAT
        }
        // 同步布局方向给播放器，用于横屏非全屏禁用双指手势
        player?.isLandscapeLayout = landscape
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
            miaoLogger().d("danmakuContext is null, skip danmaku config")
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
        // ★ DASH 缓冲秒数：这个设置以前**只声明、没人读** —— setDashBufferSec() 全工程无人调用，
        //   设置页里那个下拉也没渲染出来，所以用户"改成 50 秒"其实一点效果都没有（实测反馈）。
        //   现在接上：0 = ExoPlayer 默认(50s)，其它 = 指定秒数（内部 clamp 5~120）。
        //   放在 player 空判断之前：它只是给播放器工厂设参数，不需要播放器实例已经就绪。
        Media3ExoPlayerManager.setDashBufferSec(
            preferences[SettingPreferences.PlayerDashBufferSec] ?: 15
        )
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
        val newShowSubtitle = preferences[SettingPreferences.PlayerSubtitleShow] ?: true
        val newShowAiSubtitle = preferences[SettingPreferences.PlayerAiSubtitleShow] ?: false
        // ★ 播放中改「字幕显示 / AI字幕显示」要立即生效：
        //   这两个开关原来只在"设置读出来的那一刻"记进字段，没有任何人重新挑轨道，
        //   所以正在播的视频要等下次换清晰度/重开播放器才变 —— 用户感受就是"开了不显示、关了不消失"。
        //   这里检测到变化就按新设置重挑一次轨道（关 → null → 立即隐藏；开 → 立即走加载/缓存）。
        val subtitleSettingChanged =
            newShowSubtitle != showSubtitle || newShowAiSubtitle != showAiSubtitle
        showSubtitle = newShowSubtitle
        showAiSubtitle = newShowAiSubtitle
        if (subtitleSettingChanged) {
            // 只有"选出来的轨道确实变了"才重新赋值：否则设置流每次发射都会重挑同一轨，
            // 白白把已经加载/正在加载的字幕请求打断重来
            val target = getDefaultSubtitle(player.subtitleSourceList)
            if (target?.subtitle_url != player.currentSubtitleSource?.subtitle_url) {
                player.currentSubtitleSource = target
            }
        }
        // 拖动进度条预览图（默认开）。关掉后连数据都不再拉，省流量
        player.showSeekPreview = preferences[SettingPreferences.PlayerSeekPreviewShow] ?: true
        // 空降助手：总开关 + 每类别策略（默认档 = 11 个类别全部"跳过一次"，见 DEFAULT_SKIP_TYPES）
        // ★ 默认**开**（这里是故意与 PiliPlus 不同：用户要开箱即用；PiliPlus 默认关是隐私考虑）
        val sponsorEnabled = preferences[SettingPreferences.SponsorBlockEnable] ?: true
        val sponsorWasEnabled = player.sponsorSkipEnabled
        player.sponsorSkipEnabled = sponsorEnabled
        player.sponsorSkipTypes = SponsorCategory.entries.associate { category ->
            val ordinal = preferences[SettingPreferences.sponsorBlockSkipTypeKey(category.id)]
            category.id to if (ordinal == null) {
                SponsorCategory.DEFAULT_SKIP_TYPES[category.id] ?: SponsorSkipType.Disable
            } else {
                SponsorSkipType.of(ordinal)
            }
        }
        // 最短片段时长 / 跳过提示 / 上报（对齐 PiliPlus 的 blockLimit、blockToast、blockTrack）
        player.sponsorLimitSec = preferences[SettingPreferences.SponsorBlockLimit] ?: 0
        // 自定义服务端（镜像站）——直接写给 API 单例，它每个请求现取
        com.a10miaomiao.bilimiao.comm.apis.SponsorBlockApi.serverOverride =
            preferences[SettingPreferences.SponsorBlockServer]
        // 自定义色块颜色（缺省就是类别默认色）
        player.sponsorColors = SponsorCategory.entries.mapNotNull { category ->
            preferences[SettingPreferences.sponsorBlockColorKey(category.id)]?.let { category.id to it }
        }.toMap()
        player.sponsorToastEnabled = preferences[SettingPreferences.SponsorBlockToast] ?: true
        player.sponsorTrackEnabled = preferences[SettingPreferences.SponsorBlockTrack] ?: true
        // 播放中途打开开关：立刻补一次片段请求，否则要等下一个视频才生效
        if (sponsorEnabled && !sponsorWasEnabled) {
            delegate.playerSource?.let { delegate.loadSponsorSegments(it) }
        }
        // 字幕字号（sp）：设置页允许手输，这里兜底夹到合理区间（12~30），
        // 避免误输 0 把字幕弄没、或 999 糊满屏
        player.subtitleTextSizeSp =
            (preferences[SettingPreferences.PlayerSubtitleTextSize] ?: 16)
                .coerceIn(12, 30)
                .toFloat()
        player.longPressSpeedMultiplier =
            (preferences[SettingPreferences.PlayerLongPressSpeed] ?: 300) / 100f
        // 快进/快退步长：0（或没设置）= 关闭 —— 双击屏幕不做快进/快退（默认就是关闭）
        val seekStepSec = preferences[SettingPreferences.PlayerDoubleTapSeek] ?: 0
        player.doubleTapSeekMs = if (seekStepSec > 0) seekStepSec * 1000L else 0L
        // 通知栏 ±秒按钮、蓝牙线控、章节退化跳转：跟随步长；关闭时用默认 10 秒（用户要求）
        delegate.seekStepMs = (if (seekStepSec > 0) seekStepSec else 10) * 1000L
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
                else -> R.id.full_mode_auto
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
            activity.openBottomSheet(VideoPagesPage(playerSource.aid, playerSource.effectiveBvid))
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
        // 暂停与"关掉后要不要恢复播放"都由委托层统一管理（记住打开前是否在播放）
        delegate.openDanmakuEditor()
        activity.openBottomSheet(SendDanmakuPage())
    }

    fun holdUpPlayer(view: View) {
        if (player?.mode == DanmakuVideoPlayer.PlayerMode.SMALL_FLOAT) {
            explicitExitSmallWindow = true
        }
        scaffoldApp.holdUpPlayer()
    }

    /** 打开播放设置（顶栏齿轮按钮与「更多」菜单共用） */
    fun openVideoSetting() {
        activity.openBottomSheet(VideoSettingPage())
    }

    /** 弹出「画面比例」选择（底栏按钮与「更多」菜单共用；anchor 决定弹窗位置） */
    fun openScreenScale(anchor: View) {
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

    /**
     * 进小窗（画中画）。
     *
     * 顶栏那个「小窗播放」图标按钮和「更多」菜单里的小窗播放都走这里，逻辑只留一份。
     *
     * ★这是**手动入口**：用户点一下就**立即**进小窗，不需要先退到桌面
     *   （`Activity.enterPictureInPictureMode()` 在前台调用即生效，见 PicInPicHelper）。
     *   退后台自动进 PiP 是另一条路（[PlayerDelegate2.tryEnterPipOnBackground]，受设置「后台小窗播放」门控），
     *   两条路最终共用同一份 PiP 参数。
     */
    fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 播放器没开着就别进：没有内容的 PiP 窗口是纯黑一块，用户会以为播放器坏了。
            // （按钮本身在播放器 UI 里，正常点不到，这里只防状态竞态）
            if (!delegate.isOpened()) {
                toast("播放器还没准备好")
                return
            }
            val height = playerSourceInfo?.height
            val width = playerSourceInfo?.width
            // 设置宽高比例值
            val aspectRatio = if (height == null || width == null || height <= 0 || width <= 0) {
                // 宽高缺失或接口返回 0 时兜底：Rational 分母为 0 会抛 IllegalArgumentException
                Rational(16, 9)
            } else {
                Rational(width, height)
            }
            try {
                // 返回值 = 系统有没有接受这次请求。false 时原来是一声不吭，
                // 用户只会觉得"点了没反应"，这里如实反馈（进 PiP 失败最常见的原因：
                // 系统设置/ROM 关了本应用的画中画权限，或设备正处于不允许 PiP 的多窗口状态）
                val entered = delegate.picInPicHelper?.enterPictureInPictureMode(aspectRatio) == true
                if (!entered) {
                    miaoLogger() debug "enterPip 被系统拒绝：sdk=${Build.VERSION.SDK_INT} ratio=$aspectRatio"
                    toast("系统未允许进入小窗播放")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                toast("此设备不支持小窗播放")
            }
        } else {
            toast("小窗播放功能需要安卓8.0及以上版本")
        }
    }

    /**
     * 打开弹幕显示设置。
     *
     * 底栏那个「弹幕设置」按钮和（原来的）「更多」菜单都走这里；全屏/小窗分别对应两套弹幕设置。
     */
    fun openDanmakuSetting() {
        val tabName = if (scaffoldApp.fullScreenPlayer) {
            SettingPreferences.DanmakuFullMode.name
        } else {
            SettingPreferences.DanmakuSmallMode.name
        }
        activity.openBottomSheet(DanmakuDisplaySettingPage(tabName))
    }

    private fun moreMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sponsor_block_detail -> {
                val p = views.videoPlayer
                if (p == null) {
                    toast("播放器还没准备好")
                } else {
                    SponsorBlockUi.showSegments(activity, p)
                }
                return true
            }
            R.id.sponsor_block_submit -> {
                val p = views.videoPlayer
                if (p == null) {
                    toast("播放器还没准备好")
                } else if (p.sponsorVideoId.isBlank()) {
                    toast("这个视频不支持提交片段（番剧/本地视频没有 BVID）")
                } else {
                    SponsorBlockUi.showSubmit(activity, p, p.sponsorVideoId, p.sponsorCid)
                }
                return true
            }
            R.id.video_setting -> {
                openVideoSetting()
            }


            R.id.player_scale -> {
                val anchor = moreMenuAnchor ?: return@moreMenuItemClick true
                openScreenScale(anchor)
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
        // showSubtitle == null（设置还没读出来）也按"显示"处理，避免开局把字幕吞掉
        if (showSubtitle != false) {
            return list.find { showAiSubtitle == true || it.ai_status == 0 }
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
        // 全屏时按返回一律"先退出全屏、继续播放"，关播放器留给小窗的 ✕。
        // 原来在自动全屏(onlyFull，比如设置里开了自动转屏全屏)时按返回会直接把播放器关掉，
        // 和手动全屏的行为不一致
        if (scaffoldApp.fullScreenPlayer && !onlyFull) {
            smallScreen()
            return
        }
        if (player?.mode == DanmakuVideoPlayer.PlayerMode.SMALL_FLOAT) {
            explicitExitSmallWindow = true
        }
        delegate.closePlayer()
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
            // ★ 防御1：未开循环时，若 next() 返回的就是当前视频，视为无下一项，直接完成。
            // 防止 onAutoCompletion → openPlayer(同视频) → STATE_ENDED → 死循环。
            val nextIsSameAsCurrent = nextPlayerSourceInfo != null
                && nextPlayerSourceInfo!!.id == currentPlayerSourceInfo.id
            if (nextIsSameAsCurrent && !isLoop) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.isInPictureInPictureMode) {
                    activity.moveTaskToBack(true)
                }
                delegate.completionBoxController.show()
                return@launch
            }
            if (nextPlayerSourceInfo is VideoPlayerSource
                && order and SettingConstants.PLAYER_ORDER_NEXT_P != 0) {
                // 自动播放下一P
                delegate.openPlayer(nextPlayerSourceInfo!!)
                return@launch
            } else if (nextPlayerSourceInfo is BangumiPlayerSource
                && order and SettingConstants.PLAYER_ORDER_NEXT_EPISODE != 0
                && !orderRandom) {
                // 自动播放下一集（顺序模式；随机模式跳过 next() 走播单路径）
                delegate.openPlayer(nextPlayerSourceInfo!!)
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
                // ★ 防御2：nextVideo 返回当前视频且未开循环时，不 openPlayer，走完成。
                if (nextVideo != null && nextVideo!!.aid == (currentPlayerSourceInfo as? VideoPlayerSource)?.aid && !isLoop) {
                    // same video, not looping → skip
                } else if (nextVideo != null) {
                    delegate.openPlayer(nextVideo!!.toVideoPlayerSource())
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
        // 暂停不计入"定时关闭"：作废计时基准，恢复播放后重新起算
        isTimerInitialized = false
        // 立刻落一次位置：用户常见操作是"暂停 → 切桌面/微信 → 进程被杀"，
        // 只靠每 5 秒一次的写入会丢掉最后这段，回来就只能从 0 播
        delegate.savePlaybackPositionNow()
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
                    miaoLogger() error "updatePictureInPictureActions failed: ${e.message}"
                }
            }
        }
        if (state >= GSYVideoView.CURRENT_STATE_PAUSE) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
        if (autoStopDuration > 0) {
            if (!delegate.isPlaying()) {
                // 暂停中不计时（进度回调可能还在跑）：只作废基准，恢复播放后重新起算，
                // 这样暂停多久都不会被算进"定时关闭"
                isTimerInitialized = false
            } else if (!isTimerInitialized) {
                lastRecordedPosition = System.currentTimeMillis()
                isTimerInitialized = true
            } else {
                val now = System.currentTimeMillis()
                // 按真实流逝时间扣减：切后台/息屏/主线程卡顿后回来要一次扣够。
                // 原来只在 passedTime∈0..5 时才扣，超过 5 秒的间隔被整段丢掉
                // → 倒计时越走越慢（甚至永远不关）
                val passedTime = ((now - lastRecordedPosition) / 1000L).toInt()
                if (passedTime > 0) {
                    var remainTimeNew = autoStopDuration - passedTime
                    if (remainTimeNew <= 0) {
                        // 时间被消耗完，暂停
                        remainTimeNew = 0
                        delegate.views.videoPlayer?.onVideoPause()
                        isTimerInitialized = false
                        // 到点静默暂停、倒计时文字直接消失，用户不知道发生了什么
                        toast("定时关闭时间到，已暂停")
                    }
                    playerStore.setAutoStopDuration(remainTimeNew)
                    // 同步倒计时到UI
                    delegate.views.videoPlayer?.updateAutoStopTimer(remainTimeNew)
                }
                lastRecordedPosition = now
            }
        } else if (autoStopDuration == 0) {
            // 计时器被重置，隐藏UI
            delegate.views.videoPlayer?.updateAutoStopTimer(0)
            isTimerInitialized = false
        }
    }

    // ========== 视频章节 ==========

    /** 当前视频章节数据，供通知栏/蓝牙章节控制使用 */
    var currentChapters: List<ChapterInfo> = emptyList()
        private set

    private var chapterFetchJob: Job? = null

    /**
     * Activity 重建后恢复章节数据。
     *
     * 章节网络请求只在 onPrepared 触发，重建后不会重来；而章节数据还留在复用的
     * 播放器 View 的 ChapterManager 里 → 由 PlayerDelegate2 取回后灌进来，
     * 并刷新通知栏（章节按钮的有无取决于这里）。
     */
    fun restoreChapters(chapters: List<ChapterInfo>) {
        currentChapters = chapters
        PlaybackService.instance?.refreshNotification()
    }

    private fun fetchChapters() {
        chapterFetchJob?.cancel()
        // 先隐藏旧章节，防止切换视频后残留
        player?.chapterManager?.hideChapters()
        currentChapters = emptyList()
        PlaybackService.instance?.refreshNotification()
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
                }.sortedBy { it.startMs }

                withContext(Dispatchers.Main) {
                    currentChapters = chapters
                    player?.chapterManager?.setChapters(chapters) { startMs ->
                        player?.seekTo(startMs)
                    }
                    // 通知栏按钮需要根据是否有章节刷新
                    PlaybackService.instance?.refreshNotification()
                }
            } catch (e: Exception) {
                miaoLogger().e("ChapterSegments", "获取章节异常", e)
                withContext(Dispatchers.Main) {
                    currentChapters = emptyList()
                    player?.chapterManager?.hideChapters()
                    PlaybackService.instance?.refreshNotification()
                }
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