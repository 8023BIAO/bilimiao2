package cn.a10miaomiao.bilimiao.compose.pages.setting

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.navigation.NavBackStackEntry
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.defaultNavOptions
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.preference.rememberPreferenceFlow
import cn.a10miaomiao.bilimiao.compose.pages.filter.FilterSettingPage
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.entity.miao.MiaoSettingInfo
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.preference
import me.zhanghai.compose.preference.preferenceCategory
import me.zhanghai.compose.preference.switchPreference
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
class SettingPage : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: SettingPageViewModel = diViewModel()
        SettingPageContent(viewModel)
    }
}

private class SettingPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val fragment by instance<Fragment>()
    private val pageNavigation by instance<PageNavigation>()

    // 远程设置列表已禁用，防止作者远程添加按钮/链接
    val moreSettingList = MutableStateFlow(listOf<MiaoSettingInfo>())

    fun toThemePage() {
        pageNavigation.navigate(ThemeSettingPage())
    }

    fun toHomeSettingPage() {
        pageNavigation.navigate(HomeSettingPage())
    }

    fun toVideoSettingPage() {
        pageNavigation.navigate(VideoSettingPage())
    }

    fun toDanmakuSettingPage() {
        pageNavigation.navigate(DanmakuSettingPage())
    }

    fun toFilterSettingPage() {
        pageNavigation.navigate(FilterSettingPage())
    }

    fun toFlagsSettingPage() {
        pageNavigation.navigate(FlagsSettingPage())
    }
}


@Composable
private fun SettingPageContent(
    viewModel: SettingPageViewModel
) {
    PageConfig(
        title = "设置"
    )
    val windowStore: WindowStore by rememberInstance()
    val userStore: UserStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val userState = userStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())
    val context = LocalContext.current
    // 远程设置列表已禁用 - 不收集远程数据
    // val moreSettingList by viewModel.moreSettingList.collectAsState()

    val dataStore = remember {
        SettingPreferences.run { context.dataStore }
    }
    val showLogoutDialog = remember {
        mutableStateOf(false)
    }
    ProvidePreferenceLocals(
        flow = rememberPreferenceFlow(dataStore)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = windowInsets.leftDp.dp,
                    end = windowInsets.rightDp.dp,
                )
        ) {
            item("top") {
                Spacer(
                    modifier = Modifier.height(windowInsets.topDp.dp)
                )
            }
            preferenceCategory(
                key = "general",
                title = {
                    Text( "常规")
                }
            )
            preference(
                key = "theme",
                title = {
                    Text("切换主题")
                },
                summary = {
                    Text("设置软件主题")
                },
                onClick = viewModel::toThemePage,
            )
            preference(
                key = "home",
                title = {
                    Text("首页设置")
                },
                summary = {
                    Text("自定义首页")
                },
                onClick = viewModel::toHomeSettingPage
            )
            preference(
                key = "video",
                title = {
                    Text("播放设置")
                },
                summary = {
                    Text("视频播放相关")
                },
                onClick = viewModel::toVideoSettingPage
            )
            preference(
                key = "danmaku",
                title = {
                    Text("弹幕设置")
                },
                summary = {
                    Text("弹幕显示与样式")
                },
                onClick = viewModel::toDanmakuSettingPage,
            )
            preferenceCategory(
                key = "filter_category",
                title = {
                    Text("屏蔽设置")
                }
            )
            preference(
                key = "filter",
                title = {
                    Text("屏蔽规则")
                },
                summary = {
                    Text("各种屏蔽规则")
                },
                onClick = viewModel::toFilterSettingPage
            )

            preferenceCategory(
                key = "other",
                title = {
                    Text("其他")
                }
            )
            preference(
                key = "flags_setting",
                title = {
                    Text("实验性功能")
                },
                summary = {
                    Text("包含可能不稳定的测试功能")
                },
                onClick = viewModel::toFlagsSettingPage,
            )

            // 远程设置列表已禁用 - 不显示任何远程加载的按钮
            if (userState.isLogin()) {
                preference(
                    key = "logout",
                    title = {
                        Text(
                            text = "退出登录",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = Color.Red,
                        )
                    },
                    onClick = {
                        showLogoutDialog.value = true
                    }
                )
             }
            
            item("bottom") {
                Spacer(
                    modifier = Modifier.height(
                        windowInsets.bottomDp.dp + windowStore.bottomAppBarHeightDp.dp
                    )
                )
            }
        }
    }

    if (showLogoutDialog.value) {
        AlertDialog(
            title = {
                Text(text = "提示")
            },
            text = {
                Text(text = "确认退出登录？")
            },
            onDismissRequest = {
                showLogoutDialog.value = false
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        userStore.logout()
                        showLogoutDialog.value = false
                    }
                ) {
                    Text(text = "确认")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog.value = false
                    }
                ) {
                    Text(text = "取消")
                }
            }
        )
    }
}