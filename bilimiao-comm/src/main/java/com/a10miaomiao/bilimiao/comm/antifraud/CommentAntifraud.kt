package com.a10miaomiao.bilimiao.comm.antifraud

import android.webkit.CookieManager
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.apis.CommentApi
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.video.AntifraudCommentInfo
import com.a10miaomiao.bilimiao.comm.entity.video.AntifraudCommentPageInfo
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import kotlinx.coroutines.delay

/**
 * 评论反诈（ShadowBan / 仅自己可见 检测）
 *
 * 思路与判定规则完全照搬开源项目 **biliSendCommAntifraud**（哔哩发评反诈，
 * https://github.com/freedom-introvert/biliSendCommAntifraud ），它把阿瓦隆系统的
 * 行为摸清楚了，核心结论就一句：
 *
 * > ShadowBan 的评论，**带 Cookie 能在评论列表里找到，不带 Cookie 找不到**。
 *
 * 具体用到的接口特性（上游 README《检查逻辑》+ 源码 CommentCheckTask 实测结论）：
 *  - 游客取"某条根评论的回复页"（x/v2/reply/reply?root=rpid）：
 *      评论被 ShadowBan → 12022 / 12006（"已经没有该评论"）
 *      评论正常或只是被限流在列表里 → 0
 *  - 登录账号取同一条评论的回复页：真的被秒删 → 12022；ShadowBan → 0（自己看得见）
 *  - 楼中楼（root != 0）没有这个特性，只能用 seek_rpid 定位：游客定位不到、登录定位得到 = ShadowBan
 *
 * 所以"找不到评论"之后的分支就是：
 *  根评论：登录查得到 + 游客查不到 → ShadowBan；登录都查不到 → 秒删；
 *          登录游客都查得到 → 疑似审核中（列表里翻不到但接口还有）
 *  楼中楼：登录定位得到 → ShadowBan；登录定位不到 → 已删除
 *
 * ⚠️ 只是**参考**：阿瓦隆会针对账号、评论区、内容分别控评，检测结果不等于"你被封号了"。
 */
object CommentAntifraud {

    /** 发送后等待多久再查：B站要时间处理评论，查太早会把正常评论误判成被吞（上游默认 5 秒） */
    const val WAIT_MS = 5_000L

    /** 带图评论额外多等：阿瓦隆识别图片内容更慢（上游默认再加 15 秒） */
    const val WAIT_PIC_MS = 15_000L

    /** 根评论最多翻几页时间序（每页 20 条）；上游是 30 页，这里够用就行 */
    private const val MAX_PAGES = 6

    /** 自动复查的间隔：30 秒查一次（上游监控是 60 秒一次，这里勤一点，评论区状态变化挺快） */
    const val RECHECK_INTERVAL_MS = 30_000L

    /** 复查总时长上限，防止手滑把时长设成天文数字 */
    const val RECHECK_MAX_MINUTES = 30

    /** 时间序翻页的兜底上限：翻到的评论比"我这条"还早，就没必要再翻了 */
    private const val CTIME_EPS = 2L

    // ---- 评论状态码（上游 GeneralResponse 里的常量）----
    private const val CODE_OK = 0
    private const val CODE_COMMENT_DELETED = 12022   // 没有该评论 / 已被删除
    private const val CODE_COMMENT_NOT_EXIST = 12006 // 没有该评论

    private var cachedBuvid3: String? = null

    /**
     * 检测一条刚发出去的评论。
     *
     * @param oid        评论区 id（视频=aid、动态=动态id、专栏=cv号）
     * @param type       评论区类型（视频 1、动态 17、专栏 12……）
     * @param rpid       刚发出的评论 id
     * @param root       根评论 id；自己就是根评论时传 0
     * @param sentTimeSec 发送时间（秒）；用来在时间序里判断"翻过头了"
     * @param hasPictures 是否带图（带图要多等一会）
     */
    suspend fun check(
        oid: Long,
        type: Int,
        rpid: Long,
        root: Long,
        sentTimeSec: Long,
        hasPictures: Boolean,
    ): AntifraudResult {
        val waitMs = if (hasPictures) WAIT_MS + WAIT_PIC_MS else WAIT_MS
        AntifraudDiag.start("评论反诈检测 oid=$oid type=$type rpid=$rpid root=$root")
        AntifraudDiag.step("等待 ${waitMs}ms（带图=$hasPictures）后开始")
        delay(waitMs)
        return try {
            val r = doCheck(oid, type, rpid, root, sentTimeSec)
            AntifraudDiag.finish("${r.state}｜${r.detail}｜code=${r.code} ${r.rawMessage}")
            r
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AntifraudDiag.step("抛异常：${e.javaClass.simpleName} ${e.message ?: ""}")
            AntifraudDiag.finish("FAILED｜${e.javaClass.simpleName}")
            AntifraudResult(
                state = AntifraudState.FAILED,
                detail = "检测失败：${e.javaClass.simpleName} ${e.message ?: ""}".trim(),
            )
        }
    }

