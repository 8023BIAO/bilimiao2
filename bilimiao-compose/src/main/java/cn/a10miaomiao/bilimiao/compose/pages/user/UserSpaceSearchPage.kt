package cn.a10miaomiao.bilimiao.compose.pages.user

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
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
import cn.a10miaomiao.bilimiao.compose.pages.user.content.UserSearchArchiveContent
import cn.a10miaomiao.bilimiao.compose.pages.user.content.UserSearchDynamicContent
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.mypage.SearchConfigInfo
import com.a10miaomiao.bilimiao.comm.mypage.myMenu
import com.a10miaomiao.bilimiao.comm.mypage.myMenuItem
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
class UserSpaceSearchPage(
    val id: String,
    val keyword: String,
) : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: UserSpaceSearchPageViewModel = diViewModel()
        UserSpaceSearchPageContent(viewModel, id.toLong(), keyword)
    }
}

@Stable
private sealed class UserSpaceSearchPageTab(
    val id: String,
    val name: String,
) {

    @Composable
    abstract fun PageContent(mid: Long, keyword: String, rankOrder: String)

    data object Archive: UserSpaceSearchPageTab(
        id = PageTabIds.UserSearchArchive,
        name = "视频",
    ) {
        @Composable
        override fun PageContent(mid: Long, keyword: String, rankOrder: String) {
            UserSearchArchiveContent(mid, keyword, rankOrder)
        }
    }

    data object Dynamic : UserSpaceSearchPageTab(
        id = PageTabIds.UserSearchDynamic,
        name = "动态"
    ) {
        @Composable
        override fun PageContent(mid: Long, keyword: String, rankOrder: String) {
            UserSearchDynamicContent(mid, keyword)
        }
    }

}

private class UserSpaceSearchPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val pageNavigation by instance<PageNavigation>()

    val rankOrder = MutableStateFlow("pubdate")

    val tabs = listOf<UserSpaceSearchPageTab>(
        UserSpaceSearchPageTab.Archive,
        UserSpaceSearchPageTab.Dynamic,
    )

    fun changeRankOrder(value: String) {
        rankOrder.value = value
    }

}


@Composable
private fun UserSpaceSearchPageContent(
    viewModel: UserSpaceSearchPageViewModel,
    mid: Long,
    keyword: String
) {
    val rankOrder by viewModel.rankOrder.collectAsState()

    val pageConfigId = PageConfig(
        title = "搜索投稿\n-\n${keyword}",
        menu = rememberMyMenu(rankOrder) {
            myItem {
                key = MenuKeys.sort
                title = when(rankOrder) {
                    "pubdate" -> "最新发布"
                    "click" -> "最多播放"
                    "stime" -> "最旧发布"
                    else -> "排序"
                }
                iconFileName = "ic_baseline_filter_list_grey_24"
                childMenu = myMenu {
                    checkable = true
                    checkedKey = when(rankOrder) {
                        "pubdate" -> 1
                        "click" -> 2
                        "stime" -> 3
                        else -> 1
                    }
                    myItem {
                        key = 1
                        action = "pubdate"
                        title = "最新发布"
                    }
                    myItem {
                        key = 2
                        action = "click"
                        title = "最多播放"
                    }
                    myItem {
                        key = 3
                        action = "stime"
                        title = "最旧发布"
                    }
                }
            }
            myItem {
                key = MenuKeys.search
                title = "继续搜索"
                iconFileName = "ic_search_gray"
            }
        },
        search = SearchConfigInfo(
            name = "搜索投稿",
            keyword = keyword,
        )
    )
    PageListener(
        pageConfigId,
        onMenuItemClick = { _, item ->
            when (item.key) {
                in 1..3 -> viewModel.changeRankOrder(item.action ?: "")
            }
        },
        onSearchSelfPage = null
    )
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { viewModel.tabs.size })
    val emitter = localEmitter()
    val combinedTabClick = combinedTabDoubleClick(
        pagerState = pagerState,
        onDoubleClick = {
            scope.launch {
                emitter.emit(
                    EmitterAction.DoubleClickTab(
                        tab = viewModel.tabs[it].id
                    ))
            }
        }
    )

    Column(
        modifier = Modifier.fillMaxSize()
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
            viewModel.tabs.forEachIndexed { index, tab ->
                Tab(
                    text = {
                        Text(
                            text = tab.name,
                            color = if (index == pagerState.currentPage) {
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
        val saveableStateHolder = rememberSaveableStateHolder()
        HorizontalPager(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            state = pagerState,
        ) { index ->
            saveableStateHolder.SaveableStateProvider(index) {
                viewModel.tabs[index].PageContent(mid, keyword, rankOrder)
            }
        }
    }

}