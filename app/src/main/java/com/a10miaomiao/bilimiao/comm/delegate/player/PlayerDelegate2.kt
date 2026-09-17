package com.a10miaomiao.bilimiao.comm.delegate.player

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.a10miaomiao.bilimiao.comm.utils.SponsorDiag
import com.a10miaomiao.bilimiao.MainUi
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Rational
import android.view.DisplayCutout
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.registerReceiver
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.delegate.helper.PicInPicHelper
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.PlayerSourceIds
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.PlayerSourceInfo
import com.a10miaomiao.bilimiao.comm.delegate.theme.ThemeDelegate
import com.a10miaomiao.bilimiao.comm.dialogx.showTop
import com.a10miaomiao.bilimiao.comm.entity.player.SubtitleJsonInfo
import com.a10miaomiao.bilimiao.comm.exception.AreaLimitException
import com.a10miaomiao.bilimiao.comm.exception.DabianException
import com.a10miaomiao.bilimiao.comm.network
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.player.BilimiaoPlayerManager
import com.a10miaomiao.bilimiao.comm.proxy.ProxyServerInfo
import com.a10miaomiao.bilimiao.comm.store.PlayerStore
import com.a10miaomiao.bilimiao.comm.store.PlayListStore
import com.a10miaomiao.bilimiao.comm.store.UserLibraryStore
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.comm.utils.CdnHosts
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.config.config
import com.a10miaomiao.bilimiao.service.PlaybackService
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.widget.player.ChapterNavigator
import com.a10miaomiao.bilimiao.widget.player.DanmakuVideoPlayer
import com.a10miaomiao.bilimiao.widget.player.SponsorBlockUi
import com.a10miaomiao.bilimiao.widget.player.media3.ExoMediaSourceInterceptListener
import com.a10miaomiao.bilimiao.widget.player.media3.ExoSourceManager
import com.a10miaomiao.bilimiao.widget.scaffold.getScaffoldView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.a10miaomiao.bilimiao.comm.toast
import com.kongzue.dialogx.dialogs.PopTip
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import com.shuyu.gsyvideoplayer.video.base.GSYVideoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.File
import java.net.UnknownHostException


