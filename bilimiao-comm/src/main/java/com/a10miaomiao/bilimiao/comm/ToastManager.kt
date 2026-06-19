package com.a10miaomiao.bilimiao.comm

import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 全局提示管理器，使用 Android 原生 Toast
 * 自动跟随系统深浅主题，带原生圆角，最上层不会被遮挡
 */
object ToastManager {

    private var toast: Toast? = null

    fun show(text: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val app = BilimiaoCommApp.commApp.app
            Handler(app.mainLooper).post {
                showToast(text)
            }
            return
        }
        showToast(text)
    }

    private fun showToast(text: String) {
        val app = BilimiaoCommApp.commApp.app
        if (toast == null) {
            toast = Toast.makeText(app, text, Toast.LENGTH_SHORT)
        } else {
            toast?.setText(text)
        }
        toast?.show()
    }
}

/**
 * 全局 toast 函数
 */
fun toast(text: String) {
    ToastManager.show(text)
}
