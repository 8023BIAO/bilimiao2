package com.a10miaomiao.bilimiao.comm.apis

import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp

/**
 * 观看历史 Web 接口（PiliPlus `UserHttp.historyList` 同源）。
 *
 * 端点：`api.bilibili.com/x/web-interface/history/cursor`
 * 参数：`type` = all / archive / live / article；`max` + `view_at` 翻页游标。
 *
 * 为什么不用 gRPC `History/CursorV2` 的 `business=all`：
 *   - proto 注释里 CursorReq 明确列了 all，但 CursorV2Req 的注释只列了 archive/live/article；
 *   - 真实接口能不能吃 `all` 需要带完整 APP 风控头在真机上验证；
 *   - web 接口已用真登录态实测 `type=all` 返回视频+直播+专栏混合（PiliPlus 用的也是它）。
 * 所以这一条走纯 Web：`isWebApi = true` 跳过 appkey/Authorization，只靠 Cookie/WBI。
 */
class HistoryAPI {

    fun cursor(
        type: String,
        max: Long,
        viewAt: Long,
        pageSize: Int = 20,
    ) = MiaoHttp.request {
        isWebApi = true
        url = "https://api.bilibili.com/x/web-interface/history/cursor?" + ApiHelper.urlencode(
            mapOf(
                "type" to type,
                "ps" to pageSize.toString(),
                "max" to max.toString(),
                "view_at" to viewAt.toString(),
            )
        )
    }
}
