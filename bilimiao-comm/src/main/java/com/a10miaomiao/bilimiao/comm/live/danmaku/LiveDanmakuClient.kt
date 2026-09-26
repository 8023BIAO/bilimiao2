package com.a10miaomiao.bilimiao.comm.live.danmaku

import android.os.SystemClock
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.live.LiveAPI
import com.a10miaomiao.bilimiao.comm.live.LiveDanmakuSendAPI
import com.a10miaomiao.bilimiao.comm.live.entity.LiveDanmuInfo
import com.a10miaomiao.bilimiao.comm.live.entity.wsUrls
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.comm.utils.WbiSigner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 直播弹幕消息（第二阶段 B 路）。
 *
 * ★**A 路直接消费这个类型，字段与顺序已锁死，不要改**。
 *
 * 与点播弹幕的区别：直播弹幕是**流式到达**的，没有时间轴对齐，
 * 所以这里只带"这条弹幕本身的信息"，渲染由
 * `cn.a10miaomiao.bilimiao.compose.pages.live.LiveDanmakuOverlay` 决定。
 */
sealed interface LiveMessage {

    companion object {
        /** 滚动弹幕（普通弹幕，绝大多数是它） */
        const val MODE_SCROLL = 1

        /** 底部固定弹幕 */
        const val MODE_BOTTOM = 4

        /** 顶部固定弹幕 */
        const val MODE_TOP = 5
    }

    /**
     * 普通弹幕（`DANMU_MSG`）。
     *
     * 字段来源（方案 §2.5 实测逐下标）：
     * `info[1]`=文本、`info[2][0]`=uid、`info[2][1]`=昵称、`info[0][3]`=颜色、
     * `info[0][4]`=时间戳ms、`info[3]`=粉丝牌（[0]=等级 [1]=牌子名）、`info[4][0]`=用户等级。
     */
    data class Danmaku(
        /** 已限长 + 已过滤换行/控制字符（见 [LiveDanmakuClient] 里的清洗） */
        val text: String,
        val uid: Long,
        val uname: String,
        /** `0xRRGGBB`，**不含 alpha**；渲染时自己补 `0xFF` 前缀 */
        val color: Int,
        /** 弹幕时间戳（毫秒，已归一化） */
        val timeMs: Long,
        /** 粉丝牌名字；没有牌子时为 null */
        val medalName: String?,
        val medalLevel: Int,
        val userLevel: Int,
        /**
         * 弹幕模式：`1/2/3`=滚动、`4`=底部、`5`=顶部（`info[0][1]`，方案 §2.5 实测该下标=1）。
         *
         * ★第三阶段补的字段（原来受"字段锁死"约束没有带出来，导致顶部/底部弹幕只能按滚动画）。
         * 取值非法时归一成 [MODE_SCROLL]，渲染侧不用再判空。
         */
        val mode: Int = MODE_SCROLL,
        /**
         * 粉丝牌颜色 `0xRRGGBB`（`info[3][4]`，实测与 `info[0][15].user.medal.color` 一致）；
         * 没有牌子 / 解析不到时 0。
         */
        val medalColor: Int = 0,
        /** 是否房管（`info[2][2]`，方案 §2.5 实测该下标=是否房管） */
        val isAdmin: Boolean = false,
        /**
         * 表情弹幕的图片 URL（`info[0][13]`）。
         *
         * ★实测该位置平时是**字符串** `"{}"`（不是对象！），只有表情弹幕才是一个含 `url` 的 JSON 串，
         *   所以这里用"必须是 `{` 开头 + 解析出 `url` + 必须是 http(s)"三重判据，宁可不解析也不解析错。
         */
        val emoticonUrl: String? = null,
    ) : LiveMessage

    /** 醒目留言（`SUPER_CHAT_MESSAGE`）。[price] 单位是元（B 站原字段就是元） */
    data class SuperChat(val uname: String, val text: String, val price: Int) : LiveMessage

    /** 礼物（`SEND_GIFT`）。[num] 是本次数量（不是总价） */
    data class Gift(val uname: String, val giftName: String, val num: Int) : LiveMessage

    /** 人气值：来自 op=3 心跳回复的 body 前 4 字节（方案 §2.4 操作码表） */
    data class Popularity(val value: Int) : LiveMessage

    /** "看过"人数变化（`WATCHED_CHANGE`）：`data.num` + `data.text_large`（方案 §2.5 实测） */
    data class Watched(val num: Int, val text: String) : LiveMessage

    /**
     * 直播状态变化。
     * [status] 取值见 [LiveDanmakuClient.LIVE_STATUS_PREPARING] /
     * [LiveDanmakuClient.LIVE_STATUS_LIVE] / [LiveDanmakuClient.LIVE_STATUS_STOPPED]。
     */
    data class LiveStatus(val status: Int) : LiveMessage
}

/**
 * 弹幕连接状态机。
 *
 * - [Idle]：初始 / [LiveDanmakuClient.close] 之后
 * - [Connecting]：首次 `connect()` 中（取 token / 握手 / 等 op=8）
 * - [Connected]：**认证成功**（收到 op=8 且 code=0）——不是"TCP 连上了"
 * - [Reconnecting]：掉线后正在按退避重连
 * - [Failed]：不可自动恢复（风控 / 房间异常 / 认证连续失败），需要 A 路提示用户并重新 [LiveDanmakuClient.connect]
 */
enum class ConnState { Idle, Connecting, Connected, Reconnecting, Failed }

/**
 * B 站直播弹幕 WebSocket 客户端（第二阶段 B 路）。
 *
 * 职责与状态机（方案 §4.1）：
 * ```
 * connect() ──► getDanmuInfo(WBI 签名) ──► host_list 去重后依次连 wss://{host}:{wss_port}/sub
 *                                        │
 *                         onOpen ──► op=7 认证（6s 没等到 op=8 判超时）
 *                                        │
 *                         op=8 code=0 ──► Connected + 每 30s op=2 心跳
 *                                        │
 *              每 5s 健康检查：距上次收到**任意服务端帧** > 75s → 判死重连
 *                                        │
 *              退避 1/2/4/8/10s，换下一台 host，直到成功（或判 Failed）
 * ```
 *
 * ★三条最容易踩的坑（都已在实现里堵住）：
 * 1. **不能只看"有没有弹幕"判活**：实测有的房间 20s 一条弹幕都没有（方案 §6.5），
 *    所以健康检查看的是"任意服务端帧"（op=3 / op=8 也算）。
 * 2. **必须有重复重连防护**：`onFailure` / `onClosing`+`onClosed` / 认证超时 / 健康检查
 *    会几乎同时触发，不做闸门就会出现多条重连链路互相打断（方案 §4.1 要求照 blbl
 *    `LiveMessageClient.kt:140` 的 `reconnectTask?.isDone == false` 做法）。
 * 3. **弹幕流绝不阻塞 WS 读线程**：`onMessage` 只把原始帧塞进有界 Channel（满了丢最旧），
 *    解包 + JSON 解析在独立协程里做。
 *
 * @param roomId **真实房间号**（不是短号；短号→真实号要走 `room_init`，见 `LiveAPI.roomInit`）
 */
class LiveDanmakuClient(private val roomId: Long) {

    // ------------------------------------------------------------------
    // 对外接口（A 路调用；签名已锁死）
    // ------------------------------------------------------------------

    init {
        // 「发弹幕」入口：登记最近实例，close() 时注销（见 companion 里的 lastClient 注释）
        register(this)
    }

    private val _messages = MutableSharedFlow<LiveMessage>(
        replay = 0,
        // ★需求下限 64；直播热门房实测峰值可达每秒数十条（方案 §6.5），这里给 128 留突发余量。
        //   配合 DROP_OLDEST：UI 跟不上就丢**旧**的，绝不反压到 WS 读线程（方案 §4.1 关键约束）
        extraBufferCapacity = MESSAGE_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 归一化后的弹幕消息流（SharedFlow 支持多个订阅者：页面和弹幕浮层能同时收） */
    val messages: SharedFlow<LiveMessage> = _messages.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnState.Idle)
    val connectionState: StateFlow<ConnState> = _connectionState.asStateFlow()

    /**
     * 最近一次 [sendDanmaku] 失败的原因（成功时为 null）。
     *
     * ★为什么要有它：需求要求"失败 false + 原因可读、方便 toast"，
     *   而 `sendDanmaku` 只回 Boolean（签名锁死），所以原因走这个字段。
     *   典型值：`发送失败(-101)：账号未登录`、`弹幕最多 40 字（当前 57 字）`、`网络异常：timeout`。
     */
    @Volatile
    var lastSendError: String? = null
        private set

