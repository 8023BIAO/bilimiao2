package cn.a10miaomiao.bilimiao.compose.components.user

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 进直播间的**唯一入口**：原生播放页 `LivePlayerActivity`，拉不起来就兜底网页。
 *
 * ## 这个文件为什么只剩这一个函数
 * 这里原来是「直播中」头像组件 `LiveBadgedAvatar`（头像 + 三字药丸「直播中」+ 向外扩散的涟漪）。
 * 用户 2026-09-28 拍板做减法：
 *
 * > "直播中还有哪一些页面有这些涟漪，还有它那个三个字的样式，全部给它删除了，
 * >  仅保留那个直播页面的我的关注在直播那个。"
 *
 * 于是 `LiveBadgedAvatar()` / `LiveRipple()` / `LiveLabel()` / `rememberLiveStatus()` /
 * `rememberIsResumed()` 连同涟漪的全部动画常量一起删除；三个调用点（动态页 UP 栏宽屏/窄屏、
 * 用户空间顶部大头像）都改回了裸头像。
 * ★2026-09-29 状态（本轮更正）：当时写的"「直播中」只剩关注区块那一处角标"**也已不成立** ——
 *   那颗角标（`HomeLiveContent.kt` 的 `LiveStatusBadge`，以及直播搜索 / 全站搜索直播 Tab 的同款）
 *   同一批一起删掉了。
 *   ⇒ **全工程现在没有任何「直播中」药丸/角标**，本文件是"进直播间"的唯一入口 [enterLiveRoom]。
 *   详情与删除记录见 `HomeLiveContent.kt` 里 `LiveRoomCard` 的 KDoc。
 *
 * ## 为什么 `enterLiveRoom` 留在这里、文件名也不改
 * 它是三处（`HomeLiveContent` / `HistoryPage` / `LiveFollowPage`）共用的公开函数，
 * 是"进直播间"的唯一入口；为一个函数去换文件/换包，只会白白改动那三处的 import，没有收益。
 *
 * ★为什么用 `setClassName` 的类名字符串、而不是直接 `import LivePlayerActivity`：
 *   `LivePlayerActivity` 在 **app 模块**，本文件在 **bilimiao-compose 模块**，
 *   依赖方向是 app → compose，反向引用会成环编译不过。extra 的 key `"roomId"`
 *   与 `LivePlayerActivity.EXTRA_ROOM_ID` 是同一份字面量约定 ——
 *   与 `CoverViewModel`、`HomeLiveContent` 里那几处用的是同一套。
 *
 * @param roomId **真实房间号**（`get_status_info_by_uids` / `x/v2/space` 给的都是真实号）
 */
fun enterLiveRoom(context: Context, roomId: Long) {
    if (roomId <= 0) return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setClassName(context, LIVE_PLAYER_ACTIVITY)
        putExtra(LIVE_PLAYER_EXTRA_ROOM_ID, roomId.toString())
    }
    runCatching { context.startActivity(intent) }.onFailure {
        // 原生页万一拉不起来（理论上不会），退回网页直播间，别让用户点了没反应
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://live.bilibili.com/$roomId"))
            )
        }
    }
}

/** 与 app 模块 `LivePlayerActivity` 对齐的字面量约定（见 [enterLiveRoom] 注释） */
private const val LIVE_PLAYER_ACTIVITY = "com.a10miaomiao.bilimiao.LivePlayerActivity"
private const val LIVE_PLAYER_EXTRA_ROOM_ID = "roomId"
