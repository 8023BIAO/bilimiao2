package com.a10miaomiao.bilimiao.comm.apis

import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp

class BangumiAPI {

    /**
     * 番剧信息
     */
    fun seasonInfo(seasonId: String) = MiaoHttp.request {
        url = BiliApiService.biliBangumi("view/api/season",
            "season_id" to seasonId
        )
    }

    /**
     * 番剧信息V2
     */
    fun seasonInfoV2(seasonId: String, epId: String) = MiaoHttp.request {
        url = BiliApiService.biliApi("pgc/view/v2/app/season",
            "season_id" to seasonId.ifBlank { null },
            "ep_id" to epId.ifBlank { null },
        )
    }

    /**
     * 番剧剧集信息
     */
    fun seasonSection(seasonId: String) = MiaoHttp.request {
        url = BiliApiService.biliApi("pgc/web/season/section",
            "season_id" to seasonId
        )
    }

    /**
     * 剧集信息
     */
    fun episodeInfo(epId: String) = MiaoHttp.request {
        url = BiliApiService.biliApi("pgc/view/app/season",
            "ep_id" to epId)
    }

    /**
     * 追番列表
     */
    fun followList(
        type: String = "bangumi",
        status: Int,
        pageNum: Int,
        pageSize: Int,
    ) = MiaoHttp.request {
        url = BiliApiService.biliApi(
            "pgc/app/follow/v2/$type",
            "status" to status.toString(),
            "pn" to pageNum.toString(),
            "ps" to pageSize.toString()
        )
    }

    /**
     * 收藏番剧
     */
    fun followSeason(seasonId: String) = MiaoHttp.request {
        url = BiliApiService.biliApi("pgc/app/follow/add")
        formBody = ApiHelper.createParams(
            "season_id" to seasonId,
        )
        method = MiaoHttp.POST
    }

    /**
     * 取消收藏番剧
     */
    fun cancelFollow(seasonId: String) = MiaoHttp.request {
        url = BiliApiService.biliApi("pgc/app/follow/del")
        formBody = ApiHelper.createParams(
            "season_id" to seasonId,
        )
        method = MiaoHttp.POST
    }

    /**
     * 设置状态
     */
    fun setFollowStatus(seasonId: String, status: Int) = MiaoHttp.request {
        url = BiliApiService.biliApi("pgc/app/follow/status/update")
        formBody = ApiHelper.createParams(
            "season_id" to seasonId,
            "status" to status.toString(),
        )
        method = MiaoHttp.POST
    }

    /**
     * 番剧/影视 索引筛选条件（动态获取）
     * @param seasonType 番剧=1, 影视综合=null
     * @param type 固定 0
     */
    fun seasonIndexCondition(
        seasonType: Int? = null,
        type: Int = 0,
        indexType: Int? = null,
    ) = MiaoHttp.request {
        val params = listOfNotNull(
            seasonType?.let { "season_type" to it.toString() },
            "type" to type.toString(),
            indexType?.let { "index_type" to it.toString() },
        )
        url = BiliApiService.biliApi("pgc/season/index/condition", *params.toTypedArray())
    }

    /**
     * 番剧/影视 索引列表（使用动态筛选参数）
     * @param seasonType 番剧=1, 影视=102
     * @param page 页码
     * @param pageSize 每页数量
     * @param params 筛选条件 map（从 condition API 获取的 field→keyword 映射）
     */
    fun seasonIndex(
        seasonType: Int? = null,
        page: Int = 1,
        pageSize: Int = 20,
        params: Map<String, String> = emptyMap(),
        indexType: Int? = null,
        type: Int = 0,
    ) = MiaoHttp.request {
        val baseParams = listOfNotNull(
            seasonType?.let { "season_type" to it.toString() },
            "page" to page.toString(),
            "pagesize" to pageSize.toString(),
            "type" to type.toString(),
            indexType?.let { "index_type" to it.toString() },
        )
        url = BiliApiService.biliApi(
            "pgc/season/index/result",
            *(baseParams + params.toList()).toTypedArray(),
        )
    }
}