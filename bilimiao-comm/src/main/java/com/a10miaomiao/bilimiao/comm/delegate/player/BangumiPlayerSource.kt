package com.a10miaomiao.bilimiao.comm.delegate.player


import bilibili.community.service.dm.v1.DMGRPC
import bilibili.community.service.dm.v1.DmViewReq
import bilibili.pgc.gateway.player.v2.PlayURLGRPC
import bilibili.pgc.gateway.player.v2.PlayViewReq
import bilibili.pgc.gateway.player.v2.CodeType
import bilibili.pgc.gateway.player.v2.Stream
import com.a10miaomiao.bilimiao.comm.apis.PlayerAPI
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorActionType
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorCategory
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorSegment
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
import com.a10miaomiao.bilimiao.comm.utils.PlayerDiag
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

    // TODO AI 原声翻译：暂时关闭（原来这里还有个 language: String? 参数）。
    override suspend fun getPlayerUrl(
        quality: Int,
        fnval: Int,
    ): PlayerSourceInfo {
        val proxy = proxyServer
        if (proxy != null) {
            return getProxyPlayerUrl(proxy, quality, fnval)
        }
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
        PlayerDiag.log("bangumi", "gRPC 没给出结果 → 回退 HTTP JSON playurl（fnval=$fnval qn=$quality）")
        val res = BiliApiService.playerAPI.getBangumiUrl(
            epid, id, quality, fnval
        )
        runCatching {
            val clips = res.clip_info_list.orEmpty()
            if (clips.isNotEmpty()) {
                pgcClips = clips.mapNotNull { toPgcSegment(it.start, it.end, it.clipType) }
            }
        }
        return defaultPlayerSource.also {
            // TODO AI 原声翻译：暂时关闭（原来是 languages / currentLanguage 赋值）
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
            // ★ 跟随"视频格式选择"：fnval > 2 = DASH，= 2 = MP4/FLV（用户 2026-09-19 明确要求）。
            //   以前这里写死"durl 优先"，等于把设置架空；顺带一提：MP4 源**不一定**没有高清晰度
            //   （实测样本里只给了 720P/360P，但部分内容 MP4 也能到 1080P），所以清晰度不该由代码替用户决定。
            //   实测 PGC 的 dash 带完整 SegmentBase（initialization + index_range），MPD 是好的。
            val preferDash = fnval > 2
            if (preferDash && dash != null) {
                // ★★ vc106：DASH **不再手工拼 MPD**，改成"视频 + 音频两条流"（与普通视频、番剧 gRPC 路径一致）。
                //
                // 为什么（2026-09-20 实机日志 + 用户反馈"有些视频黑屏 / 缓冲一次只有几百 KB"）：
                //  ① 手工 MPD 里我们写的是接口给的 SegmentBase(indexRange)，播放器按它把文件**按 sidx 切成
                //     2~3MB 一段一段**去请求；而 MP4/merging 一次能读 7~8MB —— 用户感受就是"负优化"；
                //  ② 只要 sidx 与真正拿到的字节有任何不一致（多 CDN 换节点、镜像），播放器就会按**错误偏移**读：
                //     轻则 ParserException: Invalid NAL length（黑屏），重则请求越界 → HTTP 416 / EOFException；
                //     这两种错误在 ripper 关掉、CDN 竞速关掉时同样出现，根因在 MPD 这条路上；
                //  ③ merging 让播放器顺序读文件、用**文件自带**的索引寻址，天生一致，拖进度条也能秒跳。
                //  代价：不再有 MPD 的"多 BaseURL 自适应"；我们用 `|` 候选列表 + CdnFailoverDataSource 顶上。
                it.duration = dash.duration * 1000L
                // ★ vc110：同一个清晰度 B 站会给多条不同编码（AV1/HEVC/AVC），以前直接取"接口第一条"
                //   —— 而 fnval=4048 时接口通常把 **AV1 排在前面**；没有 AV1 硬解的老机器只能软解（卡/发热），
                //   个别机型直接黑屏。改成按本机解码能力挑（HEVC > AVC > AV1，硬解优先），见 [VideoCodecSupport]。
                val v = VideoCodecSupport.pickBest(
                    list = dash.video,
                    qualityOf = { it.id },
                    codecsOf = { it.codecs },
                    quality = res.quality,
                ) ?: throw Exception("未找到可播放的dash视频")
                it.height = v.height
                it.width = v.width
                val a = dash.audio?.firstOrNull()
                val videoCandidates = buildCandidates(v.base_url, v.backup_url.orEmpty(), race = true)
                val audioCandidates = a?.let {
                    buildCandidates(
                        it.base_url,
                        it.backup_url.orEmpty(),
                        race = !audioIndependentCdn,
                    )
                }
                it.url = if (videoCandidates.isBlank()) {
                    // 极端兜底：连 base_url 都没有时，才退回手工 MPD（正常情况下走不到这里）
                    PlayerDiag.log("bangumi-http", "没有可用直链 → 退回手工 MPD（qn=${res.quality}）")
                    DashSource().getMDPUrl(dashData = dash, quality = res.quality)
                } else if (audioCandidates.isNullOrBlank()) {
                    videoCandidates
                } else {
                    "[merging]\n$videoCandidates\n$audioCandidates"
                }
                PlayerDiag.log(
                    "bangumi-http",
                    "DASH → [merging] 视频+音频两条流（qn=${res.quality} 候选 " +
                        "${videoCandidates.split("|").size} 条）"
                )
            } else if (durl != null && durl.isNotEmpty()) {
                PlayerDiag.log(
                    "bangumi-http",
                    if (durl.size == 1) "MP4 直链（1 段 ${durl[0].length}ms）"
                    else "MP4 多段直链（${durl.size} 段 → ConcatenatingMediaSource）"
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
                // durl 可能不含完整宽高信息，从 dash 补充
                dash?.video?.firstOrNull()?.let { dv ->
                    it.height = dv.height
                    it.width = dv.width
                }
            } else if (dash != null) {
                PlayerDiag.log("bangumi-http", "只有 DASH 可用 → 生成 MPD（qn=${res.quality}）")
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

    /**
     * 组装一条轨道的 CDN 候选列表：`base|backup1|backup2…`（竞速开着时按延迟排序，赢家在前）。
     *
     * 与 gRPC 路径、普通视频路径同一套规则：
     *  - 「CDN 固定主机」= uposHost 非空 → 所有候选都换成这个主机（**用户点名就不换节点**）；
     *  - `uposHost == "backup"` → 优先用接口给的第一个 backup 地址；
     *  - 竞速开 → [CdnSelector.pickAndRank] 用 1 字节 GET 测延迟排序；关 → 保持原顺序（仍可供运行时故障转移）。
     */
    private suspend fun buildCandidates(
        baseUrl: String,
        backupUrls: List<String>,
        race: Boolean,
    ): String {
        val urls = buildList {
            if (uposHost == "backup") {
                backupUrls.firstOrNull { it.isNotBlank() }?.let { add(it) }
                if (baseUrl.isNotBlank()) add(baseUrl)
            } else if (uposHost.isNotBlank()) {
                if (baseUrl.isNotBlank()) add(UrlUtil.replaceHost(baseUrl, uposHost))
                backupUrls.forEach { if (it.isNotBlank()) add(UrlUtil.replaceHost(it, uposHost)) }
            } else {
                if (baseUrl.isNotBlank()) add(baseUrl)
                backupUrls.forEach { if (it.isNotBlank()) add(it) }
            }
        }.distinct()
        if (urls.isEmpty()) return ""
        return if (race && urls.size > 1) CdnSelector.pickAndRank(urls) else urls.joinToString("|")
    }

// TODO AI 原声翻译：暂时关闭。恢复时把这段注释放开。
//     /** 只为拿 AI 翻译语言列表（HTTP playurl 的 language.items）——番剧是 AI 翻译的主战场 */
//     override suspend fun getTranslateLanguages(
//         quality: Int,
//         fnval: Int,
//     ): List<PlayerSourceInfo.LanguageInfo> {
//         return try {
//             BiliApiService.playerAPI
//                 .getBangumiUrl(epid, id, quality, fnval)
//                 .language?.items.orEmpty()
//                 .map { PlayerSourceInfo.LanguageInfo(it.lang, it.title) }
//         } catch (e: Exception) {
//             emptyList()
//         }
//     }

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
        // 番剧跳片头片尾：gRPC 的 business.clip_info 里就带着（含 B 站自己的提示语）
        runCatching {
            // pbandk 生成的 repeated 字段访问器就是字段名本身（clipInfo），不是 protobuf-java 的 clipInfoList
            val clips = result.business?.clipInfo.orEmpty()
            if (clips.isNotEmpty()) {
                pgcClips = clips.mapNotNull {
                    toPgcSegment(
                        startSec = it.start.toDouble(),
                        endSec = it.end.toDouble(),
                        clipType = it.clipType.name.orEmpty(),
                        toastText = it.toastText.orEmpty(),
                    )
                }
            }
        }
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
                // gRPC 的 DashVideo 没有 SegmentBase（proto 里就没这个字段）→ 只能当两条普通流合并播放，
                // 但清晰度是完整的（DASH 才有 1080P）——所以它是"番剧 DASH"的主路径，不是坏的。
                PlayerDiag.log("bangumi-grpc", "[merging] DashVideo 视频+音频两条流（qn=${videoInfo.quality}）")
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
                            if (uposHost.isNotBlank()) UrlUtil.replaceHost(it.baseUrl, uposHost) else it.baseUrl
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

                playerSource.url = if (finalAudioCandidates == null) {
                    finalVideoCandidates
                } else {
                    "[merging]\n$finalVideoCandidates\n$finalAudioCandidates"
                }
            }
            is Stream.Content.SegmentVideo -> {
                val durl = streamContent.value
                PlayerDiag.log("bangumi-grpc", "[concatenating] MP4 分片 ${durl.segment.size} 段（qn=${videoInfo.quality}）")
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
        // ★ 代理/解锁线路也要收集"跳过片头片尾"配置：
        //   以前只有直连 HTTP 那条路读了 clip_info_list，走代理的用户永远拿不到番剧片段
        runCatching {
            val clips = res.clip_info_list.orEmpty()
            if (clips.isNotEmpty()) {
                pgcClips = clips.mapNotNull { toPgcSegment(it.start, it.end, it.clipType) }
            }
        }
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

    // ─────────────── 番剧「跳过片头/片尾」（PGC 自带的 clip_info，不走 SponsorBlock）───────────────
    // PiliPlus 对 PGC 的做法：不查 SponsorBlock，改用 playurl 返回的 clip_info_list，
    // 由独立的 pgcSkipType 控制。我们把它映射成同一套片段模型，复用"每类别策略"
    // （开场动画=intro / 片尾=outro / 广告=sponsor），由用户在设置里逐类开关。

    /** 本次播放拿到的 PGC 片段（gRPC 优先，HTTP 兜底） */
    private var pgcClips: List<SponsorSegment> = emptyList()

    override suspend fun getSponsorSegments(cid: String): List<SponsorSegment> = pgcClips

    /**
     * 一条 PGC clip → 片段。
     *
     * clipType 映射：OP→开场动画、ED→片尾、AD→赞助/恰饭；
     * **HE / MULTI_VIEW / 未知一律不处理** —— 这里和 PiliPlus 不同（它把未知类型兜底成 sponsor），
     * 因为那些类型覆盖的是正片内容，按"赞助"自动跳过会误伤。
     */
    private fun toPgcSegment(
        startSec: Double,
        endSec: Double,
        clipType: String,
        toastText: String = "",
    ): SponsorSegment? {
        val category = when (clipType) {
            "CLIP_TYPE_OP" -> SponsorCategory.Intro.id
            "CLIP_TYPE_ED" -> SponsorCategory.Outro.id
            "CLIP_TYPE_AD" -> SponsorCategory.Sponsor.id
            else -> return null
        }
        if (endSec <= startSec) return null
        return SponsorSegment(
            // PGC 没有服务端 UUID：造一个稳定的本地 ID（前缀 pgc- 用于"不上报"判定）
            UUID = "pgc-$clipType-$startSec-$endSec",
            category = category,
            actionType = SponsorActionType.Skip.id,
            segment = listOf(startSec, endSec),
            cid = id,
            description = toastText,
        )
    }

    override suspend fun getVideoShot(): PlayerAPI.VideoShotData? {
        return try {
            // 番剧同样用 aid + cid 取缩略图；部分剧集没有这数据 → 返回 null 走降级
            // （aid 必须是纯数字，PlayerAPI 里会归一化；不是数字就返回 null 降级）
            com.a10miaomiao.bilimiao.comm.utils.PreviewDiag.log("BangumiPlayerSource: aid=$aid cid=$id")
            BiliApiService.playerAPI.getVideoShot(aid = aid, cid = id)?.toHttps()
        } catch (e: Exception) {
            null
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

    override fun previous(): BasePlayerSource? {
        val index = episodes.indexOfFirst { it.cid == id }
        val prevIndex = index - 1
        if (prevIndex in episodes.indices) {
            val prevEpisode = episodes[prevIndex]
            val prevPlayerSource = BangumiPlayerSource(
                sid = sid,
                epid = prevEpisode.epid,
                aid = prevEpisode.aid,
                id = prevEpisode.cid,
                title = prevEpisode.index_title.ifBlank { prevEpisode.index },
                coverUrl = prevEpisode.cover,
                ownerId = ownerId,
                ownerName = ownerName,
            )
            prevPlayerSource.episodes = episodes
            return prevPlayerSource
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