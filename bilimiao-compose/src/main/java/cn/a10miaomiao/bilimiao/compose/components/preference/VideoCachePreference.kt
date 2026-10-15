package cn.a10miaomiao.bilimiao.compose.components.preference

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.a10miaomiao.bilimiao.compose.components.dialogs.OverlayAlertDialog
import com.a10miaomiao.bilimiao.comm.toast
import com.a10miaomiao.bilimiao.comm.utils.VideoCacheUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.Preference

inline fun LazyListScope.videoCachePreference(
    key: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    item(key = key, contentType = "VideoCachePreference") {
        VideoCachePreference(modifier = modifier)
    }
}

/**
 * 「清除视频播放磁盘缓存」。
 *
 * 为什么给一个手动入口：磁盘缓存里一旦进了坏数据（历史上真发生过：别的文件的字节被写进
 * 这个条目的缓存块），表现就是"这个视频怎么都放不出来 / 放的还是上一个视频"，而且**每次都命中**，
 * 用户只能自己去删缓存目录。这里让它在设置里一键可用。
 *
 * 清完**必须重启**：活着的 SimpleCache 已被释放、目录也清了，重启让新会话干净地重建。
 * （"0 = 不启用磁盘缓存"是另一条路：那条路只是不写不读，不会替你删旧文件。）
 */
@Composable
fun VideoCachePreference(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 遍历目录别放在组合期（设置页会掉帧）→ 进页面后异步算，与「图片缓存」同一套做法
    var cacheSize by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) { VideoCacheUtil.sizeText(context) }
    }
    var showDialog by remember { mutableStateOf(false) }

    Preference(
        modifier = modifier,
        title = { Text("清除视频播放磁盘缓存") },
        summary = { Text(cacheSize) },
        onClick = { showDialog = true }
    )

    if (showDialog) {
        OverlayAlertDialog(
            title = { Text("提示") },
            text = { Text("确定清除视频播放磁盘缓存？当前 $cacheSize。清除后应用会自动重启。") },
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        // ★为什么不用协程：`rememberCoroutineScope()` 绑在**组合**上，一点确认弹窗就关、
                        //   界面零反馈，而删目录是秒级窗口 —— 这期间按返回键/转屏离开页面，scope 就被
                        //   cancel ⇒ 收尾（提示 + 重启）跑不到；实测只加 `NonCancellable` 也救不了：
                        //   它只保证**块内**跑完，`withContext` 的**返回**仍按外层那个已取消的 Job 做
                        //   MODE_CANCELLABLE 恢复 ⇒ 抛 JobCancellationException，块后面的代码照样不执行。
                        //   所以这里用**不挂靠组合的普通线程**：与页面生命周期彻底解耦，删完一定提示 + 重启。
                        Thread {
                            runCatching { VideoCacheUtil.clear(context.applicationContext) }
                            Handler(Looper.getMainLooper()).post {
                                toast("已清除视频缓存，正在重启…")
                                restartApp(context)
                            }
                        }.start()
                    }
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 重启应用：与「初始化设置 → 游客模式」同一套做法（重启任务栈 + 退出进程） */
private fun restartApp(context: Context) {
    val restartIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (restartIntent != null) {
        context.startActivity(Intent.makeRestartActivityTask(restartIntent.component))
    }
    System.exit(0)
}
