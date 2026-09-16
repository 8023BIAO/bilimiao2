package cn.a10miaomiao.bilimiao.compose.pages.web

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.BiliJsBridge
import cn.a10miaomiao.bilimiao.compose.common.navigation.BilibiliNavigation
import cn.a10miaomiao.bilimiao.compose.common.addPaddingValues
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.serialization.Serializable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
class WebPage(
    val url: String,
) : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: WebPageViewModel = diViewModel {
            WebPageViewModel(it, url)
        }
        WebPageContent(viewModel)
    }
}

private class WebPageViewModel(
    override val di: DI,
    private val startUrl: String,
) : ViewModel(), DIAware {

    private val userAgent = """
            |os/android 
            |model/${Build.MODEL} 
            |build/${ApiHelper.BUILD_VERSION} 
            |osVer/${Build.VERSION.RELEASE} 
            |sdkInt/${Build.VERSION.SDK_INT}  
            |network/2 
            |BiliApp/${ApiHelper.BUILD_VERSION} 
            |mobi_app/android_hd 
            |channel/bili 
            |c_locale/zh_CN 
            |s_locale/zh_CN 
            |disable_rcmd/0
        """.trimMargin().replace("\n", "")

    private val fragment by instance<Fragment>()
    private val pageNavigation by instance<PageNavigation>()

    var webView: WebView? = null

    val loading = mutableStateOf(false)
    val pageTitle = mutableStateOf("")
    val hideNavbar = mutableStateOf(false)

    /** 是否已经把导航交棒给 App 内页面（此后本页只是"跳转中间页"） */
    val inAppNavigated = mutableStateOf(false)

    /**
     * 已经交棒过的 WebPage 被重新显示 = 用户从目标页返回到了这个中间页。
     * 直接跳过自己（返回上一层），并保证不再重建 WebView：
     * 否则 startUrl 会重新加载 → 页面重定向 → 又被拽回目标页，
     * 表现就是"返回之后一直回到它导航的那个地方"。
     */
    fun skipSelfIfHandedOff(): Boolean {
        if (!inAppNavigated.value) return false
        val route = pageNavigation.hostController.currentBackStackEntry?.destination?.route ?: return false
        if (!route.startsWith("WebPage/")) return false
        pageNavigation.popBackStack()
        return true
    }

    init {
//        initWebView(webView)
    }

    fun initWebView(view: WebView) {
        // 深链入口（bilimiao://web?url= / bilibili://forward?-Btarget=）校验：
        // 非白名单域名不允许进入带 JS 桥的内嵌浏览器
        val startHost = Uri.parse(startUrl).host ?: ""
        if (!BilibiliNavigation.isAllowedWebHost(startHost)) {
            toast("不支持的链接：$startUrl")
            view.post { pageNavigation.popBackStack() }
            return
        }
        val biliJsBridge = BiliJsBridge(fragment, pageNavigation, view)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = mWebViewClient
        view.webChromeClient = mWebChromeClient
        view.settings.apply {
            javaScriptEnabled = true
            var defaultUserAgentString = userAgentString
            if ("Mobile" !in defaultUserAgentString) {
                defaultUserAgentString += " Mobile"
            }
            userAgentString = "$defaultUserAgentString $userAgent"
            allowContentAccess = true
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            databaseEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadsImagesAutomatically = true
        }
        view.addJavascriptInterface(biliJsBridge, "_BiliJsBridge")
        // 已交棒给 App 内页面的中间页不再加载网页（避免重定向把人又拽走）
        if (!inAppNavigated.value) {
            view.loadUrl(startUrl)
        }
    }

    private val mWebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // 只接管主框架导航：iframe/广告等子框架不该决定整页跳转
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            val isAppDeepLink = url.startsWith("bilibili://") || url.startsWith("bilimiao://")
            val re = BilibiliNavigation.navigationTo(pageNavigation, url)
            if (re) {
                // ★ 交棒给 App 内页面：本页从此只是"跳转中间页"
                inAppNavigated.value = true
                // 记录刚推入的栈顶，用来识别"用户已经手动返回/又跳走了"
                val pushedEntryId = pageNavigation.hostController.currentBackStackEntry?.id
                // ★ 无感返回：跳转 app 内页面成功后，把 WebPage 自身移出返回栈，
                // 避免返回时出现空白中间层（b23.tv 短链 → 视频详情页场景）
                // 顺序：先弹掉 WebPage(连同其上的目标页)，再重新导航目标页
                view.post {
                    val nav = pageNavigation.hostController
                    // 用户已经不在刚跳过去的那一页（按了返回）→ 什么都不做，
                    // 否则会把人又拽回目标页（"返回不够快就被拉回去"的根因）
                    if (pushedEntryId == null || nav.currentBackStackEntry?.id != pushedEntryId) {
                        return@post
                    }
                    // 竞态防护：用户可能已先手动返回，此时 popBackStack 失败会走 onClose 误关整个 Activity。
                    // 仅当 WebPage 仍存在于返回栈时才执行"无感返回"弹栈
                    val entries = listOfNotNull(
                        nav.currentBackStackEntry,
                        nav.previousBackStackEntry,
                    )
                    val stillInStack = entries.any {
                        (it.destination.route ?: "").startsWith("WebPage/")
                    }
                    if (stillInStack) {
                        if (pageNavigation.popBackStack(WebPage(startUrl), inclusive = true)) {
                            BilibiliNavigation.navigationTo(pageNavigation, url)
                        }
                    }
                }
                return true
            }
            if (isAppDeepLink) {
                // 页面里无法路由的 App 唤起链接：静默忽略（停在当前网页）
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            loading.value = true
            hideNavbar.value = url.indexOf("navhide=1") != -1
            view.evaluateJavascript("""
                (function(){
                    window.BiliJsBridge = {
                        sendTasks: [],
                        callbacks: [],
                        selfCallbackId: 0,
                        newVersion: true,
                        inited: true,
                    };
                    window.BiliJsBridge.biliInject = {
                        postMessage: function(e) {
                            window._BiliJsBridge.postMessage(e);
                        },
                        biliCallbackReceived: function(t, e, n) {
                            var r = window.BiliJsBridge.callbacks.map((function(t) {
                                return t.callbackId
                            })).indexOf(Number(t));
                            r >= 0 && window.BiliJsBridge.callbacks[r].callback && window.BiliJsBridge.callbacks[r].callback(n || e)
                        }
                    }
                })()
            """.trimIndent()) {
//                DebugMiao.log("callback", it)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            loading.value = false
//            val js = """javascript:(function() {
//                        var parent = document.getElementsByTagName('head').item(0);
//                        var style = document.createElement('style');
//                        style.type = 'text/css';
//                        style.innerHTML = '#dynamic-openapp, #dynamic-openapp-mask,.mini-header-container,.fixed-header-container,.v-navbar__body,#internationalHeader,.international-footer,.bili-footer,#cannot-check{display: none !important;} #app{padding-bottom: 0;}';
//                        parent.appendChild(style);
//                        window.java_obj.showDescription(
//                               'theme-color',
//                               document.querySelector('meta[name="theme-color"]').getAttribute('content')
//                        );
//                        window.java_obj.test.hello('from js');
//                    })()
//                """
//            view.loadUrl(js)
        }
    }

    private val mWebChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String) {
            super.onReceivedTitle(view, title)
            pageTitle.value = title
        }
    }

}

