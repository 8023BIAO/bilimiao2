package cn.a10miaomiao.bilimiao.compose.common.navigation

/**
 * 完整视频链接带 `?t=` 秒数时的"当前页原地接管"总线（单 handler）。
 *
 * 为什么需要它：导航层只负责决定"压不压新页"，但"播放器没打开/正在放别的视频时，
 * 怎么把**本页这条视频**拉到目标时间点"只有视频详情页的 ViewModel 做得到（它手里有
 * 详情、分P和播放器 delegate）。所以导航层先问当前页能不能接管，能就不导航。
 *
 * 同页 seek 复用评论区空降那条路（[cn.a10miaomiao.bilimiao.compose.pages.video.VideoDetailViewModel.seekToPosition]），
 * 不另写一套播放器逻辑。
 *
 * ★ 只有**当前组合中的** VideoDetailPage 注册（DisposableEffect）；返回 true 表示已接管，
 *   调用方不要再导航。
 */
object VideoLinkSeekBus {

    /**
     * 参数：视频 id（BV 号或 av 号）+ 目标毫秒位置。
     * 返回 true = 当前视频详情页已接管；false = 不是它的视频（或详情还没加载好，让导航层兜底）。
     */
    var onSeek: ((videoId: String, seekMs: Long) -> Boolean)? = null
}
