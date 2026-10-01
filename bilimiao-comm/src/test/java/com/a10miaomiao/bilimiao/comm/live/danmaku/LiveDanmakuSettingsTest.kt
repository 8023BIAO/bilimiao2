package com.a10miaomiao.bilimiao.comm.live.danmaku

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「竖屏列表字号」（task-6）在 [LiveDanmakuSettings.from] 里的映射单测。
 *
 * 覆盖三件事（每条都对着一个**能失败**的变异）：
 * 1. 默认值 = **13sp**（键没落盘 / 快照没就绪两条路都必须是 13，且它**不等于**滚动弹幕默认的 15sp）；
 * 2. 越界 clamp：低于下限 → 10sp、高于上限 → 30sp；
 * 3. 用户改过的值能读到（22sp 就是 22sp），且**不影响**滚动弹幕字号（两套各调各的）。
 * 另外把"行距等比"的基准钉住：倍率 = 17/13 ⇒ 默认 13sp 时行距正好是改前的 17sp（逐像素一致）。
 *
 * ★纯 JVM：只构造 `LiveDanmakuSettings` 与 `SettingPreferences.Live.Values` 两个值对象，
 *   不碰 Context / DataStore / Compose。
 */
class LiveDanmakuSettingsTest {

    /** 造一份只改了「竖屏列表字号」的直播设置快照 */
    private fun settingsWith(chatFontSize: Float): LiveDanmakuSettings =
        LiveDanmakuSettings.from(
            SettingPreferences.Live.Values(danmakuChatFontSize = chatFontSize),
        )

    @Test
    fun chatFontSize_default_is13_onBothPaths() {
        // ① 快照还没就绪（进程刚起，from(null)）：必须走默认值 13
        assertEquals(13f, LiveDanmakuSettings.from(null).chatFontSizeSp, 0f)
        // ② 快照就绪但键没落盘（Values 的字段默认值）：也必须是 13
        assertEquals(13f, LiveDanmakuSettings.from(SettingPreferences.Live.Values()).chatFontSizeSp, 0f)
        // ③ "默认值真值"只有 SettingConstants 一处，LiveDanmakuSettings 只是引用它
        assertEquals(
            SettingConstants.LIVE_DANMAKU_CHAT_FONT_SIZE_DEFAULT,
            LiveDanmakuSettings.CHAT_FONT_SIZE_SP_DEFAULT,
            0f,
        )
        // ④ 它**不是**滚动弹幕那个默认值（15sp）：两套字号从一开始就是分开的
        assertEquals(15f, LiveDanmakuSettings.from(null).fontSizeSp, 0f)
    }

    @Test
    fun chatFontSize_belowMin_clampedTo10() {
        // 变异：把 coerceIn 删掉 / 下限改成 8 → 这条必须失败
        assertEquals(10f, settingsWith(4f).chatFontSizeSp, 0f)
    }

    @Test
    fun chatFontSize_aboveMax_clampedTo30() {
        // 变异：把上限改成 48（照抄滚动弹幕那套）→ 这条必须失败
        assertEquals(30f, settingsWith(99f).chatFontSizeSp, 0f)
    }

    @Test
    fun chatFontSize_customValue_isRead_andDoesNotTouchRollingFontSize() {
        val s = settingsWith(22f)
        assertEquals(22f, s.chatFontSizeSp, 0f)
        // 只改竖屏列表字号 ⇒ 滚动弹幕字号仍是它自己的默认值（两套互不影响）
        assertEquals(15f, s.fontSizeSp, 0f)
    }

    @Test
    fun chatLineHeight_factor_is17over13_soDefaultRowIsPixelIdentical() {
        assertEquals(17f / 13f, LiveDanmakuSettings.CHAT_LINE_HEIGHT_FACTOR, 1e-6f)
        // 默认 13sp 时行距 = 13 × 17/13 = 17sp = 改前那两行写死的值（13.sp / 17.sp）
        assertEquals(17f, 13f * LiveDanmakuSettings.CHAT_LINE_HEIGHT_FACTOR, 1e-4f)
    }

    // ── 「弹幕纯白」（2026-10-01 新增，默认开）─────────────────────────────

    /**
     * 三条断言各对着一个**能失败**的变异：
     * ①/② 默认必须是 **true**（变异：把默认值写成 false、或忘了映射 → 失败）；
     * ③ 用户**关掉**（落盘 false）之后必须读到 **false**（变异：`from()` 里恒 true、
     *   或读取写成 `prefs.get(...) == true` 之外的"真值判空"写法把 false 吃掉 → 失败）。
     */
    @Test
    fun whiteOnly_defaultIsTrue_andStoredFalse_readsFalse() {
        // ① 快照还没就绪（进程刚起，from(null)）
        assertTrue(LiveDanmakuSettings.from(null).whiteOnly)
        // ② 快照就绪但键没落盘（Values 的字段默认值）
        assertTrue(LiveDanmakuSettings.from(SettingPreferences.Live.Values()).whiteOnly)
        // ②b 默认值真值只有 SettingConstants 一处，且必须是 true
        assertEquals(true, SettingConstants.LIVE_DANMAKU_WHITE_ONLY_DEFAULT)
        // ③ 用户关掉之后读到的就是 false（不是"默认值兜底"把它盖回去）
        val off = LiveDanmakuSettings.from(
            SettingPreferences.Live.Values(danmakuWhiteOnly = false),
        )
        assertFalse(off.whiteOnly)
        // ④ 这个开关**不影响**别的字段（尤其不透明度：纯白不改透明度）
        assertEquals(1f, off.opacity, 0f)
        assertEquals(1f, off.chatOpacity, 0f)
    }

    /**
     * ★走**真正的读取口** `SettingPreferences.Live.of(Preferences)`（不是直接构造 `Values`）。
     *
     * 为什么必须有这一条：`of()` 里布尔是 `prefs?.get(键) ?: 默认值`；
     * 一旦有人"顺手"改成 `prefs?.get(键) == true`，**落盘的 false 会被吃掉** ——
     * 现象是"设置页开关显示关、浮层却按开渲染"。只构造 `Values` 的断言**抓不到**这个变异。
     */
    @Test
    fun whiteOnly_of_readsStoredFalse_andStoredTrue() {
        // ① 落盘 false（用户关掉）⇒ 必须读到 false（变异：`== true` / 恒 true ⇒ 这条失败）
        val off = LiveDanmakuSettings.from(
            SettingPreferences.Live.of(
                mutablePreferencesOf(SettingPreferences.LiveDanmakuWhiteOnly to false),
            ),
        )
        assertFalse(off.whiteOnly)
        // ② 落盘 true（用户明确打开）⇒ true
        val on = LiveDanmakuSettings.from(
            SettingPreferences.Live.of(
                mutablePreferencesOf(SettingPreferences.LiveDanmakuWhiteOnly to true),
            ),
        )
        assertTrue(on.whiteOnly)
        // ③ 对照组：空快照（键不存在）⇒ 走默认值 true
        assertTrue(LiveDanmakuSettings.from(SettingPreferences.Live.of(mutablePreferencesOf())).whiteOnly)
    }

    @Test
    fun chatFontSizeText_isSpSuffixedInteger() {
        assertEquals("13sp", LiveDanmakuSettings.chatFontSizeText(13f))
        assertEquals("22sp", LiveDanmakuSettings.chatFontSizeText(22f))
    }
}
