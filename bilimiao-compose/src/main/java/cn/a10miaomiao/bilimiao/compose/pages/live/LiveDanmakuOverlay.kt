package cn.a10miaomiao.bilimiao.compose.pages.live

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.a10miaomiao.bilimiao.comm.live.danmaku.LiveDanmakuSettings
import com.a10miaomiao.bilimiao.comm.live.danmaku.LiveDanmakuTrace
import com.a10miaomiao.bilimiao.comm.live.danmaku.LiveMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 直播弹幕浮层（第二阶段 B 路，方案 §4.2 的"第一版：Compose 自己画"）。
 *
 * ★**不碰点播的 DanmakuFlameMaster 管线**（方案 §4.1/§4.2 明确要求）：
 * 那套引擎是按**视频时间轴**驱动的，而直播弹幕是**流式到达**的，模型根本对不上；
 * 而且 `widget/player/` 目录属禁改区。所以这里用最朴素的
 * `Box` + `Modifier.absoluteOffset`（逐帧平移）+ 车道分配，实现量比那套小一个数量级。
 *
 * ### 渲染模型
 * - 屏幕横向切成若干条**车道**，弹幕从右边缘进入、匀速向左跑出（固定"穿越时长"，
 *   于是长弹幕速度更快，观感与 DanmakuFlameMaster 的固定 duration 一致）；
 * - **车道数 = 显示区域高度 ÷ 车道高**（`settings.areaFraction`：1/4 屏 / 半屏 / 全屏），
 *   字号 / 速度 / 不透明度 / 显示区域也全部来自 `settings`（一个参数带全部渲染参数）；
 *   ★本轮的解耦：**车道上限也只看 `settings.areaFraction` 自己** —— 以前这里还有一条
 *   "点播滚动弹幕最大行数"的外部上限，现在整条链路一个点播键都不读（见 `LiveDanmakuSettings`
 *   的文件头）；**也不做任何关键词过滤**（屏蔽词链路与状态条里的"屏蔽词过滤=N"本轮已删）。
 *   ★vc168 修的就是这里：接线时写成"点播行数上限 ?: 8"，于是**选全屏也只有 8 条车道**
 *   （8 × 22dp ≈ 176dp，占 2400px 竖屏的 19%、1080p 横屏的 43%）——详见文件末尾那段说明。
 *   **车道的分配策略（挑空得最久的那条）没有动**：它本来就均匀铺满可用车道，
 *   出问题的只是"可用车道"被钉死在 8 条；用户明确要求不动这套渲染/流畅度，所以只改上限的来源。
 * - `mode=5/4`（顶部/底部）的弹幕**不占车道**，居中固定在显示区顶/底，[FIXED_DURATION_MS] 后消失；
 * - 位置更新走 `withFrameNanos` 单帧循环，写入 `MutableFloatState`，
 *   并在 `Modifier.absoluteOffset { }` 的 lambda 里读 → **每帧只触发重新布局，不触发重组**；
 * - 空闲（没有待入场、屏上也没有弹幕）时帧循环**挂起等信号**，不白占 Choreographer。
 *
 * ### 出问题时的可观测性（第三阶段修复的重点）
 * 用户实测"看不见弹幕"时，release 包的日志是**空实现**
 * （`MiaoLogger.println`：`if (!BuildConfig.DEBUG && level != Log.ERROR) return`），
 * 于是"到底没收到 / 收到了没画出来"完全无从判断。这里给两条肉眼可见的通道：
 * - [debugCounters]=true（默认取 [LiveDanmakuTrace.overlayCounters]）→ 左上角常显一行计数：
 *   `收=弹幕流条数`、`上屏/在屏`、`帧/包`、`视图尺寸`、`连接状态`。
 *   **判据：`收>0` 而 `上屏=0` → 问题在渲染；`收=0` → 问题在链路（不是浮层）。**
 * - [statusHint]=true → 连接进入 `Failed` 时把**失败原因**直接显示出来（附带怎么重试），
 *   不再出现"用户什么都看不到、也永远不知道发生了什么"。
 *
 * ### 性能与防重叠（方案 §6.5：热门房每秒几十条）
 * | 问题 | 做法 |
 * |---|---|
 * | 上游太猛 | 上游 `SharedFlow` 已是 `DROP_OLDEST`；这里再叠一层：待入场队列上限 [MAX_PENDING]，超了丢最旧 |
 * | 渲染太快 | 入场上限 [MIN_SPAWN_INTERVAL_MS]（约 22 条/秒）+ 每帧最多 [MAX_SPAWN_PER_FRAME] 条 |
 * | 追不上 | 排队超过 [PENDING_MAX_AGE_MS] 直接丢 —— 直播弹幕迟到就没意义了 |
 * | 同车道重叠 | 车道互斥：上一条的**尾巴完全越过右边界** + [LANE_GAP] 间隙之后，该车道才允许放下一条 |
 * | 相邻车道重叠 | 车道高 = max(22dp, 字号 × [LANE_LINE_HEIGHT_FACTOR])：字号被调大时车道跟着变高 |
 * | 用户自己写的正则把画面清了 | ★不再存在：直播**不做关键词过滤**（本轮删掉屏蔽词链路），
 *   `收>0 上屏=0` 只可能是渲染/车道问题，判据反而更干净 |
 * | 长弹幕被截断 | 文本用 `wrapContentSize(unbounded = true)` 按真实宽度测量（不受父容器宽度约束） |
 * | 宽度未知 | 用 [estimateTextWidthPx] 估算且**宁大勿小**（估大只是车道利用率低，估小会叠字） |
 * | 尺寸未就绪 | 首帧 `width=0` 时帧循环先退出，尺寸到位后 `stage` 换 key 自动重启（不会白转） |
 *
 * ### 用法（A 路）
 * ```kotlin
 * val client = remember { LiveDanmakuClient(roomId) }
 * LaunchedEffect(roomId) { client.connect() }
 * // 设置从 DataStore 订阅（宿主 LiveDanmakuOverlayHost 已经这么做了）：
 * val settings by LiveDanmakuSettings.watch(context).collectAsStateWithLifecycle(
 *     initialValue = LiveDanmakuSettings.loadCached()
 * )
 * Box(Modifier.fillMaxSize()) {
 *     LivePlayerView(...)
 *     LiveDanmakuOverlay(messages = client.messages, settings = settings, modifier = Modifier.fillMaxSize())
 * }
 * DisposableEffect(Unit) { onDispose { client.close() } }
 * ```
 * 注意：`LiveMessage` 里除 [LiveMessage.Danmaku] 以外的类型（SuperChat / Gift / Popularity /
 * Watched / LiveStatus）**不由本浮层负责**；`SharedFlow` 支持多订阅者，A 路可以再 collect 一份自己渲染。
 *
 * @param messages 弹幕消息流，通常就是 `LiveDanmakuClient.messages`
 * @param settings 生效中的弹幕设置（[LiveDanmakuSettings]）。★**一个参数带全部渲染参数**
 *   （显示开关 / 字号 / 速度 / 不透明度 / 显示区域），比散着传 5 个 float 好维护，
 *   也让"改了设置不生效"只剩一个可能：调用方没把新的 settings 传下来。
 *   由宿主 `LiveDanmakuOverlayHost` 订阅 DataStore 得到 → 设置一改，这里当场换一份新的。
 *   ★这些字段**全部**来自 `live_danmaku_*`（本轮起与点播彻底解耦，连屏蔽词都不再读）。
 * @param modifier 外层修饰符；浮层自身会 `fillMaxSize()`（弹幕覆盖整个播放区）
 * @param debugCounters 是否显示左上角的调试计数条（release 也能看，默认跟随 [LiveDanmakuTrace]）
 * @param statusHint 连接失败时是否显示一行原因提示（默认开）
 * @param laneHeight 车道高的**下限**（默认 [LANE_HEIGHT]）。字号调大时会自动抬高车道，
 *   否则相邻车道会叠字（见下文 `laneHeightPx` 的算法）
 * @param rollingVisible 是否画滚动弹幕。false = **竖屏弹幕列表模式**（列表常驻停靠在视频下方）：
 *   滚动弹幕整体让位（不画、不入队、帧循环直接退出），消息只进 [chat] 那份列表缓冲。
 *   为什么不"两套一起显示"：用户的心智是"滚动弹幕 ↔ 弹幕列表"二选一（横屏仍是滚动弹幕），
 *   叠着显示等于同一条消息在一屏上出现两遍，而且列表模式下没人看滚动层，白烧一个 60fps 帧循环。
 * @param chat 竖屏弹幕列表的数据缓冲。★本轮起**恒为同一份**（横屏 / PiP / 听音频期间也继续往里存，
 *   只是不显示列表）—— 用户实测："我在横屏的状态下，那些用户发弹幕，他不会记录在我的竖屏那里区域显示。
 *   我返回竖屏，我发现一条都没有。" 传 null 只剩"弹幕整体关着 / 页面不在"这种情况：
 *   浮层那时压根不组合，连 collect 都不跑（**面板不可见时不订阅 `lines`，也不额外开销**这一点没变）。
 *   只读复用 `LiveDanmakuClient.messages`（同一个 collect，不开第二条连接、不加第二个订阅者）。
 *   进房那批"最近历史弹幕"不走这条流，由宿主用 [LiveDanmakuChatLog.addHistory] 直接铺底（见该方法）。
 */
