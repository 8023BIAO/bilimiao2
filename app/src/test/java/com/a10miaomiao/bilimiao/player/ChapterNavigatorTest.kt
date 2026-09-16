package com.a10miaomiao.bilimiao.player

import com.a10miaomiao.bilimiao.widget.player.ChapterInfo
import com.a10miaomiao.bilimiao.widget.player.ChapterNavigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterNavigatorTest {

    private fun chapter(startMs: Long) = ChapterInfo(
        title = "c$startMs",
        startFraction = 0f,
        endFraction = 0f,
        startMs = startMs,
        endMs = startMs,
    )

    private val chapters = listOf(
        chapter(0L),
        chapter(10_000L),
        chapter(30_000L),
    )

    @Test
    fun `middle position goes to adjacent chapters`() {
        assertEquals(0L, ChapterNavigator.previousStart(chapters, 15_000L))
        assertEquals(30_000L, ChapterNavigator.nextStart(chapters, 15_000L))
    }

    @Test
    fun `exactly on chapter start uses current chapter`() {
        assertEquals(0L, ChapterNavigator.previousStart(chapters, 10_000L))
        assertEquals(30_000L, ChapterNavigator.nextStart(chapters, 10_000L))
    }

    @Test
    fun `just after chapter start does not skip previous chapter`() {
        assertEquals(0L, ChapterNavigator.previousStart(chapters, 10_100L))
        assertEquals(30_000L, ChapterNavigator.nextStart(chapters, 10_100L))
    }

    @Test
    fun `just before chapter start does not skip next chapter`() {
        assertEquals(10_000L, ChapterNavigator.nextStart(chapters, 9_900L))
    }

    @Test
    fun `first chapter has no previous`() {
        assertFalse(ChapterNavigator.hasPrevious(chapters, 0L))
        assertFalse(ChapterNavigator.hasPrevious(chapters, 500L))
    }

    @Test
    fun `last chapter has no next`() {
        assertFalse(ChapterNavigator.hasNext(chapters, 30_000L))
        assertFalse(ChapterNavigator.hasNext(chapters, 35_000L))
    }

    @Test
    fun `unsorted and duplicated starts are normalized`() {
        val messy = listOf(chapter(30_000L), chapter(0L), chapter(10_000L), chapter(10_000L))
        assertEquals(listOf(0L, 10_000L, 30_000L), ChapterNavigator.sortedStarts(messy))
        assertEquals(0L, ChapterNavigator.previousStart(messy, 10_000L))
        assertEquals(30_000L, ChapterNavigator.nextStart(messy, 10_000L))
    }

    @Test
    fun `one chapter has no navigation`() {
        val one = listOf(chapter(0L))
        assertFalse(ChapterNavigator.hasChapters(one))
        assertNull(ChapterNavigator.previousStart(one, 5_000L))
        assertNull(ChapterNavigator.nextStart(one, 0L))
        assertTrue(ChapterNavigator.hasChapters(chapters))
    }

    @Test
    fun `position before first chapter still has a next chapter`() {
        // 章节不从 0 开始（第一节在 10s）时，5s 处的"下一章"就是第一章，
        // hasNext 必须和 nextStart 一致，否则通知栏会错判成"没有下一章"
        val late = listOf(chapter(10_000L), chapter(30_000L), chapter(60_000L))
        assertEquals(10_000L, ChapterNavigator.nextStart(late, 5_000L))
        assertTrue(ChapterNavigator.hasNext(late, 5_000L))
        assertFalse(ChapterNavigator.hasPrevious(late, 5_000L))
        assertNull(ChapterNavigator.previousStart(late, 5_000L))
    }

    @Test
    fun `hasNext agrees with nextStart on every position`() {
        val late = listOf(chapter(10_000L), chapter(30_000L), chapter(60_000L))
        for (pos in listOf(0L, 5_000L, 10_000L, 20_000L, 30_000L, 59_999L, 60_000L, 90_000L)) {
            assertEquals(
                "position=$pos",
                ChapterNavigator.nextStart(late, pos) != null,
                ChapterNavigator.hasNext(late, pos),
            )
        }
    }
}
