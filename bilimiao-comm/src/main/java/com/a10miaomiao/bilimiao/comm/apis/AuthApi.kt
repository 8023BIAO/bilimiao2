package com.a10miaomiao.bilimiao.comm.apis

import android.webkit.CookieManager
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.entity.auth.LoginInfo
import com.a10miaomiao.bilimiao.comm.entity.user.UserInfo
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.ApiHelper.BILI_APP_VERSION
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.utils.RSAUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.*

class AuthApi {

    fun account() = MiaoHttp.request {
        url = BiliApiService.biliApp(
            "x/v2/account/mine",
        )
    }

    /**
     * 用一段 `access_token` 验证登录态并取回用户信息（**不看全局登录态**）。
     *
     * ★`asGuest = true` 是**必须的**，不是借用"游客"语义：`MiaoHttp.buildRequest` 只要
     * `!isWebApi && !asGuest` 就会把**已保存的**身份塞进头（`x-bili-mid` + `Authorization`，
     * 见 `MiaoHttp.kt` 的 APP 头区块）。这里要验的是用户刚粘贴的 token，若不禁掉那块，
     * 同一个请求会带两个 `Authorization`（旧的在前）且 `x-bili-mid` 还是旧账号 ——
     * 切号/重登时验的就是旧身份。`asGuest` 同时让请求不带本机 Cookie，避免旧 SESSDATA 混进来。
     *
     * 还有两个配套：
     * · `notoken=1`：`ApiHelper.createParams` 默认把"当前登录态"注入 query 的 `access_key`/`mid`
     *   （`ApiHelper.addAccessKeyAndMidToParams`），这里必须显式传自己的 `access_key`；
     *   `sign` 照显式参数计算。
     * · `Authorization` 头自己补（`asGuest` 只跳过 MiaoHttp 自动加的那份，自定义 headers 照发）。
     */
    fun accountByToken(accessToken: String) = MiaoHttp.request {
        url = BiliApiService.createUrl(
            "https://app.bilibili.com/x/v2/account/mine",
            "access_key" to accessToken,
            "notoken" to "1",
        )
        asGuest = true
        headers["Authorization"] = "identify_v1 $accessToken"
    }

    /**
     * 用一段 Cookie 取 **Web** 登录资料（`x/web-interface/nav`，**不写全局 CookieManager**）。
     *
     * `asGuest = true` 让 MiaoHttp 只带这里给的这段 Cookie（见 MiaoHttp.buildRequest），
     * 所以验证失败不会把无效 Cookie 留在本机；`isWebApi = true` 跳过 APP 专有头。
     *
     * ★为什么不复用 `account()`：APP 接口（`app.bilibili.com`）**不认 Cookie** —— 只带
     * SESSDATA 时它照样回 `code=0`，但 `mid=0`、`name` 为空（匿名档），只有 Web 接口认。
     * 所以 Cookie 登录的"验证 + 资料"都从 nav 取（nav 还在 `MiaoHttp` 里被排除在 WBI 之外，
     * 请求形态不会被签名改变）。
     */
    fun webNav(cookie: String) = MiaoHttp.request {
        url = "https://api.bilibili.com/x/web-interface/nav"
        isWebApi = true
        asGuest = true
        guestCookie = cookie
    }

//    fun authInfo(access_token: String): Observable<ResultInfo<UserInfo>> {
//        var params = mapOf(
//            "appkey" to ApiHelper.APP_KEY_NEW,
//            "access_key" to access_token,
//            "build" to "5310300",
//            "mobi_app" to "android",
//            "platform" to "android",
//            "ts" to ApiHelper.getTimeSpen().toString()
//        )
//        var url = "https://app.bilibili.com/x/v2/account/mine?" + ApiHelper.urlencode(params)
//        url += "&sign=" + ApiHelper.getNewSign(url)
//        return MiaoHttp.getJson(url)
//    }

    fun oauth2() = MiaoHttp.request {
        url = BiliApiService.createUrl(
            "https://passport.bilibili.com/api/oauth2/info"
        )
    }

    fun refreshToken(refreshToken: String) = MiaoHttp.request {
        url = "https://passport.bilibili.com/api/oauth2/refreshToken"
        formBody = ApiHelper.createParams(
            "refresh_token" to refreshToken
        )
        method = MiaoHttp.POST
    }

