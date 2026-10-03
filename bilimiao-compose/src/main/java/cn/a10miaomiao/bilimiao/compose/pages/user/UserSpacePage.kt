package cn.a10miaomiao.bilimiao.compose.pages.user


import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.with
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.emitter.EmitterAction
import cn.a10miaomiao.bilimiao.compose.common.foundation.combinedTabDoubleClick
import cn.a10miaomiao.bilimiao.compose.common.foundation.pagerTabIndicatorOffset
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localEmitter
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.mypage.rememberMyMenu
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.layout.chain_scrollable.ChainScrollableLayout
import cn.a10miaomiao.bilimiao.compose.components.layout.chain_scrollable.rememberChainScrollableLayoutState
import cn.a10miaomiao.bilimiao.compose.components.status.BiliFailBox

import cn.a10miaomiao.bilimiao.compose.pages.user.components.UserSpaceHeader
import com.a10miaomiao.bilimiao.comm.entity.user.SpaceInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuActions
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.mypage.SearchConfigInfo
import com.a10miaomiao.bilimiao.comm.mypage.myMenu
import androidx.compose.ui.graphics.toArgb
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.store.WindowStore.Insets
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.kodein.di.compose.rememberInstance
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import cn.a10miaomiao.bilimiao.compose.components.status.BiliLoadingBox

@Serializable
data class UserSpacePage(
    val id: String,
) : ComposePage() {

    @Composable
    override fun Content() {
        val archiveViewModel = diViewModel(key = "archive$id") {
            UserArchiveViewModel(it, id)
        }
        val articleViewModel = diViewModel(key = "article$id") {
            UserArticleViewModel(it, id)
        }
        val viewModel = diViewModel() {
            UserSpaceViewModel(it, id, archiveViewModel, articleViewModel)
        }
        // 在别人的空间里点抽屉里的自己头像：nav entry（含 ViewModelStore）被 launchSingleTop 复用，
        // VM 还停在旧用户 → 页面切不过去。这里只提示一句，不重建页面（见
        // UserSpaceViewModel.hintIfStuckOnOtherSpace 的说明）。
        LaunchedEffect(viewModel, id) {
            viewModel.hintIfStuckOnOtherSpace(id)
        }
//        AnimatedContent()
        UserSpacePageContent(viewModel, archiveViewModel)
    }
}

/** 空间页的三种界面态：加载中 / 已注销账号 / 正常账号。 */
private enum class UserSpacePageState { Loading, Deleted, Detail }

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun UserSpacePageContent(
    viewModel: UserSpaceViewModel,
    archiveViewModel: UserArchiveViewModel,
) {
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val detailData = viewModel.detailData.collectAsStateWithLifecycle().value
    val deletedUpper = viewModel.deletedUpper.collectAsStateWithLifecycle().value
    val fail = viewModel.fail.collectAsStateWithLifecycle().value

    val pageState = when {
        deletedUpper -> UserSpacePageState.Deleted
        detailData == null -> UserSpacePageState.Loading
        else -> UserSpacePageState.Detail
    }
//    val slideDistance = LocalDensity.current.run {
//        100.dp.toPx()
//    }
    AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        targetState = pageState,
        label = "UserSpacePageContent",
        transitionSpec = {
            // Follow M3 Clean fades
            val fadeIn = fadeIn(
                tween(),
            )
            val fadeOut = fadeOut()
            fadeIn.togetherWith(fadeOut)
        }
    ) { state ->
        when {
            state == UserSpacePageState.Deleted -> UserSpaceDeletedContent(
                viewModel = viewModel,
                windowInsets = windowInsets,
            )
            state == UserSpacePageState.Detail && detailData != null -> UserSpacePageDetailContent(
                viewModel = viewModel,
                archiveViewModel = archiveViewModel,
                windowInsets = windowInsets,
                detailData = detailData,
            )
            else -> UserSpacePageLoadingContent(
                fail = fail,
                innerPadding = windowInsets.toPaddingValues()
            )
        }
    }
}

