package com.a10miaomiao.bilimiao.comm.sponsor

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

    fun showStats(context: Context) {
        val dialog = AlertDialog.Builder(context)
            .setTitle("空降助手状态")
            .setMessage("查询中…")
            .setPositiveButton("关闭", null)
            .show()
        scope.launch {
            val text = withContext(Dispatchers.IO) {
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
                        append("\n本机用户ID：${SponsorBlockApi.localUserId()}")
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
            dialog.setMessage(text)
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

    // ───────────────────────── 用户ID（对齐 PiliPlus 的「用户ID」项）─────────────────────────

    /**
     * 查看 / 编辑 / 重掷本机匿名 userID。
     *
     * 服务端用它做"一人一票"去重、也是统计数据的键；换一个 ID 相当于在服务端眼里变成另一个人
     * （之前投的票、提交的片段、省下的时间都不再算在你头上）。所以这里学 PiliPlus：
     * 弹一个输入框 + 「随机」按钮，并明确写清后果。
     */
    fun showUserIdDialog(context: Context) {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(SponsorBlockApi.localUserId())
            setSelection(text?.length ?: 0)
        }
        val tip = TextView(context).apply {
            text = "至少 30 个字符、只能是字母和数字（建议直接用「随机」）。\n" +
                "换 ID 后：投票/提交记录、服务端统计都会从零开始，且无法找回旧 ID 的数据。"
            textSize = 12f
            setPadding(dp(PAD), dp(8), dp(PAD), 0)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAD), dp(8), dp(PAD), 0)
            addView(input)
            addView(tip)
        }
        AlertDialog.Builder(context)
            .setTitle("用户ID")
            .setView(ScrollView(context).apply { addView(box) })
            .setNeutralButton("随机") { _, _ ->
                val id = SponsorBlockApi.resetUserId()
                toast(context, "已随机生成新 ID：${id.take(12)}…")
            }
            .setPositiveButton("确定") { _, _ ->
                val value = input.text?.toString()?.trim().orEmpty()
                if (SponsorBlockApi.setUserId(value)) {
                    toast(context, "已保存")
                } else {
                    toast(context, "保存失败：至少 30 个字符、只能字母和数字")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 「关于空降助手」：把项目地址复制出来说明清楚（设置页那边用系统浏览器打开） */
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
                setPadding(dp(PAD), dp(8), dp(PAD), dp(8))
            }
            SponsorCategory.entries.forEach { category ->
                val color = current[category.id] ?: category.color
                box.addView(
                    simpleRow(context, dot(context, color), category.label) {
                        showPalette(context, category)
                    }
                )
            }
            AlertDialog.Builder(context)
                .setTitle("进度条片段颜色")
                .setView(ScrollView(context).apply { addView(box) })
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun showPalette(context: Context, category: SponsorCategory) {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAD), dp(8), dp(PAD), dp(8))
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
        AlertDialog.Builder(context)
            .setTitle("${category.label} · 选择颜色")
            .setView(ScrollView(context).apply { addView(box) })
            .setNegativeButton("关闭", null)
            .show()
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
            AlertDialog.Builder(context)
                .setTitle("自定义服务端")
                .setMessage("留空使用官方 ${SponsorBlockApi.BASE_URL}（可填镜像站）")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
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
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ───────────────────────── 小工具 ─────────────────────────

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
