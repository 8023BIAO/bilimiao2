package cn.a10miaomiao.bilimiao.compose.common.constant

object PageTabIds {

    const val HomeTimeMachine = "home.time-machine"
    /** 直播（首页第一个 Tab：分类标签条 + 直播房间卡片） */
    const val HomeLive = "home.live"
    const val HomeRecommend = "home.recommend"
    const val HomePopular = "home.popular"
    /** 分区（左侧分区条 + 右侧视频卡片） */
    const val HomeRegion = "home.region"
    const val HomeTimeSelect = "home.time-select"
    const val HomeBangumi = "home.bangumi"
    const val HomeCinema = "home.cinema"

    const val DynamicAll = "dynamic.all"
    const val DynamicVideo = "dynamic.video"
    val DynamicByUpper = TabId("dynamic.upper")

    const val SearchAll = "search.all"
    val SearchByType = TabId("search.type")

    const val UserIndex = "user.index"
    const val UserDynamic = "user.dynamic"
    const val UserArchive = "user.archive"
    const val UserSearchArchive = "user.search-archive"
    const val UserSearchDynamic = "user.search-dynamic"

    val MyBangumi = TabId("my.bangumi")

    class TabId(
        private val name: String
    ) {
        operator fun get(key: Int): String {
            return "$name[$key]"
        }
        operator fun get(key: String): String {
            return "$name[$key]"
        }
    }
}