package cn.a10miaomiao.bilimiao.compose.pages.dynamic

import android.net.Uri
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.BilibiliNavigation
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.pages.article.ArticleParagraph
import cn.a10miaomiao.bilimiao.compose.pages.article.ImageParagraphItem
import cn.a10miaomiao.bilimiao.compose.pages.article.TextParagraphItem
import cn.a10miaomiao.bilimiao.compose.pages.article.parseArticleParagraphs
import cn.a10miaomiao.bilimiao.compose.pages.community.MainReplyListPageContent
import cn.a10miaomiao.bilimiao.compose.pages.community.MainReplyViewModel
import cn.a10miaomiao.bilimiao.compose.components.status.BiliFailBox
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.store.WindowStore.Insets
import com.a10miaomiao.bilimiao.comm.toast
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.json.JSONObject
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
data class DynamicOpusPage(
    private val id: String,
) : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel = diViewModel(key = "dynamic$id") {
            DynamicOpusPageViewModel(it, id)
        }
        DynamicOpusPageContent(viewModel)
    }

}

/** opus 详情渲染模型（由 HTTP web-dynamic opus detail 解析而来） */
private data class OpusDetailData(
    val title: String = "",
    val authorName: String = "",
    val authorFace: String = "",
    val authorMid: Long = 0,
    val topicName: String = "",
    val topicUrl: String = "",
    val paragraphs: List<ArticleParagraph> = emptyList(),
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    val forwardCount: Int = 0,
    val commentId: String = "",
)

private class DynamicOpusPageViewModel(
    override val di: DI,
    val dynId: String,
) : ViewModel(), DIAware {

    /**
     * 深链可能带 query（如 ?jump_opus=1&…），B站长链接把字符整体编码进 path（%3F）时
     * Navigation 也会把 ? 后的内容按路径段当作 {id}，导致 toLong 抛
     * "For input string: 123?jump_opus=1" → 页面"不显示"。这里先剥离再去解析。
     */
    private val cleanDynId = dynId.substringBefore('?').substringBefore('#').trim()

    private val pageNavigation: PageNavigation by instance()

    private val _loading = MutableStateFlow(false);
    val loading: StateFlow<Boolean> get() = _loading

    private val _fail = MutableStateFlow<Any?>(null)
    val fail: StateFlow<Any?> get() = _fail

    private val _detailData = MutableStateFlow<OpusDetailData?>(null)
    val detailData: StateFlow<OpusDetailData?> get() = _detailData

    init {
        if (cleanDynId.isNotBlank()) {
            loadData()
        }
    }

    fun loadData() = viewModelScope.launch(Dispatchers.IO) {
        try {
            _loading.value = true
            _fail.value = null
            if (cleanDynId.isBlank() || cleanDynId.toLongOrNull() == null) {
                _fail.value = IllegalArgumentException("无效的动态ID: $dynId")
                return@launch
            }
            // 走官方 web-dynamic opus 详情（带登录态/匿名 buvid 均可，与 PiliPlus 一致；
            // 旧 gRPC OpusDetail 的 shareMode/shareId 参数已取不到正文内容）
            val response = MiaoHttp.request {
                url = BiliApiService.biliApi(
                    "x/polymer/web-dynamic/v1/opus/detail",
                    "id" to cleanDynId,
                    "features" to "htmlNewStyle",
                    "timezone_offset" to "-480",
                )
            }.awaitCall()
            val json = JSONObject(response.body?.string() ?: "")
            val code = json.optInt("code", -1)
            if (code != 0) {
                _fail.value = IllegalArgumentException(
                    "加载失败: code=$code message=${json.optString("message")}"
                )
                return@launch
            }
            val data = json.optJSONObject("data") ?: run {
                _fail.value = IllegalArgumentException("数据为空")
                return@launch
            }
            val item = data.optJSONObject("item")
            val modules = item?.optJSONArray("modules")

            var detail = OpusDetailData()
            if (modules != null) {
                for (i in 0 until modules.length()) {
                    val m = modules.optJSONObject(i) ?: continue
                    when (m.optString("module_type")) {
                        "MODULE_TYPE_TITLE" -> {
                            val title = m.optJSONObject("module_title")?.optString("text") ?: ""
                            detail = detail.copy(title = title)
                        }
                        "MODULE_TYPE_AUTHOR" -> {
                            val a = m.optJSONObject("module_author")
                            detail = detail.copy(
                                authorName = a?.optString("name") ?: "",
                                authorFace = a?.optString("face") ?: "",
                                authorMid = a?.optLong("mid") ?: 0L,
                            )
                        }
                        "MODULE_TYPE_TOPIC" -> {
                            val t = m.optJSONObject("module_topic")
                            detail = detail.copy(
                                topicName = t?.optString("name") ?: "",
                                topicUrl = t?.optString("jump_url") ?: "",
                            )
                        }
                        "MODULE_TYPE_CONTENT" -> {
                            val c = m.optJSONObject("module_content")
                            detail = detail.copy(
                                paragraphs = parseArticleParagraphs(c?.optJSONArray("paragraphs"))
                            )
                        }
                        "MODULE_TYPE_STAT" -> {
                            val s = m.optJSONObject("module_stat")
                            detail = detail.copy(
                                likeCount = s?.optJSONObject("like")?.optInt("count") ?: 0,
                                replyCount = s?.optJSONObject("comment")?.optInt("count") ?: 0,
                                forwardCount = s?.optJSONObject("forward")?.optInt("count") ?: 0,
                            )
                        }
                    }
                }
            }
            // opus 的评论挂在 comment_id_str（basic 内），与 opus id 不同
            val commentId = item?.optJSONObject("basic")?.optString("comment_id_str")
                ?.takeIf { it.isNotBlank() } ?: cleanDynId
            _detailData.value = detail.copy(commentId = commentId)
        } catch (e: Exception) {
            _fail.value = e
            toast("网络错误")
            e.printStackTrace()
        } finally {
            _loading.value = false
        }
    }

    fun toAuthorPage() {
        val mid = _detailData.value?.authorMid ?: return
        if (mid > 0) {
            pageNavigation.navigateByUri(Uri.parse("bilibili://space/$mid"))
        }
    }

    fun openTopic() {
        val url = _detailData.value?.topicUrl ?: return
        if (url.isNotBlank()) {
            BilibiliNavigation.navigationToWeb(pageNavigation, url)
        }
    }

    /** 页面原始 id（用于评论接口兜底） */
    val rawId: String get() = cleanDynId

    fun menuItemClick(view: View, item: MenuItemPropInfo) {
        when (item.key) {
        }
    }
}


