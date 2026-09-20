package com.a10miaomiao.bilimiao.comm.delegate.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

class PlayerCoroutineScope: CoroutineScope {

    var job: Job = Job()

    // CoroutineScope 的实现
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    fun onCreate () {
        // ★ 旧 job 必须先取消。onCreate 是**无条件**调用的（同一个视频重复打开时不会走
        //   onDestroy），原来直接 `job = Job()` 把旧 Job 丢掉、永不取消：里面那个永不结束的
        //   activity.dataStore.data.collect{} 会一直活着 → Activity 泄漏、每改一次设置就多一份
        //   重复的播放器/弹幕初始化；在飞的加载协程还会在播放器已 release 之后继续 setUp。
        job.cancel()
        job = Job()
    }

    fun onDestroy () {
        job.cancel()
    }

}