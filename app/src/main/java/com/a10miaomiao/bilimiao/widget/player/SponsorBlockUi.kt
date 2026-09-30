package com.a10miaomiao.bilimiao.widget.player

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.a10miaomiao.bilimiao.comm.apis.SponsorBlockApi
import com.a10miaomiao.bilimiao.comm.utils.ClickGuard
import com.a10miaomiao.bilimiao.comm.utils.OverlayDialog
import com.a10miaomiao.bilimiao.comm.utils.SponsorDiag
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorActionType
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorCategory
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorSegment
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorSkipType
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.shuyu.gsyvideoplayer.video.base.GSYVideoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「空降助手」的交互界面：片段列表（彩色圆点）、投票（赞成/反对/改类别）、提交新片段。
 *
 * 为什么单独一个文件、且全部用代码建视图：播放器文件已经很大，而且这些弹窗只需要
 * 标准控件（列表 + 输入框），不值得为它们改布局资源（改 XML 的风险面更大）。
 *
 * 对齐 PiliPlus：
 *  - 片段列表 = `block_mixin.dart` 的 `showSBDetail()`（每行一个彩色圆点 + 类别名）
 *  - 点某行 → 投票弹窗（`_showVoteDialog`：赞成票 / 反对票 / 更改类别）
 *  - 提交 = `post_panel` 的 segmentWidget（开始/结束 + 分类 + 动作）
 */
object SponsorBlockUi {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private const val PAD = 16

    /**
     * 正在显示的弹窗**栈**（片段列表 → 投票 → 改类别 会叠三层，单个引用表达不了）。
     *
     * 为什么必须是栈：
     *  - 这些弹窗挂在 Activity 上，Activity 先销毁而弹窗还在就 WindowLeaked；
     *    播放器销毁时由 `PlayerDelegate2.onDestroy()` 调 [dismissAll] 统一收掉 ——
     *    单引用漏掉的那几层（例如提交弹窗里的分类/动作选择）就必然泄漏。
     *  - 以前用单个 `currentDialog`：子弹窗打开会覆盖父弹窗的引用，子弹窗关掉后引用既不回退
     *    也不清空 → 父弹窗的「关闭」按钮 dismiss 的是一具已关闭的 Dialog（框架 `!mShowing`
     *    直接早返回）→ **按钮静默失效**，且父弹窗再也不会被 dismissAll 关掉。
     *
     * 登记方式：`OverlayDialog.show(..., onDismiss = { stack.remove(dlg) })`。
     * **不要**在返回的 Dialog 上再 `setOnDismissListener` —— 那会覆盖 OverlayDialog 自己设的
     * 监听器，decorView 上的 OnLayoutChangeListener 就摘不掉了。
     */
    private val dialogStack = mutableListOf<Dialog>()

    // ── 防连点闸门 key（顶栏两个按钮连点 N 次不该叠 N 个弹窗）──
    private const val KEY_SEGMENTS = "sponsor_ui:segments"
    private const val KEY_SUBMIT = "sponsor_ui:submit"

    /**
     * 显示覆盖层弹窗并登记进栈（关闭时自动出栈）；弹窗里的「关闭」按钮请 dismiss 它的返回值。
     * [onDismissExtra] 用来挂"释放防连点闸门"这类收尾动作。
     */
    private fun showOverlay(
        activity: Activity,
        card: View,
        onDismissExtra: (() -> Unit)? = null,
    ): Dialog {
        var dlg: Dialog? = null
        dlg = OverlayDialog.show(activity, card, onDismiss = {
            dialogStack.remove(dlg)
            onDismissExtra?.invoke()
        })
        dialogStack.add(dlg)
        return dlg
    }

    /** 列表选择弹窗（点选即自动关闭，见 [OverlayDialog.showList] 的契约），同样登记进栈 */
    private fun showOverlayList(
        activity: Activity,
        title: String,
        items: List<String>,
        onPick: (Int) -> Unit,
    ): Dialog {
        var dlg: Dialog? = null
        dlg = OverlayDialog.showList(
            activity, title, items,
            onPick = onPick,
            onDismiss = { dialogStack.remove(dlg) },
        )
        dialogStack.add(dlg)
        return dlg
    }

    /** Activity 销毁时调用：关掉所有残留弹窗（从栈顶往下），并取消还在等待"到点暂停"的试播。 */
    fun dismissAll() {
        cancelTrial()
        dialogStack.toList().forEach { runCatching { it.dismiss() } }
        dialogStack.clear()
    }

