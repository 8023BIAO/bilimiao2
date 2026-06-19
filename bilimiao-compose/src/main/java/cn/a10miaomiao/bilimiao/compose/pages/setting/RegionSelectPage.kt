package cn.a10miaomiao.bilimiao.compose.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.a10miaomiao.bilimiao.compose.base.BottomSheetState
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.pages.home.components.HomeTimeMachineCard
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.entity.region.RegionInfo
import com.a10miaomiao.bilimiao.comm.store.RegionStore
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

@Serializable
class RegionSelectPage : ComposePage() {

    @Composable
    override fun Content() {
        val regionStore: RegionStore by rememberInstance()
        val regionList = regionStore.stateFlow.collectAsState().value.regions
        val context = LocalContext.current
        val bottomSheetState: BottomSheetState by rememberInstance()

        var selected: Set<Int> by remember {
            mutableStateOf<Set<Int>>(
                runBlocking {
                    SettingPreferences.mapData(context) { prefs ->
                        prefs[SettingPreferences.TimeSelectSelectedRegions]
                            ?.mapNotNull { it.toIntOrNull() }
                            ?.toSet()
                            ?: emptySet()
                    }
                }
            )
        }

        fun toggleRegion(tid: Int) {
            selected = if (tid in selected) selected - tid else selected + tid
        }

        fun saveAndClose() {
            runBlocking {
                SettingPreferences.edit(context) { prefs ->
                    prefs[SettingPreferences.TimeSelectSelectedRegions] = selected.map { it.toString() }.toSet()
                }
            }
            bottomSheetState.close()
        }

        val windowStore: WindowStore by rememberInstance()
        val windowState = windowStore.stateFlow.collectAsState().value
        val windowInsets = windowState.getContentInsets(localContainerView())

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = windowInsets.topDp.dp)
        ) {
            PageConfig(title = "选择分区")

            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(300.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                items(regionList, key = { it.tid }) { region ->
                    HomeTimeMachineCard(
                        iconModel = region.icon
                            ?: region.logo?.let { UrlUtil.autoHttps(it) },
                        cardName = region.name,
                        onClick = { toggleRegion(region.tid) }
                    ) {
                        val isRegionSelected = region.tid in selected
                        FlowRow {
                            region.children?.forEach { child ->
                                val isChildSelected = child.tid in selected
                                AssistChip(
                                    onClick = { toggleRegion(child.tid) },
                                    label = {
                                        Text(
                                            text = child.name,
                                            color = if (isChildSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 底部确定按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = windowInsets.bottomDp.dp)
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { saveAndClose() }
                ) {
                    Text(
                        text = "确定 (${selected.size})",
                        fontSize = 18.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
