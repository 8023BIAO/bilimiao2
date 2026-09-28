package com.a10miaomiao.bilimiao.comm.apis

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
     *
     * rid 用 **x/v2/region/index 原样返回的 tid**（全站=0）：
     * 2026-02 逐个分区实测（未登录 curl + buvid3），动画1/音乐3/舞蹈129/游戏4/知识36/科技188/
     * 运动234/汽车223/生活160/美食211/动物圈217/鬼畜119/时尚155/娱乐5/影视181/纪录片177/
     * 电影23/电视剧11 全部 code=0 且有内容；只有 番剧13/国创167/资讯202/剧情85 回 -400。
     * ★这四个"没有 ranking/v2 榜单"的分区原先各走一个兜底接口（PGC 榜 / 分区最新投稿 / 旧版分区榜），
     *   那些接口随首页「分区」Tab 一起删除了（2026-09-28）—— 它们**没有**第三方调用方，
     *   需要时按 git 历史取回；本函数（时光机在用）不受影响。
     * 注意别照抄 PiliPlus 的 RankType 里那套 1001~1024 的 rid：那是它自己的另一套编号，
     * 实测 rid=1001 出的是科技(数码)内容、跟它标的"影视"对不上。
     */
    fun regionVideoRanking(
        rid: Int,
    ) = MiaoHttp.request {
        url = "https://api.bilibili.com/x/web-interface/ranking/v2?rid=${rid}&type=all"
    }

    // ★2026-09-28 删除：`pgcRankList` / `pgcSeasonRankList` / `regionNewlist` / `regionRankLegacy`
    //   四个接口 —— 它们**唯一**的调用点是首页「分区」Tab（已随 tab 一起删掉），删前已全仓 grep 确认无其它调用方。
    //   当时的实测结论（哪个分区只有哪个接口有内容）与实现都在 git 历史里，需要时按提交记录取回，别重新发明。
}

// ★2026-09-28 一并删除的返回壳：RegionNewlistInfo / RegionNewlistArchive / RegionNewlistOwner /
//   RegionNewlistStat / LegacyRegionRankItem —— 只被上面那 4 个已删接口使用（删前已逐个 grep 确认）。
