package com.a10miaomiao.bilimiao.comm.network

import android.content.Context
import android.content.SharedPreferences
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 持久化 OkHttp CookieJar — 自包含，不依赖 MiaoHttp。
 *
 * 用于 WebCookieMaintainer 存储 buvid3/buvid4/bili_ticket 等指纹 cookie。
 * 通过 syncToWebView() 同步到 WebView CookieManager，MiaoHttp 从 CookieManager 读取。
 *
 * 参考 blbl 项目 CookieStore.kt。
 */
class CookieStore private constructor(context: Context) : CookieJar {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("bilimiao_cookie_store", Context.MODE_PRIVATE)

    private val store: ConcurrentHashMap<String, MutableList<Cookie>> = ConcurrentHashMap()

    init {
        loadFromDisk()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        for (cookie in cookies) {
            val key = cookie.domain
            val list = (store[key] ?: mutableListOf()).toMutableList()
            list.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            list.add(cookie)
            store[key] = list
        }
        persistToDisk()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.values.flatten().filter { it.expiresAt >= now && it.matches(url) }
    }

    fun cookieHeaderFor(url: HttpUrl): String? {
        val cookies = loadForRequest(url).toMutableList()
        if (cookies.isEmpty()) return null
        cookies.sortWith(compareByDescending<Cookie> { it.path.length }.thenBy { it.name })
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun hasSessData(): Boolean {
        val now = System.currentTimeMillis()
        return store.values.flatten().any { it.name == "SESSDATA" && it.expiresAt >= now }
    }

    fun getCookieValue(name: String): String? {
        val now = System.currentTimeMillis()
        return store.values.flatten().firstOrNull { it.name == name && it.expiresAt >= now }?.value
    }

    fun getCookie(name: String): Cookie? {
        val now = System.currentTimeMillis()
        return store.values.flatten().firstOrNull { it.name == name && it.expiresAt >= now }
    }

    fun upsert(cookie: Cookie) {
        val key = cookie.domain
        val list = (store[key] ?: mutableListOf()).toMutableList()
        list.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
        list.add(cookie)
        store[key] = list
        persistToDisk()
    }

    fun upsertAll(cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        for (cookie in cookies) {
            val key = cookie.domain
            val list = (store[key] ?: mutableListOf()).toMutableList()
            list.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            list.add(cookie)
            store[key] = list
        }
        persistToDisk()
    }

    fun clearAll() {
        store.clear()
        prefs.edit().clear().apply()
    }

    /** 从 WebView CookieManager 导入登录 cookie */
    fun importFromWebView() {
        val cookieManager = try {
            android.webkit.CookieManager.getInstance()
        } catch (e: Exception) { return }
        val domains = listOf("https://www.bilibili.com", "https://api.bilibili.com", "https://passport.bilibili.com", "https://.bilibili.com")
        val expiresAt = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        for (domain in domains) {
            val header = cookieManager.getCookie(domain) ?: continue
            for (pair in header.split(";")) {
                val trimmed = pair.trim()
                val eq = trimmed.indexOf("=")
                if (eq <= 0) continue
                val name = trimmed.substring(0, eq)
                val value = trimmed.substring(eq + 1)
                if (name.isBlank()) continue
                runCatching {
                    upsert(Cookie.Builder().name(name).value(value).domain("bilibili.com").path("/").expiresAt(expiresAt).build())
                }
            }
        }
    }

    /** 将关键 cookie 同步到 WebView CookieManager */
    fun syncToWebView() {
        val cookieManager = try {
            android.webkit.CookieManager.getInstance()
        } catch (e: Exception) { return }
        val names = listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3", "buvid4", "b_nut", "bili_ticket")
        val sb = StringBuilder()
        for (name in names) {
            val value = getCookieValue(name) ?: continue
            if (sb.isNotEmpty()) sb.append("; ")
            sb.append("$name=$value")
        }
        if (sb.isNotEmpty()) {
            cookieManager.setCookie(".bilibili.com", sb.toString())
            cookieManager.flush()
        }
    }

    private fun persistToDisk(sync: Boolean = false) {
        val editor = prefs.edit().putString("cookies", buildJsonRoot(includeExpired = true).toString())
        if (sync) editor.commit() else editor.apply()
    }

    private fun loadFromDisk() {
        val raw = prefs.getString("cookies", null) ?: return
        runCatching {
            store.clear()
            store.putAll(parseJsonRoot(JSONObject(raw)))
        }.onFailure {
            miaoLogger().e("CookieStore 加载失败，清空", it)
            store.clear(); prefs.edit().clear().apply()
        }
    }

    private fun buildJsonRoot(includeExpired: Boolean): JSONObject {
        val now = System.currentTimeMillis()
        val root = JSONObject()
        for ((host, cookies) in store.entries) {
            val arr = JSONArray()
            cookies.forEach { cookie ->
                if (!includeExpired && cookie.expiresAt < now) return@forEach
                arr.put(JSONObject()
                    .put("name", cookie.name).put("value", cookie.value)
                    .put("domain", cookie.domain).put("path", cookie.path)
                    .put("expiresAt", cookie.expiresAt).put("secure", cookie.secure)
                    .put("httpOnly", cookie.httpOnly).put("hostOnly", cookie.hostOnly)
                    .put("persistent", cookie.persistent))
            }
            if (arr.length() > 0) root.put(host, arr)
        }
        return root
    }

    private fun parseJsonRoot(root: JSONObject): ConcurrentHashMap<String, MutableList<Cookie>> {
        val parsed = ConcurrentHashMap<String, MutableList<Cookie>>()
        val it = root.keys()
        while (it.hasNext()) {
            val domain = it.next()
            val arr = root.optJSONArray(domain) ?: continue
            val list = mutableListOf<Cookie>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val builder = Cookie.Builder()
                    .name(obj.getString("name")).value(obj.getString("value"))
                    .path(obj.optString("path", "/"))
                val cd = obj.optString("domain", domain)
                if (obj.optBoolean("hostOnly", false)) builder.hostOnlyDomain(cd) else builder.domain(cd)
                if (obj.optBoolean("secure", false)) builder.secure()
                if (obj.optBoolean("httpOnly", false)) builder.httpOnly()
                val ea = obj.optLong("expiresAt", 0L)
                if (ea > 0L) builder.expiresAt(ea)
                list.add(builder.build())
            }
            if (list.isNotEmpty()) parsed[domain] = list
        }
        return parsed
    }

    companion object {
        @Volatile
        private var instance: CookieStore? = null

        fun getInstance(context: Context): CookieStore {
            return instance ?: synchronized(this) {
                instance ?: CookieStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
