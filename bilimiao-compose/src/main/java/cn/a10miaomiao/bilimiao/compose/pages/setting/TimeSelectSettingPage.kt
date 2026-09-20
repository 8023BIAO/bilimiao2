package cn.a10miaomiao.bilimiao.compose.pages.setting

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.a10miaomiao.bilimiao.compose.base.BottomSheetState
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.preference.rememberPreferenceFlow
import cn.a10miaomiao.bilimiao.compose.components.preference.textIntPreference
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.entity.region.RegionInfo
import com.a10miaomiao.bilimiao.comm.store.RegionStore
import com.a10miaomiao.bilimiao.comm.utils.TimeSelectUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.preference
import me.zhanghai.compose.preference.preferenceCategory
import me.zhanghai.compose.preference.switchPreference
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
object TimeSelectSettingPage : ComposePage() {

    @Composable
    override fun Content() {
        TimeSelectSettingPageContent()
    }
}

@Composable
private fun TimeSelectSettingPageContent() {
    PageConfig(title = "时光精选设置")
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = remember { SettingPreferences.run { context.dataStore } }
    val bottomSheetState by rememberInstance<BottomSheetState>()
    val regionStore by rememberInstance<RegionStore>()

    // 选中的分区（从 DataStore 读）
    var selectedRegionIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // 排序权重：整页共用一份状态，避免每个滑块各存一份快照互相覆盖
    var weightsStr by remember { mutableStateOf(SettingConstants.TIME_SELECT_DEFAULT_WEIGHTS) }
    val updateWeights: (String) -> Unit = { newWeights ->
        weightsStr = newWeights
        scope.launch {
            SettingPreferences.edit(context) { prefs ->
                prefs[SettingPreferences.TimeSelectWeights] = newWeights
            }
        }
    }

    // 监听 bottom sheet 关闭事件，刷新已选分区数量（首次进入时 bottomSheetPage 为 null 也会执行）
    val bottomSheetPage by bottomSheetState.page.collectAsStateWithLifecycle()
    LaunchedEffect(bottomSheetPage) {
        if (bottomSheetPage == null) {
            selectedRegionIds = SettingPreferences.mapData(context) { prefs ->
                prefs[SettingPreferences.TimeSelectSelectedRegions]
                    ?.mapNotNull { it.toIntOrNull() }
                    ?.toSet()
                    ?: emptySet()
            }
        }
    }

    // 权重与分区都改成异步读取，避免在组合阶段 runBlocking 阻塞主线程
    LaunchedEffect(Unit) {
        weightsStr = SettingPreferences.mapData(context) { prefs ->
            prefs[SettingPreferences.TimeSelectWeights]
                ?: SettingConstants.TIME_SELECT_DEFAULT_WEIGHTS
        }
    }

    ProvidePreferenceLocals(
        flow = rememberPreferenceFlow(dataStore)
    ) {
        val allRegionsFlow = remember(dataStore) {
            dataStore.data.map { it[SettingPreferences.TimeSelectAllRegions] ?: true }
        }
        val allRegionsValue by allRegionsFlow.collectAsStateWithLifecycle(initialValue = true)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = windowInsets.leftDp.dp,
                    end = windowInsets.rightDp.dp,
                )
        ) {
            item("top") {
                Spacer(modifier = Modifier.height(windowInsets.topDp.dp))
            }

            // ========== 时间线 ==========
            preferenceCategory(
                key = "time",
                title = { Text("时间线设置") }
            )
            textIntPreference(
                key = SettingPreferences.TimeSelectExcludeRecent.name,
                defaultValue = 0,
                title = { Text("排除最近N天") },
                summary = { Text(if (it <= 0) "不过滤，展示全部时间" else "排除最近 ${it} 天的视频") },
                label = "天",
            )

            // ========== 排序权重 ==========
            preferenceCategory(
                key = "weights",
                title = { Text("排序权重") }
            )
            item(key = "weight_desc") {
                val weights = TimeSelectUtil.parseWeights(weightsStr)
                Text(
                    text = "当前公式：${TimeSelectUtil.weightsToDisplayString(weights)}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            weightSliderItem("weight_favorite", "favorite", "收藏", 75, weightsStr, updateWeights)
            weightSliderItem("weight_click", "click", "播放", 15, weightsStr, updateWeights)
            weightSliderItem("weight_danmaku", "danmaku", "弹幕", 5, weightsStr, updateWeights)
            weightSliderItem("weight_reply", "reply", "评论", 5, weightsStr, updateWeights)

            // ========== 分区选择 ==========
            preferenceCategory(
                key = "regions",
                title = { Text("分区选择") }
            )
            switchPreference(
                key = SettingPreferences.TimeSelectAllRegions.name,
                defaultValue = true,
                title = { Text("全部分区") },
                summary = { Text(if (it) "遍历所有分区" else "手动选择分区") },
            )
            // 非全部分区模式时显示"选择分区"按钮
            if (!allRegionsValue) {
                preference(
                    key = "select_regions",
                    title = { Text("选择分区") },
                    summary = {
                        val count = selectedRegionIds.size
                        Text(if (count > 0) "已选 $count 个分区" else "点击选择分区")
                    },
                    onClick = {
                        bottomSheetState.open(RegionSelectPage())
                    },
                )
            }

            // ========== 过滤 ==========
            preferenceCategory(
                key = "filter",
                title = { Text("过滤") }
            )
            textIntPreference(
                key = SettingPreferences.TimeSelectMinDuration.name,
                defaultValue = 0,
                title = { Text("最小时长(秒)") },
                summary = { Text("过滤短视频，0=不过滤") },
                label = "秒",
            )
            textIntPreference(
                key = SettingPreferences.TimeSelectMinPlayCount.name,
                defaultValue = 0,
                title = { Text("最小播放量") },
                summary = { Text("低于此不展示，0=不过滤") },
                label = "个",
            )
            switchPreference(
                key = SettingPreferences.TimeSelectOriginalOnly.name,
                defaultValue = true,
                title = { Text("只看原创") },
                summary = { Text("过滤转载内容") },
            )

            item("bottom") {
                Spacer(
                    modifier = Modifier.height(
                        windowInsets.bottomDp.dp + windowStore.bottomAppBarHeightDp.dp
                    )
                )
            }
        }
    }
}

