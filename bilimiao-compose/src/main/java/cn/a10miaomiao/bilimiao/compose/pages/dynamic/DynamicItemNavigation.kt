package cn.a10miaomiao.bilimiao.compose.pages.dynamic

import android.net.Uri
import bilibili.app.dynamic.v2.DynamicItem
import cn.a10miaomiao.bilimiao.compose.common.defaultNavOptions
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation

/**
 * 动态卡片点击的统一入口（动态页 / 关注人动态 / 空间动态 / 空间搜索动态 共用）。
 *
 * 以前这 4 个列表都是 `navigateByUri(Uri.parse(extend.cardUrl))`：
 * 图文、纯文字这类 opus 动态的 cardUrl 是 `bilibili://opus/{长id}`，正好撞上
 * `PageNavigation.navigateByUri` 里“opus 深链统一进专栏页”那条拦截 → 用户点的是**动态**，
 * 打开的却是「专栏」阅读页（正文按文章排版，还只渲染了段落里的第一张图）。
 *
 * 现在按“是不是 opus 类动态”分流：
 *  - opus 类 → [DynamicDetailPage] 原生动态详情（gRPC dynDetail 返回完整 modules：
 *    作者/正文/整组图/统计，实测 5/5 图文动态都能取到全文 + 全部图片）；
 *  - 其它（视频/番剧/直播/转发/专栏…）→ 保持原样按 cardUrl 走原来的路由
 *    （转发动态的 cardUrl 是 `bilibili://following/detail/{id}`，本来就进 [DynamicDetailPage]）。
 *
 * @return true 表示已经发起导航
 */
fun PageNavigation.navigateToDynamic(item: DynamicItem): Boolean {
    val extend = item.extend ?: return false
    val cardUrl = extend.cardUrl
    if (isOpusDeepLink(cardUrl)) {
        // id 优先取 cardUrl 里的（与它当初被专栏页接走时用的是同一个 id），拿不到再用 dynIdStr
        val dynId = opusIdOf(cardUrl) ?: extend.dynIdStr.takeIf { it.isNotBlank() }
        if (dynId != null) {
            // 用 defaultNavOptions（不带 launchSingleTop）：从动态详情里的“转发内容”再点开
            // 另一条动态时，不能被单栈顶当成连点把当前页替换掉（同 navigateToVideoInfo 那条链的坑）
            navigate(DynamicDetailPage(dynId), defaultNavOptions)
            return true
        }
    }
    if (cardUrl.isBlank()) return false
    return runCatching { navigateByUri(Uri.parse(cardUrl)) }.getOrDefault(false)
}

/** `bilibili://opus/{id}` / `bilimiao://opus/{id}` */
private fun isOpusDeepLink(url: String): Boolean {
    if (url.isBlank()) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    return (uri.scheme == "bilibili" || uri.scheme == "bilimiao") && uri.host == "opus"
}

/** 从 `bilibili://opus/{id}` 里取 opus/动态长 id（剥掉可能被整体编码进来的 query/# 残留） */
private fun opusIdOf(url: String): String? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    return (uri.path ?: "")
        .substringAfterLast('/')
        .substringBefore('?')
        .substringBefore('#')
        .trim()
        .takeIf { it.isNotBlank() }
}
