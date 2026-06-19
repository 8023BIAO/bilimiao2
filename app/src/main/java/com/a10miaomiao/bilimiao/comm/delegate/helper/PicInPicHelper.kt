package com.a10miaomiao.bilimiao.comm.delegate.helper

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.a10miaomiao.bilimiao.R
import com.a10miaomiao.bilimiao.widget.player.DanmakuVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYVideoPlayer


@RequiresApi(Build.VERSION_CODES.O)
class PicInPicHelper(
    val activity: Activity,
    /** 动态获取当前播放器 View，不持有强引用以防 View 重建后引用失效 */
    private val playerProvider: () -> DanmakuVideoPlayer?,
) {

    companion object {
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
    }


    private val builder = PictureInPictureParams.Builder()

    var isInPictureInPictureMode = false


    val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) {
                return
            }
            if (intent.action != ACTION_MEDIA_CONTROL) {
                return
            }
            val player = playerProvider() ?: return
            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                CONTROL_TYPE_PLAY -> {
                    player.onVideoResume()
                }
                CONTROL_TYPE_PAUSE -> {
                    player.onVideoPause()
                }
                CONTROL_TYPE_SKIP_BACK -> {
                    player.seekTo(player.currentPosition - 10000)
                }
                CONTROL_TYPE_SKIP_FORWARD -> {
                    player.seekTo(player.currentPosition + 10000)
                }
            }
        }
    }

    fun enterPictureInPictureMode(aspectRatio: Rational) {
        // 判断Android版本是否大于等于8.0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val player = playerProvider() ?: return
            // Android PiP 宽高比必须在 [1:2.39, 2.39:1] 范围内，
            // 超宽屏视频（如32:9）会超限导致 IllegalArgumentException → "此设备不支持小窗播放"
            val ratio = aspectRatio.numerator.toFloat() / aspectRatio.denominator.toFloat()
            val clamped = when {
                ratio > 2.39f -> Rational(239, 100)     // 上限 2.39:1
                ratio < 1f / 2.39f -> Rational(100, 239) // 下限 1:2.39
                else -> aspectRatio
            }
            // 设置画中画窗口的宽高比例
            builder.setAspectRatio(clamped)
            builder.setActions(getActions(player.currentState))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setSeamlessResizeEnabled(true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.setTitle(player.findViewById<TextView>(R.id.title).text)
            }
            // 进入画中画模式，注意enterPictureInPictureMode是Android8.0之后新增的方法
            activity.enterPictureInPictureMode(builder.build());
        };
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun getActions(state: Int): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()
        // 后退10秒
        actions.add(
            RemoteAction(
                Icon.createWithResource(activity, R.drawable.media3_icon_skip_back_10),
                "后退10秒",
                "",
                PendingIntent.getBroadcast(
                    activity,
                    REQUEST_TYPE_SKIP_BACK,
                    Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_SKIP_BACK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        )
        // 播放/暂停
        val playPauseAction = if (state == GSYVideoPlayer.CURRENT_STATE_PLAYING) {
            RemoteAction(
                Icon.createWithResource(activity, R.drawable.bili_player_play_can_pause),
                "暂停",
                "",
                PendingIntent.getBroadcast(
                    activity,
                    REQUEST_TYPE_PAUSE,
                    Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PAUSE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            RemoteAction(
                Icon.createWithResource(activity, R.drawable.bili_player_play_can_play),
                "播放",
                "",
                PendingIntent.getBroadcast(
                    activity,
                    REQUEST_TYPE_PLAY,
                    Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PLAY),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        }
        actions.add(playPauseAction)
        // 前进10秒
        actions.add(
            RemoteAction(
                Icon.createWithResource(activity, R.drawable.media3_icon_skip_forward_10),
                "前进10秒",
                "",
                PendingIntent.getBroadcast(
                    activity,
                    REQUEST_TYPE_SKIP_FORWARD,
                    Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_SKIP_FORWARD),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        )
        return actions
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun updatePictureInPictureActions(state: Int) {
        builder.setActions(getActions(state))
        activity.setPictureInPictureParams(builder.build());
    }

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        this.isInPictureInPictureMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            ContextCompat.registerReceiver(
                activity,
                broadcastReceiver,
                IntentFilter(ACTION_MEDIA_CONTROL),
                ContextCompat.RECEIVER_EXPORTED
            )
        } else {
            try {
                activity.unregisterReceiver(broadcastReceiver)
            } catch (_: IllegalArgumentException) { }
        }
    }

}
