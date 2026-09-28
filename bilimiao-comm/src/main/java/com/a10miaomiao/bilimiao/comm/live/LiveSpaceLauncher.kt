package com.a10miaomiao.bilimiao.comm.live

import android.app.Activity
import android.view.View

/**
 * 「直播播放页 → 该 UP 的用户空间」的**注册桥**（comm 模块，唯一的跨模块落点）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ## ★定位沿革（2026-09-26 起，2026-09-28 复核修正）
 *
 * 用户报的 bug："在直播间点 UP主 进他主页没问题，但返回是直播界面的那个 Tab，不是他的直播间。"
 * 根因就在这条桥上：它的实现是"把**主界面**的 NavHost 导航到用户空间"，而主界面被直播页压着，
 * 要让它露出来就只能 `finish()` 掉直播页 —— 返回时直播页已经没了，自然只能回到直播 Tab。
 *
 * 2026-09-26 那批的应对是新增页内浮层
 * `com.a10miaomiao.bilimiao.compose.pages.user.UserSpaceOverlayHost`（用户空间盖在直播页自己的
 * 视图树最上层，直播页不 finish，一次返回回到**还在播**的直播间），本桥降级为兜底。
 *
 * ★**该浮层文件已在后续批次（第五批）被整体删除** —— 所以现在本桥是**唯一**路径，
 *   「直播页 → UP 空间」只有这一条：`open(mid)` 成功即由 compose 侧导航主界面 NavHost，
 *   调用方随后 `finish()` 直播页。**代价照旧**：从 UP 空间返回落到直播 Tab，不是原直播间。
 *   （要恢复"返回还在直播间"就得重建那份浮层，属 compose + DI 的较大改动；本轮评论区已记录。）
 *
 * ★**2026-09-28（task-26/27）浮层以"工厂"形式复活**（用户："返回为什么是回到直播 TAB，
 *   不是直播间？路线还是要理成一条线的"）：页内浮层重新实现（compose 侧 `UserSpaceOverlayHost`），
 *   但这次**不再让任何人反向依赖** —— 浮层把自己的构造注册成本文件下面的 [SpaceOverlayFactory]，
 *   直播页拿到的是本模块的 [SpaceOverlayHandle]。于是本桥同时承载两条路：
 *   · **主路** = [createOverlay]：直播页把浮层铺在自己页面最上层，**不 finish、不 pause/stop**，
 *     一次返回回到**还在播**的直播间（上面那条代价就此消失）；
 *   · **兜底** = [open]：浮层建不出来（注册缺席 / 构造抛异常）时照旧"导航主界面 + finish 直播页"，
 *     宁可返回栈不完美，也不让"点标题没反应"。
 *   两条路的取舍与顺序写在 `LivePlayerActivity.openAnchorSpace()` 的 KDoc 上。
 *
 * 因此：**注册点（`ComposeFragment`）与实现都不要删** —— 删掉就没有任何进 UP 空间的路径了。
 * 拿不到注册实现时 [open] 返回 false，调用方只 toast、不 finish、不乱跳（用户留在直播间）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ## 为什么当初需要一座桥（而不是直播页直接跳）
 * 用户空间（`UserSpacePage`）是 **Compose 页面**，只活在 `bilimiao-compose` 的 NavHost 里，
 * 只能通过 `PageNavigation.navigate(...)` 打开；而调用方 `LivePlayerActivity` 在 **app** 模块。
 * ★缺的不是"依赖方向"，是"**导航句柄**"（2026-09-28 复核修正，别把下面那条旧论据写回来）：
 *   app 模块**本来就能** import compose —— `app/build.gradle.kts` 里
 *   `implementation(project(":bilimiao-compose"))`，且 `MainActivity` / `MainUi` / `PlayerController`
 *   等已在直接 import `cn.a10miaomiao.bilimiao.compose.*`；真正拿不到的是 `PageNavigation`
 *   句柄本身（它只活在 `ComposeFragment` 的根组合里，`grep -rn "pageNavigation" app/src`
 *   的**代码**引用数为 0）。
 *   （`LiveBadgedAvatar` 那条"类名字符串 + 字面量 extra"的老路，也只够传一个房间号，
 *    传不了"要打开哪个 Compose 页面"。）
 *
 * 所以这里采用**注册桥**：comm 模块只留**函数类型的挂点**（不放任何 Compose 依赖 ——
 * [overlayFactory] 交出来的是 Android `View`，[opener] 交出来的是一次导航回调），
 * 由 compose 侧在 `ComposeFragment` 的根组合里注册真正的实现。
 *
 * ```
 * app 侧：LivePlayerActivity.openAnchorSpace()
 *            ├─ LiveSpaceLauncher.createOverlay(this, uid) { … }  ← 主路：要一个浮层句柄（不认 compose）
 *            └─ LiveSpaceLauncher.open(mid)                       ← 兜底：只要一次导航（不认 compose）
 * compose 侧：ComposeFragment 根组合
 *            ├─ LiveSpaceLauncher.registerOverlay { … }           ← 浮层工厂（task-26 实现）
 *            └─ LiveSpaceLauncher.register { mid -> pageNavigation.navigate(UserSpacePage(...)) }
 * ```
 *
 * ## 线程约定
 * **只在主线程用**：注册发生在 Compose 组合里（主线程），调用发生在 `LivePlayerActivity`
 * 的点击回调里（主线程），`PageNavigation.navigate` 本身也标了 `@MainThread`。
 * 这里仍用 `@Volatile` 护一下可见性，代价是一个字段，收益是"万一将来有人在别的线程调"也不会读到脏引用。
 *
 * ## 生命周期
 * `ComposeFragment` 用 `DisposableEffect` 注册 + `onDispose` 注销，两套挂点同生共死，所以：
 * · 页面在 → [open] 返回 true（跳转成功）、[createOverlay] 返回句柄（浮层建得起来）；
 * · 页面没了（ComposeFragment 已销毁 / 进程刚起还没组合）→ [open] 返回 **false**、
 *   [createOverlay] 返回 **null**，调用方 toast 兜底 / 自动走兜底老路，**不 finish、不乱跳**
 *   （用户留在直播间，再点一次就好）。
 */
