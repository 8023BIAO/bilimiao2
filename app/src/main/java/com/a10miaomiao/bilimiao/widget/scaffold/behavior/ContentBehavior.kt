package com.a10miaomiao.bilimiao.widget.scaffold.behavior

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.a10miaomiao.bilimiao.widget.scaffold.ScaffoldView

class ContentBehavior : CoordinatorLayout.Behavior<View> {

    constructor() {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    fun init() {
    }

    var parentRef: ScaffoldView? = null
    var viewRef: View? = null
    var height = 0
    var width = 0

    private var _translationYAnimator: ValueAnimator? = null

    override fun onLayoutChild(
        parent: CoordinatorLayout,
        child: View,
        layoutDirection: Int
    ): Boolean {
        this.viewRef = child
        if (parent is ScaffoldView) {
            parentRef = parent
            if (parent.fullScreenPlayer) {
                // ★全屏时**只把内容 layout 成 0×0（不绘制、不 overdraw），测量尺寸必须保持真实窗口尺寸**。
                //   以前这里把 height/width 也设成 0，下面的 layoutParams 同步块就会把子 View 的 layoutParams
                //   压成 0×0 + requestLayout ⇒ 内容里的 ComposeView 被 EXACTLY 0×0 约束测量。
                //   Compose Pager 在 0 视口下会把 currentPage 写成 pageCount-1（最后一页），
                //   而 PagerState 多数活在 ViewModel 里 ⇒ 退出全屏后搜索页等所有内容区 tab
                //   100% 跳到最后一个（专栏），与用返回键/缩小/左上返回哪条退出路径无关。
                height = parent.measuredHeight
                width = parent.measuredWidth
                child.layout(0, 0, 0, 0)
            } else {
                height = parent.measuredHeight
                if (parent.orientation == ScaffoldView.HORIZONTAL) {
                    val appBarWidth = parent.appBarWidth
                    child.layout(appBarWidth, 0, parent.measuredWidth, height)
                    width = parent.measuredWidth - appBarWidth
                } else {
                    child.layout(0, 0, parent.measuredWidth, height)
                    width = parent.measuredWidth
                }
                // 竖屏模式下，内容视图需要被播放器高度下推
                if (parent.orientation == ScaffoldView.VERTICAL && parent.showPlayer) {
                    animateTranslationY(parent.playerSpaceHeight.toFloat())
                } else {
                    animateTranslationY(0f)
                }
            }

            if (child.layoutParams.height != height || child.layoutParams.width != width) {
                child.layoutParams.height = height
                child.layoutParams.width = width
                child.requestLayout()
            }
        } else {
            height = parent.measuredHeight
            width = parent.measuredWidth
            child.layout(0, 0, width, height)
        }
        return true
    }

    /**
     * 更新内容视图的 translationY
     * 在播放器高度变化时调用
     */
    fun updateContentOffset() {
        val parent = parentRef ?: return
        val child = viewRef ?: return
        if (parent.fullScreenPlayer) {
            animateTranslationY(0f)
        } else if (parent.orientation == ScaffoldView.VERTICAL) {
            animateTranslationY(parent.playerSpaceHeight.toFloat())
        } else {
            animateTranslationY(0f)
        }
    }

    private fun animateTranslationY(translationY: Float) {
        val child = viewRef ?: return
        // 直接设置 translationY，不用动画 — 避免竖屏拖拽时露出灰色背景
        child.translationY = translationY
    }
}
