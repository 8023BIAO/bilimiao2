package cn.a10miaomiao.bilimiao.compose.pages.community

import android.content.Context
import android.view.View
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bilibili.main.community.reply.v1.CursorReply
import bilibili.main.community.reply.v1.CursorReq
import bilibili.main.community.reply.v1.MainListReq
import bilibili.main.community.reply.v1.ReplyGRPC
import bilibili.main.community.reply.v1.ReplyInfo
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.pages.web.WebPage
import cn.a10miaomiao.bilimiao.compose.components.dialogs.MessageDialogState
import cn.a10miaomiao.bilimiao.compose.pages.community.components.ReplyEditDialogState
import cn.a10miaomiao.bilimiao.compose.pages.community.components.CommentAntifraudLauncher
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import com.a10miaomiao.bilimiao.comm.entity.MessageInfo
import com.a10miaomiao.bilimiao.comm.entity.comm.PaginationInfo
import com.a10miaomiao.bilimiao.comm.entity.video.VideoCommentReplyInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.mypage.myMenu
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.toast
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.a10miaomiao.bilimiao.comm.utils.ClickGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

class MainReplyViewModel(
    override val di: DI,
    /** 当前评论区对象 id（视频 aid / 动态 id / 专栏 cid…）。可被 [switchTarget] 换掉 */
    var oid: String,
    /** 评论区类型（1=视频，11=动态，12=专栏…）。可被 [switchTarget] 换掉 */
    var type: Int,
    var extra: String = "",
    var filterTagName: String = "",
) : ViewModel(), DIAware {

    private val pageNavigation: PageNavigation by instance()
    private val messageDialog: MessageDialogState by instance()
    private val userStore: UserStore by instance()

    val editDialogState = ReplyEditDialogState(
        scope = viewModelScope,
        onAddReply = ::addNewReply,
        // 评论反诈：判定"仅自己可见"后点「去申诉」→ 内置浏览器打开官方申诉页（带着 App 的登录态）
        onOpenAppeal = { _, _, _ ->
            pageNavigation.navigate(WebPage(CommentAntifraudLauncher.APPEAL_URL))
        },
    )

    private var _sortOrder = MutableStateFlow(3)
    val sortOrder: StateFlow<Int> get() = _sortOrder
    val sortOrderList = listOf(
        2 to "按时间",
        3 to "按热度",
    )

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> get() = _isRefreshing
    val list = FlowPaginationInfo<ReplyInfo>()
    private val _upMid = MutableStateFlow(-1L)
    val upMid: StateFlow<Long> get() = _upMid
    private var _cursor: CursorReply? = null

    private val _currentReply = MutableStateFlow<ReplyInfo?>(null)
    val currentReply: StateFlow<ReplyInfo?> get() = _currentReply

    private val _replyCount = MutableStateFlow(0L)
    val replyCount: StateFlow<Long> get() = _replyCount

    /** 当前在途的列表请求：刷新/切排序时先把它取消掉 */
    private var loadJob: Job? = null

    init {
        loadJob = loadData()
    }

    private fun addNewReply(reply: VideoCommentReplyInfo) {
        // TODO: 自定义通用Reply实体类
        _sortOrder.value = 2
        refreshList()
    }

    fun removeReplyItem(reply: ReplyInfo) {
        val newList = list.data.value.toMutableList()
        val index = newList.indexOfFirst {
            it.id == reply.id
        }
        if (index != -1) {
            newList.removeAt(index)
        }
        list.data.value = newList
    }

    private fun loadData() = viewModelScope.launch(Dispatchers.IO) {
        try {
            list.loading.value = true
            list.fail.value = ""   // 开始加载就清掉上一次的失败提示
            val req = MainListReq(
                oid = oid.toLong(),
                type = type.toLong(),
                rpid = 0,
                extra = extra,
                filterTagName = filterTagName,
                cursor = CursorReq(
                    mode = bilibili.main.community.reply.v1.Mode.fromValue(sortOrder.value),
                    next = _cursor?.next ?: 0,
                )
            )
            val res = BiliGRPCHttp.request {
                ReplyGRPC.mainList(req)
            }.awaitCall()
            val listData = list.data.value.toMutableList()
            if (_cursor == null) {
                res.upTop?.let {
                    listData.add(it)
                }
                res.adminTop?.let {
                    listData.add(it)
                }
                res.voteTop?.let {
                    listData.add(it)
                }
            }
            res.subjectControl?.let {
                _upMid.value = it.upMid
                _replyCount.value = it.count
            }
            val replies = res.replies.filter { i1 ->
                listData.indexOfFirst { i2 -> i1.id == i2.id } == -1
            }
            listData.addAll(replies)
            list.data.value = listData
            _cursor = res.cursor
            if (res.cursor?.isEnd == true) {
                list.finished.value = true
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            if (e !is java.io.IOException || (e.message?.contains("gRPC") != true)) {
                list.fail.value = e.message ?: e.toString()
            }
        } finally {
            // 被取消的旧请求不要复位标志位：否则会把新请求刚设上的 loading 清掉，
            // 用户又能触发一次 loadMore（并发叠加）
            if (isActive) {
                list.loading.value = false
                _isRefreshing.value = false
            }
        }
    }

    fun loadMore() {
        if (!this.list.finished.value && !this.list.loading.value) {
            loadJob = loadData()
        }
    }

    /**
     * 换评论区目标（同一个页面被复用来显示另一个视频/动态的评论）：**复用同一个 VM**，不新建。
     *
     * 为什么要它：列表页以前用 `diViewModel(key = oid)` 建 VM —— 页面（nav entry）被复用、
     * 只有 oid 变时，每换一个目标就建一个新 VM，旧的全部留在 ViewModelStore 里到页面退出，
     * 而且每个 VM 的 init 都会立刻发一次评论请求（连播 50 集 = 50 个 VM + 50 次请求）。
     *
     * 这里把"上一目标"的页面级状态全部清掉再重新加载：
     * 分页与列表（[refreshList] 里 reset）、置顶/UP 主/总数、当前展开的楼中楼。
     * 排序选择（sortOrder）保留 —— 那是用户的阅读偏好，换视频不该被重置。
     */
    fun switchTarget(oid: String, type: Int, extra: String = "", filterTagName: String = "") {
        if (this.oid == oid && this.type == type &&
            this.extra == extra && this.filterTagName == filterTagName
        ) {
            return   // 首次组合也会调到这里：目标没变就别重复请求
        }
        this.oid = oid
        this.type = type
        this.extra = extra
        this.filterTagName = filterTagName
        _upMid.value = -1L
        _replyCount.value = 0L
        _currentReply.value = null
        // refreshing = false：换目标走普通首屏加载，不显示"下拉刷新"转圈
        refreshList(refreshing = false)
    }

    fun refreshList(
        refreshing: Boolean = true,
    ) {
        // ★ 先取消在途请求。以前不取消：切排序/下拉刷新会和上一次请求并发，旧响应回来照样写
        //   _cursor / list.data / finished —— 表现是"排序混杂""下面没有了提前出现"。
        loadJob?.cancel()
        list.reset()
        _cursor = null
        _isRefreshing.value = refreshing
        loadJob = loadData()
    }

    fun likeReply(reply: ReplyInfo) {
        val index = list.data.value.indexOfFirst {
            it.id == reply.id
        }
        if (index != -1) {
            likeReplyAt(index)
        }
    }

    fun likeReplyAt(index: Int) = viewModelScope.launch(Dispatchers.IO) {
        if (!userStore.isLogin()) {
            toast("请先登录")
            return@launch
        }
        // ★ 防连点：连点两次时第二次读到的还是没更新的旧 action，会把同一个点赞请求发两遍。
        //   同一楼层同一时刻只放一个请求进去（请求结束即释放）。
        val likeKey = "reply:like:$index"
        if (!ClickGuard.enter(likeKey)) return@launch
        try {
            val item = list.data.value[index]
            val isLike = item.replyControl?.action == 1L
            val newAction = if (isLike) 0 else 1
            val res = BiliApiService.commentApi
                // 这里原来写死 1（视频评论区类型），动态(17)/专栏等页面点赞必然失败；
                // 本 ViewModel 的 type 就是当前评论区类型
                .action(type, item.oid.toString(), item.id.toString(), newAction)
                .awaitCall()
                .json<MessageInfo>()
            if (res.isSuccess) {
                val likeNum = if (isLike) item.like - 1 else item.like + 1
                val newItem = item.copy(
                    replyControl = item.replyControl?.copy(
                        action = newAction.toLong(),
                    ),
                    like = likeNum,
                )
                val newList = list.data.value.toMutableList()
                newList[index] = newItem
                list.data.value = newList
                if (currentReply.value?.id == newItem.id) {
                    _currentReply.value = newItem
                }
            } else {
                toast(res.message)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            toast("加载失败:" + (e.message ?: e.toString()))
        } finally {
            ClickGuard.leave(likeKey)
        }
    }

    // 用评论ID点赞，避免index错位
    fun likeReplyById(rpid: Long) = viewModelScope.launch(Dispatchers.IO) {
        if (!userStore.isLogin()) {
            toast("请先登录")
            return@launch
        }
        // 同上：按 rpid 的入口也要防连点（列表里连点同一个赞按钮）
        val likeKey = "reply:like:$rpid"
        if (!ClickGuard.enter(likeKey)) return@launch
        try {
            val index = list.data.value.indexOfFirst { it.id == rpid }
            if (index == -1) return@launch
            val item = list.data.value[index]
            val isLike = item.replyControl?.action == 1L
            val newAction = if (isLike) 0 else 1
            val res = BiliApiService.commentApi
                // 这里原来写死 1（视频评论区类型），动态(17)/专栏等页面点赞必然失败；
                // 本 ViewModel 的 type 就是当前评论区类型
                .action(type, item.oid.toString(), item.id.toString(), newAction)
                .awaitCall()
                .json<MessageInfo>()
            if (res.isSuccess) {
                val likeNum = if (isLike) item.like - 1 else item.like + 1
                val newItem = item.copy(
                    replyControl = item.replyControl?.copy(
                        action = newAction.toLong(),
                    ),
                    like = likeNum,
                )
                val newList = list.data.value.toMutableList()
                newList[index] = newItem
                list.data.value = newList
                if (currentReply.value?.id == newItem.id) {
                    _currentReply.value = newItem
                }
            } else {
                toast(res.message)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            toast("加载失败:" + (e.message ?: e.toString()))
        } finally {
            ClickGuard.leave(likeKey)
        }
    }

    fun deleteReply(
        reply: ReplyInfo
    ) {
        messageDialog.open(
            title = "提示",
            text = "确定要删除这条评论：${reply.content?.message}",
            confirmButton = {
                TextButton(
                    onClick = {
                        messageDialog.close()
                        requestDeleteReply(reply)
                    },
                ) {
                    Text("确定")
                }
            },
            closeText = "取消",
            showClose = true,
        )
    }

    fun requestDeleteReply(
        reply: ReplyInfo
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                messageDialog.loading("正在删除")
            }
            val res = BiliApiService.commentApi
                .del(
                    type = reply.type.toInt(),
                    oid = reply.oid.toString(),
                    rpid = reply.id.toString(),
                )
                .awaitCall()
                .json<MessageInfo>()
            if (res.isSuccess) {
                withContext(Dispatchers.Main) {
                    toast("删除成功")
                    messageDialog.close()
                    if (currentReply.value?.id == reply.id) {
                        _currentReply.value = null
                    }
                    removeReplyItem(reply)
                }
            } else {
                withContext(Dispatchers.Main) {
                    TipDialog.show(res.message, WaitDialog.TYPE.WARNING)
                    messageDialog.close()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                // ★ 页面被销毁（返回/退出）时协程会被取消，走到这里。如果不关掉"正在删除"这个
                //   全屏遮罩：MessageDialogState 是 Fragment 级单例、MessageDialog 挂在 NavHost 之外，
                //   遮罩会一直盖在整个 App 上，而它的 onDismissRequest 是空实现、全屏 Spacer 又会吞掉
                //   所有点击 —— 用户看到的就是"整个 App 卡死，只能杀进程"。
                //   NonCancellable：此时 scope 已在取消中，普通 withContext 会立刻再抛异常，
                //   下面这行根本执行不到。
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) {
                        runCatching { messageDialog.close() }
                    }
                }
                throw e
            }
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                messageDialog.alert(
                    title = "加载失败",
                    text = e.message ?: e.toString()
                )
            }
        }
    }

    fun setSortOrder(value: Int) {
        _sortOrder.value = value
        refreshList(false)
    }

    fun setCurrentReply(reply: ReplyInfo) {
        _currentReply.value = reply
    }

    fun clearCurrentReply() {
        _currentReply.value = null
    }

    fun isLogin() = userStore.isLogin()


    fun toUserPage(mid: String) {
        pageNavigation.navigate(UserSpacePage(
            id = mid,
        ))
    }

    fun openReplyDialog() {
        if (!isLogin()) {
            toast("请先登录")
            return
        }
        val params = ReplyEditParams(
            type = type,
            oid = oid,
        )
        editDialogState.show(params)
    }

    fun menuItemClick(view: View, item: MenuItemPropInfo) {
        when (val key = item.key) {
            MenuKeys.sort -> {
                val next = sortOrderList.firstOrNull { it.first != sortOrder.value } ?: sortOrderList.first()
                setSortOrder(next.first)
            }
            MenuKeys.send -> {
                openReplyDialog()
            }
        }
    }
}