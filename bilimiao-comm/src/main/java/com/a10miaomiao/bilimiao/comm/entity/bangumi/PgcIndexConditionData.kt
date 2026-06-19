package com.a10miaomiao.bilimiao.comm.entity.bangumi

import kotlinx.serialization.Serializable

/**
 * PGC 索引筛选条件（从 /pgc/season/index/condition 接口获取）
 * 动态返回 B站 当前支持的筛选维度
 */
@Serializable
data class PgcIndexConditionData(
    val filter: List<PgcConditionFilter> = emptyList(),
    val order: List<PgcConditionOrder> = emptyList(),
)

@Serializable
data class PgcConditionFilter(
    val field: String = "",
    val name: String = "",
    val values: List<PgcConditionValue> = emptyList(),
)

@Serializable
data class PgcConditionOrder(
    val field: String = "",
    val name: String = "",
    val sort: String = "",
)

@Serializable
data class PgcConditionValue(
    val keyword: String = "",
    val name: String = "",
)
