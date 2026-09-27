package com.a10miaomiao.bilimiao.comm.delegate.helper

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class StatusBarHelper(
    val activity: Activity,
) {

    var isShowNavigation = true
        set(value) {
            field = value
            update()
        }
    var isShowStatus = true
        set(value) {
            field = value
            update()
        }
    var isLightStatusBar = true
        set(value) {
            field = value
            update()
        }
    /**
     * 导航栏（手势条）底下的界面是不是浅色 → true = 用**深色**图标。
     *
     * 与 [isLightStatusBar] 分开：状态栏看的是"页面顶部"（竖屏播放器在顶部 → 那里是黑的），
     * 导航栏看的是"页面底部"（底栏/信息流都是跟主题走的），两者不能共用一个值。
     * 全屏播放器时底部也是黑的，由 MainActivity 置为 false。
     */
    var isLightNavigationBar = true
        set(value) {
            field = value
            update()
        }

    init {
        // 全透明状态栏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.window.run {
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                statusBarColor = Color.TRANSPARENT
            }
        }
    }

    fun update () {
        var uiFlags = if (isShowStatus) {
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
        uiFlags = uiFlags or 0x00001000
        if (!isShowNavigation) {
            uiFlags = uiFlags or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
        val isNightMode = activity.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        // ★ 顺序要紧：先写老的 systemUiVisibility，再用 compat 接口设置图标明暗。
        //   WindowInsetsControllerCompat 在低版本就是往 systemUiVisibility 里塞 LIGHT_* 位，
        //   反过来写会把刚设好的明暗位清掉（表现为状态栏/导航栏图标颜色不跟主题）。
        activity.window.decorView.systemUiVisibility = uiFlags
        val controller = WindowCompat.getInsetsController(
            activity.window,
            activity.window.decorView
        )
        controller.isAppearanceLightStatusBars = isLightStatusBar && !isNightMode
        // 原来**完全没设**导航栏明暗 → 浅色主题下白图标压在浅色底栏上 = 看不见（用户反馈的"没适配"）
        controller.isAppearanceLightNavigationBars = isLightNavigationBar && !isNightMode
    }

    fun getStatusBarHeight (): Int {
        var statusBarHeight = 0
        //获取status_bar_height资源的ID
        val resourceId: Int = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            //根据资源ID获取响应的尺寸值
            statusBarHeight = activity.resources.getDimensionPixelSize(resourceId)
        }
        return statusBarHeight
    }

}