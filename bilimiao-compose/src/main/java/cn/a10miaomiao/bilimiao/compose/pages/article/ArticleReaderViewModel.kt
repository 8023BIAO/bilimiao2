package cn.a10miaomiao.bilimiao.compose.pages.article

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import com.a10miaomiao.bilimiao.comm.apis.ArticleAPI
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
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

    /**
     * 两个入口共用本页：搜索进普通专栏 cv（小 id），opus 深链进动态 opus（长 id）。
     * 动态 opus 用 web-dynamic 接口（/x/article/view 对其返回 -404）。
     */
    private val isOpus get() = articleId >= 1_000_000_000_000L
    val replyType get() = if (isOpus) 11 else 12
    var commentId: String = ""
        private set

    init {
        loadArticle()
    }

    fun loadArticle() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                isLoading.value = true
                error.value = null
                article.value = if (isOpus) loadOpus() else loadCv()
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

    /** 动态 opus：web-dynamic opus 详情（module_type 为 TITLE/AUTHOR/TOPIC/CONTENT/STAT） */
    private suspend fun loadOpus(): ArticleData {
        val response = MiaoHttp.request {
            url = BiliApiService.biliApi(
                "x/polymer/web-dynamic/v1/opus/detail",
                "id" to articleId.toString(),
                "features" to "htmlNewStyle",
                "timezone_offset" to "-480",
            )
        }.awaitCall()
        val json = JSONObject(response.body?.string() ?: "")
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw Exception("加载失败: code=$code message=${json.optString("message")}")
        }
        val item = json.optJSONObject("data")?.optJSONObject("item")
        val modules = item?.optJSONArray("modules")

        var title = ""
        var authorName = ""; var authorFace = ""; var authorMid = 0L; var pubTs = 0L
        var paragraphs = emptyList<ArticleParagraph>()
        var like = 0; var favorite = 0; var reply = 0

        commentId = item?.optJSONObject("basic")?.optString("comment_id_str") ?: ""

        if (modules != null) {
            for (i in 0 until modules.length()) {
                val m = modules.optJSONObject(i) ?: continue
                when (m.optString("module_type")) {
                    "MODULE_TYPE_TITLE" -> {
                        title = m.optJSONObject("module_title")?.optString("text") ?: ""
                    }
                    "MODULE_TYPE_AUTHOR" -> {
                        val a = m.optJSONObject("module_author")
                        authorName = a?.optString("name") ?: ""
                        authorFace = a?.optString("face") ?: ""
                        authorMid = a?.optLong("mid") ?: 0L
                        pubTs = a?.optLong("pub_ts") ?: 0L
                    }
                    "MODULE_TYPE_CONTENT" -> {
                        paragraphs = parseArticleParagraphs(
                            m.optJSONObject("module_content")?.optJSONArray("paragraphs")
                        )
                    }
                    "MODULE_TYPE_STAT" -> {
                        val s = m.optJSONObject("module_stat")
                        like = s?.optJSONObject("like")?.optInt("count") ?: 0
                        favorite = s?.optJSONObject("favorite")?.optInt("count") ?: 0
                        reply = s?.optJSONObject("comment")?.optInt("count") ?: 0
                    }
                }
            }
        }
        return ArticleData(
            id = articleId,
            title = title,
            authorName = authorName,
            authorFace = authorFace,
            authorMid = authorMid,
            likeCount = like,
            favoriteCount = favorite,
            replyCount = reply,
            publishTime = pubTs,
            paragraphs = paragraphs,
        )
    }

    /** 普通专栏 cv：/x/article/view */
    private suspend fun loadCv(): ArticleData {
        val response = ArticleAPI().view(articleId.toString())
        val result = response.awaitCall()
        val bodyStr = result.body?.string() ?: ""
        val json = JSONObject(bodyStr)
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw Exception("加载失败: code=$code")
        }

        val data = json.optJSONObject("data") ?: throw Exception("数据为空")

        val author = data.optJSONObject("author")
        val stats = data.optJSONObject("stats")
        val opus = data.optJSONObject("opus")
        val content = opus?.optJSONObject("content")

        val paragraphs = parseArticleParagraphs(content?.optJSONArray("paragraphs"))

        return ArticleData(
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
    }

    fun refresh() {
        isRefreshing.value = true
        loadArticle()
    }

    private fun parseParagraphs(jsonArray: JSONArray?): List<ArticleParagraph> {
        return parseArticleParagraphs(jsonArray)
    }

    fun toAuthorPage() {
        val mid = article.value?.authorMid ?: return
        pageNavigation.navigateByUri(Uri.parse("bilibili://space/$mid"))
    }
}
