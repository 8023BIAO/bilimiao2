package com.a10miaomiao.bilimiao.comm.datastore

object SettingConstants {

    const val HOME_ENTRY_VIEW_DEFAULT = 0 // 时光姬
    const val HOME_ENTRY_VIEW_RECOMMEND = 1 // 推荐
    const val HOME_ENTRY_VIEW_POPULAR = 2 // 热门
    const val HOME_ENTRY_VIEW_DYNAMIC = 3 // 动态
    const val HOME_ENTRY_VIEW_BANGUMI = 5 // 番剧
    const val HOME_ENTRY_VIEW_CINEMA = 6 // 影视
    // 分区。为什么取 7 而不是 4：4 已经被"时光精选"占用了（见文件末尾那一段），
    // 这里的值会原样落盘进 HomeEntryView，老数字的含义一个都不能动，新项只能往后取号。
    const val HOME_ENTRY_VIEW_REGION = 7 // 分区
    // 直播（第三阶段 A 路）。同理取 8：0~7 都已经被占用（4 = 时光精选，见文件末尾），
    // 这个值会原样落盘进 HomeEntryView，已经发出去的数字含义一个都不能动。
    const val HOME_ENTRY_VIEW_LIVE = 8 // 直播

    const val THEME_TYPE_DEFAULT = 0
    const val THEME_TYPE_DYNAMIC_COLOR = 1
    // 自定义主题（主题设置页第 11 项）：主色 + 副色 + 点缀色三个种子色。
    // 为什么新开一个类型值，而不是给 THEME_TYPE_DEFAULT 加标志位：
    // ① 老数据只可能是 0/1，读出来含义一字不变（向后兼容靠这个）；
    // ② "预设 / Material You / 自定义" 三者天然互斥，UI 上直接比 type 就行，
    //    不必再去猜"颜色值相等时到底算哪个预设"。
    const val THEME_TYPE_CUSTOM = 2

    const val PLAYER_DECODER_DEFAULT = "default"
    const val PLAYER_DECODER_AV1 = "AV1"

    const val PLAYER_FNVAL_FLV = 2
    const val PLAYER_FNVAL_MP4 = 2
    const val PLAYER_FNVAL_DASH = 4048

    // 0000 0000：什么都不做
    const val PLAYER_OPEN_MODE_DEFAULT = 0
    // 0000 0001：无播放时，自动播放
    const val PLAYER_OPEN_MODE_AUTO_PLAY = 1
    // 0000 0010：自动替换播放中的视频
    const val PLAYER_OPEN_MODE_AUTO_REPLACE = 2
    // 0000 0100：自动替换暂停暂停的视频
    const val PLAYER_OPEN_MODE_AUTO_REPLACE_PAUSE = 4
    // 0000 1000：自动替换播放完成的视频
    const val PLAYER_OPEN_MODE_AUTO_REPLACE_COMPLETE = 8
    // 0001 0000：自动关闭
    const val PLAYER_OPEN_MODE_AUTO_CLOSE = 16
    // 0010 0000：竖屏状态自动全屏
    const val PLAYER_OPEN_MODE_AUTO_FULL_SCREEN = 32
    // 0100 0000：横屏状态自动横屏
    const val PLAYER_OPEN_MODE_AUTO_FULL_SCREEN_LANDSCAPE = 64

    // 0000：播放完结束
    const val PLAYER_ORDER_END = 0
    // 0001：播放完循环
    const val PLAYER_ORDER_LOOP = 1
    // 0010：自动下一P
    const val PLAYER_ORDER_NEXT_P = 2
    // 0100：自动下一个视频
    const val PLAYER_ORDER_NEXT_VIDEO = 4
    // 1000：自动下一集（番剧）
    const val PLAYER_ORDER_NEXT_EPISODE = 8
    // 默认：自动下一P + 自动下一个视频 + 自动下一集（番剧）
    const val PLAYER_ORDER_DEFAULT = PLAYER_ORDER_NEXT_P or PLAYER_ORDER_NEXT_VIDEO or PLAYER_ORDER_NEXT_EPISODE

    // 跟随视频
    const val PLAYER_FULL_MODE_AUTO = 0
    // 跟随系统
    const val PLAYER_FULL_MODE_UNSPECIFIED = 8
    // 横向全屏(自动旋转)
    const val PLAYER_FULL_MODE_SENSOR_LANDSCAPE = 3
    // 横向全屏(固定方向1)
    const val PLAYER_FULL_MODE_LANDSCAPE = 1
    // 横向全屏(固定方向2)
    const val PLAYER_FULL_MODE_REVERSE_LANDSCAPE = 2

