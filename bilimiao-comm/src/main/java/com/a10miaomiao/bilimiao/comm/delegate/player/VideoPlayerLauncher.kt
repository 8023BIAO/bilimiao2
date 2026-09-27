package com.a10miaomiao.bilimiao.comm.delegate.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.a10miaomiao.bilimiao.comm.entity.player.PlayListItemInfo

/**
 * 点播「打开视频」的**唯一收敛点**（阶段 1）。
 *
 * ## 为什么需要它（问题原文见 `直播优化-点播PIP立即开启-说明.md` §7.3）
 * 点播播放器过去活在 `MainActivity`（task 根）自己里：一进 PiP，task 整体退到后台，
 * 小窗背后没有任何自家页面 → 只能露出手机桌面。直播之所以"小窗浮在自家界面上"，
 * 是因为它有独立的 `LivePlayerActivity` 压在 `MainActivity` 之上。
 * 所以点播也要把"播放"这件事搬到栈顶的独立 Activity 里（[VideoPlayerActivity]）。
 *
 * ## 为什么用"进程内直传"而不是 Intent extra
 * `BasePlayerSource`（`VideoPlayerSource` / `BangumiPlayerSource`）不是 `Parcelable`：
 * 它带着分P/剧集列表、宽高、封面、各种 CDN 开关，甚至一堆可变状态（[BasePlayerSource.uposHost] 等）。
 * 序列化一份"副本"进 Intent，等于把播放语义拆成两处（容易漏字段、丢状态）；
 * 这里直接把**同一个对象引用**交给新页面，播放语义与老路径逐字一致。
 * 代价：进程被杀后重建拿不到源（`onCreate` 里会 `finish()`），阶段 2 再补持久化恢复。
 *
 * ## 回退（一行）
 * 把 [USE_STANDALONE_ACTIVITY] 改成 `false` 即回到老路径：`play()` 直接调用宿主
 * （`MainActivity` 里那个 `PlayerDelegate2` 单例）的 `openPlayer()`，与本次改动前**完全一致**。
 * `MainActivity` 上的挂载（`MainActivity.kt:120` 创建、`:208` 挂到 `ui.root`）**一行没删**。
 */
object VideoPlayerLauncher {
    // ★ 这个 object 放在 `bilimiao-comm` 模块（2026-09-26 从 app 模块搬来）：
    //   调用方在 `bilimiao-compose`（视频详情页），被启动的 `VideoPlayerActivity` 在 `app`，
    //   两个模块都依赖 comm —— 只有放这里两边才都看得见（播放参数的进程内直传也才成立）。

    /**
     * ★★ 回退开关（一行回滚）★★
     *
     * - `true`  ：点播播放交给独立 [VideoPlayerActivity] → PiP 小窗浮在自家界面（MainActivity）之上
     * - `false` ：回到改动前：仍由 `MainActivity` 里的 `PlayerDelegate2` 单例播放（PiP 会露出桌面）
     *
     * 只影响"新开一个视频"这件事；已经在播的那个页面不受影响（切开关后重开视频即生效，无需重启 App）。
     */
    // ★ 2026-09-26 用户实测后**关掉**：搬进独立 Activity 后"一点开就自动全屏、布局乱"，
    //   用户明确要求"不要碰我以前已经做好的点播播放"。独立 Activity 的代码先留着（阶段 2 再评估），
    //   行为回到"主界面内播放"的老路径 —— 一行即可重开。
    const val USE_STANDALONE_ACTIVITY = false

    /** 待播源：进程内直传（见类注释"为什么用进程内直传"） */
    @Volatile
    private var pendingSource: BasePlayerSource? = null

    /**
     * 待播种的**播放列表快照**。
     *
     * 为什么要带它：播单/合集自动连播（`PlayerController.onAutoCompletion` → `playListStore`）
     * 依赖"播放列表里有哪几个视频"，而播放列表是在宿主页面（视频详情页）里填进
     * `MainActivity` 的 `PlayListStore` 的。新页面有自己的一份 store（见 [VideoPlayerActivity] 注释），
     * 不把这几个 item 带过去，**合集播完第一集就会直接弹"播放完成"**（本该自动下一集）。
     * 播放过程中播放器侧只**读**不写这个列表（全仓 grep 确认），所以一次性快照足够。
     */
    @Volatile
    private var pendingPlayList: List<PlayListItemInfo> = emptyList()

    /**
     * 打开一个点播视频。
     *
     * @param context  必须是 Activity（Compose 页面传进来的就是宿主 Activity）
     * @param source   播放源（与老路径**同一个对象**）
     * @param oldHost  老宿主（`MainActivity` 的播放器委托）。回退模式下由它播放；
     *                 独立 Activity 模式下用它"收摊"——见下面对 GSY 单例的说明。
     * @param playList 当前播放列表快照（合集/播单自动连播要用，可为空）
     */
    fun play(
        context: Context,
        source: BasePlayerSource,
        oldHost: BasePlayerDelegate? = null,
        playList: List<PlayListItemInfo> = emptyList(),
    ) {
        // 回退路径 / 拿不到 Activity（理论上不会）：原样交给老宿主，行为与改动前一致
        if (!USE_STANDALONE_ACTIVITY || context !is Activity) {
            oldHost?.openPlayer(source)
            return
        }
        // ★ 老宿主里如果还开着播放器，必须先收摊再开新页面：
        //   GSY 的播放管理器（`GSYVideoManager.instance()`）与 ExoPlayer 是**进程单例**，
        //   两个播放器 View 同时 setUp 会抢同一个 surface/解码器 —— 表现是"声音打架/画面黑屏"。
        //   这正是"小窗里还播着 A，又从详情页点了 B"的情形（`keepPlayerView` 让 View 能跨 Activity 复用，
        //   但同一个单例只能有一个主导播放器）。closePlayer() 是用户级"关掉播放器"，
        //   会保存进度、清通知栏、释放播放器，语义正好是"这里不再播了"。
        if (oldHost != null && oldHost.isOpened()) {
            oldHost.closePlayer()
        }
        pendingSource = source
        pendingPlayList = playList
        // 用**类名字符串**启动，而不是 `VideoPlayerActivity::class.java`：
        // 本文件在 `bilimiao-comm` 模块，而 Activity 在 `app` 模块 —— app 依赖 comm，反向引用不存在，
        // 直接写类会变成"Unresolved reference"（这就是这一版第一次编译失败的原因）。
        // 同一个约定在直播链路上已经用了三处（CoverViewModel / HomeLiveContent / SearchLiveContent）。
        context.startActivity(
            Intent().setClassName(context, "com.a10miaomiao.bilimiao.VideoPlayerActivity")
        )
    }

    /** 取走待播源（一次性）。取不到 = 没有播放任务 → 页面自己结束 */
    fun consumePendingSource(): BasePlayerSource? =
        pendingSource.also { pendingSource = null }

    /** 取走待播种的播放列表（一次性，可以为空） */
    fun consumePendingPlayList(): List<PlayListItemInfo> =
        pendingPlayList.also { pendingPlayList = emptyList() }

    /**
     * 系统回收（不是用户退出）时把源存回去：重建后 `onCreate` 会重新取到它并续播
     * （续播位置由 `PlayerDelegate2.openPlayer` 从 `PlaybackService`/本地记录里取，不靠这里）。
     */
    fun stashPendingSource(source: BasePlayerSource?) {
        if (source != null) pendingSource = source
    }
}
