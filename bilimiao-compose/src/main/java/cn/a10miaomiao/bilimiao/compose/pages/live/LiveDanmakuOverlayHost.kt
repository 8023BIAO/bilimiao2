package cn.a10miaomiao.bilimiao.compose.pages.live

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.a10miaomiao.bilimiao.compose.appColorScheme
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.live.danmaku.LiveDanmakuClient
import com.a10miaomiao.bilimiao.comm.live.danmaku.LiveDanmakuSettings
import kotlin.math.roundToInt

/**
 * 弹幕浮层的**宿主 View**（第二阶段 A 路新增的桥接文件）。
 *
 * ## 为什么需要它（而不是在 `LivePlayerActivity` 里直接写 `setContent { ... }`）
 * 播放页 `LivePlayerActivity` 在 **`app` 模块**，而 `app` 模块**没有开 Compose**
 * （`app/build.gradle.kts` 既没挂 `org.jetbrains.kotlin.plugin.compose`，也没有
 * `buildFeatures { compose = true }`，全模块 0 处 `androidx.compose` 引用）。
 * 要在那里直接调用 `@Composable`，就得给 app 模块加编译器插件 + 一串 Compose 依赖 ——
 * 那是比"加一行 HLS 依赖"大得多的构建改动，风险与收益不成比例。
 *
 * 于是把 Compose 那一侧留在**本来就有 Compose 的 `bilimiao-compose` 模块**，
 * 对 app 模块只暴露**纯 Android View** 的接口（`start()/stop()/release()`），
 * 两边都干净：
 * ```
 * LivePlayerActivity（app，纯 View）
 *   └─ LiveDanmakuOverlayHost（compose 模块，FrameLayout）
 *        ├─ LiveDanmakuClient(roomId)          ← B 路提供（comm.live.danmaku）
 *        ├─ ComposeView → LiveDanmakuOverlay(messages = …, settings = …, modifier = …, rollingVisible = …, chat = …)
 *        └─ ComposeView → LiveDanmakuChatPanel(chat = …, visible = …)   ← 注入到 activity 内容视图（见下）
 * ```
 * `client` / `LiveDanmakuOverlay` 的签名按 B 路锁定的契约直接使用，没有任何包装或改写。
 *
 * ## 弹幕设置（★本轮起：全部来自直播自己那套 `live_danmaku_*`）
 * 字号 / 不透明度 / 速度 / 显示区域 / 显示开关**只**来自直播设置页写的那几个键，
 * 翻译工作是 [LiveDanmakuSettings] 做的。**宿主负责订阅**：
 * [LiveDanmakuSettings.watch]（DataStore 流）→ `collectAsStateWithLifecycle`（初值走主线程 O(1) 的
 * [LiveDanmakuSettings.loadCached]）→ 一路传给浮层。
 *
 * ★与点播**彻底解耦**（用户："这他妈的相关的直播弹幕屏蔽词也给它去掉了"）：
 *   · 可见性 = `live_danmaku_enable`，**不再** ∩ 点播的三层开关；
 *   · 车道上限只由「弹幕显示区域」决定，**不再**读点播「滚动弹幕最大行数」；
 *   · **不做任何关键词过滤**，点播的词表对直播一条都不生效。
 *   所以宿主这里不再需要按点播模式（全屏/画中画）去换一套设置来订阅 ——
 *   `LiveDanmakuSettings.watch(context)` 没有 mode 参数了。
 *
 * ★为什么订阅放在宿主里而不是播放页：播放页在 app 模块、碰不到 Compose/DataStore，
 *   若让它先读一次再推下来，用户在设置页改完回到直播就"不会当场变"；
 *   宿主自己订阅 = **设置一改，画面当场变**，播放页一行都不用动。
 *
 * ## 生命周期
 * - [start]：开始连接（可反复调用，幂等）
 * - [stop]：断开（退到后台就该停，别让 WS 在后台空转烧流量）
 * - [release]：页面销毁时彻底释放（连 ComposeView 一起摘掉）
 *
 * 这三件事的语义**没有变**：听音频（`LivePlayerActivity.enterAudioMode`）、退后台
 * （`onStop`）、临时关弹幕（`applyDanmakuEnabled`）依旧靠 [start]/[stop] 控制；
 * 设置订阅只是在这之上多加了一道"用户把弹幕关了就别连"的闸门（见 [start] 的注释）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ## 竖屏弹幕列表：**常驻停靠**（vc168 任务：不挡底栏、不靠按钮切换）
 *
 * ### 用户原话（这一版要解决的两件事）
 * > "还有弹幕列表把那个按钮给挡住了，我有点想让它也放到底栏去，要么就是**固定在那个视频下面**，
 * >  直接不要有一个说什么按钮才能让它显示去……就把那个列表**一直显示在它的下面**，
 * >  如果说它是竖屏的情况下。还有那个弹幕列表，就**一直显示到底部**就行了。
 * >  奇了怪了，它竟然也会卡到我那个底栏的几个按钮。"
 *
 * 拆成两条硬要求：
 * 1. **去掉"点按钮才显示"**：不再有浮动胶囊，竖屏时列表**常驻**；
 * 2. **不许压底栏**：列表的区域是"视频区下方 → 底栏上沿"，与底栏按钮**零重叠**。
 *
 * ### 上一版为什么真的会压住「发弹幕」（根因，值得记住）
 * 上一版把留白**写死**成两个 dp 常量：面板 `bottomMargin = 52dp`、胶囊 `bottomMargin = 56dp`
 * （`CHAT_PANEL_BOTTOM_MARGIN` / `CHAT_ENTRY_BOTTOM_MARGIN`），依据是"底栏实测高 ≈ 41dp"。
 * 那个 41dp 是**单行底栏**的尺寸。而 vc168 的 `rebuildBottomBar()` 把竖屏底栏改成了
 * **每行 5 颗、共两行**（`PORTRAIT_BUTTONS_PER_ROW = 5`，10 颗按钮 → 5+5）：
 * 单行 ≈ 14sp 字高 17dp + 上下 6dp padding = 29dp，两行 + 行间距 4dp×2 + 底栏自身 padding 4dp×2
 * ≈ **66dp，再加导航栏 insets（三键机型 ~48dp）**。于是：
 * - 胶囊（56dp）**正好落在第一行按钮上** —— 第一行第 5 颗就是「发弹幕」，与用户截图完全一致；
 * - 面板（52dp）同理，压住第一行。
 * ★教训（本版的设计原则）：**任何写死的 dp 留白都会在"底栏行数 / 导航栏 insets / 按钮集变化"
 *   时失效**。所以这一版**一个 dp 数字都不写**，全部改成"按实测几何算"（见下）。
 *
 * ### 新版式（竖屏）
 * ```
 * ┌──────────────────────────────┐
 * │  视频区（播放页负责靠上摆放）  │
 * ├──────────────────────────────┤ ← 列表顶边 = 视频画面底边
 * │ 用户名：内容                  │
 * │ 用户名：内容        （常驻）   │   最新贴下、上滑看历史、「↓ 回到底部」
 * │ 用户名：内容                  │   无标题栏、无胶囊、无收起按钮
 * ├──────────────────────────────┤ ← 列表底边 = 底栏顶边（绝不重叠）
 * │ 暂停 听音频 画质 线路 发弹幕   │
 * │ 弹幕  UP主  重试 旋转 画中画   │
 * └──────────────────────────────┘
 * ```
 *
 * ### 与播放页的接口（**唯一接线口**：[bindPortraitListArea]）
 * 列表矩形不猜、不写死，由播放页把三个锚点 View 交给宿主，宿主在**窗口坐标**里实测：
 * ```kotlin
 * // LivePlayerActivity.setupPlayerAndDanmaku() 里一行（会随 layout 变化自动跟进）：
 * //   ★现状（本轮只读播放页确认过）：slot = danmakuListSlot、bottomBound = bottomBar；
 * //   ★推荐再补上 videoView = videoContainer —— 宿主就有一条可靠的"画面底边"锚点，不必走结构兜底。
 * host.bindPortraitListArea(slot = danmakuListSlot, videoView = videoContainer, bottomBound = bottomBar)
 * ```
 * | 参数 | 含义 | 传 null 时的行为 |
 * |---|---|---|
 * | `slot` | 播放页**已经为列表预留好的空槽 View**（"预留矩形"最直接的表达） | 顶边改按画面底边推 |
 * | `videoView` | 竖屏视频**画面**所在 View；列表顶边 = 它的底边 | 宿主自己找画面（[findPictureView]），找不到才 `宿主高 ÷ 2` |
 * | `bottomBound` | 列表**不许越过**的 View（播放页底栏）；列表底边 = 它的顶边 | 兜底：宿主自己在内容视图里找"贴底那一条" |
 *
 * ★**顶边的取值优先级（本轮改过）**：`videoView` 锚点 > 宿主自己找到的画面（`TextureView`/`SurfaceView`）
 *   > 槽顶边 > 宿主高 ÷ 2。为什么槽不再是第一优先：槽是播放页"量过一次就不再变"的**镜像**，
 *   而画面底边是**活**的几何 —— 两个用户可见的 bug（转回竖屏列表不回来、视频与列表之间的黑缝）
 *   都是"镜像过期了还当真值"造成的，证据链见下文"根因与修法"一节。
 *   底边**一律以底栏（活锚点）为准**（★本轮：不再与"槽底边"取 min —— 槽也会过期，
 *   取 min 在两种残留下都会留下错的那个：盖住底栏=半透明残影 / 离底栏一大截=一大块空白），
 *   所以"不压底栏"这条硬约束一个字没松。
 *
 * ★★**键盘/转屏（本轮用户实测三条）**：底栏会**因为输入法 insets 而位移**（Android 15 起键盘不再让
 *   窗口变矮，是播放页自己按 `Type.ime()` 抬底栏的 —— 窗口尺寸**一个像素都没变**）。
 *   这种情况下"底栏动了"在宿主的自动重量链上（锚点 layout → 窗口级签名 → 组合 `LaunchedEffect`）
 *   要过一次重组才落到面板上，漏一环面板就停在旧矩形：盖住底栏 = 半透明残影，停在半空 = 一大块空白。
 *   所以播放页在几何落定后会**主动**调 [notifyPortraitListGeometryChanged]（同步、不依赖重组时机），
 *   这条入口是"面板底边实时跟随底栏"的最后一道保险。
 *
 * 三个兜底细节（都是为了"没人接线时也不会退化成上一版的 bug"）：
 * 1. **画面而不是容器**：`videoView` 若是"按比例居中/靠上摆画面"的容器（里面还有一层 TextureView），
 *    容器底边可能贴着屏幕底 —— 直接拿它当列表顶边会让列表高度为 0。所以取**最高的那个子 View**的底边
 *    （那才是真正的画面），取不到子 View 才用容器自己的底边；
 * 2. **底栏留白是"占位"而不是"当前可见高度"**：播放页底栏 4s 后会自动隐藏
 *    （`CONTROLS_AUTO_HIDE_MS`），若按 `isShown` 算留白，列表会随着每次点按**长高/缩短**，还会在底栏
 *    浮出来的瞬间盖住刚出现的按钮。隐藏的 View 不会重新 layout，`getLocationInWindow()` 拿到的仍是它
 *    **上次可见时的位置** —— 正好就是稳定的"底栏占位"；
 * 3. **找不到贴底那一条就铺到底**：只有真找不到（比如底栏被 GONE 且从未 layout 过）才退回"列表到屏幕底"，
 *    此时不存在会被压住的按钮，是安全的降级。
 *
 * ★为什么仍然注入到 `android.R.id.content`（而不是塞进 `danmakuLayer`）：播放页的图层顺序被
 *   `buildUi()` 末尾的 `bringToFront()` 序列钉死，`tapCatcher`（全屏手势层，`isClickable = true` +
 *   `GestureDetector`）压在 `danmakuLayer` **上面**；放进弹幕层的任何东西都收不到触摸 ——
 *   列表会"看得见、滑不动"。注入 content 让它落在手势层之上，且**只占自己那块矩形**：
 *   矩形之外的触摸照旧落回手势层（亮度/音量手势、单击显隐控制条不受影响）。
 *   注入的 View 在页面销毁时由 [release] 摘掉，不会在 activity 的视图树上留垃圾。
 *
 * ★为什么不像播放页底栏那样"贴屏幕最底"：注入的 View 是 content 的**后加子 View**，
 *   天然压在 `rootLayout`（连同底栏按钮）**之上**。所以矩形必须**扣掉底栏与导航栏 insets**，
 *   否则又会变成"面板盖住按钮"——只是这次连点击都会被吃掉（比上一版更糟）。
 *   扣掉之后底栏自动隐藏时那段会露出 `rootLayout` 的黑底（与视频黑边同色，不跳动）——
 *   这是刻意的取舍：**宁可留一条稳定的黑边，也不要列表每 4 秒长高缩短一次**。
 *
 * ### 什么时候能用（[isDanmakuListAvailable]）
 * 竖屏（宿主自身"高 > 宽"，宿主铺满播放页，所以这就是屏幕方向）
 * ∩ 视图真正可见（`isShown`）∩ 非 PiP ∩ 注入目标就绪；组合侧再叠加"弹幕开着"这一条。
 * 横屏**仍然是滚动弹幕**（用户明确要求），PiP 里列表也没意义（PiP 窗口里连控制条都没有）。
 * ★PiP 判定不依赖 [setMode]：那个口子播放页目前**还没有调用点**（`grep "\.setMode(" app/` 为 0 处），
 *   所以这里直接读 `Activity.isInPictureInPictureMode()`（API 24 = 本工程 minSdk）。
 *   进 PiP 必然伴随宿主尺寸变化（`onSizeChanged`）→ 状态刷新 → 面板当场隐藏，不用播放页配合。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ### ★转屏后列表回不来 / 视频与列表之间那条黑缝：根因与修法（本轮，2026-09-26）
 *
 * 用户原话①："怎么我旋转全屏，再转回竖屏，我竖屏状态下专属的弹幕（用户名+内容那种列表）怎么不见了？"
 * 用户原话②："中间有一大块黑屏空呢……竖屏的情况下把它给占满？"
 *
 * #### 根因（两个症状是**同一个**结构性缺陷的两面）
 * 上一版把"列表画不画、画在哪"押在**播放页那个槽 View 的一次量测结果**上：
 * ```
 * 顶边 = slot.yInWindow()（槽顶边）        ← 单一来源、且没有任何"有效性"判断
 * 是否显示 = 矩形高 ≥ 96dp
 * ```
 * 而槽是播放页**只在 onConfigurationChanged / 进出听音频 / 退出 PiP 时**量一次的
 * （`LivePlayerActivity.measurePortraitStage()`），量到的值会以两种方式失真：
 *
 * | 场景 | 播放页实际行为（可复核的代码路径） | 上一版宿主的反应 |
 * |---|---|---|
 * | **转回竖屏** | `measurePortraitStageAfterLayout()` 的"当场量"分支在**窗口还没按新方向 resize** 时就跑了（`rootLayout.isLaidOut && !isLayoutRequested` 为真），量到的是**上一个方向的页面尺寸**：竖屏配置 + 横屏页面 → `videoContainer.bottom`（≈783）> `bottomBar.top`（≈1004）之间放不下 96dp → 走"地方太小"分支 `applyListSlot(top, 0)`；而那个一次性布局监听**已经用掉了**，resize 真正到来时**再没有任何东西会重量槽** | 槽高 0 → `rect.height < 96dp` → `listShown=false`，面板 GONE；锚点监听虽然还会响一次，但槽**永远是 0 高** → **列表再也不回来**（滚动弹幕接管，用户看到的就是"列表不见了"） |
 * | **视频换比例** | `onVideoSizeChanged()` 只做 `videoContainer.videoAspectRatio = w/h; requestLayout()`（`LivePlayerActivity` 1436-1443），**不会**再量版式 → 槽顶边停在"第一次量版式时按**默认 16:9** 算出来的值" | 比 16:9 **宽**的流：视频带变矮、槽顶边没变 → 视频与列表之间留一条**黑缝**（用户原话②）；比 16:9 **高**的流：列表**压住画面底部**。两个方向都是"槽是镜像、不是真值"造成的 |
 *
 * 换句话说：**槽顶边只是"视频带底边"的一个镜像，而镜像是会过期的**；
 * 上一版把镜像当真值，还没有任何"镜像过期/不可用"时的退路，所以一次坏的量测就能永久关掉列表。
 *
 * #### 修法（三件事，都在本文件里）
 * 1. **顶边以"画面底边"为准，槽降级为兜底**（[computeDockedRect]）：
 *    播放页接了 `videoView` 就用它；没接就在视图树里找正在放画面的那块
 *    （`TextureView` / `SurfaceView`，[findPictureView]）取它的底边 —— 那才是用户说的
 *    "视频带底边"，而且是**活的几何**（每次布局都读当前值），比例一变就跟着变，不会留缝也不会压画面。
 *    只有连画面都找不到时才退回槽顶边，最后才是"上半屏"这条老退路。
 * 2. **槽不可用 ≠ 列表不可用**：槽没布局 / 量成 0 高时**不再**因此关掉面板（那正是转屏现场的触发条件），
 *    改走上面那条兜底；面板显不显示最终只由"算出来的矩形够不够 96dp"决定（[CHAT_DOCKED_MIN_HEIGHT]）。
 * 3. **窗口级重量钩子**（[refreshChromeStates] 里那段 `ViewTreeObserver.OnGlobalLayoutListener`）：
 *    每一次布局跑完都算一遍**几何签名**（宿主尺寸 / 注入容器 / 槽 / 底栏 / **画面底边** / PiP），
 *    变了就重新量一次面板矩形。为什么必须有它：锚点监听只在"锚点自己的 bounds 变了"时才响，
 *    而本轮的两种失真恰好都是"锚点没变、页面变了"（槽被留在 0 高；视频带高度变了但槽的 layoutParams 没动）
 *    —— 上一版对这两种情况**一次都不会重量**。签名比对是 O(几次坐标读)，签名不变时连重组都不触发。
 *
 * ★同时保留的既有约定（一条都没改）：横屏 / PiP / 听音频 / 关弹幕 → 列表不显示、仍是滚动弹幕；
 *   竖屏列表在屏上时滚动弹幕整体让位；矩形不写死任何 dp 留白。
 *
 * ### 数据来源与性能（★本轮两条改动：横屏也累积 + 进房铺历史）
 * - 数据**只读复用** `client.messages`（`DANMU_MSG`）——不新开连接、不改协议、不加第二个订阅者：
 *   缓冲是在 [LiveDanmakuOverlay] 已有的那个 `collect` 里顺路存的（见那边的注释）。
 * - ★**横屏 / PiP / 听音频期间照样往里存**（用户实测："我在横屏的状态下，那些用户发弹幕，
 *   他不会记录在我的竖屏那里区域显示。我返回竖屏，我发现一条都没有。"）：
 *   上一版是 `if (listSupported) chat else null` —— 横屏连存字符串都不做；
 *   现在**恒传同一份 [chat]**，变的只有"列表画不画"（[listShown]）。
 *   累积代价 = 一次 `SnapshotStateList` 插入 + 200 条硬上限的裁剪；面板不在屏上时
 *   **没有任何组合在订阅** `chat.lines`（`LiveDanmakuChatPanel(visible = false)` 第一句就 return），
 *   所以"不可见 = 不干活"仍然成立（唯一多出来的就是那次 O(200) 的插入，与热门房 30 条/秒同阶可忽略）。
 * - ★**进房铺一次"最近的历史弹幕"**：`client.fetchHistory()`（PiliPlus 同款接口
 *   `xlive/web-room/v1/dM/gethistory`，出处见 [LiveDanmakuHistoryAPI] 的类注释）成功后
 *   `chat.addHistory(...)` 把它们接到列表**更旧的那一端**（不打扰用户当前阅读位置）；
 *   失败静默（结论落在 `LiveDanmakuTrace.historyState`），**不影响**实时链路一个字节。
 * - 列表上限 200 条（[LiveDanmakuChatLog]，硬上限，超出丢最旧）；面板不可用时**不组合**列表内容，
 *   显示时滚动弹幕整体让位（帧循环停）。
 * - ★**几何**那一侧的唯一开销仍然是上一轮加的**窗口级布局钩子**（本轮的弹幕/历史改动不碰它）：
 *   每次布局跑完读 6~7 个坐标（复用同一个 `IntArray`，
 *   零分配）、比一个长整型签名；签名不变时**不写任何 Compose 状态**（也就不会重组、不会重新布局）。
 *   画面 View 找到一次就缓存（[pictureFallback]），后续只读它的坐标，不做全树扫描。
 */
