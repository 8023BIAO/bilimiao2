package com.a10miaomiao.bilimiao.comm.live.danmaku

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * B 站**直播弹幕** WebSocket 的二进制协议编解码（第二阶段 B 路）。
 *
 * 本文件是**纯 JVM**：只用 `java.nio` / `java.util.zip`，不碰 Android、不碰 okhttp、不碰协程
 * —— 这样它可以脱离设备直接单测（方案 §5.1 要求的"能单独写单测"）。
 * 联网/连接/重连的部分在 [LiveDanmakuClient]。
 *
 * ### 包头（16 字节，**全大端**，方案 §2.4 实测 + 源码一致）
 * | 偏移 | 长度 | 字段 |
 * |---|---|---|
 * | 0 | 4 | totalLength（含包头自身） |
 * | 4 | 2 | headerLength（恒 16，**解析时按它算 body 起点，别写死**） |
 * | 6 | 2 | protoVer（0/1=明文，2=zlib，3=brotli） |
 * | 8 | 4 | operation |
 * | 12 | 4 | sequence（发送时自增，服务端不校验） |
 *
 * 源码依据：blbl `feature/live/LiveMessageClient.kt:332-368`
 * （`parsePackets` / `buildPacket` / `readInt` / `readShort` 全部 `ByteOrder.BIG_ENDIAN`）。
 */
object LiveDanmakuPacket {

    /** 包头固定长度。协议里 headerLength 字段理论上恒等于它（方案 §2.4） */
    const val HEADER_LEN = 16

    // ---------------- 操作码（方案 §2.4 操作码表 / BiliPai DanmakuProtocol.kt:34-38） ----------------

    /** 心跳，C→S，每 30s 一次 */
    const val OP_HEARTBEAT = 2

    /** 心跳回复，S→C，body 前 4 字节 = 人气值（uint32 BE） */
    const val OP_HEARTBEAT_REPLY = 3

    /** 普通消息，S→C，body 是 JSON（或压缩后的**嵌套包**） */
    const val OP_MESSAGE = 5

    /** 认证，C→S，body 是 JSON */
    const val OP_AUTH = 7

    /** 认证回复，S→C，body 是 JSON `{"code":0}` */
    const val OP_AUTH_REPLY = 8

    // ---------------- 协议版本 ----------------

    /** 明文 JSON */
    const val VER_PLAIN = 0

    /** 明文（认证包/心跳包/未压缩业务消息用它；op=3 的人气值也是明文） */
    const val VER_HEARTBEAT = 1

    /** zlib 压缩：**解压出来仍是同格式包，必须递归解** */
    const val VER_ZLIB = 2

    /** brotli 压缩：本客户端**不引依赖**，遇到就优雅跳过（见 [decode]） */
    const val VER_BROTLI = 3

    /**
     * 认证包里请求的协议版本。
     *
     * ★为什么是 2 而不是参考实现 blbl 的 3：方案 §2.4「压缩与嵌套」实测结论 ——
     * 抓 83 个包**一个压缩帧都没有**，且 `protover=2` 时服务端最多只用 zlib（JDK 自带），
     * 于是**可以完全不引入 `org.brotli:dec` 依赖**。填 3 才是"允许 brotli"，
     * 那会带来一个我们没处理的压缩分支。
     */
    const val AUTH_PROTOVER = 2

    /** 心跳 body：B 站 Web 端就是发这个字面量（blbl `LiveMessageClient.kt:164`） */
    private const val HEARTBEAT_TEXT = "[object Object]"

    private val EMPTY_BODY = ByteArray(0)

    /**
     * 单帧解出来的包。
     *
     * 注意 [body] 是 `ByteArray`，所以本类**不做** data class 的结构化相等
     * （数组相等要 `contentEquals`，写了容易误用），要比较请自己 `contentEquals`。
     */
    class Packet(
        val ver: Int,
        val op: Int,
        val seq: Int,
        val body: ByteArray,
    ) {
        override fun toString(): String = "Packet(ver=$ver, op=$op, seq=$seq, bodyLen=${body.size})"
    }

    // ------------------------------------------------------------------
    // 编码（C→S）
    // ------------------------------------------------------------------

