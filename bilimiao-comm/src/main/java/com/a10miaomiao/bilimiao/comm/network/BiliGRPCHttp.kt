package com.a10miaomiao.bilimiao.comm.network

import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import pbandk.Message
import pbandk.decodeFromByteArray
import pbandk.decodeFromStream
import pbandk.encodeToByteArray
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class BiliGRPCHttp<ReqT : Message, RespT : Message>(
    val method: GRPCMethod<ReqT, RespT>
) {

    companion object {
        private var baseUrl = ApiHelper.GRPC_BASE

        private val client = OkHttpClient()

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
        try {
            // 读取 gRPC frame header: 1 byte compression + 4 bytes big-endian length
            val header = ByteArray(5)
            var offset = 0
            while (offset < 5) {
                val read = inputStream.read(header, offset, 5 - offset)
                if (read == -1) throw IOException("gRPC header truncated")
                offset += read
            }
            val compressionFlag = header[0].toInt() and 0xFF
            val messageLength = ((header[1].toInt() and 0xFF) shl 24) or
                    ((header[2].toInt() and 0xFF) shl 16) or
                    ((header[3].toInt() and 0xFF) shl 8) or
                    (header[4].toInt() and 0xFF)

            if (compressionFlag != 0) {
                // 解压
                inputStream = GZIPInputStream(body.byteStream())
                inputStream.skip(5L) // 解压流从头开始，跳过原始header
            } else if (res.header(BiliHeaders.GRPCEncoding) == BiliHeaders.GRPCEncodingGZIP) {
                // fallback: server-level gzip
                inputStream = GZIPInputStream(body.byteStream())
                inputStream.skip(5L)
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

    suspend fun awaitCall(): RespT {
        miaoLogger().d(
            "name" to method.name,
            "reqMessage" to method.reqMessage
        )
        return suspendCoroutine { continuation ->
            val req = buildRequest()
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val respMessage = parseResponse(response)
                        continuation.resume(respMessage)
                    } catch (e: Exception) {
                        response.close()
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}
