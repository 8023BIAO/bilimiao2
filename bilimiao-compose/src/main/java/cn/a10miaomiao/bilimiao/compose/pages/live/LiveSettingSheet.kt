package cn.a10miaomiao.bilimiao.compose.pages.live

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cn.a10miaomiao.bilimiao.compose.appColorScheme
import cn.a10miaomiao.bilimiao.compose.common.preference.rememberPreferenceFlow
import cn.a10miaomiao.bilimiao.compose.components.dialogs.AutoSheetDialog
import cn.a10miaomiao.bilimiao.compose.pages.setting.liveSettingPreferenceItems
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.store.AppStore
import me.zhanghai.compose.preference.ProvidePreferenceLocals

/**
 * 主色兜底值：`AppStore.init()` 里那一句 `it[ThemeColor] ?: 0xFF2196F3` 的同一个数。
 *
 * ★为什么在本文件里定义、而不是塞进 `SettingConstants`：那个文件是**禁改区**（键与默认值的唯一
 *   定义处，另一个 agent 在维护）；而且这**不是一条设置项**，只是"主题键读不到时先按这个颜色画"的
 *   本地兜底。写成常量并在这里注明出处，改 `AppStore` 那一行的人搜 `2196F3` 就能找到这两处。
 */
private const val SHEET_THEME_FALLBACK_COLOR = 0xFF2196F3.toInt()

/**
 * ★★第十四批：直播播放页底栏「设置」按钮弹出来的**直播设置弹窗**（Compose）+ 它的 View 桥。
 *
 * ## 用户原话（2026-09-26）
 * > "我们在直播间底栏，画质的后面、PIP 的中间添加一个按钮，就是设置。就是会弹出直播间的设置选项、
 * >  设置页。**记得用我的那个自定义的全屏弹窗，不管你怎么转屏，它都会自己适配。就是我的那个底栏筛选的那个弹窗。**"
 *
 * ## 宿主用的是哪一套：与「底栏筛选弹窗」**同一个**
 * ```
 * HomeLiveFilterSheet.kt（首页直播 Tab 底栏「筛选」）
 *   └─ AutoSheetDialog  ← 同一套外壳（窄屏从底部升起、宽屏居中一张 600dp 卡片）
 *        └─ AnyPopDialog → DialogFullScreen → androidx.compose.ui.window.Dialog
 *             └─ ★转屏自适应就落在这里：DialogFullScreen 给宿主 decorView 挂了
 *                OnLayoutChangeListener，宿主尺寸一变就 dialogWindow.setLayout(w, h)
 *                （AnyPopDialog.kt:139-164 —— 正是因为本 App 声明了 configChanges=orientation，
 *                 旋转**不重建 Activity**，光靠重组拿到的还是旧方向的宽高）
 * ```
 * 本文件**不新造任何弹窗样式**：外壳就是 `AutoSheetDialog`，排版也与
 * `HomeLiveFilterSheet` 一致（`Column(fillMaxWidth)` → 可滚动内容吃 `weight(1f)`
 * → 底部一行按钮钉在下面；横向 16dp 内边距由标题与底部按钮各自带，条目自带 ——
 * 与「设置 → 直播设置」页逐像素同款）。差别只有两处，都是内容决定的：
 *   ① 内容项来自 [liveSettingPreferenceItems]（设置项，不是筛选 chip）；
 *   ② 底部只有一个「完成」——设置是**改一项立即生效并落盘**的，没有"待确认"这一说
 *      （筛选弹窗要「重置/取消/确定」是因为那三个条件要一起提交）。
 *
 * ## 为什么播放页不自己写 Compose，而是用这个"桥 View"
 * `LivePlayerActivity` 在 `app` 模块，而 `app/build.gradle.kts` **没有** Compose 编译器插件
 * （`buildFeatures { compose = true }` 只在 `bilimiao-compose` 里开着，且本任务明令禁改那个文件）——
 * 在 app 模块里写 `@Composable` 一律编译不过。所以照本页已有的那套：
 * `LiveDanmakuOverlayHost`（同目录）= "compose 模块里的 View 桥"，播放页只会 `new` 它、调它的方法。
 * 本类就是设置弹窗那一半：播放页 `show()` / `dismiss()` / `release()`，一个 Compose 符号都不用碰。
 *
 * ## 转屏与 PiP
 * · **转屏**：靠上面那条 `AutoSheetDialog`/`AnyPopDialog` 的既有机制（Dialog 窗口跟着宿主
 *   decorView 尺寸重设），本页只管"弹不弹"；内容用 `LazyColumn(weight(1f))`，横屏 800dp 宽时
 *   外壳自己切成"居中 600dp 卡片"（`WindowWidthSizeClass != COMPACT` → `DirectionState.NONE`），
 *   竖向空间不够就在卡片里滚动，不会溢出。
 * · **PiP**：播放页在 `isInPictureInPictureMode` 时既不弹（[com.a10miaomiao.bilimiao.LivePlayerActivity]
 *   的门控）、进小窗时也会把已经打开的这个弹窗关掉 —— 小窗里没有它的位置。
 *
 * ## 设置项本身
 * 全部来自 [liveSettingPreferenceItems]（`LiveSettingPreferences.kt`）：**与「设置 → 直播设置」页
 * 是同一份项、同一批键、同一批默认值、同一套读写口**（`ProvidePreferenceLocals` +
 * `rememberPreferenceFlow(dataStore)`），见那个函数的 KDoc。本文件一个键名都没写。
 */
