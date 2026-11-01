package com.a10miaomiao.bilimiao.comm.utils

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.kongzue.dialogx.dialogs.BottomMenu
import com.a10miaomiao.bilimiao.comm.toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream


class ImageSaveUtil(
    val activity: Activity,
    val imageUrl: String,
) {

    private val menuItems = arrayOf<String>(
        "保存图片",
        "复制链接",
    )

    fun showMemu(context: Context = activity) {
        BottomMenu.show(menuItems)
            .setOnMenuItemClickListener { _, _, index ->
                when(index) {
                    0 -> downloadAndSaveImage()
                    1 -> copyImageUrl()
                }
                false
            }
    }



    /**
     * 复制图片链接到剪切板
     */
    private fun copyImageUrl() {
        val clipboardManager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = ClipData.newPlainText("imageUrl", imageUrl)
        clipboardManager.setPrimaryClip(clipData)
        toast("图片链接已复制到剪切板")
    }

    /**
     * 下载并保存图片
     */
    private fun downloadAndSaveImage() {
        Glide.with(activity)
            .asFile()
            .load(imageUrl)
            .into(object : CustomTarget<File>() {
                override fun onResourceReady(
                    resource: File,
                    transition: Transition<in File>?
                ) {
                    // 直接把下载到的原始文件复制进相册，GIF 等动画格式不会被重新编码
                    saveImage(
                        activity,
                        getFileName(imageUrl),
                        resource,
                    )
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    // 失败走 onLoadFailed（onLoadCleared 是"被清理"）→ 以前点了保存图片失败时完全没反应
                    toast("原图下载失败")
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                }
            })
    }


    companion object {

        /** 保存目录统一放在 Pictures/Bilimiao（DCIM 是相机拍摄专用目录） */
        private const val ALBUM_DIR_NAME = "Bilimiao"

        private const val DEFAULT_FILE_NAME = "image"

        /** 文件名主体保留的最大长度，避免标题过长撑爆文件名 */
        private const val MAX_BASE_NAME_LENGTH = 64

        /** 文件系统 / MediaStore 不接受的字符 */
        private val ILLEGAL_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

        /** 已知图片格式与标准后缀的对应关系，用于纠正文件名后缀 */
        private val EXTENSION_BY_MIME = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/gif" to "gif",
            "image/webp" to "webp",
        )

        /** 保存图片时解析出的最终文件名 / MIME /（位图保存时的）编码格式 */
        private data class SaveImageMeta(
            val fileName: String,
            val mimeType: String,
            val compressFormat: Bitmap.CompressFormat?,
        )

        /**
         * 保存图片（系统相册优先，失败回退应用私有目录）
         */
        fun saveImage(
            activity: Activity,
            fileName: String,
            bitmap: Bitmap
        ) {
            // 相册写入失败（无权限 / 输出流打不开 / 编码失败）时按返回值回退私有目录，
            // 不能只看异常：内部已经吞掉异常，否则失败也会提示"已保存"
            val meta = resolveBitmapMeta(fileName)
            val albumDenied = !canWriteToAlbum(activity)
            val savedName = if (albumDenied) null else saveImageToAlbum(activity, meta, bitmap)
            if (savedName != null) {
                toast("已保存至系统相册，文件名:$savedName")
            } else {
                saveToPrivateDir(activity, meta, bitmap, albumDenied)
            }
        }

        /**
         * 保存图片（原样复制下载好的文件，GIF 等动画格式不会被重新编码）
         */
        fun saveImage(
            activity: Activity,
            fileName: String,
            inputFile: File
        ) {
            // 同：按返回值判断是否回退，避免失败也报"已保存至系统相册"
            val meta = resolveFileMeta(fileName, inputFile)
            val albumDenied = !canWriteToAlbum(activity)
            val savedName = if (albumDenied) null else saveImageToAlbum(activity, meta, inputFile)
            if (savedName != null) {
                toast("已保存至系统相册，文件名:$savedName")
            } else {
                saveToPrivateDir(activity, meta, inputFile, albumDenied)
            }
        }

        // ───────────────────────── 私有目录兜底 ─────────────────────────

        private fun saveToPrivateDir(
            activity: Activity,
            meta: SaveImageMeta,
            bitmap: Bitmap,
            albumDenied: Boolean
        ) {
            try {
                val privateDir = File(activity.getExternalFilesDir(null), "BiliMiao/图片")
                if (!privateDir.exists()) privateDir.mkdirs()
                val outFile = uniqueFile(privateDir, meta.fileName)
                FileOutputStream(outFile).use { output ->
                    if (!bitmap.compress(
                            meta.compressFormat ?: Bitmap.CompressFormat.JPEG,
                            100,
                            output,
                        )
                    ) {
                        throw IOException("图片编码失败")
                    }
                    output.flush()
                }
                toast(privateDirSavedMessage(albumDenied))
            } catch (e2: Exception) {
                e2.printStackTrace()
                toast("保存失败：" + (e2.message ?: e2.toString()))
            }
        }

        private fun saveToPrivateDir(
            activity: Activity,
            meta: SaveImageMeta,
            inputFile: File,
            albumDenied: Boolean
        ) {
            try {
                val privateDir = File(activity.getExternalFilesDir(null), "BiliMiao/图片")
                if (!privateDir.exists()) privateDir.mkdirs()
                val outFile = uniqueFile(privateDir, meta.fileName)
                inputFile.copyTo(outFile)
                toast(privateDirSavedMessage(albumDenied))
            } catch (e2: Exception) {
                e2.printStackTrace()
                toast("保存失败：" + (e2.message ?: e2.toString()))
            }
        }

        private fun privateDirSavedMessage(albumDenied: Boolean): String =
            if (albumDenied) "未授予存储权限，图片已保存至应用私有目录" else "图片已保存至本地"

        // ───────────────────────── 系统相册 ─────────────────────────

        /**
         * 将位图写入系统相册
         * @return 实际保存的文件名（MediaStore 重名时会自动加序号）；失败返回 null
         */
        private fun saveImageToAlbum(
            context: Context,
            meta: SaveImageMeta,
            bitmap: Bitmap
        ): String? {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10 起通过 MediaStore 写入，不需要任何存储权限
                    insertToMediaStore(context, meta) { output ->
                        if (!bitmap.compress(
                                meta.compressFormat ?: Bitmap.CompressFormat.JPEG,
                                90,
                                output,
                            )
                        ) {
                            throw IOException("图片编码失败")
                        }
                    }
                } else if (hasWriteStoragePermission(context)) {
                    // Android 9 及以下写公共目录仍需要存储权限
                    saveToPicturesLegacy(context, meta) { output ->
                        if (!bitmap.compress(
                                meta.compressFormat ?: Bitmap.CompressFormat.JPEG,
                                90,
                                output,
                            )
                        ) {
                            throw IOException("图片编码失败")
                        }
                    }
                } else {
                    // 存储权限已在外层检查，这里防御性兜底
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        /**
         * 将下载好的原始文件写入系统相册
         * @return 实际保存的文件名（MediaStore 重名时会自动加序号）；失败返回 null
         */
        private fun saveImageToAlbum(
            context: Context,
            meta: SaveImageMeta,
            inputFile: File
        ): String? {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    insertToMediaStore(context, meta) { output ->
                        inputFile.inputStream().use { input -> input.copyTo(output) }
                    }
                } else if (hasWriteStoragePermission(context)) {
                    saveToPicturesLegacy(context, meta) { output ->
                        inputFile.inputStream().use { input -> input.copyTo(output) }
                    }
                } else {
                    // 存储权限已在外层检查，这里防御性兜底
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        /**
         * Android 10+：MediaStore 两段式写入
         *
         * 先以 IS_PENDING=1 插入，写完再置为 0，这样写入过程中相册看不到半成品；
         * 写入失败则删除已插入的记录，避免相册里留下 0 字节空图。
         *
         * @return 实际保存的文件名（MediaStore 遇到重名会自动加序号）
         */
        private fun insertToMediaStore(
            context: Context,
            meta: SaveImageMeta,
            writeBody: (OutputStream) -> Unit
        ): String {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, meta.fileName)
                put(MediaStore.Images.Media.MIME_TYPE, meta.mimeType)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM_DIR_NAME"
                )
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法创建媒体库记录")
            try {
                val outputStream = resolver.openOutputStream(uri)
                    ?: throw IOException("无法打开输出流")
                outputStream.use { output ->
                    writeBody(output)
                    output.flush()
                }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            } catch (e: Exception) {
                // 回滚已经插入的半成品记录
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            return queryDisplayName(resolver, uri) ?: meta.fileName
        }

        /** 查询 MediaStore 最终采用的显示名（重名时系统会自动加序号） */
        private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
            resolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

        /**
         * Android 9 及以下：直接写入公共 Pictures 目录，再通知媒体扫描入库
         *
         * @return 实际保存的文件名（重名时自动加序号）
         */
        @Suppress("DEPRECATION")
        private fun saveToPicturesLegacy(
            context: Context,
            meta: SaveImageMeta,
            writeBody: (OutputStream) -> Unit
        ): String {
            if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
                throw IOException("外部存储不可用")
            }
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ALBUM_DIR_NAME,
            )
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("无法创建目录：${dir.absolutePath}")
            }
            val file = uniqueFile(dir, meta.fileName)
            try {
                FileOutputStream(file).use { output ->
                    writeBody(output)
                    output.flush()
                }
                if (file.length() <= 0L) {
                    throw IOException("图片写入失败")
                }
            } catch (e: Exception) {
                // 写入失败时删掉半成品，避免相册里留下 0 字节空图
                runCatching { file.delete() }
                throw e
            }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(meta.mimeType), null)
            return file.name
        }

        /** 重名时在文件名后追加序号，与 MediaStore 的命名策略保持一致 */
        private fun uniqueFile(dir: File, fileName: String): File {
            val baseName = fileName.substringBeforeLast('.', fileName)
            val extension = fileName.substringAfterLast('.', "")
            var file = File(dir, fileName)
            var index = 1
            while (file.exists()) {
                file = File(
                    dir,
                    if (extension.isEmpty()) "$baseName ($index)" else "$baseName ($index).$extension",
                )
                index++
            }
            return file
        }

        // ───────────────────────── 文件名 / 格式解析 ─────────────────────────

        /**
         * 位图保存：格式只能按原文件名后缀决定（位图已解码，拿不到原始字节），默认 JPEG。
         */
        private fun resolveBitmapMeta(fileName: String): SaveImageMeta {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val compressFormat =
                if (extension == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val mimeType = if (compressFormat == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
            val baseName = sanitizeFileName(fileName.substringBeforeLast('.', fileName))
                .take(MAX_BASE_NAME_LENGTH)
            return SaveImageMeta(
                fileName = "$baseName.${EXTENSION_BY_MIME.getValue(mimeType)}",
                mimeType = mimeType,
                compressFormat = compressFormat,
            )
        }

        /**
         * 文件保存：优先按文件头识别真实格式。
         *
         * 网络图片的 URL 末段文件名不一定可信（例如 .jpg 里装的其实是 webp），
         * 只看后缀会让相册出现"后缀与实际内容不符"的图片，因此以文件头为准修正后缀与 MIME。
         */
        private fun resolveFileMeta(fileName: String, inputFile: File): SaveImageMeta {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = sniffImageMimeType(inputFile) ?: getMimeType(extension)
            val baseName = sanitizeFileName(fileName.substringBeforeLast('.', fileName))
                .take(MAX_BASE_NAME_LENGTH)
            val targetExtension = EXTENSION_BY_MIME[mimeType] ?: extension
            return SaveImageMeta(
                fileName = if (targetExtension.isEmpty()) baseName else "$baseName.$targetExtension",
                mimeType = mimeType,
                compressFormat = null,
            )
        }

        /** 按文件头识别图片格式，识别不出来时才回退到后缀判断 */
        private fun sniffImageMimeType(file: File): String? {
            return try {
                FileInputStream(file).use { input ->
                    val header = ByteArray(12)
                    var length = 0
                    while (length < header.size) {
                        val count = input.read(header, length, header.size - length)
                        if (count <= 0) break
                        length += count
                    }
                    sniffImageMimeType(header, length)
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun sniffImageMimeType(bytes: ByteArray, length: Int): String? {
            fun byteAt(index: Int) = bytes[index].toInt() and 0xFF
            fun charAt(index: Int) = bytes[index].toInt()
            return when {
                length >= 3 && byteAt(0) == 0xFF && byteAt(1) == 0xD8 -> "image/jpeg"
                length >= 8 && byteAt(0) == 0x89 &&
                    charAt(1) == 'P'.code && charAt(2) == 'N'.code && charAt(3) == 'G'.code -> "image/png"
                length >= 6 && charAt(0) == 'G'.code && charAt(1) == 'I'.code &&
                    charAt(2) == 'F'.code -> "image/gif"
                length >= 12 && charAt(0) == 'R'.code && charAt(1) == 'I'.code &&
                    charAt(2) == 'F'.code && charAt(3) == 'F'.code &&
                    charAt(8) == 'W'.code && charAt(9) == 'E'.code &&
                    charAt(10) == 'B'.code && charAt(11) == 'P'.code -> "image/webp"
                else -> null
            }
        }

        private fun getMimeType(extension: String): String {
            if (extension.isNotEmpty()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
            }
            return "image/jpeg"
        }

        private fun sanitizeFileName(fileName: String): String =
            ILLEGAL_FILE_NAME_CHARS.replace(fileName, "_").trim().ifBlank { DEFAULT_FILE_NAME }

        /** Android 10 起写媒体库不需要权限；9 及以下写公共目录需要存储权限 */
        private fun canWriteToAlbum(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasWriteStoragePermission(context)

        private fun hasWriteStoragePermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED

        fun getFileName(imageUrl: String): String {
            // 去掉查询串和锚点，避免把 "?x-oss-process=..." 之类拼进文件名
            val path = imageUrl.substringBefore('?').substringBefore('#')
            return path.split("/").last().takeIf { it.isNotBlank() } ?: DEFAULT_FILE_NAME
        }
    }

}
