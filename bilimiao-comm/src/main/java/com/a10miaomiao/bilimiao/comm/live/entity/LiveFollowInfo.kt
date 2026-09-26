package com.a10miaomiao.bilimiao.comm.live.entity

import kotlinx.serialization.Serializable

/**
 * 「我的关注 · 正在直播」的实体层（纯新增文件，2026-09-26）。
 *
 * ## 为什么一个文件里装两条接口的实体
 * 它们回答的是**同一个问题**（"我关注的人里谁在播"），只是用在两个地方：
 *
 * | 用在哪 | 接口 | 外层类型 |
 * | --- | --- | --- |
 * | 首页直播 Tab 顶部区块（"我的关注 N人正在直播" + 头像/卡片行） | `xlive/app-interface/v2/index/feed` 的 `my_idol_v1` 卡片 | [LiveFollowCard] |
 * | 「查看更多 ›」进去的完整列表页 | `xlive/web-ucenter/user/following` | [LiveFollowListData] |
 *
 * 两者**都是 PiliPlus 自己在用的那一条**（出处见各自类注释），不是我们另找的替代品。
 * 放在同一个文件里，是因为它们的字段映射目标完全相同（都是 [LiveRoomItem]），
 * 卡片只认一种实体这件事才有单一出处。
 *
 * ## 为什么所有字段都给默认值
 * [com.a10miaomiao.bilimiao.comm.miao.MiaoJson] 没开 `coerceInputValues`
 * （见 MiaoJson.kt）：JSON 里出现显式 null 而字段是非空类型时会直接抛 SerializationException。
 * 这两个接口的字段集合按账号/实验组会变（实测 `area_name` 在 following 里就是空串而不是 null），
 * 一律给默认值兜底，宁可少显示一个字段，也不要整个区块消失。
 */

// ══════════════════════════════════════════════════════════════════════════
// ① 首页「我的关注」卡片（xlive/app-interface/v2/index/feed 的 my_idol_v1）
// ══════════════════════════════════════════════════════════════════════════

/**
 * `card_list[]` 里 `card_type == "my_idol_v1"` 那一个的 `card_data.my_idol_v1`。
 *
 * ## PiliPlus 出处（逐行核对过）
 * - 卡片类型判定：`lib/models_new/live/live_feed_index/data.dart:22-24`
 *   （`case 'my_idol_v1': followItem = LiveCardList.fromJson(json);`）
 * - 取总数：`lib/pages/live/view.dart:266`
 *   （`final totalCount = item.cardData?.myIdolV1?.extraInfo?.totalCount ?? 0;`）
 * - 取列表：`lib/pages/live/view.dart:303`
 *   （`if (item.cardData?.myIdolV1?.list case final list?)`）
 * - 头部文案：`lib/pages/live/view.dart:275-296`（"我的关注  " + N + "人正在直播"）
 * - 数据从哪来：`lib/http/live.dart:193-257 liveFeedIndex()` →
 *   `lib/http/api.dart:781-782`（`${liveBaseUrl}/xlive/app-interface/v2/index/feed`），
 *   其中 `module_select: 1` 是它刷顶部模块时用的参数（`lib/pages/live/controller.dart:96 queryTop()`）。
 *
 * ## ★实测（2026-09-26，容器内 curl，登录态，见报告 `/root/test/直播优化-关注直播区块-说明.md`）
 * ```
 * 登录 + module_select=1 + relation_page=1 → code=0，10 KB，
 *   card_list = [my_idol_v1, area_entrance_v3]（**没有** small_card_v1）
 *   my_idol_v1.extra_info.total_count = 1
 *   my_idol_v1.list[0] = {roomid:22873552, uid:475468247, uname:"…", face:"…", cover:"…",
 *                         title:"…", area_name:"单机联机", area_v2_name:"吃鸡行动", online:14, …}
 * 未登录（同参数）→ card_list 里**没有** my_idol_v1（只有 area_entrance_v3）→ 区块整块不显示
 * ```
 * 所以"未登录 = 不显示"这条不需要我们自己判登录态，**接口本身就是这个语义**；
 * 我们额外再判一次登录只是为了省掉那条无用请求（见 HomeLiveContent.kt 的 HomeLiveFollowViewModel）。
 */
@Serializable
data class LiveFollowCard(
    /** 正在直播的关注（服务端已按"在播"筛过；实测未在播的人不会出现在这里） */
    val list: List<LiveIdolRoom> = emptyList(),
    val extra_info: LiveFollowExtraInfo? = null,
) {
    /**
     * 「N人正在直播」里的那个 N。
     *
     * ★`extra_info.total_count` 是**服务端给的真实总数**，可能大于 [list] 的长度
     *   （服务端只下发前若干个）—— 这正是「查看更多 ›」的判据来源
     *   （PiliPlus 同款：`totalCount > listLength` 就多放一个"更多"箭头，
     *   见 `lib/pages/live/view.dart:326`）。字段缺席时退回"我拿到了几个"。
     */
    val totalCount: Int get() = extra_info?.total_count ?: list.size

    /** 映射成首页直播卡片统一吃的 [LiveRoomItem]（顺序照接口给的，不重排） */
    val rooms: List<LiveRoomItem> get() = list.map { it.toRoomItem() }
}

