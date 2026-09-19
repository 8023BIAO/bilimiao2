package com.a10miaomiao.bilimiao.comm.sponsor

import android.content.Context
import android.app.Dialog
import com.a10miaomiao.bilimiao.comm.utils.ClickGuard
import com.a10miaomiao.bilimiao.comm.utils.OverlayDialog
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT

import android.view.ViewGroup.LayoutParams.WRAP_CONTENT

import android.view.ViewGroup
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.a10miaomiao.bilimiao.comm.apis.SponsorBlockApi
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 空降助手的**设置页弹窗**：颜色自定义、服务端状态/统计、自定义服务端。
 *
 * 为什么放在 bilimiao-comm 而不是 app 模块：设置页在 `bilimiao-compose` 里，
 * 而依赖方向是 app → compose（compose 看不到 app 的类）。这三个弹窗只依赖设置存储和 API，
 * 不需要播放器实例，所以放 comm 层最合适；需要播放器的那几个（片段列表/投票/提交）留在 app 模块。
 *
 * ★ 交互契约（2026-09-19 修）：
 *  - 每个弹窗入口都过 [ClickGuard] 的**独占闸门**：连点 N 次只会有一个弹窗，
 *    弹窗关掉（onDismiss）才释放 —— 以前「公开昵称」连点 N 次会排队弹 N 个。
 *  - 「公开昵称」以前要等本机算公开ID（SHA256×5000）+ 一次网络取昵称**都完成**才显示，
 *    点下去像卡住；现在**先立刻显示弹窗**（正文写"读取中…"、输入框禁用），数据回来再回填。
 *  - 所有弹窗登记进 [dialogStack]，页面销毁时 [dismissAll] 统一收口（防 WindowLeaked）。
 */
object SponsorBlockSettingsUi {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private const val PAD = 16

    // ── 防连点闸门 key（一个入口一个，互不干扰）──
    private const val KEY_STATS = "sponsor_ui:stats"
    private const val KEY_USERNAME = "sponsor_ui:username"
    private const val KEY_USER_ID = "sponsor_ui:user_id"
    private const val KEY_SERVER = "sponsor_ui:server"
    private const val KEY_COLORS = "sponsor_ui:colors"
    private const val KEY_PALETTE = "sponsor_ui:palette"

    /**
     * 正在显示的弹窗（本项目设置页这几个弹窗可以叠第二层：颜色列表 → 调色板）。
     * 页面销毁时由 [dismissAll] 统一关掉。
     */
    private val dialogStack = mutableListOf<Dialog>()

    /**
     * 显示一个覆盖层弹窗并登记进栈；[onDismissExtra] 用来挂"释放闸门"这类收尾动作。
     * 注意：入栈/出栈走 `OverlayDialog.show` 的 `onDismiss` 形参，
     * **不要**在返回的 Dialog 上再 setOnDismissListener（会覆盖它自己的监听器 → 监听器泄漏）。
     */
    private fun showOverlay(
        context: Context,
        card: View,
        onDismissExtra: (() -> Unit)? = null,
    ): Dialog {
        var dlg: Dialog? = null
        dlg = OverlayDialog.show(context, card, onDismiss = {
            dialogStack.remove(dlg)
            onDismissExtra?.invoke()
        })
        dialogStack.add(dlg)
        return dlg
    }

    /** 设置页销毁/离开时收口：关掉所有还开着的弹窗 */
    fun dismissAll() {
        dialogStack.toList().forEach { runCatching { it.dismiss() } }
        dialogStack.clear()
    }

    // ───────────────────────── 服务端状态 / 统计 ─────────────────────────

