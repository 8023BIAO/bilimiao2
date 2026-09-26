package com.a10miaomiao.bilimiao.comm.live.danmaku

import android.util.Log
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 直播弹幕链路的**可观测性**（第三阶段修复新增）。
 *
 * ## 为什么需要它（这是"看不见弹幕"排查里最关键的一块）
 * 工程的日志实现 [com.a10miaomiao.bilimiao.comm.utils.MiaoLogger] 有一句
 * `if (!BuildConfig.DEBUG && level != Log.ERROR) return` ——
 * **release 包里 `d()/i()` 全是空操作**。而弹幕链路的关键信息（getDanmuInfo 的返回码、
 * op=8 的认证结果、收了多少条弹幕）此前**全部**走的是 `d()`，
 * 于是用户装的正式包一旦"看不见弹幕"，**logcat 里一个字都没有**，只能靠猜。
 *
 * 这里给两条**在 release 包里也有效**的观测通道：
 * 1. [overlayCounters]：把计数器直接画在弹幕浮层上（用户/验收方肉眼可见，
 *    不依赖 logcat、不依赖调试包）；
 * 2. [enabled]：把"事件级"轨迹追加写进
 *    `Android/data/<包名>/files/live_danmaku.log` —— 这个目录**用 adb 就能 pull**
 *    （工程里 `AntifraudDiag` 已经是同一套做法），release 包也能事后取证。
 *
 * ## 为什么是"事件级"而不是"每条弹幕一行"
 * 热门房每秒几十条弹幕，逐条落盘会把主线程/IO 拖垮。这里只在
 * **状态变化 / 连接动作 / 计数快照**时写一行，量极小（一次会话几十行）。
 * 逐条信息靠计数器聚合。
 *
 * ## 开关
 * - [enabled]：是否写 trace 文件（默认开；怕有 IO 顾虑可关，不影响其它逻辑）
 * - [overlayCounters]：浮层上是否显示计数条（默认开）。
 *   ★**正式发版前若要关掉屏幕上的调试文字，把这一行改成 false 即可**（一行开关，见字段注释）。
 */
object LiveDanmakuTrace {

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    /** 是否把事件轨迹写进 `files/live_danmaku.log`（release 也生效，adb 可直接 pull） */
    @Volatile
    var enabled: Boolean = true

    /**
     * 是否在弹幕浮层左上角显示实时计数条。
     *
     * ★默认 **true**：本次"看不见弹幕"的教训就是 release 包完全没有可观测手段，
     *   验收时必须能一眼看到"收到了几条 / 上屏几条 / 连接状态"。
     *   正式发版前把它改成 `false` 就恢复干净画面（无需改别的代码）。
     */
    @Volatile
    // ★ 2026-09-26 用户反馈后关掉默认显示："上面的小黄字很挡我的视线……我 PiP 的时候，它挡住我大部分视野。"
    //   失败原因那行不受这里影响：**只有真的连不上时才出现**（弹幕修好后正常播放屏幕上不会有任何文字）。
    //   需要现场诊断时把它改回 true 重新编译即可；事件级 trace 文件（live_danmaku.log）保持开启，不影响画面。
    var overlayCounters: Boolean = false

    // ------------------------------------------------------------------
    // 计数器（多线程写：okhttp 读线程 / 解码协程 / Compose 主线程）
    // 只做统计展示，不参与任何逻辑判断，所以用 Atomic 保证不丢更新即可
    // ------------------------------------------------------------------

    /** 收到的原始 WS 帧数（含压缩帧） */
    val rawFrames = AtomicLong()

    /** 二进制协议解出的包数（含 op=3/op=5/op=8） */
    val packets = AtomicLong()

    /** 归一化出的 [LiveMessage.Danmaku] 条数 = "弹幕流里到底有没有货" */
    val danmaku = AtomicLong()

    /** 除弹幕外的其它消息条数（礼物/醒目留言/人气/看过…） */
    val others = AtomicLong()

    /** 浮层入队（收到并准备上屏）条数 */
    val enqueued = AtomicLong()

    /** 浮层真正上屏条数 */
    val shown = AtomicLong()

    /** 浮层丢掉的条数（队列满/过期/超出同屏上限） */
    val dropped = AtomicLong()

    /** 帧循环跑过的帧数：证明 [androidx.compose.runtime.withFrameNanos] 循环在跑 */
    val frames = AtomicLong()

    /** 弹幕幂等丢弃（同一人同一句 2s 内重复） */
    val deduped = AtomicLong()

    /** 解包跳过次数（ver=3 brotli / 脏数据） */
    val decodeSkips = AtomicLong()

    /**
     * ★"进房铺底的最近历史弹幕"这一路的结果（纯加法）。
     *
     * 为什么值得单独留一格：这条链路**必须失败静默**（绝不能影响实时弹幕），
     * 所以屏幕上永远不会因为它出提示 —— "到底拉到了没有"就只能靠这里取证：
     * trace 文件（release 也写）与浮层计数条都会带上它。
     * 取值形如 `成功 18 条` / `失败 code=-352 …` / `未拉取` / `空（接口没给数据）`。
     */
    @Volatile
    var historyState: String = "未拉取"

