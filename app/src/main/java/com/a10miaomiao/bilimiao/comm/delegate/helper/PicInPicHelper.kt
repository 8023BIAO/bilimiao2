package com.a10miaomiao.bilimiao.comm.delegate.helper

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.a10miaomiao.bilimiao.R
import com.a10miaomiao.bilimiao.widget.player.DanmakuVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYVideoPlayer
import java.util.concurrent.atomic.AtomicInteger


@RequiresApi(Build.VERSION_CODES.O)
class PicInPicHelper(
    val activity: Activity,
    /** 动态获取当前播放器 View，不持有强引用以防 View 重建后引用失效 */
    private val playerProvider: () -> DanmakuVideoPlayer?,
) {

    companion object {
        /**
         * PiP 动作广播 action 的**前缀**（不是最终 action，最终 action 见 [mediaControlAction]）。
         *
         * ★为什么必须有实例盐：PendingIntent 的身份 =（创建包名, requestCode, `Intent.filterEquals`），
         *   而 `filterEquals` **不比较 extras** —— 同一个 action + 写死的 requestCode 会让同 App 内
         *   所有 PiP 实例拿到**同一个** PendingIntent（token 被系统合并，extras 谁最后建谁覆盖）。
         *   于是两个播放器同时在小窗时，点一次两个接收器都会响应（用户说的"抢接口"），
         *   而且完全无法分辨点的是哪个小窗。
         */
        val ACTION_MEDIA_CONTROL = "media_control"
        val EXTRA_CONTROL_TYPE = "control_type"

        val CONTROL_TYPE_PLAY = 1
        val CONTROL_TYPE_PAUSE = 2
        val CONTROL_TYPE_SKIP_BACK = 3
        val CONTROL_TYPE_SKIP_FORWARD = 4

        val REQUEST_TYPE_PLAY = 1
        val REQUEST_TYPE_PAUSE = 2
        val REQUEST_TYPE_SKIP_BACK = 3
        val REQUEST_TYPE_SKIP_FORWARD = 4

        /**
         * 实例发号器：只用来给 action / requestCode 加盐，不参与任何播放逻辑。
         *
         * 为什么不用 `hashCode()` 当盐：两个 Activity 的 hashCode 完全可能相同（撞了就退化成
         * 老 bug）；发号器单调递增，天然不重复。低位只留 28 位，避免后面 `shl 3` 溢出成负数。
         */
        private val INSTANCE_SEQ = AtomicInteger(1)
    }


    /**
     * 最近一次进 PiP 用的宽高比（已夹取过）。
     *
     * 为什么必须记住：PiP 里的动作按钮要随播放状态刷新，而刷新是**整体重建**参数
     * （见 [buildParams] 注释）—— 重建时若不带比例，系统会退回默认比例，
     * 部分 ROM 上小窗形状会跟着变（横屏视频变方、竖屏视频变扁）。
     * 初值 16:9 只是兜底，正常路径一定先经过 [enterPictureInPictureMode]。
     */
    private var lastAspectRatio: Rational = Rational(16, 9)

    /**
     * 本实例的盐（全局唯一，见 [INSTANCE_SEQ]）。
     * 只决定"这条 PiP 动作通道归谁"，不参与任何播放行为 —— 所以单播放器场景下它等于不存在。
     */
    private val instanceSalt: Int = INSTANCE_SEQ.getAndIncrement() and 0x0FFFFFFF

    /**
     * 本实例专属的媒体控制 action（[ACTION_MEDIA_CONTROL] + 盐）。
     *
     * 接收器只认它（外加一个老协议兼容口，见 [broadcastReceiver]）：
     * 广播的 action 是**实例级**唯一的，所以"点哪个小窗"只会送到"哪个小窗的接收器"。
     */
    private val mediaControlAction: String = "$ACTION_MEDIA_CONTROL.$instanceSalt"

    /**
     * 接收器是否已注册：注册/注销必须成对且幂等。
     *
     * 为什么需要这个标志：`onPictureInPictureModeChanged(true)` 在个别 ROM 上会连着来两次，
     * 同一个接收器用同一个 filter 注册两遍会被系统记成两条 filter → 一次点击投递两遍
     * （表现就是"点一下前进 10 秒，实际跳了 20 秒"）。
     */
    private var receiverRegistered = false

    var isInPictureInPictureMode = false


    val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            // 只认两种 action：
            //  ① 本实例的定向 action（[mediaControlAction]）—— 正常路径，点哪个小窗只动哪个；
            //  ② 裸 action（老协议）—— **兼容口**：点播播放页（VideoPlayerActivity）自己组装 PiP
            //     动作，用的还是 `ACTION_MEDIA_CONTROL` 这个老 action（它按"接收器由 PlayerDelegate2
            //     在进 PiP 时注册"来设计的）。那边改用 [controlPendingIntent] 之后这个口子就能删掉。
            //     注意：老协议天然不区分实例，所以它仍是"谁在 PiP 谁都应"。
            if (action != mediaControlAction && action != ACTION_MEDIA_CONTROL) {
                return
            }
            val player = playerProvider() ?: return
            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                CONTROL_TYPE_PLAY -> {
                    player.onVideoResume()
                    // 不刷新的话画中画里的图标一直停在旧状态，点一次后就"失灵"
                    updatePictureInPictureActions(player.currentState)
                }
                CONTROL_TYPE_PAUSE -> {
                    player.onVideoPause()
                    updatePictureInPictureActions(player.currentState)
                }
                CONTROL_TYPE_SKIP_BACK -> {
                    player.seekTo(player.currentPosition - 10000)
                    updatePictureInPictureActions(player.currentState)
                }
                CONTROL_TYPE_SKIP_FORWARD -> {
                    player.seekTo(player.currentPosition + 10000)
                    updatePictureInPictureActions(player.currentState)
                }
            }
        }
    }

    /**
     * 立刻进画中画（PiP）。
     *
     * ★这是**手动入口**用的函数：播放器顶栏「小窗播放」图标 / 「更多」里的那一项点一下就走这里。
     *   前台调用 `enterPictureInPictureMode()` 系统会**立即**把当前 Activity 弹成小窗，
     *   不需要用户先退到桌面 —— 退后台自动进 PiP 是另一条路（[com.a10miaomiao.bilimiao.comm.delegate.player.PlayerDelegate2.tryEnterPipOnBackground]），
     *   它最终也调到这里，两条路共用同一份参数。
     *
     * @return 是否真的把请求交给了系统。false 有两种情况：
     *         ① 播放器 View 还没就绪（[playerProvider] 为空）；② 系统拒绝（多窗口/被 ROM 关闭画中画等）。
     *         调用方据此给用户反馈，别让"点了没反应"变成无解之谜。
     */
    fun enterPictureInPictureMode(aspectRatio: Rational): Boolean {
        // 判断Android版本是否大于等于8.0（enterPictureInPictureMode 是 8.0 新增的方法）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val player = playerProvider() ?: return false
        val clamped = clampAspectRatio(aspectRatio)
        lastAspectRatio = clamped
        // 源矩形提示只在真正要进 PiP 时才给：它描述"视频此刻在屏幕上的位置"，
        // 进/出场动画从这块矩形开始（缩）和回到这块矩形（放），比整屏缩放自然
        val params = buildParams(player.currentState, clamped, sourceRectHint())
        return activity.enterPictureInPictureMode(params)
    }

    /**
     * Android PiP 的宽高比必须在 [1:2.39, 2.39:1] 范围内，
     * 超宽屏视频（如 32:9）不夹会让 `build()` 抛 IllegalArgumentException
     * → 用户看到的是"此设备不支持小窗播放"。
     */
    private fun clampAspectRatio(aspectRatio: Rational): Rational {
        val ratio = aspectRatio.numerator.toFloat() / aspectRatio.denominator.toFloat()
        return when {
            ratio > 2.39f -> Rational(239, 100)      // 上限 2.39:1
            ratio < 1f / 2.39f -> Rational(100, 239) // 下限 1:2.39
            else -> aspectRatio
        }
    }

    /**
     * 组装一次**完整**的 PiP 参数（进 PiP 与 PiP 内刷新按钮图标共用同一份，避免两套参数打架）。
     *
     * 为什么每次新建 Builder、不再复用一个字段：
     *   ① 参数必须按**当下**状态重建（播放/暂停图标、标题、源矩形都是"此刻"的）；
     *   ② 复用的 builder 会把上一次进 PiP 时塞进去的 sourceRectHint 一直带着，
     *      PiP 窗口里刷新按钮图标时会顺手把过期矩形也提交上去（无害但没意义）；
     *   ③ 一条路组装 = 以后加参数（比如 setAutoEnterEnabled）只需改一处。
     */
    private fun buildParams(
        state: Int,
        aspectRatio: Rational,
        sourceRectHint: Rect?,
    ): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(clampAspectRatio(aspectRatio))
            .setActions(getActions(state))
        // ★2026-09-26 用户实测回退：`setSeamlessResizeEnabled(true)` 会让系统对"进/出小窗"做
        //   无缝尺寸过渡；而点播页在"小窗 → 桌面 → 回 App"这条路上要经历一次全屏回填，
        //   用户实测**卡在过渡中间**（半屏旧界面半屏新界面 + 全 App 黑屏乱掉）。
        //   这条参数是后来才加的锦上添花，去掉后行为回到用户之前满意的状态（比例/动作/标题都不受影响）。
        //   想恢复只需把下面两行取消注释。
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        //     builder.setSeamlessResizeEnabled(true)
        // }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // PiP 窗口标题取自播放器标题控件：那个控件只是 visibility=invisible，
            // 一直在（见 MEMORY：删除控件会让 PiP 没标题）。取空就不设，免得 PiP 顶栏空一块
            val title = playerProvider()?.findViewById<TextView>(R.id.title)?.text
            if (title != null && title.isNotBlank()) {
                builder.setTitle(title)
            }
        }
        // ★同上：源矩形也是"过渡动画从视频矩形起缩"的优化，与无缝缩放配套；
        //   回退无缝缩放后它一并停用，避免"一半旧界面一半新界面"的过渡卡住。
        // if (sourceRectHint != null) {
        //     builder.setSourceRectHint(sourceRectHint)
        // }
        return builder.build()
    }

    /**
     * 源矩形提示（[PictureInPictureParams.Builder.setSourceRectHint]）：视频此刻在屏幕上的可见矩形。
     *
     * 直播那套（`LivePlayerActivity.enterPipMode`）没有这个参数 —— 这是点播这边**多出来**的一处改进：
     * 进/出 PiP 的过渡动画以视频矩形为起点/终点，而不是从整屏缩过来，视觉上更"原地变小窗"。
     *
     * 只在 View 真的挂在窗口上、且可见区域非空时才给：
     * 给一个空矩形与不给等价（系统会按整屏处理），不如干脆不设。
     * `getGlobalVisibleRect` 给的是**屏幕坐标**，与 setSourceRectHint 要求的坐标口径一致。
     */
    private fun sourceRectHint(): Rect? {
        val player = playerProvider() ?: return null
        if (!player.isAttachedToWindow || player.width <= 0 || player.height <= 0) return null
        val rect = Rect()
        if (!player.getGlobalVisibleRect(rect) || rect.isEmpty()) return null
        return rect
    }

    /**
     * PiP 动作的 Intent：**显式定向**（老版是 `Intent("media_control")` 这种隐式广播）。
     *
     * - action 带实例盐 → 同 App 内别的 PiP 实例的接收器不匹配，点谁只动谁；
     * - `setPackage(自己的包名)` → 这条广播只在本 App 内投递：别的 App 既**发不进来**
     *   （接收器按 NOT_EXPORTED 注册，见 [registerReceiverSafe]），也**偷听不到**我们按了什么。
     *
     * ★不用 `setClass`：接收器是**动态注册**的（只在 PiP 期间存在，随 Activity 生死），
     *   没有清单里的类可以指；对动态接收器来说，"定向"能用的手段就是 package + 唯一 action。
     */
    private fun controlIntent(controlType: Int): Intent =
        Intent(mediaControlAction)
            .setPackage(activity.packageName)
            .putExtra(EXTRA_CONTROL_TYPE, controlType)

    /**
     * 每个动作的 requestCode：实例盐放高位，动作编号放低 3 位。
     *
     * ★必须避开 [REQUEST_TYPE_PLAY]..[REQUEST_TYPE_SKIP_FORWARD]（1~4）这几个**写死的旧值**：
     *   点播播放页（VideoPlayerActivity）那边就是拿它们建 PiP 动作 PendingIntent 的，
     *   一旦撞上（同包 + 同 requestCode + filterEquals 相同）系统就会当成同一个 token。
     *   盐从 1 起 → 实际 requestCode 从 8 起，天然错开。
     */
    private fun requestCodeOf(requestType: Int): Int = (instanceSalt shl 3) or (requestType and 0x7)

    /**
     * PiP 动作的 PendingIntent（[getActions] 里四个动作共用这一份组装逻辑）。
     *
     * 为什么不复用 builder / 不缓存 PendingIntent：动作集合随播放状态整体重建，
     * 每次都要带上"此刻"的 controlType；这里只保证"同一实例 + 同一动作"拿到的是同一个 token。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun controlPendingIntent(requestType: Int, controlType: Int): PendingIntent =
        PendingIntent.getBroadcast(
            activity,
            requestCodeOf(requestType),
            controlIntent(controlType),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * 给"自己组装 PiP 动作"的宿主用的公开入口（当前是点播播放页 `VideoPlayerActivity`）。
     *
     * 那边现在是**自己拼 Intent**（裸 action + 写死的 requestCode），所以它的按钮仍然
     * "谁在 PiP 谁都应"；改调这里就能拿到同一条定向通道（包名 + 实例盐 + 唯一 requestCode），
     * 接收端不用动（控制类型协议完全一样）。
     *
     * ★本次没有改那个文件（不在本轮的独占范围内），所以这个方法是**给那一侧接线的钩子**；
     *   在它被调用之前，接收器里的裸 action 兼容口必须留着（见 [broadcastReceiver]）。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun newControlPendingIntent(requestType: Int, controlType: Int): PendingIntent =
        controlPendingIntent(requestType, controlType)

    @RequiresApi(Build.VERSION_CODES.O)
    fun getActions(state: Int): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()
        // 后退10秒
        actions.add(
            RemoteAction(
                Icon.createWithResource(activity, R.drawable.media3_icon_skip_back_10),
                "后退10秒",
                "",
                controlPendingIntent(REQUEST_TYPE_SKIP_BACK, CONTROL_TYPE_SKIP_BACK),
            )
        )
        // 播放/暂停
        val playPauseAction = if (state == GSYVideoPlayer.CURRENT_STATE_PLAYING) {
            RemoteAction(
                Icon.createWithResource(activity, R.drawable.bili_player_play_can_pause),
                "暂停",
                "",
                controlPendingIntent(REQUEST_TYPE_PAUSE, CONTROL_TYPE_PAUSE),
            )
        } else {
            RemoteAction(
                Icon.createWithResource(activity, R.drawable.bili_player_play_can_play),
                "播放",
                "",
                controlPendingIntent(REQUEST_TYPE_PLAY, CONTROL_TYPE_PLAY),
            )
        }
        actions.add(playPauseAction)
        // 前进10秒
        actions.add(
            RemoteAction(
                Icon.createWithResource(activity, R.drawable.media3_icon_skip_forward_10),
                "前进10秒",
                "",
                controlPendingIntent(REQUEST_TYPE_SKIP_FORWARD, CONTROL_TYPE_SKIP_FORWARD),
            )
        )
        return actions
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun updatePictureInPictureActions(state: Int) {
        // PiP 窗口里只有三个按钮，图标必须跟着状态走（否则点一次后图标停在旧状态、看着像"失灵"）。
        // 重建时带上最近一次的比例，别让小窗形状在刷新图标时被系统改掉
        activity.setPictureInPictureParams(buildParams(state, lastAspectRatio, null))
    }

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        this.isInPictureInPictureMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            registerReceiverSafe()
        } else {
            unregisterReceiverSafe()
        }
    }

    /**
     * 注册 PiP 动作接收器（幂等）。
     *
     * 为什么用 `RECEIVER_NOT_EXPORTED`：这个接收器只服务本 App 自己发的 PiP 动作
     * （PendingIntent 由 SystemUI 代发，但身份是**创建它的本 App**，所以照收不误），
     * 于是"任何 App 都能发 media_control 遥控本 App 播放"这条就此关掉。
     * 各版本的落地方式（`ContextCompat` 内部处理，工程里不用自己分支）：
     *  - Android 13+（API 33）：平台标志位，系统硬拦；
     *  - Android 7~12L（API 24~32）：退化成"要求发送方持有本 App 的签名级动态权限"
     *    （`<包名>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，androidx.core 清单里声明、
     *    随清单合并进来），效果同样是"只有本 App/同签名能发"。
     *
     * 为什么 filter 里有两个 action：
     *  - [mediaControlAction]：本实例的定向通道（正常路径）；
     *  - [ACTION_MEDIA_CONTROL]：老协议兼容口，理由见 [broadcastReceiver] 的注释。
     */
    private fun registerReceiverSafe() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(mediaControlAction)
            addAction(ACTION_MEDIA_CONTROL)
        }
        ContextCompat.registerReceiver(
            activity,
            broadcastReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    /**
     * 注销画中画广播接收器（幂等）。
     * 只在 onPictureInPictureModeChanged(false) 里注销不够：进画中画后直接销毁 Activity
     * 不保证回调 false → LogCat "Activity has leaked IntentReceiver"，接收器还持有播放器与 Activity。
     *
     * ★这里不靠 [receiverRegistered] 提前 return：标志只用来挡"重复注册"，
     *   注销一律照做（没注册过时系统抛 IllegalArgumentException，被吞掉），
     *   免得万一标志与实际状态不同步就漏掉一次注销 → 泄漏。
     */
    fun unregisterReceiverSafe() {
        receiverRegistered = false
        try {
            activity.unregisterReceiver(broadcastReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

}
