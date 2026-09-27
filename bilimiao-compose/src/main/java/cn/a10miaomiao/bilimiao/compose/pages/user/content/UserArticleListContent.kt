package cn.a10miaomiao.bilimiao.compose.pages.user.content

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.a10miaomiao.bilimiao.compose.common.constant.PageTabIds
import cn.a10miaomiao.bilimiao.compose.common.emitter.EmitterAction
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localEmitter
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.pages.search.components.ArticleItemBox
import cn.a10miaomiao.bilimiao.compose.pages.user.UserArticleViewModel
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import org.kodein.di.compose.rememberInstance

/**
 * 用户空间「专栏」tab：栅格卡片，点击进原生专栏阅读页。
 *
 * 数据和分页在 [UserArticleViewModel]；这里只负责列表、刷新与双点 Tab 回顶。
 */
@Composable
fun UserArticleListContent(
    viewModel: UserArticleViewModel,
    authorName: String,
) {
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    LaunchedEffect(Unit) {
        viewModel.initData()
    }

    val listFlow = viewModel.list
    val list by listFlow.data.collectAsStateWithLifecycle()
    val listLoading by listFlow.loading.collectAsStateWithLifecycle()
    val listFinished by listFlow.finished.collectAsStateWithLifecycle()
    val listFail by listFlow.fail.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    val emitter = localEmitter()
    val listState = rememberLazyGridState()
    LaunchedEffect(Unit) {
        emitter.collectAction<EmitterAction.DoubleClickTab> {
            if (it.tab == PageTabIds.UserArticle) {
                if (listState.firstVisibleItemIndex == 0) {
                    viewModel.refreshList()
                } else {
                    listState.animateScrollToItem(0)
                }
            }
        }
    }

    // 复用仓库既有的 SwipeToRefresh（与投稿 tab 一致）。
    cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh(
        refreshing = isRefreshing,
        onRefresh = viewModel::refreshList,
    ) {
        LazyVerticalGrid(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            columns = GridCells.Adaptive(300.dp),
            contentPadding = windowInsets.toPaddingValues(top = 0.dp),
        ) {
            items(list, key = { item -> item.id ?: item.cvid ?: item.uri.hashCode() }) { item ->
                ArticleItemBox(
                    modifier = Modifier.padding(10.dp),
                    title = item.title.orEmpty(),
                    cover = item.origin_image_urls?.firstOrNull().orEmpty(),
                    author = authorName,
                    viewNum = item.stats?.view?.takeIf { it > 0L }
                        ?.let { NumberUtil.converString(it) } ?: "",
                    likeNum = item.stats?.like?.takeIf { it > 0L }
                        ?.let { NumberUtil.converString(it) } ?: "",
                    replyNum = item.stats?.reply?.takeIf { it > 0L }
                        ?.let { NumberUtil.converString(it) } ?: "",
                    desc = "",
                    onClick = { viewModel.toArticle(item) },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                ListStateBox(
                    loading = listLoading,
                    finished = listFinished,
                    fail = listFail,
                    listData = list,
                ) {
                    viewModel.loadMore()
                }
            }
        }
    }
}
