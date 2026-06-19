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
 * 参考 pilipala 策略：并发 HEAD 请求测延迟，选耗时最低的
 */
object CdnSelector {

    /** 单次测试超时（毫秒） */
    private const val TEST_TIMEOUT_MS = 2000L

    /** 并发测试的 URL 上限 */
    private const val MAX_CONCURRENT_TESTS = 5

    /** 从多个备选 URL 中选出最快的 */
    suspend fun pickFastest(urls: List<String>): String {
        if (urls.isEmpty()) return ""
        if (urls.size == 1) return urls.first()

        return withContext(Dispatchers.IO) {
            // 去重并限制数量
            val candidates = urls.distinct().take(MAX_CONCURRENT_TESTS)

            val results = candidates.map { url ->
                async {
                    val latency = testLatency(url)
                    url to latency
                }
            }.awaitAll()

            // 按延迟排序，选最快的（排除超时/失败的 -1）
            val valid = results.filter { it.second > 0 }.sortedBy { it.second }
            if (valid.isNotEmpty()) {
                miaoLogger().d("CDN竞速结果",
                    "fastest" to "${valid.first().first.take(60)}... (${valid.first().second}ms)",
                    "all" to valid.joinToString { "${it.first.take(40)}...=${it.second}ms" }
                )
                valid.first().first
            } else {
                // 全部失败，用第一个
                urls.first()
            }
        }
    }

    /** 测试单个 URL 的延迟（TCP 握手），返回毫秒数；失败返回 -1 */
    private suspend fun testLatency(urlStr: String): Long {
        if (urlStr.isBlank()) return -1L
        return withTimeoutOrNull(TEST_TIMEOUT_MS) {
            try {
                val url = URL(urlStr)
                val start = System.currentTimeMillis()
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "GET"
                    connectTimeout = TEST_TIMEOUT_MS.toInt()
                    readTimeout = TEST_TIMEOUT_MS.toInt()
                    setRequestProperty("User-Agent", "Bilibili Freedoooooom/MarkII")
                    setRequestProperty("Referer", "https://www.bilibili.com/")
                }
                conn.connect()
                // 只要能连上就算成功（不管返回码），只测连接延迟
                conn.getResponseCode()
                conn.disconnect()
                System.currentTimeMillis() - start
            } catch (e: Exception) {
                -1L
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
