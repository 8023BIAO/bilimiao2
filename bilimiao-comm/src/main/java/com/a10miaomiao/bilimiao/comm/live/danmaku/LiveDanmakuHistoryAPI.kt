package com.a10miaomiao.bilimiao.comm.live.danmaku

import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 直播**最近弹幕历史**接口（"进房先铺一批最近的弹幕"的数据源）。
 *
 * ## 接口出处（只读扒的 `/tmp/PiliPlus`，行号已核对）
 * - URL 常量：`PiliPlus/lib/http/api.dart:324-326`
 *   ```dart
 *   // 直播间弹幕预获取
 *   // roomid roomId
 *   static const String liveRoomDmPrefetch =
 *       '${HttpString.liveBaseUrl}/xlive/web-room/v1/dM/gethistory';
 *   ```
 *   （`liveBaseUrl` = `https://api.live.bilibili.com`，`PiliPlus/lib/http/constants.dart:6`）
 * - 发请求：`PiliPlus/lib/http/live.dart:127-150`（`liveRoomDmPrefetch({required roomId})`）
 *   ```dart
 *   final res = await Request().get(
 *     Api.liveRoomDmPrefetch,
 *     queryParameters: {'roomid': roomId},
 *     options: Options(headers: {
 *       'referer': 'https://live.bilibili.com/$roomId',
 *       'user-agent': BrowserUa.pc,
 *     }),
 *   );
 *   ... (res.data['data']?['room'] as List?)?.map((e) => DanmakuMsg.fromPrefetch(e)).toList()
 *   ```
 * - 什么时候拉：`PiliPlus/lib/pages/live_room/controller.dart:466-473`（`startLiveMsg()` 里
 *   `if (messages.isEmpty) prefetch();`）→ 也就是**进房一次**（房间页首次建立消息流时）；
 *   实现体在 `:419-435`（`prefetch()`，成功就 `messages.addAll(...)` 然后 `scrollToBottom()`）。
 * - 字段映射参考：`PiliPlus/lib/models_new/live/live_danmaku/danmaku_msg.dart:26-63`
 *   （昵称取 `user['base']['name']`、内容取 `obj['text']`、时间取 `check_info['ts']/'ct'`、
 *     uid 取 `user['uid']`、房管看 `obj['isadmin']`）。
 *
 * ## 我们这一侧的参数（比 PiliPlus 多一个 `room_type`，不是自己猜的）
 * B 站**自己的 Web 直播间**打包产物里就是这个调用（`s1.hdslb.com/bfs/static/blive/blfe-live-room/
 * static/js/app.*.js`，可 grep 到）：
 * ```js
 * m().get("/xlive/web-room/v1/dM/gethistory", { params: { roomid: t, room_type: e } })
 * ```
 * 所以这里跟它对齐：`roomid` + `room_type=0`；Referer/Origin 用直播域自己的
 * （与工程里 `LiveAPI.danmuInfo` / `LiveDanmakuSendAPI` 的写法完全一致）。
 *
 * ★**这条接口不在 WBI 白名单里**（`WbiSigner.AUTO_SIGN_LIVE_PATHS` 只有 `getDanmuInfo` 与 `/msg/send`），
 *   所以按现状**不签名**直接发；MiaoHttp 的 `autoScopeFor()` 对它返回 null，也不会偷偷给它加 `w_rsid`。
 *
 * ★**失败一律静默**：本类**不抛异常**（协程取消除外），拿到什么返回什么，
 *   调用方（`LiveDanmakuClient.fetchHistory`）只把结果铺进竖屏列表，
 *   **绝不参与**实时 WS 链路的任何判断 —— 接口挂了也只是"进房列表是空的"，与现在行为一致。
 *
 * ## 容器 curl 实测（2026-09-26，未登录，详见报告）
 * 30 个**正在直播**的房间（在线 1.5 万 ~ 52 万）实测全部是
 * `{"code":0,"data":{"admin":[],"room":[]},"message":"","msg":""}`：
 * `code=0` 但没有数据。同一 IP、同一 cookie 打 `getRoomPlayInfo` 却能拿到完整播放信息，
 * 说明不是网络/风控，而是**这条接口对未登录（或已下线）返回空**。
 * 所以：登录态的真机上应当有数据；真没有也只是"列表从空开始"，与改造前一致。
 */
class LiveDanmakuHistoryAPI {

