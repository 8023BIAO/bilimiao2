package com.a10miaomiao.bilimiao.comm.live.danmaku

import android.content.Context
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * 直播弹幕的**有效渲染参数**（纯数据 + 纯读取，不依赖任何 Android UI）。
 *
 * ## ★定稿：直播弹幕与点播**彻底解耦** —— 每一个参数都只来自 `live_danmaku_*`
 * 用户原话（本轮）："这他妈的相关的直播弹幕屏蔽词也给它去掉了，这个设置也不要了。"
 * 于是三条线一起收口（第 1 条是本轮新增，第 2、3 条是上一轮列的遗留、本轮一起做）：
 * 1. **不做任何关键词过滤**：`filterEnabled` / `filterKeywords` / `buildFilter()` 与
 *    `LiveDanmakuTextFilter` 整个删掉。点播的 `danmaku_filter_enabled` /
 *    `danmaku_filter_keywords` 现在是**零处读写**（键字符串一个字没改，只是不再有人读它）——
 *    用户在点播里把词表写到天上去，直播也一条都不会被拦。
 * 2. **可见性只看 `live_danmaku_enable`**：不再 ∩ 点播那三层开关
 *    （`danmaku_enable` ∩ `{mode}_danmaku_show` ∩ `{mode}_danmaku_r2l_show`）。
 *    点播里把弹幕总开关关了，直播该显示还是显示（用户要的就是"彻底解耦"）。
 * 3. **车道上限只看直播自己的「弹幕显示区域」**：不再读点播的「滚动弹幕最大行数」
 *    （`{mode}_danmaku_r2l_max_line`）。浮层的车道数 = `区域高度 ÷ 车道高`，
 *    所以**区域全屏 = 不限**（`maxLanes` 字段随之整个删除）。
 *
 * ★结构性保证：本类**连 `Preferences` 快照都不再整体接进来**（`from()` 只收 `Live.Values`），
 *   所以"某个字段偷偷回去读点播键"这件事在类型上就写不出来 —— 想加回来必须先改签名。
 *
 * 与点播**再无共用项**。下面这些点播设置对直播**天然不适用**，也不要接：
 * - `danmaku_filter_enabled` / `danmaku_filter_keywords`（屏蔽词）：本轮起直播不读、不拦（见上）。
 * - `danmaku_enable` / `{mode}_danmaku_show` / `{mode}_danmaku_r2l_show`（三层显示开关）：本轮起直播不读。
 * - `{mode}_danmaku_r2l_max_line`（滚动弹幕最大行数）：本轮起直播不读，车道只由显示区域决定。
 * - `danmaku_filter_duplicate`（过滤重复弹幕）：直播客户端**已经无条件去重**
 *   （`LiveDanmakuClient` 用 `uid|文本` + 2s 窗口，见 `DEDUP_WINDOW_MS`），
 *   这个点播开关读数已删（原来也只当"信息位"暴露、从未生效）。
 * - `danmaku_time_sync`（时间同步）：直播弹幕是**流式到达**的，没有时间轴可对齐
 *   （顺带一提：这个键全工程**无人读取**，是个死键）。
 * - `*_danmaku_ft_show` / `*_danmaku_fb_show` / `*_danmaku_special_show`（顶部/底部/高级弹幕显示）
 *   与 `ftMaxLine` / `fbMaxLine`：直播 `DANMU_MSG` **只有滚动这一种类型**（顶部/底部/高级弹幕
 *   是点播弹幕池才有的类型）。接了只会出现"用户关掉顶部弹幕 → 直播整个不显示"这种错位。
 * - `*_danmaku_max_lines`：点播侧就是**死键**（全工程零处读取，读的是 r2l/ft/fb 三个分类型的键）。
 * - `danmaku_sys_font`：点播那个开关切的是 **app 模块 assets 里的 `fonts/danmaku.ttf`**；
 *   直播浮层是 Compose 文本、字体本来就走系统字体，comm/compose 模块也拿不到 app 的 asset。
 * - 弹幕点击跳转（`PlayerSeekBus` / 时间戳跳转）：直播**不能 seek**。
 *
 * ## 单位换算（直播自己的键 → 浮层要的数值）
 * | 项 | 键 | 存的 | 浮层要的 | 换算 |
 * |---|---|---|---|---|
 * | 显示开关 | `live_danmaku_enable` | Boolean（默认 true） | `visible` | 直接用（**唯一**的可见性来源） |
 * | 字号 | `live_danmaku_font_size` | Int，sp 绝对值 10~30 | sp | 直接用（默认 15 = 浮层原来写死的字号） |
 * | 不透明度 | `live_danmaku_opacity` | Int，% 10~100 | Compose alpha 0f~1f | `% ÷ 100` |
 * | 速度 | `live_danmaku_speed` | Float，**倍率** 0.5~2.0（越大越快） | 固定"穿越时长" | `travelDurationMs = 7000ms ÷ 倍率` |
 * | 显示区域 | `live_danmaku_area_percent` | Int，% 25/50/75/100 | 区域比例 + **车道数** | `% ÷ 100`（默认 100 = 全屏；车道数 = 区域高 ÷ 车道高） |
 *
 * ★`visible` 只是"设置里允许显示弹幕"，**不是**最终显隐：播放页还有会话级的底栏「弹幕」按钮
 *   （`LivePlayerActivity.applyDanmakuEnabled`，只改本页状态、**绝不回写任何键**），
 *   浮层宿主再把竖屏列表形态叠上去（`LiveDanmakuOverlayHost`）。本类不掺和那两层。
 *
 * ## 速度的映射（设置页给用户看的就是这张表）
 * | 倍率 | 观感 | 一条弹幕穿过屏幕 |
 * |---|---|---|
 * | 0.5x | 慢 | 14.0 秒 |
 * | 1.0x | 正常（默认） | 7.0 秒 |
 * | 2.0x | 快 | 3.5 秒 |
 * 默认 1.0x 的依据：浮层原来写死 `DANMAKU_TRAVEL_DURATION_MS = 7000ms`，
 * 与"点播那套速度键的默认倍率也是 1.0"完全重合 —— 这是历史上唯一一处"删掉耦合零影响"的巧合，
 * 记在这里是为了以后不要再拿它当"两边该共用"的理由。
 *
 * ★键的登记与默认值：`SettingPreferences.LiveDanmaku*`（键名字符串的唯一真值）
 *   + `SettingConstants.LIVE_DANMAKU_*_DEFAULT`；档位与文案的真值在下面的 [LIVE_SPEED_MIN] 一族。
 *   写入方是直播设置页，读取方是直播弹幕浮层（`LiveDanmakuOverlayHost` 订阅 [watch]
 *   后把本对象一路传到 `LiveDanmakuOverlay`）。
 *
 * ## 线程模型
 * 本类**不可变**，构造完就可以随便跨线程读（历史上点播的过滤链路会在渲染/收流线程上跑；
 * 直播现在连过滤都没有了，但"整对象不可变"这条约束保留：它是这套参数能被安全跨线程传递的前提）。
 */
