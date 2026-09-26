package cn.a10miaomiao.bilimiao.compose.pages.home

import android.content.Context
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.navOptions
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.constant.PageTabIds
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.emitter.EmitterAction
import cn.a10miaomiao.bilimiao.compose.common.foundation.combinedTabDoubleClick
import cn.a10miaomiao.bilimiao.compose.common.foundation.pagerTabIndicatorOffset
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localEmitter
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.mypage.rememberMyMenu
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.pages.dynamic.DynamicPage
import cn.a10miaomiao.bilimiao.compose.pages.home.content.HomeBangumiContent
import cn.a10miaomiao.bilimiao.compose.pages.home.content.HomeLiveContent
import cn.a10miaomiao.bilimiao.compose.pages.home.content.HomePopularContent
import cn.a10miaomiao.bilimiao.compose.pages.home.content.HomeRecommendContent
import cn.a10miaomiao.bilimiao.compose.pages.home.content.HomeRegionContent
import cn.a10miaomiao.bilimiao.compose.pages.home.content.HomeTimeMachineContent
import cn.a10miaomiao.bilimiao.compose.pages.home.content.HomeTimeSelectContent
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.delegate.player.BasePlayerDelegate
import com.a10miaomiao.bilimiao.comm.mypage.MenuActions
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.store.AppStore
import com.a10miaomiao.bilimiao.comm.store.AppStore.HomeSettingState
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
object HomePage : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: HomePageViewModel = diViewModel()
        HomePageContent(viewModel)
    }
}

@Stable
private sealed class HomePageTab(
        val id: String,
        val name: String,
) {

    @Composable abstract fun PageContent(pageState: HomePageState)

    /**
     * 直播：顶部横向分类标签条（全部/网游/手游/…，实测 12 个顶级分区 + 子分区）+ 直播房间卡片。
     * 用户明确要求**放在第一个**（"为什么不在首页的第一个 tab 加上直播的分区"），
     * 所以它在 getTabs 里也是第一个被 add 的，排在「时光姬」前面。
     */
    data object Live : HomePageTab(id = PageTabIds.HomeLive, name = "直播") {
        @Composable
        override fun PageContent(pageState: HomePageState) {
            HomeLiveContent()
        }
    }

    data object TimeMachine :
            HomePageTab(
                    id = PageTabIds.HomeTimeMachine,
                    name = "时光姬",
            ) {
        @Composable
        override fun PageContent(pageState: HomePageState) {
            HomeTimeMachineContent(pageState)
        }
    }

    data object Recommend : HomePageTab(id = PageTabIds.HomeRecommend, name = "推荐") {
        @Composable
        override fun PageContent(pageState: HomePageState) {
            HomeRecommendContent()
        }
    }

    data object Popular : HomePageTab(id = PageTabIds.HomePopular, name = "热门") {
        @Composable
        override fun PageContent(pageState: HomePageState) {
            HomePopularContent()
        }
    }

    /**
     * 分区：左侧一条竖排分区条 + 右侧该分区的视频卡片（内容在 HomeRegionContent.kt）。
     * 位置按用户要求插在「热门」之后、「番剧」之前。
     */
    data object Region : HomePageTab(id = PageTabIds.HomeRegion, name = "分区") {
        @Composable
        override fun PageContent(pageState: HomePageState) {
            HomeRegionContent()
        }
    }

    data object Bangumi : HomePageTab(id = PageTabIds.HomeBangumi, name = "番剧") {
        @Composable
        override fun PageContent(pageState: HomePageState) {
            HomeBangumiContent(
                seasonType = 1,
                tabId = PageTabIds.HomeBangumi,
            )
        }
    }

    data object Cinema : HomePageTab(id = PageTabIds.HomeCinema, name = "影视") {
        @Composable
        override fun PageContent(pageState: HomePageState) {
            HomeBangumiContent(
                seasonType = 102,
                tabId = PageTabIds.HomeCinema,
            )
        }
    }

    // ====== 时光精选已禁用 ======
    // data object TimeSelect :
    //         HomePageTab(
    //                 id = PageTabIds.HomeTimeSelect,
    //                 name = "时光精选",
    //         ) {
    //     @Composable
    //     override fun PageContent(pageState: HomePageState) {
    //         HomeTimeSelectContent(pageState)
    //     }
    // }
}

