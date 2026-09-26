package cn.a10miaomiao.bilimiao.compose.pages.setting

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.preference.rememberPreferenceFlow
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.kodein.di.compose.rememberInstance

/**
 * 直播设置页。
 *
 * ## 为什么单开一页，而不是把几项塞进「播放器设置」
 * 用户要的是"设置里专门加一个**直播的设置**"：直播的后台/小窗/画质和点播不是同一套语义
 * （点播后台播放是继续**出声**，直播是**画面照常播**），混在播放器设置里既不好找，
 * 也容易让人以为改了它点播也会跟着变。入口挂在「设置 → ① 播放 → 直播设置」，
 * 和「播放器设置 / 弹幕设置」并列，符合现有六大分类的风格。
 *
 * ## ★直播弹幕 = 自己一套（本轮定稿）
 * 用户原话："单独设置就单独设置，这两个跟随又是不跟随的样子，我都蒙了。就让直播的那个弹幕成另一套吧。"
 * 于是「直播弹幕」这一组就是**直播唯一的弹幕样式入口**，四项各管一件事、都只对直播生效：
 * | 设置项 | 键 | 默认值 | 谁读取 |
 * |---|---|---|---|
 * | 弹幕字号 | `live_danmaku_font_size` | 15sp | `LiveDanmakuSettings.from()` → 浮层 `settings.fontSizeSp` |
 * | 弹幕不透明度 | `live_danmaku_opacity` | 100% | 同上 → `settings.opacity`（÷100 成 0f~1f） |
 * | **弹幕速度**（本轮新增） | **`live_danmaku_speed`** | **1.0x** | 同上 → `settings.speedScale` → `travelDurationMs = 7000ms ÷ 倍率` |
 * | 弹幕显示区域 | `live_danmaku_area_percent` | 100（全屏） | 同上 → `settings.areaPercent` / `areaFraction` |
 *
 * 速度给用户看的是 **0.5x ~ 2.0x**（0.1 一档，越大越快），文案里同时写"慢/正常/快"和秒数：
 * 0.5x = 慢（14.0 秒跑完全程）/ 1.0x = 正常（默认，7.0 秒）/ 2.0x = 快（3.5 秒）。
 * 秒数与浮层用的**同一个公式**（`travelDurationMs = 7000ms ÷ 倍率`），设置页说的和画面上跑的永远是同一个数。
 *
 * ★**删掉的「跟随点播弹幕设置」开关**（`live_danmaku_follow_vod`）：
 *   它把"直播此刻用的是哪一套字号/速度/不透明度"变成一个要在脑子里推演的状态，用户明确说被搞蒙了
 *   （"这两个跟随又是不跟随的样子"）。本轮把**开关本身、以及链路里读它的那段代码**一起删掉
 *   （改动落在 `LiveDanmakuSettings.from()`：现在只有一个来源）。
 *   但**键对象与默认值常量原样保留**（`SettingPreferences.LiveDanmakuFollowVod` +
 *   `SettingConstants.LIVE_DANMAKU_FOLLOW_VOD_DEFAULT`，字符串一个字没改）—— DataStore 按字符串相等认键，
 *   删键会让老用户已经落盘的值变成孤儿。现在全工程零处读写它，老数据不影响任何行为。
 *
 * ★**屏蔽词那条说明/跳转已删除**（用户原话："这他妈的相关的直播弹幕屏蔽词也给它去掉了，
 *   这个设置也不要了"）：直播弹幕**不做任何关键词过滤**，浮层入队前不再过点播那份词表，
 *   状态条里的"屏蔽词过滤=N"也一并删掉；`LiveDanmakuSettings` 里 `filterEnabled` /
 *   `filterKeywords` / `buildFilter()` 三个读取口已清理（**键字符串一个字没改，只是零处读写**）。
 *   所以本页**没有任何**关于屏蔽词的说明或入口 —— 直播弹幕参数全部只来自 `live_danmaku_*`。
 *
 * ## 本页其余项与"删显示 ≠ 删键"（上一轮精简的结论，保持不变）
 * 判据只有一条：**这一项在播放页/首页有没有更顺手的入口**。有，就从设置里删掉显示：
 * | 删掉的项 | 键（★保留，一个字没动） | 为什么删 | 现在去哪儿改 |
 * |---|---|---|---|
 * | 双击暂停 | `live_double_tap_pause`（默认 true） | 用户："我都想默认就是开启双击暂停的"——默认行为不需要一个开关 | 默认就是开；播放页底栏「设置」浮层里仍能关（A 路） |
 * | 退后台自动进小窗 | `live_pip_on_background`（默认 true） | 用户："用户要是想真的自己干嘛的，他自己会点那个 PIP 小窗的" | 默认开 + 播放页底栏的 PIP 按钮（A 路） |
 * | 后台继续直播 | `live_background_play`（默认 false） | 同上：属于极少数人才要的逃生门，不该占设置页一行 | 播放页底栏「设置」浮层（A 路） |
 * | 显示弹幕 | `live_danmaku_enable`（默认 true） | 用户："进直播时候底栏就已经有一个弹幕开关了，我觉得就是重复项" | 播放页底栏「弹幕」按钮。★它当前是**会话级**的（只改本页状态、不落盘，见 `app/.../LivePlayerActivity.applyDanmakuEnabled`），所以这个键目前**全工程没有写入方**——本轮起它又是直播可见性的**唯一**来源，建议 A 路把按钮状态按需落盘到它（详见交付报告 §4.7） |
 * | 默认排序 | `live_sort_type`（默认 "online"） | 用户要求挪到首页直播 Tab 的底栏筛选弹窗里（在列表上调比在设置里调顺手） | 首页「直播」Tab → 底栏「筛选」（B 路） |
 *
 * ★"删显示" ≠ "删键"：上表五个键的定义、默认值、读取逻辑一行都没动（DataStore 按**字符串相等**
 *   认键，删键 = 让老用户已经落盘的数据变成孤儿）。本页只是不再暴露它们，读取方（播放页 / 首页 /
 *   直播弹幕链路）照旧工作，所以老用户升级后行为逐字不变；反过来，通过播放页浮层改出来的值，
 *   本页也不会再显示成一个"和实际不符"的开关。
 *
 * ## 读取口只有这几个，别各写各的
 *  - **非 Compose / 主线程直接读**（播放页）：`SettingPreferences.liveSettings()`（内存快照，不阻塞）；
 *  - **Compose 里要跟着设置变**（首页 Tab）：`AppStore.stateFlow` 的 `state.live`（同一个 Values 类型）；
 *  - **直播弹幕浮层**：`LiveDanmakuSettings.watch()`（`LiveDanmakuOverlayHost` 内部订阅，改完当场生效）。
 *
 * ## 交接：新键 `live_auto_rotate` 的消费方式（★A 路播放页照这个接）
 * 用户要的语义是"屏幕方向转动自动旋转：竖屏它就竖屏，横屏它就是全屏"，落到播放页只有一行：
 * ```
 * // 进房时（以及从「旋转」按钮手动锁方向之前）决定 requestedOrientation
 * val autoRotate = SettingPreferences.liveSettings().autoRotate   // 主线程 O(1)，不阻塞
 * requestedOrientation = if (autoRotate) {
 *     ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR                 // 跟随重力：竖→竖屏、横→全屏
 * } else {
 *     ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED                 // 不跟随：保持系统/用户当前方向
 * }
 * ```
 * 为什么默认是**开**：现状（`AndroidManifest.xml:86-91` 没写 `screenOrientation`）本来就是跟随系统，
 * 且 `configChanges` 已经声明了 `orientation|screenSize|...`，转屏**不重建 Activity**、
 * 窗口几何在 `onConfigurationChanged` 里重算——也就是说"竖屏竖着看、横过来全屏"这条路
 * 现在就是通的，默认值必须对齐现状才不会让老用户升级后"转屏没反应"。
 * 关掉它 = 尊重用户在播放页里手动锁定的方向（`toggleOrientation()` 那条路径），两者天然互补。
 *
 * ## 为什么"后台继续直播"的默认值仍然是**关**（尽管本页已不显示它）
 * ① 现状就是关（`LivePlayerActivity.onStop()` 里直接 `delegate?.pause()`），默认值必须对齐现状，
 *    否则老用户升级后"退后台还在响"会变成惊吓；
 * ② "继续播"真正的代价在系统侧：Android 14+ 要 `mediaPlayback` 类型的前台服务 + MediaSessionService，
 *    否则退后台几十秒内就会被系统冻结/杀掉，"设置成开也留不住"；
 * ③ 想后台看画面的人，真正想要的是**小窗**（默认就开，且已经能用），那一项零系统限制。
 * 因为 ②③，这一项从来就不是"默认体验"的一部分，只配当逃生门；本轮既然播放页浮层里已经有它，
 * 设置页这一行就是纯重复 —— 删显示、留键留默认值。
 */
