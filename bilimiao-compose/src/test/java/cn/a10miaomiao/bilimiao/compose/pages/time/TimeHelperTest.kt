package cn.a10miaomiao.bilimiao.compose.pages.time

import cn.a10miaomiao.bilimiao.compose.pages.time.components.MAX_SPAN_DAYS
import cn.a10miaomiao.bilimiao.compose.pages.time.components.daysBetween
import cn.a10miaomiao.bilimiao.compose.pages.time.components.getCalendarRowCount
import cn.a10miaomiao.bilimiao.compose.pages.time.components.getMonthDayNum
import cn.a10miaomiao.bilimiao.compose.pages.time.components.getWeek
import cn.a10miaomiao.bilimiao.compose.pages.time.components.isLeapYear
import cn.a10miaomiao.bilimiao.compose.pages.time.components.spanDays
import com.a10miaomiao.bilimiao.comm.store.model.DateModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间线（时光姬）日历工具的单元测试。
 *
 * getWeek 之前返回 0=周日..6=周六，却被直接当成"周一起始"日历的列偏移 →
 * 每个月都错位一格。这里用真实日历锁定 0=周一..6=周日 的约定。
 *
 * 自定义范围的"整日差"与"含首尾天数"（daysBetween / spanDays）不用系统时区的毫秒差，
 * 见 TimeHelper 注释；日历行数（getCalendarRowCount）锁定"不多画一整行空白"。
 */
class TimeHelperTest {

    private fun date(year: Int, month: Int, day: Int) = DateModel().also {
        it.year = year
        it.month = month
        it.date = day
    }

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
    fun leapYear_centuryRule() {
        // 整百年份必须能被 400 整除：2100 是平年（2 月 28 天），2000 是闰年
        assertFalse(isLeapYear(2100))
        assertEquals(28, getMonthDayNum(2100, 2))
        assertEquals(29, getMonthDayNum(2000, 2))
    }

    @Test
    fun monthDayNum() {
        assertEquals(29, getMonthDayNum(2024, 2))
        assertEquals(28, getMonthDayNum(2025, 2))
        assertEquals(31, getMonthDayNum(2026, 1))
        assertEquals(30, getMonthDayNum(2026, 4))
    }

    @Test
    fun daysBetween_countsWholeDaysByYmd() {
        // 2026-08-07 → 2026-09-04：相差 28 天（含首尾 29 天，摘要那行的例子）
        assertEquals(28, daysBetween(date(2026, 8, 7), date(2026, 9, 4)))
        assertEquals(-28, daysBetween(date(2026, 9, 4), date(2026, 8, 7)))
        assertEquals(0, daysBetween(date(2026, 8, 7), date(2026, 8, 7)))
        // 跨年
        assertEquals(1, daysBetween(date(2025, 12, 31), date(2026, 1, 1)))
        // 闰年 2 月 29 日
        assertEquals(2, daysBetween(date(2024, 2, 28), date(2024, 3, 1)))
        assertEquals(1, daysBetween(date(2025, 2, 28), date(2025, 3, 1)))
        // 整百年份（2100 平年）：365 天，2 月只有 28 天
        assertEquals(365, daysBetween(date(2100, 1, 1), date(2101, 1, 1)))
        assertEquals(28, daysBetween(date(2100, 2, 1), date(2100, 3, 1)))
    }

    @Test
    fun spanDays_isInclusive() {
        // 纯算法口径：含首尾计数（同一天 = 1 天）
        assertEquals(1, spanDays(date(2026, 8, 7), date(2026, 8, 7)))
        assertEquals(29, spanDays(date(2026, 8, 7), date(2026, 9, 4)))
        assertEquals(30, spanDays(date(2026, 8, 7), date(2026, 9, 5)))
        assertEquals(31, spanDays(date(2026, 8, 7), date(2026, 9, 6)))
        // 注意：这里断言的是天数本身，**不要**写成 MAX_SPAN_DAYS ——
        // 那会把"算法正确"和"上限取值"绑在一起（2026-10-02 上限由 30 抬到 90 时就这么挂过一次）。
        assertTrue("上限必须是正数", MAX_SPAN_DAYS > 0)
    }

    @Test
    fun calendarRowCount_hasNoBlankLastRow() {
        // 月首正好是周一 + 该月 31 天：正好 5 行（边界上不许多画一整行空白）
        assertEquals(0, getWeek(2024, 1, 1))
        assertEquals(5, getCalendarRowCount(2024, 1))
        // 月首周一 + 28 天（平年 2 月）：正好 4 行
        assertEquals(4, getCalendarRowCount(2027, 2))
        // 月首周日 + 30 天：月末那天单独占最后一行 → 6 行
        assertEquals(6, getCalendarRowCount(2024, 9))
    }

    @Test
    fun calendarRowCount_coversAllDays() {
        for (y in 2009..2030) {
            for (m in 1..12) {
                val cells = getCalendarRowCount(y, m) * 7
                val used = getWeek(y, m, 1) + getMonthDayNum(y, m)
                assertTrue("y=$y m=$m cells=$cells used=$used", cells >= used)
                // 最多只多出最后一行的空格，不允许整整多一行
                assertTrue("y=$y m=$m cells=$cells used=$used", cells - used < 7)
            }
        }
    }
}
