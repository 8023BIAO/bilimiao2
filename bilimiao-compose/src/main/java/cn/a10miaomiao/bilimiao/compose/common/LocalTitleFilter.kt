package cn.a10miaomiao.bilimiao.compose.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.a10miaomiao.bilimiao.comm.utils.TitleCleaner

/**
 * 标题净化开关（Compose 侧的只读入口）。
 *
 * 开关值由 [cn.a10miaomiao.bilimiao.compose.BilimiaoTheme] 从 AppStore.State 下发；
 * 关闭时 [cleanDisplayTitle] 原样返回，不会做任何字符串处理。
 */
val LocalTitleFilterEnabled = compositionLocalOf { false }

/**
 * 显示层标题净化：只在开关打开时清洗 + 缩短，失败自动回退原文（见 [TitleCleaner]）。
 * 只能在 @Composable 里调用（读取 CompositionLocal 以触发重组）。
 */
@Composable
fun cleanDisplayTitle(title: String?): String? {
    if (title.isNullOrBlank()) return title
    if (!LocalTitleFilterEnabled.current) return title
    return TitleCleaner.clean(title)
}
