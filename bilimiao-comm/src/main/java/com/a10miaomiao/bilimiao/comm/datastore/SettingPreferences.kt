package com.a10miaomiao.bilimiao.comm.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

object SettingPreferences {

    val Context.dataStore: DataStore<Preferences>
            by preferencesDataStore(name = "settings")

    inline fun launch(
        scope: CoroutineScope,
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        crossinline block: suspend SettingPreferences.() -> Unit
    ) = scope.launch(context, start) {
        block()
    }

    suspend fun edit(
        context: Context,
        transform: suspend SettingPreferences.(MutablePreferences) -> Unit
    ) {
        context.dataStore.edit {
            transform(it)
        }
    }

    suspend fun getData(
        context: Context,
        block: suspend SettingPreferences.(Preferences) -> Unit
    ) {
        val preferences = context.dataStore.data.first()
        block(preferences)
    }

    suspend fun <T> mapData(
        context: Context,
        block: suspend SettingPreferences.(Preferences) -> T
    ): T {
        val preferences = context.dataStore.data.first()
        return block(preferences)
    }

    /**
     * General
     */

    /**
     * Home
     */
    // 显示时光姬
    val HomeTimeMachineShow = booleanPreferencesKey("home_time_machine_show")
    // 显示推荐
    val HomeRecommendShow = booleanPreferencesKey("home_recommend_show")
    // 显示热门
    val HomePopularShow = booleanPreferencesKey("home_popular_show")
    // 显示番剧
    val HomeBangumiShow = booleanPreferencesKey("home_bangumi_show")
    // 显示影视
    val HomeCinemaShow = booleanPreferencesKey("home_cinema_show")
    // 【已删除】HomePopularCarryToken — 热门API不支持个性化，开关无实际作用
    // 推荐列表样式
    val HomeRecommendListStyle = intPreferencesKey("home_recommend_list_style")
    // 首页入口视图
    val HomeEntryView = intPreferencesKey("home_entry_view")
    // 视频最小过滤时长(秒)，0表示不过滤
    val VideoMinDuration = intPreferencesKey("video_min_duration")
    // 视频最小播放量过滤(个)，0表示不过滤
    val VideoMinPlayCount = intPreferencesKey("video_min_play_count")
    // 番剧/影视首页卡片列数 (1-5, 默认2)
    val HomeBangumiGridSpan = intPreferencesKey("home_bangumi_grid_span")
    // 番剧筛选持久化
    val HomeBangumiFilter = stringPreferencesKey("home_bangumi_filter")
    // 影视筛选持久化
    val HomeCinemaFilter = stringPreferencesKey("home_cinema_filter")
    // 隐藏视频封面
    val VideoHideCover = booleanPreferencesKey("video_hide_cover")
    // 隐藏视频详情页相关推荐
    val VideoHideRelates = booleanPreferencesKey("video_hide_relates")

    // ===== 时光精选 =====
    val TimeSelectShow = booleanPreferencesKey("time_select_show")
    val TimeSelectTimeMode = intPreferencesKey("time_select_time_mode")
    val TimeSelectPastDays = intPreferencesKey("time_select_past_days")
    val TimeSelectExcludeRecent = intPreferencesKey("time_select_exclude_recent")
    val TimeSelectCustomFrom = stringPreferencesKey("time_select_custom_from")
    val TimeSelectCustomTo = stringPreferencesKey("time_select_custom_to")
    val TimeSelectWeights = stringPreferencesKey("time_select_weights")
    val TimeSelectSelectedRegions = stringSetPreferencesKey("time_select_regions")
    val TimeSelectAllRegions = booleanPreferencesKey("time_select_all_regions")
    val TimeSelectPagesPerRegion = intPreferencesKey("time_select_pages_per_region")
    val TimeSelectPageSize = intPreferencesKey("time_select_page_size")
    val TimeSelectMinDuration = intPreferencesKey("time_select_min_duration")
    val TimeSelectMinPlayCount = intPreferencesKey("time_select_min_play_count")
    val TimeSelectOriginalOnly = booleanPreferencesKey("time_select_original_only")

