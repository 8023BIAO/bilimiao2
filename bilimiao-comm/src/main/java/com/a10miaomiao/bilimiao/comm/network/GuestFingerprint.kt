package com.a10miaomiao.bilimiao.comm.network

import android.webkit.CookieManager
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger

/**
 * 游客模式（未登录）下的**匿名设备指纹**。
 *
 * 为什么需要：
 *  ① 未登录时若一个 buvid3 都没有，B站 的 Web 接口会直接返回 `-352 风控校验失败`
 *     —— 这就是游客模式下"什么都刷不出来"的原因；
 *  ② 但也不能用**登录时期留下的那套**指纹（buvid3 会跟账号关联），否则"游客"在服务端眼里
 *     还是同一个人 —— 用户想要的是"原来的 cookie、身份验证一点都不带"。
 *
 * 做法：未登录时向公开接口 `x/frontend/finger/spi` 现要一对**全新的** buvid3/buvid4 写进
 * WebView CookieManager（每天最多一次），全程不带任何登录凭据。
 */
object GuestFingerprint {

    private const val SP = "bilimiao_guest_fingerprint"
    private const val KEY_DAY = "day"

    /** 登录状态下什么都不做；游客状态每天补一次匿名指纹 */
    suspend fun ensureAnonymousOncePerDay() {
        if (BilimiaoCommApp.commApp.loginInfo != null) return   // 已登录：用他自己那套
        val ctx = BilimiaoCommApp.commApp.app
        val today = (System.currentTimeMillis() / 86_400_000L).toInt()
        val sp = ctx.getSharedPreferences(SP, android.content.Context.MODE_PRIVATE)
        if (sp.getInt(KEY_DAY, -1) == today && hasBuvid3()) return

        runCatching {
            val res = MiaoHttp.request {
                url = BiliApiService.biliApi("x/frontend/finger/spi")
                isWebApi = true
                // spi 本身也要匿名：显式游客模式（不带任何 Cookie、不带账号头）
                asGuest = true
            }.awaitCall().json<ResponseData<Map<String, String>>>()
            val b3 = res.data?.get("b_3")?.takeIf { it.isNotBlank() } ?: return@runCatching
            val b4 = res.data?.get("b_4")?.takeIf { it.isNotBlank() }
            val cm = CookieManager.getInstance()
            cm.setCookie(".bilibili.com", "buvid3=$b3")
            if (b4 != null) cm.setCookie(".bilibili.com", "buvid4=$b4")
            cm.setCookie(".bilibili.com", "b_nut=${System.currentTimeMillis() / 1000}")
            cm.flush()
            sp.edit().putInt(KEY_DAY, today).apply()
            miaoLogger().i("GuestFingerprint", "游客匿名指纹已更新：buvid3=${b3.take(8)}…")
        }.onFailure {
            miaoLogger().e("GuestFingerprint", "取匿名指纹失败：${it.message}")
        }
    }

    private fun hasBuvid3(): Boolean = runCatching {
        (CookieManager.getInstance().getCookie("https://api.bilibili.com") ?: "")
            .contains("buvid3=")
    }.getOrDefault(false)
}
