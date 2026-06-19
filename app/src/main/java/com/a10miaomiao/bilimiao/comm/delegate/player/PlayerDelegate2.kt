package com.a10miaomiao.bilimiao.comm.delegate.player

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.player.BilimiaoPlayerManager
import com.a10miaomiao.bilimiao.comm.proxy.ProxyServerInfo
import com.a10miaomiao.bilimiao.comm.store.PlayerStore
import com.a10miaomiao.bilimiao.comm.store.UserLibraryStore
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.comm.utils.CdnHosts
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.config.config
import com.a10miaomiao.bilimiao.service.PlaybackService
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.widget.player.DanmakuVideoPlayer
import com.a10miaomiao.bilimiao.widget.player.media3.ExoMediaSourceInterceptListener
import com.a10miaomiao.bilimiao.widget.player.media3.ExoSourceManager
import com.a10miaomiao.bilimiao.widget.scaffold.getScaffoldView
import com.google.common.util.concurrent.MoreExecutors
import com.a10miaomiao.bilimiao.comm.toast
import com.kongzue.dialogx.dialogs.PopTip
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import com.shuyu.gsyvideoplayer.video.base.GSYVideoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.File
import java.net.UnknownHostException


class PlayerDelegate2(
    private var activity: AppCompatActivity,
    override val di: DI,
) : BasePlayerDelegate, DIAware, ExoMediaSourceInterceptListener {

    companion object {
        private fun getCacheMaxSize(context: Context): Long {
            return try {
                kotlinx.coroutines.runBlocking {
                    SettingPreferences.mapData(context) { prefs ->
                        (prefs[SettingPreferences.PlayerDiskCacheSize] ?: 512).coerceIn(100, 10240)
                    }
                } * 1024L * 1024L
            } catch (e: Exception) {
                1024L * 1024 * 1024 // fallback
            }
        }

        @Volatile
        private var videoCache: SimpleCache? = null

        private fun getCache(context: Context): SimpleCache {
            return videoCache ?: synchronized(this) {
                videoCache ?: SimpleCache(
                    File(context.externalCacheDir ?: context.cacheDir, "video_cache"),
                    LeastRecentlyUsedCacheEvictor(getCacheMaxSize(context)),
                    StandaloneDatabaseProvider(context)
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
    private val userLibraryStore by instance<UserLibraryStore>()
    private val windowStore by instance<WindowStore>()
    private val themeDelegate by instance<ThemeDelegate>()

    private var themeObserver: Observer<Int>? = null
    private var isBroadcastReceiverRegistered = false

    var playerSourceInfo: PlayerSourceInfo? = null

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
        // 音频焦点冲突时不释放播放器（暂停而非杀死，配合"不放生"策略）
        vp.isReleaseWhenLossAudio = false

        // 已有播放内容 → 隐藏加载/错误覆盖层
        if (isPlaying()) {
            loadingBoxController.hideLoading()
            areaLimitBoxController.hide()
            errorMessageBoxController.hide()
            completionBoxController.hide()
        }
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

    /** 保存当前播放位置到 PlaybackService，供 Activity 重建/断点续播使用 */
    private fun savePlaybackPosition() {
        val p = views.videoPlayer
        val pos = p?.let { it.gsyVideoManager?.currentPosition } ?: 0L
        val sid = playerSource?.id ?: ""
        if (pos > 0L && sid.isNotEmpty()) {
            val url = playerSourceInfo?.url ?: ""
            val header = playerSourceInfo?.header ?: emptyMap()
            PlaybackService.instance?.savePlaybackState(pos, sid, url, header)
        }
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
        val controllerFuture = MediaController.Builder(activity, sessionToken).buildAsync()
        controllerFuture.addListener({
            PlaybackService.instance?.setPlayerDelegate(this@PlayerDelegate2)
        }, MoreExecutors.directExecutor())
    }

    override fun onResume() {
        // 从 PiP / 后台回来时更新 PiP 动作按钮（播放/暂停图标同步当前状态）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val player = views.videoPlayer
            if (player != null && activity.isInPictureInPictureMode) {
                picInPicHelper?.updatePictureInPictureActions(player.currentState)
            }
        }
    }

    override fun onPause() {
    }

    override fun onStart() {
        // 不在后台播放模式时恢复播放（和原版哔哩猫一致）
        // GSY 内部 onVideoResume 会自动 seek 到 onVideoPause 时保存的 mCurrentPosition
        if (!controller.isBackgroundPlay
            && views.videoPlayer?.isInPlayingState == true) {
            views.videoPlayer?.onVideoResume()
        }
    }

    override fun onStop() {
        // 保存播放位置
        savePlaybackPosition()
        // 不在后台播放模式时暂停播放（GSY 内部 onVideoPause 保存 mCurrentPosition）
        if (!controller.isBackgroundPlay
            && views.videoPlayer?.isInPlayingState == true) {
            views.videoPlayer?.onVideoPause()
        }
        // 尝试进入 PiP
        if (controller.isPipOnBackground && isOpened()) {
            tryEnterPipOnBackground()
        }
    }

    override fun onDestroy() {
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
        playerCoroutineScope.onDestroy()
        try {
            activity.unregisterReceiver(broadcastReceiver)
            isBroadcastReceiverRegistered = false
        } catch (e: IllegalArgumentException) {
            // 接收器未注册，忽略
        }
        // 只 detach View，不清除 playerSource / playerSourceInfo
        // GSYVideoManager 单例中的 ExoPlayer 保持存活
        views.videoPlayer?.detachView()
    }

    override fun onBackPressed(): Boolean {
        val p = views.videoPlayer ?: return false
        if (p.isLock) {
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

        // 释放播放器（GSY 内部走完整释放链路，含音频焦点回收）
        // 下次 openPlayer 走 setUp 重建播放器
        vp?.releaseDanmaku()
        vp?.hideExpandButton()
        vp?.release()
        MainUi.clearKeepPlayerView()
        lastPosition = 0L

        // ★ 清理通知栏和媒体会话：释放 ExoPlayer → 停止前台 → 回收音频焦点
        PlaybackService.instance?.notifyPlaybackComplete()

        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @OptIn(markerClass = [UnstableApi::class])
    private fun initPlayer() {
        BilimiaoPlayerManager.initConfig()
        GSYVideoType.setRenderType(GSYVideoType.TEXTURE);
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
        return when (dataSourceArr[0]) {
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
                // 音视频分离
                val dataSourceFactory = createCachedFactory(activity, userAgent, header)
                val videoMedia = MediaItem.Builder().apply {
                    setUri(dataSourceArr[1])
                    mediaMetadata?.let(::setMediaMetadata)
                }.build()
                val audioMedia = MediaItem.Builder().apply {
                    setUri(dataSourceArr[2])
                    mediaMetadata?.let(::setMediaMetadata)
                }.build()
                MergingMediaSource(
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(videoMedia),
                    ProgressiveMediaSource.Factory(dataSourceFactory)
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
        // 存到 PlaybackService（Activity重建后可恢复，无需等B站API同步）
        savePlaybackPosition()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            playerSource?.historyReport(progressSec)
            // 同步写入本地缓存，方便本地下载视频续播读取
            val source = playerSource ?: return@launch
            val ids = source.getSourceIds()
            if (ids.aid.isNotBlank() && ids.cid.isNotBlank()) {
                activity.getPreferences(android.content.Context.MODE_PRIVATE)
                    .edit().putLong("dl_${ids.aid}_${ids.cid}", progressSec).apply()
            }
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
                } catch (e: Exception) {
                    quality = previousQuality  // 切换失败回退
                    PopTip.show("清晰度切换失败").showTop()
                }
            }
        }
    }

    suspend fun loadPlayerSource(
        isChangedQuality: Boolean = false
    ) {
        // 新视频开始播放，重置退出标志
        controller.explicitExitSmallWindow = false
        val source = playerSource ?: return
        try {
            // ───── 快速重连：PlaybackService 有同一视频的缓存 URL → 跳过网络请求 ─────
            val quickUrl = PlaybackService.instance?.getSavedUrl(source.id) ?: ""
            if (!isChangedQuality && quickUrl.isNotEmpty() && lastPosition > 0L) {
                loadingBoxController.println("断点续播（缓存命中）")
                // 跳过 setUp()：ExoPlayer 仍在后台存活，MediaSource/Surface 未释放
                // 直接 seek + resume，解码器/渲染器/Manifest 全部复用，真正秒播
                player?.seekOnStart = lastPosition
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
                source.getPlayerUrl(quality, fnval)
            }
            if (playerClosed) return
            quality = sourceInfo.quality
            playerSourceInfo = sourceInfo
            loadingBoxController.print("成功")
            player?.releaseDanmaku()
            player?.danmakuParser = danmukuParser
            player?.setUp(
                sourceInfo.url,
                false,
                null,
                sourceInfo.header,
                source.title
            )
            loadingBoxController.hideLoading()
            if (lastPosition > 0L) {
                player?.seekOnStart = lastPosition
                lastPosition = 0L
            } else if (
                sourceInfo.lastPlayCid == source.id
                && !source.isLoop // 循环的视频不恢复播放
                && sourceInfo.lastPlayTime > 0L
                && (sourceInfo.duration <= 0L || sourceInfo.lastPlayTime < sourceInfo.duration - 10000)
            ) {
                player?.seekOnStart = sourceInfo.lastPlayTime
                lastPosition = 0L
                val lastTimeStr = NumberUtil.converDuration(sourceInfo.lastPlayTime / 1000)
                controller.postPrepared(sourceInfo.lastPlayCid) {
                    PopTip.show("自动恢复:$lastTimeStr", "重新开始")
                        .showTop()
                        .showLong()
                        .setButton { dialog, v ->
                            player?.startPlayLogic()
                            false
                        }
                }
            } else if (sourceInfo.lastPlayCid == source.id) {
                // 从进度0开始记录播放历史
                historyReport(0L)
            }
            lastReportProgress = 0L
            player?.setLooping(source.isLoop)
            player?.startPlayLogic()
            player?.requestLayout()

            if (isChangedQuality) {
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
            }
        } catch (e: DabianException) {
            errorMessageBoxController.show("少儿不宜，禁止观看", canRetry = false)
        } catch (e: AreaLimitException) {
            (playerSource as? BangumiPlayerSource)?.let {
                areaLimitBoxController.show(it)
            } ?: errorMessageBoxController.show("抱歉你所在的地区不可观看！")
        } catch (e: UnknownHostException) {
            errorMessageBoxController.show("网络请求失败")
        } catch (e: Exception) {
            e.printStackTrace()
            errorMessageBoxController.show(e.message ?: e.toString())
        }
    }


    /**
     * 加载字幕数据
     */
    private fun loadSubtitleData(subtitleUrl: String) {
        if (subtitleUrl.isBlank()) {
            return
        }
        playerCoroutineScope.launch(Dispatchers.IO) {
            try {
                val res = MiaoHttp.request {
                    url = UrlUtil.autoHttps(subtitleUrl)
                }.awaitCall().json<SubtitleJsonInfo>()
                player?.subtitleBody = res.body.map {
                    DanmakuVideoPlayer.SubtitleItemInfo(
                        from = (it.from * 1000).toLong(),
                        to = (it.to * 1000).toLong(),
                        content = it.content,
                    )
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    PopTip.show(e.message.toString()).showTop()
                }
            }
        }
    }


    /**
     * 记录播放位置
     */
    fun reloadPlayer() {
        lastPosition = player?.currentPositionWhenPlaying ?: 0L
        playerCoroutineScope.launch(Dispatchers.Main) {
            playerSource?.defaultPlayerSource?.let {
                it.lastPlayCid = ""
                it.lastPlayTime = 0L
            }
            loadPlayerSource()
        }
    }

    override fun openPlayer(source: BasePlayerSource) {
        playerClosed = false
        loadingBoxController.showLoading(source.title, source.coverUrl)
        loadingBoxController.print("初始化播放器...")
        completionBoxController.hide()
        errorMessageBoxController.hide()
        areaLimitBoxController.hide()
        lastPosition = PlaybackService.instance?.getSavedPosition(source.id) ?: 0L
        // 循环播放或预设跳转位置（空降/恢复播放）时，清除 lastPosition 从头开始
        if (source.isLoop || source.defaultPlayerSource.lastPlayTime > 0L) {
            lastPosition = 0L
        }
        // 不同视频 → 释放旧播放器；同一视频 → 保留走快速重连
        if (playerSource != null && playerSource!!.id != source.id) {
            views.videoPlayer?.release()
            playerCoroutineScope.onDestroy()
            playerSource = null
        }
        playerCoroutineScope.onCreate()
        playerSource = source
        controller.resetAutoFullScreenCheck()
        scaffoldApp.showPlayer = true
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadingBoxController.println("成功")
        // 玩家视图由 ScaffoldView.updateLayout() 异步创建，必须用 post 等待布局完成
        scaffoldApp.post {
            if (playerClosed || activity.isFinishing) return@post
            setThumbImageView(source.coverUrl)
            playerCoroutineScope.launch(Dispatchers.Main) {
                SettingPreferences.getData(activity) {
                    fnval = it[PlayerFnval] ?: SettingConstants.PLAYER_FNVAL_DASH
                    // 番剧/影视强制使用 MP4 源：fnval 必须为 MP4，否则 API 返回的
                    // durl 是 DASH 小分段 → ConcatenatingMediaSource 多段缓存 → OOM
                    if (source is BangumiPlayerSource) {
                        fnval = SettingConstants.PLAYER_FNVAL_MP4
                    }
                    quality = it[PlayerQuality] ?: 64
                    speed = it[PlayerSpeed] ?: 1f
                    showNotification = it[PlayerNotification] ?: true
                }
                // ───── CDN 设置 ─────
                source.cdnRaceEnabled = SettingPreferences.mapData(activity) { prefs ->
                    prefs[SettingPreferences.CdnRaceEnabled] ?: false
                }
                source.audioIndependentCdn = SettingPreferences.mapData(activity) { prefs ->
                    prefs[SettingPreferences.AudioIndependentCdn] ?: false
                }
                val selectedHost = SettingPreferences.mapData(activity) { prefs ->
                    prefs[SettingPreferences.SelectedCdnHost] ?: "default"
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
                if (speed != 1f) {
                    PopTip.show("注意，当前为${speed}倍速").showTop()
                }
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

    override fun sendDanmaku(
        type: Int,
        danmakuText: String,
        danmakuTextSize: Float,
        danmakuTextColor: Int,
        danmakuPosition: Long,
    ) {
        val dispDensity = activity.resources.displayMetrics.density
        // 先恢复播放，确保 danmaku view 处于活跃状态再添加弹幕。
        // 否则 addDanmaku 发出的 NOTIFY_RENDERING 会被后面的
        // start(position) 中 handler.removeCallbacksAndMessages(null) 清除，
        // 导致弹幕即使已在 danmakuList 中也无法立即渲染。
        if (!isPlaying()) {
            player?.onVideoResume()
        }
        // 使用实时的 currentPosition（恢复播放后已更新），而非调用方在 API 请求前捕获的过期值
        val currentPosition = player?.currentPosition ?: danmakuPosition
        val danmaku = controller.createDanmaku(type).apply {
            text = danmakuText
            textColor = danmakuTextColor
            textSize = danmakuTextSize * (dispDensity - 0.6f);
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
            val aspectRatio = if (height == null || width == null) {
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
        views.videoPlayer?.onVideoResume(false)
    }

    override fun mediaPause() {
        views.videoPlayer?.onVideoPause()
    }

    override fun mediaSeekTo(position: Long) {
        views.videoPlayer?.seekTo(position)
    }

    override fun mediaSeekBack() {
        val p = views.videoPlayer ?: return
        val target = (p.currentPositionWhenPlaying - 10000).coerceAtLeast(0)
        p.seekTo(target)
    }

    override fun mediaSeekForward() {
        val p = views.videoPlayer ?: return
        val dur = p.duration
        val target = (p.currentPositionWhenPlaying + 10000).coerceAtMost(dur)
        p.seekTo(target)
    }

    override fun mediaGetDuration(): Long {
        return views.videoPlayer?.duration ?: 0L
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
}