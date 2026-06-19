# 播放器「关闭不干净」Bug 分析报告

## 1. 所有「关闭播放器」的路径

| # | 触发方式 | 调用链 |
|---|---------|--------|
| ① | 播放器 UI 的 X 按钮 | `DanmakuVideoPlayer.closeVideo()` → `VideoPlayerCallBack.onVideoClose()` → `PlayerController.onVideoClose()` → `PlayerDelegate2.closePlayer()` |
| ② | 完成弹窗「关闭视频」按钮 | `CompletionBoxController.completionCloseBtn.onClick` → `controller.smallScreen()` → `CompletionBoxController.hide()` → `delegate.closePlayer()` |
| ③ | 通知栏「关闭」按钮 | `PlaybackService.onCustomCommand("bilimiao.close")` → `playerDelegate?.closePlayer()` |
| ④ | 双击返回键 | `PlayerDelegate2.onBackPressed()` — 第一下提示"再按一次"，第二下 → `closePlayer()` |
| ⑤ | Activity 销毁（系统回收 / 配置变更 / 用户按返回退出） | `PlayerDelegate2.onDestroy()` → `views.videoPlayer?.detachView()` — **不走 `closePlayer()`！** |

路径 ⑤ 是特殊的：它只暂停底层播放器（`gsyVideoManager?.player?.pause()`），**不释放弹幕**，**不设 `showPlayer = false`**。

---

## 2. 为什么去掉 `autoCompletionHandled` 会暴露旧 Bug

### onAutoCompletion() 与 onCompletion() 的关系

先澄清一个关键点：**`super.onAutoCompletion()` 不会触发 `onCompletion()`**。它们是独立的：

```kotlin
// DanmakuVideoPlayer.kt:1135
override fun onAutoCompletion() {
    super.onAutoCompletion()               // GSY: 设 CURRENT_STATE_AUTO_COMPLETE，调 VideoAllCallBack.onAutoComplete
    videoPlayerCallBack?.onAutoCompletion() // → PlayerController: 展示完成弹窗 / 自动连播
    releaseDanmaku()                        // 释放弹幕引擎
}

// DanmakuVideoPlayer.kt:1191
override fun onCompletion() {
    super.onCompletion()                    // GSY: 设 CURRENT_STATE_NORMAL，断开 listener
    releaseDanmaku()                        // 释放弹幕引擎
}
```

`onCompletion()` 只在以下时机被调用：
- `DanmakuVideoPlayer.startPrepare()` 的 `this.gsyVideoManager.listener()?.onCompletion()` — 清理上一个 listener

所以「`onAutoCompletion` + `onCompletion` 双重 `releaseDanmaku()`」只在**自动连播**（`openPlayer` 替换当前源）时发生，普通播放完成不会双调。

### 去掉 autoCompletionHandled 的实际影响

`autoCompletionHandled` 的作用是防止 `onAutoCompletion()` 里的 `releaseDanmaku()` 被多次调用。

- **有 flag 时**：`onAutoCompletion()` 不释放弹幕 → 完成弹窗出现 → 用户点「关闭」→ `closePlayer()` 中第一次（也是唯一一次）释放弹幕 → 干净。
- **去掉 flag 后**：`onAutoCompletion()` 先释放弹幕 → 完成弹窗出现 → 用户点「关闭」→ `closePlayer()` 中 `releaseDanmaku()` IDEMPOTENT（`DanmakuView.handler` 已是 null，立即返回），`release()` 再次调 `releaseDanmaku()` 同样是 no-op。

**弹幕释放本身不会出僵尸**。`DanmakuView.release()` → `stopDraw()` 会把 handler 置 null、quit HandlerThread，第二次调用安全返回。

### 那旧 Bug 到底是怎么暴露的？

关键不在于弹幕是否释放了两次，而在于**关闭→退出→重进**这条路径中，有一个被释放的播放器 View 被复用了。

---

## 3. 根因：`keepPlayerView` 静态变量 + `PlayerBehavior` 初始状态不隐藏

### 核心问题链

```
① MainUi.kt:40  — companion object { private var keepPlayerView: DanmakuVideoPlayer? = null }
② MainUi.kt:62  — mVideoPlayerView = keepPlayerView?.apply { … } ?: inflate<DanmakuVideoPlayer>(…)
③ ScaffoldView  — var showPlayer = false (默认)
④ PlayerBehavior — onLayoutChild: 只在 showPlayer 发生 true↔false 转换时才做显隐动画
```

### Bug 触发路径

```
┌──────────────────────────────────────────────────────────────┐
│ 1. 视频自动完成                                               │
│    onAutoCompletion() → releaseDanmaku() → 完成弹窗显示       │
│                                                              │
│ 2. 用户点完成弹窗「关闭视频」                                  │
│    CompletionBoxController.hide() → videoPlayer.visibility = VISIBLE │
│    closePlayer() → releaseDanmaku()(no-op) + release()        │
│    showPlayer = false → ScaffoldView 播放器隐藏动画            │
│                                                              │
│ 3. 用户按返回键退出 Activity（或 Home 后进程存活）             │
│    onDestroy() → detachView() (仅暂停，不额外清理)             │
│                                                              │
│ 4. ★ keepPlayerView 仍持有已被 release() 的 DanmakuVideoPlayer │
│    （companion object 是静态变量，进程存活就不释放）            │
│                                                              │
│ 5. 用户重新进入 App                                           │
│    新 Activity → 新 MainUi                                    │
│    mVideoPlayerView = keepPlayerView  ← 复用旧 View！         │
│    新 ScaffoldView: showPlayer = false (默认)                  │
│    PlayerBehavior.onLayoutChild:                              │
│      showPlayer==false && isShowChild==false → 两个分支都不走  │
│      播放器被放在 (0,0)，alpha=1，完全可见！                   │
│                                                              │
│ 6. 结果：播放器布局可见，无视频数据，DanmakuView 空白/残留     │
└──────────────────────────────────────────────────────────────┘
```

