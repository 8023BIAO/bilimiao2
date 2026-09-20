package com.a10miaomiao.bilimiao.comm.apis

import com.a10miaomiao.bilimiao.comm.entity.video.VideoReplyPictureInfo
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class CommentApi() {

    /**
     * 评论列表（REST，仅按时间排序）—— 评论反诈检测用（见 [CommentAntifraud]）。
     *
     * 与原 [mainList] 的区别：这个走的是"时间倒序 + 游标翻页"，
     * 因为检测要判断的是"刚发的评论在时间序里排第几、有没有被吞"。
     *
     * @param offset  翻页游标：首次传 null（等价 {"offset":""}），之后取响应里的
     *                data.cursor.pagination_reply.next_offset
     * @param seekRpid 定位评论：传楼中楼的 rpid 时，B站会把该评论塞进预览域（replies）里
     * @param asGuest  游客模式（不带登录 Cookie）。**必须带 buvid3**，否则 -352 风控校验失败
     */
    fun mainListByTime(
        oid: String,
        type: Int,
        offset: String? = null,
        seekRpid: Long? = null,
        asGuest: Boolean = false,
        guestBuvid3: String? = null,
    ) = MiaoHttp.request {
        val params = mutableListOf<Pair<String, String?>>(
            "oid" to oid,
            "type" to type.toString(),
            // mode=2：仅按时间（0/3 热度、1 热度+时间、2 时间）
            "mode" to "2",
            "plat" to "2",
            "ps" to "20",
            "pagination_str" to """{"offset":"${offset ?: ""}"}""",
        )
        if (seekRpid != null && seekRpid > 0) {
            params.add("seek_rpid" to seekRpid.toString())
        }
        url = BiliApiService.biliApi("x/v2/reply/main", *params.toTypedArray())
        asGuestMode(asGuest, guestBuvid3)
    }

    /**
     * 取某条**根评论**的回复页（REST）。
     *
     * 反诈检测的关键特性（biliSendCommAntifraud 的实测结论）：
     *  - 登录账号来查：评论被系统秒删 → 12022；ShadowBan / 正常 → 0
     *  - 游客身份来查：ShadowBan → 12022/12006；正常 → 0
     * 所以"登录查得到 + 游客查不到"就是 ShadowBan。
     */
    fun replyPage(
        oid: String,
        type: Int,
        root: Long,
        pageNum: Int = 1,
        pageSize: Int = 20,
        asGuest: Boolean = false,
        guestBuvid3: String? = null,
    ) = MiaoHttp.request {
        url = BiliApiService.biliApi(
            "x/v2/reply/reply",
            "oid" to oid,
            "type" to type.toString(),
            "root" to root.toString(),
            "pn" to pageNum.toString(),
            "ps" to pageSize.toString(),
            "sort" to "0",
        )
        asGuestMode(asGuest, guestBuvid3)
    }

    /** 把"游客模式 + buvid3"塞进请求（游客不能带 app-key/Authorization/登录 Cookie） */
    private fun MiaoHttp.asGuestMode(asGuest: Boolean, guestBuvid3: String?) {
        if (!asGuest) return
        this.asGuest = true
        this.isWebApi = true
        if (!guestBuvid3.isNullOrBlank()) {
            this.guestCookie = "buvid3=$guestBuvid3"
        }
    }

    /** 视频评论 */
    fun mainList(
        aid: String,
        sort: Int,
        type: Int,
        pageNum: Int,
        pageSize: Int
    ) = MiaoHttp.request {
        url = BiliApiService.biliApi(
            "x/v2/reply/main",
            "oid" to aid,
            "plat" to "2",
            "sort" to sort.toString(),
            "pn" to pageNum.toString(),
            "ps" to pageSize.toString(),
            "type" to type.toString(),
        )
    }

    /**
     * 评论回复列表
     */
    fun replyList(
        oid: String,
        rpid: String,
        type: Int,
        pageNum: Int,
        pageSize: Int
    ) = MiaoHttp.request {
        url = BiliApiService.biliApi(
            "x/v2/reply/main",
            "oid" to oid,
            "plat" to "2",
            "root" to rpid,
            "sort" to "0",
            "type" to type.toString(),
            "pn" to pageNum.toString(),
            "ps" to pageSize.toString(),
        )
    }

    /**
     * 点赞/取消赞
     */
    fun action(
        type: Int, // 评论区类型代码
        oid: String, // 目标评论区id
        rpid: String, // 目标评论rpid
        action: Int, // 操作代码 0：取消赞 1：点赞
    ) = MiaoHttp.request {
        url = BiliApiService.biliApi("x/v2/reply/action")
        method = MiaoHttp.POST
        formBody = ApiHelper.createParams(
            "type" to type.toString(),
            "oid" to oid,
            "rpid" to rpid,
            "action" to action.toString(),
        )
    }

    fun emoteList() = MiaoHttp.request {
        url = BiliApiService.biliApi(
            "x/emote/user/panel",
            "business" to "reply"
        )
    }

    fun emoteList(
        ids: String,
    ) = MiaoHttp.request {
        url = BiliApiService.biliApi(
            "x/emote/package",
            "business" to "reply",
            "ids" to ids,
        )
    }

    /**
     * 发表评论 / 回复
     *
     * @param pictures 评论图片（可选）：上传接口拿到的 image_url + 宽高。
     *                 服务端按 pictures 字段渲染；只在主楼评论（root/parent 为空）时可用。
     */
    fun add(
        message: String,
        type: Int,
        oid: String,
        root: String? = null,
        parent: String? = null,
        pictures: List<VideoReplyPictureInfo>? = null,
    ) = MiaoHttp.request {
        url = BiliApiService.biliApi("x/v2/reply/add")
        method = MiaoHttp.POST
        val params = mutableMapOf<String, String?>(
            "type" to type.toString(),
            "oid" to oid,
            "message" to message,
            "plat" to "2",
        )
        // 二级评论根评论
        root?.let { params.put("root", it) }
        // 二级评论父级评论
        parent?.let { params.put("parent", it) }
        // 评论图片：JSON 字符串数组（与 PiliPlus 的 replyAdd 同款字段）
        if (!pictures.isNullOrEmpty()) {
            params["pictures"] = MiaoJson.toJson(pictures)
        }
        formBody = ApiHelper.createParams(params)
    }

    /**
     * 发表**带图评论**（纯 WEB 通道）。
     *
     * ⚠️ 关键坑（已用真机+真接口实测）：
     * 带 `pictures` 的评论**不能走 APP 签名通道**（appkey/platform/mobi_app/statistics/
     * access_key/mid/sign + app-key/Authorization 头），服务端会直接回
     * `12088 不支持发送图片`。同一张图、同一条视频，纯 web（仅 Cookie + csrf + WBI）就成功。
     * 所以带图评论必须走这里，不能复用 [add]。
     */
    fun addWithPictures(
        message: String,
        type: Int,
        oid: String,
        pictures: List<VideoReplyPictureInfo>,
        minimalForm: Boolean = true,
    ): MiaoHttp {
        val csrf = MiaoHttp.csrfToken()
        // ★ 字段清单严格对齐 PiliPlus 的 replyAdd：type/oid/message/pictures/csrf
        //   —— 它连 plat 都不传。B 站对 pictures 这条链路似乎会按表单字段判定"客户端类型"，
        //   多传 plat / csrf_token / build 都可能触发 12088 不支持发送图片。
        val params = linkedMapOf<String, String?>(
            "type" to type.toString(),
            "oid" to oid,
            "message" to message,
            "pictures" to MiaoJson.toJson(pictures),
        )
        if (!minimalForm) {
            params["plat"] = "1"
        }
        if (!csrf.isNullOrBlank()) params["csrf"] = csrf
        return MiaoHttp.request {
            isWebApi = true
            // ⚠️ 不能用 BiliApiService.createUrl：它内部调 ApiHelper.createParams，
            //    会自动注入 appkey/platform/mobi_app/statistics/access_key/mid/sign 整套 APP 参数，
            //    B 站看到 appkey+sign 就把带图评论判成"客户端发图"→ 12088 不支持发送图片。
            //    这里只拼我们自己这几个字段。
            url = "https://api.bilibili.com/x/v2/reply/add?" +
                com.a10miaomiao.bilimiao.comm.network.ApiHelper.urlencode(params)
            method = MiaoHttp.POST
            if (!minimalForm) {
                // 备用表单：额外带 csrf_token + build（WBI 签名需要 query 有参数）
                formBody = LinkedHashMap(params).apply {
                    if (!csrf.isNullOrBlank()) put("csrf_token", csrf)
                    put("build", com.a10miaomiao.bilimiao.comm.network.ApiHelper.BUILD_VERSION.toString())
                }
            } else {
                formBody = params
            }
        }
    }

    /**
     * 上传图片到 B 站图床（BFS）。返回可直接放进评论 pictures 的图片信息。
     *
     * 与 PiliPlus 的 `MsgHttp.uploadBfs` 同款接口：multipart/form-data，文件字段名 `file_up`。
     *
     * **两条通道二选一**（这是踩过的坑）：
     *  - WEB 通道：`isWebApi = true`，只靠 WebView Cookie（SESSDATA + bili_jct）+ csrf。
     *    如果 App 是扫码登录、CookieManager 里没有 web 登录态，这条会返回 `-101 账号未登录`。
     *  - APP 通道：带 app-key/Authorization(access_token)，和 `x/v2/reply/add` 一样的鉴权。
     *    Cookie 不可用时兜底，保证"能发文字评论就一定能发图"。
     *
     * @param file 本地图片文件（调用方先压缩）
     * @param biz  业务标识：评论 "reply"，动态 "new_dyn"，私信 "im"
     * @param preferWeb true = 先走 WEB 通道（更贴合 B 站 web 行为），失败自动回落 APP 通道
     */
    fun uploadImage(
        file: File,
        biz: String = "reply",
        category: String = "daily",
        preferWeb: Boolean = true,
    ): MiaoHttp {
        val csrf = MiaoHttp.csrfToken()
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        // 该接口无 query 参数时 WbiSigner 不会签名、URL 拼接也会退化，显式带上 build
        val baseParams = mutableListOf<Pair<String, String?>>("build" to ApiHelper.BUILD_VERSION.toString())
        if (!csrf.isNullOrBlank()) {
            baseParams.add("csrf" to csrf)
            baseParams.add("csrf_token" to csrf)
        }
        val url = "https://api.bilibili.com/x/dynamic/feed/draw/upload_bfs?" +
            ApiHelper.urlencode(baseParams.toMap())
        return MiaoHttp.request {
            this.url = url
            method = MiaoHttp.POST
            if (preferWeb) {
                // WEB 通道：不带 app-key/Authorization
                isWebApi = true
            } else {
                // APP 通道：MiaoHttp 会自动补 app-key / x-bili-mid / Authorization
                isWebApi = false
            }
            body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file_up", file.name, file.asRequestBody(mime.toMediaType()))
                .addFormDataPart("category", category)
                .addFormDataPart("biz", biz)
                .apply { if (!csrf.isNullOrBlank()) addFormDataPart("csrf", csrf) }
                .build()
        }
    }

    /** 当前 Cookie 里有没有 web 登录态（SESSDATA）；没有就别指望 WEB 通道 */
    fun hasWebLoginCookie(): Boolean = MiaoHttp.sessDataToken() != null

    fun del(
        type: Int,
        oid: String,
        rpid: String,
    ) = MiaoHttp.request {
        url = BiliApiService.biliApi("x/v2/reply/del")
        method = MiaoHttp.POST
        val params = mutableMapOf<String, String?>(
            "type" to type.toString(),
            "oid" to oid,
            "rpid" to rpid,
        )
        formBody = ApiHelper.createParams(params)
    }


}