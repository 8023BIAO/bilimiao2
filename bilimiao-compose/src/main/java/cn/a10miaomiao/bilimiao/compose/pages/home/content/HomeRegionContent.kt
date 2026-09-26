package cn.a10miaomiao.bilimiao.compose.pages.home.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.common.constant.PageTabIds
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.emitter.EmitterAction
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localEmitter
import cn.a10miaomiao.bilimiao.compose.common.navigation.BilibiliNavigation
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.components.video.VideoItemBox
import com.a10miaomiao.bilimiao.comm.apis.LegacyRegionRankItem
import com.a10miaomiao.bilimiao.comm.apis.RegionNewlistArchive
import com.a10miaomiao.bilimiao.comm.apis.RegionNewlistInfo
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.region.PgcRankInfo
import com.a10miaomiao.bilimiao.comm.entity.region.PgcRankItem
import com.a10miaomiao.bilimiao.comm.entity.region.RankingV2Response
import com.a10miaomiao.bilimiao.comm.entity.region.RankingV2VideoInfo
import com.a10miaomiao.bilimiao.comm.entity.region.RegionCatalog
import com.a10miaomiao.bilimiao.comm.entity.region.RegionCatalog.Source
import com.a10miaomiao.bilimiao.comm.entity.region.RegionInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.FilterStore
import com.a10miaomiao.bilimiao.comm.store.RegionStore
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import java.util.concurrent.atomic.AtomicLong

/**
 * 首页「分区」Tab：左侧一条竖排分区条 + 右侧该分区的视频卡片。
 *
 * 布局照着 PiliPlus 的分区页来（lib/pages/rank/view.dart:26-45）：
 *   Row [ VerticalTabBar(可滚动) , TabBarView(禁止横滑，只由左侧条切换) ]
 * 区别只有两个：
 *  1. 左侧条的数据来自 [RegionCatalog]（**分区表**，见该文件的注释）—— 不是直接拿
 *     x/v2/region/index 的返回：接口里没有「综艺」，也不给"排行榜页该按什么顺序排"，
 *     还有几个分区的 tid 丢给 ranking/v2 会回 -400（番剧/国创/资讯/剧情），必须按分区指定接口。
 *     接口的名字仍然优先（同 tid 时用接口的名字），接口新增的分区也会自动补到左条末尾。
 *  2. 右侧卡片复用首页推荐/热门的 [VideoItemBox]（HomeRecommendContent.kt:318 用的就是它），
 *     列数同样用 GridCells.Adaptive(300.dp) 自适应。
 *
 * 本次**不做直播**（用户：直播不急），也刻意没往这里塞别的入口。
 */

/**
 * 左侧条上的一项：把 [RegionCatalog.Entry] 加上"接口给的显示名"。
 *
 * 为什么不直接用 tid 当标识：综艺在 region/index 里没有顶级 tid（它只是娱乐的子分区 71），
 * 只能合成一项，所以统一用 [key]（"tid13"/"pgc7"）当 Pager / ViewModel / rememberSaveable 的标识。
 */
@Immutable
private data class RegionTab(
    val key: String,
    /** 显示名：优先用 region/index 给的名字（接口改名会跟着变），接口没有的用分区表里的名字 */
    val name: String,
    /** region/index 的 tid；综艺是 0。只用于"跟接口名字对齐"和"按 tid 去重"，不参与取数 */
    val tid: Int,
    val source: Source,
    /** [Source.UGC_RANK]/[Source.REGION_NEWLIST]/[Source.LEGACY_REGION_RANK] 是 rid；[Source.PGC_SEASON_RANK] 是 season_type */
    val param: Int,
)

/**
 * 右侧一条视频：把四种来源（ranking/v2 榜、PGC 榜、newlist 投稿、旧版分区榜）的返回
 * 拍平成卡片要的字段。卡片本身不用知道数据来自哪个接口。
 */
@Immutable
private data class RegionVideo(
    /** LazyGrid 的 key：重复 key 会让列表直接崩，所以必须稳定唯一 */
    val key: String,
    val title: String,
    val pic: String,
    val upperName: String?,
    val playNum: String?,
    val damukuNum: String?,
    val duration: String?,
    /** PGC 条目放"更新至第X话"（卡片没有单独一行放这个，用它顶 remark 那行） */
    val remark: String?,
    /** 稿件 bvid：非空时按投稿打开播放页 */
    val bvid: String?,
    /** PGC 的 ss/ep 地址：没有 bvid 时交给统一路由（能落到番剧页） */
    val url: String,
    /** 原始时长（秒）：给屏蔽规则用（卡片显示的是上面那个 duration 文本）；0 = 未知 */
    val durationSeconds: Int,
    /** UP 的 mid：PGC 条目没有，0 表示不参与 UP 屏蔽 */
    val upMid: Long,
)

