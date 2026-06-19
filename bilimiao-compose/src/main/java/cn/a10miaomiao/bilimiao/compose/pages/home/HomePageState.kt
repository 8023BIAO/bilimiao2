package cn.a10miaomiao.bilimiao.compose.pages.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation

@Stable
class HomePageState(
    val pageNavigation: PageNavigation
) {

    private val _forceVisible = mutableStateOf(true)
    val forceVisible: State<Boolean> get() = _forceVisible

    fun setForceVisible(visible: Boolean) {
        _forceVisible.value = visible
    }

}