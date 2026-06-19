package com.a10miaomiao.bilimiao.comm.delegate.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
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
            val isDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
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

    fun isSystemInDark(): Boolean {
        val uiMode = activity.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

}
