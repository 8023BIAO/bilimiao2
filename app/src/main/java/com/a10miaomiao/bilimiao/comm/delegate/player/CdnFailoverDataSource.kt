@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.a10miaomiao.bilimiao.comm.delegate.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * CDN 运行时故障转移 — 包装 Media3 DataSource，open() 失败时自动切换到下一个候选 URL。
 *
 * 与 CdnSelector 的播放前竞速互补：
 * - CdnSelector：播放前并发 HEAD 测延迟，选最快的（预选）
 * - CdnFailoverDataSource：播放中某个 CDN 挂了，自动切下一个（兜底）
 *
 * 候选列表中第一个是竞速赢家（或 baseUrl），其余是 backupUrl。
 * 成功打开后记住 index，下次优先从上次成功的 CDN 开始。
 *
 * 参考 blbl 项目 CdnFailoverDataSource.kt。
 */
internal class CdnFailoverState(
    val candidates: List<Uri>,
) {
    @Volatile
    private var preferredIndex: Int = 0

    @Synchronized
    fun getPreferredIndex(): Int {
        val last = candidates.lastIndex
        return preferredIndex.coerceIn(0, last.coerceAtLeast(0))
    }

    @Synchronized
    fun prefer(index: Int) {
        val last = candidates.lastIndex
        preferredIndex = index.coerceIn(0, last.coerceAtLeast(0))
    }
}

internal class CdnFailoverDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val state: CdnFailoverState,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = CdnFailoverDataSource(upstreamFactory, state)
}

internal class CdnFailoverDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val state: CdnFailoverState,
) : DataSource {
    private var upstream: DataSource? = null
    private val transferListeners = ArrayList<TransferListener>(2)

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        closeQuietly()
        val candidates = state.candidates
        if (candidates.isEmpty()) throw IOException("No CDN candidates for failover")

        val start = state.getPreferredIndex()
        var lastException: IOException? = null
        for (attempt in candidates.indices) {
            val idx = (start + attempt) % candidates.size
            val uri = candidates[idx]
            val ds = upstreamFactory.createDataSource()
            transferListeners.forEach { ds.addTransferListener(it) }
            val spec = dataSpec.buildUpon().setUri(uri).build()
            try {
                val openedLength = ds.open(spec)
                upstream = ds
                state.prefer(idx)
                return openedLength
            } catch (e: IOException) {
                runCatching { ds.close() }
                lastException = e
            }
        }
        throw lastException ?: IOException("Failed to open any CDN candidate")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val ds = upstream ?: throw IllegalStateException("read() before open()")
        return ds.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream?.uri

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { upstream?.close() }
        upstream = null
    }
}
