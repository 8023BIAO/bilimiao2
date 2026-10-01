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
        for (cookie in cookies) upsertInternal(cookie)
        persistToDisk()
    }

    /**
     * 读-改-写必须原子。
     *
     * 原来是 `store[key] ?: mutableListOf()` 取出来复制、改完再 put 回去：两个线程（比如
     * 并发的两个请求同时回 Set-Cookie）会各自读到同一份旧列表，后写的把先写的覆盖掉，
     * 表现为"cookie 偶尔丢一个"。ConcurrentHashMap.compute 把整段更新锁在同一个桶上。
     */
    private fun upsertInternal(cookie: Cookie) {
        store.compute(cookie.domain) { _, existing ->
            val list = existing?.toMutableList() ?: mutableListOf()
            list.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            list.add(cookie)
            list
        }
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
        upsertInternal(cookie)
        persistToDisk()
    }

    fun upsertAll(cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        for (cookie in cookies) upsertInternal(cookie)
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
        // 用共享的真实 URL 列表：原来这里还多一条 `https://.bilibili.com`，那不是合法 URL（永远读不到东西）
        val expiresAt = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        for (domain in WEB_COOKIE_URLS) {
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

    /**
     * 将关键 cookie 同步到 WebView CookieManager。
     *
     * ★ 只同步**指纹类** cookie；SESSDATA / bili_jct / DedeUserID / sid 这类**身份凭据一律不回写**。
     *   原因：登出（游客模式）后 CookieManager 是干净的，但本仓库可能还留着旧的 SESSDATA，
     *   一旦回写，游客模式就变回"已登录"了（用户实测担心的问题）。
     *   身份 cookie 的正路是登录时由 [BilimiaoCommApp.setCookie] 写入，不靠这里补。
     *
     * ★ 写入统一走 [writeRawCookie]（真实 URL + 回读校验 + 只打名字的日志）：
     *   以前这里直接把 `.bilibili.com` 当 url 写，**根本没写进去**过 —— 顺带说明
     *   "把 4 个拼成一条会被 WebView 整条丢掉"那段注释描述的坑在当时也没实际发生（因为压根没写成功）。
     */
    fun syncToWebView() {
        for (name in FP_COOKIE_NAMES) {
            val value = getCookieValue(name) ?: continue
            if (value.isEmpty()) continue
            writeRawCookie(name, value)
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
        /** 读/写 Cookie 用的真实 URL（★`CookieManager` 这两个 API 的第一个参数都是 URL，传 `.bilibili.com` 无效） */
        private val WEB_COOKIE_URLS = listOf(
            "https://www.bilibili.com",
            "https://api.bilibili.com",
            "https://passport.bilibili.com",
        )

        /** 指纹 cookie 名（syncToWebView 只同步这些，身份 cookie 不回写） */
        private val FP_COOKIE_NAMES = listOf("buvid3", "buvid4", "b_nut", "bili_ticket")

        /** 身份 Cookie 名：只有它们能证明"这是登录凭据"（buvid3 那类指纹不算） */
        internal val IDENTITY_COOKIE_NAMES = listOf("SESSDATA", "bili_jct", "DedeUserID")

        /** 有效期兜底（`LoginInfo.Cookie.expires` 缺失/为 0 时用） */
        const val ONE_YEAR_SECONDS = 365L * 24 * 60 * 60

        /** 默认 cookie 属性：path + 域 + 一年有效期 */
        private val DEFAULT_ATTRS = "; Path=/; Domain=.bilibili.com; Max-Age=$ONE_YEAR_SECONDS"

        /**
         * 往 WebView 写一条 cookie —— **全 App 唯一实现**（身份 cookie 与指纹 cookie 都走它）。
         *
         * ★`CookieManager.setCookie(url, value)` 的第一个参数是 **URL**：传 `.bilibili.com` 这种裸域
         *   等于什么都没写。历史上有三处都这么写（登录 Cookie、指纹同步、游客匿名指纹），
         *   于是它们**全是空操作** —— 表现为「CSRF 认证失败」「导出的身份文件 cookie 是空壳」
         *   「游客模式补匿名指纹防 -352 从未生效」。
         *
         * ★`attributes` 由调用方给：身份 cookie 需要 `Domain/Max-Age/HttpOnly`（`expires` 是**秒级时间戳**，
         *   不是 HTTP 日期，必须换算成 `Max-Age`）；指纹 cookie 用默认值即可。
         *
         * @return 写完**立刻回读**第一个真实 URL，是否读到了这条 cookie（按名字匹配）。
         *         **不抛异常**（写失败不能打断登录流程）。
         */
        fun writeRawCookie(
            name: String,
            value: String,
            attributes: String = DEFAULT_ATTRS,
        ): Boolean {
            if (name.isBlank() || value.isBlank()) return false
            val cookieManager = runCatching { android.webkit.CookieManager.getInstance() }.getOrNull()
            if (cookieManager == null) {
                miaoLogger().e("写Cookie失败", "name=$name", "CookieManager 不可用")
                return false
            }
            WEB_COOKIE_URLS.forEach { url ->
                runCatching { cookieManager.setCookie(url, "$name=$value$attributes") }
            }
            runCatching { cookieManager.flush() }
            val names = readBackCookieNames(cookieManager)
            val ok = names.any { it.equals(name, ignoreCase = true) }
            // 只打 cookie 名，绝不落值
            miaoLogger().e("写Cookie${if (ok) "成功" else "失败"}", "name=$name", "回读到=${names.joinToString(",")}")
            return ok
        }

        /** 回读真实 URL 上的 cookie 名（只要名字，用来判断"到底写进去没有"） */
        fun readBackCookieNames(
            cookieManager: android.webkit.CookieManager,
            url: String = WEB_COOKIE_URLS.first(),
        ): List<String> = runCatching { cookieManager.getCookie(url) }.getOrNull().orEmpty()
            .split(";")
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }

        @Volatile
        private var instance: CookieStore? = null

        fun getInstance(context: Context): CookieStore {
            return instance ?: synchronized(this) {
                instance ?: CookieStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
