package cn.a10miaomiao.bilimiao.compose.pages.home.content

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.R
import cn.a10miaomiao.bilimiao.compose.assets.BilimiaoIcons
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.Common
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.common.Upper
import cn.a10miaomiao.bilimiao.compose.common.constant.PageTabIds
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.emitter.EmitterAction
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localEmitter
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.components.user.enterLiveRoom
import cn.a10miaomiao.bilimiao.compose.pages.live.LiveFollowPage
import cn.a10miaomiao.bilimiao.compose.pages.live.LiveSearchPage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.live.LiveAPI
import com.a10miaomiao.bilimiao.comm.live.entity.LiveAreaGroup
import com.a10miaomiao.bilimiao.comm.live.entity.LiveRecommendFeed
import com.a10miaomiao.bilimiao.comm.live.entity.LiveRoomItem
import com.a10miaomiao.bilimiao.comm.live.entity.LiveStatus
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.AppStore
import com.a10miaomiao.bilimiao.comm.store.FilterStore
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.store.WindowStore
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import java.util.concurrent.atomic.AtomicLong

/**
 * 「推荐」在**分区选择**里的哨兵 id（第六阶段新增，UI 内部约定，**绝不会发给接口**）。
 *
 * ## 为什么要一个负数哨兵，而不是复用一个"看起来像分区"的值
 * 选择状态 `selectedParentId` 的取值现在有三种含义：
 * ```
 *   -1  = 推荐（B 站推荐流，不传任何分区/排序参数）
 *    0  = 全部（LiveAPI.AREA_ALL：parent_area_id=0 & area_id=0，按人气排的榜单）
 *   >0  = 某个顶级分区（值就是接口的 parent_area_id）
 * ```
 * 「推荐」**不是**一个分区（接口里没有这个 id，它是服务端按画像下发的流），所以不能借用 0
 * 或任何一个真实分区 id：借 0 会让"推荐"和"全部"变成同一个状态（点了推荐却按榜单请求），
 * 借真实 id 则会撞上某个真分区。负数在 `room/v1/Area/getList` 的 id 空间里**不存在**
 * （实测顶级分区 id 全是正整数），所以 -1 拿来当哨兵是安全的。
 *
 * ★它同时是**防御性**的：真被误发出去也只会拿到 `code=404` 空列表（分区不存在），
 *   而不会静默变成另一个分区的数据 —— 但正常情况下它到不了网络层，
 *   因为 [LiveRoomListViewModel] 拿到这个值走的是 [LiveAPI.recommendFeed] 那条分支。
 *
 * ★为什么放在 Compose 层而不是 `LiveAPI` 里：它不是接口参数，是**页面的选择状态**，
 *   跟进 `LiveAPI.AREA_ALL`（那个是货真价实的请求参数值，`parent_area_id=0`）会误导人。
 *   本文件与 [HomeLiveFilterSheet] 同包，弹窗直接引用它，不需要 import。
 */
internal const val LIVE_PARENT_RECOMMEND = -1

/**
 * 「推荐」这个选项的显示名（第六阶段的固定文案，页面与筛选弹窗**共用同一份**）。
 *
 * ★为什么单独抽一个常量：它在三个地方出现（顶部筛选按钮的文案、弹窗里那个 chip、弹窗顶部
 *   "当前：…"那一行），只要有一处写成别的字（"推荐流"/"为你推荐"），用户就会怀疑
 *   自己点的和看到的不是同一个东西。与 PiliPlus 的叫法一致（`lib/pages/live/view.dart:115`
 *   里就是写死的 `'推荐'`）。
 */
internal const val LIVE_RECOMMEND_LABEL = "推荐"

/**
 * 首页「直播」Tab（第三阶段 A 路）。
 *
 * ## 它对应 PiliPlus 的哪两个页面
 * PiliPlus 把直播浏览拆成了两层：
 *   - `lib/pages/live_area/view.dart`：**全部标签**页 —— 顶级分区 TabBar + 每个顶级分区下的子分区网格；
 *   - `lib/pages/live_area_detail/child/view.dart`：**子分区详情** —— 子分区横向标签条 + 直播房间卡片网格。
 * 这里**照它的信息层次抄成一层**（用户要的是"首页一个 Tab 里就能浏览直播"，不是再点进去两层）：
 *
 * ```
 * ┌ 搜索直播间（点击 → LiveSearchPage，B 路的搜索页）+ 右侧「分类」筛选按钮
 * └ 直播房间卡片网格（按设置的列数 / 分页 / 下拉刷新 / 失败重试 / 空态）
 * ```
 *
 * ## 分类为什么从"顶部两条标签"改成"筛选弹窗"（第四阶段改动，用户要求）
 * 原先顶级 12 个 + 子分区最多 195 个全铺在搜索框下面，用户原话是"好长的条啊，不好看也不好找"。
 * 现在照**影视/番剧**首页那一套（`HomeBangumiFilterSheet.kt`：底部弹窗 + FilterChip + 重置/确定，
 * 选中后回父级刷新列表，见 HomeBangumiContent.kt:411-419）：分类收进 [HomeLiveFilterSheet]，
 * 弹窗内两级联动（先选顶级分区，再选子分区），点「确定」→ 关弹窗 + 刷新卡片。
 * 入口有两个，走同一套 [EmitterAction.OpenFilter]：
 *   ① 搜索框右侧的「分类」按钮（始终可见，按钮上直接显示"现在在哪个分区"）；
 *   ② 底栏的「筛选」按钮（与番剧/影视完全一致的入口，见 HomePage.kt 的菜单条件）。
 * 顶部因此只剩【搜索框 + 一个筛选入口】，不再有分类长条。
 *
 * ## 列表长相跟着设置走（第四阶段：这两项是"真被读取"的设置）
 * `live_grid_span`（每行卡片数）与 `live_sort_type`（默认排序）从 AppStore 的 `state.live` 读，
 * 设置里一改、回到首页 Tab 立刻生效（不需要重进页面）。
 *
 * ## 排序入口搬到筛选弹窗（第五阶段，用户要求）
 * 用户原话："我想在直播的 Tab 首页底栏筛选的那个，在最上面，就是在全部分类的上面，按排序说
 * 排序是热度排序或者是最新排序。这样我们就不用去到设置里面了。设置里面的热度排序选项给它不显示了，
 * 给它删除代码，就移动到首页的底栏筛选那里去。"
 * 所以排序现在**在 [HomeLiveFilterSheet] 里选**（设置页那一项由另一路删除），本文件的职责是：
 *   ① 把当前排序交给弹窗显示（[HomeLiveContent] 里的 `sortType`）；
 *   ② 弹窗点「确定」后，**把新排序写回同一个键** `live_sort_type`（老用户的值和默认值都不变）；
 *   ③ 排序变了就用新排序重建列表 VM —— `sort_type` 是请求参数，和分区一起进 VM key，
 *      两个条件永远在**同一条请求**里生效（见 [LiveRoomList]）。
 *
 * ★为什么本地还存一份 `pickedSortType`（而不是只依赖 AppStore 回流）：
 *   DataStore 写盘是**异步**的，只等回流的话，用户点完「确定」会先按旧排序请求一次、
 *   回流到了再按新排序请求一次（列表闪两下、还白发一次请求）。
 *   本地这份让"用户刚选的排序"当帧就生效；写盘只负责"下次进来还是它"。
 *   它和设置值是同一个字符串（都取自 `SettingPreferences.Live` 的常量），不存在两份真源打架。
 *
 * 刻意**没有照抄**的只有视觉细节（Flutter 的 SearchText chip、Skeleton 骨架屏、SliverGrid 参数），
 * 本页一律用工程既有的 M3 组件 + `ListStateBox`/`SwipeToRefresh`，跟首页其它 Tab 长得一样。
 *
 * ## 数据来源（全部实测过，详见 LiveAPI.kt 的注释）
 *   - 分区树：`room/v1/Area/getList` → 12 个顶级分区 / 450 个子分区，每个子分区带图标；
 *   - 房间列表：`room/v1/Area/getRoomList`（`parent_area_id` + `area_id` + `page` + `sort_type`），
 *     `sort_type=online` 按人气（B 站"热门直播"，默认）、`sort_type=live_time` 按开播时间倒序（"最新"）。
 *     ★本次交付重新用容器 curl 实测过：`live_time` 前 12 个房间的真实开播时间严格递减、
 *       第 2 页开播时间继续低于第 1 页末尾（分页也成立），字段集与 `online` 完全一致（实体兼容）。
 *
 * ## 为什么没有 ComposePage
 * 本页是**挂在首页 Tab 里的普通 Composable**（`HomePageTab.Live`），不是独立路由，
 * 所以不需要（也**不允许**）在 `BilimiaoPageRoute.kt` 注册 —— 代码检查规则 C 针对的是 ComposePage 子类。
 *
 * ## 第六阶段：分类里多了一个「推荐」（用户要求，排在「全部」**之前**）
 * 用户原话："皮皮 Plus 它直播有一个推荐的 Tab……我想添加在那个全部 tag 那上面，前面就它前面
 * 写一个推荐，然后去推荐之后这些都是应该有系统的 API 分流推荐给我们，我们用它的就行。
 * 然后如果用户想自定义化它会去选择其他的分类什么的。"
 *
 * 落的三个点，一个不多一个不少：
 *   ① [HomeLiveFilterSheet] 的「顶级分区」那一排，第一个 chip 是**推荐**、第二个才是全部
 *      （照 PiliPlus `lib/pages/live/view.dart:105-128`：标签条第一个格子恒为「推荐」）；
 *   ② 「推荐」是**独立选项**（[LIVE_PARENT_RECOMMEND]），点它 → [LiveAPI.recommendFeed]，
 *      不传 `parent_area_id/area_id/sort_type`（这条流没有这些条件，是服务端分流给我们的）；
 *   ③ 想自己挑分区/排序的用户，照旧在同一个弹窗里选（行为一个字都没改，仍走 `areaRoomList`）。
 *
 * ★**默认值保持原样**：`selectedParentId` 的初始值仍然是 [LiveAPI.AREA_ALL]（全部），
 *   「推荐」只多一个选项、不改任何既有默认（用户要求"别改用户既有观感"）。
 *   谁想用推荐，自己去点一下，点完 `rememberSaveable` 会记住（切 Tab/转屏不丢）。
 *
 * ★顶部那个筛选按钮的文案也跟着走：选「推荐」时按钮上写"推荐"（见 [HomeLiveContent] 的 filterLabel），
 *   用户不用点开弹窗就知道自己在看推荐流。
 */

