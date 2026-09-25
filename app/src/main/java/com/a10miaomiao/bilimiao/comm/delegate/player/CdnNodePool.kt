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
 * 我们这边候选节点默认来自 App 自己的 CDN 逻辑（`baseUrl` + `backupUrl`，以及「CDN 竞速」排过序的列表）。
 *
 * ★ 2026-09-25 更新（对齐 lemonteaau/PiliPlus 的四点改进，全部带开关、都可一键回退）：
 *  - [orderFor] 从"bps 降序 + 全局游标轮转（= 平均分配）"改成 **SWRR 平滑加权轮询**
 *    （开关 [smartAssign]，默认开；关掉 = 完全回到旧行为）；
 *  - [Node.bps] 加 **90 秒 TTL**、[noteSuccess] 加 **48KiB 计分门槛**（同上开关）；
 *  - 预留 **三粒度封禁**（[CdnBanList]）：节点坏 / 地址被拒 / 组合被拒分开记账，
 *    由「跨 host 候选合成」开关驱动（**默认关**，关着时这张表完全不生效）；
 *  - 原来的"绝不把签名换 host"承诺**没有被推翻**：换 host 的候选合成写在
 *    `CdnCandidateSynthesizer`，由用户显式打开才可能发生（默认关，且只发给 B站自有 CDN 域名）。
 *
 * ★ 与用户设置的关系（不能破坏的承诺）：
 *  - 「CDN 竞速」开着 → 候选里本来就有多个节点，随便换；
 *  - 「CDN 固定主机」→ 候选列表里**全是同一个 host**，抢跑时"必须换 host"的规则会自然失效，
 *    也就是用户点名了节点就**绝不换**（候选合成也会在 `uposHost` 非空时整体跳过）；
 *  - 「音频不跟随 CDN」→ 音频有自己的候选列表，各走各的。
 */
internal object CdnNodePool {

    /** 最多记多少个媒体文件（按 path）的候选，防止长期挂着无限增长 */
    private const val MAX_ENTRIES = 32

    /** 坏节点最长暂停多久（上游也是 60 秒封顶） */
    private const val BLOCK_MAX_MS = 60_000L
    private const val BLOCK_BASE_MS = 3_000L

    /**
     * 速度分有效期：**90 秒**（2026-09-25，对齐 lemonteaau/PiliPlus `cdn_resolver.dart:92-99`）。
     *
     * 原来 [Node.bps] 一旦写入就**永远**是排序依据 —— 切网络（WiFi→4G）、换视频、
     * 甚至同一个 host 换了台后端机器，都会拿"过期的好成绩"误导调度。
     * 超过这个时间没用过就当没测过（回到"探索"那一档）。
     */
    private const val SCORE_TTL_MS = 90_000L

    /**
     * 计入门槛：单次读够 **48 KiB** 才记速度分（对齐对方 `cdn_resolver.dart:187-192`）。
     *
     * 为什么要门槛：小于 48KB 的读取（尾块、被取消的块、抢跑刚读到 64KB 就被掐的那条）
     * 耗时里握手/首字节占比过大，算出来的 bps 要么极低（冤枉好节点）要么极高（偶然）。
     * 低于门槛**只清失败计数、不记分**（见 [noteSuccess]）。
     */
    private const val SCORE_MIN_BYTES = 48L * 1024L

    /** 慢于"最快节点"这个倍数的节点：几乎不派活（对齐对方"慢于最快 1/12 不派主请求"） */
    private const val SLOW_RATIO = 12L

    /** 被判定为"太慢"的节点保底权重（占最快节点的百分比）—— 留一线生机，不做硬淘汰 */
    private const val MIN_WEIGHT_PERCENT = 5L

    /**
     * **没测过**的节点权重（占最快节点的百分比）= 探索名额。
     *
     * 对方是在"按块分配"的模型里显式留 `count/4` 个探索名额；我们这边一次
     * [orderFor] 只产出"这一块的起步节点"，没有"一批块"可切，所以把探索名额折算成权重：
     * 给未测速节点 1/4 的权重 ≈ 4 个不同节点时它大约每 7 块能轮到 1 次。
     * （对方同一位置的取值是 5%，但它的探索主要靠显式名额；我们只有权重这一个杠杆，
     *  5% 会等于"永远轮不到"，那就永远发现不了新的快节点。）
     */
    private const val UNKNOWN_WEIGHT_PERCENT = 25L

