package com.a10miaomiao.bilimiao.widget.menu

import android.content.Context
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import com.a10miaomiao.bilimiao.comm.utils.setCheckMarkTint

class CheckPopupMenu<T>(
    private val context: Context,
    private val anchor: View,
    private val menus: List<MenuItemInfo<T>>,
    private val value: T,
    themeColor: Int = 0,
    private val checkable: Boolean = true,
) : PopupMenu.OnMenuItemClickListener {

    private val popupMenu = PopupMenu(ContextThemeWrapper(context, com.a10miaomiao.bilimiao.R.style.Theme_Bilimiao), anchor)
    private var currentValue = value

    var onMenuItemClick: ((item: MenuItemInfo<T>) -> Unit)? = null

    init {
        popupMenu.menu.apply { initMenu() }
        popupMenu.setOnMenuItemClickListener(this)
        // 🔧 主题切换时 PopupMenu 不会自动更新，需手动注入 checkMark 颜色
        if (themeColor != 0) {
            popupMenu.setCheckMarkTint(themeColor)
        }
    }

    private fun Menu.initMenu() {
        menus.forEachIndexed { index, item ->
            add(Menu.FIRST, index, 0, item.title).apply {
                isCheckable = checkable
                isChecked = checkable && (item.value == currentValue)
            }
        }
    }

    private fun updateChecked() {
        if (!checkable) return
        for (i in 0 until popupMenu.menu.size()) {
            popupMenu.menu.getItem(i).isChecked = (menus[i].value == currentValue)
        }
    }

    fun setOnMenuItemClickListener(listener: PopupMenu.OnMenuItemClickListener) {
        popupMenu.setOnMenuItemClickListener(listener)
    }

    fun show() {
        popupMenu.show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        currentValue = menus[item.itemId].value
        updateChecked()
        onMenuItemClick?.invoke(menus[item.itemId])
        return true
    }

    class MenuItemInfo<T>(
        var title: String,
        var value: T,
    )

}
