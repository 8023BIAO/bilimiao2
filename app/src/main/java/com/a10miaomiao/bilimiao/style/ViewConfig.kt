package com.a10miaomiao.bilimiao.config

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.a10miaomiao.bilimiao.R
import splitties.dimensions.dip
import splitties.views.dsl.core.matchParent

class ViewConfig(val context: Context) {
    // 从 Resources 创建全新的 Theme 解析属性，绕过 Activity.mTheme 缓存。
    // AppCompatDelegate.setDefaultNightMode 会更新 Resources 配置，
    // 但 Activity.getTheme() 缓存的 Theme 对象在第2次切换后不会刷新。
    // 直接用 resources.newTheme() + applyStyle 保证每次读到最新的主题属性。
    private val attrCache = HashMap<Int, Int>()

    private fun resolveAttr(resid: Int): Int {
        attrCache[resid]?.let { return it }
        val freshTheme = context.resources.newTheme().apply {
            applyStyle(R.style.Theme_Bilimiao, true)
        }
        val typedValue = TypedValue()
        freshTheme.resolveAttribute(resid, typedValue, true)
        val result = typedValue.resourceId
        attrCache[resid] = result
        return result
    }

    val pagePadding = context.dip(10)
    val bottomSheetTitleHeight = context.dip(48)

    val smallPadding = context.dip(5)
    val largePadding = context.dip(20)

    val dividerSize = context.dip(8)

    val themeName = context.resources.getString(resolveAttr(R.attr.themeName))

    val themeColorResource = resolveAttr(android.R.attr.colorPrimary)
    val themeColor = getColor(themeColorResource)

    val windowBackgroundResource = resolveAttr(R.attr.defaultBackgroundColor)
    val windowBackgroundColor = getColor(windowBackgroundResource)

    val blockBackgroundResource = resolveAttr(R.attr.blockBackground)
    val blockBackgroundColor = getColor(blockBackgroundResource)
    val blockBackgroundAlpha45Color = (blockBackgroundColor and 0x00FFFFFF) or 0x71000000

    val foregroundColorResource = resolveAttr(R.attr.foregroundColor)
    val foregroundColor = getColor(foregroundColorResource)
    val foregroundAlpha45Color = (foregroundColor and 0x00FFFFFF) or 0x71000000

    val foregroundAlpha80Color = (foregroundColor and 0x00FFFFFF) or 0xCC000000.toInt()

    private val isLightThemeResource = resolveAttr(R.attr.isLightTheme)
    val isLightTheme = context.resources.getBoolean(isLightThemeResource)

    val lineColorResource = resolveAttr(R.attr.lineColor)
    val lineColor = getColor(lineColorResource)
    val shadowColorResource = resolveAttr(R.attr.shadowColor)
    val shadowColor = getColor(shadowColorResource)

    val selectableItemBackground = resolveAttr(android.R.attr.selectableItemBackground)
    val selectableItemBackgroundBorderless =
        resolveAttr(android.R.attr.selectableItemBackgroundBorderless)

    val appBarHeight = context.dip(70)
    val appBarTitleHeight = context.dip(20)
    val appBarMenuHeight = context.dip(50)
    val appBarMenuWidth = context.dip(120)

    internal fun getColor(resId: Int): Int {
        return ContextCompat.getColor(context, resId)
    }
}

@SuppressLint("StaticFieldLeak")
private object BackendViewConfig {
    var config: ViewConfig? = null
    var configContext: Context? = null
}

fun Context.resetViewConfig() {
    // 无条件清除缓存，确保主题切换后所有Context都重新解析
    BackendViewConfig.config = null
    BackendViewConfig.configContext = null
}
@get:Synchronized
val Context.config: ViewConfig
    get() {
        return BackendViewConfig.config?.takeIf {
            this === BackendViewConfig.configContext
        } ?: ViewConfig(this).also {
            BackendViewConfig.config = it
            BackendViewConfig.configContext = this
        }
    }
inline val Fragment.config get() = requireContext().config
inline val View.config get() = context.config
