package com.a10miaomiao.bilimiao.comm.apis

import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.utils.BvUtils
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * B站官方「社区违规申诉」——接口全部从官方 H5 `bilibili.com/h5/comment/appeal`
 * 的前端 JS（webpack 产物）里抓出来，不是猜的：
 *
 * ```
 * ① 评论申诉（H5 第一个 radio「评论申诉」）
 *    POST /x/v2/reply/appeal/submit        x-www-form-urlencoded
 *    oid  = BV号（视频） 或
 *    url  = 相关位置链接（专栏/动态等，H5 与 oid 二选一）
 *    type = 1
 *    reason = 10~1000 字
 *    csrf = cookie.bili_jct（H5 的请求中间件自动带，形式是 query/表单参数）
 *
 * ② 图文动态申诉（H5 第二个 radio「图文动态申诉」）
 *    POST /x/dynamic/feed/dyn/appeal       JSON
 *    { uid: 当前用户 mid, link: 动态id或链接, reason, csrf }
 * ```
 *
 * H5 原话：「注：24小时内仅可提交3条申诉」——所以额度见 [DAILY_LIMIT]，
 * 本地还有 [com.a10miaomiao.bilimiao.comm.antifraud.AntifraudAppealQuota] 兜底记账。
 *
 * 为什么不是 WebView：
 *   ① H5 页面深色适配后表单白底白字（之前踩过）；
 *   ② 用户要"弹窗里一键提交"，页面上再点三次反而烦；
 *   ③ 接口参数已经抓清楚，原生提交可控、可给明确失败提示。
 *
 * 注意：服务端只接受"确实处于可申诉状态"的内容；正常评论会回
 * `12082 该bv号或链接下无可申诉评论`（实测）。
 */
object CommentAppeal {

    /** 官方 H5 明文：24 小时内仅可提交 3 条申诉（服务端超限回 56601） */
    const val DAILY_LIMIT = 3

    /** 理由长度约束：H5 的提交按钮在 `reason.length >= 10` 之前是灰的，输入框 1000 字截断 */
    const val REASON_MIN = 10
    const val REASON_MAX = 1000

    /**
     * 默认申诉理由（正式、长版）。
     *
     * 用户没填/没改时用这一份；用户填写后立即落盘，下次默认带出上次的内容。
     * H5 表单硬性要求 10~1000 字，下面这段本身约 130 字。
     */
    const val DEFAULT_REASON = "本人保证该评论不存在违规内容：评论主旨为正常的观点表达与友好交流，" +
        "不包含违法违规、人身攻击、引战、广告推广、刷屏、色情低俗、政治敏感等信息，" +
        "也未侵犯他人合法权益。该评论可能被系统误判为仅自己可见，现申请人工复核并恢复展示。" +
        "如需核实，可提供评论原文及完整上下文，感谢。"

    /** 申诉类型：与官方 H5 的两个 radio 一一对应 */
    enum class Kind {
        /** 评论申诉：x/v2/reply/appeal/submit */
        REPLY,

        /** 图文动态申诉：x/dynamic/feed/dyn/appeal */
        DYNAMIC,
    }

    /**
     * 一次申诉的目标（自动识别后的结果）。
     *
     * @param oid  评论申诉：BV 号或 aid（走 oid 参数）
     * @param url  评论申诉：位置链接（走 url 参数）
     * @param link 进申诉理由正文、以及动态申诉用的原始目标
     * @param label 给用户看的识别结论（弹窗里那一行小字）
     */
    data class Target(
        val kind: Kind,
        val oid: String? = null,
        val url: String? = null,
        val link: String = "",
        val label: String = "",
    )

    @Serializable
    data class Response(
        val code: Int = 0,
        val message: String = "",
        val data: Data? = null,
    ) {
        @Serializable
        data class Data(
            val success_toast: String? = null,
            val toast: String? = null,
        )
    }

    @Serializable
    private data class DynamicResponse(
        val code: Int = 0,
        val message: String = "",
        val data: DynamicData? = null,
    ) {
        @Serializable
        data class DynamicData(
            val toast: String? = null,
        )
    }

