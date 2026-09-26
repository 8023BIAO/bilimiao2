package com.a10miaomiao.bilimiao.comm.live

import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.live.entity.LivePlayUrl
import com.a10miaomiao.bilimiao.comm.live.entity.LivePlayUrlInfo
import com.a10miaomiao.bilimiao.comm.live.entity.LiveStatus
import com.a10miaomiao.bilimiao.comm.live.entity.fullUrls

/**
 * "这个直播间现在到底能不能播"的**唯一判据**（纯新增，2026-09-26）。
 *
 * ## 为什么必须单独有这么一个判据（本文件要解决的误判）
 * 修前，播放页有**两处**都只看 `live_status` 就下结论：
 * 1. `LivePlayerActivity.startResolveAndPlay()`：`if (!LiveStatus.isPlayable(init.live_status))` →
 *    直接报"房间未开播（live_status=X）"，**连一次取流都不试**；
 * 2. `LivePlayerDelegate.fetchOnce()`：`if (playurl == null) → Offline(live_status)` →
 *    UI 文案同样是"房间未开播"。
 *
 * 这两处把"我拿不到流"直接翻译成了"房间没开播"。而**实测（2026-09-26，容器内 curl）**，
 * "拿不到流"至少有 4 种完全不同的原因，只有其中一种真的是"没开播"：
 *
 * | 实测现象 | 真实原因 | 修前文案 |
 * |---|---|---|
 * | `code=1002002 参数错误` + `playurl_info=null` + `live_status=1` | **请求形状不对**：样本房 11218604 要求 `protocol`/`format`/`codec` **三个参数同时给**，少一个就 1002002 | "未开播" |
 * | `code=0` + `playurl_info=null` + `live_status=1` | **服务端这次没下发流**（在播房间也会出现：推流刚重启 / 风控 / 降级），下一条请求可能就有了 | "未开播" |
 * | `live_status=2` + `playurl_info=null` | **轮播**（房间在放回放，接口不给流；实测 24567/32421 两个轮播房都是 null） | "未开播" |
 * | `encrypted=true` / 付费 / 地区限制 | **权限问题**（官方页面能看、第三方拿不到流） | "未开播" |
 *
 * 所以判据必须落在**服务端到底有没有下发可用的流**（`playurl_info.playurl.stream[].format[].codec[].url_info[]`），
 * `live_status` 只用来**区分措辞**（未开播 / 轮播 / 在播但没流 / 权限），不用来做"能不能播"的结论。
 *
 * ## 用法（调用方只需要看 [LivePlayability.hasStream] 和 [LivePlayability.uiText]）
 * ```
 * when (val v = LivePlayabilityJudge.judge(res)) {   // res: ResponseData<LivePlayUrlInfo>
 *     is LivePlayability.Playable -> 用 v.playurl 继续 buildCandidates/起播
 *     else -> 报 v.uiText；v.retryable 为 true 时保留自动重试
 * }
 * ```
 *
 * ★纯新增：本文件不改任何既有类型/函数的语义，`LiveStatus.isPlayable`（角标那套"只认 1"）
 *   仍然照旧 —— 角标是"挂不挂直播中标记"的取舍，和"进来能不能播"不是一回事。
 */
sealed interface LivePlayability {

    /** 能不能**真的起播**：只有它为 true 才允许继续建 MediaSource。 */
    val hasStream: Boolean

    /**
     * 直接给状态栏用的一句话（**说人话**）。
     * ★不要在这些文案里出现 `live_status=0/1/2` 这种接口术语 —— 那是给日志看的，
     *   用户看到"明明在播却说没开播"时，最需要的是"到底卡在哪一步"。
     */
    val uiText: String

    /** 值不值得**自动重试**（网络/风控/服务端没下发流都值得；没开播/轮播/没权限不值得）。 */
    val retryable: Boolean

    /** 成功：`playurl` 里有 **可用** 的 URL（不是"code=0"就算）。 */
    class Playable(
        val playurl: LivePlayUrl,
        val urlCount: Int,
        val protocols: List<String>,
    ) : LivePlayability {
        override val hasStream: Boolean get() = true
        override val uiText: String get() = "直播中"
        override val retryable: Boolean get() = false
    }

    /** 轮播（`live_status=2`）：房间在放回放，**web 接口不下发流**（实测 playurl_info=null）。 */
    class Round(val liveStatus: Int) : LivePlayability {
        override val hasStream: Boolean get() = false
        override val uiText: String get() = "轮播中：接口没有下发直播流（官方页面放的是回放）"
        override val retryable: Boolean get() = true
    }

    /** 真·未开播（`live_status=0` 且确实没有流）。 */
    class Offline(val liveStatus: Int) : LivePlayability {
        override val hasStream: Boolean get() = false
        override val uiText: String get() = "主播未开播"
        override val retryable: Boolean get() = true
    }

    /** 权限类：密码房 / 付费 / 地区限制 —— 官方页面能看、第三方拿不到流就是这一类。 */
    class Gated(val reason: String) : LivePlayability {
        override val hasStream: Boolean get() = false
        override val uiText: String get() = reason
        override val retryable: Boolean get() = false
    }

