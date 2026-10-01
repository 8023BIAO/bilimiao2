package com.a10miaomiao.bilimiao.comm.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 「检查更新」的全部逻辑：GitHub Release 查询 + **纯函数**解析/比对。
 *
 * ## 版本约定（rules/03）
 * · tag = `v<YYYY.MM.DD>`，与 `versionName` 逐字一致（例 `v2026.10.01`）；
 * · **只按日期比**：`v2026.10.01 → 20261001`，整数比大小；versionCode 固定 210，永不参与比较；
 * · APK 是 Release 附件（`bilimiao-v<YYYY.MM.DD>.apk`），Release 正文 = 更新说明。
 *
 * ## 为什么解析全是纯函数
 * 网络那段没法单测，但"tag 认不认、乱序取最大、三态怎么判、为什么没发布、哪种状态码走哪条路"
 * 全是纯逻辑，抽出来放这里，`AppUpdateCheckerTest` 直接喂字符串/喂状态码就能测。
 *
 * ## 不做什么（用户拍板）
 * 不接第三方服务/自建服务器、不自动下载安装、不后台常驻检查、不做镜像加速；
 * 错误弹窗里**不加**"去 GitHub 仓库"兜底按钮（关于页已有仓库入口，加了是重复）。
 */
object AppUpdateChecker {

    const val REPO = "8023BIAO/bilimiao2"

    private const val API_URL = "https://api.github.com/repos/$REPO/releases?per_page=10"

    /** 兜底：Release 的 Atom feed，不吃 API 配额（限流时也能用） */
    private const val ATOM_URL = "https://github.com/$REPO/releases.atom"

    /** GitHub 强制要求 User-Agent；也顺便把"谁在查"写清楚 */
    private const val USER_AGENT = "BiliMiao-Mod-UpdateChecker (+https://github.com/$REPO)"

    private const val TIMEOUT_SECONDS = 10L

    /** 一条 Release 的归一化形态（只留更新检查要用的字段） */
    data class AppRelease(
        val tag: String,
        /** tag 解析出的日期整数（`v2026.10.01` → 20261001）；不是日期格式时为 null */
        val date: Int?,
        val draft: Boolean,
        val prerelease: Boolean,
        /** Release 正文 = 更新说明（可能为空） */
        val notes: String,
        /** APK 附件直链；没有附件（或名字不对）时 null ⇒ UI 退到 Release 页面 */
        val apkUrl: String?,
        /** Release 页面（**不是**仓库首页） */
        val pageUrl: String,
    )

    /** 检查结果三态 */
    sealed interface UpdateResult {
        /** 本机与远端同一天 */
        data class UpToDate(val currentTag: String) : UpdateResult

        data class Available(
            val currentTag: String,
            val newTag: String,
            val newDate: Int,
            val notes: String,
            val apkUrl: String?,
            val pageUrl: String,
        ) : UpdateResult

        /** 远端最新那条比本机旧：如实说"可能是测试版"，绝不假装"已是最新" */
        data class RemoteOlder(val currentTag: String, val latestTag: String) : UpdateResult
    }

    /** 一次检查的最终结果：成功（三态）或失败（分型给人话） */
    sealed interface Outcome {
        data class Done(val result: UpdateResult) : Outcome
        data class Failed(val reason: FailReason) : Outcome
    }

    /**
     * 失败分型 —— 每一种都对应一句人话（文案在 UI 层，这里只分型）。
     * ① [TIMEOUT] 无网络/超时；② [RATE_LIMITED] 被 GitHub 限流（会先自动退 Atom，退成功就不算失败）；
     * ③ [NO_RELEASES] 远端一个都没发（含"全是 draft"、Atom feed 没有 entry）；
     * ④ [UNRECOGNIZED_TAGS] 确实发布了但 tag 格式认不出；⑤ [REQUEST_FAILED] GitHub 明确拒绝/出错（403 非限流、404、5xx 等）；
     * ⑥ [UNPARSABLE] 响应结构/内容看不懂。
     */
    enum class FailReason {
        TIMEOUT,
        RATE_LIMITED,
        NO_RELEASES,
        UNRECOGNIZED_TAGS,
        REQUEST_FAILED,
        UNPARSABLE,
    }

    /** HTTP 状态码该走哪条路（纯函数，实测边界见复核报告：配额用尽那一次是 200 + remaining=0） */
    enum class StatusClass {
        /** 2xx：**有效响应**，正常解析（`remaining=0` 也不例外） */
        OK,