    /** 最近一次解析/连接相关事件（浮层与 trace 文件都会带上它） */
    @Volatile
    var lastEvent: String = "（还没有事件）"

    /** 连接状态的可读文本（由 [LiveDanmakuClient] 写；[LiveMessage] 接口不变，所以走这里共享） */
    @Volatile
    var connectionText: String = "Idle"

    /** 连接失败的**原因**（Failed 时非空）：浮层靠它把"为什么没弹幕"告诉用户 */
    @Volatile
    var lastConnectError: String? = null

    /** 最近一次发送弹幕失败的原因（见 [LiveDanmakuClient.sendDanmaku]） */
    @Volatile
    var lastSendError: String? = null

    /** 浮层当前状态（由浮层写，浮层掉帧/尺寸为 0 这类问题一眼可见） */
    @Volatile
    var overlayLanes: Int = 0
    @Volatile
    var overlayWidth: Int = 0
    @Volatile
    var overlayHeight: Int = 0
    @Volatile
    var overlayActive: Int = 0
    @Volatile
    var overlayPending: Int = 0

    /**
     * 浮层上/日志里的一行快照。
     *
     * 刻意做成"一行"：验收时截图就能看清
     * —— `收` = 弹幕流里有多少条（0 就是链路问题，不是渲染问题），
     *    `上屏/在屏` = 浮层有没有真的画出来（0 而 `收`>0 就是渲染问题）。
     */
    fun snapshot(): String = buildString(160) {
        append("弹幕 收=").append(danmaku.get())
        append(" 其它=").append(others.get())
        append(" 上屏=").append(shown.get())
        append(" 在屏=").append(overlayActive)
        append(" 队列=").append(overlayPending)
        append(" 丢=").append(dropped.get())
        append(" | 帧=").append(rawFrames.get())
        append(" 包=").append(packets.get())
        append(" 跳=").append(decodeSkips.get())
        append(" | 车道=").append(overlayLanes)
        append(" 视图=").append(overlayWidth).append('x').append(overlayHeight)
        append(" 动画帧=").append(frames.get())
        append(" | ").append(connectionText)
        // ★历史弹幕这一路的结论（进房有没有铺上东西，一眼可见）
        append(" 历史=").append(historyState)
        lastConnectError?.let { append(" 失败原因=").append(it) }
        lastSendError?.let { append(" 发送失败=").append(it) }
    }

    /** 事件级留痕：内存里记一份 + （可选）追加到 adb 可读的 trace 文件 */
    fun note(event: String) {
        lastEvent = event
        // ★同时打一条 ERROR 级日志：MiaoLogger 在 release 下**只放行 ERROR**
        //   （MiaoLogger.kt:60-61），所以这就是 release 包里唯一能进 logcat 的通道。
        //   事件级调用，量很小，不会刷屏。
        runCatching { Log.e(TAG, "$prefix$event") }
        if (!enabled) return
        val line = "$prefix$event"
        runCatching { io.execute { appendLine(line) } }
    }

    /** 清空计数（换房间时调用，避免旧房间的数字混进来） */
    fun reset(roomId: Long) {
        rawFrames.set(0); packets.set(0); danmaku.set(0); others.set(0)
        enqueued.set(0); shown.set(0); dropped.set(0); frames.set(0)
        deduped.set(0); decodeSkips.set(0)
        historyState = "未拉取"
        connectionText = "Idle"
        lastConnectError = null
        lastSendError = null
        note("========== 新的弹幕会话 room=$roomId ==========")
    }

    // ------------------------------------------------------------------
    // 落盘（单线程 + 体积上限；绝不阻塞调用线程）
    // ------------------------------------------------------------------

    private const val TAG = "LiveDanmaku"

    /** 日志文件超过它就先清空：只保留最近一段，避免长期使用把用户存储写满 */
    private const val MAX_FILE_BYTES = 256 * 1024L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "live-danmaku-trace").apply { isDaemon = true }
    }

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    private val prefix: String
        get() = "${timeFormat.format(Date())} "

    private val lineCounter = AtomicInteger()

    private fun appendLine(line: String) {
        runCatching {
            val f = file ?: (resolveFile() ?: return).also { file = it }
            if (f.length() > MAX_FILE_BYTES) f.delete()
            f.appendText(line + "\n")
            // 每 20 行补一条计数快照：即使用户只 pull 日志、没看屏幕，也能看到趋势
            if (lineCounter.incrementAndGet() % 20 == 0) f.appendText(prefix + snapshot() + "\n")
        }
    }

    /**
     * `Android/data/<包名>/files/live_danmaku.log`。
     *
     * ★为什么用**外置**私有目录而不是内部 filesDir：内部目录在非 root 机器上 adb 读不到，
     *   而这个功能存在的唯一意义就是"release 包出问题时能取证"。
     *   工程里 `AntifraudDiag`（:45）已经是同一套做法，测试流程也已经在 pull 它。
     */
    private fun resolveFile(): File? = runCatching {
        BilimiaoCommApp.commApp.app.getExternalFilesDir(null)
            ?.resolve("live_danmaku.log")
    }.getOrNull()
}
