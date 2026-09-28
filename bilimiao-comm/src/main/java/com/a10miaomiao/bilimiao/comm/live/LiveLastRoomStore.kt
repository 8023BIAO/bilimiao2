package com.a10miaomiao.bilimiao.comm.live

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.WeakHashMap

/**
 * 「上次停在哪个直播间」的记录 + 「回 App 仍停在直播间」的**确定性**恢复（★2026-09-26 本轮）。
 *
 * ## 为什么要有它（旧实现为什么不可靠）
 * 旧实现是 `LivePlayerActivity.ReturnToLiveGuard`：一个**进程级**字段，进 PiP 时武装、
 * 别的页面 resume 时按记下的房间号把直播间重新拉起来。它把成败押在**两个时机**上：
 * ① **直播间 Activity 还活着** —— 而"点桌面图标回 App"这条路恰恰会把 PiP 任务收掉、finish 本页
 *   （真机任务栈证据见 `LivePlayerActivity` 里守卫那段注释），于是"本页 onDestroy 解除武装"与
 *   "守卫被触发"谁先谁后就决定了成不成（间歇性的直接来源）；
 * ② **主界面 resume 的时机** —— 用户是"退桌面（没进 PiP）→ 回软件"时，直播间根本没进过 PiP，
 *   守卫压根没武装，这条路**永远不触发**。
 *
 * ## 现在的规则（只看状态，不看时机）
 * ```
 * 记录  ← 用户带着直播间离开 App：
 *          · onUserLeaveHint（按 Home / 切走 —— 本页最早的"用户要离开"信号）
 *          · 真退后台的 onStop（熄屏 / 来电 / 被别的 App 抢前台…不走 hint 的那些路）
 *        → DataStore 写 [SettingPreferences.LiveLastRoom] + [SettingPreferences.LiveLastRoomRestore]
 * 清理  ← ① 用户主动退出直播间（返回键 / 顶栏返回 → exitPage）
 *          ② App 从**非直播间**页面退到后台（点播页离开 → 不会把用户拉去直播）
 *          ③ 直播间自己回到全屏前台（人已经在直播间里了 → 防循环）
 * 恢复  ← App 真的"回到软件"了（前台 resumed 的是主界面 [HOST_ACTIVITY]）+ 记录还在
 *          + 没有活着的直播间实例
 *        → 用字面量约定把那个直播间开起来（[LIVE_PLAYER_ACTIVITY] + [EXTRA_ROOM_ID]），
 *          **取走即消费**（一次性；不会因为直播间自己 resume 再触发第二次）
 * ```
 * 判据全是"当下的状态"，没有任何一条依赖"谁先回调"：恢复的触发点有"有 Activity resume"和
 * "直播间销毁"两个，先到的那个先判、后到的看到记录已被消费就什么都不做 —— 所以 PiP 小窗被系统
 * 收掉与主界面 resume 谁先谁后都不影响结果。
 *
 * ## 与 ReturnToLiveGuard（旧守卫）如何共存
 * 守卫保留为**兜底**，但它不再自己说了算：拉起之前先来 [consumePendingForGuard] 问一次
 * （同一套判据、同一个一次性记录）—— 于是两条路**只可能有一条**真的把直播间开起来，
 * 不会叠出两个直播间。
 *
 * ## 为什么"记录"要落 DataStore，"决策"却用内存
 * 记录要跨进程存活（用户离开很久、进程被系统回收后重新打开 App，位置记忆仍然成立），
 * 所以写 DataStore；而"回到前台该不该恢复"跑在**主线程**的回调里（`onStart`/`onResume`/
 * `onDestroy`），不能阻塞去读盘，所以进程内维护一份**权威内存镜像**（[memoryAuthoritative]）：
 * 本进程写过就以内存为准（O(1) 同步决策）；只有"进程刚起、本进程还没写过"时才异步问一次
 * DataStore（[seedFromDataStore]），问完再决策。
 *
 * ## 并发（这页代码里最容易写错的地方）
 * · 状态的两个字段（房间号 + "应当恢复"）**必须一起变**，所以打包成一个不可变对象 [Pending]，
 *   只用一个 `@Volatile` 字段承载 —— 主线程的读/写都是原子的，不会读到"半个状态"；
 * · 落盘走**单消费者 + CONFLATED 通道**（[writeChannel]）：主线程只负责 `trySend` 最新状态
 *   （不阻塞、不挂起），IO 侧一个协程按顺序写 DataStore。合并语义保证"**最后一次落盘 = 最后那个状态**"，
 *   不会出现"旧值后写"把新值盖掉（记录/清理几乎同时发生时也不会）。
 *
 * ★本对象**只持有 applicationContext 与"当前 resumed 的 Activity"**（后者在 onPause 就清掉），
 *   不给进程留 Activity 泄漏 —— 与旧守卫"只记值、不持有 Activity"的约束一致。
 */
