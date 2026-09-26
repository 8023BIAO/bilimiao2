package cn.a10miaomiao.bilimiao.compose

import ReplyDetailListPage
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.serialization.decodeArguments
import cn.a10miaomiao.bilimiao.compose.animation.materialFadeThroughIn
import cn.a10miaomiao.bilimiao.compose.animation.materialFadeThroughOut
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.pages.BlankPage
import cn.a10miaomiao.bilimiao.compose.pages.TestPage
import cn.a10miaomiao.bilimiao.compose.pages.article.ArticleReaderPage
import cn.a10miaomiao.bilimiao.compose.pages.auth.H5LoginPage
import cn.a10miaomiao.bilimiao.compose.pages.auth.LoginPage
import cn.a10miaomiao.bilimiao.compose.pages.auth.QrCodeLoginPage
import cn.a10miaomiao.bilimiao.compose.pages.auth.SMSLoginPage
import cn.a10miaomiao.bilimiao.compose.pages.auth.TelVerifyPage
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.BangumiDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.BangumiEpisodesPage
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.SeasonCheckPage
import cn.a10miaomiao.bilimiao.compose.pages.community.MainReplyListPage
import cn.a10miaomiao.bilimiao.compose.pages.download.DownloadBangumiCreatePage
import cn.a10miaomiao.bilimiao.compose.pages.download.DownloadDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.download.DownloadListPage
import cn.a10miaomiao.bilimiao.compose.pages.dynamic.DynamicDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.dynamic.DynamicPage
import cn.a10miaomiao.bilimiao.compose.pages.filter.FilterSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.filter.FilterRecommendSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.filter.FilterCommentSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.home.HomePage
import cn.a10miaomiao.bilimiao.compose.pages.live.LiveSearchPage
import cn.a10miaomiao.bilimiao.compose.pages.message.MessagePage
import cn.a10miaomiao.bilimiao.compose.pages.message.ChatPage
import cn.a10miaomiao.bilimiao.compose.pages.mine.HistoryPage
import cn.a10miaomiao.bilimiao.compose.pages.mine.MyBangumiPage
import cn.a10miaomiao.bilimiao.compose.pages.mine.WatchLaterPage
import cn.a10miaomiao.bilimiao.compose.pages.player.SendDanmakuPage
import cn.a10miaomiao.bilimiao.compose.pages.playlist.PlayListPage
import cn.a10miaomiao.bilimiao.compose.pages.rank.RankPage
import cn.a10miaomiao.bilimiao.compose.pages.search.SearchResultPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.DanmakuDisplaySettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.DanmakuSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.CdnSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.AntifraudSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.AccountDataSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.AboutSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.BottomBarSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.ErrorLogPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.HomeSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.LiveSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.TimeSelectSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.RegionSelectPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.SettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.AutoStopTimerPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.ExportSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.VideoSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.SponsorBlockSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.RipperSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.time.TimeRegionDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.time.TimeSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.mine.MyFollowPage
import cn.a10miaomiao.bilimiao.compose.pages.user.EditProfilePage
import cn.a10miaomiao.bilimiao.compose.pages.user.MyFollowerPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.ThemeSettingPage
import cn.a10miaomiao.bilimiao.compose.pages.user.SearchFollowPage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserBangumiPage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserFavouriteDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserFavouritePage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserFollowPage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserLikeArchivePage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserMedialistPage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSeasonDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpaceSearchPage
import cn.a10miaomiao.bilimiao.compose.pages.video.VideoDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.video.VideoPagesPage
import cn.a10miaomiao.bilimiao.compose.pages.web.WebPage
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import kotlinx.serialization.serializer
import kotlin.reflect.KType

