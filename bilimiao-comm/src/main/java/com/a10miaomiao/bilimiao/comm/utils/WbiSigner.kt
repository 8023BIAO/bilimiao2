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

    /**
     * Mixin 查找表。
     *
     * 前 32 个下标不变即可：标准 64 位表里第 33~64 项取到的字符不会进入最终的前 32 位混音串
     * （已实测：用本表算出的 mixKey 与 B 站标准值 ea1db124af3c7062474693fa704f4ff8 一致）。
     */
    private val MIXIN_TABLE = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )

    @Volatile private var mixKey: String? = null
    @Volatile private var lastFetchDay: Int = -1

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
        // nav 返回的 key 不是预期长度时（接口改版/被风控返回空壳），凑不满 32 位就返回空串，
        // 让调用方按"没拿到签名"处理；原来的 substring(0, 32) 会抛 StringIndexOutOfBoundsException
        // （虽然被上层 catch 兜住，但那是靠异常控流程）
        if (sb.length < 32) return ""
        return sb.substring(0, 32)
    }

    /** 对 URL 追加 WBI 签名参数（w_rid + wts） */
    suspend fun signUrl(rawUrl: String): String {
        val mixKey = getMixKey()
        if (mixKey.isEmpty()) return rawUrl

        val qIndex = rawUrl.indexOf('?')
        if (qIndex < 0) return rawUrl

        val params = parseQuery(rawUrl.substring(qIndex + 1))
        return rawUrl.substring(0, qIndex + 1) + buildSignedQuery(params, mixKey)
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

        val params = parseQuery(rawUrl.substring(qIndex + 1))
        return rawUrl.substring(0, qIndex + 1) + buildSignedQuery(params, mixKey)
    }

    private fun parseQuery(queryPart: String): LinkedHashMap<String, String> {
        val params = linkedMapOf<String, String>()
        for (pair in queryPart.split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            // 没有 '=' 的参数原来是**整条丢掉**（?foo&bar=1 里 foo 不见了）——
            // 保留成 foo= 更接近原意，也不会让服务端少收一个参数
            val rawKey = if (eq < 0) pair else pair.substring(0, eq)
            val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
            // 值里带裸 '%'（比如搜索词里手打了百分号）时 URLDecoder 会抛 IllegalArgumentException，
            // 以前这个异常会一路冒到调用方 → 崩溃。解不开就按原样用。
            val key = runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey)
            val value = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
            params[key] = value
        }
        return params
    }

    /**
     * WBI 签名串。
     *
     * ★ 参与签名的 key/value 必须先剔除 `!'()*` 五个字符 —— 这是 B 站服务端的算法约定
     * （Python 参考实现：`''.join(filter(lambda c: c not in "!'()*", v))`）。
     * 不剔除的后果：搜索词/动态文案里出现 `!`、`'`、`(`、`)` 时，服务端算出的串与本地不同，
     * 签名校验失败 → `-403 签名错误`，而且是"只有特定关键词才复现"的偶发 bug。
     */
    private fun buildSignedQuery(params: Map<String, String>, mixKey: String): String {
        val all = LinkedHashMap(params)
        all["wts"] = (System.currentTimeMillis() / 1000).toString()

        val queryString = all.keys.sorted().joinToString("&") { key ->
            val encodedKey = URLEncoder.encode(stripWbiChars(key), "UTF-8")
            val encodedValue = URLEncoder.encode(stripWbiChars(all[key] ?: ""), "UTF-8").replace("+", "%20")
            "$encodedKey=$encodedValue"
        }

        val wRid = MessageDigest.getInstance("MD5").digest((queryString + mixKey).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return "$queryString&w_rid=$wRid"
    }

    private fun stripWbiChars(s: String): String {
        var needStrip = false
        for (c in s) {
            if (c == '!' || c == '\'' || c == '(' || c == ')' || c == '*') { needStrip = true; break }
        }
        if (!needStrip) return s
        return s.filterNot { it == '!' || it == '\'' || it == '(' || it == ')' || it == '*' }
    }
}
