package cn.a10miaomiao.bilimiao.compose.pages.article

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import androidx.activity.compose.BackHandler
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.foundation.LocalSeekEnabled
import cn.a10miaomiao.bilimiao.compose.common.foundation.pagerTabIndicatorOffset
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.pages.community.MainReplyListPageContent
import cn.a10miaomiao.bilimiao.compose.pages.community.MainReplyViewModel
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.kodein.di.compose.rememberInstance

@Serializable
class ArticleReaderPage(
    val id: Long,
) : ComposePage() {

    @Composable
    override fun Content() {
        val articleVM: ArticleReaderViewModel = diViewModel(
            key = "article_reader_$id"
        ) { ArticleReaderViewModel(it, id) }
        val replyVM: MainReplyViewModel = diViewModel(
            key = "article_reply_$id"
        ) { MainReplyViewModel(it, id.toString(), type = 12) }

        val windowStore: WindowStore by rememberInstance()
        val windowState by windowStore.stateFlow.collectAsState()
        val contentInsets = windowState.getContentInsets(localContainerView())
        val innerPadding = contentInsets.toPaddingValues()

        val article by articleVM.article.collectAsState()
        val replyCount = article?.replyCount ?: 0
        val tabs = remember(replyCount) {
            listOf("article" to "专栏", "reply" to "评论($replyCount)")
        }
        val pagerState = rememberPagerState(pageCount = { tabs.size })
        val coroutineScope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateLeftPadding(LayoutDirection.Ltr),
                    end = innerPadding.calculateRightPadding(LayoutDirection.Ltr),
                ),
                containerColor = MaterialTheme.colorScheme.surface,
                indicator = { tabPositions ->
                    TabRowDefaults.PrimaryIndicator(
                        Modifier.pagerTabIndicatorOffset(pagerState, tabPositions),
                    )
                },
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = pagerState.currentPage == index
                    Tab(
                        selected = selected,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = {
                            Text(
                                text = tab.second,
                                color = if (selected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            // 返回导航：在"评论"tab时返回切换到"专栏"tab
            BackHandler(
                enabled = pagerState.currentPage > 0
            ) {
                coroutineScope.launch { pagerState.animateScrollToPage(0) }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> ArticleReaderContent(
                        viewModel = articleVM,
                        bottomPadding = contentInsets.bottomDp.dp + windowStore.bottomAppBarHeightDp.dp,
                    )
                    1 -> CompositionLocalProvider(LocalSeekEnabled provides false) {
                        MainReplyListPageContent(
                            headerContent = {},
                            viewModel = replyVM,
                            pageTitle = "评论",
                        )
                    }
                }
            }
        }
    }
}
