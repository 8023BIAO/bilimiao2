package com.a10miaomiao.bilimiao.comm.network

import android.webkit.CookieManager
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.comm.utils.WbiSigner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MiaoHttp(var url: String? = null) {

    private val cookieManager by lazy {
        try {
            CookieManager.getInstance()
        } catch (e: Exception) {
            miaoLogger().e("CookieManager init failed", e)
            null
        }
    }

    private val client get() = sharedClient
    val headers = mutableMapOf<String, String>()
    var method = GET

    var body: RequestBody? = null
    var formBody: Map<String, String?>? = null

    /** 纯 WEB API 模式：跳过 app-key/Authorization 等 APP 头部，仅依赖 Cookie + WBI 签名 */
    var isWebApi = false

    /**
     * 游客模式：**不带登录 Cookie**，用来复现"没登录的人看到什么"。
     *
     * 评论反诈检测就靠这个：同一条评论，带 Cookie 查得到、游客查不到 = 仅自己可见（ShadowBan）。
     * Cookie 头是本类手工加的（sharedClient 没挂 CookieJar），所以跳过那一段就是真游客。
     */
    var asGuest = false

    /**
     * 游客模式下唯一要带的 Cookie。
     *
     * B站 x/v2/reply/main 没有 buvid3 会直接回 -352 风控校验失败（实测），
     * 但带上完整登录 Cookie 就不是游客了 —— 所以只带 buvid3 这一条。
     */
    var guestCookie: String? = null

    private fun buildRequest(): Request {
        val requestBuilder = Request.Builder()
        requestBuilder.addHeader("User-Agent", ApiHelper.USER_AGENT)
        requestBuilder.addHeader("Referer", ApiHelper.REFERER)
        requestBuilder.addHeader("buvid", BilimiaoCommApp.commApp.getBilibiliBuvid())
        val isBiliHost = url?.let { "bilibili.com" in it } == true
        if (isBiliHost) {
            if (!isWebApi && !asGuest) {
                // APP API 头部（仅非 WEB API 模式添加；游客模式也不能带账号身份）
                requestBuilder.addHeader("env", "prod")
                requestBuilder.addHeader("app-key", "android_hd")
                BilimiaoCommApp.commApp.loginInfo?.token_info?.let{
                    requestBuilder.addHeader("x-bili-mid", it.mid.toString())
                    val token = it.access_token
                    if (token.isNotBlank()) {
                        requestBuilder.addHeader("Authorization", "identify_v1 $token")
                    }
                }
            }
            // Web API 自动加 WBI 签名。
            //
            // ★ 2026-09 收敛（用户要求："直播需要 WBI 你就给他，不需要的就不给他那个认证"）：
            //   判据从原来的 `"api.bilibili.com" in url`（**等于给全 App 的 Web 接口泛签**）
            //   改成 WbiSigner.autoScopeFor(url) —— 只有**直播域名 + 直播里确实需要签名的端点**才返回非空。
            //
            //   为什么必须收：WbiSigner 修好之前它从来没生效过（nav 解析必抛异常被吞），
            //   所以"未签名 URL"是评论 / 分区榜 / 番剧点评 / 播放进度上报等区域的**实际基线**；
            //   签名一修好，按 api.bilibili.com 泛签就会把这些区域的请求形态全部改掉
            //   （加 wts + w_rid），用户明确反对这种"修一个坏三个"的影响面。
            //   非直播接口将来确实要签名时：在它自己的调用点写 WbiScope.NON_LIVE，不要回来动这里。
            val autoScope = url?.let { WbiSigner.autoScopeFor(it) }
            val hasSign = url?.let { "sign=" in it || "w_rid=" in it } == true
            // 兜底：nav（取 WBI key 本身）与 ranking/v2 永远不签。
            // 现在白名单已经天然排除它们，留着是防止将来有人往白名单里加路径时手滑。
            val isExcluded = url?.let { "/x/web-interface/nav" in it || "ranking/v2" in it } == true
            if (autoScope != null && !hasSign && !isExcluded) {
                val beforeUrl = url
                url = WbiSigner.signUrlBlocking(
                    url ?: throw IllegalStateException("url must be set"),
                    autoScope,
                )
                if (com.a10miaomiao.bilimiao.comm.BuildConfig.DEBUG) {
                    android.util.Log.i("MiaoHttp-WBI", "签名前: $beforeUrl")
                    android.util.Log.i("MiaoHttp-WBI", "签名后: $url")
                }
            } else {
                if (com.a10miaomiao.bilimiao.comm.BuildConfig.DEBUG) {
                    android.util.Log.d("MiaoHttp-WBI", "跳过WBI: autoScope=$autoScope hasSign=$hasSign excluded=$isExcluded isWebApi=$isWebApi url=$url")
                }
            }
        }
        // 游客模式只带 buvid3；正常模式带 CookieManager 里的登录态
        val cookie = if (asGuest) guestCookie else getCookie(url)
        if (!cookie.isNullOrBlank()) {
            requestBuilder.addHeader("Cookie", cookie)
        }
        // 评论反诈排查用：把"这次到底发了哪些 Cookie"记进诊断日志（只记名字，不记值）。
        // 只有一轮反诈检测进行中才记录，平时完全不写。
        com.a10miaomiao.bilimiao.comm.antifraud.AntifraudDiag.traceRequest(url, cookie, asGuest)
        for ((key, value) in headers) {
            requestBuilder.addHeader(key, value)
        }

        if (body == null && formBody != null) {
            val bodyStr = ApiHelper.urlencode(formBody!!)
            body = bodyStr.toRequestBody(
                "application/x-www-form-urlencoded".toMediaType()
            )
        }
        val req = requestBuilder.method(method, body)
            .url(url ?: throw IllegalStateException("url must be set"))
            .build()
        return req
    }

    fun call(): Response {
        val req = buildRequest()
        return client.newCall(req).execute()
    }

    private fun getCookie(url: String?): String {
        return cookieManager?.getCookie(url) ?: ""
    }

    suspend fun awaitCall(): Response{
        miaoLogger().d(
            "method" to method,
            "url" to url,
            "formBody" to formBody
        )
        return suspendCancellableCoroutine { continuation ->
            val req = buildRequest()
            val call = client.newCall(req)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // ★ 取消和"响应到达"是竞态：continuation 取消后再 resume 是空操作，
                    //   但 response 的 body 已经打开 —— 不关就是连接泄漏（OkHttp 连接池被占满，
                    //   表现为"切视频/退页面几次之后所有请求都超时"）
                    if (continuation.isCancelled) {
                        response.close()
                        return
                    }
                    continuation.resume(response)
                }
            })
        }
    }

    fun get(): Response {
        method = GET
        return call()
    }

    fun post(): Response {
        method = POST
        return call()
    }

