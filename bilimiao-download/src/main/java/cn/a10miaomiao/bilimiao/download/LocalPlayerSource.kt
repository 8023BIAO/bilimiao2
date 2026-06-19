package cn.a10miaomiao.bilimiao.download

import android.app.Activity
import android.content.Context
import android.net.Uri
import cn.a10miaomiao.bilimiao.download.entry.BiliDownloadEntryInfo
import cn.a10miaomiao.bilimiao.download.entry.BiliDownloadMediaFileInfo
import com.a10miaomiao.bilimiao.comm.apis.PlayerAPI
import com.a10miaomiao.bilimiao.comm.delegate.player.BasePlayerSource
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.PlayerSourceIds
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.PlayerSourceInfo
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.SubtitleSourceInfo
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import master.flame.danmaku.danmaku.loader.android.DanmakuLoaderFactory
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.danmaku.parser.BiliDanmukuParser
import java.io.File
import java.io.InputStream

class LocalPlayerSource(
    val activity: Activity,
    val entryDirPath: String,
    override val id: String,
    override val title: String,
    override val coverUrl: String,
): BasePlayerSource() {

    override val ownerId: String
        get() = ""

    override val ownerName: String
        get() = "本地视频"

    private val entry = getEntryFileInfo()

    private val avid: Long? get() = entry.avid

    /** 从本地读取上次播放进度（存的是秒，转毫秒返回） */
    private fun readSavedProgress(): Long {
        val avid = avid ?: return 0L
        return activity.getPreferences(Context.MODE_PRIVATE)
            .getLong("dl_${avid}_${id}", 0L) * 1000L
    }

    /** 从B站云同步播放进度（playurl API，返回毫秒） */
    private suspend fun fetchCloudProgress(): Long {
        val avid = avid ?: return 0L
        return try {
            val res = BiliApiService.playerAPI.getVideoPalyUrl(
                avid.toString(), id, 64, 4048
            )
            res.last_play_time ?: 0L
        } catch (_: Exception) {
            0L // 断网或未登录，静默忽略
        }
    }

    override suspend fun getPlayerUrl(quality: Int, fnval: Int): PlayerSourceInfo {
        val duration = entry.total_time_milli
        val acceptList = listOf(
            PlayerSourceInfo.AcceptInfo(0, "本地")
        )
        var savedProgress = readSavedProgress()
        // 本地无进度时尝试从B站云同步（用户在线看过的话）
        if (savedProgress <= 0L) {
            savedProgress = fetchCloudProgress()
        }
        val emptyPlayerSourceInfo = PlayerSourceInfo().also {
            it.url = ""
            it.quality = -1
            it.acceptList = acceptList
            it.duration = duration
            it.lastPlayCid = id
            it.lastPlayTime = savedProgress
        }

        val videoDirPath = entryDirPath + "/" + entry.type_tag
        val videoDir = File(videoDirPath)
        if (!videoDir.exists() || !videoDir.isDirectory) {
            return emptyPlayerSourceInfo
        }
        val videoIndexJsonFile = File(videoDirPath, "index.json")
        if (!videoIndexJsonFile.exists()) {
            return emptyPlayerSourceInfo
        }
        val videoIndexJson = videoIndexJsonFile.readText()
        if (entry.media_type == 1) {
            val mediaInfo = MiaoJson.fromJson<BiliDownloadMediaFileInfo.Type1>(videoIndexJson)
            val videoFile = File(
                videoDir, "0" + "." + mediaInfo.format
            )
            if (videoFile.exists()) {
                val url = Uri.fromFile(videoFile).toString()
                return PlayerSourceInfo().also {
                    it.url = url
                    it.quality = 0
                    it.acceptList = acceptList
                    it.duration = duration
                    it.lastPlayCid = id
                    it.lastPlayTime = savedProgress
                }
            } else {
                return emptyPlayerSourceInfo
            }
        } else {
            val mediaInfo = parseType2(videoIndexJson)
            val videoFile = File(videoDir, "video.m4s")
            val audioFile = File(videoDir, "audio.m4s")
            val url = Uri.fromFile(videoFile).toString()
            if (audioFile.exists()) {
                val audioUrl = Uri.fromFile(audioFile).toString()
                val mergingUrl = "[local-merging]\n$url\n$audioUrl"
                return PlayerSourceInfo().also {
                    it.height = mediaInfo.video[0].height
                    it.width = mediaInfo.video[0].width
                    it.url = mergingUrl
                    it.quality = 0
                    it.acceptList = acceptList
                    it.duration = duration
                    it.lastPlayCid = id
                    it.lastPlayTime = savedProgress
                }
            } else {
                return PlayerSourceInfo().also {
                    it.height = mediaInfo.video[0].height
                    it.width = mediaInfo.video[0].width
                    it.url = url
                    it.quality = 0
                    it.acceptList = acceptList
                    it.duration = duration
                    it.lastPlayCid = id
                    it.lastPlayTime = savedProgress
                }
            }
        }
    }

    override fun getSourceIds(): PlayerSourceIds {
        return PlayerSourceIds(
            cid = id,
            sid = entry.season_id ?: "",
            epid = entry.ep?.episode_id?.toString() ?: "",
            aid = avid?.toString() ?: "",
        )
    }

    override suspend fun historyReport(progress: Long) {
        val avid = avid ?: return

        // 方案A：本地缓存（断网兜底）
        activity.getPreferences(Context.MODE_PRIVATE)
            .edit().putLong("dl_${avid}_${id}", progress).apply()

        // 方案C：上报B站（联网时静默同步）
        try {
            MiaoHttp.request {
                url = "https://api.bilibili.com/x/v2/history/report"
                formBody = ApiHelper.createParams(
                    "aid" to avid.toString(),
                    "cid" to id,
                    "progress" to progress.toString(),
                    "realtime" to progress.toString(),
                    "type" to "3"
                )
                method = MiaoHttp.POST
            }.awaitCall()
        } catch (_: Exception) { }
    }

    override suspend fun getDanmakuParser(): BaseDanmakuParser? {
        val inputStream = getBiliDanmukuStream()
        return if (inputStream == null) {
            null
        } else {
            val loader = DanmakuLoaderFactory.create(DanmakuLoaderFactory.TAG_BILI)
            loader.load(inputStream)
            val parser = BiliDanmukuParser()
            val dataSource = loader.dataSource
            parser.load(dataSource)
            parser
        }
    }

    private fun parseType2(json: String): BiliDownloadMediaFileInfo.Type2 {
        return try {
            MiaoJson.fromJson(json)
        } catch (e: Exception) {
            // 兼容旧版bilimiao2，字段名 evideo/audiol → video/audio
            val patched = json
                .replace("\"evideo\"", "\"video\"")
                .replace("\"audiol\"", "\"audio\"")
            MiaoJson.fromJson(patched)
        }
    }

    private fun getEntryFileInfo(): BiliDownloadEntryInfo {
        val entryJsonFile = File(entryDirPath, "entry.json")
        return MiaoJson.fromJson(entryJsonFile.readText())
    }

    private fun getBiliDanmukuStream(): InputStream? {
        val danmakuXMLFile = File(entryDirPath, "danmaku.xml")
        return danmakuXMLFile.inputStream()
    }

    override suspend fun getSubtitles(): List<SubtitleSourceInfo> = emptyList()
}
