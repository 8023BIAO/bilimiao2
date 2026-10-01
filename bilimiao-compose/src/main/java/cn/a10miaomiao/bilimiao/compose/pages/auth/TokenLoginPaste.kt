package cn.a10miaomiao.bilimiao.compose.pages.auth

import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 粘贴内容被识别成了什么（提示语必须说实话，别让用户以为"它自己拿去登录了"） */
enum class PastedKind {
    /** 裸 `access_token` */
    TOKEN,

    /** Cookie 文本 */
    COOKIE,

    /** JSON 里 token 与 Cookie 都有（导出文件常见） */
    TOKEN_COOKIE,

    /** 只粘了 SESSDATA 的值本身（DevTools 里双击复制到的那一串） */
    SESSDATA_VALUE,

    /** 没认出来 */
    NONE,
}

/**
 * 从「粘贴内容」里解析出来的登录凭据。
 *
 * 五种粘贴形态都归一到这一份数据上（见 [AuthPasteParser.parse]）：
 * ① 裸 `access_token`；② Cookie 头文本；③ 身份导出文件（扁平 JSON / `LoginInfo` JSON）；
 * ④ 只粘 SESSDATA 的值；⑤ 认不出（返回 null）。
 */
data class PastedAuth(
    val accessToken: String = "",
    val refreshToken: String = "",
    val cookie: String = "",
    val kind: PastedKind = PastedKind.NONE,
) {
    val isEmpty: Boolean get() = accessToken.isBlank() && cookie.isBlank()
    val hasToken: Boolean get() = accessToken.isNotBlank()
    val hasCookie: Boolean get() = cookie.isNotBlank()
}

/** 能证明"这是登录凭据"的 Cookie 名（只有 buvid3 这类指纹不算） */
private val IDENTITY_COOKIE_NAMES = listOf("SESSDATA", "bili_jct", "DedeUserID")

/**
 * SESSDATA **值**的特征：`8位十六进制%2C时间戳%2C…`（F12 里双击复制到的就是这一串）。
 * ★刻意做成"窄而安全"的规则：只认这个前缀 + 长度≥40，绝不做"长串就当 cookie"的宽泛启发式
 * （那会把普通 token / 随机串吃掉）。
 */
private val SESSDATA_VALUE_PREFIX = Regex("^[0-9a-fA-F]{8}%2C\\d{9,}%2C")

/** 请求头里的 `Cookie:` 标签（**排除** `Set-Cookie:`）：整段 dump 时优先从它之后取值 */
private val REQUEST_COOKIE_LABEL = Regex("(?i)(?<!set-)cookie\\s*:")

private const val SESSDATA_VALUE_MIN_LEN = 40

/**
 * 裸 token 的字符集（JWT / access_token 都是这些字符）。
 * 含空白、中文、全角标点的"一段话"不是 token —— 那种情况宁可说"没认出登录信息"，
 * 也不要把一段话当 token 发出去换来一句"Token 无效"。
 */
private val TOKEN_CHARS = Regex("^[A-Za-z0-9_.~+/=:\\-]+$")

