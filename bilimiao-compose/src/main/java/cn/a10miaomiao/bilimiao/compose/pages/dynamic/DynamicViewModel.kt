package cn.a10miaomiao.bilimiao.compose.pages.dynamic

import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.navOptions
import bilibili.app.dynamic.v2.DynamicGRPC
import bilibili.app.dynamic.v2.UpListItem
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.pages.home.HomePage
import cn.a10miaomiao.bilimiao.compose.pages.user.FollowingsInfo
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.user.UserInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

class DynamicViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val fragment by instance<Fragment>()
    private val pageNavigation by instance<PageNavigation>()
    private val userStore by instance<UserStore>()

    private val _upList = MutableStateFlow(listOf<UpListItem>())
    val upList: StateFlow<List<UpListItem>> get() = _upList
    private val _selectedUpper = MutableStateFlow<UpListItem?>(null)
    val selectedUpper: StateFlow<UpListItem?> get() = _selectedUpper

    /**
     * 全部关注（第 1 页 50 个，"最常访问"排序）。
     * dynAll 的 upList 只带**有更新的常访问**那几个（只关注 4 个人的账号实测只回 2 个），
     * 拿它当侧栏列表会让"明明还有空间却只显示两三个" —— 这里补上完整的关注列表。
     */
    private var allFollows: List<UpListItem> = emptyList()

    init {
        loadUpList()
    }

    private fun loadUpList() = viewModelScope.launch(Dispatchers.IO) {
        // 先拿全部关注（失败也无所谓，侧栏退回只有 dynAll 那几个人）
        loadAllFollows()
        try {
            val req = bilibili.app.dynamic.v2.DynAllReq(
                refreshType = bilibili.app.dynamic.v2.Refresh.NEW,
                localTime = 8,
                from = "3",
                coldStart = 1,
            )
            val result = BiliGRPCHttp.request {
                DynamicGRPC.dynAll(req)
            }.awaitCall()
            val list = mutableListOf<UpListItem>()
            result.upList?.let {
                list.addAll(it.list)
                list.addAll(it.listSecond)
            }
            applyUpList(list, autoSelect = true)
        } catch (e: Exception) {
            e.printStackTrace()
            // dynAll 挂了也先把关注列表摆上，别让侧栏空着
            applyUpList(emptyList(), autoSelect = true)
        }
    }

    /** 全部关注：order_type=attention 就是"最常访问"排序（和侧栏想要的顺序一致） */
    private suspend fun loadAllFollows() {
        val mid = userStore.state.info?.mid ?: return
        try {
            val res = BiliApiService.userRelationApi
                .followings(
                    mid = mid.toString(),
                    pageNum = 1,
                    pageSize = 50,
                    order = "attention",
                )
                .awaitCall()
                .json<ResponseData<FollowingsInfo>>()
            if (res.isSuccess) {
                allFollows = res.data?.list.orEmpty().mapNotNull { item ->
                    val uid = item.mid.toLongOrNull() ?: return@mapNotNull null
                    if (uid <= 0L) return@mapNotNull null
                    UpListItem(
                        face = item.face,
                        name = item.uname,
                        uid = uid,
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 官方列表在前（官方那份是"有更新的常访问"，排最前最有意义），其余关注按最常访问顺序补在后，按 uid 去重。
     * 人再多也就是往下滚，列表最底下还有「更多关注」进完整关注页。
     *
     * @param autoSelect 首次加载且还没选过 UP 时，默认选第一个（保持原行为）
     */
    private fun applyUpList(
        fromServer: List<UpListItem>,
        autoSelect: Boolean = false,
    ) {
        val merged = LinkedHashMap<Long, UpListItem>()
        fromServer.forEach { if (it.uid > 0L) merged[it.uid] = it }
        allFollows.forEach { if (!merged.containsKey(it.uid)) merged[it.uid] = it }
        val list = merged.values.toList()
        _upList.value = list
        if (autoSelect && _selectedUpper.value == null && list.isNotEmpty()) {
            _selectedUpper.value = list.first()
        }
    }

    fun toHomePage() {
        val nav = pageNavigation.hostController
        nav.navigate(HomePage, navOptions {
            popUpTo(nav.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        })
    }

    fun menuItemClick(view: View, item: MenuItemPropInfo) {
        when (item.key) {
            MenuKeys.home -> {
                toHomePage()
            }
        }
    }

    fun setUpList(list: List<UpListItem>) {
        // 动态列表刷新时官方那份只带回有更新的人 → 仍然和"全部关注"合并，别把侧栏缩回去
        applyUpList(list)
    }

    fun selectUpper(item: UpListItem) {
        _selectedUpper.value = item
    }

    fun selectMyDynamics(userInfo: UserInfo?) {
        userInfo?.let {
            _selectedUpper.value = UpListItem(
                uid = it.mid,
                name = it.name,
                face = it.face ?: "",
            )
        }
    }

}
