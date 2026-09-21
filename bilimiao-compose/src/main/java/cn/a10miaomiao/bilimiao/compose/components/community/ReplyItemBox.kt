package cn.a10miaomiao.bilimiao.compose.components.community

import android.os.Parcelable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.a10miaomiao.bilimiao.compose.R
import cn.a10miaomiao.bilimiao.compose.assets.BilimiaoIcons
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.Common
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.common.Delete
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.common.Like
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.common.Likefill
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.common.Reply
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.common.Share
import cn.a10miaomiao.bilimiao.compose.common.foundation.annotatedText
import cn.a10miaomiao.bilimiao.compose.common.foundation.AnnotatedTextNode
import cn.a10miaomiao.bilimiao.compose.common.foundation.LocalOnSeekTime
import cn.a10miaomiao.bilimiao.compose.common.foundation.ScaleIndication
import cn.a10miaomiao.bilimiao.compose.common.foundation.inlineAnnotatedContent
import cn.a10miaomiao.bilimiao.compose.components.image.ImagesGrid
import cn.a10miaomiao.bilimiao.compose.components.image.provider.PreviewImageModel
import com.a10miaomiao.bilimiao.comm.utils.MiaoLogger
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import kotlin.math.max
import kotlin.math.min

/** 评论正文解析用的时间戳/分隔符正则：提成文件级常量，避免每个节点都重新编译 */
private val TIMESTAMP_REGEX = Regex("\\d{1,3}[:：]\\d{1,2}(?:[:：]\\d{1,2})?")
private val TIME_SEP_REGEX = Regex("[:：]")

/** 评论正文里需要识别的固定模式（URL/av/BV/ac/sm/cv/时间戳/表情） */
private val REPLY_TEXT_REGEX_BASE = """(?i)""" +
        """(\b(https?://|www\.)[\w-]+(\.[\w-]+)+([/\S]*)*\b)|""" +  // URL（优先匹配）
        """(\b(av\d{1,15})\b)|""" +     // B站av号（1-15位数字）
        """(\b(BV[\dA-Za-z]{10})\b)|""" + // B站BV号（固定10位）
        """(\b(ac\d{1,10})\b)|""" +     // A站ac号（1-10位数字）
        """(\b(sm\d{1,10})\b)|""" +     // Niconico sm号（1-10位数字）
        """(\b(cv\d{1,8})\b)|""" +      // B站专栏cv号（1-8位数字）
        """(\d{1,3}[:：]\d{1,2}(?:[:：]\d{1,2})?)|""" + // 时间戳
        """(\[[^\[\]\s]{1,30}])"""     // 匹配emote表情

/**
 * 拼接完整正则：@用户名 这一支只有在真的有名字时才追加。
 * 原实现结尾固定留一个 `|`，@列表为空时会匹配空串，导致 find 出的节点数暴增。
 */
private fun buildReplyTextRegex(atNames: Set<String>): Regex {
    if (atNames.isEmpty()) return Regex(REPLY_TEXT_REGEX_BASE)
    val atPart = atNames.joinToString("|") { Regex.escape(it) }
    return Regex("$REPLY_TEXT_REGEX_BASE|@(?:$atPart)")
}

@Stable
class ReplyItemBoxPictureInfo(
    val src: String,
    val width: Int,
    val height: Int,
    val size: Int,
)

/** 一级评论下面预览的二级回复（条数上限见 [SUB_REPLY_PREVIEW_MAX]） */
@Stable
class SubReplyPreviewInfo(
    val uname: String,
    val content: ReplyItemBoxContentInfo?,
)

/**
 * 一级评论下最多预览几条二级回复。官方是 2 条，这里对齐官方；
 * 其余回复靠下面那行「共N条回复」点进楼中楼看全部。
 */
private const val SUB_REPLY_PREVIEW_MAX = 2

