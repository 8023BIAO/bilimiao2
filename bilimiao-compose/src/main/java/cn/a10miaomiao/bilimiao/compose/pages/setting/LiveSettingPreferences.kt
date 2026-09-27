package cn.a10miaomiao.bilimiao.compose.pages.setting

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.ui.text.AnnotatedString
import cn.a10miaomiao.bilimiao.compose.components.preference.sliderIntPreference
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.live.LiveAPI
import com.a10miaomiao.bilimiao.comm.live.danmaku.LiveDanmakuSettings
import me.zhanghai.compose.preference.ListPreferenceType
import me.zhanghai.compose.preference.listPreference
import me.zhanghai.compose.preference.preferenceCategory
import me.zhanghai.compose.preference.sliderPreference
import me.zhanghai.compose.preference.switchPreference

/**
 * ★★第十四批：**直播设置项的唯一一份定义**（原本内联在 `LiveSettingPage.kt` 里）。
 *
 * ## 为什么要抽出来
 * 用户要求"底栏加「设置」按钮 → 弹出直播间的设置选项、设置页"，并且点名要用**他那套
 * 「底栏筛选弹窗」（`HomeLiveFilterSheet` → `AutoSheetDialog`）**。于是现在有**两个**入口要展示
 * 同一批设置项：
 * ```
 * ① 设置 → 直播设置            LiveSettingPage.kt   （原入口，行为一个字不改）
 * ② 直播播放页底栏「设置」弹窗    pages/live/LiveSettingSheet.kt（★新增）
 * ```
 * 两处各抄一份的后果是**必然漂移**（键名、默认值、档位、文案随便哪一处改漏一次，
 * 用户就会看到"设置页是 15sp、弹窗里是 20sp"这种自相矛盾）。所以项本身只留这一份
 * （方案 A：抽成可复用 composable），[LiveSettingPage] 与播放页弹窗都只是**调用**它。
 *
 * ★★本轮（2026-09-26 用户拍板"弹窗瘦身"）：两个入口不再展示**同一批**项，而是同一份定义的
 *   **两个子集**（见 [liveDanmakuSettingPreferenceItems] 的 KDoc）：
 * ```
 * liveSettingPreferenceItems()        设置页：播放 4 + 弹幕 4 + 直播列表 1 = 9 项（一个不少）
 * liveDanmakuSettingPreferenceItems() 播放页弹窗：只有弹幕 4 项
 * ```
 *   ★弹幕那一组仍然**只有一份实现**（[liveDanmakuPreferenceItems]），两个入口都调它；
 *     播放类 4 项**没删**，只是不在弹窗里出现 —— 设置页照旧全部展示。
 *
 * ★**键与默认值仍然只在 `SettingPreferences` / `SettingConstants` 里定义**：本文件一个字符串键、
 *   一个默认值都没有新写，全部引用现成常量（`SettingPreferences.xxx.name` +
 *   `SettingConstants.xxx_DEFAULT`），读写口仍然只有 `ProvidePreferenceLocals` 那一套。
 * ★本文件**只搬位置**：每一项的 key / 默认值 / 档位 / 文案 / summary / 顺序与搬之前逐字相同。
 * ★它是 `LazyListScope` 的扩展（不是包一个 `LazyColumn`）：两个入口的滚动容器、内边距、
 *   上下留白各不相同（设置页要留状态栏/底部 AppBar，弹窗要 `weight(1f)` 吃满并给底部按钮让位），
 *   共同的部分只是"有哪些项"。
 */
internal fun LazyListScope.liveSettingPreferenceItems() {
    // ★顺序 = 设置页上的顺序，一个字节都没动（播放 → 弹幕 → 直播列表）
    livePlayPreferenceItems()
    liveDanmakuPreferenceItems()
    liveBrowsePreferenceItems()
}

