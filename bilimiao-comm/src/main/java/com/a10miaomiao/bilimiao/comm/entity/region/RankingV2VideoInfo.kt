package com.a10miaomiao.bilimiao.comm.entity.region

import kotlinx.serialization.Serializable

/**
 * ranking/v2 接口返回的顶层包装
 */
@Serializable
data class RankingV2Response(
    val note: String = "",
    val list: List<RankingV2VideoInfo> = emptyList(),
)

/**
 * ranking/v2 返回的视频信息（字段与 newlist_rank 不同）
 */
@Serializable
data class RankingV2VideoInfo(
    val aid: Long,
    val bvid: String,
    val title: String,
    val pic: String,
    val pubdate: Long,
    val duration: Int,
    val owner: RankingV2Owner,
    val stat: RankingV2Stat,
    val description: String = "",
    val copyright: Int = 1,
    val mid: Long = 0,
) {
    /**
     * 转换为 HomeTimeSelectContent 使用的 RegionVideoInfo 格式
     */
    fun toRegionVideoInfo(): RegionVideoInfo = RegionVideoInfo(
        author = owner.name,
        bvid = bvid,
        description = description,
        duration = duration.toString(),
        favorites = stat.favorite.toString(),
        id = aid.toString(),
        pic = pic,
        play = stat.view.toString(),
        pubdate = pubdate.toString(),
        review = stat.reply.toString(),
        video_review = stat.danmaku.toString(),
        title = title,
        type = "",
        tag = "",
        mid = owner.mid.toString(),
        is_pay = 0,
        is_union_video = 0,
        rank_index = 0,
        rank_offset = 0,
        rank_score = 0,
        senddate = pubdate,
    )
}

@Serializable
data class RankingV2Owner(
    val mid: Long,
    val name: String,
)

@Serializable
data class RankingV2Stat(
    val view: Long,
    val danmaku: Long,
    val reply: Long,
    val favorite: Long,
    val coin: Long,
)
