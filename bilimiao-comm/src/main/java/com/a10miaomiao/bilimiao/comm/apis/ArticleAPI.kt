package com.a10miaomiao.bilimiao.comm.apis

import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp

class ArticleAPI {

    /**
     * 专栏信息（元数据）
     */
    fun info(id: String) = MiaoHttp.request {
        url = BiliApiService.biliApi("x/article/viewinfo",
            "id" to id,
        )
    }

    /**
     * 专栏正文（含结构化内容）
     */
    fun view(id: String) = MiaoHttp.request {
        url = BiliApiService.biliApi("x/article/view",
            "id" to id,
        )
    }

}