    // 小屏时显示底部进度条
    const val  PLAYER_BOTTOM_PROGRESS_BAR_SHOW_IN_SMALL = 1
    // 全屏时显示底部进度条
    const val  PLAYER_BOTTOM_PROGRESS_BAR_SHOW_IN_FULL = 2
    // 画中画时显示底部进度条
    const val  PLAYER_BOTTOM_PROGRESS_BAR_SHOW_IN_PIP = 4

    // 倍速值集合
    val PLAYER_SPEED_SETS = setOf("0.5", "1.0", "2.0")

    // 播放器定时关闭默认值(秒)，0表示关闭
    const val PLAYER_AUTO_STOP_DURATION_DEFAULT = 0

    // 视频最小过滤时长默认值(秒)，0表示不过滤
    const val VIDEO_MIN_DURATION_DEFAULT = 0

    // 视频最小播放量过滤默认值(个)，0表示不过滤
    const val VIDEO_MIN_PLAY_COUNT_DEFAULT = 0

    // ===== 直播设置（第四阶段）=====
    // 为什么单独一组常量、而不是复用 PLAYER_* / DownloadQuality*：
    //   直播的"后台播放/小窗/画质"和点播不是同一件事（点播后台播放是继续出声，
    //   直播是画面照常播），取舍和风险都不一样，混用会让同一个开关在两处互相影响。
    //
    // 默认画质存的是**接口 qn 原值**，另有两个负数"策略值"表示"由播放器按可用清晰度挑"。
    // 为什么用负数：qn 取值是 80~30000（见下表），负数永远不会和真实 qn 撞车，判据一眼可读。
    /** 最高可用（默认）：请求原画，未登录时服务端会静默降到 250（实测，见方案 §2） */
    const val LIVE_QUALITY_HIGHEST = -1
    /** 最低可用：在接口返回的 `accept_qn` 里挑最小的那条 */
    const val LIVE_QUALITY_LOWEST = -2

    // ★实测清晰度全表（方案 §2 `g_qn_desc` 原样，顺序即接口给的顺序）：
    //   30000 杜比 / 20000 4K / 15000 2K / 10000 原画 / 400 蓝光 / 250 超清 / 150 高清 / 80 流畅。
    // LiveAPI 的 companion 里只有 10000/400/250/150/80 五个，缺 4K/2K/杜比三个 ——
    // 这里只**补常量**（不动 LiveAPI，避免和播放侧改动撞车），值必须和接口表一致。
    const val LIVE_QUALITY_DOLBY = 30000
    const val LIVE_QUALITY_4K = 20000
    const val LIVE_QUALITY_2K = 15000

    /** 默认线路策略：自动换线（= 播放器现有行为：一条不通就试下一条） */
    const val LIVE_LINE_POLICY_AUTO = 0
    /** 默认线路策略：固定接口给的第一条，不自动换（排查"到底哪条线路好"时有用） */
    const val LIVE_LINE_POLICY_FIRST = 1

    /** 直播列表排序：按人气（接口 sort_type=online，= 现状） */
    const val LIVE_SORT_ONLINE = 0
    /** 直播列表排序：按最新开播（接口 sort_type=live_time） */
    const val LIVE_SORT_LIVE_TIME = 1

    /** 直播列表每行卡片数：0 = 自适应（= 现状 GridCells.Adaptive(300.dp)） */
    const val LIVE_GRID_SPAN_AUTO = 0

