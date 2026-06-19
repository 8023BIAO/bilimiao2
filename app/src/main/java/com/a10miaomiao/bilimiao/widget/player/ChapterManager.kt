package com.a10miaomiao.bilimiao.widget.player

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.a10miaomiao.bilimiao.R
import com.a10miaomiao.bilimiao.widget.menu.CheckPopupMenu

/**
 * 章节弹窗管理
 *
 * 管理播放器章节按钮显示/隐藏、章节列表弹窗。
 * 完全独立于手势/触摸逻辑。
 */
class ChapterManager(
    private val player: DanmakuVideoPlayer,
) {
    private val mChapterBtnLayout: ViewGroup = player.findViewById(R.id.chapter_btn_layout)
    private val mChapterBtnText: TextView = player.findViewById(R.id.chapter_btn_text)

    private var chapters: List<ChapterInfo> = emptyList()
    private var chapterSeekAction: ((Long) -> Unit)? = null

    /** 设置章节数据（有章节显示按钮，没有则隐藏） */
    fun setChapters(chapters: List<ChapterInfo>, onChapterClick: ((Long) -> Unit)? = null) {
        this.chapters = chapters
        chapterSeekAction = onChapterClick
        if (chapters.size <= 1) {
            mChapterBtnLayout.visibility = View.GONE
        } else {
            mChapterBtnText.text = "章节"
            mChapterBtnLayout.visibility = View.VISIBLE
        }
    }

    /** 隐藏章节按钮 */
    fun hideChapters() {
        mChapterBtnLayout.visibility = View.GONE
        chapters = emptyList()
    }

    /** 初始化章节按钮点击监听 */
    fun initChapterButton() {
        mChapterBtnLayout.setOnClickListener {
            showChapterPopup()
        }
    }

    /** 打开章节选择弹窗 */
    fun showChapterPopup() {
        if (chapters.size <= 1) return

        val menus = chapters.mapIndexed { index, chap ->
            val label = if (chap.title.isNullOrEmpty()) {
                "第${index + 1}章"
            } else {
                chap.title
            }
            CheckPopupMenu.MenuItemInfo(label, chap.startMs)
        }
        val pm = CheckPopupMenu(
            context = player.context,
            anchor = mChapterBtnLayout,
            menus = menus,
            value = -1L,
            themeColor = player.themeColor,
            checkable = false,
        )
        pm.onMenuItemClick = { item ->
            val startMs = item.value
            chapterSeekAction?.invoke(startMs)
        }
        pm.show()
    }

    /** 控件可见性变化时同步章节按钮 */
    fun onControlsVisibleChanged(visible: Boolean) {
        mChapterBtnLayout.visibility =
            if (visible && chapters.size > 1) View.VISIBLE else View.GONE
    }

    /** 投屏按钮可见性 */
    fun updateCastButton(hasDevices: Boolean, isVideoReady: Boolean) {
        player.findViewById<View>(R.id.cast_btn_layout).visibility =
            if (hasDevices && isVideoReady) View.VISIBLE else View.GONE
    }
}
