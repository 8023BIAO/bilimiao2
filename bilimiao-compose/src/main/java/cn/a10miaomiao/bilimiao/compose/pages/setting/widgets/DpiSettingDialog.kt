package cn.a10miaomiao.bilimiao.compose.pages.setting.widgets

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.components.dialogs.OverlayAlertDialog
import com.a10miaomiao.bilimiao.comm.toast

/**
 * 应用内 DPI / 字体缩放弹窗。
 *
 * 从 FlagsSettingPage 里抽出来做成公共组件：用户反馈"设置 → 界面 → 显示与字号 → 再点一下"
 * 要三步才出弹窗，现在设置首页那一级直接调它（一步到位）。
 * 值存在 DefaultSharedPreferences 的 app_dpi / app_font_scale，改完用 recreate() 生效。
 */
@Composable
fun DpiSettingDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val defaultDpi = context.applicationContext.resources.configuration.densityDpi
    val defaultFontScale = context.applicationContext.resources.configuration.fontScale
    var dpiText by remember {
        val t = defaultDpi.toString()
        mutableStateOf(TextFieldValue(t, TextRange(t.length)))
    }
    var fontScaleText by remember {
        val t = defaultFontScale.toString()
        mutableStateOf(TextFieldValue(t, TextRange(t.length)))
    }
    OverlayAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DPI 设置") },
        text = {
            Column {
                Text("系统默认DPI：$defaultDpi   字缩：$defaultFontScale")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = dpiText,
                    onValueChange = { dpiText = it },
                    label = { Text("DPI (80~640)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
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
                        // 存进 DefaultSharedPreferences（app_dpi / app_font_scale）——
                        // 与 app 模块 ScreenDpiUtil.readCustomConfiguration 读的是同一份文件；
                        // 注意不能直接引用 app 模块的 ScreenDpiUtil（compose 模块不依赖 app）
                        android.preference.PreferenceManager
                            .getDefaultSharedPreferences(context.applicationContext)
                            .edit()
                            .putInt("app_dpi", dpi)
                            .putFloat("app_font_scale", fontScale)
                            .apply()
                        // 用 recreate() 重新应用配置即可：原来直接 System.exit(0) 会把正在播放的视频、
                        // 正在下载的任务（前台服务）一起杀掉。context 可能被 ContextWrapper 包着，拿不到就明确提示
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
                        Toast.makeText(context, "请输入合法数字", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