/**
 * ★★本轮（2026-09-26）：**播放页底栏「设置」弹窗**只要「直播弹幕」这一组（用户拍板的"减法"）。
 *
 * 用户原话（本轮）：
 * > "播放页「设置」弹窗瘦身：只保留弹幕相关的项（字号 / 不透明度 / 速度 / 显示区域），
 * >  播放类 4 项（默认画质 / 默认线路策略 / 自动重连 / 自动旋转）从弹窗移除 ——
 * >  它们仍在「设置 → 直播设置」页，设置页一个项都不能少。"
 *
 * 于是两个入口是**同一份项定义的两个子集**（不是两份拷贝）：
 * ```
 * liveSettingPreferenceItems()          → 设置页：播放 4 + 弹幕 4 + 直播列表 1（= 9 项，一个不少）
 * liveDanmakuSettingPreferenceItems()   → 播放页弹窗：弹幕 4 项（唯一实现 [liveDanmakuPreferenceItems]）
 * ```
 * ★为什么播放类 4 项从弹窗移除：它们都是"**进房前/播放策略**"类的设置（默认画质、默认线路策略、
 *   自动重连、自动旋转），在弹窗里改完当场也只对**下一次**起播/恢复生效，放在"直播间里随手调"的
 *   弹窗里既占地方又容易让人以为"改了没反应"；而弹幕那 4 项是**看直播时随时想调**的东西
 *   （字号/不透明度/速度/显示区域），改完当场生效 —— 这正是弹窗该干的事。
 * ★播放类 4 项**一个都没少**：它们仍在设置页（[livePlayPreferenceItems]），播放页也照旧读同一批键。
 */
internal fun LazyListScope.liveDanmakuSettingPreferenceItems() {
    liveDanmakuPreferenceItems()
}

// ===== 播放 =====
/**
 * 「直播播放」那一组（4 项：默认画质 / 默认线路策略 / 自动重连 / 自动旋转）。
 *
 * ★★本轮起它**只在「设置 → 直播设置」页出现**：播放页底栏「设置」弹窗按用户要求瘦身成
 *   只剩弹幕 4 项（见 [liveDanmakuSettingPreferenceItems]），但这四项**没有删**。
 */
private fun LazyListScope.livePlayPreferenceItems() {

    // ★本组原来有 7 项，上一轮按"别处有更顺手的入口就别在这儿重复"删掉 3 项：
    //   「后台继续直播」(`live_background_play`)、「退后台自动进小窗」(`live_pip_on_background`)、
    //   「双击暂停」(`live_double_tap_pause`) —— 三个键与默认值、读取逻辑一个字没动，
    //   本页只是不再显示（详见 LiveSettingPage 文件头 KDoc）。
    //   ★本轮更正一句旧注释：它们**不在**播放页底栏「设置」弹窗里（那个弹窗现在只有弹幕 4 项），
    //     当前全工程**没有**这三项的 UI 入口 —— 值仍被播放页读取（后台继续直播 / 退后台进小窗 /
    //     双击暂停），要改只能改默认值常量或另开入口。
    preferenceCategory(
        key = "live_play",
        title = {
            Text("直播播放")
        }
    )
    listPreference(
        key = SettingPreferences.LiveDefaultQuality.name,
        type = ListPreferenceType.DROPDOWN_MENU,
        title = {
            Text("默认画质")
        },
        // listPreference 的 summary 回调给的就是"当前值"，用它拼最准（不会滞后）
        summary = { value ->
            Text("进直播间时请求的画质（当前：${LiveSettingOptions.qualityName(value)}）。最高/最低可用 = 由播放器按房间实际支持的清晰度挑")
        },
        defaultValue = SettingConstants.LIVE_DEFAULT_QUALITY_DEFAULT,
        values = LiveSettingOptions.qualitySelectionList,
        valueToText = LiveSettingOptions::qualityName,
    )
    listPreference(
        key = SettingPreferences.LiveLinePolicy.name,
        type = ListPreferenceType.DROPDOWN_MENU,
        title = {
            Text("默认线路策略")
        },
        summary = { value ->
            Text("直播流有多条 CDN 线路（当前：${LiveSettingOptions.linePolicyName(value)}）")
        },
        defaultValue = SettingConstants.LIVE_LINE_POLICY_DEFAULT,
        values = LiveSettingOptions.linePolicySelectionList,
        valueToText = LiveSettingOptions::linePolicyName,
    )
    switchPreference(
        key = SettingPreferences.LiveAutoReconnect.name,
        title = {
            Text("自动重连")
        },
        summary = {
            Text("断流 / 取流失败时自动重试并换线路（默认开；关掉后失败只会提示，需要手动点重试）")
        },
        defaultValue = SettingConstants.LIVE_AUTO_RECONNECT_DEFAULT,
    )
    // 自动旋转（键 `live_auto_rotate`，默认开）。
    // 为什么要有这一项：直播用户一半时间在"竖屏刷列表 → 点进直播间"，进房后又是横屏看更爽，
    //   而本页/播放页原来的方向逻辑是"点一次旋转按钮锁一个方向"，想跟随重力反而没入口。
    // 为什么默认开：现状（Manifest 没写 screenOrientation + configChanges 已声明 orientation）
    //   本来就是跟随系统转屏，默认值必须对齐现状（详见 LiveSettingPage 文件头 KDoc 的交接段）。
    // 消费方是播放页（A 路）：`SettingPreferences.liveSettings().autoRotate` → `requestedOrientation`。
    switchPreference(
        key = SettingPreferences.LiveAutoRotate.name,
        title = {
            Text("自动旋转")
        },
        summary = {
            if (it) {
                // ★2026-09-26 语义更新（与直播页「旋转」按钮对齐）：开着的时候点一次旋转 =
                //   先切到另一个方向停住，**手机再转一下就继续跟随**（不再永久锁死 ✗）。
                Text("竖着拿就竖屏看，横过来自动全屏（默认开）。底栏「旋转」可临时切一次，之后仍跟随手机方向")
            } else {
                Text("不跟随重力感应；屏幕方向由播放页底栏的旋转按钮手动切换（此时它是唯一的方向开关）")
            }
        },
        defaultValue = SettingConstants.LIVE_AUTO_ROTATE_DEFAULT,
    )
}

