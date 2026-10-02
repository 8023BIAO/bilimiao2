package cn.a10miaomiao.bilimiao.compose.common.foundation

import androidx.compose.runtime.compositionLocalOf

val LocalOnSeekTime = compositionLocalOf<((Int) -> Unit)?> { null }
val LocalSeekEnabled = compositionLocalOf { true }

/**
 * 评论时间戳空降的**秒数上限**（= 当前视频时长），与 [LocalSeekEnabled] 正交：
 * 那个是"整块评论区是否允许空降"（专栏页禁用），这个是"单条时间戳是否超出视频时长"。
 *
 * · 默认 [Int.MAX_VALUE] = **不设上限** —— 消息页/动态页等拿不到视频时长的评论区保持原行为；
 * · `seconds > LocalSeekMaxSeconds` → 该时间戳按**纯文本**渲染（不着色、不可点、不跳转）；
 * · `<= 0` 视为"时长未知"：由提供方回落成默认值（不设上限），避免数据缺失时误禁整屏时间戳。
 */
val LocalSeekMaxSeconds = compositionLocalOf { Int.MAX_VALUE }
