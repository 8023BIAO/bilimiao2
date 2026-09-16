package com.a10miaomiao.bilimiao.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.view.KeyEvent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.a10miaomiao.bilimiao.MainActivity
import com.a10miaomiao.bilimiao.R
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences.dataStore
import com.a10miaomiao.bilimiao.comm.delegate.player.BasePlayerDelegate
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class ControlMode(val label: String) {
    EPISODE("上/下集"),
    CHAPTER("章节"),
    SEEK("±10秒"),
}

class PlaybackService : MediaSessionService(), MediaSession.Callback {

    companion object {
        var instance: PlaybackService? = null
            private set
        const val CHANNEL_ID = "bilimiao_playback"
        const val ACTION_SEEK_BACK = "seek_back"
        const val ACTION_SEEK_FORWARD = "seek_forward"
        const val ACTION_PLAY_PAUSE = "play_pause"
    }

    private var exoPlayer: Player? = null
    private var mediaSession: MediaSession? = null
    private var playerDelegate: BasePlayerDelegate? = null

    // 播放位置暂存（Service 存活期 + 进程重启前有效）
    private var savedPosition: Long = 0L
    private var savedSourceId: String = ""
    private var savedUrl: String = ""
    private var savedHeader: Map<String, String> = emptyMap()
    // 播放源信息（Activity 重建后可恢复）
    private var savedSourceTitle: String = ""
    private var savedSourceCover: String = ""
    private var savedSourceType: String = "" // "video" | "bangumi" | ""

    fun savePlaybackState(position: Long, sourceId: String, url: String, header: Map<String, String>) {
        savedPosition = position
        savedSourceId = sourceId
        savedUrl = url
        savedHeader = header
    }

    fun saveSourceInfo(title: String, cover: String, type: String) {
        savedSourceTitle = title
        savedSourceCover = cover
        savedSourceType = type
    }

    fun getSavedSourceInfo() = SavedSourceInfo(
        title = savedSourceTitle,
        cover = savedSourceCover,
        type = savedSourceType,
    )

    data class SavedSourceInfo(
        val title: String,
        val cover: String,
        val type: String,
    )

    fun getSavedPosition(sourceId: String): Long {
        return if (sourceId == savedSourceId) savedPosition else 0L
    }

    fun getSavedUrl(sourceId: String): String {
        return if (sourceId == savedSourceId) savedUrl else ""
    }

