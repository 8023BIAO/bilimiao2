package cn.a10miaomiao.bilimiao.compose.pages.user

import bilibili.app.archive.v1.Arc

/**
 * 用户空间「搜索投稿」结果的客户端排序。
 *
 * 接口 `SearchArchiveReq` 本身没有排序字段（proto 里只有 keyword/mid/pn/ps/player_args），
 * 所以只能拿到数据后在本地排——抽成纯函数便于单测（见 UserSearchSortTest）。
 */
object UserSearchSort {

    /** 最新发布（也是接口返回的默认顺序） */
    const val ORDER_PUBDATE = "pubdate"

    /** 最多播放 */
    const val ORDER_CLICK = "click"

    /** 最旧发布 */
    const val ORDER_STIME = "stime"

    fun sort(items: List<Arc>, order: String?): List<Arc> = when (order) {
        ORDER_STIME -> items.sortedBy { it.ctime }
        ORDER_CLICK -> items.sortedByDescending { it.stat?.view ?: 0 }
        else -> items.sortedByDescending { it.ctime }
    }
}
