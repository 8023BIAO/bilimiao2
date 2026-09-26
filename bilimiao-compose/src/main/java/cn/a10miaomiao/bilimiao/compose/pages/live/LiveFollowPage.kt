package cn.a10miaomiao.bilimiao.compose.pages.live

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.components.user.enterLiveRoom
import cn.a10miaomiao.bilimiao.compose.pages.home.content.LiveRoomCard
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.live.LiveAPI
import com.a10miaomiao.bilimiao.comm.live.entity.LiveFollowListData
import com.a10miaomiao.bilimiao.comm.live.entity.LiveRoomItem
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import java.util.concurrent.atomic.AtomicLong

/**
 * 「关注直播」完整列表页（首页直播 Tab 顶部区块的「查看更多 ›」进来的就是这里）。
 *
 * ## 照着谁做的
 * PiliPlus `lib/pages/live_follow/view.dart`（`LiveFollowPage`）：
 * - 标题 = 「N人正在直播」（N 来自接口的 `live_count`），拿不到就写「关注直播」
 *   —— 同文件 :31-36；
 * - 数据 = `xlive/web-ucenter/user/following`，参数 `page` / `page_size=9` /
 *   `ignoreRecord=1` / `hit_ab=true`（`lib/http/live.dart:259-272 liveFollow`）；
 * - 只显示在播的（`live_status == 1`，客户端过滤）—— `lib/models_new/live/live_follow/data.dart:26-29`；
 * - "到底了" = 已经凑齐服务端说的在播人数，或这一页是空的
 *   —— `lib/pages/live_follow/controller.dart:18-24 checkIsEnd`；
 * - 卡片与首页**完全同一张**（[LiveRoomCard]）：PiliPlus 那边也是复用同一族的 `LiveCardVFollow`，
 *   点卡片进直播间（`lib/pages/live_follow/widgets/live_item_follow.dart:27`，
 *   `onTap: () => PageUtils.toLiveRoom(liveItem.roomid)`）。
 *
 * ## 为什么它必须注册在 BilimiaoPageRoute.kt 里
 * 导航框架按 KClass 反查路由表，漏注册的表现是"点「查看更多」直接崩"。
 * 本次注册与页面文件同一次改动完成（`BilimiaoPageRoute.kt` 的 `composable<LiveFollowPage>()`）。
 *
 * ## 和首页区块的关系
 * 两者是**两条独立请求**（区块走 feed 的 `my_idol_v1`、本页走 user/following），
 * 这不是重复：区块要的是"一眼扫到几个 + 一条 10 KB 的小请求"，
 * 本页要的是"完整、可翻页、能一直翻到没有"（feed 只下发前几个，翻不了页）。
 */
@Serializable
class LiveFollowPage : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: LiveFollowPageViewModel = diViewModel(key = "live-follow") {
            LiveFollowPageViewModel(it)
        }
        LiveFollowPageContent(viewModel)
    }
}

/**
 * 「关注直播」列表的 ViewModel。
 *
 * ★翻页判据的两个来源（都在[LiveFollowListData] 里，实测字段）：
 *   - `count` = **关注总数**（这个接口一页里既有在播也有没在播的，所以"翻到底"要看它）；
 *   - `live_count` = **在播人数**（标题里的 N，也是"已经凑齐了"的终点）。
 *   两者都不是"这一页几条"，所以**不能**用"这一页不满一页就算到底"当唯一判据
 *   （最后一页可能刚好装满、也可能全是没在播的人）。
 */
