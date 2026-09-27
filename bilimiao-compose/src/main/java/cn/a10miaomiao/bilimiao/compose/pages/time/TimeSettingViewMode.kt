package cn.a10miaomiao.bilimiao.compose.pages.time

import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.pages.time.components.getMonthDayNum
import com.a10miaomiao.bilimiao.comm.store.TimeSettingStore
import com.a10miaomiao.bilimiao.comm.store.model.DateModel
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.flow.MutableStateFlow
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.util.Date

class TimeSettingViewMode(
    override val di: DI,
) : ViewModel(), DIAware {

    private val timeSettingStore by instance<TimeSettingStore>()
    private val pageNavigation by instance<PageNavigation>()
    // 本页是 bottomSheetState.open(TimeSettingPage()) 打开的，不在 nav 返回栈里
    private val bottomSheetState by instance<cn.a10miaomiao.bilimiao.compose.base.BottomSheetState>()

//    private val calendar = Calendar.getInstance()

    val cardIndex = MutableStateFlow(timeSettingStore.state.timeType)

    val minDate = DateModel().also {
        it.year = 2009
        it.month = 1
        it.date = 1
    }

    val maxDate = DateModel().also {
        it.setDate(Date())
//        it.year = Calendar.getInstance().get(Calendar.YEAR)
//        it.month = Calendar.getInstance().get(Calendar.MONTH + 1)
//        it.date = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
    }

    val yearCount = maxDate.year - minDate.year + 1

    val currentTime = MutableStateFlow(TimeInfo().apply {
        val now = Date()
        timeTo.setDate(now)
        timeFrom.set(timeTo.getTimeByGapCount(-7)) //最近7天
    })

    val monthTime = MutableStateFlow(TimeInfo().apply {
        val dateModel = timeSettingStore.state.timeFrom.copy()
        dateModel.date = 1 // 当月第一天
        timeFrom.set(dateModel)
        dateModel.date = getMonthDayNum(dateModel.year, dateModel.month) // 当月最后一天
        timeTo.set(dateModel)
    })

    val customTime = MutableStateFlow(TimeInfo().apply {
        timeFrom.set(timeSettingStore.state.timeFrom)
        timeTo.set(timeSettingStore.state.timeTo)
    })

    fun setMonthTime(year: Int, month: Int) {
        // 未来月份选得进去、也能存下来，结果是一条永远查不到内容的时间线
        if (year > maxDate.year || (year == maxDate.year && month > maxDate.month)) {
            toast("不能选择未来的月份")
            return
        }
        monthTime.value = TimeInfo().apply {
            val dateModel = DateModel()
            dateModel.year = year
            dateModel.month = month
            dateModel.date = 1
            timeFrom.set(dateModel)
            dateModel.date = getMonthDayNum(dateModel.year, dateModel.month)
            timeTo.set(dateModel)
        }
    }

    fun setCustomTime(start: DateModel?, end: DateModel?) {
        customTime.value = TimeInfo().apply {
            if (start != null && end != null) {
                timeFrom.set(start)
                timeTo.set(end)
            } else if (start != null) {
                // 只选了开始（通常是第二个日期点在了 30 天以外的禁用格上）：
                // 标记成"未选完整"，否则 TimeInfo() 的默认值 2009-01-01 会被当成真实区间存下去
                toast("时间间隔不能大于 30 天，请重新选择")
                timeFrom.year = -1
            } else {
                timeFrom.year = -1
            }
        }
    }

    fun setCurrentCardAsCurrent(avtive: Boolean) {
        cardIndex.value = TimeSettingStore.TIME_TYPE_CURRENT
    }

    fun setCurrentCardAsMonth(avtive: Boolean) {
        cardIndex.value = TimeSettingStore.TIME_TYPE_MONTH
    }

    fun setCurrentCardAsCustom(avtive: Boolean) {
        cardIndex.value = TimeSettingStore.TIME_TYPE_CUSTOM
    }

    fun save() {
        val timeInfo = (when (cardIndex.value) {
            TimeSettingStore.TIME_TYPE_CURRENT -> currentTime.value
            TimeSettingStore.TIME_TYPE_MONTH -> monthTime.value
            TimeSettingStore.TIME_TYPE_CUSTOM -> customTime.value
            else -> currentTime.value
        })
        if (timeInfo.timeFrom.year == -1 || timeInfo.timeTo.year == -1) {
            // 两端都选了才允许保存：以前只点一个日期就"确定"会静默保存上一次的旧区间
            toast("请选择完整的时间范围")
            return
        }
        timeSettingStore.setTime(
            cardIndex.value,
            timeInfo.timeFrom.copy(),
            timeInfo.timeTo.copy(),
        )
        timeSettingStore.save()
        // 点"确定"原来调 popBackStack()：弹窗不在返回栈里，被弹掉的是弹窗下面那一页
        //（分区详情/首页），设置弹窗反而留在屏幕上。首页入口因为下面没页面可弹才"看起来正常"。
        if (bottomSheetState.page.value is TimeSettingPage) {
            bottomSheetState.close()
        } else {
            pageNavigation.popBackStack()
        }
    }

    class TimeInfo(
        val timeFrom: DateModel = DateModel(),
        val timeTo: DateModel = DateModel(),
    )
}