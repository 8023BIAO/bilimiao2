package cn.a10miaomiao.bilimiao.compose.pages.user

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.pages.auth.H5LoginPage
import cn.a10miaomiao.bilimiao.compose.pages.community.components.ReplyImageHelper
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.apis.MemberProfileApi
import com.a10miaomiao.bilimiao.comm.apis.ProfileAvatarUploader
import com.a10miaomiao.bilimiao.comm.entity.MessageInfo
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.user.AccountMyInfoInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * 一次提交的结果，给页面显示。
 *
 * [message] 在失败时**一定是服务端原文**（PiliPlus view.dart:412 也只做 `toast(message)`）：
 * 硬币不足 / 昵称违规 / 频率限制这些规则都在服务端，我们本地猜不出准确的码，
 * 把原文透传给用户比翻译成"操作失败"有用得多。
 *
 * ★ 第二阶段多了一个 [needWebLogin]：头像走 web 通道，可能压根**没发请求**
 *   （缺 SESSDATA/bili_jct），那种失败跟"服务端拒绝"不是一回事，页面要能分别显示
 *   （失败原因 + 一个「去网页登录」按钮），不能只丢一句 message 让用户干瞪眼。
 */
data class ProfileSubmitResult(
    /** 提交的字段：MemberProfileApi.FIELD_UNAME / FIELD_SIGN / FIELD_FACE */
    val field: String,
    val success: Boolean,
    val message: String,
    /** true = 失败原因是"缺 web 登录态"（只有头像会用到），页面据此显示「去网页登录」 */
    val needWebLogin: Boolean = false,
)

/**
 * 「编辑资料」页状态机：读资料 + 改单个字段（第二阶段：+ 改头像）。
 *
 * 依据：调研报告 §4.5 第 4/8 步、§4.6 的 P0-2（写操作绝不重试）、P0-1（web 登录态）。
 *
 * ★★ 这个类里最重要的几件事，改动前请先读对应的注释：
 *    1. **单飞**：同一时刻只允许一个写操作在飞（[submit] / [uploadAvatar] / [retryAvatarUpload]
 *       共用同一把 `_submitting` 锁）—— 头像和昵称并发提交没有任何好处，只会让"哪一步失败"
 *       变得说不清
 *    2. **只在 code == 0 时**才更新页面状态与 UserStore 缓存（失败保持原值，不做乐观更新）
 *    3. **绝不自动重试**：失败只提示 + 给一个「重试」按钮，由用户手动决定
 */
class EditProfileViewModel(override val di: DI) : ViewModel(), DIAware {

    private val userStore: UserStore by instance()

    /** 缺 web 登录态时「去网页登录」的去处（头像专用，见 [toWebLogin]） */
    private val pageNavigation: PageNavigation by instance()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> get() = _loading

    /** 读资料失败的原因（String 或 Exception，交给 BiliFailBox 显示） */
    private val _fail = MutableStateFlow<Any?>(null)
    val fail: StateFlow<Any?> get() = _fail

    private val _profile = MutableStateFlow<AccountMyInfoInfo?>(null)
    val profile: StateFlow<AccountMyInfoInfo?> get() = _profile

    /** true = 有提交在飞。UI 的禁用态读它；真正的互斥靠 [_submitting] 的 CAS，不靠 UI */
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> get() = _submitting

    /** 正在提交哪个字段（昵称/签名/头像：只有那一行显示转圈，但整页写操作都禁用） */
    private val _submittingField = MutableStateFlow<String?>(null)
    val submittingField: StateFlow<String?> get() = _submittingField

    private val _lastResult = MutableStateFlow<ProfileSubmitResult?>(null)
    val lastResult: StateFlow<ProfileSubmitResult?> get() = _lastResult

    /**
     * 头像：**已经处理好的本地文件**。
     *
     * 为什么要留着它：上传失败后用户点「重试」时不必重新选图、重新压缩
     * （重选一次 = 重新解码一张几千万像素的原图，纯浪费）。上传成功后置空。
     */
    private val _avatarPrepared = MutableStateFlow<File?>(null)
    val avatarPrepared: StateFlow<File?> get() = _avatarPrepared