private class LiveFollowPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val context by instance<Context>()
    private val pageNavigation by instance<PageNavigation>()

    /** 已拿到的**在播**房间（每页 [LiveAPI.FOLLOW_PAGE_SIZE] 条，够不够 9 条看运气） */
    val list = FlowPaginationInfo<LiveRoomItem>(pageSize = LiveAPI.FOLLOW_PAGE_SIZE)
    val isRefreshing = MutableStateFlow(false)

    /** 「N人正在直播」的 N；**-1 = 还没拿到**（标题退回「关注直播」，照 PiliPlus 的判空） */
    val liveCount = MutableStateFlow(-1)

    // 在途请求 + 加载代数：刷新时取消旧请求，避免慢的旧批次把新数据覆盖掉（与首页列表同一套）
    private var loadJob: Job? = null
    private val loadEpoch = AtomicLong(0)

    init {
        loadData(1)
    }

    private fun loadData(pageNum: Int) {
        val epoch = loadEpoch.incrementAndGet()
        list.loading.value = true
        list.fail.value = ""
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = LiveAPI()
                    .liveFollowing(page = pageNum)
                    .awaitCall()
                    .json<ResultInfo<LiveFollowListData>>()
                if (!res.isSuccess) {
                    // 未登录是 -101、参数/风控是 -400 等：都是"接口明确说不"，
                    // 按可重试的错误态给用户（ListStateBox 会画红字 + 「重试」）
                    throw LiveFollowApiException("关注直播拉取失败（code=${res.code}）")
                }
                if (loadEpoch.get() != epoch) return@launch
                val data = res.data
                val pageItems = data?.list.orEmpty()
                val liveItems = data?.liveRooms.orEmpty()
                // 这两个数字每页都会回，直接覆盖（服务端保证一致）
                // ★`count`（关注总数）只用于诊断：判"到底了"不需要它 ——
                //   见下面那三个条件（PiliPlus 的 checkIsEnd 也只用了 live_count）
                data?.live_count?.let { liveCount.value = it }

                // 按房间号去重：翻页期间榜单会动，同一个房间可能被两页各送一次，
                // LazyGrid 的 key 撞了会直接崩（首页直播列表那边同样处理）
                val merged = if (pageNum <= 1) {
                    liveItems.distinctBy { it.roomid }
                } else {
                    val seen = list.data.value.mapTo(HashSet()) { it.roomid }
                    list.data.value + liveItems.filter { seen.add(it.roomid) }
                }
                list.pageNum = pageNum
                list.data.value = merged
                // 到底了（三个条件任一，都是"没有下一页"的硬信号）：
                //   ① 这一页是空的（服务端对越界页回空数组，实测）
                //   ② 这一页连 9 条都凑不满 ⇒ 已经是关注列表的最后一页
                //   ③ 已经攒够服务端说的在播人数（PiliPlus 的 checkIsEnd 就是这一条）
                list.finished.value = pageItems.isEmpty() ||
                    pageItems.size < list.pageSize ||
                    (liveCount.value >= 0 && merged.size >= liveCount.value)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                if (loadEpoch.get() != epoch) return@launch
                list.fail.value = if (e is java.io.IOException) "网络请求失败" else e.message ?: e.toString()
            } finally {
                if (loadEpoch.get() == epoch) {
                    list.loading.value = false
                    isRefreshing.value = false
                }
            }
        }
    }

    /**
     * 加载更多 / 失败重试。
     *
     * ★"列表是空的就重拉第 1 页"这一句不能省（与首页直播列表同一个坑）：
     *   首屏失败时 `pageNum` 还是 1，直接 `+1` 会去请求第 2 页 —— 用户点「重试」看到的是第 2 页。
     */
    fun loadMore() {
        if (!list.loading.value && !list.finished.value) {
            loadData(if (list.data.value.isEmpty()) 1 else list.pageNum + 1)
        }
    }

    /** 下拉刷新：不清空 data（清空会让列表白闪一下），第 1 页回来时整体替换 */
    fun refresh() {
        list.fail.value = ""
        list.finished.value = false
        isRefreshing.value = true
        loadData(1)
    }

    /** 点卡片 → 直接进他的直播间（复用公开的 [enterLiveRoom]，与首页/头像标记进的是同一个原生播放页） */
    fun toLiveRoom(item: LiveRoomItem) = enterLiveRoom(context, item.roomid)

    /** 点卡片上的 UP 名 → 该 UP 的用户空间（uid 由本接口直接给，无需补查） */
    fun toUserSpace(item: LiveRoomItem) {
        if (item.uid <= 0) return
        pageNavigation.navigate(UserSpacePage(id = item.uid.toString()))
    }
}

/** 接口明确返回失败（code != 0）：和"网络挂了"分开，重试文案才不会误导 */
private class LiveFollowApiException(message: String) : Exception(message)

@Composable
private fun LiveFollowPageContent(viewModel: LiveFollowPageViewModel) {
    val list by viewModel.list.data.collectAsStateWithLifecycle()
    val listLoading by viewModel.list.loading.collectAsStateWithLifecycle()
    val listFinished by viewModel.list.finished.collectAsStateWithLifecycle()
    val listFail by viewModel.list.fail.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val liveCount by viewModel.liveCount.collectAsStateWithLifecycle()

    // 标题照 PiliPlus：拿到 live_count 就写「N人正在直播」，没拿到写「关注直播」
    // （lib/pages/live_follow/view.dart:31-36）
    PageConfig(title = if (liveCount >= 0) "${liveCount}人正在直播" else "关注直播")

    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())
    val listState = rememberLazyGridState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = windowInsets.topDp.dp),
    ) {
        SwipeToRefresh(
            refreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
        ) {
            LazyVerticalGrid(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // 与首页直播 Tab / 直播搜索页同一套自适应列数（手机 1 列，平板/横屏自动多列）
                columns = GridCells.Adaptive(300.dp),
                contentPadding = windowInsets.toPaddingValues(top = 0.dp),
            ) {
                items(
                    items = list,
                    key = { it.roomid },
                ) { item ->
                    LiveRoomCard(
                        item = item,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        onClick = { viewModel.toLiveRoom(item) },
                        // 卡片上的 UP 名：进用户空间（uid 由本接口直接给，不用像搜索页那样补查）
                        onClickUpper = { viewModel.toUserSpace(item) },
                    )
                }
                item(
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    if (list.isEmpty() && !listLoading && listFail.isBlank() && listFinished) {
                        LiveFollowEmptyHint()
                    } else {
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
    }
}

/**
 * 真的一个在播的都没有时的提示。
 *
 * ★为什么要自己写一句而不是用 [ListStateBox] 的「空空如也」：这个页面空的原因**只有一种**
 *   （关注的人现在都没开播），含糊的"空空如也"会让用户以为是页面坏了。
 *   注意这是**列表页**的空态：首页那个区块遇到同样的情况是**整块不显示**（用户明确要求），
 *   两者不冲突 —— 用户主动点进来，就得告诉他为什么是空的。
 */
@Composable
private fun LiveFollowEmptyHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "你关注的人现在都没有开播",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
