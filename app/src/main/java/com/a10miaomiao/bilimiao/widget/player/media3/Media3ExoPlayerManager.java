package com.a10miaomiao.bilimiao.widget.player.media3;

import android.content.Context;
import android.media.AudioManager;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.video.PlaceholderSurface;

import com.shuyu.gsyvideoplayer.cache.ICacheManager;
import com.shuyu.gsyvideoplayer.model.GSYModel;
import com.shuyu.gsyvideoplayer.model.VideoOptionModel;
import com.shuyu.gsyvideoplayer.player.BasePlayerManager;

import java.util.List;

import tv.danmaku.ijk.media.player.IMediaPlayer;

@OptIn(markerClass = UnstableApi.class)
public class Media3ExoPlayerManager extends BasePlayerManager {

    /** DASH播放器缓冲时间(秒)，默认15，0=系统默认(50s) */
    private static int dashBufferSec = 15;

    /**
     * **内存里最多缓存多少字节（硬上限）**。
     *
     * 为什么必须有它（用户 2026-09-19 报的 OOM）：`DefaultLoadControl` 不设这个值时，
     * 内存占用是按「码率 × 缓冲时长」算出来的 —— 4K / 高码率番剧缓冲 30 秒就是 100MB+
     * （音视频各一份，还要算上拖进度留下的 back buffer），长视频来回拖很容易把堆吃爆。
     * 设了它之后，缓冲到顶就**停止下载**（数据还在磁盘缓存里），而不是继续往堆里分配。
     * 64MB 是官方文档里的常见取值：够放几十秒高码率视频，又不会把手机堆撑爆。
     */
    private static final int TARGET_BUFFER_BYTES = 64 * 1024 * 1024;

    /** 缓冲秒数允许的范围（防止设置里填出离谱的值把内存拖垮） */
    private static final int MIN_BUFFER_SEC = 5;
    private static final int MAX_BUFFER_SEC = 120;

    public static void setDashBufferSec(int sec) {
        dashBufferSec = sec;
    }

    public static int getDashBufferSec() {
        return dashBufferSec;
    }

    private Context context;

    private ExoMediaPlayer mediaPlayer;

    private Surface surface;

    private PlaceholderSurface dummySurface;

    private long lastTotalRxBytes = 0;

    private long lastTimeStamp = 0;

    @Override
    public IMediaPlayer getMediaPlayer() {
        return mediaPlayer;
    }

