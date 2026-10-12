package com.a10miaomiao.bilimiao.comm.mypage

object MenuKeys {
    var id_counter = 100

    val back = -233

    val menu = id_counter++
    val home = id_counter++
    val dynamic = id_counter++

    val setting = id_counter++
    val history = id_counter++
    val download = id_counter++
    val search = id_counter++
    val searchInHome = id_counter++

    val region = id_counter++
    val sort = id_counter++
    val filter = id_counter++
    val time = id_counter++
    val add = id_counter++
    val edit = id_counter++
    val delete = id_counter++
    val clear = id_counter++
    val select = id_counter++
    val complete = id_counter++
    val send = id_counter++
    val save = id_counter++
    val favourite = id_counter++

    val more = id_counter++
    val follow = id_counter++
    val message = id_counter++
    val share = id_counter++

    val keyboard = id_counter++
    val emoji = id_counter++
    val playList = id_counter++
    val url = id_counter++
    val parent = id_counter++

    /** 底栏：复制当前页链接（内置网页页，2026-10-12 加） */
    val copyLink = id_counter++

    /** 消息页底栏：消息提醒设置（回复我的 / @我的 / 收到的赞，2026-09-27 加） */
    val msgNoticeSetting = id_counter++
    val aiSummary = id_counter++
}