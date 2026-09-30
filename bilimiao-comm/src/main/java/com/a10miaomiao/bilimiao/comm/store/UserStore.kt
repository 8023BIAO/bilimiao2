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
import com.a10miaomiao.bilimiao.comm.entity.ResponseResult
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.user.UserInfo
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.base.BaseStore
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
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

    fun loadInfo() = viewModelScope.launch(Dispatchers.IO) {
        // ★cookie-only 会话（auth 文件里没有 access_token，只有 Cookie）：APP 接口
        //   `x/v2/account/mine` 只会回匿名档（实测 code=0 但 mid=0/name 空）——那不是"登录失效"，
        //   是这条通道根本不认 Cookie。所以改走 Web 的 nav 取资料（见 [AuthApi.webNav]）。
        if (BilimiaoCommApp.commApp.loginInfo?.token_info?.access_token.isNullOrBlank()) {
            loadInfoByCookie()
            return@launch
        }
        try {
            val res = BiliApiService.authApi
                .account()
                .awaitCall()
                .json<ResponseData<UserInfo>>()
            val user = res.data
            when {
                res.code == 0 && user != null && user.mid != 0L -> {
                    setState {
                        info = user
                    }
                    seveUserInfo(user)
                }
                // 服务端**明确**判未登录才算失效：实测 access_key 无效时 APP 接口回 code=0 + 匿名档（mid=0），
                // 不是 -101；`data` 缺失（hasPayload=false）不算 —— 那是响应不完整，别误清。
                isExplicitNotLogin(res.code, hasPayload = user != null, notLoggedIn = user?.mid == 0L) -> {
                    clearAuthAndNotify()
                }
                // 其余（-352 风控、-509 超限、-412 拦截等）是临时状态：保留缓存资料，别误判失效
                else -> toast("网络请求失败")
            }
        } catch (e: Exception) { 
            toast("网络请求失败")
            e.printStackTrace()
        }
    }

    /**
     * cookie-only 会话的资料刷新（走 Web 的 nav）。
     *
     * ★判据分级，绝不把"网络失败"当成"登录失效"：
     * · 拿到有效资料（isLogin && mid != 0）→ 正常置登录态并落盘 user.data；
     * · **服务端明确说未登录**（code=0 且 isLogin=false / mid=0）→ 才清空 + 「登录已失效」；
     * · 非 0 code（风控/临时错误）或抛异常 → **保留缓存资料** + 「网络请求失败」。
     */
    private suspend fun loadInfoByCookie() {
        val cookie = cookieHeaderForNav()
        if (cookie == null) {
            // 连能用的 Cookie 都没有了，这时才算真的失效
            clearAuthAndNotify()
            return
        }
        try {
            val res = BiliApiService.authApi
                .webNav(cookie)
                .awaitCall()
                .json<ResponseData<WebNavInfo>>()
            val nav = res.data
            when {
                res.isSuccess && nav != null && nav.isLogin && nav.mid != 0L -> {
                    val user = nav.toUserInfo()
                    setState { info = user }
                    seveUserInfo(user)
                }
                // 服务端**明确**说没登录，才算失效：
                // · Cookie 无效/过期：nav 回 code=-101「账号未登录」（实测，无 Cookie 与假 SESSDATA 都是它）
                // · nav 对"未登录"也可能回 code=0 + isLogin=false
                // ★`code=0` 但 `data` 缺失（nav==null）时**不算** —— 响应不完整 ≠ 服务端判失效
                isExplicitNotLogin(res.code, hasPayload = nav != null, notLoggedIn = nav?.isLogin == false) -> {
                    clearAuthAndNotify()
                }
                // 其余（-352 风控、-509 超限、-412 拦截等）是临时状态：保留缓存，别误判失效
                else -> toast("网络请求失败")
            }
        } catch (e: Exception) {
            toast("网络请求失败")
            e.printStackTrace()
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
     * 明确失效时的统一收尾：清登录态 **并同步 auth 文件 / Cookie**。
     *
     * ★必须连 [BilimiaoCommApp.deleteAuth] 一起做：只清 `state.info` 的话 `commApp.loginInfo`
     *   仍然非空，于是 ①设置页「导出身份信息」（按 `userState.isLogin()` 判）会把**已失效**的
     *   凭据导出去，②设置页「开启游客模式」那一行（按 `commApp.loginInfo != null` 判）也仍然显示 ——
     *   同一次失效在两个事实源上分叉（2026-10-01 复核第 3 轮必改）。
     */
    private fun clearAuthAndNotify() {
        BilimiaoCommApp.commApp.deleteAuth()
        setState { info = null }
        toast("登录已失效，请重新登录")
    }

    /**
     * cookie-only 会话刷新资料时用的 Cookie：优先用 auth 文件里存的那份（冷启动时
     * [BilimiaoCommApp.readAuthInfo] 会把它灌回 CookieManager，但外部清过 CookieManager 时
     * 文件里那份仍在），取不到再回落到 CookieManager。
     *
     * 必须含 `SESSDATA`：只有指纹 Cookie 的"会话"在 Web 侧等于没登录。
     */
    private fun cookieHeaderForNav(): String? {
        val fromFile = BilimiaoCommApp.commApp.loginInfo?.cookie_info?.cookies
            ?.mapNotNull { c -> c.value.takeIf { it.isNotBlank() }?.let { "${c.name}=$it" } }
            ?.takeIf { pairs -> pairs.any { it.startsWith("SESSDATA=") } }
            ?.joinToString("; ")
        if (fromFile != null) return fromFile
        return try {
            CookieManager.getInstance()
                .getCookie("https://api.bilibili.com")
                ?.takeIf { it.contains("SESSDATA") }
        } catch (e: Exception) {
            null
        }
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