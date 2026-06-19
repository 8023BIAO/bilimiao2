package cn.a10miaomiao.bilimiao.compose.pages.setting

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.pages.setting.ErrorLogPage
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.preference.rememberPreferenceFlow
import cn.a10miaomiao.bilimiao.compose.components.preference.glidePreference
import cn.a10miaomiao.bilimiao.compose.components.preference.textIntPreference
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.utils.CdnHosts
import cn.a10miaomiao.bilimiao.compose.pages.setting.widgets.CdnSelectDialog
import com.a10miaomiao.bilimiao.comm.datastore.SettingsExporter
import com.a10miaomiao.bilimiao.comm.entity.auth.LoginInfo
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.utils.WbiSigner
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.comm.store.UserStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    var dpiText by remember { mutableStateOf(currentDpi.toString()) }
    var fontScaleText by remember { mutableStateOf(currentFontScale.toString()) }
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
                        wbiCache["mix_key"]?.let { put("mix_key", JsonPrimitive(it.toString())) }
                        wbiCache["last_fetch_day"]?.let { put("last_fetch_day", JsonPrimitive(it as Int)) }
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

                    // 恢复 WBI 缓存
                    jsonObj["wbi"]?.jsonObject?.let { wbiObj ->
                        WbiSigner.restoreWbiCache(mapOf(
                            "mix_key" to (wbiObj["mix_key"]?.jsonPrimitive?.contentOrNull),
                            "last_fetch_day" to (wbiObj["last_fetch_day"]?.jsonPrimitive?.intOrNull),
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
            try {
                val json = runBlocking { SettingsExporter.exportToJson(context) }
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                Toast.makeText(context, "设置已导出", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
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
                    val count = SettingsExporter.importFromJson(context, jsonString)
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

    ProvidePreferenceLocals(
        flow = rememberPreferenceFlow(dataStore)
    ) {
        // 游客模式状态（必须在 Composable 作用域内）
        val loginInfoState by userStore.stateFlow.collectAsState()
        var hasBackup by remember {
            mutableStateOf(
                context.getSharedPreferences("bilimiao_guest_backup", Context.MODE_PRIVATE)
                    .getString("login_info_backup", null) != null
            )
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
                    Text("导出所有设置项配置到文件")
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

            // ===== CDN =====
            preferenceCategory(
                key = "cdn",
                title = { Text("CDN") }
            )
            switchPreference(
                key = SettingPreferences.CdnRaceEnabled.name,
                defaultValue = false,
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
                    dpiText = context.resources.configuration.densityDpi.toString()
                    fontScaleText = context.resources.configuration.fontScale.toString()
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
                title = { Text("GitHub 仓库") },
                summary = { Text("8023BIAO/bilimiao-mod") },
                onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                    intent.data = android.net.Uri.parse("https://github.com/8023BIAO/bilimiao-mod")
                    context.startActivity(intent)
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
            AlertDialog(
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
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("确认重置") },
                text = { Text("将清除所有偏好设置、屏蔽数据、缓存，此操作不可撤销。确定继续？") },
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
            AlertDialog(
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
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = fontScaleText,
                            onValueChange = { fontScaleText = it },
                            label = { Text("字体缩放 (0.5~3.0)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            try {
                                val dpi = dpiText.toInt()
                                val fontScale = fontScaleText.toFloat()
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
                                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                if (intent != null) {
                                    context.startActivity(android.content.Intent.makeRestartActivityTask(intent.component))
                                }
                                java.lang.System.exit(0)
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
