package com.a10miaomiao.bilimiao.comm.entity.message

import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一条"系统通知"（消息中心 → 系统通知）。
 *
 * 为什么没有直接复用 [MessageResponseInfo]/[MessageCursorInfo]：
 * 系统通知的接口**没有顶层 cursor 对象**，`data` 直接就是数组，翻页游标藏在**每条自己的 cursor 字段里**
 * （PiliPlus 的 handleListResponse 就是"取上一页最后一条的 cursor"当下一页的游标）。
 *
 * 字段来源：PiliPlus `lib/models_new/msg/msg_sys/data.dart` 的 MsgSysItem ——
 * 那边的字段本身就是全可空的（`int? id` / `String? content` / `String? time_at`），所以这里也**全部给默认值**：
 * kotlinx.serialization 遇到"显式 null"时不会去救一个非空属性（MiaoJson 没开 coerceInputValues），
 * 字段一旦少一个或为 null，整页就会解析失败 —— 系统通知这种纯展示的页面不值得为字段完整性赌一把。
 *
 * 状态：接口地址/参数已在 PiliPlus 里确认（见 MessageAPI.sysNotify），
 *      但返回体的细节（尤其是 [time_at] 的类型）**待实机验证**，所以这里做了兜底而不是硬转型。
 */
@Serializable
data class SystemMessageInfo(
    /**
     * 通知 id（暂不参与展示，留作列表去重/后续"删除单条"用）。
     */
    val id: Long? = null,

    /**
     * 翻页游标：请求下一页时把它作为 cursor 参数传回去。
     */
    val cursor: Long? = null,

    /**
     * 标题（通知的"一句话结论"，如"你的视频过审了"）。
     */
    val title: String? = null,

    /**
     * 正文。注意服务端有时给的是**一段 JSON 字符串**（形如 {"web":"真正的正文"}），
     * 取值请用 [plainContent]，不要直接用这个字段。
     */
    val content: String? = null,

    /**
     * 时间。PiliPlus 按 String 读（`json['time_at'] as String?`），说明服务端多半直接给可显示文案；
     * 但为了不因为"某天变成时间戳数字"而整页解析失败，这里用 JsonPrimitive 兜底：
     * 字符串、数字都能收下，展示策略交给 UI（见 SystemMessageContent 的 formatSysMsgTime）。
     */
    @SerialName("time_at")
    val time_at: JsonPrimitive? = null,
) {

    /**
     * 可直接显示的正文：content 是 {"web":"..."} 这类 JSON 时取里面的 web 文本，否则原样返回。
     *
     * PiliPlus 在 fromJson 里做的是同一件事（jsonDecode 后取 json['web']），
     * 只是它解析失败时静默保留原文 —— 这里也保持一致，绝不因为"正文格式怪"就丢内容。
     */
    val plainContent: String
        get() {
            val raw = content.orEmpty()
            val trimmed = raw.trim()
            // 只有"看起来像 JSON 对象"才值得解析，普通文案直接返回，省一次解析
            if (!trimmed.startsWith("{")) return raw
            return try {
                val obj = MiaoJson.kotlinJson.parseToJsonElement(trimmed) as? JsonObject
                (obj?.get("web") as? JsonPrimitive)?.contentOrNull ?: raw
            } catch (e: Exception) {
                raw
            }
        }

    /**
     * 原始时间文本（不做任何格式化）。UI 里再决定显示成相对时间还是绝对时间。
     */
    val rawTimeText: String
        get() = time_at?.contentOrNull.orEmpty()
}