@Stable
class ReplyItemBoxContentInfo(
    val message: String,
    val emote: List<EmoteInfo>,
    val url: List<UrlInfo>,
    val atNameToMid: Map<String, Long> = emptyMap(),
) {
    @Stable
    class EmoteInfo(
        val id: Long,
        val text: String,
        val url: String
    )

    @Stable
    class UrlInfo(
        val text: String,
        val url: String
    )

    @Composable
    fun toAnnotatedTextNode(): List<AnnotatedTextNode> {
        // 以前每次重组都重新编译这条正则（还把全部 @用户名 拼在里面，可能几百个），
        // 是评论列表滑动卡顿的大头；现在只按 atNameToMid 缓存一次。
        // 另外原来结尾固定带一个 `|`，@列表为空时会退化成"匹配空串"，节点数会爆炸，这里一并修掉。
        val regex = remember(atNameToMid) { buildReplyTextRegex(atNameToMid.keys) }
        // ★ 连"扫一遍正则 + 建出节点列表"的结果也要缓存，光缓存正则不够：
        //   ReplyItemBox 的参数是 protobuf 对象（unstable），列表父级每次重组（下拉刷新、
        //   loading/finished 变化、屏蔽词重算、滚动回收重建）都会让每条可见评论**重新全量扫一遍**，
        //   中文长评论尤其明显。节点列表只跟 message/@映射/表情表有关。
        return remember(message, atNameToMid, emote, regex) {
            buildAnnotatedTextNodes(message, emote, atNameToMid, regex)
        }
    }


    private fun buildAnnotatedTextNodes(
        message: String,
        emote: List<EmoteInfo>,
        atNameToMid: Map<String, Long>,
        regex: Regex,
    ): List<AnnotatedTextNode> {
        val nodes = mutableListOf<AnnotatedTextNode>()
        var lastEnd = 0
        regex.findAll(message).forEach {
            // 添加前面的普通文本
            val rangeFirst = it.range.first
            val rangeLast = it.range.last + 1
            if (rangeFirst != lastEnd) {
                val nodeText = message.substring(lastEnd, rangeFirst)
                nodes.add(AnnotatedTextNode.Text(nodeText))
            }
            val nodeText = message.substring(rangeFirst, rangeLast)
            if (nodeText.startsWith('[')) {
                val e = emote.find { it.text == nodeText }
                if (e != null) {
                    nodes.add(AnnotatedTextNode.Emote(
                        text = nodeText,
                        url = UrlUtil.autoHttps(e.url)
                    ))
                } else {
                    nodes.add(AnnotatedTextNode.Text(nodeText))
                }
            } else if (nodeText.startsWith('@')) {
                val name = nodeText.substring(1, nodeText.length)
                if (atNameToMid.containsKey(name)) {
                    nodes.add(AnnotatedTextNode.Link(
                        text = nodeText,
                        url = "bilimiao://user/${atNameToMid[name]}"
                    ))
                } else {
                    nodes.add(AnnotatedTextNode.Text(nodeText))
                }
            } else if (nodeText.matches(TIMESTAMP_REGEX)) {
                val seconds = nodeText.split(TIME_SEP_REGEX).map { it.toInt() }.let { parts ->
                    when (parts.size) {
                        2 -> parts[0] * 60 + parts[1]
                        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                        else -> 0
                    }
                }
                nodes.add(AnnotatedTextNode.Link(
                    text = nodeText,
                    url = "bilimiao://seek/$seconds"
                ))
            } else {
                nodes.add(AnnotatedTextNode.Link(
                    text = nodeText,
                    url = getLinkUrl(nodeText)
                ))
            }
            lastEnd = rangeLast
        }
        if (lastEnd < message.length) {
            val nodeText = message.substring(lastEnd, message.length)
            nodes.add(AnnotatedTextNode.Text(nodeText))
        }
        return nodes
    }


    // 纯字符串处理，不需要 composition（原来标了 @Composable，挪出 @Composable 上下文后要去掉）
    private fun getLinkUrl(text: String): String {
        val url = if (text.startsWith("http")) {
            text
        } else if (text.startsWith("av") || text.startsWith("AV")){
            "bilimiao://video/${text.substring(2, text.length)}"
        } else if (text.startsWith("BV")){
            "bilimiao://video/$text"
        } else if (text.startsWith("sm") || text.startsWith("SM")){
            "https://www.nicovideo.jp/watch/$text"
        } else if (text.startsWith("ac") || text.startsWith("AC")){
            "https://www.acfun.cn/v/${text}"
        } else if (text.startsWith("cv") || text.startsWith("CV")){
            "https://www.bilibili.com/read/${text}"
        } else {
            "http://$text"
        }
        return url
    }
}

