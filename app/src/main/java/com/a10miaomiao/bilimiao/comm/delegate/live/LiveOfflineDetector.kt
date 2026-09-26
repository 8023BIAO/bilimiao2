package com.a10miaomiao.bilimiao.comm.delegate.live

import androidx.media3.common.PlaybackException

/**
 * **"这一串失败到底是主播下播，还是线路/网络抖动"** —— 直播播放页的**下播判据**
 * （纯新增，2026-09-26；纯逻辑：无 IO、无 Android 状态、时间由调用方喂进来）。
 *
 * ## 为什么必须有它（要解决的正是用户实测的"一直在换流换流"）
 * 修前，播放中途主播下播时，所有恢复路径都只看**播放器自己**的信号：
 * ```
 * STATE_ENDED            → LivePlayerDelegate.handleStreamEnded()  → 重取流
 * ERROR_CODE_IO_* / 4xx  → LivePlayerDelegate.handlePlayerError()   → 换线路
 * 画面不出帧 / 连续缓冲   → LivePlayerActivity.checkLiveHealthOnce() → autoRetryLiveStream()
 * ```
 * 这三条路各自的限流器都是**滑动/固定窗口**，窗口一过额度就自己回满（`RateLimiter.tryAcquire`
 * 的窗口重置、`AUTO_RETRY_WINDOW_MS` 的滑窗），而 Activity 的自动追流又会调
 * `LivePlayerDelegate.retry()` **清空** delegate 的三个预算 —— 于是**没有任何一处**会得出
 * "主播已经不在了，别再换了"这个结论。用户看到的就是无限换流（详见交付报告 §2）。
 *
 * 本类只做一件新事：把"失败"**记账**，并在**连续 N 次**之后允许调用方去打**一次**
 * 限频的 `live_status` 复查；复查说"没在播"就**锁定**（[latched]），从此所有自动恢复一律封死，
 * 只剩"等待开播"轮询这一条低频路径（见 `LivePlayerActivity.startOfflinePolling`）。
 *
 * ## 三层判据（由弱到强，越强越不需要计数）
 * | 层 | 输入 | 处理 | 为什么 |
 * |---|---|---|---|
 * | ① 硬证据 | 取流返回 `live_status != 1`（0 未开播 / 2 轮播） | **直接锁定**，不计数 | 这是"能不能播"的**唯一权威**判据（`LivePlayability` 的结论：`playurl_info` 为空 + `live_status` 非 1） |
 * | ② 限频复查 | [shouldVerify]：窗口内累计 ≥ [SUSPECT_BREAKS] 次中断 | 允许调用方打**一次** `room/v1/Room/get_info` 问 `live_status`（[VERIFY_MIN_INTERVAL_MS] 起间隔、[VERIFY_MAX_IN_WINDOW] 次/[VERIFY_WINDOW_MS] 封顶） | 只在"播放侧反复失败"时才多花一次请求；`get_info` 实测免登录、无 WBI、不吃 `room_init` 那波 412 风控 |
 * | ③ 彻底停手 | 窗口内累计 ≥ [GIVE_UP_BREAKS] 次中断 | [gaveUp]（不再换线/重取流，转等待开播） | "预算 + 下播判定"两道终点都要有：即使复查说"在播"，也不能永远换下去 |
 *
 * ## 错误码白名单（**故意的窄**：不确定的一律不算"下播嫌疑"）
 * | 信号 | 算不算 | 为什么 |
 * |---|---|---|
 * | `STATE_ENDED`（流"放完了"） | ✅ 强 | 直播本不该有终点（delegate 自己的 KDoc 就这么写）；下播时服务端会直接结流 |
 * | HTTP **403 / 404 / 410 / 451** | ✅ 弱 | 下播后 CDN 上那份签名地址会立刻 403/404；但也可能是防盗链/地区限制 ⇒ 只记嫌疑，不定性 |
 * | `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT` / `_FAILED` / `_UNSPECIFIED` | ❌ | 地铁/切网/弱网一抖就报"下播"，比不报更糟 |
 * | HTTP 412 / 5xx | ❌ | 风控与服务端抖动（本工程实测 `room_init` 被整端点 412 封过），与下播无关 |
 * | `ERROR_CODE_BEHIND_LIVE_WINDOW` | ❌ | 时间轴问题，delegate 已有 seek 自愈 |
 * | `ERROR_CODE_PARSER_*` / `ERROR_CODE_DECODING_*` | ❌ | 线路/解封装/本地解码器的问题，换线才是解 |
 * | 取流成功但 `playurl_info == null`（`live_status == 1`） | ✅ 弱 | 服务端自相矛盾（说在播却不下发流）；实测在播房间偶发，连续两次才值得复查 |
 * | 弹幕 WS `cmd=PREPARING`（[noteRoomPreparing]） | ✅ 弱 | B 站自己推的"房间回到准备中"= 主播下播；只需复查确认，不直接定性 |
 * | 弹幕 WS `cmd=LIVE`（[noteRoomLive]） | ⟲ 清零 | 真的开播了（把嫌疑洗掉） |
 * | 弹幕 WS `STOP_LIVE_ROOM_LIST` | ❌ **刻意不用** | 它是"一批房间停播"的**全局列表**类消息，本工程解析层（`LiveDanmakuClient`）没有按 room_id 过滤就发成了 `LiveMessage.LiveStatus(STOPPED)` ⇒ 拿它定性会误伤别的房间 |
 *
 * ## 线程约定
 * 本类**不是线程安全**的：所有方法都只在**主线程**被调用（delegate 的播放器回调、Activity 的
 * 看门狗与弹幕订阅都在主线程），与 `LivePlayerDelegate` 的既有约定一致。
 */