### PlayerBehavior 的初始状态问题

```kotlin
// PlayerBehavior.kt:93
if (parent.showPlayer && !isShowChild) {
    isShowChild = true
    startShowAnimation(child)        // 从隐藏动画到显示
} else if (!parent.showPlayer && isShowChild) {
    isShowChild = false
    startHideAnimation(child)        // 从显示动画到隐藏
}
// 如果 showPlayer==false && isShowChild==false: 什么都不做！
```

当 `ScaffoldView` 新创建时 `showPlayer=false, isShowChild=false`——行为类**不会把播放器藏起来**。正常情况下没问题，因为 `openPlayer()` 之前播放器 View 还没加入 ScaffoldView（或者就是新 inflate 的）。但 `keepPlayerView` 复用路径破坏了这一前提。

---

## 4. `PlayerDelegate2.onDestroy()` 不设 `showPlayer = false` 的影响

```kotlin
// PlayerDelegate2.kt:358
override fun onDestroy() {
    savePlaybackPosition()
    // ...
    playerClosed = true
    playerCoroutineScope.onDestroy()
    // 注销广播接收器
    views.videoPlayer?.detachView()   // 仅暂停，不释放
    // ★ 没有 scaffoldApp.showPlayer = false ★
}
```

这是一个次要问题。如果 Activity 因配置变更（旋转屏幕）被销毁重建：
- `onDestroy()` 被调用，`showPlayer` 保持 `true`
- `onCreate()` 被调用，但没有 `openPlayer()` → 视频源不存在
- `ScaffoldView.showPlayer` 为 `true` → 播放器布局被显示，但无内容

不过实际上 `MainUi` 的创建可能在 `onCreate` 之前，`keepPlayerView` 持有旧 view，而新 ScaffoldView 的 `showPlayer` 是 `false`（默认）——所以这个问题单独不一定触发，但与根因叠加会加剧。

---

## 5. 修复方案

### 推荐方案（最简单有效）

**在 `closePlayer()` 中清掉 `keepPlayerView`，让下次进入时重新 inflate。**

修改 `PlayerDelegate2.closePlayer()`：

```kotlin
override fun closePlayer() {
    savePlaybackPosition()
    // ... 现有清理 ...
    vp?.releaseDanmaku()
    vp?.hideExpandButton()
    vp?.release()
    lastPosition = 0L
    
    // ★ 关键修复：清除静态缓存，防止释放后的播放器被复用
    MainUi.clearKeepPlayerView()  // 或直接置 null
    
    PlaybackService.instance?.notifyPlaybackComplete()
    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
```

在 `MainUi` 中添加：

```kotlin
companion object {
    private var keepPlayerView: DanmakuVideoPlayer? = null
    
    fun clearKeepPlayerView() {
        keepPlayerView = null
    }
}
```

**为什么这样就够了**：`keepPlayerView = null` 后，下次 Activity 重建时走 `?: inflate<DanmakuVideoPlayer>(R.layout.include_palyer2)` 分支，生成全新的播放器实例，不受之前 `release()` 影响。

### 备选方案（防御性更强）

同时修复 `PlayerBehavior` 的初始状态问题，确保 `showPlayer=false` 时播放器一定不可见：

```kotlin
// PlayerBehavior.kt onLayoutChild 中添加 else 分支
if (parent.showPlayer && !isShowChild) {
    isShowChild = true
    startShowAnimation(child)
    parent.updateContentOffset()
} else if (!parent.showPlayer && isShowChild) {
    isShowChild = false
    startHideAnimation(child)
    parent.updateContentOffset()
} else if (!parent.showPlayer && !isShowChild) {
    // ★ 防御：初始状态也确保播放器不可见
    child.alpha = 0f
    child.translationY = child.height.toFloat()
}
```

### 不做的事

- **不引入状态机**。问题本质是生命周期管理，不是状态转移。
- **不在 `onDestroy()` 设 `showPlayer = false`**。`onDestroy()` 语义是"Activity 即将消失"，此时修改 ScaffoldView 无意义，且可能触发不必要的 layout pass。
- **不删除 `keepPlayerView`**。它在配置变更场景下有实际价值（无缝保持播放器状态），只需在真正"关闭播放器"时清除。

---

## 6. 验证清单

修复后需验证以下场景：

1. ☐ 视频完成 → 完成弹窗 → 点「关闭」→ 返回退出 → 重进 → 播放器不可见
2. ☐ 播放中 → 点 X → 返回退出 → 重进 → 播放器不可见
3. ☐ 播放中 → 双击返回 → 重进 → 播放器不可见
4. ☐ 播放中 → 通知栏关闭 → 重进 → 播放器不可见
5. ☐ 播放中 → 旋转屏幕 → 播放器状态正常保持
6. ☐ 自动连播（下一P）→ 正常切换
7. ☐ 循环播放 → 正常从头开始
