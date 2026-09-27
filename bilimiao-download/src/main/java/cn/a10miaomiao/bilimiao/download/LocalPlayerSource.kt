package cn.a10miaomiao.bilimiao.download

import android.app.Activity
import android.content.Context
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

    private val entry by lazy { getEntryFileInfo() }

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

    // TODO AI 原声翻译：暂时关闭（原来这里还有个 language: String? 参数，本地文件用不到）。
    override suspend fun getPlayerUrl(
        quality: Int,
        fnval: Int,
    ): PlayerSourceInfo {
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

        // 清晰度目录名（老数据可能只有 video_quality，两者都没有就没法定位文件）
        val typeTag = entry.type_tag ?: entry.video_quality?.toString() ?: return emptyPlayerSourceInfo
        // ★ 读取全部走 DownloadFileResolver：相对路径身份（已发布到公共目录）时从 MediaStore 拿 content://，
        //   绝对路径身份（还在私有目录/老数据）时直接用 File；以前这里 File(entryDirPath, ...) 拼相对路径读不到文件
        val videoIndexJson = DownloadFileResolver.readText(activity, entryDirPath, "$typeTag/index.json")
            ?: return emptyPlayerSourceInfo
        // 不能用 entry.media_type 判定：所有创建点都写 media_type = 2，
        // Type1(durl 多分片) 会被当成 Type2 解析出空 video 列表，随后 video[0] 越界崩溃。
        // 改为按 index.json 内容判定（Type1 一定带 segment_list）；旧数据 media_type = 1 也认。
        val type1MediaInfo = try {
            MiaoJson.fromJson<BiliDownloadMediaFileInfo.Type1>(videoIndexJson)
        } catch (e: Exception) {
            null
        }
        if (type1MediaInfo != null && (type1MediaInfo.segment_list.isNotEmpty() || entry.media_type == 1)) {
            // 优先合并后的 0.<format>；format 对不上时退回目录里实际存在的 0.* 文件
            val names = DownloadFileResolver.listNames(activity, entryDirPath, typeTag)
            val videoName = names.firstOrNull { it == "0." + type1MediaInfo.format }
                ?: names.firstOrNull { it.startsWith("0.") }
                ?: return emptyPlayerSourceInfo
            val url = DownloadFileResolver.uri(activity, entryDirPath, "$typeTag/$videoName")?.toString()
                ?: return emptyPlayerSourceInfo
            return PlayerSourceInfo().also {
                it.url = url
                it.quality = 0
                it.acceptList = acceptList
                it.duration = duration
                it.lastPlayCid = id
                it.lastPlayTime = savedProgress
            }
        } else {
            val mediaInfo = parseType2(videoIndexJson)
            // 空 video 列表（index.json 损坏/类型识别失败）时返回空源，避免下面的 video[0] 越界崩溃
            val videoStream = mediaInfo.video.firstOrNull() ?: return emptyPlayerSourceInfo
            val url = DownloadFileResolver.uri(activity, entryDirPath, "$typeTag/video.m4s")?.toString()
                ?: return emptyPlayerSourceInfo
            // 音频缺失时只播视频（老数据/单流下载），不硬拼一个不存在的音轨
            val audioUrl = DownloadFileResolver.uri(activity, entryDirPath, "$typeTag/audio.m4s")?.toString()
            if (audioUrl != null) {
                val mergingUrl = "[local-merging]\n$url\n$audioUrl"
                return PlayerSourceInfo().also {
                    it.height = videoStream.height
                    it.width = videoStream.width
                    it.url = mergingUrl
                    it.quality = 0
                    it.acceptList = acceptList
                    it.duration = duration
                    it.lastPlayCid = id
                    it.lastPlayTime = savedProgress
                }
            } else {
                return PlayerSourceInfo().also {
                    it.height = videoStream.height
                    it.width = videoStream.width
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
        // entry.json 同样走 resolver：已发布的一集在公共目录里，私有目录已经没有了
        val entryJson = DownloadFileResolver.readText(activity, entryDirPath, "entry.json")
            ?: throw java.io.FileNotFoundException("entry.json 读取失败：$entryDirPath")
        return MiaoJson.fromJson(entryJson)
    }

    private fun getBiliDanmukuStream(): InputStream? =
        DownloadFileResolver.openInput(activity, entryDirPath, "danmaku.xml")

    override suspend fun getSubtitles(): List<SubtitleSourceInfo> = emptyList()
}
