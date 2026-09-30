package com.a10miaomiao.bilimiao.comm.delegate.player

/**
 * DASH `sidx`（SegmentIndexBox，ISO/IEC 14496-12 §8.16.3）解析 + 按真实分段边界切块。
 *
 * 为什么要它：同一个 m4s 内各分段（`referenced_size`）本来就不均，按"区间平均等分"切会让
 * 某条连接分到超大段、尾巴拖长。这里读出真实边界后按段切，并要求块是**若干整段**的并集。
 *
 * ★纯 JVM、零 android 依赖（可单测）；**任何异常/不可信输入一律返回 null**，
 *   由调用方回退到原来的均分逻辑 —— 绝不抛、绝不改变"没有 sidx 时"的行为。
 */
internal object DashSidxParser {

    /** 文件内的一个分段：绝对偏移 + 字节数 */
    data class Segment(val start: Long, val size: Long)

    /** 一次并发请求的区间：绝对偏移 + 字节数 */
    data class Range(val offset: Long, val size: Long)

    /** `sidx` 四个字节的 box type（'sidx'） */
    private const val TYPE_SIDX = 0x73696478L

    /** 参考条目上限（12 字节/条）：坏数据别把内存吃光 */
    private const val MAX_REFERENCES = 50_000

    /** 单段size上限（31 位字段本就 ≤2GB，这里再兜一道） */
    private const val MAX_SEGMENT_BYTES = 1L shl 32

    /** 段表总长上限（防累加溢出/荒谬值） */
    private const val MAX_TOTAL_BYTES = 1L shl 40

    /**
     * 从 m4s 头部字节里解析 `sidx`。
     *
     * @param bytes 头部缓冲
     * @param size  有效字节数（实际读到的可能比缓冲短）
     * @return 各分段（相对**文件起点**的区间，已含 `first_offset`）；
     *         无 `sidx` / 截断 / `reference_type=1`（分层索引）/ 计数为 0 / 数值越界 一律 null
     */
    fun parse(bytes: ByteArray, size: Int = bytes.size): List<Segment>? = try {
        parseOrNull(bytes, size)
    } catch (_: Throwable) {
        null // 绝不抛：调用方按"没有 sidx"回退均分
    }

    private fun parseOrNull(bytes: ByteArray, size: Int): List<Segment>? {
        if (size <= 0 || size > bytes.size) return null
        var pos = 0
        while (pos + 8 <= size) {
            var boxSize = readU32(bytes, pos)
            val type = readU32(bytes, pos + 4)
            var headerSize = 8
            if (boxSize == 1L) {                       // largesize：64 位长度
                if (pos + 16 > size) return null
                boxSize = readU64(bytes, pos + 8)
                headerSize = 16
            } else if (boxSize == 0L) {                // 0 = 一直到文件尾
                boxSize = (size - pos).toLong()
            }
            if (boxSize < headerSize) return null       // 坏框（含 sidx 自身被截断）
            val boxEnd = pos + boxSize
            if (boxEnd > size) {
                // 框越界 = 头部读少了/被截断：是 sidx 就放弃（上层回退），否则也放弃（无法可靠跳框）
                return null
            }
            if (type == TYPE_SIDX) return parseSidx(bytes, pos + headerSize, boxEnd.toInt())
            pos = boxEnd.toInt()                        // 不是 sidx（styp/moof…）就跳过去继续找
        }
        return null
    }

    private fun parseSidx(bytes: ByteArray, bodyStart: Int, boxEnd: Int): List<Segment>? {
        var p = bodyStart
        if (p + 4 > boxEnd) return null
        val version = bytes[p].toInt() and 0xFF
        p += 4                                          // version(1) + flags(3)
        if (version != 0 && version != 1) return null
        p += 8                                          // reference_ID + timescale
        // earliest_presentation_time：v0 是 32 位、v1 是 64 位
        p += if (version == 0) 4 else 8
        if (p + (if (version == 0) 4 else 8) + 4 > boxEnd) return null
        val firstOffset = if (version == 0) {
            readU32(bytes, p).also { p += 4 }
        } else {
            readU64(bytes, p).also { p += 8 }
        }
        p += 2                                          // reserved
        val count = readU16(bytes, p); p += 2
        if (count <= 0 || count > MAX_REFERENCES) return null
        if (p + count * 12 > boxEnd) return null        // 条目被截断（头部读少了）

        var cursor = boxEnd.toLong() + firstOffset
        if (firstOffset < 0) return null
        val out = ArrayList<Segment>(count)
        var total = 0L
        repeat(count) {
            val word = readU32(bytes, p); p += 4        // reference_type(1) + referenced_size(31)
            val referenceType = (word ushr 31).toInt()
            val referencedSize = word and 0x7FFFFFFFL
            p += 8                                      // subsegment_duration + SAP 字段
            if (referenceType == 1) return null         // 指向别的 sidx（分层索引）⇒ 放弃
            if (referencedSize <= 0 || referencedSize > MAX_SEGMENT_BYTES) return null
            total += referencedSize
            if (total > MAX_TOTAL_BYTES) return null
            out.add(Segment(cursor, referencedSize))
            cursor += referencedSize
        }
        return out
    }

    /**
     * 把"请求区间 [start, start+length)"按真实段边界切成 ≤ [maxChunks] 块（每块 = 若干整段的并集）。
     *
     * 合并策略：按 `ceil(length / maxChunks)` 作为目标块大小，遇到"从当前块起点算已累计到目标"
     * 的段边界就下刀；最多下 [maxChunks]-1 刀 ⇒ 不会把一次请求拆成几百条。
     *
     * @return 切好的块；**没有可用段边界 / 只能切出 1 块**时返回 null（调用方回退均分；
     *         1 块会让"并发"退化成单连接，属于净损失）
     */
    fun planChunks(segments: List<Segment>, start: Long, length: Long, maxChunks: Int): List<Range>? {
        if (segments.isEmpty() || length <= 0L || maxChunks <= 1) return null
        val end = start + length
        val cuts = segments.asSequence()
            .map { it.start }
            .filter { it > start && it < end }
            .distinct()
            .sorted()
            .toList()
        if (cuts.isEmpty()) return null
        val target = (length + maxChunks - 1) / maxChunks
        val out = ArrayList<Range>(maxChunks)
        var cur = start
        for (cut in cuts) {
            if (out.size >= maxChunks - 1) break
            if (cut - cur >= target) {
                out.add(Range(cur, cut - cur))
                cur = cut
            }
        }
        out.add(Range(cur, end - cur))
        return if (out.size < 2) null else out
    }

    private fun readU16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun readU32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, i: Int): Long =
        (readU32(b, i) shl 32) or readU32(b, i + 4)
}