class LiveSettingSheetHost(
    context: Context,
    /**
     * 弹窗**关掉之后**的通知（播放页用它把"改完的设置"再下发一次给播放核心）。
     * 不是"关闭请求"：调用方不需要（也不该）在这里再 dismiss 一次。
     */
    private val onDismiss: () -> Unit = {},
) : FrameLayout(context) {

    /**
     * 弹窗该不该在屏上。用 Compose 的 `mutableStateOf`（与 `LiveDanmakuOverlayHost.active` 同款）：
     * 播放页在**主线程**写它，重组在下一帧统一发生，不需要额外的 Flow/观察者。
     */
    private val shown = mutableStateOf(false)

    /**
     * 桥的本体：一个**常驻**的 ComposeView。
     *
     * ★尺寸给 `0×0`：弹窗是**独立窗口**（`Dialog`），跟这个 View 在页面里占多大地方毫无关系；
     *   给 0 就绝对不会影响播放页的版式（播放页的每一层位置都是算出来的）。
     *   而它必须在窗口里 —— `Dialog` 需要一个已 attach 的 `LocalView` 才能建窗口，
     *   所以播放页会把它 addView 进 rootLayout（见 [com.a10miaomiao.bilimiao.LivePlayerActivity]）。
     * ★`shown == false` 时组合里**什么都不做**（一个 `if` 而已）：没点过「设置」的人，
     *   这个宿主除了一个空 ComposeView 之外没有任何开销。
     */
    private val composeView = ComposeView(context).apply {
        setContent {
            if (shown.value) {
                LiveSettingSheet(
                    onDismiss = {
                        // 先落状态再回调：回调里播放页会去读设置、重下发播放策略，
                        // 顺序反过来会出现"策略已重下发、弹窗还挂在屏上"的那一帧。
                        shown.value = false
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

    /** 弹出设置弹窗（幂等）。播放页必须**在 PiP 之外**调用（门控在播放页那一侧，见类注释） */
    fun show() {
        if (!shown.value) shown.value = true
    }

    /** 收起设置弹窗（幂等）：进 PiP、页面销毁、或另一颗按钮要弹自己的弹窗时调 */
    fun dismiss() {
        if (shown.value) shown.value = false
    }

    /** 弹窗此刻在不在屏上（播放页的诊断日志用；只读） */
    fun isShowing(): Boolean = shown.value

    /**
     * 彻底释放（播放页 `onDestroy`）：把桥摘下来。
     * ComposeView 一 detach 就会 dispose 这份组合 → `Dialog` 的 `onDispose` 把窗口收掉，
     * 所以"页面没了、弹窗还挂在屏幕上"这种事不会发生。
     */
    fun release() {
        shown.value = false
        runCatching { removeAllViews() }
    }
}

/**
 * 弹窗本体（只在 [LiveSettingSheetHost] 的组合里用，所以是 private）。
 *
 * 结构照 `HomeLiveFilterSheet`：外壳 [AutoSheetDialog] + 顶部标题 + **吃满剩余高度的可滚动内容**
 * + 底部一行按钮。
 */
@Composable
private fun LiveSettingSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dataStore = remember {
        SettingPreferences.run { context.dataStore }
    }
    // ★主题：这里是**独立 Activity** 里的一份独立组合（不像 App 其它 Compose 页面那样在
    //   `ComposeFragment` 的 `BilimiaoTheme` 里面），不套主题的话 `AutoSheetDialog` 的
    //   `MaterialTheme.colorScheme.surface` 会落到 Material3 基线色 —— 深色主题下弹出一张白卡。
    //   色板仍然用**同一个** `appColorScheme()`（调色算法只有一份），只是"读主题键"那几行要自己来，
    //   原因见 [liveSheetThemeState]。
    val themeState = remember { liveSheetThemeState(context) }
    MaterialTheme(colorScheme = appColorScheme(themeState, isSystemInDarkTheme())) {
        // 读写同一份：与「设置 → 直播设置」页**完全同一个口**（DataStore 的 preferences flow）。
        // 改一项 → 写 DataStore → 直播弹幕浮层订阅的 `live_danmaku_*` 当场生效。
        ProvidePreferenceLocals(flow = rememberPreferenceFlow(dataStore)) {
            AutoSheetDialog(
                modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                onDismiss = onDismiss,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    // ★标题与说明自己带 16dp 横向内边距、**不给整个 Column 加**：
                    //   `me.zhanghai.compose.preference` 的每一行自带 16dp 横向内边距（设置页那边
                    //   也是这么排的），再给 Column 加一层就会让"标题在 16dp、条目文字在 32dp"，
                    //   两处对不齐。这样排出来与「设置 → 直播设置」页逐像素同款。
                    Text(
                        text = "直播设置",
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "与「设置 → 直播设置」是同一批设置：改一项立即生效，也会存下来",
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 可滚动内容区：吃满剩余高度（弹窗顶边到底部按钮之间），横屏/小屏也滑得动
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        liveSettingPreferenceItems()
                        // 最后一项与底部按钮之间的呼吸位（列表最后一行贴着按钮会看着很挤）
                        item("sheet_bottom") {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    // 底部按钮：右侧一个「完成」（筛选弹窗是「重置 / 取消 / 确定」三颗 ——
                    // 设置项没有"待确认"语义，改一项就已经生效并落盘了，所以只留一个出口）。
                    // 左右各自 16dp：与标题、与条目文字的左边距对齐（同筛选弹窗那一行按钮的排版）。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) {
                            Text("完成")
                        }
                    }
                }
            }
        }
    }
}

/**
 * **直播播放页上这几份独立组合**要用的主题状态（`AppStore.ThemeSettingState`）。
 *
 * ★可见性（2026-09-26）：`internal` —— 直播设置弹窗（本文件的 [LiveSettingSheet]）与
 * **竖屏弹幕列表面板**（`LiveDanmakuOverlayHost.ensureListPanel`，本轮改成"用户名跟随主题色"
 * 时也要这一份）是同一个 Activity 里的两份独立 ComposeView，主题键只读这一处，
 * 不各自抄一遍（抄两遍迟早会和 `AppStore.init()` 漂移）。
 *
 * ## 为什么要自己组装一次（而不是像别的 Compose 页面那样读 `AppStore.stateFlow`）
 * 直播播放页是**独立 Activity**、没有 Store/DI（`LivePlayerActivity` 那段"主题配色"KDoc 里
 * 写得很清楚：那要 `store.loadStoreModules()` 一整套），拿不到 `AppStore` 这个 ViewModel；
 * 而工程里**没有**"从 Preferences 组装 ThemeSettingState"的公共函数（`AppStore.init` 里是内联写的）。
 * 所以这里按**同一份键**读一次（`theme_color` / `theme_type` / `theme_dark_mode` /
 * `theme_app_bar_type` / `theme_custom_*`），默认值与兜底也与 `AppStore.init()` 逐字对齐 ——
 * **色板本身仍然只由 `appColorScheme()` 一份算法算**，重复的只有"读键"这几行。
 *
 * 读的是 `SettingPreferences.cachedPreferencesOrNull()`（进程级内存快照，主线程 O(1)、不阻塞），
 * 与播放页 `resolveThemeColors()` 用的是同一个口子；快照还没热时返回的默认值与
 * `AppStore.init()` 的兜底一致（0xFF2196F3 / 跟随系统深浅色）。
 *
 * 只在各自的组合**第一次组合**时读一次（`remember {}`）：这两处都没有主题项，活着的时候主题不会变，
 * 不需要订阅。关掉再开 / 重进直播间就是新的一份（换过主题后立即是新配色）。
 */
internal fun liveSheetThemeState(context: Context): AppStore.ThemeSettingState {
    val prefs = SettingPreferences.cachedPreferencesOrNull()
    val themeType = prefs?.get(SettingPreferences.ThemeType)
        ?: SettingConstants.THEME_TYPE_DEFAULT
    // 主色：Material You 动态取色时跟系统色（与 `AppStore.materialYouColor` 同一支系统色），
    // 否则就是用户存下的 `theme_color`（自定义主题也写在这同一个键里）
    val color = if (themeType == SettingConstants.THEME_TYPE_DYNAMIC_COLOR) {
        runCatching {
            ContextCompat.getColor(context, android.R.color.system_primary_light)
        }.getOrDefault(SHEET_THEME_FALLBACK_COLOR)
    } else {
        (prefs?.get(SettingPreferences.ThemeColor)
            ?: SHEET_THEME_FALLBACK_COLOR.toLong()).toInt()
    }
    return AppStore.ThemeSettingState(
        color = color,
        type = themeType,
        darkMode = prefs?.get(SettingPreferences.ThemeDarkMode) ?: 0,
        appBarType = prefs?.get(SettingPreferences.ThemeAppBarType) ?: 0,
        customPrimary = prefs?.get(SettingPreferences.ThemeCustomPrimary),
        customSecondary = prefs?.get(SettingPreferences.ThemeCustomSecondary),
        customTertiary = prefs?.get(SettingPreferences.ThemeCustomTertiary),
    )
}
