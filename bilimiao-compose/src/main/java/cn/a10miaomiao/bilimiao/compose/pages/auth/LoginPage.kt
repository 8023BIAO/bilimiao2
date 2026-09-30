package cn.a10miaomiao.bilimiao.compose.pages.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.R
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.components.dialogs.MessageDialogState
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.apis.WebNavInfo
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.auth.LoginInfo
import com.a10miaomiao.bilimiao.comm.entity.auth.WebKeyInfo
import com.a10miaomiao.bilimiao.comm.entity.user.UserInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.toast
import com.a10miaomiao.bilimiao.comm.utils.BiliGeetestUtil
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONObject
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@Serializable
class LoginPage : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: LoginPageViewModel = diViewModel()
        LaunchedEffect(Unit) {
            viewModel.checkLogin()
        }
        LoginPageContent(viewModel)
    }

}

private class LoginPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware, BiliGeetestUtil.GTCallBack {

    private var verifyUrl = ""
    private var recaptchaToken = ""

    private val pageNavigation by instance<PageNavigation>()
    private val userStore by instance<UserStore>()
    private val biliGeetestUtil by instance<BiliGeetestUtil>()
    private val messageDialog by instance<MessageDialogState>()

    val loading = MutableStateFlow(false)
    val userName = MutableStateFlow("")
    val password = MutableStateFlow("")

    fun setUserName(value: String) {
        userName.value = value
    }

    fun setPassword(value: String) {
        password.value = value
    }

