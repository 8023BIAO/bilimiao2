package cn.a10miaomiao.bilimiao.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
            // ★ 只 debug 包写日志：以前 release 也会往 /sdcard/Download/BiliMiao/bilimiao_dl.log
            //   一步步追加（公开目录 + 无条件 IO），属于调试残留；要排查下载问题时用 debug 包即可
            if (!BuildConfig.DEBUG) return
            try {
                logFile.parentFile?.mkdirs()
                logFile.appendText("${java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())} $msg\n")
            } catch (e: Exception) {
                android.util.Log.e("DownloadService", "logToFile failed", e)
            }
        }
        private var _instance: DownloadService? = null
        private val instanceLock = Any()

        /** 服务正在启动时共享的等待对象：并发调用必须复用同一个，
         *  否则 Channel 只发一条消息，第二个 receive() 会永远挂住（点下载一直转圈）。 */
        private var waitingInstance: CompletableDeferred<DownloadService>? = null

        val instance get() = _instance

        suspend fun getService(context: Context): DownloadService{
            _instance?.let { return it }
            var isStarter = false
            val deferred = synchronized(instanceLock) {
                _instance?.let { return it }
                waitingInstance ?: CompletableDeferred<DownloadService>().also {
                    waitingInstance = it
                    isStarter = true
                }
            }
            if (isStarter) {
                try {
                    startService(context)
                    val service = channel.receive()
                    _instance = service
                    synchronized(instanceLock) { waitingInstance = null }
                    deferred.complete(service)
                } catch (e: Exception) {
                    synchronized(instanceLock) { waitingInstance = null }
                    deferred.completeExceptionally(e)
                    throw e
                }
            }
            return deferred.await()
        }

        fun startService(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            context.startService(intent)
        }
    }

    private var job: Job = SupervisorJob()

    /** 服务作用域里的未捕获 IO 异常兜底：只记日志 + 走失败流程，绝不让异常冒泡成进程崩溃 */
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        logToFile("UNCAUGHT coroutine exception: ${e.message}")
        android.util.Log.e(TAG, "uncaught coroutine exception", e)
        try {
            // 复用统一的失败处理：标记失败 + 错误通知 + 结束当前任务让队列继续
            curDownload.value?.let { onTaskError(it, e) }
        } catch (e2: Exception) {
            logToFile("exceptionHandler FAILED: ${e2.message}")
        }
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job + exceptionHandler
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
            // 音频下载失败：终止整个任务并给出失败提示（此前空实现导致任务永久卡在等待音频）
            logToFile("audio download FAILED: ${error.message}")
            curDownload.value = info.copy(status = CurrentDownloadInfo.STATUS_FAIL_DOWNLOAD)
            downloadNotify.showErrorStatusNotify(info)
            stopDownload()
        }

    }

    var downloadList = mutableListOf<BiliDownloadEntryAndPathInfo>()
    var downloadListVersion = MutableStateFlow(0)
    // 线程安全：本 Service 的协程跑在 Dispatchers.IO，而 add/nextDownload 会从主线程调进来，
    // 普通 ArrayList 在 removeAll/removeAt 交叉时会抛 ConcurrentModificationException
    //（被 exceptionHandler 接住后表现成"下载出错"）
    var waitDownloadQueue = java.util.concurrent.CopyOnWriteArrayList<BiliDownloadEntryAndPathInfo>()
    val curDownload = MutableStateFlow<CurrentDownloadInfo?>(null)
    private val curBiliDownloadEntryAndPathInfo: BiliDownloadEntryAndPathInfo?
        get() = curDownload.value?.let { cur ->
            downloadList.find { it.entry.key == cur.id }
        }
    private var curMediaFile: File? = null
    private var curMediaFileInfo: BiliDownloadMediaFileInfo? = null
    /** Type1 多分片待下载队列（按 order 升序），全部完成后合并为单文件 */
    private var pendingSegments = mutableListOf<BiliDownloadMediaFileInfo.Type1Segment>()
    /** Type1 已完成分片的字节累计：entry.json 的分母是全部还是分片之和，不是单个分片 */
    private var completedSegmentBytes = 0L
    /** 公共目录回退私有目录的提示只弹一次，避免每次下载都打扰 */
    private var warnedPrivateFallback = false
    /** 正在发布中的剧集目录（绝对路径）：防止"启动补发布"和"刚下完发布"同一集撞车 */
    // 用 synchronizedSet 而不是 ConcurrentHashMap.newKeySet()：后者要 API 24+，本项目 minSdk 21
    private val publishingEntries: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())


    override fun onCreate() {
        super.onCreate()
        logToFile("SERVICE onCreate")
        // SupervisorJob：单个下载任务失败不要连带取消整个服务作用域（否则之后所有下载都静默失效）
        job = SupervisorJob()
        launch {
            readDownloadList()
            logToFile("SERVICE readDownloadList done, items=${downloadList.size}")
            channel.send(this@DownloadService)
            // 上次没发布成功的（发布失败、当时没有存储权限、发布完删私有失败）在这里补一次；
            // 放在 channel.send 之后：不拖慢首次打开下载页，也不影响任何已有文件
            publishPendingEntries()
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

    /**
     * 提升为前台服务。必须在“真正开始下载”时调用（不能放 onCreate，
     * 否则只是启动服务什么都没干也会常驻一条通知），否则退到后台会被系统回收，
     * 下载半截且通知永久卡住。任何异常（后台启动限制、缺权限等）只记日志，绝不崩。
     */
    private fun startForegroundCompat() {
        try {
            val notification = downloadNotify.buildForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14 (API 34)
                startForeground(
                    downloadNotify.notificationID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(downloadNotify.notificationID, notification)
            }
            logToFile("startForeground OK")
        } catch (e: Exception) {
            logToFile("startForeground FAILED: ${e.message}")
            android.util.Log.e(TAG, "startForeground failed", e)
        }
    }

    /**
     * 退出前台状态并撤下常驻通知：任务完成/失败时若不撤，“正在下载”会永久挂在通知栏
     */
    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { // API 24+
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            logToFile("stopForeground FAILED: ${e.message}")
        }
    }

    /**
     * 重建下载列表（磁盘即真相，没有数据库）：私有工作目录 + 公共下载目录两边都扫，然后去重。
     */
    private fun readDownloadList() {
        val list = mutableListOf<BiliDownloadEntryAndPathInfo>()
        // 1) 私有工作目录：下载中/未发布/发布失败/老系统无权限的（路径身份 = 私有绝对路径）
        val downloadDir = File(getWorkPath())
        downloadDir.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { pageDir -> list.addAll(readPrivatePageEntries(pageDir)) }
        // 2) 公共目录 Download/BiliMiao：已发布的（路径身份 = 相对路径，例如 "s_123/1-1"）
        list.addAll(readPublicEntries())
        // 3) 同一集可能在两边都有（发布成功但删私有失败）→ 去重，优先保留"已发布"那条
        downloadList = dedupeEntries(list).reversed().toMutableList()
    }

    /**
     * 读一个"页面目录"下的所有剧集。
     * @param dirPath 可以是私有绝对路径（工作目录下的一级目录），也可以是公共目录的相对一级目录名
     *                （下载详情页拿到的 `pageDirPath` 就是这两种之一）。
     */
    fun readDownloadDirectory(dirPath: String): List<BiliDownloadEntryAndPathInfo> {
        if (dirPath.isEmpty()) return emptyList()
        val result = mutableListOf<BiliDownloadEntryAndPathInfo>()
        // 私有侧：绝对路径直接用；相对路径映射到工作目录下的同名目录（发布中途失败时两边都可能有）
        result.addAll(readPrivatePageEntries(DownloadFileResolver.privateFile(this, dirPath)))
        // 公共侧：同一分组里可能混着"已发布（相对路径）"和"未发布（绝对路径）"两种身份的条目，
        // 所以绝对页面目录也要按"一级目录名"去公共目录里找同名页面目录
        //（发布时的相对路径就是"页面目录名/剧集目录名"，名字一定对得上）
        val relPageDir = if (DownloadFileResolver.isPublished(dirPath)) dirPath.trim('/')
        else DownloadFileResolver.nameOf(dirPath)
        if (relPageDir.isNotEmpty()) {
            PublicDownloadStore.listEntryDirs(this)
                .filter { it.substringBefore('/') == relPageDir }
                .forEach { relDir -> readPublicEntry(relDir)?.let { result.add(it) } }
        }
        return dedupeEntries(result)
    }

    /** 扫一个私有页面目录里的剧集（绝对路径身份） */
    private fun readPrivatePageEntries(privatePageDir: File): List<BiliDownloadEntryAndPathInfo> {
        if (!privatePageDir.isDirectory) return emptyList()
        return privatePageDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { entryDir -> readPrivateEntry(File(entryDir, "entry.json")) }
            ?: emptyList()
    }

    /** 读私有目录里的 entry.json（绝对路径身份） */
    private fun readPrivateEntry(entryJsonFile: File): BiliDownloadEntryAndPathInfo? {
        if (!entryJsonFile.isFile) return null
        return try {
            val entry = MiaoJson.fromJson<BiliDownloadEntryInfo>(entryJsonFile.readText())
            BiliDownloadEntryAndPathInfo(
                entry = entry,
                entryDirPath = entryJsonFile.parent,
                pageDirPath = entryJsonFile.parentFile.parent,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 读公共目录里的 entry.json（相对路径身份） */
    private fun readPublicEntry(relDir: String): BiliDownloadEntryAndPathInfo? {
        val entryJson = PublicDownloadStore.readText(this, relDir, "entry.json") ?: return null
        return try {
            val entry = MiaoJson.fromJson<BiliDownloadEntryInfo>(entryJson)
            BiliDownloadEntryAndPathInfo(
                entry = entry,
                entryDirPath = relDir,
                // "s_123/1-1" → 页面目录 "s_123"；没有斜杠时退化成自身（不会崩，只是分组名不理想）
                pageDirPath = relDir.substringBefore('/'),
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 读公共目录里全部已发布剧集（相对路径身份） */
    private fun readPublicEntries(): List<BiliDownloadEntryAndPathInfo> =
        PublicDownloadStore.listEntryDirs(this).mapNotNull { readPublicEntry(it) }

    /**
     * 同一集去重：同一集在私有、公共两边都存在时（发布成功后删私有失败）只留一条，
     * 优先保留"已发布"（相对路径）那条 —— 它是完整发布过、卸载也不丢的权威副本。
     */
    private fun dedupeEntries(list: List<BiliDownloadEntryAndPathInfo>): List<BiliDownloadEntryAndPathInfo> {
        val result = mutableListOf<BiliDownloadEntryAndPathInfo>()
        for (item in list) {
            val index = result.indexOfFirst { isSameEntry(it, item) }
            if (index < 0) {
                result.add(item)
            } else if (DownloadFileResolver.isPublished(item.entryDirPath) &&
                !DownloadFileResolver.isPublished(result[index].entryDirPath)
            ) {
                // 保留先出现的位置（列表顺序更稳定），只把内容换成已发布的那条
                result[index] = item
            }
        }
        return result
    }

    /**
     * 是不是同一集：优先用 entry.key（cid）；万一是 0（老/异常数据）就比目录名。
     * 不能用 entryDirPath 比 —— 同一集在私有、公共两种身份下路径字符串本来就不一样。
     */
    private fun isSameEntry(
        a: BiliDownloadEntryAndPathInfo,
        b: BiliDownloadEntryAndPathInfo,
    ): Boolean = if (a.entry.key != 0L && b.entry.key != 0L) {
        a.entry.key == b.entry.key
    } else {
        DownloadFileResolver.nameOf(a.entryDirPath) == DownloadFileResolver.nameOf(b.entryDirPath)
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
        // 公共目录不可用时（老系统用户拒绝了存储权限）提醒一次实际保存位置：文件在私有目录，卸载会丢
        warnPrivateFallbackOnce()
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
        // 失败态（status < 0）只是 stopDownload 刻意保留给 UI 看的残留，调度上必须当成空闲，
        // 否则一次失败之后新任务永远躺在 waitDownloadQueue 里（nextDownload 只在 stop/complete 末尾调用，
        // 此时队列为空，再没有任何触发点）。失败提示仍有错误通知，不会丢。
        if (curDl != null && curDl.status < 0) {
            curDownload.value = null
        }
        if (curDownload.value == null) {
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
     * 下载写入目录：**永远是私有工作目录**里的那一层。
     * 身份是相对路径（已发布）时映射回私有目录，绝不按相对路径创建字面量目录。
     */
    private fun writeEntryDir(entryDirPath: String): File =
        DownloadFileResolver.privateFile(this, entryDirPath)

    /**
     * 开始任务
     */
    fun startDownload(biliDownInfo: BiliDownloadEntryAndPathInfo) = launch {
        logToFile("startDownload: entry.name=${biliDownInfo.entry.name} entryDirPath=${biliDownInfo.entryDirPath}")
        // 真正开跑就从等待队列里摘掉：否则暂停/失败后 nextDownload() 又会把它从队列里取出来重启
        //（用户看到"已暂停"，过一会儿它自己又跑起来；失败项也会被悄悄重跑一次）
        waitDownloadQueue.removeAll { it.entry.key == biliDownInfo.entry.key }
        // 真正有任务要跑了才提升前台服务
        startForegroundCompat()
        // 取消当前任务
        downloadManager?.cancel()
        audioDownloadManager?.cancel()
        downloadManager = null
        audioDownloadManager = null
        // 开始任务/继续任务（写私有工作目录：断点续传/分片合并都依赖本地 File）
        val entryDir = writeEntryDir(biliDownInfo.entryDirPath)
        if (!entryDir.exists()) entryDir.mkdirs()
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
                // 失败要让用户看到：以前只写日志 + 状态被清掉，界面只剩"暂停中"
                try { downloadNotify.showErrorStatusNotify(currentDownloadInfo) } catch (e2: Exception) { logToFile("notify FAIL_DANMAKU failed: ${e2.message}") }
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
        val entryDir = writeEntryDir(biliDownInfo.entryDirPath)
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
                    // 多分片：逐个下载临时分片，全部完成后按序合并（此前只下第一段会静默丢数据）
                    val segments = mediaFileInfo.segment_list
                    if (segments.isEmpty()) throw Exception("segment_list为空")
                    pendingSegments = segments.sortedBy { it.order }.toMutableList()
                    completedSegmentBytes = 0L // 新一轮多分片下载，整体进度从 0 累计
                    startNextSegment(currentDownloadInfo, mediaFileInfo, videoDir, httpHeader)
                }
                is BiliDownloadMediaFileInfo.Type2 -> {
                    val dlInfo = currentDownloadInfo.copy(
                        url = mediaFileInfo.video.firstOrNull()?.base_url ?: throw Exception("video流为空"),
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
            try { downloadNotify.showErrorStatusNotify(currentDownloadInfo) } catch (e2: Exception) { logToFile("notify FAIL_PLAYURL failed: ${e2.message}") }
            stopDownload()
        }
    }

    fun cancelDownload(taskId: Long) {
        if (taskId == currentTaskId) {
            pendingSegments.clear()
            downloadManager?.cancel()
            audioDownloadManager?.cancel()
            downloadManager = null
            audioDownloadManager = null
            currentTaskId = 0L
            stopDownload()
        }
    }

    /**
     * 启动下一个 Type1 分片的下载；分片写为 part_<order>.<format>，完成后合并
     */
    private fun startNextSegment(
        currentDownloadInfo: CurrentDownloadInfo,
        mediaFileInfo: BiliDownloadMediaFileInfo.Type1,
        videoDir: File,
        httpHeader: Map<String, String>,
    ) {
        val seg = pendingSegments.removeAt(0)
        val idx = mediaFileInfo.segment_list.size - pendingSegments.size - 1
        val dlInfo = currentDownloadInfo.copy(
            url = seg.url,
            header = httpHeader,
            size = seg.bytes,
            length = seg.duration,
            progress = 0L, // 新分片从 0 开始，避免继承上一分片进度误发 RANGE
        )
        downloadManager = DownloadManager(this, dlInfo, this).also {
            it.start(File(videoDir, "part_$idx.${mediaFileInfo.format}"))
        }
        curDownload.value = dlInfo
        logToFile("segment start $idx/${mediaFileInfo.segment_list.size} bytes=${seg.bytes}")
    }

    /**
     * 按 order 顺序合并分片为 0.<format>（与旧单文件命名一致），随后删除临时分片
     */
    private fun mergeSegments(videoDir: File, mediaFileInfo: BiliDownloadMediaFileInfo.Type1) {
        val target = File(videoDir, "0.${mediaFileInfo.format}")
        val parts = mediaFileInfo.segment_list.indices.map { File(videoDir, "part_$it.${mediaFileInfo.format}") }
        try {
            FileOutputStream(target, false).use { out ->
                parts.forEach { part ->
                    if (part.exists()) {
                        part.inputStream().use { it.copyTo(out) }
                    }
                }
            }
            parts.forEach { if (it.exists()) it.delete() }
            logToFile("segment merge done -> ${target.name} size=${target.length()}")
        } catch (e: Exception) {
            logToFile("segment merge FAILED: ${e.message}")
            throw e
        }
    }

    /**
     * 结束当前任务
     */
    fun stopDownload () {
        pendingSegments.clear()
        curDownload.value?.let { cur ->
            val entryAndPathInfo = downloadList.find {
                cur.id == it.entry.key
            }
            if (entryAndPathInfo != null) {
                val type1MediaInfo = curMediaFileInfo as? BiliDownloadMediaFileInfo.Type1
                if (type1MediaInfo != null) {
                    // Type1 多分片：整体进度 = 已完成分片累计 + 当前分片进度，
                    // 直接写 cur.size/cur.progress 只是单个分片的量，比例会错乱
                    entryAndPathInfo.entry.total_bytes = type1MediaInfo.segment_list.sumOf { it.bytes }
                    entryAndPathInfo.entry.downloaded_bytes = completedSegmentBytes + cur.progress
                } else {
                    entryAndPathInfo.entry.total_bytes = cur.size
                    entryAndPathInfo.entry.downloaded_bytes = cur.progress
                }
                updateBiliDownloadEntryJson(
                    entryAndPathInfo.entryDirPath,
                    entryAndPathInfo.entry,
                )
                downloadListVersion.value++
                downloadManager?.cancel()
            }
        }
        // 失败状态保留给 UI 展示，非失败才清空（此前失败态被立即置空，用户看不到失败）
        if (curDownload.value?.status != CurrentDownloadInfo.STATUS_FAIL_DOWNLOAD) {
            curDownload.value = null
        }
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
        // 先按 (页面目录, 剧集目录) 精确匹配；匹配不到再只按剧集目录匹配 ——
        // 同一分组里可能混着"已发布（相对路径）"和"未发布（绝对路径）"两种身份的条目，
        // 页面目录字符串不一定对得上，但 entryDirPath 是唯一的，只按它兜底更可靠
        val index = downloadList.indexOfFirst {
            it.pageDirPath == pageDirPath && it.entryDirPath == entryDirPath
        }.takeIf { it >= 0 } ?: downloadList.indexOfFirst { it.entryDirPath == entryDirPath }
        if (index != -1) {
            // 如果为当前下载任务则先停止任务
            val entryAndPathInfo = downloadList[index]
            if (curDownload.value?.id == entryAndPathInfo.entry.key) {
                cancelDownload(currentTaskId)
            }
        }
        // 公共（相对身份）和私有（绝对身份）两边都要删：发布成功但删私有失败、发布到一半失败
        // 都会让同一集在两边各留一份，用户点删除就是"这一集不要了"
        DownloadFileResolver.deleteDir(this, entryDirPath)
        // 页面目录空了顺手删掉（公共目录那边空目录会自动消失，MediaStore 不记录空目录）
        val pageDir = DownloadFileResolver.privateFile(this, pageDirPath)
        if (pageDir.isDirectory && pageDir.listFiles()?.isEmpty() == true) {
            pageDir.delete()
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
        val info = curBiliDownloadEntryAndPathInfo ?: return
        val entry = info.entry
        entry.downloaded_bytes = entry.total_bytes
        entry.is_completed = true
        entry.total_time_milli = (curDownload.value?.length ?: 0L) * 1000
        updateBiliDownloadEntryJson(info.entryDirPath, entry)
        downloadListVersion.value++
        // ★ 一集全部下完：整目录发布到公共目录 Download/BiliMiao/（Android 10+ 不需要任何权限）。
        //   发布成功才删私有副本、身份换成相对路径；任何一个文件失败就保留私有副本、下次启动再试。
        //   放在 nextDownload() 之前同步做：发布是本地 IO，串行执行不会和下一个任务的写入抢文件。
        publishEntryToPublic(info)
        // 顺带把之前发布失败/当时没存储权限的条目再试一次（"下次完成时再试"）；
        // 它跑在独立协程里，不阻塞当前任务的收尾和队列里的下一个任务
        publishPendingEntries()
        curDownload.value = null
        curMediaFile = null
        curMediaFileInfo = null
        downloadManager = null
        audioDownloadManager = null
        nextDownload()
    }

    /**
     * 把一集的私有目录整目录发布到公共目录；成功则删私有副本、把记录身份改成相对路径。
     *
     * 失败（或公共目录不可用）时**什么都不动**：私有副本还在、身份还是绝对路径，
     * 下次启动/下次完成时 [publishPendingEntries] 会再试一次 —— 绝不能因为发布失败让用户文件消失。
     */
    private fun publishEntryToPublic(info: BiliDownloadEntryAndPathInfo?) {
        if (info == null) return
        val entryDirPath = info.entryDirPath
        // 只有私有目录存在才需要发布（发布成功后会把它删掉，下次进来这里直接跳过）
        val entryDir = DownloadFileResolver.privateFile(this, entryDirPath)
        if (!entryDir.isDirectory) return
        if (!DownloadStoragePolicy.canUsePublicStorage(this)) {
            logToFile("publish SKIP: 公共目录不可用（老系统未授权存储权限），保留私有副本 $entryDirPath")
            return
        }
        val relDir = DownloadFileResolver.relativeDirOf(this, entryDirPath)
        if (relDir == null) {
            logToFile("publish SKIP: 目录不在私有工作目录内，无法映射公共相对路径 $entryDirPath")
            return
        }
        if (!publishingEntries.add(entryDirPath)) return // 同一集已在发布中
        try {
            val ok = DownloadPublisher.publishEntryDir(this, entryDir, relDir)
            logToFile("publish ${if (ok) "OK" else "FAILED"}: $entryDirPath -> ${PublicDownloadStore.relativePathOf(relDir)}")
            if (!ok) return
            // 发布成功（entry.json 已确认可读）才删私有副本；删失败也只是留一份冗余，不会丢文件
            val pageDir = entryDir.parentFile
            if (!entryDir.deleteRecursively()) {
                logToFile("publish: 私有副本删除失败，保留 ${entryDir.absolutePath}")
            }
            if (pageDir != null && pageDir.listFiles()?.isEmpty() == true) {
                pageDir.delete()
            }
            changeEntryIdentity(info, relDir)
        } catch (e: Exception) {
            e.printStackTrace()
            logToFile("publish EXCEPTION: ${e.message}")
        } finally {
            publishingEntries.remove(entryDirPath)
        }
    }

    /** 发布成功：把记录身份从"私有绝对路径"换成"公共相对路径"（下次启动就是从公共目录重建出来的相对身份） */
    private fun changeEntryIdentity(info: BiliDownloadEntryAndPathInfo, relDir: String) {
        val index = downloadList.indexOfFirst {
            it.entryDirPath == info.entryDirPath && it.entry.key == info.entry.key
        }
        if (index < 0) return
        downloadList[index] = info.copy(
            entryDirPath = relDir,
            // "s_123/1-1" → 页面目录 "s_123"
            pageDirPath = relDir.substringBefore('/'),
        )
        // 同一集万一列表里有两条（重复点下载），发布完成后合并成一条
        downloadList = dedupeEntries(downloadList).toMutableList()
        downloadListVersion.value++
    }

    /**
     * 服务启动时补发布：上次发布失败、当时没有存储权限、发布成功但删私有失败的，
     * 在这里重试一次（只重试 `is_completed` 的，正在下载的那条绝不动）。
     */
    private fun publishPendingEntries() = launch {
        val pending = downloadList.filter { it.entry.is_completed }
        if (pending.isEmpty()) return@launch
        logToFile("publishPending: 检查 ${pending.size} 条已完成记录")
        pending.forEach { info ->
            // 正在下载的那条不能发布（文件还在写）
            if (curDownload.value?.id == info.entry.key) return@forEach
            publishEntryToPublic(info)
        }
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
        // 队列已空（当前任务已完成/失败/暂停）：退出前台状态，否则常驻通知会一直显示“正在下载”
        stopForegroundCompat()
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
                val type1MediaInfo = curMediaFileInfo as? BiliDownloadMediaFileInfo.Type1
                if (type1MediaInfo != null) {
                    // Type1 多分片：分母写全部分片之和、分子写已完成分片累计，
                    // 只写当前分片的 size 会让 entry.json 里的进度比例错乱
                    entryAndPathInfo.entry.total_bytes = type1MediaInfo.segment_list.sumOf { it.bytes }
                    entryAndPathInfo.entry.downloaded_bytes = completedSegmentBytes
                } else {
                    entryAndPathInfo.entry.total_bytes = info.size
                }
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
            // 原来这里直接 return，任务再没有任何回调 → 永远停在“正在下载”的静默死状态。
            // 按失败处理：发错误通知并结束当前任务，让等待队列继续。
            logToFile("onTaskComplete: progress=${info.progress} < size=${info.size}, treat as FAILED")
            onTaskError(info, IOException("下载未完成：${info.progress}/${info.size}"))
            return
        }
        // Type1 多分片：还有分片未下载则继续，全部完成则合并为单文件
        if (curMediaFileInfo is BiliDownloadMediaFileInfo.Type1) {
            val entryInfo = curBiliDownloadEntryAndPathInfo
            if (entryInfo != null && pendingSegments.isNotEmpty()) {
                val mediaInfo = curMediaFileInfo as BiliDownloadMediaFileInfo.Type1
                completedSegmentBytes += info.progress // 本分片已完成，累计进整体进度
                startNextSegment(
                    info,
                    mediaInfo,
                    writeVideoDir(entryInfo),
                    mediaInfo.httpHeader(),
                )
                return
            }
            if (entryInfo != null) {
                mergeSegments(
                    writeVideoDir(entryInfo),
                    curMediaFileInfo as BiliDownloadMediaFileInfo.Type1,
                )
            }
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
        downloadNotify.showErrorStatusNotify(info)
        stopDownload()
    }

    private fun updateBiliDownloadEntryJson(
        entryDirPath: String,
        entry: BiliDownloadEntryInfo,
    ) {
        // 保存视频信息（entry.json 永远写私有工作目录：相对身份也映射回私有目录，
        // 绝不按相对路径在当前进程工作目录下创建字面量文件）
        val entryDir = writeEntryDir(entryDirPath)
        if (!entryDir.exists()) entryDir.mkdirs()
        val entryJsonFile = File(entryDir, "entry.json")
        val entryJsonStr = MiaoJson.toJson(entry)
        entryJsonFile.writeText(entryJsonStr)
    }

    /** 写分片/合并用的视频目录（私有工作目录里的 `<entryDir>/<type_tag>`） */
    private fun writeVideoDir(entryInfo: BiliDownloadEntryAndPathInfo): File =
        File(writeEntryDir(entryInfo.entryDirPath), entryInfo.entry.type_tag ?: "")

    /**
     * 下载工作目录：**永远是应用私有目录**（`.../files/BiliMiao`）。
     * 下载过程（断点续传、多分片临时文件、合并）全部写这里，一集下完后再整目录发布到公共目录。
     * 为什么不让下载器直接写公共目录：MediaStore 的流不支持可靠的随机写/续传/rename，
     * 而且发布失败时私有副本是用户文件的最后安全网。
     */
    fun getWorkPath(): String {
        val privateDir = DownloadFileResolver.workDir(this)
        if (!privateDir.exists()) {
            privateDir.mkdirs()
        }
        // .nomedia：防止系统图库/媒体扫描把下载的视频、弹幕也算进去
        val nomedia = File(privateDir, ".nomedia")
        if (!nomedia.exists()) {
            runCatching { nomedia.createNewFile() }
        }
        return privateDir.absolutePath
    }

    /**
     * UI 展示用的"下载保存位置"（人类可读路径）。
     * 与 [getWorkPath] 严格区分：这里只回答"文件最终会放在哪"，**不创建目录、不写测试文件**
     * （以前每次打开下载页都 mkdir + 写探针文件，纯属多余 IO）。
     * 公共目录可用 → `/sdcard/Download/BiliMiao`（Android 10+ 真实存在且无需权限）；
     * 老系统用户拒绝存储权限 / 环境不支持 → 退回私有目录（提示用户卸载会丢）。
     */
    fun getDownloadPath(): String {
        return if (DownloadStoragePolicy.canUsePublicStorage(this)) {
            DownloadStoragePolicy.publicDirPath()
        } else {
            // 纯路径计算，不创建目录（展示而已）
            DownloadFileResolver.workDir(this).absolutePath
        }
    }

    /**
     * 公共目录不可用（老系统用户明确拒绝了存储权限）时提示一次实际保存位置。
     * 以前这段提示在 getDownloadPath() 里，改造后工作目录永远是私有目录，
     * 只有"真的发布不了"才需要提醒用户"卸载会丢"。
     */
    private fun warnPrivateFallbackOnce() {
        if (DownloadStoragePolicy.canUsePublicStorage(this)) return
        // 还没问过用户（没有拒绝记录）就先不提示：授权弹窗马上会来，授权成功后
        // 之前下好的也会补发布到公共目录，提前吓唬用户没必要
        if (!DownloadStoragePolicy.isLegacyPermissionDenied(this)) return
        if (warnedPrivateFallback) return
        warnedPrivateFallback = true
        val privatePath = DownloadFileResolver.workDir(this).absolutePath
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                this,
                "无存储权限，视频只能保存到应用私有目录：${privatePath}（卸载应用会丢失）",
                android.widget.Toast.LENGTH_LONG
            ).show()
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
        // 新任务一律建在私有工作目录（下完再整目录发布公共目录），公共目录在 Android 10+ 不能用文件路径写
        val downloadDir = File(getWorkPath(), dirName)
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