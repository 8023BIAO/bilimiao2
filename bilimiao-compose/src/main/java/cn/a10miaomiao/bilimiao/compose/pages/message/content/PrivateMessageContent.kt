package cn.a10miaomiao.bilimiao.compose.pages.message.content

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bilibili.im.type.SessionInfo
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localPageNavigation
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.pages.message.ChatPage
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.store.WindowStore
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import java.text.SimpleDateFormat
import java.util.*

// ════════════════════════════════════════════════════════════
// 全局用户信息缓存（进程级，ChatPage 复用；LRU 淘汰）
// ════════════════════════════════════════════════════════════
internal object UserInfoCache {
    private const val MAX_SIZE = 512
    private val cache = object : LinkedHashMap<Long, AccInfoData>(MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, AccInfoData>?): Boolean {
            return size > MAX_SIZE
        }
    }

    private var saveFile: java.io.File? = null
    private val saveJson = Json { ignoreUnknownKeys = true }
    private var saveJob: kotlinx.coroutines.Job? = null
    private val saveScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /** 🔧 在应用启动时调用，从文件恢复缓存 */
    @Synchronized fun init(file: java.io.File) {
        saveFile = file
        try {
            if (file.exists()) {
                val text = file.readText()
                if (text.isNotBlank()) {
                    val list: List<AccInfoData> = saveJson.decodeFromString(text)
                    list.forEach { cache[it.mid] = it }
                }
            }
        } catch (_: Exception) {}
    }

    /** 🔧 防抖持久化：3 秒内多次写入合并为一次 */
    @Synchronized private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = saveScope.launch {
            kotlinx.coroutines.delay(3000)
            performSave()
        }
    }

    @Synchronized private fun performSave() {
        try {
            val file = saveFile ?: return
            val list = cache.values.toList()
            file.writeText(saveJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(AccInfoData.serializer()), list))
        } catch (_: Exception) {}
    }

    @Synchronized fun get(uid: Long) = cache[uid]
    @Synchronized fun put(uid: Long, info: AccInfoData) { cache[uid] = info; scheduleSave() }
    @Synchronized fun contains(uid: Long) = cache.containsKey(uid)
    @Synchronized fun putAll(map: Map<Long, AccInfoData>) { cache.putAll(map); scheduleSave() }
}

// 全局刷新事件（ChatPage发消息后触发，PrivateMessageContent监听）
// 🔧 使用递增计数器替代时间戳，避免系统时间回拨导致相同值不触发刷新
internal object MessageRefreshEvent {
    private val _needRefresh = MutableStateFlow(0L)
    val needRefresh: StateFlow<Long> = _needRefresh
    private var counter = 0L
    fun trigger() {
        counter++
        _needRefresh.value = counter
    }
}

// ════════════════════════════════════════════════════════════
// JSON 解析 & 数据类
// ════════════════════════════════════════════════════════════

// 🔧 复用 Json 实例，避免每条消息重复创建
private val parseJson = Json { ignoreUnknownKeys = true }

// 解析 B站私信 JSON content → 纯文字
// 🔧 检测系统提示（含 color_day/color_nig → 隐藏）
private fun parseMsgContent(raw: String): String {
    if (raw.isBlank()) return ""
    if (raw.startsWith("[")) return ""
    if (raw.contains("\"color_day\"") || raw.contains("\"color_nig\"")) return ""
    return try {
        @Serializable data class C(val content: String = "")
        val c = parseJson.decodeFromString<C>(raw)
        if (c.content.isNotBlank()) {
            if (c.content.startsWith("[")) return ""
            if (c.content.contains("\"color_day\"") || c.content.contains("\"color_nig\"")) return ""
            return c.content
        }
        @Serializable data class Sys(val title: String = "", val text: String = "")
        val s = parseJson.decodeFromString<Sys>(raw)
        s.text.ifBlank { s.title.ifBlank { "" } }
    } catch (_: Exception) { "" }
}

