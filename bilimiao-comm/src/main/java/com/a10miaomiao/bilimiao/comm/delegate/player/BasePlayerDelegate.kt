package com.a10miaomiao.bilimiao.comm.delegate.player

import android.content.res.Configuration
import android.view.DisplayCutout
import com.a10miaomiao.bilimiao.comm.delegate.BaseDelegate
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.PlayerSourceIds
import com.a10miaomiao.bilimiao.comm.proxy.ProxyServerInfo
import master.flame.danmaku.danmaku.model.BaseDanmaku

interface BasePlayerDelegate: BaseDelegate {
    fun openPlayer(source: BasePlayerSource)
    fun closePlayer()

    /** 划掉最近任务等"彻底收摊"场景：停播并释放播放器（不走 UI 路径，也不回头通知服务） */
    fun releasePlayback() {}

    /** 立刻持久化当前播放位置（暂停时调用：否则"暂停→切走→被杀"会丢位置，回来就从 0 播） */
    fun savePlaybackPositionNow() {}
    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration?)
    fun isOpened(): Boolean
    fun isPlaying(): Boolean
    fun isPause(): Boolean
    fun isPreparing(): Boolean
    fun setWindowInsets(left: Int, top: Int, right: Int, bottom: Int, displayCutout: DisplayCutout?)
    fun onConfigurationChanged(newConfig: Configuration)
    fun getSourceIds(): PlayerSourceIds
    fun currentPosition(): Long
    fun sendDanmaku(type: Int, danmakuText: String, danmakuTextSize: Float, danmakuTextColor: Int, danmakuPosition: Long)

    /** 打开弹幕编辑框：记住之前是否在播放，并按需暂停（默认空实现，子类覆盖） */
    fun openDanmakuEditor() {}

    /** 关闭弹幕编辑框：只有"打开前本来在播放"才恢复播放，用户自己暂停过的不要自动播 */
    fun closeDanmakuEditor() {}

    fun setProxy(proxyServer: ProxyServerInfo, uposHost: String)

    // 通知栏控制器委托方法（默认空实现，子类按需覆盖）
    fun mediaPlay() {}
    fun mediaPause() {}
    fun mediaSeekTo(position: Long) {}
    fun mediaSeekBack() {}
    fun mediaSeekForward() {}
    fun mediaGetDuration(): Long = 0L
    fun mediaGetTitle(): String? = null
    fun mediaGetSubtitle(): String? = null
    fun mediaGetCoverUrl(): String? = null
    fun syncPlayMode(order: Int, random: Boolean) {}
    fun mediaPlayNext(): Boolean = false
    fun mediaPlayPrevious(): Boolean = false
    fun hasPreviousEpisode(): Boolean = false
    fun hasNextEpisode(): Boolean = false
    fun isPlaylistSingle(): Boolean = false

    // 视频章节控制（有章节数据的实现类覆盖）
    fun hasChapters(): Boolean = false
    fun hasPreviousChapter(): Boolean = false
    fun hasNextChapter(): Boolean = false
    fun mediaSeekToPreviousChapter(): Boolean = false
    fun mediaSeekToNextChapter(): Boolean = false
}