        /** 403 + `X-RateLimit-Remaining: 0`：真限流，退 Atom */
        RATE_LIMITED,

        /** 其余非 2xx：普通失败（403 非限流 / 404 / 5xx…），也退 Atom 试一次 */
        REQUEST_FAILED,
    }

    // ══════════════════════════ 纯函数（可单测） ══════════════════════════

    /**
     * tag → 日期整数。认 `v2026.10.01` / `2026.10.01`（允许前后空白、月日 1~2 位、
     * `-beta` / `+1` 这类后缀按"取日期部分"处理）；不认 `vc210`、`v2026.10`、`v2026.10.01.1`、
     * `release-1`、以及**不存在的日历日期**（`v2026.02.31`/`v2026.11.31`/平年 `v2026.02.29`）。
     * 解析不出返回 null（**绝不猜**）。
     */
    fun parseReleaseDate(tag: String): Int? {
        val matched = TAG_DATE.matchEntire(tag.trim()) ?: return null
        val year = matched.groupValues[1].toIntOrNull() ?: return null
        val month = matched.groupValues[2].toIntOrNull() ?: return null
        val day = matched.groupValues[3].toIntOrNull() ?: return null
        if (month !in 1..12) return null
        if (day !in 1..daysInMonth(year, month)) return null
        return year * 10000 + month * 100 + day
    }

    /** `v2026.10.01` / `2026.10.01`，可选 `-xxx` / `+xxx` 后缀；多一段数字（`.1`）不认 */
    private val TAG_DATE = Regex("""^v?(\d{4})\.(\d{1,2})\.(\d{1,2})(?:[-+].*)?$""")

