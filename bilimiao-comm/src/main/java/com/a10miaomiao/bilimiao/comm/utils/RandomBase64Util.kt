package com.a10miaomiao.bilimiao.comm.utils

import android.util.Base64
import kotlin.random.Random

/**
 * 随机 base64 串 —— 给请求里的**假指纹**字段用（`dm_img_str` / `dm_cover_img_str`）。
 *
 * 逐条对齐 PiliPlus `lib/utils/utils.dart:48-59`：
 *  · 字节数在 `[minBytes, maxBytes]` 里随机取；
 *  · 每字节 = `0x26 + random(0x59)`（即 0x26~0x7E，全是可见 ASCII）——这组范围是上游
 *    刻意挑的：**保证不含 `%`(0x25)**，否则拼进 query 会被当成转义符；
 *  · 标准 base64（[Base64.NO_WRAP]：不换行、带 `=` 填充）之后**砍掉末尾 2 个字符**
 *    （PiliPlus 就是 `substring(0, length - 2)`，实际效果是去掉 `==` 填充）。
 *
 * **每次调用都是一串新值，不要缓存**：服务端看的就是"每次请求指纹不同"。
 * 不引第三方依赖：JDK 随机数 + Android 自带 base64（本模块 minSdk 21，用不了 java.util.Base64）。
 */
object RandomBase64Util {

    /** 共享实例即可（[Random.Default] 线程安全），不需要也不该按调用新建 */
    private val random = Random.Default

    /** 生成一串随机 base64；调用方给的长度下限要 ≥ 1（内部只按 `[minBytes, maxBytes]` 取） */
    fun string(minBytes: Int, maxBytes: Int): String {
        val byteCount = minBytes + random.nextInt(maxBytes - minBytes + 1)
        val bytes = ByteArray(byteCount) { (0x26 + random.nextInt(0x59)).toByte() }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return encoded.substring(0, encoded.length - 2)
    }
}
