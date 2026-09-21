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
    val colorScheme = rememberDynamicColorScheme(
        themeColor,
        isDarkTheme,
        isAmoled = true
    )
    return colorScheme
}
