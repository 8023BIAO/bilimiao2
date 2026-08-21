package com.a10miaomiao.bilimiao.widget.player

/**
 * 章节相邻跳转的纯逻辑，方便单元测试。
 *
 * 规则：
 * - 上一章：所有起点严格小于当前位置的章节里，最晚开始的那一章
 * - 下一章：所有起点严格大于当前位置的章节里，最早开始的那一章
 * - 不使用任何时间窗口，正好站在章节起点时按当前章节处理
 */
object ChapterNavigator {

    fun sortedStarts(chapters: List<ChapterInfo>): List<Long> {
        return chapters
            .map { it.startMs }
            .distinct()
            .sorted()
    }

    fun hasChapters(chapters: List<ChapterInfo>): Boolean {
        return sortedStarts(chapters).size > 1
    }

    fun hasPrevious(chapters: List<ChapterInfo>, positionMs: Long): Boolean {
        val starts = sortedStarts(chapters)
        if (starts.size <= 1) return false
        return currentIndex(starts, positionMs) > 0
    }

    fun hasNext(chapters: List<ChapterInfo>, positionMs: Long): Boolean {
        val starts = sortedStarts(chapters)
        if (starts.size <= 1) return false
        val index = currentIndex(starts, positionMs)
        return index in 0 until starts.lastIndex
    }

    fun previousStart(chapters: List<ChapterInfo>, positionMs: Long): Long? {
        val starts = sortedStarts(chapters)
        if (starts.size <= 1) return null
        val index = currentIndex(starts, positionMs)
        return starts.getOrNull(index - 1)
    }

    fun nextStart(chapters: List<ChapterInfo>, positionMs: Long): Long? {
        val starts = sortedStarts(chapters)
        if (starts.size <= 1) return null
        val index = currentIndex(starts, positionMs)
        return starts.getOrNull(index + 1)
    }

    /**
     * 当前位置所在章节：
     * 最后一个 startMs <= positionMs 的章节。
     * 正好在章节起点时，算作已经进入该章节。
     */
    private fun currentIndex(starts: List<Long>, positionMs: Long): Int {
        return starts.indexOfLast { it <= positionMs }
    }
}
