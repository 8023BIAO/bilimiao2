package cn.a10miaomiao.bilimiao.compose.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.isAppThemeDark
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.flow.stateMap
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.preference.rememberPreferenceFlow
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.pages.setting.components.CustomThemeColorDialog
import cn.a10miaomiao.bilimiao.compose.pages.setting.components.ThemeColorButton
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences.dataStore
import com.a10miaomiao.bilimiao.comm.platform.isMaterialYouSupported
import com.a10miaomiao.bilimiao.comm.store.AppStore
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

/**
 * 主题色列表里"自定义"那一项的哨兵 key。
 *
 * 沿用 Material You（0x100000000）的老写法：用一个超出 32 位真彩色范围的值当 id，
 * 既不会和任何真实颜色撞车，也能继续用 `List<Pair<Long, String>>` 装这一列。
 */
private const val CUSTOM_THEME_COLOR_KEY = 0x200000000L

/**
 * 「Material You」那一项的哨兵 key。
 *
 * 关键：它只用来**标记选项**，绝不能当颜色用 —— `Color(0x100000000L)` 取低 32 位是全透明黑。
 * 真正的系统主色在点选后由 `AppStore.setThemeColor` / [AppStore.materialYouColor] 提供。
 * 与自定义哨兵一样超出 32 位，所以判断必须用 `==`，不能用 `>`（会把两个哨兵互相误判）。
 */
private const val MATERIAL_YOU_COLOR = 0x100000000L

@Serializable
class ThemeSettingPage : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: ThemeSettingPageViewModel = diViewModel()
        ThemeSettingPageContent(viewModel)
    }

}