/**
 * 分类树（顶级分区 + 子分区）。
 *
 * 为什么单独一个 ViewModel、而且只加载一次：这棵树是**准静态**的（B 站不会分钟级改分区），
 * 实测一次请求就有 12+450 条、十几 KB，跟着房间列表每次刷新都重拉纯属浪费。
 * 它是 `diViewModel()`（挂在当前导航条目的 ViewModelStore 上），切 Tab 回来不会重新请求。
 */
private class HomeLiveContentViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    /** 顶级分区（接口原样给，顺序也照接口；「全部」是 UI 侧的合成项，不入这个表） */
    val groups = mutableStateListOf<LiveAreaGroup>()

    /** 分类树自己的加载态/失败态（和房间列表是两条独立的请求，失败也要能各自重试） */
    val loading = MutableStateFlow(true)
    val fail = MutableStateFlow("")

    private var loadJob: Job? = null
    private val loadEpoch = AtomicLong(0)

    init {
        loadData()
    }

    fun loadData() {
        val epoch = loadEpoch.incrementAndGet()
        // 同步置位（调用线程），第一帧就是"加载中"，不会先闪一下"空空如也"
        loading.value = true
        fail.value = ""
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = LiveAPI().areaList()
                    .awaitCall()
                    .json<ResultInfo<List<LiveAreaGroup>>>()
                if (!res.isSuccess) {
                    // 实测正常恒为 code=0；-352 是风控（请求头不对/请求过密），
                    // 这种"接口明确说不"的情况要把 code 带出来，用户报错时才有线索
                    throw LiveApiException("直播分区拉取失败（code=${res.code}）")
                }
                val list = res.data.orEmpty().filter { it.name.isNotBlank() && it.list.isNotEmpty() }
                if (loadEpoch.get() != epoch) return@launch
                // 内容没变就别动 list：无谓的 clear+addAll 会让标签条闪一下
                if (list != groups) {
                    groups.clear()
                    groups.addAll(list)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                if (loadEpoch.get() != epoch) return@launch
                fail.value = if (e is java.io.IOException) "网络请求失败" else e.message ?: e.toString()
            } finally {
                if (loadEpoch.get() == epoch) {
                    loading.value = false
                }
            }
        }
    }
}

/** 接口明确返回失败（code != 0，如 -352 风控）：和"网络挂了"分开，重试文案才不会误导 */
private class LiveApiException(message: String) : Exception(message)

/**
 * 一页直播数据的产物：**过滤前**的原始条目 + "服务端还有没有下一页"。
 *
 * ★为什么原始条目与"到底了"要一起返回：屏蔽过滤（[LiveRoomItem.passFilter]）会删条目，
 *   而"到底了"必须按**过滤前**的条数判断（服务端给不满一页 = 最后一页）——
 *   两者分开传的话，调用方很容易图省事拿过滤后的列表去算，那就掉进
 *   "整页都被屏蔽 → 误判没有更多 → 后面的页永远翻不出来"这个坑里（HomePopularContent.kt:195 的教训）。
 *   绑在一个对象里返回，就不会有人只拿到一半。
 */
private class LivePage(
    val raw: List<LiveRoomItem>,
    val finished: Boolean,
)

/**
 * 某个分区的直播房间列表（分页）。
 *
 * 每个分区一个实例（`diViewModel(key = "home-live-{parent}-{area}-{sort}")`），所以来回切分区
 * 不会丢掉已经加载的列表、也不会每次点击都重新请求 —— 和「分区」Tab 的 RegionVideoListViewModel
 * 是同一套做法（那边用 HorizontalPager 保活，这边用 VM key 保活）。
 *
 * [sortType] 也必须进 key：它是**请求参数**（`sort_type`），只改排序而 key 不变的话，
 * diViewModel 会把老 VM 还回来，用户会看到"排序改了但列表没变"
 * （第五阶段排序入口搬到筛选弹窗后，这一条同样是"点了最新排序立刻重新请求"的关键）。
 *
 * ★parentId / areaId / sortType **三个一起**进 key，也一起进请求参数（见 [loadData]）：
 *   分类和排序是**并列**的筛选条件，任何一项变了都得换 VM 重拉；
 *   少了谁都会出现"新条件 + 旧数据"（比如只按新排序拉了全站，用户选的分区被丢掉）。
 *
 * ## 第六阶段：「推荐」也复用过这个 VM（[parentId] == [LIVE_PARENT_RECOMMEND] 时走另一条接口）
 * ★为什么不让推荐另起一个 ViewModel：分页/下拉刷新/失败重试/屏蔽过滤/去重/进播放页这一整套
 *   逻辑跟分区列表**逐行相同**，唯一的不同是"请求哪条接口、怎么解析"。所以这里只在
 *   [loadData] 里分了一个请求分支 —— 页面网格、卡片、`SwipeToRefresh`、`ListStateBox`
 *   一行都不用改，也不会多出第二份"推荐专用"的列表组件。
 *
 * ★`recommend` 是**从 [parentId] 推出来的**，不是构造参数：一个状态只有一个来源，
 *   就不可能造出"parentId=推荐 但 recommend=false"这种自相矛盾的 VM。
 *
 * ★推荐流的 [sortType] **不是请求参数**（接口压根没有 sort_type），所以调用方
 *   （[LiveRoomList] 的 VM key、[HomeLiveContent] 的 `key(...)`）都**不把 sortType 算进身份**，
 *   否则"推荐 + 换个排序"会白白建出第二个 VM、发第二条一模一样的请求（拿到的数据还完全相同）。
 */