data class LiveDanmakuSettings(
    /**
     * 直播弹幕该不该显示。★**只来自直播自己的键** `live_danmaku_enable`（默认 true），
     * 不再与点播的三层开关（`danmaku_enable` / `{mode}_danmaku_show` / `{mode}_danmaku_r2l_show`）相与。
     */
    val visible: Boolean,
    /** 字号（sp）。来自直播自己的键 `live_danmaku_font_size`（默认 15sp = 浮层原来写死的字号） */
    val fontSizeSp: Float,
    /** 不透明度 0f~1f（直播自己的键 `live_danmaku_opacity` 的百分比 ÷ 100）。渲染时乘到每条弹幕的颜色 alpha 上 */
    val opacity: Float,
    /** 速度倍率（直播自己的键 `live_danmaku_speed`，0.5~2.0，越大越快）。穿越时长见 [travelDurationMs] */
    val speedScale: Float,
    /**
     * 显示区域占播放区的百分比（25 = 1/4 区 / 50 = 半屏 / 100 = 全屏）。
     *
     * ★它同时也是**车道数的唯一上限来源**（浮层按 `区域高 ÷ 车道高` 算车道）：
     *   区域全屏 = 不限条数。原来那条"点播滚动弹幕最大行数"的外部上限已删除。
     */
    val areaPercent: Int,
) {

    /** 一条弹幕从右边缘跑到完全离开左边所用的毫秒数（直播速度倍率的反比：1.0x → 7000ms） */
    val travelDurationMs: Float
        get() = BASE_TRAVEL_DURATION_MS / speedScale.coerceIn(SPEED_MIN, SPEED_MAX)

    /** 显示区域比例 0.25f~1f（浮层用它乘高度：`有效高度 = heightPx × areaFraction`） */
    val areaFraction: Float
        get() = areaPercent.coerceIn(AREA_PERCENT_MIN, AREA_PERCENT_MAX) / 100f

    companion object {

        // ── 与浮层里写死的常量对齐（浮层是 private 常量，comm 模块看不到，所以在这里复述一份）──
        /** = `LiveDanmakuOverlay.DANMAKU_FONT_SIZE`（15sp）。直播弹幕字号以它为 1.0 倍基准 */
        const val BASE_FONT_SIZE_SP = 15f
        /** = `LiveDanmakuOverlay.DANMAKU_TRAVEL_DURATION_MS`（7000ms 跑完全程） */
        const val BASE_TRAVEL_DURATION_MS = 7000f

        // ── 兜底取值域（防御性 clamp；设置页正常能选到的范围更窄，见下面 LIVE_SPEED_*）──
        const val SPEED_MIN = 0.1f
        const val SPEED_MAX = 2f
        /** 字号兜底范围：太小看不见、太大一屏放不下两条 */
        const val FONT_SIZE_SP_MIN = 8f
        const val FONT_SIZE_SP_MAX = 48f

        // ── 直播自己的速度档位（设置页滑杆的 valueRange / valueSteps 与文案都引用这里，
        //    避免"设置页写的档位"和"链路支持的档位"两处各写一套、改一处忘一处）──
        /** 最慢：0.5x（一条弹幕 14.0 秒穿过屏幕） */
        const val LIVE_SPEED_MIN = 0.5f
        /** 最快：2.0x（一条弹幕 3.5 秒穿过屏幕） */
        const val LIVE_SPEED_MAX = 2f
        /** 一档 0.1x：0.5 / 0.6 / … / 2.0，共 16 档 */
        const val LIVE_SPEED_STEP = 0.1f
        /**
         * 滑杆的离散档数（Compose `Slider(steps = ...)` 的语义 = "两端点**之间**的档位数"）：
         * (2.0 - 0.5) ÷ 0.1 - 1 = **14**。★写错会取不到端点档
         * （和点播速度 0.1~2.0 用 18 是同一个道理）。
         */
        const val LIVE_SPEED_STEPS = 14

        // ── 显示区域（4 档，设置页可以直接拿去做 listPreference / sliderIntPreference）──
        const val AREA_PERCENT_MIN = 25
        const val AREA_PERCENT_MAX = 100
        const val AREA_PERCENT_QUARTER = 25
        const val AREA_PERCENT_HALF = 50
        const val AREA_PERCENT_THREE_QUARTER = 75
        const val AREA_PERCENT_FULL = 100

        /** 设置页展示顺序（1/4 → 全屏）；`areaPercentText()` 负责文案 */
        val AREA_PERCENT_OPTIONS = listOf(
            AREA_PERCENT_QUARTER,
            AREA_PERCENT_HALF,
            AREA_PERCENT_THREE_QUARTER,
            AREA_PERCENT_FULL,
        )

        /**
         * 键名字符串（**契约**，一旦发布就不能改 —— DataStore 里存的就是这个字符串）。
         *
         * ★[KEY_FOLLOW_ON_DEMAND] = **已停用**的「跟随点播弹幕设置」：开关与读取都删掉了，
         *   但键字符串在这里、也在 `SettingPreferences.LiveDanmakuFollowVod` 里**原样保留**
         *   （DataStore 按字符串相等认键，删键 = 老用户已落盘的值变孤儿）。全工程零处读写。
         * ★[KEY_AREA_PERCENT] 仍在用：读取统一走**已登记**的 key 对象
         *   `SettingPreferences.LiveDanmakuAreaPercent`（Preferences.Key 按 name 相等，同一个键），
         *   不在这里再造一份字面量，免得"改了一处 → 设置写了没人读"。
         *   ★本类现在连这个 key 对象都不直接碰了：区域值随 `Live.Values.danmakuAreaPercent` 一起
         *   进来（`SettingPreferences.Live.of()` 里读的就是上面那个 key 对象）。
         */
        const val KEY_FOLLOW_ON_DEMAND = "live_danmaku_follow_vod"
        const val KEY_AREA_PERCENT = "live_danmaku_area_percent"

        /** 显示区域的展示文案（纯字符串，不依赖 Compose，设置页/浮层都能用） */
        fun areaPercentText(percent: Int): String = when (percent) {
            AREA_PERCENT_QUARTER -> "1/4 屏"
            AREA_PERCENT_HALF -> "半屏"
            AREA_PERCENT_THREE_QUARTER -> "3/4 屏"
            AREA_PERCENT_FULL -> "全屏"
            else -> "$percent%"
        }

        /**
         * 速度的展示文案（设置页 `valueText`）：`0.5x` / `1.0x` / `2.0x`。
         * 不用 `"%.1f".format()` 是因为它跟着系统 Locale 走（有些语言会输出 "1,0x"）；
         * 档位本来就是 0.1 的整数倍，先 round 再除就够，且结果与语言无关。
         */
        fun speedText(scale: Float): String = "${(scale * 10).roundToInt() / 10f}x"

        /** 速度的档位说明（设置页 `summary`）：慢 / 正常 / 快 */
        fun speedTierText(scale: Float): String = when {
            scale <= 0.75f -> "慢"
            scale < 1.25f -> "正常"
            else -> "快"
        }

        /**
         * 速度设置项的完整说明（设置页 `summary` 用）。
         *
         * 秒数用的是**和浮层同一个公式**（`travelDurationMs = 7000ms ÷ 倍率`），
         * 所以设置页写的"约几秒"和画面上真正跑出来的速度永远是同一个数 ——
         * 这就是"设置项与浮层对齐"的落点（浮层那边只认 `speedScale`）。
         * 例：1.0x → "正常（1.0x）：一条弹幕约 7.0 秒穿过屏幕（0.5x 最慢、1.0x 默认、2.0x 最快）"。
         */
        fun speedSummaryText(scale: Float): String {
            val seconds = (BASE_TRAVEL_DURATION_MS / scale.coerceIn(SPEED_MIN, SPEED_MAX) / 100f)
                .roundToInt() / 10f
            return "${speedTierText(scale)}（${speedText(scale)}）：一条弹幕约 ${seconds} 秒穿过屏幕（0.5x 最慢、1.0x 默认、2.0x 最快）"
        }

        /**
         * 把一份**直播自己的**设置快照翻译成直播弹幕参数。**纯函数**（不碰 DataStore、不碰 Context），
         * 方便单测。
         *
         * @param live 直播自己的设置（`SettingPreferences.Live.of(prefs)` / `AppStore.state.live`）：
         *             **本类需要的全部字段都在它里面**，没有一个来自点播。
         *             null = 进程刚起、内存快照还没就绪 → 全部走默认值（默认就是"弹幕开、15sp、
         *             100% 不透明、1.0x、全屏"）。
         *
         * ★签名刻意**不收 `Preferences`**（上一版收）：直播弹幕与点播彻底解耦之后，
         *   这里再没有任何一个点播键要读；不收快照 = 结构上堵死"回去读点播键"这条路。
         */
        fun from(live: SettingPreferences.Live.Values? = null): LiveDanmakuSettings {
            // ① 可见性：**只有直播自己这一个开关**（不再 ∩ 点播的三层显示开关）。
            val visible = live?.danmakuEnable ?: SettingConstants.LIVE_DANMAKU_ENABLE_DEFAULT

            // ② 样式：只有直播自己这一套（`live_danmaku_*`）。
            //    字号：Int，sp 绝对值（默认 15 = 浮层原来写死的 [BASE_FONT_SIZE_SP]）
            val fontSizeSp = (live?.danmakuFontSize ?: SettingConstants.LIVE_DANMAKU_FONT_SIZE_DEFAULT)
                .toFloat()
                .coerceIn(FONT_SIZE_SP_MIN, FONT_SIZE_SP_MAX)
            //    不透明度：Int，百分比 0~100 → Compose 的 0f~1f
            val opacity = ((live?.danmakuOpacity ?: SettingConstants.LIVE_DANMAKU_OPACITY_DEFAULT)
                .coerceIn(0, 100)) / 100f
            //    速度：Float，倍率 0.5~2.0（默认 1.0）→ 浮层 `travelDurationMs = 7000ms ÷ 倍率`
            val speedScale = (live?.danmakuSpeed ?: SettingConstants.LIVE_DANMAKU_SPEED_DEFAULT)
                .coerceIn(SPEED_MIN, SPEED_MAX)

            // ③ 显示区域：直播自己的键 `live_danmaku_area_percent`，缺省 100 = 全屏不裁剪。
            //    ★车道上限不再另取（原来读点播 `{mode}_danmaku_r2l_max_line`，已删）：
            //      浮层的车道数 = 区域高度 ÷ 车道高，全屏就是整屏。
            val areaPercent = (live?.danmakuAreaPercent ?: SettingConstants.LIVE_DANMAKU_AREA_PERCENT_DEFAULT)
                .coerceIn(AREA_PERCENT_MIN, AREA_PERCENT_MAX)

            return LiveDanmakuSettings(
                visible = visible,
                fontSizeSp = fontSizeSp,
                opacity = opacity,
                speedScale = speedScale,
                areaPercent = areaPercent,
            )
        }

        /**
         * 读一次（挂起）。DataStore 只有挂起读，所以这里**不是**普通函数 ——
         * 想给主线程（Activity 生命周期回调）用请走 [loadCached]。
         */
        suspend fun load(context: Context): LiveDanmakuSettings {
            val prefs = SettingPreferences.run { context.dataStore.data.first() }
            // 用同一份快照翻译直播自己的设置，避免"两次读之间设置被改"导致前后不一致
            return from(SettingPreferences.Live.of(prefs))
        }

        /**
         * 主线程 O(1) 取值：走 `SettingPreferences` 那份**进程级内存快照**
         * （`BilimiaoCommApp.onCreate` 里 `warmUpCache` 已经热起来了）。
         * 进程刚起、快照还没就绪时返回的就是各字段默认值 —— 不阻塞主线程，也不会读到半截状态。
         */
        fun loadCached(): LiveDanmakuSettings {
            val prefs = SettingPreferences.cachedPreferencesOrNull()
            return from(SettingPreferences.Live.of(prefs))
        }

        /**
         * 跟着设置变（Compose 里 `collectAsStateWithLifecycle`）。
         *
         * 为什么必须有它：用户在直播设置页里拖一下透明度，浮层**当场**就该变 ——
         * 点播那边是靠 `activity.dataStore.data.collect { initDanmakuContext(it) }` 做到的
         * （`PlayerController.initPlayerSetting`），直播同理。
         *
         * ★不再有 `mode` 参数（上一版有）：那套"全屏 / 画中画"的点播模式只决定读哪一套**点播**键，
         *   而直播现在一个点播键都不读，PiP 下的差异只剩"窗口大小"这种纯几何因素，
         *   由浮层按实际尺寸自适应（车道数、区域高度都是量出来的）。
         */
        fun watch(context: Context): Flow<LiveDanmakuSettings> {
            return SettingPreferences.run { context.dataStore.data }
                .map { prefs -> from(SettingPreferences.Live.of(prefs)) }
        }
    }
}
