package cn.a10miaomiao.bilimiao.compose.pages.community.components

import android.content.Context
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.antifraud.AntifraudResult
import com.a10miaomiao.bilimiao.comm.antifraud.AntifraudState
import cn.a10miaomiao.bilimiao.compose.components.antifraud.AntifraudMonitor
import cn.a10miaomiao.bilimiao.compose.components.antifraud.AntifraudMonitorSession
import cn.a10miaomiao.bilimiao.compose.components.antifraud.AntifraudResultState
import com.a10miaomiao.bilimiao.comm.antifraud.AntifraudDiag
import com.a10miaomiao.bilimiao.comm.antifraud.AntifraudLastResult
import com.a10miaomiao.bilimiao.comm.antifraud.CommentAntifraud
import com.a10miaomiao.bilimiao.comm.apis.CommentApi
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.entity.MessageInfo
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.toast
import com.a10miaomiao.bilimiao.comm.utils.BvUtils
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.PopTip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 评论反诈的 UI 侧入口：发完评论后等几秒自动检测，出结果弹窗（删除 / 申诉 / 关闭）。
 *
 * 检测逻辑在 [CommentAntifraud]（bilimiao-comm），判定规则照搬 biliSendCommAntifraud：
 * https://github.com/freedom-introvert/biliSendCommAntifraud
 */
object CommentAntifraudLauncher {

    /** B站官方评论申诉页（H5）。**用外部浏览器打开** —— 见 [openAppealPage] 的说明 */
    const val APPEAL_URL = "https://www.bilibili.com/h5/comment/appeal"

