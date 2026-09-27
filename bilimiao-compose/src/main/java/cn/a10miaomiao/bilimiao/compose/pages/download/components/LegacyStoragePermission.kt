package cn.a10miaomiao.bilimiao.compose.pages.download.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cn.a10miaomiao.bilimiao.download.DownloadStoragePolicy

/**
 * 老系统（Android 6~9 / API 23~28）的"下载存储权限"请求入口，只弹一次。
 *
 * 为什么只在这里做：Android 10（API 29）起公共目录走 MediaStore、**完全不需要权限**，
 * 所以要弹出请求的只有老系统；而且权限请求需要 Activity，服务里没有 UI（服务只做
 * "已授权判断 + 失败回退私有目录"）。
 *
 * 为什么拒绝后不再弹：用户拒绝就写 SharedPreferences 标记（[DownloadStoragePolicy.markLegacyPermissionDenied]），
 * 之后所有下载直接走应用私有目录 —— 功能不受影响（只是文件在私有目录、卸载会丢），也不再打扰用户。
 */
@Composable
fun LegacyStoragePermissionEffect() {
    val context = LocalContext.current
    // 只在本次组合里发起一次，避免重组时反复弹
    var launched by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // 授权成功：发布环节会自动把（含之前下载好的）完成剧集发到公共目录
            DownloadStoragePolicy.clearLegacyPermissionDenied(context)
        } else {
            DownloadStoragePolicy.markLegacyPermissionDenied(context)
        }
    }
    LaunchedEffect(Unit) {
        if (!launched && DownloadStoragePolicy.shouldRequestLegacyPermission(context)) {
            launched = true
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}
