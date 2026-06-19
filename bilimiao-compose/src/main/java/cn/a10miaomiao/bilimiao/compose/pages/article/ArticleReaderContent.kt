package cn.a10miaomiao.bilimiao.compose.pages.article

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.mypage.rememberMyMenu
import cn.a10miaomiao.bilimiao.compose.components.image.provider.PreviewImageModel
import cn.a10miaomiao.bilimiao.compose.components.image.provider.localImagePreviewerController
import cn.a10miaomiao.bilimiao.compose.components.list.SwipeToRefresh
import cn.a10miaomiao.bilimiao.compose.components.zoomable.previewer.TransformItemView
import cn.a10miaomiao.bilimiao.compose.components.zoomable.previewer.VerticalDragType
import cn.a10miaomiao.bilimiao.compose.components.zoomable.previewer.rememberPreviewerState
import cn.a10miaomiao.bilimiao.compose.components.zoomable.previewer.rememberTransformItemState
import com.a10miaomiao.bilimiao.comm.mypage.myMenu
import com.a10miaomiao.bilimiao.comm.utils.HtmlTagHandler
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ArticleReaderContent(
    viewModel: ArticleReaderViewModel,
    bottomPadding: androidx.compose.ui.unit.Dp = 32.dp,
) {
    val context = LocalContext.current
    val articleUrl = "https://www.bilibili.com/read/cv${viewModel.articleId}"

    val menu = rememberMyMenu {
        myItem {
            key = 1
            iconFileName = "ic_baseline_open_in_browser_24"
            title = "浏览器打开"
        }
        myItem {
            key = 2
            iconFileName = "ic_baseline_content_copy_24"
            title = "复制链接"
        }
    }
    val configId = PageConfig(title = "专栏", menu = menu)
    PageListener(configId = configId) { _, item ->
        when (item.key) {
            1 -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(articleUrl))
                context.startActivity(intent)
            }
            2 -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("专栏链接", articleUrl))
                android.widget.Toast.makeText(context, "已复制链接", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val article by viewModel.article.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val error by viewModel.error.collectAsState()

    if (isLoading && article == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (error != null && article == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = error ?: "加载失败",
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    onClick = { viewModel.loadArticle() },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = "重试",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        return
    }

    val data = article ?: return

    SwipeToRefresh(
        refreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
    ) {
        SelectionContainer(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding)
            ) {
                // Banner
                if (data.bannerUrl.isNotBlank()) {
                    item {
                        val imageUrl = com.a10miaomiao.bilimiao.comm.utils.UrlUtil.autoHttps(
                            if (data.bannerUrl.startsWith("//")) "https:${data.bannerUrl}" else data.bannerUrl
                        )
                        GlideImage(
                            model = imageUrl,
                            contentDescription = data.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                        )
                    }
                }

                // Title
                item {
                    Text(
                        text = data.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }

                // Author row
                item {
                    Surface(
                        onClick = { viewModel.toAuthorPage() },
                        modifier = Modifier.padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val faceUrl = com.a10miaomiao.bilimiao.comm.utils.UrlUtil.autoHttps(
                                if (data.authorFace.startsWith("//")) "https:${data.authorFace}" else data.authorFace
                            )
                            GlideImage(
                                model = faceUrl,
                                contentDescription = data.authorName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = data.authorName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Row {
                                    Text(
                                        text = "阅读 ${NumberUtil.converString(data.viewCount.toString())}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (data.publishTime > 0) {
                                        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                            .format(Date(data.publishTime * 1000))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = dateStr,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "${data.words}字",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Stats — 放在作者信息下方
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatItem("阅读", NumberUtil.converString(data.viewCount.toString()))
                        StatItem("点赞", NumberUtil.converString(data.likeCount.toString()))
                        StatItem("收藏", NumberUtil.converString(data.favoriteCount.toString()))
                        StatItem("评论", NumberUtil.converString(data.replyCount.toString()))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Content paragraphs
                itemsIndexed(data.paragraphs) { _, paragraph ->
                    when (paragraph) {
                        is ArticleParagraph.TextParagraph -> TextParagraphItem(paragraph)
                        is ArticleParagraph.ImageParagraph -> ImageParagraphItem(paragraph)
                    }
                }

                // End spacer
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun TextParagraphItem(paragraph: ArticleParagraph.TextParagraph) {
    val align = when (paragraph.align) {
        "center" -> TextAlign.Center
        "right" -> TextAlign.End
        else -> TextAlign.Start
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = when (align) {
            TextAlign.Center -> Alignment.Center
            TextAlign.End -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
    ) {
        Text(
            text = buildAnnotatedString {
                paragraph.nodes.forEach { node ->
                    if (node.text.contains('<')) {
                        val spanned = HtmlTagHandler.fromHtml(node.text)
                        val annotated = spannedToAnnotatedString(spanned, node.fontSize)
                        if (annotated.text.isBlank() && node.text.isNotBlank()) {
                            append(node.text
                                .replace(Regex("<[^>]+>"), "")
                                .replace("&nbsp;", " ")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .replace("&amp;", "&")
                            )
                        } else {
                            append(annotated)
                        }
                    } else {
                        withStyle(
                            SpanStyle(
                                color = parseNodeColor(node.color),
                                fontSize = node.fontSize.sp,
                                fontWeight = if (node.bold) FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (node.italic) FontStyle.Italic else FontStyle.Normal,
                                textDecoration = if (node.underline) TextDecoration.Underline else TextDecoration.None,
                            )
                        ) {
                            append(node.text)
                        }
                    }
                }
            },
            textAlign = align,
            lineHeight = (17 * 1.6).sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun spannedToAnnotatedString(spanned: Spanned, baseFontSize: Int): AnnotatedString {
    return buildAnnotatedString {
        val text = spanned.toString()
        append(text)
        spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start < 0 || end <= start || start >= text.length || end > text.length) return@forEach
            when (span) {
                is StyleSpan -> {
                    val fw = when (span.style) {
                        Typeface.BOLD, Typeface.BOLD_ITALIC -> FontWeight.Bold
                        else -> FontWeight.Normal
                    }
                    val fs = when (span.style) {
                        Typeface.ITALIC, Typeface.BOLD_ITALIC -> FontStyle.Italic
                        else -> FontStyle.Normal
                    }
                    addStyle(SpanStyle(fontWeight = fw, fontStyle = fs), start, end)
                }
                is ForegroundColorSpan -> {
                    addStyle(SpanStyle(color = Color(span.foregroundColor)), start, end)
                }
                is AbsoluteSizeSpan -> {
                    addStyle(SpanStyle(fontSize = span.size.sp), start, end)
                }
                is RelativeSizeSpan -> {
                    addStyle(SpanStyle(fontSize = (baseFontSize * span.sizeChange).sp), start, end)
                }
                is UnderlineSpan -> {
                    addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
                }
                is StrikethroughSpan -> {
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ImageParagraphItem(paragraph: ArticleParagraph.ImageParagraph) {
    val imageUrl = com.a10miaomiao.bilimiao.comm.utils.UrlUtil.autoHttps(
        if (paragraph.url.startsWith("//")) "https:${paragraph.url}" else paragraph.url
    )
    val imgWidth = if (paragraph.width > 0) paragraph.width else 600
    val imgHeight = if (paragraph.height > 0) paragraph.height else 400
    val aspectRatio = if (paragraph.width > 0 && paragraph.height > 0) {
        paragraph.width.toFloat() / paragraph.height.toFloat()
    } else {
        16f / 9f
    }
    val previewerController = localImagePreviewerController()
    val imageModel = PreviewImageModel(
        previewUrl = imageUrl,
        originalUrl = imageUrl,
        width = imgWidth.toFloat(),
        height = imgHeight.toFloat(),
    )
    val previewerState = rememberPreviewerState(
        verticalDragType = VerticalDragType.Down,
        pageCount = { 1 },
        getKey = { imageUrl },
    )
    val itemState = rememberTransformItemState(
        intrinsicSize = Size(imgWidth.toFloat(), imgHeight.toFloat()),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    previewerController.enterTransform(
                        state = previewerState,
                        models = listOf(imageModel),
                        index = 0,
                    )
                },
        ) {
            TransformItemView(
                key = imageUrl,
                itemState = itemState,
                transformState = previewerState,
            ) {
                GlideImage(
                    model = imageUrl,
                    contentDescription = paragraph.caption.ifBlank { "图片" },
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio),
                )
            }
        }
        if (paragraph.caption.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = paragraph.caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun parseNodeColor(hexColor: String): Color {
    if (hexColor.isBlank()) return MaterialTheme.colorScheme.onSurface
    val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val fallback = MaterialTheme.colorScheme.onSurface
    val parsed = runCatching {
        val colorStr = hexColor.removePrefix("#")
        when (colorStr.length) {
            6 -> Color(android.graphics.Color.parseColor("#$colorStr"))
            8 -> Color(android.graphics.Color.parseColor("#$colorStr"))
            else -> null
        }
    }.getOrNull() ?: return fallback
    // 暗色主题下，深色文字（如 #333333）不可见 → 用 onSurface 代替
    if (isDarkTheme) {
        val r = parsed.red
        val g = parsed.green
        val b = parsed.blue
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        if (luminance < 0.4f) return fallback
    }
    return parsed
}
