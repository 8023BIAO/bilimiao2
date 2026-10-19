package com.a10miaomiao.bilimiao.comm.utils

/**
 * 标题净化（默认关闭，由设置开关控制）。
 *
 * 纯 Kotlin、无第三方依赖、无网络、无模型；只做字符串清洗 + 基于分句/保护词的保守缩短。
 * 规则来源：内部规则 v6（A 清洗 + AI/反AI 去壳 + 钩子尾巴清理 + 保守缩短）。
 *
 * 设计约束（重要）：
 *  - 只用于“显示层”，不改数据、不影响播放/下载/搜索请求；
 *  - 任何一步失败（空标题/丢数字/书名/集数/括号不配对/过短）→ 回退上一步结果，最终可回退原文；
 *  - 结果按原文缓存（LRU 1024），列表滚动不重复计算。
 */
object TitleCleaner {

    /** 非 Compose 场景（播放器标题/通知/媒体元数据）读这个；Compose 场景由本地 CompositionLocal 控制。 */
    @Volatile
    var enabled: Boolean = false

    private const val CACHE_MAX = 1024

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > CACHE_MAX
        }
    }

    fun cleanIfEnabled(raw: String?): String? {
        if (!enabled || raw.isNullOrBlank()) return raw
        return clean(raw)
    }

    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return raw ?: ""
        synchronized(cache) {
            cache[raw]?.let { return it }
        }
        val result = cleanInternal(raw)
        synchronized(cache) {
            cache[raw] = result
        }
        return result
    }

    // ================= 主流程 =================

    private fun cleanInternal(raw: String): String {
        var s = normalizeL1(raw)
        s = removeMarketing(s)
        s = removeAiShells(s)
        s = removeLeadingRhetoricalQuestion(s)
        s = removeBaitTails(s)
        s = shortenIfNeeded(s)
        s = tidy(s)
        return if (s.isBlank()) raw.trim() else s
    }

    // ================= L1：安全归一 =================

    private val HTML_TAG = Regex("<[^>]+>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")
    private val REPEAT_PUNCT = Regex("([，,。．！!？?；;：:、…~～·|｜/／])\\1+")
    private val MULTI_SPACE = Regex("\\s+")
    private val CJK_SPACE = Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])")
    private val EMPTY_BRACKET = Regex("[【\\[（(「『]\\s*[】\\]）)」』]")
    private const val TRIM_CHARS = " \t\n\r，,、；;：:·-—–_|｜/\\~～…"
    private const val DELIMITER_CHARS = " ，,。.!！?？：:；;、~～…|｜/\\-—_"

    private fun normalizeL1(raw: String): String {
        var s = HTML_TAG.replace(raw, "")
        s = decodeEntities(s)
        s = stripEmojiAndInvisible(s)
        s = normalizeFullWidth(s)
        s = MULTI_SPACE.replace(s, " ")
        s = CJK_SPACE.replace(s, "")
        s = REPEAT_PUNCT.replace(s) { it.groupValues[1] }
        s = EMPTY_BRACKET.replace(s, "")
        s = s.trim { TRIM_CHARS.indexOf(it) >= 0 }
        return s
    }

    private fun decodeEntities(s: String): String {
        return ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e == "amp" -> "&"
                e == "lt" -> "<"
                e == "gt" -> ">"
                e == "quot" -> "\""
                e == "apos" -> "'"
                e == "nbsp" -> " "
                e.startsWith("#x") || e.startsWith("#X") -> {
                    val cp = e.substring(2).toIntOrNull(16)
                    if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else ""
                }
                e.startsWith("#") -> {
                    val cp = e.substring(1).toIntOrNull()
                    if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else ""
                }
                else -> m.value
            }
        }
    }

    private fun stripEmojiAndInvisible(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val cc = Character.charCount(cp)
            if (!isEmojiOrInvisible(cp)) {
                sb.appendCodePoint(cp)
            }
            i += cc
        }
        return sb.toString()
    }

    private fun isEmojiOrInvisible(cp: Int): Boolean {
        return cp == 0x200D || cp == 0x20E3 || cp == 0xFEFF || cp == 0x00AD ||
            cp in 0xFE00..0xFE0F ||
            cp in 0x200B..0x200F ||
            cp in 0x2060..0x206F ||
            cp in 0x1F000..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x2B00..0x2BFF ||
            cp in 0x1F1E6..0x1F1FF ||
            cp in 0xE0000..0xE007F
    }

    private fun normalizeFullWidth(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val code = ch.code
            val normalized = when {
                code in 0xFF10..0xFF19 || code in 0xFF21..0xFF3A || code in 0xFF41..0xFF5A ->
                    (code - 0xFEE0).toChar()
                else -> ch
            }
            sb.append(normalized)
        }
        return sb.toString()
    }

    // ================= L2：营销/噪音/情绪壳 =================

    // 多字、明确是 CTA/营销壳的短语：可以直接删
    private val CTA_STRONG = listOf(
        "一键三连", "点赞投币", "记得关注", "求关注", "建议收藏", "强烈推荐",
        "记得三连", "下集预告", "关注我"
    )

    // 单/双字词：只在标题首尾、且与内容之间有分隔符时删（避免“关注阿尔茨海默病”“电影预告”被误伤）
    private val CTA_EDGE = listOf(
        "点赞", "投币", "收藏", "关注", "三连", "转发", "分享", "私信", "弹幕",
        "评论区", "务必", "赶紧", "速看", "记得", "别忘了", "下集", "预告"
    )

    private val MARKETING_STRONG = listOf(
        "保姆级", "手把手教学", "手把手", "干货满满", "干货分享", "纯干货",
        "一文看懂", "一文读懂", "一文了解", "全网最全", "最全整理",
        "万字长文", "千字长文", "深度解析", "深度解读", "深度剖析", "深度推演",
        "全网首发", "重磅消息", "重磅发布", "重磅来袭", "震撼来袭", "强势来袭",
        "我们的时间还剩多少", "能让你保命的一期", "保命的一期", "再不看就晚了",
        "最后的机会", "看完你就懂了", "看完就懂", "细思极恐", "不寒而栗", "时间不多了"
    )

    private val MARKETING_EDGE = listOf(
        "重磅", "炸裂", "燃爆", "碾压", "吊打", "封神", "逆天", "无敌", "天花板",
        "揭秘", "曝光", "内幕", "打脸", "反转", "实锤", "怒斥", "破防", "泪目",
        "笑死", "救命", "跪求", "细品", "保命", "你敢信", "你知道吗"
    )

    private val NOISE_TAGS = setOf(
        "高能", "前方高能", "高能预警", "全程高能", "必看", "速看", "强烈推荐",
        "建议收藏", "爆笑", "泪目", "震惊", "警告", "快看", "注意", "干货"
    )

    private val SHELL_START = Regex(
        "^(?:震惊瘫坐|震惊|惊呆|惊呆了|重磅|炸裂|逆天|封神|绝了|泪目|破防|笑死|救命|跪求|注意看|前方高能|高能预警|快看|速看)[！!，,：:\\s]+"
    )

    private val BRACKET_CONTENT = Regex("[【\\[［「『]([^】\\]］」』]{1,8})[】\\]］」』]")
    private val VERSION_PAREN = Regex("[（(](?:新版|修复版|高清版|超清版|合集版|典藏版|重制版|加长版)[）)]")

    private fun removeStrongPhrases(s0: String, phrases: List<String>): String {
        var s = s0
        for (p in phrases.sortedByDescending { it.length }) s = s.replace(p, "")
        return s
    }

    private fun removeEdgeDelimited(s0: String, words: List<String>): String {
        var s = s0.trim { TRIM_CHARS.indexOf(it) >= 0 }
        var changed = true
        while (changed) {
            changed = false
            for (w in words.sortedByDescending { it.length }) {
                if (s.length <= w.length + 2) continue
                if (s.startsWith(w)) {
                    val next = s[w.length]
                    if (DELIMITER_CHARS.indexOf(next) >= 0) {
                        s = s.substring(w.length).trimStart { TRIM_CHARS.indexOf(it) >= 0 }
                        changed = true
                        break
                    }
                }
                if (s.endsWith(w)) {
                    val prev = s[s.length - w.length - 1]
                    if (DELIMITER_CHARS.indexOf(prev) >= 0) {
                        s = s.substring(0, s.length - w.length).trimEnd { TRIM_CHARS.indexOf(it) >= 0 }
                        changed = true
                        break
                    }
                }
            }
        }
        return s
    }

    private fun removeMarketing(s0: String): String {
        var s = SHELL_START.replace(s0, "")
        s = removeStrongPhrases(s, CTA_STRONG)
        s = removeEdgeDelimited(s, CTA_EDGE)
        s = removeStrongPhrases(s, MARKETING_STRONG)
        s = removeEdgeDelimited(s, MARKETING_EDGE)
        s = BRACKET_CONTENT.replace(s) { m ->
            if (NOISE_TAGS.contains(m.groupValues[1].trim())) "" else m.value
        }
        s = VERSION_PAREN.replace(s, "")
        s = tidy(s)
        // 删太狠（少于 6 字 / 不足原来一半）就整体回退，宁可不清
        if (s.isBlank() || s.length < 6 || s.length < s0.length * 0.5) return tidy(s0)
        // 20 字以内的短标题：删词比例超过 25% 就整体回退，避免“乱七八糟”
        if (s0.length <= 20 && s.length < s0.length * 0.75) return tidy(s0)
        return s
    }

    // ================= AI / 反AI 题材去壳 =================

    private val AI_TOPIC = Regex(
        "(?i)(AI|人工智能|大模型|DeepSeek|GPT|AGI|LLM|生成式|算法|机器人|算力|OpenAI|Claude|Gemini|豆包|文心|通义|混元|Kimi|Sora|智能体|Agent|机器学习|神经网络|MCP|硅基|英伟达|NVIDIA)"
    )

    private val AI_SHELLS = listOf(
        "震惊瘫坐", "震惊", "惊呆", "重磅", "史诗级", "炸裂", "燃爆", "碾压", "吊打",
        "封神", "逆天", "无敌", "天花板", "全网最全", "全网首发", "全网",
        "深度推演", "深度解析", "深度解读", "深度剖析", "万字长文", "千字长文",
        "一文看懂", "一文读懂", "一文了解", "保姆级", "手把手", "干货",
        "建议收藏", "值得收藏", "强烈推荐", "务必", "赶紧", "速看", "快看",
        "我们的时间还剩多少", "能让你保命的一期", "保命的一期", "保命",
        "你敢信", "你知道吗", "看完你就懂了", "细思极恐", "不寒而栗",
        "揭秘", "曝光", "内幕", "打脸", "反转", "实锤", "怒斥"
    )

    private fun removeAiShells(s: String): String {
        if (!AI_TOPIC.containsMatchIn(s)) return s
        var t = s.replace("震惊瘫坐", "")
        t = removeStrongPhrases(t, AI_SHELLS.filter { it.length >= 3 })
        t = removeEdgeDelimited(t, AI_SHELLS.filter { it.length < 3 })
        t = tidy(t)
        // 删太多（丢了一半）就回退，宁可不清
        if (t.isBlank() || t.length < s.length * 0.5) return s
        return t
    }

    // ================= 钩子尾巴（么/吧/嗯/吗/呢/啊/呀/嘛/啦 + 问号） =================

    // 开头"反问句 + 回答"结构：为什么X？别急，答案是Y → X，答案是Y（把反问改成陈述，去掉"别急"）
    private val LEAD_QUESTION = Regex("^(为什么|为啥|为何|难道|到底|凭什么|怎么|怎样|如何)")
    private val LEAD_DISCOURSE = listOf("别急", "别慌", "先说结论", "直接说", "不卖关子", "答案是", "其实", "原来", "告诉你")

    private fun removeLeadingRhetoricalQuestion(s0: String): String {
        if (!s0.contains('？') && !s0.contains('?')) return s0
        val clauses = splitClauses(s0)
        if (clauses.size < 2) return s0
        var newFirst = clauses.first().trim()
        var changed = false
        // 循环剥前缀：“到底为什么…”先剥“到底”再剥“为什么”，避免残留问句
        while (true) {
            val trimmed = LEAD_QUESTION.replaceFirst(newFirst, "").trim { TRIM_CHARS.indexOf(it) >= 0 }
            if (trimmed == newFirst) break
            newFirst = trimmed
            changed = true
        }
        if (!changed || newFirst.length < 4) return s0
        // 剥完仍是疑问句/残句（才能…/能…）就回退，避免把正常问句改坏
        if (GENUINE_QUESTION.containsMatchIn(newFirst)) return s0
        if (newFirst.endsWith('吗') || newFirst.endsWith('呢') ||
            newFirst.endsWith('吧') || newFirst.endsWith('么')
        ) return s0
        if (newFirst.matches(Regex("^(才能|能|会|要|是|有|就|还|也|都|才|不|没|别).*"))) return s0
        val rest = clauses.drop(1).mapNotNull { c ->
            var t = c.trim()
            for (d in LEAD_DISCOURSE) {
                if (t == d) return@mapNotNull null
                if (t.startsWith(d + "：") || t.startsWith(d + ":")) {
                    t = t.substring(d.length + 1).trim()
                    break
                }
            }
            t.ifBlank { null }
        }
        if (rest.isEmpty()) return s0
        val candidate = (listOf(newFirst) + rest).joinToString("，")
        return if (acceptableRemainder(candidate, s0, allowTrailingLe = true)) candidate else s0
    }

    private val GENUINE_QUESTION = Regex("(为什么|为何|怎么|怎样|如何|是什么|什么|谁|哪|多少|是否|能不能|会不会)")
    private val BAIT_TAIL = Regex(
        "(?:[么吧嗯呢啊呀嘛啦吗]|了吧|了吗|了呢|的么|了么|的吗|好吗|行吗|是吗|对吗|可以吗|好不好|行不行)\\s*[？?！!。～~]*\\s*$"
    )
    private val PROTECT_NOUN_ENDINGS = listOf(
        "什么", "怎么", "这么", "那么", "多么", "要么", "为什么", "懂么",
        "酒吧", "网吧", "贴吧", "吧台", "吧友", "吧主", "嗯哼"
    )
    private const val DANGLE_CHARS = "的了和与及而但却被把让给对从向将能会要如果因为所以然后是在有这那里中时后前上下内外间等地得所"

    private val SPLIT_CHARS = "，,。！？；;：:…~～|｜/／、".toCharArray().toSet()

    private fun splitClauses(s: String): List<String> {
        val parts = ArrayList<String>()
        val buf = StringBuilder()
        var depth = 0
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '【' || ch == '[' || ch == '「' || ch == '『' || ch == '《' ||
                ch == '（' || ch == '(' || ch == '“'
            ) {
                depth++
            } else if (ch == '】' || ch == ']' || ch == '」' || ch == '』' || ch == '》' ||
                ch == '）' || ch == ')' || ch == '”'
            ) {
                if (depth > 0) depth--
            }
            // 保护 5:20 / 10:30 这类时间冒号
            val isTimeColon = (ch == ':' || ch == '：') && i > 0 && i + 1 < s.length &&
                s[i - 1].isLetterOrDigit() && s[i + 1].isLetterOrDigit()
            if (ch in SPLIT_CHARS && depth == 0 && !isTimeColon) {
                if (buf.isNotBlank()) parts.add(buf.toString().trim())
                buf.setLength(0)
            } else {
                buf.append(ch)
            }
            i++
        }
        if (buf.isNotBlank()) parts.add(buf.toString().trim())
        return parts
    }

    /**
     * “陈述 + 反问/疑问尾巴”结构：前文能独立成义、尾巴短且没有硬信息时，把尾巴整句删掉。
     * 例：“所有内存容量解析。你需要多少内存？”→“所有内存容量解析”
     * 单句疑问（没有前文）不动。
     */
    private fun shouldDropRhetoricalTail(clauses: List<String>): Boolean {
        if (clauses.size < 2) return false
        val last = clauses.last()
        val head = clauses.dropLast(1).joinToString("，")
        if (last.length > 12 || head.length < 8) return false
        if (HARD_TOKEN.containsMatchIn(last)) return false
        val isQuestion = GENUINE_QUESTION.containsMatchIn(last) ||
            BAIT_TAIL.containsMatchIn(last) || last.contains('？') || last.contains('?')
        if (!isQuestion) return false
        // 只在尾巴带明确“钩子特征”时删；纯内容疑问（什么体验/哪些机会）保留
        val hasHook = last.contains('你') || last.contains("大家") || last.contains("咱") ||
            last.contains("你猜") || last.contains("有没有") || last.contains("是不是") ||
            BAIT_TAIL.containsMatchIn(last)
        if (!hasHook) return false
        return VERB.containsMatchIn(head) || HARD_TOKEN.containsMatchIn(head) || NUM_UNIT.containsMatchIn(head)
    }

    private fun isBaitClause(c: String): Boolean {
        if (c.isBlank()) return false
        if (GENUINE_QUESTION.containsMatchIn(c)) return false
        for (prot in PROTECT_NOUN_ENDINGS) if (c.endsWith(prot)) return false
        return BAIT_TAIL.containsMatchIn(c)
    }

    private fun acceptableRemainder(
        candidate: String,
        original: String,
        allowTrailingLe: Boolean = false,
    ): Boolean {
        val c = candidate.trim { TRIM_CHARS.indexOf(it) >= 0 }
        if (c.length < 8) return false
        if (!c.any { it in '\u4e00'..'\u9fff' }) return false
        if (c.last() in DANGLE_CHARS && !(allowTrailingLe && c.last() == '了')) return false
        if (!bracketBalanced(c)) return false
        if (hasHardLoss(original, c)) return false
        return true
    }

    private fun removeBaitTails(s0: String): String {
        var s = s0
        val clauses = splitClauses(s)
        if (clauses.size >= 2 &&
            (isBaitClause(clauses.last()) || shouldDropRhetoricalTail(clauses))
        ) {
            val candidate = clauses.dropLast(1).joinToString("，")
            if (acceptableRemainder(candidate, s)) s = candidate
        }
        if (s.contains('？') || s.contains('?')) {
            s = s.replace(Regex("[？?！!。～~…]+\\s*$"), "").trim()
            var protected = false
            for (prot in PROTECT_NOUN_ENDINGS) {
                if (s.endsWith(prot)) {
                    protected = true
                    break
                }
            }
            if (!protected) {
                if (!GENUINE_QUESTION.containsMatchIn(s)) {
                    // 好吗：还/挺/很/太/真/不 + 好吗 → 保留“好”（你还好吗→你还好）；其余“好吗”整段删
                    s = s.replace(Regex("(?<=[还挺很太真不])好吗\\s*$"), "好")
                    s = s.replace(Regex("(?<![还挺很太真不])好吗\\s*$"), "")
                    // 行吗/对吗/是吗/可以吗/能吗 → 只删“吗”，保留前面的词
                    s = s.replace(Regex("(?<=[行对是可以能])吗\\s*$"), "")
                    s = s.replace(Regex("(?:吗|么|吧|嗯|呢|啊|呀|嘛|啦)+\\s*$"), "")
                } else {
                    s = s.replace(Regex("(?:吧|嗯)\\s*$"), "")
                }
                s = s.trimEnd('的', '，', ',', '、', '：', ':', '；', ';', ' ')
            }
            s = s.replace(Regex("^(?:难道|莫非|岂不是|不是)"), "").trim()
        }
        return tidy(s)
    }

    // ================= 缩短：只对清洗后 > 30 字 =================

    private val HARD_TOKEN = Regex(
        "第[0-9一二三四五六七八九十百]+[集期话回季]|EP?\\s*\\d+|P\\s*\\d+|S\\d+E\\d+|" +
            "4K|8K|1080P|720P|HDR|中字|双语|无删减|未删减|完整版|" +
            "《[^》]{1,20}》|「[^」]{1,20}」|『[^』]{1,20}』|\\d{4}年|\\d+(?:\\.\\d+)?[万千百亿%+]?",
        RegexOption.IGNORE_CASE
    )

    // ★只删“纯背景/来源/框架”分句；条件、让步、因果、目的从句一律保留（避免丢语境，如“假如三年前…”）
    private val FRONT_DELETE = listOf(
        "经过", "通过", "针对", "对于", "关于", "作为", "据",
        "听说", "据说", "据报道", "截至", "按照", "根据", "除了", "而不是"
    )

    // 含“数字+单位”的分句不当作可删噪音（含中文数字：三年的、十把、两集…）
    private val NUM_UNIT = Regex("[零一二三四五六七八九十百千万两0-9]+\\s*(?:年|月|日|号|把|个|次|元|块|级|集|人|岁|期|季|版|帧|分钟|小时|秒)")

    private val VERB = Regex(
        "(是|了|在|有|这|那|能|会|要|让|把|被|给|对|从|向|将|发布|上线|推出|更新|测试|体验|盘点|讲|说|看|做|来|去|成|用|玩|吃|跑|打|写|拍|改|拆|测|评|解析|揭秘|记录|挑战|对比|回顾|自制|复刻|还原|进化|翻车|来了|曝光|回应|官宣|宣布|公布|开放|开服|登陆|登顶|夺冠|亮相|上演|定档|开播|完结|停更|回归)"
    )

    private val MARKETING_FOR_ROLE = Regex(
        "(点赞|投币|收藏|关注|三连|建议收藏|强烈推荐|干货|保姆级|手把手|一键三连)"
    )

    private val DISCOURSE_START = listOf(
        "其实", "原来", "结果", "居然", "竟然", "真的", "简直", "说实话", "讲真",
        "不得不说", "没想到", "事实上", "说白了", "家人们", "然后", "于是", "总之"
    )

    private val SINGLE_PREFIX = Regex(
        "^(?:其实|原来|结果|居然|竟然|真的|简直|说实话|讲真|不得不说|没想到|事实上|说白了|家人们)[，,：:\\s]*"
    )
    private val TRAILING_TIME_FRAME = Regex("(?:的时候|之后|之前|以来|的话|等等|什么的|之类的)$")
    private val PAREN_CONTENT = Regex("[（(][^）)]{0,20}[）)]")

    private fun shortenIfNeeded(s: String): String {
        if (s.replace(" ", "").length <= 30) return s
        val candidates = ArrayList<String>()

        val clauses = splitClauses(s)
        if (clauses.isEmpty()) return s
        val roles = clauses.map { it to isNoiseClause(it) }
        val kept = roles.filter { !it.second }.map { it.first }
        if (kept.isEmpty()) return s

        val scored = kept.map { Pair(clauseScore(it), it) }.sortedByDescending { it.first }
        val main = scored.first().second
        if (main.length <= 25) candidates.add(main)

        val hook = kept.firstOrNull { GENUINE_QUESTION.containsMatchIn(it) }
        if (hook != null && hook != main) {
            val combo = "$main，$hook"
            if (combo.length <= 30) candidates.add(combo)
        }

        // 贪心累加（按原顺序）
        val acc = ArrayList<String>()
        for (c in kept) {
            if ((acc + c).joinToString("，").length <= 25) acc.add(c) else break
        }
        if (acc.isNotEmpty()) candidates.add(acc.joinToString("，"))

        // 去掉噪音分句后贪心
        val acc2 = ArrayList<String>()
        for (c in kept) {
            if ((acc2 + c).joinToString("，").length <= 30) acc2.add(c) else break
        }
        if (acc2.isNotEmpty()) candidates.add(acc2.joinToString("，"))

        // 单句压缩
        val single = singleCompress(main)
        if (single != null && single.length < main.length) candidates.add(single)

        var best: String? = null
        for (c in candidates) {
            val t = c.trim { TRIM_CHARS.indexOf(it) >= 0 }
            if (!acceptableShortened(s, t)) continue
            if (best == null || t.length < best!!.length) best = t
        }
        return best ?: s
    }

    private fun isNoiseClause(c: String): Boolean {
        val t = c.trim()
        if (NUM_UNIT.containsMatchIn(t)) return false
        if (t.length < 4) return true
        if (MARKETING_FOR_ROLE.containsMatchIn(t) && t.length <= 24) return true
        if (t.replace(Regex("[\\s\\d.\\-+%A-Za-z、,，/｜|·]"), "").isEmpty()) return true
        for (w in FRONT_DELETE) if (t.startsWith(w)) return true
        val timeStart = Regex("^(?:今天|昨天|前天|刚刚|最近|近日|日前|现在|如今|今年|去年|明年|20\\d{2}年|\\d{1,2}月|星期|周)")
        if (timeStart.containsMatchIn(t)) return true
        val placeStart = Regex("^(?:在|从|于|位于|全网|全国|全球|国内|国外|海外|线下|线上)")
        if (placeStart.containsMatchIn(t)) return true
        for (w in DISCOURSE_START) if (t.startsWith(w)) return true
        return false
    }

    private fun clauseScore(c: String): Int {
        var sc = 0
        if (HARD_TOKEN.containsMatchIn(c)) sc += 3
        if (GENUINE_QUESTION.containsMatchIn(c)) sc += 2
        if (VERB.containsMatchIn(c)) sc += 2
        if (c.length in 8..24) sc += 1
        if (MARKETING_FOR_ROLE.containsMatchIn(c)) sc -= 2
        return sc
    }

    private fun singleCompress(s: String): String? {
        var t = s
        t = SINGLE_PREFIX.replace(t, "")
        t = PAREN_CONTENT.replace(t, "")
        t = TRAILING_TIME_FRAME.replace(t, "")
        t = t.trim { TRIM_CHARS.indexOf(it) >= 0 }
        return if (t.length >= 8 && t != s) t else null
    }

    private fun acceptableShortened(original: String, candidate: String): Boolean {
        val c = candidate.trim { TRIM_CHARS.indexOf(it) >= 0 }
        if (c.length !in 8..30) return false
        if (!c.any { it in '\u4e00'..'\u9fff' }) return false
        if (c.last() in DANGLE_CHARS) return false
        if (!bracketBalanced(c)) return false
        if (hasHardLoss(original, c)) return false
        return true
    }

    // ================= 工具 =================

    private fun hasHardLoss(original: String, candidate: String): Boolean {
        return hardTokens(original).minus(hardTokens(candidate)).isNotEmpty()
    }

    private fun hardTokens(s: String): Set<String> {
        val norm = s.replace(Regex("\\s+"), "")
        val tokens = HashSet<String>()
        HARD_TOKEN.findAll(norm).forEach { tokens.add(it.value) }
        NUM_UNIT.findAll(norm).forEach { tokens.add(it.value) }
        return tokens
    }

    private fun bracketBalanced(s: String): Boolean {
        return s.count { it == '【' } == s.count { it == '】' } &&
            s.count { it == '《' } == s.count { it == '》' } &&
            s.count { it == '「' } == s.count { it == '」' } &&
            s.count { it == '『' } == s.count { it == '』' } &&
            s.count { it == '（' } == s.count { it == '）' } &&
            s.count { it == '(' } == s.count { it == ')' }
    }

    private fun tidy(s0: String): String {
        var s = s0
        s = EMPTY_BRACKET.replace(s, "")
        s = REPEAT_PUNCT.replace(s) { it.groupValues[1] }
        s = MULTI_SPACE.replace(s, " ").trim()
        s = CJK_SPACE.replace(s, "")
        s = s.trim { TRIM_CHARS.indexOf(it) >= 0 }
        return s
    }
}
