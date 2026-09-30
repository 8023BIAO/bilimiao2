package cn.a10miaomiao.bilimiao.compose.pages.setting

import android.os.Build
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
// `val x by state` 需要 getValue 操作符在作用域内（本文件是逐个导入，不是 runtime.* —— 漏了会报
// "Type 'State<...>' has no method 'getValue(...)', so it cannot serve as a delegate"）
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.navOptions
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.preference.rememberPreferenceFlow
import cn.a10miaomiao.bilimiao.compose.components.preference.customSetsPreference
import cn.a10miaomiao.bilimiao.compose.components.preference.multiSelectIntPreference
import cn.a10miaomiao.bilimiao.compose.components.preference.sliderIntPreference
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.listPreference
import me.zhanghai.compose.preference.preference
import me.zhanghai.compose.preference.preferenceCategory
import me.zhanghai.compose.preference.switchPreference
import cn.a10miaomiao.bilimiao.compose.base.BottomSheetState
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
class VideoSettingPage : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: VideoSettingPageViewModel = diViewModel()
        VideoSettingPageContent(viewModel)
    }
}

private class VideoSettingPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val fragment by instance<Fragment>()
    private val pageNavigation by instance<PageNavigation>()

    /**
     * 定时关闭：页面本体还是 [AutoStopTimerPage]（"懒得重构"就不动了），
     * 只是把入口收进「播放设置」—— 用户在播放器点齿轮进来时找不到它。
     */
    fun toAutoStopTimerPage() {
        pageNavigation.navigate(AutoStopTimerPage())
    }

    private val fnvalSelection = mapOf(
        SettingConstants.PLAYER_FNVAL_DASH to AnnotatedString("dash(支持4K)"),
        SettingConstants.PLAYER_FNVAL_MP4 to AnnotatedString("mp4(不支持2K及以上)"),
    )

    fun fnvalSelectionName(value: Int) = fnvalSelection[value] ?: AnnotatedString(value.toString())
    val fnvalSelectionList = fnvalSelection.keys.toList()


    // DASH播放器缓冲选择
    private val dashBufferSecSelection = mapOf(
        0 to AnnotatedString("系统默认(50秒)"),
        10 to AnnotatedString("10秒"),
        15 to AnnotatedString("15秒"),
        20 to AnnotatedString("20秒"),
        30 to AnnotatedString("30秒"),
        50 to AnnotatedString("50秒"),
    )
    fun dashBufferSecSelectionName(value: Int) = dashBufferSecSelection[value] ?: AnnotatedString("${value}秒")
    val dashBufferSecSelectionList = dashBufferSecSelection.keys.toList()


    private val fullModeSelection = mapOf(
        SettingConstants.PLAYER_FULL_MODE_AUTO to AnnotatedString("跟随视频"),
        SettingConstants.PLAYER_FULL_MODE_UNSPECIFIED to AnnotatedString("跟随系统"),
        SettingConstants.PLAYER_FULL_MODE_SENSOR_LANDSCAPE to AnnotatedString("横向全屏(自动)"),
        SettingConstants.PLAYER_FULL_MODE_LANDSCAPE to AnnotatedString("横向全屏(固定方向1)"),
        SettingConstants.PLAYER_FULL_MODE_REVERSE_LANDSCAPE to AnnotatedString("横向全屏(固定方向2)"),
    )

    fun fullModeSelectionName(value: Int) =
        fullModeSelection[value] ?: AnnotatedString(value.toString())

    val fullModeSelectionList = fullModeSelection.keys.toList()


    private val openModeSelection = mapOf(
        SettingConstants.PLAYER_OPEN_MODE_AUTO_PLAY to AnnotatedString("无视频播放时，自动播放"),
        SettingConstants.PLAYER_OPEN_MODE_AUTO_REPLACE to AnnotatedString("正在播放时，自动替换播放"),
        SettingConstants.PLAYER_OPEN_MODE_AUTO_REPLACE_PAUSE to AnnotatedString("暂停播放时，自动替换播放"),
        SettingConstants.PLAYER_OPEN_MODE_AUTO_REPLACE_COMPLETE to AnnotatedString("完成播放时，自动替换播放"),
        SettingConstants.PLAYER_OPEN_MODE_AUTO_CLOSE to AnnotatedString("退出详情页时，自动关闭"),
        SettingConstants.PLAYER_OPEN_MODE_AUTO_FULL_SCREEN to AnnotatedString("设备竖屏状态时，自动全屏播放"),
        SettingConstants.PLAYER_OPEN_MODE_AUTO_FULL_SCREEN_LANDSCAPE to AnnotatedString("设备横屏状态时，自动全屏播放"),

    )

    fun openModeSelectionName(value: Int) =
        openModeSelection[value] ?: AnnotatedString(value.toString())

    val openModeSelectionList = openModeSelection.keys.toList()

    private val orderSelection = mapOf(
        SettingConstants.PLAYER_ORDER_LOOP to AnnotatedString("循环播放（有勾选下列选项时为列表循环，无勾选时为单个循环）"),
        SettingConstants.PLAYER_ORDER_NEXT_P to AnnotatedString("自动下一P"),
        SettingConstants.PLAYER_ORDER_NEXT_VIDEO to AnnotatedString("自动下一个视频"),
        SettingConstants.PLAYER_ORDER_NEXT_EPISODE to AnnotatedString("自动下一集（番剧）"),
    )

    fun orderSelectionName(value: Int) = orderSelection[value] ?: AnnotatedString(value.toString())
    val orderSelectionList = orderSelection.keys.toList()

    private val bottomProgressBarShowSelection =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mapOf(
                SettingConstants.PLAYER_BOTTOM_PROGRESS_BAR_SHOW_IN_SMALL
                        to AnnotatedString("小屏播放时，显示底部进度条"),
                SettingConstants.PLAYER_BOTTOM_PROGRESS_BAR_SHOW_IN_FULL
                        to AnnotatedString("全屏播放时，显示底部进度条"),
                SettingConstants.PLAYER_BOTTOM_PROGRESS_BAR_SHOW_IN_PIP
                        to AnnotatedString("画中画(应用外小窗)模式，显示底部进度条"),
            )
        } else {
            mapOf(
                SettingConstants.PLAYER_BOTTOM_PROGRESS_BAR_SHOW_IN_SMALL
                        to AnnotatedString("小屏播放时，显示底部进度条"),
                SettingConstants.PLAYER_BOTTOM_PROGRESS_BAR_SHOW_IN_FULL
                        to AnnotatedString("全屏播放时，显示底部进度条"),
            )
        }

    fun bottomProgressBarShowName(value: Int) = bottomProgressBarShowSelection[value]
        ?: AnnotatedString(value.toString())

    val bottomProgressBarShowSelectionList = bottomProgressBarShowSelection.keys.toList()


}