    fun getSavedHeader(sourceId: String): Map<String, String> {
        return if (sourceId == savedSourceId) savedHeader else emptyMap()
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var showNotification = true

    /**
     * "后台播放"开关的同步缓存。
     * onTaskRemoved() 是同步回调，不能在里面挂起读 DataStore，所以像 showNotification 一样
     * 用一条 Flow 先把值缓存下来（详见 observeBackgroundPlay()）。
     */
    @Volatile private var backgroundPlayEnabled = false

    // 自定义 SessionCommand — 通知栏按钮走 onCustomCommand 路由
    private val seekBackCmd = SessionCommand("bilimiao.seek_back", Bundle.EMPTY)
    private val seekForwardCmd = SessionCommand("bilimiao.seek_forward", Bundle.EMPTY)
    private val togglePrevNextModeCmd = SessionCommand("bilimiao.toggle_prev_next_mode", Bundle.EMPTY)
    private val togglePlayModeCmd = SessionCommand("bilimiao.toggle_play_mode", Bundle.EMPTY)
    private val prevEpisodeCmd = SessionCommand("bilimiao.prev_episode", Bundle.EMPTY)
    private val nextEpisodeCmd = SessionCommand("bilimiao.next_episode", Bundle.EMPTY)
    private val prevChapterCmd = SessionCommand("bilimiao.prev_chapter", Bundle.EMPTY)
    private val nextChapterCmd = SessionCommand("bilimiao.next_chapter", Bundle.EMPTY)

    /** 收摊重入保护：teardownSession 里会回调 delegate（delegate 又可能回头通知服务） */
    @Volatile private var tearingDown = false

    /** 快进/快退步长（秒）：跟随"快进/快退步长"设置，通知栏按钮的文字与图标一起变 */
    private var seekStepSec = 10

    /** 取对应秒数的图标；media3 只自带 5/10/15/30，其它值退化成不带数字的箭头 */
    private fun seekIconRes(forward: Boolean, sec: Int): Int = when (sec) {
        5 -> if (forward) R.drawable.media3_icon_skip_forward_5 else R.drawable.media3_icon_skip_back_5
        15 -> if (forward) R.drawable.media3_icon_skip_forward_15 else R.drawable.media3_icon_skip_back_15
        30 -> if (forward) R.drawable.media3_icon_skip_forward_30 else R.drawable.media3_icon_skip_back_30
        10 -> if (forward) R.drawable.media3_icon_skip_forward_10 else R.drawable.media3_icon_skip_back_10
        else -> if (forward) R.drawable.media3_icon_skip_forward else R.drawable.media3_icon_skip_back
    }

    private fun buildSeekBackButton() = CommandButton.Builder()
        .setDisplayName("后退${seekStepSec}秒")
        .setIconResId(seekIconRes(forward = false, sec = seekStepSec))
        .setSessionCommand(seekBackCmd)
        .build()

    private fun buildSeekForwardButton() = CommandButton.Builder()
        .setDisplayName("前进${seekStepSec}秒")
        .setIconResId(seekIconRes(forward = true, sec = seekStepSec))
        .setSessionCommand(seekForwardCmd)
        .build()

    private val prevEpisodeButton = CommandButton.Builder()
        .setDisplayName("上一集")
        .setIconResId(R.drawable.media3_icon_previous)
        .setSessionCommand(prevEpisodeCmd)
        .build()

    private val nextEpisodeButton = CommandButton.Builder()
        .setDisplayName("下一集")
        .setIconResId(R.drawable.media3_icon_next)
        .setSessionCommand(nextEpisodeCmd)
        .build()

    private val prevChapterButton = CommandButton.Builder()
        .setDisplayName("上一章节")
        .setIconResId(R.drawable.media3_icon_previous_chapter)
        .setSessionCommand(prevChapterCmd)
        .build()

    private val nextChapterButton = CommandButton.Builder()
        .setDisplayName("下一章节")
        .setIconResId(R.drawable.media3_icon_next_chapter)
        .setSessionCommand(nextChapterCmd)
        .build()

    // ========== 播放模式按钮 ==========

    private var currentOrder = SettingConstants.PLAYER_ORDER_DEFAULT
    private var currentRandom = false
    /** 三档控制模式：上/下集 → 章节 → ±10秒 */
    private var controlMode = ControlMode.EPISODE

    /** 根据当前设置构建模式按钮（图标+文字动态变化） */
    private fun buildModeButton(order: Int, random: Boolean): CommandButton {
        val nextBits = SettingConstants.PLAYER_ORDER_NEXT_P or
                SettingConstants.PLAYER_ORDER_NEXT_VIDEO or
                SettingConstants.PLAYER_ORDER_NEXT_EPISODE
        val hasLoop = (order and SettingConstants.PLAYER_ORDER_LOOP) != 0
        val hasNext = (order and nextBits) != 0
        val (iconRes, displayName) = when {
            random -> R.drawable.media3_icon_shuffle_on to "随机播放"
            hasLoop && !hasNext -> R.drawable.media3_icon_repeat_one to "单曲循环"
            hasLoop && hasNext -> R.drawable.media3_icon_repeat_all to "列表循环"
            else -> R.drawable.media3_icon_repeat_off to "自动播放"
        }
        return CommandButton.Builder()
            .setDisplayName(displayName)
            .setIconResId(iconRes)
            .setSessionCommand(togglePlayModeCmd)
            .build()
    }

    private fun hasAnyEpisode(): Boolean {
        return playerDelegate?.hasPreviousEpisode() == true ||
                playerDelegate?.hasNextEpisode() == true
    }

    /** 当前可用的三档模式（无章节/无上下集时自动退化） */
    private fun availableControlModes(): List<ControlMode> {
        return buildList {
            if (hasAnyEpisode()) add(ControlMode.EPISODE)
            if (playerDelegate?.hasChapters() == true) add(ControlMode.CHAPTER)
            add(ControlMode.SEEK)
        }
    }

    /** 第一个按钮：上一集 → 上一章 → 后退10秒，逐级退化 */
    private fun buildBackButton(): CommandButton {
        return when (controlMode) {
            ControlMode.EPISODE -> when {
                playerDelegate?.hasPreviousEpisode() == true -> prevEpisodeButton
                playerDelegate?.hasChapters() == true -> prevChapterButton
                else -> buildSeekBackButton()
            }
            ControlMode.CHAPTER -> {
                if (playerDelegate?.hasChapters() == true) prevChapterButton else buildSeekBackButton()
            }
            ControlMode.SEEK -> buildSeekBackButton()
        }
    }

    /** 第二个按钮：下一集 → 下一章 → 前进10秒，逐级退化 */
    private fun buildForwardButton(): CommandButton {
        return when (controlMode) {
            ControlMode.EPISODE -> when {
                playerDelegate?.hasNextEpisode() == true -> nextEpisodeButton
                playerDelegate?.hasChapters() == true -> nextChapterButton
                else -> buildSeekForwardButton()
            }
            ControlMode.CHAPTER -> {
                if (playerDelegate?.hasChapters() == true) nextChapterButton else buildSeekForwardButton()
            }
            ControlMode.SEEK -> buildSeekForwardButton()
        }
    }

    /** 第三个切换按钮：位置不变，仅显示当前模式名 */
    private fun buildToggleControlModeButton(): CommandButton {
        return CommandButton.Builder()
            .setDisplayName(controlMode.label)
            .setIconResId(R.drawable.media3_icon_sync)
            .setSessionCommand(togglePrevNextModeCmd)
            .build()
    }

    /** 构建 4 按钮布局：前两个按三档模式替换；单视频时隐藏第三个切换按钮 */
    private fun buildCustomLayout(order: Int, random: Boolean): ImmutableList<CommandButton> {
        val modeButton = buildModeButton(order, random)
        val first = buildBackButton()
        val second = buildForwardButton()
        // 只要有 ≥2 档可用模式就显示切换按钮：
        // 单视频没有上下集但有章节时，用户仍需要在“章节 ↔ ±10秒”之间切换。
        val third = if (availableControlModes().size > 1) {
            buildToggleControlModeButton()
        } else {
            null
        }
        return if (third != null) {
            ImmutableList.of(first, second, third, modeButton)
        } else {
            ImmutableList.of(first, second, modeButton)
        }
    }

    /** 切换到下一档可用模式 */
    private fun cycleControlMode() {
        val modes = availableControlModes()
        if (modes.isEmpty()) return
        val index = modes.indexOf(controlMode)
        controlMode = if (index >= 0) {
            modes[(index + 1) % modes.size]
        } else {
            modes.first()
        }
        refreshNotification()
    }

    /** 刷新通知栏按钮列表（章节拉取成功/切换视频后也会调用） */
    fun refreshNotification() {
        val modes = availableControlModes()
        if (modes.isNotEmpty() && controlMode !in modes) {
            controlMode = modes.first()
        }
        mediaSession?.setCustomLayout(buildCustomLayout(currentOrder, currentRandom))
    }

    /** 计算下一个模式 */
    private fun nextMode(order: Int, random: Boolean): Pair<Int, Boolean> {
        val nextBits = SettingConstants.PLAYER_ORDER_NEXT_P or
                SettingConstants.PLAYER_ORDER_NEXT_VIDEO or
                SettingConstants.PLAYER_ORDER_NEXT_EPISODE
        val hasLoop = (order and SettingConstants.PLAYER_ORDER_LOOP) != 0
        val hasNext = (order and nextBits) != 0

        // 当前模式: 0=单曲循环 1=列表循环 2=自动播放 3=随机
        val currentMode = when {
            random -> 3
            hasLoop && !hasNext -> 0
            hasLoop && hasNext -> 1
            else -> 2
        }

        // 下一个模式
        return when ((currentMode + 1) % 4) {
            0 -> SettingConstants.PLAYER_ORDER_LOOP to false
            1 -> (SettingConstants.PLAYER_ORDER_LOOP or nextBits) to false
            2 -> nextBits to false
            3 -> nextBits to true
            else -> nextBits to false
        }
    }

    /** 轮换播放模式：读设置 → 算下一个 → 写回 → 刷新图标 */
    private fun cyclePlayMode() {
        serviceScope.launch {
            val (newOrder, newRandom) = SettingPreferences.mapData(this@PlaybackService) { prefs ->
                val order = prefs[SettingPreferences.PlayerOrder] ?: SettingConstants.PLAYER_ORDER_DEFAULT
                val random = prefs[SettingPreferences.PlayerOrderRandom] ?: false
                nextMode(order, random)
            }
            SettingPreferences.edit(this@PlaybackService) { prefs ->
                prefs[SettingPreferences.PlayerOrder] = newOrder
                prefs[SettingPreferences.PlayerOrderRandom] = newRandom
            }
            refreshNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        initializeSession()
        serviceScope.launch { initPlayerSetting() }
        serviceScope.launch { observeBackgroundPlay() }
        serviceScope.launch { observeSeekStep() }
        observePlayMode()
    }

    override fun onGetSession(ci: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            "bilimiao.seek_back" -> {
                playerDelegate?.mediaSeekBack()
                refreshNotification()
            }
            "bilimiao.seek_forward" -> {
                playerDelegate?.mediaSeekForward()
                refreshNotification()
            }
            "bilimiao.toggle_prev_next_mode" -> cycleControlMode()
            "bilimiao.toggle_play_mode" -> cyclePlayMode()
            "bilimiao.prev_episode" -> {
                playerDelegate?.mediaPlayPrevious()
                refreshNotification()
            }
            "bilimiao.next_episode" -> {
                playerDelegate?.mediaPlayNext()
                refreshNotification()
            }
            "bilimiao.prev_chapter" -> {
                playerDelegate?.mediaSeekToPreviousChapter()
                refreshNotification()
            }
            "bilimiao.next_chapter" -> {
                playerDelegate?.mediaSeekToNextChapter()
                refreshNotification()
            }
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    /** 上一项：上一集 → 上一章 → 后退10秒 */
    private fun onPreviousControl() {
        when (controlMode) {
            ControlMode.EPISODE -> when {
                playerDelegate?.hasPreviousEpisode() == true -> playerDelegate?.mediaPlayPrevious()
                playerDelegate?.hasChapters() == true -> playerDelegate?.mediaSeekToPreviousChapter()
                else -> playerDelegate?.mediaSeekBack()
            }
            ControlMode.CHAPTER -> {
                if (playerDelegate?.hasChapters() == true) {
                    playerDelegate?.mediaSeekToPreviousChapter()
                } else {
                    playerDelegate?.mediaSeekBack()
                }
            }
            ControlMode.SEEK -> playerDelegate?.mediaSeekBack()
        }
        refreshNotification()
    }

    /** 下一项：下一集 → 下一章 → 前进10秒 */
    private fun onNextControl() {
        when (controlMode) {
            ControlMode.EPISODE -> when {
                playerDelegate?.hasNextEpisode() == true -> playerDelegate?.mediaPlayNext()
                playerDelegate?.hasChapters() == true -> playerDelegate?.mediaSeekToNextChapter()
                else -> playerDelegate?.mediaSeekForward()
            }
            ControlMode.CHAPTER -> {
                if (playerDelegate?.hasChapters() == true) {
                    playerDelegate?.mediaSeekToNextChapter()
                } else {
                    playerDelegate?.mediaSeekForward()
                }
            }
            ControlMode.SEEK -> playerDelegate?.mediaSeekForward()
        }
        refreshNotification()
    }

    override fun onMediaButtonEvent(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        intent: Intent,
    ): Boolean {
        if (intent.action == Intent.ACTION_MEDIA_BUTTON) {
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (event != null && event.action == KeyEvent.ACTION_UP) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        onPreviousControl()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        onNextControl()
                        return true
                    }
                }
            }
        }
        return super.onMediaButtonEvent(session, controller, intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                if (playerDelegate?.isPlaying() == true) {
                    playerDelegate?.mediaPause()
                } else {
                    playerDelegate?.mediaPlay()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            .add(seekBackCmd)
            .add(seekForwardCmd)
            .add(togglePrevNextModeCmd)
            .add(togglePlayModeCmd)
            .add(prevEpisodeCmd)
            .add(nextEpisodeCmd)
            .add(prevChapterCmd)
            .add(nextChapterCmd)
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setCustomLayout(buildCustomLayout(currentOrder, currentRandom))
            .setAvailableSessionCommands(commands)
            .build()
    }

    private fun initializeSession() {
        val dummy = MyForwardingPlayer(ExoPlayer.Builder(this).build())
        val intent = Intent(this, MainActivity::class.java)
        val pIntent = PendingIntent.getActivity(
            this, 4, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, dummy)
            .setCallback(this)
            .setSessionActivity(pIntent)
            .setCustomLayout(buildCustomLayout(currentOrder, currentRandom))
            .build()
    }

    fun setPlayer(player: ExoPlayer) {
        // 释放旧 session player（可能是 dummy 或 ForwardingPlayer 包装的旧 exoPlayer）
        mediaSession?.player?.let { old ->
            try { old.release() } catch (e: Exception) { miaoLogger() error "release old player failed: ${e.message}" }
        }
        exoPlayer?.release()
        exoPlayer = player
        if (showNotification) {
            mediaSession?.player = MyForwardingPlayer(player)
        }
        refreshSessionIntent()
    }

    fun setPlayerDelegate(delegate: BasePlayerDelegate) {
        playerDelegate = delegate
        refreshSessionIntent()
        refreshNotification()
    }

    /** 播放完成后释放通知栏 + 停止 Service（同样要换掉会话 player，否则通知会被复活） */
    fun notifyPlaybackComplete() {
        exoPlayer?.release()
        exoPlayer = null
        teardownSession()
    }

    /** 根据当前播放内容动态更新通知栏点击跳转目标 */
    private fun refreshSessionIntent() {
        val ids = playerDelegate?.getSourceIds() ?: return
        val uriString = when {
            ids.sid.isNotBlank() -> "bilimiao://bangumi/${ids.sid}"
            ids.epid.isNotBlank() -> "bilimiao://bangumi/${ids.epid}"
            ids.aid.isNotBlank() -> {
                if (ids.cid.isNotBlank())
                    "bilimiao://video/${ids.aid}?cid=${ids.cid}"
                else
                    "bilimiao://video/${ids.aid}"
            }
            else -> return
        }
        val intent = Intent(this, com.a10miaomiao.bilimiao.MainActivity::class.java)
            .setData(Uri.parse(uriString))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pIntent = PendingIntent.getActivity(
            this, 4, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession?.setSessionActivity(pIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "播放控制", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "视频播放控制"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 语义（用户确认）：
        //   开了"后台播放"且确实在播 → 保留 Service（通知栏留着，重进 App 画面接上继续）；
        //   否则彻底收摊（不能只 stopForeground + stopSelf：MediaController 还绑着时服务不会销毁，
        //   会话状态一变通知就被重新 post 回来 —— 这就是"划掉最近任务后通知栏还显示上一个视频、
        //   还能操作，但重进 App 已经没有画面"的原因）。
        // 用户决定（2026-09-16）：不再区分后台播放开关 —— 划掉最近任务就**彻底收摊**。
        // 之前的"保活"太刁钻：后台还在放、通知栏还在，但重进 App 接不上画面，
        // 用户还以为杀掉了其实没杀。现在一律：停播 + 释放播放器 + 撤通知 + 断会话。
        teardownSession()
    }

    /**
     * 设置里关掉"通知栏"时，不要让 MediaSessionService 把通知再 post 回来。
     *
     * 以前是靠"把会话的 player 换成一个裸 ExoPlayer"来阻止 —— 副作用是播放链路被搞死：
     * 用户一关通知栏，视频就暂停且点什么都没反应，只能重进页面/换视频。
     * 这里改用官方钩子，**完全不碰播放器**。
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (!showNotification) {
            return
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    /**
     * 彻底收摊：撤掉前台通知 + 把会话的 player 换成一个没有媒体的空播放器 + 断开 delegate。
     * 换 player 是关键：会话状态随之清空，通知不会再被任何状态变化"复活"。
     */
    private fun teardownSession() {
        if (tearingDown) return
        tearingDown = true
        // 先把真播放器停掉并释放（否则"划掉任务"后声音还在响）
        try {
            playerDelegate?.releasePlayback()
        } catch (e: Exception) {
            miaoLogger() error "teardown releasePlayback failed: ${e.message}"
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        mediaSession?.let { session ->
            val old = session.player
            try {
                session.player = ExoPlayer.Builder(this@PlaybackService).build()
            } catch (e: Exception) {
                miaoLogger() error "teardown swap session player failed: ${e.message}"
            }
            try {
                old.release()
            } catch (e: Exception) {
                miaoLogger() error "teardown release old player failed: ${e.message}"
            }
        }
        // 断开真播放器：服务不再攥着旧 Activity 的 delegate
        playerDelegate = null
        tearingDown = false
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 兜底释放（notifyPlaybackComplete 已清理时 mediaSession 为 null，这里不执行）
        mediaSession?.run {
            try { player.release() } catch (e: Exception) { miaoLogger() error "onDestroy player.release failed: ${e.message}" }
            try { release() } catch (e: Exception) { miaoLogger() error "onDestroy session.release failed: ${e.message}" }
        }
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
        instance = null
        super.onDestroy()
    }

    private suspend fun initPlayerSetting() = SettingPreferences.run {
        // Flow 1: 通知栏开关（仅 PlayerNotification）
        var init = true
        dataStore.data.map { it[PlayerNotification] ?: true }.collect {
            showNotification = it
            if (!init) {
                // DataStore 任意设置变化都会重发同一个值；这里只处理真正的切换。
                // 注意：**不要动会话的 player** —— 换 player 会把播放链路搞卡死
                //（用户报过"关一下通知栏，视频就暂停且点什么都没反应"）。
                // 关掉时靠 onUpdateNotification() 拦 post + 撤掉已有通知即可。
                if (it) {
                                refreshNotification()
                } else {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            .cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
                    }
                }
            }
            init = false
        }
    }

    /** 监听"快进/快退步长"设置：通知栏 ±秒按钮的文字和图标跟着变 */
    private suspend fun observeSeekStep() {
        // 步长设置 0 = 关闭双击快进快退 —— 但通知栏 ± 按钮始终有步长，关闭时用默认 10 秒
        dataStore.data.map {
            val v = it[SettingPreferences.PlayerDoubleTapSeek] ?: 0
            if (v > 0) v else 10
        }.collect { sec ->
            if (seekStepSec != sec) {
                seekStepSec = sec
                refreshNotification()
            }
        }
    }

    /** 监听播放模式变化，同步刷新按钮图标 */
    private fun observePlayMode() {
        serviceScope.launch {
            dataStore.data.map { prefs ->
                Pair(
                    prefs[SettingPreferences.PlayerOrder] ?: SettingConstants.PLAYER_ORDER_DEFAULT,
                    prefs[SettingPreferences.PlayerOrderRandom] ?: false
                )
            }.collect { (order, random) ->
                currentOrder = order
                currentRandom = random
                refreshNotification()
                playerDelegate?.syncPlayMode(order, random)
            }
        }
    }

    /**
     * 缓存"后台播放"开关（SettingPreferences.PlayerBackground）。
     * 划掉最近任务时 onTaskRemoved() 要同步判断"该不该继续留在后台"，不能在里面挂起读 DataStore。
     */
    private suspend fun observeBackgroundPlay() {
        dataStore.data
            .map { it[SettingPreferences.PlayerBackground] ?: false }
            .collect { backgroundPlayEnabled = it }
    }

    @OptIn(UnstableApi::class)
    inner class MyForwardingPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_FORWARD)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()
        }

        override fun play() { playerDelegate?.mediaPlay() }
        override fun pause() { playerDelegate?.mediaPause() }
        override fun seekBack() { playerDelegate?.mediaSeekBack() }
        override fun seekForward() { playerDelegate?.mediaSeekForward() }
        override fun seekTo(positionMs: Long) {
            playerDelegate?.mediaSeekTo(positionMs)
            super.seekTo(positionMs)
        }
        override fun stop() { super.stop(); playerDelegate?.closePlayer() }
    }
}
