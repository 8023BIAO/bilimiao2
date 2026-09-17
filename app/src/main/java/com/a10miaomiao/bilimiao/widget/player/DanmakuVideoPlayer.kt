package com.a10miaomiao.bilimiao.widget.player

import android.app.Activity
import android.app.Dialog
import android.app.Service
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.Shape
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.AttributeSet
import android.view.DisplayCutout
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.a10miaomiao.bilimiao.comm.utils.SponsorDiag
import com.a10miaomiao.bilimiao.R
import com.a10miaomiao.bilimiao.comm.apis.PlayerAPI
import com.a10miaomiao.bilimiao.comm.delegate.helper.StatusBarHelper
import com.a10miaomiao.bilimiao.comm.delegate.player.PlayerSeekBus
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorCategory
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorSegment
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorSkipType
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.service.PlaybackService
import com.a10miaomiao.bilimiao.comm.toast
import com.a10miaomiao.bilimiao.comm.utils.ImageSaveUtil
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.config.config
import com.a10miaomiao.bilimiao.widget.menu.CheckPopupMenu
import com.shuyu.gsyvideoplayer.utils.CommonUtil
import com.shuyu.gsyvideoplayer.utils.Debuger
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuView
// 【已移除】新弹幕引擎import — V2引擎已废弃
// import com.a10miaomiao.danmaku.DanmakuView as NewDanmakuView
// import com.a10miaomiao.danmaku.data.TextDanmakuData
// import com.a10miaomiao.danmaku.util.LAYER_TYPE_SCROLL
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import splitties.dimensions.dip
import splitties.views.backgroundColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min


class DanmakuVideoPlayer : StandardGSYVideoPlayer {

    enum class PlayerMode {
        SMALL_TOP,
        SMALL_FLOAT,
        FULL,
    }
    // 主题颜色
    private var mThemeColor = Color.BLUE
    val themeColor: Int get() = mThemeColor  // 公开给 PlayerController 弹窗用
    // 弹幕引擎
    private val mDanmakuView: DanmakuView by lazy { findViewById(R.id.danmaku_view) }
    // 【已移除】新弹幕引擎变量 — V2引擎已废弃
    // var useNewDanmakuEngine = false
    // private val mNewDanmakuView: NewDanmakuView by lazy { findViewById(R.id.new_danmaku_view) }

    // 根布局组件
    private val mRootLayout: RelativeLayout by lazy { findViewById(R.id.root_layout) }

    // 视频渲染容器
    private val mSurfaceContainer: FrameLayout by lazy {
        findViewById(com.shuyu.gsyvideoplayer.R.id.surface_container)
    }

    // 双指缩放/平移/旋转控制器
    private lateinit var pinchToZoom: PinchToZoomHelper

    // 还原屏幕按钮
    private val mRestoreScaleBtn: TextView by lazy { findViewById(R.id.restore_scale) }

    // 小窗顶部拖动横条
    private val mDragBarLayout: FrameLayout by lazy { findViewById(R.id.layout_drag_bar) }
    private val mDragBar: View by lazy { findViewById(R.id.drag_bar) }
    private val mHoldUpBtn: View by lazy { findViewById(R.id.hold_up) }

    // 顶栏更多按钮
    private val mMoreBtn: View by lazy { findViewById(R.id.more) }

    // 投屏按钮
    private val mCastBtnLayout: ViewGroup by lazy { findViewById(R.id.cast_btn_layout) }
    private val mCastBtnText: TextView by lazy { findViewById(R.id.cast_btn_text) }

    // DlnaManager（由外部设置）
    var dlnaManager: DlnaManager? = null
    var onCastClick: ((View) -> Unit)? = null

    /** 当前播放视频的URL（供DLNA投屏使用） */
    val currentVideoUrl: String get() = mUrl

    // 底栏布局
    private val mBottomLayout: LinearLayout by lazy { findViewById(R.id.layout_bottom) }

    // 全屏时底栏布局
    private val mFullModeBottomContainer: ViewGroup by lazy { findViewById(R.id.layout_full_mode_bottom) }

    // 底栏播放按钮
    private val mButtomPlay: ImageView by lazy { findViewById(R.id.buttom_play) }

    // 底部字幕
    private val mBottomSubtitleTV: TextView by lazy { findViewById(R.id.bottom_subtitle) }

    /**
     * 字幕字号（sp）。默认 16 = 布局里原来的写死值；
     * 设置页可改（12~30），由 PlayerController 下发。改动立即生效，不用重进播放器。
     */
    var subtitleTextSizeSp: Float = 16f
        set(value) {
            val v = value.coerceIn(12f, 30f)
            if (field == v) return
            field = v
            applySubtitleTextSize()
        }

    /** 把字号应用到字幕 TextView（布局里是 16sp，这里覆盖掉） */
    private fun applySubtitleTextSize() {
        runCatching { mBottomSubtitleTV.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp) }
    }

    // 字幕开关
    private val mSubtitleSwitch: ViewGroup by lazy { findViewById(R.id.subtitle_switch) }

    // 字幕开关图标
    private val mSubtitleSwitchIV: ImageView by lazy { findViewById(R.id.subtitle_switch_icon) }

    // 字幕开关文字
    private val mSubtitleSwitchTV: TextView by lazy { findViewById(R.id.subtitle_switch_text) }

    // AI 原声翻译开关（底栏，只有视频支持 AI 翻译时才显示）
//     private val mAiTranslateSwitch: ViewGroup by lazy { findViewById(R.id.ai_translate_switch) }
//     private val mAiTranslateSwitchIV: ImageView by lazy { findViewById(R.id.ai_translate_switch_icon) }
//     private val mAiTranslateSwitchTV: TextView by lazy { findViewById(R.id.ai_translate_switch_text) }

    // 听视频（仅音频）开关 + 黑屏遮罩
    private val mAudioOnlySwitch: ViewGroup by lazy { findViewById(R.id.audio_only_switch) }
    private val mAudioOnlySwitchIV: ImageView by lazy { findViewById(R.id.audio_only_switch_icon) }
    private val mAudioOnlySwitchTV: TextView by lazy { findViewById(R.id.audio_only_switch_text) }
    private val mAudioOnlyOverlay: View by lazy { findViewById(R.id.audio_only_overlay) }

    // 截图
    private val mScreenshotSwitch: ViewGroup by lazy { findViewById(R.id.screenshot_switch) }

    // 弹幕开关
    private val mDanmakuSwitch: ViewGroup by lazy { findViewById(R.id.danmaku_switch) }

    // 弹幕开关图标
    private val mDanmakuSwitchIV: ImageView by lazy { findViewById(R.id.danmaku_switch_icon) }

    // 弹幕开关文字
    private val mDanmakuSwitchTV: TextView by lazy { findViewById(R.id.danmaku_switch_text) }

    private val mMiniSendDanmakuIV: ImageView by lazy { findViewById(R.id.send_danmaku_mini) }
    private val mSendDanmakuTV: TextView by lazy { findViewById(R.id.send_danmaku) }

    // 清晰度
    private val mQuality: ViewGroup by lazy { findViewById(R.id.quality) }

    // 清晰度文字
    private val mQualityTV: TextView by lazy { findViewById(R.id.quality_text) }

    // 倍速
    private val mPlaySpeed: ViewGroup by lazy { findViewById(R.id.play_speed) }

    // 倍速文字名称
    private val mPlaySpeedName: TextView by lazy { findViewById(R.id.play_speed_name) }

    // 倍速文字值
    private val mPlaySpeedValue: TextView by lazy { findViewById(R.id.play_speed_value) }

    // ===== 章节管理 =====
    val chapterManager = ChapterManager(this)

    init {
    }

    // 锁定按钮
    private val mLock: ViewGroup by lazy { findViewById(R.id.lock) }

    // 锁定时控制容器
    private val mLockContainer: ViewGroup by lazy { findViewById(R.id.layout_lock_screen) }

    // 左边解锁按钮
    private val mUnlockLeftIV: ImageView by lazy { findViewById(R.id.unlock_left) }

    // 右边解锁按钮
    private val mUnlockRightIV: ImageView by lazy { findViewById(R.id.unlock_right) }

    // 倍数播放提示
    private val mSpeedTips: LinearLayout by lazy { findViewById(R.id.speed_tips) }

    // 倍数播放提示图标
    private val mSpeedTipsIV: ImageView by lazy { findViewById(R.id.speed_tips_icon) }

    // 拓展按钮布局
    private val mExpandBtnLayout: LinearLayout by lazy { findViewById(R.id.expand_btn_layout) }

    // 拓展按钮文本
    private val mExpandBtnTV: TextView by lazy { findViewById(R.id.expand_btn_text) }

    // 定时关闭倒计时显示
    private val mAutoStopTimerTV: TextView by lazy { findViewById(R.id.auto_stop_timer) }

    // 弹幕时间与播放器时间同步
    private val mDanmakuTime = object : DanmakuTimer() {
        private var lastTime = 0L
        override fun currMillisecond(): Long {
            lastTime = try {
                gsyVideoManager.currentPosition
            } catch (e: Exception) {
                0L
            }
            return lastTime
        }

        override fun update(curr: Long): Long {
            lastInterval = curr - lastTime
            return lastInterval
        }
    }

    private var mDisplayCutout: DisplayCutout? = null

    // 字幕源列表
    var subtitleSourceList = emptyList<SubtitleSourceInfo>()
        set(value) {
            field = value
            updateSubtitleSourceList()
        }

    // 当前选中字幕
    var currentSubtitleSource: SubtitleSourceInfo? = null
        set(value) {
            field = value
            updateCurrentSubtitleSource()
        }

    /** 长按倍速的倍率（默认 3×；设置页可改，由 PlayerController.initVideoSetting 下发） */
    var longPressSpeedMultiplier = 3f

    /**
     * 续播账本：我们自己的"最后位置"。
     *
     * 为什么不能只靠 GSY：它只有两个**一次性、条件写**的槽（源码 v13.0.0）——
     *  - `mCurrentPosition`：只有 `onVideoPause()` 在底层 `isPlaying()` 为真时才写（:518-531），
     *    暂停中/缓冲中调用它等于什么都没记；被 `onVideoResume()` 读一次就清零（:562）；
     *  - `mSeekOnStart`：默认 -1，只在 `startAfterPrepared()` 里被消费一次然后清零（:844-846）。
     * 实测：暂停→切走→回来时位置还是 89108ms，随后 GSY 在 surface 重建时自己
     * 重新 prepare，两个槽都是空的 → 从 0 开始播。这就是"从 0 开始"的根因。
     *
     * 所以账本由我们记（暂停/退出/seek/播放中都记），槽只当"投递给 GSY 的通道"。
     */
    private var lastGoodPositionMs = 0L
    private var lastGoodUpdateAt = 0L

    /**
     * "正在 prepare" 标记。
     * 弹幕计时器跑在弹幕渲染线程上，能在 `super.startAfterPrepared()` 的
     * `setStateAndUi(PLAYING)` 与紧随其后的 `seekTo(mSeekOnStart)` 之间插进来 ——
     * 那时新播放器刚起、位置还是 0 附近，它会把投递槽写成 0/小值，
     * 于是"换清晰度从 0 开始播"。prepare 期间一律不许计时器碰槽。
     */
    @Volatile
    private var isPreparing = false

    /**
     * 用户的"暂停意图"。
     *
     * GSY 重新 prepare 后默认是**自动开播**的（`mStartAfterPrepared` 默认 true），
     * 暂停着切后台再回来，surface 重建就会自己播起来。用这个标记在 prepare 后按回暂停。
     * 更新点：onVideoPause → true；onVideoResume / 点播放 / 换视频 → false。
     */
    private var userPaused = false

    /**
     * "下一次 prepare 必须落在这里"的**显式投递位**（一次性）。
     *
     * 为什么不能直接用 GSY 的 `mSeekOnStart` 存：那个槽还有别的写者 ——
     * 计时器保鲜（播放中每秒写当前位置）、`armResumeSlot()` 补空、`seekTo()` 同步。
     * 换清晰度/换语言时它们会在 prepare 之前把显式落点盖成"当前位置"甚至 0，
     * 结果就是**换个清晰度从头播**（实机回归 2026-09-17）。
     * 所以显式落点单独存一格，只有 prepare 消费它，其它写者一律绕开。
     */
    private var pendingSeekMs = 0L

    /** 显式投递"下次 prepare 的落点"（换清晰度 / 换语言 / 重试 / 续播 / 点播放用） */
    fun armSeekOnPrepare(posMs: Long) {
        if (posMs > 0L) {
            pendingSeekMs = posMs
            mSeekOnStart = posMs
            // 兜底：万一这次 prepare 没把落点应用下去（seek 被底层吞掉/顺序错位），
            // 0.8s 后检查一次"位置是不是掉到 0 附近"，是就拽回账本位置
            scheduleRestartGuard()
        }
    }

    /** 账本里的续播位置（播放器还活着就以底层真实位置为准；它返回 0 时还能兜住 mCurrentPosition） */
    val resumePosition: Long
        get() = currentPositionWhenPlaying.takeIf { it > 0L } ?: lastGoodPositionMs

    /** 记一笔账（暂停 / 退出 / 播放中都调），保证任何时刻都有位置可投递 */
    fun noteResumePosition(posMs: Long) {
        if (posMs > 0L) {
            lastGoodPositionMs = posMs
            lastGoodUpdateAt = System.currentTimeMillis()
        }
    }

    /**
     * 槽感知补位：GSY 的两个槽是一次性的，谁读到就清零，而写又是有条件的（见账本注释）。
     * 在"紧接着可能 re-prepare"的时刻（回前台 / 退出 / 点播放）把**空槽**补上：
     *  - `mSeekOnStart`：下一次 prepare 的定位（画面重建也走 prepare —— GSY 的
     *    `addTextureView()` 只在 `startAfterPrepared()` 里被调用，源码 :852）；
     *  - `mCurrentPosition`：`onVideoResume()` 要读的那个槽（回前台自动续播）。
     * 已有值不覆盖：GSY 自己刚写的值比账本更新。
     */
    fun armResumeSlot() {
        val pos = currentPositionWhenPlaying.takeIf { it > 0L } ?: lastGoodPositionMs
        if (pos <= 0L) return
        if (mCurrentPosition <= 0L) mCurrentPosition = pos
        if (mSeekOnStart <= 0L) mSeekOnStart = pos
    }

    /** 换视频 / 播放完成：账本整个复位（否则会把新视频或重播拽到上一段的位置） */
    fun resetRestartGuard() {
        lastGoodPositionMs = 0L
        userPaused = false
        pendingSeekMs = 0L
        // 连 GSY 的"prepare 后定位"一起清掉，否则残留值会漏到下一个视频
        mSeekOnStart = 0L
        removeCallbacks(restartGuardRunnable)
    }

    /** 同一个视频的重载（换清晰度 / 换语言 / 网络重试）：只撤掉待执行的兜底检查，账本留着 */
    fun resetRestartGuardKeepPosition() {
        removeCallbacks(restartGuardRunnable)
    }

    /** 用户明确要播（重播 / 重试 / 通知栏播放）：清掉"暂停意图"，否则 prepare 完会被按回暂停 */
    fun clearPausedIntent() {
        userPaused = false
    }

    private val restartGuardRunnable = Runnable {
        val p = try { currentPosition } catch (_: Exception) { 0L }
        val state = mCurrentState
        val shouldRestore = lastGoodPositionMs > 0L &&
            p < 3_000L &&
            (state == CURRENT_STATE_PLAYING || state == CURRENT_STATE_PAUSE)
        if (shouldRestore) {
            try { seekTo(lastGoodPositionMs) } catch (_: Exception) {}
        }
    }

    /**
     * GSY 重新 prepare 完成后一定会走这里（surface 被销毁后重建、播放器被释放后重建都会），
     * 而 `mSeekOnStart` 就是在这个方法里被消费来"prepare 后定位"的（源码 v13.0.0:844 行）。
     *
     * 两件事：
     *  1. super 之前把空槽补上账本位置 → 重新 prepare 出来的**第一帧就在正确位置**，
     *     不会先闪一下 0:00 再被兜底拽回来；
     *  2. 如果用户是"暂停着离开"的，补 `mPauseBeforePrepared` → prepare 完自动按回暂停
     *     （GSY 默认 mStartAfterPrepared=true，重建后会自己播起来）。
     */
    override fun startAfterPrepared() {
        // 显式落点优先级最高：换清晰度/换语言/重试/点播放投递的位置必须落到这一帧上
        if (pendingSeekMs > 0L) {
            mSeekOnStart = pendingSeekMs
            pendingSeekMs = 0L
        }
        // 位置已经贴着结尾了就别投递：seek 到末尾会立刻 STATE_ENDED 再走一遍播放完成（连播死循环）
        val totalDuration = try { duration } catch (_: Exception) { 0L }
        if (totalDuration > 0L && mSeekOnStart >= totalDuration - 2_000L) {
            mSeekOnStart = 0L
        }
        if (mSeekOnStart <= 0L && lastGoodPositionMs > 0L) {
            mSeekOnStart = lastGoodPositionMs
        }
        if (userPaused) {
            mPauseBeforePrepared = true
        }
        isPreparing = true
        try {
            super.startAfterPrepared()
        } finally {
            isPreparing = false
        }
        // GSY 在 mPauseBeforePrepared 分支里会紧接着 onVideoPause()，而此时 seek 可能还没落地，
        // 它读到的 0 会被写进 mCurrentPosition（= 下一次续播位置）→ 用账本补回来
        if (mCurrentPosition <= 0L && lastGoodPositionMs > 0L) {
            mCurrentPosition = lastGoodPositionMs
        }
    }

    /** 启动动作（点播放/回前台续播）后挂一次检查 */
    private fun scheduleRestartGuard() {
        if (lastGoodPositionMs <= 0L) return
        removeCallbacks(restartGuardRunnable)
        // 0.8s：够晚（避免播放还没起来误判），又尽量早（别让用户看见那一下）
        postDelayed(restartGuardRunnable, 800)
    }

    /** 听视频（仅音频）：黑掉画面继续放声音。刻意不碰 surface/播放器，避免 GSY 因 surface 变化误暂停 */
    var isAudioOnly = false
        private set

    /**
     * 当前帧截图（不含弹幕层）。
     * 我们是 TextureView 渲染（GSYVideoType.TEXTURE），TextureView.bitmap 拿到的就是这一帧画面。
     */
    fun takeScreenshot(): Boolean {
        val act = getActivity() ?: return false
        val textureView = findTextureView(mSurfaceContainer)
        if (textureView == null || !textureView.isAvailable) {
            toast("截图失败：画面还没准备好")
            return false
        }
        val bitmap = try {
            textureView.bitmap
        } catch (e: Exception) {
            null
        }
        if (bitmap == null) {
            toast("截图失败")
            return false
        }
        // 复用相册保存（内部会 toast 文件名；失败会回退到 App 私有目录）
        ImageSaveUtil.saveImage(act, "bilimiao_${System.currentTimeMillis()}.png", bitmap)
        return true
    }

    /** 在视图树里找 TextureView（GSY 把渲染器加进 surface_container，外面还套了缩放容器） */
    private fun findTextureView(root: ViewGroup?): TextureView? {
        root ?: return null
        for (i in 0 until root.childCount) {
            when (val child = root.getChildAt(i)) {
                is TextureView -> if (child.isAvailable) return child
                is ViewGroup -> findTextureView(child)?.let { return it }
            }
        }
        return null
    }

    fun setAudioOnly(enabled: Boolean) {
        if (isAudioOnly == enabled) return
        isAudioOnly = enabled
        setViewShowState(mAudioOnlyOverlay, if (enabled) VISIBLE else GONE)
        mAudioOnlySwitchIV.setImageResource(
            if (enabled) R.drawable.ic_player_audio_only_on
            else R.drawable.ic_player_audio_only_off
        )
        mAudioOnlySwitchTV.text = if (enabled) "听视频中" else "听视频"
        // 画面都藏起来了，双指旋转/缩放必须一起关掉：
        // 否则黑屏上还会转画面、还会冒出"还原屏幕"按钮（用户明确要求关掉）
        if (enabled) {
            if (::pinchToZoom.isInitialized) {
                pinchToZoom.resetImmediate()
                pinchToZoom.enabled = false
            }
            setViewShowState(mRestoreScaleBtn, GONE)
            // 控件（暂停/快进快退/进度条/听视频按钮）必须立刻可见，
            // 否则用户进音频模式后连"取消听音频"都点不到
            showAllWidget()
            } else {
            updatePinchState()
            }
    }

    // TODO AI 原声翻译：暂时整体关闭（2026-09-17）。
    //  原因：B 站的 AI 配音流只以 DASH 形式返回，实机切过去后播放器 state=7(CURRENT_STATE_ERROR) → 转圈黑屏。
    //  恢复时把本文件与 PlayerDelegate2 里带 "TODO AI 原声翻译" 的注释块全部放开即可，
    //  底层 plumbing（PlayerAPI 的 cur_language、PlayerSourceInfo.language、getTranslateLanguages）都还在。
