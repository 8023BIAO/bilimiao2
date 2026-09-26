package com.a10miaomiao.bilimiao.comm.entity.region

/**
 * 首页「分区」页左侧条的**分区表**（纯数据：没有请求、没有 UI 逻辑）。
 *
 * 为什么不再"完全以 x/v2/region/index 返回为准"（上一版的做法）：
 *  1. region/index **压根不返回「综艺」** —— 2026-02 用三种方式实测（app.bilibili.com / api.bilibili.com /
 *     带 APP 签名的完整 APP 请求）拿到的都是同一份 22 项顶级分区，里面没有综艺；
 *     综艺只存在于 PGC 的 `season_type=7`（PGC 榜实测 100 条）和「娱乐」的子分区 tid=71（ranking/v2 回 -400）。
 *     PiliPlus 也是把它写死在枚举里的：`lib/models/common/rank_type.dart:27` `variety('综艺', seasonType: 7)`。
 *  2. region/index 只给"有哪些分区"，不给"排行榜页该按什么顺序排"。左侧条是排行榜页，
 *     按 PiliPlus 的顺序排（全站 → 番剧 → 国创 → 动画 → … → 影视 → 记录 → 电影 → 剧集 → 综艺）更符合预期。
 *  3. 各分区**用的接口不一样**（有排行榜的走 ranking/v2，番剧/国创/记录/电影/剧集/综艺走 PGC 榜，
 *     资讯没有榜单但有分区投稿，剧情只有旧版分区榜）。写在一张表里，以后改一个分区只动一行；
 *     上一版把这些 if 写在页面里，出现"剧情/资讯点进去什么都没有"时得翻页面代码才知道为什么。
 *
 * 名字仍然"以接口为准"：HomeRegionContent 会用 region/index 里同一个 tid 的名字覆盖表里的名字
 * （接口叫「纪录片」「电视剧」，PiliPlus 叫「记录」「剧集」—— 我们跟 B站官方名字，因为搜索筛选页
 * RegionSelectPage 用的也是接口名字，两处要一致）。只有接口里根本没有的（综艺）才用表里的名字。
 *
 * 每一条后面都标了 **2026-02 的实名实测结果**（未登录 curl + buvid3，带不带签名/多带参数都复测过），
 * 完整对照表见 /root/test/分区子tab对照与修复-说明.md。
 */
object RegionCatalog {

    /**
     * 一个分区的内容从哪来。
     *
     * 注意这里**不是**"tid 大小"或"是不是 PGC"的判断，而是实测出来"这个分区只有哪个接口有东西"。
     */
    enum class Source {
        /** `/x/web-interface/ranking/v2?rid=&type=all`：普通分区排行榜，一次 60~100 条，接口没有 pn/ps（不能翻页） */
        UGC_RANK,

        /** `/pgc/season/rank/web/list?day=3&season_type=`：PGC 榜，一次 46~100 条，不能翻页 */
        PGC_SEASON_RANK,

        /** `/x/web-interface/newlist?rid=&pn=&ps=&order=pubdate`：分区最新投稿，**可以翻页**（本页只取 50 条的一页） */
        REGION_NEWLIST,

        /** `/x/web-interface/ranking/region?rid=&day=3`：旧版分区榜，只有 9~11 条，不能翻页 */
        LEGACY_REGION_RANK,
    }

    /**
     * 左侧条上的一项。
     *
     * @param key    稳定标识（Pager / ViewModel / rememberSaveable 都用它）。综艺在 region/index 里没有 tid，
     *               所以不能用 tid 当 key，统一用 "tid13"/"pgc7" 这种字符串。
     * @param tid    region/index 的 tid；全站=0，综艺=0（B站没给它顶级 tid，它只是娱乐的子分区 71）。
     *               它只用来"跟接口名字对齐"和"按 tid 去重"，**不参与取数**（取数看 [source]+[param]）。
     * @param param  [Source.UGC_RANK]/[Source.REGION_NEWLIST]/[Source.LEGACY_REGION_RANK] 传 rid；
     *               [Source.PGC_SEASON_RANK] 传 season_type。
     */
    data class Entry(
        val key: String,
        val name: String,
        val tid: Int,
        val source: Source,
        val param: Int,
    )

