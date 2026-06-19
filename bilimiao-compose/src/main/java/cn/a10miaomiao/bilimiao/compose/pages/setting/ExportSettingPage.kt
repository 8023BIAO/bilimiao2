package cn.a10miaomiao.bilimiao.compose.pages.setting

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import com.a10miaomiao.bilimiao.comm.datastore.SettingsExport
import com.a10miaomiao.bilimiao.comm.datastore.SettingsExporter
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.kodein.di.compose.rememberInstance
import java.io.BufferedReader
import java.io.InputStreamReader

@Serializable
class ExportSettingPage : ComposePage() {

    @Composable
    override fun Content() {
        ExportSettingPageContent()
    }
}

private sealed class ExportStatus {
    data class Success(val message: String) : ExportStatus()
    data class Error(val message: String) : ExportStatus()
}

@Composable
private fun ExportSettingPageContent() {
    val context = LocalContext.current
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())
    val scope = rememberCoroutineScope()
    var showImportConfirm by remember { mutableStateOf(false) }
    var pendingJson by remember { mutableStateOf<String?>(null) }
    var pendingFileName by remember { mutableStateOf<String?>(null) }
    var exportStatus by remember { mutableStateOf<ExportStatus?>(null) }

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
                exportStatus = ExportStatus.Success("导出成功")
            } catch (e: Exception) {
                exportStatus = ExportStatus.Error("导出失败: ${e.message}")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
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
                    val jsonString = SettingsExporter.truncateToValidJson(rawJson)
                    // Validate JSON
                    kotlinx.serialization.json.Json.decodeFromString<SettingsExport>(jsonString)
                    pendingJson = jsonString
                    pendingFileName = uri.lastPathSegment
                    showImportConfirm = true
                } catch (e: Exception) {
                    exportStatus = ExportStatus.Error("读取失败: ${e.message}")
                }
            }
        }
    }

    PageConfig(title = "设置导入导出")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = windowInsets.leftDp.dp,
                end = windowInsets.rightDp.dp,
            )
    ) {
        item("top") {
            Spacer(modifier = Modifier.height(windowInsets.topDp.dp + 16.dp))
        }

        item("info") {
            Text(
                text = "导出全部设置到 JSON 文件，包括：\n• 播放/弹幕/主题/首页等参数\n• 屏蔽关键字/UP主/标签/UP名\n• 评论屏蔽词 / 弹幕过滤\n• 时光姬时间 / DPI / 代理\n\n导入后会自动重启应用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        item("export_btn") {
            OutlinedButton(
                onClick = {
                    exportLauncher.launch("bilimiao_settings_${System.currentTimeMillis()}.json")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("导出设置文件")
            }
        }

        item("import_btn") {
            OutlinedButton(
                onClick = {
                    importLauncher.launch(arrayOf("application/json", "*/*"))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("导入设置文件")
            }
        }

        exportStatus?.let { status ->
            item("status") {
                val (text, color) = when (status) {
                    is ExportStatus.Success -> Pair("✅ ${status.message}", MaterialTheme.colorScheme.primary)
                    is ExportStatus.Error -> Pair("❌ ${status.message}", MaterialTheme.colorScheme.error)
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        item("bottom") {
            Spacer(
                modifier = Modifier.height(
                    windowInsets.bottomDp.dp + windowStore.bottomAppBarHeightDp.dp
                )
            )
        }
    }

    if (showImportConfirm) {
        AlertDialog(
            title = { Text("确认导入") },
            text = {
                Text(
                    "将从 \"${pendingFileName}\" 导入设置。\n\n" +
                    "警告：当前所有设置将被覆盖，导入后需重启应用生效。"
                )
            },
            onDismissRequest = {
                showImportConfirm = false
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirm = false
                        scope.launch {
                            try {
                                val count = SettingsExporter.importFromJson(context, pendingJson!!)
                                exportStatus = ExportStatus.Success("已导入 $count 项设置，请重启应用")
                                withContext(Dispatchers.Main) {
                                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                    if (intent != null) {
                                        context.startActivity(android.content.Intent.makeRestartActivityTask(intent.component))
                                    }
                                    java.lang.System.exit(0)
                                }
                            } catch (e: Exception) {
                                exportStatus = ExportStatus.Error("导入失败: ${e.message}")
                            }
                        }
                    }
                ) {
                    Text("确认导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}