    /** 一条历史弹幕（**已归一化**：时间已转毫秒、房管已转 Boolean、昵称已取好兜底） */
    data class Item(
        val text: String,
        val uid: Long,
        val uname: String,
        /** 发送时间（毫秒）；解析不出时是 0（排序时排在最前，不影响后面按真实时间排好的部分） */
        val timeMs: Long,
        val isAdmin: Boolean,
    )

    /**
     * 一次拉取的结果。
     *
     * [items] **已按时间升序**（旧 → 新）——"按时间顺序铺进列表"这条要求的数据侧保证；
     * 竖屏列表内部是"新的在 index 0"，由 `LiveDanmakuChatLog.addHistory` 反向追加，
     * 所以这里给调用方的就是最自然的"时间顺序"。
     */
    data class FetchResult(
        val code: Int,
        val message: String,
        val items: List<Item>,
    ) {
        val ok: Boolean get() = code == 0
    }

    /**
     * 拉一次最近历史弹幕。
     *
     * @param roomId **真实房间号**（与收弹幕同一口径；短号先走 `LiveAPI.roomInitResolved`）
     */
    suspend fun fetch(roomId: Long): FetchResult {
        val url = buildUrl(roomId)
        return try {
            // ★切 IO：调用方（宿主）在 Compose 主线程上发起的；`awaitCall()` 本身是 enqueue
            //   （不阻塞），但 buildRequest 里要读 CookieManager —— 一条历史弹幕不值得赌主线程。
            val body = withContext(Dispatchers.IO) {
                val response = MiaoHttp.request {
                    // 直播 Web 接口：只认 Cookie + Referer/Origin，不能带 APP 身份头
                    // （理由与 LiveAPI 的类注释同款：APP 头是另一条通道，轻则行为不一致、重则风控）
                    isWebApi = true
                    headers["Referer"] = "$LIVE_REFERER$roomId"
                    headers["Origin"] = LIVE_ORIGIN
                    this.url = url
                }.awaitCall()
                response.json<HistoryResponse>()
            }
            FetchResult(
                code = body.code,
                message = body.message,
                items = if (body.code == 0) parseItems(body.data) else emptyList(),
            )
        } catch (ce: CancellationException) {
            // 协程取消不是"接口失败"：原样抛出，别把它变成一次静默的空结果
            throw ce
        } catch (t: Throwable) {
            FetchResult(
                code = CODE_NETWORK,
                message = "${t::class.java.simpleName}: ${t.message}",
                items = emptyList(),
            )
        }
    }

    /** 手拼 query：沿用工程写法（`ApiHelper.urlencode` 会丢掉空值参数） */
    private fun buildUrl(roomId: Long): String {
        val query = ApiHelper.urlencode(
            mapOf(
                "roomid" to roomId.toString(),
                // B 站 Web 直播间自己的打包产物里带的第二个参数（见类注释）；PiliPlus 没带，
                // 但实测两种都 `code=0`，带上与官方前端一致，少一层未知。
                "room_type" to ROOM_TYPE_NORMAL,
            )
        )
        return "$HISTORY_URL?$query"
    }

    /**
     * `data.admin`（房管的最近 10 条）+ `data.room`（普通用户的最近 10 条）合并成一条时间线。
     *
     * ★两处防御：
     * 1. `text` 为空/全空白的条目直接丢（别在列表里出现"用户名："这种缺一半的行）；
     * 2. 时间解析失败（`timeline` 是 `"0000-00-00 00:00:00"` 或字段缺失）时为 0，
     *    `sortedBy` 是**稳定排序**，所以这些条目保持接口给的原始相对顺序。
     */
    private fun parseItems(data: HistoryData?): List<Item> {
        if (data == null) return emptyList()
        val all = ArrayList<HistoryItem>(data.admin.size + data.room.size)
        all.addAll(data.admin)
        all.addAll(data.room)
        if (all.isEmpty()) return emptyList()
        // ★SimpleDateFormat 不是线程安全的：这里**每次调用新建一个**（一次进房才一次，开销可忽略），
        //   不做成员字段，避免"两个直播间同时进房"时互相踩（工程里踩过这类坑）。
        val format = SimpleDateFormat(TIMELINE_PATTERN, Locale.CHINA)
        val items = ArrayList<Item>(all.size)
        for (raw in all) {
            val text = raw.text
            if (text.isBlank()) continue
            // ★实测历史里真有"只有 U+202B（从右到左标记）"这种**看不见**的条目
            //   （bilibili-API-collect 的真实样本 10 条里有 4 条就是它），
            //   别让它们变成列表里一行"用户名："空气 —— 见 [hasVisibleContent]
            if (!hasVisibleContent(text)) continue
            // 昵称：优先顶层 nickname，退到 user.base.name（PiliPlus 取的就是后者）
            val uname = raw.nickname.takeIf { it.isNotBlank() }
                ?: raw.user?.base?.name.orEmpty()
            items += Item(
                text = text,
                uid = if (raw.uid != 0L) raw.uid else (raw.user?.uid ?: 0L),
                uname = uname,
                timeMs = parseTimeline(format, raw.timeline),
                isAdmin = raw.isadmin != 0,
            )
        }
        return items.sortedBy { it.timeMs }
    }

