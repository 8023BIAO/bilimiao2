package com.a10miaomiao.bilimiao.comm.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * **全屏覆盖层弹窗**（对齐 App 首页筛选弹层的行为）。
 *
 * 为什么不继续用 `AlertDialog`：
 * `AlertDialog` 是一个**小窗口**，尺寸/位置由 WindowManager 管；而本 App 在 Manifest 里声明了
 * `configChanges=orientation|screenSize|...`，**旋转时 Activity 不会重建** —— 小窗口不会跟着更新，
 * 内容却按新方向重新排版，于是出现"**看到的和点到的不是一个地方**"（横屏全屏播放时最明显，
 * 弹窗还会比屏幕高、按钮被顶到屏幕外）。
 *
 * 这里和首页筛选弹层（Compose 的 `AnyPopDialog`）用**完全相同的思路**：
 * **窗口铺满整个屏幕** + 半透明遮罩 + 居中卡片（高度封顶、内容自己滚）。
 * 全屏窗口不存在"窗口几何过期"：它永远贴合屏幕，卡片按当前尺寸实时排版 → 怎么旋转都点得准，
 * 也不需要 Activity 重建或 onConfigurationChanged。
 */
object OverlayDialog {

    private const val SCRIM_COLOR = 0x99000000.toInt()

    /** 自己会封顶高度的卡片容器（View 没有 maxHeight，只能自己算） */
    private class MaxHeightFrame(context: Context) : FrameLayout(context) {
        var maxHeightPx: Int = Int.MAX_VALUE
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, capped)
        }
    }

    /**
     * 把一个内容 View 以"全屏覆盖层 + 居中卡片"显示。
     *
     * @param content 卡片内容（内部布局自己负责，长内容请自带 ScrollView）
     * @param maxWidthRatio 卡片最大宽度占宿主窗口比例（默认 0.94，横屏不会超宽）
     * @param maxHeightRatio 卡片最大高度占宿主窗口比例（默认 0.88，超出部分内容自己滚）
     */
    fun show(
        context: Context,
        content: View,
        maxWidthRatio: Float = 0.94f,
        maxHeightRatio: Float = 0.88f,
        cancelOnTouchOutside: Boolean = true,
        onDismiss: (() -> Unit)? = null,
    ): Dialog {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val surface = surfaceColor(context)
        val card = MaxHeightFrame(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(surface)
            }
            isClickable = true          // 吃掉点击，避免穿透到遮罩
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        val scrim = View(context).apply {
            setBackgroundColor(SCRIM_COLOR)
            isClickable = true
            if (cancelOnTouchOutside) setOnClickListener { dialog.dismiss() }
        }
        val root = FrameLayout(context).apply {
            addView(scrim, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
        }

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(cancelOnTouchOutside)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)                       // 遮罩自己画，别让系统再叠一层
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.CENTER)
            // 全屏窗口 + 输入法：让窗口自己缩，别被 adjustPan 顶跑偏
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            )
        }

        // 卡片尺寸一律取**宿主窗口的实时尺寸**：
        // 旋转不重建 Activity 时，resources/displayMetrics 可能还是旧方向的。
        val hostDecor = (context as? Activity)?.window?.decorView
        var lastW = -1
        var lastH = -1
        val applySize = { w: Int, h: Int ->
            if (w > 0 && h > 0) {
                (card.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.width = ((w * maxWidthRatio).toInt() - dp(24)).coerceAtLeast(dp(200))
                    card.layoutParams = lp
                }
                card.maxHeightPx = (h * maxHeightRatio).toInt()
                card.requestLayout()
            }
        }
        var sizeListener: View.OnLayoutChangeListener? = null
        if (hostDecor != null) {
            val listener = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                val w = r - l
                val h = b - t
                if (w > 0 && h > 0 && (w != lastW || h != lastH)) {
                    lastW = w
                    lastH = h
                    applySize(w, h)
                }
            }
            sizeListener = listener
            hostDecor.addOnLayoutChangeListener(listener)
            applySize(hostDecor.width, hostDecor.height)
        } else {
            applySize(
                context.resources.displayMetrics.widthPixels,
                context.resources.displayMetrics.heightPixels,
            )
        }

        dialog.setOnDismissListener {
            sizeListener?.let { hostDecor?.removeOnLayoutChangeListener(it) }
            onDismiss?.invoke()
        }
        dialog.show()
        return dialog
    }

    /** 纯列表选择（替代 `AlertDialog.setItems`），走同一套全屏覆盖层 */
    fun showList(
        context: Context,
        title: String?,
        items: List<String>,
        onPick: (Int) -> Unit,
        onDismiss: (() -> Unit)? = null,
    ): Dialog {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val textColor = onSurfaceColor(context)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        title?.let {
            box.addView(TextView(context).apply {
                text = it
                textSize = 17f
                setTextColor(textColor)
                setPadding(0, 0, 0, dp(8))
            })
        }
        box.addView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                items.forEachIndexed { index, label ->
                    addView(TextView(context).apply {
                        text = label
                        textSize = 16f
                        setTextColor(textColor)
                        setPadding(0, dp(12), 0, dp(12))
                        isClickable = true
                        setOnClickListener { onPick(index) }
                    })
                }
            })
        })
        return show(context, box, onDismiss = onDismiss)
    }

    // ── 颜色：直接用系统属性解析，避免依赖具体主题/库 ──

    private fun attrColor(context: Context, attr: Int, fallback: Int): Int = runCatching {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }.getOrDefault(fallback)

    private fun surfaceColor(context: Context): Int =
        attrColor(context, android.R.attr.colorBackground, Color.parseColor("#FF1E1E1E"))

    /** 文字色按卡片底色亮度选黑白，省掉一层 ColorStateList 解析 */
    private fun onSurfaceColor(context: Context): Int {
        val bg = surfaceColor(context)
        val lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0
        return if (lum > 0.5) Color.BLACK else Color.WHITE
    }
}