@Composable
fun ReplyItemBox(
    modifier: Modifier = Modifier,
    item: bilibili.main.community.reply.v1.ReplyInfo,
    isUpper: Boolean = false,
    showDelete: Boolean = false,
    onAvatarClick: () -> Unit = {},
    onLikeClick: () -> Unit = {},
    onReplyClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onClick: () -> Unit = {},
    /** 一级评论下直接带出几条二级回复（设置项「显示二级回复」；默认关 = 原版行为） */
    showSubReplies: Boolean = false,
    /** 二级回复的屏蔽词判定（true = 屏蔽掉、不显示），与一级评论共用同一套规则 */
    isSubReplyBlocked: (bilibili.main.community.reply.v1.ReplyInfo) -> Boolean = { false },
) {
    val content = remember(item.content) {
        item.content?.let {
            ReplyItemBoxContentInfo(
                message = it.message,
                emote = it.emote.values.filterNotNull().map { emote ->
                    ReplyItemBoxContentInfo.EmoteInfo(
                        emote.id, emote.text, emote.url
                    )
                },
                url = it.url.values.filterNotNull().map { url ->
                    ReplyItemBoxContentInfo.UrlInfo(
                        url.title, url.pcUrl
                    )
                },
                atNameToMid = it.atNameToMid
            )
        }
    }
    val picturesList = remember(item.content) {
        item.content?.pictures?.mapNotNull {
            val imgHeight = it.imgHeight.toInt()
            val imgWidth = it.imgWidth.toInt()
            val imgSize = it.imgSize.toInt()
            // 元数据异常保护：宽或高为 0 时跳过该图，避免除零崩溃 (upstream #290)
            if (imgWidth <= 0 || imgHeight <= 0) {
                return@mapNotNull null
            }
            val w = min(600, imgWidth)
            val h = w * imgHeight / imgWidth
            val url = UrlUtil.autoHttps(it.imgSrc)
            PreviewImageModel(
                previewUrl = url + "@${w}w_${h}h",
                originalUrl = url,
                height = imgHeight.toFloat(),
                width = imgWidth.toFloat(),
            )
        } ?: listOf()
    }
    // ★ 二级回复预览：数据就挂在接口返回的一级评论上（ReplyInfo.replies），**不需要多打任何请求**。
    //   只渲染前 SUB_REPLY_PREVIEW_MAX 条，且和一级评论一样过屏蔽词（含 /正则/）。
    //   **不放「共N条回复」那一行**：下面点赞/回复那排里，回复图标后面的数字就是总条数，重复了。
    //   预览全被屏蔽时这一块直接不显示，进楼中楼由下排那个数字负责。
    val subReplyPreview = remember(item.replies, item.count, showSubReplies, isSubReplyBlocked) {
        if (!showSubReplies || item.count <= 0L) {
            emptyList()
        } else {
            item.replies.filterNot(isSubReplyBlocked)
                .take(SUB_REPLY_PREVIEW_MAX)
                .map { sub ->
                    SubReplyPreviewInfo(
                        uname = sub.member?.name ?: "",
                        content = sub.content?.let { c ->
                            ReplyItemBoxContentInfo(
                                message = c.message,
                                emote = c.emote.values.filterNotNull().map { emote ->
                                    ReplyItemBoxContentInfo.EmoteInfo(emote.id, emote.text, emote.url)
                                },
                                url = c.url.values.filterNotNull().map { url ->
                                    ReplyItemBoxContentInfo.UrlInfo(url.title, url.pcUrl)
                                },
                                atNameToMid = c.atNameToMid,
                            )
                        },
                    )
                }
        }
    }
    ReplyItemBox(
        modifier = modifier,
        oid = item.oid,
        id = item.id,
        mid = item.mid,
        uname = item.member?.name ?: "",
        avatar = item.member?.face ?: "",
        time = NumberUtil.converCTime(item.ctime),
        location = item.replyControl?.location ?: "",
        floor = 0,
        content = content,
        picturesList = picturesList,
        like = item.like,
        count = item.count,
        cardLabels = item.replyControl?.cardLabels?.map { it.textContent } ?: emptyList(),
        isUpper = isUpper,
        showDelete = showDelete,
        isLike = item.replyControl?.action == 1L,
        subReplies = subReplyPreview,
        onAvatarClick = onAvatarClick,
        onLikeClick = onLikeClick,
        onReplyClick = onReplyClick,
        onDeleteClick = onDeleteClick,
        onClick = onClick,
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ReplyItemBox(
    modifier: Modifier = Modifier,
    oid: Long,
    id: Long,
    mid: Long,
    uname: String,
    avatar: String,
    time: String,
    location: String,
    floor: Int,
    content: ReplyItemBoxContentInfo?,
    picturesList: List<PreviewImageModel>,
    like: Long,
    count: Long,
    cardLabels: List<String>,
    isUpper: Boolean = false,
    showDelete: Boolean = false,
    isLike: Boolean = false,
    /** 二级回复预览（空 = 不显示这块） */
    subReplies: List<SubReplyPreviewInfo> = emptyList(),
    onAvatarClick: () -> Unit = {},
    onLikeClick: () -> Unit = {},
    onReplyClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onClick: () -> Unit = {},
    onSubReplyClick: () -> Unit = onReplyClick,
) {
    Row(
        Modifier
            .clickable(onClick = onClick)
            .padding(10.dp)
            .then(modifier)
    ) {
        GlideImage(
            model = UrlUtil.autoHttps(avatar) + "@200w_200h",
            loading = placeholder(R.drawable.bili_akari_img),
            contentDescription = null,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onAvatarClick)
        )
        Column(
            modifier = Modifier
                .padding(start = 5.dp)
                .weight(1f)
        ) {
            Row(
                modifier = Modifier.padding(bottom = 2.dp)
                    .clickable(onClick = onAvatarClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = uname,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isUpper) {
                    Text(
                        text = "UP主",
                        maxLines = 1,
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(vertical = 2.dp, horizontal = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 10.sp,
                        lineHeight = 10.sp
                    )
                }
            }
            Row(
                modifier = Modifier.padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = time,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.outline,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
                if (floor != 0) {
                    Text(
                        text = "#${floor}",
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.outline,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (location.isNotBlank()) {
                    Text(
                        text = location.replace("IP属地", "").replace("：", "").replace(":", "").trim(),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.outline,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (content != null) {
                SelectionContainer {
                    val nodes = content.toAnnotatedTextNode()
                    val emoteMap = inlineAnnotatedContent(nodes)
                    Text(
                        annotatedText(nodes),
                        inlineContent = emoteMap,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (picturesList.isNotEmpty()) {
                Box(modifier = Modifier.padding(
                    vertical = 5.dp
                )) {
                    ImagesGrid(picturesList)
                }
            }
            // 二级回复预览（设置项「显示二级回复」打开时才非空）；点整块进楼中楼看全部
            if (subReplies.isNotEmpty()) {
                SubReplyPreviewBox(
                    subReplies = subReplies,
                    onClick = onSubReplyClick,
                )
            }
            Row(
                modifier = Modifier.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(40.dp)
            ) {
                Row(
                    modifier = Modifier.clickable(
                        onClick = onLikeClick,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ScaleIndication,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    var likeNum = like
                    if (isLike) {
                        Icon(
                            BilimiaoIcons.Common.Likefill,
                            contentDescription = "like",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp)
                                .size(14.dp)
                        )
                        likeNum = max(1L, likeNum)
                    } else {
                        Icon(
                            BilimiaoIcons.Common.Like,
                            contentDescription = "like",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(end = 4.dp)
                                .size(14.dp)
                        )
                        likeNum = max(0L, likeNum) // 防止出现负数
                    }
                    Text(
                        text = NumberUtil.converString(likeNum),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    modifier = Modifier.clickable(
                        onClick = onReplyClick,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ScaleIndication,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        BilimiaoIcons.Common.Reply,
                        contentDescription = "reply",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(end = 4.dp)
                            .size(14.dp)
                    )
                    Text(
                        NumberUtil.converString(count),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (showDelete) {
                    Box(
                        modifier = Modifier.clickable(
                            onClick = onDeleteClick,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ScaleIndication,
                        ),
                    ) {
                        Icon(
                            BilimiaoIcons.Common.Delete,
                            contentDescription = "reply",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(end = 4.dp)
                                .size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一级评论下面的二级回复预览块（对齐官方客户端的观感）：
 * 圆角浅底，里面每行是「用户名 + 回复 @某某 :内容」（最多两行，超出打省略号）。
 * 整块可点 —— 点哪一行都进楼中楼看全部。
 * 这里**不显示「共N条回复」**：下排回复图标后面的数字就是总条数。
 */
@Composable
private fun SubReplyPreviewBox(
    subReplies: List<SubReplyPreviewInfo>,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(top = 5.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = ScaleIndication,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        subReplies.forEach { sub ->
            SubReplyPreviewRow(sub)
        }
    }
}

@Composable
private fun SubReplyPreviewRow(sub: SubReplyPreviewInfo) {
    val content = sub.content
    if (content == null) return
    val unameColor = MaterialTheme.colorScheme.primary
    val nodes = content.toAnnotatedTextNode()
    val emoteMap = inlineAnnotatedContent(nodes, size = 16.sp)
    val message = annotatedText(nodes)
    // 用户名和正文要在同一段里连排（正文可能带表情/链接，所以先拿到 AnnotatedString 再拼）
    val text = remember(sub.uname, message, unameColor) {
        buildAnnotatedString {
            if (sub.uname.isNotBlank()) {
                withStyle(SpanStyle(color = unameColor)) { append(sub.uname) }
                append(" ")
            }
            append(message)
        }
    }
    SelectionContainer {
        Text(
            text = text,
            inlineContent = emoteMap,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
