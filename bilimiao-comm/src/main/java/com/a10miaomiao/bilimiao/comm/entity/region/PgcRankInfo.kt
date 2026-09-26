package com.a10miaomiao.bilimiao.comm.entity.region

import kotlinx.serialization.Serializable

/**
 * PGC 排行榜（番剧/国创/纪录片/电影/电视剧）返回。
 *
 * 为什么一个类吃两个接口：B站这两个接口的外壳不一样 ——
 *  - `/pgc/web/rank/list`         → `{"code":0,"result":{"list":[...]}}`  （番剧用这个）
 *  - `/pgc/season/rank/web/list` → `{"code":0,"data":{"list":[...]}}`    （其余 season_type 用这个）
 * PiliPlus 也是分两个函数解析的（lib/http/video.dart:900-943）。
 * 这里做成"两个字段都可选"，解析处用 [items] 取，少一份重复壳子；
 * 字段全部给了默认值：PGC 榜单偶尔会改结构（有的 season_type 没有 new_ep/rating），
 * 少一个可选字段不该让整个分区报错。
 */
@Serializable
data class PgcRankInfo(
    val code: Int = -1,
    val message: String = "",
    val result: PgcRankList? = null,
    val data: PgcRankList? = null,
) {
    val isSuccess get() = code == 0

    /** 两个外壳里那个非空的 list */
    val items: List<PgcRankItem>
        get() = result?.list ?: data?.list ?: emptyList()
}

@Serializable
data class PgcRankList(
    val list: List<PgcRankItem> = emptyList(),
)

@Serializable
data class PgcRankItem(
    val title: String = "",
    val cover: String = "",
    /** ss/ep 网页地址（如 https://www.bilibili.com/bangumi/play/ss33415），点击时交给统一路由 */
    val url: String = "",
    val season_id: Long = 0,
    /** 评分文字（"8.8分"），部分分区为空 */
    val rating: String = "",
    /** 与 new_ep.index_show 同义的更新文案（"更新至第1273话"），两个接口各给一个 */
    val desc: String = "",
    val new_ep: PgcNewEp? = null,
    val stat: PgcRankStat? = null,
) {
    /** "更新至第X话/第X期" */
    val updateText: String
        get() = new_ep?.index_show?.takeIf { it.isNotBlank() }
            ?: desc.takeIf { it.isNotBlank() }
            ?: ""
}

@Serializable
data class PgcNewEp(
    val cover: String = "",
    val index_show: String = "",
)

@Serializable
data class PgcRankStat(
    val view: Long = 0,
    val follow: Long = 0,
    val danmaku: Long = 0,
)