private class LiveRoomListViewModel(
    override val di: DI,
    private val parentId: Int,
    private val areaId: Int,
    private val sortType: String,
) : ViewModel(), DIAware {

    /** 当前是不是「推荐」流（唯一判据是 [parentId]，见类注释） */
    private val recommend: Boolean get() = parentId == LIVE_PARENT_RECOMMEND

    private val context: Context by instance()
    private val filterStore: FilterStore by instance()

    /**
     * 页面导航（点 UP 名 → 用户空间）。
     *
     * ★为什么注入到 ViewModel、而不是像同文件的 [LiveSearchEntry] 那样在 Composable 里
     *   `rememberInstance()`：点卡片进直播间这件事本来就由本 ViewModel 负责（[toLiveRoom]），
     *   两个跳转放在一起，卡片那边只管"点哪儿触发哪个回调"，不掺导航细节。
     *   `PageNavigation by instance()` 是首页其它 Content 的同一套写法
     *   （HomePopularContent.kt:91、HomeBangumiContent.kt:76）。
     */
    private val pageNavigation: PageNavigation by instance()

    // 每页条数跟着数据源走：分区列表 30（实测 page_size=30 不缩水），推荐流 20（实测接口写死 20）
    val list = FlowPaginationInfo<LiveRoomItem>(
        pageSize = if (recommend) LiveAPI.RECOMMEND_PAGE_SIZE else LiveAPI.AREA_ROOM_PAGE_SIZE
    )
    val isRefreshing = MutableStateFlow(false)

    // 在途请求 + 加载代数：刷新时取消旧请求，避免慢的旧批次用旧数据覆盖新列表
    // （首页其它 Content 都是这套，照抄 HomeRegionContent.kt:327-328）
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
                // 取这一页（两条数据源的分叉在 [requestPage] 里，这里只管拿结果）
                val page = requestPage(pageNum)
                // 等屏蔽开关就位再过滤，避免冷启动第一批在默认值下漏过本该屏蔽的房间
                filterStore.awaitSettingsReady()
                val items = page.raw.filter { it.passFilter(filterStore) }
                if (loadEpoch.get() != epoch) return@launch
                // ★按房间号去重：分区列表是**按实时人气排序**的、推荐流是**实时推荐**的，
                //   翻页期间榜单/画像都会动，第 2 页完全可能把第 1 页出现过的房间再送一遍；
                //   LazyGrid 的 key 撞了会直接崩（实测推荐流相邻页不重复，但这条不能赌）。
                val merged = if (pageNum <= 1) {
                    items.distinctBy { it.roomid }
                } else {
                    val seen = list.data.value.mapTo(HashSet()) { it.roomid }
                    list.data.value + items.filter { seen.add(it.roomid) }
                }
                list.pageNum = pageNum
                list.data.value = merged
                list.finished.value = page.finished
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                if (loadEpoch.get() != epoch) return@launch
                list.fail.value = if (e is java.io.IOException) "网络请求失败" else e.message ?: e.toString()
            } finally {
                // 只有最新一代请求才复位加载标志（被取消的旧批次不得复位）
                if (loadEpoch.get() == epoch) {
                    list.loading.value = false
                    isRefreshing.value = false
                }
            }
        }
    }

    /**
     * 请求"第 [pageNum] 页"，两条数据源在这里分叉（**唯一**的一处不同）：
     *
     * ```
     * 推荐 → xlive/app-interface/v2/index/feed     （B 站推荐流，不传分区/排序，见 LiveAPI.recommendFeed）
     * 分区 → room/v1/Area/getRoomList              （parent_area_id + area_id + sort_type）
     * ```
     *
     * ★为什么单独抽一个 suspend 函数、返回 [LivePage] 而不是在 [loadData] 里用两个分支直接赋值：
     *   ① `loadData` 的主体是 `viewModelScope.launch { … }`，而 **lambda 里给外部声明的局部
     *      `val` 赋值在 Kotlin 里是编译错误**（"Captured values initialization is forbidden…"）；
     *   ② 抽出来之后"过滤/去重/分页状态"那一段与数据源无关的代码只有一份，两条路不可能各写各的。
     */
    private suspend fun requestPage(pageNum: Int): LivePage {
        if (recommend) {
            val res = LiveAPI()
                .recommendFeed(page = pageNum)
                .awaitCall()
                .json<ResultInfo<LiveRecommendFeed>>()
            if (!res.isSuccess) {
                throw LiveApiException("直播推荐拉取失败（code=${res.code}）")
            }
            // feed 可能是 null（data 缺失）或没有 small_card_v1（只回了 banner/分区入口），
            // 两种都按"这一页没有房间"处理 —— 空列表会让用户看到空态 + 重试，比崩好
            val feed = res.data
            val rooms = feed?.rooms.orEmpty()
            // ★推荐流是"无限流"，实测翻到第 60 页 `has_more` 依然是 1，
            //   所以"到底了"要靠这两个条件**任一**成立：服务端明确说没有了（`has_more == 0`），
            //   或这一页不够一页。`has_more` 缺席（null）时只按每页条数判断 ——
            //   把"没说"当成"没有了"会让分页在第 1 页后就停掉，那才是真的坏。
            val finished = feed == null || feed.has_more == 0 || rooms.size < list.pageSize
            return LivePage(rooms, finished)
        }

        val res = LiveAPI()
            .areaRoomList(
                parentAreaId = parentId,
                areaId = areaId,
                page = pageNum,
                // 排序来自 live_sort_type（第五阶段起由筛选弹窗写入，设置页那一项已删）。
                // ★实测 sort_type 传空串会**静默返回 0 条**
                // （不是报错），所以 Values.sortTypeOrOnline 已经把非法值兜底成 online
                sortType = sortType,
            )
            .awaitCall()
            .json<ResultInfo<List<LiveRoomItem>>>()
        if (!res.isSuccess) {
            throw LiveApiException("直播列表拉取失败（code=${res.code}）")
        }
        // ★"到底了"必须看**过滤前**的条数：服务端给不满一页 = 最后一页。
        //   若改用过滤后的条数，万一整页都被屏蔽规则滤掉，就会被误判成"没有更多"，
        //   后面的页再也翻不出来（首页热门那边就是吃了这个亏，见 HomePopularContent.kt:195）。
        val raw = res.data.orEmpty()
        return LivePage(raw, raw.size < list.pageSize)
    }

    /**
     * 加载更多/重试。
     *
     * ★"列表是空的就重拉第 1 页"这一句不能省：首屏失败时 `pageNum` 还是 1，
     *   直接 `pageNum + 1` 会去请求第 2 页 —— 用户点"重试"，看到的却是第 2 页的内容（首页直接是空的）。
     *   （同一个写法见 TagFollowContent.kt:214）
     */
    fun loadMore() {
        if (!list.loading.value && !list.finished.value) {
            loadData(if (list.data.value.isEmpty()) 1 else list.pageNum + 1)
        }
    }

    fun refresh() {
        // 先清空旧列表：屏蔽规则/分区换了之后，旧内容继续显示到新列表回来会造成"闪一下又消失"
        list.reset()
        isRefreshing.value = true
        loadData(1)
    }

    /**
     * 点卡片 → **原生直播播放页**（第二阶段 A 路的成果，这里只调用）。
     *
     * ★为什么用 `setClassName` 的类名字符串、而不是 `import LivePlayerActivity`：
     *   LivePlayerActivity 在 **app 模块**，本文件在 **bilimiao-compose 模块**，
     *   依赖方向是 app → compose，反向引用会成环编译不过。extra 的 key `"roomId"`
     *   与 `LivePlayerActivity.EXTRA_ROOM_ID` 是同一份字面量约定
     *   （同样的做法见 bilimiao-cover 的 CoverViewModel.kt:84-99）。
     *
     * ★为什么传 `roomid`（真实房间号）：列表接口给的就是真实号；即使哪天给的是短号也没关系，
     *   播放页进来第一件事就是 room_init 换算，真实号是幂等的。
     */
    fun toLiveRoom(item: LiveRoomItem) {
        if (item.roomid <= 0) return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(context, LIVE_PLAYER_ACTIVITY)
            putExtra(LIVE_PLAYER_EXTRA_ROOM_ID, item.roomid.toString())
        }
        // 原生页万一拉不起来（理论上不会），退回网页直播间，别让用户点了没反应
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://live.bilibili.com/${item.roomid}"))
                )
            }
        }
    }

    /**
     * 点卡片上的 UP 名 → **该 UP 主的用户空间**（点卡片其它区域仍是进直播间，两者互不干扰，
     * 点击区域的划分见 [LiveRoomCard]）。
     *
     * ★uid 从哪来（**实测，不是猜的**）：`room/v1/Area/getRoomList` 的响应里就带 `uid`，
     *   `LiveRoomItem.uid` 早就建模了它（实体注释原话："主播 uid（= LivePlayerActivity 里 mid 的来源）"）。
     *   所以进用户空间**不需要多发一次请求**，卡片上这一次点击直接用列表里已有的 mid。
     *   （★这也是本页能做、而两个「直播搜索」页做不了的原因：`search_live` 实测**不返回 uid**，
     *     详见本次交付报告；那边保持现状，没有硬编任何 id。）
     *
     * ★为什么也判一次 `uid > 0`（卡片那边已经判过）：接口偶发脏条目（屏蔽规则里也专门判过 uid），
     *   真拿到 0 时 `UserSpacePage("0")` 只会打开一个报错页 —— 宁可什么都不做。
     *   注意这里**不 toast**：用户点的是"名字"，没反应比弹一个"打不开"更不容易让人误会成 App 坏了；
     *   而且 uid<=0 时卡片根本不会把这一块做成可点（点击会落回卡片=进直播间）。
     */
    fun toUserSpace(item: LiveRoomItem) {
        if (item.uid <= 0) return
        pageNavigation.navigate(UserSpacePage(id = item.uid.toString()))
    }

    companion object {
        /** 与 app 模块 LivePlayerActivity 的类名/extra key 对齐的字面量约定（见方法注释） */
        private const val LIVE_PLAYER_ACTIVITY = "com.a10miaomiao.bilimiao.LivePlayerActivity"
        private const val LIVE_PLAYER_EXTRA_ROOM_ID = "roomId"
    }
}

