package cn.a10miaomiao.bilimiao.compose.components.status

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.R
import com.a10miaomiao.bilimiao.comm.network.GrpcStatusException
import java.net.UnknownHostException

@Composable
fun BiliFailBox(
    e: Any,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    val message = remember(e) {
        when (e) {
            is String -> e
            is UnknownHostException -> "网络请求失败"
//            is JsonSyntaxException -> "数据解析失败: $e"
            // gRPC "HTTP 200 + 空 body"：异常 message 本身就是人话（grpc-status 等只在日志里）。
            // 这里按**类型**给出（不靠文案子串），并把文案来源统一到 GrpcStatusException 的常量上，
            // 免得两处各写一句、以后改文案漏一处。
            is GrpcStatusException -> e.message ?: GrpcStatusException.HUMAN_NO_CONTENT
            is Exception -> e.message ?: e.toString()
            else -> e.toString()
        }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            modifier = Modifier
                .widthIn(max = 150.dp)
                .aspectRatio(1f),
            painter = painterResource(id = R.drawable.bili_fail_img),
            contentDescription = "fail",
        )
        Text(
            modifier = Modifier.padding(16.dp),
            text = message,
            color = textColor,
        )
    }
}