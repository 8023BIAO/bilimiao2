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
import java.io.IOException
import java.lang.reflect.Type
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

    private var client = OkHttpClient()
    val headers = mutableMapOf<String, String>()
    var method = GET

    var body: RequestBody? = null
    var formBody: Map<String, String?>? = null

    /** 纯 WEB API 模式：跳过 app-key/Authorization 等 APP 头部，仅依赖 Cookie + WBI 签名 */
    var isWebApi = false

    private fun buildRequest(): Request {
        val requestBuilder = Request.Builder()
        requestBuilder.addHeader("User-Agent", ApiHelper.USER_AGENT)
        requestBuilder.addHeader("Referer", ApiHelper.REFERER)
        requestBuilder.addHeader("buvid", BilimiaoCommApp.commApp.getBilibiliBuvid())
        val isBiliHost = url?.let { "bilibili.com" in it } == true
        if (isBiliHost) {
            if (!isWebApi) {
                // APP API 头部（仅非 WEB API 模式添加）
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
            // Web API 自动加 WBI 签名（开关控制）
            // 跳过已有 app 签名或 WBI 签名的请求，以及 nav/ranking 端点
            val isApiBili = url?.let { "api.bilibili.com" in it } == true
            val hasSign = url?.let { "sign=" in it || "w_rid=" in it } == true
            val isExcluded = url?.let { "/x/web-interface/nav" in it || "ranking/v2" in it } == true
            if (isWbiEnabled && isApiBili && !hasSign && !isExcluded) {
                val beforeUrl = url
                url = WbiSigner.signUrlBlocking(url ?: throw IllegalStateException("url must be set"))
                android.util.Log.i("MiaoHttp-WBI", "签名前: $beforeUrl")
                android.util.Log.i("MiaoHttp-WBI", "签名后: $url")
            } else {
                android.util.Log.d("MiaoHttp-WBI", "跳过WBI: isWbiEnabled=$isWbiEnabled api=$isApiBili hasSign=$hasSign excluded=$isExcluded isWebApi=$isWebApi url=$url")
            }
        }
        val cookie = getCookie(url)
        if (!cookie.isNullOrBlank()) {
            requestBuilder.addHeader("Cookie", cookie)
        }
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
                    continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
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
        /** WBI 签名开关，由 FlagsSettingPage 同步写入 */
        @Volatile
        var isWbiEnabled: Boolean = true

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