    /** SWRR 的 credit 表最多记多少条（都是 URL，正常一个视频个位数） */
    private const val MAX_CREDIT_ENTRIES = 128

    /**
     * 「智能调度」开关（= 设置里的 `thread_ripper_smart_assign`，**默认开**）。
     *
     * 关掉时本对象**完全回到 2026-09-25 之前的行为**：
     *  - [orderFor] 用"bps 降序 + 全局游标轮转"（平均分配）；
     *  - [speedOf] 不设 90 秒 TTL；
     *  - [noteSuccess] 不设 48KiB 门槛。
     * 这就是这一条的"回退开关"：用户觉得不对，关掉即等于没改过。
     */
    @Volatile
    var smartAssign: Boolean = true

    /**
     * ② 跨 host 候选合成开关。由 [ThreadRipperSettings.refresh] 下发（默认 false）。
     *
     * ★ 为什么放在这里而不是调用方：合成必须**只发生在海外加速（线程撕裂者）这一层内部**。
     *   上一版把这个调用挂到了 `PlayerDelegate2` 的默认 DASH/MP4 分支上，等于改了**默认线路**，
     *   用户实测播放直接废掉（DASH/MP4 都变成 300~400KB/s）—— 所以现在 `register()` 自己吸收，
     *   调用方（PlayerDelegate2）保持与改动前**逐字节一致**。
     */
    @Volatile
    var crossHost: Boolean = false

    private class Node {
        /** 实测速度（字节/秒，滑动平均）；0 = 还没测过（或已过期，见 [speedOf]） */
        @Volatile
        var bps: Long = 0

        /** 上次记分时刻（[SystemClock.uptimeMillis]）；配合 [SCORE_TTL_MS] 判断过期 */
        @Volatile
        var measuredAt: Long = 0

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

    /**
     * **SWRR（smooth weighted round-robin）的 credit 表**：URL → 当前信用值。
     *
     * nginx 的平滑加权轮询：每次选 `credit 最大`的那个，选中后 `credit -= 总权重`，
     * 每轮开始前所有节点 `credit += 自身权重`。效果是"权重高的节点被选得更频繁，
     * 但**不会连着被选**"（对比"按权重排序"那种会把快节点连排 8 次的暴力做法）。
     * 只在 [smartAssign] 开着、且至少有一个节点有实测速度时才使用。
     */
    private val credit = HashMap<String, Long>()

    fun register(primaryUrl: String, candidates: List<String>) {
        val key = Uri.parse(primaryUrl).path ?: return
        // ★ 跨 host 合成（默认关）：**原始候选永远排在最前、顺序不变**，合成出来的追加在后面。
        //   关着时这里与改动前完全一致（连一次列表拷贝都不多做）。
        val expanded = if (crossHost) {
            runCatching { com.a10miaomiao.bilimiao.comm.utils.CdnCandidateSynthesizer.expand(candidates) }
                .getOrDefault(candidates)
        } else {
            candidates
        }
        val all = (listOf(primaryUrl) + expanded)
            .filter { it.isNotBlank() }
            .distinct()
        // ★★ 必须只保留**同一个文件**的候选（2026-09-20 定位到的严重 bug）：
        //    我们生成的 MPD 里同时有视频和音频的 <BaseURL>，早先没按路径过滤 →
        //    视频分块抢跑时可能去请求**音频文件**：
        //      · 大偏移直接 HTTP 416（音频文件小得多）→ 满屏 node-block / chunk-retry；
        //      · 小偏移则把音频字节塞进视频流 → ParserException: Invalid NAL length → 黑屏。
        //    日志证据：`[nodes] 登记候选节点 6 条 / 5 个 host`（视频 3 + 音频 3），
        //    而音频自己的请求反而一条候选都没有。
        val list = all.filter { Uri.parse(it).path == key }
        if (list.isEmpty()) return
        val dropped = all.size - list.size
        synchronized(byPath) { byPath[key] = list }
        RipperDiag.log(
            "nodes",
            "登记候选节点 ${list.size} 条 / ${list.map { hostOf(it) }.distinct().size} 个 host：" +
                list.map { hostOf(it) }.distinct().joinToString(",") +
                if (dropped > 0) "（已丢弃 $dropped 条路径不同的：另一条轨/别的文件）" else ""
        )
    }

