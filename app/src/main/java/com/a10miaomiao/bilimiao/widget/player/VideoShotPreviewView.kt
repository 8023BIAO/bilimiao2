package com.a10miaomiao.bilimiao.widget.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.min

/**
 * 拖动进度条时显示在画面中央的预览缩略图。
 *
 * B 站给的是"雪碧图"：一张大图按 cols×rows 排满小格（通常是 10×10，每格 160×90），
 * 再给每格的起始时间；拖动时按时间取对应格子渲染。
 *
 * 为什么不用 ImageView + imageMatrix：
 * 取格子 = "裁剪 + 缩放"。用 Matrix 得反算平移和缩放，每换一格还要 setImageMatrix；
 * 这里在 onDraw 里直接画 src→dst 两个矩形，换格子只改一个 Int。拖动时**每一帧**都在更新，
 * 这点差别是实打实的（对齐 PiliPlus：它也是自绘，见 pl_player/view/widgets.dart 的 VideoShotImage）。
 */
class VideoShotPreviewView(context: Context) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()

    private var sheet: Bitmap? = null
    private var cols = 0
    private var rows = 0
    private var cell = 0

    init {
        visibility = GONE
        // 圆角（对齐 PiliPlus 的 mdRadius）
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // ★ 必须挡 0 尺寸：Android 的 Outline.setRoundRect 在 left>=right 或
                //   top>=bottom 时会直接抛 IllegalArgumentException("Invalid rectangle")。
                //   本控件一开始是 GONE、尺寸为 0，只要框架在首次布局前问一次 outline 就会崩，
                //   表现为"一打开 App 就闪退"（播放器 View 在启动时就创建）。
                val w = view.width
                val h = view.height
                if (w <= 0 || h <= 0) return
                outline.setRoundRect(0, 0, w, h, 6f * view.resources.displayMetrics.density)
            }
        }
        clipToOutline = true
    }

    /**
     * 切到第 [index] 小格。
     *
     * @param cols 一张图横向几格（img_x_len）
     * @param rows 纵向几格（img_y_len）
     */
    fun showCell(bitmap: Bitmap, cols: Int, rows: Int, index: Int) {
        if (cols <= 0 || rows <= 0 || bitmap.isRecycled) return
        val changed = sheet !== bitmap || this.cols != cols || this.rows != rows || cell != index
        sheet = bitmap
        this.cols = cols
        this.rows = rows
        cell = index
        if (changed) invalidate()
    }

    /** 收起来（不显示预览图） */
    fun clear() {
        sheet = null
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = sheet ?: return
        if (bmp.isRecycled || cols <= 0 || rows <= 0) return

        // 格子尺寸直接由位图算：解码时可能做过 inSampleSize 降采样，
        // 用接口给的 img_x_size 会因为降采样而偏大 → 取到相邻格子的边。
        val cw = bmp.width.toFloat() / cols
        val ch = bmp.height.toFloat() / rows
        val safeCell = cell.coerceIn(0, cols * rows - 1)
        val col = safeCell % cols
        val row = safeCell / cols

        src.set(
            (col * cw).toInt(),
            (row * ch).toInt(),
            ((col + 1) * cw).toInt().coerceAtMost(bmp.width),
            ((row + 1) * ch).toInt().coerceAtMost(bmp.height),
        )
        if (src.width() <= 0 || src.height() <= 0) return

        // 按格子比例等比缩放填进自己的尺寸（居中）
        val scale = min(width / src.width().toFloat(), height / src.height().toFloat())
        val w = src.width() * scale
        val h = src.height() * scale
        dst.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        canvas.drawBitmap(bmp, src, dst, paint)
    }
}