    /**
     * 覆盖层的**卡片内容**：标题 + 内容 +（可选）关闭按钮。
     * 观感对齐首页筛选弹层（标题在卡片里、按钮在卡片底部）。
     */
    private fun cardOf(
        context: Context,
        title: String,
        content: View,
        closeLabel: String? = "关闭",
        onClose: () -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAD), dp(14), dp(PAD), dp(8))
            addView(TextView(context).apply {
                text = title
                textSize = 17f
                setPadding(0, 0, 0, dp(10))
            })
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            if (closeLabel != null) {
                addView(TextView(context).apply {
                    text = closeLabel
                    textSize = 15f
                    gravity = Gravity.END
                    setPadding(0, dp(10), 0, dp(6))
                    isClickable = true
                    setOnClickListener { onClose() }
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        }
    }

    /**
     * 高度封顶的 ScrollView：横屏时屏幕矮，内容不封顶的话弹窗会长到屏幕外面去
     * （按钮被顶出可视区，用户"看得到标题、点不到按钮"就是这么来的）。
     */
    private class CappedScrollView(
        private val host: Activity,
        private val maxHeightFraction: Float,
    ) : ScrollView(host) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // ★ 用**宿主 decorView 的实时高度**算上限，别用 resources.displayMetrics：
            //   本 App 旋转不重建 Activity，resources 里可能还是旧方向的配置 ——
            //   这就是"横屏下弹窗又高又宽、取消/提交被顶到屏幕外"的来源。
            //   decorView 是当前真实窗口，永远最新。
            val decorH = host.window?.decorView?.height ?: 0
            val base = if (decorH > 0) decorH else resources.displayMetrics.heightPixels
            val capped = MeasureSpec.makeMeasureSpec(
                (base * maxHeightFraction).toInt(), MeasureSpec.AT_MOST
            )
            super.onMeasure(widthMeasureSpec, capped)
        }
    }

    // ───────────────────────── 片段列表 ─────────────────────────

    fun showSegments(activity: Activity, player: DanmakuVideoPlayer) {
        val segments = player.sponsorSegments
        if (segments.isEmpty()) {
            toast(activity, "这个视频还没有人标记片段")
            return
        }
        // 连点顶栏「片段」按钮只开一个（列表为空时上面已经 return，不会把闸门占死）
        if (!ClickGuard.enter(KEY_SEGMENTS)) return
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAD), dp(8), dp(PAD), dp(8))
        }
        // 整片标记（如"恰饭"）单独提一行
        if (player.sponsorVideoLabel.isNotBlank()) {
            box.addView(TextView(ctx).apply {
                text = "整片标记：${player.sponsorVideoLabel}"
                setTextColor(Color.parseColor("#FF9800"))
                textSize = 13f
                setPadding(0, dp(4), 0, dp(8))
            })
        }
        segments.forEach { seg ->
            box.addView(buildSegmentRow(ctx, player, seg, onVote = {
                showVote(activity, player, seg)
            }, onJump = {
                // showOnly = 只提示不跳 → 按钮给"跳至"；其它档给"跳过"（对齐 PiliPlus）
                val toEnd = player.sponsorSkipTypeFor(seg) != SponsorSkipType.ShowOnly
                player.sponsorJumpTo(seg, toEnd)
            }))
        }

        val scroll = CappedScrollView(activity, 0.62f).apply { addView(box) }
        // 走全屏覆盖层（和首页筛选弹层同一套），不用系统小弹窗
        // ★「关闭」按钮 dismiss 的是**这个弹窗自己**，不是某个可能已过期的全局引用
        var self: Dialog? = null
        self = showOverlay(
            activity,
            cardOf(activity, "空降助手片段（${segments.size}）", scroll) { self?.dismiss() },
            onDismissExtra = { ClickGuard.leave(KEY_SEGMENTS) },
        )
    }

    /**
     * 一行：彩色圆点 + 类别 + 时间区间 + 票数 + 「跳过/跳至」按钮。
     * 点整行进投票弹窗，点右侧按钮直接跳（对齐 PiliPlus `showSBDetail` 的 trailing 按钮）。
     */
    private fun buildSegmentRow(
        ctx: Context,
        player: DanmakuVideoPlayer,
        seg: SponsorSegment,
        onVote: () -> Unit,
        onJump: () -> Unit,
    ): View {
        val density = ctx.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener { onVote() }

            // 优先用用户自定义色（设置页"片段颜色"里改的）
            addView(dot(ctx, player.sponsorColors[seg.category] ?: SponsorCategory.colorOf(seg.category)))
            addView(TextView(ctx).apply {
                text = buildString {
                    append(SponsorCategory.labelOf(seg.category))
                    if (seg.isPoint) {
                        append("（整片标记）")
                    } else {
                        append("  ")
                        append(SponsorTime.format(seg.startMs))
                        append(" - ")
                        append(SponsorTime.format(seg.endMs))
                    }
                }
                textSize = 15f
                setPadding(dp(10), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                // 处理策略标签（对齐 PiliPlus showSBDetail：trailing 上是 item.skipType.label）
                text = player.sponsorSkipTypeFor(seg).label
                textSize = 12f
                setTextColor(Color.parseColor("#9E9E9E"))
                setPadding(dp(6), 0, dp(6), 0)
            })
            addView(TextView(ctx).apply {
                // 票数：locked 只是"服务端不再接受改动"的标记，票数照样显示出来
                text = if (seg.locked >= 1.0) "锁定·${seg.votes.toInt()}票" else "${seg.votes.toInt()}票"
                textSize = 12f
                setTextColor(Color.parseColor("#9E9E9E"))
                setPadding(dp(2), 0, dp(6), 0)
            })
            // 零宽片段（服务端的"整片标记"，如 [0,0]）不给跳转按钮：
            // 它的"起点"就是 0，点一下等于把视频拉回开头（实测很吓人）
            if (!seg.isPoint) {
                addView(TextView(ctx).apply {
                    text = if (player.sponsorSkipTypeFor(seg) == SponsorSkipType.ShowOnly) "跳至" else "跳过"
                    textSize = 14f
                    setTextColor(Color.parseColor("#2196F3"))
                    setPadding(dp(6), 0, 0, 0)
                    isClickable = true
                    setOnClickListener { onJump() }
                })
            }
        }
    }

    // ───────────────────────── 投票 ─────────────────────────

    private fun showVote(activity: Activity, player: DanmakuVideoPlayer, seg: SponsorSegment) {
        // ★ locked 的片段**不拦着用户投**（这点跟 PiliPlus 保持一致：它压根不看 locked）。
        //   实测服务端对 locked=1 的片段投票返回 200 但**不计数**（静默忽略），
        //   所以"能不能投"交给服务端，我们只负责把结果**如实**告诉用户（见 vote()）。
        val items = listOf("赞成票", "反对票", "更改类别")
        // 点选后由 showList 自己关掉。这里**不要**再写 currentDialog?.dismiss()：
        // 那个引用可能指向别的弹窗（父层片段列表），会把不该关的一起关掉。
        showOverlayList(
            activity,
            "${SponsorCategory.labelOf(seg.category)} · ${SponsorTime.format(seg.startMs)}",
            items,
        ) { which ->
            when (which) {
                0 -> vote(activity, seg, type = 1, category = null)
                1 -> vote(activity, seg, type = 0, category = null)
                else -> showCategoryPicker(activity, seg)
            }
        }
    }

    private fun showCategoryPicker(activity: Activity, seg: SponsorSegment) {
        val categories = SponsorCategory.entries
        showOverlayList(activity, "改成哪个类别？", categories.map { it.label }) { which ->
            vote(activity, seg, type = null, category = categories[which].id)
        }
    }

    private fun vote(
        activity: Activity,
        seg: SponsorSegment,
        type: Int?,
        category: String?,
    ) {
        scope.launch {
            val ok = try {
                BiliApiService.sponsorBlockAPI.vote(seg.UUID, type = type, category = category)
            } catch (e: Exception) {
                false
            }
            toast(
                activity,
                when {
                    !ok -> "投票失败：网络异常或服务端拒绝了这次请求"
                    // 服务端对 locked 片段返回 200 但**不计数**（实测），所以别说"成功"骗用户
                    seg.locked >= 1.0 -> "已发送。但该片段已被服务端锁定，票数不会变化"
                    else -> "投票成功"
                }
            )
        }
    }

    // ───────────────────────── 提交片段 ─────────────────────────

    /**
     * 上一次没提交完的草稿（key = `"$bvid|$cid"`）：关掉弹窗再打开要接着改，
     * 这样用户能"先填时间 → 关弹窗 → 拖进度条核对 → 再打开接着改"。
     *
     * 为什么只留**一份**而不是 Map：同一时刻只可能在编辑一个视频，留 Map 只会随浏览过的视频无限长。
     */
    private class SubmitDraft(
        val key: String,
        val start: String,
        val end: String,
        val category: SponsorCategory,
        val action: SponsorActionType,
    )

    private var submitDraft: SubmitDraft? = null

    /**
     * 两个时间输入框的过滤器：只放行 `0-9`、`:`、`：`、`.`。
     *
     * 全角冒号也放行 —— [SponsorTime.parseSec] 认它（PiliPlus 也是 `[:：]`）；
     * 粘贴 `01:11.250` 要能整段进；字母等非法字符只丢掉它们自己，**不会把已有内容清空**
     * （返回过滤后的片段当替换文本，而不是返回空串）。
     *
     * ★ 为什么不换成数字/时间键盘（`TYPE_CLASS_NUMBER` / `TYPE_CLASS_DATETIME`）：
     *   部分输入法的数字键盘**没有 `:` 和 `.` 键**，换过去用户连 `mm:ss` 都打不出来
     *   （毫秒更不可能）。所以键盘保持 `TYPE_CLASS_TEXT`，只靠这个过滤器限字符。
     */
    private val TIME_INPUT_FILTER = InputFilter { source, _, _, _, _, _ ->
        val kept = source.filter { it in '0'..'9' || it == ':' || it == '：' || it == '.' }
        if (kept.length == source.length) null else kept
    }

    /** 提交时的最短片段：太短服务端也会拒，本地先拦一次省一趟往返（一行可改；试播**不**受此限） */
    private const val MIN_SUBMIT_SEGMENT_MS = 500L

    /**
     * 提交新片段。
     *
     * 表单：开始 / 结束（`mm:ss.SSS`，也可手输纯秒数）+「设为当前」+ 分类 + 动作。
     * 默认区间是"当前时间点"（和 PiliPlus 的快捷提交一致：先设为当前，再手动调两端）；
     * 有草稿时用草稿 —— 输入框的值**只**由"用户手输 / 设为当前 / 视频开头·结尾"三件事改写，
     * 没有任何播放进度回调或定时器会来冲掉它。
     */
    fun showSubmit(
        activity: Activity,
        player: DanmakuVideoPlayer,
        bvid: String,
        cid: String,
    ) {
        if (bvid.isBlank() || cid.isBlank()) {
            toast(activity, "这个视频不支持提交片段")
            return
        }
        // 连点顶栏「提交」按钮只开一个（提交成功后弹窗关闭时释放闸门）
        if (!ClickGuard.enter(KEY_SUBMIT)) return
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val nowMs = player.currentPosition.coerceAtLeast(0L)
        val durationSec = (player.duration / 1000.0).takeIf { it > 0 } ?: 0.0

        val draftKey = "$bvid|$cid"
        val draft = submitDraft?.takeIf { it.key == draftKey }
        var category = draft?.category ?: SponsorCategory.Sponsor
        var action = draft?.action ?: SponsorActionType.Skip

        val startEt = EditText(ctx).apply {
            // 键盘保持文本：数字/时间键盘在部分输入法上没有 `:` `.` 键（详见 TIME_INPUT_FILTER 注释）
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(TIME_INPUT_FILTER)
            setText(draft?.start ?: SponsorTime.format(nowMs))
        }
        val endEt = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(TIME_INPUT_FILTER)
            setText(draft?.end ?: SponsorTime.format(nowMs))
        }
        val categoryTv = TextView(ctx).apply {
            text = "分类：${category.label}"
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener {
                // 列表点选后由 showList 自动关闭（默认契约）。
                // ★ 这里**绝不能**写 currentDialog?.dismiss()：此刻它指向底下的「提交片段」主弹窗，
                //   会把用户填了一半的表单一起关掉（这正是不能照抄投票弹窗写法的原因）。
                showOverlayList(ctx, "选择分类", SponsorCategory.entries.map { it.label }) { which ->
                    category = SponsorCategory.entries[which]
                    text = "分类：${category.label}"
                }
            }
        }
        val actionTv = TextView(ctx).apply {
            text = "动作：${action.label}"
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener {
                showOverlayList(ctx, "这段是什么行为", SponsorActionType.entries.map { it.label }) { which ->
                    action = SponsorActionType.entries[which]
                    text = "动作：${action.label}"
                }
            }
        }

        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAD), dp(8), dp(PAD), dp(8))
            addView(label(ctx, "开始时间（mm:ss.SSS 或秒）"))
            addView(startEt)
            addView(rowOf(ctx, button(ctx, "设为当前") {
                startEt.setText(SponsorTime.format(player.currentPosition.coerceAtLeast(0L)))
            }, button(ctx, "视频开头") { startEt.setText(SponsorTime.format(0L)) }))
            addView(label(ctx, "结束时间（mm:ss.SSS 或秒）"))
            addView(endEt)
            addView(rowOf(ctx, button(ctx, "设为当前") {
                endEt.setText(SponsorTime.format(player.currentPosition.coerceAtLeast(0L)))
            }, button(ctx, "视频结尾") {
                // 直接用毫秒原值：先换算成秒再乘回来会让 1001ms 变成 1000ms（浮点截断 1ms）
                if (player.duration > 0L) endEt.setText(SponsorTime.format(player.duration))
            }))
            addView(categoryTv)
            addView(actionTv)
            addView(TextView(ctx).apply {
                text = "提交会带上本机随机生成的匿名 ID（不含账号信息），服务端审核通过后所有人可见。"
                textSize = 12f
                setTextColor(Color.parseColor("#9E9E9E"))
                setPadding(0, dp(4), 0, 0)
            })
        }

        // ★ 取消/提交/试播做成弹窗**自己的按钮**，不用 AlertDialog 的系统按钮栏：
        //   系统按钮点了会**无条件关掉弹窗**，校验失败时用户会觉得"点了没反应/白填了"。
        var dialog: Dialog? = null
        var submitted = false
        // 提交中标志：防连点（协程跑在 Main，局部布尔就够；失败路径会恢复，成功会关弹窗）
        var submitting = false
        // 提交按钮的引用：pending 期间要置灰（声明在 footer 之前，点击回调里才能引用到它自己）
        var submitBtn: TextView? = null
        // 关弹窗（取消 / 点空白 / 返回键 / 试播）时留下草稿；提交成功那次不存（见 submitted）
        val saveDraft = {
            submitDraft = SubmitDraft(
                key = draftKey,
                start = startEt.text.toString(),
                end = endEt.text.toString(),
                category = category,
                action = action,
            )
        }
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), dp(PAD), dp(10))
            // 试播放最左：中间垫一条可伸缩空档，把"试播"顶到行首、取消/提交留在右侧。
            // （不用按钮自身的 weight 去撑：那样文字右边会多出一大片看不见的点击区）
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(button(ctx, "试播这段") {
                val start = SponsorTime.parseSec(startEt.text.toString())
                val end = SponsorTime.parseSec(endEt.text.toString())
                when {
                    start == null || end == null ->
                        toast(activity, "时间格式看不懂，用 mm:ss.SSS（如 01:30.500）或秒数")
                    end <= start ->
                        toast(activity, "结束时间要大于开始时间")
                    else -> {
                        // 先存草稿再关弹窗：用户看完试播再打开，输入框还是刚才填的那段
                        // （关弹窗本身也会存一次草稿，这里是幂等的，只为"先存"这个顺序）
                        saveDraft()
                        dialog?.dismiss()
                        startTrial(player, (start * 1000).toLong(), (end * 1000).toLong())
                    }
                }
            })
            addView(button(ctx, "取消") { dialog?.dismiss() })
            val submitView = button(ctx, "提交") {
                if (submitting) return@button
                val start = SponsorTime.parseSec(startEt.text.toString())
                val end = SponsorTime.parseSec(endEt.text.toString())
                // 本地校验（只加在提交路径；试播仍只校验格式 + end>start）：
                // 时长上限与"太短"都是服务端也会拒的，本地先拦一次省一趟往返
                val startMs = ((start ?: 0.0) * 1000).toLong()
                val endMs = ((end ?: 0.0) * 1000).toLong()
                val durationMs = player.duration
                when {
                    start == null || end == null ->
                        toast(activity, "时间格式看不懂，用 mm:ss.SSS（如 01:30.500）或秒数")
                    end <= start ->
                        toast(activity, "结束时间要大于开始时间")
                    durationMs > 0L && endMs > durationMs ->
                        toast(activity, "结束时间超出视频时长（${SponsorTime.format(durationMs)}）")
                    endMs - startMs < MIN_SUBMIT_SEGMENT_MS ->
                        toast(activity, "片段太短（不足 0.5 秒）")
                    else -> {
                        submitting = true
                        submitBtn?.isEnabled = false
                        submitBtn?.alpha = 0.5f
                        toast(activity, "正在提交…")
                        scope.launch {
                            // postSegments 是"全函数"：任何失败都落在 code 里（-1 = 网络）；只原样抛取消
                            val result = BiliApiService.sponsorBlockAPI.postSegments(
                                bvid = bvid,
                                cid = cid,
                                videoDurationSec = durationSec,
                                segments = listOf(
                                    SponsorBlockApi.PostSegment(
                                        segment = listOf(start, end),
                                        category = category.id,
                                        actionType = action.id,
                                    )
                                ),
                            )
                            if (result.code != 200) {
                                toast(activity, submitFailMessage(result.code))
                                // 失败：恢复可点（成功路径不需要，弹窗已经关了）
                                submitting = false
                                submitBtn?.isEnabled = true
                                submitBtn?.alpha = 1f
                                return@launch
                            }
                            // ★ 请求之后的这段（合并 / 兜底拉取 / 赋值 / 关弹窗）整段包住：
                            //   setter 万一抛异常，也不能让 Main 未捕获崩溃、更不能把按钮永久卡灰。
                            //   （不用 runCatching：它会把 CancellationException 一起吞掉，本仓明令不许。）
                            var mergedOk = false
                            try {
                                // ★A1：200 回给我们的就是**刚新建的片段**，并进本地列表 → 立刻生效。
                                val merged: List<SponsorSegment>? = if (result.created.isNotEmpty()) {
                                    mergeSponsorSegments(player.sponsorSegments, result.created)
                                } else {
                                    // 兜底：body 里没带片段就再拉一次。网络 + 解析放 IO（赋值仍在 Main）；
                                    // ★拉空（网络/解析失败都是空列表）就**保持现状**，绝不把已有片段与色块清空
                                    withContext(Dispatchers.IO) {
                                        BiliApiService.sponsorBlockAPI.getSegments(bvid, cid)
                                    }.takeIf { it.isNotEmpty() }
                                }
                                mergedOk = merged != null
                                // 上面两条路径（created 非空合并 / created 为空兜底拉取）都汇到这一处赋值。
                                // 走 mergeSubmittedSegments：带一次性抑制 —— 不越界补偿、也不重置"上一位置"，
                                // 连"本地列表本来是空（[] → [新片段]）"和"暂停在起点提交"都不会被自己的片段弹走。
                                merged?.let { player.mergeSubmittedSegments(it) }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // 请求已经成功（200），只是本地刷新这一步炸了：下面按"已提交，稍后刷新"告知
                                SponsorDiag.log("submit-merge", "${e.javaClass.simpleName}: ${e.message}")
                            } finally {
                                // 弹窗还开着（走了异常分支 / 还没走到 dismiss）才需要恢复可点
                                if (dialog?.isShowing == true) {
                                    submitting = false
                                    submitBtn?.isEnabled = true
                                    submitBtn?.alpha = 1f
                                }
                            }
                            // ★ 只要 HTTP 200，这次提交就算完成（本地列表只是"能不能立刻显示"）：
                            //   无论合并成没成都要关弹窗 + 作废草稿 —— 否则用户再点一次会拿到 409「已有人提交过」。
                            //   取消路径上面已原样抛出、走不到这里，弹窗与草稿保持原样。
                            // 提交成功：草稿作废（下次打开回到"当前进度"）；
                            // submitted 置位让 dismiss 收尾别再把它写回草稿
                            submitted = true
                            submitDraft = null
                            dialog?.dismiss()
                            // 合并成功才说"已加入"；"200 但没片段、兜底也空"或本地刷新失败时说"稍后刷新"
                            toast(
                                activity,
                                if (mergedOk) "提交成功，已加入本视频片段" else "已提交，列表稍后刷新"
                            )
                        }
                    }
                }
            }
            submitBtn = submitView
            addView(submitView)
        }
        val scroll = CappedScrollView(ctx, 0.55f).apply { addView(form) }

        // ★ 取消/提交（以及试播）放在**滚动区外面**的固定页脚：
        //   以前塞在 form 里，横屏时表单比屏幕还高 → 按钮被顶到可视区外，
        //   用户以为"点了没反应"，其实那位置根本没有按钮（截图里连取消都看不见）。
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // 水平内边距由 form / footer 各自负责，这里别再套一层（否则内容会缩两遍）
            setPadding(0, 0, 0, 0)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        // 提交弹窗也走全屏覆盖层（卡片里：标题 + 可滚动表单 + 固定页脚按钮）
        dialog = showOverlay(
            activity,
            cardOf(activity, "提交片段到空降助手", root, closeLabel = null) { dialog?.dismiss() },
            onDismissExtra = {
                ClickGuard.leave(KEY_SUBMIT)
                // ★ 草稿挂在已有的 onDismissExtra 上，不动 OverlayDialog 自己的 onDismiss
                //   （那里面还有"出栈"，覆盖了就破坏弹窗栈语义）
                if (!submitted) saveDraft()
            },
        )
    }

    /**
     * 把服务端刚回的新片段并进本地列表：**按 UUID 去重**（已在列表里的以本地那份为准）、
     * 丢掉无效片段、按起点升序 —— 色块/列表/跳过引擎都假设"有序 + 无重复 + 有效"。
     */
    private fun mergeSponsorSegments(
        current: List<SponsorSegment>,
        created: List<SponsorSegment>,
    ): List<SponsorSegment> = (current + created)
        .filter { it.isValid }
        .distinctBy { it.UUID }
        .sortedBy { it.startMs }

    /**
     * 提交失败时按服务端状态码给准确提示（比"可能重复提交/太短/被限流"这种一锅炖强）。
     * 语义照服务端文档：400 参数错 / 403 被自动审核拒 / 409 重复 / 429 太频繁；-1 是我们自己的网络异常。
     */
    private fun submitFailMessage(code: Int): String = when (code) {
        409 -> "这段已经有人提交过了"
        403 -> "被服务端自动审核拒绝"
        429 -> "提交太快，过一会儿再试"
        400 -> "时间或片段不合法，被服务端拒了"
        -1 -> "网络异常，没提交上去"
        else -> "提交失败：可能重复提交、片段太短或被限流"
    }

    // ───────────────────────── 试播这段 ─────────────────────────

    /** 正在等待"到点暂停"的试播任务：同一时刻最多一个（连点两次先取消旧的），[dismissAll] 也会取消 */
    private var trialJob: Job? = null

    private const val TRIAL_POLL_MS = 50L

    /**
     * 1000ms 容差，两个用途都跟播放器自己的判据一致：
     *  - 位置离 seek 目标 1s 以内 = seek 已经落地（`DanmakuVideoPlayer` 判 `lastSeekTargetMs` 退役用的是同一个 1s）；
     *  - 位置掉回起点之前 1s 以上 = 用户自己跳走了，不是 seek 抖动。
     */
    private const val TRIAL_TOL_MS = 1_000L

    /** seek 迟迟不落地（底层卡住）就别一直挂着轮询 */
    private const val TRIAL_ARM_TIMEOUT_MS = 5_000L

    /**
     * 起播预算：seek 落地后位置**一直不动**的累计时间超过它才放弃。
     * 起播前缓冲**不计入**停滞看门狗（刚 seekTo 完位置就贴着 start，但画面还在缓冲 —— 那不是"播不动"）；
     * 只有"位置不动"才吃这份预算：慢慢起播（位置已经在往前挪）不算超时，也就不会被误杀。
     */
    private const val TRIAL_START_TIMEOUT_MS = 8_000L

    /** 起播**之后**位置连续这么久没前进 = 播不动了（用户暂停 / 缓冲 / 已播完 / 位置钉死）→ 干净退出 */
    private const val TRIAL_STALL_MS = 3_000L

    /**
     * 到点暂停重试预算：第 1 次在 0ms、第 61 次在 3000ms ⇒ 正好覆盖"到点那一帧起缓冲整整 3.0s"。
     * （GSY 缓冲态下 `onVideoPause()` 是空操作，所以要重试；60 次只够到 2950ms，差最后一拍。）
     */
    private const val TRIAL_PAUSE_RETRY_MAX = 61

    private fun cancelTrial() {
        trialJob?.cancel()
        trialJob = null
    }

    /**
     * 试播 `[startMs, endMs]` **一遍**：seek 到起点 → 需要时开始播放 → 播到终点**暂停**（绝不循环）。
     *
     * 为什么用 50ms 轮询而不是给播放器挂进度回调：本仓播放器的进度回调是进度条/弹幕层在用的，
     * 再挂一个"到点暂停"要动播放器内部状态（越界，且容易和连播/续播记账打架）；
     * 轮询只读 public 的 `currentPosition`，下面五条出口都会干净退出，不留任何回调或协程。
     *
     * 五条出口（判定顺序即下面代码的顺序，都不会误暂停别人的播放，且**每条都有界**）：
     *  1. 位置 >= endMs → `onVideoPause()`；GSY 只在 `isPlaying()` 为真时才真的暂停
     *     （本仓 v13.2.1），刚越过终点那一帧若在缓冲就是空操作 ⇒ 核对 `currentState` 没停就
     *     在后续轮询重试，上限 [TRIAL_PAUSE_RETRY_MAX] 次（≈3s），确认停了才退出
     *     （"到点"排在"等起播"之前：短片段会在起播判据成立之前先越过终点）；
     *  2. 位置掉回 start 之前 1s 以上 → 用户自己跳走了，放弃；
     *  3. seek 落地后位置一直不动累计超过 [TRIAL_START_TIMEOUT_MS]（起播预算）→ 放弃；
     *  4. 起播后位置连续 [TRIAL_STALL_MS] 没前进（用户暂停 / 缓冲 / 已播完 / 位置钉死）→ 放弃；
     *  5. seek [TRIAL_ARM_TIMEOUT_MS] 还没落地 → 放弃，不留死循环。
     *
     * ★ 为什么"等起播"要单独一段：`seekTo` 之后位置会**立刻**贴着 start 报出来，但画面可能还在缓冲
     *   （位置贴着 start ≠ 已经在播）—— 一落地就武装停滞看门狗的话，弱网下起播缓冲 ≥3s 就会被误判
     *   "播不动了"，试播静默放弃（弹窗已关、视频自己播下去、到终点不停）。所以看门狗只在位置真的
     *   前进过（`pos > start + TRIAL_TOL_MS`）之后才武装。
     *
     * ★ `seekTo` 是异步的（`GSYVideoBaseManager.getCurrentPosition()` 直接转发底层播放器，
     *   seek 完成前可能还报旧位置），所以要先等位置落到起点附近，再判"到点/跳走" ——
     *   否则用户从片段后面点试播会被旧位置立刻误暂停。
     */
    private fun startTrial(player: DanmakuVideoPlayer, startMs: Long, endMs: Long) {
        cancelTrial()
        player.seekTo(startMs)
        if (player.currentState != GSYVideoPlayer.CURRENT_STATE_PLAYING) {
            player.onVideoResume()
        }
        trialJob = scope.launch {
            var seekLanded = false
            var waitedMs = 0L
            var started = false
            var waitStartMs = 0L
            var lastPos = -1L
            var stalledMs = 0L
            var pauseTries = 0
            while (true) {
                delay(TRIAL_POLL_MS)
                waitedMs += TRIAL_POLL_MS
                val pos = player.currentPosition
                if (!seekLanded) {
                    if (kotlin.math.abs(pos - startMs) > TRIAL_TOL_MS) {
                        if (waitedMs >= TRIAL_ARM_TIMEOUT_MS) break
                        continue
                    }
                    seekLanded = true
                }
                if (pos >= endMs) {
                    player.onVideoPause()
                    // 空操作（缓冲态）不算数：位置还在动/还会再越过终点，下一轮接着试；有上限
                    if (player.currentState == GSYVideoPlayer.CURRENT_STATE_PAUSE) break
                    if (++pauseTries >= TRIAL_PAUSE_RETRY_MAX) break
                    continue
                }
                if (pos < startMs - TRIAL_TOL_MS) break
                if (!started) {
                    if (pos > startMs + TRIAL_TOL_MS) {
                        started = true
                    } else {
                        // 起播阶段：只有位置不动才吃起播预算（慢慢起播不算超时）
                        if (pos <= lastPos) waitStartMs += TRIAL_POLL_MS
                        lastPos = pos
                        if (waitStartMs > TRIAL_START_TIMEOUT_MS) break
                        continue
                    }
                }
                // 停滞看门狗（起播后才武装）：位置不再前进 = 播不动了（暂停/缓冲/已播完/钉死），干净退出。
                // 放在"到点"之后：停在终点附近时先试一次暂停，再让看门狗兜底。
                if (pos <= lastPos) stalledMs += TRIAL_POLL_MS else stalledMs = 0L
                lastPos = pos
                if (stalledMs >= TRIAL_STALL_MS) break
            }
            // 跑完就把引用放掉（顺带松开对 player/Activity 的强引用）；被 cancel 时走不到这里，
            // 但 cancelTrial() 已经置空了，两条路都不会留下活着的轮询。
            trialJob = null
        }
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private fun dot(ctx: Context, color: Int): View {
        val size = (10 * ctx.resources.displayMetrics.density).toInt()
        return View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    private fun label(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#9E9E9E"))
        setPadding(0, (8 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun rowOf(ctx: Context, vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun button(ctx: Context, text: String, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.parseColor("#2196F3"))
        setPadding(0, (6 * ctx.resources.displayMetrics.density).toInt(),
            (18 * ctx.resources.displayMetrics.density).toInt(),
            (6 * ctx.resources.displayMetrics.density).toInt())
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun toast(context: Context, msg: String) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // 界面已销毁：忽略
        }
    }
}