private fun RankingV2VideoInfo.toRegionVideo() = RegionVideo(
    key = bvid.ifBlank { aid.toString() },
    title = title,
    pic = pic,
    upperName = owner.name,
    playNum = stat.view.toString(),
    damukuNum = stat.danmaku.toString(),
    duration = NumberUtil.converDuration(duration),
    remark = null,
    bvid = bvid.ifBlank { null },
    url = "",
    durationSeconds = duration,
    upMid = owner.mid,
)

private fun PgcRankItem.toRegionVideo() = RegionVideo(
    key = if (season_id > 0) "ss$season_id" else url.ifBlank { title },
    title = title,
    pic = cover,
    upperName = null,
    playNum = stat?.view?.takeIf { it > 0 }?.toString(),
    damukuNum = stat?.danmaku?.takeIf { it > 0 }?.toString(),
    duration = null,
    remark = updateText.ifBlank { null },
    bvid = null,
    url = url,
    durationSeconds = 0,
    upMid = 0,
)

/** 分区最新投稿（newlist，给资讯用）：字段和 ranking/v2 几乎一样，就是壳子和 UP 主/统计对象名字不同 */
private fun RegionNewlistArchive.toRegionVideo() = RegionVideo(
    key = bvid.ifBlank { aid.toString() },
    title = title,
    pic = pic,
    upperName = owner?.name,
    playNum = stat?.view?.toString(),
    damukuNum = stat?.danmaku?.toString(),
    duration = NumberUtil.converDuration(duration),
    remark = tname.ifBlank { null },
    bvid = bvid.ifBlank { null },
    url = "",
    durationSeconds = duration,
    upMid = owner?.mid ?: 0,
)

/** 旧版分区榜（ranking/region，给剧情用）：aid 是字符串、时长为 "5:04" 文本、UP 主是扁平的 author/mid */
private fun LegacyRegionRankItem.toRegionVideo() = RegionVideo(
    key = bvid.ifBlank { aid.ifBlank { title } },
    title = title,
    pic = pic,
    upperName = author.ifBlank { null },
    playNum = play.toString(),
    damukuNum = video_review.toString(),
    duration = duration.ifBlank { null },
    remark = typename.ifBlank { null },
    bvid = bvid.ifBlank { null },
    url = "",
    durationSeconds = parseDurationText(duration),
    upMid = mid,
)

/**
 * "5:04" / "1:02:03" → 秒。给屏蔽规则用（旧版分区榜只给文本时长）。
 * 解析不出来就当 0 = 未知，宁可不过滤，也不要拿错误的时长把视频误伤掉。
 */
private fun parseDurationText(text: String): Int {
    val parts = text.split(':')
    if (parts.isEmpty() || parts.size > 3) return 0
    var seconds = 0
    parts.forEach {
        val n = it.trim().toIntOrNull() ?: return 0
        seconds = seconds * 60 + n
    }
    return seconds
}

/**
 * 这个分区**拉不到内容**（接口回 -400 / 回空且没有兜底接口）。
 *
 * 单独一个异常是为了把"这个分区压根没有可用的榜/列表"（离线 assets 里的广告 165 那种）
 * 和"网络挂了"分开：前者再点一百次重试也没用，提示文案得说清楚。
 *
 * 注意：以前的文案写"暂无排行榜"，但资讯/剧情现在走的是**投稿列表**和**旧版分区榜**，
 * 它们不是"排行榜"，所以改成中性的"暂时没有内容"。
 */
private class NoContentException(regionName: String) :
    Exception("「$regionName」这个分区暂时拉不到内容，换一个分区看看")