    /**
     * **不是"未开播"的失败**：接口报错 / 服务端没下发流 / stream 里一条可用线路都没有。
     * ★这一类最容易被旧代码写成"未开播"，也是本次用户反馈（"明明在播却显示未开播"）的正主。
     */
    class NoStream(val detail: String) : LivePlayability {
        override val hasStream: Boolean get() = false
        override val uiText: String get() = detail
        override val retryable: Boolean get() = true
    }
}

/** [LivePlayability] 的构造器：把一次 `getRoomPlayInfo` 的响应翻译成"能不能播 + 为什么"。 */
object LivePlayabilityJudge {

    /** 触发 1002002 的典型请求形状缺失（样本房 11218604 实测）。 */
    const val CODE_PARAM_ERROR = 1002002

    /**
     * 能不能**尝试**起播（进房门神 / 开播轮询用）。
     *
     * ★只有明确 `live_status=0`（未开播）可以先挡一下（那时连取流都不用试）。
     *   `live_status=2`（轮播）**要放进来试**：实测轮播房确实拿不到流，但那是"试了才知道"，
     *   而且轮播随时可能切成真开播；用 2 当门神的结果就是用户看到假的"未开播"。
     *   其它任何取值（含缺字段解析出来的 0/未知）也一律放行 —— 让最权威的那一步（取流）决定。
     */
    fun shouldAttemptPlay(liveStatus: Int): Boolean = liveStatus != LiveStatus.OFF

    /** 直接判 `ResponseData<LivePlayUrlInfo>`（`code != 0` 也要能给出人话原因）。 */
    fun judge(res: ResponseData<LivePlayUrlInfo>): LivePlayability =
        judge(res.data, res.code, res.message)

    /**
     * @param data 服务端返回的 `data`（`code != 0` 时也可能带着 `live_status`，别丢）
     * @param code 业务 code（0 = 成功）
     * @param message 业务 message，用于拼人话文案
     */
    fun judge(data: LivePlayUrlInfo?, code: Int, message: String? = null): LivePlayability {
        if (code != 0) return failed(code, message)
        if (data == null) return LivePlayability.NoStream("取流失败：服务端没有返回房间数据")

        // 权限类优先判：这类房间"官方页面能看"，但第三方拿不到流，必须如实说清原因
        if (data.encrypted && !data.pwd_verified) {
            return LivePlayability.Gated("该直播间需要密码，App 里无法直接观看")
        }

        val playurl = data.playurl_info?.playurl
        if (playurl == null) {
            return when (data.live_status) {
                LiveStatus.ROUND -> LivePlayability.Round(data.live_status)
                LiveStatus.OFF -> LivePlayability.Offline(data.live_status)
                LiveStatus.LIVE -> LivePlayability.NoStream(
                    "房间在播，但服务端这次没下发播放地址（已在自动重试）"
                )
                // 缺字段/未知取值：绝不能替用户下"没开播"的结论
                else -> LivePlayability.NoStream(
                    "服务端没有下发播放地址（live_status=${data.live_status}）"
                )
            }
        }

        val urls = streamUrls(playurl)
        if (urls.isEmpty()) {
            return LivePlayability.NoStream("服务端下发的 stream 里没有任何可用线路（已自动重试）")
        }
        return LivePlayability.Playable(
            playurl = playurl,
            urlCount = urls.size,
            protocols = playurl.stream.map { it.protocol_name }.filter { it.isNotBlank() }.distinct(),
        )
    }

    /**
     * `stream[] → format[] → codec[] → url_info[]` 展平成**全部候选地址**。
     *
     * ★和 `LiveCodec.fullUrls` 用同一套拼装规则（`host + base_url + extra` 纯字符串相加），
     *   所以"判据说有流"与"播放器真去拉的地址"永远是同一份，不会出现"判据说能播、播放器没地址"。
     */
    fun streamUrls(playurl: LivePlayUrl?): List<String> =
        playurl?.stream.orEmpty()
            .flatMap { it.format }
            .flatMap { it.codec }
            .flatMap { it.fullUrls }
            .distinct()

    /** `code != 0` 时的人话文案：把"参数错误/风控/其它"分开，别一律说成"没开播"。 */
    private fun failed(code: Int, message: String?): LivePlayability {
        val detail = message?.takeIf { it.isNotBlank() && it != "-352" } ?: "接口错误"
        return when (code) {
            // ★样本房 11218604 实测：protocol/format/codec 少给一个就回这个 code，
            //   而响应里 data.live_status 仍然是 1、playurl_info 是 null。
            //   旧代码把它当"取流失败"甚至"未开播"，其实请求形状本身就是错的。
            CODE_PARAM_ERROR -> LivePlayability.NoStream(
                "取流参数错误（code=$CODE_PARAM_ERROR）：该房间要求 protocol/format/codec 三个参数齐全"
            )
            // 风控：B 站对直播接口的高频调用会回 -352 / -412，等一下再来就有
            -352, -412 -> LivePlayability.NoStream("接口被风控拦截（code=$code），稍后自动重试")
            else -> LivePlayability.NoStream("取流失败：$detail（code=$code）")
        }
    }
}
