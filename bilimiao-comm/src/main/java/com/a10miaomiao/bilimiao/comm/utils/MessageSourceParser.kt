package com.a10miaomiao.bilimiao.comm.utils

/**
 * 消息（收到的赞 / 回复我的 / @我的）里"这条消息指向哪里"。
 *
 * 为什么需要：消息详情页原来只会从 `item.uri` 里用
 * `startsWith("https://www.bilibili.com/video/")` 猜来源 —— 实测（2026-09-26，真实点赞消息）
 * 图文动态/专栏下的评论被赞时，uri 是 `https://www.bilibili.com/opus/1250769544048279552`，
 * 这个判断直接失败 → 用户看到的详情页**没有「评论来源」入口**（他反馈的"为什么不和收到回复那个一样"）。
 *
 * 权威信息其实在 `native_uri` 里：
 * ```
 * bilibili://comment/detail/{type}/{oid}/{rpid}/?subType=0&anchor=...
 * ```
 * 统一从它解析 type/oid/rpid，再按评论区类型拼出 App 认识的位置：
 *   1 视频 → `bilimiao://video/{BV}`   12 专栏 → `read/cv{oid}`
 *   17 图文动态 → `opus/{oid}`        11 动态 → `t.bilibili.com/{oid}`
 *
 * 解析不到时退回 `uri`（本身是 bilibili 链接或 bilibili:// 深链就够用了）。
 */
object MessageSourceParser {

    data class Source(
        /** 评论区类型：1 视频、12 专栏、17 图文动态、11 动态……；解析不到为 0 */
        val type: Int,
        /** 评论区 id（视频=aid、专栏=cv号、动态=动态 id）；解析不到为 0 */
        val oid: Long,
        /** 评论 id（rpid）；解析不到为 0（调用方会退回消息自带的 item_id） */
        val rpid: Long,
        /** App 里可直接导航的位置；拼不出来就是空串 */
        val enterUrl: String,
    )

    /** `bilibili://comment/detail/{type}/{oid}/{rpid}`（rpid 可能缺） */
    private val COMMENT_DETAIL = Regex("""comment/detail/(\d+)/(\d+)(?:/(\d+))?""")

    fun parse(nativeUri: String?, uri: String?): Source? {
        COMMENT_DETAIL.find(nativeUri.orEmpty())?.let { m ->
            val type = m.groupValues[1].toIntOrNull() ?: 0
            val oid = m.groupValues[2].toLongOrNull() ?: 0L
            val rpid = m.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L
            if (type > 0 && oid > 0L) {
                return Source(
                    type = type,
                    oid = oid,
                    rpid = rpid,
                    enterUrl = enterUrlOf(type, oid) ?: fallbackUrl(uri).orEmpty(),
                )
            }
        }
        return fallbackUrl(uri)?.let { Source(0, 0L, 0L, it) }
    }

    /** 按评论区类型拼 App 认识的位置；拼不出来返回 null */
    fun enterUrlOf(type: Int, oid: Long): String? {
        if (oid <= 0L) return null
        return when (type) {
            1 -> runCatching { BvUtils.toBvid(oid.toString()) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { "bilimiao://video/$it" }
            12 -> "https://www.bilibili.com/read/cv$oid"
            17 -> "https://www.bilibili.com/opus/$oid"
            11 -> "https://t.bilibili.com/$oid"
            else -> null
        }
    }

    /** 消息自带的 uri：bilibili:// 深链或 bilibili.com 链接都能被 App 的导航吃掉 */
    private fun fallbackUrl(uri: String?): String? {
        val u = uri.orEmpty().trim()
        if (u.isEmpty()) return null
        if (u.startsWith("bilibili://", ignoreCase = true)) return u
        if (u.startsWith("http", ignoreCase = true) && "bilibili.com" in u) return u
        return null
    }
}
