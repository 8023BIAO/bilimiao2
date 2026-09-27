package cn.a10miaomiao.bilimiao.compose.pages.user

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.a10miaomiao.bilimiao.compose.common.constant.PageTabIds
import cn.a10miaomiao.bilimiao.compose.pages.user.content.UserArchiveListContent
import cn.a10miaomiao.bilimiao.compose.pages.user.content.UserArticleListContent
import cn.a10miaomiao.bilimiao.compose.pages.user.content.UserDynamicListContent
import cn.a10miaomiao.bilimiao.compose.pages.user.content.UserSpaceIndexContent

sealed class UserSpacePageTabs(
    val id: String,
    val name: String,
) {
    @Composable
    abstract fun PageContent()

    data class Index(
        val viewModel: UserSpaceViewModel,
    ) : UserSpacePageTabs(
        id = PageTabIds.UserIndex,
        name = "主页"
    ) {
        @Composable
        override fun PageContent() {
            UserSpaceIndexContent(viewModel)
        }
    }

    data class Dynamic(
        val vmid: String,
    ) : UserSpacePageTabs(
        id = PageTabIds.UserDynamic,
        name = "动态"
    ) {
        @Composable
        override fun PageContent() {
            UserDynamicListContent(vmid)
        }
    }

    data class Archive(
        val viewModel: UserArchiveViewModel,
    ) : UserSpacePageTabs(
        id = PageTabIds.UserArchive,
        name = "投稿"
    ) {
        @Composable
        override fun PageContent() {
            UserArchiveListContent(viewModel)
        }
    }

    /**
     * 第 4 个 tab「专栏」（对齐 PiliPlus `MemberArticle`）。
     * 作者名从用户空间主数据取（卡片上展示 UP 名）。
     */
    data class Article(
        val userViewModel: UserSpaceViewModel,
        val articleViewModel: UserArticleViewModel,
    ) : UserSpacePageTabs(
        id = PageTabIds.UserArticle,
        name = "专栏",
    ) {
        @Composable
        override fun PageContent() {
            val authorName = userViewModel
                .detailData
                .collectAsStateWithLifecycle()
                .value
                ?.card
                ?.name
                .orEmpty()
            UserArticleListContent(
                viewModel = articleViewModel,
                authorName = authorName,
            )
        }
    }

}