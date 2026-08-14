package cn.a10miaomiao.bilimiao.download

import cn.a10miaomiao.bilimiao.download.entry.CurrentDownloadInfo
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloadManager(
    val scope: CoroutineScope,
    val downloadInfo: CurrentDownloadInfo,
    val callback: Callback,
) {

    private val mClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 当前进行中的请求，用于暂停时立即中断阻塞读 */
    private var activeCall: okhttp3.Call? = null

    fun start(file: File, downloadedLength: Long = 0) {
        scope.launch {
            create(downloadInfo, file, downloadedLength).run {
                throttleFirst(200)
            }.catch { e ->
                if (downloadInfo.status == CurrentDownloadInfo.STATUS_PAUSE) {
                    // 用户主动暂停导致的取消属于正常终止，不按失败处理
                } else {
                    downloadInfo.status = CurrentDownloadInfo.STATUS_FAIL_DOWNLOAD
                    callback.onTaskError(downloadInfo, e)
                }
            }.onCompletion {
                if (downloadInfo.status == CurrentDownloadInfo.STATUS_COMPLETED) {
                    callback.onTaskComplete(downloadInfo)
                }
            }.collect {
                if (it.status == CurrentDownloadInfo.STATUS_DOWNLOADING) {
                    callback.onTaskRunning(it)
                }
            }
        }
    }

    private fun <T> Flow<T>.throttleFirst(periodMillis: Long): Flow<T> {
        return flow {
            var lastTime = 0L
            collect { value ->
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTime >= periodMillis) {
                    lastTime = currentTime
                    emit(value)
                }
            }
        }
    }

    /**
     * 创建下载
     */
    private fun create(info: CurrentDownloadInfo, file: File, downloadedLength: Long = 0) = flow<CurrentDownloadInfo> {
        if (file.exists()) {
            if (info.size == 0L) {
                file.delete()
            } else {
                info.progress = file.length()
            }
        }
        var downloadLength = info.progress //已经下载好的长度
        val request = Request.Builder()
            .url(UrlUtil.autoHttps(info.url))
        if (downloadLength > 0 && info.size != 0L) {
            if (info.size == downloadLength) {
                downloadInfo.status = CurrentDownloadInfo.STATUS_COMPLETED
                return@flow
            }
            request.addHeader("RANGE", "bytes=$downloadLength-")
        }
        downloadLength += downloadedLength
        for (keys in info.header.keys) {
            request.addHeader(keys, info.header[keys] ?: "")
        }
        val call = mClient.newCall(request.build())
        activeCall = call
        val response = call.execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code}: ${response.message}")
        }
        // 断点续传必须校验 206：服务器忽略 Range 返回 200 全量时，
        // 直接 append 会把全量内容接到半截文件后导致文件损坏
        if (downloadLength > 0 && response.code != 206) {
            FileOutputStream(file, false).use { } // 清空文件，从头下载
            downloadLength = 0
            info.progress = 0
            emit(info)
        }
        val body = response.body
            ?: throw IOException("Response body is null for url: ${info.url}")
        downloadInfo.status = CurrentDownloadInfo.STATUS_DOWNLOADING
        if (info.size == 0L) {
            info.size = body.contentLength()
            emit(info)
        }
        try {
            body.byteStream().use { `is` ->
                BufferedInputStream(`is`).use { bis ->
                    FileOutputStream(file, true).use { fos ->
                        var buffer = ByteArray(2048) //缓冲数组2kB
                        var len: Int = bis.read(buffer)
                        while (len != -1 && downloadInfo.status == CurrentDownloadInfo.STATUS_DOWNLOADING) {
                            fos.write(buffer, 0, len)
                            downloadLength += len
                            info.progress = downloadLength
                            emit(info)
                            len = bis.read(buffer)
                        }
                        if (downloadInfo.status == CurrentDownloadInfo.STATUS_PAUSE) {
                            call.cancel()
                        } else {
                            downloadInfo.status = CurrentDownloadInfo.STATUS_COMPLETED
                        }
                        fos.flush()
                    }
                }
            }
        } finally {
            response.close()
        }
    }

    /**
     * 取消下载
     */
    fun cancel(): CurrentDownloadInfo? {
        downloadInfo.status = CurrentDownloadInfo.STATUS_PAUSE
        // 立即中断阻塞读，否则暂停最长要等 120s readTimeout 才生效
        activeCall?.cancel()
        return downloadInfo
    }


    interface Callback {
        fun onTaskRunning(info: CurrentDownloadInfo)
        fun onTaskComplete(info: CurrentDownloadInfo)
        fun onTaskError(info: CurrentDownloadInfo, error: Throwable)
    }
}