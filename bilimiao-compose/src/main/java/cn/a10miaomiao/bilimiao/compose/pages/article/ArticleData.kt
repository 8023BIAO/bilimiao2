package cn.a10miaomiao.bilimiao.compose.pages.article

data class TextNode(
    val text: String = "",
    val fontSize: Int = 17,
    val color: String = "",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
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