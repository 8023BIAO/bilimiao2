package com.a10miaomiao.bilimiao.comm.apis

import android.content.Context
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorSegment
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorSegmentGroup
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorUserInfo
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.utils.SponsorDiag
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 「小电视空降助手」（BilibiliSponsorBlock）客户端。
 *
 * 服务端 `https://www.bsbsb.top`（上游 https://github.com/hanydd/BilibiliSponsorBlock 的公共实例），
 * 接口契约见 /root/test/sponsorblock-server-api.md，行为基准参考 PiliPlus 与 BiliRoaming。
 *
 * 为什么取片段用**哈希隐私端点**：服务端只知道 `SHA256(裸BVID)` 的前 4 位，不知道你在看哪个视频
 * （BiliRoaming 也是这么做的）。代价是它一次返回"同一哈希前缀下所有视频"的片段，需要本地过滤。
 *
 * 实测要点（都 curl 验证过）：
 *  - 纯 GET/POST、无鉴权、无签名、不需要 Cookie；UA 只要不是 python-urllib 那种就放行；
 *  - 哈希 = `SHA256(裸BVID)` **只哈希 1 次**取前 4 位小写十六进制（提交侧才是 5000 次，别混）；
 *  - 查不到 = `200 []`（不是 404）；
 *  - 哈希端点**不接受任何 query 参数**（多传一个 `cid` 就 400）→ 过滤全在本地做；
 *  - 投票用 **query 参数**、上报用 **JSON body**，两者不一样（照 PiliPlus 抄的实测结论）。
 */
class SponsorBlockApi {