    /**
     * 打开官方申诉页 —— **走外部浏览器**。
     *
     * 这里换过两轮，把结论写下来免得再折腾（用户 2026-09-25 拍板："你还是跳外部吧，一劳永逸"）：
     *   · 内置 WebView：得替 B站 页面适配主题 —— 注入官方深色令牌 `bili_dark` 之后，
     *     表单变成"白底白字"（用户原话："黑色主题看不见"），提交还因为填错字段报"请求错误"；
     *   · 顺带的"把评论ID/位置复制到剪贴板让用户粘"也一起去掉了：用户粘进了"BV号"那一格，直接提交失败；
     *   · 外部浏览器本来就是 B站 官方通道，登录一次长期有效，不用我们维护。
     */
    private fun openAppealPage() {
        runCatching {
            val ctx = BilimiaoCommApp.commApp.app
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(APPEAL_URL)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }.onFailure {
            toast("没能打开浏览器，申诉页地址：$APPEAL_URL")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 同时在跑的检测会话数。
     *
     * 以前是布尔互斥，但加了"自动复查"之后一次会话要盯好几分钟 —— 那期间你再发评论就全被挡掉了。
     * 改成允许并行几路（各自独立、各自弹窗），超过上限才跳过并提示。
     */
    private var activeChecks = 0
    private const val MAX_ACTIVE_CHECKS = 3

    /**
     * 发完评论后调一次（只在**发送成功**后调）。
     *
     * 登录门：没登录直接不检测（游客没有"仅自己可见"这回事，也无从申诉）。
     *
     * @param result      发送接口返回的原始结果（拿 rpid / root）
     * @param onOpenAppeal 申诉回调：由页面提供，用来跳到内置浏览器的官方申诉页
     */
    fun start(
        result: com.a10miaomiao.bilimiao.comm.entity.video.VideoCommentSendResultInfo,
        message: String,
        hasPictures: Boolean,
        onOpenAppeal: ((oid: Long, type: Int, rpid: Long) -> Unit)? = null,
    ) {
        val rpid = result.rpid
        if (rpid <= 0) {
            // 以前这里一声不响地 return —— 用户根本不知道检测没跑，只能看到"什么都没发生"
            AntifraudDiag.start("评论反诈：参数缺失")
            AntifraudDiag.step("发送接口没回 rpid（rpid=$rpid）→ 无法定位这条评论，跳过检测")
            AntifraudDiag.finish("SKIPPED｜没有 rpid")
            PopTip.show("评论反诈：没拿到这条评论的 ID，这次没检测")
            return
        }
        val oid = result.reply.oid
        val type = result.reply.type
        if (oid <= 0 || type <= 0) {
            AntifraudDiag.start("评论反诈：参数缺失")
            AntifraudDiag.step("评论区参数不全（oid=$oid type=$type）→ 跳过检测")
            AntifraudDiag.finish("SKIPPED｜没有 oid/type")
            PopTip.show("评论反诈：没拿到评论区 ID，这次没检测")
            return
        }
        // 登录门：没登录检测一定不准（自己看自己永远"正常"）
        if (BilimiaoCommApp.commApp.loginInfo == null) {
            AntifraudDiag.start("评论反诈：未登录")
            AntifraudDiag.step("当前是未登录/游客模式 → 跳过检测（游客没有『仅自己可见』这回事）")
            AntifraudDiag.finish("SKIPPED｜未登录")
            PopTip.show("评论反诈：当前没登录，这次没检测")
            return
        }
        if (activeChecks >= MAX_ACTIVE_CHECKS) {
            AntifraudDiag.start("评论反诈：同时在查的评论太多")
            AntifraudDiag.step("已经有 $activeChecks 条评论在查（上限 $MAX_ACTIVE_CHECKS）→ 这次跳过")
            AntifraudDiag.finish("SKIPPED｜并行会话已达上限")
            PopTip.show("评论反诈：同时在查的评论太多了，这次跳过")
            return
        }
        activeChecks++
        val app = BilimiaoCommApp.commApp.app
        val root = result.root
        val sentTimeSec = System.currentTimeMillis() / 1000
        var monitor: AntifraudMonitorSession? = null
        scope.launch {
            try {
                val (enabled, recheckEnabled, recheckMinutes) = readSettings(app)
                if (!enabled) {
                    // 开关是用户自己关的 → 不打扰，但日志里留一笔（排查"为什么没检测"时要看）
                    AntifraudDiag.start("评论反诈：开关未开启")
                    AntifraudDiag.finish("SKIPPED｜设置里没打开「发评论后自动检测是否被限流」")
                    return@launch
                }
                // 上限兜底：设置导入没有范围校验，被写成天文数字会真盯那么久（审查发现）
                val minutes = recheckMinutes.coerceIn(1, CommentAntifraud.RECHECK_MAX_MINUTES)
                val recheckMs = if (recheckEnabled) minutes * 60_000L else 0L
                // 登记到「监控中」列表，设置页里实时显示进度（用户要求：别让他干等）
                val bvLabel = if (type == 1) {
                    runCatching { BvUtils.toBvid(oid.toString()) }.getOrNull()?.takeIf { it.isNotBlank() }
                        ?: "av$oid"
                } else "oid=$oid"
                val planned = 1 + (recheckMs / CommentAntifraud.RECHECK_INTERVAL_MS).toInt()
                monitor = AntifraudMonitor.start(
                    label = bvLabel,
                    rpid = rpid,
                    firstWaitMs = if (hasPictures) CommentAntifraud.WAIT_MS + CommentAntifraud.WAIT_PIC_MS
                    else CommentAntifraud.WAIT_MS,
                    recheckEnabled = recheckEnabled,
                    recheckTotalMs = recheckMs,
                    planned = planned,
                )
                PopTip.show(
                    if (!recheckEnabled) {
                        if (hasPictures) "评论已发出，20 秒后检测一次（没开自动复查）"
                        else "评论已发出，5 秒后检测一次（没开自动复查）"
                    } else {
                        val first = if (hasPictures) "20 秒" else "5 秒"
                        val times = 1 + recheckMs / CommentAntifraud.RECHECK_INTERVAL_MS
                        "评论已发出：${first}后首查，之后每 30 秒复查一次，共盯 $minutes 分钟（最多 $times 次）"
                    }
                )
                val r = withContext(Dispatchers.IO) {
                    CommentAntifraud.checkWithRecheck(
                        oid = oid,
                        type = type,
                        rpid = rpid,
                        root = root,
                        sentTimeSec = sentTimeSec,
                        hasPictures = hasPictures,
                        recheckEnabled = recheckEnabled,
                        recheckTotalMs = recheckMs,
                        onAttempt = { attempt, total, result ->
                            // 回调来自 IO 上下文 → 统一切主线程再改 Compose 状态 / 弹提示
                            scope.launch {
                                monitor?.let {
                                    it.attempt = attempt
                                    it.planned = total
                                    it.lastState = result.state
                                    it.lastDetail = result.detail
                                }
                                // 只在"首查正常、准备开始盯"时提示一次，之后静静盯着，别刷屏
                                if (attempt == 1 && total > 1 && !result.isBad) {
                                    PopTip.show("首查正常，开始复查（每 30 秒一次 / 共 $minutes 分钟）")
                                }
                            }
                        },
                    )
                }
                showResult(r, message, oid, type, rpid, root, onOpenAppeal, sentTimeSec = sentTimeSec)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                AntifraudDiag.start("评论反诈：检测过程出错")
                AntifraudDiag.step("${e.javaClass.simpleName}: ${e.message}")
                AntifraudDiag.finish("FAILED｜${e.javaClass.simpleName}")
                val errText = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
                MessageDialog.build()
                    .setTitle("评论反诈：检测没跑完")
                    .setMessage("$errText\n\n这条评论的状态没能确认。")
                    .setOkButton("知道了")
                    .show()
            } finally {
                activeChecks = (activeChecks - 1).coerceAtLeast(0)
                // 最终结论已经弹过窗了 → 从"监控中"列表移除
                monitor?.let { m -> scope.launch { AntifraudMonitor.finish(m) } }
            }
        }
    }

    /** 一次读齐三个设置：总开关、复查开关、复查监控时长（分钟） */
    /**
     * **手动复检**一条已经发出去的评论（设置页「上次检测结果」里那个按钮 / 结果弹窗里的"重新检测"）。
     *
     * 为什么需要：检测只在"发评论"那一刻自动触发，而评论往往是**几分钟后**才被限流 ——
     * 用户想验证"它到底被判成什么"，不该被迫再发一条新评论。这里不等 5/20 秒、也不进复查循环，
     * 立刻查一次就给结论。
     */
    fun recheck(
        oid: Long,
        type: Int,
        rpid: Long,
        root: Long,
        message: String,
        /** 这条评论的发送时间（秒），0 = 不知道（不早停）。设置页复检时会带上记录里的值 */
        sentTimeSec: Long = 0L,
    ) {
        if (oid <= 0L || type <= 0 || rpid <= 0L) {
            PopTip.show("缺少参数，无法复检")
            return
        }
        if (activeChecks >= MAX_ACTIVE_CHECKS) {
            PopTip.show("同时在查的评论太多了，稍后再试")
            return
        }
        activeChecks++
        val app = BilimiaoCommApp.commApp.app
        scope.launch {
            try {
                AntifraudDiag.start("手动复检 oid=$oid type=$type rpid=$rpid root=$root")
                val r = withContext(Dispatchers.IO) {
                    CommentAntifraud.check(
                        oid = oid,
                        type = type,
                        rpid = rpid,
                        root = root,
                        // ★ 0 = 不知道发送时间（老记录），此时不能早停；传 now 会让"翻到更早的评论就停"
                        //   在第 1 页立刻命中 → 正常评论被误报"疑似审核中"。
                        //   新记录（vc130 起）会把真实发送时间存下来，复检时带过来更准。
                        sentTimeSec = sentTimeSec,
                        hasPictures = false,
                        skipWait = true,
                    )
                }
                showResult(r, message, oid, type, rpid, root, null, sentTimeSec = sentTimeSec)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                AntifraudDiag.finish("FAILED｜复检异常 ${e.javaClass.simpleName}")
                PopTip.show("复检没跑完：${e.javaClass.simpleName}")
            } finally {
                activeChecks = (activeChecks - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * 只知道 BV 号时的复检入口。
     *
     * 为什么需要：老版本（vc124 及以前）的检测记录里**没有存 oid/type**，只有"视频 BVxxxx"这段文字，
     * 于是点「重新检测」会报"缺少参数"（用户实测撞上）。这里先把 BV 换成 aid 再复检 ——
     * 老记录也能用，用户不必为了验证再发一条新评论。
     */
    fun recheckByBv(
        bv: String,
        rpid: Long,
        message: String,
        /** 同 recheck：0 = 不知道发送时间（不早停） */
        sentTimeSec: Long = 0L,
    ) {
        if (!BvUtils.isValidBvid(bv)) {
            toast("这条记录的 BV 号不合法，没法复检")
            return
        }
        if (rpid <= 0L) {
            // 老/坏记录里 rpid=0 时，去查 rpid 0 会拿到 12022 → 误报"评论已被删除"
            toast("这条记录的评论 ID 无效，没法复检")
            return
        }
        activeChecks++
        scope.launch {
            try {
                AntifraudDiag.start("手动复检（先用 BV 换 aid）bv=$bv rpid=$rpid")
                val aid = withContext(Dispatchers.IO) {
                    val res = MiaoHttp.request {
                        url = BiliApiService.biliApi("x/web-interface/view", "bvid" to bv)
                    }.awaitCall().json<ResponseData<com.a10miaomiao.bilimiao.comm.entity.video.VideoIdInfo>>()
                    res.data?.aid ?: 0L
                }
                if (aid <= 0L) {
                    AntifraudDiag.step("BV 换 aid 失败（aid=$aid）")
                    AntifraudDiag.finish("FAILED｜拿不到 aid")
                    PopTip.show("没查到这条视频（可能已删除）")
                    return@launch          // 计数由 finally 归还（以前这里手动还了一次，变成"多还"）
                }
                AntifraudDiag.step("BV=$bv → aid=$aid，开始复检")
                val r = withContext(Dispatchers.IO) {
                    CommentAntifraud.check(
                        oid = aid,
                        type = 1,
                        rpid = rpid,
                        root = 0L,
                        // ★ 这里原来是 now —— 会让"翻到更早的评论就停"在第 1 页立刻命中，
                        //   正常评论被误报成"仅自己可见/审核中"（老记录复检唯一通路，必现）
                        sentTimeSec = sentTimeSec,
                        hasPictures = false,
                        skipWait = true,
                    )
                }
                showResult(r, message, aid, 1, rpid, 0L, null, sentTimeSec = sentTimeSec)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                PopTip.show("复检没跑完：${e.javaClass.simpleName}")
            } finally {
                activeChecks = (activeChecks - 1).coerceAtLeast(0)
            }
        }
    }

    private suspend fun readSettings(context: Context): Triple<Boolean, Boolean, Int> {
        return runCatching {
            SettingPreferences.mapData(context) {
                Triple(
                    it[SettingPreferences.AntifraudEnabled] ?: false,
                    it[SettingPreferences.AntifraudRecheckEnabled] ?: true,
                    it[SettingPreferences.AntifraudRecheckMinutes]
                        ?: CommentAntifraud.DEFAULT_RECHECK_MINUTES,
                )
            }
        }.getOrElse { e ->
            // 读设置失败 ≠ 用户关了开关：记一笔，免得排查时被"设置里没打开"误导
            AntifraudDiag.start("评论反诈：读设置失败")
            AntifraudDiag.step("SettingPreferences 读取异常：${e.javaClass.simpleName} ${e.message}")
            AntifraudDiag.finish("SKIPPED｜读设置失败", mirror = false)
            Triple(false, true, CommentAntifraud.DEFAULT_RECHECK_MINUTES)
        }
    }



    private fun showResult(
        result: AntifraudResult,
        message: String,
        oid: Long,
        type: Int,
        rpid: Long,
        /** 根评论 id：楼中楼复检要用它，否则会把二级评论当根评论查（审查发现的误判） */
        root: Long,
        onOpenAppeal: ((oid: Long, type: Int, rpid: Long) -> Unit)?,
        /**
         * 这条评论的发送时间（秒）。0 = 不知道（手动复检老记录时）。
         * 存下来是为了让设置页的「重新检测」能用上正确的早停时间，见 AntifraudLastResult.Result.sentTimeSec。
         */
        sentTimeSec: Long = 0L,
    ) {
        // ★ 不管结果是好是坏，**一律弹窗**（用户要求：等了好几分钟，不能只闪个提示就完了）。
        //   内容固定四段：状态 / 哪条视频下的哪条评论 / 判定依据 / 免责说明。
        // ★ 先落盘再弹窗：复查跑几分钟，用户切走/进程被杀时弹窗弹不出来（实测撞过），
        //   设置页里那份"上次检测结果"就是他唯一的交代。
        runCatching {
            AntifraudResultState.set(
                BilimiaoCommApp.commApp.app,
                AntifraudLastResult.Result(
                    time = System.currentTimeMillis(),
                    title = result.title,
                    detail = result.detail,
                    where = if (type == 1) {
                        runCatching { BvUtils.toBvid(oid.toString()) }.getOrNull()?.let { "视频 $it" } ?: "av$oid"
                    } else "oid=$oid",
                    rpid = rpid,
                    message = message.take(200),
                    isBad = result.isBad,
                    oid = oid,
                    type = type,
                    root = root,
                    sentTimeSec = sentTimeSec,
                ),
            )
        }
        val mark = if (result.isBad) "⚠️ " else "✅ "
        val where = buildString {
            if (type == 1) {
                // 视频：把 aid 换成 BV 号显示，用户认得出来
                val bv = runCatching { BvUtils.toBvid(oid.toString()) }.getOrNull()
                append(if (bv.isNullOrBlank()) "视频 av$oid" else "视频 $bv")
            } else {
                append("评论区 oid=$oid（type=$type）")
            }
            append("\n评论 ID：$rpid")
        }
        val body = buildString {
            append(where)
            append("\n\n评论内容：")
            // ★ 这里原来写的是 message.take(200) + "…"：内容长了就被截掉，用户"想看全部"看不到。
            //   DialogX 的消息体本来就在 dialogx 的 DialogScrollView 里（外面还有 MaxRelativeLayout 封顶高度），
            //   所以直接给全文即可 —— 长了能上下滑，按钮不受影响。
            append(message)
            append("\n\n判定依据：")
            append(result.detail)
            if (result.code != 0) {
                append("\n（接口返回 ${result.code} ${result.rawMessage}）")
            }
            append("\n\n结果仅供参考：阿瓦隆会按账号/评论区/内容分别控评，不代表账号被封。")
        }
        showResultDialog(
            mark = mark,
            title = result.title,
            body = body,
            isBad = result.isBad,
            oid = oid,
            type = type,
            rpid = rpid,
            message = message,
            onOpenAppeal = onOpenAppeal,
        )
    }

    /**
     * 反诈结果弹窗 —— **两处共用**：① 发完评论后的首次检测；② 设置页里那条"上次检测结果"。
     *
     * 用户 2026-09-25 要求：**两个弹窗的按钮和位置必须一模一样**（"不要这个位置在那，这个位置在这"），
     * 所以这里只留一个构建入口，谁都不许自己拼按钮。
     *
     * 按钮顺序（DialogX 的 Material 布局槽位是固定的：`btn_selectOther` + space(weight=1) +
     * `btnSelectNegative` + `btnSelectPositive`，即"最左 / 空隙 / 中间 / 最右"），
     * 因此按**位置**分配而不是按语义分配：
     *   ① 关闭 → other（最左）    ② 申诉此评论 → cancel（中间）    ③ 删除此评论 → ok（最右）
     *
     * 另外：申诉**无条件显示**（原来写成"有回调才显示"，用户实测弹窗里根本没有申诉按钮）。
     */
    fun showResultDialog(
        mark: String,
        title: String,
        body: String,
        isBad: Boolean,
        oid: Long,
        type: Int,
        rpid: Long,
        message: String,
        onOpenAppeal: ((oid: Long, type: Int, rpid: Long) -> Unit)? = null,
    ) {
        val dialog = MessageDialog.build()
            .setTitle(mark + title)
            .setMessage(body)
        if (isBad) {
            dialog.setOtherButton("关闭") { _, _ -> false }
            dialog.setCancelButton("申诉此评论") { _, _ ->
                openAppealPage()
                false
            }
            dialog.setOkButton("删除此评论") { _, _ ->
                deleteComment(type, oid, rpid)
                false
            }
        } else {
            dialog.setCancelButton("关闭")
            dialog.setOkButton("知道了")
        }
        dialog.show()
    }

    /**
     * 去申诉：先把"是哪条评论"复制进剪贴板，再用**内置浏览器**打开官方申诉页。
     *
     * 为什么复制：官方申诉页是个表单，要填评论定位；而它支持的 URL 参数我们没有权威依据
     * （不臆造参数，免得表单打不开），所以把信息放剪贴板让用户直接粘。
     * 为什么走内置页：App 的登录态在内置 WebView 的 CookieManager 里，甩给外部浏览器等于让用户
     * 重新登录一次（用户实测反馈）。
     */

    private fun deleteComment(type: Int, oid: Long, rpid: Long) {
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    CommentApi().del(type, oid.toString(), rpid.toString())
                        .awaitCall()
                        .json<MessageInfo>()
                }
                if (res.isSuccess) {
                    toast("已删除这条评论")
                } else {
                    toast("删除失败：${res.message}")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                toast("删除失败：${e.message ?: e.toString()}")
            }
        }
    }
}
