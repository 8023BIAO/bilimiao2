package com.a10miaomiao.bilimiao.comm.delegate.player.entity

import com.a10miaomiao.bilimiao.comm.apis.PlayerAPI
import com.a10miaomiao.bilimiao.comm.utils.PlayerDiag
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.comm.utils.VideoCodecSupport

class DashSource(
    val uposHost: String = "",
) {

    private data class DashItem(
        val id: Int,
        val baseUrl: String,
        val backupUrl: List<String>,
        val bandwidth: Int,
        val codecsId: Int,
        val codecs: String,
        val width: Int,
        val height: Int,
        val mimeType: String,
        val frameRate: String,
        val minBufferTime: Double?,
        val segmentBase: SegmentBase?,
    )

    private data class SegmentBase(
        val initialization: String,
        val indexRange: String,
    )

    private fun codecidToCodecString(codecid: Int): String = when (codecid) {
        7 -> "avc1.64001F"
        12 -> "hev1.1.6.L93.90"
        13 -> "av01.0.08M.08"   // vc110 补：AV1（原来返回空串，走 MPD 时会缺 codecs）
        else -> ""
    }

    private fun replaceHostToUposHost(url: String): String {
        if (uposHost.isEmpty()) return url
        return UrlUtil.replaceHost(url, uposHost)
    }

    /**
     * 生成多 CDN 的 BaseURL XML 片段。
     * primaryUrl + backupUrls → 多个 <BaseURL> 元素，ExoPlayer 自动故障转移。
     */
    /**
     * **XML 文本转义** —— 这个 MPD 播不出来的直接原因就是漏了它。
     *
     * B 站 CDN 的 URL 里全是 `&`（`...&nbs=1&os=hwbv&og=hw&platform=android&...`），
     * 直接塞进 `<BaseURL>` 会让整个 MPD 变成**非良构 XML**（实测 144 个裸 &，解析在
     * line 1 column 658 就炸）→ `DashManifestParser` 抛异常 → `getMediaSource` 返回 null →
     * GSY 把整串 `[dash-mpd]\n…` 当 URL 打开 → `MalformedURLException: no protocol: [dash-mpd]`。
     *
     * 顺序必须是**先转 `&`**，否则会把后面生成的 `&amp;` 二次转义成 `&amp;amp;`。
     */
    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** MPD 必须真的是良构 XML，否则调用方要回退（别让播放器拿到一串假 URL） */
    fun isWellFormedXml(mpd: String): Boolean = try {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.newDocumentBuilder().parse(mpd.byteInputStream())
        true
    } catch (e: Throwable) {
        false
    }

    private fun buildBaseUrlElements(
        primaryUrl: String,
        backupUrls: List<String>,
    ): String {
        val allUrls = mutableListOf<String>()
        allUrls.add(if (uposHost == "backup") {
                backupUrls.firstOrNull { it.isNotBlank() } ?: primaryUrl
            } else if (uposHost.isNotEmpty()) {
                replaceHostToUposHost(primaryUrl)
            } else primaryUrl
            )
        for (backup in backupUrls) {
            if (uposHost == "backup") continue  // backup模式下跳过原始backupUrl，避免重复
            val url = if (uposHost.isNotEmpty()) replaceHostToUposHost(backup) else backup
            if (url.isNotBlank()) {
                allUrls.add(url)
            }
        }
        return allUrls.distinct().joinToString("\n") { url ->
            "                    <BaseURL>${xmlEscape(url)}</BaseURL>"
        }
    }

    /**
     * ★ `<SegmentBase>` **必须放在 `<Representation>` 里面**（而且要在 `<BaseURL>` 之后）。
     *
     * 为什么（2026-09-19 定位到的番剧 DASH 播不出来的根因）：
     * media3 的 `DashManifestParser` 是**顺序解析**的 —— 遇到 `<Representation>` 时就地用
     * "此刻已解析到的 segmentBase" 构造 Representation；如果 `<SegmentBase>` 写在
     * `<Representation>` 后面（我们以前就是这么写的），解析时它还是 null →
     * **SegmentBase 被整个忽略** → ExoPlayer 把整个 .m4s 当成一个巨大分段（等于整集一次拉完），
     * 表现就是"选了 DASH 播不出来/一直转圈"。普通视频走的是 [merging]（两条流）所以看不出问题，
     * 只有番剧/影视会走到这条 MPD 路径。
     */
    private fun getSegmentBaseXml(segmentBase: SegmentBase?): String {
        if (segmentBase == null) return ""
        return "<SegmentBase indexRange=\"${segmentBase.indexRange}\">" +
                "<Initialization range=\"${segmentBase.initialization}\" />" +
                "</SegmentBase>"
    }

    private fun getMDPUrl(
        video: DashItem,
        audio: DashItem?,
        duration: Long,
    ): String {
        val videoBaseUrls = buildBaseUrlElements(video.baseUrl, video.backupUrl)
        val mpdStr = """
<MPD xmlns="urn:mpeg:DASH:schema:MPD:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" mediaPresentationDuration="PT${duration}S" minBufferTime="PT1.5S">
    <Period start="PT0S">
        <AdaptationSet>
            <ContentComponent contentType="video" id="1" />
            <Representation bandwidth="${video.bandwidth}" codecs="${video.codecs}" height="${video.height}" id="${video.id}" mimeType="${video.mimeType}" width="${video.width}">
$videoBaseUrls
                ${getSegmentBaseXml(video.segmentBase)}
            </Representation>
        </AdaptationSet>
        ${
            if (audio != null) {
                val audioBaseUrls = buildBaseUrlElements(audio.baseUrl, audio.backupUrl)
                """
                <AdaptationSet>
                    <ContentComponent contentType="audio" id="2" />
                    <Representation bandwidth="${audio.bandwidth}" codecs="${audio.codecs}" id="${audio.id}" mimeType="${audio.mimeType}" >
$audioBaseUrls
                        ${getSegmentBaseXml(audio.segmentBase)}
                    </Representation>
                </AdaptationSet>
                """.trimIndent()
            } else {
                ""
            }
        }
    </Period>
</MPD>
        """.trimIndent()
        // ★ 记一笔：SegmentBase 有没有真的写进 MPD、Representation 里长什么样。
        //   番剧 DASH 播不出来的那次，就是因为它被写在了 <Representation> 外面（被解析器忽略）。
        val wellFormed = isWellFormedXml(mpdStr)
        runCatching {
            PlayerDiag.log(
                "mpd",
                "SegmentBase=${if (mpdStr.contains("<SegmentBase")) "已写入 Representation 内" else "缺失！"}" +
                    // 注意：videoBaseUrls 是 String（多个 <BaseURL> 用换行拼起来的），
                    // 不是集合 —— 对它用 count{} 拿到的是 Char，Char 没有 contains(CharSequence)，
                    // 会编译报 "receiver type mismatch"（vc100 首次编译就是这么挂的）。
                    // 数出现次数用 split：出现 n 次 → 切成 n+1 段 → n
                    " | baseUrl 数=${videoBaseUrls.split("<BaseURL>").size - 1}" +
                    " | audio=${if (audio != null) "有" else "无"}" +
                    " | XML=${if (wellFormed) "良构 ✓" else "非良构 ✗（& 没转义？）"}"
            )
        }
        if (!wellFormed) {
            // 宁可让调用方回退成 [merging]（能播），也不要把假 URL 交给播放器
            return ""
        }
        val primaryUrl = if (uposHost == "backup") {
            video.backupUrl.firstOrNull { it.isNotBlank() } ?: video.baseUrl
        } else if (uposHost.isNotEmpty()) {
            replaceHostToUposHost(video.baseUrl)
        } else video.baseUrl
        return "[dash-mpd]\n" + primaryUrl + "\n" + mpdStr.replace("\n", "")
    }

    // ---------- gRPC PGC ----------

    fun getMDPUrl(
        videoId: Int,
        videoFormat: String,
        video: bilibili.pgc.gateway.player.v2.DashVideo,
        audio: bilibili.pgc.gateway.player.v2.DashItem?,
        durationMs: Long,
    ): String {
        return getMDPUrl(
            video = DashItem(
                id = videoId,
                baseUrl = video.baseUrl,
                backupUrl = video.backupUrl,
                bandwidth = video.bandwidth,
                codecsId = video.codecid,
                codecs = codecidToCodecString(video.codecid),
                width = video.width,
                height = video.height,
                mimeType = "video/${videoFormat}",
                frameRate = video.frameRate,
                minBufferTime = null,
                segmentBase = null,
            ),
            audio = audio?.let {
                DashItem(
                    id = it.id,
                    baseUrl = it.baseUrl,
                    backupUrl = it.backupUrl,
                    bandwidth = it.bandwidth,
                    codecsId = it.codecid,
                    codecs = codecidToCodecString(it.codecid),
                    width = 0,
                    height = 0,
                    mimeType = "audio/${videoFormat}",
                    frameRate = it.frameRate,
                    minBufferTime = null,
                    segmentBase = null,
                )
            },
            duration = durationMs / 1000,
        )
    }

    // ---------- gRPC app ----------

    fun getMDPUrl(
        videoId: Int,
        videoFormat: String,
        video: bilibili.app.playurl.v1.DashVideo,
        audio: bilibili.app.playurl.v1.DashItem?,
        durationMs: Long,
    ): String {
        return getMDPUrl(
            video = DashItem(
                id = videoId,
                baseUrl = video.baseUrl,
                backupUrl = video.backupUrl,
                bandwidth = video.bandwidth,
                codecsId = video.codecid,
                codecs = codecidToCodecString(video.codecid),
                width = video.width,
                height = video.height,
                mimeType = "video/${videoFormat}",
                frameRate = video.frameRate,
                minBufferTime = null,
                segmentBase = null,
            ),
            audio = audio?.let {
                DashItem(
                    id = it.id,
                    baseUrl = it.baseUrl,
                    backupUrl = it.backupUrl,
                    bandwidth = it.bandwidth,
                    codecsId = it.codecid,
                    codecs = codecidToCodecString(it.codecid),
                    width = 0,
                    height = 0,
                    mimeType = "audio/${videoFormat}",
                    frameRate = it.frameRate,
                    minBufferTime = null,
                    segmentBase = null,
                )
            },
            duration = durationMs / 1000,
        )
    }

    // ---------- JSON ----------

    fun getMDPUrl(
        dashData: PlayerAPI.Dash,
        quality: Int,
    ): String {
        // vc110：同样按本机解码能力挑编码（老设备别被 AV1 拖死）
        val video = VideoCodecSupport.pickBest(
            list = dashData.video,
            qualityOf = { it.id },
            codecsOf = { it.codecs },
            quality = quality,
        ) ?: dashData.video.lastOrNull() ?: return ""
        val audio = dashData.audio?.firstOrNull()
        return getMDPUrl(
            video = DashItem(
                id = video.id,
                baseUrl = video.base_url,
                backupUrl = video.backup_url ?: listOf(),
                bandwidth = video.bandwidth,
                codecsId = video.codecid,
                codecs = video.codecs,
                width = video.width,
                height = video.height,
                mimeType = video.mime_type,
                frameRate = video.frame_rate,
                minBufferTime = dashData.min_buffer_time,
                segmentBase = video.segment_base?.let { sb ->
                    SegmentBase(
                        initialization = sb.initialization,
                        indexRange = sb.index_range,
                    )
                },
            ),
            audio = audio?.let {
                DashItem(
                    id = it.id,
                    baseUrl = it.base_url,
                    backupUrl = it.backup_url ?: listOf(),
                    bandwidth = it.bandwidth,
                    codecsId = it.codecid,
                    codecs = it.codecs,
                    width = 0,
                    height = 0,
                    mimeType = it.mime_type,
                    frameRate = it.frame_rate,
                    minBufferTime = null,
                    segmentBase = it.segment_base?.let { sb ->
                        SegmentBase(
                            initialization = sb.initialization,
                            indexRange = sb.index_range,
                        )
                    },
                )
            },
            duration = dashData.duration,
        )
    }
}