// 用户名片（头像+昵称）
@Serializable
data class AccInfoData(
    val mid: Long = 0,
    val name: String = "",
    val face: String = "",
)

// 🔧 自包含的展示数据（不再依赖 ChatSessionInfo，gRPC 直接映射）
data class ChatSessionDisplay(
    val talkerId: Long,
    val userName: String = "",
    val userFace: String = "",
    val lastMsgContent: String = "",
    val lastMsgTimestamp: Long = 0L,
    val unreadCount: Int = 0,
)

// 系统 UID 列表
private val SYSTEM_UIDS = setOf(475210L, 68962L)

// 🔧 复用 SimpleDateFormat（ThreadLocal 保证线程安全）
private val timeFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
}

// ════════════════════════════════════════════════════════════
// ViewModel
// ════════════════════════════════════════════════════════════

private class PrivateMessageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val activity: android.app.Activity by instance()
    val isRefreshing = MutableStateFlow(false)
    val list = FlowPaginationInfo<ChatSessionDisplay>()

    init {
        val cacheFile = java.io.File(activity.cacheDir, "user_info_cache.json")
        UserInfoCache.init(cacheFile)
        loadData()
    }

    /**
     * 🔧 优先使用 gRPC（参考 PiliPlus），一次请求拿到 SessionInfo（含 AccountInfo: 头像+昵称）
     *   失败时回退到 REST API
     */
    fun loadData() = viewModelScope.launch(Dispatchers.IO) {
        try {
            list.loading.value = true
            try {
                loadDataGrpc()
            } catch (e: Exception) {
                // gRPC 失败 → 回退 REST
                loadDataRest()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (e !is java.net.UnknownHostException) {
                list.fail.value = e.message ?: e.toString()
            }
        } finally {
            list.loading.value = false
            isRefreshing.value = false
        }
    }

    /**
     * gRPC GetSessions（参考 PiliPlus 的 SessionMain）
     * SessionInfo 自带 accountInfo (name + picUrl)，无需额外查用户信息
     */
    private suspend fun loadDataGrpc() {
        val response = BiliApiService.messageApi
            .sessionsGrpc()
            .awaitCall()
        val sessionList = response.sessionList
            .filter { it.systemMsgType == 0 && it.talkerId !in SYSTEM_UIDS }

        // 第一遍：gRPC 返回的 accountInfo 已有数据直接填，缺失的先占位
        val rawList = sessionList.map { session ->
            val accInfo = session.accountInfo
            val name = accInfo?.name?.takeIf { it.isNotBlank() } ?: ""
            val face = accInfo?.picUrl ?: ""
            if (face.isNotBlank()) {
                UserInfoCache.put(session.talkerId, AccInfoData(session.talkerId, name, face))
            }
            ChatSessionDisplay(
                talkerId = session.talkerId,
                userName = name,
                userFace = face,
                lastMsgContent = session.lastMsg?.content ?: "",
                lastMsgTimestamp = session.lastMsg?.timestamp ?: 0L,
                unreadCount = session.unreadCount,
            )
        }
        // 🔧 先用已有数据（gRPC + 已有缓存）填充显示，不等待异步补全
        list.data.value = rawList.map { it ->
            if (it.userFace.isBlank()) {
                val cached = UserInfoCache.get(it.talkerId)
                if (cached != null && cached.face.isNotBlank()) {
                    it.copy(userName = cached.name, userFace = cached.face)
                } else {
                    it.copy(userName = it.userName.ifBlank { "UID:${it.talkerId}" })
                }
            } else it
        }
        // 🔧 异步补全缺失的用户信息，全部完成后在主线程直接重建列表
        val needFetch = rawList
            .filter { it.userFace.isBlank() && UserInfoCache.get(it.talkerId)?.face.isNullOrBlank() }
            .map { it.talkerId }.distinct()
        if (needFetch.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    var hasNew = false
                    needFetch.chunked(10).forEach { batch ->
                        batch.map { uid ->
                            async(Dispatchers.IO) {
                                try {
                                    val res = BiliApiService.userApi
                                        .accInfo(uid.toString())
                                        .awaitCall()
                                        .json<ResultInfo<AccInfoData>>()
                                    if (res.isSuccess && res.data != null) {
                                        UserInfoCache.put(uid, res.data!!)
                                        hasNew = true
                                    }
                                } catch (_: Exception) {}
                            }
                        }.awaitAll()
                    }
                    // 全部fetch完成后，直接清空data再重新设值，强制Compose感知变化
                    if (hasNew) {
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            // 创建全新的ArrayList触发StateFlow变化
                            val current = list.data.value
                            val updated = java.util.ArrayList<ChatSessionDisplay>(current.size)
                            for (item in current) {
                                if (item.userFace.isBlank()) {
                                    val cached = UserInfoCache.get(item.talkerId)
                                    if (cached != null && cached.face.isNotBlank()) {
                                        updated.add(item.copy(
                                            userName = cached.name.ifBlank { "UID:${item.talkerId}" },
                                            userFace = cached.face
                                        ))
                                    } else {
                                        updated.add(item)
                                    }
                                } else {
                                    updated.add(item)
                                }
                            }
                            list.data.value = updated
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        list.finished.value = sessionList.isEmpty() || response.hasMore == 0
    }

    /**
     * REST API 回退方案（保留兼容）
     */
    private suspend fun loadDataRest() {
        val res = BiliApiService.messageApi
            .sessions()
            .awaitCall()
            .json<com.a10miaomiao.bilimiao.comm.entity.ResultInfo<com.a10miaomiao.bilimiao.comm.entity.message.ChatSessionResponse>>()
        if (res.isSuccess) {
            val sessions: List<com.a10miaomiao.bilimiao.comm.entity.message.ChatSessionInfo> = res.data?.session_list
                ?.filter { it.system_msg_type == 0 && it.talker_id !in SYSTEM_UIDS }
                ?: emptyList()

            // 先用已有缓存填充显示（不等待异步补全）
            val displayList = sessions.map { session ->
                val info = UserInfoCache.get(session.talker_id)
                ChatSessionDisplay(
                    talkerId = session.talker_id,
                    userName = info?.name ?: "UID:${session.talker_id}",
                    userFace = info?.face ?: "",
                    lastMsgContent = session.last_msg?.content ?: "",
                    lastMsgTimestamp = session.last_msg?.timestamp ?: 0L,
                    unreadCount = session.unread_count,
                )
            }
            list.data.value = displayList

            // 异步补全缺失的用户信息，每批完成后在主线程刷新
            val newUids = sessions.map { it.talker_id }
                .filter { UserInfoCache.get(it)?.face.isNullOrBlank() }
                .distinct()
            if (newUids.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        newUids.chunked(10).forEach { batch ->
                            var hasNew = false
                            batch.map { uid ->
                                async(Dispatchers.IO) {
                                    try {
                                        val res = BiliApiService.userApi
                                            .accInfo(uid.toString())
                                            .awaitCall()
                                            .json<ResultInfo<AccInfoData>>()
                                        if (res.isSuccess && res.data != null) {
                                            UserInfoCache.put(uid, res.data!!)
                                            hasNew = true
                                        }
                                    } catch (_: Exception) {}
                                }
                            }.awaitAll()
                            if (hasNew) {
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    val current = list.data.value
                                    val updated = java.util.ArrayList<ChatSessionDisplay>(current.size)
                                    for (item in current) {
                                        if (item.userFace.isBlank()) {
                                            val cached = UserInfoCache.get(item.talkerId)
                                            if (cached != null && cached.face.isNotBlank()) {
                                                updated.add(item.copy(
                                                    userName = cached.name.ifBlank { "UID:${item.talkerId}" },
                                                    userFace = cached.face
                                                ))
                                            } else {
                                                updated.add(item)
                                            }
                                        } else {
                                            updated.add(item)
                                        }
                                    }
                                    list.data.value = updated
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            val data: com.a10miaomiao.bilimiao.comm.entity.message.ChatSessionResponse? = res.data
            list.finished.value = sessions.isEmpty() || data?.has_more == 0
        } else {
            val errMsg: String = res.message
            list.fail.value = errMsg.ifBlank { "加载私信失败" }
        }
    }

    fun refresh() {
        isRefreshing.value = true
        list.fail.value = ""
        loadData()
    }

    fun removeSession(talkerId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                BiliApiService.messageApi
                    .removeSession(talkerId)
                    .awaitCall()
            } catch (_: Exception) {}
        }
        val current = list.data.value.toMutableList()
        current.removeAll { it.talkerId == talkerId }
        list.data.value = current
    }
}

// ════════════════════════════════════════════════════════════
// UI
// ════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class, ExperimentalGlideComposeApi::class)
@Composable
internal fun PrivateMessageContent() {
    val viewModel: PrivateMessageViewModel = diViewModel()
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val list by viewModel.list.data.collectAsState()
    val listLoading by viewModel.list.loading.collectAsState()
    val listFinished by viewModel.list.finished.collectAsState()
    val listFail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val pageNavigation = localPageNavigation()

    val deleteTarget = remember { mutableStateOf<ChatSessionDisplay?>(null) }

    // 监听从ChatPage发来的刷新事件
    val refreshTrigger by MessageRefreshEvent.needRefresh.collectAsState()
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0L) viewModel.refresh()
    }

    // 确认删除弹窗
    deleteTarget.value?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget.value = null },
            title = { Text("确认移除聊天") },
            text = { Text("确定要移除 ${target.userName} 的聊天吗？\n移除后聊天记录将不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeSession(target.talkerId)
                    deleteTarget.value = null
                }) {
                    Text("确认移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget.value = null }) {
                    Text("取消")
                }
            },
        )
    }

    SwipeToRefresh(
        modifier = Modifier.padding(
            start = windowInsets.leftDp.dp,
            end = windowInsets.rightDp.dp,
        ),
        refreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = windowInsets.bottomDp.dp),
        ) {
            items(list, key = { it.talkerId }) { chat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                pageNavigation.navigate(
                                    ChatPage(
                                        talkerId = chat.talkerId,
                                        talkerName = chat.userName,
                                        talkerFace = chat.userFace,
                                    )
                                )
                            },
                            onLongClick = { /* TODO: 长按菜单 */ },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 🔧 加 loading 占位图，避免网络加载时显示空白
                    GlideImage(
                        model = chat.userFace.ifEmpty { null },
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop,
                        loading = placeholder(cn.a10miaomiao.bilimiao.compose.R.drawable.bili_default_placeholder_img_tv),
                        failure = placeholder(cn.a10miaomiao.bilimiao.compose.R.drawable.bili_default_placeholder_img_tv),
                        requestBuilderTransform = { it.apply(RequestOptions.diskCacheStrategyOf(DiskCacheStrategy.ALL)) },
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    // 名称 + 最后消息
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = chat.userName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (chat.lastMsgTimestamp > 0L) {
                                    timeFormatter.get().format(Date(chat.lastMsgTimestamp * 1000L))
                                } else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SelectionContainer {
                                Text(
                                    text = parseMsgContent(chat.lastMsgContent),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    // 红色移除按钮
                    IconButton(
                        onClick = { deleteTarget.value = chat },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "移除聊天",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                // 底部卡片间距
                Spacer(modifier = Modifier.height(2.dp))
            }
            item {
                ListStateBox(
                    modifier = Modifier.padding(bottom = windowInsets.bottomDp.dp),
                    loading = listLoading,
                    finished = listFinished,
                    fail = listFail,
                    listData = list,
                ) {
                    viewModel.loadData()
                }
            }
        }
    }
}
