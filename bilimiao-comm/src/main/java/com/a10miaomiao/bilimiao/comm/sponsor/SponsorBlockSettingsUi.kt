package com.a10miaomiao.bilimiao.comm.sponsor

import android.content.Context
import android.app.Dialog
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
 */
object SponsorBlockSettingsUi {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private const val PAD = 16

    // ───────────────────────── 服务端状态 / 统计 ─────────────────────────

    // ───────────────────────── 服务端状态 / 统计 ─────────────────────────

    fun showStats(context: Context) {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val text = TextView(context).apply {
            text = "查询中…"
            textSize = 14f
            setPadding(0, 0, 0, dp(4))
        }
        var dlg: Dialog? = null
        dlg = OverlayDialog.show(context, cardOf(context, "空降助手状态", text) { dlg?.dismiss() })
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
     */
    fun showUsernameDialog(context: Context) {
        scope.launch {
            val publicId = withContext(Dispatchers.IO) { SponsorBlockApi.publicUserId() }
            val current = withContext(Dispatchers.IO) { SponsorBlockApi().getUsername() }
            val shown = if (current.isNullOrBlank() || current == publicId) "" else current

            val density = context.resources.displayMetrics.density
            val dp = { v: Int -> (v * density).toInt() }
            val input = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "留空 = 不用昵称（排行榜显示公开ID）"
                setText(shown)
                setSelection(text?.length ?: 0)
            }
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(input)
                addView(TextView(context).apply {
                    text = "排行榜和统计里显示的名字，支持中文。\n你的公开ID：$publicId\n" +
                        "（私人ID 相当于密码，别外发；改私人ID = 换一个身份，昵称也得重设）"
                    textSize = 12f
                    setPadding(0, dp(8), 0, 0)
                })
            }
            var dlg: Dialog? = null
            dlg = OverlayDialog.show(
                context,
                cardOf(context, "公开昵称", box, positive = "保存" to {
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
                })
            )
        }
    }

    // ───────────────────────── 用户ID（对齐 PiliPlus 的「用户ID」项）─────────────────────────

    /**
     * 查看 / 编辑 / 重掷本机匿名 userID。
     *
     * 私人 ID 是鉴权用的"密码"，换一个 = 在服务端眼里变成另一个人（投票/提交记录、统计都从零开始）。
     */
    fun showUserIdDialog(context: Context) {
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
        dlg = OverlayDialog.show(
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
            )
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
            SponsorCategory.entries.forEach { category ->
                val color = current[category.id] ?: category.color
                box.addView(
                    simpleRow(context, dot(context, color), category.label) {
                        showPalette(context, category)
                    }
                )
            }
            var dlg: Dialog? = null
            dlg = OverlayDialog.show(
                context,
                cardOf(context, "进度条片段颜色", ScrollView(context).apply { addView(box) }) { dlg?.dismiss() }
            )
        }
    }

    private fun showPalette(context: Context, category: SponsorCategory) {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
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
                }
            })
        }
        var dlg: Dialog? = null
        dlg = OverlayDialog.show(
            context,
            cardOf(context, "${category.label} · 选择颜色", ScrollView(context).apply { addView(box) }) { dlg?.dismiss() }
        )
    }

    // ───────────────────────── 自定义服务端 ─────────────────────────

    fun showServerDialog(context: Context) {
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
            dlg = OverlayDialog.show(
                context,
                cardOf(
                    context, "自定义服务端", box,
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
                )
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
