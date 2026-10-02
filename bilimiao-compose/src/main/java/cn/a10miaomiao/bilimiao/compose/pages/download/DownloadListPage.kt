package cn.a10miaomiao.bilimiao.compose.pages.download

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.components.dialogs.OverlayAlertDialog
import cn.a10miaomiao.bilimiao.compose.pages.download.components.DownloadListItem
import cn.a10miaomiao.bilimiao.compose.pages.download.components.LegacyStoragePermissionEffect
import cn.a10miaomiao.bilimiao.download.DownloadService
import cn.a10miaomiao.bilimiao.download.entry.BiliDownloadEntryAndPathInfo
import cn.a10miaomiao.bilimiao.download.entry.CurrentDownloadInfo
import com.a10miaomiao.bilimiao.comm.mypage.myMenu
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
class DownloadListPage : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: DownloadListPageViewModel = diViewModel()
        DownloadListPageContent(viewModel)
    }

}

internal class DownloadListPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val fragment by instance<Fragment>()
    private val pageNavigation by instance<PageNavigation>()

    var downloadListVersion = 0
    val downloadList = MutableStateFlow(emptyList<BiliDownloadEntryAndPathInfo>())
    val curDownload = MutableStateFlow<CurrentDownloadInfo?>(null)
    /** 队列版本号：变化时界面重算"这组是不是在排队" */
    val waitQueueVersion = MutableStateFlow(0)
    private var downloadService: DownloadService? = null

    /** 这一组里是否有条目在等待队列（排队中 ≠ 暂停中） */
    fun isQueuedGroup(info: DownloadInfo): Boolean =
        info.items.any { downloadService?.isInWaitDownloadQueue(it.dir_path) == true }
    var downloadPath = ""

    init {
        loadDownloadList()
    }

    fun refresh() = viewModelScope.launch {
        val service = DownloadService.getService(fragment.requireContext())
        _loadDownloadList(service)
    }

    private fun loadDownloadList() = viewModelScope.launch {
        val service = DownloadService.getService(fragment.requireContext())
        downloadService = service
        downloadPath = service.getDownloadPath()
        _loadDownloadList(service)
        launch {
            service.downloadListVersion.collect {
                if (it != downloadListVersion) {
                    downloadListVersion = it
                    _loadDownloadList(service)
                }
            }
        }
        launch {
            launch {
                service.waitQueueVersion.collect { waitQueueVersion.value = it }
            }
            service.curDownload.collect(curDownload::value::set)
        }
    }

    private fun _loadDownloadList(
        service: DownloadService,
    ) {
        downloadList.value = service.downloadList.toList()
    }

    fun filterDownloadList(
        list: List<BiliDownloadEntryAndPathInfo>,
        status: Int,
    ): List<DownloadInfo> {
        val result = mutableListOf<DownloadInfo>()
        list.filter {
            if (status == 1) {
                !it.entry.is_completed
            } else if (status == 2) {
                it.entry.is_completed
            } else {
                true
            }
        }
        .forEach { item ->
            val biliEntry = item.entry
            var indexTitle = ""
            var itemTitle = ""
            var id = 0L
            var cid = 0L
            var epid = 0L
            var type = DownloadType.VIDEO
            val page = biliEntry.page_data
            if (page != null) {
                // 合集下载用season_id分组，否则用avid
                id = biliEntry.season_id?.toLongOrNull() ?: biliEntry.avid!!
                indexTitle = page.download_title ?: "unknown"
                cid = page.cid
                type = DownloadType.VIDEO
                // ★卡片标题：**合集优先用合集名**（新下载写了 season_title），否则用统一真名解析
                itemTitle = biliEntry.seasonTitle ?: biliEntry.showTitle
            }
            val ep = biliEntry.ep
            val source = biliEntry.source
            if (ep != null && source != null) {
                id = biliEntry.season_id!!.toLong()
                indexTitle = ep.index_title
                epid = ep.episode_id
                cid = source.cid
                type = DownloadType.BANGUMI
                itemTitle = if (ep.index_title.isNotBlank()) {
                    ep.index_title
                } else {
                    ep.index
                }
            }
            val downloadItem = DownloadItemInfo(
                dir_path = item.entryDirPath,
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
                page = biliEntry.page_data?.page ?: 0,
                index_title = indexTitle,
            )
            // 搜索整个result列表，找同类型+同id的分组合并
            val existing = result.find {
                it.type == downloadItem.type && it.id == downloadItem.id
            }
            if (existing != null) {
                if (existing.is_completed && !downloadItem.is_completed) {
                    existing.is_completed = false
                }
                // ★合集名兜底（2026-10-02）：只要这一组里**任何一条**带 season_title，卡片就用它。
                //   不能只靠"建组时那条恰好带了"——同一组里可能混着改动前下载的旧条目（那时还没写这个字段），
                //   而建组顺序取决于读取顺序，会出现"详情页表头是合集名、外面卡片却是某一集"的不一致。
                biliEntry.seasonTitle?.let { name ->
                    if (name != existing.title) existing.title = name
                }
                existing.items.add(downloadItem)
            } else {
                result.add(
                    DownloadInfo(
                        dir_path = item.pageDirPath,
                        media_type = biliEntry.media_type,
                        has_dash_audio = biliEntry.has_dash_audio,
                        is_completed = biliEntry.is_completed,
                        total_bytes = biliEntry.total_bytes,
                        downloaded_bytes = biliEntry.downloaded_bytes,
                        // ★卡片标题（新建分组这一处才是列表上显示的那个）：合集优先用合集名，
                        //   否则用条目标题（多P=视频标题 / 番剧=番剧名）。别用分P名——那会让多P 卡片只显示某一P。
                        title = biliEntry.seasonTitle ?: biliEntry.title,
                        cover = biliEntry.cover,
                        cid = cid,
                        id = id,
                        type = type,
                        items = mutableListOf(downloadItem)
                    )
                )
            }
        }
        // 按合集分P序号排序每个分组内的视频
        result.forEach { info ->
            info.items.sortBy { it.page }
        }
        return result
    }

    fun toDetailPage(item: DownloadInfo) {
        pageNavigation.navigate(DownloadDetailPage(
            path = item.dir_path
        ))
    }

    fun copyDownloadPathToClipboard() {
        val context = fragment.requireContext()
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("", downloadPath))
        // 安卓13(33)以上操作剪切板会自动提示，无需手动toast
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2){
            toast("已复制路径到剪切板")
        }
    }

    fun deleteSelections(items: List<DownloadInfo>) = viewModelScope.launch {
        val service = DownloadService.getService(fragment.requireContext())
        // ★统计"真正删掉了几份"（私有 + 公共两边都可能有一份），删不到就如实说 ——
        //   原来是"选了 N 个文件夹就报已删除 N 项"，遇到身份失配（发布后路径从绝对变相对）会谎报
        // ★按"条目"计数，不按"文件份数"：一集发布到公共目录后是 video/audio/danmaku/index/entry 约 5 个文件，
        //   直接累加会提示"已删除 5 个视频文件"，批量删 30 集就是"150 个" —— 用户数的是视频，不是文件。
        var deletedItems = 0
        items.forEach { info ->
            info.items.forEach { item ->
                try {
                    if (service.deleteDownload(info.dir_path, item.dir_path) > 0) deletedItems++
                } catch (_: Exception) {}
            }
        }
        toast(
            if (deletedItems > 0) "已删除 $deletedItems 个视频"
            else "没有找到可删除的文件（可能已被移动或删除）"
        )
        _loadDownloadList(service)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadListPageContent(
    viewModel: DownloadListPageViewModel
) {
    // 打开下载页时请求一次老系统（API 23~28）的存储权限：授权后才能把下载好的视频发布到公共目录
    LegacyStoragePermissionEffect()
    var isEditMode by remember { mutableStateOf(false) }
    val selectedDirs = remember { mutableStateListOf<String>() }

    // ★先把列表状态读出来：下面菜单"给不给编辑入口 / 给不给删除"都取决于它
    var status by remember { mutableStateOf(0) }
    val downloadList by viewModel.downloadList.collectAsStateWithLifecycle()
    val curDownload by viewModel.curDownload.collectAsStateWithLifecycle()
    // 读一下队列版本：队列变化时要重算每张卡片的"排队中/暂停中"
    val waitQueueVersion by viewModel.waitQueueVersion.collectAsStateWithLifecycle()
    val list = remember(downloadList, status) {
        viewModel.filterDownloadList(downloadList, status)
    }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isSearchMode by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    // 搜索过滤
    val filteredList = remember(list, searchText) {
        if (searchText.isBlank()) list
        else list.filter { info ->
            info.title.contains(searchText, ignoreCase = true) ||
            info.items.any { it.title.contains(searchText, ignoreCase = true) }
        }
    }
    // ★2026-10-02：**列表空的时候才不给"编辑"**（没东西可编辑，点进去只会看到"已选0项"）；
    //   有东西时必须给 —— 用户要靠它多选、一次性把整个下载文件夹删掉。
    val canEdit = filteredList.isNotEmpty()

    val pageConfigId = PageConfig(
        title = if (isEditMode) "已选${selectedDirs.size}项" else "下载列表",
        // selectedDirs.size 也要进 key：菜单里"删除"的显隐依赖它，只按 isEditMode 缓存会读到旧菜单
        menu = remember(isEditMode, canEdit, selectedDirs.size) {
            myMenu {
                if (isEditMode) {
                    myItem { key = 1; iconFileName = "ic_baseline_done_24"; title = "完成" }
                    // 一项都没选就不显示"删除"：原来点了会弹"选中的 0 项"、一个文件都不删还提示"已删除0项"
                    if (selectedDirs.isNotEmpty()) {
                        myItem { key = 3; iconFileName = "ic_baseline_delete_24"; title = "删除" }
                    }
                } else {
                    myItem { key = 0; iconFileName = "ic_baseline_lightbulb_24"; title = "提示" }
                    if (canEdit) {
                        myItem { key = 2; iconFileName = "ic_baseline_edit_24"; title = "编辑" }
                    }
                }
            }
        }
    )
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())
    val bottomAppBarHeight = windowStore.bottomAppBarHeightDp
    
    PageListener(
        pageConfigId,
        onMenuItemClick = { _, menuItem ->
            when(menuItem.key) {
                0 -> showHelpDialog = true
                1 -> { isEditMode = false; selectedDirs.clear() }
                2 -> { isEditMode = true; selectedDirs.clear() }
                3 -> {
                    // 双保险：真到这一步还是一项没选，就别说"删除"了
                    if (selectedDirs.isEmpty()) toast("请先选择要删除的项")
                    else showDeleteDialog = true
                }
                // 搜索已常驻显示
            }
        }
    )

    // 返回键：编辑/搜索模式 → 退出模式，否则 → 退出页面
    BackHandler(enabled = isEditMode) {
        isEditMode = false
        selectedDirs.clear()
    }

    if (showHelpDialog) {
        val downloadPath = viewModel.downloadPath
        OverlayAlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text(text = "下载保存位置") },
            text = {
                Column() {
                    // 这里是"文件最终保存在哪"（已发布的在公共目录 Download/BiliMiao，公共目录不可用时是应用私有目录）
                    Text(text = "保存位置：${downloadPath}")
                }
            },
            confirmButton = {
                Row() {
                    TextButton(
                        onClick = {
                            viewModel.copyDownloadPathToClipboard()
                            showHelpDialog = false
                        },
                    ) {
                        Text("复制路径")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showHelpDialog = false },
                ) {
                    Text("取消")
                }
            }
        )
    }


    // 删除确认弹窗
    if (showDeleteDialog) {
        OverlayAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除选中的 ${selectedDirs.size} 项吗？\n文件将被永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelections(list.filter { it.dir_path in selectedDirs })
                    selectedDirs.clear(); isEditMode = false; showDeleteDialog = false
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 搜索栏
            if (!isEditMode) {
                // 公共搜索框组件（下载面板也用同一个，见 components/DownloadSearchBox.kt）
                DownloadSearchBox(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = "搜索下载标题",
                    modifier = Modifier
                        .padding(top = windowInsets.topDp.dp)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
            // 全选行（编辑模式）
            if (isEditMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = windowInsets.topDp.dp)
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                        .padding(start = windowInsets.leftDp.dp, end = windowInsets.rightDp.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = selectedDirs.size == filteredList.size && filteredList.isNotEmpty(),
                        onCheckedChange = { checked ->
                            if (checked) {
                                // 去重：手选过几项再点"全选"，否则 selectedDirs 会有重复项、"已选 N 项"虚高
                                filteredList.forEach { info ->
                                    if (info.dir_path !in selectedDirs) selectedDirs.add(info.dir_path)
                                }
                            } else selectedDirs.clear()
                        }
                    )
                    Text(
                        text = if (selectedDirs.isEmpty()) "全选" else "已选 ${selectedDirs.size} 项",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            LazyColumn(
            modifier = Modifier.fillMaxWidth()
                .weight(1f)
                .padding(start = windowInsets.leftDp.dp, end = windowInsets.rightDp.dp)
        ) {

            if (!isEditMode) {
                item {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = status == 0, onClick = { status = 0 }, label = { Text("全部") })
                        FilterChip(selected = status == 1, onClick = { status = 1 }, label = { Text("下载中") })
                        FilterChip(selected = status == 2, onClick = { status = 2 }, label = { Text("下载完成") })
                    }
                }
            }
            items(filteredList, key = { it.dir_path }) { info ->
                DownloadListItem(
                    curDownload = curDownload, item = info,
                    // waitQueueVersion 作 key：队列变化时重算，其它时候不重复算
                    queued = remember(waitQueueVersion, curDownload) { viewModel.isQueuedGroup(info) },
                    onClick = { viewModel.toDetailPage(info) },
                    selectMode = isEditMode,
                    selected = info.dir_path in selectedDirs,
                    onSelect = { if (info.dir_path in selectedDirs) selectedDirs.remove(info.dir_path) else selectedDirs.add(info.dir_path) },
                )
            }
            item { Spacer(modifier = Modifier.height(windowInsets.bottomDp.dp + bottomAppBarHeight.dp)) }
        }
        }
    }

}

