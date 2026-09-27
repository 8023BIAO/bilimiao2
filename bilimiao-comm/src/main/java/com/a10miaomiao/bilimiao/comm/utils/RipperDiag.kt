package com.a10miaomiao.bilimiao.comm.utils

/**
 * **分段并发下载（原「线程撕裂者」）的诊断日志 —— 发布版已停用（2026-09-20）**。
 *
 * 原来会把节点登记/抢跑/掐连接/失败退避等写进
 * `/sdcard/Android/data/<包名>/files/ripper_diag.log`；正式版里实现已清空，
 * **不再创建、不再写入任何文件**（调用点保留，理由见 [PlayerDiag]）。
 */
object RipperDiag {

    /** 记一行诊断日志（发布版：不落盘、不输出） */
    @Suppress("UNUSED_PARAMETER")
    fun log(tag: String, msg: String) = Unit
}