object LiveLastRoomStore {

    /** 直播间 Activity 的类名。★字面量约定：comm 模块不能反向 import app 模块的类（依赖方向 app → comm） */
    private const val LIVE_PLAYER_ACTIVITY = "com.a10miaomiao.bilimiao.LivePlayerActivity"

    /** 房间号 extra 的键。★同样是与 `LivePlayerActivity.EXTRA_ROOM_ID` 的字面量约定 */
    private const val EXTRA_ROOM_ID = "roomId"

    /**
     * App 主界面（宿主 Activity）的类名。★同样是字面量约定。
     *
     * 恢复**只在主界面回到前台时**发生 —— 这一条把"回软件"钉死在用户语义上：
     * · 点桌面图标 / 点最近任务里那张主卡片 → 前台就是 MainActivity ✓ 恢复；
     * · 若是从外部链接直接打开了点播播放页（VideoPlayerActivity 在前台），那不是"回软件"，
     *   不该把用户从点播页拽去直播间（用户明确画过这条红线）。
     */
    private const val HOST_ACTIVITY = "com.a10miaomiao.bilimiao.MainActivity"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val attachLock = Any()

    /**
     * "上次停在哪个直播间"这份记录（不可变的两个字段，一起读、一起写）。
     *
     * @param roomId 入口给的原样房间号（短号/真实号都行）；null = 没有记录
     * @param restore true = "应当恢复"；false = 记录已作废
     */
    private class Pending(val roomId: String?, val restore: Boolean) {
        /** 真的能拿来恢复吗（两个字段缺一不可） */
        val usable: Boolean get() = restore && !roomId.isNullOrBlank()
    }

    /** 当前记录（**内存权威**）。只用主线程读写；@Volatile 保证落盘协程读到的不是旧值 */
    @Volatile private var pending = Pending(null, false)

    /**
     * 落盘队列：只放"最新状态"（CONFLATED = 新的顶掉还没写的旧的），IO 侧单消费者按顺序写。
     * 主线程用 [Channel.trySend] 投递 —— 不挂起、不阻塞（生命周期回调里绝不能等 IO）。
     */
    private val writeChannel = Channel<Pending>(Channel.CONFLATED)

    // ── 宿主（Application / 进程级）────────────────────────────────────────
    @Volatile private var appContext: Context? = null
    @Volatile private var observerRegistered = false

    /** 本进程是否写过这份记录（写过 = 内存就是权威，不再被 DataStore 的旧值覆盖） */
    @Volatile private var memoryAuthoritative = false

    /** 冷启动那次 DataStore 读取是否在路上（防重复发起） */
    @Volatile private var seedInFlight = false

    // ── 前台状态（进程级，只记状态不记 Activity）────────────────────────────
    /** 活着的直播间实例数（onCreate +1 / onDestroy -1）。> 0 = "还有一个直播间在（比如 PiP 小窗）" */
    @Volatile private var livePageCount = 0

    /**
     * ★task-55：**整任务已被划掉**（`PlaybackService.onTaskRemoved` → [onTaskRemoved]）之后，抑制补记。
     *
     * 为什么需要它：本任务给直播页加了"**系统清栈也要记得住**"的补记（见
     * `LivePlayerActivity.onDestroy`）—— 那一刻页面同样 `isFinishing == true`，与"任务被划掉"
     * 在页面侧**不可区分**。而任务移除时 [onTaskRemoved] 会 `clear()`；如果"服务先收到 onTaskRemoved、
     * 页面随后才 onDestroy"，补记就会把刚清掉的账又记回来 ⇒ 用户划掉 App 再打开又冒出直播间
     * （正是他骂过的"保活强得离谱"）。所以这里留一个抑制位：任务被划掉的这一刻起不再记账，
     * 直到**有新的直播间页面被创建**（[onLivePageCreated] 复位 = 新的一局）。
     *
     * ★只在主线程读写（服务回调与页面钩子都在主线程），与既有字段同一套约定。
     */
    @Volatile private var taskRemovedSuppressRecord = false

    /**
     * ★task-55：账本里现在**有没有**"应当恢复"的记录（只读）。
     *
     * 给直播页在"系统清栈"那一刻判"要不要补记"用：账本非空通常意味着"用户确实是带着直播间
     * 离开 App 的"（按 Home / PiP 那条路，[onLivePageLeavingApp] 已经记过），那种情况**不补记** ——
     * 保持"同一时刻只有一条账"这条既有契约，PiP 叉小窗等路径的行为才一个字节都不变。
     */
    fun hasPendingRestore(): Boolean = pending.usable

