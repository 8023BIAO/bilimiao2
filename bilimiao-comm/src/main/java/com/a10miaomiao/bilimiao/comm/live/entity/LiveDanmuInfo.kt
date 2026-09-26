package com.a10miaomiao.bilimiao.comm.live.entity

import kotlinx.serialization.Serializable

/**
 * 弹幕服务器信息实体（第一阶段骨架）。
 *
 * 接口：`GET xlive/web-room/v1/index/getDanmuInfo?id={真实房间号}&type=0&web_location=444.8`
 *
 * ★实测（2026-09-25）：这个接口**不签名一律返回 `{"code":-352}`（风控）**，
 *   加 buvid3 / Referer / Origin / 换参数名都没用，**必须带 WBI 签名（w_rid + wts）**
 *   才能拿到 token。签名后的实测返回见 [LiveDanmuInfo]。
 *
 * ★token 是有时效的：参考实现 blbl 是**每次建立 WebSocket 连接前重新调一次**
 *   （见 LiveMessageClient.kt:64，在 connect() 内部取），不复用旧 token。
 */
@Serializable
data class LiveDanmuInfo(
    /** 认证包（op=7）里要带的 key。实测长度 252~276 字符 */
    val token: String = "",
    /**
     * ★实测会有**重复项**（同一 host 连出 3 条），必须去重 —— 见 [wsUrls]。
     */
    val host_list: List<LiveDanmuHost> = emptyList(),
    /** 服务端下发的心跳/延迟相关参数（实测 refresh_rate=100、max_delay=5000） */
    val refresh_rate: Int = 0,
    val max_delay: Int = 0,
)

/**
 * 弹幕服务器节点。
 * ★实测三个端口（host_list 里同时给）：
 * - [wss_port] = 2245 → **加密 WebSocket，我们用它**
 * - [ws_port]  = 2244 → 明文 WebSocket
 * - [port]     = 2243 → 裸 TCP 协议（不是 WebSocket，用不上）
 */
@Serializable
data class LiveDanmuHost(
    val host: String = "",
    val port: Int = 0,
    val wss_port: Int = 0,
    val ws_port: Int = 0,
)

/**
 * 按**优先级顺序**给出可用的弹幕 WebSocket 地址（wss://{host}:{wss_port}/sub），已去重。
 *
 * 为什么要把"去重"和"排序"封在这里：
 * - ★实测 host_list 里有完全重复的条目（同 host 同端口出现 3 次），
 *   直接拿去轮询重连会白等三次超时；
 * - 参考实现 blbl 同样做了 `distinctBy { host:wssPort:wsPort }`（LiveApi.kt:455）；
 * - 2245 端口（wss）优先，没有 wss_port 的节点直接跳过（明文 2244 在
 *   Android 9+ 默认禁 cleartext，走它会额外踩 network-security-config 的坑）。
 *
 * 返回值第一个通常是最优节点；连接失败时应**按顺序**换下一个（多线路回退）。
 */
val LiveDanmuInfo.wsUrls: List<String>
    get() = host_list
        .filter { it.host.isNotBlank() && it.wss_port > 0 }
        .distinctBy { "${it.host}:${it.wss_port}" }
        .map { "wss://${it.host}:${it.wss_port}/sub" }
