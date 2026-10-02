package cn.a10miaomiao.bilimiao.compose.pages.download

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.pages.download.components.DownloadDetailItem
import cn.a10miaomiao.bilimiao.compose.pages.download.components.DownloadListItem
import cn.a10miaomiao.bilimiao.compose.pages.download.components.LegacyStoragePermissionEffect
import cn.a10miaomiao.bilimiao.download.DownloadService
import cn.a10miaomiao.bilimiao.download.LocalPlayerSource
import cn.a10miaomiao.bilimiao.download.entry.CurrentDownloadInfo
import com.a10miaomiao.bilimiao.comm.delegate.player.BasePlayerDelegate
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import cn.a10miaomiao.bilimiao.compose.pages.download.components.DownloadSearchBox

@Serializable
data class DownloadDetailPage(
    private val path: String,
) : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: DownloadDetailPageViewModel = diViewModel()
        DownloadDetailPageContent(path, viewModel)
    }

}
internal class DownloadDetailPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val fragment by instance<Fragment>()
    private val pageNavigation by instance<PageNavigation>()
    private val basePlayerDelegate by instance<BasePlayerDelegate>()

    val downloadInfo = MutableStateFlow<DownloadInfo?>(null)
    val downloadItems = MutableStateFlow(emptyList<DownloadItemInfo>())
    val curDownload = MutableStateFlow<CurrentDownloadInfo?>(null)
    /** 队列版本号：变化时界面重算"这条是不是在排队" */
    val waitQueueVersion = MutableStateFlow(0)
    private var downloadService: DownloadService? = null

    /** 这一条是否在等待队列里（排队中 ≠ 暂停中） */
    fun isQueued(dirPath: String): Boolean =
        downloadService?.isInWaitDownloadQueue(dirPath) == true

    fun loadDownloadDetail(
        dirPath: String,
    ) = viewModelScope.launch {
        val service = DownloadService.getService(fragment.requireContext())
        downloadService = service
        _loadDownloadDetail(service, dirPath)
        if (downloadInfo.value == null) {
            toast("缓存文件错误")
        }
        launch {
            service.downloadListVersion.collect {
                _loadDownloadDetail(service, dirPath)
            }
        }
        launch {
            service.waitQueueVersion.collect {
                waitQueueVersion.value = it
            }
        }
        service.curDownload.collect {
            curDownload.value = it
        }
    }

    private fun _loadDownloadDetail(
        service: DownloadService,
        dirPath: String,
    ) {
        // dirPath 可能是私有绝对路径，也可能是公共目录的相对页面目录名 —— 服务里两种都认
        val list = service.readDownloadDirectory(dirPath)
        val items = mutableListOf<DownloadItemInfo>()
        var isCompleted = true
        list.forEach {
            val biliEntry = it.entry
            var indexTitle = ""
            var itemTitle = ""
            var id = 0L
            var cid = 0L
            var epid = 0L
            var type = DownloadType.VIDEO
            val page = biliEntry.page_data
            if (page != null) {
                id = biliEntry.avid!!
                indexTitle = page.download_title ?: "unknown"
                cid = page.cid
                type = DownloadType.VIDEO
                // ★统一走 entry.showTitle：新下载有 display_title（真名），老条目回落 part/subtitle/title
                itemTitle = biliEntry.showTitle
            }
            val ep = biliEntry.ep
            val source = biliEntry.source
            if (ep != null && source != null) {
                id = biliEntry.season_id!!.toLong()
                indexTitle = ep.index_title
                epid = ep.episode_id
                cid = source.cid
                type = DownloadType.BANGUMI
                itemTitle = biliEntry.showTitle
            }
            val item = DownloadItemInfo(
                dir_path = it.entryDirPath,
                media_type = biliEntry.media_type,
                has_dash_audio = biliEntry.has_dash_audio,
                is_completed = biliEntry.is_completed,
                total_bytes = biliEntry.total_bytes,
                downloaded_bytes = biliEntry.downloaded_bytes,
                title = itemTitle,
                cover = biliEntry.cover,
                id = id,
                type = type,
                cid = cid,
                epid = epid,
                index_title = indexTitle,
                page = biliEntry.page_data?.page ?: 0,
                seasonIndex = biliEntry.seasonIndex,
            )
            items.add(item)
            if (!item.is_completed) {
                isCompleted = false
            }
        }
        if (list.isEmpty()) {
            downloadInfo.value = null
            downloadItems.value = emptyList()
        } else {
            val biliEntry = list[0].entry
            val item = items[0]
            downloadInfo.value = DownloadInfo(
                dir_path = list[0].pageDirPath,
                media_type = biliEntry.media_type,
                has_dash_audio = biliEntry.has_dash_audio,
                is_completed = isCompleted,
                total_bytes = biliEntry.total_bytes,
                downloaded_bytes = biliEntry.downloaded_bytes,
                // ★表头标题：合集优先用**合集名**（新下载写了 season_title），否则回落第一条的标题。
                //   原来固定用 list[0] 的标题 ⇒ 合集表头显示的却是"某一集"的名字。
                title = list.firstNotNullOfOrNull { it.entry.seasonTitle } ?: biliEntry.title,
                cover = biliEntry.cover,
                cid = item.cid,
                id = item.id,
                type = item.type,
                items = items
            )
            // ★按"UP 主的顺序"排（2026-10-02）：
            //   合集 ⇒ seasonIndex（下载时写入的合集内序号）；多P ⇒ page（第几P）；
            //   番剧 ⇒ ep.sort_index（由 showTitle/seasonIndex 统一取值）。
            //   老条目这两个字段都是 null/0 ⇒ 全部并列，退化成原来的顺序（不会更差）。
            val ordered = items.sortedWith(
                compareBy({ it.seasonIndex ?: Int.MAX_VALUE }, { it.page })
            )
            downloadItems.value = ordered
        }
    }

    fun itemClick(item: DownloadItemInfo) {
        if (item.is_completed) {
            basePlayerDelegate.openPlayer(LocalPlayerSource(
                activity = fragment.requireActivity(),
                entryDirPath = item.dir_path,
                // LocalPlayerSource 的 id 是 cid（弹幕 oid / 历史记录 cid / 本地进度键 dl_${avid}_${id}），
                // 原来传 item.id（avid / season_id）→ 发弹幕必失败、云同步进度对不上、多P进度互相串
                id = item.cid.toString(),
                title = item.title,
                coverUrl = item.cover,
            ))
        }
    }

    fun startClick(item: DownloadItemInfo) {
        downloadService?.startDownload(item.dir_path)
    }

    fun pauseClick(item: DownloadItemInfo, taskId: Long) {
        downloadService?.cancelDownload(taskId)
    }

    fun deleteDownload(
        item: DownloadItemInfo,
        dirPath: String,
    ) {
        val info = downloadInfo?.value ?: return
        viewModelScope.launch {
            val service = DownloadService.getService(fragment.requireContext())
            // ★如实提示（同列表页批量删除那条）：删不到文件时不要还说"已删除"
            val deleted = service.deleteDownload(info.dir_path, item.dir_path)
            toast(
                if (deleted > 0) "已删除：" + item.title
                else "没有找到可删除的文件（可能已被移动或删除）"
            )
            _loadDownloadDetail(service, dirPath)
            if (downloadInfo.value == null) {
                pageNavigation.popBackStack()
            }
        }
    }
}

