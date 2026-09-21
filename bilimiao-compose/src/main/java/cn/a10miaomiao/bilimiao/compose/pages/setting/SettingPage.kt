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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.navOptions
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.defaultNavOptions
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.preference.rememberPreferenceFlow
import cn.a10miaomiao.bilimiao.compose.pages.filter.FilterSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.filter.FilterRecommendSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.filter.FilterCommentSettingPage
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

    fun toAutoStopTimerPage() {
        pageNavigation.navigate(AutoStopTimerPage())
    }

    fun toBottomBarSettingPage() {
        pageNavigation.navigate(BottomBarSettingPage())
    }

    fun toDisplayScaleSettingPage() {
        pageNavigation.navigate(DisplayScaleSettingPage())
    }

    fun toFilterRecommendPage() {
        pageNavigation.navigate(FilterRecommendSettingPage())
    }

    fun toFilterCommentPage() {
        pageNavigation.navigate(FilterCommentSettingPage())
    }

    fun toSponsorBlockSettingPage() {
        pageNavigation.navigate(SponsorBlockSettingPage())
    }

    /** ④ 扩展 → 海外加速：进**一级页**（里面有「启用分段并发下载」开关 + 「并发设置」入口） */
    fun toThreadRipperSettingPage() {
        pageNavigation.navigate(RipperSettingPage())
    }

    fun toCdnSettingPage() {
        pageNavigation.navigate(CdnSettingPage())
    }

    fun toAntifraudSettingPage() {
        pageNavigation.navigate(AntifraudSettingPage())
    }

    fun toAiSummarySettingPage() {
        pageNavigation.navigate(AiSummarySettingPage())
    }

    fun toAccountDataSettingPage() {
        pageNavigation.navigate(AccountDataSettingPage())
    }

    fun toExportSettingPage() {
        pageNavigation.navigate(ExportSettingPage())
    }

    fun toAboutSettingPage() {
        pageNavigation.navigate(AboutSettingPage())
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
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val userState = userStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())
    val context = LocalContext.current
    // 远程设置列表已禁用 - 不收集远程数据

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
            // ===== ① 播放 =====
            preferenceCategory(key = "play", title = { Text("播放") })
            preference(
                key = "video",
                title = { Text("播放器设置") },
                summary = { Text("后台/小窗、视频源、字幕、下载等") },
                onClick = viewModel::toVideoSettingPage
            )
            preference(
                key = "danmaku",
                title = { Text("弹幕设置") },
                summary = { Text("弹幕显示、样式与过滤") },
                onClick = viewModel::toDanmakuSettingPage,
            )
            preference(
                key = "auto_stop",
                title = { Text("定时关闭") },
                summary = { Text("播够指定时长自动停止") },
                onClick = viewModel::toAutoStopTimerPage,
            )

            // ===== ② 界面 =====
            preferenceCategory(key = "ui", title = { Text("界面") })
            preference(
                key = "theme",
                title = { Text("主题") },
                summary = { Text("配色与深色模式") },
                onClick = viewModel::toThemePage,
            )
            preference(
                key = "home",
                title = { Text("首页设置") },
                summary = { Text("首页入口、卡片与时光精选") },
                onClick = viewModel::toHomeSettingPage
            )
            preference(
                key = "bottom_bar",
                title = { Text("底栏与导航") },
                summary = { Text("锁定底栏、滚动隐藏行为") },
                onClick = viewModel::toBottomBarSettingPage,
            )
            preference(
                key = "display_scale",
                title = { Text("显示与字号") },
                summary = { Text("应用内 DPI 与字体缩放") },
                onClick = viewModel::toDisplayScaleSettingPage,
            )

            // ===== ③ 内容与评论 =====
            preferenceCategory(key = "content", title = { Text("内容与评论") })
            preference(
                key = "filter",
                title = { Text("屏蔽规则") },
                summary = { Text("按标题 / UP / 标签 / UP名屏蔽") },
                onClick = viewModel::toFilterSettingPage
            )
            preference(
                key = "filter_recommend",
                title = { Text("推荐过滤") },
                summary = { Text("时长、播放量、封面、相关推荐等") },
                onClick = viewModel::toFilterRecommendPage,
            )
            preference(
                key = "filter_comment",
                title = { Text("评论区") },
                summary = { Text("评论关键字、二级回复显示") },
                onClick = viewModel::toFilterCommentPage,
            )

            // ===== ④ 扩展 =====
            preferenceCategory(key = "ext", title = { Text("扩展") })
            preference(
                key = "sponsor_block",
                title = { Text("空降助手") },
                summary = { Text("自动跳过片头片尾 / 赞助片段") },
                onClick = viewModel::toSponsorBlockSettingPage,
            )
            preference(
                key = "thread_ripper",
                title = { Text("海外加速") },
                summary = { Text("分段并发下载，改善卡顿") },
                onClick = viewModel::toThreadRipperSettingPage,
            )
            preference(
                key = "cdn",
                title = { Text("CDN") },
                summary = { Text("竞速、固定主机、音频独立") },
                onClick = viewModel::toCdnSettingPage,
            )
            preference(
                key = "antifraud",
                title = { Text("评论反诈") },
                summary = { Text("发评后自动检测是否被限流") },
                onClick = viewModel::toAntifraudSettingPage,
            )
            preference(
                key = "ai_summary",
                title = { Text("AI 视频总结") },
                summary = { Text("详情页显示视频摘要") },
                onClick = viewModel::toAiSummarySettingPage,
            )

            // ===== ⑤ 账号与数据 =====
            preferenceCategory(key = "account_data", title = { Text("账号与数据") })
            preference(
                key = "account_storage",
                title = { Text("账号与存储") },
                summary = { Text("游客模式、身份导入导出、缓存与重置") },
                onClick = viewModel::toAccountDataSettingPage,
            )
            preference(
                key = "export_setting",
                title = { Text("备份与恢复") },
                summary = { Text("导出 / 导入全部设置") },
                onClick = viewModel::toExportSettingPage,
            )
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

            // ===== ⑥ 关于 =====
            preferenceCategory(key = "about_app", title = { Text("关于") })
            preference(
                key = "about",
                title = { Text("关于本应用") },
                summary = { Text("版本号、仓库、错误日志与诊断") },
                onClick = viewModel::toAboutSettingPage,
            )

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