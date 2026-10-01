package com.a10miaomiao.bilimiao.comm.utils

import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate

/**
 * 深浅色判据的**真源**（别再读 `activity.resources.configuration`）。
 *
 * 为什么要有这个工具：`MainActivity` / `VideoPlayerActivity` / `LivePlayerActivity` 都覆写了
 * `attachBaseContext`，把**建页那一刻**的 `Configuration`（含 `uiMode`/`orientation`）交给
 * `createConfigurationContext(...)`；而三个 Activity 的 `configChanges` 又都含 `uiMode`
 * ⇒ 切系统深浅色**不重建页面**，读那份 `resources.configuration` 就永远停在进页那一刻
 * （与直播间「旋转」回不到竖屏**同一个病根**）。
 *
 * 同机制的既有正确做法（都不是新发明）：
 * · `LiveDanmakuOverlay`：读 `Resources.getSystem()`（系统配置，不受 app override 影响）；
 * · `PlayerController.hostIsLandscape` / `ScaffoldView`：读 `decorView` **真实尺寸**判横竖。
 */
object NightModeUtil {

    /** 系统是否深色：问**系统**配置（`Resources.getSystem()` 不吃 app 的 override） */
    fun isSystemInDark(): Boolean {
        val uiMode = Resources.getSystem().configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 应用当前是不是深色：设置是「跟随系统」时看 [isSystemInDark]，
     * 「始终浅色 / 始终深色」由设置说了算。
     *
     * 真源用 `AppCompatDelegate.getDefaultNightMode()`：`Bilimiao.onCreate` 与
     * `AppStore.setDarkMode` 都从 `ThemeDarkMode` 写它，设置一改这里立刻是新的
     * （`MODE_NIGHT_UNSPECIFIED` 归入"跟随系统"）。
     */
    fun isAppInDark(): Boolean = when (AppCompatDelegate.getDefaultNightMode()) {
        AppCompatDelegate.MODE_NIGHT_YES -> true
        AppCompatDelegate.MODE_NIGHT_NO -> false
        else -> isSystemInDark()
    }
}
