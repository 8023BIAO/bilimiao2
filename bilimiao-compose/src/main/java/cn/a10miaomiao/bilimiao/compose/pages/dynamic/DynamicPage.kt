package cn.a10miaomiao.bilimiao.compose.pages.dynamic

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.constant.PageTabIds
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.foundation.combinedTabDoubleClick
import cn.a10miaomiao.bilimiao.compose.common.foundation.pagerTabIndicatorOffset
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.mypage.rememberMyMenu
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.pages.dynamic.components.DynamicMiniUpperList
import cn.a10miaomiao.bilimiao.compose.pages.dynamic.components.DynamicUpperList
import cn.a10miaomiao.bilimiao.compose.pages.dynamic.content.DynamicUpperContent
import cn.a10miaomiao.bilimiao.compose.pages.dynamic.content.DynamicVideoListContent
import com.a10miaomiao.bilimiao.comm.mypage.MenuActions
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.kodein.di.compose.rememberInstance

@Serializable
object DynamicPage : ComposePage() {

    @Composable
    override fun Content() {
        val userStore: UserStore by rememberInstance()
        val userState by userStore.stateFlow.collectAsState()
        val isLogin = userState.info != null
        val viewModel: DynamicViewModel = diViewModel()
        if (isLogin) {
            DynamicPageContent(viewModel)
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("请先登录", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private val TAB_VIDEO = "视频"
private val TAB_UP = "UP"

@Composable
private fun DynamicPageContent(
    viewModel: DynamicViewModel
) {
    val pageConfigId = PageConfig(
        title = "动态",
        menu = rememberMyMenu {
            checkable = true
            checkedKey = MenuKeys.dynamic
            myItem { key = MenuKeys.home; title = "首页"; iconFileName = "ic_baseline_home_24" }
            myItem { key = MenuKeys.dynamic; title = "动态"; iconFileName = "ic_baseline_icecream_24" }
            myItem {
                key = MenuKeys.search
                title = "搜索"
                iconFileName = "ic_baseline_search_24"
                action = MenuActions.search
            }
        }
    )
    PageListener(configId = pageConfigId, onMenuItemClick = viewModel::menuItemClick)

    val scope = rememberCoroutineScope()
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val upperList by viewModel.upList.collectAsState()
    val selectedUpper by viewModel.selectedUpper.collectAsState()
    val saveableStateHolder = rememberSaveableStateHolder()

    val tabs = listOf(TAB_VIDEO, TAB_UP)
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    val combinedTabClick = combinedTabDoubleClick(
        pagerState = pagerState,
        onDoubleClick = {}
    )

    val userStore: UserStore by rememberInstance()
    val userInfo = userStore.state.info

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部 TabRow
        TabRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(windowInsets.toPaddingValues(bottom = 0.dp)),
            selectedTabIndex = pagerState.currentPage,
            indicator = { positions ->
                TabRowDefaults.PrimaryIndicator(
                    Modifier.pagerTabIndicatorOffset(pagerState, positions),
                )
            },
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    text = {
                        Text(
                            text = title,
                            color = if (index == pagerState.currentPage)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground
                        )
                    },
                    selected = pagerState.currentPage == index,
                    onClick = { combinedTabClick(index) },
                )
            }
        }

        // 内容区：UP侧栏（UP Tab显示，视频Tab隐藏）
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val isWide = maxWidth >= 600.dp
            val showUpSidebar = pagerState.currentPage == 1  // UP tab
            Row(modifier = Modifier.fillMaxSize()) {
                // 左侧 UP 面板 - AnimatedVisibility 避免 Row 子元素数量突变
                AnimatedVisibility(
                    visible = showUpSidebar,
                    enter = expandHorizontally() + fadeIn(),
                    exit = shrinkHorizontally() + fadeOut(),
                ) {
                    if (isWide) {
                        DynamicUpperList(
                            modifier = Modifier
                                .width(160.dp)
                                .fillMaxHeight(),
                            safePadding = PaddingValues(
                                top = windowInsets.topDp.dp,
                                bottom = windowInsets.bottomDp.dp,
                            ),
                            upperList = upperList,
                            selectedUpper = selectedUpper,
                            userInfo = userInfo,
                            onMyDynamics = {
                                viewModel.selectMyDynamics(userInfo)
                                scope.launch { pagerState.animateScrollToPage(1) }
                            },
                            onSelected = viewModel::selectUpper,
                            showAllHeader = true,
                        )
                    } else {
                        DynamicMiniUpperList(
                            modifier = Modifier
                                .width(70.dp)
                                .fillMaxHeight(),
                            upperList = upperList,
                            selectedUpper = selectedUpper,
                            userInfo = userInfo,
                            onMyDynamics = {
                                viewModel.selectMyDynamics(userInfo)
                                scope.launch { pagerState.animateScrollToPage(1) }
                            },
                            onSelected = viewModel::selectUpper,
                        )
                    }
                }

                // 右侧内容
                HorizontalPager(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    state = pagerState,
                    key = { index ->
                        if (tabs[index] == TAB_UP) {
                            "up_" + (selectedUpper?.uid ?: "none")
                        } else {
                            tabs[index]
                        }
                    },
                ) { index ->
                    when (tabs[index]) {
                        TAB_VIDEO -> {
                            saveableStateHolder.SaveableStateProvider(PageTabIds.DynamicVideo) {
                                DynamicVideoListContent()
                            }
                        }
                        TAB_UP -> {
                            selectedUpper?.let { up ->
                                saveableStateHolder.SaveableStateProvider(
                                    PageTabIds.DynamicByUpper[up.uid.toString()]
                                ) {
                                    DynamicUpperContent(
                                        dynamicViewModel = viewModel,
                                        upper = up,
                                    )
                                }
                            } ?: Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "请选择UP主",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                        }
                    }
                }
            }
        }
    }
}