// TODO AI 原声翻译：暂时关闭（切到 AI 音轨后播放器进 ERROR/黑屏）。恢复时把这段注释放开。
//     /** AI 翻译可选语言（playurl 的 language.items；空 = 该视频没有 AI 翻译） */
//     var translateLanguages = emptyList<TranslateLanguageInfo>()
//         set(value) {
//             field = value
//             updateAiTranslateSwitch()
//         }
//
//     /** 当前 AI 翻译语言（null = 未翻译，播原声） */
//     var currentTranslateLang: String? = null
//         set(value) {
//             field = value
//             updateAiTranslateSwitch()
//         }
//
//     /** 用户在字幕菜单里选了翻译语言（null = 关闭翻译）：由 PlayerDelegate2 重新取播放地址 */
//     var onTranslateSelected: ((String?) -> Unit)? = null

// TODO AI 原声翻译：暂时关闭（切到 AI 音轨后播放器进 ERROR/黑屏）。恢复时把这段注释放开。
//     /** B 站 AI 翻译语言（playurl 响应里的 language.items，lang 形如 ai-zh） */
//     data class TranslateLanguageInfo(
//         val lang: String,
//         val title: String?,
//     )

    /** 字幕菜单里的一个选项（CheckPopupMenu 需要一个统一类型来打勾）；AI 翻译项已暂时注释掉 */
    private sealed interface SubtitleMenuValue {
        data class Track(val source: SubtitleSourceInfo) : SubtitleMenuValue
//         data class Translate(val lang: String) : SubtitleMenuValue
        data object SubtitleOff : SubtitleMenuValue
//         data object TranslateOff : SubtitleMenuValue
    }

    // 当前模式
    var mode = PlayerMode.SMALL_TOP
        set(value) {
            field = value
            updateMode()
            updatePinchState()
        }

    /** 外部设置的布局方向（ScaffoldView 的 orientation） */
    var isLandscapeLayout = false
        set(value) {
            field = value
            updatePinchState()
        }

    // 是否处于画中画模式
    var isPicInPicMode = false

    var isHoldUp = false

    // 是否显示当面
    var isShowDanmaku = true
        set(value) {
            field = value
            resolveDanmakuShow()
        }

    // 弹幕开始位置
    var danmakuStartSeekPosition: Long = -1
    var danmakuParser: BaseDanmakuParser? = null
        set(value) {
            if (value != null) {
                value.timer = mDanmakuTime
            }
            field = value
        }
    var danmakuContext: DanmakuContext? = null

    // 状态栏
    var statusBarHelper: StatusBarHelper? = null

    // 播放回调
    var videoPlayerCallBack: VideoPlayerCallBack? = null

    // 加载字幕（url = null 表示"当前没有选中字幕"，外部据此作废还在飞的请求）
    var subtitleLoader: ((url: String?) -> Unit)? = null

    // 字幕源选择
    var subtitleSourceSelector: ((list: List<SubtitleSourceInfo>) -> SubtitleSourceInfo?)? = null

    var subtitleBody: List<SubtitleItemInfo> = emptyList()
        set(value) {
            field = value
            if (value.isNotEmpty()) {
                postDelayed(subtitleTask, 0)
            }
        }

    private var subtitleIndex = 0

    val isAutoCompletion get() = currentState == CURRENT_STATE_AUTO_COMPLETE
    val currentPosition get() = try {
        gsyVideoManager.currentPosition
    } catch (e: Exception) {
        0L
    }

    // 供外部访问
    val topContainer: ViewGroup get() = mTopContainer
    val qualityView: View get() = mQuality
    val speedView: View get() = mPlaySpeed
    val speedValueTextView: View get() = mPlaySpeedValue
    val moreBtn: View get() = mMoreBtn

    // 是否处于锁定状态
    var isLock: Boolean = false
        set(value) {
            field = value
            // 锁定 → 禁用缩放并复位；解锁 → 按横屏/全屏状态重算
            // （长屏非全屏时解锁也不该放开缩放）
            updatePinchState()
            if (value) {
                hideAllWidget()
                mLockContainer.visibility = VISIBLE
            } else {
                mLockContainer.visibility = GONE
            }
        }
    // 全屏状态下显示底部进度条
    var showBottomProgressBarInFullMode = true
    // 小屏状态下显示底部进度条
    var showBottomProgressBarInSmallMode = true
    // 画中画状态下显示底部进度条
    var showBottomProgressBarInPipMode = true

    constructor(context: Context?, fullFlag: Boolean?) : super(context, fullFlag) {
        initView()
    }

    constructor(context: Context?) : super(context) {
        initView()
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        initView()
    }

    override fun getLayoutId(): Int {
        return R.layout.layout_danmaku_palyer
    }

    private fun initView() {
        mSeekRatio = 200f
        isShowDragProgressTextOnSeekBar = true
        enlargeImageRes = R.drawable.ic_player_portrait_fullscreen
        shrinkImageRes = R.drawable.ic_player_portrait_fullscreen
initDanmakuTouchListener()
        // 【已移除】新弹幕引擎开关读取 — V2引擎已废弃
        initDanmakuContext()

        // --- 初始化双指缩放/平移/旋转 ---
        // ★ 不直接缩放 surface_container（缩放后会破坏触摸事件分发），
        //   而是在里面加一层 zoomWrapper，只缩放它视觉上变大，surface_container 本身不动。
        pinchToZoom = PinchToZoomHelper(mSurfaceContainer, mRestoreScaleBtn)
        // ★ addTextureView() 中会重建 pinchToZoom 后再设点击监听，这里不重复设
        // 横屏非全屏时禁用
        updatePinchState()
        // 在 surface_container 内加一层子容器用于缩放，等 GSY 加完渲染器后移进去
        initZoomWrapper()
        // 拖动进度时的中央预览：预览图 + 下方时间条，**整组居中**，加在最上层
        // （只在拖动时可见，平时 GONE 不参与布局）
        mSeekPreviewBox.addView(mSeekPreviewView, LinearLayout.LayoutParams(dip(144), dip(81)))
        mSeekPreviewBox.addView(
            mSeekPreviewTimeTV,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dip(8) }
        )
        addView(
            mSeekPreviewBox,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        // 空降助手：顶栏两个图标，插在**章节按钮后面**（对齐 PiliPlus header 的 ADS / 盾牌+播放）
        runCatching {
            val topBar = findViewById<ViewGroup>(R.id.layout_top)
            val chapterBtn = findViewById<View>(R.id.chapter_btn_layout)
            val at = (topBar.indexOfChild(chapterBtn).takeIf { it >= 0 } ?: (topBar.childCount - 1)) + 1
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL }
            // 与 PiliPlus 一致：提交(盾+播放) 在前，片段信息(ADS) 在后
            topBar.addView(mSponsorSubmitTopBtn, at, lp)
            topBar.addView(mSponsorInfoTopBtn, at + 1, lp)
        }.onFailure {
            // 裸 runCatching 会让"按钮没出现"变成无解之谜，至少留一行日志
            SponsorDiag.log("ui-buttons", "插入空降顶栏按钮失败：${it.javaClass.simpleName}: ${it.message}")
        }

        // 空降助手：入口只在顶栏（用户要求：底栏那两个按钮和顶栏重复，已去掉）

        // 空降助手：色块**只画在可拖动的那条进度条上**（用户要求：底部细条重复，去掉）。
        // 实现见 mSeekSegmentsDrawable（以 LayerDrawable 叠在进度条 drawable 上）。
        // 手动跳过的气泡仍加在最上层。

        addView(
            mSponsorSkipTip,
            // 位置对齐 PiliPlus：左下角、浮在控制栏上方（全屏时更高一点）
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                leftMargin = dip(16)
                // bottomMargin 在**显示气泡时**按当前模式重算（见 showSponsorSkipTip）：
                // 这里 initView 阶段 mode 还是默认的 SMALL_TOP、height 还是 0，
                // 写在这里的话全屏分支永远走不到（用户反馈过气泡位置不对）
                bottomMargin = dip(75)
            }
        )
        mButtomPlay.setOnClickListener {
            clickStartIcon()
        }
        mSubtitleSwitch.setOnClickListener {
            val menus = mutableListOf<CheckPopupMenu.MenuItemInfo<SubtitleMenuValue>>()
            subtitleSourceList.forEach {
                menus.add(
                    CheckPopupMenu.MenuItemInfo(it.lan_doc, SubtitleMenuValue.Track(it))
                )
            }
            menus.add(CheckPopupMenu.MenuItemInfo("关闭字幕", SubtitleMenuValue.SubtitleOff))
            // TODO AI 原声翻译：暂时关闭（原本这里是 AI 翻译语言入口）
            val current = when {
                currentSubtitleSource != null -> SubtitleMenuValue.Track(currentSubtitleSource!!)
                else -> SubtitleMenuValue.SubtitleOff
            }
            val pm = CheckPopupMenu(
                context = context,
                anchor = it,
                menus = menus,
                value = current,
                themeColor = mThemeColor,
            )
            pm.onMenuItemClick = { item ->
                when (val v = item.value) {
                    is SubtitleMenuValue.Track -> currentSubtitleSource = v.source
                    SubtitleMenuValue.SubtitleOff -> currentSubtitleSource = null
//                     is SubtitleMenuValue.Translate -> onTranslateSelected?.invoke(v.lang)
//                     SubtitleMenuValue.TranslateOff -> onTranslateSelected?.invoke(null)
                }
            }
            pm.show()
        }
        // AI 原声翻译：点一下开/关（开的时候用第一条语言；想挑具体语言去上面的字幕菜单）
