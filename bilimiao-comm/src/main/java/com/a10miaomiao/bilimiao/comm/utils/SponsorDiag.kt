package com.a10miaomiao.bilimiao.comm.utils

/**
 * **空降助手（BilibiliSponsorBlock）的诊断日志 —— 发布版已停用（2026-09-20）**。
 *
 * 原来会把 segments 拉取/解析/过滤/跳过等写进
 * `/sdcard/Android/data/<包名>/files/sponsor_diag.log`；正式版里实现已清空，
 * **不再创建、不再写入任何文件**（调用点保留，理由见 [PlayerDiag]）。
 */
object SponsorDiag {

    /** 记一行诊断日志（发布版：不落盘、不输出） */
    @Suppress("UNUSED_PARAMETER")
    fun log(tag: String, msg: String) = Unit
}
