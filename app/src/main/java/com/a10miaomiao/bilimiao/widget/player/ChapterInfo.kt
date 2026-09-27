package com.a10miaomiao.bilimiao.widget.player

/**
 * 视频章节信息，用于进度条渲染
 * @param title 章节标题
 * @param startFraction 起始位置（0~1）
 * @param endFraction 结束位置（0~1）
 * @param startMs 起始时间（毫秒）
 * @param endMs 结束时间（毫秒）
 */
data class ChapterInfo(
    val title: String?,
    val startFraction: Float,
    val endFraction: Float,
    val startMs: Long,
    val endMs: Long
)
