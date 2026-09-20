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

    // SimpleDateFormat 不是线程安全的：日志会被"最多 3 路检测 + OkHttp 线程 + 主线程"同时写
    // （ErrorLogCollector 早就为同样的问题加了锁，这里是漏改）→ ThreadLocal + 全局锁
    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }
    private val lock = Any()

    /** 文件写入统一丢到这条单线程队列：调用方大多在主线程，不能在那里做 IO */
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "antifraud-diag").apply { isDaemon = true }
    }

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

    /**
     * 追踪"这一次检测实际发出去的 Cookie"。开关只在一轮检测期间为 true，
     * 免得整个 App 的 B站请求都往日志里灌。MiaoHttp 里读它。
     */
    @Volatile
    var traceRequests: Boolean = false

    /** 一轮检测结束时务必复位（协程被取消也走这里），否则后续所有 reply 请求都会写日志 */
    fun resetTrace() {
        traceRequests = false
    }

    /** 由 MiaoHttp 在拼好请求头之后调用（只记名字，绝不记值） */
    fun traceRequest(url: String?, cookie: String?, asGuest: Boolean) {
        if (!traceRequests) return
        if (url == null || "reply" !in url) return
        val mode = if (asGuest) "游客模式" else "登录态"
        info("→ 实发请求[$mode] Cookie=【${cookieNames(cookie)}】")
    }

    /** 一次检测开始：写分隔线，重置步骤号 */
    @Synchronized
    fun start(title: String) {
        if (!enabled) return
        sessionStart = System.currentTimeMillis()
        stepNo = 0
        traceRequests = true
        write("")
        write("========== $title ==========")
    }

    /** 记一步 */
    @Synchronized
    fun step(msg: String) {
        if (!enabled) return
        stepNo++
        write("[${stepNo}] $msg   (+${System.currentTimeMillis() - sessionStart}ms)")
    }

    /** 记一条补充信息（不占步骤号） */
    @Synchronized
    fun info(msg: String) {
        if (!enabled) return
        write("    · $msg")
    }

    /**
     * 收尾：整段写进日志文件。
     *
     * @param mirror 是否**同时**塞一条进「错误日志」页。
     *   复查一轮会查 10~30 次，**每次**都往错误日志塞一条的话，那一页会被刷爆（而且它没有条数上限）
     *   —— 所以只有"整轮检测的最终结论"才 mirror，中间每次只进日志文件（文件每次进程启动会清空）。
     */
    @Synchronized
    fun finish(resultLine: String, mirror: Boolean = true) {
        traceRequests = false
        if (!enabled) return
        write("===== 结论：$resultLine =====")
        if (mirror) {
            runCatching {
                // ★ 用内存 buffer，不要重读日志文件：
                //   ① 文件写入是丢到单线程队列上**异步**做的，在主线程读文件可能读不到刚排队的行
                //      （结论那一行正好缺失，这正是我们最想看的）；
                //   ② SKIPPED 早退分支是在主线程调 finish()，读整份文件会直接卡 UI。
                //   buffer 里就是"本次进程写过的全部内容"，和文件语义一致。截到 4000 字，
                //   避免一条日志把「错误日志」页撑爆。
                val tail = synchronized(lock) { buffer.toString().takeLast(4000) }
                ErrorLogCollector.logError(
                    error = "[评论反诈] $resultLine",
                    stackTrace = tail,
                )
            }
        }
        buffer.setLength(0)
    }

    private val buffer = StringBuilder()

    private fun write(line: String) {
        val stamped = "${timeFormat.get()?.format(Date()) ?: "-"} $line"
        synchronized(lock) {
            buffer.append(stamped).append('\n')
            // 文件 IO 交给单线程队列：调用方（含主线程的 SKIPPED 分支）不做磁盘写
            ioExecutor.execute { runCatching { logFile?.appendText(stamped + "\n") } }
        }
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
