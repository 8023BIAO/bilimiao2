package com.a10miaomiao.bilimiao.comm.live

import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import java.net.URLEncoder

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
 * ## ★★实测结论之二（2026-09-29）：关键字带**半角空格** → 服务端 `-3 签名错误`，编成 `%20` 才对
 * 用户实测反馈："它这个好像不能用空格，一用空格它就出现那个签名错误。"
 * 用本容器 curl 逐字节复刻 App 的这套签名（[ApiHelper.createParams] + `urlencode(sort=true)` +
 * `getSign`），**同一个 ts、同一套参数、只换 keyword 的编码方式**（探测脚本可重跑，原始输出
 * 见交付报告）：
 *
 * | keyword | keyword 编码 | 服务端 |
 * | --- | --- | --- |
 * | `8e6`（无空格） | — | `code=0` |
 * | `8e6 新机` | `+`（Java `URLEncoder` 对空格的默认形态 = 改动前的 App） | `code=-3 签名错误` |
 * | `8e6 新机` | `%20` | `code=0` |
 * | `英雄联盟 比赛` | `+` / `%20` | `-3` / `code=0`（30 条） |
 * | `原神 攻略 最新` | `+` / `%20` | `-3` / `code=0`（30 条） |
 * | `' 8e6'` / `'8e6 '` / `'8e6  新机'`（首/尾/双空格） | `+` | 全部 `-3` |
 * | `8e6` + 全角空格 + `新机` | — | `code=0`（不受影响） |
 *
 * 同一次排查里还实测出**同一根因**的另两个字符（`URLEncoder` 与服务端的规范化不一致）：
 *
 * | keyword | `URLEncoder` 给的 | 服务端要的 | 实际结果 |
 * | --- | --- | --- | --- |
 * | `a*b` | `a*b`（`*` 原样放行） | `a%2Ab` | `-3` / `code=0` |
 * | `a~b` | `a%7Eb` | `a~b` | `-3` / `code=0` |
 * | `C++` | `C%2B%2B` | 同左 | `code=0`（本来就没问题） |
 *
 * 所以 [encodeKeyword] 把这三个字符一次拉齐（详见它的注释，附逐条实测）。
 *
 * **不是签名算法错**：`java.net.URLEncoder` 的空格/`*`/`~` 与服务端那套量化编码不一致，
 * 而这个通道是按"收到的 query 原文"复算签名的 —— 我们签进去的那串与服务端读到/算出的那串
 * 不再逐字节相同，于是判定签名不符。所以修法只能在**本文件的调用点**：把关键字编成服务端
 * 那一套形态，并保证**签名串与真正发出去的 query 是同一个字符串**（推导见 [buildSearchUrl]）。
 *
 * ★[ApiHelper.urlencode] 是全局共用的（评论 / 发弹幕 / 私信 / 动态都在用），Java 的 `+`
 *   语义对那条通道是**既有的正确行为** —— 所以它一个字节都没动，改动全部收在本文件。
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
     * @param keyword  关键字。半角空格 / `*` / `~` 会在 [buildSearchUrl] 里编成服务端认的形态
     *                 （★实测：这三处不拉齐会回 `-3 签名错误`，见文件顶部两张表）；
     *                 其余字符仍交给 `java.net.URLEncoder` 做 UTF-8 百分号编码
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
     * ★要害：**签名串必须与真正发出去的 query 逐字节一致**，否则服务端算出来的 sign 与我们
     *   给的对不上（实测就表现为 `-3 签名错误`）。
     *
     * 改动前两边天然一致：签名在 [ApiHelper.createParams] 内部按 `urlencode(params, isSort = true)`
     * 算，拼 URL 又用同一个函数。现在 keyword 要走 `%20`，这条路走不通了 ——
     * `URLEncoder` **永远编不出 `%20`**（`%` 只会被编成 `%25`，所以"先把空格换成 `%20` 再交给
     * urlencode"会得到 `%2520`，服务端解出来是字面 "%20"，反而更坏）。
     * 于是这里改成**按最终要发出去的那串 query 重新算签名**（分 4 步，sign 最后单独插回）：
     *
     * 1. 参数集合照旧由 [ApiHelper.createParams] 生成（appkey/ts/statistics/build/access_key/mid
     *    一个不少，只是它内部按 `+` 算出来的那个 sign 作废）；
     * 2. 除 `keyword` / `sign` 外的每一段，整体交给全局共用的 [ApiHelper.urlencode]（`isSort = true`），
     *    与改动前逐字节相同 —— `urlencode` 的输出里不含 `&`（值里的 `&` 会被编成 `%26`），
     *    所以摘掉 keyword 那一段、其余 `split("&")` 出来的段就是原来的段；
     * 3. keyword 自己拼一段：[encodeKeyword] 用**同一个** `java.net.URLEncoder`，只把与服务端
     *    规范化不一致的三个字符（半角空格 / `*` / `~`）拉齐；
     * 4. `sign = MD5(排序后的 query + secret)` —— 与 `createParams` 内部调的
     *    `ApiHelper.getSign(params, secret)` 是**同一个定义**（见 [ApiHelper.getSign]），
     *    差别只在入参字符串：这里喂进去的就是最后要发出去的那串 query 本身。
     *
     * 第 4 步那句就是"逐字节一致"的全部理由：**签名字符串与 URL query 是同一个变量**，
     * 不存在"两处各编一遍、编法可能不同"的缝隙；sign 只在最后按排序位插回去（服务端校验前
     * 会先剔掉 sign 再排序），它不参与上面的签名串。
     *
     * ★不含那三个字符的关键字（绝大多数请求）**新旧 URL 逐字节完全相同**：keyword 那一段
     *   `encodeKeyword` 与 `URLEncoder.encode` 结果一样，其余段来自同一个 `urlencode`，
     *   排序规则 / secret / MD5 全没变，所以这次改动不会影响既有能用的搜索。
     */
    private fun buildSearchUrl(keyword: String, page: Int, pageSize: Int): String {
        // ① 参数集合照旧由 createParams 生成（appkey/ts/statistics/build/access_key/mid 一个不少）
        val params = ApiHelper.createParams(
            "keyword" to keyword,
            "page" to page.toString(),
            "pagesize" to pageSize.toString(),
            // ★type 必须显式给：实测缺了它直接 -400（服务端要按类型选索引）
            "type" to TYPE_ROOM,
            // 0 = 接受推荐补位：结果不足时服务端会掺一些"猜你喜欢"的直播间（PiliPlus 同款）
            "disable_rcmd" to "0",
        )
        // ② 除 keyword / sign 外的参数：照旧整体交给全局共用的 urlencode
        val otherItems = ApiHelper.urlencode(
            params.filterKeys { it != "keyword" && it != "sign" },
            isSort = true,
        ).split("&").filter { it.isNotEmpty() }
        // ③ keyword 那一段自己拼；空关键字照旧不参与（与 urlencode 里 !value.isNullOrBlank() 一致）
        val keywordItem = keyword
            .takeUnless { it.isBlank() }
            ?.let { "keyword=${encodeKeyword(it)}" }
        val items = (otherItems + listOfNotNull(keywordItem)).sorted()
        val signedQuery = items.joinToString("&")
        // ④ 签名按"最终要发出去的 query"算，而不是按任何中间形态算
        val sign = ApiHelper.getSign("$BASE_URL?$signedQuery", ApiHelper.APP_SECRET_HD)
        // ⑤ sign 照旧按排序位插回（与改动前的 URL 形状一致）
        val query = (items + "sign=$sign").sorted().joinToString("&")
        return "$BASE_URL?$query"
    }

    /**
     * 关键字编码：**只把 `URLEncoder` 与服务端规范化不一致的那三个字符拉齐**，其余字符与全局共用的
     * `java.net.URLEncoder.encode(s, "UTF-8")`（[ApiHelper.urlencode] 内部用的就是它）完全一样。
     *
     * 三个字符的实测依据（同一 ts / 同一套参数，只换编码，原始输出见交付报告）：
     *
     * | 字符 | `URLEncoder` 给的 | 服务端的规范化 | 直接发 `URLEncoder` 的结果 | 拉齐后 |
     * | --- | --- | --- | --- | --- |
     * | 半角空格 | `+` | `%20` | `-3 签名错误` | `code=0` |
     * | `*` | 原样 `*` | `%2A` | `-3 签名错误` | `code=0` |
     * | `~` | `%7E` | 原样 `~` | `-3 签名错误` | `code=0` |
     * | `+` / 中文 / 其它 | `%2B` / `%XX` | 同左 | `code=0` | `code=0`（没动） |
     *
     * 为什么修在同一处：三者是**同一个根因** —— 服务端按"解码后再用它自己那套量化编码"复算签名，
     * 只要客户端发出去的字面量与它算出的一致就通过。空格是用户这次踩到的那个，`*` / `~` 是
     * 同一次排查里顺带实测出来的同类问题（都是 `-3`），代价只有两行、且**只影响本来就会 `-3`
     * 的输入**（不含这三类字符的关键字输出与改动前逐字节相同）。
     *
     * ★为什么可以 `replace`：`URLEncoder` 会把**字面加号**编成 `%2B`，所以 encode 结果里的 `+`
     *   只可能来自半角空格；`*` 只会来自用户输入的 `*`；`%7E` 只会来自 `~`
     *   （UTF-8 多字节序列里不可能出现 `%7E`，用户自己输入的 "%7E" 会被编成 `%257E`，不匹配）。
     * ★为什么不写成 `keyword.replace(" ", "%20")` 再交给 urlencode：见 [buildSearchUrl] 开头，
     *   `%` 会被二次编码成 `%25`（`8e6 新机` → `8e6%2520%E6%96%B0%E6%9C%BA`）。
     */
    private fun encodeKeyword(keyword: String): String =
        URLEncoder.encode(keyword, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

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
