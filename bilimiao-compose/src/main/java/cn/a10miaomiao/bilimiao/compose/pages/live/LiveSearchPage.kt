package cn.a10miaomiao.bilimiao.compose.pages.live

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.R
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import com.a10miaomiao.bilimiao.comm.db.LiveSearchHistoryDB
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.live.LiveAPI
import com.a10miaomiao.bilimiao.comm.live.LiveSearchAPI
import com.a10miaomiao.bilimiao.comm.live.entity.LiveRoomInitInfo
import com.a10miaomiao.bilimiao.comm.live.entity.LiveSearchInfo
import com.a10miaomiao.bilimiao.comm.live.entity.LiveSearchRoomItem
import com.a10miaomiao.bilimiao.comm.mypage.SearchConfigInfo
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import java.util.concurrent.atomic.AtomicLong

/**
 * 直播搜索页（第三阶段 B 路）。
 *
 * ## 这个页面是干什么的
 * 用户"直播也要有自己的搜索"：从直播入口进来搜**正在直播的直播间**，
 * 点卡片直接进直播播放页（`com.a10miaomiao.bilimiao.LivePlayerActivity`，Intent 启动）。
 *
 * ## 第四阶段补：卡片上的 UP 名也能点（进该 UP 的用户空间）
 * 交互与首页直播 Tab 完全一致（`HomeLiveContent.kt`：内层 Row 挂 clickable、父卡片仍是进直播间，
 * 两处不互相吞点击）。区别只在 uid 从哪来：首页列表接口自带 uid，而 `search_live` 实测不返回 uid
 * （见 [LiveSearchInfo] 的注释），所以这里走"**点击那一刻用 room_init 补查一次**"——
 * 一次点击一次请求、不预取整屏；拿不到就 toast「暂时拿不到 UP 信息」，
 * 既不静默失败，也不硬编任何 mid。
 *
 * ## 结构照谁抄的
 * - **页面骨架**：`pages/search/SearchResultPage.kt` + `pages/search/content/SearchByTypeContent.kt`
 *   （顶部输入框、搜索历史、结果网格、分页/刷新/失败重试），只把数据源从"全站搜索 gRPC"
 *   换成"直播搜索 `search_live`"。
 * - **输入框 + 历史交互**：`components/start/SearchInputInline.kt`（取值/清空/提交的写法）。
 * - **分页/下拉刷新/到底/重试**：`pages/home/content/HomeRegionContent.kt`（[FlowPaginationInfo]
 *   + [ListStateBox] + [SwipeToRefresh] + 加载代数丢弃过期响应）。
 * - **房间卡片的信息层次**：PiliPlus `lib/pages/live_search/widgets/live_search_room.dart`
 *   —— 封面上一行渐变条：左边主播名、右边人气文案（`watched_show.text_large`），下面标题两行。
 *   我们只多加了用户点名要的「直播中」角标（PiliPlus 靠进详情页才知道开播状态）。
 *
 * ## 搜索历史：和全站搜索**分开存**（第四阶段改动）
 * 本页历史落在自己的库/表 `LiveSearchHistory_db` / `LiveSearchHistory`（[LiveSearchHistoryDB]），
 * 不再往全站搜索的 `PreventKeyWord_db2` 里写 —— 用户要求"两者不联动、各自独立"。
 * 输入框里点一下就走**直播搜索**接口（`search_live`），底栏那个搜索入口走的是全站搜索，互不影响。
 *
 * ## ★类名与构造签名是**约定锁死**的
 * A 路的直播浏览页会直接 `LiveSearchPage(keyword)` 把我推出来，构造签名的默认参数
 * （`keyword: String = ""`）不能改 —— 空关键字进来自动聚焦输入框并显示搜索历史，
 * 带关键字进来直接出结果（且**不弹键盘**，键盘会盖掉半屏结果）。
 *
 * ## 为什么用 ComposePage 就**必须**在 BilimiaoPageRoute.kt 里注册
 * 导航框架按 KClass 反查路由表，漏注册的表现是"点进去直接崩"（不是回退、不是空白）。
 * 本次注册已与页面文件同一次改动完成。
 */
