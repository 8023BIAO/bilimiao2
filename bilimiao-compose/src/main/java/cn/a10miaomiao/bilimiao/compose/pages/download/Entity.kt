package cn.a10miaomiao.bilimiao.compose.pages.download

enum class DownloadType {
    VIDEO,
    BANGUMI
}
data class DownloadInfo(
    val dir_path: String,
    val media_type: Int,
    val has_dash_audio: Boolean,
    var is_completed: Boolean,
    val total_bytes: Long,
    val downloaded_bytes: Long,
    val title: String,
    val cover: String,
    val id: Long,
    val cid: Long,
    val type: DownloadType,
    val items: MutableList<DownloadItemInfo>,
//    val owner_id: Long,
)

data class DownloadItemInfo(
    val dir_path: String,
    val media_type: Int,
    val has_dash_audio: Boolean,
    val is_completed: Boolean,
    val total_bytes: Long,
    val downloaded_bytes: Long,
    val title: String,
    val cover: String,
    val id: Long,
    val type: DownloadType,
    val index_title: String,
    val cid: Long,
    val epid: Long,
    val page: Int = 0, // 分P序号（多P 下载时是第几P）
    /** 合集内序号（0 起；合集下载才有；番剧用 ep.sort_index）。老的 entry.json 没有 = null */
    val seasonIndex: Int? = null,
)