package com.a10miaomiao.bilimiao

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.delegate.helper.PicInPicHelper
import com.a10miaomiao.bilimiao.comm.delegate.helper.StatusBarHelper
import com.a10miaomiao.bilimiao.comm.delegate.player.PlayerDelegate2
import com.a10miaomiao.bilimiao.comm.delegate.player.VideoPlayerLauncher
import com.a10miaomiao.bilimiao.comm.delegate.player.VideoPlayerSource
import com.a10miaomiao.bilimiao.comm.delegate.theme.ThemeDelegate
import com.a10miaomiao.bilimiao.comm.toast
import com.a10miaomiao.bilimiao.comm.utils.ScreenDpiUtil
import com.a10miaomiao.bilimiao.store.Store
import com.a10miaomiao.bilimiao.widget.player.DanmakuVideoPlayer
import com.a10miaomiao.bilimiao.widget.scaffold.ScaffoldView
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.ContentBehavior
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.PlayerBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuyu.gsyvideoplayer.video.base.GSYVideoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.bindSingleton

/**
 * 点播播放页（阶段 1：把点播播放从 `MainActivity` 搬进独立 Activity）。
 *
 * ## 为什么是独立 Activity（问题的根因）
 * Android 的 PiP 只能把**栈顶 Activity** 弹成小窗，小窗背后露出的是"同一个 task 里它下面那个 Activity"。
 * 点播播放器原来活在 `MainActivity`（task 根）自己里 → 一进小窗下面没有自家页面 → 只能露出桌面。
 * 直播之所以"小窗浮在自家界面上"，就是因为它有独立的 `LivePlayerActivity` 压在 `MainActivity` 之上。
 * 本页照这个结构做：它压在 `MainActivity`（视频详情页）之上，于是 PiP 时详情页留在屏幕上。
 *
 * ## 它和"老路径"的关系（可回退）
 * 老路径**一行没删**：`MainActivity` 依然创建并挂载 `PlayerDelegate2`（`MainActivity.kt:120` / `:208`），
 * 除视频详情页以外的入口（番剧详情、收藏、下载、播放列表、稍后再看、用户空间…）仍走老路径。
 * 本页只是"另一个宿主"：自己 new 一个 `PlayerDelegate2`（`activity` 指向自己）+ 自己的一份 store。
 * 回退开关在 [VideoPlayerLauncher.USE_STANDALONE_ACTIVITY]（一个常量）。
 *
 * ## 播放语义一个字没改
 * 播放/取流/缓存/CDN/弹幕/清晰度/倍速/手势/SponsorBlock 全部还是 `PlayerDelegate2` +
 * `PlayerController` + `DanmakuVideoPlayer` 那三件套，本页只负责"宿主"：
 * ① 提供它们要找的 View（`ScaffoldView` + `R.id.video_player` + 四个覆盖层）；
 * ② 提供 DI（Activity / store / ThemeDelegate / StatusBarHelper）；
 * ③ 生命周期转发（onCreate/onStart/onResume/onPause/onStop/onDestroy/转屏/Insets/PiP/返回）。
 *
 * ## 为什么用 `ScaffoldView` 而不是普通 FrameLayout
 * `PlayerDelegate2`/`PlayerController` 是围绕 `ScaffoldView` 的语义写的（`showPlayer`、`fullScreenPlayer`、
 * `playerViewSizeStatus`、Insets 让位…），自己拿 FrameLayout 拼一套等于重写播放器的布局协议（那才是"重写播放核心"）。
 * 这里直接复用**同一个** `ScaffoldView` + 同一套 behavior，只是少了主界面的 AppBar/抽屉/Compose 内容 ——
 * 布局结果与主界面完全同源，风险最小。
 *
 * ## 已知缺口（阶段 2 待办，见报告）
 * 播放器上那几个"打开 Compose 底部弹层"的按钮（播放设置 / 弹幕设置 / 发弹幕 / 番剧选集）在本页
 * **不会弹层**：`Activity.openBottomSheet()` 的实现是 `(this as? MainActivity)?.openBottomSheet(page)`，
 * 换个 Activity 就是空实现（不崩，但会"点了没反应"）。所以本页把这几处显式换成一句提示，
 * 并自己实现了最常用的「分P」列表（纯 View 弹窗，不依赖 Compose）。阶段 2 再把弹层搬过来。
 */
