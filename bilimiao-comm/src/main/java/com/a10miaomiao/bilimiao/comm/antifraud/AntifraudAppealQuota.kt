package com.a10miaomiao.bilimiao.comm.antifraud

import android.content.Context

/**
 * 申诉额度 / 去重记账（纯本地）。
 *
 * 为什么需要：
 *   · 官方 H5 写得很清楚——「24小时内仅可提交3条申诉」，服务端超限回 `56601`；
 *     同一条内容重复提交回 `56602`。全自动反诈是**背着你发请求**的，
 *     不记账的话三次额度会被同一条评论连点三次白白烧掉。
 *   · 所以本地记 `(时间, 目标)` 流水：24 小时窗口内数一遍就是"已用几次"，
 *     同一个目标 24 小时内已经申诉过就直接不发了（等系统通知里的处理结果）。
 *
 * 只存在本机 SharedPreferences，不上传任何东西；清空检测记录时可一并清掉。
 */
object AntifraudAppealQuota {

    private const val SP = "antifraud_appeal_quota"
    private const val KEY = "log"

    /** 官方窗口：24 小时 */
    const val WINDOW_MS = 24 * 60 * 60 * 1000L

    /** 只做去重标记、不算额度的流水（服务端 56602 那种"已经申诉过"） */
    private const val KEY_DEDUP = "dedup"

    /** 流水最多留这么多条（防止 SharedPreferences 无限涨） */
    private const val MAX_ENTRIES = 40

    data class Entry(val time: Long, val key: String)

    /** 服务端硬上限（与官方 H5 一致）：24 小时内 3 条 */
    val dailyLimit: Int get() = com.a10miaomiao.bilimiao.comm.apis.CommentAppeal.DAILY_LIMIT

    private fun raw(context: Context): String = runCatching {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    }.getOrDefault("")

    private fun dedupEntries(context: Context): List<Entry> = runCatching {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY_DEDUP, "").orEmpty()
    }.getOrDefault("")
        .lineSequence()
        .mapNotNull { line ->
            val i = line.indexOf('|')
            if (i <= 0) return@mapNotNull null
            val t = line.substring(0, i).toLongOrNull() ?: return@mapNotNull null
            Entry(t, line.substring(i + 1))
        }
        .toList()

    fun entries(context: Context): List<Entry> = raw(context)
        .lineSequence()
        .mapNotNull { line ->
            val i = line.indexOf('|')
            if (i <= 0) return@mapNotNull null
            val t = line.substring(0, i).toLongOrNull() ?: return@mapNotNull null
            Entry(t, line.substring(i + 1))
        }
        .toList()

    /** 24 小时窗口内已经提交过几次 */
    fun usedCount(context: Context, now: Long = System.currentTimeMillis()): Int =
        entries(context).count { now - it.time in 0 until WINDOW_MS }

    /** 还剩几次（本地记账口径；实际以服务端为准） */
    fun remaining(context: Context, now: Long = System.currentTimeMillis()): Int =
        (dailyLimit - usedCount(context, now)).coerceAtLeast(0)

    /** 这个目标最近 24 小时内是不是已经申诉过（同一目标重复提交没意义，服务端回 56602） */
    fun isDuplicate(context: Context, key: String, now: Long = System.currentTimeMillis()): Boolean {
        if (key.isBlank()) return false
        val inWindow = { e: Entry -> e.key == key && now - e.time in 0 until WINDOW_MS }
        return entries(context).any(inWindow) || dedupEntries(context).any(inWindow)
    }

    /**
     * 只记"这条已经申诉过"（服务端回 56602 时用），**不占额度**。
     *
     * 为什么分开（review 抓到）：56602 = "您已提交过该申诉"，那次请求并没有真的消耗次数；
     * 混进额度流水的话，清空记录后连打三次 56602 就会让本机误以为"3 次用完了"。
     */
    fun recordDedupOnly(context: Context, key: String, now: Long = System.currentTimeMillis()) {
        runCatching {
            val kept = dedupEntries(context)
                .filter { now - it.time in 0 until WINDOW_MS }
                .takeLast(MAX_ENTRIES - 1)
            val text = (kept + Entry(now, normalize(key)))
                .joinToString("\n") { "${it.time}|${it.key}" }
            context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putString(KEY_DEDUP, text)
                .apply()
        }
    }

    /** 记一笔（成功提交后调用） */
    fun record(context: Context, key: String, now: Long = System.currentTimeMillis()) {
        runCatching {
            val kept = entries(context)
                .filter { now - it.time in 0 until WINDOW_MS }
                .takeLast(MAX_ENTRIES - 1)
            val text = (kept + Entry(now, normalize(key)))
                .joinToString("\n") { "${it.time}|${it.key}" }
            context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putString(KEY, text)
                .apply()
        }
    }

    /** 服务端额度用尽时塞进流水里的占位键 */
    const val SERVER_LIMIT_KEY = "__server_limit__"

    /**
     * 服务端回 56601（"申诉提交已达当日上限"）时调用：把本地流水直接记满。
     *
     * 为什么：官方额度是全端共享的（H5、App、我们这里都算），本地流水看不到别人/别处的消耗；
     * 不记的话，之后每条被判限流的评论都会再发一次注定 56601 的请求，用户还每次都被弹一次窗。
     */
    fun markServerExhausted(context: Context, now: Long = System.currentTimeMillis()) {
        runCatching {
            val kept = entries(context)
                .filter { now - it.time in 0 until WINDOW_MS && it.key != SERVER_LIMIT_KEY }
                .takeLast((MAX_ENTRIES - dailyLimit).coerceAtLeast(0))
            val filler = (0 until dailyLimit).map { Entry(now, SERVER_LIMIT_KEY) }
            val text = (kept + filler).joinToString("\n") { "${it.time}|${it.key}" }
            context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putString(KEY, text)
                .apply()
        }
    }

    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    /** 目标归一化：去掉换行/竖线，免得把流水格式撑坏 */
    fun normalize(key: String): String = key.replace('\n', ' ').replace('|', '/').trim()

    /**
     * 申诉目标的去重键：评论申诉按 BV号/链接，图文动态按 link。
     * 带上类型前缀，避免"BV号"和"同名动态"撞键。
     */
    fun keyOf(kind: String, target: String): String = normalize("$kind:$target").lowercase()
}