private class ThemeSettingPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val appStore by instance<AppStore>()
    private val pageNavigation by instance<PageNavigation>()

    val darkModeList = listOf(
        0 to "跟随系统",
        1 to "关闭",
        2 to "打开"
    )
    val darkModeListSize get() = darkModeList.size

    val appBarTypeList = listOf(
        0 to "主题颜色",
        1 to "纯色",
    )
    val appBarTypeListSize get() = appBarTypeList.size

    /** 系统主色（Material You）：色块用它上色，取不到时由 AppStore 回退默认色 */
    val materialYouColor get() = appStore.materialYouColor

    private val defaultColorList = listOf<Pair<Long, String>>(
        0xFF2196F3 to "胖次蓝",
        0xFFFB7299 to "少女粉",
        0xFFFDD835 to "咸蛋黄",
        0xFFFF9800 to "猫猫橙",
        0xFF673AB7 to "元气紫",
        0xFF4CAF50 to "早苗绿",
        0xFFF44336 to "麻衣红",
        0xFF39C5BB to "初音绿",
        0xFF66CCFF to "天依蓝",
    )

    /**
     * 主题色候选：预设色 +（Android 12+ 才有）Material You + 自定义。
     *
     * ★Material You 仅 [isMaterialYouSupported] 时展示：低版本系统没有系统动态配色，
     *   露一个选了不生效的选项等于骗用户（上游 f9cc3474 同款做法）。
     *   写在 if/else 两支里（不用列表拼接）—— 分支类型一目了然，也避开 `emptyList()` 的推断问题。
     */
    val colorList: List<Pair<Long, String>> =
        if (isMaterialYouSupported) {
            defaultColorList + (MATERIAL_YOU_COLOR to "Material You") + (CUSTOM_THEME_COLOR_KEY to "自定义")
        } else {
            defaultColorList + (CUSTOM_THEME_COLOR_KEY to "自定义")
        }

    val themeState = appStore.stateFlow.stateMap {
        it.theme ?: AppStore.ThemeSettingState(
            color = 0xFF2196F3.toInt(),
        )
    }

    fun setDarkMode(mode: Int) {
        appStore.setDarkMode(mode)
        // 0 跟随系统，1 关闭，2 打开
//        if (mode == 0) {
//            windowStore.setSystemUiMode()
//        } else {
//            windowStore.setDarkMode(mode == 2)
//        }
    }

    fun setAppBarType(type: Int) {
        appStore.setAppBarType(type)
    }

    fun setThemeColor(color: Long) {
        val type = when (color) {
            // ★哨兵用"等于"比较：自定义哨兵 0x200000000 也在 32 位之外，用 > 判会互相误判
            MATERIAL_YOU_COLOR -> SettingConstants.THEME_TYPE_DYNAMIC_COLOR
            else -> SettingConstants.THEME_TYPE_DEFAULT
        }
        appStore.setThemeColor(color, type)
    }

    /**
     * 保存自定义三色（第 11 项弹窗点"保存"）。
     * 走 AppStore.setCustomThemeColor：同时落"当前生效的主题"和"自定义存的那一份"。
     */
    fun setCustomThemeColor(primary: Int, secondary: Int, tertiary: Int) {
        appStore.setCustomThemeColor(primary, secondary, tertiary)
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSettingPageContent(
    viewModel: ThemeSettingPageViewModel
) {
    PageConfig(
        title = "主题设置"
    )
    // 第 11 项"自定义"的调色弹窗开关。放在页面本地（和 TextIntPreference / DpiSettingDialog 一个套路）：
    // 宿主 Activity 声明了 configChanges，转屏不重建，remember 足够稳，不必塞进 ViewModel。
    var showCustomColorDialog by remember { mutableStateOf(false) }
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())
    val themeState by viewModel.themeState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = windowInsets.toPaddingValues(),
    ) {
        item {
            Column(
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                )
            ) {
                Text(
                    text = "深色模式",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(
                        bottom = 8.dp,
                    ),
                )
                SingleChoiceSegmentedButtonRow {
                    viewModel.darkModeList.forEachIndexed { index, mode ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = viewModel.darkModeListSize,
                            ),
                            onClick = {
                                viewModel.setDarkMode(mode.first)
                            },
                            selected = index == themeState.darkMode,
                            modifier = Modifier.width(IntrinsicSize.Max),
                        ) {
                            Text(
                                text = mode.second,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
        item {
            Column(
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                )
            ) {
                Text(
                    text = "应用操作栏风格",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(
                        bottom = 8.dp,
                    ),
                )
                SingleChoiceSegmentedButtonRow {
                    viewModel.appBarTypeList.forEachIndexed { index, type ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = viewModel.appBarTypeListSize,
                            ),
                            onClick = {
                                viewModel.setAppBarType(type.first)
                            },
                            selected = index == themeState.appBarType,
                            modifier = Modifier.width(IntrinsicSize.Max),
                        ) {
                            Text(
                                text = type.second,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
        item {
            Column(
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                )
            ) {
                Text(
                    text = "主题颜色",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(
                        bottom = 8.dp,
                    ),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val isCustomActive = themeState.type == SettingConstants.THEME_TYPE_CUSTOM
                    viewModel.colorList.forEach { color ->
                        val colorValue = color.first
                        val isCustomColor = colorValue == CUSTOM_THEME_COLOR_KEY
                        // ★哨兵一律用"等于"判断（上游 f9cc3474）：Material You 与自定义的哨兵都在
                        //   32 位之外，以前用 `> 0xFFFFFFFF` 只能靠"再排掉自定义"兜住，容易漏。
                        val isDynamicColor = colorValue == MATERIAL_YOU_COLOR
                        ThemeColorButton(
                            onClick = {
                                if (isCustomColor) {
                                    // 没保存过 → 弹窗默认"当前主题色 + 副色/点缀色跟随主色"；
                                    // 保存过 → 弹窗带着上次的三色继续改（默认值在下面组装）
                                    showCustomColorDialog = true
                                } else {
                                    viewModel.setThemeColor(colorValue)
                                }
                            },
                            baseColor = when {
                                // 第 11 项按"用户自己那套"上色：没存过就跟着当前主题色，
                                // 存过就用自定义主色（这样切到预设色后，这一格依旧显示自定义长什么样）
                                isCustomColor -> Color(themeState.customPrimary ?: themeState.color)
                                isDynamicColor -> Color(viewModel.materialYouColor)
                                else -> Color(colorValue)
                            },
                            selected = when {
                                isCustomColor -> isCustomActive
                                isDynamicColor -> themeState.type == SettingConstants.THEME_TYPE_DYNAMIC_COLOR
                                // 预设色保持原来的"颜色值相等就高亮"；只加一条：自定义生效时不再跟着亮，
                                // 否则自定义主色恰好等于某个预设色（很常见，比如就是喜欢胖次蓝）会同时选中两个
                                else -> !isCustomActive && themeState.color == colorValue.toInt()
                            },
                            colorName = color.second,
                        )
                    }
                }
            }
        }

    }

    if (showCustomColorDialog) {
        // 默认值：没保存过自定义 → 主色 = 当前主题色，副色/点缀色 = 跟随主色（同值，
        // 弹窗里显示成"跟随主色"，保存后也不覆盖调色板）；保存过 → 带着上次的三色继续改
        val savedPrimary = themeState.customPrimary ?: themeState.color
        CustomThemeColorDialog(
            initialPrimary = savedPrimary,
            initialSecondary = themeState.customSecondary ?: savedPrimary,
            initialTertiary = themeState.customTertiary ?: savedPrimary,
            // 预览要按"当前实际明暗"生成，否则深色下预览的是浅色配色，保存完发现不是那个样子
            darkTheme = isAppThemeDark(),
            onDismiss = { showCustomColorDialog = false },
            onSave = { primary, secondary, tertiary ->
                viewModel.setCustomThemeColor(primary, secondary, tertiary)
                // 关掉弹窗回到主题页：主题页在 MaterialTheme 里，AppStore.state 一变就整体换色
                showCustomColorDialog = false
            },
        )
    }
}