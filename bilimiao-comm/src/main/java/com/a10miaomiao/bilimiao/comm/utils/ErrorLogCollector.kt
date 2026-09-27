package com.a10miaomiao.bilimiao.comm.utils

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class ErrorLogEntry(
    val error: String,
    val stackTrace: String,
    val time: String,
    val deviceInfo: Map<String, String> = emptyMap(),
    val appInfo: Map<String, String> = emptyMap()
)

object ErrorLogCollector {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    // SimpleDateFormat 不是线程安全的：崩溃可能发生在任意线程（含未捕获异常处理器），
    // 并发 format 会输出错乱的时间甚至抛异常 → 统一加锁
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private fun nowString(): String = synchronized(dateFormat) {
        dateFormat.format(Date())
    }

    private var logFile: File? = null
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var handlerInstalled = false

    /** 超过这个体积就裁掉旧记录：崩溃循环里一秒能写几十条，不设上限会把用户存储写满 */
    private const val MAX_BYTES = 256 * 1024L

    /** 裁剪后保留的条数（最新的） */
    private const val KEEP_ENTRIES = 300

    fun init(context: Context) {
        val dir = File(context.filesDir, "error_logs")
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, "errors.jsonl")

        // 捕获未处理的异常
        // ★ 只装一次：init 被调用两次时原来的 originalHandler 会指向自己，崩溃时无限递归 → StackOverflow
        if (handlerInstalled) return
        handlerInstalled = true
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logError(
                error = throwable.message ?: "Unknown error",
                stackTrace = throwable.stackTraceToString(),
                deviceInfo = mapOf(
                    "model" to (android.os.Build.MODEL ?: ""),
                    "sdk" to android.os.Build.VERSION.SDK_INT.toString(),
                    "manufacturer" to (android.os.Build.MANUFACTURER ?: "")
                )
            )
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    fun logError(
        error: String,
        stackTrace: String = "",
        deviceInfo: Map<String, String> = emptyMap(),
        appInfo: Map<String, String> = emptyMap()
    ) {
        try {
            val entry = ErrorLogEntry(
                error = error,
                stackTrace = stackTrace,
                // 走加锁的 nowString()，崩溃线程并发格式化时 SimpleDateFormat 会输出错乱时间
                time = nowString(),
                deviceInfo = deviceInfo,
                appInfo = appInfo
            )
            val line = json.encodeToString(entry) + "\n"
            // 崩溃线程与业务线程可能同时写，加锁避免同一行被互相截断
            synchronized(this) {
                val file = logFile ?: return
                file.appendText(line)
                if (file.length() > MAX_BYTES) trimLocked(file)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 只保留最新 KEEP_ENTRIES 条。调用方必须已持有 this 锁 */
    private fun trimLocked(file: File) {
        runCatching {
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.size <= KEEP_ENTRIES) return
            file.writeText(lines.takeLast(KEEP_ENTRIES).joinToString("\n") + "\n")
        }
    }

    fun getLogs(): List<ErrorLogEntry> {
        return try {
            // 与 logError 的 append 互斥：不加锁会读到写了一半的行；更要命的是 deleteLogs 的
            // read-modify-write 会覆盖掉并发写入的记录
            synchronized(this) {
                val file = logFile ?: return emptyList()
                if (!file.exists()) return emptyList()
                file.readLines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        try {
                            json.decodeFromString<ErrorLogEntry>(line)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .reversed()  // 最新的在前
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearLogs() {
        try {
            synchronized(this) {
                logFile?.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 删除指定索引的日志条目（索引顺序与 getLogs() 一致：最新在前） */
    fun deleteLogs(indices: Set<Int>) {
        try {
            synchronized(this) {
                val currentLogs = getLogs()
                val keepLogs = currentLogs.filterIndexed { idx, _ -> idx !in indices }
                if (keepLogs.isEmpty()) {
                    // 全删掉时直接删文件，别留一个只有换行的空文件
                    logFile?.delete()
                } else {
                    logFile?.writeText(keepLogs.reversed().joinToString("\n") { json.encodeToString(it) } + "\n")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