object LiveSpaceLauncher {

    /**
     * 打开"某个 UP 的用户空间"的实现，由 `ComposeFragment` 注册。
     * 参数是 **UP 的 uid**（[open] 里已保证 > 0）。
     */
    @Volatile
    private var opener: ((Long) -> Unit)? = null

    /**
     * 注册实现（**唯一注册点**：`ComposeFragment`）。
     *
     * ★重复注册 = 覆盖：ComposeFragment 重建时旧实现已经失效（它捕获的是旧的
     *   `pageNavigation`/NavController），覆盖正是我们要的语义；注销由 `onDispose` 负责。
     */
    fun register(open: (mid: Long) -> Unit) {
        opener = open
    }

    /** 注销（ComposeFragment 的 `onDispose` 调用）。注销后 [open] 一律返回 false。 */
    fun unregister() {
        opener = null
    }

    /**
     * 打开用户空间。
     *
     * @param mid UP 的 uid，必须 > 0（调用方已校验，这里再挡一道：0 会打开一个空白的用户空间）
     * @return true = 已经交给注册的实现；false = **桥没注册**或实现抛异常（调用方据此 toast 兜底）
     *
     * ★实现抛异常也返回 false（`runCatching`）：导航失败（路由没注册、NavController 已经没了）
     *   在工程里是 `hostController.navigate` 的常见失败模式，绝不能让直播页因此崩掉 ——
     *   用户点一个按钮最坏的结果应该是"弹一句提示"，不是闪退。
     */
    fun open(mid: Long): Boolean {
        if (mid <= 0L) return false
        val impl = opener ?: return false
        return runCatching { impl(mid) }.isSuccess
    }

    // ── 页内浮层（2026-09-28 task-26/27；与上面的 [opener] 互不影响、各自独立注册）──────────

    /**
     * 浮层的工厂实现，由 `ComposeFragment` 注册（与 [register] 同一个注册点、同一条 `DisposableEffect`）。
     * ★与 [opener] 一样：**重复注册 = 覆盖**（重建时旧实现已失效），注销走 [unregisterOverlay]。
     */
    @Volatile
    private var overlayFactory: SpaceOverlayFactory? = null

    /** 注册浮层工厂（**唯一注册点**：`ComposeFragment`）。 */
    fun registerOverlay(factory: SpaceOverlayFactory) {
        overlayFactory = factory
    }

    /** 注销浮层工厂（`ComposeFragment.onDispose`）。注销后 [createOverlay] 一律返回 null。 */
    fun unregisterOverlay() {
        overlayFactory = null
    }

