package com.a10miaomiao.bilimiao.comm.utils

/**
 * **跨 host 候选合成**（2026-09-25，实验性，**默认关**）。
 *
 * 背景：API 只给 `baseUrl + backupUrl`（通常 2~4 条），节点池里牌太少 —— 抢跑/加权调度
 * 手里没得挑。参考 `lemonteaau/PiliPlus` 的 `cdn_resolver.dart:43-73`：把**同一份
 * "路径 + 查询串"（含签名）原样保留，只把 host 换成内置节点列表里的其它 host**，
 * 于是 1 条原始地址能派生出多条候选。
 *
 * ★★ 为什么默认关（不要擅自改成默认开）：
 *   "upos 的签名能不能跨 host 复用"**没有实测验证** —— 对方代码是按这个假设工作的
 *   （且它自己带三级封禁兜底），但本工程从未真机验证过。B 站若把签名绑定 host，
 *   表现就是大量 403/412。所以：**默认关**，开了也必须配 [com.a10miaomiao.bilimiao.comm.delegate.player.CdnBanList]
 *   那套三粒度封禁 + 原有的"全候选失败退回原始 URL"兜底。
 *
 * ★ 本类只做**纯字符串**处理，刻意不碰 `android.net.Uri` 之外的任何东西：
 *   - 只接受 `https://`（与对方 `supports()` 一致：换 host 后 scheme/端口不变，证书按新 host 校验）；
 *   - 只接受后缀像媒体文件的路径（`.m4s/.mp4/.flv`）—— 直播、本地文件、接口 JSON 一律不接管；
 *   - 只接受白名单内的 B站自有 CDN 域名做 donor，**绝不把带签名的地址发到任意网站**；
 *   - **akamai 既不当作 donor 也不当作合成目标**（对方也是先把 akamai 排除在 donor 之外：
 *     它的签名/回源模型与 upos 系列不同，拿它的签名去套别的 host、或拿别人的签名去套它，都更容易 403）。
 *
 * ★ 与缓存的关系（很重要，别改坏）：`PlayerDelegate2` 的 `CacheKeyFactory` 用的是
 *   `dataSpec.uri.path`。本类**只换 host、不动 path，也不动 query**，所以合成出来的候选
 *   与原始地址共用同一个缓存 key —— 不会出现"换个节点就重新下一遍"。
 */
object CdnCandidateSynthesizer {

    /**
     * 一次最多**新增**几条候选（原始候选永远保留在最前面，这里只限制增量）。
     * 取 8：候选总量通常 2~4 条 → 合成后 10~12 条封顶。候选不是越多越好 ——
     * 每条候选都会进 [com.a10miaomiao.bilimiao.comm.delegate.player.CdnNodePool] 参与抢跑/重试，
     * 太多会稀释调度、也会多打几次无用的请求。
     */
    private const val MAX_SYNTHESIZED = 8

    /** B站自有 CDN 域名白名单（对齐对方 `cdn_resolver.dart:22-24` 的 `_hosts` 正则） */
    private val ALLOWED_CDN_SUFFIXES = listOf(
        "bilivideo.com", "bilivideo.cn", "bilivideo.net",
        "akamaized.net", "szbdyd.com", "hdslb.com", "xycdn.com",
        "mountaintoys.cn", "nexusedgeio.com", "ahdohpiechei.com",
    )

    /** akamai：不当 donor 也不当目标（理由见类注释） */
    private const val AKAMAI_SUFFIX = "akamaized.net"

    /** 只有这几种后缀才认为"这是一条媒体直链"（对齐对方 `supports()`） */
    private val MEDIA_SUFFIXES = listOf(".m4s", ".mp4", ".flv")

