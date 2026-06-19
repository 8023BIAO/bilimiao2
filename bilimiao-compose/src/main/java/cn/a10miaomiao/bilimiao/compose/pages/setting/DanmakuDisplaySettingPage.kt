package cn.a10miaomiao.bilimiao.compose.pages.setting

import android.os.Build
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.navigation.NavBackStackEntry
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.foundation.pagerTabIndicatorOffset
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.preference.rememberPreferenceFlow
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.pages.setting.content.DanmakuDisplaySettingContent
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.map
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.preference
import me.zhanghai.compose.preference.preferenceCategory
import me.zhanghai.compose.preference.switchPreference
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
data class DanmakuDisplaySettingPage(
    val name: String = "default"
) : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: DanmakuDisplaySettingPageViewModel = diViewModel()
        val initialMode = name
        DanmakuDisplaySettingPageContent(
            viewModel,
            initialMode,
        )
    }
}

private class DanmakuDisplaySettingPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val fragment by instance<Fragment>()

    val modeList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        listOf(
            Pair(
                SettingPreferences.DanmakuDefault,
                "默认",
            ),
            Pair(
                SettingPreferences.DanmakuSmallMode,
                "小屏",
            ),
            Pair(
                SettingPreferences.DanmakuFullMode,
                "全屏",
            ),
            Pair(
                SettingPreferences.DanmakuPipMode,
                "画中画",
            )
        )
    } else {
        listOf(
            Pair(
                SettingPreferences.DanmakuDefault,
                "默认",
            ),
            Pair(
                SettingPreferences.DanmakuSmallMode,
                "小屏",
            ),
            Pair(
                SettingPreferences.DanmakuFullMode,
                "全屏",
            ),
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DanmakuDisplaySettingPageContent(
    viewModel: DanmakuDisplaySettingPageViewModel,
    initialMode: String,
) {
    PageConfig(
        title = "弹幕显示设置"
    )
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        pageCount = {
            viewModel.modeList.size + 1 // +1 for 弹幕过滤 tab
        }
    )
    LaunchedEffect(initialMode) {
        val page = viewModel.modeList.indexOfFirst {
            it.first.name == initialMode
        }
        if (page != -1) {
            val mode = viewModel.modeList[page]
            val enable = SettingPreferences.mapData(context) {
                it[mode.first.enable] ?: false
            }
            if (enable) {
                pagerState.scrollToPage(page)
            }
        }
    }

    Column(
        modifier = Modifier.padding(
            start = windowInsets.leftDp.dp,
            end = windowInsets.rightDp.dp,
        )
    ) {
        TabRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(windowInsets.toPaddingValues(bottom = 0.dp)),
            selectedTabIndex = pagerState.currentPage,
            indicator = { positions ->
                TabRowDefaults.PrimaryIndicator(
                    Modifier.pagerTabIndicatorOffset(pagerState, positions),
                )
            },
        ) {
            viewModel.modeList.forEachIndexed { index, item ->
                val selected = index == pagerState.currentPage
                Tab(
                    text = {
                        Text(
                            text = item.second,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onBackground
                            }
                        )
                    },
                    selected = selected,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                )
            }
            // 弹幕过滤 tab
            val filterSelected = viewModel.modeList.size == pagerState.currentPage
            Tab(
                text = {
                    Text(
                        text = "弹幕过滤",
                        color = if (filterSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onBackground
                        }
                    )
                },
                selected = filterSelected,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(viewModel.modeList.size)
                    }
                },
            )
        }
        HorizontalPager(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            state = pagerState,
        ) { index ->
            if (index < viewModel.modeList.size) {
                val item = viewModel.modeList[index]
                DanmakuDisplaySettingContent(
                    danmakuPreferences = item.first
                )
            } else {
                // 弹幕过滤内容
                DanmakuFilterTabContent(
                    scope = scope,
                    bottomDp = windowInsets.bottomDp,
                )
            }
        }
    }
}

@Composable
private fun DanmakuFilterTabContent(
    scope: kotlinx.coroutines.CoroutineScope,
    bottomDp: kotlin.Float,
) {
    val context = LocalContext.current
    val dataStore = remember {
        SettingPreferences.run { context.dataStore }
    }
    var showFilterDialog by remember { mutableStateOf(false) }
    val filterEnabled by dataStore.data.map {
        it[SettingPreferences.DanmakuFilterEnabled] ?: false
    }.collectAsState(initial = false)
    val keywords by dataStore.data.map {
        it[SettingPreferences.DanmakuFilterKeywords] ?: emptySet()
    }.collectAsState(initial = emptySet())

    ProvidePreferenceLocals(
        flow = rememberPreferenceFlow(dataStore)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            preferenceCategory(
                key = "filter",
                title = { Text("弹幕过滤") }
            )
            switchPreference(
                key = SettingPreferences.DanmakuFilterEnabled.name,
                title = { Text("启用弹幕过滤") },
                summary = {
                    if (it) Text("已启用，将过滤包含关键词的弹幕")
                    else Text("未启用")
                },
                defaultValue = false,
            )

            preference(
                key = "manage_keywords",
                enabled = filterEnabled,
                title = { Text("管理屏蔽关键词") },
                summary = { Text("共 ${keywords.size} 个关键词") },
                onClick = { showFilterDialog = true }
            )

            switchPreference(
                key = SettingPreferences.DanmakuFilterDuplicate.name,
                enabled = { filterEnabled },
                title = { Text("过滤重复弹幕") },
                summary = {
                    if (it) Text("已启用，将过滤内容相同的弹幕")
                    else Text("未启用")
                },
                defaultValue = false,
            )

            item {
                Spacer(modifier = Modifier.height(bottomDp.dp))
            }
        }
    }

    if (showFilterDialog) {
        var newKeyword by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text("管理屏蔽关键词") },
            text = {
                Column {
                    keywords.forEach { kw ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = kw)
                            TextButton(onClick = {
                                scope.launch {
                                    SettingPreferences.edit(context) {
                                        it[SettingPreferences.DanmakuFilterKeywords] = keywords - kw
                                    }
                                }
                            }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (keywords.isEmpty()) {
                        Text("暂无屏蔽关键词")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = newKeyword,
                        onValueChange = { newKeyword = it },
                        label = { Text("新关键词") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default.copy(
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                if (newKeyword.isNotBlank()) {
                                    scope.launch {
                                        SettingPreferences.edit(context) {
                                            it[SettingPreferences.DanmakuFilterKeywords] = keywords + newKeyword.trim()
                                        }
                                        newKeyword = ""
                                    }
                                }
                            }
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "注：支持正则表达式（语法：/正则表达式主体/）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newKeyword.isNotBlank()) {
                        scope.launch {
                            SettingPreferences.edit(context) {
                                it[SettingPreferences.DanmakuFilterKeywords] = keywords + newKeyword.trim()
                            }
                            newKeyword = ""
                        }
                    } else {
                        showFilterDialog = false
                    }
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFilterDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}