package cn.a10miaomiao.bilimiao.download

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * 把"下好的私有剧集目录"整目录发布到公共目录 `Download/BiliMiao/<相对目录>/`。
 *
 * 为什么是"下完再整目录复制"而不是"边下边写公共目录"：断点续传要按已有长度发 RANGE、
 * 分片合并要 append/rename，这些只有本地 File 能做（MediaStore 的 OutputStream 不支持可靠的随机写），
 * 而且复制失败时私有副本还在，是用户文件的安全网。
 */
object DownloadPublisher {

    private const val MIME_VIDEO = "video/mp4"
    private const val MIME_DEFAULT = "application/octet-stream"

    /**
     * 按文件名给 MIME：`.mp4/.m4s` → video/mp4，`audio.m4s` → audio/mp4，其余（json/xml）用默认。
     * 为什么较真：MIME 决定文件管理器/相册怎么归类，也给别的 App 一个正确的类型提示。
     */
    fun mimeOf(displayName: String): String {
        val lower = displayName.lowercase(Locale.ROOT)
        return when {
            // ★★ .m4s 一律用 octet-stream —— 不能用 video/mp4、audio/mp4：
            //   MediaProvider（MediaStore）会"帮你"把文件名补成与 MIME 匹配的扩展名，实测：
            //     video.m4s  + video/mp4  → 存成 video.m4s.mp4
            //     audio.m4s  + audio/mp4  → 存成 audio.m4s.m4a
            //   而播放器是按 video.m4s / audio.m4s 去找文件的 → 找不到 → 黑屏（用户实测）。
            //   用 octet-stream（没有对应扩展名）它就不动名字了。
            lower.endsWith(".m4s") -> MIME_DEFAULT
            lower.endsWith(".mp4") -> MIME_VIDEO
            else -> MIME_DEFAULT
        }
    }

    /**
     * 一集是不是"真的下完了"：entry.json / danmaku.xml / index.json / 媒体文件都得在。
     * 缺任何一样都不发布（宁可继续留在私有目录），避免公共目录里出现一集播不了的半成品。
     */
    fun isReadyToPublish(entryDir: File): Boolean {
        if (!entryDir.isDirectory) return false
        val entryJson = File(entryDir, "entry.json")
        if (!entryJson.isFile || entryJson.length() <= 0L) return false
        if (!File(entryDir, "danmaku.xml").isFile) return false
        var hasIndex = false
        var hasMedia = false
        entryDir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            if (name == "index.json") hasIndex = true
            // Type2 是 video.m4s（可能还有 audio.m4s）；Type1 是多分片合并后的 0.<format>
            if (name == "video.m4s" || name.startsWith("0.")) hasMedia = true
        }
        return hasIndex && hasMedia
    }

    /**
     * 整目录发布，返回 true 表示**每一个文件都发布成功**（有一个失败就返回 false，调用方必须保留私有副本）。
     *
     * 发布顺序有讲究：`entry.json` **最后**发。下载列表是从公共目录里"含 entry.json 的目录"重建的
     * （[PublicDownloadStore.listEntryDirs]），先发媒体文件、最后发 entry.json，中途失败时公共目录里
     * 就没有 entry.json，不会出现"看起来已发布、其实缺文件"的假记录。
     */
    fun publishEntryDir(context: Context, entryDir: File, relativeDir: String): Boolean {
        if (!entryDir.isDirectory || relativeDir.isBlank()) return false
        if (!isReadyToPublish(entryDir)) return false
        val files = entryDir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") } // .nomedia 之类的隐藏文件不用发布
            .sortedBy { if (it.name == "entry.json") 1 else 0 } // entry.json 放最后（见上）
            .toList()
        if (files.isEmpty()) return false
        for (file in files) {
            val sub = file.parentFile?.relativeTo(entryDir)?.path?.replace('\\', '/')?.trim('/')
                ?: return false // 拿不到相对层级就整体放弃，绝不猜路径
            val targetDir = if (sub.isEmpty()) relativeDir else "$relativeDir/$sub"
            if (!PublicDownloadStore.publish(context, file, targetDir, file.name, mimeOf(file.name))) {
                return false
            }
        }
        // 最后再确认一次"公共目录里真的能读到 entry.json"：只有确认了，调用方才敢删私有副本
        return PublicDownloadStore.exists(context, relativeDir, "entry.json")
    }
}
