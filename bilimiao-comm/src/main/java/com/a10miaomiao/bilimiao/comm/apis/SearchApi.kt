package com.a10miaomiao.bilimiao.comm.apis

import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import java.net.URLEncoder

class SearchApi {


    /**
     * 关键字列表
     */
    fun suggestList(keyword: String) = MiaoHttp.request {
        // 直接拼接 term 时，搜索词里的 & # + 会被当成 URL 结构（如 "C#" 会在 # 处截断），必须转义
        // 调用方传入的是原始搜索词，这里只编码一次
        url = "https://s.search.bilibili.com/main/suggest?suggest_type=accurate&sub_type=tag&main_ver=v1&term=${URLEncoder.encode(keyword, "UTF-8")}"
    }

    /**
     * 综合
     */
    fun searchArchive(
        keyword: String,
        pageNum: Int,
        pageSize: Int,
        order: String,
        duration: Int,
        rid: Int,
    ) = MiaoHttp.request {
        url = BiliApiService.biliApp(
            "x/v2/search",
            "duration" to duration.toString(),
            "order" to order,
            "rid" to rid.toString(),
            "keyword" to keyword,
            "pn" to pageNum.toString(),
            "ps" to pageSize.toString(),
        )
    }

    /**
     * 番剧
     */
    fun searchBangumi(
        keyword: String,
        pageNum: Int,
        pageSize: Int
    ) = MiaoHttp.request{
        url = BiliApiService.biliApp(
            "x/v2/search/type",
            "keyword" to keyword,
            "type" to "7",
            "pn" to pageNum.toString(),
            "ps" to pageSize.toString(),
        )
    }

    /**
     * UP主
     */
    fun searchUpper(
        keyword: String,
        pageNum: Int,
        pageSize: Int,
        userType: Int = 0, // 0:全部,1:UP主,2:普通用户,3:认证用户
    ) = MiaoHttp.request{
        url = BiliApiService.biliApp(
            "x/v2/search/type",
            "disable_rcmd" to "0",
            "highlight" to "1",
            "order" to "totalrank",
            "keyword" to keyword,
            "type" to "2",
            "user_type" to userType.toString(),
            "pn" to pageNum.toString(),
            "ps" to pageSize.toString(),
        )
    }

}