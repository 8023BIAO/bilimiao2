package com.a10miaomiao.bilimiao.comm.apis

import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import kotlinx.serialization.Serializable

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
     * 电影23/电视剧11 全部 code=0 且有内容；只有 番剧13/国创167/资讯202/剧情85 回 -400
     * （番剧、国创改走 PGC 榜见 pgcSeasonRankList；资讯走分区最新投稿 regionNewlist；
     *  剧情走旧版分区榜 regionRankLegacy —— 它们各自"只有哪个接口有东西"见分区表 RegionCatalog）。
     * 注意别照抄 PiliPlus 的 RankType 里那套 1001~1024 的 rid：那是它自己的另一套编号，
     * 实测 rid=1001 出的是科技(数码)内容、跟它标的"影视"对不上。
     */
    fun regionVideoRanking(
        rid: Int,
    ) = MiaoHttp.request {
        url = "https://api.bilibili.com/x/web-interface/ranking/v2?rid=${rid}&type=all"
    }

    /**
     * PGC 排行榜（`/pgc/web/rank/list`）—— **不推荐**，保留作番剧的兜底。
     *
     * 对应 PiliPlus `VideoHttp.pgcRankList`（lib/http/video.dart:900）→ `Api.pgcRank`
     * （lib/http/api.dart:677 `/pgc/web/rank/list`），参数就是 `day` + `season_type`。
     *
     * 2026-02 实测：它只对 season_type=1（番剧，99 条）是满的，
     * 2 电影 48 条 / 3 纪录片 50 条 / 4 国创 46 条 / 5 电视剧 50 条 都明显偏少，
     * season_type=7（综艺）直接返回空数组 —— 综艺只有一个接口有内容，就是 [pgcSeasonRankList]。
     * 所以正常路径走 [pgcSeasonRankList]（它对 1~7 全部可用的实测数据见分区表 RegionCatalog），
     * 这个函数只在 season_type=1 且 season 榜异常时当兜底（两条路实测都出 99 条同样的番剧榜）。
     *
     * 为什么 isWebApi = true + 手拼 query（不用 biliApi）：
     * 这是 web 接口，只要 Cookie(+WBI) —— 该带的是网页那套参数，不该带 appkey/sign/access_key
     * 那套 APP 身份（biliApi 会全塞进 query，MessageAPI 就是踩了这个坑才单独写 biliMessageApi）。
     * 实测只有 UA + Cookie 就能出数据。写法照本文件里的 regionVideoList。
     */
    fun pgcRankList(
        seasonType: Int,
        day: Int = 3,
    ) = MiaoHttp.request {
        isWebApi = true
        url = "https://api.bilibili.com/pgc/web/rank/list?" + ApiHelper.urlencode(
            mapOf(
                "day" to day.toString(),
                "season_type" to seasonType.toString(),
            )
        )
    }

    /**
     * PGC 分季排行榜（`/pgc/season/rank/web/list`）—— 番剧/国创/纪录片/电影/电视剧/综艺 都走它。
     *
     * 对应 PiliPlus `VideoHttp.pgcSeasonRankList`（lib/http/video.dart:923）→ `Api.pgcSeasonRank`
     * （lib/http/api.dart:679 `/pgc/season/rank/web/list`）。
     * 与 [pgcRankList] 的区别：返回外壳是 `data`（那个是 `result`）、榜单口径不同、且**它是唯一有综艺的**。
     * 2026-02 实测（day=3）：season_type=1 → 99 条，2 → 100，3 → 100，4 → 97，5 → 100，7 综艺 → 100。
     */
    fun pgcSeasonRankList(
        seasonType: Int,
        day: Int = 3,
    ) = MiaoHttp.request {
        isWebApi = true
        url = "https://api.bilibili.com/pgc/season/rank/web/list?" + ApiHelper.urlencode(
            mapOf(
                "day" to day.toString(),
                "season_type" to seasonType.toString(),
            )
        )
    }

    /**
     * 分区**最新投稿**（`/x/web-interface/newlist`）—— 给"B站没做排行榜"的分区用。
     *
     * 目前只有「资讯」202 需要它：`ranking/v2?rid=202` 实测回 -400（资讯没有视频榜），
     * 但 newlist 实测 code=0、返回 20~50 条，条目 tid 是 203/205（资讯的子分区 热点/社会），
     * **内容与分区名对得上**，不会出现"点资讯看到别的区"。
     *
     * 它是这批接口里唯一**支持翻页**的（`page.num/size` 真的生效，ps 最大实测 50）。
     * 本页只取第一页 50 条：和别的分区一次给 60~100 条是一个量级，先不做分页，
     * 免得"只有资讯能翻页、别的分区翻不动"这种不一致（要翻页时在页面里改成 pn++ 追加即可）。
     *
     * 写法照本文件里的 regionVideoList（同一族的 web 接口）：直接拼完整 URL，不走 biliApi。
     * 实测多带 w_rid/wts/appkey/sign 服务端都不校验（假的 w_rid 也照样 code=0），
     * 所以在 app 里被 WBI 签名不影响它。
     */
    fun regionNewlist(
        rid: Int,
        pageNum: Int = 1,
        pageSize: Int = 50,
        order: String = "pubdate",
    ) = MiaoHttp.request {
        url = "https://api.bilibili.com/x/web-interface/newlist?" + ApiHelper.urlencode(
            mapOf(
                "rid" to rid.toString(),
                "pn" to pageNum.toString(),
                "ps" to pageSize.toString(),
                "order" to order,
            )
        )
    }

    /**
     * **旧版**分区排行榜（`/x/web-interface/ranking/region`）—— 给「剧情」85 用。
     *
     * 剧情是 region/index 里的顶级分区，但：`ranking/v2?rid=85` 回 -400、`newlist?rid=85` 回 0 条。
     * 只有这个旧接口实测有 9 条，`typename` 都是"小剧场"（tid=85 在影视下面就叫小剧场），
     * 内容与分区名对得上，只是**条数少**（9~11 条）。B站自己没给这个区做榜单，属于"能刷出来但就这么点"。
     *
     * `day` 只有 3 和 7 有数据（实测 day=1/30 回 -400），默认 3。
     */
    fun regionRankLegacy(
        rid: Int,
        day: Int = 3,
    ) = MiaoHttp.request {
        url = "https://api.bilibili.com/x/web-interface/ranking/region?" + ApiHelper.urlencode(
            mapOf(
                "rid" to rid.toString(),
                "day" to day.toString(),
            )
        )
    }
}

