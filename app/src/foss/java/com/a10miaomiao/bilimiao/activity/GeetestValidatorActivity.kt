package com.a10miaomiao.bilimiao.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.a10miaomiao.bilimiao.R
import com.a10miaomiao.bilimiao.comm.delegate.theme.ThemeDelegate
import com.a10miaomiao.bilimiao.comm.utils.BiliGeetestUtil
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.config.config
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.kodein.di.DI
import splitties.dimensions.dip
import splitties.experimental.InternalSplittiesApi
import splitties.views.backgroundColor
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.setContentView
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.core.wrapInScrollView

class GeetestValidatorActivity : AppCompatActivity() {

    companion object {

        private var tempCallback: CallBack? = null

        /** 加载超时：**从真正发起 [GeetestValidatorUi.loadValidator] 那一刻**起算，这么久还没等到
         *  [WebViewClient.onPageFinished] 就判失败（asset 加载失败也可能不回调错误）。
         *  ★只量"页面加载"这一段：取参数（getGTApiJson）花多久都不吃这个预算。 */
        private const val LOAD_TIMEOUT_MS = 10_000L

        fun openGeetestValidatorActivity(activity: Activity, callback: CallBack) {
            tempCallback = callback
            val intent = Intent(activity, GeetestValidatorActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val di: DI = DI.lazy {}

    private var mCallback: CallBack? = null

    /** 当前页面 UI（兜底面板 / WebView 都在里面）；onDestroy 置空 */
    private var ui: GeetestValidatorUi? = null

    /** 超时计时器（每次「重试」先取消上一个，避免旧计时器把新一次加载误判成超时） */
    private var timeoutJob: Job? = null

    /**
     * 「第几次尝试」代际号：每次 [startLoad] 自增，用来丢弃上一次尝试的迟到回调。
     *
     * 写法与弹幕客户端的 `generation` 同一套（`LiveDanmakuClient`：连上时
     * `generation.incrementAndGet()`，在途回调里 `gen != generation.get()` 就丢）。
     * ★WebView 回调只给 URL、不给"第几次"，所以代际号写进文档 URL 的 `_n=`
     *   （见 [GeetestValidatorUi.loadValidator]）：回调比对 URL 就等于比对代际号。
     */
    private var loadAttempt = 0

    private val themeDelegate by lazy {
        ThemeDelegate(this@GeetestValidatorActivity, di)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        mCallback = tempCallback
        themeDelegate.onCreate(savedInstanceState)
        super.onCreate(savedInstanceState)
        val jsBridge = JsBridge(this)
        val ui = GeetestValidatorUi(this, jsBridge)
        this.ui = ui
        setContentView(ui)
        startLoad()
    }

    override fun onDestroy() {
        timeoutJob?.cancel()
        timeoutJob = null
        // ★WebView 必须显式收摊：它是少数几个"不 destroy 就会把 Activity/JS 桥一起留着"的 View
        //   （本页又在登录硬门槛上，反复进出会累积）。先摘掉 JS 桥再 destroy。
        ui?.release()
        ui = null
        super.onDestroy()
        // 真正关闭时清理静态回调引用，避免 Activity 泄漏；
        // 旋转重建(isFinishing=false)时保留 tempCallback 供 onCreate 恢复
        if (isFinishing) {
            tempCallback = null
        }
    }

    /** 页内「重试」按钮的入口：重新取参数 + 重新加载（参数可能已经过期，必须整条重来） */
    fun retry() {
        ui?.showPage()
        startLoad()
    }

    /**
     * 取参数 → 加载验证码页 → 武装超时。
     *
     * ★任何一步拿不到可用参数都不再"什么都不做"（那是纯白页，用户连重试的地方都没有）：
     *   统一落到 [GeetestValidatorUi.showFallback]，页内给提示 + 「重试」按钮。
     * ★超时计时器**不在这里武装**：参数多久回来都不该吃掉页面加载的预算，
     *   只有真正要 [GeetestValidatorUi.loadValidator] 时才起表（见 [armLoadTimeout] 的调用点）。
     * ★已知边界：**"取参数"这段没有自己的计时器**（它靠调用方网络层的超时兜底）—— 万一
     *   `getGTApiJson()` 永久挂起，本页既不报超时也不白屏（停在上一状态）。改动前同样如此
     *   （那时计时器虽武装，但 `loadRequested` 还是 false，到点什么都不做），本次没加第二套表。
     */
    private fun startLoad() {
        val ui = ui ?: return
        // ★代际号：这次尝试的编号。每次「重试」都会 +1，比它旧的回调一律丢。
        val attempt = ++loadAttempt
        timeoutJob?.cancel()
        timeoutJob = null
        ui.resetLoadState()
        lifecycleScope.launch {
            val callback = mCallback
            if (callback == null) {
                // 进程被杀后重建：静态 tempCallback 没了 —— 只能提示用户返回重来（不崩、不白屏）。
                // 这种情况重试也没用（没有 callback 可取参数），所以不给「重试」按钮，只留返回。
                miaoLogger().d("geetest callback 为空（进程重建？）")
                ui.showFallback("登录会话已失效，请返回后重新登录", showRetry = false)
                return@launch
            }
            // getGTApiJson 是挂起函数，可能抛（网络/解析）；optString 保证缺键不抛 JSONException
            val json = try {
                callback.getGTApiJson()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce // 取消要原样抛（别吞成"参数获取失败"）
            } catch (t: Throwable) {
                miaoLogger().e("geetest 取验证码参数失败", t)
                null
            }
            // ★陈旧回调：这次尝试已被更新的「重试」取代（连点重试时前一次的参数可能后到），丢掉
            if (attempt != loadAttempt) {
                miaoLogger().d("geetest 丢弃过期尝试的参数结果（attempt=$attempt，当前=$loadAttempt）")
                return@launch
            }
            val gt = json?.optString("gt").orEmpty()
            val challenge = json?.optString("challenge").orEmpty()
            if (gt.isBlank() || challenge.isBlank()) {
                miaoLogger().d("geetest 参数不全（gt/challenge 至少一个为空）")
                ui.showFallback("验证码参数获取失败，请重试")
                return@launch
            }
            // ★计时器从这里起：只量"页面加载"这一段（参数已经拿到了，见本函数 KDoc）
            armLoadTimeout(attempt)
            ui.loadValidator(gt, challenge, attempt)
        }
    }

    /**
     * 武装"页面加载"超时：只有真正 [GeetestValidatorUi.loadValidator] 之后才该有这 10s 预算。
     * 先 cancel 旧的 ⇒ 任何时刻只有一个计时器；回调侧再比对代际号当保险栓。
     */
    private fun armLoadTimeout(attempt: Int) {
        val ui = ui ?: return
        timeoutJob?.cancel()
        timeoutJob = lifecycleScope.launch {
            delay(LOAD_TIMEOUT_MS)
            // 已被更新的尝试取代（正常情况下下面这句在 startLoad/armLoadTimeout 里已被 cancel）
            if (attempt != loadAttempt) return@launch
            if (ui.loadRequested && !ui.pageLoaded && !ui.loadFailed) {
                miaoLogger().d("geetest 加载超时（${LOAD_TIMEOUT_MS}ms 没有 onPageFinished）")
                ui.showFallback("验证码加载超时，请检查网络后重试")
            }
        }
    }

    fun onGTResult(
        gt3Result: BiliGeetestUtil.GT3ResultBean
    ) {
        mCallback?.onGTResult(gt3Result)
        finish()
    }

    private class JsBridge(
        val activity: GeetestValidatorActivity
    ) {
        @JavascriptInterface
        fun postMessage(challenge: String, validate: String, seccode: String) {
            activity.onGTResult(
                BiliGeetestUtil.GT3ResultBean(
                    geetest_challenge = challenge,
                    geetest_validate = validate,
                    geetest_seccode = seccode,
                )
            )
        }
        @JavascriptInterface
        fun close() {
            activity.finish()
        }
    }

    @OptIn(InternalSplittiesApi::class)
    private class GeetestValidatorUi(
        val activity: GeetestValidatorActivity,
        val jsBridge: JsBridge,
    ) : Ui {
        override val ctx = activity

        private companion object {
            /** 从回调 URL 里取代际号 `_n`：`?`/`&` 起头、值必须是数字（见 [isCurrentDocument]） */
            val DOCUMENT_ATTEMPT_PATTERN = Regex("[?&]_n=(\\d+)")
        }

        /** [WebViewClient.onPageFinished] 到过（= 页面真的渲染完了） */
        var pageLoaded = false

        /** 主文档加载失败过（[WebViewClient.onReceivedError]）；失败后即使再收到 onPageFinished 也不翻回页面 */
        var loadFailed = false

        /** 真的发起过一次 [loadValidator]（用来把"参数都没取到"和"页面加载超时"分开） */
        var loadRequested = false

        /**
         * 当前这次尝试的文档地址（`loadValidator` 里写，带 `_n=<代际号>`；[resetLoadState] 清空）。
         *
         * ★为什么把 `_n` 当"文档身份"：WebView 回调只给 URL、不给"第几次尝试"，而**能带上下标的
         *   只有 URL**。于是 `_n=` 就是文档上的代际号：对不上的回调 = 上一次尝试的迟到回调，丢弃。
         *   `resetLoadState()` 会先清空 ⇒ "取参数"那段空窗期里任何旧回调也一律不算数。
         *   ★身份判定**只比 `_n` 的值**（见 [isCurrentDocument]），**不比整串 URL**。
         */
        var documentUrl: String? = null

        /** 本次尝试的代际号（= [documentUrl] 里 `_n` 的值）；null = 当前没有在飞的加载 */
        var documentAttempt: Int? = null

        fun resetLoadState() {
            pageLoaded = false
            loadFailed = false
            loadRequested = false
            documentUrl = null
            documentAttempt = null
        }

        /**
         * 这个回调属于"当前这次尝试"的文档吗。
         *
         * ★只比代际号 `_n` 的值，**不比整串 URL**：WebView 回调给的是它**规范化后**的 URL ——
         *   只要出现任何编码/规范化差异（`gt`/`challenge` 里带需转义的字符、参数顺序变化、
         *   file URL 被改写成等价形态），整串比较就会把**合法回调**判成陈旧 ⇒ [pageLoaded]
         *   永远 false ⇒ 到点弹"加载超时"盖住其实已经加载好的页面（本页在登录硬门槛上，
         *   用户只能反复重试且必然失败）。
         * ★取不到 `_n`（其它页面 / 子帧 / `url == null` / `_n` 不是数字）一律按"不是当前文档"。
         */
        fun isCurrentDocument(url: String?): Boolean {
            val attempt = url
                ?.let { DOCUMENT_ATTEMPT_PATTERN.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: return false
            return attempt == documentAttempt
        }

        val toolBar = view<MaterialToolbar>(View.generateViewId()) {
            clipToPadding = true
            fitsSystemWindows = true
            setTitle(R.string.geetest_validator)
            setNavigationOnClickListener {
                activity.onBackPressed()
            }
        }

        val webView = view<WebView> {
            settings.apply {
                javaScriptEnabled = true
            }
            addJavascriptInterface(jsBridge, "JsBridge")
            // ★没有 WebViewClient 时，asset 加载失败也是纯白页（onReceivedError 根本不会有人接）
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    // ★代际号比对：不是"当前这次尝试"的文档（上一次的超时/重试残留）一律丢，
                    //   否则它会把新一次加载的状态粘死。
                    if (!isCurrentDocument(url)) return
                    pageLoaded = true
                    // 主文档失败后再来的 onPageFinished 是错误页，别把兜底面板翻掉
                    if (!loadFailed) showPage()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    // 只认主文档：子资源（图标/字体）失败不该把整页判死
                    if (!request.isForMainFrame) return
                    // ★代际号比对：上一次尝试的**迟到失败回调**丢掉 —— 不能让它把新一次加载
                    //   已经翻好的页面又粘回错误面板。
                    if (!isCurrentDocument(request.url?.toString())) return
                    loadFailed = true
                    miaoLogger().d("geetest 页面加载失败：${error.errorCode} ${error.description}")
                    showFallback("验证码页面加载失败（${error.description}），请重试")
                }
            }
        }

        /** 兜底提示文案（错误原因由调用方给） */
        val fallbackText = view<TextView>(View.generateViewId()) {
            gravity = Gravity.CENTER
            textSize = 16f
        }

        val retryButton = view<Button>(View.generateViewId()) {
            text = "重试"
            setOnClickListener { activity.retry() }
        }

        /**
         * 兜底面板：与 webView 二选一（各占满剩余高度）。
         *
         * ★为什么要它：本页在"登录硬门槛"上，之前任何一步失败都是纯白页 —— 用户既不知道发生了什么，
         *   也没有任何可点的东西（只能杀进程）。这里至少给出"发生了什么 + 重试"。
         */
        val fallbackPanel = verticalLayout {
            visibility = View.GONE
            gravity = Gravity.CENTER
            setPadding(dip(24))
            addView(fallbackText, lParams(wrapContent, wrapContent) {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(retryButton, lParams(wrapContent, wrapContent) {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dip(16)
            })
        }

        override val root: View = verticalLayout {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            fitsSystemWindows = true

            addView(toolBar, lParams(matchParent, wrapContent))

            addView(webView, lParams(matchParent, matchParent) {
                weight = 1f
            })

            addView(fallbackPanel, lParams(matchParent, matchParent) {
                weight = 1f
            })

        }

        /**
         * 真正发起页面加载。[attempt] = 本次尝试的代际号，会以 `_n=` 写进 URL 当**文档身份**
         * （WebView 回调只带 URL；验证码页只读 `gt`/`challenge`，多一个参数不影响它）。
         */
        fun loadValidator(gt: String, challenge: String, attempt: Int) {
            loadRequested = true
            documentUrl =
                "file:///android_asset/geetest-validator/index.html?gt=$gt&challenge=$challenge&_n=$attempt"
            // 代际号单独存一份：文档身份只比它（回调 URL 可能被 WebView 规范化）
            documentAttempt = attempt
            showPage()
            webView.loadUrl(documentUrl!!)
        }

        fun showPage() {
            webView.visibility = View.VISIBLE
            fallbackPanel.visibility = View.GONE
        }

        fun showFallback(message: String, showRetry: Boolean = true) {
            fallbackText.text = message
            retryButton.visibility = if (showRetry) View.VISIBLE else View.GONE
            webView.visibility = View.GONE
            fallbackPanel.visibility = View.VISIBLE
        }

        /** Activity 销毁时收摊：先摘 JS 桥，再 destroy（否则 WebView 会把 Activity/桥一起留着） */
        fun release() {
            runCatching { webView.removeJavascriptInterface("JsBridge") }
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
    }

    interface CallBack {
        fun onGTResult(
            result: BiliGeetestUtil.GT3ResultBean,
        )
        suspend fun getGTApiJson(): JSONObject?
    }
}