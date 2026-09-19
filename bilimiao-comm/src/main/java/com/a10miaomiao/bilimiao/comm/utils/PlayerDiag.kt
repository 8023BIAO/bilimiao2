package com.a10miaomiao.bilimiao.comm.utils

import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * **播放器诊断日志**（文件版）。
 *
 * 为什么需要它（2026-09-19 用户要求）：
 *  - 这台 ROM 把 App 的 logcat 全掐了，`miaoLogger().debug` 在 release 包里又是静默的
 *    （`BuildConfig.DEBUG == false` 直接 return）→ 装机版上"到底走了哪条取流路径、
 *    缓冲参数是多少、内存涨到多少"**完全看不见**；
 *  - 之前"番剧 DASH 会 OOM、MP4 只有 720P"这类问题只能靠猜，猜错一次就白改一轮。
 *
 * 记什么（每类事件一行，写得很少）：
 *  - 每次开播：来源类型 / fnval（用户设置 vs 实际生效）/ 清晰度 / 走的哪条路径
 *    （gRPC / HTTP / 代理）/ 建出来的源类型（MPD / merging / concatenating / 直链）；
 *  - LoadControl 的缓冲参数（含 64MB 堆内上限）；
 *  - 播放中每约 30 秒一条**堆内存**曲线（治 OOM 取证用）。
 *
 * 路径：`/sdcard/Android/data/<包名>/files/player_diag.log`（debug 包是 `…​.mod.dev`）。
 * 超过 400KB 自动清空，不会无限长大。
 */
object PlayerDiag {

    private const val MAX_BYTES = 400 * 1024L
    private const val FILE_NAME = "player_diag.log"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val file: File? by lazy {
        runCatching {
            BilimiaoCommApp.commApp.app.getExternalFilesDir(null)?.resolve(FILE_NAME)
        }.getOrNull()
    }

    fun log(tag: String, msg: String) {
        val f = file ?: return
        scope.launch {
            runCatching {
                f.parentFile?.mkdirs()
                if (f.exists() && f.length() > MAX_BYTES) {
                    f.writeText("")
                }
                f.appendText("${System.currentTimeMillis()} [$tag] $msg\n")
            }
        }
    }

    /** 记一行堆内存（MB）：治 OOM 时看这条曲线就知道是"一直涨"还是"某一刻炸" */
    fun memory(tag: String) {
        runCatching {
            val rt = Runtime.getRuntime()
            val used = (rt.totalMemory() - rt.freeMemory()) / 1048576L
            val max = rt.maxMemory() / 1048576L
            log("mem:$tag", "堆已用 ${used}MB / 上限 ${max}MB")
        }
    }
}
