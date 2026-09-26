package cn.a10miaomiao.bilimiao.compose.pages.search.content

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.R
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.emitter.EmitterAction
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localEmitter
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.mypage.rememberMyMenu
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.live.LiveAPI
import com.a10miaomiao.bilimiao.comm.live.LiveSearchAPI
import com.a10miaomiao.bilimiao.comm.live.entity.LiveRoomInitInfo
import com.a10miaomiao.bilimiao.comm.live.entity.LiveSearchInfo
import com.a10miaomiao.bilimiao.comm.live.entity.LiveSearchRoomItem
import com.a10miaomiao.bilimiao.comm.mypage.MenuActions
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.mypage.SearchConfigInfo
import com.a10miaomiao.bilimiao.comm.mypage.myMenu
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.toast
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import java.util.concurrent.atomic.AtomicLong

/**
 * 全站搜索结果页里的「直播」Tab（本次新增：纯新增文件 + `SearchResultPage.kt` 挂两处）。
 *
 * ## 用户要的是什么
 * 「全站搜索能不能加一个直播的 tab？点一下就跳到那个直播间去。」
 * —— 所以本 Tab 只干两件事：**把正在直播的房间列出来** + **点卡片直接进直播间**。
 *
 * ## 第四阶段补：卡片上的 UP 名也能点（进该 UP 的用户空间）
 * 交互与首页直播 Tab **完全一致**（`HomeLiveContent.kt`：内层 Row 挂 clickable、父卡片仍是进直播间，
 * 两处不互相吞点击）。区别只在 uid 从哪来：首页列表接口自带 uid，而 `search_live` 实测不返回 uid
 * （见 [LiveSearchInfo] 的注释），所以这里走"**点击那一刻用 room_init 补查一次**"——
 * 一次点击一次请求、不预取整屏；拿不到就 toast「暂时拿不到 UP 信息」，
 * 既不静默失败，也不硬编任何 mid。
 *
 * ## 数据源为什么是 [LiveSearchAPI] 而不是全站搜索的 gRPC
 * 全站搜索走的是 `SearchGRPC.searchByType(type=…)`（`SearchByTypeContent.kt`），它的注释里
 * 虽然写着「直播：4」，但那条通道并没有真正可用的直播索引；而 `xlive/app-interface/v2/search_live`
 * 是已经实装并实测可用的那条路（`LiveSearchAPI.kt` 顶部有成对的实测记录：
 * 30 条/页、`total_room=1000`、`total_page=34`）。有能用的接口就用能用的，
 * 不去赌一个没验证过的 type。
 *
 * ## 为什么不直接复用 `pages/live/LiveSearchPage.kt`
 * 那个页面是**独立路由**（整页带自己的输入框和历史），而这里要的是"结果页里的一个分类 Tab"：
 * 关键字由搜索页给（不能让用户再输一遍）、没有历史面板、容器要和本页其它 Tab 一致。
 * 所以只把它的**取数机制**（分页 / 去重 / 过期响应丢弃）和**卡片**搬过来，页面壳子按
 * `SearchByTypeContent` 的写法来。
 * `LiveSearchPage.kt` 里的 `LiveRoomCard`/`hotText()` 都是 private，所以卡片在这里**复制一份**
 * —— 两份的视觉层次刻意保持一致（人气定位的修复也是两份同步改的，见 [LiveRoomCard] 的注释）。
 *
 * ## Tab 是怎么挂进搜索页的（原机制，读懂后照做）
 * `SearchResultPage.kt` 里：
 *   1. `private sealed class SearchResultPageTab(id, name)`，每个分类是一个 `data object`，
 *      自己实现 `PageContent(keyword, isActive)`；
 *   2. `SearchPageViewModel.tabs` 是一个**有序 list**，TabRow 直接 `forEachIndexed` 画它，
 *      `HorizontalPager` 用下标取 `tabs[index]` 渲染 —— 所以「加 Tab」= 加一个 object + 进 list；
 *   3. `id` 有两个用途：双击 Tab 刷新时通过 `EmitterAction.DoubleClickTab(tab = id)` 广播，
 *      以及各内容页拿它拼自己 ViewModel 的 key（见下）。
 *   4. 内容页只在 `isActive` 时调用 `PageConfig(...)`：整页共用同一个 PageConfig 槽位，
 *      不判 isActive 的话非当前 Tab 的标题/菜单会盖掉当前 Tab 的。
 *
 * ★[SearchLiveTabId] 为什么写在这个文件里、而不是加进 `common/constant/PageTabIds.kt`：
 *   本次改动范围被限定在 `pages/search/` 这个目录（外加新增文件），
 *   `PageTabIds.kt` 不在允许改的清单里。
 *   （★注意：注释里**不能**写出 `pages/search/` 加两个星号那样的通配写法 —— Kotlin 的块注释
 *     是**可嵌套**的，`斜杠+星号` 会在 KDoc 里再开一层注释，整份文件会被判成"注释未闭合"。）
 *   这个 id 只有"搜索页 + 本 Tab"两边用，放在本文件同样是一份唯一定义，不会出现两处字面量漂移。
 *
 * ## 历史为什么不掺在一起
 * 本 Tab 全程**不碰搜索历史表**（`SearchHistoryDB`）：关键字是从全站搜索页带下来的，
 * 用户没有在这里"新搜一个词"，写历史只会把直播搜索的历史和全站搜索的历史搅在一起
 * （历史分离由另一路负责）。所以这里既没有输入框、也不写库。
 */
