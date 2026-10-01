package com.a10miaomiao.bilimiao.comm.store

import android.content.Context
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.apis.WebNavInfo
import com.a10miaomiao.bilimiao.comm.apis.toUserInfo
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.auth.LoginInfo
import com.a10miaomiao.bilimiao.comm.entity.user.UserInfo
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.base.BaseStore
import com.a10miaomiao.bilimiao.comm.toast
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance
import java.io.File

class UserStore(override val di: DI) :
    ViewModel(), BaseStore<UserStore.State> {

    data class State (
        var info: UserInfo? = null
    ) {
        fun isSelf(mid: String) = info?.mid != null && info?.mid == mid.toLongOrNull()

        fun isSelf(mid: Long) = info?.mid == mid

        fun isLogin() = info != null

        fun isVip() = (info?.vip_type ?: 0) > 0
    }

    override val stateFlow = MutableStateFlow(State())
    override fun copyState() = state.copy()

    private val activity: AppCompatActivity by instance()

    private val messageStore: MessageStore by instance()

    override fun init(context: Context) {
        super.init(context)
        if (BilimiaoCommApp.commApp.loginInfo != null)  {
            readUserInfo()
            loadInfo()
            messageStore.getUnreadMessage()
        }
    }

    fun setUserInfo(userInfo: UserInfo?) {
        setState {
            info = userInfo
        }
        seveUserInfo(userInfo)
        if (userInfo != null) {
            messageStore.getUnreadMessage()
        }
    }

    fun logout () {
        BilimiaoCommApp.commApp.deleteAuth()
        setUserInfo(null)
        // 未读角标要一起清掉：那是上一个账号的，登出后还挂在首页
        try { messageStore.clearUnread() } catch (e: Exception) { }
    }

    private fun seveUserInfo(userInfo: UserInfo?) {
        val file = File(activity.filesDir.path + "/user.data")
        if (userInfo != null) {
            val jsonStr = MiaoJson.toJson(userInfo)
            file.writeText(jsonStr)
        } else {
            file.delete()
        }
    }

    private fun readUserInfo() {
        try {
            val file = File(activity.filesDir.path + "/user.data")
            if (file.exists()) {
                val jsonStr = file.readText()
                val localInfo = MiaoJson.fromJson<UserInfo>(jsonStr)
                setState {
                    info = localInfo
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 刷新登录态与用户资料（冷启动 / 导入后 / 登录后都会走）。
     *
     * 口径（2026-10-01 用户实测后重构，**别再退回"失败就删凭据"**）：
     * ① 每次先把 auth 文件里的 Cookie **真正**灌回 CookieManager（见 [BilimiaoCommApp.setCookie]）；
     * ② 有 `access_token` 走 APP `x/v2/account/mine`，没有就走 Web `nav`；
     * ③ **token 失效 ≠ 会话失效**：APP 判未登录时，只要身份里还有含 SESSDATA 的 Cookie，先回落 Cookie 再判；
     * ④ 两条都"明确未登录" → **只清内存状态 + 如实提示**，绝不删 auth 文件（凭据只能由"主动退出登录"删）。
     */
    fun loadInfo() = viewModelScope.launch(Dispatchers.IO) {
        val loginInfo = BilimiaoCommApp.commApp.loginInfo
        // 冷启动把 Cookie 灌回 WebView：Web 接口（发弹幕/评论要的 csrf 等）读的是 CookieManager
        loginInfo?.cookie_info?.let { BilimiaoCommApp.commApp.setCookie(it) }
        val token = loginInfo?.token_info?.access_token
        val cookie = cookieHeaderForNav()
        try {
            var probe = if (token.isNullOrBlank()) {
                if (cookie == null) {
                    clearStateAndNotify("身份里没有 access_token，也没有可用的 Cookie")
                    return@launch
                }
                probeNav(cookie)
            } else {
                probeApp()
            }
            // ★token 失效要回落 Cookie：旧导出/双凭据的身份常常还留着一份活着的 Cookie
            if (probe.user == null && probe.explicitNotLogin && !token.isNullOrBlank() && cookie != null) {
                logAuthDiag("token失效回落Cookie", token, probe)
                probe = probeNav(cookie)
            }
            when {
                probe.user != null -> {
                    setState { info = probe.user }
                    seveUserInfo(probe.user)
                    logAuthDiag("登录态正常", token, probe)
                }
                probe.explicitNotLogin -> clearStateAndNotify(
                    "服务端返回未登录（来源=${probe.source}；access_token" +
                        (if (token.isNullOrBlank()) "为空" else "已失效") + "）"
                )
                // 其余（-352 风控、-509 超限、-412 拦截、网络异常）都是临时状态：保留缓存资料
                else -> {
                    logAuthDiag("刷新失败(临时)", token, probe)
                    toast("网络请求失败")
                }
            }
        } catch (e: Exception) {
            logAuthDiag("刷新异常", token, null)
            toast("网络请求失败")
            e.printStackTrace()
        }
    }

    /**
     * 现场验真一份 [LoginInfo]（导入身份后**立刻**校验用）：有 `access_token` 走 APP，
     * 否则用它自己的 `cookie_info` 走 Web nav。**不改全局状态、不写文件**，只回结果。
     */
    suspend fun probeLoginInfo(loginInfo: LoginInfo?): AuthProbe {
        val token = loginInfo?.token_info?.access_token
        if (!token.isNullOrBlank()) {
            // ★用**这份候选身份自己的 token** 走 accountByToken（asGuest=true，只带它自己的凭据）：
            //   导入是"先验真、后落盘"，此刻全局还挂着用户原来的身份 —— 借全局 APP 通道会验错对象。
            var probe = probeAppByToken(token)
            if (probe.user == null && probe.explicitNotLogin) {
                cookieHeaderOf(loginInfo)?.let { cookie ->
                    logAuthDiag("导入校验:token失效回落Cookie", token, probe)
                    probe = probeNav(cookie)
                }
            }
            return probe
        }
        val cookie = cookieHeaderOf(loginInfo)
            ?: return AuthProbe(
                source = "cookie",
                explicitNotLogin = true,
                reason = "文件里没有可用的 Cookie（缺少 SESSDATA）",
            )
        return probeNav(cookie)
    }

    /**
     * 用**指定** access_token 的 APP 探针（导入候选身份用）：
     * `x/v2/account/mine` + `access_key` + `notoken=1` + `Authorization: identify_v1`，`asGuest = true`
     * 保证只带这份 token，不受当前全局登录态影响。
     */
    private suspend fun probeAppByToken(token: String): AuthProbe {
        val res = BiliApiService.authApi
            .accountByToken(token)
            .awaitCall()
            .json<ResponseData<UserInfo>>()
        val user = res.data
        return when {
            res.code == 0 && user != null && user.mid != 0L ->
                AuthProbe(user = user, code = res.code, source = "app:token")
            isExplicitNotLogin(res.code, hasPayload = user != null, notLoggedIn = user?.mid == 0L) ->
                AuthProbe(code = res.code, source = "app:token", explicitNotLogin = true, reason = "服务端返回未登录")
            else -> AuthProbe(code = res.code, source = "app:token", reason = res.message)
        }
    }

    /** APP 通道探针（`x/v2/account/mine`） */
    private suspend fun probeApp(): AuthProbe {
        val res = BiliApiService.authApi
            .account()
            .awaitCall()
            .json<ResponseData<UserInfo>>()
        val user = res.data
        return when {
            res.code == 0 && user != null && user.mid != 0L ->
                AuthProbe(user = user, code = res.code, source = "app")
            isExplicitNotLogin(res.code, hasPayload = user != null, notLoggedIn = user?.mid == 0L) ->
                AuthProbe(code = res.code, source = "app", explicitNotLogin = true, reason = "服务端返回未登录")
            else -> AuthProbe(code = res.code, source = "app", reason = res.message)
        }
    }

    /** Web 通道探针（`x/web-interface/nav` + 指定 Cookie 串） */
    private suspend fun probeNav(cookie: String): AuthProbe {
        val res = BiliApiService.authApi
            .webNav(cookie)
            .awaitCall()
            .json<ResponseData<WebNavInfo>>()
        val nav = res.data
        return when {
            res.isSuccess && nav != null && nav.isLogin && nav.mid != 0L ->
                AuthProbe(user = nav.toUserInfo(), code = res.code, source = "web")
            isExplicitNotLogin(res.code, hasPayload = nav != null, notLoggedIn = nav?.isLogin == false) ->
                AuthProbe(code = res.code, source = "web", explicitNotLogin = true, reason = "服务端返回未登录")
            else -> AuthProbe(code = res.code, source = "web", reason = res.message)
        }
    }

    /**
     * 服务端是否**明确**判"未登录"（只有这两种，其余一律当临时状态）。
     *
     * 实测口径：
     * · APP `x/v2/account/mine`：access_key 无效/缺失 → `code=0` + 匿名档（`mid=0`），不是 -101；
     * · Web `x/web-interface/nav`：无 Cookie / 失效 SESSDATA → `code=-101`「账号未登录」。
     * ★`code=0` 但 `hasPayload=false`（响应里没有 data）不算 —— 那是响应不完整，别误清登录态。
     */
    private fun isExplicitNotLogin(code: Int, hasPayload: Boolean, notLoggedIn: Boolean): Boolean =
        code == CODE_NOT_LOGIN || (code == 0 && hasPayload && notLoggedIn)

    /**
     * 明确失效：**只清内存状态** + 如实提示。
     *
     * ★这里**绝不**删 auth 文件 / Cookie（`deleteAuth()` 只允许出现在"用户主动退出登录"）。
     *   2026-10-01 用户实测的"导入成功、下次启动身份被静默删除"就是这一步造成的：
     *   凭据一删，用户连重试 / 换文件的机会都没有。防"失效凭据被导出"改用**导出前判 `state.info`**。
     */
    private fun clearStateAndNotify(reason: String) {
        miaoLogger().e("登录态失效(仅清内存，保留凭据)", reason)
        setState { info = null }
        toast("登录已失效，请重新登录")
    }

    /**
     * 登录态诊断日志：**只打"有没有 / 前 6 位 / 长度"**，绝不落完整凭据。
     *
     * 目的是让"登录/导入为什么没生效"一眼可查（用户实测反馈渠道就是截图 + 日志）。
     */
    fun logAuthDiag(tag: String, token: String?, probe: AuthProbe?) {
        fun masked(name: String): String {
            val value = runCatching { MiaoHttp.cookieValue(name) }.getOrNull()
            return if (value.isNullOrBlank()) "$name=无" else "$name=${value.take(6)}…(len=${value.length})"
        }
        miaoLogger().e(
            "AuthDiag[$tag]",
            "access_token=" + (if (token.isNullOrBlank()) "空" else "有(len=${token.length})"),
            masked("SESSDATA"),
            masked("bili_jct"),
            "probe=" + (probe?.let {
                "source=${it.source} code=${it.code} ok=${it.ok} explicit=${it.explicitNotLogin} reason=${it.reason}"
            } ?: "无"),
        )
    }

    /** 从一份 LoginInfo 里拼出含 `SESSDATA` 的 Cookie 串；没有就 null */
    private fun cookieHeaderOf(loginInfo: LoginInfo?): String? =
        loginInfo?.cookie_info?.cookies
            ?.mapNotNull { c -> c.value.takeIf { it.isNotBlank() }?.let { "${c.name}=$it" } }
            ?.takeIf { pairs -> pairs.any { it.startsWith("SESSDATA=") } }
            ?.joinToString("; ")

    /**
     * cookie-only 会话刷新资料时用的 Cookie：优先用 auth 文件里存的那份（auth 文件是权威，
     * 外部清过 CookieManager 时它仍在），取不到再回落到 CookieManager。
     *
     * 必须含 `SESSDATA`：只有指纹 Cookie 的"会话"在 Web 侧等于没登录。
     */
    private fun cookieHeaderForNav(): String? =
        cookieHeaderOf(BilimiaoCommApp.commApp.loginInfo)
            ?: try {
                CookieManager.getInstance()
                    .getCookie("https://api.bilibili.com")
                    ?.takeIf { it.contains("SESSDATA") }
            } catch (e: Exception) {
                null
            }

    fun sso() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val res = BiliApiService.authApi
                .sso()
                .awaitCall()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isSelf(mid: String) = state.info?.mid != null && state.info?.mid == mid.toLongOrNull()

    fun isLogin() = state.info != null

    fun isVip() = (state.info?.vip_type ?: 0) > 0

    companion object {
        /** 服务端明确的"未登录"（nav 用无 Cookie / 失效 Cookie 请求时的返回码） */
        private const val CODE_NOT_LOGIN = -101
    }

}

/** 登录态探针结果（只用于判断 / 提示 / 日志，不参与持久化） */
data class AuthProbe(
    val user: UserInfo? = null,
    val code: Int = 0,
    /** `app` = 全局 APP 通道；`app:token` = 指定 token 的 APP 通道；`web` = Web nav；`cookie` = 连 Cookie 都没有 */
    val source: String = "",
    /** 服务端**明确**判未登录（区别于 -352 这类临时错误） */
    val explicitNotLogin: Boolean = false,
    val reason: String = "",
) {
    val ok: Boolean get() = user != null
}
