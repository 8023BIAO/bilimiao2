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
        downloadLength += downloadedLength

        // ★★ 候选地址依序尝试（系统一次会给 base_url + 若干 backup_url）。
        //   实测踩过的坑：audio 的 base_url 是 mcdn PCDN 节点，HTTP 000 根本连不上，
        //   而同一个音频的 backup_url 返回 206 —— 只试第一个就会"下载失败 / 下不完全"。
        //   这里只换"系统给的"地址，不写死任何 CDN 节点。
        val candidates = (listOf(info.url) + info.candidateUrls)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        var opened: okhttp3.Response? = null
        var lastError: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            val request = Request.Builder().url(UrlUtil.autoHttps(candidate))
            if (downloadLength > 0 && info.size != 0L) {
                if (info.size == downloadLength) {
                    downloadInfo.status = CurrentDownloadInfo.STATUS_COMPLETED
                    return@flow
                }
                request.addHeader("RANGE", "bytes=$downloadLength-")
            }
            for (keys in info.header.keys) {
                // ★★ 绝对不要发 Referer —— 这是"下载秒失败"的根因（2026-09-20 实测）：
                //   B站取流 CDN（尤其 PCDN 节点）对带 Referer 的请求直接回 403，同一个 URL：
                //     UA + Referer            → HTTP 403（老代码就是这个组合）
                //     只发 UA（不带 Referer） → HTTP 206，正常下载
                //   播放器的媒体请求也是**只设 User-Agent、不设 Referer**，所以播放一直没事。
                //   另注：完全不带头也不行（curl 默认 UA 会被拒），UA 必须保留。
                if (keys.equals("Referer", ignoreCase = true)) continue
                request.addHeader(keys, info.header[keys] ?: "")
            }
            val call = mClient.newCall(request.build())
            activeCall = call
            try {
                val r = call.execute()
                if (!r.isSuccessful) {
                    val code = r.code
                    val message = r.message
                    r.close()
                    throw IOException("HTTP $code: $message")
                }
                opened = r
                break
            } catch (e: Exception) {
                lastError = e
                // 还有候选就换下一个；最后一个也失败才把异常抛出去（交给上层标记失败/等重试）
                if (index == candidates.lastIndex) {
                    throw IOException(
                        "所有地址都不可用（试了 ${candidates.size} 个）：${e.message}", e
                    )
                }
            }
        }
        val response = opened ?: throw (lastError ?: IOException("没有可用的下载地址：${info.url}"))
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
                            // 用字段 activeCall：循环里那个 call 已经出了作用域
                            activeCall?.cancel()
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