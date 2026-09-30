package cn.a10miaomiao.bilimiao.compose.pages.auth

import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 从「粘贴内容」里解析出来的登录凭据。
 *
 * 三种粘贴形态都归一到这一份数据上（见 [AuthPasteParser.parse]）：
 * ① 裸 `access_token`；② Cookie 头文本；③ 身份导出文件内容（JSON）。
 */
data class PastedAuth(
    val accessToken: String = "",
    val refreshToken: String = "",
    val cookie: String = "",
) {
    val isEmpty: Boolean get() = accessToken.isBlank() && cookie.isBlank()
    val hasToken: Boolean get() = accessToken.isNotBlank()
    val hasCookie: Boolean get() = cookie.isNotBlank()
}

/** 能证明"这是登录凭据"的 Cookie 名（只有 buvid3 这类指纹不算） */
private val IDENTITY_COOKIE_NAMES = listOf("SESSDATA", "bili_jct", "DedeUserID")

/** Cookie 名的合法形态（B 站这些名字都是字母数字下划线点横线；不匹配的段一律丢掉） */
private val COOKIE_NAME = Regex("^[A-Za-z0-9_.\\-]+$")

/**
 * Token/Cookie 登录输入框的解析器（纯函数，不碰 Android）。
 *
 * 支持的 JSON 形态：
 * · 本 App「身份导出」/ `.bili_login.json` 的扁平形态：
 *   `{"cookie":"SESSDATA=…; bili_jct=…","access_token":"…","refresh_token":"…","mid":"123", …}`
 * · `LoginInfo` 的序列化形态：`{"token_info":{"access_token":…},"cookie_info":{"cookies":[{"name":…,"value":…}]}}`
 *
 * 容错要求：多余字段、`null`、数字/字符串混用、缺字段都不许抛异常 —— 取不到就返回 null，
 * 由调用方给人话提示。
 */
object AuthPasteParser {

    /** `trim()` 不剥这些字符（BOM / 零宽），必须显式处理 */
    private val INVISIBLE_CHARS = charArrayOf('\uFEFF', '\u200B', '\u200C', '\u200D')

    /** 解析粘贴内容；返回 null = 三种形态都识别不出 */
    fun parse(raw: String): PastedAuth? {
        val text = raw.stripInvisible()
        if (text.isEmpty()) return null
        if (text.startsWith("{")) {
            val root = runCatching {
                MiaoJson.kotlinJson.parseToJsonElement(text) as? JsonObject
            }.getOrNull() ?: return null
            return fromJson(root).takeIf { !it.isEmpty }
        }
        // Cookie 分支：必须**真的含身份 Cookie**（SESSDATA / bili_jct / DedeUserID）才认。
        // 光有 `;` 和 `=` 不算 —— 否则带 BOM 的导出 JSON、DevTools 整段请求头都会被当成 Cookie，
        // 用户拿着有效文件却看到「Cookie 无效或已过期」。
        if (text.contains(";") || text.contains("SESSDATA", ignoreCase = true)) {
            val cookie = normalizeCookie(text)
            return PastedAuth(cookie = cookie).takeIf { hasIdentityCookie(it.cookie) }
        }
        // 其余按裸 access_token 处理
        return PastedAuth(accessToken = text).takeIf { it.hasToken }
    }

    private fun fromJson(root: JsonObject): PastedAuth {
        val tokenInfo = root["token_info"] as? JsonObject
        val cookieInfo = root["cookie_info"] as? JsonObject
        val cookie = root.string("cookie")
            ?: cookieInfo?.cookiesText()
            ?: ""
        return PastedAuth(
            accessToken = root.string("access_token")
                ?: tokenInfo?.string("access_token")
                ?: "",
            refreshToken = root.string("refresh_token")
                ?: tokenInfo?.string("refresh_token")
                ?: "",
            // JSON 里的 cookie 也按同一把尺子：只有指纹 Cookie 时当没给
            cookie = cookie.takeIf { hasIdentityCookie(it) } ?: "",
        )
    }

    /** 数字/字符串都当字符串取；null、空串一律返回 null */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.cookiesText(): String? {
        val arr = this["cookies"] as? JsonArray ?: return null
        val pairs = arr.mapNotNull { element ->
            val cookie = element as? JsonObject ?: return@mapNotNull null
            val name = cookie.string("name") ?: return@mapNotNull null
            val value = cookie.string("value") ?: return@mapNotNull null
            "$name=$value"
        }
        return pairs.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /**
     * `SESSDATA=x; bili_jct=y` → 规范化成 `k=v; k=v`。
     *
     * 丢掉三类段：空段、没有 `=` 的段、以及**名字不像 Cookie 名**的段 —— 后者专门挡住
     * "整段粘贴 DevTools 请求头"（那种输入的第一个段名会带换行/冒号/空格，
     * 一旦原样塞进 Cookie 头就是畸形请求）。
     */
    fun normalizeCookie(raw: String): String {
        val pairs = raw.split(";").mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val name = part.substring(0, index).trim()
            val value = part.substring(index + 1).trim()
            if (name.isEmpty() || value.isEmpty() || !COOKIE_NAME.matches(name)) {
                null
            } else {
                "$name=$value"
            }
        }
        return pairs.joinToString("; ")
    }

    /** 是否含身份 Cookie（按规范化后的 `k=v` 段判） */
    private fun hasIdentityCookie(cookie: String): Boolean =
        cookie.split(";").any { part ->
            val name = part.substringBefore('=').trim()
            IDENTITY_COOKIE_NAMES.any { it.equals(name, ignoreCase = true) }
        }

    /**
     * 剥掉首尾的 BOM / 零宽字符再 trim。
     *
     * 为什么必须显式剥：Kotlin 的 `trim()` 按 `Char.isWhitespace` 判，**U+FEFF 不是空白**
     * ⇒ Windows 记事本存过的导出 JSON、部分分享链路带过来的文本会以 BOM 开头，
     * `startsWith("{")` 直接判否，整段 JSON 掉进 Cookie 分支。
     */
    private fun String.stripInvisible(): String =
        trim().trimStart(*INVISIBLE_CHARS).trimEnd(*INVISIBLE_CHARS).trim()
}
