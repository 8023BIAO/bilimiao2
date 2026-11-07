package cn.a10miaomiao.bilimiao.compose.components.image

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
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
    contentAlignment: Alignment = Alignment.Center,
    isLongImage: Boolean = false,
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
                if (isLongImage) {
                    // 长图显示尺寸可达数万像素高，从 cell 到整图的共享元素动画会长时间掉帧；
                    // 用 1ms 动画直接跳过过渡，避免明显的缩放卡顿（产品决定（2026-11-07））
                    previewerController.enterTransform(
                        state = previewerState,
                        models = imageModels,
                        index = index,
                        animationSpec = tween(1),
                    )
                } else {
                    previewerController.enterTransform(
                        state = previewerState,
                        models = imageModels,
                        index = index
                    )
                }
            }
    ) {
        TransformItemView(
            key = model.originalUrl,
            itemState = itemState,
            transformState = previewerState,
        ) {
            val pxRadius = LocalDensity.current.run { 5.dp.toPx().toInt() }
            GlideImage(
                modifier = Modifier
                    .fillMaxSize()
                    // 长图按 top-center 裁切后看不到原图自身的圆角，改为裁切图层圆角，与普通图观感一致
                    .then(
                        if (isLongImage) Modifier.clip(RoundedCornerShape(5.dp)) else Modifier
                    ),
                contentDescription = null,
                model = if (previewerController.isImageLoaded(model.originalUrl)) {
                    model.originalUrl
                } else { model.previewUrl },
                contentScale = contentScale,
                alignment = contentAlignment,
                requestBuilderTransform = { requestBuilder ->
                    requestBuilder
                        .override(600)
                        .transform(RoundedCorners(pxRadius))
                }
            )
        }
        if (isLongImage) {
            LongImageBadge(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            )
        }
    }
}

/**
 * 「长图」角标：单图与多图 cell 共用，右下角主题色小标签。
 */
@Composable
private fun LongImageBadge(modifier: Modifier = Modifier) {
    Text(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        text = "长图",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

/**
 * 计算长图单图卡片尺寸（裁剪头图，而不是按整图比例铺成一条窄条）。
 *
 * 宽度铺满可用宽度（上限 [maxWidth]），高度取宽 × min(高/宽, 长图阈值 2.6)，
 * 再用 [maxHeight]（约一屏高的 85%）封顶；内容在卡片内按 top-center 裁切。
 * 仅用于 [isLongImage] 为 true（宽高均 > 0）的图片。
 */
private fun calculateLongSingleImageSize(
    intrinsicWidth: Float,
    intrinsicHeight: Float,
    maxWidth: Dp,
    maxHeight: Dp,
): DpSize {
    val heightRatio = min(intrinsicHeight / intrinsicWidth, LONG_IMAGE_ASPECT_RATIO)
    return DpSize(maxWidth, minOf((maxWidth.value * heightRatio).dp, maxHeight))
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
        val longImage = isLongImage(model.width, model.height)
        BoxWithConstraints {
            val density = LocalDensity.current
            val maxSize = min(maxWidth.value, 300f).dp
            // 长图高度封顶：屏高的 85%，避免一条竖图占满整屏
            val maxLongHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
            val imageSize = remember(
                model.width,
                model.height,
                maxSize,
                maxLongHeight,
                density,
                longImage,
            ) {
                if (longImage) {
                    calculateLongSingleImageSize(
                        intrinsicWidth = model.width,
                        intrinsicHeight = model.height,
                        maxWidth = maxSize,
                        maxHeight = maxLongHeight,
                    )
                } else {
                    calculateSingleImageSize(
                        intrinsicWidth = model.width,
                        intrinsicHeight = model.height,
                        maxSize = maxSize,
                        density = density,
                    )
                }
            }
            ImagesGridItem(
                modifier = Modifier.size(imageSize.width, imageSize.height),
                index = 0,
                imageModels = imageModels,
                previewerController = previewerController,
                previewerState = previewerState,
                isLongImage = longImage,
                contentScale = if (longImage) ContentScale.Crop else ContentScale.Fit,
                contentAlignment = if (longImage) Alignment.TopCenter else Alignment.Center,
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
                    val model = imageModels[index]
                    ImagesGridItem(
                        modifier = Modifier.size(itemSize),
                        index = index,
                        imageModels = imageModels,
                        previewerController = previewerController,
                        previewerState = previewerState,
                        isLongImage = isLongImage(model.width, model.height),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}
