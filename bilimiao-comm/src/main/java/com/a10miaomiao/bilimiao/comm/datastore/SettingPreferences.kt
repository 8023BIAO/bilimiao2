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

    /**
     * 设置的内存快照（进程级）。
     *
     * 为什么要有它：DataStore 只有**挂起**读取（`data.first()`），而播放器的初始化和
     * `getMediaSource()` 都是**主线程**在调 —— 那些地方以前只能 runBlocking（顶多加个几百毫秒
     * 超时兜底），于是每次开播、每次换清晰度都有机会卡主线程。
     * 这里的做法：后台一个协程把 `dataStore.data` 一直收进 @Volatile 快照，主线程随时 O(1) 取。
     * `dataStore.data` 本身是热的（设置一改就推新值），所以快照不会过期、也不需要手动失效。
     *
     * 取不到（进程刚起、还没读完）返回 null，调用方照旧走自己的默认值/兜底，绝不阻塞。
     */
    @Volatile
    private var cachedPreferences: Preferences? = null

    /** 设置的内存快照；还没就绪返回 null */
    fun cachedPreferencesOrNull(): Preferences? = cachedPreferences

    private val snapshotScope = CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    private var snapshotJob: kotlinx.coroutines.Job? = null

    /** 进程启动时调一次（BilimiaoCommApp.onCreate）：开始维护设置内存快照 */
    fun warmUpCache(context: Context) {
        if (snapshotJob?.isActive == true) return
        snapshotJob = snapshotScope.launch {
            try {
                context.dataStore.data.collect { prefs ->
                    cachedPreferences = prefs
                }
            } catch (e: Exception) {
                // 收集失败不影响任何功能：调用方拿不到快照就用自己的默认值。
                // 按发布要求这里不留任何日志（要排查就用 debug 包）。
            }
        }
    }

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
    // 显示分区
    // 键名沿用上面这一组的命名（HomeXxxShow / home_xxx_show）；字符串保持不变，
    // 是因为分区功能最早就是往这个键写的，已经点过开关的用户升级后设置不会丢。
    val HomeRegionShow = booleanPreferencesKey("home_region_show")
    // 显示直播（第三阶段 A 路：首页第一个 Tab）。键名沿用这一组的命名习惯，
    // 新键 = 新字符串，所以默认值 true 能直接对"老用户升级后第一次读"生效。
    val HomeLiveShow = booleanPreferencesKey("home_live_show")
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
    // ===== 自定义主题（第 11 项）=====
    // 只做加法：这三个 key 在老版本里根本不存在，读出来是 null，
    // 于是"没保存过自定义颜色"这件事天然可判（state 里对应字段也是 null）。
    // 为什么不复用 ThemeColor 存自定义主色：切到预设色会把 ThemeColor 覆盖掉，
    // 那样"再点自定义"就还原不出用户上次调的三色了（需求要求带着已保存的颜色继续改）。
    // 自定义主色（0xFFRRGGBB；null = 从没保存过自定义配色）
    val ThemeCustomPrimary = intPreferencesKey("theme_custom_primary")
    // 自定义副色；与主色**相等**表示"跟随主色"（此时不覆盖 materialkolor 的调色板）
    val ThemeCustomSecondary = intPreferencesKey("theme_custom_secondary")
    // 自定义点缀色（强调色）；与主色相等表示"跟随主色"
    val ThemeCustomTertiary = intPreferencesKey("theme_custom_tertiary")
    // 深色模式
    val ThemeDarkMode = intPreferencesKey("theme_dark_mode")
    //
    val ThemeAppBarType = intPreferencesKey("theme_app_bar_type")

    /** WBI 签名开关（实验性） */
    val WbiSignEnabled = booleanPreferencesKey("wbi_sign_enabled")

    /** AI 视频总结开关（实验性） */
    val AiSummaryEnabled = booleanPreferencesKey("ai_summary_enabled")

    /**
     * 评论反诈：发完评论自动检测是否被"仅自己可见"（ShadowBan）。
     *
     * 思路来自开源项目 biliSendCommAntifraud
     * https://github.com/freedom-introvert/biliSendCommAntifraud
     * **默认关**：每发一条评论都会多打几个接口，用不上的人不必背这个开销。
     */
    val AntifraudEnabled = booleanPreferencesKey("antifraud_enabled")

    /**
     * 评论反诈 · 自动复查开关。
     *
     * 为什么要复查：评论发出去**先短暂可见、过一会儿才被限流**（B站那套"秋后算账"）是真实现象，
     * 实测就撞上了 —— 发出后 8 秒游客还能看到，之后才只有自己可见。只查一次必然误判成"正常"。
     * 默认**开**。
     */
    val AntifraudRecheckEnabled = booleanPreferencesKey("antifraud_recheck_enabled")

    /**
     * 评论反诈 · 复查监控时长（分钟）。
     *
     * 首查 5 秒后开始，之后每 30 秒复查一次，一直查到这条评论"状态变化"或者跑满这个时长。
     * 上游 biliSendCommAntifraud 的监控是"每分钟一次、最多 30 分钟"，这里默认 5 分钟。
     */
    val AntifraudRecheckMinutes = intPreferencesKey("antifraud_recheck_minutes")

    /** CDN 竞速开关（实验性）：播放前并发测试各CDN延迟，选最快节点 */
    val CdnRaceEnabled = booleanPreferencesKey("cdn_race_enabled")

    /** 音频不跟随CDN（实验性）：音频使用原始CDN，视频使用竞速最优CDN */
    val AudioIndependentCdn = booleanPreferencesKey("audio_independent_cdn")

    /** 固定 CDN 主机（空=默认，用 API 返回的） */
    val SelectedCdnHost = stringPreferencesKey("selected_cdn_host") 
    // 【已移除】DanmakuEngineV2 — 新弹幕引擎已废弃，保留旧引擎

    // ── 分段并发下载（原「线程撕裂者」，海外加速：把每个分段再切成多个字节 Range 并发下载）──
    // 对齐 https://github.com/MrTangLuyao/Bilibili-thread-ripper 的思路：
    // 不再赌单个 CDN 节点，而是把播放器要读的字节范围切小、多路并发拉，谁快用谁。
    /** 总开关。**默认关**：实验性功能，海外用户建议开、国内不建议（自行测试） */
    val ThreadRipperEnable = booleanPreferencesKey("thread_ripper_enable")
    /** 自动线程：线程数按分段大小自适应，上限取 [ThreadRipperThreads]；开时下面那根滑块置灰 */
    val ThreadRipperAutoThreads = booleanPreferencesKey("thread_ripper_auto_threads")
    /** 线程数档位：0 = 不限（自适应，最多到本机处理器核数），1..max = 固定线程数 */
    val ThreadRipperThreads = intPreferencesKey("thread_ripper_threads")

    // ── 2026-09-25 四点改进的开关（对齐 lemonteaau/PiliPlus 的 thread_ripper）──
    // 默认值一律取"不会变差"的那一侧：能回到旧行为的一律默认开、引入新假设的一律默认关。
    /**
     * 智能节点调度：SWRR 平滑加权轮询（按实测吞吐加权，而不是平均轮转）
     * + 速度分 90 秒 TTL + 单次读够 48KiB 才计分。**默认开**；关掉 = 完全回到旧行为。
     */
    val ThreadRipperSmartAssign = booleanPreferencesKey("thread_ripper_smart_assign")

    /**
     * 跨 host 候选合成：把 API 给的签名路径换到内置的其它 B站 CDN 域名上，候选 2~4 → 最多 12 条。
     * **默认关** —— "签名能否跨 host 复用"没有实测验证（改动说明里有风险标注）。
     * 关着时候选列表与失败封禁行为与改动前完全一致。
     */
    val ThreadRipperCrossHost = booleanPreferencesKey("thread_ripper_cross_host")

    /** 自适应抢跑延迟：按实测首块耗时把 900ms 的固定错峰压到 400~900ms。**默认开** */
    val ThreadRipperAdaptiveHedge = booleanPreferencesKey("thread_ripper_adaptive_hedge")

    /**
     * 412/429 风控退让：先"降一档并发 + 180 秒冷静期"，冷静期内再次触发才走原来的熔断。
     * **默认开**；关掉 = 恢复"直接计入 3 次分块失败 → 熔断 10 分钟"。
     */
    val ThreadRipperPushback = booleanPreferencesKey("thread_ripper_pushback")

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
    // 字幕字号（sp，默认 16；建议 12~30）
    val PlayerSubtitleTextSize = intPreferencesKey("player_subtitle_text_size")
    // AI字幕显示
    val PlayerAiSubtitleShow = booleanPreferencesKey("player_ai_subtitle_show")
    // 拖动进度条时在画面中央显示预览缩略图（默认开）
    val PlayerSeekPreviewShow = booleanPreferencesKey("player_seek_preview_show")
    // ── 空降助手（BilibiliSponsorBlock：跳过赞助/恰饭等片段）──
    // 总开关**默认开**（★有意与 PiliPlus 不同：PiliPlus 默认关。理由是要开箱即用；
    // 代价是首次安装就会向第三方 bsbsb.top 发查询——所以设置页里把开关和隐私说明都写清楚了）
    val SponsorBlockEnable = booleanPreferencesKey("sponsor_block_enable")

    /** 每个类别一个处理策略，存 `SponsorSkipType.ordinal`（0禁用/1仅显示/2手动/3跳一次/4总是跳） */
    fun sponsorBlockSkipTypeKey(id: String) = intPreferencesKey("sponsor_block_skip_$id")
    /** 自定义服务端地址（留空 = 用默认 https://www.bsbsb.top；镜像站可填这里） */
    val SponsorBlockServer = stringPreferencesKey("sponsor_block_server")
    /** 每个类别的色块颜色（ARGB int）；缺省 = 用 SponsorCategory 的默认色 */
    fun sponsorBlockColorKey(id: String) = intPreferencesKey("sponsor_block_color_$id")
    // 最短片段时长（秒）：短于它的片段降级成"仅显示"；0 = 不限制
    val SponsorBlockLimit = intPreferencesKey("sponsor_block_limit")
    // 跳过时是否弹提示
    val SponsorBlockToast = booleanPreferencesKey("sponsor_block_toast")
    // 是否上报"已跳过"（服务端统计用）
    val SponsorBlockTrack = booleanPreferencesKey("sponsor_block_track")
    // 长按倍速的倍率（存 ×100 的整数：150=1.5× 200=2× 300=3× 400=4×，默认 3×）
    val PlayerLongPressSpeed = intPreferencesKey("player_long_press_speed")
    // 双击快进/快退的秒数（默认 10 秒）
    val PlayerDoubleTapSeek = intPreferencesKey("player_double_tap_seek")
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
    /**
     * 滚动隐藏底栏时，**是否连"页名"那一条标题行一起收起**（默认是）。
     * 关掉 = 只收起 50dp 菜单排、页名那 20dp 留在屏幕上（旧行为，"我在哪"更有底）。
     * 只在 [BottomBarLock] = 关（也就是允许随滚动隐藏）时才有意义。
     */
    val BottomBarScrollHideTitle = booleanPreferencesKey("bottom_bar_scroll_hide_title")
    // 视频播放磁盘缓存大小（MB, 默认500）
    val PlayerDiskCacheSize = intPreferencesKey("player_disk_cache_size")
    // 图片缓存大小限制（MB, 默认50）
    val ImageDiskCacheSize = intPreferencesKey("image_disk_cache_size")

    // 已关注UP主白名单：开启后已关注UP的视频不受屏蔽规则影响
    val FollowWhitelistEnabled = booleanPreferencesKey("follow_whitelist_enabled")

    // 屏蔽推广视频：card_goto 非 "av" 的视为推广/广告，直接过滤
    val BlockPromotion = booleanPreferencesKey("block_promotion")

    // 屏蔽标签严格模式：标签 gRPC 查询失败时按已屏蔽处理（默认放行，避免网络波动误杀）
    val FilterTagStrict = booleanPreferencesKey("filter_tag_strict")

    // ══════════════════════════════════════════════════════════════════════
    // 直播设置（第四阶段）
    //
    // 为什么单开一组 `live_*` 键，不复用 PlayerBackground / PlayerPipOnBackground：
    //   ① 语义不同：点播的"后台播放"是**继续出声**（MediaSessionService + 前台服务），
    //      直播的"后台继续直播"是**画面照常播**（PiP / 不退播），两者的系统限制与合规要求完全两回事；
    //   ② 用户要的是"设置里有一整块直播设置"，复用点播的键会让同一个开关在两处出现、改一处两处都变。
    //   键名一律 `live_` 前缀：老版本里这些键根本不存在，读出来是 null → 各自走默认值，
    //   所以"老用户升级后行为不变"这件事天然成立（默认值全部对齐现有代码的写死值）。
    // ══════════════════════════════════════════════════════════════════════

    /** 后台继续直播（退到后台**不**暂停）。默认关，见 [SettingConstants.LIVE_BACKGROUND_PLAY_DEFAULT] */
    val LiveBackgroundPlay = booleanPreferencesKey("live_background_play")
    /** 退后台自动进 PIP 小窗。默认开 */
    val LivePipOnBackground = booleanPreferencesKey("live_pip_on_background")
    /**
     * 默认画质。存**接口 qn 原值**（80/150/250/400/10000/15000/20000/30000），
     * 或两个策略值 [SettingConstants.LIVE_QUALITY_HIGHEST] / [SettingConstants.LIVE_QUALITY_LOWEST]。
     */
    val LiveDefaultQuality = intPreferencesKey("live_default_quality")
    /** 默认线路策略：0 = 自动换线（默认）/ 1 = 固定第一条 */
    val LiveLinePolicy = intPreferencesKey("live_line_policy")
    /** 断流/失败时自动重连（重取流 + 换线）。默认开 */
    val LiveAutoReconnect = booleanPreferencesKey("live_auto_reconnect")
    /** 双击画面暂停/继续。默认开 */
    val LiveDoubleTapPause = booleanPreferencesKey("live_double_tap_pause")
    /** 进直播间默认开弹幕。默认开 */
    val LiveDanmakuEnable = booleanPreferencesKey("live_danmaku_enable")
    /**
     * 直播弹幕字号（sp）。默认 15 = LiveDanmakuOverlay 里原来写死的字号。
     * ★直播**自己那套**样式之一：只对直播生效，和点播的字号倍率键互不相干。
     */
    val LiveDanmakuFontSize = intPreferencesKey("live_danmaku_font_size")
    /**
     * 直播弹幕不透明度（%）。默认 100 = 完全不透明。
     * ★直播**自己那套**样式之一（点播那边存的是 0f~1f，量纲不同，所以各用各的键）。
     */
    val LiveDanmakuOpacity = intPreferencesKey("live_danmaku_opacity")
    /**
     * 直播弹幕速度（**倍率** 0.5~2.0，越大越快）。默认 1.0。
     *
     * 为什么用 Float 倍率而不是 Int 百分比：浮层的速度语义就是倍率
     * （`travelDurationMs = 7000ms / speedScale`），存倍率 = 设置页写进去什么、浮层就用什么，
     * 中间不存在换算；默认 1.0 与点播那套的默认速度等价 → 7000ms，行为与接线前逐字一致。
     * 档位真值（0.5~2.0、0.1 一档）与文案在 `LiveDanmakuSettings.LIVE_SPEED_*` / `speedText()`。
     */
    val LiveDanmakuSpeed = floatPreferencesKey("live_danmaku_speed")
    /**
     * 【已停用】直播弹幕"跟随点播弹幕设置"（Boolean，原默认 true）。
     *
     * 用户原话："单独设置就单独设置，这两个跟随又是不跟随的样子，我都蒙了。就让直播的那个弹幕成另一套吧。"
     * → 开关已从设置页删除，`LiveDanmakuSettings.from()` 也不再读它：直播弹幕固定用
     *   上面那几个 `live_danmaku_*` 键（字号/不透明度/速度/显示区域），屏蔽词固定共用点播那一份。
     *
     * ★键对象与默认值 [SettingConstants.LIVE_DANMAKU_FOLLOW_VOD_DEFAULT] **原样保留、字符串一个字没改**
     *   （DataStore 按字符串相等认键，删键 = 老用户已落盘的值变孤儿）；
     *   键名字符串仍与 `comm.live.danmaku.LiveDanmakuSettings.KEY_FOLLOW_ON_DEMAND` 一致，
     *   但全工程**零处读写**，老数据只是静静躺着。
     */
    val LiveDanmakuFollowVod = booleanPreferencesKey("live_danmaku_follow_vod")
    /**
     * 直播弹幕显示区域（%）：25/50/75/100。默认 100 = 全屏。
     * ★键字符串同样与 `LiveDanmakuSettings.KEY_AREA_PERCENT` 一致（同一份约定）。
     * 同样是直播自己那套（点播只有"最大行数"这个行数模型，没有区域比例语义）。
     */
    val LiveDanmakuAreaPercent = intPreferencesKey("live_danmaku_area_percent")
    /**
     * 直播播放页是否跟随重力感应**自动旋转**（竖屏竖着看、横过来全屏）。
     *
     * 为什么单开一个 `live_auto_rotate`、不复用点播那套 `player_full_mode`：
     * 点播的"全屏模式"是**视频比例驱动**的（竖向视频跟随视频方向），直播没有"视频比例"这个前提
     * （流永远是 16:9），用户要的只是"手机怎么拿就怎么显示"，两者判据不同，混用会互相改坏。
     *
     * 为什么默认**开**：现状本来就是跟随系统 —— `AndroidManifest.xml` 里 LivePlayerActivity
     * 没写 `screenOrientation`，且 `configChanges` 已声明 `orientation|screenSize|...`，
     * 转屏不重建 Activity、窗口几何在 `onConfigurationChanged` 里重算。默认值必须对齐现状，
     * 否则老用户升级后会得到"以前转屏能变、现在不变了"这种莫名其妙的回退。
     *
     * 读取方：直播播放页起播/转屏时决定 `requestedOrientation`
     * （`if (autoRotate) SCREEN_ORIENTATION_FULL_SENSOR else 保持不动`，A 路接线，
     * 用法写在 `LiveSettingPage` 的文件头 KDoc 里）。
     */
    val LiveAutoRotate = booleanPreferencesKey("live_auto_rotate")
    /** 直播浏览页每行卡片数：0 = 自适应（默认）/ 1..5 = 固定列数 */
    val LiveGridSpan = intPreferencesKey("live_grid_span")
    /**
     * 直播浏览页默认排序（接口 `sort_type`）。
     * 为什么用 String 而不是 Int：这个值要**原样**发给接口（"online"/"live_time"），
     * 存字符串就不用维护一张"数字 ↔ 字符串"的翻译表，也不会出现表里漏一项就发空串的情况
     * （★实测：`sort_type` 传空串接口返回 0 条，不报错，很难查）。
     */
    val LiveSortType = stringPreferencesKey("live_sort_type")

    /**
     * 首页直播 Tab 的**筛选记忆**（2026-09-26 用户反馈"它没有持久化记忆，番剧/影视就有"）。
     *
     * 格式：`"<顶级分区id>:<子分区id>"`（例如 `"1:0"`=动画区全部、`"-1:0"`=推荐）。
     * 与 `HomeBangumiFilter`（番剧那个）同一套做法：一个字符串键装下整份选择。
     * 排序不在这里 —— 它有自己的键 `live_sort_type`（早就在用了）。
     */
    val HomeLiveFilter = stringPreferencesKey("home_live_filter")

    /**
     * ★「上次停在哪个直播间」（2026-09-26 本轮）：**用户带着直播间离开 App** 时记下的房间号。
     *
     * 只由 `comm.live.LiveLastRoomStore` 读写（`LivePlayerActivity` 的离开/退出钩子叫它），
     * 消费方是"回 App 仍停在直播间"的确定性恢复：回到前台时若 [LiveLastRoomRestore] 还是 true，
     * 就把这个房间号重新开起来。存的是**入口给的原样房间号**（短号/真实号都行 ——
     * 直播间自己进来第一件事就是 `room_init` 换算，与 `EXTRA_ROOM_ID` 的约定完全一致）。
     *
     * ★为什么要落 DataStore 而不是进程级字段：进程被系统回收后，"上次停在哪个直播间"这件事
     *   仍然成立（用户回到 App 时该回到直播间），进程级字段会随着进程一起消失。
     */
    val LiveLastRoom = stringPreferencesKey("live_last_room")

    /**
     * ★「应当恢复」标记（与 [LiveLastRoom] 成对）：true = 下次 App 回到前台要把那个直播间开回来。
     *
     * 三条清理路径（少一条都会误触发，见 `LiveLastRoomStore` 的 KDoc）：
     * ① 用户**主动退出**直播间（返回键 / 顶栏返回）→ 清；
     * ② App 从**非直播间**页面退到后台（例如点播页）→ 清 —— 这就是"点播页离开不该被拉去直播"那条；
     * ③ 直播间自己回到全屏前台（人已经在直播间里了）→ 清（防循环）。
     *
     * 默认（键不存在）= false：老版本升级上来读不到这个键 → 不恢复 → 行为与升级前一致。
     */
    val LiveLastRoomRestore = booleanPreferencesKey("live_last_room_restore")

    /**
     * 直播设置的一把读取。
     *
     * 为什么要有它（而不是让每个调用点自己去 prefs[...] ?: 默认值）：
     *   默认值只写在这里一份，读取方（直播播放页/浏览页）拿到的语义永远和设置页显示的默认值一致；
     *   少写一次 `?: false`，就少一次"设置页显示关、代码按开跑"的机会。
     */
    object Live {
        /**
         * 直播设置的内存快照。字段名就是使用方的语义名。
         *
         * 全部字段都有默认值 = 可以无参构造，所以 AppStore 的 state 直接用它当类型（不用再抄一份）。
         */
        data class Values(
            val backgroundPlay: Boolean = SettingConstants.LIVE_BACKGROUND_PLAY_DEFAULT,
            val pipOnBackground: Boolean = SettingConstants.LIVE_PIP_ON_BACKGROUND_DEFAULT,
            val defaultQuality: Int = SettingConstants.LIVE_DEFAULT_QUALITY_DEFAULT,
            val linePolicy: Int = SettingConstants.LIVE_LINE_POLICY_DEFAULT,
            val autoReconnect: Boolean = SettingConstants.LIVE_AUTO_RECONNECT_DEFAULT,
            /** 直播页是否跟随重力感应自动旋转（竖屏竖着看、横过来全屏）。默认开 */
            val autoRotate: Boolean = SettingConstants.LIVE_AUTO_ROTATE_DEFAULT,
            val doubleTapPause: Boolean = SettingConstants.LIVE_DOUBLE_TAP_PAUSE_DEFAULT,
            val danmakuEnable: Boolean = SettingConstants.LIVE_DANMAKU_ENABLE_DEFAULT,
            val danmakuFontSize: Int = SettingConstants.LIVE_DANMAKU_FONT_SIZE_DEFAULT,
            val danmakuOpacity: Int = SettingConstants.LIVE_DANMAKU_OPACITY_DEFAULT,
            /**
             * 弹幕速度倍率（0.5~2.0，越大越快）。默认 1.0。
             * ★直播自己那套的速度（不再有"跟随点播"这一说，见 [LiveDanmakuFollowVod] 的注释）。
             */
            val danmakuSpeed: Float = SettingConstants.LIVE_DANMAKU_SPEED_DEFAULT,
            /** 弹幕显示区域（%）：25/50/75/100 */
            val danmakuAreaPercent: Int = SettingConstants.LIVE_DANMAKU_AREA_PERCENT_DEFAULT,
            val gridSpan: Int = SettingConstants.LIVE_GRID_SPAN_DEFAULT,
            /**
             * 接口 `sort_type` 原值（"online" / "live_time"）。
             * 空串是**非法值**（实测接口会返回 0 条），所以这里兜底成"按人气"。
             */
            val sortType: String = LIVE_SORT_TYPE_ONLINE,
        ) {
            /** 是否要求"最低可用"画质（播放器在 accept_qn 里挑最小） */
            val qualityLowest: Boolean get() = defaultQuality == SettingConstants.LIVE_QUALITY_LOWEST

            /** 是否"最高可用"（默认，等价于请求原画，由服务端按登录态降级） */
            val qualityHighest: Boolean get() = defaultQuality == SettingConstants.LIVE_QUALITY_HIGHEST

            /** 提交给 `areaRoomList(sortType=...)` 的值；任何非法值都退回"按人气" */
            val sortTypeOrOnline: String
                get() = sortType.takeIf { it == LIVE_SORT_TYPE_LIVE_TIME } ?: LIVE_SORT_TYPE_ONLINE
        }

        /** 接口 sort_type 的两个合法值（实测只有这两个；传别的/空串都会拿到空列表） */
        const val LIVE_SORT_TYPE_ONLINE = "online"
        const val LIVE_SORT_TYPE_LIVE_TIME = "live_time"

        /**
         * 读 Float 设置的**安全兜底**。
         *
         * 为什么要有它：这几个 Float 键在历史版本/手改数据里有可能存成了 Int，
         * DataStore 读 Float 遇到 Int 会抛 `ClassCastException`（点播侧 `PlayerController.initDanmakuContext`
         * 就是这么兜的）。直播速度键虽然是本轮新加的、正常不会有脏数据，
         * 但"设置读崩了 = 直播弹幕整个不显示"，代价太大，所以照点播的规矩兜一层。
         * ★只在**已确认是 Float 键**的地方用；Int/Boolean 键不要用它（那会把类型错误藏起来）。
         */
        private fun Preferences?.floatOr(
            key: Preferences.Key<Float>,
            defaultValue: Float,
        ): Float = try {
            this?.get(key) ?: defaultValue
        } catch (_: ClassCastException) {
            defaultValue
        }

        /** 把一个 Preferences 快照翻译成 [Values]（缺项走默认值，见 [Values] 的字段默认值） */
        fun of(prefs: Preferences?): Values = Values(
            backgroundPlay = prefs?.get(LiveBackgroundPlay)
                ?: SettingConstants.LIVE_BACKGROUND_PLAY_DEFAULT,
            pipOnBackground = prefs?.get(LivePipOnBackground)
                ?: SettingConstants.LIVE_PIP_ON_BACKGROUND_DEFAULT,
            defaultQuality = prefs?.get(LiveDefaultQuality)
                ?: SettingConstants.LIVE_DEFAULT_QUALITY_DEFAULT,
            linePolicy = prefs?.get(LiveLinePolicy)
                ?: SettingConstants.LIVE_LINE_POLICY_DEFAULT,
            autoReconnect = prefs?.get(LiveAutoReconnect)
                ?: SettingConstants.LIVE_AUTO_RECONNECT_DEFAULT,
            autoRotate = prefs?.get(LiveAutoRotate)
                ?: SettingConstants.LIVE_AUTO_ROTATE_DEFAULT,
            doubleTapPause = prefs?.get(LiveDoubleTapPause)
                ?: SettingConstants.LIVE_DOUBLE_TAP_PAUSE_DEFAULT,
            danmakuEnable = prefs?.get(LiveDanmakuEnable)
                ?: SettingConstants.LIVE_DANMAKU_ENABLE_DEFAULT,
            danmakuFontSize = prefs?.get(LiveDanmakuFontSize)
                ?: SettingConstants.LIVE_DANMAKU_FONT_SIZE_DEFAULT,
            danmakuOpacity = prefs?.get(LiveDanmakuOpacity)
                ?: SettingConstants.LIVE_DANMAKU_OPACITY_DEFAULT,
            // ★这里原来读的是"跟随点播弹幕设置"（`LiveDanmakuFollowVod`）：本轮该开关已删除，
            //   读取一并去掉 —— 直播弹幕固定用自己那套键（字号/不透明度/速度/显示区域）。
            danmakuSpeed = prefs.floatOr(LiveDanmakuSpeed, SettingConstants.LIVE_DANMAKU_SPEED_DEFAULT),
            danmakuAreaPercent = prefs?.get(LiveDanmakuAreaPercent)
                ?: SettingConstants.LIVE_DANMAKU_AREA_PERCENT_DEFAULT,
            gridSpan = prefs?.get(LiveGridSpan) ?: SettingConstants.LIVE_GRID_SPAN_DEFAULT,
            sortType = prefs?.get(LiveSortType) ?: LIVE_SORT_TYPE_ONLINE,
        )
    }

    /**
     * 直播设置的**主线程读取口**（★推荐 A 路/LivePlayerActivity 用这个）。
     *
     * 为什么不给一个挂起版本：播放页的 `onCreate/onStop/onUserLeaveHint` 都是主线程回调，
     * 而 DataStore 只有挂起读取（`data.first()`）。这里走的是 [cachedPreferencesOrNull] 那份
     * **进程级内存快照**（`BilimiaoCommApp.onCreate` 里 [warmUpCache] 已经热起来了）：
     * 主线程 O(1) 取，不阻塞、不联网，进程刚起还没读完时返回的就是各字段的默认值。
     *
     * 用法（播放页）：
     * ```
     * val live = SettingPreferences.liveSettings()
     * if (live.backgroundPlay) { ...不暂停... } else delegate?.pause()
     * ```
     * 想在 Compose 里**跟着设置变**，用 `AppStore.stateFlow` 的 `state.live`（同一个 Values 类型）。
     */
    fun liveSettings(): Live.Values = Live.of(cachedPreferencesOrNull())

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
    /**
     * 评论区：一级评论下面直接带出几条二级回复（默认关 = 原版行为：只显示一级，点进去才看二级）。
     * 预览用的数据是接口随一级评论一起返回的（ReplyInfo.replies），开这个开关**不会多打任何请求**。
     * 二级回复同样过 [CommentBlockedWords] 那套屏蔽词（含 /正则/）。
     */
    val CommentSubReplyPreview = booleanPreferencesKey("comment_sub_reply_preview")
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