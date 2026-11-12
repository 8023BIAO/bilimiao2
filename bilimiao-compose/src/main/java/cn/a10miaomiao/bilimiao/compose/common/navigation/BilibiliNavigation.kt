package cn.a10miaomiao.bilimiao.compose.common.navigation

import android.app.Activity
import android.net.Uri
import android.util.TypedValue
import android.view.View
import androidx.browser.customtabs.CustomTabsIntent
import androidx.navigation.NavController
import androidx.navigation.Navigation
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.pages.article.ArticleReaderPage
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.BangumiDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.dynamic.DynamicDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.SeasonCheckPage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import cn.a10miaomiao.bilimiao.compose.pages.web.WebPage
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.comm.toast
import java.util.regex.Pattern
import kotlin.math.roundToLong
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
        // 完整视频链接里的 `?t=` 秒数（见 parseSeekMs）。四条视频分支共用，
        // 非视频分支不会用到它，解析一次即可。
        val seekMs = parseSeekMs(uri)
        // 分P只在带 t 时有意义：B 站多P链接的 t 是相对「链接指定/默认 P1」的时间，
        // 所以没有 p 就按 P1（与 B 站语义一致）。不带 t 的链接 pageIndex 一律 null，
        // 行为与改动前完全一致。
        val pageIndex = if (seekMs != null) parsePageIndex(uri) ?: 1 else null

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
                return openVideo(pageNavigation, "BV$id", seekMs, pageIndex)
            }
            compile = Pattern.compile("av(\\d+)")
            matcher = compile.matcher(path.lowercase())
            if (matcher.find()) {
                return openVideo(pageNavigation, matcher.group(1), seekMs, pageIndex)
            }
            // 视频深链未匹配到 id 时交给路由表（类型安全路由兜底）
            return pageNavigation.navigateByUri(uri)
        }

        if (scheme == "http" || scheme == "https") {
            // ── 动态/opus 进「动态详情」，专栏(cv) 才进专栏阅读页 ──
            // 之前 www.bilibili.com/opus/{id} 匹配不到任何目的地，会一路落到内嵌
            // 浏览器(WebPage)，用户看到的就是"卡在中间页"；页面里再唤起 bilibili://
            // 又被忽略，只能手动返回。t.bilibili.com/{id} 是老动态分享页，
            // 只在 id 是 opus 长 id 时才接管，短 id 仍走原来的网页兜底，避免取错接口。
            if (host == "www.bilibili.com" || host == "bilibili.com" || host == "m.bilibili.com") {
                // /opus/{id}：动态（opus 长 id）→ 动态详情
                val opusId = Regex("^/opus/(\\d+)").find(path)?.groupValues?.get(1)
                if (opusId != null) {
                    opusId.toLongOrNull()?.let {
                        pageNavigation.navigate(DynamicDetailPage(it.toString()))
                        return true
                    }
                }
                // /read/cv{id}、/read/mobile/{id}：专栏 → 专栏阅读页
                val cvId = Regex("(?i)^/read/cv(\\d+)").find(path)?.groupValues?.get(1)
                    ?: Regex("^/read/mobile/(\\d+)").find(path)?.groupValues?.get(1)
                if (cvId != null) {
                    cvId.toLongOrNull()?.let {
                        pageNavigation.navigate(ArticleReaderPage(it))
                        return true
                    }
                }
            } else if (host == "t.bilibili.com") {
                val dynId = Regex("^/(\\d+)").find(path)?.groupValues?.get(1)
                val dynIdLong = dynId?.toLongOrNull()
                if (dynIdLong != null && dynIdLong >= 1_000_000_000_000L) {
                    pageNavigation.navigate(DynamicDetailPage(dynIdLong.toString()))
                    return true
                }
            }
            var compile = Pattern.compile("BV([a-zA-Z0-9]{5,})")
            var matcher = compile.matcher(path)
            if (matcher.find()) {
                val id = matcher.group(1)
                return openVideo(pageNavigation, "BV$id", seekMs, pageIndex)
            }
            compile = Pattern.compile("av(\\d+)")
            matcher = compile.matcher(path.lowercase())
            if (matcher.find()) {
                return openVideo(pageNavigation, matcher.group(1), seekMs, pageIndex)
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
            pageNavigation.navigate(VideoDetailPage(aid), cn.a10miaomiao.bilimiao.compose.common.defaultNavOptions)
            return true
        }

        return pageNavigation.navigateByUri(uri)
    }

    /**
     * 解析完整视频链接里的 `?t=` 秒数（可带小数，如 `529.7` → 529700ms）。
     *
     * 为什么统一抽出来：BV/av、深链/网页一共四条视频分支都要用；`t` 缺失、非数字、
     * `NaN`/无穷、`<= 0` 一律当成"没带 t"（不 seek）—— 脏参数不该把用户莫名跳到片头。
     */
    private fun parseSeekMs(uri: Uri): Long? {
        // 链接正文来自评论区，畸形转义（如 `t=100%`）会让 getQueryParameter 抛异常；
        // 不能因为一个坏 query 崩掉整次跳转，当作没带 t 处理。
        val raw = runCatching { uri.getQueryParameter("t") }.getOrNull() ?: return null
        val seconds = raw.toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds <= 0.0) return null
        return (seconds * 1000).roundToLong()
    }

    /**
     * 解析完整视频链接里的 `?p=` 分P（从 1 开始）。
     *
     * 为什么只认正整数：`p=0`、负数、`p=abc` 都是脏参数，一律当成"没带 p"（调用方按 P1 处理），
     * 免得把用户指到一个不存在的分P上。
     */
    private fun parsePageIndex(uri: Uri): Int? {
        val raw = runCatching { uri.getQueryParameter("p") }.getOrNull() ?: return null
        return raw.toIntOrNull()?.takeIf { it > 0 }
    }

    /**
     * 打开视频详情页（四条视频分支的唯一入口）。
     *
     * 为什么先问 [VideoLinkSeekBus]：带 `?t=` 且目标就是当前详情页那条视频时，再压一层
     * 详情页会让整页（含播放器/评论区）重建、返回还要多按一次；交给当前页原地 seek 才是
     * 用户预期。当前页接不住（不是它的视频 / 详情还没加载好）时由这里正常导航兜底。
     * 分P也必须一起带过去：多P视频的 `t` 是相对指定 P 的，选错 P 时间点就落到别的内容上了。
     */
    private fun openVideo(
        pageNavigation: PageNavigation,
        id: String,
        seekMs: Long?,
        pageIndex: Int?,
    ): Boolean {
        if (seekMs != null && VideoLinkSeekBus.onSeek?.invoke(id, seekMs, pageIndex) == true) {
            return true
        }
        pageNavigation.navigate(
            VideoDetailPage(id, seekPosition = seekMs, pageIndex = pageIndex),
            cn.a10miaomiao.bilimiao.compose.common.defaultNavOptions
        )
        return true
    }

    /**
     * 番剧兜底专用：把"站内跳转地址"（`x/web-interface/view` 的 `redirect_url`）解析成**番剧详情页**。
     *
     * ★ 目标必须是 [BangumiDetailPage]，**不能**用 [SeasonCheckPage]：
     *   `SeasonCheckPage` 只拿到 `epId` 时会走 `detectPvSeason` 的 ep-only 分支 —— 那个分支不是 PV 检测，
     *   而是"取本季第 1 集"，于是跳到 `VideoDetailPage(本季第 1 集的 bvid)`；这类稿件的 bvid 在 UGC 的
     *   `View/View` 里同样是 -404 ⇒ 再兜底 ⇒ **成环**（复核员 2026-10-01 实测：`ep5578285` 属第 24 集，
     *   第 1 集是 `BV1tYud6hEVF`，两者互为环）。直接进番剧页绕开这个启发式。
     *
     * 为什么单独一个**只解析、不导航**的函数：调用方（VideoDetailViewModel 的番剧兜底）要的是
     * "目标页 + `popUpTo(当前视频页){inclusive=true}`"这套**自替换**，而 [navigationTo] 只能把页面压上去 ——
     * 换成"先压栈、再弹自己"会把刚压上去的目标页一起弹掉（`popBackStack(route, inclusive)` 连上面的页一起弹）。
     *
     * 只认三类，其余一律 null（由调用方退回人话错误，不猜、不透传）：
     *   · `/bangumi/play/ep123` → [BangumiDetailPage]（epId）
     *   · `/bangumi/play/ss123` → [BangumiDetailPage]（id = 季）
     *   · `…/media/md123`       → [BangumiDetailPage]（mediaId）
     */
    fun pgcPageOf(url: String): ComposePage? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        val path = uri.path ?: return null
        Regex("(?i)^/bangumi/play/ep(\\d+)").find(path)?.let {
            return BangumiDetailPage(epId = it.groupValues[1])
        }
        Regex("(?i)^/bangumi/play/ss(\\d+)").find(path)?.let {
            return BangumiDetailPage(id = it.groupValues[1])
        }
        Regex("(?i)^/bangumi/media/md(\\d+)").find(path)?.let {
            return BangumiDetailPage(mediaId = it.groupValues[1])
        }
        return null
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