    /**
     * 这个请求可以用哪些节点，返回一个**有序**列表（第一个 = 本块该从哪个节点起步）。
     *
     * ★ 2026-09-25 起：返回顺序由 **SWRR 平滑加权轮询**决定（详见 [orderFor] 内部注释），
     *   语义从"平均分配"变成"按实测吞吐加权分配"。**返回的仍然是一个轮换过的完整列表**，
     *   不是单个节点 —— 调用方（[ThreadRipperDataSource.Chunk]）拿 `list[0]` 当主节点、
     *   在 list 里找不同 host 的节点做抢跑、重试时顺着 list 往后换，这些都照旧。
     *
     * 这一步**只影响"谁先上"，不影响"能不能上"**：
     *  - 被暂停的、被判太慢的节点仍然留在列表里（只是排后面）—— 它们还是抢跑/重试的备胎；
     *  - 只有当"所有候选都被封禁"时才会退回未过滤的列表（绝不让候选池变空）。
     *
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
        // ★ 三粒度封禁（只在「跨 host 候选合成」开着时才可能真的封掉东西，见 CdnBanList）
        val allowed = if (CdnBanList.enabled) base.filter { CdnBanList.allows(it) } else base
        val alive = if (allowed.isNotEmpty()) allowed else base
        val usable = alive.filter { !isBlocked(it, now) }
        val pool = if (usable.isNotEmpty()) usable else alive

        if (smartAssign) {
            weightedOrder(pool, now)?.let { return it }
        }
        return rotateBySpeed(pool, now)
    }

    /**
     * 日志/诊断用的**只读**预览：不推进任何游标、不动 SWRR 的 credit。
     *
     * 存在的理由：`open()` 每次请求都会打一行 `[parallel] 候选节点=...`，
     * 它要是走 [orderFor] 就会白白吃掉一个 SWRR 名额（等于每次开拉都偏一次调度）。
     */
    fun preview(uri: Uri): List<String> {
        val key = uri.path ?: return emptyList()
        val list = synchronized(byPath) { byPath[key] } ?: return emptyList()
        val now = SystemClock.uptimeMillis()
        return list.sortedByDescending { speedOf(it, now) }
    }

    /**
     * SWRR 分配：给**这一次调用**选出"下一个该用的节点"，其余按"谁最该下一个被选"排。
     *
     * 为什么不是"直接按 bps 排序然后轮转"（原来的做法）：那等于**平均主义** ——
     * 快节点和慢节点领一样多的块，整段的完成时间被最慢的那条钉死。
     *
     * 为什么不是"按权重排个序"：那会让高分节点包揽前几名（连着派给它），
     * 而 SWRR 的特征恰好是"权重高的**更频繁**，但**不连续**"（平滑）。
     *
     * 权重取值（对齐对方 `RipperAssignments.assign()` 的 `cdn_resolver.dart:234-273`）：
     *  - 有实测速度：[Node.bps]；
     *  - 慢于最快节点 1/[SLOW_RATIO]：压到 [MIN_WEIGHT_PERCENT]%（几乎不派活，但不淘汰）；
     *  - 没测过 / 已过期：[UNKNOWN_WEIGHT_PERCENT]%（探索名额，别把新节点饿死）。
     *
     * 返回 null = "一条都没测过"，交给调用方走老的纯轮转（此时两者本来就等价）。
     */
    private fun weightedOrder(pool: List<String>, now: Long): List<String>? {
        val speeds = pool.associateWith { speedOf(it, now) }
        val top = speeds.values.maxOrNull() ?: 0L
        if (top <= 0L) return null

        val weights = pool.associateWith { url ->
            val bps = speeds[url] ?: 0L
            when {
                bps <= 0L -> (top * UNKNOWN_WEIGHT_PERCENT / 100).coerceAtLeast(1L)
                bps < top / SLOW_RATIO -> (top * MIN_WEIGHT_PERCENT / 100).coerceAtLeast(1L)
                else -> bps
            }
        }
        val total = weights.values.sum().coerceAtLeast(1L)

        synchronized(credit) {
            // credit 表只保留当前候选池里的 URL（path 换了/视频换了就自然清账）
            if (credit.size > MAX_CREDIT_ENTRIES) credit.clear()
            credit.keys.retainAll(pool.toSet())
            // ★ SWRR 的标准两步，缺一不可（2026-09-25 被单测抓过一次）：
            //   ① 每个节点 credit += 自身权重；② 选 credit 最大者，选中后 credit -= 总权重。
            //   只写"比较时用 credit+weight、但把 +weight 丢掉"是**不等价**的 —— 那样
            //   credit 只会被减、永远不被加，几步之后就退化成纯轮转（等于这个开关白开）。
            pool.forEach { credit[it] = (credit[it] ?: 0L) + (weights[it] ?: 0L) }
            val next = pool.maxByOrNull { credit[it] ?: 0L } ?: return null
            credit[next] = (credit[next] ?: 0L) - total
            // 其余按"下一轮谁最该被选"（credit + 自身权重）降序：
            // 抢跑/重试拿到的就是**次优**节点（天然与主节点不同）
            val rest = pool.filter { it != next }
                .sortedByDescending { (credit[it] ?: 0L) + (weights[it] ?: 0L) }
            return listOf(next) + rest
        }
    }