    /**
     * 头像：**本地预览图**（上传成功后、服务端还没返回新 URL 时用它显示）。
     *
     * ★ 为什么一定要有这个（报告 §3.4 第 6 条 / §4.6"改完头像 Glide 还是旧图"）：
     *   服务端头像 URL 通常要过一会儿（甚至过审）才变；这段时间 `myinfo.face` 还是**旧地址**，
     *   Glide 按 URL 命中缓存 → 用户刚改完却看到旧头像，会以为没改成。
     *   直接把用户自己选的那张图当预览，是唯一"永远不会说谎"的显示。
     */
    private val _avatarPreview = MutableStateFlow<File?>(null)
    val avatarPreview: StateFlow<File?> get() = _avatarPreview

    /**
     * 头像前置体检的结论：null = web 登录态齐全；非 null = 给用户看的原因 + 下一步动作。
     * 进页面就探测一次（**只读**），别等用户选完图点上传才告诉他"你这台机器改不了"。
     */
    private val _webLoginWarning = MutableStateFlow<String?>(null)
    val webLoginWarning: StateFlow<String?> get() = _webLoginWarning

    /**
     * 冷却截止时刻（`System.currentTimeMillis()` 口径）。
     *
     * 只被 [submit] / [startAvatarTask] 读写，而它们与各自的协程都在主线程调度器上跑
     * （viewModelScope 默认 Main，`awaitCall()` 只是挂起、不切线程），所以不需要加锁。
     */
    private var cooldownUntil = 0L

    /** 拿 Application 当 Context：给 [ReplyImageHelper.prepare] 读相册用 */
    private val context: Context get() = BilimiaoCommApp.commApp.app

    init {
        load()
    }

    /**
     * 读账号资料（`x/v2/account/myinfo`）。
     *
     * 这是 GET、幂等，随便重试；页面上也留了「刷新」入口（改名/改签名/改头像后服务端状态
     * 可能要过一会儿才更新）。
     */
    fun load() = viewModelScope.launch(Dispatchers.IO) {
        _loading.value = true
        _fail.value = null
        try {
            if (!userStore.isLogin()) {
                reportLoadFailure("未登录，请先登录后再编辑资料")
                return@launch
            }
            val res = BiliApiService.memberProfileApi
                .myinfo()
                .awaitCall()
                .json<ResponseData<AccountMyInfoInfo>>()
            if (res.code != 0) {
                reportLoadFailure(res.message)
                return@launch
            }
            val data = res.data
            if (data == null || data.mid == 0L) {
                // 和 UserStore.loadInfo 同一个教训：没有登录态时服务端**也会回 code=0**，
                // 只是 mid=0 —— 不看 mid 就会把一页空资料当成"读取成功"。
                reportLoadFailure("账号资料读取失败，请重新登录后再试")
                return@launch
            }
            _profile.value = data
            // 资料真的到手了，才值得做一次**只读**的 web 登录态体检：
            // 缺 SESSDATA/bili_jct 的话，页面上常驻一行提示 +「去网页登录」，
            // 而不是等用户选完图点上传才失败（报告 P0-1：TV 扫码登录很可能没有 web 登录态）
            refreshWebLoginWarning(allowRestore = false)
        } catch (e: Exception) {
            e.printStackTrace()
            reportLoadFailure(e)
        } finally {
            _loading.value = false
        }
    }

    /**
     * 读资料失败的统一出口。
     *
     * ★ 为什么要 toast 一份：页面只在**没有资料**时才用整屏失败态显示 [fail]。
     *   已经有资料时点「刷新」再失败，页面上没有位置显示原因 —— 用户会觉得"点了没反应"。
     */
    private fun reportLoadFailure(message: Any) {
        _fail.value = message
        if (_profile.value != null) {
            toast(if (message is String) message else "网络请求失败")
        }
    }

    // ──────────────────────────── 头像（第二阶段） ────────────────────────────

