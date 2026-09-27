package com.a10miaomiao.bilimiao.comm.live.danmaku

import android.os.SystemClock
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.entity.user.UserInfo
import com.a10miaomiao.bilimiao.comm.miao.MiaoJson
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **"我自己叫什么"** —— 只服务一件事：自己发的弹幕在列表里显示**昵称**（与其它人同格式
 * `用户名：内容`），而不是"我"或者 uid。
 *
 * ## 为什么需要它（用户原话）
 * > "我去上一个（弹幕）回来，我的 ID 还是我吗？还是说显示的是我的昵称全名？"
 *
 * 现状：`LiveDanmakuClient.echoSentDanmaku` 的本地回显把昵称写死成 **"我"**
 * （那是"发出去立刻有反馈"的乐观回显，当时手里没有昵称）。而竞品 PiliPlus
 * **没有本地回显**：它只等服务的 WS 回声，那条 `DANMU_MSG` 里带着真实昵称
 * （`PiliPlus/lib/models_new/live/live_danmaku/danmaku_msg.dart:45` 的 `user['base']['name']`），
 * 所以它列表里永远是昵称全名。我们既要保留"立刻可见"的回显，又要把名字写对，
 * 于是需要一个**本地就能拿到昵称**的来源。
 *
 * ## 三级来源（从"一定准确"到"能拿到就用"，全部失败才退回 "我"）
 * 1. [learn]：**服务端回声 / 历史弹幕里我们自己的那条**（uid 相同）—— 最权威、零成本；
 * 2. [resolve] 的本机缓存：`filesDir/user.data`（`UserStore` 登录后写的 `UserInfo` JSON，
 *    含 `mid` + `name`）—— 一次小文件读，不联网；
 * 3. [resolve] 的 `nav` 接口：`https://api.bilibili.com/x/web-interface/nav` 的 `data.uname`
 *    （Web 登录态下才有；工程里 `WbiSigner` 本来就在打这个接口取 WBI key，是熟路）。
 *
 * ## 线程与成本
 * - [current] 只读一个 `@Volatile`，O(1)，可以在 `sendDanmaku` 的回显路径上直接调；
 * - [resolve] 是挂起函数，**每个进程/每 30 分钟最多一次真实联网**，且**从不抛异常**
 *   （网络失败 → null，调用方按 "我" 兜底，绝不因为"查不到昵称"把发送流程搞挂）。
 */
object LiveSelfNickname {

    /** 已知的**自己**的昵称（null = 还不知道） */
    @Volatile
    private var nickname: String? = null

    /** 已知的**自己**的 uid（0 = 还不知道；用来防止把别人的昵称学成自己的） */
    @Volatile
    private var mid: Long = 0L

    /** 上一次成功解析的时刻（[SystemClock.elapsedRealtime]，单调时钟） */
    @Volatile
    private var resolvedAtMs = 0L

    /** 解析在飞（避免多个直播间/多次调用同时打 nav） */
    private val resolving = AtomicBoolean(false)

    /** 当前已知的昵称；不知道时 null（调用方自己决定兜底文案） */
    fun current(): String? = nickname?.takeIf { it.isNotBlank() }

    /** 当前已知的自己的 uid（0 = 不知道） */
    fun currentMid(): Long = mid