class LiveDanmakuOverlayHost(
    context: Context,
    roomId: Long,
    /**
     * 播放形态标记（全屏 / 画中画）。
     * ★本轮起它**只影响"竖屏弹幕列表能不能用"这一件事**（PiP 里列表没意义）——
     *   以前它还决定"订阅点播的哪一套弹幕设置"，但直播弹幕与点播彻底解耦后，
     *   设置只来自 `live_danmaku_*`，与这个模式无关了（见 [setMode] 的注释）。
     */
    mode: SettingPreferences.Danmaku = SettingPreferences.DanmakuFullMode,
) : FrameLayout(context) {

    /**
     * 弹幕开关。用 Compose 的 `mutableStateOf` 而不是 Flow：
     * 组合里直接读 `active.value` 就会在变化时自动重组，不需要额外引入 collectAsState。
     */
    private val active = mutableStateOf(false)

    /** 当前播放形态（画中画会切）。★只用于"PiP 里不显示竖屏列表"，不再影响弹幕设置来源 */
    private val modeState = mutableStateOf(mode)

    /**
     * 弹幕客户端 —— **公开**，供直播播放页做两件事：
     *   ① 发弹幕（`client.sendDanmaku(text)`）；
     *   ② 观察 `connectionState` 做"弹幕断了/重连"的提示与手动重连入口。
     *
     * 为什么从 private 改成 public（2026-09-26）：播放页在 `app` 模块、宿主在 `bilimiao-compose` 模块，
     * 之前唯一的访问方式是**按字段类型匹配的反射**（扛 R8 改名但很脆）。开一个只读入口就够了，
     * 反射那套可以整段删掉。
     */
    val client = LiveDanmakuClient(roomId)

    // ══════════════════════════════════════════════════════════════════════
    // 竖屏弹幕列表：状态、锚点、注入的 View
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 弹幕列表的数据缓冲（最新在 index 0）。公开是给"以后想让播放页也读一份"留的口子，
     * 现在只有浮层写、面板读。
     */
    val chat = LiveDanmakuChatLog()

    /**
     * 用户/播放页**是否还想让列表显示**。默认 **true = 常驻**（这一版的用户要求：
     * "直接不要有一个说什么按钮才能让它显示"）。
     * 保留这个可写开关只为两种情况：① 旧调用点（`setDanmakuListOpen(false)`）还能编译且语义合理；
     * ② 以后播放页真想临时腾地方（比如全屏看画面）时有一个现成的口子 —— 但**界面上没有任何按钮**
     * 会去动它，所以正常使用就是常驻。
     */
    private val listWanted = mutableStateOf(true)

    /**
     * **该直播间是否关闭了弹幕**（★本轮新增，由播放页判定后从 [setRoomDanmakuClosed] 写进来）。
     *
     * 打开时（true）**同时关掉两样东西**：
     * ```
     * ① 竖屏弹幕列表：并进 listSupported（→ refreshDockedPanel(false) → 面板 GONE）
     * ② 滚动弹幕：整块 LiveDanmakuOverlay 不再组合（横屏竖屏一起关）
     * ```
     * ## 为什么是"不组合"而不是"组合了但不画"
     * 滚动弹幕那一层的每一帧都由它自己的帧循环驱动（`withFrameNanos`）——"画不画"只是省了绘制，
     * 循环、车道、文本测量都还在跑；而 [LiveDanmakuOverlay] 是本文件里唯一持有那份帧循环的东西，
     * 不组合它就等于**彻底没有这条链路**（这也正是 PiP/听音频那几档的既有做法：靠组合外的门控）。
     *
     * ## 为什么弹幕长连接**照旧**
     * 这个门**不碰** `active` / `settings.visible`（[start]/[stop] 与 `client.connect()` 的门都在那边）：
     * 关弹幕的房间里 WS 仍然连、心跳照发（在线人数/事件流无害），只是不再有东西把弹幕画出来。
     * 用户明确要求"不要因此引入重连风暴"——这里连一次 connect/disconnect 都不会发生。
     *
     * ★默认 false = 没关闭：播放页拿不到判定（风控/断网）时也走这一档，绝不误关。
     */
    private val roomDanmakuClosed = mutableStateOf(false)

    /**
     * **此刻列表真的画出来了吗**（竖屏 ∩ 弹幕开着 ∩ 视图可见 ∩ 非 PiP ∩ 有足够地方放）。
     *
     * 两个消费者，必须是**同一个信号**：
     * - 浮层的 `rollingVisible = !listShown`（列表在，滚动弹幕让位；这也是"不重复显示同一条"的落点）；
     * - 面板自己的组合 `visible = listShown`（false 时**不订阅** `chat.lines`，做到"不可见不干活"）。
     * ★不能直接用"竖屏"当这个信号：注入的目标/地方大小都要等实测（见 [refreshDockedPanel]）。
     */
    private val listShown = mutableStateOf(false)

    /**
     * 竖屏判定。★用**宿主自身的尺寸**（高 > 宽）而不是 `Configuration.orientation`：
     * 宿主是 MATCH_PARENT（铺满播放页），尺寸就是屏幕尺寸；而播放页声明了
     * `configChanges=orientation|screenSize`（自己处理旋转，不重建 Activity），
     * 用尺寸判断最直接，也不用和 Configuration 的更新时机赛跑。
     */
    private val portrait = mutableStateOf(false)

    /**
     * 宿主当前是否真正可见（`isShown`：含祖先 GONE 与窗口不可见）。
     * ★为什么用它当闸门：听音频（`danmakuLayer.visibility = GONE`）、临时关弹幕、
     * 退后台、进 PiP 这几种情况都汇到这一个信号上，不用分别去 hook 播放页的每个分支。
     * 注意面板是注入到 content 里的，**不受 danmakuLayer 的 GONE 影响**，
     * 所以这个信号必须取自"宿主自己"（它在 danmakuLayer 里）。
     */
    private val chromeShown = mutableStateOf(false)

    /** 注入成功/失效的代数：让组合里的应用逻辑能因为"View 树就绪"再跑一次 */
    private val chromeGeneration = mutableStateOf(0)

    /** 构造/初始化完成前，View 回调（onSizeChanged 等）可能先到，用这个闸门挡住 */
    private var chromeReady = false

    /** 注入目标（activity 内容视图）与注入的面板 View */
    private var chromeHost: ViewGroup? = null
    private var listPanel: ComposeView? = null

    /** 注入的 post 还挂着（防重入：多次 onSizeChanged 不能插出两个面板） */
    private var chromeInjecting = false

    /** 本次算出来的停靠矩形（宿主坐标 px）。**不是** Compose 状态：只有 [refreshDockedPanel] 读写 */
    private var dockedRect = DockedRect(0, 0)

    /**
     * 上一次算出来的**几何签名**（[layoutSignature]）。
     * 只在主线程读写（布局回调 / 组合），所以普通字段就够，不需要 @Volatile。
     */
    private var lastLayoutSignature = 0L

    /** 复用同一个坐标数组：签名每次布局都要算一遍，布局回调里一次分配都不做 */
    private val scratchLocation = IntArray(2)

    /**
     * 挂在**窗口**上的全局布局监听（注册在 `onAttachedToWindow`、摘在 `onDetachedFromWindow`）。
     *
     * ★为什么除了锚点监听还需要它：锚点监听只在"锚点自己的 bounds 变了"时才响，而本轮两个 bug
     *   （槽被留在 0 高、视频带高度变了但槽的 layoutParams 没动）恰好都是**锚点没变、页面变了**。
     *   挂在 `viewTreeObserver` 上就覆盖了"任何一次布局跑完"这个时机，再由签名比对决定要不要重量。
     */
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    /**
     * 注册时用的那个 `ViewTreeObserver` 实例本身。
     * ★为什么要存实例：`View.viewTreeObserver` 在 View **已从窗口摘下**时会返回另一个"游离"实例，
     *   拿它 remove 等于没摘（监听还挂在真身上 → 白白多活一个窗口的生命周期）。
     *   存下注册时那个，摘的时候才对得上（且 `isAlive` 为假时跳过即可）。
     */
    private var globalLayoutObserver: ViewTreeObserver? = null

    /**
     * 兜底找到的"画面 View"（`TextureView` / `SurfaceView`）。见 [findPictureView]。
     * 找到就缓存：之后每次签名/量矩形只读它的坐标，不再全树扫描；它从树上掉下来/量成 0 时自动作废。
     */
    private var pictureFallback: View? = null

    // ── 播放页接线进来的三个锚点（都可空 = 没接线，走兜底规则）──
    private var slotView: View? = null
    private var videoAnchor: View? = null
    private var bottomAnchor: View? = null

    /**
     * 锚点自己 layout 一变（转屏 / 换清晰度改了视频比例 / 底栏 1↔2 行 / 导航栏 insets 变化）→
     * 重新量一次矩形。
     * ★为什么只"加一代"而不再当场改 layoutParams：这个回调发生在**布局过程中**，
     *   当场改注入 View 的 layoutParams 会触发系统那句
     *   `requestLayout() improperly called … during layout`（白补一次 layout + 刷屏告警）。
     *   把代数 +1 交给组合里的 `LaunchedEffect` 去做，就落在布局之后了。
     */
    private val anchorLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        chromeGeneration.value += 1
    }

    private val composeView = ComposeView(context).apply {
        setContent {
            val on = active.value
            val currentMode = modeState.value

            // ★设置订阅：DataStore 一推新值，`settings` 就换一份新的 →
            //   浮层里所有 `remember(settings, …)` 的 key 跟着变 → 字号/速度/不透明度/
            //   显示区域/显示开关**当场**生效，不需要重进直播间。
            //   initialValue 用 loadCached()（主线程 O(1) 的内存快照），首帧就有正确的值，
            //   不会先按默认值画一帧再跳变。
            //   ★不再有 `remember(currentMode)` 这一层：设置只来自 `live_danmaku_*`，
            //     全屏/画中画读的是同一份（本轮"与点播彻底解耦"的落点）。
            val settingsFlow = remember { LiveDanmakuSettings.watch(context) }
            val settings by settingsFlow.collectAsStateWithLifecycle(
                initialValue = remember { LiveDanmakuSettings.loadCached() },
            )

            // 连不连：① 用户/页面要弹幕（on）② 直播设置里没把弹幕关掉（settings.visible =
            // `live_danmaku_enable`，**不再**看点播那三层开关）。
            //   ★只"不发起连接"，**不主动 close**：这里省掉的是"进房/回前台就白连一条 WS"；
            //   若用户是在本页停留期间把设置关掉，已连上的会话会保持到页面 stop() 为止。
            //   之所以不 close()：`close()` 是"释放"语义（播放页 stop() 用），
            //   设置反复开关时跟着反复 close/connect 会重取 token（WBI 接口有风控风险，
            //   见 LiveDanmakuClient 的 ensureConnectionInfo 注释），得不偿失。
            //   用户把开关打开时 connect() 会因"已在连接"而直接返回，不会有多余往返。
            //   active 翻 false 时这个协程会被取消（connect 若是长挂的挂起函数正好在这里被掐断），
            //   真正的断开由 stop() 里的 client.close() 兜底 —— 与接线前完全一致。
            LaunchedEffect(on, settings.visible) {
                if (on && settings.visible) client.connect()
            }

            // ★**进房铺一次"最近的历史弹幕"**（用户："点进去发现有最近的弹幕或者评论……
            //   我他妈也要抄这个，要不然我进去一脸懵，人家最近在讨论什么我都不知道"）。
            //   · 独立协程：不阻塞上面那个 connect()（它要握手），也不受连接成败影响；
            //   · 失败静默：`client.fetchHistory()` 契约上**永不抛**、失败/空都回空列表，
            //     结果只落在 `LiveDanmakuTrace.historyState`（release 也写 trace 文件）；
            //   · 为什么由宿主铺、而不是让它走 `client.messages`：① 旧弹幕不该在滚动层再飞一遍；
            //     ② `messages` 是 replay=0 的 SharedFlow，进房那一瞬浮层可能还没订阅 → 会静默丢；
            //   · 铺完再预热"我自己叫什么"（自己发的弹幕要显示昵称），它同样失败静默。
            LaunchedEffect(on, settings.visible, roomId) {
                if (!on || !settings.visible) return@LaunchedEffect
                val history = client.fetchHistory()
                if (history.isNotEmpty()) chat.addHistory(history)
                client.warmUpSelfNickname()
            }

            // ★竖屏弹幕列表**能不能画出来**：弹幕开着 + 视图真正可见 + 竖屏 + 不是 PiP。
            //   四项缺一不可 —— 横屏/PiP 下必须仍是滚动弹幕（用户明确要求）。
            //   ★本轮语义收窄：这个布尔**只决定"列表画不画"**（→ refreshDockedPanel / rollingVisible），
            //     **不再决定"缓不缓冲"** —— 缓冲（chat）横屏也照样收，见下面 LiveDanmakuOverlay 的传参。
            //   ★`currentMode` 在本轮只剩这一个用处（PiP 判定）；它不再影响弹幕设置。
            val listSupported = on && settings.visible &&
                // ★该房间关闭了弹幕 → 列表槽收掉（"不显示竖屏弹幕列表"）。
                //   它与下面滚动弹幕用的是**同一个** roomDanmakuClosed：两处不能各判各的。
                !roomDanmakuClosed.value &&
                chromeShown.value && portrait.value && !isInPip() &&
                currentMode != SettingPreferences.DanmakuPipMode

            // 状态一变就把矩形/显隐落到注入的面板 View 上（主线程；真正算矩形的地方只有这一处）。
            // chromeGeneration 进 key：注入 View 是懒创建的、锚点 layout 也会变，
            // 建好之后要靠它再触发一次，否则第一次进页面可能"状态算对了但 View 还没建"。
            LaunchedEffect(listSupported, listWanted.value, chromeGeneration.value) {
                refreshDockedPanel(listSupported)
            }

            // ★该直播间关闭了弹幕 → **整块滚动弹幕不组合**（横屏/竖屏都关），见 [roomDanmakuClosed] 的注释。
            //   WS 连接不受影响（它的门是 active/settings.visible，在这段之外）。
            if (on && settings.visible && !roomDanmakuClosed.value) {
                LiveDanmakuOverlay(
                    messages = client.messages,
                    settings = settings,
                    modifier = Modifier.fillMaxSize(),
                    // 列表在屏上时滚动弹幕整体让位（不画、不入队、帧循环退出）——见类注释
                    rollingVisible = !listShown.value,
                    // ★本轮改动：**恒传同一份 chat** —— 横屏 / PiP / 听音频期间继续累积，只是不显示列表。
                    //   上一版是 `if (listSupported) chat else null`，那正是用户实测
                    //   "横屏收到的弹幕，转回竖屏一条都没有"的根因（横屏连存都没存）。
                    //   `listSupported` 现在只喂给 refreshDockedPanel / rollingVisible（画不画）。
                    chat = chat,
                )
            }
        }
    }

    init {
        // 自己不吃触摸事件：点按/双击要能穿透到播放页的手势层
        isClickable = false
        // 写全限定名，不依赖"继承来的嵌套类能否用简单名引用"这条边角规则
        addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        chromeReady = true
    }

    /** 开始连接（退到前台 / 用户打开弹幕开关时调用）。★设置里关着弹幕时不会连（见 setContent 的注释） */
    fun start() {
        active.value = true
    }

    /** 断开连接（退到后台 / 用户关掉弹幕时调用），View 本身保留，可以再次 [start] */
    fun stop() {
        active.value = false
        runCatching { client.close() }
    }

    /** 彻底释放（Activity onDestroy） */
    fun release() {
        stop()
        // ★注入到 activity 里的面板必须在这里摘掉：它**不在本 View 的子树里**，
        //   光 removeAllViews() 摘不掉，会留在 activity 的视图树上。
        removeChromeViews()
        // 锚点是播放页的 View（比本宿主活得久或一起销毁）：监听一定要在这里摘干净
        clearAnchors()
        runCatching { removeAllViews() }
    }

    /**
     * 标记当前播放形态（全屏 ↔ 画中画）。
     *
     * ★本轮起它**只用于"PiP 里不显示竖屏列表"**（见 [isDanmakuListAvailable]）。
     *   以前它还会换一套点播的 `pip_*` 弹幕设置来订阅 —— 直播弹幕与点播彻底解耦之后，
     *   设置只来自 `live_danmaku_*`，PiP 下的差异只剩"窗口大小"这种纯几何因素，
     *   由浮层按实测尺寸自适应（车道数 = 区域高度 ÷ 车道高，区域比例是用户自己选的）。
     * ★必须在主线程调用（会写 Compose 状态）：播放页的 `onPictureInPictureModeChanged` 就在主线程。
     */
    fun setMode(mode: SettingPreferences.Danmaku) {
        if (modeState.value != mode) modeState.value = mode
    }

    /**
     * 当前生效的**直播**弹幕设置（主线程 O(1)，读的是进程级内存快照）。
     *
     * 给播放页用的只读入口，典型用途：底栏「弹幕」按钮的初值按 `visible` 走 ——
     * 现在 `visible` 就是 `live_danmaku_enable`（**不再∩点播的三层开关**），
     * 所以"按钮显示弹幕开、画面却一条没有"这种自相矛盾只可能来自会话级开关或渲染问题。
     */
    fun currentSettings(): LiveDanmakuSettings = LiveDanmakuSettings.loadCached()

    // ══════════════════════════════════════════════════════════════════════
    // 竖屏弹幕列表：公开接口（给播放页用；全部要求主线程调用）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * **竖屏列表停靠区的唯一接线口**（播放页调用；主线程）。见类注释"与播放页的接口"。
     *
     * ```kotlin
     * // LivePlayerActivity.setupPlayerAndDanmaku()（现状：slot + bottomBound；推荐补上 videoView）：
     * host.bindPortraitListArea(slot = danmakuListSlot, videoView = videoContainer, bottomBound = bottomBar)
     * ```
     * 三个参数任选（**顶边优先级（本轮改过）**：`videoView` > 宿主自己找到的画面 > `slot` > "上半屏"；
     * 下边界：`bottomBound` > 自己找贴底那一条。理由见类注释"根因与修法"）：
     * @param slot 播放页**已经为列表预留好的空槽 View**（"你给我留的矩形"最直接的表达）：
     *   列表铺满它。★宿主不会把面板塞进这个槽里，而是**在内容视图里复刻它的矩形** ——
     *   槽若在弹幕层/视频层里（在 `tapCatcher` 之下），塞进去就"看得见、滑不动"（见类注释）。
     *   ★它现在主要是**兜底**：播放页把它量成 0 高（转屏瞬间的旧几何）时宿主不再因此关掉列表；
     *   它的底边只在"没接 `bottomBound` 且找不到贴底那一条"时才当底边用（★本轮改动，
     *   见 [computeDockedRect]：底栏锚点是活的，槽是镜像）。
     * @param videoView 竖屏视频**画面**所在 View，列表顶边取它的底边；容器里还有一层真正的画面时，
     *   宿主会自动退到"最高的那个子 View"的底边（见类注释兜底细节 1）。
     *   ★**建议播放页一定接上它**（`videoView = videoContainer`）：宿主就不必走"在树里找画面"的结构兜底。
     * @param bottomBound 列表**不许越过**的 View（播放页底栏），列表底边取它的**顶边**；
     *   不传就由宿主在内容视图里找"贴底那一条"（Gravity.BOTTOM + WRAP_CONTENT + 有高度）。
     *
     * 传进来的锚点会被挂上 `OnLayoutChangeListener`：转屏、视频换比例、底栏 1↔2 行、
     * 导航栏 insets 变化都会让宿主**重新量一次**，不需要播放页再调一次本方法。
     * ★本轮又加了一道**窗口级**布局监听（`ViewTreeObserver.OnGlobalLayoutListener`）兜住
     *   "锚点没变、页面变了"的两种失真（槽被量成 0、视频比例变了但槽的 layoutParams 没动）——
     *   所以即使播放页那侧某次没重量，宿主这一侧也会在布局结束后自己发现并重量。
     */
    fun bindPortraitListArea(
        slot: View? = null,
        videoView: View? = null,
        bottomBound: View? = null,
    ) {
        clearAnchors()
        slotView = slot
        videoAnchor = videoView
        bottomAnchor = bottomBound
        listOfNotNull(slot, videoView, bottomBound).forEach { it.addOnLayoutChangeListener(anchorLayoutListener) }
        // 触发一次重算：也覆盖"播放页是在注入/布局之后才接线"的情况
        chromeGeneration.value += 1
    }

    /**
     * ★★本轮（键盘/转屏适配）：**立刻**按活几何重算一次停靠矩形（同步、主线程）。
     *
     * 谁调：播放页。底栏因为**输入法 insets**（Android 15 起键盘不再让窗口变矮，底栏是播放页
     * 自己按 insets 抬的）或**转屏换页高**而位移之后，它会调这里一次。
     *
     * 为什么不能只靠宿主自己的三条自动重量路（锚点 layout / 窗口级签名 / 组合 `LaunchedEffect`）：
     * 它们最终都要经过**一次重组**才落到注入面板的 layoutParams 上，而上面这两种变化是
     * "insets 变了但窗口尺寸没变"或"一次动画里连发几十帧"的形态 —— 只要其中一环没赶上，
     * 面板矩形就会停在旧值上，用户看到的就是：
     * · 底边停在**旧位置（更靠下）**→ 面板盖住已经被抬起的底栏 → 面板 90% 的底色把底栏
     *   压成"半透明的按钮/输入条残影"；
     * · 底边停在**中间态（更靠上）**→ 面板与底栏之间空出一大块（黑底）= "一大块黑色空白"。
     * 这里给播放页一个**不依赖重组时机**的同步入口，把"面板底边 = 底栏顶边"这条不变式立刻兑现。
     *
     * ★只重算**几何**，不重新判断"该不该显示"：那个判断要用组合里的弹幕开关/设置（`settings.visible`
     *   与 `active`），宿主不在这里猜。面板没在屏上时直接返回 —— 没有需要对齐的东西。
     */
    fun notifyPortraitListGeometryChanged() {
        if (!listShown.value) return
        refreshDockedPanel(true)
    }

    /**
     * 展开/收起弹幕列表。
     *
     * ★vc168 之后竖屏是**常驻**（默认就是显示），所以 `open = true` 基本等于幂等；
     *   `open = false` 是留给"播放页想临时腾地方"的口子（界面上没有按钮会调它）。
     * @return true = 这次操作生效；false = 当前形态不支持（横屏 / PiP / 视图不可见），
     *   调用方可以据此给用户一句提示（比如"横屏下仍是滚动弹幕"）。
     *   ★为什么不"横屏也强行弹出来"：用户明确要求横屏仍是滚动弹幕，弹出来反而是错的。
     */
    fun setDanmakuListOpen(open: Boolean): Boolean {
        if (open && !isDanmakuListAvailable()) return false
        if (listWanted.value != open) listWanted.value = open
        return true
    }

    /** 停靠列表的显隐互切（默认常驻，所以这个口子平时用不到）。返回 [setDanmakuListOpen] 的语义 */
    fun toggleDanmakuList(): Boolean = setDanmakuListOpen(!listWanted.value)

    /** 用户/播放页是否还想让列表显示（默认 true = 常驻）。注意这不是"此刻在不在屏上" */
    fun isDanmakuListOpen(): Boolean = listWanted.value

    /**
     * **此刻列表是否真的在屏上**（竖屏 ∩ 弹幕开着 ∩ 可见 ∩ 非 PiP ∩ 地方够）。
     * 播放页若要显示"弹幕列表"状态文字，用这个；[isDanmakuListOpen] 只是用户的意愿。
     */
    fun isDanmakuListShown(): Boolean = listShown.value

    /**
     * 当前停靠矩形（**注入容器坐标系** px，`[0]=顶边, [1]=底边`）。
     *
     * 纯只读的观测口子（不改变任何行为）：播放页要打一行日志核对"列表到不到底栏上沿、
     * 顶边是不是视频画面底边"时用它，比截图量像素靠谱；`[1] <= [0]` 表示当前没有可用区域
     * （此时 [isDanmakuListShown] 必为 false，画面保持滚动弹幕）。
     */
    fun dockedListRectPx(): IntArray = intArrayOf(dockedRect.top, dockedRect.bottom)

    /**
     * 当前形态能不能用弹幕列表（竖屏 + 可见 + 非 PiP + 注入目标已就绪）。
     * 播放页可以用它决定要不要显示/置灰自己的入口。
     */
    fun isDanmakuListAvailable(): Boolean =
        // ★关闭弹幕的房间里"列表可用"恒为 false（与 setContent 里的 listSupported 同一个门）：
        //   播放页问这一项来决定要不要腾地方/提示时，得到的答案必须与"此刻真的会不会画"一致。
        !roomDanmakuClosed.value &&
            chromeShown.value && portrait.value && chromeHost != null && !isInPip() &&
            modeState.value != SettingPreferences.DanmakuPipMode

    /**
     * **该直播间关没关弹幕**（★本轮新增；播放页在进房判定后调用，主线程）。
     *
     * 只影响两件事：竖屏弹幕列表画不画、滚动弹幕组合不组合（见 [roomDanmakuClosed]）。
     * 幂等：值没变就一个字节都不写（Compose 的 `mutableStateOf` 本来也按相等性判断，这里再挡一道
     * 是为了"每次进房/每次判定回来都无脑调一次"这件事在语义上完全安全）。
     *
     * @param closed true = 该房间关闭了弹幕（输入条/列表/滚动弹幕一起收）；false = 照旧（默认）
     */
    fun setRoomDanmakuClosed(closed: Boolean) {
        if (roomDanmakuClosed.value != closed) roomDanmakuClosed.value = closed
    }

    /**
     * 是否在画中画里。
     *
     * ★为什么不只看 [modeState]：`setMode` 这个口子目前**播放页还没有调用点**
     *   （见上一节的说明），所以 PiP 判定不能押在它身上，直接问 Activity 最可靠。
     *   `Activity.isInPictureInPictureMode()` 是 API 24 的，本工程 `minSdk = 24`，不用版本判断。
     */
    private fun isInPip(): Boolean = context.findActivity()?.isInPictureInPictureMode == true

    /**
     * 显示/隐藏**本宿主自带的浮动入口**（旧版右下角那个「弹幕列表」小胶囊）。
     *
     * ★vc168"常驻停靠"之后**胶囊已经删掉了**（用户："直接不要有一个说什么按钮才能让它显示"），
     *   本方法保留**只为兼容旧调用点**（比如播放页某处仍写着 `setDanmakuListEntryVisible(false)`
     *   想关掉重复入口）：现在调用它什么都不会发生，但也不会把代码编译搞坏。
     */
    fun setDanmakuListEntryVisible(visible: Boolean) {
        // 故意的空实现：入口没了，"关掉入口"这个诉求自动成立
    }

    // ══════════════════════════════════════════════════════════════════════
    // 竖屏弹幕列表：View 注入、矩形测量与显隐
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 可见性 / 尺寸 / 版式变化 → 刷新状态（**本轮起也是"窗口级重量"的唯一入口**）。
     *
     * 覆盖点：
     * - `onSizeChanged`（转屏 → 竖/横屏）
     * - `onVisibilityChanged`（祖先 danmakuLayer 被 GONE：听音频 / 关弹幕）
     * - `onWindowVisibilityChanged`（退后台 / PiP）
     * - `onAttachedToWindow`
     * - ★[globalLayoutListener]（**每一次布局跑完**）—— 锚点没动但页面动了的两种情况（槽被量成 0、
     *   视频带高度变了而槽的 layoutParams 没变）只有这条能兜住，见类注释"根因与修法"。
     *
     * ★这些回调都在主线程，写 Compose 状态是安全的（重组会在下一帧统一发生）。
     * ★"每次布局都调用"**不等于**"每次都重组"：`portrait` / `chromeShown` 是等值写入（Compose 的
     *   `mutableStateOf` 默认按相等性判断，值没变就不失效），签名不变时更是连 `chromeGeneration` 都不动。
     */
    private fun refreshChromeStates() {
        if (!chromeReady) return
        // 方向：直接按宿主自己的尺寸算（宿主 MATCH_PARENT 铺满播放页）。
        // ★放在这里而不只在 onSizeChanged 里：尺寸/窗口 resize 完成之后重量一次，方向状态就不会卡在旧值上。
        if (width > 0 && height > 0) portrait.value = height > width
        chromeShown.value = isShown
        if (isShown) ensureChromeHost()
        val signature = layoutSignature()
        if (signature != lastLayoutSignature) {
            lastLayoutSignature = signature
            chromeGeneration.value += 1
        }
    }

    /**
     * 当前"会影响列表矩形"的全部几何的**指纹**。
     *
     * 进签名的每一项都必须满足一个条件：**它变了，矩形就可能要变**。
     * ```
     * 宿主宽高 / 注入容器(顶边+高+paddingTop) / 槽(顶边+高) / 底栏顶边 / 画面底边 / 是否 PiP
     * ```
     * ★为什么画面底边必须进签名：顶边现在优先取画面底边（见 [computeDockedRect]）——
     *   播放页换清晰度/换线路改了视频比例时，`videoContainer` 会重新量高，但槽的 layoutParams
     *   一个字节都没变（`onVideoSizeChanged()` 只 requestLayout，不再量版式），
     *   于是只有"画面底边"这一项能发现矩形该变了。
     * ★为什么用"指纹比对"而不是"每次都重量"：每次布局都重算矩形 = 每次布局都可能改注入 View 的
     *   layoutParams（再触发一次布局）——那才是真正会自激的写法。指纹只读几个坐标，零分配。
     */
    private fun layoutSignature(): Long {
        var s = -0x340d631b7bdddcdbL // 任意非零种子；首帧一定会与初值 0 不同 → 触发一次量测
        s = s * 31 + width
        s = s * 31 + height
        val parent = chromeHost
        s = s * 31 + (parent?.height ?: -1)
        s = s * 31 + (parent?.let { it.yInWindowInto(scratchLocation) } ?: -1)
        s = s * 31 + (parent?.paddingTop ?: -1)
        val slot = slotView
        s = s * 31 + (slot?.let { it.yInWindowInto(scratchLocation) } ?: -1)
        s = s * 31 + (slot?.height ?: -1)
        val bar = bottomAnchor
        s = s * 31 + (if (bar != null && bar.height > 0) bar.yInWindowInto(scratchLocation) else -1)
        s = s * 31 + (videoPictureBottomInWindow() ?: -1)
        s = s * 31 + (if (isInPip()) 1 else 0)
        return s
    }

    /**
     * 挂上窗口级布局监听（幂等）。
     * ★`onAttachedToWindow` 里 `mAttachInfo` 一定在 → `viewTreeObserver` 就是窗口那一个真身，存下来备用。
     */
    private fun registerGlobalLayoutListener() {
        if (globalLayoutListener != null) return
        val observer = viewTreeObserver
        if (!observer.isAlive) return // 极端情况：那还有锚点回调与 onSizeChanged 两条老路
        val listener = ViewTreeObserver.OnGlobalLayoutListener { refreshChromeStates() }
        observer.addOnGlobalLayoutListener(listener)
        globalLayoutObserver = observer
        globalLayoutListener = listener
    }

    private fun unregisterGlobalLayoutListener() {
        val listener = globalLayoutListener ?: return
        val observer = globalLayoutObserver
        globalLayoutListener = null
        globalLayoutObserver = null
        // 页面销毁后 ViewTreeObserver 可能已经死了，此时 remove 会抛 IllegalStateException —— 那种情况已经无所谓了
        if (observer != null && observer.isAlive) runCatching { observer.removeOnGlobalLayoutListener(listener) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        portrait.value = w > 0 && h > w
        refreshChromeStates()
        // 宿主自身尺寸变了（转屏/分屏）：矩形必须重量一次，别等锚点的 layout 回调
        // （窗口级监听通常也会在同一帧末尾发现，这里显式再来一次是为了不依赖那次时序）
        chromeGeneration.value += 1
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        refreshChromeStates()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        refreshChromeStates()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerGlobalLayoutListener()
        refreshChromeStates()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unregisterGlobalLayoutListener()
        // 宿主被摘掉（页面销毁）：注入的 View 也一并摘掉，别留在 activity 的视图树上
        removeChromeViews()
        chromeShown.value = false
    }

    /**
     * 解析注入目标 = activity 的内容视图（`android.R.id.content`）。
     * 见类注释"为什么仍然注入到 android.R.id.content"：必须在手势层之上，且只占自己那块矩形。
     */
    private fun resolveChromeHost(): ViewGroup? {
        val activity = context.findActivity() ?: return null
        return activity.findViewById<ViewGroup>(android.R.id.content)
    }

    /**
     * 只在第一次真正需要时解析注入目标（进页面时不用的人不付这份钱）。
     *
     * ★本轮加了一条"缓存失效"判据：内容视图**掉出窗口**（页面重建 / `content` 换了实例，
     *   例如 AppCompat 在某些 config 变化里重挂 subDecor）时重新解析一次 —— 否则我们会继续往
     *   一棵已经不在屏上的树里塞面板，表现同样是"状态对、列表看不见"。
     */
    private fun ensureChromeHost() {
        if (chromeInjecting) return
        val cached = chromeHost
        if (cached != null && cached.isAttachedToWindow) return
        if (cached != null) {
            // 旧目标已掉出窗口（页面重建 / content 换了实例）：只丢引用，**不去动**那棵已经不在屏上的树
            // （在布局过程中 removeView/disposeComposition 会触发系统那句"improperly called during layout"；
            //   而那份组合在 detach 时已经按默认策略 dispose 过了）
            chromeHost = null
            listPanel = null
            pictureFallback = null
            dockedRect = DockedRect(0, 0)
            listShown.value = false
        }
        val host = resolveChromeHost() ?: return
        // 解析回来还是那个"不在窗口里"的目标（页面正在拆 / 还没挂上）：等它自己回来，
        // 否则会在"丢引用 → 重新解析 → 又是它"之间白转（onAttachedToWindow / 全局布局回调还会再来）
        if (host === cached) return
        chromeHost = host
        chromeInjecting = true
        // ★不当场 addView：本方法最常见的调用点是 `onSizeChanged`，那会儿整棵树**正在 layout**，
        //   此刻往 activity 的内容视图里插 View 会触发系统那句
        //   "requestLayout() improperly called … during layout"（补一次 layout + 刷一屏告警）。
        //   统一丢到当前这一帧之后再做，代价是列表晚出现一帧（肉眼看不出）。
        post {
            chromeInjecting = false
            if (chromeHost !== host) return@post // 期间被 removeChromeViews 摘掉了
            chromeGeneration.value += 1
        }
    }

    /**
     * 面板：懒创建的 ComposeView（第一次真的要显示才建）。
     * 位置/尺寸**全部由 [refreshDockedPanel] 按实测矩形写进 layoutParams**，这里只给初值。
     *
     * ★本轮加了一道"面板还在不在"的自检：缓存的面板**必须仍挂在当前注入目标上**。
     *   不满足就把它丢掉、重新建一个 —— 面板是注入到 activity 的 View（不在宿主子树里），
     *   一旦被谁摘掉（页面重建、content 换实例、别的代码 removeAllViews），
     *   旧写法会继续对着一个已经不在树上的 View 设 `visibility=VISIBLE`：
     *   状态全对、屏上什么都没有，表现就是"列表不见了"而且怎么转屏都不回来。
     */
    private fun ensureListPanel(): ComposeView? {
        val host = chromeHost ?: return null
        val existing = listPanel
        if (existing != null && existing.parent === host) return existing
        if (existing != null) {
            // 只丢引用：它已经不在我们的注入目标里了，交给它的新父容器/GC 处理
            // （ComposeView 默认策略在 detach 时已 dispose 组合，这里不再多此一举）
            listPanel = null
        }
        val panel = ComposeView(context).apply {
            setContent {
                // ★本轮（2026-09-26）给面板套上**App 的主题**：这份组合是一棵**独立**的
                //   ComposeView（不在 `ComposeFragment` 的 `BilimiaoTheme` 子树里），不套的话
                //   列表里 `MaterialTheme.colorScheme.primary` 会落到 Material3 的**基线紫** ——
                //   用户要的"名字跟随我们主题色"就等于没做。
                //   · 色板仍由**同一个** `appColorScheme()` 算（调色算法全工程只有一份）；
                //   · 主题键的读取复用 `liveSheetThemeState()`（与直播设置弹窗 `LiveSettingSheet`
                //     同一个函数、同一批键），原因见那个函数的 KDoc：本页是独立 Activity、没有 Store/DI；
                //   · 只在组合第一帧读一次（`remember`）：面板里没有主题项，主题不会在它活着的时候变
                //     （换主题要回设置页，再进直播间就是新的一份）。
                // ★★`systemDark = true`（**刻意**不用 `isSystemInDarkTheme()`）：面板底色
                //   **恒为深色**（`CHAT_PANEL_BG = 0xE6101418`，视频页的"蒙层保持中性黑"约定，
                //   浅色主题下也是深色），所以这里要的是"为深底设计"的那一支色调 ——
                //   深色色板里 `primary` 是亮色调（tone 80），压在近黑面板上对比度 ≈10:1；
                //   而浅色色板的 `primary` 是给浅底用的暗色调（tone 40），压在近黑面板上只有 ≈3:1，
                //   13sp 的小字会发闷读不清（本轮之前那个写死的 `0xFF8AB4F8` 亮蓝就是亮色调，
                //   观感上也不该突然变暗）。**色相与彩度仍然完全来自用户主题色**，只是取了适配深底的明度。
                //   ⇒ 想改成"严格跟随 App 深浅色"的话：把下面的 `systemDark = true` 换成
                //     `isSystemInDarkTheme()` 即可（代价就是浅色主题下名字会偏暗）。
                val themeState = remember { liveSheetThemeState(context) }
                MaterialTheme(colorScheme = appColorScheme(themeState, systemDark = true)) {
                    // ★visible 用的是与 View 显隐**同一个信号**（listShown），不是裸的"竖屏"：
                    //   面板处于隐藏态时，这一份组合必须真的**不订阅** chat.lines
                    //   （只把 View 设成 GONE、组合还在跟着新弹幕重组，就不叫"不可见不干活"了）。
                    LiveDanmakuChatPanel(
                        chat = chat,
                        visible = listShown.value,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            visibility = View.GONE
        }
        host.addView(
            panel,
            // 宽 = 屏宽（列表要占满整行）、高 = 待会儿算出来的矩形高；Gravity.TOP + topMargin 定位
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                Gravity.TOP,
            ),
        )
        listPanel = panel
        return panel
    }

    /**
     * 把"该不该显示 + 显示在哪"落到注入的面板 View 上（主线程；状态由组合侧驱动）。
     *
     * 这是**唯一**算矩形的地方：
     * ```
     * 顶边 = 画面底边（videoView 锚点 > 结构找到的画面）> 槽顶边 > 宿主高÷2
     * 底边 = 底栏顶边（bottomBound 锚点 > 自己找到的贴底那一条）> 槽底边 > 宿主底边
     *        ★本轮：只要有"活"的底栏锚点就只认它 —— 不再与槽底边取 min（理由见 [computeDockedRect]）
     * ```
     * ★顶边这一行的**优先级是本轮改的**（原来"槽顶边"第一）：槽只是"视频带底边"的镜像、会过期，
     *   而画面底边是活的几何。两个症状（转屏后列表不回来 / 视频与列表之间的黑缝）都是"镜像当真值"
     *   造成的，见类注释"根因与修法"。
     */
    private fun refreshDockedPanel(supported: Boolean) {
        val panel = listPanel
        if (!supported) {
            // 横屏 / PiP / 听音频 / 弹幕关着：列表整体不参与（滚动弹幕接管，见组合里的 rollingVisible）
            dockedRect = DockedRect(0, 0)
            listShown.value = false
            panel?.visibility = View.GONE
            return
        }
        // 注入目标/面板还没就绪：等下一次 chromeGeneration（注入成功、锚点 layout、任何一次布局都会来）
        val p = ensureListPanel() ?: return
        val rect = computeDockedRect()
        dockedRect = rect
        // 地方太小（比如视频几乎占满、或底栏特别高）：宁可保留滚动弹幕，也不挤出一条读不了的列表
        val minPx = (CHAT_DOCKED_MIN_HEIGHT.value * resources.displayMetrics.density).roundToInt()
        val shown = listWanted.value && rect.height >= minPx
        listShown.value = shown
        p.visibility = if (shown) View.VISIBLE else View.GONE
        if (!shown) return
        // 注入的 View 是后加的、本来就在最上面；显式再提一次，防止播放页以后往 content 里加别的东西
        p.bringToFront()
        val lp = p.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.gravity != Gravity.TOP || lp.topMargin != rect.top || lp.height != rect.height) {
            lp.gravity = Gravity.TOP
            lp.topMargin = rect.top
            lp.height = rect.height
            p.layoutParams = lp
        }
    }

    /**
     * 算竖屏列表的停靠矩形（**注入容器的坐标系** px，可直接当 `topMargin` / `height` 用）。
     *
     * ★全程**不写死任何 dp 留白** —— 这正是上一版压住「发弹幕」的成因（见类注释的根因一节）。
     * 换算基准：宿主与锚点都在**同一个窗口**里，各自 `getLocationInWindow()` 的 y 相减，
     * 就得到容器坐标系里的位置（沉浸式下宿主铺满播放页，所以这套换算对 insets 也不敏感）。
     * ★为什么以"注入容器"而不是"宿主自己"为基准：矩形最终写给**注入 View 的 layoutParams**，
     *   而它的父容器就是 content。当前布局里两者重合（danmakuLayer/rootLayout 都铺满 content），
     *   以父容器为基准的话，播放页以后给 rootLayout 加内边距也不会让面板错位。
     * ★本轮再扣掉**父容器自己的 `paddingTop`**（FrameLayout 摆子 View 用的是 `paddingTop + topMargin`）：
     *   不扣的话面板会整体下移整整一个 paddingTop —— 在内容视图被系统/主题塞了 padding 的机型上，
     *   那就是视频与列表之间一条**凭空多出来的黑缝**。padding 为 0 时这一步是恒等变换。
     */
    private fun computeDockedRect(): DockedRect {
        val parent = chromeHost
        val parentTop = parent?.yInWindow() ?: yInWindow()
        val parentPaddingTop = parent?.paddingTop ?: 0
        val parentHeight = parent?.height?.takeIf { it > 0 } ?: height
        if (parentHeight <= 0) return DockedRect(0, 0)
        // 子 View 实际可用的高（FrameLayout 不会把子 View 摆进父容器的 padding 里）
        val contentHeight = (parentHeight - parentPaddingTop - (parent?.paddingBottom ?: 0)).coerceAtLeast(0)
        if (contentHeight <= 0) return DockedRect(0, 0)
        // 下边界：底栏顶边（不给锚点就自己找"贴底那一条"；找不到 = 不存在会被压住的按钮，铺到底）
        // ★★本轮：读一次就同时拿到"有没有活锚点"与它的值 —— 底边要不要认它，见下面那段。
        val bottomBoundTop = bottomBoundTopInWindow()
        val bottomLimit = ((bottomBoundTop ?: (parentTop + parentHeight)) - parentTop - parentPaddingTop)
            .coerceIn(0, contentHeight)
        val slot = slotView
        // 顶边第一优先：**画面底边**（= 用户要的"视频带底边"，也是唯一"活"的那个真值）
        val pictureTop = videoPictureBottomInWindow()?.let { it - parentTop - parentPaddingTop }
        val rawTop: Int
        val rawBottom: Int
        if (slot != null && slot.height > 0) {
            // 播放页已经留好一个矩形：铺满它，但仍然不许越过底栏。
            // ★顶边仍以画面为准（画面拿不到才用槽顶边）：槽是"量过就不再变"的镜像，
            //   视频换比例/转屏之后它可能还停在旧值上，那时按槽摆就会压住画面或留下黑缝。
            rawTop = pictureTop ?: (slot.yInWindow() - parentTop - parentPaddingTop)
            // ★★底边：有底栏锚点时**只认它**，不再与"槽的底边"取 min（本轮键盘/转屏适配的核心一行）。
            //   槽的底边只是"底栏顶边"的镜像，而且它是**播放页量过就不再变**的那一份：
            //   键盘抬起底栏（IME insets）/ 转屏换页高时，只要有一次重量没赶上，槽就停在旧值上，
            //   而 min() 在这两种残留下都会把**错的那个**留下 —— 这正是用户实测的三条现象：
            //   · 槽停在旧位置（更靠下，键盘抬起前）→ 面板底边越过底栏顶边 → 面板那层 90% 底色
            //     把底栏压成"半透明的按钮/输入条残影"（截图像素：底栏按钮的文字只剩 ~10% 亮度）；
            //   · 槽停在中间态（更靠上）→ 面板底边离底栏一大截 → "竖屏弹幕区域离底栏一大块空白"。
            //   底栏锚点是**活**的（每次都读它的当前坐标），拿它当唯一真值，两种残留都不可能出现。
            //   槽的底边仍然在"没接 bottomBound 且找不到贴底那一条"时兜底（如下）。
            rawBottom = if (bottomBoundTop != null) {
                bottomLimit
            } else {
                minOf(slot.bottomInWindow() - parentTop - parentPaddingTop, bottomLimit)
            }
        } else {
            // ★槽"没就绪"（没布局 / 被量成 0 高）**不等于**列表不该显示 —— 那正是转回竖屏时的现场。
            //   顶边退到画面底边；连画面都找不到才用"上半屏是视频"这条最老的兜底。
            rawTop = pictureTop ?: (contentHeight / 2)
            rawBottom = bottomLimit
        }
        val bottom = rawBottom.coerceIn(0, contentHeight)
        val top = rawTop.coerceIn(0, bottom)
        return DockedRect(top, bottom)
    }

    /**
     * 列表**下边界**（底栏顶边）在窗口坐标里的 y。
     *
     * 优先用播放页给的 `bottomBound`；没给就在内容视图里找"贴底那一条"。
     * ★两者都**不按 `isShown` 过滤**：播放页底栏 4s 后会自动隐藏（`CONTROLS_AUTO_HIDE_MS`），
     *   若按"现在可见吗"来留白，列表会在每次点按后长高/缩短，还会在底栏浮出来的瞬间盖住按钮。
     *   隐藏的 View 不会重新 layout，`getLocationInWindow()` 拿到的仍是它上次可见时的位置 ——
     *   正好就是我们要的稳定"底栏占位"（见类注释的取舍说明）。
     */
    private fun bottomBoundTopInWindow(): Int? {
        val anchor = bottomAnchor
        if (anchor != null && anchor.height > 0) {
            return anchor.yInWindow()
        }
        return findBottomBarTopInWindow()
    }

    /**
     * 没接线时的兜底：在内容视图里找"贴底那一条"（通常是播放页底栏）。
     *
     * 判据两条，缺一不可：
     * 1. **几何**：底边正好落在容器底边（±1px）—— 这才是"贴底"的定义；
     * 2. **结构**：高度是 `WRAP_CONTENT`（内容决定高度）且已经 layout 出高度。
     * 在播放页当前的树里满足这两条的唯一一个就是底栏：
     * 顶栏贴着**顶**边、缓冲圈在**中间**、视频/弹幕层/手势层/音频舞台是 MATCH_PARENT（整屏，不是"一条"）。
     * ★为什么**不**用 `(gravity and Gravity.BOTTOM) != 0` 这种位判断：`Gravity.CENTER`(0x11) 含垂直居中位
     *   (0x10)，与 `Gravity.BOTTOM`(0x50) 按位与非零 —— 缓冲圈正是 `Gravity.CENTER` + WRAP_CONTENT，
     *   一旦它转起来（`showLoading(true)`）就会被误判成"底栏"，把列表挤没；
     *   而未指定 gravity 的 `UNSPECIFIED_GRAVITY = -1` 更是与任何掩码都非零。几何判据没有这个坑。
     * ★为什么不去读播放页的字段：两个模块的依赖方向是 app → compose，compose 看不见 app 的类。
     */
    private fun findBottomBarTopInWindow(): Int? {
        val root = chromeHost ?: return null
        if (root.height <= 0) return null
        val containerBottom = root.yInWindow() + root.height
        // 容差取"1px 取整差"和"容器高的 5%"里更大的那个：
        // 播放页底栏底边就是容器底边（对齐），但若它外面以后包一层带 padding 的容器，5% 仍然兜得住；
        // 而缓冲圈在屏幕中间（离底 ≥ 40% 屏高），离得远，绝不会被这条容差漏进来 ——
        // 两种误判的代价不对等：漏掉底栏 = 列表压住按钮（用户报的 bug）；多认出"一条" = 列表不显示、
        // 保持滚动弹幕（安全降级）。所以宁可取宽的。
        val tolerance = (root.height / 20).coerceAtLeast(BOTTOM_SNAP_TOLERANCE_PX)
        var best: Int? = null

        fun visit(parent: ViewGroup, depth: Int) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i) ?: continue
                if (child === this || child === listPanel) continue // 自己和注入的面板不参与
                val lp = child.layoutParams
                val isBar = lp != null &&
                    lp.height == ViewGroup.LayoutParams.WRAP_CONTENT &&
                    child.height > 0 &&
                    child.bottomInWindow() >= containerBottom - tolerance
                if (isBar) {
                    val top = child.yInWindow()
                    val current = best
                    if (current == null || top < current) best = top
                    continue // 命中就不再往下钻（底栏里面的按钮不参与）
                }
                if (child is ViewGroup && depth < MAX_CHROME_SCAN_DEPTH) visit(child, depth + 1)
            }
        }

        visit(root, 0)
        return best
    }

    /**
     * 视频**画面**底边在窗口坐标里的 y。
     *
     * ★为什么不是直接用锚点自己的底边：播放页的 `videoContainer`（`AspectRatioFrameLayout`）
     *   现在还是 MATCH_PARENT，真正的画面（TextureView）是**按比例量好之后居中**放在里面的 ——
     *   容器底边 = 屏幕底边，拿它当列表顶边等于列表高度 0。所以取它**最高的那个子 View**的底边
     *   （那才是画面）；容器被播放页改成"按比例定高、靠上"之后，两者本来就会重合，这条也依然对。
     */
    private fun pictureBottomInWindow(anchor: View): Int {
        val group = anchor as? ViewGroup
        if (group != null) {
            var pic: View? = null
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) ?: continue
                if (child.height <= 0) continue
                if (pic == null || child.height > pic.height) pic = child
            }
            if (pic != null) return pic.bottomInWindow()
        }
        return anchor.bottomInWindow()
    }

    /**
     * "视频**画面**底边"在窗口坐标里的 y —— **列表顶边的首选来源**（用户要的"紧贴视频带底边"）。
     *
     * 三级来源，从可信到兜底：
     * 1. 播放页通过 [bindPortraitListArea] 显式接的 `videoView` 锚点（最可信：它知道自己哪个 View 是画面）；
     * 2. 缓存过的画面 View（[pictureFallback]，见 [findPictureView]）—— 只读坐标，不再搜树；
     * 3. 结构搜索：[findPictureView]。
     *
     * ★为什么不把"槽顶边"放在第一位：槽是播放页**量过一次就不再变**的镜像，
     *   而画面底边是**活**的几何。两个 bug（转屏后列表不回来 / 视频与列表之间的黑缝）
     *   都是"拿过期的镜像当真值"造成的 —— 详见类注释"根因与修法"。
     * ★画面底边什么时候不是"视频带底边"：只有播放页把画面竖向居中放在一条比画面更高的带子里时
     *   （当前不是这种版式：`AspectRatioFrameLayout` 的带子模式让画面**填满带子高度**，
     *   两者重合）。真出现竖向 letterbox 时，按画面底边摆只会让列表往上吃掉一点黑边 ——
     *   比"留一条黑缝/压住画面"都好。
     */
    private fun videoPictureBottomInWindow(): Int? {
        val anchor = videoAnchor
        if (anchor != null && anchor.height > 0) return pictureBottomInWindow(anchor)
        val cached = pictureFallback
        if (cached != null && isUsablePicture(cached)) return cached.bottomInWindow()
        pictureFallback = null
        val found = findPictureView() ?: return null
        pictureFallback = found
        return found.bottomInWindow()
    }

    /** 缓存的画面 View 还有效吗（还在窗口里、还量出了尺寸、还可见） */
    private fun isUsablePicture(view: View): Boolean =
        view.isAttachedToWindow && view.isShown && view.width > 0 && view.height > 0

    /**
     * 结构兜底：在内容视图里找"正在放画面的那一块"（`TextureView` / `SurfaceView`）。
     *
     * ★为什么敢用结构判据：本页（直播播放页）的视频渲染面是 `TextureView`，而且是**全工程唯一一处**
     *   （历史上特意从 `SurfaceView` 换过来的，见 `LivePlayerActivity.buildUi()` 的长注释：
     *   SurfaceView 的画面不在窗口里，"叠在视频上的 View 在不在上面"要看 ROM 的图层合成；
     *   `attachSurfaceView()` 那条回退路径现在也没人调）。所以"树里最大的那个 TextureView/SurfaceView"
     *   就是画面，判据唯一、不会误伤。真找不到就返回 null，退回槽顶边/上半屏，绝不给错值。
     * ★只在"没接 videoView 锚点、且缓存失效"时才会走到这里（正常路径一次都不搜）；
     *   搜到就缓存，后续只读坐标。下钻深度与底栏兜底同一个上限（[MAX_CHROME_SCAN_DEPTH]），
     *   当前树是 content → rootLayout → videoContainer → TextureView，3 层正好够。
     */
    private fun findPictureView(): View? {
        val root = chromeHost ?: return null
        var best: View? = null
        var bestArea = 0L

        fun visit(parent: ViewGroup, depth: Int) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i) ?: continue
                if (child === this || child === listPanel) continue
                if (child is TextureView || child is SurfaceView) {
                    if (isUsablePicture(child)) {
                        val area = child.width.toLong() * child.height.toLong()
                        if (area > bestArea) {
                            bestArea = area
                            best = child
                        }
                    }
                    continue // 画面 View 里面不会再有画面
                }
                if (child is ViewGroup && depth < MAX_CHROME_SCAN_DEPTH) visit(child, depth + 1)
            }
        }

        visit(root, 0)
        return best
    }

    /** 摘掉注入的面板（不碰锚点：锚点属于播放页，由 [release] 统一清） */
    private fun removeChromeViews() {
        listPanel?.let { panel ->
            (panel.parent as? ViewGroup)?.removeView(panel)
            // 摘掉即销毁这份组合（ComposeView 的默认策略是 detach 时 dispose，这里显式再来一次，
            // 幂等）；留着会让"列表不可见时也在订阅 chat.lines"变成真话
            panel.disposeComposition()
        }
        listPanel = null
        chromeHost = null
        chromeInjecting = false
        pictureFallback = null
        dockedRect = DockedRect(0, 0)
        listShown.value = false
        chromeGeneration.value += 1
    }

    /** 解开锚点引用与监听（[release] 用；[bindPortraitListArea] 重新接线前也会先清一遍） */
    private fun clearAnchors() {
        listOfNotNull(slotView, videoAnchor, bottomAnchor)
            .forEach { it.removeOnLayoutChangeListener(anchorLayoutListener) }
        slotView = null
        videoAnchor = null
        bottomAnchor = null
        // 重新接线意味着"画面的定义可能变了"：结构兜底那份缓存一并作废（下一帧会重新解析）
        pictureFallback = null
    }
}

