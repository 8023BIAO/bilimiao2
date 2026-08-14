package com.a10miaomiao.bilimiao.comm.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * CDN 竞速选择器 — 从多个 CDN URL 中选最快的
 * 并发 GET + Range:bytes=0-0 请求测延迟（只取1字节），选耗时最低的
 *
 * 为什么用 GET 而不是 HEAD：
 * - B站部分 CDN 节点对 HEAD 返回 403/405，但 GET 正常
 * - Range:bytes=0-0 只下载 1 字节，开销与 HEAD 相当
 * - GET 测的是真实下载链路，比 HEAD 更接近实际播放体验
 */
object CdnSelector {

    /** 单次测试超时（毫秒）— 1.5s 足够，慢节点不值得等 */
    private const val TEST_TIMEOUT_MS = 1500L

    /** 并发测试的 URL 上限 */
    private const val MAX_CONCURRENT_TESTS = 5

    /** 从多个备选 URL 中选出最快的 */
    suspend fun pickFastest(urls: List<String>): String {
        return pickRanked(urls).firstOrNull() ?: urls.firstOrNull() ?: ""
    }

    /**
     * 竞速排序：并发 HEAD 测延迟，按延迟升序返回全部候选 URL。
     * 竞速赢家排第一位，其余按延迟跟在后面（超时/失败的排最后）。
     * 返回 | 分隔的字符串，供 [merging] 协议传给 CdnFailoverDataSource 做运行时故障转移。
     */
    suspend fun pickAndRank(urls: List<String>): String {
        return pickRanked(urls).joinToString("|")
    }

    /** 竞速排序：返回按延迟升序排列的 URL 列表（赢家在前） */
    private suspend fun pickRanked(urls: List<String>): List<String> {
        if (urls.isEmpty()) return emptyList()
        if (urls.size == 1) return urls

        return withContext(Dispatchers.IO) {
            // 去重；并发测速上限 5 个，其余候选不测速、直接排在末尾，
            // 保证返回值覆盖全部候选（文档契约），CdnFailoverDataSource 兜底列表不丢候选
            val distinct = urls.distinct()
            val candidates = distinct.take(MAX_CONCURRENT_TESTS)
            val rest = distinct.drop(MAX_CONCURRENT_TESTS)

            val results = candidates.map { url ->
                async {
                    val latency = testLatency(url)
                    url to latency
                }
            }.awaitAll()

            // 按延迟排序（成功的在前，超时/失败的 -1 排最后）
            val valid = results.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }
            val failed = results.filter { it.second <= 0 }.map { it.first }
            val ranked = valid + failed + rest

            miaoLogger().d("CDN竞速结果",
                "fastest" to "${ranked.firstOrNull()?.take(60)}... (${results.firstOrNull { it.first == ranked.firstOrNull() }?.second}ms)",
                "all" to results.joinToString { "${it.first.take(40)}...=${it.second}ms" }
            )
            ranked
        }
    }

    /** 测试单个 URL 的延迟（TCP 握手 + 首字节响应），返回毫秒数；失败返回 -1 */
    private suspend fun testLatency(urlStr: String): Long {
        if (urlStr.isBlank()) return -1L
        return withTimeoutOrNull(TEST_TIMEOUT_MS) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(urlStr)
                val start = System.currentTimeMillis()
                conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    // GET + Range:bytes=0-0 只取 1 字节 — 比 HEAD 更可靠
                    // B站部分 CDN 对 HEAD 返回 403/405，但 GET+Range 正常
                    requestMethod = "GET"
                    connectTimeout = TEST_TIMEOUT_MS.toInt()
                    readTimeout = TEST_TIMEOUT_MS.toInt()
                    setRequestProperty("User-Agent", "Bilibili Freedoooooom/MarkII")
                    setRequestProperty("Referer", "https://www.bilibili.com/")
                    setRequestProperty("Range", "bytes=0-0")
                }
                conn.connect()
                // 只要拿到响应码就算成功（206/200/403 都行，测的是连接延迟）
                conn.getResponseCode()
                // 读取并丢弃 1 字节响应体，确保完整测量首字节延迟
                conn.inputStream.use { it.read() }
                System.currentTimeMillis() - start
            } catch (e: Exception) {
                -1L
            } finally {
                conn?.disconnect()
            }
        } ?: -1L
    }


    /**
     * 批量测速：返回每个 CDN 的延迟（毫秒），用于弹窗展示。
     */
    suspend fun testBatch(urls: List<String>): List<CdnTestResult> {
        if (urls.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            urls.mapIndexed { index, url ->
                async {
                    val latency = testLatency(url)
                    CdnTestResult(index, url, latency)
                }
            }.awaitAll()
        }
    }

    data class CdnTestResult(
        val index: Int,
        val url: String,
        val latencyMs: Long,
    )

}
