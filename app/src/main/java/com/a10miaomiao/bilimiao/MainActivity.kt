package com.a10miaomiao.bilimiao

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.DisplayCutout
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.result.ActivityResult
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentOnAttachListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import cn.a10miaomiao.bilimiao.compose.BilimiaoPageRoute
import cn.a10miaomiao.bilimiao.compose.ComposeFragment
import cn.a10miaomiao.bilimiao.compose.StartViewWrapper
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.base.PageSearchMethod
import cn.a10miaomiao.bilimiao.compose.pages.search.SearchResultPage
import com.a10miaomiao.bilimiao.comm.BiliGeetestUtilImpl
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.delegate.helper.StatusBarHelper
import com.a10miaomiao.bilimiao.comm.delegate.helper.SupportHelper
import com.a10miaomiao.bilimiao.comm.delegate.player.BasePlayerDelegate
import com.a10miaomiao.bilimiao.comm.delegate.player.PlayerDelegate2
import com.a10miaomiao.bilimiao.comm.delegate.theme.ThemeDelegate
import com.a10miaomiao.bilimiao.comm.mypage.MenuActions
import com.a10miaomiao.bilimiao.comm.mypage.MyPage
import com.a10miaomiao.bilimiao.comm.mypage.MyPageConfigInfo
import com.a10miaomiao.bilimiao.comm.mypage.MyPopupMenu
import com.a10miaomiao.bilimiao.comm.navigation.openBottomSheet
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCConfig
import com.a10miaomiao.bilimiao.comm.store.AppStore
import com.a10miaomiao.bilimiao.comm.utils.BiliGeetestUtil
import com.a10miaomiao.bilimiao.comm.utils.ScreenDpiUtil
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.config.config
import com.a10miaomiao.bilimiao.config.resetViewConfig
import com.a10miaomiao.bilimiao.page.MainBackPopupMenu
import com.a10miaomiao.bilimiao.service.PlaybackService
import com.a10miaomiao.bilimiao.store.Store
import com.a10miaomiao.bilimiao.widget.scaffold.ScaffoldView
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.DrawerBehaviorDelegate
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.AppBarBehavior
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.PlayerBehavior
import com.a10miaomiao.bilimiao.widget.scaffold.getScaffoldView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.MoreExecutors
import com.a10miaomiao.bilimiao.comm.toast
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.a10miaomiao.bilimiao.comm.utils.BiliUrlMatcher
import com.materialkolor.ktx.DynamicScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.bindSingleton
import splitties.views.backgroundColor