    /**
     * 选图入口：系统相册返回的 uri → 压缩/裁剪落盘 → 上传。
     *
     * 注意这只是"用户选了图"，**不代表用户确认了要换** —— B 站的头像接口没有"二次确认"这一步
     * （PiliPlus 也是选完直接传，view.dart:476-530），所以这里也照做，但把失败原因完整暴露出来。
     */
    fun uploadAvatar(uri: Uri) {
        startAvatarTask {
            val file = try {
                AvatarImageHelper.prepare(context, uri)
            } catch (e: CancellationException) {
                // 见 startAvatarTask：取消不是失败，原样抛出
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                reportFailure(MemberProfileApi.FIELD_FACE, "图片处理失败：${e.message ?: e.toString()}")
                return@startAvatarTask false
            }
            // 上一张"待重试"的图没人要了：换新的就删旧的（cacheDir 里的临时文件别越堆越多）
            val old = _avatarPrepared.value
            if (old != null && old.absolutePath != file.absolutePath && old != _avatarPreview.value) {
                runCatching { old.delete() }
            }
            _avatarPrepared.value = file
            doUploadAvatar(file)
        }
    }

    /**
     * 上传失败后的「点击重试」。
     *
     * ★ 为什么允许重试、但**不允许自动重试**：
     *   `face/update` 是幂等的（同一张图重复提交没有额外代价，报告 §8.3），所以给用户一个
     *   明确的「重试」按钮是安全的；但写操作自动重发可能触发风控，且"到底写没写成功"在网络
     *   异常时是未知的 —— 重试必须由人来做决定（报告 §4.6 第一条）。
     */
    fun retryAvatarUpload() {
        val file = _avatarPrepared.value
        if (file == null || !file.exists()) {
            toast("没有可重试的图片，请重新选择")
            return
        }
        startAvatarTask { doUploadAvatar(file) }
    }

