package cn.a10miaomiao.bilimiao.compose.common.foundation

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.comm.delegate.player.PlayerSeekBus
import cn.a10miaomiao.bilimiao.compose.common.foundation.LocalSeekEnabled
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage

@Stable
sealed class AnnotatedTextNode {
    @Stable
    class Text(val text: String) : AnnotatedTextNode()
    @Stable
    class Emote(
        val text: String,
        val url: String,
//        val width: Int,
//        val height: Int
    ) : AnnotatedTextNode()
    @Stable
    class Link(
        val text: String,
        val url: String,
        val withLineBreak: Boolean = false, // 表示前面要加换行符
    ) : AnnotatedTextNode()
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun inlineAnnotatedContent(
    nodes: List<AnnotatedTextNode>,
    size: TextUnit = 20.sp,
    inlineVerticalAlign: PlaceholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
):  Map<String, InlineTextContent> {
    return nodes.filterIsInstance<AnnotatedTextNode.Emote>().map { node ->
        node.text to InlineTextContent(
            placeholder = Placeholder(
                width = size,
                height = size,
                placeholderVerticalAlign = inlineVerticalAlign,
            ),
            children = {
                GlideImage(
                    model = UrlUtil.autoHttps(node.url),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
        )
    }.toMap()
}

@Composable
fun annotatedText(
    nodes: List<AnnotatedTextNode>
): AnnotatedString {
    val onSeekTime = LocalOnSeekTime.current
    val seekEnabled = LocalSeekEnabled.current
    return buildAnnotatedString {
        nodes.forEach {
            when (it) {
                is AnnotatedTextNode.Text -> append(it.text)
                is AnnotatedTextNode.Emote -> {
                    appendInlineContent(it.text)
                }
                is AnnotatedTextNode.Link -> {
                    if (it.withLineBreak) {
                        append("\n")
                    }
                    // 时间戳链接（专栏禁用空降 → 渲染为纯文本）
                    if (it.url.startsWith("bilimiao://seek/")) {
                        if (!seekEnabled) {
                            append(it.text)
                            return@forEach
                        }
                        val seconds = it.url.substringAfter("//seek/").toIntOrNull() ?: 0
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "seek",
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                ),
                                linkInteractionListener = LinkInteractionListener {
                                    // 优先用 CompositionLocal（详情页可做视频匹配检查）
                                    onSeekTime?.invoke(seconds)
                                        // 再用全局bus（弹幕时间戳等）
                                        ?: PlayerSeekBus.onSeek?.invoke(seconds * 1000L)
                                }
                            )
                        ) {
                            append(it.text)
                        }
                    } else {
                        withLink(
                            LinkAnnotation.Url(
                                it.url,
                                TextLinkStyles(
                                    style = SpanStyle(
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                )
                            )
                        ) {
                            append(it.text)
                        }
                    }
                }
            }
        }
    }
}