@Composable
private fun WebPageContent(
    viewModel: WebPageViewModel
) {
    // 网页内部的历史要自己接管返回：否则按一次返回就把整个网页关掉，
    // 网页里点进去的几层全丢（系统返回和 App 底栏返回都走 OnBackPressedDispatcher，这里都能拦到）
    BackHandler(enabled = viewModel.webView?.canGoBack() == true) {
        viewModel.webView?.goBack()
    }

    PageConfig(
        title = viewModel.pageTitle.value,
    )
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    // 从 App 内目标页返回时：本页只是"跳转中间页" → 直接跳过自己回上一层，
    // 并且不再重建 WebView（重建 → 重新加载 startUrl → 重定向 → 又被拽回目标页）
    LaunchedEffect(Unit) {
        viewModel.skipSelfIfHandedOff()
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        if (!viewModel.inAppNavigated.value) {
            AndroidView(
                modifier = Modifier.fillMaxSize()
                    .padding(
                        windowInsets.addPaddingValues(
                            addTop = if (viewModel.hideNavbar.value) -windowInsets.topDp.dp else 0.dp,
                            addBottom = windowStore.bottomAppBarHeightDp.dp
                        )
                    ),
                factory = {
                    FrameLayout(it).apply {
                        val webView = viewModel.webView ?: WebView(it).also {
                            viewModel.initWebView(it)
                            viewModel.webView = it
                        }
                        addView(webView)
                    }
                },
                onRelease = {
                    // 释放 WebView 原生资源，避免页面频繁进出时内存持续累积
                    viewModel.webView?.destroy()
                    viewModel.webView = null
                    it.removeAllViews()
                }
            )
        }
        if (viewModel.loading.value) {
            CircularProgressIndicator(
                modifier = Modifier.size(60.dp)
                    .align(Alignment.Center),
            )
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}