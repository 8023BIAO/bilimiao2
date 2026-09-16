package com.a10miaomiao.bilimiao.comm.delegate.player

import com.a10miaomiao.bilimiao.comm.delegate.player.entity.PlayerSourceIds
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.PlayerSourceInfo
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.SubtitleSourceInfo
import com.a10miaomiao.bilimiao.comm.proxy.ProxyServerInfo
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser

abstract class BasePlayerSource() {
    abstract val id: String // cid
    abstract val title: String
    abstract val coverUrl: String
    abstract val ownerId: String
    abstract val ownerName: String
    /**
     * @param language AI 原声翻译语言（null = 原声）。gRPC 取流接口没有语言字段，
     *   所以带语言时实现类必须走 HTTP playurl（见各实现的注释）。
     */
    abstract suspend fun getPlayerUrl(
        quality: Int,
        fnval: Int,
        language: String? = null,
    ): PlayerSourceInfo
    abstract fun getSourceIds(): PlayerSourceIds

    /**
     * 只为了拿 AI 翻译语言列表（HTTP playurl 的 language.items），不用于播放。
     * 视频本身没有 AI 翻译时返回空列表。
     */
    open suspend fun getTranslateLanguages(quality: Int, fnval: Int): List<PlayerSourceInfo.LanguageInfo> = emptyList()

    open suspend fun getSubtitles(): List<SubtitleSourceInfo> = emptyList()
    open suspend fun getDanmakuParser(): BaseDanmakuParser? = null
    open suspend fun historyReport(progress: Long) {}

    open fun next(): BasePlayerSource? = null

    /** 上一个播放单元（上一P/上一集），与 next() 对称；无可回退项返回 null */
    open fun previous(): BasePlayerSource? = null

    var defaultPlayerSource = PlayerSourceInfo()
    var proxyServer: ProxyServerInfo? = null
    var uposHost: String = ""
    var isLoop: Boolean = false // 循环播放
    var cdnRaceEnabled: Boolean = false // CDN 竞速开关
    var audioIndependentCdn: Boolean = false // 音频不跟随CDN
}