    /**
     * 建立连接。**不抛异常**：所有失败都通过 [connectionState] 暴露
     * （A 路通常在 `viewModelScope.launch {}` 里直接调，抛异常会把协程打崩）。
     *
     * 重复调用是安全的：已在 `Connecting/Connected/Reconnecting` 时直接忽略；
     * `Failed` 或 `close()` 之后可以再次调用来重试 / 复用同一个实例。
     *
     * ★唯一的例外是**协程取消**：取消会原样抛出（那是调用方自己要求的，不该被吞）。
     *   但抛出前会把状态从 `Connecting` 收回来 —— 见方法末尾的注释，否则状态机会被永久卡死。
     */
    suspend fun connect() {
        val state = _connectionState.value
        if (state == ConnState.Connected || state == ConnState.Reconnecting) {
            log("connect() 重复调用已忽略（当前状态=$state）")
            return
        }
        if (state == ConnState.Connecting) {
            // ★"Connecting 卡死"闸门：正常情况下 Connecting 最长只活 15s（握手超时）。
            //   如果它活了超过 [CONNECTING_STALE_MS]，说明上一次调用被取消/异常路径漏掉了状态回收，
            //   这时**必须**允许重新进入，否则每一次 connect() 都会被这句挡掉 —— 就是"永远不会自己好"。
            val age = nowMs() - connectingSinceMs
            if (age in 0 until CONNECTING_STALE_MS) {
                log("connect() 重复调用已忽略（当前状态=$state，已持续 ${age}ms）")
                return
            }
            log("上一次 Connecting 已卡住 ${age}ms（超 ${CONNECTING_STALE_MS}ms）→ 强制重新连接")
        }
        closed = false
        if (!scope.isActive) {
            // close() 里 cancel 过 scope：允许同一个实例被复用（A 路页面重建时可能不 new 新对象）
            scope = newScope()
        }
        reconnectAttempt = 0
        authFailCount = 0
        hostIndex = 0
        // ★新增（历史/昵称这条路）：每次 connect() 重新读一次"我是谁"（可能刚登录/刚换号）
        selfUidCache = -1L
        // ★每次 connect() 都重取 token（方案 §2.3：token 会过期；blbl 也是在 connect() 内部取）
        token = ""
        tokenFetchedAtMs = 0L
        hosts = emptyList()
        forceTokenRefresh = true
        // 新会话：把可观测性计数器清零（浮层/trace 文件都读它）
        LiveDanmakuTrace.reset(roomId)

        _connectionState.value = ConnState.Connecting
        connectingSinceMs = nowMs()
        noteState("开始连接（roomId=$roomId）")
        startDecodeLoop()
        startHealthCheck()
        try {
            when (connectOnce()) {
                INFO_OK -> Unit // 握手结果由 Listener 回调推进（onOpen → op=7 → op=8）
                INFO_FATAL -> failPermanently(infoFailureReason)
                else -> scheduleReconnect("首次获取弹幕服务器信息失败（网络）")
            }
        } catch (ce: CancellationException) {
            // 调用方协程被取消（宿主 Compose 的 LaunchedEffect 在切页面/关弹幕时就会取消它）。
            // ★不清状态就会永远停在 Connecting：之后所有 connect() 都被"重复调用"闸门挡掉，
            //   表现就是"一条弹幕都没有，而且永远不会自己好"。这里回收到 Idle（close() 已关则不动）。
            if (!closed) {
                _connectionState.value = ConnState.Idle
                LiveDanmakuTrace.connectionText = "Idle"
                LiveDanmakuTrace.note("connect() 被取消 → 状态回 Idle（可再次 connect）")
            }
            throw ce
        }
    }

    /**
     * 发送一条弹幕（"发弹幕按钮"的能力侧）。
     *
     * ## 契约（A 路按这个调，签名锁死）
     * - 成功 `true`；失败 `false`，并且把**可读原因**写进 [lastSendError]（直接拿去 toast 即可）。
     * - **不抛异常**：网络异常、未登录、长度超限、敏感词被拒……全部变成 `false` + 原因。
     * - 与弹幕连接状态**解耦**：发送走 HTTP（`/msg/send`），断线也能发（服务端消息会经 WS 回来）。
     *
     * @param text 用户输入的原文（内部会做换行/控制字符清洗；**超长不静默截断，直接拒绝并说明**）
     */
    suspend fun sendDanmaku(text: String): Boolean {
        val cleaned = sanitizeSendText(text)
        if (cleaned == null) return false // 原因已写进 lastSendError
        // 防连点：上一次还在飞就直接拒绝（否则双击会真的发两条）
        if (!sending.compareAndSet(false, true)) {
            lastSendError = "上一条弹幕还在发送中，请稍候"
            return false
        }
        return try {
            val result = withContext(Dispatchers.IO) { sendApi.send(roomId, cleaned) }
            if (result.ok) {
                lastSendError = null
                LiveDanmakuTrace.lastSendError = null
                LiveDanmakuTrace.note("发送弹幕成功（${cleaned.length} 字）")
                echoSentDanmaku(cleaned)
                true
            } else {
                // setSendError 内部已经写了 trace（含 code/message），这里不重复记
                setSendError("发送失败(${result.code})：${result.message}")
                false
            }
        } catch (ce: CancellationException) {
            throw ce // 调用方取消，不是"发送失败"
        } catch (t: Throwable) {
            // 兜底：HTTP 层已经吞过异常，这里再兜一层，保证"UI 永远拿不到异常"
            setSendError("发送异常：${t.message ?: t::class.java.simpleName}")
            false
        } finally {
            sending.set(false)
        }
    }

    /**
     * ★**进房先拉一批"最近的弹幕"**（第四个需求，用户原话："点进去发现有最近的弹幕或者评论……
     * 我进去一脸懵，人家最近在讨论什么我都不知道"）。
     *
     * ## 接口与出处（照竞品 PiliPlus 抄，行号见 [LiveDanmakuHistoryAPI] 的类注释）
     * `GET https://api.live.bilibili.com/xlive/web-room/v1/dM/gethistory?roomid=&room_type=0`
     * —— PiliPlus `lib/http/api.dart:324-326` + `lib/http/live.dart:127-150`，
     * 它在 `lib/pages/live_room/controller.dart:466-473`（`startLiveMsg()`）里
     * **进房一次**（`if (messages.isEmpty) prefetch()`）。
     *
     * ## 契约（调用方只需要记这一条）
     * **失败静默**：任何异常 / 非 0 业务码 / 空数据都返回 `emptyList()`，只在 [LiveDanmakuTrace]
     * 里留一行（release 也能取证）。它**绝不**影响实时链路 —— 本类的连接、重连、心跳、
     * 去重、发送没有一行因为历史弹幕而改变。
     *
     * ## 为什么不直接 `_messages.tryEmit(...)` 塞进实时流
     * 1. **滚动弹幕层会把这些旧弹幕当新弹幕飞一遍** —— 用户要的是"铺进竖屏列表"，
     *    不是"一进房先刷一屏几分钟前的旧弹幕"；
     * 2. `_messages` 是 `replay = 0` 的 `SharedFlow`：浮层还没订阅时发出去的消息会**静默丢失**，
     *    而"拉历史"恰好就发生在进房那一瞬（正是这个空档）。
     * 所以由宿主拿到列表后直接铺进 `LiveDanmakuChatLog`（见 `LiveDanmakuOverlayHost`）。
     *
     * ## 幂等
     * 每个客户端实例最多发起一次（[historyAttempt]）；**网络类失败会放开重试**，
     * 免得"进房那一下正好断网"就把这个功能永久废掉。
     *
     * @return **按时间升序**（旧 → 新）的历史弹幕；失败/空时是空列表
     */
    suspend fun fetchHistory(): List<LiveMessage.Danmaku> {
        if (!historyAttempt.compareAndSet(false, true)) {
            log("历史弹幕：本实例已拉取过，忽略本次调用")
            return emptyList()
        }
        val result = try {
            historyApi.fetch(roomId)
        } catch (ce: CancellationException) {
            historyAttempt.set(false) // 取消不算"拉过"：页面重建后还会再来
            throw ce
        } catch (t: Throwable) {
            // 兜底：HTTP 层已经吞过异常，这里再兜一层（保证调用方永远拿不到异常）
            historyAttempt.set(false)
            markHistory("异常：${t::class.java.simpleName}: ${t.message}")
            return emptyList()
        }
        if (!result.ok) {
            // 网络类失败允许下次再试；业务拒绝（风控/未登录）不重试，别去撞风控
            if (result.code == LiveDanmakuHistoryAPI.CODE_NETWORK) historyAttempt.set(false)
            markHistory("失败 code=${result.code} ${result.message}")
            return emptyList()
        }

        val selfUid = currentUid()
        val items = ArrayList<LiveMessage.Danmaku>(result.items.size)
        for (item in result.items) {
            if (items.size >= MAX_HISTORY_ITEMS) break
            val text = sanitizeText(item.text)
            if (text.isEmpty()) continue
            // ★顺手学"我叫什么"：历史里**我自己那条**带着真实昵称（最权威且零成本，
            //   见 LiveSelfNickname）；本地回显就不再是"我"了
            if (selfUid != 0L && item.uid == selfUid && item.uname.isNotBlank()) {
                LiveSelfNickname.learn(item.uid, item.uname)
            }
            // 与实时链路**共用同一套指纹**：铺底后 2s 内 WS 把同一条再推回来会被丢掉，
            // 不会出现"同一句话在列表里出现两遍"（历史与实时的重叠期只有这么长）
            if (isDuplicate(item.uid, item.uname, text)) continue
            items += LiveMessage.Danmaku(
                text = text,
                uid = item.uid,
                uname = item.uname,
                // 历史接口不返回颜色/粉丝牌/用户等级：给渲染侧的默认值（列表里就是"昵称：内容"）
                color = DEFAULT_COLOR,
                timeMs = item.timeMs,
                medalName = null,
                medalLevel = 0,
                userLevel = 0,
                mode = LiveMessage.MODE_SCROLL,
                isAdmin = item.isAdmin,
            )
        }
        markHistory(if (items.isEmpty()) "空（接口没给数据）" else "成功 ${items.size} 条")
        // 契约：给调用方的是**时间顺序**（旧 → 新）；列表内部"新的在 index 0"由 addHistory 反向追加
        return items.sortedBy { it.timeMs }
    }