@Composable
private fun UserSpacePageLoadingContent(
    fail: Any?,
    innerPadding: PaddingValues,
) {
    PageConfig(
        title = "个人中心"
    )
    if (fail != null) {
        BiliFailBox(
            e = fail,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    } else {
        // ★原来这里什么都不画：失败为 null 的这段时间整页空白（用户报"点进某人的空间是白的"）。
        //   vc210 那版补的是**手写** CircularProgressIndicator —— UI 红线 11 不许手写，
        //   且复核也点过名；这里换回现成的 BiliLoadingBox。
        BiliLoadingBox(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

/**
 * 已注销账号的空间页。
 *
 * 空间接口（`x/v2/space`）对已注销账号一律失败，昵称/头像/粉丝数这些 card 数据**拿不回来**
 * （WBI 的 `x/space/wbi/acc/info` 也回 -404），所以这里只画一个极简头部：
 * 默认头像 + 「账号已注销」+ UID —— 不摆"0 粉 0 获赞"那种假计数
 * （见 `evidence/deactivated-account-space-audit.md` 的"明确不建议"一节）。
 *
 * tab 用 [UserSpaceViewModel.deletedTabs]：去掉主页（数据源已失效，会是纯空白），
 * 保留仍能按 vmid 直接拉的投稿/动态/专栏 —— 2026-10-02 实测：投稿 tab 用的
 * `x/v2/space/archive/cursor` 回 code 0 且**返回 3 条投稿**，专栏 `x/v2/space/article` 回 code 0；
 * 动态那条走 gRPC `dynSpace`、**未实测**（失败只是该 tab 的空态，不影响投稿）。
 */
@Composable
private fun UserSpaceDeletedContent(
    viewModel: UserSpaceViewModel,
    windowInsets: Insets,
) {
    val pageConfigId = PageConfig(
        title = "账号已注销",
        // 只留不需要 card 数据的入口：复制链接 + 搜索投稿（关注/私信/屏蔽都依赖 detailData，这里不给）。
        menu = rememberMyMenu {
            myItem {
                key = MenuKeys.more
                iconFileName = "ic_more_vert_grey_24dp"
                title = "更多"
                childMenu = myMenu {
                    myItem {
                        key = 4
                        title = "复制链接"
                    }
                }
            }
            myItem {
                key = MenuKeys.search
                title = "搜索"
                iconFileName = "ic_search_gray"
                action = MenuActions.search
            }
        },
        search = SearchConfigInfo(
            name = "搜索投稿列表",
            keyword = "",
        )
    )
    PageListener(
        pageConfigId,
        onMenuItemClick = viewModel::menuItemClick,
        onSearchSelfPage = viewModel::searchSelfPage
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            // ★只吃上/左/右三边：底部安全区由各 tab 自己的列表 contentPadding 出一次
            //   （投稿/专栏/动态三个 ListContent 都带 `toPaddingValues(...)`）——
            //   这里再吃一次的话，列表滚到底会在底栏之上多出一整份 bottomDp 的死白（复核抓到的必改 1）。
            .padding(windowInsets.toPaddingValues(bottom = 0.dp)),
    ) {
        UserSpaceDeletedHeader(vmid = viewModel.vmid)
        UserSpaceTabsSection(
            tabs = viewModel.deletedTabs,
            pagerState = rememberPagerState { viewModel.deletedTabs.size },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 已注销账号的极简头部：默认头像 + 「账号已注销」+ UID。
 * 颜色/间距全走主题（rule 02：只用 `MaterialTheme.*` 与 4/8/12/16 间距）。
 */
@Composable
private fun UserSpaceDeletedHeader(
    vmid: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(64.dp),
        )
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = "账号已注销",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "UID:$vmid",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 空间页那排 tab（TabRow + HorizontalPager）。
 *
 * 正常账号与已注销账号**共用这一份** —— 两边只有 tab 列表 / pager 状态 / 修饰符不同
 * （rule 02 第 15 条：同一交互已有组件就不许写第二份）。
 */
@Composable
private fun UserSpaceTabsSection(
    tabs: List<UserSpacePageTabs>,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    tabRowModifier: Modifier = Modifier,
    pagerModifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val emitter = localEmitter()
    val combinedTabClick = combinedTabDoubleClick(
        pagerState = pagerState,
        onDoubleClick = { index ->
            scope.launch {
                emitter.emit(
                    EmitterAction.DoubleClickTab(
                        tab = tabs[index].id
                    ))
            }
        }
    )
    Column(modifier = modifier) {
        TabRow(
            modifier = tabRowModifier,
            selectedTabIndex = pagerState.currentPage,
            indicator = { positions ->
                TabRowDefaults.PrimaryIndicator(
                    Modifier.pagerTabIndicatorOffset(pagerState, positions),
                )
            },
        ) {
            tabs.forEachIndexed { index, tab ->
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
            modifier = pagerModifier.weight(1f),
            state = pagerState,
        ) { index ->
            saveableStateHolder.SaveableStateProvider(index) {
                tabs[index].PageContent()
            }
        }
    }
}

@Composable
private fun UserSpacePageDetailContent(
    viewModel: UserSpaceViewModel,
    archiveViewModel: UserArchiveViewModel,
    windowInsets: Insets,
    detailData: SpaceInfo,
) {
    val isFollow = viewModel.isFollow.collectAsStateWithLifecycle().value
    val rankOrder = archiveViewModel.rankOrder.collectAsStateWithLifecycle().value
    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    val grayIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f).toArgb()
    val pageConfigId = PageConfig(
        title = detailData.card?.name ?: "个人中心",
        menu = rememberMyMenu(isFollow, viewModel.isFiltered, viewModel.currentPage, rankOrder) {
            myItem {
                key = MenuKeys.more
                iconFileName = "ic_more_vert_grey_24dp"
                title = "更多"
                childMenu = myMenu {
                    if (!viewModel.isSelf) {
                        if (viewModel.isFiltered) {
                            myItem {
                                key = 1
                                title = "取消屏蔽该UP主"
                            }
                        } else {
                            myItem {
                                key = 2
                                title = "屏蔽该UP主"
                            }
                        }
                        // R10：私信在别人的空间是低频操作，收进「更多」里，让顶栏那排按钮少一颗。
                        // 只挪位置、不改行为：key/文案/图标与原来那颗按钮逐字一致，点击仍然走
                        // UserSpaceViewModel.menuItemClick 的 MenuKeys.message 分支（PopupMenu 递归查 key）。
                        // 图标名虽然不会被 PopupMenu 渲染（它只显示标题），仍原样保留 —— 这样"挪动"就是挪动。
                        myItem {
                            key = MenuKeys.message
                            iconFileName = "ic_baseline_send_24"
                            title = "私信"
                        }
                    }
//                    myItem {
//                        key = 3
//                        title = "用浏览器打开"
//                    }
                    // 编辑资料只对**自己的**空间有意义（对齐 PiliPlus：它是在自己空间把「关注」
                    // 换成「编辑资料」，见报告 §1.1）。这里不动头部主按钮，只在「更多」里加一项。
                    if (viewModel.isSelf) {
                        myItem {
                            key = MenuKeys.edit
                            title = "编辑资料"
                        }
                    }
                    myItem {
                        key = 4
                        title = "复制链接"
                    }
//                    myItem {
//                        key = 5
//                        title = "分享"
//                    }
                }
            }
            if (viewModel.currentPage == 2) {
                myItem {
                    key = MenuKeys.filter
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
                            "pubdate" -> 11
                            "click" -> 12
                            "stime" -> 13
                            else -> 11
                        }
                        myItem {
                            key = 11
                            action = "pubdate"
                            title = "最新发布"
                        }
                        myItem {
                            key = 12
                            action = "click"
                            title = "最多播放"
                        }
                        myItem {
                            key = 13
                            action = "stime"
                            title = "最旧发布"
                        }
                    }
                }
            }
            myItem {
                key = MenuKeys.search
                title = "搜索"
                iconFileName = "ic_search_gray"
                action = MenuActions.search
            }
            if (!viewModel.isSelf) {
                myItem {
                    key = MenuKeys.follow
                    if (isFollow) {
                        iconFileName = "ic_baseline_favorite_24"
                        title = "已关注"
                        tintColor = primaryColor
                    } else {
                        iconFileName = "ic_outline_favorite_border_24"
                        title = "关注"
                    }
                }
            }
        },
        search = SearchConfigInfo(
            name = "搜索投稿列表",
            keyword = "",
        )
    )
    PageListener(
        pageConfigId,
        onMenuItemClick = viewModel::menuItemClick,
        onSearchSelfPage = viewModel::searchSelfPage
    )

    val maxHeaderSize = remember { mutableStateOf(0 to 0) }
    val density = LocalDensity.current
    // maxScrollPosition = header height + status bar spacing (like VideoDetailPage's Spacer pattern)
    val chainScrollableLayoutState = rememberChainScrollableLayoutState(
        density.run { maxHeaderSize.value.second.toDp() + windowInsets.topDp.dp },
        windowInsets.topDp.dp,
    )
    val isLargeScreen = remember(maxHeaderSize.value.first) {
        density.run { maxHeaderSize.value.first.toDp() } > 600.dp
    }
    val scrollableState = rememberScrollState()

    ChainScrollableLayout(
        modifier = Modifier.fillMaxSize(),
        state = chainScrollableLayoutState,
    ) { state ->
        val headerHeightPx = density.run { maxHeaderSize.value.second.toDp().toPx() }
        val alpha = if (headerHeightPx > 0f) {
            ((headerHeightPx + state.getOffsetYValue()) / headerHeightPx).coerceIn(0f, 1f)
        } else {
            1f
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset {
                    IntOffset(
                        0,
                        state
                            .getOffsetYValue()
                            .roundToInt()
                    )
                }
                .background(MaterialTheme.colorScheme.background)
                .nestedScroll(state.nestedScroll)
                .scrollable(scrollableState, Orientation.Vertical),
        ) {
            Column {
                Spacer(Modifier.height(windowInsets.topDp.dp))
                UserSpaceHeader(
                modifier = Modifier
                    .height(IntrinsicSize.Min)
                    .fillMaxWidth()
                    .alpha(alpha)
                    .onGloballyPositioned { coordinates ->
                        val headerHeight = coordinates.size.height
                        val headerWidth = coordinates.size.width
                        if (maxHeaderSize.value.first != headerWidth ||
                            maxHeaderSize.value.second != headerHeight
                        ) {
                            maxHeaderSize.value = headerWidth to headerHeight
                        }
                    },
                isLargeScreen = isLargeScreen,
                viewModel = viewModel,
                archiveViewModel = archiveViewModel,
            )
            }
        }
        UserSpaceTabsSection(
            tabs = viewModel.tabs,
            pagerState = viewModel.pagerState,
            modifier = Modifier.offset {
                IntOffset(
                    0,
                    (state.maxPx + state.getOffsetYValue()).roundToInt()
                )
            },
            tabRowModifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(
                    start = windowInsets.leftDp.dp,
                    end = windowInsets.rightDp.dp,
                )
                .nestedScroll(state.nestedScroll)
                .scrollable(scrollableState, Orientation.Vertical),
            pagerModifier = Modifier
                .fillMaxSize()
                .padding(bottom = state.minScrollPosition),
        )
    }
}