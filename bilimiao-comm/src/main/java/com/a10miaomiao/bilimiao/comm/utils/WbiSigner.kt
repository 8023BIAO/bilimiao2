package com.a10miaomiao.bilimiao.comm.utils

import com.a10miaomiao.bilimiao.comm.BuildConfig
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * B站 Web API WBI 签名
 * 从 /x/web-interface/nav 获取 img_key + sub_key，混合后 MD5 签名
 */
object WbiSigner {

    /** Mixin 查找表（32位） */
    private val MIXIN_TABLE = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13
    )

    private var mixKey: String? = null
    private var lastFetchDay: Int = -1

    /** 导出 WBI 缓存数据 */
    fun getWbiCache(): Map<String, Any?> {
        return mapOf(
            "mixKey" to mixKey,
            "lastFetchDay" to lastFetchDay,
        )
    }

    /** 恢复 WBI 缓存数据 */
    fun restoreWbiCache(data: Map<String, Any?>) {
        mixKey = data["mixKey"] as? String
        lastFetchDay = data["lastFetchDay"] as? Int ?: -1
    }

    /** 获取 mix_key（每天刷新一次） */
    suspend fun getMixKey(): String {
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        if (mixKey != null && mixKey!!.isNotEmpty() && lastFetchDay == today) {
            return mixKey!!
        }
        // 缓存为空或已过期，重新获取
        return fetchAndCacheMixKey().also {
            if (it.isNotEmpty()) {
                mixKey = it
                lastFetchDay = today
            }
        }
    }

    /**
     * 同步获取 mix_key（缓存命中时纯内存返回，未命中才阻塞网络请求）。
     * 供 signUrlBlocking 在签名前预取，避免无谓的协程调度。
     */
    private fun getMixKeySync(): String {
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        if (mixKey != null && mixKey!!.isNotEmpty() && lastFetchDay == today) {
            return mixKey!!
        }
        // 缓存未命中：在 IO 线程阻塞获取一次并缓存
        return runBlocking(Dispatchers.IO) {
            getMixKey()
        }
    }

    private suspend fun fetchAndCacheMixKey(): String {
        try {
            if (BuildConfig.DEBUG) android.util.Log.d("WbiSigner", "→ 请求 nav 接口获取 WBI keys...")
            val response = MiaoHttp.request {
                url = "https://api.bilibili.com/x/web-interface/nav"
                isWebApi = true  // nav 是 WEB API，不能带 app-key/Authorization 等 APP 头
            }.awaitCall()
            val navBody = response.body?.string() ?: ""
            if (BuildConfig.DEBUG) android.util.Log.d("WbiSigner", "← nav 响应: code=${response.code}, body前200=${navBody.take(200)}")
            val navRes = MiaoJson.fromJson<Map<String, Any>>(navBody)
            val data = navRes["data"] as? Map<*, *>
            if (data == null) { if (BuildConfig.DEBUG) android.util.Log.e("WbiSigner", "nav data 为空"); return "" }
            val wbiImg = data["wbi_img"] as? Map<*, *>
            if (wbiImg == null) { if (BuildConfig.DEBUG) android.util.Log.e("WbiSigner", "nav wbi_img 为空"); return "" }
            val imgUrl = wbiImg["img_url"] as? String
            val subUrl = wbiImg["sub_url"] as? String
            if (imgUrl == null || subUrl == null) { if (BuildConfig.DEBUG) android.util.Log.e("WbiSigner", "nav img_url/sub_url 为空"); return "" }
            if (BuildConfig.DEBUG) android.util.Log.d("WbiSigner", "imgUrl=$imgUrl subUrl=$subUrl")
            val imgKey = imgUrl.substringAfterLast("/").substringBefore(".")
            val subKey = subUrl.substringAfterLast("/").substringBefore(".")
            val rawKey = imgKey + subKey
            val mixKey = getMixinKey(rawKey)
            if (BuildConfig.DEBUG) android.util.Log.d("WbiSigner", "imgKey=$imgKey subKey=$subKey rawKey=$rawKey mixKey=${mixKey.take(4)}...")
            return mixKey
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.e("WbiSigner", "fetchAndCacheMixKey 异常: ${e.javaClass.simpleName}: ${e.message}")
            return ""
        }
    }

    private fun getMixinKey(raw: String): String {
        val sb = StringBuilder()
        for (i in MIXIN_TABLE) {
            if (i < raw.length) {
                sb.append(raw[i])
            }
        }
        return sb.toString().substring(0, 32)
    }

    /** 对 URL 追加 WBI 签名参数（w_rid + wts） */
    suspend fun signUrl(rawUrl: String): String {
        val mixKey = getMixKey()
        if (mixKey.isEmpty()) return rawUrl

        val qIndex = rawUrl.indexOf('?')
        if (qIndex < 0) return rawUrl

        val params = linkedMapOf<String, String>()
        val queryPart = rawUrl.substring(qIndex + 1)
        for (pair in queryPart.split("&")) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val key = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
            val value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            params[key] = value
        }

        val wts = (System.currentTimeMillis() / 1000).toString()
        params["wts"] = wts

        val sortedKeys = params.keys.sorted()
        val queryString = sortedKeys.joinToString("&") { key ->
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val encodedValue = URLEncoder.encode(params[key] ?: "", "UTF-8")
            "$encodedKey=$encodedValue"
        }

        val rawSign = queryString + mixKey
        val wRid = MessageDigest.getInstance("MD5").digest(rawSign.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return rawUrl.substring(0, qIndex + 1) + queryString + "&w_rid=$wRid"
    }

    /**
     * 同步签名：缓存命中时纯内存计算（无协程、无阻塞）；
     * 仅当 mixKey 未缓存时才阻塞获取一次。
     *
     * 修复：原实现每次新建 CoroutineScope 且从不 cancel（作用域泄漏），
     * 并用 CountDownLatch.await() 阻塞调用线程，即便缓存命中也走协程调度。
     */
    fun signUrlBlocking(rawUrl: String): String {
        val mixKey = getMixKeySync()
        if (mixKey.isEmpty()) return rawUrl

        val qIndex = rawUrl.indexOf('?')
        if (qIndex < 0) return rawUrl

        val params = linkedMapOf<String, String>()
        val queryPart = rawUrl.substring(qIndex + 1)
        for (pair in queryPart.split("&")) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val key = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
            val value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            params[key] = value
        }

        val wts = (System.currentTimeMillis() / 1000).toString()
        params["wts"] = wts

        val sortedKeys = params.keys.sorted()
        val queryString = sortedKeys.joinToString("&") { key ->
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val encodedValue = URLEncoder.encode(params[key] ?: "", "UTF-8")
            "$encodedKey=$encodedValue"
        }

        val rawSign = queryString + mixKey
        val wRid = MessageDigest.getInstance("MD5").digest(rawSign.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return rawUrl.substring(0, qIndex + 1) + queryString + "&w_rid=$wRid"
    }
}
