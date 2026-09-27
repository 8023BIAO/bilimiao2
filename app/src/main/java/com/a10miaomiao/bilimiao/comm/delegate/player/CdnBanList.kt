package com.a10miaomiao.bilimiao.comm.delegate.player

import android.os.SystemClock
import com.a10miaomiao.bilimiao.comm.utils.RipperDiag
import java.util.concurrent.ConcurrentHashMap

/**
 * **三粒度封禁表**（2026-09-25）——「跨 host 候选合成」的必要配套，对齐
 * `lemonteaau/PiliPlus` 的 `RipperBanList`（`cdn_resolver.dart:283-335`）。
 *
 * 为什么必须有它：一旦允许"把同一份签名换到别的 host 上问"，失败就不再只有一种含义 ——
 *   - **节点坏**：这个 host 整体不通（连接超时、5xx）；
 *   - **地址被拒**：这份签名/这个文件在**所有**节点上都不被认（4xx）；
 *   - **组合被拒**：签名本身是好的、节点也是好的，只是"这份签名在这个节点上"不被认。
 * 如果只按"完整 URL"退避 3~60 秒（[CdnNodePool.noteFailure] 现在做的事），
 * 403 会被放大成"每个候选都先失败一次"，等于把加速变成负优化。所以这里分三张账：
 *
 *  - `node:<host>`    —— 这个 host 被拉黑；
 *  - `address:<path>` —— 这个文件（path）被拉黑；
 *  - `pair:<host> <path>` —— 这个组合被拉黑。
 *
 * ★ 判定规则（照抄对方 `_judge()` 的语义）：
 *   1. 有数据回来的失败**一律不算空响应**（`received > 0` → return）；
 *   2. 非 4xx 的空响应 → 怪**节点**；
 *   3. 4xx 且"该节点成功过 + 该地址也成功过" → 怪**组合**；
 *   4. 4xx 且"只有该地址成功过" → 怪**节点**（地址是好的，换节点）；
 *   5. 4xx 且"只有该节点成功过" → 怪**地址**（节点是好的，是这份签名不行）；
 *   6. 两边都没成功过 → 怪**节点**（对方没写这一条；我们的取舍是"先怀疑外挂来的节点"，
 *      因为地址是 API 亲自给的、节点是我们自己猜的）；
 *   7. **连续 2 次**空响应才真正封（strikes >= 2）。
 *
 * ★ 与对方的两处**有意的不同**（都是往"更保守"的方向）：
 *   - 对方 `_banned` 看起来是永久的；这里给 **10 分钟 TTL** —— 网络抖动/一次风控不该让
 *     某个节点或某个地址在本次进程生命周期里永远出局；
 *   - 表本身有容量上限（超出就整体清空重来），避免长期播放把内存堆起来。
 *
 * ★ **默认完全不生效**：[enabled] 由「跨 host 候选合成」开关驱动（那一条默认 false）。
 *   关着的时候 [allows] 永远返回 true、[failure]/[noteSuccess] 直接 return，
 *   所以"默认关 = 行为和现在一模一样"是**结构性保证**，不是靠调用方自觉。
 */
internal object CdnBanList {

    /** 连续几次空响应才封（对方同值：2） */
    private const val STRIKES_TO_BAN = 2

    /** 封禁时长：10 分钟后自动解封（对方疑似永久，我们更保守） */
    private const val BAN_MS = 10 * 60 * 1000L

    /** 两张表的容量上限：超了整体清空（宁可忘掉旧账，也不要无限长） */
    private const val MAX_ENTRIES = 512

    /**
     * 是否启用（= 用户开了「跨 host 候选合成」）。
     * 关着时本表的**所有**入口都是 no-op —— 这是"默认关与现状完全一致"的保证点。
     */
    @Volatile
    var enabled: Boolean = false

    /** `host|path|is4xx` → 连续空响应次数 */
    private val emptyReplies = ConcurrentHashMap<String, Int>()

    /** 封禁项 key（`node:` / `address:` / `pair:`）→ 解封时刻 */
    private val bannedUntil = ConcurrentHashMap<String, Long>()

