package cn.a10miaomiao.bilimiao.compose.pages.user

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cn.a10miaomiao.bilimiao.compose.BilimiaoTheme
import cn.a10miaomiao.bilimiao.compose.MyBottomSheet
import cn.a10miaomiao.bilimiao.compose.base.BottomSheetState
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.LocalContainerView
import cn.a10miaomiao.bilimiao.compose.common.LocalEmitter
import cn.a10miaomiao.bilimiao.compose.common.LocalPageNavigation
import cn.a10miaomiao.bilimiao.compose.common.addPaddingValues
import cn.a10miaomiao.bilimiao.compose.common.emitter.SharedFlowEmitter
import cn.a10miaomiao.bilimiao.compose.common.mypage.LocalPageConfigState
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfigState
import cn.a10miaomiao.bilimiao.compose.common.navigation.BilibiliNavigation
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.components.dialogs.MessageDialog
import cn.a10miaomiao.bilimiao.compose.components.dialogs.MessageDialogState
import cn.a10miaomiao.bilimiao.compose.components.image.MyImagePreviewer
import cn.a10miaomiao.bilimiao.compose.components.image.provider.ImagePreviewerProvider
import cn.a10miaomiao.bilimiao.compose.components.miao.MiaoTitleBar
import cn.a10miaomiao.bilimiao.compose.pages.mine.MyBangumiPage
import cn.a10miaomiao.bilimiao.compose.pages.mine.MyFollowPage
import com.a10miaomiao.bilimiao.comm.live.SpaceOverlayHandle
import com.a10miaomiao.bilimiao.comm.store.AppStore
import com.a10miaomiao.bilimiao.store.WindowStore
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.compose.rememberInstance
import org.kodein.di.compose.withDI
import kotlin.reflect.KClass

/**
 * 竖屏直播间里的「UP 空间**页内浮层**」宿主（compose 侧实现；app 侧只认 comm 的 [SpaceOverlayHandle]）。
 *
 * ## 它解决什么
 * 用户原话："直播间我点击那个标题进入 UP 的主页，为什么我返回是返回到直播 TAG 的那个页面去，
 * 不是返回到直播间？……路线还是要理成一条线的。"
 * 根因：进空间走的是"让**主界面**的 NavHost 导航 + `finish()` 掉直播页"——直播页一没，
 * 返回自然落到主界面的直播 Tab。唯一能"返回时还在播"的办法就是**直播页不 finish**：
 * 把空间盖在直播页自己的视图树最上层（本浮层），关掉它就是摘一个 View，下面的播放器一行没动。
 *
 * ## 绝不崩（本文件的第一硬要求）
 * 这一版浮层当年被删掉，就是因为线上崩栈：
 * `androidx.startup.StartupException: Binding AppCompatActivity must override an existing binding.`
 * —— 崩在浮层自己那份 `subDI` 的**覆盖校验**上。这次从根上解决，两道：
 * 1. **覆盖语义按 kodein 7.33.0 的真实实现来**（`javap` 读的 `DIContainerBuilderImpl.checkOverrides`）：
 *    `overrides = true` 要求**父容器里已经有同类型（tag + type 完全相同）的绑定**，否则抛
 *    `DI$OverridingException`；反过来，不加 `overrides` 而父容器已有同类型绑定，同样抛。
 *    所以这里**逐条**只覆盖"父容器里确定存在的 5 条"（见 [di]），其余一律不动；
 *    也**不再**绑 `Activity` / `AppCompatActivity` 这类父容器里没有的类型 —— 当年炸的就是它。
 * 2. [create] 把**整个构造过程**（建 DI、建 View、装 insets 监听）包在 `runCatching` 里，
 *    任何异常都返回 `null`；调用方（`LivePlayerActivity`）拿到 null 就走今天的老路
 *    （`LiveSpaceLauncher.open` + finish），**最坏结果是"返回落到直播 Tab"，绝不是闪退**。
 *
 * ## 导航：空间流程内留在浮层，其它交回主界面
 * 浮层自己的 NavHost **只注册 UP 空间流程内的目的地**（[SPACE_FLOW_ROUTES]，白名单与注册表一一对应）。
 * 交给页面的那份 [PageNavigation] 带一个**闸门**（`PageNavigation.routeGate`，task-26 新加的 seam）：
 * · 命中白名单 → 返回 false，照常在**浮层内**导航；
 * · 其它任何目的地（视频详情 / 动态详情 / 番剧 / 播放列表 / 私信…）→
 *   `mainNavigation.navigate(page)`（主界面那份，闭包捕获）+ 下一帧 `onExitToMainHost()`，
 *   浮层关闭、直播页 finish，主界面停在目标页 —— 效果与改动前一致。
 *   这样也**不需要** `PlaybackHandoffPlayerDelegate`：浮层里永远不会去播视频。
 *
 * ## 返回键
 * 浮层**不覆写** `LocalOnBackPressedDispatcherOwner`（保持宿主的 = 直播页）：系统返回键由直播页
 * 统一转发进 [onBack]；页面自己的 `BackHandler`（图片预览、确认弹窗…）挂在直播页的 dispatcher 上，
 * 天然比直播页那个转发回调"后注册、优先级更高"，所以层级顺序是对的。
 * [onBack] 只做一件事：`popBackStack()`；退不动（已经在空间首页）返回 false = **浮层该关了**
 * （直播页只关浮层、不退出直播间）。
 */
