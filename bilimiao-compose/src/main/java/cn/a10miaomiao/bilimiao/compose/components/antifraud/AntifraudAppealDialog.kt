package cn.a10miaomiao.bilimiao.compose.components.antifraud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.components.dialogs.AutoSheetDialog
import cn.a10miaomiao.bilimiao.compose.pages.community.components.CommentAntifraudLauncher
import com.a10miaomiao.bilimiao.comm.apis.CommentAppeal
import com.a10miaomiao.bilimiao.comm.toast

/**
 * 申诉弹窗的入参（谁都能 set 一下把它叫出来）。
 *
 * 为什么不把这些值留在 UI 里现取：理由存在 DataStore、额度存在 SharedPreferences，
 * 都是 IO；在"点按钮那一刻"读会把主线程卡一下，而且读失败没有兜底。统一由
 * [CommentAntifraudLauncher.showAppealDialog] 在 IO 线程备好再塞进来。
 */
data class AntifraudAppealRequest(
    val oid: Long,
    val type: Int,
    val rpid: Long,
    /** 被判限流的那条评论原文（会拼进申诉理由） */
    val message: String = "",
    val hasPictures: Boolean = false,
    val pictureCount: Int = 0,
    /** 直接以「图文动态申诉」打开（设置页那个入口） */
    val startAsDynamic: Boolean = false,
    /** 预填的"所在稿件 BV 号或位置链接"；图文动态模式为空 */
    val presetTarget: String = "",
    /** 预填的申诉理由（保存过的 / 内置正式文案） */
    val presetReason: String = CommentAppeal.DEFAULT_REASON,
    /** 额度说明那一行（"官方限制 24 小时 3 次；本机记录已用 N 次"） */
    val quotaText: String = "",
    val dailyLimit: Int = CommentAppeal.DAILY_LIMIT,
)

/**
 * 申诉弹窗的全局状态 —— 挂在 ComposeFragment 根部渲染（见 [AntifraudAppealDialogHost]）。
 *
 * 为什么不继续用 DialogX（2026-09-27 用户实测后的结论）：
 *   ① DialogX 自己的 MaterialYou 调色板跟本 App 的主题色/点缀色对不上（用户："没有使用我这个软件主题的搭配色"）；
 *   ② 它那个 CustomDialog 容器是 wrap_content + centerInParent，横屏/大字体时底部按钮会被裁。
 * 现在改用 App 自己的 [AutoSheetDialog] —— 就是直播页、番剧首页那个"底栏筛选弹窗"同一套：
 * 主题色走 MaterialTheme（含用户自定义主题色/materialKolor），
 * 竖屏贴底、横屏居中、安全区与旋转由 AnyPopDialog 统一处理（用户原话："它不管你软件怎么旋转屏幕…都会居中"）。
 */
object AntifraudAppealDialogState {

    var request: AntifraudAppealRequest? by mutableStateOf(null)
        private set

    fun show(r: AntifraudAppealRequest) {
        request = r
    }

    fun close() {
        request = null
    }
}

/** 挂在 ComposeFragment 根部的宿主：有请求就弹，跟 MessageDialog 一个层级 */
@Composable
fun AntifraudAppealDialogHost() {
    val request = AntifraudAppealDialogState.request ?: return
    AutoSheetDialog(
        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        onDismiss = { AntifraudAppealDialogState.close() },
    ) {
        AppealFormSheet(request)
    }
}