class VideoPlayerActivity : AppCompatActivity(), DIAware {

    companion object {
        /** PiP 宽高比的合法区间（与 `PicInPicHelper` 内部夹取同口径，那边是私有方法，这里按同一规则重算） */
        private const val PIP_MIN_RATIO = 1f / 2.39f
        private const val PIP_MAX_RATIO = 2.39f
    }

    // ══════════════════════════════════════════════════════════════════════
    // DI / 委托
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 本页自己的 DI 图（与 `MainActivity.di` 同构，但只登记本页真正会用到的东西）。
     *
     * 为什么必须自己建一份：DI 是 Activity 级的（`Store` 里的 store 都是
     * `activity.diViewModel(di)` → 绑在本 Activity 的 ViewModelStore 上）。
     * 复用 `MainActivity` 那份 = MainActivity 一被系统回收，本页的 store 就全成了死 ViewModel。
     * 代价：store 状态与主界面**不共享**（播放列表/登录态各一份），阶段 2 收敛成进程级 store。
     */
    override val di: DI = DI.lazy {
        bindSingleton { this@VideoPlayerActivity }
        store.loadStoreModules(this)
        bindSingleton { playerDelegate }
        bindSingleton { themeDelegate }
        bindSingleton { statusBarHelper }
    }

    private val store by lazy { Store(this, di) }

    /** 本页的播放器委托：`activity` 指向本页，与 `MainActivity` 里那个单例互不干扰 */
    private val playerDelegate by lazy { PlayerDelegate2(this, di) }

    private val themeDelegate by lazy { ThemeDelegate(this, di) }
    private val statusBarHelper by lazy { StatusBarHelper(this) }

    // ══════════════════════════════════════════════════════════════════════
    // 视图
    // ══════════════════════════════════════════════════════════════════════

    private lateinit var root: ScaffoldView
    private lateinit var playerView: DanmakuVideoPlayer
    private lateinit var hintView: TextView

    // ══════════════════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 播放页恒为黑底：盖掉主题 splash，避免起播瞬间白/彩闪一下（与 LivePlayerActivity 同一处理）
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        // ① 先把 View 树建好并 setContentView —— PlayerViews 是靠
        //    `activity.findViewById(R.id.video_player)` 找播放器的，onCreate 之前必须已经挂在窗口上
        buildContentView()

        // ② store 初始化：只做播放器真正需要的两件事
        //    · appStore：主题色（播放器进度条/按钮配色由 ThemeDelegate 下发）
        //    · userStore：登录态 + 大会员（清晰度菜单靠它决定给不给 1080P+/4K）
        //    刻意**不**调 `store.onCreate()`：那会连带把历史/收藏/稍后再看/分区列表/未读消息
        //    全部重新拉一遍（每个都是网络请求），而本页只用得到上面两项。
        store.appStore.init(this)
        store.userStore.init(this)

        // ③ 委托初始化：注册 PiP、控制器、字幕、弹幕设置入口等（与 MainActivity 里的调用同一份代码）
        playerDelegate.onCreate(savedInstanceState)

        // ④ 主题色跟随 App 设置（与 MainActivity 的 applyAppBarTheme 同一来源，只取播放器要用的主色）
        lifecycleScope.launch {
            store.appStore.stateFlow.mapNotNull { it.theme }.flowOn(Dispatchers.Main).collect {
                themeDelegate.setThemeColor(it.color)
            }
        }

        // ⑤ 把 Compose 弹层的入口换成"有反馈的提示"（见类注释"已知缺口"），并接管 PiP 按钮
        bindPlayerUiCallbacks()

