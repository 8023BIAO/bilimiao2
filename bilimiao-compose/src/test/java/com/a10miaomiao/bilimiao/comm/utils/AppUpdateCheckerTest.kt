package com.a10miaomiao.bilimiao.comm.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AppUpdateChecker` 纯函数单测：tag 解析正反例 / 乱序取最大 / 三态 / 附件缺失 / Atom 兜底 / 超长正文。
 *
 * 运行：`gradlew :bilimiao-compose:testDebugUnitTest`（0 步门里会跑）。
 * 断言全部是"能失败"的具体值比较（没有 assertTrue(true) 这类恒真断言）。
 */
class AppUpdateCheckerTest {

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        notes: String = "",
        apkUrl: String? = null,
        pageUrl: String = "https://github.com/8023BIAO/bilimiao2/releases/tag/$tag",
    ) = AppUpdateChecker.AppRelease(
        tag = tag,
        date = AppUpdateChecker.parseReleaseDate(tag),
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

    // ── ② tag → 日期：反例（必须返回 null，绝不猜） ──

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

    // ── ③ pickLatest：乱序 / draft / prerelease / 空 ──

    @Test
    fun pickLatest_unorderedList_takesMaxDate() {
        val list = listOf(
            release("v2026.09.20"),
            release("v2026.10.01"),
            release("v2026.09.30"),
        )
        assertEquals("v2026.10.01", AppUpdateChecker.pickLatest(list)?.tag)
    }

    @Test
    fun pickLatest_newerDraft_isSkipped() {
        val list = listOf(
            release("v2026.10.02", draft = true),
            release("v2026.10.01"),
        )
        assertEquals("v2026.10.01", AppUpdateChecker.pickLatest(list)?.tag)
    }

    @Test
    fun pickLatest_allDrafts_isNull() {
        val list = listOf(release("v2026.10.02", draft = true), release("v2026.10.01", draft = true))
        assertNull(AppUpdateChecker.pickLatest(list))
    }

    @Test
    fun pickLatest_emptyList_isNull() {
        assertNull(AppUpdateChecker.pickLatest(emptyList()))
    }

    @Test
    fun pickLatest_prereleaseIsKept() {
        val list = listOf(
            release("v2026.10.01", prerelease = true),
            release("v2026.09.20"),
        )
        val latest = AppUpdateChecker.pickLatest(list)
        assertEquals("v2026.10.01", latest?.tag)
        assertTrue(latest!!.prerelease)
    }

    @Test
    fun pickLatest_nonDateTag_isIgnored() {
        val list = listOf(release("nightly"), release("v2026.09.20"))
        assertEquals("v2026.09.20", AppUpdateChecker.pickLatest(list)?.tag)
    }

    // ── ④ compare：三态 ──

    @Test
    fun compare_sameDate_isUpToDate() {
        val result = AppUpdateChecker.compare("v2026.10.01", 20261001, release("v2026.10.01"))
        assertTrue(result is AppUpdateChecker.UpdateResult.UpToDate)
        assertEquals("v2026.10.01", (result as AppUpdateChecker.UpdateResult.UpToDate).currentTag)
    }

    @Test
    fun compare_newerRemote_isAvailableAndCarriesFields() {
        val latest = release(
            "v2026.10.02",
            notes = "修了几个毛病",
            apkUrl = "https://github.com/8023BIAO/bilimiao2/releases/download/v2026.10.02/bilimiao-v2026.10.02.apk",
        )
        val result = AppUpdateChecker.compare("v2026.10.01", 20261001, latest)
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
        val result = AppUpdateChecker.compare("v2026.10.05", 20261005, release("v2026.10.01"))
        assertTrue(result is AppUpdateChecker.UpdateResult.RemoteOlder)
        val older = result as AppUpdateChecker.UpdateResult.RemoteOlder
        assertEquals("v2026.10.05", older.currentTag)
        assertEquals("v2026.10.01", older.latestTag)
    }

    @Test
    fun compare_unknownCurrentDate_isAvailableNotUpToDate() {
        // 本机 versionName 不符合约定（开发版）时：宁可多提示一次，也不谎称"已是最新"
        val result = AppUpdateChecker.compare("dev-build", null, release("v2026.10.01"))
        assertTrue(result is AppUpdateChecker.UpdateResult.Available)
    }

    // ── ⑤ parseReleasesJson：字段映射 / 附件优先 / 坏 JSON ──

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

    // ── ⑥ Atom 兜底 ──

    private val atomXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <title>v2026.09.20</title>
            <link rel="alternate" type="text/html" href="https://github.com/8023BIAO/bilimiao2/releases/tag/v2026.09.20"/>
            <content type="html">旧版本</content>
          </entry>
          <entry>
            <title>v2026.10.02</title>
            <link rel="alternate" type="text/html" href="https://github.com/8023BIAO/bilimiao2/releases/tag/v2026.10.02"/>
            <content type="html">&lt;p&gt;修了 &amp;lt; 转义 &lt;br/&gt;第二行&lt;/p&gt;</content>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun parseAtomReleases_picksMaxDateRegardlessOfOrder() {
        val list = AppUpdateChecker.parseAtomReleases(atomXml)
        assertNotNull(list)
        assertEquals("v2026.10.02", AppUpdateChecker.pickLatest(list!!)?.tag)
    }

    @Test
    fun parseAtomReleases_stripsHtmlAndUnescapes() {
        val list = AppUpdateChecker.parseAtomReleases(atomXml)!!
        val latest = AppUpdateChecker.pickLatest(list)!!
        assertEquals("修了 &lt; 转义\n第二行", latest.notes)
    }

    @Test
    fun parseAtomReleases_keepsReleasePageUrl() {
        val latest = AppUpdateChecker.pickLatest(AppUpdateChecker.parseAtomReleases(atomXml)!!)!!
        assertEquals("https://github.com/8023BIAO/bilimiao2/releases/tag/v2026.10.02", latest.pageUrl)
        assertNull(latest.apkUrl)
    }

    @Test
    fun parseAtomReleases_noEntry_isNull() {
        assertNull(AppUpdateChecker.parseAtomReleases("<feed></feed>"))
    }

    // ── ⑦ 端到端（纯逻辑侧）：API JSON → 最新 → 三态 ──

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
        val result = AppUpdateChecker.compare("v2026.09.30", 20260930, latest)
        assertTrue(result is AppUpdateChecker.UpdateResult.Available)
        assertEquals(20261001, (result as AppUpdateChecker.UpdateResult.Available).newDate)
    }

    // ── ⑧ 错误分型：限流判定 / "为什么没有可用的版本" ──

    @Test
    fun isRateLimited_403WithZeroRemaining_isTrue() {
        assertTrue(AppUpdateChecker.isRateLimited(403, "0"))
    }

    @Test
    fun isRateLimited_403WithoutHeader_isTrue() {
        // 不带 X-RateLimit-Remaining 的 403（二次限流/被拦）同样按"请求太频繁"处理
        assertTrue(AppUpdateChecker.isRateLimited(403, null))
    }

    @Test
    fun isRateLimited_403WithRemainingLeft_isTrue() {
        assertTrue(AppUpdateChecker.isRateLimited(403, "17"))
    }

    @Test
    fun isRateLimited_200_isFalse() {
        assertTrue(!AppUpdateChecker.isRateLimited(200, null))
    }

    @Test
    fun isRateLimited_404_isFalse() {
        assertTrue(!AppUpdateChecker.isRateLimited(404, null))
    }

    @Test
    fun noReleaseReason_allDrafts_isNoReleases() {
        val list = listOf(release("v2026.10.02", draft = true), release("v2026.10.01", draft = true))
        assertEquals(AppUpdateChecker.FailReason.NO_RELEASES, AppUpdateChecker.noReleaseReason(list))
    }

    @Test
    fun noReleaseReason_emptyList_isNoReleases() {
        assertEquals(AppUpdateChecker.FailReason.NO_RELEASES, AppUpdateChecker.noReleaseReason(emptyList()))
    }

    @Test
    fun noReleaseReason_allTagsUnrecognized_isUnrecognizedTags() {
        val list = listOf(release("vc210"), release("v2026.10"), release("release-1"))
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
        // 有能认出来的正式条目（这条会被 pickLatest 取走），"没有发布"的判定不应误报"格式认不出"
        val list = listOf(release("vc210"), release("v2026.10.01"))
        assertEquals(AppUpdateChecker.FailReason.NO_RELEASES, AppUpdateChecker.noReleaseReason(list))
    }
}
