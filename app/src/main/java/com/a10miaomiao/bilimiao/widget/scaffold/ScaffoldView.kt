package com.a10miaomiao.bilimiao.widget.scaffold

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.a10miaomiao.bilimiao.comm.delegate.player.PlayerDelegate2
import com.a10miaomiao.bilimiao.config.config
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.AppBarBehavior
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.ContentBehavior
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.DrawerBehavior
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.MaskBehavior
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.PlayerBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior
import splitties.dimensions.dip
import splitties.views.dsl.core.wrapContent

class ScaffoldView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : CoordinatorLayout(context, attrs, defStyleAttr) {

    companion object {
        const val HORIZONTAL = 2 // 横屏
        const val VERTICAL = 1 // 竖屏
    }

    var onPlayerChanged: ((show: Boolean) -> Unit)? = null
    var onDrawerStateChanged: ((state: Int) -> Unit)? = null
    var playerDelegate: PlayerDelegate2? = null

    /**
     * 横屏时小窗大小
     */
    var playerSmallShowArea: Int = 480
    var playerHoldShowArea: Int = 130

    /**
     * 播放器比例
     */
    val playerVideoRatio: Float
        get() {
            return playerDelegate?.getVideoRatio() ?: (16f / 9f)
        }

    /**
     * 播放器视图尺寸状态
     */
    var playerViewSizeStatus: PlayerViewSizeStatus = PlayerViewSizeStatus.NORMAL
        set(value) {
            if (field != value) {
                field = value
                playerDelegate?.setHoldStatus(isHoldUpPlayer)
                updateLayout()
            }
        }

    /**
     * 播放器视图位置状态
     */
    var playerViewPlaceStatus: PlayerViewPlaceStatus = PlayerViewPlaceStatus.RT
        set(value) {
            if (field != value) {
                field = value
                updateLayout()
            }
        }

    val isFoldPlayer: Boolean
        get() = playerViewSizeStatus == PlayerViewSizeStatus.FOLD

    val isHoldUpPlayer: Boolean
        get() = playerViewSizeStatus == PlayerViewSizeStatus.HOLD_UP

    var orientation = VERTICAL
        set(value) {
            val changed = field != value
            field = value
            // ★ 挂起/折叠状态**无条件**复位：
            //   以前只在"方向值真的变了"时复位，于是"ScaffoldView 认为方向没变、窗口其实变了"
            //   （关掉系统自动旋转后手动旋转设备就是这种情形）会把播放器 UI 永久锁在
            //   isHoldUp=true —— 除进度条外全部 GONE、点什么都没反应（用户报的"界面被锁住"）。
            if (playerViewSizeStatus != PlayerViewSizeStatus.NORMAL) {
                playerViewSizeStatus = PlayerViewSizeStatus.NORMAL
            }
            if (changed) {
                this.appBar?.orientation = orientation
                updateLayout()
            }
        }

