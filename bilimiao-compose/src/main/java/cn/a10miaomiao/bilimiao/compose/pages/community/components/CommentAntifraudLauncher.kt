package cn.a10miaomiao.bilimiao.compose.pages.community.components

import android.content.Context
import cn.a10miaomiao.bilimiao.compose.components.antifraud.AntifraudAppealDialogState
import cn.a10miaomiao.bilimiao.compose.components.antifraud.AntifraudAppealRequest
import cn.a10miaomiao.bilimiao.compose.components.antifraud.AntifraudMonitor
import cn.a10miaomiao.bilimiao.compose.components.antifraud.AntifraudMonitorSession
import cn.a10miaomiao.bilimiao.compose.components.antifraud.AntifraudResultState
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.antifraud.AntifraudAppealQuota
import com.a10miaomiao.bilimiao.comm.antifraud.AntifraudDiag
import com.a10miaomiao.bilimiao.comm.antifraud.AntifraudLastResult
import com.a10miaomiao.bilimiao.comm.antifraud.AntifraudResult
import com.a10miaomiao.bilimiao.comm.antifraud.CommentAntifraud
import com.a10miaomiao.bilimiao.comm.apis.CommentApi
import com.a10miaomiao.bilimiao.comm.apis.CommentAppeal
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.entity.MessageInfo
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.toast
import com.a10miaomiao.bilimiao.comm.utils.BvUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kongzue.dialogx.dialogs.MessageDialog

/**
 * 评论反诈的 UI 侧入口：发完评论后等几秒自动检测，出结果弹窗（删除 / 自动申诉 / 关闭）。
 *
 * 检测逻辑在 [CommentAntifraud]（bilimiao-comm），判定规则照搬 biliSendCommAntifraud：
 * https://github.com/freedom-introvert/biliSendCommAntifraud
 *
 * 申诉这一段（2026-09-26 用户拍板的三条）：
 *   ① **不要 WebView / 外部浏览器**：官方 H5 的表单字段已从它的前端 JS 里抓出来（[CommentAppeal]），
 *      原生直接 POST，成功失败都有明确提示；
 *   ② **两个输入**：照官方表单做「所在稿件BV号或位置链接」+「申诉理由」，链接自动填、
 *      理由默认用写好的正式长文案并记住用户改过的版本；
 *   ③ （**已回退**，2026-09-27 用户拍板）曾经有过"全自动反诈"开关：判定被限流就直接静默申诉、
 *      连弹窗都没有。用户觉得"一天最多三次，多余非必要" → 现在一律弹窗 + 底部「自动申诉」按钮。
 */
object CommentAntifraudLauncher {

    /** B站官方评论申诉页（H5）。只在**原生提交不可用**时兜底（拿不到 bili_jct 时） */
    const val APPEAL_URL = "https://www.bilibili.com/h5/comment/appeal"

    /**
     * 打开官方申诉页 —— 兜底路径。
     *
     * 现在的主路径是原生提交（见 [submitAppeal]）；只有拿不到 `bili_jct`（网页登录态）时
     * 才把用户送到官方页面。这里换过两轮，结论留着免得再折腾（用户 2026-09-25 拍板："你还是跳外部吧"）：
     *   · 内置 WebView：得替 B站 页面适配主题 —— 注入官方深色令牌 `bili_dark` 之后，
     *     表单变成"白底白字"（用户原话："黑色主题看不见"），提交还因为填错字段报"请求错误"；
     *   · 顺带的"把评论ID/位置复制到剪贴板让用户粘"也一起去掉了：用户粘进了"BV号"那一格，直接提交失败。
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

    /** 反诈设置三件套（读一次用一整轮，免得中途改设置读到半新半旧） */
    private data class AntiSettings(
        val enabled: Boolean,
        val recheckEnabled: Boolean,
        val recheckMinutes: Int,
    )

