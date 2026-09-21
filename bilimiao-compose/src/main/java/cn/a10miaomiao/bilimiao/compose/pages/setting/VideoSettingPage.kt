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
import cn.a10miaomiao.bilimiao.compose.components.preference.textIntPreference
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.listPreference
import me.zhanghai.compose.preference.preference
import me.zhanghai.compose.preference.preferenceCategory
import me.zhanghai.compose.preference.switchPreference
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
                    Text("切换应用或锁屏后继续播放音频")
                },
                defaultValue = false,
            )
            switchPreference(
                key = SettingPreferences.PlayerPipOnBackground.name,
                title = {
                    Text("小窗播放")
                },
                summary = {
                    Text("退出APP时自动转为小窗悬浮播放，回到APP后自动恢复")
                },
                defaultValue = false,
            )
            switchPreference(
                key = SettingPreferences.PlayerAudioFocus.name,
                title = {
                    Text("占用音频焦点")
                },
                summary = {
                    Text("关闭后可以与其它APP同时播放")
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
                    Text("不能播放时，换个格式试试吧（当前：${viewModel.fnvalSelectionName(value)}）")
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
                        "当前：${viewModel.dashBufferSecSelectionName(value)}。" +
                            "缓冲越久越抗卡、但更吃内存（DASH/MP4 都生效；堆内上限固定 64MB，不会因此爆内存）"
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
            switchPreference(
                key = SettingPreferences.PlayerNotification.name,
                title = {
                    Text("显示通知栏播放器控制器")
                },
                summary = {
                    if (it) {
                        Text(text = "播放时才会显示")
                    } else {
                        Text(text = "通知栏播放器已关闭")
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
                    Text("打开或关闭视频详情时自动进行的操作")
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
                    Text("可以多个选项组合选择")
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
                    Text("播放完一个视频后，随机播放下一个视频，单个视频循环时无效")
                },
                defaultValue = false,
            )
            listPreference(
                key = SettingPreferences.PlayerFullMode.name,
                title = {
                    Text("全屏播放屏幕方向")
                },
                summary = {
                    Text("长按播放器全屏按钮可打开此选项")
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
                    Text("长按屏幕时临时加快的倍率（当前 ${longPressSpeedText(it)}）")
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
                    Text("双击屏幕左/右侧的跳转秒数；通知栏 ± 按钮跟随该步长。选\"关闭\"则双击任意位置都是播放/暂停（通知栏 ± 仍用默认 10 秒）")
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
                        Text("拖动进度时在画面中央显示该时间点的缩略图（对齐 PiliPlus/B 站；视频没有缩略图数据时不显示）")
                    } else {
                        Text("已关闭：拖动时只显示时间气泡")
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
                        Text(text = "已启用，播放时可拖拽小屏播放器")
                    } else {
                        Text(text = "启用后，小屏状态时播放器手势无效")
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
                        Text("字幕功能已打开")
                    } else {
                        Text("字幕功能已关闭")
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
                    Text("此AI字幕是指UP主手动生成的AI字幕，并非每个视频都有")
                },
                defaultValue = false,
            )
            // 字幕字号：可手输纯数字（数字键盘），建议 12~30，默认 16
            textIntPreference(
                key = SettingPreferences.PlayerSubtitleTextSize.name,
                defaultValue = DEFAULT_SUBTITLE_TEXT_SIZE,
                title = {
                    Text("字幕字号")
                },
                label = " sp",
                summary = { value ->
                    val v = value.coerceIn(MIN_SUBTITLE_TEXT_SIZE, MAX_SUBTITLE_TEXT_SIZE)
                    Text(
                        "当前 ${v}sp（建议 $MIN_SUBTITLE_TEXT_SIZE~$MAX_SUBTITLE_TEXT_SIZE，" +
                            "默认 $DEFAULT_SUBTITLE_TEXT_SIZE）。超出范围会按边界值生效"
                    )
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