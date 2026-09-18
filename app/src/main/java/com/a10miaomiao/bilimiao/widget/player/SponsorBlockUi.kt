package com.a10miaomiao.bilimiao.widget.player

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import com.a10miaomiao.bilimiao.comm.utils.OverlayDialog
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorActionType
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorCategory
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorSegment
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorSkipType
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.shuyu.gsyvideoplayer.utils.CommonUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
     * 当前正在显示的弹窗（同一时刻只会有一个）。
     *
     * 为什么要留引用：这些弹窗是挂在 Activity 上的，如果 Activity 先销毁、弹窗还在，
     * 就会 WindowLeaked（本项目其它地方已经有 `dismissCachedDialogs()` 在防这个）。
     * 播放器销毁时由 `PlayerDelegate2.onDestroy()` 调 [dismissAll] 统一收掉。
     */
    private var currentDialog: Dialog? = null

    /** Activity 销毁时调用：关掉残留弹窗。 */
    fun dismissAll() {
        runCatching { currentDialog?.dismiss() }
        currentDialog = null
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
        OverlayDialog.show(activity, cardOf(activity, "空降助手片段（${segments.size}）", scroll) {
            currentDialog?.dismiss()
        }).also { currentDialog = it }
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
                        append(CommonUtil.stringForTime(seg.startMs))
                        append(" - ")
                        append(CommonUtil.stringForTime(seg.endMs))
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
        OverlayDialog.showList(
            activity,
            "${SponsorCategory.labelOf(seg.category)} · ${CommonUtil.stringForTime(seg.startMs)}",
            items,
            onPick = { which ->
                currentDialog?.dismiss()
                when (which) {
                    0 -> vote(activity, seg, type = 1, category = null)
                    1 -> vote(activity, seg, type = 0, category = null)
                    else -> showCategoryPicker(activity, seg)
                }
            },
        ).also { currentDialog = it }
    }

    private fun showCategoryPicker(activity: Activity, seg: SponsorSegment) {
        val categories = SponsorCategory.entries
        OverlayDialog.showList(
            activity, "改成哪个类别？", categories.map { it.label },
            onPick = { which ->
                currentDialog?.dismiss()
                vote(activity, seg, type = null, category = categories[which].id)
            },
        ).also { currentDialog = it }
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
     * 提交新片段。
     *
     * 表单：开始 / 结束（可手输 `mm:ss` 或秒）+「设为当前」+ 分类 + 动作。
     * 默认区间是"当前时间点"（和 PiliPlus 的快捷提交一致：先设为当前，再手动调两端）。
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
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val nowMs = player.currentPosition.coerceAtLeast(0L)
        val durationSec = (player.duration / 1000.0).takeIf { it > 0 } ?: 0.0

        var category = SponsorCategory.Sponsor
        var action = SponsorActionType.Skip

        val startEt = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(CommonUtil.stringForTime(nowMs))
        }
        val endEt = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(CommonUtil.stringForTime(nowMs))
        }
        val categoryTv = TextView(ctx).apply {
            text = "分类：${category.label}"
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener {
                OverlayDialog.showList(
                    ctx, "选择分类", SponsorCategory.entries.map { it.label },
                    onPick = { which ->
                        category = SponsorCategory.entries[which]
                        text = "分类：${category.label}"
                    },
                )
            }
        }
        val actionTv = TextView(ctx).apply {
            text = "动作：${action.label}"
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener {
                OverlayDialog.showList(
                    ctx, "这段是什么行为", SponsorActionType.entries.map { it.label },
                    onPick = { which ->
                        action = SponsorActionType.entries[which]
                        text = "动作：${action.label}"
                    },
                )
            }
        }

        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAD), dp(8), dp(PAD), dp(8))
            addView(label(ctx, "开始时间（mm:ss 或秒）"))
            addView(startEt)
            addView(rowOf(ctx, button(ctx, "设为当前") {
                startEt.setText(CommonUtil.stringForTime(player.currentPosition.coerceAtLeast(0L)))
            }, button(ctx, "视频开头") { startEt.setText("00:00") }))
            addView(label(ctx, "结束时间（mm:ss 或秒）"))
            addView(endEt)
            addView(rowOf(ctx, button(ctx, "设为当前") {
                endEt.setText(CommonUtil.stringForTime(player.currentPosition.coerceAtLeast(0L)))
            }, button(ctx, "视频结尾") {
                if (durationSec > 0) endEt.setText(CommonUtil.stringForTime((durationSec * 1000).toLong()))
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

        // ★ 取消/提交做成弹窗**自己的按钮**，不用 AlertDialog 的系统按钮栏：
        //   系统按钮点了会**无条件关掉弹窗**，校验失败时用户会觉得"点了没反应/白填了"。
        var dialog: Dialog? = null
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), dp(PAD), dp(10))
            addView(button(ctx, "取消") { dialog?.dismiss() })
            addView(button(ctx, "提交") {
                val start = parseTimeSec(startEt.text.toString())
                val end = parseTimeSec(endEt.text.toString())
                when {
                    start == null || end == null ->
                        toast(activity, "时间格式看不懂，用 mm:ss（如 01:30）或秒数")
                    end <= start ->
                        toast(activity, "结束时间要大于开始时间")
                    else -> {
                        toast(activity, "正在提交…")
                        scope.launch {
                            val ok = try {
                                BiliApiService.sponsorBlockAPI.postSegments(
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
                            } catch (e: Exception) {
                                false
                            }
                            toast(
                                activity,
                                if (ok) "提交成功，感谢你让社区更好用"
                                else "提交失败：可能重复提交、片段太短或被限流"
                            )
                            if (ok) dialog?.dismiss()
                        }
                    }
                }
            })
        }
        val scroll = CappedScrollView(ctx, 0.55f).apply { addView(form) }

        // ★ 取消/提交放在**滚动区外面**的固定页脚：
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
        dialog = OverlayDialog.show(
            activity,
            cardOf(activity, "提交片段到空降助手", root, closeLabel = null) { dialog?.dismiss() },
        )
        currentDialog = dialog
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

    /**
     * 解析 `mm:ss` / `hh:mm:ss` / 纯秒数 → 秒；**解析失败或不是合法时间返回 null**。
     *
     * 校验要点（以前只挡住了"结束 <= 开始"，`-5` 和 `1:2:3:4` 都能溜进去）：
     *  - 最多 3 段（时:分:秒），每段都必须是数字；
     *  - 不允许负数、NaN、无穷大；
     *  - 分/秒必须在 0..59（纯秒数那种没有这个限制）。
     */
    fun parseTimeSec(raw: String): Double? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return try {
            val sec = if (":" in text) {
                val parts = text.split(":")
                if (parts.size > 3) return null
                if (parts.any { it.isBlank() || it.trim().toDoubleOrNull() == null }) return null
                val nums = parts.map { it.trim().toDouble() }
                if (nums.any { it < 0 || it.isNaN() || it.isInfinite() }) return null
                // 除最高位（小时）外，分和秒都必须在 0..59
                if (nums.drop(1).any { it >= 60 }) return null
                nums.fold(0.0) { acc, v -> acc * 60 + v }
            } else {
                val v = text.toDoubleOrNull() ?: return null
                if (v < 0 || v.isNaN() || v.isInfinite()) return null
                v
            }
            if (sec < 0 || sec.isNaN() || sec.isInfinite()) null else sec
        } catch (e: Exception) {
            null
        }
    }
}
