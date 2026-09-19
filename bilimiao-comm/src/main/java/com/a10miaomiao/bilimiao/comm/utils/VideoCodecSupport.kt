package com.a10miaomiao.bilimiao.comm.utils

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaCodecList
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * **按设备解码能力挑视频编码**（对齐上游 Bilibili-thread-ripper 0.9.2.0 的"编码跟随播放策略"）。
 *
 * 为什么需要它：B 站同一个清晰度会给**多条不同编码**的流（AV1 / HEVC / AVC），
 * 我们以前直接取"接口返回的第一条" —— 而接口在 `fnval=4048`（含 AV1）时通常**把 AV1 排在前面**。
 * 新机型（骁龙 8 Gen2+ / 天玑 9000+ 等）有 AV1 硬解，没问题；
 * 但**没有 AV1 硬解的老机器**只能软解：CPU 拉满、发热、掉帧，个别机型干脆解不出来 → **黑屏**。
 *
 * 选法（故意"兼容优先"而不是"参数优先"）：
 *  1. 有**硬件**解码器时：HEVC → AVC → AV1（HEVC 兼容性足够好、同画质码率比 AVC 低）；
 *  2. 只有软解时：AVC → HEVC → AV1（H.264 软解最省 CPU）；
 *  3. 三条都解不了 → 返回 null，调用方保持原样（不改变现状）。
 *
 * 结果按 MIME 缓存，播放时零开销。
 */
object VideoCodecSupport {

    private val decoderCache = ConcurrentHashMap<String, Int>()

    /** codecs 字符串（`hev1.1.6.L150.90` / `avc1.640032` / `av01.0.08M.08`）→ MIME */
    fun mimeOf(codecs: String?): String? {
        val c = codecs?.trim()?.lowercase() ?: return null
        return when {
            c.startsWith("avc1") || c.startsWith("avc3") -> MediaFormat.MIMETYPE_VIDEO_AVC
            c.startsWith("hev1") || c.startsWith("hvc1") -> MediaFormat.MIMETYPE_VIDEO_HEVC
            c.startsWith("av01") -> MediaFormat.MIMETYPE_VIDEO_AV1
            c.startsWith("vp09") -> MediaFormat.MIMETYPE_VIDEO_VP9
            else -> null
        }
    }

    /** B 站的 codecid → MIME（7=AVC、12=HEVC、13=AV1） */
    fun mimeOfCodecid(codecid: Int): String? = when (codecid) {
        7 -> MediaFormat.MIMETYPE_VIDEO_AVC
        12 -> MediaFormat.MIMETYPE_VIDEO_HEVC
        13 -> MediaFormat.MIMETYPE_VIDEO_AV1
        else -> null
    }

    /**
     * 这个 MIME 在本机的"优先分"：越高越该选；**-1 = 解不了**。
     * 硬解 > 软解；硬解内部 HEVC > AVC > AV1；软解内部 AVC > HEVC > AV1。
     */
    fun score(mime: String?): Int {
        val m = mime ?: return -1
        val cached = decoderCache[m]
        if (cached != null) return cached
        val hw = hasDecoder(m, hardwareOnly = true)
        val any = hw || hasDecoder(m, hardwareOnly = false)
        val s = when {
            !any -> -1
            // 有硬解：跟 B 站播放器"默认"策略一致 —— AV1 > HEVC > AVC（AV1 同画质最省流量）
            hw -> when (m) {
                MediaFormat.MIMETYPE_VIDEO_AV1 -> 60
                MediaFormat.MIMETYPE_VIDEO_HEVC -> 55
                MediaFormat.MIMETYPE_VIDEO_AVC -> 50
                else -> 45
            }
            else -> when (m) {
                MediaFormat.MIMETYPE_VIDEO_AVC -> 30
                MediaFormat.MIMETYPE_VIDEO_HEVC -> 20
                MediaFormat.MIMETYPE_VIDEO_AV1 -> 10
                else -> 5
            }
        }
        decoderCache[m] = s
        return s
    }

    /** 一批候选（各自的 codecs 字符串）里挑最合适的那条；**都解不了返回 null** */
    fun bestIndex(codecsList: List<String?>): Int? {
        var bestIdx: Int? = null
        var bestScore = -1
        codecsList.forEachIndexed { i, c ->
            val s = score(mimeOf(c))
            if (s > bestScore) {
                bestScore = s
                bestIdx = i
            }
        }
        return if (bestScore >= 0) bestIdx else null
    }

    /** 一批候选（各自的 codecid）里挑最合适的那条；**都解不了返回 null** */
    fun bestIndexOfCodecids(codecidList: List<Int>): Int? {
        var bestIdx: Int? = null
        var bestScore = -1
        codecidList.forEachIndexed { i, id ->
            val s = score(mimeOfCodecid(id))
            if (s > bestScore) {
                bestScore = s
                bestIdx = i
            }
        }
        return if (bestScore >= 0) bestIdx else null
    }

    /**
     * 从"同一清晰度的多条候选（不同编码）"里挑一条本机能解的。
     *
     * 规则：优先在**同清晰度**的候选里挑（HEVC > AVC > AV1，且硬解优先）；
     * 都解不了就退回同清晰度的第一条；连同清晰度都没有才退回全部候选的第一条
     * —— **绝不因为挑编码而把视频搞成放不出来**。
     */
    fun <T> pickBest(
        list: List<T>,
        qualityOf: (T) -> Int,
        codecsOf: (T) -> String?,
        quality: Int,
    ): T? {
        if (list.isEmpty()) return null
        val sameQuality = list.filter { qualityOf(it) == quality }
        val pool = if (sameQuality.isNotEmpty()) sameQuality else list
        val idx = bestIndex(pool.map { codecsOf(it) })
        return if (idx != null) pool[idx] else pool.first()
    }

    /** 本机有没有这个 MIME 的解码器（[hardwareOnly] = 只要硬解） */
    private fun hasDecoder(mime: String, hardwareOnly: Boolean): Boolean = runCatching {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        list.codecInfos.any { info ->
            if (info.isEncoder) return@any false
            if (hardwareOnly && !isHardware(info)) return@any false
            info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }.getOrDefault(false)

    /** 硬解判定：API 29+ 用系统标志；老系统按名字猜（`c2.android.` / `OMX.google.` / `OMX.ffmpeg.` 是软解） */
    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching { info.isHardwareAccelerated }.getOrDefault(true)
        }
        val n = info.name.lowercase()
        return !(n.startsWith("c2.android.") || n.startsWith("omx.google.") || n.startsWith("omx.ffmpeg.") ||
            n.contains("sw.decoder") || n.contains("software"))
    }
}
