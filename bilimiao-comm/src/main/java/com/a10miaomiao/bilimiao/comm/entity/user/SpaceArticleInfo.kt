package com.a10miaomiao.bilimiao.comm.entity.user

import kotlinx.serialization.Serializable

/**
 * 用户空间「专栏」列表（APP 接口 `x/v2/space/article`）。
 *
 * 字段全部可空 + 默认值：B站返回里 `origin_image_urls` / `stats` 子项 / `id`
 * 等确实可能显式给 null，而 MiaoJson 没开 coerceInputValues，非空字段遇到 null 会抛异常。
 */
@Serializable
data class SpaceArticleInfo(
    val count: Int? = null,
    val item: List<SpaceArticleItem>? = null,
)

@Serializable
data class SpaceArticleItem(
    val id: Long? = null,
    val cvid: Long? = null,
    val title: String? = null,
    val stats: SpaceArticleStats? = null,
    val origin_image_urls: List<String>? = null,
    val uri: String? = null,
    val publish_time_text: String? = null,
)

@Serializable
data class SpaceArticleStats(
    val view: Long? = null,
    val reply: Long? = null,
    val like: Long? = null,
)
