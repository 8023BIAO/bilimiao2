package cn.a10miaomiao.bilimiao.compose.pages.article

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import com.a10miaomiao.bilimiao.comm.apis.ArticleAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

class ArticleReaderViewModel(
    override val di: DI,
    val articleId: Long,
) : ViewModel(), DIAware {

    private val pageNavigation: PageNavigation by instance()

    val article = MutableStateFlow<ArticleData?>(null)
    val isLoading = MutableStateFlow(true)
    val isRefreshing = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    init {
        loadArticle()
    }

    fun loadArticle() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                isLoading.value = true
                error.value = null

                val response = ArticleAPI().view(articleId.toString())
                val result = response.awaitCall()
                val bodyStr = result.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val code = json.optInt("code", -1)
                if (code != 0) {
                    error.value = "加载失败: code=$code"
                    isLoading.value = false
                    return@launch
                }

                val data = json.optJSONObject("data") ?: run {
                    error.value = "数据为空"
                    isLoading.value = false
                    return@launch
                }

                val author = data.optJSONObject("author")
                val stats = data.optJSONObject("stats")
                val opus = data.optJSONObject("opus")
                val content = opus?.optJSONObject("content")

                val paragraphs = parseParagraphs(content?.optJSONArray("paragraphs"))

                article.value = ArticleData(
                    id = data.optLong("id"),
                    title = data.optString("title"),
                    bannerUrl = data.optString("banner_url"),
                    authorName = author?.optString("name") ?: "",
                    authorFace = author?.optString("face") ?: "",
                    authorMid = author?.optLong("mid") ?: 0L,
                    viewCount = stats?.optInt("view") ?: 0,
                    likeCount = stats?.optInt("like") ?: 0,
                    replyCount = stats?.optInt("reply") ?: 0,
                    favoriteCount = stats?.optInt("favorite") ?: 0,
                    coinCount = stats?.optInt("coin") ?: 0,
                    shareCount = stats?.optInt("share") ?: 0,
                    publishTime = data.optLong("publish_time"),
                    words = data.optInt("words"),
                    paragraphs = paragraphs,
                )
                isLoading.value = false
            } catch (e: Exception) {
                e.printStackTrace()
                error.value = e.message ?: "未知错误"
                isLoading.value = false
            } finally {
                isRefreshing.value = false
            }
        }
    }

    fun refresh() {
        isRefreshing.value = true
        loadArticle()
    }

    private fun parseParagraphs(jsonArray: JSONArray?): List<ArticleParagraph> {
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
                            val words = wordObj?.optString("words") ?: ""
                            val fontSize = wordObj?.optInt("font_size") ?: 17
                            val color = wordObj?.optString("color") ?: ""
                            val styleObj = wordObj?.optJSONObject("style")
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

    fun toAuthorPage() {
        val mid = article.value?.authorMid ?: return
        pageNavigation.navigateByUri(Uri.parse("bilibili://space/$mid"))
    }
}