    /**
     * 首查 + **自动复查**（用户要求）：一次判定不算数，得盯着看它会不会"过一会儿才被限流"。
     *
     * 流程：首查（5 秒 / 带图 20 秒）→ 有问题立刻返回 → 没问题就每 30 秒复查一次，
     * 直到"状态变了"或者跑满 [recheckTotalMs]。真实案例：发出后 8 秒游客还能看到，
     * 几分钟后才变成仅自己可见 —— 只查一次会把这种情况误判成"正常"。
     *
     * @param recheckEnabled 关掉就只查一次（等同老的 [check]）
     * @param recheckTotalMs 复查监控总时长；0 或负数也按只查一次处理
     * @param onAttempt 每次查完回调（第几次、共预计几次、这次结论），用来更新界面提示
     */
    suspend fun checkWithRecheck(
        oid: Long,
        type: Int,
        rpid: Long,
        root: Long,
        sentTimeSec: Long,
        hasPictures: Boolean,
        recheckEnabled: Boolean,
        recheckTotalMs: Long,
        onAttempt: ((attempt: Int, totalPlanned: Int, result: AntifraudResult) -> Unit)? = null,
    ): AntifraudResult {
        val first = check(oid, type, rpid, root, sentTimeSec, hasPictures)
        onAttempt?.invoke(1, plannedAttempts(recheckEnabled, recheckTotalMs), first)
        if (!recheckEnabled || recheckTotalMs <= 0L) return first
        if (first.isBad) return first          // 一开场就不对，不用再复查

        var attempt = 1
        var elapsed = 0L
        var last = first
        // 首查已经等过 5/20 秒了，复查从这会儿开始计时
        AntifraudDiag.info("进入自动复查：每 ${RECHECK_INTERVAL_MS / 1000} 秒一次，共 ${recheckTotalMs / 60000} 分钟")
        while (elapsed < recheckTotalMs) {
            delay(RECHECK_INTERVAL_MS)
            elapsed += RECHECK_INTERVAL_MS
            attempt++
            AntifraudDiag.start("反诈复查 第${attempt}次（已监控 ${elapsed / 1000} 秒）oid=$oid rpid=$rpid")
            val r = try {
                doCheck(oid, type, rpid, root, sentTimeSec)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AntifraudDiag.step("复查抛异常：${e.javaClass.simpleName} ${e.message ?: ""}")
                AntifraudDiag.finish("FAILED｜复查异常")
                onAttempt?.invoke(attempt, plannedAttempts(true, recheckTotalMs), last)
                continue
            }
            AntifraudDiag.finish("${r.state}｜${r.detail}")
            onAttempt?.invoke(attempt, plannedAttempts(true, recheckTotalMs), r)
            if (r.isBad) {
                // ★ 就是这种情况：首次正常，后来才被限流
                val afterText = if (elapsed < 60_000) "${elapsed / 1000} 秒后"
                else "${elapsed / 60_000} 分钟后"
                return r.copy(
                    detail = r.detail + "\n（首次检测是正常的，$afterText 复查才发现变化 —— B站这种『先放出来再限流』很常见）"
                )
            }
            last = r
        }
        // 全程正常：把"查了几次、盯了多久"写进结论，别让人以为只查了一下
        return last.copy(
            detail = last.detail + "\n（共复查 $attempt 次、持续 ${recheckTotalMs / 60000} 分钟都是正常）"
        )
    }

