package com.a10miaomiao.bilimiao.widget.scaffold

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import com.a10miaomiao.bilimiao.R
import com.a10miaomiao.bilimiao.config.config

class MenuCheckableItemView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : MenuItemView(context, attrs) {

    var themeColor = 0
        set(value) = run {
            field = value
            updateChecked()
        }

    var fgColor = config.foregroundAlpha45Color
        set(value) {
            field = value
            if (!checked) {
                ui.title.setTextColor(value)
                ui.icon.imageTintList = ColorStateList.valueOf(value)
            }
        }

    var checked: Boolean = false
        set(value) {
            field = value
            updateChecked()
        }

    private fun updateChecked() {
        if (checked) {
            setBackgroundResource(config.selectableItemBackgroundBorderless)
            ui.title.setTextColor(themeColor)
            ui.icon.imageTintList = ColorStateList.valueOf(themeColor)
        } else {
            setBackgroundResource(config.selectableItemBackgroundBorderless)
            ui.title.setTextColor(fgColor)
            ui.icon.imageTintList = ColorStateList.valueOf(fgColor)
        }
    }

}