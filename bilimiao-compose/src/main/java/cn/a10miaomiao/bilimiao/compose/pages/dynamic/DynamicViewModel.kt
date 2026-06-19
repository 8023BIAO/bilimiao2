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
import com.a10miaomiao.bilimiao.comm.entity.user.UserInfo
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
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

    private val _upList = MutableStateFlow(listOf<UpListItem>())
    val upList: StateFlow<List<UpListItem>> get() = _upList
    private val _selectedUpper = MutableStateFlow<UpListItem?>(null)
    val selectedUpper: StateFlow<UpListItem?> get() = _selectedUpper

    init {
        loadUpList()
    }

    private fun loadUpList() = viewModelScope.launch(Dispatchers.IO) {
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
            result.upList?.let {
                val list = mutableListOf<UpListItem>()
                list.addAll(it.list)
                list.addAll(it.listSecond)
                _upList.value = list
                if (list.isNotEmpty()) {
                    _selectedUpper.value = list[0]
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        _upList.value = list
        // 不自动选中，保留用户当前选择
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
