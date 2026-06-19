package com.a10miaomiao.bilimiao.comm.utils

import android.content.res.ColorStateList
import android.widget.CheckedTextView
import androidx.appcompat.widget.ListPopupWindow
import androidx.appcompat.widget.PopupMenu

fun PopupMenu.setCheckMarkTint(color: Int) {
    if (color == 0) return
    try {
        val popupField = PopupMenu::class.java.getDeclaredField("mPopup")
        popupField.isAccessible = true
        val listPopupWindow = popupField.get(this) as? ListPopupWindow ?: return
        val listView = listPopupWindow.listView ?: return
        listView.post {
            for (i in 0 until listView.childCount) {
                val child = listView.getChildAt(i)
                if (child is CheckedTextView) {
                    child.checkMarkTintList = ColorStateList.valueOf(color)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
