package cn.a10miaomiao.bilimiao.compose

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import androidx.window.core.layout.WindowWidthSizeClass
import cn.a10miaomiao.bilimiao.compose.base.BottomSheetState
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.LocalContainerView
import cn.a10miaomiao.bilimiao.compose.common.LocalEmitter
import cn.a10miaomiao.bilimiao.compose.common.LocalPageNavigation
import cn.a10miaomiao.bilimiao.compose.common.addPaddingValues
import cn.a10miaomiao.bilimiao.compose.common.emitter.SharedFlowEmitter
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.LocalPageConfigState
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfigState
import cn.a10miaomiao.bilimiao.compose.common.navigation.BilibiliNavigation
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.components.antifraud.AntifraudAppealDialogHost
import cn.a10miaomiao.bilimiao.compose.components.dialogs.AutoSheetDialog
import cn.a10miaomiao.bilimiao.compose.components.dialogs.DirectionState
import cn.a10miaomiao.bilimiao.compose.components.dialogs.MessageDialog
import cn.a10miaomiao.bilimiao.compose.components.dialogs.MessageDialogState
import cn.a10miaomiao.bilimiao.compose.components.image.MyImagePreviewer
import cn.a10miaomiao.bilimiao.compose.components.image.provider.ImagePreviewerProvider
import cn.a10miaomiao.bilimiao.compose.pages.home.HomePage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpaceOverlayHost
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.live.LiveSpaceLauncher
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MyPage
import com.a10miaomiao.bilimiao.comm.mypage.myPageConfig
import com.a10miaomiao.bilimiao.comm.store.AppStore
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.subDI
import org.kodein.di.android.x.closestDI
import org.kodein.di.bindSingleton
import org.kodein.di.compose.rememberInstance
import org.kodein.di.compose.withDI
import org.kodein.di.instance

class ComposeFragment : Fragment(), MyPage, DIAware, OnBackPressedDispatcherOwner {

    override val di: DI = subDI(closestDI()) {
        bindSingleton { this@ComposeFragment }
        bindSingleton { this@ComposeFragment.requireArguments() }
        bindSingleton { messageDialogState }
        bindSingleton { emitter }
        bindSingleton { pageNavigation }
        bindSingleton { bottomSheetState }
    }