    /**
     * 尝试建一个"页内 UP 空间浮层"。
     *
     * @param mid UP 的 uid（必须 > 0；0 会打开一个空白的用户空间，与 [open] 同一条门）
     * @return 句柄；**没注册 / 构造抛异常 / 返回 null 一律给调用方 null**（调用方据此走 [open] 兜底）
     *
     * ★★**"绝不抛"是本轮的安全底线**：compose 侧那套浮层要自己拼 `subDI`（历史上正是它抛过
     *   `StartupException: Binding AppCompatActivity must override an existing binding.` 把用户崩过），
     *   所以这里把整个构造过程 `runCatching` 掉 —— DI 出问题、窗口拿不到、注册表缺 destination……
     *   任何异常都只退化成"今天的行为"（[open] + finish），**绝不让"点一下标题"变成闪退**。
     */
    fun createOverlay(
        activity: Activity,
        mid: Long,
        onExitToMainHost: () -> Unit,
    ): SpaceOverlayHandle? {
        if (mid <= 0L) return null
        val impl = overlayFactory ?: return null
        return runCatching { impl.create(activity, mid, onExitToMainHost) }.getOrNull()
    }
}

// ══════════════════════════════════════════════════════════════════════════
// 页内浮层的两个类型（2026-09-28 task-26/27 **冻结**的接口：名字与签名不许改）
//
// ★为什么两个类型放在文件末尾而不是对象前面：对象那段 KDoc 必须**紧贴** `object`，
//   中间插两个声明会把它变成悬空注释（谁也没被它注释到）—— 文档结构也是代码结构的一部分。
// ══════════════════════════════════════════════════════════════════════════

/**
 * **页内 UP 空间浮层**的句柄（直播页拿到的就是这个接口）。
 *
 * 为什么要有它（而不是让直播页直接持有 compose 那个 `UserSpaceOverlayHost`）：
 * 直播页在 **app** 模块、浮层实现在 **compose** 模块，而 app 侧**拿不到 compose 的
 * `PageNavigation` 句柄**（它只活在 `ComposeFragment` 的根组合里）—— 注册桥（本文件）
 * 因此仍是唯一的跨模块落点，只是这次交换的是"一个浮层句柄"而不是"一次导航"。
 *
 * 三个成员都是**实现方（compose 侧）**的责任，直播页只调：
 * · [view]：浮层的根 View，由直播页 `addView` 到自己的页面最上层（铺满 + 高 elevation）；
 * · [onBack]：把一次系统返回**交给浮层自己**处理（它内部可能还能退一层 / 关掉自己的弹窗）。
 *   返回 `true` = 这次返回已被浮层消费掉；`false` = 浮层已经退到底、该关了（**由直播页关**，
 *   直播页只关浮层、**绝不退出直播间** —— 那正是本方案要根治的 bug）。
 * · [dispose]：关浮层时由直播页调用（先摘 view 再 dispose）。**实现方必须保证可重复调用不炸**
 *   （直播页在 onStop / onDestroy / 进 PiP 等多条路径上都会尝试关它）。
 */
interface SpaceOverlayHandle {
    /** 浮层根 View（**未挂载**的新 View；直播页负责 addView / removeView）。 */
    val view: View

    /** @return true = 这次返回已在浮层内消费；false = 浮层该关了（直播页只关它，不退出直播间） */
    fun onBack(): Boolean

    /** 关浮层（摘 view 之后调用）；**可重复调用不炸**。 */
    fun dispose()
}

/**
 * 浮层工厂：由 compose 侧（`ComposeFragment`）实现并注册（见 [LiveSpaceLauncher.registerOverlay]）。
 *
 * @param activity 宿主 Activity（= 直播页；浮层要拿它当自己那套 Compose 的宿主）
 * @param mid UP 的 uid（> 0）
 * @param onExitToMainHost 浮层里点到"不属于 UP 空间流程"的目的地（典型 = 打开点播播放器）时回调：
 *   直播页收到后**关浮层 + 退出直播间**，把界面让给主界面自己那条导航
 *   （为什么不用再调 [LiveSpaceLauncher.open]，见 `LivePlayerActivity.handOffToMainHost()`）。
 * @return 句柄；**构造失败就返回 null**（调用方自动退回旧路，绝不让"点标题"变成闪退）
 */
fun interface SpaceOverlayFactory {
    fun create(activity: Activity, mid: Long, onExitToMainHost: () -> Unit): SpaceOverlayHandle?
}
