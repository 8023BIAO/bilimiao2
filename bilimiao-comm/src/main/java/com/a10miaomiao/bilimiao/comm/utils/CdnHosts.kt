package com.a10miaomiao.bilimiao.comm.utils

/**
 * B站 CDN 主机列表，参考 pili plus / BiliRoaming
 * https://github.com/yujincheng08/BiliRoaming
 */
object CdnHosts {

    data class CdnInfo(
        val key: String,
        val label: String,
        val host: String,
    )

    /**
     * 可选 CDN 列表。
     * baseUrl/backupUrl 由 API 返回，其余为固定主机供用户手动选择。
     */
    val list: List<CdnInfo> = buildList {
        add(CdnInfo("default", "默认（API 返回）", ""))
        add(CdnInfo("backup", "备用 URL", "backup"))

        // 腾讯云
        add(CdnInfo("cos", "cos（腾讯云）", "upos-sz-mirrorcos.bilivideo.com"))
        add(CdnInfo("cosb", "cosb（腾讯云 VOD）", "upos-sz-mirrorcosb.bilivideo.com"))
        add(CdnInfo("coso1", "coso1（腾讯云）", "upos-sz-mirrorcoso1.bilivideo.com"))

        // 阿里云
        add(CdnInfo("ali", "ali（阿里云）", "upos-sz-mirrorali.bilivideo.com"))
        add(CdnInfo("alib", "alib（阿里云）", "upos-sz-mirroralib.bilivideo.com"))
        add(CdnInfo("alio1", "alio1（阿里云）", "upos-sz-mirroralio1.bilivideo.com"))

        // 华为云
        add(CdnInfo("hw", "hw（华为云）", "upos-sz-mirrorhw.bilivideo.com"))
        add(CdnInfo("hwb", "hwb（华为云）", "upos-sz-mirrorhwb.bilivideo.com"))
        add(CdnInfo("hwo1", "hwo1（华为云）", "upos-sz-mirrorhwo1.bilivideo.com"))
        add(CdnInfo("hw08c", "08c（华为云）", "upos-sz-mirror08c.bilivideo.com"))
        add(CdnInfo("hw08h", "08h（华为云）", "upos-sz-mirror08h.bilivideo.com"))
        add(CdnInfo("hw08ct", "08ct（华为云）", "upos-sz-mirror08ct.bilivideo.com"))

        // 海外
        add(CdnInfo("akamai", "akamai（海外）", "upos-hz-mirrorakam.akamaized.net"))
        add(CdnInfo("aliov", "aliov（阿里云海外）", "upos-sz-mirroraliov.bilivideo.com"))
        add(CdnInfo("cosov", "cosov（腾讯云海外）", "upos-sz-mirrorcosov.bilivideo.com"))
        add(CdnInfo("hwov", "hwov（华为云海外）", "upos-sz-mirrorhwov.bilivideo.com"))
        add(CdnInfo("hk", "hk（B站海外）", "cn-hk-eq-bcache-01.bilivideo.com"))
    }
}
