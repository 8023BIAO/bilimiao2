package com.a10miaomiao.bilimiao.comm.entity.history

import kotlinx.serialization.Serializable

/**
 * `x/web-interface/history/cursor` 的 Web JSON 外壳。
 *
 * 与 PiliPlus `models_new/history` 同源。字段尽量可空 + 默认值：
 * B站 Web JSON 对封面数量 / 作者 mid / 进度等字段确实会显式返回 null，
 * MiaoJson 没开 coerceInputValues，非空字段遇到 null 会直接抛反序列化异常。
 */
@Serializable
data class WebHistoryResponse(
    val code: Int = 0,
    val message: String = "",
    val data: WebHistoryData? = null,
)

@Serializable
data class WebHistoryData(
    val tab: List<WebHistoryTab>? = null,
    val list: List<WebHistoryItem>? = null,
    val cursor: WebHistoryCursor? = null,
)

@Serializable
data class WebHistoryTab(
    val type: String? = null,
    val name: String? = null,
)

@Serializable
data class WebHistoryCursor(
    val max: Long? = null,
    val view_at: Long? = null,
    val business: String? = null,
    val ps: Int? = null,
)

@Serializable
data class WebHistoryItem(
    val title: String? = null,
    val cover: String? = null,
    val covers: List<String>? = null,
    val uri: String? = null,
    val history: WebHistory? = null,
    val author_name: String? = null,
    val author_mid: Long? = null,
    val view_at: Long? = null,
    val progress: Long? = null,
    val duration: Long? = null,
    val badge: String? = null,
    val show_title: String? = null,
    val kid: Long? = null,
    val tag_name: String? = null,
    val live_status: Int? = null,
    val videos: Long? = null,
    val is_fav: Int? = null,
)

@Serializable
data class WebHistory(
    val oid: Long? = null,
    val bvid: String? = null,
    val page: Int? = null,
    val cid: Long? = null,
    val business: String? = null,
    val epid: Long? = null,
)