// TODO AI 原声翻译：暂时关闭（切到 AI 音轨后播放器进 ERROR/黑屏）。恢复时把这段注释放开。
//         // AI 原声翻译：点开独立弹窗（语言列表 + 关闭），不和字幕菜单混在一起
//         mAiTranslateSwitch.setOnClickListener { anchor ->
//             if (!AI_TRANSLATE_ENABLED) return@setOnClickListener
//             val langs = translateLanguages
//             if (langs.isEmpty()) return@setOnClickListener
//             val menus = mutableListOf<CheckPopupMenu.MenuItemInfo<SubtitleMenuValue>>()
//             langs.forEach {
//                 menus.add(
//                     CheckPopupMenu.MenuItemInfo(
//                         "AI 翻译：${it.title ?: it.lang}",
//                         SubtitleMenuValue.Translate(it.lang),
//                     )
//                 )
//             }
//             menus.add(CheckPopupMenu.MenuItemInfo("关闭 AI 翻译", SubtitleMenuValue.TranslateOff))
//             val current = if (!currentTranslateLang.isNullOrEmpty()) {
//                 SubtitleMenuValue.Translate(currentTranslateLang!!)
//             } else {
//                 SubtitleMenuValue.TranslateOff
//             }
//             val pm = CheckPopupMenu(
//                 context = context,
//                 anchor = anchor,
//                 menus = menus,
//                 value = current,
//                 themeColor = mThemeColor,
//                 checkable = true,
//             )
//             pm.onMenuItemClick = { item ->
//                 when (val value = item.value) {
//                     is SubtitleMenuValue.Translate -> onTranslateSelected?.invoke(value.lang)
//                     SubtitleMenuValue.TranslateOff -> onTranslateSelected?.invoke(null)
//                     else -> {}
//                 }
//             }
//             pm.show()
//         }
        // 听视频：只黑掉画面，音频继续（播放器/surface 都不动）
        mAudioOnlySwitch.setOnClickListener {
            setAudioOnly(!isAudioOnly)
        }
        // 截图：抓当前帧存相册
        mScreenshotSwitch.setOnClickListener {
            takeScreenshot()
        }
        chapterManager.initChapterButton()
        mCastBtnLayout.setOnClickListener {
            onCastClick?.invoke(it)
        }
        mBottomSubtitleTV.setTextColor(Color.parseColor("#FFFFFF"))
        mBottomSubtitleTV.backgroundColor = Color.parseColor("#66000000")
        applySubtitleTextSize()

        val lockClickListener = OnLockClickListener()
        mLock.setOnClickListener(lockClickListener)
        mLockContainer.setOnClickListener(lockClickListener)
        mUnlockLeftIV.setOnClickListener(lockClickListener)
        mUnlockRightIV.setOnClickListener(lockClickListener)

    }


    private fun updateMode() {
        when (mode) {
            PlayerMode.SMALL_TOP, PlayerMode.SMALL_FLOAT -> {
                mFullModeBottomContainer.visibility = GONE
                mPlaySpeedName.visibility = GONE
                mMiniSendDanmakuIV.visibility = VISIBLE
                mSendDanmakuTV.visibility = GONE
                mBackButton.setImageResource(R.drawable.ic_close_white_24dp)
                if (mode == PlayerMode.SMALL_FLOAT) {
                    mDragBarLayout.visibility = mTopContainer.visibility
                } else {
                    mDragBarLayout.visibility = GONE
                }
                updateDanmakuMargin()
            }
            PlayerMode.FULL -> {
                mFullModeBottomContainer.visibility = VISIBLE
                mPlaySpeedName.visibility = VISIBLE
                mMiniSendDanmakuIV.visibility = GONE
                mSendDanmakuTV.visibility = VISIBLE
                mBackButton.setImageResource(R.drawable.ic_arrow_back_white_24dp)
                mDragBarLayout.visibility = GONE
                updateDanmakuMargin()
            }
        }
    }

    /**
     * 横屏非全屏时禁用双指缩放。
     * 全屏或竖屏小窗时启用。
     */
    private fun updatePinchState() {
        if (!::pinchToZoom.isInitialized) return
        // isLandscapeLayout 由外部（PlayerController）基于 ScaffoldView.orientation 设置，
        // 适配 bilimiao 自身的横屏布局（非设备物理旋转）
        // 音频模式（听视频）下不允许双指缩放/旋转
        val shouldDisable = isLock || isAudioOnly || (isLandscapeLayout && mode != PlayerMode.FULL)
        pinchToZoom.enabled = !shouldDisable
        if (shouldDisable) {
            pinchToZoom.resetImmediate()
        }
    }

    /**
     * 由外部（如 Activity onConfigurationChanged）调用，
     * 通知播放器方向变化以重新判断双指手势是否可用。
     */
    fun onOrientationChanged() {
        updatePinchState()
    }

    /**
     * 竖屏全屏时，防止挖孔屏挡住弹幕
     */
    private fun updateDanmakuMargin() {
        val danmakuViewLP = mDanmakuView.layoutParams as MarginLayoutParams
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            && mode == PlayerMode.FULL
            && resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        ) {
            danmakuViewLP.topMargin = mDisplayCutout?.safeInsetTop ?: 0
        } else {
            danmakuViewLP.topMargin = 0
        }
    }

    private fun updateSubtitleSourceList() {
        if (subtitleSourceList.isEmpty()) {
            setViewShowState(mSubtitleSwitch, GONE)
            currentSubtitleSource = null
        } else {
            setViewShowState(mSubtitleSwitch, VISIBLE)
            currentSubtitleSource = subtitleSourceSelector?.invoke(subtitleSourceList)
        }
    }

    private fun updateCurrentSubtitleSource() {
        subtitleBody = emptyList()
        subtitleIndex = 0
        mBottomSubtitleTV.visibility = GONE
        // ★ 选轨/关闭都要通知外部，关闭时传 null：
        //   否则"关字幕后，关闭前发出去的那次请求"返回时仍会把 subtitleBody 填回来，
        //   表现就是"字幕关不掉"（点了关闭，字幕过一会儿又冒出来）。
        subtitleLoader?.invoke(currentSubtitleSource?.subtitle_url)
        if (currentSubtitleSource == null) {
            mSubtitleSwitchIV.setImageResource(R.drawable.bili_player_subtitle_is_closed)
            mSubtitleSwitchTV.text = "字幕关"
        } else {
            mSubtitleSwitchIV.setImageResource(R.drawable.bili_player_subtitle_is_open)
            mSubtitleSwitchTV.text = currentSubtitleSource?.lan_doc ?: "字幕开"
        }
    }

    // ───────────────────────── 拖动进度条预览图 ─────────────────────────
    // 数据源：B 站 videoshot 接口给的"缩略图雪碧图"（大图 + 每格起始秒数）。
    // 参考 PiliPlus：pl_player/controller.dart 的 updatePreviewIndex/_clearPreview、
    //              pl_player/view/widgets.dart 的 buildSeekPreviewWidget/VideoShotImage。

    /** 拖动时是否显示预览图（PlayerController 下发；设置页可关） */
    var showSeekPreview = true

    /** 本视频的缩略图数据；null = 这个视频没有预览图（拖动只显示时间气泡） */
    var videoShotData: PlayerAPI.VideoShotData? = null
        set(value) {
            field = value
            // 换视频/换清晰度：作废上一次的图和还在飞的下载
            previewLoadToken++
            previewSheets.clear()
            hideSeekPreview()
        }

    private val mSeekPreviewView: VideoShotPreviewView by lazy { VideoShotPreviewView(context) }

    /**
     * 预览容器：预览图 + 下方时间条，**整组居中**。
     *
     * 以前只有一张图、还上移 48dp 给"居中时间盒子"让位，看着又小又偏上；
     * 现在有预览图时不再弹 GSY 那个 152dp 的居中盒子（它会压住大预览），
     * 时间自己画在图下面 —— 于是图可以放到接近半屏且真正居中。
     */
    private val mSeekPreviewBox: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = GONE
        }
    }

    /** 预览图下方的时间条（`当前 / 总长`） */
    private val mSeekPreviewTimeTV: TextView by lazy {
        TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dip(10), dip(4), dip(10), dip(4))
            background = GradientDrawable().apply {
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.parseColor("#CC000000"))
            }
        }
    }

    /** 已解码的雪碧图缓存（key = 图片 URL）。一张 ~1600×900 的 RGB_565 约 2.9MB，留 3 张够跨页拖。 */
    private val previewSheets = LinkedHashMap<String, Bitmap>()

    /** 下载令牌：换视频/换清晰度后，旧的下图结果直接丢弃（避免串台到新视频） */
    private var previewLoadToken = 0

    /** 预览图相关的协程（只在 View 层用，播放器 View 跨 Activity 保活，所以不随生命周期取消） */
    private val previewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 当前是否正在拖动进度（只有拖动中才允许预览图出现） */
    private var seekPreviewActive = false

    /**
     * 显示某个时间点的预览图（由进度 HUD 的显示回调驱动）。
     *
     * 全程不阻塞：图没下好就先不显示（宁可没有，也不要卡住拖动）。
     */
    private fun updateSeekPreview(timeMs: Long) {
        val data = videoShotData
        if (!showSeekPreview || data == null) return
        val total = data.totalPerImage
        if (total <= 0 || data.index.isEmpty() || data.image.isEmpty()) return
        seekPreviewActive = true

        val cell = previewCellIndex(data, (timeMs / 1000L).toInt())
        val page = (cell / total).coerceIn(0, data.image.size - 1)
        val align = cell % total
        val url = data.image[page]
        val sheet = previewSheets[url]
        // 容器先亮出来：图还没下好时至少能看到时间条（不再干等）
        mSeekPreviewBox.visibility = VISIBLE
        if (sheet == null || sheet.isRecycled) {
            // 这张雪碧图还没下好：先不显示，下好了如果还在拖动就直接补上
            mSeekPreviewView.clear()
            downloadPreviewSheet(url)
            return
        }
        showPreviewCell(sheet, data, align)
    }

    /**
     * 时间(秒) → 小格序号。
     *
     * 用 PiliPlus 的经验公式 `count(index <= t) - 2`（`controller.dart:1617` 的 updatePreviewIndex）：
     * B 站 index 数组存的是每格的**结束时刻**，且开头有占位项，减 2 才对得上画面。
     * 个数用二分求（拖动一次可能跳几分钟，逐条数会白跑几百上千次）。
     */
    private fun previewCellIndex(data: PlayerAPI.VideoShotData, seconds: Int): Int {
        val index = data.index
        // 二分找"最后一个 <= seconds 的位置"，个数 = 位置 + 1（index 是升序的）
        var lo = 0
        var hi = index.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (index[mid] <= seconds) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return (found + 1 - 2).coerceAtLeast(0)
    }

    private fun showPreviewCell(sheet: Bitmap, data: PlayerAPI.VideoShotData, align: Int) {
        val cols = data.img_x_len
        val rows = data.img_y_len
        if (cols <= 0 || rows <= 0) return

        // 格子宽高比 = 雪碧图的 cols/rows 均分
        val cellW = sheet.width.toFloat() / cols
        val cellH = sheet.height.toFloat() / rows
        if (cellW <= 0f || cellH <= 0f) return

        // 尺寸：全屏横屏给到 240dp（以前 108dp，用户反馈"小得看不见"），
        // 其它模式 160dp；再按播放器可视区收一收（0.62 = 上下各留 ~19% 给字幕/时间条）。
        // 注意源只有 160×90，放太大会糊 —— 这个上限是取"看得清内容"和"别太糊"的平衡。
        val vh = height
        val vw = width
        if (vh <= 0 || vw <= 0) return
        val want = if (mode == PlayerMode.FULL && isLandscapeLayout) dip(240) else dip(160)
        var h = want.coerceAtMost((vh * 0.62f).toInt()).coerceAtLeast(dip(60))
        var w = (h * cellW / cellH).toInt()
        val maxW = (vw * 0.86f).toInt()
        if (w > maxW) {
            w = maxW
            h = (w * cellH / cellW).toInt()
        }

        val lp = mSeekPreviewView.layoutParams as? LinearLayout.LayoutParams
        if (lp == null) {
            mSeekPreviewView.layoutParams = LinearLayout.LayoutParams(w, h)
        } else if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            mSeekPreviewView.layoutParams = lp
        }
        mSeekPreviewView.showCell(sheet, cols, rows, align)
        mSeekPreviewView.visibility = VISIBLE
        mSeekPreviewBox.visibility = VISIBLE
    }

    /** 刷新预览图下方的时间条（`当前 / 总长`） */
    private fun updateSeekPreviewTime(timeMs: Long, totalMs: Long) {
        if (!seekPreviewActive) return
        val pos = CommonUtil.stringForTime(timeMs.coerceAtLeast(0L))
        mSeekPreviewTimeTV.text = if (totalMs > 0) {
            "$pos / ${CommonUtil.stringForTime(totalMs)}"
        } else {
            pos
        }
    }

    /** 把片段换算成"进度条上的一段"（0~1 + 颜色），同时供可拖动进度条使用 */
    private fun updateSeekBarMarks() {
        val total = duration
        mSeekSegmentsDrawable.marks = if (total <= 0L) {
            emptyList()
        } else {
            sponsorSegments
                .filter { it.skipTypeOf(sponsorSkipTypes, sponsorLimitSec) != SponsorSkipType.Disable }
                .map {
                    Triple(
                        it.startMs.toFloat() / total,
                        it.endMs.toFloat() / total,
                        sponsorColors[it.category] ?: SponsorCategory.colorOf(it.category)
                    )
                }
        }
    }

    private fun hideSeekPreview() {
        seekPreviewActive = false
        mSeekPreviewView.clear()
        mSeekPreviewBox.visibility = GONE
    }

    /** 下载并解码一张雪碧图（带降采样：长视频的图可能 3000+ 宽，全尺寸解码会吃掉几十 MB） */
    private fun downloadPreviewSheet(url: String) {
        val token = previewLoadToken
        previewScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val res = MiaoHttp.request { this.url = url }.awaitCall()
                    val bytes = res.body?.bytes() ?: return@withContext null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2
                    BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            // 缩略图不需要真彩：RGB_565 省一半内存，肉眼无差
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                    )
                } catch (e: Exception) {
                    null
                }
            } ?: return@launch
            if (token != previewLoadToken) {
                // 期间换了视频/清晰度：这张图已经没用了
                return@launch
            }
            previewSheets[url] = bitmap
            // 超过 3 张丢最早的（不手动 recycle：可能正被 onDraw 用着）
            while (previewSheets.size > 3) {
                val oldest = previewSheets.keys.firstOrNull() ?: break
                previewSheets.remove(oldest)
            }
            // 图下好时如果用户还在拖，立刻补上
            if (seekPreviewActive) {
                val data = videoShotData ?: return@launch
                val total = data.totalPerImage
                if (total <= 0) return@launch
                val cell = previewCellIndex(data, (mSeekTimePosition / 1000L).toInt())
                val page = (cell / total).coerceIn(0, data.image.size - 1)
                if (data.image[page] == url) showPreviewCell(bitmap, data, cell % total)
            }
        }
    }

    // [hermes-fix 2026-09-17] 原"预览图钩子"重载（totalTime: String?）与下方自定义 showProgressDialog
    // （totalTime: String）JVM 签名相同 → Platform declaration clash 编译失败；已把 updateSeekPreview
    // 调用并进下方自定义实现，此块删除。

    override fun dismissProgressDialog() {
        hideSeekPreview()
        super.dismissProgressDialog()
    }

    /**
     * 拖动**进度条本体**（SeekBar）时也要出预览图。
     *
     * 这条路径和"在画面上横向拖动"不是一回事：GSY 的 SeekBar 拖动过程中只更新底部时间文本
     * （showDragProgressTextOnSeekBar），走到 seek 是在**松手**时（onStopTrackingTouch → seekTo），
     * 全程不碰 showProgressDialog —— 所以预览图得单独挂在这里。
     * 这里只更新中央预览图，不改播放状态、不发起 seek（真正的 seek 仍由 GSY 在松手时做）。
     */
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        super.onProgressChanged(seekBar, progress, fromUser)
        if (!fromUser || seekBar == null) return
        val total = duration
        if (total <= 0L) return
        val target = total * progress / 100
        updateSeekPreview(target)
        updateSeekPreviewTime(target, total)
    }

    override fun onStopTrackingTouch(seekBar: SeekBar?) {
        // 松手即收：接下来 GSY 会按进度条位置 seek，预览图不该继续挂在画面中央
        hideSeekPreview()
        super.onStopTrackingTouch(seekBar)
    }