    /**
     * 把 [originals] 扩成"原始候选 + 跨 host 合成候选"。
     *
     * 顺序契约：**原始候选永远排在前面、顺序不变** —— 也就是"播放器第一个打开的还是 API 给的那条"，
     * 合成出来的只作为抢跑/重试/故障转移的**额外**牌。这一条是"不会负优化"的关键：
     * 假设不成立（签名真的绑 host）时，退化路径就是"合成候选一律失败 → 回到原始候选"，
     * 而原始候选的位置和行为与不开这个开关时**完全一致**。
     *
     * 返回空/原样返回的情形（一律不改变现有行为）：
     *  - 没有候选；
     *  - 找不到合法 donor（不是 https / 不在白名单 / 是 akamai / 不是媒体路径）；
     *  - 白名单里所有 host 都已经在候选里（去重后没有新增）。
     */
    fun expand(originals: List<String>): List<String> {
        val clean = originals.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return clean

        // donor = 第一条"可以拿来当模板"的原始地址（对齐对方 `donors.take(1)`：一份签名打天下）
        val donor = clean.firstOrNull { isUsableDonor(it) } ?: return clean
        val rest = pathAndQuery(donor) ?: return clean

        val existing = clean.toHashSet()
        val added = ArrayList<String>(MAX_SYNTHESIZED)
        for (host in targetHosts()) {
            if (added.size >= MAX_SYNTHESIZED) break
            val url = "https://$host$rest"
            if (existing.add(url)) added.add(url)
        }
        return if (added.isEmpty()) clean else clean + added
    }

    /**
     * 合成目标 host：**海外线路优先**（这个功能的主场景就是海外），再补大陆线路。
     * 顺序取自 [CdnHosts.list]（保持单一数据源，用户以后加节点这里自动跟上），
     * 只排除：空 host（"默认"）、`backup`（伪 host）、akamai（理由见类注释）。
     */
    private fun targetHosts(): List<String> {
        val hosts = CdnHosts.list
            .map { it.key to it.host }
            .filter { (_, h) -> h.isNotBlank() && h != "backup" && !isAkamai(h) }
        val overseas = hosts.filter { (key, _) -> key in OVERSEAS_KEYS }
        val mainland = hosts.filter { (key, _) -> key !in OVERSEAS_KEYS }
        return (overseas + mainland).map { it.second }.distinct()
    }

    /** [CdnHosts.list] 里属于"海外"的那几个 key（与设置页的注释分组保持一致） */
    private val OVERSEAS_KEYS = setOf("akamai", "aliov", "cosov", "hwov", "hk")

    private fun isUsableDonor(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val path = pathOf(url) ?: return false
        return url.startsWith("https://") &&
            matchesHost(host, ALLOWED_CDN_SUFFIXES) &&
            !isAkamai(host) &&
            MEDIA_SUFFIXES.any { path.endsWith(it, ignoreCase = true) }
    }

    private fun isAkamai(host: String): Boolean = matchesHost(host, listOf(AKAMAI_SUFFIX))

    /** host == 后缀 或 host 以 `.后缀` 结尾（避免 `evil-bilivideo.com` 这种假后缀混进来） */
    private fun matchesHost(host: String, suffixes: List<String>): Boolean =
        suffixes.any { host.equals(it, ignoreCase = true) || host.endsWith(".$it", ignoreCase = true) }

    /**
     * 取 `https://host/path?query` 里的 `/path?query` 部分（**逐字节保留**，含签名）。
     * 返回 null = 这条 URL 结构不对（没有 authority 或没有 path）。
     */
    private fun pathAndQuery(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val start = url.indexOf('/', schemeEnd + 3)
        if (start < 0 || start == url.length - 1) return null
        return url.substring(start)
    }

    /** `https://host:port/path?query` → `host`（不含端口、不含 userInfo） */
    private fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val start = schemeEnd + 3
        val end = url.indexOf('/', start).let { if (it < 0) url.length else it }
        var authority = url.substring(start, end)
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        // IPv6 字面量 [::1]:443 —— 端口分隔符在 ']' 之后
        val bracket = authority.lastIndexOf(']')
        val colon = authority.lastIndexOf(':')
        val host = if (colon > bracket) authority.substring(0, colon) else authority
        return host.ifBlank { null }
    }

    /** `https://host/a/b.m4s?x=1` → `/a/b.m4s`（不含 query，用来判断"是不是媒体直链"） */
    private fun pathOf(url: String): String? {
        val full = pathAndQuery(url) ?: return null
        val q = full.indexOf('?')
        return if (q >= 0) full.substring(0, q) else full
    }
}