internal const val SearchLiveTabId = "search.live"

/**
 * 直播 Tab 的 ViewModel。
 *
 * 取数机制照 `LiveSearchPageViewModel`（同一条接口、同样的坑），差异只有两处：
 * 关键字由构造参数给定（没有"输入框里的字"这一层），并且不涉及搜索历史。
 *
 * ★为什么必须自己持一份 [loadEpoch]（加载代数）：
 *   直播搜索是**页码分页**接口。"下拉刷新"和"加载更多"并发时，旧响应回来会把第 2 页
 *   追加到刚刷新好的第 1 页后面，列表内容/顺序全乱。刷新时把代数 +1，在途的旧请求
 *   回来发现代数变了就整批丢弃（`LiveSearchPage` 与 `HomeRegionContent` 都是这个办法）。
 */
private class SearchLiveContentViewModel(
    override val di: DI,
    private val keyword: String,
) : ViewModel(), DIAware {

    /** 拉起直播播放页要 Activity（`Intent.setClassName` + `startActivity`），走工程既有 DI 注入 */
    private val activity by instance<Activity>()

    /**
     * 页面导航：点卡片上的 UP 名 → 该 UP 的用户空间（见 [toUserSpace]）。
     *
     * ★为什么注入到 ViewModel、而不是在 Composable 里 `rememberInstance()`：
     *   和首页直播 Tab（`HomeLiveContent.kt` 的 `LiveRoomListViewModel`）是同一套写法 ——
     *   "点哪儿跳哪儿"全留在 VM 里，卡片那边只负责回调，两种跳转（直播间/用户空间）也放在一起看。
     *   `PageNavigation` 由 ComposeFragment 的 DI 提供（`bindSingleton { pageNavigation }`），
     *   页面级/首页 Tab 级的 `diViewModel` 都拿得到。
     */
    private val pageNavigation: PageNavigation by instance()

    /** 每页条数用接口常量 30（[LiveSearchAPI.PAGE_SIZE]），与直播搜索页一致 */
    val list = FlowPaginationInfo<LiveSearchRoomItem>(pageSize = LiveSearchAPI.PAGE_SIZE)
    val isRefreshing = MutableStateFlow(false)

    /** 命中总数（接口 `total_room`），只在列表头部显示一行"共 N 个直播间" */
    val totalRoom = MutableStateFlow(0)

    private val loadEpoch = AtomicLong(0)

    /** UP 名 → 用户空间的 uid 补查任务：在途时忽略重复点击（见 [toUserSpace]） */
    private var upperJob: Job? = null

    init {
        // 进 Tab 就出结果（关键字是搜索页给的，不需要用户再点一次"搜索"）。
        // 这里**不置 isRefreshing**：首次加载的进度由列表尾部的 ListStateBox 表达，
        // 顶上再转一个下拉刷新圈会让人以为"有人在刷新"（本页其它 Tab 也是这么处理的）。
        loadPage(1, loadEpoch.get())
    }

    /**
     * 往下翻一页 / 失败重试（`ListStateBox` 的「加载更多」「重试」按钮都调它）。
     */
    fun loadMore() {
        if (list.loading.value || list.finished.value) return
        // list.pageNum 只在**请求成功后**才推进，所以第 1 页就失败时它还停在初始值 1。
        // 这时若照 `pageNum + 1` 去重试，会跳过第 1 页直接请求第 2 页（结果从第 31 条开始）。
        // 列表还空着 = 一页都没成功 → 重试第 1 页；否则正常往后翻。
        val nextPage = if (list.data.value.isEmpty()) 1 else list.pageNum + 1
        loadPage(nextPage, loadEpoch.get())
    }

    /**
     * 下拉刷新 / 双击 Tab 刷新。
     * ★不清空 data：刷新时旧列表留在屏幕上，第 1 页回来整体替换
     * （清空会让列表白闪一下；见 [loadPage] 里 `pageNum <= 1` 走替换而不是追加）。
     */
    fun refresh() {
        val epoch = loadEpoch.incrementAndGet()
        list.fail.value = ""
        list.finished.value = false
        isRefreshing.value = true
        loadPage(1, epoch, force = true)
    }

    /**
     * 取第 [pageNum] 页。
     *
     * @param force 刷新路径必须能"插队"：即使上一页还在飞也要发第 1 页，否则下拉刷新会被
     *              下面那行 loading 守卫吃掉，`isRefreshing` 永远转圈。被插队的旧请求由
     *              [loadEpoch] 判废，不会写回列表。
     */
    private fun loadPage(pageNum: Int, epoch: Long, force: Boolean = false) {
        if (keyword.isBlank()) return
        // ★守卫同步置位（不是进协程再置位）：否则"加载更多"被连点两次会并发两页，
        //   同一批房间进列表两次 → LazyGrid 重复 key 直接抛 "Key was already used" 崩溃
        if (!force && list.loading.value) return
        list.loading.value = true
        list.fail.value = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = LiveSearchAPI()
                    .searchRoom(keyword, pageNum, list.pageSize)
                    .awaitCall()
                    .json<ResponseData<LiveSearchInfo>>()
                // 刷新把这一代请求作废了：结果已经过期，整批丢掉
                if (loadEpoch.get() != epoch) return@launch
                if (!res.isSuccess) {
                    // 接口自己报错（如 -352 风控）也走失败态，文案优先用服务端给的 message
                    list.fail.value = res.message.ifBlank { "请求失败（code=${res.code}）" }
                    return@launch
                }
                val room = res.data?.room
                val items = room?.list.orEmpty()
                totalRoom.value = room?.total_room ?: 0
                // 去重：翻页时接口可能给出重复房间，而 LazyGrid 的 key 用 roomid，
                // 重复 key 会抛 "Key was already used" 直接崩。
                // 显式写类型参数：写成 `if (…) mutableSetOf() else …` 会把空分支推成
                // MutableSet<out Long>，后面的 seen.add(roomid) 就编不过
                val seen = mutableSetOf<Long>()
                if (pageNum > 1) {
                    list.data.value.mapNotNullTo(seen) { it.roomid.takeIf { id -> id > 0 } }
                }
                // roomid<=0 的脏条目直接丢：它既打不开播放页，也没法当列表 key
                val fresh = items.filter { it.roomid > 0 && seen.add(it.roomid) }
                list.pageNum = pageNum
                list.data.value = if (pageNum <= 1) fresh else list.data.value + fresh
                // 到底了的三个判据：本页没有新房间 / 本页不满一页 / 总页数已经到页码
                // （实测超出范围时接口回 code=0 + 空 list，所以不能靠 code 判断"到底"）
                list.finished.value = fresh.isEmpty() ||
                    items.size < list.pageSize ||
                    (room?.total_page ?: 0) in 1..pageNum
            } catch (e: Exception) {
                // 协程取消不是"失败"：它由组合被销毁触发，报给用户只会看到一个假错误
                if (e is CancellationException) throw e
                e.printStackTrace()
                if (loadEpoch.get() == epoch) {
                    list.fail.value = e.message ?: e.toString()
                }
            } finally {
                // 只有最新一代才复位加载标志：被插队/被丢弃的旧批次不得把新批次的 loading 抹掉
                if (loadEpoch.get() == epoch) {
                    list.loading.value = false
                    isRefreshing.value = false
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 点卡片 → 直播间
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 打开直播播放页（`LivePlayerActivity`，第二阶段 A 路做的原生播放页）。
     *
     * ★为什么类名/extra key 写成字符串字面量、不直接引用 `LivePlayerActivity`：
     *   依赖方向是 `app → bilimiao-compose`，compose 模块**不能反向 import app 模块的类**
     *   （成环，编译不过）。`bilimiao-cover` 的 `CoverViewModel.kt`、
     *   `pages/home/content/HomeLiveContent.kt` 是同一个处境、同一个办法 —— 三处字符串是
     *   **同一份约定**：`LivePlayerActivity.EXTRA_ROOM_ID == "roomId"`，改 extra 名要一起改。
     */
    fun toLiveRoom(roomId: Long) {
        // 脏数据（roomid<=0）在搜索结果里真的出现过，直接不响应比让播放页崩了强
        if (roomId <= 0) return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(activity, LIVE_PLAYER_ACTIVITY)
            // 传真实房间号字符串：播放页自己还会 room_init 一次（短号/真实号都收）
            putExtra(EXTRA_ROOM_ID, roomId.toString())
        }
        // 拉不起来（组件被改名/被系统拦截）就退回网页直播间，别让用户"点了没反应"
        runCatching { activity.startActivity(intent) }.onFailure {
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://live.bilibili.com/$roomId"))
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 点卡片上的 UP 名 → 该 UP 的用户空间
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 点卡片上的 UP 名 → **该 UP 主的用户空间**（点卡片其它区域仍是进直播间，两者互不干扰，
     * 点击区域的划分见 [LiveRoomCard]）。
     *
     * ★为什么这里要"点击时补查一次 uid"，而首页直播 Tab 不用（`HomeLiveContent.kt` 的 `toUserSpace`）：
     *   `search_live` 实测**不返回 uid** —— 条目字段并集只有
     *   cover/face/link/name/online/roomid/title/watched_show（见 [LiveSearchInfo] 的注释），
     *   搜索结果里根本没有 mid 可用。所以只能拿房间号去 `room/v1/Room/room_init` 换：
     *   响应里 `data.uid` 就是主播 mid，实体侧 [LiveRoomInitInfo.uid] 早已建模，**不需要改接口层**。
     *
     * ★为什么只在**点击那一刻**查、不预取整屏：一屏 30 条就是 30 次额外请求，而绝大多数卡片
     *   用户根本不会去点 UP 名；B 站风控对直播接口的高频很敏感（[LiveAPI] 注释里记着"分区直播
     *   列表都会回 -352"）。一次点击换一次请求，代价最小。
     *
     * ★为什么失败要 toast（首页那边是静默的）：首页的 uid 是列表自带的，拿不到就不挂点击区，
     *   那一下自然落到"进直播间"（两边不互相吞点击）；而这里 uid 要点击后才知道，
     *   点击区必须**点击前**就挂好 —— 如果查不到又什么都不说，用户看到的就是"点了名字没反应"，
     *   分不清是 App 坏了还是网慢。所以明确告诉他这一次没拿到，他可以再点一次（重试）
     *   或者点卡片进直播间。**绝不硬编任何 mid 兜底**。
     */
    fun toUserSpace(roomId: Long) {
        // 脏房间号（列表里已经滤过 roomid<=0 的条目，这里是"崩不起"的防御）：
        // 不用发请求，直接明确报错，别让它变成一次注定失败的请求
        if (roomId <= 0) {
            toast("暂时拿不到 UP 信息")
            return
        }
        // 连点保护：上一次补查还在飞就忽略这一次。否则连点两下会发两次 room_init，
        // 回来再把用户空间往返回栈压两层（"只在点击那一刻请求一次"就是这个意思）
        if (upperJob?.isActive == true) return
        upperJob = viewModelScope.launch {
            // ★线程划分：只有这一次网络+解析放进 IO（`awaitCall()` 是挂起的，但 `json()` 解析是
            //   阻塞的）；导航留在协程默认的 Main —— `NavController.navigate` 是主线程 API，
            //   在 IO 线程调会抛 "Method addObserver must be called on the main thread" 那一类错。
            //   另外这里不需要"退出页面后别再导航"的额外判断：页面销毁会清掉 ViewModel、
            //   连带取消 viewModelScope，这次补查连同后面的导航一起作废，不会往已退出的栈里压页。
            val uid = withContext(Dispatchers.IO) { queryUpperUid(roomId) }
            if (uid <= 0) {
                toast("暂时拿不到 UP 信息")
                return@launch
            }
            pageNavigation.navigate(UserSpacePage(id = uid.toString()))
        }
    }

    /**
     * `room/v1/Room/room_init` 的 uid 补查。**任何**失败都归一成 0，由 [toUserSpace] 统一 toast。
     *
     * 为什么不把异常抛给调用方：对用户来说"接口回错 / 网络断了 / uid 本来就是 0"是同一件事
     * （这一次没拿到 UP 信息），抛出去只会让调用点多一层没有信息量的 try。
     * 但**协程取消**必须原样抛 —— 那是页面销毁时的正常取消，不是失败，吞掉它等于把
     * "取消"误报成"拿不到 UP 信息"（`if (e is CancellationException) throw e` 是全工程同一套写法）。
     */
    private suspend fun queryUpperUid(roomId: Long): Long = try {
        val res = LiveAPI()
            .roomInit(roomId.toString())
            .awaitCall()
            .json<ResponseData<LiveRoomInitInfo>>()
        // code != 0（风控/房间不存在）时 data 可能是 null：`res.data?.uid` 一起挡掉
        if (res.isSuccess) res.data?.uid ?: 0 else 0
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
        0
    }

    companion object {
        /** 直播播放页全限定类名（约定，见 [toLiveRoom] 注释） */
        private const val LIVE_PLAYER_ACTIVITY = "com.a10miaomiao.bilimiao.LivePlayerActivity"

        /** 与 `LivePlayerActivity.EXTRA_ROOM_ID` 同一个 key（约定） */
        private const val EXTRA_ROOM_ID = "roomId"
    }
}

/**
 * 本 Tab 的 PageConfig（标题栏 + 右上角菜单）。
 *
 * 为什么必须只在 `isActive` 时调用：`PageConfig` 是**整页共用**的配置槽
 * （`PageConfigState` 同一时刻只认最后加入的那份配置），而 HorizontalPager 会把相邻 Tab
 * 也组合出来 —— 不判 `isActive`，非当前 Tab 的标题/菜单就会盖掉当前 Tab 的。
 * 这也是 `SearchAllContent`/`SearchByTypeContent` 里那句 `if (isActive)` 的原因。
 */
@Composable
private fun SearchLiveContentConfig(
    keyword: String,
) {
    val pageConfigId = PageConfig(
        // 标题与其它 Tab 保持一致（含换行分段），切 Tab 时标题栏不跳
        title = "搜索\n-\n$keyword",
        menu = rememberMyMenu {
            // 与所有兄弟 Tab 一样提供「继续搜索」：action = MenuActions.search 由 MainActivity
            // 拦截并弹搜索框（`MainActivity.initAppBar`），这里不需要自己的回调
            myItem {
                key = MenuKeys.search
                action = MenuActions.search
                title = "继续搜索"
                iconFileName = "ic_search_gray"
            }
        },
        // 让"继续搜索"弹窗预填当前关键字（搜索模式仍是全站搜索）
        search = SearchConfigInfo(
            keyword = keyword
        )
    )
    // 没有自定义菜单回调（唯一的菜单项是框架自己处理的 action），注册空监听即可
    PageListener(pageConfigId)
}

/**
 * 「直播」Tab 的内容。签名与 [SearchByTypeContent]/[SearchAllContent] 对齐
 * （`keyword` + `isActive`），由 `SearchResultPage` 的 `SearchResultPageTab.Live` 调起。
 */
@Composable
internal fun SearchLiveContent(
    keyword: String,
    isActive: Boolean,
) {
    // ViewModel 的 key 带上 TabId 与关键字：同一页不同 Tab / 不同关键字各持一份列表状态。
    // （key 里带 keyword 是照 SearchByTypeContent 的写法，避免"换了词旧 VM 的列表还在"）
    val viewModel = diViewModel(
        key = SearchLiveTabId + keyword
    ) {
        SearchLiveContentViewModel(it, keyword)
    }
    if (isActive) {
        SearchLiveContentConfig(keyword)
    }

    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val list by viewModel.list.data.collectAsStateWithLifecycle()
    val listLoading by viewModel.list.loading.collectAsStateWithLifecycle()
    val listFinished by viewModel.list.finished.collectAsStateWithLifecycle()
    val listFail by viewModel.list.fail.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val totalRoom by viewModel.totalRoom.collectAsStateWithLifecycle()

    val listState = rememberLazyGridState()
    val emitter = localEmitter()
    LaunchedEffect(Unit) {
        // 双击本 Tab：已经在顶部就刷新，不在顶部先回到顶部（与兄弟 Tab 的手感一致）
        emitter.collectAction<EmitterAction.DoubleClickTab> {
            if (it.tab == SearchLiveTabId) {
                if (listState.firstVisibleItemIndex == 0) {
                    viewModel.refresh()
                } else {
                    listState.animateScrollToItem(0)
                }
            }
        }
    }

    SwipeToRefresh(
        refreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
    ) {
        LazyVerticalGrid(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 自适应列数：与全站搜索其它 Tab 完全同款（手机 1 列，平板/横屏自动多列）
            columns = GridCells.Adaptive(300.dp),
            contentPadding = windowInsets.toPaddingValues(
                top = 0.dp,
            )
        ) {
            if (totalRoom > 0 && list.isNotEmpty()) {
                item(
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    // 命中数只在这里报一次（接口 total_room 会被截到 1000，当"约数"看）
                    Text(
                        text = "共 $totalRoom 个直播间",
                        modifier = Modifier.padding(
                            start = 12.dp,
                            top = 6.dp,
                            bottom = 2.dp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            items(
                items = list,
                // key 用 roomid：上面已按 roomid 去过重（重复 key 会让 LazyGrid 直接崩）
                key = { it.roomid },
            ) { item ->
                LiveRoomCard(
                    item = item,
                    onClick = { viewModel.toLiveRoom(item.roomid) },
                    // 卡片上的 UP 名：进用户空间。★uid 不在搜索结果里，是点击那一刻才补查的
                    // （查不到会 toast，不会静默失败 —— 见 SearchLiveContentViewModel.toUserSpace）
                    onClickUpper = { viewModel.toUserSpace(item.roomid) },
                )
            }
            item(
                span = { GridItemSpan(maxLineSpan) },
            ) {
                if (list.isEmpty() && !listLoading &&
                    listFail.isBlank() && listFinished
                ) {
                    // 真的 0 条：ListStateBox 只会说"空空如也"，在搜索场景太含糊 ——
                    // 明确告诉他"这个词没搜到直播间"，他才知道要换词
                    // （文案与直播搜索页同一套，两处对同一个接口的 0 结果解释一致）
                    LiveSearchEmptyHint(keyword)
                } else {
                    // 加载中 / 到底了 / 失败（带重试按钮）/ 手动加载更多，全部交给本页通用组件
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

/**
 * 一张直播间卡片（**复制**自 `pages/live/LiveSearchPage.kt` 的 private `LiveRoomCard`）。
 *
 * 为什么复制而不是共用：那个文件属于另一路交付物，里面的 `LiveRoomCard`/`hotText()` 都是 private ——
 * 要么改它，要么复制一份（本次选择）。
 * ★两份刻意保持一字不差，包括"人气叠在封面右下角"这一处修复：`LiveSearchPage.kt` 里那份
 *   **同步改了同一个位置**（用户说的"在直播页搜索"走的正是那个路由 —— 首页直播 Tab 的搜索框
 *   `HomeLiveContent.kt` 的 LiveSearchEntry → `LiveSearchPage`）。只改一份 = 漏一半。
 *   将来若要统一，应抽成公共组件（已写进交付报告的风险项）。
 *
 * 信息层次（用户点名要的四样：封面 / 标题 / UP / 人气，外加"直播中"角标）：
 * ```
 * ┌───────────────┐
 * │直播中          │  ← 角标：接口语义就是"搜出来的都是正在直播的"
 * │   （封面）      │  ┐
 * │ 主播名    [人气]│  ├ 底部渐变条 + 右下角人气胶囊
 * ├───────────────┤  ┘
 * │ 标题（最多两行） │
 * └───────────────┘
 * ```
 *
 * ★人气的位置为什么是"叠在封面右下角"、而不是跟在主播名后面（用户实测反馈的修复点）：
 *   原来人气是底部渐变条 Row 里的第三个孩子（名字 / Spacer / 人气），位置由名字宽度决定；
 *   现在人气是**封面 Box 里独立的一颗角标**（`align(BottomEnd)` + 6dp），锚在封面右下角，
 *   名字再长也推不动它。渐变条那一行只留名字，并且用"名字 fill = false + Spacer weight(1f)"
 *   给右下角**预留半行**，保证长名字的省略号不会伸到人气胶囊底下（取舍与推导见 [LiveRoomCard]）。
 *
 * ## 两个点击区（与首页直播 Tab 的 `HomeLiveContent.kt` 同一套写法）
 * ```
 * ┌───────────────────────┐
 * │直播中          （封面）│  ┐
 * │  主播名        [人气]  │  ├─ 点这里 → 进直播间（onClick）
 * ├───────────────────────┤  ┘
 * │ 标题                   │  ┘
 * └───────────────────────┘
 *      ↑ 只有渐变条左边那块「主播名」→ 进用户空间（onClickUpper）
 * ```
 * 实现就是"父 clickable 里嵌一个子 clickable"：Compose 的指针事件从内层往外派发，
 * 内层（名字那块的 Row）把事件消费掉，外层卡片的 clickable 收到的是已消费的事件 →
 * 不会再去开直播间。所以**不需要**自己算坐标、也不用 `pointerInput` 拦截
 * （那种写法反而会把长按/无障碍点坏）。
 *
 * ★与首页那张卡片的唯一差异：这里的名字**永远**挂着点击区，不管 uid 是否已知 ——
 *   搜索接口不返回 uid，点击前根本无从判断这次能不能拿到（首页那边 uid 是列表自带的，
 *   uid<=0 时干脆不挂，让点击落回"进直播间"）。拿不到 uid 的情况由点击时那次补查
 *   用 toast 明确告知，绝不静默失败（见 [SearchLiveContentViewModel.toUserSpace]）。
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun LiveRoomCard(
    item: LiveSearchRoomItem,
    onClick: () -> Unit,
    onClickUpper: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            GlideImage(
                // 直播封面本来就是 16:9，带上缩略图后缀省流量也更快
                model = UrlUtil.autoHttps(item.cover) + "@480w_270h_1c_",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = placeholder(R.drawable.bili_default_placeholder_img_tv),
                failure = placeholder(R.drawable.bili_fail_placeholder_img_tv),
            )
            Text(
                text = "直播中",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(LIVE_BADGE_COLOR, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        // 渐变条：封面有亮有暗，纯色条会在亮封面上"糊掉"、看不清字
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                        )
                    )
                    .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ★UP 名 = 进用户空间的入口：把左边这块「主播名」包成**内层可点 Row**。
                //   为什么包一层而不是直接给 Text 挂 clickable：两者点击范围其实一样大，
                //   但这样做和首页直播卡片（HomeLiveContent.kt:889-909）是**同一份写法** ——
                //   将来那边把「Ⓤ 图标 + 名字」一起纳入点击区时，两份卡片不会长歪。
                //   fill = false：内层 Row 的宽度仍由名字文字决定、不撑满整行 ——
                //   点名字右边的空白依旧算点卡片（进直播间），长名字按下面那条"半行上限"打省略号。
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClickLabel = "进入UP主空间", onClick = onClickUpper),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.name, // UP 主（主播名）
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // ★这颗 Spacer 是**给右下角人气胶囊预留的右半行**，不是排版装饰（删掉人气就会被名字推着走）：
                //   Row 里两个带 weight 的孩子各拿 `weightUnitSpace × weight` —— 名字那块 fill = false，
                //   实际宽度 = min(名字宽度, 半行)；这颗 fill = true 的 Spacer 则**永远**吃满另外半行。
                //   ★关键：fill = false 省下来的空间**不会**重新分给兄弟（Row 的测量实现里，
                //     带 weight 的孩子各自按 weightUnitSpace × weight 定量测，没有二次分配），
                //     所以"名字 + 这颗 Spacer"必须成对存在：只留一颗，半行上限就没了。
                //   结果：名字再长，右半行也不会被挤掉 —— 右下角那颗胶囊的位置与名字长度完全无关。
                Spacer(modifier = Modifier.weight(1f))
            }
            // 人气：**叠在封面右下角**（同首页直播 Tab 的 LiveRoomCard，HomeLiveContent.kt:1088-1107）。
            // ★为什么从"行内贴右"改成"叠封面右下角"：用户要的是"像直播 Tab 那样固定住"，
            //   而直播 Tab 的人气就在封面右下角（半透明胶囊）。锚点从"名字行的行尾"换成"封面右下角"后，
            //   人气的位置只由卡片宽度决定，跟主播名一个字都不相干。
            //   文案仍旧走 [hotText]（优先服务端的 watched_show.text_large），只改位置、不改文案来源。
            // 空文案（既没有 text_large、online 也是 0）不画这颗胶囊：半透明底的空壳比什么都不画更像 bug。
            val hotText = item.hotText()
            if (hotText.isNotBlank()) {
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
                        text = hotText, // 人气
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
                .padding(start = 4.dp, end = 4.dp, top = 6.dp),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 卡片右下角的人气文案：优先用接口给的 `watched_show.text_large`，没有再按 `online` 自己拼。
 * （实测有的房间 `online` 是"当前人气"、有的房间 `text_large` 才是正确说法"9.9万人看过"，
 *   只有 `text_large` 是服务端按 `watched_show.switch` 选好的成品文案。）
 */
private fun LiveSearchRoomItem.hotText(): String {
    val text = watched_show?.text_large.orEmpty()
    if (text.isNotBlank()) return text
    return if (online > 0) "${NumberUtil.converString(online)}人看过" else ""
}

/** 搜到 0 条时的提示：比"空空如也"多告诉用户两件事 —— 搜的是什么词、下一步能干什么 */
@Composable
private fun LiveSearchEmptyHint(keyword: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "没有搜到「$keyword」相关的直播间",
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "换个关键字，或试试主播的名字",
            modifier = Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** 「直播中」角标底色：B 站直播的粉（比主题色更能一眼认出"这是直播"） */
private val LIVE_BADGE_COLOR = Color(0xFFFB7299)
