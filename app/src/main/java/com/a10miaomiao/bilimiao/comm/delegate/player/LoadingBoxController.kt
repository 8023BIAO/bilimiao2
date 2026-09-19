package com.a10miaomiao.bilimiao.comm.delegate.player

import android.graphics.drawable.AnimationDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.a10miaomiao.bilimiao.R
import com.a10miaomiao.bilimiao.comm.network
import splitties.views.imageResource

class LoadingBoxController(
    private val activity: AppCompatActivity,
    private val delegate: PlayerDelegate2,
) {

    private val loadingLayout = activity.findViewById<FrameLayout>(R.id.player_loading)
    private val loadingTopLauout = activity.findViewById<LinearLayout>(R.id.loading_top)
    private val loadingBottomLayout = activity.findViewById<LinearLayout>(R.id.loading_bottom);
    private val loadingCloseBtn = activity.findViewById<ImageView>(R.id.loading_close)
    private val loadingMoreBtn = activity.findViewById<ImageView>(R.id.loading_more)
    private val loadingFullscreenBtn = activity.findViewById<ImageView>(R.id.loading_fullscreen)
    private val loadingTitle = activity.findViewById<TextView>(R.id.loading_title)
    private val loadingCover = activity.findViewById<ImageView>(R.id.loading_cover)
    private val loadingAnimTV = activity.findViewById<ImageView>(R.id.loading_anim_tv)
    private val loadingText = activity.findViewById<TextView>(R.id.loading_text)

    init {
        initLoadingBox()
    }

    private fun initLoadingBox() {
        loadingCloseBtn.setOnClickListener {
            delegate.controller.onBackClick()
        }
        loadingMoreBtn.setOnClickListener(delegate.controller::showMoreMenu)
        loadingFullscreenBtn.setOnClickListener(delegate.controller::changeFullscreen)
        loadingFullscreenBtn.setOnLongClickListener {
            delegate.controller.showFullModeMenu(it)
            true
        }
    }

    fun setWindowInsets(left: Int, top: Int, right: Int, bottom: Int) {
        loadingTopLauout.setPadding(left, top, right, 0);
        loadingBottomLayout.setPadding(left, 0, right, bottom)
    }

    /**
     * 加载页底部那几行步骤日志（"初始化播放器...成功 / 装载弹幕数据...成功 / 获取视频信息..."）。
     *
     * ★ 先攒在缓冲里，**加载超过 [STEP_LOG_DELAY_MS] 才显示**：
     *   秒开的时候它只会闪一下就没了（用户反馈："中间过渡的是什么玩意，显示不到一秒，怪"），
     *   干脆不让它闪；加载慢的时候它照旧在，能告诉用户卡在哪一步。
     */
    private val stepLog = StringBuilder()
    private var loadingShownAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val flushStepLog = Runnable { loadingText.text = stepLog.toString() }

    fun showLoading(title: String, cover: String) {
        stepLog.setLength(0)
        loadingText.text = ""
        mainHandler.removeCallbacks(flushStepLog)
        loadingShownAt = SystemClock.uptimeMillis()
        mainHandler.postDelayed(flushStepLog, STEP_LOG_DELAY_MS)
        loadingTitle.text = title
        loadingCover.network(cover)
        loadingLayout.visibility = View.VISIBLE
        (loadingAnimTV.drawable as? AnimationDrawable)?.start()
    }

    fun hideLoading() {
        mainHandler.removeCallbacks(flushStepLog)
        (loadingAnimTV.drawable as? AnimationDrawable)?.stop()
        loadingLayout.visibility = View.GONE
        loadingCover.imageResource = 0
        loadingText.text = ""
    }

    fun print(text: String) {
        stepLog.append(text)
        flushStepLogIfReady()
    }

    fun println(text: String) {
        stepLog.append(text).append('\n')
        flushStepLogIfReady()
    }

    /** 过了"秒开"窗口就实时刷新；还在窗口内就等 [flushStepLog] 到点再一次性显示 */
    private fun flushStepLogIfReady() {
        if (SystemClock.uptimeMillis() - loadingShownAt >= STEP_LOG_DELAY_MS) {
            loadingText.text = stepLog.toString()
        }
    }

    companion object {
        /** 加载快于这个时长 = 秒开，步骤日志不显示（免得闪一下技术日志） */
        private const val STEP_LOG_DELAY_MS = 700L
    }
}