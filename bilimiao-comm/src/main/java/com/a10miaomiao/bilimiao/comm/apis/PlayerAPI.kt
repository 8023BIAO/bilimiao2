package com.a10miaomiao.bilimiao.comm.apis

import android.os.SystemClock
import android.widget.Toast
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.exception.AreaLimitException
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.utils.BvUtils
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.proxy.ProxyServerInfo
import com.a10miaomiao.bilimiao.comm.utils.PlayerDiag
import com.a10miaomiao.bilimiao.comm.utils.PreviewDiag
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

class PlayerAPI {

    val DEFAULT_REFERER = "https://www.bilibili.com/"
    val DEFAULT_USER_AGENT = "Bilibili Freedoooooom/MarkII"

    private fun getVideoHeaders(avid: String) = mapOf(
        "Referer" to "https://www.bilibili.com/av$avid",
        "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/63.0.3239.84 Safari/537.36"
    )


    fun getPlayerV2Info(
        aid: String,
        cid: String,
    ) = MiaoHttp.request {
        url = BiliApiService.biliApi(
            "x/player/v2",
            "aid" to aid,
            "cid" to cid,
        )
    }

    fun getPlayerV2Info(
        aid: String,
        cid: String,
        epId: String,
        seasonId: String,
    ) = MiaoHttp.request {
        url = BiliApiService.biliApi(
            "x/player/v2",
            "aid" to aid,
            "cid" to cid,
            "ep_id" to epId,
            "season_id" to seasonId,
        )
    }

    /**
     * 获取视频播放地址
     * fnval: 976:flv,1:mp4,4048:dash
     */
    suspend fun getVideoPalyUrl(
        avid: String,
        cid: String,
        quality: Int = 64,
        fnval: Int = 4048,
// TODO AI 原声翻译：暂时关闭。恢复时把这段注释放开。
//         /** AI 原声翻译语言（null/空 = 原声）：写进 cur_language，服务端返回翻译后的音轨/字幕 */
//         language: String? = null,
    ): PlayurlData {
        val params = mutableMapOf<String, String?>(
            "avid" to avid,
            "cid" to cid,
            "qn" to quality.toString(),
            "fnval" to fnval.toString(),
            "fnver" to "0",
            "force_host" to "2", // 强制音视频返回 https
            "type" to "",
            "otype" to "json",
        )
        // TODO AI 原声翻译：暂时关闭（原来是 cur_language 写入）
        if (fnval > 2) {
            params.put("fourk", "1")
        }
        // ★ 诊断日志（用户 2026-09-19 要求）：把"我们到底请求了什么、服务端给了什么"写下来。
        //   清晰度不是客户端能"解锁"的：qn/fnval/fourk 只是"我想要什么"，
        //   真正给什么由服务端按**账号权限 + 内容**决定（accept_quality 就是它给的清单）。
        PlayerDiag.log(
            "playurl-req",
            "x/player/playurl (UGC) avid=$avid cid=$cid qn=$quality fnval=$fnval " +
                "fourk=${params["fourk"] ?: "0"} otype=json module=普通视频"
        )
        val res = MiaoHttp.request {
            url = BiliApiService.biliApi("x/player/playurl", *params.toList().toTypedArray())
            headers.putAll(getVideoHeaders(avid))
        }.awaitCall().json<ResponseData<PlayurlData>>()
        if (res.isSuccess) {
            val data = res.requireData()
            PlayerDiag.log(
                "playurl-res",
                "quality=${data.quality} accept=${data.accept_quality} " +
                    "acceptDesc=${data.accept_description} " +
                    "dash=${if (data.dash != null) "有" else "无"} durl=${data.durl?.size ?: 0}段"
            )
            return data
        } else {
            throw Exception(res.message)
        }
    }

