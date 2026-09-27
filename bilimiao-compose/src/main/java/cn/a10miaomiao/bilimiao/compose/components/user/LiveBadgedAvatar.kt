package cn.a10miaomiao.bilimiao.compose.components.user

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.a10miaomiao.bilimiao.compose.R
import com.a10miaomiao.bilimiao.comm.live.LiveStatusCache
import com.a10miaomiao.bilimiao.comm.live.entity.LiveUserStatus
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder

/**
 * 「直播中」头像 —— UP 头像 + 直播中标签 + 向外扩散的涟漪，点一下直接进直播间。
 *
 * ## 为什么做成一个独立 Composable
 * 用户空间顶部头像、关注列表、动态卡片作者、粉丝列表……这些地方的头像**长得不一样、
 * 拿数据的方式也不一样**（有的是 Glide 直出、有的外面还套着图片预览器的缩放层），
 * 但"在播要挂标记 + 涟漪 + 点了进直播间"这件事完全一样。
 * 所以这里不接管头像的绘制（可以用 [avatarContent] 槽位自己画），
 * 只负责**在已有头像上叠三件事**：涟漪层、标签层、点击路由。
 *
 * ## 三层结构（顺序即绘制顺序，都在一个不自裁剪的 Box 里）
 * ```
 * Box(size = 头像尺寸)            ← 故意**不裁剪**：涟漪要能扩散到头像外面
 *  ├─ LiveRipple（仅 isLive 且 animateRipple）  圆环，画在头像底下
 *  ├─ Box(clip = CircleShape).clickable        头像本体 + 圆形水波纹反馈
 *  └─ LiveLabel「直播中」                       画在最上层，自带点击
 * ```
 * ★为什么涟漪层和"可点击的头像层"要拆成两个兄弟节点：
 *   若把 `clip(CircleShape)` 和绘制涟漪写在同一层，涟漪会被圆裁掉、全都看不见；
 *   而 `clickable` 又必须待在裁剪层里，否则点按反馈的水波纹会变成方角。
 *
 * ## 省电（这是本文件最需要注意的地方）
 * - **动画随组合存亡**：涟漪的 `rememberInfiniteTransition` 关在 [LiveRipple] 内部，
 *   而 [LiveRipple] 是 `if (isLive && animateRipple)` 条件组合的 ——
 *   不满足条件时它**整个从组合树里消失，动画随之销毁**，不是"画了但看不见"。
 *   `LazyColumn` 里滑出屏幕的 item 会被回收，于是这些头像的涟漪自动停掉。
 * - **[rememberIsResumed]**：页面不在前台（Activity 没 RESUMED）时，
 *   连**接口都不发**，动画也不跑。
 * - 接口本身还有 [LiveStatusCache] 的 5 分钟缓存 + 批合并兜底，见那边的注释。
 *
 * ## 参考实现
 * PiliPlus 的 `lib/common/widgets/pendant_avatar.dart:107-141` 是同一套做法
 * （底部居中一个带 `Icons.equalizer_rounded` 的「直播中」小药丸，`GestureDetector`
 * 点击 → `PageUtils.toLiveRoom(roomId)`，`roomId` 由 `liveStatus == 1` 决定）。
 * **涟漪是我们自己加的** —— PiliPlus 原版没有扩散动画（全仓 `ripple` 只命中
 * 一个和头像无关的 Tab 注释）。
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun LiveBadgedAvatar(
    /** 头像地址（拼了 `@200w_200h` 的 B 站 face 链接原样传进来即可） */
    face: String,
    size: Dp,
    modifier: Modifier = Modifier,
    /**
     * UP 的 uid。传了它就**自动查在播状态**（走 [LiveStatusCache] 的缓存 + 批合并）。
     * 不知道 uid（比如动态卡片只给了 face）就传 null，此时只看 [liveStatus]。
     */
    mid: String? = null,
    /**
     * 调用方**已知**的在播状态，优先级高于 [mid] 的自查。
     * 用户空间那条路就是这么用的：`x/v2/space` 的返回里本来就带 `live.liveStatus`，
     * 顺手喂进来就**一条请求都不用多发**。
     */
    liveStatus: LiveUserStatus? = null,
    /** 是否播涟漪动画。离屏 / 不可见时传 false（用户空间头部就是这么用的） */
    animateRipple: Boolean = true,
    /** 点在**头像本体**上时是否直接进直播间。默认 true，符合"点头像就是进他直播间"的诉求 */
    liveClickOnAvatar: Boolean = true,
    /** 没在播时点头像干什么（在播时被"进直播间"顶掉） */
    onClick: (() -> Unit)? = null,
    /** 覆盖"进直播间"的行为；不传就用内置的 [enterLiveRoom] */
    onLiveClick: ((roomId: Long) -> Unit)? = null,
    /** 头像本体自定义绘制（比如用户空间要套图片预览器的缩放层）。传了就忽略 [face] */
    avatarContent: (@Composable BoxScope.() -> Unit)? = null,
) {
    // ★只查一次、只在需要时查：liveStatus 已知就完全不发请求
    val queried = rememberLiveStatus(if (liveStatus == null) mid else null)
    val status = liveStatus ?: queried
    val roomId = status?.roomId ?: 0L
    val isLive = status?.isLive == true && roomId > 0

    val context = LocalContext.current
    val rippleColor = MaterialTheme.colorScheme.error

    val toLiveRoom: () -> Unit = {
        if (onLiveClick != null) onLiveClick(roomId) else enterLiveRoom(context, roomId)
    }
    val onAvatarClick: (() -> Unit)? = when {
        isLive && liveClickOnAvatar -> toLiveRoom
        else -> onClick
    }

    Box(modifier = modifier.size(size)) {
        // ① 涟漪：条件组合 —— 不可见/没在播时直接不进组合树，动画随之销毁
        if (isLive && animateRipple) {
            LiveRipple(
                color = rippleColor,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // ② 头像本体：裁剪 + 点击都在这一层（水波纹反馈才是圆的）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .then(
                    if (onAvatarClick == null) Modifier
                    else Modifier.clickable(onClick = onAvatarClick)
                ),
        ) {
            if (avatarContent != null) {
                avatarContent()
            } else {
                GlideImage(
                    model = UrlUtil.autoHttps(face) + "@200w_200h",
                    loading = placeholder(R.drawable.bili_akari_img),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // ③「直播中」标签：叠在最上层，自带点击（头像本体被别的层盖住时也点得到）
        if (isLive) {
            LiveLabel(
                fontSize = labelFontSize(size),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 2.dp)
                    .clickable(onClick = toLiveRoom),
            )
        }
    }
}

/**
 * 向外扩散的涟漪：默认 **3 圈**，相位各差 1/3 个周期，所以任何时刻都有圈在往外走。
 *
 * ★为什么用 `rememberInfiniteTransition` 而不是自己起 `Animatable` 循环：
 *   Compose 的无限动画跟着**组合**走，本 Composable 一旦离屏被回收就自动结束，
 *   不用手写"什么时候 start / 什么时候 stop"的状态机（那也是漏电的重灾区）。
 *
 * ★为什么画在 `Canvas` 里而不是叠三个 `Box`：
 *   圆环要扩到**头像尺寸之外**，`Box` 会被父级布局约束卡住，
 *   而 `Canvas` 的 `drawCircle` 不受布局边界限制（Compose 默认不裁剪绘制内容）。
 */
@Composable
private fun LiveRipple(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "live-ripple")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = RIPPLE_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "live-ripple-progress",
    )
    Canvas(modifier = modifier) {
        val baseRadius = size.minDimension / 2f
        // ★往外扩多少用"比例 + 上下夹紧"，不用固定倍数：
        //   固定倍数（比如 1.4x）在大头像上会扩出去 16dp、直接压到隔壁内容，
        //   在 40dp 小头像上又只扩 4dp 看不出来。夹到 [5dp, 9dp] 两头都舒服，
        //   而且保证不超出列表项本来留的 10~12dp 内边距（涟漪不会被裁）。
        val expand = (baseRadius * RIPPLE_EXPAND_RATIO).coerceIn(
            RIPPLE_EXPAND_MIN_DP.dp.toPx(),
            RIPPLE_EXPAND_MAX_DP.dp.toPx(),
        )
        val maxRadius = baseRadius + expand
        // ★线宽必须现算：Stroke 的 width 单位是**像素**，写死 3f 在 3x 屏上只有 1dp，
        //   在 1x 屏上又是 3dp，同一份代码两种观感。用 dp 换算才跟屏幕密度解耦。
        val strokeWidth = RIPPLE_STROKE_DP.dp.toPx()
        for (i in 0 until RIPPLE_RING_COUNT) {
            // 相位错开：第 i 圈比第 0 圈晚 i/count 个周期出发
            val p = (progress + i.toFloat() / RIPPLE_RING_COUNT) % 1f
            val radius = baseRadius + (maxRadius - baseRadius) * p
            // 透明度随扩散衰减到 0（而不是突然消失），看起来才像涟漪
            val alpha = (1f - p) * RIPPLE_MAX_ALPHA
            if (alpha <= 0.01f) continue
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                style = Stroke(width = strokeWidth),
            )
        }
    }
}