// TODO AI 原声翻译：暂时关闭（切到 AI 音轨后播放器进 ERROR/黑屏）。恢复时把这段注释放开。
//     /**
//      * AI 原声翻译开关（底栏）：
//      * 只有"这个视频确实有 AI 翻译语言 + 外部接好了回调"时才显示；
//      * 点一下开/关（多语言时开启用第一条，具体选哪条去字幕菜单里挑）。
//      */
//     private fun updateAiTranslateSwitch() {
//         if (!AI_TRANSLATE_ENABLED || translateLanguages.isEmpty() || onTranslateSelected == null) {
//             setViewShowState(mAiTranslateSwitch, GONE)
//             return
//         }
//         setViewShowState(mAiTranslateSwitch, VISIBLE)
//         val lang = currentTranslateLang
//         if (lang == null) {
//             mAiTranslateSwitchIV.setImageResource(R.drawable.ic_player_ai_translate_off)
//             mAiTranslateSwitchTV.text = "AI翻译"
//         } else {
//             mAiTranslateSwitchIV.setImageResource(R.drawable.ic_player_ai_translate_on)
//             mAiTranslateSwitchTV.text = translateLanguages
//                 .find { it.lang == lang }?.title ?: "AI翻译开"
//         }
//     }

    private var touchSurfaceDownTime = Long.MAX_VALUE
    private var isSpeedPlaying = false
    private var lastSpeed = 0f  // init an invalid value

    /** 亮度手势跟踪：每次手指抬起时重置，下次从系统亮度重新开始 */
    private var lastGestureBrightness = -1f

    /** 显式持有的当前 Activity 引用（keepPlayerView 复用后替代失效的 View.mContext） */
    private var currentActivity: Activity? = null

    /** 双指按下前保存的播放位置，缩放结束时重设。哨兵值 -1L 表示"未保存" */
    private var savedPositionForPinch = -1L

    /** 第一指按下时间戳，200ms内抑制单指手势（等第二指） */
    private var pendingPinchTime = 0L
    private val PINCH_PENDING_MS = 200L

    /** 触摸事件来源 View 的实际宽度（TextureView，可能因视频比例而窄于播放器容器） */
    private var touchViewWidth = 0

    /** DOWN 事件的绝对屏幕 X 坐标，用于精确左右半区判定（不受 view 坐标系影响） */
    private var rawDownX = 0f

    /** 双指缩放锁：进入即锁死单指手势，直到所有手指抬起（ACTION_UP）才解锁 */
    private var isPinching = false


    private val longClickControlTask = Runnable {
        if (System.currentTimeMillis() - touchSurfaceDownTime >= 500
            && mCurrentState == CURRENT_STATE_PLAYING
            && !mChangePosition && !mChangeVolume && !mBrightness) {
            startLongClickSpeedPlay()
        }
    }

    /**
     * 开始长按倍数播放
     */
    private fun startLongClickSpeedPlay() {
        isSpeedPlaying = true
        lastSpeed = speed
        // 倍率由设置页下发（默认 3×）；下限兜底，别让异常值把播放速度乘成 0（画面卡死）
        speed *= longPressSpeedMultiplier.coerceAtLeast(1.1f)
        // speed_tips 已由用户设置为隐藏，不再显示"倍速播放中"提示
        mTouchingProgressBar = false
        // 震动反馈
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * 停止长按倍数播放
     */
    private fun stopLongClickSpeedPlay() {
        isSpeedPlaying = false
        speed = lastSpeed
    }

    /**
     * 中止当前单指手势（不提交 seek）。
     *
     * GSY 的 onTouch 只处理 DOWN/UP/MOVE，ACTION_CANCEL 直接落空，
     * mChangePosition/mSeekTimePosition 会残留；清理前若来一次 UP，
     * GSY 的 touchSurfaceUp() 就会按被中止手势的旧位置真跳一次。
     */
    private fun abortGesture() {
        mChangePosition = false
        mChangeVolume = false
        mBrightness = false
        mTouchingProgressBar = false
        if (savedPositionForPinch >= 0) {
            mSeekTimePosition = savedPositionForPinch
        }
        dismissProgressDialog()
        dismissVolumeDialog()
        dismissBrightnessDialog()
    }


    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        // 捕获触摸来源 View 的实际宽度（TextureView 可能因视频比例窄于播放器容器）
        if (v != null && v.width > 0) {
            touchViewWidth = v.width
        }
        if (v != null && event?.action == MotionEvent.ACTION_DOWN) {
            // 捕获绝对屏幕坐标，用于精确左右半区判定
            rawDownX = event.rawX
        }
        if (event != null) {
            // 双指变换激活时，完全拦截事件给 pinchToZoom
            if (::pinchToZoom.isInitialized && pinchToZoom.isActive) {
                isPinching = true
                val consumed = pinchToZoom.onTouchEvent(event)
                // UP/CANCEL 必须传给 super.onTouch，否则播放器内部状态会被卡住
                if (event.action == MotionEvent.ACTION_UP
                    || event.action == MotionEvent.ACTION_CANCEL) {
                    // pinch 收尾即解锁单指手势；此处早于统一复位分支，不置位会导致
                    // 下一次单指手势被 isPinching 整体抑制直到抬手
                    pendingPinchTime = 0L
                    isPinching = false
                    super.onTouch(v, event)
                }
                return consumed
            }

            // pinch 结束但用户还没完全抬手 → 保持 isPinching 锁定，直到 ACTION_UP
            // （锁定逻辑在 touchSurfaceMove 中，这里不需要额外操作）

            // ★ 检测双指按下：取消 GSY 手势跟踪 + 恢复位置 + 激活缩放
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
                && event.pointerCount >= 2
                && ::pinchToZoom.isInitialized) {
                pendingPinchTime = 0L
                isPinching = true
                // 显式中止第一指留下的手势状态：GSY 的 onTouch 只处理 DOWN/UP/MOVE
                // （反编译 tableswitch 0..2），合成 CANCEL 落不到它的分支里，
                // mChangePosition/mSeekTimePosition 会残留 → 抬手时按旧位置真跳一次
                abortGesture()
                // 发 CANCEL 让 GSY 放弃第一指的手势跟踪（含内部 GestureDetector）
                val cancelEvent = MotionEvent.obtain(
                    event.downTime, event.eventTime,
                    MotionEvent.ACTION_CANCEL,
                    event.getX(0), event.getY(0), 0
                )
                super.onTouch(v, cancelEvent)
                cancelEvent.recycle()
                // 恢复播放位置
                if (savedPositionForPinch >= 0) {
                    try { gsyVideoManager.seekTo(savedPositionForPinch) } catch (_: Exception) {}
                }
                // 取消长按
                removeCallbacks(longClickControlTask)
                touchSurfaceDownTime = Long.MAX_VALUE
                if (isSpeedPlaying) stopLongClickSpeedPlay()
                // 激活缩放
                pinchToZoom.onTouchEvent(event)
                return true
            }

            // 200ms pending 过期 → 恢复正常单指手势
            if (pendingPinchTime > 0 && System.currentTimeMillis() - pendingPinchTime > PINCH_PENDING_MS) {
                pendingPinchTime = 0L
            }

            when(event.action){
                MotionEvent.ACTION_CANCEL -> {
                    // 手势被父容器（小窗拖拽）抢走：中止，不提交快进
                    abortGesture()
                    pendingPinchTime = 0L
                    isPinching = false
                    removeCallbacks(longClickControlTask)
                    touchSurfaceDownTime = Long.MAX_VALUE
                    if (isSpeedPlaying) {
                        stopLongClickSpeedPlay()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    pendingPinchTime = 0L
                    isPinching = false
                    removeCallbacks(longClickControlTask)
                    touchSurfaceDownTime = Long.MAX_VALUE
                    if (isSpeedPlaying) {
                        stopLongClickSpeedPlay()
                    }
                }
            }

            // 非激活时也让 pinchToZoom 监视事件（用于检测双指按下）
            if (::pinchToZoom.isInitialized) {
                pinchToZoom.onTouchEvent(event)
            }
        }

        return super.onTouch(v, event)
    }

    override fun touchSurfaceDown(x: Float, y: Float) {
        super.touchSurfaceDown(x, y)
        // 保存当前播放位置，防止双指缩放时 seek 被误触
        savedPositionForPinch = try { gsyVideoManager.currentPosition } catch (_: Exception) { -1L }
        // 启动200ms pending，等第二指
        pendingPinchTime = System.currentTimeMillis()
        // ★ GSY v13 的 touchSurfaceDown 可能不再重置 mDownPosition，
        //    导致跨手势 mDownPosition 不更新。手动重置以确保每次手势从当前播放位置开始计算。
        mDownPosition = 0L
        val curWidth = measuredWidth
        val curHeight = measuredHeight
        val edgeSize = context.dip(80).let {
            min(min(curWidth, curHeight), it) / 2
        }
        if (x.toInt() in edgeSize..(curWidth - edgeSize)
            && y.toInt() in edgeSize..(curHeight - edgeSize)) {
            // 屏幕边缘不触发长按倍数
            touchSurfaceDownTime = System.currentTimeMillis()
            postDelayed(longClickControlTask, 500)
        }
    }

    override fun touchSurfaceMove(deltaX: Float, deltaY: Float, y: Float) {
        // pending期内或双指缩放锁：传零delta给GSY，保持时序但抑制手势
        if (isPinching || pendingPinchTime > 0) {
            super.touchSurfaceMove(0f, 0f, y)
            return
        }
        if (isSpeedPlaying) {
            mChangePosition=false
            return
        }
        if (mDownY<context.dip(25)){
            //顶部防误触
            mChangePosition=false
            return
        }
        if (activityContext == null) return
        var curHeight = 0
        if (activityContext != null) {
            curHeight =
                if (CommonUtil.getCurrentScreenLand(activityContext as Activity)) mScreenWidth else mScreenHeight
        }
        if (mChangePosition) {
            if (mDownPosition == 0L) {
                mDownPosition = currentPosition
            }
            //
            val totalTimeDuration = duration
            val offsetPosition = deltaX / context.dip(1) * mSeekRatio
            mSeekTimePosition = (mDownPosition + offsetPosition).toLong()
            if (mSeekTimePosition < 0) {
                mSeekTimePosition = 0
            }
            if (mSeekTimePosition > totalTimeDuration) mSeekTimePosition = totalTimeDuration
            val seekTime = CommonUtil.stringForTime(mSeekTimePosition)
            val totalTime = CommonUtil.stringForTime(totalTimeDuration)
            showProgressDialog(deltaX, seekTime, mSeekTimePosition, totalTime, totalTimeDuration)
            // ★ 字幕跟手：拖动进度条时，底部字幕立即切到目标时间点那一句。
            //   只读取+切文本，不碰播放器状态、不发起 seek（真正的 seek 仍由松手后的 GSY 逻辑负责），
            //   所以对播放没有任何副作用；松手后由 100ms 字幕定时器接管，自动回到真实播放位置。
            if (subtitleBody.isNotEmpty()) {
                setBottomSubtitleText(mSeekTimePosition)
            }
        } else if (mChangeVolume) {
            val deltaYNeg = -deltaY
            // ★ 绕过 GSY 的 mAudioManager 缓存（复用播放器时可能为 null），直接从 context 获取
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am == null) {
                return
            }
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (mGestureDownVolume < 0) {
                mGestureDownVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            val deltaV = (max * deltaYNeg * 2 / curHeight).toInt()
            am.setStreamVolume(AudioManager.STREAM_MUSIC, mGestureDownVolume + deltaV, 0)
            val volumePercent =
                (mGestureDownVolume * 100 / max + deltaYNeg * 2 * 100 / curHeight).toInt()
            showVolumeDialog(-deltaY, volumePercent)
        } else if (mBrightness) {
            if (Math.abs(deltaY) > mThreshold) {
                val percent = -deltaY / (curHeight * 3f)
                onBrightnessSlide(percent)
                mDownY = y
            }
        }
    }

    override fun onBrightnessSlide(percent: Float) {
        val act = getActivity() ?: return
        // 先读 App 已设的亮度（window.screenBrightness），没有才读系统亮度
        var brightness = try {
            act.window.attributes.screenBrightness
        } catch (_: Exception) { -1f }

        if (brightness <= 0f) {
            brightness = try {
                val sys = android.provider.Settings.System.getInt(
                    context.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS
                )
                (sys / 255f).coerceIn(0.01f, 1.0f)
            } catch (_: Exception) {
                0.5f
            }
        }

        // 首次触发：从当前亮度开始；后续累加手势偏移
        if (lastGestureBrightness < 0f) {
            lastGestureBrightness = brightness
        }
        val newBrightness = (lastGestureBrightness + percent).coerceIn(0.01f, 1.0f)
        lastGestureBrightness = newBrightness

        try {
            val lp = act.window.attributes
            lp.screenBrightness = newBrightness
            act.window.attributes = lp
        } catch (_: Exception) {}
        showBrightnessDialog(newBrightness)
    }

    override fun touchSurfaceMoveFullLogic(absDx: Float, absDy: Float) {
        // pending期内或双指缩放锁：传零delta给GSY
        if (isPinching || pendingPinchTime > 0) {
            super.touchSurfaceMoveFullLogic(0f, 0f)
            return
        }
        // ★ 用绝对屏幕坐标判定左右半区，彻底解决坐标系不匹配导致音量失效
        val screenLocation = IntArray(2)
        getLocationOnScreen(screenLocation)
        val screenMidX = screenLocation[0] + measuredWidth * 0.5f
        val isLeftSide = rawDownX < screenMidX
        if (absDx > mThreshold && absDx > absDy) {
            mChangePosition = true
        } else if (absDy > mThreshold) {
            // 左半区 = 亮度，右半区 = 音量
            if (isLeftSide) {
                mBrightness = true
            } else {
                mChangeVolume = true
                mGestureDownVolume = -1
            }
        }
    }

    // end

