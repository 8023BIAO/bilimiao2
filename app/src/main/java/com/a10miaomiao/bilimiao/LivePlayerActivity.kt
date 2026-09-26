@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.a10miaomiao.bilimiao

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
// ★本轮：「旋转」按钮在「自动旋转=开」时切出来的方向是**一次性**的，释放时机挂在"设备真的被转动"
//   那一刻上（为什么不能用"下一个配置回调"当释放点，见 [pinOrientationByUser] /
//   [deviceOrientationSentinel] —— `FULL_SENSOR` 读的是手机的物理姿态，电话题一交还就当场弹回去）。
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import cn.a10miaomiao.bilimiao.compose.pages.live.LiveDanmakuOverlayHost
// ★第十四批：底栏「设置」按钮弹的那个直播设置弹窗（compose 模块的 View 桥 —— 本模块没有 Compose
//   编译器插件，见那个类的 KDoc）。与「首页直播 Tab 底栏筛选弹窗」共用同一套外壳 `AutoSheetDialog`。
import cn.a10miaomiao.bilimiao.compose.pages.live.LiveSettingSheetHost
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.delegate.live.LivePlayerDelegate
// ★本轮：顶栏「在线人数」要用的两个类型 —— 都是**现成的**（`LiveAPI().roomInfo()` 的返回壳与实体，
//   本页 `resolveRoom` 的降级链里早就在用同一条接口/同一个实体，没有新增接口也没有新增解析）。
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.live.LiveAPI
import com.a10miaomiao.bilimiao.comm.live.LiveLastRoomStore
import com.a10miaomiao.bilimiao.comm.live.LivePageTrace
import com.a10miaomiao.bilimiao.comm.live.LivePortraitStage
import com.a10miaomiao.bilimiao.comm.live.danmaku.ConnState
import com.a10miaomiao.bilimiao.comm.live.danmaku.LiveDanmakuClient
import com.a10miaomiao.bilimiao.comm.live.danmaku.LiveMessage
import com.a10miaomiao.bilimiao.comm.live.entity.LiveRoomDetail
import com.a10miaomiao.bilimiao.comm.live.entity.LiveRoomInitInfo
import com.a10miaomiao.bilimiao.comm.live.entity.LiveStatus
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.toast
// ★本轮：顶栏「在线人数」的数字文案复用首页直播卡片**同一个**格式化函数
//   （`HomeLiveContent.kt` 的 `"${NumberUtil.converString(item.online)}人气"`），
//   所以"1.2万"这个口径全 App 只有一份实现。
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.widget.player.PlayerViewDrawable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.shuyu.gsyvideoplayer.R as GsyR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 直播播放页（第二阶段 A 路）。
 *
 * ## 为什么是独立 Activity（方案 §3.3）
 * 点播播放器 `PlayerDelegate2` 是 `MainActivity` 里的**单例**（`MainActivity.kt:120` 创建、
 * `:208` 挂到 `ui.root`），想让它播直播就得改 `MainActivity` + `PlayerDelegate2` —— 那是禁改区。
 * 而且直播没有时长/进度条/倍速/SponsorBlock 这些点播语义，独立页面天然把这些冲突消掉：
 * 自己的 ExoPlayer（[LivePlayerDelegate]）、自己的 PiP、自己的生命周期，风险完全隔离。
 *
 * ## 页面结构（纯 Android View，没有 Compose）
 * ```
 * FrameLayout（rootLayout = 用户看到的那一页）
 * ├─ AspectRatioFrameLayout ── TextureView     视频（按视频比例等比，不变形）
 * │     ★竖屏：缩成"顶栏之下的那条带"（宽 ÷ 比例，封顶 62% 页高），顶边 = [videoBandTopPx]
 * │           （状态栏内边距 + 顶栏高度），**底边永远等于列表槽 [danmakuListSlot] 的顶边**
 * │     ★横屏：铺满整页、画面居中（与改动前逐字一致）
 * │     ★PiP：**一律铺满小窗**（关掉带子、顶边归零）—— 小窗里的黑边就是这么没的，
 * │           见 [applyVideoStageLayout] 的第八批那段
 * ├─ danmakuLayer（FrameLayout）              弹幕层容器 → LiveDanmakuOverlayHost（compose 模块的桥接 View）
 * ├─ TapCatcher                                手势层：单击显隐控制条 / 双击播放暂停 / 左右半区上下滑
 * ├─ hudLayer（FrameLayout）                   手势气泡层：音量/亮度（★第三批：从 Dialog 窗口搬进来的）
 * ├─ 顶栏：返回**图标** + 标题（`直播间 房间号（x.x万人在线）`）+ 状态文字
 * │        ★状态文字**只在异常/过渡时显示**（正常播放时 `GONE`，顶栏只剩返回 + 标题）——
 * │          见 [renderStatus]；在线人数跟着房间号写在同一个括号里，见 [renderRoomTitle]。
 * ├─ 底栏（bottomBar）：**输入条 + 五颗按钮（同一行）**（弹幕 / 画质 / **设置** / 画中画 / 旋转）
 * │        ★第八批：竖屏与横屏**同一套一行版式**（用户："把它和那个几个按钮放一起"）；
 * │          输入条**与五颗按钮同一套显隐**（点画面唤出、[CONTROLS_AUTO_HIDE_MS] 后一起消失，
 * │          用 INVISIBLE 保住占位；"正在输入"不收、PiP 里 GONE —— 见 [applyControlsVisibility]）。
 * │          「刷新 / 发弹幕」两颗按钮已删：「刷新」改自动（[autoRetryLiveStream]）+ 弹窗里的
 * │          「重新取流」，「发弹幕」变成输入条本身（"画中画"曾搬到顶栏，用户改主意后又回了底栏）。
 * │        （按钮是纯文字按钮，**不横滑**；五颗**等宽**、同一档字号 —— [applyBottomBarTextSizes]）
 * │        ★第十四批：「设置」回来了（用户点名"画质的后面、PIP 的中间"；第二批删过它，
 * │          这次弹的是与「首页直播 Tab 底栏筛选」同一套外壳的 Compose 弹窗，见 [settingButton]）。
 * │        ★第四批：**「暂停/播放」与它占的那一格已删**（用户："我需要腾空间"），
 * │          暂停入口见下面第四条；「重试」改名「刷新」（功能一行未改）——
 * │          ★第七批起「刷新」这颗按钮本身也没了，改成自动追流，见下面第七批。
 * │        ★第五批：**「听音频」与「UP主」两颗按钮连同它们背后的功能一起删掉**
 * │          （用户："多一事不如少一事"）—— 见下面第五批那一段。
 * └─ ProgressBar                               缓冲指示
 * ```
 * ★竖屏下"弹幕列表"这块面板**不在这棵树里**：它由弹幕宿主注入到 activity 内容视图的最上层
 *   （理由见宿主注释：列表要能滑动，而本页的手势层是"全屏可点"的）。播放页只负责**把地方留出来**
 *   并通过 [LivePortraitStage] 把矩形发布出去 —— 见 [measurePortraitStage]。
 *
 * ## 本轮的四处交互修复（用户实测反馈，详见各实现处的注释与交付报告）
 * 1. **手势提示 = 点播那套的 1:1 复刻**（[GestureHud]）：音量气泡贴左、亮度气泡贴右
 *    （调右半区时提示落在左半区，手指不挡提示），样式/图标/进度条/淡入淡出全部照抄；
 * 2. **播放/暂停去掉图标**，只留「暂停」/「播放」两个中文字（[updatePlayPauseButton]）；
 * 3. **弹窗换成正常弹窗**：清晰度/线路/发弹幕全部走 [MaterialAlertDialogBuilder]
 *    （居中、定宽、内容区封顶可滚），不再自建全屏 Dialog —— 那是"弹窗超出屏幕点不了"的病根；
 * 4. **从桌面回到 App 仍停在直播间**（[ReturnToLiveGuard]）：PiP 会把本页从原任务里搬走，
 *    守卫负责在用户回到 App 时把直播间重新拉起来；自动进 PiP 的行为保留。
 *
 * ★**图层顺序是"钉死的契约"**（曾经弹幕看不见的头号排查项）：顺序由 [buildUi] 末尾那次
 *   `bringToFront()` 序列显式确定，而不是靠"谁后 addView 谁在上面"。弹幕层必须**在视频之上、
 *   手势层之下**：弹幕不吃点击，所以手势层压着它不影响任何操作；而控制条又必须压着弹幕，
 *   否则弹幕会从按钮上飞过去。气泡层夹在**手势层之上、控制条之下**，靠"子树里没有 clickable
 *   控件"保证不吃触摸（见 [GestureHud.addBubble]）。
 *
 * ## 入口
 * `Intent` 显式启动，房间号走 extra [EXTRA_ROOM_ID]（短号/真实号都收，进来先 room_init 换算）。
 * 目前唯一入口是封面页（`bilimiao-cover/.../CoverViewModel.kt` 的 ROOM 分支）。
 *
 * ## 本轮的收尾项（用户反馈 + 诊断报告驱动的最后一批）
 * 1. **发弹幕失败说人话**：失败 toast 改成 `LiveDanmakuClient.lastSendError` 的**服务端原文**
 *    （"发送失败(-400)：请求错误" / 敏感词 / 频率限制 / 长度超限），不再是一句笼统的"可能被风控"；
 * 2. **补齐 3 个还没接线的直播设置**：默认线路策略 / 自动重连 / 双击暂停（见
 *    [applyPlaybackPolicyToDelegate] 与 [onDoubleTapPauseEnabled]；
 *    ★这三个的 UI 入口现在只在「设置 → 直播设置」页里）；
 * 3. **直播 PiP 有自己的播放/暂停按钮**（[buildPipActions] + [LivePipActionReceiver]）：
 *    定向 Intent + 直播专用 action + 唯一 requestCode，只操作本页的 [LivePlayerDelegate]，
 *    与点播那条 `media_control` 通道彻底分开（诊断报告 §5 第 2 条）；
 * 4. 直播的弹幕开关**只写直播自己的键**，绝不回写点播的 `default_danmaku_show`。
 *
 * ## 第二批（用户实测反馈驱动）
 * 1. **手势气泡改成"各自贴自己那一侧"的统一规则**（[GestureHud] + [applyHudGeometry]）：
 *    音量贴左、亮度贴右 —— 修掉"亮度气泡在横屏/全屏被甩出屏幕"（旧写法把两个气泡都按
 *    `TOP|START + 整页宽` 定位，右侧贴边的亮度气泡正好落在窗口右边缘上，窗口一旦越过屏幕
 *    右边缘就被裁掉）。★这一版**仍然是悬浮窗**，竖屏下又暴露了同一类坐标系问题的另一半，
 *    第三批把它整体搬进页面（见下），这里保留记录以便回溯"为什么是三步走到今天"；
 * 2. **删掉底栏「设置」按钮与它弹的直播设置弹窗**（用户："用户想设置自己退出来再去设置"）：
 *    设置项一个没少，只是入口收敛到「设置 → 直播设置」页（原来的 `showLiveSettingsDialog` 已删）；
 * 3. **PiP 播放/暂停按钮点不动**：根因是动作 Intent 用了 `setClass()`（显式组件）而接收器
 *    是**动态注册**的 —— 详见 [buildPipActions] 的证据链；
 * 4. **配色跟随 App 主题**：[appThemeColor] / [appSecondaryColor] / [appTertiaryColor]
 *    直接读「主题设置」那一份键（含自定义主题的三色），[accentColor] 不再是写死的资源色；
 * 5. **新增「自动旋转」的消费端**：[applyAutoRotatePolicy] 读 `live_auto_rotate`（默认开）；
 * 6. **底栏不横滑**：[rebuildBottomBar] 竖屏 5 个/行最多两行、横屏一行，文字给到最短；
 * 7. **弹窗通用加固**：[dialogContentMaxHeightPx] 按**播放页真实高度**（并扣掉输入法高度）封顶，
 *    发弹幕弹窗也进可滚容器 —— 小屏/横屏/输入法遮挡下按钮都点得到。
 *
 * ## 「听音频」模式（★第五批已整块删除，这里只留"删了什么、为什么"）
 * 这里曾经有一条完整的「听音频」链路（底栏第 2 颗按钮 → 摘渲染面 + 关视频轨 → 音频舞台 UI →
 * 前台服务 `LiveAudioService` 的 MediaSession 媒体通知）。用户在本轮明确要求删掉：
 * > "听音频这个功能删除掉。不再需要了，还有听音频这个直播间的相关代码也删除掉。**不要乱删掉其他的东西。**"
 * 于是：按钮、[audioOnly] 状态与所有分支、音频舞台（audioScroll/audioStage/…）、
 * `LiveAudioService.kt`、Manifest 里那条 `<service>` 全部删除（逐个符号见交付报告）。
 * ★**点播的 `PlaybackService` 与 `comm/delegate/player/` 整个目录一个字节都没动**。
 * ★`live_background_play`（「设置 → 直播设置 → 后台继续直播」）这个键保留在设置页；
 *   它在第五~十一批之间本页**没有消费端**，**第十二批已经重新接回来**（退后台只关视频轨、
 *   声音继续，见 [backgroundPlayEnabled] / [backgroundAudioOnly]）。
 *   ★那一轮是"**全自动、零按钮**"的：底栏那颗「听音频」按钮**没有**复活（用户明确删过它），
 *   前台服务 `LiveAudioService` 也没有重建（理由与代价见第十二批那一段）。
 *
 * ## 第三批（用户实测反馈驱动 —— 竖屏版式）
 * 1. **手势气泡不再住在 Dialog 窗口里**，而是挂到播放页自己的视图树（[hudLayer] + [applyHudGeometry]）：
 *    竖屏下"亮度气泡跑到屏幕外、音量气泡不贴边"的根因是**两套坐标系的混用**
 *    （页面的屏幕坐标 vs 系统给悬浮窗分配的父框），完整证据与修法见 [applyHudGeometry] 的 KDoc；
 * 2. **竖屏改成"上面视频、下面弹幕列表"**（用户给的第一张参考图 = B 站官方竖屏直播页）：
 *    视频压成**一条带**（宽 ÷ 视频比例，封顶 [PORTRAIT_VIDEO_MAX_HEIGHT_FRACTION] 页高），
 *    横屏一个字不改（仍是整屏视频 + 沉浸式）；★第四批把这条带的**顶边**从"贴顶"改成
 *    "顶栏之下"（[videoBandTopPx]），理由见下面第四批第 3 条；
 * 3. **把留给列表的那块地方交给弹幕宿主**：
 *    · 版式上留出它 —— [measurePortraitStage] 量出"视频区底边 → 底栏顶边"这块矩形；
 *    · 交给宿主 —— [danmakuListSlot]（一块 INVISIBLE 的占位 View，矩形就是它）+
 *      [bindPortraitListArea]（宿主 `bindPortraitListArea(slot = …, bottomBound = …)`）；
 *    · 数字版 —— [LivePortraitStage.portraitDanmakuListBounds]（同一块地方的只读镜像）。
 *    用户的三条要求因此分别落地：**固定显示**（宿主竖屏常驻，入口胶囊早已删除）、
 *    **不挡底栏**（矩形底边 = 底栏顶边 + 宿主再夹一道）、**不要切换按钮**（宿主侧已无胶囊）。
 *
 * ## 第四批（用户实测反馈驱动 —— 底栏字号 / 听音频居中 / 竖屏抬头 / 另外四条）
 * 1. **PiP 回来底栏字号变小**（用户："我 PIP 返回软件它就变小了，其他字体好像是正常的"）
 *    → 当时定位到的根因是**底栏分行与版式按 `Configuration.orientation` 推导**：进 PiP 时系统会带着
 *    "小窗尺寸"的配置回调一次 [onConfigurationChanged]，而 16:9 小窗在竖屏手机上的方向就是
 *    LANDSCAPE → 底栏被按**横屏规则**重排成"一行 10 颗"，每格宽度只剩竖屏的一半，
 *    `autosize(9~14sp)` 于是把文案压到下限（部分还带省略号）；退出 PiP 的那条路
 *    （[onPictureInPictureModeChanged]）**从来不重建底栏**，字号就永久留在小窗时代。
 *    这一批的修法：分行/版式**只认真实布局尺寸**（[isPageLandscape]，与点播
 *    `PlayerController.hostIsLandscape()` 同一条规则"尺寸是方向的最终真源"，也与宿主
 *    `LiveDanmakuOverlayHost` 的 `portrait = h > w` 对齐），并且**尺寸一变就重排**
 *    （[installPageLayoutWatchers] → [syncPageLayoutToRealSize]），字号因此只由"格子宽度"决定，
 *    与首次进入逐像素一致。完整证据链与 AOSP 依据见报告《直播优化-播放页第三批-说明.md》§1。
 *    ★★**用户复测仍然"变小了"** —— 真正的病根在**平台 autosize**（见下面第五批第 3 条），
 *    这一批的"字号归一 + 交给 autosize"其实从来没生效过（③ 是空的）。
 * 2. **听音频舞台整体垂直居中**（用户："往居中一点，不然它全部顶上去了"）：
 *    ★该舞台与整个听音频模式已在第五批删除，这里只留记录。
 * 3. **竖屏视频带下移到「状态栏 + 顶栏」之下**（用户："太顶了……应该放在那个顶栏和状态栏下面"）：
 *    顶边 = [videoBandTopPx]（顶栏底边，顶栏那份 insets 里已经含状态栏内边距）；
 *    同时**底边永远等于列表槽 [danmakuListSlot] 的顶边**（[measurePortraitStage] 的
 *    `listTop = videoContainer.bottom` + [installPageLayoutWatchers] 里那两只监听器），
 *    中间不留黑缝。底栏是浮层、4 秒自动隐藏，但**视频始终在它下面**（用顶栏最后一次布局的位置，
 *    与底栏"隐藏后位置不变"是同一套依据）。
 * 4. **底栏删掉「暂停/播放」那一颗**（用户："第一个暂停和播放那个按钮给取消掉吧，我需要腾空间"）：
 *    暂停/播放入口一个没少 —— ① **双击画面**（[TapCatcher]，受「设置 → 直播设置 → 双击暂停」
 *    `live_double_tap_pause` 管、默认开）；② PiP 小窗上的动作按钮（[buildPipActions]）。
 *    唯一要留意的边界写在报告"未做/风险"里（用户把「双击暂停」关掉时，画面模式就只剩 PiP 这一条路）。
 * 5. **发弹幕成功即关输入弹窗**（用户："发送完成应该把那个提示框给马上隐藏了"）：
 *    当时那个发弹幕弹窗的 `submit()` 里成功分支补一次 `dismiss()`；失败仍然留着让用户改文本重发。
 *    ★第七批：那个弹窗已整块删除（改成常驻输入条），这一条只作为历史记录保留。
 * 6. **清晰度/线路弹窗里的文字居中**（用户："里面的文字没有居中，它靠边上去了"）：
 *    [LiveListDialog.show] 的行文案与副标题改成居中（弹窗标题仍是常规 `MaterialAlertDialog` 观感）。
 * 7. **底栏「重试」→ 第一个按钮「刷新」**（用户："放到第一个按钮去……是否可以叫符合名字一点的刷新？
 *    它的功能不要乱改"）：[orderedBottomButtons] 把它排到最前，文案改「刷新」，
 *    实现仍然是 [retryPlayback]（重新取流/追到最新直播进度，一行未改）。
 *
 * ## 第五批（本轮，用户明确要求"做减法"）
 * 1. **删掉底栏「UP主」按钮与它带来的整条链路**：按钮、`openUpSpace` / `navigateToUpSpace` /
 *    `openSpaceOverlay` / `closeSpaceOverlay` / `resolveAnchorUid`、`MainActivityRef`、
 *    `UpSpace` 那几个字段与常量、`cn…compose.pages.user.UserSpaceOverlayHost`（文件已删）；
 *    `MainActivity.basePlayerDelegate` 还原成 `private`。
 *    ★点它**必崩**（线上异常 `androidx.startup.StartupException: Binding AppCompatActivity must
 *    override an existing binding.` —— 浮层里 `subDI` 的 override 校验），删掉就是最划算的修法。
 *    ★`comm.live.LiveSpaceLauncher` 与它在 `ComposeFragment` 里的注册**故意留着**（无人调用的兜底，
 *    删它会牵动 compose 侧，风险更大）。
 * 2. **删掉「听音频」功能**（按钮 / 状态 / 全部分支 / 音频舞台 / `LiveAudioService` / Manifest 条目）。
 * 3. **PiP 期间顶栏与底栏一律不可见**（用户："PIP 模式下，我想让它隐藏那个底部按钮，还有顶部的
 *    状态栏各种信息按钮"）：收口成一个门控 [controlsAllowed]（= 不在 PiP 且没有"即将进入 PiP"），
 *    并在**进小窗的第一步**（[enterPipMode] / [onUserLeaveHint]）就把控制条收掉、撤掉自动隐藏计时，
 *    这样进小窗那 1~2 秒也不会闪出来；退出小窗再恢复（[onPictureInPictureModeChanged]）。
 *    ★病根是 [onConfigurationChanged] 里的 `showControlsTemporarily()`：进小窗时系统会派发
 *    一次小窗尺寸的配置变更，它把关掉的控制条又打开了（同时也让按钮按小窗宽度被测量/缩字）。
 * 4. **底栏字号改成确定性写法**（用户："返回直播间的话，它那些按钮的字体又变小了"）：
 *    不再用平台 `autosize`，改成**固定档位 [BOTTOM_BUTTON_TEXT_SP_STEPS]（14/13/11/9sp）+
 *    按真实格宽选一档**，并且**底栏每次布局都按它自己的真实宽度重算一遍**
 *    （[applyBottomBarTextSizes]，挂在 [bottomBar] 的布局监听上）——
 *    于是"字号 = 真实格宽的函数"，与"哪条回调先来、PiP 有没有重建过底栏"彻底无关。
 *    完整根因（AOSP 源码级）见报告《直播优化-删UP主与听音频-说明.md》§4。
 *
 * ## 第六批（本轮，两处交互调整）
 * 1. **返回键分级**（用户："直播间全屏的时候，我按一下返回，应该是退出全屏的状态，返回到竖屏。
 *    如果是竖屏的状态返回的，应该是退出直播间。"）：系统返回键与顶栏返回图标**共用** [handleBack] ——
 *    横屏（= 全屏）→ [exitFullscreenToPortrait]（切回竖屏、**留在直播间**）；竖屏 → [exitPage]。
 *    ★与「自动旋转」不打架的关键：切竖屏时同时置 [orientationPinnedByUser]（会话级），
 *    否则 [applyAutoRotatePolicy] 会在紧接着的 [onConfigurationChanged] 里把方向断言回
 *    `FULL_SENSOR` → 手机还横着 → 立刻又被转回横屏。设置键 `live_auto_rotate` 一个字节都不写。
 *    ★★本轮修正（用户实测报的 bug）：**自动旋转=开时这个钉住是"一次性"的** ——
 *    设备下一次真的被转动就交还给自动旋转（见 [pinOrientationByUser] / [releaseOneShotOrientationHold]），
 *    否则"点一次旋转 / 按一次返回"就等于把自动旋转永久关掉了（用户原话："点旋转按钮之后，你旋转
 *    方向它是不跟随的，除非你手动按那个旋转按钮才能切换方向"）。自动旋转=关时才是永久钉住。
 * 2. **顶栏返回只留图标**（用户："去掉那个顶栏的返回，只保留一个图标，就是复用我们视频播放器的
 *    那个返回图标，也就是我们其他页底栏的那个返回图标。"）：原来的「← 返回」文字按钮
 *    （`actionButton` + 主题色药丸底）换成 24dp 的
 *    `R.drawable.ic_arrow_back_white_24dp` —— 点播播放器全屏那颗返回用的就是它
 *    （`widget/player/DanmakuVideoPlayer.kt:1094`），与「其他页」AppBar 的 `ic_back_24dp`
 *    是同一条 Material arrow_back 路径（[buildUi] 顶栏那一段有完整的选型理由）。
 *
 * ## 第七批（本轮，用户拍板的"底栏重构"）
 * 用户原话与逐条落点：
 * 1. **发弹幕 = 常驻输入条**（"发弹幕变常驻输入条……记得用安卓的 API 把它那个改成发送，
 *    我们就省了一个发送的按钮了，也省出那个弹窗按钮"）：
 *    底栏最上面一条 [danmakuInput]（hint「发个弹幕…」），`imeOptions = IME_ACTION_SEND` ——
 *    软键盘回车键就是「发送」，**没有弹窗、没有发送按钮**；发送中禁用 + 转圈
 *    （[danmakuSendProgress]），成功清空输入并收起键盘，失败**保留文本**并把
 *    `LiveDanmakuClient.lastSendError`（服务端原文）同时给到输入条下方的 [danmakuInputError]
 *    与 toast。`showSendDanmakuDialog()` 与 `sendDanmakuDialog` 字段**整块删除**。
 * 2. **「旋转」保留在底栏**（"那个旋转一定要放到底栏来啊，它是经常用到的"）→ [rotateButton] 不动。
 * 3. **「刷新」改自动**（"能省则省"）：触发时机 = 回到前台（[onResume]，靠 [onStop] 留下的
 *    [autoRetryOnNextResume] 标记区分"真的退过后台"）、以及看门狗 [checkLiveHealthOnce] 检出的
 *    "停滞 / 落后直播边缘 / 画面不再出新帧"；**行为与手动刷新逐字一致**（[retryPlayback] →
 *    `delegate.retry()`）。
 *    ★★第十批：**"从 PiP 回全屏"不再是无条件触发点** —— 退出小窗只把"要不要追"交给看门狗
 *    （它才是"只对真故障生效"的那条闸门），理由见 [onPictureInPictureModeChanged] 里那段注释。
 *    手动入口没丢：**「画质·线路」弹窗里的「重新取流」**（不占底栏）。
 *    防抖与预算见 [autoRetryLiveStream]（20s 最小间隔 + 5 分钟最多 3 次 + 每次都会告知原因）。
 * 4. **「画质」+「线路」合并成一颗**（两者本来就是"选用哪条流"）：底栏一颗
 *    [qualityButton]（文案「画质·原画」）→ [showStreamDialog]：一个弹窗、两段（TabLayout，
 *    清晰度 / 线路），既有行为一条不少（当前档打勾 + 主题色高亮、已请求不可用标注、线路可点选），
 *    内容高度仍按 [dialogContentMaxHeightPx] 的 **62% 真机屏幕**封顶并可滚，**没有「取消」按钮**。
 * 5. **「画中画」挪到顶栏**（★已回退，且死代码已删）：第七批曾把画中画做成**顶栏图标**
 *    （与顶栏返回同一套风格：白色 24dp 图标、[BACK_ICON_BOX_DP]dp 点击区、borderless ripple）。
 *    用户随后改主意 ——"为什么要把画中画移到顶栏去？简直就是没有必要" —— 画中画**留在底栏**
 *    （[orderedBottomButtons] / [pipButton]）。那颗**从未挂载**的顶栏图标（字段、创建代码、
 *    被注释掉的 `topBar.addView`、主题刷新那一行）已作为死代码**整体删除**：
 *    顶栏现在只有 **返回 + 标题 + 状态文字**（状态文字仅异常时显示，见 [renderStatus]）。
 * 6. 结果：竖屏底栏 = 常驻输入条 + 3 颗（旋转 / 画质·线路 / 弹幕开）；横屏输入条与 3 颗**同一行**；
 *    **没有「更多」按钮**（用户明确反对多一步）。
 *    ★第八批把这一条也统一了（"常驻"与"竖屏两行"都作废，见下一节）。
 *
 * ## 第八批（用户装完 vc168 后的 4 条实测反馈 —— 本轮）
 * 只改了本文件，四条逐条落点：
 * 1. **输入框与四颗按钮同一行**（"还有那个输入框，你干嘛要单独一行呢？把它和那个几个按钮放一起呢？"）：
 *    竖屏/横屏统一成 `bottomBar = [ row(danmakuInputRow(weight=1) + bottomButtons), danmakuInputError ]`
 *    （[rebuildBottomBar]）；输入框吃剩下的宽度。
 * 2. **输入框跟顶栏/底栏一起"显示一两秒就隐藏"**（"为什么不和那几个按钮一起显示一两秒呢？
 *    为什么要一直站在那？"）：[applyControlsVisibility] 里输入条与四颗按钮同一套显隐
 *    （`INVISIBLE` 保住占位）；"正在输入"（[isDanmakuInputActive]）一档强制可见，
 *    PiP 一档 `GONE`。**第七批那句"输入条常驻"自本轮起作废**（下面历史注释里凡写"常驻输入条"的，
 *    "常驻"二字都按本轮作废）。
 * 3. **四颗按钮同高、同宽（等分）、同内边距，文字不换行不省略**
 *    （"那几颗按钮奇奇怪怪"）：[applyBottomBarTextSizes] 改成"先给输入框让出保底宽
 *    （[DANMAKU_INPUT_MIN_WIDTH_DP]）→ 按**一行里最宽的文案**统一挑一档字号 → 四颗同一个格宽"，
 *    容器宽度写死在 [bottomButtons] 上、里面四颗是 `weight=1` 的等分格；窄屏**缩字号**、不横滑。
 *    文案也按要求缩短：[qualityButton]「画质·原画」→「画质」（当前值仍在顶栏状态行与弹窗里），
 *    三态那颗「弹幕重连」→「重连」。
 *    ★★第十四批：底栏加了第 5 颗「设置」之后，"四颗"这个数字全面改成**五颗**（下文老段落里
 *    凡写"四颗/4 颗"的，按本条读作"当时是四颗"）；上面这套算法**一行都没改** ——
 *    颗数进 [applyBottomBarTextSizes] 的预算公式（`/ count`）与 `weight=1` 的等分，自动跟着分。
 *    360dp 屏上的完整宽度账（5 颗 + 输入框）见交付报告《直播优化-底栏设置按钮-说明.md》§2。
 * 4. **PiP 小窗铺满、无黑边**（"为什么我 pip 怎么又是那种不完全显示占满 pip 的呢？"）：
 *    根因 = 小窗里**没人重排过视频版式**，竖屏那条 62% 的带子与全屏顶栏留下的 topMargin 被原样
 *    带进小窗（[syncPageLayoutToRealSize] 在 PiP 里是有意 `return` 的，而 [applyVideoStageLayout]
 *    只从它那里被调到）。修法：[applyVideoStageLayout] 里 PiP 一律走"铺满"那一支；
 *    进小窗的那一刻（[onPictureInPictureModeChanged]）立刻排一次；
 *    并在 [measurePortraitStage]（画面矩形的唯一收敛点）末尾按指纹**重新下发 PiP 参数**
 *    （[syncPipParamsToVideoGeometry]），不再只在进入时算一次。完整证据链见 [applyVideoStageLayout]。
 *
 * ### 键盘避让（这一批最容易踩坑的地方，写清楚免得后人再猜）
 * 本页 `targetSdk = 36`，而 Android 15 起**目标 35+ 的 App 被强制 edge-to-edge**
 * （`windowSoftInputMode="adjustResize"` 不再让系统缩窗口），所以"键盘弹出时窗口自己变矮"
 * 这条老路在用户的机器（Android 15）上**根本不成立**。落地成两件事一起做：
 * ① `window.setSoftInputMode(ADJUST_RESIZE | STATE_UNCHANGED)`（[onCreate]）—— 老系统上仍然是
 *    窗口缩矮这条路（与 `AndroidManifest.xml:50` 给 MainActivity 写的是同一个值）；
 * ② **自己按 ime insets 抬底栏**（[refreshBottomBarInsets]）：把键盘高度加到底栏的**底部内边距**上，
 *    并用"没有键盘时页面高度 - 现在的页面高度"扣掉**窗口已经让出的那部分**，
 *    保证"窗口缩了"和"窗口没缩"两种世界都**只抬一次**。
 * 底栏是 `Gravity.BOTTOM` 的 `WRAP_CONTENT` 浮层 → 抬高只会让它**向上长**，
 * 不可能被顶出屏幕；输入条在其中，永远不会被键盘盖住。
 * 弹幕列表区跟着缩短：底栏顶边上移 → [installPageLayoutWatchers] 里那只底栏监听器立刻重跑
 * [measurePortraitStage]，列表槽与宿主面板一起让位。
 *
 * ## 第九批（用户实测三条："横屏开着键盘转回竖屏"这条路上的键盘/转屏组合）
 * 用户原话与现象（三张真机截图逐像素核过，证据见交付报告）：
 * 1. **转回竖屏后弹幕区一大块黑色空白** —— 面板的矩形**冻在键盘动画的中间态**上（底边停在半空，
 *    转屏、收键盘都不再跟着走）；
 * 2. **键盘上方有半透明残影** —— 面板底边停在**键盘抬起之前**的底栏位置（更靠下），
 *    它那层 90% 不透明底色正好盖在已经抬上去的底栏上，把按钮/输入条压成"半透明残影"；
 *    ★同一个根因的另一半：**键盘开着转屏**到横屏时基准（[pageHeightWithoutIme]）还是竖屏的页高，
 *    算出来"窗口已经让掉 1536" ⇒ 横屏 + 键盘干脆不抬底栏（"不是完全显示的顶上去"）。
 * 3. **竖屏弹幕区离底栏一大块空白** —— 面板底边被"列表槽"这份**过期镜像**按住不动
 *    （槽的底边是"上次量到的底栏顶边"，键盘一抬它就过期了）。
 *
 * 三条的公共结论：**弹幕列表面板的底边必须实时等于底栏的顶边**，而"底栏顶边"会因为
 * ① 我们自己按 IME insets 改的底部内边距、② 转屏/窗口为键盘让位 —— 而位移，
 * 这两种位移**都不保证**在宿主的自动重量链上被消化掉。所以本轮：
 * · **基准绑定宽度**（[pageHeightBaselineWidthPx]）：转屏后作废重取，横屏 + 键盘也照抬；
 * · **底边只认活锚点**（宿主 `computeDockedRect`：不再与槽底边取 min）；
 * · **几何落定后再推一次**（[scheduleLiveListGeometrySettle] → [settleLiveListGeometry]）：
 *   每次几何变化都重置计时，最后一帧之后必然还有一次"重量槽 + 主动通知宿主"
 *   （宿主新入口 `notifyPortraitListGeometryChanged`，同步、不依赖重组时机）。
 *
 * ## 第十二批（本轮：退后台"只出声、不解码视频"，全自动、无按钮）
 * 用户原话（语音）："我说的后台音频继续播放，可以看一下我的其他的普通视频，听音频的。"
 * 落成一句话：**照点播那条已有的"只听声音"形态，把直播页的退后台行为补上"只出声"这一档**。
 *
 *  1. **门控 = 已有设置键 `live_background_play`**（「后台继续直播」，**默认关**）：
 *     · 关（默认）→ [onStop] 走原来那句 `delegate?.pause()`，**逐字不变**；
 *     · 开 + 离开前在播 + 不在 PiP → [onStop] 调 `delegate.setAudioOnly(true)`：**关视频轨**
 *       （轨道选择器不再选视频轨 → 视频渲染器 disabled → MediaCodec 释放）⇒ 声音继续、
 *       **视频一个字节都不再解码**（真省算力，不是拿黑遮罩假装）。
 *  2. **回前台只做一件事**：[onResume] 调 `delegate.setAudioOnly(false)` 把视频轨放回来 ——
 *     Surface 从头到尾没摘过、播放器没 release ⇒ **不重新取流**、不换源、不黑屏；
 *     真的断了（playerError / 不在 READY|BUFFERING）才把 [resumePlayIntent] 留给原来的
 *     "回到前台按需追到最新"（恢复能力与改动前一致）。按用户要求，看门狗仍是唯一的自动追流闸门。
 *  3. **不进小窗、不加按钮、不动 PiP**：进 PiP 不触发 [onStop]（小窗照旧渲染视频）；
 *     底栏当时仍是 4 颗（弹幕 / 画质 / 画中画 / 旋转），`live_background_play` 没有新增任何 UI。
 *     ★第十四批起底栏是 **5 颗**（中间多了「设置」，用户点名要的），本条其余结论不受影响。
 *  4. **保活**：本批**没有**重建第五批删掉的 `LiveAudioService`（前台服务 + 媒体通知）。
 *     依据与代价写在交付报告《直播优化-后台只出声-说明.md》§4：点播的保活壳是
 *     `service/PlaybackService`（MediaSessionService），而它属于"点播那条链"，本批一个字节没动；
 *     直播这条路的后台形态默认由小窗承担（`live_pip_on_background` 默认开），
 *     所以只在"真 onStop（熄屏 / 关掉小窗设置后切走 / 小窗被收起）"时才需要它。
 */
class LivePlayerActivity : AppCompatActivity(), LivePortraitStage {

    companion object {
        /**
         * Intent extra：房间号。值可以是短号（如 "1"）或真实号（如 "5440"）——
         * 两个都行，因为进来第一件事就是 `room_init` 换算成真实号（方案 §2.1）。
         *
         * ★注意：这个字符串在 `CoverViewModel`（bilimiao-cover 模块）里是**字面量重复**的 ——
         *   cover 模块不能反向 import app 模块的类（依赖方向是 app → cover），只能靠约定对齐。
         */
        const val EXTRA_ROOM_ID = "roomId"

        /**
         * ★内部 extra：本次启动是"同一房间**复用已有实例**"（见 [onCreate] 的同房间复用分支）。
         *
         * 为什么要它：复用是"新实例把自己 finish 掉、把老实例 REORDER_TO_FRONT 拉到前台"，
         * 而 REORDER_TO_FRONT 万一没命中（老实例在别的任务里、已被系统收走……）就**会新建一个实例**，
         * 新实例又会走一遍复用判断 → 无休止地新建/退出。带上这个标记，第二次进来的实例就知道
         * "复用已经试过一次了"，直接走正常创建流程，从根上掐掉这条自激回路。
         */
        private const val EXTRA_REUSE_ATTEMPTED = "liveReuseAttempted"

        /**
         * 直播 PiP 动作的**专用 action**（点小窗里的播放/暂停按钮时发回来）。
         *
         * ★为什么不复用点播的 `media_control`（诊断报告 §0「附带隐患」+ §5 第 2 条）：
         *   ① 那个 action 是**隐式广播**且 PendingIntent 身份只认（包名, requestCode, filterEquals）
         *      —— 不比 extras，于是全 App 所有 PiP 实例共享同一批 PI token，
         *      两个播放器同时在小窗里时**一次点击会同时操作两个**（用户原话"它和我的普通视频在抢那个接口"）；
         *   ② 任何 App 都能发那个 action（`RECEIVER_EXPORTED`）。
         *   所以直播用**自己的 action + 自带包名的定向 Intent + 自留的 requestCode**，
         *   与点播的动作通道彻底分开：谁的小窗被点，就只有谁的播放器动。
         *
         * ★★定向手段是 `Intent(action).setPackage(包名)`，**绝不能**写成
         *   `Intent(this, LivePipActionReceiver::class.java)`（后者是本轮修掉的病根：
         *   带 component 的广播不会被投给动态注册的接收器）—— 完整证据链见 [buildPipActions]。
         */
        const val ACTION_LIVE_PIP_CONTROL = "com.a10miaomiao.bilimiao.action.LIVE_PIP_CONTROL"

        /** PiP 动作 intent 里的动作类型 extra；取值只有下面两个 */
        const val EXTRA_LIVE_PIP_CONTROL = "livePipControl"

        /** PiP 动作：播放（图标/文案都是"播放"时点它） */
        private const val LIVE_PIP_CONTROL_PLAY = 1

        /** PiP 动作：暂停 */
        private const val LIVE_PIP_CONTROL_PAUSE = 2

        /**
         * 直播 PiP 播放/暂停动作的 requestCode（**直播专用**，与点播的 1..4 不撞车）。
         * `PendingIntent` 的身份 =（包名, requestCode, Intent 的 filterEquals），
         * 所以"自己的 action + 自己的 requestCode"两件一起做，才不会再和点播共享 token。
         */
        private const val REQUEST_LIVE_PIP_TOGGLE = 0x11B0

        /**
         * 未开播时的轮询间隔。★方案 §3.5/§6.1：30~60s，别更密 ——
         *  B 站风控很敏感（实测连"分区直播列表"都会回 -352）。
         */
        private const val OFFLINE_POLL_INTERVAL_MS = 45_000L

        /** 控制条自动隐藏延时 */
        private const val CONTROLS_AUTO_HIDE_MS = 4_000L

        /**
         * 手势气泡（音量 / 亮度）的淡入淡出时长（ms）。
         *
         * 上一版气泡是 **Dialog 窗口**，淡入淡出由 GSY 的 `video_popup_toast_anim`
         * （`@android:anim/fade_in` / `fade_out`：decelerate 插值、两三百毫秒）提供；
         * 本轮气泡改成页内 View（见 [applyHudGeometry] 的根因），窗口动画随之没有了，
         * 这里用同一个"减速淡入淡出"补回来（180ms 取平台 fade 动画的量级），观感不变。
         */
        private const val HUD_FADE_MS = 180L

        /**
         * 手势气泡贴边时**额外**留的边距（dp）。★取 0 是有意的：
         * GSY 那两份布局自带留白（音量胶囊在 90dp 盒子里居中 ≈ 25dp 起；亮度图标 `marginEnd=15dp`），
         * 而那正是用户认可的横屏观感（"距离左边一点距离，右边也是距离一点距离"）。
         * 再加一层边距会让横屏跟着变 —— 本轮要求"横屏不许回归"，所以统一 0：
         * 横竖屏用的是同一行代码、同一个值，横屏与已验证的那版逐像素一致。
         */
        private const val HUD_EDGE_MARGIN_DP = 0

        /**
         * 竖屏视频带的**高度上限**（占页面高度的比例）。
         *
         * 竖屏版式 = "上面视频、下面弹幕列表"，视频高度 = `页宽 ÷ 视频比例`：
         * · 16:9 直播（绝大多数）→ 1264px 宽的机器上只有约 711px（25% 页高），列表拿到 ~64% 屏高 ✓；
         * · **竖屏主播（9:16）**→ 按比例算出来是 2247px（80% 页高），列表就只剩一条缝了 ✗。
         * 所以封顶 62%：留 38% 页高给列表与底栏（1264×2800 的机器上 ≈ 1064px ≈ 380dp 列表区）。
         * ★视频比这条带子高时由 [AspectRatioFrameLayout] 居中放（左右留黑边）——不裁切、不变形。
         */
        private const val PORTRAIT_VIDEO_MAX_HEIGHT_FRACTION = 0.62f

        /**
         * 竖屏列表区的**最小可用高度**（dp）。低于它就认为"这块版式没排出来"
         * （分屏/折叠屏/极矮窗口）：[portraitDanmakuListBounds] 返回 null、
         * [danmakuListSlot] 量成 0 高（见 [applyListSlot]）→ 宿主自己那道最小高度门限会把面板收起，
         * 滚动弹幕照旧。宁可退版式，也不要给宿主一块放不下东西的矩形。
         */
        private const val PORTRAIT_LIST_MIN_HEIGHT_DP = 96

        /**
         * ★第七批：原来这里还有一个 `PORTRAIT_BUTTONS_PER_ROW = 5`（竖屏每行几颗、两行 5+2 的那套
         * 自适应排版）。底栏只剩 4 颗（弹幕 / 画质 / 画中画 / 旋转）+ 一条输入条之后，
         * "分行"这件事不再存在：**竖屏与横屏是同一套一行版式**（★第八批统一，见 [rebuildBottomBar]），
         * 所以那个常量连同 [rebuildBottomBar] 里的 `chunked(perRow)` 一起删了。
         * [BOTTOM_ROW_EQUAL_CELL_MIN_COUNT] 保留：它仍是"一行里按钮多了要不要均分"的规则，
         * 而现在 5 颗**恒走"等宽格"那一支**（用户第 3 条："同高、同宽（等分）、同内边距"；
         * ★第十四批加第 5 颗「设置」之后这个数更大，规则照样恒成立）。
         */

        /**
         * 底栏按钮的**字号档位**（sp，从大到小）。★第五批：**不再用平台 autosize**，
         * 改成"固定档位 + 按真实格宽选一档"的确定性写法（见 [applyDeterministicTextSize]）。
         *
         * 为什么把 autosize 换掉（AOSP 源码级证据见交付报告 §4）：autosize 选出来的字号是
         * "**上一次测量**时的 `getMeasuredWidth()`"的函数，而且 `setAutoSizeTextTypeUniformWithConfiguration`
         * 会**当场**按那个旧宽度算一次并写进去（`setTextSizeInternal(..., false)`，不请求重新布局）；
         * 更致命的是 `TextView.setTextSize(int,float)` 在 autosize 打开时**直接 return**
         * （`if (!isAutoSizeEnabled())`），所以"先把字号归一到 14sp"那一步从来没有生效过 ——
         * PiP 小窗时代测量出来的小字号因此可能一直留在按钮上。
         * 现在：字号 = f(按钮真实格宽)，由 [applyBottomBarTextSizes] 在**底栏每次布局**时重算，
         * 与"哪条回调先来、底栏有没有被重建"完全无关。
         */
        private val BOTTOM_BUTTON_TEXT_SP_STEPS = intArrayOf(14, 13, 11, 9)

        /**
         * 一行里有**几个以上**按钮才"均分整行宽度"（否则按内容宽度居中）。
         *
         * 4 是分界点：4 个及以上均分（一行铺满），3 个及以下按内容宽度居中 ——
         * 两个按钮各占半屏会像两块巨型色块。
         * ★第七批之后底栏固定 4 颗（弹幕 / 画质 / 画中画 / 旋转），**恒成立** ——
         *   所以格子一律等宽（用户第 3 条），字号也由"一行里最宽的文案"统一挑一档（同高）。
         *   ★第十四批加第 5 颗「设置」后是 **5 颗**，仍然 ≥ 这个分界点 → 规则一字不改。
         */
        private const val BOTTOM_ROW_EQUAL_CELL_MIN_COUNT = 4

        /**
         * ★第八批：底栏里**输入框的保底宽度**（dp）—— "输入框不被压成一条缝"的硬门限。
         *
         * 输入条与底栏那几颗按钮现在**同一行**（用户："把它和那个几个按钮放一起"；
         * ★第十四批起是 5 颗：弹幕 / 画质 / 设置 / 画中画 / 旋转），
         * 而按钮的格宽由 [applyBottomBarTextSizes] 按"最宽的那条文案"算出来 ——
         * 算之前先把这 [DANMAKU_INPUT_MIN_WIDTH_DP]dp 从整行里**扣掉**：
         * 按钮区最多只能用到"整行 - 底栏内边距 - 输入框保底宽"，
         * 不够就先缩字号（[BOTTOM_BUTTON_TEXT_SP_STEPS] 从大到小），绝不横滑、也绝不把输入框挤没。
         * ★为什么是 120dp：输入框自己还有 12dp×2 的左右内边距 + 右侧 18dp+6dp 的转圈，
         *   扣完净剩 ≈ 100dp ≈ 7 个汉字，hint「发个弹幕…」与短弹幕都看得见。
         * ★★第十四批（加第 5 颗「设置」之后）**如实记一笔**：颗数 4 → 5，每一格的预算随之变小，
         *   同一台机器上"最宽的 3 字文案（「弹幕开」/「画中画」）"可能**掉一档字号**：
         *   360~362dp 屏（本项目实测机 1264px ÷ 3.5 = 361dp）落在 **9sp**、393dp 落在 11sp、
         *   412dp 及以上仍是 14sp（完整宽度账见交付报告《直播优化-底栏设置按钮-说明.md》§2）。
         *   想把它拉回来只有三条路，**都不动算法**：① 文案再缩（3 字 → 2 字）；
         *   ② 本常量 120 → ≤116（360dp 上可回到 11sp）；③ 调 [BOTTOM_BUTTON_PADDING_H_DP]。
         *   三条**本轮都没做**：用户没要求改文案/间距，硬性要求是"只许改文案表与顺序、
         *   不要另起一套测量逻辑"，而字号档位本身就是这套算法的既定行为（[BOTTOM_BUTTON_TEXT_SP_STEPS]）。
         */
        private const val DANMAKU_INPUT_MIN_WIDTH_DP = 120

        /**
         * 底栏内边距（dp，四边相同）与按钮的左右外边距/左右内边距（dp）。
         *
         * ★这三个数**同时**被 [rebuildBottomBar]（排版）、[refreshBottomBarInsets]（输入法/导航栏内边距）
         *   和 [applyBottomBarTextSizes]（算格宽）使用，所以必须只有一处定义：
         *   格宽算错一点，字号档位就会选错一档。
         */
        private const val BOTTOM_BAR_PADDING_DP = 4
        private const val BOTTOM_BUTTON_MARGIN_DP = 3
        private const val BOTTOM_BUTTON_PADDING_H_DP = 4

        /**
         * 顶栏**图标按钮**（现在只有返回那一颗）的点击区边长（dp）与内边距（dp）。
         *
         * 40dp = 与点播播放器那颗返回（`layout_danmaku_palyer.xml` 的 `@id/back`，40×40dp）
         * 完全一致；图标本身 24dp，四周 8dp 内边距让它居中 —— 于是"图标 24 / 点击区 40"
         * 同时成立（点击区比图标大一圈，手指点得中）。
         * ★第七批那颗「画中画」顶栏图标（曾用同一组数）已整体删除（见 [buildUi] 顶栏那一段），
         *   这两个数现在只服务 [backButton]。
         * ★想调顶栏高度就动这两个数：顶栏高 = 点击区 + 顶栏自身 6dp×2 内边距，
         *   而竖屏视频带的顶边由 [videoBandTopPx]（顶栏底边）说话，会跟着自动重排。
         */
        private const val BACK_ICON_BOX_DP = 40
        private const val BACK_ICON_PADDING_DP = 8

        /**
         * 弹幕输入条那条**失败提示**（[danmakuInputError]）自动收起的时长（ms）。
         *
         * 为什么要自动收：提示行会让底栏长高一行，而底栏顶边是弹幕列表区的底边
         * （[measurePortraitStage]）—— 留着不收，列表就一直矮一截。6 秒够看清服务端原文，
         * 又不会长期占地方；期间再点一次发送会重新计时（见 [showDanmakuInputError]）。
         */
        private const val DANMAKU_INPUT_ERROR_HIDE_MS = 6_000L

        /**
         * 输入框的 hint（正常房间）。
         * ★抽成常量：与"该房间关闭了弹幕"那一句（[DANMAKU_CLOSED_HINT]）在同一处定义 ——
         *   [applyDanmakuInputClosedUi] 会在两态之间来回换，两句话必须只有一个出处。
         */
        private const val DANMAKU_INPUT_HINT = "发个弹幕…"

        /**
         * **该直播间关闭了弹幕**时，输入框原位显示的那一行很轻的提示（用户允许的两种做法之一，
         * 见 [applyRoomDanmakuClosed] 里"为什么不留空"那段）。
         */
        private const val DANMAKU_CLOSED_HINT = "该直播间已关闭弹幕"

        /**
         * ★★第九批：底栏/键盘几何**落定之后**再推一次竖屏列表几何的延时（ms）。
         *
         * 用户实测（三条：转回竖屏一大块黑色空白 / 键盘上方半透明残影 / 弹幕列表离底栏一大块空白）
         * 的像素证据都指向同一件事：**注入的弹幕列表面板底边没有跟着底栏走**。
         * 底栏的位移有两个来源，两个都可能"动了但没人告诉列表面板"：
         * ① 我们按 IME insets 改它的**底部内边距**（[refreshBottomBarInsets]）；
         * ② 窗口自己变矮（老系统 `adjustResize`）或转屏换页高。
         * 这两种位移都发生在**布局过程中**（甚至一次动画里连发几十帧），当场去改注入面板的
         * layoutParams 会踩系统那句 `requestLayout() improperly called … during layout`；
         * 所以这里统一**延后一小段**再推：每次几何变化都重置这个计时，
         * 于是"最后一帧之后 48ms"必然还有一次按**最终几何**的重推（幂等，几何没变就一个字节不写）。
         *
         * 48ms ≈ 3 帧（60Hz）：既躲开布局中途，又远早于"用户能看出没对齐"的时间尺度。
         */
        private const val LIVE_LIST_SETTLE_MS = 48L

        /**
         * 「画质·线路」弹窗里那条 Tab 栏的**高度预留**（dp）。
         *
         * [dialogContentMaxHeightPx] 是按"整块内容区"算的 62% 屏幕高；这个弹窗的内容区 =
         * Tab 栏 + 列表，所以列表的上限要再扣掉 Tab 栏这一条（宁可扣多一点，
         * 也不要让"Tab + 列表 + 标题 + 按钮"整体超出屏幕 —— 那正是"按钮被顶出屏幕"的病根）。
         */
        private const val DIALOG_TAB_STRIP_DP = 52

        // ── 自动追流（★第七批：「刷新」按钮改自动）────────────────────────────
        /**
         * 两次**自动**追流之间至少隔这么久（ms）。
         *
         * 为什么必须有：触发源不止一个（回到前台 / 看门狗检出停滞、落后或画面不出帧），
         * 它们可能在同一秒里一起到达（例如"从小窗回到全屏"本身就会先走一次 resume 路径），
         * 没有这道闸门就会连发两次取流请求 —— B 站对 `/room/v1/Room/playUrl` 这类接口很敏感
         * （方案 §3.5：实测连"分区直播列表"都会回 -352）。
         */
        private const val AUTO_RETRY_MIN_INTERVAL_MS = 20_000L

        /** 自动追流的**预算窗口**（ms）与窗口内最多几次 —— 超了就停手并告知用户走手动入口 */
        private const val AUTO_RETRY_WINDOW_MS = 5 * 60_000L
        private const val AUTO_RETRY_MAX_IN_WINDOW = 3

        /** 看门狗巡检间隔（ms）：只读几个 player getter，几乎不耗电 */
        private const val LIVE_WATCHDOG_INTERVAL_MS = 5_000L

        /**
         * ★本轮：顶栏「在线人数」的刷新间隔（ms）—— **搭在看门狗那次巡检上**（[liveHealthJob]），
         * 不新开 Job、不新开定时器。
         *
         * 为什么是 45s（用户要求 30~60s）：
         * · 在线人数是"看个大概"的数字，秒级刷新没有任何意义，只会白白多打接口；
         * · [LiveAPI.roomInfo]（`room/v1/Room/get_info`）实测**免登录、无 WBI、裸请求即 code=0**，
         *   但 B 站对直播接口整体有风控（连"分区直播列表"都会回 -352），低频是必须的礼貌；
         * · 45s 与"未开播轮询"的 [OFFLINE_POLL_INTERVAL_MS] 同量级，两个数字一眼能对上。
         *
         * ★它与看门狗**同频不同拍**：看门狗每 [LIVE_WATCHDOG_INTERVAL_MS]（5s）醒一次（只读 getter，
         *   不联网），本项由 [ONLINE_REFRESH_INTERVAL_MS] 这个时间戳闸门压到 45s 才真发一次请求。
         */
        private const val ONLINE_REFRESH_INTERVAL_MS = 45_000L

        /** 连续缓冲超过它就认为"卡住了"（ms）。取 8s：正常换线/起播的缓冲不会这么长 */
        private const val STALL_RETRY_MS = 8_000L

        /**
         * ★★第十批：**画面不再出新帧**超过它就认为"画面卡住了"（ms），取 10s。
         *
         * 为什么还必须有这一条（第三条判据）：上面那条"停滞"看的是 `STATE_BUFFERING`，
         * 而"画面黑着/冻着但播放器自认为在播"（解码器不出帧、surface 尺寸重配卡住、
         * 新换的地址起不来帧）**不会**进 BUFFERING；`currentLiveOffset` 在 FLV 直播上又常常是
         * `C.TIME_UNSET`（本文件 [LIVE_LAG_RETRY_MS] 的 KDoc 自己写了这条），于是两种信号都看不见它。
         * 判据换成 media3 自己的解码计数 `videoDecoderCounters.renderedOutputBufferCount`
         * 有没有往前走 —— 它就是"渲染出了几帧"，与 UI/窗口无关。
         * ★取 10s（比 8s 的缓冲判据更宽）是为了避开"换清晰度/重取流之后等首帧"的正常窗口。
         */
        private const val FRAME_STALL_RETRY_MS = 10_000L

        /**
         * ★★第十批：退出小窗后多久之内**不往系统下发 PiP 参数**（ms），改成动画结束后补一次。
         *
         * 取值依据：系统"小窗 → 全屏"的窗口过渡（含各家 ROM 的无缝缩放）在 300~500ms 量级，
         * 取 600ms 留一点余量；只影响"下发时机"，不影响任何一份下发内容（见 [pipExitedAtMs]）。
         */
        private const val PIP_EXIT_SETTLE_MS = 600L

        /**
         * 离直播边缘超过它就认为"落后了"（ms）。
         *
         * 依据 delegate 的 live 配置：`targetOffsetMs = 3s`、`maxOffsetMs = 20s`，
         * 且允许 0.97~1.03 倍速自己追。media3 自己能追的时候不必插手，
         * 取 15s = "已经明显落后、且快追上 maxOffset 了"的那条线。
         * ★拿不到这个值时（`C.TIME_UNSET`，例如 FLV 直播没有可用的 live 窗口）**不判落后**，
         *   只靠上面那条"停滞"信号。
         */
        private const val LIVE_LAG_RETRY_MS = 15_000L

        /**
         * 输入条失败提示的**兜底颜色**（浅红）：主题里取不到 `colorError` 时用它。
         * 压在底栏的黑蒙层上，深浅色主题下都看得清（见 [errorTextColor]）。
         */
        private const val DEFAULT_ERROR_TEXT_COLOR = 0xFFFF8A80.toInt()

        /**
         * 亮度手势灵敏度：**与点播（GSY）完全一致 —— 划满 3 个屏高走完整条行程**。
         *
         * ★2026-09-26 用户实测"手势太灵敏，想下滑看状态栏，一滑就全黑"。
         *   病根：这里的系数原来是 `0.9`，即"一屏就滑掉 110% 行程"；而点播是
         *   `增量 / (屏高 × 3)` 累加 ⇒ 3 屏才走完全程。绝对位移与增量写法是线性等价的，
         *   **差的就是这个系数**（1/0.9 ≈ 1.11 屏 vs 3 屏，灵敏度差约 3.3 倍）。
         *   现在取 3f，与点播同一手感；音量那边（[handleDrag]）也同步改成 ×3。
         */
        private const val BRIGHTNESS_FULL_SWIPE_RATIO = 3f

        /**
         * ★第十三批：这里原来有一个 `VOLUME_WRITE_MIN_INTERVAL_MS = 16`（"同一帧最多写一次系统音量"）。
         * **它已经不需要了，别再搬回来**：
         * · 节流存在的唯一理由是"让 UI 线程少挨几次 binder" —— 而本轮把写入整个搬到了
         *   [StreamVolumeWriter]（后台线程），UI 线程一次都不挨；
         * · 后台那边用的是"**最新档位必胜** + 同档不重复写"：一次手势真正下发几次，只由
         *   "手指跨过了几个档位"决定（十几档，与点播逐档写入的行为一致），中间档位自己会被合并；
         * · 多出来的那道时间闸门只会让音量档位跳得比点播更粗，属于纯损失。
         */

        /** 亮度下限：0 会让屏幕全黑，用户找不回画面（点播同样夹 0.01） */
        private const val MIN_BRIGHTNESS = 0.01f

        /**
         * 正常弹窗里"非内容部分"的高度预留（dp）：标题一行 + 按钮栏 + 上下内边距。
         * 取值比 Material 弹窗的真实占用（约 120~140dp）留足余量 —— 宁可内容区矮一点，
         * 也不要让总高顶出屏幕把按钮挤出去（见 [dialogContentMaxHeightPx]）。
         */
        private const val DIALOG_CHROME_DP = 200

        /** 手势模式：手指按下点落在页面的哪半区（见 [TapCatcher]） */
        private const val DRAG_NONE = 0
        private const val DRAG_BRIGHTNESS = 1
        private const val DRAG_VOLUME = 2

        /**
         * ★本轮：「一次性方向钉住」的释放哨兵（[deviceOrientationSentinel]）用的三个数。
         *
         * · [DEVICE_AXIS_MARGIN]：屏幕平面上两根轴的重力分量差不到这个倍数（≈45° 边界）时，
         *   判不出手机是横是竖 —— 按"没读到"处理；
         * · [FLAT_GRAVITY_RATIO]：平面分量小于 `0.35g`（≈离平放 20° 以内）时同样判不出
         *   —— 手机躺桌上时横竖本来就读不准，把噪声当成"用户转了手机"会让方向乱跳；
         * · [DEVICE_BUCKET_CONFIRM_SAMPLES]：新档要**连续**读到几帧才算数（一帧抖动不算）。
         *   取 2：`SENSOR_DELAY_NORMAL`（≈5Hz）下约 0.2~0.4s —— 用户转完手机，页面几乎立刻跟上。
         */
        private const val DEVICE_AXIS_MARGIN = 1.3f
        private const val FLAT_GRAVITY_RATIO = 0.35f
        private const val DEVICE_BUCKET_CONFIRM_SAMPLES = 2

        /**
         * 同一时刻只允许一个直播播放页。
         * 不然"封面页连点两次"会叠出两个 ExoPlayer，两路声音同时响。
         */
        @Volatile
        private var currentInstance: LivePlayerActivity? = null

        /**
         * 「从桌面回到 App 仍停在直播间」的守卫（用户实测反馈，本轮修复）。
         *
         * ★为什么是**进程级**字段、而不是本页的一个普通字段：
         *   回到 App 的那一刻，本页很可能已经被系统收掉了（真机任务栈实测 + AOSP 源码，
         *   见 [ReturnToLiveGuard] 的注释），那时只有活在进程上的守卫还能把直播间重新拉起来。
         */
        private val returnToLiveGuard = ReturnToLiveGuard()
    }

    /**
     * PiP 期间「回 App 仍停在直播间」的守卫。
     *
     * ## 真实根因（真机 `dumpsys activity activities` 实测 + AOSP 源码，报告 §4 有完整证据）
     * 直播间是压在 MainActivity 之上的**第二个** Activity（同一任务里两个）。按 Home 自动进 PiP 时，
     * 系统走 `RootWindowContainer#moveActivityToPinnedRootTask()`：
     * ```java
     * final boolean singleActivity = task.getNonFinishingActivityCount() == 1;
     * if (singleActivity) { rootTask = task; }
     * else {
     *     // In the case of multiple activities, we will create a new task for it and then
     *     // move the PIP activity into the task.
     *     rootTask = new Task.Builder(...).build();
     *     r.reparent(rootTask, MAX_VALUE, reason);
     * }
     * ```
     * 即：**任务里不止一个 Activity 时，系统会新建一个 pinned 任务，把直播页搬过去**。
     * 真机实测对得上：`Task #12054 [MainActivity, LivePlayerActivity] sz=2` →
     * 按 Home 后 `Task #12058 mode=pinned [LivePlayerActivity] sz=1`，原任务 `#12054` 只剩 MainActivity。
     *
     * 于是点桌面图标回来时：launcher 拉起的是**主任务**（MainActivity 是 `launchMode="singleTask"`
     * 的 task 根、也是唯一的 LAUNCHER 入口），系统还会顺手把那个 PiP 任务 dismiss 掉 ——
     * 用户看到的就是"上一层"，直播页则被 finish 了。
     *
     * ## 为什么守卫不能挂在 Activity 上
     * 因为"回来"时本页可能已经不存在了（PiP 被 dismiss 的那条路会 finish 本页）。
     * 所以守卫做成**进程级单例**（[companion] 里的 returnToLiveGuard），只记房间号这种值，
     * **不持有任何 Activity 引用**（不给进程留泄漏）。
     *
     * ## 行为边界（刻意的）
     * - 只在"**用户带着直播间离开过 App**"（进过 PiP）之后才生效 —— 冷启动、正常浏览都不会被它打扰；
     * - 触发一次就解除武装：不会反复把用户拽回直播间；
     * - 用户自己回到直播间（点 PiP 窗口把它放大）时立即解除武装，什么都不做；
     * - 用户主动退出直播间（返回键 / 通知栏「退出直播」）时也解除武装。
     *
     * ## ★★2026-09-26 本轮：从"主路径"降级为**兜底**（见 [LiveLastRoomStore]）
     * 上面那套"进 PiP 才武装 + 依赖本页还活着"的机制有两个天生的时机依赖，用户 9 小时内实测到 3 次
     * "回软件落在直播 Tab 而不是直播间"：
     * ① 只覆盖"进过 PiP"这条路 —— **退桌面没进 PiP**（关掉了自动小窗）时压根不武装；
     * ② 本页 `onDestroy` 会解除武装，而"点桌面图标回 App"这条路偏偏会先 finish 掉 PiP 里的本页
     *   —— 解除与触发谁先谁后决定成败。
     * 现在**主路径**是 [LiveLastRoomStore]（记录落 DataStore、只看状态不看时机），
     * 守卫退化成"进程级、不依赖 DataStore 读写完成"的第二条触发路：它拉起之前先调
     * [LiveLastRoomStore.consumePendingForGuard] 问一次（同一套判据 + 同一个一次性记录），
     * 拿不到就什么都不做 —— 于是两条路不会打架、也不会叠出两个直播间。
     */
    private class ReturnToLiveGuard : Application.ActivityLifecycleCallbacks {

        /** 武装状态 = "用户带着直播间离开过 App，回来时应该落在直播间"；存房间号，不存 Activity */
        @Volatile
        private var armedRoomId: String? = null

        @Volatile
        private var registered = false

        fun arm(application: Application, roomId: String) {
            if (roomId.isBlank()) return
            armedRoomId = roomId
            // ★诊断日志（只读）：旧守卫武装（"回 App 落错页"的兜底路径）
            LivePageTrace.note("guard.arm", "room" to roomId, "registeredBefore" to registered)
            if (!registered) {
                // 只注册一次：同一个回调实例注册两次会被回调两次，第二次就会多开一个直播间
                application.registerActivityLifecycleCallbacks(this)
                registered = true
            }
        }

        /** 解除武装：用户自己回到直播间 / 主动退出 / 已经拉起过一次 */
        fun disarm() {
            // ★诊断日志（只读）
            LivePageTrace.note("guard.disarm", "room" to (armedRoomId ?: "-"))
            armedRoomId = null
        }

        override fun onActivityResumed(activity: Activity) {
            val roomId = armedRoomId ?: return
            // ★诊断日志（只读）：守卫看到"别的页面 resume"
            LivePageTrace.note(
                "guard.resumed",
                "armedRoom" to roomId,
                "activity" to activity.javaClass.name,
            )
            if (activity is LivePlayerActivity) {
                // 用户自己点开了 PiP 窗口（或系统把小窗展开）：人已经在直播间里了，守卫下班
                LivePageTrace.note("guard.disarm", "reason" to "alreadyInLivePage")
                armedRoomId = null
                return
            }
            // ★先解除武装（再往下走）：startActivity 会引发新的 onActivityResumed 回调，
            //   不先清掉就会自己触发自己
            armedRoomId = null
            // ★★2026-09-26 本轮：守卫**不再自己说了算**。它去问 [LiveLastRoomStore]
            //   （新的确定性主路径，记录落在 DataStore）—— 只有"应当恢复"的记录还在、
            //   且当下确实满足"回到软件 + 前台没有直播间 + 没有活着的直播间实例"时才拿到房间号；
            //   拿到即**消费**。于是主路径与守卫这两条路**只可能有一条**真的把直播间开起来，
            //   不会叠出第二个直播间（这正是"两条不能互相打架"的实现）。
            //   ★守卫保留的价值：它是"进程级、不依赖 DataStore 读写完成"的那条兜底。
            val resumeRoomId = LiveLastRoomStore.consumePendingForGuard(activity)
            if (resumeRoomId == null) {
                // ★诊断日志（只读）：守卫问过记录，但"当下不该恢复"（记录已消费/前台不是主界面/还有直播间实例）
                LivePageTrace.note(
                    "guard.restore.skip",
                    "reason" to "noPending",
                    "activity" to activity.javaClass.name,
                )
                return
            }
            // ★诊断日志（只读）：守卫真的要把直播间拉起来
            LivePageTrace.note(
                "guard.restore",
                "room" to resumeRoomId,
                "activity" to activity.javaClass.name,
            )
            runCatching {
                activity.startActivity(
                    Intent(activity, LivePlayerActivity::class.java)
                        .putExtra(EXTRA_ROOM_ID, resumeRoomId)
                        // NEW_TASK：把直播间开在**刚刚回到前台的那个任务**里（MainActivity 所在的主任务），
                        // 否则它会落进当前那个 pinned 小窗任务，用户看到的还是一个小窗
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            // 拉起失败（极少数 ROM 的后台启动限制）：不重试、不提示 ——
            // 用户回到 App 至少是能用的，反复弹直播间才是真的骚扰
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 视图 ────────────────────────────────────────────────────────────────
    private lateinit var rootLayout: FrameLayout
    private lateinit var videoContainer: AspectRatioFrameLayout
    private lateinit var videoView: TextureView
    private lateinit var danmakuLayer: FrameLayout
    private lateinit var tapCatcher: View

    /**
     * 手势气泡层（音量 / 亮度气泡挂在这里）——**播放页自己的视图树**里的一层。
     *
     * ★为什么不是 Dialog 窗口（本轮修的正是这个）：见 [applyHudGeometry] 的 KDoc。
     * 一句话 —— 悬浮窗的位置由"系统给这个窗口分配的框"决定，而播放页的位置由"页面在屏幕上的矩形"
     * 决定，两套坐标系一旦不一致（沉浸式/挖孔/系统栏/分屏/ROM 差异），气泡就会往外跑或不贴边。
     * 挂在页面里之后，气泡的坐标只有一套（页内），而且**物理上不可能离开页面**。
     *
     * ★图层位置（[buildUi] 末尾的 bringToFront 序列钉死）：弹幕之上、控制条之下。
     *   `TapCatcher` 在它**下面**却仍然收得到手势 —— 因为这一层和两份 GSY 布局里
     *   **没有任何 clickable 的控件**，触摸事件会原样穿透下去（见 [GestureHud.addBubble] 的注释）。
     */
    private lateinit var hudLayer: FrameLayout

    /**
     * 竖屏版式量出来的两个矩形（[LivePortraitStage] 的取值来源；非竖屏为 null）。
     * 只在主线程写（[measurePortraitStage]）、只在主线程读（宿主注入 View 也在主线程）。
     */
    private var portraitVideoRect: Rect? = null
    private var portraitListRect: Rect? = null

    // ── 「按真实尺寸重排」的状态与指纹（★第四批第 1/3 条，见 [installPageLayoutWatchers]）──────
    /** 上一次看到的页面真实宽高（px）。**只有它变了**才重排底栏/版式（防自激，见 [syncPageLayoutToRealSize]） */
    private var lastPageWidth = 0
    private var lastPageHeight = 0

    /**
     * 底栏当前分行的"指纹"：`方向/可见按钮数/每行几个`。
     * 与"应该是什么"不一致时才重建 —— 这是 [syncBottomBarLayout] 的幂等依据
     * （尺寸监听器会在每一次布局回调里问一次，没有它就会反复重建）。
     */
    private var bottomBarLayoutKey: String? = null

    /**
     * 竖屏视频带**当前生效**的顶边（页内 px，-1 = 还没排过）。
     * 顶栏高度一变（状态栏临时划出/标题换行）就拿它比对，只在真的变了时重排，防自激。
     */
    private var appliedVideoBandTop = -1

    /** 视频带上一次量到的矩形；只有它真的变了才重新量版式（见 [installPageLayoutWatchers]） */
    private var lastVideoBandRect: Rect? = null

    /**
     * 沉浸式策略**上一次真正下发**的值（`true` = 横屏、已隐藏系统栏；`null` = 还没下发过）。
     *
     * 这是 [syncImmersivePolicy] 的幂等依据之一：策略值没变（且系统栏真实可见性也符合策略）时
     * 一个字节都不下发 —— 于是"每个形态变化点都调一次"是安全的，不会闪、也不会产生多余事务。
     * ★为什么不能只信它：小窗/ROM 可能在我们背后动过系统栏（"PiP 回来还是隐藏"正是这种状态），
     *   所以还要拿 [statusBarVisible] 的真实值再比一次，见 [syncImmersivePolicy] 的两道门。
     */
    private var immersiveBarsHidden: Boolean? = null

    /**
     * 竖屏"留给弹幕列表"的**空槽** —— 一块 `INVISIBLE` 的占位 View，位置就是那块矩形。
     *
     * ## 为什么用一个 View 来表达"矩形"，而不是只给宿主一组数字
     * 宿主（`LiveDanmakuOverlayHost`）的接线口是 `bindPortraitListArea(slot = …)`：给它一个 View，
     * 它按这个 View **实际 layout 出来的位置**摆列表，并挂 `OnLayoutChangeListener` ——
     * 转屏、视频换比例、底栏 1↔2 行、导航栏 insets 变化它都会自己重算，**不需要播放页再通知一次**。
     * 数字版（[portraitDanmakuListBounds]）是同一块地方的只读镜像，给"不想持有 View"的调用方用。
     *
     * ## 它为什么不会出问题
     * · `INVISIBLE`：不画（用户看不见）、不参与触摸派发（`ViewGroup` 只把事件给 VISIBLE 的子 View）；
     * · 仍然会被 layout（`INVISIBLE != GONE`），所以 `getLocationInWindow()` / `height` 都是真值；
     * · 它只是"标尺"，**宿主不会把面板塞进它里面**（塞进去就会被手势层压住、滑不动）——
     *   宿主是在 activity 内容视图里**复刻**这块矩形，见宿主类注释。
     * · 不放进 [buildUi] 末尾的 bringToFront 序列：它是最底层的一块透明标尺，图层无关紧要。
     */
    private lateinit var danmakuListSlot: View

    private lateinit var topBar: LinearLayout

    /**
     * 顶栏返回按钮：**纯图标**（本轮从「← 返回」文字按钮改过来，构建处在 [buildUi] 顶栏那一段）。
     * 图标 = 点播播放器同款 `R.drawable.ic_arrow_back_white_24dp`；点击走 [handleBack]（与系统返回键同源）。
     */
    private lateinit var backButton: ImageView

    /**
     * 底栏「画中画」文字按钮 —— **画中画唯一的入口**（用户要求留在底栏）。
     *
     * ★第七批那颗"顶栏画中画图标"（`pipIconButton`）已**整体删除**（用户 2026-09-26 改主意：
     *   "没必要移到顶栏"；★本轮清死代码：字段 / 创建代码 / 被注释掉的 addView / 主题刷新那一行
     *   一起删掉，见 [buildUi] 顶栏那一段）。
     */
    private lateinit var pipButton: TextView

    /**
     * 顶栏标题：`直播间 房间号（x.x万人在线）`（★本轮：在线人数就写在房间号后面那个括号里）。
     * 拼接只在 [renderRoomTitle] 一处（初值「直播间」；拿不到人数就不带括号）。
     */
    private lateinit var titleText: TextView

    /**
     * 顶栏状态文字（播放侧 + 弹幕连接状态拼一行）—— ★本轮起**只在异常/过渡时显示**，
     * 正常播放时 `GONE`（顶栏只剩返回 + 标题）。判定清单见 [renderStatus]。
     */
    private lateinit var statusText: TextView

    /**
     * 底栏（★第七批重构，★第八批统一成一行；★第十四批起 5 颗按钮）：`Gravity.BOTTOM` 的浮层，
     * 内容 = **输入条 + 五颗按钮（同一行）**。
     *
     * ```
     * bottomBar(VERTICAL) = [ row(HORIZONTAL: danmakuInputRow(weight=1) + bottomButtons), danmakuInputError ]
     *                          ↑ 输入框吃剩下的宽度                   ↑ 5 颗等宽格
     * ```
     * ★竖屏与横屏**同一套版式**（★第八批：用户要"输入框和那几个按钮放一起"，见 [rebuildBottomBar]）。
     * ★它自己**不 GONE**：显隐全落在孩子身上（输入条与五颗按钮都走 `INVISIBLE`）——
     *   于是隐藏时底栏高度不变、底栏顶边不变，弹幕列表区的底边也就不会跟着跳。
     * ★它的**顶边**就是竖屏弹幕列表区的底边（[measurePortraitStage]），
     *   而它的**底部内边距**由 [refreshBottomBarInsets] 按导航栏 + 输入法高度写。
     */
    private lateinit var bottomBar: LinearLayout

    /**
     * 输入条那一行（HORIZONTAL）：[danmakuInput] + [danmakuSendProgress]。
     *
     * ★它自己是一个**可搬运的整行**：在底栏那条横向 `row` 里（weight = 1，吃剩下的宽度，
     * 五颗按钮贴在它右边）。搬运在 [rebuildBottomBar] 里做（先摘后加）。
     * ★显隐与五颗按钮**同一套**（[applyControlsVisibility]）：点画面出现、4 秒后一起消失，
     *   只有"正在输入"与 PiP 两档是特例（见那段 KDoc）。
     */
    private lateinit var danmakuInputRow: LinearLayout

    /**
     * **弹幕输入框**（★第七批的核心，用户："发弹幕变常驻输入条……把它那个改成发送，
     * 我们就省了一个发送的按钮了，也省出那个弹窗按钮，很烦，感觉又多走一步"）。
     *
     * · hint = 「发个弹幕…」，`imeOptions = IME_ACTION_SEND` → 软键盘的回车键就是**发送**，
     *   不需要发送按钮、也不弹任何窗口（[submitDanmakuInput]）；
     * · 发送中 `isEnabled = false` + [danmakuSendProgress] 转圈；
     * · 成功清空 + 收起键盘；失败**保留文本**并把服务端原文写进 [danmakuInputError]；
     * · 焦点变化会 `holdControls()`（见 [buildUi]）：正在打字时控制条不许自动隐藏。
     * · ★第八批（用户实测第 2 条）：它**不再常驻** —— 与那几颗按钮一起按 4 秒计时显隐
     *   （"为什么不和那几个按钮一起显示一两秒呢？为什么要一直站在那？"），
     *   见 [applyControlsVisibility] 的 `isDanmakuInputActive()` 档（正在输入时绝不收）。
     */
    private lateinit var danmakuInput: EditText

    /** 发送中的转圈（放输入框右侧，`GONE` ↔ `VISIBLE`） */
    private lateinit var danmakuSendProgress: ProgressBar

    /**
     * 输入条下方的失败提示行（`GONE` 常态）。
     *
     * 为什么不只 toast（用户："失败**保留文本**并把 `LiveDanmakuClient.lastSendError`（服务端原文）
     * 显示出来（toast 或输入条下方的提示）"）：toast 两秒就没了，而"为什么被拒"
     * （敏感词 / 频率限制 / 未登录 / 长度超限）恰恰要边改文本边看，所以两条路一起给：
     * toast 负责"立刻知道"，这一行负责"看得见地留着"。见 [showDanmakuInputError] 的 6 秒自动收起。
     */
    private lateinit var danmakuInputError: TextView

    /**
     * 五颗按钮的**横向容器**（弹幕 / 画质 / 设置 / 画中画 / 旋转）。
     *
     * ★为什么按钮要独立成一层：显隐时只换**这一层**的 `visibility`（`VISIBLE` ↔ `INVISIBLE`），
     *   而用 `INVISIBLE`（不是 `GONE`）是为了**保住占位** ——
     *   底栏高度不变 → 底栏顶边不变 → 弹幕列表区不随控制条显隐跳来跳去
     *   （这正是"上下留白是用户有意留给控制栏的，不要优化掉"那条要求的落点）。
     * ★第八批：它的**宽度**由 [applyBottomBarTextSizes] 按"等宽格 × 颗数"写成固定值
     *   （里面每颗都是 `weight = 1` 的等分格）—— 用户第 3 条要的"同高、同宽（等分）"就落在这里。
     *   ★第十四批加第 5 颗（[settingButton]）时**这一行代码没动**：颗数是从
     *     [orderedBottomButtons] 数出来的，宽度与字号自动按 5 格重算。
     */
    private lateinit var bottomButtons: LinearLayout

    private lateinit var progressBar: ProgressBar

    /**
     * 底栏「画质」（★第七批：原来的 `qualityButton` + `lineButton` **合并成这一颗**）。
     *
     * ★第八批：文案从「画质·原画」**缩短成「画质」** —— 用户第 3 条给了两条路
     *   （"要么给足宽度、要么缩短为「画质」"），而这一颗要和输入框、另外四颗**挤在同一行**：
     *   5 个字的文案会把整行字号从 14sp 拖到 9sp（[applyBottomBarTextSizes] 按"最宽的文案"统一挑档）。
     *   当前画质/线路**一个信息都没少**：点开这颗按钮，「清晰度」段与「线路」段的副标题就是
     *   「当前：原画（qn 10000）」「当前：线路 1/2」（[showStreamDialog]），当前档还带打勾。
     *   ★本轮更正一句旧注释：它**不再**在顶栏状态行里出现（用户拍板"当前画质/线路从状态行去掉"，
     *     见 [renderStatus] 的判定清单）—— 顶栏现在只在异常/过渡时显示状态文字。
     *   想改回"带当前值"：把这里与 [delegateListener] 里那处换成 `"画质·$desc"` 即可（一行）。
     */
    private lateinit var qualityButton: TextView
    private lateinit var danmakuButton: TextView

    /**
     * ★第十四批：底栏「设置」（**排在「画质」与「画中画」中间** —— 用户点名要的位置）。
     *
     * 唯一去向 = [showLiveSettingSheet]（compose 模块的 `LiveSettingSheetHost`，与首页直播 Tab
     * 那颗「筛选」用的是**同一套弹窗外壳** `AutoSheetDialog`）。
     * ★它**不**参与"当前值"的显示（就是「设置」两个字）：一是位置夹在中间、二是它弹的弹窗里
     *   每一项都带着当前值，按钮上再写一遍只会把整行字号拖低。
     * ★文案 2 个字：与「画质 / 旋转 / 重连」同档，**不改变整行字号档位**
     *   （[applyBottomBarTextSizes] 按"一行里最宽的文案"统一挑档，最宽的是 3 字的「弹幕开」/「画中画」）。
     */
    private lateinit var settingButton: TextView

    /** 底栏「旋转」（用户点名要留在底栏："那个旋转一定要放到底栏来啊，它是经常用到的"） */
    private lateinit var rotateButton: TextView

    // ★第七批删掉的字段（连同它们的按钮一起）：
    //   · `lineButton`   —— 线路并进「画质·线路」弹窗的第二段（[showStreamDialog]）；
    //   · `sendDanmakuButton` —— 被常驻输入条 [danmakuInput] 取代；
    //   · `retryButton`  —— 「刷新」改自动（[autoRetryLiveStream]），手动入口在弹窗里；
    //   · `pipButton`    —— ★2026-09-26 又搬回底栏了（用户："没必要移到顶栏"）；顶栏那颗图标（`pipIconButton`）已整体删除（见 [buildUi] 顶栏那一段）。

    // ── 播放 / 弹幕 ─────────────────────────────────────────────────────────
    private var delegate: LivePlayerDelegate? = null
    private var danmakuHost: LiveDanmakuOverlayHost? = null

    /**
     * ★第十四批：底栏「设置」按钮弹出的**直播设置弹窗**的宿主（compose 模块的 View 桥）。
     *
     * 用户原话："我们在直播间底栏，画质的后面、PIP 的中间添加一个按钮，就是设置。就是会弹出直播间的
     * 设置选项、设置页。**记得用我的那个自定义的全屏弹窗**，不管你怎么转屏，它都会自己适配。
     * 就是我的那个底栏筛选的那个弹窗。"
     *
     * · 弹窗本体（外壳 `AutoSheetDialog` + 设置项）在 `LiveSettingSheet.kt`；本页只负责
     *   "什么时候弹/收"（[showLiveSettingSheet] / [dismissLiveSettingSheet]），
     *   以及弹窗关掉之后把改过的设置**再下发一次**给播放核心（[onLiveSettingSheetDismissed]）。
     * · 为什么是 `var`（可空）而不是 `lateinit`：它**懒创建**（第一次点「设置」才 new 并 addView），
     *   没点过这个按钮的人连一个空 ComposeView 都不会有。
     * · 它 addView 进 [rootLayout] 时给的是 `0×0`：弹窗是**独立窗口**，跟宿主占多大地方无关，
     *   给 0 就绝不会影响本页任何一层的版式（本页每个几何都是算出来的）。
     */
    private var liveSettingSheetHost: LiveSettingSheetHost? = null

    /**
     * 弹幕客户端引用（取自 [LiveDanmakuOverlayHost.client]，宿主已把它开成公开只读属性）。
     * 用它做两件事：发送弹幕（`sendDanmaku`）、读连接状态（`connectionState`）。
     */
    private var danmakuClient: LiveDanmakuClient? = null
    private var danmakuConnState: ConnState? = null
    private var danmakuStateJob: Job? = null

    // ── 弹窗 / 手势提示 ─────────────────────────────────────────────────────
    //
    // ★弹窗全部是"正常弹窗"（用户实测：自建的全屏 Dialog 超出屏幕、点不到按钮）：
    //   清晰度 + 线路 = **同一个** [LiveListDialog]（内部是 MaterialAlertDialog +
    //   TabLayout 两段 + 定高可滚列表，见 [showStreamDialog]）。
    //   ★第七批：**发弹幕的弹窗已整块删除** —— 它被常驻输入条 [danmakuInput] 取代
    //     （用户："省出那个弹窗按钮"），所以这里只剩一个弹窗字段。
    //   ★底栏「设置」与它弹的直播设置弹窗**已删**（用户："用户想设置自己退出来再去设置"）——
    //     那些设置一个没少，入口只剩「设置 → 直播设置」页（Compose 的 `LiveSettingPage`）。
    private var streamDialog: LiveListDialog? = null

    private val gestureHud by lazy { GestureHud() }

    /**
     * 手势音量的**后台写入器**（★第十三批，见 [StreamVolumeWriter]）。
     *
     * ★为什么是"可空 + 访问器"而不是 `by lazy`：它持有一条 `HandlerThread`，
     *   而 [onDestroy] 必须能把这条线程**收掉**（每进一次直播间就会有一个本页实例，
     *   漏一条线程就是漏一个）。`by lazy` 在 [onDestroy] 里一读就会**创建**它，
     *   于是"从没滑过音量手势的页面"也会凭空多一条线程 —— 所以用手写缓存：
     *   只有真的用了才建，[onDestroy] 里只收"建过的那个"。
     */
    private var volumeWriter: StreamVolumeWriter? = null

    private fun obtainVolumeWriter(): StreamVolumeWriter =
        volumeWriter ?: StreamVolumeWriter(applicationContext).also { volumeWriter = it }

    /**
     * 直播 PiP 动作接收器（点小窗里的播放/暂停）。
     *
     * ★生命周期（本轮改）：**随本页创建/销毁**（[onCreate] 注册、[onDestroy] 注销），
     *   不再"只在 PiP 期间注册"。改的理由见 [registerPipActionReceiver]：
     *   旧写法把"按钮能不能用"押在 `onPictureInPictureModeChanged(true)` 这个回调上，
     *   而它并不保证在所有进小窗的路径（自动进入 / 系统代劳 / 回调被 ROM 吞掉）都先于用户点击到达。
     * ★注册用 `RECEIVER_NOT_EXPORTED`：只有**本 App**（含本 App 发出的、由 SystemUI 代发的
     *   PendingIntent）能把动作投进来 —— 为什么它不会挡住 SystemUI，见 [buildPipActions] 的证据链。
     */
    private val pipActionReceiver = LivePipActionReceiver()

    /** 接收器当前是否已注册（幂等注册/注销都要用它判断） */
    private var pipReceiverRegistered = false

    // ── 状态 ────────────────────────────────────────────────────────────────
    /** 原始房间号（可能是短号） */
    private var rawRoomId: String = ""

    /** 真实房间号（room_init 换算之后；弹幕与取流都用它） */
    private var realRoomId: Long = 0L

    /**
     * 顶栏标题里那个房间号（来自 [LivePlayerDelegate.Listener.onStreamReady] 的 `info.roomId`
     * = 真实房间号）；0 = 还没拿到 → 标题保持初值「直播间」，不带括号。
     * ★它单独存一份而不是每次去读 `delegate?.roomId`：标题要在**在线人数回来**时重画一次
     *   （见 [renderRoomTitle]），那条路与"取流成功"是两条时间线。
     */
    private var titleRoomId: Long = 0L

    /**
     * 顶栏「在线人数」（`room/v1/Room/get_info` 的 `online` 字段；**null / 0 = 没拿到**）。
     *
     * ★为什么用可空 + "0 也算没拿到"：接口拿不到、风控、断网时这个字段会是 0 或不返回，
     *   而"0 人在线"在直播里几乎不存在 —— 用户点名要求**拿不到就不显示括号**
     *   （"不要显示 0 / --"），所以渲染那一侧只认 `> 0`（见 [renderRoomTitle]）。
     */
    private var roomOnline: Long? = null

    /**
     * 上一次**真正发出**在线人数请求的时刻（`SystemClock.elapsedRealtime()`；0 = 还没发过）。
     *
     * 它是 [ONLINE_REFRESH_INTERVAL_MS] 的闸门：看门狗每 5s 醒一次，但只有这里过了 45s
     * 才真发一次请求。★取"发出前"就写时间戳（乐观写法），这样即使请求挂在超时上，
     * 也不会被下一次巡检再叠一发。
     */
    private var roomOnlineFetchedAtMs = 0L

    /** 当前请求的清晰度。
     * ★初值取自「设置 → 直播设置 → 默认画质」（`live_default_quality`），见 [defaultRequestedQn]：
     *   该键的默认值是"最高可用" = 请求原画、未登录时由服务端静默降到 250（见 delegate 注释），
     *   与本次改动之前写死的 [LiveAPI.QUALITY_ORIGIN] 完全一致 —— 老用户行为不变。
     */
    private var requestedQn: Int = LiveAPI.QUALITY_ORIGIN

    /** 服务端**实际**下发的清晰度（弹窗里"当前"打勾的就是它，不是 [requestedQn]） */
    private var actualQn: Int = 0
    private var actualQnDesc: String = ""

    private var qualityOptions: List<LivePlayerDelegate.LiveQualityOption> = emptyList()
    private var lineOptions: List<LivePlayerDelegate.LiveLineOption> = emptyList()

    /**
     * 弹幕开关。
     * ★初值取自「设置 → 直播设置 → 显示弹幕」（`live_danmaku_enable`，默认开 = 与本次改动前一致），
     *   底栏「弹幕」按钮和底栏「设置」里的那一项改的都是**本页这个字段**（会话级临时开关），
     *   设置弹窗里改动会同时写回 DataStore（下次进直播间沿用）。
     */
    private var danmakuEnabled = true

    /**
     * **该直播间是不是"关闭了弹幕/评论"的房间**（★本轮新增。用户原话："某一些直播间，它是关闭了
     * 评论的那个选项。如果能获取到它关闭评论 API 的话，那么这个时候就不应该去显示那个发送弹幕的
     * 输入框，还有那个弹幕区域了。还有弹幕的那个，不管横屏竖屏，应该给它关了。"）。
     *
     * ```
     * true  → 输入条不再能输入/发送（原位只留一行很轻的提示）、竖屏弹幕列表不显示、滚动弹幕不渲染
     * false → 一切照旧（与本次改动前逐字节一致）
     * ```
     *
     * ## 判据从哪来（★跟着房间走）
     * [probeRoomDanmakuPolicy]（进房时调一次；换房走 `recreate()`，天然重新判定）→
     * `LiveAPI.roomDanmakuPolicy()`：`getInfoByRoom` 的
     * `new_switch_info["room-danmaku-editor"] == 0`（**官方 Web 端"显不显示弹幕输入框"用的就是它**）
     * 或 `switch_info.close_danmaku == true`。实测对照表与出处见 `LiveRoomInfoByRoom` 的类注释。
     *
     * ★**初值 false（= 没关闭）**：判定是异步的、而且可能失败（风控 -352 / 断网），任何"还不知道"
     *   的时刻都必须按"没关闭"处理 —— 绝不因为一次请求慢/失败就把正常直播间的弹幕 UI 提前关掉。
     */
    private var roomDanmakuClosed = false

    /** "这个房间关没关弹幕"的判定任务（换房/重进时先撤掉上一次的，避免结论串台） */
    private var roomDanmakuPolicyJob: Job? = null

    /** 开播轮询任务（未开播/下播时才活着） */
    private var pollJob: Job? = null

    // ── 「没在播」这条线（★本轮新增：未开播 / 中途下播）────────────────────────
    /**
     * **"现在没有在播"的锁定**（未开播 / 中途下播 / 拿不到流反复失败之后都落在这里）。
     *
     * ```
     * true  → 所有自动追流一律不做（[autoRetryLiveStream] 的第 ⓪ 道门、看门狗直接跳过），
     *          唯一的恢复路径 = "等待开播"轮询 [startOfflinePolling]（45s 一次的单次尝试）
     * false → 一切照旧（与改动前逐字节一致）
     * ```
     *
     * 为什么必须有它：修前"下播"这件事**没有任何判据**，看门狗 + delegate 的自动换线/重取流
     * 各自只有"降速"用的滑动窗口（窗口一过额度回满），于是中途下播会变成无限换流
     * （详见交付报告 §2）。锁定之后"换流"这条链**只剩一个终点**：等主播回来。
     *
     * 谁来置位：① [LivePlayerDelegate.LivePlayState.OFFLINE]（delegate 判定"没有流了"）；
     * ② [LivePlayerDelegate.Listener.onLiveOffline]（delegate 拿到**硬证据**：`live_status != 1`）。
     * 谁来清：真的播出画面（`PLAYING`）/ 用户手动「重新取流」/ 开播轮询发现开播后重新起播。
     */
    private var offlineLatched = false

    /**
     * "主播已下播 / 未开播"这个弹窗**本次进房是否已经弹过**。
     *
     * ★用户明确要求"不要自动循环弹窗"：一次下播事件只弹一次；真的重新开播（`PLAYING`）之后
     *   才重新允许下一次（那时是**新的一次**下播，再弹一次才是对的）。
     */
    private var offlineDialogShown = false

    /** 那个一次性提示弹窗（走本页统一的 [normalDialog]，与「画质·线路」同一套观感） */
    private var liveOfflineDialog: AlertDialog? = null

    /**
     * **本次进房有没有真的播出过画面**（`PLAYING`）—— 只用来决定文案说"未开播"还是"已下播"。
     *
     * ★为什么用"出过画面"而不是列表里那个 `live_status`：列表可能是旧的（用户实测："我之前的列表
     *   还是'他开播'的状态，我进去其实他没开播"），而这个标记是**本页亲眼看到的**事实。
     */
    private var hasPlayedThisRoom = false

    /**
     * ★本轮新增：自动追流的预算**是不是已经用尽**（一次性，不再随滑窗回满）。
     *
     * 修前 [autoRetryLiveStream] 的 ④ 号门是"5 分钟内最多 3 次"的**滑动窗口**：窗口滑过去额度就
     * 自动恢复 ⇒ 只要房间一直不出流，它就会**永远**每 20s 追一次（这正是"一直在换流"的一路）。
     * 现在改成"一份预算用到底"：用完就停手并如实告知；只有**真的恢复播放**（`PLAYING`）或用户
     * 手动「重新取流」才重新给一份（见 [autoRetryBudgetExhausted] 的两个复位点）。
     */
    private var autoRetryBudgetExhausted = false

    /** 弹幕 WS 的直播状态订阅（`LIVE` / `PREPARING`）—— 零成本的下播信号，喂给 delegate 的判据 */
    private var liveSignalJob: Job? = null

    /** 已经就"该清晰度拿不到"提示过的 qn，避免反复弹 toast */
    private var warnedQn = -1

    private var videoWidth = 0
    private var videoHeight = 0

    /**
     * 上一次**真正下发**给系统的 PiP 几何指纹（[pipGeometryKey]：宽高比 + 源矩形）。
     *
     * ★第八批新增：几何一变就重下发（[syncPipParamsToVideoGeometry]），而"变没变"要有依据 ——
     *   这就是那份依据。初值 null = 还没下发过（`buildUi` 末尾会下发第一次并写上）。
     * ★只由 [updatePipParams] 写（唯一的下发口），所以它永远等于"系统手上那份参数"。
     */
    private var lastSentPipGeometryKey: String? = null

    /**
     * 上一次**真正下发**给系统的**整份参数**指纹（★本轮新增，[pipParamsKey]）。
     *
     * 与 [lastSentPipGeometryKey]（只有几何：比例 + 源矩形）的区别：
     * [buildPipParams] 下发的**不只是几何**，还有两个非几何入参 ——
     * ```
     * setActions(buildPipActions())                 ← 播放/暂停图标随 delegate.isPlaying 翻
     * setAutoEnterEnabled(pipOnBackgroundEnabled() && hasVideoPicture())   ← 随"画面就绪"翻
     * ```
     * 所以"去重"只能按**整份参数**的指纹做：只按几何去重会把上面这两条路一起挡掉，
     * 而它们各自都是已交付的修复（小窗按钮图标不刷新 / 首帧前不弹假比例黑小窗）。
     */
    private var lastSentPipParamsKey: String? = null

    /**
     * 控制条（顶栏 + 底栏那几颗按钮 + 输入条）当前**实际**是否可见；真正的取值由 [applyControlsVisibility] 写。
     * ★第七批起它不管输入条（那时输入条常驻）；★第八批起**又管了** —— 输入条与按钮同一套显隐
     *   （用户："为什么不和那几个按钮一起显示一两秒呢？"），只有"正在输入"与 PiP 两档例外，
     *   见 [applyControlsVisibility]。
     */
    private var controlsVisible = true

    /**
     * "**马上就要进小窗**"的标记（★第五批第 3 条）。
     *
     * ## 为什么不能只信 `isInPictureInPictureMode`
     * 进小窗的完整时序是：`onUserLeaveHint`（31+ 时系统随后**自动**把小窗做出来）→ 窗口缩成小窗
     * → 一次"小窗尺寸"的配置变更 → [onConfigurationChanged] → `onPictureInPictureModeChanged(true)`。
     * 中间这几步里 `isInPictureInPictureMode` **还是 false**，而 [onConfigurationChanged] 末尾那句
     * `showControlsTemporarily()` 会把刚收起来的控制条**又显示出来**（用户看到的就是"PiP 里那
     * 一两秒被底栏和顶栏挡住"）；同一时刻按小窗宽度测量出来的底栏按钮，也是"返回后字号变小"的源头之一。
     * 所以从"我们决定/预期要进小窗"的那一刻起就置位，直到**真的回到全屏**（[onResume] /
     * `onPictureInPictureModeChanged(false)`）或确定没进成（[onStop]）才清掉。
     */
    private var pipEntryPending = false

    /**
     * ★★第十批：**刚刚退出小窗**的时刻（`SystemClock.elapsedRealtime()`；0 = 没在过渡窗口里）。
     *
     * ## 为什么需要它（本轮修复 ④，见报告 ②-3）
     * "点小窗放大"在系统侧是一次**窗口过渡动画**（小窗尺寸 → 全屏尺寸）。过渡期间我们对系统做的
     * 每一件"改窗口几何"的事都会被卷进那次动画里：`setPictureInPictureParams`（跨进程，
     * 会让 SystemUI 重算小窗几何）、`requestedOrientation` 断言、底栏/版式重量。
     * 而退出小窗这条路上，几何**必然**变化（画面矩形从小窗矩形变成全屏矩形）⇒
     * [syncPipParamsToVideoGeometry] 的指纹必然变 ⇒ 原来一定会在动画中间下发一次 PiP 参数。
     * 点播那条稳的链路没有这个问题：它的 `setPictureInPictureParams` 只在"进入小窗"和
     * "点了小窗动作按钮"两处发生（`PicInPicHelper:331`），**几何驱动的重下发**是直播这边独有的
     * （那份对齐报告自己也写了"点播没有'几何驱动的重下发'"）。
     *
     * ## 怎么用（**延迟，不是取消** —— 保护"几何变化重下发参数"这条已知修复）
     * [PIP_EXIT_SETTLE_MS] 毫秒内的下发请求不丢，只是排到过渡动画之后再补一次
     * （见 [deferPipParamsDuringExitTransition]）。所以"几何一变就重下发"这条修复**一条没少**，
     * 只是不再插在动画中间。时间戳写法自带过期，不会像布尔标志那样"卡住变永不下去"。
     */
    private var pipExitedAtMs = 0L

    /**
     * 顶栏状态行的"播放侧"文案（正常态也记，只是不显示 —— 见 [streamStatusAbnormal]）。
     * 弹幕连接状态由 [danmakuStatusLabel] 拼在后面，见 [renderStatus]。
     */
    private var streamStatus = "准备中…"

    /**
     * ★本轮：这条播放侧文案是不是**异常/过渡**态（true = 显示，false = 正常态、状态行整条隐藏）。
     *
     * 初值 true：进页面时是「准备中…」（正在解析房间/取流），属于过渡态，该显示。
     * 两个写入口只有 [setStreamStatus]（异常）与 [setStreamStatusNormal]（正常），
     * 判定清单写在 [renderStatus] 的 KDoc 里 —— **不要**在别处直接改这个字段。
     */
    private var streamStatusAbnormal = true

    private val hideControlsRunnable = Runnable {
        // 只有"正在播"才自动隐藏：暂停/报错时控制条要留在屏幕上；
        // 弹窗开着时也不能藏（用户正在列表里挑，控制条消失纯属添乱）；
        // ★第七批：**正在输入弹幕时也不藏** —— 用户手指还在输入框上，控制条突然消失会让人以为点错了。
        //   ★第八批起输入条与按钮**同一套显隐**，所以这一道门现在是"整条控制条都不藏"
        //   （[applyControlsVisibility] 里还有第二道同样的门：就算别处请求隐藏，正在输入时也不落地）。
        if (delegate?.isPlaying == true && !isAnyDialogShowing() && !isDanmakuInputActive()) {
            setControlsVisible(false)
        }
    }

    // ── 主题配色（本轮新增：直播区域跟随 App 主题）─────────────────────────────
    /**
     * 本页要用的三个主题色，**只在这里算一次**（[applyThemeColors] 负责刷到控件上）。
     *
     * 为什么提成字段而不是每次现取：取色要读 DataStore 内存快照 + 解主题属性，
     * 而"按钮底色/状态文字/进度条"这些地方一次 buildUi 就要用好几遍；
     * 缓存一份还能保证同一屏里的所有点缀色**同源**（不会出现按钮是旧色、弹窗是新色）。
     * 主题在设置页改完要重进本页才生效（与本页其它设置项一致），转屏 / 深浅色切换
     * （[onConfigurationChanged]）会重算一次。
     */
    private var themePrimary = Color.WHITE
    private var themeSecondary = Color.WHITE
    private var themeTertiary = Color.WHITE

    /**
     * 输入法当前占掉的高度（px，来自 `WindowInsetsCompat.Type.ime()` 的 bottom）。
     *
     * 两个消费端：
     * ① [refreshBottomBarInsets] —— 把键盘高度加到底栏的底部内边距上（★第七批的键盘避让，
     *    为什么不能只靠 `adjustResize` 见类注释"键盘避让"那一段）；
     * ② [dialogContentMaxHeightPx]`(leaveRoomForIme = true)` —— 内容区封顶再扣掉键盘高度。
     */
    private var imeInsetPx = 0

    /** 导航栏内边距（px，`systemBars().bottom`）；底栏底部内边距取它与键盘高度的**较大值**，见 [refreshBottomBarInsets] */
    private var systemBarBottomInsetPx = 0

    /**
     * 没有键盘时页面的高度（px，[rootLayout] 的实测高度）。
     * 用来判断"窗口本身有没有为键盘让出高度"（老系统 `adjustResize` 会缩窗口，Android 15+ 不会），
     * 见 [refreshBottomBarInsets]。
     * ★★第九批：它现在**绑定到一个宽度**上（[pageHeightBaselineWidthPx]）—— 见那个字段。
     */
    private var pageHeightWithoutIme = 0

    /**
     * ★★第九批：[pageHeightWithoutIme] 是在**哪个页面宽度**下量到的（px；0 = 还没量过）。
     *
     * 为什么必须记宽度（用户实测第 2 条的根因之一，"键盘上方半透明/没完全顶上去"）：
     * 转屏时宽高一起变（竖屏 1264×2800 ↔ 横屏 2800×1264），而**键盘还开着**的那条路上
     * `ime > 0` 永远不更新基准（基准的定义就是"没有键盘时的页高"）—— 于是横屏下会拿
     * **竖屏的 2800** 当基准去算"窗口已经让掉了多少"：
     * ```
     * 已让 = 2800 - 1264 = 1536  →  还要再让 = max(0, 键盘 700 - 1536) = 0
     * ```
     * 结论就是"键盘在横屏下不抬底栏"—— 底栏只露出半截在键盘上方（用户看到的"有点透明、
     * 不是完全显示的顶上去"）。判据用**宽度**而不是 `Configuration.orientation`：
     * 宽度变化 ⟺ 窗口形状变了（转屏/分屏/折叠屏展开），与 [isPageLandscape] 同源。
     */
    private var pageHeightBaselineWidthPx = 0

    /** 已经写给底栏的那一份"底部内边距"(px)；与算出来的不一样才写（防自激布局） */
    private var appliedBottomBarInsetPx = -1

    /**
     * ★★第九批：竖屏列表几何的"落定后再推一次"任务（见 [scheduleLiveListGeometrySettle]）。
     * 每次几何变化都 `removeCallbacks` + `postDelayed` 重置它，所以它永远是"最后一帧之后"那一次。
     */
    private val liveListGeometrySettleRunnable = Runnable { settleLiveListGeometry() }

    /**
     * ★★第十批：退出小窗过渡结束后的那一次 **PiP 参数补发**（见 [deferPipParamsDuringExitTransition]）。
     * 每次排之前都先 `removeCallbacks`，所以同一时刻最多只有一个；[onDestroy] 里也会撤掉
     * （它抓着本页，不能让它活过页面）。
     */
    private val pipParamsResendRunnable = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        pipExitedAtMs = 0L
        updatePipParams()
    }

    // ── 自动追流的状态（★第七批：「刷新」按钮改自动，见 [autoRetryLiveStream]）──────────
    /** 页面是否处于"已 start 未 stop"（看门狗只在可见时动作） */
    private var pageStarted = false
    /** [onStop] 留下的标记：真的退过后台 → 下一次 [onResume] 自动追一次最新流 */
    private var autoRetryOnNextResume = false

    /**
     * ★第十二批（**后台只出声**）：退后台时视频轨**是不是真的已经被关掉**了
     * （= 本页正处在"只有声音"的形态；判定权在 `LivePlayerDelegate.setAudioOnly()` 的返回值上）。
     *
     * ```
     * onStop()   ：live_background_play=开 且 离开前在播 且 不在 PiP
     *              → delegate.setAudioOnly(true)（关视频轨，解码器释放）
     *              → backgroundAudioOnly = 返回值
     * onResume() ：backgroundAudioOnly == true
     *              → delegate.setAudioOnly(false)（视频轨放回来，画面自己就回来了）
     *              → backgroundAudioOnly = false
     * ```
     * 三条不变量（改这个字段时一起看）：
     * ① **默认路径一个字节都不变**：`live_background_play` 默认关 → 这个字段永远是 false，
     *    [onStop] 走的就是原来那句 `delegate?.pause()`（见那一行）；
     * ② 它为 true 时，[onResume] 会把 [resumePlayIntent] 一并作废 —— 后台这一路**流一秒都没断**
     *    （只是关了视频轨），所以既不需要"回到前台追最新"那次重新取流，也不会白黑一下；
     * ③ **PiP 不进这条路**：进小窗不触发 [onStop]，小窗里照旧渲染视频（小窗 + 关视频轨 = 一块黑窗）。
     */
    private var backgroundAudioOnly = false
    /** 上一次自动追流的时刻（`SystemClock.elapsedRealtime()`；0 = 还没追过） */
    private var lastAutoRetryAtMs = 0L
    /** 自动追流的时间戳队列，用来算 [AUTO_RETRY_WINDOW_MS] 窗口内的次数预算 */
    private val autoRetryStamps = ArrayDeque<Long>()
    /** 预算用尽时只提醒一次（窗口滑过去、又有额度了会自动复位） */
    private var autoRetryBudgetWarned = false
    /** 看门狗里"连续缓冲"的起点（0 = 当前没在缓冲） */
    private var stallSinceMs = 0L
    /** 看门狗里"画面不再出新帧"的起点（0 = 还没开始盯；见 [checkLiveHealthOnce] 第三条判据） */
    private var frameStaticSinceMs = 0L
    /** 上一次看到的 `videoDecoderCounters.renderedOutputBufferCount`（-1 = 还没读到过） */
    private var lastRenderedFrameCount = -1
    /** 看门狗任务（随页面生命周期） */
    private var liveHealthJob: Job? = null

    /** 输入条失败提示自动收起的那个 Runnable（[onDestroy] 要撤掉，别让它抓住已销毁的页面） */
    private var inputErrorHideRunnable: Runnable? = null

    /** 弹幕是否正在发送（防连点；与 `LiveDanmakuClient` 内部那道闸门是两层保险） */
    private var danmakuSending = false

    /**
     * 用户是否**手动钉过方向**（底栏「旋转」按钮 [toggleOrientation]，或横屏按返回退出全屏
     * [exitFullscreenToPortrait] —— 两个入口共用它）。
     * 置位后 [applyAutoRotatePolicy] 不再顶掉他的选择 —— 否则"点了旋转/按了返回，手机一歪又转回去"。
     * ★它是**会话级**的：不写 DataStore，`live_auto_rotate` 设置键一个字节都不动；
     *   退出直播间再进 = 设置里的自动旋转照旧生效。
     *
     * ★★本轮（用户实测报的 bug）起，这个标记分**两档**，由 [pinOrientationByUser] 按设置置位：
     *
     * | 「自动旋转」 | 置位 | 谁解开 | 用户看到的行为 |
     * |---|---|---|---|
     * | **关** | 永久（本次会话） | 只有退出直播间 | 「旋转」是**唯一**的方向开关：按一次切一次；手机怎么转都不跟随 |
     * | **开** | 一次性 | **设备真的被转动**那一刻（[releaseOneShotOrientationHold]） | 这一次切换照做，之后**继续跟随** —— 不再"点一次就永久锁死" |
     *
     * ★旧行为（本轮修掉的 bug）：不管设置是开是关都**永久**置位，而且**全文件没有任何地方清除**
     *   （置位只有 [toggleOrientation] 与 [exitFullscreenToPortrait] 两处）——于是自动旋转=开时
     *   点一次「旋转」，[applyAutoRotatePolicy] 就在**每一次**转屏回调里提前 return，
     *   方向只剩「旋转」按钮能改（用户原话："除非你手动按那个旋转按钮才能切换方向"）。
     */
    private var orientationPinnedByUser = false

    /**
     * ★本轮：**「一次性方向钉住」的释放哨兵**（[deviceOrientationSentinel]）是否已经注册在
     * [SensorManager] 上。注册/注销一一对应（[armDeviceOrientationSentinel] /
     * [disarmDeviceOrientationSentinel]）—— 加速度计是**系统服务**，忘记注销会一直抓着本页实例。
     */
    private var orientationSentinelRegistered = false

    /**
     * 钉住那一刻的设备姿态档（[deviceOrientationBucket] 的返回值；
     * `null` = 还没读到一帧可信的 —— 比如手机正平放在桌上，那时横竖本来就读不出来）。
     */
    private var orientationSentinelBaseline: Int? = null

    /** 连续读到的**新档**（候选；攒够 [DEVICE_BUCKET_CONFIRM_SAMPLES] 帧才算"用户真的转了手机"） */
    private var orientationSentinelPendingBucket: Int? = null
    private var orientationSentinelPendingSamples = 0

    // ══════════════════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★事件级诊断日志（2026-09-26，纯观测：只读已有字段，不改任何判定与顺序，见 [LivePageTrace]）
        LivePageTrace.section(
            "LivePage.onCreate",
            "room" to (intent?.getStringExtra(EXTRA_ROOM_ID) ?: "-"),
            "savedState" to (savedInstanceState != null),
            "configOrientation" to resources.configuration.orientation,
            "landscape" to isPageLandscape(),
            "pip" to isInPictureInPictureMode,
        )

        // ★「回 App 仍停在直播间」的**确定性**实现（2026-09-26 本轮，见 [LiveLastRoomStore] 的 KDoc）：
        //   登记"多了一个活着的直播间实例"。放在最前面 —— 本页后面任何一条提前 return
        //   （没拿到房间号 / 命中同房间复用）都由 [onDestroy] 把它减回去，计数天然平衡。
        LiveLastRoomStore.onLivePageCreated(applicationContext)

        // 播放页恒为黑底全屏：盖掉主题里的 splash 背景，避免起播瞬间白/彩闪一下
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // ★第七批：输入条要弹键盘，这里显式声明"输入法可以改变窗口大小"。
        //   两个理由：
        //   ① `AndroidManifest.xml` 里本页**没有** `windowSoftInputMode`（MainActivity 有），
        //      而本页的输入法行为必须自己负责（本页不归 Manifest 那一行管，且本轮不改 Manifest）；
        //   ② 在 Android 15 以下（没被强制 edge-to-edge 的系统）上，ADJUST_RESIZE 就是
        //      "键盘弹出 → 窗口变矮 → 贴底的底栏被顶到键盘之上"这条老路；
        //      在 Android 15+（targetSdk 35+ 强制 edge-to-edge）上它不再缩窗口，
        //      改由 `Type.ime()` insets 自己抬底栏 —— 见 [refreshBottomBarInsets]。
        //   ★`STATE_UNCHANGED`：进页面时**不要**主动弹键盘（输入条随控制条一起出现，
        //     但焦点不在它身上 —— 用户点一下它才弹，接线见 [buildUi]）。
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED,
        )
        // ★沉浸式策略的统一入口（幂等，见 [syncImmersivePolicy]）：进页面第一帧先把系统栏
        //   按"当前形态"定下来（此刻尺寸还没量出来，[isPageLandscape] 会退回 configuration 兜底）。
        syncImmersivePolicy()

        rawRoomId = readRoomIdFromIntent().orEmpty()
        if (rawRoomId.isBlank()) {
            // ★诊断日志（只读）
            LivePageTrace.note("onCreate.abort", "reason" to "blankRoomId")
            toast("没有拿到房间号")
            finish()
            return
        }

        // ★「已经在看同一个房间时不再新开」（防堆栈的第 2 条，见 [reuseExistingSameRoomInstance]）：
        //   命中就直接把老实例拉回前台并结束自己 —— 不建第二个 ExoPlayer、不重取流、不双声。
        if (reuseExistingSameRoomInstance()) return

        // 关掉上一个实例（防双开双声）
        currentInstance?.takeIf { it !== this && !it.isFinishing }?.finish()
        currentInstance = this

        // ★先把「设置 → 直播设置」里那两个**会话初值**定下来（都是主线程 O(1) 读内存快照，见
        //   `SettingPreferences.liveSettings()`）：
        //   · 默认画质 → [requestedQn]：起播时请求哪一档（默认"最高可用"= 原画，和改动前一致）；
        //   · 显示弹幕 → [danmakuEnabled]：进房间要不要连弹幕服务器（默认开）。
        //   放在 buildUi() 之前，「弹幕开/关」「清晰度」这些按钮第一次渲染出来就是对的。
        //   ★「默认线路策略 / 自动重连」不在这里读：它们要交给播放核心，
        //     在 delegate 建好那一刻由 [applyPlaybackPolicyToDelegate] 统一下发（见 setupPlayerAndDanmaku）。
        requestedQn = defaultRequestedQn()
        danmakuEnabled = SettingPreferences.liveSettings().danmakuEnable
        // ★主题三色在 buildUi() **之前**取好：按钮底色/状态文字/进度条第一次渲染出来就是主题色，
        //   否则会先按白色画一遍再被 applyThemeColors() 刷成主题色（第一帧闪一下）。
        resolveThemeColors()

        buildUi()
        // ★「自动旋转」（`live_auto_rotate`，默认开）在这里落地：见 [applyAutoRotatePolicy]。
        //   必须在 startResolveAndPlay() **之前** —— 方向定下来再起播，避免"先按竖屏量一次、
        //   起播后再转横屏"的窗口尺寸抖动（本页是 TextureView，尺寸变化会多一次 surface 重配）。
        //   放在 buildUi() 之后只是为了让"进房就请求方向"这件事和建 UI 挨在一起，便于阅读。
        applyAutoRotatePolicy()
        // ★PiP 动作接收器改为**随页面生命周期**注册（见 [registerPipActionReceiver] 的"为什么不再只在
        //   PiP 期间注册"）：早于任何一次可能的小窗进入，用户点按钮时它一定在。
        registerPipActionReceiver()
        // ★第七批：自动追流的看门狗（"直播落后/停滞"这个触发时机靠它，见 [checkLiveHealthOnce]）。
        //   随页面创建，[onDestroy] 里随 lifecycleScope 一起取消。
        startLiveHealthWatchdog()
        // ★诊断日志（只读）：进页面初始化完成时的形态与关键初值
        LivePageTrace.note(
            "onCreate.done",
            "room" to rawRoomId,
            "requestedQn" to requestedQn,
            "danmakuEnabled" to danmakuEnabled,
            "landscape" to isPageLandscape(),
            "pip" to isInPictureInPictureMode,
            "page" to (if (::rootLayout.isInitialized) "${rootLayout.width}x${rootLayout.height}" else "-"),
        )
        startResolveAndPlay()
    }

    /**
     * 「已经在看同一个房间 → 不再新开」：命中时把**已有实例**拉回前台，自己立刻退出。
     *
     * ## 为什么需要它（用户点名的"防堆栈/防死循环"第 2 条）
     * 「直播 → 空间 → 点直播中头像 → 回到原直播间」这条路上，最后一个动作又是
     * `startActivity(LivePlayerActivity)`。如果每次都新建实例，就要重复"重取流 + 黑屏 1~2 秒"，
     * 而且中间会短暂存在两个 ExoPlayer（双声/抢解码器）。上游的 `currentInstance` 兜底
     * （谁新建谁就把旧实例 finish 掉）能保证"不会真的叠层"，但那是**重建**不是**复用**。
     *
     * ## 三条防死循环的闸门（少一条都可能自激）
     * 1. **同房间**：房间号不同说明用户就是要换房间，交给原来的"关掉旧实例、正常新建"路径；
     * 2. **同任务**：`REORDER_TO_FRONT` 只能把**本任务内**的实例提到前台。
     *    进过 PiP 的直播页会被系统搬进一个独立的 pinned 任务（见 [ReturnToLiveGuard] 的 AOSP 引文），
     *    那种情况下 REORDER 会退化成"新建实例" → 新实例又走一遍本判断 → 无限套娃，所以任务不同一律不复用；
     * 3. **[EXTRA_REUSE_ATTEMPTED]**：复用这条路只试一次。万一 REORDER 没命中而生成了新实例，
     *    新实例看到这个标记就不再复用，直接走正常的"关旧建新"，同样收敛。
     *    ★第五批之前还有第 4 条（「UP主」跳转的入口去抖），那条链路连同按钮一起删了。
     *
     * @return true = 已复用并已 finish 自己（调用方必须立刻 return，别再碰任何 UI/播放器）
     */
    private fun reuseExistingSameRoomInstance(): Boolean {
        val existing = currentInstance ?: return false
        if (existing === this || existing.isFinishing) return false
        // "同一个房间"的判据：入口给的房间号相同，**或者**两边都已经解析出真实房间号且相等
        // （用户可能一次用短号 1、一次用真实号 5440 进同一个房间，那种情况同样该复用）
        val sameRoom = existing.rawRoomId == rawRoomId ||
            (realRoomId > 0L && existing.realRoomId > 0L && existing.realRoomId == realRoomId)
        if (!sameRoom) return false
        if (intent?.getBooleanExtra(EXTRA_REUSE_ATTEMPTED, false) == true) return false
        if (existing.taskId != taskId) return false
        val started = runCatching {
            startActivity(
                Intent(this, LivePlayerActivity::class.java)
                    // REORDER_TO_FRONT：本任务里已有实例 → 提到栈顶（**不新建**）
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(EXTRA_ROOM_ID, rawRoomId)
                    .putExtra(EXTRA_REUSE_ATTEMPTED, true),
            )
        }.isSuccess
        if (!started) return false
        // ★诊断日志（只读）：命中"同房间复用"，本实例马上 finish（回 App 落页问题的关键一步）
        LivePageTrace.note(
            "onCreate.reuseExisting",
            "room" to rawRoomId,
            "existingRoom" to existing.rawRoomId,
            "sameTask" to (existing.taskId == taskId),
            "reuseAttemptedBefore" to (intent?.getBooleanExtra(EXTRA_REUSE_ATTEMPTED, false) == true),
        )
        finish()
        return true
    }

    /**
     * 本页已经在栈顶时又收到一次启动（Manifest 里 `launchMode="singleTop"`）。
     *
     * 两种情况：
     * - **同房间**（复用路径，带 [EXTRA_REUSE_ATTEMPTED]）：什么都不做，本页该播的还在播 ——
     *   这正是"回到原直播间"想要的效果（不重取流、不黑屏、不换播放器）；
     * - **换了房间**：`recreate()` 让本页带着新 Intent 重建一遍。
     *   ★为什么不在这里"就地换房间"：那要把 delegate / 弹幕宿主 / 音频服务 / 轮询 / 各种状态
     *   逐个拆干净再重建，漏一处就是脏状态；`recreate()` 走的是**同一条 onCreate 初始化路径**，
     *   不会出现"两条换房逻辑各修各的"。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 复用路径来的（同房间被 REORDER_TO_FRONT 拉回前台）：什么都不做，继续播原来那一路流。
        // ★判据用这个 extra 而不是"房间号字符串相等"：复用允许"短号 1 → 真实号 5440"这种写法，
        //   字符串是不同的，但房间其实是同一个（见 [reuseExistingSameRoomInstance]）。
        if (intent.getBooleanExtra(EXTRA_REUSE_ATTEMPTED, false)) return
        // ★诊断日志（只读）
        LivePageTrace.note(
            "onNewIntent",
            "reuseAttempted" to false,
            "newRoom" to (intent.getStringExtra(EXTRA_ROOM_ID) ?: "-"),
            "curRoom" to rawRoomId,
        )
        val newRoomId = intent.getStringExtra(EXTRA_ROOM_ID)?.takeIf { it.isNotBlank() } ?: return
        if (newRoomId == rawRoomId) return
        LivePageTrace.note("onNewIntent.recreate", "newRoom" to newRoomId, "oldRoom" to rawRoomId)
        recreate()
    }

    override fun onStart() {
        super.onStart()
        pageStarted = true
        // ★诊断日志（只读）
        LivePageTrace.note(
            "onStart",
            "room" to rawRoomId,
            "danmakuEnabled" to danmakuEnabled,
            "host" to (danmakuHost != null),
            "landscape" to isPageLandscape(),
        )
        // 回到前台：恢复弹幕连接（播放本身不自动续播，由用户点"播放" —— 见 onStop 注释）
        if (danmakuEnabled) danmakuHost?.start()
    }

    override fun onResume() {
        super.onResume()
        // ★诊断日志（只读）：进入 onResume 时的"回前台恢复"全部输入
        LivePageTrace.note(
            "onResume.enter",
            "room" to rawRoomId,
            "pip" to isInPictureInPictureMode,
            "pipEntryPending" to pipEntryPending,
            "backgroundAudioOnly" to backgroundAudioOnly,
            "resumePlayIntent" to resumePlayIntent,
            "autoRetryOnNextResume" to autoRetryOnNextResume,
            "isPlaying" to (delegate?.isPlaying == true),
            "delegate" to (delegate != null),
            "landscape" to isPageLandscape(),
        )
        // ★第十一批第 1 条：**回到前台再同步一次沉浸式**（幂等，见 [syncImmersivePolicy]）。
        //   两个场景都靠它兜底：① 从别的 App 回来（系统栏可能在这期间被别的窗口/ROM 改过）；
        //   ② 从 PiP 小窗放大回全屏（小窗里的系统栏不归本页管，回到全屏必须重新按真实尺寸收敛）。
        //   放在 `super.onResume()` 之后、下面那条 PiP 提前返回**之前**：
        //   PiP 中不会有 onResume（官方行为），真进来了函数内部也有 PiP 门控，是个空操作。
        syncImmersivePolicy()
        // ★本页**全屏可见了** = 用户自己把直播间找回来了（点小窗放大 / 系统把小窗展开 /
        //   他本来就在看直播）→ 解除「回 App 仍停在直播间」的武装，守卫什么都不用做。
        //
        // ★为什么解除动作放在这里、而不是 onPictureInPictureModeChanged(false)：
        //   小窗被系统收掉（或被 ROM 顺手关掉）时，那个回调**也会先来一次 false**，
        //   紧接着系统就 finish 本页 —— 而那正是我们要救的场景（下次进 App 该回到直播间）。
        //   在那里解除武装等于自废武功。onResume 才是"人真的回到直播间"的可靠信号：
        //   PiP 里的 Activity 只到 onPause，不会 onResume（官方 PiP 行为），所以不会误解除。
        if (isInPictureInPictureMode) return
        // ★"即将进小窗"的标记也在这里清（这是唯一能证明"人真的回到全屏"的信号）：
        //   置位后若系统最终没进小窗（自动进入被 ROM 拒了 / 被别的 PiP 占着），
        //   页面一 resume 就该恢复正常行为，不能让控制条永远被那条标记压着不显示；
        //   而且既然"小窗没进成"，当时为进小窗收起来的控制条要**还给用户**。
        val wasPipEntryPending = pipEntryPending
        pipEntryPending = false
        // ★第七批：标记一清就要**重新求值一次可见性** —— 输入条的显隐门控读的就是它
        //   （见 [applyControlsVisibility]）。少了这一句，"以为要进小窗 → 把输入条收掉 →
        //   结果没进成"这条路上的输入条会**永远不再出现**（控制条本身是可见的，
        //   所以下面那条恢复分支也不会走）。
        if (wasPipEntryPending) applyControlsVisibility(controlsVisible)
        if (wasPipEntryPending && !controlsVisible) showControlsTemporarily()
        returnToLiveGuard.disarm()
        // ★第七批：**回到前台 = 自动追一次最新流**（用户："回到前台（onResume）……自动重新取流追到最新"）。
        //   判据是 [onStop] 留下的那个标记，而不是"onResume 被调用了" —— 后者在**进页面第一帧**也会来，
        //   那时 delegate 还没建出来/第一路取流正在飞，再追一次纯属重复请求。
        //   真的退过后台（onStop → onResume）才算"回到前台"，见 [autoRetryLiveStream] 的防抖与预算。
        // ★第十二批（后台只出声）：**先把画面接回来**。
        //   这一路后台期间流一秒都没断（只关了视频轨），所以这里只做一件事：re-enable 视频轨
        //   —— Surface 从头到尾没摘过，画面自己就回来了，**一个网络请求都不发**
        //   （用户要求："回前台不许重新取流，除非看门狗判定卡死"）。
        //   ★唯一例外：后台期间**真的断了**（playerError / 不在 READY|BUFFERING）——那就把
        //   [resumePlayIntent] 留给下面那条"回到前台按需追到最新"，恢复能力与改动前逐字一致，
        //   不会因为"关了视频轨"把一条已经断掉的流永远留在错误态里。
        //   ★两条看门狗采样起点一起清零：视频轨关了这么久，"一直缓冲 / 10s 没有新帧"都不该按故障算。
        if (backgroundAudioOnly) {
            backgroundAudioOnly = false
            delegate?.setAudioOnly(false)
            stallSinceMs = 0L
            frameStaticSinceMs = 0L
            lastRenderedFrameCount = -1
            val streamAlive = delegate?.player?.let { p ->
                p.playerError == null &&
                    (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
            } == true
            // 意图在后台已经兑现（一直在播）→ 作废，别让下面那句再重取一次流
            resumePlayIntent = !streamAlive
            // ★诊断日志（只读）：resumePlayIntent 的写入点之一
            LivePageTrace.note(
                "resumePlayIntent.write",
                "value" to resumePlayIntent,
                "source" to "onResume.backgroundAudioOnly",
                "streamAlive" to streamAlive,
            )
        }
        if (autoRetryOnNextResume) {
            autoRetryOnNextResume = false
            // ★诊断日志（只读）
            LivePageTrace.note(
                "onResume.autoRetryBranch",
                "resumePlayIntent" to resumePlayIntent,
                "pip" to isInPictureInPictureMode,
            )
            // ★2026-09-26 用户定的规则：**离开前什么意图，回来就是什么意图，且不重新取流**。
            //   原来这里是 `autoRetryLiveStream("回到前台")` —— 每次回前台都重新取一次流，
            //   用户看到"回来加载那么一下"，而且顶栏状态会和真实播放状态对不上。
            //   现在改成：离开时在播 → 继续播；离开时暂停 → 保持暂停（要用户自己点）。
            if (resumePlayIntent) {
                resumePlayIntent = false
                // ★诊断日志（只读）：消费掉"离开前在播"的意图 → 下面会走自动追流
                LivePageTrace.note(
                    "resumePlayIntent.write",
                    "value" to false,
                    "source" to "onResume.consume",
                    "next" to "autoRetryLiveStream",
                )
                // ★2026-09-26 用户补的第二条：直播是**流**，离开期间会落后 —— 回前台若原本在播，
                //   就应该**追到最新时间节点**（"重新刷到最新是没问题的，因为刷新已经自动化了"）。
                //   走 `autoRetryLiveStream`（自带防抖 20s + 5 分钟最多 3 次 + 尊重"自动重连"设置），
                //   它内部就是 `retryPlayback()`（清限流预算 + 重新取流 + 起播）→ 天然追到最新。
                //   · 原本在播 → 追流并继续播（可能有一瞬间加载，这是直播的正常代价）；
                //   · 原本暂停 → 上面这个分支不进，**保持暂停**，一个请求都不发（用户要的"暂停就是暂停"）。
                autoRetryLiveStream("回到前台：按需追到最新")
            }
            // else：保持暂停，什么都不做（`resumePlayIntent` 本来就是 false）
        }
    }

    /**
     * "离开 App 之前用户的播放意图"（2026-09-26 用户定的规则）。
     *
     * 用户原话："用户退出之前什么意图，就是继续什么意图。"
     *   · 退桌面时**在播** → 回前台**继续播**；
     *   · 退桌面时**暂停** → 回前台**还是暂停**（要用户自己点播放）；
     *   · 而且**不许重新取流**（所以 [onResume] 里原来那句"回到前台自动追流"被撤掉了；
     *     真的卡死由看门狗兜底：连续缓冲 / 10 秒没有新画面帧才追）。
     */
    private var resumePlayIntent = false

    override fun onStop() {
        super.onStop()
        // ★诊断日志（只读）
        LivePageTrace.note(
            "onStop.enter",
            "room" to rawRoomId,
            "isFinishing" to isFinishing,
            "isChangingConfigurations" to isChangingConfigurations,
            "pip" to isInPictureInPictureMode,
            "isPlaying" to (delegate?.isPlaying == true),
            "delegate" to (delegate != null),
            "autoRetryOnNextResume" to autoRetryOnNextResume,
        )
        // ★「记住离开时的位置」的**兜底记录点**（见 [LiveLastRoomStore] 的 KDoc）：
        //   真退到后台（进 PiP 不走 onStop；正常退出/重建由下面两个判据挡掉），
        //   且此刻进程里没有别的前台页面 → 记下这个直播间（onUserLeaveHint 通常已经记过一次，
        //   这里覆盖熄屏 / 来电 / 被别的 App 抢前台等**不走 hint** 的路径，幂等）。
        if (!isFinishing && !isChangingConfigurations) {
            LiveLastRoomStore.onLivePageStopped(applicationContext, rawRoomId)
        }
        // ★必须在**动播放器之前**记下意图（第十二批起 onStop 不再是"无条件暂停"，见下）：
        //   这里记的是"退后台那一刻到底在不在播"——下面那条门控与 [onResume] 的恢复分支都读它，
        //   记晚了就永远是 false 了。进 PiP 不会走 onStop（PiP 只到 onPause），所以这条只代表"真退到后台"。
        resumePlayIntent = runCatching { delegate?.isPlaying == true }.getOrDefault(false)  // isPlaying 是属性（不是方法）
        // ★诊断日志（只读）：resumePlayIntent 的写入点之二（"退后台那一刻到底在不在播"）
        LivePageTrace.note(
            "resumePlayIntent.write",
            "value" to resumePlayIntent,
            "source" to "onStop",
            "delegate" to (delegate != null),
        )
        pageStarted = false
        // ★"即将进小窗"的标记在这里也清一次：真的进了 PiP 的本页**不会走 onStop**（PiP 只是 onPause），
        //   所以能走到这里就说明"置位之后并没有进成小窗"（划走被 ROM 拦下、熄屏但没进 PiP…）。
        //   不清的话下一次 onConfigurationChanged 的 showControlsTemporarily() 会被它挡掉，
        //   用户回到页面会发现点一下才出控制条。
        //   ★第七批：同上，清完要重新求值一次（输入条的门控读它）。
        val wasPipEntryPending = pipEntryPending
        pipEntryPending = false
        if (wasPipEntryPending) applyControlsVisibility(controlsVisible)
        // ★本轮：真退到后台，就把"一次性方向钉住"一并交还给自动旋转（[releaseOneShotOrientationHold]）。
        //   两个理由：① 页面都不可见了，没必要让加速度计还在后台按 5Hz 跑着等"用户转手机"；
        //   ② 用户回来时本来就该是"跟随"（自动旋转=开），不该把一个几分钟前的钉住原样带回来。
        //   ★只影响"自动旋转=开"那一档：=关时它在函数里被判掉（那是"唯一的方向开关"，永久有效）。
        //   ★PiP 不走 onStop（只到 onPause），小窗里的释放由哨兵自己那条 PiP 早退兜住。
        releaseOneShotOrientationHold("onStop")
        danmakuHost?.stop()
        // 退后台时把手势提示收掉：气泡是页内 View，留着会在回来时"凭空亮着"（旧版是 Dialog 窗口，
        // 会有同样的观感问题，只是成因不同 —— 它会在回来时重新淡入一次）
        gestureHud.hide()
        // ★退到后台要做什么，由「设置 → 直播设置 → 后台继续直播」
        //   （`SettingPreferences.LiveBackgroundPlay`，键 `live_background_play`，**默认关**）决定。
        //   注意：进 PiP **不会**触发 onStop（PiP 里只是 onPause），所以画中画照常播。
        //
        // ★★第十二批（本轮）：「后台继续直播 = 开」时不再是"留着一路视频在后台烧解码"，而是**只出声** ——
        //   退后台就把**视频轨关掉**（`delegate.setAudioOnly(true)`：轨道选择器不再选视频轨 →
        //   视频渲染器 disabled → `MediaCodecRenderer.onDisabled()` → MediaCodec 释放，
        //   **真省一份视频解码**）；Surface 不摘、播放器不 release、**一个字都不重新取流**，
        //   回前台在 [onResume] 里把视频轨放回来即可（画面/声音都不换源）。
        //   机制细节与"为什么只有这一种写法真省"写在 `LivePlayerDelegate.setAudioOnly()` 的 KDoc 上。
        //   · `live_background_play` 默认 false ⇒ 下面 else 那一句与改动前**逐字一致**（退后台 = 暂停）；
        //   · PiP 里不走这条路（小窗的契约就是继续看画面，关掉视频轨只会得到一块黑窗）；
        //   · 暂停中退后台也不走（本来就不解码，而且不能把用户的"暂停"变成"继续播"）。
        val keepPlayingInBackground = backgroundPlayEnabled()
        // ★诊断日志（只读）：后台策略的三个输入
        LivePageTrace.note(
            "onStop.backgroundPolicy",
            "keepPlayingInBackground" to keepPlayingInBackground,
            "pip" to isInPictureInPictureMode,
            "resumePlayIntent" to resumePlayIntent,
            "systemBarBottomInset" to systemBarBottomInsetPx,
        )
        if (keepPlayingInBackground && !isInPictureInPictureMode && resumePlayIntent) {
            // 关不掉时（例如这条流一条可用音频轨都没有）delegate 返回 false，本页**不暂停**：
            // 用户开的就是"后台继续直播"，那就照旧播（只是照旧解码视频）—— 与旧「听音频」实现的兜底同义。
            backgroundAudioOnly = delegate?.setAudioOnly(true) ?: false
        } else {
            delegate?.pause()
        }
        // ★诊断日志（只读）：后台策略的**结果**（audioOnly=true 表示真的只关掉了视频轨）
        LivePageTrace.note(
            "onStop.backgroundResult",
            "backgroundAudioOnly" to backgroundAudioOnly,
            "action" to if (backgroundAudioOnly) "audioOnly" else "pauseOrNothing",
        )
        // ★第七批：真的退过后台（PiP 不走这里）→ 下次 [onResume] 自动追一次最新流。
        //   只在实际起过播（delegate 已建）时置位，免得"进页面还没起播就被切走"那种情况也去追。
        if (delegate != null) autoRetryOnNextResume = true
        // ★诊断日志（只读）：onStop 结束时的"回前台要做什么"标记
        LivePageTrace.note(
            "onStop.done",
            "autoRetryOnNextResume" to autoRetryOnNextResume,
            "resumePlayIntent" to resumePlayIntent,
            "backgroundAudioOnly" to backgroundAudioOnly,
        )
    }

    override fun onDestroy() {
        // ★诊断日志（只读）
        LivePageTrace.note(
            "onDestroy.enter",
            "room" to rawRoomId,
            "isFinishing" to isFinishing,
            "isChangingConfigurations" to isChangingConfigurations,
            "page" to (if (::rootLayout.isInitialized) "${rootLayout.width}x${rootLayout.height}" else "-"),
        )
        pollJob?.cancel()
        danmakuStateJob?.cancel()
        // ★本轮：弹幕流里的"直播状态"订阅也要收（它抓着 delegate 与本页实例）
        liveSignalJob?.cancel()
        liveHealthJob?.cancel()
        mainHandler.removeCallbacks(hideControlsRunnable)
        // ★第九批：那条"几何落定后重推弹幕列表"的任务也要撤（它抓着本页的 View 与弹幕宿主）
        mainHandler.removeCallbacks(liveListGeometrySettleRunnable)
        // ★第十批：退出小窗后那次"PiP 参数补发"也要撤（同上，它抓着本页的 window）
        mainHandler.removeCallbacks(pipParamsResendRunnable)
        // ★输入条那条"6 秒后自动收起失败提示"的任务也要撤（它抓着本页的 View）
        inputErrorHideRunnable?.let { mainHandler.removeCallbacks(it) }
        // ★2026-09-26 用户实测：在点播页 → 回桌面 → 回软件，被拉回了直播 Tab。
        //   根因是**本页已经销毁了，但"回 App 仍停在直播间"的守卫还武装着**，主界面一 resume
        //   它就按记下的房间号把直播间又开出来。页面没了就该解除 —— 守卫只对"活着并进了小窗"的
        //   本页有意义（[exitPage] 那条路已经解过一次，这里是兜底：被系统回收/异常销毁也解）。
        returnToLiveGuard.disarm()
        // ★★「记住离开时的位置」（2026-09-26 本轮，见 [LiveLastRoomStore] 的 KDoc）：
        //   与上一行的旧守卫**刻意相反** —— 记录**必须活过本页的销毁**。
        //   根因（真机任务栈实测）：点桌面图标回 App 时，系统会先把 PiP 任务里的本页 finish 掉，
        //   再让主界面 resume；"本页没了"恰恰是要救的场景，而不是该放弃的场景。
        //   记录只在三种情况下作废（都在 [LiveLastRoomStore] 里）：
        //   · [exitPage] 用户主动退出；· App 从**非直播间**页面退到后台（点播页那条红线）；
        //   · 直播间自己回到全屏前台（人已经在里面了）。
        //   本方法顺带做两件事：直播间实例计数 -1，以及**再判一次**"该不该恢复"
        //   （这样"PiP 小窗被系统收掉"与"主界面 resume"谁先谁后都恰好恢复一次）。
        LiveLastRoomStore.onLivePageDestroyed(applicationContext)
        inputErrorHideRunnable = null
        gestureHud.hide()
        // ★第十三批：手势音量的后台写入线程随页面一起收掉（每进一次直播间一个本页实例，
        //   不收就是漏一条线程）。`quitSafely` 会把已经投递进去的那一档写完再退 ——
        //   所以"抬手就退出直播间"也不会丢最后那一档音量（[StreamVolumeWriter]）。
        volumeWriter?.shutdown()
        volumeWriter = null
        // ★PiP 动作接收器注销（它现在是随页面注册的，所以注销点就是这里 —— 一一对应，不会漏）
        unregisterPipActionReceiver()
        // ★本轮：方向释放哨兵的加速度计监听也要跟着页面一起收（同样一一对应）——
        //   加速度计是**系统服务**，不注销它就一直抓着本页实例，退出直播间也回收不掉。
        disarmDeviceOrientationSentinel()
        streamDialog = null
        // ★本轮：「主播已下播」那个一次性提示随页面一起收（它是本页建的 AlertDialog 窗口）
        dismissLiveOfflineDialog()
        // ★第十四批：直播设置弹窗的宿主也要释放（ComposeView 一 detach 就会 dispose 那份组合，
        //   弹窗的 `Dialog` 窗口随之收掉 —— 页面没了弹窗还挂在屏幕上是不可能发生的）。
        liveSettingSheetHost?.release()
        liveSettingSheetHost = null
        danmakuHost?.release()
        danmakuHost = null
        danmakuClient = null
        delegate?.release()
        delegate = null
        if (currentInstance === this) currentInstance = null
        super.onDestroy()
    }

    /**
     * ★转屏 / 进出 PiP / 分屏 / 深浅色切换都**不会**重建本 Activity（Manifest 里声明了
     * `configChanges=orientation|screenSize|...|uiMode`，见 AndroidManifest.xml:87）。
     *
     * 这正是"弹窗旋转后不适配"的病根：**窗口/版式几何只在创建那一次算过**。
     * 点播播放器那边为同一个病写过一次解药（`widget/player/DanmakuVideoPlayer.kt:3120-3134`
     * `applyGestureDialogGeometry()`："横竖屏切换 / 分屏 / 折叠屏展开后，气泡仍停在旧方向的
     * 尺寸和旧坐标上（甚至被甩到屏幕外）"）——这里把同一套结论用在直播页的**每一个**按像素布局的东西上：
     * 竖屏版式与两个矩形（[applyVideoStageLayout] / [measurePortraitStage]）、
     * 手势提示气泡（[GestureHud.applyGeometry]）、底栏换行（[syncBottomBarLayout]）、
     * 主题色重算（[applyThemeColors]，uiMode 变化时深浅色判定会变）。
     * 清晰度/线路/发弹幕这些**正常弹窗**（MaterialAlertDialog）不在此列：几何交给系统按当前方向算，
     * 我们再伸手 setLayout 反而会把它们顶出屏幕。
     *
     * ★★第四批第 1 条（**`newConfig.orientation` 不再参与分行/版式判断**）：
     *   进 PiP 时系统会带着"小窗尺寸"的配置回调这里，而小窗的方向是**窗口**的方向、不是设备的方向
     *   （项目里点播早就写下过这条结论：`PlayerController.updatePlayerMode()` 的注释
     *   "画中画：窗口方向 ≠ 设备方向，别按它推导"，并用 `isPicInPicMode` 把它挡掉）。
     *   16:9 小窗在竖屏手机上 = LANDSCAPE → 旧代码把底栏按横屏规则重排成"一行 10 颗"，
     *   每格宽度只剩竖屏的一半，`autosize` 把字缩到下限；退出 PiP 时底栏又不重建 → 字号永久变小。
     *   ★第五批补记：这一批的修法**不够**（用户复测仍变小），字号已改成确定性档位，见下面第五批第 4 条。
     *   现在这里只调 [syncPageLayoutToRealSize]，方向由**真实布局尺寸**说话（[isPageLandscape]）；
     *   `newConfig` 只在页面还没量过尺寸时当兜底，而"尺寸真的变了"那一刻还有
     *   [installPageLayoutWatchers] 兜住。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // ★诊断日志（只读）：转屏/分屏/PiP 的配置回调（★这一行读到的还是**旧尺寸**，收敛点是尺寸监听）
        LivePageTrace.note(
            "onConfigurationChanged",
            "orientation" to newConfig.orientation,
            "landscape" to isPageLandscape(),
            "page" to (if (::rootLayout.isInitialized) "${rootLayout.width}x${rootLayout.height}" else "-"),
            "video" to LivePageTrace.rect(
                if (::videoContainer.isInitialized) {
                    Rect(videoContainer.left, videoContainer.top, videoContainer.right, videoContainer.bottom)
                } else {
                    null
                },
            ),
            "topBarBottom" to (if (::topBar.isInitialized) topBar.bottom else -1),
            "pip" to isInPictureInPictureMode,
            "pipEntryPending" to pipEntryPending,
        )
        // 转屏后系统栏会重新出现，沉浸式要再同步一次，否则上下会漏出状态栏/导航栏。
        // ★这一句只是"尽可能早"：配置回调到达时窗口**还没重新布局**，[isPageLandscape] 读到的
        //   仍是旧尺寸（横屏转竖屏时会被判成"还是横屏"）；真正的收敛点是
        //   [installPageLayoutWatchers] 那只尺寸监听 —— 尺寸一落定它就会再同步一次（幂等）。
        syncImmersivePolicy()
        // ★竖屏/横屏两套版式 + 底栏分行：按**真实尺寸**重排一次（理由见上面那段）。
        //   转屏回调的这一瞬间尺寸可能还是旧方向的，那就什么都不用做 —— 尺寸一变，
        //   [installPageLayoutWatchers] 里的监听器会带着新尺寸再进来一次（幂等）
        syncPageLayoutToRealSize()
        // ★第七批：换方向后"无键盘时的页高"这个基准**整个变了**（竖屏 2800 ↔ 横屏 1264），
        //   所以要把它清掉重记 —— 否则"键盘还开着时转屏"会用竖屏的基准去算横屏该让多少，
        //   算出来是个巨大的"已经被让掉了"，输入条就不再抬起来（被键盘盖住）。
        //   清零后 [refreshBottomBarInsets] 会用"页高 vs 整屏高"里更大的那个当基准，
        //   两种世界（窗口缩了/没缩）都正好只抬一次，见那段 KDoc 的算账。
        pageHeightWithoutIme = 0
        refreshBottomBarInsets()
        // ★气泡几何按新方向重算：气泡在页内、跟着视频带走，转屏后必须重算一次
        gestureHud.applyGeometry()
        // ★主题色重算：深浅色切换会走到这里（uiMode 在 configChanges 名单里）
        resolveThemeColors()
        applyThemeColors()
        // ★自动旋转开着时，方向策略再断言一次（手动点过「旋转」的话它已经被钉成固定方向，
        //   这里只在"当前策略与设置一致"时才写，避免把用户的手动选择顶掉）
        applyAutoRotatePolicy(preserveManualChoice = true)
        // 画面比例没变，但 PiP 的源矩形提示按新方向要重算（舞台位置变了）
        updatePipParams()
        // ★★第五批第 3 条：**PiP 相关的配置回调里绝不能把关掉的控制条再打开**。
        //   进小窗的时序是"窗口先缩 → 配置变更回调到这里 → onPictureInPictureModeChanged(true)"，
        //   而这条回调末尾原来那句 `showControlsTemporarily()` 会把刚收起来的顶栏/底栏重新显示出来，
        //   一直挂到 4 秒的自动隐藏计时到点 —— 用户看到的就是"PiP 里那 1~2 秒被底栏和顶栏挡住"，
        //   同时底栏按钮被按**小窗宽度**测量了一遍（返回全屏后字号变小的直接来源之一）。
        //   现在统一走 [showControlsTemporarily]（内部按 [controlsAllowed] 门控），PiP 期间它什么都不做。
        showControlsTemporarily()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // ★诊断日志（只读）：用户确实要离开本页（"回 App 恢复"的主记录点就在下面）
        LivePageTrace.note(
            "onUserLeaveHint",
            "room" to rawRoomId,
            "pipOnBackground" to pipOnBackgroundEnabled(),
            "isPlaying" to (delegate?.isPlaying == true),
            "pip" to isInPictureInPictureMode,
        )
        // ★★「记住离开时的位置」的**主记录点**（2026-09-26 本轮，见 [LiveLastRoomStore] 的 KDoc）：
        //   `onUserLeaveHint` 是"用户确实要离开本页"最早、最准的信号（本页下面那段也是在用它）。
        //   记下"上次停在哪个直播间"→ 用户再回到软件时，把直播间开回来（不再依赖旧守卫的时机）。
        //   ★必须放在下面那两处提前 return（`!pipOnBackgroundEnabled()` / 已在 PiP）之前：
        //     "关掉了自动进小窗"或"已经在小窗里"的用户同样要能"回 App 还在直播间"。
        //   ★手动点「画中画」按钮**不走这里** —— 那条路用户并没有离开 App（主界面就在小窗后面），
        //     不该记、也不该在回来时被恢复打扰。
        LiveLastRoomStore.onLivePageLeavingApp(applicationContext, rawRoomId)
        // ★「设置 → 直播设置 → 退后台自动进小窗」（`live_pip_on_background`，默认开）：
        //   关掉它的用户明确表示"切走就别再挂个小窗"，此时连 31 以下的兜底分支也不走。
        if (!pipOnBackgroundEnabled()) return
        // 用户按 Home/切走且正在播：自动进画中画（直播最自然的后台形态）
        //
        // ★Android 12(31)+ 走**系统自动进入**：`setAutoEnterEnabled(true)` 已经注册好，
        //   划走/熄屏时系统**立即**把窗口缩成 PiP 并且是"无缝"的（没有那段先缩小再移动的过渡动画）。
        //   此时再手动 enterPictureInPictureMode() 反而会打断那次无缝动画，所以这里只在
        //   31 以下兜底（minSdk 24，所以这条分支在真机上仍然有用）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ★★第五批第 3 条：31+ 的自动进入**没有我们能插手的"进入前"回调**（系统直接缩窗口），
            //   而 `onPictureInPictureModeChanged(true)` 又要等窗口缩完才来 —— 中间那 1~2 秒里
            //   控制条会明晃晃地挡在小窗上。所以在这里（`onUserLeaveHint` 是"用户确实要离开本页"
            //   的最早信号）就把控制条收掉、并撤掉自动隐藏计时，再用 [pipEntryPending] 把这段
            //   "过渡期"也关进 [controlsAllowed] 的门里。
            // ★这里**故意不判"在不在播"**：`setAutoEnterEnabled(true)` 对"暂停中"一样生效，
            //   而暂停时控制条本来就常显（`onPlayStateChanged(PAUSED)` → showControlsTemporarily）
            //   —— 只有在这里一起收掉，才能保证"进了小窗就一点控制条都不露"。
            if (!isInPictureInPictureMode) {
                pipEntryPending = true
            }
            // ★第七批：把输入条按门控收掉（小窗里多一条挡住画面毫无意义，
            //   而且 PiP 窗口里根本弹不出输入法）。这里只是**重新求值一次可见性**：
            //   传当前值不改变顶栏/按钮的显隐，只让 [applyControlsVisibility] 里那道
            //   "PiP / 即将进 PiP → 收起输入条"的门生效。
            applyControlsVisibility(controlsVisible)
            return
        }
        if (delegate?.isPlaying == true && !isInPictureInPictureMode) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // ★诊断日志（只读）：进/出小窗（此刻 `isInPictureInPictureMode` 已是新值 —— AOSP 先写后回调）
        LivePageTrace.note(
            "pip.modeChanged",
            "pip" to isInPictureInPictureMode,
            "orientation" to newConfig.orientation,
            "landscape" to isPageLandscape(),
            "page" to (if (::rootLayout.isInitialized) "${rootLayout.width}x${rootLayout.height}" else "-"),
            "video" to LivePageTrace.rect(
                if (::videoContainer.isInitialized) {
                    Rect(videoContainer.left, videoContainer.top, videoContainer.right, videoContainer.bottom)
                } else {
                    null
                },
            ),
            "pipEntryPending" to pipEntryPending,
            "isPlaying" to (delegate?.isPlaying == true),
            "controlsVisible" to controlsVisible,
        )
        // PiP 窗口里没有点按钮的空间：**顶栏与底栏一起收起来**（用户："PIP 模式下，我想让它隐藏那个
        // 底部按钮，还有顶部的状态栏各种信息按钮，因为它会挡住 PIP 的大部分视觉"）。
        if (isInPictureInPictureMode) {
            pipEntryPending = true
            // PiP 里误触手势会同时改系统音量和画面亮度，很难发现，直接收起手势层
            gestureHud.hide()
            // ★第十四批：**进小窗必须把直播设置弹窗收掉** —— 用户明确要求"PiP 里不弹"。
            //   主路径（点底栏「画中画」/ 退后台自动进）走 [enterPipMode] → [dismissDialogs]（已含它），
            //   但系统也可能**不经那条路**直接把本页缩成小窗（ROM/系统手势），所以这里再兜一次。
            //   幂等（[LiveSettingSheetHost.dismiss] 里有判断），没开着时零开销。
            dismissLiveSettingSheet()
            // ★第七批：小窗里**输入条必须收掉**（同时把键盘收掉、焦点清掉：PiP 窗口里输入法根本没法用，
            //   留着焦点只会在退出小窗时突然弹一次键盘）。
            //   ★第八批：它现在与那几颗按钮同一套显隐（[applyControlsVisibility]），这里传当前值只是
            //   "重新求值一次"，让"PiP → 输入条 GONE"那一档生效。
            dismissDanmakuInput()
            applyControlsVisibility(controlsVisible)
            // ★★第八批（用户实测第 4 条）：**进小窗立刻把版式切成"画面铺满"**——
            //   小窗里没人重排过版式（[syncPageLayoutToRealSize] 在 PiP 里是有意 return 的），
            //   不切的话竖屏那条 62% 的带子 + 全屏顶栏留下的 topMargin 会被原样带进小窗，
            //   画面只剩中间一小块、四周全是黑边。根因与证据见 [applyVideoStageLayout] 的 KDoc。
            //   ★必须在 `super.onPictureInPictureModeChanged(...)` 之后调：AOSP 是**先写**
            //   `mIsInPictureInPictureMode` 再回调本函数（`Activity.java:8580-8581`），
            //   所以这一行读到的 `isInPictureInPictureMode` 一定是 true → 走"铺满"那一支。
            //   ★它内部会 [runAfterPageLayout] 再量一次，量完顺带把 PiP 参数（源矩形）重下发一次
            //   （[measurePortraitStage] 末尾的 [syncPipParamsToVideoGeometry]）。
            applyVideoStageLayout()
            // ★小窗里不摆"竖屏弹幕列表"：宿主自己读 `Activity.isInPictureInPictureMode()` 判定，
            //   进/出小窗它都会靠 `onSizeChanged` 重新算矩形与显隐，播放页这里**不需要**再通知它
            // ★这里**不再**注册/注销 PiP 动作接收器（上一轮改）：接收器现在随页面生命周期存在
            //   （[onCreate] 注册 / [onDestroy] 注销）。旧写法把"按钮能不能用"押在**这个回调**上，
            //   一旦某条进小窗的路径没走到这里（系统自动进入被 ROM 改写、回调被吞、
            //   小窗期间 Activity 被重建），PiP 上的播放/暂停就永远收不到动作。见 [registerPipActionReceiver]。
            // ★武装「回 App 仍停在直播间」守卫（用户实测反馈）：
            //   进 PiP = 用户带着直播间离开了 App，而系统会把本页从原任务里**搬到一个新的 pinned 任务**
            //   （AOSP `RootWindowContainer#moveActivityToPinnedRootTask()` 的 singleActivity 分支，
            //   报告 §4 有真机任务栈证据）—— 用户再点桌面图标时落在主任务（MainActivity）= "上一层"。
            //   所以从这一刻起盯着"是不是有别的页面被拉回前台"，见 [ReturnToLiveGuard]。
            //   ★2026-09-26 本轮：这已是**兜底**；主路径 [LiveLastRoomStore] 的记录在上面
            //   [onUserLeaveHint] 里就写好了（那里更早，而且覆盖"没进 PiP"的路），本行只负责
            //   把守卫叫醒。守卫真正拉起前还会去问那个记录（`consumePendingForGuard`），
            //   两条路共用一份一次性记录 → 不会各开一个。
            returnToLiveGuard.arm(application, rawRoomId)
        } else {
            // ★回到全屏：解除"过渡期"标记，并把顶栏/底栏恢复出来。
            //   这里用 [applyControlsVisibility] 而不是 [setControlsVisible]：后者的门控读
            //   `isInPictureInPictureMode`，而这一行的**语义就是"已经出来了"**，不该再被门控二次判断。
            pipEntryPending = false
            applyControlsVisibility(true)
            // ★从 PiP 回到全屏：列表显隐由宿主按"竖屏 ∩ 非 PiP ∩ 视图可见"自己判定，
            //   这里不用推状态；但视频带/列表区的**几何**要按当前这一帧重排一次
            //   （小窗期间窗口尺寸与 insets 都变过，`measurePortraitStage` 的输入跟着变）。
            //
            // ★★字号那一条：这里**强制**把底栏分行/版式按恢复后的尺寸重排一次
            //   （force = true 忽略指纹 —— 指纹只记"方向/可见数/每行几个"，记不住"格宽"，
            //   而退出 PiP 恰恰可能出现"结构没变、格宽变了"的中间态）。
            //   ★字号本身不靠这一句保证：底栏每次布局都会由 [applyBottomBarTextSizes] 按**真实宽度**
            //   重算一遍（见那段 KDoc），所以无论这个回调与窗口恢复谁先谁后，字号都会收敛到同一档。
            runAfterPageLayout {
                syncPageLayoutToRealSize(force = true)
                measurePortraitStage()
                // ★★第十一批第 1 条：**退出小窗必须重排沉浸式**（用户："我在 PIP 进入软件，它也是隐藏的"）。
                //   必须放在 [runAfterPageLayout] 里、和版式同一帧：这一行的本意就是"窗口已经恢复了"，
                //   此刻 [isPageLandscape] 读到的才是**恢复后的真实尺寸**；直接写在外层会在
                //   "窗口还停在小窗尺寸（16:9 = 横屏）"的那一瞬间判错方向、又压一次 hide。
                //   （幂等，见 [syncImmersivePolicy]；另外 [onResume] 也会同步一次，双保险。）
                syncImmersivePolicy()
            }
            // ★这里**故意不解除**「回 App 仍停在直播间」的武装：见 [onResume] 的注释 ——
            //   小窗被收掉时这个回调也会来一次 false，紧接着本页就被 finish，
            //   而那正是要靠守卫把直播间拉回来的场景。解除只在"真的全屏可见"（onResume）时做。
            //
            // ★★第十批（本轮修复 ③）：**退出小窗不再无条件重取流**，改成"交给看门狗判健康"。
            //
            // ## 旧写法错在哪（这一处正是"为什么点播没这些屁事"的答案之一）
            // 旧代码在这里无条件 `autoRetryLiveStream("退出小窗回到全屏")` ⇒ `delegate.retry()`
            // ⇒ **重新取流 + 换 MediaSource + prepare**，而这三件事正好落在"点放大"的展开过渡里：
            // · 点播那条稳的链路在退出小窗时**一个网络请求、一次换源都没有**
            //   （`comm/delegate/player/PlayerDelegate2.kt:1654-1680`：只 hideController /
            //   恢复 fullScreenPlayer / 重读设置），所以它稳；
            // · 本页这一句把"自动追流"的额度用在**画面本来好好的**时候：成功 = 白黑一次
            //   （换源到出帧要等新地址的 CDN 首帧），失败（切网 / 限流 / 地址过期）= 直接进 ERROR
            //   ⇒ 用户看到的正是"有时没事、有时黑屏播放不了"；
            // · 它还和"小窗展开"的过渡动画抢窗口几何（见 [updatePipParams] 里"过渡期间不下发"）。
            //
            // ## 现在怎么做（用户"从 PiP 回全屏追到最新"这条要求一条没少）
            // 退出小窗只做两件事：① 记下"刚出小窗"的时刻（[pipExitedAtMs]，让 PiP 参数下发避开
            // 过渡动画）；② 清掉看门狗的采样起点，把"要不要追"交给 [checkLiveHealthOnce] ——
            // 它 5s 一次巡检，三条判据（连续缓冲 8s / 落后 15s / **10s 没有新视频帧**）任意一条成立
            // 才追一次，且共用同一个 20s 防抖与"5 分钟 3 次"的预算。
            // ⇒ 画面健康时一个字节都不发（不再黑屏）；画面真卡住/真落后时 **5~15 秒内自动追一次**
            //   （比"回到全屏立刻重取"慢几秒，但不会把好画面换成黑的，也不会白烧额度）。
            pipExitedAtMs = SystemClock.elapsedRealtime()
            stallSinceMs = 0L
            frameStaticSinceMs = 0L
            miaoLogger() info "[live] 退出小窗回全屏：不无条件重取流，交给看门狗判健康"
            // ★诊断日志（只读）：退出小窗只重置采样起点，是否追流交给看门狗
            LivePageTrace.note(
                "pip.exit.noRefetch",
                "watchdogSampleReset" to true,
                "settleMs" to PIP_EXIT_SETTLE_MS,
                "autoRetryBudgetStamps" to autoRetryStamps.size,
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 返回键分级（本轮新增）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 系统返回键与顶栏返回图标**共用**的一套分级语义。
     *
     * 用户原话："直播间全屏的时候，我按一下返回，应该是退出全屏的状态，返回到竖屏。
     * 如果是竖屏的状态返回的，应该是退出直播间。" 落成三行：
     * ```
     * 画中画（小窗）→ 退出直播间（[exitPage]）
     * 横屏（= 全屏）→ 退出全屏：切回竖屏，**留在直播间**（[exitFullscreenToPortrait]）
     * 竖屏          → 退出直播间（[exitPage]）
     * ```
     *
     * ## 为什么"横屏 = 全屏"
     * 本页恒为沉浸式全屏，横竖屏就是它的**两套版式**（竖屏 = 顶栏之下一条视频带 + 下面整块弹幕列表，
     * 横屏 = 整屏视频，见类注释"竖屏版式"那一段），所以"退出全屏"就落成"把方向切回竖屏"。
     * 判据用 [isPageLandscape]（**真实布局尺寸**，不是 `Configuration.orientation`）——
     * 与页面版式、弹幕宿主 `portrait = h > w` 同源，分屏/折叠屏/小窗下也不会判错。
     *
     * ## ★与「旋转」按钮 / 「自动旋转」设置如何不打架（本轮点名的细节）
     * 「自动旋转」开着（`live_auto_rotate`，默认**开**）时 [applyAutoRotatePolicy] 会把
     * `requestedOrientation` 断言成 [ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR]（跟随重力）。
     * 而"退出全屏"这一刻手机**物理上还横着**：只要随后任何一次 [onConfigurationChanged] 走到
     * [applyAutoRotatePolicy]，FULL_SENSOR 就会立刻把页面又转回横屏 —— 用户看到的是
     * "按了返回，闪一下又回全屏"。所以 [exitFullscreenToPortrait] 必须**两件事一起做**：
     * ① `requestedOrientation` = [ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT]
     *    （与底栏「旋转」按钮切竖屏用的是**同一个值**：允许 180° 翻转，但不跟随重力横过去）；
     * ② `orientationPinnedByUser` 置位 —— 把这个"用户当下明确要的方向"记进那个**会话级**标记，
     *    [applyAutoRotatePolicy] 见到它就提前返回（`preserveManualChoice`），不再用设置里的
     *    自动旋转覆盖用户的选择。少了②，①会被下一次转屏回调原样推翻。
     *    ★★本轮：置位这件事改走 [pinOrientationByUser]（按设置分两档）—— 自动旋转=**开**时它是
     *    **一次性**的：手机还横着的那一刻照旧钉得住（上面这条"闪一下又回全屏"依然修好），
     *    但**设备下一次真的被转动**就把方向交还给自动旋转（[releaseOneShotOrientationHold]），
     *    不再"按一次返回就永久不跟随"。自动旋转=**关**时两档合一，仍是永久钉住。
     * ★"临时"的边界（**没有**改设置）：整个动作一个字节都不写 DataStore ——
     *   `live_auto_rotate` 仍是用户设置里的那个值，钉住只活在**本页这次会话**里
     *   （与「旋转」按钮同一条生命周期：退出直播间再进 = 自动旋转照旧生效）。
     *   想再回全屏：点底栏「旋转」（[toggleOrientation] 也是同一套语义），或退出重进。
     * ★小窗里为什么直接退直播间：PiP 的窗口方向 ≠ 设备方向（见 [isPageLandscape] 的 KDoc），
     *   16:9 小窗在竖屏手机上也是"横"的 —— 按 [isPageLandscape] 判会把小窗误当成全屏去转方向；
     *   而且小窗里按返回的语义本来就是"关掉它"。
     *
     * ★入口：系统返回键（[onBackPressed]）与顶栏那颗返回图标（[buildUi]）都调这里 ——
     *   同一屏上的两个"返回"不该有两种语义（点播播放页 `VideoPlayerActivity.onBackPressed()`
     *   也是"全屏时返回 = 退出全屏"）。
     */
    private fun handleBack() {
        if (isInPictureInPictureMode) {
            exitPage()
            return
        }
        if (isPageLandscape()) {
            exitFullscreenToPortrait()
            return
        }
        exitPage()
    }

    /**
     * "退出全屏" = 把方向切回竖屏并**钉住**（为什么必须钉、钉多久，见 [handleBack]）。
     *
     * ★为什么用 [ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT] 而不是 `SCREEN_ORIENTATION_PORTRAIT`：
     *   与底栏「旋转」按钮 [toggleOrientation] 完全一致 —— 两个入口切出来的"竖屏"必须是同一个东西，
     *   否则会出现"用按钮转的竖屏能倒过来、用返回转的不能"这种说不清的差别。
     * ★这里**只动方向**：转屏不重建 Activity（Manifest 里 `configChanges` 含 `orientation|screenSize`，
     *   见 AndroidManifest.xml:87），播放/弹幕一秒都不会中断，也不需要重新取流。
     * ★控制条顺手亮一次（与 [toggleOrientation] 一样）：用户刚做过动作，得看见反馈；
     *   紧接着的 [onConfigurationChanged] 末尾也会再刷一次，幂等。
     * ★★本轮：钉多久不再由这里写死（原来是一行 `orientationPinnedByUser = true`，**永久**）——
     *   改走 [pinOrientationByUser]：自动旋转=开时它是**一次性**的（设备一动就交还给自动旋转，
     *   用户"按了返回退出全屏"的效果一点不少：那一刻手机还横着，"退出全屏"照旧钉得住），
     *   自动旋转=关时才是永久钉住。
     */
    private fun exitFullscreenToPortrait() {
        pinOrientationByUser()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        // ★诊断日志（只读）：返回键"退出全屏"= 切竖屏并钉住
        LivePageTrace.note(
            "orientation.exitFullscreen",
            "requestedOrientation" to requestedOrientation,
            "landscape" to isPageLandscape(),
            "pip" to isInPictureInPictureMode,
            "autoRotate" to autoRotateEnabled(),
        )
        showControlsTemporarily()
        // ★第十一批第 1 条：**"退出全屏"（系统返回键 / 顶栏返回图标）也要重排沉浸式** ——
        //   与 [toggleOrientation] 完全同一条理由、同一个做法（两个入口共用这套语义）。
        //   这里同步的这一次通常还是旧尺寸（手机物理上仍横着），紧接着的
        //   [onConfigurationChanged] 与尺寸监听会各再同步一次，最终按真实尺寸收敛（都幂等）。
        syncImmersivePolicy()
    }

    /**
     * 系统返回键（手势返回 / 三键导航 / `KEYCODE_BACK` / 遥控器返回都汇到这一个口）。
     *
     * ★为什么直接覆盖 `onBackPressed()`、而不是往 `onBackPressedDispatcher` 注册回调：
     *   与点播播放页 `VideoPlayerActivity.onBackPressed()`（:285-308）保持同一条写法 ——
     *   本页是**独立 Activity**、没有 Fragment 返回栈，弹窗（[LiveListDialog] / 发弹幕）都是
     *   **独立窗口**、自己先吃掉返回键，弹幕宿主 `LiveDanmakuOverlayHost` 里也没有 BackHandler。
     *   ★另一个方向的保险（已核对 androidx.activity 1.13.0 字节码）：`OnBackPressedDispatcher`
     *   只有在**存在已启用的回调**时才会往 `OnBackInvokedDispatcher` 注册平台回调
     *   （`OnBackInvokedInput.updateBackInvokedCallbackState(hasEnabledHandlers)`），
     *   本页一个回调都没注册 → 平台返回仍然走框架默认（`Activity.onBackPressed()`）→ 就是下面这个覆盖。
     *   将来若有 Compose 侧注册 BackHandler，它会**先**拿到返回（弹层先关），这是期望行为。
     * ★`onBackPressed()` 自 API 33 起被标记 deprecated，但仍是"没有注册回调"时的唯一入口；
     *   与工程里既有的点播播放页写法一致，等整体迁到 `OnBackPressedCallback` 时两个页面一起迁。
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    /**
     * 注册 PiP 动作接收器（幂等）。
     *
     * ## 为什么改成"随页面生命周期"（本轮）
     * 旧写法是"进 PiP 才注册、退出就注销"，它的隐含假设是"`onPictureInPictureModeChanged(true)`
     * 一定在用户点小窗按钮之前到达"。这条假设在下面几种情况下不成立，而**每一种的表现都是
     * "PiP 里的播放/暂停点不动"**：
     * · 31+ 的自动进入（`setAutoEnterEnabled(true)`）由系统在划走/熄屏瞬间完成，回调时序由 ROM 决定；
     * · 小窗期间本页被系统重建（低内存 / 任务搬迁）→ 新实例的 onCreate 不注册，回调也可能不再来；
     * · 部分 ROM 只在"窗口真的缩放完"之后才派发回调。
     * 注册成本几乎为零，而**只有本 App**能往这个接收器投递（`RECEIVER_NOT_EXPORTED` + 带包名的
     * 定向 action），所以"提前注册"不会给外部留下任何入口，却把上面三种失效路径一次性消掉。
     *
     * ★`ContextCompat.RECEIVER_NOT_EXPORTED` 为什么不会挡住 SystemUI 代发的 PiP 动作：
     *   `PendingIntent` 的语义是"被授权的另一方**以创建者（本 App）的身份**执行这次操作"
     *   （官方原文："you are granting it the right to perform the operation you have specified
     *   **as if the other application was yourself** (with the same permissions and identity)"），
     *   所以 SystemUI 调 `PendingIntent.send()` 时，AMS 眼里的发送方 UID 就是本 App 自己 ——
     *   "只收本 App 发的广播"这条限制**恰好**放行。ContextCompat 在 33+ 用平台标志、
     *   24~32 退化为"要求发送方持有本 App 的签名级动态权限"，两条路都指向同一个结论。
     *   ★反过来说：**不要**为了"试试能不能收到"改成 `RECEIVER_EXPORTED`——那会把上一轮专门关掉的
     *   坑重新打开（任何 App 只要知道 action 字符串就能遥控本页播放/暂停）。
     *   （点播那边 `PicInPicHelper.registerReceiverSafe()` 用的是同一组合，且已被真机验证过。）
     *
     * ★ContextCompat 会按 API 等级自动挑 registerReceiver 的重载（Android 14 起不写导出标志会直接抛
     *   IllegalArgumentException）。
     */
    private fun registerPipActionReceiver() {
        if (pipReceiverRegistered) return
        val filter = IntentFilter(ACTION_LIVE_PIP_CONTROL)
        try {
            ContextCompat.registerReceiver(
                this,
                pipActionReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            pipReceiverRegistered = true
        } catch (e: IllegalArgumentException) {
            // 重复注册 / ROM 拒绝：不崩、不提示（PiP 按钮失效远好过整页崩掉），只落一条日志
            miaoLogger() error "LivePlayerActivity 注册 PiP 接收器失败: ${e.message}"
        }
    }

    /** 注销 PiP 动作接收器（幂等；`unregisterReceiver` 对未注册的接收器会抛异常，所以必须 try） */
    private fun unregisterPipActionReceiver() {
        if (!pipReceiverRegistered) return
        runCatching { unregisterReceiver(pipActionReceiver) }
        pipReceiverRegistered = false
    }

    /**
     * 直播 PiP 的播放/暂停动作接收器。
     *
     * ★它只做一件事：操作**本页自己的** [LivePlayerDelegate]，然后刷新一次 PiP 动作图标。
     *   与点播没有任何共享状态（action 专属、Intent 带包名定向、requestCode 专属），
     *   所以"两个播放器同时在小窗里，点一次两个都动"这件事在直播这条路上不会再发生。
     *
     * ★为什么 Activity 处于 paused / PiP 状态**不影响**收广播：动态注册的接收器挂在
     *   **Activity 的 Context** 上，只要没调 `unregisterReceiver` 就一直在（广播投递只看
     *   "接收器是否注册 + 进程是否存活"，与 Activity 的 onPause/onStop 无关）；
     *   而 PiP 里的本页只是 paused（官方 PiP 行为：不进 onStop），进程更是稳定存活。
     *   —— 这一条是排查时**排除**掉的假设，不是修复点，记在这里免得下一轮又怀疑它。
     *
     * ★动作是"播放"还是"暂停"由 Intent 里的 [EXTRA_LIVE_PIP_CONTROL] **明写**，
     *   不靠 `isPlaying` 现判 —— 小窗里的图标有短暂过期（用户点了、状态还没回来），
     *   按图标语义执行才是用户点下去时看到的那件事（点播那边用 `control_type` 也是同一个道理）。
     */
    private inner class LivePipActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // ★只有"本页自己的 action"才处理：点播的 media_control / 别的 action 一律放过
            //   （两条通道彻底分开，见 ACTION_LIVE_PIP_CONTROL 注释）
            if (intent?.action != ACTION_LIVE_PIP_CONTROL) return
            when (intent.getIntExtra(EXTRA_LIVE_PIP_CONTROL, 0)) {
                LIVE_PIP_CONTROL_PLAY -> delegate?.play()
                LIVE_PIP_CONTROL_PAUSE -> delegate?.pause()
                else -> return
            }
            // 状态回调（delegate → onPlayStateChanged → updatePlayPauseButton）会顺带刷新图标，
            // 这里再显式刷一次是保险：万一状态没变（比如点了个已经是"播放"的动作），
            // 图标也必须是当前状态的样子，不能停在旧图标上"看着像失灵"
            updatePipParams()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 起播：解析房间 → 建播放器/弹幕 → 取流
    // ══════════════════════════════════════════════════════════════════════

    private fun readRoomIdFromIntent(): String? {
        intent?.getStringExtra(EXTRA_ROOM_ID)?.takeIf { it.isNotBlank() }?.let { return it }
        val asLong = intent?.getLongExtra(EXTRA_ROOM_ID, 0L) ?: 0L
        return if (asLong > 0L) asLong.toString() else null
    }

    /**
     * 先 `room_init` 拿**真实房间号**：
     * - 播放接口和弹幕接口都要真实号（短号只对 room_init 有效）；
     * - 顺手拿到 `live_status`，没开播就**不要**白调一次 getRoomPlayInfo，直接转轮询。
     */
    private fun startResolveAndPlay() {
        // ★诊断日志（只读）：解析房间 + 取流的**起**（结果见 stream.ready / play.state）
        LivePageTrace.note(
            "resolve.start",
            "room" to rawRoomId,
            "requestedQn" to requestedQn,
            "delegate" to (delegate != null),
        )
        lifecycleScope.launch {
            showLoading(true)
            setStreamStatus("正在解析房间…")
            val init = resolveRoom(rawRoomId)
            // ★不再把"房间信息"当进房门神（2026-09-26 用户实测：**所有**直播间都提示"获取直播信息失败"）。
            //   根因：`room/v1/Room/room_init` 被风控直接回 **HTTP 412**（批量实测 70 个直播房间 68 个 412），
            //   而 PiliPlus 全库 grep `room_init`/`Room/get_info` **零命中** —— 它只信 getRoomPlayInfo 的
            //   live_status + 有没有真的下发流。所以这里失败就**降级继续**：用原始房间号去取流，
            //   让最权威的那一步决定成败（拿不到流时下方会给出真实原因）。
            if (init == null) {
                miaoLogger() error "[live] resolveRoom 失败（多为 room_init 风控 412），降级用 rawRoomId=$rawRoomId 继续取流"
            }
            // ★第五批：这里原来还有一句 `anchorUid = init.uid`（底栏「UP主」按钮要靠它进用户空间）。
            //   按钮连同整条链路一起删了，`init.uid` 在本页**没有消费端**了，所以整句删掉。
            //   `LiveRoomInitInfo.uid` 这个实体字段保留 —— 它属于共用的网络实体，不归本页管。
            val fallbackRoomId = rawRoomId.toLongOrNull()
            if (init == null && fallbackRoomId == null) {
                showLoading(false)
                setStreamStatus("房间号无法识别")
                toast("房间号无法识别：$rawRoomId")
                return@launch
            }
            val effectiveRoomId = init?.room_id ?: fallbackRoomId!!
            // ★诊断日志（只读）：房间解析结果（init=null 多为 room_init 风控 412，会降级继续取流）
            LivePageTrace.note(
                "resolve.result",
                "rawRoomId" to rawRoomId,
                "initNull" to (init == null),
                "effectiveRoomId" to effectiveRoomId,
                "liveStatus" to (init?.live_status ?: -1),
            )
            setupPlayerAndDanmaku(effectiveRoomId)
            // ★只在"明确没在播"时才提前报未开播（原来只要 room_init 说不可播就直接 OFFLINE，
            //   而 room_init 本身现在经常 412/数据滞后 → 用户看到假"未开播"）。真正的判据交给取流结果。
            // ★★本轮（未开播与中途下播）：这一支现在做**三件事**，而且必须在 delegate.start() **之前**：
            //   ① 以**服务端实时状态**为准（[resolveRoom] = 现有降级链 room_init → getH5InfoByRoom → get_info）
            //      —— 进房这一刻 `live_status == 0` 就**不取流、不进播放态**（连一次 getRoomPlayInfo 都不发）；
            //   ② 走 [enterOfflineWait] 而不是只写一行状态文字：**锁定**"没在播"（自动追流/换线全部停），
            //      并起 45s 的"等待开播"轮询；
            //   ③ 弹一次"主播未开播"（[showLiveOfflineDialogOnce]，判据是"本次进房没播出过画面"）。
            //   ★列表里的旧状态**不许当真**：那一路（`HomeLiveContent`）只把 roomId 传进来、
            //     不传任何 live_status（见那里的 `toLiveRoom`），本页一律自己问服务端。
            if (init != null && init.live_status == 0) {
                // ★实测：轮播房 live_status=2 虽然接口 code=0，但 playurl_info 是 null，
                //   一条流都拿不到（方案 §1）—— 所以这里只把**明确的 0（未开播）**提前挡下，
                //   2（轮播）仍要放进去试一次（`LivePlayabilityJudge.shouldAttemptPlay` 的既有结论：
                //   只挡 0，其余放行让"取流"这最权威的一步决定）。
                LivePageTrace.note(
                    "offline.entry",
                    "room" to effectiveRoomId,
                    "liveStatus" to init.live_status,
                    "source" to "resolveRoom",
                )
                enterOfflineWait("主播未开播（等待开播…）")
                showLiveOfflineDialogOnce(init.live_status, "resolveRoom")
                return@launch
            }
            setStreamStatus("正在获取直播流…")
            delegate?.start(requestedQn)
        }
    }

    private fun setupPlayerAndDanmaku(realRoomId: Long) {
        this.realRoomId = realRoomId
        if (delegate == null) {
            delegate = LivePlayerDelegate(this, realRoomId, delegateListener).also {
                it.attachTextureView(videoView)
            }
            // ★「默认线路策略 / 自动重连」在这里下发（播放器刚建好、还没起播）：
            //   这两项管的是播放核心内部的自动恢复行为，必须在第一次 start() 之前就位，
            //   否则第一波失败仍会按旧策略走（改动后立即生效的那份在设置弹窗里再下发一次）。
            applyPlaybackPolicyToDelegate()
        }
        if (danmakuHost == null) {
            danmakuHost = LiveDanmakuOverlayHost(this, realRoomId).also { host ->
                // ★宿主只往 danmakuLayer 这个**已经占好位置**的容器里塞：
                //   弹幕在图层的第 1 层这件事由 buildUi() 末尾的 bringToFront() 序列钉死，
                //   与"宿主什么时候才建出来"无关（它必须等 room_init 拿到真实房间号）。
                danmakuLayer.addView(
                    host,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                bindDanmakuClient(host)
                // ★底栏「弹幕」按钮的初值必须取**实际生效**的可见性，而不是只读 live_danmaku_enable
                //   （B 路交接项）：可见性 = 点播三层开关 ∩ live_danmaku_enable ∩ 跟随/覆盖仲裁，
                //   只读一个键的话，用户关掉点播弹幕总开关时按钮会显示"弹幕开"却一条都没有，像按钮坏了。
                danmakuEnabled = host.currentSettings().visible
                if (danmakuEnabled) host.start()
                // ★把竖屏列表的两根**锚点**交给宿主（唯一接线口，见 [bindPortraitListArea]）：
                //   列表从此贴着"视频区底边 → 底栏顶边"这块矩形停靠，横屏/PiP/听音频由宿主自己收起。
                //   ★这里能直接调：宿主刚 addView 进 danmakuLayer，锚点 View（列表槽 / 底栏）
                //     早在 buildUi 里就建好了；宿主给锚点挂的是长驻的 OnLayoutChangeListener，
                //     下一次 layout 它会自己把矩形量准，不依赖这次调用的时机。
                bindPortraitListArea()
            }
        }
        updateDanmakuButton()
        updatePipParams()
        // ★该直播间关没关弹幕：**进房时判一次**（换房走 recreate()，天然重新判）。
        //   结论只影响三处 UI（输入条 / 竖屏列表 / 滚动弹幕），不碰取流与 WS 连接。
        probeRoomDanmakuPolicy(realRoomId)
    }

    /**
     * 判定"**这个直播间关没关弹幕**"（用户原话："某一些直播间，它是关闭了评论的那个选项……
     * 这个时候就不应该去显示那个发送弹幕的输入框，还有那个弹幕区域了"）。
     *
     * ## 时机与代价
     * 只在 [setupPlayerAndDanmaku] 里调一次（**每个房间一次**，换房 `recreate()` 后自然再判一次），
     * 不做轮询、不做重试：`getInfoByRoom` 按 IP 有风控（容器内直连实测很快转 `-352`），
     * 重试风暴只会把正常房间也一起判成"拿不到"（那正是要避免的）。
     * `roomDanmakuPolicy` 是阻塞的 HTTP 调用，所以整段包在 `Dispatchers.IO` 里 ——
     * 与 `resolveRoom` 同一套做法，主线程一个字节都不等。
     *
     * ## 失败/拿不到怎么办（★保守分支）
     * `policy.closed == null`（风控 -352 / 断网 / 解析失败 / 字段缺失）→ 走 [applyRoomDanmakuClosed]`(false)`，
     * 也就是**按"没关闭"处理**：输入框、列表、滚动弹幕全部照旧。绝不因为一次判定失败误关。
     *
     * ## 判定结果会落到哪三处
     * ① [danmakuInput] 不再可输入/可发送（原位留一行很轻的提示）——[applyRoomDanmakuClosed]；
     * ② 竖屏弹幕列表不显示、③ 滚动弹幕不渲染 —— `danmakuHost.setRoomDanmakuClosed(true)`
     *   （宿主里那两个门，见 `LiveDanmakuOverlayHost.setRoomDanmakuClosed`）。
     * ★WS 连接**不动**：`active` 与 `settings.visible` 两个门一个都没改，心跳/在线人数照旧。
     */
    private fun probeRoomDanmakuPolicy(realRoomId: Long) {
        roomDanmakuPolicyJob?.cancel()
        roomDanmakuPolicyJob = lifecycleScope.launch {
            val policy = withContext(Dispatchers.IO) {
                LiveAPI().roomDanmakuPolicy(realRoomId.toString())
            }
            if (isFinishing || isDestroyed) return@launch
            // ★诊断日志（只读）：判定结论 + **原始字段**（复核"到底是哪个字段判的"不必再抓包）
            LivePageTrace.note(
                "danmaku.roomPolicy",
                "room" to realRoomId,
                "closed" to when (policy.closed) {
                    true -> "true"
                    false -> "false"
                    else -> "unknown"
                },
                "closeDanmaku" to (policy.closeDanmaku?.toString() ?: "-"),
                "danmakuEditor" to (policy.danmakuEditor?.toString() ?: "-"),
                "code" to (policy.code?.toString() ?: "-"),
            )
            // ★拿不到（null）→ false = 没关闭（保守，绝不误关）
            applyRoomDanmakuClosed(policy.closed == true)
        }
    }

    /**
     * 把"该房间关闭了弹幕"这个结论**落到 UI 上**（幂等：结论没变就一个字节都不动）。
     *
     * ## 关了的时候改三件事
     * | 对象 | 改成什么 | 为什么这么做 |
     * |---|---|---|
     * | [danmakuInput] | `isEnabled=false` + 去掉圆角底 + hint 换「该直播间已关闭弹幕」 | **复用同一个 View**：底栏那一行的高度由 EditText 自己决定，另起一个 TextView 很难做到逐像素同高 —— 一旦差几 dp，底栏高度就变，[measurePortraitStage] 量出的"弹幕列表槽底边 = 底栏顶边"这条不变式跟着动。只关掉它、撤掉底色，几何**一个像素都不变** |
     * | [danmakuSendProgress] | `GONE` | 关了就没有"发送中" |
     * | [danmakuHost] | `setRoomDanmakuClosed(true)` | 宿主里"竖屏列表"与"滚动弹幕"两个门（横竖屏都关） |
     *
     * ## 为什么是"一行很轻的提示"而不是干脆留空
     * 留空的话用户只会看到底栏少了一个框（"是不是 App 坏了？"）—— 这类"看着像 bug"的反馈在本页
     * 历史里反复出现过（PiP/字号/黑条那几条）。提示文案只有一句、用次级色、无背景、不可点，
     * 是"能解释清楚"与"不喧哗"之间的折中；真正的克制是**它不占据任何额外空间**（与输入框同位同高）。
     *
     * @param closed true = 该房间关闭了弹幕；false = 照旧（**含"拿不到判定"这一档**）
     */
    private fun applyRoomDanmakuClosed(closed: Boolean) {
        // ★防御：本函数的正常入口是"进房判定回来"（一定在 buildUi 之后），但真到了
        //   "UI 还没建好"这一档也绝不能崩 —— 什么都不做就是"按没关闭处理"，语义一致。
        if (!::danmakuInput.isInitialized) return
        if (roomDanmakuClosed == closed) return
        roomDanmakuClosed = closed
        if (closed) {
            // 关掉的一瞬间：正在输入的话先收键盘/清焦点（不然光标留在一条不可用的框里）
            dismissDanmakuInput()
            clearDanmakuInputError()
            danmakuInput.setText("")
        }
        // 两态都重刷一遍（关→开也要把底色/hint/可聚焦性还原，不能只做单向）
        applyDanmakuInputClosedUi()
        // ★宿主侧：竖屏列表不显示 + 滚动弹幕不渲染（横屏同样不渲染）
        danmakuHost?.setRoomDanmakuClosed(closed)
        // 输入条整行的显隐规则不变（仍跟四颗按钮走），这里只是把"当前可见性"再落实一次
        if (::danmakuInputRow.isInitialized && ::topBar.isInitialized) {
            applyControlsVisibility(controlsVisible)
        }
    }

    /**
     * [danmakuInput] 的"可输入 ↔ 已关闭"两态（★只改这一个 View 的属性，不动版式）。
     *
     * 关闭态四件事：① 不能聚焦/不能输入；② 撤掉圆角底色（"那个框"不再画）；
     * ③ hint 换成一句提示；④ 转圈收掉。IME 的回车发送（[submitDanmakuInput]）在 `isEnabled=false`
     * 之后根本不会触发，[submitDanmakuInput] 里还另有一道同样的门（双保险）。
     */
    private fun applyDanmakuInputClosedUi() {
        if (!::danmakuInput.isInitialized) return
        val closed = roomDanmakuClosed
        danmakuInput.isEnabled = !closed
        danmakuInput.isFocusable = !closed
        danmakuInput.isFocusableInTouchMode = !closed
        danmakuInput.hint = if (closed) DANMAKU_CLOSED_HINT else DANMAKU_INPUT_HINT
        danmakuInput.background = if (closed) null else danmakuInputBackground()
        // ★2026-09-26 用户实测两条：
        //   ① "应该把那个弹幕的按钮也给隐藏掉" —— 房间已关闭弹幕时，那颗「弹幕」开关按钮没有意义
        //      （点了也发不出去），跟着输入框一起收掉；重新打开则还原。
        //   ② "在横屏全屏的情况下，把提示居中，它现在靠得太左了" —— 关闭态的提示文字**居中**；
        //      正常态保持左对齐（用户平时习惯的输入位置）。
        if (::danmakuButton.isInitialized) {
            danmakuButton.visibility = if (closed) View.GONE else View.VISIBLE
        }
        danmakuInput.gravity = if (closed) {
            android.view.Gravity.CENTER
        } else {
            android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
        if (::danmakuSendProgress.isInitialized && closed) {
            danmakuSendProgress.visibility = View.GONE
        }
    }

    /**
     * 输入框那层圆角底（主题色 × 30% 透明）。
     * ★抽成一个函数是为了"关闭弹幕时不要这层底"和 [applyThemeColors] 两处**共用同一份定义**
     *   （否则换主题时会把关闭态重新画出一个框来）。
     */
    private fun danmakuInputBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpToPx(18).toFloat()
        setColor(ColorUtils.setAlphaComponent(accentColor(), 78))
    }

    /** `room_init`：★实测免登录、免 UA、免 Referer，裸请求即 code=0（方案 §2.1） */
    private suspend fun resolveRoom(id: String): LiveRoomInitInfo? = withContext(Dispatchers.IO) {
        // ★改用**多级降级**解析（2026-09-26 用户实测"所有直播间都提示获取直播信息失败"）：
        //   `room/v1/Room/room_init` 被 B 站整端点风控封禁（HTTP 412 `request was banned`，
        //   容器内 87 次实测 97.7% 被封），而 getH5InfoByRoom / get_info / getRoomPlayInfo 全部 200。
        //   降级链能同时拿回 live_status（未开播判定与 30s 轮询靠它）与 uid（第五批之前「UP主」按钮靠它），
        //   并且实测对有效短号也能解析（1→5440、3→23058），顺带修掉短号房取流报 60004 的问题。
        //   三种签名方式打 room_init 一律 412 → 签名不是解药；本函数只换数据源，不动任何签名逻辑。
        LiveAPI().roomInitResolved(id)
    }

    /**
     * 开播轮询：未开播/下播之后每 [OFFLINE_POLL_INTERVAL_MS] 查一次 `live_status`，
     * 一开播就用真实房间号重新起播。
     *
     * ★★本轮（未开播与中途下播）它是**锁定之后唯一的自动恢复路径**：
     * ```
     * 进房发现没开播 / 中途下播 / 反复拿不到流
     *   → [enterOfflineWait]（锁定 + 停掉所有自动追流 + 换文案）
     *     → 本函数：每 45s **一次**（真的只问一次接口，不是连环换流）
     *       → 开播了 → delegate.start() → 出画面 → PLAYING 把锁定解开
     * ```
     * 为什么是"单次尝试"而不是"重试套餐"：这一路每次只做一件事 —— 问一句 `live_status`；
     * 只有在**服务端说开播了**的情况下才会重新取流一次。所以它天然不会形成换流风暴。
     *
     * ★它仍然用 [resolveRoom] 的**降级链**（`room_init` → `getH5InfoByRoom` → `get_info`）：
     *   `room_init` 已被风控整端点 412 封过（实测 97.7%），只信它会让轮询永远"查不到"。
     *   `?: continue` 那一支（三个都失败）**保持"继续等"**：拿不到 ≠ 没开播，宁可下一轮再问，
     *   也绝不把"没问到"写成"未开播"（与 `LiveRoomProbe.STATUS_UNKNOWN` 同一条原则）。
     */
    private fun startOfflinePolling() {
        if (pollJob?.isActive == true) return
        pollJob = lifecycleScope.launch {
            while (isActive) {
                setStreamStatus("等待开播：${OFFLINE_POLL_INTERVAL_MS / 1000}s 后自动检查（开播即自动起播）")
                delay(OFFLINE_POLL_INTERVAL_MS)
                val init = resolveRoom(rawRoomId) ?: continue
                if (LiveStatus.isPlayable(init.live_status)) {
                    setStreamStatus("主播开播了，正在起播…")
                    // ★本轮：开播了 = "没在播"这条线结束（锁定解开、预算复位）。
                    //   真正的解锁仍在 PLAYING 那一支（出画面才算数），这里先解开是为了让
                    //   "起播这一次失败"也能重新走一遍自动追流（否则一次失败就再无自愈）。
                    offlineLatched = false
                    autoRetryBudgetExhausted = false
                    delegate?.start(requestedQn)
                    return@launch
                }
            }
        }
    }

    /**
     * ★本轮新增：**进入"没在播"的等待态**（`OFFLINE` 状态与锁定判定的**唯一收敛点**）。
     *
     * 做四件事（顺序无关，但一件都不能少）：
     * 1. **锁定** [offlineLatched] ⇒ 看门狗与 [autoRetryLiveStream] 从此什么都不做（换流停止）；
     * 2. **清掉自动追流的记账**（预算窗口 + "已提醒"标记）—— 下一次真的开播恢复后是全新的一轮；
     * 3. 如实写状态行（文案由 delegate 给，区分"未开播 / 已下播 / 反复中断"）并收起转圈；
     * 4. 起 [startOfflinePolling]（锁定期间**唯一**的自动恢复路径）。
     *
     * ★它**不弹**任何弹窗：弹窗只在**有硬证据**时弹（[showLiveOfflineDialogOnce]），
     *   否则"流断了但主播可能还在"也会被说成"下播"，那是把猜测当结论。
     */
    private fun enterOfflineWait(message: String) {
        offlineLatched = true
        autoRetryBudgetExhausted = false
        autoRetryStamps.clear()
        autoRetryBudgetWarned = false
        // ★转圈必须收掉：进度条是"正在取流"的信号，而这一刻已经**没有流可等**了
        //   （进房那条路是 `startResolveAndPlay()` 开的转圈，不走 delegate 回调，
        //     所以这里必须自己收 —— 漏了这一行就是"一直在转圈"）
        showLoading(false)
        setStreamStatus(message)
        startOfflinePolling()
    }

    /**
     * ★本轮新增：**"主播已下播 / 未开播"的一次性提示**（用户原话："这个我想整个弹窗说他没开播"）。
     *
     * ## 为什么这么克制（三件事刻意不做）
     * | 不做 | 为什么 |
     * |---|---|
     * | **不自动退出直播间** | 用户可能只是想看看封面、等一会儿、或者去点别的；替他退出去是**替他做决定**。退出走系统返回键/顶栏返回（他本来就会用），弹窗里只留一句"知道了" |
     * | **不循环弹** | 一次下播事件只弹一次（[offlineDialogShown]）；真的重新开播（`PLAYING`）之后才允许下一次 —— 否则"轮询失败→再弹→再失败"会变成弹窗风暴 |
     * | **不打断版式/手势** | 走本页统一的 [normalDialog]（MaterialAlertDialog，独立窗口、居中、系统算几何），横竖屏/PiP/小窗下都不会越界，也不参与任何几何契约 |
     *
     * ## 弹窗里的两句话
     * - 标题：**"主播已下播"**（本次进房真的播出过画面 —— [hasPlayedThisRoom]）/ **"主播未开播"**（一次都没播起来）；
     *   ★判据是本页**亲眼看到的画面**，不是列表里那个可能过期的 `live_status`（用户实测："列表还是'他开播'，进去其实没开播"）。
     * - 正文：一句"已经在等待开播，开播后会自动起播" —— 让用户知道**现在什么都不用做**，
     *   也在暗示"不会一直换流了"（这正是本轮修的东西）。
     *
     * @param liveStatus 判据看到的那次 `live_status`（只进诊断日志，不进文案）
     * @param source 判据来源（只进诊断日志）
     */
    private fun showLiveOfflineDialogOnce(liveStatus: Int, source: String) {
        if (isFinishing || isDestroyed) return
        if (isInPictureInPictureMode) {
            // 小窗里弹一个"主播已下播"没有意义（用户在看别的页面），状态行/等待开播照旧。
            LivePageTrace.note("offline.dialog.skip", "reason" to "pip", "source" to source)
            return
        }
        if (offlineDialogShown) {
            LivePageTrace.note("offline.dialog.skip", "reason" to "alreadyShown", "source" to source)
            return
        }
        offlineDialogShown = true
        val played = hasPlayedThisRoom
        LivePageTrace.note(
            "offline.dialog",
            "room" to rawRoomId,
            "liveStatus" to liveStatus,
            "source" to source,
            "everPlayed" to played,
        )
        // 与「画质·线路」「直播设置」同一条规矩：同一时刻只留一个弹窗
        dismissDialogs()
        dismissDanmakuInput()
        val dialog = normalDialog()
            .setTitle(if (played) "主播已下播" else "主播未开播")
            .setMessage(
                if (played) {
                    "直播已经结束，已停止自动重试。\n留在本页即可：主播重新开播后会自动起播。"
                } else {
                    "这个直播间现在没有开播，已停止自动重试。\n留在本页即可：开播后会自动起播。"
                }
            )
            .setPositiveButton("知道了", null)
            .create()
        // 点"知道了"只关弹窗（**留在页面等待**，正是上面正文承诺的事）；退出仍走返回键
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                LivePageTrace.note("offline.dialog.ack", "room" to rawRoomId, "polling" to (pollJob?.isActive == true))
                runCatching { dialog.dismiss() }
            }
        }
        dialog.setOnDismissListener { if (liveOfflineDialog === dialog) liveOfflineDialog = null }
        liveOfflineDialog = dialog
        runCatching { dialog.show() }
    }

    /** 收掉"主播已下播"提示（幂等；开播/重新取流/页面销毁时都要收） */
    private fun dismissLiveOfflineDialog() {
        liveOfflineDialog?.takeIf { it.isShowing }?.let { runCatching { it.dismiss() } }
        liveOfflineDialog = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // 弹幕客户端：取引用 + 连接状态
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 订阅弹幕连接状态。
     *
     * ★为什么必须订阅（"看不见弹幕"的另一半原因）：B 路的 `connect()` 一旦
     *   `getDanmuInfo` 被风控（-352）或 op=8 认证连续失败，就会进 `ConnState.Failed`
     *   并且**永久停止自动重连**；而宿主只在 `active` 由 false 变 true 时才会再调 `connect()`。
     *   于是没有任何提示、也不会自愈 —— 从用户视角就是"这个直播间就是没有弹幕"。
     *   把状态显示出来 + 给出"弹幕重连"入口，才能把这种情况从"玄学"变成"可操作"。
     */
    private fun bindDanmakuClient(host: LiveDanmakuOverlayHost) {
        // 宿主把 client 开成了公开只读属性（LiveDanmakuOverlayHost.kt 的 `val client`），
        // 所以这里直接拿就行 —— 之前"按字段类型反射"的 workaround 已经删除。
        val client = host.client
        danmakuClient = client
        danmakuStateJob?.cancel()
        danmakuStateJob = lifecycleScope.launch {
            client.connectionState.collect { state ->
                danmakuConnState = state
                renderStatus()
                updateDanmakuButton()
                if (state == ConnState.Failed) {
                    toast("弹幕连接失败（风控或认证被拒），点底栏「弹幕重连」可再试一次")
                }
            }
        }
        // ★本轮新增：**弹幕流里的直播状态**（`cmd=PREPARING` / `cmd=LIVE`）—— 下播的**零成本信号**。
        //
        // 为什么值得接：主播下播时弹幕链路会先收到 `PREPARING`，比"播放器发现流断了"更早，
        // 而且**不多花任何一次请求**（这条连接本来就在跑；`LiveDanmakuClient` 早就把这个消息
        // 发进 `messages` 了，只是此前**没有任何消费端**）。
        // 为什么只当"嫌疑"：判据与定性全部交给 delegate 的 `LiveOfflineDetector`
        // （`PREPARING` 可能是瞬态，真要定性必须再问一次 `live_status`；
        //  `STOP_LIVE_ROOM_LIST` 是全局列表消息，刻意不用 —— 见那里的判据表）。
        liveSignalJob?.cancel()
        liveSignalJob = lifecycleScope.launch {
            client.messages.collect { message ->
                if (message !is LiveMessage.LiveStatus) return@collect
                delegate?.noteDanmakuLiveSignal(message.status)
            }
        }
    }

    /**
     * 弹幕连接状态 → 顶栏短标签，**只回"异常/过渡"那几档**；正常态回 null（不占位置）。
     *
     * ★本轮（用户："顶栏正常的时候没有状态文字……'弹幕 已连接'也算正常态，隐藏"）：
     * | 状态 | 回什么 | 为什么 |
     * |---|---|---|
     * | [ConnState.Connecting] | 「弹幕 连接中」 | 过渡态（刚进房/刚重连，用户能看到它在动） |
     * | [ConnState.Connected] | **null** | ★正常态 —— 不显示 |
     * | [ConnState.Reconnecting] | 「弹幕 重连中」 | 异常（连接已断，正在自愈） |
     * | [ConnState.Failed] | 「弹幕 连接失败」 | 异常（B 路此后**不会**再自动重连，必须让用户看见） |
     * | [ConnState.Idle] + 弹幕开着 | 「弹幕 未连接」 | 异常（开着却一条连接都没有） |
     * | [ConnState.Idle] + 弹幕关着 | **null** | 正常态（用户自己在底栏关的，不用提醒） |
     * | null（还没收到状态） | **null** | 未知不占位置 |
     *
     * ★诊断/兜底文案不要用这个函数（它在正常态回 null会让提示变成"未知"）——
     *   要"无论什么状态都给一句人话"请用 [danmakuConnLabel]。
     */
    private fun danmakuStatusLabel(): String? = when (danmakuConnState) {
        ConnState.Connecting -> "弹幕 连接中"
        ConnState.Connected -> null
        ConnState.Reconnecting -> "弹幕 重连中"
        ConnState.Failed -> "弹幕 连接失败"
        ConnState.Idle -> if (danmakuEnabled) "弹幕 未连接" else null
        null -> null
    }

    /**
     * 弹幕连接状态的**完整**标签（每一档都有话，包括正常态）—— 只给"诊断/兜底提示"用。
     *
     * 目前唯一调用点：发弹幕失败但 `lastSendError` 也拿不到时那句
     * `弹幕发送失败（原因未知；弹幕状态：…）`。那种时候"已连接 / 已关"恰恰是**要看的**信息，
     * 所以不能复用 [danmakuStatusLabel]（它在正常态回 null）。
     */
    private fun danmakuConnLabel(): String = when (danmakuConnState) {
        ConnState.Connecting -> "弹幕 连接中"
        ConnState.Connected -> "弹幕 已连接"
        ConnState.Reconnecting -> "弹幕 重连中"
        ConnState.Failed -> "弹幕 连接失败"
        ConnState.Idle -> if (danmakuEnabled) "弹幕 未连接" else "弹幕 已关"
        null -> "未知"
    }

    // ══════════════════════════════════════════════════════════════════════
    // 播放器回调（全部在主线程）
    // ══════════════════════════════════════════════════════════════════════

    private val delegateListener = object : LivePlayerDelegate.Listener {

        override fun onStreamReady(info: LivePlayerDelegate.LiveStreamInfo) {
            // ★诊断日志（只读）：取流成功（清晰度/线路的**结果**）
            LivePageTrace.note(
                "stream.ready",
                "room" to info.roomId,
                "actualQn" to info.actualQn,
                "requestedQn" to info.requestedQn,
                "actualQnDesc" to info.actualQnDesc,
                "line" to (info.lineIndex + 1),
                "lines" to info.lineCount,
                "qualities" to info.qualities.size,
            )
            qualityOptions = info.qualities
            lineOptions = info.lines
            actualQn = info.actualQn
            actualQnDesc = info.actualQnDesc
            showLoading(false)
            // ★本轮：标题 = `直播间 房间号（x.x万人在线）`（房间号 + 在线人数在同一个出口里拼，
            //   见 [renderRoomTitle]）。在线人数每 45s 回来一次时会再调一次同一个函数。
            titleRoomId = info.roomId
            renderRoomTitle()
            // ★★本轮（用户拍板的"减法"）：这里原来还有一句
            //   `setStreamStatus("画质 ${info.actualQnDesc}$requestedHint ｜ 线路 …")` —— **整句删除**。
            //   用户原话："当前画质/线路也在状态行里显示 ✗ —— 那部分去掉（画质弹窗内已有'当前：xxx'）"。
            //   当前档位信息一个都没少：底栏「画质」那颗按钮点开的弹窗里，清晰度段与线路段的副标题
            //   就是「当前：原画（qn 10000）」「当前：线路 1/2」（[showStreamDialog]）。
            //   ★随之删掉的还有只为这句话服务的 `requestedHint`（"（请求 xxx）"那个后缀）——
            //     它的另一半信息仍在：未登录被静默降级时下面那句 toast 会如实告知。
            // ★★第八批（用户实测第 3 条）：底栏那颗按钮的文案从「画质·原画」**缩短成「画质」**，
            //   所以这里**不再**把当前值写进按钮 —— 用户原话给了两条路（"要么给足宽度、要么缩短为「画质」"），
            //   而这一行现在要和输入框 + 另外三颗按钮挤在同一行：5 个字会把整行字号从 14sp 拖到 9sp
            //   （[applyBottomBarTextSizes] 按"一行里最宽的文案"统一挑一档）。
            //   ★想改回"带当前值"：把本行换成 `qualityButton.text = "画质·${info.actualQnDesc}"` 即可
            //   （下面那次 [applyBottomBarTextSizes] 会自动把整行切到放得下的那一档字号）。
            //   ★第七批：「画质」与「线路」合并成一颗，所以这里**只刷这一颗**，
            //   线路的"当前值"在弹窗第二段的副标题里（[showStreamDialog]），底栏不再重复占一格。
            // ★按钮文案长短变了（三态那颗：弹幕开 / 弹幕关 / 重连）→ 档位要重算一遍
            //   （底栏的布局监听通常也会兜住，这里显式调一次是为了不依赖"改文字一定会触发底栏布局"）
            applyBottomBarTextSizes(bottomBarAvailableWidthPx())
            updatePlayPauseButton()
            // ★未登录时服务端把原画**静默降级**成 250（方案 §2.2）：
            //   必须如实告诉用户，否则他会以为是我们播放器画质差
            if (info.actualQn in 1 until info.requestedQn && warnedQn != info.requestedQn) {
                warnedQn = info.requestedQn
                toast("「${qnDesc(info.requestedQn)}」需要登录或大会员，当前实际为「${info.actualQnDesc}」")
            }
        }

        override fun onPlayStateChanged(state: LivePlayerDelegate.LivePlayState, message: String?) {
            // ★诊断日志（只读）：播放状态收敛点（真实 isPlaying 由 delegate 的 onIsPlayingChanged 记）
            LivePageTrace.note(
                "play.state",
                "state" to state,
                "msg" to message,
                "isPlaying" to (delegate?.isPlaying == true),
                "playWhenReady" to (delegate?.player?.playWhenReady),
                "pip" to isInPictureInPictureMode,
            )
            when (state) {
                LivePlayerDelegate.LivePlayState.RESOLVING,
                LivePlayerDelegate.LivePlayState.LOADING,
                -> {
                    showLoading(true)
                    message?.let { setStreamStatus(it) }
                }

                LivePlayerDelegate.LivePlayState.PLAYING -> {
                    showLoading(false)
                    // ★本轮：「直播中」是**正常态** → 走 [setStreamStatusNormal]（状态行整条隐藏，
                    //   顶栏只剩返回 + 房间号（在线人数））；它同时负责把上一条异常文案清掉。
                    setStreamStatusNormal("直播中")
                    // ★本轮：真的播出画面 = 一次**真恢复** —— 把"没在播"这条线的账全部解开：
                    //   · 解除锁定（下一条路才允许自动追流）；
                    //   · 重新给一份自动追流预算（上次那份用尽是因为"确实追不回来"，现在追回来了）；
                    //   · 允许下一次下播**再弹一次**提示（新的事件）；并把已经弹着的那个收掉
                    //     （开播轮询把画面接上了，弹窗不该再挡着）。
                    hasPlayedThisRoom = true
                    offlineLatched = false
                    autoRetryBudgetExhausted = false
                    offlineDialogShown = false
                    autoRetryStamps.clear()
                    autoRetryBudgetWarned = false
                    dismissLiveOfflineDialog()
                    updatePlayPauseButton()
                }

                LivePlayerDelegate.LivePlayState.PAUSED -> {
                    showLoading(false)
                    setStreamStatus("已暂停")
                    updatePlayPauseButton()
                    // 暂停时把控制条留在屏幕上，否则用户找不到"播放"在哪
                    showControlsTemporarily()
                }

                LivePlayerDelegate.LivePlayState.OFFLINE -> {
                    showLoading(false)
                    // ★本轮：**没有流了 ⇒ 锁定"没在播"**：停掉一切自动追流，只留 45s 开播轮询。
                    //   文案用 delegate 给的那句（它按 live_status / 有没有播过区分措辞，
                    //   且绝不把接口术语写给用户看）。
                    enterOfflineWait(message ?: "暂时没有直播信号（等待开播…）")
                }

                LivePlayerDelegate.LivePlayState.ERROR -> {
                    showLoading(false)
                    // ★本轮：「等待开播」期间的那次起播尝试失败 = 轮询的**中间态**，不是新事件：
                    //   · **不 toast**（否则每 45s 弹一次"服务端暂未下发播放地址…"）；
                    //   · **把轮询接回去**（`startOfflinePolling()` 出来时那个协程已经 return 了）——
                    //     否则用户会停在一句"稍后自动重试"上，而实际上**再没有任何东西会去重试**。
                    //   ★这一路是"服务端说在播、但这次确实没给流"（B 站下播后常见的一段自相矛盾期），
                    //     所以频率仍然是 45s 一次**单次尝试**，不是换流风暴。
                    if (offlineLatched) {
                        setStreamStatus(message ?: "暂时没有直播信号（等待开播…）")
                        if (pollJob?.isActive != true) startOfflinePolling()
                        return
                    }
                    setStreamStatus(message ?: "播放失败")
                    message?.let { toast(it) }
                    setControlsVisible(true)
                }
            }
        }

        override fun onVideoSizeChanged(width: Int, height: Int) {
            videoWidth = width
            videoHeight = height
            // ★诊断日志（只读）：解码尺寸到达（PiP 比例与"黑边"的输入）
            LivePageTrace.note(
                "video.size",
                "w" to width,
                "h" to height,
                "ratio" to pipAspectRatio(),
                "pip" to isInPictureInPictureMode,
            )
            videoContainer.videoAspectRatio = width.toFloat() / height.toFloat()
            videoContainer.requestLayout()
            // 比例变了 → PiP 的宽高比与源矩形提示跟着变，不然 PiP 窗口会留黑边/动画起点错位
            updatePipParams()
        }

        /**
         * ★本轮新增：delegate **确认了"主播没在播"**（硬证据：取流 `live_status != 1`，
         * 或限频复查 `get_info` 明确说没在播）。
         *
         * 这里只做**一件事**：弹那个一次性的提示。状态行文案与"等待开播"轮询已经由
         * [LivePlayerDelegate.LivePlayState.OFFLINE] 那一支（[enterOfflineWait]）做掉了 ——
         * 两个回调分工见 `LivePlayerDelegate.Listener` 的 KDoc，不重复、也不互相打架。
         */
        override fun onLiveOffline(liveStatus: Int, source: String) {
            showLiveOfflineDialogOnce(liveStatus, source)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 交互：播放/暂停
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 播放/暂停状态的**唯一收敛点**（★第四批删了底栏那颗按钮、★第五批删了音频舞台那颗，
     * 现在它只剩下"把状态变化通知给 PiP 动作图标"这一件事）。
     *
     * ## 暂停/播放入口现在有哪几个（一个没少）
     * | 入口 | 在哪 | 说明 |
     * |---|---|---|
     * | **双击画面** | [TapCatcher.onDoubleTap] | 受「设置 → 直播设置 → 双击暂停」（`live_double_tap_pause`）管，**默认开** |
     * | PiP 动作按钮 | [buildPipActions] | 小窗上唯一那颗，图标随状态翻转 |
     *
     * ★第五批删掉「听音频」之后，音频舞台那颗「暂停/播放」与媒体通知里的控制一起没了
     *   （`LiveAudioService` 整个文件删除）。
     *
     * 状态只从 [LivePlayerDelegate.LivePlayState] 来（delegate 里新加了 `onIsPlayingChanged`
     * 上报的 `PAUSED`），**不是**点一下自己翻一下 —— 后者在"退后台被自动暂停"、
     * "缓冲结束自动恢复"这些路径上必然显示错。
     */
    private fun updatePlayPauseButton() {
        // ★播放/暂停状态一变就把 PiP 的动作图标重刷一遍（诊断报告 §5 第 4 条）：
        //   小窗里的按钮图标**不会自己更新** —— 点一次后图标停在旧状态，用户会以为"点了没反应"
        //   （点播在那份注释里踩过同一个坑）。这里是"播放状态变化"的唯一收敛点：
        //   delegate 的 PLAYING/PAUSED 回调、双击画面、退后台自动暂停，最终都走到这里。
        updatePipParams()
    }

    private fun togglePlayPause() {
        val d = delegate ?: return
        if (d.isPlaying) {
            d.pause()
            setControlsVisible(true)
        } else {
            d.play()
            showControlsTemporarily()
        }
        // 立刻反馈一次（真正的状态仍以 delegate 回调用准）
        updatePlayPauseButton()
    }

    /**
     * 「重新取流」= 原来的底栏第一颗「刷新」（第四批之前叫「重试」）—— **功能一行未改**。
     *
     * 用户原话（第四批）："把底栏的那个重试放到第一个按钮去。这个重试按钮是否可以叫符合名字一点的刷新？
     * ……它的功能不要乱改，它这个功能是对的。"
     * 用户原话（★第七批）："「刷新」改成自动……**保留手动入口**：放进「画质·线路」弹窗里一个
     * 「重新取流」小按钮（不占底栏）……行为与现在的手动「刷新」**完全一致**。"
     *
     * 落到的就是本函数（`delegate.retry()`）：**清空限流预算 + 立即重新取流/追到最新直播进度**，
     * 播放器不重建、底栏其它按钮与弹幕开关的状态都不动（见 `LivePlayerDelegate.retry()`）。
     * 唯一多出来的分支是"播放器还没建出来"（进房就点重新取流）：退化成重跑一次
     * [startResolveAndPlay]（解析房间 → 取流），语义仍然是"重新来一遍"。
     *
     * ## 现在谁在调它（两条路，**没有**常驻按钮）
     * | 调用方 | 场景 |
     * |---|---|
     * | [autoRetryLiveStream] | 自动：回到前台 / 看门狗检出停滞、落后或"画面不出新帧"（带防抖与预算，`fromAuto = true`） |
     * | [showStreamDialog] 的「重新取流」 | 手动：用户自己点，**不受**防抖与预算限制（`fromAuto` 用默认值 false） |
     *
     * ★本轮新增 [fromAuto]：**手动**这一路还要额外做三件事（自动那一路刻意不做，理由见下）：
     * ```
     * ① 解除"没在播"的锁定（offlineLatched = false）—— 用户明确要我再来一次；
     * ② 重新给一份自动追流预算（autoRetryBudgetExhausted = false）；
     * ③ 收掉"主播已下播"那个提示（并允许下一次下播再弹）。
     * ```
     * 自动那一路不做：它是**看门狗自己**发起的，若它也解锁/发预算，"预算用尽 ⇒ 停手"这道终点
     * 就会被它自己无限续杯 —— 那正是修前"一直在换流"的成因之一（见交付报告 §2）。
     */
    private fun retryPlayback(fromAuto: Boolean = false) {
        // ★诊断日志（只读）：手动/自动"重新取流"的**起**
        LivePageTrace.note(
            "retry.start",
            "delegate" to (delegate != null),
            "pip" to isInPictureInPictureMode,
            "pageStarted" to pageStarted,
            "fromAuto" to fromAuto,
            "offlineLatched" to offlineLatched,
        )
        pollJob?.cancel()
        if (!fromAuto) {
            offlineLatched = false
            autoRetryBudgetExhausted = false
            offlineDialogShown = false
            dismissLiveOfflineDialog()
        }
        val d = delegate
        if (d == null) {
            LivePageTrace.note("retry.path", "path" to "startResolveAndPlay")
            startResolveAndPlay()
        } else {
            LivePageTrace.note("retry.path", "path" to "delegate.retry")
            d.retry()
        }
        // ★诊断日志（只读）：**止**（真正的结果由 onStreamReady / onPlayStateChanged 记录）
        LivePageTrace.note("retry.end", "path" to if (d == null) "startResolveAndPlay" else "delegate.retry")
    }

    // ══════════════════════════════════════════════════════════════════════
    // 自动追流（★第七批：「刷新」按钮改自动）
    //
    // 触发时机（★第十批起是这两条）+ 一道统一的闸门：
    // ```
    // ① onResume（真的退过后台又回来）      → [autoRetryOnNextResume] 标记
    // ② 看门狗 [checkLiveHealthOnce]         → 停滞（连续缓冲）/ 落后直播边缘 / **画面不再出新帧**
    // ```
    // ★第十批改掉了原来的第 ②'条"退出小窗回全屏 → 无条件重取流"：它把好画面也换成黑屏（换源等首帧），
    //   失败时还直接把页面留在 ERROR（"有时黑屏、播放不了"）。现在退出小窗只做"把判定交给看门狗"，
    //   见 [onPictureInPictureModeChanged] 与 [checkFrameProgressOnce]。
    // 两条都汇到 [autoRetryLiveStream]，由它做防抖（20s）、预算（5 分钟 3 次）、
    // 以及对「设置 → 直播设置 → 自动重连」的尊重（关掉 = 用户要完全手动）。
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 看门狗：每 [LIVE_WATCHDOG_INTERVAL_MS] 巡检一次（只读几个 player getter，几乎不耗电）。
     *
     * ★本轮多挂了一件事：[refreshRoomOnlineIfDue]（顶栏在线人数）—— **搭在这一次已有巡检上**，
     *   没有新建 Job、没有新建定时器；它自己带 [ONLINE_REFRESH_INTERVAL_MS]（45s）时间戳闸门，
     *   所以看门狗 5s 醒一次 ≠ 5s 打一次接口，真发出去的请求仍是 45s 一次。
     *   顺序上先刷人数、再判健康：人数那一支是"发起即返回"（内部另起一个协程等网络），
     *   不会把同一拍的健康巡检拖在网络 IO 后面。
     */
    private fun startLiveHealthWatchdog() {
        if (liveHealthJob?.isActive == true) return
        liveHealthJob = lifecycleScope.launch {
            while (isActive) {
                delay(LIVE_WATCHDOG_INTERVAL_MS)
                // ★在线人数已按用户要求移除：这行若恢复，顶栏会重新拼括号（见 renderRoomTitle 的说明）

                // refreshRoomOnlineIfDue()
                checkLiveHealthOnce()
            }
        }
    }

    /**
     * ★本轮：顶栏「在线人数」的刷新（用户要求"搭在已有的轮询上、30~60 秒刷一次"）。
     *
     * ## 数据来源：`room/v1/Room/get_info` 的 `online`
     * 走的是 [LiveAPI.roomInfo]（**本页早就在用的同一条接口**：`resolveRoom` 的降级链第 ③ 层
     * 就是它），解析成的 [LiveRoomDetail] 里 `online` 字段**早就声明好了** ——
     * 本轮没有新增接口、没有新增实体、没有新增解析代码，也没有碰 `AppStore/SettingConstants`。
     * 为什么选它而不是 `getInfoByRoom`：那一条（[LiveAPI.roomInfoByRoom]）要 WBI 签名、
     * 按 IP 有风控（工程注释里写着"重试风暴会把正常房间也一起判成拿不到"），而它在本页的
     * 唯一用途是"这个房间关没关弹幕"（[probeRoomDanmakuPolicy]，**每个房间只调一次**，
     * 不该改造成轮询）。`get_info` 实测免登录、无 WBI、裸请求 code=0，是这两个候选里更耐操的一个。
     *
     * ## 刷新路径（★没有新开轮询）
     * ```
     * liveHealthJob（已有的看门狗，5s 一拍）
     *   └─ refreshRoomOnlineIfDue()            ← 本轮唯一新增调用点
     *        ├─ 闸门：距上次**发出**不足 ONLINE_REFRESH_INTERVAL_MS(45s) → 直接返回（不打接口）
     *        ├─ 闸门：不在前台（PiP / 即将进 PiP / 页面没起）→ 直接返回（顶栏那时候也看不见）
     *        └─ 过闸才 lifecycleScope.launch { withContext(IO) { LiveAPI().roomInfo(...) } }
     * ```
     * 时间戳在**发出前**就写（乐观），所以一次超时不会让下一次巡检再叠一发。
     *
     * ## 拿不到怎么办（用户要求："拿不到人数时不显示括号，不要显示 0 / --"）
     * 失败 / `online <= 0` → **什么都不改**：上一次的值留着（数字不会跳成 0），
     * 首次就没拿到则一直不带括号（[renderRoomTitle] 里那个 `> 0` 判断）。
     */
    private fun refreshRoomOnlineIfDue() {
        val roomId = realRoomId
        if (roomId <= 0L) return
        // 页面没起来 / 在小窗里：顶栏看不见，没必要打接口（与本页其它网络动作同一套门控）
        if (!pageStarted || isInPictureInPictureMode || pipEntryPending) return
        val now = SystemClock.elapsedRealtime()
        if (roomOnlineFetchedAtMs > 0L && now - roomOnlineFetchedAtMs < ONLINE_REFRESH_INTERVAL_MS) return
        roomOnlineFetchedAtMs = now
        lifecycleScope.launch {
            val online = withContext(Dispatchers.IO) {
                runCatching {
                    LiveAPI().roomInfo(roomId.toString()).call().json<ResponseData<LiveRoomDetail>>()
                }.getOrNull()?.takeIf { it.isSuccess }?.data?.online
            }
            if (isFinishing || isDestroyed) return@launch
            if (online == null || online <= 0L) return@launch
            roomOnline = online
            renderRoomTitle()
        }
    }

    /**
     * 巡检一次"直播还跟得上吗"——**只读**，不改任何东西；真要追流时才调 [autoRetryLiveStream]。
     *
     * 三个信号，各自独立（拿不到就跳过，绝不猜）：
     * 1. **停滞**：`playbackState == STATE_BUFFERING` 并且持续超过 [STALL_RETRY_MS]。
     *    用"持续"而不是"出现过"：切画质/换线路/起播都会缓冲一下，那不是故障；
     *    ★只在**用户没暂停**（`playWhenReady`）时才判 —— 暂停中的缓冲不算停滞。
     * 2. **落后**：`currentLiveOffset > LIVE_LAG_RETRY_MS`（离直播边缘太远）。
     *    `C.TIME_UNSET`（FLV 直播常常给不出 live 窗口）时**不判**，只靠上面那条。
     * 3. ★★第十一批新增：**画面不再出新帧** —— 解码计数 `renderedOutputBufferCount` 连续
     *    [FRAME_STALL_RETRY_MS] 没有增长（要求 `isPlaying`，即播放器自认为在播）。
     *
     * ## 为什么加第 3 条（它与本轮那把"退出小窗不再无条件重取流"是配套的）
     * 退出小窗原来会**无条件**重取一次流，其中有"歪打正着"的恢复作用：画面真坏掉时它顺手救回来。
     * 本轮把这句去掉（它正是"点放大 → 黑屏/播放不了"的来源之一，见
     * `onPictureInPictureModeChanged` 的注释），就必须补一条**只对真故障生效**的兜底，
     * 否则"黑着但播放器自认为在播"这种情况会一直没人管：
     * · 第 1 条看不见它（`STATE_READY`，不是 BUFFERING）；
     * · 第 2 条在 FLV 直播上常常看不见它（`currentLiveOffset == C.TIME_UNSET`）；
     * · 第 3 条直接数"渲染出来的帧"，与窗口/版式/UI 状态全都无关，所以两种盲区都能盖住。
     *
     * 为什么读 `delegate.player` 而不是让 delegate 加接口：`LivePlayerDelegate.player` 本来就是
     * 公开的 ExoPlayer（见它的声明），这几个 getter 是 media3 的标准 API（`videoDecoderCounters`
     * 是 `@UnstableApi`，本文件顶部已有 `@file:OptIn(UnstableApi::class)`）。
     */
    private fun checkLiveHealthOnce() {
        if (isFinishing || isDestroyed) return
        // ★本轮：已经锁定"没在播"（未开播/中途下播/反复拿不到流）⇒ 看门狗什么都不做。
        //   画面停住是**预期之内**的（主播已经下播、播放器已被 delegate 暂停），
        //   再判"停滞/不出帧"只会得出"要追流"这个错误结论 —— 那正是无限换流的一路。
        //   两个采样起点一起清零：等真的重新开播（锁定被 PLAYING 解开）时从零开始计。
        if (offlineLatched) {
            stallSinceMs = 0L
            frameStaticSinceMs = 0L
            return
        }
        // 后台 / 小窗里不追：用户没在看全屏，追了也白追（还会白耗一次接口）
        if (!pageStarted || isInPictureInPictureMode || pipEntryPending) return
        val p = delegate?.player ?: return
        if (!p.playWhenReady) {
            stallSinceMs = 0L
            frameStaticSinceMs = 0L
            return
        }
        if (p.playbackState == Player.STATE_BUFFERING) {
            val since = stallSinceMs
            if (since <= 0L) {
                stallSinceMs = SystemClock.elapsedRealtime()
                // ★诊断日志（只读）：判据①（连续缓冲）开始计时
                LivePageTrace.note("watchdog.stall.start", "thresholdMs" to STALL_RETRY_MS)
                return
            }
            if (SystemClock.elapsedRealtime() - since >= STALL_RETRY_MS) {
                stallSinceMs = 0L
                // ★诊断日志（只读）：判据①命中
                LivePageTrace.note(
                    "watchdog.stall.hit",
                    "bufferedMs" to (SystemClock.elapsedRealtime() - since),
                    "thresholdMs" to STALL_RETRY_MS,
                )
                autoRetryLiveStream("直播卡住（连续缓冲超过 ${STALL_RETRY_MS / 1000}s）")
            }
            return
        }
        stallSinceMs = 0L
        // ★第 3 条判据继续往下走之前先看"画面还在不在动"（它比"落后"更接近用户看到的黑屏）
        if (checkFrameProgressOnce(p)) return
        if (!p.isCurrentMediaItemLive) return
        val offset = p.currentLiveOffset
        if (offset == C.TIME_UNSET || offset < 0L) return
        if (offset > LIVE_LAG_RETRY_MS) {
            // ★诊断日志（只读）：判据②命中（落后直播边缘）
            LivePageTrace.note(
                "watchdog.lag.hit",
                "offsetMs" to offset,
                "offsetSec" to (offset / 1000),
                "thresholdMs" to LIVE_LAG_RETRY_MS,
            )
            autoRetryLiveStream("落后直播 ${offset / 1000}s")
        }
    }

    /**
     * ★★第十批：第 3 条判据 —— "画面还在出新帧吗"。
     *
     * 只在 `STATE_READY && isPlaying`（= 播放器自认为在正常播）时判：起播/换源/换清晰度的
     * "等首帧"窗口是 `STATE_BUFFERING`，会被上面那条挡掉，所以这里的 10s 阈值不需要再留更长。
     * 拿不到解码计数（`videoDecoderCounters == null`，例如纯音频或渲染器还没起来）时**不判**，
     * 与另外两条"拿不到就跳过"的写法一致。
     *
     * @return true = 这一轮已经处理过（调用方不要再判"落后"）
     */
    private fun checkFrameProgressOnce(p: Player): Boolean {
        if (!p.isPlaying) {
            frameStaticSinceMs = 0L
            return false
        }
        // ★`videoDecoderCounters` 是 **ExoPlayer** 上的属性，不在 `Player` 接口上
        //   （2026-09-26 全量编译实测：Unresolved reference 'videoDecoderCounters' on receiver of type 'Player'）。
        //   本页持有的就是 ExoPlayer（media3），这里显式取一次类型即可；拿不到就当作"看不到帧数"，
        //   与原来的 ?: 分支同义（不清零判据、不误判停滞）。
        val rendered = (p as? androidx.media3.exoplayer.ExoPlayer)
            ?.videoDecoderCounters?.renderedOutputBufferCount ?: run {
            frameStaticSinceMs = 0L
            lastRenderedFrameCount = -1
            return false
        }
        if (rendered != lastRenderedFrameCount) {
            // 出新帧了：重新开始计时
            lastRenderedFrameCount = rendered
            frameStaticSinceMs = 0L
            return false
        }
        val since = frameStaticSinceMs
        if (since <= 0L) {
            frameStaticSinceMs = SystemClock.elapsedRealtime()
            return false
        }
        if (SystemClock.elapsedRealtime() - since >= FRAME_STALL_RETRY_MS) {
            frameStaticSinceMs = 0L
            // ★诊断日志（只读）：判据③命中（画面不再出新帧）
            LivePageTrace.note(
                "watchdog.frameStall.hit",
                "staticMs" to (SystemClock.elapsedRealtime() - since),
                "thresholdMs" to FRAME_STALL_RETRY_MS,
                "renderedFrames" to rendered,
                "playerState" to p.playbackState,
            )
            autoRetryLiveStream("画面卡住（${FRAME_STALL_RETRY_MS / 1000}s 没有新视频帧）")
            return true
        }
        return false
    }

    /**
     * 自动重新取流（**唯一入口**，两个自动触发时机 —— 回到前台、看门狗判出的真故障 —— 都汇到这里）。
     *
     * ## 行为
     * 成功放行时调 [retryPlayback] —— 与手动「重新取流」**完全同一条路**
     * （`delegate.retry()`：清三个限流预算 + 重新取流 + 追到最新），功能一个字都没改。
     *
     * ## 防抖与预算（B 站接口对频率很敏感，方案 §3.5：连"分区直播列表"都会回 -352）
     * ```
     * ⓪ 已经锁定"没在播"（[offlineLatched]）            → 不做（★本轮新增：这就是"下播之后别再换流"）
     * ① 页面没在前台 / 在小窗 / 正在进小窗        → 不做
     * ② 「自动重连」关掉（live_auto_reconnect=false）→ 不做（那是用户"我要完全手动"的明确表态，
     *                                              手动入口 = 弹窗里的「重新取流」）
     * ③ 距离上一次自动追流 < AUTO_RETRY_MIN_INTERVAL_MS → 不做（合并同一秒里的多个触发源）
     * ④ 这一份预算已经用尽（[autoRetryBudgetExhausted]）→ 不做，并**只提醒一次**
     * ⑤ 这一份预算里已经追了 AUTO_RETRY_MAX_IN_WINDOW 次 → 不做，并**只提醒一次**
     * ⑥ 以上都过 → 记账（时间戳入队）+ 调 [retryPlayback]
     * ```
     * ★④⑤ 是"止损阀"，而且**不再随滑窗回满**（★本轮改的就是这一点）：真遇到一条持续坏的流，
     *   看门狗会每 [LIVE_WATCHDOG_INTERVAL_MS] 就再想追一次，没有它就会变成"每 20 秒打一次接口"
     *   的死循环；而**只有滑动窗口**的旧写法会"窗口一过额度自动恢复"，也就是**永远**在追 ——
     *   这正是用户实测的"一直在换流换流"。现在一份预算用到底，只有两条路能重新给预算：
     *   真的恢复播放（`PLAYING`）或用户手动「画质·线路 → 重新取流」。
     */
    private fun autoRetryLiveStream(reason: String) {
        if (isFinishing || isDestroyed) {
            LivePageTrace.note("autoRetry.blocked", "reason" to reason, "gate" to "finishingOrDestroyed")
            return
        }
        // ★本轮 ⓪ 号门：已经判定"没在播"（未开播 / 中途下播 / 反复拿不到流）⇒ 一次都不追。
        //   这是"下播之后不再换流"的第一道、也是最关键的一道闸门。
        if (offlineLatched) {
            LivePageTrace.note("autoRetry.blocked", "reason" to reason, "gate" to "offlineLatched")
            return
        }
        if (!pageStarted || isInPictureInPictureMode || pipEntryPending) {
            LivePageTrace.note(
                "autoRetry.blocked",
                "reason" to reason,
                "gate" to "notForeground",
                "pageStarted" to pageStarted,
                "pip" to isInPictureInPictureMode,
                "pipEntryPending" to pipEntryPending,
            )
            return
        }
        if (delegate == null) {
            LivePageTrace.note("autoRetry.blocked", "reason" to reason, "gate" to "noDelegate")
            return
        }
        // ★尊重「设置 → 直播设置 → 自动重连」：关掉 = 所有自动恢复路径都停，只留手动入口
        //   （与 `LivePlayerDelegate.handlePlayerError` 里那条判断同源，见 `live_auto_reconnect` 的 KDoc）
        if (!SettingPreferences.liveSettings().autoReconnect) {
            LivePageTrace.note("autoRetry.blocked", "reason" to reason, "gate" to "autoReconnectOff")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastAutoRetryAtMs > 0L && now - lastAutoRetryAtMs < AUTO_RETRY_MIN_INTERVAL_MS) {
            LivePageTrace.note(
                "autoRetry.blocked",
                "reason" to reason,
                "gate" to "debounce",
                "sinceLastMs" to (now - lastAutoRetryAtMs),
                "minIntervalMs" to AUTO_RETRY_MIN_INTERVAL_MS,
            )
            return
        }
        // ★本轮 ④ 号门：**这一份预算已经用尽** ⇒ 一次都不再追（不是"等窗口滑过去再说"）。
        //   复位点只有两个：真的恢复播放（PLAYING）/ 用户手动「重新取流」（见 retryPlayback 的 fromAuto）。
        if (autoRetryBudgetExhausted) {
            LivePageTrace.note("autoRetry.blocked", "reason" to reason, "gate" to "budgetExhausted")
            return
        }
        while (autoRetryStamps.isNotEmpty() && now - autoRetryStamps.first() > AUTO_RETRY_WINDOW_MS) {
            autoRetryStamps.removeFirst()
        }
        if (autoRetryStamps.size >= AUTO_RETRY_MAX_IN_WINDOW) {
            // ★本轮：用满即**停手到底**（旧写法只是"这一窗口内不再追"，窗口一滑又满血复活）
            autoRetryBudgetExhausted = true
            // ★诊断日志（只读）：被"5 分钟 3 次"的预算拦下
            LivePageTrace.note(
                "autoRetry.blocked",
                "reason" to reason,
                "gate" to "budget",
                "stampsInWindow" to autoRetryStamps.size,
                "maxInWindow" to AUTO_RETRY_MAX_IN_WINDOW,
                "windowMs" to AUTO_RETRY_WINDOW_MS,
            )
            if (!autoRetryBudgetWarned) {
                autoRetryBudgetWarned = true
                setStreamStatus("自动追流已暂停（${AUTO_RETRY_WINDOW_MS / 60_000} 分钟内已追 ${AUTO_RETRY_MAX_IN_WINDOW} 次）")
                toast("自动追流先停一会儿；要立刻追最新，请点「画质·线路 → 重新取流」")
            }
            return
        }
        autoRetryStamps.addLast(now)
        if (autoRetryStamps.size < AUTO_RETRY_MAX_IN_WINDOW) autoRetryBudgetWarned = false
        lastAutoRetryAtMs = now
        // ★诊断日志（只读）：真的放行 → 下面走 retryPlayback()（= 重新取流追到最新）
        LivePageTrace.note(
            "autoRetry.fire",
            "reason" to reason,
            "stampsInWindow" to autoRetryStamps.size,
            "maxInWindow" to AUTO_RETRY_MAX_IN_WINDOW,
            "isPlaying" to (delegate?.isPlaying == true),
        )
        miaoLogger() info "[live] 自动追流：$reason"
        // 状态文案交给 delegate（retry() → load() 会报"正在重新获取直播流…"），这里不抢着写
        // ★`fromAuto = true`：自动这一路**不解开**"没在播"的锁定、也不重新给 Activity 侧的预算 ——
        //   那是"用户明确要我再来一次"（手动「重新取流」）才该做的事；自动这一路只负责把画面追回来。
        retryPlayback(fromAuto = true)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 「设置 → 直播设置」里那几项的**读取口**（本页对 DataStore 只读不写）
    //
    // 四个消费端：默认画质（起播请求哪一档）、默认线路策略 + 自动重连（下发给播放核心）、
    // 双击暂停（双击画面要不要暂停）、退后台自动进小窗（onUserLeaveHint）。
    // ★设置项一个没少：本页只负责"读出来用"，改写全在「设置 → 直播设置」页（Compose）。
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 退后台要不要自动进小窗 —— 读「设置 → 直播设置 → 退后台自动进小窗」
     * （`SettingPreferences.LivePipOnBackground`，键 `live_pip_on_background`，默认**开**）。
     *
     * ★为什么每次都现读而不是缓存成字段：这一项在设置页能被用户当场改掉，
     *   下一次按 Home 就该生效；走的是主线程 O(1) 的内存快照（见 `SettingPreferences.liveSettings()`），
     *   没有 IO、也不阻塞 `onUserLeaveHint`。
     */
    private fun pipOnBackgroundEnabled(): Boolean =
        SettingPreferences.liveSettings().pipOnBackground

    /**
     * 退后台要不要**只出声**（声音继续、视频轨关掉）—— 读「设置 → 直播设置 → 后台继续直播」
     * （`SettingPreferences.LiveBackgroundPlay`，键 `live_background_play`，**默认关**）。
     *
     * ★唯一调用点 = [onStop]（第十二批把这条键重新接回本页；上一轮删掉「听音频」后它一度没有消费端）：
     *   · 开 → 退后台只关视频轨、声音继续（真省一份视频解码，见 `LivePlayerDelegate.setAudioOnly()`）；
     *   · 关（默认）→ 退后台暂停，与这条设置接回来之前**逐字一致**。
     * ★与 [pipOnBackgroundEnabled] 同一套写法：每次都现读，走主线程 O(1) 的内存快照
     *   （`SettingPreferences.liveSettings()`，无 IO），用户在设置页改完，下一次退后台立即生效。
     * ★本页对它**只读不写**：键、默认值常量、`Live.of()` 读取行一个字都没动。
     *
     * ⚠️验收前必读：这个键目前**没有 UI 入口** —— 「设置 → 直播设置」页在《直播设置精简》那一轮
     *   把它的显示删掉了（键/默认值保留），播放页底栏那颗「设置」按钮与其浮层也早已删除。
     *   要按"打开后台继续直播"这条步骤验收，得先补一个入口；那不在本页可改范围
     *   （见交付报告《直播优化-后台只出声-说明.md》的"未做/风险"一节）。
     */
    private fun backgroundPlayEnabled(): Boolean =
        SettingPreferences.liveSettings().backgroundPlay

    /**
     * 进房间请求哪一档画质 = 「设置 → 直播设置 → 默认画质」（`live_default_quality`）。
     *
     * 三个分支对应设置页那三种取值：
     * - 具体 qn（80/150/250/400/10000/15000/20000/30000）→ 原样请求；
     * - [SettingConstants.LIVE_QUALITY_LOWEST]（-2，"最低可用（省流）"）→ 请求已知最低的"流畅"；
     *   真正的"按房间可用列表挑最小"要读接口的 `accept_qn`，那是取流侧的事（delegate 禁改），
     *   这里只能表达成"请求最低档"，服务端仍会按房间能力给最接近的一档；
     * - [SettingConstants.LIVE_QUALITY_HIGHEST]（-1，默认）以及任何读不出来的值 → 原画。
     *   ★这条兜底就是**本次改动前的行为**（`requestedQn` 写死 `LiveAPI.QUALITY_ORIGIN`），
     *   所以老用户升级后画质行为不变。
     */
    private fun defaultRequestedQn(): Int {
        val qn = SettingPreferences.liveSettings().defaultQuality
        return when {
            qn == SettingConstants.LIVE_QUALITY_LOWEST -> LiveAPI.QUALITY_SMOOTH
            qn > 0 -> qn
            else -> LiveAPI.QUALITY_ORIGIN
        }
    }

    /**
     * 把「默认线路策略 / 自动重连」两项设置**下发给播放核心**（唯一的接线口）。
     *
     * | 设置键 | 取值 | 下发给 delegate 的什么 | 什么时候生效 |
     * |---|---|---|---|
     * | `live_line_policy` | [SettingConstants.LIVE_LINE_POLICY_FIRST] | `setAutoLineSwitch(false)` | 建 delegate 时（下一次自动换线就按新值） |
     * | `live_auto_reconnect` | false | `setAutoReconnect(false)` | 同上（下一次失败就只提示，不自动恢复） |
     *
     * ★"固定第一条"固定的是**线路列表里的第 1 条**（`candidates[0]`，本工程按"最稳优先"排过序：
     *   FLV → flv → avc → 第一台 CDN），也就是用户在「线路」弹窗里看到的那条 —— 详见 delegate 里
     *   `setAutoLineSwitch` 的注释。
     * ★为什么"立即生效"不会打断当前播放：这两个开关只在**错误恢复那一刻**被读
     *   （`handlePlayerError` / `advanceLine` / `handleStreamEnded`），改它们不重取流、不换源。
     * ★默认值即现状：`LIVE_LINE_POLICY_DEFAULT = 自动`、`LIVE_AUTO_RECONNECT_DEFAULT = true`，
     *   所以老用户升级后行为逐字不变（这也是这两个键当初把默认值定成这样的原因）。
     */
    private fun applyPlaybackPolicyToDelegate() {
        val live = SettingPreferences.liveSettings()
        delegate?.setAutoReconnect(live.autoReconnect)
        delegate?.setAutoLineSwitch(live.linePolicy != SettingConstants.LIVE_LINE_POLICY_FIRST)
    }

    /**
     * 双击画面要不要暂停/继续 = 「设置 → 直播设置 → 双击暂停」（`live_double_tap_pause`，默认开）。
     *
     * ★为什么每次双击都现读、而不是缓存成字段：这一项在设置页能被当场改掉，
     *   下一次双击就该生效；读的是主线程 O(1) 的内存快照（`SettingPreferences.liveSettings()`），
     *   没有 IO。和 [pipOnBackgroundEnabled] 是同一套写法。
     */
    private fun onDoubleTapPauseEnabled(): Boolean =
        SettingPreferences.liveSettings().doubleTapPause

    // ══════════════════════════════════════════════════════════════════════
    // 交互：「画质 · 线路」合并弹窗（★第七批：两颗按钮合并成一颗）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 「画质 · 线路」弹窗 —— 底栏那一颗 [qualityButton] 的唯一去向（★第七批合并）。
     *
     * 用户原话："「画质」和「线路」合并成一颗（两者本来就是'选用哪条流'）：一个按钮
     * （文案如「画质·原画」）→ 打开一个弹窗，里面两段/两个 Tab：清晰度 + 线路。"
     *
     * ## 一个弹窗、两段（[LiveListDialog] 的 `segments`）
     * | 段 | 标题 | 内容 | 点一条做什么 |
     * |---|---|---|---|
     * | 0 | 清晰度 | `desc（qn N）` + 说明行 | `requestedQn = qn` + `delegate.switchQuality(qn)` |
     * | 1 | 线路 | `线路 N　desc` + 说明行 | `delegate.switchToLine(index)` |
     *
     * 两段的既有行为**一条没少**（原来两个弹窗各自的那套原样搬过来）：
     * · **当前正在生效**的那一条打勾 ✓ + 主题色高亮（`actualQn` / `line.current`）；
     * · "我们请求了但服务端没给"的档位标 `已请求 · 当前不可用（需登录或大会员）`；
     * · 线路**可点选**（不是盲切下一条）；
     * · 副标题写"当前是什么"（`当前：原画（qn 10000）` / `当前：线路 1/6`）。
     *
     * ## 两个"不占底栏"的入口都在这里
     * · **「重新取流」**（中性按钮）—— ★第七批「刷新」改自动之后留下的**手动兜底**
     *   （用户："保留手动入口：放进「画质·线路」弹窗里一个「重新取流」小按钮（不占底栏）"）。
     *   行为与以前那颗「刷新」按钮**逐字一致**：[retryPlayback] → `delegate.retry()`
     *   （清限流预算 + 重新取流追到最新），点完关弹窗，让用户直接看顶栏状态。
     * · 清晰度/线路本来的两级入口也都在这里 —— 底栏因此不再需要「更多」按钮。
     *
     * ## 几何（用户点名"别改回去"的那条）
     * 内容区高度仍由 [dialogContentMaxHeightPx] 按**真机屏幕的 62%** 封顶并可滚；
     * 本弹窗比原来多了一条 Tab 栏，所以列表的上限再扣掉 [DIALOG_TAB_STRIP_DP]
     * （不扣的话"Tab + 列表 + 标题 + 按钮"会比 62% 高一截，正是"按钮被顶出屏幕"的病根）。
     * **没有「取消」按钮**：点条目即切换并关闭、点弹窗外/返回键关闭（用户明确要求过）。
     */
    private fun showStreamDialog() {
        dismissDialogs()
        // ★顺手把输入法收掉：底栏那颗按钮不会抢走输入框的焦点（按钮在触摸模式下不抢焦点），
        //   不清的话键盘会一直挂在那儿，而这个弹窗的内容会被键盘顶上去一截（用户在挑清晰度时更挤）。
        //   ★只收键盘、**不清空文本**（[dismissDanmakuInput] 的契约）。
        dismissDanmakuInput()
        val qualities = qualityOptions
        val lines = lineOptions
        if (qualities.isEmpty() && lines.isEmpty()) {
            toast("还没拿到清晰度/线路列表")
            return
        }
        // 每一段的"点一条做什么"按**段的下标**排（某一段没数据时会被跳过，所以不能写死下标）
        val pickers = ArrayList<(Int) -> Unit>(2)
        val segments = ArrayList<Segment>(2)
        if (qualities.isNotEmpty()) {
            val entries = qualities.map { option ->
                val isPlayingNow = option.qn == actualQn
                val requestedButUnavailable = option.qn == requestedQn && !isPlayingNow
                Entry(
                    label = "${option.desc}（qn ${option.qn}）",
                    note = when {
                        isPlayingNow -> "当前正在播放"
                        requestedButUnavailable -> "已请求 · 当前不可用（需登录或大会员）"
                        else -> null
                    },
                    current = isPlayingNow,
                )
            }
            segments.add(
                Segment(
                    title = "清晰度",
                    subheading = if (actualQnDesc.isNotBlank()) "当前：$actualQnDesc（qn $actualQn）" else null,
                    entries = entries,
                ),
            )
            pickers.add { index ->
                val option = qualities.getOrNull(index) ?: return@add
                requestedQn = option.qn
                setStreamStatus("正在切换清晰度…")
                // ★切画质 = 重新取流 + 换 MediaSource（不 release 播放器，保留最后一帧）
                delegate?.switchQuality(option.qn)
                // ★诊断日志（只读）：切清晰度的**起**（结果见 stream.ready / play.state）
                LivePageTrace.note(
                    "switch.quality",
                    "qn" to option.qn,
                    "desc" to option.desc,
                    "prevActualQn" to actualQn,
                    "delegate" to (delegate != null),
                )
            }
        }
        if (lines.isNotEmpty()) {
            val current = lines.firstOrNull { it.current }
            val entries = lines.map { line ->
                Entry(
                    label = "线路 ${line.index + 1}　${line.desc}",
                    note = if (line.current) "当前正在播放" else null,
                    current = line.current,
                )
            }
            segments.add(
                Segment(
                    title = "线路",
                    subheading = current?.let { "当前：线路 ${it.index + 1}/${lines.size}" },
                    entries = entries,
                ),
            )
            pickers.add { index ->
                setStreamStatus("正在切换线路…")
                delegate?.switchToLine(index)
                // ★诊断日志（只读）：切线路的**起**与结果（结果见 stream.ready / play.state）
                LivePageTrace.note(
                    "switch.line",
                    "index" to index,
                    "lines" to lines.size,
                    "delegate" to (delegate != null),
                )
            }
        }
        streamDialog = LiveListDialog(
            heading = "画质 · 线路",
            segments = segments,
            onPick = { segmentIndex, index -> pickers.getOrNull(segmentIndex)?.invoke(index) },
            neutralLabel = "重新取流",
            onNeutral = {
                // ★诊断日志（只读）：手动"重新取流"入口（不受防抖与预算限制）
                LivePageTrace.note("switch.retry.manual", "source" to "streamDialog")
                retryPlayback()
            },
        ).also { it.show() }
        holdControls()
    }

    // ══════════════════════════════════════════════════════════════════════
    // 交互：发弹幕
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 输入条那条**发送**路径（★第七批：发弹幕弹窗整块删除后，这里是唯一的发送实现）。
     *
     * 触发点只有一个：[danmakuInput] 的 `IME_ACTION_SEND`（软键盘的回车键 = 发送，接线在 [buildUi]）。
     * 用户原话："记得用安卓的 API 把它那个改成发送，我们就省了一个发送的按钮了，
     * 也省出那个弹窗按钮，很烦，感觉又多走一步，很麻烦。"
     *
     * ## 四条约定（与删掉的那个弹窗逐条对齐，行为一个不少）
     * | 阶段 | 行为 |
     * |---|---|
     * | 发送中 | 输入框 `isEnabled = false`（禁用）+ 右侧 [danmakuSendProgress] 转圈；[danmakuSending] 再防一层连点 |
     * | 成功 | **清空输入** + [dismissDanmakuInput]（收键盘、清焦点）+ toast「弹幕已发送」 |
     * | 失败 | **文本原样保留**（用户接着改重发）+ toast **服务端原文** + 同一句话落到输入条下方的 [danmakuInputError] |
     * | 通道没就绪 | 不走网络，直接把"弹幕通道还没准备好"写进同一行提示（老弹窗时代是一句 toast） |
     *
     * ## 为什么失败要"两条路一起给"
     * `lastSendError` 的取值就是给人看的（`发送失败(-400)：请求错误` / 敏感词 / 频率限制 /
     * `弹幕最多 40 字（当前 57 字）` / `上一条弹幕还在发送中，请稍候`），所以：
     * toast 负责"立刻知道"，[danmakuInputError] 负责"边改文本边看得见"
     * （6 秒后自动收起，见 [showDanmakuInputError]）。**绝不**自己编"可能是未登录、被风控"这种猜的话；
     * 真拿不到原因（理论上不会）才退回"原因未知 + 弹幕连接状态"那句兜底。
     *
     * ★防连点是**两层**：这里的 [danmakuSending] 管界面（禁用输入框），
     *   `LiveDanmakuClient.sendDanmaku` 里那个 `AtomicBoolean` 管网络（第二次直接回
     *   `上一条弹幕还在发送中，请稍候`）—— 两层都在，双击/连按回车都不会发出两条。
     */
    private fun submitDanmakuInput() {
        if (isFinishing || isDestroyed) return
        // ★该直播间关闭了弹幕 → **不再尝试发送**（用户要求："这个时候就不应该去显示那个发送弹幕的
        //   输入框"）。这是第二道门：第一道是 [applyDanmakuInputClosedUi] 把输入框置成不可用
        //   （IME 的回车动作根本不会触发）；但"正在发送的途中房间判定回来了"这种时序也要挡住。
        if (roomDanmakuClosed) return
        val text = danmakuInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            showDanmakuInputError("弹幕不能为空")
            return
        }
        if (danmakuSending) return
        val client = danmakuClient
        if (client == null) {
            // 与老弹窗同一句话（宿主还没建出来：room_init 未回 / 刚进房）
            showDanmakuInputError("弹幕通道还没准备好，稍后再试（若一直这样请反馈日志）")
            return
        }
        danmakuSending = true
        setDanmakuInputSending(true)
        clearDanmakuInputError()
        lifecycleScope.launch {
            // 锁死的接口：只回 Boolean，**原因在 client.lastSendError 里**（见其 KDoc）
            val ok = runCatching { client.sendDanmaku(text) }.getOrDefault(false)
            danmakuSending = false
            setDanmakuInputSending(false)
            if (ok) {
                // 成功：清空 + 收键盘（用户："成功清空输入并收起键盘"）
                danmakuInput.setText("")
                dismissDanmakuInput()
                toast("弹幕已发送")
                return@launch
            }
            // 失败：文本保留（一个字都不动），原因 = 服务端原文
            val reason = client.lastSendError?.takeIf { it.isNotBlank() }
            val message = reason
                ?: "弹幕发送失败（原因未知；弹幕状态：${danmakuConnLabel()}）"
            showDanmakuInputError(message)
            toast(message)
        }
    }

    /** 发送中：禁用输入框 + 转圈（界面状态只在主线程写，不需要加锁） */
    private fun setDanmakuInputSending(sending: Boolean) {
        if (!::danmakuInput.isInitialized) return
        danmakuInput.isEnabled = !sending
        if (::danmakuSendProgress.isInitialized) {
            danmakuSendProgress.visibility = if (sending) View.VISIBLE else View.GONE
        }
        if (sending) holdControls()
    }

    /** 输入条是否"正在用"（有焦点 / 正在发送）—— 控制条自动隐藏与单击手势都要为它让路 */
    private fun isDanmakuInputActive(): Boolean =
        (::danmakuInput.isInitialized && danmakuInput.isFocused) || danmakuSending

    /**
     * 把失败原因写到输入条下方那一行（[danmakuInputError]），并**重新计时 [DANMAKU_INPUT_ERROR_HIDE_MS]**
     * 后自动收起。
     *
     * ★为什么要自动收：这一行会让底栏长高一行，而底栏顶边就是弹幕列表区的底边
     *   （[measurePortraitStage]）—— 留着不收，列表就一直矮一截。
     * ★计时前先撤掉上一条延迟任务：连续两次失败时，旧任务不能把**新的**提示提前收掉。
     */
    private fun showDanmakuInputError(message: String) {
        if (!::danmakuInputError.isInitialized) return
        danmakuInputError.text = message
        danmakuInputError.visibility = View.VISIBLE
        inputErrorHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { clearDanmakuInputError() }
        inputErrorHideRunnable = runnable
        mainHandler.postDelayed(runnable, DANMAKU_INPUT_ERROR_HIDE_MS)
    }

    /** 收起失败提示（下一次发送开始 / 6 秒到点 / 页面销毁时都走它） */
    private fun clearDanmakuInputError() {
        if (!::danmakuInputError.isInitialized) return
        if (danmakuInputError.visibility == View.GONE) return
        danmakuInputError.text = ""
        danmakuInputError.visibility = View.GONE
    }

    /**
     * 收起键盘 + 清焦点（发送成功 / 进 PiP / 单击画面时用）。
     * ★输入内容**一个字都不动**：失败重发要靠它，见 [submitDanmakuInput]。
     */
    private fun dismissDanmakuInput() {
        if (!::danmakuInput.isInitialized) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        runCatching { imm?.hideSoftInputFromWindow(danmakuInput.windowToken, 0) }
        danmakuInput.clearFocus()
    }

    // ══════════════════════════════════════════════════════════════════════
    // 交互：弹幕开关 / 重连
    // ══════════════════════════════════════════════════════════════════════

    private fun toggleDanmaku() {
        if (danmakuHost == null) return
        // ★连接已经判死（ConnState.Failed）时，这个按钮的语义变成"重连"：
        //   因为 Failed 之后 B 路不会自己再连，用户不点就永远没有弹幕。
        if (danmakuEnabled && danmakuConnState == ConnState.Failed) {
            restartDanmaku()
            return
        }
        applyDanmakuEnabled(!danmakuEnabled)
    }

    /**
     * 开/关本页弹幕（**会话级**动作）：底栏「弹幕」按钮的唯一实现。
     * （本轮之前底栏「设置」弹窗里还有一项「显示弹幕」也调它；那个弹窗已按用户要求删掉，
     *   所以现在只剩这一条调用链，语义更干净：**本页开关就是本页开关**。）
     *
     * ★只改本页状态，**一行 DataStore 都不写**：它是"临时关一下"，
     *   想持久化请去「设置 → 直播设置」（那边的 `live_danmaku_enable` 是同一个语义位的键）。
     *
     * ★★**绝不回写点播弹幕设置**（用户明确要求："直播里关弹幕不许写 `default_danmaku_show`"）★★
     *   本函数一行 DataStore 都不写，这是**刻意的**：
     *   · 直播弹幕的可见性本来就 = 点播那三层开关 ∩ `live_danmaku_enable`
     *     （见 `LiveDanmakuSettings.from()` 的 `visible`）。往点播的 `default_danmaku_show`
     *     写一次 false，用户点播那边的弹幕会**跟着消失**，而直播页没有把它恢复回来的入口 ——
     *     那是"我只是在直播里关一下弹幕，结果点播也坏了"的经典事故；
     *   · 本页**不提供**持久化入口（写键的地方在设置页），所以这条事故路径从源头就断了。
     */
    private fun applyDanmakuEnabled(enabled: Boolean) {
        danmakuEnabled = enabled
        // 说明：底栏开关是**会话级**的（本轮按用户要求删掉了底栏「设置」弹窗，而 live_danmaku_enable
        //   的写入点原本就在那个弹窗里；设置页那一行也早已删显示）。要恢复"记住开关状态"很容易：
        //   在这里把 enabled 写进 SettingPreferences.LiveDanmakuEnable 即可 —— 但**绝不能**碰点播的
        //   default_danmaku_show（在直播间关弹幕不该把点播也关掉）。当前按"会话级"交付，等用户确认。
        danmakuLayer.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) danmakuHost?.start() else danmakuHost?.stop()
        updateDanmakuButton()
        renderStatus()
    }

    /**
     * 强制重连弹幕。
     * ★必须走 `stop()` + `start()` 这一对：宿主的 `start()` 只是把 `active` 置 true，
     *   而它内部的 `LaunchedEffect(on)` 只在 `on` **变化**时才重跑 `connect()`；
     *   已经在 true 上再置一次 true 不会触发任何东西。先 stop 把它翻成 false，
     *   再 start 才能真正重新走一遍 `client.connect()`（B 路的 `connect()` 在 Failed
     *   或 close 之后的重复调用是允许的，且会重建 scope）。
     */
    private fun restartDanmaku() {
        val host = danmakuHost ?: return
        danmakuEnabled = true
        danmakuLayer.visibility = View.VISIBLE
        host.stop()
        host.start()
        toast("正在重新连接弹幕…")
        updateDanmakuButton()
    }

    private fun updateDanmakuButton() {
        // ★2026-09-26 用户拍板（底栏加到 5 颗后字号会从 14sp 掉到 9sp）：**文案全部缩成 2 字**，
        //   用"整行最宽文案"决定字号档 ⇒ 5 颗都是 2 字 ⇒ 字号**保持 14sp**（与 4 颗时一样大）。
        //   「弹幕开/弹幕关」的**状态**改用**亮度**表示（关 = 变暗 45%），不再靠多一个字。
        danmakuButton.alpha = if (danmakuEnabled) 1f else 0.45f
        danmakuButton.text = when {
            !danmakuEnabled -> "弹幕"
            // ★第八批（用户实测第 3 条"文字不换行不省略"）：连接判死时的文案从「弹幕重连」
            //   缩成「重连」—— 4 个字会把**整行**字号拖低一档（[applyBottomBarTextSizes] 按最宽文案挑档），
            //   而 2 个字与其它三颗（画质 / 画中画 / 旋转）同量级，整行永远停在最大档。
            //   语义不变：这颗按钮此时点一下就是 [restartDanmaku]（见 [toggleDanmaku]）。
            danmakuConnState == ConnState.Failed -> "重连"
            else -> "弹幕"
        }
        // ★「弹幕关 / 弹幕开 / 重连」是三档不同字数 → 文案一变就把字号档位重算一遍
        //   （理由同 delegateListener 里那处：不依赖"改文字一定触发底栏布局"这个假设）
        applyBottomBarTextSizes(bottomBarAvailableWidthPx())
    }

    // ══════════════════════════════════════════════════════════════════════
    // ★★第十四批：底栏「设置」→ 直播设置弹窗（**回来了**，这次用 Compose 那套外壳）
    //
    // 历史（免得后人以为功能丢过）：这里原来有一个 `showLiveSettingsDialog()` —— 底栏「设置」弹的、
    // 用**原生控件拼的**"直播设置"弹窗（默认画质 / 默认线路策略 / 退后台自动进小窗 / 后台继续直播 /
    // 自动重连 / 双击暂停 / 显示弹幕 共 7 项 + 两个二级选择列表）。第二批被用户要求删掉
    // （"把播放底栏区域的设置按钮给去掉吧……用户想设置自己退出来再去设置"），
    // 按钮 + 弹窗 + 两个二级列表 + 三个写入小工具一起删，入口只剩「设置 → 直播设置」页。
    //
    // 用户现在又点名要它（原话见 [settingButton] 的 KDoc），而且**指定了弹窗长什么样**：
    // "记得用我的那个自定义的全屏弹窗，不管你怎么转屏，它都会自己适配。就是我的那个底栏筛选的那个弹窗。"
    // 于是：
    // · 外壳 = `AutoSheetDialog`（与首页直播 Tab 的「筛选」弹窗 `HomeLiveFilterSheet` **同一套**，
    //   转屏自适应是它自带的：`DialogFullScreen` 按宿主 decorView 尺寸重设 Dialog 窗口，见那个文件）；
    // · 内容 = `liveDanmakuSettingPreferenceItems()`（★本轮起**只有弹幕 4 项**：字号 / 不透明度 /
    //   速度 / 显示区域；这四项与「设置 → 直播设置」页的弹幕组是**同一份实现、同一批键与默认值**，
    //   读写口都是 `ProvidePreferenceLocals` + DataStore，所以改完立即生效、也立即落盘。
    //   ★播放类 4 项（默认画质 / 默认线路策略 / 自动重连 / 自动旋转）与「每行卡片数」按用户要求
    //     从弹窗移除 —— 它们仍在设置页，**一个项都没少**）；
    // · 宿主 = compose 模块的 [LiveSettingSheetHost]（本页在 app 模块，**没有 Compose 编译器插件**，
    //   写不了 `@Composable`；照 [LiveDanmakuOverlayHost] 那条"View 桥"的老路）。
    //
    // ★本页仍然**只读不写**那些设置键（[defaultRequestedQn] / [applyPlaybackPolicyToDelegate] /
    //   [pipOnBackgroundEnabled] / [shouldKeepPlayingInBackground] / [onDoubleTapPauseEnabled] /
    //   [autoRotateEnabled] 全是读口）—— 写口只有弹窗里那些 Compose 设置项。
    // ★底栏「弹幕」按钮照旧是**会话级临时开关**（[applyDanmakuEnabled] 一行 DataStore 都不写）：
    //   它从来没写过设置，现在也不会、更不能去写点播的 `default_danmaku_show`。
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 弹直播设置弹窗（底栏「设置」的唯一去向）。
     *
     * 三步都是本页既有那套写法（与 [showStreamDialog] 逐条对应）：
     * ① **PiP 里不弹** —— 与"底栏按钮在 PiP 里本来就 `INVISIBLE`"是同一件事的两道门：
     *    按钮点不到是第一道，这里是显式的第二道（`isInPictureInPictureMode` 的门控在本页
     *    到处都是，见 [enterPipMode] / [applyControlsVisibility]）；
     * ② [dismissDialogs]：同一时刻只留一个弹窗 —— 它会**先把这个弹窗收掉再弹新的**
     *    （由 [showLiveSettingSheet] 先调用、[showStreamDialog] 也调它，两个方向都对称）；
     * ③ [dismissDanmakuInput]：收键盘、清焦点（输入条在弹窗下面，留着键盘只会让弹窗更挤；
     *    只收键盘、**不清空文本**，那是 [dismissDanmakuInput] 的契约）。
     *
     * ★宿主**懒创建**：第一次点「设置」才 new + addView（`0×0`，见 [liveSettingSheetHost]），
     *   没点过的人一分钱不花。创建之后一直留着复用（弹窗开关只是 Compose 状态翻转）。
     */
    private fun showLiveSettingSheet() {
        if (isInPictureInPictureMode) return
        // ★诊断日志（只读）：弹窗打开（"点了设置没反应"这类问题一眼能看出是没弹还是弹了又没了）
        LivePageTrace.note(
            "settingSheet.open",
            "room" to rawRoomId,
            "landscape" to isPageLandscape(),
            "pip" to isInPictureInPictureMode,
            "hostReady" to (liveSettingSheetHost != null),
        )
        dismissDialogs()
        dismissDanmakuInput()
        val host = liveSettingSheetHost ?: LiveSettingSheetHost(this) { onLiveSettingSheetDismissed() }
            .also { created ->
                // 0×0：弹窗是独立窗口，宿主占多大地方完全不影响版式（见字段 KDoc）。
                // 它必须进窗口树 —— `Dialog` 需要一个已 attach 的 View 才能建窗口。
                rootLayout.addView(
                    created,
                    FrameLayout.LayoutParams(0, 0),
                )
                liveSettingSheetHost = created
            }
        host.show()
    }

    /** 收起直播设置弹窗（幂等）。没建过宿主 / 本来就没弹时什么都不做 */
    private fun dismissLiveSettingSheet() {
        liveSettingSheetHost?.dismiss()
    }

    /**
     * 弹窗关掉之后：把**可能被改过**的那几项设置再下发一次。
     *
     * 弹窗里的项是"改一项立即写 DataStore"的，绝大多数（弹幕字号/不透明度/速度/显示区域）
     * 本来就是**订阅式**的（直播弹幕浮层 `LiveDanmakuSettings.watch()`），不需要本页做任何事。
     * ★本轮起弹窗里**只剩弹幕 4 项**（播放类 4 项按用户要求移除、仍在设置页），所以下面这两下
     *   在正常情况下已经是"什么都不用做"——**保留**它们是因为：① 两处都幂等、无 IO、无副作用；
     *   ② 万一以后播放类项回到弹窗里，"改完当场生效"这条保证不用再补一遍（与第二批那个原生弹窗
     *   "改动后立即生效的那份在设置弹窗里再下发一次"是同一个做法，只是那个弹窗已删）：
     * · [applyPlaybackPolicyToDelegate]：自动重连 / 线路策略 → 下发给播放核心（幂等、无 IO）；
     * · [applyAutoRotatePolicy]`(preserveManualChoice = true)`：自动旋转 → 重设 `requestedOrientation`。
     *   ★带 `preserveManualChoice`：用户手动点过「旋转」之后（[orientationPinnedByUser]），
     *     这里**不许**把他的选择顶掉 —— 与 [onConfigurationChanged] 那一处调用同一个语义。
     *     （★本轮：自动旋转=开时那个钉住是**一次性**的，设备一动就被
     *     [releaseOneShotOrientationHold] 解开，见 [pinOrientationByUser]。）
     * ★[defaultRequestedQn]（默认画质）**不在这里重取流**：它只在"进房请求画质"那一刻有意义，
     *   正在播的流不该因为用户在设置里选了一档就当场重取（那是用户在弹窗里没要求的事）。
     */
    private fun onLiveSettingSheetDismissed() {
        applyPlaybackPolicyToDelegate()
        applyAutoRotatePolicy(preserveManualChoice = true)
        // ★诊断日志（只读）：弹窗关闭 + 下发结果（"改完没生效"这类问题看这一行）
        LivePageTrace.note(
            "settingSheet.dismiss",
            "room" to rawRoomId,
            "autoReconnect" to SettingPreferences.liveSettings().autoReconnect,
            "autoRotate" to autoRotateEnabled(),
            "requestedOrientation" to requestedOrientation,
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // 交互：旋转 / 画中画
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 底栏「旋转」：手动把方向切到当前方向的另一侧（竖↔横）。
     *
     * ★★本轮重定语义（用户实测："你点旋转按钮之后，你旋转方向它是不跟随的，除非你手动按那个旋转
     *   按钮才能切换方向。这个我觉得和那个设置页开启自动旋转冲突"）——**按设置分两档**：
     *
     * | 「自动旋转」 | 点这一下之后 | 手机再转 | 依据 |
     * |---|---|---|---|
     * | **开**（默认） | 一次性：这一次切到另一侧 | **继续跟随**（设备一动就交还给 `FULL_SENSOR`）—— 不再"一按就永久锁死" | [pinOrientationByUser] + [deviceOrientationSentinel] |
     * | **关** | 唯一的方向开关：按一次切一次 | **不跟随**（永久钉在当前这一侧；想再切还是按它） | [pinOrientationByUser] 的永久档 |
     *
     * ★两个入口共用同一套"钉多久"：[exitFullscreenToPortrait]（横屏按返回 = 退出全屏）也调
     *   [pinOrientationByUser]，切出来的"竖屏"与这里逐字相同（都是
     *   [ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT]，允许 180° 翻转但不跟随重力横过去）。
     * ★钉住仍然是**会话级**的：一个字节都不写 DataStore，`live_auto_rotate` 照旧是设置里那个值；
     *   退出直播间再进 = 按设置重新定方向。
     */
    private fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // ★本轮：以前这里是 `orientationPinnedByUser = true`（**永久**置位、且全文件没有清除点），
        //   自动旋转=开时点一次就再也不跟随 —— 现在改走"按设置分两档"的那一个口。
        pinOrientationByUser()
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        // ★诊断日志（只读）：手动「旋转」按钮的方向决策
        LivePageTrace.note(
            "orientation.toggle",
            "configLandscape" to landscape,
            "requestedOrientation" to requestedOrientation,
            "autoRotate" to autoRotateEnabled(),
            "page" to (if (::rootLayout.isInitialized) "${rootLayout.width}x${rootLayout.height}" else "-"),
        )
        showControlsTemporarily()
        // ★第十一批第 1 条：**底部「旋转」按钮也要重排沉浸式**（用户点名："我点击旋转按钮，
        //   再旋转再旋转，你没有更新，它还是隐藏了"）。这里先同步一次（用户在横屏点了旋转、
        //   窗口方向如果已经翻过来就当场生效）；真正可靠的收敛点仍是转屏之后**尺寸落定**那一次
        //   —— [installPageLayoutWatchers] 的尺寸监听会再调一次，两处都幂等。
        syncImmersivePolicy()
    }

    /**
     * 「设置 → 直播设置 → 自动旋转」（`live_auto_rotate`，默认**开**）的**消费端**。
     *
     * 用户原话："我想在直播设置里面添加一个开关，就是屏幕方向转动自动旋转，竖屏它就竖屏，
     * 横屏它就是全屏。" 落成两行：
     * | 设置值 | requestedOrientation | 效果 |
     * |---|---|---|
     * | 开（默认） | [ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR] | 跟随重力感应：竖着拿=竖屏、横过来=横屏（本页恒为沉浸式全屏，所以横屏即全屏看） |
     * | 关 | [ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT] / [ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE] | 按**进入房间那一刻**的方向锁死（允许 180° 翻转，但不跟着从竖变横） |
     *
     * ★为什么默认开就必须"什么都不改"：Manifest 里 `LivePlayerActivity` 没有写 `screenOrientation`
     *   （AndroidManifest.xml:86-91），现状本来就是跟随系统；`configChanges` 又已经包含
     *   `orientation|screenSize`，转屏不重建 Activity。所以默认开 = 与改动前逐字一致，
     *   老用户升级后不会遇到"转屏没反应"。
     * ★为什么关掉时锁的是"进入时的方向"而不是 `UNSPECIFIED`：`UNSPECIFIED` 只是"我不表态"，
     *   系统照样会把 Activity 跟着重力转 —— 那就等于开关没生效。要"锁"就必须给出一个明确值。
     *   （设置页 KDoc 里写的 `UNSPECIFIED` 是"不跟随"的**意图**写法，这里按用户原话
     *   "关=锁定进入时的方向"落成明确值，行为更贴合。）
     * ★读取口：`SettingPreferences.liveSettings().autoRotate`（主线程 O(1) 内存快照，
     *   键 `live_auto_rotate` 由设置页那边登记，见 `SettingPreferences.LiveAutoRotate`）。
     *   设置页改完要**重进直播间**才生效 —— 与本页其它设置项（默认画质等）保持一致，
     *   避免"正在看的时候屏幕自己转一下"。
     *
     * @param preserveManualChoice true = 用户在本次会话里**手动定过方向**（点过底栏「旋转」[toggleOrientation]，
     *   或按过返回键退出全屏 [exitFullscreenToPortrait]），不要顶掉他的选择
     *   （[onConfigurationChanged] 走这条；[onCreate] 走 false，进房必须按设置定方向）
     *   ★本轮起"钉住"分两档（见 [pinOrientationByUser]）：自动旋转=**关**时它是永久钉住；
     *   自动旋转=**开**时它只是"这一次切换"的钉子 —— [deviceOrientationSentinel] 会在设备真的被
     *   转动的那一刻解开它（[releaseOneShotOrientationHold] → 回到本函数 → `FULL_SENSOR` 继续跟随）。
     */
    private fun applyAutoRotatePolicy(preserveManualChoice: Boolean = false) {
        if (preserveManualChoice && orientationPinnedByUser) {
            // ★诊断日志（只读）：这一条在"自动旋转=开 + 刚点过旋转/按过返回"时也会出现 ——
            //   那是**意料之中**的：那一次切换正靠它挡住"被配置回调原样弹回去"，
            //   设备一动就会走 [releaseOneShotOrientationHold] 把方向交还给自动旋转。
            LivePageTrace.note(
                "orientation.policy.skip",
                "reason" to "pinnedByUser",
                "preserveManualChoice" to preserveManualChoice,
                "autoRotate" to autoRotateEnabled(),
                "requestedOrientation" to requestedOrientation,
            )
            return
        }
        // ★★第九批（**与点播对齐**）：**PiP 里一条方向都不推导**。
        //
        // 点播那条路早就把这条结论写成代码并挡在门口（`comm/delegate/player/PlayerController.kt`）：
        // ```
        // :341-342  fun updatePlayerMode(...)  { if (player?.isPicInPicMode == true) return ... }
        // :347-348  fun onHostSizeChanged(...) { if (player?.isPicInPicMode == true) return ... }
        //           KDoc 原话："画中画：窗口方向 ≠ 设备方向，别按它推导"
        // ```
        // 本页下面那句 `resources.configuration.orientation` 读的正是**窗口**方向，而进/出小窗都会走
        // [onConfigurationChanged]（进小窗时窗口先缩成小窗尺寸 → 配置回调 → PiP 回调）：16:9 房间在
        // 竖屏手机上，小窗是**横的** ⇒ 这里会把"自动旋转关 = 锁进入房间时的方向"里的竖屏**改写成
        // SENSOR_LANDSCAPE**，用户退出小窗后页面直接横过来 —— 而他要的是"竖屏它就竖屏"。
        // 小窗期间系统本来就不理会 requestedOrientation（小窗形状只由 PiP 参数里的比例决定），
        // 退出小窗时窗口恢复到原尺寸会再走一次 [onConfigurationChanged]，那时再断言方向即可
        // （窗口尺寸/方向与进小窗前完全一致、配置没变时，本页的 `requestedOrientation` 也一个字节没被动过，
        //   正是我们想要的"保持进小窗前的方向"）。
        if (isInPictureInPictureMode) return
        if (autoRotateEnabled()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            // ★诊断日志（只读）：自动旋转开 → 跟随重力
            LivePageTrace.note("orientation.policy", "autoRotate" to true, "requestedOrientation" to requestedOrientation)
            return
        }
        // 关：锁"进入时的方向"。onCreate 时读的是设备当时的方向（本页还没施加过任何约束）；
        // 转屏回调里再走一遍也只是把同一个方向再断言一次（幂等）。
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        // ★诊断日志（只读）：自动旋转关 → 锁进入时的方向
        LivePageTrace.note(
            "orientation.policy",
            "autoRotate" to false,
            "configLandscape" to landscape,
            "requestedOrientation" to requestedOrientation,
        )
    }

    /** 自动旋转开关（`live_auto_rotate`，默认开）；读不到（快照还没热）时按默认值 true 处理 */
    private fun autoRotateEnabled(): Boolean =
        SettingPreferences.liveSettings().autoRotate

    // ══════════════════════════════════════════════════════════════════════
    // ★本轮：「旋转」按钮 / 返回键钉的方向，钉多久（自动旋转=开 → 一次性；关 → 永久）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * ★本轮：把"用户手动定方向"这件事按当前的「自动旋转」设置记下来
     * （[toggleOrientation] 与 [exitFullscreenToPortrait] 两个入口共用 —— 切方向的**执行**仍然
     * 各走各的既有路径，本函数只管"钉多久"这一件事）。
     *
     * | 「自动旋转」 | 置位 | 谁解开 | 用户看到的行为 |
     * |---|---|---|---|
     * | **关** | [orientationPinnedByUser] 永久置位（会话级） | 只有退出直播间 | 「旋转」是**唯一**的方向开关：按一次切一次，手机怎么转都不跟随 |
     * | **开** | 置位 **+ 武装 [deviceOrientationSentinel]** | **设备真的被转动**那一刻（[releaseOneShotOrientationHold]） | 这一次切换照做（不会被紧接着的配置回调弹回去），之后**继续跟随** |
     *
     * ★为什么"开"这一档非要盯设备姿态不可：钉住与
     *   [ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR] 天生互斥 —— `FULL_SENSOR` 读的是
     *   **手机此刻的物理姿态**，所以"钉一次、下一个配置回调就复位"等于没钉：手机还竖着的时候
     *   把方向交还回去，系统会立刻把刚切出来的横屏**原样弹回去**（用户看到的是"点了旋转，
     *   闪一下又回来"）。这不是推测 —— 第六批修的就是同一机制的**反方向**（不钉住时
     *   "按了返回、闪一下又回全屏"，正是用户真机实测报上来的），见 [handleBack] 的 KDoc。
     *   所以"交还"的时机只能是"**手机自己动了**"。
     */
    private fun pinOrientationByUser() {
        orientationPinnedByUser = true
        if (autoRotateEnabled()) {
            armDeviceOrientationSentinel()
        } else {
            // 自动旋转=关：钉住就是"唯一的方向开关"，永久有效，不需要（更不该有）哨兵
            disarmDeviceOrientationSentinel()
        }
    }

    /**
     * ★本轮新增：**「一次性方向钉住」的释放哨兵** —— 只盯一件事：钉住之后，设备姿态有没有真的变过。
     *
     * ## 它读什么
     * 加速度计（[Sensor.TYPE_ACCELEROMETER]）的**粗档姿态**：[deviceOrientationBucket] 把一帧读数
     * 归成 `竖 / 倒竖 / 横 / 倒横` 四档（平放与 45° 边界上的读数一律忽略），第一帧可信读数就是
     * "钉住那一刻手机的姿态"（[orientationSentinelBaseline]）。之后**连续**
     * [DEVICE_BUCKET_CONFIRM_SAMPLES] 帧读到**另一个档**，就认定用户自己转了手机 →
     * [releaseOneShotOrientationHold] 把方向交还给「自动旋转」。
     *
     * ## ★★三条纪律（一条都不能破）
     * · **PiP 里一条方向都不推导**（铁律）：回调第一句就判 [isInPictureInPictureMode]，命中直接
     *   释放并停表 —— 小窗里"窗口方向 ≠ 设备方向"，钉住本来也没有意义；
     * · **只在"自动旋转=开 且 刚手动钉过一次"这段窗口里注册**，一释放就注销：不常驻、不轮询；
     *   "自动旋转=关"那一档的钉住是**永久**的，压根不走这条路（见 [pinOrientationByUser]）；
     * · 释放只做一件事 —— 调 [applyAutoRotatePolicy]（**复用既有那条策略口**，不另写一套方向逻辑）。
     */
    private val deviceOrientationSentinel = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // 没钉住（或已被释放）时它不该还在听 —— 兜底停表，绝不留一个空转的传感器
            if (!orientationPinnedByUser) {
                disarmDeviceOrientationSentinel()
                return
            }
            // ★铁律：PiP 里不按设备方向推导
            if (isInPictureInPictureMode) {
                releaseOneShotOrientationHold("pip")
                return
            }
            val bucket = deviceOrientationBucket(event) ?: return
            val baseline = orientationSentinelBaseline
            if (baseline == null) {
                // 第一帧可信读数 = "钉住那一刻手机的姿态"；手机平放时会一直读不到，那就先不定基线
                orientationSentinelBaseline = bucket
                orientationSentinelPendingBucket = null
                orientationSentinelPendingSamples = 0
                return
            }
            if (bucket == baseline) {
                orientationSentinelPendingBucket = null
                orientationSentinelPendingSamples = 0
                return
            }
            if (orientationSentinelPendingBucket == bucket) {
                orientationSentinelPendingSamples++
            } else {
                orientationSentinelPendingBucket = bucket
                orientationSentinelPendingSamples = 1
            }
            if (orientationSentinelPendingSamples >= DEVICE_BUCKET_CONFIRM_SAMPLES) {
                releaseOneShotOrientationHold("deviceRotated")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /**
     * 武装释放哨兵（幂等）：把基线重置成"还没读到"，并在需要时注册加速度计。
     *
     * ★注册失败（没有加速度计 / 被系统挡住）时**绝不**留一个永远解不开的钉住 ——
     *   当场把方向交还给自动旋转：宁可退回"跟随"，也不要退回用户投诉的那个"永久锁死"。
     */
    private fun armDeviceOrientationSentinel() {
        orientationSentinelBaseline = null
        orientationSentinelPendingBucket = null
        orientationSentinelPendingSamples = 0
        // ★铁律：PiP 里不按设备方向推导 —— 不注册，也不留下"没人能释放"的钉住
        if (isInPictureInPictureMode) {
            releaseOneShotOrientationHold("pip")
            return
        }
        if (orientationSentinelRegistered) return
        val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            releaseOneShotOrientationHold("sentinelUnavailable")
            return
        }
        orientationSentinelRegistered = manager.registerListener(
            deviceOrientationSentinel,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
        )
        if (!orientationSentinelRegistered) {
            releaseOneShotOrientationHold("sentinelUnavailable")
            return
        }
        // ★诊断日志（只读）：哨兵武装（"点了旋转之后还跟不跟随"从这一行往下看）
        LivePageTrace.note(
            "orientation.sentinel.arm",
            "requestedOrientation" to requestedOrientation,
            "landscape" to isPageLandscape(),
        )
    }

    /** 注销释放哨兵（幂等）；加速度计是系统服务，注册与注销必须一一对应 */
    private fun disarmDeviceOrientationSentinel() {
        orientationSentinelBaseline = null
        orientationSentinelPendingBucket = null
        orientationSentinelPendingSamples = 0
        if (!orientationSentinelRegistered) return
        orientationSentinelRegistered = false
        (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.unregisterListener(deviceOrientationSentinel)
    }

    /**
     * 释放「一次性方向钉住」：把方向交还给「自动旋转」那条**既有**策略（[applyAutoRotatePolicy]）。
     *
     * 触发点只有三类，都在"用户确实又动过手机 / 钉住已经没有意义 / 根本没法监听"里：
     * · [deviceOrientationSentinel] 读到设备姿态真的变了（用户自己转了手机）；
     * · 进出小窗（PiP 里方向判据一律早退）；
     * · 传感器不可用（宁可退回"跟随"，也不留一个解不开的钉住）。
     *
     * ★「自动旋转=**关**」时它是空操作：那一档的钉住就是"唯一的方向开关"，
     *   只能靠"再按一次旋转"或"退出直播间"改，不会被设备姿态解开。
     */
    private fun releaseOneShotOrientationHold(reason: String) {
        disarmDeviceOrientationSentinel()
        if (!orientationPinnedByUser || !autoRotateEnabled()) return
        orientationPinnedByUser = false
        // ★诊断日志（只读）：一次性钉住被释放
        LivePageTrace.note(
            "orientation.hold.release",
            "reason" to reason,
            "pip" to isInPictureInPictureMode,
            "landscape" to isPageLandscape(),
        )
        // ★★只调这一个口：方向该是什么仍然由 [applyAutoRotatePolicy] 说了算
        //   （自动旋转=开 → `FULL_SENSOR`，即用户要的"继续跟随"）
        applyAutoRotatePolicy()
    }

    /**
     * 一帧加速度计读数 → **设备姿态粗档**：`0`=竖 `1`=倒竖 `2`=横 `3`=倒横；
     * `null` = 这一帧读不出可信的横竖，调用方按"没读到"处理。
     *
     * 只看重力在**屏幕平面**上那两根轴的分量（x = 屏幕向右、y = 屏幕向上；z = 屏幕法线）：
     * 哪根轴的 |分量| 明显更大就是哪一族（[DEVICE_AXIS_MARGIN]），符号决定正反。两种帧返回 null：
     * · **平放**（屏幕大致朝上/朝下）：平面分量小于 [FLAT_GRAVITY_RATIO]×g ——
     *   手机躺桌上时横竖本来就读不准，把噪声当成"用户转了手机"会让方向乱跳；
     * · **45° 边界**：两根轴的分量太接近（手机正斜着）—— 同样是噪声。
     *
     * ★分四档（而不是只分竖/横）是为了让**任意 90° 以上的真实转动**都能被认出来：
     *   竖↔横、竖↔倒竖、横↔倒横都会换档；同一档内的小晃动不会换档。
     *   符号只用来分"正/反"，不参与任何"哪边算竖屏"的判定（那是 [isPageLandscape] 的事）。
     */
    private fun deviceOrientationBucket(event: SensorEvent): Int? {
        val values = event.values
        if (values.size < 3) return null
        val x = values[0]
        val y = values[1]
        val ax = abs(x)
        val ay = abs(y)
        if (maxOf(ax, ay) < SensorManager.GRAVITY_EARTH * FLAT_GRAVITY_RATIO) return null
        if (ax > ay * DEVICE_AXIS_MARGIN) return if (x > 0f) 2 else 3
        if (ay > ax * DEVICE_AXIS_MARGIN) return if (y > 0f) 0 else 1
        return null
    }

    /**
     * 用户点底栏「画中画」手动进小窗（与"退后台自动进"是两条路，见 [onUserLeaveHint]）。
     *
     * ★★第五批第 3 条：**进小窗的动作一开始就把控制条收掉**（顶栏 + 底栏），
     *   而不是等 `onPictureInPictureModeChanged(true)` —— 那个回调要等窗口缩完才来，
     *   中间那 1~2 秒里控制条会挡在小窗画面上（用户原话："它虽然只有一两秒的显示时间，
     *   但是它会挡住"）。同时置 [pipEntryPending]，把这段过渡期关进 [controlsAllowed] 的门里，
     *   免得 [onConfigurationChanged] 的 `showControlsTemporarily()` 又把它打开。
     *
     * ★失败（系统拒掉 / 26 以下）时把标记清掉，否则控制条会一直被门控压着不显示。
     */
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            toast("当前系统不支持画中画")
            return
        }
        // ★已经在小窗里时什么都不做。
        //   「画中画」的入口是**底栏**那颗按钮（[pipButton] → [orderedBottomButtons]），
        //   它在 PiP 期间不显示；而顶栏会按 4 秒计时露出来，用户点一下不该再发一次进入请求
        //   （再进一次必然失败，紧接着的恢复分支还会白闪一次控制条）。
        if (isInPictureInPictureMode) return
        // 进 PiP 前先把弹窗/手势提示收掉：清晰度/线路是**独立窗口**的 Dialog，在 PiP 里会浮成
        // 一个怪东西（气泡这一项是页内 View，收掉的理由只是"小窗里不该有东西亮着"）
        dismissDialogs()
        gestureHud.hide()
        pipEntryPending = true
        hideControlsForPip()
        val entered = runCatching { enterPictureInPictureMode(buildPipParams()) }.getOrDefault(false)
        // ★诊断日志（只读）：手动进小窗的结果（notEntered 时下面会恢复控制条）
        LivePageTrace.note(
            "pip.enter.manual",
            "entered" to entered,
            "ratio" to pipAspectRatio(),
            "srcRect" to LivePageTrace.rect(pipSourceRectHint()),
            "pip" to isInPictureInPictureMode,
        )
        if (!entered) {
            // 没进成（系统拒绝、或者当前不在前台）：恢复原状，别把控制条永久压住
            pipEntryPending = false
            showControlsTemporarily()
        }
    }

    /**
     * PiP 参数：宽高比 + （31+）**立即进入**。
     *
     * ★"立即开启"这条链路（用户明确夸过的点）靠两件事，缺一不可：
     * ① `setAutoEnterEnabled(true)`（API 31+）：系统在划走/熄屏时**直接**进 PiP，
     *    而不是先等 App 收到 `onUserLeaveHint` 再请求 —— 这就是"立即"；
     * ② `setSourceRectHint(视频区域)`：让系统知道画面在屏幕上的真实位置，
     *    进出动画从画面本身开始，视觉上没有"整页缩小再变形"的过渡。
     * 比例变化（换清晰度/换线路/换个直播间）时都会重新下发一次，见 [updatePipParams]。
     *
     * ★★第十批：`setSeamlessResizeEnabled(true)` **已停用**（原因见下面 [buildPipParams]
     *   里那段"为什么去掉无缝缩放"的证据链）——它曾经是"立即开启"的第三件配套，
     *   但在本机上正是"点小窗放大回直播间 → 有时黑屏、播放不了"的头号嫌疑。
     *
     * ## ★★第九批：这份参数与**点播**那份逐项对照（对齐结果）
     * ```
     * setAspectRatio      ← 画面自己的宽高比（[pipAspectRatio] = 解码尺寸，与 videoContainer 同源）
     *                       点播：`playerSourceInfo.width/height`（接口元数据，也是"画面尺寸"）
     *                       ⇒ 两边都**没有"竖屏走一条、横屏走一条"的分支**：竖屏视频 → Rational(w,h) < 1
     *                         → 系统给竖屏小窗；横屏视频 → > 1 → 横屏小窗（用户说的"竖屏走竖屏、
     *                         横屏走横屏"就是这个比例的直接结果，不是某个方向开关）
     * setActions          ← 直播：播放/暂停 1 颗；点播：后退/播放暂停/前进 3 颗（直播无时间轴，见 [buildPipActions]）
     * setSeamlessResize   ← ★★第十批更正（**上一轮这张表在这一行是错的**，它写的是"两边都在 S+ 置 true"）：
     *                       点播**在用的**那条链路（`PicInPicHelper.buildParams()`，也是用户说"一直很稳"
     *                       的那条）已经把 `setSeamlessResizeEnabled` **注释掉了**
     *                       —— `PicInPicHelper.kt:190-197`，2026-09-26 用户实测回退，原话级理由：
     *                       "无缝尺寸过渡…点播页在'小窗 → 桌面 → 回 App'这条路上要经历一次全屏回填，
     *                       用户实测**卡在过渡中间**（半屏旧界面半屏新界面 + 全 App 黑屏乱掉）"。
     *                       ⇒ 本页原来那一行 `setSeamlessResizeEnabled(true)` 现在是**全工程唯一**
     *                       还开着无缝缩放的地方，本轮按同一条结论停用（见 [buildPipParams]）。
     *                       注：`VideoPlayerActivity`（独立点播页）也还留着它，
     *                       但那个页面被 `VideoPlayerLauncher.USE_STANDALONE_ACTIVITY = false`
     *                       停用了（用户："不要碰我以前已经做好的点播播放"），所以不在对比范围。
     * setAutoEnterEnabled ← 直播 S+ 置（受"退后台自动进小窗"开关 + [hasVideoPicture] 门控，见下）；
     *                       点播的 `VideoPlayerActivity.buildPipParams(autoEnter=true)` 同样置
     * setSourceRectHint   ← ★第九批对齐：26+ **一律**带（原来只在 31+ 带，见下面那段说明）
     * setTitle            ← 点播在 T+ 取播放器标题控件设小窗标题；本页**不设**（有意差异：直播的
     *                       小窗里不需要再占一行标题，且本页顶栏标题控件与播放器标题控件不是同一个）
     * ```
     */
    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(pipAspectRatio())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // ★直播**自己的**播放/暂停动作（诊断报告 §5 第 2 条）：
            //   在这之前直播 PiP 一个动作都没有，小窗上任何"播放"入口只可能打到点播的会话上
            //   —— 这就是"我在 PIP 点继续播放，结果播的是普通视频"的根因。
            //   加上它之后：小窗里的按钮只操作本页的 [LivePlayerDelegate]。
            builder.setActions(buildPipActions())
        }
        // ★★第九批（**与点播对齐**）：源矩形**必须在每一个 API 26+ 的设备上都带上**，不能只在 31+ 带。
        //   点播的 `PicInPicHelper.buildParams()`（`comm/delegate/helper/PicInPicHelper.kt:201-203`）
        //   是**无条件** `setSourceRectHint(...)`（那个 helper 整体只在 26+ 存在），进入与刷新两条路
        //   都带；直播这里原来把它写在 `SDK_INT >= S` 的块里 ⇒ **Android 8/9/10/11 上的直播小窗一个
        //   源矩形都没有**，进/出小窗的过渡动画只能从整页缩放（"不是原地缩小"、画面缩放起点对不上），
        //   而同版本的点播却有 —— 这就是"同一台机器上普通视频的小窗比直播的小窗正常"的一条参数级差异。
        //   `setSourceRectHint` 本身就是 API 26 的方法，26~30 上设它完全合法。
        // 源矩形必须是屏幕上的真实可见矩形；没布局出来（空矩形）时不能设，
        // 否则系统会以 IllegalArgumentException 拒掉整份参数。
        // ★★第八批：这一处抽成 [pipSourceRectHint]，因为"几何变了要重下发参数"时也要用它算指纹
        //   （见 [syncPipParamsToVideoGeometry]）—— 两处必须**同源**，否则指纹与真正下发的值会漂移。
        pipSourceRectHint()?.let { builder.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ★「设置 → 直播设置 → 退后台自动进小窗」关掉时关掉自动进入（默认开 = 现状）。
            //   注意：底栏那颗「画中画」按钮走的是 [enterPipMode]，是**用户当下明确要小窗**，
            //   不受这个开关管（开关管的是"退后台**自动**进"）。
            //   ★第五批之前这里还挂了一条"听音频模式不自动进小窗"的条件，听音频删掉后不再需要。
            //
            // ★★第九批（**与点播对齐**）：再与上"**画面就绪**"（[hasVideoPicture]）。
            //   点播进小窗有两个前置：`PlayerController.enterPip()` 的 `delegate.isOpened()`
            //   （`comm/delegate/player/PlayerController.kt:825-828`，KDoc 原话：**"播放器没开着就别进：
            //   没有内容的 PiP 窗口是纯黑一块，用户会以为播放器坏了"**）+ 比例来自**起播时就已到手**的
            //   接口元数据（`playerSourceInfo.width/height`，`PlayerDelegate2.kt:1145` 取流成功即写）。
            //   直播没有尺寸元数据（`LiveRoomInitInfo`/`LiveStreamInfo` 里没有 width/height），
            //   比例只能等**首帧解码**的 `onVideoSizeChanged`（`:1895-1898`）——在那之前
            //   [pipAspectRatio] 只能给 16:9 兜底，而 [buildUi]（`:3314`）与 [setupPlayerAndDanmaku]
            //   （`:1739`）**在起播之前就把 `autoEnterEnabled=true` 连同这个 16:9 注册给了系统**。
            //   于是"开了竖屏直播间、画面还没出来就划走"会按 16:9 弹出小窗 —— 用户原话
            //   **"竖屏它走的是横屏"** 唯一还能成立的代码路径就是这一条（比例已知时
            //   [pipAspectRatio] 给的就是画面自己的比例，见那份 KDoc）。
            //   ⇒ 尺寸未知时**不让系统自动进**：宁可这次不弹小窗（回到 App 还有 [autoRetryLiveStream]），
            //     也不弹一个"比例是假的"的黑小窗。尺寸一到 [onVideoSizeChanged] 会立刻重下发本参数
            //     （`updatePipParams()`），自动进入随即恢复 —— 所以受影响的只有"开房间后首帧之前"
            //     这一小段。**手动点「画中画」不受影响**（用户当下明确要小窗，照样立刻进）。
            builder.setAutoEnterEnabled(pipOnBackgroundEnabled() && hasVideoPicture())
            // ★★第十批（本轮修复 ②）：**不启用无缝缩放**（`setSeamlessResizeEnabled`）。
            //
            // 它是"点小窗放大 → 回到直播间有时黑屏、播放不了"的头号嫌疑，三条证据：
            // ① 点播**在用的**那条链路（`PicInPicHelper`，用户原话"其他视频 PIP 小窗它就没那么多屁事"）
            //    已经在 2026-09-26 把它注释掉了，理由是同场景下的同一种故障：
            //    "小窗 → 桌面 → 回 App"→"用户实测**卡在过渡中间**（半屏旧界面半屏新界面 + 全 App
            //    黑屏乱掉）"（`comm/delegate/helper/PicInPicHelper.kt:190-197`，原文可查）。
            //    ⇒ 本行是全工程**唯一**还开着无缝缩放的地方（另一个 `VideoPlayerActivity:557`
            //      已被 `VideoPlayerLauncher.USE_STANDALONE_ACTIVITY = false` 停用）。
            // ② 它把"点放大"从"一次性换尺寸"变成**连续尺寸过渡**（小窗尺寸一路扫到全屏），
            //    中间每一帧都派发一次 [onConfigurationChanged]；而本页那条回调很重：底栏分行/重建、
            //    主题重算、版式重量、`setPictureInPictureParams` 跨进程下发、`requestedOrientation`
            //    断言、`showControlsTemporarily()` —— 在过渡中间做这些正是"卡在过渡里"的成因。
            // ③ 它只是观感优化（顺滑），**不是**任何一条用户点名的功能：比例、动作按钮、
            //    立即进入（`setAutoEnterEnabled`）、源矩形提示、PiP 全幅、几何重下发全都与它无关。
            // ★要恢复：取消下面这一行的注释即可（一行回滚，与 PicInPicHelper 的回退写法一致）。
            // builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /**
     * PiP 的**源矩形提示**：画面（[videoView]）此刻在屏幕上的真实可见矩形；拿不到（还没布局/不可见）
     * 就返回 null（调用方不设这一项 —— 给一个空矩形与不给等价，而系统会因空矩形拒掉整份参数）。
     *
     * ★坐标系口径：`getGlobalVisibleRect` 给的是**屏幕坐标**，与 `setSourceRectHint` 要求的完全一致。
     * ★★第八批为什么要把它抽出来单独一份：PiP 的源矩形 = "**画面**在哪"，
     *   而画面矩形会随版式/方向/键盘 insets/底栏几何变化 —— 必须能在几何变化后**重新算一次并重下发**
     *   （用户实测第 4 条："pip 怎么又是那种不完全显示占满的呢"）。指纹与下发共用这一个函数，
     *   两边永远不会读到不同的值。
     */
    private fun pipSourceRectHint(): Rect? {
        if (!::videoView.isInitialized) return null
        val hint = Rect()
        if (!videoView.getGlobalVisibleRect(hint) || hint.isEmpty) return null
        return hint
    }

    /**
     * 组装直播 PiP 的动作列表：**只有一个槽位** —— 播放/暂停（图标与动作随状态翻转）。
     *
     * ## 为什么只有一个（点播那边是三个）
     * 点播的 PiP 是"后退 10s + 播放/暂停 + 前进 10s"，直播**没有时间轴**：seek 在直播里
     * 要么被忽略、要么直接跳回 live edge，放上去只会误导用户点了"后退"却什么都没发生。
     * 所以直播只留唯一一个真正有意义的动作，把图标做准（播放中显示"暂停"，暂停时显示"播放"）。
     *
     * ## ★★本轮修复：为什么小窗里的播放/暂停"点不动"（证据链）
     *
     * **旧代码**（本次改动前）：
     * ```kotlin
     * val intent = Intent(this, LivePipActionReceiver::class.java).apply { action = ACTION_LIVE_PIP_CONTROL; ... }
     * ```
     * 也就是 `setClass()` 把 Intent 变成了**显式组件**广播，组件指向
     * `com.a10miaomiao.bilimiao.LivePlayerActivity$LivePipActionReceiver`。
     *
     * **框架语义**（AOSP `services/core/java/com/android/server/am/ActivityManagerService.java`
     * `broadcastIntentLocked()`，广播解析只有两处）：
     * ```
     * if ((intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
     *     receivers = collectReceiverComponents(intent, ...);   // ① 清单里声明的 receiver（按 component 解析）
     * }
     * if (intent.getComponent() == null) {                      // ★★ 注意这个前置条件
     *     registeredReceivers = mReceiverResolver.queryIntent(intent, ...);  // ② 动态注册的 receiver
     * }
     * ```
     * 两件事一起看就出结论了：
     * ① **Intent 一旦带了 component，动态注册的接收器根本不在候选集合里**（②整段被跳过），
     *    只有**清单里声明过**的同名组件才会被投递；
     * ② 而 `LivePlayerActivity$LivePipActionReceiver` 是 `inner class`、**只在代码里动态注册**，
     *    AndroidManifest.xml 里根本没有这一项（`<activity .LivePlayerActivity>` 那个块里没有 receiver）。
     * ⇒ 这次广播的接收者数量是 **0**：SystemUI 老老实实把 PendingIntent 发出去了，
     *    AMS 也老老实实按 component 找了一圈清单接收器，没找到 → 静默丢弃 → 按钮毫无反应。
     *    （不会崩、不会报错、不会有 toast —— 与"用户看到的现象"逐字吻合。）
     *
     * **旁证：同一个仓库里那条能用的路是怎么写的** —— 点播的 PiP 动作
     * （`comm/delegate/helper/PicInPicHelper.kt:225-238`）用的是
     * `Intent(action).setPackage(packageName)`，并且留了一句一模一样的结论：
     * ```
     * ★不用 setClass：接收器是**动态注册**的（只在 PiP 期间存在，随 Activity 生死），
     *   没有清单里的类可以指；对动态接收器来说，"定向"能用的手段就是 package + 唯一 action。
     * ```
     * 两条路的唯一差别就是这一处 —— 一边是 `setPackage`（用户实测能用），一边是 `setClass`（点不动）。
     *
     * **顺带排除掉的两个嫌疑**（都不是原因，写在这里免得下一轮重复怀疑）：
     * · `RECEIVER_NOT_EXPORTED` 不会挡住 SystemUI 代发的动作：PendingIntent 是"以**创建者**身份
     *   执行"（官方语义："as if the other application was yourself (with the same permissions and
     *   identity)"），所以 AMS 眼里发送方 UID = 本 App，恰好放行；点播那条通道用的是同一个标志
     *   且已被真机验证。改成 `RECEIVER_EXPORTED` 只会把"任何 App 都能遥控本页"重新打开。
     * · Activity 处于 PiP/paused 收不到广播：不成立。动态接收器挂在 Activity 的 Context 上，
     *   投递只看"注册没注册 + 进程活没活"，与生命周期状态无关；PiP 里的本页只是 paused，
     *   进程稳定存活（详见 [LivePipActionReceiver] 的注释）。
     *
     * ## 三件必须一起做的事（少一件就会和点播抢通道，见 ACTION_LIVE_PIP_CONTROL 注释）
     * ① 定向：`setPackage(自己的包名)` + 直播专用 action（[ACTION_LIVE_PIP_CONTROL]）
     *    —— **不是** `setClass`！理由见上面那段证据链；
     * ② 直播专用 requestCode（[REQUEST_LIVE_PIP_TOGGLE]）—— PendingIntent 身份不撞车；
     * ③ `FLAG_IMMUTABLE`：动作内容由我们写死，不需要任何人改它（Android 12+ 不加会直接抛异常）；
     *    再配 `FLAG_UPDATE_CURRENT`：同一个 token 每次都带上"此刻"的播放状态。
     */
    private fun buildPipActions(): List<RemoteAction> {
        // 调用点都在 API 26+ 的判断之后（buildPipParams 内），这里再兜一次是因为
        // RemoteAction / Icon.createWithResource 的类本身就要 26+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        val playing = delegate?.isPlaying == true
        val control = if (playing) LIVE_PIP_CONTROL_PAUSE else LIVE_PIP_CONTROL_PLAY
        val iconRes = if (playing) {
            R.drawable.bili_player_play_can_pause // 正在播 → 按钮是"暂停"
        } else {
            R.drawable.bili_player_play_can_play  // 已暂停 → 按钮是"播放"
        }
        // ★★本轮的核心修复：**不能用 `Intent(this, LivePipActionReceiver::class.java)`**。
        //   带 component 的广播只投给"清单里声明过的组件"，而本接收器是动态注册的
        //   （没有清单项）→ AMS 解析出来 0 个接收者 → 点按钮什么都不发生。
        //   动态接收器唯一可用的"定向"手段就是 包名 + 唯一 action（与点播那条能用的路逐字一致）。
        val intent = Intent(ACTION_LIVE_PIP_CONTROL)
            .setPackage(packageName)
            .putExtra(EXTRA_LIVE_PIP_CONTROL, control)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_LIVE_PIP_TOGGLE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return listOf(
            RemoteAction(
                Icon.createWithResource(this, iconRes),
                if (playing) "暂停" else "播放",
                "直播画中画播放控制",
                pendingIntent,
            ),
        )
    }

    /**
     * 把最新参数交给系统（API 26+）；失败只吞掉，绝不能因为 PiP 参数让播放崩掉。
     *
     * ★顺带记下这次下发的"几何指纹"（[pipGeometryKey]），给 [syncPipParamsToVideoGeometry] 比对 ——
     *   两条路共用同一份指纹，谁下发谁记账。
     */
    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // ★★第十批（本轮修复 ④）：**退出小窗的过渡动画期间不下发**，排到动画之后再补一次
        //   （延迟、不取消 —— 见 [deferPipParamsDuringExitTransition] 的 KDoc）。
        if (deferPipParamsDuringExitTransition()) {
            // ★诊断日志（只读）：本次下发被"退出小窗过渡期"推迟
            LivePageTrace.note(
                "pip.params.defer",
                "sinceExitMs" to (SystemClock.elapsedRealtime() - pipExitedAtMs),
                "settleMs" to PIP_EXIT_SETTLE_MS,
            )
            return
        }
        // ★★本轮：**指纹相同就不下发**（用户实测：6 分钟 202 次 `setPictureInPictureParams`，
        //   其中还夹着连续 3 次一模一样的 `key=16/9|null`）—— 每次都是一次跨进程 IPC，
        //   而这条路上有一半的调用点（onConfigurationChanged / onVideoSizeChanged /
        //   退出小窗补发 / PiP 动作）根本不会改变参数。
        //   指纹覆盖**整份参数**（几何 + 动作 + 自动进入），见 [pipParamsKey] 的说明。
        val paramsKey = pipParamsKey()
        if (paramsKey == lastSentPipParamsKey) {
            // ★诊断日志（只读）：本次**跳过**下发（"参数没变却反复下发"那一条的观测点）
            LivePageTrace.note(
                "pip.params.skip",
                "key" to paramsKey,
                "pip" to isInPictureInPictureMode,
                "video" to "${videoWidth}x$videoHeight",
            )
            return
        }
        // ★几何指纹沿用既有行为（下发前记账，一个字都不改）；**新增的整份指纹只在真的发成功时才记**：
        //   失败（极少）时下一次调用会重试，而不是被自己的记账卡成"永远不下发"。
        lastSentPipGeometryKey = pipGeometryKey()
        val sent = runCatching { setPictureInPictureParams(buildPipParams()) }.isSuccess
        if (sent) lastSentPipParamsKey = paramsKey
        // ★诊断日志（只读）：真正下发的比例 / 源矩形 / 指纹
        LivePageTrace.note(
            "pip.params.send",
            "ratio" to pipAspectRatio(),
            "srcRect" to LivePageTrace.rect(pipSourceRectHint()),
            "key" to lastSentPipGeometryKey,
            "ok" to sent,
            "pip" to isInPictureInPictureMode,
            "video" to "${videoWidth}x$videoHeight",
        )
    }

    /**
     * ★★第十批（本轮修复 ④）：把"退出小窗后 [PIP_EXIT_SETTLE_MS] 之内的 PiP 参数下发"
     * **推迟到过渡动画结束之后**，而不是插在动画中间。
     *
     * ## 为什么（用户："点小窗放大 → 回直播间有时黑屏、播放不了"）
     * `setPictureInPictureParams` 不是"改个内存变量"：它是一次跨进程调用，SystemUI / WMShell
     * 收到后会**按当前 PiP 状态重算小窗几何与窗口容器**。而"点放大"正在做小窗→全屏的窗口过渡，
     * 此时再让系统重算小窗几何，就等于在动画中途改它正在动画的那个东西 —— 表现可以是从
     * "过渡卡住/半屏旧界面"一直到"窗口回来了但画面是黑的"。
     * · 点播那条稳的链路**不会**在几何变化时下发（`PicInPicHelper:331` 只在进小窗与点动作按钮时下发；
     *   那份对齐报告也写了"点播没有'几何驱动的重下发'"）；
     * · 而本页退出小窗时几何**必然**变（画面矩形从小窗矩形 → 全屏矩形），指纹必变 ⇒ 原来必然下发。
     *
     * ## 为什么"延迟"而不是"取消"（保护已知修复）
     * "几何一变就重下发参数"（[syncPipParamsToVideoGeometry]）是本页修"PiP 不完全铺满"的一条已知修复，
     * 它的价值在于"系统手上那份源矩形/比例永远是最新的"，**与"哪一毫秒发出去"无关**：
     * 过渡动画期间系统根本不会用这份参数去开小窗（我们正在离开小窗），动画结束后补发同样新鲜。
     * 所以这里只挪时间点：同一时刻最多排一个补发任务（先撤后post），[onDestroy] 里也会撤掉。
     *
     * ## 为什么不只在 [syncPipParamsToVideoGeometry] 里挡
     * 挡在 [updatePipParams] 这一层，`onConfigurationChanged`（过渡中必来）与
     * `onVideoSizeChanged` 这两条路也一并避开动画 —— 它们原来也都是"无条件立刻下发"。
     *
     * @return true = 本次下发已被推迟（调用方直接 return，什么都不发）
     */
    private fun deferPipParamsDuringExitTransition(): Boolean {
        val exitedAt = pipExitedAtMs
        if (exitedAt <= 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - exitedAt
        if (elapsed >= PIP_EXIT_SETTLE_MS) {
            // 过渡窗口过了：时间戳自动失效（不靠布尔标志，不会"卡住变永不下去"）
            pipExitedAtMs = 0L
            return false
        }
        mainHandler.removeCallbacks(pipParamsResendRunnable)
        mainHandler.postDelayed(pipParamsResendRunnable, PIP_EXIT_SETTLE_MS - elapsed + 50L)
        return true
    }

    /**
     * ★★第八批（用户实测第 4 条）：**几何一变就重新下发 PiP 参数**，不要只在进小窗时算一次。
     *
     * ## 为什么必须有它（用户原话："为什么我 pip 怎么又是那种不完全显示占满 pip 的呢？
     * 刚才不是修好了，现在怎么又回来了？"）
     * PiP 参数里有两样东西是**几何的函数**：
     * ```
     * setAspectRatio     ← 视频解码尺寸（换清晰度 / 换线路 / 换直播间）
     * setSourceRectHint  ← 画面此刻在屏幕上的可见矩形（转屏 / 竖屏视频带 / 底栏与 insets 变化 / 进出小窗）
     * ```
     * 而重下发的路径**只有两条**：`onVideoSizeChanged`（比例）与 [onConfigurationChanged]（配置）。
     * **"版式几何变了"那条路没有** —— 本页的版式由 [applyVideoStageLayout] / [measurePortraitStage]
     * 算出来（顶栏高度、底栏行数、键盘 insets、竖屏带子的 62% 封顶都会改它），
     * 这些变化**不经过上面两条回调**。于是系统手上那份 `setSourceRectHint` 会一直停在旧值：
     * 进/出小窗的过渡动画会从"旧画面位置"开始，而系统按旧矩形算出来的缩放/裁切与此刻真实的
     * 画面矩形对不上 —— 表现出来就是"小窗里画面不占满、有黑边"。
     * ★**注意分工**（免得后人只改一处）：小窗"不占满"的**主因**是版式（PiP 里页面仍按竖屏带子排，
     *   见 [applyVideoStageLayout] 的第八批那段证据链）；这一条修的是**参数侧**：
     *   比例与源矩形必须跟着几何走。两件一起做，小窗才是"画面正好铺满"。
     *
     * ## 挂在哪
     * [measurePortraitStage]（本页**唯一**的"画面矩形收敛点"：进/出小窗、转屏、底栏换行、
     * 键盘 insets、换清晰度改比例，最终都会走到它）末尾 —— 挂在它那里就等于覆盖了全部几何变化。
     *
     * ## 为什么带指纹（而不是每次布局都无脑 [updatePipParams]）
     * `setPictureInPictureParams` 是一次跨进程调用，而 [measurePortraitStage] 在底栏换行、键盘
     * insets 变化、弹幕列表量算等时机都会被调到 —— 无脑下发既浪费又可能让系统反复重算小窗几何。
     * 指纹 = 比例 + 源矩形；**与真正下发的两个值同源**（[pipAspectRatio] / [pipSourceRectHint]），
     * 所以"指纹没变"就等于"系统手上的那两份值已经是对的"，跳过是安全的。
     */
    private fun syncPipParamsToVideoGeometry() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (pipGeometryKey() == lastSentPipGeometryKey) return
        // ★诊断日志（只读）：几何指纹变了 → 重下发（"PiP 不铺满"那一条的观测点）
        LivePageTrace.note(
            "pip.params.geomChanged",
            "from" to (lastSentPipGeometryKey ?: "-"),
            "to" to pipGeometryKey(),
            "pip" to isInPictureInPictureMode,
        )
        updatePipParams()
    }

    /**
     * PiP 参数的**几何指纹**：宽高比 + 源矩形。
     *
     * ★两个分量都必须是"真正会下发的那两个值"（[buildPipParams] 用的就是它们），
     *   否则指纹会说"没变"而系统手上的却是旧的 —— 那正是这一条第 4 项要修的病。
     */
    private fun pipGeometryKey(): String =
        pipAspectRatio().let { "${it.numerator}/${it.denominator}" } + "|" + pipSourceRectHint()

    /**
     * PiP 参数的**整份指纹**（★本轮"指纹相同就不下发"的判据，见 [updatePipParams]）。
     *
     * ```
     * = pipGeometryKey()（比例 + 源矩形）
     * + 播放状态（setActions 的图标/动作由它决定 —— delegate.isPlaying）
     * + 自动进入（setAutoEnterEnabled 的取值，只在 S+ 真正下发）
     * ```
     * ★三个分量都**与 buildPipParams 里真正读到的来源同源**：
     *   "指纹没变" ⟺ "这次 build 出来的参数与系统手上那份逐项相同" ⟺ 跳过是安全的。
     * ★为什么不能用"PiP 是否在屏"之类的状态当分量：它们不参与 build，只会让去重失效。
     */
    private fun pipParamsKey(): String = buildString {
        append(pipGeometryKey())
        append("|play=").append(delegate?.isPlaying == true)
        append("|auto=").append(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pipOnBackgroundEnabled() && hasVideoPicture()
            } else {
                false
            },
        )
    }

    /**
     * PiP 宽高比：优先用真实视频比例，并夹到系统允许的 [1/2.39, 2.39] 区间内。
     *
     * ★★第八批：为什么**不**改用"视频带矩形（[portraitVideoRect]）的比例"（把这条写清楚，
     *   免得后人按"小窗不铺满"的现象去改这一行）：
     * · PiP 窗口的宽高比要等于**画面**的宽高比，小窗里才会一点黑边都没有 ——
     *   而画面是 [AspectRatioFrameLayout] 按 [videoWidth]/[videoHeight] 摆出来的
     *   （`videoContainer.videoAspectRatio` 与这里同源，都由 `onVideoSizeChanged` 写），
     *   所以"真实解码尺寸"就是画面比例，正是系统要的那个数；
     * · **视频带矩形 ≠ 画面矩形**：竖屏带子封顶 [PORTRAIT_VIDEO_MAX_HEIGHT_FRACTION] 页高，
     *   9:16 的竖屏主播在 1080 宽的页面上是"1080×1488 的带子 + 837×1488 的画面"——
     *   拿带子比例（0.73）当 PiP 比例，小窗会变成横的，画面反而左右两条大黑边。
     * ⇒ 黑边的真凶不是这个比例，而是"**PiP 里页面仍按竖屏带子版式排版**"（见 [applyVideoStageLayout]）：
     *   小窗的窗口比例是对的，但画面在小窗里被那条 62% 的带子挤成了中间一小块。
     */
    /**
     * "**画面尺寸已经到手**" —— PiP 那条链路的就绪判据（**点播 `delegate.isOpened()` 的同义物**）。
     *
     * ```kotlin
     * videoWidth/videoHeight 只由 onVideoSizeChanged 写（`:1895-1898`，与 videoContainer.videoAspectRatio 同源）
     * ⇒ 它俩 > 0  ⟺  画面比例已知  ⟺  [pipAspectRatio] 给出的不再是 16:9 兜底值
     * ```
     * 为什么需要它：直播的比例**只能**来自解码尺寸，而解码尺寸要等首帧；点播的比例来自取流接口的元数据
     * （`PlayerSourceInfo.width/height`，`PlayerDelegate2.kt:1145` 取流成功即写）——**起播前就有**。
     * 这个"点播没有、直播有"的空窗期就是"竖屏房间弹出横屏小窗"的唯一入口，门控见 [buildPipParams]
     * 里 `setAutoEnterEnabled(... && hasVideoPicture())` 那段。
     *
     * ★判据与 [AspectRatioFrameLayout] 完全同源（它也是 `videoAspectRatio <= 0f` 就认为"还不知道比例"），
     *   所以不会出现"版式以为知道、PiP 以为不知道"两套说法。
     */
    private fun hasVideoPicture(): Boolean = videoWidth > 0 && videoHeight > 0

    private fun pipAspectRatio(): Rational {
        val w = videoWidth
        val h = videoHeight
        if (!hasVideoPicture()) return Rational(16, 9)
        val ratio = w.toDouble() / h.toDouble()
        return when {
            ratio > 2.39 -> Rational(239, 100)
            ratio < 1.0 / 2.39 -> Rational(100, 239)
            else -> Rational(w, h)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI 搭建
    // ══════════════════════════════════════════════════════════════════════

    private fun buildUi() {
        rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // ① 视频：TextureView 套一层"按视频比例 measure"的容器，避免画面被拉伸变形
        //
        // ★为什么用 TextureView 而不是 SurfaceView（"看不见弹幕"的头号排查项，结论见交付报告）：
        //   SurfaceView 的画面**不画在窗口里**，它在自己独立的图层上（相对窗口的负 Z 序，
        //   靠"窗口打洞 + 洞后面那个图层"合成），所以"叠在视频上的 View 到底在不在上面"
        //   取决于各 ROM 对打洞/图层合成的实现（实测机是 Honor / MagicOS，日志里出现过）。
        //   TextureView 就是把画面当**普通 View** 画进窗口缓冲 → "视频 → 弹幕 → 手势 → 控制条"
        //   的叠放顺序**由 View 树本身保证**，不依赖任何窗口合成语义。
        //   旁证：本工程其它视频渲染全都是 TextureView —— 点播是 GSY 的 GSYVideoType.TEXTURE
        //   （widget/player/DanmakuVideoPlayer.kt:657 原话"我们是 TextureView 渲染"），
        //   点播弹幕是 master.flame.danmaku.ui.widget.DanmakuView（也是 TextureView 实现）；
        //   直播页原先那处 SurfaceView 是**全工程唯一一处**，也正好是唯一"叠上去的 View 看不见"的地方。
        //   代价：TextureView 多一次 GPU 拷贝、不支持 DRM/HDR（直播 FLV/HLS 两者都没有）。
        //   要回退：把这里换回 SurfaceView + 调 delegate.attachSurfaceView()（已一并改好）。
        videoContainer = AspectRatioFrameLayout(this)
        videoView = TextureView(this)
        videoContainer.addView(
            videoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        rootLayout.addView(
            videoContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // ② 弹幕层：**先占好位置**的空容器，真正的宿主 View 要等 room_init 拿到真实房间号才能建
        //    （见 setupPlayerAndDanmaku）。用一个固定容器而不是"到那时再 insert 到 index=1"，
        //    是为了让图层顺序完全由本函数决定，不受宿主创建时机影响。
        danmakuLayer = FrameLayout(this)
        rootLayout.addView(
            danmakuLayer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // ③ 手势层（透明，压在视频/弹幕之上、控制条之下）
        tapCatcher = TapCatcher(this)
        rootLayout.addView(
            tapCatcher,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // ③′ 手势气泡层（音量/亮度提示）——**页内一层 View**，不是悬浮窗（为什么见 [hudLayer]）。
        //     两份 GSY 布局由 [GestureHud] 懒加载后 addView 进来（和点播一样：第一次滑动才建）。
        //     ★它必须压在 [tapCatcher] 之上（看得见）却**不能吃触摸**（手势要继续落到手势层），
        //       这两件事同时成立的依据见 [hudLayer] 与 [GestureHud.addBubble]。
        hudLayer = FrameLayout(this).apply {
            isClickable = false
            isFocusable = false
        }
        rootLayout.addView(
            hudLayer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // ③″ 竖屏"弹幕列表槽"（INVISIBLE 的占位 View）：位置 = 播放页留给列表的那块矩形，
        //     由 [measurePortraitStage] 每次量完写进它的 LayoutParams。
        //     它自己不画、不吃触摸，作用只有一个：把矩形**以 View 的形式**交给弹幕宿主
        //     （宿主 `bindPortraitListArea(slot = …)` 按它的实际位置摆面板，随 layout 自动跟进）。
        //     起始尺寸给 0：竖屏量出来之前，"预留区"就该是零。
        danmakuListSlot = View(this).apply {
            visibility = View.INVISIBLE
            isClickable = false
            isFocusable = false
        }
        rootLayout.addView(
            danmakuListSlot,
            FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START),
        )

        // ④ 顶栏
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 蒙层色走 [scrimColor]（深浅色主题给不同透明度）；强调色部分在 [applyThemeColors] 里刷
            setBackgroundColor(scrimColor())
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
        }
        // ★返回按钮本轮改成**只有图标**（用户："去掉那个顶栏的返回，只保留一个图标，就是复用我们视频
        //   播放器的那个返回图标，也就是我们其他页底栏的那个返回图标。"）：
        //   · 图标 = `R.drawable.ic_arrow_back_white_24dp` —— 点播播放器**全屏时**那颗返回用的就是它
        //     （`widget/player/DanmakuVideoPlayer.kt:1094` 的 `mBackButton.setImageResource(...)`；
        //     布局里 `@id/back` 的默认图是 `ic_close_white_24dp`，进 FULL 模式才换成这一支）；
        //     「其他页」AppBar 的返回图标 `ic_back_24dp`（`widget/scaffold/AppBarView.kt:188`）与它
        //     是**同一条 Material arrow_back 路径**（两个 vector 的 `pathData` 逐字相同：
        //     `M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z`）。
        //   · 为什么取"白色那一支"而不是 `ic_back_24dp`：后者带
        //     `android:tint="?attr/colorControlNormal"`，**浅色主题下会被染成深色**，而顶栏底是压在
        //     画面上的**黑蒙层**（[scrimColor]，深浅色主题都是黑）—— 深色图标压上去几乎看不见；
        //     `ic_arrow_back_white_24dp` 没有 tint、`fillColor` 写死 #FFF，**深浅色主题下都清晰**，
        //     也与点播播放器里那颗图标逐像素一致。
        //   · 尺寸/反馈：点击区 [BACK_ICON_BOX_DP]dp（与点播播放器 40dp 的返回一致），
        //     图标 24dp 居中；点击反馈用「其他页」AppBar 那颗返回同款的 borderless ripple
        //     （`AppBarVerticalUi.kt:218` / `AppBarHorizontalUi.kt:35` 用的就是它）。
        //   · 点击语义 = 系统返回键（[handleBack]）：横屏先退全屏、竖屏才退直播间。
        backButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back_white_24dp)
            contentDescription = "返回"
            scaleType = ImageView.ScaleType.CENTER
            val pad = dpToPx(BACK_ICON_PADDING_DP)
            setPadding(pad, pad, pad, pad)
            selectableItemBackgroundBorderlessRes().takeIf { it != 0 }
                ?.let { setBackgroundResource(it) }
            setOnClickListener { handleBack() }
        }
        titleText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            text = "直播间"
        }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            // ★本轮起它**只在异常/过渡时可见**（正常播放时 `GONE`，顶栏只剩返回 + 标题，
            //   见 [renderStatus]）；异常文案仍然可能很长（播放失败原因 / 追流提示），
            //   所以照旧"宁可截断也不要换行"——顶栏高度必须稳定（它决定竖屏视频带的顶边）。
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            text = "准备中…"
        }
        // ★顶栏现在只有三样东西：返回图标（固定 [BACK_ICON_BOX_DP]dp）、标题（`weight=1`）、
        //   状态文字（正常态 **GONE**，见 [renderStatus]）。
        //   LinearLayout 第一趟只量"没有 weight 的孩子"，`weight=1` 的标题最后拿剩下的 ——
        //   所以真正会被挤的始终是标题（它本来就带省略号），返回图标一定拿得到自己的位置。
        // ★第七批那颗「顶栏画中画图标」（`pipIconButton`）**已整体删除**：用户 2026-09-26 改主意
        //   （"没必要移到顶栏"）→ 画中画回到 [orderedBottomButtons] 留在底栏；本轮把**从未挂载**的
        //   图标创建代码与那段被注释掉的 `topBar.addView(…)` 一起删掉，不留死代码。
        // ★返回按钮的点击区是**正方形**（图标居中，见上面那段）：用显式 LayoutParams 交给 LinearLayout，
        //   否则它会按 `wrap_content` 量成"图标 + 内边距"（那一量出来比 40dp 小一圈，
        //   手指能点到的范围也跟着缩）。
        topBar.addView(
            backButton,
            LinearLayout.LayoutParams(dpToPx(BACK_ICON_BOX_DP), dpToPx(BACK_ICON_BOX_DP)),
        )
        topBar.addView(
            titleText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        topBar.addView(statusText)
        rootLayout.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        // 沉浸式下状态栏是隐藏的；用户临时划出来时别让内容被状态栏压住
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dpToPx(8), dpToPx(6) + bars.top, dpToPx(8), dpToPx(6))
            insets
        }

        // ⑤ 底栏 = **输入条 + 五颗按钮（同一行）**（★第七批重构 + ★第八批统一版式 + ★第十四批第 5 颗）
        //
        // 用户原话："发弹幕变常驻输入条……记得用安卓的 API 把它那个改成发送，我们就省了一个发送的按钮了，
        // 也省出那个弹窗按钮"；"那个旋转一定要放到底栏来啊，它是经常用到的"；
        // "「画质」和「线路」合并成一颗"；"不要「更多」按钮"；
        // ★第十四批："我们在直播间底栏，画质的后面、PIP 的中间添加一个按钮，就是设置。"
        // ★第八批（用户实测第 1/3 条）：**竖屏也做成一行** ——
        //   "还有那个输入框，你干嘛要单独一行呢？把它和那个几个按钮放一起呢？"
        //   "那几颗按钮奇奇怪怪"（大小不一、间距怪异）。所以竖屏与横屏现在是**同一套**：
        // ```
        // bottomBar(VERTICAL) = [ row(HORIZONTAL: danmakuInputRow(weight=1) + bottomButtons), danmakuInputError ]
        //                          ↑ 输入框吃剩下的宽度            ↑ 5 颗**等宽**格（[applyBottomBarTextSizes]）
        // ```
        // 结构由 [rebuildBottomBar] 装配（两个可搬运的容器：输入条那一行、按钮那一行）。
        //
        // ★第八批（用户实测第 2 条）：输入条**不再常驻** —— 与那几颗按钮一起走
        //   `setControlsVisible` / [showControlsTemporarily] / [CONTROLS_AUTO_HIDE_MS] 那一套显隐
        //   （"为什么不和那几个按钮一起显示一两秒呢？为什么要一直站在那？"），只有 PiP 里 `GONE`。
        //   隐藏用 `INVISIBLE`（与按钮同款）：保住占位 → 底栏高度不变 → 弹幕列表区不跳。
        // ★5 颗按钮是浮层动作：4 秒后 `INVISIBLE`（不是 `GONE`）—— 同上，都是为了"底栏顶边不变"。
        // ★按钮文案一律**尽量短**（用户："按钮文字尽量短"）：5 颗同一行 + 还要给输入框留位，
        //   长文案只会把整行字号拖小（[applyBottomBarTextSizes] 按"最宽的文案"统一挑档）。
        //   ★第十四批那颗「设置」是 2 个字，**没有改变整行字号档位**（最宽的仍是 3 字的
        //     「弹幕开」/「画中画」；真正的变量是"颗数 4 → 5"，账见 [DANMAKU_INPUT_MIN_WIDTH_DP]）。
        qualityButton = actionButton("画质").apply {
            setOnClickListener { showStreamDialog() }
        }
        danmakuButton = actionButton("弹幕").apply { setOnClickListener { toggleDanmaku() } }
        rotateButton = actionButton("旋转").apply { setOnClickListener { toggleOrientation() } }
        // ★2026-09-26 用户改主意：**画中画留在底栏**（"为什么要把画中画移到顶栏去？简直就是没有必要"）。
        //   底栏当时 4 颗（★第十四批起 5 颗），竖屏/横屏都放得下，不需要搬到顶栏去。
        // 2 字：保住 14sp（见 updateDanmakuButton 的说明）
        pipButton = actionButton("小窗").apply { setOnClickListener { enterPipMode() } }
        // ★★第十四批：底栏「设置」**回来了**（用户："我们在直播间底栏，画质的后面、PIP 的中间添加一个
        //   按钮，就是设置。就是会弹出直播间的设置选项、设置页。**记得用我的那个自定义的全屏弹窗**，
        //   不管你怎么转屏，它都会自己适配。就是我的那个底栏筛选的那个弹窗。"）。
        //   · 位置：`orderedBottomButtons()` 里排在 [qualityButton] 与 [pipButton] **中间**；
        //   · 文案「设置」两个字 —— 与其它四颗同量级，不会把整行字号多拖低一档
        //     （[applyBottomBarTextSizes] 按"一行里最宽的文案"统一挑档，最宽的仍是 3 字的
        //      「弹幕开」/「画中画」，本颗不改变档位；5 颗的整行宽度账见 [DANMAKU_INPUT_MIN_WIDTH_DP] 那段的交付报告）；
        //   · 弹窗是 compose 模块的 `LiveSettingSheetHost`（== 首页「底栏筛选弹窗」同一套外壳），
        //     用的是**同一批 `live_*` 键**，与「设置 → 直播设置」页共用同一份项（见 [showLiveSettingSheet]）。
        settingButton = actionButton("设置").apply { setOnClickListener { showLiveSettingSheet() } }
        bottomButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        // 五颗按钮**只在这里 addView 一次**（[rebuildBottomBar] 之后只搬运 [bottomButtons] 这个容器，
        // 不重建按钮本身）——这样字号档位（记在 `View.tag` 上）与背景不会被反复刷。
        // ★第八批：每颗都是 `width = 0 + weight = 1` 的**等分格** —— 容器宽度由
        //   [applyBottomBarTextSizes] 按"最宽的那条文案 × 颗数"写成固定值，于是 5 颗**同宽**
        //   （用户第 3 条："同高、同宽（等分）、同内边距"）；字号也是**一行共用一档**（同高）。
        //   左右外边距 [BOTTOM_BUTTON_MARGIN_DP] 留在每一颗自己身上，几种宽度算法共用同一份账。
        //   ★第十四批加了第 5 颗，**排版代码一个字节都没改**：颗数进 [applyBottomBarTextSizes] 的
        //     预算公式（`/ count`）与 `weight=1` 的等分，这里只多了一行创建。
        orderedBottomButtons().forEach { button ->
            val lp = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
            val margin = dpToPx(BOTTOM_BUTTON_MARGIN_DP)
            lp.marginStart = margin
            lp.marginEnd = margin
            applyBottomButtonStyle(button)
            bottomButtons.addView(button, lp)
        }

        // ⑤′ 弹幕输入条（用户："发个弹幕…"）+ 发送中的转圈 + 失败提示行
        //
        // ★`imeOptions = IME_ACTION_SEND`：软键盘回车键 = 「发送」→ [submitDanmakuInput]，
        //   所以既没有发送按钮、也没有弹窗（用户："我们就省了一个发送的按钮了，也省出那个弹窗按钮"）。
        // ★`inputType = TYPE_CLASS_TEXT`（**不带** MULTI_LINE）+ `maxLines = 1`：这是"回车键变成
        //   动作键而不是换行"的标准组合；`maxLines` 同时防住"输入框被撑成三行把底栏顶高"。
        // ★焦点变化：拿到焦点就 `holdControls()`（正在打字时控制条不许自动隐藏），
        //   并补一次 `showSoftInput`（部分 ROM 上手点输入框不弹键盘，见老弹窗时代同一个坑的注释）。
        danmakuInput = EditText(this).apply {
            hint = DANMAKU_INPUT_HINT
            setHintTextColor(ColorUtils.setAlphaComponent(Color.WHITE, 140))
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dpToPx(12), dpToPx(9), dpToPx(12), dpToPx(9))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(18).toFloat()
                setColor(ColorUtils.setAlphaComponent(accentColor(), 78))
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitDanmakuInput()
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) return@setOnFocusChangeListener
                holdControls()
                post {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        // 转圈：18dp、主题色（`GONE` 常态，发送中才 `VISIBLE`）
        danmakuSendProgress = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accentColor())
        }
        // 输入条那一整行（HORIZONTAL，可搬运：[rebuildBottomBar] 把它摆进那一行版式的左边，weight = 1）
        danmakuInputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        danmakuInputRow.addView(
            danmakuInput,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        danmakuInputRow.addView(
            danmakuSendProgress,
            LinearLayout.LayoutParams(dpToPx(18), dpToPx(18)).apply { marginStart = dpToPx(6) },
        )
        // 失败提示行：紧贴输入条下方（`GONE` 常态，见 [showDanmakuInputError]）
        danmakuInputError = TextView(this).apply {
            visibility = View.GONE
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(errorTextColor())
            setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
        }

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(scrimColor())
            // ★内边距走常量：格宽 = (底栏宽 - 这里的内边距 - 每格左右外边距) / 每行格数，
            //   [applyBottomBarTextSizes] 要用同一批数字算字号档位（见那三个常量）
            val pad = dpToPx(BOTTOM_BAR_PADDING_DP)
            setPadding(pad, pad, pad, pad)
        }
        rootLayout.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        // 沉浸式下导航栏是隐藏的；用户临时划出来（或三键导航机型）时别让底栏被压住 ——
        // 这也是"底栏按钮点不着"的一类成因（第 7 项：弹窗/控件不被系统栏遮挡）。
        // ★★第七批：这里**只记导航栏高度**，真正的 `setPadding` 交给 [refreshBottomBarInsets] ——
        //   因为底栏的内边距现在有**两个来源**（导航栏 + 输入法），两处各写各的必然互相覆盖：
        //   ```kotlin
        //   底部内边距 = 常态内边距 + max(导航栏, 键盘让位后仍被盖住的那部分)
        //   ```
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { _, insets ->
            systemBarBottomInsetPx = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            refreshBottomBarInsets()
            insets
        }

        // ⑥ 缓冲指示
        progressBar = ProgressBar(this).apply { visibility = View.GONE }
        rootLayout.addView(
            progressBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        // ⑧ ★把图层顺序**钉死**（这一段就是"弹幕看不见"的结构性修复）：
        //    视频 → 弹幕 → 手势 → 气泡 → 顶栏 → 底栏 → 缓冲圈。
        //    用 bringToFront() 逐个提到最前，顺序只由这一行决定 —— 不再依赖
        //    "谁在什么时候 addView"，也就不会再出现"弹幕宿主是后来才建出来的，
        //    结果掉到某一层下面"这种事。
        //    ★气泡层夹在 [tapCatcher] 与顶栏之间：看得见（压着视频/弹幕）、
        //      挡不住手势（不吃触摸）、也不会盖住控制条上的按钮。
        //    ★第五批删掉了原来夹在手势层与顶栏之间的"音频舞台"（[audioScroll]）。
        listOf<View>(danmakuLayer, tapCatcher, hudLayer, topBar, bottomBar, progressBar)
            .forEach { it.bringToFront() }

        setContentView(rootLayout)

        // ⑨ 根布局的 insets 监听：记下**输入法当前占用的高度**并抬底栏。
        //
        // ★★第七批在这里改了语义（原来是"只记一个值，给弹窗封顶用"）：
        //   · `imeInsetPx` 仍然记（[dialogContentMaxHeightPx] 的 `leaveRoomForIme` 还要用它）；
        //   · 新增 [refreshBottomBarInsets]：**把键盘高度加到底栏的底部内边距上**。
        //     为什么不能只靠 `ADJUST_RESIZE` 让窗口自己变矮（老注释就是这么假设的）：
        //     本页 `targetSdk = 36`，而 Android 15 起"目标 35+ 的 App 被强制 edge-to-edge"，
        //     `adjustResize` 在那种系统上**不再缩窗口** —— 底栏不会被顶上去，输入条就会被键盘盖住。
        //     完整取舍（"窗口缩了/没缩"两种世界只抬一次）见 [refreshBottomBarInsets] 的 KDoc。
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            imeInsetPx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            refreshBottomBarInsets()
            insets
        }

        // ★第四批：装上"按真实尺寸重排"的三只监听器（转屏 / 分屏 / 进出 PiP / 折叠屏展开 /
        //   状态栏临时划出都会走到它们）——必须在**内容和监听器都就绪之后**再装，
        //   而且要在下面的 rebuildBottomBar()/applyVideoStageLayout() **之前**：
        //   这样"第一帧布局量出真实尺寸"那一刻就已经有人在盯着了。
        installPageLayoutWatchers()

        // ★底栏内容按"当前形态 + 可见按钮"填进去（显隐/转屏时都要重建）
        rebuildBottomBar()
        // ★把主题色刷到控件上（按钮底色/状态文字/进度条/封面占位）
        applyThemeColors()
        // ★竖屏版式（视频带落在顶栏之下 + 给弹幕列表留出下面那块）——必须在底栏排好之后调：
        //   列表区的底边就是"底栏顶"，底栏没分行完就量不到那个位置。
        applyVideoStageLayout()
        // 初始状态：还没起播 → "播放"（真正的状态随后由 delegate 的 PLAYING/PAUSED 回调刷新）
        updatePlayPauseButton()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 一进来就把 PiP 参数注册好（比例先用 16:9，出画面后会按真实比例更新）：
            // API 31+ 的"立即进 PiP"依赖它已经被设置过
            updatePipParams()
        }
        showControlsTemporarily()
    }

    /**
     * 装上三只"布局监听"——**第四批第 1/3 条的地基**（也是本页唯一一处"按真实尺寸重排"的入口）。
     *
     * ## 为什么必须监听布局，而不是只信回调
     * 本页声明了 `configChanges=orientation|screenSize|...`，转屏/PiP/分屏都**不重建** Activity，
     * 于是"版式几何"只可能在回调里重算。而回调有两个先天的坑：
     * ① `Configuration.orientation` 是**窗口**方向，不是设备方向（PiP 小窗、分屏、自由窗口都会不一致，
     *    项目里点播早就写了这条：`PlayerController.updatePlayerMode()` 的"画中画：窗口方向 ≠ 设备方向"）；
     * ② 回调可能**根本不来**（退出 PiP、某些 ROM 的自动进入、窗口被系统改尺寸）。
     * 这两个坑合起来就是用户报的"PIP 返回后底栏文字变小"：底栏被小窗方向的配置重排成横屏那一套，
     * 回到全屏却没人再排一次。**真实布局尺寸**是唯一同时躲开这两个坑的信号（点播的
     * `onHostSizeChanged` / "尺寸是方向的最终真源"、宿主的 `portrait = h > w` 都是同一条结论）。
     *
     * ## 三只监听器各管什么（都只在**输入真的变了**时才动作，保证不会自激）
     * | 监听对象 | 触发条件 | 动作 |
     * |---|---|---|
     * | [rootLayout] | 页面真实宽高变了（转屏/分屏/PiP/折叠屏） | [syncPageLayoutToRealSize]：底栏分行 + 视频带/列表槽版式 |
     * | [topBar] | 顶栏底边变了（状态栏划出/标题换行/insets 变化） | [applyVideoStageLayout]：视频带顶边跟着顶栏走（★第 3 条） |
     * | [videoContainer] | 视频带矩形变了（比例到达/顶边变化/换清晰度） | [measurePortraitStage]：列表槽顶边立刻贴回视频带底边（★第 3 条） |
     *
     * ★为什么每只都要"变了才动"：`OnLayoutChangeListener` 在**每一次强制布局**都会被调用
     *   （不只是 frame 真的变了），而我们这里改 LayoutParams 又会请求一次布局 ——
     *   不设门就会变成"改→布局→再改"的自激回路（白耗电，还会让手势气泡一直闪）。
     */
    private fun installPageLayoutWatchers() {
        rootLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val w = rootLayout.width
            val h = rootLayout.height
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            if (w == lastPageWidth && h == lastPageHeight) return@addOnLayoutChangeListener
            lastPageWidth = w
            lastPageHeight = h
            // ★诊断日志（只读；签名没变就不写）：**真实尺寸变了** —— 转屏/进出小窗/分屏的唯一可信信号
            LivePageTrace.noteIfChanged(
                "layout.page",
                "$w|$h",
                "layout.pageSize",
                "page" to "${w}x$h",
                "landscape" to (w > h),
                "configOrientation" to resources.configuration.orientation,
                "pip" to isInPictureInPictureMode,
                "imeInset" to imeInsetPx,
                "navInset" to systemBarBottomInsetPx,
            )
            // ★第七批：页面高度一变 = "窗口到底有没有为键盘让位"的答案变了，
            //   先把底栏的内边距重算一次（老系统上窗口会缩矮，这一步会把多加的那份减掉），
            //   再按新尺寸重排底栏与版式（转屏 / 分屏 / 进出 PiP / 折叠屏展开）。
            refreshBottomBarInsets()
            syncPageLayoutToRealSize()
            // ★★第十一批第 1 条：**真实尺寸变了 = 唯一可信的"页面形态变了"信号，沉浸式也在这里收敛**。
            //   这就是"各种逻辑门"里最关键的一道：转屏 / 进出 PiP / 分屏 / 折叠屏展开，不管是从
            //   哪条回调走进来的，尺寸一落定就会到这里 —— 而 [onConfigurationChanged] 那一刻
            //   读到的还是旧尺寸（见 [syncImmersivePolicy] 的 KDoc），少了这一句，
            //   "横屏转回竖屏后状态栏还是隐藏的 / 从 PiP 回来还是隐藏的"就永远没人纠正。
            //   放在 [syncPageLayoutToRealSize] 之后：先用新尺寸把版式排好，再按同一个尺寸定系统栏。
            //   幂等（策略没变且真实可见性已符合 → 一个字节不下发），所以不会与上面那句形成自激。
            syncImmersivePolicy()
            // ★★第九批：页面尺寸变了（转屏 / 窗口为键盘让位）—— 同样安排一次"落定后重推"，
            //   把宿主列表面板的底边钉回"底栏现在的顶边"（理由见 [scheduleLiveListGeometrySettle]）。
            scheduleLiveListGeometrySettle()
        }
        // 顶栏底边 = 视频带该在的顶边（顶栏那份 insets 里已经含状态栏内边距，见 buildUi ④）
        topBar.addOnLayoutChangeListener { _, _, _, _, _, bottom, _, _, oldBottom ->
            if (bottom == oldBottom) return@addOnLayoutChangeListener
            if (bottom == appliedVideoBandTop) return@addOnLayoutChangeListener
            if (!::videoContainer.isInitialized) return@addOnLayoutChangeListener
            applyVideoStageLayout()
        }
        // 视频带矩形一变（比例到达/顶边下移/底栏变高），列表槽顶边立刻跟上去 → 中间不留黑缝
        videoContainer.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val frame = Rect(v.left, v.top, v.right, v.bottom)
            if (frame == lastVideoBandRect) return@addOnLayoutChangeListener
            lastVideoBandRect = frame
            measurePortraitStage()
        }
        // ★★第五批第 4 条：**底栏每布局一次就按它自己的真实宽度校准一次字号**。
        //   这一只是"字号恒定"的最终保险：它不看任何标志位、不等任何回调顺序 ——
        //   PiP 里底栏按钮是 INVISIBLE（不画但仍有位置），退出后重新可见 → 一定有一次布局 →
        //   字号按**恢复后的真实宽度**重算（幂等，档位没变就一个字节都不写）。
        //   ★这里**故意不做**"宽度没变就跳过"的短路：`OnLayoutChangeListener` 本来就在每次布局
        //   都会被调用，而 [applyBottomBarTextSizes] 自己幂等（靠 `tag` 比对档位），
        //   短路反而会漏掉"宽度没变、但字号曾被别的路径写坏"的情况。
        // ★★第七批加了第二件事：**底栏顶边一变就重新量竖屏版式**。
        //   为什么必须有它：底栏是"弹幕列表区的底边"（[measurePortraitStage]），而它的顶边会因为
        //   三件事上移 —— ① 键盘弹出（[refreshBottomBarInsets] 加了底部内边距）；
        //   ② 输入条下方的失败提示行出现/收起；③ 转屏换版式（输入条与按钮同一行 ↔ 两行）。
        //   不重新量，"列表底边 = 底栏顶边"这条契约就会过期，列表会被输入条压住一截。
        //   ★只判 `top`（不判高度）：高度变化必然伴随顶边变化（底边固定在页底），判一个就够；
        //   而 [measurePortraitStage] 自己是幂等的（[applyListSlot] 值没变就一个字节不写），
        //   不会形成"改 → 布局 → 再改"的自激回路。
        // OnLayoutChangeListener 的 9 个参数：(v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom)
        // —— 第 5 个是 bottom，之前写成 `_` 却在体内用 `bottom`，编译报 Unresolved reference。
        bottomBar.addOnLayoutChangeListener { _, left, top, right, bottom, _, oldTop, _, _ ->
            val width = right - left
            if (width > 0) applyBottomBarTextSizes(width)
            if (top != oldTop) {
                // ★诊断日志（只读；签名没变就不写）：底栏**落定后**的顶边（列表区底边的契约输入）
                LivePageTrace.noteIfChanged(
                    "bottomBar.geom",
                    "top=$top|h=${bottom - top}|oldTop=$oldTop",
                    "bottomBar.geom",
                    "barTop" to top,
                    "barHeight" to (bottom - top),
                    "oldTop" to oldTop,
                    "appliedInset" to appliedBottomBarInsetPx,
                )
                measurePortraitStage()
                // ★★第九批：底栏顶边一变（键盘抬栏 / 失败提示行显隐 / 转屏换版式 / 窗口变矮），
                //   除了当场重量列表槽，还要**落定后再推一次宿主的列表面板** ——
                //   宿主自己的三条自动重量路都要过重组，键盘/转屏这种连续变化里
                //   只要有一环没赶上，面板底边就会停在旧值（用户看到的半透明残影 / 一大块黑色空白）。
                scheduleLiveListGeometrySettle()
            }
        }
    }

    /**
     * 底栏按钮的**固定顺序**（[rebuildBottomBar] 按它摆进底栏，[applyBottomBarTextSizes] 按它算等宽格）。
     * 顺序即"使用频率"（★第十四批之后共 5 颗，用户拍板的顺序）：
     * ```
     * 输入条（在它们左边，不算按钮）：弹幕开关 → 画质 → 设置 → 画中画 → 旋转
     * ```
     * · **「弹幕开」第一颗**：三态「弹幕开 / 弹幕关 / 重连」（[updateDanmakuButton]）；
     * · **「画质」**：原来的「画质」+「线路」两颗合并（两者本来就是"选用哪条流"），
     *   点开是同一个弹窗的两段（[showStreamDialog]）。★第八批文案从「画质·原画」缩短成「画质」
     *   （理由见 [qualityButton] 的 KDoc）；
     * · **「设置」**（★第十四批，本颗）：用户点名"画质的后面、PIP 的中间"，弹直播设置弹窗
     *   （[showLiveSettingSheet] → compose 模块的 `LiveSettingSheetHost`）；
     * · **「画中画」**：点一下进小窗（[enterPipMode]）；
     * · **「旋转」**：用户点名"那个旋转一定要放到底栏来啊，它是经常用到的"。
     *
     * ★第七批从这里**删掉**的按钮（连同它们的字段）：`retryButton`「刷新」（改自动追流，
     *   手动入口在「画质·线路」弹窗里）、`sendDanmakuButton`「发弹幕」（变成输入条本身）。
     *   「画中画」第七批曾搬到顶栏（那颗图标已作为死代码删除），用户随后改主意，**又回到了底栏**（本清单里那颗）。
     * ★「设置」自己也有过一轮反复：第二批从底栏**删掉**（用户当时："用户想设置自己退出来再去设置"），
     *   ★第十四批用户明确要求**加回来**（文案与位置都点名了）—— 键与设置项一直没有变过，
     *   变的只是入口；现在两个入口（本页弹窗 + 「设置 → 直播设置」页）共用同一份项
     *   （`liveSettingPreferenceItems`），不再有"两套 UI 各写各的"那种漂移。
     * ★历史：「暂停/播放」（第四批）、「听音频」「UP主」（第五批）也早已从这里删除，
     *   理由见类注释各批次那几段。
     * ★★第八批：这一行里的按钮**必须等宽**（用户第 3 条），所以清单的顺序 = 屏幕上的从左到右，
     *   而格宽由 [applyBottomBarTextSizes] 按"一行里最宽的文案"统一算出来
     *   —— 往这里加/减按钮都不需要再改排版代码（颗数变了，`weight=1` 的等分格自动跟着分；
     *   第十四批加第 5 颗就是这么加的，`applyBottomBarTextSizes` 一行都没动）。
     */
    private fun orderedBottomButtons(): List<TextView> = listOf(
        // ★顺序由用户指定：弹幕开关 → 画质 → **设置** → 画中画 → 旋转
        //   （「设置」是 2026-09-26 第十四批点名的位置："画质的后面、PIP 的中间"；
        //     输入条在它们左边，不算按钮）
        danmakuButton, qualityButton, settingButton, pipButton, rotateButton,
    )

    /**
     * 重建底栏（★第七批：从"按可见按钮分行"改成"**摆两个可搬运的容器**"；
     * ★第八批：竖屏与横屏**统一成同一套一行版式**；★第十四批：同一行里 5 颗按钮）。
     *
     * ```
     * bottomBar(VERTICAL) = [ row(HORIZONTAL: danmakuInputRow(weight=1) + bottomButtons), danmakuInputError ]
     *                          ↑ 输入框在左、吃掉剩下的宽度        ↑ 5 颗**等宽**按钮贴在右
     * ```
     * ★★第八批为什么竖屏也改成一行（用户实测第 1/3 条）：
     * · "还有那个输入框，你干嘛要单独一行呢？把它和那个几个按钮放一起呢？"
     *   —— 竖屏原来把输入条单独摆一行（第七批的写法），用户明确不要；
     * · 那一行里的按钮原先各按 `WRAP_CONTENT` 排，文案长短不同 → 宽窄不一、看着"奇奇怪怪"；
     *   现在一律 `weight=1` 等分 [bottomButtons]（容器宽度由 [applyBottomBarTextSizes] 定死），
     *   字号也统一成一档 —— **同高、同宽、同内边距**。
     * · 用户还明确不要横滑（"别横滑"）：所以一行的宽度账全部在 [applyBottomBarTextSizes] 里算完，
     *   窄屏是**缩字号**（[BOTTOM_BUTTON_TEXT_SP_STEPS]），不给任何滚动容器。
     * ★**只搬运容器，不重建按钮**：五颗按钮在 [buildUi] 里 addView 进 [bottomButtons] 一次，
     *   这里只把 [bottomButtons] 换父容器。好处是按钮的 `tag`（字号档位记录）与背景不会被反复刷掉。
     * ★**为什么不再有"每行几个/均分/最多两行"**：底栏固定那么几颗按钮（★第十四批起 5 颗）、
     *   两种形态都只有一行（见类注释第七批）；那套 `chunked(perRow)` 与
     *   `PORTRAIT_BUTTONS_PER_ROW` 一起删了。
     *   而 `BOTTOM_ROW_EQUAL_CELL_MIN_COUNT` 那条"≥4 颗均分"的规则现在**恒成立**（就是这条一行版式）。
     *
     * ★★第四批第 1 条仍然成立：判"哪种形态"用 [isPageLandscape]（真实布局尺寸），
     *   不读 `Configuration.orientation`（那个值在小窗/分屏下是"窗口方向"）。
     *   ★第八批：方向**不再参与排版**（一行版式通用），它只留在 [bottomBarKey] 的指纹里 ——
     *   转屏后重建一次仍然是对的（幂等，重建出来的结构与横屏那套逐字相同）。
     *
     * ★★第五批第 4 条仍然成立：重建完**立刻按底栏真实宽度把字号档位刷一遍**
     *   （[applyBottomBarTextSizes]）—— 底栏的布局监听随后还会再校准一次（幂等）。
     *
     * @param orientationOverride 只留给"页面还没量出尺寸"的兜底场景（现无调用点传值）；
     *   传 null = 按 [isPageLandscape] 判（真实尺寸优先）
     */
    private fun rebuildBottomBar(orientationOverride: Int? = null) {
        if (!::bottomBar.isInitialized) return
        if (!::danmakuInputRow.isInitialized || !::bottomButtons.isInitialized) return
        if (!::danmakuInputError.isInitialized) return
        // 先把三个"可搬运的容器"从旧父容器里摘出来（View 只能有一个父容器，不摘就 addView 抛异常）
        listOf<View>(danmakuInputRow, danmakuInputError, bottomButtons).forEach { child ->
            (child.parent as? ViewGroup)?.removeView(child)
        }
        bottomBar.removeAllViews()

        val landscape = isPageLandscape(orientationOverride)
        val visibleCount = orderedBottomButtons().count { it.visibility == View.VISIBLE }
        bottomBarLayoutKey = bottomBarKey(landscape, visibleCount)
        // 每一行都**新造**一份 LayoutParams（不给几个孩子共用同一个实例：共用的那份一旦被谁改了，
        // 另外几个孩子会跟着变 —— 这种坑不值得省这几行）
        fun barRowLp(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        // ★一行版式（竖屏/横屏同一套）：输入条（weight = 1，吃掉剩下的宽度）+ 五颗按钮（等宽格贴在右）
        val row = LinearLayout(this).apply {
            // ★写 `this.orientation` 是有来历的（第四批去掉了外层那个 `val orientation` 局部变量，
            //   保留这个写法是为了不再踩同一脚）：局部变量会**优先于**隐式接收者的成员，
            //   直接写 `orientation = …` 一旦外层再出现同名局部 val，就会被解析成给它赋值 →
            //   编译报 "'val' cannot be reassigned"。写成 `this.` 就永远指向这一行的 LinearLayout。
            this.orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            danmakuInputRow,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        // 按钮容器的宽度由 [applyBottomBarTextSizes] 按"等宽格 × 颗数"写；这里给 `WRAP_CONTENT`
        // 只是首帧的兜底（那一次校准就在本函数末尾，下一帧前一定已经写上了）。
        row.addView(
            bottomButtons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        bottomBar.addView(row, barRowLp())
        // 失败提示紧贴输入条那一行下方（见 [showDanmakuInputError]）
        bottomBar.addView(danmakuInputError, barRowLp())
        // ★刚换过父容器的按钮还没有测量结果：先用"底栏宽 == 页宽"这个已知条件定一次档位
        applyBottomBarTextSizes(bottomBarAvailableWidthPx())
    }

    /**
     * 底栏版式的"指纹"：**方向 + 可见按钮数**。
     * 两个都相同 = 现在这套结构就是对的，不用重建（[syncBottomBarLayout] 的幂等依据）。
     * ★第七批去掉了原来第三段 `perRow`：底栏只剩 3 颗按钮，两种形态都只有一行，没有"每行几个"了。
     */
    private fun bottomBarKey(landscape: Boolean, visibleCount: Int): String =
        (if (landscape) "L" else "P") + "/" + visibleCount

    /**
     * 把底栏版式**同步到当前真实尺寸**（幂等：指纹没变就一个 View 都不动）。
     *
     * 这是第四批第 1 条的落点：任何"可能让版式该变"的时机都只调它，由它去比对指纹 ——
     * 于是不会出现"两次重建之间版式停在中间态、又没人再排一次"的情况。
     * 调用点：[onConfigurationChanged]、[installPageLayoutWatchers] 的尺寸监听、
     * [onPictureInPictureModeChanged]（退出小窗时带 force = true）。
     *
     * ★**在 PiP 里一律不重建**：小窗里的尺寸/方向都不代表用户回到全屏后要的版式；
     *   退出小窗时 [onPictureInPictureModeChanged] 会用恢复后的真实尺寸补上这一次（且是强制的一次）。
     *   ★字号**不靠这里保证**：底栏每次布局都由 [applyBottomBarTextSizes] 按真实宽度校准
     *   （见那段 KDoc），所以就算这一次重建被 PiP 门控挡掉、或者按小窗宽度重建了，
     *   回到全屏后字号也会自己收敛到正确档位。
     *
     * @param force true = 忽略指纹强制重建（退出 PiP：指纹记不住"格宽变了但结构没变"这种中间态）
     */
    private fun syncBottomBarLayout(force: Boolean = false) {
        if (!::bottomBar.isInitialized) return
        if (isInPictureInPictureMode) return
        val visibleCount = orderedBottomButtons().count { it.visibility == View.VISIBLE }
        val landscape = isPageLandscape()
        val key = bottomBarKey(landscape, visibleCount)
        // ★诊断日志（只读）：底栏版式指纹（行数 = 恒定 1 行；可见按钮数 + 方向 + 是否强制）
        LivePageTrace.note(
            "bottomBar.sync",
            "key" to key,
            "prevKey" to (bottomBarLayoutKey ?: "-"),
            "force" to force,
            "landscape" to landscape,
            "visibleCount" to visibleCount,
            "rows" to 1,
            "sp" to (orderedBottomButtons().firstOrNull { (it.tag as? Int) != null }?.tag ?: "-"),
            "pip" to isInPictureInPictureMode,
        )
        if (!force && key == bottomBarLayoutKey) return
        rebuildBottomBar()
    }

    /**
     * 页面"当前实际形态"是不是横屏 —— **判据是真实布局尺寸，不是 `Configuration.orientation`**。
     *
     * ```
     * 页面已经量过尺寸 → 宽 > 高 ？横屏 ：竖屏      （尺寸 = 方向的最终真源）
     * 还没量过（首帧之前）→ 退回 orientationOverride / resources.configuration
     * ```
     *
     * ★为什么必须这么判（第四批第 1 条的根因）：
     * · `Configuration.orientation` 描述的是**窗口**方向：PiP 小窗、分屏、自由窗口下它
     *   **不等于设备方向**（16:9 小窗在竖屏手机上就是 LANDSCAPE）。项目里点播早就踩过并写下结论：
     *   `PlayerController.updatePlayerMode()` —— "画中画：窗口方向 ≠ 设备方向，别按它推导"，
     *   且它的 `hostIsLandscape()` 同样是"先看 decorView 的真实宽高，再看配置"。
     * · 宿主 `LiveDanmakuOverlayHost` 判"竖屏列表模式"用的也是**真实尺寸**（`portrait = h > w`）。
     *   两边判据必须同源，否则会出现"播放页以为在横屏（不留槽）、宿主以为在竖屏（要列表）"这种
     *   自相矛盾的中间态。
     * · 进/出 PiP 都会触发配置回调，而"退出"那一刻**没有任何东西保证再来一次回调** ——
     *   只认真实尺寸 + 尺寸监听（[installPageLayoutWatchers]）才能覆盖所有路径。
     */
    private fun isPageLandscape(orientationOverride: Int? = null): Boolean {
        if (::rootLayout.isInitialized) {
            val w = rootLayout.width
            val h = rootLayout.height
            if (w > 0 && h > 0) return w > h
        }
        val orientation = orientationOverride ?: resources.configuration.orientation
        return orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * 按**真实尺寸**把"底栏分行 + 视频带/列表槽版式"重排一次（幂等，见两张指纹）。
     * 调用点：转屏/分屏/PiP 的配置回调、页面尺寸监听、退出 PiP。
     *
     * ★顺序：先底栏（列表区底边 = 底栏顶边，底栏没排完就量不准），再视频版式。
     *
     * @param force 透传给 [syncBottomBarLayout]：退出 PiP 时用它忽略指纹强制排一次
     *   （那时可能"结构没变、格宽变了"，指纹看不出来）
     */
    private fun syncPageLayoutToRealSize(force: Boolean = false) {
        if (!::rootLayout.isInitialized) return
        if (isInPictureInPictureMode) return
        syncBottomBarLayout(force = force)
        applyVideoStageLayout()
    }

    /**
     * 底栏单颗按钮的样式：**主题色底 + 白字 + 固定档位字号**（★第五批：不再用平台 autosize）。
     *
     * · 底色 = 强调色压到 ~30% 不透明度（[accentColor] 已经是用户当前主题色）——
     *   这就是"按钮态跟随主题"的落点；
     * · 文字白色：按钮压在**视频**上，白字对画面/主题底都有足够对比（和点播控制条一致）；
     * · 左右内边距压到 [BOTTOM_BUTTON_PADDING_H_DP]dp：均分格子里文案再长也不换行、不溢出
     *   （换行会把底栏撑成三行，那正是用户抱怨的"按钮过多"）；
     * · 字号 = [applyBottomBarTextSizes] 按"**一行里最宽的文案** + 等宽格预算"统一挑一档，
     *   再由 [applyDeterministicTextSize] 写到每一颗上（★第八批：同一档 → 同高）。
     *
     * ## ★★为什么把 autosize 换掉（第五批第 4 条，用户："返回直播间的话，它那些按钮的字体又变小了"）
     * 第四批写的"先把字号归一到 14sp 再交给 autosize"这套保护**从来没生效过**，两条 AOSP 源码级事实：
     * 1. `TextView.setTextSize(int unit, float size)`（AOSP 13 `TextView.java:4485`）的整个函数体是
     *    `if (!isAutoSizeEnabled()) { setTextSizeInternal(...) }` —— autosize 一旦打开，
     *    **这个调用直接返回**，字号一个字节都不改。"归一到 14sp"这句话在代码里是真的，在运行时是空的；
     * 2. `setAutoSizeTextTypeUniformWithConfiguration(...)`（`:1948`）会**当场**用
     *    `getMeasuredWidth()`（= **上一次测量**留下的宽度）算一次并写进去，
     *    而且是 `setTextSizeInternal(..., false)`（**不请求重新布局**，`:9842`）。
     *    于是"按小窗宽度测出来的 9sp"可能就一直挂在按钮上；对 `wrap_content` 的按钮更是死锁 ——
     *    它的宽度跟着字号走，字号小 → 宽度小 → autosize 认为"当前字号刚好放得下"，永远不往回长。
     * ⇒ 结论：字号只由"**测量时机**"决定，不可判定。现在改成**确定性档位**：
     *   字号 = f(按钮真实格宽)，由 [applyBottomBarTextSizes] 在**底栏每一次布局**时重算，
     *   与"哪条回调先来、有没有被重建过、PiP 有没有插一脚"全部无关。
     */
    private fun applyBottomButtonStyle(button: TextView) {
        button.setTextColor(Color.WHITE)
        button.gravity = Gravity.CENTER
        button.maxLines = 1
        button.ellipsize = TextUtils.TruncateAt.END
        val padH = dpToPx(BOTTOM_BUTTON_PADDING_H_DP)
        button.setPadding(padH, dpToPx(6), padH, dpToPx(6))
        button.background = GradientDrawable().apply {
            cornerRadius = dpToPx(6).toFloat()
            setColor(ColorUtils.setAlphaComponent(accentColor(), 78))
        }
        // ★先**关掉**平台 autosize（必须！见上面 KDoc 第 1 条：不关的话下面这句 setTextSize 是空操作），
        //   再由 applyBottomBarTextSizes 按真实格宽写档位。这里先按上限给一版，避免新按钮第一帧过小。
        TextViewCompat.setAutoSizeTextTypeWithDefaults(
            button,
            TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE,
        )
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, BOTTOM_BUTTON_TEXT_SP_STEPS.first().toFloat())
        // ★★第八批（必须）：上面这句把字号**写回了上限档**，而"当前档位"记在 `View.tag` 里 ——
        //   不清掉 tag，[applyDeterministicTextSize] 的幂等判断就会认为"已经是那一档了"而直接 return，
        //   于是**上限档字号会被留在按钮上**（第八批起档位可能真的是 13/11/9，这个坑就真的会踩到；
        //   第五批那种"恒为上限档"的写法下它只是潜在问题）。清成哨兵值，下一次校准一定会重写。
        button.tag = null
    }

    /**
     * 底栏**可用宽度**（px）：优先底栏自己的实测宽度，其次页面宽度，最后整块屏幕宽度。
     *
     * 三个来源的优先级是有意的：底栏是 `MATCH_PARENT`，正常情况下它的宽度就是页宽；
     * 但它刚 addView / 还没布局时宽度是 0，这时用页宽（同样权威，只是晚一帧才更新）；
     * 页面也还没量的话（`buildUi` 里第一次 rebuild）只能退回 displayMetrics。
     */
    private fun bottomBarAvailableWidthPx(): Int {
        if (::bottomBar.isInitialized && bottomBar.width > 0) return bottomBar.width
        if (::rootLayout.isInitialized && rootLayout.width > 0) return rootLayout.width
        return resources.displayMetrics.widthPixels
    }

    /**
     * 按**底栏真实宽度**把每一颗按钮的"等宽格 + 统一字号"刷一遍
     * （★第五批第 4 条的核心；★第八批改成"等宽 + 一行同字号"；★第十四批起是 5 颗，
     * **这个函数一行都没改** —— 颗数是从 [orderedBottomButtons] 数出来的）。
     *
     * ```
     * ① 按钮区预算 = 底栏宽 - 底栏内边距 - 每格左右外边距 - 输入框保底宽([DANMAKU_INPUT_MIN_WIDTH_DP])
     *    格宽预算   = 按钮区预算 ÷ 颗数
     * ② 字号档位：取 [BOTTOM_BUTTON_TEXT_SP_STEPS] 里**最大的、且"一行里最宽的那条文案"也放得下**的一档
     *    （所有颗共用这一档 → 字号相同 → **同高**）
     * ③ 格宽 = 最宽那条文案在这一档下的实测宽 + 左右内边距，并夹进"格宽预算"
     *    （所有颗同一个值 → **同宽**）；再把"格宽 × 颗数 + 外边距"写进 [bottomButtons] 的宽度，
     *    容器里每颗都是 `weight = 1` 的等分格，于是**等分**这件事由 weight 完成。
     * ```
     *
     * ## 为什么是"先让出输入框，再缩字号"（★第八批，用户第 3 条）
     * 输入框与那 5 颗按钮现在**同一行**，两者的宽度是零和的：
     * ```
     * 按钮占满 → 输入框被压成一条缝（用户明确不要）
     * 按钮不缩字号又不让位 → 整行溢出（用户明确不要横滑）
     * ```
     * 所以先扣掉输入框的保底宽（[DANMAKU_INPUT_MIN_WIDTH_DP]），剩下的才是按钮区的预算；
     * 预算不够就沿 [BOTTOM_BUTTON_TEXT_SP_STEPS] **缩字号**（14 → 13 → 11 → 9），
     * 直到"最宽的那条文案 + 内边距"放得进一格。文案本身也已经足够短
     * （「画质」不写当前值，理由见 [delegateListener] 里那一处），所以正常机型上停在 13~14sp。
     * ★★第十四批（颗数 4 → 5）**如实记一笔**：这一档会随颗数变化，361dp 的机器上会落到 9sp
     *   （账见 [DANMAKU_INPUT_MIN_WIDTH_DP] 那段 KDoc 与交付报告 §2）—— 这是**算法的既定行为**，
     *   不是这次改坏了；本轮按要求只动了"文案表与顺序"，没有另起测量逻辑、也没有调任何常量。
     *
     * ## 为什么它是一个"随便调、幂等、且不会自激"的函数
     * · **纯函数**：只读"底栏宽 + 按钮文案 + 按钮自己的 Paint"，不读任何历史状态；
     * · **幂等**：算出来的档位/容器宽度与当前值相同就一个字节都不写
     *   （档位记在按钮的 `View.tag` 上，容器宽度比对 `layoutParams.width`）——
     *   它自己会写 [bottomButtons] 的宽度（触发一次布局），写在底栏的布局监听里也不会"改→布局→再改"自激；
     * · **不自激**：格宽只由"最宽文案 × 字号"决定，与按钮的实际宽度无关（宽度是 weight 分出来的）；
     * · **调用点**：① [rebuildBottomBar] 末尾（新按钮还没有测量结果）；
     *   ② [bottomBar] 的布局监听（**每次布局都调**，宽高没变也调 —— 这正是"PiP 里 INVISIBLE、
     *   退出后重新可见"那条路能自愈的原因：按钮一被重新布局，字号就按当时的真实宽度重算一次）；
     *   ③ [applyThemeColors] 末尾（换主题时顺带校准一次）；
     *   ④ 文案变化处（[delegateListener] / [updateDanmakuButton]）。
     */
    private fun applyBottomBarTextSizes(barWidthPx: Int) {
        if (!::bottomBar.isInitialized || barWidthPx <= 0) return
        val barPadding = dpToPx(BOTTOM_BAR_PADDING_DP) * 2
        val cellMargin = dpToPx(BOTTOM_BUTTON_MARGIN_DP) * 2
        val padH = dpToPx(BOTTOM_BUTTON_PADDING_H_DP)
        // ★第七批起不再遍历 `bottomBar` 的孩子（那一层装着"输入条 + 按钮"整行 / 失败提示行，
        //   拿它们当按钮行会连输入框一起量字号），改成**直接按按钮清单算** —— 与"按钮被摆在哪个容器里"
        //   完全无关。★第八批起所有按钮恒在同一行（★第十四批起 5 颗），这段账只有这一种形态。
        val buttons = orderedBottomButtons()
        val count = buttons.size
        if (count <= 0) return
        // ① 先给输入框让位：按钮区最多只能用"整行 - 内边距 - 输入框保底宽"
        val inputFloor = dpToPx(DANMAKU_INPUT_MIN_WIDTH_DP)
        val cellBudget = ((barWidthPx - barPadding - count * cellMargin - inputFloor) / count)
            .coerceAtLeast(1)
        val textBudget = (cellBudget - padH * 2).coerceAtLeast(1)
        // ② 所有颗共用一档字号：取"最大的、且一行里最宽的文案也放得下"的那一档
        var chosenSp = BOTTOM_BUTTON_TEXT_SP_STEPS.last()
        var widestPx = 0f
        for (sp in BOTTOM_BUTTON_TEXT_SP_STEPS) {
            val w = widestBottomButtonLabelPx(buttons, sp)
            if (w <= textBudget) {
                chosenSp = sp
                widestPx = w
                break
            }
            // 全部档位都放不下时用最小档：widestPx 仍是 9sp 下的实测宽（下面会被 cellBudget 夹住）
            widestPx = w
        }
        // ③ 所有颗**同宽**：格宽 = 最宽文案 + 左右内边距，再夹进预算（宁可窄一点也不溢出）
        //    ★≥ [BOTTOM_ROW_EQUAL_CELL_MIN_COUNT] 颗走等宽格；不足那个数时格宽 = 整行可用宽
        //    （与改动前"自然宽行"的语义一致：宽度不构成约束，按钮按内容宽居中）
        val equalCells = count >= BOTTOM_ROW_EQUAL_CELL_MIN_COUNT
        val cellWidth = if (equalCells) {
            (ceil(widestPx).toInt() + padH * 2).coerceIn(1, cellBudget)
        } else {
            cellBudget
        }
        // ★诊断日志（只读；签名没变就不写）：字号档位与格宽（"回全屏字变小"那一条的观测点）
        LivePageTrace.noteIfChanged(
            "bottomBar.text",
            "w=$barWidthPx|sp=$chosenSp|cell=$cellWidth|n=$count",
            "bottomBar.text",
            "barWidth" to barWidthPx,
            "count" to count,
            "sp" to chosenSp,
            "spSteps" to BOTTOM_BUTTON_TEXT_SP_STEPS.joinToString("/"),
            "widestPx" to widestPx,
            "cellBudget" to cellBudget,
            "cellWidth" to cellWidth,
            "equalCells" to equalCells,
            "landscape" to isPageLandscape(),
            "pip" to isInPictureInPictureMode,
        )
        buttons.forEach { applyDeterministicTextSize(it, chosenSp) }
        // 容器宽度 = 格宽 × 颗数 + 每格左右外边距（容器里是 weight=1 的等分格 → 所有颗同宽）
        val regionWidth = cellWidth * count + cellMargin * count
        if (::bottomButtons.isInitialized) {
            val lp = bottomButtons.layoutParams as? LinearLayout.LayoutParams
            if (lp != null && lp.width != regionWidth) {
                lp.width = regionWidth
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                bottomButtons.layoutParams = lp
            }
        }
    }

    /**
     * 一行里**最宽的那条按钮文案**在给定字号档位下有多宽（px）—— 用户第 3 条"同字号"的判据。
     *
     * ★为什么取"最宽"而不是"每颗各量各的"：按钮在同一行且共用一档字号，只要最宽的那条放得下，
     *   其余的一定放得下；反过来若按每颗自己量，就会出现"这颗 14sp、那颗 11sp"——
     *   字号不一 → 高度不一 → 正是用户说的"奇奇怪怪"。
     * ★用**按钮自己的 Paint** 量文字（`button.paint`）：它带着这个按钮真实的字体/字距/缩放，
     *   量出来的宽度就是"按这个字号画出来有多宽"，和平台 autosize 的判据同源（都是"文字宽 ≤ 可用宽"），
     *   只是输入宽度从"上一次测量的结果"换成了"当前真实的格宽"。
     * ★量完把 `paint.textSize` 还原：那支 Paint 就是 `TextView` 用来画字的，不能留下副作用。
     */
    private fun widestBottomButtonLabelPx(buttons: List<TextView>, sp: Int): Float {
        val px = spToPx(sp.toFloat())
        var widest = 0f
        buttons.forEach { button ->
            val label = button.text ?: return@forEach
            if (label.isEmpty()) return@forEach
            val paint = button.paint ?: return@forEach
            val restoreTextSize = paint.textSize
            try {
                paint.textSize = px
                widest = maxOf(widest, paint.measureText(label, 0, label.length))
            } finally {
                paint.textSize = restoreTextSize
            }
        }
        return widest
    }

    /**
     * 把**统一选定**的字号档位写到这一颗按钮上（档位由 [applyBottomBarTextSizes] 按"一行里最宽的文案"
     * 算出来，见 [widestBottomButtonLabelPx]）。
     *
     * ★为什么不再"每颗按自己的格宽各挑一档"（第五批的写法）：它们现在与输入框同一行、
     *   共用同一份宽度预算，各挑各的必然出现"字号不一、大小奇怪"（用户第 3 条原话）；
     *   同一行里**同字号 → 同高、同宽**才是这一条要求的落点。
     * ★`View.tag` 在这里只是"当前生效档位"的记录（全工程这些按钮没有别的 tag 用途）；
     *   档位没变就一个字节都不写（幂等，见 [applyBottomBarTextSizes] 的"不自激"那一段）。
     */
    private fun applyDeterministicTextSize(button: TextView, sp: Int) {
        if ((button.tag as? Int) == sp) return
        button.tag = sp
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
    }

    /** sp → px（与 `TextView.setTextSize(COMPLEX_UNIT_SP, …)` 用的是同一个换算，含系统字体缩放） */
    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    /**
     * 通用纯文字按钮（**底栏每一颗**都用它建）。
     *
     * ★顶栏那颗「← 返回」本轮已经**不用它**了：用户要"只有图标、复用点播播放器的返回图标"，
     *   现在是 [backButton]（[ImageView] + `ic_arrow_back_white_24dp`，见 [buildUi] 顶栏那一段）。
     *
     * ★底色 = 主题强调色 30% 透明（用户要"按钮态跟随主题"）。原来是写死的
     *   `argb(90,255,255,255)`（白 35%），换成主题色之后，"点缀色"这件事才真的跟着主题走；
     *   底栏那些按钮会再由 [applyBottomButtonStyle] 统一刷一遍（单行/内边距/固定档位字号），
     *   这里给的是"独立使用它"时的默认样子。
     */
    private fun actionButton(label: String): TextView = AppCompatTextView(this).apply {
        // ★仍然用 AppCompatTextView：底栏按钮会走 TextViewCompat 的 autosize API（第五批是"关掉它"，
        //   但同一族 API 在 API < 26 上只对 AppCompatTextView 生效，裸 TextView 会静默 no-op）。
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
        background = GradientDrawable().apply {
            cornerRadius = dpToPx(6).toFloat()
            setColor(ColorUtils.setAlphaComponent(accentColor(), 78))
        }
    }

    /**
     * 弹窗里的"纯文本按钮"：没有底色、只有点击反馈。
     * ★只在**自建内容的弹窗**（如直播设置）里用；清晰度/线路/发弹幕那几个已经交给
     *   MaterialAlertDialog 的系统按钮栏，不需要它。
     */
    private fun textButton(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(accentColor())
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
        isClickable = true
        selectableItemBackgroundRes().takeIf { it != 0 }?.let { setBackgroundResource(it) }
    }

    /**
     * 播放侧状态文案 = **异常/过渡**（★本轮：正常态不再往状态行写东西）。
     *
     * 用户原话（本轮）："正常播放的时候，这个状态就不要显示了……顶栏就只剩返回和房间号。"
     * 所以本函数**只**用于"此刻值得用户看一眼"的那几档（缓冲中 / 正在追流 / 已暂停 /
     * 弹幕断开 / 未开播 / 播放失败 …），全量清单见 [renderStatus] 的 KDoc。
     *
     * ★正常态请用 [setStreamStatusNormal]（它会让状态行整条隐藏）。分成两个入口而不是
     *   "在调用点自己判断要不要显示"：显示与否是 [renderStatus] **一处**的决定，
     *   调用点只需要回答"我这句话是正常态还是异常态"。
     */
    private fun setStreamStatus(text: String) {
        streamStatus = text
        streamStatusAbnormal = true
        renderStatus()
    }

    /**
     * 正常态文案（★本轮新增）：记下来但**不显示** —— 顶栏状态行只在异常/过渡时出现。
     *
     * 目前唯一的调用点是"真的在播"（`LivePlayState.PLAYING` → 「直播中」）：
     * 它同时承担**清掉上一条异常文案**的职责（追流成功后状态行要自己消失，
     * 而不是把"线路异常，正在切换…"一直挂在顶栏上）。
     */
    private fun setStreamStatusNormal(text: String) {
        streamStatus = text
        streamStatusAbnormal = false
        renderStatus()
    }

    /**
     * 顶栏状态行（[statusText]）—— ★本轮起**只在异常/过渡时显示**，正常播放时 `GONE`。
     *
     * ## 判定清单（"显示"= 异常或过渡，"隐藏"= 正常）
     * | 显示（异常 / 过渡） | 出处 |
     * |---|---|
     * | 正在解析房间… / 正在获取直播流… / 起播中… | [startResolveAndPlay] / delegate 的 LOADING |
     * | 缓冲中… | delegate 的 `STATE_BUFFERING`（只在出过画面之后才报） |
     * | 正在切换清晰度… / 正在切换线路… / **线路异常，正在切换…** | [showStreamDialog] / delegate [delegateListener] 的 LOADING 文案 |
     * | 正在回到直播最新进度… / 重连中… / 正在重新获取直播流… | delegate 的 behind-live-window / 重连 / 重取流 |
     * | 所有线路均失败… / 线路反复失败… / 已固定线路 1… | delegate 的换线预算用尽与"固定第一条"策略 |
     * | 已暂停 | [delegateListener] 的 PAUSED（用户主动暂停，必须看得见） |
     * | 主播未开播（等待开播…）/ 主播已下播（等待重新开播…）/ 直播流反复中断，已停止自动重连（等待开播…） | delegate 的 OFFLINE（★本轮：`live_status` 不再是写给用户看的术语） |
     * | 等待开播：45s 后自动检查（开播即自动起播） | 未开播/下播之后的轮询 [startOfflinePolling]（★本轮改的文案） |
     * | 播放失败：… / 房间号无法识别 | ERROR / 房间号解析失败 |
     * | 自动追流已暂停（5 分钟内已追 3 次） | [autoRetryLiveStream] 的预算用尽 |
     * | 弹幕 连接中 / 重连中 / 连接失败 / 未连接 | [danmakuStatusLabel]（★断开侧异常；已连接是正常态，不显示） |
     * | **隐藏（正常）** | 出处 |
     * |---|---|
     * | 直播中 | PLAYING → [setStreamStatusNormal] |
     * | 画质 … ｜ 线路 … | ★**本轮整条删除**（用户："那部分去掉，画质弹窗里已经有'当前：xxx'"）—— 现在 [onStreamReady] 一个字都不写状态行，当前画质/线路只在「画质 · 线路」弹窗的副标题里 |
     * | 弹幕 已连接 | [danmakuStatusLabel] 返回 null（正常态不占位置） |
     *
     * ## 为什么用 `GONE` 而不是"写空串"
     * 状态行是 [topBar]（LinearLayout）里唯一**没有 weight** 的文字：`GONE` 之后它彻底不参与
     * 测量，`weight=1` 的标题自动吃掉整行（顶栏高度不变 —— `maxLines=1` 本来也不会换行，
     * 所以"顶栏底边 = 竖屏视频带顶边"那条不变式不受影响，见 [videoBandTopPx]）。
     */
    private fun renderStatus() {
        val parts = buildList {
            if (streamStatusAbnormal) add(streamStatus)
            danmakuStatusLabel()?.let { add(it) }
        }
        statusText.text = parts.joinToString(" ｜ ")
        statusText.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * 顶栏标题 = `直播间 房间号（x.x万人在线）`（★本轮：在线人数就写在房间号后面的括号里）。
     *
     * ## 三档（用户要求："拿不到人数时**不显示括号**，不要显示 0 / --"）
     * ```
     * 还没拿到房间号（titleRoomId == 0）→ 保持初值「直播间」
     * roomOnline == null 或 <= 0        → 「直播间 8178490」（不带括号）
     * roomOnline > 0                    → 「直播间 8178490（1.2万人在线）」
     * ```
     *
     * ## 数字口径与首页直播卡片**完全一致**
     * `NumberUtil.converString(online)` —— 与首页直播卡片人气文案同一句
     * （`HomeLiveContent.kt`：`"${NumberUtil.converString(item.online)}人气"`）：
     * ≥1 万显示 `x.x万`（保留 1 位小数）、≥1 亿显示 `x.x亿`、不足 1 万就是原数字。
     * 本页**不自己写格式化**，也不在文案里加"人气/看过"这类另一种口径的说法。
     *
     * 两个入口：① [LivePlayerDelegate.Listener.onStreamReady]（拿到真实房间号）；
     * ② [refreshRoomOnlineIfDue]（每 45s 刷新一次人数）。两处都只调本函数，不各写一份拼接。
     */
    private fun renderRoomTitle() {
        if (!::titleText.isInitialized) return
        val roomId = titleRoomId
        if (roomId <= 0L) return
        // ★★2026-09-26 用户实测回退：**在线人数不加了**。
        //   原因：`get_info` 的 `online` 在部分房间给出的其实是"**看过的人次**"（用户实测某房间显示 7 千，
        //   而该房间实际在线远不止/或根本不是一个量级），拿它当"实时在线"会**骗人** ✗。
        //   而真正可靠的实时在线只在那几个被风控挡住的接口里（`getInfoByRoom` 等，App 外一律 -352 ✗）。
        //   ⇒ 宁可不显示：顶栏回到「直播间 房间号」，不再拼括号（也就不用发那次 45s 一次的请求 ✓）。
        titleText.text = "直播间 $roomId"
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * 控制条（顶栏 + 底栏）现在**允许**显示吗（★第五批第 3 条的唯一门控点）。
     *
     * ```
     * 允许 = 不在 PiP 且 没有"即将进 PiP"
     * ```
     *
     * ★为什么"两个条件"缺一不可：
     * · `isInPictureInPictureMode` 管的是"已经在小窗里"——PiP 全生命周期内控制条必须一直不可见；
     * · [pipEntryPending] 管的是"**马上要进小窗**"那 1~2 秒 —— 从我们决定进小窗（或系统即将自动进入）
     *   到 `onPictureInPictureModeChanged(true)` 之间，`isInPictureInPictureMode` **还是 false**，
     *   而这段时间恰好有一次 [onConfigurationChanged]（窗口缩成小窗会带一次配置变更），
     *   它末尾的 `showControlsTemporarily()` 会把控制条又显示出来（用户看到的就是"PiP 里那一两秒
     *   被底栏和顶栏挡住"）。
     * ★AOSP 依据：`Activity.dispatchPictureInPictureModeChanged()` 是**先写** `mIsInPictureInPictureMode`
     *   再回调 `onPictureInPictureModeChanged(...)`（AOSP 13 `Activity.java:8580-8581`），
     *   所以回调里读 `isInPictureInPictureMode` 是准的 —— [onPictureInPictureModeChanged] 里那句
     *   `hideControlsForPip()` 一定生效。
     */
    // ★2026-09-26 用户实测后**回退**上一轮"进 PiP 就把控制条收死"的做法：
    //   原话"让它显示几秒就显示几秒啊……越改越烂"。现在门控恒为 true —— 控制条按自己的
    //   4 秒计时显示/隐藏（点画面唤出、到点自动收起），PiP 里也一样，不再有"永久收起"的特殊态。
    //   ★第七批补记：[pipEntryPending] / `isInPictureInPictureMode` 现在**只**用来管
    //   **[danmakuInputRow] 那条输入条**的显隐（见 [applyControlsVisibility]）——
    //   顶栏与底栏那几颗按钮的显隐仍然不受它们影响（上一条回退结论不变）。
    private fun controlsAllowed(): Boolean = true

    /**
     * 真正把顶栏 / 底栏那几颗按钮 / 输入条刷成该有的样子（**不看任何门控**，
     * 只给"确定自己在做什么"的调用点用）。
     *
     * ★★第七批起它的行为分三块；★第八批把**输入条并进同一套显隐**（用户实测第 2 条）：
     * | 控件 | 显隐规则 | 为什么 |
     * |---|---|---|
     * | [topBar] | 跟着 `visible`（`GONE`） | 与改动前一致：4 秒自动隐藏、单击唤出 |
     * | [bottomButtons]（那一行按钮，★第十四批起 5 颗） | 跟着 `visible`，但用 **`INVISIBLE`** 而不是 `GONE` | **保住占位**：底栏高度不变 → 底栏顶边不变 → 弹幕列表区不随控制条显隐跳动（"上下留白是用户有意留给控制栏的"）；同时 `INVISIBLE` 的子 View 不参与触摸派发，按钮不会"看不见却点得到" |
     * | [danmakuInputRow]（输入条） | **跟着 `visible`** 一起显隐（`INVISIBLE`，与按钮同款）；**正在输入时一定不收**；**PiP / 即将进 PiP 时 `GONE`** | ★第八批（用户："为什么不和那几个按钮一起显示一两秒呢？为什么要一直站在那？"）：输入条不再是"常驻"，点画面唤出、4 秒后与按钮**一起消失**；正在打字（[isDanmakuInputActive]）时收起会把光标/键盘留着却把条藏了，那是纯粹的添乱，所以那一档强制可见；PiP 小窗里多一条挡画面、也弹不出输入法，所以整条 `GONE`（连占位都不要） |
     * ★`bottomBar` 自己**不整体 GONE**：它是"输入条 + 按钮"的容器，两种显隐都只落在孩子身上 ——
     *   于是隐藏时底栏高度**一点不变**，弹幕列表区的底边也就不会跟着跳。
     */
    private fun applyControlsVisibility(visible: Boolean) {
        controlsVisible = visible
        val flag = if (visible) View.VISIBLE else View.GONE
        topBar.visibility = flag
        if (::bottomButtons.isInitialized) {
            bottomButtons.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
        // ★2026-09-26 用户实测"屏幕上下一直有一条半透明黑条挡着画面"（横屏底部/竖屏/PiP 里都有）：
        //   根因是**只藏了按钮和输入条，没藏装它们的那层容器** —— 而 `bottomBar` 自己带
        //   `setBackgroundColor(scrimColor())`（见 applyThemeColors），容器还在画，黑条就永远在。
        //   现在：容器跟着一起藏；`INVISIBLE`（不是 GONE）**保住占位**，所以用户有意留的上下留白不变，
        //   只是不再画那层黑；PiP 里连占位都不要（GONE），那点高度全留给画面。
        val pipLikeForBar = isInPictureInPictureMode || pipEntryPending
        if (::bottomBar.isInitialized) {
            bottomBar.visibility = when {
                visible -> View.VISIBLE
                pipLikeForBar -> View.GONE
                else -> View.INVISIBLE
            }
        }
        if (::danmakuInputRow.isInitialized) {
            val pipLike = isInPictureInPictureMode || pipEntryPending
            danmakuInputRow.visibility = when {
                // PiP：整条收掉（`GONE`，连占位都不要 —— 小窗里那点高度全留给画面）
                pipLike -> View.GONE
                // ★正在输入（有焦点）或正在发送：**绝不隐藏** —— 用户手指还在输入框上，
                //   条一藏键盘却还在，等于把"正在打的字"藏起来了。[hideControlsRunnable]
                //   也有一道同样的门（那道门管的是"别触发隐藏"，这一道管"触发到了也不落地"）。
                isDanmakuInputActive() -> View.VISIBLE
                visible -> View.VISIBLE
                // 与那几颗按钮**同步隐藏**；`INVISIBLE`（不是 `GONE`）保住占位 —— 底栏高度不变
                else -> View.INVISIBLE
            }
        }
    }

    /** 请求显示/隐藏控制条：**按 [controlsAllowed] 门控**（PiP 期间一切都变成"隐藏"） */
    private fun setControlsVisible(visible: Boolean) {
        applyControlsVisibility(visible && controlsAllowed())
    }

    /**
     * 进小窗的"第一步"就该调它：**撤掉自动隐藏计时 + 立刻收起顶栏/底栏按钮/输入条**（★第五批第 3 条）。
     *
     * 为什么不能只依赖 `onPictureInPictureModeChanged(true)`：那个回调要等窗口**缩完**才来，
     * 中间那 1~2 秒里控制条还挂在屏幕上（用户原话："它虽然只有一两秒的显示时间，但是它会挡住"）。
     * 所以 [enterPipMode]（手动）与 [onUserLeaveHint]（31+ 系统自动进入）都在**最早的那一刻**调它。
     * 撤计时是必须的：`hideControlsRunnable` 到点后会再调一次 [setControlsVisible]，
     * 虽然那时门控也会把它压成不可见，但留着一条待执行的 Runnable 没有意义。
     * ★第七批：调用点会先置 [pipEntryPending]，所以这里顺带把**输入条**也收掉
     *   （见 [applyControlsVisibility] 的门控）—— 小窗里不该多一条挡画面的输入条。
     */
    private fun hideControlsForPip() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        applyControlsVisibility(false)
    }

    /** 显示控制条并重新计时（打开弹窗/手势/转屏时用）；PiP 期间它什么都不做（见 [controlsAllowed]） */
    private fun showControlsTemporarily() {
        if (!controlsAllowed()) {
            // PiP 里"要显示控制条"的请求一律当成"保持隐藏"：既不能亮出来挡画面，
            // 也不能留下一条到点就执行的隐藏任务
            hideControlsForPip()
            return
        }
        setControlsVisible(true)
        holdControls()
    }

    /** 重新计时控制条自动隐藏（打开弹窗/开始手势时用，避免列表看着看着控制条没了） */
    private fun holdControls() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, CONTROLS_AUTO_HIDE_MS)
    }

    /**
     * 沉浸式：**只有横屏（全屏）才隐藏系统栏**（竖屏一律把状态栏显出来）。
     *
     * ★2026-09-26 用户要求："竖屏的情况下可以不要隐藏状态栏吗？……我老是想下滑去看时间，
     *   还有手机电量什么的。竖屏其实可以不用去隐藏的。" —— 竖屏时系统状态栏本来就该在，
     *   顶栏（返回/房间号/状态）排在它**下面**即可（顶栏的顶边本来就是按状态栏内边距算的，
     *   见 [videoBandTopPx] 用的是 `topBar.bottom`，所以这里只要别把它藏掉）。
     *   横屏仍是全屏沉浸（隐藏系统栏 + 允许滑动临时唤出）。
     *
     * ## ★★第十一批第 1 条：只"更新进入那一刻"是不够的（用户复测原话）
     * > "你说竖屏状态下那个状态栏不丢失，你只更新了进入的那一刻，我点击旋转按钮，再旋转再旋转，
     * >  你没有更新，它还是隐藏了。然后我在 PIP 进入软件，它也是隐藏的。**你没有做好各种逻辑门**。"
     *
     * 病根**不是那个 if/else 分支**（它一直是对的），而是"什么时候执行"——两条：
     * 1. **[onConfigurationChanged] 里读到的尺寸还是旧的**。[isPageLandscape] 认的是 [rootLayout]
     *    的真实宽高，而配置回调到达时窗口**还没重新布局**（转屏/进出小窗都是"回调先来、尺寸后到"）
     *    ⇒ 横屏转回竖屏那一次它仍然判成"横屏"→ 又压一遍 hide；而尺寸真正落定的那一刻
     *    （[installPageLayoutWatchers] 的尺寸监听）**原来只重排版式、没有重排沉浸式**
     *    ⇒ 状态栏就永久留在"隐藏"，再转多少次都一样（每次都在旧尺寸上重复同一个错误判断）。
     * 2. **进出小窗这条路上没人重排**。PiP 小窗是 16:9（窗口方向 ≠ 设备方向，见 [isPageLandscape]），
     *    进小窗那次配置回调会把策略判成"横屏 → hide"；而退出小窗时
     *    [onPictureInPictureModeChanged]`(false)` **从来没调过沉浸式** ⇒ 回到全屏的竖屏页面上，
     *    状态栏仍是隐藏的。
     *
     * ## 修法：一个**幂等**的统一入口 + 把所有"形态会变"的路径都接上
     * 判据只认 [isPageLandscape]（**真实尺寸**，不用 `Configuration.orientation` —— PiP 小窗会把
     * 方向判错，这是本工程踩过的坑）；show/hide 的决策与下发**只在本函数里**，
     * 每个调用点都只是"再同步一次"，自己不携带任何判断（这正是"各种逻辑门"的收口方式）。
     *
     * **幂等**（反复调用无副作用、也不会闪）靠两道门：
     * ```
     * ① 策略值没变（immersiveBarsHidden）且 ② 系统栏真实可见性已经符合策略（statusBarVisible）
     *    → 一个字节都不下发
     * ```
     * ②必须存在：小窗/ROM 完全可能在我们背后动过系统栏（"PiP 回来还是隐藏"的直接观感就是这么来的），
     * 只信①那个缓存会把"我们记着显示、实际是隐藏"的状态永久锁死；反过来①保证稳态下每次同步都是纯读，
     * 不会因为"版式重排 → 尺寸回调"这类正常抖动反复下发事务（下发本身也幂等：show 已显示、hide 已隐藏，
     * 在系统侧都是空操作，所以最坏情况也只是多一次无害调用）。
     *
     * ★**PiP 里一律不动**：小窗里的系统栏是 SystemUI 的事，窗口方向也不代表设备方向；
     *   退出小窗时 [onPictureInPictureModeChanged]`(false)` 与尺寸监听都会再调一次，
     *   用**恢复后的真实尺寸**收敛（与 [syncPageLayoutToRealSize] 的 PiP 门控同一条理由）。
     *
     * ## 调用点（任何一个"形态可能变了"的入口都要有它）
     * | 调用点 | 覆盖的形态变化 |
     * |---|---|
     * | [onCreate] | 进页面第一帧（尺寸还没量出来时按 `resources.configuration` 兜底） |
     * | [onResume] | 从别的 App / 小窗回到前台（★第十一批新增） |
     * | [onConfigurationChanged] | 转屏 / 分屏 / 深浅色（尺寸落定后由下面那只监听器兜住） |
     * | [installPageLayoutWatchers] 的 [rootLayout] 尺寸监听 | ★第十一批新增：**真实尺寸变了**是唯一可信的"形态变了"信号（转屏、进出小窗、分屏、折叠屏展开都汇到这里） |
     * | [onPictureInPictureModeChanged]`(false)` | 退出小窗回全屏（★第十一批新增） |
     * | [toggleOrientation] | 底栏「旋转」按钮（★第十一批新增） |
     * | [exitFullscreenToPortrait] | 返回键 / 顶栏返回触发的"退出全屏"（★第十一批新增） |
     *
     * ★"进/出听音频"这类形态切换**没有残留分支**：听音频模式（连同它的舞台与分支）已在第五批
     *   整块删除（见类注释），所以这里不需要为它留调用点。
     */
    private fun syncImmersivePolicy() {
        // PiP 小窗：系统栏不归本页管，小窗方向也不代表设备方向 —— 退出小窗时再收敛
        if (isInPictureInPictureMode) {
            // ★诊断日志（只读）
            LivePageTrace.note("immersive.skip", "reason" to "pip")
            return
        }
        val hide = isPageLandscape()
        // ★诊断日志（只读）：把**判定输入**记下来（真实 statusBar 可见性是幂等门②的一半）
        val barsVisible = statusBarVisible()
        LivePageTrace.note(
            "immersive.eval",
            "policy" to (if (hide) "hide" else "show"),
            "prevPolicy" to (immersiveBarsHidden?.let { if (it) "hide" else "show" } ?: "none"),
            "statusBarVisible" to (barsVisible ?: "unknown"),
            "landscape" to hide,
            "page" to (if (::rootLayout.isInitialized) "${rootLayout.width}x${rootLayout.height}" else "-"),
        )
        // 幂等门①+②：策略没变、且真实可见性已经符合策略 → 什么都不做（不闪、不产生多余事务）
        if (immersiveBarsHidden == hide && barsVisible == !hide) {
            // ★诊断日志（只读）
            LivePageTrace.note("immersive.skip", "reason" to "policyAndVisibilityMatch")
            return
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hide) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            // 竖屏：把系统栏显出来（从横屏全屏、从 PiP 小窗切回竖屏时都要显回来）
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        // ★诊断日志（只读）：真正下发的那一次（show/hide）
        LivePageTrace.note(
            "immersive.apply",
            "action" to (if (hide) "hide" else "show"),
            "statusBarVisibleBefore" to (barsVisible ?: "unknown"),
            "page" to (if (::rootLayout.isInitialized) "${rootLayout.width}x${rootLayout.height}" else "-"),
        )
        immersiveBarsHidden = hide
    }

    /**
     * 系统**状态栏**现在到底可不可见（`null` = 还读不到 / 读失败）。
     *
     * ★用状态栏而不是 `systemBars()` 整体来比对：手势导航下导航栏的"可见性"在部分 ROM 上本来就报
     *   false，拿它一起判会让竖屏每次都判成"不一致"而白下一次 `show()`；而用户要盯的也正是**状态栏**
     *   （见 [syncImmersivePolicy] 的 KDoc）。
     * ★读不到（首帧之前、窗口还没挂 insets）时返回 null ⇒ 上层按"不确定"处理，照样下发一次。
     */
    private fun statusBarVisible(): Boolean? = runCatching {
        ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.statusBars())
    }.getOrNull()

    private fun dpToPx(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun qnDesc(qn: Int): String =
        qualityOptions.firstOrNull { it.qn == qn }?.desc ?: qn.toString()

    // ══════════════════════════════════════════════════════════════════════
    // 弹窗公共设施：正常弹窗（居中、定宽、可滚动）+ 手势提示的全屏透明窗口
    // ══════════════════════════════════════════════════════════════════════

    private fun isAnyDialogShowing(): Boolean =
        streamDialog?.isShowing == true || liveSettingSheetHost?.isShowing() == true

    /**
     * 收掉本页所有"浮层式"弹窗（幂等）。
     *
     * ★第十四批把**直播设置弹窗**也纳进来了：它是 Compose 的 `Dialog`（独立窗口），
     *   与 [LiveListDialog] 同属"压在播放页上面的弹窗"，所以"同一时刻只留一个弹窗"
     *   这条规矩必须由**同一个函数**保证 —— 两个方向都要对称：
     *   · 点底栏「设置」→ [showLiveSettingSheet] 先调这里（收掉可能开着的「画质·线路」）；
     *   · 点底栏「画质」→ [showStreamDialog] 也调这里（收掉可能开着的设置弹窗）。
     *   漏了这一行就会出现"两个弹窗叠在一起、返回键要按两次"。
     * ★本轮：「主播已下播」那个一次性提示也归这里管（同一条规矩，纯加法）。
     */
    private fun dismissDialogs() {
        streamDialog?.dismiss()
        dismissLiveSettingSheet()
        dismissLiveOfflineDialog()
    }

    /**
     * 底栏的**底部内边距**（px）—— ★第七批新增，键盘避让的唯一落点。
     *
     * ```
     * 底部内边距 = 常态内边距([BOTTOM_BAR_PADDING_DP]) + max(导航栏高度, 键盘还要再让出的那部分)
     * ```
     * ## 为什么不是"只加导航栏"（老写法）也不是"只加键盘"
     * · **导航栏**：沉浸式下它是隐藏的，但用户临时划出来 / 三键导航机型上它是常驻的，
     *   底栏必须让开（老注释里"底栏按钮点不着"的一类成因）；
     * · **键盘**：本页 `targetSdk = 36`，而 **Android 15 起"目标 35+ 的 App 被强制 edge-to-edge"**
     *   —— `windowSoftInputMode="adjustResize"` 在那种系统上**不再缩窗口**（官方行为变更），
     *   所以"贴底的底栏自动被顶到键盘之上"这条老路在用户的机器上不成立，必须自己抬。
     * ## 为什么两种世界都只抬一次（这一段是整件事的关键）
     * 键盘弹起时"页面被让出了多少高度"是可以**量**出来的：
     * ```
     * 让出 = pageHeightWithoutIme - 现在的高度        ← pageHeightWithoutIme 只在没有键盘时更新
     * 还要再让出 = max(0, 键盘高度 - 让出)
     * ```
     * · **窗口缩了**（Android 15 以下 / 没被强制 edge-to-edge 的系统）：让出 ≈ 键盘高度 →
     *   还要再让出 ≈ 0 → 不会和窗口缩矮叠成"两倍高的空隙"；
     * · **窗口没缩**（Android 15+ 的 edge-to-edge）：让出 = 0 → 还要再让出 = 键盘高度 →
     *   底栏整体抬到键盘之上。
     * ★时序上的极端情况（老系统上 insets 先到、窗口还没缩完）会先按"没缩"抬一次，
     *   紧接着 [installPageLayoutWatchers] 的页面尺寸监听会用新高度再算一次并把多加的减掉 ——
     *   最多一帧的偏差，而"窗口缩矮"这件事在新系统上根本不会发生（那条路不会走到）。
     * ★键盘高度拿不到（ime = 0）时会把 [pageHeightWithoutIme] 刷成当前页高 —— 那是"基准"的定义。
     * ★写之前比对 [appliedBottomBarInsetPx]：`setPadding` 会 requestLayout，不比对就会"改→布局→再改"自激。
     *
     * ## ★★第九批：基准还要绑定"宽度"（用户实测第 2 条的根因之一）
     * 基准（[pageHeightWithoutIme]）只在 `ime == 0` 时更新，而**键盘开着转屏**时 `ime` 一直 > 0
     * —— 于是横屏会拿**竖屏的 2800** 当基准，算出"窗口已经让掉 1536"，`imeStillCovering` 直接归零
     * ⇒ **横屏 + 键盘：底栏完全不抬**（输入条被键盘盖住/只露一条，用户看到的"有点透明、
     * 不是完全显示的顶上去"）。现在基准连同**量到它时的页面宽度**一起记
     * （[pageHeightBaselineWidthPx]），宽度一变就作废、改用当前方向的整屏高。完整算账见那个字段。
     */
    private fun refreshBottomBarInsets() {
        if (!::bottomBar.isInitialized) return
        val pageWidth = if (::rootLayout.isInitialized) rootLayout.width else 0
        val pageHeight = if (::rootLayout.isInitialized) rootLayout.height else 0
        val ime = imeInsetPx
        if (ime <= 0) {
            // 没有键盘：把"无键盘时的页高"基准确认下来（转屏/分屏/折叠屏展开也在这里跟着更新）。
            // ★第九批：连同"量到它时的页宽"一起记 —— 宽度一变这份基准就作废（见字段 KDoc）。
            if (pageHeight > 0) {
                pageHeightWithoutIme = pageHeight
                pageHeightBaselineWidthPx = pageWidth
            }
        }
        val widthChanged = pageWidth > 0 && pageHeightBaselineWidthPx > 0 &&
            pageWidth != pageHeightBaselineWidthPx
        val baseline = when {
            // ① 同一宽度（同方向）下量到过"无键盘页高" → 用它（最准：分屏/自由窗口也不吃亏）
            pageHeightWithoutIme > 0 && !widthChanged -> pageHeightWithoutIme
            // ② 转屏/折叠屏展开后基准作废 → 用**当前方向的整屏高**（本页恒为沉浸式全屏，
            //    整屏高就是"没有键盘时本页该有的高度"）。
            //    ★这里**故意不掺 `pageHeight`**：转屏回调那一瞬间 `rootLayout.height` 还是旧方向的
            //      （竖屏 2800），跟新的 `displayMetrics`（横屏 1264）取 max 会算出
            //      "窗口已经让掉 1536"这种假账 —— 那正是"横屏 + 键盘不抬底栏"的根因
            //      （用户看到的"有点透明、不是完全显示的顶上去"）。
            widthChanged -> resources.displayMetrics.heightPixels
            // ③ 从没量过基准的兜底（键盘可能比首帧更早到）：取"页高 / 整屏高"里更大的那个 ——
            //    两种世界下都不会多抬（见上面 KDoc 的算账）。
            else -> maxOf(pageHeight, resources.displayMetrics.heightPixels)
        }
        val alreadyGiven = (baseline - pageHeight).coerceAtLeast(0)
        val imeStillCovering = (ime - alreadyGiven).coerceAtLeast(0)
        val bottomExtra = maxOf(systemBarBottomInsetPx, imeStillCovering)
        // ★诊断日志（只读；签名里的 `ime>0` 把键盘动画期间的连续 insets 回调收敛成"起/落"两次）
        val insetsSignature = "${ime > 0}|$bottomExtra|$systemBarBottomInsetPx|$pageWidth|$pageHeight|$baseline"
        if (bottomExtra == appliedBottomBarInsetPx) {
            LivePageTrace.noteIfChanged(
                "bottomBar.insets",
                "skip|$insetsSignature",
                "bottomBar.insets.skip",
                "ime" to ime,
                "navInset" to systemBarBottomInsetPx,
                "page" to "${pageWidth}x$pageHeight",
                "baseline" to baseline,
                "alreadyGiven" to alreadyGiven,
                "imeStillCovering" to imeStillCovering,
                "bottomExtra" to bottomExtra,
                "applied" to appliedBottomBarInsetPx,
                "barTop" to (if (::bottomBar.isInitialized) bottomBar.top else -1),
            )
            // ★★本案修复（**键盘收起后竖屏弹幕列表消失**）：**内边距没变 ≠ 几何没变**。
            //   这里是"IME insets 变了、窗口尺寸一个像素都没变"那条路上**唯一**的播放页入口
            //   （`page=1264x2800` + `ime=1031` 的真机日志可证），而它原来在这条分支里**直接 return**
            //   —— 于是这次 insets 回调在播放页这侧"该重新量的底栏顶边"一次都不量。
            //   现在无论内边距写不写，都安排一次"落定后再推"（[scheduleLiveListGeometrySettle]）：
            //   · 它会**重新量**一次竖屏版式（底栏顶边 → 列表槽）并**主动推**宿主，不依赖任何重组时机；
            //   · 幂等：几何没变时 [measurePortraitStage]/[applyListSlot] 与宿主侧一个字节都不写；
            //   · 不是逐帧轮询：它只在"insets 变化"这个事件上排队，且每次都被重置成"最后一帧之后一次"。
            scheduleLiveListGeometrySettle()
            return
        }
        appliedBottomBarInsetPx = bottomExtra
        LivePageTrace.noteIfChanged(
            "bottomBar.insets",
            "apply|$insetsSignature",
            "bottomBar.insets.apply",
            "ime" to ime,
            "navInset" to systemBarBottomInsetPx,
            "page" to "${pageWidth}x$pageHeight",
            "baseline" to baseline,
            "alreadyGiven" to alreadyGiven,
            "imeStillCovering" to imeStillCovering,
            "bottomExtra" to bottomExtra,
            "prevApplied" to appliedBottomBarInsetPx,
        )
        val pad = dpToPx(BOTTOM_BAR_PADDING_DP)
        bottomBar.setPadding(pad, pad, pad, pad + bottomExtra)
        // ★诊断日志（只读）：写完内边距那一刻的底栏几何（**落定后的**顶边见 stage.measure / bottomBar.geom）
        LivePageTrace.noteIfChanged(
            "bottomBar.insets.result",
            "top=${bottomBar.top}|h=${bottomBar.height}|pad=${pad + bottomExtra}",
            "bottomBar.insets.result",
            "barTop" to bottomBar.top,
            "barHeight" to bottomBar.height,
            "padTop" to pad,
            "padBottom" to (pad + bottomExtra),
        )
        // ★★第九批：底栏**自身的几何变了** —— 这是"弹幕列表底边 = 底栏顶边"这条契约的输入，
        //   落定之后再推一次（键盘动画期间每帧都改 → 计时被不断重置 → 最后只在末尾推一次）。
        scheduleLiveListGeometrySettle()
    }

    /**
     * ★★第九批：把"竖屏列表几何"安排成**几何落定之后再推一次**（幂等；见 [LIVE_LIST_SETTLE_MS]）。
     *
     * 调用点（全是"底栏位置 / 页面尺寸可能变了"的时机）：
     * · [refreshBottomBarInsets] 真的改了底栏内边距时（键盘 insets 驱动的抬栏）；
     * · 底栏自己的布局监听里顶边变了时（[installPageLayoutWatchers]）；
     * · 页面尺寸变了时（同上）。
     *
     * 为什么不是"当场推"：这些时机全都在**布局过程中**（`OnLayoutChangeListener` 就是在 layout 里回调的），
     * 当场改注入面板的 layoutParams 会触发系统那句
     * `requestLayout() improperly called … during layout`（宿主注释里专门躲过这一脚）。
     * `postDelayed` 落在当前这一帧之后，而且每次几何变化都重置计时 ——
     * 所以它保证的是"**最后一帧之后**必然还有一次"，这正是键盘/转屏这种连续多帧变化需要的收敛语义。
     */
    private fun scheduleLiveListGeometrySettle() {
        mainHandler.removeCallbacks(liveListGeometrySettleRunnable)
        mainHandler.postDelayed(liveListGeometrySettleRunnable, LIVE_LIST_SETTLE_MS)
    }

    /**
     * ★★第九批：按**当前活几何**把竖屏列表再收一次口（[scheduleLiveListGeometrySettle] 的落地）。
     *
     * ```
     * ① [measurePortraitStage]：重量"列表槽"（它的底边 = 底栏顶边）与两个矩形
     * ② 宿主 notifyPortraitListGeometryChanged()：让它**立刻**按活几何重算一次面板矩形
     * ```
     * 为什么②不能省（本轮三条用户实测的公共病根）：
     * 宿主的自动重量有三条路（锚点 `OnLayoutChangeListener`、窗口级几何签名、组合里的
     * `LaunchedEffect`），它们**都要经过一次重组**才落到注入面板 View 上；而键盘/转屏这几种变化
     * 是"insets 变了、窗口尺寸不变"或"连续几十帧"的形态，只要其中一环没赶上，
     * 面板的矩形就会**停在旧值**上 —— 屏幕上就是"底边盖住底栏"（键盘上方那个半透明残影）
     * 或"底边停在半空"（弹幕区一大块黑色空白）。这里直接调宿主的公开入口，
     * 不依赖它的重组时机；面板没在屏上时它自己会跳过（见宿主该方法的注释）。
     */
    private fun settleLiveListGeometry() {
        if (isFinishing || isDestroyed) return
        // ★PiP 里不摆竖屏列表（那是宿主自己的判定，见它组合里的 listSupported）——
        //   这条主动路径不该跟它抢：小窗里"列表该不该显示"的答案永远是"不显示"。
        if (isInPictureInPictureMode) return
        measurePortraitStage()
        // ★横屏（= 全屏）不是"竖屏列表"的形态：**只推几何、不推显隐** ——
        //   显隐归宿主组合里的 `listSupported`（竖屏 ∩ 可见 ∩ 非 PiP ∩ 弹幕开着）判定，
        //   这里若也去推一次，横屏下会跟它抢着把列表亮出来（转屏那一两帧的闪）。
        if (isPageLandscape()) return
        danmakuHost?.notifyPortraitListGeometryChanged()
    }

    /**
     * 用户主动离开直播间（顶栏返回图标 / 系统返回键走到的那条路 —— 两条都经 [handleBack] 分级，
     * 只有"竖屏下按返回"与"小窗里按返回"会走到这里）。
     *
     * ★必须显式解除「回 App 仍停在直播间」的武装（[ReturnToLiveGuard]）：
     *   用户是**主动收摊**，下次再进 App 不该被自动弹一个直播间出来 ——
     *   守卫只负责"带着直播间离开过 App（进了 PiP）"这一种情况。
     * ★本轮之前系统返回键**不经过这里**（走的是框架默认的 finish），也就没解除武装；
     *   现在返回键统一收口到 [handleBack] → [exitPage]，这条"主动退出要解除武装"的契约
     *   才对两条路都成立（见 [onBackPressed]）。
     */
    private fun exitPage() {
        // ★诊断日志（只读）：用户主动退出直播间（"回 App 恢复"记录的清理点之一）
        LivePageTrace.note(
            "exitPage",
            "room" to rawRoomId,
            "pip" to isInPictureInPictureMode,
            "isFinishing" to isFinishing,
        )
        returnToLiveGuard.disarm()
        // ★「记住离开时的位置」：用户**主动收摊** → 清掉"应当恢复"的记录。
        //   这条是"正常退出直播间后再回软件不该自动开"的实现；也是"点播页退桌面 → 回软件
        //   不该被拉去直播"那条红线的第一道保证（见 [LiveLastRoomStore] 的清理规则）。
        LiveLastRoomStore.onLivePageExited(applicationContext)
        finish()
    }

    /**
     * 弹窗内容区的**高度上限**（px）：正常弹窗不许顶出屏幕。
     *
     * 为什么要有这个数（用户实测："它弹窗超出了我的屏幕，我点不了"）：
     * 一个 wrap_content 的弹窗，内容（长列表 / 长文本）比屏幕还高时，
     * 系统的做法是把窗口裁到屏幕大小 —— **底部的按钮就被裁到屏幕外了**，怎么点都点不到。
     * 所以这里把"内容区"的高度封顶，弹窗总高 = 标题 + 内容 + 按钮栏 ≤ 可用高度，
     * 内容超高就在内容区内部滚动（[MaxHeightScrollView]），按钮永远留在屏幕里。
     *
     * 算账（保守取整，宁可小一点）：
     * ```
     * 可用高度 = 播放页真实高度 - 输入法高度(仅发弹幕弹窗) - 2 × 24dp（上下留白）
     * 内容上限 = 可用高度 - [DIALOG_CHROME_DP]（标题一行 + 按钮栏 + 内边距，比真实值留足余量）
     * ```
     * 竖屏（≥ 640dp 高）→ 内容上限 ≈ 470dp 以上，足够看 5~6 个选项；
     * 横屏小屏（360dp 高）→ 内容上限 ≈ 110dp，列表变矮但**能滚、按钮一定在屏内**。
     * ★下限 72dp：屏幕矮到 320dp 以下（极罕见）时给内容区留一点保底高度 ——
     *   那种尺寸下弹窗本来就挤，但内容区绝不能量成 0（那就成了"空白弹窗"）。
     *
     * ★★本轮的两处加固（第 7 项"弹窗覆盖/显示不全"）：
     * ① 基准从 `resources.displayMetrics.heightPixels` 换成**播放页自己的高度**（[pageHeightPx]）——
     *    displayMetrics 给的是"整块屏幕"，分屏/小窗/折叠屏展开时它比本页真实可用高度大得多，
     *    按它算出来的上限会把弹窗顶到可视区之外（用户看到的就是"弹窗显示不全"）；
     * ② 发弹幕弹窗（[leaveRoomForIme] = true）再扣掉**输入法当前占用的高度**：
     *    横屏下软键盘能占掉 40% 屏高，不扣的话输入框那一行正好被键盘盖住，"看不见自己打的字"。
     */
    private fun dialogContentMaxHeightPx(leaveRoomForIme: Boolean = false): Int {
        val ime = if (leaveRoomForIme) imeInsetPx else 0
        // ★2026-09-26 用户反馈"线路弹窗的取消按钮怎么都被挡住"——根因就在这里：
        //   原来按 **pageHeightPx()（播放页高度≈整屏）** 减一点算上限，于是"内容 + 标题 + 按钮栏"
        //   必然超过屏幕，按钮栏被挤到屏幕外（不是按钮没放对位置）。
        //   现在按**真实屏幕高度**取一个保守百分比（62%），再加上对话框自身的 chrome 预留，
        //   保证"内容 + 标题 + 按钮栏"永远装得下；内容超了就滚动。
        val screen = resources.displayMetrics.heightPixels
        val base = minOf(pageHeightPx(), screen)
        val available = (base * 0.62f).toInt() - ime
        return (available - dpToPx(DIALOG_CHROME_DP)).coerceAtLeast(dpToPx(72))
    }

    /**
     * 内容区高度封顶的 ScrollView。
     *
     * 用自定义 onMeasure（AT_MOST 上限）而不是"show 之后再改 layoutParams"：
     * 后者会先按内容高度铺一次、再被夹回来，用户能看见弹窗**跳一下**；
     * 这里第一帧量出来就已经是夹过的尺寸。
     *
     * ★上限取"**框架给的**"和"**我们算的**"里更小的那个：
     *   `AlertDialogLayout.tryOnMeasure()`（appcompat 1.8.0 字节码 offset 184-275）是
     *   **先量按钮栏**，再用 `makeMeasureSpec(Math.max(0, heightSize - usedHeight), heightMode)`
     *   去量内容面板 —— 也就是框架自己就会给内容一个"扣掉标题和按钮之后"的上限，
     *   那个值比我们的经验值更准。我们只负责把"-1/-2 之外的真实像素"再夹一道，
     *   绝不能反过来把它放大（放大了就可能把按钮栏挤出屏幕）。
     */
    private class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val incomingMode = MeasureSpec.getMode(heightMeasureSpec)
            val incomingSize = MeasureSpec.getSize(heightMeasureSpec)
            val cap = if (incomingMode == MeasureSpec.UNSPECIFIED) {
                maxHeightPx
            } else {
                minOf(maxHeightPx, incomingSize)
            }
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST),
            )
        }
    }

    /**
     * 正常弹窗的统一构建口（清晰度 / 线路 / 发弹幕都用它 —— 直播设置弹窗本轮已删）。
     *
     * 为什么统一走 [MaterialAlertDialogBuilder]：
     * · 它就是**全 App 的弹窗观感**（`MainActivity.showNotificationPermissionTips()` 用的也是它，
     *   主题 `Theme.Bilimiao` 继承 `Theme.Material3.DayNight.NoActionBar`）——
     *   用户要的"MD 三 E / 系统自带那种弹窗"；
     * · 窗口几何由主题 + 系统算（居中、左右留白、最大宽度），**不再由我们按像素钉死**，
     *   于是竖屏/横屏/小屏/分屏都不会越界，也不需要转屏后重算（本轮删掉的那段全屏几何代码）；
     * · 长内容靠我们自己的 [MaxHeightScrollView] 封顶滚动，按钮在弹窗外面的按钮栏里，永远点得到。
     */
    private fun normalDialog(): MaterialAlertDialogBuilder = MaterialAlertDialogBuilder(this)

    // ★这里原来有 saveLiveBoolean / saveLiveInt / saveLiveDanmakuEnabled 三个"写直播设置"的小工具，
    //   它们唯一的调用方是已删掉的直播设置弹窗（见上面那段说明）。
    //   本页现在对 DataStore **只读不写**：设置统一在「设置 → 直播设置」页改，
    //   播放页只负责"读出来用"（[SettingPreferences.liveSettings] 内存快照）。
    //   底栏「弹幕」那个按钮是会话级临时开关，同样一行都不写 —— 写入点已经不在这条路上。

    /**
     * 气泡贴在**哪一侧**（用户规则："各自贴不挡手的那一侧"）。
     *
     * · 调音量（右半区按下）→ 气泡在**左**（[START]，手指在右边不挡）
     * · 调亮度（左半区按下）→ 气泡在**右**（[END]，手指在左边不挡）
     * 这正是 GSY 两份布局的区别（`video_volume_dialog.xml` 内容贴左 / `video_brightness.xml` 贴右），
     * 也是点播那套的表现；本页把它显式化成一个参数，两个气泡走**同一条定位代码**
     * （外层贴页面的哪一边 + 内容贴气泡的哪一头，都用它，见 [applyHudGeometry] 与 [hugSide]）。
     */
    private enum class HudSide { START, END }

    /**
     * 手势气泡（音量 / 亮度）在**页内**的位置 —— 两个气泡同一套规则，只差一个 [side]。
     *
     * ```
     * 横向：贴"重力那一侧"的页边（音量 START=左 / 亮度 END=右），内容由 [hugSide] 决定贴哪头
     * 纵向：气泡**中心**落在"视频区"的中线上（横屏视频区=整页 → 与改动前逐字一致：屏幕正中）
     * ```
     * 尺寸只有 90~100dp，父容器又是播放页自己 → **物理上不可能跑出屏幕**。
     *
     * ## ★竖屏根因（用户实测："亮度超出我的屏幕外、音量没有靠边"，横屏却是对的）
     *
     * ### 上一版是怎么写的（错在哪：两套坐标系混用）
     * 气泡住在**独立的 Dialog 悬浮窗**里，位置这样算：
     * ```kotlin
     * val location = IntArray(2); rootLayout.getLocationOnScreen(location)   // ① 页面的"屏幕坐标"
     * val screen = screenBoundsPx()                                           //    屏幕矩形(0,0,W,H)
     * lp.gravity = Gravity.TOP or START/END                                   // ② 窗口锚在"系统给的框"的边上
     * lp.x = left - screen.left          // 或 screen.right - right           //    偏移量却按①算
     * lp.y = top  - screen.top
     * lp.width = WRAP_CONTENT; lp.height = pageHeight                         // ③ 窗口和整页一样高
     * ```
     * ①和②**不是同一个坐标系**：`getLocationOnScreen`/`currentWindowMetrics` 给的是**显示区**坐标，
     * 而 `WindowManager.LayoutParams.x/y` 是**相对于系统给这个窗口分配的父框（parent frame）**的偏移
     * —— 一个没有 `FLAG_LAYOUT_IN_SCREEN`、又不是全屏的悬浮窗，父框是"显示区扣掉系统栏/挖孔安全区"
     * 之后的那块（沉浸式、挖孔、手势导航、分屏、各家 ROM 的处理都会让这两者不同）。
     * 于是气泡的最终位置 = **父框的边** + 代码以为的偏移 —— 差的就是父框原点与显示区原点的差值，
     * 而这个差值**没人验证过**，代码注释里直接写着"页面矩形 ∩ 屏幕矩形"就当它成立了。
     *
     * ### 为什么症状长这样（"亮度出屏、音量不贴边"）—— 一个偏移、两种表现
     * 两个气泡锚在**相反的两条边**上：音量锚 `START`（父框左边）、亮度锚 `END`（父框右边）。
     * 父框相对显示区整体**右移/右扩**时：
     * · 音量：左边 = 父框左边 → 比显示区左边**往里缩**，看起来"没有靠边"；
     * · 亮度：右边 = 父框右边 → **超出显示区右边**，看起来"跑到屏幕外"。
     * 这正是用户描述的两个症状 —— 它们不是两个 bug，是**同一个坐标偏移**。
     * 反证也在同一份实测里：上一轮"整页宽 + x=页左边界"的旧写法在横屏下让亮度气泡被裁掉
     * （窗口右边缘 = x+width 越过了显示区），说明**这台机器上父框本来就不等于显示区**；
     * 这一轮改成"锚父框的边"之后横屏好了（父框右边≈显示区右边 + 布局自带留白 = 用户说的
     * "距离右边一点距离"），竖屏却暴露了另一个方向上的同一个差值。
     * ③ 也在放大这个问题：`lp.height = pageHeight`（竖屏 2800px = 整块显示区高）让窗口比父框还高，
     * 纵向位置同样变成"父框说了算"，气泡的垂直位置也跟着父框走。
     * ★结论：**只要位置由悬浮窗的父框决定，就永远要跟 ROM/系统栏/挖孔/分屏对账**，
     *   而这三轮的经验是"每次对账都对不齐"。
     *
     * ### 修法：把气泡搬进播放页自己的视图树（[hudLayer]），坐标系只剩一套
     * 1. 气泡是 `rootLayout`（= 用户看到的那一页）的子 View → 位置用**页内坐标**表达，
     *    和"页面在屏幕上的位置""系统给悬浮窗的框"全部无关；
     * 2. 只有一个祖先（页面）在裁剪它 → **不可能跑到屏幕外**（页面就是屏幕）；
     * 3. 横竖屏共用这一条代码路径、同一个边距（[HUD_EDGE_MARGIN_DP]=0）→ 横屏与已验证的那版
     *    逐像素一致，不存在"修竖屏把横屏带坏"的通道；
     * 4. 纵向改成跟着**视频区**走（[videoBandCenterY]）：竖屏视频压成一条带之后，
     *    气泡正好落在画面中间，而不是"整页正中"（那已经在列表区里了）。
     *
     * ### 保留下来的东西（复用点没丢）
     * 布局仍是 GSY 那两份 XML、图标仍是布局自带的、进度条仍是点播同一支
     * [PlayerViewDrawable.videoVolumeProgress]；淡入淡出由窗口动画换成等价的页内 alpha 动画
     * （[HUD_FADE_MS]，同样挂在抬手即收的路径上）。唯一变的是"宿主"：从窗口变成页内一层 View。
     *
     * @param bubble [GestureHud] 建出来的气泡 View（已经在 [hudLayer] 里）
     */
    private fun applyHudGeometry(bubble: View, side: HudSide) {
        if (!::rootLayout.isInitialized || !::hudLayer.isInitialized) return
        val pageHeight = rootLayout.height.takeIf { it > 0 } ?: return
        val lp = bubble.layoutParams as? FrameLayout.LayoutParams ?: return
        // ① 贴哪一侧：音量 START(左)、亮度 END(右)。
        //   ★写成两步而不是 `Gravity.CENTER_VERTICAL or if (...) ... else ...`：
        //     infix 调用的右操作数不吃 if 表达式，拆开就没有歧义了
        val sideGravity = if (side == HudSide.START) Gravity.START else Gravity.END
        val targetGravity = Gravity.CENTER_VERTICAL or sideGravity
        // ★边距写 leftMargin/rightMargin 而不是 marginStart/marginEnd：
        //   FrameLayout 摆子 View 时读的就是左右 margin（`case Gravity.RIGHT: parentRight - width - lp.rightMargin`），
        //   start/end 那份要靠 MarginLayoutParams.resolveLayoutDirection 再翻译一次，
        //   而这里是播放页（LTR）里自己 setLayoutParams，少一层翻译就少一个"什么时候翻译"的疑问。
        val targetLeft = if (side == HudSide.START) dpToPx(HUD_EDGE_MARGIN_DP) else 0
        val targetRight = if (side == HudSide.END) dpToPx(HUD_EDGE_MARGIN_DP) else 0
        // ② 纵向：把"居中"从整页挪到视频区。
        //   FrameLayout 的 CENTER_VERTICAL 摆法是
        //       `top = (页高 - 气泡高)/2 + topMargin - bottomMargin`
        //   代入"气泡中心 = 视频区中线"化简，气泡高度那一项正好被消掉：
        //       `topMargin - bottomMargin = 视频区中线 - 页高/2`
        //   —— 所以不需要知道气泡自己的高度（那是测量之后才有的事）。
        //   两项里只留正的那一个；横屏（视频区中线 = 页高/2）两个都是 0，与改动前完全一致。
        val offset = (videoBandCenterY() - pageHeight / 2)
            .coerceIn(-pageHeight / 2, pageHeight / 2)
        val targetTop = offset.coerceAtLeast(0)
        val targetBottom = (-offset).coerceAtLeast(0)
        // ③ ★第四批加固（防"布局自激"）：**算出来和现在一样就一个字节都不写**。
        //   为什么必须这样：`bubble.layoutParams = lp` 会 requestLayout，而
        //   [measurePortraitStage] 现在挂在"视频带 layout 变化"的监听上 ——
        //   若这里每次都无条件写一遍，就会出现"改 → 布局 → 再改"的无限回路
        //   （气泡可见的那几秒会一直重排）。写之前比一下，回路当场断掉。
        if (lp.gravity == targetGravity &&
            lp.leftMargin == targetLeft &&
            lp.rightMargin == targetRight &&
            lp.topMargin == targetTop &&
            lp.bottomMargin == targetBottom
        ) {
            return
        }
        lp.gravity = targetGravity
        lp.leftMargin = targetLeft
        lp.rightMargin = targetRight
        lp.topMargin = targetTop
        lp.bottomMargin = targetBottom
        bubble.layoutParams = lp
    }

    /**
     * 视频区（画面所在那条带子）在页内的**垂直中线**（px）。
     *
     * 取值来源是 [videoContainer] 自己的矩形：它是 `rootLayout` 的直接子 View，
     * `top/height` 本来就是页内坐标，不需要任何屏幕坐标换算 —— 这正是本轮把气泡搬进页面之后
     * 才敢用的一条捷径（悬浮窗时代拿不到这个值，只能拿"整页高"凑）。
     * 量不到（首帧之前、听音频模式画面 GONE）时退回整页中线，保证任何时刻都有个合法值。
     */
    private fun videoBandCenterY(): Int {
        val pageHeight = rootLayout.height
        if (::videoContainer.isInitialized &&
            videoContainer.visibility == View.VISIBLE &&
            videoContainer.height > 0
        ) {
            return videoContainer.top + videoContainer.height / 2
        }
        return pageHeight / 2
    }

    /**
     * 把 GSY 那份气泡布局包一层"贴边容器"：**贴哪一侧由一个显式参数决定**。
     *
     * ★为什么要包这一层：直接 `setContentView(原生布局)` 的话，"贴左还是贴右"完全由两份 GSY 布局
     *   **各自的 gravity** 决定 —— 音量那份是 `center_vertical`（贴左），亮度那份是 `center|end`（贴右）。
     *   两套规则、两处生效点，改一处就会漏另一处。
     *   现在统一成：外层 [FrameLayout] 包住内容，内层内容的 `layout_gravity` 由 [side] 决定，
     *   并且内层宽度强制 WRAP_CONTENT（否则它自己那份 gravity 又会生效，等于两套规则叠加）。
     *   ★本轮宿主从"悬浮窗"换成"页内 View"之后这一层照样要用：外层宽度 = 气泡宽度（WRAP_CONTENT），
     *     真正"贴页面的哪一边"由 [applyHudGeometry] 给外层的 gravity 决定 —— 两件事各管一头，不重叠。
     */
    private fun hugSide(content: View, side: HudSide): View {
        val horizontal = if (side == HudSide.START) Gravity.START else Gravity.END
        return FrameLayout(this).apply {
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER_VERTICAL or horizontal,
                ),
            )
        }
    }

    /** 主题里的点击反馈背景（`?android:attr/selectableItemBackground`），取不到返回 0 */
    private fun selectableItemBackgroundRes(): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            value.resourceId
        } else {
            0
        }
    }

    /**
     * 主题里的**无边界**点击反馈背景（`?android:attr/selectableItemBackgroundBorderless`），取不到返回 0。
     *
     * ★顶栏那颗返回图标用的就是它：「其他页」AppBar 的返回/菜单图标走的是同一个属性
     *   （`AppBarVerticalUi.kt:218`、`AppBarHorizontalUi.kt:35` 都 `setBackgroundResource` 这个），
     *   顶栏返回跟着用同款，点击反馈才和 App 其它页一致（图标周围一圈水波纹，而不是一个方块底色）。
     */
    private fun selectableItemBackgroundBorderlessRes(): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)) {
            value.resourceId
        } else {
            0
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 主题配色（用户反馈："直播区域的所有东西，点缀色、主题色、副色什么的，没有跟随我们的那些主题"）
    //
    // ★病根：原来页面里几乎所有颜色都是**写死**的两类 ——
    //   · 中性色：底栏/顶栏黑色蒙层 `argb(140,0,0,0)`、按钮 `argb(90,255,255,255)`、白字；
    //   · 强调色：走 [accentColor]，而它读的是**主题资源** `colorPrimary`
    //     （`Theme.Bilimiao` 里钉死 `@color/theme_color_primary` = #fb7299）。
    //   问题出在后者：本 App 的主题色是**运行时可改**的 ——「设置 → 主题设置」把用户选的色写进
    //   DataStore（键 `theme_color` / 自定义主题的 `theme_custom_primary|secondary|tertiary`），
    //   再由 `MainActivity.applyAppBarTheme()` / `VideoPlayerActivity` 走
    //   `AppStore.theme.color → ThemeDelegate.setThemeColor()` **发给控件**。
    //   也就是说：**主题资源里的 colorPrimary 永远是最初那个粉色**，用户换成蓝色主题后，
    //   点播跟着变了、直播页的强调色还是粉的 —— 用户看到的就是"没跟随主题"。
    //
    // ★修法：按**同一份数据源**取色（`SettingPreferences` 的三色键，与 `AppStore` 里
    //   `ThemeSettingState` 的组装逐字对应），三色分别落到页面不同的位置上：
    //   | 变量 | 来源键 | 用在哪 |
    //   |---|---|---|
    //   | [themePrimary] | `theme_color`（自定义主题时=自定义主色） | 按钮态/弹窗强调/进度条/当前项 |
    //   | [themeSecondary] | `theme_custom_secondary`（未自定义=主色） | 顶栏状态文字（"状态条"） |
    //   | [themeTertiary] | `theme_custom_tertiary`（未自定义=主色） | 音频舞台封面占位底/说明高亮 |
    //   ★读的是 [SettingPreferences.cachedPreferencesOrNull] 那份**主线程内存快照**
    //     （和 `liveSettings()` 同一个口子），不阻塞、不联网；快照还没热时逐级回退：
    //     主题资源 colorPrimary → 白色。宁可朴素，也不能因为取色失败让整页配色崩掉。
    //   ★本页是**独立 Activity**、没有 Store/DI（那要 `store.loadStoreModules()` 一整套），
    //     所以直接读键 —— 这也正是工程里"设置键只有一处定义、谁都能读"的用法。
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 重新解析主题三色（[onCreate] 建 UI 之前 + [onConfigurationChanged] 深浅色变化时各一次）。
     * 只改字段，不动控件 —— 刷控件是 [applyThemeColors] 的事。
     */
    private fun resolveThemeColors() {
        val prefs = SettingPreferences.cachedPreferencesOrNull()
        val themeType = prefs?.get(SettingPreferences.ThemeType) ?: SettingConstants.THEME_TYPE_DEFAULT
        val storedPrimary = prefs?.get(SettingPreferences.ThemeColor)
        // 主色：Material You 动态取色时跟系统色（与 `AppStore.materialYouColor` 同一支），
        // 否则就是用户存下的 `theme_color`（自定义主题也写在这同一个键里，见 setCustomThemeColor）。
        val primary = when {
            themeType == SettingConstants.THEME_TYPE_DYNAMIC_COLOR -> materialYouColor()
            storedPrimary != null -> storedPrimary.toInt()
            else -> themePrimaryFromResources()
        }
        themePrimary = primary
        // 副色/点缀色：**只有"自定义主题"（type=2）才真的有这两支独立颜色**。
        // ★必须在类型上判一下：`theme_custom_secondary/tertiary` 是"自定义里存着的那一份"，
        //   用户从自定义切回预设色之后这两个键**仍然留着旧值**（设置页刻意保留，方便再切回去）——
        //   只看"值不等于主色"就会把上次自定义的配色又涂回预设主题上。
        //   预设/动态主题下副色与点缀色一律跟随主色（与设置页"与主色相等表示跟随主色"同一语义）。
        val useCustomPalette = themeType == SettingConstants.THEME_TYPE_CUSTOM
        val customSecondary = prefs?.get(SettingPreferences.ThemeCustomSecondary)
        val customTertiary = prefs?.get(SettingPreferences.ThemeCustomTertiary)
        themeSecondary = if (useCustomPalette) {
            customSecondary?.takeIf { it != primary } ?: primary
        } else {
            primary
        }
        themeTertiary = if (useCustomPalette) {
            customTertiary?.takeIf { it != primary } ?: primary
        } else {
            primary
        }
    }

    /** 动态取色（Material You）：与 `AppStore.materialYouColor` 用的是同一支系统色 */
    private fun materialYouColor(): Int = runCatching {
        ContextCompat.getColor(this, android.R.color.system_primary_light)
    }.getOrDefault(themePrimaryFromResources())

    /** 兜底主色：主题资源里的 `colorPrimary`（老写法就是取这个） */
    private fun themePrimaryFromResources(): Int = runCatching {
        val attrs = theme.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.colorPrimary))
        try {
            attrs.getColor(0, Color.WHITE)
        } finally {
            attrs.recycle()
        }
    }.getOrDefault(Color.WHITE)

    /**
     * 强调色（弹窗强调、按钮激活态、进度条、当前项高亮…）—— 一律走它，不要各写各的。
     * ★它现在返回的是**用户当前主题色**（见上面的取色说明），不再是资源里那个写死的粉色。
     */
    private fun accentColor(): Int = themePrimary

    /** 副色（顶栏"状态条"文字用） */
    private fun secondaryColor(): Int = themeSecondary

    /** 点缀色（音频舞台封面占位/说明文字用） */
    private fun tertiaryColor(): Int = themeTertiary

    /**
     * 中性蒙层色（顶栏/底栏那种半透明底）。
     *
     * ★为什么这里仍然用"黑底"而不是主题 surface：这是**视频播放页**的既有约定 ——
     *   点播那套（GSY 的 `layout_video_control` / 我们的原生控制条）在浅色主题下也是黑蒙层，
     *   因为蒙层压在**画面**上，浅色蒙层会让画面发灰、白字也失去对比。
     *   所以"跟随主题"落在**点缀色**上（按钮态、状态文字、进度条、弹窗强调），蒙层保持中性。
     *   唯一跟随深浅色的是透明度：深色主题下可以更实一点，浅色主题下更透一点（避免画面被压死）。
     */
    private fun scrimColor(): Int =
        if (isAppDarkTheme()) Color.argb(150, 0, 0, 0) else Color.argb(120, 0, 0, 0)

    /**
     * 当前是不是深色主题：与 `MainActivity.applyAppBarTheme()` 的判定逐字一致
     * （`darkMode`：0=跟随系统、1=浅色、2=深色）。
     */
    private fun isAppDarkTheme(): Boolean {
        val mode = SettingPreferences.cachedPreferencesOrNull()
            ?.get(SettingPreferences.ThemeDarkMode) ?: 0
        return when (mode) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    /**
     * 把主题色刷到**已经建好的控件**上（转屏/深浅色切换后重算一次就够了，不重建页面）。
     *
     * 覆盖范围 = 用户点名的几处："弹窗强调色、按钮态、状态条、图标"：
     * · 按钮态 → 底栏每一颗按钮的底色（[applyBottomButtonStyle]）；
     * · 状态条 → 顶栏那行状态文字（副色）+ 缓冲圈（主色）；
     * · 弹窗强调色 → [LiveListDialog] 每次 show 时现取 [accentColor]，
     *   本来就是主题色（把 accentColor 的数据源修对了之后，它们自动跟着变）。
     * ★第五批删掉了原来"图标 → 音频舞台封面占位/说明文字（点缀色）"那一档：音频舞台没了。
     *   [tertiaryColor] 仍然保留（设置里的自定义主题三色语义未变，只是本页不再有消费点）。
     */
    private fun applyThemeColors() {
        if (!::rootLayout.isInitialized) return
        topBar.setBackgroundColor(scrimColor())
        bottomBar.setBackgroundColor(scrimColor())
        statusText.setTextColor(secondaryColor())
        titleText.setTextColor(Color.WHITE)
        // ★顶栏返回改成"只有图标"（复用点播播放器的 `ic_arrow_back_white_24dp`）——
        //   它**不再有主题色药丸底**（原来那颗「← 返回」文字按钮才有），所以这里只把**点击反馈底**
        //   按当前主题重新解一次（深浅色切换后 ripple 颜色跟着换）。图标本身是写死的白色，
        //   压在黑色蒙层上深浅色下都看得清，不需要跟着主题刷（选型理由见 [buildUi] 顶栏那一段）。
        //   ★第七批那颗「顶栏画中画图标」已整体删除，顶栏图标只剩返回这一颗（见 [buildUi]）。
        selectableItemBackgroundBorderlessRes().takeIf { it != 0 }?.let { ripple ->
            backButton.setBackgroundResource(ripple)
        }
        progressBar.indeterminateTintList = ColorStateList.valueOf(accentColor())
        orderedBottomButtons().forEach { applyBottomButtonStyle(it) }
        // ★第七批新增：输入条那一套（输入框底色/转圈颜色/失败提示色）也跟着主题走 ——
        //   与"按钮态跟随主题"是同一件事，只是它们不属于 [orderedBottomButtons]。
        if (::danmakuInput.isInitialized) {
            // ★关闭弹幕的房间**不要这层底**（换主题也不能把它画回来）—— 与 [applyDanmakuInputClosedUi] 同源
            danmakuInput.background = if (roomDanmakuClosed) null else danmakuInputBackground()
        }
        if (::danmakuSendProgress.isInitialized) {
            danmakuSendProgress.indeterminateTintList = ColorStateList.valueOf(accentColor())
        }
        if (::danmakuInputError.isInitialized) {
            danmakuInputError.setTextColor(errorTextColor())
        }
        // ★颜色变了顺带把字号档位校准一次（[applyBottomButtonStyle] 会把字号写回上限档，
        //   这里立刻按真实格宽重算 —— 不校准的话，下一次布局之前那一帧字号是"上限档"，
        //   在窄格子里会闪一下省略号）
        applyBottomBarTextSizes(bottomBarAvailableWidthPx())
        updatePlayPauseButton()
    }

    /**
     * 选择列表弹窗（清晰度 / 线路共用）—— **正常尺寸的居中弹窗**。
     *
     * ★这一版和上一版的区别（用户实测反馈驱动的返工）：
     *   上一版按需求"全屏弹窗 + 旋转适配"做成了**自建全屏 Dialog**（自己 setContentView、
     *   自己按像素 `setLayout(播放页宽, 播放页高)`、转屏后在 [onConfigurationChanged] 里重算）。
     *   实测结果是"**弹窗超出了我的屏幕，我点不了**"—— 自建几何那条路只要有一处没算对
     *   （内容比屏幕高、旋转与重算之间有时间差、沉浸式下坐标口径不同），
     *   按钮就落在屏幕外。现在改回**系统正常弹窗**：几何交给主题与系统，
     *   内容区自己封顶可滚，按钮永远在屏幕内（详见 [show] 与 [dialogContentMaxHeightPx]）。
     */
    /**
     * 选择弹窗里的一行。
     *
     * ★为什么放在 Activity 类体层级、而不是塞在 [LiveListDialog] 里面（2026-09-26 编译报错修的）：
     *   Kotlin 不允许在 `inner class` 内部再声明嵌套类（"'Class' is prohibited here"），
     *   放外面两者都能看见（inner class 能访问外部类的嵌套类）。
     *
     * @param current 是否是"当前正在生效"的那一项（打勾 + 主题色高亮）
     * @param note 次要说明（"当前正在播放" / "已请求 · 当前不可用"）
     */
    private class Entry(val label: String, val note: String?, val current: Boolean)

    /**
     * 弹窗里的**一段**（★第七批新增：一个弹窗装"清晰度"+"线路"两段）。
     *
     * ★与 [Entry] 一样必须放在 Activity 类体层级：Kotlin 不允许在 `inner class` 里再声明嵌套类
     *   （"'Class' is prohibited here"，2026-09-26 编译报错修过一次，见 [Entry] 的注释）。
     *
     * @param title 段的 Tab 标题（"清晰度" / "线路"）
     * @param subheading 段内的"当前是什么"那行（居中、主题色），没有就不占位置
     * @param entries 段内的可选项（打勾/高亮/说明行的规则都在 [LiveListDialog.show] 里）
     */
    private class Segment(
        val title: String,
        val subheading: String?,
        val entries: List<Entry>,
    )

    /**
     * 选择列表弹窗（★第七批：**清晰度 + 线路共用同一个弹窗**）—— 正常尺寸的居中弹窗。
     *
     * ★这一版和上一版的区别（用户实测反馈驱动的返工）：
     *   上一版按需求"全屏弹窗 + 旋转适配"做成了**自建全屏 Dialog**（自己 setContentView、
     *   自己按像素 `setLayout(播放页宽, 播放页高)`、转屏后在 [onConfigurationChanged] 里重算）。
     *   实测结果是"**弹窗超出了我的屏幕，我点不了**"—— 自建几何那条路只要有一处没算对
     *   （内容比屏幕高、旋转与重算之间有时间差、沉浸式下坐标口径不同），
     *   按钮就落在屏幕外。现在改回**系统正常弹窗**：几何交给主题与系统，
     *   内容区自己封顶可滚，按钮永远在屏幕内（详见 [show] 与 [dialogContentMaxHeightPx]）。
     *
     * ★第七批的两处结构性变化：
     * 1. **两段（TabLayout）**：`segments.size > 1` 时在内容区顶部摆一条 Tab 栏（Material 的
     *    [TabLayout]，`MODE_FIXED` 两等分），切 Tab 只换下面那块列表的内容 ——
     *    两个"当前档打勾 / 已请求不可用 / 线路可点选"的行为各自原样保留；
     * 2. **一个可选的中性按钮**（[neutralLabel] / [onNeutral]）：现在只用来放「重新取流」。
     *    与正文里的行一样**手动接点击**（`setNeutralButton(label, null)` + `setOnShowListener` 里
     *    覆盖），因为默认的按钮回调点完会**无条件关弹窗**，而我们要自己决定关不关
     *    （这里选择：关掉 —— 用户点它就是想"重来一遍"，关掉正好能看顶栏状态）。
     *    ★**仍然没有「取消」按钮**（用户 2026-09-26 明确要求："我直接不要那个取消按钮了"）：
     *    关闭方式齐全 —— 点任意条目即切换并关闭、点弹窗外关闭、返回键关闭。
     */
    private inner class LiveListDialog(
        private val heading: String,
        private val segments: List<Segment>,
        private val onPick: (segmentIndex: Int, index: Int) -> Unit,
        private val neutralLabel: String? = null,
        private val onNeutral: (() -> Unit)? = null,
    ) {
        var dialog: AlertDialog? = null
            private set

        val isShowing: Boolean get() = dialog?.isShowing == true

        /**
         * 弹出选择列表 —— **正常弹窗**（居中、定宽、内容超高就在内部滚）。
         *
         * 行样式保留原样（打勾 + 主题色 = 当前项、副标题写"当前是什么"），
         * 只是配色改成**跟随弹窗主题**（不再写死白色/黑色）：
         * 弹窗是浅色还是深色由 `Theme.Bilimiao` 的 DayNight 决定，写死颜色会在浅色主题下看不见。
         *
         * ★★第四批第 6 条：**里面的文字改成居中**。用户原话："那个画质选质，还有源选质的那个弹窗，
         *   里面的文字没有居中，它靠边上去了。你看我软件大概的设置都是怎么做的？"
         *   病根是[自建内容的弹窗]没有 Material 正文那 24dp 内边距：这些行是我们自己 addView 进去的，
         *   只给了 4dp 左右内边距 → 文字几乎贴到弹窗边缘，而标题（`setTitle`，走 Material 标题样式）
         *   自带内边距，两者一比就显得"靠边、不居中"。
         *   修法就是用户说的那个词 —— **居中**：
         *   · 副标题（"当前：超清（qn 400）"）→ `gravity = CENTER`；
         *   · 每一行选项（含第二行的说明文字）→ `gravity = CENTER`，
         *     并且**去掉原来靠 5 个空格手工缩进的对齐**（居中之后那个缩进只会让第二行歪掉）。
         *   ★标题仍走 `setTitle(heading)`：那是全 App 常规 `MaterialAlertDialog` 的观感
         *     （`MainActivity.showNotificationPermissionTips()` 同样是它），标题自带内边距、并不"靠边"，
         *     与"正文居中"并不冲突 —— 用户抱怨的是正文那几行。
         *
         * ★★第七批的高度账（用户点名"弹窗内容高度必须按真实屏幕高度封顶 + 可滚，
         *   [dialogContentMaxHeightPx] 已按屏幕 62% 改好，别改回去"）：
         * ```
         * 列表上限 = dialogContentMaxHeightPx() - DIALOG_TAB_STRIP_DP   （铺开 Tab 栏时）
         * ```
         * 不扣这一条的话，"Tab + 列表 + 标题 + 按钮栏"会比 62% 高一截 —— 那正是"按钮被顶出屏幕"的病根。
         */
        fun show() {
            if (isFinishing || isDestroyed) return
            val accent = accentColor()
            val textColor = dialogTextColor()

            // 列表容器：切 Tab 只重建**它**的内容，弹窗本身不动（Tab 栏也就不会闪）
            val list = LinearLayout(this@LivePlayerActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            fun renderSegment(index: Int) {
                list.removeAllViews()
                val segment = segments.getOrNull(index) ?: return
                segment.subheading?.let { text ->
                    list.addView(
                        TextView(this@LivePlayerActivity).apply {
                            this.text = text
                            setTextColor(accent)
                            textSize = 13f
                            // ★第四批第 6 条：副标题居中（和下面的选项行同一套对齐）
                            gravity = Gravity.CENTER
                        },
                        matchWrap(topMarginDp = 2, bottomMarginDp = 6),
                    )
                }
                segment.entries.forEachIndexed { entryIndex, entry ->
                    val row = TextView(this@LivePlayerActivity).apply {
                        text = buildString {
                            // 打勾 + 主题色 = "就是这一档"，不需要用户自己去比对 qn 数字
                            append(if (entry.current) "✓ " else "")
                            append(entry.label)
                            // ★说明文字另起一行、**不再手工缩进**：整行居中之后缩进会把这一行带歪
                            entry.note?.let { append("\n$it") }
                        }
                        textSize = 15f
                        setTextColor(if (entry.current) accent else textColor)
                        // ★第四批第 6 条：整行居中（多行时每一行各自居中）
                        gravity = Gravity.CENTER
                        setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
                        isClickable = true
                        selectableItemBackgroundRes().takeIf { it != 0 }?.let { setBackgroundResource(it) }
                        setOnClickListener {
                            dismiss()
                            onPick(index, entryIndex)
                        }
                    }
                    list.addView(
                        row,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
            }
            renderSegment(0)

            // 内容区封顶 + 可滚：选项再多也不会把弹窗顶出屏幕（按钮在系统的按钮栏里，不受影响）
            val twoSegments = segments.size > 1
            val listCap = if (twoSegments) {
                (dialogContentMaxHeightPx() - dpToPx(DIALOG_TAB_STRIP_DP)).coerceAtLeast(dpToPx(72))
            } else {
                dialogContentMaxHeightPx()
            }
            val scroller = MaxHeightScrollView(this@LivePlayerActivity, listCap).apply {
                addView(
                    list,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }

            // Tab 栏放**滚动容器外面**：滚列表时 Tab 不该跟着滚走（否则切段要先滚回顶部）
            val content = LinearLayout(this@LivePlayerActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            if (twoSegments) {
                val tabs = TabLayout(this@LivePlayerActivity).apply {
                    tabMode = TabLayout.MODE_FIXED
                    tabGravity = TabLayout.GRAVITY_FILL
                    segments.forEach { segment -> addTab(newTab().setText(segment.title)) }
                    addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                        override fun onTabSelected(tab: TabLayout.Tab) {
                            renderSegment(tab.position)
                        }

                        override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                        override fun onTabReselected(tab: TabLayout.Tab) = Unit
                    })
                }
                content.addView(
                    tabs,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            content.addView(
                scroller,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            val builder = normalDialog()
                .setTitle(heading)
                .setView(content)
            // ★中性按钮（「重新取流」）走"手动接"而不是直接给回调：
            //   默认回调点完会**无条件关弹窗**，而"关不关"该由调用方决定（见类 KDoc）。
            //   先落到局部 val 上，后面的匿名监听器里就是普通局部变量，不依赖对成员属性的智能转换。
            val neutral = neutralLabel
            val neutralAction = onNeutral
            if (neutral != null && neutralAction != null) {
                builder.setNeutralButton(neutral, null)
            }
            val d = builder.create()
            d.setOnDismissListener {
                if (dialog === d) dialog = null
            }
            if (neutral != null && neutralAction != null) {
                d.setOnShowListener {
                    d.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                        dismiss()
                        neutralAction.invoke()
                    }
                }
            }
            dialog = d
            // ★这里**故意不做**任何 setLayout：弹窗尺寸交给主题与内容自己算（这正是"不越界"的保证）
            runCatching { d.show() }
        }

        fun dismiss() {
            dialog?.takeIf { it.isShowing }?.let { runCatching { it.dismiss() } }
        }

        private fun matchWrap(topMarginDp: Int = 0, bottomMarginDp: Int = 0): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dpToPx(topMarginDp)
                bottomMargin = dpToPx(bottomMarginDp)
            }
    }

    /**
     * 弹窗正文颜色：跟着**弹窗自己的主题**取 `colorOnSurface`（Material 3 的正文色）。
     * ★为什么不写死白色：`Theme.Bilimiao` 是 DayNight，浅色主题下白字白底 = 什么都看不见。
     * 取不到就退回系统主文字色，再取不到才用黑色。
     */
    private fun dialogTextColor(): Int = runCatching {
        val attrs = theme.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorOnSurface))
        try {
            attrs.getColor(0, Color.BLACK)
        } finally {
            attrs.recycle()
        }
    }.getOrDefault(Color.BLACK)

    /**
     * 输入条失败提示行（[danmakuInputError]）的文字颜色 —— ★第七批新增。
     *
     * 取 Material 主题的 `colorError`（深浅色主题各有一份，浅色下不会看不见）；
     * 取不到（老主题/属性缺失）时退回一个压在黑蒙层上也清楚的浅红。
     * ★为什么不用主题资源里的写死色：本页的主题色是**运行时可改**的（见 [resolveThemeColors] 那一段），
     *   而"错误提示"这件事要的是"一眼看出这是错误"，不该跟着用户的主题色走。
     */
    private fun errorTextColor(): Int =
        // ★不用 `com.google.android.material.R.attr.colorError`：本工程依赖里解析不到该属性
        //   （2026-09-26 全量编译实测 Unresolved reference 'colorError'）。
        //   且按上面注释的理由，错误色本来就不该跟随用户主题色 —— 固定醒目红更符合意图。
        DEFAULT_ERROR_TEXT_COLOR

    /**
     * 手势反馈提示（音量 / 亮度）—— **直接复用点播那套资源**（用户实测反馈：位置不对、样式太差）。
     *
     * ## 复用对象（点播链路，只读参考）
     * 点播的音量/亮度气泡是 GSY 建的**两个铺满播放器**的 Dialog 窗口：
     * ```
     * StandardGSYVideoPlayer.showVolumeDialog()     → inflate R.layout.video_volume_dialog
     * StandardGSYVideoPlayer.showBrightnessDialog() → inflate R.layout.video_brightness
     * 窗口：new Dialog(ctx, R.style.video_style_dialog_progress)
     *       width/height = 播放器宽高、x/y = 播放器在屏幕上的位置
     *       音量 gravity = TOP|START(8388659)，亮度 gravity = TOP|END(8388661)
     *       flags = NOT_FOCUSABLE|NOT_TOUCHABLE|NOT_TOUCH_MODAL（javap 反编译
     *       gsyvideoplayer-java 13.2.1 字节码逐条核对过，见交付报告的证据表）
     * 进度条：GSY 默认用 @drawable/video_volume_progress_bg，
     *        本工程点播把它换成了 PlayerViewDrawable.videoVolumeProgress(context, 主题色)
     *        （widget/player/DanmakuVideoPlayer.kt:3296 themeProgressBar() / :3454 setDialogVolumeProgressBar()）
     * 收起：GSYVideoControlView.touchSurfaceUp() 里 dismiss 这两个弹窗 —— **抬手即收**
     * ```
     * 两条 GSY 布局的差异**只有位置**（音量那份内容贴左、亮度那份内容贴右），
     * 也正是用户点名的那条规则：
     * | 手势（哪半区按下） | 气泡贴哪边 | 用户看到的效果 |
     * |---|---|---|
     * | 右半区 → 音量 | **左**（[HudSide.START]） | 气泡在**左**半区，手指在右边不挡视线 |
     * | 左半区 → 亮度 | **右**（[HudSide.END]） | 气泡在**右**半区，手指在左边不挡视线 |
     * "贴哪边"**不由布局自己的 gravity 决定**（那是两套规则、两处生效点），
     * 而是由 [hugSide] 用同一个参数统一表达。
     *
     * ## 复用到了什么程度（不是"照着抄"，而是"用同一份资源"）
     * 本工程 `gradle.properties:23` 是 `android.nonTransitiveRClass=false`，GSY 的布局/图标/样式
     * 在 app 模块里都能直接引用；而 `widget/player/DanmakuVideoPlayer.kt:3180/3245` 早就这么用了
     * （`R.style.video_style_dialog_progress` 就是 GSY 的资源）。所以这里：
     * · **布局 = `inflate(R.layout.video_volume_dialog / video_brightness)`** —— 同一份 XML，
     *   连 40dp 胶囊、4dp×81dp 进度条、图标、左右对齐的 gravity 都是点播那一份，不存在抄歪的可能；
     * · **图标 = 布局里自带的** `video_volume_icon` / `video_brightness_6_white_36dp`（GSY 的 drawable）；
     * · **淡入淡出照样有**：窗口动画（`video_popup_toast_anim` = 平台 `fade_in`/`fade_out`）换成
     *   等价的页内 alpha 动画 [HUD_FADE_MS]，时长/插值器取同一量级（decelerate）；
     * · 音量/亮度是两个独立 View（和 GSY 一样各记各的缓存、互不干扰，懒加载、复用）；
     * · 显示时长照抄：滑动过程中一直显示，**抬手（ACTION_UP）立刻收**（见 TapCatcher.endDrag）。
     *
     * ## 与点播的两处**有意**不同
     * 1. **宿主不是窗口，而是页内一层 View**（[hudLayer]）——
     *    竖屏"亮度出屏/音量不贴边"的根因就是悬浮窗的坐标系（父框 ≠ 显示区），
     *    完整证据链与代价权衡见 [applyHudGeometry] 的 KDoc；
     * 2. **不吃触摸靠"子树里没有 clickable 控件"**，而不是靠窗口的 `FLAG_NOT_TOUCHABLE`
     *    （页内 View 没有窗口 flag 可用）—— 见 [addBubble] 的"硬约束"注释。
     *
     * ## 保留的自有实现：进度条 drawable 与页内几何
     * · 进度条 drawable 换成点播用的同一支 [PlayerViewDrawable.videoVolumeProgress]（照点播的做法），
     *   并清掉 M3 主题给的 `progressTint`（不清会被盖成纯色，那正是"看不出版本/进度"的原因）；
     * · 位置由 [applyHudGeometry] 给：贴"不挡手的那一侧"+ 纵向对齐视频区中线。
     */
    private inner class GestureHud {

        // ── 音量气泡（内容贴左，来自 GSY 的 video_volume_dialog）──────────────
        private var volumeBubble: View? = null
        private var volumeBar: ProgressBar? = null

        // ── 亮度气泡（内容贴右，来自 GSY 的 video_brightness）────────────────
        private var brightnessBubble: View? = null
        private var brightnessText: TextView? = null

        /**
         * **正在淡出**的那个气泡（null = 没有）。
         * ★用途只有一个：[showBubble] 据此判断"要不要先取消那一截淡出" ——
         *   拖动中的绝大多数调用因此不做任何动画操作（见 [showBubble] 的第 ① 支）。
         */
        private var fadingOut: View? = null

        /**
         * 右半区（音量）：`0..100`。
         * 点播的音量气泡**只有进度条 + 图标、没有百分比数字**（复用同一份布局，所以这里也没有）。
         */
        fun showVolume(percent: Int) {
            val bubble = ensureVolumeBubble() ?: return
            // ★第十三批：**先落值**（纯 UI，不碰几何、不碰动画、不碰系统），
            //   再算几何、再切显隐 —— 拖动中"数值跟手"这一条因此永远不会被别的活儿挡住。
            volumeBar?.progress = percent.coerceIn(0, 100)
            // 每次显示都重算几何：转屏后气泡还停在旧方向的尺寸/坐标是点播踩过的坑。
            // ★贴 START（左）——手指在右半区，提示落在左边不挡手（点播同款）
            //   （[applyHudGeometry] 里"值没变就一个字节不写"的短路保证它每帧只花几次比较）
            applyHudGeometry(bubble, HudSide.START)
            showBubble(bubble)
        }

        /** 左半区（亮度）：`0..100`（点播的亮度气泡带一个 "50%" 文本，同一份布局自带） */
        fun showBrightness(percent: Int) {
            val bubble = ensureBrightnessBubble() ?: return
            // ★第十三批：同上，先落值再算几何/切显隐
            brightnessText?.text = "${percent.coerceIn(0, 100)}%"
            // ★贴 END（右）——手指在左半区，提示落在右边不挡手。
            //   和音量气泡**同一行定位代码**，只差这一个参数（[applyHudGeometry]）
            applyHudGeometry(bubble, HudSide.END)
            showBubble(bubble)
        }

        /**
         * 收起两个气泡（抬手即收：见 [TapCatcher.endDrag]；退后台/进 PiP 也会调）。
         * ★只把 View 隐藏 + 淡出，不销毁视图：下一次滑动直接复用（和 GSY 缓存 Dialog 同构）。
         */
        fun hide() {
            volumeBubble?.let { hideBubble(it) }
            brightnessBubble?.let { hideBubble(it) }
        }

        /**
         * 转屏 / 分屏 / 竖屏版式变化后重算几何（[onConfigurationChanged] 与
         * [applyVideoStageLayout] 调）。
         * ★两个气泡各按**自己那一侧**重算 —— 不能像旧写法那样"一套参数套两个气泡"。
         */
        fun applyGeometry() {
            volumeBubble?.takeIf { it.visibility == View.VISIBLE }
                ?.let { applyHudGeometry(it, HudSide.START) }
            brightnessBubble?.takeIf { it.visibility == View.VISIBLE }
                ?.let { applyHudGeometry(it, HudSide.END) }
        }

        /** 音量气泡：充气点播那份布局 → 换成点播那支进度 drawable → 贴左放进气泡层 */
        private fun ensureVolumeBubble(): View? {
            volumeBubble?.let { return it }
            if (isFinishing || isDestroyed) return null
            val content = LayoutInflater.from(this@LivePlayerActivity)
                .inflate(GsyR.layout.video_volume_dialog, null)
            // ★泛型实参写 ProgressBar（不是 ProgressBar?）：findViewById 的类型参数上界是 View，
            //   平台签名返回的是可空平台类型，再用 ?. 兜住"布局里找不到"的情况
            volumeBar = content.findViewById<ProgressBar>(GsyR.id.volume_progressbar)?.apply {
                max = 100
                isIndeterminate = false
                // ★必须清掉主题给的 tint：Material3 的 ProgressBar 默认带 progressTint，
                //   会把我们这支"白底 + 主题色"的 drawable 整个盖成同一个纯色
                //   （GSY 在它自己的播放器主题里没这个问题，直播页的 Activity 主题是 M3，必须先清）
                progressTintList = null
                progressBackgroundTintList = null
                indeterminateTintList = null
                // 与点播同一支：白底 + 主题色、竖直 ClipDrawable
                progressDrawable = PlayerViewDrawable.videoVolumeProgress(this@LivePlayerActivity, accentColor())
            }
            // ★贴边容器：把"内容贴哪一侧"从布局自己的 gravity 里拿出来，交给 hugSide 统一表达
            return addBubble(hugSide(content, HudSide.START)).also { volumeBubble = it }
        }

        /** 亮度气泡：充气点播那份布局（图标 + 百分比文本都在里面）→ 同一个贴边容器 */
        private fun ensureBrightnessBubble(): View? {
            brightnessBubble?.let { return it }
            if (isFinishing || isDestroyed) return null
            val content = LayoutInflater.from(this@LivePlayerActivity)
                .inflate(GsyR.layout.video_brightness, null)
            brightnessText = content.findViewById(GsyR.id.app_video_brightness)
            // ★和音量气泡**同一个** hugSide 调用，只差 side 参数
            return addBubble(hugSide(content, HudSide.END)).also { brightnessBubble = it }
        }

        /**
         * 把气泡放进 [hudLayer]（懒加载一次，之后复用）。
         *
         * ## ★为什么不吃触摸是"硬约束"
         * 气泡层压在 [tapCatcher]（手势层）**之上**，而触摸是自顶向下派发的：
         * 只要这一层或它的任何子 View 是 clickable/longClickable，`ACTION_DOWN` 就会被它吃掉，
         * 正在进行的滑动手势当场断在半路（点播当年踩过同一个坑：所以它的气泡窗口带
         * `FLAG_NOT_TOUCHABLE`）。这里用**另一条等价的路**保证同一件事：
         * 整条子树里没有任何 clickable 的控件（两份 GSY 布局是纯展示布局：LinearLayout /
         * RelativeLayout / ProgressBar / ImageView / TextView 全都不 clickable），
         * 也没有设 OnClickListener → `onTouchEvent` 一律返回 false → 事件继续落到手势层。
         * ★谁要是给气泡加一个 `setOnClickListener`，手势立刻断在这里 —— 别加。
         *
         * 初始 `visibility = GONE` + `alpha = 0`：第一次显示走 [showBubble] 的淡入，
         * 与点播"每次滑出来都淡入一下"的观感一致。
         */
        private fun addBubble(view: View): View {
            view.visibility = View.GONE
            view.alpha = 0f
            hudLayer.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    // 初始值：贴左 + 垂直居中；真正的位置由 [applyHudGeometry] 在每次显示前给
                    Gravity.CENTER_VERTICAL or Gravity.START,
                ),
            )
            return view
        }

        /**
         * 显示气泡（滑动过程中每个 MOVE 都会被调到）。
         *
         * ## ★★第十三批：绝不在拖动中重启淡入动画（"跟手"的第二处修法）
         * 老写法每次调用都 `animate().cancel()` + `if (alpha < 1f) 再 start()` —— 于是**每个 MOVE
         * 都把淡入动画从头开始一次**：120Hz 下一个 180ms 的淡入被拆成上百次重启，
         * 气泡在整段拖动里始终只有半透明（`setAlpha` 反复回退），手感上就是"它怎么才出来"。
         * 现在分两种情况，**各自只做一件事**：
         * ```
         * 已经在显示（拖动中的绝大多数调用）→ 什么都不做；只有在"淡出被打断"时才把它补回全不透明
         * 从隐藏变可见（每次手势的第一次）  → 淡入一次（[HUD_FADE_MS]，观感与点播的窗口动画一致）
         * ```
         * ★为什么"已经显示"那一支要把 alpha 直接补成 1 而不是继续淡入：上一次手势的淡出
         *   （[hideBubble]）可能只走到一半就被新手势打断，此时 `cancel()` 之后动画不会再推进，
         *   不补这一下气泡会**永远停在半透明**。
         */
        private fun showBubble(view: View) {
            if (view.visibility == View.VISIBLE) {
                // ① "上一次手势的淡出还在跑" → 取消它并**直接补成全不透明**。
                //    取消不会执行 [hideBubble] 的 `withEndAction`（框架文档明确：被 cancel 的动画
                //    不跑 endAction），所以这一下不会把气泡按成 GONE。
                if (fadingOut === view) {
                    view.animate().cancel()
                    fadingOut = null
                }
                if (view.alpha < 1f) view.alpha = 1f
                return
            }
            // ② 从隐藏变可见（每次手势的第一次）：只播**一次**淡入
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate()
                .alpha(1f)
                .setDuration(HUD_FADE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        /**
         * 淡出并隐藏。
         * ★`withEndAction` 里再查一次 `alpha`：淡出途中用户又滑了一下（[showBubble] 会把动画取消
         *   并淡回来），这一帧回调不能把刚显示出来的气泡又按回 GONE。
         * ★[fadingOut] 是"正在淡出的那个气泡"：**只在**它命中的时候，[showBubble] 才需要取消动画 ——
         *   拖动中的绝大多数调用因此连一次 `animate().cancel()` 都不用做（那一次调用会去
         *   `MessageQueue.removeCallbacks` 扫一遍队列，120Hz 下没必要每个事件都扫）。
         */
        private fun hideBubble(view: View) {
            if (view.visibility != View.VISIBLE) return
            view.animate().cancel()
            fadingOut = view
            view.animate()
                .alpha(0f)
                .setDuration(HUD_FADE_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (fadingOut === view) fadingOut = null
                    if (view.alpha == 0f) view.visibility = View.GONE
                }
                .start()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 系统音量的后台写入器（★第十三批）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 手势音量的**唯一出口**，跑在自己的 `HandlerThread` 上 —— **UI 线程一个字节都不写**。
     *
     * ## ★★要修的到底是什么（用户实测："音量手势 UI 没有实时跟进，过了一两秒才显示"）
     *
     * 老写法在 `ACTION_MOVE` 里**同步**调 `AudioManager.setStreamVolume(...)`。这一句是
     * **跨进程调用**，链路每一步都能查到（证据在报告里逐行列出）：
     * ```
     * UI 线程：AudioManager.setStreamVolume
     *   → binder（同步，等对方返回才继续）→ system_server 的 IAudioService
     *   → AudioService.setStreamVolumeWithAttribution
     *       · checkNoteAppOp(...)                    // app-ops 检查
     *       · mSoundDoseHelper.…                     // 听力保护（Android 13+ 的安全音量）
     *       · sendVolumeUpdate → mVolumeController.postVolumeChanged  // 再 binder 给 SystemUI
     *       · MSG_PERSIST_VOLUME → mSettings.putSystemIntForUser(...) // 落一次系统设置（DB 写）
     * ```
     * MOVE 是按刷新率批量投递的（120Hz ⇒ 8.3ms 一个）。**只要一次写入比两个事件间隔还长，
     * 主线程就永远追不上手指**：输入队列越积越多，而气泡的刷新就在同一个 MOVE 处理里、
     * 还排在写入**后面** ⇒ 用户看到的正是"手势已经滑完了，气泡才慢吞吞地显示/更新"。
     *
     * ## 改法：写入搬出 UI 线程 + "最新档位必胜"
     * · [submit] 只做两件事：记下最新目标、往后台线程投一个任务 —— **立即返回**，不等 binder；
     * · 后台线程只写**"此刻最新的那一档"**：拖动过程中跨过的中间档位自然被合并掉
     *   （同一个 `pending` 被后写的覆盖），**抬手时的最后一档一定会落地**；
     * · 目标档位与上次真正下发的档位相同 → 一个字节都不写（系统音量是整数档，
     *   同档重复写对系统状态与听感都毫无影响，纯浪费）；
     * · 因此**不再需要**老的"16ms 节流 + 抬手补写"：节流是为了让 UI 线程少挨几次 binder，
     *   现在 UI 线程一次都不挨；抬手补写是为了补回被节流挡掉的档位，现在没有任何档位会被丢。
     *
     * ## 语义（与老写法**逐条对齐**，只是换了执行线程）
     * | 老写法 | 现在 |
     * |---|---|
     * | 档位没变不写 | 一样（[written] 去重） |
     * | 被挡下的档位抬手补写 | 不需要：最后一档从来不会被丢（[pending] 一直在） |
     * | `FLAG_SHOW_UI = 0`（不弹系统音量面板） | 一样（第三参恒为 0） |
     * | `STREAM_MUSIC` | 一样 |
     *
     * ## 量控（只为诊断，不参与行为）
     * [writeCount] / [writeTotalMs] / [writeMaxMs] 累计"真正下发了多少次、各自花了多久"，
     * 由 [TapCatcher.endDrag] 每手势写**一条** `LivePageTrace` —— 于是"一次
     * `setStreamVolume` 到底要多少毫秒"在用户手机上可以直接量出来（报告里的验证步骤）。
     */
    private class StreamVolumeWriter(context: Context) {

        /** 用 ApplicationContext 拿服务：写入线程比页面活得久一点也不能攥着 Activity */
        private val appContext = context.applicationContext

        /** 写入线程（★它不是守护线程，必须显式 [shutdown]，调用点在 [LivePlayerActivity.onDestroy]） */
        private val thread = HandlerThread("live-volume-write").apply { start() }

        private val handler = Handler(thread.looper)

        private val audioManager: AudioManager? =
            appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        /**
         * **最新**目标档位（-1 = 还没提交过）。
         * ★只被覆盖、**从不清零** —— 清零点会与 [submit] 抢占（清掉的正是刚提交的新目标），
         *   而"从不清零"让每个后台任务都读到"此刻最新的那一档"，天然做到"最后一档必胜"。
         */
        @Volatile
        private var pending = -1

        /** 最后一次**真正下发**的档位（-1 = 本页还没下发过）；去重就靠它 */
        @Volatile
        private var written = -1

        /** 诊断：真正下发的次数 / 累计耗时 / 单次最长耗时（ms） */
        @Volatile
        var writeCount: Int = 0
            private set

        @Volatile
        var writeTotalMs: Long = 0L
            private set

        @Volatile
        var writeMaxMs: Long = 0L
            private set

        /** 页面销毁后不再接受提交（此时线程正在退出，再 post 也没人处理） */
        @Volatile
        private var stopped = false

        private val drainRunnable = Runnable { drain() }

        /**
         * 手势开始：把"系统现在的档位"告诉写入器。
         *
         * 为什么需要：老写法用 `lastWrittenVolume = downVolume` 避免"第一档位移白写一次同值"，
         * 这里保留同一语义。传入的是 UI 线程刚 `getStreamVolume` 读到的**真值**，
         * 所以即使上一次手势的写入还在队列里，也只是多写/少写一次同值 —— 无副作用。
         */
        fun noteCurrent(index: Int) {
            written = index
        }

        /**
         * 提交一个目标档位 —— **只投递、不等待**（调用线程永远不会被 binder 挡住）。
         * 中间档位会被后续提交合并掉；最后一次提交的那个档位必然会写进系统。
         */
        fun submit(target: Int) {
            if (stopped || target < 0) return
            pending = target
            handler.post(drainRunnable)
        }

        /** 收线程（[LivePlayerActivity.onDestroy]）。`quitSafely` 会把已投递的任务做完再退。 */
        fun shutdown() {
            stopped = true
            thread.quitSafely()
        }

        /** 后台线程：把"此刻最新的那一档"写进系统；与上次同档就跳过。 */
        private fun drain() {
            val am = audioManager ?: return
            val target = pending
            if (target < 0 || target == written) return
            val at = SystemClock.uptimeMillis()
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
            val cost = SystemClock.uptimeMillis() - at
            written = target
            writeCount++
            writeTotalMs += cost
            if (cost > writeMaxMs) writeMaxMs = cost
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 手势层
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 手势层：单击显隐控制条、双击播放/暂停、**左右半区上下滑调亮度/音量**。
     *
     * ★左右半区映射**与点播逐字一致**（`DanmakuVideoPlayer.kt:1806-1817`）：
     * ```
     * 左半区 = 亮度，右半区 = 音量
     * ```
     * 用户要的是"老样子复用点播那套逻辑和 UI"，所以这里不做反向映射。
     * ★**提示气泡的位置是"反"的，这也是照抄点播**：调右半区（音量）气泡显示在**左半区**，
     *   调左半区（亮度）气泡显示在**右半区** —— 手指不挡提示，用户点名要的就是这个手感。
     *   依据是 GSY 的两份布局（`video_volume_dialog.xml` 内容贴左 / `video_brightness.xml` 贴右），
     *   详见 [GestureHud]。
     *
     * ★半区判定用**绝对屏幕坐标**（`event.rawX` 对比"本页在屏幕上的中点"），
     *   而不是本 View 的坐标系 —— 这是点播里踩过坑之后的结论，原注释写着
     *   "★ 用绝对屏幕坐标判定左右半区，彻底解决坐标系不匹配导致音量失效"（:1801）。
     *
     * ★本页**不做横向滑动 seek**：直播没有时间轴，"快进"没有意义（点播的横向手势对应的是 seek）。
     *   所以只有"竖直位移明显大于横向"时才进入手势，横向滑动直接放行给上层。
     */
    private inner class TapCatcher(context: Context) : View(context) {

        private val detector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    // ★第五批第 3 条的安全阀：**能点到这一层就说明本页是全屏可交互的**
                    //   （PiP 小窗里既没有手势层、也没有点击空间）。万一某条 ROM 路径没派发
                    //   `onPictureInPictureModeChanged(false)` / `onResume`，"即将进小窗"的标记
                    //   就会一直挂着、控制条被永久压住 —— 这里清掉它，一次点击即可恢复正常。
                    if (pipEntryPending && !isInPictureInPictureMode) pipEntryPending = false
                    // ★第七批：正在输入弹幕时，单击画面 = **收起键盘**（而不是把控制条切走）。
                    //   理由：用户点一下画面多半是想"看画面了、先别打字"；而控制条这时本来就该亮着
                    //   （[hideControlsRunnable] 与 [applyControlsVisibility] 都不在输入时隐藏）。
                    //   ★只在这一种情况下改变单击语义；没在输入时，单击仍是"显隐控制条"（手势语义一行未改）。
                    if (isDanmakuInputActive()) {
                        dismissDanmakuInput()
                        holdControls()
                        return true
                    }
                    if (controlsVisible) setControlsVisible(false) else showControlsTemporarily()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // ★「设置 → 直播设置 → 双击暂停」（`live_double_tap_pause`，默认开）：
                    //   关掉之后双击**什么都不做**（设置页的说明逐字如此："关掉后双击不做事，
                    //   单击仍然显隐控制条"）。★这里返回 true（吃掉这次双击）而不是 false：
                    //   GestureDetector 已经把这一串事件按"双击"处理了，返回 false 不会让第一下
                    //   变成"单击确认"，只会把事件继续往下传给一个没人接的 View —— 徒增不确定性。
                    if (!onDoubleTapPauseEnabled()) return true
                    togglePlayPause()
                    return true
                }
            },
        )

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        /**
         * 复用同一个两元数组装"视频画面矩形在屏幕上的左上角"（[isInsideVideoStage] 用）。
         * 起手判定最多每个手势跑几次，但**一次分配都不做**是这类手势路径上的老规矩
         * （同 `isLeftHalf` 旁边那套写法，只是那边每次 new 一个——它不在热路径上，没动它）。
         */
        private val videoStageLocation = IntArray(2)

        private var dragMode = DRAG_NONE
        private var consumedByDrag = false
        private var downRawX = 0f
        private var downRawY = 0f
        private var downVolume = -1
        private var downBrightness = -1f

        // ── 音量手势状态（★第十三批：写入已搬出 UI 线程，见 [StreamVolumeWriter]）──────────
        /** `AudioManager` 缓存（点播 GSY 同样是字段缓存：`GSYVideoControlView.getAudioManager()`） */
        private var audioManager: AudioManager? = null
        /**
         * `STREAM_MUSIC` 最大档位（0 = 还没读）。
         * ★**按页面缓存**（老写法每个手势重读一次）：它是"音量刻度的分母"，只有切输出设备
         *   （蓝牙/耳机）才会变，没必要每个手势都去 binder 问一次；只读到 0 时才重读。
         */
        private var cachedMaxVolume = 0
        /** 本次手势提交给后台写入器的**最后一档**（-1 = 没提交过）；只用于诊断日志 */
        private var lastVolumeTarget = -1

        // ── 跟手性诊断（★第十三批：每手势**一条**日志，绝不逐帧，见 [endDrag]）──────────
        /** 本次手势处理了多少个 MOVE 事件（被批量合并时，这个数会明显小于"刷新率 × 时长"） */
        private var dragMoves = 0
        /** 本次手势第一个 MOVE 的时刻（0 = 还没开始） */
        private var dragStartedAt = 0L
        /** 本次手势里"事件产生 → 被我们处理"的**最大**延迟（ms）：跟手性的直接度量 */
        private var dragLagMaxMs = 0L
        /** 抬手前最后一个 MOVE 的同一延迟（ms） */
        private var dragLagLastMs = 0L
        /** 手势开始时的写入器计数（抬手时做差 = "本手势真正写了几次、共花多久"） */
        private var dragStartWriteCount = 0
        private var dragStartWriteMs = 0L

        init {
            isClickable = true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // 拖动一旦成立，这一串事件就只归手势用：绝不能再喂给 GestureDetector，
            // 否则抬手时它还会补一个"单击确认"，把控制条又切一次
            if (consumedByDrag) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> handleDrag(event)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
                    else -> Unit
                }
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    consumedByDrag = false
                    dragMode = DRAG_NONE
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downVolume = -1
                    downBrightness = -1f
                    // ★第十三批：逐手势重来的只有"跟手性诊断"的计数与最后一档；
                    //   音量档位缓存（[cachedMaxVolume]）是**按页面**的（见字段注释）。
                    //   ★老写法这里还要清 `lastWrittenVolume` 等三个写入状态 —— 那些状态
                    //     已经整个搬进 [StreamVolumeWriter]（后台线程自己维护），UI 侧不再有。
                    lastVolumeTarget = -1
                    dragMoves = 0
                    dragStartedAt = 0L
                    dragLagMaxMs = 0L
                    dragLagLastMs = 0L
                }

                MotionEvent.ACTION_MOVE -> if (startDragIfNeeded(event)) return true
            }
            return detector.onTouchEvent(event) || super.onTouchEvent(event)
        }

        /** 竖直位移超过 slop 且**明显大于**横向位移 → 进入手势；返回 true 表示本串事件已被接管 */
        private fun startDragIfNeeded(event: MotionEvent): Boolean {
            val dy = abs(event.rawY - downRawY)
            val dx = abs(event.rawX - downRawX)
            if (dy <= touchSlop || dy <= dx) return false

            // ★★本轮（2026-09-26）：**起手点必须落在视频画面区域内**，才允许进入亮度/音量手势。
            //
            // 用户原话："我在竖屏的状态下拖动某一些区域，比如说**底栏的那个黑色块区域**，
            // 它也能调音量……就是在竖屏的情况下，只能点它的**播放区域**。"
            //
            // 判据是**正向**的一条：`按下点在 [videoContainer] 矩形内`（见 [isInsideVideoStage]）——
            // **不是**"排除底栏"那种反着写的判据。反着写必须枚举"哪些区域不该响应"
            // （底栏 + 输入条 + 弹幕列表 + 视频带下方的留白 + 顶栏……），漏一个就漏一个洞；
            // 而"画面矩形"这一个正向判据天然把上面这些**全部**挡在外面：
            // ```
            // 竖屏：画面 = 顶栏之下的那条视频带（[applyVideoStageLayout]，顶边 = [videoBandTopPx]）
            //       ├─ 带子以下 → 弹幕列表（宿主注入的面板）→ 不在矩形内 → 不响应 ✓
            //       ├─ 列表以下 → 底栏（含输入条）        → 不在矩形内 → 不响应 ✓
            //       └─ 带子以内的黑边/留白                → 在矩形内   → 响应（观感仍是"视频区"）
            // 横屏：带子关闭（`band = 0`）、容器铺满整页 ⇒ 矩形 ≈ 整屏 → **行为与改动前逐字一致** ✓
            // PiP：小窗里既没有手势层也没有这个交互，不受影响。
            // ```
            // ★位置放在 slop 判定**之后**、给 `GestureDetector` 发 CANCEL **之前**：
            //   没接管这一串事件时（横向滑动 / 起手在画面外），就与"横向滑动直接放行"走同一条路 ——
            //   不取消 detector 的按下状态，单击/双击的语义一个字不变。
            // ★**起手定模式**：只在这里（= 按下点）判一次。手势一旦成立（`consumedByDrag = true`），
            //   后续 MOVE 全部直接进 [handleDrag]，**手指滑出画面矩形也不会中断**（与改动前一致）。
            if (!isInsideVideoStage(downRawX, downRawY)) return false

            // 给 GestureDetector 一个 CANCEL 收尾：清掉它内部的按下状态，
            // 免得后面某个 UP 触发"单击确认"（拖动结束不该显隐控制条）
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            detector.onTouchEvent(cancel)
            cancel.recycle()

            dragMode = if (isLeftHalf(downRawX)) DRAG_BRIGHTNESS else DRAG_VOLUME
            consumedByDrag = true
            handleDrag(event)
            return true
        }

        /**
         * 这个点（**绝对屏幕坐标**，与 `event.rawX/rawY` 同一套）落在视频画面矩形里吗？
         *
         * ## 矩形从哪来（"竖屏版式的唯一真相"）
         * 直接读 [videoContainer]（`AspectRatioFrameLayout`）**当前的真实几何**，换算到屏幕坐标：
         * ```
         * 竖屏：videoContainer = 顶栏之下的视频带，高 = 宽 ÷ 视频比例（封顶 62% 页高）
         *       —— 这与 [measurePortraitStage] 发布出去的 [portraitVideoRect] **是同一个矩形**
         *          （那里面写的就是 `Rect(videoContainer.left, top, right, bottom)`），
         *          也就是弹幕列表要贴的那个"画面底边"的来源；这里直接读容器，不多一份镜像。
         * 横屏：videoContainer 铺满整页（`bandMaxHeightFraction = 0`）→ 矩形 = 整屏。
         * ```
         * ★为什么不用 [videoView]（TextureView，真正的画面）：那是**letterbox 之后**的画面矩形，
         *   横屏宽银幕下左右两条黑边会被它排除掉 —— 那等于顺手改了横屏的手势范围（用户没要求，
         *   也不该在"修竖屏小瑕疵"里做）。用**视频带/容器**既覆盖竖屏的"播放区域"，
         *   又天然保住横屏"整屏可滑"的既有手感。
         * ★几何没就绪时**放行**（返回 true）：还没 `isInitialized` / 还没量出宽高（首帧、极端时序）时
         *   宁可保持改动前的老行为，也不能因为拿不到几何就让亮度/音量手势整体失灵。
         * ★只在**起手**时调用一次（每个手势一次坐标读，零分配之外的代价可忽略）。
         */
        private fun isInsideVideoStage(rawX: Float, rawY: Float): Boolean {
            if (!::videoContainer.isInitialized) return true
            val w = videoContainer.width
            val h = videoContainer.height
            if (w <= 0 || h <= 0) return true
            videoContainer.getLocationOnScreen(videoStageLocation)
            val left = videoStageLocation[0]
            val top = videoStageLocation[1]
            return rawX >= left && rawX < left + w && rawY >= top && rawY < top + h
        }

        /** 按下点是否落在本页左半区（绝对屏幕坐标，抄点播 :1801-1806） */
        private fun isLeftHalf(rawX: Float): Boolean {
            val location = IntArray(2)
            getLocationOnScreen(location)
            val midX = location[0] + width * 0.5f
            return rawX < midX
        }

        private fun handleDrag(event: MotionEvent) {
            // ★第十三批：跟手性诊断（**只为日志**，不改任何手势行为）。
            //   `event.eventTime` 是这一串触摸样本在系统里**产生的时刻**，与 `uptimeMillis()` 同一个
            //   时基；两者之差 = "手指已经动了多久，我们才开始处理它"。主线程被跨进程写入堵住时，
            //   这个值会涨到几百甚至上千毫秒 —— 那正是用户说的"过了一两秒 UI 才跟上"；
            //   修好之后它应该只剩一帧左右的量级（抬手时由 [endDrag] 记成一条日志）。
            val now = SystemClock.uptimeMillis()
            if (dragMoves == 0) dragStartedAt = now
            dragMoves++
            val lag = (now - event.eventTime).coerceAtLeast(0L)
            dragLagLastMs = lag
            if (lag > dragLagMaxMs) dragLagMaxMs = lag
            when (dragMode) {
                DRAG_VOLUME -> handleVolumeDrag(event)
                DRAG_BRIGHTNESS -> handleBrightnessDrag(event)
                else -> Unit
            }
        }

        /**
         * 右半区：上下滑调**媒体音量**。
         * 换算与点播同一套（`DanmakuVideoPlayer.kt:1743-1751`）：
         * `Δ音量 = max × ΔY × 2 / 屏高`，即"半屏高度的滑动 = 满音量"。
         *
         * ## ★★第十三批：UI 线程只做 UI（用户实测："手势的 UI 没有实时跟进，过了一两秒才显示"）
         *
         * ### 上一版（第十一批第 2 条）修到哪、为什么还不够
         * 那一版把写入降到"档位变了才写 + 同一帧最多写一次（16ms 节流）"，但**写入本身仍在 UI 线程**：
         * ```
         * handleVolumeDrag：先 writeStreamVolume(am, target) → am.setStreamVolume(…)   ← 同步跨进程
         *                  后 gestureHud.showVolume(…)                                 ← 气泡排在它后面
         * ```
         * 两个后果，正好就是用户报的两个症状：
         * 1. `setStreamVolume` 是**跨进程**调用（binder → system_server 的 AudioService，后者还要做
         *    app-ops 检查、听力保护判定、再 binder 通知 SystemUI、最后落一次系统设置 —— 完整链路
         *    逐行走在 [StreamVolumeWriter] 的 KDoc 里）。MOVE 是按刷新率投递的（120Hz ⇒ 8.3ms 一个），
         *    **只要一次写入比这个间隔长，主线程就永远追不上手指**，输入队列越积越多；
         * 2. 气泡刷新排在写入**后面**，于是"手指已经滑完了，气泡才慢吞吞地更新"——
         *    正是"过了一两秒它才显示"。16ms 节流救不回来：它只把写入从 ~120 次/秒压到 ~60 次/秒，
         *    仍远多于"一次手势真正跨过的档位数"（十几档）。
         *
         * ### 现在（第十三批）：三层各自归位
         * ```
         * ① 气泡先刷：gestureHud.showVolume(…)      —— 纯 UI，这条路上一个跨进程调用都没有
         * ② 音量只投递：obtainVolumeWriter().submit(…)    —— 立即返回，UI 线程不等 binder
         * ③ 后台线程"最新档位必胜"地写（[StreamVolumeWriter]，含去重与中间档位合并）
         * ```
         * ⇒ 这类手势的 UI 线程**一次 binder 都不做**，气泡每个 MOVE 都在同一条消息里就画出去，
         * 输入队列不再堆积 —— 这是"跟手"的必要条件（诊断值见 [endDrag] 的 `lagMaxMs`）。
         *
         * ### 保持不变（别改坏）
         * · `AudioManager` 缓存（点播 GSY 也是字段缓存：`GSYVideoControlView.getAudioManager()`）；
         * · `max` 只**按页面**读一次（比点播"每个事件读一次"更省），读到 0 才重读；
         * · `getStreamVolume` 仍只在手势开始时读一次（与点播一致；一次手势一次，不在 MOVE 里）；
         * · **不加** `FLAG_SHOW_UI`：那会弹出系统音量面板（用户明确不要）；
         * · 换算公式与点播逐字一致（那段"太灵敏"的历史注释别再翻案）。
         * ★亮度分支（[handleBrightnessDrag]）**只换了两句的顺序**（气泡先刷、窗口属性后写），
         *   公式与显隐语义一个字没动 —— 用户说它是顺的，别动它。
         */
        private fun handleVolumeDrag(event: MotionEvent) {
            val writer = obtainVolumeWriter()
            val am = audioManager
                ?: (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.also { audioManager = it }
                ?: return
            if (cachedMaxVolume <= 0) {
                cachedMaxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            }
            val max = cachedMaxVolume
            val pageHeight = pageHeightPx().coerceAtLeast(1)
            if (downVolume < 0) {
                downVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                // 手势开始时系统音量就是 downVolume：告诉写入器"现在就在这一档"，
                // 于是第一档位移不会白白写一次同样的值（真正的第一档变化照样会写）
                writer.noteCurrent(downVolume)
                // 诊断起点：抬手时做差 = "本手势真正下发了几次、共花了多少毫秒"
                dragStartWriteCount = writer.writeCount
                dragStartWriteMs = writer.writeTotalMs
            }
            val deltaY = downRawY - event.rawY // 向上滑 = 变大
            // ★2026-09-26 用户实测"手势太灵敏，一滑就全黑/音量归零"。
            //   病根：本页用"绝对位移 × 2 / 屏高"，而点播（GSY）用的是"增量 / (屏高 × 3) 累加"
            //   —— 两者是线性等价的写法，但系数差了 **6 倍**（2 vs 1/3）。
            //   这里改成与点播同一个系数：整屏滑到底约改变 1/3 量程，跟点播手感一致。
            // ★2026-09-26 对着点播**逐字抄**（用户："我的写代码在那里，你不会去对着抄吗？"）：
            //   点播 `DanmakuVideoPlayer.kt:1748-1754` 的音量公式是
            //     `deltaV = (max * deltaY * 2 / curHeight)`，气泡百分比另算
            //     `volumePercent = downVolume*100/max + deltaY*2*100/curHeight`。
            //   我上一轮把**亮度**的 `/(curHeight*3)` 误抄到了音量上（差 6 倍），这里改回 ×2。
            // ★★2026-09-26 用户实测："还是太灵敏，比我普通视频的音量调节差很多"。
            //   复盘：早先的 ÷3（3 屏走完全程）那一版，用户**没有**抱怨灵敏度（只抱怨卡顿）；
            //   后来我"对着点播逐字抄"改回 ×2（点播源码里那个系数）⇒ 灵敏度**放大 6 倍** ⇒ 就是这个反馈。
            //   而"卡顿"已经由另一处修复解决（音量写入搬到后台线程 + 气泡先刷，见 StreamVolumeWriter）——
            //   所以这里把手感回到用户认可的那一版：**划满 3 个屏高走完整条行程**（÷3）。
            val deltaV = (max * deltaY / (pageHeight * 3)).toInt()
            val volumePercent =
                (downVolume * 100 / max + deltaY * 100 / (pageHeight * 3)).toInt()
            val target = (downVolume + deltaV).coerceIn(0, max)
            // ① 气泡**先**刷（★第十三批的顺序）：用户看到的即时反馈优先 ——
            //    这条路上一个跨进程调用都没有，所以"每个 MOVE 都跟手"由它保证。
            // ★音量气泡贴**左**边显示（点播 video_volume_dialog.xml 就是这么摆的）：
            //   手指在右半区滑动，提示落在左半区，既挡不住手也看得见 —— 用户点名要的正是这条。
            // 气泡用点播同一条百分比公式（与 target 同源，但保留点播的写法便于日后对照）
            gestureHud.showVolume(volumePercent.coerceIn(0, 100))
            // ② 系统音量：**只投递、不等待**。唯一出口是 [StreamVolumeWriter]（后台线程），
            //    UI 线程一个字节都不写系统 —— 这是本轮"跟手"的关键（见本函数 KDoc）。
            lastVolumeTarget = target
            writer.submit(target)
        }

        /**
         * 左半区：上下滑调**画面亮度**。
         * 走 `window.attributes.screenBrightness`（和点播 `onBrightnessSlide` 一样），
         * 只影响本页，不动系统亮度设置。
         */
        private fun handleBrightnessDrag(event: MotionEvent) {
            val pageHeight = pageHeightPx().coerceAtLeast(1)
            if (downBrightness < 0f) {
                downBrightness = currentWindowBrightness()
            }
            val percent = (downRawY - event.rawY) / (pageHeight * BRIGHTNESS_FULL_SWIPE_RATIO)
            val target = (downBrightness + percent).coerceIn(MIN_BRIGHTNESS, 1f)
            // ★第十三批：**先刷气泡、再写窗口属性**（和音量那边同一条原则）。
            //   气泡这一句是纯 UI；后面那句 `window.attributes = lp` 是**同步的窗口事务**
            //   （relayoutWindow 的跨进程调用，点播 GSY 每个事件也做一次，见同文件 :1787-1791）。
            //   顺序换过来只是把"用户看得见的反馈"排在前面 —— 亮度这条本身是顺的，公式与
            //   本条路径的其它行为**一个字都没动**。
            gestureHud.showBrightness((target * 100).roundToInt())
            runCatching {
                val lp = window.attributes
                lp.screenBrightness = target
                window.attributes = lp
            }
        }

        /**
         * 当前亮度：先看本页已经设过的 `window.screenBrightness`，
         * 没有（= -1，跟随系统）才去读系统亮度。抄点播 `onBrightnessSlide` 的取值顺序。
         */
        private fun currentWindowBrightness(): Float {
            var brightness = runCatching { window.attributes.screenBrightness }.getOrDefault(-1f)
            if (brightness <= 0f) {
                brightness = runCatching {
                    val sys = android.provider.Settings.System.getInt(
                        contentResolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    )
                    (sys / 255f).coerceIn(MIN_BRIGHTNESS, 1f)
                }.getOrDefault(0.5f)
            }
            return brightness
        }

        private fun endDrag() {
            // ★第十三批：抬手时记**一条**跟手性诊断（不是每 MOVE，见 [LivePageTrace] 的量控原则）。
            //   三个数就是"这次手势跟不跟手"的直接证据：
            //   · `lagMaxMs` / `lagLastMs` —— 触摸样本从产生到被我们处理的延迟。跟手时应该只有
            //     一帧左右的量级（几十毫秒以内）；主线程被跨进程调用堵住时会涨到成百上千毫秒；
            //   · `moves` —— 本手势真正处理了多少个 MOVE。被堵住时系统会把 MOVE 批量合并，
            //     这个数会明显小于"刷新率 × 手势时长"；
            //   · `writes` / `writeMs` / `writeMaxMs` —— 本手势**真正下发系统音量**的次数与耗时
            //     （在后台线程量的）。它就是"一次 setStreamVolume 到底多贵"的实测值。
            val now = SystemClock.uptimeMillis()
            val kind = if (dragMode == DRAG_BRIGHTNESS) "brightness" else "volume"
            val writer = volumeWriter
            LivePageTrace.note(
                "gesture.drag",
                "kind" to kind,
                "moves" to dragMoves,
                "ms" to (if (dragStartedAt > 0L) now - dragStartedAt else 0L),
                "lagMaxMs" to dragLagMaxMs,
                "lagLastMs" to dragLagLastMs,
                "startVolume" to downVolume,
                "targetVolume" to lastVolumeTarget,
                "maxVolume" to cachedMaxVolume,
                "writes" to (writer?.let { it.writeCount - dragStartWriteCount } ?: 0),
                "writeMs" to (writer?.let { it.writeTotalMs - dragStartWriteMs } ?: 0L),
                "writeMaxMs" to (writer?.writeMaxMs ?: 0L),
            )
            consumedByDrag = false
            dragMode = DRAG_NONE
            gestureHud.hide()
            // ★抬手前 **不再**补写系统音量（老写法那句 flushPendingVolumeWrite 已删）：
            //   写入在后台线程，"最新档位必胜"（见 [StreamVolumeWriter]）——
            //   抬手时最后提交的那一档本来就在那儿，没有任何档位会被节流丢掉。
            // ★抬手后把"手势起点"清掉：下一次手势必须重新读当时的音量/亮度。
            //   不清的话第二次手势会拿"上次的起点 + 本次绝对位移"再算一遍，越滑越离谱
            //   （点播里 `mGestureDownVolume = -1` / `lastGestureBrightness = -1f` 是同一件事）。
            downVolume = -1
            downBrightness = -1f
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 竖屏版式：上面视频、下面弹幕列表
    //
    // 第三批用户原话："竖屏的情况下，能不能把播放的区域往靠上一点？像我第一张图一样"
    // （那张图是 B 站官方 App 的竖屏直播页：上面视频，下面整块是弹幕/聊天区）。
    // 第四批用户把它修正成："这个直播的画面太上了，太顶了。它把那个状态栏，还有我们点一下
    // 显示几秒的那个 Tab 栏（顶栏）也顶住了，**应该放在那个顶栏和状态栏下面**。"
    //
    // 落地成"两套版式、一个开关"，**视频带的顶边由顶栏说话**：
    // ```
    // 竖屏：视频 = 顶栏之下的那条带（顶边 = 状态栏内边距 + 顶栏高，高度 = 宽 ÷ 比例，封顶 62% 页高）
    //       ├─ 底边 = 列表槽 [danmakuListSlot] 的顶边（同一时刻同一个值，中间不留黑缝）
    //       ├─ 下面全部留给弹幕列表：矩形由 [portraitDanmakuListBounds] 发布给宿主
    //       └─ 底栏照旧贴底（列表区的底边就是底栏顶，所以面板不会压住按钮）
    // 横屏：一个字不改（容器铺满整页、画面居中、沉浸式全屏）
    // ```
    // ★版式的**唯一事实来源**是 [measurePortraitStage] 量出来的两个矩形（[portraitVideoRect] /
    //   [portraitListRect]）：手势气泡按前者定位、弹幕列表按后者摆放、[LivePortraitStage] 也读它们。
    // ★"竖屏/横屏"也在这里统一成 [isPageLandscape]（真实尺寸，不看 `Configuration.orientation`）——
    //   与宿主判"竖屏列表模式"的 `portrait = h > w` 同源，见 [isPageLandscape] 的 KDoc。
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 竖屏视频带**该在的顶边**（页内 px）—— 第四批第 3 条的落点。
     *
     * ```
     * = 顶栏底边（[topBar].bottom）
     * = 状态栏内边距 + 顶栏内容高     ← 顶栏那份 OnApplyWindowInsetsListener 已经把 bars.top 加进自己的 padding
     * ```
     * 所以"放在状态栏和顶栏下面"这一条用**一个值**就表达完了，不用分别去要两段的 insets。
     *
     * ★顶栏是**浮层**（GONE 之后 4 秒才随单击回来），但这条**不跟着它显隐变**：
     *   · 这正是用户的要求 ——"让视频**始终**在其下，不要压在它下面"；
     *   · 依据与宿主"底栏留白"那条完全一样：被 GONE 的 View 不会重新 layout，
     *     `top/bottom` 保留**最后一次显示时的位置** —— 拿到的就是稳定的"顶栏占位"。
     * ★顶栏还没量出来（首帧之前）返回 0：视频带先贴顶，紧接着 [installPageLayoutWatchers] 里
     *   挂在顶栏上的那只监听器会在它量出来之后把带子挪下来（同一帧内，页面还是黑的，看不见闪）。
     */
    private fun videoBandTopPx(): Int =
        if (::topBar.isInitialized && topBar.height > 0) topBar.bottom.coerceAtLeast(0) else 0

    /**
     * 按当前形态排一次版式（[buildUi] 末尾、[syncPageLayoutToRealSize]、进出小窗各调一次）。
     *
     * ## ★★第八批：**PiP 里必须"画面铺满窗口"**（用户实测第 4 条的修法，根因证据在这段里）
     *
     * ```
     * 不在 PiP：竖屏 = 顶栏之下的一条带（高 ≤ 62% 页高，顶边 = 顶栏底边）
     *          横屏 = 容器铺满整页、画面按比例居中
     * 在  PiP：**一律走"铺满"这一支**（band = 0、topMargin = 0）—— 与小窗的形状无关
     * ```
     * 为什么非要有这一支（"小窗里不占满、有黑边"的确切来路）：
     * ① PiP 小窗**不是把整页缩小**，它是把本页的窗口**改成小窗的尺寸**（所以进来之后页面会按
     *    小窗重新排版 —— 本页的 [installPageLayoutWatchers]、"PiP 里按钮被按小窗宽度测量"这些注释
     *    都是这个事实的旁证）。小窗的形状 = [pipAspectRatio] = **视频解码比例**，与窗口比例一致；
     * ② 可是小窗里**没人重排过版式**：[syncPageLayoutToRealSize] / [syncBottomBarLayout] 在
     *    `isInPictureInPictureMode` 时**直接 return**（那是有意的：小窗的尺寸不代表全屏版式），
     *    而 [applyVideoStageLayout] 的唯一调用点就是 [syncPageLayoutToRealSize]（+ [buildUi]）；
     * ③ 于是竖屏进小窗时，`videoContainer.bandMaxHeightFraction` 还是 0.62、
     *    `topMargin` 还是全屏时顶栏的高度（`topBar` 已经 GONE，`top()` 停在最后一次布局的位置）
     *    —— 小窗里画面被压成"小窗高 - 顶栏高"的 62%，还要再按比例居中，四周全是黑边；
     * ④ 竖屏**主播（9:16）**更明显：小窗本身是竖的（窗口比例 = 视频比例），
     *    而带子封顶 62% → 画面只有小窗的 62% 高、62% 宽，四面黑边。
     * ⇒ 修法就是这一段：**在 PiP 里关掉带子、顶边归零**，容器铺满小窗；此时
     *    【窗口比例 == 画面比例】（都由 [pipAspectRatio] / `videoContainer.videoAspectRatio` 说话），
     *    所以 [AspectRatioFrameLayout] 量出来的画面**正好等于小窗**，一点黑边都没有。
     *    退出小窗时 [onPictureInPictureModeChanged]`(false)` 会再排一次，带子照旧回来。
     *
     * @param orientationOverride 只给"页面还没量出尺寸"的兜底（现无调用点传值）；传 null = 按
     *   [isPageLandscape]（真实尺寸优先）。★第四批起**不再**用 `newConfig.orientation`：
     *   那是窗口方向，PiP 下会把竖屏版式排成横屏那一套（第 1 条的根因）。
     */
    private fun applyVideoStageLayout(orientationOverride: Int? = null) {
        if (!::videoContainer.isInitialized || !::rootLayout.isInitialized) return
        // ★★第八批：PiP 里"哪种形态"不再是问题 —— 画面必须铺满小窗（见上面那段 KDoc 的 ①~④）
        val pipFill = isInPictureInPictureMode
        val portrait = !isPageLandscape(orientationOverride) && !pipFill
        // ① 视频：竖屏 = 顶栏之下的一条带（高度由 [AspectRatioFrameLayout] 按比例量）；
        //          横屏 / PiP = 0f = 关掉带子，容器铺满整页（横屏与改动前逐字一致）
        val band = if (portrait) PORTRAIT_VIDEO_MAX_HEIGHT_FRACTION else 0f
        // ★第四批第 3 条：带子模式下顶边 = 顶栏底边（横屏/听音频/PiP 保持 0 = 铺满/贴顶）
        val bandTop = if (band > 0f) videoBandTopPx() else 0
        val lp = videoContainer.layoutParams
        if (lp is FrameLayout.LayoutParams &&
            (lp.gravity != Gravity.TOP || lp.topMargin != bandTop)
        ) {
            // 贴顶 + 顶边下移到顶栏之下。横屏也显式写 TOP：FrameLayout 的默认 gravity 在纵向本来
            // 就是"贴顶"，显式写出来只是把这条不变量固定住 —— 横向因为宽度是 MATCH_PARENT 而不受影响。
            // ★topMargin 不只是"挪一下"：FrameLayout 给 MATCH_PARENT 子 View 的高度测量里会**扣掉
            //   margin**（`getChildMeasureSpec(parentHeight, padding + margins, MATCH_PARENT)`），
            //   所以 [AspectRatioFrameLayout] 算带子高度时用的可用高度天然就是"页高 - 顶栏"——
            //   62% 的封顶也落在剩下的这块里，带子不会被顶出屏幕。
            lp.gravity = Gravity.TOP
            lp.topMargin = bandTop
            videoContainer.layoutParams = lp
        }
        appliedVideoBandTop = bandTop
        if (videoContainer.bandMaxHeightFraction != band) {
            videoContainer.bandMaxHeightFraction = band
            videoContainer.requestLayout()
        }
        // ★诊断日志（只读；签名没变就不写）：视频带版式（top/height、是否 PiP 全幅）
        LivePageTrace.noteIfChanged(
            "stage.video",
            "pip=$pipFill|portrait=$portrait|band=$band|bandTop=$bandTop" +
                "|top=${videoContainer.top}|h=${videoContainer.height}|w=${videoContainer.width}" +
                "|page=${if (::rootLayout.isInitialized) "${rootLayout.width}x${rootLayout.height}" else "-"}",
            "stage.video",
            "pipFill" to pipFill,
            "portrait" to portrait,
            "bandFraction" to band,
            "bandTop" to bandTop,
            "appliedBandTop" to appliedVideoBandTop,
            "topBarBottom" to (if (::topBar.isInitialized) topBar.bottom else -1),
            "videoRect" to LivePageTrace.rect(Rect(videoContainer.left, videoContainer.top, videoContainer.right, videoContainer.bottom)),
            "videoSize" to "${videoContainer.width}x${videoContainer.height}",
            "page" to (if (::rootLayout.isInitialized) "${rootLayout.width}x${rootLayout.height}" else "-"),
            "isLaidOut" to videoContainer.isLaidOut,
        )
        // ② 量两个矩形 + 摆列表槽。★必须等**这一次布局跑完**再量：见 [runAfterPageLayout]
        measurePortraitStageAfterLayout()
    }

    /**
     * 在当前这一帧的**布局之后**做一件事（版式量算用它）。
     *
     * ★为什么不是 `rootLayout.post {}`：`View.post` 的 Runnable 排在消息队列里，会在
     *   **下一次 vsync 的 traversal 之前**执行 —— 而竖屏视频带的高度是这次布局才量出来的
     *   （[AspectRatioFrameLayout] 在 onMeasure 里改的），post 里读到的还是旧高度
     *   （转屏那一瞬间读到的就是"上一个方向的高度"）。用布局回调才是"布局之后"的准确时点：
     *   · 已经量过、且没有待处理的布局请求 → 当场做（值就是当前的）；
     *   · 否则挂一个**一次性**的 `OnLayoutChangeListener`，这一帧布局完立刻做、随即摘掉。
     *   ★一次性的：回调里第一件事就是摘掉自己，不会形成"量→改 layoutParams→再量"的自激回路。
     * ★退出 PiP 也用它（[onPictureInPictureModeChanged]）：那时要读的必须是**恢复后的全屏尺寸**，
     *   而不是小窗残留的那个尺寸 —— 这就是"PIP 回来字号变小"那一条的收尾动作。
     */
    private fun runAfterPageLayout(block: () -> Unit) {
        if (!::rootLayout.isInitialized) return
        if (rootLayout.isLaidOut && !rootLayout.isLayoutRequested) {
            block()
            return
        }
        rootLayout.addOnLayoutChangeListener(
            object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    rootLayout.removeOnLayoutChangeListener(this)
                    if (!isFinishing && !isDestroyed) block()
                }
            },
        )
    }

    /** [runAfterPageLayout] 的专用版：布局之后量一次竖屏版式（[measurePortraitStage]） */
    private fun measurePortraitStageAfterLayout() {
        runAfterPageLayout { measurePortraitStage() }
    }

    /**
     * 量出竖屏的两个矩形（页内坐标 px），并把"列表槽"摆到预留区上。
     *
     * 量的三个来源都是**页内坐标**，不需要任何屏幕坐标换算：
     * · 视频区 = [videoContainer] 自己的矩形（它是 rootLayout 的直接子 View）；
     * · 列表区 = 视频区底边 → [bottomBar] 顶边；
     * · 左右 = 整页宽。
     *
     * ★列表区的**顶边 = 视频带的底边**（`listTop = video.bottom`）：这是第四批第 3 条
     *   "视频带与下方弹幕列表之间不要留黑缝"的落点，也是与宿主约定好的唯一契约
     *   （宿主 `bindPortraitListArea(slot = …)` 就是照这块矩形摆面板）。
     *   为了让它在**任何时刻**都成立，[installPageLayoutWatchers] 还给 [videoContainer]
     *   挂了一只"矩形一变就重新量"的监听器（比例到达、顶栏高度变化、底栏换行都会走到）。
     * ★列表区底边取"底栏顶"而不是"页底"，是"不挡底栏"这条要求的落点；而底栏被 GONE（控制条自动隐藏）
     *   之后它的 `top` 仍然是最后一次显示时的位置 —— 正是我们要的：**列表区不随控制条显隐跳动**。
     * ★量不出可信矩形时（横屏 / 听音频 / 首帧之前 / 页面矮到放不下）两个字段一律置 null、列表槽量成 0 高，
     *   宿主与气泡各自退回"老版式"，绝不拿一个 0 矩形去摆。
     */
    private fun measurePortraitStage() {
        if (!::rootLayout.isInitialized || !::videoContainer.isInitialized) return
        val pageWidth = rootLayout.width
        val pageHeight = rootLayout.height
        // ★"竖屏"与 [applyVideoStageLayout] 用同一个判据（真实尺寸），别再用 Configuration.orientation：
        //   它在小窗/分屏下是窗口方向，会造成"视频按竖屏摆、列表按横屏收"这种自相矛盾的中间态。
        // ★★第九批（**与点播对齐**）：**PiP 里不算"竖屏版式"** —— 与 [applyVideoStageLayout] 里那句
        //   `pipFill = isInPictureInPictureMode` 同一个口径。点播在 PiP 里根本不按窗口方向摆版式：
        //   `PlayerDelegate2.onPictureInPictureModeChanged(true)` 直接把宿主按"全屏"排
        //   （`scaffoldApp.fullScreenPlayer = true`，`:1665-1667`），从不拿小窗尺寸判"竖屏"。
        //   而本页这一行原来还按**小窗**的宽高判形态 ⇒ 竖屏房间的小窗（小窗本身是竖的）会向宿主
        //   发布一整块"竖屏视频区 / 列表区"矩形，与"PiP 里画面铺满、不摆竖屏带子"自相矛盾。
        //   今天这一步的可见影响很小（宿主自己也读 `Activity.isInPictureInPictureMode()` 收起列表，
        //   手势气泡在 PiP 里本来就是隐藏的），改它是为了**判据唯一**：小窗尺寸永远不代表页面形态。
        val portrait = !isPageLandscape() && !isInPictureInPictureMode
        val measurable = portrait && pageWidth > 0 && pageHeight > 0 &&
            videoContainer.width > 0 && videoContainer.height > 0
        if (!measurable) {
            portraitVideoRect = null
            portraitListRect = null
            applyListSlot(0, 0)
        } else {
            val video = Rect(
                videoContainer.left,
                videoContainer.top,
                videoContainer.right,
                videoContainer.bottom,
            )
            portraitVideoRect = video
            val listTop = video.bottom.coerceIn(0, pageHeight)
            // 底栏还没量过（理论上不会：buildUi 里就排好了）→ 退到页底
            val barTop = if (::bottomBar.isInitialized && bottomBar.height > 0) {
                bottomBar.top
            } else {
                pageHeight
            }
            val listBottom = barTop.coerceIn(listTop, pageHeight)
            if (listBottom - listTop >= dpToPx(PORTRAIT_LIST_MIN_HEIGHT_DP)) {
                portraitListRect = Rect(0, listTop, pageWidth, listBottom)
                applyListSlot(listTop, listBottom - listTop)
            } else {
                // 地方太小（视频几乎占满 / 底栏特别高）：不给宿主一块放不下东西的矩形
                portraitListRect = null
                applyListSlot(listTop, 0)
            }
        }
        // ★诊断日志（只读；签名没变就不写）：竖屏版式量算结果（槽矩形 / 列表顶底边 / 底栏顶边）
        LivePageTrace.noteIfChanged(
            "stage.measure",
            "page=${pageWidth}x$pageHeight|portrait=$portrait|measurable=$measurable" +
                "|video=${LivePageTrace.rect(portraitVideoRect)}|list=${LivePageTrace.rect(portraitListRect)}" +
                "|barTop=${if (::bottomBar.isInitialized) bottomBar.top else -1}" +
                "|slotTop=${if (::danmakuListSlot.isInitialized) danmakuListSlot.top else -1}" +
                "|slotH=${if (::danmakuListSlot.isInitialized) danmakuListSlot.height else -1}" +
                "|vidH=${if (::videoContainer.isInitialized) videoContainer.height else -1}",
            "stage.measure",
            "page" to "${pageWidth}x$pageHeight",
            "portrait" to portrait,
            "measurable" to measurable,
            "pip" to isInPictureInPictureMode,
            "videoRect" to LivePageTrace.rect(portraitVideoRect),
            "listRect" to LivePageTrace.rect(portraitListRect),
            "videoContainer" to LivePageTrace.rect(
                if (::videoContainer.isInitialized) {
                    Rect(videoContainer.left, videoContainer.top, videoContainer.right, videoContainer.bottom)
                } else {
                    null
                },
            ),
            "barTop" to (if (::bottomBar.isInitialized && bottomBar.height > 0) bottomBar.top else -1),
            "slotTop" to (if (::danmakuListSlot.isInitialized) danmakuListSlot.top else -1),
            "slotHeight" to (if (::danmakuListSlot.isInitialized) danmakuListSlot.height else -1),
            "minListHeightPx" to dpToPx(PORTRAIT_LIST_MIN_HEIGHT_DP),
        )
        // 版式一变，气泡（贴在视频区中线上）的几何也要跟着走
        gestureHud.applyGeometry()
        // ★★第八批（用户实测第 4 条）：**视频带几何一变就把 PiP 参数重下发一次**。
        //   这里是本页"画面矩形"的**唯一收敛点**（进/出小窗、转屏、底栏换行、键盘 insets 变化、
        //   换清晰度改比例，最终都会走到它），所以挂在它末尾就覆盖了全部几何变化；
        //   内部按"比例 + 源矩形"的指纹比对，没变就一个字节都不发给系统（见 [syncPipParamsToVideoGeometry]）。
        //   缺了这一句，系统手上留着的永远是"上一次下发时"的画面矩形 —— 进/出小窗的动画
        //   与画面缩放都按旧几何走，用户看到的就是"小窗不占满、有黑边"。
        syncPipParamsToVideoGeometry()
    }

    /**
     * 把预留矩形写到列表槽的 LayoutParams 上（值没变就不动 —— 免得白触发一次 layout）。
     *
     * @param top    槽顶边（页内 y，px）
     * @param height 槽高（px，0 = 没有预留区）
     */
    private fun applyListSlot(top: Int, height: Int) {
        if (!::danmakuListSlot.isInitialized) return
        val lp = danmakuListSlot.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.topMargin == top && lp.height == height) return
        lp.gravity = Gravity.TOP or Gravity.START
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = height.coerceAtLeast(0)
        lp.topMargin = top.coerceAtLeast(0)
        danmakuListSlot.layoutParams = lp
    }

    /**
     * 竖屏视频区矩形（[LivePortraitStage] 契约，页内坐标 px）；非竖屏 / 没量到 = null。
     * 返回副本：契约是"只读"，不能让调用方改到内部这份（它每次版式变化都会被重写）。
     */
    override fun portraitVideoBounds(): Rect? = portraitVideoRect?.let { Rect(it) }

    /**
     * 竖屏留给弹幕列表的矩形（[LivePortraitStage] 契约，页内坐标 px）；非竖屏 / 没量到 = null。
     * 三条边界的定义与"为什么"都写在接口 KDoc 里（顶贴视频、底让底栏、左右整页宽）。
     *
     * ★它与 [danmakuListSlot] 是**同一块地方**：宿主走 `bindPortraitListArea(slot = …)` 拿的是槽的
     *   实际 layout 结果（更权威，且随 layout 自动跟进）；这里给的是同一块的数字版，
     *   给"只想读个数"的调用方（例如浮层要把滚动弹幕收进视频区时要一个基准高度）。
     */
    override fun portraitDanmakuListBounds(): Rect? = portraitListRect?.let { Rect(it) }

    /**
     * 把竖屏列表的**停靠锚点**交给弹幕宿主 —— 宿主的唯一接线口
     * （`LiveDanmakuOverlayHost.bindPortraitListArea`，只读它的公开 API，宿主一个字节都没碰）。
     *
     * ```kotlin
     * host.bindPortraitListArea(slot = danmakuListSlot, bottomBound = bottomBar)
     * ```
     * | 传什么 | 宿主拿它做什么 |
     * |---|---|
     * | `slot = `[danmakuListSlot] | 列表**铺满这块矩形**（顶边贴视频、底边让开底栏，见 [measurePortraitStage]） |
     * | `bottomBound = `[bottomBar] | 列表底边**绝不超过底栏顶边**（硬夹一道；输入法顶起底栏、底栏 1↔2 行变化都靠它兜） |
     *
     * ★为什么不自己算矩形、自己 setLayoutParams 到宿主的面板上：面板是宿主**私有的注入 View**，
     *   播放页看不见也拿不到（那正是"宿主自己管自己的 View"的边界）。给锚点 View 是宿主定的契约，
     *   而且它会给锚点挂 `OnLayoutChangeListener` —— 转屏、视频换比例、底栏换行、insets 变化
     *   宿主都会**自己重新量一次**，播放页不需要在每种变化里再通知它。
     * ★"不要切换按钮"这条不需要播放页做什么：宿主自带的那颗胶囊在 vc168 已经删掉了
     *   （`setDanmakuListEntryVisible` 现在是空实现），列表在竖屏是**常驻**的。
     * ★必须**在宿主建好之后**调（[setupPlayerAndDanmaku] 里它刚 `addView` 进 danmakuLayer），
     *   锚点属于播放页的视图树，早于 [buildUi] 也不存在。
     */
    private fun bindPortraitListArea() {
        danmakuHost?.bindPortraitListArea(
            slot = danmakuListSlot,
            bottomBound = bottomBar,
        )
    }

    private fun pageHeightPx(): Int =
        rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

    /**
     * 按视频比例摆放子 View 的 FrameLayout。
     *
     * 为什么需要：直播既有 16:9 也有竖屏主播（9:16），
     * 不按比例摆放就会变形。这里在 measure 阶段把子 View 量成"贴比例"的尺寸，
     * 再由 FrameLayout 的 CENTER gravity 居中（黑边留在两侧/上下）。
     *
     * ★本轮新增 [bandMaxHeightFraction]（竖屏"贴顶视频带"）：>0 时**容器自己**也按比例缩成一条带，
     *   而不是铺满整页 —— 这是"上面视频、下面弹幕列表"这块版式的落地处。横屏传 0，行为与改动前逐字一致。
     */
    private class AspectRatioFrameLayout(context: Context) : FrameLayout(context) {

        private companion object {
            /** 还没拿到真实视频比例时的兜底（16:9 = 直播最常见的形态） */
            const val DEFAULT_ASPECT_RATIO = 16f / 9f
        }

        /** 视频宽高比（宽/高）；<=0 表示还不知道，按容器撑满 */
        var videoAspectRatio: Float = 0f

        /**
         * ★竖屏"贴顶视频带"的高度上限（占页面高度的比例），0 = 关闭（横屏 / 现状）。
         *
         * 为什么高度算法放在 onMeasure 里、而不是在外面算好了 setLayoutParams：
         * "页面有多高"要等这一帧量完才知道，在外面算就得跟布局时机赛跑（转屏/分屏/折叠屏展开
         * 那几种时机正是历史上"几何还是旧方向"的 bug 来源）。放在这里，用的永远是当前这一帧的真值。
         */
        var bandMaxHeightFraction: Float = 0f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val child = getChildAt(0) ?: return
            val ratio = videoAspectRatio
            val band = bandMaxHeightFraction > 0f
            // 还没拿到真实比例时：老行为是"容器撑满"（等于把画面拉伸铺满），带子模式下先按 16:9 摆一条 ——
            // 宁可先按最常见比例摆一帧，也不要把画面拉成整页高
            if (ratio <= 0f && !band) return
            val containerWidth = View.MeasureSpec.getSize(widthMeasureSpec)
            val containerHeight = View.MeasureSpec.getSize(heightMeasureSpec)
            if (containerWidth <= 0 || containerHeight <= 0) return
            val effectiveRatio = if (ratio > 0f) ratio else DEFAULT_ASPECT_RATIO
            // 带子模式下，容器的"可用高度"= 宽 ÷ 比例，并封顶 bandMaxHeightFraction × 页高
            val height = if (band) {
                (containerWidth / effectiveRatio).roundToInt()
                    .coerceAtMost((containerHeight * bandMaxHeightFraction).roundToInt())
                    .coerceAtLeast(1)
            } else {
                containerHeight
            }
            val containerRatio = containerWidth.toFloat() / height.toFloat()
            val childWidth: Int
            val childHeight: Int
            if (containerRatio > effectiveRatio) {
                // 容器更宽 → 高度顶满，左右留黑边
                childHeight = height
                childWidth = (height * effectiveRatio).roundToInt()
            } else {
                childWidth = containerWidth
                childHeight = (containerWidth / effectiveRatio).roundToInt()
            }
            child.measure(
                View.MeasureSpec.makeMeasureSpec(childWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(childHeight, View.MeasureSpec.EXACTLY),
            )
            // ★带子：容器自己的高度也要跟着改小（super.onMeasure 量出来的是"铺满整页"）
            if (band) setMeasuredDimension(containerWidth, height)
        }
    }
}