//    fun <T> responseType<>() {
//
//    }

    companion object {
        /**
         * WBI 签名总开关（由 FlagsSettingPage 同步写入 SettingPreferences.WbiSignEnabled，默认开）。
         *
         * ★2026-09 收敛后的语义：它**只约束非直播接口的显式 opt-in**（[WbiSigner.WbiScope.NON_LIVE]）。
         *   直播链路（弹幕 token / 发弹幕）**不受它影响** —— 用户要求"不需要用户说开不开，
         *   那直播的直接需要的就给他"，否则一个设置项就能把直播弹幕卡死。
         *   另外也不再存在"按 api.bilibili.com 自动签"这回事，见 [buildRequest] 里的注释。
         */
        @Volatile
        var isWbiEnabled: Boolean = true

        /**
         * 取 web 侧 CSRF token（cookie 里的 bili_jct）。
         *
         * 一些 web 接口（如图片上传 upload_bfs）需要它；APP 接口不需要。
         * 取不到就返回 null，调用方应自行决定是否带上该参数。
         */
        fun csrfToken(): String? = cookieValue("bili_jct")

        /** Web 登录态（SESSDATA）。APP 扫码登录时这里可能为空 */
        fun sessDataToken(): String? = cookieValue("SESSDATA")

        /**
         * 取当前 CookieManager 里 WebView 侧的某个 cookie 值（api.bilibili.com）。
         * 用于判断"有没有 web 登录态"以及给 web 接口补 csrf。
         */
        fun cookieValue(name: String): String? {
            return try {
                val cookie = CookieManager.getInstance()
                    .getCookie("https://api.bilibili.com")
                    ?: return null
                cookie.split(";")
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("$name=") }
                    ?.substringAfter('=')
                    ?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }

        /** 诊断用：只回 cookie 名字，绝不回值（SESSDATA 是凭据） */
        fun cookieNames(): String {
            return try {
                (CookieManager.getInstance().getCookie("https://api.bilibili.com") ?: "")
                    .split(";")
                    .map { it.trim().substringBefore('=') }
                    .filter { it.isNotBlank() }
                    .joinToString(",")
            } catch (e: Exception) {
                "err:${e.message}"
            }
        }

        /**
         * 全局共享 OkHttpClient：复用连接池 + 启用 HTTP 缓存，避免每个请求新建 client。
         * OkHttp 5.x 默认支持 HTTP/2 与 ALPN，无需额外配置。
         * 缓存仅对带 Cache-Control/Expires 的响应生效，B 站 API 多数不带，不会误缓存实时数据。
         */
        private val sharedClient: OkHttpClient by lazy {
            val app = BilimiaoCommApp.commApp.app
            val cacheDir = File(app.cacheDir, "miao-http").apply { mkdirs() }
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .cache(Cache(cacheDir, 50L * 1024 * 1024)) // 50MB
                .retryOnConnectionFailure(true)
                .build()
        }

        fun request(url: String? = null, init: (MiaoHttp.() -> Unit)? = null) = MiaoHttp(url).apply {
            init?.invoke(this)
        }

        fun Response.string(): String {
            return this.body?.string() ?: ""
        }

        inline fun <reified T> Response.json(isLog: Boolean = false): T {
            val jsonStr = this.string()
            if (isLog) {
                miaoLogger() debug jsonStr
            }
            return MiaoJson.fromJson(jsonStr)
        }

        const val GET = "GET"
        const val POST = "POST"

    }
}
