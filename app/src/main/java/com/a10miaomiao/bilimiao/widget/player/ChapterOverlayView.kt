package com.a10miaomiao.bilimiao.widget.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * 在进度条上方绘制视频章节标记
 * drawBounds = SeekBar的实际绘制区域（像素坐标，相对本View）
 * 参考 PiliPlus 的 ViewPointSegmentProgressBar
 */
class ChapterOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 8f, context.resources.displayMetrics
        )
        color = 0xFFFFFFFF.toInt()
    }
    private val measurePaint = Paint(textPaint)

    var chapters: List<ChapterInfo> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var onChapterClick: ((Long) -> Unit)? = null

    /** 设置文字颜色（跟随主题） */
    fun setTextColor(color: Int) {
        textPaint.color = color
        invalidate()
    }


    /** SeekBar的绘制区域（相对本View左边缘的偏移X和宽度） */
    var drawOffsetX: Int = 0
    var drawWidth: Int = 0

    private var alignmentCallback: (() -> Unit)? = null

    /** 设置对齐回调（每次布局变更时自动触发） */
    fun setAlignmentCallback(cb: () -> Unit) {
        alignmentCallback = cb
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            post { alignmentCallback?.invoke() }
        }
    }

    // 全部用 DP 固定值
    private val barHeight = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 10f, context.resources.displayMetrics
    ).toInt()
    private val dividerWidth = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 2f, context.resources.displayMetrics
    )
    private val cornerRadius = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 2f, context.resources.displayMetrics
    )
    /** 每次跳过文字的像素数（太挤就不画文字） */
    private val minPixelsPerLabel = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 30f, context.resources.displayMetrics
    ).toInt()

    init {
        bgPaint.color = 0x73000000
        dividerPaint.color = 0xFF999999.toInt()
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (chapters.isEmpty() || width == 0) return

        val w = width.toFloat()
        if (w <= 0) return

        val drawL = drawOffsetX.toFloat()
        val drawW = if (drawWidth > 0) drawWidth.toFloat() else w
        val drawR = drawL + drawW

        if (drawW <= 0) return

        // 背景条（只画在SeekBar区域内）
        canvas.drawRoundRect(RectF(drawL, 0f, drawR, barHeight.toFloat()), cornerRadius, cornerRadius, bgPaint)

        if (chapters.size <= 1) return

        val cl = textPaint.fontMetrics.let { it.descent - it.ascent }
        val textBaseY = (barHeight + cl) / 2f

        // 只画大段落的文字，跳过太短的段落
        val avgSegPixels = drawW / chapters.size

        for (i in 0 until chapters.size) {
            val chap = chapters[i]
            val segStart = drawL + chap.startFraction * drawW
            val segEnd = if (i + 1 < chapters.size) {
                drawL + chapters[i + 1].startFraction * drawW
            } else {
                drawR
            }

            // 分隔线（每个章节起点画一条，不含第一个）
            if (i > 0) {
                canvas.drawRect(segStart, 0f, segStart + dividerWidth, barHeight.toFloat(), dividerPaint)
            }

            // 标题文字（只在段落宽度足够时画）
            val title = chap.title
            if (!title.isNullOrEmpty() && avgSegPixels > minPixelsPerLabel) {
                val textWidth = measurePaint.measureText(title)
                val segWidth = segEnd - segStart - dividerWidth
                if (segWidth > 8f) {
                    val cx = (segStart + segEnd) / 2f
                    if (textWidth > segWidth - 4f) {
                        // 溢出时缩放到 segWidth
                        val scale = ((segWidth - 4f) / textWidth).coerceIn(0.5f, 1f)
                        canvas.save()
                        canvas.translate(segStart + 2f, textBaseY - cl * scale / 2f)
                        canvas.scale(scale, scale)
                        canvas.drawText(title, 0f, cl, textPaint)
                        canvas.restore()
                    } else {
                        canvas.drawText(title, cx - textWidth / 2f, textBaseY, textPaint)
                    }
                }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            barHeight
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (chapters.isEmpty() || width == 0) return false
        val drawW = if (drawWidth > 0) drawWidth.toFloat() else width.toFloat()
        if (drawW <= 0) return false

        val drawL = drawOffsetX.toFloat()
        when (event.action) {
            MotionEvent.ACTION_UP -> {
                val fraction = ((event.x - drawL) / drawW).coerceIn(0f, 1f)
                val idx = chapters.indices.firstOrNull { i ->
                    chapters[i].startFraction > fraction
                }?.minus(1) ?: (chapters.size - 1)
                if (idx in chapters.indices) {
                    onChapterClick?.invoke(chapters[idx].startMs)
                }
                return true
            }
            MotionEvent.ACTION_DOWN -> return true
            else -> return true
        }
    }
}
