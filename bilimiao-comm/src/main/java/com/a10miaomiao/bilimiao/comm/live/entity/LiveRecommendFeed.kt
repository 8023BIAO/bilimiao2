package com.a10miaomiao.bilimiao.comm.live.entity

import kotlinx.serialization.Serializable

/**
 * 首页直播「推荐」Tab 的响应实体（`xlive/app-interface/v2/index/feed`，本次纯新增文件）。
 *
 * ## 这个接口是什么、为什么用它做「推荐」
 * 它就是**B 站直播首页那条个性化推荐流**（不是"按分区/按人气排"的人工榜单）：
 * 服务端按账号画像 + 实时热度分流下发，客户端**不需要、也无法**自己定义"推荐哪些分区"。
 * 这正是用户要的："这个推荐应该是我们定义不了哪些分区……系统的 API 分流推荐给我们，
 * 我们用它的就行"。
 *
 * 端到端出处（照 PiliPlus 抄，行号已核对）：
 *  - URL 常量：PiliPlus `lib/http/api.dart:781-782`
 *    ```
 *    static const String liveFeedIndex =
 *        '${HttpString.liveBaseUrl}/xlive/app-interface/v2/index/feed';
 *    ```
 *  - 调用：PiliPlus `lib/http/live.dart:193-257 liveFeedIndex({required int pn, ...})`
 *    （参数 `page` = 页码；请求前过 `AppSign.appSign(params)`，见 `lib/utils/app_sign.dart`）
 *  - 「推荐」这个 Tab 的摆放：PiliPlus `lib/pages/live/view.dart:105-128` ——
 *    分区标签条的第一个格子固定是「推荐」（`text: isFirst ? '推荐' : item.title!`），
 *    而且它是**合成项**：`onTap: (_) => controller.onSelectArea(index, isFirst ? null : item)`
 *    传的 `item` 是 `null`（= 不属于任何分区），落回
 *    `lib/pages/live/controller.dart:78-88 customGetData()`
 *    （`areaIndex == 0` → `liveFeedIndex`，否则 `liveSecondList`）。
 *
 * ## ★实测（2026-09-26，本容器 curl，原始响应见交付报告 `/root/test/直播优化-推荐Tab-说明.md`）
 * | 请求 | 结果 |
 * | --- | --- |
 * | 完全裸请求（只有 appkey/ts，无 sign） | `{"code":-3,"message":"签名错误"}` |
 * | sign 故意写错 | `{"code":-3,"message":"签名错误"}` |
 * | 工程既有 APP 签名（`ApiHelper.createParams`，HD appkey） | `code=0`，**20 条 `small_card_v1`**，`has_more=1` |
 * | 同一参数集**加上假 access_key/mid** | 仍 `code=0`（登录态失效也不会把推荐打挂） |
 * | `page=1/2/3/4/60` | 每页都是 20 条、**互不重复**；`page_size=20/30/50` 传了也**只回 20 条**（接口不认这个参数） |
 * | 未登录（无 Cookie） | `code=0`，`trackid` 形如 `rec-fallback-live-…`（**兜底推荐**：没画像也能用，有登录态会更贴人） |
 *
 * ## 与 [LiveRoomItem] 的字段差异（**每个都有实测依据**）
 * | 推荐流字段 | 映射到 | 说明 |
 * | --- | --- | --- |
 * | `id` | `roomid` | ★推荐流**没有 `roomid` 字段**（实测整份响应 `"roomid"` 出现 0 次），房间号只在 `id` 里；PiliPlus 也是这么兜的（`card_data_list_item.dart:36` → `roomid: json['roomid'] ?? json['id']`） |
 * | `area_id` / `area_name` | 同名 | 卡片上的分区角标直接用 `area_name`（实测 20/20 非空，如 292/火影忍者手游） |
 * | `parent_area_id` / `parent_area_name` | `parent_id` / `parent_name` | 推荐流用的是带 `_area_` 的这一套名字，语义与 `getRoomList` 的 `parent_id/parent_name` 完全一致（顶级分区，如 3/手游），所以在这里就地改名对齐 |
 * | `uid`/`uname`/`face`/`cover`/`system_cover`/`title`/`online` | 同名 | 实测 20/20 都非空、`uid > 0`（卡片上"点 UP 名进空间"照常可用） |
 * | `live_status` | 同名 | **推荐流绝大多数条目不给这个字段** → 映射时按 [LiveStatus.LIVE] 兜底（够用：推荐流只推在播的房间）；哪天真给了，这里会自动生效，卡片不用改 |
 *
 * ## 刻意**没有**建模的字段（以及为什么）
 * - `link`：推荐流的 `link` 是一整条**带签名 CDN 参数的播放器 URL**（单条 2~3KB，`expires` 秒级过期），
 *   与 [LiveRoomItem.link] 文档里"形如 `/5050` 的站内路径"**不是一回事**；页面跳转走的是
 *   `roomid` + 原生播放页，所以不收它（收了只是白占内存）。
 * - `watched_show` / `like_show` / `pendent_list` / `feedback` / `click_callback` …：
 *   当前卡片一个都不显示，等 UI 要用时再加字段，避免"先收一堆用不上的数据"。
 * - `banner_v2` / `my_idol_v1` / `area_entrance_v3` 等**其它 card_type**：本页只要直播卡片，
 *   [LiveRecommendFeed.rooms] 只挑 `small_card_v1`；广告位/横幅/分区入口自然被过滤掉。
 *
 * ## 为什么所有字段都是可空 + 有默认值
 * 工程的 `MiaoJson` 是 `ignoreUnknownKeys = true` 但 **`coerceInputValues = false`**
 * （`comm/miao/MiaoJson.kt:10-14`）：JSON 里出现**显式 null** 而字段又是非空类型时，
 * kotlinx 会直接抛 SerializationException（不是回落默认值）。推荐流是"千人千面"的接口，
 * 不同账号/不同实验组的字段缺席与 null 都可能出现 —— 全部声明成可空再在 [toRoomItem] 里兜底，
 * 才不会被一条脏数据把整页打崩。
 */
