package cn.a10miaomiao.bilimiao.compose.components.layout

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
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
            val density = LocalDensity.current
            val leftMaxHeightPx = remember(density, leftMaxHeight, chainScrollableLayoutState.minScrollPosition) {
                density.run {
                    leftMaxHeight.roundToPx().toFloat() - chainScrollableLayoutState.minScrollPosition.roundToPx()
                }
            }
            val scrollableState = rememberScrollState()
            ChainScrollableLayout(
                modifier = modifier,
                state = chainScrollableLayoutState,
            ) { state ->
                // 分母可能为 0（番剧详情页左栏高度给的是 0.dp）→ 0/0=NaN，
                // .alpha(NaN) 会导致整块内容不绘制；这里兜底并夹到 0..1
                val alpha = if (leftMaxHeightPx > 0f) {
                    ((leftMaxHeightPx + state.getOffsetYValue()) / leftMaxHeightPx).coerceIn(0f, 1f)
                } else {
                    1f
                }
                val offsetY by animateIntAsState(
                    targetValue = state.getOffsetYValue().roundToInt(), label = "",
                )
                Box(
                    modifier = Modifier
                        .animateContentSize()
                        .height(leftMaxHeight)
                        .offset {
                            IntOffset(0, offsetY)
                        }
                        .alpha(alpha)
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
                                state.maxPx.roundToInt() + offsetY
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
//                            top = density.run { (state.maxPx + state.getOffsetYValue()).toDp() }
                        )
                    )
                }
            }
        }
    }
}