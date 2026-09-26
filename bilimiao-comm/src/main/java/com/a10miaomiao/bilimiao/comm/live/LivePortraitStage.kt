package com.a10miaomiao.bilimiao.comm.live

import android.graphics.Rect

/**
 * 直播播放页的**竖屏版式坐标契约**（comm 模块 —— app 与 bilimiao-compose 都能 import 的唯一落点）。
 *
 * ## 为什么要有它
 * 竖屏下直播页的版式是"**上面视频、下面弹幕列表**"：
 * ```
 * ┌──────────────────────────┐ ← rootLayout 顶
 * │ 顶栏（蒙层，浮在视频上）      │
 * ├──────────────────────────┤ ← [portraitVideoBounds] 的底
 * │ 视频带（宽 ÷ 视频比例，封顶） │
 * ├──────────────────────────┤ ← [portraitDanmakuListBounds] 的顶
 * │ 弹幕列表区（宿主的面板摆这里） │
 * ├──────────────────────────┤ ← 列表区底 = 底栏顶（底栏不被遮）
 * │ 底栏（播放/画质/线路…）      │
 * └──────────────────────────┘
 * ```
 * 视频带由播放页（`LivePlayerActivity`，app 模块）排；弹幕列表面板由弹幕宿主
 * （`LiveDanmakuOverlayHost`，bilimiao-compose 模块）注入到 `android.R.id.content`。
 * 两边跨模块（compose 不能反向 import app），所以用这个接口当**数字版契约**。
 *
 * ## 与宿主实际接线的关系（★先看这一段再决定用哪个）
 * 宿主摆面板走的是**锚点 View**（`LiveDanmakuOverlayHost.bindPortraitListArea(slot, videoView,
 * bottomBound)`），播放页传给它的 `slot` 就是同一块矩形的**占位 View**
 * （`LivePlayerActivity.danmakuListSlot`，INVISIBLE、随 layout 自动更新）：
 * ```
 * 宿主面板的矩形  ==  slot 的实际 layout 结果  ==  [portraitDanmakuListBounds]
 * ```
 * 也就是说本接口是那块地方的**只读数字镜像**，给两类调用方：
 * 1. 只想读个数、不想持有播放页 View 的（例如浮层要把滚动弹幕收进视频区时要一个基准高度）；
 * 2. 需要在**没有 View 引用**的地方（例如播放页自己的其它逻辑）核对版式。
 * 摆面板本身请优先用 `bindPortraitListArea`（它自带 layout 变化重算）。
 *
 * ## 坐标系（★最容易搞错的一点）
 * 两个矩形都是 **`rootLayout`（= `setContentView` 的内容视图 = `android.R.id.content`
 * 的第一个子 View）的局部坐标，单位 px**。
 * 宿主的列表面板注入到 `android.R.id.content`，而 `rootLayout` 是它的 MATCH_PARENT 子 View
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
     * 1. **顶 = 视频区底**：列表紧贴视频下方，中间不留黑缝；
     * 2. **底 = 底栏顶**（底栏本身含系统导航栏内边距）：面板不许压住底栏按钮；
     * 3. **左右 = 整页宽**：`left = 0`、`width = 屏幕宽`。
     *
     * ★用户要的三件事由这三条直接满足：「固定在视频下面、一直显示」（顶边贴视频）、
     *   「不挡底栏」（底边让开底栏）、「不要切换按钮」（宿主侧那个入口胶囊已经删掉，
     *   竖屏列表是常驻的）。
     */
    fun portraitDanmakuListBounds(): Rect?
}
