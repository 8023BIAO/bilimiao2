package cn.a10miaomiao.bilimiao.compose.common.navigation

import android.net.Uri
import android.util.TypedValue
import androidx.annotation.MainThread
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.NavDeepLinkRequest
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.Navigator
import androidx.navigation.navOptions
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.pages.dynamic.DynamicDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.video.VideoDetailPage
import cn.a10miaomiao.bilimiao.compose.common.defaultNavOptions
import cn.a10miaomiao.bilimiao.compose.common.singleTopNavOptions
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger

class PageNavigation(
    private val navHostController: () -> NavHostController,
    private val launchUrl: (uri: Uri) -> Unit,
    private val onClose: () -> Unit = {},
) {

    val hostController get() = navHostController()

    fun navigateByUri(deepLink: Uri): Boolean {
        // opus 深链（bilibili://opus/{id}、bilibili://opus/detail/{id}）→ **动态详情页**。
        // vc83 曾把它们统一合进专栏页(ArticleReaderPage)，结果"点动态进专栏"（动态本来就有专门的动态页）。
        // 实测：gRPC 动态详情对这类 opus 长 id 5/5 都能取到正文 + 全部图片；专栏页那条数据源
        // (x/polymer/web-dynamic/v1/opus/detail) 反而有 2/5 的图藏在 MODULE_TYPE_TOP 里取不到。
        // 专栏(cv)链接不走这里：/read/cv{id}、bilimiao://article/{id} 由路由表进专栏页。
        // 先剥离 query/编码残留再解析，避免 "For input string: 123?jump_opus=1" 崩溃
        if ((deepLink.scheme == "bilibili" || deepLink.scheme == "bilimiao")
            && deepLink.host == "opus"
        ) {
            val rawId = (deepLink.path ?: "")
                .substringAfterLast('/')
                .substringBefore('?')
                .substringBefore('#')
                .trim()
            val opusId = rawId.toLongOrNull()
            if (opusId != null) {
                return runCatching {
                    hostController.navigate(DynamicDetailPage(opusId.toString()), navOptions {
                        launchSingleTop = true
                    })
                }.isSuccess.also {
                    if (!it) miaoLogger() debug "[NotFoundPage]:opus=$opusId"
                }
            }
        }
        return runCatching {
            hostController.navigate(deepLink, navOptions {
                launchSingleTop = true
            })
        }.isSuccess.also {
            if (!it) miaoLogger() debug "[NotFoundPage]:deepLink=${deepLink}"
        }
    }

    /** 上一次导航的"页面+参数"指纹与时刻：只用来挡"同一个入口连点两次"（见 navigate） */
    private var lastNavSignature: String? = null
    private var lastNavAt = 0L

    fun <T : ComposePage> navigate(
        route: T,
        navOptions: NavOptions? = null,
        navigatorExtras: Navigator.Extras? = null
    ) {
        // ★ 默认补 launchSingleTop：连点同一个入口 N 次不再往返回栈压 N 层
        //   （全工程 40+ 个调用点没传 navOptions，以前它们全裸着 —— 用户要按 N 次返回）
        //
        // ★ 但"同一个路由、不同参数"的导航绝不能按连点处理：导航框架的 launchSingleTop 只看路由、
        //   不看参数，会把当前页**替换**掉（相关视频点进另一个视频就是这种，后果见
        //   VideoDetailViewModel.toVideoPage 的注释）。所以这里补一道 600ms 的同指纹闸门：
        //   同一个页面 + 同样参数在 1000ms 内重复调用 → 直接忽略（连点保护照旧；窗口取 1 秒是为了
        //   把"慢一点的双击"也挡住 —— 相关视频去掉 singleTop 之后没有别的兜底），
        //   参数不同（或过了窗口）→ 正常交给上层 navOptions 处理。
        // 指纹 = 页面自己给的 navDedupeKey（带参数的那种，比如 "VideoDetailPage/BV1xx"）；
        // 页面没给（null）= 不做去重，行为与从前完全一致。
        val signature = route.navDedupeKey
        val now = android.os.SystemClock.uptimeMillis()
        if (signature != null && signature == lastNavSignature && now - lastNavAt < 1000L) return
        lastNavSignature = signature
        lastNavAt = now
        hostController.navigate(route, navOptions ?: singleTopNavOptions, navigatorExtras)
    }

    fun <T : ComposePage> navigate(
        route: T,
        builder: NavOptionsBuilder.() -> Unit
    ) {
        // 同理：走 builder 的调用点若没自己写 launchSingleTop，这里兜底
        navigate(route, navOptions {
            launchSingleTop = true
            builder()
        })
    }

    /**
     * 获取当前页面如果是视频详情页的视频ID，否则返回null
     */
    fun getCurrentVideoId(): String? {
        val route = hostController.currentBackStackEntry?.destination?.route ?: return null
        // Type-safe route for VideoDetailPage is VideoDetailPage/{id}
        val pattern = Regex("VideoDetailPage/([A-Za-z0-9_]+)")
        return pattern.find(route)?.groupValues?.getOrNull(1)
    }

    fun popBackStack() {
        if (!hostController.popBackStack()) {
            onClose()
        }
    }

    fun <T : ComposePage> popBackStack(
        route: T,
        inclusive: Boolean,
        saveState: Boolean = false
    ): Boolean {
        val popped = hostController.popBackStack(route, inclusive, saveState)
        if (!popped) {
            onClose()
        }
        return popped
    }

    /**
     * 打开某个视频详情页（首页/历史/消息/动态等卡片都走这里）。
     *
     * 用 defaultNavOptions 而不是 launchSingleTop：后者只看路由不看参数，
     * 当栈顶已经是别的视频详情页时会把当前页**替换**掉（返回直接回上上层、滚动位置不重置、
     * 也没有转场动画）—— 和"相关视频"那条链是同一个坑（见 VideoDetailViewModel.toVideoPage）。
     * 重复点击由 VideoDetailPage 的 navDedupeKey 指纹闸门挡着，不会压两层。
     */
    fun navigateToVideoInfo(id: String) {
        hostController.navigate(VideoDetailPage(id), cn.a10miaomiao.bilimiao.compose.common.defaultNavOptions)
    }

    fun launchWebBrowser(url: String) {
        launchWebBrowser(Uri.parse(url))
    }

    fun launchWebBrowser(uri: Uri) {
        launchUrl(uri)
    }

}