    /**
     * 获取番剧播放地址
     */
    suspend fun getBangumiUrl(
        epid: String,
        cid: String,
        qn: Int = 64,
        fnval: Int = 4048,
// TODO AI 原声翻译：暂时关闭。恢复时把这段注释放开。
//         /** AI 原声翻译语言（null/空 = 原声） */
//         language: String? = null,
    ): PlayurlData {
        val params = mutableMapOf<String, String?>(
            "ep_id" to epid,
            "cid" to cid,
            "fnval" to fnval.toString(),
            "fnver" to "0",
            "force_host" to "2", // 强制音视频返回 https
            "module" to "bangumi",
            "qn" to qn.toString(),
            "season_type" to "1",
            "session" to ApiHelper.getMD5((System.currentTimeMillis() - SystemClock.currentThreadTimeMillis()).toString()),
            "track_path" to "",
            "device" to "android",
            "mobi_app" to "android",
            "platform" to "android"
        )
        // TODO AI 原声翻译：暂时关闭（原来是 cur_language 写入）
        if (fnval > 2) {
            params["fourk"] = "1"
        }
        // ★ 诊断日志：番剧走的是 pgc 接口，参数和普通视频不同（module=bangumi、fourk=1）
        PlayerDiag.log(
            "playurl-req",
            "pgc/player/api/playurl (番剧) ep_id=$epid cid=$cid qn=$qn fnval=$fnval " +
                "fourk=${params["fourk"] ?: "0"} module=bangumi season_type=1"
        )
        val res = MiaoHttp.request {
            url = BiliApiService.biliApi(
                "pgc/player/api/playurl",
                *params.toList().toTypedArray()
            )
        }.awaitCall().json<PlayurlData>()
        if (res.code == 0) {
            PlayerDiag.log(
                "playurl-res",
                "code=0 quality=${res.quality} accept=${res.accept_quality} " +
                    "acceptDesc=${res.accept_description} " +
                    "dash=${if (res.dash != null) "有" else "无"} durl=${res.durl?.size ?: 0}段"
            )
            return res
        } else if (res.code == -10403) {
            throw AreaLimitException()
        } else {
            throw Exception(res.message)
        }
    }

    suspend fun getProxyBangumiUrl(
        epid: String,
        cid: String,
        qn: Int = 64,
        fnval: Int = 4048,
        proxyServer: ProxyServerInfo,
    ): PlayurlData {
        val params = mutableMapOf<String, String?>(
            "ep_id" to epid,
            "cid" to cid,
            "fnval" to fnval.toString(),
            "fnver" to "0",
            "force_host" to "2", // 强制音视频返回 https
            "module" to "bangumi",
            "qn" to qn.toString(),
            "season_type" to "1",
            "session" to ApiHelper.getMD5((System.currentTimeMillis() - SystemClock.currentThreadTimeMillis()).toString()),
            "track_path" to "",
            "device" to "android",
            "mobi_app" to "android",
            "platform" to "android",
        )
        if (fnval > 2) {
            params["fourk"] = "1"
        }
        if (!proxyServer.isTrust) {
            params["notoken"] = "1"
        }
        val res = MiaoHttp.request {
            if (proxyServer.enableAdvanced == true) {
                // 启用高级设置，自定义请求参数和请求头
//                headers["x-from-biliroaming"] = "1.6.12"
//                params["area"] = "hk"
                proxyServer.queryArgs?.forEach {
                    if (it.enable && it.key.isNotBlank()) {
                        params[it.key] = it.value
                    }
                }
                proxyServer.headers?.forEach {
                    if (it.enable
                        && it.name.isNotBlank()
                        && it.value.isNotBlank()) {
                        headers[it.name] = it.value
                    }
                }
            }
            url = BiliApiService.createUrl(
                "https://${proxyServer.host}/pgc/player/api/playurl",
                *params.toList().toTypedArray()
            )
        }.awaitCall().json<PlayurlData>()
        if (res.code == 0) {
            return res
        } else if (res.code == -10403) {
            throw AreaLimitException()
        } else {
            throw Exception(res.message)
        }
    }

    fun getDanmakuList(cid: String): MiaoHttp {
        return MiaoHttp.request {
            url = "https://comment.bilibili.com/$cid.xml"
        }
    }