/** `my_idol_v1.extra_info`：只收 [total_count]，其余（card_type/show_type/...）本页用不到 */
@Serializable
data class LiveFollowExtraInfo(
    /** 正在直播的关注数（"N人正在直播"的 N） */
    val total_count: Int = 0,
)

/**
 * `my_idol_v1.list[]` 的一条 —— 一个**正在直播的**关注。
 *
 * 字段名与接口逐字对齐（实测 key 集合见报告），改名统一在 [toRoomItem] 里做。
 * 刻意**没有**建模的大字段：
 * - `play_url` / `play_url_h265` / `play_url_card`：单条 2~3 KB 的**带签名取流地址**
 *   （`expires` 秒级过期），进直播间走的是 `roomid` + 原生播放页，收了纯属白占内存；
 * - `link` / `session_id` / `trackid` / `quality_description` / `pendent_list` / `nft_dmark` …：
 *   卡片一个都不显示（`ignoreUnknownKeys` 会直接跳过）。
 */
@Serializable
data class LiveIdolRoom(
    /** 真实房间号（实测这个接口给的就是真实号，可直接进播放页） */
    val roomid: Long = 0,
    /** 主播 uid（点 UP 名进用户空间用） */
    val uid: Long = 0,
    val uname: String = "",
    val face: String = "",
    val cover: String = "",
    val title: String = "",
    /** 当前人气（热度） */
    val online: Long = 0,
    /** 子分区名（如"单机联机"）—— 注意这个字段实测是**父级视角**的老名字 */
    val area_name: String = "",
    val area_v2_id: Long = 0,
    /** 子分区名（新版字段，如"吃鸡行动"） */
    val area_v2_name: String = "",
    val area_v2_parent_id: Long = 0,
    val area_v2_parent_name: String = "",
    /** 本接口实测不返回 `live_status`；默认 [LiveStatus.LIVE]（"它列出来的就是在播的"，与 getRoomList 同一个理由） */
    val live_status: Int = LiveStatus.LIVE,
)

/** [LiveIdolRoom] → 首页直播卡片统一吃的 [LiveRoomItem]（UI 一行都不用为"关注"特判） */
fun LiveIdolRoom.toRoomItem(): LiveRoomItem = LiveRoomItem(
    roomid = roomid,
    uid = uid,
    title = title,
    uname = uname,
    online = online,
    cover = cover,
    face = face,
    area_id = area_v2_id,
    // 卡片上的分区角标：优先新版子分区名，退回老字段（哪个非空用哪个，两个都空就不显示角标）
    area_name = area_v2_name.ifBlank { area_name },
    parent_id = area_v2_parent_id,
    parent_name = area_v2_parent_name,
    live_status = live_status,
)

// ══════════════════════════════════════════════════════════════════════════
// ② 「查看更多」的完整列表（xlive/web-ucenter/user/following）
// ══════════════════════════════════════════════════════════════════════════

/**
 * `xlive/web-ucenter/user/following` 的 data 外壳（「关注直播」完整列表页的数据源）。
 *
 * ## PiliPlus 出处（逐行核对过）
 * - URL：`lib/http/api.dart:784-785`
 *   （`static const String liveFollow = '${liveBaseUrl}/xlive/web-ucenter/user/following';`）
 * - 调用 + 参数：`lib/http/live.dart:259-272 liveFollow(int page)`
 *   （`page` / `page_size: 9` / `ignoreRecord: 1` / `hit_ab: true`）
 * - 页面标题用它：`lib/pages/live_follow/view.dart:32-35`
 *   （`Text(count != null ? '$count人正在直播' : '关注直播')`，count = [live_count]）
 * - "到底了"用它：`lib/pages/live_follow/controller.dart:18-24`
 *   （`count != null && length >= count` → isEnd，count = [count] = 关注总数）
 * - 客户端只留"在播"的：`lib/models_new/live/live_follow/data.dart:26-29`
 *   （`.where((i) => i['live_status'] == 1)`）
 *
 * ## ★实测（2026-09-26，容器内 curl，登录态）
 * | 请求 | 结果 |
 * | --- | --- |
 * | 带登录 Cookie，`page=1&page_size=9&ignoreRecord=1&hit_ab=true` | `code=0`，`count=3`（关注总数）、`live_count=1`、`list` 3 条（含 1 条 `live_status=1`） |
 * | 同上但**去掉** `ignoreRecord=1` | `live_count=0`（！）—— 这个参数不是可选的，去掉就把"在播"判据打没了 |
 * | `page_size=1/2/3` | 服务端如实按 1/2/3 条返回，`totalPage` 跟着变 → **分页真的生效** |
 * | `page_size=50` | 被服务端夹回 **10**（echo 的 `pageSize=10`）→ 单页上限 10，PiliPlus 用 9 是安全值 |
 * | `page=2`（只有 3 个关注） | `code=0` + `list=[]`（不报错）→ "到底了"判空数组即可 |
 * | **不带** Cookie | `code=-101 账号未登录`；带假 Cookie → `code=-400 请求错误` |
 *
 * ## 为什么 [list] 里既有在播也有没在播的
 * 这个接口是**直播站的关注列表**（`count` = 关注总数，实测 3），每个人带自己的 `live_status`；
 * 在播只是其中一部分（[live_count]）。所以列表页要**边翻页边筛**（照 PiliPlus 的做法），
 * 而不是"一页就是一个在播的人"。
 */
