package com.a10miaomiao.bilimiao.comm.delegate.player

import android.net.Uri
import android.os.SystemClock
import com.a10miaomiao.bilimiao.comm.utils.RipperDiag
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「分段并发下载」的**多节点池**（2026-09-19，对齐上游 Bilibili-thread-ripper 的核心机制）。
 *
 * 上游为什么快（它的 README 与 `cdn-resolver.js` / `idm-downloader.js`）：
 *  1. 手里有一批 CDN 节点，**同一个分段同时向两三个节点发请求，谁先回来用谁**（错峰 900ms）；
 *  2. 每个节点记**速度分**（bps 滑动平均）与失败次数，慢的被降权、坏的被暂停；
 *  3. 第一块/启动阶段会拿多个节点一起"抢跑"试路。
 *
 * 我们这边候选节点来自 App 自己的 CDN 逻辑（`baseUrl` + `backupUrl`，以及「CDN 竞速」排过序的列表），
 * 不做上游那种"把签名地址换到别的 host 去问"——那等于把签名发给第三方节点，风险不值得。
 *
 * ★ 与用户设置的关系（不能破坏的承诺）：
 *  - 「CDN 竞速」开着 → 候选里本来就有多个节点，随便换；
 *  - 「CDN 固定主机」→ 候选列表里**全是同一个 host**，抢跑时"必须换 host"的规则会自然失效，
 *    也就是用户点名了节点就**绝不换**；
 *  - 「音频不跟随 CDN」→ 音频有自己的候选列表，各走各的。
 */
internal object CdnNodePool {

    /** 最多记多少个媒体文件（按 path）的候选，防止长期挂着无限增长 */
    private const val MAX_ENTRIES = 32

    /** 坏节点最长暂停多久（上游也是 60 秒封顶） */
    private const val BLOCK_MAX_MS = 60_000L
    private const val BLOCK_BASE_MS = 3_000L

    private class Node {
        /** 实测速度（字节/秒，滑动平均）；0 = 还没测过 */
        @Volatile
        var bps: Long = 0

        @Volatile
        var failures: Int = 0

        @Volatile
        var blockedUntil: Long = 0
    }

    /** path → 这个文件的候选 URL（同一个文件的多个节点，各自带自己的签名） */
    private val byPath = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean =
            size > MAX_ENTRIES
    }

    private val nodes = ConcurrentHashMap<String, Node>()

    /** 轮换游标：让不同分块从不同节点起步（上游 `rangeCursor` 同款思路） */
    private val cursor = AtomicInteger(0)

    fun register(primaryUrl: String, candidates: List<String>) {
        val key = Uri.parse(primaryUrl).path ?: return
        val list = (listOf(primaryUrl) + candidates)
            .filter { it.isNotBlank() }
            .distinct()
        if (list.isEmpty()) return
        synchronized(byPath) { byPath[key] = list }
        RipperDiag.log(
            "nodes",
            "登记候选节点 ${list.size} 条 / ${list.map { hostOf(it) }.distinct().size} 个 host：" +
                list.map { hostOf(it) }.distinct().joinToString(",")
        )
    }

    /**
     * 这个请求可以用哪些节点，顺序 = 有速度分的在前 + 轮换；被暂停的节点排到最后。
     * 返回空列表 = 没登记过（调用方按"只有一个 URL"处理）。
     */
    fun orderFor(uri: Uri): List<String> {
        val key = uri.path ?: return emptyList()
        val list = synchronized(byPath) { byPath[key] } ?: return emptyList()
        if (list.size <= 1) return list
        val now = SystemClock.uptimeMillis()
        // PCDN/MCDN 节点对任意 Range + 多并发支持很差（原来就是命中就整段走单连接）
        val noPcdn = list.filterNot { isPcdn(it) }
        val base = if (noPcdn.isNotEmpty()) noPcdn else list
        val usable = base.filter { !isBlocked(it, now) }
        val pool = if (usable.isNotEmpty()) usable else base
        // 有速度分的排前面（快的先用），没测过的保持 App 那边竞速排出来的顺序
        val sorted = pool.sortedByDescending { nodes[it]?.bps ?: 0L }
        val start = if (sorted.isEmpty()) 0 else (cursor.getAndIncrement() % sorted.size + sorted.size) % sorted.size
        return sorted.subList(start, sorted.size) + sorted.subList(0, start)
    }

    /** 一次尝试成功后记速度分（滑动平均，权重对齐上游 0.65/0.35） */
    fun noteSuccess(url: String, bytes: Long, elapsedMs: Long) {
        if (bytes <= 0 || elapsedMs <= 0) return
        val bps = bytes * 1000L / elapsedMs
        val node = nodes.getOrPut(url) { Node() }
        val old = node.bps
        node.bps = if (old <= 0) bps else old * 65 / 100 + bps * 35 / 100
        node.failures = 0
        node.blockedUntil = 0
    }

    /** 一次尝试真的失败（不是被上层取消）→ 记失败并暂停这个节点，指数退避、60 秒封顶 */
    fun noteFailure(url: String) {
        val node = nodes.getOrPut(url) { Node() }
        val failures = node.failures + 1
        node.failures = failures
        node.blockedUntil = SystemClock.uptimeMillis() +
            (BLOCK_BASE_MS shl minOf(failures - 1, 4)).coerceAtMost(BLOCK_MAX_MS)
        RipperDiag.log(
            "node-block",
            "${hostOf(url)} 第 $failures 次失败 → 暂停 ${(node.blockedUntil - SystemClock.uptimeMillis()) / 1000} 秒"
        )
    }

    private fun isBlocked(url: String, now: Long): Boolean =
        (nodes[url]?.blockedUntil ?: 0L) > now

    private fun isPcdn(url: String): Boolean {
        val host = hostOf(url)
        return host.contains("pcdn", ignoreCase = true) || host.contains("mcdn", ignoreCase = true)
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
}
