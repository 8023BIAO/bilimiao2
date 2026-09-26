package com.a10miaomiao.bilimiao.comm.live

import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.live.entity.LivePlayUrlInfo
import com.a10miaomiao.bilimiao.comm.live.entity.LiveRoomDetail
import com.a10miaomiao.bilimiao.comm.live.entity.LiveRoomInitInfo
import com.a10miaomiao.bilimiao.comm.live.entity.LiveStatus
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger

/**
 * 直播房间**探活**（纯新增，2026-09-26）：一次调用回答"这个房间现在能不能播、为什么不能"。
 *
 * ## 为什么不能只问 `room_init`
 * 修前，"能不能起播"和"开播轮询"都只信 `room/v1/Room/room_init` 一个接口：
 * ```
 * 进房失败/下播 → startOfflinePolling()
 *     while (true) { delay(30s); val init = resolveRoom(rawRoomId) ?: continue; ... }
 * ```
 * ★`?: continue` 这一句是**死循环**：只要 `room_init` 拿不到（风控 412/HTML 拦截页/超时），
 *   轮询就永远"继续下一轮"，状态栏永远停在"房间未开播，30s 后自动重试…"——
 *   而房间其实在播、官方页面也正常（2026-09-26 用户反馈的正是这个观感）。
 *   （实测：本容器在做批量扫描时 `room_init` 会成片回 412/空体，风控一过又全好；
 *     弹幕/取流接口是另外的配额，同一时刻照样 200。）
 *
 * ## 探活顺序（一个接口失败不代表房间没开播，必须按"权威度"降级问）
 * 1. `getRoomPlayInfo`（**最权威**：有没有真的下发流 + 它自己给的 `live_status`）；
 * 2. `room/v1/Room/get_info`（次之：给 `live_status` / 真实房间号，且**不带 WBI、不吃 412**，
 *    实测同一时刻 `getH5InfoByRoom` 已经 `-352` 了它还是 code=0）；
 * 3. `room/v1/Room/room_init`（最后：短号 → 真实房间号 + 主播 uid；它也是最容易被风控的那个）。
 *
 * 三个都失败时才返回"什么都没问到"（`liveStatus = [STATUS_UNKNOWN]`），
 * **调用方在未知时应该继续重试，而不是显示"未开播"**。
 */
object LiveRoomProbe {

    /**
     * `live_status` 未知（三个接口都没问到）。
     * ★刻意**不用 0**：0 是"服务端明确说没开播"，而这里是"我们没问到" ——
     *   把两者混在一起，就又回到了"接口一抖就显示未开播"的老毛病。
     */
    const val STATUS_UNKNOWN = -1

    /**
     * 探活结果。
     *
     * @param roomId 真实房间号（问到就用问到的，没问到用入参）
     * @param liveStatus 最可信的一次 `live_status`（[STATUS_UNKNOWN] = 没问到）
     * @param source 上面这个值是谁给的（诊断/日志用）
     * @param playability 取流判定（[LivePlayability]）；只有 `code=0` 的那次才会填
     * @param anchorUid 主播 uid（只有 `room_init` 会给；给不到是 0）
     */
    data class Result(
        val roomId: Long,
        val liveStatus: Int,
        val source: String,
        val playability: LivePlayability?,
        val anchorUid: Long = 0,
    ) {
        /** 已经有了可用的流地址 —— 可以直接起播，不用再问任何接口。 */
        val canPlay: Boolean get() = playability?.hasStream == true

        /**
         * 还要不要继续试/继续轮询。
         * ★"未知"（没问到）也必须为 true —— 宁可多试一轮，也不能把"没问到"显示成"未开播"。
         */
        val shouldKeepTrying: Boolean
            get() = canPlay || liveStatus == STATUS_UNKNOWN ||
                LivePlayabilityJudge.shouldAttemptPlay(liveStatus)
    }

    /**
     * 探一次。**必须在 IO 线程调用**（内部是阻塞式 OkHttp 调用，照 [LiveAPI] 既有用法）。
     *
     * @param roomId 房间号（短号/真实号都行：三个接口自己会解析）
     * @param qn 期望清晰度，只有第 1 步取流会用到
     */
    suspend fun probe(roomId: String, qn: Int = LiveAPI.QUALITY_ORIGIN): Result {
        var realRoomId = roomId.toLongOrNull() ?: 0L
        var liveStatus = STATUS_UNKNOWN
        var source = "none"
        var anchorUid = 0L
        var playability: LivePlayability? = null

        // ① 最权威：有没有真的下发流
        try {
            val res = LiveAPI().playUrl(roomId, qn).awaitCall().json<ResponseData<LivePlayUrlInfo>>()
            val verdict = LivePlayabilityJudge.judge(res)
            playability = verdict
            val data = res.data
            if (data != null) {
                if (data.room_id > 0L) realRoomId = data.room_id
                liveStatus = data.live_status
                source = "getRoomPlayInfo"
            }
            if (verdict.hasStream) {
                return Result(realRoomId, liveStatus, source, verdict, anchorUid)
            }
            if (res.code != 0) {
                miaoLogger().d("直播探活：getRoomPlayInfo 失败", "code" to res.code, "message" to res.message)
            }
        } catch (e: Exception) {
            miaoLogger().d("直播探活：getRoomPlayInfo 异常", "message" to e.message)
        }

        // ② 次之：get_info（拒 WBI、拒风控都比 h5Info/room_init 稳）
        try {
            val res = LiveAPI().roomInfo(roomId).awaitCall().json<ResponseData<LiveRoomDetail>>()
            if (res.isSuccess && res.data != null) {
                val detail = res.data
                if (detail.room_id > 0L) realRoomId = detail.room_id
                if (source == "none") {
                    liveStatus = detail.live_status
                    source = "get_info"
                }
            }
        } catch (e: Exception) {
            miaoLogger().d("直播探活：get_info 异常", "message" to e.message)
        }

        // ③ 最后：room_init（真实房间号 + 主播 uid；也是风控最容易打掉的那个）
        try {
            val res = LiveAPI().roomInit(roomId).awaitCall().json<ResponseData<LiveRoomInitInfo>>()
            if (res.isSuccess && res.data != null) {
                val init = res.data
                if (init.room_id > 0L) realRoomId = init.room_id
                anchorUid = init.uid
                if (source == "none") {
                    liveStatus = init.live_status
                    source = "room_init"
                }
            }
        } catch (e: Exception) {
            miaoLogger().d("直播探活：room_init 异常", "message" to e.message)
        }

        return Result(realRoomId, liveStatus, source, playability, anchorUid)
    }
}
