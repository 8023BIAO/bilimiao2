package cn.a10miaomiao.bilimiao.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 公共下载目录 **Download/BiliMiao** 的读写层。
 *
 * 为什么要它：Android 10 起系统不允许 App 用文件路径直接往 `/sdcard/Download/` 写东西
 * （旧的 `Environment.getExternalStoragePublicDirectory()` + `File` 写法在 Android 10+ 必然失败，
 * 所以之前只能退回应用私有目录 `/sdcard/Android/data/<包名>/files/BiliMiao`，卸载就丢）。
 * MediaStore 是官方给的"无权限写公共目录"通道 —— 和本项目保存截图/图片的
 * [com.a10miaomiao.bilimiao.comm.utils.ImageSaveUtil] 是同一套机制（那里写 DCIM/Bilimiao，这里写 Download/BiliMiao）。
 *
 * 两条实现路径：
 *  - **Android 10（API 29）及以上**：[MediaStore.Downloads] 插入条目 + `openOutputStream` 写入，
 *    **不需要任何存储权限**；`IS_PENDING=1` 让半成品对其他 App 不可见，写完再置 0。
 *  - **Android 9（API 28）及以下**：没有 MediaStore.Downloads，退回直接文件写入，
 *    需要 `WRITE_EXTERNAL_STORAGE`（manifest 里已声明并限制 `maxSdkVersion=28`）。
 *
 * 约定：对外一律用**相对路径**（例如 `"某视频/1-1"` 表示 `Download/BiliMiao/某视频/1-1/`），
 * 不再把绝对路径写进下载记录 —— 绝对路径在 Android 10+ 上根本不存在。
 */
object PublicDownloadStore {

    /** 公共目录下的根目录名：Download/BiliMiao */
    const val ROOT = "BiliMiao"

    /** 元数据文件（entry.json / index.json 等非媒体文件）统一用这个 MIME */
    private const val MIME_BINARY = "application/octet-stream"

    private fun mediaStoreMode(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** 相对目录 → MediaStore 的 RELATIVE_PATH，例如 "视频A/1-1" → "Download/BiliMiao/视频A/1-1" */
    fun relativePathOf(relativeDir: String): String {
        val clean = relativeDir.replace('\\', '/').trim('/')
        return if (clean.isEmpty()) "${Environment.DIRECTORY_DOWNLOADS}/$ROOT"
        else "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$clean"
    }

    /** 相对目录 → Android 9 及以下使用的真实目录 */
    private fun legacyDirOf(relativeDir: String): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val clean = relativeDir.replace('\\', '/').trim('/')
        return if (clean.isEmpty()) File(base, ROOT) else File(File(base, ROOT), clean)
    }

    // ───────────────────────── 写入 ─────────────────────────

    /**
     * 把一个**已经下好的私有文件**发布到公共目录（复制）。
     * @return 成功返回 true；失败返回 false（调用方应保留私有副本，别把用户的文件弄丢）
     */
    fun publish(context: Context, src: File, relativeDir: String, displayName: String, mime: String = MIME_BINARY): Boolean {
        if (!src.exists() || !src.isFile) return false
        return try {
            if (mediaStoreMode()) publishViaMediaStore(context, src, relativeDir, displayName, mime)
            else publishViaLegacyFile(src, relativeDir, displayName)
        } catch (e: Exception) {
            false
        }
    }

    private fun publishViaMediaStore(context: Context, src: File, relativeDir: String, displayName: String, mime: String): Boolean {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // 同名文件已存在（重复下载/断点续传重来）→ 先删掉，避免出现 "xxx (1).mp4" 这种副本
        delete(context, relativeDir, displayName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathOf(relativeDir))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { input -> input.copyTo(out, DEFAULT_BUFFER) }
            } ?: return false
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            // 写失败：把半成品条目删掉，别在用户的下载目录里留垃圾
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    private fun publishViaLegacyFile(src: File, relativeDir: String, displayName: String): Boolean {
        val dir = legacyDirOf(relativeDir)
        if (!dir.exists() && !dir.mkdirs()) return false
        writeNoMedia(dir)
        val target = File(dir, displayName)
        src.inputStream().use { input ->
            target.outputStream().use { out -> input.copyTo(out, DEFAULT_BUFFER) }
        }
        return target.exists() && target.length() == src.length()
    }

