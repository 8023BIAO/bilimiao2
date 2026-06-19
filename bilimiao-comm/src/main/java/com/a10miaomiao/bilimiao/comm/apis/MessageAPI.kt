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
