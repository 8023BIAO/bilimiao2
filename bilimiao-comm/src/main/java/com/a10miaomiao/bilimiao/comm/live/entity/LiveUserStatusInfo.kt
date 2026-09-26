package com.a10miaomiao.bilimiao.comm.live.entity

import kotlinx.serialization.Serializable

/**
 * `room/v1/Room/get_status_info_by_uids` 的**单条**返回值 —— "这个 UP 现在在不在播"。
 *
 * ★为什么单独建这个实体，而不复用 [LiveRoomInitInfo] / `LiveRoomItem`：
 *   那两类的语义是"某个房间"，而本接口的语义是"关注列表里的某个人" ——
 *   返回体是**以 uid 为 key 的 Map**，字段里还多一整套 `area_v2_*`。
 *   硬凑成一个类只会让两边都变脏。
 *
 * ★实测（2026-09，容器内 curl，无 Cookie / 无 WBI 签名，仅带 UA + live Referer）：
 *   - 一次 30 个 uid → `code=0` 且**30 条全回**；一次 60 个 uid → 只回 19 条
 *     （那批 uid 是瞎编的，没房间的自然不回）。所以调用方按 30 个一批切。
 *   - 参数必须写成 `uids[]=1&uids[]=2` 重复键；**逗号拼接（`uids=1,2`）实测 `code=1` 报错**。
 *   - 值本身用 `[]` 原样或 `%5B%5D` 都可以，两种都通。
 *
 * ★为什么所有字段都给默认值：
 *   MiaoJson 没开 `coerceInputValues`（见 MiaoJson.kt:12-16），少一个字段就抛
 *   SerializationException。而这个接口在不同 uid 上返回的字段集合并不完全一致
 *   （比如没房间的 uid 连 `room_id` 都可能没有），所以一律给默认值兜底。
 */
@Serializable
data class LiveUserStatusInfo(
    val uid: Long = 0,
    val room_id: Long = 0,
    val short_id: Long = 0,
    /** 0 未开播 / 1 直播中 / 2 轮播（取值语义见 [LiveStatus]） */
    val live_status: Int = 0,
    val uname: String = "",
    val face: String = "",
    val title: String = "",
    val cover_from_user: String = "",
    val area_v2_name: String = "",
    val online: Long = 0,
)

/**
 * 从 [LiveUserStatusInfo] 提炼出来的**领域模型** —— UI 层真正关心的只有两件事：
 * "在不在播" 和 "在播的话房间号是多少"。
 *
 * ★为什么要再包一层、不直接让 UI 用 [LiveUserStatusInfo]：
 *   1. UI 不该知道 `live_status` 的整数语义（0/1/2 哪个算在播），那是接口细节；
 *   2. 用户空间那条路的"在播"信息来自**另一个接口**（`x/v2/space` 的 `live` 对象，
 *      字段是驼峰 `liveStatus`），两层数据源最终都要归一成同一个类型，
 *      UI 组件才能做到"不管数据从哪来，长得都一样"。
 */
data class LiveUserStatus(
    val uid: String,
    /** 真正在播（`live_status == 1`）。★轮播（`== 2`）刻意不算 —— 见 [LiveStatus.isPlayable] */
    val isLive: Boolean,
    /** 直播间**真实**房间号；未开播时为 0 */
    val roomId: Long,
) {
    companion object {
        /** 明确"没在播"的结果，省得每次都 new 一个 */
        fun off(uid: String) = LiveUserStatus(uid = uid, isLive = false, roomId = 0)

        /**
         * 由接口的整型状态构造。
         *
         * ★为什么只认 `LIVE(1)`：轮播房（2）进去是**没有播放流**的
         *   （见 [LiveStatus.isPlayable] 的实测注释），给用户挂个"直播中"再点进去看黑屏，
         *   比不挂标记更糟。PiliPlus 也是同一判据
         *   （`roomId: live?.liveStatus == 1 ? live!.roomid : null`，
         *   见 lib/pages/member/widget/user_info_card.dart:529）。
         */
        fun of(uid: String, liveStatus: Int, roomId: Long): LiveUserStatus {
            val live = liveStatus == LiveStatus.LIVE && roomId > 0
            return LiveUserStatus(uid = uid, isLive = live, roomId = if (live) roomId else 0)
        }
    }
}