@Composable
fun LiveDanmakuOverlay(
    messages: Flow<LiveMessage>,
    settings: LiveDanmakuSettings,
    modifier: Modifier = Modifier,
    debugCounters: Boolean = LiveDanmakuTrace.overlayCounters,
    statusHint: Boolean = true,
    laneHeight: Dp = LANE_HEIGHT,
    rollingVisible: Boolean = true,
    chat: LiveDanmakuChatLog? = null,
) {
    val density = LocalDensity.current
    // 渲染参数全部从 settings 派生（一处真值：设置页/链路改了值，这里只需要重建）
    val fontSizeSp = settings.fontSizeSp
    // 字号只在绘制时用得到（内部按 sp 转 px）；弹幕设置给的是 sp 数值
    val fontSizePx = fontSizeSp * density.density
    // 显示区域比例：兜底下限 [MIN_AREA_FRACTION]，再小就一条车道都放不下
    val areaFrac = settings.areaFraction.coerceIn(MIN_AREA_FRACTION, 1f)
    val textAlpha = settings.opacity.coerceIn(0f, 1f)
    val travelDurationMs = settings.travelDurationMs
    /*
     * ★车道上限：**只有"显示区域"这一个来源**（本轮解耦的落点之一）。
     *
     * 历史（vc168 修过一次，本轮又把最后一条外部上限摘掉）：
     * - 接线时写的是 `if (settings.maxLanes > 0) settings.maxLanes else DEFAULT_MAX_LANES(8)`，
     *   而 `settings.maxLanes` 来自**点播**的「滚动弹幕最大行数」，它的默认值是 **0 = 无限制**
     *   （`DanmakuDisplaySettingContent.kt:121` 的 `defaultValue = 0`）—— 于是**绝大多数用户**
     *   拿到的是那个兜底 8。8 条车道 × 22dp ≈ 176dp：在 1080×2400 的竖屏上只占 19%，
     *   在横屏 2400×1080 上占 43%，**显示区域选"全屏"也不会多出一条车道** ——
     *   这正是用户报的"我选全屏，进去热门直播间却还不是全屏"。
     * - vc168 把兜底 8 删了、并让"全屏"无视点播行数；本轮（用户要求"直播弹幕所有参数只来自
     *   `live_danmaku_*`"）连那条点播行数上限本身也不再读 —— `maxLanes` 字段已从
     *   `LiveDanmakuSettings` 整个删除。
     *
     * 现在的语义**只有一句**：车道数 = 显示区域高度 ÷ 车道高（见下面 [laneCount]）。
     * 于是"区域全屏 = 不限"、"选 1/4 屏就只有 1/4 屏那么多条车道"都是同一个公式的直接结果，
     * 没有任何继承来的外部数字能再盖住它。
     */
    /**
     * ★车道高必须**大于字号行高**：接线前字号写死 15sp、车道 22dp 正好不叠；
     * 现在字号可被**直播自己的**「弹幕字号」放到 48sp（`LiveDanmakuSettings.FONT_SIZE_SP_MAX`），
     * 再用固定 22dp 就会"上下两行字压在一起"。所以按字号给一个下限（1.4 倍 ≈ Compose 默认行高），
     * 调用方传的 [laneHeight] 只作为更大的那一档 —— 默认字号下 21dp < 22dp，行为与接线前完全一致。
     */
    val fontLineHeight = with(density) { (fontSizeSp * LANE_LINE_HEIGHT_FACTOR).sp.toDp() }
    val laneHeightPx = with(density) { maxOf(laneHeight, fontLineHeight).toPx() }
    val gapPx = with(density) { LANE_GAP.toPx() }

    // 文字样式：白字 + 黑影，保证在亮画面上也看得清（颜色按条覆盖）
    val textStyle = remember(fontSizeSp) {
        TextStyle(
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Medium,
            shadow = Shadow(color = Color.Black, offset = Offset(1.5f, 1.5f), blurRadius = 2f),
        )
    }

    // 浮层实际尺寸：先量出来才知道能放几条车道、弹幕要跑多远
    val sizeState = remember { mutableStateOf(IntSize.Zero) }
    val widthPx = sizeState.value.width.toFloat()
    val heightPx = sizeState.value.height.toFloat()
    // 弹幕可用的纵向区域（顶部/底部弹幕也按它摆放）
    val areaHeightPx = heightPx * areaFrac

    // 车道数：**只受"区域里能放几行"约束** —— 这就是"选全屏就真的铺满"的落点：
    // 区域=100% 时 byHeight = 整屏高 ÷ 车道高；选了 1/4 屏就只有 1/4 屏那么多条。
    // 小窗/横屏矮的时候自动减少，不会把弹幕排到看不见的地方。
    // ★本轮起**没有上限参数**：那条"点播滚动弹幕最大行数"的外部上限已删（见上面车道上限的注释）。
    val laneCount = remember(areaHeightPx, laneHeightPx) {
        if (areaHeightPx <= 0f || laneHeightPx <= 0f) 1 else (areaHeightPx / laneHeightPx).toInt().coerceAtLeast(1)
    }

    // ★remember 的 key 里**必须**带 settings：key 不变 → DanmakuStage 不重建 → 用户改了字号/速度/
    //   显示区域会"看起来完全没生效"（这是最容易漏的一条）。带上之后任何一项一变就换一个新 stage：
    //   在屏弹幕会被清掉重来 —— 这是设置变更的正常代价（不清的话旧速度/旧字号会和新设置混在一起）。
    //   注：DataStore 任何键变化都会推一次新快照，但 LiveDanmakuSettings 是 data class，
    //   字段全等时 remember 的 key 相等 → 不会白重建。
    val stage = remember(settings, laneCount, widthPx, areaHeightPx, laneHeightPx, gapPx, fontSizePx, travelDurationMs) {
        DanmakuStage(
            laneCount = laneCount,
            widthPx = widthPx,
            areaHeightPx = areaHeightPx,
            laneHeightPx = laneHeightPx,
            gapPx = gapPx,
            fontSizePx = fontSizePx,
            travelDurationMs = travelDurationMs,
            textAlpha = textAlpha,
        )
    }
    val wakeUp = remember { Channel<Unit>(Channel.CONFLATED) }

    /**
     * ★为什么用"持有器"而不是直接把 [stage] 收进 collect 的闭包：
     * 尺寸变化会重建 stage，如果 collect 的 key 里带上 stage，订阅就会在**每次尺寸变化时被取消再重建**，
     * `SharedFlow` 没有 replay → 重建的空档里到达的弹幕会**静默丢失**（横竖屏切换/首帧测量时最明显）。
     * 所以订阅只认 [messages]，投递时再去读"当前 stage"。
     */
    val stageHolder = remember { StageHolder() }
    stageHolder.stage = stage

    /**
     * ★[chat] / [rollingVisible] 都用 `rememberUpdatedState` 读**最新值**，而不是进 `LaunchedEffect` 的 key：
     * 订阅的 key 一旦跟着这两个参数变，`SharedFlow`（无 replay）就会在"切列表 / 转屏"的
     * 取消-重启空档里**静默丢弹幕** —— 这和上面 [stageHolder] 要解决的是同一类问题。
     */
    val chatState = rememberUpdatedState(chat)
    val rollingState = rememberUpdatedState(rollingVisible)

    // ① 收消息：滚动层入队 + 唤醒帧循环；竖屏弹幕列表顺路存一份
    LaunchedEffect(messages) {
        messages.collect { msg ->
            if (msg !is LiveMessage.Danmaku) return@collect
            // ★列表缓冲走**同一个 collect**：SharedFlow 虽然支持多订阅者，但同一批数据没必要起第二个协程。
            //   ★本轮改动：横屏 / PiP 期间**也照样存**（宿主现在恒传同一份 chat）——
            //   用户实测"横屏收到的弹幕转回竖屏一条都没有"，根因就是这里以前按形态把 chat 传成 null。
            //   存一条的代价 = 往**待提交缓冲**里 append + （缓冲满 40 条时）一次提交（见 LiveDanmakuChatLog）；
            //   面板不在屏上时没有任何组合在订阅它，所以"不可见不干活"仍然成立（只是"存"这件事继续做）。
            chatState.value?.add(msg)
            if (!rollingState.value) return@collect // 列表模式：滚动层让位（不入队 → 也不会有积压）
            val s = stageHolder.stage ?: return@collect
            s.enqueue(msg)
            wakeUp.trySend(Unit)
        }
    }

    // ② 帧循环（列表模式下**整个不跑**：滚动层没画东西，白转 60fps 就是白耗电）
    LaunchedEffect(stage, wakeUp, rollingVisible) {
        if (!rollingVisible) return@LaunchedEffect
        if (widthPx <= 0f) return@LaunchedEffect // 还没测量出尺寸（stage 的 key 含尺寸，下一轮会重启）
        while (true) {
            if (!stage.hasWork()) {
                // ★空闲时不占 Choreographer：等新弹幕的信号再继续（直播间安静时不该空转 60fps）
                wakeUp.receive()
                continue
            }
            val nowMs = withFrameNanos { it } / 1_000_000L
            LiveDanmakuTrace.frames.incrementAndGet()
            stage.advance(nowMs)
        }
    }

    // ③ 状态条（计数 / 失败原因）：**独立于帧循环**——
    //    因为"一条弹幕都没有"正是帧循环挂起的场景，靠帧循环刷新就永远看不到状态了
    var statusText by remember { mutableStateOf("") }
    // ★key 带 laneCount / areaFrac / rollingVisible：这三项是"显示区域到底生效没有"的直接答案
    //   （用户报的"选了全屏还不是全屏"），改了就该立刻反映到状态条上，而不是靠肉眼猜。
    //   （本轮之前 key 里还有一个"屏蔽词过滤器"：直播不做过滤之后它没了。）
    LaunchedEffect(debugCounters, statusHint, laneCount, areaFrac, rollingVisible) {
        if (!debugCounters && !statusHint) {
            statusText = ""
            return@LaunchedEffect
        }
        while (true) {
            statusText = buildStatusText(
                debugCounters = debugCounters,
                statusHint = statusHint,
                // 车道/区域只在调试计数条开着时显示（普通用户不该看到这行字）
                layoutHint = if (debugCounters) {
                    "区域=${(areaFrac * 100).roundToInt()}% 车道=$laneCount（上限：显示区域${if (areaFrac >= 1f) "，全屏=不限" else ""}）" +
                        if (!rollingVisible) " 列表模式" else ""
                } else {
                    ""
                },
            )
            delay(STATUS_REFRESH_MS)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                sizeState.value = it
                // 浮层尺寸也进可观测性：视图=0x0 就是"浮层根本没被布局"（宿主/父容器问题）
                LiveDanmakuTrace.overlayWidth = it.width
                LiveDanmakuTrace.overlayHeight = it.height
            },
    ) {
        // ★rollingVisible=false（竖屏弹幕列表模式）：整块滚动层不参与组合 ——
        //   不只是"看不见"，是**根本不建**这些 Text 节点；在屏的 item 留在 stage 里，
        //   切回来时帧循环第一帧就按"早就跑出左边"把它们回收掉（见 moveAndRecycle）。
        if (rollingVisible) {
            // ★「显示区域」（1/4 屏 / 半屏 / 全屏）的落地：所有弹幕都关在这一块里 ——
            //   滚动弹幕的车道按 areaHeightPx 算，顶部/底部固定弹幕也贴这块区域的顶/底，
            //   于是"选 1/4 屏 = 只有上 1/4 有弹幕"是所见即所得。
            //   （接线前 fixedBottom 贴的是**整个浮层**的底，区域选小了就自相矛盾。）
            //   区域固定贴顶部（车道从 y=0 往下排）；"贴底部 / 垂直居中"属于新语义，本版不做。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { areaHeightPx.toDp() }),
            ) {
                for (item in stage.active) {
                    key(item.key) {
                        Text(
                            text = item.text,
                            color = item.color, // 不透明度已在入队时乘进 item.color（见 DanmakuStage.enqueue）
                            style = textStyle,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                // 只读 item.x：状态变化 → 只重新布局，不重组（每帧都在变，重组会白烧 CPU）
                                // ★absoluteOffset 而不是 offset：offset 是 RTL 感知的（阿拉伯语等右到左语言下
                                //   会把 x 镜像），弹幕必须永远向左跑，所以用绝对偏移
                                .absoluteOffset { IntOffset(item.x.floatValue.roundToInt(), item.topPx.roundToInt()) }
                                // unbounded：弹幕比屏幕宽时按真实宽度测量，否则会被父约束截断成半句
                                .wrapContentSize(align = Alignment.TopStart, unbounded = true),
                        )
                    }
                }
                // 顶部/底部固定弹幕：交给 Box 的 Alignment 居中，不用自己算宽度
                for ((index, item) in stage.fixedTop.withIndex()) {
                    key(item.key) {
                        Text(
                            text = item.text,
                            color = item.color, // 不透明度已在入队时乘进 item.color（见 DanmakuStage.enqueue）
                            style = textStyle,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .absoluteOffset { IntOffset(0, (index * laneHeightPx).roundToInt()) }
                                .wrapContentSize(align = Alignment.TopStart, unbounded = true),
                        )
                    }
                }
                for ((index, item) in stage.fixedBottom.asReversed().withIndex()) {
                    key(item.key) {
                        Text(
                            text = item.text,
                            color = item.color, // 不透明度已在入队时乘进 item.color（见 DanmakuStage.enqueue）
                            style = textStyle,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                // BottomCenter 现在是**显示区域**的底（见上面内层 Box），不是整屏的底
                                .align(Alignment.BottomCenter)
                                .absoluteOffset { IntOffset(0, -(index * laneHeightPx).roundToInt()) }
                                .wrapContentSize(align = Alignment.TopStart, unbounded = true),
                        )
                    }
                }
            }
        }
        if (statusText.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(STATUS_PADDING)
                    .background(STATUS_BG, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    text = statusText,
                    color = STATUS_COLOR,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    maxLines = 4,
                )
            }
        }
    }
}

/**
 * 把"当前 stage"从一个不可变值变成可被 collect 闭包读到的**最新值**。
 *
 * 只在主线程（Composition / LaunchedEffect）读写，所以用普通字段就够，不需要 @Volatile。
 */
private class StageHolder {
    var stage: DanmakuStage? = null
}

/**
 * 弹幕浮层的运行时状态：车道 + 待入场队列 + 入场/回收逻辑。
 *
 * 全部可变状态都是 Compose 快照状态（[active] 是 `SnapshotStateList`、[LiveDanmakuItem.x] 是
 * `MutableFloatState`），所以谁改都行（这里由帧循环协程改），Compose 照样能观察到。
 */
