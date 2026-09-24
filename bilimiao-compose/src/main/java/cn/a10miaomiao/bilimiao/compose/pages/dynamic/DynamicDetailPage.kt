package cn.a10miaomiao.bilimiao.compose.pages.dynamic

import android.net.Uri
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.ModalDrawer
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptions
import bilibili.app.archive.middleware.v1.PlayerArgs
import bilibili.app.dynamic.v2.DynDetailReq
import bilibili.app.dynamic.v2.DynamicGRPC
import bilibili.app.dynamic.v2.DynamicItem
import bilibili.app.dynamic.v2.Module.ModuleItem
import bilibili.app.dynamic.v2.OpusDetailReq
import bilibili.app.dynamic.v2.OpusGRPC
import bilibili.app.dynamic.v2.OpusItem
import bilibili.app.dynamic.v2.Paragraph
import bilibili.app.dynamic.v2.PicParagraph
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.defaultNavOptions
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.community.ReplyItemBox
import cn.a10miaomiao.bilimiao.compose.components.dyanmic.DynamicModuleBox
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.status.BiliFailBox
import cn.a10miaomiao.bilimiao.compose.components.status.BiliLoadingBox

import cn.a10miaomiao.bilimiao.compose.pages.community.MainReplyListPageContent
import cn.a10miaomiao.bilimiao.compose.pages.community.MainReplyViewModel
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.store.WindowStore.Insets
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.json.JSONObject
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class DynamicDetailPage(
    private val id: String,
) : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel = diViewModel(key = "dynamic$id") {
            DynamicDetailPageViewModel(it, id)
        }
        DynamicDetailPageContent(viewModel)
    }

}

