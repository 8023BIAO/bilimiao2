package cn.a10miaomiao.bilimiao.compose.pages.message

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.getValue
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.navigation.NavBackStackEntry
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.foundation.pagerTabIndicatorOffset
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.mypage.rememberMyMenu
import cn.a10miaomiao.bilimiao.compose.components.dialogs.SingleChoiceDialog
import cn.a10miaomiao.bilimiao.compose.components.dialogs.SingleChoiceItem
import cn.a10miaomiao.bilimiao.compose.components.dialogs.rememberDialogState
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.pages.message.content.AtMessageContent
import cn.a10miaomiao.bilimiao.compose.pages.message.content.LikeMessageContent
import cn.a10miaomiao.bilimiao.compose.pages.message.content.PrivateMessageContent
import cn.a10miaomiao.bilimiao.compose.pages.message.content.ReplyMessageContent
import cn.a10miaomiao.bilimiao.compose.pages.message.content.SystemMessageContent
import com.a10miaomiao.bilimiao.comm.entity.MessageInfo
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.message.LinkSettingInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.MessageStore
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.serialization.Serializable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
class MessagePage : ComposePage() {

    @Composable
    override fun Content() {
        val userStore: UserStore by rememberInstance()
        val userState by userStore.stateFlow.collectAsStateWithLifecycle()
        val isLogin = userState.info != null
        val viewModel: MessagePageViewModel = diViewModel()
        if (isLogin) {
            MessagePageContent(viewModel)
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("请先登录", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

}

/**
 * 消息页的 5 个 Tab。
 *
 * ★ name 用极简文案（回复 / @我 / 赞 / 私信 / 系统）：
 *   5 个长名字（"回复我的""我的@""收到的赞""私信""系统通知"）在窄屏上会被挤成两行、Tab 条整体抬高，
 *   用户 2026-09-25 实测反馈"要极简、好识别，要不然多的话它又抬上去，不好看"。
 *   只动显示名：id 与下面的角标（unread.reply / at / like / chat / sys_msg）逻辑一个字都没改。
 */
private sealed class MessagePageTab(
    val id: Int,
    val name: String,
) {
    @Composable
    abstract fun PageContent()
    data object Reply : MessagePageTab(
        id = 0,
        name = "回复"
    ) {
        @Composable
        override fun PageContent() {
            ReplyMessageContent()
        }
    }

    data object At : MessagePageTab(
        id = 1,
        name = "@我"
    ) {
        @Composable
        override fun PageContent() {
            AtMessageContent()
        }
    }

    data object Like : MessagePageTab(
        id = 2,
        name = "赞"
    ) {
        @Composable
        override fun PageContent() {
            LikeMessageContent()
        }
    }

    data object PrivateMsg : MessagePageTab(
        id = 3,
        name = "私信"
    ) {
        @Composable
        override fun PageContent() {
            PrivateMessageContent()
        }
    }

    /**
     * 系统通知：放在"私信"**后面**（用户要求的位置）。
     * 它和另外四个 Tab 不一样 —— 数据不是"某个人对我做了什么"，而是 B站自己发的通知
     * （稿件状态/活动/风纪等），所以列表里没有头像和用户，见 SystemMessageContent。
     */
    data object SystemMsg : MessagePageTab(
        id = 4,
        name = "系统"
    ) {
        @Composable
        override fun PageContent() {
            SystemMessageContent()
        }
    }

}

private class MessagePageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val fragment by instance<Fragment>()
    private val messageStore by instance<MessageStore>()

    val tabs = listOf<MessagePageTab>(
        MessagePageTab.Reply,
        MessagePageTab.At,
        MessagePageTab.Like,
        MessagePageTab.PrivateMsg,
        MessagePageTab.SystemMsg,
    )

    /**
     * 消息提醒设置（回复我的 / @我的 / 收到的赞）—— 三个 Tab 底栏那个「设置」入口的单选弹窗用它。
     *
     * 接口见 [com.a10miaomiao.bilimiao.comm.apis.MessageAPI.linkSetting]（官方网页版消息中心那条，
     * 域名 api.vc.bilibili.com）。进页面拉一次，打开弹窗时再拉一次，保证显示的是服务端当前值。
     */
    val noticeSetting = MutableStateFlow<LinkSettingInfo?>(null)

    init {
//        messageStore.clearUnread()
        loadNoticeSetting()
    }

    fun loadNoticeSetting() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val res = BiliApiService.messageApi.linkSetting()
                .awaitCall()
                .json<ResultInfo<LinkSettingInfo>>()
            if (res.isSuccess && res.data != null) {
                noticeSetting.value = res.data
            }
        }.onFailure { it.printStackTrace() }
    }

