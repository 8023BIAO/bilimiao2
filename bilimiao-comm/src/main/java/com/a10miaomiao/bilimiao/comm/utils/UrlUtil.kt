package com.a10miaomiao.bilimiao.comm.utils

import android.net.Uri

object UrlUtil {

    /** 官方网关（对齐 PiliPlus `video_utils.dart:13` 的 `_proxyTf`）：只剩 P2P 候选时用它兜底 */
    private const val PROXY_TF_HOST = "proxy-tf-all-ws.bilivideo.com"

    /**
     * **裸 IP** 上"P2P 资源"的路径形态：
     * - `/v<数字>/resource`（对齐 PiliPlus `video_utils.dart:19-22` 的 `_mCdnTfRegex`）；
     * - **`/upgcxcode/`**：仓库自带的真实样例 `ArchiveInfo.kt:10` 就是这条（裸 IP + `/upgcxcode/…`
     *   + `&os=mcdn`）。上游对"含 `/upgcxcode/` 的地址"走的是"换 host 使用"分支，而我们对
     *   `os=mcdn` 保留了"不换 host"的守卫 ⇒ 两边都拿不到有效形态；**交给官方网关更强**
     *   （裸 IP 的 P2P 源本来就常连不上）。
     *
     * ★只匹配**路径开头**（`/upgcxcode/` 或 `/v<数字>/resource` 必须在 path 的第一段）：
     *   判定前先取 path（见 [pathOf]），**不拿 query 里编码过的 `%2Fupgcxcode%2F` 当旁证** ——
     *   那是参数值，不是资源路径。裸 IP + 其它路径**不判 P2P**，别把"其实是 CDN 的裸 IP"一网打尽。
     */
    private val BARE_IP_P2P_PATH_REGEX = Regex("^/(?:v\\d+/resource|upgcxcode)/", RegexOption.IGNORE_CASE)

    /** 取 host（不含 query —— 诊断日志只允许记 host，见 [replaceHost] / [resolveP2pFallback]） */
    private fun hostOf(url: String): String =
        Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://([^/?#]+)").find(url)?.groupValues?.get(1).orEmpty()