    /**
     * 打一个 16 字节包头 + body。
     *
     * @param seq 序列号，由调用方自增（服务端不校验，但按协议填着更稳）
     */
    fun encode(op: Int, ver: Int, body: ByteArray = EMPTY_BODY, seq: Int = 1): ByteArray {
        val total = HEADER_LEN + body.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(total)                   // totalLength
        buf.putShort(HEADER_LEN.toShort())  // headerLength
        buf.putShort(ver.toShort())         // protoVer
        buf.putInt(op)                      // operation
        buf.putInt(seq)                     // sequence
        if (body.isNotEmpty()) buf.put(body)
        return buf.array()
    }

    /** 心跳包：op=2 / ver=1 / body=`"[object Object]"`（方案 §2.4 心跳包） */
    fun heartbeatPacket(seq: Int = 1): ByteArray =
        encode(OP_HEARTBEAT, VER_HEARTBEAT, HEARTBEAT_TEXT.toByteArray(Charsets.UTF_8), seq)

    /**
     * 认证包：op=7 / ver=1 / body=JSON（方案 §2.4 认证包）。
     *
     * body 形如
     * `{"uid":0,"roomid":7734200,"protover":2,"platform":"web","type":2,"key":"<token>"}`
     *
     * @param uid 登录时填 `DedeUserID`，**未登录填 0**（实测 uid=0 也能认证成功，方案 §2.4）
     */
    fun authPacket(
        roomId: Long,
        uid: Long,
        token: String,
        protover: Int = AUTH_PROTOVER,
        seq: Int = 1,
    ): ByteArray = encode(OP_AUTH, VER_HEARTBEAT, authBody(roomId, uid, token, protover), seq)

    /**
     * 单独暴露 body（不含包头），方便单测直接断言 JSON 文本。
     *
     * ★为什么不引 JSON 库手写这几行：token 是 base64 串（正常只有 `+ / =` 这些字符），
     *   但**没有任何协议保证它不含引号/反斜杠**；手写 + 转义 = 4 个字段的 body
     *   完全不依赖任何库，本文件才真正做到"纯 JVM 可单测"。
     */
    fun authBody(roomId: Long, uid: Long, token: String, protover: Int = AUTH_PROTOVER): ByteArray {
        val json = buildString(token.length + 80) {
            append("{\"uid\":").append(uid)
            append(",\"roomid\":").append(roomId)
            append(",\"protover\":").append(protover)
            append(",\"platform\":\"web\"")
            append(",\"type\":2")
            append(",\"key\":\"").append(jsonEscape(token)).append("\"}")
        }
        return json.toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------------
    // 解码（S→C）
    // ------------------------------------------------------------------

    /** 单帧里最多认这么多包：压缩帧理论上可以塞任意多条，加个上限防止畸形数据把内存打爆 */
    private const val MAX_PACKETS_PER_FRAME = 4096

    /** 嵌套解压的深度上限：正常最多两层（zlib → 明文包），再深必是畸形数据 */
    private const val MAX_DEPTH = 4

    /** zlib 解压后的体积上限（防解压炸弹） */
    private const val MAX_INFLATED_BYTES = 4 * 1024 * 1024

    /**
     * 把一帧原始数据解成**扁平**的包列表。
     *
     * - `ver=0/1` → 明文包，直接产出；
     * - `ver=2` → zlib 解压，**解压出来还是同格式包 → 递归**（方案 §2.4「压缩与嵌套」，
     *   对应 blbl `LiveMessageClient.kt:248-261`）；
     * - `ver=3` → brotli：**优雅跳过**（不抛异常、不崩、不重连），只回调一条日志。
     *   我们请求的是 `protover=2`，服务端不该发 brotli；真收到说明 B 站改了策略，
     *   这时"少收几条弹幕"远好过"整个连接炸掉"。
     *
     * 循环边界（防越界/防死循环）照 blbl `LiveMessageClient.kt:335-346`：
     * `off + 16 <= size` 且 `total in 16..(size-off)`。
     *
     * @param onSkip 跳过/异常时的说明回调（默认不做事，保持纯函数语义；客户端把日志传进来）
     */
    fun decode(data: ByteArray, onSkip: (String) -> Unit = {}): List<Packet> {
        val out = ArrayList<Packet>(8)
        decodeInto(data, out, onSkip, 0)
        return out
    }

    private fun decodeInto(
        data: ByteArray,
        out: MutableList<Packet>,
        onSkip: (String) -> Unit,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) {
            onSkip("嵌套解压超过 $MAX_DEPTH 层，丢弃该分支（size=${data.size}）")
            return
        }
        var off = 0
        val size = data.size
        while (off + HEADER_LEN <= size) {
            if (out.size >= MAX_PACKETS_PER_FRAME) {
                onSkip("单帧包数超过 $MAX_PACKETS_PER_FRAME，丢弃剩余部分")
                return
            }
            val total = readInt(data, off)
            if (total < HEADER_LEN || off + total > size) {
                // 半包/脏数据：停止解析整帧（不是异常，下一帧继续）
                onSkip("包长度非法 total=$total remain=${size - off}，停止解析本帧")
                return
            }
            val headerLen = readShort(data, off + 4)
            val ver = readShort(data, off + 6)
            val op = readInt(data, off + 8)
            val seq = readInt(data, off + 12)

            if (headerLen < HEADER_LEN || headerLen > total) {
                onSkip("headerLen=$headerLen 非法（total=$total），停止解析本帧")
                return
            }
            // ★body 起点用 headerLen 算（别写死 16）：协议将来加扩展头也不用改这里
            val bodyOff = off + headerLen
            val bodyLen = total - headerLen
            val body = if (bodyLen > 0) data.copyOfRange(bodyOff, bodyOff + bodyLen) else EMPTY_BODY

            when (ver) {
                VER_ZLIB -> {
                    val inflated = inflateZlib(body)
                    if (inflated == null) {
                        onSkip("ver=2 zlib 解压失败，丢弃该包（len=${body.size}）")
                    } else {
                        decodeInto(inflated, out, onSkip, depth + 1)
                    }
                }

                VER_BROTLI -> onSkip(
                    "ver=3 brotli 帧已跳过（len=${body.size}）：本客户端请求 protover=$AUTH_PROTOVER，" +
                        "只用 JDK 自带的 zlib，未引入 org.brotli:dec"
                )

                else -> out.add(Packet(ver = ver, op = op, seq = seq, body = body))
            }

            off += total
        }
        if (off != size) {
            // 尾部有 1~15 字节的残余（理论上不该出现），记一条便于排查
            onSkip("帧尾残余 ${size - off} 字节未解析")
        }
    }

