package cn.a10miaomiao.bilimiao.compose.pages.live

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowWidthSizeClass
import cn.a10miaomiao.bilimiao.compose.appColorScheme
import cn.a10miaomiao.bilimiao.compose.components.dialogs.AutoSheetDialog

/**
 * 「画质 · 线路」弹窗的**数据模型**（一屏 = 标题 + 若干分段 + 可选的中性按钮）。
 *
 * ★为什么在 compose 模块里定义、而不是复用播放页那两个私有类：播放页在 app 模块、
 *   **没有 Compose 编译器插件**，写不了 `@Composable`；两边只能靠"数据 + View 桥"过话
 *   （与 [LiveSettingSheetHost] / [LiveDanmakuOverlayHost] 同一条老路）。
 * ★模型刻意做成**通用**的（分段 + 单选项），因为它要承载"清晰度 / 线路"这两段，
 *   将来别的两段式单选也能直接复用这一套，不必再写第二个弹窗。
 */
data class LiveOptionEntry(
    /** 主文字（例如 `原画（qn 10000）` / `线路 1　高清`） */
    val label: String,
    /** 第二行说明（折行；没有就不占位置）—— "当前正在播放" / "已请求 · 当前不可用（需登录或大会员）" */
    val note: String?,
    /** 就是当前生效的那一档：打勾 + 主题色高亮 */
    val current: Boolean,
)

/** 一个分段（= 一条 Tab）；只有一个分段时不画 Tab 栏 */
data class LiveOptionSegment(
    val title: String,
    /** 段内"当前是什么"说明（`当前：原画（qn 10000）`），没有就不占位置 */
    val subheading: String?,
    val entries: List<LiveOptionEntry>,
)

/** 一屏要展示的全部内容 */
data class LiveOptionSheetModel(
    val heading: String,
    val segments: List<LiveOptionSegment>,
    /** 底部中性按钮文案（null = 不画）；本弹窗用「重新取流」 */
    val neutralLabel: String? = null,
)

/**
 * 一次"弹出请求"：模型 + 两个回调。
 *
 * ★回调语义与旧的原生弹窗**逐条对齐**（既有行为一条不少）：
 * · [onPick]：**先关弹窗、再应用**（旧实现就是 `dismiss(); onPick(...)`）——
 *   点"当前已经在播的那一档"也一样会关（应用侧自己判断要不要真的重取流）；
 * · [onNeutral]：同样**先关、再执行**；"点完关不关"由调用方在动作里决定（旧语义原样保留）。
 */
class LiveOptionSheetRequest(
    val model: LiveOptionSheetModel,
    val onPick: (segmentIndex: Int, entryIndex: Int) -> Unit,
    val onNeutral: (() -> Unit)? = null,
)