/**
 * 「直播中」小药丸：一个音量条图标 + 三个字（对齐 PiliPlus 的
 * `Icons.equalizer_rounded` + `Text('直播中')`，见 pendant_avatar.dart:119-137）。
 *
 * ★为什么字号跟着头像尺寸走：同一份组件要同时服务用户空间的 80dp 大头像和
 *   关注列表的 40dp 小头像，写死 13sp 会让小头像上的标签糊成一坨。
 */
@Composable
private fun LiveLabel(
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(percent = 50),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Equalizer,
                contentDescription = null,
                // 图标比字略大一点点，视觉上才跟汉字等重
                modifier = Modifier.size(fontSize.value.dp + 1.dp),
            )
            Spacer(Modifier.width(1.dp))
            Text(
                text = "直播中",
                fontSize = fontSize,
                lineHeight = fontSize,
                maxLines = 1,
                softWrap = false,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * 查一个 UP 的在播状态（走 [LiveStatusCache]：缓存 + 批合并）。
 *
 * ★为什么要"先 peek 再 query"：
 *   `peek` 是**同步**的，能让首帧就带上标记。只写 query 的话，一个刚才已经查过的 UP
 *   会先渲染成"没在播"、等下一个组合帧才补上标记，肉眼看得见地闪一下。
 *
 * ★为什么 `LaunchedEffect` 的 key 里有 [rememberIsResumed]：
 *   页面退到后台时 effect 被取消（不再发请求 / 不再等结果），回到前台再重新问一次 ——
 *   这就是"离屏不请求"里"离屏"的那一半（另一半是 LazyColumn 回收 item）。
 */
@Composable
fun rememberLiveStatus(
    mid: String?,
    enabled: Boolean = true,
): LiveUserStatus? {
    val key = mid?.trim().orEmpty()
    val resumed = rememberIsResumed()
    var status by remember(key) {
        mutableStateOf(if (key.isEmpty()) null else LiveStatusCache.peek(key))
    }
    LaunchedEffect(key, resumed, enabled) {
        if (key.isEmpty() || !resumed || !enabled) return@LaunchedEffect
        val cached = LiveStatusCache.peek(key)
        if (cached != null) {
            status = cached
            return@LaunchedEffect
        }
        status = LiveStatusCache.query(key)
    }
    return status
}

/**
 * 当前 Activity 是否处于 RESUMED。
 *
 * ★为什么不用 `collectAsStateWithLifecycle`：那个是给 `Flow` 用的；
 *   这里只是要一个布尔值，`LifecycleEventObserver` 更直接，也不会因为
 *   lifecycle 版本差异换 API（`currentStateFlow` 是 2.7 才有的）。
 */
@Composable
fun rememberIsResumed(): Boolean {
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(owner) {
        // ★写成匿名对象而不是 `LifecycleEventObserver { _, _ -> }` 的 SAM 简写：
        //   后者要求 LifecycleEventObserver 是 Kotlin 的 `fun interface`，
        //   而这属于"lifecycle 版本一变就可能不成立"的隐含前提，不值得赌。
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

/**
 * 进直播间：**原生播放页**（第二阶段 A 路的 `LivePlayerActivity`），失败兜底网页。
 *
 * ★为什么用 `setClassName` 的类名字符串、而不是直接 `import LivePlayerActivity`：
 *   `LivePlayerActivity` 在 **app 模块**，本文件在 **bilimiao-compose 模块**，
 *   依赖方向是 app → compose，反向引用会成环编译不过。extra 的 key `"roomId"`
 *   与 `LivePlayerActivity.EXTRA_ROOM_ID`（app/.../LivePlayerActivity.kt:87）
 *   是同一份字面量约定 —— 与 `CoverViewModel.kt:84-99`、
 *   `HomeLiveContent.kt:322-346` 用的是同一套。
 *
 * @param roomId **真实房间号**（`get_status_info_by_uids` / `x/v2/space` 给的都是真实号）
 */
fun enterLiveRoom(context: Context, roomId: Long) {
    if (roomId <= 0) return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setClassName(context, LIVE_PLAYER_ACTIVITY)
        putExtra(LIVE_PLAYER_EXTRA_ROOM_ID, roomId.toString())
    }
    runCatching { context.startActivity(intent) }.onFailure {
        // 原生页万一拉不起来（理论上不会），退回网页直播间，别让用户点了没反应
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://live.bilibili.com/$roomId"))
            )
        }
    }
}

