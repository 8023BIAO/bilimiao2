package cn.a10miaomiao.bilimiao.compose.pages.search.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import cn.a10miaomiao.bilimiao.compose.components.dialogs.OverlayAlertDialog
import com.a10miaomiao.bilimiao.comm.store.RegionStore
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
                timeFrom = o.optLong("from", 0L),
                timeTo = o.optLong("to", 0L),
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

    // 自定义时间区间：点「起始/结束日期」弹 M3 日期选择器
    if (pickerTarget != 0) {
        // 显式声明 Long：别让编译器去猜 takeIf/times 的类型（第一版就是在这里栽的）
        val seedSeconds: Long = if (pickerTarget == 1) state.timeFrom else state.timeTo
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = if (seedSeconds > 0) seedSeconds * 1000 else null
        )
        DatePickerDialog(
            onDismissRequest = { pickerTarget = 0 },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        val seconds = ms / 1000
                        if (pickerTarget == 1) {
                            state.handleSetTimeFrom(seconds)
                        } else {
                            // 结束日期含当天：补到 23:59:59，否则选"今天"会查不到今天发的内容
                            state.handleSetTimeTo(seconds + 86399)
                        }
                    }
                    pickerTarget = 0
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { pickerTarget = 0 }) {
                    Text("取消")
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** epoch 秒 → "yyyy-MM-dd"（只用于日期按钮文案；用 SimpleDateFormat 而非 java.time，兼容低版本） */
private fun formatDay(seconds: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(seconds * 1000))

private fun JSONArray?.toIntList(): List<Int> {
    val arr = this ?: return emptyList()
    val out = ArrayList<Int>(arr.length())
    for (i in 0 until arr.length()) out.add(arr.optInt(i))
    return out
}