    /**
     * 左条的全部分区，顺序 = PiliPlus `RankType`（lib/models/common/rank_type.dart）的顺序。
     *
     * 两处例外（都在注释里标了原因）：
     *  - 「生活」排在汽车与美食之间：PiliPlus 排行榜页没有它，但 B站 region/index 有，按 B站自己的顺序插进去；
     *  - 「资讯」「剧情」排在最后：它们同样不在 PiliPlus 的表里，而且是仅有的两个"没有排行榜"的分区
     *    （资讯走分区最新投稿、剧情走旧版分区榜），放末尾不会打乱前面 21 项与官方排行页一致的顺序。
     */
    val entries: List<Entry> = listOf(
        // —— 以下顺序照 PiliPlus RankType：它是 21 项（全站…综艺），我们比它多一个 B站独有的「生活」 ——
        // 全站：接口不返回这一项，PiliPlus 自己加的第一项（rank_type.dart:2 rid: 0），实测 100 条
        Entry("tid0", "全站", 0, Source.UGC_RANK, 0),
        // 番剧 13：ranking/v2 回 -400，只有 PGC season_type=1 有（实测 99 条，首条「名侦探柯南（中配）」）
        Entry("tid13", "番剧", 13, Source.PGC_SEASON_RANK, 1),
        // 国创 167：同上，ranking/v2 回 -400；PGC season_type=4 实测 97 条（首条「凡人修仙传」）
        Entry("tid167", "国创", 167, Source.PGC_SEASON_RANK, 4),
        // 动画 1：ranking/v2 实测 95 条
        Entry("tid1", "动画", 1, Source.UGC_RANK, 1),
        // 音乐 3：96 条
        Entry("tid3", "音乐", 3, Source.UGC_RANK, 3),
        // 舞蹈 129：95 条
        Entry("tid129", "舞蹈", 129, Source.UGC_RANK, 129),
        // 游戏 4：96 条
        Entry("tid4", "游戏", 4, Source.UGC_RANK, 4),
        // 知识 36：95 条（注意离线 assets/region.json 里 36 还叫「科技」，那是 2020 年的旧名字，别被它带跑）
        Entry("tid36", "知识", 36, Source.UGC_RANK, 36),
        // 科技 188：97 条（tid=188，不是 36）
        Entry("tid188", "科技", 188, Source.UGC_RANK, 188),
        // 运动 234：97 条
        Entry("tid234", "运动", 234, Source.UGC_RANK, 234),
        // 汽车 223：94 条
        Entry("tid223", "汽车", 223, Source.UGC_RANK, 223),
        // 生活 160：94 条（PiliPlus 排行榜页没有这一项，B站有；按 B站顺序插在汽车与美食之间）
        Entry("tid160", "生活", 160, Source.UGC_RANK, 160),
        // 美食 211：97 条
        Entry("tid211", "美食", 211, Source.UGC_RANK, 211),
        // 动物圈 217：97 条
        Entry("tid217", "动物圈", 217, Source.UGC_RANK, 217),
        // 鬼畜 119：94 条
        Entry("tid119", "鬼畜", 119, Source.UGC_RANK, 119),
        // 时尚 155：95 条
        Entry("tid155", "时尚", 155, Source.UGC_RANK, 155),
        // 娱乐 5：93 条（注意它的榜单里天然含「综艺」子分区 tid=71 的稿件，见报告的"重复"一节）
        Entry("tid5", "娱乐", 5, Source.UGC_RANK, 5),
        // 影视 181：91 条（榜单里天然含「小剧场/剧情」tid=85 的稿件）
        Entry("tid181", "影视", 181, Source.UGC_RANK, 181),
        // 纪录片 177：走 PGC 榜口径（100 条，首条「生命奇观2」）；ranking/v2 也有 73 条，但那是单集投稿
        Entry("tid177", "纪录片", 177, Source.PGC_SEASON_RANK, 3),
        // 电影 23：PGC season_type=2 实测 100 条（ranking/v2 只有 61 条，且夹杂预告片）
        Entry("tid23", "电影", 23, Source.PGC_SEASON_RANK, 2),
        // 电视剧 11：PGC season_type=5 实测 100 条（ranking/v2 只有 72 条）
        Entry("tid11", "电视剧", 11, Source.PGC_SEASON_RANK, 5),
        // 综艺：region/index 没有它，只能合成一条；PGC season_type=7 实测 100 条（首条「KPL系列团综：时差五小时3」）
        // tid 给 0：B站没给它顶级 tid（它在娱乐下面叫 71），给 0 也让"按 tid 去重"不会误伤任何真分区
        Entry("pgc7", "综艺", 0, Source.PGC_SEASON_RANK, 7),
        // —— 以下是 B站 region/index 有、PiliPlus 排行榜页没有的两项，排在最后 ——
        Entry("tid202", "资讯", 202, Source.REGION_NEWLIST, 202),
        Entry("tid85", "剧情", 85, Source.LEGACY_REGION_RANK, 85),
    )

    /** tid → 表项（综艺的 tid=0，不参与反查，避免和「全站」的 tid=0 撞） */
    val byTid: Map<Int, Entry> = entries
        .filter { it.tid != 0 }
        .associateBy { it.tid }
}
