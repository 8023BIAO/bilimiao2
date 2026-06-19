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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.pages.download.components.DownloadListItem
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
                itemTitle = page.part ?: "unknown"
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
                        title = biliEntry.title,
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
        items.forEach { info ->
            info.items.forEach { item ->
                try { service.deleteDownload(info.dir_path, item.dir_path) } catch (_: Exception) {}
            }
        }
        toast("已删除${items.size}项")
        _loadDownloadList(service)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadListPageContent(
    viewModel: DownloadListPageViewModel
) {
    var isEditMode by remember { mutableStateOf(false) }
    val selectedDirs = remember { mutableStateListOf<String>() }

    val pageConfigId = PageConfig(
        title = if (isEditMode) "已选${selectedDirs.size}项" else "下载列表",
        menu = remember(isEditMode) {
            myMenu {
                if (isEditMode) {
                    myItem { key = 1; iconFileName = "ic_baseline_done_24"; title = "完成" }
                    myItem { key = 3; iconFileName = "ic_baseline_delete_24"; title = "删除" }
                } else {
                    myItem { key = 0; iconFileName = "ic_baseline_lightbulb_24"; title = "提示" }
                    myItem { key = 2; iconFileName = "ic_baseline_edit_24"; title = "编辑" }
                }
            }
        }
    )
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())
    val bottomAppBarHeight = windowStore.bottomAppBarHeightDp

    var status by remember { mutableStateOf(0) }
    val downloadList by viewModel.downloadList.collectAsState()
    val curDownload by viewModel.curDownload.collectAsState()
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
    
    PageListener(
        pageConfigId,
        onMenuItemClick = { _, menuItem ->
            when(menuItem.key) {
                0 -> showHelpDialog = true
                1 -> { isEditMode = false; selectedDirs.clear() }
                2 -> isEditMode = true
                3 -> showDeleteDialog = true
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
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text(text = "下载路径") },
            text = {
                Column() {
                    Text(text = "下载路径：${downloadPath}")
                   // Text(text = "文件已保存到公共下载目录，无需额外工具导出")
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
        AlertDialog(
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
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = windowInsets.topDp.dp)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    singleLine = true,
                    placeholder = { Text("搜索下载标题", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索", tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingIcon = {
                        if (searchText.isNotEmpty()) {
                            IconButton(onClick = { searchText = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "清空", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
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
                            if (checked) filteredList.forEach { selectedDirs.add(it.dir_path) }
                            else selectedDirs.clear()
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