    /**
     * 从**外部证据**里学一次自己的昵称（服务端推回来的 `DANMU_MSG` / 历史弹幕里自己那条）。
     *
     * 只做"记下来"，不做任何网络与 IO —— 所以能在解码协程里随便调。
     *
     * @param mid 证据里的 uid
     * @param uname 证据里的昵称
     */
    fun learn(mid: Long, uname: String) {
        if (uname.isBlank()) return
        val known = this.mid
        // 已经知道自己的 mid 且和证据对不上 → 这不是"我"，别把自己叫成别人
        if (known != 0L && mid != 0L && known != mid) return
        nickname = uname.trim()
        if (mid != 0L) this.mid = mid
        resolvedAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * 解析自己的昵称（幂等、可重复调用、**不抛异常**）。
     *
     * 命中缓存（[NICKNAME_TTL_MS] 内已解析过）就直接返回；否则按
     * "本机 `user.data` → `nav` 接口"的顺序各试一次。
     *
     * @return 昵称；三级来源都没结果时 null（调用方退回 "我"）
     */
    suspend fun resolve(): String? {
        val cached = current()
        if (cached != null && SystemClock.elapsedRealtime() - resolvedAtMs < NICKNAME_TTL_MS) {
            return cached
        }
        // 已经有一次在飞：不排队、不等待，直接用当前已知值（没有就先返回 null）
        if (!resolving.compareAndSet(false, true)) return current()
        return try {
            val fromFile = readFromUserFile()
            if (fromFile != null) {
                learn(fromFile.first, fromFile.second)
                LiveDanmakuTrace.note("自己昵称取自本机 user.data：${fromFile.second}")
                return current()
            }
            val fromNav = fetchFromNav()
            if (fromNav != null) {
                learn(fromNav.first, fromNav.second)
                LiveDanmakuTrace.note("自己昵称取自 nav：${fromNav.second}")
                return current()
            }
            LiveDanmakuTrace.note("自己昵称暂时取不到（未登录 / 缓存缺失）→ 自己发的弹幕先显示「我」")
            null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // 兜底：查昵称失败绝不能影响发送/连接
            null
        } finally {
            resolving.set(false)
        }
    }

    /** 登录/登出后可以调它把缓存丢掉（工程目前没有调用点，留给后续账号切换场景） */
    fun invalidate() {
        nickname = null
        mid = 0L
        resolvedAtMs = 0L
    }

    /**
     * 来源②：`filesDir/user.data`。
     *
     * 这是 `UserStore`（`bilimiao-comm/.../comm/store/UserStore.kt:76-96`）登录后写的
     * `UserInfo` JSON（`mid` + `name`），本 App 一启动 `Store.onCreate()` 就会
     * `userStore.init()` → 读它 / 联网刷新它，所以登录用户这里基本一定有值。
     * ★只读文件、不碰 DI、不碰 ViewModel —— 弹幕客户端在 comm 模块，拿不到 Compose 那套 DI。
     */
    private suspend fun readFromUserFile(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val app = BilimiaoCommApp.commApp.app
            val file = File(app.filesDir, USER_INFO_FILE)
            if (!file.exists()) return@runCatching null
            val info = MiaoJson.fromJson<UserInfo>(file.readText())
            val name = info.name.takeIf { it.isNotBlank() } ?: return@runCatching null
            info.mid to name
        }.getOrNull()
    }

    /**
     * 来源③：`/x/web-interface/nav`（Web 登录态下返回 `data.uname` / `data.mid`）。
     *
     * ★只在"确实有 web 登录 Cookie"时才打这一枪：没登录时它必然 `code=-101`，
     *   白花一次请求（还要算进风控计数），所以先看一眼 `SESSDATA` / `DedeUserID`。
     * ★`nav` 在 MiaoHttp 里被显式排除在 WBI 签名之外（`MiaoHttp.kt:93`），不会因为
     *   "自动签名"把请求形态改掉。
     */
    private suspend fun fetchFromNav(): Pair<Long, String>? {
        val hasWebLogin = !MiaoHttp.sessDataToken().isNullOrBlank() ||
            !MiaoHttp.cookieValue("DedeUserID").isNullOrBlank()
        if (!hasWebLogin) return null
        // ★切 IO：`awaitCall()` 本身是 enqueue（不阻塞），但 buildRequest 里会读 CookieManager
        //   （一次跨进程查询）—— 调用方在 Compose 的主线程上，这点活儿不值得赌在"它很快"上。
        val res = withContext(Dispatchers.IO) {
            MiaoHttp.request {
                isWebApi = true
                url = NAV_URL
            }.awaitCall().json<NavResponse>()
        }
        if (res.code != 0 || res.data?.isLogin != true) return null
        val name = res.data.uname.takeIf { it.isNotBlank() } ?: return null
        return res.data.mid to name
    }

    /** 昵称缓存有效期：30 分钟内不再联网（改名/换号由 [invalidate] 或 [learn] 覆盖） */
    private const val NICKNAME_TTL_MS = 30 * 60 * 1000L

    /** `UserStore` 写的那个文件名（`UserStore.kt:77`） */
    private const val USER_INFO_FILE = "user.data"

    private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
}

/**
 * `nav` 的返回壳（只取"我是不是登录了、我叫什么、我 uid 是多少"三个字段）。
 * 同样每个字段都给默认值：nav 的字段极多且会变，少一个不该让整次解析失败。
 */
@Serializable
private data class NavResponse(
    val code: Int = -1,
    val data: NavData? = null,
)

@Serializable
private data class NavData(
    val isLogin: Boolean = false,
    val uname: String = "",
    val mid: Long = 0L,
)
