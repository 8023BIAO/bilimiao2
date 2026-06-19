package com.a10miaomiao.bilimiao.widget.scaffold.behavior

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.Dimension
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import com.a10miaomiao.bilimiao.config.config
import com.a10miaomiao.bilimiao.widget.scaffold.AppBarView
import com.a10miaomiao.bilimiao.widget.scaffold.ScaffoldView

class AppBarBehavior : CoordinatorLayout.Behavior<View> {

    companion object {
        @Volatile
        var globalLock = false
    }

    private val STATE_SCROLLED_DOWN = 1
    private val STATE_SCROLLED_UP = 2

    private var currentState = STATE_SCROLLED_UP
    private var additionalHiddenOffsetY = 0

    var appBarHeight = 0
    var appBarWidth = 0
    var appBarMenuHeight = 0
    var showPlayer = false

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        appBarHeight = context.config.appBarHeight
        appBarWidth = context.config.appBarMenuWidth
        appBarMenuHeight = context.config.appBarMenuHeight
        init()
    }

    fun init() {
    }

    var parentRef: ScaffoldView? = null
    var viewRef: View? = null
    var bottomBarLocked = false

    override fun onLayoutChild(parent: CoordinatorLayout, child: View, layoutDirection: Int): Boolean {
        this.viewRef = child
        if (globalLock) {
            this.bottomBarLocked = true
        }
        if (parent is ScaffoldView) {
            this.parentRef = parent
            if (parent.fullScreenPlayer) {
                child.layout(0, 0, 0, 0)
            } else {
                if (currentState == STATE_SCROLLED_DOWN
                    && parent.orientation == ScaffoldView.HORIZONTAL) {
                    currentState = STATE_SCROLLED_UP
                    child.translationY = 0f
                }
                if (parent.orientation == ScaffoldView.HORIZONTAL) {
                    val width = appBarWidth + child.paddingLeft
                    child.layout(width - parent.measuredWidth, 0, width, parent.measuredHeight)
                } else {
                    val height = appBarHeight + child.paddingBottom
                    child.layout(
                        0,
                        parent.measuredHeight - height,
                        parent.measuredWidth,
                        parent.measuredHeight + parent.measuredHeight + height
                    )
                }
            }
        } else {
            val height = appBarHeight + child.paddingBottom
            val width = appBarWidth + child.paddingLeft
            child.layout(0, parent.measuredHeight - height, parent.measuredWidth, parent.measuredHeight)
        }
        return true
    }

    override fun onInterceptTouchEvent(
        parent: CoordinatorLayout,
        child: View,
        ev: MotionEvent
    ): Boolean {
        var left = 0
        var right = 0
        var top = 0
        var bottom = 0
        if(ev.action == MotionEvent.ACTION_DOWN && parentRef != null){
            if (parentRef!!.fullScreenPlayer) {
            } else {
                if (parentRef!!.orientation == ScaffoldView.HORIZONTAL) {
                    val width = appBarWidth + child.paddingLeft
                    left = width - parentRef!!.measuredWidth
                    top = 0
                    right = width
                    bottom = parentRef!!.measuredHeight
                } else {
                    val height = appBarHeight + child.paddingBottom
                    left = 0
                    top = parentRef!!.measuredHeight - height
                    right = parentRef!!.measuredWidth
                    bottom = parentRef!!.measuredHeight + parentRef!!.measuredHeight + height
                }
            }
            if (ev.x > left && ev.x < right && ev.y > top && ev.y < bottom) {
                child.requestFocus()
            }
        }
        return super.onInterceptTouchEvent(parent, child, ev)
    }

    fun setAdditionalHiddenOffsetY(child: View, @Dimension offset: Int) {
        additionalHiddenOffsetY = offset
        if (currentState == STATE_SCROLLED_DOWN) {
            child.translationY = (appBarMenuHeight + additionalHiddenOffsetY).toFloat()
        }
    }

    override fun onStartNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: View,
        directTargetChild: View,
        target: View,
        nestedScrollAxes: Int,
        type: Int
    ): Boolean {
        return viewRef?.top != 0
                && parentRef?.showMaskView != true
                && !(parentRef?.bottomBarLocked ?: false)
                && !bottomBarLocked
                && !globalLock
                && target.tag != false
                && nestedScrollAxes == ViewCompat.SCROLL_AXIS_VERTICAL
    }

    override fun onNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: View,
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray
    ) {
        if (parentRef?.bottomBarLocked == true || bottomBarLocked || globalLock) {
            return
        }
        if (parentRef?.orientation == ScaffoldView.HORIZONTAL) {
            return
        }
        if (dyConsumed > 0) {
            slideDown(child)
        } else if (dyConsumed < 0) {
            slideUp(child)
        }
    }

    private fun isScrolledUp(): Boolean {
        return currentState == STATE_SCROLLED_UP
    }

    fun slideUp(child: View) {
        slideUp(child, true)
    }

    fun slideUp(child: View, animate: Boolean) {
        if (isScrolledUp()) {
            return
        }
        currentState = STATE_SCROLLED_UP
        if (animate) {
            child.animate()
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            child.translationY = 0f
        }
        if (child is AppBarView) {
            child.showMenu()
        }
    }

    private fun isScrolledDown(): Boolean {
        return currentState == STATE_SCROLLED_DOWN
    }

    fun slideDown(child: View) {
        slideDown(child, true)
    }

    fun slideDown(child: View, animate: Boolean) {
        if (isScrolledDown()) {
            return
        }
        currentState = STATE_SCROLLED_DOWN
        val targetTranslationY = appBarMenuHeight + additionalHiddenOffsetY
        if (animate) {
            child.animate()
                .translationY(targetTranslationY.toFloat())
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            child.translationY = targetTranslationY.toFloat()
        }
        if (child is AppBarView) {
            child.hideMenu()
        }
    }
}