/**
 * 直播房间的屏蔽规则。
 *
 * ★只吃"标题/UP 名/UP mid"三条，**刻意不吃**时长和播放量两条：
 *   那两条设置的语义是"视频时长/播放量"（`VideoMinDuration` / `VideoMinPlayCount`），
 *   而直播这边对应的是**实时人气** —— 一个刚开播、只有 3 个人看的主播会被"播放量≥1000"直接隐藏，
 *   这不是用户的意图（他屏蔽的是"短视频/低播放视频"，不是"小主播"）。宁可少过滤，不要误伤。
 */
private fun LiveRoomItem.passFilter(filterStore: FilterStore): Boolean {
    // 房间号为 0 的条目直接丢：LazyGrid 的 key 就是 roomid，两条 0 撞在一起会崩整个页面
    // （接口正常不会给 0，这是"崩不起"的兜底；顺带也挡掉了"点进去必然失败"的卡片）
    if (roomid <= 0) return false
    if (!filterStore.filterWord(title)) return false
    if (uid > 0 && !filterStore.filterUpper(uid)) return false
    if (uname.isNotBlank() && !filterStore.filterUpperName(uname)) return false
    return true
}

@Composable
internal fun HomeLiveContent() {
    val viewModel: HomeLiveContentViewModel = diViewModel()
    val groups = viewModel.groups
    val areaLoading by viewModel.loading.collectAsStateWithLifecycle()
    val areaFail by viewModel.fail.collectAsStateWithLifecycle()

    // 选中的分区：顶级按接口给的数字 id 记（不是下标 —— 分区表刷新后下标会整体错位，
    // 出现"点的是手游，接口一回来高亮和内容跳到网游"）。0 = 全部。
    // ★第六阶段：-1 = 推荐（[LIVE_PARENT_RECOMMEND]，UI 侧哨兵，不是分区）。
    //   **默认值一个字没改**：老用户点进这个 Tab 看到的还是「全部」的榜单，
    //   推荐只多一个可选项（用户要求"别改既有观感"）。
    var selectedParentId by rememberSaveable { mutableStateOf(LiveAPI.AREA_ALL) }
    var selectedAreaId by rememberSaveable { mutableStateOf(0) }

    // ★筛选记忆（用户 2026-09-26："首页直播 Tab 的底栏筛选没有持久化记忆，番剧/影视就有"）：
    //   上面两个 `rememberSaveable` 只活到进程被杀；这里照番剧那套再落一份盘（一个字符串键 `parent:area`），
    //   进页面时异步读回一次。排序有自己的键（live_sort_type），不在这里。
    //   只读一次（`LaunchedEffect(Unit)`），失败就保持默认「全部」——不影响任何现有行为。
    // 显式取一次 Context：这个作用域里裸写 `context` 会解析到同名的组合函数（编译报
    // "Function invocation 'context(...)' expected"），必须显式从 LocalContext 拿。
    val filterCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        runCatching {
            SettingPreferences.mapData(filterCtx) { it[SettingPreferences.HomeLiveFilter] }
        }.getOrNull()?.let { saved ->
            val parts = saved.split(':')
            val pid = parts.getOrNull(0)?.toIntOrNull()
            val aid = parts.getOrNull(1)?.toIntOrNull()
            if (pid != null && aid != null) {
                selectedParentId = pid
                selectedAreaId = aid
            }
        }
    }

    // 筛选弹窗（第四阶段做分类、第五阶段加排序）：显隐 + "点了几次确定"。
    // ★为什么要有 tick：同一分区再点一次「确定」也要把列表刷一遍（用户："选完关闭弹窗并刷新卡片"），
    //   而选中项没变时列表组件不会重建，只能靠这个计数让里面主动 refresh（见 LiveRoomList 的参数）。
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var filterApplyTick by rememberSaveable { mutableStateOf(0) }

    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    // 直播设置（每行卡片数 / 默认排序）：读 AppStore 而不是直接 collect DataStore，
    // 这样和设置页共用同一份 Values（默认值、字段语义都只有一处定义）
    val appStore: AppStore by rememberInstance()
    val liveSetting = appStore.stateFlow.collectAsStateWithLifecycle().value.live

    /**
     * 当前排序（= 发给接口的 `sort_type`）。
     *
     * 取值优先级：**用户在筛选弹窗里刚选的**（[pickedSortType]）→ 设置里的 `live_sort_type`
     * （`sortTypeOrOnline`，非法值兜底成 online）→ 默认"热度排序"。
     * 默认这条链的终点就是**改动前的现状**：弹窗一打开，"热度排序"是选中的那一个。
     *
     * ★哨兵用空串而不是 null：`rememberSaveable` 的 autoSaver 存不了 null（save 返回 null =
     *   这个值不可保存），空串则一定能进 Bundle。而空串本身是**非法 sort_type**
     *   （实测接口对空串静默返回 0 条），所以下面用 ifBlank 兜底 —— 它绝不会被真的发出去。
     */
    var pickedSortType by rememberSaveable { mutableStateOf("") }
    val sortType = pickedSortType.ifBlank { liveSetting.sortTypeOrOnline }

    // 写盘用：DataStore 的 edit 是挂起函数，得有作用域；context 从 Compose 拿
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 筛选按钮上的文案：显示"现在在哪个分区"，用户不用点开弹窗就知道自己看的是哪一类。
    // ★排序**故意不塞进这个按钮**：按钮最宽 72dp（见 LiveSearchEntry 的 widthIn），
    //   再挂一个"·最新排序"会把分区名挤没；排序的选中态在弹窗里已经有明确显示。
    // ★第六阶段：选「推荐」时这里就是"推荐"（它不在分区树里，所以要**先**判它，
    //   否则会掉进下面 `parent == null` 的分支显示成"全部" —— 用户明明在看推荐流）。
    // 写成 if/else 而不是 when{...}：局部 val 的智能转换在 if/else 里是稳的，
    // 不值得为了少两行去赌 when 分支里的类型收窄
    val filterLabel = remember(groups.toList(), selectedParentId, selectedAreaId) {
        val parent = groups.firstOrNull { it.id == selectedParentId }
        if (selectedParentId == LIVE_PARENT_RECOMMEND) {
            LIVE_RECOMMEND_LABEL
        } else if (parent == null) {
            // 顶级还是「全部」（它不在 groups 里）+ 分类树还没到位的过渡态，都显示"全部"
            "全部"
        } else {
            val sub = parent.list.firstOrNull { it.id.toIntOrNull() == selectedAreaId }
            if (selectedAreaId == 0 || sub == null) parent.name else sub.name
        }
    }

    // 底栏那个「筛选」按钮也走这条路（与番剧/影视同一套 EmitterAction.OpenFilter）：
    // 条件不满足时它不会被点出来，这里只是把两个入口汇到同一个弹窗
    val emitter = localEmitter()
    LaunchedEffect(Unit) {
        emitter.collectAction<EmitterAction.OpenFilter> {
            if (it.tabId == PageTabIds.HomeLive) {
                showFilter = true
            }
        }
    }

    // 存档里的顶级分区在新树里不存在时（进程被杀后重启、或 B 站调了分区）回到「全部」，
    // 不然会拿着一个不存在的 parent_area_id 去请求 → 接口回 404 空列表，用户只看到"空空如也"。
    // ★「全部」(0) 不在 groups 里（它是 UI 侧的合成项），所以要显式排除它，否则每次都会"重置"一遍。
    // ★第六阶段：「推荐」(-1) **同样必须显式排除** —— 它也不在 groups 里，漏了这一条的话，
    //   用户一点「推荐」就会被这个 effect 立刻打回「全部」（表现是"点了没反应"）。
    LaunchedEffect(groups.toList(), selectedParentId) {
        if (groups.isNotEmpty() &&
            selectedParentId != LiveAPI.AREA_ALL &&
            selectedParentId != LIVE_PARENT_RECOMMEND &&
            groups.none { it.id == selectedParentId }
        ) {
            selectedParentId = LiveAPI.AREA_ALL
            selectedAreaId = 0
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LiveSearchEntry(
            filterLabel = filterLabel,
            onFilterClick = { showFilter = true },
            startPadding = windowInsets.leftDp.dp,
            endPadding = windowInsets.rightDp.dp,
        )

        if (groups.isEmpty()) {
            // 分类树没拿到：加载中转圈；失败给明确的重试按钮（这一层挂了整个页面都没内容，
            // 所以不能只靠网格底部那行"重试"，得整屏给）
            LiveAreaStateBox(
                loading = areaLoading,
                fail = areaFail,
                onRetry = viewModel::loadData,
            )
        } else {
            // ★key(...)：切分区/换排序时把网格的滚动位置/下拉刷新状态一起重置。
            //   不加这个 key，rememberLazyGridState 会被复用 —— 在「网游」翻到第 25 个卡片再切到「手游」，
            //   新列表会直接从第 25 个卡片开始显示（列表明明是新的，位置却是旧的）。
            //   ★排序也进 key 的两个理由：① 排序换了就是另一份榜单，滚动位置同样该回到顶部；
            //   ② 本组件里的 handledTick（"这个 tick 已处理过"）会跟着 key 一起重置，
            //      于是"换排序"只走新 VM 的首次加载，不会再被 applyTick 触发第二次 refresh
            //      （一个条件变化 = 一条请求）。
            // gridModifier 在 key 外面算：weight 是 ColumnScope 的，写在 inline 的 key{} 里虽然也能解析，
            // 但拿出去更直观，也避免以后有人把 key 换成非 inline 的包装函数时突然编译不过。
            // ★第六阶段：key 用 [liveListIdentity]（推荐下不含 sortType），
            //   它与 [LiveRoomList] 里 VM 的 key 是**同一个函数算出来的**，两处永远一致 ——
            //   否则会出现"组件重建了但 VM 没换"（或反过来）这种只刷新一半的状态。
            val gridModifier = Modifier.weight(1f)
            key(liveListIdentity(selectedParentId, selectedAreaId, sortType)) {
                LiveRoomList(
                    parentId = selectedParentId,
                    areaId = selectedAreaId,
                    // 每行卡片数只影响布局；排序是请求参数，跟着 VM key 一起换（见 LiveRoomList）
                    gridSpan = liveSetting.gridSpan,
                    sortType = sortType,
                    applyTick = filterApplyTick,
                    modifier = gridModifier,
                )
            }
        }
    }

    // 筛选弹窗：分类树还没到位时不弹（弹了里面是空的，只能选个"全部"）
    if (showFilter && groups.isNotEmpty()) {
        HomeLiveFilterSheet(
            groups = groups,
            currentParentId = selectedParentId,
            currentAreaId = selectedAreaId,
            // 当前排序传进去 = 弹窗里默认高亮的那个；用户没动过就是设置里的值（现状不变）
            currentSortType = sortType,
            onApply = { parentId, areaId, newSortType ->
                // ★分类和排序在**同一处**落地：三个值一起改，下游（key + VM key + 请求参数）
                //   才可能同时生效，不会出现"新排序配旧分区"这种半拉子状态
                selectedParentId = parentId
                selectedAreaId = areaId
                // ★同一处落盘（与排序写回并列）：只记"分区"，排序由下面那段单独写 live_sort_type。
                //   失败不影响本帧（内存里已经生效），最多"下次进来回到上次存住的分区"。
                scope.launch {
                    runCatching {
                        SettingPreferences.edit(context) {
                            it[SettingPreferences.HomeLiveFilter] = "$parentId:$areaId"
                        }
                    }
                }
                if (newSortType != sortType) {
                    // 先改内存（这一帧的 sortType 就是新值 → key/VM 立刻换 → 用新排序重新请求），
                    // 再异步落盘（只负责"下次进 App 还是这个排序"，慢一点不影响这一帧的列表）。
                    // ★写的是**同一个键** live_sort_type，值也是原样的 sort_type 字符串：
                    //   设置页那一项虽然删了，老用户已存的值照样读得出来，等于把入口搬了个家。
                    pickedSortType = newSortType
                    scope.launch {
                        // 落盘失败不该影响已经生效的排序（内存里这份还在），所以吞掉异常：
                        // 顶多是"下次进来回到上次存住的排序"，比弹一个看不懂的错误好
                        runCatching {
                            SettingPreferences.edit(context) {
                                it[SettingPreferences.LiveSortType] = newSortType
                            }
                        }
                    }
                }
                // 无条件 +1：即使选的是同一个分区、同一个排序，也要让下面的列表刷一次
                filterApplyTick++
            },
            onDismiss = { showFilter = false },
        )
    }
}

