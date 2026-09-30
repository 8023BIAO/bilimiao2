package cn.a10miaomiao.bilimiao.compose.components.community

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 评论正文 @昵称 **最长匹配** 规则的单元测试（纯正则逻辑，不依赖 android / compose，不需要设备）。
 *
 * 背景：`buildReplyTextRegex` 的 `@` 支路原来按昵称表的原始顺序拼 `|`，而正则的 `|` 是
 * "左边先试、先匹配上就算赢"，**不是**最长匹配 ⇒ 表里同时有"张三"和"张三丰"时，
 * `@张三丰` 会被 `@张三` 抢走、只剩一个"丰"。修法：候选昵称按长度倒序拼。
 *
 * ★这组用例同时兼作**变异测试**：把 `sortedByDescending { it.length }` 去掉（退回前缀匹配），
 *   下面 `atLongerNameWins` / `atLongerNameThenLeftoverChar` / `laddersAbcAbA` /
 *   `atResultIsIndependentOfSetOrder` 必须失败。
 */
class ReplyTextRegexTest {

    /** 只取命中片段（等价于 buildAnnotatedTextNodes 里那些"非普通文本"节点） */
    private fun hits(message: String, names: Set<String>): List<String> =
        buildReplyTextRegex(names).findAll(message).map { it.value }.toList()

    /** 命中片段 + 中间夹的普通文本，与 buildAnnotatedTextNodes 的节点序列一一对应 */
    private fun segments(message: String, names: Set<String>): List<String> {
        val out = mutableListOf<String>()
        var lastEnd = 0
        buildReplyTextRegex(names).findAll(message).forEach {
            if (it.range.first != lastEnd) out.add(message.substring(lastEnd, it.range.first))
            out.add(it.value)
            lastEnd = it.range.last + 1
        }
        if (lastEnd < message.length) out.add(message.substring(lastEnd))
        return out
    }

    // ---------- 昵称表 {张三, 张三丰}：短名在前，正是旧实现翻车的顺序 ----------

    @Test
    fun atLongerNameWins() {
        assertEquals(listOf("@张三丰"), hits("@张三丰 你好", setOf("张三", "张三丰")))
        assertEquals(listOf("@张三丰", " 你好"), segments("@张三丰 你好", setOf("张三", "张三丰")))
    }

    @Test
    fun atShorterNameStillMatchesAlone() {
        assertEquals(listOf("@张三"), hits("@张三 你好", setOf("张三", "张三丰")))
        assertEquals(listOf("@张三", " 你好"), segments("@张三 你好", setOf("张三", "张三丰")))
    }

    @Test
    fun atLongerNameThenLeftoverChar() {
        // 最长优先 ⇒ 命中 @张三丰，多出来的"丰"是普通文本（旧实现命中 @张三 + "丰丰"）
        assertEquals(listOf("@张三丰"), hits("@张三丰丰", setOf("张三", "张三丰")))
        assertEquals(listOf("@张三丰", "丰"), segments("@张三丰丰", setOf("张三", "张三丰")))
    }

    @Test
    fun atUnknownNameIsPlainText() {
        assertEquals(emptyList<String>(), hits("@李四 好", setOf("张三", "张三丰")))
        assertEquals(listOf("@李四 好"), segments("@李四 好", setOf("张三", "张三丰")))
    }

    @Test
    fun atTwoNamesInOneMessage() {
        assertEquals(listOf("@张三丰", "@张三"), hits("@张三丰@张三", setOf("张三", "张三丰")))
        assertEquals(listOf("@张三丰", "@张三"), segments("@张三丰@张三", setOf("张三", "张三丰")))
    }

    @Test
    fun atInMiddleOfText() {
        assertEquals(listOf("文本", "@张三丰", "结尾"), segments("文本@张三丰结尾", setOf("张三", "张三丰")))
    }

    @Test
    fun atResultIsIndependentOfSetOrder() {
        val expected = listOf("@张三丰")
        assertEquals(expected, hits("@张三丰 你好", linkedSetOf("张三", "张三丰")))
        assertEquals(expected, hits("@张三丰 你好", linkedSetOf("张三丰", "张三")))
        assertEquals(expected, hits("@张三丰 你好", setOf("张三丰", "张三")))
    }

    // ---------- 昵称表 {A, AB, ABC} ----------

    @Test
    fun laddersAbcAbA() {
        val names = setOf("A", "AB", "ABC")
        assertEquals(listOf("@ABC"), hits("@ABC", names))
        assertEquals(listOf("@AB"), hits("@AB", names))
        assertEquals(listOf("@A"), hits("@A", names))
    }

