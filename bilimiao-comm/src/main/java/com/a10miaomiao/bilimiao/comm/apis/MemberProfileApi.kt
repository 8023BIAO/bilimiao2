package com.a10miaomiao.bilimiao.comm.apis

import com.a10miaomiao.bilimiao.comm.network.ApiHelper
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * 账号资料（"编辑资料"页）——读资料 + 改单个字段
 *
 * 依据：调研报告 `/root/test/用户资料编辑-调研与方案.md`
 *   · 接口契约表 §2.1（R1 / W1 / W2）
 *   · PiliPlus `lib/pages/member_profile/view.dart:72-114`（读）、`:344-415`（改）
 *
 * ★ 签名方式：**零新增签名代码**。
 *   PiliPlus 的 `AppSign.appSign()`（`lib/utils/app_sign.dart:8-31`）与我们的
 *   [ApiHelper.createParams]（ApiHelper.kt:144-179）是同一对 HD appkey/secret
 *   （`dfca71928277209b` / `b5475a8825547a4fc26c7d518eaaa02e`）+ 同一个算法
 *   （MD5(按 key 排序的 urlencode 串 + secret)），并且都会注入 access_key（报告 §1.5）。
 *
 * ★ 为什么两个接口一个用 [BiliApiService.biliApp]（参数进 query）、一个用手拼 URL（参数进 formBody）：
 *   `myinfo` 是 GET，PiliPlus 也把参数放 query；改字段那三条 PiliPlus 明确是
 *   `Headers.formUrlEncodedContentType`（form body）。按原样对齐，少一个变量。
 *   顺带一个好处：`api.bilibili.com` **URL 不带 query** 时 [MiaoHttp] 不会触发 WBI 签名
 *   （MiaoHttp.kt:80-95 + WbiSigner.kt:141-150），写操作就少一个失败点。
 *
 * ⚠️ 第一阶段没做、**第二阶段已落地**的（本文件只做加法，原有接口一字未改）：
 *   · 改头像 `POST /x/member/web/face/update`：**纯 web 通道**，要 SESSDATA + bili_jct。
 *     请求本体在下面的 [updateFace]，"先探测 web 登录态、缺了就兜底、仍失败给用户看原因"
 *     这套闸门在同包的 [ProfileAvatarUploader]（TV 扫码登录很可能没有 web 登录态 → P0-1）。
 *   · 改性别 `sex/update`、改生日 `birthday/update` 仍是同一个 [updateField] 通道，未接 UI。
 */
class MemberProfileApi {

    /**
     * 读账号资料：昵称 / 签名 / 硬币 / 生日 / 头像 / 性别。
     *
     * 不需要额外参数：`biliApp` 内部走 [ApiHelper.createParams]，会自动补齐
     * build / c_locale / channel / mobi_app / platform / s_locale / statistics / ts
     * 以及登录态的 access_key + mid，并算好 sign（ApiHelper.kt:135-179）。
     */
    fun myinfo() = MiaoHttp.request {
        url = BiliApiService.biliApp("x/v2/account/myinfo")
    }

    /**
     * 改**一个**字段。第一阶段只用 `uname`（昵称）和 `sign`（个性签名）。
     *
     * @param name 路径里的字段名：`uname` / `sign`（后续 `sex` / `birthday` 同一通道）
     * @param value 新值。纯字符串，不做二次加工
     *
     * 返回体只有 code / message 有意义（PiliPlus 也只看这两个），用
     * [com.a10miaomiao.bilimiao.comm.entity.MessageInfo] 接即可，见 EditProfileViewModel。
     *
     * ★★ 最重要的一条：**签名的表单字段名是 `user_sign`，不是 `sign`**。
     *    路径是 `x/member/app/sign/update`，body 里的字段却是 `user_sign`
     *    （PiliPlus view.dart:364-365）。写成 `sign` 服务端只会回参数错误，非常难查。
     *
     * ★ 为什么要传 access_key：这条通道是 APP 签名通道，access_key 由 createParams 自动注入；
     *   没有登录态时服务端回 -101 账号未登录（实测，报告 §2.4）。
     *
     * ⚠️ 已知不确定项（报告 §5.9）：createParams 会**多带一个 `mid`**（自己），而 PiliPlus 不带。
     *    我们所有 APP 写接口都这么发（收藏夹增删改等），先保持一致；若真机实测报参数错误，
     *    第一件事就是去掉这个 mid（报告 §4.4 的补充说明）。
     */
    fun updateField(name: String, value: String) = MiaoHttp.request {
        // 路径用 name（sign），表单字段名按 name 映射（sign → user_sign）
        val formField = if (name == FIELD_SIGN) PARAM_SIGN else name
        url = "$API_BASE/x/member/app/$name/update"
        method = MiaoHttp.POST
        formBody = ApiHelper.createParams(
            formField to value,
        )
    }