    private val pageNavigation = PageNavigation(
        navHostController = { composeNav },
        launchUrl = ::launchWebBrowser,
    )
    private val pageConfigState = PageConfigState()
    private val emitter = SharedFlowEmitter()
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            if (!BilibiliNavigation.navigationTo(pageNavigation, uri)) {
                BilibiliNavigation.navigationToWeb(pageNavigation, uri)
            }
        }
    }

    private val startViewWrapper by instance<StartViewWrapper>()
    private val appStore by instance<AppStore>()
    private val windowStore by instance<WindowStore>()

    private var _pageConfig = PageConfigState.Cofing(-1)
    override val pageConfig = myPageConfig {
        val config = _pageConfig
        title = config.title
        menu = config.menu
        search = config.search
    }

    override fun onMenuItemClick(view: View, menuItem: MenuItemPropInfo) {
        super.onMenuItemClick(view, menuItem)
        pageConfigState.onMenuItemClick(view, menuItem)
    }

    override fun onSearchSelfPage(context: Context, keyword: String) {
        super.onSearchSelfPage(context, keyword)
        pageConfigState.onSearchSelfPage(context, keyword)
    }

    private val messageDialogState = MessageDialogState()
    private val bottomSheetState = BottomSheetState()

    lateinit var composeNav: NavHostController

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val bottomSheetView = FrameLayout(context) // 临时打补丁给windowStore使用，后期会移除windowStore
        bottomSheetView.tag = "bottomSheet"
        return ComposeView(context).apply {
            setContent {
                // 读取系统配置以在暗色模式切换时触发Compose重组
                val config = LocalConfiguration.current
                val connection = rememberNestedScrollInteropConnection(container ?: LocalView.current)
                composeNav = rememberNavController()
                // ★直播播放页「UP主」按钮 → 用户空间的**注册桥**（全工程唯一注册点，见 LiveSpaceLauncher）。
                //   为什么注册在这里：直播页在 app 模块、用户空间是 compose 模块的 Compose 页面，
                //   app 侧**拿不到本 Fragment 的 `pageNavigation`**（它绑在这个 Fragment 的 subDI 上，
                //   不是全局 DI，app 解析不到），两边唯一的共同可见点就是 comm 模块的 LiveSpaceLauncher。
                //   ★别再写"app 反向 import compose 会成环"：app 本来就
                //     `implementation(project(":bilimiao-compose"))`（app/build.gradle.kts:152），
                //     缺的是**导航句柄**、不是依赖方向。2026-09-28 已纠正的同款错论据是**两处**：
                //     本处，以及 `LivePlayerActivity` 那座桥的 KDoc。
                //     ★另外三处写着"成环"的别一起改 —— 它们说的是**反方向**（compose 不能 import app 的类，
                //     反向引用确实会成环编译不过），说法成立：`LiveBadgedAvatar`、`HomeLiveContent`、
                //     `SearchLiveContent` 里那几处同类说明。
                //   为什么用 DisposableEffect：页面在时挂上实现、Fragment 销毁时注销 ——
                //   注销后直播页的 open() 会返回 false，由它自己 toast 兜底，不会静默失败。
                DisposableEffect(pageNavigation) {
                    LiveSpaceLauncher.register { mid ->
                        pageNavigation.navigate(UserSpacePage(id = mid.toString()))
                    }
                    // ★task-26：**页内浮层**工厂（与上面那条老桥并列，**不删老桥**——它是兜底）。
                    //   为什么在这里注册：浮层要拿"本 Fragment 这份 DI + 这份 pageNavigation"，
                    //   而它们都只活在这个根组合里（app 侧拿不到）。工厂被直播页调用时：
                    //   · parentDi      = 本 Fragment 的 di（往上还挂着 MainActivity 的 DI：Store/播放器委托）；
                    //   · mainNavigation = 本 Fragment 的 pageNavigation（浮层里点到"空间流程外"的目的地时，
                    //     由浮层把这次导航交给它，见 UserSpaceOverlayHost.gateRoute）。
                    //   工厂自己 runCatching → null（构造失败就返回 null，直播页自动走老路，绝不闪退）。
                    LiveSpaceLauncher.registerOverlay { activity, mid, onExitToMainHost ->
                        UserSpaceOverlayHost.create(
                            activity = activity,
                            mid = mid,
                            parentDi = di,
                            mainNavigation = pageNavigation,
                            onExitToMainHost = onExitToMainHost,
                        )
                    }
                    onDispose {
                        LiveSpaceLauncher.unregister()
                        LiveSpaceLauncher.unregisterOverlay()
                    }
                }
                CompositionLocalProvider(
                    LocalContainerView provides container,
                    LocalPageConfigState provides pageConfigState,
                    LocalOnBackPressedDispatcherOwner provides requireActivity() as OnBackPressedDispatcherOwner,
                    LocalPageNavigation provides pageNavigation,
                    LocalEmitter provides emitter,
                    LocalUriHandler provides uriHandler
                ) {
                    withDI(di = di) {
                        val currentDark = isSystemInDarkTheme()
                        val appState = appStore.stateFlow.collectAsStateWithLifecycle().value
                        val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
                        val windowInsets = windowState.getContentInsets(localContainerView())
                        BilimiaoTheme(
                            appState = appState,
                            systemDark = currentDark,
                        ) {
                            ImagePreviewerProvider(
                                contentPadding = windowInsets.addPaddingValues(
                                    addBottom = windowStore.bottomAppBarHeightDp.dp
                                ),
                                previewer = { state, innerPadding ->
                                    MyImagePreviewer(state, innerPadding)
                                }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .nestedScroll(connection)
                                        .background(MaterialTheme.colorScheme.background),
                                ) {
                                    val startRoute = HomePage
                                    MyNavHost(composeNav, startRoute)
                                }
                                val bottomSheetPage = bottomSheetState.page.collectAsStateWithLifecycle().value
                                if (bottomSheetPage != null) {
                                    MyBottomSheet(
                                        bottomSheetView,
                                        bottomSheetPage,
                                        onClose = {
                                            bottomSheetState.close()
                                        }
                                    )
                                }
                            }
                            MessageDialog(messageDialogState)
                            // 评论反诈的申诉弹窗（Compose 版：主题色跟随 + 竖屏贴底/横屏居中）
                            // 以前挂在 DialogX 上（配色不跟主题、横屏按钮被裁），见 AntifraudAppealDialogHost
                            AntifraudAppealDialogHost()
                        }
                        MyStartView(startViewWrapper = startViewWrapper)
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureNavHostBackCallback()
        lifecycleScope.launch {
            pageConfigState.collectConfig {
                _pageConfig = it
                pageConfig.notifyConfigChanged()
            }
        }
    }

    fun onBackPressed(): Boolean {
        // ensureNavHostBackCallback() 已在 onViewCreated 中调用
        onBackPressedDispatcher.onBackPressed()
        miaoLogger() debug "onBackPressed"
        return true
    }

    // 委托给 Activity 的 dispatcher——activity 1.13+ 改用 NavigationEventDispatcher 机制，
    // Fragment 自己 new 的 dispatcher 不在 Activity 回退链路里。
    override val onBackPressedDispatcher: OnBackPressedDispatcher
        get() = requireActivity().onBackPressedDispatcher

    // NavHost 兜底：在 Activity dispatcher 上加一个常驻低优先级 callback
    private var navHostBackCallback: OnBackPressedCallback? = null

    private fun ensureNavHostBackCallback() {
        if (navHostBackCallback != null) return
        val callback = object : OnBackPressedCallback(true) { // 始终启用——低优先级兜底
            override fun handleOnBackPressed() {
                try {
                    composeNav.popBackStack()
                } catch (_: Exception) {}
            }
        }
        navHostBackCallback = callback
        requireActivity().onBackPressedDispatcher.addCallback(this, callback)
    }

    fun launchWebBrowser(uri: Uri) {
        // 使用外部浏览器打开
        val activity = requireActivity()
        val typedValue = TypedValue()
        val attrId = com.google.android.material.R.attr.colorSurfaceVariant
        activity.theme.resolveAttribute(attrId, typedValue, true)
        val intent = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(ContextCompat.getColor(activity, typedValue.resourceId))
                    .build()
            )
            .build()
        intent.launchUrl(activity, uri)
    }

    fun navigateByUri(deepLink: Uri): Boolean {
        return pageNavigation.navigateByUri(deepLink)
    }

    fun navigate(page: ComposePage) {
        pageNavigation.navigate(page)
    }

    fun goBackHome() {
        composeNav.popBackStack(composeNav.graph.findStartDestination().id, false)
    }

    fun openBottomSheet(page: ComposePage) {
        bottomSheetState.open(page)
    }

}

