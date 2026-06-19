package cn.a10miaomiao.bilimiao.compose.pages.setting

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.navigation.NavBackStackEntry
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.preference.rememberPreferenceFlow
import cn.a10miaomiao.bilimiao.compose.components.preference.listStylePreference
import cn.a10miaomiao.bilimiao.compose.components.preference.textIntPreference
import cn.a10miaomiao.bilimiao.compose.components.preference.sliderIntPreference
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreferenceType
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.listPreference
import me.zhanghai.compose.preference.preference
import me.zhanghai.compose.preference.preferenceCategory
import me.zhanghai.compose.preference.switchPreference
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
class HomeSettingPage : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: HomeSettingPageViewModel = diViewModel()
        HomeSettingPageContent(viewModel)
    }
}

private class HomeSettingPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    val entryViews = mapOf(
        SettingConstants.HOME_ENTRY_VIEW_DEFAULT to "默认",
        SettingConstants.HOME_ENTRY_VIEW_RECOMMEND to "推荐",
        SettingConstants.HOME_ENTRY_VIEW_POPULAR to "热门",
        SettingConstants.HOME_ENTRY_VIEW_BANGUMI to "番剧",
        SettingConstants.HOME_ENTRY_VIEW_CINEMA to "影视",
        // SettingConstants.HOME_ENTRY_VIEW_TIME_SELECT to "时光精选",
    )

    // ====== 时光精选已禁用 ======
    // fun toTimeSelectSettingPage() {
    //     pageNavigation.navigate(TimeSelectSettingPage)
    // }

}


@Composable
private fun HomeSettingPageContent(
    viewModel: HomeSettingPageViewModel
) {
    PageConfig(
        title = "首页设置"
    )
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val context = LocalContext.current
    val dataStore = remember {
        SettingPreferences.run { context.dataStore }
    }

    // ====== 时光精选已禁用 ======
    // var showTimeSelect by remember {
    //     mutableStateOf(
    //         kotlinx.coroutines.runBlocking {
    //             SettingPreferences.run {
    //                 SettingPreferences.mapData(context) { prefs ->
    //                     prefs[SettingPreferences.TimeSelectShow] ?: true
    //                 }
    //             }
    //         }
    //     )
    // }
    // LaunchedEffect(dataStore) {
    //     dataStore.data.collect { prefs ->
    //         showTimeSelect = prefs[SettingPreferences.TimeSelectShow] ?: true
    //     }
    // }

    val entryViewValues = viewModel.entryViews.keys.toList()

    ProvidePreferenceLocals(
        flow = rememberPreferenceFlow(dataStore)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = windowInsets.leftDp.dp,
                    end = windowInsets.rightDp.dp,
                )
        ) {
            item("top") {
                Spacer(
                    modifier = Modifier.height(windowInsets.topDp.dp)
                )
            }
            preferenceCategory(
                key = "top_nav",
                title = {
                    Text("首页顶部设置")
                }
            )
            listPreference(
                key = SettingPreferences.HomeEntryView.name,
                defaultValue = SettingConstants.HOME_ENTRY_VIEW_DEFAULT,
                type = ListPreferenceType.DROPDOWN_MENU,
                title = {
                    Text("首页入口")
                },
                summary = {
                    Text(text = "当前: " + viewModel.entryViews[it])
                },
                values = entryViewValues,
                valueToText = {
                    val text = viewModel.entryViews[it]
                    AnnotatedString(text ?: "未知")
                },
            )
            switchPreference(
                key = SettingPreferences.HomeTimeMachineShow.name,
                title = {
                    Text("显示时光姬")
                },
                defaultValue = false,
            )
            // switchPreference(
            //     key = SettingPreferences.TimeSelectShow.name,
            //     title = {
            //         Text("显示时光精选")
            //     },
            //     defaultValue = true,
            // )
            switchPreference(
                key = SettingPreferences.HomeRecommendShow.name,
                title = {
                    Text("显示推荐")
                },
                defaultValue = true,
            )
            switchPreference(
                key = SettingPreferences.HomePopularShow.name,
                title = {
                    Text("显示热门")
                },
                defaultValue = false,
            )
            switchPreference(
                key = SettingPreferences.HomeBangumiShow.name,
                title = {
                    Text("显示番剧")
                },
                defaultValue = true,
            )
            switchPreference(
                key = SettingPreferences.HomeCinemaShow.name,
                title = {
                    Text("显示影视")
                },
                defaultValue = false,
            )

            // ====== 时光精选已禁用 ======
            // preference(
            //     key = "time_select",
            //     title = { Text("时光精选设置") },
            //     summary = { Text("自定义精选高质量旧视频的算法") },
            //     enabled = showTimeSelect,
            //     onClick = if (showTimeSelect) viewModel::toTimeSelectSettingPage else null,
            // )

            // 【已删除】"热门设置"分类 + "个性化热门列表"开关 — B站热门API不支持个性化，开关无实际作用

            preferenceCategory(
                key = "recommend",
                title = {
                    Text("推荐设置")
                }
            )
            listStylePreference(
                key = SettingPreferences.HomeRecommendListStyle.name,
                defaultValue = 0,
            )

            preferenceCategory(
                key = "bangumi",
                title = {
                    Text("番剧/影视设置")
                }
            )
            sliderIntPreference(
                key = SettingPreferences.HomeBangumiGridSpan.name,
                title = {
                    Text("每行卡片数")
                },
                valueRange = 0..5,
                valueSteps = 5,
                defaultValue = 0,
                valueText = {
                    Text(if (it == 0) "自适应" else "${it}列")
                }
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
