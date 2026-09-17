package com.a10miaomiao.bilimiao.comm.entity.sponsor

import kotlinx.serialization.Serializable

/**
 * 「小电视空降助手」（BilibiliSponsorBlock）的一个片段。
 *
 * 服务端：`https://www.bsbsb.top`，接口 `GET /api/skipSegments[/:hashPrefix]`。
 * 字段名与线上返回体一字不差（含大写 `UUID`），别改成驼峰 —— 反序列化会拿不到值。
 *
 * ⚠️ 实测坑（见 /root/test/sponsorblock-server-api.md）：
 *  - `segment` 单位是**秒**且是浮点（如 30.015）；
 *  - `UUID` 实测是 65 位十六进制、**不是**标准 UUID，当不透明字符串用；
 *  - 查不到数据服务端返回 `200 []`（不是 404），所以空列表是正常结果。
 */
@Serializable
data class SponsorSegment(
    val UUID: String = "",
    val category: String = "",
    val actionType: String = "skip",
    /** [起, 止]，单位秒 */
    val segment: List<Double> = emptyList(),
    /** 该片段所属分P 的 cid（服务端给的是字符串） */
    val cid: String = "",
    /** 裸 BVID（哈希隐私端点会一次返回多个视频的片段，靠它区分） */
    val videoID: String = "",
    /**
     * 提交时的视频时长（秒），0 = 未知。
     * ⚠️ 服务端给的是**小数**（实测 `1244.078`）—— 声明成 Int 会让整个 JSON 解析失败，
     *    进而"彩色条/片段列表/提交"全部消失（踩过，靠 /sdcard 诊断日志才定位到）。
     */
    val videoDuration: Double = 0.0,
    /** 是否被锁定（锁定后普通用户不能投票修改）；服务端也是小数，用 `>= 1.0` 判断 */
    val locked: Double = 0.0,
    val votes: Double = 0.0,
    val description: String = "",
) {
    val startMs: Long get() = ((segment.getOrNull(0) ?: 0.0) * 1000).toLong()
    val endMs: Long get() = ((segment.getOrNull(1) ?: 0.0) * 1000).toLong()

    /**
     * 这条片段要不要留下。
     *
     * 正常片段要求"终点在起点之后"；**零宽片段**（起止相同，如 `[0,0]`）只在它是
     * 服务端的**整片标记**（`actionType = full`，PiliPlus 会把它显示成视频标签）时才留下 ——
     * 其它零宽片段没有任何意义，而且放进跳过引擎会导致"跳到原地"。
     */
    val isValid: Boolean get() = segment.size >= 2 && (endMs > startMs || isPointLabel)

    /** 起止相同（服务端用它表示"整片标记"，如 [0,0]；也可能是 poi 这种纯标记） */
    val isPoint: Boolean get() = segment.size >= 2 && startMs == endMs

    /** 是"整片标记"这类纯标记片段（不参与跳过，只用来提示） */
    private val isPointLabel: Boolean
        get() = isPoint && (actionType == "full" || actionType == "poi")

    /** 类别对象（拿中文名和颜色） */
    val categoryInfo: SponsorCategory? get() = SponsorCategory.of(category)

    /** 展示用颜色（用户没自定义时就是类别默认色） */
    fun color(colorOf: (String) -> Int = { SponsorCategory.colorOf(it) }): Int = colorOf(category)

    /**
     * 这个片段该用什么策略 —— 完整复刻 PiliPlus `segment_model.dart` 的 `fromItemModel`：
     *  1. 先取该类别在设置里的策略；
     *  2. **只有在策略确实要跳（手动/跳一次/总是跳）时**，才过一遍降级规则：
     *     整片标记（起止相同）或长度 < blockLimit 的片段降级成"仅显示"（只画色块、不跳）。
     *
     * ⚠️ 降级只对"要跳"的策略生效：`Disable` 是"连色块都不画"，绝不能把它降级成
     * `ShowOnly`（那会让用户明明关掉/禁用的类别又在进度条上冒出整片标记色块）。
     */
    fun skipTypeOf(
        settings: Map<String, SponsorSkipType>,
        limitSec: Int,
    ): SponsorSkipType {
        val configured = settings[category] ?: SponsorSkipType.Disable
        val wantsSkip = configured == SponsorSkipType.SkipManually ||
            configured == SponsorSkipType.SkipOnce ||
            configured == SponsorSkipType.AlwaysSkip
        if (wantsSkip && (isPoint || (limitSec > 0 && (endMs - startMs) < limitSec * 1000L))) {
            return SponsorSkipType.ShowOnly
        }
        return configured
    }
}

/**
 * 哈希隐私端点 `GET /api/skipSegments/{prefix}` 的返回元素：**按视频分组**。
 *
 * ⚠️ 这与扁平端点 `GET /api/skipSegments?videoID=&cid=` 的形状不同（那个直接给 `[SponsorSegment]`），
 * 实测确认过：哈希端点给 `[{"videoID":"BV...","segments":[...]}]`，且**不接受任何 query 参数**
 * （多传 `cid` 直接 `400 Unknown query parameter.`）→ 只能本地按 videoID/cid 过滤。
 */
