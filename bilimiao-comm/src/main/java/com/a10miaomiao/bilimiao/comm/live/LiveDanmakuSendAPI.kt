package com.a10miaomiao.bilimiao.comm.live

import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.live.danmaku.LiveDanmakuTrace
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.utils.WbiSigner
import kotlinx.serialization.Serializable

/**
 * 直播**发送弹幕**接口（第三阶段修复新增，方案里"发弹幕按钮"的能力侧）。
 *
 * ## 接口怎么来的（只许查本机参考实现的约束下）
 * **本机参考实现里只有 PiliPlus 有发送弹幕**（blbl / BiliPai 的 `LiveMessageClient` 只收不发）：
 * - `PiliPlus/lib/http/api.dart:317` → `sendLiveMsg = '${liveBaseUrl}/msg/send'`（`liveBaseUrl = api.live.bilibili.com`）
 * - `PiliPlus/lib/http/live.dart:37-70` → `LiveHttp.sendLiveMsg(...)`：**POST + form 表单**，
 *   参数清单与 `queryParameters: WbiSign.makSign({'web_location': 444.8})`
 * - 调用点 `PiliPlus/lib/pages/live_room/send_danmaku/view.dart:187`
 * 所以：`POST https://api.live.bilibili.com/msg/send`，表单体 + `web_location=444.8`（PiliPlus 还做了 WBI 签名）。
 *
 * ## 实测（容器有网，2026-09-26；用真实房间 24158116）
 * | 变体 | 实测返回 |
 * |---|---|
 * | 不签名 + 最小参数集（roomid/msg/color/fontsize/mode/rnd） | `{"code":-101,"message":"账号未登录"}` |
 * | **WBI 签名 + PiliPlus 全字段** | `{"code":-101,"message":"账号未登录"}` |
 * | 同上 + 伪造 `SESSDATA`/`bili_jct` Cookie | `{"code":-400,"message":"请求错误"}`（已过登录检查 → 走到 csrf 校验） |
 * | APP 通道（appkey/appsec sign + 伪造 access_key） | `{"code":-400,"message":"请求错误"}` |
 * 结论：
 * 1. **端点与参数集被服务端接受**（两次都只回"未登录"，没有 `-400 请求错误`）；
 * 2. **未登录时 WBI 签名不是必需**（不签名也回 -101 而不是 -352 风控）；
 * 3. 带上 Cookie 后服务端**先认登录态再校验 csrf** → 真正发送必须 `csrf` = cookie 里的 `bili_jct`；
 * 4. 没有任何一条返回 `-352`，说明这条路不是"必签"接口（与 `getDanmuInfo` 的脾气不同）。
 *
 * ## 双通道（为什么两条都留）
 * - **WEB 通道**（首选，有 `bili_jct` 时）：Cookie(SESSDATA) + `csrf`，与 PiliPlus 完全一致，
 *   并按它的做法给 `web_location=444.8` 打 WBI 签名；
 * - **APP 通道**（兜底）：工程里用户可能是**APP 扫码登录**（有 `access_key`、没有 web `bili_jct`），
 *   此时 WEB 通道根本没法过 csrf，只能走 `ApiHelper.createParams` 的 appkey/appsec 签名 + `access_key`。
 *   只有在"有 access_key"且"WEB 通道明确失败"时才降级重试一次 —— 不会双发（前一次既然返回了业务错误码，
 *   服务端就没有落库）。
 */
class LiveDanmakuSendAPI {

    /** 发送结果：`ok` 之外把 code/message 原样带出，方便上层 toast 出"到底为什么失败" */
    data class SendResult(
        val ok: Boolean,
        val code: Int,
        val message: String,
    ) {
        companion object {
            fun fail(code: Int, message: String) = SendResult(false, code, message)
        }
    }

