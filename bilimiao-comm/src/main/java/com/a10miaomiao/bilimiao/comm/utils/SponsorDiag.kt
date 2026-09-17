package com.a10miaomiao.bilimiao.comm.utils

import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 空降助手的**文件诊断日志**（放 comm 层，API 和播放器都能写）。
 *
 * 为什么写文件而不用 logcat：这台 ROM 把 App 日志全掐了（logcat 里一条都看不到），
 * 之前排查评论发图就是靠写 `files/` 下的文件。
 * 写得很少、每类事件一行，文件超过 200KB 自动清空，不会无限长大。
 *
 * ★ 路径必须走 `context.getExternalFilesDir(null)` 而不是拼 `/sdcard/Android/data/<包名>/`：
 *   包名会因为 `applicationIdSuffix`（debug 是 `…​.mod.dev`）而变，写死包名会让日志
 *   在 debug 包上**静默写不进去**——而那正是最需要它的时候。
 */
object SponsorDiag {

    private const val MAX_BYTES = 200 * 1024L
    private const val FILE_NAME = "sponsor_diag.log"

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
