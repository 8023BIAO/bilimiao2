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
    status: TextBoxStatus = TextBoxStatus.Enable
) {
    Surface(
        modifier = if (onClick != null) {
            modifier.clickable(onClick = onClick)
        } else modifier,
        color = if (status == TextBoxStatus.Start || status == TextBoxStatus.End) {
            MaterialTheme.colorScheme.tertiaryContainer
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
                          it.background(MaterialTheme.colorScheme.tertiaryContainer)
                      } else it
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = text,
                color = textColor
            )
            if (status == TextBoxStatus.Start) {
                Text(
                    text = "起",
                    color = textColor,
                    fontSize = 10.sp,
                )
            } else if (status == TextBoxStatus.End) {
                Text(
                    text = "止",
                    color = textColor,
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

@Composable
internal fun CustomTime(
    viewModel: TimeSettingViewMode
) {
    val customTime = viewModel.customTime.collectAsStateWithLifecycle()
    val timeFrom = customTime.value.timeFrom
    val timeTo = customTime.value.timeTo
    val maxDate = viewModel.maxDate
    val minDate = viewModel.minDate

    var year = remember {
        mutableStateOf(2009)
    }
    var month = remember {
        mutableStateOf(9)
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

    LaunchedEffect(Unit) {
        if (timeFrom.year != -1) {
            startTime = timeFrom.copy()
            endTime = timeTo.copy()
            year.value = timeFrom.year
            month.value = timeFrom.month
        }
    }

    val itemClick = remember(viewModel) {
        { i: Int ->
            val clicked = dateOf(year.value, month.value, i)
            val _startTime = startTime
            val _endTime = endTime
            when {
                // 还没选开始：落一个起点，并告诉 ViewModel"未选完整"
                //（否则点一下再按"确定"会把上一次的旧区间静默存下去）
                _startTime == null -> {
                    startTime = clicked
                    endTime = null
                    viewModel.setCustomTime(clicked, null)
                }
                // 选了开始、还没选结束
                _endTime == null -> {
                    val gap = daysBetween(_startTime, clicked) // 有符号整日差
                    if (gap == 0) {
                        // 同一天点两次 = 单日区间（以前 gap==0 时 > 和 < 都不成立，选择会一直悬着）
                        endTime = clicked
                        viewModel.setCustomTime(startTime, endTime)
                    } else if (spanDays(_startTime, clicked) > MAX_SPAN_DAYS) {
                        // 超过上限给明确反馈，不再静默：起点保留，等用户点一个更近的日期
                        toast("最多只能选 $MAX_SPAN_DAYS 天，请重新选择")
                    } else if (gap > 0) {
                        endTime = clicked
                        viewModel.setCustomTime(startTime, endTime)
                    } else {
                        startTime = clicked
                        endTime = _startTime
                        viewModel.setCustomTime(startTime, endTime)
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
                        viewModel.setCustomTime(startTime, endTime)
                    } else {
                        // 点在区间外 → 以这一格为新起点重新选，保留"再点一次可以重来"的旧手感
                        startTime = clicked
                        endTime = null
                        viewModel.setCustomTime(clicked, null)
                    }
                }
            }
            Unit
        }
    }

    val selectedStart = startTime
    val selectedEnd = endTime
    // 摘要放日历上方：原来那行在日历下方，会被底部"确定"按钮压住
    val summaryText = when {
        selectedStart == null -> "点日期选择开始与结束（最多 $MAX_SPAN_DAYS 天）"
        selectedEnd == null -> "开始 ${selectedStart.getValue("-")}，请再点一个日期作为结束（最多 $MAX_SPAN_DAYS 天）"
        else -> "${selectedStart.getValue("-")} → ${selectedEnd.getValue("-")}（共 ${spanDays(selectedStart, selectedEnd)} 天，上限 $MAX_SPAN_DAYS 天）"
    }

    Column() {

        Text(
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

                            val status = if (_startTime == null) {
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