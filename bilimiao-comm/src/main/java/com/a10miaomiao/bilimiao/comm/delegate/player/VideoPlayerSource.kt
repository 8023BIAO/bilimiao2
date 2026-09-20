package com.a10miaomiao.bilimiao.comm.delegate.player

import bilibili.app.playurl.v1.PlayURLGRPC
import bilibili.app.playurl.v1.PlayViewReq
import bilibili.app.playurl.v1.Stream
import bilibili.community.service.dm.v1.DMGRPC
import bilibili.community.service.dm.v1.DmViewReq
import com.a10miaomiao.bilimiao.comm.apis.PlayerAPI
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorSegment
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.DashSource
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.PlayerSourceIds
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.PlayerSourceInfo
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.SubtitleSourceInfo
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.utils.PlayerDiag
import com.a10miaomiao.bilimiao.comm.utils.BvUtils
import com.a10miaomiao.bilimiao.comm.utils.CdnSelector
import com.a10miaomiao.bilimiao.comm.utils.CompressionTools
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.comm.utils.VideoCodecSupport
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import master.flame.danmaku.danmaku.loader.android.DanmakuLoaderFactory
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.danmaku.parser.BiliDanmukuParser
import java.io.ByteArrayInputStream
import java.io.InputStream

class VideoPlayerSource(
    val mainTitle: String, //视频名字，不是分p名
    override val title: String,
    override val coverUrl: String,
    var aid: String, // av号 或 bvid
    var bvid: String = "", // BV号
    override var id: String, // cid
    override val ownerId: String,
    override val ownerName: String,
): BasePlayerSource() {

    var pages = emptyList<PageInfo>()

    /**
     * 空降助手要用的 BV 号。
     *
     * 取值优先级：① `bvid` 本身是**合法 BV 号**时用它；② 否则用 `aid` 现算。
     *
     * ★ 关键：这里**必须校验 bvid 的形态**，不能只判空串。
     *   因为很多入口传进来的 `bvid` 其实是 **av 号**（「继续播放」卡片传 `playerState.aid`、
     *   历史/收藏卡片传 `it.aid.toString()`），拿它去查会得到 `[]` →
     *   **片头片尾等片段全部消失**（用户实测报的 bug）。
     *   现算那一路对超出经典算法范围的新 av 号会返回 null（`BvUtils.toBvid` 的保护），
     *   宁可不查也不能查错视频。
     */
    val effectiveBvid: String
        get() = bvid.takeIf { BvUtils.isValidBvid(it) } ?: BvUtils.toBvid(aid).orEmpty()

    // TODO AI 原声翻译：暂时关闭（原来这里还有个 language: String? 参数）。
    override suspend fun getPlayerUrl(
        quality: Int,
        fnval: Int,
    ): PlayerSourceInfo {
        // grpc (proto可能过期，异常时静默回退到JSON API)
        // TODO AI 原声翻译：暂时关闭（原来这里判断"带语言时跳过 gRPC 改走 HTTP"）
        try {
            getGrpcPlayerUrl(quality, fnval)?.let {
                return it
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 如果grpc api获取失败则使用旧版api
        PlayerDiag.log("video", "gRPC 没给出结果 → 回退 HTTP JSON playurl（fnval=$fnval qn=$quality）")
        val res = BiliApiService.playerAPI
            .getVideoPalyUrl(aid, id, quality, fnval)

        return defaultPlayerSource.also {
            // TODO AI 原声翻译：暂时关闭（原来是 languages / currentLanguage 赋值）
            // 与番剧源一致：保留调用方预设的 lastPlayCid（如空降跳转），服务端无返回时不覆盖
            it.lastPlayCid = res.last_play_cid ?: it.lastPlayCid
            // 同理保留预设的 lastPlayTime：详情页带过来的云端进度（继续观看/空降）不能被
            // "接口没返回"抹成 0 —— 之前是 `?: 0`，于是"本地和云端都有记录却从头播"
            it.lastPlayTime = res.last_play_time ?: it.lastPlayTime
            it.quality = res.quality
            it.acceptList = res.accept_quality.mapIndexed { index, i ->
                PlayerSourceInfo.AcceptInfo(i, res.accept_description[index])
            }
            val dash = res.dash
            it.header = mapOf(
                "Referer" to BiliApiService.playerAPI.DEFAULT_REFERER,
                "User-Agent" to BiliApiService.playerAPI.DEFAULT_USER_AGENT,
            )
            if (dash != null) {
                PlayerDiag.log("video-http", "DASH → 生成 MPD（qn=${res.quality}）")
                it.duration = dash.duration * 1000L
                val dashVideo = dash.video.firstOrNull() ?: throw Exception("未找到可播放的dash视频")
                it.height = dashVideo.height
                it.width = dashVideo.width
                // 使用 uposHost（如果有设置）进行 CDN 替换，同时 MPD 内包含 backup_url 备选 CDN
                val dashSource = DashSource(uposHost)
                val mpd = dashSource.getMDPUrl(
                    dashData = dash,
                    quality = res.quality
                )
                if (mpd.startsWith("[dash-mpd]")) {
                    it.url = mpd
                } else {
                    // vc110：按本机解码能力挑编码（AV1 优先会给老机器带来软解卡顿/黑屏）
                    val v = VideoCodecSupport.pickBest(
                        list = dash.video,
                        qualityOf = { it.id },
                        codecsOf = { it.codecs },
                        quality = res.quality,
                    )
                    val a = dash.audio?.firstOrNull()
                    val videoUrl = v?.base_url.orEmpty()
                    it.url = if (a?.base_url.isNullOrBlank()) {
                        videoUrl
                    } else {
                        "[merging]\n$videoUrl\n${a!!.base_url}"
                    }
                    PlayerDiag.log("video-http", "MPD 不可用 → 退回 [merging]（qn=${res.quality}）")
                }
            } else {
                val durl = res.durl ?: throw Exception("Missing durl in video player response")
                PlayerDiag.log(
                    "video-http",
                    if (durl.size == 1) "MP4 直链（1 段）" else "MP4 多段直链（${durl.size} 段）"
                )
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

// TODO AI 原声翻译：暂时关闭。恢复时把这段注释放开。
//     /**
//      * 只为拿 AI 翻译语言列表：HTTP playurl 的 language.items（gRPC 没有这个字段）。
//      * 走的是同一条 HTTP 接口但结果只用来填菜单，失败就当该视频没有 AI 翻译。
//      */
//     override suspend fun getTranslateLanguages(
//         quality: Int,
//         fnval: Int,
//     ): List<PlayerSourceInfo.LanguageInfo> {
//         return try {
//             BiliApiService.playerAPI
//                 .getVideoPalyUrl(aid, id, quality, fnval)
//                 .language?.items.orEmpty()
//                 .map { PlayerSourceInfo.LanguageInfo(it.lang, it.title) }
//         } catch (e: Exception) {
//             emptyList()
//         }
//     }

    private suspend fun getGrpcPlayerUrl(quality: Int, fnval: Int): PlayerSourceInfo? {
        val result = BiliGRPCHttp.request {
            val req = PlayViewReq(
                aid = aid.toLong(),
                cid = id.toLong(),
                qn = quality.toLong(),
                download = 0,
                fnval = fnval,
                fnver = 0,
                forceHost = 2,
                fourk = true,
            )
            PlayURLGRPC.playView(req)
        }.awaitCall()
        val videoInfo = result.videoInfo ?: return null
        val playerSource = defaultPlayerSource
        val availableStreamList = videoInfo.streamList.filter {
            it.content != null
        }
        playerSource.header = mapOf(
            "User-Agent" to "Mozilla/5.0 BiliDroid/1.41.0 (bbcallen@gmail.com)",
        )
        if (availableStreamList.isEmpty()) {
            return null
        }
        playerSource.acceptList = availableStreamList.map {
            val acceptInfo = it.streamInfo ?: return@map PlayerSourceInfo.AcceptInfo(0, "")
            PlayerSourceInfo.AcceptInfo(
                acceptInfo.quality,
                acceptInfo.newDescription
            )
        }
        // ★ vc110：优先挑"本机能解"的那条流。
        //   gRPC 返回的流如果只有 AV1 而手机没有 AV1 硬解，会退化成软解：CPU 拉满、发热、掉帧，
        //   个别机型直接黑屏（上游 Bilibili-thread-ripper 0.9.2.0 也是为这个加了"编码跟随播放策略"）。
        //   挑不到能解的 → 保持原来的行为（同清晰度第一条），绝不因此放不出来。
        val stream = pickDecodableStream(availableStreamList, quality)
            ?: availableStreamList.firstOrNull { it.streamInfo?.quality == quality }
            ?: availableStreamList.firstOrNull()
        val streamContent = stream?.content ?: return null
        playerSource.quality = stream.streamInfo?.quality ?: videoInfo.quality
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

                // ───── CDN 候选列表构建 ─────
                // 竞速选最快的放第一位，其余 backupUrl 跟在后面供运行时故障转移
                // 格式: <primaryUrl>|<backup1>|<backup2>...  (单 URL 时无 |)
                val finalVideoCandidates: String
                val finalAudioCandidates: String?
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
                    finalVideoCandidates = CdnSelector.pickAndRank(videoUrls)

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
                        finalAudioCandidates = CdnSelector.pickAndRank(audioUrls)
                    } else {
                        finalAudioCandidates = audio?.let {
                            val u = if (uposHost.isNotBlank()) UrlUtil.replaceHost(it.baseUrl, uposHost) else it.baseUrl
                            u
                        }
                    }
                } else {
                    // CDN 竞速关闭：baseUrl + backupUrl 仍供运行时故障转移
                    finalVideoCandidates = buildList {
                        if (uposHost == "backup") {
                            dash.backupUrl.firstOrNull { it.isNotBlank() }?.let { add(it) }
                            add(dash.baseUrl)
                        } else if (uposHost.isNotBlank()) {
                            add(UrlUtil.replaceHost(dash.baseUrl, uposHost))
                            dash.backupUrl.forEach { if (it.isNotBlank()) add(UrlUtil.replaceHost(it, uposHost)) }
                        } else {
                            add(dash.baseUrl)
                            dash.backupUrl.forEach { if (it.isNotBlank()) add(it) }
                        }
                    }.joinToString("|")
                    finalAudioCandidates = audio?.let {
                        buildList {
                            if (uposHost == "backup") {
                                it.backupUrl.firstOrNull { b -> b.isNotBlank() }?.let { b -> add(b) }
                                add(it.baseUrl)
                            } else if (uposHost.isNotBlank()) {
                                add(UrlUtil.replaceHost(it.baseUrl, uposHost))
                                it.backupUrl.forEach { b -> if (b.isNotBlank()) add(UrlUtil.replaceHost(b, uposHost)) }
                            } else {
                                add(it.baseUrl)
                                it.backupUrl.forEach { b -> if (b.isNotBlank()) add(b) }
                            }
                        }.joinToString("|")
                    }
                }

                PlayerDiag.log("video-grpc", "[merging] DASH 视频+音频两条流（qn=${playerSource.quality}）")
                playerSource.url = if (finalAudioCandidates == null) {
                    finalVideoCandidates
                } else {
                    "[merging]\n$finalVideoCandidates\n$finalAudioCandidates"
                }
            }
            is Stream.Content.SegmentVideo -> {
                val durl = streamContent.value
                PlayerDiag.log("video-grpc", "[concatenating] MP4 分片 ${durl.segment.size} 段（qn=${playerSource.quality}）")
                playerSource.url = "[concatenating]\n" + durl.segment.joinToString("\n") { it.url }
            }
        }
        miaoLogger().d(
            "获取播放器地址成功 (gRPC + CDN竞速)",
            "url" to playerSource.url,
            "header" to playerSource.header,)
        return playerSource
    }

    override fun getSourceIds(): PlayerSourceIds {
        return PlayerSourceIds(
            cid = id,
            aid = aid,
        )
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


    /**
     * 从 gRPC 返回的流列表里挑一条**本机能解码**的（vc110）。
     *
     * 优先级：同清晰度且能解 → 低于等于目标清晰度里最高的那条能解的 → null（调用方按原逻辑处理）。
     * 拿不到 codecid（不是 DASH）时视为"能解"，不做干预。
     */
    private fun pickDecodableStream(
        list: List<bilibili.app.playurl.v1.Stream>,
        quality: Int,
    ): bilibili.app.playurl.v1.Stream? {
        fun codecidOf(s: bilibili.app.playurl.v1.Stream): Int? =
            (s.content as? Stream.Content.DashVideo)?.value?.codecid

        fun decodable(s: bilibili.app.playurl.v1.Stream): Boolean {
            val id = codecidOf(s) ?: return true
            return VideoCodecSupport.score(VideoCodecSupport.mimeOfCodecid(id)) >= 0
        }

        list.firstOrNull { it.streamInfo?.quality == quality && decodable(it) }?.let { return it }
        return list
            .filter { (it.streamInfo?.quality ?: 0) <= quality && decodable(it) }
            .maxByOrNull { it.streamInfo?.quality ?: 0 }
    }

    private suspend fun getBiliDanmukuStream(): InputStream? {
        val res = BiliApiService.playerAPI.getDanmakuList(id)
            .awaitCall()
        val body = res.body
        return if (body == null) {
            null
        } else {
            ByteArrayInputStream(CompressionTools.decompressXML(body.bytes()))
        }
    }

    override suspend fun getSubtitles(): List<SubtitleSourceInfo> {
        try {
            val req = DmViewReq(
                pid = aid.toLong(),
                oid = id.toLong(),
                type = 1,
                spmid = "main.ugc-video-detail.0.0"
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

    override suspend fun getVideoShot(): PlayerAPI.VideoShotData? {
        return try {
            // ★ 别再自己挑"BV 还是 av"塞进 aid：这个接口的 aid 只认纯数字 av 号，
            //   塞 BV 会被 -400 拒掉（2026-09 起 B 站收紧了校验）→ 预览图全没。
            //   这里把 raw aid 和有效的 bvid 都给过去，由 PlayerAPI 决定用 aid= 还是 bvid=。
            com.a10miaomiao.bilimiao.comm.utils.PreviewDiag.log(
                "VideoPlayerSource: aid=$aid bvid=$bvid effectiveBvid=$effectiveBvid cid=${this.id}"
            )
            BiliApiService.playerAPI.getVideoShot(
                aid = aid,
                cid = this.id,
                bvid = effectiveBvid,
            )?.toHttps()
        } catch (e: Exception) {
            // 预览图是"锦上添花"，任何失败都静默降级，绝不弹错
            null
        }
    }

    /**
     * 「空降助手」片段。
     *
     * 必须用**裸 BVID**（哈希端点算的就是 `SHA256(bvid)`，喂 av 号或 `bvid+cid` 拼接串
     * 都会静默查不到），所以只有 bvid 非空时才发请求。
     */
    override suspend fun getSponsorSegments(cid: String): List<SponsorSegment> {
        val bv = effectiveBvid
        if (bv.isBlank()) return emptyList()
        return BiliApiService.sponsorBlockAPI.getSegments(bv, cid)
    }

    override suspend fun historyReport(progress: Long) {
        try {
            val realtimeProgress = progress.toString()  // 秒数
            MiaoHttp.request {
                url = "https://api.bilibili.com/x/v2/history/report"
                formBody = ApiHelper.createParams(
                    "aid" to aid,
                    "cid" to id,
                    "progress" to realtimeProgress,
                    "realtime" to realtimeProgress,
                    "type" to "3"
                )
                method = MiaoHttp.POST
            }.awaitCall()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun next(): BasePlayerSource? {
        val index = pages.indexOfFirst { it.cid == id }
        val nextIndex = index + 1
        if (nextIndex in pages.indices) {
            val nextPage = pages[nextIndex]
            val nextPlayerSource = VideoPlayerSource(
                mainTitle = mainTitle,
                title = nextPage.title,
                coverUrl = coverUrl,
                aid = aid,
                bvid = bvid,
                id = nextPage.cid,
                ownerId = ownerId,
                ownerName = ownerName,
            )
            nextPlayerSource.pages = pages
            return nextPlayerSource
        }
        return null
    }

    override fun previous(): BasePlayerSource? {
        val index = pages.indexOfFirst { it.cid == id }
        val prevIndex = index - 1
        if (prevIndex in pages.indices) {
            val prevPage = pages[prevIndex]
            val prevPlayerSource = VideoPlayerSource(
                mainTitle = mainTitle,
                title = prevPage.title,
                coverUrl = coverUrl,
                aid = aid,
                bvid = bvid,
                id = prevPage.cid,
                ownerId = ownerId,
                ownerName = ownerName,
            )
            prevPlayerSource.pages = pages
            return prevPlayerSource
        }
        return null
    }

    data class PageInfo(
        val cid: String,
        val title: String,
    )

}
