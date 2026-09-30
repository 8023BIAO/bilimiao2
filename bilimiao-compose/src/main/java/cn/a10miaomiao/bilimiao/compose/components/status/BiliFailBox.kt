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
            // ★ gRPC "HTTP 200 + 空 body"：真实错误只在 trailers 里（grpc-status/grpc-message），
            //   那是给开发看的，不该糊在用户脸上（2026-10-01：时光机番剧条目点进去整页
            //   "gRPC header truncated"）。人话主文案在这里；
            //   原始错误（httpCode/grpc-status/grpc-message）已在 BiliGRPCHttp 抛之前进日志。
            is GrpcStatusException -> if (e.isNotFound) {
                "这条内容打不开（可能已下架，或是番剧/影视）"
            } else {
                "服务端没有返回内容，请稍后重试"
            }
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