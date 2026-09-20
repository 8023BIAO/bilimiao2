package cn.a10miaomiao.bilimiao.compose.components.antifraud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.a10miaomiao.bilimiao.comm.antifraud.AntifraudState

/**
 * 「正在监控中的评论」的共享状态。
 *
 * 为什么需要它：自动复查要盯好几分钟（默认 5 分钟、每 30 秒一次），
 * 用户离开评论区之后**完全看不到进度**，只能干等一个最终弹窗（用户原话：
 * "要不然它就算有提示的话，中间我不知道它进度的怎么样"）。
 * 所以这里把每条正在监控的评论暴露成一个可观察列表，设置页里实时显示：
 * 哪条视频下的哪条评论、第几次/共几次、已经盯了多久/共多久、上一次结论是什么。
 *
 * 数据只在内存里，进程结束就没了（不需要持久化 —— 监控本来就是一次性的）。
 */
object AntifraudMonitor {

    /** 正在监控的评论（按开始时间顺序） */
    val sessions: SnapshotStateList<AntifraudMonitorSession> = mutableStateListOf()

    fun start(
        label: String,
        rpid: Long,
        firstWaitMs: Long,
        recheckEnabled: Boolean,
        recheckTotalMs: Long,
        planned: Int,
    ): AntifraudMonitorSession {
        val s = AntifraudMonitorSession(
            key = "$label-$rpid",
            label = label,
            rpid = rpid,
            startedAt = System.currentTimeMillis(),
            firstWaitMs = firstWaitMs,
            recheckEnabled = recheckEnabled,
            recheckTotalMs = recheckTotalMs,
            planned = planned,
        )
        sessions.add(s)
        return s
    }

    fun finish(session: AntifraudMonitorSession) {
        session.finished = true
        sessions.remove(session)
    }
}

/** 一条正在监控的评论 */
class AntifraudMonitorSession(
    val key: String,
    /** 显示用：视频 BV 号（或 oid） */
    val label: String,
    val rpid: Long,
    /** 开始时间（毫秒时间戳）—— 进度条按它算"已经盯了多久" */
    val startedAt: Long,
    /** 首查要等多久（5 秒 / 带图 20 秒） */
    val firstWaitMs: Long,
    val recheckEnabled: Boolean,
    /** 复查总时长；0 = 只查一次 */
    val recheckTotalMs: Long,
    planned: Int,
) {
    /** 第几次（1 = 首查） */
    var attempt by mutableStateOf(1)

    /** 预计一共查几次 */
    var planned by mutableStateOf(planned)

    /** 上一次的结论（没出结果前是"等待首查"） */
    var lastState by mutableStateOf<AntifraudState?>(null)

    /** 上一次的说明文字 */
    var lastDetail by mutableStateOf("等待首查（${firstWaitMs / 1000} 秒后开始）")

    var finished by mutableStateOf(false)

    /** 已监控毫秒数 */
    fun elapsedMs(now: Long): Long = (now - startedAt).coerceAtLeast(0L)

    /** 总时长：首查等待 + 复查总时长 */
    val totalMs: Long get() = firstWaitMs + if (recheckEnabled) recheckTotalMs else 0L

    /** 进度 0f~1f */
    fun progress(now: Long): Float {
        val t = totalMs
        if (t <= 0L) return 1f
        return (elapsedMs(now).toFloat() / t).coerceIn(0f, 1f)
    }
}