private class DynamicDetailPageViewModel(
    override val di: DI,
    dynId: String,
) : ViewModel(), DIAware {

    /**
     * 深链可能带 query 或被整体编码进 path（同 DynamicOpusPage），剥离后再用，
     * 避免动态详情/评论 gRPC 请求发出错误 id 导致"不显示"。
     */
    private val cleanDynId = dynId.substringBefore('?').substringBefore('#').trim()

    private val fragment by instance<Fragment>()
    val activity: AppCompatActivity by instance()
    val userStore: UserStore by instance()

    private val _loading = MutableStateFlow(false);
    val loading: StateFlow<Boolean> get() = _loading

    private val _fail = MutableStateFlow<Any?>(null)
    val fail: StateFlow<Any?> get() = _fail

    private val _detailData = MutableStateFlow<DynamicItem?>(null)
    val detailData: StateFlow<DynamicItem?> get() = _detailData

    /**
     * 评论区目标（oid + type）。默认 = (动态id, 17)：转发/纯文字这类动态本来就是这套。
     * 但**图文动态**实测是 `basic.comment_type = 11`、`basic.comment_id_str` 是 rid（不是动态 id），
     * 拿动态 id + 17 去取会直接 -404 → 评论区一片空白。gRPC 的 DynamicItem 里没有这两个字段，
     * 所以详情显示出来之前先补一次 web 动态详情把它们读出来；读不到就保持默认值，不比改动前差。
     */
    private val _commentOid = MutableStateFlow(cleanDynId)
    val commentOid: StateFlow<String> get() = _commentOid

    private val _commentType = MutableStateFlow(17)
    val commentType: StateFlow<Int> get() = _commentType

    init {
        if (cleanDynId.isNotBlank()) {
            loadData()
        }
    }

    fun loadData() = viewModelScope.launch(Dispatchers.IO) {
        try {
            _loading.value = true
            _fail.value = null
            // ★ 评论区目标先确认再放详情：详情一显示评论区就会拿着目标去拉评论，
            //   用默认的"动态id + 17"对图文动态必然 -404 → 会先闪一次错误再被修正。
            //   探针最多等 2 秒（超时就按默认值走），不能让它拖住详情本身。
            withTimeoutOrNull(2000) { loadCommentTarget() }
            val req = DynDetailReq(
                uid = userStore.state.info?.mid ?: 0L,
                dynamicId = cleanDynId,
                shareId = "dt.opus-detail.0.0.pv",
                shareMode = 3,
                localTime = 8,
                playerArgs = PlayerArgs(
                    qn = 32,
                    fnval = 400,
                )
            )
            val res = BiliGRPCHttp.request {
                DynamicGRPC.dynDetail(req)
            }.awaitCall()
            _detailData.value = res.item
        } catch (e: Exception) {
            _fail.value = e
            toast("网络错误")
            e.printStackTrace()
        } finally {
            _loading.value = false
        }
    }

    /** 补一次 HTTP 动态详情，只为拿评论区的真实 oid/type；失败静默（详情照常显示） */
    private suspend fun loadCommentTarget() {
        try {
            val response = MiaoHttp.request {
                url = BiliApiService.biliApi(
                    "x/polymer/web-dynamic/v1/detail",
                    "id" to cleanDynId,
                    "features" to "itemOpusStyle,opusBigCover",
                    "timezone_offset" to "-480",
                )
            }.awaitCall()
            val json = JSONObject(response.body?.string() ?: "")
            val basic = json.optJSONObject("data")
                ?.optJSONObject("item")
                ?.optJSONObject("basic")
            val commentId = basic?.optString("comment_id_str") ?: ""
            val commentType = basic?.optInt("comment_type", 0) ?: 0
            if (commentId.isNotBlank() && commentType > 0) {
                _commentOid.value = commentId
                _commentType.value = commentType
            }
        } catch (e: CancellationException) {
            throw e   // withTimeoutOrNull 的超时取消，别吞掉
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun menuItemClick(view: View, item: MenuItemPropInfo) {
        when (item.key) {
            MenuKeys.home -> {
            }
        }
    }
}


@Composable
private fun DynamicDetailPageContent(
    viewModel: DynamicDetailPageViewModel
) {
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val detailData = viewModel.detailData.collectAsStateWithLifecycle().value

    AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        targetState = detailData == null,
        label = "DynamicDetailPageContent",
        transitionSpec = {
            // Follow M3 Clean fades
            val fadeIn = fadeIn(
                tween(),
            )
            val fadeOut = fadeOut()
            fadeIn.togetherWith(fadeOut)
        }
    ) {
        if (it || detailData == null) {
            DynamicDetailPageLoadingContent(
                loading = viewModel.loading.collectAsStateWithLifecycle().value,
                fail = viewModel.fail.collectAsStateWithLifecycle().value,
                innerPadding = windowInsets.toPaddingValues()
            )
        } else {
            DynamicDetailPageDetailContent(
                viewModel = viewModel,
                windowInsets = windowInsets,
                detailData = detailData,
            )
        }
    }
}

@Composable
private fun DynamicDetailPageLoadingContent(
    loading: Boolean,
    fail: Any?,
    innerPadding: PaddingValues,
) {
    PageConfig(
        title = "动态详情"
    )
    if (fail != null) {
        BiliFailBox(
            e = fail,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    } else if (loading) {
        // 加载中也给个加载图（以前这里什么都不画，点进去先白屏一下）
        BiliLoadingBox(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

@Composable
private fun DynamicDetailPageDetailContent(
    viewModel: DynamicDetailPageViewModel,
    windowInsets: Insets,
    detailData: DynamicItem,
) {
    // 评论区目标由 VM 给（默认 动态id+17，图文动态会被修正成 comment_id_str+11；
    // key 带上两者，目标变了就换一个 ReplyViewModel）
    val commentOid by viewModel.commentOid.collectAsStateWithLifecycle()
    val commentType by viewModel.commentType.collectAsStateWithLifecycle()
    val replyViewModel = diViewModel(
        key = "dynamic.reply.${commentOid}.${commentType}"
    ) {
        MainReplyViewModel(
            it, commentOid,
            type = commentType,
            extra = "{\"spmid\":\"dt.dt-detail.0.0\",\"from_spmid\":\"\"}",
            filterTagName = "全部"
        )
    }

    val buttomModule = remember(detailData) {
        detailData.modules.lastOrNull()?.let {
            val moduleItem = it.moduleItem
            if (moduleItem is ModuleItem.ModuleButtom) moduleItem.value
            else null
        }
    }

    val origName = detailData.extend?.origName

    MainReplyListPageContent(
        viewModel = replyViewModel,
        pageTitle = origName?.let {
            "${it}\n的\n动态详情"
        } ?: "动态详情",
        headerContent = {
            item {
                Column(
                    modifier = Modifier
                        .padding(bottom = 5.dp),
                ) {
                    for(module in detailData.modules) {
                        DynamicModuleBox(module = module)
                    }
                }
            }
            item {
                val moduleStat = buttomModule?.moduleStat
                Text(
                    modifier = Modifier
                        .padding(
                            top = 10.dp,
                            bottom = 5.dp,
                            start = 10.dp,
                            end = 10.dp,
                        ),
                    text = if (moduleStat == null) "全部评论"
                    else "全部评论(${moduleStat.reply})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                HorizontalDivider()
            }
        }
    )
}