    /**
     * Theme
     */
    // 主题颜色
    val ThemeColor = longPreferencesKey("theme_color")
    // 主题类型
    val ThemeType = intPreferencesKey("theme_type")
    // 深色模式
    val ThemeDarkMode = intPreferencesKey("theme_dark_mode")
    //
    val ThemeAppBarType = intPreferencesKey("theme_app_bar_type")

    /** WBI 签名开关（实验性） */
    val WbiSignEnabled = booleanPreferencesKey("wbi_sign_enabled")

    /** AI 视频总结开关（实验性） */
    val AiSummaryEnabled = booleanPreferencesKey("ai_summary_enabled")

    /** CDN 竞速开关（实验性）：播放前并发测试各CDN延迟，选最快节点 */
    val CdnRaceEnabled = booleanPreferencesKey("cdn_race_enabled")

    /** 音频不跟随CDN（实验性）：音频使用原始CDN，视频使用竞速最优CDN */
    val AudioIndependentCdn = booleanPreferencesKey("audio_independent_cdn")

    /** 固定 CDN 主机（空=默认，用 API 返回的） */
    val SelectedCdnHost = stringPreferencesKey("selected_cdn_host") 
    // 【已移除】DanmakuEngineV2 — 新弹幕引擎已废弃，保留旧引擎

    /**
     * Player
     */
    // 解码器
    val PlayerDecoder = intPreferencesKey("player_decoder")
    // 清晰度
    val PlayerQuality = intPreferencesKey("player_quality")
    // 倍速
    val PlayerSpeed = floatPreferencesKey("player_speed")
    // 屏幕缩放类型
    val PlayerScreenType = intPreferencesKey("player_screen_type")
    // 格式
    val PlayerFnval = intPreferencesKey("player_fnval")
    // DASH播放器缓冲时间(秒)，默认15
    val PlayerDashBufferSec = intPreferencesKey("player_dash_buffer_sec")
    // 后台播放
    val PlayerBackground = booleanPreferencesKey("player_background")
    // 后台小窗播放（退出APP自动转小窗）
    val PlayerPipOnBackground = booleanPreferencesKey("player_pip_on_background")
    // 代理
    val PlayerProxy = stringPreferencesKey("player_proxy")
    // 播放器打开模式
    // 0000 0000：什么都不做
    // 0000 0001：无播放时，自动播放
    // 0000 0010：自动替换播放中的视频
    // 0000 0100：自动替换暂停暂停的视频
    // 0000 1000：自动替换播放完成的视频
    // 0001 0000：自动关闭
    // 0010 0000：竖屏状态自动全屏
    // 0100 0000：横屏状态自动全屏
    val PlayerOpenMode = intPreferencesKey("player_open_mode")
    // 播放顺序
    // 0000：播放完结束
    // 0001：播放完循环
    // 0010：自动下一P
    // 0100：自动下一个视频
    // 1000：自动下一集（番剧）
    val PlayerOrder = intPreferencesKey("player_order")
    // 随机播放
    val PlayerOrderRandom = booleanPreferencesKey("player_order_random")
    // 显示通知栏控制器
    val PlayerNotification = booleanPreferencesKey("player_notification")
    // 全屏模式
    val PlayerFullMode = intPreferencesKey("player_full_mode")
    // 底部进度条显示控制
    val PlayerBottomProgressBarShow = intPreferencesKey("player_bottom_progress_bar_show")
    // 倍速菜单值
    val PlayerSpeedValues = stringSetPreferencesKey("player_speed_values")
    // 占用音频焦点
    val PlayerAudioFocus = booleanPreferencesKey("player_audio_focus")
    // 字幕显示
    val PlayerSubtitleShow = booleanPreferencesKey("player_subtitle_show")
    // AI字幕显示
    val PlayerAiSubtitleShow = booleanPreferencesKey("player_ai_subtitle_show")
    // 小屏显示面积
    val PlayerSmallShowArea = intPreferencesKey("player_small_show_area")
    // 挂起时显示面积
    val PlayerHoldShowArea = intPreferencesKey("player_hold_show_area")
    // 小屏是否可拖动
    val PlayerSmallDraggable = booleanPreferencesKey("player_small_draggable")
    // 播放器定时关闭时间(秒)，0表示关闭
    val PlayerAutoStopDuration = intPreferencesKey("player_auto_stop_duration")
    // 锁定底栏，不随滚动隐藏
    val BottomBarLock = booleanPreferencesKey("bottom_bar_lock")
    // 视频播放磁盘缓存大小（MB, 默认500）
    val PlayerDiskCacheSize = intPreferencesKey("player_disk_cache_size")
    // 图片缓存大小限制（MB, 默认250）
    val ImageDiskCacheSize = intPreferencesKey("image_disk_cache_size")

