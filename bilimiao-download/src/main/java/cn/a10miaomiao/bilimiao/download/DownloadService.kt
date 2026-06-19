package cn.a10miaomiao.bilimiao.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import cn.a10miaomiao.bilimiao.download.entry.BiliDownloadEntryAndPathInfo
import cn.a10miaomiao.bilimiao.download.entry.BiliDownloadEntryInfo
import cn.a10miaomiao.bilimiao.download.entry.BiliDownloadMediaFileInfo
import cn.a10miaomiao.bilimiao.download.entry.CurrentDownloadInfo
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.utils.CompressionTools
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import java.io.*
import kotlin.coroutines.CoroutineContext

class DownloadService: Service(), CoroutineScope, DownloadManager.Callback {
    companion object {
        private const val TAG = "DownloadService"
        private val channel = Channel<DownloadService>()
        private val logFile by lazy {
            java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "BiliMiao/bilimiao_dl.log")
        }
        fun logToFile(msg: String) {
            try {
                logFile.parentFile?.mkdirs()
                logFile.appendText("${java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())} $msg\n")
            } catch (_: Exception) {}
        }
        private var _instance: DownloadService? = null

        val instance get() = _instance

        suspend fun getService(context: Context): DownloadService{
            _instance?.let { return it }
            startService(context)
            return channel.receive().also {
                _instance = it
            }
        }