/**
 * 首页「分区」的左侧条数据。
 *
 * 数据源是两部分拼起来的（**这是"重复项"的要害，改动前请先读完**）：
 *  1. [RegionCatalog.entries]（分区表）决定**有哪些分区、什么顺序、每个区用哪个接口** ——
 *     接口不返回综艺、也不给排行榜页的顺序，光靠接口拼不出正确的左条；
 *  2. RegionStore（region/index，app 启动时后台加载 + 本地缓存 + assets 兜底）只用来**取名字**：
 *     接口里同一个 tid 叫什么，左条就显示什么（接口改名我们跟着改），
 *     接口里多出来的新分区（表里没有的）**追加到末尾**、按普通排行榜取数，
 *     这样"服务端加了新分区"仍然不用改代码。
 * 两个来源都可能重复（B站数据里本来就存在同名分区：顶级「资讯」202 与番剧子区 51、国创子区 170 同名，
 * 「剧情」85 同时是顶级分区和影视的子区，「综艺」71 是娱乐的子区），所以这里**按 key + tid + 名字三重去重**。
 */
private class HomeRegionContentViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val regionStore: RegionStore by instance()

    val tabs = mutableStateListOf<RegionTab>()

    init {
        viewModelScope.launch {
            regionStore.stateFlow.collect { state ->
                val newTabs = buildTabs(state.regions)
                // 内容没变就别动 list：RegionStore 每次 setState 都会发一遍，
                // 无脑 clear+addAll 会让 Pager 白白重建一遍页面
                if (newTabs != tabs) {
                    tabs.clear()
                    tabs.addAll(newTabs)
                }
            }
        }
    }

    private fun buildTabs(regions: List<RegionInfo>): List<RegionTab> {
        // 接口的 tid → 名字。同一 tid 出现多次时以第一条为准（后端列表里 tid 是唯一的）
        // 用 getOrPut 而不是 HashMap.putIfAbsent：后者是 Java 8 的默认方法，对这个工程（minSdk 24）虽然可用，
        // 但 Kotlin 的扩展不挑 API、语义一样，没必要蹭边界
        val nameByTid = HashMap<Int, String>()
        regions.forEach { nameByTid.getOrPut(it.tid) { it.name } }

        val result = ArrayList<RegionTab>(RegionCatalog.entries.size + regions.size)
        val usedKeys = HashSet<String>()
        val usedNames = HashSet<String>()

        RegionCatalog.entries.forEach { entry ->
            // 名字优先用接口的：接口叫"纪录片""电视剧"，分区表里写的也是这套官方名，
            // 但接口哪天改名了（比如"纪录片"→"纪实"）这里是唯一需要跟的地方
            val name = nameByTid[entry.tid]?.takeIf { it.isNotBlank() } ?: entry.name
            // 同名去重：B站数据里同名分区真实存在（顶级资讯 vs 番剧/国创的子区资讯），
            // 万一哪天接口把它们摊平上来，左条也不会出现两个"资讯"
            if (!usedKeys.add(entry.key) || !usedNames.add(name)) return@forEach
            result.add(RegionTab(entry.key, name, entry.tid, entry.source, entry.param))
        }

        // 分区表之外的新分区：接口加了新顶级分区时自动补到末尾（老行为，保留）。
        // 去重同样按 key/tid/名字三重，避免和分区表里的项撞车。
        regions.forEach { region ->
            if (RegionCatalog.byTid.containsKey(region.tid)) return@forEach
            val key = "tid${region.tid}"
            val name = region.name
            if (name.isBlank()) return@forEach
            if (!usedKeys.add(key) || !usedNames.add(name)) return@forEach
            result.add(RegionTab(key, name, region.tid, Source.UGC_RANK, region.tid))
        }
        return result
    }
}

/**
 * 单个分区的视频列表。
 *
 * 每个分区一个实例（diViewModel 的 key 用分区表的 key，如 "tid13"/"pgc7"），
 * 所以来回切分区不会丢掉已经加载的列表，也不会每次点击都重新请求
 * —— 跟 PiliPlus 用 TabBarView 保活每个 ZonePage 是一个意思。
 */
