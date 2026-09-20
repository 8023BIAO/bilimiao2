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
    ) {
        fun timeText(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
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
        )
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().clear().apply() }
    }
}