    // 已关注UP主白名单：开启后已关注UP的视频不受屏蔽规则影响
    val FollowWhitelistEnabled = booleanPreferencesKey("follow_whitelist_enabled")

    // 屏蔽推广视频：card_goto 非 "av" 的视为推广/广告，直接过滤
    val BlockPromotion = booleanPreferencesKey("block_promotion")

    /**
     * Danmaku
     */
    // 启用弹幕
    val DanmakuEnable = booleanPreferencesKey("danmaku_enable")
    // 使用系统字体
    val DanmakuSysFont = booleanPreferencesKey("danmaku_sys_font")
    // 时间同步
    val DanmakuTimeSync = booleanPreferencesKey("danmaku_time_sync")
    // 默认状态
    val DanmakuDefault = Danmaku("default")
    // 小屏模式
    val DanmakuSmallMode = Danmaku("small")
    // 全屏模式
    val DanmakuFullMode = Danmaku("full")
    // 画中画模式
    val DanmakuPipMode = Danmaku("pip")
    // 弹幕过滤
    val DanmakuFilterEnabled = booleanPreferencesKey("danmaku_filter_enabled")
    val DanmakuFilterKeywords = stringSetPreferencesKey("danmaku_filter_keywords")
    val CommentBlockedWords = stringSetPreferencesKey("comment_blocked_words")
    val DanmakuFilterDuplicate = booleanPreferencesKey("danmaku_filter_duplicate")

    class Danmaku(
        val name: String,
    ) {
        // 启用设置
        val enable = booleanPreferencesKey("${name}_danmaku_enable")
        // 显示
        val show = booleanPreferencesKey("${name}_danmaku_show")
        // 滚动显示
        val r2lShow = booleanPreferencesKey("${name}_danmaku_r2l_show")
        // 顶部显示
        val ftShow = booleanPreferencesKey("${name}_danmaku_ft_show")
        // 底部显示
        val fbShow = booleanPreferencesKey("${name}_danmaku_fb_show")
        // 特殊弹幕显示
        val specialShow = booleanPreferencesKey("${name}_danmaku_special_show")
        // 字体大小
        val fontSize = floatPreferencesKey("${name}_danmaku_fontsize")
        // 不透明度
        val opacity = floatPreferencesKey("${name}_danmaku_opacity")
        // 滚动速度
        val speed = floatPreferencesKey("${name}_danmaku_speed")
        // 最大显示行数
        val maxLines = intPreferencesKey("${name}_danmaku_max_lines")
        // 滚动最大显示行数
        val r2lMaxLine = intPreferencesKey("${name}_danmaku_r2l_max_line")
        // 顶部最大显示行数
        val ftMaxLine = intPreferencesKey("${name}_danmaku_ft_max_line")
        // 底部最大显示行数
        val fbMaxLine = intPreferencesKey("${name}_danmaku_fb_max_line")
    }

    /**
     * Flag
     */
    // 副屏显示
    val FlagSubContentShow = booleanPreferencesKey("flag_sub_content_show")
    // 主副屏分割比
    val FlagContentSplit = intPreferencesKey("flag_content_split")
    // 动画时长
    val FlagContentAnimationDuration = intPreferencesKey("flag_content_animation_duration")
    // 游客模式：临时清除登录信息，用匿名身份访问B站
    val GuestMode = booleanPreferencesKey("guest_mode")

    /**
     * Download
     */
    // 下载画质模式: 0=手动选择, 1=最高画质, 2=最低画质, 3=固定画质
    val DownloadQualityMode = intPreferencesKey("download_quality_mode")
    // 固定下载画质值(quality值)
    val DownloadFixedQuality = intPreferencesKey("download_fixed_quality")
    // 下载列表排序: 0=默认(合集顺序), 1=播放量最多, 2=播放时长最长, 3=发布日期最新
    val DownloadSortOrder = intPreferencesKey("download_sort_order")
}