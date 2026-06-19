package com.a10miaomiao.bilimiao.comm.entity.message

import kotlinx.serialization.Serializable

@Serializable
data class ChatMsgResponse(
    val messages: List<ChatMsgInfo>? = null,
    val has_more: Int = 0,
    val min_seqno: Long = 0,
    val max_seqno: Long = 0,
)

@Serializable
data class ChatMsgInfo(
    val msg_key: Long = 0,
    val msg_type: Int = 0,       // 1=text
    val sender_uid: Long = 0,
    val receiver_id: Long = 0,
    val content: String = "",    // JSON string: {"content":"消息文本"}
    val timestamp: Long = 0,
    val msg_seqno: Long = 0,
    val msg_status: Int = 0,
)