@Serializable
data class LiveRecommendFeed(
    /** 卡片数组。一页实测 20 个 `small_card_v1`（还可能有 banner/分区入口等其它类型） */
    val card_list: List<LiveRecommendCard> = emptyList(),
    /**
     * 还有下一页：**只有明确为 0 才代表到底了**。
     *
     * ★实测每一页都是 1（推荐流是"无限流"，翻到第 60 页照样给 20 条）；
     *   声明成可空是为了"字段哪天缺席"也不误判 —— null 在页面侧被当作"服务端没说，
     *   那就按每页条数判断"（见 HomeLiveContent.kt 的 `requestPage`），而不是当成 0 把分页停掉。
     */
    val has_more: Int? = null,
) {

    /**
     * 这一页里**能直接塞进卡片网格**的直播间（顺序照接口给的推荐顺序，不重排）。
     *
     * ★为什么顺序不能动：推荐流的**顺序本身就是推荐结果**（`index` 字段也是它），
     *   再按人气/开播时间排一遍等于把"推荐"降级成"榜单"，那就失去这个 Tab 的意义了。
     */
    val rooms: List<LiveRoomItem>
        get() = card_list.mapNotNull { it.card_data?.small_card_v1 }.map { it.toRoomItem() }
}

/** `card_list` 的一个元素：`card_type` 决定 `card_data` 里是哪一个 key（实测有 banner_v2 / my_idol_v1 / area_entrance_v3 / small_card_v1） */
@Serializable
data class LiveRecommendCard(
    val card_type: String = "",
    val card_data: LiveRecommendCardData? = null,
)

/** 卡片内容。本页只关心直播卡片 `small_card_v1`，其它 key 一律不建模（`ignoreUnknownKeys` 会跳过） */
@Serializable
data class LiveRecommendCardData(
    val small_card_v1: LiveRecommendRoom? = null,
)

/** 推荐流里的一张直播卡片。字段名与接口**逐字对齐**（不改名），改名统一在 [toRoomItem] 里做 */
@Serializable
data class LiveRecommendRoom(
    /** ★房间号在 `id` 里（推荐流没有 `roomid`），见文件头表格 */
    val id: Long? = null,
    val uid: Long? = null,
    val uname: String? = null,
    val title: String? = null,
    /** 实时人气（热度）。实测有个别条目是 0 —— 卡片那边 0 就不显示人气块，正好 */
    val online: Long? = null,
    /** 主播自传封面 */
    val cover: String? = null,
    /** 系统关键帧（没封面时的兜底，[LiveRoomItem.coverUrl] 已经在做这件事） */
    val system_cover: String? = null,
    /** 主播头像（当前卡片不用，收着 —— 与 [LiveRoomItem.face] 同一个理由） */
    val face: String? = null,
    val area_id: Long? = null,
    val area_name: String? = null,
    val parent_area_id: Long? = null,
    val parent_area_name: String? = null,
    /** 多数条目不给；给了就用，没给按"在播"兜底 */
    val live_status: Int? = null,
)

/**
 * 推荐流卡片 → 首页卡片统一吃的 [LiveRoomItem]。
 *
 * ★为什么要有这一层映射（而不是让 UI 同时认两种实体）：
 *   首页网格、屏蔽规则、点卡片进播放页、点 UP 名进空间这一整套全是按 [LiveRoomItem] 写的；
 *   在这里把 `id → roomid`、`parent_area_id → parent_id` 对齐掉，**UI 一行都不用为推荐流特判**，
 *   卡片也就不用复制第二份（用户明确要求："别再复制第四份"）。
 *
 * ★数值字段一律 `?: 0`、字符串 `?: ""`：与 [LiveRoomItem] 的默认值语义一致，
 *   界面侧"拿不到就不显示"的判断（如 `online > 0`、`area_name.isNotBlank()`）照常成立。
 */
fun LiveRecommendRoom.toRoomItem(): LiveRoomItem = LiveRoomItem(
    roomid = id ?: 0,
    uid = uid ?: 0,
    title = title.orEmpty(),
    uname = uname.orEmpty(),
    online = online ?: 0,
    cover = cover.orEmpty(),
    system_cover = system_cover.orEmpty(),
    face = face.orEmpty(),
    area_id = area_id ?: 0,
    area_name = area_name.orEmpty(),
    parent_id = parent_area_id ?: 0,
    parent_name = parent_area_name.orEmpty(),
    // 推荐流只推在播的房间；字段真给了就按它显示（轮播/未开播会自己亮灰角标）
    live_status = live_status ?: LiveStatus.LIVE,
)
