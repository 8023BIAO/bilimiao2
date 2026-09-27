package com.a10miaomiao.bilimiao.comm.live.entity

import kotlinx.serialization.Serializable

/**
 * 直播播放地址实体（第一阶段骨架）。
 *
 * 接口：`GET xlive/web-room/v2/index/getRoomPlayInfo`
 * ★实测（2026-09-25，room 7734200）：该接口**完全不需要鉴权** ——
 *   无 Cookie / 无 UA / 无 Referer 的裸请求也返回 code=0 且带完整流地址，
 *   也不需要 WBI 签名。但**未登录时 `current_qn` 恒为 250(超清)**，
 *   传 qn=10000(原画) 也只回 250 —— 想要原画必须有登录态。
 *
 * 返回体的嵌套关系（每一层都可能整段缺失，所以全部给默认值 + 可空）：
 * ```
 * data.playurl_info?.playurl
 *   ├─ g_qn_desc[]                    清晰度全表（房间"支持"哪些）
 *   └─ stream[]                       多协议
 *       └─ format[]                   多封装
 *           └─ codec[]                多编码
 *               ├─ accept_qn[]        房间支持的可选清晰度
 *               ├─ current_qn         本次**实际**下发的清晰度
 *               └─ url_info[]         多 CDN 线路（host + extra）
 * ```
 */

/** getRoomPlayInfo 的 data（只声明直播播放需要的字段，其余靠 ignoreUnknownKeys 丢弃）。 */
@Serializable
data class LivePlayUrlInfo(
    val room_id: Long = 0,
    val live_status: Int = 0,
    /** ★轮播房/未开播时实测为 null，调用方必须判空 */
    val playurl_info: LivePlayUrlDetail? = null,
    /**
     * 是否**加密房**（需要房间密码）。
     * ★2026-09-26 新增（纯加法）：这两个字段一直躺在响应里，只是旧实体没声明、
     *   被 `ignoreUnknownKeys` 丢掉了。判"为什么拿不到流"时必须能区分
     *   "没开播"与"这是密码/付费房" —— 后者官方页面能看、第三方拿不到流，
     *   笼统报"未开播"正是用户在反馈里追问的"是权限不足吗"。
     *   （`room_init` 的实体 [LiveRoomInitInfo] 里本来就有同名的这两个字段，语义一致。）
     */
    val encrypted: Boolean = false,
    /** 密码是否已校验通过（`encrypted=false` 时无意义）。 */
    val pwd_verified: Boolean = false,
)

@Serializable
data class LivePlayUrlDetail(
    val playurl: LivePlayUrl? = null,
    /** 形如 {"cdn_rate":10000,"report_interval_sec":150} */
    val conf_json: String = "",
)

@Serializable
data class LivePlayUrl(
    val cid: Long = 0,
    /** 清晰度全表：qn + desc */
    val g_qn_desc: List<LiveQualityDesc> = emptyList(),
    val stream: List<LiveStream> = emptyList(),
)

/**
 * 清晰度描述。
 * ★实测全表（room 7734200）：30000 杜比 / 20000 4K / 15000 2K / 10000 原画 /
 *   400 蓝光 / 250 超清 / 150 高清 / 80 流畅。
 * 注意这张表是**房间能力**，不是你实际能拿到的画质 —— 见 [LiveCodec.current_qn]。
 */
@Serializable
data class LiveQualityDesc(
    val qn: Int = 0,
    val desc: String = "",
)

/** stream：协议维度。★实测 protocol_name 取值：http_stream(FLV) / http_hls(HLS)。 */
@Serializable
data class LiveStream(
    val protocol_name: String = "",
    val format: List<LiveFormat> = emptyList(),
)

/** format：封装维度。★实测 format_name 取值：flv / ts / fmp4。 */
@Serializable
data class LiveFormat(
    val format_name: String = "",
    val codec: List<LiveCodec> = emptyList(),
)

/**
 * codec：编码维度，也是真正挂 URL 的那一层。
 *
 * ★实测（room 7734200）同一 codec 下：
 * - `accept_qn = [10000, 400, 250, 150]`（房间支持）
 * - 未登录时 `current_qn = 250`（**实际只给超清**）
 * 所以画质菜单应该用 accept_qn 渲染候选项、用 current_qn 显示"当前实际画质"，
 * 否则用户会以为是我们播放器画质差。
 */
@Serializable
data class LiveCodec(
    /** ★实测取值：avc / hevc。hevc 部分设备解码不支持，选流时应 avc 优先。 */
    val codec_name: String = "",
    /** 本次实际下发的清晰度 */
    val current_qn: Int = 0,
    /** 房间支持的可选清晰度列表 */
    val accept_qn: List<Int> = emptyList(),
    /** ★形如 `/live-bvc/909339/live_50329118_9516950_2500.flv?` —— 注意自带结尾 '?' */
    val base_url: String = "",
    val url_info: List<LiveUrlInfo> = emptyList(),
)

/** url_info：CDN 线路维度。★实测同一 codec 下有 2 条（如 gotcha07 / gotcha07b）。 */
@Serializable
data class LiveUrlInfo(
    /** 形如 https://d1--cn-gotcha07.bilivideo.com */
    val host: String = "",
    /** 形如 expires=1790354290&len=0&oi=0x…&pt=web&qn=250&trid=…&sign=… */
    val extra: String = "",
)

/**
 * 拼出这个 codec 下**所有**可用的完整播放地址（= 线路候选列表）。
 *
 * 拼装规则：`host + base_url + extra`，纯字符串相加。
 *
 * ★为什么不做 URL encode / 不"整理"参数：
 *   - `base_url` 自带结尾 `?`，encode 会把它变成 `%3F` 直接 404；
 *   - `extra` 里带 `sign` / `oi` / `expires`，是服务端签发的防盗链凭据，
 *     任何字符改动都会让签名失效。
 *
 * ★有效期：实测 `extra.expires` ≈ 取流时刻 + 3590 秒（**约 1 小时**）。
 *   所以这份地址不能长期缓存，过期后必须重新调 getRoomPlayInfo。
 */
val LiveCodec.fullUrls: List<String>
    get() = url_info.mapNotNull { info ->
        val host = info.host.trim()
        val base = base_url.trim()
        if (host.isBlank() || base.isBlank()) return@mapNotNull null
        host + base + info.extra
    }.distinct()