        fun startService(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            context.startService(intent)
        }
    }

    private var job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job
    private val downloadNotify by lazy { DownloadNotify(this) }
    private var downloadManager: DownloadManager? = null
    private var audioDownloadManager: DownloadManager? = null
    private var currentTaskId = 1L
    private var idCounter = 1L

    private var audioDownloadManagerCallback = object : DownloadManager.Callback {
        override fun onTaskRunning(info: CurrentDownloadInfo) {
        }

        override fun onTaskComplete(info: CurrentDownloadInfo) {
            if (downloadManager?.downloadInfo?.status == CurrentDownloadInfo.STATUS_COMPLETED) {
                downloadNotify.showCompletedStatusNotify(info)
                completeDownload()
            }
        }

        override fun onTaskError(info: CurrentDownloadInfo, error: Throwable) {
            if (downloadManager?.downloadInfo?.status == CurrentDownloadInfo.STATUS_COMPLETED) {

            }
        }

    }

    var downloadList = mutableListOf<BiliDownloadEntryAndPathInfo>()
    var downloadListVersion = MutableStateFlow(0)
    var waitDownloadQueue = mutableListOf<BiliDownloadEntryAndPathInfo>()
    val curDownload = MutableStateFlow<CurrentDownloadInfo?>(null)
    private val curBiliDownloadEntryAndPathInfo: BiliDownloadEntryAndPathInfo?
        get() = curDownload.value?.let { cur ->
            downloadList.find { it.entry.key == cur.id }
        }
    private var curMediaFile: File? = null
    private var curMediaFileInfo: BiliDownloadMediaFileInfo? = null


    override fun onCreate() {
        super.onCreate()
        logToFile("SERVICE onCreate")
        job = Job()
        launch {
            readDownloadList()
            logToFile("SERVICE readDownloadList done, items=${downloadList.size}")
            channel.send(this@DownloadService)
        }
        launch {
            curDownload.collect { info ->
                if (info == null) {
                    downloadNotify.cancel()
                } else {
                    // 静默下载不弹通知
                    val entry = curBiliDownloadEntryAndPathInfo
                    if (entry == null || !entry.entry.isSilent) {
                        downloadNotify.notifyData(info)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        _instance = null
    }

    private fun readDownloadList() {
        val downloadDir = File(getDownloadPath())
        val list = mutableListOf<BiliDownloadEntryAndPathInfo>()
        downloadDir.listFiles()
            .filter { it.isDirectory }
            .forEach {
                list.addAll(readDownloadDirectory(it))
            }
        downloadList = list.reversed().toMutableList()
    }

    fun readDownloadDirectory(dir: File): List<BiliDownloadEntryAndPathInfo>{
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }
        return dir.listFiles()
            .filter { pageDir -> pageDir.isDirectory }
            .map { File(it.path, "entry.json") }
            .filter { it.exists() }
            .mapNotNull {
                try {
                    val entryJson = it.readText()
                    val entry = MiaoJson.fromJson<BiliDownloadEntryInfo>(entryJson)
                    BiliDownloadEntryAndPathInfo(
                        entry = entry,
                        entryDirPath = it.parent,
                        pageDirPath = it.parentFile.parent
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
    }

    /**
     * 是否处于等待下载队列中
     */
    fun isInWaitDownloadQueue(dirPath: String): Boolean {
        return waitDownloadQueue.indexOfFirst { it.entryDirPath == dirPath } >= 0
    }

    /**
     * 创建任务
     */
    fun createDownload(
        biliEntry: BiliDownloadEntryInfo
    ) {
        logToFile("createDownload: season_id=${biliEntry.season_id} avid=${biliEntry.avid} ep_id=${biliEntry.ep?.episode_id} cid=${biliEntry.source?.cid}")
        val entryDir = getDownloadFileDir(biliEntry)
        // 保存视频信息
        val entryJsonFile = File(entryDir, "entry.json")
        val entryJsonStr = MiaoJson.toJson(biliEntry)
        entryJsonFile.writeText(entryJsonStr)
        val biliDownInfo = BiliDownloadEntryAndPathInfo(
            entry = biliEntry,
            pageDirPath = entryDir.parent,
            entryDirPath = entryDir.absolutePath,
        )
        val index = downloadList.indexOfFirst {
            if (biliEntry.avid != null) {
                biliEntry.avid == it.entry.avid
            } else {
                biliEntry.season_id == it.entry.season_id
            }
        }
        downloadList.add(index + 1, biliDownInfo)
        downloadListVersion.value++
        val curDl = curDownload.value
        logToFile("createDownload: curDownload=${curDl?.status} curDl.id=${curDl?.id} waitQueue=${waitDownloadQueue.size}")
        if (curDl == null) {
            logToFile("createDownload: STARTING immediately")
            startDownload(biliDownInfo)
        } else {
            logToFile("createDownload: QUEUED (curDownload not null)")
            waitDownloadQueue.add(biliDownInfo)
        }
    }

    fun startDownload(entryDirPath: String) {
        val biliDownInfo = downloadList.find {
            it.entryDirPath == entryDirPath
        }
        if (biliDownInfo != null) {
            startDownload(biliDownInfo)
        } else {
//            val entryFile = File(entryDirPath, "entry.json")
//            if (entryFile.exists()) {
//
//            }
        }
    }
    /**
     * 开始任务
     */
    fun startDownload(biliDownInfo: BiliDownloadEntryAndPathInfo) = launch {
        logToFile("startDownload: entry.name=${biliDownInfo.entry.name} entryDirPath=${biliDownInfo.entryDirPath}")
        // 取消当前任务
        downloadManager?.cancel()
        audioDownloadManager?.cancel()
        downloadManager = null
        audioDownloadManager = null
        // 开始任务/继续任务
        val entryDir = File(biliDownInfo.entryDirPath)
        val danmakuXMLFile = File(entryDir, "danmaku.xml")
        val entry = biliDownInfo.entry
        val parentId = entry.season_id ?: entry.avid?.toString() ?: ""
        val id = entry.page_data?.cid ?: entry.source?.cid ?: 0L
        currentTaskId = idCounter++
        val currentDownloadInfo = CurrentDownloadInfo(
            taskId = currentTaskId,
            parentDirPath = entryDir.parent,
            parentId = parentId,
            id = id,
            name = entry.name,
            url = "",
            header = mapOf(),
            size = entry.total_bytes,
            progress = entry.downloaded_bytes,
            length = entry.total_time_milli,
        )
        if (!danmakuXMLFile.exists()) {
            try {
                // 获取弹幕并下载
                curDownload.value = currentDownloadInfo.copy(
                    status = CurrentDownloadInfo.STATUS_GET_DANMAKU,
                )
                val dmUrl = BiliPalyUrlHelper.danmakuXMLUrl(biliDownInfo.entry)
                logToFile("danmaku url=$dmUrl entry.season_id=${entry.season_id} avid=${entry.avid} cid=$id")
                if (cn.a10miaomiao.bilimiao.download.BuildConfig.DEBUG) android.util.Log.d("BilimiaoDL", "danmaku url=$dmUrl entry.season_id=${entry.season_id} avid=${entry.avid} cid=$id")
                val res = MiaoHttp.request {
                    url = dmUrl
                }.awaitCall()
                val bodyBytes = res.body?.bytes() ?: ByteArray(0)
                logToFile("danmaku status=${res.code} bodyLen=${bodyBytes.size}")
                if (cn.a10miaomiao.bilimiao.download.BuildConfig.DEBUG) android.util.Log.d("BilimiaoDL", "danmaku status=${res.code} bodyLen=${bodyBytes.size}")
                val xmlBytes = CompressionTools.decompressXML(bodyBytes)
                danmakuXMLFile.writeBytes(xmlBytes)
                logToFile("danmaku OK, start playUrl")
                if (cn.a10miaomiao.bilimiao.download.BuildConfig.DEBUG) android.util.Log.d("BilimiaoDL", "danmaku OK, start playUrl")
            } catch (e: Exception){
                logToFile("danmaku FAILED: ${e.message}")
                android.util.Log.e("BilimiaoDL", "danmaku FAILED: ${e.message}", e)
                curDownload.value = currentDownloadInfo.copy(
                    status = CurrentDownloadInfo.STATUS_FAIL_DANMAKU,
                )
                e.printStackTrace()
                stopDownload()
                return@launch
            }
        }
        downloadVideo(currentDownloadInfo, biliDownInfo)
    }

    private suspend fun downloadVideo(
        currentDownloadInfo: CurrentDownloadInfo,
        biliDownInfo: BiliDownloadEntryAndPathInfo,
    ) {
        if (currentDownloadInfo.taskId != currentTaskId) {
            return
        }
        val entry = biliDownInfo.entry
        val entryDir = File(biliDownInfo.entryDirPath)
        val videoDir = File(entryDir, entry.type_tag)
        if (!videoDir.exists()) {
            videoDir.mkdir()
        }
        try {
            curDownload.value = currentDownloadInfo.copy(
                status = CurrentDownloadInfo.STATUS_GET_PLAYURL,
            )
            //获取播放地址并下载
            logToFile("playUrl: season_id=${entry.season_id} avid=${entry.avid} ep.episode_id=${entry.ep?.episode_id} cid=${entry.source?.cid} prefered_quality=${entry.prefered_video_quality} media_type=${entry.media_type}")
            if (cn.a10miaomiao.bilimiao.download.BuildConfig.DEBUG) android.util.Log.d("BilimiaoDL", "playUrl: season_id=${entry.season_id} avid=${entry.avid} ep.episode_id=${entry.ep?.episode_id} cid=${entry.source?.cid} prefered_quality=${entry.prefered_video_quality} media_type=${entry.media_type}")
            val mediaFileInfo = BiliPalyUrlHelper.playUrl(entry)
            logToFile("playUrl done: type=${mediaFileInfo::class.simpleName}")
            if (cn.a10miaomiao.bilimiao.download.BuildConfig.DEBUG) android.util.Log.d("BilimiaoDL", "playUrl done: type=${mediaFileInfo::class.simpleName}")
            val httpHeader = mediaFileInfo.httpHeader()
            val mediaJsonFile = File(videoDir, "index.json")
            val mediaJsonStr = MiaoJson.toJson(mediaFileInfo)
            mediaJsonFile.writeText(mediaJsonStr)

            if (currentDownloadInfo.taskId != currentTaskId) {
                return
            }

            curMediaFile = mediaJsonFile
            curMediaFileInfo = mediaFileInfo
            when(mediaFileInfo) {
                is BiliDownloadMediaFileInfo.Type1 -> {
                    // TODO: 多视频文件下载
                    val dlInfo = currentDownloadInfo.copy(
                        url = mediaFileInfo.segment_list[0].url,
                        header = httpHeader,
                        size = mediaFileInfo.segment_list[0].bytes,
                        length = mediaFileInfo.segment_list[0].duration
                    )
                    downloadManager = DownloadManager(this, dlInfo, this).also {
                        it.start(File(videoDir, "0" + "." + mediaFileInfo.format))
                    }
                    curDownload.value = dlInfo
                }
                is BiliDownloadMediaFileInfo.Type2 -> {
                    val dlInfo = currentDownloadInfo.copy(
                        url = mediaFileInfo.video[0].base_url,
                        header = httpHeader,
                        size = entry.total_bytes,
                        length = mediaFileInfo.duration
                    )
                    downloadManager = DownloadManager(this, dlInfo, this)
                    curDownload.value = dlInfo
                    downloadManager?.start(File(videoDir, "video.m4s"))
                    val audio = mediaFileInfo.audio
                    if (audio != null && audio.isNotEmpty()) {
                        audioDownloadManager = DownloadManager(this, CurrentDownloadInfo(
                            taskId = currentDownloadInfo.taskId,
                            parentDirPath = currentDownloadInfo.parentDirPath,
                            parentId = currentDownloadInfo.parentId,
                            id = currentDownloadInfo.id,
                            name = entry.name,
                            url = audio[0].base_url,
                            header = httpHeader,
                            size = audio[0].size,
                            length = mediaFileInfo.duration
                        ), audioDownloadManagerCallback)
                        audioDownloadManager?.start(File(videoDir, "audio.m4s"))
                    }
                    entry.page_data?.let {
                        entry.page_data = it.copy(
                            height = mediaFileInfo.video[0].height,
                            width = mediaFileInfo.video[0].width,
                        )
                    }
                    entry.ep?.let {
                        entry.ep = it.copy(
                            height = mediaFileInfo.video[0].height,
                            width = mediaFileInfo.video[0].width,
                        )
                    }
                    updateBiliDownloadEntryJson(biliDownInfo.entryDirPath, entry)
                }
                else -> {
                    logToFile("playUrl: UNEXPECTED type ${mediaFileInfo::class.simpleName}")
                    android.util.Log.e("BilimiaoDL", "playUrl: UNEXPECTED type ${mediaFileInfo::class.simpleName}")
                    curDownload.value = currentDownloadInfo.copy(
                        status = CurrentDownloadInfo.STATUS_FAIL_PLAYURL,
                    )
                    stopDownload()
                }
            }
        } catch (e: Exception) {
            logToFile("playUrl FAILED: ${e.message}")
            android.util.Log.e("BilimiaoDL", "playUrl FAILED: ${e.message}", e)
            curDownload.value = currentDownloadInfo.copy(
                status = CurrentDownloadInfo.STATUS_FAIL_PLAYURL,
            )
            e.printStackTrace()
            stopDownload()
        }
    }

    fun cancelDownload(taskId: Long) {
        if (taskId == currentTaskId) {
            downloadManager?.cancel()
            audioDownloadManager?.cancel()
            downloadManager = null
            audioDownloadManager = null
            currentTaskId = 0L
            stopDownload()
        }
    }

    /**
     * 结束当前任务
     */
    fun stopDownload () {
        curDownload.value?.let { cur ->
            val entryAndPathInfo = downloadList.find {
                cur.id == it.entry.key
            }
            if (entryAndPathInfo != null) {
                entryAndPathInfo.entry.total_bytes = cur.size
                entryAndPathInfo.entry.downloaded_bytes = cur.progress
                updateBiliDownloadEntryJson(
                    entryAndPathInfo.entryDirPath,
                    entryAndPathInfo.entry,
                )
                downloadListVersion.value++
                downloadManager?.cancel()
            }
        }
        curDownload.value = null
        curMediaFile = null
        curMediaFileInfo = null
        nextDownload()
    }

    /**
     * 删除当前任务
     */
    fun deleteDownload (
        pageDirPath: String,
        entryDirPath: String,
    ) {
        val index = downloadList.indexOfFirst {
            it.pageDirPath == pageDirPath && it.entryDirPath == entryDirPath
        }
        if (index != -1) {
            // 如果为当前下载任务则先停止任务
            val entryAndPathInfo = downloadList[index]
            if (curDownload.value?.id == entryAndPathInfo.entry.key) {
                cancelDownload(currentTaskId)
            }
        }
        val downloadDir = File(pageDirPath)
        if (downloadDir.exists()) {
            val entryDir = File(entryDirPath)
            if (entryDir.exists()) {
                entryDir.deleteRecursively()
            }
            if (downloadDir.listFiles().size === 0) {
                downloadDir.delete()
            }
        }
        if (index != -1) {
            // 从列表移除
            downloadList.removeAt(index)
            downloadListVersion.value++
        }
    }

    /**
     * 完成下载
     */
    private fun completeDownload() {
        val (_, entryDirPath, entry) = curBiliDownloadEntryAndPathInfo ?: return
        entry.downloaded_bytes = entry.total_bytes
        entry.is_completed = true
        entry.total_time_milli = (curDownload.value?.length ?: 0L) * 1000
        updateBiliDownloadEntryJson(entryDirPath, entry)
        downloadListVersion.value++
        curDownload.value = null
        curMediaFile = null
        curMediaFileInfo = null
        downloadManager = null
        audioDownloadManager = null
        nextDownload()
    }

    /**
     * 完成下载
     */
    private fun nextDownload() {
        while (waitDownloadQueue.isNotEmpty()) {
            val next = waitDownloadQueue.removeAt(0)
            if (downloadList.indexOfFirst { it.entry.key == next.entry.key } != -1) {
                startDownload(next)
                return
            }
        }
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onTaskRunning(info: CurrentDownloadInfo) {
        if (info.progress == 0L && info.size != 0L) {
            (curMediaFileInfo as BiliDownloadMediaFileInfo.Type2)?.let {
                if (it.video[0].size == 0L && info.size != 0L) {
                    it.video[0].size = info.size
                    val mediaJsonStr = MiaoJson.toJson(it)
                    curMediaFile?.writeText(mediaJsonStr)
                }
            }
            val entryAndPathInfo = downloadList.find {
                info.id == it.entry.key
            }
            if (entryAndPathInfo != null) {
                entryAndPathInfo.entry.total_bytes = info.size
                updateBiliDownloadEntryJson(
                    entryAndPathInfo.entryDirPath,
                    entryAndPathInfo.entry,
                )
                downloadListVersion.value++
            }
        }
        curDownload.value = info.copy()
    }

    override fun onTaskComplete(info: CurrentDownloadInfo) {
        // 当 Content-Length 未知时 info.size = -1 (chunked encoding)
        // size > 0 且 progress < size 才是真的未完成
        if (info.size > 0 && info.progress < info.size) {
            return
        }
        when (audioDownloadManager?.downloadInfo?.status) {
            CurrentDownloadInfo.STATUS_DOWNLOADING -> {
                // 等待音频下载完成
                curDownload.value = info.copy(
                    status = CurrentDownloadInfo.STATUS_AUDIO_DOWNLOADING
                )
            }
            CurrentDownloadInfo.STATUS_FAIL_DOWNLOAD -> {
                // 下载失败，停止当前任务，处理队列中的下一个
                stopDownload()
            }
            CurrentDownloadInfo.STATUS_COMPLETED, null -> {
                // 完成下载
                downloadNotify.showCompletedStatusNotify(info)
                completeDownload()
            }
        }
    }

    override fun onTaskError(info: CurrentDownloadInfo, error: Throwable) {
        error.printStackTrace()
        curDownload.value = info.copy(
            status = CurrentDownloadInfo.STATUS_FAIL_DOWNLOAD
        )
        val entryAndPathInfo = downloadList.find {
            info.id == it.entry.key
        }
        if (entryAndPathInfo != null) {
            entryAndPathInfo.entry.total_bytes = info.size
            entryAndPathInfo.entry.downloaded_bytes = info.progress
            updateBiliDownloadEntryJson(
                entryAndPathInfo.entryDirPath,
                entryAndPathInfo.entry,
            )
            downloadListVersion.value++
        }
        stopDownload()
    }

    private fun updateBiliDownloadEntryJson(
        entryDirPath: String,
        entry: BiliDownloadEntryInfo,
    ) {
        // 保存视频信息
        val entryJsonFile = File(entryDirPath, "entry.json")
        val entryJsonStr = MiaoJson.toJson(entry)
        entryJsonFile.writeText(entryJsonStr)
    }

    fun getDownloadPath(): String {
        // 优先公共目录，写入失败静默回退私有目录
        val publicDir = File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        ), "BiliMiao")
        try {
            if (!publicDir.exists() && !publicDir.mkdirs()) {
                throw IOException()
            }
            val testFile = File(publicDir, ".write_test")
            testFile.createNewFile()
            testFile.delete()
            // 创建 .nomedia 防止系统图库扫描下载的视频/音频/弹幕/字幕
            val nomedia = File(publicDir, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()
            logToFile("getDownloadPath: PUBLIC ${publicDir.canonicalPath}")
            return publicDir.canonicalPath
        } catch (e: Exception) {
            val privateDir = File(getExternalFilesDir(null), "BiliMiao")
            if (!privateDir.exists()) {
                privateDir.mkdirs()
            }
            val nomedia = File(privateDir, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()
            logToFile("getDownloadPath: FALLBACK PRIVATE ${privateDir.canonicalPath}")
            return privateDir.canonicalPath
        }
    }

    private fun getDownloadFileDir(biliEntry: BiliDownloadEntryInfo): File {
        var dirName = ""
        var pageDirName = ""
        val ep = biliEntry.ep
        val page = biliEntry.page_data
        // 优先用 season_id 作为父目录名（合集/番剧共用目录，确保分组正确）
        if (biliEntry.season_id != null) {
            dirName = "s_" + biliEntry.season_id!!
            pageDirName = if (ep != null) {
                ep.episode_id.toString()
            } else if (page != null) {
                "c_" + page.cid
            } else {
                ""
            }
        } else if (page != null) {
            dirName = biliEntry.avid?.toString() ?: ""
            pageDirName = "c_" + page.cid
        }
        val downloadDir = File(getDownloadPath(), dirName)
        // 创建文件夹
        if (!downloadDir.exists()) {
            downloadDir.mkdir()
        }
        val pageDir = File(downloadDir, pageDirName)
        if (!pageDir.exists()) {
            pageDir.mkdir()
        }
        return pageDir
    }

}