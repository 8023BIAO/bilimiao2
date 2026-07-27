package com.a10miaomiao.bilimiao.comm.network

import android.content.Context
import android.util.Base64
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * B站 Web Cookie 生命周期 — buvid3/4 指纹、bili_ticket、cookie 刷新。
 *
 * 完全自包含：自带 OkHttpClient + CookieStore，不依赖 MiaoHttp。
 * 写入的指纹 cookie 通过 syncToWebView() 同步到 WebView CookieManager，
 * MiaoHttp 从 CookieManager 读取 cookie 时自然带上。
 *
 * 参考 blbl 项目 WebCookieMaintainer.kt。
 */
object WebCookieMaintainer {
    private const val TAG = "WebCookieMaintainer"
    private const val BILI_TICKET_KEY_ID = "ec02"
    private const val BILI_TICKET_HMAC_KEY = "XgwSnGZ1p"
    private const val REFRESH_SOURCE = "main_web"
    private const val WEB_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    private val refreshCsrfRegex = Regex("<div\\s+id=\"1-name\">\\s*([0-9a-fA-F]{16,})\\s*</div>")
    private val cookieRefreshMutex = Mutex()

    private val correspondPublicKey: PublicKey by lazy {
        val derBase64 = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg" +
                "Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71" +
                "nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40" +
                "JNrRuoEUXpabUzGB8QIDAQAB"
        val kf = KeyFactory.getInstance("RSA")
        kf.generatePublic(X509EncodedKeySpec(Base64.decode(derBase64, Base64.DEFAULT)))
    }

    private val app: Context get() = BilimiaoCommApp.commApp.app
    private val cookieStore: CookieStore get() = CookieStore.getInstance(app)
    private val prefs by lazy { app.getSharedPreferences("bilimiao_web_cookie", Context.MODE_PRIVATE) }