@Serializable
data class LiveFollowListData(
    val title: String = "",
    /** 服务端每页条数（实测 = 我们请求的 page_size，上限 10） */
    val pageSize: Int = 0,
    val totalPage: Int = 0,
    val list: List<LiveFollowRoom> = emptyList(),
    /**
     * **关注总数**（含没在播的）。
     *
     * ★只用于诊断，**不参与"到底了"的判定**：PiliPlus 的 `checkIsEnd` 用的是 [live_count]
     *   （`lib/pages/live_follow/controller.dart:18-24`：`length >= count`，而
     *   `count.value = response.liveCount`），我们照它来（见 LiveFollowPage 的 `loadData`）。
     *   顺带纠正一个容易想当然的写法：拿"关注总数"当终点是**错的** ——
     *   这个接口一页里混着没在播的人，翻到第 N 页时 `list` 的长度和关注总数并不是同一个量纲。
     */
    val count: Int = 0,
    /** **正在直播**的关注数 —— 页面标题「N人正在直播」的 N，也是"已经把在播的都拿到了"的终点 */
    val live_count: Int = 0,
) {
    /**
     * 这一页里**正在直播**的那些（`live_status == 1`），已映射成首页卡片统一吃的 [LiveRoomItem]。
     *
     * ★轮播（`live_status == 2`）刻意不算：轮播房没有播放流（[LiveStatus.isPlayable] 的实测注释），
     *   给用户列一个"在直播"但点进去黑屏的房间比不列更糟。PiliPlus 的判据也是 `== 1`。
     */
    val liveRooms: List<LiveRoomItem>
        get() = list.filter { it.live_status == LiveStatus.LIVE }.map { it.toRoomItem() }
}

/**
 * `list[]` 的一条 —— 直播站关注列表里的一个人（**可能是没在播的**）。
 *
 * ★`room_cover` 而不是 `cover`、`area_name_v2` 而不是 `area_name`：
 *   实测这一条返回里 `area_name` 是**空串**、`area_name_v2` 才有值（"吃鸡行动"）；
 *   封面同理叫 `room_cover`（`cover` 这个 key 压根没有）。字段名必须按实测来，
 *   照 `getRoomList` 的习惯写 `cover` 会永远拿到空封面。
 */
@Serializable
data class LiveFollowRoom(
    val roomid: Long = 0,
    val uid: Long = 0,
    val uname: String = "",
    val title: String = "",
    val face: String = "",
    /** 0 未开播 / 1 直播中 / 2 轮播（只有 1 会进列表，见 [LiveFollowListData.liveRooms]） */
    val live_status: Int = 0,
    /** 房间封面（实测这一条接口的封面上这个 key） */
    val room_cover: String = "",
    /** 观看人数文案（如 "19"）—— 卡片右下角那块"人看过"用它兜底 */
    val text_small: String = "",
    /** 子分区名（实测为空串，真正有值的是 [area_name_v2]） */
    val area_name: String = "",
    val area_name_v2: String = "",
    val parent_area_id: Long = 0,
    val area_id: Long = 0,
)

/**
 * [LiveFollowRoom] → 首页直播卡片统一吃的 [LiveRoomItem]。
 *
 * ★`online` 用 [LiveFollowRoom.text_small] 反解：这个接口不给人气数字，只给"19"这样的成品文案；
 *   卡片那边 `online > 0` 才显示"人看过"，反解成功就有、失败（空串/非数字）就自然不显示 ——
 *   既不硬编 0、也不为它多打一次接口。
 */
fun LiveFollowRoom.toRoomItem(): LiveRoomItem = LiveRoomItem(
    roomid = roomid,
    uid = uid,
    title = title,
    uname = uname,
    online = text_small.trim().toLongOrNull() ?: 0L,
    cover = room_cover,
    face = face,
    area_id = area_id,
    area_name = area_name_v2.ifBlank { area_name },
    parent_id = parent_area_id,
    live_status = live_status,
)
