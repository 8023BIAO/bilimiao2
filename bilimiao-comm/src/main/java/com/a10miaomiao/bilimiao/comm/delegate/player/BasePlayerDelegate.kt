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
    fun setWindowInsets(left: Int, top: Int, right: Int, bottom: Int, displayCutout: DisplayCutout?)
    fun onConfigurationChanged(newConfig: Configuration)
    fun getSourceIds(): PlayerSourceIds
    fun currentPosition(): Long
    fun sendDanmaku(type: Int, danmakuText: String, danmakuTextSize: Float, danmakuTextColor: Int, danmakuPosition: Long)

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
}