/**
 * 顶部一行：搜索入口 + 分类筛选入口。
 *
 * 用户原话是"什么界面啊、搜索啊、什么分类啊，给它全抄了"，而搜索页本身由 **B 路**实现
 * （`cn.a10miaomiao.bilimiao.compose.pages.live.LiveSearchPage(keyword)`，路由注册也在 B 路），
 * 这里只放入口：一个**看得见的搜索框**（不是一个 24dp 的小图标 —— 用户抱怨的就是"找不到入口"），
 * 点一下带着空关键字进搜索页，由搜索页自己去输入。
 *
 * ★为什么不做成可输入的输入框：在 Tab 里输入要么把键盘顶在首页上、要么输入到一半切 Tab 丢字，
 *   而且会有"首页搜索"和"直播搜索"两个输入框抢焦点的观感。点一下进专门的搜索页最省事，
 *   也和 PiliPlus 的做法一致（`live_area_detail` 的 AppBar 上就是一个搜索图标 → LiveSearchPage）。
 *
 * ★右侧为什么还留一个「分类」按钮（第四阶段）：分类长条搬进筛选弹窗后，页面必须有一个**看得见**的
 *   入口（底栏那个「筛选」按钮在滚动时会被收起，且新用户不一定注意到）。按钮上直接写当前分区名，
 *   等于顺手告诉用户"你现在看的是哪一类"——这正是旧版长条唯一的信息价值。
 */
