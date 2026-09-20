package cn.a10miaomiao.bilimiao.compose.pages.setting

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

@Serializable
class FlagsSettingPage : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel: FlagsSettingPageViewModel = diViewModel()
        FlagsSettingPageContent(viewModel)
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

    /** 分段并发下载（原「线程撕裂者」）的并发子设置页 */
    fun toThreadRipperSettingPage() {
        pageNavigation.navigate(ThreadRipperSettingPage())
    }

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


@Composable
private fun FlagsSettingPageContent(
    viewModel: FlagsSettingPageViewModel
) {
    PageConfig(
        title = "实验性功能"
    )
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val context = LocalContext.current
    val userStore: UserStore by rememberInstance()
    val dataStore = remember {
        SettingPreferences.run { context.dataStore }
    }
    val scope = rememberCoroutineScope()
    var showResetDialog by remember { mutableStateOf(false) }
    var showDpiDialog by remember { mutableStateOf(false) }
    var showGuestConfirmDialog by remember { mutableStateOf(false) }
    val currentDpi = context.resources.configuration.densityDpi
    val currentFontScale = context.resources.configuration.fontScale
    // 分段并发下载：本机最多能开多少连接 = 处理器核数（并发设置页滑块的上限也是它）
    val maxThreads = remember {
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    }
    // 光标放末尾：String 重载会让 DPI/字缩输入框的光标停在开头
    var dpiText by remember {
        val t = currentDpi.toString()
        mutableStateOf(TextFieldValue(t, TextRange(t.length)))
    }
    var fontScaleText by remember {
        val t = currentFontScale.toString()
        mutableStateOf(TextFieldValue(t, TextRange(t.length)))
    }
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

                    // 保存设备指纹到 SharedPreferences
                    if (buvid.isNotBlank()) {
                        context.getSharedPreferences(BilimiaoCommApp.APP_NAME, Context.MODE_PRIVATE)
                            .edit().putString("buvid", buvid).apply()
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
        // 视频格式：MP4(2) 时分段并发下载**对它无效**（MP4 是整段顺序下载、没有分段可切，
        // ThreadRipperDataSource 的并发条件要求"请求长度已知"，渐进式请求 length=UNSET → 直接单连接透传）
        // → 按用户要求：置灰不可点，并且如果原本开着就自动关掉，同时把原因写在说明里。
        val prefValues = prefFlow.collectAsState().value
        val fnvalValue = (prefValues[SettingPreferences.PlayerFnval.name] as? Int)
            ?: SettingConstants.PLAYER_FNVAL_DASH
        val mp4Selected = fnvalValue == SettingConstants.PLAYER_FNVAL_MP4
        LaunchedEffect(mp4Selected) {
            if (mp4Selected &&
                (prefValues[SettingPreferences.ThreadRipperEnable.name] as? Boolean) == true
            ) {
                SettingPreferences.edit(context) {
                    it[SettingPreferences.ThreadRipperEnable] = false
                }
                Toast.makeText(
                    context,
                    "当前视频格式是 MP4：分段并发下载对它无效，已自动关闭",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        // 游客模式状态（必须在 Composable 作用域内）
        val loginInfoState by userStore.stateFlow.collectAsState()
        // 组合期直接读 SharedPreferences 会在主线程做首次磁盘加载（进页面就掉帧）→ 异步读
        var hasBackup by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            hasBackup = withContext(Dispatchers.IO) {
                context.getSharedPreferences("bilimiao_guest_backup", Context.MODE_PRIVATE)
                    .getString("login_info_backup", null) != null
            }
        }
        // 正在监控的评论（设置页里实时显示进度）
        val monitors = cn.a10miaomiao.bilimiao.compose.components.antifraud.AntifraudMonitor.sessions
        // 每秒更新一次"当前时间"，进度条与"已盯多久"才会动（页面不可见时不会跑）
        var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                nowTick = System.currentTimeMillis()
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
            preferenceCategory(
                key = "dev_tools",
                title = {
                    Text("开发工具")
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
            preferenceCategory(
                key = "network",
                title = { Text("网络测试") }
            )
            switchPreference(
                key = SettingPreferences.WbiSignEnabled.name,
                defaultValue = true,
                title = { Text("WBI 签名") },
                summary = { Text("对 B站 Web API 自动添加 WBI 签名（-352 时关闭重试）") },
            )

            switchPreference(
                key = SettingPreferences.AiSummaryEnabled.name,
                defaultValue = false,
                title = { Text("AI 视频总结") },
                summary = { Text("在视频详情页「简介」上方显示，调用B站官方接口生成视频摘要") },
            )

            // ===== 评论反诈（发评后自动检测是否被限流）=====
            // 判定规则照搬开源项目 biliSendCommAntifraud：
            // ShadowBan 的评论"带 Cookie 能找到、游客找不到"。
            preferenceCategory(
                key = "antifraud",
                title = { Text("评论反诈") }
            )
            switchPreference(
                key = SettingPreferences.AntifraudEnabled.name,
                defaultValue = false,
                title = { Text("发评论后自动检测是否被限流") },
                summary = {
                    Text(
                        if (it) "已开启：查出被限流会弹窗，可删除或去申诉"
                        else "判断评论是否只有你自己看得见"
                    )
                },
            )
            // 复查开关 + 监控时长滑条：只查一次会漏掉"先正常、过一会儿才被限流"的情况
            switchPreference(
                key = SettingPreferences.AntifraudRecheckEnabled.name,
                defaultValue = true,
                title = { Text("自动复查（推荐开）") },
                summary = {
                    Text(
                        if (it) "已开启：正常也会继续盯，直到状态变化或盯满时长"
                        else "只查一次（评论可能先正常、过一会儿才被限流）"
                    )
                },
            )
            sliderIntPreference(
                key = SettingPreferences.AntifraudRecheckMinutes.name,
                defaultValue = 5,
                valueRange = 1..30,
                // steps = 两端点之间的档位数 = 28（每分钟一档）
                valueSteps = 28,
                title = { Text("复查监控时长") },
                valueText = { v -> Text("$v 分钟") },
                summary = { v -> Text("首查 5 秒后开始，之后每 30 秒查一次，共盯 $v 分钟") },
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
                        key = "antifraud_mon_${m.key}",
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
            val lastResult = com.a10miaomiao.bilimiao.comm.antifraud.AntifraudLastResult.load(context)
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
                                "判定：${lastResult.detail}\n（点这里可「重新检测」）"
                        )
                    },
                    onClick = {
                        if (ClickGuard.allow("flags:antifraud_last")) {
                            com.kongzue.dialogx.dialogs.MessageDialog.build()
                                .setTitle((if (lastResult.isBad) "⚠️ " else "✅ ") + lastResult.title)
                                .setMessage(
                                    "${lastResult.where}\n评论 ID：${lastResult.rpid}\n\n" +
                                        "评论内容：${lastResult.message.ifBlank { "(无)" }}\n\n" +
                                        "判定依据：${lastResult.detail}\n\n" +
                                        "检测时间：${lastResult.timeText()}"
                                )
                                .setOkButton("重新检测") { _, _ ->
                                    // 手动复检：不发新评论也能验证判定（评论常常几分钟后才被限流）
                                    cn.a10miaomiao.bilimiao.compose.pages.community.components
                                        .CommentAntifraudLauncher.recheck(
                                            oid = lastResult.oid,
                                            type = lastResult.type,
                                            rpid = lastResult.rpid,
                                            root = lastResult.root,
                                            message = lastResult.message,
                                        )
                                    false
                                }
                                .setCancelButton("清空记录") { _, _ ->
                                    com.a10miaomiao.bilimiao.comm.antifraud.AntifraudLastResult.clear(context)
                                    false
                                }
                                .setOtherButton("关闭")
                                .show()
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
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.bilibili.com/h5/comment/appeal")
                            )
                        )
                    }
                },
            )

            // ===== 空降助手（原在「播放设置」里，用户反馈藏得太深 → 移到这里）=====
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
            preferenceCategory(
                key = "thread_ripper",
                title = { Text("海外加速（分段并发下载）") }
            )
            switchPreference(
                key = SettingPreferences.ThreadRipperEnable.name,
                defaultValue = false,
                // MP4 源下置灰（不可点）—— 见上面 mp4Selected 的说明
                enabled = { !mp4Selected },
                title = { Text("启用分段并发下载") },
                summary = {
                    Text(
                        when {
                            mp4Selected -> "当前是 MP4 源，改了没用（先改成 DASH）"
                            it -> "已开启：分段切成多块并发下载（仅 DASH 有效）"
                            else -> "分段切成多块并发下载，海外建议开"
                        }
                    )
                },
            )
            preference(
                key = "thread_ripper_threads_entry",
                title = { Text("并发设置") },
                enabled = !mp4Selected,
                summary = {
                    Text(
                        if (mp4Selected) "当前是 MP4 源，改了没用"
                        else "并发连接数（默认 4，最多 $maxThreads 条）"
                    )
                },
                onClick = viewModel::toThreadRipperSettingPage,
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


            preferenceCategory(
                key = "behavior",
                title = {
                    Text("界面实验")
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
            preference(
                key = "dpi",
                title = {
                    Text("应用内DPI设置")
                },
                summary = {
                    Text("当屏幕过大或过小时，可以尝试调整一下")
                },
                onClick = {
                    val d = context.resources.configuration.densityDpi.toString()
                    val f = context.resources.configuration.fontScale.toString()
                    dpiText = TextFieldValue(d, TextRange(d.length))
                    fontScaleText = TextFieldValue(f, TextRange(f.length))
                    showDpiDialog = true
                },
            )

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
            preferenceCategory(
                key = "about",
                title = { Text("关于") }
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

        if (showDpiDialog) {
            val defaultDpi = context.applicationContext.resources.configuration.densityDpi
            val defaultFontScale = context.applicationContext.resources.configuration.fontScale
            OverlayAlertDialog(
                onDismissRequest = { showDpiDialog = false },
                title = { Text("DPI 设置") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        Text("系统默认DPI：$defaultDpi   字缩：$defaultFontScale")
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = dpiText,
                            onValueChange = { dpiText = it },
                            label = { Text("DPI (80~640)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = fontScaleText,
                            onValueChange = { fontScaleText = it },
                            label = { Text("字体缩放 (0.5~3.0)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            try {
                                val dpi = dpiText.text.toInt()
                                val fontScale = fontScaleText.text.toFloat()
                                if (dpi < 80 || dpi > 640) {
                                    Toast.makeText(context, "DPI 需在 80~640 之间", Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                if (fontScale < 0.5f || fontScale > 3.0f) {
                                    Toast.makeText(context, "字体缩放需在 0.5~3.0 之间", Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                                prefs.edit()
                                    .putInt("app_dpi", dpi)
                                    .putFloat("app_font_scale", fontScale)
                                    .commit()
                                // 用 recreate() 重新应用配置即可：原来直接 System.exit(0) 会把
                                // 正在播放的视频、正在下载的任务（前台服务）一起杀掉
                                // context 不一定是 Activity（可能被 ContextWrapper 包着），
                                // 拿不到就明确提示，别让"设置存了却不生效"变成静默失败
                                var ctx: android.content.Context? = context
                                while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) {
                                    ctx = ctx.baseContext
                                }
                                val hostActivity = ctx as? android.app.Activity
                                if (hostActivity != null) {
                                    hostActivity.recreate()
                                } else {
                                    toast("设置已保存，请手动重启应用后生效")
                                }
                            } catch (e: NumberFormatException) {
                                Toast.makeText(context, "请输入数字", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("确认")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDpiDialog = false }) {
                        Text("取消")
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