    /**
     * op=3（心跳回复）的人气值：body 前 4 字节 uint32 BE（方案 §2.4 操作码表）。
     *
     * 不用 `ByteBuffer.wrap` 是为了直白：手动移位没有字节序歧义，也少一次对象分配。
     */
    fun popularity(body: ByteArray): Int {
        if (body.size < 4) return 0
        val value = readInt(body, 0)
        return if (value < 0) 0 else value
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 大端读 4 字节（blbl `LiveMessageClient.kt:362-364` 的等价物） */
    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /** 大端读 2 字节（无符号；blbl `LiveMessageClient.kt:366-368` 同样先 `and 0xFFFF`） */
    private fun readShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    /**
     * zlib 解压（`java.util.zip.Inflater`，JDK 自带，无需任何依赖）。
     *
     * @return 解压结果；失败（脏数据/数据被截断）返回 null —— 由调用方决定跳过，**绝不抛给上层**
     */
    private fun inflateZlib(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return EMPTY_BODY
        val inflater = Inflater()
        return try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size.coerceAtLeast(64) * 2)
            val buf = ByteArray(8 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                // n<=0 只可能是 needsInput（数据被截断）/ needsDictionary / finished
                // → 都该收工；不 break 会死循环
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_INFLATED_BYTES) return null
            }
            out.toByteArray()
        } catch (t: Throwable) {
            null
        } finally {
            inflater.end() // 必须 end()：Inflater 持有 native 内存，不收会被 GC 慢慢拖死
        }
    }

    /** 极简 JSON 字符串转义（只处理必须转义的字符） */
    private fun jsonEscape(raw: String): String {
        if (raw.none { it == '"' || it == '\\' || it.code < 0x20 }) return raw
        val sb = StringBuilder(raw.length + 8)
        for (ch in raw) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch.code < 0x20 -> sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
