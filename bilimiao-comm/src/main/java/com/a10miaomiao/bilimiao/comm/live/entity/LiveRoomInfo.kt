package com.a10miaomiao.bilimiao.comm.live.entity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 直播间信息实体（第一阶段骨架，仅用于直播功能，未接入任何现有页面）。
 *
 * ★ 为什么 room_init 和 get_info 要分成两个类、而不是合成一个：
 *   两边都有一个叫 `live_time` 的字段，但**类型不一样** ——
 *   - `room/v1/Room/room_init` 的 `live_time` 是数字（未开播时是很小的负数）；
 *   - `room/v1/Room/get_info`   的 `live_time` 是字符串 `"0000-00-00 00:00:00"`。
 *   而 MiaoJson 只开了 ignoreUnknownKeys / explicitNulls / isLenient，**没有开
 *   coerceInputValues**（见 MiaoJson.kt:12-16），类型不匹配会直接抛
 *   SerializationException。所以宁可两个类分开建模，也别为了"省一个类"让解析炸掉。
 *
 * ★ 实测结论（2026-09-25，房间 5440/7734200）：这两个接口**都不需要登录态**，
 *   无 Cookie、无 UA、无 Referer 的裸请求同样返回 code=0。
 */

/** `live_status` 的取值（★实测：0 未开播 / 1 直播中 / 2 轮播）。 */
object LiveStatus {
    const val OFF = 0
    const val LIVE = 1
    const val ROUND = 2

    /**
     * 只有 [LIVE] 才拿得到播放流。
     * ★实测：轮播房（[ROUND]）虽然 code=0，但 getRoomPlayInfo 的
     * `data.playurl_info` 是 null —— 光判断 code 会误判成"可以播"。
     */
    fun isPlayable(status: Int): Boolean = status == LIVE
}

/** `room/v1/Room/room_init` 的 data：短号 → 真实房间号 + 开播状态。 */
@Serializable
data class LiveRoomInitInfo(
    val room_id: Long = 0,
    val short_id: Long = 0,
    /** 主播 uid */
    val uid: Long = 0,
    val live_status: Int = 0,
    /** 注意：本接口是数字（毫秒/负数）；get_info 里同名字段是字符串 */
    val live_time: Long = 0,
    val is_hidden: Boolean = false,
    val is_locked: Boolean = false,
    val is_portrait: Boolean = false,
    val encrypted: Boolean = false,
    val pwd_verified: Boolean = false,
    val room_shield: Int = 0,
    val special_type: Int = 0,
)

/** `room/v1/Room/get_info` 的 data：房间详情（标题/人气/分区）。 */
@Serializable
data class LiveRoomDetail(
    val room_id: Long = 0,
    val short_id: Long = 0,
    val uid: Long = 0,
    val title: String = "",
    /** HTML 富文本（带 <p> 标签），要不要渲染由 UI 决定 */
    val description: String = "",
    /** ★本接口的封面字段叫 user_cover（h5 接口里叫 cover），别混用 */
    val user_cover: String = "",
    val background: String = "",
    val keyframe: String = "",
    val live_status: Int = 0,
    /** 本接口是字符串 */
    val live_time: String = "",
    /** 关注数 */
    val attention: Long = 0,
    /** 当前人气值 */
    val online: Long = 0,
    val area_id: Long = 0,
    val area_name: String = "",
    val parent_area_id: Long = 0,
    val parent_area_name: String = "",
    val tags: String = "",
    val is_portrait: Boolean = false,
)

/**
 * `xlive/web-room/v1/index/getInfoByRoom` 的 data —— **只声明"这个房间关没关弹幕"要用的两块**。
 *
 * ★为什么只声明这两块（其余 100+ 个字段一律靠 `ignoreUnknownKeys` 丢掉）：
 *   本类的唯一用途是判"该直播间是否关闭了弹幕"，多声明一个字段就多一份"类型不匹配 →
 *   整个响应解析失败 → 判据丢失"的风险（MiaoJson 没开 coerceInputValues，见本文件顶部注释）。
 *
 * ## 判据出处（2026-09-26 实测，官方 Web 播放器自己用的就是这两个字段）
 * 官方 `live.bilibili.com` 的播放器 bundle（`blfe-live-room/static/js/app.*.js`）：
 * ```js
 * // 房间信息默认态（照 API 结构写的那份 state）
 * switch_info:{close_guard:!1,close_gift:!1,close_online:!1,close_danmaku:!1}
 * // 逐项功能开关 → UI 布尔
 * t.filterSwitchInfo=function(t){ … return {
 *   isShowDanmakuEditor: u("room-danmaku-editor"),   // ← 弹幕输入框显不显示
 *   … } }
 * ```
 * 也就是说：**官方 Web 端就是拿 `new_switch_info["room-danmaku-editor"]` 决定显不显示弹幕输入框的**。
 */
