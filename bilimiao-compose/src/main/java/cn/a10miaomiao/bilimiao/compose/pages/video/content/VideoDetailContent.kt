package cn.a10miaomiao.bilimiao.compose.pages.video.content

import androidx.compose.ui.Alignment
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.map
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.substring
import androidx.compose.ui.unit.dp
import bilibili.app.archive.v1.Arc
import bilibili.app.view.v1.ViewReply
import cn.a10miaomiao.bilimiao.compose.assets.BilimiaoIcons
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.Common
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.common.Danmukunum
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.common.Playnum
import cn.a10miaomiao.bilimiao.compose.common.foundation.annotatedText
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.mypage.rememberMyMenu
import cn.a10miaomiao.bilimiao.compose.components.video.VideoItemBox
import cn.a10miaomiao.bilimiao.compose.pages.video.VideoDetailViewModel
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoCoverBox
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoInfoBox
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoPagesBox
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoPlayListBox
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoStatBox
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoUgcSeasonBox
import cn.a10miaomiao.bilimiao.compose.pages.video.components.VideoUpperBox
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.mypage.myMenu
import com.a10miaomiao.bilimiao.comm.store.PlayListStore
import com.a10miaomiao.bilimiao.comm.store.PlayerStore
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import org.kodein.di.compose.rememberInstance

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoDetailContent(
    viewModel: VideoDetailViewModel,
    innerPadding: PaddingValues,
    showCover: Boolean,
    detailData: ViewReply,
    arcData: Arc,
    isActive: Boolean,
) {
    val playerStore by rememberInstance<PlayerStore>()
    val playListStore by rememberInstance<PlayListStore>()
    val playListState by playListStore.stateFlow.collectAsState()
    val listPosition by playerStore.listPositionFlow.collectAsState()
    var isExpandPlayList by remember {
        mutableStateOf(false)
    }
    val context = LocalContext.current
    val dataStore = remember { SettingPreferences.run { context.dataStore } }
    val minDuration by remember {
        dataStore.data.map { it[SettingPreferences.VideoMinDuration] ?: 0 }
    }.collectAsState(0)
    val minPlayCount by remember {
        dataStore.data.map { it[SettingPreferences.VideoMinPlayCount] ?: 0 }
    }.collectAsState(0)
    val hideRelates by remember {
        dataStore.data.map { it[SettingPreferences.VideoHideRelates] ?: false }
    }.collectAsState(false)

    val relatesFiltered = remember(detailData.relates, minDuration, minPlayCount, hideRelates) {
        var relates = detailData.relates
        if (minDuration > 0) {
            relates = relates.filter { it.duration >= minDuration }
        }
        if (minPlayCount > 0) {
            relates = relates.filter { (it.stat?.view ?: 0) >= minPlayCount }
        }
        if (hideRelates) {
            relates = emptyList()
        }
        relates
    }

    val videoStat = arcData.stat
    val videoPages = remember(detailData) {
        viewModel.run { detailData.getPages() }
    }
    val aiConclusionData by viewModel.aiConclusionData.collectAsState()
    val videoHistory = detailData.history
    val videoReqUser = detailData.activitySeason?.reqUser
        ?: detailData.reqUser ?: bilibili.app.view.v1.ReqUser()
    if (isActive) {
        val grayIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f).toArgb()
        val pageConfig = PageConfig(
            title = viewModel.getBvid(),
            menu = rememberMyMenu(listPosition, videoReqUser.favorite, videoStat?.share) {
                myItem {
                    key = MenuKeys.more
                    iconFileName = "ic_more_vert_grey_24dp"
                    title = "更多"
                    childMenu = myMenu {
//                        myItem {
//                            key = MenuKeys.download
//                            title = "下载"
//                        }
                        myItem {
                            key = 2
                            title = "浏览器打开"
                        }
                        myItem {
                            key = 3
                            title = "复制链接"
                        }
                        myItem {
                            key = 5
                            title = "复制BV号"
                        }
                        myItem {
                            key = 6
                            title = "保存封面"
                        }


                    }
                }
                myItem {
                    key = MenuKeys.download
                    iconFileName = "ic_download_grey_24dp"
                    title = "下载"
                }
                myItem {
                    key = MenuKeys.add
                    iconFileName = "ic_add_white_24dp"
                    title = "添加至"
                    childMenu = myMenu {
                        if (listPosition != -1) {
                            myItem {
                                key = 11
                                title = "添加至下一个播放"
                            }
                        }
                        myItem {
                            key = 12
                            title = "添加至最后一个播放"
                        }
                        myItem {
                            key = 13
                            title = "添加至稍后再看"
                        }
                    }
                }
            }
        )
        PageListener(
            configId = pageConfig,
            onMenuItemClick = viewModel::menuItemClick
        )
    }

    LazyVerticalGrid(
        modifier = Modifier.fillMaxSize(),
        contentPadding = innerPadding,
        columns = GridCells.Adaptive(300.dp),
    ) {
        item(
            span = { GridItemSpan(maxLineSpan) }
        ) {
            Column {
                AnimatedVisibility(
                    visible = showCover,
                ) {
                    VideoCoverBox(
                        modifier = Modifier
                            .aspectRatio(16f / 9f)
                            .padding(10.dp),
                        aid = arcData.aid,
                        title = arcData.title,
                        pic = arcData.pic,
                        duration = arcData.duration,
                        progress = videoHistory?.progress ?: 0L,
                        progressTitle = videoHistory?.cid?.let { cid ->
                            videoPages.find { it.cid == cid }?.part
                        } ?: "",
                        onClick = viewModel::playVideo,
                        onLongClick = viewModel::openCoverActivity
                    )
                }
                VideoUpperBox(
                    author = arcData.author,
                    ownerExt =  detailData.activitySeason?.ownerExt ?: detailData.ownerExt,
                    staffList =  detailData.activitySeason?.staff ?: detailData.staff,
                    onUserClick = viewModel::toUserPage
                )
                VideoInfoBox(
                    viewModel = viewModel,
                    arc = arcData,
                    stat = videoStat,
                    pages = videoPages,
                    aiConclusionResult = aiConclusionData
                )
                Spacer(
                    modifier = Modifier.height(5.dp)
                )
                val tags = detailData.tag
                // ★ 标签间距：代码里明明写的是"行列都 5dp"，实测出来却是**列 6dp、行 21dp**（差 3.5 倍），
                //   看着"横着挤、竖着散"就是这么来的 ——
                //   Material3 的 chip 强制 48dp 最小触控区：可见框只有 31dp 高，却在布局里占 48dp，
                //   多出来的 16.6dp 空白把每一行顶开了（56dp 宽的标签超过 48dp，所以列距没被撑）。
                //   这里把最小触控区置 0，行距列距才能真的相等。
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tags.forEach { tag ->
                            // 标签样式对齐**官方国际版**（用户给的截图量的）：
                            //   无边框、底色只比背景亮一点点、文字用灰色而不是纯白 ——
                            //   原来是"1.14dp 描边 + 纯白字 + 31dp 高"，一排框框太抢眼（用户说"突出、沉重"）。
                            // 这里不用 AssistChip：当前 Material3 的 chip 去掉了 contentPadding 参数、
                            // 内边距固定 16dp 改不动，所以用 Surface 自己搭，尺寸完全可控（涟漪照旧有）。
                            Surface(
                                onClick = { viewModel.toSearchPage(tag.name) },
                                modifier = Modifier.height(30.dp),
                                shape = RoundedCornerShape(9.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = tag.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(
                    modifier = Modifier.height(5.dp)
                )
                if (videoStat != null) {
                    VideoStatBox(
                        modifier = Modifier
                            .padding(horizontal = 10.dp),
                        viewModel = viewModel,
                        arc = arcData,
                        stat = videoStat,
                        reqUser = videoReqUser,
                    )
                }
                Spacer(
                    modifier = Modifier.height(10.dp)
                )
                val ugcSeason = detailData.ugcSeason
                    ?: detailData.activitySeason?.ugcSeason
                if (!playListState.isEmpty()) {
                    VideoPlayListBox(
                        modifier = Modifier
                            .padding(horizontal = 10.dp),
                        viewModel = viewModel,
                        arc = arcData,
                        ugcSeason = ugcSeason,
                        playListState = playListState,
                        isExpand = isExpandPlayList,
                        onChangeExpand = {
                            isExpandPlayList = it
                        }
                    )
                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )
                }
                if (ugcSeason != null) {
                    VideoUgcSeasonBox(
                        modifier = Modifier
                            .padding(horizontal = 10.dp),
                        viewModel = viewModel,
                        arc = arcData,
                        ugcSeason = ugcSeason,
                        isExpand = !isExpandPlayList,
                        onChangeExpand = {
                            isExpandPlayList = !it
                        }
                    )
                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )
                }
            }
        }
        items(relatesFiltered, key = { it.aid.toString() + "_" + it.title }) {
            VideoItemBox(
                modifier = Modifier.padding(
                    horizontal = 10.dp,
                    vertical = 5.dp
                ),
                title = it.title,
                pic = it.pic,
                upperName = it.author?.name,
                playNum = it.stat?.view?.let(NumberUtil::converString),
                damukuNum = it.stat?.danmaku?.let(NumberUtil::converString),
                duration = NumberUtil.converDuration(it.duration),
                onClick = {
                    viewModel.toVideoPage(it.aid.toString())
                }
            )
        }
    }
}