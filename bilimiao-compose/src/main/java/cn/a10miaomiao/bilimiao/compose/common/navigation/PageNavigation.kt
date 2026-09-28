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
    /**
     * ★页内浮层（UP 空间浮层，见 `pages/user/UserSpaceOverlayHost.kt`）的**路由闸门**；
     * **默认 `null` = 与没有这个参数时逐字节一致**（既有 3 个构造点一行都不用改）。
     *
     * 为什么要在类里留这么一个 seam、而不是让浮层"子类化 / 包一层"：
     * 1. 本类是 `final class`、[navigate] 是 `final fun`（Kotlin 默认 final）→ 覆写不了；
     * 2. navigation 2.10.1 里 type-safe 的 `NavController.navigate(T, …)` 也是 **final**，
     *    内部直达 `NavControllerImpl.navigate$navigation_runtime`，不经过任何可覆写的公开方法
     *    （`javap` 实证）；`androidx.navigation.compose.ComposeNavigator` 同样是 **final class**，
     *    连"换掉 Navigator 拦一跳"这条路也没有。
     * ⇒ 浮层**拿不到**"用户这次点了哪个 `ComposePage`"这个对象，也就没法把它交给主界面那份
     *   `PageNavigation`（app 侧的 `handOffToMainHost` 依赖"浮层持的就是主界面那套导航"）。
     *   所以钩子只能留在这里：返回 `true` = 这次导航**已被接管**，本方法不再导航。
     */
    private val routeGate: ((ComposePage) -> Boolean)? = null,
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
        // ★闸门（只有页内浮层那份会传）：true = 这次导航已被浮层接管（它已把 page 交给主界面那份），
        //   本方法的其余逻辑一行都不走。主界面那份传的是 null ⇒ 这一行对既有行为等于不存在。
        //   放在最前面（连"连点指纹"之前）：被接管的导航不该污染本实例的指纹状态。
        if (routeGate?.invoke(route) == true) return
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
        val page = VideoDetailPage(id)
        // ★闸门：同 [navigate]。这条入口**绕过**了上面的 navigate（直接调 hostController），
        //   所以必须单独插一行 —— 否则浮层里点视频卡片会开在浮层自己那张小路由表里（找不到目的地
        //   或"在看不见的地方播视频"），而不是交回主界面。
        if (routeGate?.invoke(page) == true) return
        hostController.navigate(page, cn.a10miaomiao.bilimiao.compose.common.defaultNavOptions)
    }

    fun launchWebBrowser(url: String) {
        launchWebBrowser(Uri.parse(url))
    }

    fun launchWebBrowser(uri: Uri) {
        launchUrl(uri)
    }

}