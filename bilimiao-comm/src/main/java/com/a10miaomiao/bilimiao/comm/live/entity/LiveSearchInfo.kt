package com.a10miaomiao.bilimiao.comm.live.entity

import kotlinx.serialization.Serializable

/**
 * `xlive/app-interface/v2/search_live` 的返回实体（第三阶段 B 路，纯新增文件）。
 *
 * 字段是照**实测响应**逐个对出来的（2026-09-26，`keyword=原神`，30 条/页），
 * 结构（省略与本页无关的字段）：
 * ```json
 * {"code":0,"message":"OK","data":{
 *   "type":"room","page":1,"pagesize":30,"query":"原神",
 *   "room":{"list":[{"roomid":4898018,"cover":"https://i0.hdslb.com/...jpg",
 *                    "title":"...","name":"主播名","face":"https://i0.hdslb.com/...jpg",
 *                    "online":170775,
 *                    "watched_show":{"num":170775,"text_small":"17.0万",
 *                                    "text_large":"17.0万人气", ...},
 *                    "link":"https://live.bilibili.com/4898018?..."}],
 *           "total_room":1000,"total_page":34},
 *   "user":{...}}}
 * ```
 *
 * ## 为什么所有字段都给默认值
 * [com.a10miaomiao.bilimiao.comm.miao.MiaoJson] 只开了 ignoreUnknownKeys / explicitNulls /
 * isLenient，**没开 coerceInputValues** —— 字段类型对不上会直接抛 SerializationException。
 * 搜索接口是服务端最容易悄悄改结构的一类接口（还可能按实验分组下发不同字段），
 * 这里全部给默认值 + 可空，宁可少显示一个"人气"，也不要整页解析失败。
 *
 * ## 为什么没有 `live_status`
 * 实测这个接口**不返回**开播状态；抽查前 6 条房间的 `room/v1/Room/room_init` 全部
 * `live_status=1`（都是正在直播的）—— 因为搜索索引里只有开播中的房间。
 * 所以卡片上那个"直播中"角标是**接口语义**（搜出来的就是直播中的），
 * 不是逐条查出来的；要逐条查就得为每张卡片多打一次接口，得不偿失。
 *
 * ## 为什么没有 `link`
 * `link` 是几百字符的超长跳转串（内含播放地址、CDN 签名），本页用不到；
 * `ignoreUnknownKeys = true` 会直接忽略它，建模它只会白解析几百字节。
 */

/** `search_live` 的 data 外壳。`type=user` 时房间里是 `user` 对象，本次不做。 */
@Serializable
data class LiveSearchInfo(
    val type: String = "",
    val page: Int = 0,
    val pagesize: Int = 0,
    /** `type=room` 时的房间列表 */
    val room: LiveSearchRoomList? = null,
    /** 服务端实际用的搜索词（可能被改写/纠错，仅作参考） */
    val query: String = "",
)

/** `data.room`：房间列表 + 总数 + 总页数 */
@Serializable
data class LiveSearchRoomList(
    val list: List<LiveSearchRoomItem> = emptyList(),
    /** 命中总数。★实测会被截到 1000（搜"原神"时就是 1000），当"上限"看，别当成精确值 */
    val total_room: Int = 0,
    /** 总页数（30 条/页）。"到底了"用它判断最稳（超出范围时接口回空列表而不是报错） */
    val total_page: Int = 0,
)

/** 一条直播间搜索结果。字段名与接口一一对应（接口是下划线风格，这里不改名，省得对不上） */
@Serializable
data class LiveSearchRoomItem(
    /** 房间号（真实号，可直接喂给 LivePlayerActivity）。用 Long：B 站房间号已到 10 位数 */
    val roomid: Long = 0,
    /** 封面；本接口的封面字段叫 cover（房间详情接口里叫 user_cover / cover，别混用） */
    val cover: String = "",
    /** 标题（★实测是纯文本，不带 web 搜索那种 `<em class="keyword">` 高亮标签） */
    val title: String = "",
    /** 主播名 */
    val name: String = "",
    /** 主播头像。PiliPlus 的搜索卡片没用它，这里也留着备用 */
    val face: String = "",
    /** 当前人气值（数字） */
    val online: Long = 0,
    /** 人气/看过的**成品文案**（"15.5万人气" / "9.9万人看过"），卡片上直接显示它 */
    val watched_show: LiveSearchWatchedShow? = null,
)

/**
 * `watched_show`：人气展示信息。
 * ★`switch` 字段决定文案含义：false = "N人气"（正在看），true = "N人看过"（累计）。
 * 卡片只要 `text_large`，所以不再为 `switch` 建模（要区分含义时再加）。
 */
@Serializable
data class LiveSearchWatchedShow(
    val num: Long = 0,
    val text_small: String = "",
    /** 卡片右下角直接显示这个（如 "15.5万人气"） */
    val text_large: String = "",
)
