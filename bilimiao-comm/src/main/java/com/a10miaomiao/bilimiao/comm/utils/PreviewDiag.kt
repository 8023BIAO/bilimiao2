package com.a10miaomiao.bilimiao.comm.utils

import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import java.io.File

/**
 * 「拖动进度条预览图」临时诊断日志 —— 只为定位"拖动没有预览图"用。
 *
 * 写到 `/sdcard/Android/data/<包名>/files/preview_diag.log`，每行带时间戳。
 * 定位完把 [enabled] 改成 false（或删掉本文件与各调用点）即可，不影响正式功能。
 */
object PreviewDiag {

    /** 排查开关：问题定位后置 false 停写 */
    @Volatile
    var enabled: Boolean = false   // 预览图问题已定位（2026-09-20），先关掉；要排查再打开

    private val logFile: File? by lazy {
        try {
            val ctx = BilimiaoCommApp.commApp.app
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            dir.mkdirs()
            File(dir, "preview_diag.log").also {
                // 每次进程启动清空，避免混进上次的
                runCatching { it.writeText("") }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun log(msg: String) {
        if (!enabled) return
        try {
            logFile?.appendText("${System.currentTimeMillis()} $msg\n")
        } catch (_: Exception) {
        }
    }
}
