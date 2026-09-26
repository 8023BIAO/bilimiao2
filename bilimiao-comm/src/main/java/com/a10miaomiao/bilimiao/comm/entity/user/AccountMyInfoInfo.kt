package com.a10miaomiao.bilimiao.comm.entity.user

import kotlinx.serialization.Serializable

/**
 * 账号资料（编辑资料页的数据源）
 *
 * 接口：`GET https://app.bilibili.com/x/v2/account/myinfo`（APP 签名 + access_key，见调研报告 §2.1 R1）
 * 逐字段依据：PiliPlus `lib/models_new/account_myinfo/data.dart`（报告 §1.3）
 *
 * ★ 为什么不复用现成的 [UserInfo]（它来自 `x/v2/account/mine`）：
 *   那个接口**没有 `sign`、没有 `birthday`，也没有改昵称要用来判断的 `coins`**（报告 §2.1 R2 vs R1）。
 *   ⚠️ 注意是 `coins`（硬币数），跟 [UserInfo.coin] 不是同一个字段名，别混。
 *
 * ★ 为什么每个字段都带默认值：
 *   1. `sign` / `birthday` 在用户没填过时，服务端可能**整个字段都不下发**（而不是给空串），
 *      kotlinx.serialization 对"缺字段"会直接抛 MissingFieldException → 整页读不出来。
 *      给默认值 = 缺字段也能解出来，页面显示空串。
 *   2. `mid = 0` 是"未登录/异常返回"的哨兵值：`x/v2/account/mine` 对游客就是回 code=0 + mid=0
 *      （[com.a10miaomiao.bilimiao.comm.store.UserStore.loadInfo] 已经踩过），所以调用方必须自己判 mid。
 */
@Serializable
data class AccountMyInfoInfo(
    val mid: Long = 0L,
    val name: String = "",
    val sign: String = "",
    /** 硬币数。用 Double 与 [UserInfo.coin] 保持一致：JSON 给整数或小数都能解出来，不会因为格式炸掉 */
    val coins: Double = 0.0,
    /** 生日，`yyyy-MM-dd`；第二阶段"改生日"要回填这个值 */
    val birthday: String = "",
    /** 头像 URL。第一阶段只显示不修改（改头像要走 web 通道，见调研报告 P0-1） */
    val face: String = "",
    /** 0 保密 / 1 男 / 2 女（第二阶段"改性别"用） */
    val sex: Int = 0,
)
