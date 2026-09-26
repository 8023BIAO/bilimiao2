@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.a10miaomiao.bilimiao.comm.delegate.live

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.a10miaomiao.bilimiao.comm.delegate.player.CdnFailoverDataSourceFactory
import com.a10miaomiao.bilimiao.comm.delegate.player.CdnFailoverState
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.live.LiveAPI
import com.a10miaomiao.bilimiao.comm.live.LivePageTrace
import com.a10miaomiao.bilimiao.comm.live.entity.LivePlayUrl
import com.a10miaomiao.bilimiao.comm.live.entity.LivePlayUrlInfo
import com.a10miaomiao.bilimiao.comm.live.entity.fullUrls
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * **直播播放核心**（第二阶段 A 路）—— 自带 ExoPlayer 的独立播放代理。
 *
 * 为什么单开一条链路、一个字节都不碰 `PlayerDelegate2`：见方案 §3.3。
 * `PlayerDelegate2` 是「点播」核心（DASH / 进度条 / 倍速 / SponsorBlock / 弹幕编辑），
 * 直播没有时长、没有 seek、没有进度记忆，复用等于给它塞一堆特例。
 *
 * ## 整条链路（每一步的"为什么"都写在对应函数上）
 * ```
 * start(qn)                          ← 起播 / 重试 / 切清晰度都走这里
 *   └─ LiveAPI().playUrl(roomId, qn)  ★实测免登录、免签名，但 QoS 靠登录态（未登录恒 250）
 *       └─ 候选取全表（protocol×format×codec×url_info）→ 按"最稳优先"排序
 *           └─ 当前线路 → DataSource（UA 必填 + Referer）
 *               ├─ FLV ：包 CdnFailoverDataSourceFactory（多线路 open 级回退）
 *               └─ HLS ：不包（原因见 [buildMediaSource]）
 *                   └─ MediaSource（HlsMediaSource / DefaultMediaSourceFactory）
 *                       └─ ExoPlayer.setMediaSource() + prepare()   ← 不 release，保留最后一帧
 *                           └─ 错误码决策（抄 BiliPai LivePlaybackPolicy.kt:44-65）
 *                               ├─ BEHIND_LIVE_WINDOW → seekToDefaultPosition()（限流：15s/1200ms/2 次）
 *                               ├─ 403/404/412/5xx + IO → 切下一线路
 *                               └─ 候选耗尽            → 重新取流（★地址有效期 ≈1h）
 * ```
 *
 * ## 线程约定
 * ExoPlayer 必须在**有 Looper 的主线程**上创建与操作（Builder 默认取当前线程的 Looper），
 * 所以本类所有公开方法都要求在主线程调用（`LivePlayerActivity` 全程满足）。
 * 网络请求在 [Dispatchers.IO] 上跑，回主线程后再动播放器。
 *
 * ## 两个由用户设置驱动的开关（都在错误恢复链路上）
 * | 设置键 | 下发口 | 关掉/固定之后 |
 * |---|---|---|
 * | `live_auto_reconnect` | [setAutoReconnect] | 失败只上报（ERROR），换线/重取流/回 live edge 全停，等用户点「重试」 |
 * | `live_line_policy`（固定第一条） | [setAutoLineSwitch] | **自动**换线停掉，只重取流并留在第 0 条；手点换线不受限 |
 *
 * ## 与 CdnFailoverDataSource 的关系（硬性约束：只调用、不修改）
 * 复用的是 `app/.../comm/delegate/player/CdnFailoverDataSource.kt` 里那三个 `internal` 类
 * （它们本就是照 blbl 直播那套做的）。**没有另写一套回退逻辑**。
 * 但它有一个必须绕开的性质，见 [buildMediaSource] 的注释（这是本文件最关键的一个决策）。
 */