    /** 该月真实天数（含闰年）—— 只查 1..31 的话，`v2026.02.31` 这种占位 tag 会被当成真版本 */
    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
    }

    /**
     * 限流判定：**只有 `403` 且 `X-RateLimit-Remaining: 0`** 才算。
     *
     * ★实测（复核报告 表 B）：配额用尽**那一次是 HTTP 200 + remaining=0**（下一跳才是 403）。
     *   所以"200 + 0"是**有效响应**、必须正常用；`403` 但 remaining 不是 0（被墙/权限等）也不是限流。
     */
    fun isRateLimited(code: Int, rateLimitRemaining: String?): Boolean =
        code == 403 && rateLimitRemaining?.trim() == "0"

    /** 状态码 → 走向（先判 2xx，再判限流，最后归普通失败） */
    fun classifyStatus(code: Int, rateLimitRemaining: String?): StatusClass = when {
        code in 200..299 -> StatusClass.OK
        isRateLimited(code, rateLimitRemaining) -> StatusClass.RATE_LIMITED
        else -> StatusClass.REQUEST_FAILED
    }

    /**
     * 解析 GitHub Releases API 的 JSON 数组。**结构解析失败返回 null**（调用方据此走 Atom 兜底），
     * 空数组返回空列表（= 远端没有发布）。
     */
    fun parseReleasesJson(body: String): List<AppRelease>? =
        runCatching { jsonParser.decodeFromString<List<ReleaseDto>>(body) }
            .getOrNull()
            ?.map { it.toAppRelease() }

    /**
     * 从候选里挑"最新"：**过滤 draft**（未发布的不算）、忽略 tag 不是日期格式的（逐条跳过），
     * 取日期最大者；不依赖数组顺序（GitHub 返回顺序变了也不受影响）。
     * prerelease **保留**（日期就是真值）；没有可用条目返回 null。
     */
    fun pickLatest(releases: List<AppRelease>): AppRelease? =
        releases.asSequence()
            .filter { !it.draft }
            .filter { it.date != null }
            .maxByOrNull { it.date ?: 0 }

    /**
     * [pickLatest] 挑不出东西时，说清"为什么"（**只在 pickLatest == null 时调用**，两者的判据必须一致）：
     * · 有正式发布（非 draft）但 tag **全都**认不出（`vc210`/`v2026.10`/乱写）→ [FailReason.UNRECOGNIZED_TAGS]；
     * · 其余（空列表 / **全是 draft**，不管 draft 的 tag 长什么样）→ [FailReason.NO_RELEASES]（等于没发布）；
     * · 只要有一条正式条目能认出日期 —— 那 [pickLatest] 就绝不会是 null，根本走不到这里。
     *
     * ★判据必须是 `none { 能认出 }` 而不是 `any { 认不出 }`：混着一条能认出的正式发布时，
     *   "逐条跳过认不出的、剩下的正常比"才是对的口径（曾把 `any` 写反，单测抓出）。
     */
    fun noReleaseReason(releases: List<AppRelease>): FailReason {
        val published = releases.filter { !it.draft }
        return if (published.isNotEmpty() && published.none { it.date != null }) {
            FailReason.UNRECOGNIZED_TAGS
        } else {
            FailReason.NO_RELEASES
        }
    }

    /**
     * 与已装版本比：远端更新 → [UpdateResult.Available]；同一天 → [UpdateResult.UpToDate]；
     * 远端更旧 → [UpdateResult.RemoteOlder]；**远端那条没有可解析日期 → [FailReason.UNRECOGNIZED_TAGS] 失败**
     * （不返回"已是最新"：本文件的口径是"绝不假装已是最新"）。
     *
     * [currentDate] 为 null = 本机 versionName 不符合发布约定（开发版/老版本）：
     * 这时**不谎称"已是最新"**，按"远端有版本可看"处理（安全方向：宁可多提示一次）。
     */
    fun compare(currentTag: String, currentDate: Int?, latest: AppRelease): Outcome {
        val latestDate = latest.date ?: return Outcome.Failed(FailReason.UNRECOGNIZED_TAGS)
        if (currentDate == null || latestDate > currentDate) {
            return Outcome.Done(
                UpdateResult.Available(
                    currentTag = currentTag,
                    newTag = latest.tag,
                    newDate = latestDate,
                    notes = latest.notes,
                    apkUrl = latest.apkUrl,
                    pageUrl = latest.pageUrl,
                )
            )
        }
        return Outcome.Done(
            if (latestDate == currentDate) {
                UpdateResult.UpToDate(currentTag)
            } else {
                UpdateResult.RemoteOlder(currentTag = currentTag, latestTag = latest.tag)
            }
        )
    }

    /**
     * Atom 兜底解析。tag **从 `<id>` 尾段取**（真实 GitHub：`<id>tag:github.com,2008:Repository/<id>/<tag></id>`；
     * `<title>` 是 Release **名字**，实测 obs-studio 是 `OBS Studio 33.0.0-beta5`）——`<id>` 取不出时才用 `<title>` 兜底。
     *
     * 返回值语义：
     * · feed 里**没有 `<entry>`** → **空列表**（= 远端没发布 ⇒ 调用方报 [FailReason.NO_RELEASES]，不是"看不懂"）；
     * · 有 `<entry>` 但一条都抠不出 tag → null（结构看不懂 ⇒ [FailReason.UNPARSABLE]）。
     *
     * `<content>` 是 HTML 且被 XML 转义：只做"反转义 + 去标签 + 每行 trim"；
     * Atom **没有 APK 附件** ⇒ apkUrl = null，UI 会退到该 Release 页面。
     */
    fun parseAtomReleases(xml: String): List<AppRelease>? {
        val entries = ATOM_ENTRY.findAll(xml).map { it.groupValues[1] }.toList()
        if (entries.isEmpty()) return emptyList()
        val parsed = entries.mapNotNull { entry ->
            val tag = atomTag(entry) ?: return@mapNotNull null
            AppRelease(
                tag = tag,
                date = parseReleaseDate(tag),
                draft = false,
                prerelease = false,
                notes = htmlToPlainText(
                    ATOM_CONTENT.find(entry)?.groupValues?.get(1).orEmpty()
                ),
                apkUrl = null,
                pageUrl = ATOM_LINK.find(entry)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
                    ?: releasePageUrl(tag),
            )
        }
        return parsed.ifEmpty { null }
    }

    /** entry → tag：优先 `<id>` 最后一段（真 tag），`<title>` 只在 `<id>` 缺失/为空时兜底 */
    private fun atomTag(entry: String): String? {
        val id = ATOM_ID.find(entry)?.groupValues?.get(1)?.trim().orEmpty()
        val fromId = id.substringAfterLast('/').trim()
        if (fromId.isNotEmpty()) return fromId
        return ATOM_TITLE.find(entry)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    // ══════════════════════════ 网络（不可单测，10s 超时 + Atom 兜底） ══════════════════════════

    /**
     * 查一次。流程：
     * ① `GET api.github.com/.../releases?per_page=10`（带 User-Agent，连接/读各 10s）；
     * ② 2xx **一律正常解析**（含 `remaining=0` 的那一次 200）；限流（403+0）/ 其它非 2xx / 无网 /
     *    结构看不懂 → 退到 `releases.atom`（不吃 API 配额），**Atom 成功就当正常结果**，不弹错；
     * ③ 两条都拿不到 → 按第一次的分型如实失败（**绝不假装"已是最新"**）。
     */
    suspend fun checkForUpdate(currentTag: String): Outcome = withContext(Dispatchers.IO) {
        val currentDate = parseReleaseDate(currentTag)

        val api = runCatching { httpGet(API_URL) }.getOrNull()
            ?: return@withContext fromAtom(currentTag, currentDate, FailReason.TIMEOUT)
        when (classifyStatus(api.code, api.rateLimitRemaining)) {
            StatusClass.OK -> Unit
            StatusClass.RATE_LIMITED -> return@withContext fromAtom(currentTag, currentDate, FailReason.RATE_LIMITED)
            StatusClass.REQUEST_FAILED -> return@withContext fromAtom(currentTag, currentDate, FailReason.REQUEST_FAILED)
        }
        val releases = parseReleasesJson(api.body)
            ?: return@withContext fromAtom(currentTag, currentDate, FailReason.UNPARSABLE)
        val latest = pickLatest(releases)
            ?: return@withContext Outcome.Failed(noReleaseReason(releases))
        compare(currentTag, currentDate, latest)
    }

    /** Atom 兜底：成功则用同一条 [compare] 判三态；失败则回第一次的分型 */
    private fun fromAtom(currentTag: String, currentDate: Int?, fallback: FailReason): Outcome {
        val response = runCatching { httpGet(ATOM_URL) }.getOrNull()
            ?: return Outcome.Failed(fallback)
        if (response.code !in 200..299) return Outcome.Failed(fallback)
        // 没有 <entry> = 远端一个都没发布（≠ "看不懂"）；有 entry 但抠不出东西才是看不懂
        val releases = parseAtomReleases(response.body) ?: return Outcome.Failed(FailReason.UNPARSABLE)
        if (releases.isEmpty()) return Outcome.Failed(FailReason.NO_RELEASES)
        val latest = pickLatest(releases) ?: return Outcome.Failed(noReleaseReason(releases))
        return compare(currentTag, currentDate, latest)
    }

    private data class HttpResponse(
        val code: Int,
        val body: String,
        val rateLimitRemaining: String?,
    )

    /** 阻塞式 GET（调用点已在 IO 线程）：独立 OkHttpClient，超时按需求固定 10s */
    private fun httpGet(url: String): HttpResponse {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json, application/atom+xml")
            .build()
        client.newCall(request).execute().use { response ->
            return HttpResponse(
                code = response.code,
                body = response.body?.string().orEmpty(),
                rateLimitRemaining = response.header("X-RateLimit-Remaining"),
            )
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    // ══════════════════════════ JSON / 文本小工具 ══════════════════════════

    private val jsonParser = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ReleaseDto(
        val tag_name: String = "",
        val body: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val html_url: String = "",
        val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private data class AssetDto(
        val name: String = "",
        val browser_download_url: String = "",
    )

    private fun ReleaseDto.toAppRelease(): AppRelease = AppRelease(
        tag = tag_name.trim(),
        date = parseReleaseDate(tag_name),
        draft = draft,
        prerelease = prerelease,
        notes = body.trim(),
        apkUrl = pickApkUrl(assets),
        pageUrl = html_url.trim().ifEmpty { releasePageUrl(tag_name.trim()) },
    )

    /** APK 附件优先（`bilimiao-` 开头的排前面）；没有附件/名字不对返回 null ⇒ UI 退到 Release 页面 */
    private fun pickApkUrl(assets: List<AssetDto>): String? =
        assets.asSequence()
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .sortedBy { if (it.name.startsWith("bilimiao-", ignoreCase = true)) 0 else 1 }
            .map { it.browser_download_url.trim() }
            .firstOrNull { it.isNotEmpty() }

    private fun releasePageUrl(tag: String): String = "https://github.com/$REPO/releases/tag/$tag"

    private val ATOM_ENTRY = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
    private val ATOM_ID = Regex("<id>(.*?)</id>", RegexOption.DOT_MATCHES_ALL)
    private val ATOM_TITLE = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
    private val ATOM_CONTENT = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
    private val ATOM_LINK = Regex("""href="([^"]+)"""")

    /**
     * Atom 的 content 是 HTML（且被 XML 转义）：反转义 → 去标签 → **每行 trim** → 压掉连续空行，
     * 弹窗里当好读的纯文本（不去 trim 的话，`<br/>` 前那一格空格会留在行尾）。
     */
    private fun htmlToPlainText(html: String): String {
        val unescaped = html
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
        return unescaped
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
