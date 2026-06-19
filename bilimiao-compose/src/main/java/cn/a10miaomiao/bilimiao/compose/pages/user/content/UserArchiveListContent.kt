package cn.a10miaomiao.bilimiao.compose.pages.user.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.a10miaomiao.bilimiao.compose.common.constant.PageTabIds
import cn.a10miaomiao.bilimiao.compose.common.emitter.EmitterAction
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localEmitter
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.video.VideoItemBox
import cn.a10miaomiao.bilimiao.compose.pages.user.UserArchiveViewModel
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import org.kodein.di.compose.rememberInstance

@Composable
fun UserArchiveListContent(
    viewModel: UserArchiveViewModel,
) {
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    LaunchedEffect(true) {
        viewModel.initData()
    }

    val listFlow = viewModel.list
    val list by listFlow.data.collectAsState()
    val listLoading by listFlow.loading.collectAsState()
    val listFinished by listFlow.finished.collectAsState()
    val listFail by listFlow.fail.collectAsState()
    val rankOrder by viewModel.rankOrder.collectAsState()

    val emitter = localEmitter()
    val listState = rememberLazyGridState()
    LaunchedEffect(Unit) {
        emitter.collectAction<EmitterAction.DoubleClickTab> {
            if (it.tab == PageTabIds.UserArchive) {
                if (listState.firstVisibleItemIndex == 0) {
                    viewModel.refreshList()
                } else {
                    listState.animateScrollToItem(0)
                }
            }
        }
    }

    LazyVerticalGrid(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        columns = GridCells.Adaptive(300.dp),
        contentPadding = windowInsets.toPaddingValues(
            top = 0.dp,
        )
    ) {
        // stime 模式：顶部放"加载更多"按钮
        if (rankOrder == "stime") {
            item(
                span = { GridItemSpan(maxLineSpan) }
            ) {
                if (!listFinished) {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (listFail?.isNotBlank() == true) {
                                TextButton(onClick = { viewModel.loadMore() }) {
                                    Text(
                                        listFail,
                                        modifier = Modifier.padding(start = 5.dp),
                                        color = MaterialTheme.colorScheme.outline,
                                        fontSize = 14.sp,
                                    )
                                }
                            } else if (listLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 3.dp,
                                )
                                Text(
                                    "加载中",
                                    modifier = Modifier.padding(start = 5.dp),
                                    color = MaterialTheme.colorScheme.outline,
                                    fontSize = 14.sp,
                                )
                            } else if (list.isEmpty()) {
                                Text(
                                    "空空如也",
                                    modifier = Modifier.padding(start = 5.dp),
                                    color = MaterialTheme.colorScheme.outline,
                                    fontSize = 14.sp,
                                )
                            } else {
                                TextButton(onClick = { viewModel.loadMore() }) {
                                    Text(
                                        "加载更多",
                                        modifier = Modifier.padding(start = 5.dp),
                                        color = MaterialTheme.colorScheme.outline,
                                        fontSize = 14.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        items(list, key = { it.param }) {
            VideoItemBox(
                modifier = Modifier.padding(10.dp),
                title = it.title,
                pic = it.cover,
                playNum = it.play,
                damukuNum = it.danmaku,
                remark = NumberUtil.converCTime(it.ctime),
                duration = NumberUtil.converDuration(it.duration),
                onClick = {
                    viewModel.toVideoDetail(it)
                }
            )
        }

        // 非 stime 模式：底部放"加载更多"指示器
        if (rankOrder != "stime") {
            item(
                span = { GridItemSpan(maxLineSpan) }
            ) {
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
