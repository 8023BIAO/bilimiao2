package com.a10miaomiao.bilimiao.comm.utils

import com.a10miaomiao.bilimiao.comm.BuildConfig
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

    /**
     * 最近一次"取 mixKey 失败"的原因（成功时清空）。
     *
     * ★为什么要留这个字段：这一步失败 = **全 App 的 WBI 签名全部静默失效**，
     *   而 release 包里 WbiSigner 的日志是 Debug 门控（等于没有）。
     *   真机排查时只能靠它把"签名没签上"这件事说出来（见 [describeState]）。
     */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 签名作用域 —— **要不要签，由调用点说了算**。
     *
     * ★为什么要有这层东西（2026-09 的教训，别删）：
     *   在本文件被修好之前（`fromJson<Map<String, Any>>` 那段，见 [fetchAndCacheMixKey]），
     *   WBI 签名**从来没生效过** —— 每一次调用都在解析 nav 时抛异常、被 catch 吞掉、原样返回未签名 URL。
     *   于是"全 App 拿到的都是未签名 URL"就成了各区域的**实际基线**：
     *   评论、分区榜、番剧点评、播放进度上报……全靠这条基线跑着。
     *   修好签名的那一刻，MiaoHttp 里那条按 `"api.bilibili.com" in url` 判定的自动签名
     *   会**同时**给上面所有区域加上 `wts` + `w_rid`，等于一个"修复"顺手改掉了全 App 的请求形态。
     *   用户明确反对这种影响面（原话：**"要不然他又破坏我软件里面的其他区域"**、"这个 WBI 我真的被坑了不知道多少次了"）。
     *
     * 所以现在的规则是：
     *   - [LIVE]：直播链路（进房拿弹幕 token / 发弹幕）**缺签名直接 -352**，必须签，且不受用户开关影响；
     *   - [NON_LIVE]：非直播接口 **默认一个都不签**；将来某个接口确实需要签名时，
     *                在**它自己的调用点**显式写上这个作用域（一行），不要动全局；
     *   - `null`（默认值）：只认 [autoScopeFor] 的直播白名单，其余一律**不签** ——
     *                       也就是"逐字节回到修复前"的未签名 URL。
     */
    enum class WbiScope {
        /** 直播：唯一"默认就该签"的作用域 */
        LIVE,

        /**
         * 非直播接口的**显式**开通。
         *
         * ★注意：这条路径仍然受设置里的老开关 [MiaoHttp.isWbiEnabled]（SettingPreferences.WbiSignEnabled，
         *   默认开）约束 —— 用户把总开关关掉时，非直播接口不再签名；直播不受它影响。
         */
        NON_LIVE,
    }

    /**
     * 直播 API 域名。
     *
     * ★`api.live.bilibili.com` **不包含**子串 `api.bilibili.com`（它是 `api.` + `live.bilibili.com`），
     *   所以"直播域名"和"主站 API 域名"必须用两个判据，混用就是这次事故的来源之一。
     */
    private const val LIVE_API_HOST = "api.live.bilibili.com"

    /**
     * 自动签名白名单：**只列"不签就一定失败"的直播端点**。
     *
     *  - `/xlive/web-room/v1/index/getDanmuInfo`：进房拿弹幕 token，不签实测一律 `-352`；
     *  - `/msg/send`：发弹幕（web 通道），调用点已显式签名，列在这里只是"将来谁忘了写 scope"的兜底。
     *
     * ★为什么是"白名单端点"而不是"整个直播域名"：
     *   直播域名下大部分接口（getRoomPlayInfo / Area/getList / 直播搜索 / 直播状态）**不需要**签名，
     *   给它们平白加上 `wts` + `w_rid` 属于本次要避免的"越权改动" —— 用户要的是
     *   "**需要 WBI 的你就给他，不需要的就不给他**"。
     */
    private val AUTO_SIGN_LIVE_PATHS = listOf(
        "/xlive/web-room/v1/index/getDanmuInfo",
        "/msg/send",
    )

    /** URL 是否属于直播域名。给 MiaoHttp / 调用点判断用。 */
    fun isLiveUrl(rawUrl: String): Boolean = LIVE_API_HOST in rawUrl

    /**
     * MiaoHttp 的**自动**签名判据：返回 `null` = 不签（URL 原样发出去，等于"修复前"的行为）。
     *
     * 纯函数（不联网、不读缓存），所以能直接在 JVM 上跑断言 —— 见报告里的"逐字节等价"证据。
     */
    fun autoScopeFor(rawUrl: String): WbiScope? {
        if (!isLiveUrl(rawUrl)) return null
        return if (AUTO_SIGN_LIVE_PATHS.any { it in rawUrl }) WbiScope.LIVE else null
    }

    /**
     * 这次到底签不签。
     *
     * ★默认值（`scope = null`）就是"修复前的行为"：**只有直播白名单会签**，其它区域原样放行。
     *   任何要给非直播接口开签名的改动，都必须在这里看得见地写明 [WbiScope.NON_LIVE]，
     *   而不是靠改全局判据（那是上次踩坑的方式）。
     */
    fun shouldSign(rawUrl: String, scope: WbiScope? = null): Boolean = when (scope) {
        // 直播：必须签。刻意**不看** [MiaoHttp.isWbiEnabled] —— 用户要求"不需要用户说开不开，
        // 直播直接需要的就给他"，所以直播链路的可用性不能被一个设置项卡住。
        WbiScope.LIVE -> true
        // 非直播：只有调用点显式写死 + 老总开关开着才签
        WbiScope.NON_LIVE -> MiaoHttp.isWbiEnabled
        // 未声明：只认直播白名单
        null -> autoScopeFor(rawUrl) != null
    }

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
            // ★★★ 根因修复：这里原来写的是 `MiaoJson.fromJson<Map<String, Any>>(navBody)`。
            //   kotlinx.serialization 里 **`Any` 没有序列化器**，reified 版 decodeFromString 在
            //   **运行期**必然抛 `SerializationException: Serializer for class 'Any' is not found.`
            //   （已用 JVM + kotlinx-serialization 1.11.0 实测：未登录 / 登录态 / 含 null 三种 nav 响应全抛）。
            //   异常被本函数末尾的 catch 吞掉 → 永远返回 "" → [signUrlBlocking] 原样返回**未签名** URL
            //   → `getDanmuInfo` 稳定 -352（风控），而代码里明明写着签名、release 包里却一行日志都没有。
            //   （旁证：全工程 `fromJson<Map<String, Any>>` 只有这一处调用，所以这个 bug 只打 WBI 一条链路。）
            //   改用 JsonElement 逐层取值：只用 JSON 树，不依赖 data class / Any 序列化器，
            //   以后 nav 增删字段也不会再让"取 key"这一步整体失败。
            val root = MiaoJson.kotlinJson.parseToJsonElement(navBody)
            val imgUrl = root.str("data", "wbi_img", "img_url")
            val subUrl = root.str("data", "wbi_img", "sub_url")
            if (imgUrl.isNullOrEmpty() || subUrl.isNullOrEmpty()) {
                // 用 ERROR 级留痕：MiaoLogger / logcat 在 release 下只放行 ERROR，
                // 而这一步失败等于"整个 App 的 WBI 签名全废"，必须能在真机上看到
                val navCode = root.str("code") ?: "?"
                lastError = "nav 取不到 wbi_img（HTTP=${response.code} code=$navCode " +
                    "body前120=${navBody.take(120)}）"
                android.util.Log.e("WbiSigner", lastError!!)
                return ""
            }
            if (BuildConfig.DEBUG) android.util.Log.d("WbiSigner", "imgUrl=$imgUrl subUrl=$subUrl")
            val imgKey = imgUrl.substringAfterLast("/").substringBefore(".")
            val subKey = subUrl.substringAfterLast("/").substringBefore(".")
            val rawKey = imgKey + subKey
            val mixKey = getMixinKey(rawKey)
            if (mixKey.isEmpty()) {
                // 同上：key 长度不对时原来只返回空串，release 下完全不可见
                lastError = "nav 的 wbi key 长度异常（rawKeyLen=${rawKey.length}，正常 64）"
                android.util.Log.e("WbiSigner", lastError!!)
                return ""
            }
            if (BuildConfig.DEBUG) android.util.Log.d("WbiSigner", "imgKey=$imgKey subKey=$subKey rawKey=$rawKey mixKey=${mixKey.take(4)}...")
            // 成功：清掉上次的失败痕迹（否则 describeState 会一直报旧的错）
            lastError = null
            return mixKey
        } catch (e: Exception) {
            lastError = "取 WBI keys 异常：${e.javaClass.simpleName}: ${e.message}"
            // ★不再用 BuildConfig.DEBUG 门控：这条错误在 release 下必须可见
            android.util.Log.e("WbiSigner", lastError!!)
            return ""
        }
    }

    /**
     * 从 JSON 树里按路径取字符串：任一层缺失 / 类型不符 / 为 null 都返回 null，**不抛异常**。
     *
     * ★为什么不写成 `obj["data"]!!.jsonObject["wbi_img"]!!...`：
     *   那样任何一层结构变化都会抛，而被上层 catch 一裹，"取不到 key"和"网络异常"就混成一条，
     *   排障时分不清到底是没网、被风控还是接口改版 —— 这次 -352 排查就吃了这个亏。
     */
    private fun JsonElement.str(vararg path: String): String? {
        var cur: JsonElement? = this
        for (p in path) {
            cur = (cur as? JsonObject)?.get(p) ?: return null
        }
        // JsonNull 也是 JsonPrimitive，contentOrNull 对它返回 null，正合适
        return (cur as? JsonPrimitive)?.contentOrNull
    }

    /**
     * 让下一次 [getMixKey] 必须重新联网取 keys（缓存与"今日已取"标记一起清掉）。
     *
     * ★给谁用：调用方发现签名没拼进 URL（`w_rid` 缺失）时，先清缓存重取一次再签；
     *   典型场景是首次 nav 请求恰好撞上弱网/瞬断 —— 不清缓存的话"今日已取"不会成立，
     *   但显式清一次比"等下一次自然过期"更快自愈。
     */
    fun invalidateCache() {
        mixKey = null
        lastFetchDay = -1
    }

    /**
     * 一行状态：**签名到底有没有生效**，给弹幕 trace / 排障页用（release 也能读）。
     *
     * ★为什么需要它：失败的表面现象只有 `code=-352`（"业务拒绝"），
     *   而 -352 既可能是"签名没签上"，也可能是"签名无效/被风控"，
     *   不给状态就只能靠猜 —— 这正是上一版真机排查卡住的地方。
     */
    fun describeState(): String {
        val key = mixKey
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        // 作用域信息一起报：现在"没签名"有两种完全不同的原因 ——
        // ①作用域不让签（非直播，正常）；②该签但 mixKey 取不到（故障）。
        // 不把这两件事分开，真机上又会绕回"只看到 -352、只能靠猜"的老路。
        val scopeInfo = "；签名范围=仅直播；非直播opt-in开关=${if (MiaoHttp.isWbiEnabled) "开" else "关"}"
        return when {
            key.isNullOrEmpty() ->
                "mixKey=未取到" + (lastError?.let { "（$it）" } ?: "") + scopeInfo
            lastFetchDay != today ->
                "mixKey=${key.take(8)}…（取于第 $lastFetchDay 天，今日未刷新）" + scopeInfo
            else ->
                "mixKey=${key.take(8)}…（今日已取、可用于签名）" + scopeInfo
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

    /**
     * 对 URL 追加 WBI 签名参数（w_rid + wts）。
     *
     * @param scope 见 [WbiScope]；**默认 `null` = 只有直播白名单会签**，其余原样返回。
     */
    suspend fun signUrl(rawUrl: String, scope: WbiScope? = null): String {
        if (!shouldSign(rawUrl, scope)) {
            logSkip(rawUrl, scope)
            // ★逐字节原样返回：这就是"修复前"所有区域实际拿到的东西
            return rawUrl
        }
        val mixKey = getMixKey()
        if (mixKey.isEmpty()) return rawUrl
        return appendSignature(rawUrl, mixKey)
    }

    /**
     * 同步签名：缓存命中时纯内存计算（无协程、无阻塞）；
     * 仅当 mixKey 未缓存时才阻塞获取一次。
     *
     * 修复：原实现每次新建 CoroutineScope 且从不 cancel（作用域泄漏），
     * 并用 CountDownLatch.await() 阻塞调用线程，即便缓存命中也走协程调度。
     *
     * @param scope 见 [WbiScope]；**默认 `null` = 只有直播白名单会签**，其余原样返回。
     */
    fun signUrlBlocking(rawUrl: String, scope: WbiScope? = null): String {
        if (!shouldSign(rawUrl, scope)) {
            logSkip(rawUrl, scope)
            // ★逐字节原样返回：这就是"修复前"所有区域实际拿到的东西
            return rawUrl
        }
        val mixKey = getMixKeySync()
        if (mixKey.isEmpty()) return rawUrl
        return appendSignature(rawUrl, mixKey)
    }

    /**
     * 不签时的留痕。
     *
     * ★为什么要记：静默不签正是这次事故的形态（`signUrlBlocking` 原样返回、release 里一行日志都没有，
     *   真机只看到业务侧 `-352`）。这里至少在 DEBUG 下说清楚"是作用域不让签"，
     *   而不是"签了但没拼上"。
     */
    private fun logSkip(rawUrl: String, scope: WbiScope?) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "WbiSigner",
                "按作用域跳过签名（scope=${scope ?: "auto"} live=${isLiveUrl(rawUrl)}）: ${rawUrl.take(120)}"
            )
        }
    }

    /**
     * **纯函数**：把 `wts` + `w_rid` 拼到 URL 上（不联网、不读缓存、不碰 Android API）。
     *
     * ★为什么单独拆出来：签名算法的正确性必须能在 JVM 上直接验证（本文件被 Android 依赖缠住，
     *   整包单测跑不起来）。拆开后，JVM 侧只要塞一个已知 mixKey 就能跑真实算法，
     *   报告里的"非直播逐字节等价 / 直播签名正确"就是这么验的。
     */
    internal fun appendSignature(rawUrl: String, mixKey: String): String {
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
