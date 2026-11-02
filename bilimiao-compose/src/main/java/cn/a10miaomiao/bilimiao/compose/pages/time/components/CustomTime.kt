package cn.a10miaomiao.bilimiao.compose.pages.time.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.a10miaomiao.bilimiao.compose.pages.time.TimeSettingViewMode
import com.a10miaomiao.bilimiao.comm.store.model.DateModel
import com.a10miaomiao.bilimiao.comm.toast
import java.util.Date

/** 年月日 → DateModel（日历里到处都在拼这个，抽一处） */
private fun dateOf(year: Int, month: Int, day: Int) = DateModel().also {
    it.year = year
    it.month = month
    it.date = day
}

internal enum class TextBoxStatus {
    Enable,
    Start,
    Middle,
    End,
    Disable,
}

/** 头部胶囊内侧的纵向留白 */
private val CapsulePadding = 5.dp

/** 胶囊里选择器行的高度 */
private val SelectorRowHeight = 38.dp

/** 胶囊里星期/「选择月份」提示行的高度（两种视图共用，胶囊高度才不变） */
private val SubRowHeight = 26.dp

/** 日期格行高（比 M3 默认更紧凑，6 行塞得下整月） */
private val CalendarRowHeight = 36.dp

/** 固定 6 行：不同月份长度、日期视图 ↔ 月份视图高度完全一致，切换时卡片不跳动 */
private const val CalendarRowCount = 6

/** 头部胶囊与网格之间的间距 */
private val CalendarGap = 4.dp

/** 整个日历区域的高度：胶囊 + 间距 + 6 行网格，日期/月份视图共用 */
private val CalendarAreaHeight =
    CapsulePadding * 2 + SelectorRowHeight + SubRowHeight + CalendarGap +
        CalendarRowHeight * CalendarRowCount

private val WeekTitles = listOf("一", "二", "三", "四", "五", "六", "日")

/** 日期选中圆的直径；区间色带也用它做高度，保证和圆点同高 */
private val DayCircleSize = 32.dp

/**
 * 胶囊条里的星期行。
 *
 * 圆角和底色由外层胶囊统一提供，这里只负责文字：**全部同一档颜色**，周末只用字重区分。
 * 原来周末用正文色、工作日用弱化色，真机上被读成"周六日可选、工作日不可选"（用户反馈）。
 */
@Composable
private fun WeekdayRow(
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SubRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WeekTitles.forEachIndexed { index, title ->
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                // 周末只保留字重差；颜色统一，避免亮度差再被读成"可选/不可选"
                fontWeight = if (index >= 5) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 胶囊条里的副标题行：月份视图下顶替星期行的位置，保证胶囊高度不变。
 */
@Composable
private fun MonthHintRow(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SubRowHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "选择月份",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 日期格在选中区间中的位置，用于绘制区间色带 */
private enum class RangeSide {
    None,
    Start,
    End,
    Full,
}

/**
 * 日期格。
 *
 * Material 3 Expressive 风格：选中态是实心主色圆点；区间中段铺一条与圆点同高的色带
 * （起点只画右半格、终点只画左半格，与相邻格连成一条）。日期格不画点击涟漪 ——
 * 圆点已经是选中反馈，涟漪会在圆外多出一圈方块残影。
 */
@Composable
private fun DayCell(
    text: String,
    status: TextBoxStatus,
    textColor: Color,
    rangeSide: RangeSide,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = status == TextBoxStatus.Start || status == TextBoxStatus.End
    Box(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        when (rangeSide) {
            RangeSide.Start -> Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxWidth(0.5f)
                    .height(DayCircleSize)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            )

            RangeSide.End -> Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.5f)
                    .height(DayCircleSize)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            )

            RangeSide.Full -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DayCircleSize)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            )

            RangeSide.None -> Unit
        }
        Box(
            modifier = Modifier
                .size(DayCircleSize)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = when {
                    selected -> MaterialTheme.colorScheme.onPrimary
                    status == TextBoxStatus.Middle -> MaterialTheme.colorScheme.onSurface
                    else -> textColor
                },
                fontSize = 14.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/**
 * 月份格：未选中透明圆、选中实心主色圆；超出可选范围（首/末年份）用弱化色且不可点。
 */
@Composable
private fun MonthCell(
    text: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (active) MaterialTheme.colorScheme.primary else Color.Transparent
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    active -> MaterialTheme.colorScheme.onPrimary
                    enabled -> MaterialTheme.colorScheme.onSurface
                    // 与日期格的"不可选"统一用 outline：原来 outlineVariant 太淡，
                    // 两处"灰"深浅不一，用户更容易把颜色误读成别的含义
                    else -> MaterialTheme.colorScheme.outline
                },
            )
        }
    }
}

