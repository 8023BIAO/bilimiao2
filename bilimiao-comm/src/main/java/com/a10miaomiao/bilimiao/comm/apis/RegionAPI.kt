package com.a10miaomiao.bilimiao.comm.apis

import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp

class RegionAPI {

    /**
     * 分区列表
     */
    fun regions() = MiaoHttp.request {
        url = BiliApiService.biliApp(
            "x/v2/region/index",
            "mobi_app" to "android", // hd版api没有子分区
            "statistics" to """{"appId":1,"platform":3,"version":"7.66.0","abtest":""}""",
            "build" to "7660300",
        )
    }

    /**
     * 分区视频列表（newlist_rank）
     *
     * 时光机分区详情用的就是它，带 time_from/time_to 才能按时间段检索。
     * 注意：**不要**用未登录的 curl 去测它（会返回 -10），真机带 cookie 是正常的；
     * 曾因为误判"接口已废弃"把它换成 ranking/v2，导致时光机所有时间线都查不出东西。
     */
    fun regionVideoList(
        rid: Int,
        rankOrder: String,
        pageNum: Int,
        pageSize: Int,
        timeFrom: String,
        timeTo: String,
    ) = MiaoHttp.request {
        val params = mutableMapOf(
            "main_ver" to "v3",
            "search_type" to "video",
            "view_type" to  "hot_rank",
            "new_web_tag" to "1",
            "cate_id" to rid.toString(),
            "order" to rankOrder,
            "copy_right" to "-1",
            "page" to pageNum.toString(),
            "pagesize" to pageSize.toString(),
            "time_from" to timeFrom,
            "time_to" to timeTo
        )
        url = "https://api.bilibili.com/x/web-interface/newlist_rank?" + ApiHelper.urlencode(params)
    }

    /**
     * 分区排行榜视频列表（ranking/v2，替代已失效的newlist_rank）
     * 每个分区返回约95个视频，含完整统计数据（包括coin）
     */
    fun regionVideoRanking(
        rid: Int,
    ) = MiaoHttp.request {
        url = "https://api.bilibili.com/x/web-interface/ranking/v2?rid=${rid}&type=all"
    }
}