    private val webClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieStore)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ═══════════ 公开入口 ═══════════

    suspend fun ensureHealthyForPlay() {
        ensureWebFingerprintCookies()
        ensureBuvidActiveOncePerDay()
        ensureBiliTicket()
        refreshCookieIfNeededOncePerDay()
    }

    suspend fun ensureDailyMaintenance() {
        ensureBiliTicket()
        refreshCookieIfNeededOncePerDay()
    }

    // ═══════════ buvid 激活 ═══════════

    suspend fun ensureBuvidActiveOncePerDay(nowMs: Long = System.currentTimeMillis()) {
        val midStr = cookieStore.getCookieValue("DedeUserID")?.trim().orEmpty()
        val mid = midStr.toLongOrNull()?.takeIf { it > 0 } ?: return
        val epochDay = nowMs / 86_400_000L
        if (prefs.getString("buvid_activated_mid", "") == mid.toString() &&
            prefs.getLong("buvid_activated_day", 0L) == epochDay
        ) return

        runCatching {
            val rand = ByteArray(32 + 8 + 4)
            java.security.SecureRandom().nextBytes(rand)
            rand[32] = 0; rand[33] = 0; rand[34] = 0; rand[35] = 0
            rand[36] = 73; rand[37] = 69; rand[38] = 78; rand[39] = 68
            val tail = ByteArray(4); java.security.SecureRandom().nextBytes(tail)
            for (i in 0 until 4) rand[40 + i] = tail[i]
            val randPngEnd = Base64.encodeToString(rand, Base64.NO_WRAP)

            val jsonData = JSONObject()
                .put("3064", 1).put("39c8", "333.1387.fp.risk")
                .put("3c43", JSONObject().put("adca", "Linux").put("bfe9", randPngEnd.takeLast(50)))
                .toString()
            val body = JSONObject().put("payload", jsonData).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            // Cookie 由 webClient 的 CookieJar 自动带上，无需手动添加 Cookie 头
            val req = Request.Builder()
                .url("https://api.bilibili.com/x/internal/gaia-gateway/ExClimbWuzhi")
                .post(body).addHeader("Content-Type", "application/json")
                .addHeader("env", "prod").addHeader("app-key", "android64")
                .addHeader("x-bili-aurora-zone", "sh001").addHeader("x-bili-mid", mid.toString())
                .addHeader("Referer", "https://www.bilibili.com")
                .apply { genAuroraEid(mid)?.let { addHeader("x-bili-aurora-eid", it) } }
                .build()
            webClient.newCall(req).execute().use { }

            prefs.edit().putString("buvid_activated_mid", mid.toString())
                .putLong("buvid_activated_day", epochDay).apply()
        }.onFailure { miaoLogger().e(TAG, "buvid激活失败", it) }
    }

    private fun genAuroraEid(mid: Long): String? {
        if (mid <= 0) return null
        val key = "ad1va46a7lza".toByteArray()
        val input = mid.toString().toByteArray()
        val out = ByteArray(input.size)
        for (i in input.indices) out[i] = (input[i].toInt() xor key[i % key.size].toInt()).toByte()
        return Base64.encodeToString(out, Base64.NO_PADDING or Base64.NO_WRAP)
    }

    // ═══════════ 指纹 cookie ═══════════

    suspend fun ensureWebFingerprintCookies() {
        val hasBuvid3 = !cookieStore.getCookieValue("buvid3").isNullOrBlank()
        val hasBNut = !cookieStore.getCookieValue("b_nut").isNullOrBlank()
        val hasBuvid4 = !cookieStore.getCookieValue("buvid4").isNullOrBlank()
        if (!hasBuvid3 || !hasBNut) {
            runCatching {
                webClient.newCall(Request.Builder().url("https://www.bilibili.com/")
                    .addHeader("User-Agent", WEB_UA).build()).execute().use { }
            }.onFailure { miaoLogger().e(TAG, "首页指纹获取失败", it) }
        }
        if (!hasBuvid4) {
            runCatching {
                webClient.newCall(Request.Builder().url("https://api.bilibili.com/x/frontend/finger/spi")
                    .addHeader("User-Agent", WEB_UA).addHeader("Referer", "https://www.bilibili.com/").build())
                    .execute().use { resp ->
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        val data = json.optJSONObject("data") ?: return@use
                        val b3 = data.optString("b_3", "").trim()
                        val b4 = data.optString("b_4", "").trim()
                        val exp = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
                        if (b3.isNotBlank() && cookieStore.getCookieValue("buvid3").isNullOrBlank())
                            cookieStore.upsert(buildCookie("buvid3", b3, exp))
                        if (b4.isNotBlank())
                            cookieStore.upsert(buildCookie("buvid4", b4, exp))
                    }
            }.onFailure { miaoLogger().e(TAG, "finger/spi失败", it) }
        }
    }

    // ═══════════ bili_ticket ═══════════

    suspend fun ensureBiliTicket() {
        val nowMs = System.currentTimeMillis()
        val epochDay = nowMs / 86_400_000L
        val existing = cookieStore.getCookie("bili_ticket")
        if (existing != null && existing.expiresAt - nowMs > 6 * 60 * 60 * 1000) return
        if (prefs.getLong("bili_ticket_day", 0L) == epochDay) return
        prefs.edit().putLong("bili_ticket_day", epochDay).apply()

        runCatching {
            val ts = (nowMs / 1000).toString()
            val hexsign = hmacSha256Hex(BILI_TICKET_HMAC_KEY, "ts$ts")
            val csrf = cookieStore.getCookieValue("bili_jct").orEmpty()
            val ub = "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket".toHttpUrl().newBuilder()
                .addQueryParameter("key_id", BILI_TICKET_KEY_ID)
                .addQueryParameter("hexsign", hexsign)
                .addQueryParameter("context[ts]", ts)
            if (csrf.isNotBlank()) ub.addQueryParameter("csrf", csrf)
            webClient.newCall(Request.Builder().url(ub.build()).post(ByteArray(0).toRequestBody(null))
                .addHeader("User-Agent", WEB_UA).addHeader("Referer", "https://www.bilibili.com/").build())
                .execute().use { resp ->
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val data = json.optJSONObject("data") ?: return@use
                    val ticket = data.optString("ticket", "").trim()
                    val ca = data.optLong("created_at", 0L)
                    val ttl = data.optLong("ttl", 0L)
                    if (ticket.isBlank() || ca <= 0L || ttl <= 0L) return@use
                    val exp = (ca + ttl) * 1000L
                    cookieStore.upsertAll(listOf(
                        buildCookie("bili_ticket", ticket, exp),
                        buildCookie("bili_ticket_expires", (ca + ttl).toString(), exp)))
                }
        }.onFailure { miaoLogger().e(TAG, "bili_ticket失败", it) }
    }

    // ═══════════ Cookie 刷新 ═══════════

    suspend fun refreshCookieIfNeededOncePerDay() {
        if (!cookieStore.hasSessData()) return
        val refreshToken = prefs.getString("web_refresh_token", null)?.takeIf { it.isNotBlank() } ?: return
        cookieRefreshMutex.withLock {
            val epochDay = System.currentTimeMillis() / 86_400_000L
            if (prefs.getLong("cookie_refresh_day", 0L) == epochDay) return@withLock
            val biliJct = cookieStore.getCookieValue("bili_jct")?.takeIf { it.isNotBlank() } ?: return@withLock
            runCatching {
                // 1. 检查是否需要刷新
                val infoUrl = "https://passport.bilibili.com/x/passport-login/web/cookie/info".toHttpUrl().newBuilder()
                    .addQueryParameter("csrf", biliJct).build()
                val info = webClient.newCall(Request.Builder().url(infoUrl)
                    .addHeader("User-Agent", WEB_UA).addHeader("Referer", "https://www.bilibili.com/").build())
                    .execute().use { JSONObject(it.body?.string() ?: "{}") }
                val shouldRefresh = info.optJSONObject("data")?.optBoolean("refresh") ?: false
                if (!shouldRefresh) return@runCatching

                // 2. 获取 refresh_csrf
                val ts = info.optJSONObject("data")?.optLong("timestamp", System.currentTimeMillis())
                    ?.takeIf { it > 0 } ?: System.currentTimeMillis()
                val cp = withContext(Dispatchers.Default) { getCorrespondPath(ts) }
                val html = webClient.newCall(Request.Builder().url("https://www.bilibili.com/correspond/1/$cp")
                    .addHeader("User-Agent", WEB_UA).build()).execute().use { it.body?.string() ?: "" }
                val refreshCsrf = refreshCsrfRegex.find(html)?.groupValues?.getOrNull(1).orEmpty()
                if (refreshCsrf.isBlank()) error("refresh_csrf not found")

                // 3. 刷新
                val fb = okhttp3.FormBody.Builder()
                    .add("csrf", biliJct).add("refresh_csrf", refreshCsrf)
                    .add("source", REFRESH_SOURCE).add("refresh_token", refreshToken).build()
                val rr = webClient.newCall(Request.Builder()
                    .url("https://passport.bilibili.com/x/passport-login/web/cookie/refresh")
                    .post(fb).addHeader("User-Agent", WEB_UA).addHeader("Referer", "https://www.bilibili.com/").build())
                    .execute().use { JSONObject(it.body?.string() ?: "{}") }
                val nrt = rr.optJSONObject("data")?.optString("refresh_token", "")?.trim() ?: ""
                if (nrt.isNotBlank()) prefs.edit().putString("web_refresh_token", nrt).apply()

                // 4. 确认
                val nb = cookieStore.getCookieValue("bili_jct")?.takeIf { it.isNotBlank() } ?: biliJct
                val cf = okhttp3.FormBody.Builder().add("csrf", nb).add("refresh_token", refreshToken).build()
                runCatching {
                    webClient.newCall(Request.Builder()
                        .url("https://passport.bilibili.com/x/passport-login/web/confirm/refresh")
                        .post(cf).addHeader("User-Agent", WEB_UA).addHeader("Referer", "https://www.bilibili.com/").build())
                        .execute().use { }
                }

                cookieStore.syncToWebView()
            }.onFailure { miaoLogger().e(TAG, "cookie刷新失败", it); return@withLock }
            prefs.edit().putLong("cookie_refresh_day", epochDay).apply()
        }
    }

    // ═══════════ 工具 ═══════════

    private fun buildCookie(name: String, value: String, expiresAt: Long): Cookie {
        return Cookie.Builder().name(name).value(value).domain("bilibili.com").path("/")
            .expiresAt(expiresAt).secure().build()
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val out = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        return StringBuilder(out.size * 2).apply { for (b in out) append(String.format("%02x", b)) }.toString()
    }

    private fun getCorrespondPath(timestampMs: Long): String {
        val cipher = runCatching { Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding") }
            .getOrElse { Cipher.getInstance("RSA/ECB/OAEPPadding") }
        cipher.init(Cipher.ENCRYPT_MODE, correspondPublicKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        val encrypted = cipher.doFinal("refresh_$timestampMs".toByteArray(StandardCharsets.UTF_8))
        return StringBuilder(encrypted.size * 2).apply { for (b in encrypted) append(String.format("%02x", b)) }.toString()
    }
}
