package com.a10miaomiao.bilimiao.comm.platform

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat

/** 应用默认主题色（少女粉）：系统取不到动态色时的兜底 */
private val DEFAULT_THEME_COLOR = 0xFFFB7299.toInt()

/**
 * 当前设备是否支持 Material You 动态取色（Android 12+）。
 *
 * 主题设置页据此决定要不要展示「Material You」选项 —— 不支持的机型露一个选了不生效的
 * 选项等于骗用户（上游 f9cc3474 同款做法）。
 */
val isMaterialYouSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 取系统主色（Material You 动态主题色），用于主题设置页色块、顶部操作栏等自绘控件取色。
 *
 * 为什么必须带 SDK 判断 + runCatching：`android.R.color.system_primary_light` 是 Android 12
 * 才新增的框架资源，低版本 `getColor` 会抛 `Resources.NotFoundException` —— 老用户 DataStore
 * 里还留着 `THEME_TYPE_DYNAMIC_COLOR` 时，AppStore 启动读取就会踩到（崩溃）。
 * 取不到一律回退 [DEFAULT_THEME_COLOR]。
 */
fun getMaterialYouColor(context: Context): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return DEFAULT_THEME_COLOR
    return runCatching {
        ContextCompat.getColor(context, android.R.color.system_primary_light)
    }.getOrDefault(DEFAULT_THEME_COLOR)
}