    fun showStats(context: Context) {
        if (!ClickGuard.enter(KEY_STATS)) return
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val text = TextView(context).apply {
            text = "查询中…"
            textSize = 14f
            setPadding(0, 0, 0, dp(4))
        }
        var dlg: Dialog? = null
        dlg = showOverlay(
            context,
            cardOf(context, "空降助手状态", text) { dlg?.dismiss() },
            onDismissExtra = { ClickGuard.leave(KEY_STATS) },
        )
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val api = SponsorBlockApi()
                    val uptime = api.uptimeSeconds()
                    val info = api.userInfo()
                    buildString {
                        if (uptime != null) {
                            append("服务端：在线（已运行 ${formatUptime(uptime)}）")
                        } else {
                            append("服务端：暂时不可达")
                        }
                        append("\n服务端地址：${SponsorBlockApi.baseUrl}")
                        // 私人 ID 相当于密码，这里只露头尾；对外可见的是公开 ID
                        val priv = SponsorBlockApi.localUserId()
                        val masked = if (priv.length > 12) priv.take(6) + "…" + priv.takeLast(4) else priv
                        append("\n私人ID（密码，别外发）：$masked")
                        append("\n公开ID（可公开）：${SponsorBlockApi.publicUserId()}")
                        val name = SponsorBlockApi().getUsername()
                        val shown = if (name.isNullOrBlank() || name == SponsorBlockApi.publicUserId()) "未设置" else name
                        append("\n昵称：$shown")
                        if (info != null) {
                            append("\n\n被跳过的片段：${info.viewCount} 次")
                            append("\n累计节省时间：${"%.1f".format(info.minutesSaved)} 分钟")
                            append("\n你提交的片段：${info.segmentCount} 条")
                        } else {
                            append("\n\n统计信息取不到（服务端限流或网络问题；统计数据是按上面的用户ID 记的）")
                        }
                    }
                } catch (e: Exception) {
                    "查询失败：${e.message}"
                }
            }
            text.text = result
        }
    }

    /** 把秒数说成人话：`105614` → "1 天 5 小时" */
    private fun formatUptime(seconds: Double): String {
        val total = seconds.toLong()
        val d = total / 86400
        val h = (total % 86400) / 3600
        val m = (total % 3600) / 60
        return when {
            d > 0 -> "$d 天 $h 小时"
            h > 0 -> "$h 小时 $m 分"
            else -> "$m 分"
        }
    }

    // ───────────────────────── 公开昵称 ─────────────────────────

    /**
     * 设置**公开昵称**：排行榜 / 统计里显示的名字（支持中文）。
     *
     * 背景（官方 API 文档「用户ID」一节）：私人 ID 相当于密码、不该外发；服务端对外只认
     * **公开 ID**（私人 ID 做 SHA256 五千次）和**用户名**；没设用户名时排行榜就显示公开 ID 那一长串。
     *
     * ★ 为什么要重写（用户报"点一下很卡、连点 N 次弹 N 个"）：
     *  - 以前是"两次读取（算公开ID + 取昵称网络请求）都完成后才 show()"，中间那段没有任何反馈，
     *    点下去像卡死 → 现在**立刻显示弹窗**，输入框先禁用、正文写"读取中…"，数据回来再回填。
     *  - 以前没有任何防重入，连点几次就排队弹几个 → 现在由 [ClickGuard] 独占闸门挡住，
     *    弹窗关闭（onDismiss）才释放。
     */
    fun showUsernameDialog(context: Context) {
        if (!ClickGuard.enter(KEY_USERNAME)) return
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        var loaded = false
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "留空 = 不用昵称（排行榜显示公开ID）"
            isEnabled = false
        }
        val tip = TextView(context).apply {
            text = "读取中…（正在本机计算公开ID并读取当前昵称）"
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(tip)
        }
        var dlg: Dialog? = null
        dlg = showOverlay(
            context,
            cardOf(
                context, "公开昵称", box,
                neutral = "取消" to { dlg?.dismiss() },
                positive = "保存" to {
                    // 数据还没回来时输入框是禁用+空值：这时按"保存"会把昵称清掉，必须挡住
                    if (!loaded) {
                        toast(context, "还在读取昵称，稍等一下再保存")
                    } else {
                        val value = input.text?.toString()?.trim().orEmpty()
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { SponsorBlockApi().setUsername(value) }
                            toast(
                                context,
                                when {
                                    !ok -> "保存失败：网络异常或服务端拒绝"
                                    value.isEmpty() -> "已清除昵称（排行榜将显示公开ID）"
                                    else -> "已保存：$value"
                                }
                            )
                            dlg?.dismiss()
                        }
                    }
                },
            ),
            onDismissExtra = { ClickGuard.leave(KEY_USERNAME) },
        )
        scope.launch {
            val publicId = withContext(Dispatchers.IO) { SponsorBlockApi.publicUserId() }
            val current = withContext(Dispatchers.IO) {
                runCatching { SponsorBlockApi().getUsername() }.getOrNull()
            }
            // 用户可能在读取期间已经关掉弹窗
            if (dlg?.isShowing != true) return@launch
            val shown = if (current.isNullOrBlank() || current == publicId) "" else current
            input.setText(shown)
            input.setSelection(input.text?.length ?: 0)
            input.isEnabled = true
            loaded = true
            tip.text = buildString {
                append("排行榜和统计里显示的名字，支持中文。\n你的公开ID：")
                append(publicId)
                append("\n（私人ID 相当于密码，别外发；改私人ID = 换一个身份，昵称也得重设）")
                if (current != null && current.isNotBlank() && current != publicId) {
                    append("\n当前昵称已填在上面的输入框里，可直接改。")
                }
            }
        }
    }

    // ───────────────────────── 用户ID（对齐 PiliPlus 的「用户ID」项）─────────────────────────

    /**
     * 查看 / 编辑 / 重掷本机匿名 userID。
     *
     * 私人 ID 是鉴权用的"密码"，换一个 = 在服务端眼里变成另一个人（投票/提交记录、统计都从零开始）。
     * 本项只读本机 SharedPreferences（无网络），本来就秒开；这里补的是**防连点**（连点 N 次弹 N 个）。
     */
    fun showUserIdDialog(context: Context) {
        if (!ClickGuard.enter(KEY_USER_ID)) return
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(SponsorBlockApi.localUserId())
            setSelection(text?.length ?: 0)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(TextView(context).apply {
                text = "这是**私人ID（相当于密码）**：至少 30 个字符、只能字母和数字，别外发。\n" +
                    "换 ID = 换一个身份：投票/提交记录、统计、昵称都会从零开始。\n" +
                    "想在排行榜显示名字，请用上面的「公开昵称」。"
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
            })
        }
        var dlg: Dialog? = null
        dlg = showOverlay(
            context,
            cardOf(
                context, "私人ID", box,
                neutral = "随机" to {
                    val id = SponsorBlockApi.resetUserId()
                    toast(context, "已随机生成新 ID：${id.take(12)}…")
                    dlg?.dismiss()
                },
                positive = "确定" to {
                    val value = input.text?.toString()?.trim().orEmpty()
                    if (SponsorBlockApi.setUserId(value)) {
                        toast(context, "已保存")
                        dlg?.dismiss()
                    } else {
                        toast(context, "保存失败：至少 30 个字符、只能字母和数字")
                    }
                },
            ),
            onDismissExtra = { ClickGuard.leave(KEY_USER_ID) },
        )
    }

    /** 「关于空降助手」的仓库地址 */
    const val ABOUT_URL = "https://github.com/hanydd/BilibiliSponsorBlock"

    // ───────────────────────── 颜色自定义 ─────────────────────────

    private val PALETTE = intArrayOf(
        0xFF00D400.toInt(), 0xFFFFFF00.toInt(), 0xFFFF9900.toInt(), 0xFFFF1684.toInt(),
        0xFFCC00FF.toInt(), 0xFF7300FF.toInt(), 0xFF0202ED.toInt(), 0xFF008FD6.toInt(),
        0xFF00FFFF.toInt(), 0xFF9E9E9E.toInt(),
    )

    fun showColors(context: Context) {
        if (!ClickGuard.enter(KEY_COLORS)) return
        scope.launch {
            var current: Map<String, Int> = emptyMap()
            try {
                SettingPreferences.getData(context) { prefs ->
                    current = SponsorCategory.entries.mapNotNull { category ->
                        prefs[SettingPreferences.sponsorBlockColorKey(category.id)]
                            ?.let { category.id to it }
                    }.toMap()
                }
            } catch (e: Exception) {
                // 读不到就用默认色
            }
            val density = context.resources.displayMetrics.density
            val dp = { v: Int -> (v * density).toInt() }
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            // 记住每行的色点，选完颜色就地更新（否则列表里的圆点还是旧色，看着像没生效）
            val dots = HashMap<String, View>()
            SponsorCategory.entries.forEach { category ->
                val color = current[category.id] ?: category.color
                val dotView = dot(context, color)
                dots[category.id] = dotView
                box.addView(
                    simpleRow(context, dotView, category.label) {
                        showPalette(context, category) { newColor ->
                            (dots[category.id]?.background as? GradientDrawable)?.setColor(newColor)
                            dots[category.id]?.invalidate()
                        }
                    }
                )
            }
            var dlg: Dialog? = null
            dlg = showOverlay(
                context,
                cardOf(context, "进度条片段颜色", ScrollView(context).apply { addView(box) }) { dlg?.dismiss() },
                onDismissExtra = { ClickGuard.leave(KEY_COLORS) },
            )
        }
    }

    /**
     * 调色板。
     *
     * ★ 修：以前点颜色只写设置 + toast，**弹窗不关**（要按返回键才消失，和播放器里
     * 「提交片段」的二级菜单是同一个毛病）。现在选完立即 dismiss 自己，
     * 并通过 [onPicked] 通知调用方就地刷新父列表的色点（父列表留在原地，不用重开、不叠层）。
     */
    private fun showPalette(context: Context, category: SponsorCategory, onPicked: (Int) -> Unit) {
        if (!ClickGuard.enter(KEY_PALETTE)) return
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        var dlg: Dialog? = null
        PALETTE.forEach { color ->
            box.addView(simpleRow(context, dot(context, color), "#%06X".format(0xFFFFFF and color)) {
                scope.launch {
                    try {
                        SettingPreferences.edit(context) {
                            it[SettingPreferences.sponsorBlockColorKey(category.id)] = color
                        }
                        toast(context, "已更新「${category.label}」的颜色")
                    } catch (e: Exception) {
                        toast(context, "保存失败：${e.message}")
                    }
                    onPicked(color)
                    dlg?.dismiss()
                }
            })
        }
        dlg = showOverlay(
            context,
            cardOf(context, "${category.label} · 选择颜色", ScrollView(context).apply { addView(box) }) {
                dlg?.dismiss()
            },
            onDismissExtra = { ClickGuard.leave(KEY_PALETTE) },
        )
    }

    // ───────────────────────── 自定义服务端 ─────────────────────────

    fun showServerDialog(context: Context) {
        if (!ClickGuard.enter(KEY_SERVER)) return
        scope.launch {
            var current = ""
            try {
                SettingPreferences.getData(context) { prefs ->
                    current = prefs[SettingPreferences.SponsorBlockServer].orEmpty()
                }
            } catch (e: Exception) {
                // 读不到当空
            }
            val input = EditText(context).apply {
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                hint = SponsorBlockApi.BASE_URL
                setText(current)
            }
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(input)
                addView(TextView(context).apply {
                    text = "留空使用官方 ${SponsorBlockApi.BASE_URL}（可填镜像站）"
                    textSize = 12f
                    setPadding(0, 8, 0, 0)
                })
            }
            var dlg: Dialog? = null
            dlg = showOverlay(
                context,
                cardOf(
                    context, "自定义服务端", box,
                    neutral = "取消" to { dlg?.dismiss() },
                    positive = "保存" to {
                        val value = input.text?.toString()?.trim().orEmpty()
                        scope.launch {
                            try {
                                SettingPreferences.edit(context) {
                                    it[SettingPreferences.SponsorBlockServer] = value
                                }
                                toast(context, if (value.isBlank()) "已恢复默认服务端" else "已保存：$value")
                            } catch (e: Exception) {
                                toast(context, "保存失败：${e.message}")
                            }
                            dlg?.dismiss()
                        }
                    },
                ),
                onDismissExtra = { ClickGuard.leave(KEY_SERVER) },
            )
        }
    }

    // ───────────────────────── 覆盖层卡片 ─────────────────────────

    /**
     * 覆盖层弹窗的卡片内容：标题 + 内容 + 底部按钮行。
     *
     * 为什么所有弹窗都走 [OverlayDialog]（全屏覆盖层）而不是 `AlertDialog`：
     * 系统小弹窗在"旋转不重建 Activity"的 App 里会出现**窗口几何不更新**，
     * 表现就是"看到的和点到的不是一个地方"（首页筛选弹层用同一套机制，怎么转都准）。
     */
    private fun cardOf(
        context: Context,
        title: String,
        content: View,
        neutral: Pair<String, () -> Unit>? = null,
        positive: Pair<String, () -> Unit>? = null,
        onClose: (() -> Unit)? = null,
    ): View {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAD), dp(14), dp(PAD), dp(10))
            addView(TextView(context).apply {
                text = title
                textSize = 17f
                setPadding(0, 0, 0, dp(8))
            })
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(10), 0, 0)
                neutral?.let { (label, action) -> addView(textButton(context, label, action)) }
                positive?.let { (label, action) -> addView(textButton(context, label, action)) }
                if (positive == null && neutral == null) {
                    addView(textButton(context, "关闭") { onClose?.invoke() })
                }
            }
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun textButton(context: Context, label: String, action: () -> Unit): View =
        TextView(context).apply {
            text = label
            textSize = 15f
            setPadding((12 * context.resources.displayMetrics.density).toInt(), 0, 0, 0)
            isClickable = true
            setOnClickListener { action() }
        }

    private fun dot(context: Context, color: Int): View {
        val size = (10 * context.resources.displayMetrics.density).toInt()
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    private fun simpleRow(context: Context, dotView: View, label: String, onClick: () -> Unit): View {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener { onClick() }
            addView(dotView)
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                setPadding(dp(10), 0, 0, 0)
            })
        }
    }

    private fun toast(context: Context, msg: String) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // 界面已销毁
        }
    }
}