@Composable
private fun VideoSettingPageContent(
    viewModel: VideoSettingPageViewModel
) {
    PageConfig(
        title = "播放设置"
    )
    val windowStore: WindowStore by rememberInstance()
    // 本页有两种出场方式：① 设置首页里当整页打开 ② 播放器齿轮按钮 openBottomSheet(VideoSettingPage())
    // 在弹层里时它不在 nav 返回栈上，往 nav 栈 navigate 会被弹层盖住 → 要判断一下（见下面「定时关闭」）
    val bottomSheetState: BottomSheetState by rememberInstance()
    val bottomSheetPage by bottomSheetState.page.collectAsStateWithLifecycle()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val context = LocalContext.current
    val dataStore = remember {
        SettingPreferences.run { context.dataStore }
    }

    val prefFlow = rememberPreferenceFlow(dataStore)
    ProvidePreferenceLocals(
        flow = prefFlow
    ) {
        val preferences = prefFlow.collectAsStateWithLifecycle().value
        val fnval = (preferences[SettingPreferences.PlayerFnval.name] as? Int)
            ?: SettingConstants.PLAYER_FNVAL_MP4

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = windowInsets.leftDp.dp,
                    end = windowInsets.rightDp.dp,
                )
        ) {
            item("top") {
                Spacer(
                    modifier = Modifier.height(windowInsets.topDp.dp)
                )
            }
            preferenceCategory(
                key = "player",
                title = {
                    Text("播放器设置")
                }
            )
            switchPreference(
                key = SettingPreferences.PlayerBackground.name,
                title = {
                    Text("后台播放")
                },
                summary = {
                    Text("切到后台或锁屏后继续播放")
                },
                defaultValue = false,
            )
            switchPreference(
                key = SettingPreferences.PlayerPipOnBackground.name,
                title = {
                    Text("小窗播放")
                },
                summary = {
                    Text("退出应用后自动小窗播放")
                },
                defaultValue = false,
            )
            switchPreference(
                key = SettingPreferences.PlayerAudioFocus.name,
                title = {
                    Text("占用音频焦点")
                },
                summary = {
                    Text("关闭后可与其它应用同时播放")
                },
                defaultValue = true,
            )

            preferenceCategory(
                key = "source",
                title = {
                    Text("视频源设置")
                }
            )
            listPreference(
                key = SettingPreferences.PlayerFnval.name,
                title = {
                    Text("视频格式选择")
                },
                // ★ 把当前值写进摘要（用户要求）：不然每次都要点进去才知道现在选的是哪个。
                //   listPreference 的 summary 回调会给出"当前值"，用它拼出来最准（不会滞后）。
                summary = { value ->
                    Text("播放异常时换个格式（当前：${viewModel.fnvalSelectionName(value)}）")
                },
                defaultValue = SettingConstants.PLAYER_FNVAL_DASH,
                values = viewModel.fnvalSelectionList,
                valueToText = viewModel::fnvalSelectionName
            )
            // 播放缓冲时长（原名"DASH 缓冲时长"）：**对 DASH 和 MP4 都生效** ——
            // Media3ExoPlayerManager 的 LoadControl 是给整个 ExoPlayer 设的，不分源类型
            // （实机日志可证：MP4 播放时同样打印 min/max=... targetBufferBytes=64MB），
            // 所以这里不隐藏，只在文案里说清楚 + 显示当前秒数。
            listPreference(
                key = SettingPreferences.PlayerDashBufferSec.name,
                title = {
                    Text("播放缓冲时长")
                },
                summary = { value ->
                    Text(
                        "当前：${viewModel.dashBufferSecSelectionName(value)}，" +
                            "缓冲越久越抗卡、越占内存"
                    )
                },
                defaultValue = 15,
                values = viewModel.dashBufferSecSelectionList,
                valueToText = viewModel::dashBufferSecSelectionName
            )
            preferenceCategory(
                key = "control",
                title = {
                    Text("播放控制设置")
                }
            )
            // 音量手势滑动距离：整条音量 = 画面高的百分之多少（30~200%，步进 5，默认 50%）。
            // 调小更灵敏、调大更迟钝；点播与直播共用同一个键；亮度不接这个设置。
            sliderIntPreference(
                key = SettingPreferences.PlayerVolumeSwipePercent.name,
                title = {
                    Text("音量手势滑动距离")
                },
                defaultValue = SettingPreferences.PLAYER_VOLUME_SWIPE_PERCENT_DEFAULT,
                valueRange = 30..200,
                valueSteps = 33,
                valueText = {
                    Text("$it%")
                },
                summary = {
                    Text("调小更灵敏，调大更迟钝")
                },
            )
            // 亮度手势滑动距离：整条亮度 = 屏高的几倍（0.5×~6.0×，步进 0.5×，默认 3.0× = 今天的手感）。
            // 调小更灵敏、调大更迟钝；点播与直播共用同一个键。
            sliderIntPreference(
                key = SettingPreferences.PlayerBrightnessSwipeTenths.name,
                title = {
                    Text("亮度手势滑动距离")
                },
                defaultValue = SettingPreferences.PLAYER_BRIGHTNESS_SWIPE_TENTHS_DEFAULT,
                valueRange = 5..60,
                valueSteps = 10,
                valueText = {
                    Text("${it / 10f}×")
                },
                summary = {
                    Text("调小更灵敏，调大更迟钝")
                },
            )
            // 定时关闭：原来只在设置首页一级挂着，用户在播放器点齿轮进来找不到它（用户反馈）
            preference(
                key = "auto_stop_timer",
                title = { Text("定时关闭") },
                summary = { Text("播够指定时长自动暂停") },
                onClick = {
                    if (bottomSheetPage is VideoSettingPage) {
                        // 弹层里换页：整页 navigate 会被弹层盖住
                        bottomSheetState.open(AutoStopTimerPage())
                    } else {
                        viewModel.toAutoStopTimerPage()
                    }
                },
            )
            switchPreference(
                key = SettingPreferences.PlayerNotification.name,
                title = {
                    Text("显示通知栏播放器控制器")
                },
                summary = {
                    if (it) {
                        Text(text = "仅在播放时显示")
                    } else {
                        Text(text = "已关闭")
                    }
                },
                defaultValue = true,
            )
            multiSelectIntPreference(
                key = SettingPreferences.PlayerOpenMode.name,
                title = {
                    Text("播放器自动控制")
                },
                summary = {
                    Text("打开或关闭详情页时的自动操作")
                },
                values = viewModel.openModeSelectionList,
                defaultValue = SettingConstants.PLAYER_OPEN_MODE_DEFAULT,
                valueToText = viewModel::openModeSelectionName,
            )
            multiSelectIntPreference(
                key = SettingPreferences.PlayerOrder.name,
                title = {
                    Text("播放器播放顺序")
                },
                summary = {
                    Text("可多选组合")
                },
                defaultValue = SettingConstants.PLAYER_ORDER_DEFAULT,
                values = viewModel.orderSelectionList,
                valueToText = viewModel::orderSelectionName
            )
            switchPreference(
                key = SettingPreferences.PlayerOrderRandom.name,
                title = {
                    Text("随机播放")
                },
                summary = {
                    Text("播完后随机播下一个（单个循环时无效）")
                },
                defaultValue = false,
            )
            listPreference(
                key = SettingPreferences.PlayerFullMode.name,
                title = {
                    Text("全屏播放屏幕方向")
                },
                summary = {
                    Text("长按全屏按钮也可打开")
                },
                defaultValue = SettingConstants.PLAYER_FULL_MODE_AUTO,
                values = viewModel.fullModeSelectionList,
                valueToText = viewModel::fullModeSelectionName
            )
            multiSelectIntPreference(
                key = SettingPreferences.PlayerBottomProgressBarShow.name,
                title = {
                    Text("底部进度条显示控制")
                },
                defaultValue = 0,
                values = viewModel.bottomProgressBarShowSelectionList,
                valueToText = viewModel::bottomProgressBarShowName
            )
            customSetsPreference(
                key = SettingPreferences.PlayerSpeedValues.name,
                title = {
                    Text("自定义倍速菜单")
                },
                defaultValue = SettingConstants.PLAYER_SPEED_SETS,
                valueText = {
                    Text(
                        text = it + "倍速",
                        modifier = Modifier.widthIn(min = 48.dp),
                        textAlign = TextAlign.Center,
                    )
                },
                valueCanEdit = {
                    it !in SettingConstants.PLAYER_SPEED_SETS
                },
                canAdd = {
                    it.size < 10
                }
            )
            listPreference(
                key = SettingPreferences.PlayerLongPressSpeed.name,
                title = {
                    Text("长按倍速倍率")
                },
                summary = {
                    Text("当前 ${longPressSpeedText(it)}，长按屏幕临时加速")
                },
                defaultValue = 300,
                values = listOf(150, 200, 300, 400),
                valueToText = { value ->
                    AnnotatedString(longPressSpeedText(value))
                }
            )
            listPreference(
                key = SettingPreferences.PlayerDoubleTapSeek.name,
                title = {
                    Text("快进/快退步长")
                },
                summary = {
                    Text("双击左右侧跳转的秒数；选「关闭」则双击为播放/暂停")
                },
                // 默认"关闭"：双击屏幕很容易误触（用户要求）
                defaultValue = 0,
                // media3 只自带 5/10/15/30 的数字图标；0 = 关闭双击快进快退
                values = listOf(0, 5, 10, 15, 30),
                valueToText = { value ->
                    AnnotatedString(if (value > 0) "$value 秒" else "关闭")
                }
            )
            // 「播放器定时关闭」已提到 设置 → ① 播放 → 定时关闭（避免同一项两处出现）
            switchPreference(
                key = SettingPreferences.PlayerSeekPreviewShow.name,
                title = {
                    Text("拖动进度显示预览图")
                },
                summary = {
                    if (it) {
                        Text("拖动进度时显示缩略图（无缩略图数据时不显示）")
                    } else {
                        Text("已关闭，仅显示时间气泡")
                    }
                },
                defaultValue = true,
            )

            // ── 空降助手入口已移到「设置 → 实验性功能 → 空降助手」──
            // 用户反馈放在播放设置里藏得太深（进了播放设置也不一定往下翻）。
            // 设置页在 SponsorBlockSettingPage，逻辑一行没动，只是换了个入口位置。

            preferenceCategory(
                key = "download",
                title = {
                    Text("下载设置")
                }
            )
            listPreference(
                key = SettingPreferences.DownloadQualityMode.name,
                title = {
                    Text("默认下载画质")
                },
                summary = {
                    Text("打开下载弹窗时自动选择的画质")
                },
                defaultValue = 0,
                values = listOf(0, 1, 2, 3),
                valueToText = { value ->
                    AnnotatedString(
                        when (value) {
                            0 -> "手动选择"
                            1 -> "最高画质"
                            2 -> "最低画质"
                            3 -> "固定画质"
                            else -> "手动选择"
                        }
                    )
                }
            )
            // fixed_quality值的设置显示
          /*   preference(
                key = "download_fixed_quality_hint",
                title = {
                    Text("固定画质值 (quality)")
                },
                summary = {
                    Text("查看视频下载弹窗了解可选quality值，常见: 16=360P, 32=480P, 64=720P, 80=1080P, 112=1080P+, 116=1080P60, 120=4K")
                },
            )*/

            preferenceCategory(
                key = "small",
                title = {
                    Text(text = "横屏状态小屏设置")
                }
            )
            switchPreference(
                key = SettingPreferences.PlayerSmallDraggable.name,
                title = {
                    Text(text = "小屏时整个播放器可拖拽")
                },
                summary = {
                    if (it) {
                        Text(text = "已开启，小屏可拖拽")
                    } else {
                        Text(text = "开启后小屏手势失效")
                    }
                },
                defaultValue = false,
            )
            sliderIntPreference(
                key = SettingPreferences.PlayerSmallShowArea.name,
                title = {
                    Text(text = "小屏时播放面积")
                },
                valueRange = 150..600,
                defaultValue = 480,
                valueText = {
                    Text(text = it.toString())
                }
            )
            sliderIntPreference(
                key = SettingPreferences.PlayerHoldShowArea.name,
                title = {
                    Text(text = "小屏挂起后播放面积")
                },
                valueRange = 100..300,
                defaultValue = 130,
                valueText = {
                    Text(text = it.toString())
                }
            )

            preferenceCategory(
                key = "subtitle",
                title = {
                    Text("字幕设置")
                }
            )
            switchPreference(
                key = SettingPreferences.PlayerSubtitleShow.name,
                title = {
                    Text("字幕显示")
                },
                summary = {
                    if (it) {
                        Text("已开启")
                    } else {
                        Text("已关闭")
                    }
                },
                defaultValue = true,
            )
            switchPreference(
                key = SettingPreferences.PlayerAiSubtitleShow.name,
                title = {
                    Text("AI字幕显示")
                },
                summary = {
                    Text("UP 主上传的 AI 字幕，不是每个视频都有")
                },
                defaultValue = false,
            )
            // 字幕字号：拖动条 12~30sp，valueSteps=17 → 每段正好 1sp（12…30 全整数），默认 16
            sliderIntPreference(
                key = SettingPreferences.PlayerSubtitleTextSize.name,
                title = {
                    Text("字幕字号")
                },
                valueRange = MIN_SUBTITLE_TEXT_SIZE..MAX_SUBTITLE_TEXT_SIZE,
                defaultValue = DEFAULT_SUBTITLE_TEXT_SIZE,
                valueSteps = 17,
                valueText = {
                    Text("${it}sp")
                },
            )

            item("bottom") {
                Spacer(
                    modifier = Modifier.height(
                        (windowInsets.bottomDp + windowStore.bottomAppBarHeightDp).dp
                    )
                )
            }
        }
    }
}

// 字幕字号范围（sp）：16 是布局里原来的写死值，12 已经偏小、30 在手机全屏上接近上限
internal const val DEFAULT_SUBTITLE_TEXT_SIZE = 16
internal const val MIN_SUBTITLE_TEXT_SIZE = 12
internal const val MAX_SUBTITLE_TEXT_SIZE = 30

/** 长按倍速倍率（存 ×100 的整数）→ 显示文案：150 → "1.5×"，300 → "3×" */
private fun longPressSpeedText(scale: Int): String {
    val value = scale / 100f
    return if (value == value.toInt().toFloat()) "${value.toInt()}×" else "$value×"
}