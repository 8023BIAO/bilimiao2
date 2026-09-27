package cn.a10miaomiao.bilimiao.compose.components.dyanmic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.common.localPageNavigation
import cn.a10miaomiao.bilimiao.compose.components.user.LiveBadgedAvatar
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage

/**
 * 动态作者
 */
@Composable
fun DynamicModuleAuthorBox(
    author: bilibili.app.dynamic.v2.ModuleAuthor,
    isJumpToUser: Boolean = true,
    showUserInfo: Boolean = true,
) {
    val authorData = author.author ?: return
    val pageNavigation = localPageNavigation()
    fun jumpToUser() {
        if (isJumpToUser) {
            pageNavigation.navigate(
                UserSpacePage(
                    id = authorData.mid.toString(),
                )
            )
        }
    }
    DynamicModuleAuthorBox(
        name = authorData.name,
        face = authorData.face,
        // 传 uid 进去，作者头像才会去查"在不在播"并挂「直播中」+涟漪（见 LiveBadgedAvatar）
        mid = authorData.mid.takeIf { it > 0 }?.toString(),
        labelText = author.ptimeLabelText,
        locationText = author.ptimeLocationText,
        showUserInfo = showUserInfo,
        onClick = ::jumpToUser,
    )
}

@Composable
fun DynamicModuleAuthorBox(
    name: String,
    face: String,
    labelText: String,
    locationText: String,
    showUserInfo: Boolean = true,
    onClick: (() -> Unit)? = null,
    /**
     * 作者 uid。给了才查在播状态 —— 别的调用方（比如视频列表卡片）只有名字和头像，
     * 传 null 就退化成原来的纯头像，行为不变。
     */
    mid: String? = null,
) {
    Row(
        modifier = Modifier
            .run {
                if (onClick == null) this
                else clickable(onClick = onClick)
            }
            .fillMaxWidth()
            .padding(10.dp)
    ) {
        if (showUserInfo) {
            LiveBadgedAvatar(
                face = face,
                size = 40.dp,
                mid = mid,
                // 没在播时点头像 = 原来的"进他空间"；在播时被"进直播间"顶掉
                onClick = onClick,
            )
            Column(
                modifier = Modifier.padding(start = 5.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row {
                    Text(
                        text = labelText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = locationText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = locationText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}