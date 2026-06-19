package com.a10miaomiao.bilimiao

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.preference.PreferenceManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import cn.a10miaomiao.bilimiao.compose.StartViewWrapper
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.widget.scaffold.AppBarView
import com.a10miaomiao.bilimiao.widget.scaffold.ScaffoldView
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.AppBarBehavior
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.ContentBehavior
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.PlayerBehavior
import com.a10miaomiao.bilimiao.config.config
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.DrawerBehavior
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.MaskBehavior
import com.a10miaomiao.bilimiao.widget.player.DanmakuVideoPlayer
import com.a10miaomiao.bilimiao.widget.scaffold.behavior.MyBottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior
import splitties.experimental.InternalSplittiesApi
import splitties.views.backgroundColor
import splitties.views.dsl.core.*


@OptIn(InternalSplittiesApi::class)
class MainUi(
    override val ctx: Context,
    private val startViewWrapper: StartViewWrapper,
) : Ui {

    companion object {
        // 重启activiry时保持播放
        private var keepPlayerView: DanmakuVideoPlayer? = null
        fun clearKeepPlayerView() {
            keepPlayerView = null
        }
    }

    val mLeftContainerView = startViewWrapper.getView()

    val mContainerView = inflate<View>(R.layout.container_fragment) {
        backgroundColor = Color.TRANSPARENT
    }

    val mSubContainerView = inflate<View>(R.layout.container_fragment_sub) {
        backgroundColor = Color.TRANSPARENT
    }

    val mAppBar = view<AppBarView>{
        setOnClickListener {  }
    }

    val mVideoPlayerView = keepPlayerView?.apply {
        try {
            (parent as? ViewGroup)?.removeAllViews()
            // 直接替换旧PlayerView的Context
            // 注意：View.mContext 是非公开 API，Android 13+ 收紧了反射访问。
            // SDK_INT 守卫：仅旧版本尝试反射，新版本直接复用（Context 通常无需替换）；
            // 失败则降级，不会导致 Activity 重建崩溃。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val clazz = View::class.java
                val mContextField = clazz.getDeclaredField("mContext")
                mContextField.isAccessible = true
                if (mContextField.get(this) is Context) {
                    mContextField.set(this, ctx)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    } ?: inflate<DanmakuVideoPlayer>(R.layout.include_palyer2) {
        keepPlayerView = this
    }

    val mPlayerLayout = frameLayout {
        backgroundColor = 0xFF000000.toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineSpotShadowColor = config.shadowColor
        }

        val completionView = inflate<RelativeLayout>(R.layout.include_completion_box)
        val errorMessageView = inflate<RelativeLayout>(R.layout.include_error_message_box)
        val areaLimitView = inflate<RelativeLayout>(R.layout.include_area_limit_box)
        val loadingView = inflate<FrameLayout>(R.layout.include_player_loading)

        addView(mVideoPlayerView, lParams(matchParent, matchParent))
        addView(completionView, lParams(matchParent, matchParent))
        addView(errorMessageView, lParams(matchParent, matchParent))
        addView(areaLimitView, lParams(matchParent, matchParent))
        addView(loadingView, lParams(matchParent, matchParent))
    }

    var mMaskView = view<View> {
        setBackgroundResource(R.color.black)
        setOnClickListener {  }
        alpha = 0f
        visibility = View.GONE
    }

    override val root = view<ScaffoldView>() {
        orientation = resources.configuration.orientation
        backgroundColor = config.blockBackgroundColor

        addView(mContainerView, lParams {
            // 内容视图，使用 ContentBehavior 来处理播放器遮挡
            behavior = ContentBehavior(ctx, null)
            width = matchParent
            height = matchParent
        })

        addView(mAppBar, lParams {
            behavior = AppBarBehavior(ctx, null)
            width = matchParent
            height = matchParent
        })

        addView(mMaskView, lParams {
            behavior = MaskBehavior(ctx, null)
            height = matchParent
            width = matchParent
        })

        addView(mPlayerLayout, lParams {
            behavior = PlayerBehavior(ctx, null)
            width = wrapContent
            height = wrapContent
        })

        addView(mLeftContainerView, lParams {
            height = matchParent
            width = matchParent
            behavior = DrawerBehavior(ctx, null)
        })
    }

}