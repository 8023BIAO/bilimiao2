package cn.a10miaomiao.bilimiao.compose.components.list

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.common.emitter.EmitterAction
import cn.a10miaomiao.bilimiao.compose.common.localEmitter

/**
 * 分页列表通用模板
 *
 * 封装了以下通用逻辑：
 * - 双击Tab刷新/回顶
 * - 滑到底部自动加载更多
 * - SwipeToRefresh 包裹
 * - ListStateBox 尾部状态
 *
 * @param T 列表项类型
 * @param tabId 当前Tab ID，用于双击刷新
 * @param list 分页数据
 * @param isRefreshing 是否正在刷新
 * @param listStyle 0=大图 1=小图
 * @param contentPadding 内容内边距
 * @param listState LazyGridState（可自定义传入）
 * @param itemKey 列表项key提取函数
 * @param itemContent 列表项内容
 */
@Composable
fun <T> PaginatedListTemplate(
    tabId: String,
    list: List<T>,
    isRefreshing: Boolean,
    listLoading: Boolean,
    listFinished: Boolean,
    listFail: String,
    listStyle: Int = 0,
    contentPadding: PaddingValues = PaddingValues(top = 0.dp),
    listState: LazyGridState = rememberLazyGridState(),
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    itemKey: (T) -> String,
    itemContent: @Composable (T) -> Unit,
) {
    // 双击Tab刷新/回顶
    val emitter = localEmitter()
    LaunchedEffect(tabId) {
        emitter.collectAction<EmitterAction.DoubleClickTab> {
            if (it.tab == tabId) {
                if (listState.firstVisibleItemIndex == 0) {
                    onRefresh()
                } else {
                    listState.animateScrollToItem(0)
                }
            }
        }
    }

    // 滑到底部自动加载更多
    val reachBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible != null && lastVisible.index >= layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(reachBottom) {
        if (reachBottom && !listLoading && !listFinished && list.isNotEmpty()) {
            onLoadMore()
        }
    }

    SwipeToRefresh(
        refreshing = isRefreshing,
        onRefresh = onRefresh,
    ) {
        LazyVerticalGrid(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            columns = if (listStyle == 0) GridCells.Adaptive(300.dp)
                else GridCells.Adaptive(180.dp),
            contentPadding = contentPadding,
        ) {
            items(list, itemKey) {
                itemContent(it)
            }
            item(
                span = { GridItemSpan(maxLineSpan) }
            ) {
                ListStateBox(
                    loading = listLoading,
                    finished = listFinished,
                    fail = listFail,
                    listData = list,
                ) {
                    onLoadMore()
                }
            }
        }
    }
}