    /**
     * 直接以"可追加写"的方式打开公共目录里的文件（给下载器边下边写用）。
     * 返回 null 表示公共目录不可写（调用方应退回私有目录）。
     */
    fun openOutput(context: Context, relativeDir: String, displayName: String, append: Boolean, mime: String = MIME_BINARY): OutputStream? {
        return try {
            if (mediaStoreMode()) {
                val resolver = context.contentResolver
                val uri = findUri(context, relativeDir, displayName) ?: run {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathOf(relativeDir))
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                } ?: return null
                resolver.openOutputStream(uri, if (append) "wa" else "rwt")
            } else {
                val dir = legacyDirOf(relativeDir)
                if (!dir.exists() && !dir.mkdirs()) return null
                writeNoMedia(dir)
                FileOutputStream(File(dir, displayName), append)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ───────────────────────── 读取 ─────────────────────────

    /** 查找文件 → Uri（私有目录里也可能有：调用方自己决定优先看哪边）。找不到返回 null */
    fun findUri(context: Context, relativeDir: String, displayName: String): Uri? {
        return try {
            if (mediaStoreMode()) findMediaStoreUri(context, relativeDir, displayName)
            else File(legacyDirOf(relativeDir), displayName).takeIf { it.isFile }?.let { Uri.fromFile(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun exists(context: Context, relativeDir: String, displayName: String): Boolean = findUri(context, relativeDir, displayName) != null

    fun openInput(context: Context, relativeDir: String, displayName: String): InputStream? {
        return try {
            val uri = findUri(context, relativeDir, displayName) ?: return null
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }
    }

    fun readText(context: Context, relativeDir: String, displayName: String): String? {
        return try {
            openInput(context, relativeDir, displayName)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 文件名候选：MediaProvider 会按 MIME 给文件补扩展名（video.m4s → video.m4s.mp4），
     * 所以查不到原名时按常见后缀再试几个 —— 这样"以前发布出去、名字被改过"的文件也能正常播放/删除。
     */
    private fun nameVariants(displayName: String): List<String> {
        val variants = linkedSetOf(displayName)
        for (ext in listOf("mp4", "m4a", "m4s", "mp3", "aac", "wav")) {
            variants.add("$displayName.$ext")
        }
        return variants.toList()
    }

    private fun findMediaStoreUri(context: Context, relativeDir: String, displayName: String): Uri? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val want = relativePathOf(relativeDir).trimEnd('/')
        for (name in nameVariants(displayName)) {
            findMediaStoreUriExact(context, collection, want, name)?.let { return it }
        }
        return null
    }

    private fun findMediaStoreUriExact(
        context: Context,
        collection: Uri,
        want: String,
        displayName: String,
    ): Uri? {
        // 不用 RELATIVE_PATH 做 selection：不同 ROM 对它的匹配规则不一致（结尾斜杠/前缀匹配都有坑），
        // 只按 DISPLAY_NAME 查、再在代码里比对相对路径，稳定但要求文件名不能太多 —— entry.json/
        // index.json/danmaku.xml 这些都很少，媒体文件是精确名字（video.m4s 等），量可控。
        context.contentResolver.query(
            collection,
            arrayOf(android.provider.BaseColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val rel = (cursor.getString(1) ?: "").trimEnd('/')
                if (rel == want) {
                    return ContentUris.withAppendedId(collection, id)
                }
            }
        }
        return null
    }

    // ───────────────────────── 列举 / 删除 ─────────────────────────

    /**
     * 列出公共目录下所有 `entry.json`（下载列表就是靠它重建的：磁盘即真相，没有数据库）。
     * @return 相对目录列表，例如 ["某视频/1-1", ...]
     */
    fun listEntryDirs(context: Context): List<String> {
        return try {
            if (mediaStoreMode()) {
                val prefix = relativePathOf("")
                val result = mutableListOf<String>()
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf("entry.json"),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val rel = (cursor.getString(0) ?: "").trimEnd('/')
                        if (rel.startsWith("$prefix/")) {
                            result.add(rel.removePrefix("$prefix/"))
                        }
                    }
                }
                result
            } else {
                legacyDirOf("").listFiles()?.filter { it.isDirectory }?.flatMap { page ->
                    page.listFiles()?.filter { it.isDirectory }?.filter { File(it, "entry.json").isFile }
                        ?.map { "${page.name}/${it.name}" } ?: emptyList()
                } ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** MediaStore 里的一行：id + 这一行所在目录（RELATIVE_PATH 去掉结尾斜杠）+ 显示名 */
    private class Row(val id: Long, val rel: String, val name: String)

    /**
     * 一次查出 Downloads 集合里的所有行。
     *
     * 为什么不用 `RELATIVE_PATH = ?` 做 selection：不同 ROM 对它的匹配规则不一致（结尾斜杠、前缀匹配
     * 都有坑），全查回来在代码里比对最稳。下载目录里的文件数可控（一集几个文件），开销可以接受。
     */
    private fun queryRows(context: Context): List<Row> {
        val rows = mutableListOf<Row>()
        try {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.BaseColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add(
                        Row(
                            id = cursor.getLong(0),
                            name = cursor.getString(1) ?: "",
                            rel = (cursor.getString(2) ?: "").trimEnd('/'),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // 失败就按"查不到/删不掉"处理：这里是尽力而为的路径，发布版不留日志（要排查用 debug 包）
        }
        return rows
    }

    /** 列出某个相对目录下所有文件名（只算直接放在这个目录里的行） */
    fun listNames(context: Context, relativeDir: String): List<String> {
        if (!mediaStoreMode()) {
            return try {
                legacyDirOf(relativeDir).listFiles()?.map { it.name } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        val want = relativePathOf(relativeDir).trimEnd('/')
        return queryRows(context).filter { it.rel == want }.map { it.name }
    }

    fun delete(context: Context, relativeDir: String, displayName: String): Boolean {
        return try {
            if (mediaStoreMode()) {
                val uri = findMediaStoreUri(context, relativeDir, displayName) ?: return false
                context.contentResolver.delete(uri, null, null) > 0
            } else {
                File(legacyDirOf(relativeDir), displayName).delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 按行 id 删一条（删不动/没权限都当失败，不抛异常） */
    private fun deleteRowById(context: Context, id: Long): Boolean {
        return try {
            context.contentResolver.delete(
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                null,
                null,
            ) > 0
        } catch (e: Exception) {
            // 失败就按"查不到/删不掉"处理：这里是尽力而为的路径，发布版不留日志（要排查用 debug 包）
            // ⚠️ 这个 catch 处在 `return try` 的**返回值位置**，必须给值：只留注释会把 try 表达式推成 Any，
            //    报 "Return type mismatch: expected 'Boolean', actual 'Any'"（PC 编译时抓到过）
            false
        }
    }

    /**
     * 删掉一个相对目录及它**下面所有子目录**里的全部文件；返回删掉的条目数。
     *
     * ★ 必须递归：一集的媒体文件在下级目录里（`<剧集目录>/<清晰度 tag>/video.m4s`），
     *   只删"直接放在剧集目录里的文件"会把整集视频留在公共目录里（用户实测："删不干净"）。
     */
    fun deleteDir(context: Context, relativeDir: String): Int {
        val clean = relativeDir.replace('\\', '/').trim('/')
        if (clean.isEmpty()) return 0 // 根目录永远不删（异常路径进来的兜底）
        if (!mediaStoreMode()) {
            val dir = legacyDirOf(clean)
            val count = dir.listFiles()?.size ?: 0
            dir.deleteRecursively()
            return count
        }
        val want = relativePathOf(clean)
        val prefix = "$want/"
        // MediaStore 目录行的 RELATIVE_PATH 记的是"父目录"、DISPLAY_NAME 才是目录名，所以：
        //   rel == want       → 直接放在这一集里的文件 + 第一层子目录行（如 "16"）
        //   rel 以 want/ 开头 → 子目录里的文件 + 更深的目录行
        // 按目录深度倒序删：先删深层文件、再删它们的目录行 —— 目录非空时 MediaProvider 会拒绝删目录行
        val targets = queryRows(context)
            .filter { it.rel == want || it.rel.startsWith(prefix) }
            .sortedByDescending { row -> row.rel.count { c -> c == '/' } }
        var count = 0
        for (row in targets) {
            if (deleteRowById(context, row.id)) count++
        }
        // 剧集目录自己那一行（rel = 页面目录、名字 = 剧集目录名）
        deleteDirRow(context, clean)
        // 页面目录里没有别的剧集了 → 一并删掉，别留 "某视频/1-1" 删完还剩 "某视频" 空壳
        val parent = clean.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && !hasRowsUnder(context, parent)) deleteDirRow(context, parent)
        // 兜底：部分 ROM 不许普通 App 删目录行，那就用文件接口把已经是空的目录删掉
        //（删不动就留个空文件夹，不影响播放；绝不能因为删不干净去动用户别的文件）
        removeEmptyDirsOnDisk(clean)
        return count
    }

    /** 这个目录下还有没有行（含子目录里的文件和子孙目录行） */
    private fun hasRowsUnder(context: Context, relativeDir: String): Boolean {
        val want = relativePathOf(relativeDir.trim('/')).trimEnd('/')
        return queryRows(context).any { it.rel == want || it.rel.startsWith("$want/") }
    }

    /** 删"目录自己"那一行：它的 RELATIVE_PATH 是父目录、DISPLAY_NAME 是目录名 */
    private fun deleteDirRow(context: Context, relativeDir: String): Boolean {
        val clean = relativeDir.replace('\\', '/').trim('/')
        if (clean.isEmpty()) return false
        val parentRel = relativePathOf(clean.substringBeforeLast('/', "")).trimEnd('/')
        val name = clean.substringAfterLast('/')
        val row = queryRows(context).firstOrNull { it.rel == parentRel && it.name == name } ?: return false
        return deleteRowById(context, row.id)
    }

    /** 目录行删不掉时的兜底：把磁盘上已经是空的目录删掉（根目录 Download/BiliMiao 绝不动） */
    private fun removeEmptyDirsOnDisk(relativeDir: String) {
        try {
            val root = legacyDirOf("")
            val dir = legacyDirOf(relativeDir)
            if (!dir.exists() || dir.canonicalFile == root.canonicalFile) return
            dir.walkBottomUp().forEach { file ->
                if (file.isDirectory && file.listFiles()?.isEmpty() == true) file.delete()
            }
            val page = dir.parentFile
            if (page != null && page.canonicalFile != root.canonicalFile && page.listFiles()?.isEmpty() == true) {
                page.delete()
            }
        } catch (e: Exception) {
            // 失败就按"查不到/删不掉"处理：这里是尽力而为的路径，发布版不留日志（要排查用 debug 包）
        }
    }

    // ───────────────────────── 小工具 ─────────────────────────

    /** 防止系统图库/媒体扫描把下载的视频/弹幕也算进去（老系统才需要；新系统的 Download 集合本来就不进图库） */
    private fun writeNoMedia(dir: File) {
        runCatching {
            val nomedia = File(dir, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()
        }
    }

    private const val DEFAULT_BUFFER = 64 * 1024
}
