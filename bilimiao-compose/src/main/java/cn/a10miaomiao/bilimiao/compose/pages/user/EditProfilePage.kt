package cn.a10miaomiao.bilimiao.compose.pages.user

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.status.BiliFailBox
import com.a10miaomiao.bilimiao.comm.apis.MemberProfileApi
import com.a10miaomiao.bilimiao.comm.entity.user.AccountMyInfoInfo
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.serialization.Serializable
import org.kodein.di.compose.rememberInstance
import java.io.File

/**
 * 「账号资料」（编辑资料）· 第一阶段：读资料 + 改昵称 + 改签名；第二阶段：**+ 改头像**。
 *
 * 依据：调研报告 `/root/test/用户资料编辑-调研与方案.md` §3（头像 7 步链路）、§4.2（页面结构）、
 *      §4.6（P0-1 web 登录态 / P0-2 写操作不重试）。
 * 入口：自己的个人空间页 → 右上角「更多」→「编辑资料」（UserSpacePage/UserSpaceViewModel）。
 *
 * ★ 头像这条路和昵称/签名**完全不是一条通道**，页面上的体现就是：
 *   · 昵称/签名：APP 签名 + access_key（有 APP 登录态就能用）；
 *   · 头像：web 接口 `/x/member/web/face/update`，只认 **Cookie(SESSDATA) + csrf(bili_jct)**。
 *   我们是 TV 扫码登录，很可能没有 web 登录态（报告 P0-1）→ 所以头像区会**提前**把这件事说出来，
 *   并给一个「去网页登录」的去处，而不是让用户选完图后吃一个 -101。
 *
 * ★ 还没做的（不在本轮范围）：性别 / 生日（同一套 APP 签名通道，接口已就绪）、
 *   头像挂件 / 哔哩哔哩认证（两个 H5 跳转）。
 *
 * ★ 为什么昵称和签名**各自一个保存按钮**，而不是一个"保存"提交全部：
 *   改昵称要**扣 6 硬币**（报告 §1.4、§5.3），把它和免费的签名改动捆在一个按钮里，
 *   用户想改签名就会莫名其妙被扣钱。分开 = 花钱的动作必须由用户单独、明确地触发。
 *   三个入口（昵称 / 签名 / 头像）共享同一把"单飞"锁（[EditProfileViewModel.submitting]），
 *   任一在提交时，其它两处都禁用。
 */
@Serializable
class EditProfilePage : ComposePage() {

    @Composable
    override fun Content() {
        // 不要覆盖 navDedupeKey：这页是"每次都要新开"的普通页（报告 §4.6 最后几行）
        val viewModel: EditProfileViewModel = diViewModel()
        EditProfilePageContent(viewModel)
    }
}

@Composable
private fun EditProfilePageContent(viewModel: EditProfileViewModel) {
    // 标题跟 PiliPlus 一致（view.dart:66-69 的 '账号资料'）
    PageConfig(title = "账号资料")

    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val fail by viewModel.fail.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val submitting by viewModel.submitting.collectAsStateWithLifecycle()
    val submittingField by viewModel.submittingField.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()

    // 委托属性拿不到 smart cast，所以先落到局部 val 再判空
    val failure = fail
    val currentProfile = profile

    // 资料还没到手（首次加载 或 加载失败）时只显示这一块：
    // 放在滚动容器外面，免得失败页被 scroll 撑成无限高度
    if (currentProfile == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(windowInsets.toPaddingValues()),
            contentAlignment = Alignment.Center,
        ) {
            if (failure != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BiliFailBox(e = failure)
                    TextButton(onClick = { viewModel.load() }) {
                        Text("重新加载")
                    }
                }
            } else {
                CircularProgressIndicator()
            }
        }
        return
    }

    EditProfileForm(
        viewModel = viewModel,
        profile = currentProfile,
        submitting = submitting,
        submittingField = submittingField,
        lastResult = lastResult,
        contentPadding = windowInsets.toPaddingValues(),
    )
}

