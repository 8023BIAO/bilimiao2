package cn.a10miaomiao.bilimiao.compose.pages.community.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

/**
 * 评论配图：选图 → 落成可上传的本地文件。
 *
 * 不把 content:// 直接丢给上传接口，原因有三：
 *  1. OkHttp 只认 File / 字节，SAF 的 uri 拿不到稳定的长度与文件名；
 *  2. B 站只收 jpg / png / gif，国产机相册常见 HEIC 必须转码；
 *  3. 失败重试、取消后清理都需要一份自己掌控的副本。
 */
object ReplyImageHelper {

    /** 长边上限：超过就等比缩小（B 站服务端还会再压，客户端只留够用的分辨率） */
    private const val MAX_EDGE = 2560

    /** 单张上限，超过就降质量重压 */
    private const val MAX_BYTES = 4 * 1024 * 1024L

    /** 原样上传的格式：GIF 一压就变静态图，webp 服务端不收，交给服务端处理 */
    private val PASSTHROUGH = setOf("gif")

    /**
     * 把选中的图片复制/转码成 app 私有目录里的一个文件。
     * 失败时抛异常，由调用方提示用户。
     */
    suspend fun prepare(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(context, uri)
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val outDir = File(context.cacheDir, "reply_image").apply { mkdirs() }

        // GIF 原样拷贝，保住动图
        if (ext in PASSTHROUGH) {
            val target = File(outDir, "reply_${UUID.randomUUID()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选图片")
            return@withContext target
        }

        // 先只读尺寸，算好采样率再解码，避免整张大图进内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            error("无法解析所选图片（可能是 HEIC 等不支持的格式）")
        }
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= MAX_EDGE) {
            sample *= 2
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("无法解码所选图片")

        // 按 EXIF 摆正方向：手机竖拍的照片像素其实是横的
        val rotation = readRotation(context, uri)
        val rotated = if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        } else {
            bitmap
        }

        // 采样后仍超长边时再精确缩一次
        val scaled = if (max(rotated.width, rotated.height) > MAX_EDGE) {
            val ratio = MAX_EDGE.toFloat() / max(rotated.width, rotated.height)
            Bitmap.createScaledBitmap(
                rotated,
                (rotated.width * ratio).toInt().coerceAtLeast(1),
                (rotated.height * ratio).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== rotated) rotated.recycle() }
        } else {
            rotated
        }

        val target = File(outDir, "reply_${UUID.randomUUID()}.jpg")
        var quality = 92
        var ok = false
        // 压到 4MB 以内；JPEG 质量阶梯下降，最低 60 还超就只能这么发
        while (quality >= 60) {
            FileOutputStream(target).use { out ->
                ok = scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            if (ok && target.length() in 1..MAX_BYTES) break
            quality -= 12
        }
        scaled.recycle()
        if (!ok) {
            target.delete()
            error("图片压缩失败")
        }
        miaoLogger() debug "评论配图已准备: ${target.name} ${target.length() / 1024}KB"
        target
    }

    private fun readRotation(context: Context, uri: Uri): Float {
        return runCatching {
            @Suppress("DEPRECATION")
            val exif = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                ExifInterface(pfd.fileDescriptor)
            } ?: return 0f
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: (uri.lastPathSegment ?: "image.jpg")
    }
}

/**
 * 评论编辑框里的一张待发图片。
 *
 * ⚠️ uploading/error/uploadedUrl 必须是 Compose 的 observable state：
 * 之前它们是普通字段，上传完成后赋值不会触发重组 —— 表现就是
 * "日志里明明 已上传=1 上传中=0，缩略图却一直转圈"。
 */
class ReplyImageItem(
    val file: File,
) {
    /** 上传成功后的图片地址（服务端返回） */
    var uploadedUrl by mutableStateOf<String?>(null)

    /** 上传中（缩略图转圈） */
    var uploading by mutableStateOf(false)

    /** 上传失败原因（缩略图变红 + 点击重试）；null = 无错误 */
    var error by mutableStateOf<String?>(null)

    var uploadedSizeKb by mutableStateOf(0)
    var uploadedWidth by mutableStateOf(0)
    var uploadedHeight by mutableStateOf(0)

    val uploaded: Boolean get() = uploadedUrl != null
    val failed: Boolean get() = error != null && !uploading
}

/** 供 Compose 观察的待发图片列表 */
class ReplyImageList {
    var items by mutableStateOf<List<ReplyImageItem>>(emptyList())
        private set

    fun add(item: ReplyImageItem) {
        items = items + item
    }

    fun remove(item: ReplyImageItem) {
        runCatching { item.file.delete() }
        items = items - item
    }

    /** 发送成功/关闭弹窗时清空并删掉临时文件 */
    fun clear() {
        items.forEach { runCatching { it.file.delete() } }
        items = emptyList()
    }
}
