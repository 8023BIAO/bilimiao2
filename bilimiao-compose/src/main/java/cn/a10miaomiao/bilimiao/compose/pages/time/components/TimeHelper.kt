package cn.a10miaomiao.bilimiao.compose.pages.time.components

import com.a10miaomiao.bilimiao.comm.store.model.DateModel
import kotlin.math.abs

/**
 * 自定义时间范围最多能选多少天（**含首尾两天**）。
 *
 * 这是**客户端自设的上限，不是接口限制**：2026-10-02 实测 `newlist_rank` 在含首尾
 * **94 天**时仍返回 `code=0` + 数据、**95 天**才 `-10`（跨锚点/跨分区一致，见
 * `evidence/probe-newlist-rank-span.md`）。取 90 是**留 4 天安全余量**，
 * 免得服务端哪天收紧就整段时间线查不出内容。
 *
 * 日历上的三处判断（点第二端、选中态、提示语）都必须引用它，不许再写死天数。
 */
internal const val MAX_SPAN_DAYS = 90

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
 * 是否闰年。
 *
 * 整百年份必须能被 400 整除才是闰年：2000 闰年，1900/2100 平年。
 * 注：`DateModel.getMonthDate()` 是同口径的另一份实现，不在本模块，未同步改动。
 */
internal fun isLeapYear(y: Int): Boolean {
    if (y % 100 == 0 && y % 400 != 0) {
        return false
    }
    return y % 4 == 0
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

/**
 * 日历要画几行（每行 7 格）。
 *
 * 行数 = ⌈(月首列偏移 + 当月天数) / 7⌉，**与原来 `while (num < total)` 的写法逐月等价**
 * （2009–2030 共 264 个月对拍 0 差异，见 `evidence/verify-timepicker-abc.md`）；
 * 抽出来只为让 CustomTime 用 `for (row in 0 until rowCount)`，读起来更直白。
 * 月首周一 + 31 天 = 5 行、月首周一 + 28 天 = 4 行 —— 整除时也不多画一整行空白。
 */
internal fun getCalendarRowCount(y: Int, m: Int): Int {
    return (getWeek(y, m, 1) + getMonthDayNum(y, m) + 6) / 7
}

/**
 * 公历年月日 → 递增的日序号（Fliegel–Van Flandern 儒略日算法）。
 * 纯整数运算，只跟年月日有关。
 */
private fun toDayNumber(y: Int, m: Int, d: Int): Int {
    val a = if (m <= 2) 1 else 0
    val yy = y - a
    val mm = m + 12 * a - 3
    return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400
}

/**
 * 按年月日算的整日差（`to - from`，单位：天）。
 *
 * 不用 `DateModel.getGapCount()`：它取"本地午夜毫秒差 ÷ 86400000"的整数商，在有夏令时的
 * 地区跨越切换日会错一天。这里只按年月日做整数运算，与系统时区无关。
 */
internal fun daysBetween(from: DateModel, to: DateModel): Int {
    return toDayNumber(to.year, to.month, to.date) - toDayNumber(from.year, from.month, from.date)
}

/**
 * 区间含首尾共几天（同一天 = 1 天）。
 */
internal fun spanDays(from: DateModel, to: DateModel): Int {
    return abs(daysBetween(from, to)) + 1
}
