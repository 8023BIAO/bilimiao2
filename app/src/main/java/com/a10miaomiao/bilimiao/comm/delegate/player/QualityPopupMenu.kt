package com.a10miaomiao.bilimiao.comm.delegate.player

import android.app.Activity
import android.view.Menu
import android.view.View
import android.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import com.a10miaomiao.bilimiao.R
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.PlayerSourceInfo
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.utils.setCheckMarkTint

class QualityPopupMenu(
    private val activity: Activity,
    private val anchor: View,
    private val userStore: UserStore,
    private val list: List<PlayerSourceInfo.AcceptInfo>,
    private val value: Int,
    private val themeColor: Int,
) {
    private var qualityListener: ((Int) -> Unit)? = null
    private var popupMenu = PopupMenu(ContextThemeWrapper(activity, com.a10miaomiao.bilimiao.R.style.Theme_Bilimiao), anchor)
    val MAX_QUALITY_NOT_LOGIN = 48 // 48[480P 清晰]
    val MAX_QUALITY_NOT_VIP = 80 // 80[1080P 高清]
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
            popupMenu.menu.getItem(i).isChecked = (list[i].quality == currentValue)
        }
    }

    private fun Menu.initMenu() {
        list.forEachIndexed { index, item ->
            add(Menu.FIRST, index, 0, item.description).apply {
                isCheckable = true
                // 不限制画质选择——全开放给服务器决定
            }
        }
    }

    fun setOnChangedQualityListener(changedQuality: (Int) -> Unit) {
        qualityListener = changedQuality
    }

    fun show() {
        popupMenu = PopupMenu(ContextThemeWrapper(activity, com.a10miaomiao.bilimiao.R.style.Theme_Bilimiao), anchor)
        popupMenu.menu.apply { initMenu() }
        updateChecked()
        if (themeColor != 0) popupMenu.setCheckMarkTint(themeColor)
        if (qualityListener != null) popupMenu.setOnMenuItemClickListener { val position = it.itemId; currentValue = list[position].quality; updateChecked(); qualityListener!!(currentValue); false }
        popupMenu.show()
    }
}
