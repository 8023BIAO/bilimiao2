package com.a10miaomiao.bilimiao.comm.live

import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp

/**
 * B 站**直播搜索**接口（第三阶段 B 路，纯新增文件，不改任何既有文件）。
 *
 * ## 为什么另起一个文件，而不是往 [LiveAPI] 里追加方法
 * `LiveAPI.kt` 是 A 路（直播浏览页）和我都可能要动的地方，同一个文件两个人改必撞车。
 * 这里独立成文件：A 路改 `LiveAPI.kt`、我加文件，零冲突（页面也只 import 本文件）。
 *
 * ## 接口出处（照 PiliPlus 最新版抄）
 * - 常量：PiliPlus `lib/http/api.dart:804`
 *   ```
 *   static const String liveSearch =
 *       '${HttpString.liveBaseUrl}/xlive/app-interface/v2/search_live';
 *   ```
 * - 调用：PiliPlus `lib/http/live.dart:455 liveSearch()`，参数 `page` / `pagesize` /
 *   `keyword` / `type`（`type` 取 `room`=直播间、`user`=主播），请求前过 `AppSign.appSign(params)`
 *   （即 appkey + ts + sign 的 APP 签名，`lib/utils/app_sign.dart`）。
 * - 页面：PiliPlus `lib/pages/live_search/` 整个目录（房间卡片 `widgets/live_search_room.dart`）。
 *
 * ## ★实测结论（2026-09-26，本容器 curl，原始响应见交付报告）
 * | 请求 | 结果 |
 * | --- | --- |
 * | 裸请求（只有 keyword/type/page/pagesize） | `{"code":-400,"message":"请求错误"}` |
 * | appkey+ts，**不带 sign** | `{"code":-3,"message":"签名错误"}` |
 * | appkey+ts+sign（APP 签名，HD appkey） | `code=0`，`data.room.list` 30 条 / `total_room=1000` / `total_page=34` |
 *
 * 即：**这个接口要的是 APP 签名（appkey+ts+sign），不是 WBI（wts+w_rid）**。
 * 所以这里不碰 [com.a10miaomiao.bilimiao.comm.utils.WbiSigner]，直接用工程既有的
 * [ApiHelper.createParams]（内部就是 appkey/ts/sign 那一套，默认 HD appkey
 * `dfca71928277209b`，与 PiliPlus 用的是同一个，也和本 App 登录用的 appkey 一致）。
 *
 * ★顺带把 [LiveAPI] 类注释里"直播接口都是 Web 接口、只要 Cookie+WBI"的范围说清楚：
 *   那条对 `room/v1/Room/room_init`、`xlive/web-room/` 那批（getRoomPlayInfo、getDanmuInfo）成立；
 *   而 `xlive/app-interface/` 那批是 **APP 通道**接口，要的是 appkey+sign。
 *   MiaoHttp 的自动 WBI 判据是 `"api.bilibili.com" in url`，对 `api.live.bilibili.com`
 *   本来就不生效 —— 这里也不指望它，签名直接算进 query。
 *
 * ## 为什么不走 `BiliApiService.biliApi`（Retrofit/Kodein 那套）
 * 那套的 baseUrl 是 `app.bilibili.com`，直播域名对不上；这里沿用 [LiveAPI] 的"手拼 query"写法。
 * 另外 [ApiHelper.createParams] 会自动补 access_key/mid（登录时）与 statistics/build 等
 * APP 参数 —— 实测这些多出来的参数不影响本接口（带假 access_key 也 code=0）。
 */
class LiveSearchAPI {

    /**
     * 搜索直播间。
     *
     * @param keyword  关键字。中文不用预处理，交给 [ApiHelper.urlencode] 做 UTF-8 百分号编码
     *                 （★注意：签名与 URL 必须用**同一个**编码函数，见 [buildSearchUrl]）
     * @param page     页码，**从 1 开始**。实测 page=2 与 page=1 内容不同、都是 30 条；
     *                 超出范围（如 total_page=34 时传 page=40）返回 `code=0` + 空 list ——
     *                 所以"到底了"要靠空列表/短页判断，不能靠 code
     * @param pageSize 每页条数。实测 20 / 30 都生效（PiliPlus 用 30，这里跟它）
     */
    fun searchRoom(keyword: String, page: Int, pageSize: Int = PAGE_SIZE) = MiaoHttp.request {
        // ★这里**故意不设 isWebApi**：本接口在 app-interface 通道下，配套的就是 appkey/sign
        //   这一整套 APP 参数；MiaoHttp 在非 web 模式补的 env/app-key/x-bili-mid/Authorization
        //   与它们是同一条通道。（实测带不带这几个头都 code=0，所以这些头不是"必须"，
        //   而是"语义一致"—— LiveAPI 里那几个 web 接口才需要 isWebApi = true。）
        url = buildSearchUrl(keyword, page, pageSize)
    }

    /**
     * 手拼 URL。
     *
     * ★要害：**签名串必须与真正发出去的 query 逐字节一致**，否则服务端算出来的 sign 与
     *   我们给的对不上（实测就表现为 `-3 签名错误`）。所以签名（[ApiHelper.createParams] 内部
     *   用 `urlencode(params, isSort = true)`）和这里拼 URL 用的是同一个函数、同一个排序开关。
     *   `sign` 自己也在 params 里，但它排在排序后的位置、服务端校验前会先剔掉它，
     *   剔掉之后的参数顺序与签名时完全一致 —— 不会因为"sign 参与了排序"而错位。
     */
    private fun buildSearchUrl(keyword: String, page: Int, pageSize: Int): String {
        val params = ApiHelper.createParams(
            "keyword" to keyword,
            "page" to page.toString(),
            "pagesize" to pageSize.toString(),
            // ★type 必须显式给：实测缺了它直接 -400（服务端要按类型选索引）
            "type" to TYPE_ROOM,
            // 0 = 接受推荐补位：结果不足时服务端会掺一些"猜你喜欢"的直播间（PiliPlus 同款）
            "disable_rcmd" to "0",
        )
        val query = ApiHelper.urlencode(params, isSort = true)
        return "$BASE_URL?$query"
    }

    companion object {
        /** 直播间搜索（PiliPlus `lib/http/api.dart:804` 的 liveSearch） */
        private const val BASE_URL =
            "https://api.live.bilibili.com/xlive/app-interface/v2/search_live"

        /** 搜直播间（本次页面用的就是它） */
        const val TYPE_ROOM = "room"

        /** 搜主播 —— 本次没做"主播"tab，常量留着，以后加 tab 时直接用 */
        const val TYPE_USER = "user"

        /**
         * 每页条数。实测 30 条/页、`total_page` 配套（34 页 = 1000 条），与 PiliPlus 一致。
         * 写 30 还有一层原因：这是**分页**接口，页数太多会被风控盯上，没必要一次要 50。
         */
        const val PAGE_SIZE = 30
    }
}