private class HomePageViewModel(
        override val di: DI,
) : ViewModel(), DIAware {

    private val appStore by instance<AppStore>()
    private val fragment by instance<Fragment>()
    private val pageNavigation by instance<PageNavigation>()

    private val playerDelegate by instance<BasePlayerDelegate>()
    private val userStore by instance<UserStore>()

    private var lastBackPressedTime = 0L

    /**
     * 「分区」Tab 显不显示，和热门/番剧/影视一样由 AppStore.HomeSettingState 统一管：
     * 设置页写 datastore → AppStore 的 collector 推新 state → 这里重建 Tab 列表。
     * 早先这里自己又 collect 了一份分区自己的 datastore，等于同一件事两条数据流，
     * 还漏掉了下面 HOME_ENTRY_VIEW_REGION 那个"首页入口"选项，现在并回一条。
     */
    private val _tabs = mutableStateOf(getTabs(appStore.state.home))
    val tabs
        get() = _tabs.value
    var initialPage = 0
        private set
    val pageState = HomePageState(pageNavigation)
    val context: Context by instance()

    /**
     * 番剧/影视 Tab 筛选按钮点击触发
     */
    val filterTrigger = mutableStateOf(false)

    init {
        // 远程init接口已禁用 - 不加载广告/设置列表/版本检查
        // loadAdData()
        viewModelScope.launch {
            appStore.stateFlow.map { it.home }.collect { _tabs.value = getTabs(it) }
        }
    }

    private fun getTabs(setting: HomeSettingState): List<HomePageTab> {
        val entryView = setting.entryView
        val tabs = mutableListOf<HomePageTab>()
        // 直播放最前面（用户要求：首页第一个 Tab 就是直播）。
        // 它和其它 Tab 一样：既能被「显示直播」开关藏掉，也能当「首页入口」的默认落点。
        if (setting.showLive) {
            tabs.add(HomePageTab.Live)
            if (entryView == SettingConstants.HOME_ENTRY_VIEW_LIVE) {
                initialPage = tabs.size - 1
            }
        }
        if (setting.showTimeMachine) {
            tabs.add(HomePageTab.TimeMachine)
            if (entryView == SettingConstants.HOME_ENTRY_VIEW_DEFAULT) {
                initialPage = tabs.size - 1
            }
        }
        // ====== 时光精选已禁用 ======
        // if (setting.showTimeSelect) {
        //     tabs.add(HomePageTab.TimeSelect)
        //     if (entryView == SettingConstants.HOME_ENTRY_VIEW_TIME_SELECT) {
        //         initialPage = tabs.size - 1
        //     }
        // }
        if (setting.showRecommend) {
            tabs.add(HomePageTab.Recommend)
            if (entryView == SettingConstants.HOME_ENTRY_VIEW_RECOMMEND) {
                initialPage = tabs.size - 1
            }
        }
        if (setting.showPopular) {
            tabs.add(HomePageTab.Popular)
            if (entryView == SettingConstants.HOME_ENTRY_VIEW_POPULAR) {
                initialPage = tabs.size - 1
            }
        }
        // 分区：用户要求插在「热门」之后、「番剧」之前。
        // 和上面几个 Tab 一样，既能被显示开关藏掉，也能当「首页入口」的默认落点
        if (setting.showRegion) {
            tabs.add(HomePageTab.Region)
            if (entryView == SettingConstants.HOME_ENTRY_VIEW_REGION) {
                initialPage = tabs.size - 1
            }
        }
        // 番剧、影视
        if (setting.showBangumi) {
            tabs.add(HomePageTab.Bangumi)
            if (entryView == SettingConstants.HOME_ENTRY_VIEW_BANGUMI) {
                initialPage = tabs.size - 1
            }
        }
        if (setting.showCinema) {
            tabs.add(HomePageTab.Cinema)
            if (entryView == SettingConstants.HOME_ENTRY_VIEW_CINEMA) {
                initialPage = tabs.size - 1
            }
        }
        return tabs
    }

    /** 远程init接口已禁用 */
    // private suspend fun getMiaoInitData(version: String): MiaoAdInfo { ... }

    /** 远程init接口已禁用 - 不加载广告/设置列表/版本检查 */
    // private fun loadAdData() = viewModelScope.launch(Dispatchers.IO) { ... }
    // fun saveSettingList(settingList: List<MiaoSettingInfo>) { ... }

    fun menuItemClick(view: View, item: MenuItemPropInfo) {
        when (item.key) {
            MenuKeys.dynamic -> {
                val nav = pageNavigation.hostController
                nav.navigate(DynamicPage, navOptions {
                    popUpTo(nav.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                })
            }
            MenuKeys.filter -> {
                filterTrigger.value = true
            }
        }
    }

    fun backPressed() {
        if (playerDelegate.onBackPressed()) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPressedTime > 2000) {
            toast("再按一次退出")
            lastBackPressedTime = now
        } else {
            fragment.requireActivity().finish()
        }
    }
}