    /**
     * 头像任务的公共外壳：**冷却 + 单飞 + 统一收尾**，与 [submit] 完全同一套规矩。
     *
     * 为什么和昵称/签名共用一把锁（`_submitting`）：三种操作都是"改账号资料"的写操作，
     * 并发提交只会让"上一次到底改成没改成"变得说不清；共用锁的代价只是"改头像时不能同时改名"，
     * 这在真人操作下几乎不会发生。
     */
    private fun startAvatarTask(block: suspend () -> Boolean) {
        if (!userStore.isLogin()) {
            toast("请先登录")
            return
        }
        // 冷却（成功后尤其重要：写操作不该被连点/重发）
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) {
            toast("提交太频繁，请 ${(cooldownUntil - now) / 1000 + 1} 秒后再试")
            return
        }
        // 单飞：CAS 抢锁，抢不到说明另一个写操作在飞
        if (!_submitting.compareAndSet(false, true)) {
            toast("正在提交，请稍候")
            return
        }
        _submittingField.value = MemberProfileApi.FIELD_FACE
        viewModelScope.launch(Dispatchers.IO) {
            var success = false
            try {
                success = block()
            } catch (e: CancellationException) {
                // 页面销毁 / 协程被取消：这不是"提交失败"，必须原样抛出
                // （吞掉取消会让已销毁的页面弹 toast，也破坏结构化并发的取消语义）
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                // 这里接住的是"压缩/裁剪/上传"之外漏出来的异常（例如相册 uri 读不了）
                reportFailure(
                    MemberProfileApi.FIELD_FACE,
                    "网络异常，提交结果未知：${e.message ?: e.toString()}",
                )
            } finally {
                val cooldown = if (success) SUCCESS_COOLDOWN_MS else FAIL_COOLDOWN_MS
                cooldownUntil = System.currentTimeMillis() + cooldown
                _submittingField.value = null
                _submitting.value = false
            }
        }
    }

    /** 真正发上传请求。返回 true = 服务端 `code == 0` */
    private suspend fun doUploadAvatar(file: File): Boolean {
        val oldFace = _profile.value?.face.orEmpty()
        // ★ 零自动重试：探测、兜底、上传、解析全在 ProfileAvatarUploader 里一次走完
        val result = ProfileAvatarUploader.uploadFace(file)
        if (!result.success) {
            // 重新体检一次（只读）：如果是登录态问题，页面上的常驻提示与「去网页登录」要亮起来
            refreshWebLoginWarning(allowRestore = false)
            reportFailure(
                field = MemberProfileApi.FIELD_FACE,
                message = result.message,
                needWebLogin = !result.loginState.ready,
            )
            return false
        }
        // ★★ 只有 code == 0 才动本地状态（和昵称/签名同一条规矩，报告 §4.6"失败回滚"）
        _webLoginWarning.value = null
        // 上一张预览图（上次成功后留下的）没人用了：换新图就删旧的，别在 cacheDir 里堆着
        val previousPreview = _avatarPreview.value
        if (previousPreview != null && previousPreview.absolutePath != file.absolutePath) {
            runCatching { previousPreview.delete() }
        }
        _avatarPreview.value = file
        // prepared 不再需要（重试已完成），但**文件不删** —— 它此刻正在当预览图
        _avatarPrepared.value = null
        _lastResult.value = ProfileSubmitResult(MemberProfileApi.FIELD_FACE, true, "修改成功")
        toast("修改成功")
        // 刷新用**独立协程**，不占着单飞锁：
        // 否则"上传已经成功了"这个状态还要陪 `refreshProfileAfterAvatar` 里的 1 秒延迟一起转圈，
        // 看起来像是还没传完（用户会以为卡住了）。
        viewModelScope.launch(Dispatchers.IO) { refreshProfileAfterAvatar(oldFace) }
        return true
    }

    /**
     * 上传成功后重新拉一次 `myinfo`，把服务端的头像地址拿回来。
     *
     * ★ 为什么要延迟：PiliPlus 是成功后 **500ms** 再拉（view.dart:532-538），说明头像不是
     *   "同一个请求就同步生效"的。这里给 1s，多留一点余量；**拉不到也不影响"上传已成功"**，
     *   页面上继续用本地预览图显示，不会自相矛盾。
     */
    private suspend fun refreshProfileAfterAvatar(oldFace: String) {
        delay(FACE_REFRESH_DELAY_MS)
        val fresh = try {
            val res = BiliApiService.memberProfileApi
                .myinfo()
                .awaitCall()
                .json<ResponseData<AccountMyInfoInfo>>()
            // ★先把 data 取到本地变量：`ResponseData.data` 是**另一个模块**的 public 属性，
            //   Kotlin 不允许对它做 smart cast（"Smart cast is impossible … declared in different module"），
            //   直接写 res.data.mid 会编译失败 —— 这是跨模块边界的经典坑。
            val d: AccountMyInfoInfo? = res.data
            if (res.code != 0 || d == null || d.mid == 0L) null else d
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return
        // 整份资料都换新（昵称/签名/硬币也可能变了；输入框的初值只灌一次，不会被冲掉）
        _profile.value = fresh
        val newFace = fresh.face
        if (newFace.isNotBlank() && newFace != oldFace) {
            // ★★ 这里为什么可以写 UserStore，而第一阶段的"签名"不行：
            //    `UserInfo`（x/v2/account/mine 的模型）**本来就有 face 字段**，`loadInfo()`
            //    每次也会返回它 —— 所以 copy 进去是"真缓存"，不会被下一次 loadInfo/冷启动清空。
            //    （签名没有这个待遇：UserInfo 里没有 sign，硬加必被清空，见第一阶段报告 §5。）
            val info = userStore.stateFlow.value.info
            if (info != null) {
                userStore.setUserInfo(info.copy(face = newFace))
            }
            // 服务端给了新地址（BFS 文件名带 hash，通常会变）→ Glide 缓存键也变了，能自然刷出来。
            // 这时本地预览的使命结束，顺手删掉落盘的临时文件。
            runCatching { _avatarPreview.value?.delete() }
            _avatarPreview.value = null
        }
        // else：URL 没变（很可能在审核中）→ **保留本地预览**，
        //       否则 Glide 还会拿缓存里的旧头像，用户看到的就是"我没改成"
    }

    /** 只读/可兜底地体检 web 登录态，并把给用户看的话写进 [webLoginWarning] */
    private fun refreshWebLoginWarning(allowRestore: Boolean): ProfileAvatarUploader.WebLoginState? {
        val state = runCatching { ProfileAvatarUploader.probeWebLogin(allowRestore) }.getOrNull()
        _webLoginWarning.value = when {
            state == null -> "读不到本地网页登录态（Cookie 探测异常）。改头像前请先确认已用「网页登录」登录过。"
            state.ready -> null
            else -> state.userHint()
        }
        return state
    }

    /** 缺 web 登录态时的一键去处：内置网页登录页（已在 BilimiaoPageRoute 注册，不新增页面） */
    fun toWebLogin() {
        pageNavigation.navigate(H5LoginPage())
    }

    override fun onCleared() {
        super.onCleared()
        // 头像的临时文件都在 cacheDir/reply_image/ 下：页面销毁时清掉自己这两份引用，
        // 不留"看不见但一直占空间"的文件（系统清缓存是兜底，不该当主要手段）
        runCatching { _avatarPrepared.value?.delete() }
        runCatching { _avatarPreview.value?.delete() }
        _avatarPrepared.value = null
        _avatarPreview.value = null
    }

    // ──────────────────────── 昵称 / 个性签名（第一阶段） ────────────────────────

    /**
     * 提交一个字段（第一阶段：昵称 / 个性签名）。
     *
     * ★★ **写操作，绝不自动重试**（报告 §4.6 第一行）：
     *    我们发图评论那条链路有"3 次退避重试"（ReplyEditDialog），但改名要扣 6 硬币，
     *    一次网络抖动重试三次 = 可能扣 18 硬币，还可能触发风控。
     *    所以这里失败只提示、由用户手动决定要不要再点一次，并做了两层保护：
     *      · 单飞（CAS）：同一时刻只有一个请求在飞
     *      · 冷却：一次提交结束后（无论成败）短时间内不再接受新的提交
     */
    fun submit(field: String, value: String) {
        val current = _profile.value
        if (current == null) {
            toast("资料还没加载出来，请稍候")
            return
        }
        // ① 登录态与 access_key：PiliPlus 在发请求前也先看 accessKey 是否为空
        //    （view.dart:348-352 → '请退出账号后重新登录'）。老版本登录态可能没有 access_key，
        //    与其等一个必然的 -101，不如直接说清楚。
        if (!userStore.isLogin()) {
            toast("请先登录")
            return
        }
        if (BilimiaoCommApp.commApp.loginInfo?.token_info?.access_token.isNullOrBlank()) {
            reportFailure(field, "登录态缺少 access_key，请退出账号后重新登录")
            return
        }
        // ② 与原内容相同：不发请求（PiliPlus view.dart:330-332）
        val oldValue = if (field == MemberProfileApi.FIELD_SIGN) current.sign else current.name
        if (value == oldValue) {
            toast("与原内容相同")
            return
        }
        // ③ 改名的本地预检：硬币 < 6 直接拦（PiliPlus view.dart:164-165）。
        //    ⚠️ 服务端在硬币不足时回什么码**未知**（报告 §2.2 / §5.2），所以这里只是"提前告知"，
        //    不把它当成功，服务端 message 依旧是最终依据。
        if (field == MemberProfileApi.FIELD_UNAME && current.coins < RENAME_COIN_COST) {
            reportFailure(
                field,
                "硬币不足：改昵称需要 $RENAME_COIN_COST 硬币，当前 ${current.coins.toInt()}",
            )
            return
        }
        // ④ 冷却（成功后尤其重要：防连点导致重复扣硬币）
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) {
            toast("提交太频繁，请 ${(cooldownUntil - now) / 1000 + 1} 秒后再试")
            return
        }
        // ⑤ 单飞：CAS 抢锁。
        //    只把按钮 enabled=false 是不够的 —— 点击到重组之间有一个窗口，两次点击可能都进来；
        //    改名是**付费**操作，重复提交就是真扣两次硬币，所以互斥必须发生在状态层。
        if (!_submitting.compareAndSet(false, true)) {
            toast("正在提交，请稍候")
            return
        }
        _submittingField.value = field
        viewModelScope.launch(Dispatchers.IO) {
            var success = false
            try {
                val res = BiliApiService.memberProfileApi
                    .updateField(field, value)
                    .awaitCall()
                    .json<MessageInfo>()
                if (res.code == 0) {
                    success = true
                    // ★ 只有 code == 0 才动本地状态与缓存（PiliPlus view.dart:381-410 也是成功分支里才回写）
                    applySuccess(field, value, current)
                    _lastResult.value = ProfileSubmitResult(field, true, "修改成功")
                    toast("修改成功")
                } else {
                    // 失败：**服务端原文**，不做任何本地更新
                    reportFailure(field, res.message)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // 网络异常 ≠ 服务端没写：结果未知。所以这里既不能乐观更新，也不能当"确定失败"，
                // 文案里明确写出来，并让用户用「刷新」去看服务端的真实值。
                reportFailure(field, "网络异常，提交结果未知：${e.message ?: e.toString()}")
            } finally {
                // 成功 3 秒 / 失败 1 秒：成功的冷却要够长（防重复扣硬币），失败只需挡住连点
                val cooldown = if (success) SUCCESS_COOLDOWN_MS else FAIL_COOLDOWN_MS
                cooldownUntil = System.currentTimeMillis() + cooldown
                _submittingField.value = null
                _submitting.value = false
            }
        }
    }

    /**
     * 提交成功后的本地更新（**调用点只有一个：code == 0 分支**）。
     *
     * 1. 页面状态：昵称/签名换成新值；改昵称顺手把硬币 -6（PiliPlus view.dart:384-386）
     * 2. 共享缓存：昵称写回 [UserStore]，让"我的"页的头像卡片（StartUserCard 读 userState.info）
     *    立刻显示新昵称
     *
     * ★ 为什么这里**不调** `userStore.loadInfo()`：
     *   `loadInfo()` 打的是 `x/v2/account/mine`（GET）。改昵称是要过审核的，服务端可能
     *   还返回旧名字 —— 刚存的新名字会被它冲回去，"我的"页闪一下变回旧名，用户以为没改成。
     *   本地 copy 是即时的，等下一次冷启动 / 手动刷新时 `loadInfo()` 自然会跟服务端对齐。
     *
     * ⚠️ 签名没有写进 UserStore：`UserInfo` 里**根本没有 sign 字段**，而且 `loadInfo()` 是
     *    "用响应整体替换 info"，`x/v2/account/mine` 又不返回 sign —— 就算硬加一个字段，
     *    也会在每次 loadInfo/冷启动时被清空。签名的本地缓存目前只存在于本页状态里（_profile）。
     *    （★ 第二阶段补充：**头像没有这个问题**，`UserInfo.face` 是接口本来就有的字段，
     *     所以 [refreshProfileAfterAvatar] 里可以放心写回 UserStore。）
     */
    private fun applySuccess(field: String, value: String, current: AccountMyInfoInfo) {
        _profile.value = if (field == MemberProfileApi.FIELD_UNAME) {
            current.copy(
                name = value,
                coins = (current.coins - RENAME_COIN_COST).coerceAtLeast(0.0),
            )
        } else {
            current.copy(sign = value)
        }
        if (field == MemberProfileApi.FIELD_UNAME) {
            val info = userStore.stateFlow.value.info
            if (info != null) {
                userStore.setUserInfo(info.copy(name = value))
            }
        }
    }

    private fun reportFailure(field: String, message: String, needWebLogin: Boolean = false) {
        _lastResult.value = ProfileSubmitResult(field, false, message, needWebLogin)
        toast(message)
    }

    companion object {
        /** 改一次昵称要扣的硬币数。PiliPlus 硬编码 6（view.dart:386），产品规则见报告 §5.3 */
        const val RENAME_COIN_COST = 6

        /** 成功后冷却：这一条是防"连点/网络重发"把 6 硬币扣两次 */
        private const val SUCCESS_COOLDOWN_MS = 3_000L

        /** 失败后冷却：网络异常时服务端到底写没写是未知的，1 秒足够挡住连点 */
        private const val FAIL_COOLDOWN_MS = 1_000L

        /**
         * 头像上传成功后等多久再拉 `myinfo`。
         * PiliPlus 是 500ms（view.dart:534），这里给 1s：服务端换图不是同步的，
         * 拉早了只是白拉一次（拉不到也不影响上传已成功这个事实）。
         */
        private const val FACE_REFRESH_DELAY_MS = 1_000L
    }
}

