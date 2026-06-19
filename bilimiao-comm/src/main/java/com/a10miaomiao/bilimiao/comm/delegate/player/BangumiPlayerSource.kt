package com.a10miaomiao.bilimiao.comm.delegate.player


import bilibili.community.service.dm.v1.DMGRPC
import bilibili.community.service.dm.v1.DmViewReq
import bilibili.pgc.gateway.player.v2.PlayURLGRPC
import bilibili.pgc.gateway.player.v2.PlayViewReq
import bilibili.pgc.gateway.player.v2.CodeType
import bilibili.pgc.gateway.player.v2.Stream
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.DashSource
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.PlayerSourceIds
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.PlayerSourceInfo
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.SubtitleSourceInfo
import com.a10miaomiao.bilimiao.comm.exception.DabianException
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.proxy.ProxyServerInfo
import com.a10miaomiao.bilimiao.comm.utils.CdnSelector
import com.a10miaomiao.bilimiao.comm.utils.CompressionTools
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import master.flame.danmaku.danmaku.loader.android.DanmakuLoaderFactory
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.danmaku.parser.BiliDanmukuParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class BangumiPlayerSource(
    val sid: String,
    val epid: String,
    val aid: String,
    override val id: String,
    override val title: String,
    override val coverUrl: String,
    override val ownerId: String,
    override val ownerName: String,
): BasePlayerSource() {

    var episodes = emptyList<EpisodeInfo>()

    override suspend fun getPlayerUrl(quality: Int, fnval: Int): PlayerSourceInfo {
        val proxy = proxyServer
        if (proxy != null) {
            return getProxyPlayerUrl(proxy, quality, fnval)
        }
        // grpc (proto可能过期，异常时静默回退到JSON API)
        try {
            getGrpcPlayerUrl(quality, fnval)?.let {
                return it
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 如果grpc api获取失败则使用旧版api
        val res = BiliApiService.playerAPI.getBangumiUrl(
            epid, id, quality, fnval
        )
        return defaultPlayerSource.also {
            // 保留调用方预设的进度（如空降跳转），不覆盖
            val preLastPlayCid = it.lastPlayCid
            val preLastPlayTime = it.lastPlayTime
            it.lastPlayCid = res.last_play_cid ?: preLastPlayCid
            it.lastPlayTime = (res.last_play_time ?: 0).takeIf { t -> t > 0 } ?: preLastPlayTime
            it.quality = res.quality
            it.acceptList = res.accept_quality.mapIndexed { index, i ->
                PlayerSourceInfo.AcceptInfo(i, res.accept_description[index])
            }
            it.header = mapOf(
                "User-Agent" to BiliApiService.playerAPI.DEFAULT_USER_AGENT,
            )
            val durl = res.durl
            val dash = res.dash
            if (durl != null && durl.isNotEmpty()) {
                // 优先使用直链 (MP4/FLV)，避免 DASH 导致的 OOM 和兼容性问题
                if (durl.size == 1) {
                    it.duration = durl[0].length
                    it.url = if (uposHost.isNotBlank()) {
                        UrlUtil.replaceHost(durl[0].url, uposHost)
                    } else { durl[0].url }
                } else {
                    var duration = 0L
                    it.url = "[concatenating]\n" + durl.joinToString("\n") { d ->
                        duration += d.length
                        if (uposHost.isNotBlank()) {
                            UrlUtil.replaceHost(d.url, uposHost)
                        } else { d.url }
                    }
                    it.duration = duration
                }
                // 取 durl 第一个分片的尺寸作为视频尺寸（durl 可能不含完整宽高信息）
                if (it.width == null || it.height == null) {
                    dash?.video?.firstOrNull()?.let { dv ->
                        it.height = dv.height
                        it.width = dv.width
                    }
                }
            } else if (dash != null) {
                // TODO: DASH 路径暂留，后续若 GRPC 修复可重新启用
                it.duration = dash.duration * 1000L
                val dashVideo = dash.video.firstOrNull() ?: throw Exception("未找到可播放的dash视频")
                it.height = dashVideo.height
                it.width = dashVideo.width
                val dashSource = DashSource()
                it.url = dashSource.getMDPUrl(
                    dashData = dash,
                    quality = res.quality
                )
            } else {
                throw Exception("Missing both durl and dash in bangumi player response")
            }
        }
    }

    private suspend fun getGrpcPlayerUrl(quality: Int, fnval: Int): PlayerSourceInfo? {
        val result = BiliGRPCHttp.request {
            val req = PlayViewReq(
                seasonId = sid.toLong(),
                epid = epid.toLong(),
                cid = id.toLong(),
                qn = quality.toLong(),
                fnver = 0,
                fnval = fnval,
                fourk = true,
                forceHost = 2,
                download = 0,
                preferCodecType = CodeType.CODE264,
            )
            PlayURLGRPC.playView(req)
        }.awaitCall()
        val videoInfo = result.videoInfo ?: return null
        val playerSource = defaultPlayerSource
        result.business?.dimension?.let {
            playerSource.height = it.height
            playerSource.width = it.width
        }
        val availableStreamList = videoInfo.streamList.filter {
            it.content != null
        }
        if (availableStreamList.isEmpty()) {
            return null
        }
        playerSource.header = mapOf(
            "User-Agent" to "Mozilla/5.0 BiliDroid/1.41.0 (bbcallen@gmail.com)",
        )
        playerSource.acceptList = availableStreamList.map {
            val acceptInfo = it.info ?: return@map PlayerSourceInfo.AcceptInfo(0, "")
            PlayerSourceInfo.AcceptInfo(
                acceptInfo.quality,
                acceptInfo.newDescription
            )
        }
        val stream = availableStreamList.firstOrNull {
            it.info?.quality == quality
        } ?: availableStreamList.firstOrNull()
        val streamContent = stream?.content ?: return null
        playerSource.quality = stream.info?.quality ?: videoInfo.quality
        playerSource.duration = videoInfo.timelength
        when (streamContent) {
            is Stream.Content.DashVideo -> {
                val dash = streamContent.value
                val dashAudio = videoInfo.dashAudio
                val audio = dashAudio.firstOrNull {
                    it.id == dash.audioId && it.baseUrl.isNotEmpty()
                } ?: dashAudio.firstOrNull { it.baseUrl.isNotEmpty() }
                playerSource.height = dash.height
                playerSource.width = dash.width
                //  无法获取Segment Base放弃手动生成MDP XML方案
//                playerSource.url = DashSource().getMDPUrl(
//                    videoId = videoInfo.quality,
//                    videoFormat = videoInfo.format,
//                    video = dash,
//                    audio = audio,
//                    durationMs = videoInfo.timelength,
//                )
                // ───── CDN 竞速选最优节点 ─────
                val finalVideoUrl: String
                val finalAudioUrl: String?
                if (cdnRaceEnabled) {
                    val videoUrls = buildList {
                        if (uposHost == "backup") {
                            val backup = dash.backupUrl.firstOrNull { it.isNotBlank() }
                            if (backup != null) add(backup)
                            add(dash.baseUrl)
                        } else {
                            add(dash.baseUrl)
                            dash.backupUrl.forEach { if (it.isNotBlank()) add(it) }
                        }
                    }
                    val pickedVideoUrl = CdnSelector.pickFastest(videoUrls)
                    finalVideoUrl = pickedVideoUrl

                    if (audio != null && !audioIndependentCdn) {
                        val audioUrls = buildList {
                            if (uposHost == "backup") {
                                val backup = audio.backupUrl.firstOrNull { it.isNotBlank() }
                                if (backup != null) add(backup)
                                add(audio.baseUrl)
                            } else {
                                add(audio.baseUrl)
                                audio.backupUrl.forEach { b -> if (b.isNotBlank()) add(b) }
                            }
                        }
                        val pickedAudioUrl = CdnSelector.pickFastest(audioUrls)
                        finalAudioUrl = pickedAudioUrl
                    } else {
                        finalAudioUrl = audio?.let {
                            if (uposHost.isNotBlank()) UrlUtil.replaceHost(it.baseUrl, uposHost) else it.baseUrl
                        }
                    }
                } else {
                    finalVideoUrl = if (uposHost.isNotBlank()) {
                        if (uposHost == "backup") {
                            dash.backupUrl.firstOrNull { it.isNotBlank() } ?: dash.baseUrl
                        } else {
                            UrlUtil.replaceHost(dash.baseUrl, uposHost)
                        }
                    } else dash.baseUrl
                    finalAudioUrl = audio?.let {
                        if (uposHost == "backup") {
                            it.backupUrl.firstOrNull { it.isNotBlank() } ?: it.baseUrl
                        } else if (uposHost.isNotBlank()) {
                            UrlUtil.replaceHost(it.baseUrl, uposHost)
                        } else it.baseUrl
                    }
                }

                playerSource.url = if (finalAudioUrl == null) {
                    finalVideoUrl
                } else {
                    "[merging]\n$finalVideoUrl\n$finalAudioUrl"
                }
            }
            is Stream.Content.SegmentVideo -> {
                val durl = streamContent.value
                playerSource.url = "[concatenating]\n" + durl.segment.joinToString("\n") { it.url }
            }
        }
        return playerSource
    }

    suspend fun getProxyPlayerUrl(
        proxy: ProxyServerInfo,
        quality: Int,
        fnval: Int
    ): PlayerSourceInfo {
        val res = BiliApiService
            .playerAPI
            .getProxyBangumiUrl(
                epid, id, quality, fnval, proxy
            )
        return PlayerSourceInfo().also {
            // 保留调用方预设的进度（如空降跳转）
            val preCid = defaultPlayerSource.lastPlayCid
            val preTime = defaultPlayerSource.lastPlayTime
            it.lastPlayCid = res.last_play_cid ?: preCid
            it.lastPlayTime = (res.last_play_time ?: 0).takeIf { t -> t > 0 } ?: preTime
            it.quality = res.quality
            it.acceptList = res.accept_quality.mapIndexed { index, i ->
                PlayerSourceInfo.AcceptInfo(i, res.accept_description[index])
            }
            val dash = res.dash
            it.header = mapOf(
                "User-Agent" to BiliApiService.playerAPI.DEFAULT_USER_AGENT,
            )
            if (dash != null) {
                it.duration = dash.duration * 1000L
                val dashVideo = dash.video.firstOrNull() ?: throw Exception("未找到可播放的dash视频")
                it.height = dashVideo.height
                it.width = dashVideo.width
                val dashSource = DashSource(uposHost)
                it.url = dashSource.getMDPUrl(
                    dashData = dash,
                    quality = res.quality
                )
            } else {
                val durl = res.durl ?: throw Exception("Missing durl in proxy bangumi player response")
                if (durl.size == 1) {
                    it.duration = durl[0].length
                    it.url = if (uposHost.isNotBlank()) {
                        UrlUtil.replaceHost(durl[0].url, uposHost)
                    } else { durl[0].url }
                } else {
                    var duration = 0L
                    it.url = "[concatenating]\n" + durl.joinToString("\n") { d ->
                        duration += d.length
                        if (uposHost.isNotBlank()) {
                            UrlUtil.replaceHost(d.url, uposHost)
                        } else { d.url }
                    }
                    it.duration = duration
                }

            }
        }
    }
    override fun getSourceIds(): PlayerSourceIds {
        return PlayerSourceIds(
            cid = id,
            sid = sid,
            epid = epid,
            aid = aid,
        )
    }

    override suspend fun getSubtitles(): List<SubtitleSourceInfo> {
        try {
            val req = DmViewReq(
                pid = aid.toLong(),
                oid = id.toLong(),
                type = 1,
                spmid = "pgc.pgc-video-detail.0.0"
            )
            val res = BiliGRPCHttp.request {
                DMGRPC.dmView(req)
            }.awaitCall()
            val subtitle = res.subtitle
            return if (subtitle == null) {
                listOf()
            } else {
                subtitle.subtitles.map {
                    SubtitleSourceInfo(
                        id = it.id.toString(),
                        lan = it.lan,
                        lan_doc = it.lanDoc,
                        subtitle_url = it.subtitleUrl,
                        ai_status = it.aiStatus.value,
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }

    override suspend fun getDanmakuParser(): BaseDanmakuParser? {
        val inputStream = getBiliDanmukuStream()
        return if (inputStream == null) {
            null
        } else {
            val loader = DanmakuLoaderFactory.create(DanmakuLoaderFactory.TAG_BILI)
            loader.load(inputStream)
            val parser = BiliDanmukuParser()
            val dataSource = loader.dataSource
            parser.load(dataSource)
            parser
        }
    }

    private suspend fun getBiliDanmukuStream(): InputStream? {
        if (sid == "26257") {
            // 答辩就不要看了
            throw DabianException()
        }
        val res = BiliApiService.playerAPI.getDanmakuList(id)
            .awaitCall()
        val body = res.body
        return if (body == null) {
            null
        } else {
            ByteArrayInputStream(CompressionTools.decompressXML(body.bytes()))
        }
    }

    override suspend fun historyReport(progress: Long) {
        try {
            val realtimeProgress = progress.toString()  // 秒数
            MiaoHttp.request {
                url = "https://api.bilibili.com/x/v2/history/report"
                formBody = ApiHelper.createParams(
                    "aid" to aid,
                    "cid" to id,
                    "epid" to epid,
                    "sid" to sid,
                    "progress" to realtimeProgress,
                    "realtime" to realtimeProgress,
                    "type" to "4",
                    "sub_type" to "1",
                )
                method = MiaoHttp.POST
            }.awaitCall()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun next(): BasePlayerSource? {
        val index = episodes.indexOfFirst { it.cid == id }
        val nextIndex = index + 1
        if (nextIndex in episodes.indices) {
            val nextEpisode = episodes[nextIndex]
            val nextPlayerSource = BangumiPlayerSource(
                sid = sid,
                epid = nextEpisode.epid,
                aid = nextEpisode.aid,
                id = nextEpisode.cid,
                title = nextEpisode.index_title.ifBlank { nextEpisode.index },
                coverUrl = nextEpisode.cover,
                ownerId = ownerId,
                ownerName = ownerName,
            )
            nextPlayerSource.episodes = episodes
            return nextPlayerSource
        }
        return null
    }

    data class EpisodeInfo(
        val epid: String,
        val aid: String,
        val cid: String,
        val cover: String,
        val index: String,
        val index_title: String,
        val badge: String,
        val badge_info: EpisodeBadgeInfo,
    )

    data class EpisodeBadgeInfo(
        val bg_color: String,
        val bg_color_night: String,
        val text: String,
    )
}