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
import cn.a10miaomiao.bilimiao.compose.pages.article.ArticleReaderPage
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
        // opus 深链统一进专栏页（与搜索入口一致，两个入口共用 ArticleReaderPage）。
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
                    hostController.navigate(ArticleReaderPage(opusId), navOptions {
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
        //   同一个页面 + 同样参数在 600ms 内重复调用 → 直接忽略（连点保护照旧），
        //   参数不同（或过了窗口）→ 正常交给上层 navOptions 处理。
        // 指纹 = 页面自己给的 navDedupeKey（带参数的那种，比如 "VideoDetailPage/BV1xx"）；
        // 页面没给（null）= 不做去重，行为与从前完全一致。
        val signature = route.navDedupeKey
        val now = android.os.SystemClock.uptimeMillis()
        if (signature != null && signature == lastNavSignature && now - lastNavAt < 600L) return
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

    fun navigateToVideoInfo(id: String) {
        hostController.navigate(VideoDetailPage(id), navOptions {
            launchSingleTop = true
        })
    }

    fun launchWebBrowser(url: String) {
        launchWebBrowser(Uri.parse(url))
    }

    fun launchWebBrowser(uri: Uri) {
        launchUrl(uri)
    }

}