    /**
     * 发完评论后调一次（只在**发送成功**后调）。
     *
     * 登录门：没登录直接不检测（游客没有"仅自己可见"这回事，也无从申诉）。
     *
     * @param result      发送接口返回的原始结果（拿 rpid / root）
     * @param hasPictures 这条评论带图吗（带图首查要等久一点：图要先过审）
     * @param pictureCount 图片张数（写进申诉理由，审核端能看到"图文"）
     * @param onOpenAppeal 兜底回调：原生提交不可用时（拿不到网页登录态）由页面打开官方申诉页
     */
    fun start(
        result: com.a10miaomiao.bilimiao.comm.entity.video.VideoCommentSendResultInfo,
        message: String,
        hasPictures: Boolean,
        pictureCount: Int = 0,
        onOpenAppeal: ((oid: Long, type: Int, rpid: Long) -> Unit)? = null,
    ) {
        val rpid = result.rpid
        if (rpid <= 0) {
            // 以前这里一声不响地 return —— 用户根本不知道检测没跑，只能看到"什么都没发生"
            AntifraudDiag.start("评论反诈：参数缺失")
            AntifraudDiag.step("发送接口没回 rpid（rpid=$rpid）→ 无法定位这条评论，跳过检测")
            AntifraudDiag.finish("SKIPPED｜没有 rpid")
            toast("评论反诈：没拿到这条评论的 ID，这次没检测")
            return
        }
        val oid = result.reply.oid
        val type = result.reply.type
        if (oid <= 0 || type <= 0) {
            AntifraudDiag.start("评论反诈：参数缺失")
            AntifraudDiag.step("评论区参数不全（oid=$oid type=$type）→ 跳过检测")
            AntifraudDiag.finish("SKIPPED｜没有 oid/type")
            toast("评论反诈：没拿到评论区 ID，这次没检测")
            return
        }
        // 登录门：没登录检测一定不准（自己看自己永远"正常"）
        if (BilimiaoCommApp.commApp.loginInfo == null) {
            AntifraudDiag.start("评论反诈：未登录")
            AntifraudDiag.step("当前是未登录/游客模式 → 跳过检测（游客没有『仅自己可见』这回事）")
            AntifraudDiag.finish("SKIPPED｜未登录")
            toast("评论反诈：当前没登录，这次没检测")
            return
        }
        if (activeChecks >= MAX_ACTIVE_CHECKS) {
            AntifraudDiag.start("评论反诈：同时在查的评论太多")
            AntifraudDiag.step("已经有 $activeChecks 条评论在查（上限 $MAX_ACTIVE_CHECKS）→ 这次跳过")
            AntifraudDiag.finish("SKIPPED｜并行会话已达上限")
            toast("评论反诈：同时在查的评论太多了，这次跳过")
            return
        }
        activeChecks++
        val app = BilimiaoCommApp.commApp.app
        val root = result.root
        val sentTimeSec = System.currentTimeMillis() / 1000
        var monitor: AntifraudMonitorSession? = null
        scope.launch {
            try {
                val settings = readSettings(app)
                if (!settings.enabled) {
                    // 开关是用户自己关的 → 不打扰，但日志里留一笔（排查"为什么没检测"时要看）
                    AntifraudDiag.start("评论反诈：开关未开启")
                    AntifraudDiag.finish("SKIPPED｜设置里没打开「发评论后自动检测是否被限流」")
                    return@launch
                }
                // 上限兜底：设置导入没有范围校验，被写成天文数字会真盯那么久（审查发现）
                val minutes = settings.recheckMinutes.coerceIn(1, CommentAntifraud.RECHECK_MAX_MINUTES)
                val recheckMs = if (settings.recheckEnabled) minutes * 60_000L else 0L
                // 登记到「监控中」列表，设置页里实时显示进度（用户要求：别让他干等）
                val areaLabel = CommentAppeal.describeArea(oid, type)
                val planned = 1 + (recheckMs / CommentAntifraud.RECHECK_INTERVAL_MS).toInt()
                monitor = AntifraudMonitor.start(
                    label = areaLabel,
                    rpid = rpid,
                    firstWaitMs = if (hasPictures) CommentAntifraud.WAIT_MS + CommentAntifraud.WAIT_PIC_MS
                    else CommentAntifraud.WAIT_MS,
                    recheckEnabled = settings.recheckEnabled,
                    recheckTotalMs = recheckMs,
                    planned = planned,
                )
                toast(
                    if (!settings.recheckEnabled) {
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
                        recheckEnabled = settings.recheckEnabled,
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
                                    toast("首查正常，开始复查（每 30 秒一次 / 共 $minutes 分钟）")
                                }
                            }
                        },
                    )
                }
                showResult(
                    result = r,
                    message = message,
                    oid = oid,
                    type = type,
                    rpid = rpid,
                    root = root,
                    onOpenAppeal = onOpenAppeal,
                    sentTimeSec = sentTimeSec,
                    hasPictures = hasPictures,
                    pictureCount = pictureCount,
                )
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
            toast("缺少参数，无法复检")
            return
        }
        if (activeChecks >= MAX_ACTIVE_CHECKS) {
            toast("同时在查的评论太多了，稍后再试")
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
                toast("复检没跑完：${e.javaClass.simpleName}")
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
                    toast("没查到这条视频（可能已删除）")
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
                toast("复检没跑完：${e.javaClass.simpleName}")
            } finally {
                activeChecks = (activeChecks - 1).coerceAtLeast(0)
            }
        }
    }

    private suspend fun readSettings(context: Context): AntiSettings {
        return runCatching {
            SettingPreferences.mapData(context) {
                AntiSettings(
                    enabled = it[SettingPreferences.AntifraudEnabled] ?: false,
                    recheckEnabled = it[SettingPreferences.AntifraudRecheckEnabled] ?: true,
                    recheckMinutes = it[SettingPreferences.AntifraudRecheckMinutes]
                        ?: CommentAntifraud.DEFAULT_RECHECK_MINUTES,
                )
            }
        }.getOrElse { e ->
            // 读设置失败 ≠ 用户关了开关：记一笔，免得排查时被"设置里没打开"误导
            AntifraudDiag.start("评论反诈：读设置失败")
            AntifraudDiag.step("SettingPreferences 读取异常：${e.javaClass.simpleName} ${e.message}")
            AntifraudDiag.finish("SKIPPED｜读设置失败", mirror = false)
            AntiSettings(false, true, CommentAntifraud.DEFAULT_RECHECK_MINUTES)
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
        hasPictures: Boolean = false,
        pictureCount: Int = 0,
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
                    where = CommentAppeal.describeArea(oid, type),
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
            append(CommentAppeal.describeArea(oid, type))
            append("\n评论 ID：$rpid")
        }
        val body = buildString {
            append(where)
            append("\n\n评论内容：")
            // ★ 这里原来写的是 message.take(200) + "…"：内容长了就被截掉，用户"想看全部"看不到。
            //   DialogX 的消息体本来就在 dialogx 的 DialogScrollView 里（外面还有 MaxRelativeLayout 封顶高度），
            //   所以直接给全文即可 —— 长了能上下滑，按钮不受影响。
            append(message)
            if (hasPictures) append("\n（这条评论带图）")
            append("\n\n判定依据：")
            append(result.detail)
            if (result.code != 0) {
                append("\n（接口返回 ${result.code} ${result.rawMessage}）")
            }
            append("\n\n结果仅供参考：阿瓦隆会按账号/评论区/内容分别控评，不代表账号被封。")
        }
        // ★ 2026-09-27 用户拍板：**回退「全自动反诈」**（"感觉多余非必要了，因为一天最多三次"）。
        //   现在一律弹窗提醒，弹窗底部「自动申诉」走原生接口（带理由、不跳浏览器）—— 这部分保留。
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
            hasPictures = hasPictures,
            pictureCount = pictureCount,
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
     *   ① 关闭 → other（最左）    ② 自动申诉 → cancel（中间）    ③ 删除此评论 → ok（最右）
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
        hasPictures: Boolean = false,
        pictureCount: Int = 0,
    ) {
        val dialog = MessageDialog.build()
            .setTitle(mark + title)
            .setMessage(body)
        if (isBad) {
            dialog.setOtherButton("关闭") { _, _ -> false }
            // 用户 2026-09-26："底部还是有一个自动申诉的按钮" —— 名字就叫「自动申诉」，
            // 点开是官方同款的两个输入（链接 + 理由），提交走原生接口。
            dialog.setCancelButton("自动申诉") { _, _ ->
                showAppealDialog(
                    oid = oid,
                    type = type,
                    rpid = rpid,
                    message = message,
                    hasPictures = hasPictures,
                    pictureCount = pictureCount,
                    onOpenAppeal = onOpenAppeal,
                )
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

    // ─────────────────────────── 申诉（原生） ───────────────────────────

    /**
     * 申诉弹窗：官方 H5 同款两个输入。
     *
     * ★ 2026-09-27 改版（用户实测两条）：原版用 DialogX 的 CustomDialog 自己搭视图 ——
     *   ① 卡片配色是 DialogX 自己的 MaterialYou 调色板，跟本 App 的主题色/点缀色对不上；
     *   ② 横屏 / 大字体时底部按钮会被裁到点不到。
     *   现在改成 App 自己的 AutoSheetDialog（直播页、番剧首页那个"底栏筛选弹窗"同一套）：
     *   主题色跟随 MaterialTheme，竖屏贴底/横屏居中、旋转与安全区都由 AnyPopDialog 兜住。
     *   宿主挂在 ComposeFragment 根部，见 AntifraudAppealDialogHost。
     *
     * 这里只负责把 IO 数据（保存的理由、额度）备齐，然后 set 一下全局状态。
     */
    fun showAppealDialog(
        oid: Long,
        type: Int,
        rpid: Long,
        message: String,
        hasPictures: Boolean = false,
        pictureCount: Int = 0,
        /** 直接以「图文动态申诉」打开（设置页入口用；此时 oid/type 可以为 0） */
        startAsDynamic: Boolean = false,
        onOpenAppeal: ((oid: Long, type: Int, rpid: Long) -> Unit)? = null,
    ) {
        val app = BilimiaoCommApp.commApp.app
        if (MiaoHttp.csrfToken().isNullOrBlank()) {
            // ★ 兜底**不用**调用方给的 onOpenAppeal：评论链路传进来的是内置 WebView，
            //   而那条路正是本文件上面写明弃用的（深色主题下官方页面白底白字、提交字段还容易填错）。
            //   直接甩外部浏览器，反而一次到位（review 抓到）。
            toast("当前登录态提交不了申诉（拿不到 bili_jct），给你打开官方申诉页")
            openAppealPage()
            return
        }
        scope.launch {
            // 额度在 SharedPreferences：放 IO 线程读，别卡主线程（理由不再持久化，直接用内置长文案）
            val quota = withContext(Dispatchers.IO) { quotaSummary(app) }
            AntifraudAppealDialogState.show(
                AntifraudAppealRequest(
                    oid = oid,
                    type = type,
                    rpid = rpid,
                    message = message,
                    hasPictures = hasPictures,
                    pictureCount = pictureCount,
                    startAsDynamic = startAsDynamic,
                    presetTarget = if (startAsDynamic) "" else CommentAppeal.autoTarget(oid, type),
                    presetReason = CommentAppeal.DEFAULT_REASON,
                    quotaText = quota,
                )
            )
        }
    }

    /**
     * 真正把申诉发出去（申诉弹窗提交 / 设置页「自动申诉上一条评论」都走这里）。
     *
     * @param reasonBase 用户填的理由（null = 用已保存的 / 内置正式长文案）
     */
    fun submitAppeal(
        target: CommentAppeal.Target,
        reasonBase: String?,
        comment: String,
        hasPictures: Boolean,
        pictureCount: Int,
        oid: Long,
        type: Int,
        rpid: Long,
        onOpenAppeal: ((oid: Long, type: Int, rpid: Long) -> Unit)? = null,
    ) {
        val app = BilimiaoCommApp.commApp.app
        val uid = BilimiaoCommApp.commApp.loginInfo?.token_info?.mid ?: 0L
        val key = appealKey(target, rpid)
        // ★ 在途互斥（review 抓到）：额度/去重是"检查 → 发网络 → 回来才记账"，
        //   中间这几秒里同一个目标再来一次就会把额度打两次。先在途占位，finally 释放。
        if (!appealInFlight.add(key)) {
            toast("这条内容正在提交申诉，稍等一下")
            return
        }
        // 额度 / 去重兜底校验（弹窗提交与设置页一键共用同一份口径）
        if (AntifraudAppealQuota.remaining(app) <= 0) {
            appealInFlight.remove(key)
            toast("24 小时内最多 ${CommentAppeal.DAILY_LIMIT} 次申诉，本机记录已用满，明天再试")
            return
        }
        if (AntifraudAppealQuota.isDuplicate(app, key)) {
            appealInFlight.remove(key)
            toast("这条内容 24 小时内已经申诉过了")
            return
        }
        scope.launch {
            try {
                toast("正在提交申诉…")
                // 理由：弹窗里填的（可能被用户改过）优先，否则用内置长文案；不落盘（用户 2026-09-27 拍板）
                val base = reasonBase?.takeIf { it.isNotBlank() } ?: CommentAppeal.DEFAULT_REASON
                val reason = CommentAppeal.composeReason(
                    base = base,
                    comment = comment,
                    link = target.link,
                    hasPictures = hasPictures,
                    pictureCount = pictureCount,
                )
                AntifraudDiag.start(
                    "评论申诉 kind=${target.kind} " +
                        "target=${target.link} oid=$oid type=$type rpid=$rpid"
                )
                AntifraudDiag.step("理由长度=${reason.length} 图片=$pictureCount")
                val r = withContext(Dispatchers.IO) {
                    CommentAppeal.submit(target = target, reason = reason, uid = uid)
                }
                AntifraudDiag.step("code=${r.code} success=${r.success}")
                AntifraudDiag.finish(
                    if (r.success) "OK｜${r.message}" else "FAILED｜${r.message}",
                    mirror = false,
                )
                AntifraudLastResult.markAppeal(
                    context = app,
                    ok = r.success,
                    message = r.message,
                    target = target.link,
                    auto = false,
                    // 只有"设置页那条记录就是这条评论"时才回写（并发会话防串台）
                    rpid = rpid,
                )?.let { updated ->
                    // 同步刷新设置页那份可观察状态（用户停在设置页时也能立刻看到申诉结果）
                    runCatching { AntifraudResultState.set(app, updated) }
                }
                if (r.success) {
                    AntifraudAppealQuota.record(app, key)
                    run {
                        MessageDialog.build()
                            .setTitle("申诉已提交")
                            .setMessage(r.message + "\n\n处理结果会发到「消息 → 系统通知」，请留意。")
                            .setOkButton("知道了")
                            .show()
                    }
                } else {
                    // 56602（已经提交过）只记去重标记，**不占额度**（那次请求没真的消耗次数）；
                    // 56601（当天 3 次用完）把本地额度一并记满，24 小时内不再空打（服务端额度是全端共享的）
                    if (r.code == 56602) AntifraudAppealQuota.recordDedupOnly(app, key)
                    if (r.code == 56601) AntifraudAppealQuota.markServerExhausted(app)
                    run {
                        MessageDialog.build()
                            .setTitle("申诉没提交成功")
                            .setMessage(r.message)
                            .setOkButton("知道了")
                            .show()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                AntifraudDiag.finish("FAILED｜申诉异常 ${e.javaClass.simpleName}", mirror = false)
                toast("申诉失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                appealInFlight.remove(key)
            }
        }
    }

    // ★ 2026-09-27 用户砍掉「自动申诉上一条评论」与「申诉理由编辑」两个设置入口（非必要勿增实体）：
    //   申诉理由不再单独持久化 —— 弹窗里预填内置长文案，用户当场改、当场用，提交完就结束。
    /**
     * 去重键：评论申诉用 oid/url（canonical），图文动态用 link。
     *
     * 直接用用户手填的原文当键会漏判 —— 同一个视频写成 BV1xx / av123 / 完整链接就是三个键
     * （review 抓到）；最终虽然由服务端 56602 兜住，但会白打一次请求、也可能白占一次额度。
     */
    /** 正在提交中的申诉目标键（防止并发路径把同一份额度打两次） */
    private val appealInFlight: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun appealKey(t: CommentAppeal.Target, rpid: Long = 0L): String =
        AntifraudAppealQuota.keyOf(
            t.kind.name,
            // ★ 键里必须带 rpid（review 抓到的真问题）：同一个视频/专栏下的**不同评论**是不同申诉，
            //   不带的话第 2 条评论会被当成"这条内容 24 小时内已经申诉过"直接拦掉。
            //   图文动态申诉没有 rpid，就按 link 去重（同一个动态重复申诉确实没意义）。
            listOfNotNull(
                (t.oid ?: t.url ?: t.link).takeIf { it.isNotBlank() },
                rpid.takeIf { it > 0L }?.toString(),
            ).joinToString("#"),
        )

    /** 设置页展示用：本地额度一句话（"24 小时 3 次，本机已用 1 次"） */
    fun quotaSummary(context: Context = BilimiaoCommApp.commApp.app): String {
        val used = AntifraudAppealQuota.usedCount(context)
        val left = AntifraudAppealQuota.remaining(context)
        return "官方限制 24 小时 ${CommentAppeal.DAILY_LIMIT} 次；本机记录已用 $used 次（剩 $left 次）"
    }

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
