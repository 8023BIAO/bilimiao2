package com.a10miaomiao.bilimiao.comm.datastore

object SettingConstants {

    const val HOME_ENTRY_VIEW_DEFAULT = 0 // 时光姬
    const val HOME_ENTRY_VIEW_RECOMMEND = 1 // 推荐
    const val HOME_ENTRY_VIEW_POPULAR = 2 // 热门
    const val HOME_ENTRY_VIEW_DYNAMIC = 3 // 动态
    const val HOME_ENTRY_VIEW_BANGUMI = 5 // 番剧
    const val HOME_ENTRY_VIEW_CINEMA = 6 // 影视

    const val THEME_TYPE_DEFAULT = 0
    const val THEME_TYPE_DYNAMIC_COLOR = 1

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