@Serializable
data class SponsorSegmentGroup(
    val videoID: String = "",
    val segments: List<SponsorSegment> = emptyList(),
)

/** `GET /api/userInfo` 的统计（"已为你节省 X 分钟"那一栏） */
@Serializable
data class SponsorUserInfo(
    val viewCount: Long = 0,
    val minutesSaved: Double = 0.0,
    val segmentCount: Long = 0,
)

/**
 * 每个类别的处理策略（PiliPlus 的 `SkipType`，5 档）。
 *
 * 顺序即 DataStore 里存的 ordinal，**不要调整顺序**（改了会让老设置的档位错位）。
 */
enum class SponsorSkipType(val label: String) {
    /** 完全不管（连色块都不画） */
    Disable("禁用"),
    /** 只画色块提示，不跳（也是"太短的片段"的降级档） */
    ShowOnly("仅显示"),
    /** 到点时弹一个"跳过"按钮，用户点了才跳（4 秒后自动消失） */
    SkipManually("手动跳过"),
    /** 自动跳，但同一个片段只跳一次（拖回去不再弹）—— 默认档 */
    SkipOnce("跳过一次"),
    /** 每次经过都跳 */
    AlwaysSkip("总是跳过"),
    ;

    companion object {
        fun of(ordinal: Int): SponsorSkipType = entries.getOrElse(ordinal) { Disable }
    }
}

/** 片段被标记的行为类型（服务端 `actionType`） */
enum class SponsorActionType(val id: String, val label: String) {
    Skip("skip", "跳过"),
    Mute("mute", "静音"),
    Full("full", "整个视频"),
    Poi("poi", "精彩时刻"),
    ;

    companion object {
        fun of(id: String): SponsorActionType = entries.firstOrNull { it.id == id } ?: Skip
    }
}

/**
 * 服务端的 category 枚举（11 个）。
 *
 * 文案与颜色都对齐 PiliPlus `lib/models/common/sponsor_block/segment_type.dart`；
 * `id` 是服务端取值，**必须一字不差**（严格白名单）。
 */
enum class SponsorCategory(
    val id: String,
    /** 完整名（设置页、片段列表用） */
    val label: String,
    /** 短名（跳过的 toast 用，对齐 PiliPlus 的 shortTitle） */
    val shortLabel: String,
    val color: Int,
) {
    Sponsor("sponsor", "赞助/恰饭", "赞助", 0xFF00D400.toInt()),
    SelfPromo("selfpromo", "无偿/自我推广", "推广", 0xFFFFFF00.toInt()),
    ExclusiveAccess("exclusive_access", "独家访问/品牌合作", "品牌合作", 0xFF008A5C.toInt()),
    Interaction("interaction", "三连/互动提醒", "三连提醒", 0xFFCC00FF.toInt()),
    PoiHighlight("poi_highlight", "精彩时刻", "精彩时刻", 0xFFFF1684.toInt()),
    Intro("intro", "开场动画", "开场动画", 0xFF00FFFF.toInt()),
    Outro("outro", "片尾/鸣谢", "片尾", 0xFF0202ED.toInt()),
    Preview("preview", "回顾/概要", "预览", 0xFF008FD6.toInt()),
    Padding("padding", "填充内容/前黑后黑", "填充内容", 0xFF222222.toInt()),
    Filler("filler", "离题闲聊", "离题", 0xFF7300FF.toInt()),
    MusicOfftopic("music_offtopic", "音乐:非音乐部分", "非音乐", 0xFFFF9900.toInt()),
    ;

    companion object {
        /** 未知类别/找不到时的兜底色（灰色） */
        const val UNKNOWN_COLOR = 0xFF9E9E9E.toInt()

        fun of(id: String): SponsorCategory? = entries.firstOrNull { it.id == id }
        fun labelOf(id: String): String = of(id)?.label ?: id
        fun shortLabelOf(id: String): String = of(id)?.shortLabel ?: id
        fun colorOf(id: String): Int = of(id)?.color ?: UNKNOWN_COLOR

        /**
         * 各类别的默认策略。
         *
         * **对齐 PiliPlus**：`storage_pref.dart:120-135` 在没存过设置时把 11 个类别
         * 全部初始化为 `SkipType.skipOnce`（跳过一次）。所以打开总开关后，默认是所有类别都跳一次，
         * 而不是只跳"赞助/恰饭" —— 想少跳就去设置页逐类改成"禁用/仅显示"。
         *
         * 番剧的片头/片尾走 intro/outro 两个类别，因此默认也会跳（PiliPlus 的 pgcSkipType
         * 默认值同样是 skipOnce）。
         */
        val DEFAULT_SKIP_TYPES: Map<String, SponsorSkipType> =
            entries.associate { it.id to SponsorSkipType.SkipOnce }
    }
}
