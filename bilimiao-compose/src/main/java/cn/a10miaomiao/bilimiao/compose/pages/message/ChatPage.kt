package cn.a10miaomiao.bilimiao.compose.pages.message

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.entity.FlowPaginationInfo
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.localPageNavigation
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageListener
import cn.a10miaomiao.bilimiao.compose.common.mypage.rememberMyMenu
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.common.toPaddingValues
import cn.a10miaomiao.bilimiao.compose.components.input.MiaoInputField
import cn.a10miaomiao.bilimiao.compose.components.input.MiaoInputToolButton
import cn.a10miaomiao.bilimiao.compose.components.input.MiaoSendButton
import cn.a10miaomiao.bilimiao.compose.components.list.ListStateBox
import cn.a10miaomiao.bilimiao.compose.components.image.ImagesGrid
import cn.a10miaomiao.bilimiao.compose.components.image.provider.PreviewImageModel
import cn.a10miaomiao.bilimiao.compose.pages.community.components.EmojiGridBox
import cn.a10miaomiao.bilimiao.compose.pages.community.components.ReplyImageHelper
import cn.a10miaomiao.bilimiao.compose.components.dialogs.AutoSheetDialog
import cn.a10miaomiao.bilimiao.compose.pages.message.content.MessageRefreshEvent
import cn.a10miaomiao.bilimiao.compose.pages.message.content.UserInfoCache
import cn.a10miaomiao.bilimiao.compose.pages.message.content.AccInfoData
import cn.a10miaomiao.bilimiao.compose.pages.message.content.mergeIntoUserInfoCache
import cn.a10miaomiao.bilimiao.compose.pages.message.content.messageAvatarUrl
import cn.a10miaomiao.bilimiao.compose.pages.user.UserSpacePage
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.comm.UploadBfsInfo
import com.a10miaomiao.bilimiao.comm.entity.message.ChatMsgInfo
import com.a10miaomiao.bilimiao.comm.entity.message.ChatMsgResponse
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.store.MessageStore
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class ChatPage(
    val talkerId: Long,
    val talkerName: String = "",
    val talkerFace: String = "",
) : ComposePage() {

    @OptIn(ExperimentalGlideComposeApi::class)
    @Composable
    override fun Content() {
        val userStore: UserStore by rememberInstance()
        if (userStore.isLogin()) {
            ChatPageContent(talkerId, talkerName, talkerFace)
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("请先登录", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ─── JSON解析 ────────────────────────────────────────────────

private fun debugJson(raw: String): String {
    if (raw.length > 200) return raw.take(200) + "..."
    return raw
}

private val parseJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/**
 * 纯 WEB 形态的私信发送地址：**一个 APP 参数都不带**（appkey / mobi_app / statistics / access_key / sign）。
 *
 * 为什么图片消息不能走 `BiliApiService.biliVcApi(...)`：那个构造器内部是 `ApiHelper.createParams`，
 * 会把上面整套 APP 参数注进 query（还会把 mobi_app 覆盖成 android_hd），`MiaoHttp` 也会因为
 * `isWebApi=false` 再补 `app-key: android_hd` + `x-bili-mid` + `Authorization` 头。
 * 本工程在"带图评论"上**真机踩过同一个坑**：走 APP 签名通道服务端直接回 `12088 不支持发送图片`，
 * 同一张图纯 web（仅 Cookie + csrf）就成功 —— 见 CommentApi.addWithPictures 的 KDoc 与
 * ReplyEditDialog.sendReply 里那句"带图评论必须走纯 WEB 通道"。
 * 私信图片（msg_type=2）是同一类"带图写接口"，所以保守按纯 web 发；文字消息（msg_type=1）
 * 那条链是现成可用的，继续走 biliVcApi，一个字不动。
 *
 * 注：这个 host 不在 [com.a10miaomiao.bilimiao.comm.utils.WbiSigner.autoScopeFor] 的直播白名单里，
 * 所以两条链都不会被 WBI 签名（wts/w_rid）影响，差异只有"APP 参数 + APP 头"这一项。
 */
private const val IM_SEND_MSG_WEB_URL = "https://api.vc.bilibili.com/web_im/v1/web_im/send_msg"

/**
 * 没有 web 登录态时给用户看的话术。
 * 要点：说清"缺什么"（SESSDATA / 网页登录态）+ "已经替你试过自动恢复" + "下一步怎么做"
 * （内置网页登录页 H5LoginPage，改头像那条链用的也是同一句话术，见 ProfileAvatarUploader）。
 */
private const val MSG_NEED_WEB_LOGIN =
    "发图需要网页登录态（SESSDATA），当前账号只有 APP 登录态；已尝试自动恢复仍未拿到，" +
        "请用「网页登录」登录一次再发图。"

data class ParsedMsg(val text: String, val toastText: String = "")

private fun parseMsgText(raw: String): ParsedMsg {
    if (raw.isBlank()) return ParsedMsg("")
    // 数组格式 → 提取text用于toast，消息本身隐藏
    if (raw.startsWith("[")) {
        return try {
            @Serializable data class Hint(val text: String = "")
            val list = parseJson
                .decodeFromString<List<Hint>>(raw)
            val tipText = list.firstOrNull()?.text ?: ""
            ParsedMsg("", toastText = tipText)
        } catch (_: Exception) { ParsedMsg("") }
    }
    // 对象格式
    return try {
        @Serializable data class C(val content: String = "")
        val c = parseJson
            .decodeFromString<C>(raw)
        if (c.content.isNotBlank()) {
            // 嵌套数组 → 系统提示，隐藏气泡
            if (c.content.startsWith("[")) return ParsedMsg("")
            return ParsedMsg(c.content)
        }
        @Serializable data class Sys(val title: String = "", val text: String = "")
        val s = parseJson
            .decodeFromString<Sys>(raw)
        ParsedMsg(s.text.ifBlank { s.title.ifBlank { "[系统通知]" } })
    } catch (_: Exception) { ParsedMsg("[消息] " + raw.take(30)) }
}

/** 图片私信（msg_type=2）的 content 解析结果 */
private data class ChatPicture(val url: String, val width: Int, val height: Int)

/**
 * 图片私信的 content 是 `{"url":"…","width":300,"height":300,"imageType":"jpeg","original":1,"size":54.1}`，
 * 跟文字私信的 `{"content":"…"}` 不是一个结构。
 *
 * 宽高按 Double 解再取整：服务端/js 端发出来的可能是小数，用 Int 解会直接抛异常、
 * 让整条消息退化成"[系统通知]"文字气泡。解析不出来就返回 null，让调用方走文字那条老路
 * （**不猜**：宁可显示原文也不要显示一个错误的图片框）。
 */
private fun parsePicContent(msg: ChatMsgInfo): ChatPicture? {
    // 2 = EN_MSG_TYPE_PIC（图片）、6 = EN_MSG_TYPE_CUSTOM_FACE（自定义表情，结构与图片相同）
    if (msg.msg_type != 2 && msg.msg_type != 6) return null
    return try {
        @Serializable data class Pic(
            val url: String = "",
            val width: Double = 0.0,
            val height: Double = 0.0,
        )
        val pic = parseJson.decodeFromString<Pic>(msg.content)
        if (pic.url.isBlank()) null else ChatPicture(pic.url, pic.width.toInt(), pic.height.toInt())
    } catch (_: Exception) { null }
}

/**
 * 私信图片 → 全局图片预览组件（[ImagesGrid] / [PreviewImageModel]）要的模型。
 *
 * previewUrl **不加** `@宽w_高h` 缩略图后缀：私信图存在 message.biliimg.com，
 * 评论图那套后缀（写在 i0.hdslb.com 上）在这个域上没验证过，给错后缀就是图片直接不显示；
 * 而 [ImagesGrid] 内部本来就 `override(600)` 限制了解码尺寸，不加后缀也不会拖内存。
 * 宽高缺失时按正方形兜底，避免除零、也避免 TransformItemView 拿到 0 尺寸。
 */
private fun ChatPicture.toPreviewModel(): PreviewImageModel {
    val original = UrlUtil.autoHttps(url)
    val w = if (width > 0) width else 600
    return PreviewImageModel(
        previewUrl = original,
        originalUrl = original,
        width = w.toFloat(),
        height = (if (height > 0) height else w).toFloat(),
    )
}

/** 图床上传结果：成功带 info，失败带给用户看的原因（口径对齐评论区 ReplyEditDialog.attemptUpload） */
private data class ImageUploadResult(val info: UploadBfsInfo?, val error: String?)

// ─── ViewModel ───────────────────────────────────────────────

private class ChatViewModel(
    override val di: DI,
    private val talkerId: Long,
) : ViewModel(), DIAware {

    private val userStore: UserStore by instance()
    private val pageNavigation: PageNavigation by instance()
    private val messageStore: MessageStore by instance()
    val isRefreshing = mutableStateOf(false)
    val isSending = mutableStateOf(false)

    /** 选完图正在压缩/上传（图片按钮转圈并拒绝重复点击） */
    val isUploadingImage = mutableStateOf(false)
    val list = FlowPaginationInfo<ChatMsgInfo>()
    // 用 TextFieldValue 而不是 String：String 拿不到光标位置，插表情只能永远追加到末尾
    val inputText = mutableStateOf(TextFieldValue(""))
    val showSendDialog = mutableStateOf(false)

    val myUid: Long get() = userStore.state.info?.mid ?: 0L
    val myFace: String get() = userStore.state.info?.face ?: ""

    val talkerName = mutableStateOf("")
    val talkerFace = mutableStateOf("")

    init {
        loadMsgs()
    }

    fun setTalkerInfo(name: String, face: String) {
        if (name.isNotBlank()) talkerName.value = name
        if (face.isNotBlank()) talkerFace.value = face
    }

    fun loadUserInfo(uid: Long) {
        if (uid <= 0L) return
        // 先查全局缓存
        val cached = UserInfoCache.get(uid)
        if (cached != null && cached.face.isNotBlank()) {
            talkerName.value = cached.name
            talkerFace.value = cached.face
            return
        }
        if (talkerName.value.isNotBlank() && talkerFace.value.isNotBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                @Serializable data class AccInfo(val mid: Long = 0, val name: String = "", val face: String = "")
                val res = BiliApiService.userApi
                    .accInfo(uid.toString())
                    .awaitCall()
                    .json<ResultInfo<AccInfo>>()
                if (res.isSuccess) {
                    val info = res.data
                    if (info != null) {
                        // ★只覆盖非空值：acc/info 偶尔会回空 name/face，直接赋值会把列表刚传进来的
                        //   好头像 / 好昵称**抹掉**（用户看到的"外面有头像、点进对话反而没有"就是这么来的）
                        if (info.name.isNotBlank()) talkerName.value = info.name
                        if (info.face.isNotBlank()) talkerFace.value = info.face
                        // 写全局缓存：空值不覆盖已有值（会话列表直接读这个缓存，被写成空 face 就会退回占位图）
                        val old = UserInfoCache.get(uid)
                        mergeIntoUserInfoCache(uid, AccInfoData(mid = info.mid, name = info.name, face = info.face))
                        // 拿到的是**新**头像：通知会话列表刷新一次，外面那块立刻跟着更新
                        // （列表监听的就是这个 MessageRefreshEvent，见 PrivateMessageContent）
                        if (info.face.isNotBlank() && old?.face != info.face) {
                            MessageRefreshEvent.trigger()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun loadMsgs(beginSeqno: Long = 0) {
        viewModelScope.launch(Dispatchers.IO) { loadMsgsInternal(beginSeqno) }
    }

    /** 内部 suspend 版，供 sendMsg 串行调用避免竞态 */
    private suspend fun loadMsgsInternal(beginSeqno: Long = 0) {
        try {
            isRefreshing.value = true
            val res = BiliApiService.messageApi
                .fetchMsgs(talkerId = talkerId, beginSeqno = beginSeqno)
                .awaitCall()
                .json<ResultInfo<ChatMsgResponse>>()
            if (res.isSuccess) {
                val msgs = res.data?.messages ?: emptyList()
                val existing = list.data.value.toMutableList()
                msgs.forEach { msg ->
                    if (existing.none { it.msg_key == msg.msg_key }) existing.add(msg)
                }
                existing.sortBy { it.timestamp }
                list.data.value = existing
                // 读完就把私信未读清掉（否则首页角标永远有红点）
                try { messageStore.clearChatUnread() } catch (e: Exception) { }
                list.finished.value = res.data?.has_more != 1
            } else if (list.data.value.isEmpty()) {
                list.fail.value = res.message.ifBlank { "加载消息失败" }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (list.data.value.isEmpty()) if (e !is java.net.UnknownHostException) {
                list.fail.value = e.message ?: e.toString()
            }
        } finally {
            isRefreshing.value = false
        }
    }

    fun refresh() {
        list.fail.value = ""
        list.reset()
        loadMsgs()
    }

    fun loadMore() {
        if (list.finished.value || isRefreshing.value) return
        // seqno<=0 的是本地乐观消息，不能拿来当分页游标（否则会请求 beginSeqno=-1）。
        // 注意是"排除掉本地消息后再取最小值"，不是拿全局最小值去判断 ——
        // 后者只要列表里存在一条乐观消息（msg_seqno=0）就会整体 return，
        // 表现为发消息期间上滑永远拉不到更早的记录
        val minSeqno = list.data.value
            .filter { it.msg_seqno > 0L }
            .minOfOrNull { it.msg_seqno } ?: return
        if (minSeqno <= 1L) {
            // 已经到最早一条了：标记到底，底部才会显示"下面没有了"而不是一直转
            list.finished.value = true
            return
        }
        loadMsgs(beginSeqno = minSeqno - 1)
    }

    fun sendMsg() = viewModelScope.launch(Dispatchers.IO) {
        val text = inputText.value.text.trim()
        if (text.isEmpty()) return@launch
        // 别手拼 JSON：多行文本（输入框允许换行）会拼出非法 JSON，服务端直接拒收
        val contentJson = kotlinx.serialization.json.buildJsonObject {
            put("content", kotlinx.serialization.json.JsonPrimitive(text))
        }.toString()
        sendMsgInternal(msgType = 1, contentJson = contentJson, clearText = true)
    }

    /**
     * 私信发图（R11）：链路跟评论区发图是同一套，只有"业务标识"和"消息体"是私信自己的。
     *   ① 选图：系统 Photo Picker（与评论区 ReplyEditDialog 同一个 contract，免存储权限）
     *   ② [ReplyImageHelper.prepare]：复用评论区的压缩 / EXIF 摆正 / HEIC 转码 / 落盘
     *   ③ [BiliApiService.commentApi.uploadImage]（biz = "im"）：复用评论区那个 multipart 上传，
     *      它早就把 biz 参数化好了，就是为私信留的口子
     *   ④ send_msg（msg_type = 2）+ 图床 JSON 消息体，交回 [sendMsgInternal] 走与文字私信完全相同的
     *      乐观插入 / 回读 / 失败回滚 / 发完刷新列表那条路
     * 图片消息体字段（url/width/height/imageType/original/size）以 B 站接口文档为准，缺 url 会被拒 21037。
     */
    fun sendImage(uri: Uri) {
        // ★ 先在主线程把标志置起来、再起协程：光靠按钮 enabled 的禁用要等重组才生效，
        //   连点两下会重复上传 + 重复发一条图（同一个 uri 发出去两张）
        if (isUploadingImage.value) return
        isUploadingImage.value = true
        viewModelScope.launch(Dispatchers.IO) {
            // 压缩落盘后的临时文件：单独留个引用给 finally 删（try 里的 val 出了块就看不见了）
            var tempFile: File? = null
            try {
                // ★纯 WEB 发图前先体检 + 补救 web 登录态；补不回来就**一个请求都不发**
                //   （注定 -101 的请求只会白费一次压缩+上传，还把失败原因说得含糊）
                if (!ensureWebLogin()) {
                    withContext(Dispatchers.Main) { toast(MSG_NEED_WEB_LOGIN) }
                    return@launch
                }
                val file = ReplyImageHelper.prepare(BilimiaoCommApp.commApp.app, uri)
                tempFile = file
                val (info, error) = uploadImageForIm(file)
                if (info == null) {
                    // 提示统一走 withContext（不再用裸 launch）：task-14 把发送逻辑抽成 suspend 函数后，
                    // sendMsgInternal 里的裸 launch 失去了 CoroutineScope 接收者 → 落到 kotlinx 已废弃的
                    // **顶层** launch，真编译直接 DEPRECATION_ERROR（vc186 拦下的就是这条）。
                    // 这一处虽然还在 viewModelScope.launch 的协程体里（接收者还在），也一并统一，
                    // 免得以后挪动代码再踩同一个坑。语义 = 等这句 toast 落地再往下走，顺序只会更严格。
                    withContext(Dispatchers.Main) { toast("图片上传失败：${error ?: "未知原因"}") }
                    return@launch
                }
                // ★尺寸宁可取本地真实像素也不许发 0：TV 扫码登录时上传先走 APP 通道，
                //   那条通道可能只回 location/url（UploadBfsInfo 的兼容字段注释就是这么写的），
                //   于是接口给的 width/height 是 0 —— 0 进消息体会让对方端按 h/w 算出 NaN/坏图。
                //   文件是我们自己刚落盘的，只读个头（inJustDecodeBounds，不解码整图）就有尺寸。
                val (localWidth, localHeight) = readLocalImageSize(file) ?: (0 to 0)
                val width = info.width.takeIf { it > 0 } ?: localWidth
                val height = info.height.takeIf { it > 0 } ?: localHeight
                // size 单位千字节；服务端不回（<=0）就按本地文件长度换算 —— 估一个真值也比 0 强
                val sizeKb = info.img_size.takeIf { it > 0 } ?: (file.length() / 1024.0)
                val contentJson = kotlinx.serialization.json.buildJsonObject {
                    put("url", kotlinx.serialization.json.JsonPrimitive(info.src))
                    // 尺寸/大小在 B 站文档里都是"非必要"：实在拿不到就**整个字段不发**（缺字段服务端接受），
                    // 绝不发 0 —— 发 0 恰恰是会让对面渲染坏的那种
                    if (width > 0 && height > 0) {
                        put("width", kotlinx.serialization.json.JsonPrimitive(width))
                        put("height", kotlinx.serialization.json.JsonPrimitive(height))
                    }
                    put("imageType", kotlinx.serialization.json.JsonPrimitive(imageTypeName(file)))
                    // 1 = 对方在 APP 里能看到"下载原图"按钮
                    put("original", kotlinx.serialization.json.JsonPrimitive(1))
                    if (sizeKb > 0) {
                        put("size", kotlinx.serialization.json.JsonPrimitive(sizeKb))
                    }
                }.toString()
                // 发图**不**清空输入框：用户可能一边打字一边插图，图发出去后那几个字还得留着
                sendMsgInternal(msgType = 2, contentJson = contentJson, clearText = false)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                withContext(Dispatchers.Main) { toast("图片发送失败：${e.message ?: "未知错误"}") }
            } finally {
                // 临时文件用完即删（评论区也是这个口径：不在缓存里留垃圾）
                tempFile?.let { f -> runCatching { f.delete() } }
                // ★UI 可见状态只在主线程改（工程约定：ReplyEditDialog.kt 的"所有 UI 可见状态只在主线程改"）
                withContext(Dispatchers.Main) { isUploadingImage.value = false }
            }
        }
    }

    /**
     * 发图前的"web 登录态体检 + 补救"。返回 true = 现在可以发纯 WEB 请求。
     *
     * 为什么必须有：图片消息走的是**纯 WEB** 表单（见 [IM_SEND_MSG_WEB_URL]），
     * `isWebApi = true` 已经把 Authorization / access_key 那条路关掉了，唯一的凭据就是 Cookie 里的 SESSDATA。
     * 而本 App 的登录可能走 TV 扫码：只有 access_token、CookieManager 里没有 SESSDATA，
     * 这时候纯 WEB 请求**必然**回 -101 —— 与其发一个注定失败的请求，不如先补、补不上就直接告诉用户。
     *
     * 补救只做一件事：重放登录时服务端下发的 cookie_info（写法对齐 ProfileAvatarUploader.restoreWebCookies，
     * 语义与冷启动 readAuthInfo → setCookie 完全一致，没有第二种凭据来源）。
     * 注意 `CookieStore.importFromWebView() + syncToWebView()` 救不了登录态：它只回写
     * buvid3 / buvid4 / b_nut / bili_ticket 这类**指纹** cookie，SESSDATA / bili_jct 是刻意不回写的。
     *
     * setCookie 的副作用只有一个：往 CookieManager 写 cookie + flush()，幂等；本来就登过网页版时
     * 第一步探测就返回 true、连写都不会写 —— 对老用户零影响。
     */
    private fun ensureWebLogin(): Boolean {
        if (hasWebLogin()) return true
        runCatching {
            BilimiaoCommApp.commApp.loginInfo?.cookie_info?.let {
                BilimiaoCommApp.commApp.setCookie(it)
            }
        }
        return hasWebLogin()
    }

    /** web 登录态判据：CookieManager 里有没有 SESSDATA（与 CommentApi.hasWebLoginCookie 同一判据） */
    private fun hasWebLogin(): Boolean =
        runCatching { MiaoHttp.sessDataToken() != null }.getOrDefault(false)

    /**
     * 只读文件头拿本地图片的真实像素尺寸（`inJustDecodeBounds` 不解码像素，开销就是读个头）。
     *
     * 存在的理由就是 [sendImage] 里那条：接口给的 width/height 可能是 0（APP 上传通道只回 location/url），
     * 而这两个值要进消息体；用本地文件的尺寸兜底，比发 0 或者干脆不发都可靠。
     * 解不出来返回 null（调用方按"没拿到"处理，不猜）。
     */
    private fun readLocalImageSize(file: File): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }.getOrNull()

    /**
     * 上传到 B 站图床（BFS）。成功返回图床信息，失败返回给用户看的原因。
     *
     * 通道策略与评论区发图**完全一致**：`upload_bfs` 是 web 接口，有 web 登录态就先走 WEB 通道、
     * 失败自动换 APP 通道兜底（保证"能发文字评论就一定能发图"）。
     * web 登录态在上游 [ensureWebLogin] 里已经补救并确认过（没有就直接不发了），这里只按当前
     * Cookie 决定先走哪条通道 —— 不再做"补 Cookie"的动作（那段 CookieStore 同步补不回凭据）。
     */
    private suspend fun uploadImageForIm(file: File): ImageUploadResult {
        val hasWeb = hasWebLogin()
        var lastError: String? = null
        for (preferWeb in if (hasWeb) listOf(true, false) else listOf(false, true)) {
            try {
                val response = BiliApiService.commentApi
                    .uploadImage(file = file, biz = "im", preferWeb = preferWeb)
                    .awaitCall()
                val parsed = MiaoJson.fromJson<ResponseData<UploadBfsInfo>>(
                    response.body?.string().orEmpty()
                )
                val data = parsed.data
                when {
                    !parsed.isSuccess ->
                        lastError = "code=${parsed.code} ${parsed.message}（HTTP ${response.code}）"
                    data == null -> lastError = "服务端没返回图片信息（HTTP ${response.code}）"
                    data.src.isBlank() -> lastError = "图片地址为空（HTTP ${response.code}）"
                    else -> return ImageUploadResult(data, null)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = "${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
            }
        }
        return ImageUploadResult(null, lastError)
    }

    /** 图片消息体里的 imageType 按 MIME 子类型写（文档示例是 jpeg），由落盘扩展名推出来 */
    private fun imageTypeName(file: File): String = when (file.extension.lowercase()) {
        "png" -> "png"
        "gif" -> "gif"
        "webp" -> "webp"
        else -> "jpeg"
    }

    /**
     * 乐观消息的插入 / 移除：一律切到主线程**同步**做，保证"先插后删"的顺序。
     *
     * 原来插入是 `launch(Dispatchers.Main) { … }` 异步派发、而失败回滚在 IO 线程直接改列表，
     * 主线程一卡就会变成"删完才插"——那条假气泡的 msg_key 再也没人删，会永久留在会话里。
     * 现在两边都走这里：调用点返回时改动已经落地，顺序不可能反。
     */
    private suspend fun applyLocalMsg(msgKey: Long, add: ChatMsgInfo? = null) =
        withContext(Dispatchers.Main) {
            val cur = list.data.value.toMutableList()
            if (add != null) cur.add(add) else cur.removeAll { it.msg_key == msgKey }
            list.data.value = cur
        }

    /**
     * 发送私信的**唯一**实现：文字（msg_type=1）与图片（msg_type=2）共用。
     * web 端 send_msg 只接受 msg_type 1 / 2 / 5，图片消息的 content 是图床 JSON（见 [sendImage]）。
     *
     * @param clearText 成功后是否清空输入框：文字消息清，图片消息不清
     */
    private suspend fun sendMsgInternal(msgType: Int, contentJson: String, clearText: Boolean) {
        // 提到 try 外：异常时也要能撤销这条本地乐观消息
        val fakeMsgKey = System.currentTimeMillis()
        try {
            isSending.value = true
            val ts = System.currentTimeMillis() / 1000
            val csrf = BilimiaoCommApp.commApp.loginInfo?.cookie_info?.cookies
                ?.find { it.name == "bili_jct" }?.value
            if (csrf == null) {
                // 本函数是 suspend 且**没有** CoroutineScope 接收者：裸 launch 会落到废弃的顶层 launch（编译报错），
                // 必须用 withContext。语义不变（提示完再 return）。
                withContext(Dispatchers.Main) { toast("未登录，无法发送") }
                isSending.value = false
                return
            }

            // 乐观更新：本地先塞一条消息（主线程同步插入，见 applyLocalMsg）
            val localMsg = ChatMsgInfo(
                msg_key = fakeMsgKey,
                msg_type = msgType,
                sender_uid = myUid,
                content = contentJson,
                timestamp = ts,
                msg_seqno = 0,
            )
            applyLocalMsg(fakeMsgKey, localMsg)

            // 发API
            val res = MiaoHttp.request {
                if (msgType == 2) {
                    // ★图片消息走纯 WEB：只有端点 + 表单，不注入 appkey/sign（见 IM_SEND_MSG_WEB_URL 的注释），
                    //   isWebApi = true 也正是 MiaoHttp 里跳过 app-key / x-bili-mid / Authorization 头的那一支
                    //   （Cookie 不受影响：MiaoHttp 无论哪种模式都会带 CookieManager 里的登录态）
                    isWebApi = true
                    url = IM_SEND_MSG_WEB_URL
                } else {
                    // 文字消息：保持原有形态，一个字不动
                    url = BiliApiService.biliVcApi("web_im/v1/web_im/send_msg")
                }
                method = MiaoHttp.POST
                formBody = mapOf(
                    "msg[sender_uid]" to myUid.toString(),
                    "msg[receiver_id]" to talkerId.toString(),
                    "msg[receiver_type]" to "1",
                    "msg[msg_type]" to msgType.toString(),
                    "msg[content]" to contentJson,
                    "msg[timestamp]" to ts.toString(),
                    "msg[dev_id]" to "bilimiao",
                    "csrf" to csrf,
                    "csrf_token" to csrf,
                )
            }.awaitCall().json<ResultInfo<Unit>>()
            if (res.isSuccess) {
                if (clearText) inputText.value = TextFieldValue("")
                showSendDialog.value = false
                // 先从API刷新获取真实数据，再移除本地假消息（避免竞态窗口）
                loadMsgsInternal()
                applyLocalMsg(fakeMsgKey)
                withContext(Dispatchers.Main) { toast("发送成功") }
                // 通知私信列表刷新
                MessageRefreshEvent.trigger()
            } else {
                // 失败：先撤掉本地假消息（主线程同步），再提示原因
                applyLocalMsg(fakeMsgKey)
                // 图片这一支把服务端 code 也带上，真机可直接按码分流（-101 再补一句可操作的话术）；
                // 文字链的文案与形态保持不变
                val failText = if (msgType == 2) {
                    val base = "发送失败（code=${res.code}）：${res.message.ifBlank { "未知原因" }}"
                    if (res.code == -101) {
                        "$base；网页登录态失效了，请用「网页登录」重新登录后再发图"
                    } else {
                        base
                    }
                } else {
                    res.message.ifBlank { "发送失败" }
                }
                withContext(Dispatchers.Main) { toast(failText) }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            // 异常时也要撤掉乐观更新的本地消息，否则它会永远留在列表里
            applyLocalMsg(fakeMsgKey)
            withContext(Dispatchers.Main) { toast("发送失败: ${e.message}") }
        } finally {
            isSending.value = false
        }
    }

    // 跳转用户主页
    fun toUserPage(uid: Long) {
        pageNavigation.navigate(UserSpacePage(id = uid.toString()))
    }

    companion object {
        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dateTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    fun fmtTime(ts: Long): String {
        val cal = Calendar.getInstance()
        val msgCal = Calendar.getInstance().apply { timeInMillis = ts * 1000 }
        return if (cal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR) &&
            cal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR)
        ) timeFmt.format(Date(ts * 1000))
        else dateTimeFmt.format(Date(ts * 1000))
    }
}


// ─── UI ──────────────────────────────────────────────────────

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ChatPageContent(
    talkerId: Long, initialName: String, initialFace: String,
) {
    val viewModel: ChatViewModel = diViewModel(key = "chat-$talkerId") {
        ChatViewModel(di = it, talkerId = talkerId)
    }
    val windowStore by rememberInstance<WindowStore>()
    val windowState by windowStore.stateFlow.collectAsStateWithLifecycle()
    val windowInsets = windowState.getContentInsets(localContainerView())

    val list by viewModel.list.data.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing
    val listState = rememberLazyListState()

    LaunchedEffect(talkerId) {
        viewModel.setTalkerInfo(initialName, initialFace)
        if (initialFace.isBlank() || initialName.isBlank()) {
            viewModel.loadUserInfo(talkerId)
        }
    }

    // 只在“最新一条消息”变化时滚到底部：往上加载历史消息时不要打断用户
    val latestMsgKey = list.lastOrNull()?.msg_key
    // 首次贴底完成前不要触发补历史，否则刚进页面就会连着拉好几页
    var loadMoreArmed by remember { mutableStateOf(false) }
    LaunchedEffect(latestMsgKey) {
        // 注意：首帧列表还是空的（latestMsgKey == null）时不能放行 ——
        // 否则首屏到达后 effect 重启，snapshotFlow 立刻发出 index=0（贴底动画还没完成），
        // 进聊天页就会连着拉好几页历史
        if (latestMsgKey == null) return@LaunchedEffect
        listState.animateScrollToItem(list.lastIndex)
        loadMoreArmed = true
    }

    // 滚到顶部附近时继续加载更早的消息（历史消息在列表上方）
    LaunchedEffect(list.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (loadMoreArmed && index <= 1) viewModel.loadMore()
            }
    }

    val sendDialogVisible = viewModel.showSendDialog

    val talkerName = viewModel.talkerName.value.ifBlank { initialName.ifBlank { "聊天" } }
    val pageConfigId = PageConfig(
        title = talkerName,
        menu = rememberMyMenu {
            myItem {
                key = MenuKeys.send
                iconFileName = "ic_baseline_send_24"
                title = "发消息"
            }
        }
    )
    PageListener(
        configId = pageConfigId,
        onMenuItemClick = { _, item ->
            if (item.key == MenuKeys.send) {
                viewModel.showSendDialog.value = true
            }
        },
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(top = windowInsets.topDp.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = windowInsets.bottomDp.dp),
            ) {
                if (list.isEmpty() && !isRefreshing) {
                    item { EmptyHint("暂无消息") }
                }
                item { Spacer(Modifier.height(8.dp)) }
                items(list, key = { it.msg_key }) { msg ->
                    // 图片消息（msg_type=2/6）先分流：它的 content 是图床 JSON，
                    // 落到 parseMsgText 的兜底分支会显示成"[系统通知]"文字气泡
                    val picture = remember(msg.msg_key) { parsePicContent(msg) }
                    if (picture != null) {
                        ChatBubble(
                            msg = msg,
                            isMe = msg.sender_uid == viewModel.myUid,
                            myFace = viewModel.myFace,
                            talkerFace = viewModel.talkerFace.value.ifBlank { initialFace },
                            talkerId = talkerId,
                            viewModel = viewModel,
                            timeText = viewModel.fmtTime(msg.timestamp),
                            msgText = "",
                            picture = picture,
                        )
                    } else {
                        val parsed = remember(msg.msg_key) { parseMsgText(msg.content) }
                        // 系统提示数组→Toast
                        if (parsed.toastText.isNotBlank()) {
                            LaunchedEffect(msg.msg_key) {
                                toast(parsed.toastText)
                            }
                        }
                        // 有文本内容才渲染气泡
                        if (parsed.text.isNotBlank()) {
                            ChatBubble(
                                msg = msg,
                                isMe = msg.sender_uid == viewModel.myUid,
                                myFace = viewModel.myFace,
                                talkerFace = viewModel.talkerFace.value.ifBlank { initialFace },
                                talkerId = talkerId,
                                viewModel = viewModel,
                                timeText = viewModel.fmtTime(msg.timestamp),
                                msgText = parsed.text,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
    }

    if (sendDialogVisible.value) {
        AutoSheetDialog(
            // 规则 4：外壳已经刷过 surface，这里不要再叠一层背景；modifier 只承载 padding
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
            content = { ChatSendPanel(viewModel) },
            onDismiss = { viewModel.showSendDialog.value = false },
        )
    }
}

// ─── 发送消息面板 ─────────────────────────────────────────────

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ChatSendPanel(vm: ChatViewModel) {
    val showEmoji = remember { mutableStateOf(false) }
    // 系统相册选择器（Photo Picker）：API 30+ 免权限；30 以下自动回退到系统文件选择器。
    // 与评论区发图用的是同一个 contract；私信选完直接上传+发送，不摆预览条（对齐 PiliPlus 的一步直发）
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.sendImage(uri)
    }

    // ★左右各 16dp（AGENTS §2.10 的四档；与评论区那条输入条同档）：
    //   原来这里没有左右内边距 → 输入框和发送按钮两边直接顶到屏幕。
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // 内容区（输入框 + 表情）：吃「剩余高度」且可滚动。
        // 横屏可用高只有 ~360dp，原来"固定 260dp 表情格 + 无 weight 无滚动的根 Column"
        // 会把输入框 / 发送按钮挤出屏幕。weight(fill = false)：空间够时保持原来的紧凑高度
        // （竖屏观感不变），空间不够时被封顶并交给 verticalScroll 滚，不会再溢出、不会和发送行重叠。
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            // 统一输入框（描边/圆角/文字色走 MiaoInputBar 那套语义色；与评论区那个是**同一份实现**）
            MiaoInputField(
                value = vm.inputText.value,
                onValueChange = { vm.inputText.value = it },
                placeholder = "说点什么...",
                minHeight = 80.dp,
                maxHeight = 160.dp,
                maxLines = 5,
            )

            Spacer(Modifier.height(8.dp))

            AnimatedVisibility(visible = showEmoji.value) {
                EmojiGridBox(
                    // 固定 height(260.dp) → heightIn 上限：横屏放不下时自己会被压小，
                    // 表情格内部照样能滚（表情都够得着），不再顶掉输入框。
                    // 注意必须留一个有界上限：表情格里的 LazyVerticalGrid 在 verticalScroll 下
                    // 拿到无界高度会直接抛异常。
                    modifier = Modifier.heightIn(max = 260.dp).padding(top = 8.dp),
                    onInputEmoji = { emoji ->
                        // 之前用 value.length 当插入点 → 光标在中间时表情也会被追加到末尾
                        val cur = vm.inputText.value
                        val text = cur.text
                        val start = cur.selection.min.coerceIn(0, text.length)
                        val end = cur.selection.max.coerceIn(0, text.length)
                        vm.inputText.value = TextFieldValue(
                            text.substring(0, start) + emoji.text + text.substring(end),
                            TextRange(start + emoji.text.length),
                        )
                    }
                )
            }
        }

        // 发送 / 表情开关行钉底
        // （它是没有 weight 的兄弟节点、会被**先**测量，所以横屏 / 大字体 / 键盘弹起时都不会被挤成 0 高）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            // 表情 / 图片两颗工具按钮：与评论区**同一份实现**（`MiaoInputToolButton`，线框图标 + 44dp）
            MiaoInputToolButton(
                icon = Icons.Outlined.EmojiEmotions,
                contentDescription = "表情",
                active = showEmoji.value,
                onClick = { showEmoji.value = !showEmoji.value },
            )
            // 发图片：图标/位置与评论区发图那颗按钮对齐（表情在左、图片其次）
            MiaoInputToolButton(
                icon = Icons.Outlined.Image,
                contentDescription = "添加图片",
                enabled = !vm.isUploadingImage.value && !vm.isSending.value,
                // 压缩 + 上传期间转圈：这里没有缩略图预览条，得让用户看到"在传"
                loading = vm.isUploadingImage.value,
                onClick = {
                    // 选图前先收起表情面板，避免两个面板叠在一起（评论区同一个约定）
                    showEmoji.value = false
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            )
            Spacer(Modifier.weight(1f))
            // 统一发送按钮：**不填充**（TextButton）+ 小飞机 + 文案「发送」（与评论区那颗是**同一份实现**）。
            // 原来这里是 primaryContainer 的填充块：深色档 primaryContainer ≈ tone 30，与近黑 sheet 几乎同色，
            // 加上"输入为空时是禁用态（onSurface 12%）"——这颗按钮在深色主题下几乎看不出来。
            MiaoSendButton(
                onClick = { vm.sendMsg() },
                // 上传图片期间也禁用：否则图片还在传、用户又把文字发出去了，两条发送请求并发
                enabled = vm.inputText.value.text.isNotBlank() && !vm.isUploadingImage.value,
                loading = vm.isSending.value,
            )
        }
    }
}

// ─── 单条消息气泡 ────────────────────────────────────────────

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ChatBubble(
    msg: ChatMsgInfo, isMe: Boolean, myFace: String, talkerFace: String,
    talkerId: Long, viewModel: ChatViewModel, timeText: String, msgText: String,
    picture: ChatPicture? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            if (!isMe) {
                // 对方头像可点击跳主页
                Avatar(talkerFace, onClick = {
                    if (talkerId > 0) viewModel.toUserPage(talkerId)
                })
                Spacer(Modifier.width(8.dp))
            }

            Column(horizontalAlignment = if (isMe) Alignment.End else Alignment.Start) {
                Surface(
                    shape = RoundedCornerShape(
                        topStart = if (isMe) 16.dp else 0.dp,
                        topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp,
                    ),
                    color = if (isMe) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    if (picture != null) {
                        // 图片气泡：复用评论区的 ImagesGrid（自带圆角、加载态、点击进全局图片预览），
                        // 不给私信另写一套图片渲染。外层 Surface 已限宽 280dp，
                        // 图片用 Fit 等比缩放，不会裁切（AGENTS 规则 5）。
                        Box(modifier = Modifier.padding(4.dp)) {
                            ImagesGrid(listOf(picture.toPreviewModel()))
                        }
                    } else {
                        val parts = remember(msgText) { parseLinks(msgText) }
                        SelectionContainer {
                            Text(
                                text = buildAnnotatedString {
                                    parts.forEach { (text, isLink) ->
                                        if (isLink) {
                                            // 之前只把链接染成链接样式，点了没反应；这里挂上真正的跳转
                                            // （默认走 LocalUriHandler → 站内链接进原生页，站外进浏览器）
                                            withLink(
                                                LinkAnnotation.Url(
                                                    text,
                                                    TextLinkStyles(
                                                        style = SpanStyle(
                                                            color = MaterialTheme.colorScheme.primary,
                                                            textDecoration = TextDecoration.Underline,
                                                        )
                                                    )
                                                )
                                            ) { append(text) }
                                        } else append(text)
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                                color = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(timeText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (isMe) {
                Spacer(Modifier.width(8.dp))
                Avatar(myFace)
            }
        }
    }
}

// ─── 共用部件 ────────────────────────────────────────────────

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun Avatar(url: String, onClick: (() -> Unit)? = null) {
    // 头像 URL 统一走 messageAvatarUrl（消息页唯一一份拼法，与私信列表共用）：
    //   autoHttps 修 http:// 与协议相对地址；@200w_200h 走图床缩略图；空值给官方默认头像。
    // 与列表拼出的是**同一个字符串** ⇒ 同一用户两处共用同一份 Glide 缓存（一处下过、另一处秒出）。
    // 占位图也换成项目统一的头像占位（原来借的是视频封面那张 TV 图）
    val place = cn.a10miaomiao.bilimiao.compose.R.drawable.bili_akari_img
    GlideImage(
        model = messageAvatarUrl(url),
        contentDescription = null,
        modifier = Modifier.size(40.dp).clip(CircleShape).let {
            if (onClick != null) it.clickable(onClick = onClick) else it
        },
        contentScale = ContentScale.Crop,
        loading = placeholder(place),
        failure = placeholder(place),
        requestBuilderTransform = { it.apply(RequestOptions.diskCacheStrategyOf(DiskCacheStrategy.ALL)) },
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun parseLinks(text: String): List<Pair<String, Boolean>> {
    val urlPattern = Regex("https?://[\\w./?=&\\-+#%]+")
    val result = mutableListOf<Pair<String, Boolean>>()
    var lastEnd = 0
    urlPattern.findAll(text).forEach { match ->
        if (match.range.first > lastEnd) result.add(text.substring(lastEnd, match.range.first) to false)
        result.add(match.value to true)
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) result.add(text.substring(lastEnd) to false)
    return result.ifEmpty { listOf(text to false) }
}
