package com.a10miaomiao.bilimiao.comm.network

import com.a10miaomiao.bilimiao.comm.apis.*

object BiliApiService {
    fun createUrl(url: String, vararg pairs: Pair<String, String?>): String {
        val params = ApiHelper.createParams(*pairs)
        return url + "?" + ApiHelper.urlencode(params)
    }

    fun biliApi(path: String, vararg pairs: Pair<String, String?>): String {
        return createUrl("https://api.bilibili.com/$path", *pairs)
    }

    fun biliVcApi(path: String, vararg pairs: Pair<String, String?>): String {
        return createUrl("https://api.vc.bilibili.com/$path", *pairs)
    }

    fun biliApp(path: String, vararg pairs: Pair<String, String?>): String {
        return createUrl("https://app.bilibili.com/$path", *pairs)
    }

    fun biliBangumi(path: String, vararg pairs: Pair<String, String?>): String {
        return createUrl("https://bangumi.bilibili.com/$path", *pairs)
    }

    /**
     * 消息中心（message.bilibili.com）——系统通知等 Web 接口。
     *
     * 为什么不用 [createUrl]：它内部走 ApiHelper.createParams，会自动注入
     * appkey/platform/mobi_app/statistics/access_key/mid/sign 一整套 **APP 参数**
     * （还会把 mobi_app 覆盖成 android_hd）。message.bilibili.com 上的接口是 Web 接口，
     * 只认 Cookie(SESSDATA) + csrf，带上 appkey/sign 会被服务端按 APP 通道解析
     * —— CommentApi.addWithPictures 就是踩了这个坑（12088 不支持发送图片）。
     * 所以这里只拼"路径 + 调用方自己的参数"；值为空的参数由 urlencode 丢掉。
     */
    fun biliMessageApi(path: String, vararg pairs: Pair<String, String?>): String {
        val query = ApiHelper.urlencode(mapOf(*pairs))
        val base = "https://message.bilibili.com/$path"
        return if (query.isBlank()) base else "$base?$query"
    }

    val regionAPI = RegionAPI()
    val videoAPI = VideoAPI()
    val bangumiAPI = BangumiAPI()
    val commentApi = CommentApi()
    val searchApi = SearchApi()
    val playerAPI = PlayerAPI()
    /** 小电视空降助手（BilibiliSponsorBlock）：独立服务端 bsbsb.top，与 B 站 API 无关 */
    val sponsorBlockAPI = SponsorBlockApi()
    val userApi = UserApi()
    /** 账号资料（编辑资料页）：读 x/v2/account/myinfo + 改昵称/签名，见 MemberProfileApi */
    val memberProfileApi = MemberProfileApi()
    val userRelationApi = UserRelationApi()
    val messageApi = MessageAPI()
    val authApi = AuthApi()
    val homeApi = HomeApi()
    val archiveApi = ArchiveApi()
}