        // ⑥ 取播放任务
        val source = VideoPlayerLauncher.consumePendingSource()
        if (source == null) {
            // 没有播放任务：进程被杀后重建、或外部误启动。没有源就没法恢复播放（源不是 Parcelable），
            // 直接退出而不是留一个黑屏页面
            toast("没有拿到要播放的视频")
            finish()
            return
        }

        // ⑦ 开始播放（数据源/网络/缓存/弹幕/清晰度全部走原有逻辑，一个字没改）
        playerDelegate.openPlayer(source)
        // 把列表状态种进本页的 PlayListStore：合集/播单自动连播要用（播放器侧只读不写，快照足够）
        val playList = VideoPlayerLauncher.consumePendingPlayList()
        if (playList.isNotEmpty()) {
            store.playListStore.setState { items = playList }
        }

        // ⑧ 本页 = 全屏播放页（竖屏就是"整屏播放器"，横屏就是横屏全屏）。
        //    ★ 必须走 `controller.fullScreen()` 而不是只写 `root.fullScreenPlayer`：
        //      前者会同时把播放器 View 的 mode 切成 FULL（控件排布跟着变），只改后者会出现
        //      "画面全屏、控件还是竖屏小窗那一套"的错位。
        //      传 UNSPECIFIED = **不强制转屏**（跟随设备方向）；用户设置的"全屏方向"偏好在
        //      手动点全屏按钮时依然生效（changeFullscreen → fullScreen(getFullMode(...))）。
        playerDelegate.controller.fullScreen(SettingConstants.PLAYER_FULL_MODE_UNSPECIFIED)

        // ⑨ 状态栏/导航栏：黑底页面 → 图标用浅色（与 MainActivity 的判定口径一致）
        statusBarHelper.isLightStatusBar = false
        statusBarHelper.isLightNavigationBar = false