/** 标签字号：跟着头像尺寸线性走，再夹到 [8sp, 12sp] —— 太小看不清、太大盖住脸 */
private fun labelFontSize(size: Dp): TextUnit =
    (size.value * 0.24f).coerceIn(8f, 12f).sp

/** 涟漪一个完整周期的时长（ms）。1.6s 是"看得出来在动、但不晃眼"的常见取值 */
private const val RIPPLE_PERIOD_MS = 1600

/** 涟漪圈数。用户要的是"两三圈"，取 3 —— 相位错开 1/3，任何时刻都有圈在往外走 */
private const val RIPPLE_RING_COUNT = 3

/** 涟漪往外扩的幅度占基础半径的比例（还要再过一遍下面的上下限） */
private const val RIPPLE_EXPAND_RATIO = 0.22f

/** 涟漪最少往外扩多少 dp —— 小头像（40dp）全靠它才看得出来在扩散 */
private const val RIPPLE_EXPAND_MIN_DP = 5f

/** 涟漪最多往外扩多少 dp —— 大头像（80dp）靠它别扩到隔壁内容上、也别被列表边缘裁掉 */
private const val RIPPLE_EXPAND_MAX_DP = 9f

/** 涟漪起始透明度。太实会像"边框"，太淡又看不见 */
private const val RIPPLE_MAX_ALPHA = 0.55f

/** 圆环线宽（dp）。在 [LiveRipple] 里用 `toPx()` 换算成像素后再交给 Stroke */
private const val RIPPLE_STROKE_DP = 1.5f

/** 与 app 模块 `LivePlayerActivity` 对齐的字面量约定（见 [enterLiveRoom] 注释） */
private const val LIVE_PLAYER_ACTIVITY = "com.a10miaomiao.bilimiao.LivePlayerActivity"
private const val LIVE_PLAYER_EXTRA_ROOM_ID = "roomId"