class BilimiaoPageRoute (
    val builder: NavGraphBuilder
) {

    fun initRoute() {
        composable<BlankPage>()
        composable<TestPage>()

        // home
        composable<HomePage>()
//        builder.composable<HomePage> {
//            val page = it.toRoute<HomePage>()
//            page.Content()
//        }
        
        // search
        composable<SearchResultPage>(
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "bilibili://search/?keyword={keyword}"
                }
            )
        )

        // live（第三阶段 B 路）：直播搜索页。
        // ★ComposePage 必须在这里注册：导航是按 KClass 反查路由表的，漏了就是"点进去直接崩"
        //   （代码检查.sh 规则 C 会拦下这种漏注册）。带不带关键字都能进（keyword 有默认值）。
        composable<LiveSearchPage>()

        // auth
        composable<LoginPage>()
        composable<QrCodeLoginPage>()
        composable<TelVerifyPage>()
        composable<H5LoginPage>()
        composable<SMSLoginPage>()

        // video
        composable<VideoDetailPage>(
            deepLinks = listOf(
                navDeepLink<VideoDetailPage>(
                    basePath = "bilimiao://video"
                ),
                navDeepLink<VideoDetailPage>(
                    basePath = "bilibili://video"
                )
            )
        )
        composable<VideoPagesPage>()

        composable<SeasonCheckPage>(
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "bilimiao://bangumi/{id}"
                },
                navDeepLink {
                    uriPattern = "https://www.bilibili.com/bangumi/play/ss{id}/"
                }
            )
        )
        composable<BangumiDetailPage>()
        composable<BangumiEpisodesPage>()

        // dynamic
        composable<DynamicPage>()
        composable<DynamicDetailPage>(
            deepLinks = listOf(
                navDeepLink<DynamicDetailPage>(
                    basePath = "bilibili://following/detail"
                ),
                // opus 分享/卡片深链也进动态详情
                // （vc83 把这条删掉、改道专栏页，才有了"点动态进专栏"）
                navDeepLink<DynamicDetailPage>(
                    basePath = "bilibili://opus/detail"
                )
            )
        )
        // 专栏(cv) 深链仍进专栏阅读页；应用内动态卡片走
        // pages/dynamic/DynamicItemNavigation.kt 的 navigateToDynamic()，同样进动态详情

        // rank
        composable<RankPage>(
            deepLinks = listOf(
                navDeepLink<RankPage>(
                    basePath = "bilibili://rank"
                )
            )
        )

        // download
        composable<DownloadListPage>(
            deepLinks = listOf(
                navDeepLink<DownloadListPage>(
                    basePath = "bilimiao://download"
                )
            )
        )
        composable<DownloadDetailPage>()
        composable<DownloadBangumiCreatePage>()

        // filter
        composable<FilterSettingPage>()

        // message
        composable<MessagePage>()
        composable<ChatPage>()

        // player
        composable<SendDanmakuPage>()

        // playlist
        composable<PlayListPage>()

        // setting
        composable<SettingPage>(
            deepLinks = listOf(
                navDeepLink<SettingPage>(
                    basePath = "bilimiao://setting"
                )
            )
        )
        composable<HomeSettingPage>()
        // 直播设置（第四阶段）：后台/小窗、默认画质、线路、弹幕、直播列表。
        // 与页面文件同一次改动注册（规则 C：ComposePage 漏注册 = 点进去直接崩）
        composable<LiveSettingPage>()
        // ====== 时光精选已禁用 ======
        // composable<TimeSelectSettingPage>()
        // composable<RegionSelectPage>()
        composable<ThemeSettingPage>()
        composable<VideoSettingPage>()
        composable<SponsorBlockSettingPage>()
        // ThreadRipperSettingPage 已删除：并发连接数滑块内联进海外加速一级页
        composable<AutoStopTimerPage>()
        composable<DanmakuSettingPage>()
        composable<DanmakuDisplaySettingPage>()
        // vc152 设置重新分类后新增的页面 —— 必须在这里注册，否则点进去会崩
        // （IllegalArgumentException: Destination with route XxxSettingPage cannot be found in navigation graph）
        composable<RipperSettingPage>()
        composable<CdnSettingPage>()
        composable<AntifraudSettingPage>()
        composable<AccountDataSettingPage>()
        composable<AboutSettingPage>()
        composable<BottomBarSettingPage>()
        composable<FilterRecommendSettingPage>()
        composable<FilterCommentSettingPage>()
        composable<ErrorLogPage>()
        composable<ExportSettingPage>()

        // time
        composable<TimeSettingPage>()
        composable<TimeRegionDetailPage>()

        // mine
        composable<MyBangumiPage>(
            deepLinks = listOf(
                navDeepLink<MyBangumiPage>(
                    basePath = "bilimiao://mine/bangumi"
                )
            )
        )
        composable<MyFollowPage>(
            deepLinks = listOf(
                navDeepLink<MyFollowPage>(
                    basePath = "bilimiao://mine/follow"
                )
            )
        )
        composable<MyFollowerPage>(
            deepLinks = listOf(
                navDeepLink<MyFollowerPage>(
                    basePath = "bilimiao://mine/follower"
                )
            )
        )
        composable<HistoryPage>(
            deepLinks = listOf(
                navDeepLink<HistoryPage>(
                    basePath = "bilimiao://mine/history"
                )
            )
        )
        composable<WatchLaterPage>(
            deepLinks = listOf(
                navDeepLink<WatchLaterPage>(
                    basePath = "bilimiao://mine/watchlater"
                )
            )
        )

        // user
        composable<UserSpacePage>(
            deepLinks = listOf(
                navDeepLink<UserSpacePage>(
                    basePath = "bilimiao://user"
                ),
                navDeepLink<UserSpacePage>(
                    basePath = "bilibili://author"
                ),
                navDeepLink<UserSpacePage>(
                    basePath = "bilibili://space"
                )
            )
        )
        // 编辑资料（自己的空间 → 「更多」→「编辑资料」）
        composable<EditProfilePage>()
        composable<UserSpaceSearchPage>()
        composable<UserFollowPage>()
        composable<SearchFollowPage>()
        composable<UserBangumiPage>()
        composable<SearchFollowPage>()
        composable<UserLikeArchivePage>()
        composable<UserFavouritePage>(
            deepLinks = listOf(
                navDeepLink<UserFavouritePage>(
                    basePath = "bilimiao://user/favourite"
                )
            )
        )
        composable<UserFavouriteDetailPage>()
        composable<UserSeasonDetailPage>()
        composable<UserMedialistPage>()

        //community
        composable<MainReplyListPage>(

        )
        composable<ReplyDetailListPage>(
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "bilimiao://comment/{id}?enterUrl={enterUrl}"
                }
            )
        )


        // article
        composable<ArticleReaderPage>(
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "bilimiao://article/{id}"
                },
                navDeepLink {
                    uriPattern = "https://www.bilibili.com/read/cv{id}"
                }
            )
        )

        // web
        composable<WebPage>(
            deepLinks = listOf(
                navDeepLink<WebPage>(
                    basePath = "bilimiao://web"
                ),
                navDeepLink {
                    uriPattern = "bilibili://forward?-Btarget={url}"
                }
            )
        )
    }

    fun defaultEnterTransition(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>
    ): @JvmSuppressWildcards EnterTransition? {
        return materialFadeThroughIn(initialScale = 0.85f)
    }

    fun defaultExitTransition(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>
    ): @JvmSuppressWildcards ExitTransition? {
        return materialFadeThroughOut()
    }

    fun defaultPopEnterTransition(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>
    ): @JvmSuppressWildcards EnterTransition? {
        return materialFadeThroughIn(initialScale = 1.15f)
    }

    fun defaultPopExitTransition(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>
    ): @JvmSuppressWildcards ExitTransition? {
        return materialFadeThroughOut()
    }

    @SuppressLint("RestrictedApi")
    inline fun <reified T: ComposePage> composable(
        typeMap: Map<KType, @JvmSuppressWildcards NavType<*>> = emptyMap(),
        deepLinks: List<NavDeepLink> = emptyList(),
        noinline enterTransition:
        (AnimatedContentTransitionScope<NavBackStackEntry>.() -> @JvmSuppressWildcards
            EnterTransition?)? = ::defaultEnterTransition,
        noinline exitTransition:
        (AnimatedContentTransitionScope<NavBackStackEntry>.() -> @JvmSuppressWildcards
            ExitTransition?)? = ::defaultExitTransition,
        noinline popEnterTransition:
        (AnimatedContentTransitionScope<NavBackStackEntry>.() -> @JvmSuppressWildcards
            EnterTransition?)? = ::defaultPopEnterTransition,
        noinline popExitTransition:
        (AnimatedContentTransitionScope<NavBackStackEntry>.() -> @JvmSuppressWildcards
            ExitTransition?)? = ::defaultPopExitTransition,
        noinline sizeTransform:
        (AnimatedContentTransitionScope<NavBackStackEntry>.() -> @JvmSuppressWildcards
            SizeTransform?)? = null,
    ) {
        val serializer = serializer<T>()
        builder.composable<T>(
            typeMap = typeMap,
            deepLinks = deepLinks,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition,
            sizeTransform = sizeTransform,
        ) { backStackEntry ->
            val bundle = backStackEntry.arguments ?: Bundle()
            val typeMap = backStackEntry.destination.arguments.mapValues { it.value.type }
            val page = serializer.decodeArguments(bundle, typeMap)
            page.Content()
        }
    }
}