package com.a10miaomiao.bilimiao.comm.live

import android.graphics.Rect
import android.util.Log
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 直播播放页的**事件级诊断日志**（2026-09-26 本轮新增，**纯观测、不改行为**）。
 *
 * ## 为什么要有它
 * 今晚修直播页的 bug（PiP 黑屏 / 转屏空白 / 黑条 / 字号 / insets 键盘 / 状态栏 / 回 App 落错页…）
 * 每一轮都靠用户肉眼看 + 截图反馈 —— 极慢，而且"到底哪一步判成了什么"全靠猜。
 * 这里把**关键状态变化**（生命周期、形态、几何、播放状态、PiP 参数、页面切换、回 App 恢复）
 * 记成一条条事件，之后用 `adb logcat` / `adb pull` **只读抓取**即可复盘。
 *
 * ## 两条通道（同一批事件）
 * 1. `Log.i(TAG, …)` —— TAG 固定为 [TAG]（`Miao>LivePage`），
 *    ★**直接用 `android.util.Log`，不走 `MiaoLogger`**：后者在 release 包里只放行 ERROR
 *    （`MiaoLogger.kt` 那句 `if (!BuildConfig.DEBUG && level != Log.ERROR) return`），
 *    而诊断恰恰要在正式包上做；
 * 2. 追加写文件 `Android/data/<包名>/files/live_debug.log` —— **外置私有目录，adb 可直接 pull**
 *    （与 `LiveDanmakuTrace` / `AntifraudDiag` 同一套做法；内部 filesDir 非 root 读不到）。
 *
 * ## 量的控制（"只在事件发生时写"）
 * · 只在**状态变化 / 判定发生 / 动作起止**时写，**绝不逐帧、绝不每次 MOVE**；
 * · 会随布局反复触发的路径（尺寸、版式、底栏、insets、PiP 参数）用 [noteIfChanged]：
 *   同一"槽位"的签名没变就一个字节都不写 —— 于是"量了一次但结果和上次一样"不会刷屏；
 * · 落盘走**单线程守护线程 + 异步投递**（调用线程只做一次 `execute`，永不阻塞主线程），
 *   **不引入任何新依赖**；
 * · 文件超过 [MAX_FILE_BYTES] 就**滚动**：当前文件改名为 `live_debug.log.1`（旧的先删），
 *   新文件继续写 —— 永不无限增长。
 *
 * ## 怎么关掉（一个布尔）
 * ```kotlin
 * LivePageTrace.enabled = false          // 运行时：logcat 与文件一起关
 * ```
 * 或者把本对象里 [enabled] 的初值从 `true` 改成 `false`（**只改这一行**，全局关）。
 * 只想关文件、保留 logcat：把 [fileEnabled] 改成 `false`。
 *
 * ## 一次会话怎么读
 * ```
 * adb logcat -s 'Miao>LivePage'
 * adb pull /sdcard/Android/data/com.a10miaomiao.bilimiao.mod/files/live_debug.log
 * ```
 * 每行格式：`MM-dd HH:mm:ss.SSS 事件名 key=value key=value …`（空格一律转成 `_`，一条事件一行）。
 */
object LivePageTrace {

    /** logcat 统一 TAG（`adb logcat -s 'Miao>LivePage'`）。 */
    const val TAG = "Miao>LivePage"

    /**
     * ★**总开关**：`false` = logcat 与文件**都不写**（一个布尔全局关掉）。
     *   默认 `true`（本轮用户明确要求"加上去"）。
     */
    @Volatile
    var enabled: Boolean = false

    /** 文件通道开关（[enabled] 为 true 时才有意义）；关掉 = 只留 logcat。 */
    @Volatile
    var fileEnabled: Boolean = true

    /** 落盘文件名（`getExternalFilesDir(null)/live_debug.log`）。 */
    private const val FILE_NAME = "live_debug.log"

