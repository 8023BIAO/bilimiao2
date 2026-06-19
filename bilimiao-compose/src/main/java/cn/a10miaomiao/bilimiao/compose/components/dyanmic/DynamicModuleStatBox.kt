package cn.a10miaomiao.bilimiao.compose.components.dyanmic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.assets.BilimiaoIcons
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.Common
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.common.Like
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.common.Likefill
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.common.Reply
import cn.a10miaomiao.bilimiao.compose.assets.bilimiaoicons.common.Share
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil


@Composable
fun DynamicModuleStatBox(
    stat: bilibili.app.dynamic.v2.ModuleStat,
) {
    DynamicModuleStatBox(
        share = stat.repost,
        reply = stat.reply,
        like = stat.like,
        isLike = stat.likeInfo?.isLike == true,
    )
}

@Composable
fun DynamicModuleStatBox(
    share: Long,
    reply: Long,
    like: Long,
    isLike: Boolean,
) {
    // 极简风格：不渲染统计数据
}