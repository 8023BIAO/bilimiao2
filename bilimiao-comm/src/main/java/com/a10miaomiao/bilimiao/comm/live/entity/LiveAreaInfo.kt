package com.a10miaomiao.bilimiao.comm.live.entity

import kotlinx.serialization.Serializable

/**
 * 直播**分区树**实体（`room/v1/Area/getList`，纯新增，第三阶段 A 路）。
 *
 * ★为什么是"树"而不是一张平表：
 *   B 站把直播分成 **12 个顶级分区**（网游/手游/单机游戏/娱乐/电台/虚拟主播/聊天室/生活/知识/赛事/互动玩法/购物），
 *   每个顶级分区下面再挂几十~几百个**子分区**（实测合计 450 个，如 网游 → 英雄联盟/无畏契约/三角洲行动…）。
 *   `getRoomList` 取房间时两个 id 都要传，所以这两个层级必须原样保留，不能压平。
 *
 * ★实测（2026-02，容器内裸 curl）：
 *   - URL：`https://api.live.bilibili.com/room/v1/Area/getList`
 *   - **不需要登录态、不需要 WBI 签名**；但**必须带 UA + Referer**：
 *     完全裸的请求（无任何 header）实测直接 `HTTP 412 Precondition Failed`（B 站风控），
 *     带 `User-Agent` + `Referer: https://www.bilibili.com/` 就 `code=0`。
 *     工程里 MiaoHttp 默认就会补这两条 + buvid（MiaoHttp.kt:63-68），所以这里什么都不用额外加。
 *   - 返回：12 个顶级分区 / 450 个子分区，**每个子分区都带 `pic` 图标**（实测 450/450 非空）。
 *
 * ★字段类型坑（照抄前先看）：
 *   - 顶级分区的 `id` 是**数字**（`"id":2`）；
 *   - 子分区的 `id` 和 `parent_id` 是**字符串**（`"id":"86"`）。
 *   同一个接口里同一个语义的字段两种类型，所以两个类分开建模（这一点和 room_init / get_info
 *   的 `live_time` 是同一种坑，见 LiveRoomInfo.kt 的注释）。
 */

/** 顶级分区（如「网游」）+ 它下面挂的所有子分区 */
@Serializable
data class LiveAreaGroup(
    /** 顶级分区 id，取房间时要当 `parent_area_id` 传（如 2 = 网游） */
    val id: Int = 0,
    val name: String = "",
    /** 子分区列表。实测 451 个里没有一个为空，但接口理论上可能给 null/缺字段，所以给默认空表 */
    val list: List<LiveArea> = emptyList(),
)

/** 子分区（如「英雄联盟」）。取房间时当 `area_id` 传；`id` 传 "0" 表示"这个顶级分区的全部" */
@Serializable
data class LiveArea(
    /** ★字符串型 id（`"86"`），不是数字 —— 详见文件头注释 */
    val id: String = "",
    val parent_id: String = "",
    val name: String = "",
    /** 分区图标。实测每个子分区都有，可直接给分类网格/标签用 */
    val pic: String = "",
    val parent_name: String = "",
    /** 1 = 热门分区（B 站自己在 App 里会把这些排前面，Web 标签条不排序，先留着备用） */
    val hot_status: Int = 0,
    val area_type: Int = 0,
)