/**
 * 头像专用：选中的图片 → 一个可直接上传的本地文件。
 *
 * 为什么复用评论配图那套（[ReplyImageHelper.prepare]）而不是自己写压缩：
 *   它已经处理了国产机相册常见 HEIC 的转码、EXIF 方向摆正、长边 2560 上限、单张 4MB 上限、
 *   落盘到 `cacheDir/reply_image/`（报告 §3.2 把这几条列为"可直接复用"）。
 *
 * 头像额外要守两条规矩（报告 §3.4 第 3/4 条）：
 *   ① **GIF 一律拒绝**：`face/update` 不收 GIF（PiliPlus view.dart:486-489），而
 *      [ReplyImageHelper] 对 GIF 是**原样透传**（ReplyImageHelper.kt:38）—— 不拦就会把 .gif
 *      直接传上去，服务端回什么码未知（报告 §5 明确列为未验证）。
 *   ② **居中裁成 1:1**：B 站头像是圆的，PiliPlus 用 image_cropper 做圆形 1:1 裁剪
 *      （view.dart:491-514）。本仓没有裁剪依赖，这里用 Bitmap 居中裁正方形 ——
 *      "圆"只是显示效果，接口不在乎；这也正是报告 §3.4 第 4 条给的降级方案。
 *
 * 为什么是 private object 而不是新文件：它只服务本页，放在同一个文件里能让
 * "选图 → 落盘 → 上传"三段在一屏内读完（改动只落在本页自己的文件里）。
 */
