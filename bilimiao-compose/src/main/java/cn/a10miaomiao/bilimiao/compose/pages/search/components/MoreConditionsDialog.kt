package cn.a10miaomiao.bilimiao.compose.pages.search.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import cn.a10miaomiao.bilimiao.compose.components.dialogs.OverlayAlertDialog
import cn.a10miaomiao.bilimiao.compose.components.dialogs.OverlayCardDialog
import cn.a10miaomiao.bilimiao.compose.pages.time.components.CustomTimeContent
import com.a10miaomiao.bilimiao.comm.store.RegionStore
import com.a10miaomiao.bilimiao.comm.store.model.DateModel
import org.kodein.di.instance

data class MoreConditionsInfo(
    val timeType: Int,
    val durationList: List<Int>,
    val regionList: List<Int>,
    /**
     * 自定义时间区间（epoch 秒；0 = 未设置）。
     * 对应 gRPC `SearchAllRequest.timeFrom/timeTo` —— proto 里本来就有这两个字段，只是界面从没暴露过。
     * 与 [timeType]（"最近 N 天"）互不冲突：用户想用哪个用哪个，服务端自行取舍。
     */
    val timeFrom: Long = 0,
    val timeTo: Long = 0,
)

@Stable
internal class MoreConditionsDialogState(
    regionStore: RegionStore,
    val onConfirm: () -> Unit,
) {

    private val _visible = mutableStateOf(false)
    val visible get() = _visible.value

    val timeTypeList = listOf(
        0 to "不限",
        1 to "最近一天",
        7 to "最近一周",
        180 to "最近半年",
    )
    private val _timeTypeSelected = mutableIntStateOf(0)
    val timeTypeSelected get() = _timeTypeSelected.intValue

    val durationList = listOf(
        0 to "不限",
        1 to "0-10分钟",
        2 to "10-30分钟",
        3 to "30-60分钟",
        4 to "60分钟+",
    )
    private val _durationSelectedList = mutableStateOf(listOf(0))
    val durationSelectedList get() = _durationSelectedList.value

    val regionList = listOf(
        0 to "不限",
        *regionStore.state.regions.map {
            it.tid to it.name
        }.toTypedArray()
    )
    private val _regionSelectedList = mutableStateOf(listOf(0))
    val regionSelectedList get() = _regionSelectedList.value

    /** 自定义时间区间（epoch 秒，0 = 未设置）：可观察状态，弹窗里的日期按钮才能跟着重画 */
    private val _timeFrom = mutableLongStateOf(0L)
    val timeFrom get() = _timeFrom.longValue
    private val _timeTo = mutableLongStateOf(0L)
    val timeTo get() = _timeTo.longValue

    private var _data = MoreConditionsInfo(
        timeType = timeTypeSelected,
        durationList = durationSelectedList,
        regionList = regionSelectedList,
        timeFrom = timeFrom,
        timeTo = timeTo,
    )
    val data get() = _data

    fun handleSelectedTimeType(timeType: Int) {
        _timeTypeSelected.intValue = timeType
    }

    fun handleSelectedDuration(duration: Int) {
        if (duration == 0) {
            _durationSelectedList.value = listOf(0)
        } else if (durationSelectedList.indexOf(duration) == -1) {
            _durationSelectedList.value = listOf(
                *durationSelectedList.filter { it != 0 }.toTypedArray(), // 移除全部时长
                duration,
            )
        } else {
            _durationSelectedList.value = durationSelectedList.filter {
                it != duration
            }
            if (durationSelectedList.isEmpty()) {
                _durationSelectedList.value = listOf(0)
            }
        }
    }

    fun handleSelectedRegion(region: Int) {
        if (region == 0) {
            _regionSelectedList.value = listOf(0)
        } else if (regionSelectedList.indexOf(region) == -1) {
            _regionSelectedList.value = listOf(
                *regionSelectedList.filter { it != 0 }.toTypedArray(), // 移除全部时长
                region,
            )
        } else {
            _regionSelectedList.value = regionSelectedList.filter {
                it != region
            }
            if (regionSelectedList.isEmpty()) {
                _regionSelectedList.value = listOf(0)
            }
        }
    }

    fun open() {
        _timeTypeSelected.intValue = data.timeType
        _durationSelectedList.value = data.durationList
        _regionSelectedList.value = data.regionList
        _timeFrom.value = data.timeFrom
        _timeTo.value = data.timeTo
        _visible.value = true
    }

    /** 选「起始日期」（epoch 秒；0 = 清空）。起 > 止 时顺手把止清掉，别让用户对着矛盾区间发懵 */
    fun handleSetTimeFrom(seconds: Long) {
        _timeFrom.value = seconds
        if (seconds > 0 && timeTo > 0 && timeTo < seconds) _timeTo.value = 0L
    }

    /** 选「结束日期」（epoch 秒；0 = 清空） */
    fun handleSetTimeTo(seconds: Long) {
        _timeTo.value = seconds
        if (seconds > 0 && timeFrom > seconds) _timeFrom.value = 0L
    }

    fun clearTimeRange() {
        _timeFrom.value = 0L
        _timeTo.value = 0L
    }

    fun close() {
        _visible.value = false
    }

    fun handleDismiss() {
        close()
    }

    fun handleConfirm() {
        _data = MoreConditionsInfo(
            timeType = timeTypeSelected,
            durationList = durationSelectedList,
            regionList = regionSelectedList,
            timeFrom = timeFrom,
            timeTo = timeTo,
        )
        onConfirm()
        close()
    }

    /**
     * 整份选择的持久化快照（写进 `SettingPreferences.SearchFilter`）。
     * 用 JSON 而不是分隔符拼接：分区/时长本来就是多选，再加两个时间戳，分隔符方案会越来越脆。
     * DataStore 读写由 ViewModel 负责，这里只做纯序列化（方便复用/单测）。
     */
    fun snapshotJson(): String = JSONObject().apply {
        put("t", data.timeType)
        put("d", JSONArray(data.durationList))
        put("r", JSONArray(data.regionList))
        put("from", data.timeFrom)
        put("to", data.timeTo)
    }.toString()

    /** 从 [snapshotJson] 的结果恢复；脏数据就当没存过（保持"不限/不限/不限"），绝不让搜索页起不来 */
    fun restoreFrom(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val o = JSONObject(json)
            _data = MoreConditionsInfo(
                timeType = o.optInt("t", 0),
                durationList = o.optJSONArray("d").toIntList().ifEmpty { listOf(0) },
                regionList = o.optJSONArray("r").toIntList().ifEmpty { listOf(0) },
                // ★入口隐藏期间**必须丢弃**旧的自定义区间：否则用户在旧版本存下的 from/to 会一直
                //   静默生效（还会让标题显示"已筛选"），而界面上没有任何地方能清除它。
                //   入口恢复（ShowCustomTimeRange = true）时这两行也要跟着恢复读取。
                timeFrom = if (ShowCustomTimeRange) o.optLong("from", 0L) else 0L,
                timeTo = if (ShowCustomTimeRange) o.optLong("to", 0L) else 0L,
            )
            _timeTypeSelected.intValue = _data.timeType
            _durationSelectedList.value = _data.durationList
            _regionSelectedList.value = _data.regionList
            _timeFrom.value = _data.timeFrom
            _timeTo.value = _data.timeTo
        } catch (_: Exception) {
            // 脏数据：保持默认值即可，不打断用户
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun MoreConditionsDialog(
    state: MoreConditionsDialogState,
) {
    val timeTypeSelected = state.timeTypeSelected
    val durationSelectedList = state.durationSelectedList
    val regionSelectedList = state.regionSelectedList
    // 0 = 没在选日期；1 = 正在选起始；2 = 正在选结束
    var pickerTarget by remember { mutableStateOf(0) }
    val leadingIcon: @Composable () -> Unit = {
        Icon(Icons.Default.Check, null)
    }
    if (state.visible) {
        OverlayAlertDialog(
            title = {
                Text("搜索筛选")
            },
            text = {
                // 滚动交给 OverlayAlertDialog 的正文区域（自己再套一层 verticalScroll 会抢手势）
                Column {
                    Text(
                        "发布时间",
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.timeTypeList.forEach { duration ->
                            val selected = timeTypeSelected == duration.first
                            FilterChip(
                                selected,
                                onClick = { state.run { handleSelectedTimeType(duration.first) } },
                                label = { Text(duration.second) },
                            )
                        }
                    }
                    Text(
                        "内容时长",
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.durationList.forEach { duration ->
                            val selected = durationSelectedList.indexOf(duration.first) != -1
                            FilterChip(
                                selected,
                                onClick = { state.run { handleSelectedDuration(duration.first) } },
                                label = { Text(duration.second) },
                            )
                        }
                    }
                    Text(
                        "内容分区",
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.regionList.forEach { region ->
                            val selected = regionSelectedList.indexOf(region.first) != -1
                            FilterChip(
                                selected,
                                onClick = { state.run { handleSelectedRegion(region.first) } },
                                label = { Text(region.second) },
                            )
                        }
                    }
                    if (ShowCustomTimeRange) {
                        Text(
                            "时间区间",
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = { pickerTarget = 1 }) {
                                Text(if (state.timeFrom > 0) formatDay(state.timeFrom) else "起始日期")
                            }
                            OutlinedButton(onClick = { pickerTarget = 2 }) {
                                Text(if (state.timeTo > 0) formatDay(state.timeTo) else "结束日期")
                            }
                            if (state.timeFrom > 0 || state.timeTo > 0) {
                                TextButton(onClick = state::clearTimeRange) {
                                    Text("清除")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = state::handleConfirm
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = state::handleDismiss
                ) {
                    Text("取消")
                }
            },
            onDismissRequest = state::handleDismiss
        )
    }

    // 自定义时间区间：复用时光姬那套自绘日历（星期头是单字「一 二 三 四 五 六 日」，
    // 几何全在组件自己手里，不依赖 M3 内部尺寸，因此换手机/换字号都不会错位）。
    // ★弃用 M3 `DatePicker` 的原因：它的星期头在中文设备上七列全「星」，而 `CalendarModel` 等
    //   都是 material3 的 internal 类，没有任何公开入口改文字或隐藏该行。
    if (pickerTarget != 0) {
        val today = remember { DateModel().setDate(Date()) }
        // from/to 是"本地日 00:00:00 / 23:59:59"的 epoch 秒；未设时给 0 ⇒ 组件不预选
        val fromDate = remember(pickerTarget) {
            if (state.timeFrom > 0) localDateOf(state.timeFrom) else DateModel().also { it.year = 0 }
        }
        val toDate = remember(pickerTarget) {
            if (state.timeTo > 0) localDateOf(state.timeTo) else DateModel().also { it.year = 0 }
        }
        var range by remember(pickerTarget) { mutableStateOf<Pair<DateModel, DateModel>?>(null) }
        OverlayCardDialog(
            onDismissRequest = { pickerTarget = 0 },
            scrollable = false,
        ) {
            CustomTimeContent(
                timeFrom = fromDate,
                timeTo = toDate,
                minDate = MinSelectableDate,
                maxDate = today,
                onRangeChange = { start, end ->
                    // end == null = 只点了第一端（组件用"未选完整"表达），此时不允许确定
                    range = if (start != null && end != null) start to end else null
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { pickerTarget = 0 }) {
                    Text("取消")
                }
                TextButton(
                    enabled = range != null,
                    onClick = {
                        range?.let { (start, end) ->
                            state.handleSetTimeFrom(localDayStartSeconds(start))
                            // 结束日含当天：补到本地 23:59:59，否则选「今天」会查不到今天发的内容
                            state.handleSetTimeTo(localDayStartSeconds(end) + 86399)
                        }
                        pickerTarget = 0
                    },
                ) {
                    Text("确定")
                }
            }
        }
    }
}



/**
 * epoch 秒 → "yyyy-MM-dd"，按**设备默认时区**显示（只用于日期按钮文案）。
 * 两个时间戳在库里都存"本地日对齐"的值，所以这里直接按本地时区格式化即自洽。
 */
private fun formatDay(seconds: Long): String =
    Calendar.getInstance().apply { timeInMillis = seconds * 1000 }
        .let { "%04d-%02d-%02d".format(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }


private fun JSONArray?.toIntList(): List<Int> {
    val arr = this ?: return emptyList()
    val out = ArrayList<Int>(arr.length())
    for (i in 0 until arr.length()) out.add(arr.optInt(i))
    return out
}


/**
 * 「搜索筛选」弹窗里是否显示**自定义时间区间**入口。
 *
 * 2026-10-13：弃用 M3 `DatePicker`（中文星期头七列全「星」，且无公开入口可改），
 * 改用时光姬那套自绘日历 `CustomTimeContent` ⇒ 入口已打开。
 */
private const val ShowCustomTimeRange = true

/** 可选日期下限（B 站内容起点），与时光姬保持一致。 */
private val MinSelectableDate = DateModel().also { it.year = 2009; it.month = 1; it.date = 1 }

/** 本地日 → `DateModel`（只取年月日；入库的 epoch 秒本来就是"本地日对齐"的）。 */
private fun localDateOf(localDaySeconds: Long): DateModel =
    DateModel().setDate(Date(localDaySeconds * 1000L))

/** `DateModel` 这一天在**设备时区** 00:00:00 的 epoch 秒。 */
private fun localDayStartSeconds(d: DateModel): Long =
    Calendar.getInstance().apply {
        clear()
        set(d.year, d.month - 1, d.date, 0, 0, 0)
    }.timeInMillis / 1000L
