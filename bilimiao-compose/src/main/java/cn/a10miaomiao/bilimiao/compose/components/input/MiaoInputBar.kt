package cn.a10miaomiao.bilimiao.compose.components.input

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「输入框 + 发送按钮」的统一实现（AGENTS §2.15：同一交互不许开第二份）。
 *
 * ## 用户要的三件事（2026-09-28）
 * ① "私信那个点缀主题色的描边很不错；但发送按钮和输入框两边顶到屏幕了……把描边也加在评论区那一个
 *   （评论区边距做得好、但没有描边）"；
 * ② "发送按钮黑色主题下完全看不见，和背景融在一起，我不知道哪边是输入框"；
 * ③ "把「发布」改成「发送」，私信界面就改成发送，复用它的小飞机图标"。
 *
 * ## 本仓只有两处是这套交互（都改成用本文件，不留第二份）
 * · 私信：`pages/message/ChatPage.kt` 的 `ChatSendPanel`（`MiaoInputField` + `MiaoSendButton`）；
 * · 评论/回复：`pages/community/components/ReplyEditDialog.kt`（同上）。
 * 其它输入框**不是**这套交互，故意不迁移（理由逐条写在任务报告里）：
 * 搜索框（`components/input/SearchBox.kt`、`DownloadListPage` 的搜索栏）没有发送动作；
 * 登录/验证码/申诉/屏蔽词/设置项都是**弹窗或页面内的表单**（确认按钮在弹窗按钮行里，规则 3 管），
 * 不是"输入条 + 发送"；弹幕发送页是另一套（见 `SendDanmakuPage`，本轮只报告不动它）。
 *
 * ## 可见性依据（浅色/深色两套都要"一眼可辨"）
 * · **未聚焦描边 = `outline`**：浅色档 ≈ 中性 tone 50，压在近白 `surface` 上对比度 ≈4.3:1；
 *   深色档 ≈ tone 60，压在近黑 `surface` 上 ≈6.6:1 —— 都 ≥ 3:1（WCAG 2.2 SC 1.4.11 非文本对比度）。
 *   私信原来用的是 `outlineVariant`（浅色 tone 80 ≈1.7:1、深色 tone 30 ≈2.2:1），
 *   **深色档那条 2.2:1 的后果就是看不出哪边是输入框**。
 * · **聚焦描边 / 光标 / 工具按钮激活态 = `primary`**（主题色那条描边）。
 * · **发送按钮不填充**：`TextButton` + 小飞机图标。禁用态是 M3 的 `onSurface 38%`
 *   （深色近黑底上 ≈3.2:1，看得见）；不再是 `primaryContainer` **填充块**
 *   （深色档 primaryContainer ≈ tone 30，与近黑 sheet 几乎同色，等于看不见）。
 *
 * ## 统一的数值
 * · 圆角 8dp（规则 9：列表项/输入框 6–8dp，两处原来都是 8dp）；
 * · 内边距 16dp（规则 10 的四档之一）由**调用方**给（输入条左右各 16dp），本文件只管输入框本身；
 * · 文字 15sp / `bodyMedium`，占位符 `onSurfaceVariant`（规则 7：次要文字）。
 */
private val InputShape = RoundedCornerShape(8.dp)

/**
 * 统一输入框：M3 [OutlinedTextField] + 上表那套语义色。
 *
 * @param minHeight 最小高度（评论框 90dp、私信框 80dp —— 各自沿用原来的"能写几行"体感）
 * @param maxHeight 最大高度，超过后输入框内部自己滚（不再顶掉下面的工具行）
 * @param maxLines  最多显示几行（私信 5 行；评论不限、由 [maxHeight] 封顶）
 */
@Composable
fun MiaoInputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    focusRequester: FocusRequester? = null,
    minHeight: Dp = 0.dp,
    maxHeight: Dp = 160.dp,
    maxLines: Int = Int.MAX_VALUE,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    // min 必须 ≤ max，否则 heightIn 的约束自相矛盾（调用方传错也不该崩）
    val safeMin = minHeight.coerceIn(0.dp, maxHeight)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = safeMin, max = maxHeight)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        placeholder = {
            Text(
                text = placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
            )
        },
        shape = InputShape,
        maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
        ),
        keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            // 容器与外壳同色（不叠第二层背景），靠描边把"这里能打字"说清楚
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}

/**
 * 统一发送按钮：**不填充**的 [TextButton] + 小飞机图标 + 文案「发送」。
 *
 * @param loading 发送/上传中：图标换成转圈（沿用两处原来的行为，转圈颜色跟随按钮 contentColor）
 */
@Composable
fun MiaoSendButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    text: String = "发送",
) {
    TextButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(text = text, fontSize = 14.sp)
        }
    }
}

/** 输入条上工具按钮（表情 / 图片）的尺寸：44dp（M3 最小可点区，两处统一用这一档） */
private val ToolButtonSize = 44.dp

/**
 * 输入条上的**工具按钮**（表情 / 图片选择）：私信与评论区**共用这一份**（AGENTS §2.15）。
 *
 * 需求（2026-09-29）：表情 / 图片这两颗按钮以**私信界面那一套**为准，两处统一 ——
 * 图标用同一对、尺寸也一致。
 * ⇒ 两处的差别只有两点，都在这里定死：
 * · **线框图标**（图标由调用方传 `Icons.Outlined.*`，两处传同一对）；
 * · **[IconButton] + 44dp**（原来是 `TextButton(size(44.dp))`，视觉上图标偏小）；
 * 颜色规则：默认 `onSurfaceVariant`（规则 7 的次要图标色），**激活时 `primary`**（表情面板展开）。
 *
 * @param active 该工具当前处于展开/激活态（表情面板开着）→ 图标转 `primary`
 * @param loading 正在上传：图标换成转圈（私信发图期间用；数量/预览条仍由各页面自己管）
 */
@Composable
fun MiaoInputToolButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(ToolButtonSize),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