/**
 * `/x/web-interface/newlist` 的返回外壳。
 *
 * 为什么这类 HTTP 返回壳写在 API 文件里而不是 entity/region：本仓库已有先例
 * （MessageAPI.kt:277 的 DTO 也直接写在 API 文件里），而且本次改动刻意只落在
 * 「分区页 + 分区接口 + 一张分区表」三处，不新增别的文件。
 *
 * 字段全给默认值：这两个接口是"没有排行榜时的兜底来源"，B站偶尔少给一个可选字段，
 * 不该让整个分区报错（少一个字段最多是卡片上少一行）。
 */
@Serializable
data class RegionNewlistInfo(
    val archives: List<RegionNewlistArchive> = emptyList(),
)

@Serializable
data class RegionNewlistArchive(
    val aid: Long = 0,
    val bvid: String = "",
    val tid: Int = 0,
    val tname: String = "",
    val title: String = "",
    val pic: String = "",
    val pubdate: Long = 0,
    val duration: Int = 0,
    val owner: RegionNewlistOwner? = null,
    val stat: RegionNewlistStat? = null,
)

@Serializable
data class RegionNewlistOwner(
    val mid: Long = 0,
    val name: String = "",
)

@Serializable
data class RegionNewlistStat(
    val view: Long = 0,
    val danmaku: Long = 0,
)

/**
 * `/x/web-interface/ranking/region`（旧版分区榜）返回的**单条**。
 *
 * 注意它和 ranking/v2 结构完全不同：aid 是**字符串**、`duration` 已经是 "5:04" 这种文本、
 * UP 主是扁平的 `mid`/`author` 而不是 owner 对象、弹幕数叫 `video_review`。
 */
@Serializable
data class LegacyRegionRankItem(
    val aid: String = "",
    val bvid: String = "",
    /** 子分区名（剧情榜实测都是"小剧场"）——用来核对"内容与分区名对得上" */
    val typename: String = "",
    val title: String = "",
    val play: Long = 0,
    val video_review: Long = 0,
    val mid: Long = 0,
    val author: String = "",
    val pic: String = "",
    val duration: String = "",
    val create: String = "",
)