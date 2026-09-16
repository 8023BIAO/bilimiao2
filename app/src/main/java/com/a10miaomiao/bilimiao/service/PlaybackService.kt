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

    /** 正在收尾（播放完成/关闭播放器）时不要再刷新通知栏，否则会把已撤下的通知 post 回来 */
    @Volatile private var shuttingDown = false

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

    // 自定义 SessionCommand — 通知栏按钮走 onCustomCommand 路由
    private val seekBackCmd = SessionCommand("bilimiao.seek_back", Bundle.EMPTY)
    private val seekForwardCmd = SessionCommand("bilimiao.seek_forward", Bundle.EMPTY)
    private val togglePrevNextModeCmd = SessionCommand("bilimiao.toggle_prev_next_mode", Bundle.EMPTY)
    private val togglePlayModeCmd = SessionCommand("bilimiao.toggle_play_mode", Bundle.EMPTY)
    private val prevEpisodeCmd = SessionCommand("bilimiao.prev_episode", Bundle.EMPTY)
    private val nextEpisodeCmd = SessionCommand("bilimiao.next_episode", Bundle.EMPTY)
    private val prevChapterCmd = SessionCommand("bilimiao.prev_chapter", Bundle.EMPTY)
    private val nextChapterCmd = SessionCommand("bilimiao.next_chapter", Bundle.EMPTY)

    private val backButton = CommandButton.Builder()
        .setDisplayName("后退10秒")
        .setIconResId(R.drawable.media3_icon_skip_back_10)
        .setSessionCommand(seekBackCmd)
        .build()

    private val forwardButton = CommandButton.Builder()
        .setDisplayName("前进10秒")
        .setIconResId(R.drawable.media3_icon_skip_forward_10)
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
                else -> backButton
            }
            ControlMode.CHAPTER -> {
                if (playerDelegate?.hasChapters() == true) prevChapterButton else backButton
            }
            ControlMode.SEEK -> backButton
        }
    }

    /** 第二个按钮：下一集 → 下一章 → 前进10秒，逐级退化 */
    private fun buildForwardButton(): CommandButton {
        return when (controlMode) {
            ControlMode.EPISODE -> when {
                playerDelegate?.hasNextEpisode() == true -> nextEpisodeButton
                playerDelegate?.hasChapters() == true -> nextChapterButton
                else -> forwardButton
            }
            ControlMode.CHAPTER -> {
                if (playerDelegate?.hasChapters() == true) nextChapterButton else forwardButton
            }
            ControlMode.SEEK -> forwardButton
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
        shuttingDown = false
        createNotificationChannel()
        initializeSession()
        serviceScope.launch { initPlayerSetting() }
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
                refreshPlaybackState()
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

    @OptIn(UnstableApi::class)
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
        // shuttingDown 是"本次播放收尾"的闩锁，不能只靠 onCreate 复位：
        // stopSelf() 在仍有 MediaController 绑定时不会销毁 Service，
        // 一旦闩锁留着 true，之后所有状态刷新都会静默失效（通知栏永久不更新）
        shuttingDown = false
        playerDelegate = delegate
        refreshSessionIntent()
        refreshNotification()
        refreshPlaybackState()
    }

    /**
     * 通知栏/锁屏/蓝牙的状态刷新。
     *
     * 会话只在 player 派发事件时才会重新读取播放状态，而这里挂的是"空壳 ExoPlayer"
     * （MyForwardingPlayer 覆写了状态 getter，空壳自己永远不会发事件）→ 真播放器
     * 播放/暂停后通知栏一直停在旧状态：图标不翻、进度条按旧状态空转、按钮点了像没反应。
     * 所以真播放器每次状态变化都要主动调一次这里。
     */
    fun refreshPlaybackState() {
        if (!showNotification || shuttingDown) return
        val wrapper = mediaSession?.player as? MyForwardingPlayer
        if (wrapper == null) {
            // 会话此刻挂的不是转发壳（通知栏关闭时的裸 ExoPlayer / setPlayer 路径）→ 静默跳过
            miaoLogger() debug "refreshPlaybackState skipped: session player is not MyForwardingPlayer"
            return
        }
        wrapper.refreshState()
    }

    /** 播放完成后释放通知栏 + 停止 Service */
    fun notifyPlaybackComplete() {
        shuttingDown = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        exoPlayer?.release()
        exoPlayer = null
        stopSelf()
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
        // 注意：exoPlayer 恒为 null（setPlayer() 全仓无调用者），会话挂的又是没有媒体项的
        // 空壳播放器 —— 都不能用来判断"还在不在播"。原来恒判"该停"→ 从最近任务划掉 App 时
        // 会话被释放，可真播放器还活在 GSYVideoManager 里继续出声，通知栏却没了、也控制不了。
        // 这里直接问真播放器（delegate），播放中就保留 Service（后台播放语义）。
        val playing = playerDelegate?.isPlaying() == true
        if (!playing) {
            shuttingDown = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
                // DataStore 任意设置变化都会重发同一个值，先判断是否真的发生了切换，
                // 否则每次改别的设置都会重建（并泄漏）一个 ExoPlayer
                if (it) {
                    mediaSession?.let { session ->
                        if (session.player !is MyForwardingPlayer) {
                            // 重新打开：exoPlayer 恒为 null（setPlayer() 全仓无调用者），
                            // 旧代码这里什么都不做 → 开关"关→开"后通知栏/线控永久失效。
                            // 与 initializeSession() 一致，重建转发到 delegate 的 player
                            val old = session.player
                            session.player = MyForwardingPlayer(
                                ExoPlayer.Builder(this@PlaybackService).build()
                            )
                            // MediaSession 不负责释放 player，旧的空 ExoPlayer 要自己释放
                            try { old.release() } catch (e: Exception) { miaoLogger() error "release old session player failed: ${e.message}" }
                        }
                    }
                } else {
                    mediaSession?.let { session ->
                        if (session.player is MyForwardingPlayer) {
                            val old = session.player
                            session.player = ExoPlayer.Builder(this@PlaybackService).build()
                            // 转发 player 只是包装，释放它即释放底层 ExoPlayer
                            try { old.release() } catch (e: Exception) { miaoLogger() error "release old session player failed: ${e.message}" }
                        }
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
            init = false
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

    @OptIn(UnstableApi::class)
    inner class MyForwardingPlayer(private val dummy: Player) : ForwardingPlayer(dummy) {
        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands().buildUpon()
                // 空壳 ExoPlayer 的命令集里没有这些，通知栏就不会渲染播放/暂停按钮与元数据
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_TIMELINE)
                .add(Player.COMMAND_GET_METADATA)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_FORWARD)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()
        }

        /**
         * 强制会话重新读取下面这些被覆写的状态。
         *
         * 底层是空壳 ExoPlayer：状态 getter 虽然转发到了真播放器，但会话只在 player
         * 派发事件时才重新读取 —— 空壳自己永远不会发事件，于是通知栏/锁屏一直停在旧状态。
         * 这里用一次反向 play/pause 拨动制造事件（空壳没有媒体项，不会真的出声）。
         */
        fun refreshState() {
            // 只拨动"空壳"：一旦哪天 setPlayer() 真的把真播放器挂到会话上（mediaItemCount>0），
            // 反向 play/pause 会真的把视频拨一下（瞬时停顿），必须直接返回
            if (dummy.mediaItemCount > 0) return
            if (playerDelegate?.isPlaying() == true) {
                dummy.pause()
                dummy.play()
            } else {
                dummy.play()
                dummy.pause()
            }
        }

        // ── 状态/元数据转发 ──
        // 会话实际绑的是 dummy(空壳 ExoPlayer)，只有命令被转发到真播放器是不够的：
        // 状态与元数据不转发的话，通知栏/锁屏/蓝牙永远显示"播放"图标、没有标题封面、
        // 暂停键按下去也不会变成暂停（真播放器仍在播）。
        // 播放完成后必须报 STATE_ENDED：否则系统一直以为还在播，
        // 通知栏停在"播放中"、进度条还会继续空转
        override fun getPlaybackState(): Int =
            if (playerDelegate?.isCompleted() == true) Player.STATE_ENDED else Player.STATE_READY
        override fun getPlayWhenReady(): Boolean = playerDelegate?.isPlaying() == true
        override fun isPlaying(): Boolean = playerDelegate?.isPlaying() == true
        override fun getCurrentPosition(): Long = playerDelegate?.currentPosition() ?: 0L
        override fun getDuration(): Long = playerDelegate?.mediaGetDuration() ?: 0L
        override fun getMediaMetadata(): androidx.media3.common.MediaMetadata {
            val builder = androidx.media3.common.MediaMetadata.Builder()
            playerDelegate?.mediaGetTitle()?.let { builder.setTitle(it) }
            playerDelegate?.mediaGetSubtitle()?.let { builder.setArtist(it) }
            // 只要 uri（Media3 自己会用 DataSourceBitmapLoader 去取图）。
            // 注意必须是带 scheme 的绝对地址，协议相对的 "//i0.hdslb.com/..." 加载不出来
            playerDelegate?.mediaGetCoverUrl()?.let {
                runCatching { builder.setArtworkUri(android.net.Uri.parse(it)) }
            }
            return builder.build()
        }

        override fun play() { playerDelegate?.mediaPlay() }
        override fun pause() { playerDelegate?.mediaPause() }
        override fun seekBack() { playerDelegate?.mediaSeekBack() }
        override fun seekForward() { playerDelegate?.mediaSeekForward() }
        override fun seekTo(positionMs: Long) {
            playerDelegate?.mediaSeekTo(positionMs)
            super.seekTo(positionMs)
            // 拖完进度条要立刻把新位置推给系统 UI，否则它会按旧位置继续"空转"
            refreshState()
        }
        override fun stop() { super.stop(); playerDelegate?.closePlayer() }
    }
}