@Composable
fun MyNavHost(
    navController: NavHostController,
    startRoute: Any,
) {
    NavHost(
        navController = navController,
        startDestination = startRoute,
    ) {
        BilimiaoPageRoute(this)
            .initRoute()
    }
}

@SuppressLint("RestrictedApi")
@Composable
fun MyBottomSheet(
    container: ViewGroup?,
    page: ComposePage,
    onClose: () -> Unit,
    /**
     * ★task-26：页内浮层专用（默认 `null` = 主界面那份**逐字不变**）。
     *
     * 为什么必须能传进来：贴底弹窗里的页面走的是**这个函数自己那份** `PageNavigation`，
     * 它绕过了浮层对页面发的那份（`LocalPageNavigation`）—— 不把闸门一起传下去，
     * 弹窗里点到"空间流程外"的目的地就会**在浮层里**打开（视频会在看不见的地方播）。
     * 语义与 [PageNavigation.routeGate] 完全一致。
     */
    routeGate: ((ComposePage) -> Boolean)? = null,
) {
    val parentPageNavigation by rememberInstance<PageNavigation>()
    val pageNavigation = remember(parentPageNavigation, onClose, routeGate) {
        PageNavigation(
            navHostController = { parentPageNavigation.hostController },
            launchUrl = parentPageNavigation::launchWebBrowser,
            onClose = onClose,
            routeGate = routeGate,
        )
    }
    val pageConfigState = remember {
        PageConfigState()
    }
    org.kodein.di.compose.subDI(
        diBuilder = {
            bindSingleton(
                overrides = true
            ) { pageNavigation }
        }
    ) {
        CompositionLocalProvider(
            LocalContainerView provides container,
            LocalPageConfigState provides pageConfigState,
            LocalPageNavigation provides pageNavigation,
        ) {
            AutoSheetDialog(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .heightIn(max = 500.dp),
                content = {
                    page.Content()
                    MyBottomSheetTitleBar(pageConfigState, onClose)
                },
                onDismiss = onClose,
            )
        }
    }
}

@Composable
fun MyBottomSheetTitleBar(
    state: PageConfigState,
    onClose: () -> Unit,
) {
    val config = state.collectConfigAsState()
    Box(
        modifier = Modifier
            .height(48.dp)
            .padding(horizontal = 10.dp)
            .fillMaxWidth(),
    ) {
        IconButton(
            onClick = onClose,
            colors = IconButtonDefaults.iconButtonColors()
                .copy(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                        .copy(alpha = 0.75f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
            modifier = Modifier
                .size(30.dp)
                .align(Alignment.CenterStart)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "close"
            )
        }

        AnimatedContent(
            modifier = Modifier
                .align(Alignment.Center),
            targetState = config.value.title,
            contentKey = { it },
            label = "BottomSheetTitle",
        ) { title ->
            Text(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer
                            .copy(alpha = 0.75f)
                    )
                    .padding(vertical = 2.dp, horizontal = 10.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                text = title.replace("\n", " "),
            )
        }

    }
}

@Composable
fun MyStartView(
    startViewWrapper: StartViewWrapper,
) {
    val appStore by rememberInstance<AppStore>()

    fun openSearch() {
        startViewWrapper.openSearchDialog("", 0, true)
    }

    if (startViewWrapper.shouldCreateCompositionOnAttachedToWindow) {
        val composition = rememberCompositionContext()
        startViewWrapper.setContent(composition) {
            val appState = appStore.stateFlow.collectAsStateWithLifecycle().value
            BilimiaoTheme(appState, isSystemInDarkTheme()) {
                StartViewContent(
                    startTopHeight = startViewWrapper.touchStart.dp,
                    navigateTo = startViewWrapper.navigateTo,
                    navigateUrl = startViewWrapper.navigateUrl,
                    openSearch = ::openSearch,
                    isSearchVisible = startViewWrapper.showSearchDialog,
                    searchInitKeyword = startViewWrapper.searchInitKeyword,
                    searchInitMode = startViewWrapper.searchInitMode,
                    pageSearchMethod = startViewWrapper.pageSearchMethod,
                    onCloseSearch = startViewWrapper::closeSearchDialog,
                )
            }
        }
    }
}