private object AvatarImageHelper {

    /** 头像最长边。头像显示只有几十 dp，1024 足够；再大只是白传流量 */
    private const val AVATAR_MAX_EDGE = 1024

    private const val GIF_ERROR = "头像不支持 GIF 动图，请换一张 JPG / PNG 图片"

    /**
     * @return 可直接交给上传接口的 jpg 文件（落在 `cacheDir/reply_image/`）
     * @throws IllegalStateException 图片读不了 / 是 GIF（消息直接给用户看）
     */
    suspend fun prepare(context: Context, uri: Uri): File {
        // 先按 MIME + 显示名拦 GIF：ReplyImageHelper 是按"显示名扩展名"决定透传的，
        // 等它落完盘再判断就白解码了一次
        if (looksLikeGif(context, uri)) error(GIF_ERROR)
        val src = ReplyImageHelper.prepare(context, uri)
        if (src.extension.equals("gif", ignoreCase = true)) {
            // MIME 与扩展名不一致时（相册偶尔会这样）会漏到这一步：拿到文件再看一眼，
            // 双重保险，成本只是一次字符串比较
            src.delete()
            error(GIF_ERROR)
        }
        val outDir = src.parentFile ?: File(context.cacheDir, "reply_image")
        val square = runCatching { cropSquare(src, outDir) }.getOrNull() ?: return src
        // 裁剪成功：中间那版压缩文件就没用了，删掉（报告 §3.4 第 5 条：临时文件要清理）
        if (square.absolutePath != src.absolutePath) runCatching { src.delete() }
        return square
    }