    fun startLogin(
        gt3Result: BiliGeetestUtil.GT3ResultBean? = null,
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            if (userName.value.isBlank()) {
                messageDialog.alert("请输入用户名/邮箱/手机号")
                return@launch
            }
            if (password.value.isBlank()) {
                messageDialog.alert("请输入密码")
                return@launch
            }
            loading.value = true
            val webKey = getWebKey()
            val res = if (gt3Result == null) {
                // 不带验证码
                BiliApiService.authApi.oauth2Login(
                    username = userName.value,
                    passport = password.value,
                    key = webKey.key,
                    rhash = webKey.hash
                )
            } else {
                // 带验证码
                BiliApiService.authApi.oauth2Login(
                    username = userName.value,
                    passport = password.value,
                    key = webKey.key,
                    rhash = webKey.hash,
                    recaptchaToken = recaptchaToken,
                    geeValidate = gt3Result.geetest_validate,
                    geeSeccode = gt3Result.geetest_seccode,
                    geeChallenge = gt3Result.geetest_challenge,
                )
            }.awaitCall().json<ResponseData<LoginInfo.PasswordLoginInfo>>()
            withContext(Dispatchers.Main) {
                if (res.isSuccess) {
                    val loginInfo = res.requireData()
                    if (loginInfo.status == 0) {
                        BilimiaoCommApp.commApp.saveAuthInfo(loginInfo.toLoginInfo())
                        authInfo()
                    } else if (loginInfo.url != null && "tmp_token=" in loginInfo.url) {
                        messageDialog.open(
                            title = "提示",
                            text = loginInfo.message,
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        val params = UrlUtil.getQueryKeyValueMap(Uri.parse(loginInfo.url))
                                        if (params.containsKey("tmp_token")
                                            && params.containsKey("request_id")
                                            && params.containsKey("source")
                                        ) {
                                            pageNavigation.navigate(TelVerifyPage(
                                                code = params["tmp_token"] ?: "",
                                                requestId = params["request_id"] ?: "",
                                                source = params["source"] ?: "",
                                            ))
                                        } else {
                                            pageNavigation.launchWebBrowser(loginInfo.url)
                                        }
                                        messageDialog.close()
                                    }
                                ) {
                                    Text("请往验证")
                                }
                            }
                        )
                    } else {
                        messageDialog.open(
                            title = "登录失败，请稍后重试：" + loginInfo.status,
                            text = loginInfo.message,
                            confirmButton = {
                                if (!loginInfo.url.isNullOrBlank()) {
                                    TextButton(
                                        onClick = {
                                            pageNavigation.launchWebBrowser(loginInfo.url)
                                        }
                                    ) {
                                        Text("查看")
                                    }
                                }
                            }
                        )
                    }
                } else if (res.code == -105 && gt3Result == null) {
                    verifyUrl = res.data!!.url ?: ""
                    biliGeetestUtil.startCustomFlow(this@LoginPageViewModel)
                } else {
                    messageDialog.alert(res.message)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            messageDialog.alert(e.message ?: e.toString())
        } finally {
            loading.value = false
        }
    }

    private suspend fun getWebKey(): WebKeyInfo {
        val res = BiliApiService.authApi
            .webKey()
            .awaitCall()
            .json<ResponseData<WebKeyInfo>>()
        if (res.isSuccess) {
            return res.requireData()
        }
        throw Exception(res.message)
    }

    private suspend fun authInfo() {
        val res = withContext(Dispatchers.IO) {
            BiliApiService.authApi
                .account()
                .awaitCall()
                .json<ResponseData<UserInfo>>()
        }
        if (res.isSuccess) {
            withContext(Dispatchers.Main) {
                userStore.setUserInfo(res.requireData())
                pageNavigation.popBackStack()
            }
        } else {
            throw Exception(res.message)
        }
    }

    fun checkLogin() {
        if (userStore.isLogin()) {
            pageNavigation.popBackStack()
        }
    }

    fun toHome() {
        pageNavigation.popBackStack()
    }

    override suspend fun onGTDialogResult(
        result: BiliGeetestUtil.GT3ResultBean
    ): Boolean {
        startLogin(result)
        return true
    }

    /**
     * 取极验验证码参数（`gt` + `challenge`）。
     *
     * ★契约：**参数不全一律返回 null** —— 没有 `recaptcha_token`，或 `gee_gt`/`gee_challenge` 任一为空
     *   （原来会返回一个缺字段的 `JSONObject`，验证码页 `getString` 一抛就是白屏）。
     *   调用方 `GeetestValidatorActivity` **必须**据此走页内兜底提示 + 重试；这里**不要**往下层页面弹
     *   alert（用户看到的是验证码页，弹在背后等于没提示）。以后新增调用方也按这个契约处理 null。
     */
    override suspend fun getGTApiJson(): JSONObject? {
        val queryMap = UrlUtil.getQueryKeyValueMap(Uri.parse(verifyUrl))
        if (!queryMap.containsKey("recaptcha_token")) {
            // ★这里**不再**往下层页面弹 alert：此刻用户看到的是验证码页，弹在背后等于没提示
            //   （他只会看到一张白页）。返回 null，让验证码页自己的兜底给提示 + 重试。
            return null
        }
        recaptchaToken = queryMap["recaptcha_token"] ?: ""
        val gt = queryMap["gee_gt"].orEmpty()
        val challenge = queryMap["gee_challenge"].orEmpty()
        // ★参数不全也当失败返回 null：别把缺 gt/challenge 的 JSON 交给验证码页
        //   （那边只会 loadUrl 出一个永远画不出来的页面 = 白屏）
        if (gt.isBlank() || challenge.isBlank()) return null
        return JSONObject().apply {
            put("success", 1)
            put("challenge", challenge)
            put("gt", gt)
        }
    }

    fun toH5LoginPage() {
        pageNavigation.navigate(H5LoginPage())
    }

    fun toQrLogin() {
        pageNavigation.navigate(QrCodeLoginPage())
    }

    fun toSMSLogin() {
        pageNavigation.navigate(SMSLoginPage())
    }

    /**
     * Token / Cookie 直接登录。
     *
     * - 输入里出现 `SESSDATA`（或分号分隔的多段 Cookie）→ 按 Cookie 处理；
     *   先用 `x/web-interface/nav` **只带这段 Cookie** 验证（不写全局 CookieManager），通过才落盘。
     * - 其余按 `access_token` 处理：`account()` 的 token 版验证通过后写 auth 文件。
     *
     * ★Cookie 只对 Web 接口生效（APP 接口只带 Cookie 时返回 `mid=0`，实测），所以这里如实提示限制。
     */
    fun loginByTokenOrCookie(raw: String, onSuccess: () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val input = raw.trim()
        if (input.isBlank()) {
            messageDialog.alert("请输入 access_token 或 Cookie")
            return@launch
        }
        val isCookie = input.contains("SESSDATA", ignoreCase = true) || input.contains(";")
        loading.value = true
        try {
            if (isCookie) {
                val cookies = parseCookie(input)
                if (cookies.isEmpty()) {
                    messageDialog.alert("Cookie 格式不对，应形如 SESSDATA=xxx; bili_jct=yyy")
                    return@launch
                }
                val res = BiliApiService.authApi
                    .webNav(cookies.joinToString("; ") { "${it.name}=${it.value}" })
                    .awaitCall()
                    .json<ResponseData<WebNavInfo>>()
                val navInfo = res.data
                val mid = navInfo?.mid ?: 0L
                // 拿不到真实资料就不写"已登录"（宁可不写，也不写假档案）
                if (!res.isSuccess || navInfo == null || !navInfo.isLogin || mid == 0L) {
                    messageDialog.alert("Cookie 无效或已过期" + res.message.toErrorSuffix())
                    return@launch
                }
                val cookieInfo = LoginInfo.CookieInfo(
                    cookies = cookies,
                    domains = listOf(".bilibili.com", "bilibili.com"),
                )
                BilimiaoCommApp.commApp.setCookie(cookieInfo)
                BilimiaoCommApp.commApp.saveAuthInfo(
                    LoginInfo(
                        // Cookie 登录没有 access_token：只留 mid 让界面能显示账号
                        token_info = LoginInfo.TokenInfo(
                            access_token = "",
                            refresh_token = "",
                            mid = mid,
                            expires_in = 0,
                        ),
                        sso = null,
                        cookie_info = cookieInfo,
                    )
                )
                // 资料全部来自 nav 的真实返回；nav 不提供的字段只能给默认值
                // （这些字段 App 接口才给，Cookie 通道拿不到）
                val user = UserInfo(
                    mid = mid,
                    name = navInfo.uname,
                    face = navInfo.face ?: "",
                    coin = navInfo.money.toDouble(),
                    bcoin = 0.0,
                    sex = 0,
                    rank = 0,
                    silence = 0,
                    show_videoup = 0,
                    show_creative = 0,
                    level = navInfo.level_info?.current_level ?: 0,
                    vip_type = navInfo.vipType,
                    audio_type = 0,
                    dynamic = 0,
                    following = 0,
                    follower = 0,
                )
                withContext(Dispatchers.Main) {
                    userStore.setUserInfo(user)
                    // 如实说清楚：Cookie 只覆盖网页接口，且冷启动会被 UserStore 的 App 接口判定为失效
                    toast("Cookie 已保存：仅网页接口生效，重启后需重新登录")
                    onSuccess()
                    pageNavigation.popBackStack()
                }
            } else {
                val res = BiliApiService.authApi
                    .accountByToken(input)
                    .awaitCall()
                    .json<ResponseData<UserInfo>>()
                val user = res.data
                if (!res.isSuccess || user == null || user.mid == 0L) {
                    messageDialog.alert("Token 无效或已过期" + res.message.toErrorSuffix())
                    return@launch
                }
                BilimiaoCommApp.commApp.saveAuthInfo(
                    LoginInfo(
                        token_info = LoginInfo.TokenInfo(
                            access_token = input,
                            refresh_token = "",
                            mid = user.mid,
                            expires_in = 0,
                        ),
                        sso = null,
                        cookie_info = null,
                    )
                )
                withContext(Dispatchers.Main) {
                    userStore.setUserInfo(user)
                    toast("已通过 Token 登录")
                    onSuccess()
                    pageNavigation.popBackStack()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            messageDialog.alert("登录失败：" + (e.message ?: e.toString()))
        } finally {
            loading.value = false
        }
    }

    /** 失败提示里带上服务端 message（`message == "0"` 这种没信息量的就不带） */
    private fun String.toErrorSuffix(): String =
        if (isBlank() || this == "0") "" else "：$this"

    /** `SESSDATA=x; bili_jct=y` → Cookie 列表（忽略空段和没有 `=` 的段） */
    private fun parseCookie(raw: String): List<LoginInfo.Cookie> = raw.split(";").mapNotNull { pair ->
        val index = pair.indexOf('=')
        if (index <= 0) return@mapNotNull null
        val name = pair.substring(0, index).trim()
        val value = pair.substring(index + 1).trim()
        if (name.isEmpty() || value.isEmpty()) {
            null
        } else {
            LoginInfo.Cookie(name = name, value = value, expires = 0, http_only = 0)
        }
    }
}

@Composable
private fun LoginPageContent(
    viewModel: LoginPageViewModel
) {
    PageConfig(title = "登录BILIBILI")
    val userStore: UserStore by rememberInstance()
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(LocalView.current)
    val bottomAppBarHeight = windowStore.bottomAppBarHeightDp

    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val userName by viewModel.userName.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()
    val passwordFocusRequester = remember { FocusRequester() }
    var passwordIsFocus by remember { mutableStateOf(false) }
    var showTokenLoginDialog by remember { mutableStateOf(false) }

    val usernameKeyboardActions = remember(passwordFocusRequester) {
        KeyboardActions(
            onNext = {
                passwordFocusRequester.requestFocus()
            }
        )
    }
    val passwordKeyboardActions = remember(viewModel) {
        KeyboardActions(
            onDone = {
                viewModel.startLogin()
            }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(80.dp)
                .padding(
                    top = windowInsets.topDp.dp,
                    bottom = windowInsets.bottomDp.dp,
                    start = windowInsets.leftDp.dp,
                    end = windowInsets.rightDp.dp,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (passwordIsFocus) {
                Image(
                    painter = painterResource(id = R.drawable.ic_22_hide),
                    contentDescription = "22娘遮眼",
                )
                Image(
                    painter = painterResource(id = R.drawable.ic_33_hide),
                    contentDescription = "33娘遮眼",
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.ic_22),
                    contentDescription = "22娘",
                )
                Image(
                    painter = painterResource(id = R.drawable.ic_33),
                    contentDescription = "33娘",
                )
            }
        }
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 10.dp)
        ) {
            Spacer(modifier = Modifier.height(windowInsets.topDp.dp))
            Box(
                modifier = Modifier
                    .height(90.dp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "登录Bilibili",
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            TextField(
                label = {
                    Text(text = "用户名/邮箱/手机号")
                },
                value = userName,
                onValueChange = viewModel::setUserName,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = usernameKeyboardActions,
            )
            Spacer(modifier = Modifier.height(10.dp))
            TextField(
                label = {
                    Text(text = "密码")
                },
                value = password,
                onValueChange = viewModel::setPassword,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocusRequester)
                    .onFocusChanged { passwordIsFocus = it.isFocused },
                singleLine = true,
                // 显示密码样式
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                // 显示密码样式
                visualTransformation = PasswordVisualTransformation(),
                keyboardActions = passwordKeyboardActions,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = viewModel::startLogin,
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    modifier = Modifier.padding(horizontal = 5.dp),
                    text = "登录"
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                // 三个入口 + 窄屏：用 SpaceEvenly 均分，别用固定 20dp 间距（360dp 屏会挤到裁字）
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = viewModel::toSMSLogin) {
                    Text(text = "手机号登录")
                }
                TextButton(onClick = viewModel::toQrLogin) {
                    Text(text = "二维码登录")
                }
                TextButton(onClick = { showTokenLoginDialog = true }) {
                    Text(text = "Token 登录")
                }
            }
            Spacer(modifier = Modifier.height(windowInsets.bottomDp.dp + bottomAppBarHeight.dp))

        }
        if (showTokenLoginDialog) {
            TokenLoginDialog(
                loading = loading,
                onDismiss = { showTokenLoginDialog = false },
                onConfirm = { input ->
                    viewModel.loginByTokenOrCookie(input) {
                        showTokenLoginDialog = false
                    }
                },
            )
        }
    }

}