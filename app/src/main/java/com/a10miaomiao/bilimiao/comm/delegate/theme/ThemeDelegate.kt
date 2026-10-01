package com.a10miaomiao.bilimiao.comm.delegate.theme

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.utils.NightModeUtil
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.kodein.di.DI
import org.kodein.di.DIAware


class ThemeDelegate(
    private val activity: AppCompatActivity,
    override val di: DI,
) : DIAware {

    companion object {
        fun getNightMode(context: Context): Int {
            // 超时上限防止 DataStore 异常时主线程无限阻塞；超时降级为跟随系统
            return runBlocking {
                withTimeoutOrNull(500L) {
                    SettingPreferences.mapData(context) {
                        it[ThemeDarkMode] ?: 0
                    }
                } ?: 0
            }
        }
    }

    private val _themeColor = MutableLiveData<Int>()
    val themeColor get() = _themeColor.value ?: defaultThemeColor
    private val defaultThemeColor: Int
        get() {
            // ★2026-10-01：深浅色判据改走 NightModeUtil（真源）。
            //   这里要的是"**应用当前**是不是深色"（颜色档位要跟 App 主题走，而不是跟系统），
            //   原来读 activity.resources.configuration.uiMode —— 那份是被 attachBaseContext 覆盖过的
            //   冻结快照，切系统深浅色（uiMode 在 configChanges 里、页面不重建）不会更新。
            //   没有缓存要清：这是 get 访问器，每次读都重新求值。
            val isDark = NightModeUtil.isAppInDark()
            val colorRes = if (isDark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.getColor(activity, colorRes)
            } else {
                ContextCompat.getColor(activity, if (isDark) android.R.color.holo_blue_dark else android.R.color.holo_blue_light)
            }
        }

    fun onCreate(savedInstanceState: Bundle?) {
    }

    fun setThemeColor(color: Int) {
        _themeColor.value = color
    }

    fun observeTheme(owner: LifecycleOwner, observer: Observer<Int>) = _themeColor.observe(owner, observer)

    /**
     * **系统**是不是深色（设置里选「跟随系统」时用它）。
     *
     * ★2026-10-01：改问 `Resources.getSystem()`（真源）——本页的 `resources.configuration` 是被
     * `attachBaseContext` 覆盖过的冻结快照，`uiMode` 在 `configChanges` 名单里、切深浅色不重建，
     * 读它就一直停在进页那一刻。**重新求值时机本来就有**：`MainActivity.onConfigurationChanged`
     * 检测到 `oldNight != newNight` 会重跑 `applyAppBarTheme`，而 `applyAppBarTheme` 的
     * `darkMode == 0`（跟随系统）这一支正是调本函数；另有 stateFlow 收集时也会调。
     * 所以这里**只换源**，不需要补额外的重新求值。
     */
    fun isSystemInDark(): Boolean = NightModeUtil.isSystemInDark()

}