private class DanmakuStage(
    private val laneCount: Int,
    private val widthPx: Float,
    private val areaHeightPx: Float,
    private val laneHeightPx: Float,
    private val gapPx: Float,
    private val fontSizePx: Float,
    private val travelDurationMs: Float,
    /** 弹幕整体不透明度（0~1，来自 `settings.opacity`）：**入队时就乘进颜色**，见 [enqueue] */
    private val textAlpha: Float,
) {
    /** 屏上**滚动**的弹幕（顺序即绘制顺序；每帧只增删少量元素） */
    val active = mutableStateListOf<LiveDanmakuItem>()

    /** 屏上**顶部固定**的弹幕（`mode=5`），最近的排在最上面 */
    val fixedTop = mutableStateListOf<LiveDanmakuItem>()

    /** 屏上**底部固定**的弹幕（`mode=4`），最近的排在最下面 */
    val fixedBottom = mutableStateListOf<LiveDanmakuItem>()

    private val pending = ArrayDeque<PendingDanmaku>()

    /** 每条车道"下次可入场时间"：防重叠的关键（见 [spawnPending]） */
    private val laneFreeAtMs = LongArray(laneCount)

    private var lastSpawnAtMs = 0L
    private var nextKey = 0L

    /** 还有活干吗？决定帧循环要不要跑（空闲就该挂起） */
    fun hasWork(): Boolean =
        active.isNotEmpty() || pending.isNotEmpty() || fixedTop.isNotEmpty() || fixedBottom.isNotEmpty()

    fun enqueue(msg: LiveMessage.Danmaku) {
        // ★本轮起**没有任何关键词过滤**（用户："直播弹幕去掉屏蔽词"）：原来这里会拿点播那份词表
        //   （`danmaku_filter_enabled` / `danmaku_filter_keywords`）在入队前把命中的弹幕丢掉，
        //   现在整条链路已删 —— 收到的每一条 `DANMU_MSG` 都会照常排队上屏。
        //   LiveMessage.Danmaku.color 是 0xRRGGBB，这里补上 alpha，并**当场乘上不透明度**：
        // 透明度是"画上去的样式"，在生成 item 时定好就只算一次（渲染处直接用 item.color，
        // 不再重复乘 —— 乘两遍会变成 alpha²，用户会看到"调 50% 实际只有 25%"）。
        // 设置在组合层是 stage 的重建 key：改了不透明度 → 新 stage → 之后入队的弹幕都用新值。
        val color = Color(0xFF000000.toInt() or (msg.color and 0xFFFFFF)).copy(alpha = textAlpha)
        if (msg.mode == LiveMessage.MODE_TOP || msg.mode == LiveMessage.MODE_BOTTOM) {
            // 滚动弹幕才需要"排队等车道"；固定弹幕不占车道，直接上屏（上限 [MAX_FIXED] 条）
            val list = if (msg.mode == LiveMessage.MODE_TOP) fixedTop else fixedBottom
            if (list.size >= MAX_FIXED) {
                list.removeAt(0)
                LiveDanmakuTrace.dropped.incrementAndGet()
            }
            list.add(
                LiveDanmakuItem(
                    key = nextKey++,
                    text = msg.text,
                    color = color,
                    startMs = 0L,
                    widthPx = estimateTextWidthPx(msg.text, fontSizePx),
                    speedPxPerMs = 0f,
                    topPx = 0f,
                ),
            )
            LiveDanmakuTrace.enqueued.incrementAndGet()
            return
        }
        if (pending.size >= MAX_PENDING) {
            pending.removeFirst() // 和上游一样：满了丢最旧
            LiveDanmakuTrace.dropped.incrementAndGet()
        }
        pending.addLast(PendingDanmaku(msg.text, color))
        LiveDanmakuTrace.enqueued.incrementAndGet()
    }

    fun advance(nowMs: Long) {
        spawnPending(nowMs)
        moveAndRecycle(nowMs)
        recycleFixed(nowMs)
        LiveDanmakuTrace.overlayActive = active.size + fixedTop.size + fixedBottom.size
        LiveDanmakuTrace.overlayPending = pending.size
        LiveDanmakuTrace.overlayLanes = laneCount
    }

    /** ① 入场：给待入场队列找空车道 */
    private fun spawnPending(nowMs: Long) {
        var spawned = 0
        while (pending.isNotEmpty() && spawned < MAX_SPAWN_PER_FRAME) {
            if (active.size >= laneCount * MAX_ITEMS_PER_LANE) return
            val head = pending.first()
            if (head.enqueuedAtMs == 0L) head.enqueuedAtMs = nowMs // 用帧时钟打戳，避免和别的时基混用
            // 过期就丢：直播弹幕迟到太久，显示出来只会和画面/声音对不上
            if (nowMs - head.enqueuedAtMs > PENDING_MAX_AGE_MS) {
                pending.removeFirst()
                LiveDanmakuTrace.dropped.incrementAndGet()
                continue
            }
            // 渲染限流：热门房每秒几十条，UI 侧按 MIN_SPAWN_INTERVAL_MS 卡住（方案 §6.5）
            if (lastSpawnAtMs != 0L && nowMs - lastSpawnAtMs < MIN_SPAWN_INTERVAL_MS) return
            val lane = pickFreeLane(nowMs) ?: return // 没有空车道 → 留在队列里等
            val item = createItem(head, lane, nowMs)
            active.add(item)
            // ★防重叠：上一条的"尾巴"越过右边界（= 宽度 / 速度）再加一个间隙，这条车道才放行下一条
            laneFreeAtMs[lane] = nowMs + ((item.widthPx + gapPx) / item.speedPxPerMs).toLong() + 1L
            pending.removeFirst()
            lastSpawnAtMs = nowMs
            spawned++
            LiveDanmakuTrace.shown.incrementAndGet()
        }
    }

    /** ② 位移 + 回收：只改 x（状态），整条移出左边就移除 */
    private fun moveAndRecycle(nowMs: Long) {
        var i = active.size - 1
        while (i >= 0) {
            val item = active[i]
            item.x.floatValue = widthPx - (nowMs - item.startMs).toFloat() * item.speedPxPerMs
            if (item.x.floatValue + item.widthPx < 0f) active.removeAt(i)
            i--
        }
    }

    /** ③ 固定弹幕到期回收（它们不动，只看时间） */
    private fun recycleFixed(nowMs: Long) {
        var i = fixedTop.size - 1
        while (i >= 0) {
            val item = fixedTop[i]
            if (item.expiresAtMs == 0L) item.expiresAtMs = nowMs + FIXED_DURATION_MS
            if (nowMs >= item.expiresAtMs) fixedTop.removeAt(i)
            i--
        }
        i = fixedBottom.size - 1
        while (i >= 0) {
            val item = fixedBottom[i]
            if (item.expiresAtMs == 0L) item.expiresAtMs = nowMs + FIXED_DURATION_MS
            if (nowMs >= item.expiresAtMs) fixedBottom.removeAt(i)
            i--
        }
    }

    /** 挑"空得最久"的车道，让弹幕尽量分散（避免总是挤在最上面几条） */
    private fun pickFreeLane(nowMs: Long): Int? {
        var best = -1
        var bestFreeAt = Long.MAX_VALUE
        for (lane in 0 until laneCount) {
            val freeAt = laneFreeAtMs[lane]
            if (freeAt <= nowMs && freeAt < bestFreeAt) {
                best = lane
                bestFreeAt = freeAt
            }
        }
        return if (best >= 0) best else null
    }

    private fun createItem(head: PendingDanmaku, lane: Int, nowMs: Long): LiveDanmakuItem {
        val width = estimateTextWidthPx(head.text, fontSizePx)
        // 固定"穿越时长"而不是固定速度：长弹幕快一点、短弹幕慢一点，和 DanmakuFlameMaster 的固定 duration 一致
        val speed = (width + widthPx) / travelDurationMs
        return LiveDanmakuItem(
            key = nextKey++,
            text = head.text,
            color = head.color,
            startMs = nowMs,
            widthPx = width,
            speedPxPerMs = speed,
            // 车道落在"显示区"内：areaHeightFraction<1 时不会排到区域外
            topPx = (lane * laneHeightPx).coerceAtMost((areaHeightPx - laneHeightPx).coerceAtLeast(0f)),
        )
    }
}

/** 屏上的一条弹幕（不可变的除 [x] / [expiresAtMs] 外） */
private class LiveDanmakuItem(
    val key: Long,
    val text: String,
    val color: Color,
    val startMs: Long,
    val widthPx: Float,
    val speedPxPerMs: Float,
    /** 车道顶部 y（px）；固定弹幕不用它 */
    val topPx: Float,
) {
    /**
     * 当前左边缘 x（px）。
     * ★放在 State 里、并只在 `Modifier.absoluteOffset { }` 的 lambda 里读：
     *   位置每帧都变，若直接参与重组会白烧 CPU；走布局阶段则只重排不重组。
     */
    val x = mutableFloatStateOf(0f)

    /** 固定弹幕（mode 4/5）的到期时刻；滚动弹幕不用。
     *  ★这里刻意**不是** Compose 状态：它只被帧循环读写，组合里从不读它 ——
     *   用普通可变字段就够，省掉一次无意义的快照写入。 */
    var expiresAtMs = 0L
}

/** 待入场的弹幕（还没分到车道） */
private class PendingDanmaku(
    val text: String,
    val color: Color,
) {
    /** 入队时间；在帧循环里用帧时钟打戳（0 = 还没打戳） */
    var enqueuedAtMs = 0L
}

/**
 * 组装左上角的状态条。
 *
 * 三块信息拼在一起：计数（[LiveDanmakuTrace.snapshot]）+ 车道/区域提示 + 连接失败原因
 * （读起来最要紧的那条放最后一行）。
 * ★这里刻意"轮询"而不是让客户端 push：浮层只需要 2Hz 的粗粒度展示，
 *   而客户端连 `connectionState` 都可能是 Idle（比如宿主没 start）——轮询才能把这种情况也显示出来。
 * ★本轮删掉了第四块"屏蔽词过滤=N"：直播不再做关键词过滤，这条统计恒为 0，留着只会误导。
 *
 * @param layoutHint 显示区域 / 车道数 / 是否列表模式（vc168 新增）。
 *   为什么值得单独占一行：用户报的"我选了全屏，它还是不是全屏"**无法从现有计数条判断** ——
 *   `收=30 上屏=20 在屏=20` 在"只有 8 条车道"和"铺满 39 条车道"两种情况下长得一模一样。
 *   把"区域=%/车道=N/上限=显示区域"打出来，这条链路是否生效当场可验（不用连 adb、不用问用户）。
 */
private fun buildStatusText(
    debugCounters: Boolean,
    statusHint: Boolean,
    layoutHint: String = "",
): String {
    val sb = StringBuilder(200)
    if (debugCounters) sb.append(LiveDanmakuTrace.snapshot())
    if (layoutHint.isNotEmpty()) {
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(layoutHint)
    }
    val state = LiveDanmakuTrace.connectionText
    if (statusHint && state.startsWith("Failed")) {
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append("弹幕连接失败：")
        sb.append(LiveDanmakuTrace.lastConnectError ?: state)
        sb.append("\n（在播放页把\"弹幕\"开关关一次再打开即可重连）")
    }
    return sb.toString()
}

/**
 * 估算文本宽度（px）。
 *
 * ★为什么不用 `TextMeasurer` 精确测量：这个值只用来决定"车道什么时候能让下一条进来"，
 * 所以刻意**宁大勿小**（[WIDTH_SAFETY_FACTOR]）—— 估大了只是车道利用率略低，估小了就会叠字。
 * 真正绘制时的宽度由 Compose 按字体自己测量，与这里无关。
 */
private fun estimateTextWidthPx(text: String, fontSizePx: Float): Float {
    var units = 0f
    for (ch in text) {
        units += when {
            ch.isHighSurrogate() -> 1f // emoji 之类的代理对：按一个全角算
            ch.isLowSurrogate() -> 0f // 低代理不重复计
            isWideChar(ch) -> 1f
            else -> 0.55f // 半角（数字/字母/空格）在 CJK 字体里大致 0.5~0.6 em
        }
    }
    return units * fontSizePx * WIDTH_SAFETY_FACTOR
}

/** CJK/全角/假名/韩文基本都在 0x2E80 之后 */
private fun isWideChar(ch: Char): Boolean = ch.code >= 0x2E80

/**
 * 车道高（**下限**）：必须大于字号行高，否则相邻车道会叠字。
 * 实际车道高 = max(本值, 字号 × [LANE_LINE_HEIGHT_FACTOR])，见 `LiveDanmakuOverlay` 里的推导。
 */
private val LANE_HEIGHT = 22.dp

/** 车道高相对字号的最小倍数（≈ Compose 默认行高）：字号被调大时车道自动跟着变高，不叠字 */
private const val LANE_LINE_HEIGHT_FACTOR = 1.4f

// ★vc168 删掉了 `DEFAULT_MAX_LANES = 8`（原来在这里）。
//   它曾是 `if (settings.maxLanes > 0) settings.maxLanes else 8` 里的兜底值，
//   而点播「滚动弹幕最大行数」的默认值就是 **0（无限制）**（DanmakuDisplaySettingContent.kt:121）
//   —— 于是"显示区域=全屏"时可用高度是整屏，真正能用的却只有 8 条车道：
//   8 × 22dp ≈ 176dp，在 1080×2400 竖屏上只占 **19%**，横屏 2400×1080 上占 **43%**，
//   下面那块永远是空的。这正是用户报的"我选全屏，进去热门直播间却还不是全屏"。
//   现在车道数 = 显示区域高度 ÷ 车道高（见 `LiveDanmakuOverlay` 里 laneCount 的推导），
//   全屏就是整屏、1/4 屏就是 1/4 屏，所见即所得。
//   ★本轮又往前一步：连那条"点播滚动弹幕最大行数"的上限也不再读（`maxLanes` 字段已从
//     `LiveDanmakuSettings` 删除）—— 车道的唯一来源就是直播自己的「弹幕显示区域」。
//   （历史取证：vc168 出厂包 `bilimiao-vc168-bigfix.apk` 的 classes4.dex 里
//     `LiveDanmakuOverlayKt` 仍有 `.field private static final DEFAULT_MAX_LANES:I = 0x8`
//     与 `if-lez … const/16 v20, 0x8` 分支 —— 用户手上那版确实带这条上限。）

/** 同一车道前后两条弹幕之间的最小横向间隙 */
private val LANE_GAP = 16.dp

/** 显示区高度占比的下限：再怎么调也不能小到一条车道都放不下 */
private const val MIN_AREA_FRACTION = 0.1f

// 注：弹幕字号基准（15sp）与"穿越时长"基准（7000ms）的**唯一真值**在
//     `LiveDanmakuSettings.BASE_FONT_SIZE_SP` / `LiveDanmakuSettings.BASE_TRAVEL_DURATION_MS`，
//     浮层不再各自留一份（避免两处真值漂移）；这里是派生值，不参与"设置从哪来"的判断。

