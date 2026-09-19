package com.a10miaomiao.bilimiao.comm.utils

import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 「分段并发下载」（原「线程撕裂者」）的**文件诊断日志**（和 [SponsorDiag] 同一套路，独立一个文件）。
 *
 * 为什么要它：这台 ROM 把 App 的 logcat 全掐了，而 `miaoLogger().debug` 在
 * **release 包里是静默的**（`BuildConfig.DEBUG` 为 false 直接 return）—— 也就是说
 * "开关开了到底有没有生效、是不是一直在回退单连接" 这类问题在装机版上**完全看不到**。
 * 所以关键事件写文件：并发启动 / 分块重试 / 回退单连接 / 熔断。
 *
 * 写得很少（每类事件一行），文件超过 200KB 自动清空。
 * 路径：`/sdcard/Android/data/<包名>/files/ripper_diag.log`（debug 包是 `.mod.dev`）。
 */
object RipperDiag {

    private const val MAX_BYTES = 200 * 1024L
    private const val FILE_NAME = "ripper_diag.log"

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
}
