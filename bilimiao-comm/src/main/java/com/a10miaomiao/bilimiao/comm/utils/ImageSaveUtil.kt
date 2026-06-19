package com.a10miaomiao.bilimiao.comm.utils

import android.app.Activity
import android.content.*
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.kongzue.dialogx.dialogs.BottomMenu
import com.a10miaomiao.bilimiao.comm.toast
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel


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
                    saveImage(
                        activity,
                        getFileName(imageUrl),
                        resource,
                    )
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    toast("原图下载失败")
                }
            })
    }


    companion object {

        /**
         * 保存图片（MediaStore优先，失败回退私有目录）
         */
        fun saveImage(
            activity: Activity,
            fileName: String,
            bitmap: Bitmap
        ) {
            try {
                saveImageToAlbum(activity, fileName, bitmap)
                toast("已保存至系统相册，文件名:${fileName}")
            } catch (e: Exception) {
                saveToPrivateDir(activity, fileName, bitmap)
            }
        }

        fun saveImage(
            activity: Activity,
            fileName: String,
            inputFile: File
        ) {
            try {
                saveImageToAlbum(activity, fileName, inputFile)
                toast("已保存至系统相册，文件名:${fileName}")
            } catch (e: Exception) {
                saveToPrivateDir(activity, fileName, inputFile)
            }
        }

        private fun saveToPrivateDir(activity: Activity, fileName: String, bitmap: Bitmap) {
            try {
                val privateDir = File(activity.getExternalFilesDir(null), "BiliMiao/图片")
                if (!privateDir.exists()) privateDir.mkdirs()
                val outFile = File(privateDir, fileName)
                if (outFile.exists()) {
                    toast("图片已存在")
                    return
                }
                outFile.writeBitmap(bitmap)
                toast("图片已保存至本地")
            } catch (e2: Exception) {
                e2.printStackTrace()
                toast("保存失败：" + (e2.message ?: e2.toString()))
            }
        }

        private fun saveToPrivateDir(activity: Activity, fileName: String, inputFile: File) {
            try {
                val privateDir = File(activity.getExternalFilesDir(null), "BiliMiao/图片")
                if (!privateDir.exists()) privateDir.mkdirs()
                val outFile = File(privateDir, fileName)
                if (outFile.exists()) {
                    toast("图片已存在")
                    return
                }
                inputFile.copyTo(outFile)
                toast("图片已保存至本地")
            } catch (e2: Exception) {
                e2.printStackTrace()
                toast("保存失败：" + (e2.message ?: e2.toString()))
            }
        }


        /**
         * 通知相册
         */
        private fun notifyPhoto(context: Context, file: File) {
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            val uri = Uri.fromFile(file)
            intent.data = uri
            context.sendBroadcast(intent)
        }

        /**
         * 保存图片
         */
        private fun File.writeBitmap(data: Bitmap) {
            FileOutputStream(this).use {
                data.compress(getImageFormat(name).first, 100, it)
                it.flush()
            }
        }

        /**
         * 将文件保存到公共的媒体文件夹
         */
        private fun saveImageToAlbum(
            context: Context,
            fileName: String,
            bitmap: Bitmap
        ) {
            try {
                val imageFormat = getImageFormat(fileName)
                //设置保存参数到ContentValues中
                val contentValues = ContentValues()
                //设置文件名
                contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, fileName)

                //android Q中不再使用DATA字段，而用RELATIVE_PATH代替
                //RELATIVE_PATH是相对路径不是绝对路径
                //DCIM是系统文件夹，关于系统文件夹可以到系统自带的文件管理器中查看，不可以写没存在的名字
                contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Bilimiao")
                //设置文件类型
                contentValues.put(MediaStore.Images.Media.MIME_TYPE, imageFormat.second)
                //执行insert操作，向系统文件夹中添加文件
                //EXTERNAL_CONTENT_URI代表外部存储器，该值不变
                val contentResolver = context.contentResolver

                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    //若生成了uri，则表示该文件添加成功
                    //使用流将内容写入该uri中即可
                    val outputStream = contentResolver.openOutputStream(uri)
                    outputStream?.use {
                        bitmap.compress(imageFormat.first, 90, it)
                        it.flush()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 将文件保存到公共的媒体文件夹
         */
        private fun saveImageToAlbum(
            context: Context,
            fileName: String,
            inputFile: File
        ) {
            try {
                val imageFormat = getImageFormat(fileName)
                val contentValues = ContentValues()
                contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Bilimiao")
                contentValues.put(MediaStore.Images.Media.MIME_TYPE, imageFormat.second)
                val contentResolver = context.contentResolver
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    val outputStream = contentResolver.openOutputStream(uri)
                    outputStream?.use { output ->
                        inputFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                        outputStream.flush()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun getFileName(imageUrl: String): String {
            val urlArr = imageUrl.split("/")
            return urlArr.last()
        }

        private fun getImageFormat(fileName: String): Pair<Bitmap.CompressFormat, String> {
            return if (fileName.uppercase().endsWith(".PNG")) {
                Bitmap.CompressFormat.PNG to "image/PNG"
            } else if (fileName.uppercase().endsWith(".GIF")) {
                Bitmap.CompressFormat.JPEG to "image/GIF"
            } else {
                Bitmap.CompressFormat.JPEG to "image/JPEG"
            }
        }
    }

}