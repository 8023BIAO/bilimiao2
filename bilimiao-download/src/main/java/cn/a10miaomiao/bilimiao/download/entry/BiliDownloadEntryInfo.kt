package cn.a10miaomiao.bilimiao.download.entry

import kotlinx.serialization.Serializable

@Serializable
data class BiliDownloadEntryInfo(
    val media_type: Int = 1,
    val has_dash_audio: Boolean = false,
    var is_completed: Boolean,
    var total_bytes: Long,
    var downloaded_bytes: Long,
    val title: String,
    val type_tag: String? = null,
    val cover: String,
    val video_quality: Int? = null,
    val prefered_video_quality: Int,
    val quality_pithy_description: String = "",
    val guessed_total_bytes: Int,
    var total_time_milli: Long,
    val danmaku_count: Int,
    val time_update_stamp: Long = 0L,
    val time_create_stamp: Long = 0L,
    val can_play_in_advance: Boolean = false,
    var interrupt_transform_temp_file: Boolean = false,
    val avid: Long? = null,
    val spid: Long? = null,
    val bvid: String? = null,
    val owner_id: Long? = null,
    var page_data: PageInfo? = null,
    val season_id: String? = null,
    val source: SourceInfo? = null,
    var ep: EpInfo? = null,
    var isSilent: Boolean = false, // 静默下载，不弹通知栏
) {

    val key: Long
        get() {
            return source?.cid ?: page_data?.cid ?: 0
        }

    /**
     * **这一条该显示什么名字** —— 下载列表/详情页/通知栏统一走它。
     *
     * 为什么需要它：B 站接口的 `pages[].part` 是**上传者填的分P标题**，很多 UP 主压根没改，
     * 服务端就把原始上传文件名回给我们（实测两种形态都出现过：`lv_0_20260907165529` 这种
     * 带前缀的，和 `1790770453216` 这种纯数字的）。多P 视频里 `part` 是有信息量的分P名，
     * 但单P/合集场景下它就是垃圾名 ⇒ 取名字必须按"这条是从哪下的"来分：
     *   · 番剧（ep 有值）→ 集数 + 长标题（保持原行为）
     *   · 有 [PageInfo.display_title]（2026-10-02 起新下载会写）→ 直接用它（下载时就定好的真名）
     *   · 否则回落 `part` → `download_subtitle` → `title`（老条目不崩、不显示 unknown）
     */
    val showTitle: String
        get() {
            val e = ep
            if (e != null) {
                return e.index + e.index_title
            }
            val p = page_data
            if (p != null) {
                p.display_title?.takeIf { it.isNotBlank() }?.let { return it }
                p.part?.takeIf { it.isNotBlank() }?.let { return it }
                p.download_subtitle?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return title
        }

    /** 合集中的序号（0 起；仅合集下载写。老的 entry.json 没有 = null） */
    val seasonIndex: Int?
        get() = page_data?.season_index ?: ep?.sort_index

    /**
     * 通知栏标题。**新下载**（有 display_title）直接用真名；
     * **老条目/番剧保持原来的拼接方式**（`title + part` / `title + index_title`）——
     * 老条目的 part 常常是上传文件名垃圾，只留它会把真标题弄丢。
     */
    val name: String
        get() {
            val p = page_data
            if (p?.display_title?.isNotBlank() == true) return showTitle
            val e = ep
            if (e != null) return title + e.index_title
            if (p != null) return title + p.part
            return title
        }

    val videoDirName: String
        get() = type_tag ?: video_quality.toString()

    // 视频分P信息
    @Serializable
    data class PageInfo(
        val cid: Long,
        val page: Int,
        val from: String? = null,
        val part: String? = null,
        val vid: String? = null,
        val has_alias: Boolean,
        val tid: Int,
        val width: Int = 0,
        val height: Int = 0,
        val rotate: Int = 0,
        val download_title: String? = null,
        val download_subtitle: String? = null,
        /**
         * **这一条要显示的真名**（2026-10-02 起写入；老 entry.json 没有 = null，走 [showTitle] 的回落链）。
         * 写入规则：合集 → 剧集真名；多P → 该分P名；单P → 视频真标题。
         */
        val display_title: String? = null,
        /** 合集内序号（0 起）。老 entry.json 没有 = null */
        val season_index: Int? = null,
    )

    // 番剧源信息
    @Serializable
    data class SourceInfo(
        val av_id: Long,
        val cid: Long,
//        val website: String,
    )

    // 番剧剧集信息
    @Serializable
    data class EpInfo(
        val av_id: Long,
        val page: Int,
        val danmaku: Long,
        val cover: String,
        val episode_id: Long,
        val index: String,
        val index_title: String,
        val from: String,
        val season_type: Int,
        val width: Int,
        val height: Int,
        val rotate: Int,
        val link: String = "",
        val bvid: String = "",
        val sort_index: Int = 0,
    )

}