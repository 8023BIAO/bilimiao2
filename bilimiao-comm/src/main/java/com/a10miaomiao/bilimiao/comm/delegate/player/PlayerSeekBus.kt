package com.a10miaomiao.bilimiao.comm.delegate.player

object PlayerSeekBus {
    var onSeek: ((Long) -> Unit)? = null
}
