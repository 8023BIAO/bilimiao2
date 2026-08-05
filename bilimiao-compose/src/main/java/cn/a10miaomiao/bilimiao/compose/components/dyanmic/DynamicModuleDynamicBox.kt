package cn.a10miaomiao.bilimiao.compose.components.dyanmic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import bilibili.app.dynamic.v2.ModuleDynamic
import cn.a10miaomiao.bilimiao.compose.common.localPageNavigation
import cn.a10miaomiao.bilimiao.compose.components.image.ImagesGrid
import cn.a10miaomiao.bilimiao.compose.components.image.provider.PreviewImageModel
import cn.a10miaomiao.bilimiao.compose.components.video.VideoItemBox
import cn.a10miaomiao.bilimiao.compose.pages.video.VideoDetailPage
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import kotlin.math.min

@Composable
private fun DynArchiveBox(
    dynPgc: bilibili.app.dynamic.v2.MdlDynArchive
) {
    val pageNavigation = localPageNavigation()

    VideoItemBox(
        modifier = Modifier.padding(
            horizontal = 10.dp,
        ),
        title = dynPgc.title,
        pic = dynPgc.cover,
        duration = dynPgc.coverLeftText1,
        playNum = dynPgc.coverLeftText2,
        damukuNum = dynPgc.coverLeftText3,
        onClick = {
            pageNavigation.navigate(VideoDetailPage(
                id = dynPgc.bvid,
            ))
        }
    )
}

@Composable
private fun DynUgcSeasonBox(
    dynUgcSeason: bilibili.app.dynamic.v2.MdlDynUGCSeason
) {
    val pageNavigation = localPageNavigation()

    VideoItemBox(
        modifier = Modifier.padding(
            horizontal = 10.dp,
        ),
        title = dynUgcSeason.title,
        pic = dynUgcSeason.cover,
        duration = dynUgcSeason.coverLeftText1,
        playNum = dynUgcSeason.coverLeftText2,
        damukuNum = dynUgcSeason.coverLeftText3,
        onClick = {
            pageNavigation.navigate(VideoDetailPage(
                id = dynUgcSeason.avid.toString(),
            ))
        }
    )
}

@Composable
fun DynDrawBox(
    dynDraw: bilibili.app.dynamic.v2.MdlDynDraw
) {
    val items = dynDraw.items
    val imageModels = remember(items) {
        items.map {
            val w = min(600, it.width)
            val h = w * it.height / it.width
            val url = UrlUtil.autoHttps(it.src)
            PreviewImageModel(
                previewUrl = url + "@${w}w_${h}h",
                originalUrl = url,
                height = it.height.toFloat(),
                width = it.width.toFloat(),
            )

        }
    }
    Box(modifier = Modifier.padding(
        horizontal = 10.dp,
        vertical = 5.dp
    )) {
        ImagesGrid(imageModels)
    }
}

@Composable
fun DynForwardBox(
    dynForward: bilibili.app.dynamic.v2.MdlDynForward
) {
    val modules = dynForward.item?.modules ?: return
    val uriHandler = LocalUriHandler.current
    Column (
        modifier = Modifier
            .padding(
                horizontal = 10.dp,
                vertical = 5.dp
            )
            .clip(RoundedCornerShape(5.dp))
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable {
                dynForward.item?.extend?.let {
                    uriHandler.openUri(it.cardUrl)
                }
            }
    ) {
        modules.forEach { moduleItem ->
            DynamicModuleBox(moduleItem)
        }
    }
}

@Composable
fun DynNoteBox(
    dynNote: bilibili.app.dynamic.v2.MdlDynNote
) {
    val uriHandler = LocalUriHandler.current
    val pictures = dynNote.pictures?.pictures ?: emptyList()
    val imageModels = remember(pictures) {
        pictures.map {
            val imgWidth = it.imgWidth.toInt()
            val imgHeight = it.imgHeight.toInt()
            val w = min(600, imgWidth)
            val h = w * imgHeight / imgWidth
            val url = UrlUtil.autoHttps(it.imgSrc)
            PreviewImageModel(
                previewUrl = url + "@${w}w_${h}h",
                originalUrl = url,
                height = imgHeight.toFloat(),
                width = imgWidth.toFloat(),
            )
        }
    }
    Column(
        modifier = Modifier
            .padding(
                horizontal = 10.dp,
                vertical = 5.dp
            )
            .clip(RoundedCornerShape(5.dp))
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable {
                dynNote.jumpUrl.let { url ->
                    if (url.isNotBlank()) {
                        uriHandler.openUri(UrlUtil.autoHttps(url))
                    }
                }
            }
    ) {
        if (dynNote.title.isNotBlank()) {
            Text(
                text = dynNote.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    start = 10.dp, end = 10.dp, top = 10.dp,
                ),
            )
        }
        if (dynNote.desc.isNotBlank()) {
            Text(
                text = dynNote.desc,
                maxLines = 2,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(
                    start = 10.dp, end = 10.dp, bottom = 5.dp,
                ),
            )
        }
        if (imageModels.isNotEmpty()) {
            ImagesGrid(imageModels)
        }
    }
}

@Composable
fun DynamicModuleDynamicBox(
    dynamic: ModuleDynamic
) {
    val moduleItem = dynamic.moduleItem ?: return
    when (moduleItem) {
        is ModuleDynamic.ModuleItem.DynArchive -> {
            DynArchiveBox(moduleItem.value)
        }
        is ModuleDynamic.ModuleItem.DynUgcSeason -> {
            DynUgcSeasonBox(moduleItem.value)
        }
        is ModuleDynamic.ModuleItem.DynDraw -> {
            DynDrawBox(moduleItem.value)
        }
        is ModuleDynamic.ModuleItem.DynForward -> {
            DynForwardBox(moduleItem.value)
        }
        is ModuleDynamic.ModuleItem.DynNote -> {
            DynNoteBox(moduleItem.value)
        }
        else -> {
            // unsupported dynamic module: hide it to avoid showing ad placeholders
            Unit
        }
    }
}