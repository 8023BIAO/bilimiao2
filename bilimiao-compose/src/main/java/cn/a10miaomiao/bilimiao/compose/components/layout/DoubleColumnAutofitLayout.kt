package cn.a10miaomiao.bilimiao.compose.components.layout

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import cn.a10miaomiao.bilimiao.compose.components.layout.chain_scrollable.ChainScrollableLayout
import cn.a10miaomiao.bilimiao.compose.components.layout.chain_scrollable.ChainScrollableLayoutState
import kotlin.math.roundToInt

@Composable
fun DoubleColumnAutofitLayout(
    modifier: Modifier = Modifier,
    innerPadding: PaddingValues = PaddingValues(),
    leftMaxHeight: Dp,
    leftMaxWidth: Dp,
    chainScrollableLayoutState: ChainScrollableLayoutState,
    leftContent: @Composable BoxScope.(Orientation, PaddingValues) -> Unit,
    content: @Composable BoxScope.(Orientation, PaddingValues) -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
    ) {
        // PaddingValues.Absolute 要求所有值 >= 0，传负数会直接抛
        // IllegalArgumentException: Padding must be non-negative。
        // 目前两个调用方传进来的都是已经夹过 0 的 toPaddingValues()，
        // 这里再兜一次底，避免以后有人传原始 padding 进来把整页搞崩。
        val innerStart = max(innerPadding.calculateStartPadding(LayoutDirection.Ltr), 0.dp)
        val innerEnd = max(innerPadding.calculateEndPadding(LayoutDirection.Ltr), 0.dp)
        val innerTop = max(innerPadding.calculateTopPadding(), 0.dp)
        val innerBottom = max(innerPadding.calculateBottomPadding(), 0.dp)

        if (maxWidth > leftMaxWidth) {
            Row() {
                Box(
                    modifier = Modifier.weight(1f)
                ) {
                    leftContent(
                        Orientation.Horizontal,
                        PaddingValues.Absolute(
                            left = innerStart,
                            top = innerTop,
                            bottom = innerBottom,
                        )
                    )
                }
                Box(
                    modifier = Modifier.weight(1f)
                ) {
                    content(
                        Orientation.Horizontal,
                        PaddingValues.Absolute(
                            top = innerTop,
                            bottom = innerBottom,
                            right = innerEnd,
                        )
                    )
                }
            }
        } else {
            val scrollableState = rememberScrollState()
            ChainScrollableLayout(
                modifier = modifier,
                state = chainScrollableLayoutState,
            ) { state ->
                Box(
                    modifier = Modifier
                        .animateContentSize()
                        .height(leftMaxHeight)
                        // 偏移与透明度都在布局/绘制阶段读取 state：
                        // 头部高度变化（如视频详情页封面显示/隐藏）以及滚动时都能立即跟随，
                        // 且不再触发每帧重组；透明度分母为 0（番剧详情页左栏高度给 0.dp）时
                        // headerFraction 兜底为 1，不会出现 alpha=NaN 整块不绘制
                        .offset {
                            IntOffset(0, state.getOffsetYValue().roundToInt())
                        }
                        .graphicsLayer {
                            alpha = state.headerFraction
                        }
                        .nestedScroll(state.nestedScroll)
                        .scrollable(scrollableState, Orientation.Vertical),
                ) {
                    leftContent(
                        Orientation.Vertical,
                        PaddingValues.Absolute(
                            top = innerTop,
                            left = innerStart,
                            right = innerEnd,
                        )
                    )
                }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                0,
                                (state.maxPx + state.getOffsetYValue()).roundToInt()
                            )
                        }
                ) {
                    content(
                        Orientation.Vertical,
                        PaddingValues.Absolute(
                            // minScrollPosition 是状态栏高度（WindowInsets 契约上非负），
                            // 一起夹一道，避免任何一边为负时这里抛异常
                            bottom = max(innerBottom + chainScrollableLayoutState.minScrollPosition, 0.dp),
                            left = innerStart,
                            right = innerEnd,
                        )
                    )
                }
            }
        }
    }
}