/** 顶部/底部固定弹幕停留多久 */
private const val FIXED_DURATION_MS = 5_000L

/** 每帧最多入场几条（一帧进来一大批会很突兀） */
private const val MAX_SPAWN_PER_FRAME = 2

/** 入场最小间隔 ≈ 22 条/秒：UI 侧渲染上限（方案 §6.5 要求"UI 侧每秒最多渲染 N 条"） */
private const val MIN_SPAWN_INTERVAL_MS = 45L

/** 排队超过这么久就丢：直播弹幕过期没价值 */
private const val PENDING_MAX_AGE_MS = 2500L

/** 待入场队列上限 */
private const val MAX_PENDING = 64

/** 每条车道同时最多几条在飞（入场 + 途中 + 将离开） */
private const val MAX_ITEMS_PER_LANE = 3

/** 顶部/底部各最多同时显示几条固定弹幕 */
private const val MAX_FIXED = 3

/** 宽度估算的安全系数：宁大勿小 */
private const val WIDTH_SAFETY_FACTOR = 1.15f

/** 状态条刷新间隔（2Hz 足够，且不会和帧循环抢资源） */
private const val STATUS_REFRESH_MS = 500L

/** 状态条内边距 */
private val STATUS_PADDING = 6.dp

/** 状态条底色：半透明黑，保证在任何画面上都读得清 */
private val STATUS_BG = Color(0x99000000)

/** 状态条文字色：淡黄，和弹幕白字区分开 */
private val STATUS_COLOR = Color(0xFFFFE082)

// ═══════════════════════════════════════════════════════════════════════════
// 竖屏「弹幕列表」（vc168 任务 2 → 改成**常驻停靠** → 本轮修**转屏恢复与贴边**）
//
// 用户原话（第一次）："我们在竖屏的情况下，弹幕是否可以做成……一个冒号，他说的什么。
//           这个区域可以上下滑动偷看，就是最新的弹幕在最底下，旧的在上面，可以往上滑动看。"
// 用户原话（停靠那一轮，5 张截图为证）："还有弹幕列表把那个按钮给挡住了……要么就是**固定在那个视频下面**，
//           直接不要有一个说什么按钮才能让它显示去……就把那个列表**一直显示在它的下面**，
//           如果说它是竖屏的情况下。还有那个弹幕列表，就**一直显示到底部**就行了。"
// 用户原话（本轮）："怎么我旋转全屏，再转回竖屏，我竖屏状态下专属的弹幕（用户名+内容那种列表）怎么不见了？"
//           "中间有一大块黑屏空呢……竖屏的情况下把它给占满？"
//
// 形态变化三条：
// 1. **常驻**：右下角那个「弹幕列表」胶囊删掉了（`ensureListChip` 整段没了），竖屏进来就是列表；
// 2. **停靠不压底栏**：面板矩形 = "视频画面底边 → 底栏顶边"，由宿主**实测**得出，
//    不再用写死的 dp 留白（那正是"盖住『发弹幕』"的根因，见本文件参数区与宿主的类注释）；
// 3. ★**本轮（转屏修复）**：两个用户可见的问题（转回竖屏列表不回来 / 视频与列表之间的黑缝）
//    根因**都不在这个文件里**，而在"宿主把播放页那个槽 View 的一次量测结果当成了真值"
//    （槽只是"视频带底边"的镜像，会过期）—— 修法、证据链、兜底优先级全写在
//    `LiveDanmakuOverlayHost` 的类注释"★转屏后列表回不来 / 视频与列表之间那条黑缝"一节。
//    本文件**据此不改渲染逻辑**，只把契约写清楚（下一段两条），因为：
//    · 面板的矩形（顶边贴视频、底边让开底栏、转屏后重量）**全部由宿主算**，面板只负责"填满这块矩形"；
//    · 列表内容是"最新贴下 + 上滑看历史 + 回到底部"，与矩形怎么来得无关。
//
// ★面板的两条契约（宿主与播放页都按这个来）：
// 1. **填满**：面板 View 多大，列表就画多大（`LiveDanmakuChatPanel` 用 `Modifier.fillMaxSize()` 铺满，
//    顶部只有一条 1dp 分隔线）—— 所以"列表区高度变了"（视频带下移后列表变矮）在内容侧不需要任何改动；
// 2. **贴边**：面板顶边必须 = 视频**画面**底边（不是"半屏"、不是"槽的旧值"）。差一点点就是用户眼里的
//    "中间一大块黑屏空"。宿主侧的实现与优先级见 [LiveDanmakuOverlayHost.computeDockedRect]。
//
// 列表内容侧（`用户名：内容`、最新贴下、上滑看历史、↓ 回到底部、空态、200 条硬上限）**一行没改**。
//
// 竞品对照（PiliPlus，`/tmp/PiliPlus`）：
// - `lib/pages/live_room/widgets/chat_panel.dart:96` 就是 `'${item.name}: '`（"用户名：内容"）；
// - 同一文件 `:218-231` 在"用户上滑过"时给一个「回到底部」按钮；
// - `lib/pages/live_room/controller.dart:485-499` 用滚动方向维护 `disableAutoScroll`
//   （上滑=暂停自动滚动；滑回距底 100px 内=恢复）；
// - `controller.dart:52-53` 上限 500 条 + 50 条松弛（`_kTrimCount = _kMaxChatCount + 50`），
//   本文件取 200 条的**硬上限**（理由见 [LiveDanmakuChatLog] 的第 2 条取舍）。
// - ★竞品在竖屏就是"视频在上、聊天列表在下的固定区域"（`view.dart:735-742` 的
//   `Expanded(child: _buildChatWidget())`）—— 把上一版的"贴屏幕底部的浮层面板"改成
//   与竞品同构的**停靠区域**，正是回到用户一开始要的那个版式。
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 竖屏弹幕列表里的一行 = "用户名：内容"。
 *
 * [key] 是**单调递增**的稳定 key，LazyColumn 靠它做"插入新行时保持用户阅读位置"的锚定
 * （`LazyListScrollPosition` 会在列表头部插入时把当前可见项锚住，不跳）。
 */
class LiveDanmakuChatLine(
    val key: Long,
    /**
     * 昵称；解析不到时是 "观众"（[LiveDanmakuChatLog.add] 兜底）。
     * ★自己发的那条也走同一个字段：本轮起取真实昵称（`LiveSelfNickname`，服务端回声 /
     * 历史弹幕 / 本机 `user.data` / `nav` 四条来源），**只有全拿不到时才退回"我"** ——
     * 用户明确要求"跟列表里其它条目一样显示昵称全名，不是 uid"。
     */
    val uname: String,
    val text: String,
    /** 已补 alpha 的内容色（**不乘**弹幕不透明度，见 [LiveDanmakuChatLog.add]） */
    val color: Color,
)

/**
 * 竖屏弹幕列表的数据缓冲（宿主建一个，浮层往里塞，面板读它画）。
 *
 * 设计取舍（都是"为什么"）：
 * 1. **新的放 index 0**：面板用 `LazyColumn(reverseLayout = true)` 画，
 *    index 0 天然贴在**屏幕最底**、index 往上都是旧弹幕 —— 用户要的"最新在最底下"不用额外布局代码。
 * 2. **上限 [capacity] = 200 条的硬上限**：热门房每秒几十条，无上限就是内存 + 重组双双失控。
 *    ★为什么不像竞品那样"攒 50 条再裁一次"（PiliPlus `controller.dart:52-53` 的 500/550）：
 *    这里是**硬上限**，好处是"列表到底占多少内存"是一句能验证的话（永远 ≤200 行）。
 *    ★本轮（贴底节流）之后裁剪的代价也降下来了：裁剪跟着**提交**走，一次 `removeRange` 收尾
 *    （上一版是每条消息各来一次 `removeAt(lastIndex)`）—— 200 个引用的一次搬移约 1.6KB，
 *    按 120ms 一拍算是 ~13KB/s，比"每条都裁"低一个数量级。
 * 3. **只在主线程读写**（宿主的组合、面板的滑动回调都在主线程），所以不需要锁、不用 @Volatile。
 *    对比点播 `DanmakuTextFilter` 当年"主线程 clear + 缓存线程遍历"崩过进程那一类问题。
 * 4. 弹幕**不透明度不参与**列表渲染：列表是"读字"，用户把弹幕透明度调到 30% 是为了不挡画面，
 *    不是为了让自己读不清聊天 —— 所以内容色只补 alpha=FF，不乘 settings.opacity。
 */
class LiveDanmakuChatLog(private val capacity: Int = CHAT_MAX_LINES) {

    /** 最新的在 index 0（面板 reverseLayout → 它在屏幕最底） */
    val lines = mutableStateListOf<LiveDanmakuChatLine>()

    /**
     * ★本轮（"贴底节流"）新增：**待提交缓冲**（旧 → 新；只有在 [flushPending] 里才进 [lines]）。
     *
     * ## 为什么要有它
     * 改动前 `add()` 每来一条就 `lines.add(0, …)` —— 一次**结构性写**，
     * 于是**每条弹幕**都会：① 让 `items(items = chat.lines)` 的内容 lambda 失效 → 面板重组；
     * ② 让 LazyList 重算 key→index 映射并**重新量测可见的十来行**；
     * ③ 顺带把新行（在 `reverseLayout` 里插到 index 0 = 屏幕**底边之外**）的"淡入"在**屏幕外**演完。
     * 第 ③ 条正是用户观感的一部分：淡入演在看不见的地方，等那一滚把它带进视野时，
     * 看到的是"啪"地出现（`CHAT_ITEM_FADE_IN_MS` 白设了）。
     *
     * 现在把"提交"降成**一拍一次**（[CHAT_COMMIT_TICK_MS]），并且在**同一拍**里紧接着滚一下：
     * 新行落地 → 下一帧 LazyList 量测 → 那一滚把新行带进视野，**淡入和入场同时发生**。
     *
     * ## 竞品对照（`/tmp/PiliPlus`，只读）
     * - `controller.dart:566-573`：粘底时 `messages.add(msg)`（每来一条通知一次），
     *   不粘底时 `messages.addOnly(msg)` ——**只写 rawValue、不通知**，用户翻历史时列表一个新帧都不重建；
     * - `controller.dart:380-396`：滚动侧 `EasyThrottle.throttle('liveDm', 500ms)` +
     *   `addPostFrameCallback(_scrollToBottom)` ——**每 500ms 最多一次**、且**在下一帧**才滚。
     * 竞品的"顺"来自这两条**节流**（通知节流 + 滚动节流），不是来自条目动画 ——
     * 本类与面板这一版取的就是同一套语义（见 [flushPending] 与 `LiveDanmakuChatPanel` 的贴底循环）。
     *
     * ## 不变式（一个字都没改）
     * - 最新仍在 **index 0**：提交时 `lines.addAll(0, pending.asReversed())`（`pending` 是旧→新）；
     * - [lines] 仍 **≤ [capacity]**：提交时一次 `removeRange` 裁掉最旧的那一段；
     * - **上限兜底**：缓冲涨到 [CHAT_PENDING_MAX] 条时 [add] 自己立刻提交 ——
     *   面板不在屏上（横屏 / PiP）或用户正在翻历史时没有"一拍"在跑，不兜底就是无界内存；
     *   这条同时保住了"横屏期间照样收、转回竖屏一条不少"的老语义；
     * - [stickToBottom] / [pendingScrollToBottom] 的语义原样保留，只是从"每条判一次"变成"每拍判一次"。
     */
    private val pending = ArrayList<LiveDanmakuChatLine>(CHAT_PENDING_MAX)

    /**
     * ★本轮新增：面板那一拍的**唤醒信号**（`CONFLATED`：连来 100 条也只值一次唤醒）。
     *
     * 它替掉了上一版的 [autoScrollTicket]（单调票据 + `snapshotFlow`）。两者**语义相同**
     * ——"同拍 N 条 → 只醒一次、只滚一次、不排队"——但通道少一次状态写入与一次快照发射，
     * 而且**触发点**分得清：
     * - 唤醒在 [add]（有新消息了，去看看要不要提交/跟一跟）；
     * - 要不要滚、滚多远，由面板在**提交之后的下一帧**按真实位置决定（见 `LiveDanmakuChatPanel`）。
     * 上一版把这两件事揉在一个票据里，才会出现"插入那一刻打的标记排到执行时已经过期"
     * 那一类要靠 `pendingScrollToBottom` 去圆的问题（见 [pendingScrollToBottom] 的注释）。
     */
    val wakeUp: Channel<Unit> = Channel(Channel.CONFLATED)

    /** 列表（含还没提交的缓冲）是不是空的：空态提示用 —— 只看 [lines] 会把"刚收到还没提交"显示成空列表 */
    val isEmpty: Boolean get() = lines.isEmpty() && pending.isEmpty()

    /** 缓冲里还有多少条没上屏（面板“一帧一条”的循环用；PiliPlus 是每条消息直接 add，这里等价节流） */
    val pendingCount: Int get() = pending.size

