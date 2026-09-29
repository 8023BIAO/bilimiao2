package cn.a10miaomiao.bilimiao.compose.pages.live

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.a10miaomiao.bilimiao.comm.store.AppStore
import org.kodein.di.compose.rememberInstance

/**
 * 直播卡片列表的「每行卡片数」——设置 → 播放 → 直播设置 → 直播列表 → 每行卡片数。
 *
 * ★为什么要有这个函数：该设置原本**只在首页「直播」Tab 生效**（那边自己读 `liveSetting`），
 *   而直播搜索页 / 搜索页的直播结果 / 关注直播列表三处都写死 `GridCells.Adaptive(300.dp)`，
 *   于是把设置调成 2 列时那三处照样 1 列 —— 同一个设置在不同页面表现不一致。
 *   四处各抄一遍读取代码属于"开第二份"，所以抽到这里：
 *   **以后任何直播卡片网格都用它，不要再自己写一遍。**
 *
 * 语义与设置项一致：`0` = 自适应（默认，按屏宽铺，手机通常 1 列、平板/横屏多列）；
 * `1..5` = 固定列数。用法：
 * ```
 * val gridSpan = rememberLiveGridSpan()
 * LazyVerticalGrid(columns = if (gridSpan == 0) GridCells.Adaptive(300.dp) else GridCells.Fixed(gridSpan))
 * ```
 */
@Composable
internal fun rememberLiveGridSpan(): Int {
    val appStore: AppStore by rememberInstance()
    return appStore.stateFlow.collectAsStateWithLifecycle().value.live.gridSpan
}
