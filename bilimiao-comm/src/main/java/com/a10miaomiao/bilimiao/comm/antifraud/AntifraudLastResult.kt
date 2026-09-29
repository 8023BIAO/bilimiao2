package com.a10miaomiao.bilimiao.comm.antifraud

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「上次反诈检测结果」的持久化。
 *
 * 为什么需要：自动复查要盯好几分钟，用户这时候往往会切走甚至把 App 划掉 ——
 * 协程被系统冻结/进程被杀之后，最终弹窗根本弹不出来（实测撞上：用户干等 5 分钟什么也没看到）。
 * 所以结果落一份盘：设置页里随时能看到"上次检测结果"，不再依赖那一瞬间的弹窗。
 */
object AntifraudLastResult {

    private const val SP = "antifraud_last_result"

    data class Result(
        val time: Long,
        val title: String,
        val detail: String,
        val where: String,
        val rpid: Long,
        val message: String,
        val isBad: Boolean,
        /** 复检要用：评论区 id / 类型 / 根评论 id */
        val oid: Long = 0L,
        val type: Int = 0,
        val root: Long = 0L,
        /**
         * 这条评论的发送时间（秒）。
         *
         * 手动复检时要用它做"翻到比我更早的评论就停止翻页"的早停判断（CommentAntifraud.findRootAsGuest）。
         * 0 = 不知道发送时间，此时**不能**早停 —— 传 now 会让时间序第一页立刻命中早停，
         * 正常评论被误判成"仅自己可见"。
         */
        val sentTimeSec: Long = 0L,
        /**
         * 申诉结果（自动申诉/手动申诉都会回写到这里）。
         *
         * 为什么存：全自动反诈是**静默**的（连弹窗都不弹），
         * 那这条记录就是唯一的交代 —— 设置页「上次检测结果」里能看到"已自动申诉 / 申诉失败 + 原因"。
         */
        val appealTime: Long = 0L,
        val appealOk: Boolean = false,
        val appealMessage: String = "",
        /** 申诉目标（BV号 / 位置链接 / 动态链接） */
        val appealTarget: String = "",
        /** true = 全自动反诈自己发的；false = 用户在弹窗里点的 */
        val appealAuto: Boolean = false,
    ) {
        fun timeText(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))

        fun appealTimeText(): String =
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(appealTime))

        /** 设置页展示用：没申诉过返回空串 */
        fun appealText(): String {
            if (appealTime <= 0L) return ""
            val head = if (appealOk) {
                if (appealAuto) "已自动申诉 ✓" else "已提交申诉 ✓"
            } else {
                if (appealAuto) "自动申诉失败 ✗" else "申诉失败 ✗"
            }
            return buildString {
                append(head)
                if (appealTarget.isNotBlank()) append("（").append(appealTarget).append("）")
                append("\n").append(appealTimeText())
                if (appealMessage.isNotBlank()) append("\n").append(appealMessage)
            }
        }
    }

    fun save(context: Context, r: Result) {
        runCatching {
            context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putLong("time", r.time)
                .putString("title", r.title)
                .putString("detail", r.detail)
                .putString("where", r.where)
                .putLong("rpid", r.rpid)
                .putString("message", r.message)
                .putBoolean("isBad", r.isBad)
                .putLong("oid", r.oid)
                .putInt("type", r.type)
                .putLong("root", r.root)
                .putLong("sentTimeSec", r.sentTimeSec)
                .putLong("appealTime", r.appealTime)
                .putBoolean("appealOk", r.appealOk)
                .putString("appealMessage", r.appealMessage)
                .putString("appealTarget", r.appealTarget)
                .putBoolean("appealAuto", r.appealAuto)
                .apply()
        }
    }

    fun load(context: Context): Result? = runCatching {
        val sp = context.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val time = sp.getLong("time", 0L)
        if (time <= 0L) return null
        Result(
            time = time,
            title = sp.getString("title", "") ?: "",
            detail = sp.getString("detail", "") ?: "",
            where = sp.getString("where", "") ?: "",
            rpid = sp.getLong("rpid", 0L),
            message = sp.getString("message", "") ?: "",
            isBad = sp.getBoolean("isBad", false),
            oid = sp.getLong("oid", 0L),
            type = sp.getInt("type", 0),
            root = sp.getLong("root", 0L),
            sentTimeSec = sp.getLong("sentTimeSec", 0L),
            appealTime = sp.getLong("appealTime", 0L),
            appealOk = sp.getBoolean("appealOk", false),
            appealMessage = sp.getString("appealMessage", "") ?: "",
            appealTarget = sp.getString("appealTarget", "") ?: "",
            appealAuto = sp.getBoolean("appealAuto", false),
        )
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().clear().apply() }
    }

    /**
     * 申诉结果回写：全自动申诉 / 弹窗申诉 / 设置页一键申诉，最后都调这里。
     *
     * 全自动反诈是静默的（没有弹窗），这份记录就是用户唯一的"交代" —— 设置页「上次检测结果」里
     * 会显示"已自动申诉 ✓ / 自动申诉失败 ✗ + 原因 + 时间 + 目标"。
     */
    fun markAppeal(
        context: Context,
        ok: Boolean,
        message: String,
        target: String,
        auto: Boolean,
        /** 是哪条评论的申诉结果：和盘里那条记录对不上就不写（并发 3 路时防止串台） */
        rpid: Long = 0L,
    ): Result? {
        val current = load(context) ?: return null
        // 只回写"就是这条评论"的结果：
        //   · rpid<=0 = 图文动态申诉 / 设置页理由入口，没有对应评论，别盖到上一条记录上（review 抓到）
        //   · rpid 和盘里那条对不上 = 并发 3 路串台，同样不写
        if (rpid <= 0L || current.rpid != rpid) return null
        val updated = current.copy(
            appealTime = System.currentTimeMillis(),
            appealOk = ok,
            appealMessage = message,
            appealTarget = target,
            appealAuto = auto,
        )
        save(context, updated)
        // 返回给调用方（bilimiao-compose 侧的 AntifraudResultState.set）去刷新界面状态 ——
        // 只写盘不刷状态的话，用户正停在设置页时看不到"已自动申诉 ✓"。
        return updated
    }
}