    /** 预计查几次（给界面显示用）：首次 + 复查次数 */
    private fun plannedAttempts(recheckEnabled: Boolean, recheckTotalMs: Long): Int {
        if (!recheckEnabled || recheckTotalMs <= 0L) return 1
        return 1 + (recheckTotalMs / RECHECK_INTERVAL_MS).toInt()
    }

    private suspend fun doCheck(
        oid: Long,
        type: Int,
        rpid: Long,
        root: Long,
        sentTimeSec: Long,
    ): AntifraudResult {
        val buvid = guestBuvid3()
        AntifraudDiag.step(
            "游客 Cookie：buvid3=${if (buvid.isNullOrBlank()) "(无)" else "有"}" +
                "，前缀 ${buvid?.take(8) ?: "-"}（匿名、非本机设备指纹）"
        )
        AntifraudDiag.info("App 当前 Cookie 名字：${AntifraudDiag.cookieNames(appCookieNames())}")

        // ===== 1. 先按游客视角找这条评论 =====
        val found = if (root == 0L) {
            findRootAsGuest(oid, type, rpid, sentTimeSec, buvid)
        } else {
            findReplyAsGuest(oid, type, rpid, root, buvid)
        }
        if (found != null) {
            return if (found.invisible) {
                AntifraudResult(AntifraudState.INVISIBLE, "评论被标记为不可见（invisible），别人看不到")
            } else {
                AntifraudResult(AntifraudState.NORMAL, "评论正常显示，游客也能看到")
            }
        }

        // ===== 2. 游客看不到：区分 秒删 / ShadowBan / 疑似审核 =====
        if (root == 0L) {
            val withAcc = replyPage(oid, type, rpid, asGuest = false, buvid = null)
            AntifraudDiag.step("②登录态查回复页 code=${withAcc.code} ${withAcc.message}")
            if (withAcc.code == CODE_COMMENT_DELETED) {
                return AntifraudResult(
                    AntifraudState.DELETED,
                    "评论被系统秒删（登录状态也查不到了）",
                    withAcc.code, withAcc.message,
                )
            }
            if (withAcc.code != CODE_OK) {
                return AntifraudResult(
                    AntifraudState.FAILED,
                    "接口返回异常：${withAcc.code} ${withAcc.message}",
                    withAcc.code, withAcc.message,
                )
            }
            // 登录看得见 → 再看游客看不看得见这条评论的回复页
            val guest = replyPage(oid, type, rpid, asGuest = true, buvid = buvid)
            AntifraudDiag.step("③游客查同一条回复页 code=${guest.code} ${guest.message}" +
                " invisible=${guest.data?.root?.invisible}")
            if (guest.code == CODE_OK) {
                // 游客也能取到回复页：多半是"疑似审核中"（列表里翻不到但接口还认）
                return if (guest.data?.root?.invisible == true) {
                    AntifraudResult(AntifraudState.INVISIBLE, "评论被标记为不可见（invisible），别人看不到")
                } else {
                    AntifraudResult(
                        AntifraudState.UNDER_REVIEW,
                        "疑似审核中：游客翻不到这条评论，但评论接口仍然存在。" +
                            "过几个小时可能就放出来了，先别急着删",
                    )
                }
            }
            if (guest.code == CODE_COMMENT_DELETED || guest.code == CODE_COMMENT_NOT_EXIST) {
                return AntifraudResult(
                    AntifraudState.SHADOW_BAN,
                    "仅自己可见（ShadowBan）：你登录还能看到，别人（游客）看不到",
                    guest.code, guest.message,
                )
            }
            return AntifraudResult(
                AntifraudState.FAILED,
                "接口返回异常：${guest.code} ${guest.message}",
                guest.code, guest.message,
            )
        } else {
            // 楼中楼：登录能定位到 = ShadowBan，登录也定位不到 = 已删除
            val foundByAcc = findReplyAsLogin(oid, type, rpid, root)
            AntifraudDiag.step("②登录态 seek_rpid 定位楼中楼：${if (foundByAcc) "找到" else "没找到"}")
            return if (foundByAcc) {
                AntifraudResult(AntifraudState.SHADOW_BAN, "仅自己可见（ShadowBan）：你登录还能看到，别人（游客）看不到")
            } else {
                AntifraudResult(AntifraudState.DELETED, "评论已被删除（登录状态也定位不到）")
            }
        }
    }

