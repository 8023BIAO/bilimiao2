package com.a10miaomiao.bilimiao.comm.apis

import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.utils.WbiSigner
import java.util.Locale

class VideoAPI {

    /**
     * 视频信息
     */
    fun info(id: String, type: String = "AV",) = MiaoHttp.request {
        url = BiliApiService.biliApp("x/v2/view",
            when(type.lowercase()) {
                "av" -> "aid" to id
                "bv" -> "bvid" to id
                else -> throw IllegalArgumentException("Unsupported video type $type")
            },
            "autoplay" to "0",
            "qn" to "32"
        )
    }

    /**
     * 点👍
     */
    fun like(
        aid: String,
        dislike: Int,
        like: Int
    ) = MiaoHttp.request {
        method = "POST"
        url = BiliApiService.biliApp("x/v2/view/like")
        formBody = ApiHelper.createParams(
            "aid" to aid,
            "dislike" to dislike.toString(),
            "like" to like.toString()
        )
    }

    /**
     * 点👎
     */
    fun disLike(
        aid: String,
        dislike: Int,
        like: Int
    ) = MiaoHttp.request {
        method = "POST"
        url = BiliApiService.biliApp("x/v2/view/like")
        formBody = ApiHelper.createParams(
            "aid" to aid,
            "dislike" to dislike.toString(),
            "like" to like.toString()
        )
    }

    /**
     * 投币
     */
    fun coin(
        aid: String,
        num: Int,
        select_like: Int = 0
    ) = MiaoHttp.request {
        method = "POST"
        url = BiliApiService.biliApp("x/v2/view/coin/add")
        formBody = ApiHelper.createParams(
            "aid" to aid,
            "multiply" to num.toString(),
            "select_like" to select_like.toString()
        )
    }

    /**
     * 一键三连
     */
    fun triple(
        aid: String
    ) = MiaoHttp.request {
        method = "POST"
        url = BiliApiService.biliApp("x/v2/view/like/triple")
        formBody = ApiHelper.createParams(
            "aid" to aid
        )
    }

    fun favoriteCreated (
        aid: String
    ) = MiaoHttp.request {
        val mid = BilimiaoCommApp.commApp.loginInfo?.token_info?.let {
            it.mid.toString()
        } ?: ""
        url = BiliApiService.biliApi(
            "medialist/gateway/base/created",
            "up_mid" to mid,
            "rid" to aid,
            "type" to "2",
            "pn" to "1",
            "ps" to "100"
        )
    }

    fun favoriteDeal(
        aid: String,
        addIds: List<String>,
        delIds: List<String>
    ) = MiaoHttp.request {
        method = "POST"
        url = BiliApiService.biliApi("medialist/gateway/coll/resource/deal")
        formBody = ApiHelper.createParams(
            "add_media_ids" to StringBuilder("").apply {
                addIds.forEachIndexed { index, s ->
                    append(if (index == 0) s else ",$s")
                }
            }.toString(),
            "del_media_ids" to StringBuilder("").apply {
                delIds.forEachIndexed { index, s ->
                    append(if (index == 0) s else ",$s")
                }
            }.toString(),
            "rid" to aid,
            "type" to "2"
        )
    }


    /**
     * AI 视频总结
     */
    fun aiConclusion(bvid: String, cid: String, upMid: String) = MiaoHttp.request {
        // WEB API：跳过 APP 头部，显式计算 WBI 签名
        isWebApi = true
        val encBvid = java.net.URLEncoder.encode(bvid, "UTF-8")
        val encCid = java.net.URLEncoder.encode(cid, "UTF-8")
        val encUpMid = java.net.URLEncoder.encode(upMid, "UTF-8")
        val rawUrl = "https://api.bilibili.com/x/web-interface/view/conclusion/get?bvid=$encBvid&cid=$encCid&up_mid=$encUpMid"
        url = WbiSigner.signUrlBlocking(rawUrl)
    }

}