/**
 * 月份网格：4 行 × 3 列，与日期网格共用同一块固定高度，切换视图时高度不变。
 */
@Composable
private fun MonthGrid(
    month: Int,
    minMonth: Int,
    maxMonth: Int,
    onClick: (month: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = 4
    val columns = 3
    Column(modifier = modifier.fillMaxSize()) {
        for (row in 0 until rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                for (column in 0 until columns) {
                    val value = row * columns + column + 1
                    MonthCell(
                        text = "${value}月",
                        active = value == month,
                        enabled = value in minMonth..maxMonth,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onClick = { onClick(value) },
                    )
                }
            }
        }
    }
}

/**
 * 日期网格：固定 [CalendarRowCount] 行，跨月空白格用占位，保证行数恒定。
 *
 * 列偏移用我们自己的 `getWeek(y, m, 1)`（**0=周一 … 6=周日**，与表头「一 … 日」对齐），
 * 不再引入上游 sunday-based 的 `getMonthStartOffset`，免得同一份代码里两套口径。
 */
@Composable
private fun DateGrid(
    year: Int,
    month: Int,
    maxDate: DateModel,
    monthStartWeek: Int,
    monthDayNum: Int,
    startTime: DateModel?,
    endTime: DateModel?,
    singleDate: Boolean,
    onClick: (day: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isMaxMonth = year == maxDate.year && month == maxDate.month
    Column(modifier = modifier.fillMaxSize()) {
        repeat(CalendarRowCount) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                for (column in 0..6) {
                    val day = row * 7 + column - monthStartWeek + 1
                    if (day in 1..monthDayNum && !(isMaxMonth && day > maxDate.date)) {
                        val curTime = dateOf(year, month, day)
                        val _startTime = startTime
                        val _endTime = endTime
                        val status = if (singleDate) {
                            // 单日模式只有"选中/没选中"，不参与区间/上限那套状态
                            if (_startTime != null && curTime == _startTime) {
                                TextBoxStatus.Start
                            } else {
                                TextBoxStatus.Enable
                            }
                        } else if (_startTime == null) {
                            TextBoxStatus.Enable
                        } else if (curTime == _startTime) {
                            TextBoxStatus.Start
                        } else if (_endTime == null) {
                            // 选了起点、还没选终点：离起点超过上限的格子用禁用视觉，
                            // 但仍可点 —— 点了由 itemClick 弹 toast 说明为什么没生效
                            if (spanDays(_startTime, curTime) > MAX_SPAN_DAYS) {
                                TextBoxStatus.Disable
                            } else {
                                TextBoxStatus.Enable
                            }
                        } else if (curTime == _endTime) {
                            TextBoxStatus.End
                        } else {
                            if (daysBetween(_startTime, curTime) > 0
                                && daysBetween(curTime, _endTime) > 0
                            ) {
                                TextBoxStatus.Middle
                            } else {
                                TextBoxStatus.Enable
                            }
                        }

                        DayCell(
                            text = day.toString(),
                            status = status,
                            rangeSide = if (singleDate) {
                                // 单日模式两端都是同一天，画半条色带会看着像"区间没选完"
                                RangeSide.None
                            } else when (status) {
                                // 起止同一天（同一天点两次 = 单日区间）只画圆点：半条色带伸出去
                                // 会让人误以为区间还在等第二端（上游漏了这个情况）
                                TextBoxStatus.Start ->
                                    if (_endTime != null && curTime != _endTime) {
                                        RangeSide.Start
                                    } else {
                                        RangeSide.None
                                    }

                                TextBoxStatus.End ->
                                    if (_startTime != null) RangeSide.End else RangeSide.None

                                TextBoxStatus.Middle -> RangeSide.Full
                                else -> RangeSide.None
                            },
                            // 可选日期一律亮色（onSurface），不再按平/周末分亮度 —— 原来工作日用
                            // outline 偏灰，用户误读成"灰=超出 90 天不可选"。现在灰色只有一个含义：
                            // Disable（真超 90 天）才用 outline 压暗一档。
                            textColor = if (status == TextBoxStatus.Disable) {
                                MaterialTheme.colorScheme.outline
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            onClick = { onClick(day) },
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * 标题栏里的可点区域（年月合并成一个按钮）。
 *
 * 上游为桌面端加了一层 hover 底色；手机没有 hover，交给默认点击涟漪即可。
 */
@Composable
private fun TitlePart(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

/**
 * 日历头部。
 *
 * 日期视图：单箭头切月、双层箭头切年，点中间标题进入月份视图；
 * 月份视图：单箭头切年、双层箭头跨十年，点中间标题弹年份下拉。
 * 越界方向箭头禁用；换年/跨十年后把月份夹到边界月份，避免落到可选范围之外。
 */
@Composable
private fun Header(
    year: MutableState<Int>,
    month: MutableState<Int>,
    maxDate: DateModel,
    minDate: DateModel,
    monthMode: Boolean,
    onMonthModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    fun clampMonth() {
        if (year.value <= minDate.year) {
            month.value = month.value.coerceAtLeast(minDate.month)
        }
        if (year.value >= maxDate.year) {
            month.value = month.value.coerceAtMost(maxDate.month)
        }
    }

    fun prevMonth() {
        if (month.value == 1) {
            month.value = 12
            year.value--
        } else {
            month.value--
        }
    }

    fun nextMonth() {
        if (month.value == 12) {
            month.value = 1
            year.value++
        } else {
            month.value++
        }
    }

    fun prevYear() {
        if (year.value > minDate.year) {
            year.value--
            clampMonth()
        }
    }

    fun nextYear() {
        if (year.value < maxDate.year) {
            year.value++
            clampMonth()
        }
    }

    fun jumpYears(step: Int) {
        year.value = (year.value + step).coerceIn(minDate.year, maxDate.year)
        clampMonth()
    }

    val atMinMonth = year.value == minDate.year && month.value == minDate.month
    val atMaxMonth = year.value == maxDate.year && month.value == maxDate.month
    val atMinYear = year.value <= minDate.year
    val atMaxYear = year.value >= maxDate.year

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SelectorRowHeight),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 外层：日期视图切一年、月份视图跨十年
        ArrowButton(
            imageVector = Icons.Outlined.KeyboardDoubleArrowLeft,
            contentDescription = if (monthMode) "上十年" else "上一年",
            enabled = !atMinYear,
            onClick = {
                if (monthMode) jumpYears(-10) else prevYear()
            },
        )
        // 内层：日期视图切一月、月份视图切一年
        ArrowButton(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            contentDescription = if (monthMode) "上一年" else "上一月",
            enabled = if (monthMode) !atMinYear else !atMinMonth,
            onClick = {
                if (monthMode) prevYear() else prevMonth()
            },
        )
        // 标题区用 weight 吃掉剩余宽度：上游写 widthIn(min = 130dp)，
        // 但窄屏/大字号下会把两侧箭头挤出屏幕，weight 能自动缩，视觉上仍居中。
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Box {
                // 年月合并成一个按钮：日期视图点开月份网格，月份视图点开年份下拉
                TitlePart(
                    onClick = {
                        if (monthMode) {
                            expanded = true
                        } else {
                            onMonthModeChange(true)
                        }
                    },
                ) {
                    Text(
                        text = if (monthMode) {
                            "${year.value}年"
                        } else {
                            "${year.value}年 ${month.value}月"
                        },
                        fontSize = 16.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    for (y in minDate.year..maxDate.year) {
                        DropdownMenuItem(
                            text = { Text(text = "${y}年") },
                            onClick = {
                                year.value = y
                                clampMonth()
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
        // 内层：日期视图切一月、月份视图切一年
        ArrowButton(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = if (monthMode) "下一年" else "下一月",
            enabled = if (monthMode) !atMaxYear else !atMaxMonth,
            onClick = {
                if (monthMode) nextYear() else nextMonth()
            },
        )
        // 外层：日期视图切一年、月份视图跨十年
        ArrowButton(
            imageVector = Icons.Outlined.KeyboardDoubleArrowRight,
            contentDescription = if (monthMode) "下十年" else "下一年",
            enabled = !atMaxYear,
            onClick = {
                if (monthMode) jumpYears(10) else nextYear()
            },
        )
    }
}

/**
 * 合并胶囊条：选择器行（箭头 + 年月）和星期行放进同一个大圆角容器。
 *
 * 月份视图下星期行换成「选择月份」提示行，两行高度一致，保证切换视图时
 * 胶囊和整个日历区域的高度都不变。
 */
@Composable
private fun CalendarHeader(
    year: MutableState<Int>,
    month: MutableState<Int>,
    maxDate: DateModel,
    minDate: DateModel,
    monthMode: Boolean,
    onMonthModeChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(vertical = CapsulePadding),
        ) {
            Header(
                modifier = Modifier.padding(horizontal = 6.dp),
                year = year,
                month = month,
                maxDate = maxDate,
                minDate = minDate,
                monthMode = monthMode,
                onMonthModeChange = onMonthModeChange,
            )
            if (monthMode) {
                MonthHintRow()
            } else {
                WeekdayRow()
            }
        }
    }
}

/**
 * 时光姬「自定义时间」页用的包装：把 ViewModel 的数据摊平成 [CustomTimeContent] 的参数。
 * 行为与以前完全一致（只是多了一层转发）。
 */
@Composable
internal fun CustomTime(
    viewModel: TimeSettingViewMode
) {
    val customTime = viewModel.customTime.collectAsStateWithLifecycle()
    CustomTimeContent(
        timeFrom = customTime.value.timeFrom,
        timeTo = customTime.value.timeTo,
        maxDate = viewModel.maxDate,
        minDate = viewModel.minDate,
        singleDate = false,
        onRangeChange = viewModel::setCustomTime,
    )
}

/**
 * 自绘日历区间选择（从时光姬的 `CustomTime` 抽出来的**纯参数**版本，供别处复用）。
 *
 * 为什么抽出来：这套组件已经有完整能力 —— 星期头是单字「一 二 三 四 五 六 日」、年月导航带年份选择、
 * 起止两端点击成一个区间、点区间内部只移动更近的一端、同一天点两次=单日、有上下限与最大跨度，
 * 并且**几何全在自己手里**（不依赖任何框架内部尺寸）。搜索页的「自定义时间区间」需要的就是它。
 *
 * 视觉照上游 11bedec3 的 M3 Expressive 版：头部胶囊（箭头 + 年月 + 星期/月份提示）、
 * 固定 6 行的紧凑网格、选中实心圆点 + 区间色带、点标题切 4×3 月份网格、日历下方一行摘要。
 * 行为（点击语义 / 90 天上限 / 单日模式 / 预填）与旧版逐条一致，改动集中在视觉层。
 *
 * @param timeFrom 区间起点；`year == -1` 表示"未选完整"（点了第一端、还没点第二端）
 * @param timeTo   区间终点
 * @param onRangeChange 回调：`(start, end)`；`end == null` 表示只选了起点
 */
@Composable
internal fun CustomTimeContent(
    timeFrom: DateModel,
    timeTo: DateModel,
    maxDate: DateModel,
    minDate: DateModel,
    /**
     * true = **只选一个日期**（点哪天就是哪天，立刻回调 `(那天, 那天)` 并由调用方收尾）：
     * 搜索页的「起始日期/结束日期」就是两次独立的单日选择，不需要区间那套（点两端、最大跨度、
     * 「点日期选择开始与结束」提示）——那些是时光姬的规则，套用过来反而不对。
     */
    singleDate: Boolean = false,
    /** 是否显示日历下方那行摘要（单日模式且空间紧张时可以关掉）。 */
    showSummary: Boolean = true,
    onRangeChange: (DateModel?, DateModel?) -> Unit,
) {
    // 初始显示的月份从入参推导（原来写死 2009/9，只有时光姬那个"进来就被 LaunchedEffect 覆盖"的
    // 场景才碰巧看不出问题；复用到别处时写死值会让用户先看到一个无关的月份）。
    val seedDate = when {
        timeFrom.year > 0 -> timeFrom
        timeTo.year > 0 -> timeTo
        else -> DateModel().setDate(Date())
    }
    var year = remember {
        mutableStateOf(seedDate.year)
    }
    var month = remember {
        mutableStateOf(seedDate.month)
    }
    // 月份视图：由日历头部标题切换，选完月份自动回到日期视图
    var monthMode by remember {
        mutableStateOf(false)
    }
    val monthStartWeek = remember(year.value, month.value) {
        getWeek(year.value, month.value, 1)
    }
    val monthDayNum = remember(year.value, month.value) {
        getMonthDayNum(year.value, month.value)
    }

    var startTime by remember {
        mutableStateOf<DateModel?>(null)
    }
    var endTime by remember {
        mutableStateOf<DateModel?>(null)
    }

    // ★只在"本地还没有选择"时用入参预填一次。
    //   原来写成 LaunchedEffect(timeFrom, timeTo)：时光姬每次 setCustomTime 都会换出新对象（内容 equals），
    //   effect 于是**每次选完都重跑**并把视图月份重置回 timeFrom —— 用户在 9 月点了第一端、切到 10 月
    //   点第二端后，视图会被拽回 9 月（丢位置）。改成只认第一次，且不再抢视图月份。
    LaunchedEffect(Unit) {
        if (startTime == null && timeFrom.year > 0) {
            startTime = timeFrom.copy()
            endTime = if (timeTo.year > 0) timeTo.copy() else null
            year.value = timeFrom.year
            month.value = timeFrom.month
        }
    }

    // 组件抽出来后这里没有 viewModel 可当 key 了；点选逻辑只依赖下面这些 state，用 Unit 即可
    val itemClick = remember(Unit) {
        { i: Int ->
            // 单日模式：点哪天就是哪天，夹到可选范围内后立刻回调（调用方负责关弹窗）
            val clicked = if (singleDate) {
                clampToSelectable(dateOf(year.value, month.value, i), minDate, maxDate)
            } else {
                dateOf(year.value, month.value, i)
            }
            if (singleDate) {
                startTime = clicked
                endTime = clicked
                onRangeChange(clicked, clicked)
            } else {
                val _startTime = startTime
                val _endTime = endTime
                when {
                    // 还没选开始：落一个起点，并告诉 ViewModel"未选完整"
                    //（否则点一下再按"确定"会把上一次的旧区间静默存下去）
                    _startTime == null -> {
                        startTime = clicked
                        endTime = null
                        onRangeChange(clicked, null)
                    }
                    // 选了开始、还没选结束
                    _endTime == null -> {
                        val gap = daysBetween(_startTime, clicked) // 有符号整日差
                        if (gap == 0) {
                            // 同一天点两次 = 单日区间（以前 gap==0 时 > 和 < 都不成立，选择会一直悬着）
                            endTime = clicked
                            onRangeChange(startTime, endTime)
                        } else if (spanDays(_startTime, clicked) > MAX_SPAN_DAYS) {
                            // 超过上限给明确反馈，不再静默：起点保留，等用户点一个更近的日期
                            toast("最多只能选 $MAX_SPAN_DAYS 天，请重新选择")
                        } else if (gap > 0) {
                            endTime = clicked
                            onRangeChange(startTime, endTime)
                        } else {
                            startTime = clicked
                            endTime = _startTime
                            onRangeChange(startTime, endTime)
                        }
                    }
                    // 已选完整区间
                    else -> {
                        if (daysBetween(_startTime, clicked) >= 0 && daysBetween(clicked, _endTime) >= 0) {
                            // 点区间内部（含两端）→ 只移动更近的那一端，不再把整个区间清掉；
                            // 正好落在中点时移动终点。
                            if (daysBetween(_startTime, clicked) < daysBetween(clicked, _endTime)) {
                                startTime = clicked
                            } else {
                                endTime = clicked
                            }
                            onRangeChange(startTime, endTime)
                        } else {
                            // 点在区间外 → 以这一格为新起点重新选，保留"再点一次可以重来"的旧手感
                            startTime = clicked
                            endTime = null
                            onRangeChange(clicked, null)
                        }
                    }
                }
            }
            Unit
        }
    }

    val selectedStart = startTime
    val selectedEnd = endTime
    // 摘要放日历下方（与上游一致）：单日模式沿用旧文案，区间模式按"已选择时间线：X 至 Y"汇报三态。
    val summaryText = if (singleDate) {
        if (selectedStart == null) "点一个日期" else "已选 ${selectedStart.getValue("-")}"
    } else {
        when {
            selectedStart == null ->
                "已选择时间线：未选择 至 未选择"

            selectedEnd == null ->
                "已选择时间线：${selectedStart.getValue("-")} 至 未选择（再点一个日期选择结束，最多 $MAX_SPAN_DAYS 天）"

            else ->
                "已选择时间线：${selectedStart.getValue("-")} 至 ${selectedEnd.getValue("-")}（共 ${spanDays(selectedStart, selectedEnd)} 天，上限 $MAX_SPAN_DAYS 天）"
        }
    }

    Column() {

        // 头部胶囊 + 网格：整体固定高度，日期/月份视图切换时卡片高度不变
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(CalendarAreaHeight)
        ) {
            CalendarHeader(
                year = year,
                month = month,
                maxDate = maxDate,
                minDate = minDate,
                monthMode = monthMode,
                onMonthModeChange = { monthMode = it },
            )
            Spacer(modifier = Modifier.height(CalendarGap))
            Crossfade(
                targetState = monthMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { showMonths ->
                if (showMonths) {
                    MonthGrid(
                        month = month.value,
                        minMonth = if (year.value == minDate.year) minDate.month else 1,
                        maxMonth = if (year.value == maxDate.year) maxDate.month else 12,
                        modifier = Modifier.fillMaxSize(),
                        onClick = {
                            month.value = it
                            monthMode = false
                        },
                    )
                } else {
                    DateGrid(
                        year = year.value,
                        month = month.value,
                        maxDate = maxDate,
                        monthStartWeek = monthStartWeek,
                        monthDayNum = monthDayNum,
                        startTime = startTime,
                        endTime = endTime,
                        singleDate = singleDate,
                        modifier = Modifier.fillMaxSize(),
                        onClick = itemClick,
                    )
                }
            }
        }

        if (showSummary) {
            Text(
                text = summaryText,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

/** 把日期夹进 `[minDate, maxDate]`（单日模式用；月份导航本来也会挡，但直接调进来时仍需兜底）。 */
private fun clampToSelectable(date: DateModel, minDate: DateModel, maxDate: DateModel): DateModel = when {
    daysBetween(date, minDate) > 0 -> minDate
    daysBetween(maxDate, date) > 0 -> maxDate
    else -> date
}