class LivePlayerDelegate(
    context: Context,
    /** **真实房间号**（短号要先经 room_init 换算，弹幕侧同样要真实号） */
    private val roomId: Long,
    private val listener: Listener? = null,
) {

    companion object {
        private const val TAG = "LivePlayer"

        /**
         * ★实测（方案 §2.2 防盗链表）：B 站直播 CDN **只校验非空且不是 curl 的 UA**，
         * 空 UA / `curl/8.5.0` 一律 403。取值与 `PlayerDelegate2.DEFAULT_USER_AGENT` 一致
         * （PlayerDelegate2.kt:255），实测该 UA 被 CDN 接受。
         */
        const val DEFAULT_USER_AGENT = "Bilibili Freedoooooom/MarkII"

        /** 直播地址的 Referer（★实测 CDN 不校验它，带上是为了与 Web 端行为一致） */
        private const val LIVE_REFERER_PREFIX = "https://live.bilibili.com/"

        /**
         * 播放地址有效期：★实测 `expires` ≈ 取流时刻 + 3590s（约 1 小时）。
         * 留 10 分钟余量——到期前主动换一份新地址，别等用户卡了才发现。
         */
        private const val URL_TTL_MS = 50 * 60 * 1000L

        /** 地址"体检"间隔：每 5 分钟看一眼是否临近过期（风控敏感，绝不高频调接口） */
        private const val URL_CHECK_INTERVAL_MS = 5 * 60 * 1000L

        // ── 直播 LiveConfiguration（方案 §3.2）──────────────────────────────
        /** 目标延迟：ts 分片 3s，别小于分片时长否则会不停追赶 */
        private const val LIVE_TARGET_OFFSET_MS = 3_000L
        /** 落后超过 20s 就交给 BEHIND_LIVE_WINDOW 走 seek 回 live edge */
        private const val LIVE_MAX_OFFSET_MS = 20_000L
        /** 轻微加速/减速追赶；★不能用 1.0，否则越拉越多 */
        private const val LIVE_MIN_SPEED = 0.97f
        private const val LIVE_MAX_SPEED = 1.03f

        // ── 自我保护限流（防"恢复→又失败→再恢复"死循环）────────────────────
        /**
         * behind-live-window 自恢复限流，逐字抄 blbl `LivePlayerActivity.kt:1335-1340, 2170-2172`：
         * 15s 观察窗内、距上次恢复 ≥1200ms、最多 2 次。
         */
        private const val RECOVER_WINDOW_MS = 15_000L
        private const val RECOVER_MIN_INTERVAL_MS = 1_200L
        private const val RECOVER_MAX_COUNT = 2

        /** 自动换线路：30s 内最多 8 次（超过说明每条线都在挂，继续换没意义） */
        private const val LINE_SWITCH_WINDOW_MS = 30_000L
        private const val LINE_SWITCH_MIN_INTERVAL_MS = 800L
        private const val LINE_SWITCH_MAX_COUNT = 8

        /** 重新取流：60s 内最多 3 次（每次都打 B 站接口，必须克制，否则 -352 风控） */
        private const val REFETCH_WINDOW_MS = 60_000L
        private const val REFETCH_MIN_INTERVAL_MS = 2_000L
        private const val REFETCH_MAX_COUNT = 3

        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000

        /** 抄 BiliPai `LivePlaybackPolicy.kt:49` 的集合（403/404/412/5xx 说明这条 CDN 不认我们） */
        private val HTTP_FAILOVER_CODES = setOf(403, 404, 412, 500, 502, 503, 504)
    }

    /** 播放状态（给 UI 用；与 media3 的 `Player.STATE_*` 不是一回事） */
    enum class LivePlayState {
        /** 正在解析房间 / 取流 */
        RESOLVING,

        /** 已拿到地址，正在起播或换源缓冲 */
        LOADING,

        /** 出画面了 */
        PLAYING,

        /**
         * **用户主动暂停**（`playWhenReady=false`）。
         * ★与"缓冲中"必须分开：缓冲时 `isPlaying` 也是 false，但 `playWhenReady` 仍是 true，
         *   把它当成暂停会让播放/暂停按钮在每次起播/换源时乱跳（见 [playerListener]）。
         */
        PAUSED,

        /** 房间没开播 / 轮播 / 流已结束（该转开播轮询） */
        OFFLINE,

        /** 失败（message 里有原因） */
        ERROR,
    }

    /** 一档清晰度：qn + 中文描述（如 10000 原画 / 250 超清） */
    data class LiveQualityOption(val qn: Int, val desc: String)

    /**
     * 一条线路（给 UI 渲染"线路选择"列表用）。
     * ★为什么要把 [desc] 一起送到 UI，而不是只给下标：线路的区分度全在
     *   "协议·封装·编码·CDN 域名"上（[describeLine]），用户选线路时看的就是这个，
     *   只显示"线路 1/3"等于让他盲选。
     */
    data class LiveLineOption(
        val index: Int,
        val desc: String,
        /** 是否是**当前正在播**的那一条（UI 用它打勾/高亮） */
        val current: Boolean,
    )

    /** 一次取流的成果（UI 拿它渲染"当前画质 / 线路 / 可选清晰度"） */
    data class LiveStreamInfo(
        val roomId: Long,
        /** 我们**请求**的清晰度 */
        val requestedQn: Int,
        /** 服务端**实际下发**的清晰度（★未登录时恒为 250，见方案 §2.2） */
        val actualQn: Int,
        val actualQnDesc: String,
        val qualities: List<LiveQualityOption>,
        /** 当前线路下标 / 总线路数 */
        val lineIndex: Int,
        val lineCount: Int,
        /** 人类可读的线路描述，如 `FLV · flv · avc · d1--cn-gotcha07.bilivideo.com` */
        val lineDesc: String,
        /** 全部候选线路（顺序 = 播放优先级），UI 拿它渲染可点选的线路列表 */
        val lines: List<LiveLineOption>,
    )

    interface Listener {
        /** 取流成功（含切清晰度/换线路后的重新取流） */
        fun onStreamReady(info: LiveStreamInfo)

        /** 状态变化；[message] 非空时 UI 应把它显示出来（例如"线路切换中…"） */
        fun onPlayStateChanged(state: LivePlayState, message: String?)

        /** 视频尺寸变化（UI 用它做等比缩放与 PiP 宽高比） */
        fun onVideoSizeChanged(width: Int, height: Int)
    }

    // ── 播放器 ──────────────────────────────────────────────────────────────
    private val loadControl: DefaultLoadControl = DefaultLoadControl.Builder()
        // 直播不需要回看缓冲：关掉 back buffer（blbl ExoPlayerEngine.kt:73-77 同样处理），省内存
        .setBackBuffer(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, true)
        .build()

    /**
     * 直播的 live 窗口参数。
     * ★必须走 `MediaItem.Builder().setLiveConfiguration(...)`：media3 1.11.1 的 `Player` 接口
     *   **没有** `setLiveConfiguration`（javap 核对过 `androidx.media3.common.Player`），
     *   方案 §3.2 的"两者取其一"在这里只有一个选项。
     */
    private val liveConfiguration: MediaItem.LiveConfiguration = MediaItem.LiveConfiguration.Builder()
        .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
        .setMinPlaybackSpeed(LIVE_MIN_SPEED)
        .setMaxPlaybackSpeed(LIVE_MAX_SPEED)
        .setMaxOffsetMs(LIVE_MAX_OFFSET_MS)
        .build()

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setLoadControl(loadControl)
        // ★★音频焦点：直播**主动退出**系统焦点管理（handleAudioFocus = false）★★
        //
        // ## 为什么改（依据 `直播优化-PIP与点播抢会话-诊断.md` §2.1/§3-RC-1/§5 第 1 条）
        // 原来这里是 `true`，于是直播 ExoPlayer 自己就是一个焦点 client：
        // 点播（GSY）起播时无条件 `requestAudioFocus(STREAM_MUSIC, AUDIOFOCUS_GAIN_TRANSIENT)`，
        // 直播随即收到 `AUDIOFOCUS_LOSS_TRANSIENT` → media3 的 AudioFocusManager 自动
        // `playWhenReady = false`（暂停）；而 GSY 暂停时**不归还**焦点 → 直播就一直停着。
        // 用户看到的就是"播了个普通视频，把我的直播 PIP 暂停了，而且点继续播放恢复的是点播"。
        //
        // 退出焦点管理之后：直播不再进焦点栈，点播那句 GAIN_TRANSIENT 顶不动它；
        // 直播也不再去顶别人 —— 两路各播各的（"多开互不干扰"）。
        //
        // ## 为什么还留 `.setHandleAudioBecomingNoisy(true)`
        // 焦点没了，但"拔耳机/断蓝牙不许外放"这条**与焦点无关**的礼貌必须保住：
        // 它是 media3 自己的 `ACTION_AUDIO_BECOMING_NOISY` 监听（焦点管理与它无关，两个开关独立），
        // 所以关掉焦点**不会**连带关掉它；这里显式写出来是为了"这一条是刻意保留的"能一眼看见。
        //
        // ## 代价（如实写在报告里）
        // 直播不再对**来电 / 别的 App 念导航**自动让路（收不到 LOSS_TRANSIENT 了）。
        // 要补回这份礼貌，得按诊断报告 §4 的 B1 做"自建焦点策略"（自己 requestAudioFocus
        // 并在回调里 duck/pause）——那是**另一档**改动（带设置项与实测矩阵），本轮不做。
        //
        // ★第五批删掉「听音频」之后，这条策略只剩"画面模式"一个消费方；
        //   焦点策略与"有没有画面"本来就无关，所以这次删除不影响它。
        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
        .setHandleAudioBecomingNoisy(true)
        .build()

    // ── 状态 ────────────────────────────────────────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 取流任务（切清晰度/重试时先取消上一个，避免旧结果回写） */
    private var loadJob: Job? = null

    /** 地址体检任务（只启动一次） */
    private var refreshJob: Job? = null

    private var released = false

    /** 候选线路（已排序，第 0 条优先级最高） */
    private var candidates: List<SourceCandidate> = emptyList()

    private var qualities: List<LiveQualityOption> = emptyList()

    /** 我们请求的清晰度 */
    private var requestedQn: Int = LiveAPI.QUALITY_ORIGIN

    /** 服务端实际下发的清晰度 */
    private var actualQn: Int = 0

    private var lineIndex: Int = 0

    /** 取流时刻（elapsedRealtime），用来判断地址是否临近过期 */
    private var urlFetchedAtMs: Long = 0L

    /**
     * 线路偏好（交给现成的 [CdnFailoverState] 记"上次哪条线路是通的"）。
     * 只有 FLV 那条路会真的用它做 open 级回退；HLS 用它同步"当前线路"的语义。
     */
    private var failoverState: CdnFailoverState? = null

    private var lastState: LivePlayState? = null

    private var lastMessage: String? = null

    /**
     * ★第十二批（后台只出声）：**视频轨现在是不是被 [setAudioOnly] 关着**。
     *
     * 存在的意义只有一个：`setAudioOnly(false)` 时才能判断"要不要真的下发一次参数"
     * （幂等，见那个函数）。它**不代表"页面在后台"** —— 后台与否是 Activity 的事
     * （`LivePlayerActivity.backgroundAudioOnly`），本类只管"视频轨开/关"这一个事实。
     */
    private var audioOnlyMode: Boolean = false

    // ── 播放策略（由 UI 侧「设置 → 直播设置」下发，见 LivePlayerActivity.applyPlaybackPolicyToDelegate）──
    //
    // ★为什么策略放在 delegate 而不是只写在 Activity 里：这两项管的都是**播放核心内部**的
    //   自动恢复行为（错误码决策、换线路），只有核心自己知道"此刻算不算失败"。Activity 只负责
    //   "把用户设置原样递进来"，决策留在这一层，两边就不会各判一套。

    /**
     * 断流 / 失败时要不要**自动**恢复（重取流 + 换线 + 回 live edge）。
     * 键：`live_auto_reconnect`（默认开 = 改这里之前的行为）。
     *
     * 关掉之后：所有自动恢复路径都只**上报错误**，等用户点底栏「重试」——
     * 与设置页那句"关掉后失败只会提示，需要手动点重试"逐字对应。
     * ★唯一不受它管的是 [startUrlCheckLoop]（地址体检）：那是**没坏之前的续签**
     *   （只更新候选表、不碰播放器、用户无感），不属于"失败后的自动重连"。
     */
    private var autoReconnect: Boolean = true

    /**
     * 允许**自动**换线路（键 `live_line_policy`；false = "固定第一条线路"）。
     *
     * ★只约束自动切换：用户在「线路」弹窗里手点的 [switchToLine] 永远优先于策略 ——
     *   那个设置项的用途正是"排查到底哪条线路好"，不让手点就自相矛盾了。
     * ★"第一条"指的是**候选表第 0 条**（`candidates[0]`），不是"接口 JSON 里的第一个 url"：
     *   本类的候选表在 [buildCandidates] 里已经按"最稳优先"排过序（FLV → flv → avc → 第一台 CDN），
     *   用户看到的"线路 1/N"就是它 —— 固定住用户看得见的那一条才符合直觉。
     */
    private var autoLineSwitch: Boolean = true

    /** 下发"失败后自动重连"（设置弹窗改动后**立即**生效：下一次失败就按新值走） */
    fun setAutoReconnect(enabled: Boolean) {
        autoReconnect = enabled
    }

    /** 下发"允许自动换线"（false = 固定第一条线路；同样立即生效） */
    fun setAutoLineSwitch(enabled: Boolean) {
        autoLineSwitch = enabled
    }

    // 三个限流器：语义见各自常量注释
    private val behindLiveWindowLimiter = RateLimiter(RECOVER_WINDOW_MS, RECOVER_MIN_INTERVAL_MS, RECOVER_MAX_COUNT)
    private val lineSwitchLimiter = RateLimiter(LINE_SWITCH_WINDOW_MS, LINE_SWITCH_MIN_INTERVAL_MS, LINE_SWITCH_MAX_COUNT)
    private val refetchLimiter = RateLimiter(REFETCH_WINDOW_MS, REFETCH_MIN_INTERVAL_MS, REFETCH_MAX_COUNT)

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = handlePlayerError(error)

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (released) return
            when (playbackState) {
                // 缓冲中：只有在"已经出过画面"之后才提示，否则起播那一瞬间会来回抖
                Player.STATE_BUFFERING -> if (lastState == LivePlayState.PLAYING) {
                    notifyState(LivePlayState.LOADING, "缓冲中…")
                }

                Player.STATE_READY -> notifyState(LivePlayState.PLAYING, null)

                Player.STATE_ENDED -> handleStreamEnded()

                else -> Unit
            }
        }

        /**
         * 播放/暂停状态。
         *
         * ★为什么要单独听这个回调：media3 在暂停时**不会**改变 `playbackState`
         *   （仍然是 `STATE_READY`），所以只靠 [onPlaybackStateChanged] 的话，
         *   用户点了暂停，UI 收不到任何通知 —— 播放/暂停按钮就只能"自己猜"，
         *   一旦别处（退后台 [LivePlayerActivity.onStop]、缓冲、换源）动了播放器就会显示错。
         * ★判据用 `playWhenReady` 而不是 [isPlaying]：缓冲/换源时 `isPlaying` 也短暂为 false，
         *   那属于"加载中"而不是"用户暂停"，拿它当暂停会让按钮在起播时乱跳。
         */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (released) return
            // ★诊断日志（只读）：**真实 isPlaying 变化**（media3 的唯一权威回调）
            LivePageTrace.note(
                "play.isPlayingChanged",
                "isPlaying" to isPlaying,
                "playWhenReady" to player.playWhenReady,
                "playbackState" to player.playbackState,
                "mediaItemLive" to runCatching { player.isCurrentMediaItemLive }.getOrDefault(false),
                "liveOffsetMs" to runCatching { player.currentLiveOffset }.getOrDefault(-1L),
            )
            if (!player.playWhenReady) {
                notifyState(LivePlayState.PAUSED, null)
            } else if (isPlaying) {
                notifyState(LivePlayState.PLAYING, null)
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (released) return
            if (videoSize.width > 0 && videoSize.height > 0) {
                listener?.onVideoSizeChanged(videoSize.width, videoSize.height)
            }
        }
    }

    init {
        player.addListener(playerListener)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 公开 API（全部要求主线程）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 起播（也用于"房间刚开播"和"下播后重新开播"）。
     *
     * @param qn 期望清晰度，默认 [LiveAPI.QUALITY_ORIGIN]（原画）。
     *           ★未登录时服务端只会给 250（超清），这是**静默降级**，不是我们的 bug ——
     *           UI 用 [LiveStreamInfo.actualQn] 如实显示"当前实际画质"。
     */
    fun start(qn: Int = LiveAPI.QUALITY_ORIGIN) {
        if (released) return
        requestedQn = qn
        load("正在获取直播流…")
    }

    /** 手动重试（用户点"重试"）：清空限流预算，立即重新取流 */
    fun retry() {
        if (released) {
            LivePageTrace.note("delegate.retry.blocked", "gate" to "released")
            return
        }
        // ★诊断日志（只读）：重新取流的**起**（结果见 onStreamReady / onIsPlayingChanged）
        LivePageTrace.note(
            "delegate.retry",
            "requestedQn" to requestedQn,
            "lineIndex" to lineIndex,
            "candidates" to candidates.size,
        )
        lineSwitchLimiter.reset()
        refetchLimiter.reset()
        behindLiveWindowLimiter.reset()
        requestedQn = if (requestedQn > 0) requestedQn else LiveAPI.QUALITY_ORIGIN
        load("正在重新获取直播流…")
    }

    /**
     * 切清晰度。
     * ★方案 §6.2：切画质 = **重新调 getRoomPlayInfo(qn=新值) + 换 MediaSource**（不是换 MediaItem），
     *   并且保留最后一帧（不 release 播放器）——[load] 正是这么做的。
     */
    fun switchQuality(qn: Int) {
        if (released || qn <= 0 || qn == requestedQn) {
            // ★诊断日志（只读）：切清晰度被拦下（released / 非法 qn / 与当前请求档相同）
            LivePageTrace.note(
                "delegate.switchQuality.blocked",
                "qn" to qn,
                "requestedQn" to requestedQn,
                "released" to released,
            )
            return
        }
        // ★诊断日志（只读）：切清晰度的**起**
        LivePageTrace.note("delegate.switchQuality", "qn" to qn, "fromQn" to requestedQn, "lineIndex" to lineIndex)
        requestedQn = qn
        load("正在切换清晰度…")
    }

    /** 手动切下一条线路（自动切换失败/用户想换 CDN 时用） */
    fun switchToNextLine() {
        if (released) return
        advanceLine(auto = false, reason = "正在切换线路…")
    }

    /**
     * 切到**指定**线路（线路弹窗里点某一条）。
     *
     * ★与 [advanceLine] 的区别：这是用户的明确选择，所以
     *   ① 不受自动切换的限流约束（[lineSwitchLimiter] 只拦自动切换）；
     *   ② 不走"候选耗尽就重新取流"那套兜底 —— 用户点的就是这一条，切不动就把错误如实报出来，
     *      而不是悄悄换成别的地址让他以为点错了。
     */
    fun switchToLine(index: Int) {
        if (released) {
            LivePageTrace.note("delegate.switchToLine.blocked", "index" to index, "gate" to "released")
            return
        }
        if (index !in candidates.indices || index == lineIndex) {
            // ★诊断日志（只读）：切线路被拦下（越界 / 点的是当前线路）
            LivePageTrace.note(
                "delegate.switchToLine.blocked",
                "index" to index,
                "lineIndex" to lineIndex,
                "candidates" to candidates.size,
            )
            return
        }
        // ★诊断日志（只读）：切线路的**起**
        LivePageTrace.note("delegate.switchToLine", "index" to index, "fromIndex" to lineIndex, "candidates" to candidates.size)
        lineIndex = index
        prepareCurrentLine("正在切换线路…")
    }

    fun play() {
        if (released) return
        // ★诊断日志（只读）：播放意图（真实状态变化见 onIsPlayingChanged）
        LivePageTrace.note("delegate.play", "playbackState" to player.playbackState)
        player.playWhenReady = true
        player.play()
    }

    fun pause() {
        if (released) return
        // ★诊断日志（只读）：暂停意图（真实状态变化见 onIsPlayingChanged）
        LivePageTrace.note("delegate.pause", "playbackState" to player.playbackState)
        player.playWhenReady = false
        player.pause()
    }

    val isPlaying: Boolean get() = !released && player.playWhenReady

    /**
     * ★第十二批（**后台只出声**）：关掉**视频轨**、只留声音；[audioOnly] = false 时把视频轨放回来。
     *
     * 调用点只有一个：`LivePlayerActivity.onStop()`（门控 = 「设置 → 直播设置 → 后台继续直播」
     * `live_background_play`，默认**关**）。默认关 ⇒ 本函数根本不会被调用 ⇒ 退后台仍是暂停。
     *
     * ## 为什么是"关轨道"，而不是"摘渲染面 / 关 VideoEffect"（本轮核对过字节码的结论，别再改回去）
     * 三种"关视频"的写法里，**只有 `setTrackTypeDisabled(TRACK_TYPE_VIDEO, true)` 能真的让
     * 视频解码器停下来**：
     * 1. **关轨道（本函数的做法，✓ 真省）**：轨道选择器不再选任何视频轨 →
     *    ExoPlayer 把视频渲染器置为 disabled → `MediaCodecRenderer.onDisabled()` →
     *    `flushOrReleaseCodec()`（media3 1.11.1 字节码核对过：`onDisabled()` 里那句就是它）
     *    ⇒ MediaCodec **释放**，一路视频一个字节都不再解码，CPU/功耗真的降下来；
     *    音频渲染器不受影响（改轨道选择只重算选择、不重建 MediaPeriod ⇒ 声音不中断、**不重新取流**）。
     * 2. **摘渲染面（✗ 不省）**：`setVideoTextureView(null)` / `clearVideoSurface()` 只是"不显示"——
     *    media3 会拿**占位面**把编解码器继续跑下去
     *    （`MediaCodecVideoRenderer` 里的 `placeholderSurface` / `shouldUsePlaceholderSurface` /
     *    `shouldUseDetachedSurface`，1.11.1 字节码里都在）⇒ 视频照旧解码，只是没人看。
     *    点播 `DanmakuVideoPlayer.setAudioOnly()`（`widget/player/DanmakuVideoPlayer.kt:723-742`）
     *    是"黑遮罩"那一档，同样不省解码；那里刻意不碰 surface 的理由是 GSY 会因面变化误暂停。
     * 3. **`setVideoEffects`（✗ 不相关）**：那是画面后处理（色彩/滤镜）入口，作用于**已经解出来的帧**，
     *    与"要不要解"无关。
     *
     * ## 为什么**不摘渲染面**（与旧的「听音频」实现的一处有意差别）
     * 旧实现是 `attachTextureView(null) + setAudioOnly(true)` 两件一起做。本轮**只关轨道、Surface 一直挂着**：
     * · 关轨道已经让解码器停了，摘面省不到第二份算力；
     * · 面一变，media3 就要走 `setOutputSurface` / 占位面那套回退，回前台重新挂面时多一次
     *   编解码器重配置（观感上就是"回前台黑一下"）；Surface 不动则 re-enable 直接复用现成的面，
     *   **没有面变化、没有重新取流**，画面回来的路径最短。
     * · TextureView 自己那条生命周期（面被销毁/重建）由 media3 的 `setVideoTextureView` 监听处理，
     *   与本函数无关。
     *
     * ## 防御（照抄被删的旧实现，理由没变）
     * 一条可用音频轨都没有时**不关视频轨**：那会把仅剩的可播轨道也掐掉，用户感受是"退后台就没声了"。
     * `currentTracks` 还是空（起播前）时按"有音频"处理 —— 直播实测常态，而且这个参数本来就是
     * "选择时"才生效。
     *
     * @return **本次调用之后**视频轨是否处于关闭状态（true = 现在只有声音）。
     *   ★Activity 必须拿这个返回值当状态：没有音频轨这种流上它返回 false，
     *   页面就不该以为自己进了"只出声"（回前台也就没有"接回画面"这回事）。
     */
    fun setAudioOnly(audioOnly: Boolean): Boolean {
        if (released) return false
        if (audioOnly == audioOnlyMode) return audioOnlyMode
        if (audioOnly) {
            val groups = player.currentTracks.groups
            val hasAudio = groups.isEmpty() ||
                groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
            if (!hasAudio) {
                miaoLogger() info "$TAG 该直播流没有可用音频轨：不关视频轨（退后台继续播，但照旧解码视频）"
                return false
            }
        }
        return runCatching {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
                .build()
            audioOnlyMode = audioOnly
            audioOnlyMode
        }.getOrElse { e ->
            miaoLogger() error "$TAG 切换视频轨失败（audioOnly=$audioOnly）：${e.javaClass.simpleName}: ${e.message}"
            audioOnlyMode
        }
    }

    /** 视频轨是否正被 [setAudioOnly] 关着（只读镜像，给 Activity/日志用） */
    val isAudioOnly: Boolean get() = audioOnlyMode

    /**
     * 把渲染面挂到 ExoPlayer 上（传 null 摘除）。
     *
     * ★为什么是 `TextureView` 而不是 `SurfaceView`（弹幕看不见的头号嫌疑，详见
     *   `LivePlayerActivity.buildUi()` 的图层注释）：SurfaceView 的画面在**独立图层**上，
     *   它和"叠在它上面的 View"之间的合成依赖窗口打洞 + 图层 Z 序，各 ROM 行为不完全一致；
     *   TextureView 就是把画面当普通 View 画进窗口缓冲，叠放顺序**由 View 树本身保证**。
     *   本工程其它视频渲染（点播 GSY、弹幕 DanmakuView）本来就都是 TextureView。
     */
    fun attachTextureView(view: TextureView?) {
        if (released) return
        runCatching { player.setVideoTextureView(view) }
    }

    /**
     * 干净释放：取消所有协程 + 摘掉 Surface + release 播放器。
     * ★顺序很重要：先 `setVideoTextureView(null)` 再 `release()`，否则渲染面可能被
     *   已经销毁的播放器持有，出现"Activity 都关了还在往 Surface 上画"的偶发崩溃。
     */
    fun release() {
        if (released) return
        released = true
        loadJob?.cancel()
        refreshJob?.cancel()
        scope.cancel()
        runCatching { player.removeListener(playerListener) }
        runCatching { player.setVideoTextureView(null) }
        runCatching { player.release() }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 取流
    // ══════════════════════════════════════════════════════════════════════

    private sealed interface FetchResult {
        /** 成功：拿到候选线路 */
        class Success(val snapshot: FetchSnapshot) : FetchResult

        /** 房间没开播/轮播：不是错误，别弹红字，转开播轮询 */
        class Offline(val liveStatus: Int) : FetchResult

        /** 真失败（网络、风控、解析） */
        class Failed(val message: String) : FetchResult
    }

    private class FetchSnapshot(
        val actualQn: Int,
        val qualities: List<LiveQualityOption>,
        val candidates: List<SourceCandidate>,
    )

    private fun load(reason: String) {
        loadJob?.cancel()
        loadJob = scope.launch {
            notifyState(LivePlayState.RESOLVING, reason)
            when (val result = fetchWithFallback(requestedQn)) {
                is FetchResult.Failed -> notifyState(LivePlayState.ERROR, result.message)

                is FetchResult.Offline -> notifyState(
                    LivePlayState.OFFLINE,
                    "房间未开播（live_status=${result.liveStatus}）",
                )

                is FetchResult.Success -> {
                    applySnapshot(result.snapshot)
                    // 新地址 = 新的一轮，所有限流预算都该清零
                    lineIndex = 0
                    lineSwitchLimiter.reset()
                    refetchLimiter.reset()
                    behindLiveWindowLimiter.reset()
                    notifyStreamReady()
                    prepareCurrentLine("起播中…")
                    startUrlCheckLoop()
                }
            }
        }
    }

    /**
     * 带**降级**的取流：按 [preferredQn] → 250 → 150 依次尝试，直到某一次真的拿到了线路。
     *
     * ★为什么需要降级：★实测未登录时 `qn=10000` 会**静默降级**成 250 并且照样返回候选
     *   （这种情况不需要重试，`current_qn` 会告诉我们真相）；
     *   真正会"一条候选都没有"的是 4K(20000)/杜比(30000) 这种要大会员的档位——
     *   那时必须退档，否则用户看到的是"取流失败"而不是"能看但画质低一档"。
     */
    private suspend fun fetchWithFallback(preferredQn: Int): FetchResult {
        val chain = buildList {
            add(preferredQn)
            if (preferredQn > LiveAPI.QUALITY_SUPER) add(LiveAPI.QUALITY_SUPER)
            if (preferredQn > LiveAPI.QUALITY_HIGH) add(LiveAPI.QUALITY_HIGH)
        }.distinct()
        var last: FetchResult = FetchResult.Failed("取流失败")
        for (qn in chain) {
            val result = fetchOnce(qn)
            if (result is FetchResult.Success) return result
            last = result
            // 房间没开播：降清晰度也没用，立刻返回，让 UI 转轮询
            if (result is FetchResult.Offline) return result
        }
        return last
    }

    private suspend fun fetchOnce(qn: Int): FetchResult {
        val res = try {
            withContext(Dispatchers.IO) {
                // isWebApi=true 已在 LiveAPI 内部设好；这里只是普通 GET，无需 WBI
                LiveAPI().playUrl(roomId.toString(), qn).call().json<ResponseData<LivePlayUrlInfo>>()
            }
        } catch (e: CancellationException) {
            // 协程取消必须原样抛出，否则会被下面的 catch 吞成"取流失败"
            throw e
        } catch (e: Exception) {
            miaoLogger() error "$TAG 取流异常 qn=$qn: ${e.javaClass.simpleName}: ${e.message}"
            return FetchResult.Failed("取流失败：${e.message ?: e.javaClass.simpleName}")
        }
        if (!res.isSuccess) {
            return FetchResult.Failed("取流失败：${res.message}（code=${res.code}）")
        }
        val data = res.data ?: return FetchResult.Failed("取流失败：服务端没有返回数据")
        val playurl = data.playurl_info?.playurl
        // ★实测（方案 §1）：轮播房 live_status=2 时 code=0 但 playurl_info 是 null ——
        //   只判 code 会把它当成"能播"，然后黑屏。这里必须判空并转 OFFLINE。
        if (playurl == null) {
            // ★"服务端没下发播放地址" ≠ "房间未开播"（2026-09-26 用户实测：真在播、几万人在看的房间
            //   `playurl` 也会是 null，而我们一律报"未开播" → 用户看到"明明在播却说没开播"）。
            //   按 live_status 把两种失败分开报，用户才知道该重试还是该放弃。
            // 用字面量 1 判断"正在直播"（LiveStatus 是播放页里的私有工具，本文件没有它的 import；
            // 这里只判"服务端自己说在播"，语义最小、不引依赖）。
            return if (data.live_status == 1) {  // live_status 是 Int（1 = 正在直播）
                FetchResult.Failed("服务端暂未下发播放地址（房间在播，稍后自动重试）")
            } else {
                FetchResult.Offline(data.live_status)
            }
        }

        val flatCandidates = buildCandidates(playurl)
        if (flatCandidates.isEmpty()) {
            miaoLogger() error "$TAG qn=$qn 没有任何候选线路（live_status=${data.live_status}）"
            return FetchResult.Failed("取流失败：qn=$qn 没有下发任何线路")
        }
        return FetchResult.Success(
            FetchSnapshot(
                actualQn = firstActualQn(playurl),
                qualities = buildQualities(playurl),
                candidates = flatCandidates,
            )
        )
    }

    /**
     * 把 `stream[] → format[] → codec[] → url_info[]` 展平成**播放线路候选表**并排序。
     *
     * URL 拼装直接用骨架里的 [fullUrls]（`host + base_url + extra` 纯字符串相加）——
     * ★绝不 encode/规范化：`base_url` 自带结尾 `?`，`extra` 里是服务端签发的防盗链凭据。
     */
    private fun buildCandidates(playurl: LivePlayUrl): List<SourceCandidate> {
        val list = ArrayList<SourceCandidate>()
        val seen = HashSet<String>()
        for (stream in playurl.stream) {
            for (format in stream.format) {
                for (codec in format.codec) {
                    for (url in codec.fullUrls) {
                        // 同一 codec 下实测有 2 条 url_info（gotcha07 / gotcha07b）＝天然 2 条线路；
                        // 跨 codec 理论上不会重复，但去重一次不亏（重复候选会让回退白等一轮）
                        if (!seen.add(url)) continue
                        list += SourceCandidate(
                            url = url,
                            protocolName = stream.protocol_name,
                            formatName = format.format_name,
                            codecName = codec.codec_name,
                            hostLabel = runCatching { Uri.parse(url).host ?: "" }.getOrDefault(""),
                        )
                    }
                }
            }
        }
        // 解析期排序（三级优先，抄 BiliPai LivePlaybackPolicy.kt:206-229 的结构）：
        // ★但协议级的次序与 BiliPai 相反 —— 方案 §3.4 明确建议第一版 **FLV 优先**
        //   （起播最快、Media3 支持最成熟、不需要 HLS 那套 parser 处理），
        //   HLS 里再 ts 先于 fmp4（fmp4 带 #EXT-X-START，第一版不剥离它，见方案 §3.2 第 3 点）。
        return list.sortedWith(
            compareBy<SourceCandidate> { protocolPriority(it.protocolName) }
                .thenBy { formatPriority(it.formatName) }
                .thenBy { codecPriority(it.codecName) }
        )
    }

    private fun protocolPriority(name: String): Int = when (name) {
        "http_stream" -> 0  // FLV
        "http_hls" -> 1
        else -> 2
    }

    private fun formatPriority(name: String): Int = when (name) {
        "flv" -> 0
        "ts" -> 1
        "fmp4" -> 2
        else -> 3
    }

    private fun codecPriority(name: String): Int = when (name) {
        "avc" -> 0   // ★hevc 部分设备解不了，一律 avc 优先（方案 §6.2）
        "hevc" -> 1
        else -> 2
    }

    /** 服务端这次**实际**下发的清晰度：★未登录时任何请求都只会得到 250 */
    private fun firstActualQn(playurl: LivePlayUrl): Int =
        playurl.stream.asSequence()
            .flatMap { it.format.asSequence() }
            .flatMap { it.codec.asSequence() }
            .firstOrNull { it.current_qn > 0 }
            ?.current_qn
            ?: 0

    /**
     * 清晰度菜单数据：优先用 `accept_qn`（房间能力），描述取自 `g_qn_desc`，按 qn 从高到低。
     * ★`accept_qn` 是"房间支持"，不等于"你能拿到"；"你能拿到"看 [LiveStreamInfo.actualQn]。
     */
    private fun buildQualities(playurl: LivePlayUrl): List<LiveQualityOption> {
        val descMap = playurl.g_qn_desc.associate { it.qn to it.desc }
        val acceptQn = playurl.stream.asSequence()
            .flatMap { it.format.asSequence() }
            .flatMap { it.codec.asSequence() }
            .firstOrNull { it.accept_qn.isNotEmpty() }
            ?.accept_qn
            .orEmpty()
        val qns = if (acceptQn.isNotEmpty()) acceptQn else descMap.keys.toList()
        return qns.filter { it > 0 }
            .distinct()
            .sortedDescending()
            .map { qn -> LiveQualityOption(qn, descMap[qn] ?: qn.toString()) }
    }

    private fun applySnapshot(snapshot: FetchSnapshot) {
        candidates = snapshot.candidates
        qualities = snapshot.qualities
        actualQn = snapshot.actualQn
        urlFetchedAtMs = SystemClock.elapsedRealtime()
        // 候选表变了 → 回退状态也要跟着换一份（CdnFailoverState 的 candidates 是不可变的）
        failoverState = CdnFailoverState(snapshot.candidates.map { Uri.parse(it.url) })
    }

    // ══════════════════════════════════════════════════════════════════════
    // 装源 / 换源
    // ══════════════════════════════════════════════════════════════════════

    private fun prepareCurrentLine(reason: String) {
        val candidate = candidates.getOrNull(lineIndex)
        if (candidate == null) {
            notifyState(LivePlayState.ERROR, "没有可用线路")
            return
        }
        notifyState(LivePlayState.LOADING, reason)
        // 让回退状态从"这一条"开始（FLV 的 open 级回退会自动往下试）
        failoverState?.prefer(lineIndex)
        val source = try {
            buildMediaSource(candidate)
        } catch (e: Exception) {
            notifyState(LivePlayState.ERROR, "起播失败：${e.message ?: e.javaClass.simpleName}")
            return
        }
        // ★方案 §3.4「无感」细节：换线路**不 release() 播放器**，只整源替换 + prepare，
        //   渲染面上保留最后一帧，用户感觉是"卡一下"而不是"黑屏重开"。
        runCatching { player.setMediaSource(source) }
        player.prepare()
        player.playWhenReady = true
        notifyStreamReady()
    }

    /**
     * 造 MediaSource —— **HLS / FLV 分流 + "哪条路能用多线路回退"** 的判断都在这里。
     *
     * 分流写法照方案 §3.2（= blbl `ExoPlayerEngine.kt:174-191`）：
     * ```
     * url.substringBefore('?').endsWith(".m3u8")   // ★必须先切 query，B 站 URL 带超长签名参数
     * ```
     * 不切 query 的话 `endsWith(".m3u8")` 永远是 false，会退化成"拿 FLV 解析器解 m3u8"。
     *
     * ## ★为什么 HLS 不能包多候选的 CdnFailoverDataSource（本文件最重要的一个决策）
     * `CdnFailoverDataSource.open()` 的实现是：
     * ```
     * val spec = dataSpec.buildUpon().setUri(candidates[idx]).build()   // 无条件替换 URI
     * ```
     * 对 **FLV 长连接**这是对的：整条流就是同一个资源，任何一次 open（含 Range 续传）
     * 换成"另一台 CDN 上的同一个文件"都成立 —— 这也正是点播里它只被用在
     * `ProgressiveMediaSource`（音视频整文件）上的原因（见 PlayerDelegate2.kt:801-802，
     * 而 `[dash-mpd]` 那条路**没有**用它）。
     * 对 **HLS** 就不成立：m3u8 与 .ts/.m4s 分片是**不同资源**，分片 URI 是由清单相对解析出来的，
     * 一旦被无条件改写成候选表里的 m3u8 地址，等于"把 m3u8 当分片拉" —— 必然解析失败。
     * 所以 HLS 这条路的线路回退交给**播放器错误码**驱动（[advanceLine]），
     * 复用的仍然是同一个 [failoverState]（它记录"当前哪条线路"）。
     */
    private fun buildMediaSource(candidate: SourceCandidate): MediaSource {
        val upstream = DefaultHttpDataSource.Factory()
            // ★★必须显式设 UA：实测空 UA / curl UA 会被 CDN 直接 403（方案 §2.2 防盗链表）
            .setUserAgent(DEFAULT_USER_AGENT)
            .setDefaultRequestProperties(mapOf("Referer" to "$LIVE_REFERER_PREFIX$roomId"))
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            // 读超时给 15s：直播是长连接，太短会把"网络抖一下"当成断流；
            // 太长则真断流时要等很久才触发回退
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            // ★实测 FLV 首跳是 302 跳到另一个域名；DefaultHttpDataSource 默认只允许同协议跳转，
            //   显式打开跨协议（http↔https）以免哪天 CDN 换了跳法直接开不了
            .setAllowCrossProtocolRedirects(true)

        val isM3u8 = candidate.url.substringBefore('?').trim().lowercase(Locale.US).endsWith(".m3u8")

        val factory: DataSource.Factory = if (isM3u8) {
            upstream
        } else {
            // 只在同一 group（protocol/format/codec 全同、只是 CDN host 不同）里做 open 级回退：
            // 跨封装回退会让"FLV 解析器"去解 m3u8，那还不如交给播放器错误码去换源
            val groupUrls = candidates.filter { it.groupKey == candidate.groupKey }.map { it.url }
            if (groupUrls.size <= 1) {
                upstream
            } else {
                // ★把"当前选中的这条"排到组内第一位：CdnFailoverState 是从 preferredIndex=0 开始试的，
                //   不排的话手动切到组内第 2 条也会被忽略（DS 开局就把 URI 改回第 1 条）。
                //   其余同组线路跟在后面，继续承担 open 级回退。
                val ordered = listOf(candidate.url) + groupUrls.filter { it != candidate.url }
                CdnFailoverDataSourceFactory(upstream, CdnFailoverState(ordered.map { Uri.parse(it) }))
            }
        }

        val mediaItem = MediaItem.Builder()
            .setUri(candidate.url)
            .setLiveConfiguration(liveConfiguration)
            .build()

        return if (isM3u8) {
            // HLS 显式造 HlsMediaSource（而不是让 DefaultMediaSourceFactory 去反射推断），
            // 这样将来要挂"剥离 #EXT-X-START 的 PlaylistParserFactory"（fmp4 用得上）有地方挂
            HlsMediaSource.Factory(factory).createMediaSource(mediaItem)
        } else {
            // FLV 走 DefaultMediaSourceFactory：它按 URI/Content-Type 选中 ProgressiveMediaSource，
            // 由内置 FlvExtractor 自动嗅探，**不需要手写 MimeTypes**（方案 §3.2 要点 1）
            DefaultMediaSourceFactory(factory).createMediaSource(mediaItem)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 错误恢复（错误码决策抄 BiliPai LivePlaybackPolicy.kt:44-65）
    // ══════════════════════════════════════════════════════════════════════

    private enum class Recovery { SEEK_TO_LIVE_EDGE, NEXT_LINE, NONE }

    private fun resolveRecovery(errorCode: Int, httpResponseCode: Int?): Recovery {
        // ★BEHIND_LIVE_WINDOW 是"时间轴/窗口"问题，不是"这条 URL 坏了" → 回 live edge，**不换线路**
        if (errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return Recovery.SEEK_TO_LIVE_EDGE
        if (httpResponseCode != null && httpResponseCode in HTTP_FAILOVER_CODES) return Recovery.NEXT_LINE
        return if (errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        ) {
            Recovery.NEXT_LINE
        } else {
            Recovery.NONE
        }
    }

    private fun handlePlayerError(error: PlaybackException) {
        if (released) return
        val httpCode = findHttpResponseCode(error)
        val recovery = resolveRecovery(error.errorCode, httpCode)
        miaoLogger() error "$TAG 播放错误 ${error.errorCodeName} http=$httpCode recovery=$recovery " +
            "line=${lineIndex + 1}/${candidates.size} autoReconnect=$autoReconnect autoLineSwitch=$autoLineSwitch"
        // ★「设置 → 直播设置 → 自动重连」关掉（`live_auto_reconnect=false`）：
        //   任何失败都**只提示、不自动恢复** —— 换线路、重取流、回 live edge 三条路一起停，
        //   否则用户会觉得"我明明关了它还在偷偷重连"。恢复入口只剩底栏「重试」。
        if (!autoReconnect) {
            notifyState(
                LivePlayState.ERROR,
                "播放失败：${error.errorCodeName}（已关闭「自动重连」，可点「重试」）",
            )
            return
        }
        when (recovery) {
            Recovery.SEEK_TO_LIVE_EDGE -> recoverBehindLiveWindow(error)
            Recovery.NEXT_LINE -> {
                // 地址快过期了（≈1h）就没必要再换线路了，直接换一份新签名地址
                if (isUrlStale()) {
                    relaunchLoad("播放地址已过期，正在重新取流…")
                } else {
                    advanceLine(auto = true, reason = "线路异常，正在切换…")
                }
            }

            Recovery.NONE -> notifyState(
                LivePlayState.ERROR,
                "播放失败：${error.errorCodeName}${error.message?.let { "（$it）" } ?: ""}",
            )
        }
    }

    /**
     * behind-live-window 自恢复，**带限流**（照 blbl `LivePlayerActivity.kt:1335-1340`）：
     * 15s 窗口内最多 2 次、两次之间至少隔 1200ms。
     * 不这么做就会掉进"seek → 又落后 → 再 seek"的死循环（方案 §3.4）。
     *
     * 预算用尽后不再死磕 seek，而是退一步换线路（blbl 第 3 次也是重设源）。
     */
    private fun recoverBehindLiveWindow(error: PlaybackException) {
        // 「自动重连」关掉时连"回 live edge"也不做：它同样是"失败后的自动恢复"，
        // 只提示原因，等用户点「重试」（那时会走 retry() 把限流预算清零重来）
        if (!autoReconnect) {
            notifyState(
                LivePlayState.ERROR,
                "直播落后过多（已关闭「自动重连」，可点「重试」）",
            )
            return
        }
        if (!behindLiveWindowLimiter.tryAcquire()) {
            miaoLogger() error "$TAG behind-live-window 恢复超预算，改为换线路"
            advanceLine(auto = true, reason = "直播落后过多，正在切换线路…")
            return
        }
        // ★不换线路：seekToDefaultPosition() 会跳到当前 live edge
        runCatching { player.seekToDefaultPosition() }
        player.prepare()
        player.playWhenReady = true
        notifyState(LivePlayState.LOADING, "正在回到直播最新进度…")
    }

    /** 从异常链里挖出 HTTP 状态码（HttpDataSource.InvalidResponseCodeException.responseCode） */
    private fun findHttpResponseCode(error: Throwable): Int? {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 8) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
            depth++
        }
        return null
    }

    private fun isUrlStale(): Boolean =
        urlFetchedAtMs > 0L && SystemClock.elapsedRealtime() - urlFetchedAtMs >= URL_TTL_MS

    /**
     * 切下一条线路。
     *
     * ★三条铁律：
     * 1. `lineIndex` 以 [CdnFailoverState] 记的"上次成功那条"为基准（open 级回退可能已经替我们换过了，
     *    不能再从旧下标往上加，否则会跳过还能用的线路）；
     * 2. 一轮（所有候选）试完 → **重新取流**（第 3 层恢复），而不是从头再循环一遍旧地址；
     * 3. 自动切换受 [lineSwitchLimiter] 限制，手动切换不受限（用户点了就切）。
     */
    private fun advanceLine(auto: Boolean, reason: String) {
        if (released) return
        // ★「默认线路策略 = 固定第一条线路」（`live_line_policy` = LIVE_LINE_POLICY_FIRST）：
        //   **自动**换线一律不做 —— 这正是那个设置项的用途（排查"到底哪条线路好"时，
        //   偷偷换到别的 CDN 会让排查结论失真）。
        //   能做的自愈只剩"重新取一份地址"（★实测地址有效期 ≈1h，这是固定线路时最常见的失败原因），
        //   而且它会从第 0 条重新起播，仍然满足"固定第一条"；预算用尽就如实报错。
        //   ★手点换线（switchToLine / switchToNextLine，auto=false）不受这里约束。
        if (auto && !autoLineSwitch) {
            if (refetchLimiter.tryAcquire()) {
                relaunchLoad("已固定线路 1，正在重新取流…")
            } else {
                notifyState(
                    LivePlayState.ERROR,
                    "当前线路不可用（已固定线路 1；可点「重试」或在设置里改「默认线路策略」）",
                )
            }
            return
        }
        if (candidates.isEmpty()) {
            relaunchLoad("正在重新获取直播流…")
            return
        }
        if (auto && !lineSwitchLimiter.tryAcquire()) {
            notifyState(LivePlayState.ERROR, "线路反复失败，已停止自动切换（可手动重试）")
            return
        }
        val current = (failoverState?.getPreferredIndex() ?: lineIndex).coerceIn(0, candidates.lastIndex)
        lineIndex = current
        if (lineIndex + 1 >= candidates.size) {
            // 一圈都试过了：★地址有效期只有 ≈1h，这时最可能的原因是签名过期，
            // 重新取一份新地址再来一轮（refetchLimiter 防止无限重取）
            if (refetchLimiter.tryAcquire()) {
                relaunchLoad("所有线路均失败，正在重新取流…")
            } else {
                notifyState(LivePlayState.ERROR, "所有线路都不可用，请稍后重试")
            }
            return
        }
        lineIndex += 1
        prepareCurrentLine(reason)
    }

    /**
     * 流"放完了"。
     * ★直播本不该有终点（方案 §3.5）：`STATE_ENDED + playWhenReady + 房间在播` = 异常结束。
     * 先当作"地址/连接失效"重连一次，超出预算就报 OFFLINE，交给 Activity 的开播轮询。
     */
    private fun handleStreamEnded() {
        if (released) return
        miaoLogger() error "$TAG 流已结束（可能已下播或地址失效），line=${lineIndex + 1}/${candidates.size}"
        // ★「自动重连」关掉：不自动重连、也不报 OFFLINE ——
        //   报 OFFLINE 会被 Activity 转成"开播轮询"（那也是一种自动恢复，用户没要）。
        //   这里如实报 ERROR：底栏会留「重试」按钮，用户想恢复就自己点。
        if (!autoReconnect) {
            notifyState(
                LivePlayState.ERROR,
                "直播流已中断（可能已下播；已关闭「自动重连」，可点「重试」）",
            )
            return
        }
        if (refetchLimiter.tryAcquire()) {
            relaunchLoad("直播流已中断，正在重连…")
        } else {
            notifyState(LivePlayState.OFFLINE, "直播可能已结束")
        }
    }

    /** 重新取流并从第 0 条线路起播（不 release 播放器） */
    private fun relaunchLoad(reason: String) {
        if (released) return
        loadJob?.cancel()
        loadJob = scope.launch {
            notifyState(LivePlayState.RESOLVING, reason)
            when (val result = fetchWithFallback(requestedQn)) {
                is FetchResult.Failed -> notifyState(LivePlayState.ERROR, result.message)
                is FetchResult.Offline -> notifyState(
                    LivePlayState.OFFLINE,
                    "房间未开播（live_status=${result.liveStatus}）",
                )

                is FetchResult.Success -> {
                    applySnapshot(result.snapshot)
                    lineIndex = 0
                    lineSwitchLimiter.reset()
                    behindLiveWindowLimiter.reset()
                    notifyStreamReady()
                    prepareCurrentLine("重连中…")
                }
            }
        }
    }

    /**
     * 地址体检：每 5 分钟看一次是否临近 1 小时有效期，临近就**悄悄**换一份新地址。
     *
     * 为什么是"悄悄"：正在播的那条连接不受影响（CDN 会话已建立），
     * 我们只是把候选表更新成新签名的地址 —— 等下一次出错（[advanceLine]/[relaunchLoad]）时
     * 用的就是新地址，而不是过期的旧签名。这样不会为了续期而打断用户看播。
     */
    private fun startUrlCheckLoop() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            while (isActive) {
                delay(URL_CHECK_INTERVAL_MS)
                if (released) return@launch
                if (!isUrlStale()) continue
                if (!refetchLimiter.tryAcquire()) continue
                val result = fetchWithFallback(requestedQn)
                if (result is FetchResult.Success) {
                    // 只更新候选表与清晰度表，**不动播放器**（保持画面连续）
                    val playingIndex = lineIndex
                    applySnapshot(result.snapshot)
                    lineIndex = playingIndex.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
                    miaoLogger() info "$TAG 播放地址已续期（${candidates.size} 条候选）"
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 状态上报
    // ══════════════════════════════════════════════════════════════════════

    private fun notifyState(state: LivePlayState, message: String?) {
        // 同一状态 + 同一句话重复上报没有意义（缓冲中会被 media3 反复触发）
        if (state == lastState && message == lastMessage) return
        lastState = state
        lastMessage = message
        listener?.onPlayStateChanged(state, message)
    }

    private fun notifyStreamReady() {
        val candidate = candidates.getOrNull(lineIndex) ?: return
        listener?.onStreamReady(
            LiveStreamInfo(
                roomId = roomId,
                requestedQn = requestedQn,
                actualQn = actualQn,
                actualQnDesc = qualities.firstOrNull { it.qn == actualQn }?.desc ?: actualQn.toString(),
                qualities = qualities,
                lineIndex = lineIndex,
                lineCount = candidates.size,
                lineDesc = describeLine(candidate),
                lines = candidates.mapIndexed { index, item ->
                    LiveLineOption(index = index, desc = describeLine(item), current = index == lineIndex)
                },
            )
        )
    }

    private fun describeLine(candidate: SourceCandidate): String {
        val protocol = when (candidate.protocolName) {
            "http_stream" -> "FLV"
            "http_hls" -> "HLS"
            else -> candidate.protocolName
        }
        val host = candidate.hostLabel.ifBlank { "未知CDN" }
        return "$protocol · ${candidate.formatName} · ${candidate.codecName} · $host"
    }

    /** 一条候选线路（protocol/format/codec 相同的若干 URL 属于同一个 group） */
    private class SourceCandidate(
        val url: String,
        val protocolName: String,
        val formatName: String,
        val codecName: String,
        val hostLabel: String,
    ) {
        val groupKey: String get() = "$protocolName/$formatName/$codecName"
    }

    /**
     * 极简限流器：滑动窗口 + 最小间隔 + 次数上限。
     * 逻辑与 blbl `tryRecoverBehindLiveWindow` 里的三个判断一一对应
     * （`LivePlayerActivity.kt:1335-1340`）。
     */
    private class RateLimiter(
        private val windowMs: Long,
        private val minIntervalMs: Long,
        private val maxCount: Int,
    ) {
        private var windowStartMs = 0L
        private var lastAtMs = 0L
        private var count = 0

        fun tryAcquire(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
            if (windowStartMs <= 0L || nowMs - windowStartMs > windowMs) {
                windowStartMs = nowMs
                count = 0
            }
            if (nowMs - lastAtMs < minIntervalMs) return false
            if (count >= maxCount) return false
            count++
            lastAtMs = nowMs
            return true
        }

        fun reset() {
            windowStartMs = 0L
            lastAtMs = 0L
            count = 0
        }
    }
}
