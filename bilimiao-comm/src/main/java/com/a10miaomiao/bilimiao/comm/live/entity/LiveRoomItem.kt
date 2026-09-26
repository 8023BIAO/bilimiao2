package com.a10miaomiao.bilimiao.comm.live.entity

import kotlinx.serialization.Serializable

/**
 * 直播房间列表里的一条（`room/v1/Area/getRoomList` 的 data 元素，纯新增，第三阶段 A 路）。
 *
 * ★实测（2026-02，容器内 curl）：该接口**返回的字段就下面这些**（一次性把所有 key 打出来核对过），
 *   没有 `live_status`、没有 `live_time`、没有 `watched_show`。这一点很关键：
 *
 *   - **`live_status` 接口不给**。所以 [live_status] 的默认值是 [LiveStatus.LIVE]：
 *     这个接口是"按分区取**正在直播**的房间"，列表里的房间本来就是在播的，
 *     默认值 = 事实；哪天后端真把这个字段补上，解析出来就能自动生效（UI 侧的"未开播/轮播"
 *     角标逻辑见 HomeLiveContent.kt 的 LiveRoomCard，不需要再改）。
 *   - **`uname`/`face` 接口给**（这正是它比 `get_info` 好用的地方：`room/v1/Room/get_info`
 *     实测不返回主播名，要主播信息还得再打一次 h5 接口 —— 列表页一次就能拿到卡片要的全部字段，
 *     35 个房间不用发 35 次请求）。
 *
 * 封面字段有 4 个（`user_cover`/`cover`/`system_cover`/`show_cover`）：
 * 实测 `user_cover == cover`，`system_cover` 是系统截的**关键帧**（直播没封面时用它）。
 * 卡片取 `cover`（拿不到再退 `system_cover`），见 HomeLiveContent.kt 的 `LiveRoomItem.coverUrl`。
 */
@Serializable
data class LiveRoomItem(
    /** 真实房间号。★注意：这些房间号可以直接喂给 LivePlayerActivity（它自己会 room_init 换算，真实号是幂等的） */
    val roomid: Long = 0,
    /** 主播 uid（= LivePlayerActivity 里 `mid` 的来源，也给屏蔽规则"按 UP mid 屏蔽"用） */
    val uid: Long = 0,
    val title: String = "",
    val uname: String = "",
    /** 当前人气值（不是"看过的人数"，是热度，可能上百万） */
    val online: Long = 0,
    /** 房间封面（主播自传） */
    val user_cover: String = "",
    /** 房间封面（实测与 user_cover 相同，接口给了两个就都收着，取不到时互相兜底） */
    val cover: String = "",
    /** 系统关键帧截图：没有封面时用它兜底，卡片不会开天窗 */
    val system_cover: String = "",
    /** 主播头像（当前 UI 没用到，先收着：卡片右下角若要放头像不用再改接口层） */
    val face: String = "",
    /** 形如 `/5050` 的站内路径。★当前不用它跳转（走原生播放页），留着做兜底/分享 */
    val link: String = "",
    /** 所属**子分区** id/名（如 21 / 视频唱见） */
    val area_id: Long = 0,
    val area_name: String = "",
    /** 所属**顶级分区** id/名（如 1 / 娱乐）。卡片的"分类角标"用它 */
    val parent_id: Long = 0,
    val parent_name: String = "",
    /** 与 area_id/area_name 同义的另一套字段，实测值完全相同（Web 端新版字段），保留以便后端切换 */
    val area_v2_id: Long = 0,
    val area_v2_name: String = "",
    val area_v2_parent_id: Long = 0,
    val area_v2_parent_name: String = "",
    /** 开播状态。★接口当前不返回该字段 → 取默认值 [LiveStatus.LIVE]，详见类注释 */
    val live_status: Int = LiveStatus.LIVE,
) {
    /**
     * 卡片要用的封面地址：优先主播自传封面，没有就用系统关键帧。
     * 为什么在实体里加这个只读属性而不是在 UI 里判断：封面兜底是**数据层**的事，
     * 以后 B 路（直播搜索页）复用同一个实体时不会再漏一次兜底。
     */
    val coverUrl: String get() = cover.ifBlank { user_cover.ifBlank { system_cover } }
}