    /** 取 **path**（去掉 scheme/host 与 `?query`/`#fragment`；没有路径段时返回空串） */
    private fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://", "")
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return ""
        return afterScheme.substring(slash).substringBefore('?').substringBefore('#')
    }

    /** 裸 IP host（可带端口），例如 `123.245.243.4` / `123.245.243.4:4480` */
    private fun isBareIpHost(host: String): Boolean {
        val h = host.substringBefore(':')
        if (h.isEmpty()) return false
        val parts = h.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.all(Char::isDigit) && (p.toIntOrNull() ?: -1) in 0..255 }
    }

    fun autoHttps(url: String) =if ("://" in url) {
        url.replace("http://","https://")
    } else {
        "https:$url"
    }

    fun getQueryKeyValueMap(uri: Uri): HashMap<String, String> {
        val keyValueMap = HashMap<String, String>()
        var key: String
        var value: String

        val keyNamesList = uri.queryParameterNames
        val iterator = keyNamesList.iterator()

        while (iterator.hasNext()) {
            key = iterator.next() as String
            value = uri.getQueryParameter(key) as String
            keyValueMap.put(key, value)
        }
        return keyValueMap
    }

    /**
     * host 正则替换。
     *
     * ★`os=mcdn` 特判：这类地址的 host 是 CDN 按签名派发的，与普通镜像**不同源** ——
     *   盲目换 host 后通常直接失效，所以这里**原样返回**。
     *   ★与参考实现的差异（写清楚，别误记）：PiliPlus `video_utils.dart:42-44` 把这类地址记下来、
     *   `:88-90` **仍会换成用户选定的 CDN 主机**再使用；我们**保守起见不动它** ——
     *   宁可少做一步，也不把本来能播的地址改坏。它作为候选用在 [resolveP2pFallback] 的②优先级里。
     */
    fun replaceHost(url: String, host: String): String {
        if (hasMcdnQuery(url)) {
            PlayerDiag.log("cdn-mcdn", "keep os=mcdn host=${hostOf(url)}")
            return url
        }
        return url.replace(":\\\\?\\/\\\\?\\/[^\\/]+\\\\?\\/".toRegex(), "://${host}/")
    }

    /** 查询串里带 `os=mcdn` 吗（只做字符串解析，不依赖 `android.net.Uri`，便于纯 JVM 复核） */
    fun hasMcdnQuery(url: String): Boolean {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return false
        return query.split('&').any { param ->
            param.substringBefore('=', "") == "os" &&
                param.substringAfter('=', "").equals("mcdn", ignoreCase = true)
        }
    }

    /**
     * 这个地址是不是**真正的 P2P** 候选：
     * ① host 含 `mcdn`/`pcdn`；或
     * ② **裸 IP** 且 path 以 `/v<数字>/resource`（对齐 PiliPlus `video_utils.dart:54-57` 的
     *    `_mCdnTfRegex`）**或 `/upgcxcode/`** 开头（见 [BARE_IP_P2P_PATH_REGEX]）。
     * 裸 IP + 其它路径**不算** P2P（别把"其实是 CDN 的裸 IP"一网打尽）。
     *
     * ★查询串里的 `os=mcdn` **不算 P2P** —— 那是 CDN 派发的**普通镜像**（host 仍是 `upos-*`），
     *   参考实现把它当可用候选**直接用**（`video_utils.dart:88-90`）；只有真 P2P 才需要走官方网关。
     */
    fun isP2pLike(url: String): Boolean {
        if (url.isBlank()) return false
        val host = hostOf(url)
        // ① mcdn / pcdn 主机：本来就算 P2P（与多线程那边排除 PCDN/MCDN 同口径）
        if (host.contains("mcdn", ignoreCase = true) || host.contains("pcdn", ignoreCase = true)) return true
        // ② 裸 IP：**看 path**（`/v<数字>/resource` 或 `/upgcxcode/` 起头）才算 P2P；其它路径不算
        if (!isBareIpHost(host)) return false
        return BARE_IP_P2P_PATH_REGEX.containsMatchIn(pathOf(url))
    }

    /**
     * 官方网关兜底：`https://<gateway>?url=<整体 URL 编码的原地址>`（对齐 PiliPlus `video_utils.dart:80-87`）。
     *
     * 原地址**整体编码**（含 `?`/`&` 等），保证网关只看到**一个** `url` 参数。
     */
    fun proxyTfUrl(url: String): String =
        "https://$PROXY_TF_HOST?url=" + java.net.URLEncoder.encode(url, "UTF-8")

    /**
     * 候选列表兜底。优先级对齐 PiliPlus `video_utils.dart:38-91`：
     * ① 有普通候选（既不是 P2P、也不带 `os=mcdn`）⇒ **原样返回**（顺序与数量都不动）；
     * ② 否则有**非 P2P 的** `os=mcdn` 镜像 ⇒ **原样返回**（它是可用镜像，参考实现同样优先直接用它）；
     * ③ 只剩真 P2P（裸 IP 的 `/v<数字>/resource` **或 `/upgcxcode/`**、`*.mcdn.bilivideo.com`、
     *    pcdn 主机）⇒ 折叠成单条官方网关。
     *    ★真 P2P **带不带 `os=mcdn` 都走这一条**（`os=mcdn` 只对非 P2P 候选起"镜像"作用）。
     *
     * 空候选原样返回（不无中生有）；"非全 P2P 就原样返回（顺序/数量不变）"是硬约束 ——
     * 运行时跨 CDN 故障转移依赖这个列表。
     */
    fun resolveP2pFallback(urls: List<String>): List<String> {
        val usable = urls.filter { it.isNotBlank() }
        if (usable.isEmpty()) return urls
        // ① 普通镜像优先
        if (usable.any { !isP2pLike(it) && !hasMcdnQuery(it) }) return urls
        // ② 退而求其次：`os=mcdn` 的**镜像**本身可用，直接用（不换 host、不套网关）。
        //   ★但**真 P2P 不算镜像**：`os=mcdn` 只是 query 上的标记，真 P2P 的 host/路径不会因为
        //     带它就变成镜像 —— 且现实里二者共现是常态（仓库自带的真实样例就是裸 IP + `&os=mcdn`），
        //     若这里只判 `hasMcdnQuery`，第③优先级的网关兜底几乎一次都不会触发（等于功能没生效）。
        //     冲突时**以 host/路径为准**（与上游 `_mCdnTfRegex` 只看 host/路径一致）。
        if (usable.any { hasMcdnQuery(it) && !isP2pLike(it) }) return urls
        // ③ 只剩真 P2P ⇒ 官方网关兜底
        val first = usable.first()
        PlayerDiag.log("cdn-mcdn", "gateway host=${hostOf(first)} candidates=${usable.size}")
        return listOf(proxyTfUrl(first))
    }

    /** 单地址形态的兜底（语义同 [resolveP2pFallback]） */
    fun resolveP2pUrl(url: String): String = resolveP2pFallback(listOf(url)).first()

}