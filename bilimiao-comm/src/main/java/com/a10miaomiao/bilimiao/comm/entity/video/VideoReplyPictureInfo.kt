package com.a10miaomiao.bilimiao.comm.entity.video

import kotlinx.serialization.Serializable

/**
 * 评论图片（发表评论时作为 pictures 字段的 JSON 数组元素提交）。
 *
 * 字段与 gRPC `bilibili.main.community.reply.v1.Picture` 一一对应：
 * img_src / img_width / img_height / img_size。
 *
 * @param img_src    上传接口返回的图片 URL
 * @param img_width  图片宽度（px）
 * @param img_height 图片高度（px）
 * @param img_size   图片大小（**KB**，服务端按这个值展示；上传接口返回的 img_size 就是 KB）
 */
@Serializable
data class VideoReplyPictureInfo(
    val img_src: String,
    val img_width: Int,
    val img_height: Int,
    val img_size: Int,
)
