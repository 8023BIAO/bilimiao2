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

    /**
     * 不可变快照：plainMatcher / regexPatterns 一旦建好就不再改动。
     *
     * 为什么必须这样：filter() 是在 **DFM 的缓存构建线程**上被调用的，而 setData()/reset()
     * 由主线程调用（每次 DataStore 发射、每次全屏切换都会走一遍）。原来是对同一个 ArrayList
     * 做 clear()/add()，后台线程正在 for 遍历时主线程 clear → ConcurrentModificationException；
     * 而 DFM 里那段遍历没有 try/catch，异常抛到缓存线程上没人接 → **整个进程崩溃**。
     * 换成"整对象替换 + @Volatile 发布"后，读线程只会看到某个完整快照。
     */
    private class Snapshot(
        val plainMatcher: DanmakuAcMatcher?,
        val regexPatterns: List<Regex>,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    override fun filter(
        danmaku: BaseDanmaku,
        index: Int,
        totalsizeInScreen: Int,
        timer: DanmakuTimer?,
        fromCachingTask: Boolean,
        config: DanmakuContext?
    ): Boolean {
        val snap = snapshot ?: return false
        val text = danmaku.text?.toString() ?: return false

        // 1. Aho-Corasick 纯文本匹配 O(文本长度)
        if (snap.plainMatcher?.containsAny(text) == true) {
            danmaku.mFilterParam = danmaku.mFilterParam or (1 shl 20)
            return true
        }

        // 2. 缓存的正则匹配
        for (pattern in snap.regexPatterns) {
            if (pattern.containsMatchIn(text)) {
                danmaku.mFilterParam = danmaku.mFilterParam or (1 shl 20)
                return true
            }
        }

        return false
    }

    override fun setData(data: Set<String>?) {
        snapshot = buildSnapshot(data)
    }

    override fun reset() {
        snapshot = null
    }

    override fun clear() {
        reset()
    }

    private fun buildSnapshot(data: Set<String>?): Snapshot? {
        if (data.isNullOrEmpty()) return null
        val plainWords = mutableListOf<String>()
        val regexes = mutableListOf<Regex>()
        for (item in data) {
            if (item.startsWith("/") && item.endsWith("/") && item.length > 2) {
                val pattern = item.substring(1, item.length - 1)
                try {
                    regexes.add(Regex(pattern))
                } catch (_: Exception) {
                    plainWords.add(item)
                }
            } else if (item.isNotEmpty()) {
                plainWords.add(item)
            }
        }
        val matcher = if (plainWords.isNotEmpty()) DanmakuAcMatcher(plainWords) else null
        if (matcher == null && regexes.isEmpty()) return null
        return Snapshot(matcher, regexes)
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
