package com.a10miaomiao.bilimiao.comm.entity.user

import kotlinx.serialization.Serializable

@Serializable
data class SpaceInfo(
    var card: CardInfo,
    var live: LiveInfo,
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

    /**
     * `x/v2/space` 返回里的 `live` 对象 —— **顺带**告诉了我们这个 UP 在不在播。
     *
     * ★为什么"头像挂直播中标记"的用户空间那条路一条请求都不用多发：
     *   这个对象本来就在空间页首屏的返回里（我们早就在解析它，只是一直没用全），
     *   里面 `liveStatus` / `roomid` 就是现成的在播状态和房间号。
     *   PiliPlus 的做法完全一样：`roomId: live?.liveStatus == 1 ? live!.roomid : null`
     *   （lib/pages/member/widget/user_info_card.dart:529，数据来自
     *   它 MemberController 的 `live = data.live`，controller.dart:109）。
     *
     * ★实测（2026-09，容器内用 APP 签名 curl `https://app.bilibili.com/x/v2/space?vmid=<uid>`）：
     *   - 在播的 uid（103128201）→ `liveStatus:1, roomid:24158116`；
     *   - 没开播的 uid（2 碧诗）→ `liveStatus:0, roomStatus:1, roomid:1024`；
     *   - **不论在不在播，`live` 对象都在**（不是 null），所以判空逻辑可以很直白。
     *
     * ★为什么 `roomStatus` / `roundStatus` 也要一起声明：
     *   网页版和 APP 版对"轮播"的表达不一致 —— 同一个嘉然（672328094），
     *   空间接口给 `liveStatus:0 + roundStatus:1`，而直播接口
     *   `get_status_info_by_uids` 给 `live_status:2`。只认 `liveStatus == 1`
     *   两边就自然对齐了（轮播不给标记：进去也没流，见 LiveStatus.isPlayable）。
     *   把这两个字段留着是为了将来排查"为什么没出标记"时不用再抓包。
     *
     * ★为什么要给默认值：新增字段必须向后兼容 —— 万一某个 uid 的返回里没有
     *   `liveStatus`（MiaoJson 没开 coerceInputValues，缺字段会直接抛异常），
     *   默认 0 等价于"没在播"，正好是安全的降级方向。
     */
    @Serializable
    data class LiveInfo(
        val url: String,
        val title: String,
        val cover: String,
        val roomid: Long,
        /** 0 未开播 / 1 直播中 / 2 轮播；缺省 = 未开播 */
        val liveStatus: Int = 0,
        /** 房间是否有效（下播后仍是 1，所以**不能**拿它当"在播"判据） */
        val roomStatus: Int = 0,
        val roundStatus: Int = 0,
    )

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