    fun sso() = MiaoHttp.request {
        url = BiliApiService.createUrl("https://passport.bilibili.com/api/login/sso")
    }


    fun cookieInfo(
        biliJct: String,
        cookie: String,
    ) = MiaoHttp.request {
        url = "https://passport.bilibili.com/x/passport-login/web/cookie/info?csrf=$biliJct"
        headers["cookie"] = cookie
    }

    /**
     * 验证码
     */
    fun captchaPre() = MiaoHttp.request {
        url = "https://passport.bilibili.com/x/safecenter/captcha/pre"
        formBody = ApiHelper.createParams(
            "disable_rcmd" to "0",
        )
        method = MiaoHttp.POST
    }

    /**
     * 密码加密密钥
     */
    fun webKey() = MiaoHttp.request {
        url = BiliApiService.createUrl("https://passport.bilibili.com/x/passport-login/web/key",
            "disable_rcmd" to "0",
            "local_id" to BilimiaoCommApp.commApp.getBilibiliBuvid(),)
    }

    /**
     * 密码登录带验证码
     */
    fun oauth2Login(
        username: String,
        passport: String,
        key: String,
        rhash: String,
        geeChallenge: String,
        geeSeccode: String,
        geeValidate: String,
        recaptchaToken: String,
    ) = MiaoHttp.request {
        url = "https://passport.bilibili.com/x/passport-login/oauth2/login"
        formBody = ApiHelper.createParams(
            "disable_rcmd" to "0",
            "local_id" to BilimiaoCommApp.commApp.getBilibiliBuvid(),
            "password" to RSAUtil.rsaPassword(passport, key, rhash),
            "username" to username,
            "gee_type" to "10",
            "gee_challenge" to geeChallenge,
            "gee_seccode" to geeSeccode,
            "gee_validate" to geeValidate,
            "recaptcha_token" to recaptchaToken,
        )
        method = MiaoHttp.POST
    }

    /**
     * 密码登录
     */
    fun oauth2Login(
        username: String,
        passport: String,
        key: String,
        rhash: String,
    ) = MiaoHttp.request {
        url = "https://passport.bilibili.com/x/passport-login/oauth2/login"
        formBody = ApiHelper.createParams(
            "disable_rcmd" to "0",
            "local_id" to BilimiaoCommApp.commApp.getBilibiliBuvid(),
            "password" to RSAUtil.rsaPassword(passport, key, rhash),
            "username" to username,
        )
        method = MiaoHttp.POST
    }

    /**
     * 短信验证码发送
     */
    fun smsSend(
        tmpCode: String,
        geeChallenge: String,
        geeSeccode: String,
        geeValidate: String,
        recaptchaToken: String,
    ) = MiaoHttp.request{
        url = "https://passport.bilibili.com/x/safecenter/common/sms/send"
        formBody = ApiHelper.createParams(
            // type ：11
            "disable_rcmd" to "0",
            "sms_type" to "loginTelCheck",
            "tmp_code" to tmpCode,
            "gee_challenge" to geeChallenge,
            "gee_seccode" to geeSeccode,
            "gee_validate" to geeValidate,
            "recaptcha_token" to recaptchaToken,
        )
        method = MiaoHttp.POST
    }


    /**
     * 手机号验证登录
     */
    fun telVerify(
        code: String,
        tmpCode: String,
        requestId: String,
        source: String,
        captcha_key: String,
    ) = MiaoHttp.request {
        url = "https://passport.bilibili.com/x/safecenter/login/tel/verify"
        formBody = ApiHelper.createParams(
            "disable_rcmd" to "0",
            "type" to "loginTelCheck",
            "code" to code,
            "tmp_code" to tmpCode,
            "request_id" to requestId,
            "source" to source,
            "captcha_key" to captcha_key,
            "local_id" to BilimiaoCommApp.commApp.getBilibiliBuvid(),
        )
        method = MiaoHttp.POST
    }

    /**
     * 邮箱验证码发送
     */
    fun emailSend(
        tmpCode: String,
        geeChallenge: String,
        geeSeccode: String,
        geeValidate: String,
        recaptchaToken: String,
    ) = MiaoHttp.request{
        url = "https://passport.bilibili.com/x/safecenter/common/email/send"
        formBody = ApiHelper.createParams(
            "type" to "14",
            "tmp_code" to tmpCode,
            "gee_challenge" to geeChallenge,
            "gee_seccode" to geeSeccode,
            "gee_validate" to geeValidate,
            "recaptcha_token" to recaptchaToken,
        )
        method = MiaoHttp.POST
    }

