package cn.a10miaomiao.bilimiao.compose.pages.time.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.a10miaomiao.bilimiao.compose.pages.time.TimeSettingViewMode
import com.a10miaomiao.bilimiao.comm.store.model.DateModel
import java.util.Date
import com.a10miaomiao.bilimiao.comm.toast

/** 年月日 → DateModel（日历里到处都在拼这个，抽一处） */
private fun dateOf(year: Int, month: Int, day: Int) = DateModel().also {
    it.year = year
    it.month = month
    it.date = day
}

@Composable
internal fun MonthText(y: Int, m: Int): String {
    if (m > 12) {
        return "${y+1}年1月"
    } else if (m < 1) {
        return "${y-1}年12月"
    } else {
        return "${y}年${m}月"
    }
}

internal enum class TextBoxStatus {
    Enable,
    Start,
    Middle,
    End,
    Disable,
}

@Composable
internal fun TextBox(
    modifier: Modifier,
    text: String,
    textColor: Color = Color.Unspecified,
    height: Dp = 25.dp,
    onClick: (() -> Unit)? = null,
    status: TextBoxStatus = TextBoxStatus.Enable,
    /**
     * 区间端点是否画「起」/「止」小字。默认 **true**（时光姬页面就靠这两个字分辨哪端是起、哪端是止
     * —— 两端的底色块完全一样）。
     * 搜索筛选那边是"一次只选一天"，传 `showRangeLabels = !singleDate` 即自动不画。
     */
    showRangeLabels: Boolean = true,
) {
    // 选中态（起 / 止 / 区间内）统一用**主题强调色**（primaryContainer）。
    // 原来用的是 tertiaryContainer——本主题的 tertiary 是偏粉的强调色，压在"选中"这个语义上
    // 看着像"没跟主题"（用户 2026-10-02 反馈"选中间那个一直是粉色固定的"）。
    // （tertiaryContainer 全仓另有几处，但都是别的语义：CDN 延迟色阶 / 调色板预览 / 测试页，别去动。）
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val onContainerColor = MaterialTheme.colorScheme.onPrimaryContainer
    // 注意：区间内（Middle）的文字也是压在 primaryContainer 上的，必须一起换成 onPrimaryContainer，
    // 否则会拿星期/次要文字色去压强调色底 → 对比度不够。
    val selected = status == TextBoxStatus.Start ||
        status == TextBoxStatus.End ||
        status == TextBoxStatus.Middle

    Surface(
        modifier = if (onClick != null) {
            modifier.clickable(onClick = onClick)
        } else modifier,
        color = if (status == TextBoxStatus.Start || status == TextBoxStatus.End) {
            containerColor
        } else Color.Transparent,
        shape = RoundedCornerShape(5.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 5.dp)
                .fillMaxWidth()
                .height(height)
                .let {
                      if (status == TextBoxStatus.Middle) {
                          it.background(containerColor)
                      } else it
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = text,
                color = if (selected) onContainerColor else textColor
            )
            if (showRangeLabels && status == TextBoxStatus.Start) {
                Text(
                    text = "起",
                    color = onContainerColor,
                    fontSize = 10.sp,
                )
            } else if (showRangeLabels && status == TextBoxStatus.End) {
                Text(
                    text = "止",
                    color = onContainerColor,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
fun MonthTextBox(
    year: Int,
    month: Int,
    textColor: Color = Color.Unspecified,
    arrowLeft: Boolean = false,
    arrowRight: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .size(120.dp, 40.dp)
            .clickable(enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (enabled) {
            if (arrowLeft) {
                Icon(
                    Icons.Outlined.KeyboardArrowLeft,
                    "上月",
                    modifier = Modifier.size(20.dp),
                    tint = textColor,
                )
            }
            Text(
                text = MonthText(year, month),
                fontSize = 16.sp,
                color = textColor,
            )
            if (arrowRight) {
                Icon(
                    Icons.Outlined.KeyboardArrowRight,
                    "下月",
                    modifier = Modifier.size(20.dp),
                    tint = textColor,
                )
            }
        }
    }

}

@Composable
fun Header(
    year: MutableState<Int>,
    month: MutableState<Int>,
    maxDate: DateModel,
    minDate: DateModel,
) {
    val titles = remember {
        listOf("一", "二", "三", "四", "五", "六", "日")
    }
    var expanded by remember { mutableStateOf(false) }

    Column() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            MonthTextBox(
                year = year.value,
                month = month.value - 1,
                textColor = MaterialTheme.colorScheme.outline,
                arrowLeft = true,
                enabled = year.value != minDate.year || month.value != minDate.month,
                onClick = {
                    if (month.value == 1) {
                        month.value = 12
                        year.value--
                    } else {
                        month.value--
                    }
                }
            )
            Box {
                MonthTextBox(
                    year = year.value,
                    month = month.value,
                    textColor = MaterialTheme.colorScheme.onBackground,
                    onClick = {  expanded = !expanded },
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    for (y in minDate.year..maxDate.year) {
                        DropdownMenuItem(
                            text = {
                                Text(text = "${y}年")
                            },
                            onClick = {
                                year.value = y
                                expanded = false
                            }
                        )
                    }
                }
            }
            MonthTextBox(
                year = year.value,
                month = month.value + 1,
                textColor = MaterialTheme.colorScheme.outline,
                arrowRight = true,
                enabled = year.value != maxDate.year || month.value != maxDate.month,
                onClick = {
                    if (month.value == 12) {
                        month.value = 1
                        year.value++
                    } else {
                        month.value++
                    }
                }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            titles.forEachIndexed { index, s ->
                TextBox(
                    text = s,
                    modifier = Modifier.weight(1f),
                    textColor = if (index > 4) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
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
 * 为什么抽出来：这套组件已经有完整能力 —— 星期头是单字「一 二 三 四 五 六 日」、年月导航带年份下拉、
 * 起止两端点击成一个区间、点区间内部只移动更近的一端、同一天点两次=单日、有上下限与最大跨度，
 * 并且**几何全在自己手里**（不依赖任何框架内部尺寸）。搜索页的「自定义时间区间」需要的就是它。
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
    /** 是否显示日历上方那行摘要（单日模式且空间紧张时可以关掉）。 */
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
    val monthStartWeek = remember(year.value, month.value) {
        getWeek(year.value, month.value, 1)
    }
    val monthDayNum = remember(year.value, month.value) {
        getMonthDayNum(year.value, month.value)
    }
    // 行数 = ⌈(月首列偏移 + 当月天数) / 7⌉：月底不再多画一整行空白
    val rowCount = remember(year.value, month.value) {
        getCalendarRowCount(year.value, month.value)
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
    // 摘要放日历上方：原来那行在日历下方，会被底部"确定"按钮压住
    val summaryText = if (singleDate) {
        if (selectedStart == null) "点一个日期" else "已选 ${selectedStart.getValue("-")}"
    } else {
        when {
            selectedStart == null -> "点日期选择开始与结束（最多 $MAX_SPAN_DAYS 天）"
            selectedEnd == null -> "开始 ${selectedStart.getValue("-")}，请再点一个日期作为结束（最多 $MAX_SPAN_DAYS 天）"
            else -> "${selectedStart.getValue("-")} → ${selectedEnd.getValue("-")}（共 ${spanDays(selectedStart, selectedEnd)} 天，上限 $MAX_SPAN_DAYS 天）"
        }
    }

    Column() {

        if (showSummary) Text(
            text = summaryText,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Header(
            year = year,
            month = month,
            maxDate = maxDate,
            minDate = minDate,
        )

        Column(
//            modifier = Modifier.height(350.dp)
        ) {
            for (row in 0 until rowCount) {
                val num = row * 7
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val isMaxMonth = (year.value == maxDate.year && month.value == maxDate.month)
                    for (dayOfWeek in num..(num + 6)) {
                        val day = dayOfWeek - monthStartWeek + 1
                        if (day in 1..monthDayNum && !(isMaxMonth && day > maxDate.date)) {
                            val curTime = dateOf(year.value, month.value, day)
                            val _startTime = startTime
                            val _endTime = endTime

                            val status = if (singleDate) {
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

                            TextBox(
                                text =  day.toString(),
                                showRangeLabels = !singleDate,
                                modifier = Modifier.weight(1f),
                                textColor = if (status == TextBoxStatus.Disable) {
                                    // 禁用
                                    MaterialTheme.colorScheme.outlineVariant
                                } else if (dayOfWeek % 7 > 4) {
                                    // 周六、日
                                    MaterialTheme.colorScheme.onBackground
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                height = 48.dp,
                                status = status,
                                onClick = {
                                    itemClick(day)
                                }
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

//@Preview
//@Composable
//fun CustomTimePreview() {
//    CurrentTime()
//}

/** 把日期夹进 `[minDate, maxDate]`（单日模式用；月份导航本来也会挡，但直接调进来时仍需兜底）。 */
private fun clampToSelectable(date: DateModel, minDate: DateModel, maxDate: DateModel): DateModel = when {
    daysBetween(date, minDate) > 0 -> minDate
    daysBetween(maxDate, date) > 0 -> maxDate
    else -> date
}
