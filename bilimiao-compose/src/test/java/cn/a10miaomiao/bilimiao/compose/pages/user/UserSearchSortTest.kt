package cn.a10miaomiao.bilimiao.compose.pages.user

import bilibili.app.archive.v1.Arc
import bilibili.app.archive.v1.Stat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用户空间搜索结果排序的单元测试（纯逻辑，不需要设备）。
 *
 * 背景：接口 SearchArchiveReq 没有排序字段，排序只能在客户端做；
 * 之前排序菜单点了没反应，根因是 rankOrder 变化后没有任何地方重排。
 * 这里把排序逻辑抽成 UserSearchSort 并锁住行为。
 */
class UserSearchSortTest {

    private fun arc(aid: Long, ctime: Long, view: Int) = Arc(
        aid = aid,
        ctime = ctime,
        stat = Stat(view = view),
    )

    // aid 顺序故意和时间顺序相反，用来区分"按 aid 排"和"按发布时间排"
    private val items = listOf(
        arc(aid = 3, ctime = 100L, view = 10),
        arc(aid = 1, ctime = 300L, view = 500),
        arc(aid = 2, ctime = 200L, view = 100),
    )

    @Test
    fun pubdate_sortsByPublishTimeDesc() {
        val sorted = UserSearchSort.sort(items, UserSearchSort.ORDER_PUBDATE)
        assertEquals(listOf(1L, 2L, 3L), sorted.map { it.aid })
    }

    @Test
    fun stime_sortsByPublishTimeAsc() {
        val sorted = UserSearchSort.sort(items, UserSearchSort.ORDER_STIME)
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.aid })
    }

    @Test
    fun click_sortsByViewCountDesc() {
        val sorted = UserSearchSort.sort(items, UserSearchSort.ORDER_CLICK)
        assertEquals(listOf(1L, 2L, 3L), sorted.map { it.aid })
    }

    @Test
    fun unknownOrder_fallsBackToPubdate() {
        assertEquals(
            listOf(1L, 2L, 3L),
            UserSearchSort.sort(items, "something-else").map { it.aid }
        )
        assertEquals(
            listOf(1L, 2L, 3L),
            UserSearchSort.sort(items, null).map { it.aid }
        )
    }

    @Test
    fun missingStat_treatedAsZeroViews() {
        val noStat = listOf(
            arc(aid = 1, ctime = 100L, view = 5),
            Arc(aid = 2, ctime = 200L), // stat = null
        )
        val sorted = UserSearchSort.sort(noStat, UserSearchSort.ORDER_CLICK)
        assertEquals(listOf(1L, 2L), sorted.map { it.aid })
    }

    @Test
    fun emptyList_staysEmpty() {
        assertEquals(emptyList<Arc>(), UserSearchSort.sort(emptyList(), UserSearchSort.ORDER_CLICK))
    }
}
