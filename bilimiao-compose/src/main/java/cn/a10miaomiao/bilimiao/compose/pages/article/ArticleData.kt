package cn.a10miaomiao.bilimiao.compose.pages.article

import org.json.JSONArray

data class TextNode(
    val text: String = "",
    val fontSize: Int = 17,
    val color: String = "",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    // 富文本节点（@提及/话题/网页链接等）。nodeKind = rich.type（RICH_TEXT_NODE_TYPE_*），
    // 为空表示普通文字；jumpUrl 为跳转链接；atMid 为 @ 用户的 mid（RICH_TEXT_NODE_TYPE_AT）
    val nodeKind: String = "",
    val jumpUrl: String = "",
    val atMid: String = "",
)

sealed class ArticleParagraph {
    data class TextParagraph(
        val nodes: List<TextNode>,
        val align: String = "left",
    ) : ArticleParagraph()

    data class ImageParagraph(
        val url: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val caption: String = "",
    ) : ArticleParagraph()
}

/**
 * 解析 opus/专栏正文段落。专栏接口(/x/article/view)与 web-dynamic opus detail 的
 * module_content.paragraphs 共用同一结构，供专栏页与动态 opus 页复用。
 */
fun parseArticleParagraphs(jsonArray: JSONArray?): List<ArticleParagraph> {
    if (jsonArray == null) return emptyList()
    val list = mutableListOf<ArticleParagraph>()
    var skipNext = false
    for (i in 0 until jsonArray.length()) {
        if (skipNext) {
            skipNext = false
            continue
        }
        val item = jsonArray.optJSONObject(i) ?: continue
        val paraType = item.optInt("para_type", 1)

        when (paraType) {
            1 -> {
                val format = item.optJSONObject("format")
                val align = format?.optString("align") ?: "left"
                if (align == "center" && i > 0) {
                    val prev = jsonArray.optJSONObject(i - 1)
                    if (prev?.optInt("para_type") == 2) {
                        continue
                    }
                }
                val textObj = item.optJSONObject("text")
                val nodes = textObj?.optJSONArray("nodes")
                val textNodes = mutableListOf<TextNode>()
                if (nodes != null) {
                    for (j in 0 until nodes.length()) {
                        val node = nodes.optJSONObject(j) ?: continue
                        val wordObj = node.optJSONObject("word")
                        if (wordObj != null) {
                            // 普通文字节点
                            val words = wordObj.optString("words") ?: ""
                            val fontSize = wordObj.optInt("font_size") ?: 17
                            val color = wordObj.optString("color") ?: ""
                            val styleObj = wordObj.optJSONObject("style")
                            textNodes.add(
                                TextNode(
                                    text = words,
                                    fontSize = fontSize,
                                    color = color,
                                    bold = styleObj?.optBoolean("bold", false) ?: false,
                                    italic = styleObj?.optBoolean("italic", false) ?: false,
                                    underline = styleObj?.optBoolean("underline", false) ?: false,
                                )
                            )
                        } else {
                            val richObj = node.optJSONObject("rich")
                            if (richObj != null) {
                                // 富文本节点：@提及/话题/网页链接/抽奖/表情等
                                val richType = richObj.optString("type")
                                val styleObj = richObj.optJSONObject("style")
                                textNodes.add(
                                    TextNode(
                                        text = richObj.optString("text")
                                            .ifBlank { richObj.optString("orig_text") },
                                        fontSize = 17,
                                        color = "",
                                        bold = styleObj?.optBoolean("bold", false) ?: false,
                                        italic = styleObj?.optBoolean("italic", false) ?: false,
                                        underline = styleObj?.optBoolean("underline", false) ?: false,
                                        nodeKind = richType,
                                        jumpUrl = richObj.optString("jump_url"),
                                        atMid = if (richType == "RICH_TEXT_NODE_TYPE_AT") {
                                            richObj.optString("rid")
                                        } else {
                                            ""
                                        },
                                    )
                                )
                            } else {
                                val formulaObj = node.optJSONObject("formula")
                                if (formulaObj != null) {
                                    // 公式节点：无 LaTeX 渲染时退化为显示原文
                                    textNodes.add(
                                        TextNode(
                                            text = formulaObj.optString("latex_content"),
                                            fontSize = 17,
                                            nodeKind = "formula",
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                list.add(ArticleParagraph.TextParagraph(textNodes, align))
            }
            2 -> {
                val picObj = item.optJSONObject("pic")
                val pics = picObj?.optJSONArray("pics")
                var caption = ""
                if (pics != null && pics.length() > 0) {
                    val firstPic = pics.optJSONObject(0)
                    val url = firstPic?.optString("url") ?: ""
                    val width = firstPic?.optInt("width") ?: 0
                    val height = firstPic?.optInt("height") ?: 0

                    if (i + 1 < jsonArray.length()) {
                        val nextItem = jsonArray.optJSONObject(i + 1)
                        if (nextItem?.optInt("para_type") == 1) {
                            val nextFormat = nextItem.optJSONObject("format")
                            if (nextFormat?.optInt("align") == 1) {
                                val nextTextObj = nextItem.optJSONObject("text")
                                val nextNodes = nextTextObj?.optJSONArray("nodes")
                                if (nextNodes != null && nextNodes.length() > 0) {
                                    val firstNode = nextNodes.optJSONObject(0)
                                    caption = firstNode?.optJSONObject("word")?.optString("words") ?: ""
                                    skipNext = true
                                }
                            }
                        }
                    }
                    list.add(ArticleParagraph.ImageParagraph(url, width, height, caption))
                }
            }
        }
    }
    return list
}

data class ArticleData(
    val id: Long = 0,
    val title: String = "",
    val bannerUrl: String = "",
    val authorName: String = "",
    val authorFace: String = "",
    val authorMid: Long = 0,
    val viewCount: Int = 0,
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    val favoriteCount: Int = 0,
    val coinCount: Int = 0,
    val shareCount: Int = 0,
    val publishTime: Long = 0,
    val words: Int = 0,
    val paragraphs: List<ArticleParagraph> = emptyList(),
)