class MainActivity
    : AppCompatActivity(),
    DIAware {

    private var mainUi: MainUi? = null
    private val ui get() = mainUi ?: throw IllegalStateException("mainUi is not initialized yet")

    override val di: DI = DI.lazy {
        bindSingleton { this@MainActivity }
        store.loadStoreModules(this)
        bindSingleton { startViewWrapper }
        bindSingleton { basePlayerDelegate }
        bindSingleton { themeDelegate }
        bindSingleton { statusBarHelper }
        bindSingleton { supportHelper }
        bindSingleton { biliGeetestUtil }
    }

    private val store by lazy { Store(this, di) }
    private val startViewWrapper by lazy {
        StartViewWrapper(
            this,
            this::startMenuNavigate,
            this::startMenuNavigateUrl,
            this::startMenuDismissRequest,
        )
    }
    private val themeDelegate by lazy { ThemeDelegate(this, di) }
    private val basePlayerDelegate: BasePlayerDelegate by lazy { PlayerDelegate2(this, di) }
    private val statusBarHelper by lazy { StatusBarHelper(this) }
    private val supportHelper by lazy { SupportHelper(this) }
    private val biliGeetestUtil: BiliGeetestUtil by lazy { BiliGeetestUtilImpl(this, lifecycle) }

    private lateinit var navHostFragment: ComposeFragment

    val currentNav: ComposeFragment
        get() = navHostFragment

    var pageConfig: MyPageConfigInfo? = null
        private set

    private var lastConfig: Configuration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API31，安卓11
            // 设置弹出输入法时不改变窗口高度，使动画更加流畅，高版本安卓可以用imePadding来顶起输入框
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        super.onCreate(savedInstanceState)
        themeDelegate.onCreate(savedInstanceState)

        // 安卓13开始手动申请通知权限（仅首次进入弹窗，不给则后续去系统设置手动开）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                val prefs = getSharedPreferences("bilimiao_permission", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("notification_asked", false)) {
                    prefs.edit().putBoolean("notification_asked", true).apply()
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        1
                    )
                }
            }
        }

        store.onCreate(savedInstanceState)

        lifecycleScope.launch {
            store.appStore.stateFlow.mapNotNull {
                it.theme
            }.flowOn(Dispatchers.Main).collect {
                if (mainUi == null) {
                    initRootView(savedInstanceState)
                }
                // 复用统一主题应用逻辑（与 onConfigurationChanged 共用，避免重复代码）
                applyAppBarTheme(it)
            }
        }

        lifecycleScope.launch {
            SettingPreferences.run { this@MainActivity.dataStore.data }
                .collect { prefs ->
                    if (mainUi != null) {
                        val locked = prefs[SettingPreferences.BottomBarLock] ?: true
                        AppBarBehavior.globalLock = locked
                        ui.root.bottomBarLocked = locked
                        ui.root.appBarBehavior?.bottomBarLocked = locked
                        if (locked) {
                            ui.root.appBar?.let { bar ->
                                ui.root.appBarBehavior?.slideUp(bar, false)
                            }
                        }
                        // updateLayout 会触发 onLayoutChild，锁同步后强制底栏可见
                        ui.root.updateLayout(false)
                        ui.root.rootWindowInsets?.let { setWindowInsets(it) }
                    }
                }
        }
    }

    private fun initRootView(savedInstanceState: Bundle?) {
        mainUi = MainUi(this, startViewWrapper)
        setContentView(ui.root)
        basePlayerDelegate.onCreate(savedInstanceState)
        ui.root.showPlayer = basePlayerDelegate.isPlaying()
        ui.root.playerDelegate = basePlayerDelegate as PlayerDelegate2
        ui.root.onDrawerStateChanged = ::onDrawerStateChanged
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ui.root.rootWindowInsets?.let {
                setWindowInsets(it)
            }
            ui.root.setOnApplyWindowInsetsListener { v, insets ->
                setWindowInsets(insets)
                insets
            }
            ui.root.onPlayerChanged = {
                statusBarHelper.isLightStatusBar =
                    !it || (ui.root.orientation == ScaffoldView.HORIZONTAL && !ui.root.fullScreenPlayer)
                ui.root.rootWindowInsets?.let { setWindowInsets(it) }
            }
        } else {
            setWindowInsetsAndroidL()
        }

        initNavController()
        initAppBar()
        initViewFocusable()
    }

    private fun initNavController() {
        navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as ComposeFragment
        navHostFragment.pageConfig.setConfig = this::notifyConfigChanged

        intent.data?.let { uri ->
            when (uri.scheme) {
                "bilibili", "bilimiao" -> navHostFragment.navigateByUri(uri)
                "https", "http" -> {
                    val urlInfo = BiliUrlMatcher.findIDByUrl(uri.toString())
                    if (urlInfo[0] != "未知类型") {
                        val biliUri = Uri.parse("bilibili://${urlInfo[0].lowercase()}/${urlInfo[1]}")
                        navHostFragment.navigateByUri(biliUri)
                    }
                }
            }
        }
    }

    private fun initAppBar() {
        ui.mAppBar.onBackClick = this.onBackClick
        ui.mAppBar.onOpenMenuClick = this.onOpenMenuClick
        ui.mAppBar.onBackLongClick = this.onBackLongClick
        ui.mAppBar.onMenuItemClick = {
            if (it.prop.action == MenuActions.search) {
                openSearchDialog()
            } else {
                val fragment = currentNav
                if (fragment is MyPage) {
                    val childMenu = it.prop.childMenu
                    if (childMenu != null) {
                        val myPopupMenu = MyPopupMenu(
                            activity = this,
                            myPage = fragment,
                            myPageMenu = childMenu,
                            anchorView = it,
                            themeColor = themeDelegate.themeColor,
                        )
                        myPopupMenu.show()
                    } else {
                        fragment.onMenuItemClick(it, it.prop)
                    }
                }
            }
        }
    }

    fun initViewFocusable() {
        ui.root.isFocusable = true
        ui.root.appBar?.isFocusable = true
        ui.root.content?.isFocusable = true
        ui.root.isFocusableInTouchMode = true
        ui.root.appBar?.isFocusableInTouchMode = true
        ui.root.content?.isFocusableInTouchMode = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { uri ->
            when (uri.scheme) {
                "bilibili", "bilimiao" -> navHostFragment.navigateByUri(uri)
                "https", "http" -> {
                    val urlInfo = BiliUrlMatcher.findIDByUrl(uri.toString())
                    if (urlInfo[0] != "未知类型") {
                        val biliUri = Uri.parse("bilibili://${urlInfo[0].lowercase()}/${urlInfo[1]}")
                        navHostFragment.navigateByUri(biliUri)
                    }
                }
            }
        }
    }

    fun notifyFocusChanged() {
        notifyConfigChanged()
    }
    fun notifyConfigChanged(){
        setMyPageConfig(currentNav.pageConfig.configInfo)
    }


    fun setMyPageConfig(config: MyPageConfigInfo) {
        pageConfig = config
        ui.mAppBar.canBack =  config.menu?.checkable != true
        ui.mAppBar.setProp {
            title = config.title
            menus = config.getMenuItems()
            isNavigationMenu = config.menu?.checkable == true
            navigationKey = config.menu?.checkedKey ?: 0
        }
        ui.root.slideUpBottomAppBar()

        val searchConfig = config.search
        val pageSearchMethod = if (searchConfig?.name.isNullOrBlank()) {
            null
        } else {
            object : PageSearchMethod {
                override val name: String
                    get() = searchConfig?.name ?: ""

                override fun onSearch(keyword: String) {
                    searchSelfPage(keyword)
                }
            }
        }
        startViewWrapper.setPageSearchMethod(pageSearchMethod)
    }

    private fun goBackHome(): Boolean {
        currentNav.goBackHome()
        return true
    }

    private val onBackClick = View.OnClickListener {
        onBackPressed()
    }

    private val onOpenMenuClick = View.OnClickListener {
        ui.root.openDrawer()
    }

    private val onBackLongClick = View.OnLongClickListener {
        if (ui.root.showPlayer) {
            MainBackPopupMenu(
                this@MainActivity,
                it,
                basePlayerDelegate
            ).show()
            true
        } else {
            goBackHome()
        }
    }

    fun onDrawerStateChanged(state: Int) {
        val startTop = ui.root.getDrawerTouchStartY()
        if (startTop > 0f) {
            startViewWrapper.setTouchStartTop(startTop)
        }
        if (state == DrawerBehaviorDelegate.STATE_COLLAPSED) {
            startViewWrapper.closeSearchDialog()
        }
    }

    fun searchSelfPage(keyword: String) {
        currentNav.onSearchSelfPage(this, keyword)
    }

    fun openBottomSheet(page: ComposePage) {
        navHostFragment.openBottomSheet(page)
    }

    private fun startMenuNavigate(page: ComposePage) {
        ui.root.closeDrawer()
        navHostFragment.navigate(page)
    }

    private fun startMenuNavigateUrl(url: String) {
        ui.root.closeDrawer()
        val uri = Uri.parse(url)
        if (!navHostFragment.navigateByUri(uri)) {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
        }
    }

    private fun startMenuDismissRequest() {
        ui.root.closeDrawer()
    }

    fun openSearchDialog() {
        val searchConfig = pageConfig?.search
        val keyword = searchConfig?.keyword ?: ""
        val mode = if (searchConfig?.name.isNullOrBlank()) 0 else 1
        startViewWrapper.openSearchDialog(keyword, mode, false)
        Handler(Looper.getMainLooper()).postDelayed({
            ui.root.openDrawer()
        }, 10)
    }

    fun setWindowInsetsAndroidL() {
        val rectangle = Rect()
        val displayMetrics = DisplayMetrics()
        window.decorView.getWindowVisibleDisplayFrame(rectangle)
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        val top = statusBarHelper.getStatusBarHeight()
        val bottom = displayMetrics.heightPixels - rectangle.bottom - rectangle.top
        val right = displayMetrics.widthPixels - rectangle.right
        setWindowInsets(0, top, right, bottom, null)
    }

    fun setWindowInsets(insets: WindowInsets) {
        val left = insets.systemWindowInsetLeft
        val top = insets.systemWindowInsetTop
        val right = insets.stableInsetRight
        val bottom = insets.systemWindowInsetBottom
        var displayCutout: DisplayCutout? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            displayCutout = insets.displayCutout
        }
        setWindowInsets(left, top, right, bottom, displayCutout)
    }

    fun setWindowInsets(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        displayCutout: DisplayCutout?
    ) {
        val windowStore = store.windowStore
        windowStore.setWindowInsets(
            left, top, right, bottom,
        )
        windowStore.setBottomSheetContentInsets(
            0, config.bottomSheetTitleHeight, 0, 0
        )
        val playerLP = ui.mPlayerLayout.layoutParams
        if (playerLP is ScaffoldView.LayoutParams) {
            val behavior = playerLP.behavior
            if (behavior is PlayerBehavior) {
                behavior.setWindowInsets(left, top, right, bottom)
            }
        }
        ui.mAppBar.setWindowInsets(left, top, right, bottom)
        val showPlayer = ui.root.showPlayer
        val fullScreenPlayer = ui.root.fullScreenPlayer
        if (ui.root.orientation == ScaffoldView.VERTICAL) {
            val bottomLockExtra = if (ui.root.bottomBarLocked) config.appBarMenuHeight else 0
            if (showPlayer) {
                windowStore.setContentInsets(
                    left,
                    0,
                    right,
                    top + bottom + config.appBarTitleHeight + ui.root.smallModePlayerMinHeight + bottomLockExtra,
                )
            } else {
                windowStore.setContentInsets(
                    left, top, right, bottom + config.appBarTitleHeight + bottomLockExtra,
                )
            }
            windowStore.setBottomAppBarHeight(config.appBarMenuHeight)
            ui.mContainerView.setPadding(0, 0, 0, 0)
            ui.mSubContainerView.setPadding(0, 0, 0, 0)
            ui.mPlayerLayout.setPadding(
                0, if (fullScreenPlayer) 0 else top, 0, 0
            )
        } else {
            // 横屏时也显示底部应用栏
            windowStore.setContentInsets(
                0, top, right, bottom,
            )
            windowStore.setBottomAppBarHeight(config.appBarMenuHeight)
            ui.mContainerView.setPadding(left, 0, right, 0)
            ui.mSubContainerView.setPadding(0, 0, 0, 0)
            ui.mPlayerLayout.setPadding(
                0, 0, 0, 0
            )
        }
        basePlayerDelegate.setWindowInsets(left, top, right, bottom, displayCutout)
        ui.root.statusBarHeight = top
        ui.root.updateLayout(false)
    }

    override fun onResume() {
        super.onResume()
        basePlayerDelegate.onResume()
    }

    override fun onPause() {
        super.onPause()
        basePlayerDelegate.onPause()
    }

    override fun onDestroy() {
        basePlayerDelegate.onDestroy()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        basePlayerDelegate.onStart()
    }

    override fun onStop() {
        super.onStop()
        basePlayerDelegate.onStop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        (basePlayerDelegate as? PlayerDelegate2)?.tryEnterPipOnBackground()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        miaoLogger() debug "onKeyUp: $keyCode"
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> {
                val videoPlayer = mainUi?.mVideoPlayerView
                if (videoPlayer != null && mainUi?.root?.showPlayer == true) {
                    if (videoPlayer.isInPlayingState) {
                        videoPlayer.onVideoPause()
                    } else {
                        videoPlayer.onVideoResume()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val videoPlayer = mainUi?.mVideoPlayerView
                if (videoPlayer != null && mainUi?.root?.showPlayer == true) {
                    videoPlayer.seekTo(videoPlayer.currentPosition - 5000)
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val videoPlayer = mainUi?.mVideoPlayerView
                if (videoPlayer != null && mainUi?.root?.showPlayer == true) {
                    videoPlayer.seekTo(videoPlayer.currentPosition + 5000)
                }
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (mainUi?.root?.showPlayer == true) {
                    basePlayerDelegate.onBackPressed()
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * 通知权限设置界面跳转
     */
    private fun jumpNotificationSetting() {
        val intent = Intent()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                intent.action = "android.settings.APP_NOTIFICATION_SETTINGS"
                intent.putExtra("app_package", packageName)
                intent.putExtra("app_uid", applicationInfo.uid)
            } else {
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                intent.data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            val uri = Uri.fromParts("package", packageName, null)
            intent.data = uri
            startActivity(intent)
        }
    }

    /**
     * 通知权限授权提示
     */
    private fun showNotificationPermissionTips() {
        MaterialAlertDialogBuilder(this).apply {
            setTitle("请求授权通知权限")
            setMessage("从Android13开始，需要您授予通知权限，在您向该应用授予该权限之前，该应用都将无法发送通知。\n受影响的功能：通知栏播放器控制器、下载进度通知")
            setCancelable(false)
            setPositiveButton("去授权") { dialog, _ ->
                jumpNotificationSetting()
            }
            setNegativeButton("拒绝", null)
        }.show()
    }

    /**
     * 判断授权的方法  授权成功直接调用写入方法  这是监听的回调
     * 参数  上下文   授权结果的数组   申请授权的数组
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            val i = permissions.indexOf(Manifest.permission.POST_NOTIFICATIONS)
            if (i != -1 && grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                showNotificationPermissionTips()
            }
        }
    }


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        basePlayerDelegate.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    /** 统一主题应用：stateFlow 和 onConfigurationChanged 共用 */
    private fun applyAppBarTheme(themeState: AppStore.ThemeSettingState) {
        resetViewConfig()
        val themeColor = themeState.color
        val isDark = when(themeState.darkMode) {
            0 -> themeDelegate.isSystemInDark()
            1 -> false
            else -> true
        }
        var bgColor = if (themeState.appBarType == 0) {
            val hct = Hct.fromInt(themeColor)
            val tone = if (isDark) 20.0 else 90.0
            Hct.from(hct.hue, 10.0, tone).toInt()
        } else {
            config.blockBackgroundColor
        }
        bgColor = (bgColor and 0x00FFFFFF) or (0xF8000000).toInt()
        ui.mAppBar.post {
            ui.mAppBar.updateTheme(themeColor, bgColor, config.foregroundAlpha45Color)
        }
        themeDelegate.setThemeColor(themeColor)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // AppCompatDelegate.setDefaultNightMode 已处理主题资源切换，不再使用已废弃的 resources.updateConfiguration

        basePlayerDelegate.onConfigurationChanged(newConfig)
        ui.root.orientation = newConfig.orientation
        statusBarHelper.isLightStatusBar =
            !ui.root.showPlayer || (ui.root.orientation == ScaffoldView.HORIZONTAL && !ui.root.fullScreenPlayer)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            setWindowInsetsAndroidL()
        }
        // 系统深色/浅色主题切换时刷新底栏主题
        val oldNight = lastConfig?.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK)
        val newNight = newConfig.uiMode.and(Configuration.UI_MODE_NIGHT_MASK)
        if (oldNight != newNight) {
            val themeState = store.appStore.stateFlow.value.theme
            if (themeState != null) {
                applyAppBarTheme(themeState)
            }
        }
        lastConfig = Configuration(newConfig)
    }

    private fun onHostNavBack(): Boolean {
        currentNav.onBackPressed()
        return true
    }

    override fun onBackPressed() {
        if (ui.root.isDrawerOpen()) {
            if (startViewWrapper.showSearchDialog) {
                startViewWrapper.closeSearchDialog()
                return
            }
            ui.root.closeDrawer()
            return
        }
        if (ui.root.fullScreenPlayer && basePlayerDelegate.onBackPressed()) {
            return
        }
        if (onHostNavBack()) {
            return
        }
        super.onBackPressed()
    }

    override fun attachBaseContext(newBase: Context) {
        val configuration: Configuration = newBase.resources.configuration
        ScreenDpiUtil.readCustomConfiguration(configuration)
        val newContext = newBase.createConfigurationContext(configuration)
        super.attachBaseContext(newContext)
    }

}
