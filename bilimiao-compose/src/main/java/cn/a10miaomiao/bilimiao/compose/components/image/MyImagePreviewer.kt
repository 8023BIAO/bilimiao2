package cn.a10miaomiao.bilimiao.compose.components.image

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.mypage.rememberMyMenu
import cn.a10miaomiao.bilimiao.compose.components.image.previewer.ImagePreviewer
import cn.a10miaomiao.bilimiao.compose.components.image.provider.ImagePreviewerState
import cn.a10miaomiao.bilimiao.compose.components.image.provider.PreviewImageModel
import cn.a10miaomiao.bilimiao.compose.components.image.viewer.AnyComposable
import cn.a10miaomiao.bilimiao.compose.components.zoomable.previewer.PreviewerState
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.mypage.MyPageMenu
import com.a10miaomiao.bilimiao.comm.mypage.myMenu
import com.a10miaomiao.bilimiao.comm.utils.ImageSaveUtil
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.placeholder
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.a10miaomiao.bilimiao.comm.toast
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.compose.rememberInstance
import java.io.File

private class MyImagePreviewerController(
    val activity: FragmentActivity,
    val imagePreviewerState: ImagePreviewerState,
) {

    val isDownloading = mutableStateOf(false)

    /** 批量保存进度：null = 不是批量保存；(已完成, 总数) */
    val savingAll = mutableStateOf<Pair<Int, Int>?>(null)

    fun saveImageFile(
        imageUrl: String,
    ) {
        val target = object : CustomTarget<File>() {
            override fun onResourceReady(
                resource: File,
                transition: Transition<in File>?
            ) {
                if (isDownloading.value) {
                    ImageSaveUtil.saveImage(
                        activity,
                        ImageSaveUtil.getFileName(imageUrl),
                        resource,
                    )
                    isDownloading.value = false
                }
            }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    // Glide 加载失败走的是 onLoadFailed，不是 onLoadCleared：
                    // 以前这里没人实现，断网/404 时"正在下载图片"会永远转圈
                    toast("原图下载失败")
                    isDownloading.value = false
                }

            override fun onLoadCleared(placeholder: Drawable?) {
                isDownloading.value = false
            }
        }
        // 先置下载中标志再发起请求：Glide 内存缓存命中时 onResourceReady 会同步回调，
        // 标志后置会导致保存被跳过且下载对话框永久卡住
        isDownloading.value = true
        Glide.with(activity)
            .asFile()
            .load(imageUrl)
            .into(target)
    }

    /**
     * 保存图集里的全部图片（评论区/动态九宫格常用）：
     * 逐张"下载 → 落盘"，串行执行 —— 并发下载会同时压图床和内存，反而更容易失败。
     * 进度通过 savingAll 显示在同一个进度对话框里。
     */
    fun saveAllImages() {
        val urls = imagePreviewerState.imageModels.map { it.originalUrl }
        if (urls.isEmpty() || isDownloading.value) return
        isDownloading.value = true
        savingAll.value = 0 to urls.size
        activity.lifecycleScope.launch {
            var saved = 0
            urls.forEachIndexed { index, url ->
                val file = runCatching {
                    // Glide 的 RequestManager 必须在主线程取（这里是 Main 协程），阻塞等待放 IO
                    val future = Glide.with(activity).asFile().load(url).submit()
                    withContext(Dispatchers.IO) { future.get() }
                }.getOrNull()
                if (file != null) {
                    ImageSaveUtil.saveImage(activity, ImageSaveUtil.getFileName(url), file)
                    saved++
                }
                savingAll.value = (index + 1) to urls.size
            }
            isDownloading.value = false
            savingAll.value = null
            toast("已保存 $saved/${urls.size} 张图片")
        }
    }

    fun copyImageUrl(imageUrl: String) {
        val clipboardManager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = ClipData.newPlainText("imageUrl", imageUrl)
        clipboardManager.setPrimaryClip(clipData)
        toast("图片链接已复制到剪切板")
    }

    fun shareImage(imageUrl: String) {
        val target = object : CustomTarget<File>() {
            override fun onResourceReady(
                resource: File,
                transition: Transition<in File>?
            ) {
                if (isDownloading.value) {

                    isDownloading.value = false
                }
            }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    // Glide 加载失败走的是 onLoadFailed，不是 onLoadCleared：
                    // 以前这里没人实现，断网/404 时"正在下载图片"会永远转圈
                    toast("原图下载失败")
                    isDownloading.value = false
                }

            override fun onLoadCleared(placeholder: Drawable?) {
                isDownloading.value = false
            }
        }
        Glide.with(activity)
            .asFile()
            .load(imageUrl)
            .into(target)
        isDownloading.value = true
    }

    fun menuItemClick(view: View, menuItem: MenuItemPropInfo) {
        val page = imagePreviewerState.previewerState.currentPage
        val model = imagePreviewerState.imageModels[page]
        when (menuItem.key) {
            MenuKeys.save -> {
                saveImageFile(model.originalUrl)
            }
            1 -> {
                copyImageUrl(model.originalUrl)
            }
            2 -> {
                saveAllImages()
            }
        }
    }

    fun cancelDownloading() {
        isDownloading.value = false
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun MyImagePreviewer(
    imagePreviewerState: ImagePreviewerState,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val activity: FragmentActivity by rememberInstance()
    val controller = remember(imagePreviewerState) {
        MyImagePreviewerController(activity, imagePreviewerState)
    }
    val pageConfigId = PageConfig(
        title = "查看图片",
        menu = rememberMyMenu {
            myItem {
                key = 1
                title = "复制图片链接"
                iconFileName = "ic_baseline_content_copy_24"
            }
            myItem {
                key = MenuKeys.save
                title = "保存图片"
                iconFileName = "ic_baseline_save_24"
            }
            myItem {
                key = 2
                title = "保存全部图片"
                iconFileName = "ic_baseline_save_24"
            }
        }
    )
    PageListener(
        configId = pageConfigId,
        onMenuItemClick = controller::menuItemClick,
    )
    ImagePreviewer(
        contentPadding = contentPadding,
        state = imagePreviewerState.previewerState,
        imageLoader = { page ->
            val model = imagePreviewerState.imageModels[page]
            val imageUrl = model.originalUrl
            // 超大图限制：图床 URL 加最长边 4096px 后缀（图床只缩不放，普通图无损），
            // 防止原图 bitmap 超限导致 Canvas 崩溃 (upstream #245: 175MB bitmap)
            // 4096x4096 ≈ 67MB，远低于崩溃线，同时保留放大查看的清晰度
            val previewUrl = if (imageUrl.contains("hdslb.com") && "@" !in imageUrl) {
                imageUrl + "@4096w_4096h"
            } else {
                imageUrl
            }
            // ★ Glide Compose 1.0.0-beta10 删掉了 GlideSubcomposition / RequestState，
            //   这里改成「Glide 自己取 Drawable + accompanist 的 rememberDrawablePainter 转 Painter」：
            //   行为跟以前一致 —— 图到了就给出 painter，并标记"原图已下载"。
            val drawableState = remember(previewUrl) { mutableStateOf<Drawable?>(null) }
            LaunchedEffect(previewUrl) {
                val drawable = runCatching {
                    withContext(Dispatchers.IO) {
                        Glide.with(activity).asDrawable().load(previewUrl).submit().get()
                    }
                }.getOrNull()
                if (drawable != null) {
                    drawableState.value = drawable
                    // 设置原图已下载标志
                    imagePreviewerState.onImageLoaded(page)
                }
            }
            val loadedDrawable = drawableState.value
            return@ImagePreviewer Pair(
                if (loadedDrawable != null) rememberDrawablePainter(loadedDrawable) else null,
                Size(model.width, model.height)
            )
        }
    )
    if (controller.isDownloading.value) {
        AlertDialog(
            onDismissRequest = controller::cancelDownloading,
            confirmButton = {
                TextButton(onClick = controller::cancelDownloading) {
                    Text("取消")
                }
            },
            title = {
                val progress = controller.savingAll.value
                Text(
                    if (progress == null) "正在下载图片"
                    else "正在保存全部图片 ${progress.first}/${progress.second}"
                )
            },
            text = {
                LinearProgressIndicator()
            }
        )
    }
}