    private fun parseTimeline(format: SimpleDateFormat, timeline: String): Long {
        if (timeline.isBlank()) return 0L
        val time = runCatching { format.parse(timeline)?.time ?: 0L }.getOrDefault(0L)
        // ★实测 `timeline = "0000-00-00 00:00:00"`（服务端"没有时间"的写法）会被 SimpleDateFormat
        //   解析成**公元前**的一个大负数（实测 -62170156800000），直接参与排序会把这条顶到最前面。
        //   非正值一律归 0（= 没有时间），与其它解析失败的情形一致，排序时保持接口给的相对顺序。
        return if (time > 0L) time else 0L
    }

    /**
     * 文本里有没有**看得见**的内容。
     *
     * 判据 = 至少一个"既不是空白、也不是格式字符（Unicode `Cf`）、也不是控制字符"的字符。
     * 为什么需要它：弹幕文本实测会出现只有 `U+202B`（RIGHT-TO-LEFT EMBEDDING）这种
     * **零宽格式字符**的条目（多半是进场/礼物占位），`String.isBlank()` 判不出来，
     * 而它进列表就是一行看不见内容的"用户名："。
     */
    private fun hasVisibleContent(text: String): Boolean = text.any { ch ->
        !ch.isWhitespace() && Character.getType(ch) != Character.FORMAT.toInt() && !ch.isISOControl()
    }

    companion object {
        /** 端点（PiliPlus `api.dart:325-326` 的同一条） */
        const val HISTORY_URL = "https://api.live.bilibili.com/xlive/web-room/v1/dM/gethistory"

        /** `room_type=0` = 普通直播（B 站 Web 前端同款参数） */
        private const val ROOM_TYPE_NORMAL = "0"

        /** 本地网络/解析异常（不是服务端返回的 code；与 `LiveDanmakuSendAPI.CODE_NETWORK` 同值） */
        const val CODE_NETWORK = -1000

        /** `timeline` 的格式（bilibili-API-collect 文档：`yyyy-MM-dd HH:mm:ss`） */
        private const val TIMELINE_PATTERN = "yyyy-MM-dd HH:mm:ss"

        private const val LIVE_ORIGIN = "https://live.bilibili.com"
        private const val LIVE_REFERER = "https://live.bilibili.com/"
    }
}

/**
 * `gethistory` 的返回壳。**每个字段都给默认值**：直播接口的字段增删很随意，
 * 少一个字段不该让"进房铺历史"整条功能失败（`MiaoJson` 已开 `ignoreUnknownKeys`）。
 */
@Serializable
private data class HistoryResponse(
    val code: Int = -1,
    val message: String = "",
    val data: HistoryData? = null,
)

@Serializable
private data class HistoryData(
    val admin: List<HistoryItem> = emptyList(),
    val room: List<HistoryItem> = emptyList(),
)

@Serializable
private data class HistoryItem(
    val text: String = "",
    val uid: Long = 0L,
    val nickname: String = "",
    /** `yyyy-MM-dd HH:mm:ss`（服务端本地时间） */
    val timeline: String = "",
    val isadmin: Int = 0,
    @SerialName("dm_type")
    val dmType: Int = 0,
    val user: HistoryUser? = null,
)

@Serializable
private data class HistoryUser(
    val uid: Long = 0L,
    val base: HistoryUserBase? = null,
)

@Serializable
private data class HistoryUserBase(
    val name: String = "",
)
