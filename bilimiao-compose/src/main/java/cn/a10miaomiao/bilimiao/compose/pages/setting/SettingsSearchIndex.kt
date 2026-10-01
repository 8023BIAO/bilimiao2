package cn.a10miaomiao.bilimiao.compose.pages.setting

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorCategory
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorSkipType
import com.a10miaomiao.bilimiao.comm.live.danmaku.LiveDanmakuSettings
/**
 * 设置搜索索引（**由 gen_settings_index.py 从真实调用点自动生成 —— 不要手改**）。
 *
 * 数据源：各设置页里真实存在的 switchPreference / sliderIntPreference / sliderPreference /
 * textIntPreference 调用点（`components/preference/` 下的 DSL 定义本身不算），外加 listPreference /
 * multiSelectIntPreference / listStylePreference / customSetsPreference 这类**搜索页改不了、
 * 但页面上真有**的项（后者收成 Kind.LINK，只为搜得到）。本次扫描到 98 个调用点，
 * 收录 139 条（按存储键去重）；未收录的调用点及原因由生成器打印在 stdout 报告里
 * （注释掉的调用、`key = item.prefKey` 这类动态渲染、`preference(...)+onClick` 动作项等）。
 *
 * ★搜索结果必须用**与原设置页同款控件**：Kind 与控件一一对应 —— [SettingSearchItem.Kind.SWITCH]
 * =开关、[SettingSearchItem.Kind.SLIDER_INT]=整数拖动条、[SettingSearchItem.Kind.SLIDER_FLOAT]
 * =Float 拖动条、[SettingSearchItem.Kind.TEXT_INT]=数值输入框；只有
 * [SettingSearchItem.Kind.LINK]（下拉/多选/集合编辑，以及档位依赖运行时值的滑条）改不了，
 * 点开进原页改。此前 sliderIntPreference 与 textIntPreference 都压成 INT，
 * 于是"原页面是拖动条、搜出来变成输入框"（用户反馈 R9）。
 *
 * 为什么要有 page / section：搜索结果显示"这是哪个设置页、哪个分组里的开关"
 * （形如「① 播放 · 直播设置 › 直播弹幕」），用户不用猜；此前只显示大类。
 *
 * 用法：设置首页顶部搜索框输入关键词 → 这一页刷成搜索结果，开关/拖动条/数值项
 * **直接在这里改，用的就是原页面那个控件**；清空输入框 → 回到 6 个大分类。
 *
 * 生成规则（改规则请改脚本，别改本文件）：
 *  · key 去 SettingPreferences.kt 查真实存储键；`danmakuPreferences.x.name`（弹幕显示设置页
 *    的 4 个模式 tab：默认/小屏/全屏/画中画共用同一份 Content）按 4 个模式各出一条；
 *  · page 取自 SettingPage.kt 的 settingPages（页面类 → 标题/大类）；共享内容文件按
 *    `if (MoreSection.X in sections)` / `if (FilterSection.X in sections)` 分流到具体页面；
 *  · section = 同一函数内、该条之前最近的 preferenceCategory 标题；
 *  · 滑条把原调用点的 valueRange / valueSteps / valueText 一起带进 [SettingSearchItem.slider]；
 *    valueText 是原页面 Composable lambda 的**原样拷贝**（数值文案与原页逐字一致），
 *    档位解析不出来（例如 range 用了运行时变量）的滑条降级 Kind.LINK；
 *  · `SponsorCategory.entries.forEach { … }` 那种按枚举条目参数化的项，逐条目展开。
 */
data class SettingSearchItem(
    /** SettingPreferences 里的属性名（唯一 id；字面量键记作 `key:<字面量>`） */
    val prefName: String,
    /** DataStore 存储键字符串（渲染控件用） */
    val prefKey: String,
    /** 设置项标题（中文） */
    val title: String,
    /** 一级分类：① 播放 / ② 界面 / ③ 内容与评论 / ④ 扩展 / ⑤ 账号与数据 / ⑥ 关于 */
    val category: String,
    /** 所属设置页标题，如 "直播设置"、"弹幕显示设置"（★本轮新增） */
    val page: String,
    /** 页内分组（最近的 preferenceCategory 标题），没有则空串（★本轮新增） */
    val section: String,
    val kind: Kind,
    /** 默认值，按源码里的表达式原样带过来（true / 4 / 1f / "online" / SettingConstants.xxx） */
    val default: Any,
    /** 搜索用关键词（标题 + 大类 + 页面 + 分组 + 英文键名 + 少量同义词） */
    val keywords: String,
    /**
     * 数值输入框的单位（原页面 `textIntPreference(label = " MB")` 的原样拷贝，含前导空格）。
     * 它是**输入弹窗里输入框的字段名**（TextIntPreference 把 label 渲染成字段标签），
     * 不是 summary 文案 —— 丢了用户点开弹窗就看不到单位（复核发现 9/9 全丢）。
     * 其它 Kind 恒为空串。
     */
    val label: String = "",
    /**
     * 滑条参数：只有 [Kind.SLIDER_INT] / [Kind.SLIDER_FLOAT] 非空。
     * 原设置页怎么调 `sliderIntPreference` / `sliderPreference`，这里就原样带一份 ——
     * 搜索结果用**同一个 DSL** 渲染，档位与数值文案都跟原页面一致（★用户 R9）。
     */
    val slider: SliderSpec? = null,
) {
    /**
     * 条目类型（Kind 与控件一一对应，搜索结果按它挑组件）：
     *  · [SWITCH] / [SLIDER_INT] / [SLIDER_FLOAT] / [TEXT_INT]：搜索页**可以直接改**，
     *    且控件与来源设置页**同款**（开关 / 整数拖动条 / Float 拖动条 / 数值输入框）；
     *    滑条的档位和数值文案在 [slider] 里。
     *  · [LINK]：搜索页改不了（下拉选择、多选、列表样式、集合编辑，以及档位依赖运行时值
     *    的滑条），只保证**搜得到**，点开进它自己的设置页去改；`default` 可能是
     *    Float/String，渲染方**不要 cast**（LINK 行不显示值）。
     */
    enum class Kind { SWITCH, SLIDER_INT, SLIDER_FLOAT, TEXT_INT, LINK }

    /** 命中判定：标题/分类/页面/分组/关键词/偏好名/存储键 任一包含（大小写不敏感） */
    fun matches(q: String): Boolean {
        val query = q.trim().lowercase()
        if (query.isEmpty()) return false
        return title.lowercase().contains(query) ||
            category.lowercase().contains(query) ||
            page.lowercase().contains(query) ||
            section.lowercase().contains(query) ||
            keywords.lowercase().contains(query) ||
            prefName.lowercase().contains(query) ||
            prefKey.lowercase().contains(query)
    }

    /**
     * 滑条参数（原页面 DSL 调用的原样拷贝）。
     *
     * 为什么 [valueText] 存的是 lambda 而不是"格式化字符串"：各设置页的数值文案
     * （"24行" / "1.0倍" / 0 档显示"无限制" / 直播速度的 "1.5x"）都是各页自己写的
     * Composable lambda，索引里存二手描述既会漏也会漂；原样拷贝才能保证搜索页
     * 显示的数值文案与原页面逐字一致 —— 用户要的就是"原来是什么，现在就是什么"。
     */
    sealed interface SliderSpec {
        /** `sliderIntPreference`：整数拖动条 */
        data class IntSlider(
            val range: IntRange,
            val steps: Int,
            /** 原页面 valueText 的原样拷贝；null = 组件默认（纯数字） */
            val valueText: (@Composable (Int) -> Unit)? = null,
        ) : SliderSpec

        /** `sliderPreference`（me.zhanghai.compose.preference）：Float 拖动条 */
        data class FloatSlider(
            val range: ClosedFloatingPointRange<Float>,
            val steps: Int,
            /** 原页面 valueText 的原样拷贝；null = 组件默认（纯数字） */
            val valueText: (@Composable (Float) -> Unit)? = null,
        ) : SliderSpec
    }
}

object SettingsSearchIndex {

