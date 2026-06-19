package cn.a10miaomiao.bilimiao.compose.common.foundation

import androidx.compose.runtime.compositionLocalOf

val LocalOnSeekTime = compositionLocalOf<((Int) -> Unit)?> { null }
val LocalSeekEnabled = compositionLocalOf { true }
