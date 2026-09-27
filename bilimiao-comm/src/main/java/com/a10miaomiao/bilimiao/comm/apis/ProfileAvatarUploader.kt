package com.a10miaomiao.bilimiao.comm.apis

import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.entity.MessageInfo
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.CookieStore
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import java.io.File

/**
 * 改头像（第二阶段）的**唯一入口**：先在本地把"web 登录态"这件事查清楚，再决定发不发请求。
 *
 * 为什么单独一个类，而不是把逻辑塞进 ViewModel / [MemberProfileApi]：
 *   · [MemberProfileApi.updateFace] 只负责"请求怎么拼"（纯协议层，一行 Cookie 判断都没有）；
 *   · "有没有 SESSDATA / bili_jct、缺了怎么补、补不上怎么跟用户说"是**策略**，
 *     和 UI 无关、将来网页登录/其它 web 写接口也要用同一套话术，放这里两边都能复用；
 *   · 这层可以脱离 Compose 单独看，方便以后真机抓日志排查。
 *
 * ★★ P0-1（本文件存在的全部理由）：
 *   我们登录走的是 **TV 扫码**（AuthApi 的 `x/passport-tv-login/qrcode/…` 系列接口），拿到的是
 *   **APP 登录态**（access_token），而 `face/update` 是 **web 接口**，只认 Cookie 里的
 *   SESSDATA + csrf(bili_jct)。两者不是一个东西 —— 有 access_token 不代表能改头像。
 *   调研报告 §4.7 把"TV 扫码登录后 CookieManager 里到底有没有 SESSDATA/bili_jct"
 *   列为**整个功能最大的未知数**，所以这里的策略是：
 *     ① 先双探测（SESSDATA + bili_jct）；
 *     ② 缺了先尝试本地兜底（CookieStore 同步 + 重灌登录时下发的 cookie_info）；
 *     ③ 仍然缺 —— **明确告诉用户缺什么、去哪儿补**，绝不静默失败，也绝不假装成功。
 *
 * ★ 为什么这里**没有任何自动重试**（与评论配图那条链路的关键区别）：
 *   本仓发图评论是"3 次退避重试"（ReplyEditDialog.uploadImage），但改资料是**写操作**，
 *   报告 §4.6 第一条就是"写操作绝不能自动重试"（改名扣硬币、写操作重发可能触发风控）。
 *   所以：一次请求，失败把原因原样返回给页面，由用户手动点「重试」。
 *   （`face/update` 本身是幂等的，手动重试没有额外代价，见报告 §8.3。）
 */
object ProfileAvatarUploader {

    /**
     * 一次"web 登录态体检"的结果。
     *
     * @param hasSessData Cookie 里有没有 SESSDATA（web 登录凭据）
     * @param hasCsrf     Cookie 里有没有 bili_jct（web 写操作的 csrf）
     * @param triedRestore 是否已经尝试过本地兜底（页面上要如实告诉用户"补过了还是不行"）
     * @param restored    兜底之后是否真的补齐了
     */
    data class WebLoginState(
        val hasSessData: Boolean,
        val hasCsrf: Boolean,
        val triedRestore: Boolean = false,
        val restored: Boolean = false,
    ) {
        /** 两个都齐了才敢发 `face/update` */
        val ready: Boolean get() = hasSessData && hasCsrf

        /** 缺哪几个 cookie（给用户看的名字，不用内部缩写） */
        val missing: List<String>
            get() = buildList {
                if (!hasSessData) add("SESSDATA")
                if (!hasCsrf) add("bili_jct")
            }

        /**
         * 给用户看的话术。要点：**说清缺什么 + 说清怎么补 + 不承诺能补上**。
         * 用户是 TV 扫码登录的，很可能从来没在这个 App 里登过网页版，所以必须给出动作。
         */
        fun userHint(): String = buildString {
            append("改头像用的是 B 站网页接口，需要网页登录态（")
            append(missing.joinToString(" + "))
            append("），当前 App 里没有。")
            if (triedRestore) {
                append("已尝试从本地 Cookie 仓和登录时下发的 Cookie 恢复，仍未拿到。")
            }
            append("请用「网页登录」登录一次，再回来改头像。")
        }
    }

