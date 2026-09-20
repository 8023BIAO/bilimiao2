package cn.a10miaomiao.bilimiao.compose.base

import androidx.compose.runtime.Composable


abstract class ComposePage {

    @Composable
    abstract fun Content()

    /**
     * 导航去重指纹（可选）：**同一个页面 + 同样的参数**在 600ms 内重复导航时只放行一次。
     *
     * 为什么需要：导航框架的 `launchSingleTop` 只看路由、不看参数，所以不能让所有导航都用它
     * （"相关视频点进另一个视频"会被当成连点、把当前页替换掉，见 VideoDetailPage 那条链）。
     * 默认返回 null = 不去重（行为与从前一致）；只有需要"挡连点、但又必须能压新页"的页面才覆盖它，
     * 例如 [cn.a10miaomiao.bilimiao.compose.pages.video.VideoDetailPage] 返回 "VideoDetailPage/{id}"。
     */
    open val navDedupeKey: String? get() = null

}
