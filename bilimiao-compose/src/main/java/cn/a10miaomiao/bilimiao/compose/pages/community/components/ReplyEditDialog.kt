package cn.a10miaomiao.bilimiao.compose.pages.community.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.a10miaomiao.bilimiao.compose.components.dialogs.AnyPopDialog
import cn.a10miaomiao.bilimiao.compose.components.dialogs.AnyPopDialogProperties
import cn.a10miaomiao.bilimiao.compose.components.dialogs.AutoSheetDialog
import cn.a10miaomiao.bilimiao.compose.components.dialogs.DirectionState
import cn.a10miaomiao.bilimiao.compose.pages.community.ReplyEditParams
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.comm.UploadBfsInfo
import com.a10miaomiao.bilimiao.comm.entity.user.UserEmoteInfo
import com.a10miaomiao.bilimiao.comm.entity.user.UserEmotePackageInfo
import com.a10miaomiao.bilimiao.comm.entity.user.UserEmotePanelInfo
import com.a10miaomiao.bilimiao.comm.entity.video.VideoCommentReplyInfo
import com.a10miaomiao.bilimiao.comm.entity.video.VideoCommentSendResultInfo
import com.a10miaomiao.bilimiao.comm.entity.video.VideoReplyPictureInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.utils.MiaoLogger
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.a10miaomiao.bilimiao.comm.toast
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Stable
class ReplyEditDialogState(
    val scope: CoroutineScope,
    val onAddReply: (VideoCommentReplyInfo) -> Unit,
) {

    private var replyParams: ReplyEditParams? = null

    private val _visible = mutableStateOf(false)
    val visible: Boolean get() = _visible.value

    private val _loading = mutableStateOf(false)
    val loading: Boolean get() = _loading.value

    val focusRequester =  FocusRequester()

    var _input = mutableStateOf(TextFieldValue(""))
    val input: TextFieldValue get() = _input.value

    val textEmpty: Boolean by derivedStateOf {
        input.text.isEmpty()
    }

    val snackbar = SnackbarHostState()

    /** 待发图片（PiliPlus 的 imageList）：仅一级评论可用 */
    val imageList = ReplyImageList()

    /** 正在上传的图片数量，用于按钮转圈与禁用发布 */
    private val _uploadingCount = mutableStateOf(0)
    val uploadingCount: Int get() = _uploadingCount.value

    /** 最多 9 张，与 PiliPlus 的评论上限一致 */
    val imageLimit = 9

    private val context: android.content.Context
        get() = BilimiaoCommApp.commApp.app

    /** 二级评论（回复某条评论）不能带图，服务端也不支持 */
    val canUploadImage: Boolean get() = replyParams?.root == null

    fun show(params: ReplyEditParams) {
        replyParams = params
        _visible.value = true
    }

    /** 选图回调：逐张落盘 + 上传，出一张显示一张，失败只标记那张 */
    fun addImages(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val room = imageLimit - imageList.items.size
        if (room <= 0) {
            scope.launch(Dispatchers.Main) {
                snackbar.showSnackbar("最多只能发 $imageLimit 张图片")
            }
            return
        }
        // prepare 是 suspend（要解码/压缩），逐张串行处理，避免同时解码多张大图爆内存
        scope.launch(Dispatchers.IO) {
            uris.take(room).forEach { uri ->
                val item = runCatching {
                    ReplyImageItem(ReplyImageHelper.prepare(context, uri))
                }.getOrElse { e ->
                    withContext(Dispatchers.Main) {
                        snackbar.showSnackbar("图片读取失败：${e.message ?: "未知错误"}")
                    }
                    return@forEach
                }
                withContext(Dispatchers.Main) { imageList.add(item) }
                uploadImage(item)
            }
        }
    }


    /** 上传单张图片；失败会把原因记到 item 上，缩略图变成"点击重试" */
    private fun uploadImage(item: ReplyImageItem) {
        // 所有 UI 可见状态只在主线程改（缩略图在对话框里读这些字段）
        scope.launch(Dispatchers.Main) {
            item.uploading = true
            item.error = null
            _uploadingCount.value += 1
            try {
                var outcome: String? = null
                // 网络抖动自动重试：最多 3 次（0.8s / 2s 退避），避免缩略图长时间转圈
                for (attempt in 0 until 3) {
                    outcome = withContext(Dispatchers.IO) { doUpload(item) }
                    if (outcome == null) break
                    if (attempt < 2) delay(if (attempt == 0) 800L else 2000L)
                }
                item.error = outcome
            } catch (e: Exception) {
                e.printStackTrace()
                item.error = e.message ?: e.toString()
            } finally {
                item.uploading = false
                _uploadingCount.value -= 1
            }
        }
    }

    /**
     * 真正发上传请求：先按"有没有 web 登录态"选通道，失败自动换另一条通道重试一次。
     * 成功返回 null，失败返回给用户看的原因（含 HTTP 状态码与服务端原文）。
     */
    private suspend fun doUpload(item: ReplyImageItem): String? {
        // 上传接口是 web 接口，服务端只认 Cookie 登录态。App 若是扫码登录、
        // CookieManager 里没有 SESSDATA，这里先尝试从已持久化的 cookie 仓 + WebView 回灌一次。
        var hasWeb = runCatching { BiliApiService.commentApi.hasWebLoginCookie() }.getOrDefault(false)
        if (!hasWeb) {
            runCatching {
                val store = com.a10miaomiao.bilimiao.comm.network.CookieStore
                    .getInstance(BilimiaoCommApp.commApp.app)
                store.importFromWebView()
                store.syncToWebView()
                hasWeb = BiliApiService.commentApi.hasWebLoginCookie()
            }
        }
        val channels = if (hasWeb) listOf(true, false) else listOf(false, true)
        // 诊断（release 版 INFO 会被吞，必须用 error 级才进 logcat）
        val token = BilimiaoCommApp.commApp.loginInfo?.token_info?.access_token
        var lastErr: String? = null
        for ((index, preferWeb) in channels.withIndex()) {
            val tag = if (preferWeb) "WEB通道" else "APP通道"
            val err = attemptUpload(item, preferWeb)
            if (err == null) {
                if (index > 0) {
                }
                return null
            }
            lastErr = err
            // 换通道重试前把上一轮的结果清掉
            item.uploadedUrl = null
        }
        return lastErr
    }

    /** 单次上传尝试；成功返回 null 并写好 item 的图片信息，失败返回原因 */
    private suspend fun attemptUpload(item: ReplyImageItem, preferWeb: Boolean): String? {
        return try {
            val call = BiliApiService.commentApi.uploadImage(
                file = item.file,
                biz = "reply",
                preferWeb = preferWeb,
            )
            val response = call.awaitCall()
            val bodyStr = response.body?.string().orEmpty()
            val parsed = runCatching {
                MiaoJson.fromJson<ResponseData<UploadBfsInfo>>(bodyStr)
            }.getOrElse { e: Throwable ->
                return "返回无法解析（HTTP ${response.code}）：${bodyStr.take(200).ifBlank { e.message ?: "" }}"
            }
            if (!parsed.isSuccess) {
                return "code=${parsed.code} ${parsed.message}（HTTP ${response.code}）"
            }
            val data = parsed.data
                ?: return "没有返回图片信息（HTTP ${response.code}）：${bodyStr.take(200)}"
            val url = data.src
            if (url.isBlank()) {
                return "图片地址为空（HTTP ${response.code}）：${bodyStr.take(200)}"
            }
            item.uploadedUrl = url
            item.uploadedSizeKb = data.sizeKb
            item.uploadedWidth = data.width
            item.uploadedHeight = data.height
            null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
        }
    }

    /** 缩略图上的"重试" */
    fun retryImage(item: ReplyImageItem) {
        if (item.uploading) return
        item.error = null
        item.uploadedUrl = null
        uploadImage(item)
    }

    /**
     * 把所有待发图片整理成 pictures 参数。
     * 只要还有图没传成功就抛错（错误文案区分"还在传"与"传失败"），避免发出没有图的评论。
     */
    private suspend fun collectPictures(): List<VideoReplyPictureInfo>? {
        val items = imageList.items
        if (items.isEmpty()) return null
        // 还在上传中的先等一下，别急着报错
        var waitMs = 0
        while (items.any { it.uploading } && waitMs < 30_000) {
            delay(300L)
            waitMs += 300
        }
        val failed = items.firstOrNull { !it.uploaded }
        if (failed != null) {
            throw IllegalStateException(
                if (failed.uploading) "图片还在上传中，请稍候再发送"
                else "图片上传失败：${failed.error ?: "未知原因"}（点缩略图可重试）"
            )
        }
        return items.mapNotNull { item ->
            item.uploadedUrl?.let {
                VideoReplyPictureInfo(
                    img_src = it,
                    img_width = item.uploadedWidth,
                    img_height = item.uploadedHeight,
                    img_size = item.uploadedSizeKb,
                )
            }
        }
    }

    /**
     * 用 web 通道发带图评论，返回 (code, 解析结果)。
     * 会把实际请求的 URL 与表单逐字节写进诊断文件，便于离线复放比对。
     */
    private suspend fun sendWebComment(
        message: String,
        params: ReplyEditParams,
        pictures: List<VideoReplyPictureInfo>,
        minimalForm: Boolean,
    ): Pair<Int, ResponseData<VideoCommentSendResultInfo>> {
        val req = BiliApiService.commentApi.addWithPictures(
            message = message,
            type = params.type,
            oid = params.oid,
            pictures = pictures,
            minimalForm = minimalForm,
        )
        runCatching {
            val bodyStr = req.formBody?.let {
                com.a10miaomiao.bilimiao.comm.network.ApiHelper.urlencode(it)
            } ?: ""
        }
        val res = req.awaitCall()
            .json<ResponseData<VideoCommentSendResultInfo>>(isLog = true)
        return res.code to res
    }

    fun removeImage(item: ReplyImageItem) {
        imageList.remove(item)
    }

    fun dismiss() {
        replyParams = null
        _visible.value = false
        // 取消后要把输入清掉：否则下次（哪怕改成回复别人）还带着上次的残留文字
        _input.value = TextFieldValue("")
        // 附带的图片也要清掉，临时文件不留在缓存里
        imageList.clear()
    }

    fun inputChange(value: TextFieldValue) {
        _input.value = value

    }

    fun requestFocus() {
        focusRequester.requestFocus()
    }

    fun freeFocus() {
        focusRequester.freeFocus()
    }

    fun inputEmoji(emoji: UserEmoteInfo) {
        val originalText = input.text
        val position = input.selection.min + emoji.text.length
        _input.value = input.copy(
            text = originalText.substring(0, input.selection.min)
                    + emoji.text
                    + originalText.substring(input.selection.max),
            selection = TextRange(position)
        )
    }

    private suspend fun _sendReply() {
        try {
            if (BilimiaoCommApp.commApp.loginInfo == null) {
                toast("请先登录")
                return
            }
            val message = input.text
            val params = replyParams
            if (params == null) {
                snackbar.showSnackbar("参数错误")
                return
            }
            // 只有纯图片、没有文字也可以发（B 站支持），但两者都空不行
            if (message.isBlank() && imageList.items.isEmpty()) {
                // 空内容不该发出去：楼中楼回复会拼上"回复 @某人 :"前缀，
                // 空输入也会变成一条垃圾评论
                snackbar.showSnackbar("请输入评论内容")
                return
            }
            val pics = imageList.items
            // 二级评论（楼中楼）服务端不接受 pictures：说清楚是"改回复为独立评论"还是根本发不了
            if (params.root != null && imageList.items.isNotEmpty()) {
                snackbar.showSnackbar(
                    "你正在回复某条评论（楼中楼），B 站不支持这种评论带图。" +
                        "想带图请返回评论区用底部输入框直接发表评论。"
                )
                return
            }
            withContext(Dispatchers.Main) {
                _loading.value = true
            }
            // 先把图片都传完再发评论：pictures 里必须已经是可访问的 URL
            val pictures = collectPictures()
            val finalMessage = if (params.parent != null && params.parent != params.root) {
                "回复 @${params.name} :$message"
            } else {
                message
            }
            // ★ 带图评论必须走纯 WEB 通道：APP 签名通道会被服务端拒（12088 不支持发送图片）
            val res = if (!pictures.isNullOrEmpty()) {
                // 表单先按 PiliPlus 的最小字段集发；被 12088 拒就自动换"带 plat/csrf_token/build"的表单再试
                var attempt = sendWebComment(finalMessage, params, pictures, minimalForm = true)
                if (attempt.first == 12088) {
                    attempt = sendWebComment(finalMessage, params, pictures, minimalForm = false)
                }
                attempt.second
            } else {
                BiliApiService.commentApi
                    .add(
                        message = finalMessage,
                        type = params.type,
                        oid = params.oid,
                        root = params.root,
                        parent = params.parent,
                    )
                    .awaitCall()
                    .json<ResponseData<VideoCommentSendResultInfo>>(isLog = true)
            }
            withContext(Dispatchers.Main) {
                if (res.isSuccess) {
                    val result = res.requireData()
                    _visible.value = false
                    toast(result.success_toast)
                    _input.value = TextFieldValue("")
                    imageList.clear()
                    delay(1000L)
                    onAddReply(result.reply)
                } else {
                    snackbar.showSnackbar(res.message)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            snackbar.showSnackbar(e.message ?: e.toString())
        } finally {
            withContext(Dispatchers.Main) {
                _loading.value = false
            }
        }
    }
    fun sendReply() {
        if (loading) return
        scope.launch(Dispatchers.IO) {
            _sendReply()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReplyEditDialog(
    state: ReplyEditDialogState
) {
    val showEmojiGrid = remember { mutableStateOf(false) }
    // 系统相册选择器（Photo Picker）：API 30+ 免权限；30 以下自动回退到系统文件选择器
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(state.imageLimit)
    ) { uris ->
        state.addImages(uris)
    }
    if (state.visible) {
        AutoSheetDialog(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp),
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    ReplyTextField(
                        state = state,
                    )
                    ReplyImagePreview(
                        state = state,
                    )
                    SnackbarHost(hostState = state.snackbar)
                    ReplyTextToolbar(
                        modifier = Modifier.padding(top = 5.dp),
                        visibleEmoji = showEmojiGrid.value,
                        loading = state.loading,
                        uploadingCount = state.uploadingCount,
                        canUploadImage = state.canUploadImage,
                        onEmojiClick = {
                            showEmojiGrid.value = !showEmojiGrid.value
                        },
                        onImageClick = {
                            // 选图时先收起表情面板，避免两个面板叠在一起
                            showEmojiGrid.value = false
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onSendClick = state::sendReply
                    )
                    AnimatedVisibility(
                        visible = showEmojiGrid.value
                    ) {
                        EmojiGridBox(
                            modifier = Modifier.padding(top = 5.dp)
                                .height(emotePanelHeight),
                            onInputEmoji = {
                                state.inputEmoji(it)
                            }
                        )
                    }
                }
                val imeVisible = WindowInsets.Companion.isImeVisible
                val keyboardController = LocalSoftwareKeyboardController.current
                LaunchedEffect(imeVisible) {
                    if (showEmojiGrid.value && imeVisible) {
                        showEmojiGrid.value = false
                    }
                }
                LaunchedEffect(showEmojiGrid.value) {
                    if (showEmojiGrid.value && imeVisible) {
                        keyboardController?.hide()
                    } else if (!showEmojiGrid.value && !imeVisible) {
                        state.requestFocus()
                        keyboardController?.show()
                    }
                }
            },
            onDismiss = state::dismiss,
            onPreDismiss = {
                if (showEmojiGrid.value) {
                    showEmojiGrid.value = false
                    true
                } else {
                    false
                }
            }
        )
    }
}

private val circleButtonSize = 44.dp
private val minInputHeight = 90.dp
private val emotePanelHeight = 300.dp

@Composable
private fun ReplyTextField(
    modifier: Modifier = Modifier,
    state: ReplyEditDialogState,
) {
    LaunchedEffect(state.visible) {
        if (state.visible) {
            state.requestFocus()
        }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
    ) {
        BasicTextField(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minInputHeight, max = 180.dp)
                .padding(8.dp)
                .focusRequester(state.focusRequester),
            textStyle = TextStyle(
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            value = state.input,
            onValueChange = state::inputChange,
            cursorBrush = SolidColor(Color(0xff00897B)),
            decorationBox = { innerTextField ->
                if (state.textEmpty) {
                    Text("请发表你的评论", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                innerTextField()
            },
            keyboardActions = KeyboardActions(
                onDone = {
                    state.freeFocus()
                }
            ),
        )
    }
}


@Composable
private fun ReplyTextToolbar(
    modifier: Modifier = Modifier,
    visibleEmoji: Boolean,
    loading: Boolean,
    uploadingCount: Int,
    canUploadImage: Boolean,
    onEmojiClick: () -> Unit,
    onImageClick: () -> Unit,
    onSendClick: () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom
    ) {
        TextButton(
            onClick = onEmojiClick,
            modifier = Modifier.then(Modifier.size(circleButtonSize)),
        ) {
            Icon(
                imageVector = Icons.Default.Mood,
                contentDescription = "emoji表情",
                tint = if (visibleEmoji) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onBackground
                }
            )
        }
        // 评论配图：只有一级评论能带图（楼中楼服务端不支持）
        if (canUploadImage) {
            TextButton(
                onClick = onImageClick,
                modifier = Modifier.then(Modifier.size(circleButtonSize)),
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = "添加图片",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
        if (uploadingCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "上传中 $uploadingCount",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = onSendClick
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        "发布",
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null
                    )
                }
            }
        }
    }
}


/**
 * 待发图片的横向预览条：每张显示缩略图、上传中转圈、右上角删除按钮。
 * 选图后立即上传，所以这里看到的是"已上传/上传中"的真实状态。
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ReplyImagePreview(
    state: ReplyEditDialogState,
) {
    val items = state.imageList.items
    if (items.isEmpty()) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items) { item ->
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                GlideImage(
                    model = item.file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                when {
                    item.uploading -> {
                        // 上传中：压一层半透明遮罩 + 转圈
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        }
                    }
                    item.failed -> {
                        // 上传失败：红罩 + "重试"，点一下重传（不再无限转圈）
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xCCB00020))
                                .clickable { state.retryImage(item) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "重试上传",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = "重试",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }
                }
                IconButton(
                    onClick = { state.removeImage(item) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "移除图片",
                        tint = Color.White,
                        modifier = Modifier
                            .size(14.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                            .padding(1.dp),
                    )
                }
            }
        }
    }
}
