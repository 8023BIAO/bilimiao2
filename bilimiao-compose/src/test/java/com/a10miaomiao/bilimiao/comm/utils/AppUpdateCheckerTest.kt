package com.a10miaomiao.bilimiao.comm.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AppUpdateChecker` 纯函数单测。
 *
 * 覆盖：tag 解析正反例（含非法日历日期）/ 乱序取最大 / 三态 / 状态码分类（限流只在 403+0）/
 * 附件缺失 / Atom 兜底（**tag 取 `<id>` 尾段**、空 feed）/ 超长正文 / pickLatest 与 noReleaseReason 一致性。
 *
 * 运行：`gradlew :bilimiao-compose:testDebugUnitTest`（0 步门里会跑）。
 * 断言全部是"能失败"的具体值比较（没有 assertTrue(true) 这类恒真断言）。
 */
class AppUpdateCheckerTest {

    /** date 默认按 tag 解析，但允许显式传：比对类用例不依赖解析器（避免"自证"） */
    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        notes: String = "",
        apkUrl: String? = null,
        pageUrl: String = "https://github.com/8023BIAO/bilimiao2/releases/tag/$tag",
        date: Int? = AppUpdateChecker.parseReleaseDate(tag),
    ) = AppUpdateChecker.AppRelease(
        tag = tag,
        date = date,
        draft = draft,
        prerelease = prerelease,
        notes = notes,
        apkUrl = apkUrl,
        pageUrl = pageUrl,
    )

    // ── ① tag → 日期：正例 ──

    @Test
    fun parseReleaseDate_withVPrefix() {
        assertEquals(20261001, AppUpdateChecker.parseReleaseDate("v2026.10.01"))
    }

    @Test
    fun parseReleaseDate_withoutVPrefix() {
        assertEquals(20261001, AppUpdateChecker.parseReleaseDate("2026.10.01"))
    }

    @Test
    fun parseReleaseDate_trimsSurroundingWhitespace() {
        assertEquals(20261001, AppUpdateChecker.parseReleaseDate("  v2026.10.01 \n"))
    }

    @Test
    fun parseReleaseDate_singleDigitMonthAndDay() {
        // 约定是零填充的 10.01；这里放宽到 1~2 位，避免将来手滑发 v2026.1.1 被整条忽略
        assertEquals(20260101, AppUpdateChecker.parseReleaseDate("v2026.1.1"))
    }

    @Test
    fun parseReleaseDate_dashSuffix_takesDatePart() {
        assertEquals(20261001, AppUpdateChecker.parseReleaseDate("v2026.10.01-beta"))
    }

    @Test
    fun parseReleaseDate_plusSuffix_takesDatePart() {
        assertEquals(20261001, AppUpdateChecker.parseReleaseDate("v2026.10.01+1"))
    }

    @Test
    fun parseReleaseDate_emptySuffix_stillDate() {
        assertEquals(20261001, AppUpdateChecker.parseReleaseDate("v2026.10.01-"))
    }

    @Test
    fun parseReleaseDate_leapDayInLeapYear_isAccepted() {
        assertEquals(20240229, AppUpdateChecker.parseReleaseDate("v2024.02.29"))
    }

    // ── ② tag → 日期：反例（必须 null，绝不猜） ──

    @Test
    fun parseReleaseDate_versionCode_isNull() {
        assertNull(AppUpdateChecker.parseReleaseDate("vc210"))
    }

    @Test
    fun parseReleaseDate_twoComponents_isNull() {
        assertNull(AppUpdateChecker.parseReleaseDate("v2026.10"))
    }

    @Test
    fun parseReleaseDate_releaseDashOne_isNull() {
        assertNull(AppUpdateChecker.parseReleaseDate("release-1"))
    }

    @Test
    fun parseReleaseDate_fourComponents_isNull() {
        assertNull(AppUpdateChecker.parseReleaseDate("v2026.10.01.1"))
    }

    @Test
    fun parseReleaseDate_monthOutOfRange_isNull() {
        assertNull(AppUpdateChecker.parseReleaseDate("v2026.13.01"))
    }

    @Test
    fun parseReleaseDate_dayOutOfRange_isNull() {
        assertNull(AppUpdateChecker.parseReleaseDate("v2026.10.32"))
    }

    @Test
    fun parseReleaseDate_empty_isNull() {
        assertNull(AppUpdateChecker.parseReleaseDate(""))
    }

    @Test
    fun parseReleaseDate_zeroMonth_isNull() {
        assertNull(AppUpdateChecker.parseReleaseDate("v2026.00.10"))
    }

    @Test
    fun parseReleaseDate_zeroDay_isNull() {
        assertNull(AppUpdateChecker.parseReleaseDate("v2026.10.00"))
    }

    @Test
    fun parseReleaseDate_february31_isNull() {
        assertNull(AppUpdateChecker.parseReleaseDate("v2026.02.31"))
    }

    @Test
    fun parseReleaseDate_april31_isNull() {
        assertNull(AppUpdateChecker.parseReleaseDate("v2026.04.31"))
    }

    @Test
    fun parseReleaseDate_november31_isNull() {
        assertNull(AppUpdateChecker.parseReleaseDate("v2026.11.31"))
    }

    @Test
    fun parseReleaseDate_leapDayInCommonYear_isNull() {
        assertNull(AppUpdateChecker.parseReleaseDate("v2026.02.29"))
    }

    // ── ③ pickLatest：乱序 / draft / prerelease / 空 / 非日期 ──

    @Test
    fun pickLatest_unorderedList_takesMaxDate() {
        val list = listOf(
            release("v2026.09.20", date = 20260920),
            release("v2026.10.01", date = 20261001),
            release("v2026.09.30", date = 20260930),
        )
        assertEquals("v2026.10.01", AppUpdateChecker.pickLatest(list)?.tag)
    }

    @Test
    fun pickLatest_newerDraft_isSkipped() {
        val list = listOf(
            release("v2026.10.02", draft = true, date = 20261002),
            release("v2026.10.01", date = 20261001),
        )
        assertEquals("v2026.10.01", AppUpdateChecker.pickLatest(list)?.tag)
    }

    @Test
    fun pickLatest_allDrafts_isNull() {
        val list = listOf(
            release("v2026.10.02", draft = true, date = 20261002),
            release("v2026.10.01", draft = true, date = 20261001),
        )
        assertNull(AppUpdateChecker.pickLatest(list))
    }

    @Test
    fun pickLatest_emptyList_isNull() {
        assertNull(AppUpdateChecker.pickLatest(emptyList()))
    }

    @Test
    fun pickLatest_prereleaseIsKept() {
        val list = listOf(
            release("v2026.10.01", prerelease = true, date = 20261001),
            release("v2026.09.20", date = 20260920),
        )
        val latest = AppUpdateChecker.pickLatest(list)
        assertEquals("v2026.10.01", latest?.tag)
        assertTrue(latest!!.prerelease)
    }

    @Test
    fun pickLatest_nonDateTag_isIgnored() {
        val list = listOf(release("nightly", date = null), release("v2026.09.20", date = 20260920))
        assertEquals("v2026.09.20", AppUpdateChecker.pickLatest(list)?.tag)
    }

    // ── ④ compare：三态 + "远端日期不可解析"必须是失败 ──

    @Test
    fun compare_sameDate_isUpToDate() {
        val outcome = AppUpdateChecker.compare("v2026.10.01", 20261001, release("v2026.10.01", date = 20261001))
        assertTrue(outcome is AppUpdateChecker.Outcome.Done)
        val result = (outcome as AppUpdateChecker.Outcome.Done).result
        assertTrue(result is AppUpdateChecker.UpdateResult.UpToDate)
        assertEquals("v2026.10.01", (result as AppUpdateChecker.UpdateResult.UpToDate).currentTag)
    }

    @Test
    fun compare_newerRemote_isAvailableAndCarriesFields() {
        val latest = release(
            tag = "v2026.10.02",
            notes = "修了几个毛病",
            apkUrl = "https://github.com/8023BIAO/bilimiao2/releases/download/v2026.10.02/bilimiao-v2026.10.02.apk",
            date = 20261002,
        )
        val outcome = AppUpdateChecker.compare("v2026.10.01", 20261001, latest)
        assertTrue(outcome is AppUpdateChecker.Outcome.Done)
        val result = (outcome as AppUpdateChecker.Outcome.Done).result
        assertTrue(result is AppUpdateChecker.UpdateResult.Available)
        val available = result as AppUpdateChecker.UpdateResult.Available
        assertEquals("v2026.10.02", available.newTag)
        assertEquals(20261002, available.newDate)
        assertEquals("修了几个毛病", available.notes)
        assertTrue(available.apkUrl!!.endsWith(".apk"))
        assertEquals("v2026.10.01", available.currentTag)
    }

    @Test
    fun compare_olderRemote_isRemoteOlder() {
        val outcome = AppUpdateChecker.compare("v2026.10.05", 20261005, release("v2026.10.01", date = 20261001))
        assertTrue(outcome is AppUpdateChecker.Outcome.Done)
        val result = (outcome as AppUpdateChecker.Outcome.Done).result
        assertTrue(result is AppUpdateChecker.UpdateResult.RemoteOlder)
        val older = result as AppUpdateChecker.UpdateResult.RemoteOlder
        assertEquals("v2026.10.05", older.currentTag)
        assertEquals("v2026.10.01", older.latestTag)
    }

    @Test
    fun compare_unknownCurrentDate_isAvailableNotUpToDate() {
        // 本机 versionName 不符合约定（开发版）时：宁可多提示一次，也不谎称"已是最新"
        val outcome = AppUpdateChecker.compare("dev-build", null, release("v2026.10.01", date = 20261001))
        assertTrue(outcome is AppUpdateChecker.Outcome.Done)
        assertTrue((outcome as AppUpdateChecker.Outcome.Done).result is AppUpdateChecker.UpdateResult.Available)
    }

    @Test
    fun compare_latestDateIsNull_isFailedUnrecognizedNotUpToDate() {
        // ★复核打回项：以前这里返回 UpToDate（等于谎称"已是最新"），现在必须是明确失败
        val outcome = AppUpdateChecker.compare("v2026.10.01", 20261001, release("nightly", date = null))
        assertTrue(outcome is AppUpdateChecker.Outcome.Failed)
        assertEquals(
            AppUpdateChecker.FailReason.UNRECOGNIZED_TAGS,
            (outcome as AppUpdateChecker.Outcome.Failed).reason,
        )
    }

    // ── ⑤ 状态码分类：限流只认 403+remaining=0；200+0 是有效响应 ──

    @Test
    fun isRateLimited_403WithZeroRemaining_isTrue() {
        assertTrue(AppUpdateChecker.isRateLimited(403, "0"))
    }

    @Test
    fun isRateLimited_403WithoutHeader_isFalse() {
        // 403 但拿不到 remaining：不能确认是限流 ⇒ 普通失败（复核要求）
        assertTrue(!AppUpdateChecker.isRateLimited(403, null))
    }

    @Test
    fun isRateLimited_403WithRemainingLeft_isFalse() {
        assertTrue(!AppUpdateChecker.isRateLimited(403, "17"))
    }

    @Test
    fun isRateLimited_200WithZeroRemaining_isFalse() {
        // ★复核实测：配额用尽那一次是 200 + remaining=0，它是**有效响应**，不能被判成限流
        assertTrue(!AppUpdateChecker.isRateLimited(200, "0"))
    }

    @Test
    fun isRateLimited_404WithZeroRemaining_isFalse() {
        assertTrue(!AppUpdateChecker.isRateLimited(404, "0"))
    }

    @Test
    fun isRateLimited_500WithZeroRemaining_isFalse() {
        assertTrue(!AppUpdateChecker.isRateLimited(500, "0"))
    }

    @Test
    fun classifyStatus_okEvenWhenRemainingZero() {
        assertEquals(AppUpdateChecker.StatusClass.OK, AppUpdateChecker.classifyStatus(200, "0"))
        assertEquals(AppUpdateChecker.StatusClass.OK, AppUpdateChecker.classifyStatus(200, "49"))
    }

    @Test
    fun classifyStatus_rateLimitedOnlyFor403WithZero() {
        assertEquals(AppUpdateChecker.StatusClass.RATE_LIMITED, AppUpdateChecker.classifyStatus(403, "0"))
    }

    @Test
    fun classifyStatus_otherFailuresAreRequestFailed() {
        assertEquals(AppUpdateChecker.StatusClass.REQUEST_FAILED, AppUpdateChecker.classifyStatus(403, null))
        assertEquals(AppUpdateChecker.StatusClass.REQUEST_FAILED, AppUpdateChecker.classifyStatus(403, "17"))
        assertEquals(AppUpdateChecker.StatusClass.REQUEST_FAILED, AppUpdateChecker.classifyStatus(404, "0"))
        assertEquals(AppUpdateChecker.StatusClass.REQUEST_FAILED, AppUpdateChecker.classifyStatus(500, "0"))
    }

    // ── ⑥ parseReleasesJson：字段映射 / 附件优先 / 坏 JSON / 超长 ──

    @Test
    fun parseReleasesJson_mapsFieldsAndPrefersBilimiaoApk() {
        val body = """
            [
              {
                "tag_name": "v2026.10.02",
                "body": "  ## 更新\n- 修 bug  ",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/8023BIAO/bilimiao2/releases/tag/v2026.10.02",
                "assets": [
                  {"name": "other.apk", "browser_download_url": "https://example.com/other.apk"},
                  {"name": "bilimiao-v2026.10.02.apk", "browser_download_url": "https://example.com/bilimiao.apk"}
                ],
                "id": 12345
              }
            ]
        """.trimIndent()
        val list = AppUpdateChecker.parseReleasesJson(body)
        assertNotNull(list)
        assertEquals(1, list!!.size)
        val item = list.first()
        assertEquals("v2026.10.02", item.tag)
        assertEquals(20261002, item.date)
        assertEquals("## 更新\n- 修 bug", item.notes)
        assertEquals("https://example.com/bilimiao.apk", item.apkUrl)
        assertTrue(item.pageUrl.endsWith("/releases/tag/v2026.10.02"))
    }

    @Test
    fun parseReleasesJson_noApkAsset_apkUrlIsNull() {
        val body = """
            [{"tag_name":"v2026.10.02","html_url":"https://github.com/8023BIAO/bilimiao2/releases/tag/v2026.10.02","assets":[]}]
        """.trimIndent()
        val item = AppUpdateChecker.parseReleasesJson(body)!!.first()
        assertNull(item.apkUrl)
        assertEquals("https://github.com/8023BIAO/bilimiao2/releases/tag/v2026.10.02", item.pageUrl)
    }

    @Test
    fun parseReleasesJson_invalidJson_isNull() {
        assertNull(AppUpdateChecker.parseReleasesJson("<html>403 rate limited</html>"))
    }

    @Test
    fun parseReleasesJson_emptyArray_isEmptyList() {
        assertEquals(0, AppUpdateChecker.parseReleasesJson("[]")!!.size)
    }

    @Test
    fun parseReleasesJson_hugeNotes_doesNotCrash() {
        val notes = "更".repeat(50_000)
        val body = """[{"tag_name":"v2026.10.02","body":"$notes"}]"""
        val item = AppUpdateChecker.parseReleasesJson(body)!!.first()
        assertEquals(50_000, item.notes.length)
    }

    // ── ⑦ Atom 兜底：tag 取 <id> 尾段（不是 <title>）、空 feed、反转义 ──

    /** 真实形态：`<id>` 尾段才是 tag；`<title>` 是 Release 名字（这里故意写成含"另一个日期"的名字） */
    private val atomWithNames = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Release notes from bilimiao2</title>
          <entry>
            <id>tag:github.com,2008:Repository/123456/v2026.09.20</id>
            <title>九月的小版本</title>
            <link rel="alternate" type="text/html" href="https://github.com/8023BIAO/bilimiao2/releases/tag/v2026.09.20"/>
            <content type="html">旧版本</content>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/123456/v2026.10.02</id>
            <title>发布说明：紧接 v2026.09.01 之后</title>
            <link rel="alternate" type="text/html" href="https://github.com/8023BIAO/bilimiao2/releases/tag/v2026.10.02"/>
            <content type="html">&lt;p&gt;修了 &amp;lt; 转义 &lt;br/&gt;第二行&lt;/p&gt;</content>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun parseAtomReleases_takesTagFromIdNotTitle() {
        val list = AppUpdateChecker.parseAtomReleases(atomWithNames)
        assertNotNull(list)
        assertEquals(2, list!!.size)
        val latest = AppUpdateChecker.pickLatest(list)
        // 若把 <title> 当 tag：第二条会算成 20260901，最大反而变成第一条的 v2026.09.20
        assertEquals("v2026.10.02", latest?.tag)
        assertEquals(20261002, latest?.date)
    }

    @Test
    fun parseAtomReleases_titleIsOnlyFallbackWhenIdMissing() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>v2026.10.02</title>
                <link rel="alternate" type="text/html" href="https://github.com/8023BIAO/bilimiao2/releases/tag/v2026.10.02"/>
                <content type="html">没有 id 的老格式</content>
              </entry>
            </feed>
        """.trimIndent()
        val latest = AppUpdateChecker.pickLatest(AppUpdateChecker.parseAtomReleases(xml)!!)
        assertEquals("v2026.10.02", latest?.tag)
    }

    @Test
    fun parseAtomReleases_emptyFeed_isEmptyListNotUnparsable() {
        // ★复核打回项：仓库 0 个 Release 的真实 feed 没有 <entry> ⇒ 必须是"没发布"，不是"看不懂"
        val emptyFeed = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Release notes from bilimiao2</title>
              <updated>2026-10-01T11:31:41Z</updated>
            </feed>
        """.trimIndent()
        val list = AppUpdateChecker.parseAtomReleases(emptyFeed)
        assertNotNull(list)
        assertEquals(0, list!!.size)
    }

    @Test
    fun parseAtomReleases_entriesWithoutAnyTag_isNull() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <link rel="alternate" type="text/html" href="https://github.com/8023BIAO/bilimiao2/releases"/>
                <content type="html">既没有 id 也没有 title</content>
              </entry>
            </feed>
        """.trimIndent()
        assertNull(AppUpdateChecker.parseAtomReleases(xml))
    }

    @Test
    fun parseAtomReleases_picksMaxDateRegardlessOfOrder() {
        val list = AppUpdateChecker.parseAtomReleases(atomWithNames)
        assertEquals("v2026.10.02", AppUpdateChecker.pickLatest(list!!)?.tag)
    }

    @Test
    fun parseAtomReleases_stripsHtmlAndUnescapes() {
        val list = AppUpdateChecker.parseAtomReleases(atomWithNames)!!
        val latest = AppUpdateChecker.pickLatest(list)!!
        assertEquals("修了 &lt; 转义\n第二行", latest.notes)
    }

    @Test
    fun parseAtomReleases_keepsReleasePageUrlAndNoApk() {
        val latest = AppUpdateChecker.pickLatest(AppUpdateChecker.parseAtomReleases(atomWithNames)!!)!!
        assertEquals("https://github.com/8023BIAO/bilimiao2/releases/tag/v2026.10.02", latest.pageUrl)
        assertNull(latest.apkUrl)
    }

    // ── ⑧ noReleaseReason：认不出 vs 没发布（含回归） ──

    @Test
    fun noReleaseReason_allDrafts_isNoReleases() {
        val list = listOf(
            release("v2026.10.02", draft = true, date = 20261002),
            release("v2026.10.01", draft = true, date = 20261001),
        )
        assertEquals(AppUpdateChecker.FailReason.NO_RELEASES, AppUpdateChecker.noReleaseReason(list))
    }

    @Test
    fun noReleaseReason_emptyList_isNoReleases() {
        assertEquals(AppUpdateChecker.FailReason.NO_RELEASES, AppUpdateChecker.noReleaseReason(emptyList()))
    }

    @Test
    fun noReleaseReason_allTagsUnrecognized_isUnrecognizedTags() {
        val list = listOf(release("vc210", date = null), release("v2026.10", date = null), release("release-1", date = null))
        assertEquals(AppUpdateChecker.FailReason.UNRECOGNIZED_TAGS, AppUpdateChecker.noReleaseReason(list))
    }

    @Test
    fun noReleaseReason_jsonPath_allTagsUnrecognized() {
        val body = """[{"tag_name":"vc210","draft":false},{"tag_name":"v2026.10","draft":false}]"""
        val releases = AppUpdateChecker.parseReleasesJson(body)!!
        assertNull(AppUpdateChecker.pickLatest(releases))
        assertEquals(AppUpdateChecker.FailReason.UNRECOGNIZED_TAGS, AppUpdateChecker.noReleaseReason(releases))
    }

    @Test
    fun noReleaseReason_jsonPath_allDrafts() {
        val body = """[{"tag_name":"v2026.10.02","draft":true}]"""
        val releases = AppUpdateChecker.parseReleasesJson(body)!!
        assertNull(AppUpdateChecker.pickLatest(releases))
        assertEquals(AppUpdateChecker.FailReason.NO_RELEASES, AppUpdateChecker.noReleaseReason(releases))
    }

    @Test
    fun noReleaseReason_recognizedEntryPresent_isNoReleases() {
        // 正式发布里混着一条认不出的 tag：逐条跳过，取能认出的那条（PC 单测曾抓出 any/none 写反）
        val list = listOf(release("vc210", date = null), release("v2026.10.01", date = 20261001))
        assertEquals(AppUpdateChecker.FailReason.NO_RELEASES, AppUpdateChecker.noReleaseReason(list))
    }

    @Test
    fun mixedTags_picksRecognizedAndNeverReportsUnrecognized() {
        val list = listOf(release("vc210", date = null), release("v2026.10.01", date = 20261001))
        assertEquals("v2026.10.01", AppUpdateChecker.pickLatest(list)?.tag)
        assertTrue(AppUpdateChecker.noReleaseReason(list) != AppUpdateChecker.FailReason.UNRECOGNIZED_TAGS)
    }

    @Test
    fun noReleaseReason_allDraftsIncludingUnrecognizedTag_isNoReleases() {
        val list = listOf(
            release("vc210", draft = true, date = null),
            release("v2026.10", draft = true, date = null),
            release("v2026.10.01", draft = true, date = 20261001),
        )
        assertNull(AppUpdateChecker.pickLatest(list))
        assertEquals(AppUpdateChecker.FailReason.NO_RELEASES, AppUpdateChecker.noReleaseReason(list))
    }

    // ── ⑨ 端到端（纯逻辑侧）：API JSON → 跳过 draft → 取最大 → 三态 ──

    @Test
    fun endToEnd_apiJsonToAvailable() {
        val body = """
            [
              {"tag_name":"v2026.09.30","draft":false,"assets":[]},
              {"tag_name":"v2026.10.02","draft":true,"assets":[]},
              {"tag_name":"v2026.10.01","draft":false,"assets":[
                 {"name":"bilimiao-v2026.10.01.apk","browser_download_url":"https://example.com/a.apk"}]}
            ]
        """.trimIndent()
        val latest = AppUpdateChecker.pickLatest(AppUpdateChecker.parseReleasesJson(body)!!)!!
        val outcome = AppUpdateChecker.compare("v2026.09.30", 20260930, latest)
        assertTrue(outcome is AppUpdateChecker.Outcome.Done)
        val result = (outcome as AppUpdateChecker.Outcome.Done).result
        assertTrue(result is AppUpdateChecker.UpdateResult.Available)
        assertEquals(20261001, (result as AppUpdateChecker.UpdateResult.Available).newDate)
    }
}
