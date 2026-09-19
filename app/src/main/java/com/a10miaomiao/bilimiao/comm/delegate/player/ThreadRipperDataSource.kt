@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.a10miaomiao.bilimiao.comm.delegate.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.utils.RipperDiag
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
 * 「线程撕裂者」——**分段多线程下载**（海外加速，实验性功能）。
 *
 * 思路对齐 [Bilibili-thread-ripper](https://github.com/MrTangLuyao/Bilibili-thread-ripper)：
 * B 站的 DASH 播放清单里每个分段的字节范围是已知的，而播放器一次只用一个连接顺序拉；
 * 海外直连大陆 CDN 时单连接很容易被限速，于是"热门视频没事、冷门/4K 卡成 PPT"。
 * 这里把播放器要读的**一个 DataSpec（一个分段）再切成 N 个字节块**，用 N 个连接并发拉，
 * 再按原顺序交付给播放器 —— 不是把同一个文件重复下载 N 遍：每个连接只负责自己那段 Range，
 * 每块的实际返回长度都会校验，长度不对直接判失败。
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

    /** 自动线程（默认开）：线程数按分段大小自适应，上限取 [threads] */
    @Volatile
    var autoThreads: Boolean = true

    /** 线程数档位：0 = 不限（自适应，最多到本机核数），1..max = 固定线程数 */
    @Volatile
    var threads: Int = 0

    /** 本机"最大线程数" = 处理器核数（设置页滑块的上限就是它），至少 1 */
    val maxThreads: Int
        get() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /**
     * 自动模式下每个线程负责的字节数：分段越大线程越多。
     * 取 512KB 是因为 B 站普通分段大多在 1~4MB（→ 2~8 线程），
     * 与上游项目"推荐 8 到 32"的经验值同量级，但小分段不会白白开一堆连接。
     */
    private const val AUTO_BYTES_PER_THREAD = 512L * 1024L

    /**
     * 从 DataStore 刷新一份快照。
     *
     * 为什么用 runBlocking + 300ms 超时：这个读取发生在 `getMediaSource()`（GSY 会从主线程调）里，
     * 与本文件里 [PlayerDelegate2] 读磁盘缓存上限是同一套写法（DataStore 首读异常时不能无限阻塞主线程）。
     * 读不到就沿用上一次的快照（默认 = 关闭），绝不影响播放。
     */
    fun refreshBlocking(context: Context) {
        val snapshot = try {
            runBlocking {
                withTimeoutOrNull(300L) {
                    SettingPreferences.mapData(context) { prefs ->
                        Triple(
                            prefs[SettingPreferences.ThreadRipperEnable] ?: false,
                            prefs[SettingPreferences.ThreadRipperAutoThreads] ?: true,
                            (prefs[SettingPreferences.ThreadRipperThreads] ?: 0)
                                .coerceIn(0, maxThreads),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
        if (snapshot != null) {
            enabled = snapshot.first
            autoThreads = snapshot.second
            threads = snapshot.third
        }
    }

    /**
     * 决定这次请求用几个线程（1 = 不并发）。
     *
     * 档位语义（设置页里逐条有说明）：
     *  - 自动线程 **开** → 按分段大小自适应，上限 = 你设的档位（不限 = 本机核数）；
     *  - 自动线程 **关** → 档位是几就用几个线程；档位选「不限」则自适应（最多到本机核数）。
     */
    fun resolveThreads(chunkBytes: Long): Int {
        if (!enabled) return 1
        val max = maxThreads
        val configured = threads
        val cap = if (configured <= 0) max else configured.coerceIn(1, max)
        val bySize = ((chunkBytes + AUTO_BYTES_PER_THREAD - 1) / AUTO_BYTES_PER_THREAD).toInt()
        val adaptive = bySize.coerceIn(1, cap).coerceAtLeast(minOf(2, cap))
        return when {
            autoThreads -> adaptive
            configured <= 0 -> adaptive
            else -> cap
        }
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

        /** 小于这个长度不值得并发（请求本身就没多大，多开连接反而更慢） */
        const val MIN_PARALLEL_BYTES = 192L * 1024L

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
         * **无进度超时**（对齐 burst-download 的 no-progress timeout）：
         * 单块 20 秒一个字节都没新增，就掐掉这条连接换一条重试 ——
         * 海外线路最常见的就是"连接还活着但彻底不动了"，光等没有意义。
         */
        const val STALL_TIMEOUT_MS = 20_000L
        const val STALL_CHECK_PERIOD_MS = 5_000L

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
        val splittable = length != C.LENGTH_UNSET.toLong() &&
            length >= MIN_PARALLEL_BYTES &&
            dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET &&
            dataSpec.httpBody == null &&
            !dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP) &&
            ThreadRipperSettings.enabled &&
            parallelAllowed()

        val threads = if (splittable) {
            ThreadRipperSettings.resolveThreads(length).coerceIn(1, MAX_WORKERS)
        } else {
            1
        }

        if (threads <= 1) return openSingle(dataSpec)

        RipperDiag.log(
            "parallel",
            "并发拉取：${threads} 线程 / ${length / 1024}KB（${dataSpec.uri.lastPathSegment ?: ""}）"
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
                throw (error as? IOException) ?: IOException("分段下载失败：${error.message}", error)
            }
            if (chunk.done) {
                nextChunk++
                if (nextChunk >= chunks.size) return C.RESULT_END_OF_INPUT
                continue
            }
            // 否则是 worker 还在跑（或还没被线程池调度到），继续等
        }
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
     */
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

        @Volatile
        private var source: DataSource? = null

        /** 已经"下载并确认入队"的字节数 = 可以续传的位置 */
        @Volatile
        private var pushed = 0L

        /** 最近一次拿到新字节的时间（无进度超时用） */
        @Volatile
        private var lastProgressAt = SystemClock.uptimeMillis()

        private var attempts = 0

        fun cancel() {
            runCatching { source?.close() }
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
            RipperDiag.log("stall", "分块 ${start / 1024}KB 处 20 秒无新字节 → 掐掉连接重试")
            runCatching { source?.close() }
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
                        attempts++
                        if (attempts > MAX_ATTEMPTS) {
                            RipperDiag.log(
                                "chunk-fail",
                                "分块 ${start / 1024}KB 重试 $MAX_ATTEMPTS 次仍失败：${e.javaClass.simpleName}: ${e.message}"
                            )
                            error = e
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
                if (!closed && pushed >= length) done = true
            } finally {
                unregisterChunk(this)
                runCatching { source?.close() }
                source = null
            }
        }

        /** 一次完整的（或续传的）拉取尝试：从 `start + pushed` 拉到这一块的末尾 */
        private fun downloadAttempt() {
            val offset = start + pushed
            val remainingTotal = length - pushed
            if (remainingTotal <= 0) return
            val pushedBefore = pushed
            val ds = upstreamFactory.createDataSource()
            source = ds
            lastProgressAt = SystemClock.uptimeMillis()
            try {
                val rangeSpec = spec.buildUpon()
                    .setPosition(offset)
                    .setLength(remainingTotal)
                    .build()
                val openedLength = ds.open(rangeSpec)
                // ★ 校验：服务端不认 Range 时会按 200 全量返回（此时 openedLength 是"从 offset 到文件尾"，
                //   通常远大于我们要的这一块）→ 判失败并（最终）熔断，交给单连接兜底
                if (openedLength != C.LENGTH_UNSET.toLong() && openedLength > remainingTotal) {
                    throw IOException("服务端未按 Range 返回（期望 $remainingTotal，实得 $openedLength）")
                }
                var remaining =
                    if (openedLength == C.LENGTH_UNSET.toLong()) remainingTotal else openedLength
                while (remaining > 0 && !closed) {
                    val want = minOf(BUFFER_SIZE.toLong(), remaining).toInt()
                    val buf = ByteArray(want)
                    var filled = 0
                    while (filled < want) {
                        val n = ds.read(buf, filled, want - filled)
                        if (n == C.RESULT_END_OF_INPUT) break
                        filled += n
                    }
                    if (filled <= 0) {
                        throw IOException("分段提前结束（还差 $remaining 字节）")
                    }
                    remaining -= filled
                    lastProgressAt = SystemClock.uptimeMillis()
                    queue.put(if (filled == buf.size) buf else buf.copyOf(filled))
                    pushed += filled
                }
            } finally {
                runCatching { ds.close() }
                source = null
            }
            // ★ 一次尝试必须"有推进"：服务器对 Range 返回 0 字节（Content-Length: 0 / 立刻 EOF）
            //   时不能当成功 —— 否则外层 while 会原地空转，疯狂重发请求。
            if (pushed == pushedBefore) {
                throw IOException("分段返回 0 字节（offset=${offset / 1024}KB）")
            }
        }
    }
}
