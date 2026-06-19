package cn.a10miaomiao.bilimiao.compose.pages.video.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.viewModelScope
import bilibili.app.archive.v1.Arc
import bilibili.app.archive.v1.Page
import bilibili.app.view.v1.ViewGRPC
import bilibili.app.view.v1.ViewReq
import cn.a10miaomiao.bilimiao.compose.components.dialogs.AutoSheetDialog
import cn.a10miaomiao.bilimiao.compose.pages.download.DownloadBangumiCreatePageViewModel.QualityInfo
import cn.a10miaomiao.bilimiao.compose.pages.download.EpisodeItem
import cn.a10miaomiao.bilimiao.download.DownloadService
import cn.a10miaomiao.bilimiao.download.entry.BiliDownloadEntryInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import android.content.Context
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import kotlinx.coroutines.withContext
import java.lang.annotation.Inherited

@Stable
class VideoDownloadDialogState(
    val scope: CoroutineScope,
) {
    private var downloadService: DownloadService? = null
    private var videoBvid = ""

    private val _visible = mutableStateOf(false)
    val visible: Boolean get() = _visible.value

    private val _loading = mutableStateOf(false)
    val loading: Boolean get() = _loading.value

    // ===== 分P数据 =====
    private val _list = mutableStateOf(listOf<Page>())
    val list: List<Page> get() = _list.value

    private val _arcData = MutableStateFlow<Arc?>(null)
    val arcData get() = _arcData.value

    private val _checkedMap = mutableStateMapOf<Long,Int>() // cid -> index
    val checkedMap: Map<Long,Int> get() = _checkedMap
    val checkedSize: Int get() = _checkedMap.size

    private val _downloadedSet = mutableStateOf(setOf<Long>()) // 已下载
    val downloadedSet: Set<Long> get() = _downloadedSet.value

    // ===== 合集数据 =====
    data class SeasonEpisodeItem(
        val aid: Long,
        val title: String,
        val cover: String = "",
        val duration: String = "",
    )

    private val _seasonEpisodes = mutableStateOf(listOf<SeasonEpisodeItem>())
    val seasonEpisodes: List<SeasonEpisodeItem> get() = _seasonEpisodes.value

    private val _seasonCheckedMap = mutableStateMapOf<Long, Boolean>() // aid -> checked
    val seasonCheckedMap: Map<Long, Boolean> get() = _seasonCheckedMap
    val seasonCheckedSize: Int get() = _seasonCheckedMap.count { it.value }

    // 合集已下载 avid 集合（与分P的 downloadedSet 同机制，按 avid 匹配）
    private val _seasonDownloadedSet = mutableStateOf(setOf<Long>())
    val seasonDownloadedSet: Set<Long> get() = _seasonDownloadedSet.value

    private val _tabIndex = mutableStateOf(0)
    val tabIndex: Int get() = _tabIndex.value

    private var _seasonId: String? = null
    private var _seasonTitle: String? = null

    private val _qualityList = mutableStateOf(listOf<Pair<Int, String>>()) // Quality: Description
    val qualityList: List<Pair<Int, String>> get() = _qualityList.value

    private val _quality = mutableIntStateOf(0)
    val quality get () = _quality.intValue
    val description get() = qualityList.find { it.first == quality }?.second ?: "未选择"

    val snackbar = SnackbarHostState()

    fun show(
        service: DownloadService,
        bvid: String,
        videoArc: Arc? = null,
        videoPages: List<Page>,
        context: Context,
        ugcSeasonEpisodes: List<SeasonEpisodeItem>? = null,
        seasonId: String? = null,
        seasonTitle: String? = null,
    ) {
        downloadService = service
        _visible.value = true
        _list.value = videoPages
        _arcData.value = videoArc
        _downloadedSet.value = getDownloadedList(
            service,
            videoPages.map { it.cid }.toSet()
        )
        videoBvid = bvid
        _tabIndex.value = 0
        _seasonId = seasonId
        _seasonTitle = seasonTitle
        if (ugcSeasonEpisodes != null) {
            _seasonEpisodes.value = ugcSeasonEpisodes
            _seasonCheckedMap.clear()
            // 查询合集内已下载的视频（按 avid 匹配）
            _seasonDownloadedSet.value = getDownloadedSeasonList(
                service,
                ugcSeasonEpisodes.map { it.aid }.toSet()
            )
        } else {
            _seasonEpisodes.value = emptyList()
            _seasonDownloadedSet.value = emptySet()
        }
        if (qualityList.isEmpty()) {
            if (videoPages.isNotEmpty() && videoArc != null) {
                val videoAid = videoArc.aid.toString()
                getAcceptQuality(videoAid, videoPages[0].cid.toString(), context)
            } else if (ugcSeasonEpisodes != null && ugcSeasonEpisodes.isNotEmpty()) {
                getAcceptQualityForSeason(ugcSeasonEpisodes[0].aid, context)
            }
        }
    }
    fun setTabIndex(index: Int) {
        _tabIndex.value = index
    }

    private fun getDownloadedList(
        service: DownloadService,
        cidSet: Set<Long>,
    ): Set<Long> {
        return service
            .downloadList
            .mapNotNull { it.entry.source?.cid ?: it.entry.page_data?.cid }
            .filter { cidSet.contains(it) }
            .toSet()
    }

    /**
     * 查询合集中已下载的视频 avid 集合。
     * 匹配逻辑：downloadList 中 entry.avid 命中本次合集传入的 aidSet。
     * 注意：合集下载的 entry 有 season_id，但 avid 同样存在，可直接用 avid 匹配。
     */
    private fun getDownloadedSeasonList(
        service: DownloadService,
        aidSet: Set<Long>,
    ): Set<Long> {
        return service
            .downloadList
            .mapNotNull { it.entry.avid }
            .filter { aidSet.contains(it) }
            .toSet()
    }

    private fun getAcceptQuality(
        aid: String,
        cid: String,
        context: Context? = null,
    ) = scope.launch(Dispatchers.IO) {
        try {
            val res = BiliApiService.playerAPI.getVideoPalyUrl(
                aid, cid, 64, fnval = 4048
            )
            val acceptDescription = res.accept_description
            _qualityList.value = res.accept_quality.mapIndexed { index, q ->
                q to (acceptDescription.getOrNull(index) ?: q.toString())
            }
            // 自动选择默认画质
            if (context != null && _qualityList.value.isNotEmpty()) {
                try {
                    val qualityMode = SettingPreferences.mapData(context) {
                        it[SettingPreferences.DownloadQualityMode] ?: 0
                    }
                    when (qualityMode) {
                        1 -> { // 最高画质
                            val highest = _qualityList.value.maxBy { it.first }.first
                            withContext(Dispatchers.Main) { setQuality(highest) }
                        }
                        2 -> { // 最低画质
                            val lowest = _qualityList.value.minBy { it.first }.first
                            withContext(Dispatchers.Main) { setQuality(lowest) }
                        }
                        3 -> { // 固定画质
                            val fixedQuality = SettingPreferences.mapData(context) {
                                it[SettingPreferences.DownloadFixedQuality] ?: -1
                            }
                            if (fixedQuality > 0 && _qualityList.value.any { it.first == fixedQuality }) {
                                withContext(Dispatchers.Main) { setQuality(fixedQuality) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun getAcceptQualityForSeason(aid: Long, context: Context?) = scope.launch(Dispatchers.IO) {
        try {
            val req = ViewReq(aid = aid)
            val res = BiliGRPCHttp.request { ViewGRPC.view(req) }.awaitCall()
            val firstPage = res.pages.firstOrNull()?.page ?: return@launch
            getAcceptQuality(aid.toString(), firstPage.cid.toString(), context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    fun checkedChange(cid: Long, index: Int) {
        if (checkedMap.contains(cid)) {
            _checkedMap.remove(cid)
        } else {
            _checkedMap[cid] = index
        }
    }

    fun selectAll() {
        list.forEachIndexed { index, page ->
            if (!downloadedSet.contains(page.cid)) {
                _checkedMap[page.cid] = index
            }
        }
    }

    fun deselectAll() {
        _checkedMap.clear()
    }

    val allSelectable: Boolean get() = list.any { !downloadedSet.contains(it.cid) }
    val allSelected: Boolean get() = list.isNotEmpty() && list.all { downloadedSet.contains(it.cid) || checkedMap.containsKey(it.cid) }

    // ===== 合集选择 =====
    fun seasonCheckedChange(aid: Long) {
        // 已下载项不可改状态（与分P tab 的 disabled 行为一致）
        if (seasonDownloadedSet.contains(aid)) return
        val current = _seasonCheckedMap[aid] ?: false
        _seasonCheckedMap[aid] = !current
    }

    fun seasonSelectAll() {
        // 只勾选未下载的，已下载的保持其默认勾选态
        _seasonEpisodes.value.forEach { ep ->
            if (!seasonDownloadedSet.contains(ep.aid)) {
                _seasonCheckedMap[ep.aid] = true
            }
        }
    }

    fun seasonDeselectAll() {
        // 只清未下载的勾选，已下载项不参与勾选 map（其"已勾选"态由 UI 层根据 downloadedSet 渲染）
        _seasonCheckedMap.clear()
    }

    // 全选按钮是否可用：存在未下载项才显示
    val seasonAllSelectable: Boolean get() = _seasonEpisodes.value.any { !seasonDownloadedSet.contains(it.aid) }
    // 全选状态：未下载项全部勾选（已下载项不计入）
    val seasonAllSelected: Boolean get() = _seasonEpisodes.value.isNotEmpty()
            && _seasonEpisodes.value.all { seasonDownloadedSet.contains(it.aid) || _seasonCheckedMap[it.aid] == true }

    fun setQuality(quality: Int) {
        _quality.intValue = quality
    }

    private fun showSnackbar(message: String) {
        scope.launch {
            snackbar.showSnackbar(message)
        }
    }

    fun startDownload() {
        if (quality == 0) {
            showSnackbar("请选择画质")
            return
        }
        val service = downloadService
        if (service == null) {
            showSnackbar("下载服务异常")
            return
        }
        val videoArc = arcData
        val isPagesTab = _list.value.isNotEmpty() && _tabIndex.value == 0
        if (isPagesTab) {
            if (videoArc == null) {
                showSnackbar("缺少视频信息")
                return
            }
            // 分P下载
            val ordered = checkedMap.entries.sortedBy { list.getOrNull(it.value)?.page ?: Int.MAX_VALUE }
            ordered.forEach { c ->
                var page = list.getOrNull(c.value)
                if (page?.cid != c.key) {
                    page = list.find { it.cid == c.key }
                }
                if (page != null) {
                    downloadVideo(service, videoArc, page)
                }
            }
            toast("成功创建${checkedSize}条记录")
        } else {
            // 合集下载：逐个视频获取pages并下载
            // 过滤已下载项（双保险，理论上 checkedMap 不含已下载 aid）
            val checkedAids = _seasonCheckedMap.filter { it.value }.keys
                .filter { !seasonDownloadedSet.contains(it) }
                .toList()
            if (checkedAids.isEmpty()) {
                showSnackbar("请选择要下载的视频")
                return
            }
            scope.launch(Dispatchers.IO) {
                var successCount = 0
                for (aid in checkedAids) {
                    try {
                        val episode = _seasonEpisodes.value.find { it.aid == aid } ?: continue
                        val req = ViewReq(aid = aid)
                        val res = BiliGRPCHttp.request { ViewGRPC.view(req) }.awaitCall()
                        val page = res.pages.firstOrNull()?.page ?: continue
                        if (page.cid == 0L) continue
                        val currentTime = System.currentTimeMillis()
                        val entryTitle = episode.title.ifBlank { _seasonTitle ?: "未知标题" }
                        val pageData = BiliDownloadEntryInfo.PageInfo(
                                cid = page.cid,
                                page = page.page,
                                from = page.from,
                                part = page.part,
                                vid = page.vid,
                                has_alias = false,
                                tid = 0,
                                width = 0,
                                height = 0,
                                rotate = 0,
                                download_title = "视频已缓存完成",
                                download_subtitle = entryTitle
                            )
                            val biliVideoEntry = BiliDownloadEntryInfo(
                                media_type = 2, has_dash_audio = true,
                                is_completed = false, total_bytes = 0, downloaded_bytes = 0,
                                title = entryTitle,
                                type_tag = quality.toString(),
                                cover = episode.cover,
                                prefered_video_quality = quality,
                                quality_pithy_description = description,
                                guessed_total_bytes = 0, total_time_milli = 0,
                                danmaku_count = 1000,
                                time_update_stamp = currentTime, time_create_stamp = currentTime,
                                can_play_in_advance = true, interrupt_transform_temp_file = false,
                                avid = aid, spid = 0,
                                season_id = _seasonId, ep = null, source = null,
                                bvid = "", owner_id = 0L, page_data = pageData,
                            )
                            service.createDownload(biliVideoEntry)
                            successCount++
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                withContext(Dispatchers.Main) {
                    toast("成功创建${successCount}条记录")
                }
            }
        }
        dismiss()
        _checkedMap.clear()
        _seasonCheckedMap.clear()
    }

    private fun downloadVideo(
        service: DownloadService,
        videoArc: Arc,
        page: Page,
    ) {
        val pageData = BiliDownloadEntryInfo.PageInfo(
            cid = page.cid,
            page = page.page,
            from = page.from,
            part = page.part,
            vid = page.vid,
            has_alias = false,
            tid = 0,
            width = 0,
            height = 0,
            rotate = 0,
            download_title = "视频已缓存完成",
            download_subtitle = videoArc.title
        )
        val currentTime = System.currentTimeMillis()
        val biliVideoEntry = BiliDownloadEntryInfo(
            media_type = 2,
            has_dash_audio = true,
            is_completed = false,
            total_bytes = 0,
            downloaded_bytes = 0,
            title = videoArc.title,
            type_tag = quality.toString(),
            cover = videoArc.pic,
            prefered_video_quality = quality,
            quality_pithy_description = description,
            guessed_total_bytes = 0,
            total_time_milli = 0,
            danmaku_count = 1000,
            time_update_stamp = currentTime,
            time_create_stamp = currentTime,
            can_play_in_advance = true,
            interrupt_transform_temp_file = false,
            avid = videoArc.aid,
            spid = 0,
            season_id = null,
            ep = null,
            source = null,
            bvid = videoBvid,
            owner_id = videoArc.author?.mid ?: 0L,
            page_data = pageData
        )
        service.createDownload(biliVideoEntry)
    }

    fun dismiss() {
        _visible.value = false
    }
}

@Composable
private fun VideoDownloadItem(
    page: Page,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    Box(
        modifier = Modifier.padding(
            vertical = 5.dp,
            horizontal = 10.dp,
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange?.invoke(!checked) },
            shape = RoundedCornerShape(10.dp),
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = page.part,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        Text(
                            text = "P${page.page}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                        Text(
                            text = NumberUtil.converDuration(page.duration),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Checkbox(
                    enabled = enabled,
                    checked = checked,
                    onCheckedChange = onCheckedChange
                )
            }
        }
    }
}

@Composable
private fun SeasonDownloadItem(
    item: VideoDownloadDialogState.SeasonEpisodeItem,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    Box(
        modifier = Modifier.padding(
            vertical = 5.dp,
            horizontal = 10.dp,
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange?.invoke(!checked) },
            shape = RoundedCornerShape(10.dp),
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                    )
                    Row {
                        Text(
                            text = item.duration,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        if (!enabled) {
                            Text(
                                text = "  · 已下载",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                Checkbox(
                    enabled = enabled,
                    checked = checked,
                    onCheckedChange = onCheckedChange
                )
            }
        }
    }
}

@Composable
fun VideoDownloadDialog(
    state: VideoDownloadDialogState,
) {
    if (state.visible) {
        var expandedQualityMenu by remember {
            mutableStateOf(false)
        }
        val hasPages = state.list.isNotEmpty()
        val hasSeason = state.seasonEpisodes.isNotEmpty()
        val tabs = if (hasPages && hasSeason) listOf("分P", "合集") else if (hasSeason) listOf("合集") else listOf("分P")
        val pagerState = rememberPagerState(pageCount = { tabs.size })
        val coroutineScope = rememberCoroutineScope()


        AutoSheetDialog(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .heightIn(max = 500.dp),
            content = {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Tab栏
                    if (tabs.size > 1) {
                        TabRow(
                            modifier = Modifier.fillMaxWidth(),
                            selectedTabIndex = pagerState.currentPage,
                        ) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = pagerState.currentPage == index,
                                    onClick = {
                                        state.setTabIndex(index)
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    },
                                    text = {
                                        Text(title)
                                    }
                                )
                            }
                        }
                    }

                    // 标题+全选
                    Row(
                        modifier = Modifier
                            .padding(10.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "请选择${tabs[0]}下载",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if ((tabs.size == 1 && hasPages || pagerState.currentPage == 0) && state.allSelectable) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = state.allSelected,
                                    onCheckedChange = {
                                        if (state.allSelected) state.deselectAll()
                                        else state.list.forEachIndexed { i, p ->
                                            if (!state.downloadedSet.contains(p.cid))
                                                state.checkedChange(p.cid, i)
                                        }
                                    },
                                )
                                Text(
                                    text = "全选",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if ((tabs.size == 1 && hasSeason || pagerState.currentPage == 1) && state.seasonAllSelectable) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = state.seasonAllSelected,
                                    onCheckedChange = {
                                        if (state.seasonAllSelected) state.seasonDeselectAll()
                                        else state.seasonSelectAll()
                                    },
                                )
                                Text(
                                    text = "全选",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }


                    // 列表
                    Box(
                        modifier = Modifier.weight(1f)
                    ) {
                        if (hasPages && hasSeason) {
                            HorizontalPager(
                                modifier = Modifier.fillMaxSize(),
                                state = pagerState,
                            ) { pageIndex ->
                                when (pageIndex) {
                                    0 -> {
                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            items(state.list.size, { state.list[it].cid }) { index ->
                                                val item = state.list[index]
                                                val isEnabled = !state.downloadedSet.contains(item.cid)
                                                val isChecked = if (isEnabled) {
                                                    state.checkedMap.containsKey(item.cid)
                                                } else { true }
                                                VideoDownloadItem(
                                                    page = item,
                                                    enabled = isEnabled,
                                                    checked = isChecked,
                                                    onCheckedChange = { state.checkedChange(item.cid, index) }
                                                )
                                            }
                                        }
                                    }
                                    1 -> {
                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            items(state.seasonEpisodes.size, { state.seasonEpisodes[it].aid }) { index ->
                                                val item = state.seasonEpisodes[index]
                                                val isEnabled = !state.seasonDownloadedSet.contains(item.aid)
                                                val isChecked = if (isEnabled) {
                                                    state.seasonCheckedMap[item.aid] ?: false
                                                } else { true }
                                                SeasonDownloadItem(
                                                    item = item,
                                                    enabled = isEnabled,
                                                    checked = isChecked,
                                                    onCheckedChange = { state.seasonCheckedChange(item.aid) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (hasSeason) {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(state.seasonEpisodes.size, { state.seasonEpisodes[it].aid }) { index ->
                                    val item = state.seasonEpisodes[index]
                                    val isEnabled = !state.seasonDownloadedSet.contains(item.aid)
                                    val isChecked = if (isEnabled) {
                                        state.seasonCheckedMap[item.aid] ?: false
                                    } else { true }
                                    SeasonDownloadItem(
                                        item = item,
                                        enabled = isEnabled,
                                        checked = isChecked,
                                        onCheckedChange = { state.seasonCheckedChange(item.aid) }
                                    )
                                }
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(state.list.size, { it }) { index ->
                                    val item = state.list[index]
                                    val isEnabled = !state.downloadedSet.contains(item.cid)
                                    val isChecked = if (isEnabled) {
                                        state.checkedMap.containsKey(item.cid)
                                    } else { true }
                                    VideoDownloadItem(
                                        page = item,
                                        enabled = isEnabled,
                                        checked = isChecked,
                                        onCheckedChange = { state.checkedChange(item.cid, index) }
                                    )
                                }
                            }
                        }
                        SnackbarHost(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            hostState = state.snackbar,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(5.dp)
                    ) {
                        Box() {
                            Button(
                                modifier = Modifier.padding(end = 5.dp),
                                onClick = { expandedQualityMenu = true },
                            ) {
                                Text(text = "画质：" + state.description)
                            }
                            DropdownMenu(
                                expanded = expandedQualityMenu,
                                onDismissRequest = { expandedQualityMenu = false },
                            ) {
                                state.qualityList.forEach {
                                    DropdownMenuItem(
                                        onClick = {
                                            expandedQualityMenu = false
                                            state.setQuality(it.first)
                                        },
                                        text = {
                                            Text(text = it.second)
                                        }
                                    )
                                }
                            }
                        }
                        val totalCount = if (hasSeason && (!hasPages || pagerState.currentPage == 1)) state.seasonCheckedSize
                            else state.checkedSize
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = state::startDownload,
                            enabled = totalCount > 0,
                        ) {
                            Text(text = "开始下载($totalCount)")
                        }
                    }
                }
            },
            onDismiss = state::dismiss
        )
    }
}