@Composable
private fun EditProfileForm(
    viewModel: EditProfileViewModel,
    profile: AccountMyInfoInfo,
    submitting: Boolean,
    submittingField: String?,
    lastResult: ProfileSubmitResult?,
    contentPadding: PaddingValues,
) {
    // 输入框初值来自**异步**加载的资料，所以用 TextFieldValue 并把 selection 放到末尾：
    // String 重载在初值非空时会把光标停在开头（FavouriteEditForm.kt:47-52 记过这个坑）。
    var nameText by remember { mutableStateOf(TextFieldValue("")) }
    var signText by remember { mutableStateOf(TextFieldValue("")) }
    var prefilled by remember { mutableStateOf(false) }
    // 只在第一次拿到资料时灌初值。之后（保存成功 → profile 变了、或手动刷新）**不再覆盖**，
    // 否则用户正在输入的内容会被后台刷新冲掉。
    LaunchedEffect(profile.mid, prefilled) {
        if (!prefilled) {
            nameText = TextFieldValue(profile.name, TextRange(profile.name.length))
            signText = TextFieldValue(profile.sign, TextRange(profile.sign.length))
            prefilled = true
        }
    }

    // 系统相册（Photo Picker）：API 30+ 免权限，30 以下自动回退到系统文件选择器。
    // 与评论配图用的是同一个契约，只是换成**单选**（ReplyEditDialog.kt:502-506 用的是 PickMultipleVisualMedia）
    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        // 用户取消时 uri = null：什么都不做（不要弹"上传失败"吓人）
        if (uri != null) viewModel.uploadAvatar(uri)
    }

    val avatarPreview by viewModel.avatarPreview.collectAsStateWithLifecycle()
    val webLoginWarning by viewModel.webLoginWarning.collectAsStateWithLifecycle()

    val renameDisabledByCoins = profile.coins < EditProfileViewModel.RENAME_COIN_COST

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 当前资料（只读） ───────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "当前资料",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    // 改完名字/签名/头像后服务端不保证立刻生效（PiliPlus 改完头像是 500ms 后重拉 myinfo），
                    // 所以留一个手动刷新，让用户能自己核对服务端的真实值
                    TextButton(
                        onClick = { viewModel.load() },
                        enabled = !submitting,
                    ) {
                        Text("刷新")
                    }
                }
                InfoRow(label = "昵称", value = profile.name)
                InfoRow(label = "个性签名", value = profile.sign.ifBlank { "（未填写）" })
                InfoRow(label = "硬币", value = profile.coins.toInt().toString())
                InfoRow(label = "UID", value = profile.mid.toString())
            }
        }

        HorizontalDivider()

        // ── 头像（第二阶段：web 接口，multipart 直传文件） ───────────────────
        AvatarSection(
            face = profile.face,
            previewFile = avatarPreview,
            busy = submittingField == MemberProfileApi.FIELD_FACE,
            enabled = !submitting,
            warning = webLoginWarning,
            result = lastResult?.takeIf { it.field == MemberProfileApi.FIELD_FACE },
            onPick = {
                avatarPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onRetry = { viewModel.retryAvatarUpload() },
            onGoWebLogin = { viewModel.toWebLogin() },
        )

        HorizontalDivider()

        // ── 改昵称（付费：6 硬币） ─────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = nameText,
                onValueChange = { nameText = limitLength(it, NAME_MAX_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("昵称") },
                singleLine = true,
                // 提交期间不让改输入：正在飞的那次请求用的是提交瞬间的文本，
                // 让用户继续编辑只会造成"我明明改了啊"的错觉
                enabled = !submitting,
                supportingText = {
                    Text(
                        text = "${nameText.text.length}/$NAME_MAX_LENGTH" +
                                "　改名要花 ${EditProfileViewModel.RENAME_COIN_COST} 硬币" +
                                if (renameDisabledByCoins) {
                                    "（当前 ${profile.coins.toInt()}，硬币不足）"
                                } else {
                                    ""
                                }
                    )
                },
            )
            SubmitButton(
                text = "保存昵称",
                // 硬币不足时本地先禁掉（PiliPlus view.dart:164-165 也是本地拦），
                // 但服务端 message 仍是最终依据：ViewModel 里还有一道同样的检查兜底
                enabled = nameText.text.isNotBlank() && !renameDisabledByCoins,
                submitting = submitting,
                thisFieldSubmitting = submittingField == MemberProfileApi.FIELD_UNAME,
                onClick = { viewModel.submit(MemberProfileApi.FIELD_UNAME, nameText.text) },
            )
        }

        // ── 改个性签名（免费） ─────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = signText,
                onValueChange = { signText = limitLength(it, SIGN_MAX_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("个性签名") },
                // 跟 PiliPlus 一样给 4 行（view.dart:292-295）
                minLines = 4,
                maxLines = 4,
                enabled = !submitting,
                supportingText = { Text("${signText.text.length}/$SIGN_MAX_LENGTH") },
            )
            SubmitButton(
                text = "保存签名",
                enabled = true,
                submitting = submitting,
                thisFieldSubmitting = submittingField == MemberProfileApi.FIELD_SIGN,
                onClick = { viewModel.submit(MemberProfileApi.FIELD_SIGN, signText.text) },
            )
        }

        // ── 上次提交结果（失败一律显示服务端原文；头像自己一块，见上面的 AvatarSection） ──
        lastResult?.takeIf { it.field != MemberProfileApi.FIELD_FACE }?.let { result ->
            val fieldLabel = if (result.field == MemberProfileApi.FIELD_UNAME) "昵称" else "个性签名"
            Text(
                text = "$fieldLabel${if (result.success) "：" else "保存失败："}${result.message}",
                color = if (result.success) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            text = "说明：提交期间按钮会禁用；失败不会自动重试，请你手动再点一次" +
                    "（改名会扣硬币，自动重试可能重复扣费）。" +
                    "头像走的是 B 站网页接口，需要网页登录态（SESSDATA / bili_jct）——" +
                    "TV 扫码登录不一定会下发这两个 Cookie，缺失时上面会给出提示和「去网页登录」。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * 头像区：整块可点 → 选图 → 上传；上传中 / 失败原因 / 重试 / 缺登录态都在这一块里说清楚。
 *
 * 为什么失败原因要留在屏幕上（而不是只弹一次 toast）：
 *   toast 一两秒就没了，用户想看清"到底为什么失败"（尤其是服务端原文）需要一行常驻文字；
 *   这跟第一阶段"上次提交结果"是同一个理由。报告 §4.6 的底线是"失败只提示、不自动重试"，
 *   这里在它之上多了「重试上传」（`face/update` 幂等，手动重试没有额外代价，报告 §8.3）。
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun AvatarSection(
    face: String,
    previewFile: File?,
    busy: Boolean,
    enabled: Boolean,
    warning: String?,
    result: ProfileSubmitResult?,
    onPick: () -> Unit,
    onRetry: () -> Unit,
    onGoWebLogin: () -> Unit,
) {
    // 显示优先级：**本地预览图 > 服务端地址**。
    // 上传成功后服务端 URL 往往还没变（在审核），直接用 URL 会命中 Glide 缓存显示旧头像，
    // 用户就会以为"没改成" —— 本地那张是用户自己选的，永远不会说谎。
    val model: Any? = previewFile
        ?: face.takeIf { it.isNotBlank() }?.let { UrlUtil.autoHttps(it) + FACE_THUMB_SUFFIX }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, onClick = onPick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    GlideImage(
                        model = model,
                        contentDescription = "当前头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape),
                    )
                    if (busy) {
                        // 上传中：头像上盖一个转圈。PiliPlus 只有 500ms 防抖、没有任何进度反馈
                        // （view.dart:150-156），我们至少要让用户知道"点到了、正在传"
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "头像",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (busy) {
                            "上传中…"
                        } else {
                            "点击更换：JPG / PNG，不支持 GIF"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                if (!busy) {
                    Text(
                        text = "更换 ›",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // ① 最近一次头像上传的结果。失败原因就是服务端原文（或"缺网页登录态"的完整说明）
            if (result != null) {
                Text(
                    text = if (result.success) {
                        "头像修改成功（服务端头像可能要过审核，稍后点上面的「刷新」核对）"
                    } else {
                        "头像上传失败：${result.message}"
                    },
                    color = if (result.success) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!result.success) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onRetry, enabled = enabled) {
                            Text("重试上传")
                        }
                        if (result.needWebLogin) {
                            TextButton(onClick = onGoWebLogin, enabled = enabled) {
                                Text("去网页登录")
                            }
                        }
                    }
                }
            }

            // ② 进页面就体检出来的 web 登录态问题：提前说，别等选完图才失败。
            //    （失败结果里已经带过同一句话时不重复显示）
            if (warning != null && result?.needWebLogin != true) {
                Text(
                    text = warning,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onGoWebLogin, enabled = enabled) {
                    Text("去网页登录")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(76.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SubmitButton(
    text: String,
    enabled: Boolean,
    submitting: Boolean,
    thisFieldSubmitting: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        // 单飞是全局的：任何一个字段在提交，两个按钮都禁用（写操作不能并发）
        enabled = enabled && !submitting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (thisFieldSubmitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(if (thisFieldSubmitting) "提交中…" else text)
    }
}

/**
 * 超长直接截断 —— 等价 PiliPlus 的 `LengthLimitingTextInputFormatter(16 / 70)`（view.dart:307-311）。
 *
 * ★ 为什么要重建 TextFieldValue 而不是 `copy(text = 截断串)`：
 *   `copy` 会保留原来的 selection/composition，一旦它们超过截断后的长度，
 *   Compose 会直接抛 "selection must be within the text" —— 粘贴一段超长文本就是崩溃。
 *   截断时把光标放到末尾是安全的，也是用户粘贴后预期的位置。
 */
private fun limitLength(value: TextFieldValue, max: Int): TextFieldValue {
    val text = value.text
    if (text.length <= max) return value
    val cut = text.take(max)
    return TextFieldValue(cut, TextRange(cut.length))
}

/** 昵称上限 16 字（PiliPlus view.dart:309） */
private const val NAME_MAX_LENGTH = 16

/** 个性签名上限 70 字（PiliPlus view.dart:309） */
private const val SIGN_MAX_LENGTH = 70

/** B 站图床的缩略参数：头像只显示 56dp，没必要拉原图（和 ReplyItemBox/UserInfoCard 同款） */
private const val FACE_THUMB_SUFFIX = "@200w_200h"