    /**
     * 预热"我自己叫什么"（[LiveSelfNickname]）：本机缓存 → `nav`，失败静默。
     *
     * 与 [fetchHistory] 分开、且**先后有序**：历史是用户看得见的东西，先拉它；
     * 昵称只在自己发弹幕时才用得到，晚一两百毫秒无所谓。
     * ★不放进 [connect]：那条路是"连接/重连语义"，本需求只做加法，不往里塞任何东西。
     */
    suspend fun warmUpSelfNickname(): String? = try {
        LiveSelfNickname.resolve()
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        null
    }

    /** 历史弹幕这一路的统一出口（内存字段 + trace 文件；屏幕上不会因此弹任何东西） */
    private fun markHistory(state: String) {
        LiveDanmakuTrace.historyState = state
        LiveDanmakuTrace.note("历史弹幕：$state")
    }

    /**
     * 释放：取消所有协程 + 主动 `close(1000)` 关闭 WS。
     *
     * ★用 1000（正常关闭）而不是 `cancel()`：让服务端知道是我们主动退出，频繁重连时对风控更友好。
     * 之后同一个实例仍可再次 [connect]（scope 会重建）。
     * ★不 shutdown 进程级的 OkHttp（见 [httpClient] 注释），这里释放的只是本连接。
     */
    fun close() {
        closed = true
        generation.incrementAndGet() // 让所有在途帧 / 迟到回调立刻失效
        stopAll()
        decodeJob?.cancel()
        decodeJob = null
        scope.cancel()
        _connectionState.value = ConnState.Idle
        // 注销「最近实例」：只清指向自己的那一个（避免把别人刚建好的顶掉）
        if (lastClient === this) register(null)
        LiveDanmakuTrace.connectionText = "Idle"
        LiveDanmakuTrace.note("弹幕客户端已释放 roomId=$roomId")
        log("弹幕客户端已释放 roomId=$roomId")
    }

    // ------------------------------------------------------------------
    // 内部状态
    // ------------------------------------------------------------------

    /** ★可能被 connect()（A 路协程，通常是主线程）重建，而 onFailure/健康检查在别的线程读它 */
    @Volatile
    private var scope = newScope()

    @Volatile
    private var closed = false

    @Volatile
    private var authed = false

    @Volatile
    private var ws: WebSocket? = null

    /** 最近一次收到**任意**服务端帧的时间（健康检查的唯一判据） */
    @Volatile
    private var lastServerFrameAtMs = 0L

    /**
     * ★重连闸门：等价 blbl 的 `reconnectTask?.isDone == false`。
     * 用普通 @Volatile 布尔而不是 `Job.isActive`，是因为重连协程体内部自己也会再排下一次，
     * 用 Job 判活会在"任务体内部"永远为真，把重连链掐死。
     */
    @Volatile
    private var reconnectPending = false

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var heartbeatJob: Job? = null

    @Volatile
    private var healthJob: Job? = null

    @Volatile
    private var authTimeoutJob: Job? = null

    @Volatile
    private var connectTimeoutJob: Job? = null

    @Volatile
    private var decodeJob: Job? = null

    /**
     * `Failed` 之后的自愈任务（见 [scheduleRevive]）。
     *
     * ★为什么要它：`Failed` 原本是"永久放弃、等 A 路手动 connect()"。但宿主（`LiveDanmakuOverlayHost`）
     *   的 `LaunchedEffect(active)` key 只有 `active` 一个 —— 一旦连接失败，key 不变就**再也不会**调
     *   `connect()`，而 `active` 从 true 再置 true 也不会触发重组。两边一叠加就是用户实测的
     *   "一条弹幕都没有，而且永远不会自己好"。所以自愈必须由客户端自己兜。
     */
    @Volatile
    private var reviveJob: Job? = null

    /** 进入 `Connecting` 的时刻：用来识别"Connecting 卡死"（见 [connect]） */
    @Volatile
    private var connectingSinceMs = 0L

    /** 发送互斥：防连点导致同一条弹幕发两遍 */
    private val sending = AtomicBoolean(false)

    /** 发送接口（HTTP `/msg/send`，与 WS 收弹幕完全解耦） */
    private val sendApi = LiveDanmakuSendAPI()

    /** 历史弹幕接口（HTTP `dM/gethistory`，纯加法：只被 [fetchHistory] 用，与 WS 完全解耦） */
    private val historyApi = LiveDanmakuHistoryAPI()

    /** [fetchHistory] 的一次性闸门（见那里的"幂等"一节） */
    private val historyAttempt = AtomicBoolean(false)

    /**
     * "我自己是谁"的只读缓存（uid，-1 = 还没取过）。
     *
     * ★为什么需要它：[currentUid] 每次都要问 CookieManager，而"顺手学自己昵称"
     * （见 [parseDanmaku]）要对**每一条弹幕**比对 uid —— 热门房每秒几十条，
     * 不能每条都去做一次 Cookie 查询。登录态变化由 [connect] 重置。
     */
    @Volatile
    private var selfUidCache = -1L

    @Volatile
    private var hosts: List<String> = emptyList()

    @Volatile
    private var hostIndex = 0

    @Volatile
    private var token = ""

    @Volatile
    private var tokenFetchedAtMs = 0L

    /** 下一次取信息时是否**强制**重取 token（认证失败 / token 过期时置位） */
    @Volatile
    private var forceTokenRefresh = true

    /**
     * 最近一次 `getDanmuInfo` 失败的可读原因（含服务端 code 与"签名有没有签上"）。
     *
     * ★为什么要有它：`failPermanently` 原来收的是一句写死的"被业务拒绝"，
     *   于是屏幕上、用户截图里都只有那一句 —— 风控 -352 / 未登录 -101 / 网关 5xx
     *   长得一模一样，拿回来也没法定位（这一轮真机排查就卡在这）。
     *   现在把真实 code + 人话 + 签名状态写进去，截图本身就能定性。
     */
    @Volatile
    private var infoFailureReason = "首次获取弹幕服务器信息被业务拒绝"

    @Volatile
    private var reconnectAttempt = 0

    @Volatile
    private var authFailCount = 0

    /** 带 uid 认证被拒后降级为匿名（uid=0）——实测未登录同样能认证成功（方案 §2.4） */
    @Volatile
    private var anonymousOnly = false

    private val seq = AtomicInteger(1)

    /** 连接代次：每次 openSocket 自增，用来丢弃"上一条连接"的迟到回调与残留帧 */
    private val generation = AtomicInteger(0)

