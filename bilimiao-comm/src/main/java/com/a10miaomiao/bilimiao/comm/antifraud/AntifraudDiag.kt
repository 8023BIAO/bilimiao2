package com.a10miaomiao.bilimiao.comm.antifraud

import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.utils.ErrorLogCollector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 评论反诈的诊断日志。
 *
 * 为什么需要它：反诈判定全靠"几个接口的返回码组合"，出问题（比如明明只有自己看得见却判成"正常"）
 * 时**光看结果是没法定位的** —— 必须知道每一步的真实返回码。release 版 logcat 全被剥掉了，
 * 所以这里落到两处，任选一处拿：
 *
 *  1. **文件**：`/sdcard/Android/data/<包名>/files/antifraud_diag.log`
 *     （adb 可直接 pull，每次进程启动清空）
 *  2. **App 内**：同时塞一条精简版进「错误日志」页（实验性功能 → 错误日志），
 *     那儿有复制按钮，改完随手就能贴出来
 *
 * 敏感信息只记 **Cookie 的名字**，绝不记值。
 */
object AntifraudDiag {

    /** 排查开关：定位完置 false 停写 */
    @Volatile
    var enabled: Boolean = true

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val logFile: File? by lazy {
        try {
            val ctx = BilimiaoCommApp.commApp.app
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            dir.mkdirs()
            File(dir, "antifraud_diag.log").also {
                // 每次进程启动清空，避免和上一次的混在一起
                runCatching { it.writeText("") }
            }
        } catch (e: Exception) {
            null
        }
    }

    private var sessionStart = 0L
    private var stepNo = 0

    /** 一次检测开始：写分隔线，重置步骤号 */
    fun start(title: String) {
        if (!enabled) return
        sessionStart = System.currentTimeMillis()
        stepNo = 0
        write("")
        write("========== $title ==========")
    }

    /** 记一步 */
    fun step(msg: String) {
        if (!enabled) return
        stepNo++
        write("[${stepNo}] $msg   (+${System.currentTimeMillis() - sessionStart}ms)")
    }

    /** 记一条补充信息（不占步骤号） */
    fun info(msg: String) {
        if (!enabled) return
        write("    · $msg")
    }

    /** 收尾：整段同时进「错误日志」页，方便在手机上直接看/复制 */
    fun finish(resultLine: String) {
        if (!enabled) return
        write("===== 结论：$resultLine =====")
        runCatching {
            ErrorLogCollector.logError(
                error = "[评论反诈] $resultLine",
                stackTrace = buffer.toString(),
            )
        }
        buffer.setLength(0)
    }

    private val buffer = StringBuilder()

    private fun write(line: String) {
        val stamped = "${timeFormat.format(Date())} $line"
        buffer.append(stamped).append('\n')
        runCatching { logFile?.appendText(stamped + "\n") }
    }

    /** 只回 Cookie 的名字列表（绝不回值——SESSDATA 是凭据） */
    fun cookieNames(cookie: String?): String {
        if (cookie.isNullOrBlank()) return "(无)"
        return cookie.split(";")
            .map { it.trim().substringBefore('=') }
            .filter { it.isNotBlank() }
            .joinToString(",")
    }
}