    /**
     * 改头像：**纯 WEB 通道**，multipart 直接把裁剪后的图片文件传上去。
     *
     * 依据：调研报告 §1.6 / §2.1 W5 + PiliPlus `lib/pages/member_profile/view.dart:519-530`。
     * 契约（逐字对齐 PiliPlus，不多传任何一个字段）：
     *   · POST `https://api.bilibili.com/x/member/web/face/update`
     *   · multipart/form-data：`dopost=save`、`DisplayRank=10000`、`face=<文件>`
     *   · 鉴权：Cookie（SESSDATA）+ csrf（cookie bili_jct）。**不需要 APP 签名，也没有 access_key**
     *   · **没有**"先申请上传 → 拿 URL → 再提交字段"这三步，服务端自己落到 BFS 图床
     *
     * ★★ `isWebApi = true` 一个都不能少：
     *    不设它，[MiaoHttp] 会给请求加 `app-key: android_hd` + `Authorization: identify_v1 ...`，
     *    服务端就可能按 APP 通道解析这条 web 接口 —— 本仓已经有同款事故
     *    （带图评论被回 `12088 不支持发送图片`，CommentApi.addWithPictures 的注释）。
     *
     * ★ 为什么 csrf 放 **body** 而不是像 PiliPlus 那样放 query：
     *    URL 里没有 `?` ⇒ 完全不进 WBI 分支（MiaoHttp 现在只签直播白名单，这里是双重保险），
     *    少一个"为了改头像去拉 nav 取 mixin key"的失败点（报告 §2.3 第 2 条明确建议这么选）。
     *
     * ★ 为什么 mime 按扩展名写死：合约里只可能出现 jpg/png（GIF 在选图层
     *    `EditProfileViewModel.AvatarImageHelper` 与 [ProfileAvatarUploader] 各拦一次），
     *    写死比再查一次 ContentResolver 稳。
     *
     * ⚠️ 未验证：成功返回体（无登录态，成功率 0% 的路径一条都没跑过，报告 §5.1）。
     *    返回码语义按报告 §2.2：只有 `code == 0` 是成功，其它一律把 `message` 原文给用户。
     */
    fun updateFace(file: File, csrf: String) = MiaoHttp.request {
        url = FACE_UPDATE_URL
        method = MiaoHttp.POST
        // 纯 web：只带 Cookie，不带 app-key / Authorization（见上面 ★★）
        isWebApi = true
        body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("dopost", "save")
            .addFormDataPart("DisplayRank", "10000")
            .addFormDataPart("face", file.name, file.asRequestBody(faceMime(file).toMediaType()))
            // csrf 为空时干脆不带：让服务端按"缺参数"报错，比带一个空串更好定位
            .apply { if (csrf.isNotBlank()) addFormDataPart("csrf", csrf) }
            .build()
    }

    companion object {
        private const val API_BASE = "https://api.bilibili.com"

        /** 改头像的 web 接口。**不带 query**（csrf 走 body），见 [updateFace] 的注释 */
        const val FACE_UPDATE_URL = "$API_BASE/x/member/web/face/update"

        /**
         * 头像在"提交状态机"里的标识（**不是**接口参数名）。
         *
         * 为什么要有它：头像上传和昵称/签名提交共用同一把单飞锁与同一个
         * `submittingField` 状态（EditProfileViewModel/EditProfilePage），
         * 页面靠它决定"哪一行显示转圈"。
         */
        const val FIELD_FACE = "face"

        /** 昵称：路径与表单字段名都是 `uname`（PiliPlus view.dart:363-364） */
        const val FIELD_UNAME = "uname"

        /** 个性签名：**路径**是 `sign`，表单字段名见 [PARAM_SIGN]（PiliPlus view.dart:364-365） */
        const val FIELD_SIGN = "sign"

        /** 个性签名真正的表单字段名。改名/改签名最容易踩的就是这个（报告 §1.4 要点） */
        private const val PARAM_SIGN = "user_sign"

        /** 头像文件的 Content-Type。GIF 不可能走到这里（上游已拦），只区分 png / jpg */
        private fun faceMime(file: File): String =
            if (file.extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg"
    }
}
