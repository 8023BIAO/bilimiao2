package com.a10miaomiao.bilimiao.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

    // 自定义 SessionCommand — 通知栏按钮走 onCustomCommand 路由
    private val seekBackCmd = SessionCommand("bilimiao.seek_back", Bundle.EMPTY)
    private val seekForwardCmd = SessionCommand("bilimiao.seek_forward", Bundle.EMPTY)
    private val closeCmd = SessionCommand("bilimiao.close", Bundle.EMPTY)
    private val togglePlayModeCmd = SessionCommand("bilimiao.toggle_play_mode", Bundle.EMPTY)

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

    private val closeButton = CommandButton.Builder()
        .setDisplayName("关闭")
        .setIconResId(R.drawable.media3_icon_stop)
        .setSessionCommand(closeCmd)
        .build()

    // ========== 播放模式按钮 ==========

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

    /** 刷新通知栏按钮列表（图标随模式变化） */
    private fun refreshCustomLayout(order: Int, random: Boolean) {
        val modeButton = buildModeButton(order, random)
        mediaSession?.setCustomLayout(ImmutableList.of(backButton, forwardButton, closeButton, modeButton))
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
            refreshCustomLayout(newOrder, newRandom)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
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
            "bilimiao.seek_back" -> playerDelegate?.mediaSeekBack()
            "bilimiao.seek_forward" -> playerDelegate?.mediaSeekForward()
            "bilimiao.close" -> {
                playerDelegate?.closePlayer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            "bilimiao.toggle_play_mode" -> cyclePlayMode()
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
            .add(closeCmd)
            .add(togglePlayModeCmd)
            .build()
        val defaultOrder = SettingConstants.PLAYER_ORDER_DEFAULT
        val modeButton = buildModeButton(defaultOrder, false)
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setCustomLayout(ImmutableList.of(backButton, forwardButton, closeButton, modeButton))
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
        val defaultOrder = SettingConstants.PLAYER_ORDER_DEFAULT
        val modeButton = buildModeButton(defaultOrder, false)
        mediaSession = MediaSession.Builder(this, dummy)
            .setCallback(this)
            .setSessionActivity(pIntent)
            .setCustomLayout(ImmutableList.of(backButton, forwardButton, closeButton, modeButton))
            .build()
    }

    fun setPlayer(player: ExoPlayer) {
        // 释放旧 session player（可能是 dummy 或 ForwardingPlayer 包装的旧 exoPlayer）
        mediaSession?.player?.let { old ->
            try { old.release() } catch (_: Exception) {}
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
    }

    /** 播放完成后释放通知栏 + 停止 Service */
    fun notifyPlaybackComplete() {
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
        val shouldStop = exoPlayer?.let { p ->
            !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED
        } ?: true  // exoPlayer 为 null = 播放器已关闭 → 停止 Service
        if (shouldStop) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 兜底释放（notifyPlaybackComplete 已清理时 mediaSession 为 null，这里不执行）
        mediaSession?.run {
            try { player.release() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
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
                if (it) {
                    exoPlayer?.let { p -> mediaSession?.player = MyForwardingPlayer(p) }
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    mediaSession?.player = ExoPlayer.Builder(this@PlaybackService).build()
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
                refreshCustomLayout(order, random)
            }
        }
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
        override fun seekToPrevious() { playerDelegate?.mediaSeekBack() }
        override fun seekToPreviousMediaItem() { playerDelegate?.mediaSeekBack() }
        override fun seekToNext() { playerDelegate?.mediaSeekForward() }
        override fun seekToNextMediaItem() { playerDelegate?.mediaSeekForward() }
        override fun seekTo(positionMs: Long) {
            playerDelegate?.mediaSeekTo(positionMs)
            super.seekTo(positionMs)
        }
        override fun stop() { super.stop(); playerDelegate?.closePlayer() }
    }
}
