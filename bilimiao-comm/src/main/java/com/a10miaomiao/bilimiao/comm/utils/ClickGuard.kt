package com.a10miaomiao.bilimiao.comm.utils

import android.os.SystemClock

/**
 * **防连点 / 防重入闸门**（Compose 侧与原生 View 侧通用）。
 *
 * 解决两类"手快"问题：
 *  1. **重复弹窗**：连点 N 次「公开昵称」这种要读网络/磁盘才出内容的入口，
 *     以前每点一次就起一个协程、各自弹一个弹窗 → N 个弹窗排队糊在屏幕上。
 *  2. **重复请求 / 重复跳转**：连点直接把同一个网络请求或页面跳转发 N 次。
 *
 * 两种闸门，按场景选：
 *  - [allow]：**时间窗节流**。同一 key 在 [windowMs] 内只放行一次。
 *    适合"点一下就该发生一次的瞬时动作"（跳转、打开浏览器、发动一次请求）。
 *    ⚠️ 不要用在"连续点本来就合法"的地方（连续切开关、连续调音量、
 *    列表里点不同项、播放器手势），那会把手速正常的用户挡掉。
 *  - [enter] / [leave]：**独占锁**。适合"有明确开始与结束的长流程"
 *    （打开一个弹窗、进入一个选择流程）——直到流程结束（弹窗 dismiss）才放行下一次。
 *    [enter] 成功必须保证有对应的 [leave]（通常挂在 Dialog 的 onDismiss 上）。
 *
 * 实现是**进程内 map**：key 用字符串（建议 `"功能:对象"` 形式），
 * 只用于 UI 交互节流，不需要跨进程、也不需要持久化。
 */
object ClickGuard {

    /** 默认节流窗口：600ms 足够挡住"手抖连点"，又不会让人感觉迟钝 */
    const val DEFAULT_WINDOW_MS = 600L

    private val lastAllowedAt = HashMap<String, Long>()
    private val busyKeys = HashSet<String>()

    /**
     * 时间窗节流：同一 [key] 在 [windowMs] 毫秒内只放行一次。
     * @return true = 这次点击放行；false = 太密，忽略这次
     */
    @JvmOverloads
    fun allow(key: String, windowMs: Long = DEFAULT_WINDOW_MS): Boolean {
        val now = SystemClock.uptimeMillis()
        synchronized(lastAllowedAt) {
            val last = lastAllowedAt[key]
            if (last != null && now - last < windowMs) return false
            lastAllowedAt[key] = now
            return true
        }
    }

    /**
     * 独占闸门：拿不到说明同名流程正在进行中。
     * 拿到后**必须**在流程结束时 [leave]（忘了 leave 会导致这个入口永久点不动）。
     */
    fun enter(key: String): Boolean = synchronized(busyKeys) { busyKeys.add(key) }

    /** 释放独占闸门；重复调用是无害的。 */
    fun leave(key: String) {
        synchronized(busyKeys) { busyKeys.remove(key) }
    }

    /** 同名流程是否进行中（用于 UI 判断，一般不用直接调） */
    fun isBusy(key: String): Boolean = synchronized(busyKeys) { key in busyKeys }
}
