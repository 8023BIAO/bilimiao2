package cn.a10miaomiao.bilimiao.compose.pages.time

import android.widget.Toast
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import cn.a10miaomiao.bilimiao.compose.base.ComposePage
import cn.a10miaomiao.bilimiao.compose.common.diViewModel
import cn.a10miaomiao.bilimiao.compose.common.localContainerView
import cn.a10miaomiao.bilimiao.compose.common.mypage.PageConfig
import cn.a10miaomiao.bilimiao.compose.pages.time.components.*
import com.a10miaomiao.bilimiao.comm.store.TimeSettingStore
import com.a10miaomiao.bilimiao.comm.store.model.DateModel
import com.a10miaomiao.bilimiao.store.WindowStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import java.util.*

@Serializable
class TimeSettingPage : ComposePage() {

    @Composable
    override fun Content() {
        val viewModel = diViewModel<TimeSettingViewMode>()
        TimeSettingPageContent(viewModel)
    }

}

@Composable
private fun TimeSettingPageContent(
    viewModel: TimeSettingViewMode,
) {
    PageConfig(title = "时光姬-时间线设置")

    val windowStore: WindowStore by rememberInstance()
    val windowState = windowStore.stateFlow.collectAsStateWithLifecycle().value
    val windowInsets = windowState.getContentInsets(localContainerView())

    val cardIndex = viewModel.cardIndex.collectAsStateWithLifecycle().value

    val scrollState = rememberScrollState()

    // 「自定义范围」卡片激活后自动滚到底：500dp 弹窗里默认视图看不到摘要，
    // 最底一行日期还会被悬浮「确定」按钮盖住（点它会误触确定）。卡片展开是动画，
    // 可滚高度要等几帧才稳定，所以等 maxValue 连续两帧不变再平滑滚过去。
    LaunchedEffect(cardIndex) {
        if (cardIndex != TimeSettingStore.TIME_TYPE_CUSTOM) return@LaunchedEffect
        var prev = -1
        repeat(40) {
            withFrameNanos { }
            val cur = scrollState.maxValue
            if (cur == prev && cur > 0) {
                scrollState.animateScrollTo(cur)
                return@LaunchedEffect
            }
            prev = cur
        }
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(windowInsets.topDp.dp))

            TimeCard(
                title = "当前时间线",
                active = cardIndex == TimeSettingStore.TIME_TYPE_CURRENT,
                onActiveChange = viewModel::setCurrentCardAsCurrent,
            ) {
                CurrentTime(viewModel)
            }
            TimeCard(
                title = "按月份选择",
                active = cardIndex == TimeSettingStore.TIME_TYPE_MONTH,
                onActiveChange = viewModel::setCurrentCardAsMonth,
            ) {
                MonthTime(viewModel)
            }
            TimeCard(
                title = "自定义范围",
                active = cardIndex == TimeSettingStore.TIME_TYPE_CUSTOM,
                onActiveChange = viewModel::setCurrentCardAsCustom,
            ) {
                CustomTime(viewModel)
            }

            Spacer(modifier = Modifier.height(windowInsets.bottomDp.dp + 50.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .padding(bottom = windowInsets.bottomDp.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = viewModel::save,
            ) {
                Text(
                    text = "确定",
                    fontSize = 20.sp,
                    lineHeight = 20.sp
                )
            }
        }

    }

}