class LiveOfflineDetector {

    /** 窗口内的中断时刻（`SystemClock.elapsedRealtime()`），用来数"最近这一阵子断了几次" */
    private val breaks = ArrayDeque<Long>()

    /**
     * **"嫌疑"计数**（驱动限频复查）。
     * ★它与 [breaks] 刻意分开：[noteVerifyOnline] 只洗嫌疑（避免同一个事故反复打接口），
     *   而 [breaks] 是"别再换了"的**总账**，只能被一次稳定播放（[notePlaying]）或
     *   [reset]（用户/开播轮询重新起播）洗掉 —— 否则"复查说在播 → 清空 → 又断 5 次 → 复查 → …"
     *   会让 [gaveUp] 永远攒不够，终点又没了。
     */
    private var suspects = 0

    /** 限频复查的发出时刻（同上一行的口径） */
    private val verifyStamps = ArrayDeque<Long>()

    /**
     * 是否**已经锁定"没在播"**（硬证据或复查确认）。
     * ★锁定期间调用方必须封死所有自动恢复（换线/重取流/seek 回沿），只留"等待开播"轮询。
     */
    var latched: Boolean = false
        private set

    /** 锁定的来源（诊断用，例如 `getRoomPlayInfo` / `verify:get_info` / `streamEnded-budget`） */
    var latchSource: String = ""
        private set

    /** 锁定时看到的那次 `live_status`（[STATUS_UNKNOWN] = 没问到） */
    var latchLiveStatus: Int = STATUS_UNKNOWN
        private set

    /** 是否已经**彻底停手**（窗口内断得太多：即使复查说在播也不再自动恢复） */
    var gaveUp: Boolean = false
        private set

    /** **本次进房以来是否真的出过画面**（决定文案说"未开播"还是"已下播"） */
    var everPlayed: Boolean = false
        private set

    /** 当前这轮"连续在播"的起点（0 = 不在播）——用来判"稳定播放过" */
    private var playingSinceMs: Long = 0L

