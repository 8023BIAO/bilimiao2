package cn.a10miaomiao.bilimiao.compose.pages.message.content

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.navigation.BilibiliNavigation
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.message.SystemMessageInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.MessageStore
import com.a10miaomiao.bilimiao.comm.toast
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import com.kongzue.dialogx.dialogs.MessageDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

/**
 * 系统通知（消息页第 5 个 Tab，位置在"私信"后面）。
 *
 * 数据来源：message.bilibili.com/x/sys-msg/query_notify_list（见 MessageAPI.sysNotify）。
 * 与回复/@/点赞三个 Tab 最大的不同：系统通知没有"谁对我做了什么"，
 * 所以列表项里**没有头像和昵称**，只有 标题 / 正文（含可点链接）/ 时间 三层
 * —— 这正是 PiliPlus 系统通知页（lib/pages/msg_feed_top/sys_msg/view.dart）
 * ListTile 的信息层次，我们只换成自己的 Compose 写法，不搬 Flutter 的视觉细节。
 */
private class SystemMessageContentModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val pageNavigation by instance<PageNavigation>()
    private val messageStore by instance<MessageStore>()

    val isRefreshing = MutableStateFlow(false)
    val list = FlowPaginationInfo<SystemMessageInfo>()

    /**
     * 翻页游标。
     *
     * 系统通知接口没有"顶层 cursor 对象"，**每条通知自己带一个 cursor**，
     * 翻页时用上一页最后一条的（PiliPlus handleListResponse 的做法）。
     * null = 还没有第一页数据，此时 loadMore 不该发请求（否则会重复拉第一页）。
     */
    private var cursor: Long? = null

    init {
        loadData()
    }

    /**
     * 拉取系统通知。
     *
     * @param nextCursor null = 拉第一页（首次进入 / 下拉刷新）；非 null = 拉下一页
     */
    fun loadData(nextCursor: Long? = null) = viewModelScope.launch(Dispatchers.IO) {
        try {
            list.loading.value = true
            list.fail.value = ""   // 开始加载就清掉上一次的失败提示，否则重试成功后它还挂在列表底部
            val res = BiliApiService.messageApi
                .sysNotify(nextCursor)
                .awaitCall()
                .json<ResultInfo<List<SystemMessageInfo>>>()
            if (res.isSuccess) {
                val items = res.data
                if (items == null) {
                    list.fail.value = "未登录账号或加载失败"
                    return@launch
                }
                if (nextCursor == null) {
                    // 首屏 = 用户已经看到最新通知：把服务端游标推到最新的一条（等价 PiliPlus 的 update_cursor），
                    // 顺便清 Tab 红点。它失败只影响"红点灭不灭"，不该影响列表，所以单独 try 在里面。
                    markRead(items.firstOrNull()?.cursor)
                    list.data.value = items
                } else {
                    list.data.value = list.data.value + items
                }
                cursor = items.lastOrNull()?.cursor
                // 这个接口没有 is_end 之类的"到底了"标志，只能按"这一页是空的"判定
                list.finished.value = items.isEmpty()
            } else {
                list.fail.value = res.message.ifBlank { "加载失败，重试" }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // ★ 断网（UnknownHostException）也要落到"失败态"：
            //   兄弟那几个 Tab 在这里会把断网静默掉，于是列表显示成 ListStateBox 的"空空如也"，
            //   用户会以为"真的没有通知"。系统通知宁可明确给出"加载失败，重试"。
            list.fail.value = when (e) {
                is java.net.UnknownHostException -> "网络不可用，请检查网络后重试"
                else -> e.message ?: "加载失败，重试"
            }
        } finally {
            list.loading.value = false
            isRefreshing.value = false
        }
    }

    fun loadMore() {
        if (
            !list.finished.value &&
            !list.loading.value
        ) {
            cursor?.let {
                loadData(it)
            }
        }
    }

    fun refresh() {
        isRefreshing.value = true
        list.finished.value = false
        list.fail.value = ""
        cursor = null
        loadData()
    }

    /**
     * 上报"系统通知读到哪了"，成功后清掉 Tab 上的未读红点。
     *
     * 这里把异常全吞掉：已读上报失败**不该**让用户看到任何错误 ——
     * 通知本身已经显示出来了，红点大不了下次进来再消一次。
     *
     * TODO: 未读角标目前只做到"进这个 Tab 就整页标记已读"（与 PiliPlus 一致）。
     *      如果想做成"滚到哪标到哪"，需要按每条 item 的 cursor 逐条上报，暂不做。
     */
    private suspend fun markRead(toCursor: Long?) {
        if (toCursor == null) return
        try {
            val res = BiliApiService.messageApi
                .sysUpdateCursor(toCursor)
                .awaitCall()
                .json<ResultInfo<Unit>>()
            if (res.isSuccess) {
                messageStore.clearSysMsgUnread()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 删除单条系统通知（长按列表项 → 确认框点"确定" → 走这里）。
     *
     * 顺序是刻意的：**先等服务端确认删掉了，再动本地列表**。
     * 反过来先删本地再请求（乐观删除）一旦失败，界面上没了、退出重进又回来，
     * 用户会以为"删了但没删干净"——宁可慢半拍，也不要这种鬼影。
     *
     * 失败（网络异常 / code != 0）只 toast，**绝不碰本地列表**（需求点名的"不要误删本地项"）。
     * 这里比回复/@/点赞三个 Tab 的 `removeItem` 多判了一次 `res.isSuccess`：
     * 它们只要"HTTP 请求没抛异常"就 toast("删除成功")，服务端回 code=1 也会报成功 —— 那是错的。
     */
    fun deleteMessage(item: SystemMessageInfo) {
        val id = item.id
        if (id == null) {
            // 服务端删的是 id（不是 cursor）。拿不到 id 就只能明说，别发一个 ids=[null] 出去
            toast("这条通知缺少 id，无法删除")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 用 JsonElement 而不是 Unit 收 data：这个接口的 data 是什么形状 PiliPlus 没解析过
                //（它只读 code）。用 Unit 的话，服务端哪天回个 `"data": 0` 就会抛解析异常，
                // 把"其实已经删掉了"误报成失败。JsonElement 什么形状都收得下，我们只要 code / message。
                val res = BiliApiService.messageApi
                    .delSysNotify(id)
                    .awaitCall()
                    .json<ResultInfo<JsonElement>>()
                if (res.isSuccess) {
                    removeLocalItem(id)
                    toast("删除成功")
                } else {
                    toast(res.message.ifBlank { "删除失败，请重试" })
                }
            } catch (e: Exception) {
                e.printStackTrace()
                toast(e.message ?: "删除失败，请重试")
            }
        }
    }

    /**
     * 把一条通知从本地列表里摘掉，并**扶正分页状态**（只在服务端删成功之后调）。
     *
     * 两个坑：
     *  ① 游标：`cursor` 记的是"当前最后一条的 cursor"，同时是下一页请求的起点（见 [loadData]）。
     *     删掉的正好是最后一条时，游标就指向一条已经不存在的通知了 —— 必须往前挪到新的最后一条。
     *     删中间/开头则不用动：游标仍是最后一条的，语义没变。
     *  ② 删空：列表空了但 finished 还是 false 的话，空态判断（`list.isEmpty() && listFinished`）
     *     不成立，UI 会掉到 ListStateBox 的"空空如也"（那不是这个页面该说的话）。
     *     这里直接把分页标成"到底了"，让它显示"没有系统通知"。
     */
    private fun removeLocalItem(id: Long) {
        val current = list.data.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return   // 已经不在了（被删过 / 已被刷新换掉）：什么都不用做
        val removed = current[index]
        val remaining = current.toMutableList().apply { removeAt(index) }
        list.data.value = remaining
        if (removed.cursor != null && removed.cursor == cursor) {
            cursor = remaining.lastOrNull()?.cursor
        }
        if (remaining.isEmpty()) {
            cursor = null
            list.finished.value = true
        }
    }

    /**
     * 全页**唯一**的链接跳转入口 —— 列表正文里的点击、弹窗里的"打开链接"都走这里，
     * 两处绝不会各判一套规则（用户 2026-09-25 反馈的"点 github 链接弹不支持"就是旧分流造成的）。
     *
     * [target] 是解析阶段就规范化好的目标（见 parseSystemMessageContent），规则表：
     *
     *   http(s)://…  → BilibiliNavigation.navigationToWeb：
     *                  B站域名（bilibili.com / b23.tv …）进内置 WebPage，
     *                  **站外域名（github 这类）进外部浏览器**。
     *                  旧实现把任何 http(s) 都直接塞给 WebPage，而 WebPage 只放行白名单域名，
     *                  于是站外链接只能弹"不支持的链接"—— 这就是要修的那件事。
     *   bilibili:// / bilimiao:// → BilibiliNavigation.navigationTo：
     *                  站内深链交给它解析成原生页（bilimiao://video/BVxxx → 视频详情，
     *                  bilimiao://article/123 → 专栏阅读，bilimiao://opus/{id} → 动态详情）。
     *                  认不出来（返回 false）时给个明确提示，别让用户觉得"点了没反应"。
     *   其它（裸域名兜底）→ 也交给 navigationToWeb，它自己会补 http:// 并做白名单判断。
     */
    fun openLink(target: String) {
        val url = target.trim().replace("\"", "")
        if (url.isBlank()) return
        val uri = Uri.parse(url)
        when (uri.scheme?.lowercase()) {
            "bilibili", "bilimiao" -> {
                if (!BilibiliNavigation.navigationTo(pageNavigation, url)) {
                    toast("不支持的链接：$url")
                }
            }

            else -> BilibiliNavigation.navigationToWeb(pageNavigation, url)
        }
    }
}

@Composable
fun SystemMessageContent() {
    val viewModel: SystemMessageContentModel = diViewModel()
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())
    val context = LocalContext.current

    val list by viewModel.list.data.collectAsStateWithLifecycle()
    val listLoading by viewModel.list.loading.collectAsStateWithLifecycle()
    val listFinished by viewModel.list.finished.collectAsStateWithLifecycle()
    val listFail by viewModel.list.fail.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    SwipeToRefresh(
        modifier = Modifier.padding(
            start = windowInsets.leftDp.dp,
            end = windowInsets.rightDp.dp,
        ),
        refreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
    ) {
        LazyColumn() {
            items(list.size) { index ->
                val item = list[index]
                Column() {
                    if (index != 0) {
                        HorizontalDivider()
                    }
                    SystemMessageItemBox(
                        item = item,
                        // ② 点整行任意位置 → 弹窗看全文（标题 / 正文全文 / 时间）。
                        //    不新开界面、不加新路由：用户明确说"不想再写一个界面，点进去再写一个界面"。
                        onClick = {
                            showSystemMessageDetailDialog(context, item, viewModel::openLink)
                        },
                        // ③ 长按整行 → 确认框 → 删掉这一条（接口见 MessageAPI.delSysNotify）。
                        //    combinedClickable 把长按和点击分开处理，长按**不会**顺带触发 onClick，
                        //    所以不会出现"长按一下顺手弹出全文弹窗"。
                        onLongClick = {
                            showSystemMessageDeleteDialog(item) { viewModel.deleteMessage(item) }
                        },
                        onOpenLink = viewModel::openLink,
                    )
                }
            }
            item() {
                // 空态：列表空的时候 ListStateBox 只会显示"空空如也"（那是给别的列表用的），
                // 这里要的是明确的"没有系统通知"，所以自己渲染；
                // 失败态仍交给 ListStateBox —— 它有红色的错误文案 + "重试"按钮。
                if (list.isEmpty() && listFinished && listFail.isBlank() && !listLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "没有系统通知",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    ListStateBox(
                        modifier = Modifier.padding(
                            bottom = windowInsets.bottomDp.dp
                        ),
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
 * 单条系统通知：标题 / 正文（含可点链接）/ 右对齐时间。
 *
 * 层次照 PiliPlus 系统通知页的 ListTile：
 *   title   → 标题（titleMedium）
 *   subtitle→ 正文（bodyMedium，里面挂链接）+ 右下角时间（小号、outline 色）
 * 它那边一行也没有头像/昵称 —— 系统通知本来就没有"对方用户"。
 *
 * 整块可点可长按：点任意位置弹窗看全文（见 [showSystemMessageDetailDialog]），
 * 长按弹删除确认框（见 [showSystemMessageDeleteDialog]）。
 * 正文里的链接是 LinkAnnotation，点击由文本自身消费，不会顺带把弹窗也带出来。
 *
 * `@OptIn(ExperimentalFoundationApi::class)`：这个 Compose 版本里 combinedClickable 仍是实验 API
 *（同 PrivateMessageContent / VideoCoverBox 的写法，照抄即可）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SystemMessageItemBox(
    item: SystemMessageInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = MaterialTheme.colorScheme.primary)
    )
    // plainContent 每次访问都要解一遍 {"web":"…"}，取一次就够
    val content = item.plainContent
    // 解析只做一次：列表渲染与弹窗取"第一个链接"都用这一份结果，保证规则只有一套
    val segments = remember(content) { parseSystemMessageContent(content) }
    // 只有一个 listener：tag 就是规范化后的跳转目标，具体跳哪里由 SystemMessageContentModel.openLink 决定
    val linkListener = LinkInteractionListener { link ->
        val target = (link as? LinkAnnotation.Clickable)?.tag ?: return@LinkInteractionListener
        onOpenLink(target.toString())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // combinedClickable 而不是 clickable：长按删除要挂在整行上，
            // 且长按与点击互斥（长按抬起时不会补一次 onClick）
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = item.title.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (content.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildSystemMessageContent(
                    segments = segments,
                    linkStyles = linkStyles,
                    linkListener = linkListener,
                ),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
        }
        val timeText = formatSysMsgTime(item.rawTimeText)
        if (timeText.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = timeText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 正文里识别出来的一段内容。
 *
 * [Link.target] 是**已经规范化**的跳转目标（http(s) 网址，或 bilimiao:// / bilibili:// 深链）：
 * 渲染时直接当 LinkAnnotation 的 tag，点击时原样交给 [SystemMessageContentModel.openLink]。
 * 也就是说"什么形态 → 跳哪里"只在这一处定义，列表和弹窗共用。
 */
private sealed class SystemMessageSegment {
    class Text(val text: String) : SystemMessageSegment()
    class Link(val text: String, val target: String) : SystemMessageSegment()
}

// ── 编号规则：与 components/community/ReplyItemBox.kt 的 REPLY_TEXT_REGEX_BASE / getLinkUrl 对齐 ──
// 那份是 private 的，没法直接调用；规则本身不能自创一套，否则同一条 BV号 在评论区和这里会跳去不同地方。
private const val BV_PATTERN = "BV[0-9A-Za-z]{10}"     // BV号：固定 10 位
private const val AV_PATTERN = "[aA][vV]\\d{1,15}"    // av号（1-15 位数字）
private const val CV_PATTERN = "[cC][vV]\\d{1,8}"     // cv号（专栏，1-8 位数字）

/**
 * URL 的"尾巴"：不含空白，也**不含中日韩文字与全角标点**。
 *
 * 为什么要把中文剔出去：B站通知正文常写成"详情见 https://github.com/a/b说明"这种没有空格的样子，
 * 用 `[^\s]*` 会把后面一整句中文都吃进链接，点开就是一个乱七八糟的地址
 * （用户 2026-09-25 反馈的 github 链接正是这一类）。
 * \u4e00-\u9fff = 汉字，\u3000-\u303f = 中文标点（。、〈〉【】…），\uff00-\uffef = 全角符号（，！？：；（）…）。
 */
private const val URL_TAIL_PATTERN = "[^\\s\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]*"

/**
 * 正文里的"可点部分"识别规则。
 *
 * ①②③ 照抄 PiliPlus sys_msg/view.dart 的 _urlRegExp（分组序号也保持一致）：
 *   1/2 → #{文案}{链接}
 *   3   → 【……】里的编号
 *   4   → （……）里的动态号
 * 为什么不是"只匹配 http(s)://"：B站系统通知的正文是服务端拼好的字符串，
 * 可点内容经常是 `#{标题}{bilibili://…}` 或 `【BV1xx】` 这种写法，只认 URL 会漏掉一大半。
 *
 * ④⑤⑥ 是用户 2026-09-25 追加的要求：正文里**裸的** BV号 / av号 / cv号 也要自动变成可点链接
 * （"我是点击 BV 号啊、CV 号啊、AV 号啊，什么玩意的都要自动识别吧？"）。
 * 顺序很重要：完整 http(s) 链接排在编号之前，URL 里夹带的 BV 号整条按 URL 处理，不会被拆成两段。
 * 编号带 \b 词边界，免得 have / cover 这类单词被截出 av1 / cv1。
 */
private val SYSTEM_MSG_LINK_REGEX = Regex(
    // ① #{文案}{链接}
    "#\\{([^}]*)\\}\\{([^}]*)\\}" +
        // ② 完整 http(s) 链接（域名首字符不能是 / $ . ? #，免得把句末标点一并吞进来）
        "|https?://[^\\s/\\$.?#]$URL_TAIL_PATTERN" +
        // ③ www. 开头的裸域名，按 https 处理
        "|www\\.[^\\s/\\$.?#]$URL_TAIL_PATTERN" +
        // ④⑤⑥ 裸的 BV号 / av号 / cv号
        "|\\b$BV_PATTERN\\b" +
        "|\\b$AV_PATTERN\\b" +
        "|\\b$CV_PATTERN\\b" +
        // ⑦ 【……】：里面是视频号/专栏号才算可点
        "|【(.*?)】" +
        // ⑧ （纯数字）→ 动态
        "|（(\\d+)）"
)
private val SYSTEM_MSG_BV_REGEX = Regex(BV_PATTERN)
private val SYSTEM_MSG_AV_REGEX = Regex(AV_PATTERN)
private val SYSTEM_MSG_CV_REGEX = Regex(CV_PATTERN)

/**
 * 裸编号 → 跳转目标（也用于【……】里的内容）：
 *   BV号 / av号 → 视频详情（bilimiao://video/… 交给 BilibiliNavigation/路由表进 VideoDetailPage，
 *                          与评论区 getLinkUrl 的落点一致，不经浏览器）
 *   cv号        → 专栏阅读页（bilimiao://article/…，与 BilibiliNavigation 里 /read/cv{id} 的落点一致）
 * 认不出来返回 null —— 认错就乱跳，比不跳更糟（比如【视频标题】里的字）。
 */
private fun systemMessageIdTarget(token: String): String? {
    val text = token.trim()
    return when {
        SYSTEM_MSG_BV_REGEX.matches(text) -> "bilimiao://video/$text"
        SYSTEM_MSG_AV_REGEX.matches(text) -> "bilimiao://video/${text.substring(2)}"
        SYSTEM_MSG_CV_REGEX.matches(text) -> "bilimiao://article/${text.substring(2)}"
        // 【13840208】这种纯数字：PiliPlus 同款，当 av 号看
        text.isNotEmpty() && text.all { it.isDigit() } -> "bilimiao://video/$text"
        else -> null
    }
}

/**
 * 把一段"链接文本"规范化成 [SystemMessageContentModel.openLink] 能直接吃的目标。
 * 认不出来（空串 / 纯文案）返回 ""，调用方按普通文字显示。
 */
private fun normalizeSystemMessageTarget(raw: String): String {
    val text = raw.trim()
        .replace("\"", "")
        // 句末的英文标点也常被 URL 尾巴黏进来（"见 https://a.com/b."），一律剪掉；
        // 全角标点已经在 URL_TAIL_PATTERN 里被排除了，这里只处理 ASCII 那一批
        .trimEnd('.', ',', ';', ':', '!', '?')
    if (text.isEmpty()) return ""
    // #{文案}{…} 里也可能直接放编号（BV号 / av号 / cv号），先按编号认一遍
    systemMessageIdTarget(text)?.let { return it }
    // http(s):// 与 bilibili:// / bilimiao:// 深链原样交给 openLink 分流
    if ("://" in text) return text
    // 裸域名（www.bilibili.com/xxx、b23.tv/xxx）：必须"有点号且不含空格"才敢补 https://，
    // 否则 #{标题}{随便一句话} 里的文案会被拼成 https://随便一句话 直接跳出去
    return if (text.none { it.isWhitespace() } && '.' in text) "https://$text" else ""
}

/**
 * 正文 → 段落列表。**纯函数**：列表渲染和弹窗取"第一个链接"都从这一份结果里拿，
 * 保证识别规则、跳转目标只有一套（需求：列表和弹窗不许各写一套规则）。
 */
private fun parseSystemMessageContent(content: String): List<SystemMessageSegment> {
    if (content.isEmpty()) return emptyList()
    val segments = mutableListOf<SystemMessageSegment>()
    var lastIndex = 0
    SYSTEM_MSG_LINK_REGEX.findAll(content).forEach { match ->
        // 匹配之前的普通文字原样补上
        if (match.range.first > lastIndex) {
            segments += SystemMessageSegment.Text(content.substring(lastIndex, match.range.first))
        }
        lastIndex = match.range.last + 1

        val raw = match.value
        when {
            raw.startsWith("#{") -> {
                val text = match.groupValues[1]
                // 链接两侧可能残留引号（服务端拼串留下的），normalize 里会去掉
                val target = normalizeSystemMessageTarget(match.groupValues[2])
                if (text.isBlank() || target.isEmpty()) {
                    segments += SystemMessageSegment.Text(raw)
                } else {
                    segments += SystemMessageSegment.Link(text, target)
                }
            }

            raw.startsWith("【") -> {
                val inner = match.groupValues[3]
                val target = systemMessageIdTarget(inner)
                if (target == null) {
                    // 【视频标题】这种写法很常见，认不出编号就原样当文字
                    segments += SystemMessageSegment.Text(raw)
                } else {
                    segments += SystemMessageSegment.Text("【")
                    segments += SystemMessageSegment.Link(inner, target)
                    segments += SystemMessageSegment.Text("】")
                }
            }

            raw.startsWith("（") -> {
                // （动态号）→ 动态详情（深链由 PageNavigation.navigateByUri 的 opus 分支接管）
                segments += SystemMessageSegment.Text("（")
                segments += SystemMessageSegment.Link("查看动态", "bilimiao://opus/${match.groupValues[4]}")
                segments += SystemMessageSegment.Text("）")
            }

            raw.startsWith("http") || raw.startsWith("www.") -> {
                // 纯网页链接：显示成"🔗网页链接"（PiliPlus 同款文案，也避免长 URL 撑破一行）；
                // 想看完整 URL 可以点开弹窗（正文全文）
                val target = normalizeSystemMessageTarget(raw)
                if (target.isEmpty()) {
                    segments += SystemMessageSegment.Text(raw)
                } else {
                    segments += SystemMessageSegment.Link("\uD83D\uDD17网页链接", target)
                }
            }

            else -> {
                // ④⑤⑥ 裸编号：显示编号本身，点了进对应的原生页
                val target = systemMessageIdTarget(raw)
                if (target == null) {
                    segments += SystemMessageSegment.Text(raw)
                } else {
                    segments += SystemMessageSegment.Link(raw, target)
                }
            }
        }
    }
    if (lastIndex < content.length) {
        segments += SystemMessageSegment.Text(content.substring(lastIndex))
    }
    return segments
}

/**
 * 段落列表 → 带可点链接的富文本。
 *
 * 所有可点片段挂**同一个** listener（tag 即规范化后的目标），谁跳哪里由
 * [SystemMessageContentModel.openLink] 一张表决定，这里不做二次判断。
 */
private fun buildSystemMessageContent(
    segments: List<SystemMessageSegment>,
    linkStyles: TextLinkStyles,
    linkListener: LinkInteractionListener,
): AnnotatedString = buildAnnotatedString {
    segments.forEach { segment ->
        when (segment) {
            is SystemMessageSegment.Text -> append(segment.text)
            is SystemMessageSegment.Link -> withLink(
                LinkAnnotation.Clickable(
                    tag = segment.target,
                    styles = linkStyles,
                    linkInteractionListener = linkListener,
                )
            ) {
                append(segment.text)
            }
        }
    }
}

/**
 * ② 点整行弹出的"看全文"弹窗：标题 / 正文全文 / 时间都在一个 MessageDialog 里说完
 * —— 不新建详情页、不加新路由（用户 2026-09-25："我真的不想再写一个界面，点进去再写一个界面"）。
 *
 * 按钮按 DialogX 固定槽位**按位置**分配（Material 布局是 `btn_selectOther` + 空隙 +
 * `btnSelectNegative` + `btnSelectPositive`，即最左 / 中间 / 最右，与评论反诈弹窗同一套排法）：
 *   ① 关闭（other，最左）  ② 复制全文（cancel，中间）  ③ 打开链接（ok，最右）
 * 正文里没有链接时，没有东西可开：ok 槽位不放"打开链接"，文案改成"知道了"，只保留"关闭"。
 *
 * 弹窗正文是 DialogX 的纯文本（链接不可点），所以"打开链接"打开的是正文里**第一个**链接，
 * 并且走的是和列表点击完全相同的 [SystemMessageContentModel.openLink] 规则。
 */
private fun showSystemMessageDetailDialog(
    context: Context,
    item: SystemMessageInfo,
    onOpenLink: (String) -> Unit,
) {
    val content = item.plainContent
    val timeText = formatSysMsgTime(item.rawTimeText)
    // "第一个链接"与列表共用同一份解析结果（同一套识别规则）
    val firstLink = parseSystemMessageContent(content)
        .filterIsInstance<SystemMessageSegment.Link>()
        .firstOrNull()
        ?.target

    val body = buildString {
        append(content.ifBlank { "（这条通知没有正文）" })
        if (timeText.isNotBlank()) {
            append("\n\n")
            append(timeText)
        }
    }

    val dialog = MessageDialog.build()
        .setTitle(item.title.orEmpty().ifBlank { "系统通知" })
        .setMessage(body)
    dialog.setOtherButton("关闭") { _, _ -> false }
    if (firstLink == null) {
        dialog.setOkButton("知道了")
    } else {
        dialog.setCancelButton("复制全文") { _, _ ->
            copySystemMessage(context, item)
            false
        }
        dialog.setOkButton("打开链接") { _, _ ->
            onOpenLink(firstLink)
            false
        }
    }
    dialog.show()
}

/**
 * ③ 长按列表项弹出的"删除确认"框（用户要求：弹出提示框问是否删除，带取消和确定两个按钮）。
 *
 * 按钮按 DialogX 固定槽位**按位置**分配（Material 布局是 `btn_selectOther` + 空隙 +
 * `btnSelectNegative` + `btnSelectPositive`，即最左 / 中间 / 最右，与"看全文"弹窗、
 * 评论反诈弹窗同一套排法）。这里只有两个按钮，所以：
 *   **取消 = cancel（中间）、确定 = ok（最右）**，最左的 other 槽位空着不占位。
 *
 * 正文必须说清"删的是哪一条"：列表里好几条通知长得差不多，只写"确定删除吗？"用户没法确认。
 * 所以正文带上标题 + 时间（时间用列表同一份 formatSysMsgTime，不另起一套格式）。
 *
 * 点"确定"只是发起请求（弹窗照常关掉），成功/失败由 ViewModel 里那次 toast 告知，
 * 不在这里等结果 —— 网络慢的时候卡着一个不关的弹窗更难受。
 */
private fun showSystemMessageDeleteDialog(
    item: SystemMessageInfo,
    onConfirm: () -> Unit,
) {
    val title = item.title.orEmpty().ifBlank { "系统通知" }
    val timeText = formatSysMsgTime(item.rawTimeText)
    val body = buildString {
        append("确定删除这条通知吗？\n\n")
        append(title)
        if (timeText.isNotBlank()) {
            append('\n')
            append(timeText)
        }
        append("\n\n删除后不可恢复。")
    }
    MessageDialog.build()
        .setTitle("删除通知")
        .setMessage(body)
        .setCancelButton("取消")
        .setOkButton("确定") { _, _ ->
            onConfirm()
            false
        }
        .show()
}

/**
 * "复制全文"：标题 + 正文 + 时间一起进剪贴板（正文**不截断** —— 弹窗存在的意义就是"看全"）。
 *
 * Android 13(33) 起系统剪贴板会自己弹"已复制"提示，只在更老的版本上补 toast
 * （与下载页 copyDownloadPathToClipboard 同一套做法）。
 */
private fun copySystemMessage(context: Context, item: SystemMessageInfo) {
    val text = buildString {
        val title = item.title.orEmpty()
        if (title.isNotBlank()) append(title).append('\n')
        append(item.plainContent.ifBlank { "（这条通知没有正文）" })
        val timeText = formatSysMsgTime(item.rawTimeText)
        if (timeText.isNotBlank()) append('\n').append(timeText)
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("系统通知", text))
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        toast("已复制全文")
    }
}

/**
 * 时间文案。
 *
 * PiliPlus 是直接把 time_at 原样显示的（`Text("${item.timeAt}")`），
 * 说明服务端给的多半就是可读文案；但万一它给的是时间戳数字（秒/毫秒/微秒/纳秒都可能），
 * 也不能把一串裸数字糊到界面上 —— 所以：
 *   全是数字 → 按位数折算成秒，再用和其它消息列表同一套的 converCTime（近期相对、过期绝对）；
 *   其它     → 原样显示（服务端已经是"2024-01-15 10:00:00"这种）。
 */
private fun formatSysMsgTime(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty() || !text.all { it.isDigit() }) return text
    val value = text.toLongOrNull() ?: return text
    val seconds = when (text.length) {
        13 -> value / 1_000                 // 毫秒
        16 -> value / 1_000_000             // 微秒
        19 -> value / 1_000_000_000         // 纳秒（PiliPlus 的 cursor 就是这个量级）
        else -> value                       // 10 位 = 秒；其它位数交给下面兜底
    }
    return if (seconds in 1_000_000_000L..4_000_000_000L) {
        NumberUtil.converCTime(seconds)
    } else {
        text
    }
}
