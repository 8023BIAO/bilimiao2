package cn.a10miaomiao.bilimiao.compose.pages.live

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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
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
            //   存一条的代价 = 一次 SnapshotStateList 的插入 + 200 条硬上限的裁剪（见 LiveDanmakuChatLog），
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
 *    这里是**硬上限**，好处是"列表到底占多少内存"是一句能验证的话（永远 ≤200 行）；
 *    代价（每条多一次 `removeAt(lastIndex)`）在本工程的量级下可以忽略 ——
 *    Compose 的 `SnapshotStateList` 是持久化列表，200 个引用的一次搬移约 1.6KB，
 *    按热门房 30 条/秒算是 ~50KB/s 的 memmove，和"每条 add 本来就 O(n)"同阶。
 * 3. **只在主线程读写**（宿主的组合、面板的滑动回调都在主线程），所以不需要锁、不用 @Volatile。
 *    对比点播 `DanmakuTextFilter` 当年"主线程 clear + 缓存线程遍历"崩过进程那一类问题。
 * 4. 弹幕**不透明度不参与**列表渲染：列表是"读字"，用户把弹幕透明度调到 30% 是为了不挡画面，
 *    不是为了让自己读不清聊天 —— 所以内容色只补 alpha=FF，不乘 settings.opacity。
 */
class LiveDanmakuChatLog(private val capacity: Int = CHAT_MAX_LINES) {

    /** 最新的在 index 0（面板 reverseLayout → 它在屏幕最底） */
    val lines = mutableStateListOf<LiveDanmakuChatLine>()

    /**
     * 用户当前是否**贴在底部**（true = 新弹幕来了要自动滚到底）。
     *
     * 由 [LiveDanmakuChatPanel] 用 `LazyListState` 维护（上滑 → false，滑回底 → true），
     * 由 [add] 读它决定"这条要不要触发自动滚动"。默认 true：一打开就跟最新。
     */
    var stickToBottom by mutableStateOf(true)
        private set

    /**
     * "插入时发现用户贴在底部"的标记：面板看到它就 `scrollToItem(0)`，然后清掉。
     *
     * ★为什么不干脆让面板监听"列表变了没"再判断一次当前是否在底部：
     *   等面板被重组/effect 跑起来时，新行**已经**插到 index 0 了，
     *   LazyList 的 key 锚定已经把可见项往后挪了一格 —— 此刻再判断就会把
     *   "其实一直贴在底部"误判成"用户上滑过"，自动滚动就永远不生效。
     *   所以判断必须在**插入之前**做，标记就是那次判断的结果。
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
     */
    fun onPanelOpened() {
        stickToBottom = true
        pendingScrollToBottom = false
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
        lines.add(0, LiveDanmakuChatLine(nextKey++, name, msg.text, color))
        // 丢最旧的 = 列表尾部（index 大的一端）；硬上限，见类注释第 2 条
        while (lines.size > capacity) lines.removeAt(lines.lastIndex)
        if (stickToBottom) pendingScrollToBottom = true
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
        while (lines.size > capacity) lines.removeAt(lines.lastIndex)
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
 */
@Composable
fun LiveDanmakuChatPanel(
    chat: LiveDanmakuChatLog,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // ① 上屏（首次 / 从横屏转回竖屏）→ 贴到最新。放在 visible 的 effect 里而不是 initial 里：
    //    面板 View 是 GONE↔VISIBLE 复用的，LazyListState 会记住上次的阅读位置，
    //    "重新上屏还停在很久以前"对用户就是"新弹幕没进来"。
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        chat.onPanelOpened()
        listState.scrollToItem(0) // reverseLayout：index 0 = 最新 = 屏幕最底
    }

    // ② 贴底时来新弹幕 → 滚回底部（标记在 add() 里、插入之前打好，见 pendingScrollToBottom 的注释）
    LaunchedEffect(visible, chat.pendingScrollToBottom) {
        if (!visible || !chat.pendingScrollToBottom) return@LaunchedEffect
        listState.scrollToItem(0)
        chat.consumeAutoScroll()
    }

    // ③ 用户滑动 → 维护"贴底/上滑"状态（上滑 = 别打扰我；滑回底部 = 恢复自动滚动）
    LaunchedEffect(visible, listState) {
        if (!visible) return@LaunchedEffect
        val tolerance = with(density) { CHAT_STICK_TOLERANCE.toPx() }
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                // reverseLayout 下"贴底" = 第一条可见项就是 index 0 且没有被滑走
                chat.onStickinessChanged(index == 0 && offset <= tolerance)
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
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
                    LiveDanmakuChatRow(line)
                }
            }
            // 空列表给一句话：刚进直播间、还没收到弹幕时，别让人以为"列表坏了"
            if (chat.lines.isEmpty()) {
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
                            // 点它 = 明确表达"回到最新"：滚到底 + 恢复自动滚动
                            chat.onStickinessChanged(true)
                            scope.launch { listState.animateScrollToItem(0) }
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
private fun LiveDanmakuChatRow(line: LiveDanmakuChatLine) {
    // 用户名色 = 当前主题色（★不要写死颜色：用户换主题后这里要跟着变）
    val unameColor = MaterialTheme.colorScheme.primary
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = unameColor)) {
                append(line.uname)
                append("：") // 用户原话里的那个冒号（半角冒号在中文里太挤，用全角）
            }
            withStyle(SpanStyle(color = line.color)) {
                append(line.text)
            }
        },
        fontSize = 13.sp,
        lineHeight = 17.sp,
        // 长弹幕最多 4 行（列表是拿来扫读的，一条占满屏就失去意义了）；超出省略
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
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
