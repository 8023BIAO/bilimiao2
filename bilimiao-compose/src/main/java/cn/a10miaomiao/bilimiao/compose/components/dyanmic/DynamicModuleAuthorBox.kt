package cn.a10miaomiao.bilimiao.compose.components.dyanmic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.common.localPageNavigation
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage

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
        labelText = author.ptimeLabelText,
        locationText = author.ptimeLocationText,
        showUserInfo = showUserInfo,
        onClick = ::jumpToUser,
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun DynamicModuleAuthorBox(
    name: String,
    face: String,
    labelText: String,
    locationText: String,
    showUserInfo: Boolean = true,
    onClick: (() -> Unit)? = null,
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
            // ★裸头像，**不再**挂「直播中」+ 涟漪：用户 2026-09-28 拍板"直播标记只留两处"
            //   （用户空间顶部大头像 + 动态页 UP 栏头像）。动态卡片作者头像当初挂标记的后果是
            //   "UP 空间动态列表里每条动态的作者头像都在扩散涟漪"，一屏全是动画。
            //   这里连 uid 都不再传 —— 曾经的 `mid` 形参是"查在播状态"的唯一开关，
            //   现在没有任何调用方需要它，留着就是死参数（规则 15：做减法，不留第二种写法）。
            //   点击行为不变：整行（含这颗头像）由上面 Row 的 clickable 负责进他的空间。
            GlideImage(
                model = UrlUtil.autoHttps(face) + "@200w_200h",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
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