    /**
     * 现在还有没有必要拦下自动恢复（[latched] 或 [gaveUp]）。
     * ★调用方（delegate）在每一个自动恢复入口上判它，这就是"换流有终点"的落点。
     */
    val blocked: Boolean get() = latched || gaveUp

    /** 窗口内累计的中断次数（"别再换了"的总账；诊断/日志用） */
    val recentBreaks: Int get() = breaks.size

    /** 当前的"嫌疑"计数（驱动复查；诊断/日志用） */
    val suspectCount: Int get() = suspects

    /**
     * 最近一次中断的**类别**（诊断用）：`streamEnded` / `playerError:BAD_HTTP_STATUS/http=404` /
     * `fetchNoStream` / `danmaku:PREPARING`。
     * ★它只进诊断日志，不参与任何判断 —— 真机上"到底是被哪一路判成下播的"全靠它一眼看出来。
     */
    var lastBreakKind: String = ""
        private set

    // ── 输入：播放侧 ────────────────────────────────────────────────────────

    /**
     * **真的在播**（media3 的 `onIsPlayingChanged(true)`，不是"prepare 成功"）。
     *
     * ★为什么用 `isPlaying` 而不是 `STATE_READY`：`STATE_READY` 在"新地址 prepare 好了但一个字节
     *   都还没来"时就会到，拿它当"恢复了"会让计数永远清零 —— 而"每次 prepare 都成功、播两秒就断"
     *   正是下播后的典型形态。`isPlaying` 要求"有东西真的在走"。
     * ★"连续播了多久"的结算在 [notePlaybackStopped]（`isPlaying` true→false 那一刻），
     *   这里只负责起算。
     */
    fun notePlaying(nowMs: Long) {
        everPlayed = true
        // 起算这一轮"连续在播"（已在播时不重置起点，否则永远量不出一段完整播放）
        if (playingSinceMs <= 0L) playingSinceMs = nowMs
    }

    /** 掉出播放态（暂停/缓冲/断流）：结算"这一轮连续播了多久"，并让下次 [notePlaying] 重新起算 */
    fun notePlaybackStopped(nowMs: Long) {
        // ★结算点放在这里（而不是 [notePlaying] 里）：`isPlaying` 由 true→false 时才一定会被调到，
        //   而"连续播了多久"只有在这一刻才是完整的一段 —— 稳过 [STABLE_PLAY_MS] 就说明
        //   上一个事故周期真的过去了，把总账与嫌疑一起清零（之后的失败是**新的事故**）。
        if (playingSinceMs > 0L && nowMs - playingSinceMs >= STABLE_PLAY_MS) {
            breaks.clear()
            suspects = 0
            gaveUp = false
        }
        playingSinceMs = 0L
    }

    /**
     * 记一次中断：**流结束了**（`STATE_ENDED`）。
     * @return true = 这次记账之后已经该"彻底停手"（[gaveUp]）
     */
    fun noteStreamEnded(nowMs: Long): Boolean = noteBreak("streamEnded", nowMs)

    /**
     * 记一次中断：**播放器报错**，但只有白名单内的错误码才算（见类 KDoc 的表）。
     * @return true = 这个错误被算作"下播嫌疑"（调用方据此决定要不要触发复查）
     */
    fun notePlaybackError(errorCode: Int, httpCode: Int?, nowMs: Long): Boolean {
        if (!isOfflineSuspectError(errorCode, httpCode)) return false
        noteBreak("playerError:${errorCodeName(errorCode)}/http=$httpCode", nowMs)
        return true
    }

    /** 记一次中断：**服务端说在播却没下发任何流**（`playurl_info == null` / 候选表为空） */
    fun noteFetchWithoutStream(nowMs: Long): Boolean = noteBreak("fetchNoStream", nowMs)

    // ── 输入：弹幕 WS（零成本信号，只当"嫌疑"，不当定性）─────────────────────

    /** `cmd=PREPARING`：房间回到"准备中"（下播）—— 记一次嫌疑 */
    fun noteRoomPreparing(nowMs: Long): Boolean = noteBreak("danmaku:PREPARING", nowMs)

