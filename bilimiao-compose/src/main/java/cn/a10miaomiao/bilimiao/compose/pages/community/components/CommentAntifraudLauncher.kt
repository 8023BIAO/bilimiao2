package cn.a10miaomiao.bilimiao.compose.pages.community.components

import android.content.Context
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.antifraud.AntifraudResult
import com.a10miaomiao.bilimiao.comm.antifraud.AntifraudState
import cn.a10miaomiao.bilimiao.compose.components.antifraud.AntifraudMonitor
import cn.a10miaomiao.bilimiao.compose.components.antifraud.AntifraudMonitorSession
import com.a10miaomiao.bilimiao.comm.antifraud.AntifraudDiag
import com.a10miaomiao.bilimiao.comm.antifraud.AntifraudLastResult
import com.a10miaomiao.bilimiao.comm.antifraud.CommentAntifraud
import com.a10miaomiao.bilimiao.comm.apis.CommentApi
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.entity.MessageInfo
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

    /** B站官方评论申诉页（H5，App 内置 WebView 打开，带着登录态） */
    const val APPEAL_URL = "https://www.bilibili.com/h5/comment/appeal"

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
                val recheckMs = if (recheckEnabled) recheckMinutes * 60_000L else 0L
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
                        "评论已发出：${first}后首查，之后每 30 秒复查一次，共盯 $recheckMinutes 分钟（最多 $times 次）"
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
                            // 更新设置页里的进度
                            monitor?.let {
                                it.attempt = attempt
                                it.planned = total
                                it.lastState = result.state
                                it.lastDetail = result.detail
                            }
                            // 只在"首查正常、准备开始盯"的时候提示一次，之后静静盯着，别刷屏。
                            // 注意这个回调是在 IO 上下文里来的，弹窗必须切回主线程。
                            if (attempt == 1 && total > 1 && !result.isBad) {
                                scope.launch {
                                    PopTip.show("首查正常，开始复查（每 30 秒一次 / 共 $recheckMinutes 分钟）")
                                }
                            }
                        },
                    )
                }
                showResult(r, message, oid, type, rpid, onOpenAppeal)
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
    private suspend fun readSettings(context: Context): Triple<Boolean, Boolean, Int> {
        return runCatching {
            SettingPreferences.mapData(context) {
                Triple(
                    it[SettingPreferences.AntifraudEnabled] ?: false,
                    it[SettingPreferences.AntifraudRecheckEnabled] ?: true,
                    it[SettingPreferences.AntifraudRecheckMinutes]
                        ?: DEFAULT_RECHECK_MINUTES,
                )
            }
        }.getOrDefault(Triple(false, true, DEFAULT_RECHECK_MINUTES))
    }

    private const val DEFAULT_RECHECK_MINUTES = 5

    private fun showResult(
        result: AntifraudResult,
        message: String,
        oid: Long,
        type: Int,
        rpid: Long,
        onOpenAppeal: ((oid: Long, type: Int, rpid: Long) -> Unit)?,
    ) {
        // ★ 不管结果是好是坏，**一律弹窗**（用户要求：等了好几分钟，不能只闪个提示就完了）。
        //   内容固定四段：状态 / 哪条视频下的哪条评论 / 判定依据 / 免责说明。
        // ★ 先落盘再弹窗：复查跑几分钟，用户切走/进程被杀时弹窗弹不出来（实测撞过），
        //   设置页里那份"上次检测结果"就是他唯一的交代。
        runCatching {
            AntifraudLastResult.save(
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
            append(message.take(200))
            if (message.length > 200) append("…")
            append("\n\n判定依据：")
            append(result.detail)
            if (result.code != 0) {
                append("\n（接口返回 ${result.code} ${result.rawMessage}）")
            }
            append("\n\n结果仅供参考：阿瓦隆会按账号/评论区/内容分别控评，不代表账号被封。")
        }
        val dialog = MessageDialog.build()
            .setTitle(mark + result.title)
            .setMessage(body)
            .setCancelButton("关闭")
        if (result.isBad) {
            dialog.setOkButton("删除这条评论") { _, _ ->
                deleteComment(type, oid, rpid)
                false
            }
            if (onOpenAppeal != null) {
                dialog.setOtherButton("去申诉") { _, _ ->
                    onOpenAppeal(oid, type, rpid)
                    false
                }
            }
        } else {
            dialog.setOkButton("知道了")
        }
        dialog.show()
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
