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
    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration?)
    fun isOpened(): Boolean
    fun isPlaying(): Boolean
    fun isPause(): Boolean
    fun isPreparing(): Boolean

    /** 是否已播放到结尾（通知栏据此报 STATE_ENDED，否则播完还显示"播放中"、进度继续空转） */
    fun isCompleted(): Boolean = false
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