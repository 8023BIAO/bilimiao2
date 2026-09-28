package com.a10miaomiao.bilimiao.comm.entity.user

import kotlinx.serialization.Serializable

@Serializable
data class SpaceInfo(
    var card: CardInfo,
    // ★2026-09-29 删除：`live: LiveInfo` 字段（连同下面的 LiveInfo 类）——
    //   它最后一位读者是空间页顶部头像的"直播中"标记，那个标记已按用户要求整体删除，
    //   全仓再没有 `.live` / `LiveInfo` 的消费方。接口仍会下发 `live` 对象，
    //   而 MiaoJson 开了 `ignoreUnknownKeys`，少声明一个字段只是被忽略，不影响解析。
    var images: ImagesInfo,
//    var favourite: Media<FavouriteItem>,
    var favourite2: Media<Favourite2Item>,
    var season: Media<SeasonItem>,
    var archive: Media<ArchiveItem>,
    var coin_archive: Media<ArchiveItem>,
    var like_archive: Media<ArchiveItem>,
    var tab: Tab
){
    @Serializable
    data class CardInfo(
        val approve: Boolean,
        val article: Int,
        val attention: Int,
//        val attentions: Any,
        val birthday: String,
        val description: String,
        val face: String,
        val fans: Int,
        val friend: Int,
        val level_info: LevelInfo,
        val likes: LikesInfo,
        val mid: String,
        val name: String,
        val official_verify: OfficialVerifyInfo,
        val place: String,
        val rank: String,
        val regtime: Int,
        val relation: RelationInfo,
        val sex: String = "",
        val sign: String,
        val spacesta: Int,
        val space_tag: List<SpaceTagInfo>?,
        val silence: Int = 0,
    )

    @Serializable
    data class RelationInfo(
        val status: Int,
        var is_follow: Int = 0,
    )

    @Serializable
    data class LevelInfo(
        val current_exp: Int,
        val current_level: Int,
        val current_min: Int,
        val next_exp: String? = null,
    )

    @Serializable
    data class OfficialVerifyInfo(
        val desc: String,
        val type: Int,
        val role: Int,
        val title: String,
        val icon: String,
    )

    @Serializable
    data class LikesInfo(
        val skr_tip: String,
        val like_num: Int,
    )

    @Serializable
    data class ImagesInfo(
        val imgUrl: String
    )

    // ★2026-09-29 删除：`LiveInfo`（`x/v2/space` 返回里的 `live` 对象）——
    //   原用途只有一处：空间页顶部头像挂「直播中」标记（零额外请求地拿到 liveStatus/roomid）。
    //   标记已按用户要求整体删除，这里也就没有读者了；将来若要恢复，按 git 历史取回这份
    //   字段声明与实测记录（在播/未开播/轮播三种返回样本都记在当时的注释里）。

    @Serializable
    data class Tab(
        val archive: Boolean,
        val favorite: Boolean,
        val bangumi: Boolean,
        val like: Boolean
    )

    @Serializable
    data class Media<T>(
        var count: Int,
        var item: List<T>
    )

//    @Serializable
//    data class FavouriteItem(
//        val atten_count: Int,
//        val cover: List<FavouriteItemCover>,
//        val ctime: Int,
//        val cur_count: Int,
//        val fid: Long,
//        val max_count: Int,
//        val media_id: Long,
//        val mid: Long,
//        val mtime: Long,
//        val name: String,
//        val state: Int
//    )

    @Serializable
    data class Favourite2Item(
        val media_id: String,
        val id: String,
        val mid: String,
        val title: String,
        val cover: String,
        val count: Int,
        val type: Int,
        val is_public: Int,
        val ctime: String,
        val mtime: String,
        val is_default: Boolean = false,
    )

//    @Serializable
//    data class FavouriteItemCover(
//        val aid: Int,
//        val pic: String,
//        val type: Int
//    )

    @Serializable
    data class ArchiveItem(
        val author: String,
        val cover: String,
        val ctime: Long,
        val danmaku: String,
        val duration: Int,
        val goto: String,
        val length: String,
        val `param`: String,
        val play: String,
        val state: Boolean,
        val title: String,
        val tname: String,
        val ugc_pay: Int,
        val uri: String
    )

    @Serializable
    data class SeasonItem(
        val attention: String,
        val cover: String,
        val finish: Int,
        val goto: String,
        val index: String,
        val is_finish: String,
        val is_started: Int,
        val mtime: Int,
        val newest_ep_id: String,
        val newest_ep_index: String,
        val `param`: String,
        val title: String,
        val total_count: String,
        val uri: String
    )

    @Serializable
    data class SpaceTagInfo(
        val type: String,
        val title: String,
    )
}