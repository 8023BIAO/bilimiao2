package com.a10miaomiao.bilimiao.comm.store

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences.dataStore
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.message.UnreadMessageInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.store.base.BaseStore
import com.kongzue.dialogx.DialogX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance

class AppStore(override val di: DI) :
    ViewModel(), BaseStore<AppStore.State> {

    data class ThemeSettingState (
        val color: Int,
        val type: Int = SettingConstants.THEME_TYPE_DEFAULT,
        val darkMode: Int = 0,
        val appBarType: Int = 0,
        // ===== 自定义主题（主题设置页第 11 项）=====
        // 三个字段都带默认值、且加在最后：老的构造/copy 调用一处都不用改
        // （data class 的 copy 是按名传参，加尾字段不影响既有代码）。
        // 自定义主色；null = 从没保存过自定义颜色（老用户、新装都是这个状态 → 第 11 项走"默认值=当前主题色"）
        val customPrimary: Int? = null,
        // 自定义副色；null = 没保存过；**等于 customPrimary** = 跟随主色（不覆盖调色板，见 BilimiaoTheme）
        val customSecondary: Int? = null,
        // 自定义点缀色（强调色）；null = 没保存过；等于 customPrimary = 跟随主色
        val customTertiary: Int? = null,
    )

    data class HomeSettingState (
        val showTimeMachine: Boolean = false,
        val showPopular: Boolean = false,
        val showRecommend: Boolean = true,
        val showTimeSelect: Boolean = false,
        val showBangumi: Boolean = true,
        val showCinema: Boolean = false,
        // 分区。默认**显示**：这个 Tab 是用户主动要的，默认藏起来等于没做；
        // 不想要的在 设置→首页设置 里关掉即可（与 推荐/番剧 默认开是同一个取舍）。
        val showRegion: Boolean = false,
        // 直播。默认**显示**（用户主动要的入口，默认藏起来等于没做）；
        // 位置是首页第一个 Tab，不想要的在 设置→首页设置 里关掉。
        val showLive: Boolean = true,
        val entryView: Int = SettingConstants.HOME_ENTRY_VIEW_RECOMMEND,
    )

    data class State (
        var theme: ThemeSettingState? = null,
        var home: HomeSettingState = HomeSettingState(),
        /**
         * 直播设置（第四阶段）。
         *
         * 为什么挂在 AppStore 上（而不是像 Bloc 那样各页面自己 collect 一份 DataStore）：
         *  ① 直播浏览页（首页 Tab）要**跟着设置变**（每行卡片数、默认排序），
         *     AppStore 的 stateFlow 本来就是 Compose 侧的统一读取口（HomePage 读 state.home 建 Tab 列表）；
         *  ② 类型直接用 [SettingPreferences.Live.Values]：字段默认值、读取兜底只有一份定义，
         *     AppStore 和 [SettingPreferences.liveSettings]（播放页用的主线程快照）拿到的东西**完全是同一个类型**，
         *     以后加一项只改一处。
         */
        var live: SettingPreferences.Live.Values = SettingPreferences.Live.Values(),
    )

    override val stateFlow = MutableStateFlow(State())
    override fun copyState() = state.copy()

    private val context: Context by instance()

    override fun init(context: Context) {
        super.init(context)
        SettingPreferences.launch(viewModelScope) {
            context.dataStore.data.collect {
                val themeType = it[ThemeType] ?: SettingConstants.THEME_TYPE_DEFAULT
                val themeColor = if (themeType == SettingConstants.THEME_TYPE_DYNAMIC_COLOR) {
                    materialYouColor
                } else {
                    (it[ThemeColor] ?: 0xFF2196F3).toInt()
                }
                setState {
                    home = HomeSettingState(
                        showTimeMachine = it[HomeTimeMachineShow] ?: false,
                        showPopular = it[HomePopularShow] ?: false,
                        showRecommend = it[HomeRecommendShow] ?: true,
                        showTimeSelect = it[TimeSelectShow] ?: false,
                        showBangumi = it[HomeBangumiShow] ?: true,
                        showCinema = it[HomeCinemaShow] ?: false,
                        showRegion = it[HomeRegionShow] ?: false,
                        showLive = it[HomeLiveShow] ?: true,
                        entryView = it[HomeEntryView] ?: SettingConstants.HOME_ENTRY_VIEW_RECOMMEND
                    )
                    theme = ThemeSettingState(
                        color = themeColor,
                        type = themeType,
                        darkMode = it[ThemeDarkMode] ?: 0,
                        appBarType = it[ThemeAppBarType] ?: 0,
                        // 自定义三色：读不到就是 null（老版本升上来的第一批数据必然如此），
                        // 此时 appColorScheme 走的是和以前完全一样的调用
                        customPrimary = it[ThemeCustomPrimary],
                        customSecondary = it[ThemeCustomSecondary],
                        customTertiary = it[ThemeCustomTertiary],
                    )
                    // 直播设置（第四阶段）：整块交给 SettingPreferences.Live.of 翻译，
                    // 这里**再抄一遍默认值**的话迟早会和设置页的 defaultValue 漂移
                    live = SettingPreferences.Live.of(it)
                }
            }
        }
    }

    val materialYouColor get() = ContextCompat.getColor(
        context,
        android.R.color.system_primary_light
    )

    fun setDarkMode(mode: Int) {
        // 同步更新 state，确保在 AppCompatDelegate.setDefaultNightMode 触发
        // uiMode config 变化前，stateFlow 中的 darkMode 已是最新值，避免
        // onConfigurationChanged 读到过期数据导致 Compose 颜色主题/AppBar 错乱
        setState {
            theme = theme?.copy(darkMode = mode)
        }
        viewModelScope.launch {
            SettingPreferences.edit(context) {
                it[ThemeDarkMode] = mode
            }
        }
        if (mode == 0) {
            DialogX.globalTheme = DialogX.THEME.AUTO
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else if (mode == 1) {
            DialogX.globalTheme = DialogX.THEME.LIGHT
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        } else if (mode == 2) {
            DialogX.globalTheme = DialogX.THEME.DARK
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    fun setThemeColor(color: Long, type: Int) {
        viewModelScope.launch {
            SettingPreferences.edit(context) {
                it[ThemeColor] = color
                it[ThemeType] = type
            }
        }
    }

    /**
     * 保存自定义主题（主题设置页第 11 项的弹窗点"保存"）。
     *
     * 一次写 5 个 key：
     *  - ThemeColor / ThemeType：**当前生效**的主题（和其它预设共用同一套读取逻辑，
     *    所以 AppBar、MainActivity.applyAppBarTheme 那些拿 themeState.color 的地方不用改）；
     *  - ThemeCustomPrimary/Secondary/Tertiary：**自定义里存的那一份**，切到预设色后仍然留着，
     *    下次点第 11 项还能带着上次的三色继续改。
     *
     * 为什么要先 setState 再落盘：DataStore 的回流是异步的，只靠 init() 里的 collector，
     * 弹窗关掉的那一两帧还会是旧配色（换主题这种"必须立刻看到"的操作会闪）。
     * 这里和 setDarkMode 一个思路：内存态先改，磁盘随后。
     */
    fun setCustomThemeColor(primary: Int, secondary: Int, tertiary: Int) {
        setState {
            theme = (theme ?: ThemeSettingState(color = primary)).copy(
                color = primary,
                type = SettingConstants.THEME_TYPE_CUSTOM,
                customPrimary = primary,
                customSecondary = secondary,
                customTertiary = tertiary,
            )
        }
        viewModelScope.launch {
            SettingPreferences.edit(context) {
                // ThemeColor 一贯存的是"32 位真彩色当成无符号数"的 Long（见 setThemeColor 的调用处），
                // 这里保持同一约定，免得同一个 key 里出现两种编码
                it[ThemeColor] = (primary.toLong() and 0xFFFFFFFFL)
                it[ThemeType] = SettingConstants.THEME_TYPE_CUSTOM
                it[ThemeCustomPrimary] = primary
                it[ThemeCustomSecondary] = secondary
                it[ThemeCustomTertiary] = tertiary
            }
        }
    }

    fun setAppBarType(type: Int) {
        viewModelScope.launch {
            SettingPreferences.edit(context) {
                it[ThemeAppBarType] = type
            }
        }
    }


}