package com.a10miaomiao.bilimiao.comm.entity.video

import kotlinx.serialization.Serializable

/**
 * `x/web-interface/view` 里我们只关心的一点点信息：视频 id 与分区等。
 *
 * 用途：手动复检时，老版本的检测记录只存了"视频 BVxxxx"这样的文字，
 * 没有 aid（oid）——于是用它把 BV 换回 aid，让老记录也能复检。
 */
@Serializable
data class VideoIdInfo(
    val aid: Long = 0,
    val bvid: String = "",
    val cid: Long = 0,
)