    /** `cmd=LIVE`：真的开播了 —— 总账与嫌疑一起复位（这是全新的一轮） */
    fun noteRoomLive() {
        breaks.clear()
        suspects = 0
        gaveUp = false
    }

    // ── 输入：硬证据 ────────────────────────────────────────────────────────

    /**
     * **锁定"没在播"**（硬证据）：[liveStatus] 必须是服务端明确给的取值
     * （0 未开播 / 2 轮播；[STATUS_UNKNOWN] 一律走 [noteGaveUp]，绝不能把"没问到"写成"下播"）。
     */
    fun noteHardOffline(liveStatus: Int, source: String) {
        if (latched) return
        latched = true
        latchSource = source
        latchLiveStatus = liveStatus
    }

    /** **彻底停手**（没有下播的硬证据，只是断得太多）：不再自动恢复，转等待开播 */
    fun noteGaveUp(source: String) {
        if (gaveUp) return
        gaveUp = true
        latchSource = source
        latchLiveStatus = STATUS_UNKNOWN
    }

    // ── 限频复查（"再问一次服务端"）────────────────────────────────────────

    /**
     * 现在该不该打那**一次** `live_status` 复查。
     *
     * 三道门：① 还没锁定/停手；② 窗口内累计的中断 ≥ [SUSPECT_BREAKS]；
     * ③ 限频（距上次 ≥ [VERIFY_MIN_INTERVAL_MS]，且 [VERIFY_WINDOW_MS] 内 ≤ [VERIFY_MAX_IN_WINDOW] 次）。
     */
    fun shouldVerify(nowMs: Long): Boolean {
        if (blocked) return false
        prune(nowMs)
        if (suspects < SUSPECT_BREAKS) return false
        while (verifyStamps.isNotEmpty() && nowMs - verifyStamps.first() > VERIFY_WINDOW_MS) {
            verifyStamps.removeFirst()
        }
        if (verifyStamps.size >= VERIFY_MAX_IN_WINDOW) return false
        val last = verifyStamps.lastOrNull() ?: return true
        return nowMs - last >= VERIFY_MIN_INTERVAL_MS
    }

    /** 复查**已经发出**（乐观记账：一次超时不会让下一次巡检再叠一发） */
    fun noteVerifySent(nowMs: Long) {
        verifyStamps.addLast(nowMs)
    }

    /**
     * 复查结果：**服务端说在播**。
     * ★只洗掉"嫌疑"（[suspects]）—— 让同一个事故不必反复打接口；**不**动 [breaks]（别再换了的总账）
     *   与 [gaveUp]：后者只能被 [reset]（用户/开播轮询重新起播）或一次 [STABLE_PLAY_MS] 的稳定播放洗掉。
     */
    fun noteVerifyOnline() {
        suspects = 0
    }

    /**
     * 复查结果：**没问到**（网络/风控/解析失败）。
     * ★什么都不改：这正是 `LiveRoomProbe.STATUS_UNKNOWN` 的既有原则 —— 宁可继续按"未知"处理，
     *   也绝不把"没问到"显示成"下播"（[breaks] 留着，下一轮限频到了还能再问）。
     */
    fun noteVerifyUnknown() = Unit