    /** 单文件体积上限：超过就滚动到 `live_debug.log.1`，避免长期使用写满用户存储。 */
    private const val MAX_FILE_BYTES = 512 * 1024L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "live-page-trace").apply { isDaemon = true }
    }

    /** 只在 IO 线程用（SimpleDateFormat 非线程安全） */
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    /** [noteIfChanged] 的"槽位 → 上次签名"表（主线程写、量极小） */
    private val lastSignatures = ConcurrentHashMap<String, String>()

    /**
     * 记一条事件：`事件名 key=value …`。
     *
     * @param event 事件名（建议 `区域.动作`，例如 `onResume.enter` / `immersive.apply`）
     * @param kv    字段；值里的空格/换行会被转成 `_`（保证一条事件一行）
     */
    fun note(event: String, vararg kv: Pair<String, Any?>) {
        if (!enabled) return
        val line = buildLine(event, kv)
        // ① logcat（release 也生效：直接用 android.util.Log）
        runCatching { Log.i(TAG, line) }
        // ② 文件（异步，绝不阻塞调用线程）
        if (!fileEnabled) return
        val at = System.currentTimeMillis()
        runCatching { io.execute { appendLine(at, line) } }
    }

    /**
     * 同 [note]，但**同一槽位的签名没变就不写**。
     *
     * 给那些"可能被布局/insets 反复调到、但只有真的变了才值得记"的调用点
     * （尺寸、竖屏版式、底栏、insets、PiP 参数）。签名请取"能代表这次结果的那几个数"。
     */
    fun noteIfChanged(slot: String, signature: String, event: String, vararg kv: Pair<String, Any?>) {
        if (!enabled) return
        if (lastSignatures.put(slot, signature) == signature) return
        note(event, *kv)
    }

    /** 矩形文本：`l,t,r,b`；null → `null`。 */
    fun rect(r: Rect?): String =
        if (r == null) "null" else "${r.left},${r.top},${r.right},${r.bottom}"

    /** 会话分隔行（进直播间时写一条，事后可按会话切分）。 */
    fun section(title: String, vararg kv: Pair<String, Any?>) {
        note("========== $title ==========", *kv)
    }

    // ------------------------------------------------------------------
    // 内部：拼行 + 落盘
    // ------------------------------------------------------------------

    private fun buildLine(event: String, kv: Array<out Pair<String, Any?>>): String {
        val sb = StringBuilder(event.length + kv.size * 24 + 8)
        sb.append(event)
        for ((k, v) in kv) {
            sb.append(' ').append(k).append('=').append(clean(v))
        }
        return sb.toString()
    }

    private fun clean(value: Any?): String {
        val s = value?.toString() ?: return "-"
        if (s.isEmpty()) return "-"
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                ' ', '\n', '\r', '\t' -> sb.append('_')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun appendLine(atMs: Long, line: String) {
        runCatching {
            val f = resolveFile() ?: return
            if (f.length() > MAX_FILE_BYTES) roll(f)
            f.appendText("${timeFormat.format(Date(atMs))} $line\n")
        }
    }

    /**
     * `Android/data/<包名>/files/live_debug.log`。
     *
     * ★用**外置**私有目录而不是内部 `filesDir`：内部目录在非 root 机器上 adb 读不到，
     *   而这条通道存在的唯一意义就是"出问题时能 pull 出来复盘"。
     * ★只在 IO 线程调用（`file` 字段因此没有竞态）。
     */
    private fun resolveFile(): File? {
        file?.let { return it }
        val resolved = runCatching {
            BilimiaoCommApp.commApp.app.getExternalFilesDir(null)?.resolve(FILE_NAME)
        }.getOrNull() ?: return null
        file = resolved
        runCatching { Log.i(TAG, "trace.file path=${resolved.absolutePath}") }
        runCatching {
            resolved.appendText(
                "${timeFormat.format(Date())} ===== live_debug.log 开始" +
                    "（上限 ${MAX_FILE_BYTES / 1024}KB，超出滚动为 $FILE_NAME.1）=====\n",
            )
        }
        return resolved
    }

    /** 滚动：当前文件 → `live_debug.log.1`（旧的先删）；改名失败就删掉重来。 */
    private fun roll(f: File) {
        val old = File(f.parentFile, "$FILE_NAME.1")
        runCatching { if (old.exists()) old.delete() }
        val renamed = runCatching { f.renameTo(old) }.getOrDefault(false)
        if (!renamed) runCatching { f.delete() }
    }
}