    companion object {
        const val BASE_URL = "https://www.bsbsb.top"

        private const val HEX = "0123456789abcdef"
        private const val USER_ID_PREF = "sponsor_block"
        private const val USER_ID_KEY = "user_id"

        /**
         * 服务端地址覆盖（设置页可填镜像站）。
         * 由 `PlayerController.initVideoSetting` 从设置里读出来写进来；null/空 = 用默认。
         */
        @Volatile
        var serverOverride: String? = null

        /** 当前实际使用的服务端基址 */
        val baseUrl: String
            get() = serverOverride?.trim()?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: BASE_URL

        /** `SHA256(裸BVID)` 前 4 位小写十六进制；输入必须原样（BVID 大小写敏感） */
        fun hashPrefix(bvid: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(bvid.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(64)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return hex.substring(0, 4)
        }

        /**
         * 本机 userID（投票/提交要带）。
         *
         * 隐私设计：**本地随机生成、只存在本机**，服务端拿它做"一人一票"去重，不关联账号。
         * 用 SharedPreferences 而不是 DataStore：这是纯客户端身份，和用户设置无关，
         * 也不需要参与设置页的重组。
         *
         * 用户可以在设置页里**手动编辑或点「随机」重掷**（对齐 PiliPlus 的 `blockUserID`）：
         * 换 ID = 换一个"服务端眼里的你"，投票/提交记录和统计都会跟着换。
         */
        fun localUserId(): String {
            val sp = prefs()
            sp.getString(USER_ID_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
            val id = randomUserId()
            sp.edit().putString(USER_ID_KEY, id).apply()
            return id
        }

        /** 生成一个新的随机匿名 ID（16 字节随机数的十六进制，32 位；对齐 PiliPlus 的「随机」按钮） */
        fun randomUserId(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val sb = StringBuilder(32)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }

        /**
         * 手动设置 userID。
         *
         * 校验规则照抄 PiliPlus：**至少 30 个字符、只能是字母和数字**
         * （服务端那边是 32~36 位的随机串，太短或带符号可能被拒）。
         * 返回 false = 没通过校验，调用方负责提示。
         */
        fun setUserId(id: String): Boolean {
            val v = id.trim()
            if (v.length < 30 || v.length > 128) return false
            if (!v.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }) return false
            prefs().edit().putString(USER_ID_KEY, v).apply()
            return true
        }

        /** 重掷一个随机 ID 并保存，返回新 ID */
        fun resetUserId(): String {
            val id = randomUserId()
            prefs().edit().putString(USER_ID_KEY, id).apply()
            return id
        }

        private fun prefs() = BilimiaoCommApp.commApp.app
            .getSharedPreferences(USER_ID_PREF, Context.MODE_PRIVATE)
    }

    /**
     * 取某个视频（分P）的片段列表。
     *
     * 任何失败（网络、限流、UA 被 WAF 拦、JSON 变形）都返回空列表 —— 这个功能必须
     * **静默降级**：服务端挂了不能让播放出任何提示或阻塞（PiliPlus 也是 Release 下无感）。
     */
    suspend fun getSegments(bvid: String, cid: String): List<SponsorSegment> {
        if (bvid.isBlank()) return emptyList()
        // 先走隐私哈希端点；拿不到（空/失败/服务端差异）再退回扁平端点。
        // 两条都实测可用，互为兜底 —— 这个功能绝不能因为一个端点抽风就整体失效。
        val byHash = getSegmentsByHash(bvid, cid)
        if (byHash.isNotEmpty()) return byHash
        SponsorDiag.log("api-fallback", "hash 端点没结果，改走扁平端点 bvid=$bvid cid=$cid")
        return getSegmentsFlat(bvid, cid)
    }

    /** 扁平端点：`GET /api/skipSegments?videoID=&cid=`（服务端已按 cid 过滤，返回扁平数组） */
    private suspend fun getSegmentsFlat(bvid: String, cid: String): List<SponsorSegment> {
        return try {
            val url = "$baseUrl/api/skipSegments?" + buildString {
                append("videoID=").append(java.net.URLEncoder.encode(bvid, "UTF-8"))
                if (cid.isNotBlank()) append("&cid=").append(java.net.URLEncoder.encode(cid, "UTF-8"))
            }
            val response = MiaoHttp.request {
                this.url = url
                isWebApi = true
            }.awaitCall()
            val bodyText = response.body?.string().orEmpty()
            SponsorDiag.log("api-flat", "GET $url -> http=${response.code} len=${bodyText.length}")
            if (response.code != 200) return emptyList()
            MiaoJson.fromJson<List<SponsorSegment>>(bodyText)
                .filter { it.isValid }
                .sortedBy { it.startMs }
                .also { SponsorDiag.log("api-flat-filter", "-> ${it.size} segments") }
        } catch (e: java.util.concurrent.CancellationException) {
            // 协程被取消（例如退出播放页）时必须原样抛出：吞掉它会让"已取消"的请求
            // 继续跑完并返回一个没人要的结果，也会破坏结构化并发
            throw e
        } catch (e: Exception) {
            SponsorDiag.log("api-flat-error", "${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getSegmentsByHash(bvid: String, cid: String): List<SponsorSegment> {
        return try {
            val url = "$baseUrl/api/skipSegments/${hashPrefix(bvid)}"
            val response = MiaoHttp.request {
                this.url = url
                // 纯 web 语义：不带 app-key/env/Authorization（那是给 bilibili 域名的）
                isWebApi = true
            }.awaitCall()
            val bodyText = response.body?.string().orEmpty()
            SponsorDiag.log("api", "GET $url -> http=${response.code} len=${bodyText.length}")
            if (response.code != 200) {
                SponsorDiag.log("api-body", bodyText.take(200))
                return emptyList()
            }
            val groups = MiaoJson.fromJson<List<SponsorSegmentGroup>>(bodyText)
            SponsorDiag.log(
                "api-parsed",
                "groups=${groups.size} rawSegs=${groups.sumOf { it.segments.size }} " +
                    "videoIDs=${groups.joinToString(",") { it.videoID }.take(120)}"
            )
            groups.asSequence()
                .filter { it.videoID == bvid }          // 哈希前缀会命中多个视频，必须精确匹配
                .flatMap { it.segments.asSequence() }
                .filter { it.isValid }
                .filter { cid.isBlank() || it.cid.isBlank() || it.cid == cid } // 分P 过滤
                .sortedBy { it.startMs }
                .toList()
                .also { SponsorDiag.log("api-filter", "bvid=$bvid cid=$cid -> ${it.size} segments") }
        } catch (e: java.util.concurrent.CancellationException) {
            // 协程被取消（例如退出播放页）时必须原样抛出：吞掉它会让"已取消"的请求
            // 继续跑完并返回一个没人要的结果，也会破坏结构化并发
            throw e
        } catch (e: Exception) {
            SponsorDiag.log("api-error", "${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    /**
     * 投票：`type = 1` 赞成、`type = 0` 反对；或传 [category] 表示"更改类别"（两者互斥）。
     * 参数走 query（照 PiliPlus `sponsor_block.dart:77` 的实测结论）。
     */
    suspend fun vote(
        uuid: String,
        type: Int? = null,
        category: String? = null,
        userId: String = localUserId(),
    ): Boolean {
        if (uuid.isBlank()) return false
        return try {
            val params = buildList {
                add("UUID" to uuid)
                type?.let { add("type" to it.toString()) }
                category?.let { add("category" to it) }
                add("userID" to userId)
            }.joinToString("&") { "${it.first}=${it.second}" }
            val res = MiaoHttp.request {
                url = "$baseUrl/api/voteOnSponsorTime?$params"
                isWebApi = true
                method = MiaoHttp.POST
                // ★★ 必须给一个 body（哪怕是空的）★★
                // okhttp 的 `Request.Builder.method("POST", null)` 会直接抛
                // IllegalArgumentException("method POST must have a request body")，
                // 被下面的 catch 吞掉 → **所有投票都"失败"**（用户实测报的就是这个）。
                // 服务端只认 query 参数（UUID/type/category/userID），body 内容无所谓，给个空体即可。
                body = "".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            }.awaitCall()
            // 用 use{} 关掉响应体：只读 code 不关的话连接不会还给连接池（OkHttp 会警告 leaked）
            res.use { it.code == 200 }
        } catch (e: java.util.concurrent.CancellationException) {
            // 协程被取消（例如退出播放页）时必须原样抛出：吞掉它会让"已取消"的请求
            // 继续跑完并返回一个没人要的结果，也会破坏结构化并发
            throw e
        } catch (e: Exception) {
            // 写诊断日志：投票失败以前是"静默 false"，用户只能看到一句模糊提示，
            // 根本不知道是网络、是服务端拒绝、还是我们自己请求构造错了（POST 没带 body 那次就是）。
            SponsorDiag.log(
                "api-vote",
                "投票失败 uuid=${uuid.take(8)} type=$type category=$category " +
                    "→ ${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    /** 上报"这个片段真的被跳过了"（服务端拿它统计省下的时间）。**不需要 userID**。 */
    suspend fun reportViewed(uuid: String): Boolean {
        if (uuid.isBlank()) return false
        return try {
            val res = MiaoHttp.request {
                url = "$baseUrl/api/viewedVideoSponsorTime"
                isWebApi = true
                method = MiaoHttp.POST
                body = MiaoJson.toJson(SponsorViewedBody(uuid))
                    .toRequestBody("application/json".toMediaType())
            }.awaitCall()
            res.use { it.code == 200 }
        } catch (e: java.util.concurrent.CancellationException) {
            // 协程被取消（例如退出播放页）时必须原样抛出：吞掉它会让"已取消"的请求
            // 继续跑完并返回一个没人要的结果，也会破坏结构化并发
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 提交新片段（POST /api/skipSegments）。
     *
     * 注意与取片段的一个关键差别：**提交侧的视频标识哈希是 `SHA256(bvid+cid)`**，
     * 但 body 里的 `videoID` 仍然是裸 BVID（哈希只用于服务端生成 hashedVideoID）。
     */
    suspend fun postSegments(
        bvid: String,
        cid: String,
        videoDurationSec: Double,
        segments: List<PostSegment>,
        userId: String = localUserId(),
    ): Boolean {
        if (bvid.isBlank() || cid.isBlank() || segments.isEmpty()) return false
        return try {
            val body = SponsorPostBody(
                videoID = bvid,
                cid = cid,
                userID = userId,
                userAgent = "BiliMiao-Mod",
                videoDuration = videoDurationSec,
                segments = segments,
            )
            val res = MiaoHttp.request {
                url = "$baseUrl/api/skipSegments"
                isWebApi = true
                method = MiaoHttp.POST
                this.body = MiaoJson.toJson(body).toRequestBody("application/json".toMediaType())
            }.awaitCall()
            res.use { it.code == 200 }
        } catch (e: java.util.concurrent.CancellationException) {
            // 协程被取消（例如退出播放页）时必须原样抛出：吞掉它会让"已取消"的请求
            // 继续跑完并返回一个没人要的结果，也会破坏结构化并发
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 服务端已运行秒数；**不可达返回 null**。
     *
     * ★ 端点必须是 `GET /api/status/uptime`（正文就是一个纯数字秒数，如 `105614.220781795`）。
     *   我一开始写的是 `/api/status` —— 那个端点返回的是**一大坨 JSON 对象**
     *   （`{"uptime":…,"commit":…,"postgresStats":{…}}`），拿 `toLongOrNull()` 解析必然失败，
     *   于是状态永远显示"暂时不可达"，而服务端其实是好的。这是照 PiliPlus 的
     *   `SponsorBlockApi.uptimeStatus = 'status/uptime'` 核出来的。
     */
    suspend fun uptimeSeconds(): Double? {
        // ① 主端点：正文是纯数字（可能带小数）
        try {
            val res = MiaoHttp.request {
                url = "$baseUrl/api/status/uptime"
                isWebApi = true
            }.awaitCall()
            if (res.code == 200) {
                res.body?.string()?.trim()?.toDoubleOrNull()?.let {
                    if (it >= 0) return it
                }
            }
        } catch (e: java.util.concurrent.CancellationException) {
            // 协程被取消（例如退出播放页）时必须原样抛出：吞掉它会让"已取消"的请求
            // 继续跑完并返回一个没人要的结果，也会破坏结构化并发
            throw e
        } catch (e: Exception) {
            SponsorDiag.log("api-status", "status/uptime 失败：${e.message}")
        }
        // ② 兜底：/api/status 的 JSON 对象里也有 uptime 字段
        return try {
            val res = MiaoHttp.request {
                url = "$baseUrl/api/status"
                isWebApi = true
            }.awaitCall()
            val text = if (res.code == 200) res.body?.string().orEmpty() else ""
            Regex("\"uptime\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
                .find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        } catch (e: java.util.concurrent.CancellationException) {
            // 协程被取消（例如退出播放页）时必须原样抛出：吞掉它会让"已取消"的请求
            // 继续跑完并返回一个没人要的结果，也会破坏结构化并发
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 服务器是否在线 */
    suspend fun uptimeStatus(): Boolean = uptimeSeconds() != null

    /** 本机在该服务端的统计数据（省下多少分钟等） */
    suspend fun userInfo(userId: String = localUserId()): SponsorUserInfo? {
        return try {
            val values = "[\"viewCount\",\"minutesSaved\",\"segmentCount\"]"
            val res = MiaoHttp.request {
                url = "$baseUrl/api/userInfo?userID=$userId&values=${java.net.URLEncoder.encode(values, "UTF-8")}"
                isWebApi = true
            }.awaitCall().json<SponsorUserInfo>()
            res
        } catch (e: java.util.concurrent.CancellationException) {
            // 协程被取消（例如退出播放页）时必须原样抛出：吞掉它会让"已取消"的请求
            // 继续跑完并返回一个没人要的结果，也会破坏结构化并发
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 上报体 */
    @Serializable
    private data class SponsorViewedBody(val UUID: String)

    /** 提交体（字段名严格照服务端/PiliPlus，别改） */
    @Serializable
    data class SponsorPostBody(
        val videoID: String,
        val cid: String,
        val userID: String,
        val userAgent: String,
        val videoDuration: Double,
        val segments: List<PostSegment>,
    )

    /** 待提交的一个片段 */
    @Serializable
    data class PostSegment(
        val segment: List<Double>,
        val category: String,
        val actionType: String,
    )
}