/**
 * ★★2026-10-01：把「画质 · 线路」弹窗**换成与直播间「设置」弹窗同一套外壳**
 *   （用户："这个弹窗为什么不复用？直接用我那个同样的直播间里点击「设置」按钮弹出的弹窗呢？
 *   那个弹窗和我直播 Tab 的筛选弹窗是一样的，**铺满全屏，好像算得很好**。我很喜欢用那个东西。"）。
 *
 * ## 为什么选 [AutoSheetDialog]（而不是 `SingleChoiceDialog` / `OverlayAlertDialog`）
 * | 候选 | 判断 |
 * |---|---|
 * | **`AutoSheetDialog`（本文件用的）** | 就是「直播设置」弹窗（[LiveSettingSheet]）与首页直播 Tab 筛选弹窗**同一个外壳**——用户点名要的那个。窗口尺寸 = 宿主 `decorView` 尺寸、转屏/分屏由外壳自己重设（`DialogFullScreen`），内容高度**由布局引擎算**（见下） |
 * | `SingleChoiceDialog`（`OverlayAlertDialog` 家族） | 是"居中卡片 + RadioButton 列表"，**没有分段**，而且是用户刚抱怨过的那种"手搓弹窗"观感 ⇒ 不用 |
 * | `OverlayCardDialog` | 同上（居中卡片），且它要求内容自己在 `weight(1f)` + LazyColumn 里滚，仍要自己拼分段 |
 *
 * ## 尺寸：一个魔法数字都没有（这正是旧实现的病根）
 * ```
 * 外壳：Dialog 窗口 = 宿主 decorView 尺寸（横竖屏都铺满，转屏自动跟上）
 * 内容：Column { 标题; 说明?; Tab 栏?; LazyColumn(weight(1f)); 按钮行 }
 * ```
 * · `LazyColumn` 的 `weight(1f)` = **吃掉标题/说明/Tab/按钮之外的全部剩余高度** ⇒
 *   "列表多高"由布局引擎分，不再有 `0.62f` / `DIALOG_CHROME_DP` / `72dp 下限` 那套估算；
 * · 按钮行是**定高**且在权重列表**之后** ⇒ 列表永远先让位，按钮永远可见可点（结构保证，不需要算账）；
 * · 列表超过剩余高度就在内部滚（`LazyColumn` 自带）。
 *
 * ## 横屏：两列
 * 横屏屏高只有 1264px（316dp），一屏能给的列表高约 130dp ⇒ 单列一行 33dp 只能看 4 行，
 * 「清晰度」4~8 档要滚好几下。窗口宽度横屏有 700dp ⇒ **宽屏（≥600dp）按两列排**，
 * 行数减半（6 档 = 3 行，正好一屏）。窄屏（竖屏 316dp）保持单列 —— 一列 148dp 放不下
 * "已请求 · 当前不可用（需登录或大会员）"这种说明行。
 */
