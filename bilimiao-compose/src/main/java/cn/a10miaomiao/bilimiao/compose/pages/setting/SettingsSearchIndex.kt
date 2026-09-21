package cn.a10miaomiao.bilimiao.compose.pages.setting

/**
 * 设置搜索索引（**自动从真实调用点生成**，别手改：数据源是各设置页里 switchPreference/textIntPreference/
 * sliderIntPreference 的 key 与 defaultValue）。
 *
 * 用途：设置首页顶部的搜索框。输入关键词 → 这一页刷成搜索结果，开关/数值项**直接在这里改**；
 * 清空输入框 → 回到 6 个大分类。
 *
 * 为什么单独维护一份而不是直接改各页面：各页面现在仍是手写 DSL，先把"能被搜到、能被改"这件事做出来；
 * 以后要把页面也改成从这份索引渲染（真正的数据驱动），再逐页迁移，不影响搜索。
 */
data class SettingSearchItem(
    /** SettingPreferences 里的属性名，唯一 id */
    val prefName: String,
    /** 偏好键字符串（key.name），渲染控件用 */
    val prefKey: String,
    val title: String,
    /** 一级分类（结果里当归类标签显示） */
    val category: String,
    val kind: Kind,
    val default: Any,
    /** 搜索用关键词（英文名 + 中文同义词） */
    val keywords: String,
) {
    enum class Kind { SWITCH, INT }

    /** 命中判定：标题/分类/关键词/偏好名/存储键 任一包含（大小写不敏感） */
    fun matches(q: String): Boolean {
        val query = q.trim().lowercase()
        if (query.isEmpty()) return false
        return title.lowercase().contains(query) ||
            category.lowercase().contains(query) ||
            keywords.lowercase().contains(query) ||
            prefName.lowercase().contains(query) ||
            prefKey.lowercase().contains(query)
    }
}

object SettingsSearchIndex {

