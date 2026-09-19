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
import me.zhanghai.compose.preference.switchPreference
import org.kodein.di.compose.rememberInstance

/**
 * 「分段并发下载」（开关本体叫「启用分段并发下载」，来自上游 Bilibili-thread-ripper）的
 * **并发子设置页**（入口：设置 → 实验性功能 → 海外加速（分段并发下载）→ 并发设置）。
 *
 * 名字说明：它切的是**字节范围 / 连接数**，不是 CPU 线程；手机的核数上限只有 8~16，
 * 叫「线程撕裂者」会让人以为是拼 CPU，所以用户可见文案统一成「分段并发下载」。
 * 代码里的类名/键名仍然是 `ThreadRipper*`（与上游一致，方便对照）。
 *
 * 两个设置项（用户要求）：
 *  1. **自动并发**（开关，默认开）——连接数按分段大小自适应；开启时下面那根滑块**置灰不可拖**，
 *     但它的档位仍然生效：作为自动模式的**上限**（"自动并发就是取你最多设置的那个数"）。
 *  2. **并发连接数（手动档）**（滑块）——档位 = 不限 / 1 / 2 / … / 本机处理器核数。
 *     关掉自动并发后可以拖：选几就是几个并发连接；选「不限」= 由程序按分段大小自适应。
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
        val autoThreads =
            (preferences[SettingPreferences.ThreadRipperAutoThreads.name] as? Boolean) ?: true
        // 视频格式是 MP4 时，分段并发下载对它无效（MP4 整段顺序下载、没有分段可切）→ 这里整页置灰
        val fnvalValue = (preferences[SettingPreferences.PlayerFnval.name] as? Int)
            ?: SettingConstants.PLAYER_FNVAL_DASH
        val mp4Selected = fnvalValue == SettingConstants.PLAYER_FNVAL_MP4
        val threadValue =
            (preferences[SettingPreferences.ThreadRipperThreads.name] as? Int) ?: 0

        fun labelOf(value: Int): String = if (value <= 0) "不限" else "$value 条连接"

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
            switchPreference(
                key = SettingPreferences.ThreadRipperAutoThreads.name,
                defaultValue = true,
                enabled = { !mp4Selected },
                title = { Text("自动并发") },
                summary = {
                    if (it) {
                        Text("开启：按分段大小自适应连接数，上限取下面的「并发连接数」（当前上限：${labelOf(threadValue)}）")
                    } else {
                        Text("关闭：完全按下面的「并发连接数」固定连接数")
                    }
                },
            )
            sliderIntPreference(
                key = SettingPreferences.ThreadRipperThreads.name,
                defaultValue = 0,
                valueRange = 0..maxThreads,
                // zhanghai 的 SliderPreference：steps = 两端点之间的档位数，故为 (end - start - 1)
                valueSteps = (maxThreads - 1).coerceAtLeast(0),
                // ★ 自动线程开着时这根滑块置灰（用户要求）：值仍然作为自动模式的上限生效；
                //   MP4 源整页置灰（分段并发下载对 MP4 无效）
                enabled = { !autoThreads && !mp4Selected },
                title = { Text("并发连接数（手动档）") },
                valueText = { value -> Text(labelOf(value)) },
                summary = { value ->
                    Text(
                        when {
                            autoThreads ->
                                "已置灰：自动并发开着。它只作为自动模式的上限（当前 ${labelOf(value)}）；" +
                                    "想手动固定连接数，请先关掉上面的「自动并发」"
                            value <= 0 ->
                                "不限：由程序按分段大小自适应，最多到本机 $maxThreads 条连接"
                            else ->
                                "固定 $value 个连接并发下载（本机最多 $maxThreads）"
                        }
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
                        "一般不用动：默认「自动并发」就能覆盖大多数情况。\n" +
                            "缓冲还是跟不上（4K / 冷门视频）再手动调大；设备较老或网络本身就抖，往小调。\n" +
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
