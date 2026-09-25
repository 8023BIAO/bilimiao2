package cn.a10miaomiao.bilimiao.compose.pages.setting

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.a10miaomiao.bilimiao.compose.components.antifraud.AntifraudMonitor
import cn.a10miaomiao.bilimiao.compose.components.antifraud.AntifraudResultState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import cn.a10miaomiao.bilimiao.compose.components.dialogs.OverlayAlertDialog
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localPageNavigation
import cn.a10miaomiao.bilimiao.compose.pages.setting.ErrorLogPage
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.preference.rememberPreferenceFlow
import cn.a10miaomiao.bilimiao.compose.components.preference.glidePreference
import cn.a10miaomiao.bilimiao.compose.components.preference.textIntPreference
import cn.a10miaomiao.bilimiao.compose.components.preference.sliderIntPreference
import com.a10miaomiao.bilimiao.comm.toast
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.utils.CdnHosts
import com.a10miaomiao.bilimiao.comm.utils.ClickGuard
import cn.a10miaomiao.bilimiao.compose.pages.setting.widgets.CdnSelectDialog
import com.a10miaomiao.bilimiao.comm.datastore.SettingsExporter
import com.a10miaomiao.bilimiao.comm.entity.auth.LoginInfo
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.utils.WbiSigner
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.comm.store.UserStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.preference
import me.zhanghai.compose.preference.preferenceCategory
import me.zhanghai.compose.preference.switchPreference
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import java.io.BufferedReader
import java.io.InputStreamReader

// 从 Compose 的 context 往上找宿主的 LifecycleOwner（可能被 ContextWrapper 包着，直接 as 会失败）。
// 注意返回的是 LifecycleOwner 而不是 Activity：Activity 本身没有 lifecycle 属性（那是 ComponentActivity 的）
private fun Context.findHostLifecycleOwner(): androidx.lifecycle.LifecycleOwner? {
    var ctx: Context? = this
    while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) {
        ctx = ctx.baseContext
    }
    return ctx as? androidx.lifecycle.LifecycleOwner
}

/**
 * 设置里的"更多"页面原先全挤在「实验性功能」一页里（1138 行 / 9 个分类，用户反馈"杂物间"）。
 * 现在按大类拆成**可选区块**：每个页面只显示自己那几块，状态与弹窗仍留在本文件（不搬家）。
 */
private enum class MoreSection {
    ANTIFRAUD, SPONSOR, RIPPER, CDN, DEV_TOOLS, STORAGE, ABOUT, BOTTOM_BAR;

    companion object {
        val ALL: Set<MoreSection> = entries.toSet()
        /** ④ 扩展 */
        val EXT: Set<MoreSection> = setOf(SPONSOR, RIPPER, CDN, ANTIFRAUD)
        /** ⑤ 账号与数据 */
        val ACCOUNT: Set<MoreSection> = setOf(DEV_TOOLS, STORAGE)
        /** ⑥ 关于（含诊断） */
        val ABOUT_PAGE: Set<MoreSection> = setOf(ABOUT)
        /** ② 界面 → 底栏与导航 */
        val BOTTOM_BAR_PAGE: Set<MoreSection> = setOf(BOTTOM_BAR)
    }
}

/** ⑤ 账号与数据：身份导入导出 / 存储 / 重置 */
@Serializable
class AccountDataSettingPage : ComposePage() {
    @Composable
    override fun Content() {
        FlagsSettingPageContent(diViewModel(), MoreSection.ACCOUNT, "账号与数据")
    }
}

/** ⑥ 关于：版本 / GitHub / 致谢 / 错误日志 / 诊断 */
@Serializable
class AboutSettingPage : ComposePage() {
    @Composable
    override fun Content() {
        FlagsSettingPageContent(diViewModel(), MoreSection.ABOUT_PAGE, "关于")
    }
}

/** ② 界面 → 底栏与导航 */
@Serializable
class BottomBarSettingPage : ComposePage() {
    @Composable
    override fun Content() {
        FlagsSettingPageContent(diViewModel(), MoreSection.BOTTOM_BAR_PAGE, "底栏与导航")
    }
}

/** ④ 扩展 → 海外加速（一级页：启用开关 + 「并发设置」入口 —— 别直接指到二级页，否则开关找不到） */
@Serializable
class RipperSettingPage : ComposePage() {
    @Composable
    override fun Content() {
        FlagsSettingPageContent(diViewModel(), setOf(MoreSection.RIPPER), "海外加速")
    }
}

/** ④ 扩展 → CDN */
@Serializable
class CdnSettingPage : ComposePage() {
    @Composable
    override fun Content() {
        FlagsSettingPageContent(diViewModel(), setOf(MoreSection.CDN), "CDN")
    }
}

/** ④ 扩展 → 评论反诈 */
@Serializable
class AntifraudSettingPage : ComposePage() {
    @Composable
    override fun Content() {
        FlagsSettingPageContent(diViewModel(), setOf(MoreSection.ANTIFRAUD), "评论反诈")
    }
}


