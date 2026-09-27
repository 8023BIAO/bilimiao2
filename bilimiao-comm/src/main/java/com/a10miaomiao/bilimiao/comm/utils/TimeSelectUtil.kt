package com.a10miaomiao.bilimiao.comm.utils

object TimeSelectUtil {

    private val WEIGHT_KEYS = listOf("coin", "favorite", "click", "danmaku", "reply")

    fun parseWeights(str: String): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        str.split(",").forEach { pair ->
            val parts = pair.split(":")
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim().toIntOrNull() ?: 0
                if (key in WEIGHT_KEYS) {
                    map[key] = value
                }
            }
        }
        // fill missing keys with 0
        for (k in WEIGHT_KEYS) {
            map.getOrPut(k) { 0 }
        }
        return map
    }

    fun formatWeights(map: Map<String, Int>): String {
        return WEIGHT_KEYS.joinToString(",") { "${it}:${map.getOrDefault(it, 0)}" }
    }

    fun getDefaultWeights(): Map<String, Int> {
        return parseWeights("coin:40,favorite:35,click:15,danmaku:5,reply:5")
    }

    fun weightsToDisplayString(map: Map<String, Int>): String {
        val labels = mapOf(
            "coin" to "硬币",
            "favorite" to "收藏",
            "click" to "播放",
            "danmaku" to "弹幕",
            "reply" to "评论",
        )
        return WEIGHT_KEYS
            .mapNotNull { key ->
                val v = map[key] ?: return@mapNotNull null
                if (v <= 0) null else "${labels[key] ?: key}×${v}%"
            }
            .joinToString(" + ")
    }
}
