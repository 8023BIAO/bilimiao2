package com.a10miaomiao.bilimiao.comm

import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import com.a10miaomiao.bilimiao.comm.network.CookieStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import com.a10miaomiao.bilimiao.comm.entity.auth.LoginInfo
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.utils.AESUtil
import com.a10miaomiao.bilimiao.comm.utils.MiaoEncryptDecrypt
import com.a10miaomiao.bilimiao.comm.utils.ErrorLogCollector
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.kongzue.dialogx.DialogX
import com.kongzue.dialogxmaterialyou.style.MaterialYouStyle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.File

class BilimiaoCommApp(
    val app: Application
) {
    var loginInfo: LoginInfo? = null
        private set

    private val authFilePath get() = app.filesDir.path + "/auth_hd"
    private val key get() = com.a10miaomiao.bilimiao.comm.BuildConfig.AES_KEY
    private var _bilibiliBuvid = ""

    companion object {
        lateinit var commApp: BilimiaoCommApp

        const val APP_NAME = "bilimiao"
    }

    fun onCreate() {
        commApp = this
        readAuthInfo()
        // 后台维护"设置的内存快照"：播放器初始化 / getMediaSource 那条主线程路径以后直接读快照，
        // 不再 runBlocking 读 DataStore（详见 SettingPreferences.warmUpCache）
        SettingPreferences.warmUpCache(app)
        // 同步 WBI 签名开关
        try {
            MiaoHttp.isWbiEnabled = runBlocking {
                SettingPreferences.run {
                    SettingPreferences.mapData(app) { prefs ->
                        prefs[SettingPreferences.WbiSignEnabled] ?: true
                    }
                }
            }
        } catch (e: Exception) {
            miaoLogger().e("WBI开关同步失败", e)
        }
        ErrorLogCollector.init(app)

        DialogX.init(app)
        DialogX.globalStyle = MaterialYouStyle.style()

        // 未登录（游客模式）：每天补一套**匿名**设备指纹。
        // 否则一个 buvid3 都没有 → B站 Web 接口全返回 -352 → 游客模式什么都刷不出来；
        // 而用登录时期留下的指纹又会跟账号关联（游客态就应当什么都不带）。
        runCatching {
            CoroutineScope(Dispatchers.IO).launch {
                com.a10miaomiao.bilimiao.comm.network.GuestFingerprint.ensureAnonymousOncePerDay()
            }
        }
    }

    /**
     * 把一份登录 Cookie 写进 WebView 的 CookieManager（**登录凭据的入口**）。
     *
     * 实现只有一份：[CookieStore.writeRawCookie]（真实 URL ×3 + 回读校验 + 只打名字的日志）。
     * 这里只做三件事：① 非 bilibili 域名直接跳过；② 按 `expires`（**秒级时间戳**，不是 HTTP 日期）
     * 换算 `Max-Age`；③ 聚合结果。
     *
     * @return 是否至少有一条身份 Cookie（SESSDATA / bili_jct / DedeUserID）**真的写进去了**。
     *         **不抛异常**：写 Cookie 失败不能打断登录流程。
     */
    fun setCookie(cookieInfo: LoginInfo.CookieInfo): Boolean {
        if (cookieInfo.domains.none { it.contains("bilibili.com", ignoreCase = true) }) {
            miaoLogger().e("写登录Cookie跳过", "非 bilibili 域名：${cookieInfo.domains}")
            return false
        }
        val nowSeconds = System.currentTimeMillis() / 1000
        var written = 0
        cookieInfo.cookies.forEach { cookie ->
            if (cookie.name.isBlank() || cookie.value.isBlank()) return@forEach
            // 已过期的凭据写了也没用（服务端照样判未登录）
            if (cookie.expires > 0 && cookie.expires < nowSeconds) return@forEach
            val maxAge = if (cookie.expires > nowSeconds) {
                cookie.expires - nowSeconds
            } else {
                CookieStore.ONE_YEAR_SECONDS
            }
            val attributes = buildString {
                append("; Path=/; Domain=.bilibili.com; Max-Age=").append(maxAge)
                if (cookie.http_only == 1) append("; HttpOnly")
            }
            CookieStore.writeRawCookie(cookie.name, cookie.value, attributes)
            written++
        }
        // 成功判据是"**身份** cookie 在 www 与 api 两个真实 URL 上都回读得到"（只写进指纹不算登录态可用）。
        // ★必须带上 api：App 真正消费 Cookie 的是 MiaoHttp（读 api.bilibili.com）；只回读 www 的话，
        //   万一 Domain 属性被丢掉退化成 host-only www，www 照样"回读成功"而 api 侧一条都不带
        //   —— 那正是「CSRF 认证失败」的形态却报成功。
        val names = runCatching { CookieManager.getInstance() }
            .getOrNull()
            ?.let { manager ->
                listOf(API_COOKIE_URL, READBACK_COOKIE_URL).flatMap { url ->
                    CookieStore.readBackCookieNames(manager, url)
                }.distinct()
            }
            .orEmpty()
        val ok = CookieStore.IDENTITY_COOKIE_NAMES.any { id ->
            names.any { it.equals(id, ignoreCase = true) }
        }
        miaoLogger().e("写登录Cookie${if (ok) "成功" else "失败"}", "写入=$written 个", "回读到=${names.joinToString(",")}")
        return ok
    }

    private fun getMiaoEncryptDecrypt(): MiaoEncryptDecrypt {
        val key = getBilibiliBuvid().toByteArray()
        return MiaoEncryptDecrypt(key)
    }

    /**
     * 落盘身份并灌 Cookie。
     *
     * ★顺序：**先写盘成功，再改内存 + 灌 Cookie**。以前是先 `this.loginInfo = loginInfo` 再写盘，
     *   写盘一抛异常就"内存说有、磁盘没有"（调用方还会以为成功了），且没有回滚点。
     *   现在 writeBytes 抛异常时，内存里的 loginInfo 仍是旧值 ⇒ 失败可回退。
     */
    fun saveAuthInfo(loginInfo: LoginInfo) {
        val miaoED = getMiaoEncryptDecrypt()
        val jsonStr = MiaoJson.toJson(loginInfo)
        val jsonByteArray = jsonStr.toByteArray()
        val secretKey = AESUtil.getKey(key, app)
        val cipher = AESUtil.encrypt(miaoED.encrypt(jsonByteArray), secretKey)
        val file = File(authFilePath)
        file.writeBytes(cipher)
        // 磁盘已就位，内存与 CookieManager 再跟上
        this.loginInfo = loginInfo
        loginInfo.cookie_info?.let { setCookie(it) }
    }

    private fun readAuthInfo(): LoginInfo? {
        try {
            val miaoED = getMiaoEncryptDecrypt()
            val secretKey = AESUtil.getKey(key, app)
            val file = File(authFilePath)
            val cipher = file.readBytes()
            val jsonByteArray = miaoED.decrypt(AESUtil.decrypt(cipher, secretKey))
            val jsonStr = String(jsonByteArray)
            val loginInfo = MiaoJson.fromJson<LoginInfo>(jsonStr)
            this.loginInfo = loginInfo
            // ★ 冷启动必须把 cookie 重新灌回 WebView CookieManager：
            //   saveAuthInfo 只在"登录那一刻"灌过一次，App 重启后 CookieManager 常常是空的，
            //   于是所有 WEB 接口（图片上传 upload_bfs、web 评论等）都会 -101 未登录，
            //   而走 Authorization 头的 APP 接口照常能用 —— 表现就是"能发文字评论、发不了图"。
            loginInfo.cookie_info?.let { setCookie(it) }
            return loginInfo
        } catch (e: Exception) {
            miaoLogger().e("读取AuthInfo失败", e)
            return null
        }
    }

    fun deleteAuth() {
        val file = File(authFilePath)
        file.delete()
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeSessionCookies(null)//移除
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        // ★ 另一个持久化 Cookie 仓库（OkHttp CookieJar，落盘在 bilimiao_cookie_store）也要清。
        //   它保存过从 WebView 导出的 SESSDATA/bili_jct（发带图评论那条路会导入），
        //   而评论框在"没有 web 登录态"时会 importFromWebView() + syncToWebView() ——
        //   只清 CookieManager 的话，旧登录态会被它悄悄写回 WebView，游客模式就名存实亡了。
        runCatching { CookieStore.getInstance(app).clearAll() }
        this.loginInfo = null
    }


    /**
     * 设置 buvid（**导入身份信息时必须走这里**）。
     *
     * 为什么：`getBilibiliBuvid()` 有内存缓存，而 auth 文件的 AES 密钥是用 buvid 派生的。
     * 导入时若只写 SharedPreferences、缓存不更新，就会"用旧 buvid 的密钥加密 + 重启后用新 buvid 解密"
     * → 解密失败 → **静默登出**，且原 auth 文件已被覆盖、登不回去（审查发现的 S2）。
     */
    fun setBilibiliBuvid(buvid: String) {
        _bilibiliBuvid = buvid
        app.getSharedPreferences(APP_NAME, Context.MODE_PRIVATE)
            .edit().putString("buvid", buvid).apply()
    }

    fun getBilibiliBuvid(): String {
        if (_bilibiliBuvid.isNotBlank()) {
            // 兜底：SharedPreferences 里的值被外部改过（导入）时以文件为准
            val spBuvid = app.getSharedPreferences(APP_NAME, Context.MODE_PRIVATE)
                .getString("buvid", "")!!
            if (spBuvid.isNotBlank() && spBuvid != _bilibiliBuvid) {
                _bilibiliBuvid = spBuvid
            }
            return _bilibiliBuvid
        }
        val sp = app.getSharedPreferences(APP_NAME, Context.MODE_PRIVATE)
        var buvid = sp.getString("buvid", "")!!
        if (buvid.isBlank()) {
            buvid = ApiHelper.generateBuvid()
            sp.edit().putString("buvid", buvid).apply()
        }
        _bilibiliBuvid = buvid
        return buvid
    }


}

/** 回读校验用的第二个真实 URL：App 的 Web 接口（MiaoHttp）读的就是它 */
private const val API_COOKIE_URL = "https://api.bilibili.com"

/** 回读校验用的第一个真实 URL */
private const val READBACK_COOKIE_URL = "https://www.bilibili.com"
