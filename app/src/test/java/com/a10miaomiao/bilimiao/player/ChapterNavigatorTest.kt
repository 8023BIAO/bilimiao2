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
}