/** 申诉表单：官方 H5 同款两个输入（类型 / 位置 + 理由） */
@Composable
private fun AppealFormSheet(request: AntifraudAppealRequest) {
    var dynamic by remember(request) { mutableStateOf(request.startAsDynamic) }
    var userPickedType by remember(request) { mutableStateOf(request.startAsDynamic) }
    var target by remember(request) { mutableStateOf(request.presetTarget) }
    var reason by remember(request) { mutableStateOf(request.presetReason) }

    // 自动填出来的目标一定按"评论申诉"：哪怕这条评论挂在动态下面，要申诉的也是那条评论、不是动态本身
    val presetIsComment = !request.startAsDynamic && request.presetTarget.isNotBlank()

    fun currentTarget(): CommentAppeal.Target = if (dynamic) {
        CommentAppeal.Target(
            kind = CommentAppeal.Kind.DYNAMIC,
            link = CommentAppeal.asDynamicLink(target) ?: target.trim(),
        )
    } else {
        CommentAppeal.replyTarget(
            value = target,
            autoFilled = presetIsComment && target.trim() == request.presetTarget.trim(),
        )
    }

    val detected = currentTarget()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .imePadding()
    ) {
        Text(
            text = if (dynamic) "图文动态申诉" else "申诉此评论",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            // 文案里**不再重复额度**（用户实测："要不然底部都有一个重复提示了"）——
            // 额度只在下面那一行 `quotaText` 里说一次。
            text = if (dynamic) {
                "动态被限流/隐藏时用：uid + 动态链接；提交后结果发到「消息 → 系统通知」"
            } else {
                "评论被限流/仅自己可见时用；提交后结果发到「消息 → 系统通知」"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        // 可滚动内容区（吃满剩余高度，底部按钮始终钉在下面 —— 与番剧/直播筛选弹窗同一结构）
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "申诉类型",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !dynamic,
                    onClick = {
                        dynamic = false
                        userPickedType = true
                    },
                    label = { Text("评论申诉") },
                )
                FilterChip(
                    selected = dynamic,
                    onClick = {
                        dynamic = true
                        userPickedType = true
                    },
                    label = { Text("图文动态申诉") },
                )
            }

            Text(
                text = if (dynamic) "申诉动态 id 或链接" else "所在稿件 BV 号或位置链接",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
            )
            OutlinedTextField(
                value = target,
                onValueChange = { v ->
                    target = v
                    // 用户自己改目标时才替他自动判类型（预填值不动类型，免得把"评论申诉"翻成"动态申诉"）
                    if (!v.isBlank()) {
                        val d = CommentAppeal.detectTarget(v, request.oid, request.type, autoFilled = false)
                        val want = d.kind == CommentAppeal.Kind.DYNAMIC
                        if (!userPickedType && want != dynamic) dynamic = want
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("BV号 / 位置链接 / 动态链接") },
                supportingText = {
                    Text(
                        text = detected.label,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "申诉理由（${CommentAppeal.REASON_MIN}~${CommentAppeal.REASON_MAX} 字）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${reason.length}/${CommentAppeal.REASON_MAX}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = reason,
                onValueChange = { v ->
                    if (v.length <= CommentAppeal.REASON_MAX) reason = v
                },
                modifier = Modifier.fillMaxWidth(),
                // ★ 用户要求（2026-09-27）：理由框至少 6 行，太矮了改不动、也看不全
                minLines = 6,
                maxLines = 12,
            )
            if (request.quotaText.isNotBlank()) {
                Text(
                    text = request.quotaText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { reason = CommentAppeal.DEFAULT_REASON }) {
                Text("用默认理由")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { AntifraudAppealDialogState.close() }) {
                Text("取消")
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = {
                    val t = currentTarget()
                    when {
                        t.kind == CommentAppeal.Kind.DYNAMIC && t.link.isBlank() ->
                            toast("先填动态 id 或链接")
                        t.kind == CommentAppeal.Kind.REPLY && t.oid.isNullOrBlank() && t.url.isNullOrBlank() ->
                            toast("先填 BV 号或位置链接")
                        reason.trim().length < CommentAppeal.REASON_MIN ->
                            toast("申诉理由至少 ${CommentAppeal.REASON_MIN} 字")
                        else -> {
                            AntifraudAppealDialogState.close()
                            CommentAntifraudLauncher.submitAppeal(
                                target = t,
                                reasonBase = reason.trim(),
                                comment = request.message,
                                hasPictures = request.hasPictures,
                                pictureCount = request.pictureCount,
                                oid = request.oid,
                                type = request.type,
                                rpid = request.rpid,
                            )
                        }
                    }
                }
            ) {
                Text("提交申诉")
            }
        }
    }
}
