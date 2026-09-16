package cn.a10miaomiao.bilimiao.compose.pages.rank.content

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import cn.a10miaomiao.bilimiao.compose.common.navigation.BilibiliNavigation
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.SeasonCheckPage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bilibili.app.show.v1.RankAllResultReq
import bilibili.app.show.v1.RankGRPC
import bilibili.app.show.v1.RankRegionResultReq
import cn.a10miaomiao.bilimiao.compose.common.defaultNavOptions
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.components.video.VideoItemBox
import cn.a10miaomiao.bilimiao.compose.pages.mine.MyFollowViewModel
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

private class RankListContentViewModel(
    override val di: DI,
    val regionId: Int,
) : ViewModel(), DIAware {

    private val pageNavigation by instance<PageNavigation>()
    private val filterStore by instance<com.a10miaomiao.bilimiao.comm.store.FilterStore>()

    val count = MutableStateFlow(1)
    val isRefreshing = MutableStateFlow(false)
    val list = FlowPaginationInfo<bilibili.app.show.v1.Item>()

    init {
        loadData()
    }


    // 显式写返回类型 Job：body 里会递归调用 loadData（整页被屏蔽时续拉下一页），
    // 不写的话 Kotlin 推断返回类型时会撞上 "recursive problem"
    fun loadData(
        pageNum: Int = list.pageNum
    ): Job = viewModelScope.launch(Dispatchers.IO) {
        try {
            // 之前从不置 loading，界面上永远看不到"加载中"
            list.loading.value = true
            list.fail.value = ""
            val result = BiliGRPCHttp.request {
                if (regionId == 0) {
                    val req = RankAllResultReq(
                        order = "all",
                        pn = pageNum,
                        ps = list.pageSize
                    )
                    RankGRPC.rankAll(req)
                } else {
                    val req = RankRegionResultReq(
                        rid = regionId,
                        pn = pageNum,
                        ps = list.pageSize
                    )
                    RankGRPC.rankRegion(req)
                }
            }.awaitCall()
            // 排行榜以前不吃屏蔽规则，首页/时光姬却吃 → 同一个屏蔽设置在不同列表表现不一致
            val items = result.items.filter {
                filterStore.filterWord(it.title) && filterStore.filterUpper(it.mid.toString())
            }
            if (pageNum == 1) {
                list.data.value = items
            } else {
                list.data.value = list.data.value
                    .toMutableList()
                    .also { it.addAll(items) }
            }
            list.pageNum = pageNum
            // 之前从不写 finished，底部永远停在"加载更多"
            list.finished.value = result.items.size < list.pageSize
            // 整页都被屏蔽规则滤掉时列表会是空的，而 ListStateBox 不会给空列表自动翻页
            // （底部只显示"空空如也"、没有"加载更多"按钮）→ 主动接着取下一页，
            // 否则屏蔽词多的用户会看到排行榜永久空白
            if (items.isEmpty() && result.items.isNotEmpty() && !list.finished.value) {
                loadData(pageNum + 1)
            }
//            if (list.data.size < 10 && totalCount != result.size) {
//                _loadData(pageNum + 1)
//            }
        } catch (e: Exception) {
            e.printStackTrace()
            list.fail.value = "网络请求失败"
        } finally {
            list.loading.value = false
            isRefreshing.value = false
        }
    }

    fun loadMore() {
        if (!list.finished.value && !list.loading.value) {
            // 首屏失败时点重试走的也是这里：列表为空必须重拉第 1 页，
            // 否则会请求 pn=2 并塞进空列表 → 排行榜把第 21-40 名显示成第 1-20 名
            loadData(if (list.data.value.isEmpty()) 1 else list.pageNum + 1)
        }
    }

    fun refresh(
        refreshing: Boolean = true
    ) {
        isRefreshing.value = refreshing
        list.finished.value = false
        list.fail.value = ""
        loadData(1)
    }

    fun toVideoDetail(item: bilibili.app.show.v1.Item) {
        // 榜单里番剧/国创/影视分区的条目 goto=bangumi，原来一律按投稿 avid 打开 →
        // 加载失败或者串到别的视频。
        // 注意：rank 接口里 param 的语义并不可靠（proto 注释写的是"稿件avid"），
        // 官方跳转地址在 uri 里（形如 .../bangumi/play/ss123 / ep123 / bilibili://bangumi/...）
        // → 先用 uri 走统一路由（能吃下 ss/ep/md 与网页地址），失败再按 season_id 兜底
        if (item.goto == "bangumi") {
            if (item.uri.isNotBlank() && BilibiliNavigation.navigationTo(pageNavigation, item.uri)) {
                return
            }
            pageNavigation.navigate(SeasonCheckPage(id = item.param))
        } else {
            pageNavigation.navigateToVideoInfo(item.param)
        }
    }
}

@Composable
internal fun RankListContent(
    regionId: Int,
) {
    val viewModel = diViewModel(key = regionId.toString()) {
        RankListContentViewModel(it, regionId)
    }
    val myFollowViewModel: MyFollowViewModel by rememberInstance()
    val windowStore: WindowStore by rememberInstance()
    val userStore: UserStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val list by viewModel.list.data.collectAsState()
    val listLoading by viewModel.list.loading.collectAsState()
    val listFinished by viewModel.list.finished.collectAsState()
    val listFail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isLogin = userStore.isLogin()

    SwipeToRefresh(
        refreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(330.dp),
            modifier = Modifier.padding(
                start = windowInsets.leftDp.dp,
                end = windowInsets.rightDp.dp,
            )
        ) {
            items(list.size, { list[it].param }) {
                val item = list[it]
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(
                            end = 10.dp,
                            top = 5.dp,
                            bottom = 5.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.width(30.dp),
                        text = (it + 1).toString(),
                        textAlign = TextAlign.Center,
                        color = if (it > 2) {
                            MaterialTheme.colorScheme.onBackground
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    VideoItemBox(
                        modifier = Modifier.weight(1f),
                        title = item.title,
                        pic = item.cover,
                        upperName = item.name,
                        playNum = item.play.toString(),
                        damukuNum = item.danmaku.toString(),
                        onClick = {
                            viewModel.toVideoDetail(item)
                        }
                    )
                }
            }

            item(
                span = { GridItemSpan(maxLineSpan) }
            ) {
                ListStateBox(
                    modifier = Modifier.padding(
                        bottom = windowInsets.bottomDp.dp
                    ),
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