@Serializable
class LiveSearchPage(
    val keyword: String = "",
) : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: LiveSearchPageViewModel = diViewModel(key = "live-search") {
            LiveSearchPageViewModel(it, keyword)
        }
        LiveSearchPageContent(viewModel)
    }
}

/**
 * 直播搜索的 ViewModel。
 *
 * ★为什么"打字不请求、只有回车/点搜索才请求"：
 *   直播搜索接口是 **APP 签名接口**（appkey+ts+sign，见 [LiveSearchAPI]），
 *   按关键字联想那种"边打边搜"会把这个接口打成高频请求（B 站风控对高频很敏感，
 *   `LiveAPI` 注释里就记着"分区直播列表都会回 -352"）。PiliPlus 的直播搜索也是提交式，
 *   这里跟它一致；要联想词的话应该走专门的 suggest 接口，不是这个接口。
 */
private class LiveSearchPageViewModel(
    override val di: DI,
    initialKeyword: String,
) : ViewModel(), DIAware {

    private val activity by instance<Activity>()
    private val context by instance<Context>()

    /**
     * 页面导航：点卡片上的 UP 名 → 该 UP 的用户空间（见 [toUserSpace]）。
     *
     * ★为什么注入到 ViewModel、而不是在 Composable 里 `rememberInstance()`：
     *   和首页直播 Tab（`HomeLiveContent.kt` 的 `LiveRoomListViewModel`）、以及全站搜索的
     *   直播 Tab（`SearchLiveContent.kt`）保持同一套写法 —— "点哪儿跳哪儿"都收在 VM 里，
     *   卡片那边只负责回调，进直播间和进用户空间两种跳转也放在一起看。
     */
    private val pageNavigation: PageNavigation by instance()

    /**
     * 搜索历史走**直播自己的**库表（[LiveSearchHistoryDB]：`LiveSearchHistory_db` / `LiveSearchHistory`）。
     *
     * ★为什么不再复用全站搜索的 `PreventKeyWord_db2`（用户明确要求）：
     *   直播搜的是主播名/直播间标题，全站搜的是视频/番剧关键词，混一张表两边都会变脏
     *   （搜完直播回到全站搜索，历史里全是主播名，"最近搜过"就没参考价值了）。
     *   两个库文件名、表名都不同 = 物理隔离，谁也不会写脏谁；
     *   全站搜索的历史逻辑一行没动（`SearchInputViewModel` 照旧用 `SearchHistoryDB`）。
     */
    private val searchHistoryDB =
        LiveSearchHistoryDB(context, LiveSearchHistoryDB.DB_NAME, null, 1)

    /** 输入框里的字。改它**只**改输入框，不发请求（见类注释） */
    val searchText = MutableStateFlow(initialKeyword)

    /** 已提交的关键字；空 = 还没搜过（此时展示搜索历史/引导语） */
    val keyword = MutableStateFlow("")

    val list = FlowPaginationInfo<LiveSearchRoomItem>(pageSize = LiveSearchAPI.PAGE_SIZE)
    val isRefreshing = MutableStateFlow(false)
    val historyList = MutableStateFlow<List<String>>(emptyList())

    /** 命中总数（接口给 `total_room`），只在结果头部显示一行"共 N 个直播间" */
    val totalRoom = MutableStateFlow(0)

    /**
     * 加载代数。刷新时 +1，在途的旧请求回来发现代数变了就**整个丢弃**。
     * 没有它会出现：刷新拿到第 1 页之后，上一批"加载更多"的响应才回来，
     * 把第 2 页追加到刚刷新好的列表上 —— 列表顺序/内容全乱。
     */
    private val loadEpoch = AtomicLong(0)

    /** UP 名 → 用户空间的 uid 补查任务：在途时忽略重复点击（见 [toUserSpace]） */
    private var upperJob: Job? = null

    init {
        // 历史读库放 IO：历史攒起来后主线程读 SQLite 会拖慢页面首帧
        // （SearchInputViewModel 里也为同一个原因把读历史挪到了 IO）
        viewModelScope.launch(Dispatchers.IO) { reloadHistory() }
        // 带关键字进来（浏览页点搜索、深链）就直接出结果，不用用户再点一次
        if (initialKeyword.isNotBlank()) {
            keyword.value = initialKeyword
            loadPage(1, loadEpoch.get())
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 搜索历史
    // ══════════════════════════════════════════════════════════════════════

    private fun reloadHistory() {
        historyList.value = runCatching { searchHistoryDB.queryAllHistory() }
            .getOrDefault(arrayListOf())
            .take(HISTORY_MAX)
    }

    private fun writeHistory(block: () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        // 读写在 IO：SQLite 放主线程会卡输入框（SearchInputViewModel 里也专门修过这个）
        runCatching(block)
        reloadHistory()
    }

    fun deleteHistory(text: String) = writeHistory {
        searchHistoryDB.deleteHistory(text)
    }

    fun clearHistory() = writeHistory {
        searchHistoryDB.deleteAllHistory()
    }

    // ══════════════════════════════════════════════════════════════════════
    // 交互
    // ══════════════════════════════════════════════════════════════════════

    fun updateSearchText(value: String) {
        searchText.value = value
    }

    /**
     * 提交搜索。回车 / 点「搜索」/ 点历史词 / AppBar 的「继续搜索」都走这里。
     */
    fun submit(text: String = searchText.value) {
        val kw = text.trim()
        if (kw.isEmpty()) {
            toast("请输入关键字")
            return
        }
        // 输入框回填 trim 后的词：用户看到的就是"真正搜的词"
        searchText.value = kw
        val sameKeyword = kw == keyword.value
        if (!sameKeyword) {
            // 换词了：旧结果跟新词无关，先清掉（同词重复搜则保留，见 [refresh] 的注释）
            list.reset()
        }
        keyword.value = kw
        if (sameKeyword && list.data.value.isNotEmpty()) {
            // 同一个词、结果还在：不重复打接口
            return
        }
        writeHistory {
            // 先删后插 = 最近搜的排最前（与 SearchInputViewModel.addSearchHistory 同一套）
            searchHistoryDB.deleteHistory(kw)
            searchHistoryDB.insertHistory(kw)
        }
        refresh()
    }

    /**
     * 下拉刷新 / 失败重试。★**不清空 data**：下拉刷新时旧列表留在屏幕上（清空会让列表
     * 白闪一下），第 1 页回来时整体替换（[loadPage] 里 pageNum<=1 走替换而不是追加）。
     */
    fun refresh() {
        val epoch = loadEpoch.incrementAndGet()
        list.fail.value = ""
        list.finished.value = false
        isRefreshing.value = true
        loadPage(1, epoch, force = true)
    }

    fun loadMore() {
        if (list.loading.value || list.finished.value) return
        loadPage(list.pageNum + 1, loadEpoch.get())
    }

    /**
     * 取第 [pageNum] 页。
     *
     * @param force 刷新路径要能"插队"：即使上一页还在飞，也必须发第 1 页
     *              （否则下拉刷新会被这行守卫吃掉，`isRefreshing` 永远转圈）。
     *              被插队的旧请求由 [loadEpoch] 判废，不会写回列表。
     */
    private fun loadPage(pageNum: Int, epoch: Long, force: Boolean = false) {
        val kw = keyword.value
        if (kw.isBlank()) return
        // ★守卫同步置位（不是进协程再置位）：否则"加载更多"被连点两次会并发两页，
        //   同一批房间进列表两次 → LazyGrid 重复 key 直接抛 "Key was already used" 崩溃
        if (!force && list.loading.value) return
        list.loading.value = true
        list.fail.value = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = LiveSearchAPI()
                    .searchRoom(kw, pageNum, list.pageSize)
                    .awaitCall()
                    .json<ResponseData<LiveSearchInfo>>()
                // 关键字/刷新换过代了：这次的结果已经过期，整批丢掉
                if (loadEpoch.get() != epoch) return@launch
                if (!res.isSuccess) {
                    list.fail.value = res.message.ifBlank { "请求失败（code=${res.code}）" }
                    return@launch
                }
                val room = res.data?.room
                val items = room?.list.orEmpty()
                totalRoom.value = room?.total_room ?: 0
                // 去重：接口翻页可能给出重复房间（首页综合搜索那边就踩过这个坑，
                // SearchByTypeContent.kt 专门做了去重，理由同样是"重复 key 会让 LazyGrid 崩"）
                // 显式写类型参数：如果写成 `if (...) mutableSetOf() else ...Set<Long>`，
                // 类型推断会把空分支推成 MutableSet<out Long>，后面的 seen.add(roomid) 就编不过
                val seen = mutableSetOf<Long>()
                if (pageNum > 1) {
                    list.data.value.mapNotNullTo(seen) { it.roomid.takeIf { id -> id > 0 } }
                }
                // roomid<=0 的脏条目直接丢：它既打不开播放页，也没法当列表 key
                val fresh = items.filter { it.roomid > 0 && seen.add(it.roomid) }
                list.pageNum = pageNum
                list.data.value = if (pageNum <= 1) fresh else list.data.value + fresh
                list.finished.value = fresh.isEmpty() ||
                    items.size < list.pageSize ||
                    (room?.total_page ?: 0) in 1..pageNum
            } catch (e: Exception) {
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
    // 打开直播播放页
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 点卡片 → 原生直播播放页（第二阶段 A 路做的 `LivePlayerActivity`）。
     *
     * ★为什么类名/extra key 写成字符串常量，而不是直接引用 `LivePlayerActivity`：
     *   依赖方向是 `app → bilimiao-compose`，compose 模块**不能反向 import app 模块的类**
     *   （会形成循环依赖，编译不过）。`bilimiao-cover` 的 `CoverViewModel.kt:87` 是同一个
     *   处境、同一个办法 —— 三处的字符串是**同一份约定**，改 extra 名要一起改：
     *   `LivePlayerActivity.EXTRA_ROOM_ID == "roomId"`。
     */
    fun toLiveRoom(roomId: Long) {
        if (roomId <= 0) return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(activity, LIVE_PLAYER_ACTIVITY)
            // 传真实房间号字符串：播放页自己还会 room_init 一次（短号/真实号都收）
            putExtra(EXTRA_ROOM_ID, roomId.toString())
        }
        // 拉不起来（被系统拦截/组件改名）就退回网页，别让用户"点了没反应"
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
     * ★uid 从哪来（**实测，不是猜的**）：`search_live` 的条目里**没有 uid**（字段并集只有
     *   cover/face/link/name/online/roomid/title/watched_show，见 [LiveSearchInfo] 的注释），
     *   所以只能在**点击那一刻**拿房间号去 `room/v1/Room/room_init` 换：响应里 `data.uid`
     *   就是主播 mid，实体侧 [LiveRoomInitInfo.uid] 早已建模，接口层一行都不用改。
     *   （首页直播 Tab 不用补查 —— 那边的列表接口 `room/v1/Area/getRoomList` 自带 uid。）
     *
     * ★为什么只在点击时查一次、不预取整屏：一屏 30 条 = 30 次额外请求，而绝大多数卡片用户
     *   根本不会去点 UP 名；B 站风控对直播接口的高频很敏感（[LiveAPI] 注释里记着"分区直播列表
     *   都会回 -352"）。一次点击换一次请求，代价最小。
     *
     * ★为什么失败必须 toast（不学首页那样"静默"）：首页的 uid 是列表自带的，拿不到就**不挂**
     *   点击区，那一下自然落到"进直播间"；而这里点击区必须在点击前就挂好（uid 还没查出来），
     *   查不到又不说，用户看到的就是"点了名字没反应"，分不清是 App 坏了还是网慢。
     *   明确告诉他这一次没拿到，他可以再点一次（重试）或点卡片进直播间 —— **绝不硬编 mid 兜底**。
     */
    fun toUserSpace(roomId: Long) {
        // 脏房间号（列表里已经滤过 roomid<=0，这里是防御）：不必发一次注定失败的请求
        if (roomId <= 0) {
            toast("暂时拿不到 UP 信息")
            return
        }
        // 连点保护：上一次补查还在飞就忽略这一次，否则会发两次 room_init、
        // 回来再把用户空间往返回栈压两层（"只在点击那一刻请求一次"就是这个意思）
        if (upperJob?.isActive == true) return
        upperJob = viewModelScope.launch {
            // ★线程划分：只有这次网络+解析放 IO（`awaitCall()` 挂着等，但 `json()` 解析是阻塞的）；
            //   导航留在协程默认的 Main —— `NavController.navigate` 是主线程 API。
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
     * `room/v1/Room/room_init` 的 uid 补查：**任何**失败都归一成 0，由 [toUserSpace] 统一 toast。
     *
     * 为什么不把异常抛出去：对用户来说"接口回错 / 网络断了 / uid 本来就是 0"是同一件事
     * （这一次没拿到 UP 信息），抛出去只会让调用点多一层没有信息量的 try。
     * 但**协程取消**必须原样抛 —— 那是页面销毁时的正常取消，不是失败，
     * 吞掉它等于把"取消"误报成"拿不到 UP 信息"（全工程同一套写法）。
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
        /** 搜索历史最多显示多少条（表里是无限增长的，别把整段历史铺满屏幕） */
        private const val HISTORY_MAX = 20

        /** 直播播放页全限定类名（约定，见 [toLiveRoom] 注释） */
        private const val LIVE_PLAYER_ACTIVITY = "com.a10miaomiao.bilimiao.LivePlayerActivity"

        /** 与 `LivePlayerActivity.EXTRA_ROOM_ID` 同一个 key（约定） */
        private const val EXTRA_ROOM_ID = "roomId"
    }
}

// ══════════════════════════════════════════════════════════════════════════
// UI
// ══════════════════════════════════════════════════════════════════════════

@Composable
private fun LiveSearchPageContent(viewModel: LiveSearchPageViewModel) {
    val searchText by viewModel.searchText.collectAsStateWithLifecycle()
    val keyword by viewModel.keyword.collectAsStateWithLifecycle()
    val historyList by viewModel.historyList.collectAsStateWithLifecycle()
    val list by viewModel.list.data.collectAsStateWithLifecycle()
    val listLoading by viewModel.list.loading.collectAsStateWithLifecycle()
    val listFinished by viewModel.list.finished.collectAsStateWithLifecycle()
    val listFail by viewModel.list.fail.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val totalRoom by viewModel.totalRoom.collectAsStateWithLifecycle()

    // 标题带上关键字：用户从返回栈里也能分清"刚才搜的是哪个词"
    val pageConfigId = PageConfig(
        title = if (keyword.isBlank()) "直播搜索" else "直播搜索\n-\n$keyword",
        // 顶部 AppBar 的搜索入口：能"继续搜索"，onSearchSelfPage 回调到本页重新搜
        search = SearchConfigInfo(
            keyword = keyword,
            name = "搜索直播间",
        ),
    )
    PageListener(
        pageConfigId,
        onSearchSelfPage = { viewModel.submit(it) },
    )

    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyGridState()

    LaunchedEffect(Unit) {
        // 只在"空着进来"时自动聚焦（用户就是要来搜东西的）。
        // 带关键字进来（浏览页点搜索）不聚焦：键盘会盖掉半屏结果，而结果才是他要看的。
        if (viewModel.searchText.value.isBlank()) {
            // 等一帧让 FocusRequester 挂到节点上：太早 requestFocus 会抛
            // "FocusRequester is not initialized"（SearchInputInline 也是 delay 后再点）
            delay(100)
            // 万一还是没挂上（页面正在退场等），就当这次不聚焦，绝不因为焦点崩页面
            runCatching { focusRequester.requestFocus() }
        }
    }

    val submit: () -> Unit = {
        // 先收键盘再提交：提交后输入框会失焦，键盘留着会盖住刚出来的结果
        focusManager.clearFocus()
        viewModel.submit()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = windowInsets.topDp.dp),
    ) {
        LiveSearchInputBar(
            searchText = searchText,
            focusRequester = focusRequester,
            onTextChange = viewModel::updateSearchText,
            onSearch = submit,
        )

        if (searchText.isBlank()) {
            // 输入框空着 = "还没开始搜"：给历史（没有历史就给引导语）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (historyList.isEmpty()) {
                    LiveSearchGuide("输入关键字，搜索正在直播的直播间")
                } else {
                    LiveSearchHistoryPanel(
                        history = historyList,
                        onPick = { viewModel.submit(it) },
                        // 用 lambda 包一层，不用 :: 方法引用：deleteHistory/clearHistory 返回 Job，
                        // 方法引用到 Unit 函数类型的转换虽然从 Kotlin 1.4 起支持，但没必要在这里赌
                        onDelete = { viewModel.deleteHistory(it) },
                        onClear = { viewModel.clearHistory() },
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (keyword.isBlank()) {
                    // 输入框有字但还没提交（首次搜索）：提示怎么触发
                    LiveSearchGuide("按回车或点「搜索」开始搜索")
                } else {
                    SwipeToRefresh(
                        refreshing = isRefreshing,
                        onRefresh = { viewModel.refresh() },
                    ) {
                        LazyVerticalGrid(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            // 自适应列数：与搜索页/首页推荐同款（手机 1 列，平板/横屏自动多列）
                            columns = GridCells.Adaptive(300.dp),
                            contentPadding = windowInsets.toPaddingValues(
                                top = 0.dp,
                            ),
                        ) {
                            if (totalRoom > 0 && list.isNotEmpty()) {
                                item(
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
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
                                key = { it.roomid },
                            ) { item ->
                                LiveRoomCard(
                                    item = item,
                                    onClick = { viewModel.toLiveRoom(item.roomid) },
                                    // 卡片上的 UP 名：进用户空间。★uid 不在搜索结果里，是点击那一刻
                                    // 才补查的（查不到会 toast，不静默失败 —— 见 toUserSpace）
                                    onClickUpper = { viewModel.toUserSpace(item.roomid) },
                                )
                            }
                            item(
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                if (list.isEmpty() && !listLoading &&
                                    listFail.isBlank() && listFinished
                                ) {
                                    // 真的 0 条：ListStateBox 只会说"空空如也"，
                                    // 在搜索页太含糊 —— 明确告诉他"这个词没搜到"，才知道要换词
                                    LiveSearchEmptyHint(keyword)
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
        }
    }
}

/**
 * 顶部搜索条。
 * 造型照 `components/start/SearchInputInline.kt` 的 SearchTextField（圆角、无下划线、
 * 尾部清空按钮），多了右边一个明确的「搜索」按钮 —— 用户说的"回车/点搜索"两条路都要有。
 */
@Composable
private fun LiveSearchInputBar(
    searchText: String,
    focusRequester: FocusRequester,
    onTextChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextField(
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                value = searchText,
                onValueChange = onTextChange,
                singleLine = true,
                placeholder = { Text("搜索直播间或主播") },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(
                            modifier = Modifier.size(24.dp),
                            onClick = { onTextChange("") },
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "清空",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                shape = MaterialTheme.shapes.large,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            TextButton(
                onClick = onSearch,
                enabled = searchText.isNotEmpty(),
            ) {
                Text("搜索")
            }
        }
    }
}

/**
 * 搜索历史面板。
 * 每条 chip 上直接带 ✕（一键删）。SearchInputInline 是"先点编辑再删"的两步操作，
 * 那是为了在底部小卡片里省空间；这里是整页，一步到位更顺手。
 */
@Composable
private fun LiveSearchHistoryPanel(
    history: List<String>,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "搜索历史",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = onClear,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("清空")
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            history.forEach { text ->
                // key：chip 自带内部状态，复用/重排时不给 key 会串状态
                key(text) {
                    SuggestionChip(
                        onClick = { onPick(text) },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "删除这条历史",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { onDelete(text) },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 一张直播间卡片。
 *
 * 信息层次照 PiliPlus `live_search_room.dart`：
 * ```
 * ┌───────────────┐
 * │直播中          │  ← 角标（我们要的，PiliPlus 没有）
 * │   （封面）      │
 * │ 主播名    [人气]│  ← 底部渐变条 + 右下角人气胶囊
 * ├───────────────┤
 * │ 标题（最多两行） │
 * └───────────────┘
 * ```
 * 人气文案直接用接口给的 `watched_show.text_large`（"15.5万人气"/"9.9万人看过"）——
 * 它是服务端算好的，用 `online` 自己拼会在"人看过"的房间里显示错含义（实测两种都有）。
 *
 * ★人气为什么叠在封面右下角（用户实测反馈"人气的位置跟着主播名走"的修复点）：
 *   原来人气是底部渐变条 Row 的第三个孩子（名字 / Spacer / 人气）：Row 测量时带 weight 的
 *   两个孩子各拿"剩余空间的一半"，名字那块 `fill = false` 只占自己文字那么宽，省下的空间
 *   **不会**补给兄弟 —— 于是人气的横坐标 = 名字宽度 + 半行，名字越长越往右，还留一段死白在右边。
 *   现在人气是**封面 Box 里独立的一颗角标**（`align(BottomEnd)` + 6dp），锚在封面右下角，
 *   与名字长度完全无关（同首页直播 Tab 的 `HomeLiveContent.kt`）；渐变条那一行只留名字，
 *   并保留"名字 fill = false + Spacer weight(1f)"给右下角**预留半行**，
 *   保证长名字的省略号不会伸到人气胶囊底下。
 *
 * ## 两个点击区（与首页直播 Tab 的 `HomeLiveContent.kt`、全站搜索直播 Tab 同一套写法）
 * ```
 * ┌───────────────────────┐
 * │直播中          （封面）│  ┐
 * │  主播名        [人气]  │  ├─ 点这里 → 进直播间（onClick）
 * ├───────────────────────┤  ┘
 * │ 标题（最多两行）        │  ┘
 * └───────────────────────┘
 *      ↑ 渐变条左边那块「主播名」→ 进用户空间（onClickUpper）
 * ```
 * 父 clickable 里嵌一个子 clickable：指针事件从内层往外派发，内层（名字那块 Row）消费掉之后，
 * 外层卡片的 clickable 收到的是已消费的事件 → 不会再去开直播间。**不需要**算坐标、
 * 也不用 `pointerInput` 拦截（那种写法反而会把长按/无障碍点坏）。
 *
 * ★与首页那张卡片的唯一差异：这里的名字**永远**挂着点击区（不管 uid 是否已知）——
 *   搜索接口不返回 uid，点击前无从判断这次能不能拿到；拿不到的情况由点击时那次补查
 *   用 toast 明确告知（见 [LiveSearchPageViewModel.toUserSpace]）。
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
                // 直播封面用 16:9 缩略图后缀（封面本来就是 16:9），省流量也更快
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
                        text = item.name,
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
            // ★修复点：人气原本是上面那个渐变条 Row 的第三个孩子，横坐标 = 名字宽度 + 半行，
            //   用户实测"名字一长，人气就跟着往右跑"。改成封面 Box 的独立角标后，
            //   锚点只剩"封面右下角 + 6dp"，与主播名一个字都不相干。
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
                        text = hotText,
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

/** 空态 / 引导语：整页居中一行灰字（与 ListStateBox 的风格统一） */
@Composable
private fun LiveSearchGuide(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 搜到 0 条时的提示（比"空空如也"多告诉用户两件事：搜的是什么词、下一步能干什么） */
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

/**
 * 卡片右下角的文案：优先用接口给的人气文案，没有再按 `online` 自己拼。
 * （`online` 也存在，但实测有的房间 `online` 是"当前人气"、有的房间 `text_large` 才是
 *   正确说法（"N人看过"），只有 `text_large` 是服务端按 `watched_show.switch` 选好的。）
 */
private fun LiveSearchRoomItem.hotText(): String {
    val text = watched_show?.text_large.orEmpty()
    if (text.isNotBlank()) return text
    return if (online > 0) "${NumberUtil.converString(online)}人看过" else ""
}

/** 「直播中」角标底色：B 站直播的粉（比主题色更能一眼认出"这是直播"） */
private val LIVE_BADGE_COLOR = Color(0xFFFB7299)