// ===== 弹幕 =====
/**
 * 「直播弹幕」那一组（4 项：弹幕字号 / 弹幕不透明度 / 弹幕速度 / 弹幕显示区域）。
 *
 * ★★这一组是**两个入口共用**的那一份：设置页（[liveSettingPreferenceItems]）与
 *   播放页底栏「设置」弹窗（[liveDanmakuSettingPreferenceItems]）都调它 ——
 *   所以"弹窗里改"与"设置页里改"永远是同一批键、同一批档位、同一批文案。
 */
private fun LazyListScope.liveDanmakuPreferenceItems() {
    // ★这一组就是**直播自己的那一套**（用户原话："就让直播的那个弹幕成另一套吧"）：
    //   字号、不透明度、速度、显示区域四项全部写 `live_danmaku_*` 键，只对直播生效，
    //   读取方都是 `LiveDanmakuSettings.from()`（→ 直播弹幕浮层）。
    //   ★组内**没有任何点播项**：屏蔽词共用的那行跳转本轮已删（直播不做关键词过滤），
    //   字号/不透明度/速度/显示区域四项全部只写、只读 `live_danmaku_*`（详见 LiveSettingPage 文件头 KDoc）。
    //   组内原先第一项是「显示弹幕」(`live_danmaku_enable`)，上一轮已整项删显示：
    //   播放页底栏那颗「弹幕」按钮当场开关并且写回同一个键，能力一点没少。
    preferenceCategory(
        key = "live_danmaku",
        title = {
            Text("直播弹幕")
        }
    )
    sliderIntPreference(
        key = SettingPreferences.LiveDanmakuFontSize.name,
        title = {
            Text("弹幕字号")
        },
        // 10..30 共 21 个整数，中间还有 19 个 → steps 必须写 19（写错会取不到档/重复档）
        valueRange = 10..30,
        valueSteps = 19,
        defaultValue = SettingConstants.LIVE_DANMAKU_FONT_SIZE_DEFAULT,
        valueText = {
            Text("${it}sp")
        },
        summary = {
            Text("只对直播生效。默认 ${SettingConstants.LIVE_DANMAKU_FONT_SIZE_DEFAULT}sp（和原来一样大）")
        },
    )
    sliderIntPreference(
        key = SettingPreferences.LiveDanmakuOpacity.name,
        title = {
            Text("弹幕不透明度")
        },
        // 10..100 共 91 个整数 → steps = 89。下限不给 0：全透明等于"弹幕开了但看不见"，
        // 那种情况应该直接关掉播放页底栏那颗「弹幕」按钮，而不是让用户以为弹幕坏了
        valueRange = 10..100,
        valueSteps = 89,
        defaultValue = SettingConstants.LIVE_DANMAKU_OPACITY_DEFAULT,
        valueText = {
            Text("$it%")
        },
        summary = {
            Text("只对直播生效。默认 100% = 完全不透明（和原来一样）")
        },
    )
    // ★本轮新增：弹幕速度（键 `live_danmaku_speed`，Float 倍率 0.5~2.0，默认 1.0）。
    // 为什么用倍率滑杆而不是"慢/正常/快"三选一：三档之间差得太远（0.5 / 1.0 / 2.0），
    //   热门房想"稍微慢一点"就没有档位了；倍率滑杆 + 文案里写清"慢/正常/快"和秒数，
    //   既可微调又一眼能懂。档位/文案全取 `LiveDanmakuSettings` 里的真值，不在这儿复述数字。
    // 和浮层的对接：`speedScale` 一路传到 `settings.travelDurationMs = 7000ms ÷ speedScale`，
    //   所以这里显示"约几秒穿过屏幕"用的是同一个公式（0.5x=14.0s / 1.0x=7.0s / 2.0x=3.5s）。
    sliderPreference(
        key = SettingPreferences.LiveDanmakuSpeed.name,
        title = {
            Text("弹幕速度")
        },
        defaultValue = SettingConstants.LIVE_DANMAKU_SPEED_DEFAULT,
        valueRange = LiveDanmakuSettings.LIVE_SPEED_MIN..LiveDanmakuSettings.LIVE_SPEED_MAX,
        valueSteps = LiveDanmakuSettings.LIVE_SPEED_STEPS,
        valueText = {
            Text(LiveDanmakuSettings.speedText(it))
        },
        summary = {
            Text(LiveDanmakuSettings.speedSummaryText(it) + "，只对直播生效")
        },
    )
    // 显示区域：同样是直播自己那套（点播那边只有"最大行数"这个行数模型，没有区域比例语义）。
    // 档位与文案直接用弹幕链路的 LiveDanmakuSettings，免得设置页写的档位和浮层支持的对不上
    listPreference(
        key = SettingPreferences.LiveDanmakuAreaPercent.name,
        type = ListPreferenceType.DROPDOWN_MENU,
        title = {
            Text("弹幕显示区域")
        },
        summary = { value ->
            Text("弹幕最多占播放区多大（当前：${LiveDanmakuSettings.areaPercentText(value)}），默认全屏。只对直播生效")
        },
        defaultValue = SettingConstants.LIVE_DANMAKU_AREA_PERCENT_DEFAULT,
        values = LiveDanmakuSettings.AREA_PERCENT_OPTIONS,
        valueToText = { value ->
            AnnotatedString(LiveDanmakuSettings.areaPercentText(value))
        },
    )
    // ★（本轮删除）这里原来还有一行「点播弹幕设置（屏蔽词）」的跳转 + 说明文案
    //   （key = "live_danmaku_goto_vod"，2026-09-26 删）。用户原话：
    //   "点播弹幕设置(屏蔽词) 屏蔽词直播和点播共用这一份：在【点播弹幕设置】里改……
    //    你为什么还要在直播里面设置呢？这他妈的相关的直播弹幕屏蔽词也给它去掉了，这个设置也不要了。"
    //   → 直播弹幕**不做任何关键词过滤**，那行说明里描述的"共用一份词表"已不成立，
    //     留着只会教用户去点播里改一个对直播毫无作用的开关。
    //     「直播弹幕」这一组现在**每一项都是直播自己的**（`live_danmaku_*`），组内零点播耦合。
}