@Serializable
class LiveSettingPage : ComposePage() {

    @Composable
    override fun Content() {
        // ★第十四批：本页原来有一个 `LiveSettingPageViewModel`，它只装两张"值 ↔ 名字"翻译表
        //   （默认画质 / 默认线路策略），没有 init、没有请求、没有状态。两张表随本次抽取搬到
        //   `LiveSettingPreferences.kt` 的 [LiveSettingOptions]（播放页的「设置」弹窗也要用它们，
        //   两处各留一份必然漂移），于是这一层**空了** —— 空 ViewModel 一起删掉，
        //   页面行为一个字不变（它本来就不参与任何读写）。
        LiveSettingPageContent()
    }
}

@Composable
private fun LiveSettingPageContent() {
    PageConfig(
        title = "直播设置"
    )
    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val context = LocalContext.current
    val dataStore = remember {
        SettingPreferences.run { context.dataStore }
    }

    ProvidePreferenceLocals(
        flow = rememberPreferenceFlow(dataStore)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = windowInsets.leftDp.dp,
                    end = windowInsets.rightDp.dp,
                )
        ) {
            item("top") {
                Spacer(
                    modifier = Modifier.height(windowInsets.topDp.dp)
                )
            }

            // ★★第十四批：这一整块设置项**搬走了** —— 现在只在
            //   `LiveSettingPreferences.kt` 的 [liveSettingPreferenceItems] 里写一份
            //   （同一批项现在有**两个入口**：本页，以及播放页底栏那颗「设置」按钮弹出的弹窗）。
            //   键 / 默认值 / 档位 / 文案 / 顺序与搬之前**逐字相同**；本页只留自己的滚动容器、
            //   左右内边距与上下留白（弹窗那边是 `weight(1f)` 吃满 + 给底部按钮让位，两边不一样）。
            liveSettingPreferenceItems()

            item("bottom") {
                Spacer(
                    modifier = Modifier.height(
                        windowInsets.bottomDp.dp + windowStore.bottomAppBarHeightDp.dp
                    )
                )
            }
        }
    }
}
