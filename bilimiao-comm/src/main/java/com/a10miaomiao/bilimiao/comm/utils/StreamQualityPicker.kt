package com.a10miaomiao.bilimiao.comm.utils

/**
 * 选流档位：**按"用户请求的档"选**，不是按服务端协商值选。
 *
 * 背景（2026-09 用户实测"未登录拿不到 1080P"）：
 * - 服务端对未登录用户**恒回 `quality=64`**（请求 qn=80 也回 64），但 `dash.video[]` 里
 *   实际会给到 id=80 的流（HTTP playurl 带 `try_look=1` 时才给）；
 * - 以前直接用 `res.quality`（=64）去挑流 ⇒ "点 1080P 播 720P"；
 * - 规则改成：**请求档在候选里就用请求档，不在才明确回退到协商档**。
 *   ★**不许**退化成"跨档挑编码最好的那条" —— 那会把画质挑错（点 1080P 反而拿到 480P 更糟）。
 *
 * 这个对象**零 Android 依赖**，可直接 JVM 单测：`bilimiao-comm/src/test/.../StreamQualityPickerTest.kt`。
 */
object StreamQualityPicker {

    /** 为什么选了这一档（进诊断日志用，也让调用方能"说真话"） */
    enum class Reason {
        /** 请求档就在候选里 ⇒ 直接用 */
        REQUESTED,

        /** 请求档不在候选里 ⇒ 回退到服务端协商档 */
        FALLBACK_NEGOTIATED,

        /** 候选档为空（服务端没给 dash/streamList）⇒ 无可选，原样返回请求档，由调用方报错 */
        NO_CANDIDATE,
    }

    /**
     * @param quality 最终用哪一档
     * @param fellBack 是否发生了回退（请求档 ≠ 实际档时，提示语要说真话）
     * @param reason 判定原因
     */
    data class Pick(
        val quality: Int,
        val fellBack: Boolean,
        val reason: Reason,
    )

    /**
     * @param requestedQuality 用户请求的档（qn）
     * @param availableQualities 服务端实际给出的档位（可重复；`0`/负数视为无效档，直接忽略）
     * @param negotiatedQuality 服务端协商回来的档（playurl 响应里的 `quality`）
     */
    fun pick(
        requestedQuality: Int,
        availableQualities: List<Int>,
        negotiatedQuality: Int,
    ): Pick {
        val available = availableQualities.filter { it > 0 }.toSet()
        if (available.isEmpty()) {
            return Pick(requestedQuality, fellBack = false, reason = Reason.NO_CANDIDATE)
        }
        if (requestedQuality in available) {
            return Pick(requestedQuality, fellBack = false, reason = Reason.REQUESTED)
        }
        return Pick(negotiatedQuality, fellBack = true, reason = Reason.FALLBACK_NEGOTIATED)
    }
}
