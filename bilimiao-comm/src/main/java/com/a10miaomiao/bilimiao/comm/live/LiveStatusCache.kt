package com.a10miaomiao.bilimiao.comm.live

import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.live.entity.LiveUserStatus
import com.a10miaomiao.bilimiao.comm.live.entity.LiveUserStatusInfo
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "这个 uid 现在在不在直播"的**全局缓存 + 批量合并器**。
 *
 * ## 为什么必须有它（而不是每个头像各发一条请求）
 * 关注列表一屏就是十几个头像，动态流更多。若每个头像自己发一条
 * `get_status_info_by_uids`，一屏 = 十几条请求，滑两下就是几十条 ——
 * B 站风控会直接拦（同类高频接口实测会回 -352），用户的流量和电也白烧。
 * 这里做三层防护：
 *
 * 1. **批合并**：调用方只管 `query(uid)`，第一个来的开一个 [BATCH_WINDOW_MS] 的窗口，
 *    窗口内所有 uid 攒成一批、切 [MAX_UIDS_PER_REQUEST] 个一组发请求。
 *    一屏 20 个头像都在同一帧里陆续进批 → **实际只发 1 条请求**。
 * 2. **TTL 缓存**：[TTL_MS] 内同一个 uid 直接吃缓存，不再进批（来回滑动不重复打）。
 * 3. **离屏不请求**：这条由调用方（Compose 侧）保证 —— `LazyColumn` 里没被组合的
 *    item 根本不会调到 [query]；页面不在前台时 Compose 侧也不会发起
 *    （见 `LiveBadgedAvatar.kt` 的 `rememberIsResumed`）。
 *
 * ## 为什么不用 Room / DataStore
 * 这是**秒级时效**的数据（开播/下播说变就变），落盘没有意义；只缓存 5 分钟，
 * 进程内一张 HashMap 就是最合适的形态，零依赖零 IO。
 *
 * ## 线程模型（为什么锁是 `synchronized(lock)` 而不是 `Mutex`）
 * 全部共享状态（[cache] / [pending] / [windowScheduled]）都只在这一把锁里读写，
 * 而且**每个临界区都极短、绝不挂起**，所以用 `synchronized` 比 `Mutex` 更直白，
 * 也天然避开"在锁里 await 把整批人卡死"这类坑。
 * 真正的网络请求跑在 [scope]（IO）里，**不占用调用方的协程** ——
 * UI 被销毁/取消时，已经进批的请求仍会正常收尾并把结果写进缓存（下一个人直接命中），
 * 不会把同批其它人的 Deferred 永远挂住。
 */
object LiveStatusCache {

    /**
     * 缓存有效期：5 分钟。
     *
     * ★为什么是 5 分钟：B 站"开播/下播"本身就有十几秒到一分钟的感知延迟，
     *   5 分钟内的"标记还挂着但其实刚下播"完全可以接受；反过来把 TTL 压到 30 秒，
     *   只会让用户来回滑动时反复打接口，收益为零、代价翻倍。
     */
    const val TTL_MS = 5 * 60 * 1000L

    /**
     * 批窗口：进批后等这么久发车。
     *
     * ★为什么是 80ms：一屏 LazyColumn 的 item 组合发生在**同一帧**里（16ms 内），
     *   80ms 足够把它们全部收进来；同时又远小于人的感知阈值，
     *   头像上的标记不会出现"先没有、过一会儿才冒出来"的迟滞感。
     */
    private const val BATCH_WINDOW_MS = 80L

    /**
     * 单次请求最多带几个 uid。
     *
     * ★实测（见 LiveAPI.liveStatusByUids 的注释）：30 个实测 30/30 全回；
     *   再往上服务端会开始"只回它有房间的那部分"，所以 30 是安全值。
     *   （顺带和 `LiveAPI.AREA_ROOM_PAGE_SIZE = 30` 撞了个巧。）
     */
    private const val MAX_UIDS_PER_REQUEST = 30