private class RegionVideoListViewModel(
    override val di: DI,
    private val tab: RegionTab,
) : ViewModel(), DIAware {

    private val pageNavigation by instance<PageNavigation>()
    private val filterStore by instance<FilterStore>()

    val list = FlowPaginationInfo<RegionVideo>(pageSize = RANK_PAGE_SIZE)
    val isRefreshing = MutableStateFlow(false)

    // 在途请求 + 加载代数：刷新时取消旧请求，避免慢的旧批次用旧数据覆盖新列表
    // （首页其它 Content 都是这套，照抄 HomePopularContent.kt:112-115）
    private var loadJob: Job? = null
    private val loadEpoch = AtomicLong(0)

    init {
        loadData()
    }

    private fun loadData() {
        val epoch = loadEpoch.incrementAndGet()
        list.loading.value = true
        list.fail.value = ""
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = fetchItems()
                // 等屏蔽开关就位再过滤，避免冷启动第一批在默认值下漏过本该屏蔽的视频
                filterStore.awaitSettingsReady()
                val filtered = items.filter { it.passFilter(filterStore) }
                if (loadEpoch.get() != epoch) return@launch
                list.data.value = filtered
                // 本页每个分区都只取"一页"：ranking/v2、PGC 榜、旧版分区榜压根没有 pn/ps；
                // 资讯用的 newlist 虽然有 pn（实测 ps 最大 50），但为了"每个分区手感一致"也只取第一页 50 条
                // （要翻页时在 RegionAPI.regionNewlist 里传 pn++ 并在页面里追加，接口是支持的）。
                // 所以拿到就标 finished —— 底部显示"下面没有了"，而不是留一个永远转圈的"加载更多"。
                // 判据用 filtered 而不是 items：整页被屏蔽规则滤掉时列表是空的，
                // 这时候应该说"空空如也"，不能说"下面没有了"
                list.finished.value = filtered.isNotEmpty()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                if (loadEpoch.get() != epoch) return@launch
                if (e is java.io.IOException) {
                    // 网络类错误（SSL/DNS/连接中断）：静默等用户下拉刷新，跟首页热门一致
                    list.fail.value = "网络请求失败"
                } else {
                    list.fail.value = e.message ?: e.toString()
                }
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
     * 按分区表里给这个分区定的接口取数。
     * 每种来源"为什么是它"（实测结论）都在 [RegionCatalog] 的注释里，这里不重复。
     */
    private suspend fun fetchItems(): List<RegionVideo> = when (tab.source) {
        Source.UGC_RANK -> fetchUgc(tab.param)
        Source.PGC_SEASON_RANK -> fetchPgc(tab.param)
        Source.REGION_NEWLIST -> fetchNewlist(tab.param)
        Source.LEGACY_REGION_RANK -> fetchLegacyRank(tab.param)
    }

    /** ranking/v2：普通分区（全站在内） */
    private suspend fun fetchUgc(rid: Int): List<RegionVideo> {
        val res = BiliApiService.regionAPI
            .regionVideoRanking(rid = rid)
            .awaitCall()
            .json<ResultInfo<RankingV2Response>>()
        if (!res.isSuccess) {
            // -400 = 这个分区没有排行榜（实测：番剧13/国创167/资讯202/剧情85/广告165），不是网络问题
            throw NoContentException(tab.name)
        }
        return res.data?.list.orEmpty()
            .distinctBy { it.bvid.ifBlank { it.aid.toString() } }
            .map { it.toRegionVideo() }
    }

    /** 分区最新投稿（newlist）：给「资讯」用。返回壳是 data.archives，不是 data.list */
    private suspend fun fetchNewlist(rid: Int): List<RegionVideo> {
        val res = BiliApiService.regionAPI
            .regionNewlist(rid = rid)
            .awaitCall()
            .json<ResultInfo<RegionNewlistInfo>>()
        if (!res.isSuccess) {
            throw NoContentException(tab.name)
        }
        return res.data?.archives.orEmpty()
            .distinctBy { it.bvid.ifBlank { it.aid.toString() } }
            .map { it.toRegionVideo() }
    }

    /** 旧版分区榜（ranking/region）：给「剧情」用。返回壳是 data 数组，条目结构和别的接口都不一样 */
    private suspend fun fetchLegacyRank(rid: Int): List<RegionVideo> {
        val res = BiliApiService.regionAPI
            .regionRankLegacy(rid = rid)
            .awaitCall()
            .json<ResultInfo<List<LegacyRegionRankItem>>>()
        if (!res.isSuccess) {
            throw NoContentException(tab.name)
        }
        return res.data.orEmpty()
            .distinctBy { it.bvid.ifBlank { it.aid.ifBlank { it.title } } }
            .map { it.toRegionVideo() }
    }

    /**
     * PGC 榜：番剧/国创/纪录片/电影/电视剧/综艺。
     *
     * 2026-02 实测（day=3，条数）：season 榜 `pgc/season/rank/web/list` 对 season_type 1~7 是
     * 99/100/100/97/100/100（7=综艺唯一有内容的就是它）；而 `pgc/web/rank/list` 是 99/48/50/46/50/0，
     * 只有番剧是满的（综艺直接空数组）。所以**统一走 season 榜**——PiliPlus 分成两个接口是它当年的写法，
     * 现在的实测结果就是 season 榜更全（尤其是综艺，非它不可）。番剧再留一条老接口兜底。
     */
    private suspend fun fetchPgc(seasonType: Int): List<RegionVideo> {
        val res = try {
            BiliApiService.regionAPI.pgcSeasonRankList(seasonType)
                .awaitCall().json<PgcRankInfo>()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        var items = res?.takeIf { it.isSuccess }?.items.orEmpty()
        if (items.isEmpty() && seasonType == 1) {
            // 番剧：season 榜空了再试一次老接口（两条路实测都是 99 条同一个榜，多一条路更抗风控/改版）
            items = try {
                BiliApiService.regionAPI.pgcRankList(seasonType)
                    .awaitCall().json<PgcRankInfo>()
                    .takeIf { it.isSuccess }?.items.orEmpty()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
        }
        if (items.isEmpty()) {
            // PGC 榜彻底不可用：有 tid 的分区退回普通排行榜兜底（纪录片/电影/电视剧实测 ranking/v2 也有内容，
            // 比整页报错强；番剧/国创退回后会走 NoContentException，提示照旧）。
            // 综艺的 tid=0，**绝不能**退到 rid=0 —— 那会把「全站」的内容显示在「综艺」下面，
            // 正是用户说的"实际 tab 跟获取的对不上"；宁可老实说"拉不到内容"。
            return if (tab.tid > 0) fetchUgc(tab.tid) else throw NoContentException(tab.name)
        }
        return items
            .distinctBy { if (it.season_id > 0) "ss${it.season_id}" else it.url.ifBlank { it.title } }
            .map { it.toRegionVideo() }
    }

    fun loadMore() {
        // 排行接口没有下一页：底部的"加载更多/重试"都是"把这一页重新拉一遍"
        // （首屏失败时列表为空，必须重拉第 1 页而不是追加）
        if (!list.loading.value) {
            loadData()
        }
    }

    fun refresh() {
        list.reset()
        isRefreshing.value = true
        loadData()
    }

    fun toVideoDetail(item: RegionVideo) {
        // UGC 条目按稿件打开；PGC 条目只有 ss/ep 链接，交给统一路由（RankListContent.kt:148 同款做法），
        // 路由不认再退到网页
        if (item.bvid != null) {
            pageNavigation.navigateToVideoInfo(item.bvid)
            return
        }
        if (item.url.isNotBlank() && !BilibiliNavigation.navigationTo(pageNavigation, item.url)) {
            BilibiliNavigation.navigationToWeb(pageNavigation, item.url)
        }
    }

    companion object {
        /** 排行接口一次给 60~100 条，写成 100 只是给"到底了"的判断一个上限，接口本身不吃这个参数 */
        private const val RANK_PAGE_SIZE = 100
    }
}

/** 屏蔽规则：跟首页其它列表用同一套（排行榜以前不吃屏蔽规则，用户会觉得设置时灵时不灵） */
private fun RegionVideo.passFilter(filterStore: FilterStore): Boolean {
    // 排行榜没有 card_goto（推广位）字段，filterPromotion 在这里没有意义
    if (durationSeconds > 0 && !filterStore.filterDuration(durationSeconds)) return false
    if (!filterStore.filterPlayCount(playNum)) return false
    if (!filterStore.filterWord(title)) return false
    if (upMid > 0 && !filterStore.filterUpper(upMid)) return false
    if (!upperName.isNullOrBlank() && !filterStore.filterUpperName(upperName)) return false
    return true
}

@Composable
internal fun HomeRegionContent() {
    val viewModel: HomeRegionContentViewModel = diViewModel()
    val tabs = viewModel.tabs

    // 选中的分区按 **key** 记（不是下标、也不是 tid）：综艺没有 tid（它是合成的 "pgc7"），
    // 而且 RegionStore 先用离线 assets 兜底（15 项、没有综艺）、随后被网络结果（22 项）整体替换，
    // 两份列表的条数和顺序都不一样 —— 按下标记会出现"用户点的是动画，接口一回来高亮和内容跳到纪录片"
    var selectedKey by rememberSaveable { mutableStateOf(RegionCatalog.entries.first().key) }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    // 列表被替换后按 key 找回原来的分区；scrollToPage 不用动画：这是纠偏，不是用户操作
    LaunchedEffect(tabs.map { it.key }) {
        val index = tabs.indexOfFirst { it.key == selectedKey }
        if (index >= 0 && pagerState.currentPage != index) {
            pagerState.scrollToPage(index)
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        RegionTabBar(
            tabs = tabs,
            selectedKey = selectedKey,
            startPadding = windowInsets.leftDp.dp,
            onSelect = { index, tab ->
                selectedKey = tab.key
                scope.launch { pagerState.animateScrollToPage(index) }
            },
        )
        VerticalDivider()
        val saveableStateHolder = rememberSaveableStateHolder()
        HorizontalPager(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f),
            state = pagerState,
            // 照 PiliPlus：只让左侧条切分区（TabBarView 用的是 NeverScrollableScrollPhysics），
            // 横滑误触会让用户以为"列表自己跳了"
            userScrollEnabled = false,
        ) { index ->
            val tab = tabs.getOrNull(index) ?: return@HorizontalPager
            saveableStateHolder.SaveableStateProvider(tab.key) {
                RegionVideoList(tab)
            }
        }
    }
}

/**
 * 左侧一条竖排分区条。
 *
 * 宽度"只够文字"：Column 不 fillMaxWidth，宽度自然等于最宽的那一项文字；
 * widthIn(max = 100.dp) 只是兜底 —— 万一以后接口下发超长分区名，也不会把右边卡片区挤没。
 */
@Composable
private fun RegionTabBar(
    tabs: List<RegionTab>,
    selectedKey: String,
    startPadding: Dp,
    onSelect: (index: Int, tab: RegionTab) -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 100.dp)
            .padding(start = startPadding)
            .verticalScroll(scrollState)
            .padding(vertical = 8.dp)
            // 无障碍：整条是一组单选，读屏会念"已选中/未选中"
            .selectableGroup(),
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = tab.key == selectedKey
            Row(
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else Color.Transparent
                    )
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(index, tab) },
                    )
                    .padding(end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 选中指示条（PiliPlus 的 VerticalTabBar indicatorWeight: 3 那条竖线）
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp, end = 6.dp)
                        .width(3.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                )
                Text(
                    text = tab.name,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/** 右侧：该分区的视频卡片（复用首页推荐的 VideoItemBox + 自适应列数） */
@Composable
private fun RegionVideoList(tab: RegionTab) {
    val viewModel: RegionVideoListViewModel = diViewModel(key = "home-region-${tab.key}") {
        RegionVideoListViewModel(it, tab)
    }
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val list by viewModel.list.data.collectAsStateWithLifecycle()
    val listLoading by viewModel.list.loading.collectAsStateWithLifecycle()
    val listFinished by viewModel.list.finished.collectAsStateWithLifecycle()
    val listFail by viewModel.list.fail.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    val listState = rememberLazyGridState()
    val emitter = localEmitter()
    LaunchedEffect(Unit) {
        emitter.collectAction<EmitterAction.DoubleClickTab> {
            if (it.tab == PageTabIds.HomeRegion) {
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
            // 自适应列数：跟首页推荐同款（HomeRecommendContent.kt:310），
            // 手机（360dp 减去左侧条）落 1 列，平板/横屏自动 2 列及以上
            columns = GridCells.Adaptive(300.dp),
            contentPadding = windowInsets.toPaddingValues(
                top = 0.dp,
                // 左边距已经由左侧条吃掉了，这里不再叠一层，免得选中态背景和卡片之间空一条
                left = 0.dp,
            ),
        ) {
            items(list, { it.key }) { item ->
                VideoItemBox(
                    modifier = Modifier.padding(
                        horizontal = 10.dp,
                        vertical = 5.dp,
                    ),
                    title = item.title,
                    pic = item.pic,
                    upperName = item.upperName,
                    remark = item.remark,
                    playNum = item.playNum,
                    damukuNum = item.damukuNum,
                    duration = item.duration,
                    onClick = { viewModel.toVideoDetail(item) },
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