        // ⑩ Insets 交给播放器（全屏控件的安全区），与 MainActivity.setWindowInsets 的最后一跳一致
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyWindowInsets(insets)
            insets
        }
        root.rootWindowInsets?.let { applyWindowInsets(it) }

        hintView.text = "正在播放：${source.title}\n\n（按返回键回到上一页）"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 本页已在栈顶时又收到一次"打开视频"（例：小窗浮在详情页上，详情页又点了别的视频；
        // 或系统回收重建的窗口期里详情页重新触发了自动播放）。
        // manifest 里声明了 singleTop，所以这里可以就地换源，而不是叠出第二个播放器。
        val source = VideoPlayerLauncher.consumePendingSource() ?: return
        if (playerDelegate.isOpened() && playerDelegate.playerSource?.id == source.id) {
            return
        }
        playerDelegate.openPlayer(source)
        VideoPlayerLauncher.consumePendingPlayList().takeIf { it.isNotEmpty() }?.let { list ->
            store.playListStore.setState { items = list }
        }
        if (!root.fullScreenPlayer) {
            playerDelegate.controller.fullScreen(SettingConstants.PLAYER_FULL_MODE_UNSPECIFIED)
        }
        hintView.text = "正在播放：${source.title}\n\n（按返回键回到上一页）"
    }

    override fun onStart() {
        super.onStart()
        playerDelegate.onStart()
    }

    override fun onResume() {
        super.onResume()
        playerDelegate.onResume()
        // Android 12+：把「退后台自动进小窗」的开关重新断言一次。
        // 为什么要每次回来都断言：PiP 里的动作按钮刷新（PicInPicHelper.updatePictureInPictureActions）
        // 与 PlayerController 的状态同步都会**整体重建** PiP 参数，而它们不带 autoEnter 这一位。
        applyAutoEnterPip()
    }

    override fun onPause() {
        super.onPause()
        playerDelegate.onPause()
    }

    override fun onStop() {
        super.onStop()
        // 与 MainActivity 完全同一条路径：保存进度 / 非后台播放模式则暂停 / 设置打开时兜底进 PiP。
        // 注意：**进 PiP 不会触发 onStop**（PiP 里只是 onPause），所以小窗里照常播。
        playerDelegate.onStop()
    }

    override fun onDestroy() {
        // 系统回收（不是用户主动退出）：把源存回进程，万一页面被重建还能接着播
        // （续播位置由 PlayerDelegate2 从 PlaybackService/本地记录里取，不靠这里）
        if (!isFinishing) {
            VideoPlayerLauncher.stashPendingSource(playerDelegate.playerSource)
        }
        playerDelegate.onDestroy()
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 用户"正要离开"（划回桌面/按 Home）的最后时刻再断言一次 autoEnter：
        // 比只在 onResume 断言更稳（期间可能被 PiP 动作按钮刷新覆盖过参数）
        if (!isFinishing) {
            applyAutoEnterPip()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // ★ 顺序同 MainActivity：先把方向写进 ScaffoldView，再通知播放器。
        //   反过来的话 PlayerController 会在"scaffoldApp.orientation 还是旧值"的窗口里推导模式。
        root.orientation = newConfig.orientation
        playerDelegate.onConfigurationChanged(newConfig)
        statusBarHelper.isLightStatusBar = false
        statusBarHelper.isLightNavigationBar = false
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // 委托侧会：注册/注销 PiP 动作广播、隐藏控制器、把播放器按全屏排版（PiP 窗口只显示播放器）
        playerDelegate.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    override fun onBackPressed() {
        // 返回键语义（独立播放页专用，与"主界面里那个播放器"不同）：
        // ① 锁定态 → 交给播放器：它会提示「已锁定，请先解锁」并吞掉这次返回
        if (playerView.isLock) {
            playerDelegate.onBackPressed()
            return
        }
        // ② 全屏中 → 先退出全屏（回到本页的"小窗 + 提示"形态），**不**直接退出页面
        //    （与播放器里那个返回按钮的语义一致：全屏时返回 = 退出全屏）
        if (root.fullScreenPlayer) {
            playerDelegate.onBackPressed()
            return
        }
        // ③ 已经在非全屏 → 关掉播放器并退出本页。
        //    这里**不**沿用老路径的"再按一次退出播放"：那套是给"主界面里的小窗"用的防误触，
        //    而本页的存在意义就是播这一个视频，返回 = 离开播放页（标准 Android 语义）。
        //    关闭动作走 closePlayer()：它会保存进度、退出小窗、清通知栏、释放播放器；
        //    「页面跟着结束」由 root.onPlayerChanged(show=false) → finish() 统一收口。
        if (playerDelegate.isOpened()) {
            playerDelegate.closePlayer()
            return
        }
        super.onBackPressed()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // 与 MainActivity 同款媒体键/方向键处理：播放页是最需要这几个键的场景
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> if (playerDelegate.isOpened()) {
                if (playerView.isInPlayingState) playerView.onVideoPause() else playerView.onVideoResume()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (playerDelegate.isOpened()) {
                playerView.seekTo(playerView.currentPosition - 5000)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (playerDelegate.isOpened()) {
                playerView.seekTo(playerView.currentPosition + 5000)
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> if (playerDelegate.isOpened()) {
                onBackPressed()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun attachBaseContext(newBase: Context) {
        // 与 MainActivity 一致：自定义 DPI 设置要生效，否则本页控件尺寸与主界面不一致
        val configuration: Configuration = newBase.resources.configuration
        ScreenDpiUtil.readCustomConfiguration(configuration)
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    // ══════════════════════════════════════════════════════════════════════
    // View 树（与 MainUi 同构，去掉 AppBar / 抽屉 / Compose 内容）
    // ══════════════════════════════════════════════════════════════════════

    private fun buildContentView() {
        root = ScaffoldView(this).apply {
            orientation = resources.configuration.orientation
            // Android 只有 setBackgroundColor()，没有对应 getter → 在 Kotlin 里不是属性，必须写方法调用
            setBackgroundColor(Color.BLACK)
        }

        // 内容位：本页没有内容，只放一句提示。留着这个 child 是为了让 ContentBehavior
        // 「播放器占位时把内容下推」的逻辑有对象，行为与主界面同源
        val content = FrameLayout(this)
        hintView = TextView(this).apply {
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
            textSize = 14f
        }
        content.addView(
            hintView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )
        root.addView(
            content,
            ScaffoldView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { behavior = ContentBehavior(this@VideoPlayerActivity, null) }
        )

        // 播放器位：顺序与 MainUi.mPlayerLayout **必须一致** ——
        // PlayerBehaviorDelegate 取 `getChildAt(0)` 当播放器，播放器必须在第一位
        val playerLayout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = layoutInflater.inflate(R.layout.include_palyer2, playerLayout, false) as DanmakuVideoPlayer
        playerLayout.addView(playerView)
        // 四个覆盖层（加载/播放完成/出错/地区限制）：PlayerDelegate2 里的四个 BoxController
        // 是直接 `activity.findViewById(R.id.xxx)` 拿的，一个都不能少
        playerLayout.addView(layoutInflater.inflate(R.layout.include_completion_box, playerLayout, false))
        playerLayout.addView(layoutInflater.inflate(R.layout.include_error_message_box, playerLayout, false))
        playerLayout.addView(layoutInflater.inflate(R.layout.include_area_limit_box, playerLayout, false))
        playerLayout.addView(layoutInflater.inflate(R.layout.include_player_loading, playerLayout, false))
        root.addView(
            playerLayout,
            ScaffoldView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { behavior = PlayerBehavior(this@VideoPlayerActivity, null) }
        )

        // ScaffoldView 靠这两个回调把"播放器显示状态"和"窗口尺寸"喂给播放器
        root.playerDelegate = playerDelegate
        root.onWindowSizeChanged = { w, h -> playerDelegate.onHostSizeChanged(w, h) }
        // 播放器被关掉（双击返回 / 通知栏「停止」/ 出错后关闭 / 播放完成框关闭）→ 本页没有存在意义了。
        // 用这个回调收口，比在每个关闭入口手动 finish() 稳（入口有七八个，漏一个就留个黑屏页）
        root.onPlayerChanged = { show ->
            if (!show && !isFinishing) finish()
        }

        setContentView(root)
    }

    /**
     * 播放器 UI 上那些"申明在外层页面"的回调，在本页重新绑一遍（不改被调方一行代码）。
     *
     * 为什么能这么干：这些回调本来就是 `PlayerDelegate2.onCreate` 注入到播放器 View 上的 `var`，
     * 宿主是"注入者"——本页作为新宿主，注入自己的一份天经地义。
     */
    private fun bindPlayerUiCallbacks() {
        // 顶栏「小窗播放」按钮：本页用自己组装的 PiP 参数（带 autoEnter + 只留播放/暂停一个动作）
        playerView.onEnterPip = { enterPipManually() }
        // 画面比例（底栏按钮）：走 PlayerController 的 PopupMenu 实现，本页完全可用，保持不动
        // 播放设置 / 弹幕设置 / 发弹幕：都是 Compose 底部弹层（`Activity.openBottomSheet` 只认 MainActivity），
        // 本页给一句明确提示，而不是"点了没反应"
        playerView.onOpenVideoSetting = { toast("播放设置在独立播放页里还没搬过来（阶段 2），可在主界面播放器里调") }
        playerView.onOpenDanmakuSetting = { toast("弹幕设置在独立播放页里还没搬过来（阶段 2），可在 App 设置 → 弹幕里调") }
        // 分P / 剧集：本页自己实现（纯 View 弹窗），这是多P视频最常用的入口，不能没有
        playerView.setExpandButtonOnClickListener { showPagesDialog() }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Insets
    // ══════════════════════════════════════════════════════════════════════

    /** 与 `MainActivity.setWindowInsets` 的最后一跳同口径：Insets 只喂给播放器（全屏控件安全区） */
    private fun applyWindowInsets(insets: WindowInsets) {
        val left = insets.systemWindowInsetLeft
        val top = insets.systemWindowInsetTop
        // 这里用 systemWindowInsetRight 而**不是** MainActivity 那个 stableInsetRight：
        // 后者是 API 28 才有的方法，minSdk 24 的机器上调用会 NoSuchMethodError（那是主界面的既有隐患，
        // 本页不复制它）。全屏播放器场景两种口径等价。
        val right = insets.systemWindowInsetRight
        val bottom = insets.systemWindowInsetBottom
        val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) insets.displayCutout else null
        playerDelegate.setWindowInsets(left, top, right, bottom, cutout)
        root.statusBarHeight = top
        root.updateLayout(false)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 分P（本页自实现，替代 VideoPagesPage 那个 Compose 弹层）
    // ══════════════════════════════════════════════════════════════════════

    private fun showPagesDialog() {
        val source = playerDelegate.playerSource as? VideoPlayerSource
        if (source == null) {
            // 番剧选集（BangumiEpisodesPage）也是 Compose 弹层，本页暂时搬不过来
            toast("这部剧集的选集入口在独立播放页里还没搬过来（阶段 2）")
            return
        }
        val pages = source.pages
        if (pages.size <= 1) {
            toast("这个视频只有一P")
            return
        }
        val titles = pages.map { it.title }.toTypedArray()
        val current = pages.indexOfFirst { it.cid == source.id }
        MaterialAlertDialogBuilder(this)
            .setTitle("选择分P")
            .setSingleChoiceItems(titles, current) { dialog, which ->
                dialog.dismiss()
                if (which != current) {
                    switchPage(source, which)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 切到指定分P。
     *
     * 构造方式与 `VideoPlayerSource.next()/previous()` **逐字一致**（aid/bvid/分P列表全保留，
     * 只换 id(cid)/title）：那两处是自动连播在用的现成路径，跟它对齐就不会引入"切P后
     * 空降助手/章节/合集归属丢失"这类问题。新的续播位置交给 `openPlayer` 的本地记录逻辑
     * （按 `dl_{aid}_{cid}` 取），所以这里不预设 lastPlayTime（也就不会弹"自动恢复"提示）。
     */
    private fun switchPage(source: VideoPlayerSource, index: Int) {
        val page = source.pages.getOrNull(index) ?: return
        val next = VideoPlayerSource(
            mainTitle = source.mainTitle,
            title = page.title,
            coverUrl = source.coverUrl,
            aid = source.aid,
            bvid = source.bvid,
            id = page.cid,
            ownerId = source.ownerId,
            ownerName = source.ownerName,
        )
        next.pages = source.pages
        next.uposHost = source.uposHost
        next.cdnRaceEnabled = source.cdnRaceEnabled
        next.audioIndependentCdn = source.audioIndependentCdn
        playerDelegate.openPlayer(next)
        hintView.text = "正在播放：${next.title}\n\n（按返回键回到上一页）"
    }

    // ══════════════════════════════════════════════════════════════════════
    // 画中画（PiP）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 手动进小窗（顶栏那个按钮）：参数与自动进小窗**共用同一份** [buildPipParams]。
     *
     * 与老的 `PlayerController.enterPip()` 的差别只有两点：
     * ① 动作按钮只留「播放/暂停」一个（用户对直播那套的期望）；
     * ② 明确带上 `setAutoEnterEnabled`（Android 12+）。
     * 失败反馈与老路径一致（原来静默，现在给 toast）。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipManually() {
        if (!playerDelegate.isOpened()) {
            toast("播放器还没准备好")
            return
        }
        val params = buildPipParams(autoEnter = true) ?: return
        val ok = try {
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            // 个别 ROM 对参数挑剔（比例/动作不合法会抛 IllegalArgumentException）
            false
        }
        if (!ok) {
            toast("系统未允许进入小窗播放")
        }
    }

    /** Android 12+：让系统在"用户离开本页"时直接进小窗（这就是用户说的"像直播那样、不用先退桌面"） */
    private fun applyAutoEnterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!playerDelegate.isOpened()) return
        buildPipParams(autoEnter = true)?.let { setPictureInPictureParams(it) }
    }

    /**
     * 组装 PiP 参数。
     *
     * 为什么不直接复用 `PicInPicHelper`：那个 helper 是**只读参考**（另一路工作正在改它），
     * 而且它的 `buildParams` 是私有的、也不带 `setAutoEnterEnabled`。
     * 本页作为宿主要求"参数对齐直播那套"，所以自己组装；口径与 helper 保持一致：
     * 比例取接口元数据（缺省 16:9）并按 [PIP_MIN_RATIO]/[PIP_MAX_RATIO] 夹取，
     * 源矩形取播放器此刻在屏幕上的可见矩形，动作按钮走**同一个**广播协议
     * （`PicInPicHelper.ACTION_MEDIA_CONTROL`，接收器由 `PlayerDelegate2` 在进入 PiP 时注册）。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(autoEnter: Boolean): PictureInPictureParams? {
        val info = playerDelegate.playerSourceInfo
        val w = info?.width ?: 0
        val h = info?.height ?: 0
        val ratio = if (w > 0 && h > 0) Rational(w, h) else Rational(16, 9)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(clampAspectRatio(ratio))
            .setActions(pipActions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
            // 只在本页断言 autoEnter：本页的生命周期就等于"播放器开着"，
            // 不会出现老路径那种"首页刷着刷着划回桌面弹出一个空黑小窗"的风险
            builder.setAutoEnterEnabled(autoEnter)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // PiP 顶栏标题：取播放器里的标题控件（它只是 invisible，一直在）
            val title = playerView.findViewById<TextView>(R.id.title)?.text
            if (!title.isNullOrBlank()) {
                builder.setTitle(title)
            }
        }
        sourceRectHint()?.let { builder.setSourceRectHint(it) }
        return builder.build()
    }

    /** Android PiP 的比例必须在 [1:2.39, 2.39] 内，超宽屏不夹会让 `build()` 抛异常 */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun clampAspectRatio(aspectRatio: Rational): Rational {
        val ratio = aspectRatio.numerator.toFloat() / aspectRatio.denominator.toFloat()
        return when {
            ratio > PIP_MAX_RATIO -> Rational(239, 100)
            ratio < PIP_MIN_RATIO -> Rational(100, 239)
            else -> aspectRatio
        }
    }

    /**
     * PiP 动作按钮：**只留播放/暂停**。
     *
     * 广播协议与 `PicInPicHelper` 完全一致（同一个 action + 同一组 extra 常量），
     * 所以点击由 `PlayerDelegate2` 注册的那个接收器处理，本页不需要自己注册接收器。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipActions(): List<RemoteAction> {
        val playing = playerView.currentState == GSYVideoPlayer.CURRENT_STATE_PLAYING
        val controlType = if (playing) PicInPicHelper.CONTROL_TYPE_PAUSE else PicInPicHelper.CONTROL_TYPE_PLAY
        val requestType = if (playing) PicInPicHelper.REQUEST_TYPE_PAUSE else PicInPicHelper.REQUEST_TYPE_PLAY
        val intent = Intent(PicInPicHelper.ACTION_MEDIA_CONTROL)
            .putExtra(PicInPicHelper.EXTRA_CONTROL_TYPE, controlType)
        val action = RemoteAction(
            Icon.createWithResource(
                this,
                if (playing) R.drawable.bili_player_play_can_pause else R.drawable.bili_player_play_can_play
            ),
            if (playing) "暂停" else "播放",
            "",
            PendingIntent.getBroadcast(
                this,
                requestType,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        return listOf(action)
    }

    /** 源矩形提示：进/出 PiP 的过渡动画以"视频此刻在屏幕上的矩形"为起点/终点，视觉上就是原地缩小 */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun sourceRectHint(): Rect? {
        if (!playerView.isAttachedToWindow || playerView.width <= 0 || playerView.height <= 0) return null
        val rect = Rect()
        if (!playerView.getGlobalVisibleRect(rect) || rect.isEmpty) return null
        return rect
    }
}
