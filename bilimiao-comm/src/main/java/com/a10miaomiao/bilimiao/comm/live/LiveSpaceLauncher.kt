package com.a10miaomiao.bilimiao.comm.live

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