    /**
     * 窗口**真实尺寸**变化回调（width, height）。方向判定的最终真源。
     *
     * 为什么需要它：旋转不重建 Activity（Manifest 里 configChanges 声明了 orientation），
     * `onConfigurationChanged` 可能早于真正的大小变化，`resources.configuration` 也可能是旧值；
     * 只有走到 onSizeChanged 才代表"尺寸已经变完了"。播放器靠它重算 全屏/小窗 模式。
     */
    var onWindowSizeChanged: ((width: Int, height: Int) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            // 宽 > 高 = 横屏。setter 里带"值没变就不重排"的判断，不会引起布局循环
            orientation = if (w > h) HORIZONTAL else VERTICAL
        }
        onWindowSizeChanged?.invoke(w, h)
    }

    var bottomBarLocked = false // 锁定底栏，不随滚动隐藏

    var showPlayer = false
        set(value) {
            if (field != value) {
                if (!value) {
                    smallModePlayerCurrentHeight = smallModePlayerMinHeight
                    playerViewSizeStatus = PlayerViewSizeStatus.NORMAL
                }
                field = value
                updateLayout()
                onPlayerChanged?.invoke(field)
            }
        }
    var fullScreenPlayer = false
    var fullScreenDraggable = true
        set(value) {
            if (field != value) {
                field = value
                updateLayout()
                onPlayerChanged?.invoke(true)
            }
        }

    var appBarHeight = config.appBarHeight
    var appBarWidth = config.appBarMenuWidth

    val smallModePlayerMinHeight = dip(200) // 竖屏模式下的播放器最小高度
    var smallModePlayerMaxHeight = smallModePlayerMinHeight // 竖屏模式下的播放器最大高度
        set(value) {
            if (field != value) {
                field = value
                if (smallModePlayerCurrentHeight > value) {
                    animatePlayerHeight(value)
                }
            }
        }
    var smallModePlayerCurrentHeight = smallModePlayerMinHeight // 竖屏模式播放器实际高度
    var statusBarHeight = 0

    var playerSpaceHeight = 0 // 播放器占用的高度

    var appBar: AppBarView? = null
    var appBarBehavior: AppBarBehavior? = null

    var content: View? = null
    var contentBehavior: ContentBehavior? = null

    val showMaskView get() = maskView?.visibility == View.VISIBLE

    var player: View? = null
    var playerBehavior: PlayerBehavior? = null

    var bottomSheet: View? = null
    var bottomSheetBehavior: BottomSheetBehavior<View>? = null

    var drawerEnabled = true // 是否允许侧滑打开个人中心，子页面禁用防误触
    var drawerView: View? = null
    var drawerBehavior: DrawerBehavior? = null

    var maskView: View? = null
    var maskBehavior: MaskBehavior? = null

    fun updateLayout(animate: Boolean = false) {
        playerBehavior?.updateLayout()
        requestLayout()
    }

    /**
     * 更新内容视图的偏移
     * 在播放器高度变化时调用，让内容视图避开播放器
     */
    fun updateContentOffset() {
        contentBehavior?.updateContentOffset()
    }

    override fun addView(
        child: View?,
        index: Int,
        params: ViewGroup.LayoutParams?
    ) {
        if (params is LayoutParams) {
            when (val behavior = params.behavior) {
                is AppBarBehavior -> {
                    if (child is AppBarView) {
                        child.orientation = orientation
                        this.appBar = child
                        this.appBarBehavior = behavior
                    }
                }

                is PlayerBehavior -> {
                    this.player = child
                    this.playerBehavior = behavior
                }

                is BottomSheetBehavior -> {
                    this.bottomSheet = child
                    this.bottomSheetBehavior = behavior
                }

                is DrawerBehavior -> {
                    this.drawerView = child
                    this.drawerBehavior = behavior
                }

                is MaskBehavior -> {
                    this.maskView = child
                    this.maskBehavior = behavior
                }

                is ContentBehavior -> {
                    // 内容视图（播放列表、动态等）使用 ContentBehavior
                    if (this.content == null) {
                        this.content = child
                        this.contentBehavior = behavior
                    }
                }
            }
        }
        super.addView(child, index, params)
    }

    fun bottomSheetState(): Int {
        return bottomSheetBehavior?.state ?: BottomSheetBehavior.STATE_HIDDEN
    }

    fun isDrawerOpen(): Boolean {
        return drawerBehavior?.isDrawerOpen() ?: false
    }

    fun openDrawer() {
        drawerBehavior?.openDrawer()
    }

    fun closeDrawer() {
        drawerBehavior?.closeDrawer()
    }

    fun changedDrawerState(state: Int) {
        onDrawerStateChanged?.invoke(state)
    }

    fun getDrawerTouchStartY(): Float {
        return drawerBehavior?.getTouchStartY() ?: 0f
    }

    fun slideUpBottomAppBar() {
        appBar?.let {
            appBarBehavior?.slideUp(it)
        }
    }

    fun setMaskViewVisibility(visibility: Int) {
        maskView?.visibility = visibility
    }

    fun getMaskViewVisibility(): Int {
        return maskView?.visibility ?: INVISIBLE
    }

    fun setMaskViewAlpha(alpha: Float) {
        maskView?.alpha = alpha
    }

    fun slideDownBottomAppBar() {
        appBar?.let {
            appBarBehavior?.slideDown(it)
        }
    }

    fun holdUpPlayer() {
        playerBehavior?.holdUpPlayer()
    }

    private var _playerHeightAnimator: ValueAnimator? = null

    fun animatePlayerHeight(target: Int) {
        if (smallModePlayerCurrentHeight != target) {
            _playerHeightAnimator?.cancel()
            _playerHeightAnimator = ValueAnimator.ofInt(
                smallModePlayerCurrentHeight,
                target
            ).apply {
                duration = 200
                addUpdateListener {
                    smallModePlayerCurrentHeight = it.animatedValue as Int
                    playerBehavior?.updateLayout()
                    player?.requestLayout()
                    updateContentOffset()
                }
                start()
            }
        }
    }

    inline fun lParams(
        width: Int = wrapContent,
        height: Int = wrapContent,
        initParams: LayoutParams.() -> Unit = {}
    ): LayoutParams {
        return LayoutParams(width, height).apply(initParams)
    }

    class LayoutParams(width: Int, height: Int) : CoordinatorLayout.LayoutParams(width, height) {

    }

    /**
     * 播放器视图尺寸状态
     */
    enum class PlayerViewSizeStatus {
        NORMAL, // 正常
        FOLD, // 折叠，以展示内容区域为主
        HOLD_UP, // 挂起，横屏状态挂在屏幕边缘
    }

    /**
     * 播放器位置状态
     */
    enum class PlayerViewPlaceStatus {
        LT,
        RT,
        LB,
        RB,
        MIDDLE,
    }

}
