package cn.a10miaomiao.bilimiao.compose.pages.search

import java.io.IOException

/**
 * 搜索页的错误文案翻译：把服务端 / gRPC / 网络层的**原文**翻成用户看得懂的一句话。
 *
 * ## 为什么要有这个文件（用户实测反馈）
 * 用户搜「8e6 新机」点进搜索页的直播 Tab，屏幕上写着「签名错误」—— 那是 `search_live` 回的
 * `code=-3` 原文，而真实原因是关键字里的半角空格让服务端复算签名对不上（已修，见
 * `LiveSearchAPI` 顶部 KDoc）。原来的提示与真实原因不符，必须换成对得上原因的说法。
 *
 * 结论：**服务端/框架的原文不能直接摊给用户** —— 它说的是"我们这边怎么想的"（签名、错误码、
 * HTTP、异常类名），不是"用户该怎么办"。这里只做一件事：把 [code] + [raw] 映射成一句和真实
 * 原因相符、并且告诉用户下一步能干什么的中文。
 *
 * ## 只映射，不改流程
 * 返回值只喂给 `FlowPaginationInfo.fail`（表现为 `ListStateBox` 里一行红字 + 「重试」按钮），
 * **不参与任何判断分支**：loading / finished / 重试 / 下拉刷新一律照旧。所以这里是纯函数、
 * 无副作用，也不吞异常、不改 code、不重试。
 *
 * ## 覆盖范围只有搜索页那几处
 * 全仓到处都用 `ListStateBox`（47 个调用方），动它 = 把所有页面的文案一起改，不在本次范围内；
 * 所以本文件只被搜索页的 6 个调用点使用：`pages/search/content/` 那 4 处（直播 Tab 的
 * `res.code` 分支与 `catch`、综合/番剧/UP主/影视/专栏的 `catch`、综合 Tab 的 `catch`）
 * 与 `pages/live/LiveSearchPage.kt` 那 2 处（`res.code` 分支与 `catch`）。
 */
internal fun searchErrorText(code: Int, raw: String): String {
    val text = raw.trim()
    return when (code) {
        // -3：服务端不认这串参数 —— 本次踩的就是它（关键字里的半角空格让签名复算对不上）。
        // 提示落在"用户能改的那件事"上（换个词），而不是"签名错误"这种用户无从下手的词。
        -3 -> "服务端不认这次的关键词或参数，换个关键词再试试"
        // -352 / -412：风控 / 请求被拦截（直播接口对频率敏感，LiveAPI 的注释里也记着 -352）；
        // -509 / -799：B 站另有两条"请求过于频繁"，对用户来说是同一件事，并进同一句
        -352, -412, -509, -799 -> "请求太频繁或触发了风控，稍等一会再试"
        -400 -> "请求参数不对，返回上一页重新搜索试试"
        -403 -> "没有权限访问，可能需要登录后再试"
        -404 -> "没有找到相关内容"
        // 其它 code：**保留原文 + 标注**（不丢信息，反馈问题时能对照），原文空白时给兜底
        else -> if (text.isEmpty()) "加载失败（错误码 $code）" else "$text（服务端错误码 $code）"
    }
}

/**
 * [Throwable] 版本：给 `catch (e: Exception)` 那一类分支用。
 *
 * 只分两种：
 * - **网络层**（`java.io.IOException` 及其子类）：okhttp 的连接失败/超时、`BiliGRPCHttp` 把非
 *   2xx 包出来的 `IOException("gRPC HTTP 412: …")` 都在内。对用户来说"连不上 / 超时 /
 *   服务端报错"是同一件事：检查网络，然后重试 —— 不把 `gRPC HTTP 412` 这种术语摊给他。
 * - **其它**（如反序列化失败）：与"其它 code"同一策略 —— 保留原文 + 标注，原文空白时给兜底。
 *
 * ★协程取消（`CancellationException`）不该走到这里：**基线就有**重抛的那两处
 *   （`SearchLiveContent` 的 loadPage、`LiveSearchPage` 的 loadPage）本轮继续保留那一行；
 *   另外两处（`SearchByTypeContent`、`SearchAllContent`）沿用改动前的写法、基线本来就没有重抛，
 *   行为未变。这里也不做特殊处理 —— 翻译函数不该有"吞掉取消"的副作用。
 */
internal fun searchErrorText(e: Throwable): String {
    if (e is IOException) return "网络请求失败，请检查网络后重试"
    val text = e.message.orEmpty().trim()
    return if (text.isEmpty()) "加载失败，请稍后重试" else "$text（未知错误）"
}