private fun LazyListScope.weightSliderItem(
    keyPrefix: String,
    weightKey: String,
    label: String,
    defaultValue: Int,
    weightsStr: String,
    onWeightsChange: (String) -> Unit,
) {
    item(key = keyPrefix, contentType = "WeightSlider") {
        val weights = TimeSelectUtil.parseWeights(weightsStr)
        var sliderValue by remember { mutableIntStateOf(weights[weightKey] ?: defaultValue) }
        // 权重是异步从 DataStore 读出来的：首帧只有内置默认值 75/15/5/5，
        // 数据到达后必须同步给滑块，否则滑块永远停在默认位置、和上面"当前公式"显示的值对不上
        val savedWeight = weights[weightKey]
        LaunchedEffect(savedWeight) {
            if (savedWeight != null) {
                sliderValue = savedWeight
            }
        }

        me.zhanghai.compose.preference.SliderPreference(
            value = sliderValue.toFloat(),
            onValueChange = { newValue ->
                sliderValue = newValue.toInt()
                // 以整页共享的最新权重为基底，避免把其它滑块的改动覆盖回旧值
                val currentWeights = TimeSelectUtil.parseWeights(weightsStr).toMutableMap()
                currentWeights[weightKey] = newValue.toInt()
                onWeightsChange(TimeSelectUtil.formatWeights(currentWeights))
            },
            sliderValue = sliderValue.toFloat(),
            onSliderValueChange = { sliderValue = it.toInt() },
            title = { Text("$label: ${sliderValue}%") },
            modifier = Modifier.fillMaxWidth(),
            valueRange = 0f..100f,
            valueSteps = 5,
        )
    }
}