    /**
     * 发送一条弹幕。
     *
     * @param roomId **真实房间号**（短号要先走 `LiveAPI.roomInit`，与收弹幕同一口径）
     * @param text 已清洗过的文本（长度/换行由 [com.a10miaomiao.bilimiao.comm.live.danmaku.LiveDanmakuClient] 把关）
     */
    suspend fun send(roomId: Long, text: String): SendResult {
        val csrf = MiaoHttp.csrfToken()
        val accessKey = BilimiaoCommApp.commApp.loginInfo?.token_info?.access_token
        val hasWebLogin = !csrf.isNullOrBlank()
        val hasAppLogin = !accessKey.isNullOrBlank()

        if (!hasWebLogin && !hasAppLogin) {
            // 没有任何登录态：连试都不用试（省一次风控计数），直接把原因说清楚
            return SendResult.fail(
                CODE_NOT_LOGIN,
                "未登录：既没有 web 的 bili_jct，也没有 APP 的 access_key",
            )
        }

        var last: SendResult? = null

        if (hasWebLogin) {
            // 上面 hasWebLogin 已经判过非空，这里落成非空局部变量（后面要传给 webForm 的 String 形参）
            val csrfValue = csrf ?: ""

            // ① WEB：WBI 签名版（对齐 PiliPlus）
            //    ★显式 WbiScope.LIVE：发弹幕是"需要 WBI 的直播请求"，必须签；
            //      默认 scope 只认白名单（/msg/send 在白名单里，双保险）。
            val signed = runCatching {
                WbiSigner.signUrl("$SEND_URL?web_location=$WEB_LOCATION", WbiSigner.WbiScope.LIVE)
            }.getOrNull() ?: "$SEND_URL?web_location=$WEB_LOCATION"
            last = postForm(signed, webForm(roomId, text, csrfValue), webApi = true)
            if (last.ok) return last

            // ② WEB 失败且像是"签名/风控"问题 → 退回**不签名**再试一次
            //    （实测不签名这条路是通的：-101 而不是 -352；签名一旦因为 mixin_key 过期算错反而更糟）
            if (last.code in SIGN_SUSPECT_CODES) {
                LiveDanmakuTrace.note("发送：WBI 签名版失败 code=${last.code} → 退不签名重试")
                last = postForm(
                    "$SEND_URL?web_location=$WEB_LOCATION",
                    webForm(roomId, text, csrfValue),
                    webApi = true,
                )
                if (last.ok) return last
            }
        }

        // ③ APP 通道兜底：有 access_key 且 WEB 明确失败（未登录 / csrf 失败 / 风控）
        if (hasAppLogin && (last == null || last.code in APP_FALLBACK_CODES)) {
            LiveDanmakuTrace.note("发送：改走 APP 通道（上一次 code=${last?.code}）")
            val appResult = postForm(SEND_URL, appForm(roomId, text, csrf), webApi = false)
            if (appResult.ok) return appResult
            last = appResult
        }

        return last ?: SendResult.fail(-1, "发送失败：没有任何可用通道")
    }

    // ------------------------------------------------------------------
    // 两条通道的请求体
    // ------------------------------------------------------------------

    /**
     * WEB 表单：**字段清单逐字对齐 PiliPlus `live.dart:47-66`**。
     *
     * 为什么连 `reply_*` / `replay_dmid` / `statistics` 这些"看起来没用"的字段都带上：
     * 实测这套字段被服务端完整接受（回 -101 而不是 -400 请求错误）；
     * 少传字段换来的"精简"没有任何收益，反而可能被当成异常客户端。
     */
    private fun webForm(roomId: Long, text: String, csrf: String): Map<String, String?> = linkedMapOf(
        "bubble" to "0",
        "msg" to text,
        "color" to "16777215", // 0xFFFFFF：与 PiliPlus / BiliPai 的发送默认色一致
        "mode" to "1", // 1 = 滚动弹幕
        "fontsize" to "25",
        "rnd" to rndSeconds(),
        "roomid" to roomId.toString(),
        "csrf" to csrf,
        "csrf_token" to csrf,
        "room_type" to "0",
        "jumpfrom" to "0",
        "reply_mid" to "0",
        "reply_attr" to "0",
        "replay_dmid" to "",
        "statistics" to """{"appId":100,"platform":5}""",
        "reply_type" to "0",
        "reply_uname" to "",
    )

