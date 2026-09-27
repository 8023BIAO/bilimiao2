package com.a10miaomiao.bilimiao.comm.utils

/**
 * **播放器诊断日志 —— 发布版已停用（2026-09-20）**。
 *
 * 背景：2026-09-19 为了定位"番剧 DASH 播不出来 / 内存曲线 / 清晰度协商"等问题，
 * 这里会把每次开播的取流路径、LoadControl 参数、堆内存等写进
 * `/sdcard/Android/data/<包名>/files/player_diag.log`（超过 400KB 自动清空）。
 *
 * 正式版发布前按用户要求**整体停用**：函数签名保留、实现清空，
 * **不再创建、不再写入任何文件**。
 *
 * 为什么保留调用点（而不是把 55 处调用一起删掉）：
 *  1. 线上再遇到同类问题，把这里的实现恢复即可，不用再把埋点重新插一遍；
 *  2. 这两个方法现在是空方法、无副作用，R8 会把整段调用优化掉，不占体积、不影响性能。
 */
object PlayerDiag {

    /** 记一行诊断日志（发布版：不落盘、不输出） */
    @Suppress("UNUSED_PARAMETER")
    fun log(tag: String, msg: String) = Unit

    /** 记一行堆内存（发布版：不落盘、不输出） */
    @Suppress("UNUSED_PARAMETER")
    fun memory(tag: String) = Unit
}