    /** 老行为（[smartAssign] 关掉时，或一条都没测过时）：bps 降序 + 全局游标轮转 = 平均分配 */
    private fun rotateBySpeed(pool: List<String>, now: Long): List<String> {
        val sorted = pool.sortedByDescending { speedOf(it, now) }
        if (sorted.isEmpty()) return sorted
        val start = (cursor.getAndIncrement() % sorted.size + sorted.size) % sorted.size
        return sorted.subList(start, sorted.size) + sorted.subList(0, start)
    }

    /**
     * 节点当前速度分：**过期（> [SCORE_TTL_MS]）当 0 处理**。
     * [smartAssign] 关掉时不做 TTL（= 改之前的行为，bps 永不过期）。
     */
    private fun speedOf(url: String, now: Long): Long {
        val node = nodes[url] ?: return 0L
        val bps = node.bps
        if (bps <= 0L) return 0L
        if (!smartAssign) return bps
        val measuredAt = node.measuredAt
        return if (measuredAt > 0L && now - measuredAt <= SCORE_TTL_MS) bps else 0L
    }

    /**
     * 一次尝试成功后记速度分（滑动平均，权重对齐上游 0.65/0.35）。
     *
     * ★ 2026-09-25：**单次不足 [SCORE_MIN_BYTES] 只清失败账、不记分**（对齐对方 `success()`）。
     *   清失败账这一步是必须保留的：一条 64KB 的尾块读成功也证明"路是通的"，
     *   不该继续背着之前的退避。
     */
    fun noteSuccess(url: String, bytes: Long, elapsedMs: Long) {
        if (bytes <= 0 || elapsedMs <= 0) return
        val node = nodes.getOrPut(url) { Node() }
        node.failures = 0
        node.blockedUntil = 0
        CdnBanList.noteSuccess(url)
        if (smartAssign && bytes < SCORE_MIN_BYTES) return
        val bps = bytes * 1000L / elapsedMs
        val old = node.bps
        node.bps = if (old <= 0) bps else old * 65 / 100 + bps * 35 / 100
        node.measuredAt = SystemClock.uptimeMillis()
    }

    /**
     * 一次尝试真的失败（不是被上层取消）→ 记失败并暂停这个节点，指数退避、60 秒封顶。
     *
     * @param status HTTP 状态码（0 = 连接层失败）。只有 [received] >= 0 时才交给
     *   [CdnBanList] 记账 —— 默认 -1 表示"这次失败不该参与封禁判定"
     *   （例如"节点太慢"是本地速度判断，不是服务端的空响应）。
     * @param received 这条连接实际交付的字节数；> 0 时封禁表直接不记账（"有数据就不算空响应"）。
     */
    fun noteFailure(url: String, reason: String? = null, status: Int = 0, received: Long = -1L) {
        if (received >= 0L) CdnBanList.failure(url, status, received)
        val node = nodes.getOrPut(url) { Node() }
        val failures = node.failures + 1
        node.failures = failures
        node.blockedUntil = SystemClock.uptimeMillis() +
            (BLOCK_BASE_MS shl minOf(failures - 1, 4)).coerceAtMost(BLOCK_MAX_MS)
        RipperDiag.log(
            "node-block",
            "${hostOf(url)} 第 $failures 次失败 → 暂停 ${(node.blockedUntil - SystemClock.uptimeMillis()) / 1000} 秒" +
                if (reason.isNullOrBlank()) "" else "：$reason"
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
