package cn.a10miaomiao.bilimiao.compose.components.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


@Composable
fun ListStateBox(
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    finished: Boolean = false,
    fail: String? = null,
    listData: List<*>? = null,
    loadMore: () -> Unit = {},
) {
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (finished) {
                Text(
                    "下面没有了",
                    modifier = Modifier.padding(start = 5.dp),
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 14.sp,
                )
            } else if (fail?.isNotBlank() == true) {
                // ★ 以前是"把错误文字本身做成按钮"：屏幕上只有一行灰字，用户看不出它能点，
                //   也没有"重试"两个字 —— 这正是用户卡住时唯一看到的东西。
                //   改成"错误文字（红色）+ 一个明确的重试按钮"，文字最多两行、点不点得着都看得见。
                Text(
                    fail,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = loadMore) {
                    Text(
                        "重试",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp,
                )
                Text(
                    "加载中",
                    modifier = Modifier.padding(start = 5.dp),
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 14.sp,
                )
            } else if (listData?.size == 0){
                Text(
                    "空空如也",
                    modifier = Modifier.padding(start = 5.dp),
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 14.sp,
                )
            } else {
                TextButton(onClick = loadMore) {
                    Text(
                        "加载更多",
                        modifier = Modifier.padding(start = 5.dp),
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
    // ★自动翻页的触发键原来只有 LaunchedEffect(Unit)：只在尾部项**首次进入组合**时跑一次。
    //   首屏就装得下的短列表（实例：楼中楼徽标写"7条回复"、gRPC 第一页只回 6 条）首帧时
    //   loading=true，这次机会被空跑跳过；数据回来后 effect 永不重跑 ⇒ 底部只剩一个没人会去点的
    //   「加载更多」灰字按钮，用户看到的就是"7条回复只显示6条"。
    //   改成按 (loading/finished/条数) 重新触发：
    //   - 数据/状态每次变化都重新判定；翻页把尾部推出视口后本组合被销毁，自然停住；
    //   - lastAutoLoadSize 挡住"服务端回空页却不置 isEnd"的死循环；列表刷新变短时自动重新武装。
    //   （fail 不当开关 —— ⚠️ 这里**不要**改成 fail.isNullOrBlank()，是负优化：
    //     FlowPaginationInfo.fail 是非空 String、默认 ""，很多页面的 fail 只在 refresh() 里清，
    //     一旦拿它当自动翻页的门槛，某次翻页失败就会永久卡死，用户必须下拉刷新才能继续。
    //     是否该加载只看"在途/是否已到底/有没有列表"，失败重试交给上面的「重试」按钮。）
    val lastAutoLoadSize = remember { mutableIntStateOf(-1) }
    LaunchedEffect(loading, finished, listData?.size) {
        val size = listData?.size ?: 0
        if (size < lastAutoLoadSize.intValue) {
            // 列表被刷新/重置（比上次触发时短）⇒ 重新武装
            lastAutoLoadSize.intValue = -1
        }
        if (!loading && !finished && size > 0 && size != lastAutoLoadSize.intValue) {
            lastAutoLoadSize.intValue = size
            loadMore()
        }
    }
}