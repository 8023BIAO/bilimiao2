package cn.a10miaomiao.bilimiao.compose.pages.user.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.a10miaomiao.bilimiao.compose.R
import cn.a10miaomiao.bilimiao.compose.components.image.previewer.ImagePreviewer
import cn.a10miaomiao.bilimiao.compose.components.image.provider.PreviewImageModel
import cn.a10miaomiao.bilimiao.compose.components.image.provider.localImagePreviewerController
import cn.a10miaomiao.bilimiao.compose.components.image.viewer.ModelProcessor
import cn.a10miaomiao.bilimiao.compose.components.user.UserLevelIcon
import cn.a10miaomiao.bilimiao.compose.components.zoomable.previewer.TransformItemView
import cn.a10miaomiao.bilimiao.compose.components.zoomable.previewer.VerticalDragType
import cn.a10miaomiao.bilimiao.compose.components.zoomable.previewer.rememberPreviewerState
import cn.a10miaomiao.bilimiao.compose.components.zoomable.previewer.rememberTransformItemState
import cn.a10miaomiao.bilimiao.compose.pages.user.UserArchiveViewModel
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpaceViewModel
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.comm.toast
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 用户空间顶部的大头像。
 *
 * ★这里现在是**裸头像**：头像上的「直播中」药丸 + 涟漪已整体删除（用户 2026-09-28：
 *   "直播中还有哪一些页面有这些涟漪，还有它那个三个字的样式，全部给它删除了，
 *   仅保留那个直播页面的我的关注在直播那个"）。
 *   原来那层 `LiveBadgedAvatar` 只是"在播装饰 + 点击路由"，删掉后点击语义回到
 *   **点头像 = 看大图**（[previewerController.enterTransform]，本来就写在 onClick 里）。
 *
 * ★图片预览器的缩放层一点没变：它仍在 [TransformItemView] 里（`itemState` +
 *   `previewerState`），80dp 的尺寸改由 [TransformItemView] 自己的 `modifier` 承担
 *   （它的实现是 `modifier…fillMaxSize()`，所以**必须**由外面给死尺寸 —— 直接裸放
 *   会被 `fillMaxSize` 撑满整行）。
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun UserFaceImage(
    face: String,
) {
    val previewerController = localImagePreviewerController()
    val previewerState = rememberPreviewerState(
        verticalDragType = VerticalDragType.Down,
        pageCount = { 1 },
        getKey = { face },
    )
    val itemState = rememberTransformItemState(
        intrinsicSize = Size(200f, 200f),
    )
    TransformItemView(
        modifier = Modifier.size(80.dp),
        key = face,
        itemState = itemState,
        transformState = previewerState,
    ) {
        GlideImage(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .clickable {
                    previewerController.enterTransform(
                        previewerState,
                        listOf(
                            PreviewImageModel(
                                originalUrl = UrlUtil.autoHttps(face),
                                previewUrl = UrlUtil.autoHttps(face) + "@200w_200h",
                                height = 200f,
                                width = 200f
                            )
                        ),
                    )
                },
            model = UrlUtil.autoHttps(face) + "@200w_200h",
            contentDescription = null,
            contentScale = ContentScale.Crop,
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun UserNameBox(
    userName: String,
    sign: String,
    level: Int,
    silence: Int,
    officialVerify: Boolean,
    officialVerifyTitle: String,
    officialVerifyIcon: String,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = userName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            UserLevelIcon(
                modifier = Modifier
                    .padding(start = 5.dp)
                    .size(24.dp, 18.dp),
                level = level,
            )
        }
        if (officialVerify) {
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlideImage(
                    model = UrlUtil.autoHttps(officialVerifyIcon),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 2.dp)
                        .size(16.dp),
                )
                Text(
                    officialVerifyTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Text(
            text = sign,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.outline,
        )
        if (silence == 1) {
            Text(
                "⚠ 该账号封禁中",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun NumBox(
    num: String,
    title: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .widthIn(min = 60.dp)
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Text(text = num, color = MaterialTheme.colorScheme.onBackground)
        Text(text = title, fontSize = 12.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * 用户空间头部（头像 + 昵称 + 数据）。
 *
 * @param rippleActive ★**本参数目前没有消费方**：它原本只喂给头像的涟漪
 *   （`LiveBadgedAvatar(animateRipple = …)`），而涟漪已随「直播中」标记整体删除
 *   （用户 2026-09-28，只保留直播页「我的关注·正在直播」区块的卡片角标）。
 *   按本轮任务要求（"rippleActive 先不要动"）保留形参不删 —— 它的调用方
 *   `UserSpacePage.kt` 本次不在写作用域内，删形参会连带改那个文件（`rippleActive = alpha > 0.02f`
 *   那一处，见 UserSpacePage 的注释）。**下次动到那里时应把形参和实参一起删掉。**
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun UserSpaceHeader(
    modifier: Modifier = Modifier,
    isLargeScreen: Boolean = false,
    rippleActive: Boolean = true,
    viewModel: UserSpaceViewModel,
    archiveViewModel: UserArchiveViewModel,
) {
    val detailData = viewModel.detailData.collectAsStateWithLifecycle().value ?: return Box {}
    val cardData = detailData.card
    val location = cardData.space_tag?.firstOrNull {
        it.type == "location"
    }?.title ?: ""
    val officialVerify = cardData.official_verify

    val seriesList = archiveViewModel.seriesList.collectAsStateWithLifecycle().value
    val seriesTotal = archiveViewModel.seriesTotal.collectAsStateWithLifecycle().value

    Box(
        modifier = modifier,
    ) {
        GlideImage(
            model = UrlUtil.autoHttps(detailData.images.imgUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .height(120.dp)
                .fillMaxWidth()
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 80.dp, start = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                UserFaceImage(
                    face = cardData.face,
                )
                if (isLargeScreen) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 45.dp, start = 10.dp), // 120 - 80
                    ) {
                        UserNameBox(
                            userName = cardData.name,
                            sign = cardData.sign,
                            level = cardData.level_info.current_level,
                            officialVerify = officialVerify.title.isNotBlank(),
                            officialVerifyTitle = officialVerify.title,
                            officialVerifyIcon = officialVerify.icon,
                            silence = cardData.silence,
                        )
                    }
                }
                Row(
                    Modifier.padding(top = 45.dp), // 120 - 80
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NumBox(
                        num = NumberUtil.converString(cardData.fans),
                        title = "粉丝",
                        onClick = viewModel::toFans,
                    )
                    VerticalDivider(Modifier.height(20.dp))
                    NumBox(
                        num = NumberUtil.converString(cardData.attention),
                        title = "关注",
                        onClick = {
                            if (viewModel.isSelf || viewModel.userStore.isLogin()) {
                                viewModel.toFollow()
                            } else {
                                toast("请先登录")
                            }
                        },
                    )
                    VerticalDivider(Modifier.height(20.dp))
                    NumBox(
                        num = NumberUtil.converString(cardData.likes.like_num),
                        title = "获赞",
                        onClick = viewModel::showLikeInfo,
                    )
                }
            }
            if (!isLargeScreen) {
                UserNameBox(
                    userName = cardData.name,
                    sign = cardData.sign,
                    level = cardData.level_info.current_level,
                    officialVerify = officialVerify.title.isNotBlank(),
                    officialVerifyTitle = officialVerify.title,
                    officialVerifyIcon = officialVerify.icon,
                    silence = cardData.silence,
                )
            }
            Row(
                modifier = Modifier.padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "UID:${cardData.mid}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Text(
                    text = location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

            }

            val seriesHeight = 36.dp
            if (seriesList.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .padding(top = 5.dp)
                        .fillMaxWidth()
                        .height(seriesHeight)
                        .clip(RoundedCornerShape(4.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    contentPadding = PaddingValues(end = 10.dp),
                ) {
                    items(seriesList, { it.param }) {
                        AssistChip(
                            onClick = { archiveViewModel.toSeriesDetail(it) },
                            label = {
                                Text(
                                    text = it.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = when(it.type) {
                                        "series" -> Icons.AutoMirrored.Default.List
                                        "season" -> Icons.AutoMirrored.Default.Article
                                        else -> Icons.AutoMirrored.Default.ViewList
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.height(seriesHeight),
                        )
                    }
                    if (seriesTotal > seriesList.size) {
                        item {
                            AssistChip(
                                onClick = archiveViewModel::toSeriesList,
                                label = {
                                    Text(
                                        text = "更多合集",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                modifier = Modifier.height(seriesHeight),
                            )
                        }
                    }
                }
            }
        }
    }
}