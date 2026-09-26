package cn.a10miaomiao.bilimiao.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.store.AppStore
import com.materialkolor.rememberDynamicColorScheme

@Composable
fun BilimiaoTheme(
    appState: AppStore.State,
    systemDark: Boolean,
    content: @Composable () -> Unit
) {
    val themeState = appState.theme ?: return
    MaterialTheme(
        colorScheme = appColorScheme(themeState, systemDark),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
    }
}

/**
 * 当前主题是不是深色。
 *
 * 判定依据是 MaterialTheme **实际给出的 surface** 亮度，所以"跟随系统 / 强制浅色 / 强制深色"
 * 三种设置都算得对（themeState.darkMode 1/2 时与系统 uiMode 无关）。
 *
 * 给那些拿不到 ColorScheme、只要一个布尔值的地方用 —— 典型是弹窗窗口的
 * `isAppearanceLightNavigationBars` / `isAppearanceLightStatusBars`：
 * 这里传错会让导航栏图标变成"黑底黑图标"或"白底白图标"。
 */
@Composable
fun isAppThemeDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

@Composable
fun appColorScheme(
    themeState: AppStore.ThemeSettingState,
    systemDark: Boolean
): ColorScheme {

    val themeColor = Color(themeState.color)
    val isDarkTheme = when(themeState.darkMode) {
        0 -> systemDark
        1 -> false
        else -> true
    }
//    if (dynamicColor) {
//        return if (isDarkTheme) {
//            dynamicDarkColorScheme(LocalContext.current)
//        } else {
//            dynamicLightColorScheme(LocalContext.current)
//        }
//    }
    // 自定义主题（设置页第 11 项）的副色 / 点缀色。
    // 非自定义类型、以及自定义里选了"跟随主色"时都是 null —— 此时这次调用与加本功能之前**逐位相同**。
    val customSecondary = customPaletteOverride(
        customColor = themeState.customSecondary,
        primaryColor = themeState.color,
        themeType = themeState.type,
    )
    val customTertiary = customPaletteOverride(
        customColor = themeState.customTertiary,
        primaryColor = themeState.color,
        themeType = themeState.type,
    )
    // 参数全部按名字传：materialkolor 里 rememberDynamicColorScheme 有两个重载
    // （首参叫 seedColor 的、和首参叫 primary 的），只传位置参数时靠默认参数规则挑，
    // 全用命名参数（尤其是把 seedColor 写出来）能唯一确定是"seedColor 版"这一支。
    val colorScheme = rememberDynamicColorScheme(
        seedColor = themeColor,
        isDark = isDarkTheme,
        isAmoled = true, // AMOLED：深色下 surface 直接纯黑（保持原有行为）
        secondary = customSecondary,
        tertiary = customTertiary,
    )
    return colorScheme
}

/**
 * 自定义副色 / 点缀色 → materialkolor 的**调色板覆盖**参数。返回 null 表示"不覆盖"。
 *
 * 为什么用 materialkolor 自带的 primary/secondary/tertiary 覆盖，而不是自己拿生成结果
 * `ColorScheme.copy(secondary = ...)` 硬塞：
 * 传进去的颜色会被当成**种子**换成一条完整的 TonalPalette，secondary / onSecondary /
 * secondaryContainer / onSecondaryContainer（tertiary 同理）全部按明暗主题的色调重新推导，
 * 对比度是色调系统保证的 —— 手写 copy 就得自己算 on 色，深浅两套主题下很容易搞出白底白字。
 *
 * 为什么"等于主色"要当作不覆盖（返回 null）：
 * 覆盖是整条调色板的 chroma 跟着种子色走，而默认（TonalSpot）的副色调色板 chroma 只有 16 左右。
 * 若把主色当副色传进去，secondaryContainer 之类会明显比现在艳 —— 用户没动过副色却看到配色变了，
 * 这就破坏了"默认（跟随主色）时与老版本无差异"的要求。传 null 即回到原路径。
 *
 * @param customColor state 里存的自定义色（null = 没保存过）
 * @param primaryColor 当前生效主题色（自定义时它就是自定义主色）
 */
fun customPaletteOverride(
    customColor: Int?,
    primaryColor: Int,
    themeType: Int,
): Color? {
    if (themeType != SettingConstants.THEME_TYPE_CUSTOM) return null
    val color = customColor ?: return null
    return if (color == primaryColor) null else Color(color)
}
