package com.a10miaomiao.bilimiao.comm.delegate.player

import android.app.Activity
import android.view.Menu
import android.view.View
import android.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import com.a10miaomiao.bilimiao.comm.utils.setCheckMarkTint
import com.shuyu.gsyvideoplayer.utils.GSYVideoType

class ScalePopupMenu(
    private val activity: Activity,
    private val anchor: View,
    private val value: Int,
    private val themeColor: Int,
) {
    private var scaleListener: ((Int) -> Unit)? = null
    private var popupMenu = PopupMenu(ContextThemeWrapper(activity, com.a10miaomiao.bilimiao.R.style.Theme_Bilimiao), anchor)
    private var currentValue = value

    private val scaleList = listOf(
        GSYVideoType.SCREEN_TYPE_DEFAULT to "默认比例",
        GSYVideoType.SCREEN_TYPE_16_9 to "16:9",
        GSYVideoType.SCREEN_TYPE_4_3 to "4:3",
        GSYVideoType.SCREEN_TYPE_FULL to "全屏裁减",
        GSYVideoType.SCREEN_MATCH_FULL to "全屏拉伸",
    )

    init {
        popupMenu.menu.apply { initMenu() }
        updateChecked()
        // 🔧 主题切换时 PopupMenu 不会自动更新，需手动注入 checkMark 颜色
        if (themeColor != 0) {
            popupMenu.setCheckMarkTint(themeColor)
        }
    }

    private fun updateChecked() {
        for (i in 0 until popupMenu.menu.size()) {
            popupMenu.menu.getItem(i).isChecked = (scaleList[i].first == currentValue)
        }
    }

    private fun Menu.initMenu() {
        scaleList.forEachIndexed { index, item ->
            add(Menu.FIRST, index, 0, item.second).apply {
                isCheckable = true
            }
        }
    }

    fun setOnChangedScaleListener(changedScale: (Int) -> Unit) {
        scaleListener = changedScale
    }

    fun show() {
        popupMenu = PopupMenu(ContextThemeWrapper(activity, com.a10miaomiao.bilimiao.R.style.Theme_Bilimiao), anchor)
        popupMenu.menu.apply { initMenu() }
        updateChecked()
        if (themeColor != 0) popupMenu.setCheckMarkTint(themeColor)
        if (scaleListener != null) popupMenu.setOnMenuItemClickListener { val position = it.itemId; currentValue = scaleList[position].first; updateChecked(); scaleListener!!(currentValue); false }
        popupMenu.show()
    }
}
