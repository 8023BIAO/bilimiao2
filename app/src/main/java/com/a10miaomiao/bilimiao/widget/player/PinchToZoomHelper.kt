package com.a10miaomiao.bilimiao.widget.player

import android.animation.ValueAnimator
import android.animation.AnimatorListenerAdapter
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 双指缩放/平移/旋转控制器
 *
 * 类似 PiliPlus 的 InteractiveViewer + TransformationController。
 * 将手势变换应用到 targetView 的 scaleX/Y / translationX/Y / rotation。
 *
 * 使用方式：
 *   val helper = PinchToZoomHelper(surfaceContainer, restoreButton)
 *   // 在 onTouch() 中调用 helper.onTouchEvent(event)
 *   // 当 controls 可见时调用 helper.setControlsVisible(true)
 */
class PinchToZoomHelper(
    /** 被变换的 View（通常是 surface_container） */
    private val targetView: View,
    /** 还原按钮，变换后自动显示，还原后自动隐藏 */
    private val restoreButton: View,
) {
    // ---------- 模式开关 ----------
    /** 是否启用双指变换 */
    var enabled = true

    /** 是否允许平移（可以在缩放后自由移动视频位置） */
    var enablePan = true

    /** 是否允许旋转 */
    var enableRotation = true

    /** 缩放限制 [min, max] */
    var scaleRange = 0.5f..5.0f

    /** 缩放灵敏度 (0~1，越小越不灵敏) */
    var scaleSensitivity = 0.7f

    /**
     * 平滑系数 (0~1)。
     * 越低画面跟随越慢、越稳不闪；越高越跟手。
     * 0.15~0.25 手感比较自然。
     */
    var smoothFactor = 0.20f

    // ---------- 当前变换值（平滑后的输出） ----------
    private var currentScale = 1f        // 当前缩放
    private var currentTransX = 0f       // 当前 X 平移
    private var currentTransY = 0f       // 当前 Y 平移
    private var currentRotation = 0f     // 当前旋转角度

    // ---------- 目标变换值（手势计算出的原始值） ----------
    private var targetScale = 1f
    private var targetTransX = 0f
    private var targetTransY = 0f
    private var targetRotation = 0f

    // ---------- 手势暂存 ----------
    /** 双指触摸是否激活 (2+ 指按下) */
    var isActive = false
        private set

    /** 变换中心（在 targetView 布局完成后固定设置一次） */
    private var pivotX = 0f
    private var pivotY = 0f
    private var pivotSet = false

    // — 手势开始时锁存的参考值 —
    private var refSpan = 0f             // 双指间距（参考值）
    private var refAngle = 0f            // 双指角度（参考值）
    private var refCenterX = 0f          // 双指中心 X（参考值）
    private var refCenterY = 0f          // 双指中心 Y（参考值）

    /** 双指模式开始时存一份当前变换，用于累计计算 */
    private var startScale = 1f
    private var startTransX = 0f
    private var startTransY = 0f
    private var startRotation = 0f

    // ---------- 外部控制 ----------
    /** controls 可见性（还原按钮在半透明控制器显示时跟随显示） */
    private var controlsVisible = false

    /** 变换状态变化回调 */
    var onTransformChanged: ((isTransformed: Boolean) -> Unit)? = null

    /** 是否发生了变换 */
    val isTransformed: Boolean
        get() = abs(currentScale - 1f) > 0.01f
                || abs(currentRotation) > 1f
                || abs(currentTransX) > 2f
                || abs(currentTransY) > 2f

    // ===================== 触摸入口 =====================

    /**
     * 在 [DanmakuVideoPlayer.onTouch] 中调用。
     *
     * @return true 表示已消费事件（双指激活时）
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    isActive = true
                    // 锁存手势开始时的各项参考值
                    saveInitialState()
                    saveGestureRef(event)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isActive && event.pointerCount >= 2) {
                    computeTarget(event)
                    applySmoothTransform()
                    return true
                }
            }

            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (isActive) {
                    isActive = false
                    // 手势结束时，target 追平 current，防止下次手势从错误起点出发
                    currentScale = targetScale
                    currentTransX = targetTransX
                    currentTransY = targetTransY
                    currentRotation = targetRotation
                    updateRestoreButton()
                    return true
                }
            }
        }
        return false
    }

    // ===================== 手势计算 =====================

    /** 锁存手势开始时的变换值 */
    private fun saveInitialState() {
        // 注意：用 currentX（平滑值）作为起点，
        // 确保手势间不丢精度
        startScale = currentScale
        startTransX = currentTransX
        startTransY = currentTransY
        startRotation = currentRotation
        // target 也同步
        targetScale = currentScale
        targetTransX = currentTransX
        targetTransY = currentTransY
        targetRotation = currentRotation
    }

    /** 锁存手势开始时的双指参数（参考值） */
    private fun saveGestureRef(event: MotionEvent) {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        refSpan = sqrt(dx * dx + dy * dy)
        refAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        refCenterX = (event.getX(0) + event.getX(1)) / 2f
        refCenterY = (event.getY(0) + event.getY(1)) / 2f
    }

    /** 计算当前手势并更新【目标值】 */
    private fun computeTarget(event: MotionEvent) {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        val span = sqrt(dx * dx + dy * dy)
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val centerX = (event.getX(0) + event.getX(1)) / 2f
        val centerY = (event.getY(0) + event.getY(1)) / 2f

        // --- 缩放 ---
        if (refSpan > 0f && span > 0f) {
            val rawDelta = span / refSpan
            val delta = 1f + (rawDelta - 1f) * scaleSensitivity
            targetScale = (startScale * delta).coerceIn(
                scaleRange.start, scaleRange.endInclusive
            )
        }

        // --- 旋转 ---
        if (enableRotation) {
            var dAngle = angle - refAngle
            if (dAngle > 180f) dAngle -= 360f
            if (dAngle < -180f) dAngle += 360f
            targetRotation = startRotation + dAngle
        }

        // --- 平移 ---
        if (enablePan) {
            targetTransX = startTransX + (centerX - refCenterX)
            targetTransY = startTransY + (centerY - refCenterY)
        } else {
            targetTransX = 0f
            targetTransY = 0f
        }
    }

    // ===================== 平滑应用变换 =====================

    /**
     * 用指数平滑将 currentX 向 targetX 靠近，
     * 避免每帧对 View 设值造成视觉闪烁。
     */
    private fun applySmoothTransform() {
        val f = smoothFactor
        currentScale += (targetScale - currentScale) * f
        currentTransX += (targetTransX - currentTransX) * f
        currentTransY += (targetTransY - currentTransY) * f
        currentRotation += (targetRotation - currentRotation) * f

        pushToView()
    }

    private fun pushToView() {
        val w = targetView.width
        val h = targetView.height
        if (w <= 0 || h <= 0) return

        if (!pivotSet) {
            pivotX = w / 2f
            pivotY = h / 2f
            targetView.pivotX = pivotX
            targetView.pivotY = pivotY
            pivotSet = true
        }

        targetView.scaleX = currentScale
        targetView.scaleY = currentScale
        targetView.translationX = currentTransX
        targetView.translationY = currentTransY
        targetView.rotation = currentRotation

        updateRestoreButton()
    }

    // ===================== 还原 =====================

    /** 立即还原（不做动画） */
    fun resetImmediate() {
        currentScale = 1f
        currentTransX = 0f
        currentTransY = 0f
        currentRotation = 0f
        targetScale = 1f
        targetTransX = 0f
        targetTransY = 0f
        targetRotation = 0f
        startScale = 1f
        startTransX = 0f
        startTransY = 0f
        startRotation = 0f
        isActive = false
        pushToView()
    }

    /** 带动画的还原（类似 PiliPlus 的 Matrix4Tween） */
    fun animateReset(duration: Long = 255) {
        val fromScale = currentScale
        val fromTransX = currentTransX
        val fromTransY = currentTransY
        val fromRotation = currentRotation

        if (!isTransformed) {
            updateRestoreButton()
            return
        }

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val t = animation.animatedFraction
                currentScale = fromScale + (1f - fromScale) * t
                currentTransX = fromTransX * (1f - t)
                currentTransY = fromTransY * (1f - t)
                currentRotation = fromRotation * (1f - t)
                // target 同步，避免下次手势跳跃
                targetScale = currentScale
                targetTransX = currentTransX
                targetTransY = currentTransY
                targetRotation = currentRotation
                pushToView()
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                currentScale = 1f
                currentTransX = 0f
                currentTransY = 0f
                currentRotation = 0f
                targetScale = 1f
                targetTransX = 0f
                targetTransY = 0f
                targetRotation = 0f
                startScale = 1f
                startTransX = 0f
                startTransY = 0f
                startRotation = 0f
                pushToView()
            }
        })
        animator.start()
    }

    // ===================== 还原按钮显示 =====================

    fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        updateRestoreButton()
    }

    /**
     * 屏幕坐标 → surface 逆映射（完整仿射变换：缩放+旋转+平移）。
     * 将触摸坐标从全屏空间映射回 surface 未变换时的坐标，
     * 用于骗过 GSYVideoPlayer 的单指手势检测（亮度/音量/快进）。
     */
    fun mapInversePoint(screenX: Float, screenY: Float): Pair<Float, Float> {
        if (currentScale < 0.01f) return Pair(screenX, screenY)
        // 逆平移
        val tx = screenX - pivotX - currentTransX
        val ty = screenY - pivotY - currentTransY
        // 逆旋转
        val rad = Math.toRadians((-currentRotation).toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val rx = tx * c - ty * s
        val ry = tx * s + ty * c
        // 逆缩放 + 平移回 pivot
        val sx = rx / currentScale + pivotX
        val sy = ry / currentScale + pivotY
        return Pair(sx, sy)
    }

    /** 公开当前缩放值，供外部做 delta 补偿 */
    fun getCurrentScale(): Float = currentScale

    private fun updateRestoreButton() {
        val shouldShow = isTransformed && controlsVisible
        restoreButton.visibility = if (shouldShow) View.VISIBLE else View.GONE
        onTransformChanged?.invoke(isTransformed)
    }
}