    /**
     * 用户当前是否**贴在底部**（true = 新弹幕来了要自动滚到底）。
     *
     * 由 [LiveDanmakuChatPanel] 用 `LazyListState` 维护（上滑 → false，滑回底 → true），
     * 由 [add]/[flushPending] 读它决定"这一拍要不要提交、要不要贴底"。默认 true：一打开就跟最新。
     */
    var stickToBottom by mutableStateOf(true)
        private set

    /**
     * "提交那一刻用户贴着底"的标记（[flushPending] 里置，面板那一拍贴底结束前一直亮着）。
     *
     * 面板**不靠它决定滚不滚**（那是按提交之后的**真实位置**判的，见 `LiveDanmakuChatPanel` 的 ②），
     * 它有两个别的用处，两个都是"别把自己造成的位移读成用户操作"：
     * 1. 「↓ 回到底部」按钮的判据里有它（`!pendingScrollToBottom`）—— 新行插到 index 0 的那一两帧里
     *    LazyList 的 key 锚定会把可见项挪到 index 1，不挡这一下按钮会闪；
     * 2. 位置判定那个 effect（③）在它亮着、且**没有任何滚动会话在跑**时闭嘴 ——
     *    同上，那段时间里的 `index=1` 是**我们提交数据**造成的，不是用户上滑。
     *
     * ★为什么"贴着底"这个判断必须在**提交之前**做：提交之后新行已经进了 index 0，
     *   key 锚定会把可见项往后挪一格，此刻再判断就会把"其实一直贴着底"误判成"用户上滑过"，
     *   自动贴底就永远不生效。所以判断留在提交点（[flushPending] 里的 `if (stickToBottom)`）。
     */
    var pendingScrollToBottom by mutableStateOf(false)
        private set

    private var nextKey = 0L

    /** 面板观察到的用户位置（由面板调用，主线程） */
    fun onStickinessChanged(stick: Boolean) {
        stickToBottom = stick
    }

    /**
     * 面板**上屏**（首次、或从横屏/PiP 切回竖屏）：贴回底部并清掉残留标记
     * （重新上屏时用户没有"阅读位置"可言，停在很久以前 = 看起来像"新弹幕没进来"）。
     * ★本轮补一句 [flushPending]：上屏时先把缓冲里攒下的落地，否则"重新上屏"看到的还是上一拍之前的列表。
     */
    fun onPanelOpened() {
        stickToBottom = true
        pendingScrollToBottom = false
        flushPending()
    }

    /** 面板已经滚到底部：把标记消费掉 */
    fun consumeAutoScroll() {
        pendingScrollToBottom = false
    }

    fun add(msg: LiveMessage.Danmaku) {
        // 昵称兜底：解析不到昵称时显示"观众"，别让列表里出现"：内容"这种缺一半的行
        val name = msg.uname.takeIf { it.isNotBlank() } ?: "观众"
        // 内容色：0xRRGGBB 补 alpha；**不乘** settings.opacity（见类注释第 4 条）
        val color = Color(0xFF000000.toInt() or (msg.color and 0xFFFFFF))
        // ★本轮：只进缓冲，**不**直接改 lines（见 [pending] 的类注释：提交降成一拍一次）
        pending.add(LiveDanmakuChatLine(nextKey++, name, msg.text, color))
        // ★[减法/降 CPU 2026-09-26] 上限兜底不再 addAll 整批（那会大块刷、还一次性重排 N 行）。
        //   高弹幕率时宁可丢最旧的一条 buffer，也不让面板一拍吞一批；内存仍有硬上限。
        if (pending.size >= CHAT_PENDING_MAX) {
            pending.removeAt(0)
        }
        // 叫醒面板那一拍：合并交给 CONFLATED 通道，连来 100 条也只值一次唤醒（不排队）
        wakeUp.trySend(Unit)
    }

    /**
     * ★本轮新增：把 [pending] **一次**提交进 [lines]（旧→新 ⇒ 倒序插到 index 0）；返回提交条数。
     *
     * 一次提交只做**两处**列表改动（上一版每条消息各做两处）：
     * 1. `addAll(0, …)` —— 结构性写**一次**（N 条同时落地只通知一次）；
     * 2. `removeRange(capacity, size)` —— 裁掉超出上限的那一段**一次**（上一版是 `while` 循环逐条 `removeAt`）。
     * 于是"同帧到 N 条"在列表侧的代价是 **O(1) 次通知**，而不是 O(N) 次。
     *
     * 顺带把两个老标记在这一拍里置一次（语义与上一版完全一致，只是判定的时刻从"每条"挪到"每拍"）：
     * - [pendingScrollToBottom]：提交那一刻**贴着底**才置 —— 它同时是「↓ 回到底部」按钮的闪避信号
     *   （新行插到 index 0 的那一两帧里，锚定会把可见项挪到 index 1，别让按钮闪一下）；
     * - 提交之后由面板在**下一帧**按真实位置决定"滚不滚、滚多远"（见 `LiveDanmakuChatPanel` 的贴底循环）。
     *
     * ★用户正在翻历史（[stickToBottom] = false）时**不置** [pendingScrollToBottom]：
     * 这一拍只是把数据落地（列表内容更新、阅读位置由 key 锚定保住），一个像素都不滚 ——
     * 与竞品 `controller.dart:573` 的 `messages.addOnly`（只写不通知、不打扰阅读）同源。
     */
    fun flushPending(): Int {
        if (pending.isEmpty()) return 0
        val n = pending.size
        lines.addAll(0, pending.asReversed())
        pending.clear()
        // 丢最旧的 = 列表尾部（index 大的一端）；硬上限 200 条这条不变式一个字没改
        if (lines.size > capacity) lines.removeRange(capacity, lines.size)
        if (stickToBottom) pendingScrollToBottom = true
        return n
    }

    /**
     * ★[2026-09-26 一条一条刷] 只把**最新的一条** [pending] 提交进 [lines]（返回 1/0）。
     *
     * 为什么不用 [flushPending] 的整批 addAll：热门房里一大块 N 条同时插到 index 0，
     * 用户看到的是"一下子大块大块地刷"。竞品（PiliPlus `controller.dart:567-568`）是
     * **每条消息 `messages.add(msg)`**，所以这里也改成"缓冲里一次只上屏一条"，
     * 由面板那一拍每帧消费一条；旧行的向上让位继续交给 `Modifier.animateItem` 的 placement tween。
     *
     * 排序与不变式与 [flushPending] 一致：取最新（尾部）放 index 0，超 [capacity] 从尾部裁最旧。
     */
    fun flushOne(): Int {
        if (pending.isEmpty()) return 0
        val line = pending.removeAt(pending.size - 1)
        lines.add(0, line)
        if (lines.size > capacity) {
            lines.removeAt(lines.size - 1)
        }
        if (stickToBottom) pendingScrollToBottom = true
        return 1
    }

    /** 换房间/重连时清空（宿主目前没有调用点，留给后续"切房间复用宿主"的用法） */
    fun clear() {
        lines.clear()
        nextKey = 0L
        pendingScrollToBottom = false
    }

    /**
     * ★**进房铺底**：把"最近的历史弹幕"一次性接进列表（第四个需求，见 `LiveDanmakuHistoryAPI`）。
     *
     * ## 为什么"接在尾部"而不是"插到 index 0"
     * 列表的不变式是 **index 0 = 最新 = 屏幕最底**（面板 `reverseLayout = true`），
     * 而历史弹幕**一定比此刻列表里的每一条都旧**（它是进房那一刻之前的消息）。
     * 所以它们该去的是**尾端（= 显示在最上面）**，而且内部要按"新 → 旧"排：
     * 传进来的 [items] 是**时间升序（旧 → 新）**（`LiveDanmakuClient.fetchHistory` 的契约），
     * 这里**倒序追加**，于是从上往下读正好是"先发生的在上、后发生的在下"，与实时弹幕无缝接上。
     *
     * ## 不会打扰用户当前的阅读位置
     * 只往"更旧"的一端加，**不动** [stickToBottom] / [pendingScrollToBottom]：
     * 用户此刻贴在底部看最新的，历史接在另一头，屏幕上的内容一个像素都不会跳。
     *
     * ## 去重与上限
     * - 与自己（`uname` + 文本）已存在或本批重复的条目直接跳过 —— 历史与实时链路的重叠期
     *   （刚连上 WS 那几秒）不会出现"同一句话两行"；
     * - 追加完仍然按 [capacity] 裁掉**最旧的**（尾部）：列表"永远 ≤ 200 行"这条不变式没变，
     *   所以"进房铺 20 条 + 实时刷了 200 条"之后，历史会被自然挤掉 —— 这是刻意的取舍：
     *   列表要的是"最近在聊什么"，不是"完整存档"。
     */
    fun addHistory(items: List<LiveMessage.Danmaku>) {
        if (items.isEmpty()) return
        // ★本轮：先把缓冲里攒下的实时弹幕落地 —— 下面的去重基准是 [lines]，
        //   不先落地就会与"还没提交的同一条"撞成两行（正是这段去重要防的那种重复）。
        flushPending()
        val seen = HashSet<String>((lines.size + items.size) * 2)
        for (line in lines) seen.add(line.uname + DEDUP_SEPARATOR + line.text)
        val appended = ArrayList<LiveDanmakuChatLine>(items.size)
        // 倒序（新 → 旧）追加到尾部：index 越大越旧，最后一个正好是最旧的那条
        for (i in items.indices.reversed()) {
            val msg = items[i]
            val name = msg.uname.takeIf { it.isNotBlank() } ?: "观众"
            val text = msg.text
            if (text.isBlank()) continue
            if (!seen.add(name + DEDUP_SEPARATOR + text)) continue
            val color = Color(0xFF000000.toInt() or (msg.color and 0xFFFFFF))
            appended.add(LiveDanmakuChatLine(nextKey++, name, text, color))
        }
        if (appended.isEmpty()) return
        lines.addAll(appended)
        // 一次 removeRange 而不是 while-removeAt（同一次铺底超上限时只改一次列表）
        if (lines.size > capacity) lines.removeRange(capacity, lines.size)
    }
}

/**
 * 竖屏列表的"自动贴底"执行者（★本轮新增；一个面板一个，只在主线程用）。
 *
 * 三件事分别对应一条要求，缺一条都会出问题：
 * 1. **平滑**：用 [LazyListState.animateScrollToItem]（LazyList 自带的平滑滚动）替掉原来的
 *    `scrollToItem(0)` 瞬时跳 —— 用户要的"过渡感"主要就是这一下：整列**滑**上去一格，
 *    而不是"啪"地换一屏。时长/曲线由 LazyList 按距离自己定（它不暴露 spec）。
 *    ★本轮补充（"这一档到底是什么"）：Compose 1.12.1 的 `animateScrollToItem` 不是
 *      "固定时长的 tween"，而是 `LazyLayoutScrollScopeKt` 里**默认 spring
 *      （`dampingRatio=NoBouncy`、`stiffness=Spring.StiffnessMedium=1500`）+ 每帧重算目标**
 *      的自适应动画（证据与行号见报告 §2）：一轮的收敛时间只随距离**对数**增长
 *      （1 行 293ms、20 行 376ms，报告 §3 表 A）—— 也就是说它**跟得上**热门房，
 *      但"每条消息各起一轮"必然把列表推成永远不停的状态（报告 §3 表 C 的占空比）。
 *      所以本轮的改法不是换动画、也不是自造"落后 N 行就跳"的阈值（那会在热门房稳态里
 *      把连续滑动切成一跳一跳），而是**把提交与起动画都收到一条循环里、给一个最小间隔**
 *      （见 `LiveDanmakuChatPanel` 的 ② 与 [CHAT_FOLLOW_TICK_MS]）。
 *      ★为什么不自己写 `animateScrollBy` 精确控距：那需要"当前离底部多少**像素**"，
 *      而 LazyList 不暴露滚动余量（`layoutInfo` 只有可见项），屏幕外的行高拿不到 ——
 *      靠估算会在"多行/长弹幕"上滚错位置。**没在真机上验证过的几何计算不进这个文件**。
 * 2. **不误判**：[animating] 给"贴底/上滑"判定当闸门。动画期间列表必然经过
 *    `index=1、offset>0` 的中间位置，而那是**我们自己**造成的位移；
 *    裸判定会把它读成"用户上滑了" → `stickToBottom` 被清掉（之后的新弹幕不再自动贴底）
 *    并且「↓ 回到底部」按钮每滚一次闪一下。收尾判定由调用方在动画结束后显式补一次。
 * 3. **不拽人**：动画跑着的时候用户上手拖（他的 `MutatePriority` 比我们高），
 *    `animateScrollToItem` 会抛 `CancellationException`。这里把它**认成"让位"**，
 *    只在**本协程自己也被取消**（转屏 / 关弹幕 / 离开组合）时才继续往上抛。
 *    ★不这么分辨的话，用户第一次上手拖就会把收集循环整条带走 —— 之后再也不自动贴底。
 */
