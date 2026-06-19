package com.a10miaomiao.bilimiao.widget.player

import android.app.Activity
import android.app.Dialog
import android.app.Service
import android.content.Context
import android.content.res.Configuration
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
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.a10miaomiao.bilimiao.R
import com.a10miaomiao.bilimiao.comm.delegate.helper.StatusBarHelper
import com.a10miaomiao.bilimiao.comm.delegate.player.PlayerSeekBus
import com.a10miaomiao.bilimiao.service.PlaybackService
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

    // 字幕开关
    private val mSubtitleSwitch: ViewGroup by lazy { findViewById(R.id.subtitle_switch) }

    // 字幕开关图标
    private val mSubtitleSwitchIV: ImageView by lazy { findViewById(R.id.subtitle_switch_icon) }

    // 字幕开关文字
    private val mSubtitleSwitchTV: TextView by lazy { findViewById(R.id.subtitle_switch_text) }

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

    // 加载字幕
    var subtitleLoader: ((url: String) -> Unit)? = null

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
            if (::pinchToZoom.isInitialized) {
                pinchToZoom.enabled = !value
                if (value) {
                    pinchToZoom.resetImmediate()
                }
            }
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
        mButtomPlay.setOnClickListener {
            clickStartIcon()
        }
        mSubtitleSwitch.setOnClickListener {
            val menus = mutableListOf<CheckPopupMenu.MenuItemInfo<SubtitleSourceInfo?>>()
            menus.addAll(subtitleSourceList.map {
                CheckPopupMenu.MenuItemInfo(it.lan_doc, it)
            })
            menus.add(CheckPopupMenu.MenuItemInfo("关闭字幕", null))
            val pm = CheckPopupMenu(
                context = context,
                anchor = it,
                menus = menus,
                value = currentSubtitleSource,
                themeColor = mThemeColor,
            )
            pm.onMenuItemClick = {
                currentSubtitleSource = it.value
            }
            pm.show()
        }
        chapterManager.initChapterButton()
        mCastBtnLayout.setOnClickListener {
            onCastClick?.invoke(it)
        }
        mBottomSubtitleTV.setTextColor(Color.parseColor("#FFFFFF"))
        mBottomSubtitleTV.backgroundColor = Color.parseColor("#66000000")

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
        val shouldDisable = isLandscapeLayout && mode != PlayerMode.FULL
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
        mBottomSubtitleTV.visibility = GONE
        if (currentSubtitleSource == null) {
            mSubtitleSwitchIV.setImageResource(R.drawable.bili_player_subtitle_is_closed)
            mSubtitleSwitchTV.text = "字幕关"
        } else {
            mSubtitleSwitchIV.setImageResource(R.drawable.bili_player_subtitle_is_open)
            mSubtitleSwitchTV.text = currentSubtitleSource?.lan_doc ?: "字幕开"
            subtitleLoader?.invoke(currentSubtitleSource?.subtitle_url ?: "")
        }
    }

    private var touchSurfaceDownTime = Long.MAX_VALUE
    private var isSpeedPlaying = false
    private var lastSpeed = 0f  // init an invalid value

    /** 亮度手势跟踪：每次手指抬起时重置，下次从系统亮度重新开始 */
    private var lastGestureBrightness = -1f

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
        speed *= 3
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
                // 发 CANCEL 让 GSY 放弃第一指的手势跟踪
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
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_UP-> {
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
        // 先读 App 已设的亮度（window.screenBrightness），没有才读系统亮度
        var brightness = try {
            (context as Activity).window.attributes.screenBrightness
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
            val lp = (context as Activity).window.attributes
            lp.screenBrightness = newBrightness
            (context as Activity).window.attributes = lp
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
    }

    override fun cancelProgressTimer() {
        super.cancelProgressTimer()
        removeCallbacks(subtitleTask)
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

    private fun setBottomSubtitleText() {
        if (subtitleBody.isEmpty()) return
        val currentTime = currentPositionWhenPlaying
        // 读取上一次索引位置，顺便检查是否在范围内
        var index = if (subtitleIndex < 0) {
            0
        } else if (subtitleIndex < subtitleBody.size) {
            subtitleIndex
        } else {
            subtitleBody.size - 1
        }
        while (index in subtitleBody.indices) {
            val item = subtitleBody[index] // 索引位置字幕信息
            if (item.from > currentTime) {
                // 字幕开始时间大于当前时间
                if (index != 0 && currentTime > subtitleBody[index - 1].to) {
                    // 上一个字幕结束时间小于当前时间
                    mBottomSubtitleTV.visibility = GONE
                    break
                } else {
                    index--
                }
            } else if (item.to < currentTime) {
                // 字幕结束时间小于当前时间
                index++
            } else {
                subtitleIndex = index // 保存当前索引
                mBottomSubtitleTV.text = item.content // 设置字幕内容
                mBottomSubtitleTV.visibility = VISIBLE
                break
            }
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
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (
            id == com.shuyu.gsyvideoplayer.R.id.surface_container
            && event.action == MotionEvent.ACTION_CANCEL
            ) {
            if (mHideKey && mShowVKey) {
                return true
            }
            touchSurfaceUp()
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
        // 重写方法，加入音频焦点开关
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
        videoPlayerCallBack?.onAutoCompletion()
        releaseDanmaku()
    }


    override fun onVideoPause() {
        super.onVideoPause()
        danmakuOnPause()
        if (!isSilentReconnecting) {
            videoPlayerCallBack?.onVideoPause()
        }
    }

    override fun onVideoResume(isResume: Boolean) {
        super.onVideoResume(isResume)
        danmakuOnResume()
        if (!isSilentReconnecting) {
            videoPlayerCallBack?.onVideoResume(isResume)
        }
    }

    override fun onVideoResume() {
        onVideoResume(true)
    }

    /** 静默重连 Surface，不触发暂停/播放 UI 闪变 */
    private var isSilentReconnecting = false

    fun reconnectSurfaceQuietly() {
        val savedState = mCurrentState
        try {
            isSilentReconnecting = true
            onVideoResume()
            if (savedState == CURRENT_STATE_PAUSE) {
                mCurrentState = CURRENT_STATE_PAUSE
                gsyVideoManager.pause()
            }
        } catch (_: Exception) {
        } finally {
            isSilentReconnecting = false
        }
    }

    override fun clickStartIcon() {
        super.clickStartIcon()
        if (mCurrentState == CURRENT_STATE_PLAYING) {
            // PlaybackService 状态已在 onVideoResume / setStateAndUi 中同步
            danmakuOnResume()
        } else if (mCurrentState == CURRENT_STATE_PAUSE) {
            // PlaybackService 状态已在 onVideoPause / setStateAndUi 中同步
            danmakuOnPause()
        }
    }

    override fun onCompletion() {
        super.onCompletion()
        releaseDanmaku()
    }

    override fun release() {
        releaseDanmaku()
        gsyVideoManager?.player?.stop()
        super.release()
    }

    /**
     * 从 Activity 安全分离（不杀死播放器，不释放弹幕）
     * Activity 销毁时调用，播放器留在 GSYVideoManager 单例中继续存活。
     * 下次新建 DanmakuVideoPlayer 并 startPrepare() 时自动重连。
     */
    fun detachView() {
        // 不释放弹幕 — 重连时复用
        gsyVideoManager?.player?.pause()
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
            override fun updateTimer(timer: DanmakuTimer) {}
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
            mDanmakuView.show()
                // 【已移除】V2显示控制 — V2引擎已废弃
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
    override fun showProgressDialog(
        deltaX: Float,
        seekTime: String?,
        seekTimePosition: Long,
        totalTime: String,
        totalTimeDuration: Long
    ) {
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
            mProgressDialog.show()
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
        // 每次显示都更新位置，适配屏幕旋转等变化
        val localLayoutParams = mBrightnessDialog!!.window!!
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
        mBrightnessDialog!!.window!!.attributes = localLayoutParams
        super.showBrightnessDialog(percent)
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
            setViewShowState(mBottomLayout, VISIBLE)
            setViewShowState(mDanmakuView, VISIBLE)
            setViewShowState(mTopContainer, VISIBLE)
            setViewShowState(mStartButton, VISIBLE)
            mDanmakuView.resume()
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
        mProgressBar.progressDrawable = draw
        mProgressBar.progressDrawable.bounds = bounds
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