private class FlagsSettingPageViewModel(
    override val di: DI,
) : ViewModel(), DIAware {

    private val fragment by instance<Fragment>()
    private val userStore by instance<UserStore>()
    private val pageNavigation by instance<PageNavigation>()

    fun toErrorLogPage() {
        pageNavigation.navigate(ErrorLogPage())
    }

    /** 空降助手的完整设置页（从「播放设置」移到这里：用户说藏在播放设置里太难找） */
    fun toSponsorBlockSettingPage() {
        pageNavigation.navigate(SponsorBlockSettingPage())
    }

    // 分段并发下载的「并发连接数」已内联进海外加速一级页（ThreadRipperSettingPage 已删除），
    // 这里不再需要跳转方法。

    // 身份信息导入导出已改为文件操作，见 FlagsSettingPageContent 中的 launchers

    fun toggleGuestMode(enabled: Boolean) {
        val ctx = fragment.requireContext()
        val isCurrentlyGuest = BilimiaoCommApp.commApp.loginInfo == null
        if (enabled == isCurrentlyGuest) return
        if (enabled) {
            val loginInfo = BilimiaoCommApp.commApp.loginInfo
            if (loginInfo != null) {
                val json = MiaoJson.toJson(loginInfo)
                val prefs = ctx.getSharedPreferences("bilimiao_guest_backup", Context.MODE_PRIVATE)
                prefs.edit().putString("login_info_backup", json).commit()
                userStore.logout()  // 复用退出登录逻辑
                Toast.makeText(ctx, "已启用游客模式，正在重启...", Toast.LENGTH_SHORT).show()
                val restartIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                if (restartIntent != null) {
                    ctx.startActivity(android.content.Intent.makeRestartActivityTask(restartIntent.component))
                }
                java.lang.System.exit(0)
            } else {
                Toast.makeText(ctx, "未登录，无需启用游客模式", Toast.LENGTH_SHORT).show()
            }
        } else {
            val prefs = ctx.getSharedPreferences("bilimiao_guest_backup", Context.MODE_PRIVATE)
            val backupJson = prefs.getString("login_info_backup", null)
            if (backupJson != null) {
                try {
                    val loginInfo = MiaoJson.fromJson<LoginInfo>(backupJson)
                    BilimiaoCommApp.commApp.saveAuthInfo(loginInfo)
                    prefs.edit().remove("login_info_backup").commit()
                    userStore.loadInfo()  // 重新加载用户信息
                    Toast.makeText(ctx, "登录信息已恢复，正在重启...", Toast.LENGTH_SHORT).show()
                    val restartIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                    if (restartIntent != null) {
                        ctx.startActivity(android.content.Intent.makeRestartActivityTask(restartIntent.component))
                    }
                    java.lang.System.exit(0)
                } catch (e: Exception) {
                    Toast.makeText(ctx, "恢复失败，请手动导入身份信息", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(ctx, "无备份信息，请手动导入身份信息", Toast.LENGTH_LONG).show()
            }
        }
    }
}


/**
 * 版本名**显示用**：去掉尾部的 "-<构建序号>"（例如 V2026.09.25-54 → V2026.09.25）。
 * 那个后缀只是"同一天多次构建"的区分号，对外没必要看到；版本号另外用 VC 显示。
 * （内部 versionName / 发布命名 / 崩溃日志里仍保留完整名字）
 */
private fun displayVersionName(versionName: String?): String {
    val name = versionName?.trim().orEmpty()
    if (name.isEmpty()) return "未知"
    return name.replace(Regex("-\\d+$"), "")
}

@Composable
private fun FlagsSettingPageContent(
    viewModel: FlagsSettingPageViewModel,
    sections: Set<MoreSection> = MoreSection.ALL,
    pageTitle: String = "实验性功能",
) {
    PageConfig(
        title = pageTitle
    )
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val context = LocalContext.current
    // 这个函数是顶层 @Composable（不在 FlagsSettingPageState 类里），要用 CompositionLocal 拿导航
    val pageNavigation = localPageNavigation()
    // 当前版本：对外版本名 + versionCode（关于页展示；用 PackageManager 取，跨模块安全）
    // 必须在 LazyColumn 之外算 —— LazyListScope 的 lambda 不是 @Composable 上下文，里面不能调 remember
    val appVersionLabel = remember(context) {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val vc = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION") pi.versionCode.toLong()
            }
            "${displayVersionName(pi.versionName)}（VC $vc）"
        } catch (e: Exception) {
            "未知"
        }
    }
    val userStore: UserStore by rememberInstance()
    val dataStore = remember {
        SettingPreferences.run { context.dataStore }
    }
    val scope = rememberCoroutineScope()
    var showResetDialog by remember { mutableStateOf(false) }
    var showGuestConfirmDialog by remember { mutableStateOf(false) }
    // 分段并发下载：本机最多能开多少连接 = 处理器核数（并发设置页滑块的上限也是它）
    val maxThreads = remember {
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    }
    // 光标放末尾：String 重载会让 DPI/字缩输入框的光标停在开头
    var showCdnDialog by remember { mutableStateOf(false) }
    var currentCdnKey by remember { mutableStateOf("default") }
    LaunchedEffect(Unit) {
        currentCdnKey = SettingPreferences.mapData(context) {
            it[SettingPreferences.SelectedCdnHost] ?: "default"
        }
    }

    // 导出身份信息
    val exportAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                val cookieManager = CookieManager.getInstance()
                val cookie = cookieManager.getCookie("https://bilibili.com") ?: ""
                val loginInfo = BilimiaoCommApp.commApp.loginInfo
                val tokenInfo = loginInfo?.token_info
                val data = buildJsonObject {
                    put("cookie", JsonPrimitive(cookie))
                    put("buvid", JsonPrimitive(BilimiaoCommApp.commApp.getBilibiliBuvid()))
                    val wbiCache = WbiSigner.getWbiCache()
                    put("wbi", buildJsonObject {
                        // 键名必须和 WbiSigner.getWbiCache() 一致（mixKey/lastFetchDay），
                        // 之前写成 mix_key 导致导出的 wbi 永远是空对象
                        (wbiCache["mixKey"] as? String)?.let { put("mixKey", JsonPrimitive(it)) }
                        (wbiCache["lastFetchDay"] as? Int)?.let { put("lastFetchDay", JsonPrimitive(it)) }
                    })
                    if (tokenInfo != null) {
                        put("access_token", JsonPrimitive(tokenInfo.access_token))
                        put("refresh_token", JsonPrimitive(tokenInfo.refresh_token))
                        put("mid", JsonPrimitive(tokenInfo.mid.toString()))
                    }
                }
                val jsonStr = data.toString()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(jsonStr.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                Toast.makeText(context, "身份信息已导出", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 导入身份信息
    val importAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val rawJson = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                        } ?: throw Exception("无法读取文件")
                    }
                    val jsonStr = SettingsExporter.truncateToValidJson(rawJson)
                    val importJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    val jsonObj = importJson.parseToJsonElement(jsonStr).jsonObject
                    val cookieStr = jsonObj["cookie"]?.jsonPrimitive?.content ?: throw Exception("未找到cookie字段")
                    val accessToken = jsonObj["access_token"]?.jsonPrimitive?.content ?: throw Exception("未找到access_token字段")
                    val refreshToken = jsonObj["refresh_token"]?.jsonPrimitive?.content ?: throw Exception("未找到refresh_token字段")
                    val midStr = jsonObj["mid"]?.jsonPrimitive?.content ?: throw Exception("未找到mid字段")
                    val mid = midStr.toLongOrNull() ?: throw Exception("mid格式错误")
                    val buvid = jsonObj["buvid"]?.jsonPrimitive?.content ?: ""

                    // 恢复 WBI 缓存（兼容旧版本导出的 mix_key/last_fetch_day 键名）
                    jsonObj["wbi"]?.jsonObject?.let { wbiObj ->
                        WbiSigner.restoreWbiCache(mapOf(
                            "mixKey" to (wbiObj["mixKey"] ?: wbiObj["mix_key"])?.jsonPrimitive?.contentOrNull,
                            "lastFetchDay" to (wbiObj["lastFetchDay"] ?: wbiObj["last_fetch_day"])?.jsonPrimitive?.intOrNull,
                        ))
                    }

                    // 保存设备指纹：必须走 setBilibiliBuvid（同步失效内存缓存），
                    // 否则 auth 文件会用"旧 buvid 的密钥"加密 → 重启后解不开 → 静默登出（审查发现的 S2）
                    if (buvid.isNotBlank()) {
                        if (buvid.length < 12) {
                            throw Exception("buvid 长度不足（至少 12 位），导入会解不开登录信息")
                        }
                        BilimiaoCommApp.commApp.setBilibiliBuvid(buvid)
                    }

                    val cookies = cookieStr.split(";").map { pair ->
                        val parts = pair.trim().split("=", limit = 2)
                        LoginInfo.Cookie(
                            name = parts[0].trim(),
                            value = if (parts.size > 1) parts[1].trim() else "",
                            expires = 0,
                            http_only = 0
                        )
                    }
                    val cookieInfo = LoginInfo.CookieInfo(
                        cookies = cookies,
                        domains = listOf(".bilibili.com", "bilibili.com")
                    )
                    val tokenInfo = LoginInfo.TokenInfo(
                        access_token = accessToken,
                        refresh_token = refreshToken,
                        mid = mid,
                        expires_in = 2592000
                    )
                    val loginInfo = LoginInfo(
                        token_info = tokenInfo,
                        sso = null,
                        cookie_info = cookieInfo
                    )
                    BilimiaoCommApp.commApp.saveAuthInfo(loginInfo)
                    userStore.loadInfo()  // 加载用户信息
                    Toast.makeText(context, "身份信息导入成功，正在重启...", Toast.LENGTH_SHORT).show()
                    val restartIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    if (restartIntent != null) {
                        context.startActivity(android.content.Intent.makeRestartActivityTask(restartIntent.component))
                    }
                    java.lang.System.exit(0)
                } catch (e: Exception) {
                    Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 导出设置
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    // 导出要读 DataStore 再写文件，放到 IO 线程，避免主线程 runBlocking 卡住界面
                    withContext(Dispatchers.IO) {
                        val json = SettingsExporter.exportToJson(context)
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                    }
                    Toast.makeText(context, "设置已导出", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 导入设置
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val jsonString = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                        } ?: throw Exception("无法读取文件")
                    }
                    // 导入要写 SQLite + DataStore，别在主线程做
                    val count = withContext(Dispatchers.IO) {
                        SettingsExporter.importFromJson(context, jsonString)
                    }
                    Toast.makeText(context, "已导入 $count 项设置，正在重启...", Toast.LENGTH_SHORT).show()
                    val restartIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    if (restartIntent != null) {
                        context.startActivity(android.content.Intent.makeRestartActivityTask(restartIntent.component))
                    }
                    java.lang.System.exit(0)
                } catch (e: Exception) {
                    Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val prefFlow = rememberPreferenceFlow(dataStore)
    ProvidePreferenceLocals(
        flow = prefFlow
    ) {
        // ★ 分段并发下载**不再按视频格式置灰/自动关闭**（用户实测反馈：MP4 有时候也能并发，
        //   不该"一改成 MP4 就帮你关掉"）。运行时那一层本来就是自适应的：
        //   ThreadRipperDataSource 只在"这次请求拿得到长度"时才切分并发，拿不到长度
        //   （部分 MP4 渐进请求 length=UNSET）就原样透传单连接，并发失败还有熔断兜底。
        val prefValues = prefFlow.collectAsStateWithLifecycle().value
        // 游客模式状态（必须在 Composable 作用域内）
        val loginInfoState by userStore.stateFlow.collectAsStateWithLifecycle()
        // 组合期直接读 SharedPreferences 会在主线程做首次磁盘加载（进页面就掉帧）→ 异步读
        var hasBackup by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            hasBackup = withContext(Dispatchers.IO) {
                context.getSharedPreferences("bilimiao_guest_backup", Context.MODE_PRIVATE)
                    .getString("login_info_backup", null) != null
            }
        }
        // 上次检测结果：从磁盘读一次（IO 线程；不能放在 LazyColumn 的 content 里，见下面的说明）
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { AntifraudResultState.ensureLoaded(context) }
        }
        // 正在监控的评论（设置页里实时显示进度）
        val monitors = AntifraudMonitor.sessions
        // 每秒更新一次"当前时间"，进度条与"已盯多久"才会动。
        // ★ 以前是裸的 while(true)：注释里写"页面不可见时不会跑"并不成立 —— 它只在**离开组合**
        //   时才取消，App 退到后台/息屏时组合仍然活着，于是每秒写一次状态、驱动监控条目重组。
        //   现在挂在宿主的 RESUMED 生命周期上（repeatOnLifecycle），后台就停。
        var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val hostLifecycleOwner = remember(context) { context.findHostLifecycleOwner() }
        // 再门控一层"有没有在监控的评论"：没监控时这个每秒 tick 纯属白醒（列表里没人读它）
        LaunchedEffect(hostLifecycleOwner, monitors.isNotEmpty()) {
            if (monitors.isEmpty()) return@LaunchedEffect
            val lifecycle = hostLifecycleOwner?.lifecycle
            if (lifecycle == null) {
                // 拿不到宿主的 LifecycleOwner 时退回原行为，别让计时器直接失效
                while (true) {
                    delay(1000)
                    nowTick = System.currentTimeMillis()
                }
            } else {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        nowTick = System.currentTimeMillis()
                        delay(1000)
                    }
                }
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = windowInsets.leftDp.dp,
                    end = windowInsets.rightDp.dp,
                )
        ) {
            item("top") {
                Spacer(
                    modifier = Modifier.height(windowInsets.topDp.dp)
                )
            }
            if (MoreSection.DEV_TOOLS in sections) {
            preferenceCategory(
                key = "dev_tools",
                title = {
                    // 这里装的是身份导入导出 / 返回登录 / 游客模式 / 设置导入导出 —— 早就不止"开发工具"了
                    Text("账号与备份")
                }
            )
            preference(
                key = "export_auth",
                title = {
                    Text("导出身份信息")
                },
                summary = {
                    Text("导出身份信息到文件")
                },
                onClick = {
                    exportAuthLauncher.launch("bilimiao_auth_${System.currentTimeMillis()}.json")
                },
            )
            preference(
                key = "import_auth",
                title = {
                    Text("导入身份信息")
                },
                summary = {
                    Text("导入身份信息文件，成功后自动重启")
                },
                onClick = {
                    importAuthLauncher.launch(arrayOf("application/json", "*/*"))
                },
            )
            preference(
                key = "export_settings",
                title = {
                    Text("导出设置")
                },
                summary = {
                    Text("导出全部设置（含私人 ID，别外发）")
                },
                onClick = {
                    exportLauncher.launch("bilimiao_settings_${System.currentTimeMillis()}.json")
                },
            )
            preference(
                key = "import_settings",
                title = {
                    Text("导入设置")
                },
                summary = {
                    Text("导入设置文件内容，成功后自动重启")
                },
                onClick = {
                    importLauncher.launch(arrayOf("application/json", "*/*"))
                },
            )

                // ===== 游客模式 =====
                if (hasBackup) {
                    // 有备份 → 显示"返回登录"，不显示"游客模式"这个 category
                    preference(
                        key = "return_login",
                        title = { Text("返回登录") },
                        summary = { Text("恢复之前备份的登录信息，清除游客模式") },
                        onClick = {
                            viewModel.toggleGuestMode(false)
                            hasBackup = false
                        },
                    )
                } else if (loginInfoState != null) {
                    // 已登录且无备份 → 显示"游客模式" category + "开启游客模式" item
                    preferenceCategory(
                        key = "guest_mode_category",
                        title = { Text("游客模式") }
                    )
                    preference(
                        key = "enable_guest",
                        title = { Text("开启游客模式") },
                        summary = { Text("临时清除登录状态，以匿名身份访问B站") },
                        onClick = { showGuestConfirmDialog = true },
                    )
                }

            // ===== 网络 =====
            }

            if (MoreSection.ANTIFRAUD in sections) {
            // ===== 评论反诈（发评后自动检测是否被限流）=====
            // 判定规则照搬开源项目 biliSendCommAntifraud：
            // ShadowBan 的评论"带 Cookie 能找到、游客找不到"。
            // ★ 第一个开关是**总开关**：CommentAntifraudLauncher 读到 false 就整个不跑
            //   （见 readSettings 的调用点），所以它关掉时后面的复查/时长必须置灰，
            //   否则用户会以为"总开关关了，下面还能单独打开"。
            val antifraudOn =
                (prefValues[SettingPreferences.AntifraudEnabled.name] as? Boolean) ?: false
            val antifraudRecheckOn =
                (prefValues[SettingPreferences.AntifraudRecheckEnabled.name] as? Boolean) ?: true
            preferenceCategory(
                key = "antifraud",
                title = { Text("评论反诈") }
            )
            switchPreference(
                key = SettingPreferences.AntifraudEnabled.name,
                defaultValue = false,
                title = { Text("发评论后自动检测是否被限流（总开关）") },
                summary = {
                    Text(
                        if (it) "已开启：查出被限流会弹窗，可删除或去申诉；下面的复查设置才生效"
                        else "已关闭：整个反诈都不跑，下面的复查设置灰着、改了也不生效"
                    )
                },
            )
            // 复查开关 + 监控时长滑条：只查一次会漏掉"先正常、过一会儿才被限流"的情况
            switchPreference(
                key = SettingPreferences.AntifraudRecheckEnabled.name,
                defaultValue = true,
                enabled = { antifraudOn },
                title = { Text("自动复查（推荐开）") },
                summary = {
                    Text(
                        when {
                            !antifraudOn -> "总开关关着 —— 先打开上面的开关"
                            it -> "已开启：正常也会继续盯，直到状态变化或盯满时长"
                            else -> "只查一次（评论可能先正常、过一会儿才被限流）"
                        }
                    )
                },
            )
            sliderIntPreference(
                key = SettingPreferences.AntifraudRecheckMinutes.name,
                // 必须与代码里的默认值一致：否则界面显示 5 分钟、实际按 15 分钟跑（审查发现）
                defaultValue = com.a10miaomiao.bilimiao.comm.antifraud.CommentAntifraud.DEFAULT_RECHECK_MINUTES,
                valueRange = 1..30,
                // steps = 两端点之间的档位数 = 28（每分钟一档）
                valueSteps = 28,
                enabled = { antifraudOn && antifraudRecheckOn },
                title = { Text("复查监控时长") },
                valueText = { v -> Text("$v 分钟") },
                summary = { v ->
                    Text(
                        when {
                            !antifraudOn -> "总开关关着 —— 先打开上面的开关"
                            !antifraudRecheckOn -> "自动复查关着，这一项不生效"
                            else -> "首查 5 秒后开始，之后每 30 秒查一次，共盯 $v 分钟"
                        }
                    )
                },
            )
            preference(
                key = "antifraud_about",
                title = { Text("参考项目：biliSendCommAntifraud") },
                summary = { Text("哔哩发评反诈（点开 GitHub）") },
                onClick = {
                    // 防连点：连点 N 次不该拉起 N 个浏览器
                    if (ClickGuard.allow("flags:antifraud_about")) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(
                                        "https://github.com/freedom-introvert/biliSendCommAntifraud"
                                    )
                                )
                            )
                        }
                    }
                },
            )
            // ── 监控中：正在盯的评论 + 实时进度（用户要求：别让人干等一个弹窗）──
            // 注意：LazyColumn 的 content 不是 @Composable，remember/LaunchedEffect 只能写在它外面
            if (monitors.isNotEmpty()) {
                preferenceCategory(
                    key = "antifraud_monitoring",
                    title = { Text("正在监控（${monitors.size} 条）") }
                )
                monitors.forEach { m ->
                    preference(
                        // key 要唯一：同一 rpid 万一被登记两次（重试/手快）会直接崩 "Key was already used"
                        key = "antifraud_mon_${m.key}_${System.identityHashCode(m)}",
                        title = { Text("${m.label} · 评论 ${m.rpid}") },
                        summary = {
                            val elapsed = m.elapsedMs(nowTick)
                            val pct = (m.progress(nowTick) * 100).toInt()
                            androidx.compose.foundation.layout.Column {
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { m.progress(nowTick) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "第 ${m.attempt}/${m.planned} 次 · 已盯 ${fmtDuration(elapsed)} / " +
                                        "共 ${fmtDuration(m.totalMs)}（$pct%）\n" +
                                        "上次结论：${m.lastDetail.ifBlank { "等待中" }}"
                                )
                            }
                        },
                    )
                }
            }
            // 上次检测结果（落盘的那份）：弹窗没弹出来时，这里是他唯一的交代
            // 用可观察状态：清空/复检后界面立刻刷新（原来直接读 SharedPreferences，Compose 不知道数据变了）。
            // ★ 只读，不做副作用：LazyColumn 的 content 会被包进 derivedStateOf 计算，在那里读盘
            //   （ensureLoaded）等于"measure 阶段在主线程读 SharedPreferences"，而且 derivedStateOf
            //   要求计算无副作用。读盘统一放到上面的 LaunchedEffect 里（IO 线程）。
            val lastResult = AntifraudResultState.last
            if (lastResult != null) {
                preference(
                    key = "antifraud_last_result",
                    title = {
                        Text(
                            (if (lastResult.isBad) "⚠️ 上次检测：" else "✅ 上次检测：") +
                                "${lastResult.title}（${lastResult.timeText()}）"
                        )
                    },
                    summary = {
                        Text(
                            "${lastResult.where} · 评论 ${lastResult.rpid}\n" +
                                "内容：${lastResult.message.ifBlank { "(无)" }}\n" +
                                "判定：${lastResult.detail}\n（点开可申诉 / 删除；复检用下面那行）"
                        )
                    },
                    onClick = {
                        if (ClickGuard.allow("flags:antifraud_last")) {
                            // ★ 与"发完评论后的检测弹窗"共用同一个构建入口 —— 用户要求两处按钮和位置
                            //   一模一样（"不要这个位置在那，这个位置在这"）。
                            //   原来这里自己拼了一个弹窗（重新检测/清空记录/关闭），所以看不到申诉按钮。
                            //   重新检测、清空记录这两个动作挪到下面的独立行，功能没丢。
                            cn.a10miaomiao.bilimiao.compose.pages.community.components.CommentAntifraudLauncher
                                .showResultDialog(
                                    mark = if (lastResult.isBad) "⚠️ " else "✅ ",
                                    title = lastResult.title,
                                    body = "${lastResult.where}\n评论 ID：${lastResult.rpid}\n\n" +
                                        "评论内容：${lastResult.message.ifBlank { "(无)" }}\n\n" +
                                        "判定依据：${lastResult.detail}\n\n" +
                                        "检测时间：${lastResult.timeText()}",
                                    isBad = lastResult.isBad,
                                    oid = lastResult.oid,
                                    type = lastResult.type,
                                    rpid = lastResult.rpid,
                                    message = lastResult.message,
                                )
                        }
                    },
                )
                // 手动复检：不发新评论也能验证判定（评论常常几分钟后才被限流）
                preference(
                    key = "antifraud_recheck",
                    title = { Text("重新检测这条评论") },
                    summary = { Text("不发新评论也能复检；评论常常几分钟后才被限流") },
                    onClick = {
                        if (ClickGuard.allow("flags:antifraud_recheck")) {
                            val launcher = cn.a10miaomiao.bilimiao.compose.pages.community.components
                                .CommentAntifraudLauncher
                            if (lastResult.oid > 0L && lastResult.type > 0) {
                                launcher.recheck(
                                    oid = lastResult.oid,
                                    type = lastResult.type,
                                    rpid = lastResult.rpid,
                                    root = lastResult.root,
                                    message = lastResult.message,
                                    // 记录里有发送时间就带上（早停才准）；老记录是 0 = 不早停
                                    sentTimeSec = lastResult.sentTimeSec,
                                )
                            } else {
                                // 老版本（vc124 及以前）的记录里没存 oid/type，只有"视频 BVxxxx"这段文字
                                // → 从里面把 BV 抠出来，换成 aid 再复检（用户实测撞上过"缺少参数"）
                                val bv = Regex("BV[0-9A-Za-z]{10}").find(lastResult.where)?.value
                                if (bv != null) {
                                    launcher.recheckByBv(
                                        bv = bv,
                                        rpid = lastResult.rpid,
                                        message = lastResult.message,
                                        sentTimeSec = lastResult.sentTimeSec,
                                    )
                                } else {
                                    com.kongzue.dialogx.dialogs.PopTip.show(
                                        "这条记录是旧版本存的、没带视频信息，没法复检；再发一条评论就有了"
                                    )
                                }
                            }
                        }
                    },
                )
                preference(
                    key = "antifraud_clear",
                    title = { Text("清空检测记录") },
                    summary = { Text("删掉保存的「上次检测结果」") },
                    onClick = {
                        if (ClickGuard.allow("flags:antifraud_clear")) {
                            AntifraudResultState.clear(context)
                        }
                    },
                )
            }
            preference(
                key = "antifraud_appeal",
                title = { Text("B站官方申诉页") },
                summary = { Text("评论被限流时来这里申诉") },
                onClick = {
                    if (ClickGuard.allow("flags:antifraud_appeal")) {
                        // 走外部浏览器（用户 2026-09-25 拍板："你还是跳外部吧，一劳永逸"）：
                        // 内置 WebView 得替 B站 页面适配主题（深色注入后表单白底白字），不值当。
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(
                                        cn.a10miaomiao.bilimiao.compose.pages.community.components
                                            .CommentAntifraudLauncher.APPEAL_URL
                                    )
                                )
                            )
                        }
                    }
                },
            )

            // ===== 空降助手（原在「播放设置」里，用户反馈藏得太深 → 移到这里）=====
            }
            if (MoreSection.SPONSOR in sections) {
            preferenceCategory(
                key = "sponsor_block",
                title = { Text("空降助手（跳过赞助/恰饭片段）") }
            )
            preference(
                key = "sponsor_block_entry",
                title = { Text("空降助手") },
                summary = { Text("自动跳过赞助/恰饭/片头片尾片段") },
                onClick = viewModel::toSponsorBlockSettingPage,
            )

            // ===== 海外加速（分段并发下载，原「线程撕裂者」）=====
            // 思路来自 MrTangLuyao/Bilibili-thread-ripper：把播放器要读的分段再切成多个字节 Range 并发拉。
            // 与上面的 CDN 设置**互不干扰**：CDN 决定"用哪个节点"，这里只决定"节点上的字节怎么并发拉"。
            }
            if (MoreSection.RIPPER in sections) {
            preferenceCategory(
                key = "thread_ripper",
                title = { Text("海外加速（分段并发下载）") }
            )
            switchPreference(
                key = SettingPreferences.ThreadRipperEnable.name,
                defaultValue = false,
                title = { Text("启用分段并发下载") },
                summary = {
                    Text(
                        if (it) "已开启：分段切成多块并发下载；拿不到长度的请求自动退回单连接"
                        else "分段切成多块并发下载，海外/卡顿时建议开"
                    )
                },
            )
            // 并发连接数：**就地内联在这一页**（原来还要再点一次「并发设置」进三级页，
            // 一级页明明能放下 → 用户嫌多此一举）。滑块本体原来在 ThreadRipperSettingPage，
            // 那个页面已删除。
            val ripperOn =
                (prefValues[SettingPreferences.ThreadRipperEnable.name] as? Boolean) ?: false
            sliderIntPreference(
                key = SettingPreferences.ThreadRipperThreads.name,
                defaultValue = 4,
                valueRange = 0..maxThreads,
                // zhanghai 的 SliderPreference：steps = 两端点之间的档位数，故为 (end - start - 1)
                valueSteps = (maxThreads - 1).coerceAtLeast(0),
                // 总开关没开时这根滑块不生效 → 置灰（跟视频格式无关）
                enabled = { ripperOn },
                title = { Text("并发连接数") },
                valueText = { v ->
                    Text(if (v <= 0) "不限（= 本机 $maxThreads 条）" else "$v 条连接")
                },
                summary = { v ->
                    // 把"这个档位实际会发生什么"直接算给用户看（上游的算法：区间平均等分，每份至少 64KB）
                    val n = if (v <= 0) maxThreads else v
                    Text(
                        if (!ripperOn) "总开关关着 —— 先打开上面的开关"
                        else "把一个分段分给 $n 条连接并发拉（默认 4，最多 $maxThreads 条）"
                    )
                },
            )
            // 说明就留一条 QA（用户要求）：什么时候该调大。
            // 多 CDN 抢跑**不用写** —— 它不管 CDN 竞速开不开都会抢跑（候选来自 baseUrl + backupUrl，
            // CdnNodePool 照样登记多个节点，竞速只影响"谁是第一个"）。
            // ── 2026-09-25 四点改进的开关（默认值一律取"不会变差"的那一侧）──
            // 总开关没开时整块置灰：这些开关只作用在分段并发这一层，单连接时它们没有任何效果。
            switchPreference(
                key = SettingPreferences.ThreadRipperSmartAssign.name,
                defaultValue = true,
                enabled = { ripperOn },
                title = { Text("智能节点调度") },
                summary = {
                    Text(
                        "按实测吞吐加权分配分块（快的节点多领活、慢的不再平均占坑）；" +
                            "速度分 90 秒过期、单次不足 48KB 不计分。关掉 = 老的「平均轮转」。"
                    )
                },
            )
            switchPreference(
                key = SettingPreferences.ThreadRipperAdaptiveHedge.name,
                defaultValue = true,
                enabled = { ripperOn },
                title = { Text("自适应抢跑延迟") },
                summary = {
                    Text("抢跑错峰按实测首块耗时自动调整（400~900ms）；只会比原来的固定 900ms 更早，不会更晚。")
                },
            )
            switchPreference(
                key = SettingPreferences.ThreadRipperPushback.name,
                defaultValue = true,
                enabled = { ripperOn },
                title = { Text("412/429 风控退让") },
                summary = {
                    Text("被限流时先降一档并发 + 冷静 180 秒；冷静期内再次被限流才熔断 10 分钟（原来是一被限流就熔断）。")
                },
            )
            switchPreference(
                key = SettingPreferences.ThreadRipperCrossHost.name,
                defaultValue = false,
                enabled = { ripperOn },
                title = { Text("跨节点候选合成（实验性）") },
                summary = { on ->
                    Text(
                        if (on) {
                            "已开启：会把同一份签名地址换到 B站其它 CDN 域名上试（候选 2~4 → 最多 12 条）；" +
                                "签名能否跨域名复用未经验证，若出现大量失败请关掉。"
                        } else {
                            "把 API 给的地址换到 B站其它 CDN 域名，候选更多、抢跑更有牌可打。" +
                                "★ 默认关：签名能否跨域名复用未经验证，可能反而引入 403/412。"
                        }
                    )
                },
            )
            preferenceCategory(
                key = "thread_ripper_qa",
                title = { Text("说明") }
            )
            preference(
                key = "tr_qa_when_bigger",
                enabled = false,
                title = { Text("Q：什么时候该调大？") },
                summary = {
                    Text("卡顿、4K 缓冲跟不上就调大；手机一般 4~8 条够用，连接越多开销越大。")
                },
            )
            preference(
                key = "thread_ripper_about",
                title = { Text("参考项目：Bilibili-thread-ripper") },
                summary = { Text("本功能思路来源（点开 GitHub）") },
                onClick = {
                    // 防连点：连点 N 次不该拉起 N 个浏览器
                    if (ClickGuard.allow("flags:thread_ripper_about")) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(
                                        "https://github.com/MrTangLuyao/Bilibili-thread-ripper"
                                    )
                                )
                            )
                        }
                    }
                },
            )

            // ===== CDN =====
            }
            if (MoreSection.CDN in sections) {
            preferenceCategory(
                key = "cdn",
                title = { Text("CDN") }
            )
            switchPreference(
                key = SettingPreferences.CdnRaceEnabled.name,
                // 必须和播放器读取时的默认值一致（PlayerDelegate2 里是 ?: true）：
                // 否则没动过开关的用户看到"关"，实际每次播放都在竞速
                defaultValue = true,
                title = { Text("CDN 竞速") },
                summary = { Text("播放前并发测试各 CDN 节点延迟，自动选最快的") },
            )
            switchPreference(
                key = SettingPreferences.AudioIndependentCdn.name,
                defaultValue = false,
                title = { Text("音频不跟随 CDN") },
                summary = { Text("音频用默认 CDN，仅视频参与竞速") },
            )
            val cdnLabel = CdnHosts.list.find { it.key == currentCdnKey }?.label ?: "默认（API 自动分配）"
            preference(
                key = "cdn_select",
                title = { Text("CDN 固定主机") },
                summary = { Text("当前：$cdnLabel") },
                onClick = { showCdnDialog = true }
            )


            }
            if (MoreSection.BOTTOM_BAR in sections) {
            preferenceCategory(
                key = "behavior",
                title = {
                    Text("底栏与导航")
                }
            )
                        // 【已移除】新弹幕引擎开关 — V2引擎已废弃
            switchPreference(
                key = SettingPreferences.BottomBarLock.name,
                title = {
                    Text("锁定底栏")
                },
                summary = {
                    Text("底栏不再随滚动隐藏，始终固定显示")
                },
                defaultValue = true,
            )
            // 只有"允许随滚动隐藏"（锁定底栏 = 关）时才谈得上"标题行要不要一起藏"
            val bottomBarLocked = (prefValues[SettingPreferences.BottomBarLock.name] as? Boolean) ?: true
            if (!bottomBarLocked) {
                switchPreference(
                    key = SettingPreferences.BottomBarScrollHideTitle.name,
                    title = {
                        Text("标题行一起隐藏")
                    },
                    summary = {
                        Text("滚动隐藏时，页名那一条也一起收起来")
                    },
                    defaultValue = true,
                )
            }
            }

            if (MoreSection.STORAGE in sections) {
            preferenceCategory(
                key = "storage",
                title = {
                    Text("存储")
                }
            )
            glidePreference(
                key = "glide_image_cache",
            )
            textIntPreference(
                key = SettingPreferences.ImageDiskCacheSize.name,
                defaultValue = 50,
                title = { Text("图片缓存上限") },
                label = " MB",
                summary = { Text("当前: ${it}MB，Glide 图片磁盘缓存上限，重启后生效") },
            )
            textIntPreference(
                key = SettingPreferences.PlayerDiskCacheSize.name,
                defaultValue = 512,
                title = { Text("视频播放磁盘缓存") },
                label = " MB",
                summary = { Text("当前: ${it}MB，用于本地缓存视频数据，暂停/回退时减少重复网络请求。调大可缓存更长时间的内容") },
            )
            preference(
                key = "reset_all",
                title = {
                    Text("重置所有设置")
                },
                summary = {
                    Text("清除所有偏好设置、屏蔽数据、缓存，部分需重启生效")
                },
                onClick = {
                    showResetDialog = true
                },
            )

                // 关于
            }
            if (MoreSection.ABOUT in sections) {
            preferenceCategory(
                key = "about",
                title = { Text("关于") }
            )
                    preference(
                        key = "app_version",
                        title = { Text("当前版本") },
                        summary = { Text(appVersionLabel) },
                    )
            preference(
                key = "github_repo",
                title = { Text("我的 GitHub 仓库") },
                summary = { Text("8023BIAO/bilimiao2（本 App 的源码）") },
                onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                    intent.data = android.net.Uri.parse("https://github.com/8023BIAO/bilimiao2")
                    context.startActivity(intent)
                },
            )
            // 本 App 是上游的 mod，得给原作者留个名（用户要求：别让人先点我的仓库、再点 fork 才能找到上游）
            preference(
                key = "github_upstream",
                title = { Text("原版项目（本 App 的上游）") },
                summary = { Text("10miaomiao/bilimiao2 · 感谢原作者") },
                onClick = {
                    if (ClickGuard.allow("flags:github_upstream")) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/10miaomiao/bilimiao2")
                                )
                            )
                        }
                    }
                },
            )

                // 错误日志入口
                preference(
                    key = "error_logs",
                    title = { Text("错误日志") },
                    summary = { Text("查看应用崩溃和错误记录") },
                    onClick = viewModel::toErrorLogPage,
                )

            }
            item("bottom") {
                Spacer(
                    modifier = Modifier.height(
                        windowInsets.bottomDp.dp + windowStore.bottomAppBarHeightDp.dp
                    )
                )
            }
        }

        if (showGuestConfirmDialog) {
            OverlayAlertDialog(
                onDismissRequest = { showGuestConfirmDialog = false },
                title = { Text("开启游客模式") },
                text = {
                    Text("开启游客模式会清除当前登录状态，建议先导出身份信息文件以便下次快速恢复。是否继续？")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showGuestConfirmDialog = false
                        viewModel.toggleGuestMode(true)
                        hasBackup = true
                    }) {
                        Text("直接开启")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showGuestConfirmDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

        if (showResetDialog) {
            OverlayAlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("确认重置") },
                text = { Text("将把所有设置恢复为默认值，并清除屏蔽数据\n（不会退出登录，也不会清理图片/播放缓存）\n此操作不可撤销。确定继续？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showResetDialog = false
                            scope.launch {
                                try {
                                    SettingsExporter.resetAll(context)
                                    Toast.makeText(context, "设置已重置，正在重启...", Toast.LENGTH_SHORT).show()
                                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                    if (intent != null) {
                                        context.startActivity(android.content.Intent.makeRestartActivityTask(intent.component))
                                    }
                                    java.lang.System.exit(0)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "重置失败：${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) {
                        Text("确定重置")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

        if (showCdnDialog) {
            CdnSelectDialog(
                currentCdnKey = currentCdnKey,
                onDismiss = { showCdnDialog = false },
                onCdnSelected = { key ->
                    currentCdnKey = key
                    showCdnDialog = false
                    scope.launch {
                        SettingPreferences.edit(context) {
                            it[SettingPreferences.SelectedCdnHost] = key
                        }
                    }
                }
            )
        }

    }
}

/** 把毫秒格式化成"1 分 05 秒" / "12 秒"，给监控进度显示用 */
private fun fmtDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val sec = total % 60
    return if (m > 0) "$m 分 ${sec.toString().padStart(2, '0')} 秒" else "$sec 秒"
}