    /** 原始帧队列：okhttp 读线程只做 trySend（非阻塞），解包在独立协程里 */
    private val frames = Channel<IncomingFrame>(
        capacity = FRAME_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 弹幕去重窗口。
     *
     * ★第三阶段起**解码协程与发送路径都会碰它**（自己发的弹幕要就地登记指纹，
     *   否则 WS 把同一条推回来时会重复上屏），所以改成 [dedupLock] 保护的共享状态。
     */
    private val recentDanmaku = ArrayDeque<Pair<String, Long>>()

    /** [recentDanmaku] 的锁：只在"查一次 / 加一条"这种微秒级操作里持有，不构成热路径瓶颈 */
    private val dedupLock = Any()

    /** 最近一次**自己发出**的弹幕文本与时刻：服务端把同一条推回来时用来抑制重复上屏 */
    @Volatile
    private var selfSentText: String? = null

    @Volatile
    private var selfSentAtMs = 0L

    // ------------------------------------------------------------------
    // 建连 / 重连
    // ------------------------------------------------------------------

    /** 一次建连尝试：确保有 token/host，然后开 socket。返回 INFO_* */
    private suspend fun connectOnce(): Int {
        val info = ensureConnectionInfo()
        if (info != INFO_OK) return info
        openSocket()
        return INFO_OK
    }

    /**
     * 确保 token / host 列表可用（必要时重新 `getDanmuInfo`）。
     *
     * ★为什么不是每次重连都重取：`getDanmuInfo` 是**必须 WBI 签名**的接口，
     * 不签名 / 高频一律 `-352` 风控（方案 §2.3、§6.1）。退避重连最短 1s 一次，
     * 每次都打这个接口就是在制造风控。所以策略是：
     * - 第一次连接、以及每次显式 `connect()`：**必取新 token**；
     * - 重连：token 还在 [TOKEN_MAX_AGE_MS] 内就复用；
     * - 认证失败过（token 很可能已过期 / 失效）：强制重取。
     *
     * @return [INFO_OK] 可以继续建连；[INFO_RETRY] 网络抖动可退避重试；
     *         [INFO_FATAL] 业务码非 0（风控）/ 数据不可用，不再自动重试
     */
    private suspend fun ensureConnectionInfo(): Int {
        val cached = token.isNotBlank() && hosts.isNotEmpty()
        val fresh = cached && !forceTokenRefresh && (nowMs() - tokenFetchedAtMs) < TOKEN_MAX_AGE_MS
        if (fresh) return INFO_OK

        // 本次取信息的"现场"：失败时要靠这几样定性（HTTP 码 / 响应体 / 签名到底有没有拼上）。
        // ★必须声明在 try **外面** —— 下面"返回体不可用"那条分支在 try 之后也要读它们
        var httpCode = 0
        var bodyHead = ""
        var signed = false
        val info: LiveDanmuInfo? = try {
            // ★必须切到 IO：danmuInfo() 内部会走 WbiSigner.signUrlBlocking（阻塞式签名，
            //   首次要打一次 nav 接口），留在主线程上就是 NetworkOnMainThreadException
            val res = withContext(Dispatchers.IO) {
                val http = LiveAPI().danmuInfo(roomId.toString())
                // ★先看签名，再发请求：URL 里没有 w_rid 时服务端**必然**回 -352，
                //   光看业务码分不清"签名压根没拼上"和"签名有效但被风控"（两者的处置完全不同）。
                signed = http.url?.contains("w_rid=") == true
                if (!signed) {
                    LiveDanmakuTrace.note("getDanmuInfo 未签名（URL 里没有 w_rid）→ ${WbiSigner.describeState()}")
                }
                val response = http.awaitCall()
                httpCode = response.code
                // peekBody 不消费响应体，后面还能正常 json()；只留前 512 字节，够定性了
                bodyHead = runCatching { response.peekBody(512).string() }.getOrDefault("")
                response.json<ResponseData<LiveDanmuInfo>>()
            }
            if (!res.isSuccess) {
                val why = describeBusinessFailure(res.code, res.message)
                val signText = if (signed) "已签" else "未签（没取到 WBI keys）"
                log("getDanmuInfo 业务失败 HTTP=$httpCode code=${res.code} message=${res.message} 签名=$signText 响应=${bodyHead.take(200)}")
                // 把"服务端 code + 人话 + 签名状态 + 响应体原文"一次写全：
                // 上一版只写了 code/message，用户截图里只有一句"业务拒绝"，完全没法定位
                LiveDanmakuTrace.note(
                    "getDanmuInfo 被拒 HTTP=$httpCode code=${res.code}｜$why｜签名=$signText｜body=${bodyHead.take(160)}"
                )
                infoFailureReason = "getDanmuInfo ${why}｜签名=$signText"
                // 拿旧 token 兜一把：可能只是刷新被风控，旧 token 其实还有效
                if (cached) {
                    log("沿用旧 token 继续建连（len=${token.length}）")
                    return INFO_OK
                }
                return INFO_FATAL
            }
            res.data
        } catch (ce: CancellationException) {
            // ★协程取消不是"网络抖动"：必须原样抛出，否则会破坏结构化并发，
            //   还会顺手排一次重连（调用方明明已经不要这个连接了）
            throw ce
        } catch (t: Throwable) {
            log("getDanmuInfo 请求异常（当作网络抖动，稍后重试）", t)
            LiveDanmakuTrace.note("getDanmuInfo 请求异常：${t::class.java.simpleName}: ${t.message}")
            if (cached) {
                log("沿用旧 token 继续建连（len=${token.length}）")
                return INFO_OK
            }
            return INFO_RETRY
        }

        // wsUrls 已经 distinctBy { host:wss_port }（方案 §2.3 实测 host_list 有重复项）
        val urls = info?.wsUrls.orEmpty()
        if (info == null || info.token.isBlank() || urls.isEmpty()) {
            log(
                "getDanmuInfo 数据不可用：tokenLen=${info?.token?.length ?: 0} " +
                    "hostList=${info?.host_list?.size ?: 0} wssUrls=${urls.size}"
            )
            LiveDanmakuTrace.note(
                "getDanmuInfo 数据不可用：HTTP=$httpCode tokenLen=${info?.token?.length ?: 0} " +
                    "wssUrls=${urls.size} 签名=${if (signed) "已签" else "未签"}｜body=${bodyHead.take(160)}"
            )
            infoFailureReason = "getDanmuInfo 返回体不可用（token/host 为空，HTTP=$httpCode）"
            return INFO_FATAL
        }
        token = info.token
        tokenFetchedAtMs = nowMs()
        forceTokenRefresh = false
        hosts = urls
        hostIndex = 0
        log("getDanmuInfo 成功：可用节点=${urls.size} tokenLen=${token.length}")
        LiveDanmakuTrace.note("getDanmuInfo 成功：节点=${urls.size} tokenLen=${token.length}")
        return INFO_OK
    }

    /**
     * 业务码 → 人话。
     *
     * ★为什么要映射：`-352` 的真实含义是"风控校验失败"，而它对客户端最常见的成因就是
     *   **缺 WBI 签名（w_rid）**。只把 `-352` 原样丢到屏幕上，用户和验收方都无从下手；
     *   配合 trace 里的"签名=已签/未签"，一眼就能分成两类：
     *   未签 → 是客户端没拼上签名（本工程 WBI 链路问题）；已签 → 才是服务端风控。
     */
    private fun describeBusinessFailure(code: Int, message: String): String = when (code) {
        -352 -> "被风控拒绝 code=-352（-352 = 风控校验失败；最常见成因是 WBI 签名 w_rid 缺失或无效）"
        -101 -> "未登录 code=-101"
        -403 -> "签名错误或无权限 code=-403"
        -400 -> "请求参数错误 code=-400"
        -412 -> "请求被拦截 code=-412（IP/UA 风控）"
        else -> "被业务拒绝 code=$code message=$message"
    }

    private fun openSocket() {
        if (closed) return
        if (hosts.isEmpty()) return
        val target = hosts[hostIndex.coerceIn(0, hosts.size - 1)]

        stopHeartbeat()
        stopAuthTimeout()
        authed = false
        // 关掉可能还挂着的旧连接（换 host 重连）
        runCatching { ws?.close(WS_CLOSE_NORMAL, "reconnect") }
        ws = null

        val gen = generation.incrementAndGet()
        val request = Request.Builder()
            .url(target)
            .header("User-Agent", ApiHelper.USER_AGENT)
            // 与 Web 端保持一致：直播域按同源校验（方案 §2.3 / blbl LiveMessageClient.kt:130-132）
            .header("Origin", LIVE_ORIGIN)
            .header("Referer", LIVE_REFERER)
            .build()
        log("连接弹幕：$target")
        ws = httpClient.newWebSocket(request, Listener(target, gen))
        startConnectTimeout(gen)
    }

    /**
     * ★重复重连防护 + 退避。
     *
     * 退避 = `1 shl attempt.coerceIn(0,4)` 封顶 10s → 1,2,4,8,16→**10**
     * （方案 §2.4 连接生命周期，blbl `LiveMessageClient.kt:142-143`）。
     * 换 host 也在这里做：`(hostIndex + 1) mod size`（blbl `:144`）。
     */
    private fun scheduleReconnect(reason: String, quiet: Boolean = false) {
        if (closed) return
        if (_connectionState.value == ConnState.Failed) return
        if (reconnectPending) {
            log("已有重连任务在排队，忽略本次触发（原因：$reason）")
            return
        }
        reconnectPending = true

        stopHeartbeat()
        stopAuthTimeout()
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null

        val delaySec = backoffSeconds()
        reconnectAttempt++
        if (hosts.isNotEmpty()) hostIndex = (hostIndex + 1).mod(hosts.size)
        _connectionState.value = ConnState.Reconnecting
        LiveDanmakuTrace.connectionText = "Reconnecting(第${reconnectAttempt}次,${delaySec}s后)"
        log("${delaySec}s 后重连（第 $reconnectAttempt 次；原因：$reason；下一台=${hosts.getOrNull(hostIndex) ?: "待重新获取节点"}）")
        if (!quiet) LiveDanmakuTrace.note("${delaySec}s 后重连（第 $reconnectAttempt 次；原因：$reason）")

        reconnectJob = scope.launch {
            try {
                delay(delaySec * 1000L)
                if (closed) return@launch
                when (connectOnce()) {
                    INFO_FATAL -> failPermanently(infoFailureReason)
                    INFO_RETRY -> log("重连取信息仍失败，继续退避")
                    else -> Unit
                }
            } finally {
                reconnectPending = false
                reconnectJob = null
                // 没开成 socket 且还处于 Reconnecting → 把退避链接上，避免"卡在重连中"
                if (!closed && _connectionState.value == ConnState.Reconnecting && ws == null) {
                    scheduleReconnect("上一轮重连未建立连接")
                }
            }
        }
    }

    /**
     * 不可自动恢复的失败：**先**把一切自动重试停掉，把状态交给 A 路（可手动再 connect()），
     * **再**排一个"慢速自愈"任务 —— 见 [scheduleRevive] 与 [reviveJob] 的注释。
     *
     * ★语义变化（第三阶段修复）：`Failed` 从"永久放弃"变成"**60s 后才自己再试一次**"。
     *   对外仍然先给出 `Failed`（A 路要提示用户、要给手动重试入口都还能用），
     *   但不再需要"UI 必须配合"才可能恢复 —— 这正是用户实测"永远不会自己好"的根因。
     */
    private fun failPermanently(reason: String) {
        log("判为不可自动恢复：$reason → 停止自动重连（60s 后自愈重试）")
        stopAll()
        _connectionState.value = ConnState.Failed
        LiveDanmakuTrace.connectionText = "Failed"
        LiveDanmakuTrace.lastConnectError = reason
        LiveDanmakuTrace.note("连接失败（不可自动恢复）：$reason")
        scheduleRevive()
    }

    /**
     * `Failed` 之后的慢速自愈：等 [FAILED_REVIVE_DELAY_MS] 再自己排一次重连。
     *
     * 为什么不是"无限快速重连"：`Failed` 的三条来源（`getDanmuInfo` 被风控、认证连续失败、
     * 房间异常）都属于"越猛越糟"，所以这里用 60s 的固定长间隔；一旦恢复，退避计数归零，
     * 之后又回到正常的 1~10s 退避链。为什么必须有它：宿主不会替我们重连（见 [reviveJob] 注释）。
     */
    private fun scheduleRevive() {
        if (closed) return
        reviveJob?.cancel()
        reviveJob = scope.launch {
            delay(FAILED_REVIVE_DELAY_MS)
            if (closed || _connectionState.value != ConnState.Failed) return@launch
            LiveDanmakuTrace.note("Failed 已持续 ${FAILED_REVIVE_DELAY_MS / 1000}s → 自愈重试")
            // 重新按"登录态"试：上次可能是匿名降级/风控临时状态，重来一遍完整的认证流程
            reconnectAttempt = 0
            authFailCount = 0
            anonymousOnly = false
            // 先把状态从 Failed 挪开（scheduleReconnect 的 Failed 闸门依赖它）
            _connectionState.value = ConnState.Reconnecting
            scheduleReconnect("Failed 自愈", quiet = true)
        }
    }

    /** socket 断开（失败 / 被关 / 超时 / 静默）后的统一处理入口 */
    private fun onSocketBroken(reason: String) {
        if (closed) return
        ws = null
        authed = false
        stopHeartbeat()
        stopAuthTimeout()
        if (_connectionState.value == ConnState.Failed) return
        scheduleReconnect(reason)
    }

    // ------------------------------------------------------------------
    // 定时任务：心跳 / 认证超时 / 握手超时 / 健康检查
    // ------------------------------------------------------------------

    /** 心跳：每 30s 发一次 op=2（方案 §2.4；第一次在 30s 后，和 blbl `:167-169` 一致） */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive && !closed) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (closed) break
                val socket = ws ?: break
                val ok = socket.send(ByteString.of(*LiveDanmakuPacket.heartbeatPacket(seq.getAndIncrement())))
                if (!ok) log("心跳发送失败（发送队列满或连接已关）")
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** 认证超时：6s 没等到 op=8 就判超时重连（方案 §2.4；blbl `:200-212`） */
    private fun startAuthTimeout() {
        stopAuthTimeout()
        authTimeoutJob = scope.launch {
            delay(AUTH_TIMEOUT_MS)
            if (!authed && !closed) {
                log("${AUTH_TIMEOUT_MS}ms 未收到 op=8 → 判认证超时，重连")
                runCatching { ws?.close(WS_CLOSE_NORMAL, "auth timeout") }
                onSocketBroken("auth timeout ${AUTH_TIMEOUT_MS}ms")
            }
        }
    }

