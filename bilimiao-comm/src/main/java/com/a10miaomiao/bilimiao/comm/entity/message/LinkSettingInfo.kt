package com.a10miaomiao.bilimiao.comm.entity.message

import kotlinx.serialization.Serializable

/**
 * 消息提醒设置（官方网页版消息中心 `link_setting/v1/link_setting/get` 的 data 里我们用得上的三项）。
 *
 * 取值来自官方前端的 options（2026-09-27 抓包 + 同值回写实测）：
 *   · [set_comment] 回复我的：0 所有人 / 1 关注的人 / 2 不接收任何消息提醒
 *   · [set_at]      @我的  ：同上三个值
 *   · [set_like]    收到的赞：0 开启 / 5 关闭
 */
@Serializable
data class LinkSettingInfo(
    val set_comment: Int = 0,
    val set_at: Int = 0,
    val set_like: Int = 0,
)