//    override fun setProgressAndTime(
//        progress: Long,
//        secProgress: Long,
//        currentTime: Long,
//        totalTime: Long,
//        forceChange: Boolean
//    ) {
//        super.setProgressAndTime(progress, secProgress, currentTime, totalTime, forceChange)
//        setBottomSubtitleText(currentTime)
//    }

    override fun startProgressTimer() {
        super.startProgressTimer()
        if (subtitleBody.isNotEmpty()) {
            postDelayed(subtitleTask, 100)
        }
        if (sponsorSkipEnabled && sponsorSegments.isNotEmpty()) {
            postDelayed(sponsorTask, 500)
        }
    }

    override fun cancelProgressTimer() {
        super.cancelProgressTimer()
        removeCallbacks(subtitleTask)
        removeCallbacks(sponsorTask)
        // 这两个以前只 post 不 remove：播放器/页面销毁后补偿重试还会继续跑（最多 20×500ms），
        // 可能对着已经没了的界面 seekTo / toast
        removeCallbacks(sponsorCompensateRetry)
        removeCallbacks(hideSponsorTipTask)
    }

    var subtitleTask: Runnable = object : Runnable {
        override fun run() {
            if (mCurrentState == CURRENT_STATE_PLAYING || mCurrentState == CURRENT_STATE_PAUSE) {
                setBottomSubtitleText()
            }
            if (mPostProgress) {
                postDelayed(this, 100)
            }
        }
    }

    // ─────────────────── 空降助手（赞助/恰饭片段自动跳过）───────────────────
    // 数据来自 BilibiliSponsorBlock（bsbsb.top）；判定/降级/上报逻辑对齐 PiliPlus 的 sponsor_block 模块。

    /** 总开关（设置页下发；**设置项默认开**——这里字段初值 false，等设置下发后再打开） */
    var sponsorSkipEnabled = false
        set(value) {
            field = value
            updateSponsorButtons()
        }

    /** 每个类别的处理策略（5 档）。默认档 = 11 个类别全部"跳过一次"（PiliPlus 的 DEFAULT_SKIP_TYPES） */
    var sponsorSkipTypes: Map<String, SponsorSkipType> = SponsorCategory.DEFAULT_SKIP_TYPES

    /** 最短片段时长（秒）：短于它的片段降级成"仅显示"（PiliPlus 的 blockLimit，0=不限制） */
    var sponsorLimitSec = 0

    /** 类别 → 色块颜色（设置页可自定义；空 = 用默认色） */
    var sponsorColors: Map<String, Int> = emptyMap()
        set(value) {
            field = value
            updateSeekBarMarks()
        }

    /** 跳过时是否弹提示（PiliPlus 的 blockToast） */
    var sponsorToastEnabled = true

    /** 是否上报"已跳过"（PiliPlus 的 blockTrack，服务端拿它统计省下多少时间） */
    var sponsorTrackEnabled = true

    /** 上报回调（由委托接到 API；为空就只跳不报） */
    var sponsorReporter: ((uuid: String) -> Unit)? = null

    /** 打开"片段列表/投票"界面（UI 在 SponsorBlockUi，免得播放器文件继续膨胀） */
    var onShowSponsorSegments: (() -> Unit)? = null

    /** 打开"提交片段"界面 */
    var onSubmitSponsorSegment: (() -> Unit)? = null

    /** 本视频的片段（按起点升序）；空 = 没数据或没启用 */
    var sponsorSegments: List<SponsorSegment> = emptyList()
        set(value) {
            field = value
            skippedSponsorUuids.clear()
            lastSponsorCheckSec = -1
            sponsorCompensationTries = 0
            SponsorDiag.log(
                "segments",
                "count=${value.size} enabled=$sponsorSkipEnabled duration=${duration} " +
                    "state=$mCurrentState marks=${value.count { it.skipTypeOf(sponsorSkipTypes, sponsorLimitSec) != SponsorSkipType.Disable }}"
            )
            sponsorVideoLabel = value.filter { it.isPoint }
                .map { SponsorCategory.labelOf(it.category) }
                .distinct()
                .joinToString("/")
            updateSeekBarMarks()
            updateSponsorButtons()
            if (value.isNotEmpty() && sponsorSkipEnabled) {
                removeCallbacks(sponsorTask)
                postDelayed(sponsorTask, 300)
                // ★ 越界补偿：数据是起播后才到的，此时可能已经站在片段里了（续播/空降/切分P）
                compensateSponsorEntry()
            }
            // 整片标记（如"赞助/恰饭"）：本视频整体属于某类别时提示一次（对齐 PiliPlus 的 videoLabel）
            removeCallbacks(sponsorVideoLabelToast)
            if (value.isNotEmpty() && sponsorVideoLabel.isNotBlank() && sponsorSkipEnabled && sponsorToastEnabled) {
                postDelayed(sponsorVideoLabelToast, 600)
            }
        }

    /** 当前视频的标识（提交片段要用）；番剧/本地视频为空 → 不支持提交 */
    var sponsorVideoId = ""
    var sponsorCid = ""

    /** 整片标记（如"赞助/恰饭"），由 [0,0] 形式的片段聚合而来（PiliPlus 的 videoLabel） */
    var sponsorVideoLabel = ""
        private set

    private val skippedSponsorUuids = HashSet<String>()
    private var lastSponsorCheckSec = -1

    /**
     * 顶栏「片段信息」按钮 = **ADS 小标**（点开当前视频的片段列表：哪一段是什么、能不能跳）。
     *
     * 语义与图标都对齐 PiliPlus（`header_control.dart:1843` 用 `MdiIcons.advertisements`，
     * 点 `showSBDetail()`）；顺序上也照抄：**提交在前、信息在后**。
     */
    private val mSponsorInfoTopBtn: TextView by lazy {
        TextView(context).apply {
            text = "ADS"
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dip(6), dip(2), dip(6), dip(2))
            background = androidx.core.content.ContextCompat.getDrawable(
                context, R.drawable.shape_player_ads_bg
            )
            visibility = GONE
            setOnClickListener { onShowSponsorSegments?.invoke() }
        }
    }

    /**
     * 顶栏「提交片段」图标 = **盾+播放**（对齐 PiliPlus `header_control.dart:1838` 的
     * `CustomIcons.shield_play_arrow`，点 `onBlock()`）；显示条件是 `enableSponsorBlock`。
     * 放在章节按钮后面、ADS 前面（与 PiliPlus 的排布一致）。
     */
    private val mSponsorSubmitTopBtn: ImageView by lazy {
        ImageView(context).apply {
            setImageResource(R.drawable.ic_player_sponsor_shield)
            visibility = GONE
            setPadding(dip(10), dip(10), dip(10), dip(10))
            setOnClickListener { onSubmitSponsorSegment?.invoke() }
        }
    }

    /** 两个空降按钮的显隐：提交=总开关开；片段信息=本视频有片段 */
    private fun updateSponsorButtons() {
        runCatching {
            val hasSegments = sponsorSegments.isNotEmpty()
            // 顶栏两个（章节按钮后面）：提交=盾+播放，片段信息=ADS
            mSponsorSubmitTopBtn.visibility = if (sponsorSkipEnabled) VISIBLE else GONE
            mSponsorInfoTopBtn.visibility = if (hasSegments) VISIBLE else GONE
        }.onFailure {
            // 以前是裸 runCatching：真出问题时按钮就是不出现，且没有任何线索
            SponsorDiag.log("ui-buttons", "更新空降按钮失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 叠在**可拖动进度条**上的色块层 */
    private val mSeekSegmentsDrawable = SegmentMarksDrawable()

    /** 上次给色块算归一化用的时长（变了才重算） */
    private var lastMarksDuration = -1L

    /** "手动跳过"气泡（"手动跳过"策略用；4 秒后自动消失） */
    private val mSponsorSkipTip: TextView by lazy {
        TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dip(14), dip(7), dip(14), dip(7))
            background = GradientDrawable().apply {
                cornerRadius = 18f * resources.displayMetrics.density
                setColor(Color.parseColor("#E6000000"))
            }
            visibility = GONE
            setOnClickListener {
                pendingManualSkip?.let { seg ->
                    pendingManualSkip = null
                    doSponsorSkip(seg)
                }
                hideSponsorSkipTip()
            }
        }
    }
    private var pendingManualSkip: SponsorSegment? = null
    private val hideSponsorTipTask = Runnable { hideSponsorSkipTip() }

    /** "本片标记：xx" 的延迟 toast；可取消，避免片段列表重建时连着弹好几次 */
    private val sponsorVideoLabelToast = Runnable {
        if (sponsorVideoLabel.isNotBlank()) toast("本片标记：$sponsorVideoLabel")
    }

    /** 越界补偿的重试（起播阶段状态还没到 PLAYING 时用） */
    private var sponsorCompensationTries = 0
    private val sponsorCompensateRetry = object : Runnable {
        override fun run() {
            if (!sponsorSkipEnabled || sponsorSegments.isEmpty()) return
            compensateSponsorEntry()
        }
    }

    private val sponsorTask = object : Runnable {
        override fun run() {
            // 时长可能比片段数据晚到（起播阶段），每跳顺便同步一次色块的归一化分母
            if (lastMarksDuration != duration) {
                lastMarksDuration = duration
                updateSeekBarMarks()
            }
            checkSponsorSkip()
            if (mPostProgress) {
                postDelayed(this, 500)
            }
        }
    }

    private fun sponsorSkipTypeOf(seg: SponsorSegment): SponsorSkipType =
        seg.skipTypeOf(sponsorSkipTypes, sponsorLimitSec)

    /**
     * 正常播放时的判定：**只认"跨过片段起点"**，不认"落在片段内部"。
     *
     * 这是 PiliPlus 的抗打架设计（`block_mixin.dart:93` 判定 `start ∈ [当前秒, 当前秒+1s)`）：
     * 用户手动拖到广告中间想看看到底是什么，不该被硬弹出去；只有正常播放跨过起点才处理。
     * 拖动进度 / 长按倍速 / 非播放态，一律不插手。
     */
    private fun checkSponsorSkip() {
        if (!sponsorSkipEnabled || sponsorSegments.isEmpty()) return
        if (mChangePosition || isSpeedPlaying) return
        if (mCurrentState != CURRENT_STATE_PLAYING) return
        val pos = currentPositionWhenPlaying
        if (pos <= 0L) return
        val sec = (pos / 1000L).toInt()
        if (sec == lastSponsorCheckSec) return
        lastSponsorCheckSec = sec
        val winStart = sec * 1000L
        val winEnd = winStart + 1000L   // 半开区间 [winStart, winEnd)
        val hit = sponsorSegments.firstOrNull { seg ->
            !seg.isPoint && seg.startMs >= winStart && seg.startMs < winEnd
        } ?: return
        handleSponsorHit(hit)
    }

    /**
     * 越界补偿：片段数据到达时已经站在片段内部。
     * PiliPlus 也只在"首次拿到数据"时做（`handleSBData` 里 `_blockListener == null` 分支），
     * 我们等价地在 `sponsorSegments` 赋值时调一次 —— 之后不再补，避免和用户手动拖拽打架。
     */
    private fun compensateSponsorEntry() {
        // ★ 起播瞬间状态还是 PREPARING/缓冲中：这时不能放弃，挂起重试
        //   （PiliPlus 也是 `player.stream.playing.firstWhere { … }` 等播放态才跳）
        if (mCurrentState != CURRENT_STATE_PLAYING && mCurrentState != CURRENT_STATE_PAUSE) {
            if (sponsorCompensationTries++ < 20) {
                postDelayed(sponsorCompensateRetry, 500)
            }
            return
        }
        var pos = currentPositionWhenPlaying
        if (pos <= 0L) return
        sponsorCompensationTries = 0
        var endTarget = -1L
        var count = 0
        var guard = 0
        // 连续片段**一起跳**：对齐 PiliPlus 的 getFirstSegment —— 下一段起点紧挨着上一段
        // （差值 < 100ms）就继续往后跳，避免"跳完一个广告又落进下一个广告"来回蹦。
        while (guard++ < 20) {
            val hit = sponsorSegments.firstOrNull { seg ->
                !seg.isPoint && pos >= seg.startMs - 100L && pos < seg.endMs &&
                    when (sponsorSkipTypeOf(seg)) {
                        SponsorSkipType.AlwaysSkip -> true
                        SponsorSkipType.SkipOnce -> skippedSponsorUuids.add(seg.UUID)
                        else -> false
                    }
            } ?: break
            endTarget = hit.endMs
            pos = hit.endMs + 100L
            count++
        }
        SponsorDiag.log(
            "compensate",
            "pos=$pos state=$mCurrentState hits=$count endTarget=$endTarget tries=$sponsorCompensationTries"
        )
        if (endTarget > 0L) {
            val target = if (duration > 0L) {
                (endTarget + 100L).coerceAtMost(duration)
            } else {
                endTarget + 100L
            }
            seekTo(target)
            if (sponsorToastEnabled) {
                toast(if (count > 1) "已跳过 $count 个片段" else "已跳过片段")
            }
        }
    }

    /** 命中片段 → 按该类别的策略决定：自动跳 / 弹手动按钮 / 只显示 */
    private fun handleSponsorHit(seg: SponsorSegment) {
        when (sponsorSkipTypeOf(seg)) {
            SponsorSkipType.AlwaysSkip -> doSponsorSkip(seg)
            SponsorSkipType.SkipOnce -> {
                // "跳过一次"：标记后再拖回来不重复跳（PiliPlus 的默认档）
                if (skippedSponsorUuids.add(seg.UUID)) {
                    doSponsorSkip(seg)
                }
            }
            SponsorSkipType.SkipManually -> showSponsorSkipTip(seg)
            SponsorSkipType.ShowOnly, SponsorSkipType.Disable -> Unit
        }
    }

    /** 给"片段列表"界面用：这个片段当前会被怎么处理（显示成"跳过/跳至"按钮） */
    fun sponsorSkipTypeFor(seg: SponsorSegment): SponsorSkipType = sponsorSkipTypeOf(seg)

    /**
     * 给"片段列表"界面用：手动跳到片段起点（仅显示档）/ 终点（其它档）。
     * 走自己的 seekTo ✓，但**不**上报"已跳过"（用户只是点了列表里的一行，不代表自动跳过）。
     */
    fun sponsorJumpTo(seg: SponsorSegment, toEnd: Boolean) {
        val target = if (toEnd) seg.endMs + 100L else seg.startMs
        seekTo(if (target < 0L) 0L else target)
    }

    private fun doSponsorSkip(seg: SponsorSegment) {
        // 跳到片段终点（+100ms 容错，避免正好落在终点帧上）；时长未知时不设上限
        val target = if (duration > 0L) {
            (seg.endMs + 100L).coerceAtMost(duration)
        } else {
            seg.endMs + 100L
        }
        SponsorDiag.log(
            "skip",
            "cat=${seg.category} ${seg.startMs}->${seg.endMs} target=$target pos=$currentPositionWhenPlaying"
        )
        // 走我们自己的 seekTo 覆写：它会把续播账本/GSY 的一次性槽一起同步，
        // 否则这次跳转会被随后的任何一次 re-prepare 抹掉（历史上踩过）。
        seekTo(target)
        if (sponsorToastEnabled) {
            // 番剧的 clip_info 自带提示语（B 站的 toast_text），有就用它
            val msg = seg.description.ifBlank { "已跳过${SponsorCategory.shortLabelOf(seg.category)}片段" }
            toast(msg)
        }
        // 只上报服务端认识的片段：PGC 片段的 UUID 是我们本地造的（pgc- 前缀），报上去是脏数据
        if (sponsorTrackEnabled && !seg.UUID.startsWith("pgc-")) {
            sponsorReporter?.invoke(seg.UUID)
        }
    }

    private fun showSponsorSkipTip(seg: SponsorSegment) {
        pendingManualSkip = seg
        mSponsorSkipTip.text = "跳过 ${SponsorCategory.shortLabelOf(seg.category)}（${CommonUtil.stringForTime(seg.startMs)}）"
        // ★ 每次显示都按**当前**模式/尺寸重算位置：PiliPlus `video/view.dart:1490` 是全屏时
        //   bottom = max(75, 高度*0.25)，而 initView() 那一刻 mode 还是默认值、height 还是 0，
        //   所以这个计算只能放在这里（放 initView 里等于永远走小屏分支）
        (mSponsorSkipTip.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.bottomMargin = if (mode == PlayerMode.FULL) {
                maxOf(dip(75), (height * 0.25f).toInt())
            } else {
                dip(75)
            }
            mSponsorSkipTip.layoutParams = lp
        }
        mSponsorSkipTip.visibility = VISIBLE
        removeCallbacks(hideSponsorTipTask)
        postDelayed(hideSponsorTipTask, 4000)
    }

    private fun hideSponsorSkipTip() {
        mSponsorSkipTip.visibility = GONE
        pendingManualSkip = null
    }

    /**
     * 按时间显示对应字幕。
     *
     * @param timeMs 指定显示哪个时间点的字幕（拖动进度条时传拖动目标位置，实现"字幕跟手"）；
     *               不传则用当前播放位置。
     *
     * 用二分查找定位，而不是从上次的索引逐条走：
     * 拖动时目标位置可能一次跳几分钟，逐条走会遍历上千条字幕（每帧一次），必然卡顿。
     */
    private fun setBottomSubtitleText(timeMs: Long = currentPositionWhenPlaying) {
        if (subtitleBody.isEmpty()) return
        val currentTime = timeMs

        // 已经落在上一条字幕区间内就沿用它，避免频繁二分
        val cached = subtitleIndex
        if (cached in subtitleBody.indices) {
            val c = subtitleBody[cached]
            if (currentTime >= c.from && currentTime <= c.to) {
                mBottomSubtitleTV.text = c.content
                mBottomSubtitleTV.visibility = VISIBLE
                return
            }
        }

        // 二分找到最后一条 from <= currentTime 的字幕
        var lo = 0
        var hi = subtitleBody.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (subtitleBody[mid].from <= currentTime) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found >= 0 && currentTime <= subtitleBody[found].to) {
            subtitleIndex = found
            mBottomSubtitleTV.text = subtitleBody[found].content
            mBottomSubtitleTV.visibility = VISIBLE
        } else {
            // 这个时间点没有字幕：保持隐藏（拖动时先不猜，松手后按真实播放位置刷新）
            mBottomSubtitleTV.visibility = GONE
        }
    }

    override fun onClickUiToggle(e: MotionEvent?) {
        super.onClickUiToggle(e)
        videoPlayerCallBack?.onClickUiToggle(e)
    }

    override fun hideAllWidget() {
        super.hideAllWidget()
        if (isPicInPicMode) {
            if (showBottomProgressBarInPipMode) {
                setViewShowState(mBottomProgressBar, VISIBLE)
            } else {
                setViewShowState(mBottomProgressBar, INVISIBLE)
            }
        } else {
            if (mode == PlayerMode.FULL && showBottomProgressBarInFullMode) {
                setViewShowState(mBottomProgressBar, VISIBLE)
            } else if ((mode == PlayerMode.SMALL_FLOAT || mode == PlayerMode.SMALL_TOP)
                && showBottomProgressBarInSmallMode) {
                setViewShowState(mBottomProgressBar, VISIBLE)
            } else {
                setViewShowState(mBottomProgressBar, INVISIBLE)
            }
        }
    }

    private fun showAllWidget() {
        if (mIfCurrentIsFullscreen && mLockCurScreen && mNeedLockFull) {
            setViewShowState(mLockScreen, VISIBLE)
        } else {
            if (mIfCurrentIsFullscreen && !mSurfaceErrorPlay
                && mCurrentState == CURRENT_STATE_ERROR) {
                changeUiToPlayingShow()
            } else if (mCurrentState == CURRENT_STATE_PREPAREING) {
                changeUiToPreparingShow()
            } else if (mCurrentState == CURRENT_STATE_PLAYING) {
                changeUiToPlayingShow()
            } else if (mCurrentState == CURRENT_STATE_PAUSE) {
                changeUiToPauseShow()
            } else if (mCurrentState == CURRENT_STATE_AUTO_COMPLETE) {
                changeUiToCompleteShow()
            } else if (mCurrentState == CURRENT_STATE_PLAYING_BUFFERING_START
                && mBottomContainer != null) {
                changeUiToPlayingBufferingShow()
            }
        }
    }

    override fun setStateAndUi(state: Int) {
        super.setStateAndUi(state)
        val playBtnImageRes = if (state == CURRENT_STATE_PLAYING) {
            R.drawable.bili_player_play_can_pause
        } else {
            R.drawable.bili_player_play_can_play
        }
        mButtomPlay.setImageResource(playBtnImageRes)
        videoPlayerCallBack?.setStateAndUi(state)
    }

    override fun setViewShowState(view: View, visibility: Int) {
        if (isPicInPicMode) {
            if (view.id == mStartButton.id || view.id == mBottomProgressBar.id) {
                view.visibility = visibility
            }
        } else if (isHoldUp){
            if (view.id == mBottomProgressBar.id) {
                view.visibility = visibility
            } else {
                view.visibility = GONE
            }
        } else {
            super.setViewShowState(view, visibility)
            if (view.id == mBottomLayout.id) {
                // ★ 通知双指控制器：控件可见状态变化
                if (::pinchToZoom.isInitialized) {
                    pinchToZoom.setControlsVisible(visibility == VISIBLE)
                }
                // 章节按钮跟随控件显示/隐藏
                chapterManager.onControlsVisibleChanged(visibility == VISIBLE)
                mBottomSubtitleTV.translationY =
                    if (visibility == VISIBLE) 0f else dip(40).toFloat()
                when (mode) {
                    PlayerMode.SMALL_FLOAT -> {
                        mDragBarLayout.visibility = visibility
                    }
                    PlayerMode.SMALL_TOP -> {
                        mDragBarLayout.visibility = View.GONE
                    }
                    PlayerMode.FULL -> {
                        statusBarHelper?.isShowStatus = visibility == View.VISIBLE
                    }
                }
            }
        }
    }

    override fun touchSurfaceUp() {
        // 亮度手势结束后重置跟踪，下一轮手势重新从系统亮度读取
        lastGestureBrightness = -1f
        super.touchSurfaceUp()
        // ★★ 必须在 super 之后清 mChangePosition ★★
        // GSY v13 只在 touchSurfaceDown() 里清它，touchSurfaceUp() **只读不写**，
        // 而 checkSponsorSkip() 的守卫是 `if (mChangePosition || isSpeedPlaying) return`。
        // 结果：用户拖过一次进度条之后，这个标记永远是 true → **之后整部片子都不再自动跳过**
        // （症状就是"一开始能跳，拖着拖着就不跳了"）。这里补上清理。
        // 位置必须在 super 之后：super 内部要先读这个标记才会真正 seek。
        mChangePosition = false
        // super 里会 dismissProgressDialog()（已重写为同时收预览图），
        // 这里再兜一次：触摸被取消/中途被别的逻辑吃掉时也保证预览图不残留
        hideSeekPreview()
    }

    /** 双击快进/快退的步长（默认 10 秒，设置页可改） */
    /** 双击快进/快退的步长（毫秒）。**0 = 关闭**（默认，设置里可选 5/10/15/30 秒） */
    var doubleTapSeekMs = 0L

    /**
     * 双击：左 1/3 快退、右 1/3 快进，中间保持 GSY 默认行为（播放/暂停）。
     *
     * GSY 的默认实现是 `touchDoubleUp() { clickStartIcon() }` —— 双击 = 播放/暂停。
     * 步长设置成"关闭"（0）时整个屏幕都用 GSY 默认行为（双击 = 播放/暂停）；
     * 设了步长才按左/右三分之一快退/快进。
     * 不弹任何提示（用户要求：快进就快进、快退就快退，提示挡画面）。
     */
    override fun touchDoubleUp(e: MotionEvent?) {
        if (e == null || !mHadPlay) {
            super.touchDoubleUp(e)
            return
        }
        // 手势锁 / 小窗拖拽模式下不处理，交回 GSY
        if (mHideKey && mShowVKey) {
            super.touchDoubleUp(e)
            return
        }
        // 步长"关闭"（0）：双击哪都一样 —— 播放/暂停（交给 GSY 的 clickStartIcon）。
        // 注意不能 return 掉：否则左右两侧的双击会被吃掉，什么都不发生。
        if (doubleTapSeekMs <= 0L) {
            super.touchDoubleUp(e)
            return
        }
        val w = if (touchViewWidth > 0) touchViewWidth else width
        val total = duration
        if (w <= 0 || total <= 0L) {
            super.touchDoubleUp(e)
            return
        }
        val pos = currentPosition
        val target: Long
        when {
            e.x < w / 3f -> target = (pos - doubleTapSeekMs).coerceAtLeast(0L)
            e.x > w * 2f / 3f -> target = (pos + doubleTapSeekMs).coerceAtMost(total)
            else -> {
                super.touchDoubleUp(e)
                return
            }
        }
        try {
            // 只跳转，不弹任何提示（提示挡画面，用户明确不要）
            seekTo(target)
        } catch (ex: Exception) {
            // 播放器刚 release / Activity 正在销毁：丢掉这次手势，别让它崩
            miaoLogger() error "touchDoubleUp seek failed: ${ex.message}"
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 父容器（小窗拖拽）抢走触摸时框架只发 CANCEL，而 GSY 自己不处理 CANCEL。
        // 原先这里用 id == surface_container 判定，但本 View 的 id 是 video_player，
        // 整个分支从来没生效过 → 改成真正的手势中止处理。
        if (event.action == MotionEvent.ACTION_CANCEL) {
            if (mHideKey && mShowVKey) {
                return true
            }
            abortGesture()
        }
        return super.onTouchEvent(event)
    }

    private var danmakuDownTouchX = 0f
    private var danmakuDownTouchY = 0f
    private var isDanmakuTouchActive = false

    /**
     * 在 DanmakuView 上直接监听触摸（OnTouchListener 比 onTouchEvent 先执行），
     * 检测弹幕时间戳点击。命中后通过 PlayerSeekBus 跳转，返回 true 消费事件。
     * 不命中则返回 false，事件正常流向 surface_container 处理视频手势。
     */
    private fun initDanmakuTouchListener() {
        val hitSlop = context.dip(25f)
        mDanmakuView.isClickable = false
        mDanmakuView.isLongClickable = false
        mDanmakuView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    danmakuDownTouchX = event.x
                    danmakuDownTouchY = event.y
                    isDanmakuTouchActive = hasTimestampDanmakuAt(event.x, event.y, hitSlop)
                    isDanmakuTouchActive
                }
                MotionEvent.ACTION_MOVE -> {
                    isDanmakuTouchActive
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.x - danmakuDownTouchX)
                    val dy = Math.abs(event.y - danmakuDownTouchY)
                    val handled = if (isDanmakuTouchActive && dx < hitSlop && dy < hitSlop) {
                        checkDanmakuClick(event.x, event.y)
                    } else {
                        false
                    }
                    isDanmakuTouchActive = false
                    handled
                }
                MotionEvent.ACTION_CANCEL -> {
                    isDanmakuTouchActive = false
                    false
                }
                else -> false
            }
        }
    }

    // ---------- 子容器缩放 ----------

    /** zoomWrapper：GSY 渲染器放在这个子容器里，缩放变换只作用于它，
     *  surface_container 本身不受 scaleX/Y 影响，触摸事件正常分发 */
    private var zoomWrapper: FrameLayout? = null

    private fun initZoomWrapper() {
        zoomWrapper = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        mSurfaceContainer.addView(zoomWrapper)
        mTextureViewContainer = zoomWrapper
    }

    override fun addTextureView() {
        super.addTextureView()
        val wrapper = zoomWrapper ?: return
        if (::pinchToZoom.isInitialized) {
            pinchToZoom.resetImmediate()
        }
        pinchToZoom = PinchToZoomHelper(wrapper, mRestoreScaleBtn)
        // 新 helper 的 enabled 默认 true，必须重新套用"锁屏/横屏非全屏禁用"状态，
        // 否则锁屏后自动连播下一集、或横屏小窗切剧集时双指缩放又会生效
        updatePinchState()
        mRestoreScaleBtn.setOnClickListener {
            pinchToZoom.animateReset()
        }
    }

    private fun isTouchHitDanmaku(x: Float, y: Float, hitSlop: Float): Boolean {
        val visibleDanmakus = mDanmakuView.currentVisibleDanmakus ?: return false
        if (visibleDanmakus.isEmpty) return false
        val hitBounds = android.graphics.RectF()
        var hit = false
        visibleDanmakus.forEachSync(object : master.flame.danmaku.danmaku.model.IDanmakus.DefaultConsumer<BaseDanmaku>() {
            override fun accept(danmaku: BaseDanmaku?): Int {
                danmaku ?: return 0
                hitBounds.set(danmaku.left, danmaku.top, danmaku.right, danmaku.bottom)
                if (hitBounds.intersect(x - hitSlop, y - hitSlop, x + hitSlop, y + hitSlop)) {
                    hit = true
                    return 1
                }
                return 0
            }
        })
        return hit
    }

    /**
     * 检查触摸位置是否命中了包含时间戳（如 "01:23" 或 "1:23:45"）的弹幕。
     * 无副作用——不执行跳转，仅用于决定是否拦截触摸事件。
     */
    private fun hasTimestampDanmakuAt(x: Float, y: Float, hitSlop: Float): Boolean {
        val visibleDanmakus = mDanmakuView.currentVisibleDanmakus ?: return false
        if (visibleDanmakus.isEmpty) return false
        val hitBounds = android.graphics.RectF()
        val timestampRegex = Regex("""\d{1,3}[:：]\d{1,2}(?:[:：]\d{1,2})?""")
        var hit = false
        visibleDanmakus.forEachSync(object : master.flame.danmaku.danmaku.model.IDanmakus.DefaultConsumer<BaseDanmaku>() {
            override fun accept(danmaku: BaseDanmaku?): Int {
                danmaku ?: return 0
                hitBounds.set(danmaku.left, danmaku.top, danmaku.right, danmaku.bottom)
                if (hitBounds.intersect(x - hitSlop, y - hitSlop, x + hitSlop, y + hitSlop)) {
                    val text = danmaku.text.toString()
                    if (timestampRegex.containsMatchIn(text)) {
                        hit = true
                        return 1
                    }
                }
                return 0
            }
        })
        return hit
    }

    /**
     * 检测弹幕时间戳点击。x,y 是 DanmakuView 坐标系（OnTouchListener 直接给）。
     */
    private fun checkDanmakuClick(x: Float, y: Float): Boolean {
        val visibleDanmakus = mDanmakuView.currentVisibleDanmakus ?: return false
        if (visibleDanmakus.isEmpty) return false
        val hitSlop = context.dip(25f)
        val hitBounds = android.graphics.RectF()
        val hitDanmakus = java.util.ArrayList<BaseDanmaku>()
        visibleDanmakus.forEachSync(object : master.flame.danmaku.danmaku.model.IDanmakus.DefaultConsumer<BaseDanmaku>() {
            override fun accept(danmaku: BaseDanmaku?): Int {
                danmaku ?: return 0
                hitBounds.set(danmaku.left, danmaku.top, danmaku.right, danmaku.bottom)
                if (hitBounds.intersect(x - hitSlop, y - hitSlop, x + hitSlop, y + hitSlop)) {
                    hitDanmakus.add(danmaku)
                }
                return 0
            }
        })
        if (hitDanmakus.isEmpty()) return false
        // 解析时间戳：支持 "01:23"、"1:23"、"01:02:03"、中文冒号
        val timestampRegex = Regex("""\d{1,3}[:：]\d{1,2}(?:[:：]\d{1,2})?""")
        for (danmaku in hitDanmakus) {
            val text = danmaku.text.toString()
            val match = timestampRegex.find(text) ?: continue
            val parts = match.value.split(Regex("""[:：]""")).map { it.toInt() }
            val seconds = when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> 0
            }
            PlayerSeekBus.onSeek?.invoke(seconds * 1000L)
            return true
        }
        return false
    }

    override fun startPrepare() {
        // super.startPrepare()
        this.gsyVideoManager.listener()?.onCompletion()

        if (mVideoAllCallBack != null) {
            Debuger.printfLog("onStartPrepared")
            mVideoAllCallBack.onStartPrepared(mOriginUrl, *arrayOf(mTitle, this))
        }

        this.gsyVideoManager.setListener(this)
        this.gsyVideoManager.playTag = mPlayTag
        this.gsyVideoManager.playPosition = mPlayPosition

        try {
            (mContext as? Activity)?.window
                ?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (var2: java.lang.Exception) {
            var2.printStackTrace()
        }

        mBackUpPlayingBufferState = -1
        this.gsyVideoManager.prepare(
            mUrl,  mMapHeadData ?: hashMapOf(),
            mLooping, mSpeed, mCache, mCachePath, mOverrideExtension
        )
        setStateAndUi(CURRENT_STATE_PREPAREING)
    }

    override fun onPrepared() {
        super.onPrepared()
        onPrepareDanmaku(this)
        videoPlayerCallBack?.onPrepared()
    }
    override fun onAutoCompletion() {
        super.onAutoCompletion()
        // 自然播完：续播点已经没有意义了（重播/连播都该从 0 开始），
        // 而计时器刚才还在把"当前位置"往投递槽里塞（≈ 片尾）→ 不清就会 seek 到末尾立刻又结束
        resetRestartGuard()
        videoPlayerCallBack?.onAutoCompletion()
        releaseDanmaku()
    }


    override fun onVideoPause() {
        super.onVideoPause()
        // 账本：GSY 的 onVideoPause() 只在底层 isPlaying() 为真时才写 mCurrentPosition
        // （源码 v13.0.0:518-531），暂停中/缓冲中调用它等于什么都没记 —— 所以这里自己记一笔
        userPaused = true
        noteResumePosition(currentPositionWhenPlaying)
        danmakuOnPause()
        videoPlayerCallBack?.onVideoPause()
    }

    override fun onVideoResume(isResume: Boolean) {
        userPaused = false
        super.onVideoResume(isResume)
        danmakuOnResume()
        videoPlayerCallBack?.onVideoResume(isResume)
    }

    override fun onVideoResume() {
        onVideoResume(true)
    }

    /**
     * 拖动进度条 / 通知栏拖进度 / 弹幕空降：GSY 的 `seekTo()` 只把位置转发给底层（源码 :1142），
     * 两个槽都不知道 —— 不记这一笔账的话，之后任何一次 re-prepare 都会跳回旧位置。
     * 顺手把两个槽也同步成新位置（暂停态 seek 不响不闪）：用户刚定的位置就是新意图。
     */
    override fun seekTo(position: Long) {
        super.seekTo(position)
        if (position >= 0L) {
            lastGoodPositionMs = position
            lastGoodUpdateAt = System.currentTimeMillis()
            mSeekOnStart = position
            mCurrentPosition = position
            // 用户/我们自己刚定的位置就是新意图：显式落点作废，之前挂的兜底检查也撤掉
            pendingSeekMs = 0L
            removeCallbacks(restartGuardRunnable)
        }
    }

    /**
     * 回前台：**不再调 `onVideoResume()`**。
     *
     * 旧实现叫"静默重连 Surface"，但按 GSY 源码它做不到这件事：
     *  - `onVideoResume()` 只在 `CURRENT_STATE_PAUSE` 时才干活（:551），播放中回前台是纯空转
     *    （补画面的 `addTextureView()` 只在 prepare 时被调用，:852）；
     *  - 暂停态回前台调它，会**真的 start() 再被我们按回去** —— 那就是"回来闪一下/响一声"；
     *  - 而且它读完就把 `mCurrentPosition` 清零（:562），偏偏这之后 GSY 很可能因为 surface
     *    重建而重新 prepare，槽空了就只能从 0 开始播。
     *
     * 现在只做两件安全的事：把空槽补上账本位置 + 位置真的塌了才 seek（暂停态 seek 不响不闪）。
     */
    fun reconnectSurfaceQuietly() {
        val state = mCurrentState
        val pos = resumePosition
        if (pos > 0L) {
            armResumeSlot()
            // 播放器活着但位置掉到 0 附近（说明被重建过）→ 直接拽回去，不用等 prepare
            if ((state == CURRENT_STATE_PLAYING || state == CURRENT_STATE_PAUSE)
                && currentPosition < 3_000L
            ) {
                try { seekTo(pos) } catch (_: Exception) {}
            }
            scheduleRestartGuard()
        }
    }

    override fun clickStartIcon() {
        val posBefore = resumePosition
        // 点播放走的是 GSY 的 clickStartIcon()：它的 PAUSE 分支**既不 seek 也不调 onVideoResume()**，
        // 只是让底层 start() —— 底层播放器若已被重建，位置就是 0，只有 mSeekOnStart 能在
        // 随后那次 prepare 里救回来，所以先把槽补好。
        if (mCurrentState == CURRENT_STATE_PAUSE) {
            armResumeSlot()
            // 用显式落点：点播放后若内部重新 prepare，位置必须落到点播放前那一帧
            if (posBefore > 0L) armSeekOnPrepare(posBefore)
        }
        super.clickStartIcon()
        if (mCurrentState == CURRENT_STATE_PLAYING) {
            // 用户明确要播：清掉"暂停意图"，否则下次 re-prepare 会被按回暂停
            userPaused = false
            // 点播放后 GSY 可能已经偷偷从 0 重新 prepare（见 restartGuardRunnable 注释）
            if (posBefore > 0L) scheduleRestartGuard()
            // PlaybackService 状态已在 onVideoResume / setStateAndUi 中同步
            danmakuOnResume()
        } else if (mCurrentState == CURRENT_STATE_PAUSE) {
            // PlaybackService 状态已在 onVideoPause / setStateAndUi 中同步
            danmakuOnPause()
        }
    }
    override fun onCompletion() {
        super.onCompletion()
        // ★ 这里**绝对不能**复位续播账本！
        //
        // GSY 的 `startPrepare()`（GSYVideoView:346-348）在**每一次 prepare 之前**都会调
        // `listener().onCompletion()` —— 也就是"旧媒体要被换掉了"，而不是"播完了"。
        // 同一个播放器换清晰度/换语言/重试、以及 surface 重建后的内部 re-prepare 全都会走这里。
        // r 轮我们在这里加了 `resetRestartGuard()`（当时还给 resetRestartGuard 加了清 mSeekOnStart），
        // 结果就是：投递好的续播落点在 prepare 之前被自己抹掉 → **换清晰度从头播**、
        // GSY 内部 re-prepare 也从 0 播（这正是最早那个"从 0 开始"的真凶）。
        //
        // 真正的"播完了"是 `onAutoCompletion()`（那里才复位）；真正的"换视频"由
        // `PlayerDelegate2.loadPlayerSource()` 里的 `lastLoadedSourceId` 判定后显式复位。
        releaseDanmaku()
    }

    override fun release() {
        releaseDanmaku()
        // 释放前清掉手势 HUD 弹窗，避免窗口泄漏（WindowLeaked）
        dismissCachedDialogs()
        gsyVideoManager?.player?.stop()
        super.release()
    }

    /**
     * 从 Activity 安全分离（不杀死播放器，不释放弹幕）
     * Activity 销毁时调用，播放器留在 GSYVideoManager 单例中继续存活。
     * 下次新建 DanmakuVideoPlayer 并 startPrepare() 时自动重连。
     */
    /** detachView 时是否保持播放（后台播放模式），用完自动复位 */
    var keepPlayingOnDetach = false

    fun detachView() {
        // 不释放弹幕 — 重连时复用
        // 后台播放模式下不能在这里暂停：Activity 重建后没有任何路径恢复播放，
        // 界面会定格、通知栏却认为还在播（keepPlayingOnDetach 由 PlayerDelegate2 设置）
        if (!keepPlayingOnDetach) {
            // 必须走 onVideoPause() 而不是直调 manager.pause()：
            // 后者绕过 GSY 状态机，mCurrentState 仍是 PLAYING(2) —— 之后任何一次
            // 状态推送都会把"播放中"报给通知栏（画面停着、通知栏在播、进度空转），
            // isPause() 判定也会跟着错（回前台误续播）
            onVideoPause()
        }
        keepPlayingOnDetach = false
        // Activity 销毁时必须清掉手势 HUD：三个 Dialog 绑在旧 Activity 的 window
        // token 上，留着会 WindowLeaked，复用后还可能 show 到错误的 Activity
        dismissCachedDialogs()
        // 不调 super.release() —— GSYVideoManager 保留播放器
    }

    fun closeVideo() {
        videoPlayerCallBack?.onVideoClose()
    }

    fun releaseDanmaku() {
        mDanmakuView.release()
        // 【已移除】V2引擎释放 — V2引擎已废弃
    }

    fun setDanmakuClickListener(listener: master.flame.danmaku.controller.IDanmakuView.OnDanmakuClickListener?) {
        mDanmakuView.setOnDanmakuClickListener(listener)
    }



    // 【已移除】feedNewDanmakuEngine() — V2引擎已废弃
    private fun initDanmakuContext() {
        // 【已移除】V2引擎初始化 — V2引擎已废弃
        mDanmakuView.setCallback(object : DrawHandler.Callback {
            override fun updateTimer(timer: DanmakuTimer) {
                // 约 1 秒记一次"正常播放到的位置"（这个回调很频繁，别每次都查播放器）
                val now = System.currentTimeMillis()
                if (now - lastGoodUpdateAt < 1000L) return
                lastGoodUpdateAt = now
                if (mCurrentState == CURRENT_STATE_PLAYING) {
                    val p = try { currentPosition } catch (_: Exception) { 0L }
                    if (p > lastGoodPositionMs) lastGoodPositionMs = p
                    // 投递槽跟着播放位置走：任何时刻被重建都能接着"当前位置"播。
                    // 两个例外：① prepare 进行中（槽正被 GSY 消费，插进来会把落点写成 0）；
                    //          ② 有显式落点（换清晰度/换语言/重试投递的），盖掉就是"换清晰度从头播"
                    if (p > 0L && !isPreparing) {
                        mCurrentPosition = p
                        if (pendingSeekMs <= 0L) mSeekOnStart = p
                    }
                }
            }
            override fun drawingFinished() {}
            override fun danmakuShown(danmaku: BaseDanmaku) {}
            override fun prepared() {
                mDanmakuView.start()
                if (danmakuStartSeekPosition != -1L) {
                    resolveDanmakuSeek(
                        this@DanmakuVideoPlayer,
                        danmakuStartSeekPosition
                    )
                    danmakuStartSeekPosition = -1
                }
                resolveDanmakuShow()
            }
        })
        mDanmakuView.enableDanmakuDrawingCache(true)
    }

    protected fun danmakuOnPause() {
        if (mDanmakuView != null && mDanmakuView.isPrepared) {
            mDanmakuView.pause()
        // 【已移除】V2暂停 — V2引擎已废弃
        }
    }

    protected fun danmakuOnResume() {
        if (mDanmakuView != null && mDanmakuView.isPrepared) {
            mDanmakuView.start(currentPositionWhenPlaying)
        // 【已移除】V2恢复 — V2引擎已废弃
        }
    }

    /**
     * 开始播放弹幕
     */
    private fun onPrepareDanmaku(gsyVideoPlayer: DanmakuVideoPlayer) {
        if (danmakuParser != null) {
            mDanmakuView.prepare(danmakuParser, danmakuContext)
        // 【已移除】V2引擎准备 — V2引擎已废弃
        }
    }

    /**
     * 弹幕的显示与关闭
     */
    private fun resolveDanmakuShow() {
        post {
            // 这里不要无条件 mDanmakuView.show()：弹幕处于"关"的状态时会先亮一帧再隐藏，
            // 还会把已经停掉的绘制任务重新拉起来
            if (isShowDanmaku) {
                if (!mDanmakuView.isShown) {
                    mDanmakuView.show()
                }
                mDanmakuSwitchIV.setImageResource(R.drawable.bili_player_danmaku_is_open)
                mDanmakuSwitchTV.text = "弹幕开"
                mMiniSendDanmakuIV.alpha = 1f
            } else {
                if (mDanmakuView.isShown) {
                    mDanmakuView.hide()
                // 【已移除】V2隐藏 — V2引擎已废弃
                }
                mDanmakuSwitchIV.setImageResource(R.drawable.bili_player_danmaku_is_closed)
                mDanmakuSwitchTV.text = "弹幕关"
                mMiniSendDanmakuIV.alpha = 0.5f
            }
        }
    }

    /**
     * 弹幕偏移
     */
    private fun resolveDanmakuSeek(gsyVideoPlayer: DanmakuVideoPlayer, time: Long) {
        if (mHadPlay && mDanmakuView.isPrepared) {
            mDanmakuView.seekTo(time)
        // 【已移除】V2同步seek — V2引擎已废弃
        }
    }

    /**
     * 添加弹幕
     */
    fun addDanmaku(danmaku: BaseDanmaku) {
        mDanmakuView.addDanmaku(danmaku)
        // 【已移除】V2引擎添加弹幕 — V2引擎已废弃
    }

    /**
     * 控制器拓展按钮
     */
    fun setDanmakuSwitchOnClickListener(l: OnClickListener) {
        mDanmakuSwitch.setOnClickListener {
            startDismissControlViewTimer()
            l.onClick(it)
        }
    }

    fun setExpandButtonText(text: String) {
        mExpandBtnTV.text = text
    }
    fun showExpandButton() {
        mExpandBtnLayout.visibility = View.VISIBLE
    }
    fun hideExpandButton() {
        mExpandBtnLayout.visibility = View.GONE
    }
    fun setExpandButtonOnClickListener(l: OnClickListener) {
        mExpandBtnLayout.setOnClickListener(l)
    }

    fun setSendDanmakuButtonOnClickListener(l: OnClickListener) {
        mMiniSendDanmakuIV.setOnClickListener(l)
        mSendDanmakuTV.setOnClickListener(l)
    }

    fun setSendDanmakuButtonOnLongClickListener(l: OnLongClickListener) {
        mMiniSendDanmakuIV.setOnLongClickListener(l)
    }

    fun serHoldUpButtonOnClickListener(l: OnClickListener) {
        mHoldUpBtn.setOnClickListener(l)
    }

    /**
     * 更新定时关闭倒计时显示
     * @param seconds 剩余秒数，0或负数表示关闭
     */
    fun updateAutoStopTimer(seconds: Int) {
        if (seconds > 0) {
            val minute = seconds / 60
            val second = seconds % 60
            mAutoStopTimerTV.text = if (second == 0) {
                "${minute}'"
            } else {
                "${minute}'${second}\""
            }
            mAutoStopTimerTV.visibility = View.VISIBLE
        } else {
            mAutoStopTimerTV.visibility = View.GONE
        }
    }

    private var mDialogOffsetText: TextView? = null

    /**
     * 从 View/Context 链中解包出 Activity。
     * Compose 的 AndroidView 给 View 的 context 是 ContextThemeWrapper，
     * 直接 as? Activity 会失败，需要逐层解包。
     */
    private fun getActivity(): Activity? {
        // 优先使用显式刷新的 Activity（Activity 重建/keepPlayerView 复用后
        // View.mContext 在 Android 13+ 无法反射替换，必须由外部显式更新）
        currentActivity?.let { act ->
            if (!act.isFinishing && !act.isDestroyed) return act
        }
        var ctx: android.content.Context? = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /** 显式刷新播放器持有的 Activity 引用（Activity 重建/复用播放器时调用） */
    fun updateActivity(activity: Activity?) {
        if (activity !== currentActivity) {
            // Activity 重建：已缓存的 HUD 弹窗绑在旧 Activity 的 window token 上，
            // 继续复用会 show() 失败（BadTokenException 被 catch 吞掉）→ 手势提示永久不显示
            dismissCachedDialogs()
        }
        currentActivity = activity
    }

    /**
     * GSY 用这个方法创建音量/亮度/快进弹窗（StandardGSYVideoPlayer 内部 new Dialog(...)）。
     * 基类实现从 View.getContext() 逐层解包 Activity，而播放器 View 跨 Activity 复用时
     * （MainUi.keepPlayerView）Android 13+ 已无法反射替换 View.mContext，解包出来的是
     * 已销毁的旧 Activity → Dialog 拿到失效 token，show() 抛 BadTokenException，
     * 被 catch 吞掉后手势 HUD 就再也不显示，只能杀掉 App 重启才恢复。
     * 这里改为优先返回显式维护的 currentActivity。
     */
    override fun getActivityContext(): Context {
        return getActivity() ?: super.getActivityContext() ?: context
    }

    /**
     * 丢弃缓存的手势弹窗（音量/亮度/快进），下次手势按当前 Activity 重建。
     * GSY 抬手时会 dismiss 并置空，但 Activity 重建、show() 失败等场景下
     * 仍会残留绑定旧 window token 的 Dialog。
     */
    private fun dismissCachedDialogs() {
        for (dialog in listOf<Dialog?>(mProgressDialog, mVolumeDialog, mBrightnessDialog)) {
            try {
                if (dialog?.isShowing == true) dialog.dismiss()
            } catch (_: Exception) {
            }
        }
        mProgressDialog = null
        mVolumeDialog = null
        mBrightnessDialog = null
        mDialogProgressBar = null
        mDialogVolumeProgressBar = null
        mBrightnessDialogTv = null
        mDialogSeekTime = null
        mDialogTotalTime = null
        mDialogIcon = null
        mDialogOffsetText = null
    }

    /**
     * Activity 窗口 token 是否仍有效，可用于安全弹出 Dialog。
     * 切后台 / 销毁中时 token 失效，此时 Dialog.show() 会抛
     * WindowManager.BadTokenException 导致崩溃（见 /sdcard/log.log）。
     */
    private fun canShowDialog(): Boolean {
        val act = getActivity() ?: return false
        return !act.isFinishing && !act.isDestroyed
    }

    override fun showVolumeDialog(deltaY: Float, volumePercent: Int) {
        if (!canShowDialog()) return
        try {
            super.showVolumeDialog(deltaY, volumePercent)
        } catch (_: Exception) {
            // Activity token 已失效（切后台/销毁/重建中）：丢弃弹窗避免崩溃，
            // 下次手势会用当前 Activity 重建，否则会一直 show 失败 → 永久不显示
            dismissCachedDialogs()
        }
    }

    override fun showProgressDialog(
        deltaX: Float,
        seekTime: String?,
        seekTimePosition: Long,
        totalTime: String,
        totalTimeDuration: Long
    ) {
        // 拖动进度时 GSY 会调它显示中央时间气泡 —— 预览图跟着这个生命周期走；
        // 先更预览图再处理弹窗，弹窗弹不出（BadTokenException）也不连累预览。
        updateSeekPreview(seekTimePosition)
        updateSeekPreviewTime(seekTimePosition, totalTimeDuration)
        // 有预览图时用我们自己的"大预览 + 时间条"接管：
        // GSY 那个 152dp 的居中盒子窗口层级比播放器里的预览图高，会直接压住大预览的中间，
        // 而且它占满半屏后大预览根本放不下 —— 所以这条路径干脆不弹它。
        if (seekPreviewActive) return
        if (!canShowDialog()) return
        if (mProgressDialog == null) {
            val localView = LayoutInflater.from(activityContext).inflate(
                R.layout.layout_video_progress_dialog, null
            )
            mDialogProgressBar = localView.findViewById(progressDialogProgressId)
            if (mDialogProgressBarDrawable != null) {
                mDialogProgressBar.progressDrawable = mDialogProgressBarDrawable
            }
            mDialogSeekTime = localView.findViewById(progressDialogCurrentDurationTextId)
            mDialogSeekTime.setTextColor(mThemeColor)
            mDialogTotalTime = localView.findViewById(progressDialogAllDurationTextId)
            mDialogIcon = localView.findViewById(progressDialogImageId)
            mDialogOffsetText = localView.findViewById(R.id.tv_offset)
            mDialogOffsetText!!.setTextColor(mThemeColor)

            mProgressDialog = Dialog(activityContext, R.style.video_style_dialog_progress)
            mProgressDialog.setContentView(localView)
            mProgressDialog.window!!.addFlags(Window.FEATURE_ACTION_BAR)
            mProgressDialog.window!!.addFlags(32)
            mProgressDialog.window!!.addFlags(16)
            mProgressDialog.window!!.setLayout(width, height)
            if (mDialogProgressNormalColor != -11 && mDialogTotalTime != null) {
                mDialogTotalTime.setTextColor(mDialogProgressNormalColor)
            }
            if (mDialogProgressHighLightColor != -11 && mDialogSeekTime != null) {
                mDialogSeekTime.setTextColor(mDialogProgressHighLightColor)
            }
            val localLayoutParams = mProgressDialog.window!!
                .attributes
            localLayoutParams.gravity = Gravity.TOP
            localLayoutParams.width = width
            localLayoutParams.height = height
            val location = IntArray(2)
            getLocationOnScreen(location)
            localLayoutParams.x = location[0]
            localLayoutParams.y = location[1]
            mProgressDialog.window!!.attributes = localLayoutParams
        }
        if (!mProgressDialog.isShowing) {
            try {
                mProgressDialog.show()
            } catch (_: Exception) {
                // Activity token 已失效：丢弃弹窗，下次手势用当前 Activity 重建
                dismissCachedDialogs()
                return
            }
        }
        if (mDialogSeekTime != null) {
            mDialogSeekTime.text = seekTime
        }
        if (mDialogTotalTime != null) {
            mDialogTotalTime.text = " / $totalTime"
        }
        if (totalTimeDuration > 0) if (mDialogProgressBar != null) {
            mDialogProgressBar.progress = (seekTimePosition * 100 / totalTimeDuration).toInt()
        }
        val offset = ((mSeekTimePosition - currentPositionWhenPlaying) / 1000.0).toInt()
        mDialogOffsetText?.text = if (offset > 0) "+${offset}s" else "${offset}s"
        if (deltaX > 0) {
            if (mDialogIcon != null) {
                mDialogIcon.setBackgroundResource(com.shuyu.gsyvideoplayer.R.drawable.video_forward_icon)
            }
        } else {
            if (mDialogIcon != null) {
                mDialogIcon.setBackgroundResource(com.shuyu.gsyvideoplayer.R.drawable.video_backward_icon)
            }
        }
    }

    override fun showBrightnessDialog(percent: Float) {
        if (!canShowDialog()) return
        if (mBrightnessDialog == null) {
            val localView = LayoutInflater.from(activityContext).inflate(
                brightnessLayoutId, null
            )
            mBrightnessDialogTv = localView.findViewById(brightnessTextId)
            // 给亮度进度条应用主题色
            themeProgressBar(localView)
            mBrightnessDialog = Dialog(activityContext, R.style.video_style_dialog_progress)
            mBrightnessDialog.setContentView(localView)
            mBrightnessDialog.window!!.run {
                addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                decorView.systemUiVisibility = SYSTEM_UI_FLAG_HIDE_NAVIGATION
                setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        val brightnessDialog = mBrightnessDialog ?: return
        // GSY 每次手势结束会 dismiss 亮度弹窗；若窗口已分离还去 setAttributes，
        // 会抛 IllegalArgumentException(View not attached to window manager)，先重新显示
        if (!brightnessDialog.isShowing) {
            try {
                brightnessDialog.show()
            } catch (_: Exception) {
                // Activity token 已失效（Activity 已销毁/重建）：丢弃这个弹窗，
                // 下次手势按当前 Activity 重建，否则会永久卡住不再显示
                dismissCachedDialogs()
                return
            }
        }
        // 每次显示都更新位置，适配屏幕旋转等变化
        try {
            val localLayoutParams = brightnessDialog.window!!
                .attributes
            localLayoutParams.gravity = Gravity.TOP or Gravity.END
            localLayoutParams.width = width
            localLayoutParams.height = height
            val location = IntArray(2)
            getLocationOnScreen(location)
            localLayoutParams.x = location[0]
            localLayoutParams.y = location[1]
            // 针对异型屏适配
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                localLayoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            brightnessDialog.window!!.attributes = localLayoutParams
        } catch (_: Exception) {
            // 窗口刚分离（竞态），本次跳过位置更新，基类仍会显示
        }
        try {
            super.showBrightnessDialog(percent)
        } catch (_: Exception) {
            // Activity token 已失效，忽略弹窗
        }
    }

    /** 递归查找 ProgressBar 并应用主题色 */
    private fun themeProgressBar(view: View) {
        if (view is android.widget.ProgressBar) {
            view.progressDrawable = PlayerViewDrawable.videoVolumeProgress(context, mThemeColor)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                themeProgressBar(view.getChildAt(i))
            }
        }
    }

    override fun setSpeed(speed: Float, soundTouch: Boolean) {
        super.setSpeed(speed, soundTouch)
        mPlaySpeedValue.text = "x$speed"
    }


    fun setWindowInsets(left: Int, top: Int, right: Int, bottom: Int, displayCutout: DisplayCutout?) {
        if (mode == PlayerMode.FULL) {
            mTopContainer.setPadding(left, top, right, 0)
            mBottomContainer.setPadding(left, 0, right, 0)
            mLockContainer.setPadding(left, 0, right, 0)
        } else {
            if (mode == PlayerMode.SMALL_FLOAT) {
                mTopContainer.setPadding(0, dip(24), 0, 0)
            } else {
                mTopContainer.setPadding(0, 0, 0, 0)
            }
            mBottomContainer.setPadding(0, 0, 0, 0)
            mLockContainer.setPadding(0, 0, 0, 0)
        }
        mDisplayCutout = displayCutout
        updateDanmakuMargin()
    }

    fun showController() {
        showAllWidget()
        cancelDismissControlViewTimer()
    }

    fun hideController() {
        hideAllWidget()
        cancelDismissControlViewTimer()
    }

    fun showSmallDargBar() {
        if (mode == PlayerMode.SMALL_FLOAT) {
            mDragBarLayout.visibility = VISIBLE
        } else {
            mDragBarLayout.visibility = GONE
        }
    }

    fun hideSmallDargBar() {
        mDragBarLayout.visibility = mTopContainer.visibility
    }

    fun getHoldButtonWidth():Int{
        return mHoldUpBtn.measuredWidth
    }

    fun setHoldStatus(isHold:Boolean){
        if(isHold){
            mDanmakuView.pause()
            setViewShowState(mBottomLayout, GONE)
            setViewShowState(mDanmakuView, GONE)
            setViewShowState(mTopContainer, GONE)
            setViewShowState(mStartButton, GONE)
            isHoldUp=true
        } else {
            isHoldUp=false
            // 按当前播放状态恢复控件，而不是无条件全部显示
            // （挂起/恢复只是拖到屏幕边缘的交互，不该把暂停中的控件强行亮出来）
            showAllWidget()
            if (isShowDanmaku) {
                mDanmakuView.show()
                // 暂停/结束时不要把弹幕绘制任务重新拉起来
                if (mCurrentState == CURRENT_STATE_PLAYING) {
                    mDanmakuView.resume()
                }
            } else {
                mDanmakuView.hide()
            }
        }

    }

    fun updateTextureViewShowType() {
        changeTextureViewShowType()
    }

    fun updateThemeColor(
        context: Context,
        themeColor: Int,
    ) {
        mThemeColor = themeColor
        mDialogSeekTime?.setTextColor(mThemeColor)
        mDialogOffsetText?.setTextColor(mThemeColor)

        val draw = PlayerViewDrawable.progressBarDrawable(context, themeColor)
        val bounds = mProgressBar.progressDrawable.bounds
        // 色块层整条叠在进度条之上（高度全覆盖，对齐 PiliPlus）：可拖动的那条进度条上也能看见片段颜色
        mProgressBar.progressDrawable = LayerDrawable(arrayOf(draw, mSeekSegmentsDrawable))
        mProgressBar.progressDrawable.bounds = bounds
        updateSeekBarMarks()
        mProgressBar.thumb.setColorFilter(themeColor, PorterDuff.Mode.SRC_ATOP)
        val baseDrawable = PlayerViewDrawable.bottomProgressBarDrawable(context, themeColor)
        mBottomProgressBar.progressDrawable = if (baseDrawable is LayerDrawable) {
            val layers = arrayOfNulls<Drawable>(3)
            layers[0] = baseDrawable.getDrawable(0)
            layers[1] = baseDrawable.getDrawable(1)
            layers[2] = baseDrawable.getDrawable(2)
            LayerDrawable(layers).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.secondaryProgress)
                setId(2, android.R.id.progress)
            }
        } else {
            baseDrawable
        }

        setDialogVolumeProgressBar(PlayerViewDrawable.videoVolumeProgress(context, themeColor))
        setDialogProgressBar(PlayerViewDrawable.dialogProgressBar(context, themeColor))

    }

    /**
     * 锁定控制按钮相关
     */
    inner class OnLockClickListener : OnClickListener {

        val isShowButton get() = mUnlockLeftIV.visibility == VISIBLE

        override fun onClick(v: View) {
            when (v.id) {
                R.id.lock -> {
                    isLock = true
                    postDelayed(dismissControlTask, mDismissControlTime.toLong())
                }
                R.id.layout_lock_screen -> {
                    if (isShowButton) {
                        removeCallbacks(dismissControlTask)
                        hideButton()
                    } else {
                        postDelayed(dismissControlTask, mDismissControlTime.toLong())
                        showButton()
                    }
                }
                R.id.unlock_left, R.id.unlock_right -> {
                    removeCallbacks(dismissControlTask)
                    isLock = false
                }
            }
        }

        private fun showButton() {
            mUnlockLeftIV.visibility = VISIBLE
            mUnlockRightIV.visibility = VISIBLE
        }

        private fun hideButton() {
            mUnlockLeftIV.visibility = GONE
            mUnlockRightIV.visibility = GONE
        }

        var dismissControlTask = Runnable { hideButton() }

    }

    /**
     * 字幕源信息
     */
    data class SubtitleSourceInfo(
        val id: String,
        val lan: String,
        val lan_doc: String,
        val subtitle_url: String,
        val ai_status: Int,
    )

    /**
     * 字幕信息
     */
    data class SubtitleItemInfo(
        val from: Long,
        val to: Long,
        val content: String,
    )
}