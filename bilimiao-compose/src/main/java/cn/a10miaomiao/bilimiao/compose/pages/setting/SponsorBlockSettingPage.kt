package cn.a10miaomiao.bilimiao.compose.pages.setting

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.common.preference.rememberPreferenceFlow
import cn.a10miaomiao.bilimiao.compose.components.preference.textIntPreference
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorCategory
import com.a10miaomiao.bilimiao.comm.entity.sponsor.SponsorSkipType
import com.a10miaomiao.bilimiao.comm.sponsor.SponsorBlockSettingsUi
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.listPreference
import me.zhanghai.compose.preference.preference
import me.zhanghai.compose.preference.preferenceCategory
import me.zhanghai.compose.preference.switchPreference
import org.kodein.di.compose.rememberInstance

/**
 * 空降助手的**独立设置页**。
 *
 * 为什么单独开一页（而不是继续堆在「播放设置」里）：
 *  - 空降助手自己有 1 个总开关 + 11 个类别策略 + 时长/提示/上报/服务端/颜色/用户ID/状态，
 *    全塞进播放设置会把那里撑成一锅粥（用户原话："太乱太杂了"）；
 *  - 独立成页后**入口永远可进**，不会因为总开关关了就找不到设置（用户明确要求）。
 *
 * 总开关关闭时：下面的子项**全部灰掉、点不动**（不是隐藏）。
 * 用户报过"关了空降助手，这几个设置还能点"——隐藏会让人以为设置丢了，灰掉才说明
 * "功能关着，所以这些现在不用改"。
 *
 * 实现细节：`switchPreference` / `listPreference` 的 `enabled` 参数是 **`(T) -> Boolean`**
 * （不是 `Boolean`），所以必须写成 `enabled = { sponsorEnabled }`；
 * 基础项 `preference` 的 `enabled` 是普通 `Boolean`。这套用法照抄本项目的 `DanmakuSettingPage`。
 */
@Serializable
class SponsorBlockSettingPage : ComposePage() {

    @Composable
    override fun Content() {
        SponsorBlockSettingPageContent()
    }
}