    /**
     * **清空全部状态**（用户手动「重新取流」/「切清晰度」/「切线路」/开播轮询发现开播后重新起播）。
     * 一次明确的"重新来一遍"必须能把锁定与总账都解开，否则用户会发现"点了也没反应"。
     */
    fun reset() {
        breaks.clear()
        suspects = 0
        verifyStamps.clear()
        latched = false
        gaveUp = false
        latchSource = ""
        latchLiveStatus = STATUS_UNKNOWN
        playingSinceMs = 0L
        // ★everPlayed 刻意**不**清：它是"这个房间这次进来有没有播过"的事实，与"重新来一遍"无关，
        //   清了会让文案从"主播已下播"退回"主播未开播"（对同一个房间是明显退步）。
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private fun noteBreak(kind: String, nowMs: Long): Boolean {
        lastBreakKind = kind
        prune(nowMs)
        breaks.addLast(nowMs)
        suspects++
        if (breaks.size >= GIVE_UP_BREAKS) gaveUp = true
        return gaveUp
    }

    private fun prune(nowMs: Long) {
        while (breaks.isNotEmpty() && nowMs - breaks.first() > BREAK_WINDOW_MS) {
            breaks.removeFirst()
        }
        // 窗口里一次中断都不剩 = 这段时间没再出问题 ⇒ 嫌疑清零
        if (breaks.isEmpty()) suspects = 0
    }

    companion object {
        /**
         * `live_status` 未知（复查没问到）。
         * ★刻意与 `LiveRoomProbe.STATUS_UNKNOWN` 同值同义：0 是"服务端明确说没开播"，
         *   而这里是"我们没问到"，两者混在一起就又回到"接口一抖就显示未开播"的老毛病。
         */
        const val STATUS_UNKNOWN = -1

        /** 中断记账的滑窗（ms）：只数"最近这一阵子"，很久以前的失败不该累计 */
        private const val BREAK_WINDOW_MS = 120_000L

        /** 窗口内累计几次中断之后，允许打**一次** `live_status` 复查 */
        private const val SUSPECT_BREAKS = 2

        /** 窗口内累计几次中断之后**彻底停手**（即使复查说在播） */
        private const val GIVE_UP_BREAKS = 5

        /** 连续在播超过它 = 一次"真恢复"（把窗口清零） */
        private const val STABLE_PLAY_MS = 60_000L

        /** 两次复查之间至少隔这么久（限频第一道） */
        private const val VERIFY_MIN_INTERVAL_MS = 45_000L

        /** 复查的总预算：这么大的窗口内最多这么多次（限频第二道） */
        private const val VERIFY_WINDOW_MS = 10 * 60_000L
        private const val VERIFY_MAX_IN_WINDOW = 3

        /**
         * HTTP 侧的下播嫌疑白名单：**只收"这份地址在 CDN 上没了"这一类**。
         * ★403/404/410/451：下播后签名地址失效；也可能是防盗链/地区限制 ⇒ 只算嫌疑（要复查确认）。
         * ★412 与 5xx **不在**这里：412 是本工程实测的风控码（`room_init` 会被整端点封），
         *   5xx 是服务端抖动 —— 把它们算进来会让"风控一抖"直接升级成"主播下播"。
         */
        private val OFFLINE_SUSPECT_HTTP_CODES = setOf(403, 404, 410, 451)

        /** 白名单判据（[httpCode] 优先；拿不到 HTTP 码时按 `PlaybackException` 的码判） */
        fun isOfflineSuspectError(errorCode: Int, httpCode: Int?): Boolean {
            if (httpCode != null && httpCode in OFFLINE_SUSPECT_HTTP_CODES) return true
            // ★拿不到 HTTP 码时**不猜**：`ERROR_CODE_IO_UNSPECIFIED` 既可能是 4xx 也可能是断网，
            //   宁可少判一次（代价是晚一轮停手），也不要一次误判把在播房间报成"下播"。
            return when (errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> true
                else -> false
            }
        }

        /** 日志用的错误码名（`ERROR_CODE_IO_BAD_HTTP_STATUS` 这种，只给诊断看） */
        fun errorCodeName(errorCode: Int): String = when (errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "BAD_HTTP_STATUS"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "FILE_NOT_FOUND"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "NET_CONN_FAILED"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "NET_CONN_TIMEOUT"
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "IO_UNSPECIFIED"
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "BEHIND_LIVE_WINDOW"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "PARSING_CONTAINER_MALFORMED"
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "DECODING_FAILED"
            else -> "code=$errorCode"
        }
    }
}
