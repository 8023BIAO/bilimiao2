package cn.a10miaomiao.bilimiao.compose.pages.community.content

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.mypage.rememberMyMenu
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.community.ReplyItemBox
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.pages.community.MainReplyViewModel
import cn.a10miaomiao.bilimiao.compose.pages.community.components.ReplyEditDialog
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.mypage.myMenu
import com.a10miaomiao.bilimiao.comm.store.AhoCorasickMatcher
import com.a10miaomiao.bilimiao.comm.store.UserStore
import kotlinx.coroutines.flow.map
import org.kodein.di.compose.rememberInstance

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ReplyListContent(
    viewModel: MainReplyViewModel,
    innerPadding: PaddingValues,
    listState: LazyListState = rememberLazyListState(),
    headerContent: LazyListScope.() -> Unit = {},
    usePageConfig: Boolean = false,
    pageTitle: String,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val userStore by rememberInstance<UserStore>()
    val userState by userStore.stateFlow.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val dataStore = androidx.compose.runtime.remember {
        SettingPreferences.run { context.dataStore }
    }
    val blockedWords by dataStore.data.map {
        it[SettingPreferences.CommentBlockedWords] ?: emptySet()
    }.collectAsState(initial = emptySet())
    // 解析屏蔽词为 AC + 正则（同 FilterStore 逻辑）
    val (commentPlainMatcher, commentRegexList) = remember(blockedWords) {
        val plainWords = mutableListOf<String>()
        val regexes = mutableListOf<Regex>()
        for (word in blockedWords) {
            if (word.length > 2 && word.startsWith('/') && word.endsWith('/')) {
                try {
                    regexes.add(Regex(word.substring(1, word.length - 1)))
                } catch (_: Exception) {
                    plainWords.add(word) // 正则有语法错误，降级为纯文本
                }
            } else if (word.isNotEmpty()) {
                plainWords.add(word)
            }
        }
        val matcher = if (plainWords.isNotEmpty()) AhoCorasickMatcher(plainWords) else null
        Pair(matcher, regexes)
    }
    val allList by viewModel.list.data.collectAsState()
    val list = if (blockedWords.isEmpty()) allList else allList.filter { reply ->
        val text = reply.content?.message ?: return@filter true
        // Aho-Corasick 子串命中
        commentPlainMatcher?.let { if (it.containsAny(text)) return@filter false }
        // 正则匹配
        for (regex in commentRegexList) {
            if (regex.containsMatchIn(text)) return@filter false
        }
        true
    }
    val listLoading by viewModel.list.loading.collectAsState()
    val listFinished by viewModel.list.finished.collectAsState()
    val listFail by viewModel.list.fail.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val upMid by viewModel.upMid.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()

    if (usePageConfig) {
        val grayIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f).toArgb()
        val configId = PageConfig(
            title = pageTitle,
            menu = rememberMyMenu(sortOrder) {
                if (userState.info != null) {
                    myItem {
                        key = MenuKeys.send
                        iconFileName = "ic_baseline_send_24"
                        title = "发布评论"
                    }
                }
                val sortOrderList = viewModel.sortOrderList
                val currentLabel = sortOrderList.find { it.first == sortOrder }?.second ?: "排序"
                myItem {
                    key = MenuKeys.sort
                    iconFileName = "ic_baseline_filter_list_grey_24"
                    title = currentLabel
                }
            }
        )
        PageListener(
            configId = configId,
            onMenuItemClick = viewModel::menuItemClick,
        )
    }


    SwipeToRefresh(
        refreshing = isRefreshing,
        onRefresh = { viewModel.refreshList() },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surface,
                )
                .fillMaxSize(),
            contentPadding = innerPadding,
        ) {
            headerContent()

            items(
                list.size,
                { list[it].id }
            ) {
                val replyItem = list[it]
                val replyMid = replyItem.mid
                ReplyItemBox(
                    modifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                rememberSharedContentState("reply-${replyItem.id}"),
                                animatedVisibilityScope,
                            )
                        }
                    } else {
                        Modifier
                    }.fillMaxWidth(),
                    item = replyItem,
                    isUpper = replyMid == upMid,
                    showDelete = userState.isSelf(replyMid),
                    onLikeClick = {
                        viewModel.likeReplyById(replyItem.id)
                    },
                    onAvatarClick = {
                        viewModel.toUserPage(replyItem.mid.toString())
                    },
                    onDeleteClick = {
                        viewModel.deleteReply(replyItem)
                    },
                    onReplyClick = {
                        viewModel.setCurrentReply(replyItem)
                    },
                    onClick = {
                        viewModel.setCurrentReply(replyItem)
                    }
                )
            }
            item() {
                ListStateBox(
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

    ReplyEditDialog(
        state = viewModel.editDialogState,
    )
}