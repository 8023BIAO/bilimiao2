package cn.a10miaomiao.bilimiao.compose.pages.time

import cn.a10miaomiao.bilimiao.compose.pages.time.components.getMonthDayNum
import cn.a10miaomiao.bilimiao.compose.pages.time.components.getWeek
import cn.a10miaomiao.bilimiao.compose.pages.time.components.isLeapYear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间线（时光姬）日历工具的单元测试。
 *
 * getWeek 之前返回 0=周日..6=周六，却被直接当成"周一起始"日历的列偏移 →
 * 每个月都错位一格。这里用真实日历锁定 0=周一..6=周日 的约定。
 */
class TimeHelperTest {

    @Test
    fun week_isMondayBased() {
        // 2024-09-01 是周日 → 列偏移应为 6（表头 一..日 的最后一列）
        assertEquals(6, getWeek(2024, 9, 1))
        // 2024-09-02 是周一 → 0
        assertEquals(0, getWeek(2024, 9, 2))
        // 2024-02-29 是周四 → 3
        assertEquals(3, getWeek(2024, 2, 29))
        // 2009-01-01 是周四 → 3
        assertEquals(3, getWeek(2009, 1, 1))
        // 2026-03-01 是周日 → 6（2025 是平年，用来覆盖跨年 1/2 月调整分支）
        assertEquals(6, getWeek(2026, 3, 1))
        // 2026-09-15 是周二 → 1
        assertEquals(1, getWeek(2026, 9, 15))
        // 2026-12-31 是周四 → 3
        assertEquals(3, getWeek(2026, 12, 31))
    }

    @Test
    fun week_isAlwaysInRange() {
        for (y in listOf(2009, 2024, 2025, 2026)) {
            for (m in 1..12) {
                val w = getWeek(y, m, 1)
                assertTrue("y=$y m=$m w=$w", w in 0..6)
            }
        }
    }

    @Test
    fun leapYear() {
        assertTrue(isLeapYear(2024))
        assertFalse(isLeapYear(2025))
        assertTrue(isLeapYear(2000))
        assertFalse(isLeapYear(1900))
    }

    @Test
    fun monthDayNum() {
        assertEquals(29, getMonthDayNum(2024, 2))
        assertEquals(28, getMonthDayNum(2025, 2))
        assertEquals(31, getMonthDayNum(2026, 1))
        assertEquals(30, getMonthDayNum(2026, 4))
    }
}