    /**
     * 居中裁成 1:1 并落盘成 jpg。返回 null = 裁不了（**调用方退回原图**，
     * 不让"裁剪"这一步成为整个功能的单点故障）。
     *
     * 为什么先算 inSampleSize 再解码：[ReplyImageHelper] 允许长边到 2560，
     * 2560×2560 的 ARGB_8888 光像素就是 26MB，低端机上直接解码会 OOM。
     */
    private fun cropSquare(src: File, outDir: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= AVATAR_MAX_EDGE) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            src.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val side = min(decoded.width, decoded.height)
        if (side <= 0) {
            decoded.recycle()
            return null
        }
        // ⚠️ createBitmap(源, x, y, w, h) 在"取的正好是整张图"时会**返回源对象本身**，
        //    所以下面每一处 recycle 都必须先比对象身份，否则就是 double recycle（直接崩）
        val square = Bitmap.createBitmap(
            decoded,
            (decoded.width - side) / 2,
            (decoded.height - side) / 2,
            side,
            side,
        )
        val scaled = if (side > AVATAR_MAX_EDGE) {
            Bitmap.createScaledBitmap(square, AVATAR_MAX_EDGE, AVATAR_MAX_EDGE, true)
                .also { if (it !== square) square.recycle() }
        } else {
            square
        }
        val target = File(outDir.apply { mkdirs() }, "avatar_${UUID.randomUUID()}.jpg")
        return try {
            FileOutputStream(target).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            if (target.length() > 0L) target else { target.delete(); null }
        } finally {
            scaled.recycle()
            if (decoded !== square && decoded !== scaled) decoded.recycle()
        }
    }

    /** GIF 判定：MIME 或显示名任一命中就算（相册给的 MIME 并不总是可靠） */
    private fun looksLikeGif(context: Context, uri: Uri): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime?.contains("gif", ignoreCase = true) == true) return true
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return name?.endsWith(".gif", ignoreCase = true) == true
    }
}
