package com.a10miaomiao.bilimiao.comm.network

import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import pbandk.Message
import pbandk.decodeFromByteArray
import pbandk.decodeFromStream
import pbandk.encodeToByteArray
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class BiliGRPCHttp<ReqT : Message, RespT : Message>(
    val method: GRPCMethod<ReqT, RespT>
) {

    companion object {
        private var baseUrl = ApiHelper.GRPC_BASE

        /** gRPC 错误所在的 HTTP/2 trailer 名（B 站：状态码与业务码都在这里，如 -404 会塞在 message） */
        private const val TRAILER_GRPC_STATUS = "grpc-status"
        private const val TRAILER_GRPC_MESSAGE = "grpc-message"
        private const val TRAILER_GRPC_STATUS_DETAILS = "grpc-status-details-bin"

        /** 从 grpc-message / details 原始字节里抠业务码用（形如 -404） */
        private val NEGATIVE_CODE_REGEX = Regex("-(\\d{1,6})")

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        inline fun <ReqT : Message, RespT : Message> request(methodGetter: () -> GRPCMethod<ReqT, RespT>)
            = BiliGRPCHttp(methodGetter())
    }

    var needToken = true

    private fun Request.Builder.addHeaders(): Request.Builder {
        val token = BilimiaoCommApp.commApp.loginInfo?.token_info?.access_token ?: ""
        if (needToken && token.isNotBlank()) {
            addHeader(BiliHeaders.Authorization, BiliHeaders.Identify + " " + token)
            BilimiaoCommApp.commApp.loginInfo?.token_info?.let{
                addHeader(BiliHeaders.BiliMid, it.mid.toString())
            }
        }
        addHeader(BiliHeaders.UserAgent, ApiHelper.USER_AGENT)
        addHeader(BiliHeaders.AppKey, BiliGRPCConfig.mobileApp)
        addHeader(BiliHeaders.BiliDevice, BiliGRPCConfig.getDeviceBin())
        addHeader(BiliHeaders.BiliFawkes, BiliGRPCConfig.getFawkesreqBin())
        addHeader(BiliHeaders.BiliLocale, BiliGRPCConfig.getLocaleBin())
        addHeader(BiliHeaders.BiliMeta, BiliGRPCConfig.getMetadataBin(token))
        addHeader(BiliHeaders.BiliNetwork, BiliGRPCConfig.getNetworkBin())
        addHeader(BiliHeaders.BiliRestriction, BiliGRPCConfig.getRestrictionBin())
        addHeader(BiliHeaders.GRPCAcceptEncodingKey, BiliHeaders.GRPCAcceptEncodingValue)
        addHeader(BiliHeaders.GRPCTimeOutKey, BiliHeaders.GRPCTimeOutValue)
        addHeader(BiliHeaders.Envoriment, BiliGRPCConfig.envorienment)
        addHeader(BiliHeaders.TransferEncodingKey, BiliHeaders.TransferEncodingValue)
        addHeader(BiliHeaders.TEKey, BiliHeaders.TEValue)
        addHeader(BiliHeaders.Buvid, BilimiaoCommApp.commApp.getBilibiliBuvid())
        addHeader(BiliHeaders.BiliTraceId, generateTraceId())
        addHeader(BiliHeaders.BiliAuroraEid, "")
        addHeader(BiliHeaders.BiliAuroraZone, "")
        addHeader(BiliHeaders.BiliExpsBin, BiliGRPCConfig.toBase64(byteArrayOf()))
        return this
    }

    private fun generateTraceId(): String {
        val randomId = UUID.randomUUID().toString().replace("-", "")
        val traceId = StringBuilder(32)
        traceId.append(randomId, 0, 24)
        var ts = System.currentTimeMillis() / 1000
        for (i in 2 downTo 0) {
            ts = ts shr 8
            val byteVal = ts % 256
            val b = if ((ts / 128) % 2 == 0L) {
                byteVal.toByte()
            } else {
                (byteVal - 256).toByte()
            }
            traceId.append(String.format("%02x", b.toInt() and 0xFF))
        }
        traceId.append(randomId[30])
        traceId.append(randomId[31])
        val result = traceId.toString()
        return "$result:${result.substring(16, 32)}:0:0"
    }

    private fun buildRequest(): Request {
        val url = baseUrl + method.name
        val messageBytes = method.reqMessage.encodeToByteArray()
        // gRPC frame header: 1 byte compression (0=uncompressed) + 4 bytes big-endian length
        val length = messageBytes.size
        val stateBytes = byteArrayOf(
            0,
            (length shr 24).toByte(),
            (length shr 16).toByte(),
            (length shr 8).toByte(),
            length.toByte(),
        )
        // 合并两个字节数组
        val bodyBytes = ByteArray(stateBytes.size + messageBytes.size)
        System.arraycopy(stateBytes, 0, bodyBytes, 0, stateBytes.size)
        System.arraycopy(messageBytes, 0, bodyBytes, stateBytes.size, messageBytes.size)

        val body = bodyBytes.toRequestBody(
            BiliHeaders.GRPCContentType.toMediaType()
        )
        return Request.Builder()
            .url(url)
            .addHeaders()
            .post(body)
            .build()
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun parseResponse(res: Response): RespT {
        if (!res.isSuccessful) {
            val errorBody = res.body?.string() ?: "no body"
            res.close()
            throw IOException("gRPC HTTP ${res.code}: $errorBody")
        }
        val body = res.body ?: throw IOException("gRPC response body is null (code=${res.code})")
        var inputStream = body.byteStream()
        // server-level gzip: 整个响应体压缩，需先解压再读取frame header
        if (res.header(BiliHeaders.GRPCEncoding) == BiliHeaders.GRPCEncodingGZIP) {
            inputStream = GZIPInputStream(inputStream)
        }
        try {
            // 读取 gRPC frame header: 1 byte compression + 4 bytes big-endian length
            val header = ByteArray(5)
            var offset = 0
            while (offset < 5) {
                val read = inputStream.read(header, offset, 5 - offset)
                if (read == -1) throw emptyBodyError(res)
                offset += read
            }
            val compressionFlag = header[0].toInt() and 0xFF
            val messageLength = ((header[1].toInt() and 0xFF) shl 24) or
                    ((header[2].toInt() and 0xFF) shl 16) or
                    ((header[3].toInt() and 0xFF) shl 8) or
                    (header[4].toInt() and 0xFF)

            if (compressionFlag != 0) {
                // gRPC frame-level compression: 仅消息体压缩，header已读取，直接包裹剩余流
                inputStream = GZIPInputStream(inputStream)
            }

            // 只读取 messageLength 字节的消息体
            val messageBytes = ByteArray(messageLength)
            var readOffset = 0
            while (readOffset < messageLength) {
                val read = inputStream.read(messageBytes, readOffset, messageLength - readOffset)
                if (read == -1) throw IOException("gRPC message body truncated")
                readOffset += read
            }
            return method.respMessageCompanion.decodeFromByteArray(messageBytes)
        } finally {
            inputStream.close()
            body.close()
        }
    }

    /**
     * HTTP 200、但**一个字节的 gRPC 帧体都没有**时的错误。
     *
     * ★ 这不是"服务端什么都没说"：B 站的 gRPC 错误放在 **HTTP/2 trailers** 里
     *   （`grpc-status` / `grpc-message`，业务码如 `-404` 直接塞在 grpc-message），body 是 0 字节。
     *   以前这里抛的是 `IOException("gRPC header truncated")` —— 开发者黑话，
     *   而 BiliFailBox 会把异常 message **逐字**当整页文案（2026-10-01 用户报障：
     *   时光机番剧条目点进去整页 "gRPC header truncated"）。
     *   现在：类型化抛 [GrpcStatusException]（调用方可判定"内容在当前接口里不存在"→ 走兜底），
     *   现场（httpCode / grpc-status / grpc-message / details / trailers）全部进日志。
     */
    private fun emptyBodyError(res: Response): IOException {
        val status = res.trailer(TRAILER_GRPC_STATUS)
        val message = res.trailer(TRAILER_GRPC_MESSAGE)
        val details = res.trailer(TRAILER_GRPC_STATUS_DETAILS)
        // 业务码：优先从 grpc-message 里取（B 站给的是 "-404" 这种）；
        // 取不到再从 details-bin（base64 的 bilibili.rpc.Status protobuf）的原始字节里找一眼，
        // **不引 protobuf 解析**、失败就当没有。
        val bizCode = NEGATIVE_CODE_REGEX.find(message.orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()?.unaryMinus()
            ?: details?.let { d ->
                runCatching {
                    String(Base64.decode(d, Base64.DEFAULT), Charsets.ISO_8859_1)
                }.getOrNull()
            }?.let { raw -> NEGATIVE_CODE_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.unaryMinus() }
        miaoLogger().d(
            "grpc-empty-body" to method.name,
            "httpCode" to res.code,
            "grpcStatus" to status,
            "grpcMessage" to message,
            // details-bin 很长（base64），拼成单个字符串：省掉一次 Pair 的类型推断
            "grpcStatusDetails=$details",
            // ★ OkHttp 5 的 trailers 是**函数**（trailers(): Headers），不是属性
            "trailers" to res.trailers().names().joinToString(","),
        )
        return GrpcStatusException(
            grpcStatus = status,
            grpcMessage = message,
            bizCode = bizCode,
            message = "服务端没有返回内容" + if (status.isNullOrBlank()) {
                ""
            } else {
                "（grpc-status=$status${if (message.isNullOrBlank()) "" else " $message"}）"
            },
        )
    }

    suspend fun awaitCall(): RespT {
        miaoLogger().d(
            "name" to method.name,
            "reqMessage" to method.reqMessage
        )
        return suspendCancellableCoroutine { continuation ->
            val req = buildRequest()
            val call = client.newCall(req)
            // 协程取消时把底层请求也取消：否则退出页面/切视频后 gRPC 仍会把整个响应体读完
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // 已经取消（页面退出/切视频）时继续 resume 没有意义
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    // ★ 取消与响应到达是竞态：continuation 已取消时 resume() 是空操作，
                    //   但 response 的 body 已经被打开，不关就是连接+字节流泄漏（连接池被占满）
                    if (continuation.isCancelled) {
                        response.close()
                        return
                    }
                    try {
                        val respMessage = parseResponse(response)
                        continuation.resume(respMessage)
                    } catch (e: Exception) {
                        response.close()
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}

/**
 * gRPC 层失败：**HTTP 200 + 空 body**，真实错误只在 HTTP/2 trailers 里（[grpcStatus] / [grpcMessage]）。
 *
 * 为什么要单独一个类型（而不是继续抛一句 `IOException("gRPC header truncated")`）：
 *   ① 调用方需要判定"内容在**这个**接口里不存在"（[isNotFound]）→ 才能走换接口/换页面的兜底，
 *      而不是把"服务端明确说没有"当成网络故障；
 *   ② 用户不该看到开发者黑话 —— BiliFailBox 会把异常 message 逐字当整页文案。
 * 原始现场（httpCode / grpc-status / grpc-message / details / trailers）在抛出前已进日志。
 */
class GrpcStatusException(
    val grpcStatus: String?,
    val grpcMessage: String?,
    /** 服务端业务码（B 站塞在 grpc-message 里，如 -404）；拿不到 = null */
    val bizCode: Int?,
    message: String,
) : IOException(message) {

    /** true = 这条内容在当前接口里不存在（业务码 -404 或 grpc-status 5/NOT_FOUND） */
    val isNotFound: Boolean get() = bizCode == -404 || grpcStatus == "5"
}
