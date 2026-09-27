@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.a10miaomiao.bilimiao.comm.delegate.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.utils.RipperDiag
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 「分段并发下载」（原「线程撕裂者」，类名沿用上游）——**分段多连接下载**（海外加速，实验性功能）。
 *
 * 思路对齐 [Bilibili-thread-ripper](https://github.com/MrTangLuyao/Bilibili-thread-ripper)：
 * B 站的 DASH 播放清单里每个分段的字节范围是已知的，而播放器一次只用一个连接顺序拉；
 * 海外直连大陆 CDN 时单连接很容易被限速，于是"热门视频没事、冷门/4K 卡成 PPT"。
 * 这里把播放器要读的**一个 DataSpec（一个分段）再切成 N 个字节块**，用 N 个连接并发拉，
 * 再按原顺序交付给播放器 —— 不是把同一个文件重复下载 N 遍：每个连接只负责自己那段 Range，
 * 每块的实际返回长度都会校验，长度不对直接判失败。
 *
 * ★ vc105 起补齐了上游的**多节点**部分（原来的实现只在一个节点上并发，海外遇到"节点活着但慢"就干等）：
 *   - 候选节点来自 App 自己的 CDN 逻辑（`baseUrl` + `backupUrl`，竞速排序后的列表），登记进 [CdnNodePool]；
 *   - 每个分块**错峰抢跑**：主节点太久没首字节，就同时向另一个节点（必须不同 host）也发一条，
 *     谁先交出首块谁赢，输的那条立刻掐掉且**不算失败**；
 *   - 节点记速度分（bps 滑动平均）+ 失败退避（3s 起、60s 封顶），坏节点自动靠后；
 *   - 超时：首字节 5.5s / 无进度 4s 掐连接（上游同值）；**不设"单次尝试总时长上限"**
 *     （vc107 教训：番剧走 `[merging]` 后一次请求就是几百 MB，15 秒上限会把正常下载反复掐断 → 黑屏），
 *     改成"最低速度 80KB/s、宽限 10s"——只甩掉活着但基本不动的连接。
 *   用户把 CDN 固定成某个主机时候选全是同一个 host → 抢跑自然退化成"不换节点"。
 *
 * ★ 2026-09-25：按 lemonteaau/PiliPlus 的做法补了四点（**每一项都有独立开关、默认关=回到旧实现**）：
 *   ① 节点调度 SWRR 加权轮询 + 速度分 90 秒 TTL + 单次 48KiB 才计分（[ThreadRipperSettings.smartAssign]，默认开）
 *      → 见 [CdnNodePool.orderFor]；快节点领到更多块，慢节点不再"平均占坑"。
 *   ② 跨 host 候选合成 + 三粒度封禁（[ThreadRipperSettings.crossHostCandidates]，**默认关**）
 *      → 见 `CdnCandidateSynthesizer` / `CdnBanList`；涉及"签名能否跨 host 复用"这个未验证假设，
 *        所以默认关，关着时候选与封禁行为**与改动前逐字节一致**。
 *   ③ 自适应抢跑延迟 400~900ms（[ThreadRipperSettings.adaptiveHedge]，默认开）
 *      → 见 companion 的 [hedgeDelayMs]；上界就是老的固定 900ms，只会更早、不会更晚。
 *   ④ 412/429 风控退让：降一档 + 180 秒冷静期，冷静期内再次触发才走熔断（[ThreadRipperSettings.pushback]，默认开）
 *      → 见 [ThreadRipperSettings.notePushback] / [ThreadRipperSettings.effectiveCap]；
 *        替掉原来"3 次分块失败 → 直接熔断 10 分钟"的那记重锤（那条路径本身仍然保留）。
 *
 * ★ 与 CDN 选择的关系：**完全独立、互不干扰**。
 *   本工程已有的「CDN 竞速 / CDN 固定主机 / 音频不跟随 CDN」照旧生效 ——
 *   它们决定"用哪个 URL"，本层只决定"这个 URL 上的字节怎么并发拉"。
 *   开启本功能**不需要**改任何 CDN 设置（用户怎么选就怎么选）。
 *
 * ★ 安全兜底（这一层绝不能把视频搞成放不出来）：
 *   - 只在 `length` 已知、>= [MIN_PARALLEL_BYTES]、GET、非 gzip 时才并发；
 *   - 其余情况（本地文件、未知长度、POST、范围太小）一律**原样透传**单连接；
 *   - 并发模式在**还没交付过任何字节**之前失败（服务端不支持 Range、403、超时…），
 *     自动退回单连接重开同一请求，用户无感；
 *   - 任何一次并发失败都会打开**熔断器**：10 分钟内所有请求直接走单连接，
 *     避免"每个分段都先失败一次再回退"的反复卡顿；
 *   - 已经交付过字节后再失败，只能抛 IOException 让播放器自己重试这一段（物理上无法回退重放）。
 */
internal object ThreadRipperSettings {

    /** 总开关（默认关） */
    @Volatile
    var enabled: Boolean = false

    /**
     * **并发连接数**（用户唯一要设的档，默认 4）。
     *  0 = 不限（= 本机核数），1..max = 最多用几条连接。
     *  ★ 2026-09-19：原来的「自动并发」开关已删除 —— 上游 Bilibili-thread-ripper 就只有
     *    这一个档位（`concurrency`），我们那个开关和它语义重叠、只会互相打架。
     */
    @Volatile
    var threads: Int = 4

    /** 本机"最大并发连接数" = 处理器核数（设置页滑块的上限就是它），至少 1 */
    val maxThreads: Int
        get() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    // ─────────────── 2026-09-25 新增的四个开关（全部可一键回退到改之前的行为） ───────────────
    // 设计原则：开关关掉时，**代码路径回到旧实现**，而不是"新实现但参数保守" ——
    // 这样"默认值取不会变差的那一侧"是可验证的，不是靠承诺。

    /**
     * ① 节点调度：SWRR 平滑加权轮询 + 速度分 90 秒 TTL + 单次 48KiB 才计分。
     * **默认开**。关掉 = 完全回到"bps 降序 + 全局游标轮转（平均分配）、速度分永不过期、无计分门槛"。
     */
    @Volatile
    var smartAssign: Boolean = true

    /**
     * ② 跨 host 候选合成 + 三粒度封禁（`CdnCandidateSynthesizer` / `CdnBanList`）。
     * **默认关** —— "签名能不能跨 host 复用"**没有实测验证**，见 `CdnCandidateSynthesizer` 的类注释。
     * 关着时：候选列表与封禁表现在与改动前**逐字节一致**。
     */
    @Volatile
    var crossHostCandidates: Boolean = false

    /**
     * ③ 自适应抢跑延迟（400~900ms，按实测首块耗时调整）。
     * **默认开**。关掉 = 恢复固定的 900ms。
     */
    @Volatile
    var adaptiveHedge: Boolean = true

    /**
     * ④ 412/429 风控退让（降一档 + 180 秒冷静期，冷静期内再次触发才走原来的熔断）。
     * **默认开**。关掉 = 恢复"直接计入 3 次分块失败 → 10 分钟熔断"。
     */
    @Volatile
    var pushback: Boolean = true

    /** 风控退让的冷静期：这么长时间内保持降档、并且"再触发一次就熔断"（对齐对方 180 秒） */
    const val PUSHBACK_COOLDOWN_SECONDS = 180L

    private const val PUSHBACK_COOLDOWN_MS = PUSHBACK_COOLDOWN_SECONDS * 1000L

    /** 冷静期里累计降了几档（一档 = 一条连接） */
    @Volatile
    private var pushbackSteps: Int = 0

    @Volatile
    private var pushbackUntilMs: Long = 0L

    /**
     * 遇到 HTTP 412/429 时调用：**降一档 + 进入 180 秒冷静期**。
     *
     * @return true = 这次是"冷静期内再次触发"，调用方应走**原来的熔断**（10 分钟全单连接）。
     *
     * 为什么要拆成两步（对齐 lemonteaau/PiliPlus `auto_concurrency.dart:128-131` 的 `pushback()`）：
     * B站的 412/429 很多时候是**瞬时风控**，原来的实现把它当成"分块坏了"，
     * 3 次就熔断 10 分钟全走单连接 —— 对用户来说是"断崖式降速"。
     * 现在的梯度是：第一次限流 → 少一条连接、冷静 180 秒；真的被盯上了（冷静期内又来）才熔断。
     */
    fun notePushback(): Boolean {
        synchronized(this) {
            val now = SystemClock.uptimeMillis()
            val inCooldown = now < pushbackUntilMs
            pushbackUntilMs = now + PUSHBACK_COOLDOWN_MS
            if (inCooldown) return true
            pushbackSteps++
            return false
        }
    }

    /**
     * 冷静期内的并发上限：**降一档 = 少一条连接**。
     *
     * "档"就是用户那根滑块的一格（这也是用户明确要求的：用现有滑块值当上限，
     * 不引入对方 8/12/16/24/32 那套自动升降）。下限永远是 1。
     *
     * 冷静期一过自动回到用户设的值 —— 这就是"180 秒内不再升档"：
     * 我们本来就没有自动升档机制，这里保证的是**降下去的值不会提前弹回来**。
     */
    fun effectiveCap(configured: Int): Int {
        synchronized(this) {
            val now = SystemClock.uptimeMillis()
            if (now >= pushbackUntilMs) {
                pushbackSteps = 0
                return configured
            }
            return (configured - pushbackSteps).coerceAtLeast(1)
        }
    }

    /**
     * 每条连接**最少**分到的字节数：**64KB**，与上游 Bilibili-thread-ripper 的
     * `minChunkBytes: 64 * 1024`（`range-core.js` / `splitRange`）完全一致。
     *
     * 上游的切法就是"把这次请求的字节区间**平均分给 N 条连接**"，唯一的下限是它。
     * ★ 手机上取 **256KB**（上游 64KB）：实测音频轨的请求只有 130KB 左右，
     *   按 64KB 切会开 3 条连接拉 130KB —— 三次握手的开销远大于收益（用户："负优化"）。
     *   256KB/连接意味着：130KB → 1 条；1MB → 4 条；8MB → 8 条（吃满本机上限）。
     * ★ 我 2026-09-19 曾自己发明过"每连接 512KB / 2MB"的规则，那跟上游无关，
     *   已于 vc104 删除：并发度应该由用户设的连接数决定，而不是由我拍一个 KB 数。
     */
    private const val MIN_CHUNK_BYTES = 256L * 1024L

    /**
     * 从**设置内存快照**刷一份值（主线程调用，不阻塞）。
     *
     * 以前这里是 `runBlocking` + 300ms 超时读 DataStore，而它被 `getMediaSource()`
     * （GSY 从主线程调）调到 —— 每次开播/换清晰度都要赌一次"设置读得够不够快"。
     * 现在只读 [SettingPreferences.cachedPreferencesOrNull]（进程启动时后台维护、设置一改就更新）：
     * 快照还没就绪就沿用上一次的值（默认 = 关闭），和原来的超时兜底结果一致，但不卡主线程。
     */
    fun refresh(context: Context) {
        val prefs = SettingPreferences.cachedPreferencesOrNull() ?: return
        // ThreadRipperAutoThreads 这个键保留在 DataStore 里但已不再使用（旧版本的开关）
        enabled = prefs[SettingPreferences.ThreadRipperEnable] ?: false
        threads = (prefs[SettingPreferences.ThreadRipperThreads] ?: 4).coerceIn(0, maxThreads)
        // ── 四个新开关（默认值 = "不会变差"的那一侧）──
        smartAssign = prefs[SettingPreferences.ThreadRipperSmartAssign] ?: true
        crossHostCandidates = prefs[SettingPreferences.ThreadRipperCrossHost] ?: false
        adaptiveHedge = prefs[SettingPreferences.ThreadRipperAdaptiveHedge] ?: true
        pushback = prefs[SettingPreferences.ThreadRipperPushback] ?: true
        // 把开关下发给两个纯逻辑对象：它们自己不做设置读取，只认这两个标志位。
        // CdnBanList 默认就是 false —— 也就是"跨 host 合成没开，封禁表绝不生效"。
        CdnNodePool.smartAssign = smartAssign
        CdnNodePool.crossHost = crossHostCandidates
        CdnBanList.enabled = crossHostCandidates
    }

    /**
     * 决定这次请求开几条连接（1 = 不并发）。
     *
     * **完全照上游 Bilibili-thread-ripper 的模型**（`range-core.js: splitRange`）：
     *   count = min(用户设的连接数, ceil(本次字节数 / 64KB))
     * 也就是"把这次的区间平均分给 N 条连接"，只有每份不足 64KB 时才自动少开。
     * 「不限」= 上限取本机核数。
     *
     * ★ 2026-09-25：在此基础上叠加**风控退让**（[effectiveCap]）—— 收到 412/429 后的
     *   180 秒冷静期内，这里的上限会比用户设的少一档（下限 1）。
     *   412/429 退让开关关掉时，这一行等于没写（[effectiveCap] 不被调用）。
     */
    fun resolveThreads(chunkBytes: Long): Int {
        if (!enabled) return 1
        val max = maxThreads
        val configured = threads
        var cap = if (configured <= 0) max else configured.coerceIn(1, max)
        if (pushback) cap = effectiveCap(cap)
        val bySize = ((chunkBytes + MIN_CHUNK_BYTES - 1) / MIN_CHUNK_BYTES).toInt()
        return bySize.coerceIn(1, cap)
    }
}

/** 把上层（CacheDataSource / 播放器）的每个请求交给 [ThreadRipperDataSource] */
internal class ThreadRipperDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = ThreadRipperDataSource(upstreamFactory)
}