    // 默认值集中放这里：设置页（switchPreference 的 defaultValue）和读取口
    // （SettingPreferences.Live.of）取的是同一个常量，不会出现"UI 显示默认关、代码当默认开"的漂移。
    //
    // ★直播设置页精简（本轮）：下面这几项的**默认值一个字没改**，只是设置页不再显示它们。
    //   判据是"播放页/首页有没有更顺手的入口"（用户原话逐条见 LiveSettingPage 文件头 KDoc）：
    //     · `live_background_play` / `live_pip_on_background` → 播放页底栏「设置」浮层 + 底栏 PIP 按钮；
    //     · `live_double_tap_pause` → 默认就是开，需要改时同上；
    //     · `live_danmaku_enable` → 播放页底栏「弹幕」按钮（当场生效并写回同一个键）；
    //     · `live_sort_type`（见下面 LIVE_SORT_DEFAULT）→ 首页直播 Tab 底栏「筛选」弹窗。
    //   为什么不连键一起删：DataStore 按**字符串相等**认键，删键 = 老用户已落盘的值变孤儿；
    //   而且读取方（播放页 onStop/onUserLeaveHint/手势层、弹幕链路、首页列表）全都没动。
    /** 后台继续直播：默认**关**（退后台 = 暂停），与 LivePlayerActivity.onStop 的现有行为一致 */
    const val LIVE_BACKGROUND_PLAY_DEFAULT = false
    /**
     * 退后台自动进 PIP 小窗：默认**关**（[A2-fix 2026-09-26] 用户要求"只有点小窗按钮才有小窗"）。
     * 直播页 [enterPipMode] 的手动按钮不受本值影响；[LivePlayerActivity.onUserLeaveHint] 的
     * 自动进入分支因默认关闭而不再触发。旧版本若曾显式写过 true，仍按持久化值走。
     */
    const val LIVE_PIP_ON_BACKGROUND_DEFAULT = false
    /** 自动重连：默认开，与 LivePlayerDelegate 现有的失败换线/重取流行为一致 */
    const val LIVE_AUTO_RECONNECT_DEFAULT = true
    /**
     * 直播页自动旋转：默认**开**（跟随重力：竖屏竖着看、横过来全屏）。
     *
     * 为什么默认开：现状就是跟随系统 —— `AndroidManifest.xml` 里 LivePlayerActivity 没写
     * `screenOrientation`，且 `configChanges` 已声明 `orientation|screenSize|...`（转屏不重建）。
     * 默认值对齐现状，老用户升级后行为才逐字不变。
     * 键 `live_auto_rotate` 定义在 [SettingPreferences.LiveAutoRotate]，消费方是直播播放页的
     * `requestedOrientation`（A 路接线，读取方式见 `LiveSettingPage` 文件头 KDoc 的交接段）。
     */
    const val LIVE_AUTO_ROTATE_DEFAULT = true
    /**
     * 双击暂停：默认**开**，与 LivePlayerActivity 手势层现有行为一致。
     *
     * ★本轮起设置页**不再显示**这一项（用户："我都想默认就是开启双击暂停的"）：
     *   默认值仍是这里这个 true，键 `live_double_tap_pause` 与读取逻辑
     *   （`LivePlayerActivity.onDoubleTapPauseEnabled()`）原样保留 ——
     *   ① 老用户以前手动关过的话，读出来还是关（尊重用户已经表达过的意愿，不偷偷改他的盘）；
     *   ② 现在改它 / 改回来的入口在播放页底栏「设置」浮层里，不是死路。
     */
    const val LIVE_DOUBLE_TAP_PAUSE_DEFAULT = true
    /**
     * 直播弹幕开关：默认开，与 LivePlayerActivity.danmakuEnabled 初值一致。
     * ★设置页已不再显示这一项（用户认为与播放页底栏「弹幕」按钮重复，见 LiveSettingPage 文件头）：
     *   键与默认值保留，读取方 `LiveDanmakuSettings.from()` 一个字没动。
     */
    const val LIVE_DANMAKU_ENABLE_DEFAULT = true
    /**
     * 直播弹幕字号（sp）：默认 15，与 LiveDanmakuOverlay 里写死的 DANMAKU_FONT_SIZE 一致。
     * ★本轮起它是**直播自己那套**里的一员（键 `live_danmaku_font_size`），只对直播生效，
     *   不再受"要不要跟随点播"影响（那个开关已经删掉，见下面 [LIVE_DANMAKU_FOLLOW_VOD_DEFAULT]）。
     */
    const val LIVE_DANMAKU_FONT_SIZE_DEFAULT = 15
    /**
     * 直播弹幕不透明度（%）：默认 100 = 完全不透明（现状）。
     * 同 [LIVE_DANMAKU_FONT_SIZE_DEFAULT]：直播自己那套，只对直播生效。
     */
    const val LIVE_DANMAKU_OPACITY_DEFAULT = 100
    /**
     * 直播弹幕速度（**倍率**，越大越快）：默认 **1.0**。
     *
     * 默认值的依据 = "行为与现在一致"：点播那套速度键（`{mode}_danmaku_speed`）的默认倍率就是 1.0，
     * 而浮层的穿越时长是 `travelDurationMs = 7000 / speedScale`（`LiveDanmakuSettings`），
     * 所以 1.0 = 7000ms 跑完全程 = 接线前写死的那个 7000ms，一分不差。
     *
     * 取值与映射（设置页滑杆就是这一档，选项真值取 `LiveDanmakuSettings.LIVE_SPEED_*`）：
     * | 倍率 | 观感 | 一条弹幕穿过屏幕 |
     * |---|---|---|
     * | 0.5x | 慢 | 14.0 秒 |
     * | 1.0x | 正常（默认） | 7.0 秒 |
     * | 2.0x | 快 | 3.5 秒 |
     *
     * 键 `live_danmaku_speed`，读取方是 `LiveDanmakuSettings.from()`
     * （`Live.Values.danmakuSpeed` → `speedScale` → 浮层的 `travelDurationMs`）。
     */
    const val LIVE_DANMAKU_SPEED_DEFAULT = 1f
    /**
     * 【已停用】"跟随点播弹幕设置"的默认值（键 `live_danmaku_follow_vod`，原默认 true）。
     *
     * 用户原话："单独设置就单独设置，这两个跟随又是不跟随的样子，我都蒙了。就让直播的那个弹幕成另一套吧。"
     * → 本轮把这个开关**从设置页和弹幕链路里整个删掉**（UI 与读取都没了），直播弹幕的
     *   字号/不透明度/速度/显示区域固定走它自己那套 `live_danmaku_*` 键。
     *
     * ★那为什么这个常量还留着：DataStore 按**字符串相等**认键，键一旦发布就不能回收
     *   （删常量/改字符串 = 老用户已经落盘的值变成孤儿，将来想复用同一个键还会读到旧值）。
     *   所以键对象 `SettingPreferences.LiveDanmakuFollowVod` 与这个默认值**原样保留**，
     *   只是全工程**没有一处再读它或写它** —— 老数据静静地躺在那儿，不影响任何行为。
     */
    const val LIVE_DANMAKU_FOLLOW_VOD_DEFAULT = true
    /**
     * 直播弹幕显示区域（%）：25 = 1/4 屏 / 50 = 半屏 / 75 = 3/4 屏 / 100 = **全屏（默认）**。
     * 默认 100 = 不做任何裁剪 = 与浮层原来的行为完全一致。
     * 选项列表与文案不在这里复述：用 `LiveDanmakuSettings.AREA_PERCENT_OPTIONS` / `areaPercentText()`，
     * 免得设置页写的档位和浮层实际支持的档位对不上。
     */
    const val LIVE_DANMAKU_AREA_PERCENT_DEFAULT = 100
    /** 默认画质：默认"最高可用"（= 现状 requestedQn = LiveAPI.QUALITY_ORIGIN） */
    const val LIVE_DEFAULT_QUALITY_DEFAULT = LIVE_QUALITY_HIGHEST
    /** 默认线路策略：自动 */
    const val LIVE_LINE_POLICY_DEFAULT = LIVE_LINE_POLICY_AUTO
    /**
     * 直播列表默认排序：按人气。
     * ★设置页已不再显示排序项（用户要求挪到首页直播 Tab 的底栏筛选弹窗，B 路）：
     *   键 `live_sort_type`、这个默认值、读取口 `Values.sortTypeOrOnline` 全部保留 ——
     *   首页列表读的还是同一份值，只是"在哪儿改"从设置页换成了列表底栏。
     */
    const val LIVE_SORT_DEFAULT = LIVE_SORT_ONLINE
    /** 直播列表默认每行卡片数：自适应 */
    const val LIVE_GRID_SPAN_DEFAULT = LIVE_GRID_SPAN_AUTO

    // ===== 时光精选 =====
    const val HOME_ENTRY_VIEW_TIME_SELECT = 4 // 时光精选
    const val TIME_SELECT_TIME_MODE_ALL = 0       // 全部时间
    const val TIME_SELECT_TIME_MODE_PAST = 1      // 只看过去
    const val TIME_SELECT_TIME_MODE_CUSTOM = 2    // 自定义范围
    const val TIME_SELECT_DEFAULT_WEIGHTS = "favorite:75,click:15,danmaku:5,reply:5"
    const val TIME_SELECT_DEFAULT_PAST_DAYS = 365
    const val TIME_SELECT_DEFAULT_EXCLUDE_RECENT = 0
    const val TIME_SELECT_DEFAULT_PAGES = 3
    const val TIME_SELECT_DEFAULT_PAGE_SIZE = 20

}