    @Serializable
    private data class DynamicBody(
        val uid: Long,
        val link: String,
        val reason: String,
        val csrf: String,
    )

    data class Result(
        val success: Boolean,
        val message: String,
        val code: Int = 0,
    )

    // ─────────────────────────── 自动识别 ───────────────────────────

    /**
     * 评论区（评论反诈检测出来的那条评论所在的区）对应的默认申诉目标。
     *
     * 视频 → BV 号（H5 同款，oid 参数吃 BV 字符串）；专栏 → 阅读页链接；
     * 动态/图文动态 → opus / t.bilibili.com 链接（H5 的「位置链接」）。
     */
    fun autoTarget(oid: Long, type: Int): String {
        if (oid <= 0L) return ""
        return when (type) {
            1 -> runCatching { BvUtils.toBvid(oid.toString()) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: oid.toString()
            12 -> "https://www.bilibili.com/read/cv$oid"
            17 -> "https://www.bilibili.com/opus/$oid"
            11 -> "https://t.bilibili.com/$oid"
            else -> oid.toString()
        }
    }

    /** 评论区的人类可读名字：弹窗标题、设置页记录、结果弹窗都用它 */
    fun describeArea(oid: Long, type: Int): String = when (type) {
        1 -> {
            val bv = runCatching { BvUtils.toBvid(oid.toString()) }.getOrNull()
            if (bv.isNullOrBlank()) "视频 av$oid" else "视频 $bv"
        }
        12 -> "专栏 cv$oid"
        11 -> "动态 $oid"
        17 -> "图文动态 $oid"
        else -> "评论区 oid=$oid（type=$type）"
    }

    /**
     * **自动识别**用户填的「BV号 / 位置链接 / 动态id」属于哪种申诉 —— 这是 H5 让用户手点的那两个 radio，
     * 我们替他判断：
     *
     * · `BV1xxxxxxxxx`、纯数字、`av123`      → 评论申诉，走 oid
     * · 动态链接（`/opus/`、`t.bilibili.com/`、`/dynamic/`） → 图文动态申诉
     * · 其它 http(s) 链接（专栏 read/cv、番剧、位置链接）    → 评论申诉，走 url
     *
     * @param autoFilled true = 这个目标是反诈检测自动带出来的（属于"评论"，哪怕它挂在一个动态下面，
     *                   要申诉的也是那条评论而不是动态本身）→ 一律按评论申诉
     */
    fun detectTarget(
        input: String,
        oid: Long,
        type: Int,
        autoFilled: Boolean = false,
    ): Target {
        val raw = input.trim()
        val effective = raw.ifBlank { autoTarget(oid, type) }
        if (effective.isBlank()) {
            return Target(Kind.REPLY, link = "", label = "没填目标，无法识别")
        }
        if (!autoFilled) {
            val dyn = asDynamicLink(effective)
            if (dyn != null) {
                return Target(
                    kind = Kind.DYNAMIC,
                    link = dyn,
                    label = "图文动态申诉（识别到动态链接）",
                )
            }
        }
        return replyTarget(effective, autoFilled)
    }

    /**
     * 按「评论申诉」规则分类一个目标：BV号/纯数字/av号 → oid，其它 → url。
     *
     * 弹窗里用户手点「评论申诉」时也走这里（不自动改判成图文动态）。
     */
    fun replyTarget(value: String, autoFilled: Boolean = false): Target {
        val v = value.trim()
        val asOid = when {
            v.startsWith("BV1", ignoreCase = true) -> v
            // av 号 / 纯数字：H5 也是 parseInt 成功就当 oid 传
            v.startsWith("av", ignoreCase = true) -> v.drop(2).takeWhile { it.isDigit() }
            v.toLongOrNull() != null -> v
            else -> null
        }
        return if (!asOid.isNullOrBlank()) {
            Target(
                kind = Kind.REPLY,
                oid = asOid,
                url = null,
                link = v,
                label = if (autoFilled) "评论申诉（自动填的 BV/评论区号）" else "评论申诉（识别为 BV号/稿件号）",
            )
        } else {
            Target(
                kind = Kind.REPLY,
                oid = null,
                url = v,
                link = v,
                label = "评论申诉（识别为位置链接）",
            )
        }
    }

    /** 动态/图文动态链接判定；不是动态就返回 null */
    fun asDynamicLink(value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return null
        val lower = v.lowercase()
        val hit = lower.contains("/opus/") ||
            lower.contains("t.bilibili.com/") ||
            lower.contains("/dynamic/") ||
            lower.contains("bilibili.com/dyn")
        if (!hit) return null
        return if (lower.startsWith("http")) v else "https://$v"
    }

    // ─────────────────────────── 理由拼装 ───────────────────────────

    /**
     * 申诉理由 = 用户理由（默认 [DEFAULT_REASON]）+ 评论原文 + 相关位置。
     *
     * **文字评论和图文评论都认**（用户要求"要识别文字和图文"）：纯图没有正文时写明
     * "图片评论 N 张，无文字正文"；有正文时在正文后标图片数 —— 审核端拿到的是"文字 + 图文"两样信息。
     */
    fun composeReason(
        base: String?,
        comment: String,
        link: String,
        hasPictures: Boolean = false,
        pictureCount: Int = 0,
    ): String {
        val head = base?.trim().takeUnless { it.isNullOrBlank() } ?: DEFAULT_REASON
        val text = comment.trim()
        val pics = pictureCount.coerceAtLeast(if (hasPictures) 1 else 0)
        val appendix = StringBuilder()
        if (text.isNotEmpty()) {
            appendix.append("\n评论内容：").append(text.take(300))
            if (hasPictures) appendix.append("（另含 ").append(pics).append(" 张图片）")
        } else if (hasPictures) {
            appendix.append("\n评论内容：（图片评论，共 ").append(pics).append(" 张图，无文字正文）")
        }
        if (link.isNotBlank()) appendix.append("\n相关位置：").append(link)
        // ★ 先给附录留位置再截正文（review 抓到）：以前是整串 take(1000)，
        //   用户把理由写到 900+ 字时，"评论内容 + 相关位置"会被整段切掉 —— 那恰恰是审核端最需要的信息。
        val headRoom = (REASON_MAX - appendix.length).coerceAtLeast(REASON_MIN)
        var out = head.take(headRoom) + appendix
        if (out.length > REASON_MAX) out = out.take(REASON_MAX)
        if (out.length < REASON_MIN) out = out.padEnd(REASON_MIN, '。')
        return out
    }

    // ─────────────────────────── 提交 ───────────────────────────

    /**
     * 提交一条申诉（自动按 [Target.kind] 走评论申诉 / 图文动态申诉）。
     *
     * @param uid 当前账号 mid —— 图文动态申诉的必填参数（H5 取的是 nav 里的 mid）
     */
    suspend fun submit(
        target: Target,
        reason: String,
        uid: Long = 0L,
    ): Result {
        val csrf = MiaoHttp.csrfToken()
        if (csrf.isNullOrBlank()) {
            return Result(false, "没有取到 bili_jct（网页登录态），无法原生提交申诉", -1)
        }
        return when (target.kind) {
            Kind.REPLY -> submitReply(target, reason, csrf)
            Kind.DYNAMIC -> {
                if (uid <= 0L) {
                    Result(false, "没拿到账号 mid，图文动态申诉提交不了", -2)
                } else {
                    submitDynamic(target, reason, uid, csrf)
                }
            }
        }
    }

    private suspend fun submitReply(target: Target, reason: String, csrf: String): Result {
        val params = linkedMapOf<String, String?>(
            "type" to "1",
            "reason" to reason,
            "csrf" to csrf,
        )
        // 名字带 Param 后缀：局部变量叫 url 的话会**遮蔽** MiaoHttp 的 url 属性，
        // 下面 request{} 里 `url = "..."` 就变成"给 val 赋值"→ 编译报 'val' cannot be reassigned（PC 编译实测）
        val oidParam = target.oid?.takeIf { it.isNotBlank() }
        val urlParam = target.url?.takeIf { it.isNotBlank() }
        if (oidParam != null) {
            params["oid"] = oidParam
        } else if (urlParam != null) {
            params["url"] = urlParam
        } else {
            return Result(false, "缺少 BV号/位置链接，无法提交评论申诉")
        }
        return try {
            val res = MiaoHttp.request {
                isWebApi = true
                method = MiaoHttp.POST
                // 不能走 BiliApiService.biliApi：会注入 appkey/sign/access_key，
                // 这是 Web 接口（H5 同款），只认 Cookie + csrf（与带图评论同一条经验）。
                this.url = "https://api.bilibili.com/x/v2/reply/appeal/submit"
                formBody = params
            }
                .awaitCall()
                .json<Response>(isLog = false)
            if (res.code == 0) {
                val toast = res.data?.success_toast?.takeIf { it.isNotBlank() }
                    ?: res.data?.toast?.takeIf { it.isNotBlank() }
                    ?: "申诉已提交，处理结果会发到「消息 → 系统通知」"
                Result(true, toast, res.code)
            } else {
                Result(false, prettyError(res.code, res.message), res.code)
            }
        } catch (e: Exception) {
            Result(false, "申诉请求失败：${e.javaClass.simpleName} ${e.message.orEmpty()}".trim())
        }
    }

    private suspend fun submitDynamic(target: Target, reason: String, uid: Long, csrf: String): Result {
        val link = target.link.trim()
        if (link.isEmpty()) return Result(false, "缺少动态 id 或链接，无法提交图文动态申诉")
        return try {
            val body = MiaoJson.toJson(DynamicBody(uid = uid, link = link, reason = reason, csrf = csrf))
            val res = MiaoHttp.request {
                isWebApi = true
                method = MiaoHttp.POST
                this.url = "https://api.bilibili.com/x/dynamic/feed/dyn/appeal"
                this.body = body.toRequestBody("application/json".toMediaType())
            }
                .awaitCall()
                .json<DynamicResponse>(isLog = false)
            if (res.code == 0) {
                val toast = res.data?.toast?.takeIf { it.isNotBlank() }
                    ?: "图文动态申诉已提交，处理结果会发到「消息 → 系统通知」"
                Result(true, toast, res.code)
            } else {
                // 实测（2026-09-26，拿不存在的动态 id 试的）：失败时它是 `data.toast` 里放人话
                Result(false, prettyError(res.code, res.message, res.data?.toast), res.code)
            }
        } catch (e: Exception) {
            Result(false, "动态申诉请求失败：${e.javaClass.simpleName} ${e.message.orEmpty()}".trim())
        }
    }

    /**
     * 服务端错误码 → 人话。56601/56602 是 H5 自己专门 switch 过的两个；
     * 12080/12082/4000230 是 2026-09-26 实测（拿不存在的稿件/动态试的，不消耗申诉额度）补上的。
     */
    fun prettyError(code: Int, message: String, serverToast: String? = null): String = when (code) {
        12082 -> "该链接下没有可申诉的评论：可能评论仍正常显示，或已经不在可申诉状态。\n（实测正常评论会返回 12082）"
        12080 -> "该 BV 号/链接有误，或这条评论当前不在可申诉状态（服务端暂时识别不了）"
        4000230 -> "动态不存在，请重新输入动态 id 或链接"
        56601 -> "申诉次数已达当日上限（官方：24 小时内最多 3 次），明天再试"
        56602 -> "这条内容已经提交过申诉，不用重复提交"
        -352 -> "风控校验失败（-352）：稍后再试，或先在 App 里打开一次网页版登录态"
        else -> serverToast?.takeIf { it.isNotBlank() }
            ?: message.ifBlank { "申诉失败（code=$code）" }
    }
}
