package cn.a10miaomiao.bilimiao.compose.pages.setting

import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.preference.rememberPreferenceFlow
import cn.a10miaomiao.bilimiao.compose.components.preference.sliderIntPreference
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.preference
import me.zhanghai.compose.preference.preferenceCategory
import org.kodein.di.compose.rememberInstance

/**
 * 「分段并发下载」（开关本体叫「启用分段并发下载」，来自上游 Bilibili-thread-ripper）的
 * **并发子设置页**（入口：设置 → 实验性功能 → 海外加速（分段并发下载）→ 并发设置）。
 *
 * 名字说明：它切的是**字节范围 / 连接数**，不是 CPU 线程；手机的核数上限只有 8~16，
 * 叫「线程撕裂者」会让人以为是拼 CPU，所以用户可见文案统一成「分段并发下载」。
 * 代码里的类名/键名仍然是 `ThreadRipper*`（与上游一致，方便对照）。
 *
 * **只有一个设置项**（vc104 起，完全照上游 Bilibili-thread-ripper 的模型）：
 *  「并发连接数」滑块 —— 档位 = 不限 / 1 / 2 / … / 本机处理器核数，**默认 4**。
 *  程序把这次请求的字节区间**平均分给 N 条连接**，唯一的下限是"每份至少 64KB"
 *  （上游 `range-core.js` 的 `minChunkBytes`），分段小就自动少开几条。
 *
 * 为什么删掉了原来的「自动并发」开关：上游本来就只有这一个档位（`concurrency`），
 * 我们那个开关和它语义重叠，开着的时候这根滑块还会置灰，反而让人不知道怎么调。
 *
 * 上限用的是 `Runtime.getRuntime().availableProcessors()`（手机给到的最大并发数），
 * 与上游项目一致：连接不是越多越快，连接/加密/调度/重组的开销会一起涨。
 */
@Serializable
class ThreadRipperSettingPage : ComposePage() {

    @Composable
    override fun Content() {
        ThreadRipperSettingPageContent()
    }
}

@Composable
private fun ThreadRipperSettingPageContent() {
    PageConfig(title = "分段并发下载")

    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsState().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val context = LocalContext.current
    val dataStore = remember {
        SettingPreferences.run { context.dataStore }
    }
    val prefFlow = rememberPreferenceFlow(dataStore)
    // 本机最多能开多少线程 = 处理器核数（滑块上限就是它）
    val maxThreads = remember {
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    }

    ProvidePreferenceLocals(flow = prefFlow) {
        val preferences = prefFlow.collectAsState().value
        // 视频格式是 MP4 时，分段并发下载对它无效（MP4 整段顺序下载、没有分段可切）→ 这里整页置灰
        val fnvalValue = (preferences[SettingPreferences.PlayerFnval.name] as? Int)
            ?: SettingConstants.PLAYER_FNVAL_DASH
        val mp4Selected = fnvalValue == SettingConstants.PLAYER_FNVAL_MP4
        val threadValue =
            (preferences[SettingPreferences.ThreadRipperThreads.name] as? Int) ?: 4

        fun labelOf(value: Int): String =
            if (value <= 0) "不限（= 本机 $maxThreads 条）" else "$value 条连接"

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

            if (mp4Selected) {
                // 说明性条目：不可点，只解释为什么这里的开关都灰了
                preference(
                    key = "tr_mp4_notice",
                    title = { Text("当前是 MP4 源，分段并发下载对它无效") },
                    enabled = false,
                    summary = {
                        Text(
                            "MP4（含 durl 直链、[merging]/[concatenating] 源）在 ExoPlayer 里是「整段顺序下载」，" +
                                "没有字节分段可以切，所以并发连接也帮不上忙；\n" +
                                "想用它请到 播放设置 → 视频格式选择 改成 DASH（DASH 的分段流才有 Range 可并发）。"
                        )
                    },
                )
            }
            preferenceCategory(key = "tr_threads", title = { Text("并发") })
            sliderIntPreference(
                key = SettingPreferences.ThreadRipperThreads.name,
                defaultValue = 4,
                valueRange = 0..maxThreads,
                // zhanghai 的 SliderPreference：steps = 两端点之间的档位数，故为 (end - start - 1)
                valueSteps = (maxThreads - 1).coerceAtLeast(0),
                // MP4 源下整页置灰（分段并发下载对 MP4 无效）；其余情况这根滑块就是唯一开关
                enabled = { !mp4Selected },
                title = { Text("并发连接数") },
                valueText = { value -> Text(labelOf(value)) },
                summary = { value ->
                    // 把"这个档位实际会发生什么"直接算给用户看（上游的算法：区间平均等分，每份至少 64KB）
                    val n = if (value <= 0) maxThreads else value
                    Text(
                        "当前：${labelOf(value)}。把一个分段的字节区间平均分给 $n 条连接" +
                            "（每份至少 64KB，分段小就自动少开）；" +
                            "默认 4 条，缓冲跟不上再往上加；连接不是越多越快，手机一般 4~8 就够"
                    )
                },
            )

            preferenceCategory(key = "tr_help", title = { Text("说明") })
            preference(
                key = "tr_help_when",
                title = { Text("什么时候该调这里") },
                enabled = false,
                summary = {
                    Text(
                        "这个数字就是**最多同时开几条连接**（和上游 Bilibili-thread-ripper 的「并发线程」是同一个意思）。\n" +
                            "海外/冷门视频、4K 缓冲跟不上 → 往大调（8~16）；设备较老或网络本身就抖 → 往小调。\n" +
                            "连接不是越多越快：连接、加密、调度和重组的开销会一起增加。"
                    )
                },
            )
            preference(
                key = "tr_help_cdn",
                title = { Text("和 CDN 设置的关系") },
                enabled = false,
                summary = {
                    Text(
                        "互不干扰：上面的「CDN 竞速 / CDN 固定主机 / 音频不跟随 CDN」照旧生效，" +
                            "它们决定用哪个节点，本功能只决定节点上的字节怎么并发拉。\n" +
                            "所以开不开分段并发下载，都**不需要**改你的 CDN 选择。"
                    )
                },
            )

            item("bottom") {
                Spacer(
                    modifier = Modifier.height(
                        windowInsets.bottomDp.dp + windowStore.bottomAppBarHeightDp.dp + 24.dp
                    )
                )
            }
        }
    }
}
