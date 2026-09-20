package com.a10miaomiao.bilimiao.comm.network

object BiliHeaders {
    const val Bearer = "Bearer";
    const val Identify = "identify_v1";
    const val FormUrlEncodedContentType = "application/x-www-form-urlencoded";
    const val JsonContentType = "application/json";
    const val GRPCContentType = "application/grpc";
    const val UserAgent = "User-Agent";
    const val Referer = "Referer";
    const val AppKey = "APP-KEY";
    const val RequestedWith = "X-Requested-With"
    const val BiliMeta = "x-bili-metadata-bin";
    const val Authorization = "authorization";
    const val BiliDevice = "x-bili-device-bin";
    const val BiliNetwork = "x-bili-network-bin";
    const val BiliRestriction = "x-bili-restriction-bin";
    const val BiliLocale = "x-bili-locale-bin";
    const val BiliFawkes = "x-bili-fawkes-req-bin";
    const val BiliMid = "x-bili-mid";
    const val GRPCAcceptEncodingKey = "grpc-accept-encoding";
    // 只声明 identity 和 gzip：响应解析（BiliGRPCHttp.parseResponse）只会解 gzip。
    // 原来还写着 deflate，服务端真按 deflate 回一次，解析端就会把压缩字节当成 protobuf 解 →
    // 报一堆莫名其妙的解析异常（而不是"不支持的编码"）。
    const val GRPCAcceptEncodingValue = "identity,gzip";
    const val GRPCTimeOutKey = "grpc-timeout";
    const val GRPCTimeOutValue = "20100m";
    const val Envoriment = "env";
    const val TransferEncodingKey = "Transfer-Encoding";
    const val TransferEncodingValue = "chunked";
    const val TEKey = "TE";
    const val TEValue = "trailers";
    const val Buvid = "buvid"
    const val BiliTraceId = "x-bili-trace-id"
    const val BiliAuroraEid = "x-bili-aurora-eid"
    const val BiliAuroraZone = "x-bili-aurora-zone"
    const val BiliExpsBin = "x-bili-exps-bin"

    // content-encoding
    const val GRPCEncoding = "grpc-encoding"
    const val GRPCEncodingGZIP = "gzip"
}