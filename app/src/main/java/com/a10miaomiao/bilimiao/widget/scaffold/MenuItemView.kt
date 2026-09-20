package com.a10miaomiao.bilimiao.widget.scaffold

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.config.config
import splitties.dimensions.dip
import splitties.views.dsl.core.*

open class MenuItemView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    val ui = ViewUi()
    var prop = MenuItemPropInfo()
        set(value) {
            field = value
            updateView()
        }

    init {
        gravity = Gravity.CENTER
        addView(ui.icon, lParams {
            // 图标尺寸的来龙去脉：原本 20dp → vc142 提到 24dp（M3 标准尺寸、更好点中）→
            // 用户实测觉得偏大（他系统字体调得大，内容区那些 16dp 小图标又没跟着长，落差明显），
            // 于是折中到 22dp（"小一丢丢"），并**不去动内容区的图标**（那要改 9 个文件）。
            // 想再调就是这一个数字：20 = 完全回到从前，24 = M3 标准。
            horizontalMargin = dip(8)
            height = dip(22)
            width =  dip(22)
        })
        addView(ui.root, lParams {
            height = wrapContent
            width = wrapContent
        })
    }

    private fun updateView () {
        if (visibility != prop.visibility) {
            visibility = prop.visibility
        }
        if (prop.iconResource == null && prop.iconDrawable == null && prop.iconFileName == null) {
            ui.icon.visibility = View.GONE
        } else if (prop.iconFileName != null) {
            val iconResource = context.resources.getIdentifier(prop.iconFileName, "drawable", context.packageName)
            ui.icon.visibility = View.VISIBLE
            ui.icon.setImageResource(iconResource)
        } else if (prop.iconDrawable != null) {
            ui.icon.visibility = View.VISIBLE
            ui.icon.setImageDrawable(prop.iconDrawable)
        } else if (prop.iconResource != null) {
            ui.icon.visibility = View.VISIBLE
            ui.icon.setImageResource(prop.iconResource!!)
        }
        if (prop.title == null) {
            ui.title.visibility = View.GONE
        } else {
            // 之前只设文本没恢复可见性：先进过 title 为空的页面后，菜单文字就再也不显示了
            ui.title.visibility = View.VISIBLE
            ui.title.text = prop.title
        }
        val subTitle = prop.subTitle
        if (subTitle == null) {
            ui.subTitle.visibility = View.GONE
        } else {
            ui.subTitle.visibility = View.VISIBLE
            ui.subTitle.text = if (orientation == HORIZONTAL) {
                subTitle
            } else {
                subTitle.replace("\n", " ")
            }
        }
        setContentDescription(prop.contentDescription)
        if (prop.tintColor != null) {
            ui.icon.imageTintList = ColorStateList.valueOf(prop.tintColor!!)
        } else {
            ui.icon.imageTintList = ColorStateList.valueOf(config.foregroundAlpha45Color)
        }
    }

    fun refreshTheme(fgColor: Int = config.foregroundAlpha45Color) {
        ui.title.setTextColor(fgColor)
        ui.subTitle.setTextColor(fgColor)
        if (prop.tintColor != null) {
            ui.icon.imageTintList = ColorStateList.valueOf(prop.tintColor!!)
        } else {
            ui.icon.imageTintList = ColorStateList.valueOf(fgColor)
        }
    }

    inner class ViewUi: Ui {
        override val ctx: Context get() = context

        val title = textView {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(config.foregroundAlpha45Color)
        }

        val subTitle = textView {
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(config.foregroundAlpha45Color)
        }

        override val root = verticalLayout {
            gravity = Gravity.CENTER

            addView(title, lParams {
                width = matchParent
                height = wrapContent
            })
            addView(subTitle, lParams {
                width = matchParent
                height = wrapContent
                topMargin = dip(2)
            })
        }


        val icon = imageView {

        }

    }

}