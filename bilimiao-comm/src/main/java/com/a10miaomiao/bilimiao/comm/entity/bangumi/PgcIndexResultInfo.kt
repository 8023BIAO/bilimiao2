package com.a10miaomiao.bilimiao.comm.entity.bangumi

import kotlinx.serialization.Serializable

@Serializable
data class PgcIndexResultInfo(
    val has_next: Int = 0,
    val list: List<PgcIndexItem> = emptyList(),
)

@Serializable
data class PgcIndexItem(
    val badge: String = "",
    val cover: String = "",
    val index_show: String = "",
    val order: String = "",
    val season_id: Int = 0,
    val title: String = "",
)