    /**
     * 进度条拖动预览图（B 站的"视频缩略图雪碧图"）。
     *
     * 返回一张（长视频是多张）拼接大图 + 每小格对应的起始秒数，客户端按拖动位置取格子。
     * 做法对齐 PiliPlus（`lib/http/video.dart:1052` 起 `videoshot()`）：web 风格请求 + index=1，
     * 不塞 appkey/sign（APP 参数会让服务端按客户端语义处理，历史上踩过坑）。
     *
     * 没有预览图的情况很常见：视频太短、番剧部分剧集、风控拦截 —— 一律返回 null，
     * 调用方按"这个视频没有预览图"降级（拖动时只显示时间气泡）。
     */
    suspend fun getVideoShot(aid: String, cid: String, bvid: String? = null): VideoShotData? {
        if (cid.isBlank()) return null
        // ★ 这个接口的 `aid` 只认**纯数字 av 号**：塞 BV 号进去会直接被拒
        //   （2026-09 起 B 站收紧了参数校验，实测 `aid=BV1Ba4y137M7` → {"code":-400,"message":"请求错误"}，
        //    而 `bvid=BV1Ba4y137M7` 正常返回）。以前把 BV 塞 aid 也能过，所以一直没暴露 ——
        //   结果就是**所有以 BV 号打开的普通视频都没有拖动预览图**。这里改成：有数字 av 号用 aid，否则退回 bvid 参数。
        val rawAid = aid.removePrefix("av").trim()
        val numericAid = rawAid.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        // 两个字段都可能装"错"东西（aid 里放 BV、bvid 里放 av 号），所以两个都试一遍，谁能当 BV 用谁
        val bv = listOfNotNull(bvid?.trim(), rawAid).firstOrNull() {
            it.isNotEmpty() && BvUtils.isValidBvid(it)
        }
        PreviewDiag.log("getVideoShot in: aid=$aid bvid=$bvid -> numericAid=$numericAid bv=$bv cid=$cid")
        if (numericAid == null && bv == null) {
            PreviewDiag.log("getVideoShot 放弃：aid/bvid 都不是可用形态")
            return null
        }

        // 另外这接口偶尔返回"空壳"（image 有、index 是空数组，实测约 1/6 概率），
        // 而 index（每格结束时刻）没有就一格都取不出来 → 表现为"这次拖动没预览图"。
        // 所以最多试 3 次；重试时加个时间戳参数，岔开可能存在的中间缓存。
        repeat(3) { attempt ->
            val params = mutableMapOf(
                "cid" to cid,
                "index" to "1",
            )
            if (numericAid != null) params["aid"] = numericAid else params["bvid"] = bv!!
            if (attempt > 0) params["_"] = System.currentTimeMillis().toString()
            val res = try {
                MiaoHttp.request {
                    // web 语义：不加 app-key/env/Authorization，只带 Cookie + WBI 签名
                    isWebApi = true
                    url = "https://api.bilibili.com/x/player/videoshot?" + ApiHelper.urlencode(params)
                    headers["Referer"] = "https://www.bilibili.com/video/av${numericAid ?: bv}"
                }.awaitCall().json<ResponseData<VideoShotData>>()
            } catch (e: Exception) {
                PreviewDiag.log("getVideoShot 异常: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            val data = res?.takeIf { it.isSuccess }?.data
            PreviewDiag.log(
                "getVideoShot 第${attempt + 1}次: code=${res?.code} msg=${res?.message} " +
                    "image=${data?.image?.size ?: -1} index=${data?.index?.size ?: -1}"
            )
            if (data != null && data.index.isNotEmpty() && data.image.isNotEmpty()) return data
            if (attempt < 2) delay(350L)
        }
        return null
    }

    /**
     * 缩略图雪碧图数据。
     *
     * 取第 i 张小格的算法（与 PiliPlus 一致）：
     *   page = i / (img_x_len * img_y_len)   —— 第几张雪碧图
     *   col  = i % img_x_len                 —— 图内列
     *   row  = i / img_x_len                 —— 图内行
     * 注意 PiliPlus 这里写的是 `i ~/ imgYLen`（列数≠行数时会错行）；我们按列数取整，
     * B 站目前是 10×10 所以两者等价，但列行不等时我们是对的。
     */
    @Serializable
    data class VideoShotData(
        val pvdata: String? = null,
        val img_x_len: Int = 0,
        val img_y_len: Int = 0,
        val img_x_size: Double = 0.0,
        val img_y_size: Double = 0.0,
        /** 雪碧图 URL 列表（http 会被换成 https） */
        val image: List<String> = emptyList(),
        /** 每小格的起始时间，单位秒 */
        val index: List<Int> = emptyList(),
    ) {
        /** 一张雪碧图里有几小格 */
        val totalPerImage: Int get() = img_x_len * img_y_len

        fun toHttps(): VideoShotData =
            copy(image = image.map { UrlUtil.autoHttps(it) })
    }

    fun sendDamaku(
        msg: String,
        aid: String,
        oid: String,
        progress: Long,
        color: Int,
        fontsize: Int,
        mode: Int, // 1：普通弹幕, 4：底部弹幕, 5：顶部弹幕, 7：高级弹幕, 9：BAS弹幕（pool必须为2）
    ) = MiaoHttp.request {
        url = BiliApiService.biliApi(
            "x/v2/dm/post"
        )
        formBody = mapOf(
            "msg" to msg,
            "type" to "1",
            "aid" to aid,
            "oid" to oid,
            "progress" to progress.toString(),
            "color" to color.toString(),
            "fontsize" to fontsize.toString(),
            "mode" to mode.toString(),
            "rnd" to System.currentTimeMillis().toString(),
        )
        method = MiaoHttp.POST
    }

    @Serializable
    data class PlayurlData(
        val accept_description: List<String> = emptyList(),
        val accept_format: String = "",
        val accept_quality: List<Int> = emptyList(),
        val format: String = "",
        val from: String = "",
        val message: String,
        val quality: Int = 0,
        val result: String = "",
        val seek_param: String = "",
        val seek_type: String = "",
        // 时长，毫秒
        val timelength: Int = 0,
        val video_codecid: Int = 0,
        val durl: List<Durl>? = null,
        val dash: Dash? = null,
        val code: Int = 0,
        val support_formats: List<SupportFormats> = emptyList(),
        val last_play_time: Long? = null,
// TODO AI 原声翻译：暂时关闭。恢复时把这段注释放开。
//         /**
//          * AI 原声翻译的可选语言（B 站"AI 翻译"）。
//          * 只有 HTTP playurl 会返回；带 cur_language 请求时返回对应语言的音轨/字幕。
//          */
//         val language: LanguageInfo? = null,
        val last_play_cid: String? = null,
        /**
         * 番剧/影视的"跳过片头片尾"配置（PGC 才有）。
         * 每项：`{start, end, clipType}`，start/end 单位**秒**；clipType 形如 CLIP_TYPE_OP/CLIP_TYPE_ED。
         */
        // 可空：兄弟字段 durl/dash 都是可空的，这里写死非空的话，
        // 服务端一旦返回 "clip_info_list": null 会让**整个** PlayurlData 反序列化抛异常，
        // 连带把 HTTP 播放回退链打断（丢的不只是跳过片头片尾）
        val clip_info_list: List<ClipInfo>? = null,
    )

// TODO AI 原声翻译：暂时关闭。恢复时把这段注释放开。
//     /** AI 翻译语言列表（playurl 响应的 language 字段） */
//     @Serializable
//     data class LanguageInfo(
//         val items: List<LanguageItem> = emptyList(),
//     )
//
//     /** 单条 AI 翻译语言：lang 形如 ai-zh（AI 中文），title 是给用户看的名字 */
//     @Serializable
//     data class LanguageItem(
//         val lang: String = "",
//         val title: String? = null,
//     )

    /** 番剧"跳过片头/片尾"的一项（HTTP playurl 的 clip_info_list） */
    @Serializable
    data class ClipInfo(
        val start: Double = 0.0,
        val end: Double = 0.0,
        val clipType: String = "",
    )

    @Serializable
    data class Durl(
        val ahead: String,
        val length: Long,
        val order: Int,
        val size: Long,
        val url: String,
        val vhead: String
    )

    @Serializable
    data class SupportFormats(
        val quality: Int,
        val format: String,
        val new_description: String,
        val display_desc: String,
        val superscript: String
    )

    @Serializable
    data class Dash(
        // 时长，秒
        val duration: Long,
        val min_buffer_time: Double,
        val video: List<DashItem>,
        val audio: List<DashItem>?,
    )

    @Serializable
    data class DashItem(
        val id: Int,
        val bandwidth: Int,
        val base_url: String,
        val backup_url: List<String>?,
        val mime_type: String,
        val codecid: Int,
        val codecs: String,
        val width: Int,
        val height: Int,
        val frame_rate: String,
        val segment_base: SegmentBase,
    )

    @Serializable
    data class SegmentBase(
        val initialization: String,
        val index_range: String,
    )

}