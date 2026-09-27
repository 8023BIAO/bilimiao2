package cn.a10miaomiao.bilimiao.compose.pages.user

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.components.input.SearchBox
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.user.UserInfoCard
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
class SearchFollowPage : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: SearchFollowPageViewModel = diViewModel()
        SearchFollowPageContent(viewModel)
    }
}

@OptIn(FlowPreview::class)
private class SearchFollowPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val activity by instance<Activity>()
    private val pageNavigation by instance<PageNavigation>()
    private val userStore by instance<UserStore>()

    val searchText = MutableStateFlow("")
    val isRefreshing = MutableStateFlow(false)
    // 接口默认一页 50（老代码用全局默认 20，搜索结果被硬截断）
    val list = FlowPaginationInfo<FollowingItemInfo>(pageSize = 50)

    init {
        viewModelScope.launch {
            // 防抖：原来每敲一个字就发一次请求，结果区还会整块闪成"加载中"
            // collectLatest：新关键词到达时取消上一次的等待/请求。
            // 原来用 collect + `if (!loading)` 判断，请求在途时敲的新词会被静默丢掉
            //（输入框显示新词、列表还是旧词的结果）
            searchText.debounce(300).collectLatest {
                loadData(it)
            }
        }
    }

    fun loadMore() {
        if (!list.finished.value && !list.loading.value) {
            loadData(searchText.value, list.pageNum + 1)
        }
    }

    fun loadData(
        name: String,
        pageNum: Int = 1,
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val mid = userStore.state.info?.mid ?: return@launch
            list.loading.value = true
            list.fail.value = ""   // 开始加载就清掉上一次的失败提示
            val res = BiliApiService.userRelationApi
                .search(
                    mid = mid.toString(),
                    name = name,
                    pageNum = pageNum,
                    pageSize = list.pageSize,
                )
                .awaitCall()
                .json<ResponseData<FollowingsInfo>>()
            if (res.isSuccess) {
                list.pageNum = pageNum
                list.finished.value = res.requireData().list.isEmpty()
                if (pageNum == 1) {
                    list.data.value = res.requireData().list
                } else {
                    list.data.value = mutableListOf<FollowingItemInfo>().apply {
                        addAll(list.data.value)
                        addAll(res.requireData().list)
                    }
                }
                list.finished.value = res.requireData().list.size < list.pageSize
            } else {
                list.fail.value = res.message
            }
        } catch (e: Exception) {
            e.printStackTrace()
            list.fail.value = e.message ?: e.toString()
        } finally {
            list.loading.value = false
            isRefreshing.value = false
            if (name != searchText.value) {
                tryAgainLoadData(searchText.value)
            }
        }
    }

    fun tryAgainLoadData(
        name: String = searchText.value
    ) {
        loadData(name)
    }

    fun updateSearchText(value: String) {
        searchText.value = value
    }

    fun toUserDetailPage(id: String) {
        pageNavigation.navigate(UserSpacePage(id))
    }

}


@Composable
private fun SearchFollowPageContent(
    viewModel: SearchFollowPageViewModel
) {
    PageConfig(
        title = "搜索我的关注"
    )
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val searchText by viewModel.searchText.collectAsStateWithLifecycle()
    val list by viewModel.list.data.collectAsStateWithLifecycle()
    val listLoading by viewModel.list.loading.collectAsStateWithLifecycle()
    val listFinished by viewModel.list.finished.collectAsStateWithLifecycle()
    val listFail by viewModel.list.fail.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize()
            .padding(10.dp)
            .padding(top = windowInsets.topDp.dp)
    ) {
        SearchBox(
            value = searchText,
            onValueChange = viewModel::updateSearchText,
            modifier = Modifier.height(40.dp)
                .fillMaxWidth(),
            placeholder = {
                Text("搜索我的关注")
            }
        )
        Box(
            modifier = Modifier.weight(1f)
        ) {
            if (listLoading && list.isEmpty()) {
                ListStateBox(loading = true)
            } else if (listFail.isNotBlank() && list.isEmpty()) {
                ListStateBox(
                    fail = listFail,
                    loadMore = viewModel::tryAgainLoadData
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(400.dp),
                    modifier = Modifier.padding(
                        start = windowInsets.leftDp.dp,
                        end = windowInsets.rightDp.dp,
                    )
                ) {
                    items(list.size, { list[it].mid }) {
                        val item = list[it]
                        Box(
                            modifier = Modifier.padding(vertical = 5.dp),
                        ) {
                            UserInfoCard(
                                name = item.uname,
                                face = item.face,
                                sign = item.sign,
                                onClick = {
                                    viewModel.toUserDetailPage(item.mid)
                                },
                                actionContent = {}
                            )
                        }
                    }
                    item(
                        span = {
                            GridItemSpan(maxLineSpan)
                        }
                    ) {
                        // 补上"加载更多/下面没有了"：原来结果被硬截断在一页，
                        // 用户会以为关注列表里根本没有这个人
                        ListStateBox(
                            loading = listLoading,
                            finished = listFinished,
                            fail = listFail,
                            listData = list,
                        ) {
                            viewModel.loadMore()
                        }
                        Spacer(modifier = Modifier.height(windowInsets.bottomDp.dp))
                    }
                }
            }

        }
    }

}