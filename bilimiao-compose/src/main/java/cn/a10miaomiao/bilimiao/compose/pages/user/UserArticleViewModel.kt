package cn.a10miaomiao.bilimiao.compose.pages.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.navigation.BilibiliNavigation
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.pages.article.ArticleReaderPage
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.user.SpaceArticleInfo
import com.a10miaomiao.bilimiao.comm.entity.user.SpaceArticleItem
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

/**
 * 用户空间「专栏」tab 的分页数据（对齐 PiliPlus `MemberArticleCtr` / `MemberHttp.spaceArticle`）。
 *
 * 接口：`app.bilibili.com/x/v2/space/article?vmid=&pn=&ps=`，返回 `count + item[]`。
 */
class UserArticleViewModel(
    override val di: DI,
    private val vmid: String,
) : ViewModel(), DIAware {

    private val pageNavigation by instance<PageNavigation>()

    val list = FlowPaginationInfo<SpaceArticleItem>(pageSize = 12)
    val isRefreshing = MutableStateFlow(false)

    private var totalCount = 0
    private var initialized = false

    fun initData() {
        if (!initialized && !list.loading.value) {
            initialized = true
            loadData()
        }
    }

    fun loadMore() {
        if (!list.finished.value && !list.loading.value) {
            loadData()
        }
    }

    fun refreshList() {
        if (isRefreshing.value) return
        isRefreshing.value = true
        list.reset()
        initialized = true
        loadData()
    }

    private fun loadData() = viewModelScope.launch(Dispatchers.IO) {
        try {
            list.loading.value = true
            list.fail.value = ""
            val res = BiliApiService.userApi
                .spaceArticle(
                    vmid = vmid,
                    pageNum = list.pageNum,
                    pageSize = list.pageSize,
                )
                .awaitCall()
                .json<ResponseData<SpaceArticleInfo>>()
            if (res.code == 0) {
                val data = res.requireData()
                totalCount = data.count ?: 0
                val items = data.item.orEmpty()
                if (list.pageNum == 1) {
                    list.data.value = items
                } else {
                    list.data.value = list.data.value + items
                }
                list.pageNum += 1
                list.finished.value = items.isEmpty() ||
                        items.size < list.pageSize ||
                        (totalCount > 0 && list.data.value.size >= totalCount)
            } else {
                list.fail.value = res.message.ifBlank { "加载失败" }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            list.fail.value = e.message ?: e.toString()
        } finally {
            list.loading.value = false
            isRefreshing.value = false
        }
    }

    /** 点击专栏：优先从 uri 里解析 cv 号进原生阅读页；解析失败再退回深链/网页。 */
    fun toArticle(item: SpaceArticleItem) {
        val raw = item.uri.orEmpty().ifBlank {
            val id = item.id?.takeIf { it > 0L } ?: item.cvid?.takeIf { it > 0L }
            if (id != null) "https://www.bilibili.com/read/cv$id" else ""
        }
        val cvId = Regex("(?i)(?:read/cv|read/mobile/|article/|cv)(\\d{1,})")
            .find(raw)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: item.id?.takeIf { it > 0L }
            ?: item.cvid?.takeIf { it > 0L }
        if (cvId != null && cvId > 0L) {
            pageNavigation.navigate(ArticleReaderPage(cvId))
            return
        }
        if (raw.isNotBlank() && BilibiliNavigation.navigationTo(pageNavigation, raw)) {
            return
        }
        if (raw.isNotBlank()) {
            BilibiliNavigation.navigationToWeb(pageNavigation, raw)
        } else {
            toast("无法打开该专栏")
        }
    }
}
