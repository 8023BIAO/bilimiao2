package com.a10miaomiao.bilimiao.player

import com.a10miaomiao.bilimiao.widget.player.SponsorTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「空降助手」片段时间纯函数（[SponsorTime]）的 JVM 单测。
 * 不依赖 Android：只跑 format / parseSec 的边界值。
 */
class SponsorTimeTest {

    /** parseSec 的合法值断言（null 会先被 assertNotNull 拦下，失败信息里带上原文） */
    private fun assertSec(expected: Double, text: String) {
        val actual = SponsorTime.parseSec(text)
        assertNotNull("parseSec(\"$text\") 不该是 null", actual)
        assertEquals("parseSec(\"$text\")", expected, actual!!, 1e-9)
    }

    @Test
    fun formatKeepsMillisecondPrecision() {
        assertEquals("00:00.000", SponsorTime.format(0L))
        assertEquals("01:11.000", SponsorTime.format(71_000L))
        assertEquals("01:40.900", SponsorTime.format(100_900L))
        // 毫秒截断（若四舍五入这里会变成 01:40.999 → 01:41.000 之类）
        assertEquals("01:40.999", SponsorTime.format(100_999L))
        assertEquals("59:59.999", SponsorTime.format(3_599_999L))
        assertEquals("01:00:00.000", SponsorTime.format(3_600_000L))
        assertEquals("01:02:03.456", SponsorTime.format(3_723_456L))
    }

    @Test
    fun formatClampsNegativeToZero() {
        assertEquals("00:00.000", SponsorTime.format(-5L))
    }

    /** 护栏：`SponsorTime.format` 必须固定 Locale.US（阿语默认区域会写阿拉伯-印度数字） */
    @Test
    fun formatIgnoresDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("01:11.000", SponsorTime.format(71_000L))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun parseAcceptsColonFullWidthColonPlainSecondsAndDecimals() {
        assertSec(71.0, "01:11.000")
        assertSec(71.25, "71.25")
        assertSec(0.0, "00:00.000")
        assertSec(3723.5, "1:2:3.5")
        assertSec(3723.5, "1：2：3.5")
        assertSec(71.0, "01:11")
        assertSec(71.0, "  71  ")
    }

    @Test
    fun parseRejectsIllegalInput() {
        val bad = listOf("", "   ", "abc", "1:60:00", "1:2:3:4", "-5", "1:2:-3", "1::2")
        bad.forEach { assertNull("parseSec(\"$it\") 应该是 null", SponsorTime.parseSec(it)) }
    }
}