@Composable
private fun DynamicOpusPageContent(
    viewModel: DynamicOpusPageViewModel
) {
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val detailData = viewModel.detailData.collectAsState().value

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
                loading = viewModel.loading.collectAsState().value,
                fail = viewModel.fail.collectAsState().value,
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
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun DynamicDetailPageDetailContent(
    viewModel: DynamicOpusPageViewModel,
    windowInsets: Insets,
    detailData: OpusDetailData,
) {
    val commentId = detailData.commentId.ifBlank { viewModel.rawId }
    val replyViewModel = diViewModel(
        key = "dynamic.reply.${commentId}"
    ) {
        MainReplyViewModel(it, commentId, type = 11)
    }
    val replyList by replyViewModel.list.data.collectAsState()
    val replyListLoading by replyViewModel.list.loading.collectAsState()
    val replyListFinished by replyViewModel.list.finished.collectAsState()
    val replyListFail by replyViewModel.list.fail.collectAsState()

    val pageTitle = detailData.title.ifBlank { "动态详情" }
    PageConfig(title = pageTitle)

    MainReplyListPageContent(
        viewModel = replyViewModel,
        pageTitle = pageTitle,
        headerContent = {
            item {
                Column(
                    modifier = Modifier
                        .padding(top = windowInsets.topDp.dp)
                        .padding(bottom = 5.dp),
                ) {
                    // 标题
                    if (detailData.title.isNotBlank()) {
                        Text(
                            text = detailData.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // 作者
                    if (detailData.authorName.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toAuthorPage() }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (detailData.authorFace.isNotBlank()) {
                                GlideImage(
                                    model = UrlUtil.autoHttps(detailData.authorFace),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(36.dp).clip(CircleShape),
                                )
                            }
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = detailData.authorName,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    // 话题
                    if (detailData.topicName.isNotBlank()) {
                        Surface(
                            onClick = { viewModel.openTopic() },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "# ${detailData.topicName}",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    // 正文段落
                    detailData.paragraphs.forEach { paragraph ->
                        when (paragraph) {
                            is ArticleParagraph.TextParagraph -> TextParagraphItem(paragraph)
                            is ArticleParagraph.ImageParagraph -> ImageParagraphItem(paragraph)
                        }
                    }
                    // 统计数据
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = "转发 ${detailData.forwardCount}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "评论 ${detailData.replyCount}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "点赞 ${detailData.likeCount}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Text(
                    modifier = Modifier
                        .padding(
                            top = 10.dp,
                            bottom = 5.dp,
                            start = 10.dp,
                            end = 10.dp,
                        ),
                    text = if (detailData.replyCount > 0) "全部评论(${detailData.replyCount})"
                    else "全部评论",
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
