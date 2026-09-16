package com.a10miaomiao.bilimiao.comm.delegate.player.entity

class PlayerSourceInfo {
    var url: String = ""
    var quality: Int = 0
    var acceptList: List<AcceptInfo> = emptyList()
    var duration: Long = 0L
    var header: Map<String, String> = emptyMap()

    val description: String get() = acceptList.find { it.quality == quality }?.description ?: "未知清晰度"

    var height = 900 // 默认 16:9
    var width = 1600
    val screenProportion get() = width.toFloat() / height.toFloat() // 视频画面比例
    var lastPlayTime = 0L
    var lastPlayCid = ""

    /**
     * AI 原声翻译可选语言（HTTP playurl 的 language.items）。
     * gRPC 的 PlayViewReq 里没有语言字段，所以只有走 HTTP 取流时才拿得到；空 = 该视频没有 AI 翻译。
     */
    var languages: List<LanguageInfo> = emptyList()

    /** 本次取流用的翻译语言（null = 原声） */
    var currentLanguage: String? = null

    data class LanguageInfo(
        val lang: String,
        val title: String?,
    )

    data class AcceptInfo(
        val quality: Int,
        val description: String,
    )
}