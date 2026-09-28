package cn.a10miaomiao.bilimiao.compose.pages.dynamic.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bilibili.app.dynamic.v2.UpListItem
import cn.a10miaomiao.bilimiao.compose.common.localPageNavigation
import cn.a10miaomiao.bilimiao.compose.components.user.LiveBadgedAvatar
import cn.a10miaomiao.bilimiao.compose.pages.mine.MyFollowPage
import com.a10miaomiao.bilimiao.comm.entity.user.UserInfo
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun DynamicUpperList(
    modifier: Modifier = Modifier,
    safePadding: PaddingValues,
    upperList: List<UpListItem>,
    selectedUpper: UpListItem? = null,
    userInfo: UserInfo? = null,
    onMyDynamics: () -> Unit = {},
    onSelected: (UpListItem) -> Unit,
    showAllHeader: Boolean = true,
) {
    val pageNavigation = localPageNavigation()
    fun toFollowPage() {
        pageNavigation.navigate(
            MyFollowPage()
        )
    }

    LazyColumn(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = safePadding,
    ) {
        if (showAllHeader) {
            item {
            val isSelected = selectedUpper == null
            Row(
                modifier = Modifier
                    .clickable { onMyDynamics() }
                    .padding(8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else Color.Transparent
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (userInfo?.face != null) {
                        GlideImage(
                            model = userInfo.face,
                            contentDescription = "我的动态",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = CircleShape
                                ),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AllInclusive,
                                contentDescription = "全部",
                                // 底色是 primary，前景必须用 onPrimary（浅色主题下 primary 很浅，白字看不见）
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "我的动态",
                    fontSize = 14.sp,
                    maxLines = 1,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        }

        items(upperList.size, key = { upperList[it].uid }) { index ->
            val item = upperList[index]
            val isSelected = selectedUpper?.uid == item.uid
            Row(
                modifier = Modifier
                    .clickable { onSelected(item) }
                    .padding(8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        // ★这里**故意不用** `.clip(CircleShape)`：它会把这个头像的涟漪一起裁掉
                        //   （LiveBadgedAvatar 的涟漪画在头像之外 5~9dp，见那边的 KDoc）。
                        //   选中态那圈 20% 底色改用 background(color, shape = CircleShape) 画成同一个圆，
                        //   观感与改动前一致，且不裁剪子节点。
                        .background(
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else Color.Transparent,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    LiveBadgedAvatar(
                        face = item.face,
                        size = 40.dp,
                        // uid 有效才去查在播状态（脏数据 uid<=0 传 null = 不请求、不挂标记）
                        mid = item.uid.takeIf { it > 0 }?.toString(),
                        // ★点哪都算"行点击"（未选中 → 筛选这个 UP；已选中再点 → 进他的空间，
                        //   两者都在 DynamicPage 的 onSelected 里判）：头像本体与「直播中」药丸
                        //   都**不**自己进直播间，事件原样落回 Row 的 clickable。
                        liveClickOnAvatar = false,
                        onClick = null,
                        // 选中描边从原来的 GlideImage 挪到这里：只加 border（形状参数自带圆），
                        // **不加 clip**（clip 会裁掉涟漪）；border 不参与点击，所以与
                        // 行点击 / 涟漪 / 药丸各在不同层，互不打架。
                        modifier = Modifier.border(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = CircleShape,
                        ),
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = item.name,
                    fontSize = 14.sp,
                    maxLines = 1,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (upperList.isNotEmpty()) {
            item {
                TextButton(
                    onClick = ::toFollowPage,
                ) {
                    Text(
                        text = "更多关注",
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Default.ArrowForward,
                        contentDescription = "more"
                    )
                }
            }
        }
    }
}