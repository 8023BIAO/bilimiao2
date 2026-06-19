package com.a10miaomiao.bilimiao.widget.player

import master.flame.danmaku.controller.DanmakuFilters
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.android.DanmakuContext

/**
 * 弹幕关键词过滤（Aho-Corasick 优化版）
 * 每条弹幕 O(文本长度) 替代 O(关键词数 × 文本长度)
 */
class DanmakuTextFilter : DanmakuFilters.BaseDanmakuFilter<Set<String>>() {

    // Aho-Corasick: 纯文本关键词一次扫遍
    private var plainMatcher: DanmakuAcMatcher? = null
    // 缓存的正则列表
    private val regexPatterns = mutableListOf<Regex>()

    override fun filter(
        danmaku: BaseDanmaku,
        index: Int,
        totalsizeInScreen: Int,
        timer: DanmakuTimer?,
        fromCachingTask: Boolean,
        config: DanmakuContext?
    ): Boolean {
        if (plainMatcher == null && regexPatterns.isEmpty()) return false
        val text = danmaku.text?.toString() ?: return false

        // 1. Aho-Corasick 纯文本匹配 O(文本长度)
        plainMatcher?.let { matcher ->
            if (matcher.containsAny(text)) {
                danmaku.mFilterParam = danmaku.mFilterParam or (1 shl 20)
                return true
            }
        }

        // 2. 缓存的正则匹配
        for (pattern in regexPatterns) {
            if (pattern.containsMatchIn(text)) {
                danmaku.mFilterParam = danmaku.mFilterParam or (1 shl 20)
                return true
            }
        }

        return false
    }

    override fun setData(data: Set<String>?) {
        reset()
        if (data != null && data.isNotEmpty()) {
            val plainWords = mutableListOf<String>()
            for (item in data) {
                if (item.startsWith("/") && item.endsWith("/") && item.length > 2) {
                    val pattern = item.substring(1, item.length - 1)
                    try {
                        regexPatterns.add(Regex(pattern))
                    } catch (_: Exception) {
                        plainWords.add(item)
                    }
                } else if (item.isNotEmpty()) {
                    plainWords.add(item)
                }
            }
            if (plainWords.isNotEmpty()) {
                plainMatcher = DanmakuAcMatcher(plainWords)
            }
        }
    }

    override fun reset() {
        plainMatcher = null
        regexPatterns.clear()
    }

    override fun clear() {
        reset()
    }
}

/**
 * 弹幕专用的轻量 Aho-Corasick 匹配器
 * 只返回 true/false（不含匹配详情），减少内存分配
 */
class DanmakuAcMatcher(keywords: List<String>) {

    data class Node(
        val children: MutableMap<Char, Node> = mutableMapOf(),
        var fail: Node? = null,
        var output: Boolean = false,
    )

    private val root = Node()

    init {
        buildTrie(keywords)
        buildFailureLinks()
    }

    private fun buildTrie(keywords: List<String>) {
        for (keyword in keywords) {
            var node = root
            for (ch in keyword) {
                node = node.children.getOrPut(ch) { Node() }
            }
            node.output = true
        }
    }

    private fun buildFailureLinks() {
        val queue: java.util.Queue<Node> = java.util.LinkedList()
        for (child in root.children.values) {
            child.fail = root
            queue.add(child)
        }
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            for ((ch, child) in current.children) {
                queue.add(child)
                var failNode = current.fail
                while (failNode != null && failNode != root && failNode.children[ch] == null) {
                    failNode = failNode.fail
                }
                val next = failNode?.children?.get(ch)
                child.fail = next ?: root
                child.output = child.output || (next?.output == true)
            }
        }
    }

    fun containsAny(text: String): Boolean {
        var node = root
        for (ch in text) {
            while (node != root && node.children[ch] == null) {
                node = node.fail ?: root
            }
            val next = node.children[ch]
            if (next != null) {
                node = next
                if (node.output) return true
            }
        }
        return false
    }
}
