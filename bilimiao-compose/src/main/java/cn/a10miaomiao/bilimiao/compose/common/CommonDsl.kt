package cn.a10miaomiao.bilimiao.compose.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.navigation.NavOptions
import cn.a10miaomiao.bilimiao.compose.R
import org.kodein.di.DI
import org.kodein.di.compose.localDI

/**
 * 只带"同一路由不重复入栈"的导航参数。
 *
 * 为什么单独一份：`PageNavigation.navigate()` 的默认路径（不传 navOptions）以前**什么保护都没有**，
 * 连点同一个入口 N 次就往返回栈压 N 层、要按 N 次返回。首页番剧/影视入口一直是显式传
 * `launchSingleTop = true` 的，所以用户感觉"那些入口不会重复弹"—— 这里把默认值补齐。
 * 转场动画不在这里设（Compose 导航的 animation 由 NavHost 统一管），所以观感不变。
 */
val singleTopNavOptions get() = NavOptions.Builder()
    .setLaunchSingleTop(true)
    .build()

val defaultNavOptions get() = NavOptions.Builder()
    .setEnterAnim(R.anim.miao_fragment_open_enter)
    .setExitAnim(R.anim.miao_fragment_open_exit)
    .setPopEnterAnim(R.anim.miao_fragment_close_enter)
    .setPopExitAnim(R.anim.miao_fragment_close_exit)
    .build()

@Composable
inline fun <reified VM : ViewModel> diViewModel(
    di: DI = localDI(),
    key: String? = null,
): VM {
    return diViewModel(di, key) {
        val constructor = VM::class.java.getDeclaredConstructor(
            DI::class.java
        )
        constructor.newInstance(it)
    }
}

@OptIn(ExperimentalStdlibApi::class)
@Composable
inline fun <reified VM : ViewModel> diViewModel(
    di: DI = localDI(),
    key: String? = null,
    crossinline initializer: ((di: DI) -> VM),
): VM {
    return androidx.lifecycle.viewmodel.compose.viewModel<VM>(
        key = remember(key, di) {
            val diHex = di.hashCode().toHexString()
            (key ?: VM::class.simpleName) + diHex
        },
        initializer = {
            initializer(di)
        }
    )
}