    /**
     * APP 表单：业务字段与 WEB 相同，身份靠 [ApiHelper.createParams] 注入
     * （appkey/appsec `sign` + `access_key` + `mid` + platform/mobi_app/statistics…）。
     *
     * ★`createParams` 会自己塞 `statistics`，所以这里**不重复传**（重复会被它覆盖，传了也没意义）。
     * ★不传 `appkey` 之外的 web 专属字段（`bubble`/`jumpfrom` 这些 APP 侧没有），只保留两端共有的核心字段。
     */
    private fun appForm(roomId: Long, text: String, csrf: String?): Map<String, String?> {
        val base = mutableMapOf<String, String?>(
            "msg" to text,
            "color" to "16777215",
            "mode" to "1",
            "fontsize" to "25",
            "rnd" to rndSeconds(),
            "roomid" to roomId.toString(),
        )
        if (!csrf.isNullOrBlank()) {
            base["csrf"] = csrf
            base["csrf_token"] = csrf
        }
        return ApiHelper.createParams(base)
    }

    // ------------------------------------------------------------------
    // 发请求
    // ------------------------------------------------------------------

    private suspend fun postForm(
        url: String,
        form: Map<String, String?>,
        webApi: Boolean,
    ): SendResult = try {
        val res = MiaoHttp.request {
            // webApi=true → 不塞 app-key/Authorization（纯 Web 通道，只靠 Cookie + csrf）
            // webApi=false → 走 APP 头部（APP 通道）
            this.isWebApi = webApi
            method = MiaoHttp.POST
            headers["Referer"] = LIVE_REFERER
            headers["Origin"] = LIVE_ORIGIN
            this.url = url
            formBody = form
        }.awaitCall().json<SendResponse>()
        SendResult(
            ok = res.code == 0,
            code = res.code,
            message = res.message.ifBlank { "code=${res.code}" },
        )
    } catch (t: Throwable) {
        // 网络异常也要变成可读结果（需求：不要把异常抛给 UI）
        if (t is kotlinx.coroutines.CancellationException) throw t
        LiveDanmakuTrace.note("发送请求异常：${t::class.java.simpleName}: ${t.message}")
        SendResult.fail(CODE_NETWORK, "网络异常：${t.message ?: t::class.java.simpleName}")
    }

    companion object {
        /** 端点（PiliPlus `api.dart:317`）。★域名是 `api.live.bilibili.com`，不是 `api.bilibili.com` */
        const val SEND_URL = "https://api.live.bilibili.com/msg/send"

        /** Web 端固定 `web_location`（PiliPlus 同值，也是它 WBI 签名的唯一 query 参数） */
        const val WEB_LOCATION = "444.8"

        /** 本地没登录态时用的 code（服务端的"账号未登录"也是 -101，保持一致便于上层判断） */
        const val CODE_NOT_LOGIN = -101

        /** 本地网络异常（不是服务端返回的 code） */
        const val CODE_NETWORK = -1000

        /** 像是"签名/风控"问题的返回码：退回不签名版再试一次 */
        private val SIGN_SUSPECT_CODES = setOf(-352, -403, -412)

        /** 这些返回码说明"WEB 通道这条路走不通了"，可以降级到 APP 通道 */
        private val APP_FALLBACK_CODES = setOf(-101, -111, -400, -403, -352)

        /** `rnd` = 秒级时间戳（PiliPlus：`millisecondsSinceEpoch ~/ 1000`） */
        private fun rndSeconds(): String = (System.currentTimeMillis() / 1000L).toString()

        private const val LIVE_ORIGIN = "https://live.bilibili.com"
        private const val LIVE_REFERER = "https://live.bilibili.com/"
    }
}

/**
 * `/msg/send` 的返回壳。
 *
 * ★只取 `code`/`message`：`data` 里是 `dm_v2`（protobuf base64）等**发送方用不到**的东西，
 *   少解析一个字段就少一个反序列化失败点（`MiaoJson` 已开 `ignoreUnknownKeys`，多出来的字段自动忽略）。
 */
@Serializable
internal data class SendResponse(
    val code: Int = -1,
    val message: String = "",
    val ttl: Int = 0,
)