    /**
     * 查询**失败**后的退避时间（写一条"没在播"的负缓存，挡住这段时间内的重复请求）。
     *
     * ★为什么必须有：失败是不写正常缓存的，若不额外挡一下，
     *   用户来回滑动列表就会"滑一次打一批"，网络一抖反而变成接口轰炸。
     *   60 秒足够让一次抖动过去，又不至于让标记长时间失灵。
     */
    private const val FAIL_BACKOFF_MS = 60 * 1000L

    private class Entry(val status: LiveUserStatus, val expireAt: Long)

    /**
     * [query] 的一次决策结果 —— 用一个小类而不是"在 synchronized 里给 val 赋值"：
     * 后者要靠编译器的确定性赋值分析，可读性和稳妥性都差。
     */
    private class Plan(
        /** 缓存直接命中时的结果 */
        val cached: LiveUserStatus?,
        /** 需要等网络时的凭据 */
        val deferred: CompletableDeferred<LiveUserStatus>?,
        /** 是不是"开窗口的人"（只有他要负责 [BATCH_WINDOW_MS] 后发车） */
        val needWindow: Boolean,
    )

    private val lock = Any()

    /** uid -> 结果（含过期时间） */
    private val cache = HashMap<String, Entry>()

    /** 已进批、正在等结果的 uid -> 它的等待者 */
    private val pending = HashMap<String, CompletableDeferred<LiveUserStatus>>()

    /** 当前是否已经有一个"窗口计时器"在跑（避免每个 uid 都开一个计时器） */
    private var windowScheduled = false

    /** 内部作用域：**故意不用调用方的 scope**，理由见类注释"线程模型" */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 同步读缓存（不发起任何请求）。
     *
     * 用途：Compose **首帧**就能拿到值 —— 如果这个 uid 刚才已经查过，
     * 头像上的「直播中」标记会**立刻**出现，而不是先渲染成没标记、等网络回来再跳一下。
     */
    fun peek(uid: String): LiveUserStatus? {
        val key = normalize(uid) ?: return null
        synchronized(lock) {
            return freshLocked(key)
        }
    }

    /**
     * 查一个 uid 的在播状态。缓存命中直接返回；没命中就进批，等这一批的请求回来。
     *
     * @return 查到返回结果；网络失败 / 接口报错返回"没在播"，并且只写一条
     *         [FAIL_BACKOFF_MS] 的负缓存 —— 不能把一次网络抖动**当成 5 分钟的确定结论**，
     *         但也不能让它变成"滑一次打一批"。
     */
    suspend fun query(uid: String): LiveUserStatus? {
        val key = normalize(uid) ?: return null
        val plan = synchronized(lock) { planLocked(key) }
        plan.cached?.let { return it }
        if (plan.needWindow) {
            scope.launch {
                delay(BATCH_WINDOW_MS)
                flush()
            }
        }
        return plan.deferred?.await()
    }

    /**
     * 把**别处顺手拿到的**在播状态塞进缓存。
     *
     * 用户空间那条路就靠它省请求：`x/v2/space` 的返回里**本来就带**
     * `live.liveStatus` / `live.roomid`（PiliPlus 也是这么拿的，
     * 见 lib/pages/member/widget/user_info_card.dart:529），页面把这份数据喂进来，
     * 同一个 UP 之后在关注列表/动态里出现时就直接命中缓存，一次多余的请求都不用发。
     */
    fun put(uid: String, liveStatus: Int, roomId: Long) {
        val key = normalize(uid) ?: return
        val status = LiveUserStatus.of(key, liveStatus, roomId)
        synchronized(lock) {
            cache[key] = Entry(status, System.currentTimeMillis() + TTL_MS)
        }
    }

    /** [put] 的重载：直接给已经归一好的领域模型 */
    fun put(status: LiveUserStatus) {
        put(status.uid, if (status.isLive) 1 else 0, status.roomId)
    }