@Composable
private fun SponsorBlockSettingPageContent() {
    PageConfig(title = "空降助手")

    // 离开本页时把还开着的覆盖层弹窗（昵称/私人ID/颜色…）一起收掉：
    // 它们是挂在 Activity 上的原生 Dialog，页面没了弹窗还在就会 WindowLeaked
    DisposableEffect(Unit) {
        onDispose { SponsorBlockSettingsUi.dismissAll() }
    }

    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val context = LocalContext.current
    val dataStore = remember {
        SettingPreferences.run { context.dataStore }
    }
    val prefFlow = rememberPreferenceFlow(dataStore)

    ProvidePreferenceLocals(flow = prefFlow) {
        val preferences = prefFlow.collectAsStateWithLifecycle().value
        // 总开关（默认开，与播放器侧 PlayerController 的读取默认值保持一致）
        val sponsorEnabled =
            (preferences[SettingPreferences.SponsorBlockEnable.name] as? Boolean) ?: true

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

            // ── 总开关 ──
            preferenceCategory(key = "sponsor_master", title = { Text("空降助手") })
            switchPreference(
                key = SettingPreferences.SponsorBlockEnable.name,
                title = { Text("启用空降助手") },
                summary = {
                    if (it) {
                        Text("播放时按下面的策略自动跳过赞助/恰饭/片头片尾等片段")
                    } else {
                        Text("已关闭：不会向第三方服务查询，也不画片段色块")
                    }
                },
                defaultValue = true,
            )
            if (!sponsorEnabled) {
                // 关着的时候给一句人话，免得用户以为下面的项坏了、点不动
                preference(
                    key = "sponsor_master_hint",
                    title = { Text("下面这些设置暂时不可改") },
                    enabled = false,
                    summary = { Text("把上面的「启用空降助手」打开即可调整") },
                )
            }

            // ── 各类别策略（PiliPlus 的 SkipType：禁用/仅显示/手动/跳一次/总是跳）──
            preferenceCategory(key = "sponsor_types", title = { Text("各类片段的处理方式") })
            SponsorCategory.entries.forEach { category ->
                listPreference(
                    key = SettingPreferences.sponsorBlockSkipTypeKey(category.id).name,
                    title = { Text(category.label) },
                    enabled = { sponsorEnabled },
                    summary = { value ->
                        Text(
                            when (SponsorSkipType.of(value)) {
                                SponsorSkipType.Disable -> "完全不管（连色块都不画）"
                                SponsorSkipType.ShowOnly -> "只在进度条上画色块提示，不跳"
                                SponsorSkipType.SkipManually -> "到点弹「跳过」按钮，你点了才跳（4 秒后消失）"
                                SponsorSkipType.SkipOnce -> "自动跳，同一片段只跳一次（拖回去不再弹）"
                                SponsorSkipType.AlwaysSkip -> "每次经过都自动跳"
                            }
                        )
                    },
                    defaultValue = (
                        SponsorCategory.DEFAULT_SKIP_TYPES[category.id] ?: SponsorSkipType.Disable
                        ).ordinal,
                    values = SponsorSkipType.entries.map { it.ordinal },
                    valueToText = { value -> AnnotatedString(SponsorSkipType.of(value).label) },
                )
            }

            // ── 行为细节 ──
            preferenceCategory(key = "sponsor_detail", title = { Text("行为") })
            textIntPreference(
                key = SettingPreferences.SponsorBlockLimit.name,
                defaultValue = 0,
                title = { Text("最短片段时长") },
                label = " 秒",
                enabled = { sponsorEnabled },
                summary = { value ->
                    Text(
                        if (value <= 0) "不限制：任何长度的片段都按上面的策略处理"
                        else "短于 ${value} 秒的片段只画色块、不自动跳"
                    )
                },
            )
            switchPreference(
                key = SettingPreferences.SponsorBlockToast.name,
                title = { Text("跳过时弹提示") },
                enabled = { sponsorEnabled },
                summary = {
                    Text(if (it) "跳过片段时提示跳过了哪一类" else "静默跳过，不弹任何提示")
                },
                defaultValue = true,
            )
            switchPreference(
                key = SettingPreferences.SponsorBlockTrack.name,
                title = { Text("上报已跳过") },
                enabled = { sponsorEnabled },
                summary = {
                    Text(if (it) "把「已跳过」回报给服务端做统计（只带下面的匿名用户ID）" else "不上报")
                },
                defaultValue = true,
            )

            // ── 身份与服务端 ──
            preferenceCategory(key = "sponsor_identity", title = { Text("身份与服务端") })
            preference(
                key = "sponsor_username",
                title = { Text("公开昵称") },
                enabled = sponsorEnabled,
                summary = { Text("排行榜/统计里显示的名字（支持中文）。不设就显示那串公开ID") },
                onClick = { SponsorBlockSettingsUi.showUsernameDialog(context) }
            )
            preference(
                key = "sponsor_user_id",
                title = { Text("私人ID") },
                enabled = sponsorEnabled,
                summary = { Text("投票/提交用的身份，相当于密码（别外发）。可编辑或重掷随机值") },
                onClick = { SponsorBlockSettingsUi.showUserIdDialog(context) }
            )
            preference(
                key = "sponsor_block_server",
                title = { Text("自定义服务端") },
                enabled = sponsorEnabled,
                summary = { Text("留空 = 官方 bsbsb.top；可填镜像站") },
                onClick = { SponsorBlockSettingsUi.showServerDialog(context) }
            )
            preference(
                key = "sponsor_block_colors",
                title = { Text("片段颜色") },
                enabled = sponsorEnabled,
                summary = { Text("自定义进度条上各类别色块的颜色（不改就用默认色）") },
                onClick = { SponsorBlockSettingsUi.showColors(context) }
            )
            preference(
                key = "sponsor_block_stats",
                title = { Text("空降助手状态") },
                enabled = sponsorEnabled,
                summary = { Text("服务端是否在线 / 被跳过的片段次数 / 累计节省时间") },
                onClick = { SponsorBlockSettingsUi.showStats(context) }
            )

            // ── 关于（任何时候都能点：只是打开一个网页）──
            preferenceCategory(key = "sponsor_about", title = { Text("关于") })
            preference(
                key = "sponsor_about_item",
                title = { Text("关于空降助手") },
                summary = { Text("数据来自公益项目 BilibiliSponsorBlock（小电视空降助手）") },
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(SponsorBlockSettingsUi.ABOUT_URL))
                        )
                    }
                }
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
