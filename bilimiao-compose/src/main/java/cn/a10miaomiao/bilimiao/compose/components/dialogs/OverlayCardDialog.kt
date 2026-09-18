package cn.a10miaomiao.bilimiao.compose.components.dialogs

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** 遮罩颜色：与 comm 层 `OverlayDialog` 的 0x99000000 保持一致 */
private val SCRIM_COLOR = Color.Black.copy(alpha = 0.6f)

/**
 * **全屏覆盖层弹窗（Compose 版）** —— 与 comm 层的 `OverlayDialog` 行为一致，
 * 也是首页筛选弹层（`AnyPopDialog`）那套"能用的机制"。
 *
 * 为什么需要它（而不是直接用 Material3 的 `AlertDialog`）：
 * 1. `AlertDialog` 内容超高时，wrap 窗口会被内容撑出屏幕，按钮跑到屏幕外点不到；
 *    这里卡片高度**封顶宿主窗口的 maxHeightRatio**，超出部分内部滚动。
 * 2. 卡片宽高按**当前**窗口尺寸实时计算（`BoxWithConstraints` 的 maxWidth/maxHeight），
 *    配合 `usePlatformDefaultWidth = false` 让弹窗窗口是 `MATCH_PARENT`（不是写死的像素）。
 *    —— 本 App 在 Manifest 里声明了 `configChanges=orientation|screenSize|...`，
 *    **旋转时 Activity 不会重建**，任何"在 show() 那一刻把像素尺寸写进 LayoutParams"的弹窗
 *    都会永久停在旧方向的几何上（看到的和点到的不是一个地方）；全屏窗口没有这个问题。
 *
 * @param maxWidthRatio 卡片最大宽度占窗口比例（横屏不会超宽）
 * @param maxHeightRatio 卡片最大高度占窗口比例（超出部分内容自己滚）
 * @param scrollable 卡片内容是否整体可滚动；内容自己管滚动（如"正文滚动 + 底部按钮固定"）时传 false
 * @param content 卡片内容（ColumnScope）
 */
@Composable
fun OverlayCardDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidthRatio: Float = 0.94f,
    maxWidthDp: Dp = 560.dp,
    maxHeightRatio: Float = 0.88f,
    cancelOnClickOutside: Boolean = true,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            // 窗口铺满屏幕（MATCH_PARENT 写进 LayoutParams，不是像素）→ 旋转/分屏永远贴合屏幕
            usePlatformDefaultWidth = false,
            // 让内容铺到状态栏/导航栏下面，遮罩才是全屏的
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = cancelOnClickOutside,
        ),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.apply {
                // 遮罩自己画，别让系统再叠一层
                setDimAmount(0f)
                // 全屏窗口 + 输入法：让窗口自己缩，别被顶跑偏（与 OverlayDialog 同一处理）
                setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                )
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(SCRIM_COLOR)
                // 输入框弹窗：键盘弹出时把卡片顶上去。
                // （窗口已设 ADJUST_RESIZE，两者不会叠加：窗口一缩，ime inset 就变 0）
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            // 尺寸取**当前窗口**的实时值：旋转后 BoxWithConstraints 会重新测量，卡片跟着变
            val cardMaxHeight = maxHeight * maxHeightRatio
            Scrim(
                onClick = { if (cancelOnClickOutside) onDismissRequest() },
            )
            Surface(
                modifier = modifier
                    // 先封顶宽度（横屏不拉成一条），再取窗口比例（竖屏跟随屏幕变窄）
                    .widthIn(max = maxWidthDp)
                    .fillMaxWidth(maxWidthRatio)
                    .heightIn(max = cardMaxHeight)
                    // 空 clickable：吃掉落在卡片上的点击，否则会穿透到遮罩把弹窗关掉
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                val contentModifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)
                if (scrollable) {
                    Column(
                        modifier = contentModifier.verticalScroll(rememberScrollState()),
                        content = content,
                    )
                } else {
                    Column(modifier = contentModifier, content = content)
                }
            }
        }
    }
}

/**
 * `Material3 AlertDialog` 的覆盖层版本：**参数名与 AlertDialog 一致**，
 * 所以把调用处的 `AlertDialog(` 换成 `OverlayAlertDialog(` 基本就能直接用。
 *
 * 差别：窗口铺满屏幕、卡片居中且高度封顶（正文区域滚动，**底部按钮始终固定在卡片里**，不会被顶出屏幕）。
 */
@Composable
fun OverlayAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    maxWidthRatio: Float = 0.94f,
    maxHeightRatio: Float = 0.88f,
) {
    OverlayCardDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        maxWidthRatio = maxWidthRatio,
        maxHeightRatio = maxHeightRatio,
        scrollable = false,
    ) {
        icon?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) { it() }
        }
        title?.let {
            Box(modifier = Modifier.padding(bottom = 10.dp)) { it() }
        }
        text?.let {
            // weight(fill = false)：正文最多占满剩余高度，超出自己滚；短内容则按内容高
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) { it() }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dismissButton?.let {
                Box(modifier = Modifier.padding(end = 4.dp)) { it() }
            }
            confirmButton()
        }
    }
}

/** 半透明遮罩：铺满屏幕、吃掉所有点击（点空白关弹窗） */
@Composable
private fun BoxScope.Scrim(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
    )
}