@Composable
private fun LiveQualityLineSheet(
    request: LiveOptionSheetRequest,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 与 [LiveSettingSheet] 同一套主题读法：本弹窗是独立 Activity 里的一份独立组合，
    // 不套主题的话 AutoSheetDialog 的 surface 会落到 Material3 基线色（深色主题下弹白卡）。
    val themeState = remember { liveSheetThemeState(context) }
    MaterialTheme(colorScheme = appColorScheme(themeState, isSystemInDarkTheme())) {
        AutoSheetDialog(
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
            onDismiss = onDismiss,
        ) {
            val model = request.model
            // 宽屏（横屏/平板）两列：与外壳自己判方向用的是同一个窗口宽度分档
            val wide = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass !=
                WindowWidthSizeClass.COMPACT
            // 分段下标：模型换了要回到第 0 段（例如重开弹窗时数据换了一批）
            var selected by remember(model) { mutableStateOf(0) }
            val entries = model.segments.getOrNull(selected)?.entries.orEmpty()

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = model.heading,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                model.segments.getOrNull(selected)?.subheading?.let { sub ->
                    Text(
                        text = sub,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (model.segments.size > 1) {
                    TabRow(selectedTabIndex = selected) {
                        model.segments.forEachIndexed { index, segment ->
                            Tab(
                                selected = index == selected,
                                onClick = { selected = index },
                                text = { Text(segment.title) },
                            )
                        }
                    }
                }
                // ★权重列表：吃掉标题/说明/Tab/按钮之外的全部剩余高度（横竖屏都不用算像素）
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    if (wide) {
                        // 宽屏两列：两两一格；`IntrinsicSize.Min` 让同一格里的两条**等高**
                        val rowCount = (entries.size + 1) / 2
                        itemsIndexed(List(rowCount) { it }) { _, rowIndex ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                            ) {
                                for (column in 0..1) {
                                    val entryIndex = rowIndex * 2 + column
                                    val entry = entries.getOrNull(entryIndex)
                                    if (entry != null) {
                                        OptionCell(
                                            entry = entry,
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight(),
                                            onClick = {
                                                onDismiss()
                                                request.onPick(selected, entryIndex)
                                            },
                                        )
                                    } else {
                                        // 奇数个条目：最后一格留白，保证左边那条不会被拉成整行
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                        itemsIndexed(entries) { entryIndex, entry ->
                            OptionCell(
                                entry = entry,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    onDismiss()
                                    request.onPick(selected, entryIndex)
                                },
                            )
                        }
                    }
                    item("quality_sheet_bottom") {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                // 底部按钮行：**没有「取消」**（用户明确要求过：点条目即切换并关闭、
                // 点弹窗外/返回键关闭 —— AutoSheetDialog 的外壳默认两者都开，见 AnyPopDialogProperties）。
                if (model.neutralLabel != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    ) {
                        TextButton(
                            onClick = {
                                onDismiss()
                                request.onNeutral?.invoke()
                            },
                        ) {
                            Text(model.neutralLabel)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * 一行选项：`✓ 主文字` + 第二行说明（居中，与旧原生弹窗的排版一致）。
 *
 * 高亮规则与旧实现逐字一致：当前那一档用 `primary`（主题色）+ "✓" 前缀；
 * 其余用 `onSurface`；说明行用 `onSurfaceVariant`（次要文字）。
 */
@Composable
private fun OptionCell(
    entry: LiveOptionEntry,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (entry.current) "✓ ${entry.label}" else entry.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        entry.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 「画质 · 线路」弹窗的 **View 桥**（与 [LiveSettingSheetHost] 同款：app 模块没有 Compose 插件，
 * 只能靠一个常驻 `ComposeView` + 一个 `mutableStateOf` 翻转来开关弹窗）。
 *
 * ★尺寸 `0×0`：弹窗是**独立窗口**，宿主占多大地方完全不影响播放页版式；
 *   但它必须在窗口树里（`Dialog` 需要已 attach 的 `LocalView`）⇒ 播放页把它 addView 进 rootLayout。
 * ★`request == null` 时组合里什么都不做（没点过「画质」的人零开销）。
 */
class LiveQualityLineSheetHost(
    context: Context,
    /** 弹窗**关掉之后**的通知（播放页用它收尾：恢复控制条常显之类）。不是"关闭请求" */
    private val onDismiss: () -> Unit = {},
) : FrameLayout(context) {

    // ★名字不能叫 `request`：下面 `LiveQualityLineSheet(request = current, …)` 里那个参数也叫 request，
    //   在 onDismiss 闭包里会**遮蔽**这个字段（`request.value` 就变成"参数上取 .value" ⇒ 编译不过）。
    private val pendingRequest = mutableStateOf<LiveOptionSheetRequest?>(null)

    private val composeView = ComposeView(context).apply {
        setContent {
            pendingRequest.value?.let { current ->
                LiveQualityLineSheet(
                    request = current,
                    onDismiss = {
                        // 先落状态再回调：回调里播放页会去切流/重取流，
                        // 顺序反过来会出现"已经切了、弹窗还挂在屏上"的那一帧。
                        pendingRequest.value = null
                        onDismiss()
                    },
                )
            }
        }
    }

    init {
        // 自己不吃触摸：0×0 本来也吃不到，这一行是"以后有人改成 MATCH_PARENT 也不会挡住手势层"的保险
        isClickable = false
        addView(
            composeView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
    }

    /** 弹出（幂等：已经在屏上就换成新内容） */
    fun show(newRequest: LiveOptionSheetRequest) {
        pendingRequest.value = newRequest
    }

    /** 收起（幂等） */
    fun dismiss() {
        if (pendingRequest.value != null) pendingRequest.value = null
    }

    /** 此刻在不在屏上（播放页诊断日志用；只读） */
    fun isShowing(): Boolean = pendingRequest.value != null

    /** 彻底释放（播放页 `onDestroy`）：ComposeView 一 detach 就 dispose 组合 ⇒ 弹窗窗口随之收掉 */
    fun release() {
        pendingRequest.value = null
        runCatching { removeAllViews() }
    }
}
