package com.a10miaomiao.bilimiao.comm.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 「自动连播」全局持久化开关的读写口。
 *
 * 五个页面共用同一个值：视频页合集浮层、合集详情、收藏夹、用户合集、稍后再看。
 * 以前各页面把开关放在自己的 ViewModel 里（`mutableStateOf`/`MutableStateFlow`），
 * 退出页面就回到默认值 —— 用户关掉自动连播、再进详情页又变回"开"。
 *
 * 读：走 [AppStore.stateFlow]（单一数据流，改一处五处同时变）；
 * 写：直接落 datastore，默认开（[SettingPreferences.AutoPlayEnable]）。
 */
fun AppStore.autoPlayEnableFlow(): Flow<Boolean> = stateFlow.map { it.autoPlayEnable }

/** 写"自动连播"（全局）。调用方传的是已解析的 [Context]（页面 VM 里一般注入 Context）。 */
suspend fun Context.setAutoPlayEnable(enable: Boolean) {
    with(SettingPreferences) {
        dataStore.edit { it[SettingPreferences.AutoPlayEnable] = enable }
    }
}