    val items: List<SettingSearchItem> = listOf(
        SettingSearchItem("HomeTimeMachineShow", "home_time_machine_show", "显示时光姬", "② 界面", SettingSearchItem.Kind.SWITCH, false, "显示时光姬 ② 界面 首页 主页"),
        SettingSearchItem("HomeRecommendShow", "home_recommend_show", "显示推荐", "② 界面", SettingSearchItem.Kind.SWITCH, true, "显示推荐 ② 界面 首页 主页"),
        SettingSearchItem("HomePopularShow", "home_popular_show", "显示热门", "② 界面", SettingSearchItem.Kind.SWITCH, false, "显示热门 ② 界面 首页 主页"),
        SettingSearchItem("HomeBangumiShow", "home_bangumi_show", "显示番剧", "② 界面", SettingSearchItem.Kind.SWITCH, true, "显示番剧 ② 界面 首页 主页"),
        SettingSearchItem("HomeCinemaShow", "home_cinema_show", "显示影视", "② 界面", SettingSearchItem.Kind.SWITCH, false, "显示影视 ② 界面 首页 主页"),
        SettingSearchItem("VideoMinDuration", "video_min_duration", "最小视频时长过滤", "③ 内容与评论", SettingSearchItem.Kind.INT, 0, "最小视频时长过滤 ③ 内容与评论 视频 内容"),
        SettingSearchItem("VideoMinPlayCount", "video_min_play_count", "最小播放量过滤", "③ 内容与评论", SettingSearchItem.Kind.INT, 0, "最小播放量过滤 ③ 内容与评论 视频 内容"),
        SettingSearchItem("HomeBangumiGridSpan", "home_bangumi_grid_span", "每行卡片数", "② 界面", SettingSearchItem.Kind.INT, 0, "每行卡片数 ② 界面 首页 主页"),
        SettingSearchItem("VideoHideCover", "video_hide_cover", "不显示封面", "③ 内容与评论", SettingSearchItem.Kind.SWITCH, false, "不显示封面 ③ 内容与评论 视频 内容"),
        SettingSearchItem("VideoHideRelates", "video_hide_relates", "隐藏相关推荐", "③ 内容与评论", SettingSearchItem.Kind.SWITCH, false, "隐藏相关推荐 ③ 内容与评论 视频 内容"),
        SettingSearchItem("TimeSelectShow", "time_select_show", "显示时光精选", "② 界面", SettingSearchItem.Kind.SWITCH, true, "显示时光精选 ② 界面 时光机 时光精选 旧视频"),
        SettingSearchItem("TimeSelectExcludeRecent", "time_select_exclude_recent", "排除最近N天", "② 界面", SettingSearchItem.Kind.INT, 0, "排除最近N天 ② 界面 时光机 时光精选 旧视频"),
        SettingSearchItem("TimeSelectAllRegions", "time_select_all_regions", "全部分区", "② 界面", SettingSearchItem.Kind.SWITCH, true, "全部分区 ② 界面 时光机 时光精选 旧视频"),
        SettingSearchItem("TimeSelectMinDuration", "time_select_min_duration", "最小时长(秒)", "② 界面", SettingSearchItem.Kind.INT, 0, "最小时长(秒) ② 界面 时光机 时光精选 旧视频"),
        SettingSearchItem("TimeSelectMinPlayCount", "time_select_min_play_count", "最小播放量", "② 界面", SettingSearchItem.Kind.INT, 0, "最小播放量 ② 界面 时光机 时光精选 旧视频"),
        SettingSearchItem("TimeSelectOriginalOnly", "time_select_original_only", "只看原创", "② 界面", SettingSearchItem.Kind.SWITCH, true, "只看原创 ② 界面 时光机 时光精选 旧视频"),
        SettingSearchItem("WbiSignEnabled", "wbi_sign_enabled", "WBI 签名", "② 界面", SettingSearchItem.Kind.SWITCH, true, "WBI 签名 ② 界面"),
        SettingSearchItem("AiSummaryEnabled", "ai_summary_enabled", "AI 视频总结", "② 界面", SettingSearchItem.Kind.SWITCH, false, "AI 视频总结 ② 界面"),
        SettingSearchItem("AntifraudEnabled", "antifraud_enabled", "发评论后自动检测是否被限流", "④ 扩展", SettingSearchItem.Kind.SWITCH, false, "发评论后自动检测是否被限流 ④ 扩展 评论 反诈 限流 吞评"),
        SettingSearchItem("AntifraudRecheckEnabled", "antifraud_recheck_enabled", "自动复查（推荐开）", "④ 扩展", SettingSearchItem.Kind.SWITCH, true, "自动复查（推荐开） ④ 扩展 评论 反诈 限流 吞评"),
        SettingSearchItem("AntifraudRecheckMinutes", "antifraud_recheck_minutes", "复查监控时长", "④ 扩展", SettingSearchItem.Kind.INT, com.a10miaomiao.bilimiao.comm.antifraud.CommentAntifraud.DEFAULT_RECHECK_MINUTES, "复查监控时长 ④ 扩展 评论 反诈 限流 吞评"),
        SettingSearchItem("CdnRaceEnabled", "cdn_race_enabled", "CDN 竞速", "④ 扩展", SettingSearchItem.Kind.SWITCH, true, "CDN 竞速 ④ 扩展 CDN 节点 线路 加速"),
        SettingSearchItem("AudioIndependentCdn", "audio_independent_cdn", "音频不跟随 CDN", "④ 扩展", SettingSearchItem.Kind.SWITCH, false, "音频不跟随 CDN ④ 扩展 CDN 节点 线路 加速"),
        SettingSearchItem("ThreadRipperEnable", "thread_ripper_enable", "启用分段并发下载", "④ 扩展", SettingSearchItem.Kind.SWITCH, false, "启用分段并发下载 ④ 扩展 海外 加速 并发 分段 卡顿"),
        SettingSearchItem("ThreadRipperThreads", "thread_ripper_threads", "并发连接数", "④ 扩展", SettingSearchItem.Kind.INT, 4, "并发连接数 ④ 扩展 海外 加速 并发 分段 卡顿"),
        SettingSearchItem("PlayerBackground", "player_background", "后台播放", "① 播放", SettingSearchItem.Kind.SWITCH, false, "后台播放 ① 播放 播放 播放器 后台 播放"),
        SettingSearchItem("PlayerPipOnBackground", "player_pip_on_background", "小窗播放", "① 播放", SettingSearchItem.Kind.SWITCH, false, "小窗播放 ① 播放 播放 播放器 小窗 画中画"),
        SettingSearchItem("PlayerOrderRandom", "player_order_random", "随机播放", "① 播放", SettingSearchItem.Kind.SWITCH, false, "随机播放 ① 播放 播放 播放器 播放顺序 连播 循环 随机"),
        SettingSearchItem("PlayerNotification", "player_notification", "显示通知栏播放器控制器", "① 播放", SettingSearchItem.Kind.SWITCH, true, "显示通知栏播放器控制器 ① 播放 播放 播放器 通知栏"),
        SettingSearchItem("PlayerAudioFocus", "player_audio_focus", "占用音频焦点", "① 播放", SettingSearchItem.Kind.SWITCH, true, "占用音频焦点 ① 播放 播放 播放器 音频焦点 声音"),
        SettingSearchItem("PlayerSubtitleShow", "player_subtitle_show", "字幕显示", "① 播放", SettingSearchItem.Kind.SWITCH, true, "字幕显示 ① 播放 播放 播放器 字幕"),
        SettingSearchItem("PlayerSubtitleTextSize", "player_subtitle_text_size", "字幕字号", "① 播放", SettingSearchItem.Kind.INT, DEFAULT_SUBTITLE_TEXT_SIZE, "字幕字号 ① 播放 播放 播放器 字幕"),
        SettingSearchItem("PlayerAiSubtitleShow", "player_ai_subtitle_show", "AI字幕显示", "① 播放", SettingSearchItem.Kind.SWITCH, false, "AI字幕显示 ① 播放 播放 播放器 AI 字幕"),
        SettingSearchItem("PlayerSeekPreviewShow", "player_seek_preview_show", "拖动进度显示预览图", "① 播放", SettingSearchItem.Kind.SWITCH, true, "拖动进度显示预览图 ① 播放 播放 播放器 进度 预览 缩略图"),
        SettingSearchItem("SponsorBlockEnable", "sponsor_block_enable", "启用空降助手", "④ 扩展", SettingSearchItem.Kind.SWITCH, true, "启用空降助手 ④ 扩展 空降 跳过 恰饭 赞助 片头 片尾 推广 广告 屏蔽"),
        SettingSearchItem("SponsorBlockLimit", "sponsor_block_limit", "最短片段时长", "④ 扩展", SettingSearchItem.Kind.INT, 0, "最短片段时长 ④ 扩展 空降 跳过 恰饭 赞助 片头 片尾 推广 广告 屏蔽"),
        SettingSearchItem("SponsorBlockToast", "sponsor_block_toast", "跳过时弹提示", "④ 扩展", SettingSearchItem.Kind.SWITCH, true, "跳过时弹提示 ④ 扩展 空降 跳过 恰饭 赞助 片头 片尾 推广 广告 屏蔽"),
        SettingSearchItem("SponsorBlockTrack", "sponsor_block_track", "上报已跳过", "④ 扩展", SettingSearchItem.Kind.SWITCH, true, "上报已跳过 ④ 扩展 空降 跳过 恰饭 赞助 片头 片尾 推广 广告 屏蔽"),
        SettingSearchItem("BottomBarLock", "bottom_bar_lock", "锁定底栏", "② 界面", SettingSearchItem.Kind.SWITCH, true, "锁定底栏 ② 界面 底栏 导航 滚动 隐藏"),
        SettingSearchItem("BottomBarScrollHideTitle", "bottom_bar_scroll_hide_title", "标题行一起隐藏", "② 界面", SettingSearchItem.Kind.SWITCH, true, "标题行一起隐藏 ② 界面 底栏 导航 滚动 隐藏"),
        SettingSearchItem("PlayerDiskCacheSize", "player_disk_cache_size", "视频播放磁盘缓存", "⑤ 账号与数据", SettingSearchItem.Kind.INT, 512, "视频播放磁盘缓存 ⑤ 账号与数据 播放 播放器"),
        SettingSearchItem("ImageDiskCacheSize", "image_disk_cache_size", "图片缓存上限", "⑤ 账号与数据", SettingSearchItem.Kind.INT, 50, "图片缓存上限 ⑤ 账号与数据 图片 缓存"),
        SettingSearchItem("FollowWhitelistEnabled", "follow_whitelist_enabled", "已关注UP主白名单", "③ 内容与评论", SettingSearchItem.Kind.SWITCH, false, "已关注UP主白名单 ③ 内容与评论 白名单 关注"),
        SettingSearchItem("BlockPromotion", "block_promotion", "屏蔽推广视频", "③ 内容与评论", SettingSearchItem.Kind.SWITCH, false, "屏蔽推广视频 ③ 内容与评论 推广 广告 屏蔽"),
        SettingSearchItem("FilterTagStrict", "filter_tag_strict", "标签查询失败时拦截", "③ 内容与评论", SettingSearchItem.Kind.SWITCH, false, "标签查询失败时拦截 ③ 内容与评论 过滤 屏蔽"),
        SettingSearchItem("DanmakuEnable", "danmaku_enable", "启用弹幕", "① 播放", SettingSearchItem.Kind.SWITCH, true, "启用弹幕 ① 播放 弹幕 danmaku 弹屏"),
        SettingSearchItem("DanmakuSysFont", "danmaku_sys_font", "弹幕使用系统字体", "① 播放", SettingSearchItem.Kind.SWITCH, true, "弹幕使用系统字体 ① 播放 弹幕 danmaku 弹屏"),
        SettingSearchItem("DanmakuFilterEnabled", "danmaku_filter_enabled", "启用弹幕过滤", "其他", SettingSearchItem.Kind.SWITCH, false, "启用弹幕过滤 其他 弹幕 danmaku 弹屏 过滤 屏蔽 弹幕 过滤 关键词"),
        SettingSearchItem("CommentSubReplyPreview", "comment_sub_reply_preview", "显示二级回复", "③ 内容与评论", SettingSearchItem.Kind.SWITCH, false, "显示二级回复 ③ 内容与评论 评论 评论区"),
        SettingSearchItem("DanmakuFilterDuplicate", "danmaku_filter_duplicate", "过滤重复弹幕", "其他", SettingSearchItem.Kind.SWITCH, false, "过滤重复弹幕 其他 弹幕 danmaku 弹屏 过滤 屏蔽 弹幕 过滤 关键词"),
    )

    /** 关键词搜索：空串返回空列表（调用方据此决定是显示 6 大类还是搜索结果） */
    fun search(query: String): List<SettingSearchItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        // 标题命中的排前面，其余按标题长度（更短的通常更相关）
        return items.filter { it.matches(q) }
            .sortedWith(compareByDescending<SettingSearchItem> { it.title.lowercase().contains(q.lowercase()) }
                .thenBy { it.title.length })
    }
}
