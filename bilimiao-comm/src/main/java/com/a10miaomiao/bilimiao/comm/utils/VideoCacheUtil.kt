package com.a10miaomiao.bilimiao.comm.utils

import android.content.Context
import java.io.File

/**
 * 视频播放磁盘缓存（media3 `SimpleCache`）的**目录 / 大小 / 清理**。
 *
 * 为什么要有它：缓存目录以前只在播放器（app 模块 `PlayerDelegate2`）那一侧写死一份字符串，
 * 设置页想"清除缓存"就得再抄一遍路径 —— 抄错一处 = 清了个寂寞。
 * 这里做**唯一的路径来源**，播放器与设置页共用同一份。
 *
 * ★清缓存必须"先把进程里活着的 SimpleCache 放掉，再删目录"：那份实例手里攥着
 *   索引（cached_content_index.exi）和一堆 span 文件，边写边删会留下一本对不上的账。
 *   所以留了 [onBeforeClear] 这个接缝：谁建的缓存谁登记释放动作。
 */
object VideoCacheUtil {

    /** 缓存目录名。与播放器建 `SimpleCache` 时用的名字必须一致（改它 = 换一个缓存世界） */
    const val DIR_NAME = "video_cache"

    /**
     * 缓存目录。取法与播放器建 `SimpleCache` 时**逐字一致**：
     * 优先外部 cache 目录，拿不到（未挂载等）退回内部 cacheDir。
     */
    fun dir(context: Context): File =
        File(context.externalCacheDir ?: context.cacheDir, DIR_NAME)

    /**
     * 清除前要执行的"释放活缓存"动作（由播放器登记）。没有则为 null。
     *
     * 为什么用回调、而不是让本工具直接持有 SimpleCache：SimpleCache 在 app 模块
     * （要 media3-datasource），而本工具在 comm 模块（设置页也要用）——
     * 反向依赖会把 media3 拖进 comm。回调是这个方向上最小的接缝。
     */
    @Volatile
    var onBeforeClear: (() -> Unit)? = null

    /** 缓存目录当前占用（字节）。IO 活，别在主线程调。 */
    fun sizeBytes(context: Context): Long = folderSize(dir(context))

    /** 缓存目录当前占用的可读文本（如 "128.50MB"）。IO 活，别在主线程调。 */
    fun sizeText(context: Context): String = formatSize(sizeBytes(context))

    /**
     * 清空缓存目录内容（含索引），返回清掉的字节数。**IO 活，别在主线程调**。
     *
     * 顺序：先释放活缓存（[onBeforeClear]）→ 再量大小 → 最后删。
     * 为什么"量大小"放在释放**之后**：`SimpleCache.release()` 会把索引写回目录，
     * 放前面量会少算这一份。
     * 根目录本身留着（SimpleCache 下次要用就地写回）。
     */
    fun clear(context: Context): Long {
        runCatching { onBeforeClear?.invoke() }
        val freed = sizeBytes(context)
        deleteContents(dir(context))
        return freed
    }

    private fun deleteContents(file: File) {
        val children = file.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) deleteContents(child)
            runCatching { child.delete() }
        }
    }

    private fun folderSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var size = 0L
        file.listFiles()?.forEach { child ->
            size += if (child.isDirectory) folderSize(child) else child.length()
        }
        return size
    }

    /**
     * 字节数 → 可读文本（与「图片缓存」那一行同一口径：B / KB / MB / GB / TB，两位小数）。
     * 用 BigDecimal 而不是 String.format：不受系统区域影响（免得出现 "1,5MB" 这种）。
     */
    fun formatSize(size: Long): String {
        val kiloByte = size / 1024
        if (kiloByte < 1) return size.toString() + "Byte"
        val megaByte = kiloByte / 1024
        if (megaByte < 1) {
            return java.math.BigDecimal(kiloByte.toString())
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "KB"
        }
        val gigaByte = megaByte / 1024
        if (gigaByte < 1) {
            return java.math.BigDecimal(megaByte.toString())
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "MB"
        }
        val teraBytes = gigaByte / 1024
        if (teraBytes < 1) {
            return java.math.BigDecimal(gigaByte.toString())
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "GB"
        }
        return java.math.BigDecimal(teraBytes)
            .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "TB"
    }
}