@Composable
private fun HomePageContent(viewModel: HomePageViewModel) {
    val userStore: UserStore by rememberInstance()
    val isLogin = userStore.stateFlow.collectAsStateWithLifecycle().value.info != null

    val scope = rememberCoroutineScope()

    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val pagerState =
            rememberPagerState(
                    pageCount = { viewModel.tabs.size },
                    initialPage = viewModel.initialPage
            )
    val emitter = localEmitter()

    val pageConfigId =
            PageConfig(
                    title = "首页",
                    menu =
                            rememberMyMenu(isLogin, pagerState.currentPage) {
                                checkable = true
                                checkedKey = MenuKeys.home
                                myItem {
                                    key = MenuKeys.home
                                    title = "首页"
                                    iconFileName = "ic_baseline_home_24"
                                }
                                if (isLogin) {
                                    myItem {
                                        key = MenuKeys.dynamic
                                        title = "动态"
                                        iconFileName = "ic_baseline_icecream_24"
                                    }
                                }
                                myItem {
                                    key = MenuKeys.search
                                    title = "搜索"
                                    iconFileName = "ic_baseline_search_24"
                                    action = MenuActions.search
                                }

                                // 筛选：仅在番剧/影视/直播 Tab 显示
                                // （直播第四阶段：分类从"搜索框下面两条长标签"搬进筛选弹窗后，
                                //   底栏这个按钮就是和番剧/影视**完全一致**的那个入口）
                                val currentTab =
                                    viewModel.tabs.getOrNull(pagerState.currentPage)
                                if (currentTab is HomePageTab.Bangumi ||
                                    currentTab is HomePageTab.Cinema ||
                                    currentTab is HomePageTab.Live) {
                                    myItem {
                                        key = MenuKeys.filter
                                        title = "筛选"
                                        iconFileName = "ic_baseline_filter_list_grey_24"
                                    }
                                }
                            }
            )
    PageListener(
            configId = pageConfigId,
            onMenuItemClick = viewModel::menuItemClick,
    )
    BackHandler(
            onBack = viewModel::backPressed,
    )

    // 从 ViewModel 的 filterTrigger 转发到 Emitter
    LaunchedEffect(viewModel.filterTrigger.value) {
        if (viewModel.filterTrigger.value) {
            val tab = viewModel.tabs.getOrNull(pagerState.currentPage)
            if (tab != null) {
                when (tab) {
                    is HomePageTab.Bangumi,
                    is HomePageTab.Cinema,
                    // 直播：同一个 EmitterAction，由 HomeLiveContent 收下来开直播分类弹窗
                    is HomePageTab.Live -> {
                        emitter.emit(EmitterAction.OpenFilter(tabId = tab.id))
                    }
                    else -> {}
                }
            }
            viewModel.filterTrigger.value = false
        }
    }

    val combinedTabClick =
            combinedTabDoubleClick(
                    pagerState = pagerState,
                    onDoubleClick = {
                        scope.launch {
                            emitter.emit(EmitterAction.DoubleClickTab(tab = viewModel.tabs[it].id))
                        }
                    }
            )

    Column(modifier = Modifier.fillMaxSize()) {
        if (viewModel.tabs.size <= 1) {
            Spacer(modifier = Modifier.height(windowInsets.topDp.dp))
        }
        if (viewModel.tabs.size > 1) {
            TabRow(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(windowInsets.toPaddingValues(bottom = 0.dp)),
                    selectedTabIndex = pagerState.currentPage,
                    indicator = { positions ->
                        TabRowDefaults.PrimaryIndicator(
                                Modifier.pagerTabIndicatorOffset(pagerState, positions),
                        )
                    },
            ) {
                viewModel.tabs.forEachIndexed { index, tab ->
                    Tab(
                            text = {
                                Text(
                                        text = tab.name,
                                        color =
                                                if (index == pagerState.currentPage) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onBackground
                                                }
                                )
                            },
                            selected = pagerState.currentPage == index,
                            onClick = { combinedTabClick(index) },
                    )
                }
            }
        }
        val saveableStateHolder = rememberSaveableStateHolder()
        HorizontalPager(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = pagerState,
        ) { index ->
            saveableStateHolder.SaveableStateProvider(index) {
                viewModel.tabs[index].PageContent(viewModel.pageState)
            }
        }
    }
}
