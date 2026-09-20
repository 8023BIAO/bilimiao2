package com.a10miaomiao.bilimiao.comm.entity.video

import kotlinx.serialization.Serializable

/**
 * 评论反诈检测用的精简实体（REST x/v2/reply/main 与 x/v2/reply/reply 共用）。
 *
 * 只声明检测真正要看的字段：rpid / ctime / invisible / replies（回复预览）。
 * 其余字段靠 MiaoJson 的 ignoreUnknownKeys 忽略。
 */
@Serializable
data class AntifraudCommentPageInfo(
    val replies: List<AntifraudCommentInfo>? = null,
    val top_replies: List<AntifraudCommentInfo>? = null,
    /** 取某条根评论的回复页时，这里就是那条根评论本身 */
    val root: AntifraudCommentInfo? = null,
    val cursor: AntifraudCommentCursorInfo? = null,
)

@Serializable
data class AntifraudCommentCursorInfo(
    val pagination_reply: AntifraudPaginationReplyInfo? = null,
)

@Serializable
data class AntifraudPaginationReplyInfo(
    /** 时间序翻页游标：下次请求的 pagination_str = {"offset":"<这个值>"} */
    val next_offset: String? = null,
)

@Serializable
data class AntifraudCommentInfo(
    val rpid: Long = 0,
    val oid: Long = 0,
    val mid: Long = 0,
    val root: Long = 0,
    val parent: Long = 0,
    val ctime: Long = 0,
    /** 前端"不可见"标记：为 true 时评论下载到了也不展示 */
    val invisible: Boolean = false,
    val content: AntifraudCommentContentInfo? = null,
    /** 回复预览（楼中楼前几条），seek_rpid 定位到的评论会出现在这里 */
    val replies: List<AntifraudCommentInfo>? = null,
)

@Serializable
data class AntifraudCommentContentInfo(
    val message: String = "",
)