    /** 139 条；由 gen_settings_index.py 生成（覆盖 98 个调用点） */
    val items: List<SettingSearchItem> = listOf(
        SettingSearchItem(prefName = "PlayerBackground", prefKey = "player_background", title = "后台播放", category = "① 播放", page = "播放器设置", section = "播放器设置",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "后台播放 ① 播放 播放器设置 PlayerBackground player_background"),
        SettingSearchItem(prefName = "PlayerPipOnBackground", prefKey = "player_pip_on_background", title = "小窗播放", category = "① 播放", page = "播放器设置", section = "播放器设置",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "小窗播放 ① 播放 播放器设置 PlayerPipOnBackground player_pip_on_background"),
        SettingSearchItem(prefName = "PlayerAudioFocus", prefKey = "player_audio_focus", title = "占用音频焦点", category = "① 播放", page = "播放器设置", section = "播放器设置",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "占用音频焦点 ① 播放 播放器设置 PlayerAudioFocus player_audio_focus"),
        SettingSearchItem(prefName = "PlayerVolumeSwipePercent", prefKey = "player_volume_swipe_percent", title = "音量手势滑动距离", category = "① 播放", page = "播放器设置", section = "播放控制设置",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = SettingPreferences.PLAYER_VOLUME_SWIPE_PERCENT_DEFAULT, keywords = "音量手势滑动距离 ① 播放 播放器设置 播放控制设置 PlayerVolumeSwipePercent player_volume_swipe_percent",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 30..200, steps = 33, valueText = {
                    Text("$it%")
                })),
        SettingSearchItem(prefName = "PlayerBrightnessSwipeTenths", prefKey = "player_brightness_swipe_tenths", title = "亮度手势滑动距离", category = "① 播放", page = "播放器设置", section = "播放控制设置",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = SettingPreferences.PLAYER_BRIGHTNESS_SWIPE_TENTHS_DEFAULT, keywords = "亮度手势滑动距离 ① 播放 播放器设置 播放控制设置 PlayerBrightnessSwipeTenths player_brightness_swipe_tenths",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 5..60, steps = 10, valueText = {
                    Text("${it / 10f}×")
                })),
        SettingSearchItem(prefName = "PlayerNotification", prefKey = "player_notification", title = "显示通知栏播放器控制器", category = "① 播放", page = "播放器设置", section = "播放控制设置",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "显示通知栏播放器控制器 ① 播放 播放器设置 播放控制设置 PlayerNotification player_notification"),
        SettingSearchItem(prefName = "PlayerOrderRandom", prefKey = "player_order_random", title = "随机播放", category = "① 播放", page = "播放器设置", section = "播放控制设置",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "随机播放 ① 播放 播放器设置 播放控制设置 PlayerOrderRandom player_order_random"),
        SettingSearchItem(prefName = "PlayerSeekPreviewShow", prefKey = "player_seek_preview_show", title = "拖动进度显示预览图", category = "① 播放", page = "播放器设置", section = "播放控制设置",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "拖动进度显示预览图 ① 播放 播放器设置 播放控制设置 PlayerSeekPreviewShow player_seek_preview_show"),
        SettingSearchItem(prefName = "PlayerSmallDraggable", prefKey = "player_small_draggable", title = "小屏时整个播放器可拖拽", category = "① 播放", page = "播放器设置", section = "横屏状态小屏设置",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "小屏时整个播放器可拖拽 ① 播放 播放器设置 横屏状态小屏设置 PlayerSmallDraggable player_small_draggable"),
        SettingSearchItem(prefName = "PlayerSmallShowArea", prefKey = "player_small_show_area", title = "小屏时播放面积", category = "① 播放", page = "播放器设置", section = "横屏状态小屏设置",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 480, keywords = "小屏时播放面积 ① 播放 播放器设置 横屏状态小屏设置 PlayerSmallShowArea player_small_show_area",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 150..600, steps = 0, valueText = {
                    Text(text = it.toString())
                })),
        SettingSearchItem(prefName = "PlayerHoldShowArea", prefKey = "player_hold_show_area", title = "小屏挂起后播放面积", category = "① 播放", page = "播放器设置", section = "横屏状态小屏设置",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 130, keywords = "小屏挂起后播放面积 ① 播放 播放器设置 横屏状态小屏设置 PlayerHoldShowArea player_hold_show_area",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 100..300, steps = 0, valueText = {
                    Text(text = it.toString())
                })),
        SettingSearchItem(prefName = "PlayerSubtitleShow", prefKey = "player_subtitle_show", title = "字幕显示", category = "① 播放", page = "播放器设置", section = "字幕设置",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "字幕显示 ① 播放 播放器设置 字幕设置 PlayerSubtitleShow player_subtitle_show subtitle"),
        SettingSearchItem(prefName = "PlayerAiSubtitleShow", prefKey = "player_ai_subtitle_show", title = "AI字幕显示", category = "① 播放", page = "播放器设置", section = "字幕设置",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "AI字幕显示 ① 播放 播放器设置 字幕设置 PlayerAiSubtitleShow player_ai_subtitle_show subtitle"),
        SettingSearchItem(prefName = "PlayerSubtitleTextSize", prefKey = "player_subtitle_text_size", title = "字幕字号", category = "① 播放", page = "播放器设置", section = "字幕设置",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = DEFAULT_SUBTITLE_TEXT_SIZE, keywords = "字幕字号 ① 播放 播放器设置 字幕设置 PlayerSubtitleTextSize player_subtitle_text_size subtitle font size 大小",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = MIN_SUBTITLE_TEXT_SIZE..MAX_SUBTITLE_TEXT_SIZE, steps = 17, valueText = {
                    Text("${it}sp")
                })),
        SettingSearchItem(prefName = "DanmakuEnable", prefKey = "danmaku_enable", title = "启用弹幕", category = "① 播放", page = "弹幕设置", section = "基础设置",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "启用弹幕 ① 播放 弹幕设置 基础设置 DanmakuEnable danmaku_enable danmaku 弹屏"),
        SettingSearchItem(prefName = "DanmakuSysFont", prefKey = "danmaku_sys_font", title = "弹幕使用系统字体", category = "① 播放", page = "弹幕设置", section = "基础设置",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "弹幕使用系统字体 ① 播放 弹幕设置 基础设置 DanmakuSysFont danmaku_sys_font danmaku 弹屏 font size 大小"),
        SettingSearchItem(prefName = "DanmakuFilterEnabled", prefKey = "danmaku_filter_enabled", title = "启用弹幕过滤", category = "① 播放", page = "弹幕显示设置", section = "弹幕过滤",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "启用弹幕过滤 ① 播放 弹幕显示设置 弹幕过滤 DanmakuFilterEnabled danmaku_filter_enabled danmaku 弹屏 filter block 屏蔽词"),
        SettingSearchItem(prefName = "DanmakuFilterDuplicate", prefKey = "danmaku_filter_duplicate", title = "过滤重复弹幕", category = "① 播放", page = "弹幕显示设置", section = "弹幕过滤",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "过滤重复弹幕 ① 播放 弹幕显示设置 弹幕过滤 DanmakuFilterDuplicate danmaku_filter_duplicate danmaku 弹屏 filter block 屏蔽词"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuSmallMode.enable", prefKey = "small_danmaku_enable", title = "启用独立设置（小屏）", category = "① 播放", page = "弹幕显示设置", section = "",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "启用独立设置（小屏） ① 播放 弹幕显示设置 DanmakuSmallMode.enable small_danmaku_enable danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuFullMode.enable", prefKey = "full_danmaku_enable", title = "启用独立设置（全屏）", category = "① 播放", page = "弹幕显示设置", section = "",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "启用独立设置（全屏） ① 播放 弹幕显示设置 DanmakuFullMode.enable full_danmaku_enable danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuPipMode.enable", prefKey = "pip_danmaku_enable", title = "启用独立设置（画中画）", category = "① 播放", page = "弹幕显示设置", section = "",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "启用独立设置（画中画） ① 播放 弹幕显示设置 DanmakuPipMode.enable pip_danmaku_enable danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuDefault.show", prefKey = "default_danmaku_show", title = "显示弹幕（默认）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "显示弹幕（默认） ① 播放 弹幕显示设置 显示 DanmakuDefault.show default_danmaku_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuSmallMode.show", prefKey = "small_danmaku_show", title = "显示弹幕（小屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "显示弹幕（小屏） ① 播放 弹幕显示设置 显示 DanmakuSmallMode.show small_danmaku_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuFullMode.show", prefKey = "full_danmaku_show", title = "显示弹幕（全屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "显示弹幕（全屏） ① 播放 弹幕显示设置 显示 DanmakuFullMode.show full_danmaku_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuPipMode.show", prefKey = "pip_danmaku_show", title = "显示弹幕（画中画）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "显示弹幕（画中画） ① 播放 弹幕显示设置 显示 DanmakuPipMode.show pip_danmaku_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuDefault.r2lShow", prefKey = "default_danmaku_r2l_show", title = "滚动弹幕显示（默认）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "滚动弹幕显示（默认） ① 播放 弹幕显示设置 显示 DanmakuDefault.r2lShow default_danmaku_r2l_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuSmallMode.r2lShow", prefKey = "small_danmaku_r2l_show", title = "滚动弹幕显示（小屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "滚动弹幕显示（小屏） ① 播放 弹幕显示设置 显示 DanmakuSmallMode.r2lShow small_danmaku_r2l_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuFullMode.r2lShow", prefKey = "full_danmaku_r2l_show", title = "滚动弹幕显示（全屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "滚动弹幕显示（全屏） ① 播放 弹幕显示设置 显示 DanmakuFullMode.r2lShow full_danmaku_r2l_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuPipMode.r2lShow", prefKey = "pip_danmaku_r2l_show", title = "滚动弹幕显示（画中画）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "滚动弹幕显示（画中画） ① 播放 弹幕显示设置 显示 DanmakuPipMode.r2lShow pip_danmaku_r2l_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuDefault.ftShow", prefKey = "default_danmaku_ft_show", title = "顶部弹幕显示（默认）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "顶部弹幕显示（默认） ① 播放 弹幕显示设置 显示 DanmakuDefault.ftShow default_danmaku_ft_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuSmallMode.ftShow", prefKey = "small_danmaku_ft_show", title = "顶部弹幕显示（小屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "顶部弹幕显示（小屏） ① 播放 弹幕显示设置 显示 DanmakuSmallMode.ftShow small_danmaku_ft_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuFullMode.ftShow", prefKey = "full_danmaku_ft_show", title = "顶部弹幕显示（全屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "顶部弹幕显示（全屏） ① 播放 弹幕显示设置 显示 DanmakuFullMode.ftShow full_danmaku_ft_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuPipMode.ftShow", prefKey = "pip_danmaku_ft_show", title = "顶部弹幕显示（画中画）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "顶部弹幕显示（画中画） ① 播放 弹幕显示设置 显示 DanmakuPipMode.ftShow pip_danmaku_ft_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuDefault.fbShow", prefKey = "default_danmaku_fb_show", title = "底部弹幕显示（默认）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "底部弹幕显示（默认） ① 播放 弹幕显示设置 显示 DanmakuDefault.fbShow default_danmaku_fb_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuSmallMode.fbShow", prefKey = "small_danmaku_fb_show", title = "底部弹幕显示（小屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "底部弹幕显示（小屏） ① 播放 弹幕显示设置 显示 DanmakuSmallMode.fbShow small_danmaku_fb_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuFullMode.fbShow", prefKey = "full_danmaku_fb_show", title = "底部弹幕显示（全屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "底部弹幕显示（全屏） ① 播放 弹幕显示设置 显示 DanmakuFullMode.fbShow full_danmaku_fb_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuPipMode.fbShow", prefKey = "pip_danmaku_fb_show", title = "底部弹幕显示（画中画）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "底部弹幕显示（画中画） ① 播放 弹幕显示设置 显示 DanmakuPipMode.fbShow pip_danmaku_fb_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuDefault.specialShow", prefKey = "default_danmaku_special_show", title = "高级弹幕显示（默认）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "高级弹幕显示（默认） ① 播放 弹幕显示设置 显示 DanmakuDefault.specialShow default_danmaku_special_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuSmallMode.specialShow", prefKey = "small_danmaku_special_show", title = "高级弹幕显示（小屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "高级弹幕显示（小屏） ① 播放 弹幕显示设置 显示 DanmakuSmallMode.specialShow small_danmaku_special_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuFullMode.specialShow", prefKey = "full_danmaku_special_show", title = "高级弹幕显示（全屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "高级弹幕显示（全屏） ① 播放 弹幕显示设置 显示 DanmakuFullMode.specialShow full_danmaku_special_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuPipMode.specialShow", prefKey = "pip_danmaku_special_show", title = "高级弹幕显示（画中画）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "高级弹幕显示（画中画） ① 播放 弹幕显示设置 显示 DanmakuPipMode.specialShow pip_danmaku_special_show danmaku 弹屏"),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuDefault.r2lMaxLine", prefKey = "default_danmaku_r2l_max_line", title = "滚动弹幕最大行数（默认）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 0, keywords = "滚动弹幕最大行数（默认） ① 播放 弹幕显示设置 显示 DanmakuDefault.r2lMaxLine default_danmaku_r2l_max_line danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 0..20, steps = 19, valueText = {
                    if (it == 0) {
                        Text(text = "无限制")
                    } else {
                        Text(text = "%d行".format(it))
                    }
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuSmallMode.r2lMaxLine", prefKey = "small_danmaku_r2l_max_line", title = "滚动弹幕最大行数（小屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 0, keywords = "滚动弹幕最大行数（小屏） ① 播放 弹幕显示设置 显示 DanmakuSmallMode.r2lMaxLine small_danmaku_r2l_max_line danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 0..20, steps = 19, valueText = {
                    if (it == 0) {
                        Text(text = "无限制")
                    } else {
                        Text(text = "%d行".format(it))
                    }
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuFullMode.r2lMaxLine", prefKey = "full_danmaku_r2l_max_line", title = "滚动弹幕最大行数（全屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 0, keywords = "滚动弹幕最大行数（全屏） ① 播放 弹幕显示设置 显示 DanmakuFullMode.r2lMaxLine full_danmaku_r2l_max_line danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 0..20, steps = 19, valueText = {
                    if (it == 0) {
                        Text(text = "无限制")
                    } else {
                        Text(text = "%d行".format(it))
                    }
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuPipMode.r2lMaxLine", prefKey = "pip_danmaku_r2l_max_line", title = "滚动弹幕最大行数（画中画）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 0, keywords = "滚动弹幕最大行数（画中画） ① 播放 弹幕显示设置 显示 DanmakuPipMode.r2lMaxLine pip_danmaku_r2l_max_line danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 0..20, steps = 19, valueText = {
                    if (it == 0) {
                        Text(text = "无限制")
                    } else {
                        Text(text = "%d行".format(it))
                    }
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuDefault.ftMaxLine", prefKey = "default_danmaku_ft_max_line", title = "顶部弹幕最大行数（默认）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 0, keywords = "顶部弹幕最大行数（默认） ① 播放 弹幕显示设置 显示 DanmakuDefault.ftMaxLine default_danmaku_ft_max_line danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 0..20, steps = 19, valueText = {
                    if (it == 0) {
                        Text(text = "无限制")
                    } else {
                        Text(text = "%d行".format(it))
                    }
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuSmallMode.ftMaxLine", prefKey = "small_danmaku_ft_max_line", title = "顶部弹幕最大行数（小屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 0, keywords = "顶部弹幕最大行数（小屏） ① 播放 弹幕显示设置 显示 DanmakuSmallMode.ftMaxLine small_danmaku_ft_max_line danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 0..20, steps = 19, valueText = {
                    if (it == 0) {
                        Text(text = "无限制")
                    } else {
                        Text(text = "%d行".format(it))
                    }
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuFullMode.ftMaxLine", prefKey = "full_danmaku_ft_max_line", title = "顶部弹幕最大行数（全屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 0, keywords = "顶部弹幕最大行数（全屏） ① 播放 弹幕显示设置 显示 DanmakuFullMode.ftMaxLine full_danmaku_ft_max_line danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 0..20, steps = 19, valueText = {
                    if (it == 0) {
                        Text(text = "无限制")
                    } else {
                        Text(text = "%d行".format(it))
                    }
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuPipMode.ftMaxLine", prefKey = "pip_danmaku_ft_max_line", title = "顶部弹幕最大行数（画中画）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 0, keywords = "顶部弹幕最大行数（画中画） ① 播放 弹幕显示设置 显示 DanmakuPipMode.ftMaxLine pip_danmaku_ft_max_line danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 0..20, steps = 19, valueText = {
                    if (it == 0) {
                        Text(text = "无限制")
                    } else {
                        Text(text = "%d行".format(it))
                    }
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuDefault.fbMaxLine", prefKey = "default_danmaku_fb_max_line", title = "底部弹幕最大行数（默认）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 0, keywords = "底部弹幕最大行数（默认） ① 播放 弹幕显示设置 显示 DanmakuDefault.fbMaxLine default_danmaku_fb_max_line danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 0..20, steps = 19, valueText = {
                    if (it == 0) {
                        Text(text = "无限制")
                    } else {
                        Text(text = "%d行".format(it))
                    }
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuSmallMode.fbMaxLine", prefKey = "small_danmaku_fb_max_line", title = "底部弹幕最大行数（小屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 0, keywords = "底部弹幕最大行数（小屏） ① 播放 弹幕显示设置 显示 DanmakuSmallMode.fbMaxLine small_danmaku_fb_max_line danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 0..20, steps = 19, valueText = {
                    if (it == 0) {
                        Text(text = "无限制")
                    } else {
                        Text(text = "%d行".format(it))
                    }
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuFullMode.fbMaxLine", prefKey = "full_danmaku_fb_max_line", title = "底部弹幕最大行数（全屏）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 0, keywords = "底部弹幕最大行数（全屏） ① 播放 弹幕显示设置 显示 DanmakuFullMode.fbMaxLine full_danmaku_fb_max_line danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 0..20, steps = 19, valueText = {
                    if (it == 0) {
                        Text(text = "无限制")
                    } else {
                        Text(text = "%d行".format(it))
                    }
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuPipMode.fbMaxLine", prefKey = "pip_danmaku_fb_max_line", title = "底部弹幕最大行数（画中画）", category = "① 播放", page = "弹幕显示设置", section = "显示",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 0, keywords = "底部弹幕最大行数（画中画） ① 播放 弹幕显示设置 显示 DanmakuPipMode.fbMaxLine pip_danmaku_fb_max_line danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 0..20, steps = 19, valueText = {
                    if (it == 0) {
                        Text(text = "无限制")
                    } else {
                        Text(text = "%d行".format(it))
                    }
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuDefault.fontSize", prefKey = "default_danmaku_fontsize", title = "字体大小（默认）", category = "① 播放", page = "弹幕显示设置", section = "字体",
            kind = SettingSearchItem.Kind.SLIDER_FLOAT, default = 1f, keywords = "字体大小（默认） ① 播放 弹幕显示设置 字体 DanmakuDefault.fontSize default_danmaku_fontsize danmaku 弹屏 font size 大小",
            slider = SettingSearchItem.SliderSpec.FloatSlider(range = 0.1f..4f, steps = 24, valueText = {
                    Text(text = "%.1f倍".format(it))
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuSmallMode.fontSize", prefKey = "small_danmaku_fontsize", title = "字体大小（小屏）", category = "① 播放", page = "弹幕显示设置", section = "字体",
            kind = SettingSearchItem.Kind.SLIDER_FLOAT, default = 1f, keywords = "字体大小（小屏） ① 播放 弹幕显示设置 字体 DanmakuSmallMode.fontSize small_danmaku_fontsize danmaku 弹屏 font size 大小",
            slider = SettingSearchItem.SliderSpec.FloatSlider(range = 0.1f..4f, steps = 24, valueText = {
                    Text(text = "%.1f倍".format(it))
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuFullMode.fontSize", prefKey = "full_danmaku_fontsize", title = "字体大小（全屏）", category = "① 播放", page = "弹幕显示设置", section = "字体",
            kind = SettingSearchItem.Kind.SLIDER_FLOAT, default = 1f, keywords = "字体大小（全屏） ① 播放 弹幕显示设置 字体 DanmakuFullMode.fontSize full_danmaku_fontsize danmaku 弹屏 font size 大小",
            slider = SettingSearchItem.SliderSpec.FloatSlider(range = 0.1f..4f, steps = 24, valueText = {
                    Text(text = "%.1f倍".format(it))
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuPipMode.fontSize", prefKey = "pip_danmaku_fontsize", title = "字体大小（画中画）", category = "① 播放", page = "弹幕显示设置", section = "字体",
            kind = SettingSearchItem.Kind.SLIDER_FLOAT, default = 1f, keywords = "字体大小（画中画） ① 播放 弹幕显示设置 字体 DanmakuPipMode.fontSize pip_danmaku_fontsize danmaku 弹屏 font size 大小",
            slider = SettingSearchItem.SliderSpec.FloatSlider(range = 0.1f..4f, steps = 24, valueText = {
                    Text(text = "%.1f倍".format(it))
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuDefault.opacity", prefKey = "default_danmaku_opacity", title = "字体不透明度（默认）", category = "① 播放", page = "弹幕显示设置", section = "字体",
            kind = SettingSearchItem.Kind.SLIDER_FLOAT, default = 1f, keywords = "字体不透明度（默认） ① 播放 弹幕显示设置 字体 DanmakuDefault.opacity default_danmaku_opacity danmaku 弹屏 alpha 透明 font size 大小",
            slider = SettingSearchItem.SliderSpec.FloatSlider(range = 0f..1f, steps = 99, valueText = {
                    Text(text = "${(it * 100).toInt()}%")
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuSmallMode.opacity", prefKey = "small_danmaku_opacity", title = "字体不透明度（小屏）", category = "① 播放", page = "弹幕显示设置", section = "字体",
            kind = SettingSearchItem.Kind.SLIDER_FLOAT, default = 1f, keywords = "字体不透明度（小屏） ① 播放 弹幕显示设置 字体 DanmakuSmallMode.opacity small_danmaku_opacity danmaku 弹屏 alpha 透明 font size 大小",
            slider = SettingSearchItem.SliderSpec.FloatSlider(range = 0f..1f, steps = 99, valueText = {
                    Text(text = "${(it * 100).toInt()}%")
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuFullMode.opacity", prefKey = "full_danmaku_opacity", title = "字体不透明度（全屏）", category = "① 播放", page = "弹幕显示设置", section = "字体",
            kind = SettingSearchItem.Kind.SLIDER_FLOAT, default = 1f, keywords = "字体不透明度（全屏） ① 播放 弹幕显示设置 字体 DanmakuFullMode.opacity full_danmaku_opacity danmaku 弹屏 alpha 透明 font size 大小",
            slider = SettingSearchItem.SliderSpec.FloatSlider(range = 0f..1f, steps = 99, valueText = {
                    Text(text = "${(it * 100).toInt()}%")
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuPipMode.opacity", prefKey = "pip_danmaku_opacity", title = "字体不透明度（画中画）", category = "① 播放", page = "弹幕显示设置", section = "字体",
            kind = SettingSearchItem.Kind.SLIDER_FLOAT, default = 1f, keywords = "字体不透明度（画中画） ① 播放 弹幕显示设置 字体 DanmakuPipMode.opacity pip_danmaku_opacity danmaku 弹屏 alpha 透明 font size 大小",
            slider = SettingSearchItem.SliderSpec.FloatSlider(range = 0f..1f, steps = 99, valueText = {
                    Text(text = "${(it * 100).toInt()}%")
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuDefault.speed", prefKey = "default_danmaku_speed", title = "弹幕速度（默认）", category = "① 播放", page = "弹幕显示设置", section = "速度",
            kind = SettingSearchItem.Kind.SLIDER_FLOAT, default = 1f, keywords = "弹幕速度（默认） ① 播放 弹幕显示设置 速度 DanmakuDefault.speed default_danmaku_speed danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.FloatSlider(range = 0.1f..2f, steps = 18, valueText = {
                    Text(text = "%.1f倍".format(it))
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuSmallMode.speed", prefKey = "small_danmaku_speed", title = "弹幕速度（小屏）", category = "① 播放", page = "弹幕显示设置", section = "速度",
            kind = SettingSearchItem.Kind.SLIDER_FLOAT, default = 1f, keywords = "弹幕速度（小屏） ① 播放 弹幕显示设置 速度 DanmakuSmallMode.speed small_danmaku_speed danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.FloatSlider(range = 0.1f..2f, steps = 18, valueText = {
                    Text(text = "%.1f倍".format(it))
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuFullMode.speed", prefKey = "full_danmaku_speed", title = "弹幕速度（全屏）", category = "① 播放", page = "弹幕显示设置", section = "速度",
            kind = SettingSearchItem.Kind.SLIDER_FLOAT, default = 1f, keywords = "弹幕速度（全屏） ① 播放 弹幕显示设置 速度 DanmakuFullMode.speed full_danmaku_speed danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.FloatSlider(range = 0.1f..2f, steps = 18, valueText = {
                    Text(text = "%.1f倍".format(it))
                })),
        SettingSearchItem(prefName = "SettingPreferences.DanmakuPipMode.speed", prefKey = "pip_danmaku_speed", title = "弹幕速度（画中画）", category = "① 播放", page = "弹幕显示设置", section = "速度",
            kind = SettingSearchItem.Kind.SLIDER_FLOAT, default = 1f, keywords = "弹幕速度（画中画） ① 播放 弹幕显示设置 速度 DanmakuPipMode.speed pip_danmaku_speed danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.FloatSlider(range = 0.1f..2f, steps = 18, valueText = {
                    Text(text = "%.1f倍".format(it))
                })),
        SettingSearchItem(prefName = "LiveAutoReconnect", prefKey = "live_auto_reconnect", title = "自动重连", category = "① 播放", page = "直播设置", section = "直播播放",
            kind = SettingSearchItem.Kind.SWITCH, default = SettingConstants.LIVE_AUTO_RECONNECT_DEFAULT, keywords = "自动重连 ① 播放 直播设置 直播播放 LiveAutoReconnect live_auto_reconnect reconnect 断流"),
        SettingSearchItem(prefName = "LiveAutoRotate", prefKey = "live_auto_rotate", title = "自动旋转", category = "① 播放", page = "直播设置", section = "直播播放",
            kind = SettingSearchItem.Kind.SWITCH, default = SettingConstants.LIVE_AUTO_ROTATE_DEFAULT, keywords = "自动旋转 ① 播放 直播设置 直播播放 LiveAutoRotate live_auto_rotate 横屏 竖屏 重力"),
        SettingSearchItem(prefName = "LiveDanmakuFontSize", prefKey = "live_danmaku_font_size", title = "弹幕字号", category = "① 播放", page = "直播设置", section = "直播弹幕",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = SettingConstants.LIVE_DANMAKU_FONT_SIZE_DEFAULT, keywords = "弹幕字号 ① 播放 直播设置 直播弹幕 LiveDanmakuFontSize live_danmaku_font_size danmaku 弹屏 font size 大小",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 10..30, steps = 19, valueText = {
            Text("${it}sp")
        })),
        SettingSearchItem(prefName = "LiveDanmakuChatOpacity", prefKey = "live_danmaku_chat_opacity", title = "竖屏弹幕透明度", category = "① 播放", page = "直播设置", section = "直播弹幕",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = SettingConstants.LIVE_DANMAKU_CHAT_OPACITY_DEFAULT, keywords = "竖屏弹幕透明度 ① 播放 直播设置 直播弹幕 LiveDanmakuChatOpacity live_danmaku_chat_opacity danmaku 弹屏 alpha 透明 竖屏列表",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 10..100, steps = 89, valueText = {
                Text("$it%")
            }),
        ),
        SettingSearchItem(prefName = "LiveDanmakuChatFontSize", prefKey = "live_danmaku_chat_font_size", title = "竖屏列表字号", category = "① 播放", page = "直播设置", section = "直播弹幕",
            kind = SettingSearchItem.Kind.SLIDER_FLOAT, default = SettingConstants.LIVE_DANMAKU_CHAT_FONT_SIZE_DEFAULT, keywords = "竖屏列表字号 ① 播放 直播设置 直播弹幕 LiveDanmakuChatFontSize live_danmaku_chat_font_size danmaku 弹屏 font size 大小",
            slider = SettingSearchItem.SliderSpec.FloatSlider(range = LiveDanmakuSettings.CHAT_FONT_SIZE_SP_MIN..LiveDanmakuSettings.CHAT_FONT_SIZE_SP_MAX, steps = 19, valueText = {
            Text(LiveDanmakuSettings.chatFontSizeText(it))
        })),
        SettingSearchItem(prefName = "LiveDanmakuOpacity", prefKey = "live_danmaku_opacity", title = "弹幕不透明度", category = "① 播放", page = "直播设置", section = "直播弹幕",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = SettingConstants.LIVE_DANMAKU_OPACITY_DEFAULT, keywords = "弹幕不透明度 ① 播放 直播设置 直播弹幕 LiveDanmakuOpacity live_danmaku_opacity danmaku 弹屏 alpha 透明",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 10..100, steps = 89, valueText = {
            Text("$it%")
        })),
        SettingSearchItem(prefName = "LiveDanmakuSpeed", prefKey = "live_danmaku_speed", title = "弹幕速度", category = "① 播放", page = "直播设置", section = "直播弹幕",
            kind = SettingSearchItem.Kind.SLIDER_FLOAT, default = SettingConstants.LIVE_DANMAKU_SPEED_DEFAULT, keywords = "弹幕速度 ① 播放 直播设置 直播弹幕 LiveDanmakuSpeed live_danmaku_speed danmaku 弹屏",
            slider = SettingSearchItem.SliderSpec.FloatSlider(range = LiveDanmakuSettings.LIVE_SPEED_MIN..LiveDanmakuSettings.LIVE_SPEED_MAX, steps = LiveDanmakuSettings.LIVE_SPEED_STEPS, valueText = {
            Text(LiveDanmakuSettings.speedText(it))
        })),
        SettingSearchItem(prefName = "LiveGridSpan", prefKey = "live_grid_span", title = "每行卡片数", category = "① 播放", page = "直播设置", section = "直播列表",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = SettingConstants.LIVE_GRID_SPAN_DEFAULT, keywords = "每行卡片数 ① 播放 直播设置 直播列表 LiveGridSpan live_grid_span",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 0..5, steps = 4, valueText = {
            Text(if (it == SettingConstants.LIVE_GRID_SPAN_AUTO) "自适应" else "${it}列")
        })),
        SettingSearchItem(prefName = "HomeTimeMachineShow", prefKey = "home_time_machine_show", title = "显示时光姬", category = "② 界面", page = "首页设置", section = "首页顶部设置",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "显示时光姬 ② 界面 首页设置 首页顶部设置 HomeTimeMachineShow home_time_machine_show"),
        SettingSearchItem(prefName = "HomeLiveShow", prefKey = "home_live_show", title = "显示直播", category = "② 界面", page = "首页设置", section = "首页顶部设置",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "显示直播 ② 界面 首页设置 首页顶部设置 HomeLiveShow home_live_show live 直播间"),
        SettingSearchItem(prefName = "HomeRecommendShow", prefKey = "home_recommend_show", title = "显示推荐", category = "② 界面", page = "首页设置", section = "首页顶部设置",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "显示推荐 ② 界面 首页设置 首页顶部设置 HomeRecommendShow home_recommend_show recommend"),
        SettingSearchItem(prefName = "HomePopularShow", prefKey = "home_popular_show", title = "显示热门", category = "② 界面", page = "首页设置", section = "首页顶部设置",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "显示热门 ② 界面 首页设置 首页顶部设置 HomePopularShow home_popular_show"),
        SettingSearchItem(prefName = "HomeBangumiShow", prefKey = "home_bangumi_show", title = "显示番剧", category = "② 界面", page = "首页设置", section = "首页顶部设置",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "显示番剧 ② 界面 首页设置 首页顶部设置 HomeBangumiShow home_bangumi_show"),
        SettingSearchItem(prefName = "HomeCinemaShow", prefKey = "home_cinema_show", title = "显示影视", category = "② 界面", page = "首页设置", section = "首页顶部设置",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "显示影视 ② 界面 首页设置 首页顶部设置 HomeCinemaShow home_cinema_show"),
        SettingSearchItem(prefName = "HomeBangumiGridSpan", prefKey = "home_bangumi_grid_span", title = "每行卡片数", category = "② 界面", page = "首页设置", section = "番剧/影视设置",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = 0, keywords = "每行卡片数 ② 界面 首页设置 番剧/影视设置 HomeBangumiGridSpan home_bangumi_grid_span",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 0..5, steps = 4, valueText = {
                    Text(if (it == 0) "自适应" else "${it}列")
                })),
        SettingSearchItem(prefName = "BottomBarLock", prefKey = "bottom_bar_lock", title = "锁定底栏", category = "② 界面", page = "底栏与导航", section = "底栏与导航",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "锁定底栏 ② 界面 底栏与导航 BottomBarLock bottom_bar_lock 导航 navbar"),
        SettingSearchItem(prefName = "BottomBarScrollHideTitle", prefKey = "bottom_bar_scroll_hide_title", title = "标题行一起隐藏", category = "② 界面", page = "底栏与导航", section = "底栏与导航",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "标题行一起隐藏 ② 界面 底栏与导航 BottomBarScrollHideTitle bottom_bar_scroll_hide_title"),
        SettingSearchItem(prefName = "TimeSelectExcludeRecent", prefKey = "time_select_exclude_recent", title = "排除最近N天", category = "② 界面", page = "时光精选设置", section = "时间线设置",
            kind = SettingSearchItem.Kind.TEXT_INT, default = 0, keywords = "排除最近N天 ② 界面 时光精选设置 时间线设置 TimeSelectExcludeRecent time_select_exclude_recent", label = "天"),
        SettingSearchItem(prefName = "TimeSelectAllRegions", prefKey = "time_select_all_regions", title = "全部分区", category = "② 界面", page = "时光精选设置", section = "分区选择",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "全部分区 ② 界面 时光精选设置 分区选择 TimeSelectAllRegions time_select_all_regions region 区域"),
        SettingSearchItem(prefName = "TimeSelectMinDuration", prefKey = "time_select_min_duration", title = "最小时长(秒)", category = "② 界面", page = "时光精选设置", section = "过滤",
            kind = SettingSearchItem.Kind.TEXT_INT, default = 0, keywords = "最小时长(秒) ② 界面 时光精选设置 过滤 TimeSelectMinDuration time_select_min_duration 分钟 秒 filter", label = "秒"),
        SettingSearchItem(prefName = "TimeSelectMinPlayCount", prefKey = "time_select_min_play_count", title = "最小播放量", category = "② 界面", page = "时光精选设置", section = "过滤",
            kind = SettingSearchItem.Kind.TEXT_INT, default = 0, keywords = "最小播放量 ② 界面 时光精选设置 过滤 TimeSelectMinPlayCount time_select_min_play_count 热门 人气", label = "个"),
        SettingSearchItem(prefName = "TimeSelectOriginalOnly", prefKey = "time_select_original_only", title = "只看原创", category = "② 界面", page = "时光精选设置", section = "过滤",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "只看原创 ② 界面 时光精选设置 过滤 TimeSelectOriginalOnly time_select_original_only"),
        SettingSearchItem(prefName = "VideoMinDuration", prefKey = "video_min_duration", title = "最小视频时长过滤", category = "③ 内容与评论", page = "推荐过滤", section = "",
            kind = SettingSearchItem.Kind.TEXT_INT, default = SettingConstants.VIDEO_MIN_DURATION_DEFAULT, keywords = "最小视频时长过滤 ③ 内容与评论 推荐过滤 VideoMinDuration video_min_duration filter block 屏蔽词 分钟 秒", label = "秒"),
        SettingSearchItem(prefName = "VideoMinPlayCount", prefKey = "video_min_play_count", title = "最小播放量过滤", category = "③ 内容与评论", page = "推荐过滤", section = "",
            kind = SettingSearchItem.Kind.TEXT_INT, default = SettingConstants.VIDEO_MIN_PLAY_COUNT_DEFAULT, keywords = "最小播放量过滤 ③ 内容与评论 推荐过滤 VideoMinPlayCount video_min_play_count filter block 屏蔽词 热门 人气", label = "个"),
        SettingSearchItem(prefName = "VideoHideCover", prefKey = "video_hide_cover", title = "不显示封面", category = "③ 内容与评论", page = "推荐过滤", section = "",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "不显示封面 ③ 内容与评论 推荐过滤 VideoHideCover video_hide_cover cover"),
        SettingSearchItem(prefName = "VideoHideRelates", prefKey = "video_hide_relates", title = "隐藏相关推荐", category = "③ 内容与评论", page = "推荐过滤", section = "",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "隐藏相关推荐 ③ 内容与评论 推荐过滤 VideoHideRelates video_hide_relates recommend"),
        SettingSearchItem(prefName = "FollowWhitelistEnabled", prefKey = "follow_whitelist_enabled", title = "已关注UP主白名单", category = "③ 内容与评论", page = "推荐过滤", section = "",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "已关注UP主白名单 ③ 内容与评论 推荐过滤 FollowWhitelistEnabled follow_whitelist_enabled"),
        SettingSearchItem(prefName = "BlockPromotion", prefKey = "block_promotion", title = "屏蔽推广视频", category = "③ 内容与评论", page = "推荐过滤", section = "",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "屏蔽推广视频 ③ 内容与评论 推荐过滤 BlockPromotion block_promotion filter block 屏蔽词"),
        SettingSearchItem(prefName = "FilterTagStrict", prefKey = "filter_tag_strict", title = "标签查询失败时拦截", category = "③ 内容与评论", page = "推荐过滤", section = "",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "标签查询失败时拦截 ③ 内容与评论 推荐过滤 FilterTagStrict filter_tag_strict filter block 屏蔽词"),
        SettingSearchItem(prefName = "CommentSubReplyPreview", prefKey = "comment_sub_reply_preview", title = "显示二级回复", category = "③ 内容与评论", page = "评论区", section = "",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "显示二级回复 ③ 内容与评论 评论区 CommentSubReplyPreview comment_sub_reply_preview"),
        SettingSearchItem(prefName = "SponsorBlockEnable", prefKey = "sponsor_block_enable", title = "启用空降助手", category = "④ 扩展", page = "空降助手", section = "空降助手",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "启用空降助手 ④ 扩展 空降助手 SponsorBlockEnable sponsor_block_enable 跳过 赞助 恰饭 片头 片尾"),
        SettingSearchItem(prefName = "SponsorBlockLimit", prefKey = "sponsor_block_limit", title = "最短片段时长", category = "④ 扩展", page = "空降助手", section = "行为",
            kind = SettingSearchItem.Kind.TEXT_INT, default = 0, keywords = "最短片段时长 ④ 扩展 空降助手 行为 SponsorBlockLimit sponsor_block_limit 分钟 秒 filter", label = " 秒"),
        SettingSearchItem(prefName = "SponsorBlockToast", prefKey = "sponsor_block_toast", title = "跳过时弹提示", category = "④ 扩展", page = "空降助手", section = "行为",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "跳过时弹提示 ④ 扩展 空降助手 行为 SponsorBlockToast sponsor_block_toast"),
        SettingSearchItem(prefName = "SponsorBlockTrack", prefKey = "sponsor_block_track", title = "上报已跳过", category = "④ 扩展", page = "空降助手", section = "行为",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "上报已跳过 ④ 扩展 空降助手 行为 SponsorBlockTrack sponsor_block_track"),
        SettingSearchItem(prefName = "ThreadRipperEnable", prefKey = "thread_ripper_enable", title = "启用分段并发下载", category = "④ 扩展", page = "海外加速", section = "海外加速（分段并发下载）",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "启用分段并发下载 ④ 扩展 海外加速 海外加速（分段并发下载） ThreadRipperEnable thread_ripper_enable 分段 加速 卡顿"),
        SettingSearchItem(prefName = "ThreadRipperSmartAssign", prefKey = "thread_ripper_smart_assign", title = "智能节点调度", category = "④ 扩展", page = "海外加速", section = "海外加速（分段并发下载）",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "智能节点调度 ④ 扩展 海外加速 海外加速（分段并发下载） ThreadRipperSmartAssign thread_ripper_smart_assign"),
        SettingSearchItem(prefName = "ThreadRipperAdaptiveHedge", prefKey = "thread_ripper_adaptive_hedge", title = "自适应抢跑延迟", category = "④ 扩展", page = "海外加速", section = "海外加速（分段并发下载）",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "自适应抢跑延迟 ④ 扩展 海外加速 海外加速（分段并发下载） ThreadRipperAdaptiveHedge thread_ripper_adaptive_hedge"),
        SettingSearchItem(prefName = "ThreadRipperPushback", prefKey = "thread_ripper_pushback", title = "412/429 风控退让", category = "④ 扩展", page = "海外加速", section = "海外加速（分段并发下载）",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "412/429 风控退让 ④ 扩展 海外加速 海外加速（分段并发下载） ThreadRipperPushback thread_ripper_pushback"),
        SettingSearchItem(prefName = "ThreadRipperCrossHost", prefKey = "thread_ripper_cross_host", title = "跨节点候选合成（实验性）", category = "④ 扩展", page = "海外加速", section = "海外加速（分段并发下载）",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "跨节点候选合成（实验性） ④ 扩展 海外加速 海外加速（分段并发下载） ThreadRipperCrossHost thread_ripper_cross_host"),
        SettingSearchItem(prefName = "CdnRaceEnabled", prefKey = "cdn_race_enabled", title = "CDN 竞速", category = "④ 扩展", page = "CDN", section = "CDN",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "CDN 竞速 ④ 扩展 CdnRaceEnabled cdn_race_enabled 节点 加速"),
        SettingSearchItem(prefName = "AudioIndependentCdn", prefKey = "audio_independent_cdn", title = "音频不跟随 CDN", category = "④ 扩展", page = "CDN", section = "CDN",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "音频不跟随 CDN ④ 扩展 AudioIndependentCdn audio_independent_cdn 竞速 节点 加速"),
        SettingSearchItem(prefName = "AntifraudEnabled", prefKey = "antifraud_enabled", title = "发评论后自动检测是否被限流（总开关）", category = "④ 扩展", page = "评论反诈", section = "评论反诈",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "发评论后自动检测是否被限流（总开关） ④ 扩展 评论反诈 AntifraudEnabled antifraud_enabled 评论区 comment"),
        SettingSearchItem(prefName = "AntifraudRecheckEnabled", prefKey = "antifraud_recheck_enabled", title = "自动复查（推荐开）", category = "④ 扩展", page = "评论反诈", section = "评论反诈",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "自动复查（推荐开） ④ 扩展 评论反诈 AntifraudRecheckEnabled antifraud_recheck_enabled recommend"),
        SettingSearchItem(prefName = "AntifraudRecheckMinutes", prefKey = "antifraud_recheck_minutes", title = "复查监控时长", category = "④ 扩展", page = "评论反诈", section = "评论反诈",
            kind = SettingSearchItem.Kind.SLIDER_INT, default = com.a10miaomiao.bilimiao.comm.antifraud.CommentAntifraud.DEFAULT_RECHECK_MINUTES, keywords = "复查监控时长 ④ 扩展 评论反诈 AntifraudRecheckMinutes antifraud_recheck_minutes 分钟 秒 filter",
            slider = SettingSearchItem.SliderSpec.IntSlider(range = 1..30, steps = 28, valueText = { v -> Text("$v 分钟") })),
        SettingSearchItem(prefName = "AiSummaryEnabled", prefKey = "ai_summary_enabled", title = "AI 视频总结", category = "④ 扩展", page = "设置", section = "扩展",
            kind = SettingSearchItem.Kind.SWITCH, default = false, keywords = "AI 视频总结 ④ 扩展 设置 AiSummaryEnabled ai_summary_enabled"),
        SettingSearchItem(prefName = "WbiSignEnabled", prefKey = "wbi_sign_enabled", title = "WBI 签名", category = "④ 扩展", page = "设置", section = "扩展",
            kind = SettingSearchItem.Kind.SWITCH, default = true, keywords = "WBI 签名 ④ 扩展 设置 WbiSignEnabled wbi_sign_enabled wbi -352"),
        SettingSearchItem(prefName = "ImageDiskCacheSize", prefKey = "image_disk_cache_size", title = "图片缓存上限", category = "⑤ 账号与数据", page = "账号与存储", section = "存储",
            kind = SettingSearchItem.Kind.TEXT_INT, default = 50, keywords = "图片缓存上限 ⑤ 账号与数据 账号与存储 存储 ImageDiskCacheSize image_disk_cache_size cache 磁盘", label = " MB"),
        SettingSearchItem(prefName = "PlayerDiskCacheSize", prefKey = "player_disk_cache_size", title = "视频播放磁盘缓存", category = "⑤ 账号与数据", page = "账号与存储", section = "存储",
            kind = SettingSearchItem.Kind.TEXT_INT, default = 512, keywords = "视频播放磁盘缓存 ⑤ 账号与数据 账号与存储 存储 PlayerDiskCacheSize player_disk_cache_size cache 磁盘", label = " MB"),
        // ===== 以下为 Kind.LINK：搜索页改不了，点开进对应页面改 =====
        SettingSearchItem(prefName = "PlayerFnval", prefKey = "player_fnval", title = "视频格式选择", category = "① 播放", page = "播放器设置", section = "视频源设置",
            kind = SettingSearchItem.Kind.LINK, default = SettingConstants.PLAYER_FNVAL_DASH, keywords = "视频格式选择 ① 播放 播放器设置 视频源设置 PlayerFnval player_fnval 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "PlayerDashBufferSec", prefKey = "player_dash_buffer_sec", title = "播放缓冲时长", category = "① 播放", page = "播放器设置", section = "视频源设置",
            kind = SettingSearchItem.Kind.LINK, default = 15, keywords = "播放缓冲时长 ① 播放 播放器设置 视频源设置 PlayerDashBufferSec player_dash_buffer_sec 分钟 秒 filter 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "PlayerOpenMode", prefKey = "player_open_mode", title = "播放器自动控制", category = "① 播放", page = "播放器设置", section = "播放控制设置",
            kind = SettingSearchItem.Kind.LINK, default = SettingConstants.PLAYER_OPEN_MODE_DEFAULT, keywords = "播放器自动控制 ① 播放 播放器设置 播放控制设置 PlayerOpenMode player_open_mode 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "PlayerOrder", prefKey = "player_order", title = "播放器播放顺序", category = "① 播放", page = "播放器设置", section = "播放控制设置",
            kind = SettingSearchItem.Kind.LINK, default = SettingConstants.PLAYER_ORDER_DEFAULT, keywords = "播放器播放顺序 ① 播放 播放器设置 播放控制设置 PlayerOrder player_order 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "PlayerFullMode", prefKey = "player_full_mode", title = "全屏播放屏幕方向", category = "① 播放", page = "播放器设置", section = "播放控制设置",
            kind = SettingSearchItem.Kind.LINK, default = SettingConstants.PLAYER_FULL_MODE_AUTO, keywords = "全屏播放屏幕方向 ① 播放 播放器设置 播放控制设置 PlayerFullMode player_full_mode 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "PlayerBottomProgressBarShow", prefKey = "player_bottom_progress_bar_show", title = "底部进度条显示控制", category = "① 播放", page = "播放器设置", section = "播放控制设置",
            kind = SettingSearchItem.Kind.LINK, default = 0, keywords = "底部进度条显示控制 ① 播放 播放器设置 播放控制设置 PlayerBottomProgressBarShow player_bottom_progress_bar_show 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "PlayerSpeedValues", prefKey = "player_speed_values", title = "自定义倍速菜单", category = "① 播放", page = "播放器设置", section = "播放控制设置",
            kind = SettingSearchItem.Kind.LINK, default = SettingConstants.PLAYER_SPEED_SETS, keywords = "自定义倍速菜单 ① 播放 播放器设置 播放控制设置 PlayerSpeedValues player_speed_values 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "PlayerLongPressSpeed", prefKey = "player_long_press_speed", title = "长按倍速倍率", category = "① 播放", page = "播放器设置", section = "播放控制设置",
            kind = SettingSearchItem.Kind.LINK, default = 300, keywords = "长按倍速倍率 ① 播放 播放器设置 播放控制设置 PlayerLongPressSpeed player_long_press_speed 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "PlayerDoubleTapSeek", prefKey = "player_double_tap_seek", title = "快进/快退步长", category = "① 播放", page = "播放器设置", section = "播放控制设置",
            kind = SettingSearchItem.Kind.LINK, default = 0, keywords = "快进/快退步长 ① 播放 播放器设置 播放控制设置 PlayerDoubleTapSeek player_double_tap_seek 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "DownloadQualityMode", prefKey = "download_quality_mode", title = "默认下载画质", category = "① 播放", page = "播放器设置", section = "下载设置",
            kind = SettingSearchItem.Kind.LINK, default = 0, keywords = "默认下载画质 ① 播放 播放器设置 下载设置 DownloadQualityMode download_quality_mode 清晰度 原画 高清 流畅 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "LiveDefaultQuality", prefKey = "live_default_quality", title = "默认画质", category = "① 播放", page = "直播设置", section = "直播播放",
            kind = SettingSearchItem.Kind.LINK, default = SettingConstants.LIVE_DEFAULT_QUALITY_DEFAULT, keywords = "默认画质 ① 播放 直播设置 直播播放 LiveDefaultQuality live_default_quality 清晰度 原画 高清 流畅 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "LiveLinePolicy", prefKey = "live_line_policy", title = "默认线路策略", category = "① 播放", page = "直播设置", section = "直播播放",
            kind = SettingSearchItem.Kind.LINK, default = SettingConstants.LIVE_LINE_POLICY_DEFAULT, keywords = "默认线路策略 ① 播放 直播设置 直播播放 LiveLinePolicy live_line_policy cdn 节点 换线 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "LiveDanmakuAreaPercent", prefKey = "live_danmaku_area_percent", title = "弹幕显示区域", category = "① 播放", page = "直播设置", section = "直播弹幕",
            kind = SettingSearchItem.Kind.LINK, default = SettingConstants.LIVE_DANMAKU_AREA_PERCENT_DEFAULT, keywords = "弹幕显示区域 ① 播放 直播设置 直播弹幕 LiveDanmakuAreaPercent live_danmaku_area_percent danmaku 弹屏 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "HomeEntryView", prefKey = "home_entry_view", title = "首页入口", category = "② 界面", page = "首页设置", section = "首页顶部设置",
            kind = SettingSearchItem.Kind.LINK, default = SettingConstants.HOME_ENTRY_VIEW_DEFAULT, keywords = "首页入口 ② 界面 首页设置 首页顶部设置 HomeEntryView home_entry_view 主页 home 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "HomeRecommendListStyle", prefKey = "home_recommend_list_style", title = "列表样式", category = "② 界面", page = "首页设置", section = "推荐设置",
            kind = SettingSearchItem.Kind.LINK, default = 0, keywords = "列表样式 ② 界面 首页设置 推荐设置 HomeRecommendListStyle home_recommend_list_style 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "SettingPreferences.sponsorBlockSkipTypeKey(\"sponsor\").name", prefKey = "sponsor_block_skip_sponsor", title = "赞助/恰饭", category = "④ 扩展", page = "空降助手", section = "各类片段的处理方式",
            kind = SettingSearchItem.Kind.LINK, default = ( SponsorCategory.DEFAULT_SKIP_TYPES["sponsor"] ?: SponsorSkipType.Disable ).ordinal, keywords = "赞助/恰饭 ④ 扩展 空降助手 各类片段的处理方式 sponsorBlockSkipTypeKey(\"sponsor\").name sponsor_block_skip_sponsor 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "SettingPreferences.sponsorBlockSkipTypeKey(\"selfpromo\").name", prefKey = "sponsor_block_skip_selfpromo", title = "无偿/自我推广", category = "④ 扩展", page = "空降助手", section = "各类片段的处理方式",
            kind = SettingSearchItem.Kind.LINK, default = ( SponsorCategory.DEFAULT_SKIP_TYPES["selfpromo"] ?: SponsorSkipType.Disable ).ordinal, keywords = "无偿/自我推广 ④ 扩展 空降助手 各类片段的处理方式 sponsorBlockSkipTypeKey(\"selfpromo\").name sponsor_block_skip_selfpromo 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "SettingPreferences.sponsorBlockSkipTypeKey(\"exclusive_access\").name", prefKey = "sponsor_block_skip_exclusive_access", title = "独家访问/品牌合作", category = "④ 扩展", page = "空降助手", section = "各类片段的处理方式",
            kind = SettingSearchItem.Kind.LINK, default = ( SponsorCategory.DEFAULT_SKIP_TYPES["exclusive_access"] ?: SponsorSkipType.Disable ).ordinal, keywords = "独家访问/品牌合作 ④ 扩展 空降助手 各类片段的处理方式 sponsorBlockSkipTypeKey(\"exclusive_access\").name sponsor_block_skip_exclusive_access 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "SettingPreferences.sponsorBlockSkipTypeKey(\"interaction\").name", prefKey = "sponsor_block_skip_interaction", title = "三连/互动提醒", category = "④ 扩展", page = "空降助手", section = "各类片段的处理方式",
            kind = SettingSearchItem.Kind.LINK, default = ( SponsorCategory.DEFAULT_SKIP_TYPES["interaction"] ?: SponsorSkipType.Disable ).ordinal, keywords = "三连/互动提醒 ④ 扩展 空降助手 各类片段的处理方式 sponsorBlockSkipTypeKey(\"interaction\").name sponsor_block_skip_interaction 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "SettingPreferences.sponsorBlockSkipTypeKey(\"poi_highlight\").name", prefKey = "sponsor_block_skip_poi_highlight", title = "精彩时刻", category = "④ 扩展", page = "空降助手", section = "各类片段的处理方式",
            kind = SettingSearchItem.Kind.LINK, default = ( SponsorCategory.DEFAULT_SKIP_TYPES["poi_highlight"] ?: SponsorSkipType.Disable ).ordinal, keywords = "精彩时刻 ④ 扩展 空降助手 各类片段的处理方式 sponsorBlockSkipTypeKey(\"poi_highlight\").name sponsor_block_skip_poi_highlight 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "SettingPreferences.sponsorBlockSkipTypeKey(\"intro\").name", prefKey = "sponsor_block_skip_intro", title = "开场动画", category = "④ 扩展", page = "空降助手", section = "各类片段的处理方式",
            kind = SettingSearchItem.Kind.LINK, default = ( SponsorCategory.DEFAULT_SKIP_TYPES["intro"] ?: SponsorSkipType.Disable ).ordinal, keywords = "开场动画 ④ 扩展 空降助手 各类片段的处理方式 sponsorBlockSkipTypeKey(\"intro\").name sponsor_block_skip_intro 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "SettingPreferences.sponsorBlockSkipTypeKey(\"outro\").name", prefKey = "sponsor_block_skip_outro", title = "片尾/鸣谢", category = "④ 扩展", page = "空降助手", section = "各类片段的处理方式",
            kind = SettingSearchItem.Kind.LINK, default = ( SponsorCategory.DEFAULT_SKIP_TYPES["outro"] ?: SponsorSkipType.Disable ).ordinal, keywords = "片尾/鸣谢 ④ 扩展 空降助手 各类片段的处理方式 sponsorBlockSkipTypeKey(\"outro\").name sponsor_block_skip_outro 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "SettingPreferences.sponsorBlockSkipTypeKey(\"preview\").name", prefKey = "sponsor_block_skip_preview", title = "回顾/概要", category = "④ 扩展", page = "空降助手", section = "各类片段的处理方式",
            kind = SettingSearchItem.Kind.LINK, default = ( SponsorCategory.DEFAULT_SKIP_TYPES["preview"] ?: SponsorSkipType.Disable ).ordinal, keywords = "回顾/概要 ④ 扩展 空降助手 各类片段的处理方式 sponsorBlockSkipTypeKey(\"preview\").name sponsor_block_skip_preview 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "SettingPreferences.sponsorBlockSkipTypeKey(\"padding\").name", prefKey = "sponsor_block_skip_padding", title = "填充内容/前黑后黑", category = "④ 扩展", page = "空降助手", section = "各类片段的处理方式",
            kind = SettingSearchItem.Kind.LINK, default = ( SponsorCategory.DEFAULT_SKIP_TYPES["padding"] ?: SponsorSkipType.Disable ).ordinal, keywords = "填充内容/前黑后黑 ④ 扩展 空降助手 各类片段的处理方式 sponsorBlockSkipTypeKey(\"padding\").name sponsor_block_skip_padding 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "SettingPreferences.sponsorBlockSkipTypeKey(\"filler\").name", prefKey = "sponsor_block_skip_filler", title = "离题闲聊", category = "④ 扩展", page = "空降助手", section = "各类片段的处理方式",
            kind = SettingSearchItem.Kind.LINK, default = ( SponsorCategory.DEFAULT_SKIP_TYPES["filler"] ?: SponsorSkipType.Disable ).ordinal, keywords = "离题闲聊 ④ 扩展 空降助手 各类片段的处理方式 sponsorBlockSkipTypeKey(\"filler\").name sponsor_block_skip_filler 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "SettingPreferences.sponsorBlockSkipTypeKey(\"music_offtopic\").name", prefKey = "sponsor_block_skip_music_offtopic", title = "音乐:非音乐部分", category = "④ 扩展", page = "空降助手", section = "各类片段的处理方式",
            kind = SettingSearchItem.Kind.LINK, default = ( SponsorCategory.DEFAULT_SKIP_TYPES["music_offtopic"] ?: SponsorSkipType.Disable ).ordinal, keywords = "音乐:非音乐部分 ④ 扩展 空降助手 各类片段的处理方式 sponsorBlockSkipTypeKey(\"music_offtopic\").name sponsor_block_skip_music_offtopic 点开对应页面改 不能在搜索页改 link 去设置页"),
        SettingSearchItem(prefName = "ThreadRipperThreads", prefKey = "thread_ripper_threads", title = "并发连接数", category = "④ 扩展", page = "海外加速", section = "海外加速（分段并发下载）",
            kind = SettingSearchItem.Kind.LINK, default = 4, keywords = "并发连接数 ④ 扩展 海外加速 海外加速（分段并发下载） ThreadRipperThreads thread_ripper_threads 分段 加速 卡顿 点开对应页面改 不能在搜索页改 link 去设置页"),
    )

    /** 关键词搜索：空串返回空列表（调用方据此决定是显示 6 大类还是搜索结果） */
    fun search(query: String): List<SettingSearchItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        // 标题命中的排前面，其余按标题长度（更短的通常更相关）
        return items.filter { it.matches(q) }
            .sortedWith(compareByDescending<SettingSearchItem> { it.title.lowercase().contains(q.lowercase()) }
                .thenBy { it.title.length })
    }
}
