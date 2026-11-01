package cn.a10miaomiao.bilimiao.compose.components.layout.chain_scrollable

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun rememberChainScrollableLayoutState(
    maxScrollPosition: Dp,
    minScrollPosition: Dp = 0.dp,
): ChainScrollableLayoutState {
    val density = LocalDensity.current
    val offsetY = rememberSaveable() {
        mutableFloatStateOf(0f)
    }
    // 状态对象只在 density 变化时重建。头部高度/状态栏高度是滚动范围，随时会变，
    // 不能当 remember 的 key，否则头部高度一变就重建 state、已折叠的偏移量
    // 不按新范围收敛，内容会错位到下一次滚动才纠正。
    val state = remember(density) {
        ChainScrollableLayoutState(
            offsetY,
            density,
        )
    }
    // 头部高度可能在动画/加载中持续变化：组合完成后立即同步可折叠范围并收敛偏移量，
    // 本帧的布局/绘制读到的是新范围（updateScrollRange 只在变化时写 state，不会死循环）
    SideEffect {
        state.updateScrollRange(maxScrollPosition, minScrollPosition)
    }
    return state
}

@Stable
class ChainScrollableLayoutState(
    private val offsetYState: MutableState<Float>,
    val density: Density,
) {
    private val maxScrollPositionState = mutableStateOf(0.dp)
    private val minScrollPositionState = mutableStateOf(0.dp)

    /**
     * 完全折叠时头部保留的高度（通常是状态栏高度）
     */
    val maxScrollPosition: Dp get() = maxScrollPositionState.value

    /**
     * 头部最少可见的高度
     */
    val minScrollPosition: Dp get() = minScrollPositionState.value

    val maxPx: Float get() = density.run { maxScrollPosition.toPx() }
    val minPx: Float get() = density.run { minScrollPosition.toPx() }

    /**
     * 可折叠的总高度（px），不会为负
     */
    val maxCollapsiblePx: Float get() = (maxPx - minPx).coerceAtLeast(0f)

    /**
     * 头部可见比例（px 无关）
     * 1 = 完全展开，0 = 完全折叠
     */
    val headerFraction: Float
        get() {
            val collapsible = maxCollapsiblePx
            if (collapsible <= 0f) return 1f
            return (1f + getOffsetYValue() / collapsible).coerceIn(0f, 1f)
        }

    /**
     * 当前头部折叠偏移量（px）：0 = 完全展开，-maxCollapsiblePx = 完全折叠。
     * 已按当前可折叠范围收敛。
     */
    fun getOffsetYValue(): Float = offsetYState.value.coerceIn(-maxCollapsiblePx, 0f)

    /**
     * 同步可折叠范围。
     * 头部高度变化时，需要把已有的折叠偏移量收敛到新的范围内，
     * 否则会出现内容错位（偏移量比可折叠高度还大）。
     */
    fun updateScrollRange(maxScrollPosition: Dp, minScrollPosition: Dp) {
        if (this.maxScrollPosition != maxScrollPosition) {
            maxScrollPositionState.value = maxScrollPosition
        }
        if (this.minScrollPosition != minScrollPosition) {
            minScrollPositionState.value = minScrollPosition
        }
        val current = offsetYState.value
        val clamped = current.coerceIn(-maxCollapsiblePx, 0f)
        if (clamped != current) {
            offsetYState.value = clamped
        }
    }

    /**
     * 修改滚动的位置
     * Set number of scroll position
     */
    fun setOffsetY(value: Float) {
        offsetYState.value = value.coerceIn(-maxCollapsiblePx, 0f)
    }

    fun scrollToMax() {
        offsetYState.value = -maxCollapsiblePx
    }

    val nestedScroll = object : NestedScrollConnection {
        override fun onPreScroll(
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            if (
                available.y > 0f
                && getOffsetYValue() < 0f
            ) {
                setOffsetY(minOf(getOffsetYValue() + available.y, 0f))
                return available
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            return super.onPostScroll(consumed, available, source)
        }
    }

}
