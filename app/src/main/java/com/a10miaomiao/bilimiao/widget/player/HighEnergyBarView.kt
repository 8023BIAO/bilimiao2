package com.a10miaomiao.bilimiao.widget.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 进度条上方的「高能进度条」：把 pbp 接口返回的弹幕热度顶点画成一条半透明曲线。
 *
 * - 放进播放器 layout_bottom 内，控制条隐藏时随父布局一起隐藏（不常驻）；
 * - 数据为空/全 0 时 GONE，不占高度；
 * - 只有绘制，不处理触摸（clickable=false / focusable=false）。
 */
class HighEnergyBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePath = Path()
    private val fillPath = Path()
    private var points = FloatArray(0)
    private var barColor = Color.WHITE
    // 由 SeekBar 真实 progressDrawable.bounds 算出的左右内缩；曲线按此绘制才与轨道 1:1
    private var insetLeft = 0f
    private var insetRight = 0f

    init {
        linePaint.color = barColor
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setBarColor(color: Int) {
        barColor = color
        linePaint.color = color
        updateFillPaint()
        invalidate()
    }

    /**
     * @param values 归一化前的热度顶点（等间隔）；null/少于2个/全 0 时隐藏
     * @param stepSec 采样间隔（秒），当前只用于未来按真实时长对齐；绘制按等间隔铺满宽度
     */
    fun setData(values: List<Double>?, stepSec: Int = 0) {
        if (values == null || values.size < 2) {
            clear()
            return
        }
        var maxValue = 0.0
        for (v in values) if (v.isFinite() && v > maxValue) maxValue = v
        if (maxValue <= 0.0) {
            clear()
            return
        }
        val count = values.size
        points = FloatArray(count)
        for (i in 0 until count) {
            val v = values[i].takeIf { it.isFinite() } ?: 0.0
            points[i] = (v / maxValue).toFloat().coerceIn(0f, 1f)
        }
        updateFillPaint()
        visibility = VISIBLE
        invalidate()
    }

    /** 按 SeekBar 轨道两端的内缩量（px）绘制，避免曲线比轨道长一截。 */
    fun setHorizontalInsets(left: Int, right: Int) {
        insetLeft = left.coerceAtLeast(0).toFloat()
        insetRight = right.coerceAtLeast(0).toFloat()
        invalidate()
    }

    fun clear() {
        points = FloatArray(0)
        linePath.reset()
        fillPath.reset()
        visibility = GONE
        invalidate()
    }

    /**
     * 固定透明度的面积填充：平的地方也有一条淡淡的底色，凸起处面积更大、更显眼。
     * 透明度不随高度变化，避免“越高越亮”导致看错；高度本身才是热度。
     */
    private fun updateFillPaint() {
        fillPaint.color = Color.argb(
            0x3D,
            Color.red(barColor),
            Color.green(barColor),
            Color.blue(barColor),
        )
        // 线本身稍微实一点，保证轮廓清楚；不做渐变
        linePaint.alpha = 0xD0
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateFillPaint()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = points.size
        if (n < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        linePath.reset()
        fillPath.reset()
        val startX = insetLeft.coerceAtMost(w / 2f)
        val endX = (w - insetRight).coerceAtLeast(startX)
        val drawWidth = endX - startX
        if (drawWidth <= 0f) return
        val stepX = drawWidth / (n - 1)
        var prevX = startX
        var prevY = h - points[0] * h
        linePath.moveTo(prevX, prevY)
        fillPath.moveTo(prevX, h)
        fillPath.lineTo(prevX, prevY)
        for (i in 1 until n) {
            // 必须加上 startX：之前漏了，导致整条曲线从 0 开始铺、左边多出一截
            val x = startX + i * stepX
            val y = h - points[i] * h
            // 用中点做三次平滑，避免直线太“硬”；数据本身仍是原始顶点
            val midX = (prevX + x) * 0.5f
            linePath.cubicTo(midX, prevY, midX, y, x, y)
            fillPath.cubicTo(midX, prevY, midX, y, x, y)
            prevX = x
            prevY = y
        }
        fillPath.lineTo(endX, h)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }
}