    /**
     * 一次头像上传的结果。
     *
     * ★ [code] 在"没发请求"（缺登录态 / 网络异常 / 返回体解析不了）时是 **null** —— 不编造返回码。
     *   报告 §5 明确列了"成功返回体未验证"，真机跑通前这里不写任何猜测值。
     */
    data class FaceUploadResult(
        val success: Boolean,
        val code: Int?,
        /** 用户可见文案：成功是"修改成功"，失败是服务端原文（可能带一句本地补充说明） */
        val message: String,
        val loginState: WebLoginState,
    )

    /**
     * 双探测：SESSDATA + bili_jct。
     *
     * @param allowRestore 是否允许"缺了就补"。
     *   页面上**提前**体检时传 false（只读、无副作用）；真正要上传时传 true（才去动 Cookie）。
     *   —— 这个区分很重要：兜底会往 CookieManager 里写 cookie，不该在用户只是打开页面时就偷偷做。
     *
     * 两个探测器都是**现成的**：
     *   · SESSDATA：`CommentApi.hasWebLoginCookie()`（`MiaoHttp.sessDataToken() != null`）。
     *     它写在 CommentApi 里只是因为当年为"带图评论"写的；判据本身是通用的。
     *   · csrf：`MiaoHttp.csrfToken()`（cookie `bili_jct`）。
     */
    fun probeWebLogin(allowRestore: Boolean = false): WebLoginState {
        val first = readState()
        if (first.ready || !allowRestore) return first
        restoreWebCookies()
        val second = readState()
        return second.copy(triedRestore = true, restored = second.ready)
    }

    /** 只读地读一次 CookieManager。任何异常都当"没有"处理（探测本身不能把页面搞崩） */
    private fun readState(): WebLoginState = WebLoginState(
        hasSessData = runCatching { BiliApiService.commentApi.hasWebLoginCookie() }.getOrDefault(false),
        hasCsrf = !runCatching { MiaoHttp.csrfToken() }.getOrNull().isNullOrBlank(),
    )

    /**
     * 本地兜底：把能找回的 web cookie 都试一遍。
     *
     * 两条路，都**不能保证**成功，所以调用方必须重新探测再说结论：
     *
     * ① `CookieStore.importFromWebView() + syncToWebView()`
     *    —— 报告 §4.6 指定的现成做法（ReplyEditDialog 就是这么写的）。
     *    ⚠️ 必须说清楚它其实**补不回身份 cookie**：`CookieStore.syncToWebView()` 只回写
     *    buvid3 / buvid4 / b_nut / bili_ticket 这四个**指纹** cookie，SESSDATA / bili_jct /
     *    DedeUserID 这类**凭据是刻意不回写的**（CookieStore.kt:125-128 有明确注释：
     *    登出后回写会让"游客模式"变回已登录）。所以它对 -352 风控有意义，对登录态没意义。
     *    仍然保留这一步：① 是用户/报告明确要求的兜底动作，② 顺带把指纹补齐，
     *    真机排查时"指纹有了、凭据还是没有"本身就是一个有用的观测结果。
     *
     * ② 重灌 `loginInfo.cookie_info`
     *    —— 这才是**唯一可能真正补回 SESSDATA/bili_jct** 的路径：TV 扫码登录时服务端
     *    会在 cookie_info 里下发一批 cookie（`LoginInfo.CookieInfo`），
     *    `BilimiaoCommApp.readAuthInfo()` 冷启动时就是靠 `setCookie()` 把它们灌回
     *    CookieManager 的。App 运行期间 CookieManager 可能被清（WebView 清数据 / 登出残留），
     *    这里重放一次同一个动作，语义与冷启动完全一致，不引入任何新的凭据来源。
     *    ⚠️ 前提是"登录时服务端**确实**下发了 SESSDATA" —— 这一条正是报告 §5.7 的未知数，
     *    没登录态无法验证；如果 cookie_info 里本来就没有，这一步自然也是白做（不会报错）。
     */
    private fun restoreWebCookies() {
        runCatching {
            val store = CookieStore.getInstance(BilimiaoCommApp.commApp.app)
            store.importFromWebView()
            store.syncToWebView()
        }
        runCatching {
            BilimiaoCommApp.commApp.loginInfo?.cookie_info?.let { cookieInfo ->
                BilimiaoCommApp.commApp.setCookie(cookieInfo)
            }
        }
    }