/** 停靠矩形（宿主坐标 px）。用一个小类而不是 `IntArray`/`Pair`：多一处读起来要猜的地方 */
private class DockedRect(val top: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/** View 顶边在**窗口坐标**里的 y（px）；宿主与锚点在同一个窗口里，相减即宿主坐标系 */
private fun View.yInWindow(): Int {
    val loc = IntArray(2)
    getLocationInWindow(loc)
    return loc[1]
}

/**
 * 同上，但复用调用方给的数组。
 * ★给[LiveDanmakuOverlayHost.layoutSignature]用：那个函数每次布局都要算一遍，
 *   复用数组就一次分配都不做（布局回调里分配是白给 GC 找活干）。
 */
private fun View.yInWindowInto(loc: IntArray): Int {
    getLocationInWindow(loc)
    return loc[1]
}

/**
 * View 底边在窗口坐标里的 y（px）。
 * ★用"上次 layout 的位置 + 高度"，所以 View 被 GONE 之后这个值**仍然有效** ——
 *   底栏自动隐藏时我们要的正是"它占过的位置"（见 [LiveDanmakuOverlayHost.bottomBoundTopInWindow]）。
 */
private fun View.bottomInWindow(): Int = yInWindow() + height

/** 从 Compose/主题包装过的 Context 里找出 Activity（注入 View 需要它当"根"） */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** 兜底找底栏时的最大下钻深度：内容视图 → 播放页根布局 → 底栏，两层足够 */
private const val MAX_CHROME_SCAN_DEPTH = 3

/** "贴底"判定的像素容差（px）：底栏底边与容器底边对齐时可能有 1px 的取整差 */
private const val BOTTOM_SNAP_TOLERANCE_PX = 1