private class LiveChatBottomScroller(private val listState: LazyListState) {

    /** 自动贴底动画进行中：位置判定要闭嘴（见类注释第 2 条） */
    var animating = false
        private set

    /**
     * 平滑滚到最新（`reverseLayout` 下 index 0 = 屏幕最底）。
     *
     * 结束后的**贴底判定**由调用方自己按真实位置补一次（不在这里做：这个类不该知道
     * "贴底"的判定规则/像素容差）。所以它不需要返回"跑完没跑完"——
     * 无论哪种收场，调用方都是同一句"按真实位置收尾"。
     */
    suspend fun animateToBottom() {
        animating = true
        try {
            listState.animateScrollToItem(0)
        } catch (e: CancellationException) {
            // 面板自己要走了（visible 翻 false / 组合被拆）：照旧把取消抛上去，别吞；
            // 被用户上手拖拽抢占的另一种情况则**让位**（什么都不做，位置判定继续归用户）
            if (!currentCoroutineContext().isActive) throw e
        } finally {
            animating = false
        }
    }

    /**
     * ★[2026-09-26 静默跟底] 新弹幕贴底时用：**请求下一帧量测**直接把 index 0 放在贴底位置，
     * 不启动 [animateScrollToItem]。
     *
     * 为什么：提交前本来就贴着底时，LazyList 的 key 锚定会先把可见项留住（index 变 1），
     * 原来紧接着的一次 [animateScrollToItem] 就成了“反向补偿”——用户看到的是列表先被留住/
     * 往上挤了一下，又被动画拉回去。改成在量测前请求 index 0 贴底后，新行直接落在屏幕底，
     * 旧行的向上让位交给 [Modifier.animateItem] 的 placement 动画（那才是“只往上”的那一下）。
     */
    fun requestBottom() {
        listState.requestScrollToItem(0)
    }

    /**
     * 瞬时兜底贴底（仍不启动滚动动画）：只在 [requestBottom] 因极端时序没落到量测、
     * 且用户没有正在拖动列表时用一次。
     */
    suspend fun snapToBottom() {
        animating = true
        try {
            listState.scrollToItem(0)
        } finally {
            animating = false
        }
    }
}

/**
 * 竖屏弹幕列表面板（"用户名：内容"，最新贴屏幕底，可上滑看历史）。
 *
 * ★**这个面板必须由宿主放进一个"能吃到触摸"的容器里**（见 `LiveDanmakuOverlayHost` 的长注释）：
 * 播放页的 `tapCatcher`（全屏手势层）压在弹幕层**上面**，所以放进弹幕层里的 Compose 内容
 * 一个触摸都收不到 —— 面板会被画出来但滑不动。宿主因此把它注入到 activity 内容视图的最上层。
 * 本文件只负责"长什么样、怎么滚"，**不关心它被挂在哪、占多大** ——
 * 矩形（视频画面底边 → 底栏顶边）由宿主按实测几何算好写进 layoutParams。
 *
 * ★vc168"常驻停靠"之后的面板**没有标题栏、没有「收起」按钮**（用户原话："直接不要有一个说什么
 *   按钮才能让它显示去"）：它常驻在视频下方，顶多留一条 1dp 分隔线。上一版那行
 *   「弹幕列表　最新在下 · 上滑看历史　　收起」占的是用户最想看的"最新那几条"的位置，
 *   而"最新在下/上滑看历史"这两条信息在列表本身的行为里已经自解释。
 *
 * ★本轮（转屏修复）补的两条不变式 —— 面板侧**不需要**为它们写代码，写在这里是给改这块的人对账用：
 * 1. **填满**：面板 View 的矩形就是列表的矩形（`modifier` 由宿主传 `Modifier.fillMaxSize()`，
 *    面板内部 `Column` + `weight(1f)` 铺满）→ 视频带下移后列表变矮，内容侧零改动；
 * 2. **贴边**：矩形顶边 = 视频**画面**底边（宿主实测，见 `LiveDanmakuOverlayHost` 的类注释），
 *    面板这一侧唯一能做的就是"别在顶部再让出一块自己的留白"——所以这里除了那条 1dp 分隔线，
 *    顶部**不加任何 padding/margin**（改了就会在用户眼里变成"视频与列表之间一条黑缝"）。
 * ★转回竖屏的恢复路径（本轮修的 bug）**不经过本文件**：宿主把 `visible` 重新置 true →
 *   ① 这个组合重新组内容（`LazyColumn` 重新建，从 index 0 = 最新开始）；
 *   ② 下面的 `LaunchedEffect(visible)` 把阅读位置贴回最新。
 *   所以面板侧没有"只算一次"的状态需要重置 —— 这也是本轮**没有改渲染逻辑**的原因。
 *
 * @param visible 面板**此刻是否真的在屏上**（宿主实测：竖屏 ∩ 弹幕开着 ∩ 视图可见 ∩ 非 PiP ∩ 地方够）。
 *   false 时**直接不组内容**（不订阅 [LiveDanmakuChatLog.lines]）：面板不在屏上时新弹幕不该让这个
 *   组合重组 —— 这就是"不可见不额外工作"的落点。
 *   ★宿主侧对应的是 `listShown`：它由"实测矩形够不够 [CHAT_DOCKED_MIN_HEIGHT]"决定，
 *   而**不再**由"播放页给的槽 View 是否可用"决定（上一版正是后者，才有"转回竖屏列表再也不回来"）。
 *
 * ★本轮（2026-09-26，弹幕列表动画）在**呈现**上加了两样，**判定与布局一个字节没动**：
 * 1. 条目动画：`Modifier.animateItem()`（新弹幕淡入、被挤上去的平滑位移、被 200 上限裁掉的淡出）；
 * 2. 自动贴底从"瞬间跳"改成"节流的平滑滚动"（见下面的 ②）。
 * 面板自己的淡入/淡出**不在这里**（在宿主 `LiveDanmakuOverlayHost.presentPanel`，用 View 的
 * `alpha`/`translationY` 做）—— 因为"隐藏"这个动作是宿主写 `visibility` 的，只有它能决定
 * "什么时候才真的 GONE"；本组合能配合的是：退场期间**别把内容拆掉**（[fadingOut]）。
 *
 * ★再一轮（"还不够丝滑"）：把上面第 2 条从"每条一滚"收紧成**一条循环、一次一件、
 *   带最小间隔**，并让"新弹幕落地"也走同一条循环（提交 = `LiveDanmakuChatLog.flushPending`）：
 * - ② 那一条循环：**提交**攒下的（一次 `addAll`）→ **等一帧**（让重组与量测落地，
 *   竞品同一处用 `addPostFrameCallback`）→ **一次补偿**（已经在底部就一次都不滚；
 *   否则一次平滑动画，两次补偿的起步时刻至少隔 [CHAT_FOLLOW_TICK_MS]）；
 * - ③ 用户滑动：多了一个"锚定重定位"的闸门（见那段注释），修的是"自动贴底偶尔会停一拍"；
 * - 条目位移动画：spring → `tween([CHAT_ITEM_PLACEMENT_MS])`（理由写在常量那里）。
 *   **判定（谁算贴着底）、布局（矩形、200 上限、index 0 = 最新）一个字没动。**
 *
 * @param fadingOut ★本轮新增：面板**正在播退场动画**（宿主的判定已经是"不显示"，
 *   但画面上还要淡出 `PANEL_FADE_OUT_MS` 那一会儿）。true 时即使 [visible] 已经是 false，
 *   也把内容留在组合里 —— 不然 View 淡的是一个**空**面板（内容先没了，等于还是硬切）。
 *   ★这期间的例外是**明说**的：内容会多订阅 [LiveDanmakuChatLog.lines] 一二百毫秒；
 *   淡完宿主把 View 置 GONE 并把本参数置回 false，组合立刻回到"不组内容"（稳态零开销不变）。
 *   触摸不吃（`clickable` 的 `enabled` 只认 [visible]）、effect 也不再跑（都以 [visible] 为门）。
 */