    /**
     * 邮箱验证登录
     */
    fun emailVerify(
        code: String,
        tmpCode: String,
        requestId: String,
        source: String,
        captcha_key: String,
    ) = MiaoHttp.request {
        url = "https://passport.bilibili.com/x/safecenter/sec/verify"
        // verify_type=email
        formBody = ApiHelper.createParams(
            "verify_type" to "email",
            "code" to code,
            "tmp_code" to tmpCode,
            "request_id" to requestId,
            "source" to source,
            "captcha_key" to captcha_key,
            "local_id" to BilimiaoCommApp.commApp.getBilibiliBuvid(),
        )
        method = MiaoHttp.POST
    }

    fun oauth2AccessToken(
        code: String,
    )  = MiaoHttp.request {
        url = "https://passport.bilibili.com/x/passport-login/oauth2/access_token"
        formBody = ApiHelper.createParams(
            "disable_rcmd" to "0",
            "code" to code,
            "local_id" to BilimiaoCommApp.commApp.getBilibiliBuvid(),
            "grant_type" to "authorization_code",
        )
        method = MiaoHttp.POST
    }

    fun tmpUserInfo(
        tmpCode: String,
    ) = MiaoHttp.request {
        url = BiliApiService.createUrl("https://passport.bilibili.com/x/safecenter/user/info",
            "tmp_code" to tmpCode,
        )
    }

    /**
     * 短信验证码登录
     */
    fun smsLogin(
        cid: String, // 国际区号
        tel: String ,
        code: String ,
        captchaKey: String ,
        key: String,
    ) = MiaoHttp.request {
        val rsaKey = key.replace("-----BEGIN PUBLIC KEY-----\n", "")
            .replace("-----END PUBLIC KEY-----\n", "")
        url = "https://passport.bilibili.com/x/passport-login/login/sms"
        formBody = ApiHelper.createParams(
            "cid" to cid,
            "tel" to tel,
            "code" to code,
            "captcha_key" to captchaKey,
            "local_id" to BilimiaoCommApp.commApp.getBilibiliBuvid(),
            "password" to RSAUtil.decryptByPublicKey(ApiHelper.getUUID().substring(0..15), rsaKey),
        )
        method = MiaoHttp.POST
    }

    fun smsLoginSend(
        cid: String, // 国际区号
        tel: String,
        geeChallenge: String,
        geeSeccode: String,
        geeValidate: String,
        recaptchaToken: String,
    ) = MiaoHttp.request {
        url = "https://passport.bilibili.com/x/passport-login/sms/send"
        formBody = ApiHelper.createParams(
            "cid" to cid,
            "tel" to tel,
            "gee_challenge" to geeChallenge,
            "gee_seccode" to geeSeccode,
            "gee_validate" to geeValidate,
            "recaptcha_token" to recaptchaToken,
            "local_id" to BilimiaoCommApp.commApp.getBilibiliBuvid(),
        )
        method = MiaoHttp.POST
    }

    fun smsLoginSend(
        cid: String, // 国际区号
        tel: String,
    ) = MiaoHttp.request {
        url = "https://passport.bilibili.com/x/passport-login/sms/send"
        formBody = ApiHelper.createParams(
            "cid" to cid,
            "tel" to tel,
            "local_id" to BilimiaoCommApp.commApp.getBilibiliBuvid(),
        )
        method = MiaoHttp.POST
    }


    /**
     * 获取登录二维码
     */
    fun qrCode(
        loginSessionId: String
    ) = MiaoHttp.request {
        url = "https://passport.bilibili.com/x/passport-tv-login/qrcode/auth_code"
        formBody = ApiHelper.createParams(
            "local_id" to BilimiaoCommApp.commApp.getBilibiliBuvid(),
            "login_session_id" to loginSessionId,
            "spm_id" to "from_spmid"
        )
        headers["APP-KEY"] = "android_hd"
        method = MiaoHttp.POST
        // Response: QRLoginInfo
    }