class PlayerDelegate2(
    private val activity: AppCompatActivity,
    override val di: DI,
) : BasePlayerDelegate, DIAware, ExoMediaSourceInterceptListener {

    companion object {
        private fun getCacheMaxSize(context: Context): Long {
            return try {
                val sizeMb = kotlinx.coroutines.runBlocking {
                    // 这条链路会被主线程的 ExoSourceManager.getMediaSource 调到：
                    // DataStore 首读异常时会无限阻塞主线程，加 500ms 超时兜底（与 ThemeDelegate 一致）
                    kotlinx.coroutines.withTimeoutOrNull(500L) {
                        SettingPreferences.mapData(context) { prefs ->
                            (prefs[SettingPreferences.PlayerDiskCacheSize] ?: 512).coerceIn(100, 10240)
                        }
                    }
                } ?: 512 // 超时/异常 → 用默认值 512MB
                sizeMb * 1024L * 1024L
            } catch (e: Exception) {
                1024L * 1024 * 1024 // fallback
            }
        }

        @Volatile
        private var videoCache: SimpleCache? = null

        /**
         * 进程级"当前播放源"缓存。
         *
         * MainUi.keepPlayerView 跨 Activity 复用同一个播放器 View（ExoPlayer 也活在
         * GSYVideoManager 单例里），画面能继续放；但 Activity 重建后新建的 PlayerDelegate2
         * 是空的 —— playerSource/playerSourceInfo 均为 null，导致通知栏/蓝牙控制、
         * 章节跳转、进度保存、播放源相关 UI 全部失效。
         * 这里在 openPlayer/loadPlayerSource 时缓存，重建时由新 delegate 取回；
         * 用户主动关闭播放器（closePlayer）时清空。
         */
        @Volatile
        private var keptSource: BasePlayerSource? = null

        @Volatile
        private var keptSourceInfo: PlayerSourceInfo? = null

        /**
         * 切后台前是否"正在播放"（即被 onStop 自动暂停）。
         * onStart 时 GSY 状态一律是 PAUSE（不管是自动暂停还是用户手动暂停），
         * 只能靠这里记录区分；GSY 的 isInPlayingState() 对 PAUSE(5) 也返回 true，
         * 直接用它判断会让"手动暂停"的视频回到前台后被 onVideoResume() 续播。
         * 静态保存的原因同 keepPlayerView：Activity 重建后新 delegate 也要能续播。
         */
        @Volatile
        private var pausedByBackground = false

        private fun getCache(context: Context): SimpleCache {
            // 用 applicationContext：StandaloneDatabaseProvider 会被静态 videoCache 持有，
            // 传 Activity 会在进程存活期间 pin 住首个 Activity 实例
            val appContext = context.applicationContext
            return videoCache ?: synchronized(this) {
                videoCache ?: SimpleCache(
                    File(appContext.externalCacheDir ?: appContext.cacheDir, "video_cache"),
                    LeastRecentlyUsedCacheEvictor(getCacheMaxSize(appContext)),
                    StandaloneDatabaseProvider(appContext)
                ).also { videoCache = it }
            }
        }

        private fun createCachedFactory(
            context: Context,
            userAgent: String,
            header: Map<String, String>,
        ): DataSource.Factory {
            val httpFactory = DefaultHttpDataSource.Factory()
            httpFactory.setUserAgent(userAgent)
            httpFactory.setDefaultRequestProperties(header)
            val cache = getCache(context)
            // 自定义 CacheKeyFactory：只取 path 作为缓存 key，跨 CDN 节点互通
            // Bilibili 同个视频在不同 API 调用中可能返回不同的 CDN host，
            // 若不统一 key，则每次重建播放器都会因 URL host 变化而缓存不命中
            val cacheKeyFactory = CacheKeyFactory { dataSpec ->
                dataSpec.uri.path ?: dataSpec.uri.buildUpon().clearQuery().build().toString()
            }
            // cacheReadDataSourceFactory 使用 DefaultDataSource 支持本地文件读取，
            // 确保已缓存的内容直接从本地存储读取而不走网络
            val cacheReadFactory = DefaultDataSource.Factory(context, httpFactory)
            return CacheDataSource.Factory()
                .setCache(cache)
                .setCacheKeyFactory(cacheKeyFactory)
                .setCacheReadDataSourceFactory(cacheReadFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setUpstreamDataSourceFactory(httpFactory)
        }

        /**
         * 当候选 URL >1 时，包装 CacheDataSource.Factory 为 CdnFailoverDataSourceFactory，
         * 播放中某个 CDN 打开失败自动切下一个。单候选时原样返回。
         */
        private fun wrapWithFailover(
            upstream: DataSource.Factory,
            candidates: List<String>,
        ): DataSource.Factory {
            if (candidates.size <= 1) return upstream
            val uris = candidates.map { Uri.parse(it) }
            val state = CdnFailoverState(uris)
            return CdnFailoverDataSourceFactory(upstream, state)
        }
    }

    val DEFAULT_REFERER = "https://www.bilibili.com/"
    val DEFAULT_USER_AGENT = "Bilibili Freedoooooom/MarkII"

    val views by lazy { PlayerViews(activity) }
    private val player: DanmakuVideoPlayer? get() = views.videoPlayer
    val controller by lazy {
        PlayerController(activity, this, playerCoroutineScope, di)
    }
    val errorMessageBoxController by lazy {
        ErrorMessageBoxController(activity, this, di)
    }
    val areaLimitBoxController by lazy {
        AreaLimitBoxController(activity, this, di)
    }
    val completionBoxController by lazy {
        CompletionBoxController(activity, this, di)
    }
    val loadingBoxController by lazy {
        LoadingBoxController(activity, this)
    }
    val scaffoldApp by lazy { activity.getScaffoldView() }

    var picInPicHelper: PicInPicHelper? = null
        private set

    private val userStore by instance<UserStore>()
    private val playerStore by instance<PlayerStore>()
    private val playListStore by instance<PlayListStore>()
    private val userLibraryStore by instance<UserLibraryStore>()
    private val windowStore by instance<WindowStore>()
    private val themeDelegate by instance<ThemeDelegate>()

    private var themeObserver: Observer<Int>? = null
    private var isBroadcastReceiverRegistered = false

    /** 与服务之间的 MediaController 连接（持有 Activity context，需显式释放） */
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null

    var playerSourceInfo: PlayerSourceInfo? = null

// TODO AI 原声翻译：暂时关闭（切到 AI 音轨后播放器进 ERROR/黑屏）。恢复时把这段注释放开。
//     /** AI 原声翻译：本次播放选中的语言（null = 原声）。换视频时复位 */
//     private var playerLanguage: String? = null
//
//     /** AI 原声翻译可选语言（HTTP playurl 的 language.items；gRPC 取流拿不到，需单独补一次） */
//     private var availableLanguages: List<PlayerSourceInfo.LanguageInfo> = emptyList()

    // 未登陆：48[480P 清晰]及以下
    // 已登陆无大会员：80[1080P 高清]及以下
    // 大会员：无限制
    val MAX_QUALITY_NOT_LOGIN = 48 // 48[480P 清晰]
    val MAX_QUALITY_NOT_VIP = 80 // 80[1080P 高清]
    var quality = 64 // 默认[高清 720P]
    var fnval = 4048 // 视频格式: 0:flv,1:mp4,4048:dash

    var speed = 1f // 播放速度
    private var showNotification = false // 通知栏控制器开关
    private var lastPosition = 0L
    private val playerCoroutineScope = PlayerCoroutineScope()
    private var playerClosed = false

    private var lastReportProgress = 0L // 最后记录的播放位置

    /**
     * 上一次真正装进播放器的视频 id（cid）。
     * 用途：区分"换视频"和"同一个视频重载（换清晰度/换语言/网络重试）"——
     * 前者要把播放器里的续播账本清掉，后者必须留着，否则重载是从 0 开始。
     */
    private var lastLoadedSourceId: String? = null

    /**
     * 这次打开视频，**云端来源**的续播位置（0 = 不弹提示）。
     *
     * 用户规则：只有"位置是云端给的"才弹 `自动恢复:xx:xx / 重新开始`；
     * 本机来源的一律不弹 —— 包括 PlaybackService 会话内存、`dl_aid_cid` 持久化记录
     * （"返回桌面再进软件"就属于这一类）。
     * 云端来源目前就是**预设**（历史页的云端进度 / 番剧的"继续观看"进度）和
     * `loadPlayerSource` 里 playurl 返回的 `last_play_time`。
     */
    private var pendingCloudResumeMs = 0L

    /** 快进/快退步长（毫秒）：通知栏按钮、蓝牙线控、章节退化跳转都用它；由 PlayerController 从设置下发 */
    var seekStepMs = 10_000L

    override fun releasePlayback() {
        try {
            savePlaybackPosition()
            playerClosed = true
            playerCoroutineScope.onDestroy()
            views.videoPlayer?.releaseDanmaku()
            views.videoPlayer?.hideExpandButton()
            // 真正停掉声音：GSY 的 release 会 stop 掉底层播放器
            views.videoPlayer?.release()
            MainUi.clearKeepPlayerView()
            releaseMediaControllerFuture()
        } catch (e: Exception) {
            miaoLogger() error "releasePlayback failed: ${e.message}"
        }
    }

    override fun savePlaybackPositionNow() {
        savePlaybackPosition()
    }
    private var lastBackPressedTime = 0L

    var playerSource: BasePlayerSource? = null
        private set(value) {
            field = value
            if (value != null) {
                playerStore.setPlayerSource(value)
            } else {
                playerStore.clearPlayerInfo()
            }
        }
    val playerSourceId get() = playerSource?.id ?: ""

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // 耳机检测
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    //暂停播放
                    if (isPlaying())
                        views.videoPlayer?.onVideoPause()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        playerCoroutineScope.onCreate()
        initPlayer()
        // 总是注册广播接收器 / 主题监听，不依赖 videoPlayer View
        registerAudioReceiver()
        ensureThemeObserver()
        // player View 可能尚未就绪（Activity 重建时 → 等 openPlayer 触发）
        val vp = views.videoPlayer ?: return
        // View 就绪 → 初始化 PiP、控制器、字幕
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            picInPicHelper = PicInPicHelper(activity) { views.videoPlayer }
        }
        controller.initController()
        vp.subtitleLoader = this::loadSubtitleData
        vp.subtitleSourceSelector = controller::getDefaultSubtitle
        // 空降助手：把"已跳过"上报给服务端（统计省下多少时间），失败静默
        vp.sponsorReporter = { uuid -> reportSponsorViewed(uuid) }
        // 底栏两个空降按钮 → 片段列表 / 提交片段
        vp.onShowSponsorSegments = { SponsorBlockUi.showSegments(activity, vp) }
        vp.onSubmitSponsorSegment = {
            if (vp.sponsorVideoId.isBlank()) {
                toast("这个视频不支持提交片段（番剧/本地视频没有 BVID）")
            } else {
                SponsorBlockUi.showSubmit(activity, vp, vp.sponsorVideoId, vp.sponsorCid)
            }
        }
        // 音频焦点冲突时不释放播放器（暂停而非杀死，配合"不放生"策略）
        vp.isReleaseWhenLossAudio = false

        // 已有播放内容 → 隐藏加载/错误覆盖层
        if (isPlaying()) {
            loadingBoxController.hideLoading()
            areaLimitBoxController.hide()
            errorMessageBoxController.hide()
            completionBoxController.hide()
        }

        // Activity 重建（MainUi.keepPlayerView 复用播放器 View）：
        // 画面还在放/暂停，但本 delegate 是新建的空壳 → 把播放源恢复回来，
        // 否则通知栏/蓝牙控制、章节跳转、进度保存、播放源相关 UI 全部失效
        if (playerSource == null && (isPlaying() || isPause())) {
            restoreKeptSource()
        }
    }

    /**
     * Activity 重建后的 delegate 级状态恢复。
     *
     * 播放器 View 与 ExoPlayer 由 keepPlayerView + GSYVideoManager 保活，
     * 这里只补 delegate 自己丢掉的数据，不重新 prepare、不打断正在播放的画面。
     */
    private fun restoreKeptSource() {
        val source = keptSource ?: return
        val p = views.videoPlayer ?: return
        playerSource = source                 // 自定义 setter 会同步 PlayerStore
        playerSourceInfo = keptSourceInfo
        keptSourceInfo?.quality?.let { if (it > 0) quality = it }
        // loadPlayerSource 里番剧被强制成 MP4，重建后切清晰度不能退回 DASH
        if (source is BangumiPlayerSource) {
            fnval = SettingConstants.PLAYER_FNVAL_MP4
        }
        // 分P/剧集按钮（View 复用时本来就在，这里兜底 View 被重建的情况）
        when {
            source is VideoPlayerSource && source.pages.size > 1 -> {
                p.setExpandButtonText("分P")
                p.showExpandButton()
            }
            source is BangumiPlayerSource && source.episodes.size > 1 -> {
                p.setExpandButtonText("剧集")
                p.showExpandButton()
            }
        }
        // 章节：fetchChapters 只在 onPrepared 触发，重建后不会重来 →
        // 从复用的播放器 View 上的 ChapterManager 取回（数据还在 View 里）
        controller.restoreChapters(p.chapterManager.getChapters())
        // 通知栏/蓝牙媒体键重新绑到本 delegate（服务可能仍持有旧 Activity 的 delegate）
        PlaybackService.instance?.setPlayerDelegate(this)
    }

    private fun registerAudioReceiver() {
        if (isBroadcastReceiverRegistered) return
        val intentFilter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_MEDIA_BUTTON)
        }
        try {
            registerReceiver(
                activity,
                broadcastReceiver,
                intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isBroadcastReceiverRegistered = true
        } catch (e: IllegalArgumentException) {
            miaoLogger() error "BroadcastReceiver already registered: ${e.message}"
        }
    }

    private fun ensureThemeObserver() {
        if (themeObserver != null) return
        themeObserver = Observer {
            val themeColor = it.toInt()
            player?.updateThemeColor(activity, themeColor)
            areaLimitBoxController.updateThemeColor(themeColor)
            errorMessageBoxController.updateThemeColor(themeColor)
            completionBoxController.updateThemeColor(themeColor)
        }
        themeDelegate.observeTheme(activity, themeObserver!!)
    }

    /** 保存当前播放位置：内存（PlaybackService）+ 持久化（进程被杀也不丢），供续播使用 */
    private fun savePlaybackPosition() {
        val p = views.videoPlayer
        val pos = p?.let { it.gsyVideoManager?.currentPosition } ?: 0L
        val sid = playerSource?.id ?: ""
        if (pos > 0L && sid.isNotEmpty()) {
            val url = playerSourceInfo?.url ?: ""
            val header = playerSourceInfo?.header ?: emptyMap()
            PlaybackService.instance?.savePlaybackState(pos, sid, url, header)
            persistLocalPlayedPosition(pos)
        }
    }

    /**
     * 把播放位置写进持久化记录（秒），键和下载续播共用 `dl_aid_cid`。
     *
     * PlaybackService 里的位置只是内存态：退后台一段时间后进程被系统杀掉就没了，
     * 下次进来"检测不到上次播到哪"就只能从 0 开始 —— 这是续播失效的主因。
     */
    private fun persistLocalPlayedPosition(posMs: Long) {
        val ids = playerSource?.getSourceIds() ?: return
        if (ids.aid.isBlank() || ids.cid.isBlank()) return
        try {
            activity.getPreferences(android.content.Context.MODE_PRIVATE)
                .edit()
                .putLong("dl_${ids.aid}_${ids.cid}", posMs / 1000)
                .apply()
        } catch (e: Exception) {
            miaoLogger() error "persist play position failed: ${e.message}"
        }
    }

    /** 读持久化的上次播放位置（秒 → 毫秒），没有则返回 null */
    private fun readLocalPlayedPosition(source: BasePlayerSource): Long? {
        val ids = source.getSourceIds()
        if (ids.aid.isBlank() || ids.cid.isBlank()) return null
        val seconds = try {
            activity.getPreferences(android.content.Context.MODE_PRIVATE)
                .getLong("dl_${ids.aid}_${ids.cid}", 0L)
        } catch (e: Exception) {
            0L
        }
        return (seconds * 1000L).takeIf { it > 0L }
    }

    private fun startPlaybackService() {
        val instance = PlaybackService.instance
        if (instance != null) {
            instance.setPlayerDelegate(this)
            return
        }
        // 首次启动：Service 通过 MediaController 异步绑定，
        // delegate 在回调里设置。若用户在此期间点通知栏停止，
        // PlaybackService 会检测到 delegate==null 并正常清理通知栏。
        val sessionToken = SessionToken(
            activity,
            ComponentName(activity, PlaybackService::class.java)
        )
        // MediaController 持有 Activity context 且会一直保持与服务连接，
        // 不持有引用就没法释放 → 每次"服务不在时开新视频"都泄漏一个连接
        releaseMediaControllerFuture()
        val controllerFuture = MediaController.Builder(activity, sessionToken).buildAsync()
        mediaControllerFuture = controllerFuture
        controllerFuture.addListener({
            PlaybackService.instance?.setPlayerDelegate(this@PlayerDelegate2)
        }, MoreExecutors.directExecutor())
    }

    /** 释放与服务之间的 MediaController 连接（关播放器/重建前调用） */
    private fun releaseMediaControllerFuture() {
        mediaControllerFuture?.let { future ->
            try {
                MediaController.releaseFuture(future)
            } catch (e: Exception) {
                miaoLogger() error "release media controller failed: ${e.message}"
            }
        }
        mediaControllerFuture = null
    }

    override fun onResume() {
        // 从 PiP / 后台回来时更新 PiP 动作按钮（播放/暂停图标同步当前状态）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val player = views.videoPlayer
            if (player != null && activity.isInPictureInPictureMode) {
                picInPicHelper?.updatePictureInPictureActions(player.currentState)
            }
        }
        // 回到前台的"补槽 + 防掉零"。
        //
        // 以前这里调的是 GSY 的 onVideoResume()（注释写的是"重连 Surface"），但按源码它做不到：
        //  - 播放中回前台它是空转（onVideoResume 只在 PAUSE 态才干活，GSYVideoView:551）；
        //  - 暂停回前台它会真的 start() 再被按回去（"回来闪一下/响一声"），
        //    而且读完就把 mCurrentPosition 清零（:562）→ 之后 surface 重建重新 prepare 就只能从 0 播。
        // 现在只补空槽（交给之后的 prepare/续播去消费）+ 位置真塌了才 seek，不再动播放状态。
        if (views.videoPlayer?.isInPlayingState == true) {
            views.videoPlayer?.reconnectSurfaceQuietly()
        }
    }

    override fun onPause() {
    }

    override fun onStart() {
        // 回前台第一件事：把 GSY 的空槽补上账本位置。
        // 两个槽都是一次性的（mCurrentPosition 被 onVideoResume 读后清零、
        // mSeekOnStart 被 startAfterPrepared 消费后清零），而且暂停中/缓冲中退出时
        // GSY 自己根本没写（它的 onVideoPause 只在底层 isPlaying() 为真时才写）。
        // 补槽只填空，不动播放状态；下面要续播时 onVideoResume() 才有东西可读。
        views.videoPlayer?.armResumeSlot()
        // 不在后台播放模式时恢复播放（和原版哔哩猫一致）
        // GSY 内部 onVideoResume 会自动 seek 到 onVideoPause 时保存的 mCurrentPosition
        // 只在"切后台前确实在播放（被 onStop 自动暂停）"时才续播：
        // 用户手动暂停的视频回前台必须保持暂停（isInPlayingState() 对 PAUSE 也返回 true，不能用）
        if (!controller.isBackgroundPlay && pausedByBackground && isPause()) {
            views.videoPlayer?.onVideoResume()
        }
        pausedByBackground = false
    }

    override fun onStop() {
        // 保存播放位置
        savePlaybackPosition()
        // 退出前把"当前真实位置"记进播放器账本并补上空槽。
        // 必须在这里做：GSY 的 onVideoPause() 只在底层 isPlaying() 时才写槽，
        // 暂停中/缓冲中切后台它什么都不写 —— 之后 surface 重建重新 prepare 就只能从 0 播。
        views.videoPlayer?.let { p ->
            p.noteResumePosition(p.currentPositionWhenPlaying)
            p.armResumeSlot()
        }
        // 不在后台播放模式时暂停播放（GSY 内部 onVideoPause 保存 mCurrentPosition）
        if (!controller.isBackgroundPlay
            && views.videoPlayer?.isInPlayingState == true) {
            // 必须在 onVideoPause() 之前判断：暂停后状态就变 PAUSE 了
            pausedByBackground = isPlaying()
            views.videoPlayer?.onVideoPause()
        } else {
            pausedByBackground = false
        }
        // 尝试进入 PiP
        if (controller.isPipOnBackground && isOpened()) {
            tryEnterPipOnBackground()
        }
    }

    override fun onDestroy() {
        // 空降助手的弹窗挂在 Activity 上：Activity 先销毁而弹窗还在 → WindowLeaked
        // （片段列表/投票/提交/颜色等弹窗都登记在 SponsorBlockUi.currentDialog 里）
        SponsorBlockUi.dismissAll()
        // 画中画广播接收器兜底注销（进入画中画后直接销毁 Activity 时不会走退出回调 → leaked receiver）
        picInPicHelper?.unregisterReceiverSafe()
        // 最后保存一次位置 + 源信息（供 Activity 重建后恢复）
        savePlaybackPosition()
        val sid = playerSource?.id ?: ""
        if (sid.isNotEmpty()) {
            PlaybackService.instance?.saveSourceInfo(
                title = playerSource?.title ?: "",
                cover = playerSource?.coverUrl ?: "",
                type = if (playerSource is BangumiPlayerSource) "bangumi" else "video",
            )
        }
        playerClosed = true
        // MediaController 持有 Activity context 并且会一直保持与服务连接，
        // 不释放就会：① 泄漏 Activity ② 让 Service 因仍有绑定而不被销毁
        //（"暂停态下从最近任务划掉 App"后通知栏再也不更新就是这么来的）
        releaseMediaControllerFuture()
        playerCoroutineScope.onDestroy()
        try {
            activity.unregisterReceiver(broadcastReceiver)
            isBroadcastReceiverRegistered = false
        } catch (e: IllegalArgumentException) {
            // 接收器未注册，忽略
        }
        // 只 detach View，不清除 playerSource / playerSourceInfo
        // GSYVideoManager 单例中的 ExoPlayer 保持存活
        views.videoPlayer?.let {
            // 后台播放模式：重建后要继续播，不能让它 detach 时 pause
            it.keepPlayingOnDetach = controller.isBackgroundPlay
            it.detachView()
        }
    }

    override fun onBackPressed(): Boolean {
        val p = views.videoPlayer ?: return false
        if (p.isLock) {
            // 原来是直接吞掉返回键，用户完全不知道发生了什么
            toast("已锁定，请先解锁")
            return true
        }
        if (scaffoldApp.fullScreenPlayer) {
            controller.onBackClick()
            return true
        }
        if (scaffoldApp.showPlayer) {
            val now = System.currentTimeMillis()
            if (now - lastBackPressedTime > 2000) {
                PopTip.show("再按一次退出播放").showLong()
                lastBackPressedTime = now
            } else {
                closePlayer()
                lastBackPressedTime = 0
            }
            return true
        }
        return false
    }

    /** 用户主动关闭播放器（双击返回 / 通知栏关闭按钮） */
    override fun closePlayer() {
        // 先保存播放位置，再关闭
        savePlaybackPosition()
        // 如果在画中画模式，先退出
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && activity.isInPictureInPictureMode
        ) {
            activity.moveTaskToBack(true)
        }
        // 退出全屏/小窗并确保 contentInsets 正确复位
        // 通知栏「停止」路径不经过 onBackClick，必须在这里调用 smallScreen
        controller.smallScreen()
        val vp = views.videoPlayer
        playerClosed = true
        scaffoldApp.showPlayer = false
        playerCoroutineScope.onDestroy()
        playerSource = null
        playerSourceInfo = null
        // 用户主动关闭播放器 → 清掉进程级缓存，避免 Activity 重建后"复活"已关闭的播放源
        keptSource = null
        keptSourceInfo = null

        // 释放播放器（GSY 内部走完整释放链路，含音频焦点回收）
        // 下次 openPlayer 走 setUp 重建播放器
        vp?.releaseDanmaku()
        vp?.hideExpandButton()
        vp?.release()
        MainUi.clearKeepPlayerView()
        lastPosition = 0L

        // ★ 清理通知栏和媒体会话：释放 ExoPlayer → 停止前台 → 回收音频焦点
        PlaybackService.instance?.notifyPlaybackComplete()
        // 服务已停，顺手释放 MediaController 连接，避免它一直攥着 Activity
        releaseMediaControllerFuture()

        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @OptIn(markerClass = [UnstableApi::class])
    private fun initPlayer() {
        BilimiaoPlayerManager.initConfig()
        GSYVideoType.setRenderType(GSYVideoType.TEXTURE)
        ExoSourceManager.setExoMediaSourceInterceptListener(this)
    }

    @OptIn(markerClass = [UnstableApi::class])
    override fun getMediaSource(
        dataSource: String,
        preview: Boolean,
        cacheEnable: Boolean,
        isLooping: Boolean,
        cacheDir: File?
    ): MediaSource? {
        val dataSourceArr = dataSource.split("\n")
        val mediaMetadata = getMediaMetadata(dataSource)
        val header = playerSourceInfo?.header ?: emptyMap()
        val userAgent = header["User-Agent"] ?: DEFAULT_USER_AGENT
        return try {
            when (dataSourceArr[0]) {
            "[local-merging]" -> {
                // 本地音视频分离
                val localSourceFactory = DefaultDataSource.Factory(activity)
                val videoMedia = MediaItem.Builder().apply {
                    setUri(dataSourceArr[1])
                    mediaMetadata?.let(::setMediaMetadata)
                }.build()
                val audioMedia = MediaItem.Builder().apply {
                    setUri(dataSourceArr[2])
                    mediaMetadata?.let(::setMediaMetadata)
                }.build()
                MergingMediaSource(
                    ProgressiveMediaSource.Factory(localSourceFactory)
                        .createMediaSource(videoMedia),
                    ProgressiveMediaSource.Factory(localSourceFactory)
                        .createMediaSource(audioMedia)
                )
            }

            "[merging]" -> {
                // 音视频分离 — 支持 | 分隔的多 CDN 候选（竞速赢家在前，运行时故障转移兜底）
                val baseFactory = createCachedFactory(activity, userAgent, header)
                val videoCandidates = dataSourceArr[1].split("|").filter { it.isNotBlank() }
                val audioCandidates = dataSourceArr.getOrNull(2)?.split("|")?.filter { it.isNotBlank() }
                val videoFactory = wrapWithFailover(baseFactory, videoCandidates)
                val audioFactory = wrapWithFailover(baseFactory, audioCandidates ?: videoCandidates)
                val videoMedia = MediaItem.Builder().apply {
                    setUri(videoCandidates.firstOrNull() ?: dataSourceArr[1])
                    mediaMetadata?.let(::setMediaMetadata)
                }.build()
                val audioMedia = MediaItem.Builder().apply {
                    setUri((audioCandidates ?: videoCandidates).firstOrNull() ?: dataSourceArr.getOrNull(2) ?: "")
                    mediaMetadata?.let(::setMediaMetadata)
                }.build()
                MergingMediaSource(
                    ProgressiveMediaSource.Factory(videoFactory)
                        .createMediaSource(videoMedia),
                    ProgressiveMediaSource.Factory(audioFactory)
                        .createMediaSource(audioMedia)
                )
            }

            "[concatenating]" -> {
                // 视频拼接
                val dataSourceFactory = createCachedFactory(activity, userAgent, header)
                ConcatenatingMediaSource().apply {
                    for (i in 1 until dataSourceArr.size) {
                        val mediaItem = MediaItem.Builder().apply {
                            setUri(dataSourceArr[i])
                            mediaMetadata?.let(::setMediaMetadata)
                        }.build()
                        addMediaSource(
                            ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(mediaItem)
                        )
                    }
                }
            }

            "[dash-mpd]" -> {
                val dataSourceFactory = createCachedFactory(activity, userAgent, header)
                // Create a DASH media source pointing to a DASH manifest uri.
                val uri = Uri.parse(dataSourceArr[1])
                val dashStr = dataSourceArr[2]
                val dashManifest =
                    DashManifestParser().parse(uri, dashStr.toByteArray().inputStream())
                val mediaSource = DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(dashManifest)
                mediaMetadata?.let {
                    mediaSource.updateMediaItem(
                        MediaItem.Builder()
                            .setMediaMetadata(it)
                            .build()
                    )
                }
                mediaSource
            }
            else -> {
                return null
            }
        }
        } catch (e: Exception) {
            // 源构建失败（如 DASH MPD 解析异常）不能抛出：Media3ExoPlayerManager
            // 会吞掉异常后继续 prepareAsync，ExoPlayer.setMediaSource(null) → NPE 闪退
            miaoLogger() error "getMediaSource 构建失败: ${e.message}"
            null
        }
    }

    override fun getMediaMetadata(dataSource: String): MediaMetadata? {
        return playerSource?.let {
            val artworkUri = Uri.parse(UrlUtil.autoHttps(it.coverUrl) + "@300w_300h_1c_")
            val metaData = MediaMetadata.Builder()
                .setTitle(it.title)
                .setArtist(it.ownerName)     // 通知栏小标题：普通视频=UP主，番剧/影视=系列名
                .setArtworkUri(artworkUri)
                .setAlbumTitle(it.ownerName)
                .build()
            return metaData
        } ?: null
    }

    @UnstableApi
    override fun getHttpDataSourceFactory(
        userAgent: String,
        listener: TransferListener?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        mapHeadData: Map<String, String>,
        allowCrossProtocolRedirects: Boolean
    ): DataSource.Factory? {
        return null
    }

    internal fun historyReport(currentPosition: Long) {
//        if (!userStore.isLogin()) {
//            return
//        }
        // 5秒记录一次
        if (currentPosition > 0 && currentPosition - lastReportProgress < 5000) {
            return
        }
        lastReportProgress = currentPosition
        val progressSec = currentPosition / 1000
        // 存内存 + 持久化（Activity 重建、进程被杀都能恢复）
        savePlaybackPosition()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            // 上报云端进度（下次进来 playurl 的 last_play_time 就是它）
            playerSource?.historyReport(progressSec)
        }
    }

    private fun setThumbImageView(coverUrl: String) {
        views.videoPlayer?.thumbImageView = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            network(coverUrl)
        }
    }

    fun changedSpeed(newSpeed: Float) {
        if (speed != newSpeed) {
            lastPosition = player?.currentPositionWhenPlaying ?: 0L
            speed = newSpeed
            player?.setSpeed(speed, true)
            PopTip.show("已切换到${speed}倍速").showTop()
            playerCoroutineScope.launch(Dispatchers.IO) {
                SettingPreferences.edit(activity) {
                    it[PlayerSpeed] = newSpeed
                }
            }
        }
    }

    fun changedQuality(newQuality: Int) {
        if (quality != newQuality) {
            val previousQuality = quality
            lastPosition = player?.currentPositionWhenPlaying ?: 0L
            quality = newQuality
            PopTip.show("正在切换清晰度").showTop()
            playerCoroutineScope.launch(Dispatchers.Main) {
                try {
                    loadPlayerSource(isChangedQuality = true)
                    SettingPreferences.edit(activity) {
                        it[PlayerQuality] = newQuality
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    quality = previousQuality  // 切换失败回退
                    PopTip.show("清晰度切换失败").showTop()
                }
            }
        }
    }

    private val loadMutex = Mutex()

// TODO AI 原声翻译：暂时关闭（切到 AI 音轨后播放器进 ERROR/黑屏）。恢复时把这段注释放开。
//     /**
//      * AI 原声翻译：切换翻译语言（null = 关闭翻译、播原声）。
//      * 换语言 = 用同一清晰度重新取一次流（HTTP playurl 的 cur_language），所以复用换清晰度那条重载路径，
//      * 只是不要弹"已切换至【清晰度】"。
//      */
//     fun changeLanguage(language: String?) {
//         // AI 原声翻译暂时整体关闭（见 DanmakuVideoPlayer.AI_TRANSLATE_ENABLED）
//         if (!DanmakuVideoPlayer.AI_TRANSLATE_ENABLED) return
//         if (playerLanguage == language) return
//         val previous = playerLanguage
//         playerLanguage = language
//         lastPosition = player?.currentPositionWhenPlaying ?: lastPosition
//         // B 站的 AI 原声翻译音轨只以 DASH 形式给（实机日志：请求 mp4 也返回 dash.duration=174 →
//         // duration=174000，而原声 mp4 是 173100）。如果用户设置的是 mp4，这次切换就变成
//         // "播放中把 MP4 流换成 DASH 流" → 播放器直接进 ERROR（转圈后黑屏，state=7）。
//         // 所以只要用过 AI 翻译，本视频会话就统一走 DASH（下次 openPlayer 会重新读设置）。
//         // 番剧源在 openPlayer 里被强制 MP4（durl 多段会 OOM），这里不动它。
//         if (playerSource !is BangumiPlayerSource) {
//             fnval = SettingConstants.PLAYER_FNVAL_DASH
//         }
//         playerCoroutineScope.launch(Dispatchers.Main) {
//             try {
//                 loadPlayerSource(isChangedQuality = true, announceQuality = false)
//                 PopTip.show(
//                     if (language.isNullOrBlank()) "已关闭 AI 翻译" else "已切换 AI 翻译音轨"
//                 ).showTop()
//             } catch (e: CancellationException) {
//                 throw e
//             } catch (e: Exception) {
//                 playerLanguage = previous  // 失败回退，别让菜单和实际音轨不一致
//                 PopTip.show("AI 翻译切换失败").showTop()
//             }
//         }
//     }

// TODO AI 原声翻译：暂时关闭（切到 AI 音轨后播放器进 ERROR/黑屏）。恢复时把这段注释放开。
//     /**
//      * 只为拿 AI 翻译语言列表：正常播放走 gRPC（PlayViewReq 没有语言字段），
//      * 所以这里在后台补一次 HTTP playurl，把字幕菜单里的"翻译"项填出来。
//      * 未登录 / 设置里关了翻译 / 视频本身不支持 → 菜单不出现翻译项。
//      */
//     private fun fetchTranslateLanguages(source: BasePlayerSource) {
//         // AI 原声翻译暂时整体关闭（见 DanmakuVideoPlayer.AI_TRANSLATE_ENABLED）：
//         // 连"后台补一次 HTTP 拿语言列表"都不做了，省一次请求
//         if (!DanmakuVideoPlayer.AI_TRANSLATE_ENABLED) return
//         if (BilimiaoCommApp.commApp.loginInfo == null) return
//         playerCoroutineScope.launch {
//             try {
//                 val languages = withContext(Dispatchers.IO) {
//                     source.getTranslateLanguages(quality, fnval)
//                 }
//                 if (languages.isNotEmpty()) {
//                     availableLanguages = languages
//                     player?.translateLanguages = languages.map {
//                         DanmakuVideoPlayer.TranslateLanguageInfo(it.lang, it.title)
//                     }
//                 }
//             } catch (e: CancellationException) {
//                 throw e
//             } catch (e: Exception) {
//                 // 拉不到就当这个视频没有 AI 翻译
//             }
//         }
//     }

    suspend fun loadPlayerSource(
        isChangedQuality: Boolean = false,
        /** 是否提示"已切换至【清晰度】"（AI 翻译换语言也会重载，但不需要报清晰度） */
        announceQuality: Boolean = isChangedQuality,
        /**
         * 同一次会话里的重载（网络失败重试 / 回前台重连）：接上上次位置就好，
         * 不要再弹"自动恢复:xx"提示 —— 用户正看着这个视频，弹提示纯打扰。
         * 只有"新打开一个看过的视频"（首次加载）才提示。
         */
        isReload: Boolean = false,
    ) {
        loadMutex.withLock {
        // 新视频开始播放，重置退出标志
        controller.explicitExitSmallWindow = false
        val source = playerSource ?: return
        // 云端提示位置只取一次（本机来源这里会是 0 → 不弹）
        val cloudTipMs = pendingCloudResumeMs
        pendingCloudResumeMs = 0L
        try {
            // ───── 账本兜底：播放器里记的"最后真实位置" ─────
            // 只在**同一个视频**（换清晰度/换语言/网络重试/回前台重连）时才能用：
            // 换视频时账本里还是上一段的位置，拿来用就会"新视频从中间开始播"。
            // 必须放在快速重连判断之前，否则账本有位置也会被当成"没位置"走全量重载。
            val sameVideo = lastLoadedSourceId != null && lastLoadedSourceId == source.id
            if (!isChangedQuality && sameVideo && lastPosition <= 0L) {
                val ledger = views.videoPlayer?.resumePosition ?: 0L
                if (ledger > 0L) {
                    lastPosition = ledger
                }
            }
            // ───── 快速重连：PlaybackService 有同一视频的缓存 URL → 跳过网络请求 ─────
            val quickUrl = PlaybackService.instance?.getSavedUrl(source.id) ?: ""
            if (!isChangedQuality && quickUrl.isNotEmpty() && lastPosition > 0L) {
                loadingBoxController.println("断点续播（缓存命中）")
                // 跳过 setUp()：ExoPlayer 仍在后台存活，MediaSource/Surface 未释放
                // 直接 seek + resume，解码器/渲染器/Manifest 全部复用，真正秒播
                // （seekTo 内部会同时把 GSY 的槽和续播账本一起更新，不用再单独写 seekOnStart）
                showResumeTipIfNeeded(cloudTipMs, isReload, isChangedQuality)
                player?.seekTo(lastPosition)
                lastPosition = 0L
                lastReportProgress = 0L
                loadingBoxController.hideLoading()
                player?.setLooping(source.isLoop)
                player?.startPlayLogic()
                player?.requestLayout()
                // 弹幕无需重连——detachView 不释放弹幕，seek 后 DanmakuTimer 自动同步
                return
            }
            // ───── 正常加载路径 ─────
            loadingBoxController.print("装载弹幕数据...")
            val danmukuParser = withContext(Dispatchers.IO) {
                source.getDanmakuParser()
            }
            if (playerClosed) return
            loadingBoxController.println("成功")
            loadingBoxController.print("获取视频信息...")
            val sourceInfo = withContext(Dispatchers.IO) {
                // TODO AI 原声翻译：暂时关闭（原来是 getPlayerUrl(quality, fnval, playerLanguage)）
                source.getPlayerUrl(quality, fnval)
            }
            if (playerClosed) return
            quality = sourceInfo.quality
            playerSourceInfo = sourceInfo
            keptSourceInfo = sourceInfo
// TODO AI 原声翻译：暂时关闭（切到 AI 音轨后播放器进 ERROR/黑屏）。恢复时把这段注释放开。
//             // AI 原声翻译：把语言列表和当前语言同步给播放器的字幕菜单
//             if (sourceInfo.languages.isNotEmpty()) {
//                 availableLanguages = sourceInfo.languages
//             }
//             // 顺序要紧：先接回调再灌语言列表 —— 语言列表的 setter 会顺带刷新底栏 AI 按钮的可见性
//             player?.onTranslateSelected = { lang -> changeLanguage(lang) }
//             player?.translateLanguages = availableLanguages.map {
//                 DanmakuVideoPlayer.TranslateLanguageInfo(it.lang, it.title)
//             }
//             player?.currentTranslateLang = sourceInfo.currentLanguage
            loadingBoxController.print("成功")
            player?.releaseDanmaku()
            player?.danmakuParser = danmukuParser
            // 换视频 → 清掉播放器的续播账本（否则新视频会被拽到上一段的位置）；
            // 同一个视频重载（换清晰度/换语言/网络重试）→ 账本留着，prepare 后才能接着播
            if (lastLoadedSourceId != source.id) {
                player?.resetRestartGuard()
                lastLoadedSourceId = source.id
            } else {
                player?.resetRestartGuardKeepPosition()
            }
            player?.setUp(
                sourceInfo.url,
                false,
                null,
                sourceInfo.header,
                source.title
            )
            loadingBoxController.hideLoading()
            // ★ P0 守卫：lastPosition 接近末尾时从头开始，避免 seek 到末尾立即 STATE_ENDED
            // 触发 onAutoCompletion 死循环（自动连播到同一视频时 PlaybackService 会返回末尾位置）
            if (sourceInfo.duration > 0L && lastPosition >= sourceInfo.duration - 2000) {
                lastPosition = 0L
            }
            if (lastPosition > 0L) {
                // 显式投递落点（防被计时器/补槽覆盖）—— 换清晰度/换语言/重试/续播都靠它
                player?.armSeekOnPrepare(lastPosition)
                // 只有云端来源（预设）才弹；本机会话内存/持久化记录不弹（用户要求）
                showResumeTipIfNeeded(cloudTipMs, isReload, isChangedQuality)
                lastPosition = 0L
            } else if (
                sourceInfo.lastPlayCid == source.id
                && !source.isLoop // 循环的视频不恢复播放
                && sourceInfo.lastPlayTime > 0L
                && (sourceInfo.duration <= 0L || sourceInfo.lastPlayTime < sourceInfo.duration - 10000)
            ) {
                player?.armSeekOnPrepare(sourceInfo.lastPlayTime)
                lastPosition = 0L
                // 这条提示只在"新打开一个看过的视频、本地又没位置"时出现（用户认可，保留）；
                // 同一次会话里的重载（网络重试/回前台重连）走 isReload 分支，不弹提示直接接着播
                showResumeTipIfNeeded(sourceInfo.lastPlayTime, isReload, isChangedQuality)
            } else if (sourceInfo.lastPlayCid == source.id) {
                // 从进度0开始记录播放历史
                historyReport(0L)
            } else {
            }
            lastReportProgress = 0L
            player?.setLooping(source.isLoop)
            player?.startPlayLogic()
            player?.requestLayout()

            if (announceQuality) {
                if (sourceInfo.quality == quality) {
                    PopTip.show("已切换至【${sourceInfo.description}】").showTop()
                } else {
                    PopTip.show("清晰度切换失败").showTop()
                }
            } else {
                player?.subtitleSourceList = withContext(Dispatchers.IO) {
                    source.getSubtitles().map {
                        DanmakuVideoPlayer.SubtitleSourceInfo(
                            id = it.id,
                            lan = it.lan,
                            lan_doc = it.lan_doc,
                            subtitle_url = it.subtitle_url,
                            ai_status = it.ai_status,
                        )
                    }
                }
                // 拖动进度条预览图：和字幕一样后台补一次（没数据/失败就静默降级，不影响播放）
                loadVideoShot(source)
                // 空降助手：赞助/恰饭片段（第三方服务端，同样静默降级）
                loadSponsorSegments(source)
// TODO AI 原声翻译：暂时关闭（切到 AI 音轨后播放器进 ERROR/黑屏）。恢复时把这段注释放开。
//                 // 正常播放走 gRPC，拿不到 language 列表 → 后台补一次 HTTP 只为填"翻译"菜单
//                 if (!isChangedQuality) {
//                     fetchTranslateLanguages(source)
//                 }
            }
        } catch (e: DabianException) {
            errorMessageBoxController.show("少儿不宜，禁止观看", canRetry = false)
        } catch (e: AreaLimitException) {
            (playerSource as? BangumiPlayerSource)?.let {
                areaLimitBoxController.show(it)
            } ?: errorMessageBoxController.show("抱歉你所在的地区不可观看！")
        } catch (e: UnknownHostException) {
            errorMessageBoxController.show("网络请求失败")
        } catch (e: CancellationException) {
            // 退出播放器/切清晰度会取消协程：取消不是错误，别在已销毁的界面上弹"播放失败"
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            errorMessageBoxController.show(e.message ?: e.toString())
        }
        }
    }


    /**
     * 字幕请求令牌：选轨、关字幕、换视频都 +1。
     *
     * 网络回来的结果只有令牌还是最新的才允许写进播放器 —— 否则会出现这两类"不立即生效"：
     *  ① 关字幕后，关闭前那次请求返回 → subtitleBody 又被填上 → 字幕"关不掉、自己冒回来"；
     *  ② 从 A 轨切到 B 轨，A 的响应比 B 晚到 → 显示的是 A（串台）。
     */
    private var subtitleRequestToken = 0

    /**
     * 字幕解析结果缓存（key = subtitle_url）。
     *
     * B 站字幕是独立 CDN 上的 json，一次往返几百毫秒起步；用户"关掉再打开同一条轨"时
     * 命中缓存就能立刻显示，不用再等一次网络。url 里带 cid/ai 标识，不会跨视频串。
     */
    private val subtitleCache = LruCache<String, List<DanmakuVideoPlayer.SubtitleItemInfo>>(8)

    /**
     * 加载字幕数据（subtitleUrl = null：当前没有选中字幕，作废在飞的请求）
     */
    private fun loadSubtitleData(subtitleUrl: String?) {
        // 先领令牌：无论走缓存、走网络还是直接关闭，旧请求都会因为令牌过期被丢弃
        val token = ++subtitleRequestToken
        if (subtitleUrl.isNullOrBlank()) {
            player?.subtitleBody = emptyList()
            return
        }
        // 命中缓存 → 立即显示（"关了再打开"不再等网络）
        val cached = subtitleCache.get(subtitleUrl)
        if (cached != null) {
            player?.subtitleBody = cached
            return
        }
        playerCoroutineScope.launch(Dispatchers.IO) {
            try {
                val res = MiaoHttp.request {
                    url = UrlUtil.autoHttps(subtitleUrl)
                }.awaitCall().json<SubtitleJsonInfo>()
                val list = res.body.map {
                    DanmakuVideoPlayer.SubtitleItemInfo(
                        from = (it.from * 1000).toLong(),
                        to = (it.to * 1000).toLong(),
                        content = it.content,
                    )
                }
                subtitleCache.put(subtitleUrl, list)
                withContext(Dispatchers.Main) {
                    // 过期结果直接丢：期间用户可能已经关了字幕、换了轨道、甚至换了视频
                    if (token != subtitleRequestToken) return@withContext
                    if (player?.currentSubtitleSource?.subtitle_url != subtitleUrl) return@withContext
                    player?.subtitleBody = list
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                // 过期请求的报错不再打扰用户（多半是他自己刚关掉/换轨）
                if (token != subtitleRequestToken) return@launch
                withContext(Dispatchers.Main) {
                    PopTip.show(e.message.toString()).showTop()
                }
            }
        }
    }

    /** 预览图请求令牌：换视频/换清晰度时作废上一次的结果（否则旧视频的图会串到新视频上） */
    private var videoShotToken = 0

    /**
     * 拉取「拖动进度条预览图」（B 站 videoshot 缩略图雪碧图）。
     *
     * 特性：
     *  - 后台拉，绝不阻塞起播；
     *  - 拿不到（视频太短 / 番剧没这数据 / 风控 / 网络失败）就置空 → 播放器退化成"只显示时间气泡"；
     *  - 设置里关掉预览图时直接不请求，省一次流量。
     */
    private fun loadVideoShot(source: BasePlayerSource) {
        val token = ++videoShotToken
        // 先清掉上一个视频的图，避免新视频还没拿到数据时拖出旧画面
        player?.videoShotData = null
        if (player?.showSeekPreview == false) return
        playerCoroutineScope.launch(Dispatchers.IO) {
            val data = try {
                source.getVideoShot()
            } catch (e: Exception) {
                null
            } ?: return@launch
            withContext(Dispatchers.Main) {
                if (token != videoShotToken) return@withContext
                player?.videoShotData = data
            }
        }
    }

    /** 空降助手片段请求令牌：换视频作废（避免上一个视频的片段跳到新视频上） */
    private var sponsorToken = 0

    /**
     * 拉取「空降助手」（BilibiliSponsorBlock）片段。
     *
     * 粒度是"整个视频一次"（不像预览图要按时间取图），拿到后交给播放器；
     * 播放器那边的 500ms 判定器会按分类/跳过策略决定跳不跳。
     * 任何失败都是空列表 —— 服务端不可达时功能静默失效，绝不影响播放。
     */
    fun loadSponsorSegments(source: BasePlayerSource) {
        val token = ++sponsorToken
        player?.sponsorSegments = emptyList()
        // 记下 BVID/CID：提交片段时要用（番剧源没有 bvid → 留空，界面会提示不支持）
        player?.sponsorVideoId = (source as? VideoPlayerSource)?.effectiveBvid.orEmpty()
        player?.sponsorCid = source.id
        // 总开关没开就不请求第三方服务端；但番剧的片头片尾是 playurl 自带的（零请求），照常加载
        if (player?.sponsorSkipEnabled != true && source !is BangumiPlayerSource) return
        val cid = source.id
        SponsorDiag.log("load", "bvid=${(source as? VideoPlayerSource)?.effectiveBvid} cid=$cid enabled=${player?.sponsorSkipEnabled}")
        playerCoroutineScope.launch(Dispatchers.IO) {
            val list = try {
                source.getSponsorSegments(cid)
            } catch (e: Exception) {
                emptyList()
            }
            if (list.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                if (token != sponsorToken) return@withContext
                player?.sponsorSegments = list
            }
        }
    }

    /** 上报"这个片段确实被跳过了"（服务端统计用；失败无所谓，绝不打扰用户） */
    private fun reportSponsorViewed(uuid: String) {
        if (uuid.isBlank()) return
        playerCoroutineScope.launch(Dispatchers.IO) {
            try {
                BiliApiService.sponsorBlockAPI.reportViewed(uuid)
            } catch (e: Exception) {
                // 静默：上报失败不影响播放
            }
        }
    }


    /**
     * 自动续播提示：`自动恢复:xx:xx` + 按钮 `重新开始`。
     *
     * 规则（用户定）：**只有云端给的位置才弹**（预设里的云端历史/番剧进度、playurl 的 last_play_time）；
     * 本机来源一律不弹（PlaybackService 会话内存、`dl_aid_cid` 持久化记录 —— "返回桌面再进软件"属于这类）；
     * 同一次会话里的重载（换清晰度/换语言/重试/回前台重连）也不弹。
     *
     * 播放器已经 prepared（快速重连那类，不会再走 onPrepared）→ 立刻弹；
     * 否则挂到下一次 onPrepared 之后弹。
     * 按钮 `重新开始` = 从 0 播：seekTo(0) 会连带把续播账本/槽位一起归零，
     * 避免随后任何一次 re-prepare 又把画面拽回原来的进度。
     */
    private fun showResumeTipIfNeeded(
        posMs: Long,
        isReload: Boolean,
        isChangedQuality: Boolean = false,
    ) {
        // 同会话里的重载（换清晰度/换语言/网络重试/回前台重连）不弹：用户正看着这个视频，弹提示是打扰
        if (isReload || isChangedQuality || posMs <= 0L) return
        val cid = playerSource?.id ?: return
        val lastTimeStr = NumberUtil.converDuration(posMs / 1000)
        val showAction = Runnable {
            PopTip.show("自动恢复:$lastTimeStr", "重新开始")
                .showTop()
                .showLong()
                .setButton { dialog, _ ->
                    player?.seekTo(0L)
                    // 暂停态时顺带开播（播放态下 onVideoResume 是空转）
                    player?.onVideoResume()
                    false
                }
        }
        if (player?.isInPlayingState == true) {
            player?.post(showAction)
        } else {
            controller.postPrepared(cid, showAction)
        }
    }

    /**
     * 记录播放位置
     */
    fun reloadPlayer() {
        // 重播/重试都是"我要看"的明确意图：清掉暂停意图，否则 prepare 完 GSY 会把画面按回暂停
        views.videoPlayer?.clearPausedIntent()
        // 位置兜底顺序：播放器当前位置 → 最后一次上报过的位置 → 上一次的 lastPosition。
        // 网络失败时播放器往往已经死了，currentPositionWhenPlaying 会返回 0，
        // 只认它就会出现"点重试 = 从头播"。
        lastPosition = player?.currentPositionWhenPlaying?.takeIf { it > 0L }
            ?: lastReportProgress.takeIf { it > 0L }
            ?: lastPosition
        playerCoroutineScope.launch(Dispatchers.Main) {
            // 不要清 lastPlayCid/lastPlayTime：这两个是 playurl 每次响应都带回来的"云端进度"，
            // 留着它们，loadPlayerSource 里的"自动恢复"分支才能在本地位置也丢了的时候接着云端播
            // （清掉就只能从 0 开始，这正是"重试后从头播"的根因）
            loadPlayerSource(isReload = true)
        }
    }

    override fun openPlayer(source: BasePlayerSource) {
        playerClosed = false
        // 换视频要复位锁定：否则锁定状态下自动连播下一集，新视频开局就是锁死的
        // （控件全隐藏、返回键还会被静默吞掉）
        views.videoPlayer?.isLock = false
        loadingBoxController.showLoading(source.title, source.coverUrl)
        loadingBoxController.print("初始化播放器...")
        completionBoxController.hide()
        errorMessageBoxController.hide()
        areaLimitBoxController.hide()
        // 恢复上次位置。优先级：循环播放 → 0；详情页预设的进度（云端历史 / 消息页跳转 / 评论空降）
        // → 用它；否则用本地记录（服务内存 → 持久化，进程被杀也不丢）。
        //
        // 老逻辑是"只要有预设就把 lastPosition 清 0"，而视频源在接口不返回 last_play_time 时
        // 又会把预设抹成 0 → 两边记录都在却从头播。这里改成"预设直接当续播位置用"。
        // （防"seek 到末尾 → STATE_ENDED 死循环"由下面 loadPlayerSource 里的近末尾守卫负责）
        val presetPosition = source.defaultPlayerSource.lastPlayTime
        lastPosition = when {
            source.isLoop -> 0L
            presetPosition > 0L -> presetPosition
            else -> PlaybackService.instance?.getSavedPosition(source.id)?.takeIf { it > 0L }
                ?: readLocalPlayedPosition(source)
                ?: 0L
        }
        // 只有"预设"（历史页云端进度 / 番剧继续观看）算云端来源 → 才弹"自动恢复"提示；
        // PlaybackService 会话内存和 dl_aid_cid 持久化记录都是本机来源 → 不弹
        pendingCloudResumeMs = if (!source.isLoop && presetPosition > 0L) presetPosition else 0L
        // 不同视频 → 释放旧播放器；同一视频 → 保留走快速重连
        if (playerSource != null && playerSource!!.id != source.id) {
            views.videoPlayer?.release()
            playerCoroutineScope.onDestroy()
            playerSource = null
// TODO AI 原声翻译：暂时关闭（切到 AI 音轨后播放器进 ERROR/黑屏）。恢复时把这段注释放开。
//             // 换视频（或换集）复位 AI 翻译：语言列表是每个视频单独给的
//             playerLanguage = null
//             availableLanguages = emptyList()
//             views.videoPlayer?.translateLanguages = emptyList()
//             views.videoPlayer?.currentTranslateLang = null
        }
        playerCoroutineScope.onCreate()
        playerSource = source
        keptSource = source
        controller.resetAutoFullScreenCheck()
        scaffoldApp.showPlayer = true
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadingBoxController.println("成功")
        // 玩家视图由 ScaffoldView.updateLayout() 异步创建，必须用 post 等待布局完成
        scaffoldApp.post {
            if (playerClosed || activity.isFinishing) return@post
            setThumbImageView(source.coverUrl)
            playerCoroutineScope.launch(Dispatchers.Main) {
                var selectedHost = "default"
                SettingPreferences.getData(activity) {
                    fnval = it[PlayerFnval] ?: SettingConstants.PLAYER_FNVAL_DASH
                    // 番剧/影视强制使用 MP4 源：fnval 必须为 MP4，否则 API 返回的
                    // durl 是 DASH 小分段 → ConcatenatingMediaSource 多段缓存 → OOM
                    if (source is BangumiPlayerSource) {
                        fnval = SettingConstants.PLAYER_FNVAL_MP4
                    }
                    quality = it[PlayerQuality] ?: 64
                    speed = it[PlayerSpeed] ?: 1f
                    // 占用音频焦点：这个开关以前是死的（:308 写死 isReleaseWhenLossAudio = false），
                    // 打开后应该"别的 App 出声就暂停自己"，关掉则允许同时出声
                    views.videoPlayer?.isReleaseWhenLossAudio = it[SettingPreferences.PlayerAudioFocus] ?: false
                    showNotification = it[PlayerNotification] ?: true
                    // ───── CDN 设置（合并读取，减少 DataStore IO） ─────
                    source.cdnRaceEnabled = it[SettingPreferences.CdnRaceEnabled] ?: true
                    source.audioIndependentCdn = it[SettingPreferences.AudioIndependentCdn] ?: false
                    selectedHost = it[SettingPreferences.SelectedCdnHost] ?: "default"
                }
                if (selectedHost.isNotEmpty()) {
                    val cdnInfo = CdnHosts.list.find { it.key == selectedHost }
                    val host = cdnInfo?.host
                    if (host != null && host.isNotEmpty() && host != "backup") {
                        source.uposHost = host
                    } else if (host == "backup") {
                        source.uposHost = "backup"
                    }
                }
                if (showNotification) {
                    // 在 loadPlayerSource 之前启动 Service，
                    // 利用 IO 挂起间隙让 onCreate() 执行完 startForeground
                    startPlaybackService()
                }
                loadPlayerSource()
                // loadPlayerSource 末尾已在成功路径刷新通知栏元数据
                // Media3 通过 ExoPlayer 自动处理通知栏，无需手动控制
                player?.setSpeed(speed, true)
                // 播放倍速提示
               //if (speed != 1f) {
                   // PopTip.show("注意，当前为${speed}倍速").showTop()
               // }
            }
            // 是否显示分P和剧集按钮
            if (source is VideoPlayerSource && source.pages.size > 1) {
                player?.setExpandButtonText("分P")
                player?.showExpandButton()
            } else if (source is BangumiPlayerSource && source.episodes.size > 1) {
                player?.setExpandButtonText("剧集")
                player?.showExpandButton()
            } else {
                player?.hideExpandButton()
            }
        }
        // 添加到用户库历史记录
        if (source is VideoPlayerSource) {
            userLibraryStore.appendHistory(
                UserLibraryStore.HistoryInfo(
                    aid = source.aid.toLong(),
                    title = source.mainTitle,
                    cover = source.coverUrl,
                    viewAt = System.currentTimeMillis(),
                )
            )
        }
    }


    override fun isPlaying(): Boolean {
        val p = views.videoPlayer ?: return false
        return p.currentState == GSYVideoPlayer.CURRENT_STATE_PLAYING ||
                p.currentState == GSYVideoPlayer.CURRENT_STATE_PREPAREING ||
                p.currentState == GSYVideoPlayer.CURRENT_STATE_PLAYING_BUFFERING_START
    }

    override fun isPause(): Boolean {
        val p = views.videoPlayer ?: return false
        return p.currentState == GSYVideoPlayer.CURRENT_STATE_PAUSE
    }

    override fun isPreparing(): Boolean {
        val p = views.videoPlayer ?: return false
        return p.currentState == GSYVideoPlayer.CURRENT_STATE_PREPAREING
    }

    override fun isOpened(): Boolean {
        return scaffoldApp.showPlayer
    }

    override fun setWindowInsets(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        displayCutout: DisplayCutout?
    ) {
        val p = views.videoPlayer ?: return
        p.setWindowInsets(left, top, right, bottom, displayCutout)
        if (p.mode == DanmakuVideoPlayer.PlayerMode.FULL) {
            loadingBoxController.setWindowInsets(left, top, right, bottom)
        } else if (p.mode == DanmakuVideoPlayer.PlayerMode.SMALL_FLOAT) {
            loadingBoxController.setWindowInsets(0, 0, 0, 0)
        } else {
            loadingBoxController.setWindowInsets(left, 0, right, 0)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            picInPicHelper?.onPictureInPictureModeChanged(isInPictureInPictureMode)
            if (isInPictureInPictureMode) { // 进入画中画模式，则隐藏其它控件
                // 隐藏视频控制器
                player?.hideController()
                //
                player?.isPicInPicMode = true
                // 视频组件全屏
                scaffoldApp.fullScreenPlayer = true
                // 调整弹幕样式，调小字体，限制行数
            } else {
                scaffoldApp.fullScreenPlayer =
                    player?.mode == DanmakuVideoPlayer.PlayerMode.FULL
                player?.isPicInPicMode = false
            }
            playerCoroutineScope.launch {
                SettingPreferences.getData(activity) {
                    controller.initVideoSetting(it)
                    controller.initDanmakuContext(it)
                }
            }
        }
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        controller.updatePlayerMode(newConfig)
        if (scaffoldApp.orientation != newConfig.orientation) {
            controller.onChangedScreenOrientation(newConfig.orientation)
        }
    }

    override fun getSourceIds(): PlayerSourceIds {
        return playerSource?.getSourceIds() ?: PlayerSourceIds()
    }

    override fun currentPosition(): Long {
        return player?.currentPosition ?: 0L
    }

    /**
     * 打开弹幕编辑框前是否在播放。
     * 只有本来在播放的，关掉编辑框后才自动恢复；用户自己暂停过再点发送的，应该保持暂停。
     */
    private var wasPlayingBeforeDanmakuEdit = false

    override fun openDanmakuEditor() {
        wasPlayingBeforeDanmakuEdit = isPlaying()
        if (wasPlayingBeforeDanmakuEdit) {
            player?.onVideoPause()
            player?.hideController()
        }
    }

    override fun closeDanmakuEditor() {
        // 编辑框以任何方式关闭（发送成功/返回/点空白）都要走这里，否则取消发送后视频会一直停着
        if (wasPlayingBeforeDanmakuEdit && isPause()) {
            player?.onVideoResume()
        }
        wasPlayingBeforeDanmakuEdit = false
    }

    override fun sendDanmaku(
        type: Int,
        danmakuText: String,
        danmakuTextSize: Float,
        danmakuTextColor: Int,
        danmakuPosition: Long,
    ) {
        val dispDensity = activity.resources.displayMetrics.density
        // 只有"打开编辑框前本来在播放"才在这里恢复播放，确保 danmaku view 处于活跃状态再添加弹幕：
        // 否则 addDanmaku 发出的 NOTIFY_RENDERING 会被后面的 start(position) 中
        // handler.removeCallbacksAndMessages(null) 清除，弹幕即使已在 danmakuList 中也无法立即渲染。
        // 用户自己暂停过的（或本来就没播）保持暂停，弹幕照样会加到列表里，恢复播放时按时间渲染。
        if (wasPlayingBeforeDanmakuEdit && isPause()) {
            player?.onVideoResume()
        }
        // 使用实时的 currentPosition（恢复播放后已更新），而非调用方在 API 请求前捕获的过期值
        val currentPosition = player?.currentPosition ?: danmakuPosition
        val danmaku = controller.createDanmaku(type).apply {
            text = danmakuText
            textColor = danmakuTextColor
            textSize = danmakuTextSize * (dispDensity - 0.6f)
            time = currentPosition + 100
            borderColor = 0xFFFFFFFF.toInt()
        }
        player?.addDanmaku(danmaku)
    }

    override fun setProxy(proxyServer: ProxyServerInfo, uposHost: String) {
        playerSource?.let {
            it.proxyServer = proxyServer
            it.uposHost = uposHost
            openPlayer(it)
        }
    }

    fun getVideoRatio(): Float? {
        return (playerSourceInfo ?: playerSource?.defaultPlayerSource)?.screenProportion
    }

    /**
     * 退出APP时自动进入小窗播放
     */
    fun tryEnterPipOnBackground() {
        // 已在小窗中，不重复
        if (activity.isInPictureInPictureMode) {
            return
        }
        // 用户手动退出了小窗
        if (controller.explicitExitSmallWindow) {
            controller.explicitExitSmallWindow = false
            return
        }
        if (controller.isPipOnBackground && isOpened()) {
            val height = playerSourceInfo?.height
            val width = playerSourceInfo?.width
            val aspectRatio = if (height == null || width == null || height <= 0 || width <= 0) {
                // 宽高缺失或接口返回 0 时兜底：Rational 分母为 0 会抛 IllegalArgumentException
                Rational(16, 9)
            } else {
                Rational(width, height)
            }
            try {
                picInPicHelper?.enterPictureInPictureMode(aspectRatio)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setHoldStatus(isHold: Boolean) {
        player?.setHoldStatus(isHold)
        completionBoxController.setHoldStatus(isHold)
    }

    // ——— 通知栏控制器委托 ———

    override fun mediaPlay() {
        // 播放完成后通知栏点播放 → 从头重新播放（匹配"重新播放"按钮行为）
        if (completionBoxController.completionLayout?.visibility == View.VISIBLE) {
            views.videoPlayer?.onVideoPause() // 阻止 GSY 因 surface 可见而自动 resume
            completionBoxController.hide()
            reloadPlayer()
            return
        }
        // 用 onVideoResume(false) 防止 GSY seek 回 onVideoPause 时保存的 mCurrentPosition，
        // 否则暂停期间通知栏拖动进度条后再点播放会回到暂停位置而非拖动位置
        // （注：它在成功后会清零 mCurrentPosition —— 我们的 seekTo/计时器会把账本槽重新填上）
        views.videoPlayer?.armResumeSlot()
        views.videoPlayer?.onVideoResume(false)
    }

    override fun mediaPause() {
        // 用户在后台（通知栏/蓝牙）主动暂停：清掉"后台自动暂停"标记，
        // 否则回到前台会被 onStart 当成自动暂停而自动续播
        pausedByBackground = false
        views.videoPlayer?.onVideoPause()
    }

    override fun mediaSeekTo(position: Long) {
        views.videoPlayer?.seekTo(position)
    }

    override fun mediaSeekBack() {
        val p = views.videoPlayer ?: return
        val target = (p.currentPositionWhenPlaying - seekStepMs).coerceAtLeast(0)
        p.seekTo(target)
    }

    override fun mediaSeekForward() {
        val p = views.videoPlayer ?: return
        val dur = p.duration
        // 时长未知时不要 seek，避免错误地跳回 0
        if (dur <= 0L) return
        val target = (p.currentPositionWhenPlaying + seekStepMs).coerceAtMost(dur)
        p.seekTo(target)
    }

    override fun mediaGetDuration(): Long {
        return views.videoPlayer?.duration ?: 0L
    }

    // ========== 章节控制（通知栏/蓝牙） ==========

    override fun hasChapters(): Boolean {
        return ChapterNavigator.hasChapters(controller.currentChapters)
    }

    override fun hasPreviousChapter(): Boolean {
        val p = views.videoPlayer ?: return false
        return ChapterNavigator.hasPrevious(
            controller.currentChapters,
            p.currentPositionWhenPlaying,
        )
    }

    override fun hasNextChapter(): Boolean {
        val p = views.videoPlayer ?: return false
        return ChapterNavigator.hasNext(
            controller.currentChapters,
            p.currentPositionWhenPlaying,
        )
    }

    override fun mediaSeekToPreviousChapter(): Boolean {
        val p = views.videoPlayer ?: return false
        val chapters = controller.currentChapters
        if (!ChapterNavigator.hasChapters(chapters)) return false
        val pos = p.currentPositionWhenPlaying
        // 1. 有上一章节 → 直接跳到上一章节起点（不用时间窗口）
        val chapterStart = ChapterNavigator.previousStart(chapters, pos)
        if (chapterStart != null) {
            p.seekTo(chapterStart)
            return true
        }
        // 2. 没有上一章节，但还可以后退 10 秒 → 后退 10 秒
        if (pos >= seekStepMs) {
            p.seekTo(pos - seekStepMs)
            return true
        }
        // 3. 不足 10 秒，有上一集 → 上一集
        if (mediaPlayPrevious()) {
            return true
        }
        // 4. 没有上一集 → 回到 0 秒
        p.seekTo(0L)
        return true
    }

    override fun mediaSeekToNextChapter(): Boolean {
        val p = views.videoPlayer ?: return false
        val chapters = controller.currentChapters
        if (!ChapterNavigator.hasChapters(chapters)) return false
        val pos = p.currentPositionWhenPlaying
        val duration = p.duration
        // 1. 有下一章节 → 直接跳到下一章节起点（不用时间窗口）
        val chapterStart = ChapterNavigator.nextStart(chapters, pos)
        if (chapterStart != null) {
            p.seekTo(chapterStart)
            return true
        }
        // 2. 没有下一章节，但剩余时间还够前进 10 秒 → 前进 10 秒
        if (duration > 0L && duration - pos >= seekStepMs) {
            p.seekTo((pos + seekStepMs).coerceAtMost(duration))
            return true
        }
        // 3. 剩余不足 10 秒，有下一集 → 下一集
        if (mediaPlayNext()) {
            return true
        }
        // 4. 没有下一集 → 不动
        return false
    }

    override fun mediaGetTitle(): String? {
        return playerSource?.title
    }

    override fun mediaGetSubtitle(): String? {
        return playerSource?.ownerName
    }

    override fun mediaGetCoverUrl(): String? {
        return playerSource?.coverUrl
    }

    override fun mediaPlayNext(): Boolean {
        val current = playerSource ?: return false
        // 直接源链下一项（下一P/下一集）
        val directNext = current.next()
        if (directNext != null) {
            openPlayer(directNext)
            return true
        }
        // 播单下一项
        val currentAid = (current as? VideoPlayerSource)?.aid
            ?: (current as? BangumiPlayerSource)?.aid ?: return false
        val state = playListStore.stateFlow.value
        val idx = state.items.indexOfFirst { it.aid == currentAid }
        if (idx >= 0 && idx + 1 < state.items.size) {
            val nextItem = state.items[idx + 1]
            val nextSource = if (nextItem.sid.isNotBlank() && nextItem.epid.isNotBlank()) {
                nextItem.toBangumiPlayerSource()
            } else {
                nextItem.toVideoPlayerSource()
            }
            openPlayer(nextSource)
            return true
        }
        return false
    }

    override fun mediaPlayPrevious(): Boolean {
        val current = playerSource ?: return false
        // 先走源链上一项（上一P/上一集），与 mediaPlayNext 对称：
        // 否则多P视频的通知栏/蓝牙"上一集"会跳过同一视频的其它分P
        val directPrevious = current.previous()
        if (directPrevious != null) {
            openPlayer(directPrevious)
            return true
        }
        // 播单上一项
        val currentAid = (current as? VideoPlayerSource)?.aid
            ?: (current as? BangumiPlayerSource)?.aid ?: return false
        val state = playListStore.stateFlow.value
        val idx = state.items.indexOfFirst { it.aid == currentAid }
        if (idx > 0) {
            val prevItem = state.items[idx - 1]
            val prevSource = if (prevItem.sid.isNotBlank() && prevItem.epid.isNotBlank()) {
                prevItem.toBangumiPlayerSource()
            } else {
                prevItem.toVideoPlayerSource()
            }
            openPlayer(prevSource)
            return true
        }
        // 无前一集 → seek 到开头
        val p = views.videoPlayer
        if (p != null) {
            p.seekTo(0)
            return true
        }
        return false
    }

    override fun hasPreviousEpisode(): Boolean {
        val current = playerSource ?: return false
        // 直接源链检查（上一P/上一集），与 hasNextEpisode 对称
        if (current.previous() != null) return true
        // 播单检查
        val currentAid = (current as? VideoPlayerSource)?.aid
            ?: (current as? BangumiPlayerSource)?.aid ?: return false
        val idx = playListStore.stateFlow.value.items.indexOfFirst { it.aid == currentAid }
        return idx > 0
    }

    override fun isPlaylistSingle(): Boolean {
        return playListStore.stateFlow.value.items.size <= 1
    }

    override fun hasNextEpisode(): Boolean {
        val current = playerSource ?: return false
        // 直接源链检查
        if (current.next() != null) return true
        // 播单检查
        val currentAid = (current as? VideoPlayerSource)?.aid
            ?: (current as? BangumiPlayerSource)?.aid ?: return false
        val state = playListStore.stateFlow.value
        val idx = state.items.indexOfFirst { it.aid == currentAid }
        return idx >= 0 && idx + 1 < state.items.size
    }

    /** 同步播放模式：通知栏切换后即时更新当前播放器循环状态 */
    override fun syncPlayMode(order: Int, random: Boolean) {
        val nextBits = SettingConstants.PLAYER_ORDER_NEXT_P or
                SettingConstants.PLAYER_ORDER_NEXT_VIDEO or
                SettingConstants.PLAYER_ORDER_NEXT_EPISODE
        val hasLoop = (order and SettingConstants.PLAYER_ORDER_LOOP) != 0
        val hasNext = (order and nextBits) != 0
        val isLoop = hasLoop && !hasNext  // 单集循环
        playerSource?.isLoop = isLoop
        // 直达 ExoPlayer repeatMode：gsyVideoManager.player 即 IPlayerManager（运行期为 Media3ExoPlayerManager），
        // getMediaPlayer() 返回 ExoMediaPlayer，setLooping → mInternalPlayer.setRepeatMode（主线程，与 ExoPlayer 同线程）
        val manager = player?.gsyVideoManager?.player
        if (manager is com.a10miaomiao.bilimiao.widget.player.media3.Media3ExoPlayerManager) {
            manager.getMediaPlayer()?.setLooping(isLoop)
        }
    }
}