/**
 * 并发分段下载的 [DataSource]。`read()` 严格按字节顺序交付，
 * 上层（包括 CacheDataSource 的落盘）看到的仍是一条普通的顺序流。
 */
internal class ThreadRipperDataSource(
    private val upstreamFactory: DataSource.Factory,
) : DataSource {

    private companion object {
        /** 单块缓冲：64KB（太小则系统调用占比高，太大则首块延迟变高） */
        const val BUFFER_SIZE = 64 * 1024

        /** 每个分块最多预读几块（4 × 64KB = 256KB/线程；线程数有上限，内存占用可控） */
        const val QUEUE_CAPACITY = 4

        /**
         * 小于这个长度不值得并发（请求本身就没多大，多开连接反而更慢）。
         * 取 256KB：和"每连接至少 256KB"一致 —— 比这更小的请求单连接就够了，
         * 开多条连接只会把一次小请求拆成几次握手（实测音频轨请求就在 130KB 量级）。
         */
        const val MIN_PARALLEL_BYTES = 256L * 1024L

        /** 线程数硬上限（核数再多也不超过它：连接、校验、重组本身也有开销） */
        const val MAX_WORKERS = 16

        /** 等待分块数据的单次轮询时长：用来周期性检查取消/完成/出错 */
        const val POLL_MS = 200L

        /**
         * 共享线程池：分段请求一个接一个来（每个分段 open 一次），不能每次新建/销毁线程。
         * 空闲 30 秒自动回收。
         *
         * 池容量取 **单流上限 × 2**：本 App 的 `[merging]` 是音频/视频两条独立 DataSource
         * **同时**在拉的，池只开"单流上限"那么多的话，后到的音频分块会排在视频分块后面 ——
         * 音频断流比视频卡更致命。×2 刚好覆盖"音+视频各跑满"。
         *
         * 注意必须是固定池（core = max）：若写成 core=0/max=N + 无界队列，
         * `ThreadPoolExecutor` 只在队列满时才扩容，无界队列下永远只跑 1 个线程（并发直接失效）。
         */
        private val executor: ThreadPoolExecutor by lazy {
            val size = (ThreadRipperSettings.maxThreads * 2).coerceAtLeast(2)
            ThreadPoolExecutor(
                size,
                size,
                30L, TimeUnit.SECONDS,
                LinkedBlockingQueue(),
                ThreadFactory { r -> Thread(r, "thread-ripper").apply { isDaemon = true } },
            ).apply { allowCoreThreadTimeOut(true) }
        }

        /**
         * 「并行模式熔断器」。
         *
         * 有些 CDN 节点根本不认 `Range`（返回 200 全量）或对并发连接限流：
         * 那种情况下如果每个分段都"先并发失败一次、再回退单连接"，播放会一直卡顿重试。
         * 所以只要有一次并发失败，就**全局**停用并发 10 分钟（之后自动再试），
         * 期间所有请求直接走单连接 —— 宁可没加速，也不能把视频搞卡。
         */
        @Volatile
        private var breakerUntilMs = 0L

        /**
         * 连续失败计数：每成功一块清零，累计 3 次分块失败就把并发整体关掉 10 分钟。
         * （上层取消导致的 InterruptedException 不算失败，见 [Chunk.isCancellation]）
         *
         * 为什么需要它（2026-09-19 实机反馈）：国内冷门视频常被分到 PCDN/MCDN 节点，
         * 这类节点对"任意 Range + 多并发"支持很差。以前只有在"一个字节都没交付"时才熔断，
         * 于是"第 1 块成功、第 N 块失败"的视频会**每段都失败一次**，播放器反复重试整段 ——
         * 用户看到的就是"开了反而死活加载不出来，关掉秒播"。
         */
        private val failureStreak = java.util.concurrent.atomic.AtomicInteger(0)

        fun noteChunkFailure() {
            if (failureStreak.incrementAndGet() >= 3) tripBreaker()
        }

        fun noteChunkSuccess() {
            failureStreak.set(0)
        }

        fun parallelAllowed(): Boolean = SystemClock.uptimeMillis() >= breakerUntilMs

        fun tripBreaker() {
            breakerUntilMs = SystemClock.uptimeMillis() + 10 * 60 * 1000L
            RipperDiag.log("breaker", "并发拉取失败 → 10 分钟内全部回退单连接（避免每段都先失败一次）")
        }

        /**
         * 单个分块的**最大尝试次数**（对齐 N_m3u8DL-RE 的 `--download-retry-count` 默认 3）：
         * 一次失败不再让整段重来，只重下这一块**没下完的部分**（续传式重试）。
         */
        const val MAX_ATTEMPTS = 3

        /**
         * **无进度超时**：单块这么久一个字节都没新增，就掐掉这条连接换一条重试。
         * vc105 从 20 秒改成 **4 秒**，与上游 `range-core.js` 的 `stallTimeoutMs: 4000` 一致 ——
         * 海外线路最常见的就是"连接还活着但彻底不动了"，20 秒够把缓冲耗光，4 秒才是对的。
         */
        const val STALL_TIMEOUT_MS = 4_000L
        const val STALL_CHECK_PERIOD_MS = 2_000L

        /**
         * **首字节超时**：连接打开后这么久还没吐出第一个数据块，就当作这条路不行。
         * 与上游 `firstByteTimeoutMs: 5500` 一致。
         */
        const val FIRST_BYTE_TIMEOUT_MS = 5_500L

        /**
         * **抢跑错峰**：主节点这么久还没交出首字节，就同时向第二个节点也发一条请求，
         * 谁先回来用谁，输的那条立刻掐掉（**不算失败**）。
         * 与上游 `hedgeDelayMs: 900` 一致 —— 这是它"哪个下载好了就先用哪个"的核心。
         *
         * ★ 2026-09-25：这个值现在是**上限**，实际值由 [hedgeDelayMs] 按实测首块耗时自适应
         *   （范围 [HEDGE_DELAY_MIN_MS]~[HEDGE_DELAY_MS]）。它本身仍被 [awaitWinner] 的
         *   等待上限引用 —— 那里用最大值，等于"判决窗口只会更宽，不会更窄"。
         */
        const val HEDGE_DELAY_MS = 900L

        /**
         * 自适应抢跑延迟的**下限：400ms**（**不用**上游的 250ms）。
         *
         * 上游 `range_proxy.dart:32-33` 是 `(pieceMs * 1.5).clamp(250, 900)`。我们不下探到 250：
         * 250ms 在移动网络下几乎等于"每个块都把两个节点同时打一遍"，请求量翻倍更容易触发
         * B站风控（412/429）——用户明确要求"不要用 250ms 下限，太激进"。
         * 400ms 仍然明显早于原来的固定 900ms，收益保留、风险减半。
         */
        const val HEDGE_DELAY_MIN_MS = 400L

        /**
         * 实测**首块耗时**的 EMA（毫秒，0 = 还没测到）。只由"抢跑赢家"贡献 ——
         * 输的那条本来就会被掐，它的耗时里混着错峰等待，不能代表线路速度。
         *
         * 为什么用"首块耗时"当 pieceMs：它就是"这个节点把 64KB 吐出来要多久"，
         * 抢跑延迟比它略长一点（×1.5）才叫"比正常情况慢，开始抢跑"。
         */
        @Volatile
        private var pieceMsEma = 0L

        /**
         * 本轮抢跑该错峰多久：`首块耗时 × 1.5`，**硬夹在 400~900ms**。
         *
         * 不会负优化的三个理由：
         *  1. 上界就是原来的固定值 900ms —— 自适应**只会让它更早**，永远不会更晚；
         *  2. 还没测到（进程刚起 / 第一个块）→ 直接返回 900，与以前一模一样；
         *  3. 开关 [ThreadRipperSettings.adaptiveHedge] 关掉 → 也直接返回 900。
         */
        fun hedgeDelayMs(): Long {
            if (!ThreadRipperSettings.adaptiveHedge) return HEDGE_DELAY_MS
            val pieceMs = pieceMsEma
            if (pieceMs <= 0L) return HEDGE_DELAY_MS
            return (pieceMs * 3 / 2).coerceIn(HEDGE_DELAY_MIN_MS, HEDGE_DELAY_MS)
        }

        /** 记一次"赢家拿到首块用了多久"（EMA 0.65/0.35，与速度分同权重） */
        fun notePieceTime(ms: Long) {
            if (ms <= 0L) return
            val old = pieceMsEma
            pieceMsEma = if (old <= 0L) ms else old * 65 / 100 + ms * 35 / 100
        }

        /**
         * **单次尝试的最低速度**：低于它、且还有别的节点可选 → 认定这条连接"活着但基本不动"，换节点续传。
         *
         * ★ vc107 修正：vc105 抄了上游的"单次尝试 15 秒上限"（`attemptTimeoutMs`），
         *   结果在番剧改走 `[merging]` 之后**成了黑屏元凶** —— 播放器现在一次请求就是几百 MB
         *   （日志实测 1,463,152KB / 1,507,360KB），拆成 8 块后每块几十 MB，
         *   15 秒**根本不可能下完** → 每块刚到 15 秒就被掐、重试、再掐 → 死循环 + 一直请求 + 黑屏。
         *   上游能用这个上限，是因为它的"一块"是内存里的小分片；我们的块是流式续传的大区间，不适用。
         *   现在改成"看速度不看时长"：慢到没意义才换，慢但一直在出数据就让它拉。
         */
        const val MIN_ATTEMPT_BPS = 80_000L      // 80KB/s：8 条连接合计约 640KB/s，再低就纯属拖后腿

        /** 速度判定的宽限期：刚连上那几秒不算，免得把正常的慢启动掐掉 */
        const val BPS_GRACE_MS = 10_000L

        /** 首块等待的宽限：首字节超时 + 抢跑错峰之后，再给这么多时间让数据真的到达 */
        const val FIRST_BLOCK_SLACK_MS = 3_000L

        /** 抢跑赢家等"判决"的最长时间（实际几毫秒，调用方每 15ms 就判决一次） */
        const val DECISION_WAIT_MS = 1_500L

        /** 正在下载的分块（卡死巡检用） */
        private val activeChunks: MutableSet<ThreadRipperDataSource.Chunk> =
            ConcurrentHashMap.newKeySet()

        @Volatile
        private var watchdogStarted = false

        fun registerChunk(chunk: ThreadRipperDataSource.Chunk) {
            activeChunks.add(chunk)
            ensureWatchdog()
        }

        fun unregisterChunk(chunk: ThreadRipperDataSource.Chunk) {
            activeChunks.remove(chunk)
        }

        /** 一个极轻量的巡检线程（守护线程，只做"看时间戳 + 掐连接"） */
        private fun ensureWatchdog() {
            if (watchdogStarted) return
            synchronized(this) {
                if (watchdogStarted) return
                watchdogStarted = true
                runCatching {
                    Executors.newSingleThreadScheduledExecutor { r ->
                        Thread(r, "thread-ripper-watchdog").apply { isDaemon = true }
                    }.scheduleWithFixedDelay({
                        val now = SystemClock.uptimeMillis()
                        activeChunks.forEach { chunk -> runCatching { chunk.checkStall(now) } }
                    }, STALL_CHECK_PERIOD_MS, STALL_CHECK_PERIOD_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private val transferListeners = ArrayList<TransferListener>(2)

    private var dataSpec: DataSpec? = null
    private var uri: Uri? = null

    /** 单连接（透传）模式的数据源；非 null 时 [read] 直接转发给它 */
    private var single: DataSource? = null

    /** 并行模式的分块与任务 */
    private var chunks: List<Chunk> = emptyList()
    private var futures: MutableList<Future<*>> = mutableListOf()
    private var nextChunk = 0

    /** 当前正在交付的那块缓冲（读完再取下一块，顺序保证就在这里） */
    private var pending: ByteArray? = null
    private var pendingOffset = 0

    private var bytesRead = 0L

    @Volatile
    private var closed = false

    // ───────────────────────────── open ─────────────────────────────

    override fun open(dataSpec: DataSpec): Long {
        closeInternal()
        closed = false
        this.dataSpec = dataSpec
        this.uri = dataSpec.uri
        this.bytesRead = 0
        this.pending = null
        this.pendingOffset = 0

        val length = dataSpec.length
        val host = dataSpec.uri.host.orEmpty()
        // PCDN / MCDN 节点（B 站冷门视频常见）对任意 Range + 多并发支持很差：
        // 命中就直接单连接，别每次都先失败一遍。
        val pcdnHost = host.contains("pcdn", ignoreCase = true) ||
            host.contains("mcdn", ignoreCase = true)
        if (pcdnHost && ThreadRipperSettings.enabled) {
            RipperDiag.log("skip", "命中 PCDN/MCDN 节点（$host）→ 这个视频走单连接")
        }
        val splittable = length != C.LENGTH_UNSET.toLong() &&
            length >= MIN_PARALLEL_BYTES &&
            dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET &&
            dataSpec.httpBody == null &&
            !dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP) &&
            !pcdnHost &&
            ThreadRipperSettings.enabled &&
            parallelAllowed()

        val threads = if (splittable) {
            ThreadRipperSettings.resolveThreads(length).coerceIn(1, MAX_WORKERS)
        } else {
            1
        }

        if (threads <= 1) return openSingle(dataSpec)

        // ★ 这里只是打日志，必须用 preview() 而不是 orderFor()：
        //   orderFor 会推进 SWRR 的分配名额，用它打日志等于每次请求都白白偏一次调度。
        val pool = CdnNodePool.preview(dataSpec.uri)
        RipperDiag.log(
            "parallel",
            "并发拉取：${threads} 连接 / ${length / 1024}KB @${dataSpec.position / 1024}KB" +
                "（${dataSpec.uri.lastPathSegment ?: ""}）" +
                if (pool.size > 1) {
                    " 候选节点=${pool.map { hostOf(it) }.distinct().joinToString(",")}"
                } else {
                    ""
                }
        )

        return try {
            openParallel(dataSpec, length, threads)
        } catch (e: Exception) {
            // 起不来（多数是服务端不支持 Range）→ 退回单连接，绝不因此放不出视频
            tripBreaker()
            runCatching { closeInternal() }
            closed = false
            openSingle(dataSpec)
        }
    }

    private fun openSingle(dataSpec: DataSpec): Long {
        val source = upstreamFactory.createDataSource()
        val opened = source.open(dataSpec)
        single = source
        notifyStart()
        return opened
    }

    private fun openParallel(dataSpec: DataSpec, length: Long, threads: Int): Long {
        val start = dataSpec.position
        val chunkSize = (length + threads - 1) / threads
        val list = ArrayList<Chunk>(threads)
        var offset = 0L
        while (offset < length) {
            val size = minOf(chunkSize, length - offset)
            list.add(Chunk(dataSpec, start + offset, size))
            offset += size
        }
        chunks = list
        nextChunk = 0
        list.forEach { chunk -> futures.add(executor.submit(chunk)) }
        notifyStart()
        return length
    }

    // ───────────────────────────── read ─────────────────────────────

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        single?.let { return it.read(buffer, offset, length) }

        while (true) {
            if (closed) throw InterruptedIOException("ThreadRipperDataSource 已关闭")
            val chunk = chunks.getOrNull(nextChunk) ?: return C.RESULT_END_OF_INPUT

            // 1) 先把当前缓冲剩下的字节交付掉（顺序保证就在这一步）
            val buf = pending
            if (buf != null && pendingOffset < buf.size) {
                val n = minOf(length, buf.size - pendingOffset)
                System.arraycopy(buf, pendingOffset, buffer, offset, n)
                pendingOffset += n
                bytesRead += n
                notifyBytes(n)
                return n
            }
            pending = null
            pendingOffset = 0

            // 2) 取下一块缓冲
            val next = chunk.queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
            if (next != null) {
                pending = next
                pendingOffset = 0
                continue
            }

            // 3) 没数据：看是"还在下"还是"出错了 / 这一块下完了"
            val error = chunk.error
            if (error != null) {
                if (bytesRead == 0L) {
                    // ★ 一个字节都还没交付 → 悄悄退回单连接（服务端不认 Range 的典型表现）
                    tripBreaker()
                    fallbackToSingle()
                    return read(buffer, offset, length)
                }
                // ★ 已经交付过字节 → **就地无缝降级**：掐掉所有并发分块，用一条单连接
                //   从"已经交付到的位置"继续拉剩下的。
                //   以前这里直接抛 IOException：播放器只能把整段重来，而重试又落在并发模式上
                //   再失败一次 —— 用户看到的就是"开了反而加载不出来"。现在用户完全无感。
                RipperDiag.log(
                    "degrade",
                    "分块失败（已交付 ${bytesRead / 1024}KB）→ 该分段就地降级为单连接续传"
                )
                failureStreak.incrementAndGet()
                fallbackToSingleFrom(bytesRead)
                return read(buffer, offset, length)
            }
            if (chunk.done) {
                nextChunk++
                if (nextChunk >= chunks.size) return C.RESULT_END_OF_INPUT
                continue
            }
            // 否则是 worker 还在跑（或还没被线程池调度到），继续等
        }
    }

    /**
     * 中途降级：从已经交付给上层的字节数之后，用**一条单连接**继续拉（不重下已交付的部分）。
     * 位置算的是"原始请求的 position + 已交付字节"，长度是原请求剩余部分。
     */
    private fun fallbackToSingleFrom(delivered: Long) {
        val spec = dataSpec ?: throw IOException("没有可以回退的 DataSpec")
        cancelWorkers()
        val remaining = if (spec.length == C.LENGTH_UNSET.toLong()) {
            C.LENGTH_UNSET.toLong()
        } else {
            (spec.length - delivered).coerceAtLeast(0L)
        }
        val resumeSpec = spec.buildUpon()
            .setPosition(spec.position + delivered)
            .setLength(remaining)
            .build()
        val source = upstreamFactory.createDataSource()
        source.open(resumeSpec)
        single = source
    }

    /** 并行模式未交付任何字节就失败时的兜底：原样重开一个单连接请求 */
    private fun fallbackToSingle() {
        RipperDiag.log("fallback", "并发模式一个字节都没交付就失败 → 退回单连接重开同一请求")
        val spec = dataSpec ?: throw IOException("没有可以回退的 DataSpec")
        cancelWorkers()
        val source = upstreamFactory.createDataSource()
        val opened = source.open(spec)
        single = source
        // 不重复发 onTransferStart：这一段传输还在继续（回退是同一段请求的中途切换），
        // 多发一次 start 会让带宽统计/诊断把一次传输记成两次
        if (opened == 0L) throw IOException("回退单连接后长度为 0")
    }

    // ───────────────────────────── 收尾 ─────────────────────────────

    override fun getUri(): Uri? = uri

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
    }

    override fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        closed = true
        runCatching { single?.close() }
        single = null
        cancelWorkers()
        pending = null
        pendingOffset = 0
        notifyEnd()
        dataSpec = null
    }

    private fun cancelWorkers() {
        futures.forEach { runCatching { it.cancel(true) } }
        futures = mutableListOf()
        chunks.forEach { runCatching { it.cancel() } }
        chunks = emptyList()
        nextChunk = 0
    }

    private fun notifyStart() {
        val spec = dataSpec ?: return
        transferListeners.forEach { runCatching { it.onTransferStart(this, spec, true) } }
    }

    private fun notifyBytes(count: Int) {
        val spec = dataSpec ?: return
        transferListeners.forEach { runCatching { it.onBytesTransferred(this, spec, true, count) } }
    }

    private fun notifyEnd() {
        val spec = dataSpec ?: return
        transferListeners.forEach { runCatching { it.onTransferEnd(this, spec, true) } }
    }

    // ───────────────────────────── 单个分块 ─────────────────────────────

    /**
     * 一个字节块：在自己的连接上把 `[start, start+length)` 拉下来，
     * 以 64KB 为单位塞进有界队列（队列满就阻塞 —— 这是"预读"的边界，也是内存闸门；
     * 上层不读了就靠 [cancel] 打断 `put`）。
     *
     * ★ 失败处理（2026-09-19 依据 N_m3u8DL-RE / burst-download 的做法重做）：
     *  - **分块级重试**：单块最多试 [MAX_ATTEMPTS] 次，只重下这一块**没下完的部分**
     *    （[pushed] 记录已确认入队的字节数，重试时从 `start + pushed` 续传，不重下已有的）；
     *  - **无进度超时**：卡死巡检发现 20 秒没有新字节（且队列没满 = 不是被上层拖住）
     *    就掐掉这条连接，让 read() 抛错走上面的重试 —— 相当于"换一条 CDN 连接再试"；
     *  - 只有**所有尝试都失败**才把错误交给上层（此时若一个字节都没交付，read() 会
     *    悄悄回退单连接；已经交付过就只能让播放器重试这一段了）。
     */
    /**
     * 一个字节块：把 `[start, start+length)` 拉下来，以 64KB 为单位塞进有界队列
     * （队列满就阻塞 —— 这是"预读"的边界，也是内存闸门；上层不读了就靠 [cancel] 打断 `put`）。
     *
     * ★ vc105 起：**多节点抢跑**（对齐上游 Bilibili-thread-ripper 的核心机制）
     *  - 本块先从节点池里的某个节点起步（哪个节点由 SWRR 按实测吞吐决定，见①）；
     *    若 [hedgeDelayMs]（400~900ms 自适应，见③）内没交出第一个数据块，
     *    就**同时**向第二个节点（必须是不同 host）也发一条 —— 谁先交出首块谁赢，
     *    输的那条立刻掐掉，而且**不算失败**（这正是抢跑的意义：慢的不该被记账）；
     *  - 赢家继续把这一块剩下的读完，所以稳定后每个块仍然只有一条活跃连接；
     *  - 首字节 [FIRST_BYTE_TIMEOUT_MS] / 无进度 [STALL_TIMEOUT_MS] / 单次尝试
     *    "活着但基本不动"（80KB/s 以下）才换节点；
     *  - 失败会记进 [CdnNodePool]，坏节点被暂停（指数退避、60 秒封顶），重试时自动换节点；
     *    HTTP 412/429 例外：走④的"降一档 + 冷静期"，不喂给熔断器的 streak；
     *  - **分块级重试**：只重下这一块没下完的部分（`pushed` 记录已确认入队的字节数）。
     *
     * 单节点（没登记过候选 / 用户固定了主机 / PCDN 节点）时行为与以前一样：
     * 一条连接、慢但有进度就不打断，安全网（熔断 + 就地降级单连接）全部保留。
     */
    private inner class Chunk(
        private val spec: DataSpec,
        private val start: Long,
        private val length: Long,
    ) : Runnable {

        val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)

        @Volatile
        var error: Throwable? = null

        @Volatile
        var done = false

        /** 本块正在用的连接（抢跑时可能同时有两条） */
        private val activeSources = java.util.concurrent.ConcurrentHashMap.newKeySet<DataSource>()

        /** 本块正在跑的尝试（取消时要连线程一起打断，否则可能卡在 queue.put 上） */
        private val liveAttempts = java.util.concurrent.ConcurrentHashMap.newKeySet<Attempt>()

        /** 已经"下载并确认入队"的字节数 = 可以续传的位置 */
        @Volatile
        private var pushed = 0L

        /** 最近一次拿到新字节的时间（无进度超时用） */
        @Volatile
        private var lastProgressAt = SystemClock.uptimeMillis()

        private var attempts = 0

        /**
         * 本块的候选节点（节点池给：第一个 = SWRR 选择的起步节点，其余 = 抢跑/重试备胎；
         * 被暂停的、太慢的、被封禁的都排在后面而不是被删掉）。
         *
         * ★ 构造顺序很重要：分块是在 `openParallel()` 的循环里**逐个构造**的，
         *   所以这里每 new 一个 Chunk 就消耗一个 SWRR 名额 —— 第 i 块拿到的正是
         *   SWRR 序列里的第 i 个节点，快节点自然领到更多块。
         */
        private val urls: List<String> = CdnNodePool.orderFor(spec.uri)

        /** 轮换游标：赢家会被提到最前，下一次重试优先用它 */
        private var urlCursor = 0

        fun cancel() {
            liveAttempts.forEach { runCatching { it.lose() } }
            activeSources.forEach { runCatching { it.close() } }
        }

        /**
         * 这个异常是"上层主动取消"，还是"下载真的失败"？
         *
         * media3 的 HttpDataSource 在读取线程被中断时会抛 `HttpDataSourceException`，
         * cause 是 `InterruptedIOException: thread interrupted` —— 播放器 seek / 切集 /
         * 关播放器都会走到这里。注意 `SocketTimeoutException` **也是** `InterruptedIOException`
         * 的子类，但它代表"这条连接真的读超时了"，必须照旧重试，所以先把它排除掉。
         */
        private fun Throwable.isCancellation(): Boolean {
            var c: Throwable? = this
            var interrupted = false
            while (c != null) {
                if (c is java.net.SocketTimeoutException) return false
                if (c is InterruptedException) return true
                if (c is java.io.InterruptedIOException) interrupted = true
                c = c.cause
            }
            return interrupted && (closed || Thread.currentThread().isInterrupted)
        }

        /**
         * 卡死巡检回调。
         * 只在"队列没满"时才判定卡死：队列满说明是**上层还没取走**（正常预读），
         * 这时候掐连接会把正常的预读反复打断。
         */
        fun checkStall(now: Long) {
            if (closed || done || error != null) return
            if (queue.remainingCapacity() == 0) return
            if (now - lastProgressAt < STALL_TIMEOUT_MS) return
            lastProgressAt = now
            RipperDiag.log(
                "stall",
                "分块 ${start / 1024}KB 处 ${STALL_TIMEOUT_MS / 1000} 秒无新字节 → 掐掉连接换节点重试"
            )
            activeSources.forEach { runCatching { it.close() } }
        }

        override fun run() {
            registerChunk(this)
            try {
                while (!closed && pushed < length) {
                    try {
                        downloadAttempt()
                        break
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    } catch (e: Throwable) {
                        if (closed) return
                        // 不是"下载失败"，而是"上层不要了"：seek / 切集 / 关播放器时读取线程被中断，
                        // media3 会把它包成 HttpDataSourceException(InterruptedIOException: thread interrupted)。
                        // 这种既不该重试、也不该记 `[chunk-retry]`，更不能喂给熔断器计数。
                        if (e.isCancellation()) {
                            Thread.currentThread().interrupt()
                            return
                        }
                        // ───── ④ 412/429 风控退让（2026-09-25）─────
                        // B站的 412（被风控）/429（限流）不是"这个块坏了"，把它喂给
                        // "连续 3 次分块失败 → 熔断 10 分钟全单连接"那记重锤，用户感受到的就是
                        // "突然从 8 条连接掉到 1 条、十分钟回不来"。
                        // 现在：先**降一档并发 + 180 秒冷静期**；只有冷静期内**再次**触发才走熔断。
                        // 注意这里**不调 noteChunkFailure()** —— 限流不该给熔断器的 streak 计数。
                        if (ThreadRipperSettings.pushback) {
                            val status = httpStatusOf(e)
                            if (status == 412 || status == 429) {
                                noteChunkSuccess()
                                val again = ThreadRipperSettings.notePushback()
                                RipperDiag.log(
                                    "pushback",
                                    "分块 ${start / 1024}KB 收到 HTTP $status → 降一档并发 + " +
                                        "冷静 ${ThreadRipperSettings.PUSHBACK_COOLDOWN_SECONDS} 秒" +
                                        if (again) "；冷静期内再次触发 → 走熔断" else ""
                                )
                                if (again) tripBreaker()
                            }
                        }
                        attempts++
                        if (attempts > MAX_ATTEMPTS) {
                            RipperDiag.log(
                                "chunk-fail",
                                "分块 ${start / 1024}KB 重试 $MAX_ATTEMPTS 次仍失败：${e.javaClass.simpleName}: ${e.message}"
                            )
                            error = e
                            noteChunkFailure()
                            return
                        }
                        RipperDiag.log(
                            "chunk-retry",
                            "分块 ${start / 1024}KB 第 $attempts 次失败（已续传 ${pushed / 1024}KB）：" +
                                "${e.javaClass.simpleName}: ${e.message}"
                        )
                        // 退避一下再换一条连接（CDN 单连接被掐/超时是最常见的偶发失败）
                        try {
                            Thread.sleep(200L * attempts)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return
                        }
                    }
                }
                if (!closed && pushed >= length) {
                    done = true
                    noteChunkSuccess()
                }
            } finally {
                unregisterChunk(this)
                liveAttempts.forEach { runCatching { it.lose() } }
                activeSources.forEach { runCatching { it.close() } }
            }
        }

        /** 把一个数据块交付给上层（只有**赢家**线程会调它，所以 [pushed] 是单写者） */
        private fun deliver(buf: ByteArray) {
            queue.put(buf)
            pushed += buf.size
            lastProgressAt = SystemClock.uptimeMillis()
        }

        /**
         * 本轮试哪几条路：主节点先上，第二个候选错峰 [hedgeDelayMs]（400~900ms 自适应，见 ③）。
         * 抢跑的第二个**必须换 host** —— 用户把 CDN 固定成某个主机时，候选全是同一个 host，
         * 这里就自然退化成"单节点不换"（这是我们对用户的承诺）。
         *
         * ★ 主节点就是 `urls[0]`：它是 [CdnNodePool.orderFor] 用 SWRR 选出来的（点①），
         *   所以"哪个节点领到这一块"从平均分配变成了按实测吞吐加权分配。
         */
        private fun planForThisAttempt(): List<Pair<String, Long>> {
            val list = urls.ifEmpty { listOf(spec.uri.toString()) }
            val primary = list[urlCursor % list.size]
            urlCursor++
            val hedge = list.firstOrNull { it != primary && hostOf(it) != hostOf(primary) }
            return if (hedge == null) {
                listOf(primary to 0L)
            } else {
                listOf(primary to 0L, hedge to hedgeDelayMs())
            }
        }

        /** 让赢家成为下一轮的起点（同一块内不再回到慢节点） */
        private fun promote(url: String) {
            val idx = urls.indexOf(url)
            if (idx > 0) urlCursor = idx
        }

        /**
         * 一次（可续传的）尝试：**多节点抢跑** → 赢家读完这一块剩下的部分。
         * 全部候选都失败时抛异常，交给 [run] 的重试循环（重试会换节点、从续传位置开始）。
         */
        private fun downloadAttempt() {
            val offset = start + pushed
            val remainingTotal = length - pushed
            if (remainingTotal <= 0) return
            val pushedBefore = pushed
            val multiNode = urls.size > 1

            val racers = planForThisAttempt().map { (url, delay) ->
                Attempt(url, offset, remainingTotal, delay)
            }
            racers.forEach { attempt ->
                liveAttempts.add(attempt)
                attempt.start()
            }

            // ★ 抢跑阶段出错（超时/全挂）时也必须把还在跑的那几条停掉：它们可能马上就会
            //   把首块塞进队列 —— 不停就会出现"重试的一条 + 旧的一条"同时往队列里写同一段字节（数据损坏）
            val winner = try {
                awaitWinner(racers)
            } catch (e: Throwable) {
                racers.forEach { r ->
                    // 9 秒都没首字节 / 直接报错，记它一笔（带上原因，方便下次一眼看出是 416 还是超时）
                    if (r.firstBlock == null) {
                        // status/received 交给三粒度封禁表（只在「跨 host 合成」开着时才有账可记）：
                        // 412/429/403 这类 4xx 空响应和"连接层超时"在它眼里是两种不同的病。
                        CdnNodePool.noteFailure(
                            r.url,
                            r.failure?.let { "${it.javaClass.simpleName}: ${it.message}" },
                            status = httpStatusOf(r.failure),
                            received = r.delivered,
                        )
                    }
                    runCatching { r.lose() }
                }
                throw e
            }
            racers.forEach { if (it !== winner) it.lose() }
            try {
                winner.awaitFinish(multiNode)
            } catch (e: Throwable) {
                // 超时/失败也要把赢家停掉，否则它会继续往队列里塞数据（同上，会重）
                runCatching { winner.lose() }
                throw e
            }
            if (pushed == pushedBefore) {
                throw IOException("分段返回 0 字节（offset=${offset / 1024}KB）")
            }
            promote(winner.url)
        }

        /** 等第一个数据块：谁先到谁是赢家；全都失败了就把最后一个异常抛出去 */
        private fun awaitWinner(racers: List<Attempt>): Attempt {
            // 判决窗口用**最大**抢跑延迟算（[HEDGE_DELAY_MS]，不是自适应的当前值）：
            // 自适应只会让抢跑更早发生，用最大值当上限 = 窗口只宽不窄，不会把正常等待判成超时。
            val deadline = SystemClock.uptimeMillis() +
                FIRST_BYTE_TIMEOUT_MS + HEDGE_DELAY_MS + FIRST_BLOCK_SLACK_MS
            while (true) {
                if (closed) throw InterruptedIOException("ThreadRipperDataSource 已关闭")
                racers.forEach { r ->
                    if (r.firstBlock != null) {
                        // ★ 关键：必须把 won 置 true —— 赢家线程在等这个判决，否则它会以为"没选我"而退出
                        r.won = true
                        return r
                    }
                }
                val failures = racers.mapNotNull { it.failure }
                if (failures.size == racers.size) throw failures.last()
                if (SystemClock.uptimeMillis() > deadline) {
                    throw IOException("${racers.size} 条路都没有首字节（等待超时）")
                }
                Thread.sleep(15L)
            }
        }

        /**
         * 一条连接的一次尝试（抢跑用）。
         *
         * 流程：错峰 → open → 读**首块**放进 [firstBlock] → 等判决（[won]）→
         * 赢了就继续读完这一块；输了/被取消就立刻 close 并退出，不记失败。
         */
        private inner class Attempt(
            val url: String,
            private val offset: Long,
            private val total: Long,
            private val delayMs: Long,
        ) {
            @Volatile
            var firstBlock: ByteArray? = null

            @Volatile
            var failure: Throwable? = null

            @Volatile
            var won = false

            @Volatile
            var lost = false

            @Volatile
            var finished = false

            /** 判决超时退出：是我们自己的等待/调度问题，不算节点失败（别把账记到 CDN 头上） */
            @Volatile
            private var undecidedExit = false

            private var ds: DataSource? = null

            /**
             * 本次尝试已经交付的字节数（[awaitFinish] 用它算速度）。
             * ★ 2026-09-25 去掉了 `private`：抢跑全挂时 [downloadAttempt] 要把它交给
             *   [CdnNodePool.noteFailure] 的 `received` 参数（"有数据回来就不算空响应"）。
             */
            @Volatile
            var delivered = 0L

            /** 真正开始收数据的时刻（速度分用它算，抢跑的错峰等待不该算进去） */
            @Volatile
            private var dataStartAt = 0L

            /** 首块耗时（毫秒，-1 = 还没量到）：自适应抢跑延迟的输入，见 companion 的 [notePieceTime] */
            @Volatile
            private var firstBlockMs = -1L

            private val thread = Thread({ body() }, "ripper-attempt").apply { isDaemon = true }

            fun start() = thread.start()

            /** 判负 / 取消：关连接 + 打断线程（可能正卡在 queue.put 上） */
            fun lose() {
                lost = true
                runCatching { ds?.close() }
                runCatching { thread.interrupt() }
            }

            fun awaitFinish(multiNode: Boolean) {
                // 速度窗口：只在"上层正等着数据"的时候累计（停着的时间不算，见下面的注释）
                var windowStart = SystemClock.uptimeMillis()
                var windowBytes = 0L
                while (!finished && failure == null && !lost && !closed) {
                    // ★ 活性兜底：万一尝试线程已经退出却没留下 finished/failure（上面那条判决超时
                    //   路径就是这么漏的），继续等下去就是死循环 —— 宁可报错让它换节点续传。
                    if (!thread.isAlive) {
                        throw IOException("抢跑线程已退出但未完成（已续传 ${pushed / 1024}KB）")
                    }
                    if (multiNode) {
                        val now = SystemClock.uptimeMillis()
                        if (queue.remainingCapacity() > 0) {
                            // ★★ 只有"队列没满 = 播放器正等着我们的数据"时才算速度（我们才是瓶颈）。
                            //    vc107 的教训：没加这个判断，把**正常的预读停驻**当成了"0KB/s 的慢节点" ——
                            //    番剧一次请求几百 MB，8 条连接抢跑时另外 7 条队列早满了，它们只是**在等播放器取走**，
                            //    却被判"太慢"掐掉 → 12 个分块连续失败 → 连锁触发 10 次熔断（10 分钟单连接）。
                            //    这和看门狗 `checkStall` 里的 `queue.remainingCapacity() == 0 → return` 是同一个道理。
                            val elapsed = now - windowStart
                            if (elapsed > BPS_GRACE_MS) {
                                val bps = (delivered - windowBytes) * 1000L / elapsed
                                if (bps < MIN_ATTEMPT_BPS) {
                                    CdnNodePool.noteFailure(url, "太慢：${bps / 1024}KB/s")
                                    throw IOException(
                                        "节点太慢（${bps / 1024}KB/s < ${MIN_ATTEMPT_BPS / 1024}KB/s）→ 换节点续传"
                                    )
                                }
                            }
                        } else {
                            // 队列满 = 上层还没取走（正常预读/缓冲已满）→ 速度当然是 0，不能因此判它慢；
                            // 把窗口往后挪，等它真的开始等数据时再重新计时。
                            windowStart = now
                            windowBytes = delivered
                        }
                    }
                    Thread.sleep(20L)
                }
                failure?.let { throw it }
                if (lost) throw IOException("赢家被取消（已续传 ${pushed / 1024}KB）")
                if (!finished && !closed) {
                    throw IOException("尝试提前结束（已续传 ${pushed / 1024}KB）")
                }
            }

            private fun body() {
                try {
                    if (delayMs > 0) {
                        Thread.sleep(delayMs)
                        if (lost || closed) return
                        // 走到这里 = 主节点在 hedgeDelayMs 内还没交出首字节 → 正式抢跑
                        // （延迟是自适应的，所以日志里打实际值，不再写死 900）
                        RipperDiag.log(
                            "hedge",
                            "分块 ${start / 1024}KB：主节点 ${delayMs}ms 没首字节 → 抢跑第二条（${hostOf(url)}）"
                        )
                    }
                    if (lost || closed) return
                    val source = upstreamFactory.createDataSource()
                    ds = source
                    activeSources.add(source)
                    val rangeSpec = spec.buildUpon()
                        .setUri(Uri.parse(url))
                        .setPosition(offset)
                        .setLength(total)
                        .build()
                    val opened = source.open(rangeSpec)
                    // 服务端不认 Range 时会按 200 全量返回（长度远大于我们要的这一块）→ 判失败
                    if (opened != C.LENGTH_UNSET.toLong() && opened > total) {
                        throw IOException("服务端未按 Range 返回（期望 $total，实得 $opened）")
                    }
                    dataStartAt = SystemClock.uptimeMillis()
                    var remaining = if (opened == C.LENGTH_UNSET.toLong()) total else opened

                    // ① 首块：谁先交出来谁赢（另一个还在等判决）
                    val first = readBlock(source, remaining)
                        ?: throw IOException("分段提前结束（还差 $remaining 字节）")
                    remaining -= first.size
                    firstBlock = first
                    // ③ 自适应抢跑延迟的输入：首块耗时（只在"赢了"之后才会真的记进 EMA，见下）
                    firstBlockMs = SystemClock.uptimeMillis() - dataStartAt

                    // ② 等判决：调用方每 15ms 轮询一次，正常几毫秒就出结果
                    val judgeDeadline = SystemClock.uptimeMillis() + DECISION_WAIT_MS
                    while (!won && !lost && !closed && SystemClock.uptimeMillis() < judgeDeadline) {
                        Thread.sleep(10L)
                    }
                    if (!won || lost || closed) {
                        // ★ 判决超时（没选我、也没被取消）时线程直接退出：以前既不设 failure 也不设
                        //   finished，而判决方只看 firstBlock != null 仍可能选中它 —— 于是 awaitFinish
                        //   的循环条件（!finished && failure == null && !lost && !closed）永远不再变化
                        //   → 永久自旋，read() 既拿不到数据也拿不到错误，播放器永久转圈，这个线程池
                        //   线程也永远回不来。这里留下"我已退出"的证据，让等待方立刻拿到异常去重试。
                        if (!won && !lost && !closed) {
                            undecidedExit = true
                            failure = IOException(
                                "抢跑线程未在 ${DECISION_WAIT_MS}ms 内等到判决（已续传 ${pushed / 1024}KB）"
                            )
                        }
                        return
                    }

                    // ③ 赢家：首块 + 剩下的全部按顺序交付
                    //    ★ 只有赢了才把"首块耗时"记进 EMA —— 输的那条是被掐掉的，
                    //      它的耗时里混着错峰等待和"根本没轮到我"，不能代表线路速度。
                    if (firstBlockMs > 0) notePieceTime(firstBlockMs)
                    deliver(first)
                    delivered += first.size
                    while (remaining > 0 && !closed && !lost) {
                        val buf = readBlock(source, remaining)
                            ?: throw IOException("分段提前结束（还差 $remaining 字节）")
                        remaining -= buf.size
                        deliver(buf)
                        delivered += buf.size
                    }
                    finished = remaining <= 0
                } catch (e: Throwable) {
                    if (e is InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                    // 输了 / 上层关了 → 不算失败（抢跑输的那条本来就会被掐）
                    if (!lost && !closed) failure = e
                } finally {
                    runCatching { ds?.close() }
                    ds?.let { source -> activeSources.remove(source) }
                    liveAttempts.remove(this)
                    when {
                        finished -> CdnNodePool.noteSuccess(
                            url,
                            delivered,
                            SystemClock.uptimeMillis() - (if (dataStartAt > 0) dataStartAt else SystemClock.uptimeMillis()),
                        )
                        failure != null && !undecidedExit -> CdnNodePool.noteFailure(
                            url,
                            failure?.let { "${it.javaClass.simpleName}: ${it.message}" },
                            // ★ 把 HTTP 状态码和"实际收到多少字节"交给节点池：
                            //   412/429/403 这类 4xx **空响应**与"连接层超时"在封禁表里是两种病，
                            //   而"有数据回来"的失败根本不该算空响应（received > 0 直接跳过记账）。
                            status = httpStatusOf(failure),
                            received = delivered,
                        )
                    }
                }
            }

            /** 读满一个 64KB 块；只有"这一段就到头了"才会返回更短的块，读不到返回 null */
            private fun readBlock(source: DataSource, remaining: Long): ByteArray? {
                val want = minOf(BUFFER_SIZE.toLong(), remaining).toInt()
                val buf = ByteArray(want)
                var filled = 0
                while (filled < want) {
                    val n = source.read(buf, filled, want - filled)
                    if (n == C.RESULT_END_OF_INPUT) break
                    filled += n
                }
                return when {
                    filled <= 0 -> null
                    filled == want -> buf
                    else -> buf.copyOf(filled)
                }
            }
        }
    }
}

/**
 * `https://host/path?query` → `host`。
 *
 * 用在两处：① `[parallel]` 日志里列出候选节点；② 抢跑时判断"第二个候选是不是**不同 host**"
 * —— 用户在设置里把 CDN 固定成某个主机时，候选全是同一个 host，抢跑就自然不换节点（承诺不打破）。
 */
private fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")

/**
 * 从异常链里挖出 HTTP 状态码（0 = 不是 HTTP 响应码错误，例如超时/DNS/TLS）。
 *
 * 为什么要它（2026-09-25）：④ 的风控退让只对 **412 / 429** 生效，
 * 而 media3 的 `DefaultHttpDataSource` 把非 2xx 包成
 * [HttpDataSource.InvalidResponseCodeException]（`responseCode` 字段），
 * 有时外面还会再套一层 `HttpDataSourceException`，所以沿着 `cause` 往里找。
 * 找不到就返回 0 —— 调用方把这个当"连接层的空响应"处理，不会误判成风控。
 */
private fun httpStatusOf(t: Throwable?): Int {
    var c = t
    var depth = 0
    while (c != null && depth++ < 8) {
        if (c is HttpDataSource.InvalidResponseCodeException) return c.responseCode
        c = c.cause
    }
    return 0
}