@Composable
internal fun DownloadDetailPageContent(
    dirPath: String,
    viewModel: DownloadDetailPageViewModel,
) {
    // 详情页也可能直接触发"继续下载"，这里同样保证老系统只弹一次存储权限
    LegacyStoragePermissionEffect()
    PageConfig(
        title = "下载详情"
    )

    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())
    val bottomAppBarHeight = windowStore.bottomAppBarHeightDp

    val downloadInfo by viewModel.downloadInfo.collectAsStateWithLifecycle()
    val downloadItems by viewModel.downloadItems.collectAsStateWithLifecycle()
    val curDownload by viewModel.curDownload.collectAsStateWithLifecycle()
    // 读一下队列版本：队列变化时要重算下面每条的"排队中/暂停中"
    val waitQueueVersion by viewModel.waitQueueVersion.collectAsStateWithLifecycle()

    // 搜索过滤
    var searchQuery by remember { mutableStateOf("") }
    val filteredItems = remember(downloadItems, searchQuery) {
        if (searchQuery.isBlank()) downloadItems
        else downloadItems.filter {
            it.title.contains(searchQuery, ignoreCase = true)
                    || it.index_title.contains(searchQuery, ignoreCase = true)
        }
    }

    LaunchedEffect(viewModel, dirPath) {
        viewModel.loadDownloadDetail(dirPath)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索框：改用公共组件（与下载列表页/下载面板同一个，右侧带"一键清空"的叉）
        DownloadSearchBox(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "搜索下载视频",
            modifier = Modifier
                .padding(top = windowInsets.topDp.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )

        LazyColumn(
            contentPadding = PaddingValues(bottom = windowInsets.bottomDp.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = windowInsets.leftDp.dp, end = windowInsets.rightDp.dp)
        ) {
            item {
                downloadInfo?.let {
                    DownloadListItem(curDownload, it, queued = viewModel.isQueued(it.dir_path), onClick = {})
                }
            }
            items(
                filteredItems,
            ) { item ->
                DownloadDetailItem(
                    curDownload = curDownload,
                    queued = remember(waitQueueVersion, curDownload) { viewModel.isQueued(item.dir_path) },
                    item = item,
                    onClick = {
                        viewModel.itemClick(item)
                    },
                    onStartClick = {
                        viewModel.startClick(item)
                    },
                    onPauseClick = {
                        viewModel.pauseClick(item, it)
                    },
                    onDeleteClick = {
                        viewModel.deleteDownload(item, dirPath)
                    }
                )
            }
            item {
                Spacer(modifier = Modifier.height(windowInsets.bottomDp.dp + bottomAppBarHeight.dp))
            }
        }
    }

}