// ===== 浏览页 =====
/**
 * 「直播列表」那一组（1 项：每行卡片数）—— **只在「设置 → 直播设置」页出现**
 * （播放页弹窗按用户要求只剩弹幕 4 项）。
 */
private fun LazyListScope.liveBrowsePreferenceItems() {
    // ★本组原来的「默认排序」(`live_sort_type`) 上一轮整项移出设置页：用户要求挪到首页
    //   直播 Tab 的底栏筛选弹窗里（在列表上调比"藏进设置里"顺手得多）。键与默认值
    //   （"online"）/ 读取口 `Values.sortTypeOrOnline` 一个字没动，写入方由 B 路在筛选弹窗里接
    //   —— 详情见 LiveSettingPage 文件头 KDoc 的删项表。所以这里只剩「每行卡片数」一项。
    preferenceCategory(
        key = "live_browse",
        title = {
            Text("直播列表")
        }
    )
    sliderIntPreference(
        key = SettingPreferences.LiveGridSpan.name,
        title = {
            Text("每行卡片数")
        },
        valueRange = 0..5,
        // 0..5 共 7 个整数，中间还有 4 个 → steps 必须是 4（同「番剧/影视设置」那一项）
        valueSteps = 4,
        defaultValue = SettingConstants.LIVE_GRID_SPAN_DEFAULT,
        valueText = {
            Text(if (it == SettingConstants.LIVE_GRID_SPAN_AUTO) "自适应" else "${it}列")
        },
        summary = {
            Text("首页「直播」Tab 的卡片列数。自适应 = 按屏幕宽度铺（手机 1 列，平板/横屏自动多列）")
        },
    )
}

