package com.a10miaomiao.bilimiao.comm.live

import android.graphics.Rect

/**
 * 直播播放页的**竖屏版式坐标契约**（comm 模块 —— app 与 bilimiao-compose 都能 import 的唯一落点）。
 *
 * ## 为什么要有它
 * 竖屏下直播页的版式 = "**视频 + 弹幕列表**"，而列表**浮在画面上、铺到屏幕最底**：
 * ```
 * ┌──────────────────────────┐ ← rootLayout 顶
 * │ 顶栏（蒙层，**悬浮在画面上**；返回/标题） │   ← 2026-10-01 起：它不再占位
 * ├──────────────────────────┤ ← [portraitVideoBounds] 的顶（= **状态栏底边**）
 * │ 视频：竖屏流 = 铺满可用区（cover，左右各裁一点）；横屏流 = 一条带 │
 * ├ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─┤ ← [portraitDanmakuListBounds] 的顶（列表内容顶）
 * │ 弹幕列表内容（宿主的面板摆这里）        │
 * ├──────────────────────────┤ ← 列表**内容**可见底 = 底栏顶（键盘弹起时 = 键盘顶）
 * │ 底栏（渐变悬浮，压在弹幕**之上**）      │  ← 面板的**背景**继续铺到窗口底（含这一条）
 * └──────────────────────────┘ ← 窗口底
 * ```
 * 视频由播放页（`LivePlayerActivity`，app 模块）排；弹幕列表面板由弹幕宿主
 * （`LiveDanmakuOverlayHost`，bilimiao-compose 模块）注入到播放页给的
 * **`danmakuPanelLayer`**（`rootLayout` 里夹在 `hudLayer` 与 `topBar` 之间的那一层）。
 * 两边跨模块（compose 不能反向 import app），所以用这个接口当**数字版契约**。
 *
 * ## 与宿主实际接线的关系（★先看这一段再决定用哪个）
 * 宿主摆面板走的是**锚点 View**（`LiveDanmakuOverlayHost.bindPortraitListArea(slot, videoView,
 * bottomBound, panelLayer)`），播放页传给它的 `slot` 就是同一块矩形的**占位 View**
 * （`LivePlayerActivity.danmakuListSlot`，INVISIBLE、随 layout 自动更新）：
 * ```
 * slot 的实际 layout 结果  ==  [portraitDanmakuListBounds]     ← **内容**矩形（本接口发布的就是它）
 * 宿主面板 View 的矩形      ==  [顶边, **窗口底**]                ← 背景比内容多铺"底栏那一条"
 * 面板 contentPadding.bottom == 窗口底 − 底栏顶边（= 面板内边距，见宿主 `panelBottomInset`）
 * ```
 * （2026-10-01 用户拍板：弹幕区"把底栏铺满"、底栏按钮悬浮在弹幕之上 ⇒ 背景铺到窗口底；
 *   而**内容**仍止于底栏顶 / 键盘顶，最新一条不会被底栏或键盘盖住。）
 * 也就是说本接口是那块地方的**只读数字镜像**，给两类调用方：
 * 1. 只想读个数、不想持有播放页 View 的（例如浮层要把滚动弹幕收进视频区时要一个基准高度）；
 * 2. 需要在**没有 View 引用**的地方（例如播放页自己的其它逻辑）核对版式。
 * 摆面板本身请优先用 `bindPortraitListArea`（它自带 layout 变化重算）。
 *
 * ## 坐标系（★最容易搞错的一点）
 * 两个矩形都是 **`rootLayout`（= `setContentView` 的内容视图 = `android.R.id.content`
 * 的第一个子 View）的局部坐标，单位 px**。
 * 宿主的列表面板注入到 `danmakuPanelLayer` —— 它是 `rootLayout` 的 MATCH_PARENT 子 View
 * —— 两者原点相同，所以这些数字可以**直接当 LayoutParams 用**：
 * ```kotlin
 * val rect = (activity as? LivePortraitStage)?.portraitDanmakuListBounds() ?: return
 * panel.layoutParams = FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
 *     gravity = Gravity.TOP or Gravity.START
 *     leftMargin = rect.left
 *     topMargin = rect.top
 * }
 * ```
 *
 * ## 什么时候返回 null（调用方必须兜住）
 * · **横屏 / 分屏横向**：竖屏版式不生效（横屏仍是"整屏视频 + 滚动弹幕"）；
 * · **听音频模式**：没有画面，也没有列表；
 * · **页面还没量过**（`onCreate` 刚进、首帧之前）或**地方太小**（视频几乎占满 / 底栏特别高）：
 *   尺寸不可信，给不出该给的矩形。
 * 一律返回 `null`，调用方按"老版式 / 自己兜底"处理，**不要**拿 0 矩形去摆。
 *
 * ## 实现方与线程
 * 实现方：`app/src/main/java/com/a10miaomiao/bilimiao/LivePlayerActivity.kt`。
 * 只在**主线程**读（实现方在主线程量完就写字段，O(1) 读；返回的是副本，调用方改不到内部状态）。
 */
interface LivePortraitStage {

    /**
     * 竖屏**视频区**矩形（视频带，含左右黑边），`rootLayout` 局部坐标、px；非竖屏返回 `null`。
     *
     * ★用途一：把滚动弹幕**收进画面里**（浮层的车道基准高度取 `rect.height()`）。
     *   注意：**不要**用改宿主 View 尺寸的办法去收 —— `LiveDanmakuOverlayHost` 的竖屏判定
     *   读的是宿主自己的 `width < height`（宿主是 MATCH_PARENT 铺满播放页），把它压矮会让宿主
     *   判定成"横屏"，弹幕列表整个失效。要用高度就当车道基准，别改 View 尺寸。
     * ★用途二：播放页自己的手势气泡（音量/亮度）就按这块矩形垂直居中，见 `applyHudGeometry`。
     */
    fun portraitVideoBounds(): Rect?

    /**
     * 竖屏**留给弹幕列表的区域**矩形，`rootLayout` 局部坐标、px；非竖屏 / 地方太小返回 `null`。
     *
     * 定义（三条都是硬约束）：
     * 1. **顶** = 视频区底（横屏流：列表紧贴视频带下方）／ 底栏顶边往上让出"列表目标 + 底栏那一条"
     *    （竖屏流 cover：画面铺满可用区，列表改为**浮在画面上**的一块，顶边由这个目标高决定）；
     * 2. **底 = 底栏顶**：这是列表**内容**的可见底（面板背景会由宿主继续铺到窗口底，
     *    但内容止于此 —— 面板不许压住底栏按钮，键盘弹起时底栏顶边就是键盘顶边）；
     * 3. **左右 = 整页宽**：`left = 0`、`width = 屏幕宽`。
     *
     * ★用户要的三件事由这三条直接满足：「固定在视频下面 / 浮在画面上、一直显示」、
     *   「不挡底栏」（内容止于底栏顶；背景铺到底但底栏浮在面板**之上**，
     *   见 [LiveDanmakuOverlayHost] 类注释里的层级图）、「不要切换按钮」（宿主侧那个入口胶囊已经删掉，
     *   竖屏列表是常驻的）。
     */
    fun portraitDanmakuListBounds(): Rect?
}