    // ---------------- 找评论 ----------------

    /** 根评论：游客翻时间序列表找（找到或翻到比我更早的评论为止） */
    private suspend fun findRootAsGuest(
        oid: Long,
        type: Int,
        rpid: Long,
        sentTimeSec: Long,
        buvid: String?,
    ): AntifraudCommentInfo? {
        var offset: String? = null
        for (page in 0 until MAX_PAGES) {
            val res = request(CommentApi().mainListByTime(oid.toString(), type, offset, null, true, buvid))
            AntifraudDiag.step(
                "①游客翻列表 第${page + 1}页 code=${res.code} ${res.message}" +
                    " 条数=${res.data?.replies?.size ?: 0}(置顶${res.data?.top_replies?.size ?: 0})"
            )
            if (res.data?.replies?.isNotEmpty() == true) {
                // 把最新几条的 rpid/ctime 记下来：万一日志显示"找到了"，也能核对是不是同一条
                res.data!!.replies!!.take(3).forEach {
                    AntifraudDiag.info("列表项 rpid=${it.rpid} ctime=${it.ctime} invisible=${it.invisible}")
                }
            }
            if (res.code != CODE_OK) {
                AntifraudDiag.info("游客列表返回非 0（多为 -352 风控）→ 这一步作废，继续走后两步")
                return null
            }
            val data = res.data
            val list = ArrayList<AntifraudCommentInfo>()
            data?.top_replies?.let { list.addAll(it) }
            data?.replies?.let { list.addAll(it) }
            list.firstOrNull { it.rpid == rpid }?.let {
                AntifraudDiag.info("★ 游客列表里找到了 rpid=$rpid（ctime=${it.ctime}）")
                return it
            }
            // 已经翻到比发送时间更早的评论 → 我这条不在时间序里
            if (list.any { it.ctime in 1 until (sentTimeSec - CTIME_EPS) }) {
                AntifraudDiag.info("翻到比发送时间($sentTimeSec)更早的评论 → 停止翻页（游客列表里没有这条）")
                return null
            }
            offset = data?.cursor?.pagination_reply?.next_offset
            if (offset.isNullOrBlank()) {
                AntifraudDiag.info("游标到底（next_offset 为空）→ 停止翻页")
                return null
            }
        }
        AntifraudDiag.info("翻了 $MAX_PAGES 页仍没找到 → 停止")
        return null
    }

    /** 楼中楼：游客用 seek_rpid 定位（B站会把定位到的评论放进预览域 replies 里） */
    private suspend fun findReplyAsGuest(
        oid: Long,
        type: Int,
        rpid: Long,
        root: Long,
        buvid: String?,
    ): AntifraudCommentInfo? {
        val res = request(CommentApi().mainListByTime(oid.toString(), type, null, rpid, true, buvid))
        val hit = if (res.code != CODE_OK) null else pickReply(res.data, rpid, root)
        AntifraudDiag.step("①游客 seek_rpid 定位楼中楼 code=${res.code} ${res.message} → ${if (hit != null) "找到" else "没找到"}")
        return hit
    }

    private suspend fun findReplyAsLogin(
        oid: Long,
        type: Int,
        rpid: Long,
        root: Long,
    ): Boolean {
        val res = request(CommentApi().mainListByTime(oid.toString(), type, null, rpid, false))
        if (res.code != CODE_OK) return false
        return pickReply(res.data, rpid, root) != null
    }

    /**
     * 在定位结果里找目标楼中楼。
     * seek_rpid 定位到的评论会出现在：顶层 replies、置顶 top_replies，
     * 或者它所属根评论的回复预览 replies 里 —— 三处都找一遍。
     */
    private fun pickReply(
        data: AntifraudCommentPageInfo?,
        rpid: Long,
        root: Long,
    ): AntifraudCommentInfo? {
        val tops = ArrayList<AntifraudCommentInfo>()
        data?.top_replies?.let { tops.addAll(it) }
        data?.replies?.let { tops.addAll(it) }
        for (c in tops) {
            if (c.rpid == rpid) return c
            c.replies?.firstOrNull { it.rpid == rpid }?.let { return it }
            if (c.rpid == root) {
                c.replies?.firstOrNull { it.rpid == rpid }?.let { return it }
            }
        }
        return null
    }

