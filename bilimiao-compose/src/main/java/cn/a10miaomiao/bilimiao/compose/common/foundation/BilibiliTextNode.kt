package cn.a10miaomiao.bilimiao.compose.common.foundation

import androidx.compose.runtime.Composable
import bilibili.app.dynamic.v2.TextNode

@Composable
fun List<TextNode>.toAnnotatedTextNode(): List<AnnotatedTextNode> {
    return mapNotNull {
        val node = it.text
        when (node) {
            is TextNode.Text.Word -> {
                AnnotatedTextNode.Text(node.value.words)
            }

            is TextNode.Text.Link -> {
                var showText = node.value.showText
                if (showText.isEmpty()) {
                    AnnotatedTextNode.Link("", node.value.link, false)
                } else {
                    // 第一个通常是换行符(0x0a)，然后第二个是换页符(0x0c)，
                    // 这里直接去除，然后是显示文字部分，到(0x11)结束
                    var showTextStart = 0
                    var showTextEnd = showText.length
                    var withLineBreak = false
                    if (showText[0].code == 0x0a) {
                        showTextStart = 1
                        withLineBreak = true
                    } else if (showText[0].code == 0x0c) {
                        showTextStart = 1
                    }
                    if (showText.length > 1 && showText[1].code == 0x0c) {
                        showTextStart = 2
                    }
                    if (showTextStart > 0) {
                        val idx = showText.indexOf(0x11.toChar())
                        showTextEnd = if (idx >= 0) idx else showText.length
                    }
                    showText = showText.substring(showTextStart, showTextEnd)
                    AnnotatedTextNode.Link(showText, node.value.link, withLineBreak)
                }
            }

            is TextNode.Text.Emote -> {
                val emote = node.value
                val emoteId = emote.rawText?.words ?: emote.emoteUrl
                AnnotatedTextNode.Emote(
                    text = emoteId,
                    url = emote.emoteUrl,
//                        width = emote.emoteWidth?.width?.toInt() ?: 20,
//                        height = (emote.emoteWidth?.emojiSize ?: 2) * 16,
                )
            }

            null -> null
        }
    }
}