@Composable
fun LiveDanmakuChatPanel(
    chat: LiveDanmakuChatLog,
    visible: Boolean,
    modifier: Modifier = Modifier,
    fadingOut: Boolean = false,
) {
    // ★注意这个早退的判据：只看"判定"是不够的 —— 退场动画期间还要留着内容给它淡（见 fadingOut）
    if (!visible && !fadingOut) return

    val density = LocalDensity.current
    val listState = rememberLazyListState()
    // 自动贴底的执行者（平滑 + 动画期间不误判"用户上滑" + 被打断时让位），见那个类的注释
    val bottomScroller = remember(listState) { LiveChatBottomScroller(listState) }
    // "贴底"判定的像素容差（px）：进 effect 之前算一次，别在每个位置回调里 with(density)
    val stickTolerancePx = with(density) { CHAT_STICK_TOLERANCE.toPx() }

    // ① 上屏（首次 / 从横屏转回竖屏）→ 贴到最新。放在 visible 的 effect 里而不是 initial 里：
    //    面板 View 是 GONE↔VISIBLE 复用的，LazyListState 会记住上次的阅读位置，
    //    "重新上屏还停在很久以前"对用户就是"新弹幕没进来"。
    //    ★这一条**仍然是瞬时**（scrollToItem）：刚上屏时用户没有"阅读位置"可言，
    //      从旧位置滑过去反而像列表在自己乱跑；要的是"一睁眼就在最新"。
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        chat.onPanelOpened()
        listState.scrollToItem(0) // reverseLayout：index 0 = 最新 = 屏幕最底
    }

    // ② ★本轮重写：**一次一件、有最小间隔**的贴底循环（原来是"一张票据 → 一次动画"）。
    //
    //    为什么改：上一版"每来一条（或上一动画刚跑完）就起一次动画"在热门房里是这样跑的 ——
    //    每条消息都各改一次列表（结构性写 + LazyList 重量测），而滚动那头**永远不停**：
    //    `animateScrollToItem` 是一轮约 0.3s 的 spring（见 LiveChatBottomScroller 类注释第 1 条），
    //    上一轮刚落地就紧接着起下一轮，列表从头到尾没有一个静止的瞬间。这一版：
    //
    //    ① **提交**：`flushPending()` 把这一拍攒下的 N 条**一次**addAll（结构性写 1 次，不是 N 次），
    //       于是"列表变了"的失效帧从"每条一次"降到"一拍一次"（报告 §3 表 B 的数）；
    //    ② **等待**：`withFrameNanos {}` 等**一帧** —— 让"提交"引起的重组 + LazyList 量测先跑完。
    //       ★这一步不能省：不等这一帧，下面读到的 `firstVisibleItemIndex` 还是提交**之前**的旧值
    //       （锚定还没重算），会误判成"已经在底部"而**永不滚动**（列表卡在落后一行）。
    //       竞品同一处用的是 `WidgetsBinding.addPostFrameCallback(_scrollToBottom)`（下一帧才滚）：
    //       `PiliPlus/lib/pages/live_room/controller.dart:380-396`，语义完全对应。
    //    ③ **补偿**：一次 `animateScrollToItem(0)`，但**两次补偿的"起步时刻"之间至少隔
    //       [CHAT_FOLLOW_TICK_MS]**（不是"每次滚完再硬等一拍"）——
    //       · 低速房间：动画几十~一两百毫秒就跑完，间隔到的这一下就是"停下来喘口气"，
    //         用户看到的是**一次次完整的滑动**（而不是永远在被推着走）；
    //       · 高速房间：一轮动画本身就有 ~0.3s > 120ms，于是间隔天然满足、动画**首尾相接**，
    //         列表是**连续**在滑的 —— 这不是妥协：内容每秒长高 λ×23dp，
    //         视口必须用同样的平均速度移动，硬塞静止段只会把同样的位移挤成更陡的"爆发"。
    //       · 已经在底部（提交之后位置还是 (0,0)，比如列表还没占满一屏）：这一拍**一次都不滚**。
    //    ★"落后很多"的那条路不在这里另设阈值：Compose 自己就有一条"远距离直接归位"的规则
    //      （`LazyLayoutScrollScopeKt` 的 `BoundDistance = 1500.dp`，见报告 §2.3），
    //      我们自造一个行数阈值只会在热门房里把"平滑"误判成"该跳了"（报告 §3 表 C 就是这个坑）。
    LaunchedEffect(visible, listState) {
        if (!visible) return@LaunchedEffect
        // 上一次"补偿起步"的时刻（nanoTime，单调）：只用于算 [CHAT_FOLLOW_TICK_MS] 这个最小间隔
        var lastCompensationNs = 0L
        // reverseLayout 下"贴底" = 第一条可见项就是 index 0 且没有被滑走（与 ③ 的判据同一条）
        fun isAtBottom() = listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset <= stickTolerancePx
        while (true) {
            chat.wakeUp.receive() // 空房间：挂在这里等信号，一帧都不转（低弹幕率房间不空转）
            // ★用户正在翻历史（上滑过）：这一拍**不提交、不滚动** —— 列表内容一个字节不改，
            //   阅读位置与帧率都不受影响（竞品 `controller.dart:573` 的 `messages.addOnly`
            //   就是这个语义：只写进 raw list、不通知、不重建）。
            //   数据不会丢：缓冲满了 [LiveDanmakuChatLog.add] 会自己提交（有上限兜底）。
            if (!chat.stickToBottom) {
                chat.consumeAutoScroll()
                delay(CHAT_FOLLOW_TICK_MS)
                continue
            }
            // ★[2026-09-26 一条一条刷 + 静默跟底]
            //   竞品是"每条消息 messages.add + 下一帧/500ms 节流贴底"（PiliPlus controller.dart:567-568 / 380-396）。
            //   这里照搬到我们的队列上：只要缓冲还有，就**一帧只上屏一条** flushOne()，
            //   旧行交给 Modifier.animateItem 的 placement tween 向上让位；不再一次 addAll 一批
            //   （那正是"一下子大块大块地刷、太快了"的来源）。
            var didFlush = false
            while (chat.pendingCount > 0 && chat.stickToBottom) {
                chat.flushOne()
                // 在下一帧量测前请求 index 0 贴底：没有反向补偿动画，用户只看到向上挤出 + 新行淡入。
                bottomScroller.requestBottom()
                withFrameNanos { }
                // 极端时序（用户恰在这一帧拖动 / request 没落到量测）：拖动中就完全让位；
                // 否则瞬时兜底一次，也绝不启动反向滚动动画。
                if (!isAtBottom() && !listState.isScrollInProgress) {
                    bottomScroller.snapToBottom()
                }
                chat.onStickinessChanged(isAtBottom())
                chat.consumeAutoScroll()
                didFlush = true
                // ★[降 CPU/降观感速度 2026-09-26] 每条之间至少隔 CHAT_FOLLOW_TICK_MS（200ms）：
                //   一帧一条在 60/120Hz 屏上会变成每秒 60~120 行往上冲，太快也费 CPU；
                //   竞品是每条消息 add + 滚动节流（PiliPlus controller.dart:567-568 / :380-396），
                //   这里等价为“最多 5 条/秒”。睡在消息之间而不是睡在动画之后，低速房也不会卡。
                if (CHAT_FOLLOW_TICK_MS > 0L) delay(CHAT_FOLLOW_TICK_MS)
                // 用户在这一帧/这一睡里上滑/点进了历史：立刻停手，把后面的 pending 留给他回到底部再上。
                if (!chat.stickToBottom) break
            }
            if (didFlush) {
                // 刚上屏过：直接回到 receive —— 有新消息时 CONFLATED 通道已经攒着信号（立刻继续），
                // 没有新消息就挂起，不用再插一个一拍延迟（竞品是每条消息直接 add，不额外睡）。
                continue
            }
            // 缓冲为空：可能是用户从历史里点「↓ 回到底部」（stick=true 但位置还没到底）
            if (!isAtBottom()) {
                withFrameNanos { }
                if (chat.stickToBottom && !isAtBottom()) {
                    val sinceMs = (System.nanoTime() - lastCompensationNs) / 1_000_000L
                    if (sinceMs in 0 until CHAT_FOLLOW_TICK_MS) delay(CHAT_FOLLOW_TICK_MS - sinceMs)
                    lastCompensationNs = System.nanoTime()
                    bottomScroller.animateToBottom()
                    // ★不管"正常跑完"还是"被用户上手拖拽抢占"，都用**真实位置**收尾一次贴底判定：
                    //   正常跑完 → 一定在 (0, 0) → 继续贴底；被抢占 → 用户在哪就是哪（他要看历史就让他看）。
                    chat.onStickinessChanged(isAtBottom())
                    chat.consumeAutoScroll()
                    continue
                }
            }
            // 没滚（在看历史 / 已经贴底 / 这一拍没有新东西）：把标记收掉，睡一拍再醒
            chat.consumeAutoScroll()
            delay(CHAT_FOLLOW_TICK_MS)
        }
    }

    // ③ 用户滑动 → 维护"贴底/上滑"状态（上滑 = 别打扰我；滑回底部 = 恢复自动滚动）
    LaunchedEffect(visible, listState) {
        if (!visible) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                // ★自动贴底动画期间闭嘴：那段时间列表必然经过 `index=1、offset>0` 的中间位置，
                //   而那是**我们自己**造成的位移。当成"用户上滑"会有两个坏结果：
                //   ① stickToBottom 被清掉 → 之后的新弹幕不再自动贴底；
                //   ② 「↓ 回到底部」按钮每自动滚一次就闪一下（它的判据里有 !stickToBottom）。
                //   动画的收尾判定由 ② 在动画结束时显式补一次（所以这里闭嘴不会漏状态）。
                if (bottomScroller.animating) return@collect
                // ★本轮补的第二个闸门（同一条"别把自己造成的位移读成用户操作"）：
                //   `pendingScrollToBottom` 从"提交那一刻"一直亮到"这一拍贴底结束"（见 ②），
                //   而这正是 LazyList 做 **key 锚定重定位**的窗口 —— 新行插到 index 0 之后，
                //   可见项在**没有任何人滚动**的情况下变成 `index=1、offset=0`。
                //   不挡这一下，`stickToBottom` 会在自动贴底真正开始之前被清成 false，
                //   于是 ② 里那句"用户上滑了就让位"会把**本该滚的那一次**也一起让掉
                //   （表现：列表停在落后一行，直到下一条弹幕才补上 —— 用户看到的就是"卡一下"）。
                //   ★判据里必须带 `!isScrollInProgress`：用户**真的**上滑一格也会落在 `index=1、offset=0`，
                //     那一次必须照旧清掉贴底（否则他一松手就会被我们拽回底部 = 老 bug 复活）；
                //     而锚定重定位期间**没有任何 scroll 会话在跑**，两者靠这一点分得干净。
                if (chat.pendingScrollToBottom && !listState.isScrollInProgress) return@collect
                // reverseLayout 下"贴底" = 第一条可见项就是 index 0 且没有被滑走
                val stick = index == 0 && offset <= stickTolerancePx
                // ★从"看历史"回到"贴底"：立刻叫醒那一拍去**提交缓冲 + 贴底**。
                //   不叫的话，用户翻完历史滑回底部时，缓冲里那几条要等**下一条弹幕**才出现
                //   （安静房间里就是"滑回来了却什么都没发生"，看起来像列表坏了）。
                if (stick && !chat.stickToBottom) chat.wakeUp.trySend(Unit)
                chat.onStickinessChanged(stick)
            }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 停靠面板是"视频下方的一块区域"，不是浮在画面上的卡片 → 不做圆角，用整块底色
            .background(CHAT_PANEL_BG)
            // ★吃掉落在面板上的**单击**：不然点一下面板的空白处会穿透到手势层，
            //   变成"显隐控制条"（列表常驻之后"点列表空白"是高频动作，一点就闪出控制条很烦）。
            //   indication = null：不要水波纹（这是个容器，不是按钮）。
            //   ★enabled = visible（本轮加）：退场动画期间 View 还是 VISIBLE 的，
            //     Compose 若不放手，那 180ms 里点在面板上的那一下会被吞掉 ——
            //     而改动前 View 是立刻 GONE，同一击会穿透到手势层。所以门只认**判定**，不认"还在不在画"。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = visible,
                onClick = {},
            ),
    ) {
        // ── 唯一的"头部"：一条 1dp 分隔线 ──
        // 不做标题栏：列表是**常驻**的（用户："直接不要有一个说什么按钮才能让它显示去"），
        // 标题/收起按钮占的正是"最新那几条"的位置；但画面与列表是两块不同材质，
        // 边界还是要一条线说清楚。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(CHAT_DIVIDER),
        )
        Box(modifier = Modifier.weight(1f)) {
            // ★reverseLayout：index 0 贴**底**。这样"最新在最下、旧的在上"是布局自带的，
            //   不需要把 200 条倒过来算坐标；LazyColumn 只组合可见的十来行（懒加载）。
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                items(items = chat.lines, key = { it.key }) { line ->
                    LiveDanmakuChatRow(
                        line = line,
                        // ★本轮：条目增删/位移的动画（`Modifier.animateItem`，Compose 1.7+ 的 API；
                        //   本工程 foundation = 1.12.1，证据见报告 §1）。
                        //   三个参数按位置给（`animateItem(淡入, 位移, 淡出)`），不写参数名 ——
                        //   这个 API 的参数名在 1.7→1.8 改过（appearance/disappearance → fadeIn/fadeOut），
                        //   位置参数对两种命名都成立：
                        //   ① 淡入 = 190ms tween（短、克制）：新弹幕是**淡**进来的，不是"啪"一下出现；
                        //   ② 位移 = ★本轮从 spring 换成等长的 tween（见下面那条注释）；
                        //   ③ 淡出 = 150ms tween（比淡入更短）：被 200 上限裁掉的那条**淡出去**，
                        //      不是凭空消失（列表没满 200 时这条路径本来也不会走）。
                        //   ★key 仍然是既有的单调 `line.key`：动画靠它认"这是同一条"，不能动。
                        modifier = Modifier.animateItem(
                            tween(
                                durationMillis = CHAT_ITEM_FADE_IN_MS,
                                easing = FastOutSlowInEasing,
                            ),
                            // ★本轮改动（spring → tween）：位移动画我们**只在一种情况下**用得上 ——
                            //   面板自己滚的时候 `LazyLayoutItemAnimator` 会用 `applyScrollOffset`
                            //   把滚动量扣掉，而"在底部插入新行"又靠 key 锚定保住了可见项的位置
                            //   （这条链路的证据见报告 §2.2），所以它本来就极少触发；
                            //   一旦触发（列表结构变化 + 位置确实变了），spring 会**按当前速度重定目标**，
                            //   在"连续几拍都在变"的场面里容易变成慢慢晃过去的拖尾；tween 是**有头有尾**的，
                            //   和贴底那一滚同量级、不抢戏，也不留"回弹"的尾巴。
                            tween(
                                durationMillis = CHAT_ITEM_PLACEMENT_MS,
                                easing = FastOutSlowInEasing,
                            ),
                            tween(
                                durationMillis = CHAT_ITEM_FADE_OUT_MS,
                                easing = FastOutLinearInEasing,
                            ),
                        ),
                    )
                }
            }
            // 空列表给一句话：刚进直播间、还没收到弹幕时，别让人以为"列表坏了"
            // ★本轮：判据从 `lines.isEmpty()` 换成 `chat.isEmpty` —— 提交降成"一拍一次"之后，
            //   "已经收到、还没提交"那一拍（≤120ms）不该被显示成"还没有收到弹幕"。
            if (chat.isEmpty) {
                Text(
                    text = "还没有收到弹幕…",
                    color = CHAT_HINT_COLOR,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (!chat.stickToBottom && !chat.pendingScrollToBottom) {
                // 用户上滑之后才出现的「回到底部」（竞品 PiliPlus chat_panel.dart:218-231 同款）：
                // 位置贴面板右下角，不遮住最新几行太久。
                // ★多一个 `!pendingScrollToBottom`：新行插到 index 0 的那一瞬，LazyList 的 key 锚定
                //   会把可见项挪到 index 1 —— 快照流会先报一次"用户上滑了"，随后自动滚动再把它纠回来。
                //   不挡这一下，按钮会在自动滚动的那一两帧里闪一下。
                Text(
                    text = "↓ 回到底部",
                    color = CHAT_TITLE_COLOR,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = 6.dp)
                        .background(CHAT_JUMP_BG, RoundedCornerShape(10.dp))
                        .clickable {
                            // 点它 = 明确表达"回到最新"：恢复自动滚动 + **叫醒那一拍**去贴底。
                            // ★本轮改成"交给 ② 那一拍"（原来是这里自己 `scope.launch { animate }`）：
                            //   因为"提交缓冲 → 等一帧量测 → 再滚"这三步必须成组出现（见 ②），
                            //   在这里单独起动画会跳过"等一帧"，滚到的还是提交前的旧位置；
                            //   而且走同一条路之后，**框架自己那条"距离超过 1500dp 就不再动画、直接归位"
                            //   的规则**对按钮也自动生效（翻了几百条历史再点它，不必等一段长动画慢慢飘回去 ——
                            //   见 LiveChatBottomScroller 类注释第 1 条与报告 §2.3）。
                            //   那一拍里的 `animating` 仍然会让位置判定闭嘴（按钮不会自己闪），
                            //   用户上手拖拽仍然是让位（不跟他抢）。
                            chat.onStickinessChanged(true)
                            chat.wakeUp.trySend(Unit)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * 列表里的一行："用户名："（**主题色**）+ 内容（弹幕自己的颜色，已补 alpha）。
 *
 * ★本轮（2026-09-26）修的就是用户报的那条 ——"直播间评论区，在竖屏的状态下，名字的高亮
 *   没有跟随我们主题颜色"：用户名以前是一个**写死的淡蓝**（`Color(0xFF8AB4F8)`，
 *   与主题毫无关系），现在改成 `MaterialTheme.colorScheme.primary`。
 *
 * 为什么是 `MaterialTheme.colorScheme.primary`（而不是别的取法）：
 * · 这是全工程"用户名跟随主题色"的**既有写法**，逐字同款 ——
 *   评论二级回复预览 `ReplyItemBox.kt:590` 的 `val unameColor = MaterialTheme.colorScheme.primary`；
 * · 而 `colorScheme.primary` 的**上游**正是"用户自定义主题色"：`BilimiaoTheme.appColorScheme()`
 *   拿 `AppStore.ThemeSettingState.color`（= DataStore 的 `theme_color`，自定义主题时就是用户挑的那个色）
 *   当 **seed**，经 materialkolor 推出整套色板（`BilimiaoTheme.kt:49-90`）。
 *   所以它不是 Material3 的基线紫，而是"我们主题色"那一支 —— 与首页/动态页同一个色源。
 *
 * ★面板里的 `MaterialTheme` 由宿主 `LiveDanmakuOverlayHost` 提供（面板是一份**独立 ComposeView**，
 *   不在 `ComposeFragment` 的 `BilimiaoTheme` 子树里；不套主题的话这里会落回 Material3 基线色）。
 *   宿主那边用的是同一个 `appColorScheme()` + `liveSheetThemeState()`（与直播设置弹窗同一条路径）。
 *
 * 只换了**颜色的来源**：字号/行高/内边距/最多 4 行/省略号、列表的滚动与去重逻辑，一个字没动。
 */
@Composable
private fun LiveDanmakuChatRow(line: LiveDanmakuChatLine, modifier: Modifier = Modifier) {
    // 用户名色 = 当前主题色（★不要写死颜色：用户换主题后这里要跟着变）
    val unameColor = MaterialTheme.colorScheme.primary
    // ★本轮：把这条 AnnotatedString **记住**（key = 这一行的三要素 + 用户名色）。
    //   这是"最小化重组"里最实在的一处：`buildAnnotatedString` 每次重跑都要重建 spans、
    //   分配 `SpanStyle`，而它现在被夹在"一拍一次提交 → `items()` 内容 lambda 重跑"之后，
    //   key 不变就返回**同一个实例** —— Text 的文本测量缓存也才有机会命中（重组的代价从
    //   "重新排版一行字 + 重新建 spans"降到"两次引用比较"）。
    //   行的内容是不可变的（`LiveDanmakuChatLine` 全是 val），所以这个 remember 不需要再多的 key。
    val text = remember(line.key, line.uname, line.text, line.color, unameColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = unameColor)) {
                append(line.uname)
                append("：") // 用户原话里的那个冒号（半角冒号在中文里太挤，用全角）
            }
            withStyle(SpanStyle(color = line.color)) {
                append(line.text)
            }
        }
    }
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        // 长弹幕最多 4 行（列表是拿来扫读的，一条占满屏就失去意义了）；超出省略
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

// ── 竖屏弹幕列表的参数 ──
//
// ★vc168"常驻停靠"删掉了 4 个常量（原来在这里）：
//   `CHAT_PANEL_HEIGHT_FRACTION = 0.42f` / `CHAT_PANEL_HEIGHT_MAX = 360.dp` /
//   `CHAT_PANEL_HEIGHT_MIN = 180.dp` / `CHAT_PANEL_BOTTOM_MARGIN = 52.dp` / `CHAT_ENTRY_BOTTOM_MARGIN = 56.dp`。
//   它们就是"弹幕列表盖住底栏『发弹幕』"的**根因**：那套数字是照"单行底栏 ≈ 41dp"估的，
//   而 vc168 的竖屏底栏是**两行 5+5**（≈ 66dp）+ 导航栏 insets → 52/56dp 的留白正好落在第一行按钮上。
//   现在矩形改由宿主 `LiveDanmakuOverlayHost` **按实测几何**算（视频画面底边 → 底栏顶边），
//   一个 dp 留白都不写死，所以这一节只剩"列表自己"的参数。

/** 列表最多保留多少条（硬上限，超出丢最旧的）。200 条 ≈ 热门房十几秒，够"偷看"用，内存也可忽略 */
private const val CHAT_MAX_LINES = 200

/**
 * "同一条弹幕"的指纹分隔符（[LiveDanmakuChatLog.addHistory] 去重用）。
 *
 * 用 `\u0000` 而不是 `:`：昵称或内容里本来就可能带冒号（"a:b"+"c" 与 "a"+"b:c" 会撞成同一个指纹），
 * NUL 是弹幕文本里**不可能出现**的字符（`LiveDanmakuClient.sanitizeText` 会把控制字符换成空格）。
 */
private const val DEDUP_SEPARATOR = "\u0000"

/** "贴底"判定的像素容差（Dp）：手指刚离开底部一点点不算"用户上滑了" */
private val CHAT_STICK_TOLERANCE = 2.dp

/**
 * 新弹幕**淡入**的时长（ms）。★本轮新增。
 *
 * 取 190（用户给的 160~220 区间中段偏短）的理由：
 * · 比 160 长一点：13sp 小字在高频房里 160ms 以内的淡入会显得"没淡开就到位了"，像闪；
 * · 比 220 短一点：热门房每秒十几条，单条停留时间本来就只有几百毫秒，淡入再长会"糊成一片灰"；
 * · 与滚动动画（`animateScrollToItem`）大致同量级，两者叠在一起是**一次**连贯的"滑进来"。
 */
private const val CHAT_ITEM_FADE_IN_MS = 190

/**
 * 被挤掉（200 上限裁掉）那一条**淡出**的时长（ms）。★本轮新增。
 *
 * 比淡入更短（150）：退场的东西不该比进场的东西更抢眼；而且它只在"列表满了又来了新的"时出现，
 * 位置一般在**屏幕外**（200 条里只有十来条可见），大多数时候用户根本看不到 —— 留着这个时长
 * 是给"列表刚好没满/刚好看得见最后一条"的场合用的（那种场合以前的"啪一下消失"最扎眼）。
 */
private const val CHAT_ITEM_FADE_OUT_MS = 150

/**
 * 条目**位移**动画的时长（ms）。★本轮新增（原来是 spring(NoBouncy, StiffnessMediumLow=400)）。
 *
 * 为什么换掉 spring（三条，见报告 §2.2 的证据链）：
 * 1. **它几乎不该被触发**：`LazyLayoutItemAnimator.startPlacementAnimationsIfNeeded` 算的是
 *    "这一项在**视口坐标**里的位置差"，而滚动量会被 `applyScrollOffset` 扣掉、头部插入又靠
 *    key 锚定保住了可见项的位置 —— 也就是说"我们自己的滚动"与"新行插入"这两条主路径都不会
 *    产生位移量。真正会让它动的是"列表结构变化 + 位置确实变了"这种少见场面；
 * 2. **spring 的收敛是"拖尾"型的**：`StiffnessMediumLow` 的临界阻尼时间常数 ≈ 1/√400 = 50ms，
 *    挪 20dp 要 ~250ms 才落到 1px 以内，而且中途被重定目标时会带着当前速度继续跑 ——
 *    在"一拍一变"的场面里容易变成慢慢晃过去；
 * 3. **tween 与贴底那一滚同呼吸**：160ms、`FastOutSlowInEasing`，有头有尾、不留回弹尾巴。
 * 取 160 而不是更长：它只是"补一格"的收尾动作，比淡入（190）略短，不该比新内容本身更抢眼。
 */
private const val CHAT_ITEM_PLACEMENT_MS = 160

/**
 * 贴底补偿的**最小间隔**（ms）★本轮新增 —— 注意语义是"两次补偿的**起步时刻**至少隔这么久"，
 * 不是"每次滚完再硬睡一拍"（这个区别决定了热门房里是"连续滑"还是"一跳一跳"）。
 *
 * 为什么要有它：上一版是"上一动画一落地，紧接着起下一个"（每个新弹幕都能起一次），
 * 每条消息都要各改一次列表、各让 LazyList 重算一遍量测；间隔把这条链按时间闸住，
 * 顺带把"同拍 N 条"的请求合并成一次补偿。
 *
 * 取值（120ms）的来历 + 为什么不能更大：
 * - 一轮补偿的时长本身就有 ~0.3s 的**下限**（Compose 的 spring 收敛时间只随距离对数增长：
 *   1 行 293ms、20 行 376ms，见报告 §2.2/§3 表 A）。间隔只要**小于**它，
 *   高速房间里两轮动画就会自然首尾相接 —— 这是必须的：内容每秒长高 λ×23dp，
 *   视口的**平均**速度必须与之一致，硬塞静止段只会把同样的位移挤成更陡的爆发。
 * - 间隔只要**大于**"低速房间一轮动画的实际可见时长"（几十毫秒），
 *   低速时就会呈现"滑一下、停一下"的完整动作（用户要的"过渡感"）。
 *   120ms 正好落在两者之间：竞品是 500ms（`EasyThrottle.throttle('liveDm', 500ms)`，
 *   `controller.dart:381-385`），我们取它的一半不到 —— 更跟手，但保持了同一套"节流"语义。
 * - 低于 100ms 就没有意义：同拍多条消息本来就会被 [LiveDanmakuChatLog.wakeUp] 合并。
 */
private const val CHAT_FOLLOW_TICK_MS = 200L

// ★本轮（"瞬时归位"这件事）**刻意没有**自己设一个"落后 N 行就跳"的阈值 —— 这里记下为什么：
// 1. Compose 自己就有一条"远距离直接归位"的规则：`animateScrollToItem` 在距离超过
//    `BoundDistance = 1500.dp` 时**不再动画，直接 snap**（证据：反编译 1.12.1 的
//    `androidx/compose/foundation/lazy/layout/LazyLayoutScrollScopeKt.animateScrollToItem`，
//    常量 2500/1500/50 dp，见报告 §2.3）。"翻了几百条历史再点回到底部"这种场面由它兜住。
// 2. 自造阈值的代价被量算打脸过（报告 §3 表 C）：热门房每秒十几~几十条，一轮补偿只跑
//    ~0.35s，于是"每轮开始时就已经落后十几行"是**稳态**而不是异常 —— 任何按行数设的阈值
//    都会在热门房里被稳态命中，把连续的滑动切成"一跳一跳"。
// 3. 所以这里的规则只有两条：**能滑就滑**（`animateToBottom`）、**已经在底部就一次都不滚**；
//    "追不上"这一类问题交给"提交与滚动都被节流 + 一轮动画覆盖整段积压"来解（见 ② 与表 B）。

/**
 * 待提交缓冲的**上限**（条）。★本轮新增（见 [LiveDanmakuChatLog] 的 `pending` 注释）。
 *
 * 为什么必须有：提交降成"一拍一次"之后，**面板不在屏上**（横屏 / PiP / 弹幕关着）或
 * 用户正在翻历史时没有"一拍"在跑，没人提交 → 缓冲会无界增长。到 40 条就自己提交：
 * - 40 条 ≈ 热门房两三秒，横屏看一会儿再转回竖屏，"那几秒的弹幕"照样一条不少；
 * - 上限同时是"最坏情况下一次 `addAll` 的量"，40 条的插入在 200 条的表上仍然是一次小操作。
 */
private const val CHAT_PENDING_MAX = 40

/**
 * 停靠列表的**最小可用高度**（宿主算出来的矩形比它矮时，宁可不显示列表、保留滚动弹幕）。
 * 96dp ≈ 5 行 13sp/17sp 的聊天，是"能读"的下限；比这更矮的一条带子里列表只剩两三行，
 * 还不如把滚动弹幕还给用户（见 `LiveDanmakuOverlayHost.refreshDockedPanel`）。
 */
internal val CHAT_DOCKED_MIN_HEIGHT = 96.dp

/** 面板底色：接近不透明（0xE6 ≈ 90%），保证小字在亮画面上也读得清 */
private val CHAT_PANEL_BG = Color(0xE6101418)

/** 头部标题/按钮色 */
private val CHAT_TITLE_COLOR = Color(0xFFDCE3EA)

/** 头部提示文字色（比标题更淡） */
private val CHAT_HINT_COLOR = Color(0xFF8A97A3)

/** 「回到底部」按钮底色 */
private val CHAT_JUMP_BG = Color(0xCC2B3440)

/** 头部分隔线 */
private val CHAT_DIVIDER = Color(0x22FFFFFF)

// ★本轮（2026-09-26）删掉了 `CHAT_NAME_COLOR = Color(0xFF8AB4F8)`（原来在这里）。
//   它就是用户报的"名字的高亮没有跟随我们主题颜色"的**根因**：用户名是写死的淡蓝，
//   换任何主题都不会变。现在用户名取 `MaterialTheme.colorScheme.primary`
//   （= 用户自定义主题色经 `appColorScheme()` 推出的主色），见 [LiveDanmakuChatRow]。
//   ★内容色（`line.color`）**不跟主题**：那是弹幕自己带的色（B 站的彩名/彩色弹幕），
//     跟主题走反而会把用户挑的颜色涂掉 —— 所以这一条只改"用户名"。
