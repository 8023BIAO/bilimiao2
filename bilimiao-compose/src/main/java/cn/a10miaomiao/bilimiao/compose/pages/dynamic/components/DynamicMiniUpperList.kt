package cn.a10miaomiao.bilimiao.compose.pages.dynamic.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import cn.a10miaomiao.bilimiao.compose.pages.mine.MyFollowPage
import com.a10miaomiao.bilimiao.comm.entity.user.UserInfo
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage


@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun DynamicMiniUpperList(
    modifier: Modifier = Modifier,
    upperList: List<UpListItem>,
    selectedUpper: UpListItem? = null,
    userInfo: UserInfo? = null,
    onMyDynamics: () -> Unit = {},
    onSelected: (UpListItem) -> Unit
) {
    val pageNavigation = localPageNavigation()
    fun toFollowPage() {
        pageNavigation.navigate(
            MyFollowPage()
        )
    }

    Column(
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            TextButton(
                onClick = onMyDynamics,
            ) {
                Column(
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (userInfo?.face != null) {
                        GlideImage(
                            model = userInfo.face,
                            contentDescription = "我的动态",
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "我",
                                fontSize = 10.sp,
                                color = Color.White,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.size(2.dp))
                    Text(
                        text = "动态",
                        fontSize = 10.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        LazyColumn(
            modifier = modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(upperList.size, { upperList[it].uid }) { index ->
                val item = upperList[index]
                val isSelected = selectedUpper?.uid == item.uid
                Column(
                    modifier = Modifier
                        .clickable { onSelected(item) }
                        .padding(vertical = 6.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                else Color.Transparent
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        GlideImage(
                            model = item.face,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (isSelected) 3.dp else 0.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = CircleShape
                                )
                        )
                    }
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = item.name,
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
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
                            text = "更多",
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowForward,
                            contentDescription = "more"
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(60.dp)) }
        }
    }
}