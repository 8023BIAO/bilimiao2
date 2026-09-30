package com.a10miaomiao.bilimiao.widget.player

import java.util.Locale

/**
 * 「空降助手」片段时间的纯函数工具：格式化 + 解析。
 *
 * 为什么单独一个文件、且**不依赖任何 Android SDK 类**：它要能在纯 JVM 单测
 * （`app/src/test/java/com/a10miaomiao/bilimiao/player/SponsorTimeTest.kt`）里直接跑，
 * 不需要 Robolectric / 真机。
 *
 * 精度对齐 PiliPlus 的 `DurationUtils.formatDuration`（`mm:ss.SSS`：它显示 `01:11.000`，
 * 我们以前只有 `01:11`）。**只对齐毫秒精度这一点**，布局不抄它。
 */
object SponsorTime {

    /**
     * 毫秒 → `mm:ss.SSS`；满 1 小时 → `hh:mm:ss.SSS`。
     *
     * - 负数（位置还没就绪时 `currentPosition` 可能给负）一律钳到 `00:00.000`；
     * - 毫秒**截断**不四舍五入：显示值不会大于真实值，用户照着显示值提交不会多切 1 毫秒；
     * - 固定 `Locale.US`：阿拉伯语等区域会把数字写成阿拉伯-印度数字（`٠١:١١`），
     *   那种串既贴不进服务端也认不回来。
     */
    fun format(ms: Long): String {
        val total = if (ms < 0L) 0L else ms
        val millis = total % 1000
        val totalSec = total / 1000
        val sec = totalSec % 60
        val totalMin = totalSec / 60
        val min = totalMin % 60
        val hour = totalMin / 60
        return if (hour > 0) {
            String.format(Locale.US, "%02d:%02d:%02d.%03d", hour, min, sec, millis)
        } else {
            String.format(Locale.US, "%02d:%02d.%03d", min, sec, millis)
        }
    }

    /**
     * 解析 `mm:ss` / `hh:mm:ss` / 纯秒数 → 秒；**解析失败或不是合法时间返回 null**。
     *
     * 校验要点（以前只挡住了"结束 <= 开始"，`-5` 和 `1:2:3:4` 都能溜进去）：
     *  - 最多 3 段（时:分:秒），每段都必须是数字；
     *  - 不允许负数、NaN、无穷大；
     *  - 分/秒必须在 0..59（纯秒数那种没有这个限制）；
     *  - 半角 `:` 与全角 `：` 都当分隔符（PiliPlus `DurationUtils` 用的就是 `[:：]`）。
     */
    fun parseSec(raw: String): Double? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return try {
            val sec = if (":" in text || "：" in text) {
                val parts = text.split(":", "：")
                if (parts.size > 3) return null
                if (parts.any { it.isBlank() || it.trim().toDoubleOrNull() == null }) return null
                val nums = parts.map { it.trim().toDouble() }
                if (nums.any { it < 0 || it.isNaN() || it.isInfinite() }) return null
                // 除最高位（小时）外，分和秒都必须在 0..59
                if (nums.drop(1).any { it >= 60 }) return null
                nums.fold(0.0) { acc, v -> acc * 60 + v }
            } else {
                val v = text.toDoubleOrNull() ?: return null
                if (v < 0 || v.isNaN() || v.isInfinite()) return null
                v
            }
            if (sec < 0 || sec.isNaN() || sec.isInfinite()) null else sec
        } catch (e: Exception) {
            null
        }
    }
}