    // ---------------- 请求 ----------------

    private suspend fun replyPage(
        oid: Long,
        type: Int,
        root: Long,
        asGuest: Boolean,
        buvid: String?,
    ): ResponseData<AntifraudCommentPageInfo> {
        return request(CommentApi().replyPage(oid.toString(), type, root, asGuest = asGuest, guestBuvid3 = buvid))
    }

    private suspend fun request(call: MiaoHttp): ResponseData<AntifraudCommentPageInfo> {
        return call.awaitCall().json(isLog = false)
    }

    /** App 当前 Cookie（登录态）里的名字清单，仅用于诊断日志 */
    private fun appCookieNames(): String? = runCatching {
        CookieManager.getInstance().getCookie("https://api.bilibili.com")
    }.getOrNull()

    // ---------------- 游客 buvid3 ----------------

    /**
     * 拿一个 buvid3 给游客请求用。
     *
     * x/v2/reply/main 不带 buvid3 会直接 -352 风控（实测），而上游是直接从账号 Cookie 里抠
     * buvid3 出来给游客用的（buvid3 本身不代表登录态）。这里同样：先看 App 的 Cookie 里有没有，
     * 没有就去官方指纹接口现要一个。
     */
    private suspend fun guestBuvid3(): String? {
        cachedBuvid3?.let { return it }
        // ★★ 绝对不要用本机 Cookie 里那个 buvid3 ★★
        //    实测（2026-09-20）：B站把 buvid3 和设备/账号**绑定**了。同一条被 shadow ban 的评论，
        //    带上本机 buvid3（哪怕完全不带登录 Cookie）请求，服务端照样把它当"本人"返回 →
        //    "游客能看到" = 假阳性，检测直接失效。
        //    证据：本机 App 侧列表 2 条（含该评论），我从外部用全新匿名 buvid3 查只有 1 条（不含）。
        //    所以这里只用一个**与账号无关**的匿名 buvid3。
        // 1) 现要一个：x/frontend/finger/spi（公开接口，返回 b_3 就是 buvid3）
        runCatching {
            val res = MiaoHttp.request {
                url = BiliApiService.biliApi("x/frontend/finger/spi")
                isWebApi = true
                asGuest = true
            }.awaitCall().json<ResponseData<Map<String, String>>>(isLog = false)
            res.data?.get("b_3")?.takeIf { it.isNotBlank() }
        }.getOrNull()?.let {
            cachedBuvid3 = it
            return it
        }
        // 2) 兜底：本机随机生成一个（同样与账号无关；只是格式对得上、服务端认）
        return runCatching { ApiHelper.generateBuvid() }.getOrNull()
    }
}

/** 检测结果状态 */
enum class AntifraudState {
    /** 正常显示 */
    NORMAL,

    /** 被前端标记不可见（invisible） */
    INVISIBLE,

    /** 仅自己可见（ShadowBan，阿瓦隆控评） */
    SHADOW_BAN,

    /** 被系统秒删 */
    DELETED,

    /** 疑似审核中 */
    UNDER_REVIEW,

    /** 检测失败（网络/接口异常） */
    FAILED,
}

/** 检测结果 */
data class AntifraudResult(
    val state: AntifraudState,
    val detail: String,
    val code: Int = 0,
    val rawMessage: String = "",
) {
    /** 是不是"有问题"（要弹窗提醒 + 给删除/申诉入口） */
    val isBad: Boolean
        get() = state == AntifraudState.SHADOW_BAN ||
            state == AntifraudState.DELETED ||
            state == AntifraudState.INVISIBLE

    val title: String
        get() = when (state) {
            AntifraudState.NORMAL -> "评论正常显示"
            AntifraudState.INVISIBLE -> "评论被隐藏（invisible）"
            AntifraudState.SHADOW_BAN -> "评论仅自己可见（疑似被限流）"
            AntifraudState.DELETED -> "评论已被删除"
            AntifraudState.UNDER_REVIEW -> "评论疑似审核中"
            AntifraudState.FAILED -> "检测没能完成"
        }
}