    private fun stopAuthTimeout() {
        authTimeoutJob?.cancel()
        authTimeoutJob = null
    }

    /**
     * ★握手超时（两份参考实现都没写，但我们必须写）：
     * 为了 WebSocket 不被读超时误杀，[httpClient] 的 `readTimeout` 是 **0（永不超时）**，
     * 于是"TCP 连上了但服务端不回 101"会**永久卡在 Connecting**。这里补一条 15s 的握手闸门。
     */
    private fun startConnectTimeout(gen: Int) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (closed || authed || gen != generation.get()) return@launch
            log("${CONNECT_TIMEOUT_MS}ms 未完成握手 → 判连接卡死，换节点重连")
            runCatching { ws?.close(WS_CLOSE_NORMAL, "connect timeout") }
            onSocketBroken("connect timeout ${CONNECT_TIMEOUT_MS}ms")
        }
    }

    /**
     * 健康检查：每 5s 一次；距上次收到**任意服务端帧**超过 75s 判死重连。
     * 两个数字都来自 BiliPai `LiveDanmakuConnectionHealthPolicy.kt:3-4`（方案 §2.4 连接生命周期）。
     */
    private fun startHealthCheck() {
        if (healthJob?.isActive == true) return
        healthJob = scope.launch {
            while (isActive && !closed) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                if (closed) break
                when (_connectionState.value) {
                    ConnState.Connected -> {
                        val silentMs = nowMs() - lastServerFrameAtMs
                        if (silentMs > SILENCE_TIMEOUT_MS) {
                            log("静默 ${silentMs}ms（>${SILENCE_TIMEOUT_MS}ms）→ 判死重连")
                            runCatching { ws?.close(WS_CLOSE_SILENT, "silent") }
                            onSocketBroken("silent ${silentMs}ms")
                        }
                    }
                    // 兜底自愈：极端竞态下如果"重连中"却没有任何待执行的重连任务，补一次
                    ConnState.Reconnecting -> {
                        if (!reconnectPending && ws == null) {
                            log("自愈：状态为 Reconnecting 但没有待执行的重连任务")
                            scheduleReconnect("watchdog")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun stopAll() {
        stopHeartbeat()
        stopAuthTimeout()
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        healthJob?.cancel()
        healthJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        // 自愈任务也一起停：close() / failPermanently 都会走到这里，
        // failPermanently 之后会重新排一个（顺序：stopAll() → 置 Failed → scheduleRevive()）
        reviveJob?.cancel()
        reviveJob = null
        reconnectPending = false
        authed = false
        runCatching { ws?.close(WS_CLOSE_NORMAL, "bye") }
        ws = null
    }

    // ------------------------------------------------------------------
    // 收包：okhttp 读线程只入队，解包在独立协程
    // ------------------------------------------------------------------

    private fun startDecodeLoop() {
        if (decodeJob?.isActive == true) return
        // ★独立协程（不在 OkHttp 读线程上）：zlib inflate + JSON 解析都是 CPU 活，
        //   热门房每秒几十帧，放在读线程会直接拖慢收包（方案 §4.1「解包放独立 IO 协程」）
        decodeJob = scope.launch(Dispatchers.Default) {
            for (frame in frames) {
                if (closed) continue
                if (frame.gen != generation.get()) continue // 上一条连接的残留帧，丢掉
                runCatching {
                    LiveDanmakuPacket.decode(frame.bytes) { skip ->
                        LiveDanmakuTrace.decodeSkips.incrementAndGet()
                        log("解包跳过：$skip")
                    }.forEach { handlePacket(it) }
                }.onFailure { log("解包/分发异常（已吞掉，不影响后续帧）", it) }
            }
        }
    }

    private fun handlePacket(packet: LiveDanmakuPacket.Packet) {
        LiveDanmakuTrace.packets.incrementAndGet()
        when (packet.op) {
            LiveDanmakuPacket.OP_HEARTBEAT_REPLY -> {
                val value = LiveDanmakuPacket.popularity(packet.body)
                if (value > 0) _messages.tryEmit(LiveMessage.Popularity(value))
            }
            LiveDanmakuPacket.OP_AUTH_REPLY -> handleAuthReply(packet.body)
            LiveDanmakuPacket.OP_MESSAGE -> handleBusinessMessage(packet.body)
            else -> log("忽略未处理的包：$packet")
        }
    }

    private fun handleAuthReply(body: ByteArray) {
        val text = body.toString(Charsets.UTF_8).trim()
        val code = (parseJson(text)?.get("code") as? JsonPrimitive)?.intOrNull ?: -1
        if (code == 0) {
            authed = true
            authFailCount = 0
            reconnectAttempt = 0 // ★认证成功 → 退避计数归零（方案 §4.1）
            forceTokenRefresh = false
            stopAuthTimeout()
            startHeartbeat()
            _connectionState.value = ConnState.Connected
            LiveDanmakuTrace.connectionText = "Connected"
            LiveDanmakuTrace.lastConnectError = null
            LiveDanmakuTrace.note("认证成功（op=8 code=0）roomId=$roomId")
            log("认证成功（op=8 code=0）roomId=$roomId")
            return
        }

        authFailCount++
        // code!=0 多半是 token 过期或被风控：下一次强制重取 token
        forceTokenRefresh = true
        LiveDanmakuTrace.note("认证失败 code=$code（连续 $authFailCount 次）：$text")
        if (authFailCount == 1 && !anonymousOnly && currentUid() != 0L) {
            // 带 uid 认证被拒 → 退回匿名再试一次。实测 uid=0 也能认证成功（方案 §2.4），
            // 这样不会因为"web 指纹和登录态对不上"陷入无意义的失败循环
            anonymousOnly = true
            log("认证被拒且本次带了 uid → 下一次改用匿名(uid=0)重试")
        }
        log("认证失败：$text（连续 $authFailCount 次）")
        if (authFailCount >= MAX_AUTH_FAIL) {
            // 认证反复失败不是网络问题，无脑重连只会加重风控
            failPermanently("认证连续失败 $authFailCount 次：$text")
            return
        }
        runCatching { ws?.close(WS_CLOSE_NORMAL, "auth failed") }
        onSocketBroken("auth failed code=$code")
    }

    // ------------------------------------------------------------------
    // 业务消息 → LiveMessage
    // ------------------------------------------------------------------

    private fun handleBusinessMessage(body: ByteArray) {
        val text = body.toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) return
        val obj = parseJson(text) ?: return
        val cmd = obj.str("cmd") ?: return
        when {
            // cmd 实测就是 "DANMU_MSG"；用 startsWith 兼容 "DANMU_MSG:LOG_V2" 这类变体
            // （变体若没有 info 数组，下面的解析会自动返回 null，不会崩）
            cmd.startsWith(CMD_DANMU_MSG) -> parseDanmaku(obj)?.let {
                LiveDanmakuTrace.danmaku.incrementAndGet()
                _messages.tryEmit(it)
            }
            cmd == CMD_SUPER_CHAT -> parseSuperChat(obj)?.let {
                LiveDanmakuTrace.others.incrementAndGet()
                _messages.tryEmit(it)
            }
            cmd == CMD_SEND_GIFT -> parseGift(obj)?.let {
                LiveDanmakuTrace.others.incrementAndGet()
                _messages.tryEmit(it)
            }
            cmd == CMD_WATCHED_CHANGE -> parseWatched(obj)?.let {
                LiveDanmakuTrace.others.incrementAndGet()
                _messages.tryEmit(it)
            }
            cmd == CMD_LIVE -> {
                LiveDanmakuTrace.others.incrementAndGet()
                _messages.tryEmit(LiveMessage.LiveStatus(LIVE_STATUS_LIVE))
            }
            cmd == CMD_PREPARING -> {
                LiveDanmakuTrace.others.incrementAndGet()
                _messages.tryEmit(LiveMessage.LiveStatus(LIVE_STATUS_PREPARING))
            }
            cmd == CMD_STOP_LIVE_ROOM -> {
                LiveDanmakuTrace.others.incrementAndGet()
                _messages.tryEmit(LiveMessage.LiveStatus(LIVE_STATUS_STOPPED))
            }
            else -> Unit // INTERACT_WORD_V2 / LIKE_INFO_V3_* / ONLINE_RANK_* / NOTICE_MSG … 第一版忽略
        }
    }

    /**
     * `DANMU_MSG` 解析。逐下标全部照方案 §2.5 的实测表，**不要凭记忆改下标**。
     * 取不到关键字段（文本为空）就整条丢弃 —— 宁可少一条，也不渲染空弹幕。
     *
     * ★第三阶段补齐了方案 §2.5 里**已经实测到、但上一版没带出来**的四个字段：
     * 弹幕模式 `info[0][1]`、粉丝牌颜色 `info[3][4]`、房管 `info[2][2]`、表情 `info[0][13]`。
     * 其中 `info[3][4]`（牌子色）与 `info[0][13]`（表情）是本次**真机抓包复核**过的：
     * 抓到的真实帧里 `info[3] = [24,"三千八","辉耀计划",24158186,1725515,…]`，
     * `info[0][13]` 平时是字符串 `"{}"`（所以表情解析必须做"是不是对象"的判据）。
     */
    private fun parseDanmaku(obj: JsonObject): LiveMessage.Danmaku? {
        val info = obj.arr("info") ?: return null
        val rawText = info.text(1) ?: return null
        val text = sanitizeText(rawText)
        if (text.isEmpty()) return null

        val user = info.getOrNull(2) as? JsonArray
        val uid = user.long(0) ?: 0L
        val uname = user.text(1).orEmpty()
        // ★"自己发的弹幕要显示昵称"：**任何**来自我自己 uid 的弹幕（服务端把刚发的那条推回来、
        //   或者我在本房间更早发的）都带真实昵称 → 顺手学下来，下一次本地回显就用它而不是"我"
        //   （见 LiveSelfNickname）。uid 走缓存：这条路径每秒可能走几十次。
        if (uid != 0L && uname.isNotBlank() && uid == selfUidCached()) {
            LiveSelfNickname.learn(uid, uname)
        }
        // info[2][2]：是否房管（方案 §2.5 实测下标）
        val isAdmin = (user.int(2) ?: 0) == 1

        val extra = info.getOrNull(0) as? JsonArray
        val colorRaw = extra.int(3) ?: DEFAULT_COLOR
        // color=0（纯黑）在视频上等于看不见，按默认白色处理
        val color = if (colorRaw == 0) DEFAULT_COLOR else colorRaw and 0xFFFFFF
        val timeMs = normalizeTimeMs(extra.long(4) ?: 0L, obj.long("send_time") ?: 0L)
        // info[0][1]：弹幕模式（1/2/3=滚动、4=底部、5=顶部）。实测值就是 1；
        // 越界值一律归一到滚动，渲染侧就不用再兜底
        val mode = (extra.int(1) ?: LiveMessage.MODE_SCROLL)
            .takeIf { it in LiveMessage.MODE_SCROLL..LiveMessage.MODE_TOP }
            ?: LiveMessage.MODE_SCROLL
        // info[0][13]：表情（**字符串形式的 JSON**，平时是 "{}"）
        val emoticonUrl = parseEmoticonUrl(extra.text(13))

        val medal = info.getOrNull(3) as? JsonArray
        val medalName = medal.text(1)?.takeIf { it.isNotBlank() }
        val medalLevel = medal.int(0) ?: 0
        // info[3][4]：粉丝牌颜色（实测与 info[0][15].user.medal.color 同值）
        val medalColor = (medal.int(4) ?: 0) and 0xFFFFFF
        val userLevel = (info.getOrNull(4) as? JsonArray).int(0) ?: 0

        if (isSelfSentEcho(text) || isDuplicate(uid, uname, text)) {
            LiveDanmakuTrace.deduped.incrementAndGet()
            return null
        }
        return LiveMessage.Danmaku(
            text = text,
            uid = uid,
            uname = uname,
            color = color,
            timeMs = timeMs,
            medalName = medalName,
            medalLevel = medalLevel,
            userLevel = userLevel,
            mode = mode,
            medalColor = medalColor,
            isAdmin = isAdmin,
            emoticonUrl = emoticonUrl,
        )
    }

    /**
     * 表情弹幕 URL（`info[0][13]`）。
     *
     * ★实测该位置平时是**字符串** `"{}"`，不是对象 —— 直接 `as? JsonObject` 会永远拿不到东西。
     * 这里用三重判据：字符串 → `{` 开头且够长 → 解析后真有 `url` 且是 http(s)。
     * 任何一步不满足就返回 null（宁可不解析，也不要把下标猜错、把普通弹幕误判成表情）。
     */
    private fun parseEmoticonUrl(raw: String?): String? {
        if (raw == null || raw.length < 8 || !raw.startsWith("{")) return null
        val url = parseJson(raw)?.str("url") ?: return null
        return url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /** `SUPER_CHAT_MESSAGE`：字段依据 blbl `LiveMessageClient.kt:36-46` 的模型 + `:317-323` 的取法 */
    private fun parseSuperChat(obj: JsonObject): LiveMessage.SuperChat? {
        val data = obj.obj("data") ?: return null
        val text = data.str("message").orEmpty()
        if (text.isBlank()) return null
        val uname = data.obj("user_info")?.str("uname").orEmpty()
        return LiveMessage.SuperChat(uname = uname, text = text, price = data.int("price") ?: 0)
    }

    /** `SEND_GIFT`：B 站标准字段 `data.{uname, giftName, num}`（方案 §2.5 要求给这类事件留分支） */
    private fun parseGift(obj: JsonObject): LiveMessage.Gift? {
        val data = obj.obj("data") ?: return null
        val giftName = data.str("giftName").orEmpty()
        if (giftName.isBlank()) return null
        return LiveMessage.Gift(
            uname = data.str("uname").orEmpty(),
            giftName = giftName,
            num = data.int("num") ?: 1,
        )
    }

    /** `WATCHED_CHANGE`：实测 `data.{num, text_small, text_large}`（方案 §2.5） */
    private fun parseWatched(obj: JsonObject): LiveMessage.Watched? {
        val data = obj.obj("data") ?: return null
        val num = data.int("num") ?: return null
        val text = data.str("text_large") ?: data.str("text_small") ?: "$num"
        return LiveMessage.Watched(num = num, text = text)
    }

    // ------------------------------------------------------------------
    // 文本清洗 / 去重 / 时间归一化
    // ------------------------------------------------------------------

    /**
     * 弹幕文本**限长 + 过滤换行**（方案 §4.1 关键设计约束）。
     *
     * 为什么：换行会把单行弹幕渲染撑破（浮层按"单行 + 固定车道高"布局），
     * 超长文本会拖慢测量/绘制、挤占车道。
     */
    private fun sanitizeText(raw: String): String {
        val sb = StringBuilder(minOf(raw.length, MAX_TEXT_LENGTH))
        for (ch in raw) {
            val c = if (ch == '\n' || ch == '\r' || ch == '\t' || ch.code < 0x20 || ch.code == 0x7F) ' ' else ch
            sb.append(c)
            if (sb.length >= MAX_TEXT_LENGTH) break
        }
        // 截断可能刚好切在代理对（emoji）中间，去掉孤立的高代理，避免渲染出 "�"
        if (sb.isNotEmpty() && sb.last().isHighSurrogate()) sb.deleteCharAt(sb.length - 1)
        return sb.toString().trim()
    }

    /**
     * 去重：同一 (uid, 文本) 在 [DEDUP_WINDOW_MS] 内只保留第一条。
     *
     * 为什么需要：① 热门房同一句话会被大量重复刷（方案 §6.5 要求"去重"）；
     * ② 断线重连后服务端可能重发最近几条。协议里的消息 id 在 `info[0][5..8]`，
     * 但 `LiveMessage.Danmaku` 的字段已被接口锁死（带不了 id），所以这里用"指纹 + 时间窗"近似。
     * 该方法只在**单条解码协程**里被调用，所以 [recentDanmaku] 无需加锁。
     */
    /** 是不是"自己刚刚发出去、服务端又推回来"的那一条（见 [echoSentDanmaku]） */
    private fun isSelfSentEcho(text: String): Boolean {
        val sent = selfSentText ?: return false
        return sent == text && nowMs() - selfSentAtMs < SELF_ECHO_WINDOW_MS
    }

    private fun isDuplicate(uid: Long, uname: String, text: String): Boolean = synchronized(dedupLock) {
        val now = nowMs()
        while (recentDanmaku.isNotEmpty() && now - recentDanmaku.first().second > DEDUP_WINDOW_MS) {
            recentDanmaku.removeFirst()
        }
        // ★指纹策略：uid 有效时只用 (uid, text) —— 这样"自己发的本地回显"和
        //   "服务端推回来的同一条"能对上（回显时拿不到自己的真实昵称）。
        //   uid=0 是真实存在的"神秘人"（实测 `info[2]=[0,"脳***"]`），它们共用 uid，
        //   才需要带上昵称避免误判。
        val key = if (uid != 0L) "$uid|$text" else "0|$uname|$text"
        if (recentDanmaku.any { it.first == key }) return@synchronized true
        recentDanmaku.addLast(key to now)
        if (recentDanmaku.size > DEDUP_MAX_ENTRIES) recentDanmaku.removeFirst()
        false
    }

    /**
     * 时间戳归一化：`info[0][4]` 实测是**毫秒**（如 1790350852034）。
     * 但线上偶有把"秒"塞进来的数据（10 位），遇到就 ×1000；两者都没有就退化成当前时间。
     */
    private fun normalizeTimeMs(fromInfo: Long, sendTimeSec: Long): Long {
        if (fromInfo > 0L) return if (fromInfo < SECOND_TIMESTAMP_UPPER_BOUND) fromInfo * 1000L else fromInfo
        if (sendTimeSec > 0L) return sendTimeSec * 1000L
        return System.currentTimeMillis()
    }

    /** 登录态下填 `DedeUserID`，否则 0（方案 §2.4 认证包；没有 web SESSDATA 时绝对不能瞎填 uid） */
    private fun currentUid(): Long {
        if (anonymousOnly) return 0L
        return try {
            if (MiaoHttp.sessDataToken().isNullOrBlank()) return 0L
            MiaoHttp.cookieValue("DedeUserID")?.trim()?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        }
    }

    /**
     * [currentUid] 的**只读缓存版**（给"顺手学自己昵称"那条每条弹幕都要走的路径用）。
     *
     * ★与 [currentUid] 的区别只有一条：不重复问 CookieManager（那是一次跨进程查询）。
     *   它**不参与**认证包 / 发送这类真的需要"当前值"的地方 —— 那些仍然用 [currentUid]。
     */
    private fun selfUidCached(): Long {
        val cached = selfUidCache
        if (cached >= 0L) return cached
        val value = currentUid()
        selfUidCache = value
        return value
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    private inner class Listener(
        private val host: String,
        private val gen: Int,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed || gen != generation.get()) return
            log("弹幕 WS 握手成功：$host")
            lastServerFrameAtMs = nowMs()
            connectTimeoutJob?.cancel()
            connectTimeoutJob = null
            sendAuth(webSocket)
            startAuthTimeout()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // ★先记"收到过服务端帧"，再入队：健康检查看的是**任意帧**（含 op=3/op=8），
            //   不是"有没有弹幕" —— 实测安静的房 20s 一条弹幕都没有（方案 §6.5）
            lastServerFrameAtMs = nowMs()
            LiveDanmakuTrace.rawFrames.incrementAndGet()
            // ★只入队不解析，且 trySend 永不阻塞：绝不拖慢 OkHttp 读线程（DROP_OLDEST）
            frames.trySend(IncomingFrame(gen, bytes.toByteArray()))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // 实测 B 站只推二进制帧；真收到文本帧说明协议有变，记一条便于排查
            log("收到文本帧（预期只有二进制）：${text.replace("\n", "\\n").take(120)}")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (gen != generation.get()) return
            log("服务端要求关闭：$code $reason")
            runCatching { webSocket.close(code, reason) } // 回一个 close 帧，让服务端干净收尾
            onSocketBroken("onClosing $code $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen != generation.get()) return
            log("弹幕 WS 已关闭：$code $reason")
            onSocketBroken("onClosed $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation.get()) return
            log("弹幕 WS 失败：code=${response?.code} ${t::class.java.simpleName}: ${t.message}")
            onSocketBroken("onFailure code=${response?.code} ${t::class.java.simpleName}")
        }
    }

    private fun sendAuth(webSocket: WebSocket) {
        val uid = currentUid()
        val packet = LiveDanmakuPacket.authPacket(
            roomId = roomId,
            uid = uid,
            token = token,
            seq = seq.getAndIncrement(),
        )
        val ok = webSocket.send(ByteString.of(*packet))
        log("已发认证包 op=7 uid=$uid protover=${LiveDanmakuPacket.AUTH_PROTOVER} keyLen=${token.length} ok=$ok")
    }

    /**
     * 发送前的文本清洗。
     *
     * 与 [sanitizeText]（收弹幕）的区别：**超长不静默截断**。
     * 收弹幕截断是"为了不把界面撑破"，发弹幕截断却是"用户以为发了一整句、实际只发了半句" ——
     * 所以这里超长直接拒绝，并把原因写进 [lastSendError]（返回 null 表示校验没过）。
     */
    private fun sanitizeSendText(raw: String): String? {
        if (raw.isBlank()) {
            setSendError("弹幕内容不能为空")
            return null
        }
        val cleaned = sanitizeText(raw)
        if (cleaned.isEmpty()) {
            setSendError("弹幕内容无效（只有空白/控制字符）")
            return null
        }
        // B 站普通用户 20 字、老爷 40 字；本地按上限 40 拦一道，剩下的交给服务端返回真实原因
        if (cleaned.length > MAX_SEND_TEXT_LENGTH) {
            setSendError("弹幕最多 $MAX_SEND_TEXT_LENGTH 字（当前 ${cleaned.length} 字）")
            return null
        }
        return cleaned
    }

    /** 统一的失败原因出口：内存字段 + trace 文件都写一份（release 也能取证） */
    private fun setSendError(reason: String) {
        lastSendError = reason
        LiveDanmakuTrace.lastSendError = reason
        LiveDanmakuTrace.note("发送弹幕失败：$reason")
    }

    /**
     * 本地回显自己刚发出去的弹幕。
     *
     * 为什么值得做：
     * 1. 用户"点了发送却什么都看不到"是最伤的体验，回显能立刻给出反馈
     *    （即使用户把弹幕开关关了、或 WS 正好断着，也看得到自己那条）；
     * 2. 它同时也是一条**渲染链路的自证**：回显能上屏 = 浮层本身没问题。
     * 去重：回显前把 `(uid,text)` 指纹登记进去，2s 内 WS 推回来的同一条会被丢掉，不会重复上屏。
     */
    private fun echoSentDanmaku(text: String) {
        val uid = currentUid()
        // 两个抑制手段，覆盖不同边角：
        // ① 指纹（uid 有效时和服务端推回来的那条同 key）；
        // ② selfSentText 窗口 —— 万一是"APP 登录没有 web DedeUserID"（uid=0，拿不到昵称），
        //    或服务端回推的 uid 与我们不同，也能靠"文本 + 3s 窗口"把自己那条盖掉
        isDuplicate(uid, "我", text)
        selfSentText = text
        selfSentAtMs = nowMs()
        val echo = LiveMessage.Danmaku(
            text = text,
            uid = uid,
            // ★昵称（第四个需求）：优先用**已经知道的自己昵称** —— 来源是"服务端把某条我发的弹幕
            //   推回来时的真实昵称" / 历史弹幕里我自己那条 / 本机 user.data / nav，
            //   统一由 LiveSelfNickname 管（见该类注释）。真的一个都拿不到才退回"我"。
            //   ★绝不显示 uid：用户明确要求"跟别人一样的昵称全名"。
            uname = LiveSelfNickname.current() ?: FALLBACK_SELF_UNAME,
            color = DEFAULT_COLOR,
            timeMs = System.currentTimeMillis(),
            medalName = null,
            medalLevel = 0,
            userLevel = 0,
            mode = LiveMessage.MODE_SCROLL,
        )
        _messages.tryEmit(echo)
    }

    /** 状态变化的统一出口：内存 + trace 文件 + release 可见的 ERROR 级日志 */
    private fun noteState(event: String) {
        LiveDanmakuTrace.connectionText = _connectionState.value.name
        LiveDanmakuTrace.note(event)
    }

    private fun parseJson(text: String): JsonObject? =
        // 复用工程的 MiaoJson（ignoreUnknownKeys + isLenient），省一个 Json 实例
        runCatching { MiaoJson.kotlinJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun nowMs(): Long = SystemClock.elapsedRealtime() // 单调时钟：不受用户改系统时间影响

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 1,2,4,8,16→10（方案 §2.4：`1 shl attempt.coerceIn(0,4)`，封顶 10s） */
    private fun backoffSeconds(): Long =
        (1L shl reconnectAttempt.coerceIn(0, MAX_BACKOFF_SHIFT)).coerceAtMost(MAX_BACKOFF_SECONDS)

    private fun log(msg: String, t: Throwable? = null) {
        if (t == null) miaoLogger().d("[$roomId] $msg") else miaoLogger().e("[$roomId] $msg", t)
    }

    private class IncomingFrame(val gen: Int, val bytes: ByteArray)

    companion object {
        /**
         * **最近一个活着的实例**（"发弹幕"按钮的取用入口）。
         *
         * ★背景：一开始宿主 `LiveDanmakuOverlayHost` 把客户端藏成 `private val client`，
         *   而宿主属 A 路、Activity 属 app 模块（本路都禁改）→ "发弹幕按钮"没有任何合法途径
         *   调到 [LiveDanmakuClient.sendDanmaku]。A 路随后已把宿主字段改成 `val client`（public），
         *   **首选走那条路**；本字段保留给"手里拿不到宿主实例"的场景（其它模块、未来的快捷发送入口等）。
         *
         * 用法：
         * ```kotlin
         * // ① 首选：Activity 直接从宿主拿（A 路已接好）
         * val ok = host.client.sendDanmaku(text)
         *
         * // ② 兜底：拿不到宿主时取最近实例
         * val ok = LiveDanmakuClient.lastClient?.sendDanmaku(text) ?: false
         * val reason = LiveDanmakuClient.lastClient?.lastSendError
         * ```
         * 生命周期：[close] 时会把指向自己的引用清空，所以拿到 null 就表示"当前没有活着的弹幕客户端"。
         */
        @Volatile
        var lastClient: LiveDanmakuClient? = null
            private set

        /** 登记/注销 [lastClient]。★写成伴生对象内部的私有函数，避免依赖
         *  "外部类能否写伴生对象的 private setter" 这条边角可见性规则。 */
        private fun register(client: LiveDanmakuClient?) {
            lastClient = client
        }

        /** ★需求下限是 64；这里给 128，配合 DROP_OLDEST（见 [_messages]） */
        const val MESSAGE_BUFFER_CAPACITY = 128

        /** 原始帧队列长度：够扛住一次 GC 停顿，满了丢最旧 */
        private const val FRAME_BUFFER_CAPACITY = 256

        /** 认证超时 6s（方案 §2.4 / blbl `LiveMessageClient.kt:200-212`） */
        private const val AUTH_TIMEOUT_MS = 6_000L

        /** 心跳间隔 30s（方案 §2.4） */
        private const val HEARTBEAT_INTERVAL_MS = 30_000L

        /** 健康检查间隔 5s（BiliPai `LiveDanmakuConnectionHealthPolicy.kt:4`） */
        private const val HEALTH_CHECK_INTERVAL_MS = 5_000L

        /** 静默判死阈值 75s：「距上次收到任意服务端帧」超过它就重连（BiliPai 同文件 `:3`） */
        private const val SILENCE_TIMEOUT_MS = 75_000L

        /** 握手超时：见 [startConnectTimeout]（readTimeout=0 的必要补丁） */
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /**
         * "Connecting 卡死"判定：正常情况下 `Connecting` 最长只活 15s（握手超时）。
         * 超过它说明上一次 `connect()` 走的是取消/异常路径、状态没回收 —— 这时允许重新进入。
         */
        private const val CONNECTING_STALE_MS = 20_000L

        /**
         * `Failed`（不可自动恢复）之后的自愈间隔。
         *
         * ★为什么不干脆"永远不重试"：宿主不会替我们重连（见 [reviveJob]），
         *   用户实测就是"一条弹幕都没有，而且永远不会自己好"。
         *   60s 是"能自愈"与"别把风控惹毛"之间的折中（`getDanmuInfo` 是敏感接口，
         *   方案 §6.1 建议这类轮询不要密于 30~60s）。
         */
        private const val FAILED_REVIVE_DELAY_MS = 60_000L

        /** 重连退避上限 10s（方案 §2.4：1,2,4,8,16→10） */
        private const val MAX_BACKOFF_SECONDS = 10L
        private const val MAX_BACKOFF_SHIFT = 4

        /** token 最长复用 10 分钟：再久就重取，避免"拿过期 token 反复认证失败" */
        private const val TOKEN_MAX_AGE_MS = 10 * 60 * 1000L

        /** 认证连续失败这么多次就停（风控场景无脑重连只会更糟） */
        private const val MAX_AUTH_FAIL = 3

        /** 弹幕文本上限（UTF-16 码元）。B 站普通用户 20 字、老爷 40 字，留点余量 */
        private const val MAX_TEXT_LENGTH = 60

        /**
         * **发送**弹幕的本地长度上限。
         *
         * 收弹幕用 60（宽进严出，界面别被撑破），发送用 40（B 站老爷上限）——
         * 超过直接拒绝而不是截断：截断会让用户以为整句发出去了（见 [sanitizeSendText]）。
         */
        private const val MAX_SEND_TEXT_LENGTH = 40

        /** 去重窗口与容量（见 [isDuplicate]） */
        private const val DEDUP_WINDOW_MS = 2_000L

        /** "自己发的弹幕被服务端推回来"的抑制窗口（见 [isSelfSentEcho]） */
        private const val SELF_ECHO_WINDOW_MS = 3_000L
        private const val DEDUP_MAX_ENTRIES = 128

        /** 10 位时间戳必然是"秒"（毫秒时间戳 2001 年就已经是 13 位） */
        private const val SECOND_TIMESTAMP_UPPER_BOUND = 100_000_000_000L

        private const val DEFAULT_COLOR = 0xFFFFFF

        /**
         * 自己发的弹幕在列表里的**兜底**昵称。
         *
         * ★只在 [LiveSelfNickname] 四个来源全部拿不到时才会出现（未登录 / 缓存缺失 /
         *   nav 也被拒）。正常登录用户看到的是真实昵称全名，与列表里其它条目同格式。
         */
        private const val FALLBACK_SELF_UNAME = "我"

        /**
         * 进房铺底最多铺多少条历史。
         *
         * 接口本身只回 `admin` 10 条 + `room` 10 条；这里给一倍余量，
         * 防的是"服务端哪天给更多"把 200 条的列表一次塞满（见 `LiveDanmakuChatLog`）。
         */
        private const val MAX_HISTORY_ITEMS = 40

        private const val WS_CLOSE_NORMAL = 1000
        private const val WS_CLOSE_SILENT = 4000

        private const val LIVE_ORIGIN = "https://live.bilibili.com"
        private const val LIVE_REFERER = "https://live.bilibili.com/"

        // 业务 cmd（方案 §2.5 实测样本）
        private const val CMD_DANMU_MSG = "DANMU_MSG"
        private const val CMD_SUPER_CHAT = "SUPER_CHAT_MESSAGE"
        private const val CMD_SEND_GIFT = "SEND_GIFT"
        private const val CMD_WATCHED_CHANGE = "WATCHED_CHANGE"
        private const val CMD_LIVE = "LIVE"
        private const val CMD_PREPARING = "PREPARING"
        private const val CMD_STOP_LIVE_ROOM = "STOP_LIVE_ROOM_LIST"

        /** [LiveMessage.LiveStatus] 的 status 取值：下播 / 准备中（cmd=PREPARING） */
        const val LIVE_STATUS_PREPARING = 0

        /** [LiveMessage.LiveStatus] 的 status 取值：已开播（cmd=LIVE） */
        const val LIVE_STATUS_LIVE = 1

        /** [LiveMessage.LiveStatus] 的 status 取值：停止推流（cmd=STOP_LIVE_ROOM_LIST，实测出现过） */
        const val LIVE_STATUS_STOPPED = 2

        /** [ensureConnectionInfo] 的返回值 */
        private const val INFO_OK = 0
        private const val INFO_RETRY = 1
        private const val INFO_FATAL = 2

        /**
         * ★弹幕专用 OkHttpClient（进程级单例，不随 client 实例销毁）。
         *
         * 为什么不能直接用 `MiaoHttp` 的共享 client：
         * 它的 `readTimeout` 是 **15s**（`MiaoHttp.kt:234`，`connectTimeout` 在 `:233`）。OkHttp 建 WebSocket 时
         * **不会**把读超时清零（`RealWebSocket.connect` 只换 eventListener/protocols），
         * 于是任何 >15s 没有下行数据的时刻都会被当成读超时报 failure ——
         * 而实测安静的直播间 20s 一条弹幕都没有（方案 §6.5）→ 会被误判成断线。
         * 所以这里 `readTimeout = 0`（永不超时），"判死"改由 75s 静默健康检查负责。
         *
         * `connectTimeout=10s` + `retryOnConnectionFailure=false`：我们自己按 host_list 依次
         * 换节点（方案 §4.1），不希望 OkHttp 在后台偷偷重试同一台把"换台"拖慢。
         * `pingInterval` 保持 0（关闭）：B 站靠 op=2 应用层心跳，协议级 ping 帧没必要也不保证被回。
         */
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}

// ----------------------------------------------------------------------
// kotlinx.serialization 取值小工具（逐层判空，业务解析写起来不啰嗦）
// ----------------------------------------------------------------------

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonArray?.text(index: Int): String? = (this?.getOrNull(index) as? JsonPrimitive)?.contentOrNull

private fun JsonArray?.int(index: Int): Int? = (this?.getOrNull(index) as? JsonPrimitive)?.intOrNull

private fun JsonArray?.long(index: Int): Long? = (this?.getOrNull(index) as? JsonPrimitive)?.longOrNull