/**
 * 直播设置的"值 ↔ 名字"翻译表（默认画质 / 默认线路策略）。
 *
 * ★搬出来之前它是 `LiveSettingPageViewModel` 的私有属性（只服务于设置页 UI 的翻译表，
 *   不参与任何存储/请求）；现在两个入口都要用它，所以放到 `internal object` 里**只留一份**。
 *   `LiveSettingPageViewModel` 因此没有别的职责了 —— 那一层已随本次抽取一起删掉
 *   （它原本只是"装这两张表"的容器，没有 init/请求/状态）。
 *
 * ★值全部引用现成常量（`SettingConstants.LIVE_QUALITY_*` / `LiveAPI.QUALITY_*` /
 *   `SettingConstants.LIVE_LINE_POLICY_*`），一个数字都没有在这里重抄。
 *   实测（方案 §2）：未登录时 `current_qn` 恒为 250（超清），请求 10000 也只会回 250 ——
 *   所以文案里必须如实写清"要登录/大会员"，否则用户会以为是我们画质差。
 */
internal object LiveSettingOptions {

    /**
     * 默认画质候选表。
     *
     * 顺序 = 从"最想要"到"最省流"，前两项是**策略值**（负数），后面是接口 qn 原值：
     *  - 具体 qn 的五个（原画/蓝光/超清/高清/流畅）直接用 `LiveAPI` 里已有的常量，不再抄一遍数字；
     *  - 杜比/4K/2K 三个 `LiveAPI` 里没有（它的 companion 只到原画），常量补在 `SettingConstants`。
     */
    private val qualitySelection = linkedMapOf(
        SettingConstants.LIVE_QUALITY_HIGHEST to AnnotatedString("最高可用（默认）"),
        SettingConstants.LIVE_QUALITY_LOWEST to AnnotatedString("最低可用（省流）"),
        SettingConstants.LIVE_QUALITY_DOLBY to AnnotatedString("杜比（需大会员）"),
        SettingConstants.LIVE_QUALITY_4K to AnnotatedString("4K（需大会员）"),
        SettingConstants.LIVE_QUALITY_2K to AnnotatedString("2K"),
        LiveAPI.QUALITY_ORIGIN to AnnotatedString("原画（需登录）"),
        LiveAPI.QUALITY_BLURAY to AnnotatedString("蓝光（需登录）"),
        LiveAPI.QUALITY_SUPER to AnnotatedString("超清"),
        LiveAPI.QUALITY_HIGH to AnnotatedString("高清"),
        LiveAPI.QUALITY_SMOOTH to AnnotatedString("流畅"),
    )

    fun qualityName(value: Int): AnnotatedString =
        qualitySelection[value] ?: AnnotatedString("qn $value")

    val qualitySelectionList = qualitySelection.keys.toList()

    /** 线路策略：0 = 自动换线（默认，= 播放器现有行为），1 = 固定第一条 */
    private val linePolicySelection = linkedMapOf(
        SettingConstants.LIVE_LINE_POLICY_AUTO to AnnotatedString("自动换线（推荐）"),
        SettingConstants.LIVE_LINE_POLICY_FIRST to AnnotatedString("固定第一条线路"),
    )

    fun linePolicyName(value: Int): AnnotatedString =
        linePolicySelection[value] ?: AnnotatedString("自动换线（推荐）")

    val linePolicySelectionList = linePolicySelection.keys.toList()

    // 原先这里还有一张「默认排序」表（sortName / sortSelectionList）。★上一轮整项移出设置页
    // （用户要求挪到首页直播 Tab 的底栏筛选弹窗，键 `live_sort_type` 与默认值一个字没动），
    // 所以这张只服务于设置页 UI 的翻译表跟着一起删掉 —— 留着就是"UI 上永远看不到的死代码"。
}
