package com.a10miaomiao.bilimiao.comm.live

import com.a10miaomiao.bilimiao.comm.live.entity.LiveDanmuInfo
import com.a10miaomiao.bilimiao.comm.live.entity.LiveH5Info
import com.a10miaomiao.bilimiao.comm.live.entity.LivePlayUrlInfo
import com.a10miaomiao.bilimiao.comm.live.entity.LiveRoomDetail
import com.a10miaomiao.bilimiao.comm.live.entity.LiveRoomInfoByRoom
import com.a10miaomiao.bilimiao.comm.live.entity.LiveRoomInitInfo
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.utils.WbiSigner
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * `new_switch_info` 里 value 是 JSON 数字时取 Int，其余（字符串/布尔/null/对象）一律 null。
 *
 * ★为什么不直接 `Map<String, Int>`：那个 map 的 value 一旦出现非数字，**整个响应**（含
 *   [LiveRoomInfoByRoom.switch_info]）都会解析失败 —— 单点脏值不能连累整条判据。
 */
private fun JsonElement?.intFlagOrNull(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()

/**
 * B 站**直播**接口封装（第一阶段骨架，纯新增，未接入任何现有页面）。
 *
 * 与 `comm/apis/LiveApi.kt` 的关系：那个文件只有一句 `info(roomId)`，且属"现有文件"
 * （第一阶段不许改）。这里另起 `comm.live` 包放完整的直播接口层。
 * ★顺带避开一个坑：`LiveApi.kt` 与 `LiveAPI.kt` 在 **Windows 大小写不敏感文件系统上
 *   是同一个文件名**，同目录共存会有覆盖风险 —— 分属两个包最干净。
 *
 * 调用方式沿用工程既有风格：
 * ```
 * LiveAPI().playUrl(roomId).call().json<ResponseData<LivePlayUrlInfo>>()
 * ```
 *
 * ★为什么每个接口都要 `isWebApi = true`：
 *   MiaoHttp 在非 web 模式下会塞 `env: prod` / `app-key: android_hd` /
 *   `x-bili-mid` / `Authorization` 这一整套 **APP 通道头部**（MiaoHttp.kt:70-79）。
 *   直播这些接口是 **Web 接口**，只认 Cookie + WBI；带上 APP 身份头是另一条通道，
 *   轻则行为不一致、重则风控。所以一律显式声明走 Web。
 *
 * ★为什么不用 `BiliApiService.createUrl` / `biliApi`：
 *   它们内部走 `ApiHelper.createParams`，会自动注入
 *   `appkey/platform/mobi_app/statistics/access_key/mid/sign` 一整套 **APP 查询参数**
 *   （ApiHelper.kt:144-167）—— 同样会把请求打成 APP 通道。
 *   工程里 `biliMessageApi` 就是为绕开这个才手拼 query 的，这里照同一思路办。
 */
class LiveAPI {

    /**
     * 房间短号 → 真实房间号 + 开播状态。
     *
     * ★实测：**无需登录/ Cookie / UA / Referer**，裸请求即 code=0。
     * 用户可以输入短号（如 1），真实房间号在 `data.room_id`（如 5440）；
     * 后续所有接口都要用**真实房间号**，不是短号。
     */
    fun roomInit(roomId: String) = MiaoHttp.request {
        isWebApi = true
        url = liveUrl("room/v1/Room/room_init", "id" to roomId)
    }

    /**
     * 房间详情：标题 / 人气 / 分区 / 封面。
     *
     * ★实测无需登录。注意本接口**不返回主播名和头像**（uname/face 恒空），
     * 要主播信息得走 [h5Info] 或 user 接口。
     */
    fun roomInfo(roomId: String) = MiaoHttp.request {
        isWebApi = true
        url = liveUrl("room/v1/Room/get_info", "room_id" to roomId)
    }

    /**
     * 移动端房间详情：**字段最全**（封面 cover / 开播时间 live_start_time / 分区）。
     *
     * ★选它做**开播轮询**：字段比 get_info 全，且同样是免登录接口。
     * 轮询间隔建议 ≥30s —— B 站风控会拦高频请求（实测连"分区直播列表"都会回 -352）。
     */
    fun h5Info(roomId: String) = MiaoHttp.request {
        isWebApi = true
        url = liveUrl("xlive/web-room/v1/index/getH5InfoByRoom", "room_id" to roomId)
    }

    /**
     * 直播**分区树**（顶级分区 + 子分区），浏览页的顶部标签条就靠它。
     *
     * ★实测（2026-02）：`code=0`，**12 个顶级分区 / 450 个子分区**，每个子分区都带图标 `pic`。
     *   - 不需要登录态、不需要 WBI 签名；
     *   - **但完全裸的请求（无 UA/Referer）实测回 `HTTP 412`**（B 站风控）——
     *     所以这条路必须走 [MiaoHttp]（它会自动补 UA/Referer/buvid），不能用裸 OkHttp。
     *
     * 返回壳是 `ResultInfo<List<LiveAreaGroup>>`（`{"code":0,"data":[...]}`）。
     */
    fun areaList() = MiaoHttp.request {
        isWebApi = true
        url = liveUrl("room/v1/Area/getList")
    }

    /**
     * 按分区取**正在直播的房间列表**（分页）。
     *
     * ★实测（2026-02）参数与行为：
     *   - `parent_area_id` + `area_id`：两个都要传。`area_id=0` 表示"这个顶级分区下的全部"；
     *     **两个都传 0 = 全站热门**（实测 code=0 且给 30 条，这就是浏览页「全部」标签的数据来源）；
     *     顶级分区对但子分区 id 不存在 → `code=404`（实测 parent=0&area=1），所以 area_id 必须来自 [areaList]。
     *   - `page`：真的分页。实测 page=1/2/3 各 30 条、**互不重复**（房间号集合零交集）。
     *   - `page_size`：实测 20→20 条、30→30 条、50→50 条、**100→只给 20 条**（超限反而缩水），
     *     所以调用方固定用 30，别贪大。
     *   - `sort_type`：**必传**。实测 `online`(按人气) / `live_time`(按开播时间) 都正常，
     *     **传空串会返回 0 条**（不是报错，是静默空列表 —— 很容易被误判成"这个分区没内容"）。
     *   - 翻到超出末页：`code=0` + `data=[]`（实测 page=999），所以"到底了"判空数组即可。
     *   - 接口**不返回 `live_status`**（见 [LiveRoomItem] 的注释）。
     *
     * 返回壳是 `ResultInfo<List<LiveRoomItem>>`。
     *
     * @param parentAreaId 顶级分区 id（0 = 不限）
     * @param areaId 子分区 id（0 = 该顶级分区全部）
     * @param page 从 1 开始
     * @param pageSize 实测安全上限 50，默认 30
     * @param sortType [SORT_ONLINE] 或 [SORT_LIVE_TIME]，**不能为空**
     */
    fun areaRoomList(
        parentAreaId: Int,
        areaId: Int,
        page: Int,
        pageSize: Int = AREA_ROOM_PAGE_SIZE,
        sortType: String = SORT_ONLINE,
    ) = MiaoHttp.request {
        isWebApi = true
        url = liveUrl(
            "room/v1/Area/getRoomList",
            "parent_area_id" to parentAreaId.toString(),
            "area_id" to areaId.toString(),
            "page" to page.toString(),
            "page_size" to pageSize.toString(),
            "sort_type" to sortType,
        )
    }

    /**
     * 直播播放地址（多协议 / 多封装 / 多编码 / 多 CDN 线路）。
     *
     * ★实测：**完全不需要鉴权**——无 Cookie、无 UA、无 Referer 也返回 code=0，
     *   也不需要 WBI 签名。
     *
     * ★为什么 protocol/format/codec 都传"多选"而不是只传一个：
     *   不同房间可用的组合会动态变化（有的房间只给 http_hls/fmp4），
     *   一次把可用集合都要回来，**在客户端排序挑选**，比按固定假设请求更耐操
     *   （同样的做法见 blbl core/api/LiveApi.kt:251-256）。
     *   - protocol: 0=http_stream(FLV), 1=http_hls
     *   - format:   0=flv, 1=ts, 2=fmp4
     *   - codec:    0=avc, 1=hevc
     *
     * ★[qn] 传的是"期望值"，**不是**"保证值"：实测未登录时无论 qn 传多少，
     *   返回的 `current_qn` 都是 250（超清）。要原画(10000)/蓝光(400) 必须有登录态。
     *
     * @param qn 期望清晰度，默认 10000(原画)。取值表：
     *           30000 杜比 / 20000 4K / 15000 2K / 10000 原画 /
     *           400 蓝光 / 250 超清 / 150 高清 / 80 流畅
     */
    fun playUrl(roomId: String, qn: Int = QUALITY_ORIGIN) = MiaoHttp.request {
        isWebApi = true
        url = liveUrl(
            "xlive/web-room/v2/index/getRoomPlayInfo",
            "room_id" to roomId,
            "protocol" to "0,1",
            "format" to "0,1,2",
            "codec" to "0,1,2", // 0=avc 1=hevc 2=av1：实测补上 av1 后线路从 12 条变 14 条（与 PiliPlus 一致）,
            "qn" to qn.toString(),
            // 下面几个是 Web 端固定参数：platform/ptype 定位到 web 播放器通道，
            // dolby/panorama 表示"也接受杜比/全景声资源"（没有就自然不下发）
            "platform" to "web",
            "ptype" to "8",
            "dolby" to "5",
            "panorama" to "1",
            "web_location" to "444.8",
        )
    }

    /**
     * 弹幕服务器信息（token + host_list）。
     *
     * ★★这是整个直播链路里**唯一必须 WBI 签名**的接口**：
     *   实测不签名一律 `{"code":-352,"message":"-352"}`（风控），
     *   加 buvid3 / Referer / Origin / 换参数名统统无效；
     *   补上 `wts` + `w_rid` 后立刻 code=0（实测拿到 token + 6 个 host）。
     *
     * ★为什么这里手动签名，而不是指望 MiaoHttp 自动签：
     *   MiaoHttp 的自动 WBI 判据是 `"api.bilibili.com" in url`（MiaoHttp.kt:80-85），
     *   而 `api.live.bilibili.com` **并不包含** `api.bilibili.com` 这个子串
     *   （它是 `api.` + `live.bilibili.com`）→ 自动签名**永远不会生效**。
     *   所以必须显式 `WbiSigner.signUrlBlocking`。
     *
     * 另外带上 live 域名自己的 Referer/Origin：Web 端接口按同源请求校验，
     * 参考实现 blbl 也是这么带的（core/api/LiveApi.kt:432-435）。
     */
    fun danmuInfo(roomId: String) = MiaoHttp.request {
        isWebApi = true
        headers["Referer"] = "https://live.bilibili.com/"
        headers["Origin"] = "https://live.bilibili.com"
        val raw = liveUrl(
            "xlive/web-room/v1/index/getDanmuInfo",
            // ★参数名是 id（不是 room_id），且必须是**真实房间号**
            "id" to roomId,
            "type" to "0",
            "web_location" to "444.8",
        )
        // signUrlBlocking 是阻塞式（内部已缓存 mixin_key，不每次都联网）。
        // ★显式传 WbiScope.LIVE = "这里要签名"的声明。默认值（不传 scope）只认直播白名单，
        //   换任何一个非直播 URL 到这一行都不会被签 —— 见 WbiSigner.WbiScope 的说明。
        var signedUrl = WbiSigner.signUrlBlocking(raw, WbiSigner.WbiScope.LIVE)
        // ★为什么要"没签上就清缓存重签一次"（真机 -352 的教训）：
        //   [WbiSigner.signUrlBlocking] 在 mixKey 取不到时是**静默**原样返回输入 URL 的，
        //   而服务端对未签名请求一律回 `-352`（风控）。调用方只看到"业务拒绝 -352"，
        //   分不清是"签名压根没拼上"还是"签名有效但被风控"——上一版真机排查就卡在这一步。
        //   这里显式判一次：没有 w_rid 就先清掉 WBI 缓存重新联网取 keys（首次 nav 撞上瞬断的典型场景）
        //   再签一遍；重签后依然没有就交给上层报错（见 LiveDanmakuClient 的 trace 与失败原因）。
        if ("w_rid=" !in signedUrl) {
            WbiSigner.invalidateCache()
            signedUrl = WbiSigner.signUrlBlocking(raw, WbiSigner.WbiScope.LIVE)
        }
        url = signedUrl
    }

    /**
     * 直播间**完整房间信息**（官方 Web 播放器的进房第一个请求）。
     *
     * 本工程只从它里面读一件事：**这个房间关没关弹幕**（见 [roomDanmakuPolicy]、
     * [LiveRoomInfoByRoom] 的字段对照表）。其余 100+ 个字段全部忽略。
     *
     * ## 参数 / 签名（照官方 Web 端逐字对齐）
     * - `room_id`：**真实房间号**（短号先过 `roomInitResolved`）；
     * - `web_location=444.8`：官方 Web 端固定带的埋点参数；
     * - ★**要 WBI 签名**：2026-09-26 用真实 Chromium 打开直播间抓到的官方请求就是
     *   `…/getInfoByRoom?room_id=8178490&web_location=444.8&w_rid=…&wts=…` ——
     *   和 [danmuInfo] 同一套（`wts` + `w_rid`）。这里照抄，同样走调用点显式声明的
     *   [WbiSigner.WbiScope.LIVE]（**不动**自动签名白名单，见那个常量的注释）。
     */
    fun roomInfoByRoom(roomId: String) = MiaoHttp.request {
        isWebApi = true
        headers["Referer"] = "https://live.bilibili.com/"
        headers["Origin"] = "https://live.bilibili.com"
        val raw = liveUrl(
            "xlive/web-room/v1/index/getInfoByRoom",
            "room_id" to roomId,
            "web_location" to "444.8",
        )
        var signedUrl = WbiSigner.signUrlBlocking(raw, WbiSigner.WbiScope.LIVE)
        // 与 danmuInfo 同一条"没签上就清缓存重签一次"的兜底（服务的 -352 分不清是没签还是被风控）
        if ("w_rid=" !in signedUrl) {
            WbiSigner.invalidateCache()
            signedUrl = WbiSigner.signUrlBlocking(raw, WbiSigner.WbiScope.LIVE)
        }
        url = signedUrl
    }

    /**
     * **这个直播间关没关弹幕** —— 把 [roomInfoByRoom] 的两个开关块翻成一个三态结论。
     *
     * ## 判据（★只用官方 Web 播放器自己用的那两个字段，见 [LiveRoomInfoByRoom] 的实测对照表）
     * ```
     * close_danmaku == true            → 关闭（主播/官方关了弹幕）
     * room-danmaku-editor == 0         → 关闭（官方 Web 端把"弹幕输入框"整块关掉：央视新闻 8178490 实测）
     * 两者都在、且都不表示关闭          → 没关
     * 两个字段都拿不到                  → **未知**（closed = null）
     * ```
     * ★为什么 `room-danmaku-editor` 是主判据：5 个房间实测（8178490 + 4 个正常房）里
     *   `close_danmaku` **恒为 false**，唯一稳定区分开的就是 `room-danmaku-editor`（关闭房 = 0，
     *   正常房 = 1）—— 这也正是官方 Web 端"显示/隐藏弹幕输入框"用的那一个
     *   （bundle 里 `isShowDanmakuEditor: u("room-danmaku-editor")`）。
     *
     * ## 三态而不是 Boolean 的理由（★保守原则）
     * 风控（`-352`）/ 断网 / 解析失败时**必须**能表达"我不知道"：调用方据此按"没关闭"处理，
     * 绝不因为一次请求失败就把一个正常直播间的弹幕 UI 全关掉。
     *
     * ★这是个**阻塞**函数（内部是同步 OkHttp 调用）：调用点只有一处，且必须包在
     *   `withContext(Dispatchers.IO)` 里（与 [roomInitResolved] 的用法一致）。
     * ★每个房间**只调一次**（进房/换房时），不做重试、不做轮询：这个接口按 IP 有风控
     *   （容器内直连实测很快转 `-352`），重试风暴只会把正常房间也一起判成"拿不到"。
     *
     * @return [LiveRoomDanmakuPolicy]，其中 `closed` = true/false/null（null = 拿不到）
     */
    fun roomDanmakuPolicy(roomId: String): LiveRoomDanmakuPolicy {
        val resp = runCatching { roomInfoByRoom(roomId).call().json<ResponseData<LiveRoomInfoByRoom>>() }
            .onFailure {
                miaoLogger() error "[live] getInfoByRoom 异常 roomId=$roomId: ${it.javaClass.simpleName}: ${it.message}"
            }
            .getOrNull()
        if (resp == null || !resp.isSuccess) {
            miaoLogger() error "[live] getInfoByRoom 不可用 roomId=$roomId code=${resp?.code}（按'没关闭弹幕'处理）"
            return LiveRoomDanmakuPolicy(closed = null, code = resp?.code)
        }
        val data = resp.data
        val closeDanmaku = data?.switch_info?.close_danmaku
        val editor = data?.new_switch_info?.get(DANMAKU_EDITOR_FLAG)?.intFlagOrNull()
        val closed: Boolean? = when {
            closeDanmaku == true -> true
            editor == 0 -> true
            closeDanmaku == false -> false
            editor != null -> false
            else -> null
        }
        return LiveRoomDanmakuPolicy(
            closed = closed,
            closeDanmaku = closeDanmaku,
            danmakuEditor = editor,
            code = resp.code,
        )
    }

    /**
     * [roomDanmakuPolicy] 的结论（三态 + 原始字段，原始字段只用于诊断日志）。
     *
     * @param closed true = 确认关闭；false = 确认没关；**null = 拿不到**（调用方按"没关"处理）
     * @param closeDanmaku `data.switch_info.close_danmaku` 原值（拿不到为 null）
     * @param danmakuEditor `data.new_switch_info["room-danmaku-editor"]` 原值（拿不到为 null）
     * @param code 接口 code（诊断用；拿不到响应时为 null）
     */
    data class LiveRoomDanmakuPolicy(
        val closed: Boolean?,
        val closeDanmaku: Boolean? = null,
        val danmakuEditor: Int? = null,
        val code: Int? = null,
    )

    /**
     * **按 uid 批量**查"这些 UP 谁在直播、房间号是多少"。
     *
     * 这是给"头像上挂『直播中』标记"用的**唯一数据源**（关注列表 / 动态卡片那种
     * 手上只有一堆 uid、没有房间号的场景）。
     *
     * ★实测（2026-09，容器内 curl，无 Cookie / 无 WBI / 只带 UA + live Referer）：
     *   - `code=0`，返回体是 `data: { "<uid>": {...} }` 的 **Map**，key 是字符串 uid；
     *   - **一次 30 个 uid 全部返回**（实测 30/30）；60 个也能回但会缺；
     *   - **不签名也能过**，与 [danmuInfo] 那个"必签"的接口不是一回事。
     *
     * ★为什么参数键写字面量 `uids%5B%5D` 而不是 `uids[]`：
     *   B 站这个接口只认**重复键** `uids[]=1&uids[]=2` 的形式，
     *   逗号拼接（`uids=1,2`）实测直接 `code=1` 报错。
     *   而 [ApiHelper.urlencode] 只对 **value** 做 URL 编码、**不碰 key**，
     *   所以 key 里的方括号要自己先编码好 —— 写成 `uids[]` 服务端实测也收，
     *   但那要靠"OkHttp 不会重编码方括号"这个隐含前提，显式编码更稳。
     *
     * ★为什么这条**不能**复用 [liveUrl] 的 vararg 那条路：
     *   `liveUrl` 内部是 `mapOf(*pairs)` —— Map 会把**同名 key 去重**，
     *   30 个 uid 传进去只剩最后一个，等于只查了一个人。所以这里手拼 query，
     *   只借用 `liveUrl(path)` 拼出来的裸 base（那部分仍是同一个域名常量）。
     *
     * 返回壳：`ResultInfo<Map<String, com.a10miaomiao.bilimiao.comm.live.entity.LiveUserStatusInfo>>`。
     */
    fun liveStatusByUids(uids: List<String>) = MiaoHttp.request {
        isWebApi = true
        // 沿用 danmuInfo 的写法带上 live 自己的 Referer/Origin。
        // ★实测这个接口对 Referer **不敏感**：只带 MiaoHttp 默认的 www Referer、
        //   两个 Referer 叠加、甚至完全不带头，三种都回 code=0 ——
        //   所以这里是"保持一致"而不是"必须"。留着的原因是万一哪天 B 站收紧同源校验，
        //   我们这边不用再改一遍。
        headers["Referer"] = "https://live.bilibili.com/"
        headers["Origin"] = "https://live.bilibili.com"
        val query = uids
            .filter { it.isNotBlank() }
            .joinToString("&") { "uids%5B%5D=$it" }
        url = liveUrl("room/v1/Room/get_status_info_by_uids") + "?" + query
    }

    /**
     * [roomInit] 的**带降级**版本：解析房间号（短号 → 真实号）+ 开播状态 + 主播 uid。
     *
     * ★为什么必须有这个降级（2026-09-26「所有直播间都提示获取直播信息失败」的根因，别删）：
     *   `room/v1/Room/room_init` 现在会被 B 站风控**按端点**拦掉。容器内 curl 实测（同一 IP、
     *   同几秒内交错打）：
     *   - `room_init`  → **HTTP 412**；补全浏览器头后拿到 JSON `{"code":-412,"message":"request was banned"}`；
     *   - `get_info` / `getH5InfoByRoom` / `getRoomPlayInfo` → **HTTP 200 code=0**（取流接口是好的！）；
     *   - 不签名 / WBI 签名 / APP 签名（appkey+ts+sign）三种都试过 → `room_init` 一律 412，
     *     **签名不是解药**，所以这跟"WBI 收敛成白名单"那次改动无关（`api.live.bilibili.com`
     *     本来就不含 `api.bilibili.com` 子串，改动前后都不签）。
     *   结论：坏的是 `room_init` 这**一个端点**，不是网络、不是登录态、不是签名。
     *
     * 所以 `room_init` 不能当进房唯一入口。降级顺序按"字段够用 + 存活率高"排：
     *   ① [roomInit] 最快；② [h5Info] 字段最全且实测 200；③ [roomInfo] 实测 200，且**也能解析短号**
     *   （实测 `room_id=1 → 5440`、`room_id=3 → 23058`）。
     *
     * ★为什么值得多花这一次请求：降级拿到的 `live_status` / `uid` 是**后面流程真的要用的** ——
     *   没有 `live_status`，未开播的房间会被当成"能播"去取流，用户看到的就不是"房间未开播，
     *   30s 后自动重试"而是"播放失败"；没有 `uid`，底栏「UP主」按钮会退化。
     *
     * ★每一层失败都留 ERROR 日志：原来这类失败被 `resolveRoom` 的 catch 静静吞掉，
     *   用户只看到一句"请检查网络"，真机上完全分不清 412 / 超时 / 解析失败。
     *
     * @return 三层都失败才返回 null；调用方此时应用自己手上的原始房间号继续取流（取流接口是好的）
     */
    fun roomInitResolved(roomId: String): LiveRoomInitInfo? {
        // ① room_init：首选，一次请求就够
        runCatching { roomInit(roomId).call().json<ResponseData<LiveRoomInitInfo>>() }
            .onFailure {
                miaoLogger() error "[live] room_init 异常 roomId=$roomId: ${it.javaClass.simpleName}: ${it.message}"
            }
            .getOrNull()
            ?.takeIf { it.isSuccess }
            ?.data
            ?.let { return it }

        // ② getH5InfoByRoom：字段最全（含主播名/头像），同样给 room_id + live_status + uid
        runCatching { h5Info(roomId).call().json<ResponseData<LiveH5Info>>() }
            .getOrNull()
            ?.takeIf { it.isSuccess }
            ?.data?.room_info
            ?.takeIf { it.room_id > 0 }
            ?.let {
                miaoLogger() error "[live] room_init 不可用，已降级 getH5InfoByRoom 解析房间 roomId=$roomId → ${it.room_id}"
                return LiveRoomInitInfo(
                    room_id = it.room_id,
                    uid = it.uid,
                    live_status = it.live_status,
                )
            }

        // ③ get_info：实测对短号也能解析（1→5440 / 3→23058），最后一层兜底
        runCatching { roomInfo(roomId).call().json<ResponseData<LiveRoomDetail>>() }
            .getOrNull()
            ?.takeIf { it.isSuccess }
            ?.data
            ?.takeIf { it.room_id > 0 }
            ?.let {
                miaoLogger() error "[live] room_init/getH5InfoByRoom 均不可用，已降级 get_info 解析房间 roomId=$roomId → ${it.room_id}"
                return LiveRoomInitInfo(
                    room_id = it.room_id,
                    short_id = it.short_id,
                    uid = it.uid,
                    live_status = it.live_status,
                )
            }

        miaoLogger() error "[live] 房间解析全部失败 roomId=$roomId（room_init / getH5InfoByRoom / get_info 都不可用）"
        return null
    }

    /**
     * 首页直播「推荐」Tab：B 站**个性化推荐流**（`xlive/app-interface/v2/index/feed`，纯新增方法）。
     *
     * ## 为什么这个 Tab 用这条流、而不是再挑一个分区
     * 这条流是**服务端按画像 + 实时热度分流**下发的（客户端不参与、也没法参与"推荐哪些分区"），
     * 正好对上用户的要求："这个推荐应该是我们定义不了哪些分区……系统的 API 分流推荐给我们，
     * 我们用它的就行"。选「推荐」= 完全不传分区/排序，与「全部」（`parent_area_id=0&area_id=0`
     * 按人气排的**榜单**）是两种东西 —— 所以「推荐」在 UI 上是一个**独立选项**，不是某个分区。
     *
     * ## 出处（照 PiliPlus 抄，行号已核对）
     * - URL：PiliPlus `lib/http/api.dart:781-782`（`liveFeedIndex`）；
     * - 调用：PiliPlus `lib/http/live.dart:193-257 liveFeedIndex({required int pn})`，参数 `page`；
     * - Tab 摆放：PiliPlus `lib/pages/live/view.dart:105-128`（标签条第一个格子恒为「推荐」，
     *   点它走 `onSelectArea(index, null)`）→ `lib/pages/live/controller.dart:78-88`
     *   （`areaIndex == 0` → `liveFeedIndex`，否则 `liveSecondList`）。
     *
     * ## ★为什么这里**不设** `isWebApi = true`（本类是这条通则的例外，与 LiveSearchAPI 一致）
     * 本类别的接口（`room/v1/…`、`xlive/web-room/…`）都是 Web 接口，所以清一色 `isWebApi = true`；
     * 而 `xlive/app-interface/…` 是 **APP 通道**接口，要的是 `appkey + ts + sign` 这套 APP 签名
     * （实测**不签名直接 `{"code":-3,"message":"签名错误"}`**）。所以这里跟 `LiveSearchAPI` 走同一条路：
     * 不设 `isWebApi`（APP 头部语义一致），签名算进 query（`ApiHelper.createParams` 内部就是
     * appkey/ts/sign，默认 HD appkey，与本 App 登录用的是同一个）。
     *
     * ★签名不会被 MiaoHttp 的自动 WBI 覆盖：`MiaoHttp.buildRequest` 里
     *   `hasSign = "sign=" in url` 为真就直接跳过自动签名（MiaoHttp.kt:82-85），
     *   拼好的 APP 签名能原样发出去。
     *
     * ## ★实测（2026-09-26，容器 curl，详见 `/root/test/直播优化-推荐Tab-说明.md`）
     * - `code=0`，一页 **20 条 `small_card_v1`**（另可能有 `banner_v2`/`area_entrance_v3` 等卡片，
     *   实体层只挑 `small_card_v1`）；
     * - **分页真的可用**：`page=1/2/3/4/60` 每页 20 条、互不重复，`has_more` 恒为 1；
     * - **`page_size` 传了没用**（20/30/50 都只回 20 条）→ 固定用 `RECOMMEND_PAGE_SIZE`；
     * - 未登录也 `code=0`（`trackid` 形如 `rec-fallback-live-…` = 兜底推荐）；带上失效的
     *   access_key/mid 同样 `code=0`（登录态过期不会把推荐打挂）。
     *
     * 返回壳：`ResultInfo<com.a10miaomiao.bilimiao.comm.live.entity.LiveRecommendFeed>`。
     *
     * @param page 页码，**从 1 开始**。传超大页码（实测 60）依然回 20 条 —— 推荐流是"无限流"，
     *             `has_more == 0` 才算到底；页面侧的"到底了"还额外看"这一页条数不足一页"
     *             （见 HomeLiveContent.kt 的 LiveRoomListViewModel）
     */
    fun recommendFeed(page: Int) = MiaoHttp.request {
        url = buildRecommendUrl(page)
    }

    /**
     * 手拼推荐流的 URL。
     *
     * ★要害与 `LiveSearchAPI` 完全相同：**签名串必须与真正发出去的 query 逐字节一致**，
     *   否则服务端回 `-3 签名错误`。所以签名（`ApiHelper.createParams` 内部用
     *   `urlencode(params, isSort = true)`）和这里拼 URL 用的是同一个函数、同一个排序开关。
     *
     * 参数分两拨：
     *   - 工程既有的一套由 `ApiHelper.createParams` 自动补齐（appkey/platform/channel/mobi_app/
     *     statistics/build/c_locale/s_locale/ts，登录时再补 access_key/mid）——
     *     实测这套参数（channel=bili、build=1450000）打这个接口 `code=0`，不必照抄 PiliPlus 的
     *     `channel=master/build=8430300`；
     *   - 下面显式列的这几个是照 PiliPlus `lib/http/live.dart:193-215` 抄的**业务参数**。
     *     `page` 是唯一真正决定内容的那个，其余是"告诉服务端我这是个正常 App 请求"的上下文
     *     （实测去掉也能通，留着是为了行为与 PiliPlus 一致，少一层未知）。
     */
    private fun buildRecommendUrl(page: Int): String {
        val params = ApiHelper.createParams(
            "page" to page.toString(),
            "actionKey" to "appkey",
            "device" to "android",
            "device_name" to "android",
            "device_type" to RECOMMEND_DEVICE_TYPE,
            "fnval" to RECOMMEND_FNVAL,
            // 0 = 接受推荐补位（PiliPlus 同款）。这条流本身就是推荐，关掉它反而可能变空
            "disable_rcmd" to "0",
            "https_url_req" to "1",
            "network" to "wifi",
            "scale" to "2",
        )
        val query = ApiHelper.urlencode(params, isSort = true)
        return "$RECOMMEND_URL?$query"
    }

    private fun liveUrl(path: String, vararg pairs: Pair<String, String?>): String {
        // 手拼 query：ApiHelper.urlencode 会丢掉值为空的参数，正好符合这些接口的脾气
        val query = ApiHelper.urlencode(mapOf(*pairs))
        val base = "https://api.live.bilibili.com/$path"
        return if (query.isBlank()) base else "$base?$query"
    }

    companion object {
        /**
         * `new_switch_info` 里"弹幕输入框"那一项的 key（官方 Web 端 `isShowDanmakuEditor` 用的就是它）。
         * 值 = 0 表示官方客户端**不显示弹幕输入框** → 本工程据此把"输入条 / 弹幕列表 / 滚动弹幕"一起收掉。
         */
        const val DANMAKU_EDITOR_FLAG = "room-danmaku-editor"

        /** 原画 */
        const val QUALITY_ORIGIN = 10000
        /** 蓝光 */
        const val QUALITY_BLURAY = 400

        /** 超清 —— ★实测未登录时能被下发的**最高**画质 */
        const val QUALITY_SUPER = 250

        /** 高清 */
        const val QUALITY_HIGH = 150

        /** 流畅 */
        const val QUALITY_SMOOTH = 80

        /** 房间列表默认页大小。★实测 page_size>50 会被服务端压回 20 条，30 是"够用且不会缩水"的值 */
        const val AREA_ROOM_PAGE_SIZE = 30

        /** 按人气排序（浏览页默认：这就是 B 站"热门直播"的排序） */
        const val SORT_ONLINE = "online"

        /** 按开播时间排序（"最新开播"） */
        const val SORT_LIVE_TIME = "live_time"

        /** 全站（不限分区）：`parent_area_id=0` + `area_id=0`，实测 code=0 有内容 */
        const val AREA_ALL = 0

        /**
         * 首页「推荐」Tab 的 URL（PiliPlus `lib/http/api.dart:781-782` 的同一条）。
         * ★注意是 `app-interface` 通道（要 APP 签名），与 `room/v1/Area/getRoomList` 不是一条路。
         */
        private const val RECOMMEND_URL =
            "https://api.live.bilibili.com/xlive/app-interface/v2/index/feed"

        /**
         * 推荐流的"一页"条数。
         *
         * ★**不是**我们选的，是接口定死的：实测 `page_size` 传 20/30/50 都只回 **20** 条
         *   （与 PiliPlus 不传 page_size 的行为一致）。写成常量只为让"一页 20 条"这个事实
         *   在页面侧的"到底了"判断（`这一页条数 < pageSize`）里有个显式出处 ——
         *   传不传给接口都行，因为传了也不生效。
         */
        const val RECOMMEND_PAGE_SIZE = 20

        /** 推荐流业务参数（照 PiliPlus `lib/http/live.dart:193-215` 抄）：设备类型 0 = 安卓 */
        private const val RECOMMEND_DEVICE_TYPE = "0"

        /** 推荐流业务参数：fnval=912 是 PiliPlus 一直在用的功能位（同 `getRoomPlayInfo` 那一套） */
        private const val RECOMMEND_FNVAL = "912"
    }
}
