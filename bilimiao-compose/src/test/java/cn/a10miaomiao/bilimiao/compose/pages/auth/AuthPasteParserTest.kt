package cn.a10miaomiao.bilimiao.compose.pages.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AuthPasteParser` 的单测：覆盖"不管粘什么都要能吃"的五类输入 + SESSDATA 值识别 + 反例。
 *
 * 运行：`gradlew :bilimiao-compose:testDebugUnitTest`（0 步门里会跑）。
 */
class AuthPasteParserTest {

    // ── ① 扁平 JSON（本 App「身份导出」/ .bili_login.json 形态） ──

    private val flatWithToken =
        """{"cookie":"SESSDATA=s1; bili_jct=j1","buvid":"BV1","wbi":{"mixKey":"k","lastFetchDay":1},"access_token":"tok-abc","refresh_token":"ref-abc","mid":"6789810"}"""

    private val flatCookieOnly =
        """{"cookie":"SESSDATA=s1; bili_jct=j1","buvid":"BV1","wbi":{},"access_token":"","refresh_token":"","mid":"6789810"}"""

    @Test
    fun flatJson_withToken_parsesTokenAndCookie() {
        val r = AuthPasteParser.parse(flatWithToken)
        assertNotNull(r)
        assertEquals(PastedKind.TOKEN_COOKIE, r!!.kind)
        assertEquals("tok-abc", r.accessToken)
        assertEquals("ref-abc", r.refreshToken)
        assertTrue(r.cookie.contains("SESSDATA=s1"))
        assertTrue(r.cookie.contains("bili_jct=j1"))
    }

    @Test
    fun flatJson_emptyToken_isCookieOnly() {
        val r = AuthPasteParser.parse(flatCookieOnly)
        assertNotNull(r)
        assertEquals(PastedKind.COOKIE, r!!.kind)
        assertTrue(r.hasCookie)
        assertTrue(!r.hasToken)
    }

    @Test
    fun flatJson_emptyCookie_tokenStillWorks() {
        val r = AuthPasteParser.parse("""{"cookie":"","access_token":"tok-abc","mid":"1"}""")
        assertNotNull(r)
        assertEquals(PastedKind.TOKEN, r!!.kind)
        assertEquals("tok-abc", r.accessToken)
    }

    // ── ② LoginInfo 形态 JSON（BOM / 多余字段 / cookie_info 为 null） ──

    private val loginInfoJson =
        """{"token_info":{"access_token":"tok-n","expires_in":2592000,"mid":42,"refresh_token":"ref-n"},"sso":["x"],"cookie_info":{"cookies":[{"expires":0,"http_only":0,"name":"SESSDATA","value":"sn"},{"expires":0,"http_only":0,"name":"bili_jct","value":"jn"}],"domains":[".bilibili.com"]}}"""

    @Test
    fun loginInfoJson_parsesNestedTokenAndCookies() {
        val r = AuthPasteParser.parse(loginInfoJson)
        assertNotNull(r)
        assertEquals(PastedKind.TOKEN_COOKIE, r!!.kind)
        assertEquals("tok-n", r.accessToken)
        assertEquals("ref-n", r.refreshToken)
        assertEquals("SESSDATA=sn; bili_jct=jn", r.cookie)
    }

    @Test
    fun loginInfoJson_withBom_stillParses() {
        val r = AuthPasteParser.parse("\uFEFF" + loginInfoJson)
        assertNotNull(r)
        assertEquals(PastedKind.TOKEN_COOKIE, r!!.kind)
        assertEquals("tok-n", r.accessToken)
    }

    @Test
    fun loginInfoJson_withExtraUnknownFields_stillParses() {
        val withExtra = loginInfoJson.dropLast(1) +
            ""","extra":{"deep":[1,2,{"a":null}]},"another":"x"}"""
        val r = AuthPasteParser.parse(withExtra)
        assertNotNull(r)
        assertEquals(PastedKind.TOKEN_COOKIE, r!!.kind)
        assertEquals("tok-n", r.accessToken)
    }

    @Test
    fun loginInfoJson_nullCookieInfo_tokenOnly() {
        val r = AuthPasteParser.parse("""{"token_info":{"access_token":"tok-n","refresh_token":"ref-n","mid":42},"cookie_info":null}""")
        assertNotNull(r)
        assertEquals(PastedKind.TOKEN, r!!.kind)
        assertEquals("tok-n", r.accessToken)
        assertTrue(!r.hasCookie)
    }

    @Test
    fun loginInfoJson_emptyCookiesArray_isNotCookie() {
        val r = AuthPasteParser.parse("""{"cookie_info":{"cookies":[],"domains":[]},"token_info":{"access_token":"tok-n"}}""")
        assertNotNull(r)
        assertEquals(PastedKind.TOKEN, r!!.kind)
    }

    @Test
    fun json_tokenAsNumber_isAcceptedAsString() {
        val r = AuthPasteParser.parse("""{"access_token":123456,"mid":789}""")
        assertNotNull(r)
        assertEquals(PastedKind.TOKEN, r!!.kind)
        assertEquals("123456", r.accessToken)
    }

    // ── ③ Cookie 文本（标准 / DevTools 整段带换行） ──

    @Test
    fun cookieText_standard_isCookie() {
        val r = AuthPasteParser.parse("SESSDATA=abc%2Cdef; bili_jct=xyz; DedeUserID=123")
        assertNotNull(r)
        assertEquals(PastedKind.COOKIE, r!!.kind)
        assertTrue(r.cookie.contains("SESSDATA=abc%2Cdef"))
        assertTrue(r.cookie.contains("DedeUserID=123"))
    }

    @Test
    fun cookieText_singleSessData_isCookie() {
        val r = AuthPasteParser.parse("SESSDATA=abc")
        assertNotNull(r)
        assertEquals(PastedKind.COOKIE, r!!.kind)
        assertEquals("SESSDATA=abc", r.cookie)
    }

    @Test
    fun devToolsHeader_keepsSessDataFromFirstSegment() {
        // ★真实 Cookie 头里 SESSDATA 几乎总是第一段：以前按"名字像不像 cookie 名"筛会把它整条丢掉
        val raw = "GET /x HTTP/1.1\nHost: api.bilibili.com\nCookie: SESSDATA=abc; bili_jct=def"
        val r = AuthPasteParser.parse(raw)
        assertNotNull(r)
        assertEquals(PastedKind.COOKIE, r!!.kind)
        assertTrue(r.cookie.contains("SESSDATA=abc"))
        assertTrue(r.cookie.contains("bili_jct=def"))
    }

    @Test
    fun cookieHeaderLine_keepsSessData() {
        // F12 里"Copy"出来的裸头行
        val r = AuthPasteParser.parse("Cookie: SESSDATA=v1; bili_jct=t1")
        assertNotNull(r)
        assertEquals(PastedKind.COOKIE, r!!.kind)
        assertTrue(r.cookie.contains("SESSDATA=v1"))
        assertTrue(r.cookie.contains("bili_jct=t1"))
    }

    @Test
    fun curlCookieFlag_keepsSessData() {
        // Copy as cURL 片段；★整串断言：值里绝不能带上包裹用的那个单引号
        val r = AuthPasteParser.parse("-H 'cookie: SESSDATA=v1; bili_jct=t1'")
        assertNotNull(r)
        assertEquals(PastedKind.COOKIE, r!!.kind)
        assertEquals("SESSDATA=v1; bili_jct=t1", r.cookie)
    }

    @Test
    fun quotedCookieText_keepsSessData() {
        // 首尾带成对引号
        val single = AuthPasteParser.parse("'SESSDATA=v1; bili_jct=t1'")
        assertNotNull(single)
        assertEquals(PastedKind.COOKIE, single!!.kind)
        assertEquals("SESSDATA=v1; bili_jct=t1", single.cookie)
        val double = AuthPasteParser.parse("\"SESSDATA=v1; bili_jct=t1\"")
        assertNotNull(double)
        assertEquals(PastedKind.COOKIE, double!!.kind)
        assertTrue(double.cookie.contains("SESSDATA=v1"))
    }

    @Test
    fun devToolsFullHeaderDump_keepsSessData() {
        // 整段请求头（含 Host / User-Agent / Accept 等杂行）
        val raw = listOf(
            "GET /x/web-interface/nav HTTP/1.1",
            "Host: api.bilibili.com",
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Accept: application/json, text/plain, */*",
            "Cookie: SESSDATA=v1; bili_jct=t1; DedeUserID=42",
        ).joinToString("\n")
        val r = AuthPasteParser.parse(raw)
        assertNotNull(r)
        assertEquals(PastedKind.COOKIE, r!!.kind)
        assertTrue(r.cookie.contains("SESSDATA=v1"))
        assertTrue(r.cookie.contains("bili_jct=t1"))
        assertTrue(r.cookie.contains("DedeUserID=42"))
    }

    @Test
    fun cookieHeaderLine_onlySessData_keepsIt() {
        val raw = "GET /x HTTP/1.1\nHost: api.bilibili.com\nCookie: SESSDATA=abc"
        val r = AuthPasteParser.parse(raw)
        assertNotNull(r)
        assertEquals(PastedKind.COOKIE, r!!.kind)
        assertEquals("SESSDATA=abc", r.cookie)
    }

    @Test
    fun fingerprintOnlyCookie_isNotRecognized() {
        assertNull(AuthPasteParser.parse("buvid3=xyz; b_nut=123"))
    }

    // ── ④ 裸 access_token ──

    @Test
    fun bareJwtToken_isToken() {
        val r = AuthPasteParser.parse("eyJhbGciOiJIUzI1NiJ9.eyJtaWQiOjEyM30.abc-def_ghi")
        assertNotNull(r)
        assertEquals(PastedKind.TOKEN, r!!.kind)
        assertEquals("eyJhbGciOiJIUzI1NiJ9.eyJtaWQiOjEyM30.abc-def_ghi", r.accessToken)
    }

    // ── ⑤ 只粘 SESSDATA 的值（窄特征）+ 反例 ──

    /** SESSDATA 值形态（★样例是**纯虚构**值：deadbeef/1700000000，绝不许用真实凭据做样例）：8 位十六进制 + %2C + 时间戳 + %2C + 长串 */
    private val sessDataValue = "deadbeef%2C1700000000%2C0123456789abcdef0123456789abcdef"

    @Test
    fun sessDataValueOnly_isRecognizedAsSessData() {
        val r = AuthPasteParser.parse(sessDataValue)
        assertNotNull(r)
        assertEquals(PastedKind.SESSDATA_VALUE, r!!.kind)
        assertEquals("SESSDATA=$sessDataValue", r.cookie)
        assertTrue(!r.hasToken)
        assertEquals("识别为 SESSDATA，按 Cookie 登录", AuthPasteParser.describe(r))
    }

    @Test
    fun sessDataPrefixButTooShort_isNotRecognized() {
        // 前缀对但长度 < 40：按窄规则不认；它也不是 token（含 % 和 ,）⇒ 如实说"没认出"
        assertNull(AuthPasteParser.parse("deadbeef%2C1700000000%2Cabc"))
    }

    @Test
    fun randomLongString_isNotSessData() {
        val r = AuthPasteParser.parse("Xk92mVq7Lp3Rt8Yu4Wn6Zc1Df5Gh0Jk2Lm9Np4Qr7St")
        assertNotNull(r)
        assertEquals(PastedKind.TOKEN, r!!.kind)
    }

    @Test
    fun normalToken_isNotSessData() {
        val r = AuthPasteParser.parse("eyJhbGciOiJIUzI1NiJ9.eyJtaWQiOjEyM30")
        assertNotNull(r)
        assertEquals(PastedKind.TOKEN, r!!.kind)
    }

    // ── ⑥ 垃圾输入：不崩、给人话提示 ──

    @Test
    fun garbageInputs_areNotRecognized() {
        assertNull(AuthPasteParser.parse("随便一段话，什么都粘一点"))
        assertNull(AuthPasteParser.parse(""))
        assertNull(AuthPasteParser.parse("   \n  "))
        assertNull(AuthPasteParser.parse("\uFEFF"))
        assertNull(AuthPasteParser.parse("{not json"))
        assertEquals("没认出登录信息", AuthPasteParser.describe(null))
    }

    // ── 提示语口径 ──

    @Test
    fun describe_matchesDetectedKind() {
        assertEquals(
            "识别为 Token，按 Token 登录",
            AuthPasteParser.describe(PastedAuth(accessToken = "tok", kind = PastedKind.TOKEN)),
        )
        assertEquals("识别为 Cookie，按 Cookie 登录", AuthPasteParser.describe(AuthPasteParser.parse(flatCookieOnly)))
        assertEquals("识别为 Token + Cookie，按 Token 登录", AuthPasteParser.describe(AuthPasteParser.parse(loginInfoJson)))
        assertEquals("没认出登录信息", AuthPasteParser.describe(PastedAuth()))
    }
}
