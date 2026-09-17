package com.a10miaomiao.bilimiao.comm.utils

/**
 * av 号 ↔ BV 号。
 *
 * 为什么需要它：空降助手（SponsorBlock）的查询与提交**只认 BV 号**
 * （哈希端点是 `SHA256(裸BVID)[:4]`），但本项目内部一路用的是 `aid`（av 号）。
 *
 * 现在主路径是**把接口给的真实 bvid 一路传下来**（`PlayListItemInfo.bvid` →
 * `VideoPlayerSource.bvid`，见 `VideoDetailViewModel` 与 `PlayListStore`），
 * 本工具只作为**兜底**：某些入口（老的 av 链接、没有 bvid 字段的实体）拿不到真 BV 时才现算。
 *
 * 算法已用真实数据校验：av2→BV1xx411c7mD、av170001→BV17x411w7KC、
 * av98935548→BV14741127BN，以及仓库里现成的 av868892769↔BV1sV4y167iq。
 */
object BvUtils {

    private const val TABLE = "fZodR9XQDSUm21yCkr6zBqiveYah8bt4xsWpHnJE7jL5VG3guMTKNPAwcF"
    private const val XOR = 177451812L
    private const val ADD = 8728348608L
    private val POS = intArrayOf(11, 10, 3, 8, 4, 6)

    /**
     * 判断一个字符串**是不是合法的 BV 号**（`BV` + 10 位字母数字，共 12 字符，如 `BV14741127BN`）。
     *
     * ★ 为什么必须有这个函数：`_id` / `playerState.aid` 这类变量**不一定是 BV 号**——
     *   「继续播放」卡片（`StartViewContent.kt:312`）、历史/收藏/稍后再看卡片
     *   （`StartLibraryCard.kt:211`）、点赞消息、动态里的 UGC 合集等入口传进来的都是 **av 号**。
     *   如果直接把它当 bvid 用，就会去查 `videoID=98935548` → 服务端返回空数组 →
     *   **片头片尾等片段全部消失**（用户报的"怎么又不见了"就是这么来的）。
     */
    fun isValidBvid(s: String?): Boolean {
        val v = s?.trim().orEmpty()
        if (v.length != 12) return false
        // ★ 必须**大写** BV：服务端的哈希是 SHA256(裸BVID)，大小写敏感，
        //   放小写进去会算出一个查不到的哈希（静默失败，最难查的那种）
        if (!v.startsWith("BV")) return false
        return v.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
    }

    /**
     * 吃 av 号（`123` / `av123`）、BV 号（`BV1xx411c7mD`）或 null；
     * 返回 BV 号，无法识别时返回 null。
     */
    fun toBvid(aidOrBvid: String?): String? {
        val raw = aidOrBvid?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // 长得像 BV 号也要**校验形态**：`BV` 开头的垃圾串（比如整串 av 号被误当 bv 传进来）
        // 直接放行会查到别的视频或空数据，宁可返回 null 让调用方现算。
        if (raw.startsWith("BV", ignoreCase = true)) return raw.takeIf { isValidBvid(it) }
        val av = raw.removePrefix("av").removePrefix("AV").toLongOrNull() ?: return null
        if (av <= 0) return null
        val x = (av xor XOR) + ADD
        // ★ 经典算法只有 6 位 base58，能表示的 av 上限是 58^6 = 38068692544
        //   （约 2.9e10，而当前真实 aid 才 ~1e9，所以对老视频完全够用）。
        //   但**新出的 BV 号已经不按这套算法编码了**：拿 BV1xVY26dEbz 反算会得到一个
        //   1.17e14 这种明显不可能是 aid 的数；把这种数塞进公式会算出**完全不相干的 BV**
        //   （→ 查别人的片段、跳错地方，比查不到危险得多）。所以超出范围一律返回 null，
        //   交给调用方去用接口返回的真实 bvid。
        if (x >= 38068692544L) return null
        val chars = "BV1  4 1 7  ".toCharArray()
        for (i in POS.indices) {
            var p = 1L
            repeat(i) { p *= 58 }
            chars[POS[i]] = TABLE[((x / p) % 58).toInt()]
        }
        return String(chars)
    }
}
