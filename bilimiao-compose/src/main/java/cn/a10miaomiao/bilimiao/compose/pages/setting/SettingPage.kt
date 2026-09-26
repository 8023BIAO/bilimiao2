package cn.a10miaomiao.bilimiao.compose.pages.setting

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
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
import cn.a10miaomiao.bilimiao.compose.pages.setting.widgets.DpiSettingDialog
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import cn.a10miaomiao.bilimiao.compose.components.preference.textIntPreference
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

    /** 直播设置（第四阶段）：和「播放器设置 / 弹幕设置」并列挂在 ① 播放 下 */
    fun toLiveSettingPage() {
        pageNavigation.navigate(LiveSettingPage())
    }

    fun toFilterSettingPage() {
        pageNavigation.navigate(FilterSettingPage())
    }


    fun toAutoStopTimerPage() {
        pageNavigation.navigate(AutoStopTimerPage())
    }

    fun toBottomBarSettingPage() {
        pageNavigation.navigate(BottomBarSettingPage())
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


/** 设置里"能点进去的页面"清单（供搜索用；点一下直接跳） */
private class SettingPageLink(
    val title: String,
    val summary: String,
    val category: String,
    val keywords: String,
    val nav: () -> Unit,
)

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
    // 显示与字号：设置首页那一级直接弹窗（原来要"界面 → 显示与字号 → 再点一下"三步）
    var showDpiDialog by remember { mutableStateOf(false) }
    // 设置搜索：空 = 显示 6 个大分类；非空 = 这一页刷成搜索结果（开关/数值项可直接改）
    var searchQuery by remember { mutableStateOf("") }
    // 搜索状态下的返回键（系统返回 / 底栏「返回」）：先清掉搜索词、留在设置页，
    // 搜索框空了才真的退出设置页 —— 否则输入完搜索一按返回就掉回首页，用户一脸问号。
    // 注：ComposeFragment.onBackPressed() 走的是 Activity 的 onBackPressedDispatcher，
    //     这个 BackHandler 比 NavHost 的兜底 callback 后注册 → 优先级更高，能拦住。
    BackHandler(enabled = searchQuery.isNotEmpty()) {
        searchQuery = ""
    }
    val settingPages = remember(viewModel) {
        listOf(
            SettingPageLink("播放器设置", "后台/小窗、视频源、字幕、下载", "① 播放", "播放 播放器 缓冲 画质 格式 字幕 下载 小窗", viewModel::toVideoSettingPage),
            SettingPageLink("弹幕设置", "弹幕显示、样式与过滤", "① 播放", "弹幕 danmaku 显示 样式 过滤 关键词", viewModel::toDanmakuSettingPage),
            // 直播设置的搜索入口（页面级）：搜"直播 / 小窗 / 画质 / 线路 / 弹幕"都能直达
            SettingPageLink("直播设置", "后台/小窗、默认画质、线路、直播弹幕与列表", "① 播放", "直播 live 直播间 后台 继续 小窗 pip 画中画 画质 清晰度 原画 线路 cdn 重连 弹幕 排序 卡片 列数", viewModel::toLiveSettingPage),
            SettingPageLink("定时关闭", "在「播放器设置 → 播放控制设置」里", "① 播放", "定时 关闭 睡眠 停止", viewModel::toAutoStopTimerPage),
            SettingPageLink("主题", "配色与深色模式", "② 界面", "主题 配色 颜色 深色 夜间 纯黑", viewModel::toThemePage),
            SettingPageLink("首页设置", "首页入口显示", "② 界面", "首页 主页 首页入口 入口显示 卡片 列数 时光姬", viewModel::toHomeSettingPage),
            SettingPageLink("底栏与导航", "锁定底栏、滚动隐藏行为", "② 界面", "底栏 导航 滚动 隐藏 标题行", viewModel::toBottomBarSettingPage),
            SettingPageLink("内容屏蔽", "按标题 / UP / 标签 / UP名屏蔽", "③ 内容与评论", "屏蔽 过滤 标题 up 标签 黑名单", viewModel::toFilterSettingPage),
            SettingPageLink("推荐过滤", "时长、播放量、封面、相关推荐等", "③ 内容与评论", "推荐 过滤 时长 播放量 封面 相关 推广", viewModel::toFilterRecommendPage),
            SettingPageLink("评论区", "评论关键字、二级回复显示", "③ 内容与评论", "评论 评论区 关键字 二级 回复", viewModel::toFilterCommentPage),
            SettingPageLink("空降助手", "自动跳过片头片尾 / 赞助片段", "④ 扩展", "空降 跳过 片头 片尾 赞助 恰饭", viewModel::toSponsorBlockSettingPage),
            SettingPageLink("海外加速", "分段并发下载，改善卡顿", "④ 扩展", "海外 加速 并发 分段 卡顿 线程", viewModel::toThreadRipperSettingPage),
            SettingPageLink("CDN", "竞速、固定主机、音频独立", "④ 扩展", "cdn 节点 线路 主机 竞速", viewModel::toCdnSettingPage),
            SettingPageLink("评论反诈", "发评后自动检测是否被限流", "④ 扩展", "评论 反诈 限流 吞评 复查 申诉", viewModel::toAntifraudSettingPage),
            SettingPageLink("账号与存储", "游客模式、身份导入导出、缓存与重置", "⑤ 账号与数据", "账号 登录 游客 身份 导入 导出 备份 缓存 重置 清空", viewModel::toAccountDataSettingPage),
            SettingPageLink("关于本应用", "版本号、仓库、错误日志", "⑥ 关于", "关于 版本 版本号 vc github 仓库 错误 日志 致谢", viewModel::toAboutSettingPage),
            // 动作型：没有独立页面（点了直接弹窗/执行），但同样要能被搜到
            SettingPageLink("显示与字号", "应用内 DPI 与字体缩放，点了直接改", "② 界面", "dpi 字号 字体 缩放 字体大小 显示 太大 太小", { showDpiDialog = true }),
            SettingPageLink("备份与恢复", "导出 / 导入全部设置（在「账号与存储」里）", "⑤ 账号与数据", "备份 恢复 导出 导入 设置文件 json 迁移", viewModel::toAccountDataSettingPage),
            SettingPageLink("退出登录", "清除登录状态", "⑤ 账号与数据", "退出 登出 logout 切号 账号", { showLogoutDialog.value = true }),
        )
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
            // 搜索框（状态栏之下）：输入即搜、清空即回到 6 大类
            item("search") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    singleLine = true,
                    placeholder = { Text("搜索设置（如：弹幕 / 缓存 / 底栏）") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            TextButton(onClick = { searchQuery = "" }) { Text("清空") }
                        }
                    },
                )
            }
            if (searchQuery.isBlank()) {
            // ===== ① 播放 =====
            preferenceCategory(key = "play", title = { Text("播放") })
            preference(
                key = "video",
                title = { Text("播放器设置") },
                summary = { Text("后台/小窗、视频源、字幕、下载、定时关闭") },
                onClick = viewModel::toVideoSettingPage
            )
            preference(
                key = "danmaku",
                title = { Text("弹幕设置") },
                summary = { Text("弹幕显示、样式与过滤") },
                onClick = viewModel::toDanmakuSettingPage,
            )
            // 直播设置（第四阶段）：直播的后台/小窗/画质/线路/弹幕/列表是一整块独立语义，
            // 单开一页而不是塞进「播放器设置」（点播后台播放是继续出声，直播是画面照常播）
            preference(
                key = "live",
                title = { Text("直播设置") },
                summary = { Text("后台/小窗、默认画质、线路、直播弹幕与列表") },
                onClick = viewModel::toLiveSettingPage,
            )
            // 「定时关闭」搬进「播放器设置 → 播放控制设置」了（用户：在播放器点齿轮进来要能找到它），
            // 这里不再单独挂一行；搜索索引里保留入口，搜"定时"仍能直达。

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
                summary = { Text("首页入口显示") },
                onClick = viewModel::toHomeSettingPage
            )
            preference(
                key = "bottom_bar",
                title = { Text("底栏与导航") },
                summary = { Text("锁定底栏、滚动隐藏行为") },
                onClick = viewModel::toBottomBarSettingPage,
            )
            // 显示与字号：单项设置，按"能内联就内联"的规则直接放在这一级（点了就弹窗）
            preference(
                key = "display_scale",
                title = { Text("显示与字号") },
                summary = { Text("应用内 DPI 与字体缩放，点这里直接改") },
                onClick = { showDpiDialog = true },
            )

            // ===== ③ 内容与评论 =====
            preferenceCategory(key = "content", title = { Text("内容与评论") })
            preference(
                key = "filter",
                title = { Text("内容屏蔽") },
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
            // 单项设置直接内联（按"能内联就内联"）：省掉一次跳转
            switchPreference(
                key = SettingPreferences.AiSummaryEnabled.name,
                defaultValue = false,
                title = { Text("AI 视频总结") },
                summary = { Text("视频详情页「简介」上方显示 B站官方接口生成的摘要") },
            )
            switchPreference(
                key = SettingPreferences.WbiSignEnabled.name,
                defaultValue = true,
                title = { Text("WBI 签名") },
                summary = { Text("给 B站 Web API 自动签名（遇到 -352 报错时可试着关掉）") },
            )

            // ===== ⑤ 账号与数据 =====
            preferenceCategory(key = "account_data", title = { Text("账号与数据") })
            preference(
                key = "account_storage",
                title = { Text("账号与存储") },
                summary = { Text("游客模式、身份导入导出、缓存与重置") },
                onClick = viewModel::toAccountDataSettingPage,
            )

            // ===== ⑥ 关于 =====
            preferenceCategory(key = "about_app", title = { Text("关于") })
            preference(
                key = "about",
                title = { Text("关于本应用") },
                summary = { Text("版本号、仓库、错误日志与诊断") },
                onClick = viewModel::toAboutSettingPage,
            )

                // 退出登录：破坏性操作单独吊在 6 大类最底下（不塞进任何分类里）
                if (userState.isLogin()) {
                    item("logout_gap") {
                        Spacer(modifier = Modifier.height(18.dp))
                    }
                    preference(
                        key = "logout",
                        title = {
                            Text(
                                text = "退出登录",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                // 用主题的 error 色而不是写死 #FF0000：深色主题下纯红扎眼、也不跟主题色走
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showLogoutDialog.value = true
                        }
                    )
                }
            } else {
                // ===== 搜索结果 =====
                // 页面入口（有跳转的）
                val q = searchQuery.trim()
                val pageHits = settingPages.filter { p ->
                    p.title.lowercase().contains(q.lowercase()) ||
                        p.category.lowercase().contains(q.lowercase()) ||
                        p.keywords.lowercase().contains(q.lowercase())
                }
                // 开关 / 数值（可直接在这里改）
                val itemHits = SettingsSearchIndex.search(q)
                if (pageHits.isEmpty() && itemHits.isEmpty()) {
                    item("no_result") {
                        Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            Text("没找到「$q」相关的设置", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "换个词试试，例如：弹幕、缓存、底栏、倍速、屏蔽、空降",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (pageHits.isNotEmpty()) {
                    preferenceCategory(key = "search_pages", title = { Text("设置页面") })
                    pageHits.forEach { p ->
                        preference(
                            key = "search_page_${p.title}",
                            title = { Text(p.title) },
                            summary = { Text("${p.category} · ${p.summary}") },
                            onClick = p.nav,
                        )
                    }
                }
                if (itemHits.isNotEmpty()) {
                    preferenceCategory(key = "search_items", title = { Text("设置项（可直接修改）") })
                    itemHits.forEach { item ->
                        when (item.kind) {
                            SettingSearchItem.Kind.SWITCH -> switchPreference(
                                key = item.prefKey,
                                defaultValue = item.default as Boolean,
                                title = { Text(item.title) },
                                summary = { Text(item.category) },
                            )
                            SettingSearchItem.Kind.INT -> textIntPreference(
                                key = item.prefKey,
                                defaultValue = item.default as Int,
                                title = { Text(item.title) },
                                summary = { Text(item.category) },
                                label = "",
                            )
                        }
                    }
                }
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

    if (showDpiDialog) {
        DpiSettingDialog(onDismiss = { showDpiDialog = false })
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