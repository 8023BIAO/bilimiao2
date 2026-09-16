package cn.a10miaomiao.bilimiao.compose.pages.time.components

/**
 * 计算星期几。
 *
 * 返回 **0=周一 … 6=周日**，与日历表头 `listOf("一","二","三","四","五","六","日")` 对齐。
 * 底层公式原本返回 0=周日 … 6=周六，之前直接当列偏移用 → 每个月都错位一格
 * （例如 2024-09-01 是周日，却被画在"一"那一列）。见 TimeHelperTest。
 */
internal fun getWeek(y: Int, m: Int, d: Int): Int {
    var y = y
    var m = m
    if (m < 3) {
        m += 12
        --y
    }
    val sundayBased = (d + 1 + 2 * m + 3 * (m + 1) / 5 + y + (y shr 2) - y / 100 + y / 400) % 7
    return (sundayBased + 6) % 7
}

/**
 * 是否闰年
 */
internal fun isLeapYear(y: Int): Boolean {
    return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
}

/**
 * 计算一个月有多少天
 */
internal fun getMonthDayNum(y: Int, m: Int): Int {
    if (m in 1..12) {
        val dates = intArrayOf(31, if (isLeapYear(y)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return dates[m - 1]
    }
    return 30
}