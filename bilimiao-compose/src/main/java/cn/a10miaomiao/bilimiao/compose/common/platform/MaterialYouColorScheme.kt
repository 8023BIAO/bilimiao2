package cn.a10miaomiao.bilimiao.compose.common.platform

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Material You 动态配色（跟随系统壁纸/主题色取色）。
 *
 * Android 12+（[com.a10miaomiao.bilimiao.comm.platform.isMaterialYouSupported]）用系统原生
 * `dynamicLight/DarkColorScheme`；其余版本回退 Material 3 默认配色。
 *
 * 单模块 Android 版：上游 KMP 的 `expect/actual` 在这里落成一个直接实现（本项目没有 desktop 端）。
 */
@Composable
fun rememberMaterialYouColorScheme(isDarkTheme: Boolean): ColorScheme {
    val context = LocalContext.current
    return remember(context, isDarkTheme) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDarkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        } else if (isDarkTheme) {
            darkColorScheme()
        } else {
            lightColorScheme()
        }
    }
}