    fun setNotice(field: String, value: Int) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val res = BiliApiService.messageApi.setLinkSetting(field, value)
                .awaitCall()
                .json<MessageInfo>()
            withContext(Dispatchers.Main) {
                if (res.isSuccess) toast("设置成功") else toast(res.message.ifBlank { "设置失败" })
            }
            if (res.isSuccess) loadNoticeSetting()
        }.onFailure {
            it.printStackTrace()
            withContext(Dispatchers.Main) {
                toast("设置失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MessagePageContent(
    viewModel: MessagePageViewModel
) {
    val scope = rememberCoroutineScope()

    val messageStore: MessageStore by rememberInstance()
    val messageState = messageStore.stateFlow.collectAsStateWithLifecycle().value

    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val pagerState = rememberPagerState(pageCount = { viewModel.tabs.size })

    // ── 消息提醒设置（用户 2026-09-27 要求）──
    // 回复/@/赞 三个 Tab 的底栏各加一个「设置」入口，打开的就是官方那三张单选弹窗：
    //   回复我的消息提醒 / @我的消息提醒：所有人 · 关注的人 · 不接收任何消息提醒
    //   收到的赞提醒：始终提醒 · 永不提醒
    val noticeDialog = rememberDialogState()
    var noticeDialogKind by remember { mutableIntStateOf(-1) }
    val noticeSetting by viewModel.noticeSetting.collectAsStateWithLifecycle()

    val configId = PageConfig(
        title = "消息通知",
        menu = rememberMyMenu(pagerState.currentPage) {
            when (pagerState.currentPage) {
                0 -> myItem {
                    key = MenuKeys.msgNoticeSetting
                    iconFileName = "ic_baseline_settings_grey_24"
                    title = "回复提醒"
                }
                1 -> myItem {
                    key = MenuKeys.msgNoticeSetting
                    iconFileName = "ic_baseline_settings_grey_24"
                    title = "@提醒"
                }
                2 -> myItem {
                    key = MenuKeys.msgNoticeSetting
                    iconFileName = "ic_baseline_settings_grey_24"
                    title = "点赞提醒"
                }
            }
        }
    )
    PageListener(
        configId = configId,
        onMenuItemClick = { _, menuItem ->
            if (menuItem.key == MenuKeys.msgNoticeSetting) {
                noticeDialogKind = pagerState.currentPage
                // 打开时再拉一次：可能刚在别处改过（也顺手把 loading 当成"正在取当前值"）
                viewModel.loadNoticeSetting()
                noticeDialog.openDialog = true
            }
        }
    )

    val noticeKind = noticeDialogKind
    if (noticeKind in 0..2) {
        val field = when (noticeKind) {
            0 -> "set_comment"
            1 -> "set_at"
            else -> "set_like"
        }
        val dialogTitle = when (noticeKind) {
            0 -> "回复我的消息提醒"
            1 -> "@我的消息提醒"
            else -> "收到的赞提醒"
        }
        val options = if (noticeKind == 2) {
            listOf(
                SingleChoiceItem("始终提醒", "0"),
                SingleChoiceItem("永不提醒", "5"),
            )
        } else {
            listOf(
                SingleChoiceItem("所有人", "0"),
                SingleChoiceItem("关注的人", "1"),
                SingleChoiceItem("不接收任何消息提醒", "2"),
            )
        }
        val currentValue = when (noticeKind) {
            0 -> noticeSetting?.set_comment
            1 -> noticeSetting?.set_at
            else -> noticeSetting?.set_like
        }?.toString() ?: ""
        SingleChoiceDialog(
            state = noticeDialog,
            title = dialogTitle,
            list = options,
            selected = currentValue,
            onChange = { value ->
                value.toIntOrNull()?.let { viewModel.setNotice(field, it) }
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        TabRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(windowInsets.toPaddingValues(bottom = 0.dp)),
            selectedTabIndex = pagerState.currentPage,
            indicator = { positions ->
                TabRowDefaults.PrimaryIndicator(
                    Modifier.pagerTabIndicatorOffset(pagerState, positions),
                )
            },
        ) {
            viewModel.tabs.forEachIndexed { index, tab ->
                Tab(
                    text = {
                        Box() {
                            val unreadCount: Int = messageState.unread?.let {
                                when (index) {
                                    0 -> it.reply
                                    1 -> it.at
                                    2 -> it.like
                                    3 -> it.chat
                                    4 -> it.sys_msg
                                    else -> 0
                                }
                            } ?: 0
                            Text(
                                modifier = Modifier.padding(
                                    end = if (unreadCount > 0) 16.dp else 0.dp
                                ),
                                text = tab.name,
                                color = if (index == pagerState.currentPage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onBackground
                                }
                            )
                            if (unreadCount > 0) {
                                Badge(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                ) {
                                    Text(unreadCount.toString())
                                }
                            }
                        }
                    },
                    selected = pagerState.currentPage == index,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                )
            }
        }
        val saveableStateHolder = rememberSaveableStateHolder()
        HorizontalPager(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            state = pagerState,
        ) { index ->
            saveableStateHolder.SaveableStateProvider(index) {
                viewModel.tabs[index].PageContent()
            }
        }
    }
}