    @Test
    fun laddersLongestThenShortest() {
        assertEquals(listOf("@ABC", "@A"), hits("@ABC@A", setOf("A", "AB", "ABC")))
        // "@ABCD"：表里最长只能到 ABC，剩下的 D 是普通文本
        assertEquals(listOf("@ABC"), hits("@ABCD", setOf("A", "AB", "ABC")))
        assertEquals(listOf("@ABC", "D"), segments("@ABCD", setOf("A", "AB", "ABC")))
    }

    // ---------- 空昵称表 / 空文本 ----------

    @Test
    fun emptyNameTableHasNoAtMatch() {
        assertEquals(emptyList<String>(), hits("@张三丰", emptySet()))
        assertEquals(listOf("@张三丰"), segments("@张三丰", emptySet()))
    }

    @Test
    fun emptyMessageGivesNoSegments() {
        assertEquals(emptyList<String>(), segments("", setOf("张三", "张三丰")))
        assertEquals(emptyList<String>(), hits("", setOf("张三", "张三丰")))
        assertEquals(emptyList<String>(), segments("", emptySet()))
    }

    // ---------- 回归：别的模式没被动 ----------

    @Test
    fun otherPatternsStillMatch() {
        val names = setOf("张三")
        assertEquals(listOf("av123"), hits("av123", names))
        assertEquals(listOf("BV1xx411c7mD"), hits("BV1xx411c7mD", names))
        assertEquals(listOf("01:23"), hits("跳到01:23", names))
        assertEquals(listOf("[微笑]"), hits("好[微笑]", names))
        assertEquals(listOf("@张三"), hits("回复@张三", names))
    }

    // ---------- 汉字紧贴的 av/BV/ac/sm/cv 号（词边界：`\b` → ASCII lookaround）----------

    @Test
    fun asciiWordBoundary_numberLinksAfterHan() {
        val names = setOf("张三")
        // ★汉字紧贴关键字：必须命中。旧规则用 JVM 的 Unicode 感知 `\b`（汉字算词字符），
        //   这几例在旧规则下**全部命中不了**（变异测试：把 lookaround 换回 `\b` 就该挂）。
        assertEquals(listOf("av123"), hits("看av123", names))
        assertEquals(listOf("av123"), hits("的av123不错", names))
        assertEquals(listOf("AV123"), hits("看AV123", names))
        assertEquals(listOf("BV1xx411c7mD"), hits("看BV1xx411c7mD", names))
        assertEquals(listOf("ac123"), hits("看ac123", names))
        assertEquals(listOf("sm123"), hits("看sm123", names))
        assertEquals(listOf("cv123"), hits("看cv123", names))
        assertEquals(listOf("看", "av123", "结尾"), segments("看av123结尾", names))
        // ★仍要拦住的：前后粘着 ASCII 词字符（这才是"词边界"的本意）
        assertEquals(emptyList<String>(), hits("xav123", names))
        assertEquals(emptyList<String>(), hits("av123abc", names))
        assertEquals(emptyList<String>(), hits("av123_", names))
        assertEquals(emptyList<String>(), hits("xBV1xx411c7mD", names))
        // 空白 / 标点 / 行首行尾照旧
        assertEquals(listOf("av123"), hits("看 av123", names))
        assertEquals(listOf("av123"), hits("av123。", names))
        assertEquals(listOf("av123"), hits("av123", names))
    }

    // ---------- URL 支：ASCII 头部边界 + 语义化尾部（末尾 `/` 保留、汉字与标点不吞）----------

