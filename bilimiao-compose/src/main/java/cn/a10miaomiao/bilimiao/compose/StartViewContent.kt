package cn.a10miaomiao.bilimiao.compose

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser as ComposePathParser
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.addPaddingValues
import cn.a10miaomiao.bilimiao.compose.common.foundation.ScaleIndication
import cn.a10miaomiao.bilimiao.compose.components.miao.MiaoCard
import cn.a10miaomiao.bilimiao.compose.components.start.StartLibraryCard
import cn.a10miaomiao.bilimiao.compose.components.start.StartPlayerCard
import cn.a10miaomiao.bilimiao.compose.components.start.StartSearchCard
import cn.a10miaomiao.bilimiao.compose.components.start.StartUserCard
import cn.a10miaomiao.bilimiao.compose.pages.auth.LoginPage
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.entity.auth.LoginInfo
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.BangumiDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.SeasonCheckPage
import cn.a10miaomiao.bilimiao.compose.pages.download.DownloadListPage
import cn.a10miaomiao.bilimiao.compose.pages.message.MessagePage
import cn.a10miaomiao.bilimiao.compose.pages.mine.HistoryPage
import cn.a10miaomiao.bilimiao.compose.pages.mine.MyBangumiPage
import cn.a10miaomiao.bilimiao.compose.pages.mine.MyFollowPage
import cn.a10miaomiao.bilimiao.compose.pages.mine.WatchLaterPage
import cn.a10miaomiao.bilimiao.compose.pages.playlist.PlayListPage
import cn.a10miaomiao.bilimiao.compose.pages.setting.SettingPage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserBangumiPage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserFavouritePage
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import cn.a10miaomiao.bilimiao.compose.pages.video.VideoDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.user.MyFollowerPage
import com.a10miaomiao.bilimiao.comm.delegate.player.BasePlayerDelegate
import com.a10miaomiao.bilimiao.comm.entity.user.UserInfo
import com.a10miaomiao.bilimiao.comm.store.PlayerStore
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.launch
import org.kodein.di.compose.rememberInstance
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import android.app.Activity
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.with
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.GenericShape
import androidx.core.view.WindowCompat
import cn.a10miaomiao.bilimiao.compose.base.PageSearchMethod
import cn.a10miaomiao.bilimiao.compose.common.foundation.add
import cn.a10miaomiao.bilimiao.compose.components.miao.MiaoOutlinedCard
import cn.a10miaomiao.bilimiao.compose.components.start.SearchInputInline
import cn.a10miaomiao.bilimiao.compose.pages.search.SearchInputViewModel
import cn.a10miaomiao.bilimiao.compose.pages.search.SearchInputViewModel.SuggestInfo
import cn.a10miaomiao.bilimiao.compose.pages.search.SearchInputViewModel.SuggestType
import cn.a10miaomiao.bilimiao.compose.pages.search.SearchResultPage

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun StartViewContent(
    modifier: Modifier = Modifier,
    startTopHeight: Dp = 200.dp,
    navigateTo: (ComposePage) -> Unit,
    navigateUrl: (String) -> Unit,
    openSearch: () -> Unit,
    isSearchVisible: Boolean = false,
    searchInitKeyword: String = "",
    searchInitMode: Int = 0,
    pageSearchMethod: PageSearchMethod? = null,
    searchAnimation: Boolean = true,
    onCloseSearch: () -> Unit = {},
) {
    val listState = rememberLazyListState()

    SharedTransitionLayout(
        modifier = modifier
    ) {
        AnimatedContent(
            isSearchVisible,
            transitionSpec = {
                // 从下往上进入，从上往下退出，并带有透明过渡
                if (searchAnimation) {
                    (
                        slideInHorizontally(
                            initialOffsetX = { fullWidth -> -fullWidth },
                            animationSpec = tween(durationMillis = 200)
                        ) + fadeIn(animationSpec = tween(durationMillis = 200))
                        ) togetherWith (
                        slideOutHorizontally(
                            targetOffsetX = { fullWidth -> -fullWidth },
                            animationSpec = tween(durationMillis = 250)
                        ) + fadeOut(animationSpec = tween(durationMillis = 250))
                    )
                } else {
                    // 无动画
                    fadeIn(animationSpec = tween(0)) togetherWith
                            fadeOut(animationSpec = tween(0))
                }
            },
            label = "basic_transition"
        ) { targetState ->
            if (targetState) {
                SearchInputInline(
                    modifier = Modifier,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                    initKeyword = searchInitKeyword,
                    initMode = searchInitMode,
                    pageSearchMethod = pageSearchMethod,
                    onDismissRequest = onCloseSearch,
                )
            } else {
                StartIndexList(
                    modifier = modifier,
                    listState = listState,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                    startTopHeight = startTopHeight,
                    openSearch = openSearch,
                    navigateTo = navigateTo,
                    navigateUrl = navigateUrl,
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun StartIndexList(
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    startTopHeight: Dp = 200.dp,
    openSearch: () -> Unit,
    navigateTo: (ComposePage) -> Unit,
    navigateUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val userStore by rememberInstance<UserStore>()
    val userState by userStore.stateFlow.collectAsState()
    val playerStore by rememberInstance<PlayerStore>()
    val playerState by playerStore.stateFlow.collectAsState()
    val playerDelegate by rememberInstance<BasePlayerDelegate>()

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = WindowInsets
            .safeDrawing
            .asPaddingValues()
            .add(PaddingValues(10.dp)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .padding(top = startTopHeight)
                    .fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .size(80.dp, 8.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            color = Color.White.copy(alpha = 0.8f)
                        ),
                )
            }
        }
        item {
            AnimatedVisibility(
                visible = playerState.title.isNotEmpty(),
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .fillMaxWidth()
            ) {
                StartPlayerCard(
                    aid = playerState.aid,
                    title = playerState.title,
                    cover = playerState.cover,
                    onClick = {
                        if (playerState.type === "video") {
                            navigateTo(
                                VideoDetailPage(
                                    id = playerState.aid,
                                )
                            )
                        } else if (playerState.type === "bangumi") {
                            navigateTo(
                                SeasonCheckPage(
                                    id = playerState.sid,
                                    epId = playerState.epid,
                                )
                            )
                        }
                    },
                    onPlayListClick = {
                        navigateTo(PlayListPage())
                    },
                    onCloseClick = playerDelegate::closePlayer,
                )
            }
        }

        item {
            StartUserCard(
                userInfo = userState.info,
                onUserClick = {
                    val userInfo = userState.info
                    if (userInfo != null) {
                        navigateTo(UserSpacePage(
                            id = userInfo.mid.toString(),
                        ))
                    } else {
                        // 游客模式下有备份信息则恢复登录，而不是跳转登录页
                        val prefs = context.getSharedPreferences("bilimiao_guest_backup", Context.MODE_PRIVATE)
                        val backupJson = prefs.getString("login_info_backup", null)
                        if (backupJson != null) {
                            try {
                                val loginInfo = MiaoJson.fromJson<LoginInfo>(backupJson)
                                BilimiaoCommApp.commApp.saveAuthInfo(loginInfo)
                                prefs.edit().remove("login_info_backup").commit()
                                userStore.loadInfo()
                                Toast.makeText(context, "登录信息已恢复", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                navigateTo(LoginPage())
                            }
                        } else {
                            navigateTo(LoginPage())
                        }
                    }
                },
                onUserDynamicClick = {
                    val userInfo = userState.info
                    if (userInfo != null) {
                        navigateTo(UserSpacePage(
                            id = userInfo.mid.toString(),
                        ))
                    }
                },
                onUserFollowerClick = {
                    val userInfo = userState.info
                    if (userInfo != null) {
                        navigateTo(MyFollowerPage(vmid = userInfo.mid.toString()))
                    }
                },
                onUserFollowingClick = {
                    if (userState.info != null) {
                        navigateTo(MyFollowPage())
                    } else {
                        Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
                    }
                },
                onMessageClick = {
                    if (userState.info != null) {
                        navigateTo(MessagePage())
                    } else {
                        Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
        item {
            StartSearchCard(
                modifier = Modifier
                    .run {
                        with(sharedTransitionScope) {
                            sharedElement(
                                rememberSharedContentState(key = "search"),
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                        }
                    },
                onClick = {
                    openSearch()
                },
            )
        }
        item {
            StartLibraryCard(
                userId = userState.info?.mid,
                navigateTo = navigateTo,
            )
        }
        item {
            StartFooterCard(
                onDownloadClick = {
                    navigateTo(DownloadListPage())
                },
                onSettingClick = {
                    navigateTo(SettingPage())
                },
            )
        }
    }
}

@Composable
private fun StartFooterCard(
    onDownloadClick: () -> Unit,
    onSettingClick: () -> Unit
) {
    MiaoOutlinedCard {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onDownloadClick)
                    .padding(vertical = 10.dp),
                text = "视频下载",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            VerticalDivider(
                modifier = Modifier
                    .height(20.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            )
            Text(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onSettingClick)
                    .padding(vertical = 10.dp),
                text = "设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}