    /**
     * 上传头像。**调用方必须保证同一时刻只有一次在飞**（单飞/冷却在 ViewModel 里做，
     * 因为这层不该管 UI 状态；本类自身不做任何重试、不做任何排队）。
     *
     * 顺序（每一步失败都有各自的、用户看得懂的文案）：
     *   ① 探测 + 兜底 → 仍缺就**不发请求**，直接把"缺什么、怎么办"返回；
     *   ② GIF 再拦一次（防御性：`face/update` 不收 GIF，PiliPlus view.dart:486-489）；
     *   ③ 发请求（[MemberProfileApi.updateFace]），把 HTTP 码/服务端原文拼进失败原因；
     *   ④ 只有 `code == 0` 算成功。
     */
    suspend fun uploadFace(file: File): FaceUploadResult {
        val state = probeWebLogin(allowRestore = true)
        if (!state.ready) {
            // ★ 这一步就是"不要静默失败"：宁可一个请求都不发，也不让用户看到一个必然的 -101
            return FaceUploadResult(
                success = false,
                code = null,
                message = state.userHint(),
                loginState = state,
            )
        }
        if (file.extension.equals("gif", ignoreCase = true)) {
            return FaceUploadResult(
                success = false,
                code = null,
                message = "头像不支持 GIF 动图，请换一张 JPG / PNG 图片",
                loginState = state,
            )
        }
        if (!file.exists() || file.length() <= 0L) {
            return FaceUploadResult(
                success = false,
                code = null,
                message = "图片文件不存在或为空，请重新选择",
                loginState = state,
            )
        }
        // csrf 在"探测通过"之后取一次就固定下来：请求必须和探测看到的是**同一批** cookie。
        // 中途若发生重新登录，宁可让服务端回 -101，也好过发出"登录态是新的、csrf 是旧的"的组合
        // （那种请求的失败原因最难查）。取不到就传空串 —— updateFace 会把 csrf 字段整个省掉。
        val csrf = runCatching { MiaoHttp.csrfToken() }.getOrNull().orEmpty()
        return try {
            val response = BiliApiService.memberProfileApi.updateFace(file, csrf).awaitCall()
            val bodyStr = response.body?.string().orEmpty()
            val parsed = runCatching { MiaoJson.fromJson<MessageInfo>(bodyStr) }.getOrNull()
            if (parsed == null) {
                // 解析不了通常意味着打到了 404 页 / 网关错误页：把 HTTP 码和正文头一段给出来，
                // 比一句"上传失败"有用得多（ReplyEditDialog.attemptUpload 也是这个思路）
                FaceUploadResult(
                    success = false,
                    code = null,
                    message = "服务端返回无法解析（HTTP ${response.code}）：${bodyStr.take(200)}",
                    loginState = state,
                )
            } else if (parsed.code == 0) {
                FaceUploadResult(true, 0, "修改成功", state)
            } else {
                FaceUploadResult(false, parsed.code, decorate(parsed.code, parsed.message), state)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 网络异常 ≠ 服务端没改：结果未知，所以文案里必须写出来（和第一阶段同款口径）
            FaceUploadResult(
                success = false,
                code = null,
                message = "网络异常，提交结果未知：${e.message ?: e.toString()}",
                loginState = state,
            )
        }
    }

    /**
     * 服务端 message 保持**原文**（报告 §2.2：非 0 一律原文弹给用户），
     * 只对**有实测依据**的 -101 追加一句可操作的补充。
     *
     * ★ 为什么只补 -101：它是"账号未登录"，报告 §2.4 第 6 条 curl 实测过
     *   （`face/update` 带不带 csrf 都回 -101，说明登录检查先于 csrf 校验）。
     *   其它码（包括改名的硬币不足）没有实测结论，**不猜、不翻译**。
     */
    private fun decorate(code: Int, message: String): String = when (code) {
        -101 -> "$message（网页登录态失效或不被服务端接受，请用「网页登录」重新登录一次）"
        else -> message
    }
}
