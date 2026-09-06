package cn.a10miaomiao.bilimiao.compose.common.navigation

import android.app.Activity
import android.net.Uri
import android.util.TypedValue
import android.view.View
import androidx.browser.customtabs.CustomTabsIntent
import androidx.navigation.NavController
import androidx.navigation.Navigation
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.BangumiDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.SeasonCheckPage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import cn.a10miaomiao.bilimiao.compose.pages.web.WebPage
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.comm.toast
import java.util.regex.Pattern
import cn.a10miaomiao.bilimiao.compose.pages.video.VideoDetailPage

object BilibiliNavigation {

    private fun isNumeric(str: String): Boolean {
        val pattern = Pattern.compile("[0-9]*")
        return pattern.matcher(str).matches()
    }

    fun navigationTo(
        pageNavigation: PageNavigation,
        url: String,
    ): Boolean {
        miaoLogger() debug url
        val uri = Uri.parse(url)
        val scheme = uri.scheme
        val host = uri.host
        val path = uri.path ?: ""

        // ---- 视频路由只匹配 path，且仅限视频深链/视频 URL ----
        // 之前用 find() 扫整串 URL（含 query 参数），opus/动态深链里夹带的
        // bvid= 兜底参数（如 BV1xx411c7mC）会被抢跳成视频详情页。
        val isVideoDeepLink = (scheme == "bilibili" || scheme == "bilimiao") &&
                (host == "video" || path.startsWith("/video/"))
        if (isVideoDeepLink) {
            var compile = Pattern.compile("BV([a-zA-Z0-9]{5,})")
            var matcher = compile.matcher(path)
            if (matcher.find()) {
                val id = matcher.group(1)
                pageNavigation.navigate(VideoDetailPage("BV$id"))
                return true
            }
            compile = Pattern.compile("av(\\d+)")
            matcher = compile.matcher(path.lowercase())
            if (matcher.find()) {
                pageNavigation.navigate(VideoDetailPage(matcher.group(1)))
                return true
            }
            // 视频深链未匹配到 id 时交给路由表（类型安全路由兜底）
            return pageNavigation.navigateByUri(uri)
        }

        if (scheme == "http" || scheme == "https") {
            var compile = Pattern.compile("BV([a-zA-Z0-9]{5,})")
            var matcher = compile.matcher(path)
            if (matcher.find()) {
                val id = matcher.group(1)
                pageNavigation.navigate(VideoDetailPage("BV$id"))
                return true
            }
            compile = Pattern.compile("av(\\d+)")
            matcher = compile.matcher(path.lowercase())
            if (matcher.find()) {
                pageNavigation.navigate(VideoDetailPage(matcher.group(1)))
                return true
            }
            compile = Pattern.compile("ss(\\d+)")
            matcher = compile.matcher(url)
            if (matcher.find()) {
                pageNavigation.navigate(
                    SeasonCheckPage(
                        id = matcher.group(1)
                    )
                )
                return true
            }
            compile = Pattern.compile("ep(\\d+)")
            matcher = compile.matcher(url)
            if (matcher.find()) {
                pageNavigation.navigate(
                    SeasonCheckPage(
                        epId = matcher.group(1)
                    )
                )
                return true
            }
            compile = Pattern.compile("md(\\d+)")
            matcher = compile.matcher(url)
            if (matcher.find()) {
                pageNavigation.navigate(
                    SeasonCheckPage(
                        mediaId = matcher.group(1)
                    )
                )
                return true
            }
        }
        if (host == "space.bilibili.com") {
            val midPath = path.replace("/", "")
            val mid = if (isNumeric(midPath)) { midPath } else { "" }
            if (mid.isNotBlank()) {
                pageNavigation.navigate(
                    UserSpacePage(mid)
                )
                return true
            }
        }
        val queryParameterNames = uri.queryParameterNames
        if (queryParameterNames.contains("avid")) {
            val aid = uri.getQueryParameter("avid") ?: ""
            pageNavigation.navigate(VideoDetailPage(aid))
            return true
        }

        return pageNavigation.navigateByUri(uri)
    }

    fun navigationToWeb(
        pageNavigation: PageNavigation,
        url: String,
    ) {
        val uri = Uri.parse(
            if ("://" in url) {
                url
            } else {
                "http://$url"
            }
        )
        if (uri.scheme != "http" && uri.scheme != "https") {
            toast("不支持的链接：${url}")
            return
        }
        val host = uri.host ?: ""
        if (isAllowedWebHost(host)) {
            // b站网页使用内部浏览器打开
            pageNavigation.navigate(
                WebPage(url)
            )
        } else {
            // 非B站网页使用外部浏览器打开
            pageNavigation.launchWebBrowser(uri)
        }
    }

    /** 内嵌浏览器域名白名单：精确域名或其子域名，防止 bilibili.com.evil.com 之类伪造域名混入 */
    private val WEB_ALLOWED_HOSTS = listOf(
        "bilibili.com",
        "bilibili.tv",
        "b23.tv",
        "b23.snm0516.aisee.tv",
    )

    fun isAllowedWebHost(host: String): Boolean {
        if (host.isBlank()) return false
        return WEB_ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

}