    /**
     *
     */
    fun checkQrCode(
        authCode: String
    ) = MiaoHttp.request {
        url = BiliApiService.createUrl("https://passport.bilibili.com/x/passport-tv-login/qrcode/poll")
        formBody = ApiHelper.createParams(
            "local_id" to BilimiaoCommApp.commApp.getBilibiliBuvid(),
            "auth_code" to authCode
        )
        method = MiaoHttp.POST
        // Response: TokenInfo
    }

    fun confirmQRCode(
        authCode: String,
    ) = MiaoHttp.request {
        val cookieManager = CookieManager.getInstance()
        val cookie = cookieManager.getCookie("https://passport.bilibili.com/") ?: ""
        val keyValueArr = cookie.split(";").map {
            it.trimIndent()
        }
        val biliJct = keyValueArr.find { it.startsWith("bili_jct=") }?.let {
            it.substring(9, it.length)
        } ?: ""
        url = "https://passport.bilibili.com/x/passport-tv-login/h5/qrcode/confirm"
        method = MiaoHttp.POST
        formBody = ApiHelper.createParams(
            "auth_code" to authCode,
            "csrf" to biliJct,
            "build" to "7082000",
            "statistics" to """{"appId":6,"platform":3,"version":"7.82.0","abtest":""}""",
            appKey = "27eb53fc9058f8c3",
            appSecrer = "c2ed53a74eeefe3cf99fbd01d8c9c375"
        )

        headers["cookie"] = cookie
    }
}

/**
 * `x/web-interface/nav` 的响应体（只取登录判断与展示要用的字段）。
 *
 * 放在这里而不是 `entity/`：它只服务于 [AuthApi.webNav] 这条"Cookie 登录"的路径。
 * 每个字段都给默认值 —— nav 字段极多且会变，少一个不该让整次解析失败
 * （同 `LiveSelfNickname.kt` 里那份私有 DTO 的写法）。
 */
@Serializable
data class WebNavInfo(
    val isLogin: Boolean = false,
    val mid: Long = 0,
    val uname: String = "",
    val face: String? = null,
    /** 硬币数（nav 里叫 money；★它是**小数**，样例 `172.4` —— 写成 Int 会让带小数的账号整次解析失败） */
    val money: Double = 0.0,
    val level_info: LevelInfo? = null,
    val vipStatus: Int = 0,
    val vipType: Int = 0,
) {
    @Serializable
    data class LevelInfo(
        val current_level: Int = 0,
        /**
         * 当前经验 / 升级所需经验。
         *
         * ★nav 原样就有（样例 `level_info{current_level:5, current_min:10800, current_exp:13135, next_exp:28800}`），
         *   有了这两个值，"经验"那一行就不必再去请求一个动辄被风控的空间接口。
         * ★用 [JsonElement] 收：满级时 `next_exp` 见过 `"--"`，写死 Int/Long 会让整次 nav 解析失败。
         */
        val current_exp: JsonElement? = null,
        val next_exp: JsonElement? = null,
    )
}

/**
 * 经验字段转数字：数字与纯数字字符串都认；`"--"`、null、对象等一律 null（= 这项拿不到）。
 *
 * ★放在 comm（nav 与空间接口共用同一把尺子），页面别自己再写一份。
 */
fun JsonElement?.asExpNumber(): Long? =
    (this as? JsonPrimitive)?.content?.trim()?.toLongOrNull()

/**
 * nav 响应 → App 的 [UserInfo]（Cookie 登录 / cookie-only 会话的资料刷新共用这一份映射）。
 *
 * nav 只给"网页侧"字段：`uname`/`face`/`money`(硬币)/`level_info.current_level`/`vipType` 都是真值；
 * App 专属字段（关注数/粉丝数/性别/等级外的各种计数）它不给，只能填默认值 —— 这不是造假，
 * 而是 Cookie 通道拿不到（要 App 接口 + access_token 才有）。
 *
 * ★放在这里而不是各处自己拼：Cookie 登录页与 `UserStore` 的冷启动刷新必须是同一套口径。
 */
fun WebNavInfo.toUserInfo(): UserInfo = UserInfo(
    mid = mid,
    name = uname,
    face = face ?: "",
    coin = money,
    bcoin = 0.0,
    sex = 0,
    rank = 0,
    silence = 0,
    show_videoup = 0,
    show_creative = 0,
    level = level_info?.current_level ?: 0,
    vip_type = vipType,
    audio_type = 0,
    dynamic = 0,
    following = 0,
    follower = 0,
)