@Composable
private fun LiveSearchEntry(
    filterLabel: String,
    onFilterClick: () -> Unit,
    startPadding: Dp,
    endPadding: Dp,
) {
    val pageNavigation: PageNavigation by rememberInstance()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = startPadding + 10.dp,
                end = endPadding + 10.dp,
                top = 8.dp,
                bottom = 2.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "搜索直播") {
                    // 空关键字 = 打开搜索页让用户自己输入（签名锁死：LiveSearchPage(keyword: String = "")）
                    pageNavigation.navigate(LiveSearchPage(keyword = ""))
                },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "搜索直播间 / 主播",
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        FilterChip(
            selected = false,
            onClick = onFilterClick,
            leadingIcon = {
                Icon(
                    // 筛选图标：用 material-icons-extended 的 FilterList（app 模块那两张
                    // ic_baseline_filter_list_*.xml 是 **app 的资源**，compose 模块引用不到）
                    imageVector = Icons.Outlined.FilterList,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            label = {
                Text(
                    text = filterLabel,
                    // 子分区名可能很长（"三角洲行动"这种），限宽 + 省略号，别把搜索框挤没
                    modifier = Modifier.widthIn(max = 72.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
}

/** 分类树整体不可用时的整屏状态：转圈 / 失败 + 重试 */
@Composable
private fun LiveAreaStateBox(
    loading: Boolean,
    fail: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (fail.isNotBlank()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = fail,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onRetry) {
                    Text(
                        "重试",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        } else if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
            )
        } else {
            Text(
                text = "暂时拿不到直播分区",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * 直播列表的**身份串**：分区/排序任一变化 = 换一份列表（`key(...)` 重建组件 + 新建 VM 重新请求）。
 *
 * ★为什么抽成一个函数：它被用在两处（[HomeLiveContent] 的 `key(...)` 与 [LiveRoomList] 的
 *   `diViewModel(key = …)`），两处必须**永远一致** —— 不一致就会出现"组件重建了但 VM 还是老的"
 *   （看到旧数据）或"VM 换了但滚动状态没重置"这类只坏一半的问题。有一处算错的余地都不留。
 *
 * ★第六阶段：选「推荐」时身份里**不含 [sortType]**。推荐流的请求参数里没有 `sort_type`
 *   （服务端按画像下发，排序由它自己决定），所以排序对推荐**不构成一份新列表**：
 *   带上它只会凭空多建一个 VM、多发一条一模一样的请求、还缓存两份同样的数据。
 *   用户从"推荐"切回某个分区时，排序照旧生效（那时 sortType 又回到身份里了）。
 */
private fun liveListIdentity(parentId: Int, areaId: Int, sortType: String): String =
    if (parentId == LIVE_PARENT_RECOMMEND) "rec" else "$parentId-$areaId-$sortType"

/** 某个分区的直播房间网格（「推荐」也在内：它走的是同一个网格 + 同一张卡片，见 [LiveRoomListViewModel]） */
@Composable
private fun LiveRoomList(
    parentId: Int,
    areaId: Int,
    gridSpan: Int,
    sortType: String,
    applyTick: Int,
    modifier: Modifier = Modifier,
) {
    // sortType 进 VM key：它是请求参数，换了排序必须换 VM（否则会拿到老 VM 的老列表）。
    // ★key 与父级 `key(...)` 用同一个 [liveListIdentity]（推荐下不含 sortType），见那个函数的注释。
    val viewModel: LiveRoomListViewModel =
        diViewModel(key = "home-live-${liveListIdentity(parentId, areaId, sortType)}") {
            LiveRoomListViewModel(it, parentId, areaId, sortType)
        }
    // 顶部「我的关注 · 正在直播」区块的 VM。
    //
    // ★为什么 key 里**没有**分区/排序（与上面那条正相反）：关注直播跟"用户在看哪个分区"毫无关系，
    //   把分区塞进 key 只会让每次切分区都白拉一次关注列表、并且让区块闪一下。
    //   固定 key ⇒ 切分区/换排序时 diViewModel 返回**同一个 VM**（VM 存在导航条目的 ViewModelStore 里），
    //   既不会重新请求，也不会丢已经拿到的数据。
    // ★它在这里（而不是在区块内部）创建：下面 SwipeToRefresh / 双击 Tab 的刷新要能一并刷新它，
    //   放在区块里就得靠回调传出去，反而更绕。
    val followViewModel: HomeLiveFollowViewModel =
        diViewModel(key = HOME_LIVE_FOLLOW_VM_KEY) { HomeLiveFollowViewModel(it) }
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val list by viewModel.list.data.collectAsStateWithLifecycle()
    val listLoading by viewModel.list.loading.collectAsStateWithLifecycle()
    val listFinished by viewModel.list.finished.collectAsStateWithLifecycle()
    val listFail by viewModel.list.fail.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    // 筛选弹窗点「确定」→ applyTick 变化 → 强制刷一次。
    // ★为什么要 handledTick 这个"上次处理过的值"：LaunchedEffect 在**首次组合时也会跑一遍**，
    //   如果直接 refresh()，每次切分区新建本组件都会在 VM.init 已经拉过一次的基础上再拉一次
    //   （同一批房间请求两遍）。初值取当前 tick，等于"这一代的 tick 已经算处理过了"。
    var handledTick by remember { mutableStateOf(applyTick) }
    LaunchedEffect(applyTick) {
        if (applyTick != handledTick) {
            handledTick = applyTick
            viewModel.refresh()
        }
    }

    val listState = rememberLazyGridState()
    val emitter = localEmitter()
    LaunchedEffect(Unit) {
        emitter.collectAction<EmitterAction.DoubleClickTab> {
            if (it.tab == PageTabIds.HomeLive) {
                // 双击底部「直播」：在顶部就刷新，不在顶部先回到顶部（和首页其它 Tab 一致）。
                // 这里只刷房间列表 —— 分类树拉不到时本组件压根不会被组合（父级走的是另一个分支），
                // 所以不存在"顺手重试分类树"这种需求，多刷一次反而白拉十几 KB。
                // ★关注区块跟着一起刷（用户要的是"我刚关注的人开播了，双击/下拉就能看到"）：
                //   它是另一条独立请求，不刷它就会出现"列表是最新的、上面的关注还是十分钟前的"。
                if (listState.firstVisibleItemIndex == 0) {
                    viewModel.refresh()
                    followViewModel.refresh()
                } else {
                    listState.animateScrollToItem(0)
                }
            }
        }
    }

    SwipeToRefresh(
        modifier = modifier,
        refreshing = isRefreshing,
        onRefresh = {
            viewModel.refresh()
            // 下拉刷新 = "我就是要看最新的"：关注区块一起刷（用户验收步骤里那条
            // "下拉刷新能刷出新的开播"就靠这一行）
            followViewModel.refresh()
        },
    ) {
        LazyVerticalGrid(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 列数来自设置（设置 → 播放 → 直播设置 → 直播列表 → 每行卡片数）：
            //   0 = 自适应（默认，= 原来的行为）：按屏幕宽度铺，300dp 宽在手机上落 1 列、
            //       平板/横屏自动 2~3 列（和首页推荐/分区同款，HomeRegionContent.kt:669）；
            //   1~5 = 固定列数（和「番剧/影视设置」的每行卡片数同一个语义）
            columns = if (gridSpan == 0) GridCells.Adaptive(300.dp) else GridCells.Fixed(gridSpan),
            contentPadding = windowInsets.toPaddingValues(
                top = 0.dp,
            ),
        ) {
            // ★顶部「我的关注 · 正在直播」区块：**永远是网格的第 0 项**（全宽）。
            //   为什么放进网格里而不是固定在搜索框下面：
            //   ① 它该跟着内容一起滚（PiliPlus 的区块也是 CustomScrollView 里的一个 sliver，
            //      见 lib/pages/live/view.dart:63-71 的 `SliverMainAxisGroup`），
            //      固定住会永久占掉一屏高度、把下面的直播列表挤下去；
            //   ② 放进网格 = 天然懒加载：LazyGrid 只在它进入可视区时组合它，
            //      滚动到别处时它连组合都没有（更不会请求）。
            //   区块自己决定显不显示（未登录/没关注/没人开播 → 渲染 0 高度），
            //   所以这里不需要条件判断，也就不会出现"区块空了但网格留了个洞"。
            item(
                key = HOME_LIVE_FOLLOW_ITEM_KEY,
                span = { GridItemSpan(maxLineSpan) },
            ) {
                HomeLiveFollowBlock(
                    viewModel = followViewModel,
                    gridSpan = gridSpan,
                )
            }
            items(list, { it.roomid }) { item ->
                LiveRoomCard(
                    item = item,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    onClick = { viewModel.toLiveRoom(item) },
                    // 卡片上的 UP 名：进用户空间（uid 无效时卡片内部不会挂这个点击，见 LiveRoomCard）
                    onClickUpper = { viewModel.toUserSpace(item) },
                )
            }
            item(
                span = { GridItemSpan(maxLineSpan) }
            ) {
                ListStateBox(
                    modifier = Modifier.padding(bottom = windowInsets.bottomDp.dp),
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

/**
 * 直播房间卡片（**直播专用，不复用 VideoItemBox**）。
 *
 * 为什么不能复用点播的 `VideoItemBox`：
 *   1. 它是**横排**卡片（左边 140x85 封面 + 右边文字），封面比例写死 140:85；直播卡片是
 *      PiliPlus/斗鱼/虎牙那种**竖排**卡片：封面在上（16:9）、标题和 UP 主在下；
 *   2. 它封面右下角放的是"视频时长"，直播没有时长、要放的是**实时人气**；
 *   3. 它没有"直播中"角标的位置（时长角标占着右上/右下）。
 *   硬套只会把它改成"既能点播又能直播"的四不像，所以另写一个，和它的取舍是"宁可多一个卡片，
 *   不要把点播卡片的语义搅浑"。
 *
 * 卡片信息层次（照 PiliPlus 的 `LiveCardVApp`）：封面 16:9 / 左上"直播中"角标 /
 * 右下人气 / 标题 1 行 / 分区名 + UP 名 1 行。
 *
 * ★第六阶段：这张卡片现在被**本页的两条数据源共用**（「全部/分区」的 `getRoomList` 与新增的
 *   「推荐」流），首页里**只此一份**直播卡片 —— 推荐流那边靠实体映射
 *   （`LiveRecommendFeed.toRoomItem()`）把字段对齐成 [LiveRoomItem]，而不是在这里再复制一张
 *   "推荐专用卡片"（用户明确要求"别再复制第四份"）。改样式只需要改这一个函数。
 *   （两个直播搜索页各自有一份 private 的 `LiveRoomCard` 副本，那是它们的历史选择，不在本次范围。）
 *
 * ## 两个点击区（第四阶段补：用户问"点圈起来的 UP 名能不能进他的空间"）
 * ```
 * ┌───────────────────────┐
 * │直播中          （封面）│  ┐
 * │                1.2万人气│  ├─ 点这里 → 进直播间（onClick）
 * ├───────────────────────┤  ┘
 * │ 标题一行               │  ┘
 * │ 英雄联盟  Ⓤ 桂圆味蘑菇  │  ← 只有「Ⓤ 名字」这一块 → 进用户空间（onClickUpper）
 * └───────────────────────┘
 * ```
 * 实现上就是"父 clickable 里嵌一个子 clickable"：Compose 的指针事件从**内层往外**派发，
 * 子节点把 up 事件消费掉，父卡片的 clickable 收到的是"已被消费"的事件 → 不会再去开直播间。
 * 所以**不需要**自己算坐标、也不需要 `pointerInput` 拦截（那种写法反而会把长按/无障碍点坏）。
 *
 * ★2026-09-26：可见性从 `private` 放宽到 `internal` —— 「查看更多」进去的
 *   [LiveFollowPage]（同模块、`pages/live` 包）复用的就是**这一张**卡片。
 *   为什么要复用而不是那边再写一张：两个页面展示的是同一批字段（关注的人 + 他的直播间），
 *   各写一张的结果一定是"首页的卡片改了、列表页的还是旧的"。
 *   放宽可见性**不改任何行为**（同一个模块内可见，编译产物里的调用点一个没变）。
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun LiveRoomCard(
    item: LiveRoomItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onClickUpper: () -> Unit,
) {
    val context = LocalContext.current
    // 跟随全局「不显示封面」开关（设置→内容与评论），和 VideoItemBox/MiniVideoItemBox 保持一套行为：
    // 用户开了省流，直播这边还在哗哗下封面图，属于"设置时灵时不灵"。
    // 注意藏掉封面**不影响**角标和人气的显示 —— 卡片仍然能看出"这是直播、多少人看"。
    val dataStore = remember { SettingPreferences.run { context.dataStore } }
    val hideCover by remember {
        dataStore.data.map { it[SettingPreferences.VideoHideCover] ?: false }
    }.collectAsStateWithLifecycle(false)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "播放直播", onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 16:9：直播封面就是 16:9（实测封面尺寸 672x378），写死比例保证卡片不会因图长短变形
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val cover = item.coverUrl
            if (!hideCover && cover.isNotBlank()) {
                GlideImage(
                    // 和 VideoItemBox 用同一套 B 站图片处理后缀：672x378 正好是 16:9，省流量也清晰
                    model = UrlUtil.autoHttps(cover) + COVER_SIZE_SUFFIX,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = placeholder(R.drawable.bili_default_placeholder_img_tv),
                    failure = placeholder(R.drawable.bili_fail_placeholder_img_tv),
                )
            }
            LiveStatusBadge(
                status = item.live_status,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            )
            // 右下角：实时人气（没拿到就不显示这一块，不要显示"0人气"——那是在撒谎）
            if (item.online > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(
                            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${NumberUtil.converString(item.online)}人看过",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Text(
            text = item.title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 分区名（如"英雄联盟"）：直播列表里没有分区信息会不知道这是哪个区的内容
            if (item.area_name.isNotBlank()) {
                Text(
                    text = item.area_name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            // ★UP 名 = 进用户空间的入口：把「Ⓤ 图标 + 名字」包成**内层可点 Row**。
            //   为什么不是只给名字那个 Text 挂 clickable：整块（图标+名字）才是用户眼里"这个主播"的区域，
            //   只点文字的话手指稍微偏到图标上就会变成"进直播间"，跟用户意图相反。
            //   为什么包一层不会改排版：图标 14dp、名字前的 3dp 间距、外层 Row 的高度（由 Icon/Text 决定）
            //   全部照旧；weight(1f, fill = false) 从 Text 挪到内层 Row 上，长名字依旧按同一宽度打省略号。
            //   为什么 uid<=0 时**不挂** clickable：挂了就会"点名字既进不去空间、又把点击吃掉"
            //   （本来该进直播间的那一下没了）。不挂 → 事件自然落到外层卡片的 onClick，这就是"两者不互相吞点击"。
            val upperModifier = if (item.uid > 0) {
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClickLabel = "进入UP主空间", onClick = onClickUpper)
            } else {
                Modifier
            }
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(upperModifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = BilimiaoIcons.Common.Upper,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = item.uname,
                    modifier = Modifier.padding(start = 3.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * 开播状态角标。
 *
 * ★数据从哪来：`room/v1/Area/getRoomList` **实测不返回 `live_status`**（见 LiveRoomItem 的注释），
 *   此时实体里的默认值是 [LiveStatus.LIVE] —— 这个接口本来就只列正在直播的房间，默认值即事实。
 *   哪天后端把字段补上，这里不用改代码就会自动区分出"轮播/未开播"（用户要求：这类房间要有区分标记）。
 *   "数据里能拿到就做"这句就是这么落的：**能区分就一定区分，拿不到就绝不假装**。
 *
 * ★第六阶段：「推荐」流的条目走的是同一张卡片、同一个角标 —— 推荐流的实体映射
 *   （LiveRecommendFeed.kt 的 `toRoomItem()`）也是"接口给了就按它显示、没给才按在播兜底"，
 *   所以这个角标在两条数据源下的语义完全一致，不需要为推荐另写一套。
 */
@Composable
private fun LiveStatusBadge(
    status: Int,
    modifier: Modifier = Modifier,
) {
    val text = when (status) {
        LiveStatus.LIVE -> "直播中"
        LiveStatus.ROUND -> "轮播"
        else -> "未开播"
    }
    val color = when (status) {
        LiveStatus.LIVE -> LIVE_BADGE_RED
        else -> OFF_BADGE_GRAY
    }
    Text(
        text = text,
        modifier = modifier
            .background(color = color, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
    )
}

/** B 站图片处理后缀：672x378 = 16:9，和 VideoItemBox 用的是同一套 */
private const val COVER_SIZE_SUFFIX = "@672w_378h_1c_"

/** 「直播中」角标的红：固定色号而不是主题色 —— 这个红是"正在直播"的语义色，不该跟着主题变 */
private val LIVE_BADGE_RED = Color(0xFFFA3E3E)

/** 「轮播/未开播」角标的灰：弱化处理，一眼能和直播中区分开 */
private val OFF_BADGE_GRAY = Color(0xFF8A8A8E)

// ══════════════════════════════════════════════════════════════════════════
// 顶部「我的关注 · 正在直播」区块（第七阶段，2026-09-26）
//
// 用户原话："我也想抄他这个，在我的那个搜索下面或者上面，添加我已关注的、是否已开播，
// 开播就在这里显示。然后如果这个列表前面几个自动适配的满了的话，抄他的「查看更多」。
// 看他怎么写，我们怎么写，直接抄。……我关注的人到底在哪？大海里面找，要么搜，真的有点麻烦。"
//
// 抄的是 PiliPlus 首页「直播」Tab 顶部那一条：
//   标题行「我的关注  N人正在直播            查看更多 ›」 + 一行主播卡片
//   （lib/pages/live/view.dart:262-303 的 `_buildFollowList` / `_buildFollowBody`），
//   点卡片/头像 → 进他的直播间（同文件 :353 `PageUtils.toLiveRoom(item.roomid)`），
//   「查看更多 ›」→ 完整列表页（同文件 :297 `Get.to(const LiveFollowPage())`）。
//
// ★与 PiliPlus 唯一**故意不同**的一处：它 `totalCount == 0` 时**仍然画标题行**
//   （`_buildFollowList` 里标题是无条件渲染的）。用户明确要求"不要显示空标题"，
//   所以本区块的判据是"有在播的人 且 总数 > 0"，否则整块不渲染（见 HomeLiveFollowBlock）。
// ══════════════════════════════════════════════════════════════════════════

/**
 * 「我的关注 · 正在直播」区块的 ViewModel。
 *
 * ## 数据从哪来（一条请求，与下面的直播列表完全独立）
 * `xlive/app-interface/v2/index/feed` + `module_select=1` + `relation_page=1`
 * → `my_idol_v1` 卡片（[LiveFollowCard]）：服务端已经筛好"我关注的、正在直播的人"，
 * 顺带给一个 `extra_info.total_count`（"N人正在直播"的 N）。
 * 实测这条请求只有约 10 KB（同一个端点的推荐流是约 290 KB），
 * 详细出处与实测数据见 `LiveAPI.followFeed()` 与 `LiveFollowInfo.kt` 的类注释。
 *
 * ## 为什么单独一个 VM，而不是塞进 [LiveRoomListViewModel]
 * 两者生命周期不同：列表 VM 按"分区+排序"分裂成很多个实例（切一次建一个），
 * 而关注区块**只有一份**（跟分区无关）。塞在一起的话，切分区就会连带重新请求一次关注列表。
 * 反过来它也不该跟着列表 VM 一起被 key(...) 重建 —— 所以它在 [LiveRoomList] 里用**固定 key** 拿。
 *
 * ## 未登录 / 失败 / 空的处理（用户三条要求，这里一一对上）
 * - **未登录**：不发请求（`userStore.isLogin()` 为假直接清空并返回）→ 区块不显示；
 * - **失败静默**：接口报错/网络异常只写一条 DEBUG 日志，**不弹任何提示**，
 *   也**不清掉已经拿到的人**（一次网络抖动不该把用户已经看到的人抹掉）；
 * - **没人开播 / 没关注**：接口不给 `my_idol_v1`（或 list 为空）→ 区块不显示。
 *
 * ## 登录态变化会自己跟上
 * 冷启动时 [UserStore] 的登录信息可能比本 VM 晚到一步（首页先组合、登录态后恢复），
 * 只查一次就会出现"明明是登录的、区块却一直不出来"。所以这里**订阅**登录态，
 * 只在"登录态真的翻转"时重新拉一次（登出 → 立刻清空；登录 → 立刻补一次）——
 * 用 [lastLogin] 做闸门，状态再怎么变也不会打成循环请求。
 */
private class HomeLiveFollowViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val userStore: UserStore by instance()

    /** 正在直播的关注（已映射成首页卡片统一吃的 [LiveRoomItem]） */
    val items = MutableStateFlow<List<LiveRoomItem>>(emptyList())

    /** 「N人正在直播」的 N（服务端给的 `extra_info.total_count`） */
    val total = MutableStateFlow(0)

    // 在途请求 + 加载代数：刷新时取消旧请求，避免慢的旧批次覆盖新数据（同 LiveRoomListViewModel）
    private var loadJob: Job? = null
    private val loadEpoch = AtomicLong(0)

    /** 上一次发起请求时的登录态；null = 还从来没查过（首次一定会走一次 [load]） */
    private var lastLogin: Boolean? = null

    init {
        viewModelScope.launch {
            userStore.stateFlow.collect {
                val login = userStore.isLogin()
                if (login != lastLogin) load()
            }
        }
    }

    /** 下拉刷新 / 双击 Tab 都走这里（用户验收："下拉刷新能刷出新的开播"） */
    fun refresh() = load()

    private fun load() {
        val login = userStore.isLogin()
        lastLogin = login
        if (!login) {
            // 未登录：**一条请求都不发**（接口未登录时本来也不会给 my_idol_v1），
            // 同时清掉旧数据 —— 用户刚登出，区块必须立刻消失，不能留着上一个人的关注
            loadEpoch.incrementAndGet()
            loadJob?.cancel()
            items.value = emptyList()
            total.value = 0
            return
        }
        val epoch = loadEpoch.incrementAndGet()
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = LiveAPI()
                    .followFeed()
                    .awaitCall()
                    .json<ResultInfo<LiveRecommendFeed>>()
                if (loadEpoch.get() != epoch) return@launch
                if (!res.isSuccess) {
                    // 失败静默：只留一行 DEBUG 日志（用户侧"什么都没有发生"）
                    miaoLogger().d("首页关注直播拉取失败", "code" to res.code, "message" to res.message)
                    return@launch
                }
                val card = res.data?.followCard
                // roomid<=0 的脏条目直接丢：网格/点击都以 roomid 为准，留着只会点到空白
                val rooms = card?.rooms.orEmpty().filter { it.roomid > 0 }
                items.value = rooms
                total.value = card?.totalCount ?: rooms.size
            } catch (e: Exception) {
                // CancellationException 必须原样抛出（协程取消不是"失败"，不能吞）
                if (e is CancellationException) throw e
                if (loadEpoch.get() != epoch) return@launch
                miaoLogger().d("首页关注直播拉取异常", "message" to e.message)
            }
        }
    }
}

/**
 * 区块本体：标题行（我的关注 / N人正在直播 / 查看更多 ›）+ 一行直播卡片。
 *
 * ★**整块不显示**的判据就一句 `items.isEmpty() || total <= 0`：
 *   未登录、没有关注、一个人都没开播 —— 三种情况的最终结果都是"拿不到在播的人"，
 *   全部收敛到这一个判断上，不写第二套分支（也就不会出现"某种空态漏了、标题挂在那里"）。
 *
 * @param gridSpan 设置里"直播列表每行卡片数"（0 = 自适应）——
 *                 自适应时按 [FOLLOW_CARD_MIN_WIDTH] 算这一行放得下几个；
 *                 用户显式设过就听用户的（免得"主列表 3 列、关注区块 2 列"对不齐）。
 */
@Composable
private fun HomeLiveFollowBlock(
    viewModel: HomeLiveFollowViewModel,
    gridSpan: Int,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val total by viewModel.total.collectAsStateWithLifecycle()

    if (items.isEmpty() || total <= 0) return

    val context = LocalContext.current
    val pageNavigation: PageNavigation by rememberInstance()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            // 与主网格里卡片的外边距（horizontal 10dp）对齐：区块的卡片和下面的卡片左右对齐
            .padding(horizontal = 10.dp)
            .padding(top = 6.dp, bottom = 2.dp),
    ) {
        val columns = if (gridSpan > 0) {
            gridSpan
        } else {
            (maxWidth / FOLLOW_CARD_MIN_WIDTH).toInt().coerceIn(1, FOLLOW_MAX_COLUMNS)
        }
        val shown = items.take(columns)

        // ★「查看更多 ›」= "这一行装不下"时的出口，判据是**服务端说的人数**而不是我们拿到了几个：
        //   刚好放满（2 个在播、一行 2 个）或放不满（1 个在播）→ 不显示（用户原话：
        //   "前面几个自动适配的满了的话……就用「查看更多」"）。
        //   PiliPlus 同源判据：`itemCount: totalCount > listLength ? listLength + 1 : listLength`
        //   （lib/pages/live/view.dart:322-326，总数比列表长才多插一个"更多"箭头）。
        val hasMore = total > shown.size

        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "我的关注",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.width(6.dp))
                // "N"用主题色、"人正在直播"用弱化色 —— 照 PiliPlus 的字色分工
                // （lib/pages/live/view.dart:279-293：数字 colorScheme.primary、后缀 outline）
                Text(
                    text = total.toString(),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = "人正在直播",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (hasMore) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClickLabel = "查看更多关注直播") {
                                pageNavigation.navigate(LiveFollowPage())
                            }
                            .padding(start = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "查看更多",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(FOLLOW_CARD_GAP),
            ) {
                shown.forEach { item ->
                    LiveRoomCard(
                        item = item,
                        modifier = Modifier.weight(1f),
                        // ★点卡片 → **直接进他的直播间**（用户点名要的："我关注的 UP 主直播那里，
                        //   点进头像应该是直接进入他的直播间了吧？"）。
                        //   走 components/user/LiveBadgedAvatar.kt 里那个公开的 enterLiveRoom：
                        //   它进的是同一个原生播放页（LivePlayerActivity），
                        //   不在这里再抄第三份 setClassName 的 Intent。
                        onClick = { enterLiveRoom(context, item.roomid) },
                        // 卡片上那块「Ⓤ 名字」照旧进用户空间 —— 与下面主列表**同一张卡片的同一套交互**
                        // （卡片本身始终是进直播间，两者互不吞点击，见 LiveRoomCard 的注释）
                        onClickUpper = {
                            if (item.uid > 0) {
                                pageNavigation.navigate(UserSpacePage(id = item.uid.toString()))
                            }
                        },
                    )
                }
                // 没放满时把剩下的列**留白**（而不是让最后一张卡片横向拉伸铺满一整行）：
                // 这样"1 个人在播"和"2 个人在播"的卡片宽度完全一样，切换刷新时不会跳版
                repeat(columns - shown.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 首页「我的关注」区块的 VM key。★固定值（不含分区/排序），见 [LiveRoomList] 里的说明 */
private const val HOME_LIVE_FOLLOW_VM_KEY = "home-live-follow"

/** 区块在网格里的 item key。★与卡片用的 roomid 不会撞：那是 Long，这是 String */
private const val HOME_LIVE_FOLLOW_ITEM_KEY = "home-live-follow-block"

/**
 * 区块里一张卡片的**目标最小宽度**。
 *
 * ★为什么不直接跟主网格一样用 300dp：主网格一列就占满手机屏宽（自适应时手机就是 1 列），
 *   而这一块要的是"一屏能扫到几个正在直播的关注"（用户原话"前面几个自动适配的"）。
 *   150dp 在 360~430dp 的手机上正好落 **2 列 × 1 行 = 2 个**，
 *   平板/横屏自动变 3~4 个（上限见 [FOLLOW_MAX_COLUMNS]）。
 */
private val FOLLOW_CARD_MIN_WIDTH = 150.dp

/** 自适应时这一行最多几个：再多就不像"扫一眼"，而且「查看更多」会永远出不来 */
private const val FOLLOW_MAX_COLUMNS = 4

/** 区块里卡片之间的横向间距（主网格卡片自带 10dp 外边距，这里用同一个数量级） */
private val FOLLOW_CARD_GAP = 10.dp
