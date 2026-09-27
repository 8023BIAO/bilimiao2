package cn.a10miaomiao.bilimiao.compose.pages.home.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.components.dialogs.AutoSheetDialog
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.live.LiveAPI
import com.a10miaomiao.bilimiao.comm.live.entity.LiveAreaGroup

/**
 * 直播筛选弹窗（**排序在最上面，下面才是两级分区**：顶级分区 → 子分区）。
 *
 * ## 布局与交互照谁抄的
 * `HomeBangumiFilterSheet.kt`（影视/番剧首页那个筛选弹窗，用户原话"可以直接复用布局呀"）：
 *   - 外壳同样是 [AutoSheetDialog]（窄屏从底部升起、宽屏居中），`modifier` 也照抄它的
 *     `padding(top = 12.dp, bottom = 12.dp)`；
 *   - 底部同样固定一行按钮，中间内容区**吃满剩余高度**（`weight(1f)`）；
 *   - 「确定」把结果**回传父级**，由父级关掉弹窗并刷新卡片
 *     （番剧那边是 `viewModel.applyFilter(it)`，见 HomeBangumiContent.kt:411-419；
 *      这边是 `HomeLiveContent` 的 `onApply` → 改选中的分区 id → 重建/刷新房间列表）。
 *   多了一个「取消」：番剧那边点外部/返回也能关，这里给个显式出口，和弹窗内的一堆 chip 区分开。
 *
 * ## 与番剧那边两点必要的不同
 * 1. **条件是本地的两级树**，不是接口给的平铺条件表：选中顶级分区后，子分区列表跟着整组换，
 *    所以这里要自己算"当前顶级分区下的子分区"（数据就是浏览页一直在用的 `LiveAreaGroup`）。
 * 2. **内容区用 [LazyVerticalGrid] 而不是 `Column + verticalScroll`**：
 *    子分区实测最多 195 个（手游），全量组合会明显卡顿（旧版顶部标签条为此专门用 LazyRow）；
 *    网格是真懒加载，"只在可见时才组合"。顶级分区只有 13 个，仍然是 FlowRow 平铺。
 *
 * ## 为什么「全部」在两级里都要有
 * 顶级选「全部」= 全站热门（`parent_area_id=0&area_id=0`，实测有内容）；
 * 顶级选了具体分区后，子分区第一项也是「全部」= 这个顶级分区下的全部房间（`area_id=0`）。
 * 两级都停在「全部」= 回到改动前"全部"那个标签的内容。
 *
 * ## 排序为什么搬到这里、为什么放在最上面（用户要求）
 * 用户原话："我想在直播的 Tab 首页底栏筛选的那个，在最上面，就是在全部分类的上面，按排序说
 * 排序是热度排序或者是最新排序。这样我们就不用去到设置里面了。"
 * 于是原来「设置 → 播放 → 直播设置 → 默认排序」那一项搬到这里（设置页那一项由另一路删除），
 * 用户在看列表的地方就能直接切换排序。
 *
 * ★**值与键都没变**：仍然写 `SettingPreferences.LiveSortType`（键字符串 `live_sort_type`），
 *   取值仍然是接口 `sort_type` 原值 `online`(热度) / `live_time`(最新) ——
 *   搬的是**入口**，不是数据格式。这样已经设过"最新开播"的老用户升上来，
 *   弹窗里默认选中的就是"最新排序"，看到的内容不会因为这次改动而变化。
 *
 * ★放在**分类之上**：用户明确要求"在全部分类的上面"，而且排序作用于**整个**列表（与选哪个分区无关），
 *   是比"看哪一类"更外层的条件，放最上面也符合"从粗到细"的阅读顺序。
 *
 * ★排序和分类**一起回传、一起生效**：点「确定」时两个条件在同一个回调里交给父级
 *   （见 [onApply]），父级换 VM → 一条请求同时带上 `parent_area_id`/`area_id`/`sort_type`，
 *   不会出现"只按新排序拉了全站、却把用户选的分区丢了"那种半生效。
 *
 * ## 第六阶段：顶级分区那一排多了个「推荐」，排在「全部」**之前**
 * 用户原话："皮皮 Plus 它直播有一个推荐的 Tab……我想添加在那个全部 tag 那上面，前面就它前面
 * 写一个推荐，然后去推荐之后这些都是应该有系统的 API 分流推荐给我们，我们用它的就行。
 * 然后如果用户想自定义化它会去选择其他的分类什么的。"
 *
 * 所以这里只是**在既有的那一排 chip 前面插了一项**（值 = [LIVE_PARENT_RECOMMEND]），
 * 交互跟其它 chip 完全一样（同一个 `FilterChip`、同一个"选中态"、同一个「确定」才生效），
 * 用户不用为它学一套新操作。三点必要的差别：
 *   1. 它**不是分区**：选中后不显示"子分区"那一层（子分区只属于真实分区），
 *      顶部"当前：…"也不拼排序名（推荐流不吃 `sort_type`，拼上去等于撒谎）；
 *   2. 选中时多一行说明，讲清"系统下发、不受分区/排序影响、想自己挑就选下面的分区"；
 *   3. 「重置」仍然回到「全部」（默认视图），不把"推荐"变成第二种默认 —— 默认观感不变。
 *
 * ★默认值/既有观感都没动：弹窗一打开高亮的还是用户原来那一个（没选过就是「全部」），
 *   「推荐」只是多出来的、排在第一个的可选项。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeLiveFilterSheet(
    groups: List<LiveAreaGroup>,
    currentParentId: Int,
    currentAreaId: Int,
    currentSortType: String,
    onApply: (parentId: Int, areaId: Int, sortType: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 弹窗里的"待确认"选择：点「确定」才回传父级；中途反悔直接关掉，列表不受影响
    var tempParentId by remember { mutableStateOf(currentParentId) }
    var tempAreaId by remember { mutableStateOf(currentAreaId) }
    // 排序也是"待确认"：和分区一个规矩 —— 只在弹窗里改，点「确定」才真的生效
    var tempSortType by remember { mutableStateOf(currentSortType) }

    // 顶级分区：推荐 + 全部 + 接口给的 12 个（顺序照接口）。
    // ★第六阶段：「推荐」在**最前面**（用户原话："我想添加在那个全部 tag 那上面，前面就它前面
    //   写一个推荐"），而且它是一个**独立选项**（[LIVE_PARENT_RECOMMEND]，不是分区之一）——
    //   它不对应接口里任何一个 parent_area_id，点它走的是 B 站推荐流接口。
    //   顺序照 PiliPlus：`lib/pages/live/view.dart:105-128` 里标签条的第一个格子恒为「推荐」，
    //   第二个起才是分区列表。
    val parentItems = remember(groups.toList()) {
        listOf(
            LIVE_PARENT_RECOMMEND to LIVE_RECOMMEND_LABEL,
            LiveAPI.AREA_ALL to "全部",
        ) + groups.map { it.id to it.name }
    }

    /**
     * 当前顶级分区下的子分区：全部 + 该分区的子分区。
     *
     * ★子分区 id 接口给的是字符串（"86"），转不成数字的直接丢掉 —— 宁可少一项，
     *   也不要拿 0 去请求（0 的含义是"全部"，会把别的房间塞到这个选择下面）。
     *   再按 id 去一次重：LazyGrid 的 key 撞了会直接抛 IllegalArgumentException 崩页面。
     *   （这两条和旧版顶部标签条里的处理完全一致。）
     */
    val subItems = remember(groups.toList(), tempParentId) {
        val group = groups.firstOrNull { it.id == tempParentId }
        listOf(0 to "全部") + group?.list.orEmpty()
            .mapNotNull { area -> area.id.toIntOrNull()?.let { it to area.name } }
            .distinctBy { it.first }
    }

    // 顶部那行"当前选中了什么"：弹窗一打开就知道自己在哪一层、按什么排序，不用回列表里猜。
    // ★要把 tempSortType 也算进去：它是"待确认"值，用户在弹窗里点了「最新排序」但还没点确定时，
    //   这一行必须跟着变，否则用户会以为"点了没反应"
    // ★第六阶段：「推荐」下面**不拼排序名** —— 推荐流的请求里根本没有 `sort_type`
    //   （排序由服务端的画像分流决定），显示成"推荐 · 热度排序"就是在撒谎
    //   （用户会以为推荐是按热度排的、甚至会以为排序没生效）。这里直说它是系统推荐流。
    val currentText = remember(parentItems, subItems, tempParentId, tempAreaId, tempSortType) {
        if (tempParentId == LIVE_PARENT_RECOMMEND) {
            "$LIVE_RECOMMEND_LABEL · 系统推荐流"
        } else {
            val parentName = parentItems.firstOrNull { it.first == tempParentId }?.second ?: "全部"
            val subName = subItems.firstOrNull { it.first == tempAreaId }?.second
            val areaText = if (tempAreaId == 0 || subName.isNullOrBlank()) parentName else "$parentName · $subName"
            "$areaText · ${sortLabelOf(tempSortType)}"
        }
    }

    AutoSheetDialog(
        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // 标题从"直播分类"改成"直播筛选"：弹窗里现在不止分类，还有排序，
            // 继续叫"分类"就是名不副实（用户进来找排序会以为走错了地方）
            Text(
                text = "直播筛选",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "当前：$currentText",
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 可滚动内容区（吃满剩余高度，底部按钮始终钉在下面 —— 与番剧筛选弹窗同一结构）。
            // 自适应列数：格子宽 104dp，够放四个汉字 + 内边距，手机 3 列、平板/横屏更多
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // ★排序在最上面（用户要求"在全部分类的上面"）：
                //   它对**整个**列表生效，跟选哪个分区无关，是比分类更外层的条件。
                //   交互与下面两级分区完全一致（同一款 FilterChip、同一个"选中态"），
                //   用户不需要为排序单独学一套操作。
                item(
                    key = "sort_label",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    SectionLabel("排序")
                }
                item(
                    key = "sorts",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SORT_OPTIONS.forEach { (value, label) ->
                            FilterChip(
                                selected = value == tempSortType,
                                onClick = { tempSortType = value },
                                label = { Text(label) },
                            )
                        }
                    }
                }

                item(
                    key = "parent_label",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    SectionLabel("顶级分区")
                }
                item(
                    key = "parents",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        parentItems.forEach { (id, name) ->
                            FilterChip(
                                selected = id == tempParentId,
                                onClick = {
                                    if (id != tempParentId) {
                                        tempParentId = id
                                        // 顶级一变，子分区必须回到「全部」：留着上一个分区的 area_id
                                        // 会拿到一个不属于这个顶级分区的房间列表
                                        // （接口只校验存在性，不校验从属关系 —— 同旧版标签条的处理）
                                        tempAreaId = 0
                                    }
                                },
                                label = { Text(name) },
                            )
                        }
                    }
                }

                // 选了具体的顶级分区才显示子分区（「全部」下面没有子分区这一层）。
                // ★第六阶段：「推荐」下也**不能**显示这一层 —— 它不对应任何分区，
                //   漏了这个条件就会在"推荐"下面挂出一排只有「全部」一项的空子分区
                //   （看着像坏了，点了还会把选择变成"全部"）。
                if (tempParentId != LiveAPI.AREA_ALL && tempParentId != LIVE_PARENT_RECOMMEND) {
                    item(
                        key = "sub_label",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        SectionLabel("子分区")
                    }
                    items(
                        items = subItems,
                        key = { it.first },
                    ) { (id, name) ->
                        FilterChip(
                            selected = id == tempAreaId,
                            onClick = { tempAreaId = id },
                            // 撑满格子：长短名字的 chip 宽度一致，网格看起来才齐
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    text = name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }

                // 选中「推荐」时补一句说明（只在选中时出现，不占别人地方）：
                // ① 告诉用户这不是某个分区，而是 B 站系统推荐的流（用户提需求时的原话就是
                //    "系统的 API 分流推荐给我们"）；② 顺手说清"排序对它不起作用"，
                //    免得用户在上面点了「最新排序」却发现列表没变，以为弹窗坏了；
                // ③ 给"想自己挑"的用户指路 —— 用户原话："如果用户想自定义化它会去选择其他的分类"。
                if (tempParentId == LIVE_PARENT_RECOMMEND) {
                    item(
                        key = "recommend_hint",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        Text(
                            text = "「$LIVE_RECOMMEND_LABEL」由 B 站推荐接口下发，" +
                                "不受分区和排序影响；想自己挑分类就点上面的分区。",
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 底部按钮：与番剧筛选弹窗一致（左「重置」右「确定」，中间多一个「取消」）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                TextButton(
                    onClick = {
                        // 重置 = 回到「全部 / 全部 / 热度排序」（不是回到"打开弹窗时那一个"，那样用户没法一键清掉筛选）。
                        // ★排序也一起重置成默认值：它现在和分区同属这个弹窗的筛选条件，
                        //   "重置"却单独漏掉排序的话，用户点了重置还以为回到了默认视图。
                        // ★第六阶段「推荐」**不进重置目标**：重置的语义是"回到默认视图"，
                        //   而默认视图就是「全部」那张榜单（默认值一个字没改，用户要求）。
                        //   想用推荐的人点一下第一个 chip 就有了，重置把它当成"推荐"反而会让
                        //   "重置"在不同人眼里有两种含义。
                        tempParentId = LiveAPI.AREA_ALL
                        tempAreaId = 0
                        tempSortType = SettingPreferences.Live.LIVE_SORT_TYPE_ONLINE
                    }
                ) {
                    Text("重置")
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        // ★先把选择回传（父级改选中 → 重建/刷新列表），再关弹窗；
                        //   顺序反过来会出现"弹窗关了但列表还是旧的"那一帧。
                        //   排序和分区**在同一个回调里**交出去 —— 两个条件必须同时生效
                        onApply(tempParentId, tempAreaId, tempSortType)
                        onDismiss()
                    }
                ) {
                    Text("确定")
                }
            }
        }
    }
}

