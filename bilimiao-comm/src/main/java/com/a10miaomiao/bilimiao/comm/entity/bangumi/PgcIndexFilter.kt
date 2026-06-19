package com.a10miaomiao.bilimiao.comm.entity.bangumi

import kotlinx.serialization.Serializable

@Serializable
data class PgcIndexFilter(
    val seasonType: Int,
    val page: Int = 1,
    val pageSize: Int = 20,
    // 排序：0=综合排序, 1=最多播放, 2=最新发布, 3=最多收藏
    val order: Int = 0,
    // 状态：1=连载, 2=完结, -1=全部
    val st: Int = 1,
    // 完结：-1=全部, 0=未完结, 1=已完结
    val isFinish: Int = -1,
    // 付费：-1=全部, 0=免费, 1=付费
    val copyright: Int = -1,
    // 版本：-1=全部, 0=TV, 1=剧场版, 2=OVA, 3=其他
    val seasonVersion: Int = -1,
    // 语言：-1=全部, 1=国语, 2=日语, 3=英语
    val spokenLanguageType: Int = -1,
    // 地区：-1=全部, 1=中国, 2=日本, 3=美国
    val area: Int = -1,
    // 年份：-1=全部, 或具体年份如2024
    val year: Int = -1,
    // 月份：-1=全部, 或1-12
    val seasonMonth: Int = -1,
    // 季状态：-1=全部
    val seasonStatus: Int = -1,
    // 风格ID：-1=全部
    val styleId: Int = -1,
    // 据称固定值
    val sort: Int = 0,
    val type: Int = 1,
) {
    fun isDefault(): Boolean {
        return order == 0
                && st == 1
                && isFinish == -1
                && copyright == -1
                && seasonVersion == -1
                && spokenLanguageType == -1
                && area == -1
                && year == -1
                && seasonMonth == -1
                && seasonStatus == -1
                && styleId == -1
                && sort == 0
                && type == 1
    }
}
