package com.a10miaomiao.bilimiao.comm.store

import android.util.LruCache
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bilibili.app.view.v1.ViewGRPC
import bilibili.app.view.v1.ViewReq
import com.a10miaomiao.bilimiao.comm.db.FilterTagDB
import com.a10miaomiao.bilimiao.comm.db.FilterUpperDB
import com.a10miaomiao.bilimiao.comm.db.FilterUpperNameDB
import com.a10miaomiao.bilimiao.comm.db.FilterWordDB
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.base.BaseStore
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance
import java.util.LinkedList
import java.util.Queue

class FilterStore(override val di: DI) :
    ViewModel(), BaseStore<FilterStore.State> {

    data class State (
        var filterWordList: MutableList<String> = mutableListOf(),
        var filterUpperList: MutableList<FilterUpperDB.Upper> = mutableListOf(),
        var filterTagList: MutableList<String> = mutableListOf(),
        var filterUpperNameList: MutableList<String> = mutableListOf(),
    )

    override val stateFlow = MutableStateFlow(State())
    override fun copyState() = state.copy()

    private val activity: AppCompatActivity by instance()

    private val filterWordDB = FilterWordDB(activity)

    private val filterUpperDB = FilterUpperDB(activity)
    private val filterUpperNameDB = FilterUpperNameDB(activity)

    private val filterTagDB = FilterTagDB(activity)

    val filterWordCount get() = state.filterWordList.size

    val filterUpperCount get() = state.filterUpperList.size
    val filterUpperNameCount get() = state.filterUpperNameList.size

    val filterTagCount get() = state.filterTagList.size

    // 视频最小过滤时长(秒)，0表示不过滤
    var videoMinDuration: Int = 0
    // 视频最小播放量过滤(个)，0表示不过滤
    var videoMinPlayCount: Int = 0
    // 已关注UP主白名单：开启后已关注UP的视频不受屏蔽规则影响
    var followWhitelistEnabled: Boolean = false
    // 屏蔽推广视频：card_goto 非 "av" 视为推广
    var blockPromotion: Boolean = false

    // ============================================================
    // Aho-Corasick 多模式匹配 —— O(文本长度) 替代 O(N×文本长度)
    // ============================================================
    private var plainMatcher: AhoCorasickMatcher? = null
    private var cachedRegexList: List<Regex> = emptyList()

    // filterUpperName: AC + 缓存正则（同 filterWord）
    private var upperNamePlainMatcher: AhoCorasickMatcher? = null
    private var upperNameCachedRegexList: List<Regex> = emptyList()

    // filterTagList → AC + 缓存正则（同 filterWord 逻辑）
    private var tagPlainMatcher: AhoCorasickMatcher? = null
    private var tagCachedRegexList: List<Regex> = emptyList()
    // filterUpper mid → HashSet
    private var filterUpperMidSet: Set<Long> = emptySet()

    // filterTag(id) gRPC 结果 LRU 缓存（避免重复请求同一视频的标签）
    private val tagResultCache = LruCache<String, List<String>>(200)

    init {
        // 从设置中加载视频过滤时长
        viewModelScope.launch {
            SettingPreferences.run {
                activity.dataStore.data.collect {
                    videoMinDuration = it[VideoMinDuration] ?: SettingConstants.VIDEO_MIN_DURATION_DEFAULT
                    videoMinPlayCount = it[VideoMinPlayCount] ?: SettingConstants.VIDEO_MIN_PLAY_COUNT_DEFAULT
                    followWhitelistEnabled = it[FollowWhitelistEnabled] ?: false
                    blockPromotion = it[BlockPromotion] ?: false
                }
            }
        }
        queryFilterWord()
        queryFilterUpper()
        queryFilterTag()
        queryFilterUpperName()
    }

    // ---- 重建所有编译缓存 ----

    private fun rebuildFilterWordCache() {
        val plainWords = mutableListOf<String>()
        val regexList = mutableListOf<Regex>()
        for (word in state.filterWordList) {
            if (word.length > 2 && word.startsWith('/') && word.endsWith('/')) {
                // 正则类型：去首尾 / 编译一次，缓存
                regexList.add(word.substring(1, word.length - 1).toRegex())
            } else if (word.isNotEmpty()) {
                plainWords.add(word)
            }
        }
        plainMatcher = if (plainWords.isNotEmpty()) AhoCorasickMatcher(plainWords) else null
        cachedRegexList = regexList
    }

    private fun rebuildTagCache() {
        val plainWords = mutableListOf<String>()
        val regexList = mutableListOf<Regex>()
        for (word in state.filterTagList) {
            if (word.length > 2 && word.startsWith('/') && word.endsWith('/')) {
                // 正则类型：去首尾 / 编译一次，缓存
                regexList.add(word.substring(1, word.length - 1).toRegex())
            } else if (word.isNotEmpty()) {
                plainWords.add(word)
            }
        }
        tagPlainMatcher = if (plainWords.isNotEmpty()) AhoCorasickMatcher(plainWords) else null
        tagCachedRegexList = regexList
    }

    private fun rebuildUpperMidSet() {
        filterUpperMidSet = state.filterUpperList.map { it.mid }.toSet()
    }

    private fun rebuildUpperNameCache() {
        val plainWords = mutableListOf<String>()
        val regexList = mutableListOf<Regex>()
        for (word in state.filterUpperNameList) {
            if (word.length > 2 && word.startsWith('/') && word.endsWith('/')) {
                regexList.add(word.substring(1, word.length - 1).toRegex())
            } else if (word.isNotEmpty()) {
                plainWords.add(word)
            }
        }
        upperNamePlainMatcher = if (plainWords.isNotEmpty()) AhoCorasickMatcher(plainWords) else null
        upperNameCachedRegexList = regexList
    }

    // ---- 查询（每次增删改后调用） ----

    fun queryFilterWord() {
        val list = filterWordDB.queryAll()
        setState {
            filterWordList = list
        }
        rebuildFilterWordCache()
    }

    fun queryFilterUpper() {
        val list = filterUpperDB.queryAll()
        setState {
            filterUpperList = list
        }
        rebuildUpperMidSet()
    }

    fun queryFilterTag() {
        val list = filterTagDB.queryAll()
        setState {
            filterTagList = list
        }
        rebuildTagCache()
        // 标签列表变了，清空 gRPC 缓存（旧缓存可能不包含新过滤规则）
        tagResultCache.evictAll()
    }

    fun queryFilterUpperName() {
        val list = filterUpperNameDB.queryAll()
        setState {
            filterUpperNameList = list
        }
        rebuildUpperNameCache()
    }

    // ---- filterWord: AC + 缓存正则 ----

    fun filterWord(text: String): Boolean {
        // 1. Aho-Corasick 纯文本匹配 O(文本长度)
        plainMatcher?.let { matcher ->
            if (matcher.containsAny(text)) return false
        }
        // 2. 缓存的正则匹配
        for (regex in cachedRegexList) {
            if (regex.containsMatchIn(text)) return false
        }
        return true
    }

    // ---- CRUD: 关键词 ----

    fun addWord(keyword: String) {
        filterWordDB.insert(keyword)
        queryFilterWord()
        toast("添加成功")
    }

    fun setWord(oldWord: String, newWord: String) {
        filterWordDB.updateKeyword(oldWord, newWord)
        queryFilterWord()
    }

    fun deleteWord(index: Int) {
        val keyword = state.filterWordList[index]
        filterWordDB.deleteByKeyword(keyword)
        queryFilterWord()
        toast("删除成功")
    }

    fun deleteWord(keywordList: List<String>) {
        keywordList.forEach {
            filterWordDB.deleteByKeyword(it)
        }
        queryFilterWord()
        toast("删除成功")
    }

    // ---- CRUD: UP主 ----

    fun addUpper(mid: Long, name: String) {
        filterUpperDB.insert(mid, name)
        queryFilterUpper()
        toast("已添加屏蔽")
    }

    fun deleteUpper(mid: Long) {
        filterUpperDB.deleteByMid(mid)
        queryFilterUpper()
        toast("已取消屏蔽")
    }

    fun deleteUpper(midList: List<Long>) {
        midList.forEach {
            filterUpperDB.deleteByMid(it)
        }
        queryFilterUpper()
        toast("删除成功")
    }

    // ---- filterUpper: HashSet O(1) ----

    fun filterUpper(mid: String) = filterUpper(mid.toLong())
    fun filterUpper(mid: Long): Boolean {
        return mid !in filterUpperMidSet
    }

    // ---- filterUpperName: AC + 缓存正则 ----

    fun filterUpperName(name: String): Boolean {
        // 1. Aho-Corasick 纯文本匹配
        upperNamePlainMatcher?.let { matcher ->
            if (matcher.containsAny(name)) return false
        }
        // 2. 缓存的正则匹配
        for (regex in upperNameCachedRegexList) {
            if (regex.containsMatchIn(name)) return false
        }
        return true
    }

    fun addUpperName(name: String) {
        filterUpperNameDB.insert(name)
        queryFilterUpperName()
        toast("添加成功")
    }

    fun setUpperName(old: String, new: String) {
        filterUpperNameDB.updateKeyword(old, new)
        queryFilterUpperName()
    }

    fun deleteUpperName(nameList: List<String>) {
        nameList.forEach {
            filterUpperNameDB.deleteByKeyword(it)
        }
        queryFilterUpperName()
        toast("删除成功")
    }

    // ---- 白名单 ----

    /**
     * 检查是否在白名单中（已关注UP主不受屏蔽规则影响）
     * @param isFollowed 视频数据中的 is_followed 字段（1=已关注）
     * @return true 表示在白名单中，应跳过过滤
     */
    fun isFollowWhitelisted(isFollowed: Int?): Boolean {
        return followWhitelistEnabled && isFollowed == 1
    }

    /**
     * 推广视频过滤：card_goto 非 "av" 的视为推广/广告直接拦截
     * TODO: 升级 proto 后可用 AdInfo.is_ad 替代，更准确
     * @return true 通过，false 拦截
     */
    fun filterPromotion(cardGoto: String): Boolean {
        return !blockPromotion || cardGoto == "av"
    }

    // ---- filterTag: AC + 缓存正则（同 filterWord 逻辑） ----

    fun filterTag(text: List<String>): Boolean {
        return text.all { tag -> filterSingleTag(tag) }
    }

    private fun filterSingleTag(tag: String): Boolean {
        // 1. Aho-Corasick 纯文本匹配 O(文本长度)
        tagPlainMatcher?.let { matcher ->
            if (matcher.containsAny(tag)) return false
        }
        // 2. 缓存的正则匹配
        for (regex in tagCachedRegexList) {
            if (regex.containsMatchIn(tag)) return false
        }
        return true
    }

    /**
     * 根据av号或bv号筛选视频标签（gRPC，带 LRU 缓存）
     */
    suspend fun filterTag(
        id: String, // av号或bv号
    ): Boolean {
        if (tagPlainMatcher == null && tagCachedRegexList.isEmpty()) {
            return true
        }
        // LRU 缓存命中 → 直接查 AC + 正则
        val cached = tagResultCache.get(id)
        if (cached != null) {
            return filterTag(cached)
        }
        // 缓存未命中 → gRPC 请求
        return try {
            val req = if (id.startsWith("BV")) {
                ViewReq(bvid = id)
            } else {
                ViewReq(aid = id.toLong())
            }
            val res = BiliGRPCHttp.request {
                ViewGRPC.view(req)
            }.awaitCall()
            val tags = res.tag.map { it.name }
            tagResultCache.put(id, tags)
            filterTag(tags)
        } catch (e: Exception) {
            miaoLogger() error "filterTag gRPC failed for $id: ${e.message}"
            // gRPC 失败时放行视频（不因网络波动误杀），不写缓存以允许下次重试
            true
        }
    }

    fun addTag(name: String) {
        filterTagDB.insert(name)
        queryFilterTag()
        toast("添加成功")
    }

    fun setTag(old: String, new: String) {
        filterTagDB.updateTagName(old, new)
        queryFilterWord()
    }

    fun deleteTag(index: Int) {
        val name = state.filterTagList[index]
        filterTagDB.deleteByTagName(name)
        queryFilterTag()
        toast("删除成功")
    }

    fun deleteTag(tagList: List<String>) {
        tagList.forEach {
            filterTagDB.deleteByTagName(it)
        }
        queryFilterTag()
        toast("删除成功")
    }

    fun filterTagListIsEmpty() = tagPlainMatcher == null && tagCachedRegexList.isEmpty()

    /**
     * 过滤视频时长（文字版）
     * @param durationText 时长文本，如 "03:24"
     * @return true 表示通过，false 表示被过滤
     */
    fun filterDuration(durationText: String?): Boolean {
        if (videoMinDuration <= 0 || durationText.isNullOrBlank()) {
            return true
        }
        // 解析时长文本 "03:24" -> 秒数
        val seconds = parseDurationToSeconds(durationText)
        return seconds >= videoMinDuration
    }

    /**
     * 过滤视频时长（秒数版）
     * @param durationSeconds 时长秒数
     * @return true 表示通过，false 表示被过滤
     */
    fun filterDuration(durationSeconds: Int): Boolean {
        if (videoMinDuration <= 0) {
            return true
        }
        return durationSeconds >= videoMinDuration
    }

    /** 过滤播放量（文本格式） */
    fun filterPlayCount(playText: String?): Boolean {
        if (videoMinPlayCount <= 0) return true
        if (playText.isNullOrBlank()) return true
        val count = NumberUtil.parseToLong(playText)
        return count >= videoMinPlayCount
    }

    private fun parseDurationToSeconds(text: String): Int {
        val parts = text.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]  // 时:分:秒
            2 -> parts[0] * 60 + parts[1]                      // 分:秒
            1 -> parts[0]                                      // 秒
            else -> 0
        }
    }
}

// ============================================================
// Aho-Corasick 多模式匹配器
// 一次性构建Trie + 失败指针，扫一遍文本出所有命中
// 时间复杂度 O(文本长度 + 命中数)，替代 O(关键词数 × 文本长度)
// ============================================================
class AhoCorasickMatcher(keywords: List<String>) {

    data class Node(
        val children: MutableMap<Char, Node> = mutableMapOf(),
        var fail: Node? = null,
        var output: Boolean = false,  // 是否有关键词在此节点结束
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
        val queue: Queue<Node> = LinkedList()
        // 深度1的节点 fail 指向 root
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

    /**
     * @return true 如果文本包含任意关键词
     */
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