    /** 成功过的 host / 地址（判定规则 3~5 要用） */
    private val successHosts = ConcurrentHashMap.newKeySet<String>()
    private val successAddresses = ConcurrentHashMap.newKeySet<String>()

    /** 这个 URL 现在能不能用（节点 / 地址 / 组合 三者都没被封） */
    fun allows(url: String): Boolean {
        if (!enabled) return true
        val host = hostOf(url)
        val address = addressOf(url)
        val now = SystemClock.uptimeMillis()
        if (isBanned("node:$host", now)) return false
        if (isBanned("address:$address", now)) return false
        if (isBanned("pair:$host $address", now)) return false
        return true
    }

    /** 这个 URL 成功过一次（哪怕是别的签名/别的时间）—— 判定"是节点坏还是地址坏"的依据 */
    fun noteSuccess(url: String) {
        if (!enabled) return
        if (successHosts.size > MAX_ENTRIES || successAddresses.size > MAX_ENTRIES) {
            successHosts.clear()
            successAddresses.clear()
        }
        successHosts.add(hostOf(url))
        successAddresses.add(addressOf(url))
        // 成功即销账：同一 host+地址 之前攒的空响应次数作废
        emptyReplies.keys.removeAll { it.startsWith("${hostOf(url)}|${addressOf(url)}|") }
    }

    /**
     * 一次失败。**只有"空响应"（一个字节都没回来）才记账**，与对方一致。
     *
     * @param status HTTP 状态码；0 = 连接层失败（超时/DNS/TLS），它属于"非 4xx 空响应"。
     * @param received 这条连接实际交付的字节数；> 0 直接 return（有数据就不算空响应）。
     */
    fun failure(url: String, status: Int, received: Long) {
        if (!enabled) return
        if (received > 0L) return
        val host = hostOf(url)
        val address = addressOf(url)
        val is4xx = status in 400..499
        if (emptyReplies.size > MAX_ENTRIES || bannedUntil.size > MAX_ENTRIES) {
            emptyReplies.clear()
            bannedUntil.clear()
            RipperDiag.log("ban", "封禁表超过 $MAX_ENTRIES 条 → 整体清空重来")
            return
        }
        val key = "$host|$address|$is4xx"
        val strikes = (emptyReplies[key] ?: 0) + 1
        emptyReplies[key] = strikes
        if (strikes < STRIKES_TO_BAN) return

        val hostOk = successHosts.contains(host)
        val addrOk = successAddresses.contains(address)
        val banned = when {
            !is4xx -> "node:$host"
            hostOk && addrOk -> "pair:$host $address"
            addrOk -> "node:$host"
            hostOk -> "address:$address"
            else -> "node:$host"
        }
        bannedUntil[banned] = SystemClock.uptimeMillis() + BAN_MS
        emptyReplies.remove(key)
        RipperDiag.log(
            "ban",
            "[$banned] 连续 $STRIKES_TO_BAN 次空响应（status=$status 4xx=$is4xx " +
                "节点成功过=$hostOk 地址成功过=$addrOk）→ 封 ${BAN_MS / 1000} 秒"
        )
    }

    private fun isBanned(key: String, now: Long): Boolean {
        val until = bannedUntil[key] ?: return false
        if (until > now) return true
        bannedUntil.remove(key)   // 过期即解封
        return false
    }

    /** `https://host/a/b.m4s?x=1` → `host` */
    private fun hostOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return ""
        val start = schemeEnd + 3
        val end = url.indexOf('/', start).let { if (it < 0) url.length else it }
        var authority = url.substring(start, end)
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        val bracket = authority.lastIndexOf(']')
        val colon = authority.lastIndexOf(':')
        return (if (colon > bracket) authority.substring(0, colon) else authority).lowercase()
    }

    /**
     * `address`：这条 URL 的 **path**（不含 query）。对方 `_address(url)` 同义。
     * 用 path 而不是完整 URL，是因为签名在 query 里、每次刷新都会变 ——
     * 只有"同一个文件"才该共享同一张账。
     */
    private fun addressOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        val start = if (schemeEnd > 0) url.indexOf('/', schemeEnd + 3) else url.indexOf('/')
        if (start < 0) return url
        val q = url.indexOf('?', start)
        return if (q >= 0) url.substring(start, q) else url.substring(start)
    }
}
