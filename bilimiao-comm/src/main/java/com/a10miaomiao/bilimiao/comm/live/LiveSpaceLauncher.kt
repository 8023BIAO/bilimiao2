package com.a10miaomiao.bilimiao.comm.live

/**
 * 「直播播放页 → 该 UP 的用户空间」的**注册桥**（comm 模块，唯一的跨模块落点）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ## ★2026-09-26 定位变更：本桥现在是**兜底**，主路改成了"直播页内的浮层"
 *
 * 用户报的 bug："在直播间点 UP主 进他主页没问题，但返回是直播界面的那个 Tab，不是他的直播间。"
 * 根因就在这条桥上：它的实现是"把**主界面**的 NavHost 导航到用户空间"，而主界面被直播页压着，
 * 要让它露出来就只能 `finish()` 掉直播页 —— 返回时直播页已经没了，自然只能回到直播 Tab。
 *
 * 现在的主路是 `com.a10miaomiao.bilimiao.compose.pages.user.UserSpaceOverlayHost`：
 * 用户空间作为**页内浮层**盖在直播页自己的视图树最上层，直播页不 finish、不 pause/stop，
 * 返回只是把浮层摘掉 —— 一次返回就回到**还在播**的直播间。
 *
 * 本桥保留下来是因为它还有**兜底价值**：进程里拿不到可用的 `MainActivity`（没有它的 DI /
 * ComposeFragment，浮层开不起来）时，直播页会退回这条路（`LiveSpaceLauncher.open(mid)` + finish）。
 * 那条路的老代价（返回落到直播 Tab）在兜底场景下被接受 —— 它至少能把空间打开。
 * 所以：**注册点（`ComposeFragment`）与实现都不要删**。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ## 为什么当初需要一座桥（而不是直播页直接跳）
 * 用户空间（`UserSpacePage`）是 **Compose 页面**，只活在 `bilimiao-compose` 的 NavHost 里，
 * 只能通过 `PageNavigation.navigate(...)` 打开；而调用方 `LivePlayerActivity` 在 **app** 模块。
 * 本工程的依赖方向是 `app → compose`、`compose → comm`，**app 反向 import compose 会成环编译不过**
 * （同一条约束在 `LiveBadgedAvatar` 里已经用"类名字符串 + 字面量 extra"绕过一次，
 * 但那条路只够传一个房间号，传不了"要打开哪个 Compose 页面"）。
 *
 * 所以这里采用**注册桥**：comm 模块只留一个函数类型的挂点（不放任何 Compose 依赖），
 * 由 compose 侧在 `ComposeFragment` 的根组合里注册一次真正的实现
 * （`pageNavigation.navigate(UserSpacePage(id = mid.toString()))`）。
 *
 * ```
 * app 侧：LivePlayerActivity.openUpSpace()
 *            └─ LiveSpaceLauncher.open(mid)   ← 只认这个函数，不认 compose
 * compose 侧：ComposeFragment 根组合
 *            └─ LiveSpaceLauncher.register { mid -> pageNavigation.navigate(UserSpacePage(...)) }
 * ```
 *
 * ## 线程约定
 * **只在主线程用**：注册发生在 Compose 组合里（主线程），调用发生在 `LivePlayerActivity`
 * 的点击回调里（主线程），`PageNavigation.navigate` 本身也标了 `@MainThread`。
 * 这里仍用 `@Volatile` 护一下可见性，代价是一个字段，收益是"万一将来有人在别的线程调"也不会读到脏引用。
 *
 * ## 生命周期
 * `ComposeFragment` 用 `DisposableEffect` 注册 + `onDispose` 注销，所以：
 * · 页面在 → [open] 返回 true（跳转成功）；
 * · 页面没了（ComposeFragment 已销毁 / 进程刚起还没组合）→ [open] 返回 **false**，
 *   调用方 toast 兜底，**不 finish、不乱跳**（用户留在直播间，再点一次就好）。
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
}
