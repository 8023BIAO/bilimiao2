package cn.a10miaomiao.bilimiao.compose.components.image

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.R
import cn.a10miaomiao.bilimiao.compose.components.image.provider.ImagePreviewerController
import cn.a10miaomiao.bilimiao.compose.components.image.provider.PreviewImageModel
import cn.a10miaomiao.bilimiao.compose.components.image.provider.localImagePreviewerController
import cn.a10miaomiao.bilimiao.compose.components.zoomable.previewer.PreviewerState
import cn.a10miaomiao.bilimiao.compose.components.zoomable.previewer.TransformItemView
import cn.a10miaomiao.bilimiao.compose.components.zoomable.previewer.VerticalDragType
import cn.a10miaomiao.bilimiao.compose.components.zoomable.previewer.rememberPreviewerState
import cn.a10miaomiao.bilimiao.compose.components.zoomable.previewer.rememberTransformItemState
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import kotlin.math.min

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ImagesGridItem(
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    index: Int,
    imageModels: List<PreviewImageModel>,
    previewerController: ImagePreviewerController,
    previewerState: PreviewerState,
) {
    val model = imageModels[index]
    val itemState = rememberTransformItemState(
        intrinsicSize = Size(model.width, model.height),
    )
//    ScaleGrid(
//        modifier = modifier,
//        detectGesture = DetectScaleGridGesture(
//            onPress = {
//                previewerController.enterTransform(
//                    state = previewerState,
//                    models = imageModels,
//                    index = index
//                )
//            }
//        )
//    ) {
    Box(
        modifier = modifier
            .clickable {
                previewerController.enterTransform(
                    state = previewerState,
                    models = imageModels,
                    index = index
                )
            }
    ) {
        TransformItemView(
            key = model.originalUrl,
            itemState = itemState,
            transformState = previewerState,
        ) {
            val pxRadius = LocalDensity.current.run { 5.dp.toPx().toInt() }
            GlideImage(
                modifier = Modifier.fillMaxSize(),
                contentDescription = null,
                model = if (previewerController.isImageLoaded(model.originalUrl)) {
                    model.originalUrl
                } else { model.previewUrl },
                contentScale = contentScale,
                requestBuilderTransform = { requestBuilder ->
                    requestBuilder
                        .override(600)
                        .transform(RoundedCorners(pxRadius))
                }
            )
        }
    }
}

/**
 * 计算单张图片的显示尺寸。
 *
 * 以图片自身尺寸（px 按当前屏幕密度换算成 dp）为基准等比缩放：
 * - 长边超过 [maxSize] 时，等比缩小到 [maxSize] 以内；
 * - 图片自身小于 [maxSize] 时保持图片自身大小，不放大；
 * - 宽高未知（0 或负数）时回退成 [maxSize] 的正方形，避免 0 尺寸不渲染。
 */
private fun calculateSingleImageSize(
    intrinsicWidth: Float,
    intrinsicHeight: Float,
    maxSize: Dp,
    density: Density,
): DpSize {
    val limit = maxSize.value
    if (intrinsicWidth <= 0f || intrinsicHeight <= 0f || limit <= 0f) {
        return DpSize(limit.dp, limit.dp)
    }
    val naturalWidth = with(density) { intrinsicWidth.toDp().value }
    val naturalHeight = with(density) { intrinsicHeight.toDp().value }
    val scale = min(1f, min(limit / naturalWidth, limit / naturalHeight))
    return DpSize(
        (naturalWidth * scale).dp,
        (naturalHeight * scale).dp,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImagesGrid(
    imageModels: List<PreviewImageModel>,
) {
    val count = imageModels.size
    val previewerController = localImagePreviewerController()
    val previewerState = rememberPreviewerState(
        verticalDragType = VerticalDragType.Down,
        pageCount = { count },
        // 越界容错：预览状态可能持有旧列表引用，索引越界时返回空 key 而不是崩溃
        getKey = { imageModels.getOrNull(it)?.originalUrl ?: "" },
    )
    if (count == 1) {
        // 单图按图片自身宽高比显示（原来固定 300x300 方框，任意比例都被撑成方框再留白）
        val model = imageModels[0]
        BoxWithConstraints {
            val density = LocalDensity.current
            val maxSize = min(maxWidth.value, 300f).dp
            val imageSize = remember(model.width, model.height, maxSize, density) {
                calculateSingleImageSize(
                    intrinsicWidth = model.width,
                    intrinsicHeight = model.height,
                    maxSize = maxSize,
                    density = density,
                )
            }
            ImagesGridItem(
                modifier = Modifier.size(imageSize.width, imageSize.height),
                index = 0,
                imageModels = imageModels,
                previewerController = previewerController,
                previewerState = previewerState,
            )
        }
    } else {
        val isSmallGrid = count <= 4
        val maxCap = if (isSmallGrid) 300f else 330f
        val columns = if (isSmallGrid) 2 else 3
        val spacingVal = if (isSmallGrid) 4f else 3f
        // 修复: 之前 Box 只 widthIn(max) 无内容时宽度=0, onSizeChanged 回调 0,
        // 导致多图评论图片区域完全不渲染(单图正常/多图空白)
        val gridWidth = maxCap.dp
        Box(
            modifier = Modifier
                .width(gridWidth)
        ) {
            val width = maxCap
            FlowRow(
                modifier = Modifier.width(width.dp),
                horizontalArrangement = Arrangement.spacedBy(spacingVal.dp),
                verticalArrangement = Arrangement.spacedBy(spacingVal.dp)
            ) {
                val itemSize = (width / columns - spacingVal).dp
                for (index in 0 until count) {
                    ImagesGridItem(
                        modifier = Modifier.size(itemSize),
                        index = index,
                        imageModels = imageModels,
                        previewerController = previewerController,
                        previewerState = previewerState,
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}
