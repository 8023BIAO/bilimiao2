package com.a10miaomiao.bilimiao.comm.entity.video

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** B站 AI 视频总结 API 返回 */
@Serializable
data class AiConclusionData(
    @SerialName("model_result") val modelResult: AiConclusionResult? = null
)

@Serializable
data class AiConclusionResult(
    val summary: String? = null,
    val outline: List<AiOutline>? = null
)

@Serializable
data class AiOutline(
    val title: String? = null,
    val timestamp: Int? = null, // 秒
    @SerialName("part_outline") val partOutline: List<AiPartOutline>? = null
)

@Serializable
data class AiPartOutline(
    val timestamp: Int? = null, // 秒
    val content: String? = null
)