    /** 只清缓存（不动正在飞的请求）。给"下拉刷新"这种"我就是要看最新的"场景用 */
    fun invalidate() {
        synchronized(lock) { cache.clear() }
    }

    // ---------------------------------------------------------------- 内部实现

    /** 必须在 [lock] 内调用：拿到没过期的缓存值，顺手清掉过期的 */
    private fun freshLocked(key: String): LiveUserStatus? {
        val entry = cache[key] ?: return null
        if (entry.expireAt <= System.currentTimeMillis()) {
            cache.remove(key)
            return null
        }
        return entry.status
    }

    /** 必须在 [lock] 内调用：决定"吃缓存 / 搭别人的车 / 自己开一趟" */
    private fun planLocked(key: String): Plan {
        freshLocked(key)?.let { return Plan(it, null, false) }
        pending[key]?.let { return Plan(null, it, false) }
        val created = CompletableDeferred<LiveUserStatus>()
        pending[key] = created
        val needWindow = !windowScheduled
        if (needWindow) windowScheduled = true
        return Plan(null, created, needWindow)
    }

    /**
     * 把当前 [pending] 里的 uid 全部取走，切批发请求，再逐个兑现等待者。
     *
     * ★为什么"取走"要先把 pending 清空：请求期间又进来的 uid 会另起一个新窗口，
     *   不会被这一批的失败连坐；同时也避免同一个 uid 被两条并发请求各写一次缓存。
     */
    private suspend fun flush() {
        val batch: Map<String, CompletableDeferred<LiveUserStatus>> = synchronized(lock) {
            windowScheduled = false
            if (pending.isEmpty()) return
            val taken = HashMap(pending)
            pending.clear()
            taken
        }
        batch.keys.toList().chunked(MAX_UIDS_PER_REQUEST).forEach { chunk ->
            fetchChunk(chunk, batch)
        }
    }

    private suspend fun fetchChunk(
        uids: List<String>,
        waiters: Map<String, CompletableDeferred<LiveUserStatus>>,
    ) {
        var data: Map<String, LiveUserStatusInfo>? = null
        try {
            val res = LiveAPI()
                .liveStatusByUids(uids)
                .awaitCall()
                .json<ResultInfo<Map<String, LiveUserStatusInfo>>>()
            if (res.isSuccess) {
                data = res.data.orEmpty()
            } else {
                miaoLogger().d("在播状态查询失败", "code" to res.code, "message" to res.message)
            }
        } catch (e: Exception) {
            miaoLogger().d("在播状态查询异常", "message" to e.message)
        }
        val result = data
        uids.forEach { uid ->
            val waiter = waiters[uid] ?: return@forEach
            if (result == null) {
                // 失败 → 兑现成"没在播"，并写一条**短 TTL** 的负缓存：
                // ★为什么不干脆不写缓存：那样用户来回滑一下就是一次重试，
                //   网络真出问题时会变成"每滑动一次打一批"，正是最该避免的接口轰炸。
                //   60 秒后自动过期，恢复正常后不会一直瞎着。
                synchronized(lock) {
                    cache[uid] = Entry(
                        LiveUserStatus.off(uid),
                        System.currentTimeMillis() + FAIL_BACKOFF_MS,
                    )
                }
                waiter.complete(LiveUserStatus.off(uid))
                return@forEach
            }
            // ★接口按 uid 返回，但"没房间的 uid"可能整个 key 都不给 ——
            //   这时按"明确没在播"处理并**写缓存**：这是服务端给的确定答案，不是网络故障。
            val info = result[uid]
            val status = if (info == null) {
                LiveUserStatus.off(uid)
            } else {
                LiveUserStatus.of(uid, info.live_status, info.room_id)
            }
            synchronized(lock) {
                cache[uid] = Entry(status, System.currentTimeMillis() + TTL_MS)
            }
            waiter.complete(status)
        }
    }

    private fun normalize(uid: String?): String? {
        val key = uid?.trim().orEmpty()
        return if (key.isEmpty() || key == "0") null else key
    }
}
