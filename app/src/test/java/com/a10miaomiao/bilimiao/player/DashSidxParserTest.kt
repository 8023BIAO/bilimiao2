package com.a10miaomiao.bilimiao.player

import com.a10miaomiao.bilimiao.comm.delegate.player.DashSidxParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `sidx` 解析与"按真实分段边界切块"的纯 JVM 单测（不需要设备）。
 *
 * 样例都是按 ISO/IEC 14496-12 §8.16.3 的字节布局**手工构造**的（本机没有真实 m4s 样本），
 * 字段宽度/顺序与规范逐条对齐：box size/type → version+flags → reference_ID → timescale →
 * earliest_presentation_time → first_offset → reserved → reference_count → N×(12 字节参考条目)。
 */
class DashSidxParserTest {

    // ───────────── 构造样例 ─────────────

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u32(v: Long) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )
    private fun u64(v: Long) = u32(v ushr 32) + u32(v and 0xFFFFFFFFL)

    /** 一个普通 box（styp 之类），用来验证"扫描而非假设第一个框就是 sidx" */
    private fun plainBox(type: String, body: ByteArray) =
        u32((8 + body.size).toLong()) + type.toByteArray(Charsets.US_ASCII) + body

    /**
     * 造一个 sidx。
     * @param refs (reference_type, referenced_size) 列表
     * @param firstOffset first_offset 字段
     * @param declaredSize 覆盖 box size 字段（测"框越界"用）
     */
    private fun sidx(
        refs: List<Pair<Int, Int>>,
        version: Int = 0,
        firstOffset: Long = 0,
        declaredSize: Long? = null,
        omitRefs: Int = 0,          // 尾部少写几条（测"条目被截断"）
        leading: ByteArray = ByteArray(0),
    ): ByteArray {
        val refBytes = refs.dropLast(omitRefs).fold(ByteArray(0)) { acc, (type, size) ->
            acc + u32(((type.toLong() and 1L) shl 31) or (size.toLong() and 0x7FFFFFFFL)) +
                u32(1_000L) + u32(0L)
        }
        val head = if (version == 0) {
            u32(0x00000000L) + u32(0x00000000L)          // version+flags, reference_ID
        } else {
            u32(0x01000000L) + u32(0x00000000L)
        }
        val body = head + u32(9_000L) /* timescale */ +
            (if (version == 0) u32(0L) else u64(0L)) +                 // earliest_presentation_time
            (if (version == 0) u32(firstOffset) else u64(firstOffset)) + // first_offset
            u16(0) + u16(refs.size) + refBytes
        val size = declaredSize ?: (8L + body.size)
        return leading + u32(size) + "sidx".toByteArray(Charsets.US_ASCII) + body
    }

    private fun assertSegments(expected: List<Pair<Long, Long>>, actual: List<DashSidxParser.Segment>?) {
        assertEquals(expected.map { DashSidxParser.Segment(it.first, it.second) }, actual)
    }

    // ───────────── ① 正常 sidx（version 0 / 1）─────────────

    @Test
    fun parse_v0_offsetsFollowFirstOffsetAndReferencedSize() {
        val refs = listOf(0 to 100, 0 to 200, 0 to 50)
        val bytes = sidx(refs, version = 0, firstOffset = 16)
        // box 总长 = 8 + 24 + 12*3 = 68；first_offset=16 ⇒ 第一段起点 = 68+16 = 84
        assertSegments(
            listOf(84L to 100L, 184L to 200L, 384L to 50L),
            DashSidxParser.parse(bytes),
        )
    }

    @Test
    fun parse_v1_uses64BitTimeAndOffsetFields() {
        val refs = listOf(0 to 128, 0 to 256)
        val bytes = sidx(refs, version = 1, firstOffset = 32)
        // box 总长 = 8 + 32 + 12*2 = 64；第一段起点 = 64+32 = 96
        assertSegments(listOf(96L to 128L, 224L to 256L), DashSidxParser.parse(bytes))
    }

    @Test
    fun parse_skipsLeadingBoxesAndFindsSidx() {
        val leading = plainBox("styp", ByteArray(8) { 1 })
        val bytes = sidx(listOf(0 to 64), firstOffset = 0, leading = leading)
        // 前导框 = 8+8 = 16 字节；sidx 总长 = 8+24+12 = 44 ⇒ 段起点 = 16+44 = 60
        assertSegments(listOf(60L to 64L), DashSidxParser.parse(bytes))
    }

    // ───────────── ② 各种不可信输入 ⇒ 一律 null（调用方回退均分）─────────────

    @Test
    fun parse_missingSidx_returnsNull() {
        val bytes = plainBox("styp", ByteArray(8)) + plainBox("moof", ByteArray(16))
        assertNull(DashSidxParser.parse(bytes))
    }

    @Test
    fun parse_truncatedReferences_returnsNull() {
        // 声明 3 条参考、只写 1 条 ⇒ 条目区越界
        val bytes = sidx(listOf(0 to 10, 0 to 20, 0 to 30), omitRefs = 2)
        assertNull(DashSidxParser.parse(bytes))
    }

    @Test
    fun parse_truncatedBox_returnsNull() {
        val full = sidx(listOf(0 to 10, 0 to 20))
        assertNull(DashSidxParser.parse(full.copyOf(full.size - 6)))       // 尾巴被切掉
        assertNull(DashSidxParser.parse(full, size = full.size - 6))       // 有效字节数更短
    }

    @Test
    fun parse_referenceType1_returnsNull() {
        // 第二条 reference_type=1（指向别的 sidx）⇒ 分层索引，放弃
        assertNull(DashSidxParser.parse(sidx(listOf(0 to 10, 1 to 20))))
    }

    @Test
    fun parse_zeroReferences_returnsNull() {
        assertNull(DashSidxParser.parse(sidx(emptyList())))
    }

    @Test
    fun parse_hugeDeclaredSize_returnsNull() {
        // 框声明 4GB（远超头部缓冲）⇒ 不越界读取，直接放弃
        assertNull(DashSidxParser.parse(sidx(listOf(0 to 10), declaredSize = 0xFFFFFFFFL)))
    }

    @Test
    fun parse_badVersion_returnsNull() {
        val bytes = sidx(listOf(0 to 10))
        bytes[8] = 7                                  // version=7
        assertNull(DashSidxParser.parse(bytes))
    }

    @Test
    fun parse_emptyOrZeroSize_returnsNull() {
        assertNull(DashSidxParser.parse(ByteArray(0)))
        assertNull(DashSidxParser.parse(ByteArray(64)))
    }

    // ───────────── ③ 按段边界切块（合并策略）─────────────

    private fun segs(vararg pairs: Pair<Long, Long>) =
        pairs.map { DashSidxParser.Segment(it.first, it.second) }

    @Test
    fun planChunks_cutsOnlyAtSegmentBoundaries_andCapsAtMaxChunks() {
        // 段：0-100 / 100-300 / 300-600 / 600-1000，请求整文件、最多 2 块
        val segments = segs(0L to 100L, 100L to 200L, 300L to 300L, 600L to 400L)
        val ranges = DashSidxParser.planChunks(segments, start = 0, length = 1000, maxChunks = 2)
        assertEquals(
            listOf(DashSidxParser.Range(0, 600), DashSidxParser.Range(600, 400)),
            ranges,
        )
    }

    @Test
    fun planChunks_moreSegmentsThanWorkers_mergesToWorkerCount() {
        // 100 个 10 字节的段、最多 4 块 ⇒ 合并成 4 块（250/250/250/250）
        val segments = (0 until 100).map { DashSidxParser.Segment(it * 10L, 10L) }
        val ranges = DashSidxParser.planChunks(segments, 0, 1000, maxChunks = 4)
        assertEquals(
            listOf(
                DashSidxParser.Range(0, 250), DashSidxParser.Range(250, 250),
                DashSidxParser.Range(500, 250), DashSidxParser.Range(750, 250),
            ),
            ranges,
        )
    }

    @Test
    fun planChunks_withinRequestWindow_only() {
        // 请求 [100,300)：只有 250 这个段边界落在里面 ⇒ 切 2 块，且只覆盖请求区间
        val segments = segs(0L to 100L, 100L to 150L, 250L to 150L, 400L to 100L)
        val ranges = DashSidxParser.planChunks(segments, start = 100, length = 200, maxChunks = 4)
        assertEquals(listOf(DashSidxParser.Range(100, 150), DashSidxParser.Range(250, 50)), ranges)
    }

    @Test
    fun planChunks_noUsableCut_returnsNull() {
        // 只有一段覆盖整个请求区间 ⇒ 没有真实边界可用 ⇒ null（回退均分）
        assertNull(DashSidxParser.planChunks(segs(0L to 1000L), 0, 1000, 4))
        // 边界都在请求区间之外 ⇒ null
        assertNull(DashSidxParser.planChunks(segs(0L to 10L, 500L to 500L), 100, 400, 4))
    }

    @Test
    fun planChunks_singleChunkResult_returnsNull() {
        // 唯一边界离起点太近（不足目标块大小）⇒ 只能切出 1 块 ⇒ null（并发会退化成单连接）
        val segments = segs(0L to 10L, 10L to 990L)
        assertNull(DashSidxParser.planChunks(segments, 0, 1000, 16))
    }

    @Test
    fun planChunks_degradesSafelyOnBadArguments() {
        assertNull(DashSidxParser.planChunks(emptyList(), 0, 1000, 4))
        assertNull(DashSidxParser.planChunks(segs(0L to 500L), 0, 0, 4))
        assertNull(DashSidxParser.planChunks(segs(0L to 500L), 0, 1000, 1))
    }
}