    /** 当前 resumed 的 Activity 类名；null = 进程里没有前台页面（App 在后台） */
    @Volatile private var resumedActivity: String? = null

    /**
     * 当前 resumed 的那个 Activity（**只在 resumed 期间持有**：onPause / onDestroy 立刻清）。
     * 恢复时要一个 Activity 来 `startActivity`（NEW_TASK 落进"刚刚回到前台的那个任务"），
     * 但绝不长期持有 —— 清得很干净，不给进程留泄漏。
     *
     * ★task-47：它现在表示"**最近一次 resume、且此刻仍在 resumed 的那个页面**"，取值由
     *   [resumedSet] 兜底（多窗口下同时可能有多个页面 RESUMED，谁 pause 了都不能把别人一起忘掉）。
     */
    @Volatile private var resumedHost: Activity? = null

    /**
     * ★task-47：**当前仍处于 RESUMED 的全部页面**（弱引用，避免留 Activity）。
     *
     * 为什么需要它（用户实测 bug 的根因）：Android 10+ 的多窗口允许**多个 Activity 同时 RESUMED**
     * （典型 = 系统小窗/分屏里"直播间"与"主界面"各占一个窗口）。原来的前台状态是**单槽**的
     * （[resumedHost] / [resumedActivity]）：主界面先 resume（记成前台）→ 小窗里的直播间也 resume
     * （覆盖成前台）→ **直播间被系统收掉时它自己的 onPause 把槽清空** ⇒ 紧接着 `onDestroy` 触发的
     * [evaluateRestore] 看到"没有前台宿主"直接跳过，而主界面**本来就是 resumed、不会再来一次
     * resume 回调** ⇒ 那个直播间再也回不来（"回桌面再回软件 → 只剩直播 Tab"）。
     *
     * 所以：集合只用于"谁 pause 之后，前台该落到谁身上"这一步（见 [onActivityPaused]）；
     * 所有既有判据（[takePendingForRestore] 的四条、消费/清理规则）**一个字没改**。
     * ★只在主线程读写（观察者回调 + `runOnUiThread` 的种子回调），与既有字段同一套约定。
     */
    private val resumedSet: MutableSet<Activity> =
        Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    /**
     * 落盘的**唯一消费者**（对象初始化时起一次，随进程存活）。
     *
     * ★放在所有状态字段之后：Kotlin 的 `init` 与属性初始化按声明顺序执行，协程体里要读的
     *   [appContext] 等字段这时都已经"存在"了（哪怕值还是 null，也不会读到未初始化的东西）。
     *   而 [writeChannel] 的投递永远发生在 [ensureAttached] 之后（每个对外入口都先调它），
     *   所以真正开写时 [appContext] 一定有值。
     */
    init {
        scope.launch {
            for (state in writeChannel) {
                val app = appContext ?: continue
                runCatching {
                    SettingPreferences.edit(app) { prefs ->
                        if (state.usable) {
                            prefs[SettingPreferences.LiveLastRoom] = state.roomId!!
                            prefs[SettingPreferences.LiveLastRoomRestore] = true
                        } else {
                            prefs.remove(SettingPreferences.LiveLastRoom)
                            prefs[SettingPreferences.LiveLastRoomRestore] = false
                        }
                    }
                }
                // 写失败（磁盘满 / DataStore 异常）不影响任何功能：内存里的记录照样能恢复，
                // 受影响的只是"跨进程存活"这一条。按发布要求这里不留日志。
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 对外：来自直播间页面的四个钩子
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 直播间 onCreate：登记"多了一个活着的直播间实例"。
     *
     * 为什么用显式计数而不是靠 lifecycle 回调：本对象可能在任意时刻才被首次接触
     * （冷启动直接进直播间、深链…），靠回调会漏掉注册之前的 created，计数就不准了。
     */
    fun onLivePageCreated(context: Context) {
        ensureAttached(context)
        livePageCount++
        // ★task-55：新的一局（有直播间页面被创建）→ 解除"整任务被划掉"的补记抑制
        //   （见 [taskRemovedSuppressRecord]：那个抑制位只为挡住"任务移除那一瞬"的补记）
        taskRemovedSuppressRecord = false
        // ★事件级诊断日志（2026-09-26，纯观测、不改逻辑；见 [LivePageTrace]）
        LivePageTrace.note(
            "lastRoom.pageCreated",
            "livePageCount" to livePageCount,
            "pendingRoom" to (pending.roomId ?: "-"),
            "restore" to pending.restore,
        )
    }

    /**
     * 直播间 onDestroy：注销实例计数，并**再判一次**恢复。
     *
     * ★这里的判非常重要：点桌面图标回 App 时，系统会先在后台把 PiP 任务里的本页 finish 掉，
     *   再让主界面 resume（也可能反过来 —— 由 ROM 决定）。两个触发点（本方法与
     *   "有 Activity resume"）谁后到谁做决策，先到的那个因为条件不满足（小窗还活着）会跳过，
     *   于是**不管谁先谁后都恰好恢复一次**。
     */
    fun onLivePageDestroyed(context: Context) {
        ensureAttached(context)
        if (livePageCount > 0) livePageCount--
        // ★诊断日志（只读）
        LivePageTrace.note(
            "lastRoom.pageDestroyed",
            "livePageCount" to livePageCount,
            "pendingRoom" to (pending.roomId ?: "-"),
            "restore" to pending.restore,
        )
        evaluateRestore()
    }

    /**
     * 直播间 `onUserLeaveHint`：**用户确实要离开本页**（按 Home / 切走）—— 记录"上次停在哪个直播间"。
     *
     * 为什么这是主记录点：它是"用户离开 App"最早、最准的信号（本页原来就用它做"退后台自动进 PiP"
     * 和"把小窗过渡期的控制条收起来"）。而**手动点底栏/顶栏「画中画」按钮**不会走这里 ——
     * 那条路用户并没有离开 App（主界面就在小窗后面），不该记、也不该在回来时被"恢复"打扰。
     */
    fun onLivePageLeavingApp(context: Context, roomId: String) {
        ensureAttached(context)
        record(roomId)
    }

    /**
     * 直播间 `onStop`（真退到后台）：**兜底**记录点，覆盖不走 `onUserLeaveHint` 的那些路
     * （熄屏、来电、被别的 App 抢前台…）。
     *
     * 两道门，缺一不可：
     * ① `isFinishing` / `isChangingConfigurations` 由调用方在页面里判掉（正常退出、重建不算"离开"）；
     * ② 这里再判一次"进程里没有别的 resumed 页面" —— 直播间被**App 内**的另一个页面盖住时
     *    （例如从直播间开了点播播放页）也会走 onStop，但那种情况用户并没有离开 App，不该记。
     *
     * ★task-47：`inMultiWindow` 是给 ② 开的一道**例外**（调用方传
     *   `isInMultiWindowMode && !isInPictureInPictureMode`，见 `LivePlayerActivity.onStop`）。
     *   理由：多窗口下"别的页面还 RESUMED"**不等于**"用户在 App 内切页" —— Android 10+ 的多窗口
     *   允许多个 Activity **同时 RESUMED**（主界面在另一个窗口里亮着，直播间这个窗口被隐藏/收起）。
     *   这时这次 stop 同样是"用户离开直播间"，记录必须留下，否则系统把直播间窗口收掉之后
     *   就无处可恢复（用户实测："系统小窗 → 回桌面 → 回软件，直播间消失、只剩直播 Tab"）。
     *   ★PiP 不走这个例外（它有自己的记录点与判据），那条路行为一个字节不变。
     *   ★复核 R1（task-53 补记）：桌面/自由窗口环境（Samsung DeX / ChromeOS 桌面模式）下
     *   `isInMultiWindowMode` **可能恒为 true** —— 那里"App 内切页"与"窗口被收起"本来就分不清，
     *   于是这道门在那种环境下**语义被弱化**（几乎总按"离开直播间"记）。这是**有意接受**的偏保守：
     *   多记一次只会让"回 App 仍在直播间"更愿意发生，不会误杀正在看的直播间
     *   （恢复判据② `livePageCount == 0` 仍然拦着双开）。
     */
    fun onLivePageStopped(context: Context, roomId: String, inMultiWindow: Boolean = false) {
        ensureAttached(context)
        if (!inMultiWindow && resumedActivity != null) {
            LivePageTrace.note(
                "lastRoom.record.skip",
                "reason" to "anotherPageResumed",
                "resumed" to resumedActivity,
                "room" to roomId,
            )
            return
        }
        record(roomId)
    }

    /** 用户主动退出直播间（返回键 / 顶栏返回图标 → `exitPage`）：记录作废，下次进 App 不该被弹直播间 */
    fun onLivePageExited(context: Context) {
        ensureAttached(context)
        // ★诊断日志（只读）：用户主动退出直播间 → 记录作废
        LivePageTrace.note("lastRoom.clear", "source" to "onLivePageExited")
        clear()
    }

    // ══════════════════════════════════════════════════════════════════════
    // 对外：宿主（MainActivity）与旧守卫
    // ══════════════════════════════════════════════════════════════════════

    /**
     * App 宿主（`MainActivity.onStart`）回到前台。
     *
     * 只做两件事，都幂等：
     * ① 挂上生命周期观察者（本对象自己注册，不需要 Application / Manifest 改动）；
     * ② 若本进程还没写过记录（典型 = 进程被系统回收后重新打开）→ 异步问一次 DataStore。
     *
     * ★真正的"恢复"决策不在这里做：`onStart` 时本页还没 resume（[resumedActivity] 还是 null），
     *   决策统一放在"有 Activity resume"与"直播间销毁"两个触发点，见 [evaluateRestore]。
     */
    fun onHostForeground(host: Activity) {
        ensureAttached(host.applicationContext)
        // ★诊断日志（只读）：主界面回前台
        LivePageTrace.note(
            "lastRoom.hostForeground",
            "memoryAuthoritative" to memoryAuthoritative,
            "seedInFlight" to seedInFlight,
            "pendingRoom" to (pending.roomId ?: "-"),
            "restore" to pending.restore,
            "livePageCount" to livePageCount,
        )
        // ★★2026-09-26 用户实测严重 bug 回退："任务被划掉 / 进程被杀之后再打开 App，
        //   它又把那个直播间拉回来了 —— 保活强得离谱"。根因就是下面这次**冷启动补读**：
        //   记录是持久化到 DataStore 的，进程没了它还在 → 重开必然恢复 ✗。
        //   用户要的只是"**切到别的 App 再回来还在直播间**" ✓，不是"杀掉还能复活" ✗。
        //   ⇒ 冷启动一律不补读；本进程写过的记录才作数（memoryAuthoritative = true）。
        //   真要恢复"被系统回收后重开"的场景，请先与用户确认语义再加回来。
        memoryAuthoritative = true
    }

    /**
     * 旧守卫（`LivePlayerActivity.ReturnToLiveGuard`）的兜底取用口。
     *
     * 与主路径**同一套判据、同一个一次性记录**：只有"记录还在 + 前台是主界面 +
     * 没有活着的直播间实例"才返回房间号，并且**取走即消费**。所以主路径已经恢复过时，
     * 守卫拿到的必然是 null（不会叠出第二个直播间）。
     *
     * @param activity 守卫正在处理的那个"刚刚 resume 的页面"（守卫的判据要求它是主界面）
     */
    fun consumePendingForGuard(activity: Activity): String? {
        ensureAttached(activity.applicationContext)
        val room = takePendingForRestore(assumeForeground = activity.javaClass.name)
        // ★诊断日志（只读）：旧守卫的兜底取用口（null = 主路径已恢复 / 条件不满足）
        LivePageTrace.note(
            "lastRoom.guardConsume",
            "room" to (room ?: "-"),
            "activity" to activity.javaClass.name,
        )
        return room
    }

    // ══════════════════════════════════════════════════════════════════════
    // 状态机内部
    // ══════════════════════════════════════════════════════════════════════

    /** 记下"上次停在哪个直播间" + "应当恢复"（幂等：同一房间重复记录不再写盘） */
    private fun record(room: String) {
        if (room.isBlank()) {
            LivePageTrace.note("lastRoom.record.skip", "reason" to "blankRoom")
            return
        }
        // ★task-55 保护②：整任务刚被划掉 ⇒ 不再记账（见 [taskRemovedSuppressRecord]）
        if (taskRemovedSuppressRecord) {
            LivePageTrace.note(
                "lastRoom.record.skip",
                "reason" to "taskRemoved",
                "room" to room,
            )
            return
        }
        if (pending.usable && pending.roomId == room) {
            LivePageTrace.note("lastRoom.record.skip", "reason" to "alreadyRecorded", "room" to room)
            return
        }
        // ★诊断日志（只读）：记下"上次停在哪个直播间"
        LivePageTrace.note(
            "lastRoom.record",
            "room" to room,
            "prevRoom" to (pending.roomId ?: "-"),
            "prevRestore" to pending.restore,
        )
        publish(Pending(room, restore = true))
    }

    /**
     * ★2026-09-26 用户实测：从最近任务里**划掉** App（任务被移除）时，记录必须作废 ——
     * 否则下次打开又会把那个直播间拉回来（"保活强得离谱" ✗）。
     *
     * [hermes-fix 2026-09-26] 由 `PlaybackService.onTaskRemoved()` 调用（同步、极轻）。
     */
    fun onTaskRemoved(context: Context) {
        ensureAttached(context.applicationContext)
        memoryAuthoritative = true
        // ★task-55 保护②：先立起抑制位，再清账 —— 顺序不能反：
        //   紧随其后的 `LivePlayerActivity.onDestroy`（同一趟任务移除）会尝试补记，
        //   抑制位必须已经在，否则刚清掉的账会被记回来（"划掉 App 又冒出直播间"）。
        taskRemovedSuppressRecord = true
        clear()
        LivePageTrace.note("lastRoom.taskRemoved", "room" to (pending.roomId ?: "-"))
    }

    /** 清掉记录（"应当恢复"作废） */
    private fun clear() {
        if (pending.roomId == null && !pending.restore) {
            LivePageTrace.note("lastRoom.clear.skip", "reason" to "alreadyEmpty")
            return
        }
        // ★诊断日志（只读）：记录作废
        LivePageTrace.note("lastRoom.clear", "room" to (pending.roomId ?: "-"))
        publish(Pending(null, restore = false))
    }

    /** 取走（消费）记录：一次性，防自激 */
    private fun consume() {
        // ★诊断日志（只读）：一次性取走（防自激）
        LivePageTrace.note(
            "lastRoom.consume",
            "room" to (pending.roomId ?: "-"),
            "restore" to pending.restore,
        )
        publish(Pending(null, restore = false))
    }

    /** 主线程改状态 + 投递落盘（[Pending] 是不可变的，所以这里天然是"整个状态一起换"） */
    private fun publish(state: Pending) {
        pending = state
        memoryAuthoritative = true
        // CONFLATED 通道的 trySend 永不阻塞、永不失败（除非通道被关闭，本对象不会关它）
        writeChannel.trySend(state)
    }

    /**
     * 恢复判据的**唯一实现**（主路径与守卫共用）。
     *
     * 四条一起看，缺一不可：
     * ① App 真的"回到软件"了（前台 = 主界面 [HOST_ACTIVITY]，不是"还在后台"也不是别的页面）；
     *   ★task-47：多窗口下"前台"取自 [resumedSet]（同时可能有多个 RESUMED 的页面），
     *   判据本身不变 —— 仍然要求"当下是主界面"。
     * ② 没有活着的直播间实例（典型 = 直播间还在 PiP 小窗里，那就等它被收掉再判）；
     * ③ "应当恢复"的记录还在；
     * ④ 取走即消费（一次性）。
     *
     * @param assumeForeground 守卫调用时传"刚刚 resume 的那个 Activity 类名"（回调顺序不保证本对象
     *   的观察者先跑，所以由调用方把它正在处理的那个前台页面显式告诉这里）。
     * @return 房间号（并已消费）；null = 什么都不该做
     */
    private fun takePendingForRestore(assumeForeground: String? = null): String? {
        val foreground = assumeForeground ?: resumedActivity
        if (foreground == null) {
            LivePageTrace.note("lastRoom.restore.skip", "reason" to "noForeground")
            return null
        }
        if (foreground != HOST_ACTIVITY) {
            LivePageTrace.note("lastRoom.restore.skip", "reason" to "foregroundNotHost", "foreground" to foreground)
            return null
        }
        if (livePageCount > 0) {
            LivePageTrace.note(
                "lastRoom.restore.skip",
                "reason" to "livePageAlive",
                "livePageCount" to livePageCount,
            )
            return null
        }
        if (!pending.usable) {
            LivePageTrace.note(
                "lastRoom.restore.skip",
                "reason" to "noPending",
                "room" to (pending.roomId ?: "-"),
                "restore" to pending.restore,
            )
            return null
        }
        val room = pending.roomId
        if (room == null) {
            LivePageTrace.note("lastRoom.restore.skip", "reason" to "nullRoom")
            return null
        }
        // ★诊断日志（只读）：判据全过 → 真的要恢复
        LivePageTrace.note(
            "lastRoom.restore.fire",
            "room" to room,
            "livePageCount" to livePageCount,
            "foreground" to foreground,
        )
        consume()
        return room
    }

    /** 触发一次"该不该恢复"的判定；该开就开（判据见 [takePendingForRestore]） */
    private fun evaluateRestore() {
        val host = resumedHost
        if (host == null) {
            LivePageTrace.note("lastRoom.evaluate", "host" to "none")
            return
        }
        if (host.isFinishing || host.isDestroyed) {
            LivePageTrace.note(
                "lastRoom.evaluate",
                "host" to host.javaClass.name,
                "reason" to "hostGone",
                "finishing" to host.isFinishing,
                "destroyed" to host.isDestroyed,
            )
            return
        }
        val room = takePendingForRestore()
        if (room == null) return
        // ★诊断日志（只读）：主路径真的把直播间开起来
        LivePageTrace.note("lastRoom.restore.startActivity", "room" to room, "host" to host.javaClass.name)
        runCatching {
            host.startActivity(
                Intent()
                    .setClassName(host, LIVE_PLAYER_ACTIVITY)
                    .putExtra(EXTRA_ROOM_ID, room)
                    // NEW_TASK：把直播间开在**刚刚回到前台的那个任务**里（主任务），
                    // 否则它可能落进当前那个 pinned 小窗任务，用户看到的还是一个小窗。
                    // ★与旧守卫用的是同一条约定（真机验证过的落点）。
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        // 拉起失败（极少数 ROM 的后台启动限制）：不重试、不提示 —— 记录已经消费掉，
        // 反复弹直播间才是真的骚扰
    }

    /**
     * 进程刚起（被系统回收后重新打开）时问一次 DataStore：上次是不是"带着直播间离开的"。
     *
     * 只在 [memoryAuthoritative] 还是 false 时发起；回调里若发现期间本进程已经写过
     * （例如用户自己先进了直播间 → 记录已消费），就以内存为准直接放弃。
     */
    private fun seedFromDataStore(host: Activity) {
        if (seedInFlight) return
        seedInFlight = true
        val app = host.applicationContext
        scope.launch {
            val saved = runCatching {
                SettingPreferences.mapData(app) { prefs ->
                    if (prefs[SettingPreferences.LiveLastRoomRestore] == true) {
                        prefs[SettingPreferences.LiveLastRoom]
                    } else {
                        null
                    }
                }
            }.getOrNull()
            host.runOnUiThread {
                seedInFlight = false
                if (memoryAuthoritative) {
                    LivePageTrace.note("lastRoom.seed.skip", "reason" to "memoryAuthoritative")
                    return@runOnUiThread
                }
                // ★诊断日志（只读）：冷启动那次 DataStore 种子读到了什么
                LivePageTrace.note("lastRoom.seed", "savedRoom" to (saved ?: "-"))
                memoryAuthoritative = true
                pending = Pending(saved, restore = !saved.isNullOrBlank())
                evaluateRestore()
            }
        }
    }

    /** 挂上生命周期观察者（幂等；第一次接触本对象时调用，不需要改 Application） */
    private fun ensureAttached(context: Context) {
        if (observerRegistered) return
        synchronized(attachLock) {
            if (observerRegistered) return
            val app = context.applicationContext
            appContext = app
            (app as? Application)?.registerActivityLifecycleCallbacks(observer)
            observerRegistered = true
        }
    }

    /**
     * 生命周期观察者：只做"记前台是谁"与"在正确的时刻触发一次判定"两件事。
     *
     * 期望恢复的触发点只有两个：**有 Activity resume**（App 回到前台）与
     * **直播间销毁**（PiP 小窗被系统收掉）—— 两者合起来覆盖了所有"该恢复"的时序，
     * 且都走同一个一次性判据，重复触发不会重复开。
     */
    private object observer : Application.ActivityLifecycleCallbacks {

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) {
            resumedSet.add(activity)
            resumedHost = activity
            resumedActivity = activity.javaClass.name
            // ★task-47：多窗口（**非 PiP**）判定 —— 见下面那段"为什么不消费记录"。
            //   两个 API 都是 API 24（本工程 minSdk 24），无需版本判断。
            val multiWindowNotPip =
                activity.isInMultiWindowMode && !activity.isInPictureInPictureMode
            // ★诊断日志（只读）："有 Activity resume" 是恢复判定的两个触发点之一
            LivePageTrace.note(
                "lastRoom.resumed",
                "activity" to resumedActivity,
                "pendingRoom" to (pending.roomId ?: "-"),
                "restore" to pending.restore,
                "livePageCount" to livePageCount,
                "multiWindowNotPip" to multiWindowNotPip,
            )
            if (resumedActivity == LIVE_PLAYER_ACTIVITY) {
                if (multiWindowNotPip) {
                    // ★★task-47（**用户实测根因**）：多窗口里**不**把"直播间 resume"当成
                    //   "人已经在直播间里了"，因此**不消费**记录。
                    //   为什么：Android 10+ 的多窗口允许多个 Activity 同时 RESUMED，而"用户点桌面图标
                    //   回 App"带回来的是**主界面**；小窗里的直播间可能恰好也处于 RESUMED（甚至只是
                    //   瞬时 resume），紧接着就被系统收掉（finish）。原来那一句"直播间 resume → 作废
                    //   记录"会在这一刻把记录先消费掉 ⇒ 小窗被收掉后 `evaluateRestore` 无记录可恢复
                    //   ⇒ 用户落在直播 Tab、直播间"消失"（普通视频没有这套账本，所以它不受影响）。
                    //   ★"取走即消费/防循环"的语义没变，只是给多窗口加了这条例外；PiP 不在其列
                    //   （PiP 的 `isInPictureInPictureMode` 为 true）→ PiP 那条路逐字不变。
                    LivePageTrace.note(
                        "lastRoom.consume.skip",
                        "reason" to "multiWindowNotPip",
                        "room" to (pending.roomId ?: "-"),
                    )
                } else if (pending.restore) {
                    // ★人已经在直播间里了（单窗口下这是可靠信号）→ "应当恢复"作废：这就是
                    //   "不能因为直播间自己 resume 又触发一次恢复"那条防循环。
                    consume()
                }
            }
            evaluateRestore()
        }

        override fun onActivityPaused(activity: Activity) {
            resumedSet.remove(activity)
            if (resumedHost === activity) {
                // ★诊断日志（只读）：前台页面离开（resumedActivity 清空）
                LivePageTrace.note("lastRoom.paused", "activity" to activity.javaClass.name)
                // ★★task-47：**不能在 pause 时无条件把前台清成 null** —— 多窗口下这里刚 pause 的
                //   可能只是"小窗里那个直播间"，而主界面**仍然 resumed 着**（它不会再来一次 resume
                //   回调）。前台落到集合里还剩下的那个页面上，"小窗被系统收掉"那一刻
                //   [evaluateRestore] 才拿得到宿主（否则直接以 "no host" 跳过 = 用户实测的 bug）。
                //   单窗口下集合此时为空 ⇒ 行为与改动前逐字一致（前台清空）。
                val next = nextHostFromResumed()
                resumedHost = next
                resumedActivity = next?.javaClass?.name
                // ★task-53 取证：登记"前台交给了谁"（多窗口下这个决定直接决定恢复能不能触发）
                LivePageTrace.note("lastRoom.paused.fallback", "next" to (resumedActivity ?: "-"))
            }
        }

        override fun onActivityStopped(activity: Activity) {
            // 直播间自己的 stop 由页面钩子（onLivePageStopped）处理，这里只管"别的页面"
            if (activity.javaClass.name == LIVE_PLAYER_ACTIVITY) return
            // 还有前台页面 = App 内部切页，不算"离开 App"
            if (resumedActivity != null) {
                // ★task-53 取证：这条"不清记录"的早退原来没有日志 —— 它决定记录留不留
                LivePageTrace.note(
                    "lastRoom.clear.skip",
                    "reason" to "anotherPageResumed",
                    "activity" to activity.javaClass.name,
                    "resumed" to resumedActivity,
                )
                return
            }
            // 直播间还活着（典型 = 用户带着它进了 PiP 小窗，App 退后台时小窗还在桌面上）
            // → 这次退后台仍然是"从直播间离开的"，记录必须留着
            if (livePageCount > 0) {
                // ★task-53 取证：同上，补全决策点
                LivePageTrace.note(
                    "lastRoom.clear.skip",
                    "reason" to "livePageAlive",
                    "activity" to activity.javaClass.name,
                    "livePageCount" to livePageCount,
                )
                return
            }
            // 走到这里 = App 从**非直播间**页面退到后台（点播页 / 直播 Tab / 设置页…）
            // → 上次那条"应当恢复"不再代表用户离开时的位置，作废
            //   （用户实测过的那条："在点播页退桌面 → 回软件，被拉去直播间" ✗）
            // ★诊断日志（只读）：记录作废的原因
            LivePageTrace.note(
                "lastRoom.clear",
                "source" to "backgroundedFromOtherPage",
                "activity" to activity.javaClass.name,
            )
            clear()
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            // ★task-47：任何页面销毁都从"仍 RESUMED 集合"里摘准（弱引用只是兜底）——
            //   否则一个已销毁的宿主可能被 [onActivityPaused] 的兜底选中；
            //   即便选中了，[evaluateRestore] 的 `isDestroyed` 那道门也不会拿它去 startActivity。
            resumedSet.remove(activity)
            if (activity.javaClass.name != LIVE_PLAYER_ACTIVITY) return
            // 直播间：前台若正是它，落到"此刻还 RESUMED 的另一个页面"（多窗口：小窗被系统收掉时
            // 主界面通常还在前台，[evaluateRestore] 需要它当宿主）。
            if (resumedHost === activity) {
                val next = nextHostFromResumed()
                resumedHost = next
                resumedActivity = next?.javaClass?.name
                // ★task-53 取证：直播间销毁后"前台交给了谁"（决定 evaluateRestore 走哪条分支）
                LivePageTrace.note("lastRoom.destroyed.fallback", "next" to (resumedActivity ?: "-"))
            }
        }

        /**
         * ★task-53（复核 R2 的一行加固）：从"仍 RESUMED 的页面集合"里挑下一个前台宿主，**优先主界面**
         * （[HOST_ACTIVITY]）。为什么必须挑：`Set.firstOrNull()` 的遍历顺序**未定义**，多窗口下集合里
         * 可能同时有"主界面"和"直播间/别的页面"，随便挑一个会让恢复判据①（前台必须是主界面）
         * **时而通过时而跳过**（间歇性故障）。挑不到主界面时退回集合里任意一个 —— 那种情况下判据①
         * 本来就不会通过，取值只影响日志与"守卫"的可见性。
         */
        private fun nextHostFromResumed(): Activity? =
            resumedSet.firstOrNull { it.javaClass.name == HOST_ACTIVITY } ?: resumedSet.firstOrNull()
    }
}