/**
 * Token/Cookie 登录输入框的解析器（纯函数，不碰 Android；有单测 [AuthPasteParserTest]）。
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

    /** 解析粘贴内容；返回 null = 认不出（调用方提示"没认出登录信息"） */
    fun parse(raw: String): PastedAuth? {
        val text = raw.stripInvisible()
        if (text.isEmpty()) return null

        // ① JSON（扁平导出 / LoginInfo 形态）
        if (text.startsWith("{")) {
            val root = runCatching {
                MiaoJson.kotlinJson.parseToJsonElement(text) as? JsonObject
            }.getOrNull() ?: return null
            return fromJson(root).takeIf { !it.isEmpty }
        }

        // ② Cookie 文本 —— 含"整行/整段请求头"（F12 里 Copy、Copy as cURL）。
        //    ★不靠位置、也不靠"名字像不像 cookie 名"判：直接在整段里找**已知身份 cookie 名**
        //      （SESSDATA / bili_jct / DedeUserID）。因为真实 Cookie 头里 SESSDATA 几乎总是第一段，
        //      按位置/名字形状去筛会把第一段（`Cookie: SESSDATA=…`）整条丢掉，
        //      只剩 bili_jct 去请求 nav ⇒ 用户看到「Cookie 无效或已过期」——这是用户实测过的形态。
        extractIdentityCookies(text)?.let { cookie ->
            return PastedAuth(cookie = cookie, kind = PastedKind.COOKIE)
        }

        // ③ 只粘了 SESSDATA 的值（窄特征，见 [SESSDATA_VALUE_PREFIX]）
        if (looksLikeSessDataValue(text)) {
            return PastedAuth(cookie = "SESSDATA=$text", kind = PastedKind.SESSDATA_VALUE)
        }

        // ④ 裸 access_token：必须是 token 字符集（一段话 / 整段请求头都不是 token，宁可说"没认出"）
        if (text.any { it.isWhitespace() } || !TOKEN_CHARS.matches(text)) return null
        return PastedAuth(accessToken = text, kind = PastedKind.TOKEN).takeIf { it.hasToken }
    }

    /** 给用户看的识别结论（弹窗实时提示 + 结果 toast 共用同一句话，保证口径一致） */
    fun describe(pasted: PastedAuth?): String = when {
        pasted == null || pasted.isEmpty -> "没认出登录信息"
        pasted.kind == PastedKind.SESSDATA_VALUE -> "识别为 SESSDATA，按 Cookie 登录"
        pasted.kind == PastedKind.TOKEN_COOKIE -> "识别为 Token + Cookie，按 Token 登录"
        pasted.kind == PastedKind.COOKIE -> "识别为 Cookie，按 Cookie 登录"
        else -> "识别为 Token，按 Token 登录"
    }

    private fun fromJson(root: JsonObject): PastedAuth {
        val tokenInfo = root["token_info"] as? JsonObject
        val cookieInfo = root["cookie_info"] as? JsonObject
        val accessToken = root.string("access_token")
            ?: tokenInfo?.string("access_token")
            ?: ""
        val refreshToken = root.string("refresh_token")
            ?: tokenInfo?.string("refresh_token")
            ?: ""
        val cookie = (root.string("cookie") ?: cookieInfo?.cookiesText() ?: "")
            // JSON 里的 cookie 也按同一把尺子：只有指纹 Cookie 时当没给
            .takeIf { hasIdentityCookie(it) }
            ?: ""
        val kind = when {
            accessToken.isNotBlank() && cookie.isNotBlank() -> PastedKind.TOKEN_COOKIE
            accessToken.isNotBlank() -> PastedKind.TOKEN
            cookie.isNotBlank() -> PastedKind.COOKIE
            else -> PastedKind.NONE
        }
        return PastedAuth(
            accessToken = accessToken,
            refreshToken = refreshToken,
            cookie = cookie,
            kind = kind,
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
     * 在整段文本里提取**已知身份 cookie**，输出规范化的 `k=v; k=v`；一个都没找到就 null。
     *
     * 覆盖这些粘贴形态（都不依赖位置）：
     * · `SESSDATA=v; bili_jct=t`（纯 cookie 文本）
     * · `Cookie: SESSDATA=v; bili_jct=t`（F12 复制的一整行）
     * · `-H 'cookie: SESSDATA=v; bili_jct=t'`（Copy as cURL 片段）
     * · `"SESSDATA=v"` / `'SESSDATA=v'`（带首尾引号）
     * · 含 `Host:` / `User-Agent:` 等杂行的**整段请求头**
     *
     * 只保留三个身份 cookie（SESSDATA / bili_jct / DedeUserID）：登录只要它们，
     * 其余（buvid3/b_nut/bili_ticket 这类）由 App 自己的指纹链路负责，粘进来也没用。
     */
    private fun extractIdentityCookies(raw: String): String? {
        val text = raw.stripInvisible()
        // 整段 dump 可能同时含响应的 `set-cookie:` 与请求的 `Cookie:`：
        // 有请求标签时**只从它之后找**，免得挑到响应里那条（比如 `set-cookie: SESSDATA=deleted`）。
        val scope = REQUEST_COOKIE_LABEL.find(text)
            ?.let { text.substring(it.range.last + 1) }
            ?: text
        val pairs = IDENTITY_COOKIE_NAMES.mapNotNull { name ->
            identityValueRegex(name).find(scope)
                ?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { "$name=$it" }
        }
        return pairs.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /**
     * `<名字>=<值>`（名字大小写不敏感）。四种窄口径容错，都是有实测形态的：
     * · 名字前允许：行首 / `;` / `:`（`cookie:SESSDATA=` 冒号后没空格）/ 空白 / 引号；
     * · 名字与 `=` 之间、`=` 与值之间允许空白（`SESSDATA = v`）；
     * · 值的终止符除 `;`/空白/引号外，还包含**全角分号 `；`**、**全角空格 U+3000**、**NBSP U+00A0**
     *   （`\s` 在 Java 正则里不含这两个 Unicode 空白，从聊天软件/网页复制过来很常见）；
     * · 值里同时排除 `'` 和 `"`：`-H 'cookie: SESSDATA=v; bili_jct=t'` 是单引号包的，
     *   只排双引号会把结尾那个 `'` 吞进值里（`bili_jct=t'`）。
     */
    private fun identityValueRegex(name: String): Regex =
        Regex(
            "(?i)(?:^|[;:；\\s\\u00A0\\u3000'\"])" + Regex.escape(name) +
                "\\s*=\\s*\"?([^;；\\s\\u00A0\\u3000'\"]+)\"?"
        )

    /** 干净的 `k=v; k=v` 串里是否含身份 cookie（**JSON 那条路**用；文本粘贴走 [extractIdentityCookies]） */
    private fun hasIdentityCookie(cookie: String): Boolean =
        cookie.split(";").any { part ->
            val name = part.substringBefore('=').trim()
            IDENTITY_COOKIE_NAMES.any { it.equals(name, ignoreCase = true) }
        }

    /** 只粘 SESSDATA 值时的窄识别：前缀特征 + 足够长 */
    private fun looksLikeSessDataValue(text: String): Boolean =
        text.length >= SESSDATA_VALUE_MIN_LEN && SESSDATA_VALUE_PREFIX.containsMatchIn(text)

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
