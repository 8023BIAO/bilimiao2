package com.a10miaomiao.bilimiao.comm.apis

import bilibili.im.interfaces.v1.ImInterfaceGRPC
import bilibili.im.interfaces.v1.ReqGetSessions
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp

class MessageAPI {

    /**
     * 获取未读消息
     */
    fun unread() = MiaoHttp.request {
        url = BiliApiService.biliApi("x/msgfeed/unread")
    }

    /**
     * 获取点赞消息
     */
    fun like(
        id: Long,
        time: Long,
    ) = MiaoHttp.request {
        url = BiliApiService.biliApi(
            "x/msgfeed/like",
            "id" to id.toString(),
            "like_time" to time.toString(),
        )
    }

    /**
     * 获取@我的消息.
     */
    fun at(
        id: Long,
        time: Long,
    ) = MiaoHttp.request {
        url = BiliApiService.biliApi(
            "x/msgfeed/at",
            "id" to id.toString(),
            "at_time" to time.toString(),
        )
    }

    /**
     * 获取回复我的消息.
     */
    fun reply(
        id: Long,
        time: Long,
    ) = MiaoHttp.request {
        url = BiliApiService.biliApi(
            "x/msgfeed/reply",
            "id" to id.toString(),
            "reply_time" to time.toString(),
        )
    }

    /**
     * 获取系统通知列表（消息中心 → 系统通知）。
     *
     * ⚠️ 这个接口**不在 api.bilibili.com** 上，而是在 message.bilibili.com（Web 接口：
     *    只认 Cookie(SESSDATA) + csrf，不认 APP 的 appkey/sign/access_key），
     *    所以这里用 biliMessageApi + isWebApi，而不是本类其它方法用的 biliApi。
     *    （同 CommentApi.addWithPictures 的道理：带上 appkey/sign 会被服务端按"APP 通道"判定。）
     *
     * 分页：首屏不传 cursor；下一页传**上一页最后一条**的 cursor（PiliPlus handleListResponse 的用法）。
     * 返回体的 `data` 直接是数组（没有顶层 cursor 对象），故解析成 List<SystemMessageInfo>。
     *
     * 来源：PiliPlus lib/http/msg.dart 的 MsgHttp.msgFeedNotify + lib/http/api.dart 的 Api.msgSysNotify。
     * 状态：接口地址与参数已确认（照抄 PiliPlus）；返回结构由 PiliPlus 的解析代码反推 → **待实机验证**。
     */
    fun sysNotify(
        cursor: Long? = null,
        pageSize: Int = 20,
    ) = MiaoHttp.request {
        isWebApi = true
        url = BiliApiService.biliMessageApi(
            "x/sys-msg/query_notify_list",
            "cursor" to cursor?.toString(),
            "page_size" to pageSize.toString(),
            // mobi_app=web + build=0：按 Web 端返回（time_at 才是给人看的文案）；build 不要用 APP 的版本号
            "mobi_app" to "web",
            "build" to "0",
            "web_location" to "333.40164",
        )
    }

    /**
     * 上报"系统通知读到哪了"（服务端游标），成功之后 Tab 红点才会灭。
     *
     * csrf 取 WebView 侧 cookie 里的 bili_jct（MiaoHttp.csrfToken）：我们发的是 Web 请求，用的是同一套 Cookie。
     * 取不到时 ApiHelper.urlencode 会把空值参数丢掉，服务端会以 csrf 校验失败拒绝 ——
     * 调用方按"上报失败不影响看通知"处理即可（未读数只是留着下次再消）。
     *
     * 来源：PiliPlus lib/http/msg.dart 的 MsgHttp.msgSysUpdateCursor（GET + csrf + cursor）。
     * 状态：待实机验证。
     */
    fun sysUpdateCursor(cursor: Long): MiaoHttp {
        val csrf = MiaoHttp.csrfToken()
        return MiaoHttp.request {
            isWebApi = true
            url = BiliApiService.biliMessageApi(
                "x/sys-msg/update_cursor",
                "csrf" to csrf,
                "cursor" to cursor.toString(),
            )
        }
    }

    /**
     * 获取私信会话列表（REST API，保留兼容）
     */
    fun sessions() = MiaoHttp.request {
        url = BiliApiService.biliVcApi(
            "session_svr/v1/session_svr/get_sessions",
            "session_type" to "1",
            "group_fold" to "1",
        )
    }

    /**
     * 🔧 获取私信会话列表（gRPC，参考 PiliPlus）
     *   一次请求返回 SessionInfo（含 AccountInfo：头像+昵称），无需额外查用户信息
     */
    fun sessionsGrpc(
        size: Int = 100,
    ) = BiliGRPCHttp(
        ImInterfaceGRPC.getSessions(
            ReqGetSessions(
                sessionType = 1,
                groupFold = 1,
                size = size,
            )
        )
    )

    /**
     * 获取会话消息
     */
    fun fetchMsgs(
        talkerId: Long,
        size: Int = 20,
        beginSeqno: Long = 0,
    ) = MiaoHttp.request {
        url = BiliApiService.biliVcApi(
            "svr_sync/v1/svr_sync/fetch_session_msgs",
            "talker_id" to talkerId.toString(),
            "session_type" to "1",
            "size" to size.toString(),
            "begin_seqno" to beginSeqno.toString(),
        )
    }

    /**
     * 移除私信会话
     */
    fun removeSession(talkerId: Long) = MiaoHttp.request {
        url = BiliApiService.biliVcApi("session_svr/v1/session_svr/remove_session")
        method = MiaoHttp.POST
        val csrf = BilimiaoCommApp.commApp.loginInfo?.cookie_info?.cookies
            ?.find { it.name == "bili_jct" }?.value ?: ""
        formBody = mapOf(
            "talker_id" to talkerId.toString(),
            "session_type" to "1",
            "csrf" to csrf,
            "csrf_token" to csrf,
        )
    }

    /**
     * 删除消息通知（回复/@/点赞）
     */
    fun delMsgfeed(tp: Int, id: Long) = MiaoHttp.request {
        url = BiliApiService.biliApi("x/msgfeed/del")
        method = MiaoHttp.POST
        val csrf = BilimiaoCommApp.commApp.loginInfo?.cookie_info?.cookies
            ?.find { it.name == "bili_jct" }?.value ?: ""
        formBody = mapOf(
            "tp" to tp.toString(),
            "id" to id.toString(),
            "csrf" to csrf,
            "csrf_token" to csrf,
            "build" to "0",
            "mobi_app" to "web",
        )
    }

    /**
     * 删除消息通知（LikeMessageInfo 的 id 为 String 类型）
     */
    fun delMsgfeed(tp: Int, id: String) = MiaoHttp.request {
        url = BiliApiService.biliApi("x/msgfeed/del")
        method = MiaoHttp.POST
        val csrf = BilimiaoCommApp.commApp.loginInfo?.cookie_info?.cookies
            ?.find { it.name == "bili_jct" }?.value ?: ""
        formBody = mapOf(
            "tp" to tp.toString(),
            "id" to id,
            "csrf" to csrf,
            "csrf_token" to csrf,
            "build" to "0",
            "mobi_app" to "web",
        )
    }

}