/** 分区标题（「排序」/「顶级分区」/「子分区」）：比正文小一号的灰字，和番剧筛选弹窗的分组标题同一观感 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 排序选项：**值就是接口 `sort_type` 的原值**（不是"数字 ↔ 名字"的翻译表）。
 *
 * ★为什么直接引用 `SettingPreferences.Live` 里的两个常量、不在这里另抄一份字面量：
 *   这两个字符串要**原样**发给接口、又要**原样**存进 `live_sort_type`，
 *   抄第二份的那天就会有人只改一处（比如把 online 写成 hot）—— 而那正是"静默返回 0 条"的经典事故
 *   （实测 `sort_type` 传非法值/空串接口不报错、直接给空列表）。
 *   这两个常量与 `LiveAPI.SORT_ONLINE` / `LiveAPI.SORT_LIVE_TIME` 是同一批字符串。
 *
 * ★文案用用户自己的说法（"热度排序 / 最新排序"）：设置页原来的叫法是"按人气 / 按最新开播"，
 *   用户提需求时说的是"排序是热度排序或者是最新排序"，入口搬过来就照用户的词写，
 *   他在弹窗里一眼能对上自己说的话（值本身一个字符都没动）。
 */
private val SORT_OPTIONS = listOf(
    SettingPreferences.Live.LIVE_SORT_TYPE_ONLINE to "热度排序",
    SettingPreferences.Live.LIVE_SORT_TYPE_LIVE_TIME to "最新排序",
)

/**
 * 排序值 → 显示名（顶部"当前：…"那一行用）。
 *
 * 认不出来的值一律显示热度排序的名字：读取链路上 `sortTypeOrOnline` 已经把非法值兜底成 `online`，
 * 这里再兜一次，保证"显示的名字"和"真正发出去的参数"永远一致（不会显示"最新排序"却按热度请求）。
 */
private fun sortLabelOf(sortType: String): String =
    SORT_OPTIONS.firstOrNull { it.first == sortType }?.second ?: SORT_OPTIONS.first().second