    protected ExoMediaPlayer buildMediaPlayer(Context context) {
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true);
        ExoMediaPlayer exoMediaPlayer = new ExoMediaPlayer(context);
        exoMediaPlayer.setRendererFactory(renderersFactory);
        // DASH 缓冲裁减：默认 15s → 可配置（播放设置 → DASH 缓冲），避免高码率 OOM
        // 0 = ExoPlayer 默认（50s/50s）；其它值 clamp 到 5~120 秒
        int bufferMs = dashBufferSec > 0
                ? Math.max(MIN_BUFFER_SEC, Math.min(dashBufferSec, MAX_BUFFER_SEC)) * 1000
                : 50000;
        // ★ min 与 max 取同一个值：原来 max 写成 min 的 2 倍，用户设「50 秒」实际会缓冲 100 秒，
        //   与设置页文案不符、也更容易吃内存
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        bufferMs,      // minBufferMs
                        bufferMs,      // maxBufferMs
                        2500,          // bufferForPlaybackMs: 2.5s
                        5000           // bufferForPlaybackAfterRebufferMs: 5s
                )
                // ★ 堆内缓冲硬上限：到顶就停止下载，而不是继续分配（长视频/高码率的 OOM 就是这么来的）
                .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                // 内存到顶时仍按"时间阈值"起播/续播，避免一直转圈
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        exoMediaPlayer.setLoadControl(loadControl);
        com.a10miaomiao.bilimiao.comm.utils.PlayerDiag.INSTANCE.log(
                "loadcontrol",
                "min/max=" + bufferMs + "ms playback=2500/5000ms targetBufferBytes="
                        + (TARGET_BUFFER_BYTES / (1024 * 1024)) + "MB dashBufferSec=" + dashBufferSec);
        com.a10miaomiao.bilimiao.comm.utils.PlayerDiag.INSTANCE.memory("player-built");
        return exoMediaPlayer;
    }

    @Override
    public void initVideoPlayer(Context context, Message msg, List<VideoOptionModel> optionModelList, ICacheManager cacheManager) {
        this.context = context.getApplicationContext();
        mediaPlayer = buildMediaPlayer(context);
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        if (dummySurface == null) {
            dummySurface = PlaceholderSurface.newInstanceV17(context, false);
        }
        //使用自己的cache模式
        GSYModel gsyModel = (GSYModel) msg.obj;
        try {
            mediaPlayer.setLooping(gsyModel.isLooping());
            mediaPlayer.setPreview(gsyModel.getMapHeadData() != null && gsyModel.getMapHeadData().size() > 0);
            if (gsyModel.isCache() && cacheManager != null) {
                //通过管理器处理
                cacheManager.doCacheLogic(context, mediaPlayer, gsyModel.getUrl(), gsyModel.getMapHeadData(), gsyModel.getCachePath());
            } else {
                //通过自己的内部缓存机制
                mediaPlayer.setCache(gsyModel.isCache());
                mediaPlayer.setCacheDir(gsyModel.getCachePath());
                mediaPlayer.setOverrideExtension(gsyModel.getOverrideExtension());
                mediaPlayer.setDataSource(context, Uri.parse(gsyModel.getUrl()), gsyModel.getMapHeadData());
            }
            if (gsyModel.getSpeed() != 1 && gsyModel.getSpeed() > 0) {
                mediaPlayer.setSpeed(gsyModel.getSpeed(), 1);
            }
        } catch (Exception e) {
            Log.e("Media3ExoPlayer", "initVideoPlayer failed", e);
        }
        initSuccess(gsyModel);
    }

    @Override
    public void showDisplay(final Message msg) {
        if (mediaPlayer == null) {
            return;
        }
        if (msg.obj == null) {
            mediaPlayer.setSurface(dummySurface);
        } else {
            Surface holder = (Surface) msg.obj;
            surface = holder;
            mediaPlayer.setSurface(holder);
        }
    }

    @Override
    public void setSpeed(final float speed, final boolean soundTouch) {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.setSpeed(speed, 1);
            } catch (Exception e) {
                Log.e("Media3ExoPlayer", "setSpeed failed", e);
            }
        }
    }

    @Override
    public void setNeedMute(final boolean needMute) {
        if (mediaPlayer != null) {
            if (needMute) {
                mediaPlayer.setVolume(0, 0);
            } else {
                mediaPlayer.setVolume(1, 1);
            }
        }
    }

    @Override
    public void setVolume(float left, float right) {
        if (mediaPlayer != null) {
            mediaPlayer.setVolume(left, right);
        }
    }

    @Override
    public void releaseSurface() {
        if (surface != null) {
            //surface.release();
            surface = null;
        }
    }

    @Override
    public void release() {
        if (mediaPlayer != null) {
            mediaPlayer.setSurface(null);
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (dummySurface != null) {
            dummySurface.release();
            dummySurface = null;
        }
        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
    }

    @Override
    public int getBufferedPercentage() {
        if (mediaPlayer != null) {
            return mediaPlayer.getBufferedPercentage();
        }
        return 0;
    }

    @Override
    public long getNetSpeed() {
        if (mediaPlayer != null) {
            return getNetSpeed(context);
        }
        return 0;
    }


    @Override
    public void setSpeedPlaying(float speed, boolean soundTouch) {
    }


    @Override
    public void start() {
        if (mediaPlayer != null) {
            mediaPlayer.start();
        }
    }

    @Override
    public void stop() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
        }
    }

    @Override
    public void pause() {
        if (mediaPlayer != null) {
            mediaPlayer.pause();
        }
    }

    @Override
    public int getVideoWidth() {
        if (mediaPlayer != null) {
            return mediaPlayer.getVideoWidth();
        }
        return 0;
    }

    @Override
    public int getVideoHeight() {
        if (mediaPlayer != null) {
            return mediaPlayer.getVideoHeight();
        }
        return 0;
    }

    @Override
    public boolean isPlaying() {
        if (mediaPlayer != null) {
            return mediaPlayer.isPlaying();
        }
        return false;
    }

    @Override
    public void seekTo(long time) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(time);
        }
    }

    @Override
    public long getCurrentPosition() {
        if (mediaPlayer != null) {
            return mediaPlayer.getCurrentPosition();
        }
        return 0;
    }

    @Override
    public long getDuration() {
        if (mediaPlayer != null) {
            return mediaPlayer.getDuration();
        }
        return 0;
    }

    @Override
    public int getVideoSarNum() {
        if (mediaPlayer != null) {
            return mediaPlayer.getVideoSarNum();
        }
        return 1;
    }

    @Override
    public int getVideoSarDen() {
        if (mediaPlayer != null) {
            return mediaPlayer.getVideoSarDen();
        }
        return 1;
    }

    @Override
    public boolean isSurfaceSupportLockCanvas() {
        return false;
    }


    /**
     * 设置seek 的临近帧。
     **/
    public void setSeekParameter(@Nullable SeekParameters seekParameters) {
        if (mediaPlayer != null) {
            mediaPlayer.setSeekParameter(seekParameters);
        }
    }


    private long getNetSpeed(Context context) {
        if (context == null) {
            return 0;
        }
        long nowTotalRxBytes = TrafficStats.getUidRxBytes(context.getApplicationInfo().uid) == TrafficStats.UNSUPPORTED ? 0 : (TrafficStats.getTotalRxBytes() / 1024);//转为KB
        long nowTimeStamp = System.currentTimeMillis();
        long calculationTime = (nowTimeStamp - lastTimeStamp);
        if (calculationTime == 0) {
            return calculationTime;
        }
        //毫秒转换
        long speed = ((nowTotalRxBytes - lastTotalRxBytes) * 1000 / calculationTime);
        lastTimeStamp = nowTimeStamp;
        lastTotalRxBytes = nowTotalRxBytes;
        return speed;
    }

}