    @Test
    fun url_asciiBoundaryAndSensibleTail() {
        val names = setOf("张三")
        // 头部：汉字紧贴也能识别；紧贴 ASCII 词字符仍不识别
        assertEquals(listOf("https://b23.tv/abc"), hits("看https://b23.tv/abc", names))
        assertEquals(listOf("看", "https://b23.tv/abc", "结尾"), segments("看https://b23.tv/abc结尾", names))
        assertEquals(emptyList<String>(), hits("xhttps://b23.tv/abc", names))
        // 尾部：末尾 `/` 保留（旧的 `\b` 会把它裁掉）
        assertEquals(listOf("https://b23.tv/abc/"), hits("https://b23.tv/abc/", names))
        assertEquals(listOf("https://b23.tv/abc/"), hits("https://b23.tv/abc/ 后面", names))
        assertEquals(listOf("https://b23.tv/"), hits("https://b23.tv/", names))
        // 尾部：汉字不吞（旧规则会把"后面"吞进链接）
        assertEquals(listOf("https://b23.tv/abc"), hits("https://b23.tv/abc后面", names))
        assertEquals(listOf("https://b23.tv/abc", "后面"), segments("https://b23.tv/abc后面", names))
        // 尾部：查询串 / 片段 / 百分号转义保留
        assertEquals(
            listOf("https://www.bilibili.com/video/BV1xx411c7mD?spm_id_from=333.999"),
            hits("https://www.bilibili.com/video/BV1xx411c7mD?spm_id_from=333.999", names)
        )
        assertEquals(listOf("https://b23.tv/a?b=1&c=2"), hits("https://b23.tv/a?b=1&c=2", names))
        assertEquals(listOf("https://b23.tv/abc#frag"), hits("https://b23.tv/abc#frag", names))
        assertEquals(listOf("https://b23.tv/search?q=%E4%B8%AD"), hits("https://b23.tv/search?q=%E4%B8%AD", names))
        // 尾部：中英文标点不吞
        assertEquals(listOf("https://b23.tv/abc"), hits("（https://b23.tv/abc）", names))
        assertEquals(listOf("https://b23.tv/abc"), hits("https://b23.tv/abc。", names))
        assertEquals(listOf("https://b23.tv/abc"), hits("见 https://b23.tv/abc, 谢谢", names))
        assertEquals(listOf("https://b23.tv/abc"), hits("https://b23.tv/abc...", names))
        assertEquals(listOf("https://b23.tv/abc"), hits("(https://b23.tv/abc)", names))
        // 尾部：`?` 与 `&` 同类一致（观感"显示完整"，别只留 `&` 却裁掉 `?`）
        assertEquals(listOf("https://b23.tv/a?"), hits("https://b23.tv/a?", names))
        assertEquals(listOf("https://b23.tv/a?b=1&"), hits("https://b23.tv/a?b=1&", names))
        // 尾部：括号配平的 URL 完整保留 `)`（维基类写法很常见）
        assertEquals(listOf("https://b23.tv/wiki/Foo_(bar)"), hits("https://b23.tv/wiki/Foo_(bar)", names))
        assertEquals(listOf("https://b23.tv/wiki/Foo_(bar)"), hits("https://b23.tv/wiki/Foo_(bar) 后面", names))
        assertEquals(listOf("https://b23.tv/wiki/Foo_(bar)"), hits("https://b23.tv/wiki/Foo_(bar),", names))
        // 尾部：配平括号**之后还有内容**也必须完整（锚点 / 路径 / 查询串 / 尾随字符都别丢）
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Foo_(bar)#Section"),
            hits("https://en.wikipedia.org/wiki/Foo_(bar)#Section", names),
        )
        assertEquals(listOf("https://example.com/a(b)/c"), hits("https://example.com/a(b)/c", names))
        assertEquals(listOf("https://example.com/a(b)?q=1&r=2"), hits("https://example.com/a(b)?q=1&r=2", names))
        assertEquals(listOf("https://x.com/a()b"), hits("https://x.com/a()b", names))
        // 尾部：配平括号后紧跟句读仍要回退（到 `)` 为止；`（…）` 的中文括号天然断开）
        assertEquals(listOf("https://x.com/a(b)"), hits("https://x.com/a(b).", names))
        assertEquals(listOf("https://b23.tv/wiki/Foo_(bar)"), hits("（https://b23.tv/wiki/Foo_(bar)）", names))
        // 尾部：不成对的连续句读仍要回退（去掉 `,)` 后链接照样能打开）
        assertEquals(listOf("https://b23.tv/abc"), hits("https://b23.tv/abc,)", names))
        assertEquals(listOf("https://b23.tv/abc"), hits("https://b23.tv/abc(", names))
        // 已知取舍：未转义的中文路径只链到 `/`（中文按 RFC3986 本该百分号转义）
        assertEquals(listOf("https://example.com/"), hits("https://example.com/中文", names))
        // 无路径 / 大小写 / www 前缀
        assertEquals(listOf("https://b23.tv"), hits("https://b23.tv", names))
        assertEquals(listOf("HTTPS://B23.TV/Abc"), hits("HTTPS://B23.TV/Abc", names))
        assertEquals(listOf("www.bilibili.com/video/x"), hits("www.bilibili.com/video/x", names))
    }

    // ---------- `@` 候选表里的空串/空白昵称（不能让裸 `@` 变成提及）----------

    @Test
    fun at_blankNamesAreIgnored() {
        assertEquals(emptyList<String>(), hits("@ 你好", setOf("张三", "")))
        assertEquals(emptyList<String>(), hits("@", setOf("张三", "")))
        assertEquals(emptyList<String>(), hits("@", setOf("", "   ")))
        assertEquals(emptyList<String>(), hits("@ ", setOf("", "   ")))
        // 正常昵称不受影响，最长匹配也照旧（长名放前面，避免这条重复承担"排序变异"的检出）
        assertEquals(listOf("@张三"), hits("@张三", setOf("张三", "")))
        assertEquals(listOf("@张三丰"), hits("@张三丰", setOf("张三丰", "", "张三")))
    }
}
