package cn.a10miaomiao.bilimiao.compose.pages.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localPageNavigation
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.mypage.rememberMyMenu
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.pages.community.components.EmojiGridBox
import cn.a10miaomiao.bilimiao.compose.components.dialogs.AutoSheetDialog
import cn.a10miaomiao.bilimiao.compose.pages.message.content.MessageRefreshEvent
import cn.a10miaomiao.bilimiao.compose.pages.message.content.UserInfoCache
import cn.a10miaomiao.bilimiao.compose.pages.message.content.AccInfoData
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.message.ChatMsgInfo
import com.a10miaomiao.bilimiao.comm.entity.message.ChatMsgResponse
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.store.MessageStore
import com.a10miaomiao.bilimiao.store.WindowStore
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class ChatPage(
    val talkerId: Long,
    val talkerName: String = "",
    val talkerFace: String = "",
) : ComposePage() {

    @OptIn(ExperimentalGlideComposeApi::class)
    @Composable
    override fun Content() {
        val userStore: UserStore by rememberInstance()
        if (userStore.isLogin()) {
            ChatPageContent(talkerId, talkerName, talkerFace)
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("请先登录", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ─── JSON解析 ────────────────────────────────────────────────

private fun debugJson(raw: String): String {
    if (raw.length > 200) return raw.take(200) + "..."
    return raw
}

private val parseJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
data class ParsedMsg(val text: String, val toastText: String = "")

private fun parseMsgText(raw: String): ParsedMsg {
    if (raw.isBlank()) return ParsedMsg("")
    // 数组格式 → 提取text用于toast，消息本身隐藏
    if (raw.startsWith("[")) {
        return try {
            @Serializable data class Hint(val text: String = "")
            val list = parseJson
                .decodeFromString<List<Hint>>(raw)
            val tipText = list.firstOrNull()?.text ?: ""
            ParsedMsg("", toastText = tipText)
        } catch (_: Exception) { ParsedMsg("") }
    }
    // 对象格式
    return try {
        @Serializable data class C(val content: String = "")
        val c = parseJson
            .decodeFromString<C>(raw)
        if (c.content.isNotBlank()) {
            // 嵌套数组 → 系统提示，隐藏气泡
            if (c.content.startsWith("[")) return ParsedMsg("")
            return ParsedMsg(c.content)
        }
        @Serializable data class Sys(val title: String = "", val text: String = "")
        val s = parseJson
            .decodeFromString<Sys>(raw)
        ParsedMsg(s.text.ifBlank { s.title.ifBlank { "[系统通知]" } })
    } catch (_: Exception) { ParsedMsg("[消息] " + raw.take(30)) }
}

// ─── ViewModel ───────────────────────────────────────────────

private class ChatViewModel(
    override val di: DI,
    private val talkerId: Long,
) : ViewModel(), DIAware {

    private val userStore: UserStore by instance()
    private val pageNavigation: PageNavigation by instance()
    private val messageStore: MessageStore by instance()
    val isRefreshing = mutableStateOf(false)
    val isSending = mutableStateOf(false)
    val list = FlowPaginationInfo<ChatMsgInfo>()
    // 用 TextFieldValue 而不是 String：String 拿不到光标位置，插表情只能永远追加到末尾
    val inputText = mutableStateOf(TextFieldValue(""))
    val showSendDialog = mutableStateOf(false)

    val myUid: Long get() = userStore.state.info?.mid ?: 0L
    val myFace: String get() = userStore.state.info?.face ?: ""

    val talkerName = mutableStateOf("")
    val talkerFace = mutableStateOf("")

    init {
        loadMsgs()
    }

    fun setTalkerInfo(name: String, face: String) {
        if (name.isNotBlank()) talkerName.value = name
        if (face.isNotBlank()) talkerFace.value = face
    }

    fun loadUserInfo(uid: Long) {
        if (uid <= 0L) return
        // 先查全局缓存
        val cached = UserInfoCache.get(uid)
        if (cached != null && cached.face.isNotBlank()) {
            talkerName.value = cached.name
            talkerFace.value = cached.face
            return
        }
        if (talkerName.value.isNotBlank() && talkerFace.value.isNotBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                @Serializable data class AccInfo(val mid: Long = 0, val name: String = "", val face: String = "")
                val res = BiliApiService.userApi
                    .accInfo(uid.toString())
                    .awaitCall()
                    .json<ResultInfo<AccInfo>>()
                if (res.isSuccess) {
                    val info = res.data
                    if (info != null) {
                        talkerName.value = info.name
                        talkerFace.value = info.face
                        // 写入全局缓存
                        UserInfoCache.put(uid, AccInfoData(mid = info.mid, name = info.name, face = info.face))
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun loadMsgs(beginSeqno: Long = 0) {
        viewModelScope.launch(Dispatchers.IO) { loadMsgsInternal(beginSeqno) }
    }

    /** 内部 suspend 版，供 sendMsg 串行调用避免竞态 */
    private suspend fun loadMsgsInternal(beginSeqno: Long = 0) {
        try {
            isRefreshing.value = true
            val res = BiliApiService.messageApi
                .fetchMsgs(talkerId = talkerId, beginSeqno = beginSeqno)
                .awaitCall()
                .json<ResultInfo<ChatMsgResponse>>()
            if (res.isSuccess) {
                val msgs = res.data?.messages ?: emptyList()
                val existing = list.data.value.toMutableList()
                msgs.forEach { msg ->
                    if (existing.none { it.msg_key == msg.msg_key }) existing.add(msg)
                }
                existing.sortBy { it.timestamp }
                list.data.value = existing
                // 读完就把私信未读清掉（否则首页角标永远有红点）
                try { messageStore.clearChatUnread() } catch (e: Exception) { }
                list.finished.value = res.data?.has_more != 1
            } else if (list.data.value.isEmpty()) {
                list.fail.value = res.message.ifBlank { "加载消息失败" }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (list.data.value.isEmpty()) if (e !is java.net.UnknownHostException) {
                list.fail.value = e.message ?: e.toString()
            }
        } finally {
            isRefreshing.value = false
        }
    }

    fun refresh() {
        list.fail.value = ""
        list.reset()
        loadMsgs()
    }

    fun loadMore() {
        if (list.finished.value || isRefreshing.value) return
        // seqno<=0 的是本地乐观消息，不能拿来当分页游标（否则会请求 beginSeqno=-1）。
        // 注意是"排除掉本地消息后再取最小值"，不是拿全局最小值去判断 ——
        // 后者只要列表里存在一条乐观消息（msg_seqno=0）就会整体 return，
        // 表现为发消息期间上滑永远拉不到更早的记录
        val minSeqno = list.data.value
            .filter { it.msg_seqno > 0L }
            .minOfOrNull { it.msg_seqno } ?: return
        if (minSeqno <= 1L) {
            // 已经到最早一条了：标记到底，底部才会显示"下面没有了"而不是一直转
            list.finished.value = true
            return
        }
        loadMsgs(beginSeqno = minSeqno - 1)
    }

    fun sendMsg() = viewModelScope.launch(Dispatchers.IO) {
        val text = inputText.value.text.trim()
        if (text.isEmpty()) return@launch
        // 提到 try 外：异常时也要能撤销这条本地乐观消息
        val fakeMsgKey = System.currentTimeMillis()
        try {
            isSending.value = true
            // 别手拼 JSON：多行文本（输入框允许换行）会拼出非法 JSON，服务端直接拒收
            val contentJson = kotlinx.serialization.json.buildJsonObject {
                put("content", kotlinx.serialization.json.JsonPrimitive(text))
            }.toString()
            val ts = System.currentTimeMillis() / 1000
            val csrf = BilimiaoCommApp.commApp.loginInfo?.cookie_info?.cookies
                ?.find { it.name == "bili_jct" }?.value
            if (csrf == null) {
                launch(Dispatchers.Main) { toast("未登录，无法发送") }
                isSending.value = false
                return@launch
            }

            // 乐观更新：本地先塞一条消息
            val localMsg = ChatMsgInfo(
                msg_key = fakeMsgKey,
                msg_type = 1,
                sender_uid = myUid,
                content = contentJson,
                timestamp = ts,
                msg_seqno = 0,
            )
            launch(Dispatchers.Main) {
                val cur = list.data.value.toMutableList()
                cur.add(localMsg)
                list.data.value = cur
            }

            // 发API
            val res = MiaoHttp.request {
                url = BiliApiService.biliVcApi("web_im/v1/web_im/send_msg")
                method = MiaoHttp.POST
                formBody = mapOf(
                    "msg[sender_uid]" to myUid.toString(),
                    "msg[receiver_id]" to talkerId.toString(),
                    "msg[receiver_type]" to "1",
                    "msg[msg_type]" to "1",
                    "msg[content]" to contentJson,
                    "msg[timestamp]" to ts.toString(),
                    "msg[dev_id]" to "bilimiao",
                    "csrf" to csrf,
                    "csrf_token" to csrf,
                )
            }.awaitCall().json<ResultInfo<Unit>>()
            if (res.isSuccess) {
                inputText.value = TextFieldValue("")
                showSendDialog.value = false
                // 先从API刷新获取真实数据，再移除本地假消息（避免竞态窗口）
                loadMsgsInternal()
                val cur = list.data.value.toMutableList()
                cur.removeAll { it.msg_key == fakeMsgKey }
                list.data.value = cur
                launch(Dispatchers.Main) { toast("发送成功") }
                // 通知私信列表刷新
                MessageRefreshEvent.trigger()
            } else {
                // 失败：移除本地假消息
                launch(Dispatchers.Main) {
                    val cur = list.data.value.toMutableList()
                    cur.removeAll { it.msg_key == fakeMsgKey }
                    list.data.value = cur
                    toast(res.message.ifBlank { "发送失败" })
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            // 异常时也要撤掉乐观更新的本地消息，否则它会永远留在列表里
            val cur = list.data.value.toMutableList()
            cur.removeAll { it.msg_key == fakeMsgKey }
            list.data.value = cur
            launch(Dispatchers.Main) { toast("发送失败: ${e.message}") }
        } finally {
            isSending.value = false
        }
    }

    // 跳转用户主页
    fun toUserPage(uid: Long) {
        pageNavigation.navigate(UserSpacePage(id = uid.toString()))
    }

    companion object {
        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dateTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    fun fmtTime(ts: Long): String {
        val cal = Calendar.getInstance()
        val msgCal = Calendar.getInstance().apply { timeInMillis = ts * 1000 }
        return if (cal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR) &&
            cal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR)
        ) timeFmt.format(Date(ts * 1000))
        else dateTimeFmt.format(Date(ts * 1000))
    }
}


// ─── UI ──────────────────────────────────────────────────────

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ChatPageContent(
    talkerId: Long, initialName: String, initialFace: String,
) {
    val viewModel: ChatViewModel = diViewModel(key = "chat-$talkerId") {
        ChatViewModel(di = it, talkerId = talkerId)
    }
    val windowStore by rememberInstance<WindowStore>()
    val windowState by windowStore.stateFlow.collectAsStateWithLifecycle()
    val windowInsets = windowState.getContentInsets(localContainerView())

    val list by viewModel.list.data.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing
    val listState = rememberLazyListState()

    LaunchedEffect(talkerId) {
        viewModel.setTalkerInfo(initialName, initialFace)
        if (initialFace.isBlank() || initialName.isBlank()) {
            viewModel.loadUserInfo(talkerId)
        }
    }

    // 只在“最新一条消息”变化时滚到底部：往上加载历史消息时不要打断用户
    val latestMsgKey = list.lastOrNull()?.msg_key
    // 首次贴底完成前不要触发补历史，否则刚进页面就会连着拉好几页
    var loadMoreArmed by remember { mutableStateOf(false) }
    LaunchedEffect(latestMsgKey) {
        // 注意：首帧列表还是空的（latestMsgKey == null）时不能放行 ——
        // 否则首屏到达后 effect 重启，snapshotFlow 立刻发出 index=0（贴底动画还没完成），
        // 进聊天页就会连着拉好几页历史
        if (latestMsgKey == null) return@LaunchedEffect
        listState.animateScrollToItem(list.lastIndex)
        loadMoreArmed = true
    }

    // 滚到顶部附近时继续加载更早的消息（历史消息在列表上方）
    LaunchedEffect(list.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (loadMoreArmed && index <= 1) viewModel.loadMore()
            }
    }

    val sendDialogVisible = viewModel.showSendDialog

    val talkerName = viewModel.talkerName.value.ifBlank { initialName.ifBlank { "聊天" } }
    val pageConfigId = PageConfig(
        title = talkerName,
        menu = rememberMyMenu {
            myItem {
                key = MenuKeys.send
                iconFileName = "ic_baseline_send_24"
                title = "发消息"
            }
        }
    )
    PageListener(
        configId = pageConfigId,
        onMenuItemClick = { _, item ->
            if (item.key == MenuKeys.send) {
                viewModel.showSendDialog.value = true
            }
        },
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(top = windowInsets.topDp.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = windowInsets.bottomDp.dp),
            ) {
                if (list.isEmpty() && !isRefreshing) {
                    item { EmptyHint("暂无消息") }
                }
                item { Spacer(Modifier.height(8.dp)) }
                items(list, key = { it.msg_key }) { msg ->
                    val parsed = remember(msg.msg_key) { parseMsgText(msg.content) }
                    // 系统提示数组→Toast
                    if (parsed.toastText.isNotBlank()) {
                        LaunchedEffect(msg.msg_key) {
                            toast(parsed.toastText)
                        }
                    }
                    // 有文本内容才渲染气泡
                    if (parsed.text.isNotBlank()) {
                        ChatBubble(
                            msg = msg,
                            isMe = msg.sender_uid == viewModel.myUid,
                            myFace = viewModel.myFace,
                            talkerFace = viewModel.talkerFace.value.ifBlank { initialFace },
                            talkerId = talkerId,
                            viewModel = viewModel,
                            timeText = viewModel.fmtTime(msg.timestamp),
                            msgText = parsed.text,
                        )
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
    }

    if (sendDialogVisible.value) {
        AutoSheetDialog(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp),
            content = { ChatSendPanel(viewModel) },
            onDismiss = { viewModel.showSendDialog.value = false },
        )
    }
}

// ─── 发送消息面板 ─────────────────────────────────────────────

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ChatSendPanel(vm: ChatViewModel) {
    val showEmoji = remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = vm.inputText.value,
            onValueChange = { vm.inputText.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 160.dp),
            placeholder = { Text("说点什么...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            shape = RoundedCornerShape(8.dp),
            maxLines = 5,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
            ),
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(
                onClick = { showEmoji.value = !showEmoji.value },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    Icons.Outlined.EmojiEmotions, "表情",
                    tint = if (showEmoji.value) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = { vm.sendMsg() },
                enabled = vm.inputText.value.text.isNotBlank() && !vm.isSending.value,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                if (vm.isSending.value) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    Text("发消息", fontSize = 14.sp)
                }
            }
        }

        AnimatedVisibility(visible = showEmoji.value) {
            EmojiGridBox(
                modifier = Modifier.height(260.dp).padding(top = 8.dp),
                onInputEmoji = { emoji ->
                    // 之前用 value.length 当插入点 → 光标在中间时表情也会被追加到末尾
                    val cur = vm.inputText.value
                    val text = cur.text
                    val start = cur.selection.min.coerceIn(0, text.length)
                    val end = cur.selection.max.coerceIn(0, text.length)
                    vm.inputText.value = TextFieldValue(
                        text.substring(0, start) + emoji.text + text.substring(end),
                        TextRange(start + emoji.text.length),
                    )
                }
            )
        }
    }
}

// ─── 单条消息气泡 ────────────────────────────────────────────

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ChatBubble(
    msg: ChatMsgInfo, isMe: Boolean, myFace: String, talkerFace: String,
    talkerId: Long, viewModel: ChatViewModel, timeText: String, msgText: String,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            if (!isMe) {
                // 对方头像可点击跳主页
                Avatar(talkerFace, onClick = {
                    if (talkerId > 0) viewModel.toUserPage(talkerId)
                })
                Spacer(Modifier.width(8.dp))
            }

            Column(horizontalAlignment = if (isMe) Alignment.End else Alignment.Start) {
                Surface(
                    shape = RoundedCornerShape(
                        topStart = if (isMe) 16.dp else 0.dp,
                        topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp,
                    ),
                    color = if (isMe) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    val parts = remember(msgText) { parseLinks(msgText) }
                    SelectionContainer {
                        Text(
                            text = buildAnnotatedString {
                                parts.forEach { (text, isLink) ->
                                    if (isLink) {
                                        // 之前只把链接染成链接样式，点了没反应；这里挂上真正的跳转
                                        // （默认走 LocalUriHandler → 站内链接进原生页，站外进浏览器）
                                        withLink(
                                            LinkAnnotation.Url(
                                                text,
                                                TextLinkStyles(
                                                    style = SpanStyle(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        textDecoration = TextDecoration.Underline,
                                                    )
                                                )
                                            )
                                        ) { append(text) }
                                    } else append(text)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                            color = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(timeText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (isMe) {
                Spacer(Modifier.width(8.dp))
                Avatar(myFace)
            }
        }
    }
}

// ─── 共用部件 ────────────────────────────────────────────────

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun Avatar(url: String, onClick: (() -> Unit)? = null) {
    // 🔧 使用默认占位图同时作为 loading 和 failure 状态，避免网络加载时显示空白
    val place = cn.a10miaomiao.bilimiao.compose.R.drawable.bili_default_placeholder_img_tv
    GlideImage(
        model = url.ifEmpty { null },
        contentDescription = null,
        modifier = Modifier.size(40.dp).clip(CircleShape).let {
            if (onClick != null) it.clickable(onClick = onClick) else it
        },
        contentScale = ContentScale.Crop,
        loading = placeholder(place),
        failure = placeholder(place),
        requestBuilderTransform = { it.apply(RequestOptions.diskCacheStrategyOf(DiskCacheStrategy.ALL)) },
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun parseLinks(text: String): List<Pair<String, Boolean>> {
    val urlPattern = Regex("https?://[\\w./?=&\\-+#%]+")
    val result = mutableListOf<Pair<String, Boolean>>()
    var lastEnd = 0
    urlPattern.findAll(text).forEach { match ->
        if (match.range.first > lastEnd) result.add(text.substring(lastEnd, match.range.first) to false)
        result.add(match.value to true)
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) result.add(text.substring(lastEnd) to false)
    return result.ifEmpty { listOf(text to false) }
}