@Serializable
data class LiveRoomInfoByRoom(
    /**
     * 逐项功能开关：`room-danmaku-editor` = 弹幕输入框、`room-socket` = 弹幕长连接、
     * `room-prop-send` = 礼物、`room-sailing` = 舰长/航海…
     *
     * ★值一律是数字（实测 `0`/`1`，官方默认态里是 `1` = 显示）。
     * ★为什么用 [JsonElement] 而不是 `Int`：这个 map 的 value 类型将来可能变成字符串/布尔，
     *   那一刻若解析炸掉，**连 `switch_info` 一起丢**（同一个响应）。用 JsonElement 收，
     *   读的时候再 `intOrNull`，单个脏值不会连累整条判据。
     */
    val new_switch_info: Map<String, JsonElement>? = null,
    /** ★权威开关块：`close_danmaku` / `close_gift` / `close_guard` / `close_online` */
    val switch_info: LiveRoomSwitchInfo? = null,
)

/**
 * `getInfoByRoom` 的 `data.switch_info`：主播/官方在"直播间设置"里关掉的那几块。
 *
 * ★实测 5 个房间（2026-09-26，真实 Chromium 打开直播间抓的原样响应，见报告）：
 * | 房间 | close_guard | close_gift | close_online | close_danmaku |
 * |---|---|---|---|---|
 * | 8178490 央视新闻（用户实测"关了弹幕"） | **true** | **true** | false | false |
 * | 545068 / 22747736 / 7734200 / 21452505（正常） | false / false / **true** / false | false… | false | false |
 * ⇒ **`close_danmaku` 在这 5 个房间里恒为 false**（它不是"这个房间关了弹幕"的判据，
 *   至少不是本次要的那个；真正区分开的是 [LiveRoomInfoByRoom.new_switch_info] 的
 *   `room-danmaku-editor`）。字段保留下来只为记日志/将来复核，**不要**单独拿它当唯一判据。
 */
@Serializable
data class LiveRoomSwitchInfo(
    /**
     * 关闭弹幕 —— ★实测对本次目标房间是 false，见类注释的对照表（别只信名字）。
     * ★四个字段全部可空：真实响应里这些块**真的会出现 `null`**（实测 `silent_room_info`/`room_config_info`
     *   就是 null），而 MiaoJson 没开 `coerceInputValues` —— 写成非空 Boolean 的话，一个 null 就让
     *   整个响应解析失败，连另一个判据字段一起丢。
     */
    val close_danmaku: Boolean? = null,
    /** 关闭礼物 */
    val close_gift: Boolean? = null,
    /** 关闭舰长/航海 */
    val close_guard: Boolean? = null,
    /** 关闭在线人数 */
    val close_online: Boolean? = null,
)

/**
 * `xlive/web-room/v1/index/getH5InfoByRoom` 的 data。
 * ★这个接口字段最全（含主播名/头像/封面/开播时间），**开播轮询首选**。
 */
@Serializable
data class LiveH5Info(
    val room_info: LiveH5RoomInfo? = null,
)

/** getH5InfoByRoom 里的 room_info */
@Serializable
data class LiveH5RoomInfo(
    val room_id: Long = 0,
    val uid: Long = 0,
    val title: String = "",
    /** ★这里叫 cover（get_info 里叫 user_cover） */
    val cover: String = "",
    val description: String = "",
    val live_status: Int = 0,
    /** 开播时间戳（秒）；未开播为 0 */
    val live_start_time: Long = 0,
    val area_id: Long = 0,
    val area_name: String = "",
    val parent_area_id: Long = 0,
    val parent_area_name: String = "",
    val online: Long = 0,
    val background: String = "",
    val app_background: String = "",
)
