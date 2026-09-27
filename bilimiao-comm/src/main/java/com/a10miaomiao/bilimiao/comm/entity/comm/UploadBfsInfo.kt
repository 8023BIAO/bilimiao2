package com.a10miaomiao.bilimiao.comm.entity.comm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * BFS 图片上传（`x/dynamic/feed/draw/upload_bfs`）返回的 data 字段。
 *
 * WEB（Cookie）通道返回：
 * ```json
 * {"image_url":"http://i0.hdslb.com/bfs/new_dyn/xxx.jpg",
 *  "image_width":1080,"image_height":1920,"img_size":662.6}
 * ```
 * APP 通道可能返回 `location` 或 `url` 字段名，这里做兼容联合。
 */
@Serializable
data class UploadBfsInfo(
    @SerialName("image_url") val image_url: String? = null,
    @SerialName("image_width") val image_width: Double = 0.0,
    @SerialName("image_height") val image_height: Double = 0.0,
    @SerialName("img_size") val img_size: Double = 0.0,
    /** 兼容字段：部分通道返回 location / url */
    val location: String? = null,
    val url: String? = null,
) {
    /** 真正的图片地址：优先 image_url，其次 location/url */
    val src: String get() = image_url?.takeIf { it.isNotBlank() }
        ?: location?.takeIf { it.isNotBlank() }
        ?: url?.takeIf { it.isNotBlank() }
        ?: ""

    val width: Int get() = image_width.toInt()
    val height: Int get() = image_height.toInt()
    val sizeKb: Int get() = img_size.toInt()
}
