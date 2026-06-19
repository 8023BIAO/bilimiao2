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
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private var logFile: File? = null
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "error_logs")
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, "errors.jsonl")

        // 捕获未处理的异常
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
                time = dateFormat.format(Date()),
                deviceInfo = deviceInfo,
                appInfo = appInfo
            )
            val line = json.encodeToString(entry) + "\n"
            logFile?.appendText(line)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLogs(): List<ErrorLogEntry> {
        return try {
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
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearLogs() {
        try {
            logFile?.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 删除指定索引的日志条目（索引顺序与 getLogs() 一致：最新在前） */
    fun deleteLogs(indices: Set<Int>) {
        try {
            val currentLogs = getLogs()
            val keepLogs = currentLogs.filterIndexed { idx, _ -> idx !in indices }
            logFile?.writeText(keepLogs.reversed().joinToString("\n") { json.encodeToString(it) } + "\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