class UserSpaceOverlayHost private constructor(
    private val activity: Activity,
    private val mid: Long,
    /** 父容器 = `ComposeFragment` 那份 DI（它往上还挂着 `MainActivity` 的 DI：Store / 播放器委托都在那） */
    private val parentDi: DI,
    /** 主界面那份导航（闭包捕获）：浮层把"不属于空间流程"的目的地交给它 */
    private val mainNavigation: PageNavigation,
    /** 浮层要求直播页让位（关浮层 + finish 本页），由 app 侧实现 */
    private val onExitToMainHost: () -> Unit,
) : SpaceOverlayHandle {

    companion object {
        /**
         * 工厂入口（`ComposeFragment` 注册进 `LiveSpaceLauncher`）。
         *
         * ★`runCatching` 是**本文件的第一硬要求**：DI 覆盖校验、主题键读取、View 构造、
         *   insets 监听……任何一步抛异常都只返回 null，调用方自动走老路 —— 绝不闪退。
         */
        fun create(
            activity: Activity,
            mid: Long,
            parentDi: DI,
            mainNavigation: PageNavigation,
            onExitToMainHost: () -> Unit,
        ): SpaceOverlayHandle? = runCatching {
            UserSpaceOverlayHost(
                activity = activity,
                mid = mid,
                parentDi = parentDi,
                mainNavigation = mainNavigation,
                onExitToMainHost = onExitToMainHost,
            )
        }.getOrNull()
    }

    // ══════════════════════════════════════════════════════════════════════
    // 浮层自己那一套"页内环境"（与主界面**不共用**：各是一份组合、各有各的宿主）
    // ══════════════════════════════════════════════════════════════════════

    private val emitter = SharedFlowEmitter()
    private val messageDialogState = MessageDialogState()
    private val bottomSheetState = BottomSheetState()
    private val pageConfigState = PageConfigState()

    /** 贴底弹窗的容器（`WindowStore.getContentInsets` 认 `tag == "bottomSheet"`，与主界面同一套约定） */
    private val bottomSheetContainer = FrameLayout(activity).apply {
        tag = "bottomSheet"
    }

    /**
     * 浮层自己的 `ViewModelStore`（页面 VM、NavHost 的 VM 都在这里）。
     *
     * 为什么不直接用宿主的（= 直播页的）：`diViewModel` 的 key 里带 `di.hashCode()`，
     * 而每次开浮层都是一份**新的** DI ⇒ key 每次都不同 ⇒ 用宿主的 store 会"每开关一次就堆一套
     * 页面 VM，直到直播页销毁"。给浮层自己一份，[dispose] 里一次 `clear()` 就干净了。
     */
    private val viewModelStore = ViewModelStore()
    private val viewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = this@UserSpaceOverlayHost.viewModelStore
    }

    /** 浮层 NavHost 的控制器；组合第一帧就交给 [overlayNavigation]（顺序坑见类注释） */
    private var navController: NavHostController? = null

    private var disposed = false

    /** 浮层自己的 WindowStore（主界面那份是按主界面容器算的，insets 不一样） */
    private val windowStore: WindowStore by lazy { WindowStore(di) }

    /** 交给浮层内页面的导航：控制器是浮层的，闸门把"空间外"的目的地交回主界面 */
    private val overlayNavigation = PageNavigation(
        navHostController = { navController ?: error("浮层 NavHost 还没就绪") },
        launchUrl = mainNavigation::launchWebBrowser,
        onClose = { closeBySystemBack() },
        routeGate = { route -> gateRoute(route) },
    )

    /**
     * 浮层 DI：`DI { extend(父) }` + **只覆盖 5 条**"必须换成本页"的绑定。
     *
     * · `extend(parentDi)`：把父容器（`ComposeFragment` → `MainActivity`）的绑定**整套**搬进来，
     *   所以 `UserStore` / `PlayerStore` / `AppStore` / 播放器委托……一个都不用重绑；
     * · 逐条 `overrides = true`：kodein 7.33.0 的语义是"**必须**覆盖一个已存在的绑定"
     *   （`DI$OverridingException("Binding X must override an existing binding.")`），
     *   所以这 5 条必须是父容器里**确实存在**的：`PageNavigation` / `MessageDialogState` /
     *   `BottomSheetState` / `SharedFlowEmitter`（`ComposeFragment.di` 绑的）
     *   + `WindowStore`（`MainActivity.di` 的 Store 模块绑的）。
     *   ★当年崩的 `AppCompatActivity` 就是"父容器里没有这个类型却写了 overrides = true" —— 别再绑它；
     *   页面里 `instance<Activity>()` / `instance<Fragment>()` 由 kodein 的**父类型查找**命中父容器里
     *   的 `MainActivity` / `ComposeFragment`，够用（本浮层不换这两个）。
     * · 不带 `allowSilentOverride`（默认 false）：任何我没想到的重复绑定都会当场抛，被 [create] 的
     *   `runCatching` 接住 → 返回 null → 走老路，而不是把 App 崩掉。
     */
    // ★第一参按位置传 = `allowSilentOverride`（**false**）：写名字要押 kodein 的参数名，
    //   位置传只押"第一个参数是那个 Boolean"（`javap` 已确认签名是 `invoke(boolean, MainBuilder.() -> Unit)`）。
    private val di: DI = DI(false) {
        extend(parentDi)
        bindSingleton(overrides = true) { overlayNavigation }
        bindSingleton(overrides = true) { messageDialogState }
        bindSingleton(overrides = true) { bottomSheetState }
        bindSingleton(overrides = true) { emitter }
        bindSingleton(overrides = true) { windowStore }
    }

    init {
        // WindowStore 要先把 density 灌进去（它按 density 算 dp），否则 insets 全是 0dp
        windowStore.init(activity)
    }

    /** 浮层根 View（**未挂载**）：直播页负责 addView / removeView（见 [SpaceOverlayHandle.view]） */
    override val view: View = ComposeView(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        // ★insets 自己灌（没人会帮浮层灌）：宿主 View 一收到系统 insets 就写进浮层自己的 WindowStore，
        //   页面里 `windowStore.getContentInsets(localContainerView())` 拿到的才是**本窗口**的值。
        //   不灌的后果：空间页的标题/内容顶到状态栏下面（用户看得见的那种"贴边"）。
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            windowStore.setWindowInsets(bars.left, bars.top, bars.right, bars.bottom)
            windowStore.setContentInsets(bars.left, bars.top, bars.right, bars.bottom)
            // 浮层里贴底弹窗用的是同一个容器（tag = bottomSheet），同一套安全区
            windowStore.setBottomSheetContentInsets(bars.left, bars.top, bars.right, bars.bottom)
            windowInsets
        }
        setContent { OverlayContent() }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 路由闸门：空间流程内留在浮层，其它交回主界面
    // ══════════════════════════════════════════════════════════════════════

    /**
     * @return true = 这次导航已被浮层接管（已经交给主界面 + 要求直播页让位），
     *   `PageNavigation` 不要再导航；false = 空间流程内，照常在浮层里导航。
     */
    private fun gateRoute(route: ComposePage): Boolean {
        if (route::class in SPACE_FLOW_ROUTES) return false
        handOffToMainHost(route)
        return true
    }

    /**
     * 把一次"空间外"的导航交回主界面：**先**让主界面导航（它此刻还在直播页后面，组合是活的），
     * **再**下一帧要求直播页让位。
     *
     * 为什么 `post` 一帧：本方法是从浮层自己的点击事件里回来的，当场 `dispose()` 等于在 Compose
     * 派发事件的过程中拆自己的组合树；等这一帧过去再交（app 侧 `handOffToMainHost` 还会再 post 一帧
     * 才真正摘 view，两道缓冲）。顺序也不能反：先 exit 的话直播页 finish、主界面浮上来，
     * 那时再导航视觉上就是"先闪一下旧页再跳"。
     */
    private fun handOffToMainHost(route: ComposePage) {
        if (disposed) return
        runCatching { mainNavigation.navigate(route) }
        view.post {
            if (!disposed) runCatching { onExitToMainHost() }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SpaceOverlayHandle
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 直播页转发的系统返回：**先退浮层自己的一层**；退不动（已经在空间首页）返回 false，
     * 由直播页关掉浮层回到直播间本体（`LivePlayerActivity.spaceOverlayBack`）。
     *
     * 组合还没建好（刚 open 的头一两帧）也返回 false —— 那一下应当就是"关掉浮层"，不是吞掉。
     */
    override fun onBack(): Boolean {
        if (disposed) return false
        val nav = navController ?: return false
        return runCatching { nav.popBackStack() }.getOrDefault(false)
    }

    /**
     * 关浮层（直播页先摘 view、再调这里）。**幂等**：`disposed` 一置就再进来也只做一次；
     * 两条清理各自 `runCatching`（清理失败最坏是晚一帧释放，绝不许连累调用方）。
     */
    override fun dispose() {
        if (disposed) return
        disposed = true
        // ① 拆组合树（ComposeView 的公开 API；拆完页面里的 effect / VM 都停）
        runCatching { (view as? ComposeView)?.disposeComposition() }
        // ② 清浮层自己的 ViewModelStore（页面 VM / NavController 的 VM 一次清空）
        runCatching { viewModelStore.clear() }
    }

    /**
     * "退回上一级"的统一出口（标题栏那颗返回箭头 + `PageNavigation.popBackStack()` 退不动时的
     * `onClose`）：**转发给宿主的返回链路**，与系统返回键逐字同一条路 ——
     * 这样浮层自己不需要（也拿不到）"关掉我"的 API：直播页的 `spaceOverlayBack()` 会先问
     * [onBack]，问出 false 就关浮层、且**不退出直播间**。
     */
    private fun closeBySystemBack() {
        val owner = activity as? ComponentActivity ?: return
        runCatching { owner.onBackPressedDispatcher.onBackPressed() }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 组合内容
    // ══════════════════════════════════════════════════════════════════════

    @Composable
    private fun OverlayContent() {
        withDI(di = di) {
            // AppStore 用父容器那份（主题/设置是全局的，不该由浮层重开一份）
            val appStore by rememberInstance<AppStore>()
            val appState = appStore.stateFlow.collectAsStateWithLifecycle().value
            BilimiaoTheme(
                appState = appState,
                systemDark = isSystemInDarkTheme(),
            ) {
                val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
                val insets = windowState.getContentInsets(bottomSheetContainer)
                // UriHandler 也必须走浮层这份导航：用主界面那份会"在看不见的主界面里"导航
                val uriHandler = remember(overlayNavigation) {
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            if (!BilibiliNavigation.navigationTo(overlayNavigation, uri)) {
                                overlayNavigation.launchWebBrowser(uri)
                            }
                        }
                    }
                }
                CompositionLocalProvider(
                    // 浮层自己的容器：页面算 insets / 贴底弹窗都认它
                    LocalContainerView provides bottomSheetContainer,
                    LocalPageConfigState provides pageConfigState,
                    LocalPageNavigation provides overlayNavigation,
                    LocalEmitter provides emitter,
                    LocalUriHandler provides uriHandler,
                    // 浮层自己的 VM 作用域（关浮层时一次 clear，见 viewModelStore）
                    LocalViewModelStoreOwner provides viewModelStoreOwner,
                    // ★**不**提供 LocalOnBackPressedDispatcherOwner：保持宿主的（直播页），
                    //   页面自己的 BackHandler 与直播页的转发回调因此在同一个 dispatcher 上分层。
                ) {
                    ImagePreviewerProvider(
                        contentPadding = insets.addPaddingValues(),
                        previewer = { state, innerPadding -> MyImagePreviewer(state, innerPadding) },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                // 浮层是"页内的一层"（不是压在视频画面上的蒙层）→ 底色跟主题
                                .background(MaterialTheme.colorScheme.background),
                        ) {
                            OverlayTitleBar(
                                pageConfigState = pageConfigState,
                                topInsetDp = insets.topDp.dp,
                                onBack = { closeBySystemBack() },
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                val nav = rememberNavController()
                                // ★顺序坑（蓝图 §2.4）：控制器必须在 NavHost 组合之前交给 PageNavigation，
                                //   否则有页面在组合期读 `getCurrentVideoId()` → 拿到 null → error(...)。
                                navController = nav
                                SpaceFlowNavHost(
                                    navController = nav,
                                    startRoute = UserSpacePage(id = mid.toString()),
                                )
                            }
                        }
                        // 页面弹窗（alert/open/loading）的宿主：少了它页面调 messageDialogState 会"没人画"
                        MessageDialog(messageDialogState)
                        val bottomSheetPage = bottomSheetState.page.collectAsStateWithLifecycle().value
                        if (bottomSheetPage != null) {
                            MyBottomSheet(
                                container = bottomSheetContainer,
                                page = bottomSheetPage,
                                onClose = { bottomSheetState.close() },
                                // ★闸门也要传进贴底弹窗：弹窗里的页面走的是它自己那份 PageNavigation，
                                //   不传的话"空间外"的目的地会撞进浮层的小路由表（destination not found → 抛）。
                                routeGate = { route -> gateRoute(route) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 浮层自己的**小路由表**：只注册 UP 空间流程内的目的地。
 *
 * 为什么不复用主界面那张整表（`MyNavHost`）：
 * · 浮层只需要空间这一支；注册整表 = 把"视频详情 / 点播播放器"也搬进浮层，与"不要在浮层里播视频"冲突；
 * · 表外的目的地由 `PageNavigation.routeGate` 交回主界面（见 [UserSpaceOverlayHost.gateRoute]），
 *   所以**不存在** "destination not found" 这条崩路。
 *
 * ★与 [SPACE_FLOW_ROUTES] 是**同一份清单的两半**（注册 + 判定），增删目的地必须同时改两处。
 */
@Composable
private fun SpaceFlowNavHost(
    navController: NavHostController,
    startRoute: Any,
) {
    NavHost(
        navController = navController,
        startDestination = startRoute,
    ) {
        composable<UserSpacePage>()
        composable<UserSpaceSearchPage>()
        composable<UserFollowPage>()
        composable<SearchFollowPage>()
        composable<UserBangumiPage>()
        composable<UserLikeArchivePage>()
        composable<UserFavouritePage>()
        composable<UserFavouriteDetailPage>()
        composable<UserSeasonDetailPage>()
        composable<UserMedialistPage>()
        composable<EditProfilePage>()
        composable<MyFollowerPage>()
        // 自己的空间里那两条"我的…"（UserSpaceViewModel 在 vmid == 自己时走 pages.mine）
        composable<MyFollowPage>()
        composable<MyBangumiPage>()
    }
}

/**
 * "属于 UP 空间流程"的目的地白名单（与 [SpaceFlowNavHost] 的注册表一一对应）。
 *
 * 用 `KClass` 而不是路由字符串判定：type-safe 路由的 `destination.route` 是
 * "包名.类名/{参数}" 这种模式串，字符串比对面窄易错；页面对象在这里是**真实例**，
 * `route::class` 是唯一的、编译期就有的判据。
 */
private val SPACE_FLOW_ROUTES: Set<KClass<out ComposePage>> = setOf(
    UserSpacePage::class,
    UserSpaceSearchPage::class,
    UserFollowPage::class,
    SearchFollowPage::class,
    UserBangumiPage::class,
    UserLikeArchivePage::class,
    UserFavouritePage::class,
    UserFavouriteDetailPage::class,
    UserSeasonDetailPage::class,
    UserMedialistPage::class,
    EditProfilePage::class,
    MyFollowerPage::class,
    MyFollowPage::class,
    MyBangumiPage::class,
)

/**
 * 浮层顶栏：`← 返回` + 当前页标题（来自 [PageConfigState]，与主界面同一套数据）。
 *
 * 为什么要有它：主界面的 AppBar 是 app 侧（`MainActivity` 的 MyPage 配置）画的，浮层里拿不到；
 * 没有它用户在空间里就只剩系统返回手势这一条出路（虽然后者也能回直播间）。
 * 用 [MiaoTitleBar]（AGENTS 规则 14：子页标题栏就走它），不新造第二份标题栏。
 */
@Composable
private fun OverlayTitleBar(
    pageConfigState: PageConfigState,
    topInsetDp: Dp,
    onBack: () -> Unit,
) {
    val config = pageConfigState.collectConfigAsState()
    MiaoTitleBar(
        // 安全区走浮层自己实测的 insets（规则 14：禁止固定高度硬顶）
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topInsetDp),
        icon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回直播间",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        },
        title = {
            Text(
                text = config.value.title.ifBlank { "用户空间" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}
