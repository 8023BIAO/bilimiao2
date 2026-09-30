package com.a10miaomiao.bilimiao.comm.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StreamQualityPicker] 的单元测试。
 *
 * 覆盖这批用例（对应任务验收里的"请求档可用 / 不可用回退 / 列表为空 / 请求档高于最高档 / 重复档位"）：
 * 1. 未登录实测形态：请求 1080P(80)、服务端协商回 720P(64)、dash 里有 80 ⇒ **必须选 80**（本次修的 bug）；
 * 2. 请求档不在候选里 ⇒ 回退协商档，且 `fellBack = true`（提示语据此说真话）；
 * 3. 候选为空 ⇒ NO_CANDIDATE，不瞎猜；
 * 4. 请求档高于最高档 ⇒ 回退协商档；
 * 5. 重复档位不影响判定；0/负数档位被忽略。
 *
 * ★变异测试（人工做一次，见任务报告）：把 pick 的主判据从"请求档在候选里"改成"协商档在候选里"，
 *   用例 1 必须失败 —— 那是"点 1080P 播 720P"的老行为。
 */
class StreamQualityPickerTest {

    /** 未登录 + HTTP 带 try_look 的真实形态：能选到 80 */
    @Test
    fun requestedQualityAvailable_isPicked_notNegotiated() {
        val pick = StreamQualityPicker.pick(
            requestedQuality = 80,
            availableQualities = listOf(80, 64, 32, 16), // dash.video[].id
            negotiatedQuality = 64,                      // 服务端对未登录恒回 64
        )
        assertEquals(80, pick.quality)
        assertFalse(pick.fellBack)
        assertEquals(StreamQualityPicker.Reason.REQUESTED, pick.reason)
    }

    /** 请求档不在候选里（未登录且拿不到 80）⇒ 明确回退到协商档 */
    @Test
    fun requestedQualityMissing_fallsBackToNegotiated() {
        val pick = StreamQualityPicker.pick(
            requestedQuality = 80,
            availableQualities = listOf(64, 32, 16),
            negotiatedQuality = 64,
        )
        assertEquals(64, pick.quality)
        assertTrue(pick.fellBack)
        assertEquals(StreamQualityPicker.Reason.FALLBACK_NEGOTIATED, pick.reason)
    }

    /** 候选为空：不猜、不崩，原样返回请求档，由调用方报错（fellBack=false，因为压根没得退） */
    @Test
    fun emptyCandidates_returnsRequestedWithNoCandidateReason() {
        val pick = StreamQualityPicker.pick(
            requestedQuality = 80,
            availableQualities = emptyList(),
            negotiatedQuality = 64,
        )
        assertEquals(80, pick.quality)
        assertFalse(pick.fellBack)
        assertEquals(StreamQualityPicker.Reason.NO_CANDIDATE, pick.reason)
    }

    /** 请求档高于最高档（如请求 4K=120，服务端只给到 80）⇒ 回退协商档 */
    @Test
    fun requestedQualityAboveMax_fallsBack() {
        val pick = StreamQualityPicker.pick(
            requestedQuality = 120,
            availableQualities = listOf(80, 64, 32, 16),
            negotiatedQuality = 64,
        )
        assertEquals(64, pick.quality)
        assertTrue(pick.fellBack)
    }

    /** 重复档位不影响判定（服务端同一档可能给多条不同编码的流） */
    @Test
    fun duplicatedQualities_stillMatch() {
        val pick = StreamQualityPicker.pick(
            requestedQuality = 64,
            availableQualities = listOf(64, 64, 64, 32),
            negotiatedQuality = 32,
        )
        assertEquals(64, pick.quality)
        assertFalse(pick.fellBack)
        assertEquals(StreamQualityPicker.Reason.REQUESTED, pick.reason)
    }

    /** 0/负数档位视为无效：只有它们 ⇒ 等价于空候选 */
    @Test
    fun invalidQualitiesAreIgnored() {
        val onlyInvalid = StreamQualityPicker.pick(
            requestedQuality = 80,
            availableQualities = listOf(0, -1),
            negotiatedQuality = 64,
        )
        assertEquals(StreamQualityPicker.Reason.NO_CANDIDATE, onlyInvalid.reason)

        val withInvalidMixedIn = StreamQualityPicker.pick(
            requestedQuality = 32,
            availableQualities = listOf(0, 32, -1),
            negotiatedQuality = 16,
        )
        assertEquals(32, withInvalidMixedIn.quality)
        assertFalse(withInvalidMixedIn.fellBack)
    }

    /** 只请求 480P(32) 时也按请求档选，不该被协商档"抬"上去 */
    @Test
    fun lowerRequestedQuality_isNotRaisedToNegotiated() {
        val pick = StreamQualityPicker.pick(
            requestedQuality = 32,
            availableQualities = listOf(80, 64, 32, 16),
            negotiatedQuality = 64,
        )
        assertEquals(32, pick.quality)
        assertFalse(pick.fellBack)
    }
}
