package cn.a10miaomiao.bilimiao.compose.pages.filter

import androidx.compose.runtime.Composable
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.pages.filter.content.FilterHomeContent
import cn.a10miaomiao.bilimiao.compose.pages.filter.content.FilterSection
import kotlinx.serialization.Serializable

/**
 * 设置重新分类（6 大类）后，原来挤在「屏蔽设置 → 其他」tab 里的两块内容各自独立成页：
 *  - 推荐过滤：时长/播放量/封面/相关推荐/白名单/推广/标签严格（看什么、不看什么）
 *  - 评论区：评论关键字 + 显示二级回复
 * 两者仍复用同一个 [FilterHomeContent]，只是用 section 过滤显示哪一半（避免复制粘贴出两份逻辑）。
 */
@Serializable
class FilterRecommendSettingPage : ComposePage() {
    @Composable
    override fun Content() {
        // 独立成页后必须自己声明标题，否则顶栏没有标题/返回
        PageConfig(title = "推荐过滤")
        FilterHomeContent(setOf(FilterSection.RECOMMEND), applyTopInset = true)
    }
}

@Serializable
class FilterCommentSettingPage : ComposePage() {
    @Composable
    override fun Content() {
        PageConfig(title = "评论区")
        FilterHomeContent(setOf(FilterSection.COMMENT), applyTopInset = true)
    }
}
