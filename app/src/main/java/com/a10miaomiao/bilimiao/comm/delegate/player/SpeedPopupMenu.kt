package com.a10miaomiao.bilimiao.comm.delegate.player

import android.app.Activity
import android.view.Menu
import android.view.View
import android.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import com.a10miaomiao.bilimiao.comm.utils.setCheckMarkTint

class SpeedPopupMenu(
    private val activity: Activity,
    private val anchor: View,
    private val value: Float,
    private val list: List<Float>,
    private val themeColor: Int,
) {
    private var speedListener: ((Float) -> Unit)? = null
    private var popupMenu = PopupMenu(ContextThemeWrapper(activity, com.a10miaomiao.bilimiao.R.style.Theme_Bilimiao), anchor)
    private var currentValue = value

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
            popupMenu.menu.getItem(i).isChecked = (list[i] == currentValue)
        }
    }

    private fun Menu.initMenu() {
        list.forEachIndexed { index, item ->
            add(Menu.FIRST, index, 0, item.toString()).apply {
                isCheckable = true
            }
        }
    }

    fun setOnChangedSpeedListener(changedSpeed: (Float) -> Unit) {
        speedListener = changedSpeed
    }

    fun show() {
        popupMenu = PopupMenu(ContextThemeWrapper(activity, com.a10miaomiao.bilimiao.R.style.Theme_Bilimiao), anchor)
        popupMenu.menu.apply { initMenu() }
        updateChecked()
        if (themeColor != 0) popupMenu.setCheckMarkTint(themeColor)
        if (speedListener != null) popupMenu.setOnMenuItemClickListener { val position = it.itemId; currentValue = list[position]; updateChecked(); speedListener!!(currentValue); false }
        popupMenu.show()
    }
}
