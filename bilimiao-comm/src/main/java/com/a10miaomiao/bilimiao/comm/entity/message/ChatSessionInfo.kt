package com.a10miaomiao.bilimiao.comm.entity.message

import kotlinx.serialization.Serializable

@Serializable
data class ChatSessionResponse(
    val session_list: List<ChatSessionInfo>? = null,
    val has_more: Int = 0,
)

@Serializable
data class ChatSessionInfo(
    val talker_id: Long = 0,
    val session_type: Int = 0,
    val is_follow: Int = 0,
    val is_dnd: Int = 0,
    val last_msg: ChatLastMsg? = null,
    val unread_count: Int = 0,
    val system_msg_type: Int = 0,
)

@Serializable
data class ChatLastMsg(
    val sender_uid: Long = 0,
    val receiver_id: Long = 0,
    val content_type: Int = 0,
    val content: String = "",
    val timestamp: Long = 0,
)
