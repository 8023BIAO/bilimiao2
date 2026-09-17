package com.a10miaomiao.bilimiao.widget.player

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * 「空降助手」片段色块 —— **画在可拖动的进度条上**（对齐 PiliPlus 的观感）。
 *
 * 做法：把它作为一层 `LayerDrawable` 叠在原来的进度条 drawable **之上**，
 * **覆盖整条高度** —— 与 PiliPlus 一致（`pl_player/view/view.dart:1749` 把高 3.5 的
 * SegmentProgressBar 用 Positioned(bottom: 0.75) 叠在 barHeight 3.5 的 ProgressBar 上，
 * 区域内色块盖住进度线）。
 *
 * 用 Drawable 而不是 View：它的 bounds 就是 SeekBar 轨道的实际范围，
 * 所以**天然对齐**（不用自己去算边距、也不怕主题/布局变化）。
 */
class SegmentMarksDrawable : Drawable() {

    /** 一段：[起点占比 0~1, 终点占比 0~1, 颜色] */
    var marks: List<Triple<Float, Float, Int>> = emptyList()
        set(value) {
            field = value
            invalidateSelf()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Drawable 自身的透明度（0..255）；draw() 里要手动乘进去，否则会被 setColor 覆盖掉 */
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        if (marks.isEmpty()) return
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val top = b.top.toFloat()   // 整条高度覆盖（对齐 PiliPlus：色块条 3.5 叠在高 3.5 的进度条上）
        val bottom = b.bottom.toFloat()
        val left0 = b.left.toFloat()
        val right0 = b.right.toFloat()

        for ((start, end, color) in marks) {
            // ★ setColor 会把 alpha 通道一起覆盖，所以这里必须每次把 drawableAlpha 乘回去
            //   （以前 setAlpha 直接写 paint.alpha，被下一行的 setColor 抹掉 → 完全无效）
            paint.color = color
            paint.alpha = (Color.alpha(color) * drawableAlpha) / 255
            val left = left0 + start.coerceIn(0f, 1f) * b.width()
            val right = left0 + end.coerceIn(0f, 1f) * b.width()
            if (right > left) {
                canvas.drawRect(left, top, right, bottom, paint)
            } else if (left > left0) {
                // 起止重合的点状片段（poi 之类）：画 2px 细线，至少看得见
                canvas.drawRect(left, top, (left + 2f).coerceAtMost(right0), bottom, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        if (drawableAlpha == alpha) return
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

}
