package com.a10miaomiao.bilimiao.comm.delegate.player

import com.a10miaomiao.bilimiao.comm.apis.PlayerAPI
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorSegment
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
    // TODO AI 原声翻译：暂时关闭（原来这里还有个 language: String? = null 参数，
    //  用来让实现类带翻译语言走 HTTP playurl 取 AI 音轨）。
    abstract suspend fun getPlayerUrl(
        quality: Int,
        fnval: Int,
    ): PlayerSourceInfo

    abstract fun getSourceIds(): PlayerSourceIds

// TODO AI 原声翻译：暂时关闭。恢复时把这段注释放开。
//     /**
//      * 只为了拿 AI 翻译语言列表（HTTP playurl 的 language.items），不用于播放。
//      * 视频本身没有 AI 翻译时返回空列表。
//      */
//     open suspend fun getTranslateLanguages(quality: Int, fnval: Int): List<PlayerSourceInfo.LanguageInfo> = emptyList()

    open suspend fun getSubtitles(): List<SubtitleSourceInfo> = emptyList()

    /**
     * 进度条拖动预览图（B 站 videoshot 缩略图雪碧图）。
     *
     * 拿不到（视频太短 / 番剧没这数据 / 风控 / 网络失败）就返回 null，
     * 播放器那边会退化成"只显示时间气泡"，不影响播放。
     */
    open suspend fun getVideoShot(): PlayerAPI.VideoShotData? = null

    /**
     * 「空降助手」（BilibiliSponsorBlock）的赞助/恰饭等片段，用于播放时自动跳过。
     *
     * 默认空实现：番剧（PGC）跟随 PiliPlus 的做法**不查 SponsorBlock**，
     * UGC（VideoPlayerSource）和番剧（BangumiPlayerSource）都覆写了它。取不到就是空列表，播放侧完全静默。
     */
    open suspend fun getSponsorSegments(cid: String): List<SponsorSegment> = emptyList()

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