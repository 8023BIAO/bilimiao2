package cn.a10miaomiao.bilimiao.compose.pages.auth

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.pages.home.HomePage
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.auth.LoginInfo
import com.a10miaomiao.bilimiao.comm.entity.auth.QRLoginInfo
import com.a10miaomiao.bilimiao.comm.entity.user.UserInfo
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.string
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.toast
import com.a10miaomiao.bilimiao.comm.utils.ImageSaveUtil
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.store.WindowStore
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import io.github.alexzhirkevich.qrose.toImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
class QrCodeLoginPage : ComposePage() {

    @Composable
    override fun Content() {
         val viewModel: QrCodeLoginPageViewModel = diViewModel()
        QrCodeLoginPageContent(viewModel)
    }

}
//
private class QrCodeLoginPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val pageNavigation by instance<PageNavigation>()
    private val userStore by instance<UserStore>()

    private val loginSessionId = ApiHelper.getUUID()

    val loading = MutableStateFlow(false)
    val qrCodeData = MutableStateFlow<String?>(null)
    val error = MutableStateFlow("")

    /** 错误详情（`code=xxx · 服务端原话`）：唯一线索，单独一行显示给用户截图 */
    val errorDetail = MutableStateFlow("")
    val isScaned = MutableStateFlow(false)

    /**
     * 二维码剩余有效秒数。
     *
     * 服务端 `x/passport-tv-login/qrcode/auth_code` 只回 `url` + `auth_code`（没有有效期字段），
     * 所以按官方客户端的口径本地倒计时 180 秒。
     */
    val expireSeconds = MutableStateFlow(QR_CODE_TTL_SECONDS)
    private var countdownJob: Job? = null
    private var pollJob: Job? = null

    /**
     * 取码代际。每次 [loadQrImage] 自增一次。
     *
     * 旧的轮询协程/倒计时靠它认出"我已经不是当前这一代"，直接退出 —— 否则"扫了旧码再点刷新"时，
     * 旧码在途的 `86090` 会给**新码**盖上"扫描成功"遮罩，旧码的 `86038` 会掐掉新码的倒计时。
     * 只在主线程自增（LaunchedEffect / 点击），IO 侧只读，故用 @Volatile 保证可见性。
     */
    @Volatile
    private var qrGeneration = 0

    /**
     * 取一张新二维码（也是「刷新二维码」）。
     *
     * 刷新 = 全部复位：先同步把倒计时、扫码遮罩、错误清掉并作废旧代际，再发请求。
     *
     * ★所有状态写入都留在主线程（只把网络+解析放进 `withContext(IO)`）：刷新按钮也在主线程，
     *   这样"验代际"和"写状态"之间不会插进一次刷新 —— 否则仍有"验完代际、刷新刚跑到、
     *   旧响应再写状态"的极小窗口（微秒级 TOCTOU）。
     */
    fun loadQrImage() {
        val generation = ++qrGeneration
        countdownJob?.cancel()
        pollJob?.cancel()
        expireSeconds.value = QR_CODE_TTL_SECONDS
        isScaned.value = false
        error.value = ""
        errorDetail.value = ""
        loading.value = true
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    BiliApiService.authApi
                        .qrCode(loginSessionId)
                        .awaitCall()
                        .json<ResponseData<QRLoginInfo>>()
                }
                // 请求在途时可能已经被下一次刷新作废：旧响应一律丢弃
                if (generation != qrGeneration) return@launch
                if (res.isSuccess) {
                    val resData = res.requireData()
                    qrCodeData.value = resData.url
                    startCountdown(generation)
                    pollJob = viewModelScope.launch { checkQRCode(resData.auth_code, generation) }
                } else {
                    logQrFailure("取二维码失败", "code" to res.code, "message" to res.message)
                    error.value = "获取二维码失败，请稍后重试"
                    errorDetail.value = qrErrorDetail(res.code, res.message)
                }
            } catch (e: Exception) {
                if (generation == qrGeneration) {
                    // 原始异常只进日志，页面上给人话
                    miaoLogger().e("取二维码异常", e.stackTraceToString())
                    error.value = "获取二维码失败，请稍后重试"
                    errorDetail.value = ""
                }
            } finally {
                if (generation == qrGeneration) loading.value = false
            }
        }
    }

    /** 服务端 code + 原话：这是用户/我们唯一的线索，必须一起显示（`message == "0"` 时只留 code） */
    private fun qrErrorDetail(code: Int, message: String): String = buildString {
        append("code=").append(code)
        if (message.isNotBlank() && message != "0") append(" · ").append(message)
    }

    /** 失败详情只走 ERROR 级日志（Release 包只保留 ERROR 级） */
    private fun logQrFailure(reason: String, vararg details: Pair<String, Any?>) {
        val text = buildString {
            append("TV扫码登录失败: ").append(reason)
            details.forEach { (key, value) -> append("\n[").append(key).append("]=").append(value) }
        }
        miaoLogger().e(text)
    }

    /**
     * 本地倒计时：归零后停在这一屏，等用户点「刷新二维码」重新取码。
     *
     * 用**墙上时钟**算剩余秒数（不是减 tick）：进程被系统冻结再回来时，tick 会漏，
     * 墙上时钟不会 —— 切后台两分钟回来显示的剩余时间仍然是对的。
     */
    private fun startCountdown(generation: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            val startAt = System.currentTimeMillis()
            while (generation == qrGeneration) {
                val left = QR_CODE_TTL_SECONDS - ((System.currentTimeMillis() - startAt) / 1000).toInt()
                if (left <= 0) {
                    expireSeconds.value = 0
                    if (generation == qrGeneration && error.value.isBlank()) {
                        error.value = "二维码已过期，请刷新"
                    }
                    return@launch
                }
                expireSeconds.value = left
                delay(COUNTDOWN_TICK_MS)
            }
        }
    }

    private suspend fun checkQRCode(
        authCode: String,
        generation: Int,
    ) {
        // 每轮进门先验代际：刷新过就直接退出，旧码的任何状态都不许写
        if (generation != qrGeneration) return
        try {
            // ★刻意不直接 json<ResponseData<QrLoginInfo>>()：非 0/非预期 code 时 data 的形状可能对不上，
            //   解析异常会把真正的服务端 code/message 盖掉（用户只剩一句"登录失败"）。
            //   这里先拿原始 body 自己取 code/message/data，并把原始返回记进日志。
            val body = withContext(Dispatchers.IO) {
                BiliApiService.authApi
                    .checkQrCode(authCode)
                    .awaitCall()
                    .string()
            }
            // ★响应回来时可能已经刷新过（请求在途）：这里再验一次，否则旧码的分支会覆盖新码状态
            if (generation != qrGeneration) return
            val root = runCatching {
                MiaoJson.kotlinJson.parseToJsonElement(body).jsonObject
            }.getOrNull()
            if (root == null) {
                logQrFailure("轮询响应不是 JSON", "body" to body.take(QR_LOG_BODY_LIMIT))
                error.value = "登录请求失败，请稍后重试"
                errorDetail.value = ""
                return
            }
            val code = root["code"]?.jsonPrimitive?.intOrNull ?: QR_UNKNOWN_CODE
            val message = root["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val dataText = root["data"]?.toString().orEmpty()
            when (code) {
                86039 -> {
                    // 未确认
                    delay(3000)
                    checkQRCode(authCode, generation)
                }
                86090 -> {
                    // 已扫码未确认
                    isScaned.value = true
                    delay(2000)
                    checkQRCode(authCode, generation)
                }
                86038, -3 -> {
                    // 过期、失效
                    logQrFailure("二维码过期/失效", "code" to code, "message" to message)
                    countdownJob?.cancel()
                    expireSeconds.value = 0
                    error.value = "二维码已过期，请刷新"
                    errorDetail.value = ""
                }
                0 -> {
                    // 成功
                    val loginInfo = runCatching {
                        MiaoJson.fromJson<LoginInfo.QrLoginInfo>(dataText)
                    }.getOrNull()
                    if (loginInfo == null) {
                        logQrFailure(
                            "登录信息解析失败",
                            "code" to code,
                            "message" to message,
                            "data" to dataText.take(QR_LOG_BODY_LIMIT),
                        )
                        error.value = "登录信息解析失败，请重试"
                        errorDetail.value = ""
                        return
                    }
                    countdownJob?.cancel()
                    BilimiaoCommApp.commApp.saveAuthInfo(loginInfo.toLoginInfo())
                    authInfo()
                }
                else -> {
                    // 服务端拒绝或未知码：原话是唯一线索，code + message 一起显示，原始返回进日志
                    logQrFailure(
                        "TV扫码授权被拒绝/未知码",
                        "code" to code,
                        "message" to message,
                        "data" to dataText.take(QR_LOG_BODY_LIMIT),
                    )
                    error.value = "服务端拒绝了这次 TV 扫码登录"
                    errorDetail.value = qrErrorDetail(code, message)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (generation == qrGeneration) {
                // 原始异常只进日志，页面上给人话
                miaoLogger().e("TV扫码轮询异常", e.stackTraceToString())
                error.value = "登录请求失败，请稍后重试"
                errorDetail.value = ""
            }
        }
    }


    /**
     * 改用 Token / Cookie 登录：离开二维码页，回到登录页（**不自动弹任何弹窗**）。
     *
     * 为什么先 pop：二维码页本来就是从登录页进来的，留着它只会让返回栈里多一层已经失效的扫码页。
     * ★**不要**给登录页传"进去就弹 Token 表单"这种**路由参数**：参数会留在返回栈里，
     *   该页之后每次重新进组合（从子页返回 / 旋屏 / 进程重建）都会再弹一次 —— 用户实测过
     *   「退出扫码页后 Token 弹窗到处乱弹」。到了登录页由用户自己点「Token 登录」即可，
     *   按钮保留、走默认导航选项（**不**加 `launchSingleTop = false`，免得反复压层）。
     */
    fun toTokenLogin() {
        pageNavigation.popBackStack()
        pageNavigation.navigate(LoginPage())
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
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QrCodeLoginPageContent(
    viewModel: QrCodeLoginPageViewModel
) {
    PageConfig(
        title = "二微码登录"
    )
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())
    val bottomAppBarHeight = windowStore.bottomAppBarHeightDp

    val scrollState = rememberScrollState()

    val loading = viewModel.loading.collectAsStateWithLifecycle().value
    val error = viewModel.error.collectAsStateWithLifecycle().value
    val errorDetail = viewModel.errorDetail.collectAsStateWithLifecycle().value
    val qrCodeData = viewModel.qrCodeData.collectAsStateWithLifecycle().value
    val isScaned = viewModel.isScaned.collectAsStateWithLifecycle().value
    val expireSeconds = viewModel.expireSeconds.collectAsStateWithLifecycle().value
    val activity: FragmentActivity by rememberInstance()
    val scope = rememberCoroutineScope()

    // painter 只建一次：页内展示、「保存到相册」都用它
    val qrPainter = if (qrCodeData != null) rememberQrCodePainter(qrCodeData) else null

    var isFullScreenQrcode by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(viewModel) {
        viewModel.loadQrImage()
    }

    if (
        isFullScreenQrcode
        && !isScaned
        && error.isBlank()
        && qrPainter != null
    ) {
        Image(
            painter = qrPainter,
            contentDescription = "",
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(20.dp)
                .clickable {
                    isFullScreenQrcode = false
                }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(scrollState)
                .padding(horizontal = 10.dp)
        ) {
            Spacer(modifier = Modifier.height(windowInsets.topDp.dp))
            Text(
                text = "请使用哔哩哔哩客户端扫码登录",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 50.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (qrPainter != null) {
                // 状态行常驻：失效时显示「二维码已失效」，不再整行消失（用户要看得到状态）
                Text(
                    text = if (error.isBlank()) "剩余有效时间：${expireSeconds} 秒" else "二维码已失效",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error.isBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (error.isNotBlank()) {
                    // 失效态只留一个出口，位置固定在二维码区**上方**，不压任何可读内容
                    TextButton(
                        onClick = { viewModel.loadQrImage() },
                        enabled = !loading,
                    ) {
                        Text("重新加载")
                    }
                } else if (qrPainter != null) {
                    TextButton(
                        onClick = { viewModel.loadQrImage() },
                        // 取码期间禁用：连点会并发取码（旧码的轮询靠代际兜底，但没必要发两次请求）
                        enabled = !loading,
                    ) {
                        Text("刷新二维码")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                // 栅格化留在主线程（painter 自带绘制缓存，不跨线程用），只有落盘走 IO
                                val bitmap = qrPainter.toQrBitmap()
                                if (bitmap == null) {
                                    toast("生成二维码图片失败")
                                } else {
                                    withContext(Dispatchers.IO) {
                                        ImageSaveUtil.saveImage(
                                            activity,
                                            "bilimiao_qrcode_${System.currentTimeMillis()}.png",
                                            bitmap,
                                        )
                                    }
                                }
                            }
                        }
                    ) {
                        Text("保存到相册")
                    }
                    TextButton(
                        onClick = { openQrCodeInOtherApp(activity, qrCodeData ?: return@TextButton) }
                    ) {
                        Text("其他应用打开")
                    }
                }
            }
            if (error.isNotBlank()) {
                // ★错误态**整块替换**二维码区：错误文案与按钮绝不叠在码上（码同时隐藏）
                QrLoginErrorCard(
                    error = error,
                    detail = errorDetail,
                    onTokenLogin = viewModel::toTokenLogin,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clickable {
                            isFullScreenQrcode = true
                        },
                ) {
                    if (qrPainter != null) {
                        Image(
                            painter = qrPainter,
                            contentDescription = "",
                            modifier = Modifier
                                .size(240.dp)
                                .align(Alignment.Center)
                                .background(Color.White)
                                .padding(5.dp)
                        )
                        if (isScaned) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                            ) {
                                Text(
                                    text = "扫描成功\n\n请在扫码端确认登录",
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontSize = 20.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.Center)
                                )
                            }
                        }
                    }
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(48.dp)
                                .align(Alignment.Center),
                            strokeWidth = 3.dp,
                        )
                    }
                }
            }
            if (qrCodeData != null && error.isBlank()) {
                // 长按复制链接；点击不做事（只保留水波纹，避免误触）
                Text(
                    text = qrCodeData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { copyQrCodeLink(activity, qrCodeData) },
                        )
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

}

/** 二维码有效期（秒）：接口不返回有效期字段，按官方客户端口径本地倒计时 */
private const val QR_CODE_TTL_SECONDS = 180

/** 轮询响应里读不到 code 时用的哨兵值（只用于显示/日志，不参与业务分支） */
private const val QR_UNKNOWN_CODE = -1

/** 失败日志里最多带多少字符的原始返回（避免把整份 body 刷进日志） */
private const val QR_LOG_BODY_LIMIT = 600

/** 倒计时刷新间隔：一秒一跳即可，500ms 只是让跨秒时的显示更跟手 */
private const val COUNTDOWN_TICK_MS = 500L

/** 「保存到相册」落盘的二维码边长（像素） */
private const val QR_BITMAP_SIZE = 720

/** 哔哩哔哩官方客户端包名 */
private const val BILI_APP_PACKAGE = "tv.danmaku.bili"

/**
 * 二维码栅格化。
 *
 * ★必须垫白底：二维码本体是黑块 + 透明背景，直接存成透明 PNG 后，很多看图器以黑底显示，
 * 扫不出来。这里的白底与页面上 `Image` 的 `Color.White` 是同一用途（二维码图片底色），
 * 不是 UI 主题色。
 */
private fun Painter.toQrBitmap(size: Int = QR_BITMAP_SIZE): Bitmap? {
    val image = runCatching { toImageBitmap(size, size) }.getOrNull() ?: return null
    val src = image.asAndroidBitmap()
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    Canvas(out).apply {
        // 非 UI 主题色：这是二维码位图的底色（透明底在深色看图器里等于黑底，扫不出来），
        // 与页面上 Image 的 Color.White 同一用途
        drawColor(android.graphics.Color.WHITE)
        drawBitmap(src, 0f, 0f, null)
    }
    return out
}

/**
 * 「其他应用打开」：优先用官方 App 的 `bilibili://browser?url=` deeplink。
 *
 * 用"直接 start + 捕获异常"判断而不是 `resolveActivity`：本 App 的 manifest 没有声明
 * `tv.danmaku.bili` 的 `<queries>`，Android 11+ 的包可见性会把 `resolveActivity` 判成 null，
 * 明明装了官方 App 也会走兜底。能 start 成功就说明目标就是官方 App。
 */
private fun openQrCodeInOtherApp(activity: Activity, qrUrl: String) {
    val deeplink = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("bilibili://browser?url=" + Uri.encode(qrUrl)),
    ).setPackage(BILI_APP_PACKAGE)
    if (runCatching { activity.startActivity(deeplink) }.isSuccess) {
        toast("将跳转到哔哩哔哩 App 完成授权登录")
        return
    }
    val fallback = Intent.createChooser(
        Intent(Intent.ACTION_VIEW, Uri.parse(qrUrl)),
        "打开二维码链接",
    )
    runCatching { activity.startActivity(fallback) }
        .onFailure { toast("没有可打开该链接的应用") }
}

/** 长按二维码链接 → 复制 + 提示 */
private fun copyQrCodeLink(activity: Activity, link: String) {
    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("二维码链接", link))
    toast("二维码链接已复制")
}

/**
 * 扫码失败时的错误区。
 *
 * ★**整块替换二维码区**（码同时隐藏），所以错误文案与按钮不会叠在码上；
 * 「重新加载」固定在上方的操作行里，也不压这里的内容。
 * 正文保留服务端原话（`detail`，唯一线索），并给一条直达 Token / Cookie 登录的出路。
 */
@Composable
private fun QrLoginErrorCard(
    error: String,
    detail: String,
    onTokenLogin: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = error,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                modifier = Modifier.padding(top = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onTokenLogin,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("改用 Token / Cookie 登录")
        }
    }
}
