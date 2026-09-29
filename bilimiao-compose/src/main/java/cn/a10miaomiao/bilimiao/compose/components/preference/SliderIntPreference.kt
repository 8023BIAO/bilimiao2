package cn.a10miaomiao.bilimiao.compose.components.preference

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.zhanghai.compose.preference.SliderPreference
import me.zhanghai.compose.preference.rememberPreferenceState
import kotlin.math.roundToInt

inline fun LazyListScope.sliderIntPreference(
    key: String,
    defaultValue: Int,
    crossinline title: @Composable (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    crossinline rememberState: @Composable () -> MutableState<Int> = {
        rememberPreferenceState(key, defaultValue)
    },
    valueRange: ClosedRange<Int> = 0..100,
    valueSteps: Int = 0,
    crossinline rememberSliderState: @Composable (Int) -> MutableState<Int> = {
        remember { mutableIntStateOf(it) }
    },
    crossinline enabled: (Int) -> Boolean = { true },
    noinline icon: @Composable ((Int) -> Unit)? = null,
    noinline summary: @Composable ((Int) -> Unit)? = null,
    noinline valueText: @Composable ((Int) -> Unit)? = null
) {
    item(key = key, contentType = "SliderIntPreference") {
        val state = rememberState()
        val value by state
        // 存量越界值（老版输入框能写进 0/40）不能带进滑块：
        // M3 Slider 的 value 超出 valueRange 时，行尾 valueText 仍会拿到原值（显示 40sp），
        // 而实际生效值是夹过的 —— 显示与行为不一致。这里只夹"传进去的初始值"，
        // 不夹 onSliderValueChange 的回调值（拖动产出的值必然落在 range 内，夹了是死代码）。
        val sliderState = rememberSliderState(value.coerceIn(valueRange))
        val sliderValue by sliderState
        SliderIntPreference(
            state = state,
            title = { title(sliderValue) },
            modifier = modifier,
            valueRange = valueRange,
            valueSteps = valueSteps,
            sliderState = sliderState,
            enabled = enabled(value),
            icon = icon?.let { { it(sliderValue) } },
            summary = summary?.let { { it(sliderValue) } },
            valueText = valueText?.let { { it(sliderValue) } }
        )
    }
}

@Composable
fun SliderIntPreference(
    state: MutableState<Int>,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedRange<Int> = 0..100,
    valueSteps: Int = 0,
    sliderState: MutableState<Int> = remember { mutableIntStateOf(state.value) },
    enabled: Boolean = true,
    icon: @Composable (() -> Unit)? = null,
    summary: @Composable (() -> Unit)? = null,
    valueText: @Composable (() -> Unit)? = null
) {
    var value by state
    var sliderValue by sliderState
    SliderPreference(
        value = value.toFloat(),
        // roundToInt 而不是 toInt：M3 滑块的值是 lerp 算出来的，可能得到 15.999999，
        // 截断会变成 15 → 第 16/22 档这种位置永远取不到（审查发现）
        onValueChange = { value = it.roundToInt() },
        sliderValue = sliderValue.toFloat(),
        onSliderValueChange = { sliderValue = it.roundToInt() },
        title = title,
        modifier = modifier,
        valueRange = valueRange.start.toFloat()..valueRange.endInclusive.toFloat(),
        valueSteps = valueSteps,
        enabled = enabled,
        icon = icon,
        summary = summary,
        valueText = valueText
    )
}